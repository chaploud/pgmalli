(ns pgmalli.data-test
  "Datasets and generated data as EDN files."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as tcg]
            [malli.core :as m]
            [pgmalli.data :as data]
            [pgmalli.sample :refer [opts registry]]))

(deftest a-dataset-kept-as-edn-comes-back-as-it-was
  (let [ds (tcg/generate (data/dataset-generator registry {:rows 3}) 30 11)
        path (str (java.io.File/createTempFile "pgmalli-ds" ".edn"))
        _ (data/write-dataset path ds)
        back (data/read-dataset path)]
    (is (= (update-vals ds vec) (update-vals back vec)) "java.time values round-trip under pgmalli's tags")
    (is (m/validate (data/dataset-schema registry) back opts))
    (is (nil? (data/short-tables ds)) "nothing short in the sample")
    (is (= (data/short-tables ds) (data/short-tables back)) "what came out short survives the file")
    (is (re-find #"#pgmalli/" (slurp path)) "the file carries the tags"))
  (let [bytes-ds {"public.t" [{:id 1 :b (byte-array [1 2 255])}]}
        path (str (java.io.File/createTempFile "pgmalli-bytes" ".edn"))]
    (data/write-dataset path bytes-ds)
    (is (= [1 2 -1] (seq (:b (first (get (data/read-dataset path) "public.t"))))) "bytes as hex")))

(deftest a-short-dataset-keeps-its-reasons-through-the-file
  (let [ds (with-meta {"public.t" [{:id 1}]} {:pgmalli/short {"public.t" {:wanted 3 :got 1 :reasons [["x" 2]]}}})
        path (str (java.io.File/createTempFile "pgmalli-short" ".edn"))]
    (data/write-dataset path ds)
    (is (= {"public.t" {:wanted 3 :got 1 :reasons [["x" 2]]}} (data/short-tables (data/read-dataset path))))
    (is (= {"public.t" [{:id 1}]} (data/read-dataset path)) "the tables alone are the value")))
