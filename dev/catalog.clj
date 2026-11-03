(ns catalog
  "Rewrites resources/pgmalli/catalog.edn from pg_catalog of each PostgreSQL version CI runs:
   bb catalog dumps each version in a babashka of its own (pgmalli.test-db starts one image per
   process). An overload not every version has names the versions it is :in;
   pgmalli.upstream-test checks the file against the server it runs on."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [clj-yaml.core :as yaml]
            [clojure.string :as str]
            [pgmalli.catalog-source :as source]
            [pgmalli.test-db :as db]))

(defn dump
  "This process's server's catalog, as query gives it."
  []
  (db/with-postgres
    #(let [{:keys [out err]} (p/sh ["docker" "exec" db/*container* "psql" "-X" "-tA" "-U" "postgres" "-d" (:dbname db/*db*) "-c" source/query])]
       (or (json/parse-string out) (throw (ex-info err {}))))))

(defn- merged
  "The catalogs of versions as one: the types and casts, which must be alike in each, and every
   overload of a function or operator, :in the versions that have it where not all do."
  [versions cs]
  (doseq [k [:types :casts :ranges]]
    (when-not (apply = (map k cs))
      (throw (ex-info (str (name k) " differ between PostgreSQL " (str/join ", " versions) ": pgmalli.impl.types reads one of each") {}))))
  (let [overloads (fn [k] (into (sorted-map)
                                (for [n (distinct (mapcat (comp keys k) cs))]
                                  [n (vec (sort-by pr-str (for [o (distinct (mapcat #(get-in % [k n]) cs))
                                                                :let [in (vec (for [[v c] (map vector versions cs) :when (contains? (get-in c [k n]) o)] v))]]
                                                            (cond-> o (not= in versions) (assoc :in in)))))])))]
    {:versions (vec versions)
     ;; a relation's columns in the last version's order, those of others after, :in the
     ;; versions that have them where not all do
     :relations (into (sorted-map)
                      (for [n (distinct (mapcat (comp keys :relations) cs))]
                        [n (vec (for [col (distinct (mapcat #(get-in % [:relations n]) (reverse cs)))
                                      :let [in (vec (for [[v c] (map vector versions cs) :when (some #{col} (get-in c [:relations n]))] v))]]
                                  (cond-> col (not= in versions) (assoc :in in))))]))
     :types (into (sorted-map) (:types (first cs)))
     :casts (into (sorted-set) (:casts (first cs)))
     :ranges (into (sorted-map) (:ranges (first cs)))
     :functions (overloads :functions)
     :operators (overloads :operators)}))

(defn -main [& _]
  (let [versions (get-in (yaml/parse-string (slurp ".github/workflows/ci.yml")) [:jobs :test :strategy :matrix :pg])
        cs (for [v versions]
             (do (println "dumping PostgreSQL" v)
                 (source/shape (read-string (:out (p/shell {:out :string :extra-env {"PGMALLI_PG_IMAGE" (str "postgres:" v "-alpine")}}
                                                    "bb" "catalog:dump"))))))
        c (merged versions cs)]
    (spit "resources/pgmalli/catalog.edn"
          (str ";; pg_catalog's types, implicit casts, functions, operators and relations of PostgreSQL " (str/join ", " versions)
               ": bb catalog writes it\n"
               ;; a line a name: a regenerated file's diff is the names that changed
               "{" (str/join "\n " (for [[k v] c]
                                     (str k (if (map? v)
                                              (str "\n {" (str/join "\n  " (for [[n x] v] (str (pr-str n) " " (pr-str x)))) "}")
                                              (str " " (pr-str v))))))
               "}\n"))))
