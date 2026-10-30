(ns pgmalli.honeysql-contract-test
  "What pgmalli.honeysql assumes of HoneySQL, against the HoneySQL on the test classpath: a
   HoneySQL that adds a clause or an operator, or writes a query differently, fails here before
   a user's query is judged on it."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [honey.sql :as sql]
            [pgmalli.honeysql :as h]
            [pgmalli.test-db :as test-db]))

(def ^:private honeysql (str "HoneySQL " (or (test-db/version "honeysql") "(version unknown)")))

(def ^:private reported-clauses
  "HoneySQL's clauses check reports as :unread-clause."
  #{:alter-table :add-column :drop-column :alter-column :modify-column :rename-column :add-index :drop-index
    :rename-table :create-table :create-table-as :with-columns :create-view :create-or-replace-view
    :create-materialized-view :create-extension :drop-table :drop-view :drop-materialized-view :drop-extension
    :refresh-materialized-view :create-index :setting :raw :nest :table :assert :distinct :expr :exclude
    :rename :into :bulk-collect-into :patch-into :replace-into :delete :erase-from :truncate :join-by
    :qualify :facet :records :on-duplicate-key-update :with-data :since :until :compare-with :timeseries})

(def ^:private clause-lists
  [["read-clauses in src/pgmalli/honeysql.clj" @#'h/read-clauses]
   ["ignored-clauses in src/pgmalli/honeysql.clj" @#'h/ignored-clauses]
   ["reported-clauses in test/pgmalli/honeysql_contract_test.clj" reported-clauses]])

(defn- older-than-pinned?
  "Whether the HoneySQL loaded is older than the one deps.edn pins (CI's floor job): a clause
   or syntax that came later is missing there, not gone."
  []
  (let [parse #(mapv parse-long (re-seq #"\d+" %))
        pinned (get-in (edn/read-string (slurp "deps.edn")) [:aliases :test :extra-deps 'com.github.seancorfield/honeysql :mvn/version])
        loaded (test-db/version "honeysql")]
    (and loaded (neg? (compare (parse loaded) (parse pinned))))))

(deftest every-honeysql-clause-is-read-ignored-or-reported
  (let [known (apply set/union (map second clause-lists))]
    (doseq [c (sort (disj (set/difference (set (sql/clause-order)) known) ::mine))]
      (is (nil? c) (str honeysql " registers clause " c " that pgmalli neither reads, ignores nor reports: add it to "
                        "read-clauses or ignored-clauses in src/pgmalli/honeysql.clj, or reported-clauses in "
                        "test/pgmalli/honeysql_contract_test.clj")))))

(deftest every-clause-pgmalli-lists-is-honeysqls
  (when-not (older-than-pinned?)
    (doseq [[place clauses] clause-lists, c (sort (set/difference clauses (set (sql/clause-order))))]
      (is (nil? c) (str honeysql " no longer has clause " c ": remove it from " place)))))

(def ^:private word-operator? #(re-find #"^[A-Za-z_]" (name %)))

(def ^:private honeysql-special (set (keys @@#'sql/special-syntax)))

(deftest honeysqls-operators-are-known-without-it
  ;; a name not an identifier (:->, :@>) is an operator by its shape; HoneySQL 2.4 wrote
  ;; [:mod a b] infix, later ones as a function
  (doseq [op (sort (set/difference (set (filter word-operator? @@#'sql/infix-ops)) @#'h/operators #{:mod}))]
    (is (nil? op) (str honeysql " has infix operator " op " that pgmalli does not know: add it to operators in src/pgmalli/honeysql.clj"))))

(deftest every-operator-pgmalli-lists-is-honeysqls
  ;; HoneySQL writes :in and :not-in in format-expr, outside both tables
  (when-not (older-than-pinned?)
    (doseq [op (sort (set/difference @#'h/operators (set @@#'sql/infix-ops) honeysql-special #{:in :not-in}))]
      (is (nil? op) (str honeysql " no longer has operator " op ": remove it from operators in src/pgmalli/honeysql.clj")))))

(def ^:private named-by-expression-name
  "HoneySQL's special syntax expression-name in src/pgmalli/honeysql.clj names itself, or that
   is not PostgreSQL's."
  #{:. :array :case :case-expr :cast :.:. :bigquery/array :bigquery/struct})

(deftest honeysqls-special-syntax-is-known
  (doseq [k (sort-by str (set/difference honeysql-special @#'h/special-syntax @#'h/operators named-by-expression-name))]
    (is (nil? k) (str honeysql " has special syntax " k " that pgmalli does not know: add it to special-syntax in "
                      "src/pgmalli/honeysql.clj (not a function call) or operators (an operator in SQL)"))))

(deftest every-special-syntax-pgmalli-lists-is-honeysqls
  (when-not (older-than-pinned?)
    (doseq [k (sort (set/difference @#'h/special-syntax honeysql-special))]
      (is (nil? k) (str honeysql " no longer has special syntax " k ": remove it from special-syntax in src/pgmalli/honeysql.clj")))))

(deftest sql-format-writes-what-pgmalli-reads
  (is (= ["SELECT a FROM t INNER JOIN j ON TRUE LEFT JOIN l ON TRUE RIGHT JOIN r ON TRUE INNER JOIN i ON TRUE FULL JOIN f ON TRUE"]
         (sql/format {:select [:a] :from [:t] :full-join [:f true] :inner-join [:i true] :right-join [:r true] :left-join [:l true] :join [:j true]}))
      "the joins in join-keys' order")
  (is (= ["SELECT a FROM t WHERE (a IN (?, ?)) AND (b IN ())" 1 2] (sql/format {:select [:a] :from [:t] :where [:and [:in :a :?xs] [:in :b :?ys]]} {:params {:xs #{1 2} :ys []}}))
      ":in expands a collection; IN () is what an empty one gives")
  (is (= ["SELECT a FROM t WHERE a IN ?" nil] (sql/format {:select [:a] :from [:t] :where [:in :a :?xs]} {:params {:xs nil}})))
  (is (= ["SELECT a FROM t WHERE (a = ?) AND (b = ?) AND (c = ?)" 1 2 3]
         (sql/format {:select [:a] :from [:t] :where [:and [:= :a :?x] [:= :b '?y] [:= :c [:param :z]]]} {:params {:x 1 :y 2 :z 3}}))
      "three spellings of a named parameter, keyed without ?")
  (is (= ["SELECT a AS b, COUNT(*), MAX(id) FROM t"] (sql/format {:select [[:a :b] [[:count :*]] :%max.id] :from [:t]}))
      "[:a :b] is a AS b; :%f.args calls f")
  (is (= ["\"null\""] (sql/format :null {:dialect :ansi})) "a dialect quotes :null")
  (is (= ["null"] (sql/format :null {:dialect :ansi :quoted false})) "unless :quoted false")
  (is (= ["null"] (sql/format :null {:dialect :ansi :quoted nil})))
  (is (= ["null"] (sql/format :null {:dialect :nrql})) ":nrql does not quote")
  (is (= ["\"null\""] (sql/format :null {:quoted-always #"^null$"})))
  (is (= ["NULL"] (sql/format :NULL)) "a keyword is written as spelt")
  (is (= ["INSERT INTO t AS u (a) VALUES (?)" 1] (sql/format {:insert-into [[:t :u] [:a]] :values [[1]]}))))

(deftest sql-format-writes-values-maps-as-write-problems-reads-them
  (let [message (str honeysql " writes :values maps differently: update write-problems in src/pgmalli/honeysql.clj, "
                     "which takes a key one map lacks and another has as NULL, DEFAULT under :values-default-columns")]
    (is (= ["INSERT INTO t (b, a) VALUES (NULL, ?), (?, NULL)" 1 2] (sql/format {:insert-into :t :values [{:a 1} {:b 2}]}))
        message)
    (is (= ["INSERT INTO t (b, a) VALUES (DEFAULT, ?), (?, NULL)" 1 2]
           (sql/format {:insert-into :t :values [{:a 1} {:b 2}]} {:values-default-columns #{:b}}))
        message)))

(deftest a-clause-of-yours-is-reported
  (sql/register-clause! ::mine (fn [_ x] [(str "MINE " x)]) :where)
  (is (= [{:kind :unread-clause :clause ::mine}] (h/check {} {::mine 1})))
  (is (some #{{:kind :unread-clause :clause :table}} (h/check {} {:table :t}))))
