(ns pgmalli.generate
  "The generation side, which needs psql or a JDBC driver: the generated files, and whether they still match
   the database. The application side (pgmalli.core) reads the files alone.

   Config:
     {:schemas [\"public\"]           ; default [\"public\"]
      :out-dir \"resources/pgmalli\"  ; default; files are <out-dir>/<schema>.edn
      :overrides {constraint-name-or-type-keyword schema-or-{:skip reason}}
      :db {:host :port :db :user :password :sslmode :psql}}  ; optional; psql's environment otherwise
   or, with a PostgreSQL JDBC driver on the classpath and no psql needed,
      :db {:jdbc-url \"jdbc:postgresql://host/db\" :user :password} or {:datasource ds}"
  (:require [pgmalli.impl.diff :as diff]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.error :as error]))

(defn generate!
  "Writes the generated file for every schema in the config. Returns {schema path}."
  [config]
  (files/generate! config))

(defn diff
  "[difference ...] between two generated data maps of a schema, as check's :stale reports them
   (:file the first, :db the second): a migration's effect on the schemas, read from the files
   alone."
  [before after]
  (doseq [x [before after] :when (not (and (map? x) (map? (:registry x))))]
    (error/fail! :pgmalli.error/invalid-options
                 (str "diff takes two generated data maps, such as (pgmalli.core/generated \"public\"); given "
                      (if (map? x) "a map without :registry (a registry?)" (pr-str x)))
                 {:given (if (map? x) (vec (take 5 (keys x))) x)}))
  (diff/diff before after))

(defn check
  "What CI checks, for the config's schemas, reading the database once:
   {:stale {schema [difference ...]} :missing [schema ...] :unrendered {schema [entry ...]}
    :diagnostics {schema [diagnostic ...]}}, each nil when it has nothing.

   :stale lists the schemas whose file differs from what the database yields now. A difference
   names the registry entry (:name) and, for a row or insert schema, the :column, :property
   (of the map, or with :column of that column), :checks or column :order that differ, with the
   :file and :db sides (nil where a side lacks it); the file's other parts (:unrendered,
   :skipped, :diagnostics) as wholes under :key. :missing lists the schemas with no file.

   Option {:db? false} reads the files alone: no :stale key, as nothing was compared."
  ([config] (diff/check config))
  ([config opts] (diff/check config opts)))
