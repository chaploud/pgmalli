(ns pgmalli.impl.runtime
  "The application side: generated files as malli registries, plus helpers that need malli.
   Generated files are read from the classpath as pgmalli/<schema>.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.experimental.time :as time]
            malli.experimental.time.generator
            [malli.generator :as mg]
            [malli.transform :as mt]
            [malli.util :as mu]))

(defn read-generated
  "The generated data for a schema name, from the classpath (pgmalli/<schema>.edn)."
  [schema]
  (let [path (str "pgmalli/" schema ".edn")]
    (edn/read-string (slurp (or (io/resource path)
                                (throw (ex-info (str path " is not on the classpath; run generate! and add its :out-dir to :paths")
                                                {:schema schema})))))))

(defn registry
  "malli registry with the generated schemas of the given schema names and everything they
   need (malli defaults, malli.util, malli.experimental.time)."
  [& schemas]
  (apply merge (m/default-schemas) (mu/schemas) (time/schemas) (map (comp :registry read-generated) schemas)))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints."
  [registry name]
  (let [s (m/deref (m/schema name {:registry registry}))]
    (if (= :and (m/type s)) (first (m/children s)) s)))

(def transformer
  "Decodes values as they arrive from JDBC drivers or JSON into the types the registry uses:
   java.sql.Timestamp / java.util.Date -> Instant, java.sql.Date -> LocalDate, strings -> numbers,
   booleans, keywords and temporal types (through malli's string transformer)."
  (mt/transformer
   mt/string-transformer
   {:name :pgmalli
    :decoders {:time/instant (fn [x] (if (instance? java.util.Date x) (.toInstant ^java.util.Date x) x))
               :time/local-date (fn [x] (cond (instance? java.sql.Date x) (.toLocalDate ^java.sql.Date x)
                                              (instance? java.util.Date x) (-> ^java.util.Date x .toInstant (.atZone java.time.ZoneOffset/UTC) .toLocalDate)
                                              :else x))
               :time/local-date-time (fn [x] (if (instance? java.sql.Timestamp x) (.toLocalDateTime ^java.sql.Timestamp x) x))}}))

;;; datasets: several tables at once, with keys and references checked

(defn- table-names [registry]
  (->> (keys registry)
       (filter #(and (keyword? %) (some-> (namespace %) (str/starts-with? "pg."))))
       (filter #(let [s (m/deref (m/schema % {:registry registry}))
                      props (m/properties (if (= :and (m/type s)) (first (m/children s)) s))]
                  (:pg/table props)))
       sort))

(defn- table-props [registry name]
  (m/properties (columns registry name)))

(defn- column-references
  "[[key [table column]] ...] for the columns of a table that reference another table."
  [registry name]
  (for [[k _ s] (m/children (columns registry name))
        :let [s (m/deref s)
              s (if (= :maybe (m/type s)) (m/deref (first (m/children s))) s)
              to (:pg/references (m/properties s))]
        :when to]
    [k to]))

(defn- unique-sets [registry n]
  (let [{:keys [pg/primary-key pg/unique]} (table-props registry n)]
    (remove nil? (cons primary-key unique))))

(defn- distinct-by-keys
  "Keeps the first row for every combination of the columns in each key set (NULLs never collide)."
  [rows key-sets]
  (first (reduce (fn [[kept seen] row]
                   (let [ks (map (fn [cols] (let [k (mapv #(get row (keyword %)) cols)] (when-not (some nil? k) [cols k]))) key-sets)]
                     (if (some seen (remove nil? ks))
                       [kept seen]
                       [(conj kept row) (into seen (remove nil? ks))])))
                 [[] #{}] rows)))

(defn dataset-schema
  "Schema for {\"table\" [row ...] ...} covering every table of the registry: rows validate against
   their row schemas, primary keys and unique constraints hold within a table, and foreign keys
   point at existing rows. The result contains functions, so it is built at runtime."
  [registry]
  (let [names (table-names registry)
        refs (into {} (map (fn [n] [n (column-references registry n)]) names))]
    [:and
     (into [:map] (for [n names] [(:pg/table (table-props registry n)) {:optional true} [:vector n]]))
     [:fn {:error/message "primary keys and unique constraints"}
      (fn [ds]
        (every? (fn [n] (let [rows (get ds (:pg/table (table-props registry n)))]
                          (every? (fn [cols] (let [ks (map (fn [r] (mapv #(get r (keyword %)) cols)) rows)
                                                   ks (remove #(some nil? %) ks)]
                                               (= (count ks) (count (distinct ks)))))
                                  (unique-sets registry n))))
                names))]
     [:fn {:error/message "foreign keys"}
      (fn [ds]
        (every? (fn [n]
                  (every? (fn [[k [table col]]]
                            (let [targets (set (map #(get % (keyword col)) (get ds table)))]
                              (every? #(or (nil? (get % k)) (contains? targets (get % k))) (get ds (:pg/table (table-props registry n))))))
                          (refs n)))
                names))]]))

(defn- topological [names deps]
  (loop [done [] left (set names)]
    (if (empty? left)
      done
      (let [ready (sort (filter #(every? (fn [d] (or (= d %) (not (left d)))) (deps %)) left))]
        (when (empty? ready) (throw (ex-info "cyclic foreign keys" {:left left})))
        (recur (into done ready) (reduce disj left ready))))))

(defn dataset-generator
  "test.check generator of datasets that satisfy dataset-schema: tables are generated in
   foreign-key order and referencing columns draw from the rows generated before them."
  ([registry] (dataset-generator registry {}))
  ([registry {:keys [rows] :or {rows 5}}]
   (let [names (table-names registry)
         table-of (fn [n] (:pg/table (table-props registry n)))
         name-of (into {} (map (fn [n] [(table-of n) n]) names))
         refs (into {} (map (fn [n] [n (column-references registry n)]) names))
         order (topological names (fn [n] (map (comp name-of second second) (refs n))))
         gen (requiring-resolve 'clojure.test.check.generators/bind)
         fmap (requiring-resolve 'clojure.test.check.generators/fmap)
         return (requiring-resolve 'clojure.test.check.generators/return)
         vector-of (requiring-resolve 'clojure.test.check.generators/vector)
         opts {:registry registry}
         row-gen (fn [n ds]
                   (let [base (mg/generator (columns registry n) opts)
                         fix (fn [row]
                               (reduce (fn [row [k [table col]]]
                                         (let [targets (seq (remove nil? (map #(get % (keyword col)) (get ds table))))]
                                           (if (and targets (some? (get row k)))
                                             (assoc row k (nth targets (mod (hash (get row k)) (count targets))))
                                             (if targets row (assoc row k nil)))))
                                       row (refs n)))]
                     (fmap (fn [rs] (-> (->> rs (map fix) (filter #(m/validate n % opts)))
                                        (distinct-by-keys (unique-sets registry n))
                                        vec))
                           (vector-of base rows))))]
     (reduce (fn [g n]
               (gen g (fn [ds] (fmap (fn [rs] (assoc ds (table-of n) rs)) (row-gen n ds)))))
             (return {})
             order))))
