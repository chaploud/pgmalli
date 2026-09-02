(ns pgmalli.impl.runtime
  "The application side: generated files as malli registries, plus helpers that need malli.
   Generated files are read from the classpath as pgmalli/<schema>.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.experimental.time :as time]
            malli.experimental.time.generator
            [malli.generator :as mg]
            [malli.transform :as mt]
            [malli.util :as mu]
            [pgmalli.impl.eval :as check]))

(def check-schema
  "[:pg/check expr]: a row passes when the CHECK expression data does (pgmalli.impl.eval).
   A column missing from the row counts as NULL."
  (m/-simple-schema
   {:type :pg/check
    :compile (fn [_ [expr] _]
               (let [pass? (check/checker expr)]
                 {:pred (fn [row] (and (map? row) (pass? row))) :min 1 :max 1}))}))

(defn read-generated
  "The generated data for a schema name, from the classpath (pgmalli/<schema>.edn)."
  [schema]
  (let [path (str "pgmalli/" schema ".edn")]
    (edn/read-string (slurp (or (io/resource path)
                                (throw (ex-info (str path " is not on the classpath; run generate! and add its :out-dir to :paths")
                                                {:schema schema})))))))

;;; insert schemas, derived from row schemas

(defn- row-map [schema] (if (= :and (first schema)) (second schema) schema))

(defn- entry-parts
  "[key props schema] of a map entry, props defaulted to {}."
  [[k p s :as e]]
  (if (map? p) [k p s] [k {} (second e)]))

(defn- column-props
  "Properties of a column schema, looking through [:maybe ...]."
  [s]
  (let [s (if (and (vector? s) (= :maybe (first s))) (last s) s)]
    (if (and (vector? s) (map? (second s))) (second s) {})))

(defn- insert-entry [e]
  (let [[k p s] (entry-parts e)
        props (column-props s)
        nullable? (and (vector? s) (= :maybe (first s)))]
    (when-not (or (:pg/generated props) (= :always (:pg/identity props)))
      [k (cond-> p (or (:pg/identity props) (contains? props :pg/default) nullable?) (assoc :optional true)) s])))

(defn- optional-keys
  "Every [:map ...] without properties (the fragments of :multi and :or) with all keys optional."
  [schema]
  (walk/postwalk (fn [f]
                   (if (and (vector? f) (= :map (first f)) (not (map? (second f))))
                     (into [:map] (map (fn [e] (let [[k p s] (entry-parts e)] [k (assoc p :optional true) s])) (rest f)))
                     f))
                 schema))

(defn- insert-schema
  "What an INSERT may carry: identity ALWAYS and generated columns removed, columns with a
   default or NULL optional, closed map; the table constraints follow with their keys optional."
  [row]
  (let [[_ props & entries] (row-map row)
        m (into [:map (assoc props :closed true)] (keep insert-entry entries))]
    (if (= :and (first row))
      (into [:and m] (map optional-keys (drop 2 row)))
      m)))

(defn- insert-name
  ":pg.<schema>/<table> -> :pg.<schema>.<table>/insert, string keys alike."
  [row-name]
  (if (keyword? row-name)
    (keyword (str (namespace row-name) "." (name row-name)) "insert")
    (str (str/replace-first row-name "/" ".") "/insert")))

(defn- row-name?
  "Row schemas carry :pg/table; their inserts do too, but closed."
  [registry k]
  (let [props (when (vector? (get registry k)) (m/properties (m/schema (row-map (get registry k)) {:registry registry})))]
    (and (:pg/table props) (not (:closed props)))))

(defn- with-inserts [registry]
  (into registry (for [[k s] registry :when (row-name? registry k)] [(insert-name k) (insert-schema s)])))

