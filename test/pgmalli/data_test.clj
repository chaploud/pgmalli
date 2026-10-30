(ns pgmalli.data-test
  "Datasets and generated data as EDN files."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as tcg]
            [malli.core :as m]
            pgmalli.core
            [pgmalli.data :as data]
            pgmalli.impl.dataset
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

(deftest a-misspelt-option-or-an-unknown-table-is-an-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be spelled :rows" (data/dataset-generator registry {:row 3})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the registry does not: \[\"groups\"\]" (data/dataset-generator registry {:except #{"groups"}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be :nothing" (data/inserts registry {} {:on-conflict :ignore})))
  (is (some? (data/dataset-generator registry {:except nil})) "an :except of nil leaves nothing out"))

(deftest a-seed-gives-the-same-times-in-every-run
  (let [times (->> (vals (tcg/generate (data/dataset-generator registry {:rows 5}) 30 3))
                   (mapcat identity)
                   (mapcat vals)
                   (filter #(instance? java.time.temporal.Temporal %)))]
    (is (seq times))
    (is (every? #(= 2025 (.get ^java.time.temporal.Temporal % java.time.temporal.ChronoField/YEAR))
                (map #(if (instance? java.time.Instant %) (.atZone ^java.time.Instant % java.time.ZoneOffset/UTC) %) times))
        "times come from a fixed range, not from the clock")))

(deftest a-dataset-not-shaped-as-one-is-an-error-not-a-hang
  (doseq [ds [{"sample.users" [nil]} {"sample.users" {:id 1}} [1] 42]]
    (is (= :pgmalli.error/invalid-dataset (:pgmalli/error (ex-data (is (thrown? clojure.lang.ExceptionInfo (data/inserts registry ds))))))
        (pr-str ds))
    (is (thrown? clojure.lang.ExceptionInfo (data/write-dataset (str (java.io.File/createTempFile "pgmalli-bad" ".edn")) ds)))))

(deftest a-row-with-string-and-keyword-columns-writes
  (let [path (str (java.io.File/createTempFile "pgmalli-keys" ".edn"))]
    (data/write-dataset path {"public.t" [{:id 1 "a.b" 2}]})
    (is (= {"public.t" [{:id 1 "a.b" 2}]} (data/read-dataset path)))))

(deftest byte-array-keys-compare-by-their-bytes
  (is (= (pgmalli.impl.dataset/key-of {:k (byte-array [1])} [:k]) (pgmalli.impl.dataset/key-of {:k (byte-array [1])} [:k]))))

(deftest a-dataset-naming-a-table-the-registry-lacks-is-invalid
  (is (not (m/validate (data/dataset-schema registry) {"sample.grups" []} opts))))

(deftest an-instant-beyond-local-date-time-goes-to-postgresql-as-it-is
  (let [reg (pgmalli.core/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:tz [:time/instant {:pg/type "timestamp with time zone"}]]]}})]
    (is (= 1 (count (data/inserts reg {"public.t" [{:tz java.time.Instant/MAX}]}))))))

(deftest a-file-that-does-not-read-as-a-dataset-is-an-invalid-dataset
  (doseq [text ["{\"public.t\" [{:b #pgmalli/bytes \"abc\"}]}" "{\"public.t\" [{:b #pgmalli/bytes \"zz\"}]}"
                "{\"public.t\" [{:d #pgmalli/date 5}]}" "[1 2]" "{\"public.t\" [{:x #nope/tag 1}]}" "{\"public.t\" 3}"]]
    (let [path (str (java.io.File/createTempFile "pgmalli-bad" ".edn"))]
      (spit path text)
      (is (= :pgmalli.error/invalid-dataset
             (:pgmalli/error (ex-data (is (thrown? clojure.lang.ExceptionInfo (data/read-dataset path))))))
          text))))
