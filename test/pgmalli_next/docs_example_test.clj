(ns pgmalli-next.docs-example-test
  "docs/example/pgmalli-next.edn, which the doc tests read, is what docs/example/schema.sql
   generates: bb docs:example rewrites it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli-next.generate :as generate]
            [pgmalli-next.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(deftest the-example-file-matches-the-example-schema
  (when *db*
    (exec-sql! (slurp "docs/example/schema.sql"))
    (is (map? (generate/check {:db *db* :out "docs/example/pgmalli-next.edn"})) "check throws where the file is stale")))
