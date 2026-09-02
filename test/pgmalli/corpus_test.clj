(ns pgmalli.corpus-test
  "Every harvested expression parses."
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [pgmalli.impl.expr :as x]))

(def ^:private corpus (edn/read-string (slurp "test/corpus/harvested.edn")))

(deftest every-expression-parses
  (doseq [{:keys [kind sql]} (:expressions corpus)]
    (let [r (if (#{:check :domain} kind)
              (try {:expr (x/check-clause sql)} (catch Exception e {:error (ex-message e)}))
              (x/try-parse sql))]
      (is (nil? (:error r)) (str kind " " sql " -> " (:error r))))))