(defn registry
  "malli registry with the generated schemas (schema names read from the classpath, or generated
   data maps), insert schemas derived from them, and everything they need (malli defaults,
   malli.util, malli.experimental.time)."
  [& schemas]
  (let [base (merge (m/default-schemas) (mu/schemas) (time/schemas) {:pg/check check-schema})
        generated (apply merge (map #(:registry (if (map? %) % (read-generated %))) schemas))]
    (merge base (with-inserts (merge base generated)) generated)))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints."
  [registry name]
  (let [s (m/deref (m/schema name {:registry registry}))]
    (if (= :and (m/type s)) (first (m/children s)) s)))

(defn transformer
  "Decodes values as they arrive from JDBC drivers or JSON into the types the registry uses:
   java.sql.Timestamp / java.util.Date -> Instant, java.sql.Date -> LocalDate, Instant and
   java.util.Date -> LocalDate / LocalDateTime in :zone (default: the JVM's zone, which is what
   JDBC drivers use when they hand out an Instant for a timestamp column), strings -> numbers,
   booleans and temporal types (malli's string transformer)."
  ([] (transformer {}))
  ([{:keys [zone] :or {zone (java.time.ZoneId/systemDefault)}}]
   (let [instant (fn [x] (if (instance? java.util.Date x) (.toInstant ^java.util.Date x) x))]
     (mt/transformer
      mt/string-transformer
      {:name :pgmalli
       :decoders {:time/instant instant
                  :time/local-date (fn [x] (cond (instance? java.sql.Date x) (.toLocalDate ^java.sql.Date x)
                                                 (instance? java.util.Date x) (.toLocalDate (.atZone ^java.time.Instant (instant x) zone))
                                                 :else x))
                  :time/local-date-time (fn [x] (cond (instance? java.sql.Timestamp x) (.toLocalDateTime ^java.sql.Timestamp x)
                                                      (instance? java.time.Instant x) (java.time.LocalDateTime/ofInstant ^java.time.Instant x zone)
                                                      :else x))}}))))

;;; datasets: several tables at once, with keys and references checked

(defn- schema-of
  "The PostgreSQL schema a registry name belongs to (:pg.public/users -> \"public\")."
  [k]
  (subs (if (keyword? k) (namespace k) (first (str/split k #"/" 2))) 3))

(defn- tables
  "[{:name :table :key-sets :refs} ...] for every row schema: :table is schema-qualified, as are
   the targets in :refs ([[keys table keys] ...]); references to tables outside the registry are left out."
  [registry]
  (let [ts (for [k (sort-by str (keys registry)) :when (row-name? registry k)
                 :let [{:keys [pg/table pg/primary-key pg/unique pg/foreign-keys]} (m/properties (columns registry k))
                       schema (schema-of k)]]
             {:name k
              :table (str schema "." table)
              :key-sets (remove nil? (cons primary-key unique))
              :refs (for [[cols to-table to] foreign-keys]
                      [(mapv keyword cols) (if (str/includes? to-table ".") to-table (str schema "." to-table)) (mapv keyword to)])})
        known (set (map :table ts))]
    (mapv (fn [t] (update t :refs #(vec (filter (comp known second) %)))) ts)))

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
  "Schema for {\"schema.table\" [row ...] ...} covering every table of the registry: rows validate
   against their row schemas, primary keys and unique constraints hold within a table, and
   foreign keys point at existing rows. The result contains functions, so it is built at runtime."
  [registry]
  (let [ts (tables registry)]
    [:and
     (into [:map] (for [t ts] [(:table t) {:optional true} [:vector (:name t)]]))
     [:fn {:error/message "primary keys and unique constraints"}
      (fn [ds]
        (every? (fn [{:keys [table key-sets]}]
                  (every? (fn [cols] (let [ks (->> (get ds table) (map (fn [r] (mapv #(get r (keyword %)) cols))) (remove #(some nil? %)))]
                                       (= (count ks) (count (distinct ks)))))
                          key-sets))
                ts))]
     [:fn {:error/message "foreign keys"}
      (fn [ds]
        (every? (fn [{:keys [table refs]}]
                  (every? (fn [[ks to-table to]]
                            (let [targets (set (map (fn [r] (mapv #(get r %) to)) (get ds to-table)))]
                              (every? (fn [r] (let [v (mapv #(get r %) ks)]
                                                (or (some nil? v) (contains? targets v))))
                                      (get ds table))))
                          refs))
                ts))]]))

(defn- topological [ts]
  (loop [done [] left (set (map :table ts))]
    (if (empty? left)
      done
      (let [deps (into {} (map (fn [t] [(:table t) (map second (:refs t))]) ts))
            ready (sort (filter #(every? (fn [d] (or (= d %) (not (left d)))) (deps %)) left))]
        (when (empty? ready) (throw (ex-info "cyclic foreign keys" {:left left})))
        (recur (into done ready) (reduce disj left ready))))))

(defn- point-at
  "Rows with their referencing columns redirected at rows of the target tables; a reference that
   already points at a row is kept, and NULL when there is nothing to point at."
  [rows refs ds]
  (let [fix (fn [row [ks to-table to]]
              (let [targets (seq (remove #(some nil? %) (map (fn [r] (mapv #(get r %) to)) (get ds to-table))))
                    v (mapv #(get row %) ks)]
                (cond (some nil? v) row
                      (some #{v} targets) row
                      targets (merge row (zipmap ks (nth targets (mod (hash v) (count targets)))))
                      :else (merge row (zipmap ks (repeat nil))))))]
    ;; composite references first, so a single-column one cannot break them
    (map (fn [row] (reduce fix row (sort-by (comp - count first) refs))) rows)))

(defn dataset-generator
  "test.check generator of datasets that satisfy dataset-schema: tables are generated in
   foreign-key order and referencing columns draw from the rows generated before them (a
   self-reference draws from the same table). :rows is the number of rows tried per table;
   rows that end up violating a constraint are dropped."
  ([registry] (dataset-generator registry {}))
  ([registry {:keys [rows] :or {rows 5}}]
   (let [ts (tables registry)
         by-table (into {} (map (juxt :table identity) ts))
         gen (requiring-resolve 'clojure.test.check.generators/bind)
         fmap (requiring-resolve 'clojure.test.check.generators/fmap)
         return (requiring-resolve 'clojure.test.check.generators/return)
         vector-of (requiring-resolve 'clojure.test.check.generators/vector)
         opts {:registry registry}
         table-gen (fn [{:keys [name table refs key-sets]} ds]
                     (let [valid (fn [rs] (-> (filter #(m/validate name % opts) rs) (distinct-by-keys key-sets) vec))
                           {self true others false} (group-by #(= table (second %)) refs)]
                       (fmap (fn [rs]
                               (let [rs (valid (point-at rs others ds))]
                                 (if self (valid (point-at rs self (assoc ds table rs))) rs)))
                             (vector-of (mg/generator (columns registry name) opts) rows))))]
     (reduce (fn [g table]
               (gen g (fn [ds] (fmap (fn [rs] (assoc ds table rs)) (table-gen (by-table table) ds)))))
             (return {})
             (topological ts)))))
