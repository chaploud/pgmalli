(ns bb-suite
  "The test namespaces, and those that need next.jdbc, which bb test leaves out."
  (:require [clojure.string :as str]))

(def jvm-only
  #{'pgmalli.accept-test 'pgmalli.catalog-fuzz-test 'pgmalli.driver-types-test
    'pgmalli.oracle-test 'pgmalli.postgres-runs-test 'pgmalli.row-count-metamorphic-test 'pgmalli.row-keys-test 'pgmalli.sent-test 'pgmalli.upstream-test 'pgmalli.expression-types-test})

(defn namespaces
  "Every test/**/*_test.clj as a namespace symbol, sorted; with jvm? false, without jvm-only."
  [jvm?]
  (->> (file-seq (java.io.File. "test"))
       (map str)
       (filter #(str/ends-with? % "_test.clj"))
       (map #(symbol (-> (subs % (count "test/") (- (count %) (count ".clj")))
                         (str/replace "/" ".")
                         (str/replace "_" "-"))))
       (remove (if jvm? #{} jvm-only))
       sort))
