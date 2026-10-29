(ns pgmalli.oracle
  "pgmalli.honeysql/check against PostgreSQL itself: the queries of oracle/corpus.edn, and
   mutants of them (a column, an alias or a table swapped, a qualifier dropped or added), are
   formatted with HoneySQL and EXPLAINed (then run, rolled back, for NOT NULL) on the database
   of oracle/schema.sql, and what PostgreSQL says is set beside what check reports. A mutant is
   drawn from a seeded java.util.Random, so a seed gives the same queries wherever it runs.

   Outcomes: :agree-ok, :agree-error, :false-positive (PostgreSQL takes it, check reports a
   problem: check's promise broken), :miss (PostgreSQL refuses it, check is silent: often a
   part check does not judge by design, code or an opaque table), :mismatch (both refuse, for
   different reasons), :other-silent / :other-flagged (PostgreSQL refuses it for something
   check does not judge, a type or syntax), :reported-by-design (PostgreSQL runs it, check
   reports a literal outside a CHECK's list, which matches no row).

   bb oracle [seed] [mutants-per-query] prints the counts and every case but the agreeing ones;
   pgmalli.oracle-test holds the false positives at none."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [honey.sql :as sql]
            [malli.generator :as mg]
            [next.jdbc :as jdbc]
            next.jdbc.result-set
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.files :as files]
            [pgmalli.test-db :as test-db])
  (:import (java.util Random)
           (org.postgresql.util PSQLException)))

(def schema-sql (slurp (io/resource "pgmalli/oracle/schema.sql")))
(def corpus (edn/read-string (slurp (io/resource "pgmalli/oracle/corpus.edn"))))

