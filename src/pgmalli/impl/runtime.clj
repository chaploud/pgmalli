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
            [pgmalli.impl.eval :as check]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.render :as render]))

(def check-schema
  "[:pg/check expr]: a row passes when the CHECK expression data does (pgmalli.impl.eval).
   A column missing from the row is NULL, or its value in the :pg/defaults property."
  (m/-simple-schema
   {:type :pg/check
    :compile (fn [{:keys [pg/defaults]} [expr] _]
               (let [pass? (check/checker expr)]
                 {:pred (fn [row] (and (map? row) (pass? (merge defaults row)))) :min 1 :max 1}))}))

(def check-value-schema
  "[:pg/check-value expr]: a domain CHECK, the value standing for VALUE."
  (m/-simple-schema
   {:type :pg/check-value
    :compile (fn [_ [expr] _]
               (let [pass? (check/checker expr)]
                 {:pred (fn [v] (pass? {:VALUE v})) :min 1 :max 1}))}))

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

(defn- gen-hints
  "Generation hints (:gen/min, :gen/max) for a column schema: key and identity integers are
   small and positive, strings short, times within the last year. Data the database accepts
   but nobody wants in a fixture is left to the schema's own bounds."
  [s key?]
  (let [[t p] (if (and (vector? s) (map? (second s))) [(first s) (second s)] [(if (vector? s) (first s) s) {}])
        now (java.time.Instant/now)
        hints (case t
                :int (when key? {:gen/min (max 1 (:min p Long/MIN_VALUE)) :gen/max (min 100000 (:max p Long/MAX_VALUE))})
                :string (when (or (nil? (:max p)) (> (:max p) 24)) {:gen/max (max (:min p 0) 24)})
                :time/instant {:gen/min (.minus now (java.time.Duration/ofDays 365)) :gen/max now}
                :time/local-date-time (let [n (java.time.LocalDateTime/now)] {:gen/min (.minusDays n 365) :gen/max n})
                :time/local-date (let [n (java.time.LocalDate/now)] {:gen/min (.minusDays n 365) :gen/max n})
                nil)]
    (if (and hints (not-any? #(contains? p %) [:gen/min :gen/max :gen/gen :gen/schema]))
      (if (map? (second s)) (assoc s 1 (merge p hints)) (into [t hints] (rest s)))
      s)))

(defn- with-gen-hints [row]
  (let [[_ props & entries] (row-map row)
        key-cols (set (map render/ident-key (concat (:pg/primary-key props) (mapcat :columns (:pg/unique props)) (mapcat :columns (:pg/foreign-keys props)))))
        m (into [:map props]
                (map (fn [e] (let [[k p s] (entry-parts e)
                                   key? (or (key-cols k) (:pg/identity (column-props s)))
                                   s (if (and (vector? s) (= :maybe (first s))) [:maybe (gen-hints (last s) key?)] (gen-hints s key?))]
                               (if (empty? p) [k s] [k p s])))
                     entries))]
    (if (= :and (first row)) (into [:and m] (drop 2 row)) m)))

(defn registry
  "The registry pgmalli.core/registry documents."
  [& schemas]
  (let [base (merge (m/default-schemas) (mu/schemas) (time/schemas) {:pg/check check-schema :pg/check-value check-value-schema})
        generated (apply merge (map #(:registry (if (map? %) % (read-generated %))) schemas))
        row? (fn [s] (and (vector? s) (string? (:pg/table (second (row-map s))))))]
    (doseq [[k s] generated :when (and (row? s) (not (str/includes? (:pg/table (second (row-map s))) ".")))]
      (throw (ex-info (str k " was generated by an older pgmalli (:pg/table is not schema-qualified); run generate! again") {:name k})))
    (let [generated (into {} (map (fn [[k s]] [k (if (row? s) (with-gen-hints s) s)])) generated)]
      (merge base (with-inserts (merge base generated)) generated))))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints."
  [registry name]
  (let [s (m/deref (m/schema name {:registry registry}))]
    (if (= :and (m/type s)) (first (m/children s)) s)))

(defn transformer
  "Decodes JDBC and string values into the registry's types: java.sql.Timestamp and
   java.util.Date -> Instant, java.sql.Date -> LocalDate, an Instant or java.util.Date landing
   in a date or timestamp (without time zone) column is read in :zone, default the JVM's;
   JSON text in a json or jsonb column is parsed."
  ([] (transformer {}))
  ([{:keys [zone] :or {zone (java.time.ZoneId/systemDefault)}}]
   (let [instant (fn [x] (if (instance? java.util.Date x) (.toInstant ^java.util.Date x) x))]
     (mt/transformer
      mt/string-transformer
      {:name :pgmalli
       :decoders {:any {:compile (fn [schema _]
                                  (when (#{"json" "jsonb"} (:pg/type (m/properties schema)))
                                    (fn [x] (if (string? x) (json/parse x) x))))}
                  :time/instant instant
                  :time/local-date (fn [x] (cond (instance? java.sql.Date x) (.toLocalDate ^java.sql.Date x)
                                                 (instance? java.util.Date x) (.toLocalDate (.atZone ^java.time.Instant (instant x) zone))
                                                 :else x))
                  :time/local-date-time (fn [x] (cond (instance? java.sql.Timestamp x) (.toLocalDateTime ^java.sql.Timestamp x)
                                                      (instance? java.time.Instant x) (java.time.LocalDateTime/ofInstant ^java.time.Instant x zone)
                                                      :else x))}}))))

;;; datasets: several tables at once, with keys and references checked

(defn- tables
  "[{:name :table :key-sets :refs} ...] for every row schema. A key set is {:keys :nulls-distinct?
   :label}; a reference {:keys :table :to :full? :label}, references to tables outside the
   registry left out."
  [registry]
  (let [ts (for [k (sort-by str (keys registry)) :when (row-name? registry k)
                 :let [{:keys [pg/table pg/primary-key pg/unique pg/foreign-keys]} (m/properties (columns registry k))]]
             {:name k
              :table table
              :key-sets (concat (when primary-key
                                  [{:keys (mapv render/ident-key primary-key) :nulls-distinct? true
                                    :label (str table " primary key " (pr-str primary-key))}])
                                (for [{:keys [columns nulls-distinct]} unique]
                                  {:keys (mapv render/ident-key columns) :nulls-distinct? (not (false? nulls-distinct))
                                   :label (str table " unique " (pr-str columns))}))
              :refs (for [{:keys [columns to match] target :table} foreign-keys]
                      {:keys (mapv render/ident-key columns) :table target :to (mapv render/ident-key to) :full? (= :full match)
                       :label (str table " " (pr-str columns) " references " target " " (pr-str to))})})
        known (set (map :table ts))]
    (mapv (fn [t] (update t :refs #(vec (filter (comp known :table) %)))) ts)))

(defn- key-of [row ks] (mapv #(get row %) ks))

(defn- duplicate-keys?
  "Whether two rows share a key; a key with a NULL counts only under NULLS NOT DISTINCT."
  [rows {:keys [keys nulls-distinct?]}]
  (let [ks (cond->> (map #(key-of % keys) rows) nulls-distinct? (remove #(some nil? %)))]
    (not= (count ks) (count (distinct ks)))))

(defn- dangling?
  "Whether a row's reference points nowhere: an all-NULL key passes, a partly NULL one passes
   unless MATCH FULL, anything else must exist in the target."
  [row {:keys [keys to table full?]} ds]
  (let [v (key-of row keys)
        nils (count (filter nil? v))]
    (cond (= nils (count v)) false
          (pos? nils) full?
          :else (not (some #(= v (key-of % to)) (get ds table))))))

(defn- distinct-by-keys
  "Keeps the first row for every combination of the columns in each key set."
  [rows key-sets]
  (first (reduce (fn [[kept seen] row]
                   (let [ks (keep (fn [{:keys [keys nulls-distinct?]}]
                                    (let [k (key-of row keys)] (when-not (and nulls-distinct? (some nil? k)) [keys k])))
                                  key-sets)]
                     (if (some seen ks) [kept seen] [(conj kept row) (into seen ks)])))
                 [[] #{}] rows)))

(defn dataset-schema
  "Schema for {\"schema.table\" [row ...] ...} covering every table of the registry: rows validate
   against their row schemas, and every primary key, unique constraint and foreign key is a
   check of its own, named in its error. The result contains functions, so it is built at runtime."
  [registry]
  (let [ts (tables registry)]
    (into [:and (into [:map] (for [t ts] [(:table t) {:optional true} [:vector (:name t)]]))]
          (concat (for [t ts, k (:key-sets t)]
                    [:fn {:error/message (:label k)} (fn [ds] (not (duplicate-keys? (get ds (:table t)) k)))])
                  (for [t ts, r (:refs t)]
                    [:fn {:error/message (:label r)} (fn [ds] (not-any? #(dangling? % r ds) (get ds (:table t))))])))))

(defn- topological [ts]
  (loop [done [] left (set (map :table ts))]
    (if (empty? left)
      done
      (let [deps (into {} (map (fn [t] [(:table t) (map :table (:refs t))]) ts))
            ready (sort (filter #(every? (fn [d] (or (= d %) (not (left d)))) (deps %)) left))]
        (when (empty? ready) (throw (ex-info "cyclic foreign keys" {:left left})))
        (recur (into done ready) (reduce disj left ready))))))

(defn- solve-refs
  "The row with its references pointing at rows of ds. References sharing columns are solved
   together: a later reference may only choose targets that agree with the columns an earlier
   one fixed (or in fixed already), and the search backtracks. A reference with nothing to
   point at becomes NULL. nil when no assignment exists."
  [row refs ds fixed]
  (letfn [(go [row fixed refs]
            (if (empty? refs)
              row
              (let [{:keys [keys to table]} (first refs)
                    v (key-of row keys)]
                (if (every? nil? v)
                  (go row fixed (rest refs))
                  (let [candidates (->> (get ds table) (map #(key-of % to)) (remove #(some nil? %)) distinct
                                        (filter (fn [t] (every? (fn [[k x]] (or (not (fixed k)) (= (get row k) x))) (map vector keys t)))))
                        n (count candidates)
                        ;; keep a reference that already holds, otherwise start from a hash of the row's value
                        ordered (if (some #{v} candidates)
                                  (cons v (remove #{v} candidates))
                                  (let [i (if (pos? n) (mod (hash v) n) 0)] (concat (drop i candidates) (take i candidates))))]
                    (or (some #(go (merge row (zipmap keys %)) (into fixed keys) (rest refs)) ordered)
                        (when (not-any? fixed keys)
                          (go (merge row (zipmap keys (repeat nil))) (into fixed keys) (rest refs)))))))))]
    (go row fixed (sort-by (comp - count :keys) refs))))

(defn dataset-generator
  "test.check generator of datasets that satisfy dataset-schema: tables are generated in
   foreign-key order and referencing columns are pointed at rows generated before them (a
   self-reference at the same table). :rows is the number of rows tried per table; rows that
   end up violating a constraint are dropped."
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
                           {self true others false} (group-by #(= table (:table %)) refs)
                           ;; self-references keep the columns other references fixed, and a dropped row
                           ;; may be what another row points at, so repeat until the batch is stable
                           settled (set (mapcat :keys others))
                           self-consistent (fn [rs]
                                             (let [rs2 (valid (keep #(solve-refs % self (assoc ds table rs) settled) rs))]
                                               (if (= (count rs2) (count rs)) rs2 (recur rs2))))]
                       (fmap (fn [rs]
                               (let [rs (valid (keep #(solve-refs % others ds #{}) rs))]
                                 (if self (self-consistent rs) rs)))
                             (vector-of (mg/generator (columns registry name) opts) rows))))]
     (reduce (fn [g table]
               (gen g (fn [ds] (fmap (fn [rs] (assoc ds table rs)) (table-gen (by-table table) ds)))))
             (return {})
             (topological ts)))))
