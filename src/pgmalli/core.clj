(ns pgmalli.core
  "Generate malli schemas from an applied PostgreSQL schema.

   Config:
     {:schemas [\"public\"]           ; default [\"public\"]
      :out-dir \"resources/pgmalli\"  ; default; one file per schema, <out-dir>/<schema>.edn
      :overrides {constraint-name malli-schema | {:skip reason}}
      :db {:host :port :db :user :password :sslmode :psql}}  ; optional; psql's environment otherwise

   The config, the generated file layout and the fact vocabulary (see pgmalli.impl.pattern)
   are the stable contract. pgmalli.impl.* may change without notice."
  (:require [clojure.data :as data]
            [clojure.java.io :as io]
            [pgmalli.impl.generate :as gen]))

(defn generate!
  "Writes the generated file for every schema in the config. Returns {schema path}."
  [config]
  (gen/generate! config))

(defn path
  "Path of the generated file for a schema."
  [config schema]
  (gen/path-for config schema))

(defn registry
  "The registry in a generated file, ready for (merge (malli.core/default-schemas) ...)."
  [path]
  (:registry (gen/load-file* path)))

(defn stale
  "Difference between the generated files and what the database yields now, as
   {schema [only-in-file only-in-db]}; nil when everything matches. A missing file counts
   as entirely stale. :database-version is ignored."
  [config]
  (let [diffs (for [[schema {:keys [path data]}] (gen/generated-all config)
                    :let [file (when (.exists (io/file path)) (dissoc (gen/load-file* path) :database-version))
                          [only-file only-db _] (data/diff file (dissoc data :database-version))]
                    :when (or only-file only-db)]
                [schema [only-file only-db]])]
    (when (seq diffs) (into {} diffs))))

(defn unrendered
  "Facts in a generated file that have no malli rendering."
  [path]
  (:unrendered (gen/load-file* path)))
