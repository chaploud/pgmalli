(ns pgmalli.generate
  "The generation side, which needs psql: the generated files, and whether they still match
   the database. The application side (pgmalli.core) reads the files alone.

   Config:
     {:schemas [\"public\"]           ; default [\"public\"]
      :out-dir \"resources/pgmalli\"  ; default; files are <out-dir>/<schema>.edn
      :overrides {constraint-name schema-or-{:skip reason}}
      :db {:host :port :db :user :password :sslmode :psql :dir}}  ; optional; psql's environment otherwise"
  (:require [clojure.java.io :as io]
            [pgmalli.impl.generate :as gen]
            [pgmalli.impl.runtime :as rt]))

(defn generate!
  "Writes the generated file for every schema in the config. Returns {schema path}."
  [config]
  (gen/generate! config))

(defn- row-parts
  "{:columns {name entry} :props :checks} of a row or insert schema, nil for other entries."
  [s]
  (let [m (if (and (vector? s) (= :and (first s))) (second s) s)]
    (when (and (vector? m) (= :map (first m)) (map? (second m)))
      {:columns (into {} (map (fn [[k p s]] [k (if (seq p) [p s] s)])) (rt/column-entries s))
       :props (second m)
       :checks (when (= :and (first s)) (vec (drop 2 s)))})))

(defn- differences
  "The differences between the file's and the database's version of one registry entry, a
   row or insert schema by column, property and CHECKs, anything else as a whole."
  [name file db]
  (let [f (row-parts file) d (row-parts db)
        props (fn [s] (when (and (vector? s) (map? (second s))) (second s)))
        props-only? (fn [a b] (and (props a) (props b) (= (assoc a 1 {}) (assoc b 1 {}))))
        by (fn [part label] (for [k (distinct (concat (keys (part f)) (keys (part d))))
                                  :let [a (get (part f) k) b (get (part d) k)]
                                  :when (not= a b)
                                  ;; a column whose properties alone differ: one line per property
                                  d (if (and (= :column label) (props-only? a b))
                                      (for [pk (distinct (concat (keys (props a)) (keys (props b))))
                                            :when (not= (get (props a) pk) (get (props b) pk))]
                                        {:name name :column k :property pk :file (get (props a) pk) :db (get (props b) pk)})
                                      [{:name name label k :file a :db b}])]
                              d))
        order (fn [s] (map first (rt/column-entries s)))]
    (cond (= file db) nil
          (and f d) (let [ds (concat (by :columns :column) (by :props :property)
                                     (when (not= (:checks f) (:checks d)) [{:name name :checks true :file (:checks f) :db (:checks d)}]))]
                      (if (seq ds) ds [{:name name :order true :file (order file) :db (order db)}]))
          :else [{:name name :file file :db db}])))

(declare files stale*)

(defn stale
  "{schema [difference ...]} for schemas whose file differs from what the database yields now;
   nil when everything matches. A difference names the registry entry (:name) and, for a row
   or insert schema, the :column, :property (of the map, or with :column of that column),
   :checks or column :order that differ, with the :file and :db sides (nil where a side lacks
   it); the file's other parts (:unrendered, :skipped, :diagnostics) as wholes under :key. A
   missing file lists every entry with no :file side."
  [config]
  (stale* config (files config)))

(defn- files [config]
  (into {} (for [schema (:schemas (gen/config config))
                 :let [p (gen/path-for config schema)]
                 :when (.exists (io/file p))]
             [schema (gen/load-file* p)])))

(defn- stale* [config files]
  (let [diffs (for [[schema {:keys [data]}] (gen/generated-all config)
                    :let [file (get files schema)
                          ds (concat (for [k (distinct (concat (keys (:registry file)) (keys (:registry data))))
                                          d (differences k (get-in file [:registry k]) (get-in data [:registry k]))]
                                      d)
                                     (for [k (distinct (concat (keys file) (keys data)))
                                           :when (and (not (#{:registry :database-version :schema} k)) (not= (get file k) (get data k)))]
                                       {:key k :file (get file k) :db (get data k)}))]
                    :when (seq ds)]
                [schema (vec ds)])]
    (when (seq diffs) (into {} diffs))))

(defn check
  "What CI wants to know in one read: {:stale {schema [difference ...]} :unrendered {schema
   [fact ...]} :diagnostics {schema [diagnostic ...]}}, :stale nil when every file matches the
   database. Option {:db? false} skips the database and reports the files alone."
  ([config] (check config {}))
  ([config {:keys [db?] :or {db? true}}]
   (let [files (files config)]
     {:stale (when db? (stale* config files))
      :unrendered (into {} (for [[s f] files :when (seq (:unrendered f))] [s (:unrendered f)]))
      :diagnostics (into {} (for [[s f] files :when (seq (:diagnostics f))] [s (:diagnostics f)]))})))
