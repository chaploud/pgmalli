(ns pgmalli.generate
  "The generation side, which needs psql: the generated files, and whether they still match
   the database. The application side (pgmalli.core) reads the files alone.

   Config:
     {:schemas [\"public\"]           ; default [\"public\"]
      :out-dir \"resources/pgmalli\"  ; default; files are <out-dir>/<schema>.edn
      :overrides {constraint-name schema-or-{:skip reason}}
      :db {:host :port :db :user :password :sslmode :psql :dir}}  ; optional; psql's environment otherwise"
  (:require [pgmalli.impl.diff :as diff]
            [pgmalli.impl.files :as files]))

(defn generate!
  "Writes the generated file for every schema in the config. Returns {schema path}."
  [config]
  (files/generate! config))

(defn stale
  "{schema [difference ...]} for schemas whose file differs from what the database yields now;
   nil when everything matches. A difference names the registry entry (:name) and, for a row
   or insert schema, the :column, :property (of the map, or with :column of that column),
   :checks or column :order that differ, with the :file and :db sides (nil where a side lacks
   it); the file's other parts (:unrendered, :skipped, :diagnostics) as wholes under :key. A
   missing file lists every entry with no :file side."
  [config]
  (diff/stale config))

(defn diff
  "[difference ...] between two generated data maps of a schema, as stale reports them (:file the
   first, :db the second): a migration's effect on the schemas, read from the files alone."
  [before after]
  (diff/diff before after))

(defn check
  "What CI wants to know in one read: {:stale {schema [difference ...]} :unrendered {schema
   [fact ...]} :diagnostics {schema [diagnostic ...]}}, :stale nil when every file matches the
   database. Option {:db? false} skips the database and reports the files alone."
  ([config] (diff/check config))
  ([config opts] (diff/check config opts)))
