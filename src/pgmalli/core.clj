(ns pgmalli.core
  "Generate malli schemas from an applied PostgreSQL schema, and use them.

   Generation (needs psql): generate!, stale.
   Application side (files on the classpath as pgmalli/<schema>.edn): registry, unrendered,
   columns, transformer, dataset-schema, dataset-generator.

   The config (pgmalli.impl.generate), the generated file layout and the fact vocabulary
   (pgmalli.impl.pattern) are the stable contract; pgmalli.impl.* may change without notice."
  (:require [clojure.java.io :as io]
            [pgmalli.impl.generate :as gen]
            [pgmalli.impl.runtime :as rt]))

;;; generation

(defn generate!
  "Writes the generated file for every schema in the config. Returns {schema path}."
  [config]
  (gen/generate! config))

(defn- row-parts
  "{:columns {name entry} :props :checks} of a row or insert schema, nil for other entries."
  [s]
  (let [m (if (and (vector? s) (= :and (first s))) (second s) s)]
    (when (and (vector? m) (= :map (first m)) (map? (second m)))
      {:columns (into {} (map (fn [[k p s]] [k (if (seq p) [p s] s)])) (rt/column-entries s))
       :props (second m)
       :checks (when (= :and (first s)) (vec (drop 2 s)))})))

(defn- differences
  "The differences between the file's and the database's version of one registry entry, a
   row or insert schema by column, property and CHECKs, anything else as a whole."
  [name file db]
  (let [f (row-parts file) d (row-parts db)
        props (fn [s] (when (and (vector? s) (map? (second s))) (second s)))
        props-only? (fn [a b] (and (props a) (props b) (= (assoc a 1 {}) (assoc b 1 {}))))
        by (fn [part label] (for [k (distinct (concat (keys (part f)) (keys (part d))))
                                  :let [a (get (part f) k) b (get (part d) k)]
                                  :when (not= a b)
                                  ;; a column whose properties alone differ: one line per property
                                  d (if (and (= :column label) (props-only? a b))
                                      (for [pk (distinct (concat (keys (props a)) (keys (props b))))
                                            :when (not= (get (props a) pk) (get (props b) pk))]
                                        {:name name :column k :property pk :file (get (props a) pk) :db (get (props b) pk)})
                                      [{:name name label k :file a :db b}])]
                              d))
        order (fn [s] (map first (rt/column-entries s)))]
    (cond (= file db) nil
          (and f d) (let [ds (concat (by :columns :column) (by :props :property)
                                     (when (not= (:checks f) (:checks d)) [{:name name :checks true :file (:checks f) :db (:checks d)}]))]
                      (if (seq ds) ds [{:name name :order true :file (order file) :db (order db)}]))
          :else [{:name name :file file :db db}])))

(defn stale
  "{schema [difference ...]} for schemas whose file differs from what the database yields now;
   nil when everything matches. A difference names the registry entry (:name) and, for a row
   or insert schema, the :column, :property (of the map, or with :column of that column),
   :checks or column :order that differ, with the :file and :db sides (nil where a side lacks
   it); the file's other parts (:unrendered, :skipped) as wholes under :key. A missing file
   lists every entry with no :file side."
  [config]
  (let [diffs (for [[schema {:keys [path data]}] (gen/generated-all config)
                    :let [file (when (.exists (io/file path)) (gen/load-file* path))
                          ds (concat (for [k (distinct (concat (keys (:registry file)) (keys (:registry data))))
                                          d (differences k (get-in file [:registry k]) (get-in data [:registry k]))]
                                      d)
                                     (for [k (distinct (concat (keys file) (keys data)))
                                           :when (and (not (#{:registry :database-version :schema} k)) (not= (get file k) (get data k)))]
                                       {:key k :file (get file k) :db (get data k)}))]
                    :when (seq ds)]
                [schema (vec ds)])]
    (when (seq diffs) (into {} diffs))))

;;; application side

(defn registry
  "malli registry holding the generated schemas of the named schemas (read from the classpath;
   generated data maps are accepted too), insert schemas derived from them, plus malli's
   defaults, malli.util and malli.experimental.time."
  [& schemas]
  (apply rt/registry schemas))

(defn unrendered
  "Facts of a schema that have no malli rendering."
  [schema-name]
  (:unrendered (rt/read-generated schema-name)))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints."
  [registry name]
  (rt/columns registry name))

(defn column
  "The schema of one column of a row or insert schema, as data, [:maybe ...] included."
  [registry name col]
  (rt/column registry name col))

(defn non-null
  "A column schema without its [:maybe ...]: what a value must be when it is not NULL."
  [schema]
  (rt/non-null schema))

(defn portable
  "The named schema as data malli's default registry reads (plus malli.experimental.time):
   the schema's own types inlined, pgmalli's types as their malli counterparts, generation
   hints dropped, the CHECKs only pgmalli evaluates left out. For :malli/schema metadata and
   other places the registry cannot follow."
  [registry name]
  (rt/portable registry name))

(defn as-read
  "The [:map ...] of a row as a JDBC result builder returns it. Options: :qualified? (keys as
   :table/column, next.jdbc's as-maps), :kebab? (keys and table names in kebab-case),
   :nil-columns :absent (NULL columns missing, next.jdbc.optional), :time :instant
   (read-as-instant: timestamps as Instants, dates stay java.sql.Date, so inst?) or :local
   (read-as-local: timestamptz as LocalDateTime)."
  [registry name opts]
  (rt/as-read registry name opts))

(defn transformer
  "malli transformer decoding JDBC and string values into the registry's types. Instants and
   java.util.Dates that land in date or timestamp (without time zone) columns are read in
   :zone, default the JVM's; JSON text in json and jsonb columns is parsed."
  ([] (rt/transformer))
  ([opts] (rt/transformer opts)))

(defn dataset-schema
  "Schema for {\"schema.table\" [row ...]} datasets: rows, primary keys, unique constraints and
   foreign keys checked across the registry's tables."
  [registry]
  (rt/dataset-schema registry))

(defn inserts
  "HoneySQL INSERT maps for a dataset, one per table, in an order the database accepts:
   parents before the tables referencing them, and within a table rows before the rows
   referencing them. Enum values are cast to their type, json written and cast, arrays given
   their element type; time values are passed as they are (next.jdbc.date-time for the
   java.time ones)."
  [registry dataset]
  (rt/inserts registry dataset))

(defn dataset-generator
  "test.check generator of datasets satisfying dataset-schema. Options: :rows wanted per table
   (default 5), :except tables (\"schema.table\") to leave out (no kept table may reference them).
   Tables that came out short are listed in the dataset's metadata under :pgmalli/short."
  ([registry] (rt/dataset-generator registry))
  ([registry opts] (rt/dataset-generator registry opts)))
