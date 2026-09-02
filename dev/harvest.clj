(ns harvest
  "Builds test/corpus/harvested.edn: runs SQL files against a throwaway PostgreSQL (statements
   that fail are ignored), then collects the expressions left in the catalog. PostgreSQL does
   the parsing; the SQL text itself is never read."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [pgmalli.impl.ir :as ir]
            [pgmalli.test-db :as db]))

(def sources
  [{:name "pg-regress" :license "PostgreSQL License"
    :url "https://github.com/postgres/postgres/tree/REL_17_STABLE/src/test/regress/sql"
    :files (sort (map str (fs/glob "test/corpus/pg-regress" "*.sql")))}
   {:name "pgschema-testdata" :license "Apache-2.0"
    :url "https://github.com/pgplex/pgschema/tree/v1.12.5/testdata/diff"
    :files (sort (map str (fs/glob (str (fs/home) "/Documents/OSS/pgschema/testdata/diff") "**/new.sql")))}])

(defn- psql! [dbname sql]
  (p/sh ["docker" "exec" "-i" db/*container* "psql" "-X" "-q" "-U" "postgres" "-d" dbname] {:in sql}))

(defn- schemas [dbname]
  (->> (p/sh ["docker" "exec" db/*container* "psql" "-X" "-A" "-t" "-U" "postgres" "-d" dbname "-c"
              "SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg\\_%' AND nspname <> 'information_schema'"])
       :out str/split-lines (remove str/blank?)))

(defn- expressions [schema-ir]
  (concat
   (for [[_ t] (:tables schema-ir) c (:columns t) :when (:default_value c)] {:kind :default :sql (:default_value c)})
   (for [[_ t] (:tables schema-ir) c (:columns t) :when (:generated_expr c)] {:kind :generated :sql (:generated_expr c)})
   (for [[_ t] (:tables schema-ir) [_ k] (:constraints t)] {:kind :check :sql (:check_clause k)})
   (for [[_ ty] (:types schema-ir) k (:constraints ty)] {:kind :domain :sql (:definition k)})))

(defn harvest []
  (db/with-postgres
    (fn []
      (let [exprs (->> (for [{:keys [name files]} sources
                             [i f] (map-indexed vector files)
                             :let [dbname (str "h_" (str/replace name "-" "_") "_" i)
                                   _ (psql! "t" (str "CREATE DATABASE " dbname))
                                   ;; regression scripts drop their tables at the end; keep them
                                   _ (psql! dbname (str/replace (slurp f) #"(?m)^\s*DROP\b" "-- DROP"))]
                             s (schemas dbname)
                             e (expressions (ir/from-db (assoc db/*db* :db dbname :schema s)))]
                         e)
                       (remove (comp str/blank? :sql))
                       distinct
                       (sort-by (juxt :kind :sql))
                       vec)]
        (spit "test/corpus/harvested.edn"
              (with-out-str
                (println ";; Expressions harvested by dev/harvest.clj from SQL run against PostgreSQL. Sources and licenses: NOTICE")
                (pp/pprint {:sources (mapv #(select-keys % [:name :license :url]) sources)
                            :expressions exprs})))
        (println "harvested" (count exprs) "expressions:" (frequencies (map :kind exprs)))))))

(harvest)
