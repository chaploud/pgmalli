(ns pgmalli.honeysql-contract-test
  "What pgmalli.honeysql assumes of HoneySQL, against the HoneySQL on the test classpath: a
   HoneySQL that adds a clause or an operator, or writes a query differently, fails here before
   a user's query is judged on it."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is]]
            [honey.sql :as sql]
            [pgmalli.honeysql :as h]))

(def ^:private read-or-ignored (set/union @#'h/read-clauses @#'h/ignored-clauses))

(deftest every-honeysql-clause-is-read-ignored-or-reported
  ;; the ones check reports as :unread-clause, known; a clause new to HoneySQL lands here first
  (is (set/subset?
       (disj (set/difference (set (sql/clause-order)) read-or-ignored) ::mine)
       #{:alter-table :add-column :drop-column :alter-column :modify-column :rename-column :add-index :drop-index
           :rename-table :create-table :create-table-as :with-columns :create-view :create-or-replace-view
           :create-materialized-view :create-extension :drop-table :drop-view :drop-materialized-view :drop-extension
           :refresh-materialized-view :create-index :setting :raw :nest :table :assert :distinct :expr :exclude
           :rename :into :bulk-collect-into :patch-into :replace-into :delete :erase-from :truncate :join-by
           :qualify :facet :records :on-duplicate-key-update :with-data :since :until :compare-with :timeseries})
      "::mine is a-clause-of-yours-is-reported's, registered for good once it has run"))

(deftest honeysqls-operators-are-known-without-it
  ;; a name not an identifier (:->, :@>) is an operator by its shape; HoneySQL 2.4 wrote
  ;; [:mod a b] infix, later ones as a function
  (is (= #{} (set/difference (set (remove #(not (re-find #"^[A-Za-z_]" (name %))) @@#'sql/infix-ops))
                             @#'h/operators #{:mod}))))

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
  (is (= ["INSERT INTO t AS u (a) VALUES (?)" 1] (sql/format {:insert-into [[:t :u] [:a]] :values [[1]]}))))

(deftest a-clause-of-yours-is-reported
  (sql/register-clause! ::mine (fn [_ x] [(str "MINE " x)]) :where)
  (is (= [{:kind :unread-clause :clause ::mine}] (h/check {} {::mine 1})))
  (is (some #{{:kind :unread-clause :clause :table}} (h/check {} {:table :t}))))
