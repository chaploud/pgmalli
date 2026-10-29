(ns docs-example
  "Rewrites docs/example/pgmalli/public.edn from docs/example/schema.sql, on a throwaway
   PostgreSQL as the tests start one. pgmalli.docs-example-test fails when the file drifts."
  (:require [pgmalli.generate :as generate]
            [pgmalli.test-db :as db]))

(db/with-postgres
  (fn []
    (db/exec-sql! (slurp "docs/example/schema.sql"))
    (println (generate/generate! {:db db/*db* :out-dir "docs/example/pgmalli"}))))