(def ^:private tables ["users" "groups" "posts" "tags" "post_tags" "active_users" "hosts"])
(def ^:private alias-ish #{"u" "g" "p" "s" "c" "x" "v" "t" "a" "b" "n" "h" "ins" "excluded" "EXCLUDED" "zz" "nosuch"})
(def ^:private row-keys #{:set :do-update-set :values})

;;; mutation

(defn- quals [q]
  (->> (tree-seq coll? seq q) (filter keyword?) (map name)
       (keep #(when (str/includes? % ".") (first (str/split % #"\.")))) set))

(defn- identifier? [known kw]
  (let [n (name kw)]
    (and (not (str/includes? n "*")) (not (str/starts-with? n "%")) (not (namespace kw))
         (every? known (str/split n #"\.")))))

(defn- candidates
  "[:val path] for an identifier keyword at path, [:key path k] for a key k of a row map at path."
  [q known]
  (letfn [(walk [x path row?]
            (cond (keyword? x) (when (identifier? known x) [[:val path]])
                  (map? x) (concat (when row? (for [k (keys x) :when (and (keyword? k) (identifier? known k))] [:key path k]))
                                   (mapcat (fn [[k v]] (walk v (conj path k) (contains? row-keys k))) x))
                  (vector? x) (mapcat (fn [i v] (walk v (conj path i) row?)) (range) x)))]
    (walk q [] false)))

(defn- mutant
  "[mutation query] drawn from q with rnd, or nil when q has no identifier to change."
  [^Random rnd columns q]
  (let [pick #(nth (vec %) (.nextInt rnd (count %)))
        pool (into columns ["nosuch" "n" "x" "a"])
        known (-> (set columns) (into tables) (into alias-ish) (into (quals q)))
        cs (vec (candidates q known))]
    (when (seq cs)
      (let [[kind path k] (pick cs)]
        (if (= :val kind)
          (let [n (name (get-in q path))
                [a b] (str/split n #"\.")
                qs (vec (into (quals q) (concat tables ["zz"])))
                [op n] (cond b (case (pick [:wrong-col :drop-qual :wrong-qual])
                                 :wrong-col [:wrong-col (str a "." (pick pool))]
                                 :drop-qual [:drop-qual b]
                                 :wrong-qual [:wrong-qual (str (pick qs) "." b)])
                             (and (some #{a} tables) (not (some #{a} columns))) [:wrong-table (pick (conj tables "nosuch"))]
                             :else (case (pick [:wrong-col :wrong-col :add-qual])
                                     :wrong-col [:wrong-col (pick pool)]
                                     :add-qual [:add-qual (str (pick qs) "." a)]))]
            [op (assoc-in q path (keyword n))])
          (let [m (get-in q path)
                swapped (-> m (dissoc k) (assoc (keyword (pick pool)) (get m k)))]
            [:wrong-key (if (seq path) (assoc-in q path swapped) swapped)]))))))

(defn cases
  "The corpus' queries and, for each, up to per-query distinct mutants drawn with seed."
  [columns seed per-query]
  (let [rnd (Random. (long seed))
        seen (volatile! #{})]
    (vec (for [[id q] corpus
               c (cons {:id id :query q}
                       (->> (repeatedly (* 4 per-query) #(mutant rnd columns q)) (remove nil?) distinct
                            (remove (fn [[_ m]] (= m q))) (take per-query)
                            (map-indexed (fn [i [op m]] {:id (keyword (str (name id) "--" i)) :query m :mutation op :base id}))))
               :when (when-not (@seen (:query c)) (vswap! seen conj (:query c)))]
           c))))

;;; PostgreSQL's verdict and check's

(defn- pg-kind [state message]
  (case state
    "42P01" :unknown-table
    "42703" :unknown-column
    "42702" :ambiguous-column
    "23502" :not-null-violation
    "428C9" :read-only-column
    (cond (and (= "22P02" state) (str/includes? message "enum")) :enum-literal
          ;; a parameter pgjdbc sent as varchar where the column's type takes none
          (and (#{"42883" "42804"} state) (str/includes? message "character varying")) :uncast-string
          :else :other)))

(defn- verdict
  "PostgreSQL's verdict on [sql & params]: :ok, or the kind of problem check would report for
   it, or :other."
  [^java.sql.Connection conn [sql & params :as sqlvec]]
  (try
    (jdbc/execute! conn (into [(str "EXPLAIN " sql)] params))
    (try (jdbc/execute! conn sqlvec) {:pg :ok}
         (catch PSQLException e
           (case (.getSQLState e)
             "23502" {:pg :not-null-violation :state "23502" :message (.getMessage e)}
             ;; a value outside a CHECK's list, which check reports as an enum literal
             "23514" {:pg :enum-literal :state "23514" :message (.getMessage e)}
             {:pg :ok})))
    (catch PSQLException e
      {:pg (pg-kind (.getSQLState e) (.getMessage e)) :state (.getSQLState e) :message (.getMessage e)})
    (finally (.rollback conn))))

;; PostgreSQL says 42P01 "missing FROM-clause entry for table x" where check says
;; {:kind :unknown-column :column :x.c}: one class, a name that resolves to nothing; and
;; 23502 for a NULL written into a NOT NULL column, given or left out
(defn- name-class [k]
  (cond (#{:unknown-table :unknown-column} k) :unknown-name
        (#{:missing-required-column :not-null-violation} k) :not-null-violation
        :else k))

(defn- outcome [pg problems]
  (let [pg (name-class pg)
        ks (set (map (comp name-class :kind) problems))]
    (cond (and (= :ok pg) (empty? ks)) :agree-ok
          ;; a literal outside a CHECK's list matches no row, which check reports by design
          ;; (docs/queries.md "Reported though PostgreSQL runs them")
          (and (= :ok pg) (= #{:enum-literal} ks)) :reported-by-design
          (= :ok pg) :false-positive
          (= :other pg) (if (empty? ks) :other-silent :other-flagged)
          (contains? ks pg) :agree-error
          (empty? ks) :miss
          :else :mismatch)))

(defn columns
  "The column names of the oracle schema, which mutants draw from."
  [db]
  (let [ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:postgresql://" (:host db) ":" (:port db) "/" (:db db)) :user (:user db) :password (:password db)})]
    (mapv :column_name (jdbc/execute! ds ["SELECT DISTINCT column_name FROM information_schema.columns WHERE table_schema = 'public' ORDER BY 1"]
                                      {:builder-fn next.jdbc.result-set/as-unqualified-maps}))))

(def formats
  "How a case is sent: its values inlined, and as HoneySQL sends them by default, parameters,
   which pgjdbc types as it does (a String as varchar)."
  {:inline {:inline true} :params {}})

(defn run
  "Every case's :sql, PostgreSQL's verdict, check's :problems (:unread-clause aside: PostgreSQL
   has none) and :outcome, on db (a pgmalli :db map of a database oracle/schema.sql is loaded
   into), once per formats entry (:format)."
  [db seed per-query]
  (let [url (str "jdbc:postgresql://" (:host db) ":" (:port db) "/" (:db db))
        ds (jdbc/get-datasource {:jdbcUrl url :user (:user db) :password (:password db)})
        registry (pgmalli/registry (files/from-database {:db db} "public"))]
    (with-open [conn (jdbc/get-connection ds)]
      (let [columns (columns db)]
        (jdbc/execute! conn ["SET statement_timeout = 3000"])
        (.setAutoCommit conn false)
        (vec (for [{:keys [query] :as c} (cases columns seed per-query)
                   [mode fmt] formats
                   :let [;; a named argument takes a value of the type arg-types gives it
                         args (into {} (for [[k s] (h/arg-types registry query {:format fmt})]
                                         [k (when-not (= :any s) (mg/generate s {:registry registry :seed seed :size 3}))]))
                         sqlvec (sql/format query (assoc fmt :params args))
                         v (verdict conn sqlvec)
                         problems (vec (remove #(= :unread-clause (:kind %)) (h/check registry query {:format fmt})))]]
               (merge c v {:format mode :sql (first sqlvec) :params (vec (rest sqlvec)) :problems problems :outcome (outcome (:pg v) problems)})))))))

(defn -main
  "bb oracle [seed] [mutants-per-query]: the run on a throwaway PostgreSQL (docker, as the tests
   start it), its counts and every case that does not agree."
  [& [seed per-query]]
  (let [seed (parse-long (or seed "42"))
        per-query (parse-long (or per-query "8"))]
    (test-db/with-postgres
      (fn []
        (test-db/exec-sql! schema-sql)
        (let [results (run test-db/*db* seed per-query)]
          (println "seed" seed "per query" per-query "cases" (count results))
          (pp/pprint (frequencies (map :outcome results)))
          (doseq [o [:false-positive :mismatch :miss :other-flagged]
                  :let [rs (filter #(= o (:outcome %)) results)]
                  :when (seq rs)]
            (println "\n=====" o (count rs))
            (doseq [r rs]
              (println (:id r) (or (:mutation r) "") (:format r) "\n  SQL:" (:sql r) (when (seq (:params r)) (pr-str (:params r)))
                       "\n  PostgreSQL:" (:pg r) (:state r) (some-> (:message r) (str/replace #"\n" " | "))
                       "\n  check:" (pr-str (:problems r))))))))
    (shutdown-agents)))
