(ns catalog
  "Rewrites resources/pgmalli-next/impl/catalog.edn from pg_catalog of each PostgreSQL version CI runs:
   bb catalog dumps each version in a babashka of its own (pgmalli-next.test-db starts one image per
   process). A type, range, cast or overload not every version has names the versions it is :in;
   pgmalli-next.upstream-test checks the file against the server it runs on."
  (:require [babashka.process :as p]
            [cheshire.core :as json]
            [ci-images]
            [clojure.string :as str]
            [pgmalli-next.catalog-source :as source]
            [pgmalli-next.test-db :as db]))

(defn dump
  "This process's server's catalog, as query gives it."
  []
  (db/with-postgres
    #(let [{:keys [out err]} (p/sh ["docker" "exec" db/*container* "psql" "-X" "-tA" "-U" "postgres" "-d" (:dbname db/*db*) "-c" source/query])]
       (or (json/parse-string out) (throw (ex-info err {}))))))

(defn- merged
  "The catalogs of versions as one: every type, range, cast and overload of a function or
   operator, :in the versions that have it where not all do (a cast [source target context {:in [v ...]}])."
  [versions cs]
  (let [in (fn [has?] (vec (for [[v c] (map vector versions cs) :when (has? c)] v)))
        named (fn [k] (into (sorted-map)
                            (for [n (distinct (mapcat (comp keys k) cs))
                                  :let [facts (distinct (keep #(get-in % [k n]) cs))
                                        vs (in #(contains? (k %) n))]]
                              (if (next facts)
                                (throw (ex-info (str "PostgreSQL " (str/join ", " versions) " differ in " (name k) " " n ": " (pr-str facts)
                                                     "; pgmalli-next.impl.types reads one of each") {}))
                                [n (cond-> (first facts) (not= vs versions) (assoc :in vs))]))))
        overloads (fn [k] (into (sorted-map)
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
     :types (named :types)
     :casts (into (sorted-set) (for [c (distinct (mapcat :casts cs)) :let [vs (in #(contains? (:casts %) c))]]
                                 (cond-> c (not= vs versions) (conj {:in vs}))))
     :ranges (named :ranges)
     :functions (overloads :functions)
     :operators (overloads :operators)
     ;; of any version: a name quoted where one need not be is the same column
     :keywords (vec (sort (distinct (mapcat :keywords cs))))}))

(defn -main [& _]
  (let [images (ci-images/images)
        versions (vec (keys images))
        cs (for [v versions]
             (do (println "dumping PostgreSQL" v)
                 (source/shape (read-string (:out (p/shell {:out :string :extra-env {"PGMALLI_PG_IMAGE" (images v)}}
                                                    "bb" "catalog:dump"))))))
        c (merged versions cs)]
    (spit "resources/pgmalli-next/impl/catalog.edn"
          (str ";; pg_catalog's types, casts, functions, operators and relations of PostgreSQL " (str/join ", " versions)
               ": bb catalog writes it\n"
               ;; a line a name: a regenerated file's diff is the names that changed
               "{" (str/join "\n " (for [[k v] c]
                                     (str k (if (map? v)
                                              (str "\n {" (str/join "\n  " (for [[n x] v] (str (pr-str n) " " (pr-str x)))) "}")
                                              (str " " (pr-str v))))))
               "}\n"))))
