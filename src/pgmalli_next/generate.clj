(ns pgmalli-next.generate
  "The generation side, which needs psql or a JDBC driver: the generated file, and whether it still matches
   the database. The application side (pgmalli-next.core) reads the file alone. Config:
   README.md#installation."
  (:require [pgmalli-next.impl.diff :as diff]
            [pgmalli-next.impl.files :as files]
            [pgmalli-next.impl.registry :as registry]))

(defn generate!
  "Writes the generated file of the config's schemas to its :out. Returns the path."
  [config]
  (files/generate! config))

(defn diff
  "[difference ...] between two generated data maps, as check's :stale reports them
   (:file the first, :db the second): a migration's effect on the schemas, read from the files
   alone."
  [before after]
  (diff/diff (registry/generated-data before "diff") (registry/generated-data after "diff")))

(defn check
  "What CI checks, reading the database once:
   {:stale [difference ...] :missing path :unrendered [entry ...] :diagnostics [diagnostic ...]},
   each nil when it has nothing.

   :stale lists how the file at :out differs from what the database yields now. A difference
   names the registry entry (:name) and, for a row or insert schema, the :column, :property (of
   the map, or with :column of that column) or column :order that differ, with the :file and :db
   sides (nil where a side lacks it); a function or operator by :key (:functions or
   :operators), :schema and :name; the file's other parts (:schemas, :sequences, :unrendered,
   :diagnostics) as wholes under :key. :missing is :out where no file is there, which :stale
   then leaves out.

   Option {:db? false} reads the file alone: no :stale key, as nothing was compared."
  ([config] (diff/check config))
  ([config opts] (diff/check config opts)))
