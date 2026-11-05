(ns docs-example
  "Rewrites docs/example/pgmalli-next.edn from docs/example/schema.sql, on a throwaway
   PostgreSQL as the tests start one. pgmalli-next.docs-example-test fails when the file drifts."
  (:require [pgmalli-next.generate :as generate]
            [pgmalli-next.test-db :as db]))

(db/with-postgres
  (fn []
    (db/exec-sql! (slurp "docs/example/schema.sql"))
    (generate/generate! {:db db/*db* :out "docs/example/pgmalli-next.edn"})))
