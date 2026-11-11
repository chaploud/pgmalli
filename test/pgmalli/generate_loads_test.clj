(ns pgmalli.generate-loads-test
  "What generating loads: clojure -X runs it with pgmalli's own dependencies, HoneySQL not among them."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

(defn- requires
  "The namespaces ns's ns form requires."
  [ns]
  (when-let [f (some #(io/resource (str (-> (name ns) (.replace "-" "_") (.replace "." "/")) %)) [".clj" ".cljc"])]
    (let [[_ _ & clauses] (binding [*read-eval* false] (read-string {:read-cond :allow} (slurp f)))]
      (for [[k & specs] clauses :when (= :require k)
            spec specs]
        (if (vector? spec) (first spec) spec)))))

(defn- reached [ns]
  (loop [todo [ns] seen #{}]
    (if-let [[n & more] (seq todo)]
      (if (seen n) (recur more seen) (recur (concat more (requires n)) (conj seen n)))
      seen)))

(deftest generating-loads-no-honeysql
  (is (not-any? #(= "honey" (first (.split (name %) "\\."))) (reached 'pgmalli.generate))))

(deftest reading-rows-loads-no-analysis
  (let [r (reached 'pgmalli.jdbc)]
    (is (not-any? r '[pgmalli.core pgmalli.impl.query pgmalli.impl.describe pgmalli.impl.registry clojure.test.check.generators]) (pr-str (sort r)))))
