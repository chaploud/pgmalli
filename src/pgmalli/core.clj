(ns pgmalli.core
  "Generate malli schemas from an applied PostgreSQL schema, and use them.

   Generation (needs psql):
     (generate! config)   ; writes <out-dir>/<schema>.edn for every schema
     (stale config)       ; nil when the files match the database, else the differences

   Application side (files on the classpath as pgmalli/<schema>.edn):
     (registry \"public\")                 ; malli registry: generated schemas + malli defaults, util and time
     (unrendered \"public\")               ; facts that have no malli rendering
     (columns registry :pg.public/users)  ; the [:map ...] without table-level constraints
     transformer                          ; decodes JDBC / JSON values into the registry's types
     (dataset-schema registry)            ; {\"table\" [row ...]} with keys and references checked
     (dataset-generator registry)         ; generator of such datasets, in foreign-key order

   Config: {:schemas [\"public\"] :out-dir \"resources/pgmalli\" :checks :data :overrides {} :db {}},
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
  "malli registry holding the generated schemas of the named schemas (from the classpath),
   plus malli's defaults, malli.util and malli.experimental.time."
  [& schema-names]
  (apply rt/registry schema-names))

(defn unrendered
  "Facts of a schema that have no malli rendering."
  [schema-name]
  (:unrendered (rt/read-generated schema-name)))

(def columns rt/columns)
(def transformer rt/transformer)
(def dataset-schema rt/dataset-schema)
(def dataset-generator rt/dataset-generator)
