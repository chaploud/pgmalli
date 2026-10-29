(ns pgmalli.test-seed
  "The seed of the randomized tests, and how to run one again with it.")

(defn seed
  "PGMALLI_FUZZ_SEED, 42 without it (CI)."
  []
  (parse-long (or (System/getenv "PGMALLI_FUZZ_SEED") "42")))

(defn rerun
  "The command that runs the test var again with seed."
  [test-var seed]
  (str "re-run: PGMALLI_FUZZ_SEED=" seed " clojure -M:test -v " (symbol test-var)))
