(ns pgmalli.honeysql-robust-test
  "pgmalli.honeysql on queries broken at random: the oracle corpus with a part replaced by
   another shape. Every function returns data or throws ex-info, never another exception."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pgmalli.honeysql :as h]
            [pgmalli.sample :refer [registry]]
            [pgmalli.test-seed :as ts]))

(def ^:private corpus
  (mapv second (edn/read-string (slurp (io/resource "pgmalli/oracle/corpus.edn")))))

(defn- paths
  "The paths of every part of x, x's own included."
  [x]
  (cons [] (cond (map? x) (mapcat (fn [[k v]] (map #(cons k %) (paths v))) x)
                 (vector? x) (mapcat (fn [i v] (map #(cons i %) (paths v))) (range) x)
                 :else nil)))

(def ^:private part
  (gen/one-of [(gen/return nil) gen/keyword (gen/elements [:users :id :nick :* :?x :select :from :fields :columns])
               gen/small-integer gen/string-alphanumeric gen/symbol
               (gen/vector (gen/elements [:id :nick nil 1 :users]) 0 3)
               (gen/map (gen/elements [:select :from :where :fields :columns :values]) (gen/elements [:id [:id] nil {} 1]))
               (gen/return '(f x)) (gen/return {})]))

(def ^:private broken-query
  (gen/let [q (gen/elements corpus)
            p (gen/elements (vec (paths q)))
            v part]
    (if (seq p) (assoc-in q (vec p) v) v)))

(defn- crash
  "The exception other than ex-info f throws on q, nil when there is none."
  [f q]
  (try (f q) nil (catch clojure.lang.ExceptionInfo _ nil) (catch Throwable e e)))

(deftest a-broken-query-returns-data-or-throws-ex-info
  (let [opts {:schema "sample"}
        fs {'check #(h/check registry % opts) 'arg-types #(h/arg-types registry % opts)
            'row-schema #(h/row-schema registry % opts) 'query-schema #(h/query-schema registry [:x] % opts)}
        seed (ts/seed)
        result (tc/quick-check 1500 (prop/for-all [q broken-query] (not-any? #(crash % q) (vals fs))) :seed seed)]
    (is (:pass? result)
        (let [[q] (-> result :shrunk :smallest)]
          (str "seed " seed ", case " (:failing-size result) ", shrunk to\n  " (pr-str q) "\n"
               (apply str (for [[n f] fs :let [e (crash f q)] :when e]
                            (str "  h/" n ": " (.getName (class e)) " " (ex-message e) "\n    at " (or (first (filter #(.startsWith (.getClassName ^StackTraceElement %) "pgmalli") (.getStackTrace e))) (first (.getStackTrace e))) "\n")))
               "  " (ts/rerun #'a-broken-query-returns-data-or-throws-ex-info seed))))))

(deftest a-registry-that-is-none-is-said-so
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"registry" (h/check "public" {:select [:id] :from [:users]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"registry" (h/check nil {:select [:id] :from [:users]}))))
