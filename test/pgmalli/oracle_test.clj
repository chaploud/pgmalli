(ns pgmalli.oracle-test
  "check reports nothing for a query PostgreSQL takes: the corpus and seeded mutants of it,
   against a real PostgreSQL, sent inlined and as parameters (see pgmalli.oracle; bb oracle for
   the misses as well). JVM only:
   babashka has no JDBC, so bb test does not list it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli.oracle :as oracle]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(deftest check-reports-nothing-postgresql-takes
  (when *db*
    (exec-sql! oracle/schema-sql)
    (let [results (oracle/run *db* 42 3)]
      (is (< 500 (count results)) "the corpus and its mutants ran")
      (doseq [r (filter #(= :false-positive (:outcome %)) results)]
        (is false (str "PostgreSQL takes " (:id r) (some->> (:mutation r) (str " mutated by ")) " sent " (name (:format r))
                       "\n  SQL:    " (:sql r) (when (seq (:params r)) (str "\n  params: " (pr-str (:params r))))
                       "\n  check:  " (pr-str (:problems r))
                       "\n  re-run: bb oracle 42 3 (the case is under :false-positive)"))))))
