(ns pgmalli.data
  "Datasets: several tables of rows at once, checked and generated against the registry, loaded
   into the database, kept as EDN. Needs test.check."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pgmalli.impl.runtime :as rt])
  (:import (java.time Duration Instant LocalDate LocalDateTime LocalTime OffsetTime)))

(defn dataset-schema
  "Schema for {\"schema.table\" [row ...]} datasets: rows, primary keys, unique constraints and
   foreign keys checked across the registry's tables."
  [registry]
  (rt/dataset-schema registry))

(defn dataset-generator
  "test.check generator of datasets satisfying dataset-schema. Options: :rows wanted per table
   (default 5), :except tables (\"schema.table\") to leave out (no kept table may reference them).
   Tables that came out short are listed in the dataset's metadata, see short-tables."
  ([registry] (rt/dataset-generator registry))
  ([registry opts] (rt/dataset-generator registry opts)))

(defn short-tables
  "{\"schema.table\" {:wanted n :got n :reasons [[reason count] ...]}} for the tables of a
   generated dataset that came out with fewer rows than wanted, with what their candidate rows
   failed on; nil when none did."
  [dataset]
  (not-empty (:pgmalli/short (meta dataset))))

(defn inserts
  "HoneySQL INSERT maps for a dataset, one per table, in an order the database accepts:
   parents before the tables referencing them, and within a table rows before the rows
   referencing them. Enum values are cast to their type, json written and cast, arrays given
   their element type; time values are passed as they are (next.jdbc.date-time for the
   java.time ones); a column a row lacks is DEFAULT. A column the table does not have is an
   error. Option :on-conflict :nothing adds ON CONFLICT DO NOTHING, for a database that already
   holds some of the rows (seeded by migrations, say)."
  ([registry dataset] (rt/inserts registry dataset {}))
  ([registry dataset opts] (rt/inserts registry dataset opts)))

;;; datasets as EDN: the values EDN has no literal for go under pgmalli's tags

(def ^:private tags
  {Instant "pgmalli/instant" LocalDate "pgmalli/date" LocalDateTime "pgmalli/date-time"
   LocalTime "pgmalli/time" OffsetTime "pgmalli/offset-time" Duration "pgmalli/duration"})

(def readers
  "EDN readers for the tags write-dataset uses; for edn/read-string on a written dataset."
  {'pgmalli/instant #(Instant/parse %) 'pgmalli/date #(LocalDate/parse %) 'pgmalli/date-time #(LocalDateTime/parse %)
   'pgmalli/time #(LocalTime/parse %) 'pgmalli/offset-time #(OffsetTime/parse %) 'pgmalli/duration #(Duration/parse %)
   'pgmalli/bytes (fn [hex] (byte-array (map #(unchecked-byte (Integer/parseInt (subs hex % (+ % 2)) 16)) (range 0 (count hex) 2))))})

(defn- literal [v]
  (cond (some (fn [[c _]] (instance? c v)) tags) (tagged-literal (symbol (some (fn [[c t]] (when (instance? c v) t)) tags)) (str v))
        (bytes? v) (tagged-literal 'pgmalli/bytes (apply str (map #(format "%02x" (bit-and % 0xff)) v)))
        :else v))

(defn write-dataset
  "Writes a dataset as EDN, one table per block and one row per line, java.time values and
   bytes under pgmalli's tags (#pgmalli/instant \"...\", #pgmalli/bytes \"hex\"), so a fixture
   generated once (with a seed) is kept in the repository and read back with read-dataset.
   The tables that came out short (short-tables) are written too, under :pgmalli/short."
  [path dataset]
  (io/make-parents path)
  (spit path
        (str "{" (str/join "\n " (concat (for [[table rows] (sort-by key dataset)]
                                            (str (pr-str table) "\n [" (str/join "\n  " (map #(pr-str (into (sorted-map) (map (fn [[k v]] [k (literal v)])) %)) rows)) "]"))
                                          (when-let [s (short-tables dataset)] [(str ":pgmalli/short " (pr-str s))])))
             "}\n")))

(defn read-dataset
  "The dataset write-dataset wrote, its short tables back in the metadata. Byte arrays compare
   by identity, so a dataset holding bytea values is not = to itself read back; its rows load
   the same."
  [path]
  (let [m (edn/read-string {:readers readers} (slurp path))]
    (with-meta (dissoc m :pgmalli/short) {:pgmalli/short (:pgmalli/short m)})))
