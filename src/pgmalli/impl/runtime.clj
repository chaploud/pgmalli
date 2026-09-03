(ns pgmalli.impl.runtime
  "The application side: generated files as malli registries, plus helpers that need malli.
   Generated files are read from the classpath as pgmalli/<schema>.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.error :as me]
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

(def bytes-schema
  "[:pg/bytes {:min n :max n}]: a byte array of bounded length, generated as such (malli's bytes?
   has no length)."
  (m/-simple-schema
   {:type :pg/bytes
    :compile (fn [{:keys [min max]} _ _]
               (let [fmap (requiring-resolve 'clojure.test.check.generators/fmap)
                     vector-of (requiring-resolve 'clojure.test.check.generators/vector)
                     byte-gen @(requiring-resolve 'clojure.test.check.generators/byte)]
                 {:pred (fn [v] (and (bytes? v) (<= (or min 0) (alength ^bytes v) (or max Integer/MAX_VALUE))))
                  :type-properties {:error/message (str "bytes, " (or min 0) " to " (or max "any") " of them")
                                    :gen/gen (fmap byte-array (vector-of byte-gen (or min 0) (or max (+ (or min 0) 16))))}
                  :min 0 :max 0}))}))

(defn- bounded-int
  "A schema type for an integer type of PostgreSQL: its range, narrowed by :min and :max
   properties from CHECKs, generated within :gen/min and :gen/max when given."
  [type lo hi]
  (m/-simple-schema
   {:type type
    :compile (fn [{:keys [min max] gen-min :gen/min gen-max :gen/max} _ _]
               (let [lo (clojure.core/max lo (or min lo)) hi (clojure.core/min hi (or max hi))
                     large-integer* (requiring-resolve 'clojure.test.check.generators/large-integer*)]
                 {:pred (fn [v] (and (int? v) (<= lo v hi)))
                  :type-properties {:error/message (str "should be an integer between " lo " and " hi)
                                    :gen/gen (large-integer* {:min (clojure.core/max lo (or gen-min lo)) :max (clojure.core/min hi (or gen-max hi))})}
                  :min 0 :max 0}))}))

(def smallint-schema (bounded-int :pg/smallint -32768 32767))

(def numeric-schema
  "[:pg/numeric {:precision p :scale s}]: a BigDecimal a numeric(p, s) column stores: rounded to
   s places (half up, as PostgreSQL rounds on the way in), fewer than p - s digits before the
   point. s may exceed p, or be negative, as PostgreSQL allows."
  (m/-simple-schema
   {:type :pg/numeric
    :compile (fn [{:keys [precision scale] :or {scale 0}} _ _]
               (let [limit (.movePointRight 1M (int (- precision scale)))
                     digits (int (clojure.core/min precision 18))
                     bound (dec (long (.longValueExact (.movePointRight 1M digits))))
                     fmap (requiring-resolve 'clojure.test.check.generators/fmap)
                     large-integer* (requiring-resolve 'clojure.test.check.generators/large-integer*)]
                 {:pred (fn [v] (and (decimal? v) (< (.abs (.setScale ^BigDecimal v (int scale) java.math.RoundingMode/HALF_UP)) limit)))
                  :type-properties {:error/message (str "should fit numeric(" precision ", " scale ")")
                                    :gen/gen (fmap #(.movePointLeft (BigDecimal/valueOf ^long %) (int scale)) (large-integer* {:min (- bound) :max bound}))}
                  :min 0 :max 0}))}))
(def integer-schema (bounded-int :pg/integer -2147483648 2147483647))

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
  [[k p s]]
  (if (map? p) [k p s] [k {} p]))

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

(defn insert-name
  ":pg.<schema>/<table> -> :pg.<schema>.<table>/insert, string keys alike."
  [row-name]
  (if (keyword? row-name)
    (keyword (str (namespace row-name) "." (name row-name)) "insert")
    (str (str/replace-first row-name "/" ".") "/insert")))

(defn- row-schema?
  "Row schemas carry :pg/table; their inserts do too, but closed."
  [s]
  (let [m (when (vector? s) (row-map s))
        props (when (vector? m) (second m))]
    (and (map? props) (string? (:pg/table props)) (not (:closed props)))))

(defn- with-inserts [registry]
  (into registry (for [[k s] registry :when (row-schema? s)] [(insert-name k) (insert-schema s registry)])))

(def ^:private json-value
  "What a json or jsonb column with no CHECK to shape it generates: small JSON values."
  [:or :string :int :boolean [:map-of {:max 3} :string [:or :string :int]] [:vector {:max 3} [:or :string :int]]])

(defn- gen-hints
  "Generation hints (:gen/min, :gen/max, :gen/schema) for a column schema: key and identity
   integers are small and positive, strings short, times within the last year, an unshaped
   json column a JSON value. Other columns keep the schema's own bounds."
  [s key?]
  (let [[t p] (if (and (vector? s) (map? (second s))) [(first s) (second s)] [(if (vector? s) (first s) s) {}])
        now (java.time.Instant/now)
        hints (case t
                (:int :pg/integer :pg/smallint) (when key?
                       (let [lo (max 1 (:min p Long/MIN_VALUE)) hi (min 100000 (:max p Long/MAX_VALUE))]
                         (when (<= lo hi) {:gen/min lo :gen/max hi})))
                :string (when (or (nil? (:max p)) (> (:max p) 24)) {:gen/max (max (:min p 0) 24)})
                :time/instant {:gen/min (.minus now (java.time.Duration/ofDays 365)) :gen/max now}
                :time/local-date-time (let [n (java.time.LocalDateTime/now)] {:gen/min (.minusDays n 365) :gen/max n})
                :time/local-date (let [n (java.time.LocalDate/now)] {:gen/min (.minusDays n 365) :gen/max n})
                (:any :some) (when (#{"json" "jsonb"} (:pg/type p)) {:gen/schema json-value})
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
  (let [base (merge (m/default-schemas) (mu/schemas) (time/schemas)
                    {:pg/check check-schema :pg/check-value check-value-schema :pg/bytes bytes-schema
                     :pg/smallint smallint-schema :pg/integer integer-schema :pg/numeric numeric-schema})
        generated (apply merge (map #(:registry (if (map? %) % (read-generated %))) schemas))]
    (doseq [[k s] generated :when (row-schema? s)
            :let [{:keys [pg/table pg/unique pg/foreign-keys]} (second (row-map s))]
            :when (or (not (str/includes? table ".")) (some vector? unique) (some vector? foreign-keys))]
      (throw (ex-info (str k " was generated by an older pgmalli; run generate! again") {:name k})))
    (let [generated (into {} (map (fn [[k s]] [k (if (row-schema? s) (with-gen-hints s) s)])) generated)]
      (merge base (with-inserts (merge base generated)) generated))))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints."
  [registry name]
  (let [s (m/deref (m/schema name {:registry registry}))]
    (if (= :and (m/type s)) (first (m/children s)) s)))

;;; the same schemas in other shapes

(defn- entries [schema] (drop (if (map? (second schema)) 2 1) schema))

(defn column-entries
  "[[column props schema] ...] of a row or insert schema as data."
  [schema]
  (map entry-parts (filter vector? (entries (row-map schema)))))

(defn read-time
  "Schema data with the time types a driver returns under :time: :instant when timestamps
   arrive as Instants (dates stay java.sql.Date, hence inst?), :local when timestamptz arrives
   as LocalDateTime, :inst when the schema is read without malli.experimental.time."
  [time schema]
  (walk/postwalk (fn [f] (case [time f]
                           [:instant :time/local-date-time] :time/instant
                           [:instant :time/local-date] 'inst?
                           [:local :time/instant] :time/local-date-time
                           (if (and (= :inst time) (keyword? f) (= "time" (namespace f))) 'inst? f)))
                 schema))

(defn- without-gen
  "Schema data without the generation hints the registry added when it was loaded."
  [schema]
  (walk/postwalk #(if (map? %) (dissoc % :gen/min :gen/max :gen/schema) %) schema))

(defn- data-columns
  "The row map of a generated schema as data (columns gives the malli schema)."
  [registry name]
  (let [s (get registry name)]
    (when-not (vector? s) (throw (ex-info (str name " is not a generated schema") {:name name})))
    (row-map s)))

(defn column
  "The schema of one column of a row or insert schema, as data (with its [:maybe ...])."
  [registry name col]
  (let [k (render/ident-key (clojure.core/name col))]
    (some (fn [[ek _ s]] (when (= k ek) (without-gen s))) (column-entries (get registry name)))))

(defn non-null
  "A column schema without its [:maybe ...]: the type a value must have when it is not NULL."
  [schema]
  (if (and (vector? schema) (= :maybe (first schema))) (last schema) schema))

(def ^:private int-ranges {:pg/smallint [-32768 32767] :pg/integer [-2147483648 2147483647]})

(defn- merge-props
  "Schema data with properties merged in (plainly; render's with-props narrows bounds)."
  [s p]
  (cond (empty? p) s
        (and (vector? s) (map? (second s))) (assoc s 1 (merge (second s) p))
        (vector? s) (into [(first s) p] (rest s))
        :else [s p]))

(defn- portable-node [registry f]
  (let [[t p] (if (vector? f) [(first f) (when (map? (second f)) (second f))] [f nil])]
    (cond
      (int-ranges t) (let [[lo hi] (int-ranges t) p (or p {})]
                       [:int (assoc p :min (max lo (:min p lo)) :max (min hi (:max p hi)))])
      (and (vector? f) (= :pg/bytes t)) 'bytes?
      (and (vector? f) (= :pg/numeric t)) 'decimal?
      (and (vector? f) (= :ref t) (contains? registry (last f)))
      ;; the inlined target is converted here: prewalk walks its children, not the node itself
      (portable-node registry (merge-props (get registry (last f)) p))
      (and (vector? f) (= :and t))
      ;; without the CHECKs only pgmalli evaluates
      (let [parts (remove #(and (vector? %) (#{:pg/check :pg/check-value} (first %))) (entries f))]
        (if (= 1 (count parts)) (merge-props (first parts) p) (into (if p [:and p] [:and]) parts)))
      :else f)))

(defn portable-data
  "Schema data from the registry as data malli's default registry reads; see portable."
  [registry schema]
  (without-gen (walk/prewalk #(portable-node registry %) schema)))

(defn portable
  "The schema named in the registry as data malli's default registry reads (with
   malli.experimental.time for the time types): references to the schema's own types inlined,
   :pg/smallint and :pg/integer as bounded :int, :pg/bytes as bytes?, generation hints dropped.
   The CHECKs only pgmalli evaluates (:pg/check, :pg/check-value) are left out, so this is
   weaker than the registry's schema; use it where the registry cannot follow."
  [registry name]
  (portable-data registry (get registry name)))

(defn as-read
  "The [:map ...] of a row as a JDBC result builder returns it; the options are documented on
   pgmalli.core/as-read."
  [registry name {:keys [qualified? kebab? nil-columns time]}]
  (let [m (without-gen (data-columns registry name))
        props (when (map? (second m)) (second m))
        kebab (fn [s] (cond-> s kebab? (str/replace "_" "-")))
        table (some-> (or (:pg/table props) (:pg/view props)) (str/split #"\." 2) second kebab)
        key* (fn [k] (let [s (kebab (clojure.core/name k))]
                       (cond (not qualified?) (if (keyword? k) (keyword s) s)
                             (keyword? k) (keyword table s)
                             :else (str table "/" s))))
        entry (fn [[k p s]] (let [s (read-time time s)
                            absent? (and (= :absent nil-columns) (vector? s) (= :maybe (first s)))
                            p (cond-> p absent? (assoc :optional true))
                            s (if absent? (last s) s)]
                        (if (empty? p) [(key* k) s] [(key* k) p s])))]
    (into (if props [:map props] [:map]) (map entry (column-entries m)))))

(defn transformer
  "Decodes JDBC and string values into the registry's types: java.sql.Timestamp and
   java.util.Date -> Instant, java.sql.Date -> LocalDate, an Instant or java.util.Date landing
   in a date or timestamp (without time zone) column is read in :zone, default the JVM's;
   JSON text in a json or jsonb column is parsed."
  ([] (transformer {}))
  ([{:keys [zone] :or {zone (java.time.ZoneId/systemDefault)}}]
   (let [instant (fn [x] (cond (instance? java.sql.Date x) (.toInstant (.atStartOfDay (.toLocalDate ^java.sql.Date x) zone))
                               (instance? java.util.Date x) (.toInstant ^java.util.Date x)
                               :else x))]
     (mt/transformer
      mt/string-transformer
      {:name :pgmalli
       :decoders {:any {:compile (fn [schema _]
                                  (when (#{"json" "jsonb"} (:pg/type (m/properties schema)))
                                    (fn [x] (if (string? x) (json/parse x) x))))}
                  :time/instant instant
                  :time/local-date (fn [x] (cond (instance? java.sql.Date x) (.toLocalDate ^java.sql.Date x)
                                                 (or (instance? java.util.Date x) (instance? java.time.Instant x)) (.toLocalDate (.atZone ^java.time.Instant (instant x) zone))
                                                 :else x))
                  :time/local-date-time (fn [x] (cond (instance? java.sql.Timestamp x) (.toLocalDateTime ^java.sql.Timestamp x)
                                                      (or (instance? java.util.Date x) (instance? java.time.Instant x)) (java.time.LocalDateTime/ofInstant (instant x) zone)
                                                      :else x))}}))))

;;; datasets: several tables at once, with keys and references checked

(defn- tables
  "[{:name :table :key-sets :refs} ...] for every row schema but those in except. A key set is
   {:columns :nulls-distinct? :label}; a reference {:columns :table :to :full? :label},
   references to tables outside the dataset left out."
  ([registry] (tables registry nil))
  ([registry except]
   (let [ts (for [[k s] (sort-by (comp str key) registry)
                  :when (and (row-schema? s) (not (contains? (set except) (:pg/table (second (row-map s))))))
                 :let [{:keys [pg/table pg/primary-key pg/unique pg/foreign-keys]} (second (row-map s))]]
             {:name k
              :table table
              :key-sets (concat (when primary-key
                                  [{:columns (mapv render/ident-key primary-key) :nulls-distinct? true
                                    :label (str table " primary key " (pr-str primary-key))}])
                                (for [{:keys [columns nulls-distinct]} unique]
                                  {:columns (mapv render/ident-key columns) :nulls-distinct? (not (false? nulls-distinct))
                                   :label (str table " unique " (pr-str columns))}))
              :refs (for [{:keys [columns to match] target :table} foreign-keys]
                      {:columns (mapv render/ident-key columns) :table target :to (mapv render/ident-key to) :full? (= :full match)
                       :label (str table " " (pr-str columns) " references " target " " (pr-str to))})})
         known (set (map :table ts))
         excepted (set except)]
     (doseq [t ts, r (:refs t) :when (excepted (:table r))]
       (throw (ex-info (str (:table t) " references " (:table r) ", which :except leaves out") {:table (:table t) :references (:table r)})))
     (mapv (fn [t] (update t :refs #(vec (filter (comp known :table) %)))) ts))))

(defn- key-of [row columns] (mapv #(get row %) columns))

(defn- counting-key
  "The key value of a row for a key set, or nil when its NULLs mean it cannot conflict."
  [row {:keys [columns nulls-distinct?]}]
  (let [k (key-of row columns)]
    (when-not (and nulls-distinct? (some nil? k)) k)))

(defn- duplicate-keys? [rows key-set]
  (let [ks (keep #(counting-key % key-set) rows)]
    (not= (count ks) (count (distinct ks)))))

(defn- dangling?
  "Whether a row's reference points nowhere: an all-NULL key passes, a partly NULL one passes
   unless MATCH FULL, anything else must exist in the target."
  [row {:keys [columns to table full?]} ds]
  (let [v (key-of row columns)
        nils (count (filter nil? v))]
    (cond (= nils (count v)) false
          (pos? nils) full?
          :else (not (some #(= v (key-of % to)) (get ds table))))))

(defn- distinct-by-keys
  "Keeps the first row for every combination of the columns in each key set."
  [rows key-sets]
  (first (reduce (fn [[kept seen] row]
                   (let [ks (keep (fn [ks] (when-let [k (counting-key row ks)] [(:columns ks) k])) key-sets)]
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

(defn- try-order
  "Candidates with the value the row already holds first, otherwise rotated by the value's hash
   so different rows pick different targets."
  [v candidates]
  (if (some #{v} candidates)
    (cons v (remove #{v} candidates))
    (let [n (count candidates)]
      (if (zero? n) candidates (let [i (mod (hash v) n)] (concat (drop i candidates) (take i candidates)))))))

(defn- solve-refs
  "[row ds] with the row's references pointing at rows of ds and the row valid?, or nil.
   References sharing columns are solved together: a later reference may only choose targets
   that agree with the columns an earlier reference fixed (or that were fixed on entry), and
   the search backtracks over targets until valid? holds. A reference holding a NULL is left
   alone where PostgreSQL accepts it as it is. When no target fits, grow offers
   datasets with one more row in the target table that carries the columns already fixed,
   tried in turn; failing that, the reference's free columns become NULL. A reference to own,
   the row's own table, never picks the row itself."
  [row refs ds fixed own valid? grow]
  (letfn [(go [row ds fixed refs grow]
            (if (empty? refs)
              (when (valid? row) [row ds])
              (let [{:keys [columns to table full?]} (first refs)
                    v (key-of row columns)]
                (if (or (every? nil? v) (and (not full?) (some nil? v)))
                  ;; a reference with a NULL is left as it is: PostgreSQL accepts it (all NULL, or any NULL
                  ;; under MATCH SIMPLE), and a NULL a branch chose must stay one. Under MATCH FULL no later
                  ;; reference may fill part of an all-NULL key
                  (go row ds (cond-> fixed full? (into columns)) (rest refs) grow)
                  (let [targets (fn [ds] (cond->> (->> (get ds table) (map #(key-of % to)) (remove #(some nil? %)) distinct)
                                           (= table own) (remove #(= % (key-of row to)))
                                           true (filter (fn [t] (every? (fn [[k x]] (or (not (fixed k)) (= (get row k) x))) (map vector columns t))))))
                        attempt (fn [ds grow]
                                  (some #(go (merge row (zipmap columns %)) ds (into fixed columns) (rest refs) grow) (try-order v (targets ds))))
                        ;; what a grown parent must carry: the target columns the row already fixed
                        pins (into {} (keep (fn [[c t]] (when (fixed c) [t (get row c)])) (map vector columns to)))]
                    (or (attempt ds grow)
                        ;; a row grows at most one parent per reference, so the search stays bounded
                        (when (and grow (not= table own)) (some #(attempt % nil) (grow table ds pins)))
                        (let [free (remove fixed columns)]
                          (when (if full? (= (count free) (count columns)) (seq free))
                            (go (merge row (zipmap free (repeat nil))) ds (into fixed columns) (rest refs) grow)))))))))]
    (go row ds fixed (sort-by (comp - count :columns) refs) grow)))

(defn- fill-branches
  "The row with the columns a :multi or :or of the row schema constrains regenerated from the
   branch the row falls in (the value of the dispatch column, or an alternative picked by
   seed), so branching CHECKs hold by construction instead of by chance."
  [registry gen-of name row seed]
  (let [generate (requiring-resolve 'clojure.test.check.generators/generate)
        schema (get registry name)
        columns (into {} (map (fn [[k _ s]] [k s])) (column-entries schema))
        fragment? (fn [f] (and (vector? f) (= :map (first f))))
        ;; a fragment saying only "not NULL" generates from the column, not from :some
        source (fn [k s] (if (= :some (if (vector? s) (first s) s)) (non-null (get columns k s)) s))
        fill (fn [row frag i]
               (reduce (fn [row [j e]] (let [[k _ s] (entry-parts e)] (assoc row k (generate (gen-of (source k s)) 30 (+ seed i j)))))
                       row
                       (map-indexed vector (rest frag))))]
    (if (= :and (first schema))
      (reduce (fn [row [i part]]
                (case (first part)
                  ;; a row whose dispatch value has no branch is moved to a branch (the default only
                  ;; passes a NULL dispatch, which a NOT NULL column cannot hold)
                  :multi (let [dk (:dispatch (second part))
                               branches (remove #(= :malli.core/default (first %)) (drop 2 part))
                               hit (some (fn [[v s]] (when (= v (get row dk)) s)) branches)
                               [v frag] (if hit [(get row dk) hit] (when (seq branches) (nth branches (mod (hash [seed i]) (count branches)))))]
                           (if (fragment? frag) (fill (assoc row dk v) frag (* 100 i)) row))
                  :or (let [alts (filterv fragment? (drop 2 part))]
                        (if (seq alts) (fill row (nth alts (mod (hash [seed i]) (count alts))) (* 100 i)) row))
                  row))
              row
              (map-indexed vector (drop 2 schema)))
      row)))

(defn- candidates
  "Up to n rows from a table's row generator (gen-of, memoized per schema), generated from seed
   at a size where keys rarely collide, their branching CHECKs filled in. Lazy, in chunks, so a
   table that fills from a few rows never generates the rest; no shrink tree is built, so
   large datasets stay cheap."
  [registry gen-of name n seed]
  (let [generate (requiring-resolve 'clojure.test.check.generators/generate)
        vector-of (requiring-resolve 'clojure.test.check.generators/vector)
        scale (requiring-resolve 'clojure.test.check.generators/scale)
        chunk 25]
    (->> (range 0 n chunk)
         (mapcat (fn [start] (generate (vector-of (scale #(max % 30) (gen-of (columns registry name))) (min chunk (- n start))) 30 (+ seed start))))
         (map-indexed (fn [i row] (fill-branches registry gen-of name row (+ seed (* 1000 i))))))))

(defn- failure-reasons
  "Why a table came out short, most frequent reason first: what malli explains about the
   candidate rows on their own, or, for rows fine on their own, the references with no row to
   point at."
  [registry {:keys [name refs]} cands ds]
  (let [opts {:registry registry}
        reasons (for [c cands]
                  (or (some-> (m/explain name c opts) me/humanize pr-str)
                      (some (fn [{:keys [label table]}] (when (empty? (get ds table)) (str "nothing to reference: " label))) refs)
                      "no combination of referenced rows fits, or keys collide"))]
    (->> reasons frequencies (sort-by (comp - val)) (take 5) vec)))

(defn- generate-table
  "The dataset with the rows of one table added: candidates solved one by one (a row fits when
   it validates and collides with no key accepted before it; parents grow only while the batch
   is short), self-references settled, the batch topped up from the pool until it holds rows.
   A table that comes out short is recorded in the dataset's metadata under :pgmalli/short
   with what it wanted, what it got and why."
  [registry gen-of {:keys [name table refs key-sets] :as t} ds rows seed grow]
  (let [opts {:registry registry}
        ;; ponytail: the search is exhaustive per row; a budget of leaf checks per table keeps a
        ;; table with many references from taking forever, at the cost of rows it might have found
        budget (atom 5000)
        valid? (fn [r] (and (pos? (swap! budget dec)) (m/validate name r opts)))
        grow (when grow (fn [target ds pins] (when (pos? @budget) (grow target ds pins))))
        {self true others false} (group-by #(= table (:table %)) refs)
        settled (set (mapcat :columns others))
        cands (candidates registry gen-of name (max 200 (* 50 rows)) seed)
        fits? (fn [pool r] (and (valid? r) (= (inc (count pool)) (count (distinct-by-keys (conj pool r) key-sets)))))
        ;; a candidate that fails on its own is dropped before any reference is solved (or grown) for it
        [pool ds] (reduce (fn [[pool ds] c]
                            (cond (>= (count pool) (* 3 rows)) (reduced [pool ds])
                                  (not (valid? c)) [pool ds]
                                  :else (if-let [[r ds] (solve-refs c others ds #{} table #(fits? pool %) (when (< (count pool) rows) grow))]
                                          [(conj pool r) ds]
                                          [pool ds])))
                          [[] ds] cands)
        ;; settling self-references keeps the columns other references fixed; a dropped row may
        ;; be what another row points at, so repeat until the batch is stable
        settle (fn settle [rs]
                 (let [rs2 (-> (keep #(first (solve-refs % self (assoc ds table rs) settled table valid? nil)) rs) (distinct-by-keys key-sets) vec)]
                   (if (= (count rs2) (count rs)) rs2 (settle rs2))))
        rs (loop [rs (vec (take rows pool)) more (drop rows pool)]
             (let [rs (if self (settle rs) rs)
                   short (- rows (count rs))]
               (if (or (<= short 0) (empty? more))
                 rs
                 (recur (into rs (take short more)) (drop short more)))))]
    (cond-> (assoc ds table rs)
      (< (count rs) rows) (vary-meta assoc-in [:pgmalli/short table] {:wanted rows :got (count rs) :reasons (failure-reasons registry t (vec cands) ds)}))))

(defn dataset-generator
  "test.check generator of datasets that satisfy dataset-schema: tables are generated in
   foreign-key order and referencing columns are pointed at rows generated before them (a
   self-reference at the same table); a reference that finds no fitting row grows its target
   table by one row that fits, whose own references may grow their targets in turn. :rows is
   the number of rows wanted per table, out of many more candidates; a table that comes out
   short (a CHECK random rows cannot satisfy, a parent that came out empty) is recorded in
   the dataset's metadata under :pgmalli/short with the reasons, and its children come out
   short too. :except names tables (\"schema.table\") to leave out; a kept table may not
   reference one of them."
  ([registry] (dataset-generator registry {}))
  ([registry {:keys [rows except] :or {rows 5}}]
   (let [ts (tables registry except)
         by-table (into {} (map (juxt :table identity) ts))
         fmap (requiring-resolve 'clojure.test.check.generators/fmap)
         choose (requiring-resolve 'clojure.test.check.generators/choose)
         opts {:registry registry}
         gen-of (memoize (fn [schema] (mg/generator schema opts)))
         ;; datasets with one more row of a parent table each, carrying the pinned columns and
         ;; solved against the dataset so far, growing their own parents up to depth levels deep
         grow (fn grow [depth]
                (when (pos? depth)
                  (fn [target ds pins]
                    (when-let [{:keys [name refs key-sets]} (by-table target)]
                      (let [{self true others false} (group-by #(= target (:table %)) refs)
                            valid? #(m/validate name % opts)
                            rs (get ds target)
                            settle (fn [[r ds]] (some-> (if self (first (solve-refs r self ds (set (mapcat :columns others)) target valid? nil)) r) (vector ds)))
                            fits? (fn [[r ds]] (when (= (inc (count rs)) (count (distinct-by-keys (conj rs r) key-sets))) [r ds]))]
                        (->> (candidates registry gen-of name 20 (hash [target (count rs) pins]))
                             (map #(merge % pins))
                             (keep #(solve-refs % others ds (set (keys pins)) target valid? (grow (dec depth))))
                             (keep settle)
                             (keep fits?)
                             (map (fn [[r ds]] (assoc ds target (conj (get ds target) r))))))))))]
     (fmap (fn [seed]
             (reduce (fn [ds [i table]] (generate-table registry gen-of (by-table table) ds rows (+ seed i) (grow 4)))
                     {}
                     (map-indexed vector (topological ts))))
           ;; a seed independent of test.check's size, so early samples differ too
           (choose 0 Long/MAX_VALUE)))))

;;; datasets into the database

(defn- sql-type
  "The type an INSERT casts to: the referenced type's qualified name (:pg.ins/mood is ins.mood;
   :pg/type carries the name without its schema), else :pg/type."
  [s]
  (if (and (vector? s) (= :ref (first s)))
    (let [k (last s) [ns n] (if (keyword? k) [(namespace k) (name k)] (str/split k #"/" 2))]
      (str (subs ns 3) "." n))
    (:pg/type (column-props s))))

(defn- insert-value
  "A dataset value in the form an INSERT needs: an enum cast to its type (a string parameter
   stays text), json written and cast, an array with its element type; anything else as it is."
  [registry schema v]
  (let [s (non-null schema)
        t (:pg/type (column-props s))
        base (if (and (vector? s) (= :ref (first s))) (get registry (last s)) s)
        kind (when (vector? base) (first base))]
    (cond (nil? v) nil
          (= :enum kind) [:cast v (keyword (sql-type s))]
          (#{"json" "jsonb"} t) [:cast (json/write v) (keyword t)]
          (= :vector kind) (let [elem (non-null (last base))
                                 elem-type (cond (and (vector? elem) (= :ref (first elem))) (sql-type elem)
                                                 (and t (str/ends-with? t "[]")) (subs t 0 (- (count t) 2)))]
                             (if elem-type [:array (vec v) (keyword elem-type)] [:array (vec v)]))
          :else v)))

(defn- parents-first
  "Tables ordered so every table comes after the tables it references, among the given ones
   (a parent not given is already in the database)."
  [ts]
  (loop [out [] left ts]
    (if (empty? left)
      out
      (let [given (set (map :table ts))
            placed (set (map :table out))
            ready (filter (fn [t] (every? #(or (= (:table %) (:table t)) (not (given (:table %))) (placed (:table %))) (:refs t))) left)]
        (when (empty? ready)
          (throw (ex-info "tables reference each other in a cycle" {:tables (mapv :table left)})))
        (recur (into out ready) (remove (set ready) left))))))

(defn- rows-parents-first
  "Rows of one table ordered so a row comes after the rows it references through refs (the
   table's references to itself); a NULL reference, or one to the row itself, waits for nothing."
  [rows refs]
  (if (empty? refs)
    rows
    (loop [out [] left rows]
      (if (empty? left)
        out
        (let [targets (set (for [r refs, row out] [(:to r) (key-of row (:to r))]))
              ready (filter (fn [row] (every? (fn [{:keys [columns to]}]
                                                (let [k (key-of row columns)]
                                                  (or (some nil? k) (= k (key-of row to)) (targets [to k]))))
                                              refs))
                            left)]
          (if (empty? ready)
            (into out left)
            (recur (into out ready) (remove (set ready) left))))))))

(defn inserts
  "[{:insert-into ... :values [row ...]} ...] for a dataset: its tables, parents before the
   tables referencing them and, within a table, rows before the rows referencing them (rows
   referencing each other in a cycle are fine in one INSERT: the database checks foreign keys
   at the end of the statement); enum, json and array values in the form the driver needs.
   Generated columns are left out; a table with an identity column gets OVERRIDING SYSTEM
   VALUE, so the ids the rows carry (and the references to them) hold; a column a row lacks is
   DEFAULT. A table the registry does not have, or a column the table does not have, is an
   error. Option :on-conflict :nothing adds ON CONFLICT DO NOTHING."
  [registry dataset {:keys [on-conflict]}]
  (let [ts (filter #(seq (get dataset (:table %))) (tables registry))]
    (when-let [unknown (seq (remove (set (map :table (tables registry))) (keys dataset)))]
      (throw (ex-info (str "dataset holds tables the registry does not: " (pr-str unknown)) {:tables unknown})))
    (for [{:keys [name table refs]} (parents-first ts)
          :let [entries (column-entries (get registry name))
                columns (into {} (map (fn [[k _ s]] [k s])) entries)
                generated (set (for [[k _ s] entries :when (:pg/generated (column-props s))] k))
                identity? (some (fn [[_ _ s]] (= :always (:pg/identity (column-props s)))) entries)
                self (filter #(= table (:table %)) refs)
                rows (rows-parents-first (get dataset table) self)
                unknown (into {} (for [[i row] (map-indexed vector rows)
                                       :let [u (remove #(contains? columns %) (keys row))] :when (seq u)]
                                   [i (vec u)]))
                _ (when (seq unknown)
                    (throw (ex-info (str table " rows carry columns the table does not have: " (pr-str (distinct (mapcat val unknown))))
                                    {:table table :rows unknown})))
                used (remove generated (distinct (mapcat keys rows)))]]
      (cond-> {:insert-into (if identity? [{:overriding-value :system} (keyword table)] (keyword table))
               :values (mapv (fn [row] (into {} (for [k used] [k (if (contains? row k) (insert-value registry (get columns k) (get row k)) [:default])]))) rows)}
        (= :nothing on-conflict) (assoc :on-conflict [] :do-nothing [])))))
