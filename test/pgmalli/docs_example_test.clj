(ns pgmalli.docs-example-test
  "docs/example/pgmalli/public.edn, which the doc tests read, is what docs/example/schema.sql
   generates: bb docs:example rewrites it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli.generate :as generate]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(deftest the-example-file-matches-the-example-schema
  (when *db*
    (exec-sql! (slurp "docs/example/schema.sql"))
    (is (nil? (:stale (generate/check {:db *db* :out-dir "docs/example/pgmalli"}))))))
