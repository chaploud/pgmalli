(ns pgmalli.caches-test
  "What pgmalli holds in memory is of CONTRIBUTING.md#memory's kinds alone: a source that keeps
   values another way (memoize, an atom, a thread) is one this test names."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def ^:private held
  "Each way of keeping values or running work beyond a call, and where CONTRIBUTING.md#memory
   lets it be, as many times as listed."
  {"(memoize" {}
   "defonce" {}
   "(Thread. " {}
   "(future " {}
   "Semaphore" {}
   "(atom " {"memo.clj" 1     ; bounded's cache
             "query.clj" 2    ; the last schemas read, one call's ::calls
             "shape.clj" 1}}) ; the last registry map checked

(deftest a-value-is-kept-only-as-contributings-memory-lets-it-be
  (doseq [f (file-seq (io/file "src")) :when (str/ends-with? (str f) ".clj")
          [s allowed] held
          :let [n (count (re-seq (re-pattern (java.util.regex.Pattern/quote s)) (slurp f)))]
          :when (pos? n)]
    (is (= (get allowed (.getName f) 0) n) (str f ": " n " of " s))))
