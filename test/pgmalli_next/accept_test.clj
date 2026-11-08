(ns pgmalli-next.accept-test
  "pgmalli-next.impl.accept against the one sending and PostgreSQL themselves: the type a value of
   each class goes as, and each sent type prepared where a value of each type goes.
   JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as rs]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.jdbc :as pgjdbc]
            [pgmalli-next.impl.types :as types]
            [pgmalli-next.oracle :as oracle]
            [pgmalli-next.test-db :refer [generated *db* datasource exec-sql! with-postgres]])
  (:import (java.lang.reflect Array)
           (java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime)))

(use-fixtures :once with-postgres)

(def ^:private candidates
  "Classes besides class-types' an application may send."
  (concat (keys oracle/values)
          (for [e (concat (keys accept/boxed) (vals accept/boxed) [String java.math.BigDecimal java.math.BigInteger java.util.UUID (Class/forName "[B")
                                                     LocalDate LocalTime OffsetTime LocalDateTime OffsetDateTime Instant
                                                     java.sql.Date java.sql.Time java.sql.Timestamp java.util.Date])]
            (class (Array/newInstance ^Class e 0)))))

(def ^:private left-out
  "Classes the one sending sends that class-types leaves out, with why."
  {org.postgresql.util.PGobject "a PGobject names its type itself, value by value"
   java.sql.Timestamp "untyped, its text in the JVM's time zone, which a timestamp column's value changes with"
   java.sql.Date "untyped, its text at midnight of the JVM's time zone"
   java.sql.Time "untyped, its text with the JVM's offset"
   (class (Array/newInstance java.sql.Time 0)) "a timetz[] of the JVM's offset"})

(defn- type-name
  "t by the name types keys it by."
  [t]
  (if-let [e (types/element t)] (str (type-name e) "[]") (:name (types/entry t) t)))

(defn- sent-as
  "The type PostgreSQL gets v as, sent as the one sending sends it (the extension of
   pgmalli-next.jdbc/install-sending!, which pgmalli-next.test-db calls): :unknown for a value sent untyped, nil
   for one it cannot send."
  [con v]
  (with-open [ps (.prepareStatement con "SELECT pg_typeof(?)::text")]
    (when (try (prepare/set-parameter v ps 1) true (catch Exception _ false))
      (try (with-open [rs (.executeQuery ps)] (.next rs) (type-name (.getString rs 1)))
           (catch java.sql.SQLException e
             (if (= "42P18" (.getSQLState e)) :unknown (throw e)))))))

(deftest class-types-are-the-types-the-one-sending-sends
  (when *db*
    ;; pgjdbc sends a java.util.Map as an hstore where the database has one: the extension sends a map as jsonb before it
    (exec-sql! "CREATE EXTENSION IF NOT EXISTS hstore")
    (with-open [con (jdbc/get-connection (datasource))]
      (doseq [c (distinct (concat (map first accept/class-types) candidates [clojure.lang.PersistentVector clojure.lang.PersistentHashMap]))
              :let [v (case (.getName ^Class c) "clojure.lang.PersistentVector" [1] "clojure.lang.PersistentHashMap" (hash-map :a 1) (oracle/value c))]
              :when (not (left-out c))]
        (is (= (sent-as con v) (some (fn [[c t]] (when (instance? c v) t)) accept/class-types)) (.getName ^Class c)))
      (is (= :unknown (sent-as con :ns/a)) "a qualified keyword goes as its text, ns/a"))))

