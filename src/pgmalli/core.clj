(ns pgmalli.core
  "Generate malli schemas from an applied PostgreSQL schema, and use them.

   Generation (needs psql): generate!, stale.
   Application side (files on the classpath as pgmalli/<schema>.edn): registry, unrendered,
   columns, transformer, dataset-schema, dataset-generator.

   The config (pgmalli.impl.generate), the generated file layout and the fact vocabulary
   (pgmalli.impl.pattern) are the stable contract; pgmalli.impl.* may change without notice."
  (:require [clojure.data :as data]
            [clojure.java.io :as io]
            [pgmalli.impl.generate :as gen]
            [pgmalli.impl.runtime :as rt]))

;;; generation

(defn generate!
  "Writes the generated file for every schema in the config. Returns {schema path}."
  [config]
  (gen/generate! config))

(defn stale
  "{schema [only-in-file only-in-db]} for schemas whose file differs from what the database
   yields now; nil when everything matches. A missing file counts as entirely stale."
  [config]
  (let [diffs (for [[schema {:keys [path data]}] (gen/generated-all config)
                    :let [file (when (.exists (io/file path)) (dissoc (gen/load-file* path) :database-version))
                          [only-file only-db _] (data/diff file (dissoc data :database-version))]
                    :when (or only-file only-db)]
                [schema [only-file only-db]])]
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

(defn dataset-generator
  "test.check generator of datasets satisfying dataset-schema. Options: :rows wanted per table
   (default 5), :except tables (\"schema.table\") to leave out (no kept table may reference them).
   Tables that came out short are listed in the dataset's metadata under :pgmalli/short."
  ([registry] (rt/dataset-generator registry))
  ([registry opts] (rt/dataset-generator registry opts)))
