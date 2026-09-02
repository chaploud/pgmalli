(ns pgmalli.core
  "Generate malli schemas from an applied PostgreSQL schema, and use them.

   Generation (needs psql): generate!, stale.
   Application side (files on the classpath as pgmalli/<schema>.edn): registry, unrendered,
   columns, transformer, dataset-schema, dataset-generator.

   Config: {:schemas [\"public\"] :out-dir \"resources/pgmalli\" :overrides {} :db {}},
   every key optional. The config, the generated file layout and the fact vocabulary
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

(defn transformer
  "malli transformer decoding JDBC and string values into the registry's types. Instants that
   land in timestamp (without time zone) columns are read in :zone, default the JVM's."
  ([] (rt/transformer))
  ([opts] (rt/transformer opts)))

(defn dataset-schema
  "Schema for {\"schema.table\" [row ...]} datasets: rows, primary keys, unique constraints and
   foreign keys checked across the registry's tables."
  [registry]
  (rt/dataset-schema registry))

(defn dataset-generator
  "test.check generator of datasets satisfying dataset-schema; {:rows n} rows tried per table."
  ([registry] (rt/dataset-generator registry))
  ([registry opts] (rt/dataset-generator registry opts)))
