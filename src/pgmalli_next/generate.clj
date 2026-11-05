(ns pgmalli-next.generate
  "The generation side, which needs psql or a JDBC driver: the generated files, and whether they still match
   the database. The application side (pgmalli-next.core) reads the files alone. Config:
   README.md#installation."
  (:require [pgmalli-next.impl.diff :as diff]
            [pgmalli-next.impl.files :as files]
            [pgmalli-next.impl.registry :as registry]))

(defn generate!
  "Writes the generated file for every schema in the config. Returns {schema path}."
  [config]
  (files/generate! config))

(defn diff
  "[difference ...] between two generated data maps of a schema, as check's :stale reports them
   (:file the first, :db the second): a migration's effect on the schemas, read from the files
   alone."
  [before after]
  (diff/diff (registry/generated-data before "diff") (registry/generated-data after "diff")))

(defn check
  "What CI checks, for the config's schemas, reading the database once:
   {:stale {schema [difference ...]} :missing [schema ...] :unrendered {schema [entry ...]}
    :diagnostics {schema [diagnostic ...]}}, each nil when it has nothing.

   :stale lists the schemas whose file differs from what the database yields now. A difference
   names the registry entry (:name) and, for a row or insert schema, the :column, :property
   (of the map, or with :column of that column), :checks or column :order that differ, with the
   :file and :db sides (nil where a side lacks it); a function or operator by :key (:functions
   or :operators) and :name, or :absent :file when the file has none (an older pgmalli's); the
   file's other parts (:unrendered, :skipped, :diagnostics) as wholes under :key. :missing lists
   the schemas with no file, which :stale leaves out.

   Option {:db? false} reads the files alone: no :stale key, as nothing was compared."
  ([config] (diff/check config))
  ([config opts] (diff/check config opts)))