(def ^:private targets
  "[column type, the type accept/accepts? takes for it]: every type of pg_catalog a column may be
   of (not a pseudo-type or the catalog's own) and of types, an array of each, an enum, and a domain as its base type."
  (concat (for [t (distinct (concat (for [[t {:keys [category]}] (:types @types/catalog) :when (not (#{"P" "X" "Z"} category))] t)
                                    (keys types/types)))
                t [t (str t "[]")]]
            [t t])
          [["feel" [:enum "feel"]] ["positive" "integer"]]))

(def ^:private sents
  "Every type a value may be sent as: pgjdbc's, and any other :send names (a map as jsonb, an
   extension's) that pgmalli-next.impl.accept answers for (answers?), and an enum's and none."
  (distinct (concat (map second accept/class-types)
                    (filter #(and (string? %) (accept/answers? %)) (map first targets))
                    ["feel" :unknown])))

(defn- column [t] (str "c_" (str/replace (str/replace t "[]" "_array") #"\W" "_")))

(defn- verdicts
  "{:t :sent :context :taken}: whether PostgreSQL prepares a parameter of type sent where a
   value of each target's type goes, by context, with accepts? and holds? on it."
  []
  (exec-sql! (str (apply str (for [e (distinct (keep :extension (vals types/types)))] (str "CREATE EXTENSION IF NOT EXISTS " e ";")))
                  "CREATE TYPE feel AS ENUM ('ok', 'meh'); CREATE DOMAIN positive AS integer CHECK (VALUE > 0);"
                  "CREATE TABLE probe_target (" (str/join ", " (for [[t] targets] (str (column t) " " t))) ");"
                  ;; one round trip for every pair: an error other than a type error is thrown
                  "CREATE FUNCTION taken(sql text) RETURNS boolean LANGUAGE plpgsql AS $$ BEGIN"
                  " EXECUTE sql; DEALLOCATE probe; RETURN true;"
                  " EXCEPTION WHEN SQLSTATE '42883' OR SQLSTATE '42804' OR SQLSTATE '42846' OR SQLSTATE '42725' THEN RETURN false; END $$"))
  (let [pairs (for [[t accepted-t] targets, sent sents
                    [context sql] {:compare (str "SELECT 1 FROM probe_target WHERE " (column t) " = $1")
                                   :assign (str "INSERT INTO probe_target (" (column t) ") VALUES ($1)")
                                   :common (str "SELECT coalesce(" (column t) ", $1) FROM probe_target")
                                   :cast (str "SELECT CAST($1 AS " t ")")}]
                {:t t :accepted-t accepted-t :sent sent :context context
                 :sql (str "PREPARE probe(" (if (= :unknown sent) "unknown" sent) ") AS " sql)})]
    (with-open [con (jdbc/get-connection (datasource))]
      (map #(assoc %1 :taken (:taken %2)) pairs
           (jdbc/execute! con ["SELECT taken(s) AS taken FROM unnest(?::text[]) WITH ORDINALITY AS u(s, n) ORDER BY n"
                               (into-array String (map :sql pairs))]
                          {:builder-fn rs/as-unqualified-maps})))))

(def ^:private stricter-file "test/resources/pgmalli-next/stricter.edn")

(def ^:private untyped-classes
  "The classes the one sending sends untyped (a Keyword), whose text accept/reads? reads by class."
  (for [[c t] accept/class-types :when (= :unknown t)] c))

(defn- stricter
  "{[sent context] [t ...]}: of verdicts vs, where PostgreSQL takes a parameter sent compared or
   assigned that accept/takes? leaves out (docs/queries.md \"Stricter than PostgreSQL\"); one sent
   untyped by each class sent so, keyed by the class's name as a symbol, as reads? reads it by class."
  [vs]
  (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
        (update-vals (group-by (juxt :sent :context)
                               ;; a type accept answers not for (an extension's) takes any argument
                               (for [{:keys [accepted-t sent context taken] :as v} vs
                                     :when (and taken (not= :cast context) (accept/answers? accepted-t))
                                     c (if (= :unknown sent) untyped-classes [nil])
                                     :when (not (accept/takes? accepted-t (if (= "feel" sent) [:enum "feel"] sent) c context))]
                                 (cond-> v c (assoc :sent (symbol (.getName ^Class c))))))
                     #(vec (sort (map :t %))))))

(defn- triples [m] (set (for [[[sent context] ts] m t ts] [sent context t])))

(deftest accept-is-postgresqls
  (when *db*
    (let [vs (verdicts)
          compares-itself (set (for [{:keys [t sent context taken]} vs :when (and taken (= :compare context) (= t sent))] t))]
      (doseq [{:keys [t accepted-t sent context taken]} vs
              :let [sent-t (if (= "feel" sent) [:enum "feel"] sent)
                    pair (str (pr-str sent) " " context " " t)]]
        ;; accepts? may take what PostgreSQL refuses (PostgreSQL 18 casts bytea and the integers), never the
        ;; other way; a type it does not answer for (answers? false) types its arguments :any
        (when (and taken (accept/answers? accepted-t) (not= :common context)) (is (accept/accepts? accepted-t sent-t context) (str "PostgreSQL takes " pair)))
        ;; a type with no = (json, point, xml) compares with nothing, its own type neither
        (when (and (not= :cast context) (accept/holds? accepted-t sent-t context) (not (and (= :compare context) (not (compares-itself t)))))
          (is taken (str "PostgreSQL refuses " pair))))
      ;; the other way, where takes? is stricter than PostgreSQL, pinned: a change of it is a choice
      (let [now (stricter vs)]
        (if (System/getenv "PGMALLI_UPDATE_STRICTER")
          (spit stricter-file (with-out-str (println "{") (doseq [[k ts] now] (pr k) (print " ") (prn ts)) (println "}")))
          (let [was (triples (edn/read-string (slurp stricter-file)))
                [left-out held] (map #(sort-by pr-str %) [(remove was (triples now)) (remove (triples now) was)])]
            (is (and (empty? left-out) (empty? held))
                (str "where PostgreSQL takes a parameter, takes? leaves out otherwise than " stricter-file
                     " ([sent context column-type]); bb test:stricter rewrites it"
                     (when (seq left-out) (str "\n  now left out: " (str/join " " (map pr-str left-out))))
                     (when (seq held) (str "\n  now held: " (str/join " " (map pr-str held))))))))))))

(defn- stricter-rule
  "The id of the rule of docs/queries.md \"Stricter than PostgreSQL\" that leaves out a parameter
   sent compared or assigned to a column of type t: a value sent untyped, of another category; a
   value PostgreSQL converts on assignment alone, by a cast (an array's element by element, a
   domain's base) or through its text into a string type."
  [sent context t]
  (let [base #(if (= "positive" %) "integer" %)
        [s c] (if (and (types/element sent) (types/element t)) [(types/element sent) (types/element t)] [sent t])]
    (cond (symbol? sent) (when-not (accept/reads? (base c) (resolve sent) context) "stricter-untyped")
          (and (= :assign context)
               (or (contains? (set (types/cast-contexts s (base c))) "a") (accept/string-type? c)))
          "stricter-assignment")))

(deftest every-pair-stricter-than-postgresql-is-a-rule-of-the-docs
  (let [docs (slurp "docs/queries.md")
        pairs (triples (edn/read-string (slurp stricter-file)))]
    (is (= [] (vec (for [[sent context t] pairs :when (nil? (stricter-rule sent context t))] [sent context t])))
        "a pair stricter.edn pins that no rule of docs/queries.md explains: a refusal of what PostgreSQL takes")
    (doseq [id (distinct (keep #(apply stricter-rule %) pairs))]
      (is (str/includes? docs (str "<a id=\"" id "\"></a>")) id))))

(deftest a-jsonb-cast-to-a-scalar-type-takes-a-scalar-alone
  (when *db*
    (with-open [con (jdbc/get-connection (datasource))]
      (let [casts? (fn [doc t] (try (jdbc/execute! con [(str "SELECT CAST(?::jsonb AS " t ")") doc]) true
                                    (catch java.sql.SQLException _ false)))]
        (doseq [t ["integer" "numeric" "double precision" "boolean"]
                doc ["{\"a\": 1}" "[1]"]]
          (is (accept/accepts? t "jsonb" :cast) t)
          (is (not (casts? doc t)) (str doc " to " t))
          (is (accept/scalar-cast? t "jsonb") t))
        (doseq [t ["text" "character varying" "json" "jsonb"]
                doc ["{\"a\": 1}" "[1]"]]
          (is (casts? doc t) (str doc " to " t))
          (is (not (accept/scalar-cast? t "jsonb")) t))
        (is (casts? "1" "integer"))
        (is (casts? "true" "boolean"))))))

(deftest an-untyped-array-literal-cast-to-a-number-or-boolean-errors
  (when *db*
    (with-open [con (jdbc/get-connection (datasource))]
      (let [casts? (fn [s t] (try (with-open [ps (.prepareStatement con (str "SELECT CAST(? AS " t ")"))]
                                    (.setObject ps 1 s java.sql.Types/OTHER)
                                    (.close (.executeQuery ps))
                                    true)
                                  (catch java.sql.SQLException _ false)))]
        (doseq [t ["integer" "bigint" "numeric" "double precision" "money" "oid" "regclass" "boolean"]
                s ["{}" "{1}" "{1,2}" "{true}" "{pg_class}"]]
          (is (accept/scalar-cast? t :unknown) t)
          (is (not (casts? s t)) (str s " to " t)))
        ;; the date and time parser skips the braces
        (doseq [[s t] [["{2020-01-01}" "timestamp with time zone"] ["{2020-01-01}" "date"] ["{1 day}" "interval"]
                       ["{a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11}" "uuid"] ["{1}" "text"] ["{1}" "integer[]"]]]
          (is (not (accept/scalar-cast? t :unknown)) t)
          (is (casts? s t) (str s " to " t)))))))

(deftest the-schema-of-each-class-sent-takes-its-values
  ;; a value of every class the one sending sends (accept/values), a new class too
  (doseq [:let [registry (pgmalli/registry {:registry {}})]
          [_ _ c] (pgjdbc/write-rows {} false)
          :let [v (get accept/values c)]
          :when (some? v)]
    (is (some (fn [[s t]] (and (= t (pgjdbc/sent-type v {})) (m/validate s v {:registry registry}))) (pgjdbc/write-rows {} false))
        (str (class v) " goes as its type"))))

(defn- refusal
  "PostgreSQL's refusal of sqlvec sent as the one sending sends it: its SQLSTATE, nil where it runs it."
  [con sqlvec]
  (try (jdbc/execute! con sqlvec) nil
       (catch java.sql.SQLException e (.getSQLState e))))

(deftest a-parameter-postgresql-types-from-the-value-alone-takes-a-value-sent-typed
  ;; "any" (concat, format, jsonb_build_object): PostgreSQL types the parameter from what is sent
  (when *db*
    (let [registry (pgmalli/registry {:registry {}})
          values [1 "a" 1.5M true (java.util.UUID/randomUUID) (java.time.OffsetDateTime/now) {:a 1} [1] (long-array [1])
                  :a nil (java.time.Instant/now) (java.time.LocalDate/of 2026 1 1) (java.time.LocalDateTime/of 2026 1 1 0 0)]]
      (with-open [con (jdbc/get-connection (datasource))]
        (doseq [q [{:select [[[:concat :?x] :v]]} {:select [[[:format "%s" :?x] :v]]} {:select [[[:jsonb_build_object "k" :?x] :v]]}
                   {:select [[[:json_build_array 1 :?x] :v]]} {:select [[[:num_nulls :?x] :v]]}]
                :let [s (:x (:args (h/analyze registry q)))]
                v values
                :let [state (refusal con (sql/format q {:params {:x v}}))]]
          (testing (pr-str q (cond-> v (some-> v class .isArray) vec))
            (is (= (nil? state) (m/validate s v {:registry registry})) (str "PostgreSQL: " (or state "takes it")))
            (when state (is (= "42P18" state) "could not determine data type of parameter"))))))))

(deftest an-enum-takes-a-keyword-label-and-a-string-label-cast
  (when *db*
    (exec-sql! "CREATE TYPE sent_mood AS ENUM ('ok', 'meh'); CREATE TABLE sent_probe (e sent_mood)")
    (let [registry (pgmalli/registry (generated {:db *db*} "public"))]
      (with-open [con (jdbc/get-connection (datasource))]
        (doseq [q [{:select [:e] :from [:sent_probe] :where [:= :e :?x]}
                   {:insert-into :sent_probe :values [{:e :?x}]}
                   {:update :sent_probe :set {:e :?x}}
                   {:select [:e] :from [:sent_probe] :where [:= :e [:cast :?x :sent_mood]]}]
                v [:ok "ok"]
                :let [s (:x (:args (h/analyze registry q)))
                      state (do (.setAutoCommit con false) (try (refusal con (sql/format q {:params {:x v}})) (finally (.rollback con) (.setAutoCommit con true))))]]
          (testing (pr-str q v)
            (is (= (nil? state) (m/validate s v {:registry registry})) (str "PostgreSQL: " (or state "takes it")))))))))

(deftest the-docs-table-of-what-is-sent-is-accepts-class-types
  ;; docs/queries.md's "What your application sends": each row of classes and types written as
  ;; `Class` and `type`, one type for all its classes or one for each
  (let [section (second (re-find #"(?s)### What your application sends(.*?)\nAnother class" (slurp "docs/queries.md")))
        by-name (into {} (for [[c t] accept/class-types] [(.getSimpleName ^Class c) t]))
        rows (for [l (str/split-lines section)
                   :let [[_ cs ts] (re-matches #"\| (.*?) \| (.*?) \|" l)]
                   :when (and cs (re-find #"^`[A-Z]" cs))
                   :let [classes (map second (re-seq #"`([A-Za-z][\w.]*)`" cs))
                         sent (map second (re-seq #"`([a-z][\w ]*)`" ts))]
                   :when (and (seq sent) (not (str/starts-with? ts "untyped")))]
               [classes sent])]
    (is (< 3 (count rows)) "the table is found")
    (doseq [[classes sent] rows
            [c t] (map vector classes (if (next sent) sent (repeat (first sent))))]
      (is (= (types/canonical-name t) (types/canonical-name (by-name c))) (str c " in docs/queries.md")))))

(deftest every-class-with-a-schema-has-a-value-and-no-other
  (is (= (set (keys accept/values)) (set (keep (fn [[c _ s]] (when s c)) accept/classes)))))

(deftest each-unchanged-assignment-is-an-assignment-cast-of-the-catalog
  (doseq [[from to] accept/unchanged-assignments]
    (is (contains? (:casts @types/catalog) [from to "a"]) (str from " to " to))))


(def ^:private inlined
  "Values sql/format's :inline writes as a literal, at the edges of the types PostgreSQL gives them."
  [1 -2147483648 2147483647 2147483648 -9223372036854775808 9223372036854775807 9223372036854775808N
   (int 1) (short -1) 1N (biginteger 5) 1M 1.5M 1E+3M 1.5 1.0E20 (float 1.5) true "a" #uuid "00000000-0000-0000-0000-000000000001"])

(def ^:private not-inlined [##NaN ##Inf (java.time.LocalDate/of 2026 1 1) :a])

(deftest an-inlined-literal-is-of-the-type-postgresql-gives-it
  (when *db*
    (with-open [con (jdbc/get-connection (datasource))]
      (doseq [v inlined
              :let [[q] (sql/format {:select [[[:pg_typeof [:inline v]] :t]]})
                    t (:t (jdbc/execute-one! con [(str "SELECT t::text AS t FROM (" q ") AS s")]))]]
        (is (= (pgjdbc/inline-type v) (if (= "unknown" t) :unknown (types/canonical-name t))) (pr-str v)))
      (doseq [v not-inlined]
        (is (nil? (pgjdbc/inline-type v)) (str (pr-str v) ": " (first (sql/format-expr [:inline v]))))))))

(deftest inline-classes-type-each-value-as-inline-type-does
  (let [types-of (fn [v] (set (for [[c t s] pgjdbc/inline-classes :when (and (instance? c v) (m/validate s v))] t)))]
    (doseq [v inlined]
      (is (contains? (types-of v) (pgjdbc/inline-type v)) (pr-str v)))
    (doseq [v not-inlined]
      (is (empty? (types-of v)) (pr-str v)))))

(deftest every-pair-stricter-edn-pins-is-one-args-refuses
  ;; the record is of the decision, not of one input to it: a value of a class sent as the pair's
  ;; type, to a column of its column type (an array's too), is refused there; the pairs of a column
  ;; type no column schema of types/types makes, named
  (let [pairs (triples (edn/read-string (slurp stricter-file)))
        query {:assign {:update :t :set {:c :?x}} :compare {:select [:c] :from [:t] :where [:= :c :?x]}
               :common {:select [[[:coalesce :c :?x] :v]] :from [:t]}}
        column (fn [t] (cond (types/types t) (types/type-schema t)
                             (types/types (types/element t)) [:vector {:pg/type t} (types/type-schema (types/element t))]))
        value (fn [sent]
                (if (symbol? sent)
                  [(get accept/values (resolve sent))]
                  ;; a type no class goes as goes as a PGobject alone, whose type args reads not (DEBT.md)
                  (for [[c st] accept/class-types :when (= sent st) :let [v (get accept/values c)] :when (some? v)] v)))
        checked (for [[sent context t] pairs :when (column t)] [sent context t])]
    (is (empty? (remove #(or (column %) (= "positive" %) (#{"aclitem" "gtsvector" "refcursor"} (or (types/element %) %))) (map last pairs)))
        "a column type left unchecked is a domain of the test's (positive) or a type types/types lists not")
    (doseq [[sent context t] checked
            :let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:c (column t)]]}})]
            v (value sent)]
      (is (not (m/validate (:x (:args (h/analyze reg (query context)))) v {:registry reg}))
          (str (pr-str v) " " context " " t ": pinned in " stricter-file ", taken by args")))))
