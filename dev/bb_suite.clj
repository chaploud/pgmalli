(ns bb-suite
  "The test namespaces, and those that need next.jdbc, which bb test leaves out."
  (:require [clojure.string :as str]))

(def jvm-only
  #{'pgmalli-next.accept-test 'pgmalli-next.catalog-fuzz-test 'pgmalli-next.driver-types-test
    'pgmalli-next.oracle-test 'pgmalli-next.postgres-runs-test 'pgmalli-next.row-keys-test 'pgmalli-next.upstream-test 'pgmalli-next.expression-types-test})

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
