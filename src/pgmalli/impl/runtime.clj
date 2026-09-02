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
            [malli.util :as mu]
            [pgmalli.impl.eval :as check]))

(def check-schema
  "[:pg/check expr]: a row passes when the CHECK expression data does (pgmalli.impl.eval).
   A column missing from the row is NULL, or its value in the :pg/defaults property."
  (m/-simple-schema
   {:type :pg/check
    :compile (fn [{:keys [pg/defaults]} [expr] _]
               (let [pass? (check/checker expr)]
                 {:pred (fn [row] (and (map? row) (pass? (merge defaults row)))) :min 1 :max 1}))}))

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

(defn- omitted-values
  "What the database stores for a column left out of an INSERT: {column literal} for literal
   defaults; columns whose default is an expression are in :unknown."
  [entries]
  (reduce (fn [acc e]
            (let [[k _ s] (entry-parts e) props (column-props s)]
              (cond (contains? props :default) (assoc-in acc [:literal k] (:default props))
                    (contains? props :pg/default) (update acc :unknown conj k)
                    :else acc)))
          {:literal {} :unknown #{}}
          entries))

(defn- as-insert-sees-it
  "A table constraint with omitted columns standing for what the database stores in them:
   fragment keys are optional unless NULL (or the literal default) would violate them, a :multi
   on a column with a literal default takes that value's branch when the column is absent, and
   a :pg/check evaluates the literal defaults."
  [schema {:keys [literal unknown]} registry]
  (letfn [(entry [e] (let [[k p s] (entry-parts e)]
                       (if (or (unknown k) (m/validate s (get literal k) {:registry registry}))
                         [k (assoc p :optional true) (go s)]
                         [k p (go s)])))
          (go [f]
            (if-not (vector? f)
              f
              (case (first f)
                :pg/check (let [[_ p e] (if (map? (second f)) f [:pg/check {} (second f)])]
                            [:pg/check (assoc p :pg/defaults literal) e])
                :map (if (map? (second f)) f (into [:map] (map entry (rest f))))
                :multi (let [f (mapv go f)
                             absent (some (fn [[v s]] (when (= v (get literal (:dispatch (second f)))) s)) (drop 2 f))]
                         (cond-> f absent (conj [nil absent])))
                (mapv go f))))]
    (go schema)))

(defn- insert-schema
  "What an INSERT may carry: identity ALWAYS and generated columns removed, columns with a
   default or NULL optional, closed map; then the table constraints, see as-insert-sees-it."
  [row registry]
  (let [[_ props & entries] (row-map row)
        m (into [:map (assoc props :closed true)] (keep insert-entry entries))
        omitted (omitted-values entries)]
    (if (= :and (first row))
      (into [:and m] (map #(as-insert-sees-it % omitted registry) (drop 2 row)))
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
  (into registry (for [[k s] registry :when (row-name? registry k)] [(insert-name k) (insert-schema s registry)])))

(defn registry
  "The registry pgmalli.core/registry documents."
  [& schemas]
  (let [base (merge (m/default-schemas) (mu/schemas) (time/schemas) {:pg/check check-schema})
        generated (apply merge (map #(:registry (if (map? %) % (read-generated %))) schemas))]
    (doseq [[k s] generated
            :let [table (when (vector? s) (:pg/table (second (row-map s))))]
            :when (and (string? table) (not (str/includes? table ".")))]
      (throw (ex-info (str k " was generated by an older pgmalli (:pg/table is not schema-qualified); run generate! again") {:name k})))
    (merge base (with-inserts (merge base generated)) generated)))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints."
  [registry name]
  (let [s (m/deref (m/schema name {:registry registry}))]
    (if (= :and (m/type s)) (first (m/children s)) s)))

(defn transformer
  "Decodes JDBC and string values into the registry's types: java.sql.Timestamp and
   java.util.Date -> Instant, java.sql.Date -> LocalDate, an Instant or java.util.Date landing
   in a date or timestamp (without time zone) column is read in :zone, default the JVM's."
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

(defn- tables
  "[{:name :table :key-sets :refs} ...] for every row schema; :refs is [[keys table keys] ...],
   references to tables outside the registry left out."
  [registry]
  (let [ts (for [k (sort-by str (keys registry)) :when (row-name? registry k)
                 :let [{:keys [pg/table pg/primary-key pg/unique pg/foreign-keys]} (m/properties (columns registry k))]]
             {:name k
              :table table
              :key-sets (remove nil? (cons primary-key unique))
              :refs (for [{:keys [columns table to]} foreign-keys] [(mapv keyword columns) table (mapv keyword to)])})
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
                           {self true others false} (group-by #(= table (second %)) refs)
                           ;; a dropped row may be what another row points at, so repeat until the batch is stable
                           self-consistent (fn [rs]
                                             (let [rs2 (valid (point-at rs self (assoc ds table rs)))]
                                               (if (= (count rs2) (count rs)) rs2 (recur rs2))))]
                       (fmap (fn [rs]
                               (let [rs (valid (point-at rs others ds))]
                                 (if self (self-consistent rs) rs)))
                             (vector-of (mg/generator (columns registry name) opts) rows))))]
     (reduce (fn [g table]
               (gen g (fn [ds] (fmap (fn [rs] (assoc ds table rs)) (table-gen (by-table table) ds)))))
             (return {})
             (topological ts)))))
