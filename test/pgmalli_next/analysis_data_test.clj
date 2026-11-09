(ns pgmalli-next.analysis-data-test
  "An analysis's types are data: printed, they read back equal, as an application caches them."
  (:require [clojure.edn :as edn]
            [clojure.test :as t :refer [deftest is]]
            [pgmalli-next.honeysql :as h]
            pgmalli-next.honeysql-test))

(deftest every-analysis-of-the-honeysql-tests-reads-back-equal
  (let [analyze h/analyze
        results (atom [])
        ;; the tests run again here, their own reports aside
        counters @t/*report-counters*]
    ;; the types, not the arguments' keys, which are the query's own (:?~)
    (with-redefs [h/analyze (fn [& args] (let [r (apply analyze args)] (swap! results conj [(vec (vals (:args r))) (:row r)]) r))]
      (binding [t/*test-out* (java.io.StringWriter.)]
        (t/test-vars (filter (comp :test meta) (vals (ns-interns 'pgmalli-next.honeysql-test))))))
    (dosync (ref-set t/*report-counters* counters))
    (is (< 1000 (count @results)))
    (let [unread (remove #(= % (try (edn/read-string (pr-str %)) (catch Exception _ ::unreadable))) @results)]
      (is (empty? unread) (pr-str (first unread))))))
