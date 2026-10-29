(ns pgmalli.oracle-test
  "check reports nothing for a query PostgreSQL takes: the corpus and seeded mutants of it,
   against a real PostgreSQL, sent inlined and as parameters (see pgmalli.oracle; bb oracle for
   the misses as well). JVM only:
   babashka has no JDBC, so bb test does not list it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.files :as files]
            [pgmalli.oracle :as oracle]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once (fn [t] (with-postgres #(do (when *db* (exec-sql! oracle/schema-sql)) (t)))))

(deftest check-reports-nothing-postgresql-takes
  (when *db*
    (let [results (oracle/run *db* 42 3)]
      (is (< 500 (count results)) "the corpus and its mutants ran")
      (doseq [r (filter #(= :false-positive (:outcome %)) results)]
        (is false (str "PostgreSQL takes " (:id r) (some->> (:mutation r) (str " mutated by ")) " sent " (name (:format r))
                       "\n  SQL:    " (:sql r) (when (seq (:params r)) (str "\n  params: " (pr-str (:params r))))
                       "\n  check:  " (pr-str (:problems r))
                       "\n  re-run: bb oracle 42 3 (the case is under :false-positive)"))))))

(def ^:private wrappers
  "Places a query keeps its problems in: a FROM subquery, a CTE body, EXISTS."
  {:from #(hash-map :select [:*] :from [[% :sub]])
   :cte #(hash-map :with [[:w %]] :select [:*] :from [:w])
   :exists #(hash-map :select [1] :where [:exists %])})

(deftest a-query-wrapped-keeps-its-problems
  (when *db*
    (let [registry (pgmalli/registry (files/from-database {:db *db*} "public"))
          fmt {:format {:inline true}}
          problems #(set (map (fn [p] (dissoc p :in)) (remove (comp #{:unread-clause} :kind) (h/check registry % fmt))))]
      (doseq [{:keys [id mutation query]} (oracle/cases (oracle/columns *db*) 42 3)
              :when (and (map? query) (some query [:select :union :union-all :intersect :except]))
              :let [alone (problems query)]
              [wrap w] wrappers]
        (is (= alone (problems (w query)))
            (str id (some->> mutation (str " mutated by ")) " wrapped in " (name wrap) "\n  query: " (pr-str query)))))))
