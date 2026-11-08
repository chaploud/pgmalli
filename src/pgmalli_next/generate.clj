(ns pgmalli-next.generate
  "The generation side, which needs psql or a JDBC driver: the generated file, and whether it still
   matches the database. The application side (pgmalli-next.core) reads the file alone.

   generate! and check take one map, {:schemas :out :overrides :db} (README.md#generating), so a
   REPL and clojure -X call them alike:

     clojure -X:pgmalli-next pgmalli-next.generate/generate!
     clojure -X:pgmalli-next pgmalli-next.generate/check"
  (:require [clojure.string :as str]
            [pgmalli-next.impl.diff :as diff]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.files :as files]
            [pgmalli-next.impl.registry :as registry]
            [pgmalli-next.impl.types :as types]))

(defn- print-unrendered [facts]
  (when (seq facts)
    (println (str "unrendered (" (count facts) "), left out of the schemas:"))
    (doseq [{:keys [table column type]} facts]
      (println (str "  " table " " column ": type " type))
      (println (str "    to keep it, add to :overrides {" (keyword table column) " <malli schema>}")))))

(defn- print-difference [{:keys [name key schema column property order file db]}]
  (println (str/join " " (remove nil? [(some-> key pr-str) schema (some-> name pr-str) (some-> column pr-str) (some-> property pr-str)
                                       (when order "column order")])))
  (println "  file:" (pr-str file))
  (println "  db:  " (pr-str db)))

(defn- version-warning
  "Why a file of a server's version (\"PostgreSQL 19\") is typed as an older one, nil when the
   catalog snapshot has it."
  [database-version]
  (let [versions (:versions @types/catalog)]
    (when-not (some #(= database-version (str "PostgreSQL " %)) versions)
      (str database-version " is newer than the versions this pgmalli knows (" (str/join ", " versions)
           "): a query's pg_catalog functions and types are typed as PostgreSQL " (last versions) " has them"))))

(defn generate!
  "Writes the generated file of config's schemas to its :out (where no file or a generated one
   is), and prints the columns left out. Returns {:out path :unrendered [entry ...] :diagnostics
   [diagnostic ...]}, check's two with :out."
  [config]
  (binding [*print-namespace-maps* false]
    (let [out (files/generate! config)
          data (registry/read-generated-file out)]
      (when-let [w (version-warning (:database-version data))] (println "warning:" w))
      (print-unrendered (:unrendered data))
      {:out out :unrendered (not-empty (:unrendered data)) :diagnostics (not-empty (:diagnostics data))})))

(defn diff
  "[difference ...] between two generated data maps, as check's :stale reports them
   (:file the first, :db the second): a migration's effect on the schemas, read from the files
   alone."
  [before after]
  (diff/diff (registry/generated-data before "diff") (registry/generated-data after "diff")))

(defn check
  "What CI checks, reading the database once with the config generate! takes: prints how the file
   at :out differs from what the database yields now, and what generate! prints, and throws when
   it differs (:pgmalli/error
   :pgmalli.error/stale, ex-data {:out path :missing path :stale [difference ...]}), so clojure -X exits
   non-zero. Else returns {:unrendered [entry ...] :diagnostics [diagnostic ...]}, each nil when
   it has nothing.

   A difference names the registry entry (:name) and, for a row schema, the :column,
   :property (of the map, or with :column of that column) or column :order that differ, with the
   :file and :db sides (nil where a side lacks it); a function or operator by :key (:functions or
   :operators), :schema and :name; the file's other parts (:schemas, :database-version,
   :sequences, :unrendered, :diagnostics) as wholes under :key. :missing is :out where no file is there, which :stale then
   leaves out."
  [config]
  (binding [*print-namespace-maps* false]
    (let [{:keys [missing stale unrendered diagnostics]} (diff/check config)]
      (doseq [d stale] (print-difference d))
      (print-unrendered unrendered)
      (when (or missing stale)
        (error/fail! :pgmalli.error/stale
                     (if missing
                       (str "no generated file at " missing "; run pgmalli-next.generate/generate! with the same map and commit it")
                       (str "stale (differences: " (count stale) "); run pgmalli-next.generate/generate! with the same map and commit the file"))
                     {:out (:out (files/config config)) :missing missing :stale stale}))
      {:unrendered unrendered :diagnostics diagnostics})))
