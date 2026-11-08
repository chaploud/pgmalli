(ns pgmalli-next.honeysql-contract-test
  "What pgmalli-next.honeysql assumes of HoneySQL, against the HoneySQL on the test classpath: a
   HoneySQL that adds a clause or an operator, or writes a query differently, fails here before
   a user's query is judged on it."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [honey.sql :as sql]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.test-db :as test-db]))

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
  [["read-clauses in src/pgmalli_next/impl/hsyntax.clj" syntax/read-clauses]
   ["ignored-clauses in src/pgmalli_next/impl/hsyntax.clj" syntax/ignored-clauses]
   ["reported-clauses in test/pgmalli_next/honeysql_contract_test.clj" reported-clauses]])

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
                        "read-clauses or ignored-clauses in src/pgmalli_next/impl/hsyntax.clj, or reported-clauses in "
                        "test/pgmalli_next/honeysql_contract_test.clj")))))

(deftest every-clause-pgmalli-lists-is-honeysqls
  (when-not (older-than-pinned?)
    (doseq [[place clauses] clause-lists, c (sort (set/difference clauses (set (sql/clause-order))))]
      (is (nil? c) (str honeysql " no longer has clause " c ": remove it from " place)))))

(deftest operator-syntax-is-honeysqls-special-syntax
  ;; HoneySQL writes :in and :not-in in format-expr, outside its table
  (when-not (older-than-pinned?)
    (doseq [op (sort (set/difference @#'syntax/operator-syntax (set (keys @@#'sql/special-syntax)) syntax/in-ops))]
      (is (nil? op) (str honeysql " no longer has special syntax " op ": remove it from operator-syntax in src/pgmalli_next/impl/hsyntax.clj")))))

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
  ;; :nrql and :quoted-always are newer than the oldest HoneySQL README supports
  (when (try (sql/format :x {:dialect :nrql}) (catch Exception _ nil))
    (is (= ["null"] (sql/format :null {:dialect :nrql})) ":nrql does not quote"))
  (when (= ["\"x\""] (sql/format :x {:quoted-always #"^x$"}))
    (is (= ["\"null\""] (sql/format :null {:quoted-always #"^null$"}))))
  (is (= ["NULL"] (sql/format :NULL)) "a keyword is written as spelt")
  (is (= ["INSERT INTO t AS u (a) VALUES (?)" 1] (sql/format {:insert-into [[:t :u] [:a]] :values [[1]]}))))

(deftest join-by-keys-are-honeysqls-join-by-aliases
  (let [theirs (some-> (resolve 'honey.sql/join-by-aliases) deref)
        written (fn [clause v] (first (sql/format {:select [:a] :from [:t] clause v})))]
    (doseq [a (sort (keys (merge theirs syntax/join-by-keys)))
            :let [k (get syntax/join-by-keys a a)
                  v (if (= :cross-join k) [:j] [:j true])]]
      (is (= (written :join-by [a v]) (written k v))
          (str honeysql " writes :join-by's " a " otherwise than " k ": update join-by-keys in src/pgmalli_next/impl/hsyntax.clj")))))

(deftest null-test-is-the-is-null-honeysql-writes
  (is (= [[:a false] [:a false] [:a true] [:a true] [:a false] [:a true] [:a false]]
         (map #(syntax/null-test % nil) [[:= :a nil] [:= nil :a] [:<> :a nil] [:not= nil :a] [:is :a nil] [:is-not :a nil] [:is :a [:inline nil]]])))
  (is (= [nil nil nil nil nil] (map #(syntax/null-test % nil) [[:= :a :null] [:= :a [:inline nil]] [:< :a nil] [:is nil :a] [:= :a :b]]))
      "a = NULL compares, NULL IS a is no SQL")
  (is (nil? (syntax/null-test [:is :a :null] {:quoted true})) "under :quoted :null is a column"))

(deftest sql-format-writes-values-maps-as-write-problems-reads-them
  (let [message (str honeysql " writes :values maps differently: update write-problems in src/pgmalli_next/impl/problems.clj, "
                     "which takes a key one map lacks and another has as NULL, DEFAULT under :values-default-columns")]
    (is (= ["INSERT INTO t (b, a) VALUES (NULL, ?), (?, NULL)" 1 2] (sql/format {:insert-into :t :values [{:a 1} {:b 2}]}))
        message)
    (is (= ["INSERT INTO t (b, a) VALUES (DEFAULT, ?), (?, NULL)" 1 2]
           (sql/format {:insert-into :t :values [{:a 1} {:b 2}]} {:values-default-columns #{:b}}))
        message)))

(deftest a-clause-of-yours-is-reported
  (sql/register-clause! ::mine (fn [_ x] [(str "MINE " x)]) :where)
  (is (= [{:kind :unread-clause :clause ::mine}] (:problems (h/analyze {} {::mine 1}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"table public.t is unknown" (h/analyze {} {:table :t})) "TABLE t, read as SELECT * FROM t"))

(deftest sql-format-writes-a-value-in-a-row-as-in-a-row-and-written-otherwise-say
  ;; honeysql.clj's in-a-row (a keyword a column, a vector a row of parameters, a map a subquery),
  ;; hsyntax/one-written's [x] as x,
  ;; and problems' :written-otherwise of [:in x a b], which drops b
  (let [message (str honeysql " writes a row's values or :in otherwise: update in-a-row in src/pgmalli_next/honeysql.clj and written-otherwise in src/pgmalli_next/impl/problems.clj")]
    (is (= "INSERT INTO t (a, c, d, e) VALUES (b, (?, ?), (SELECT ?), (?))" (first (sql/format {:insert-into :t :values [{:a :b :c [1 2] :d {:select [1]} :e [1]}]}))) message)
    (is (= ["a IN ?" 1] (sql/format-expr [:in :a 1 2])) message)))
