(ns pgmalli.impl.runtime
  "The application side: generated files as malli registries, plus helpers that need malli.
   Generated files are read from the classpath as pgmalli/<schema>.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn- insert-name
  ":pg.<schema>/<table> -> :pg.<schema>.<table>/insert, string keys alike."
  [row-name]
  (if (keyword? row-name)
    (keyword (str (namespace row-name) "." (name row-name)) "insert")
    (str (str/replace-first row-name "/" ".") "/insert")))

(defn- row-schema?
  "Row schemas carry :pg/table; their inserts do too, but closed."
  [s]
  (let [props (when (vector? s) (second (row-map s)))]
    (and (map? props) (string? (:pg/table props)) (not (:closed props)))))

(defn- with-inserts [registry]
  (into registry (for [[k s] registry :when (row-schema? s)] [(insert-name k) (insert-schema s registry)])))

(defn- gen-hints
  "Generation hints (:gen/min, :gen/max) for a column schema: key and identity integers are
   small and positive, strings short, times within the last year. Other columns keep the
   schema's own bounds."
  [s key?]
  (let [[t p] (if (and (vector? s) (map? (second s))) [(first s) (second s)] [(if (vector? s) (first s) s) {}])
        now (java.time.Instant/now)
        hints (case t
                :int (when key?
                       (let [lo (max 1 (:min p Long/MIN_VALUE)) hi (min 100000 (:max p Long/MAX_VALUE))]
                         (when (<= lo hi) {:gen/min lo :gen/max hi})))
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
  (let [base (merge (m/default-schemas) (mu/schemas) (time/schemas)
                    {:pg/check check-schema :pg/check-value check-value-schema :pg/bytes bytes-schema})
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
   the search backtracks over targets until valid? holds. When no target fits, grow offers
   datasets with one more row in the target table that carries the columns already fixed,
   tried in turn; failing that, the reference's free columns become NULL. A reference to own,
   the row's own table, never picks the row itself."
  [row refs ds fixed own valid? grow]
  (letfn [(go [row ds fixed refs grow]
            (if (empty? refs)
              (when (valid? row) [row ds])
              (let [{:keys [columns to table full?]} (first refs)
                    v (key-of row columns)]
                (if (every? nil? v)
                  ;; an all-NULL reference stays so; under MATCH FULL no later reference may fill part of it
                  (go row ds (cond-> fixed full? (into columns)) (rest refs) grow)
                  (let [candidates (fn [ds] (cond->> (->> (get ds table) (map #(key-of % to)) (remove #(some nil? %)) distinct)
                                              (= table own) (remove #(= % (key-of row to)))
                                              true (filter (fn [t] (every? (fn [[k x]] (or (not (fixed k)) (= (get row k) x))) (map vector columns t))))))
                        attempt (fn [ds grow]
                                  (some #(go (merge row (zipmap columns %)) ds (into fixed columns) (rest refs) grow) (try-order v (candidates ds))))
                        ;; what a grown parent must carry: the target columns the row already fixed
                        pins (into {} (keep (fn [[c t]] (when (fixed c) [t (get row c)])) (map vector columns to)))]
                    (or (attempt ds grow)
                        ;; a row grows at most one parent per reference, so the search stays bounded
                        (when (and grow (not= table own)) (some #(attempt % nil) (grow table ds pins)))
                        (let [free (remove fixed columns)]
                          (when (if full? (= (count free) (count columns)) (seq free))
                            (go (merge row (zipmap free (repeat nil))) ds (into fixed columns) (rest refs) grow)))))))))]
    (go row ds fixed (sort-by (comp - count :columns) refs) grow)))

(defn- candidates
  "n rows from a table's row generator (row-gen, memoized per table), generated from seed at a
   size where keys rarely collide. No shrink tree is built, so large datasets stay cheap."
  [row-gen name n seed]
  (let [generate (requiring-resolve 'clojure.test.check.generators/generate)
        vector-of (requiring-resolve 'clojure.test.check.generators/vector)
        scale (requiring-resolve 'clojure.test.check.generators/scale)]
    (generate (vector-of (scale #(max % 30) (row-gen name)) n) 30 seed)))

(defn- failure-reasons
  "How the candidate rows of a table failed, most frequent first: what malli explains about the
   row, or the reference that found no target."
  [registry {:keys [name refs]} cands ds]
  (let [opts {:registry registry}
        reasons (for [c cands]
                  (or (some (fn [{:keys [label columns to table]}]
                              (let [v (key-of c columns)]
                                (when (and (not-any? nil? v) (not-any? #(= v (key-of % to)) (get ds table))) label)))
                            refs)
                      (some-> (m/explain name c opts) me/humanize pr-str)
                      "keys collide"))]
    (->> reasons frequencies (sort-by (comp - val)) (take 5))))

(defn- generate-table
  "The rows of one table given the tables before it: candidates, references solved (growing
   the parents when a reference finds nothing), keys made distinct, self-references settled,
   the batch topped up from the pool until it holds rows. Throws when nothing fits."
  [registry row-gen {:keys [name table refs key-sets] :as t} ds rows seed grow]
  (let [opts {:registry registry}
        valid? #(m/validate name % opts)
        {self true others false} (group-by #(= table (:table %)) refs)
        settled (set (mapcat :columns others))
        cands (candidates row-gen name (max 200 (* 50 rows)) seed)
        ;; a row is fit when it validates and its keys collide with none accepted before it, so a
        ;; colliding target makes the search move on (or grow the parent) instead of dropping the row
        fits? (fn [pool r] (and (valid? r) (= (inc (count pool)) (count (distinct-by-keys (conj pool r) key-sets)))))
        ;; a candidate that fails on its own is dropped before any reference is solved (or grown) for it
        [pool ds] (reduce (fn [[pool ds] c]
                            (cond (>= (count pool) (* 3 rows)) (reduced [pool ds])
                                  (not (valid? c)) [pool ds]
                                  :else (if-let [[r ds] (solve-refs c others ds #{} table #(fits? pool %) grow)] [(conj pool r) ds] [pool ds])))
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
    (when (and (pos? rows) (empty? rs))
      (throw (ex-info (str name ": none of " (count cands) " generated rows satisfied its constraints. Most often: "
                           (str/join "; " (map (fn [[why n]] (str why " (" n ")")) (failure-reasons registry t cands ds))))
                      {:table table :reasons (failure-reasons registry t cands ds)})))
    (assoc ds table rs)))

(defn dataset-generator
  "test.check generator of datasets that satisfy dataset-schema: tables are generated in
   foreign-key order and referencing columns are pointed at rows generated before them (a
   self-reference at the same table); a reference that finds no fitting row grows its target
   table by one row that fits, whose own references may grow their targets in turn. :rows is
   the number of rows wanted per table, out of many more candidates; a
   table none of them fits is an error naming the constraints that failed most, since a fixture
   with an empty table is never what was asked for. :except names tables (\"schema.table\") to
   leave out; a kept table may not reference one of them."
  ([registry] (dataset-generator registry {}))
  ([registry {:keys [rows except] :or {rows 5}}]
   (let [ts (tables registry except)
         by-table (into {} (map (juxt :table identity) ts))
         fmap (requiring-resolve 'clojure.test.check.generators/fmap)
         seeds (requiring-resolve 'clojure.test.check.generators/large-integer)
         opts {:registry registry}
         row-gen (memoize (fn [name] (mg/generator (columns registry name) opts)))
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
                        (->> (candidates row-gen name 20 (hash [target (count rs) pins]))
                             (map #(merge % pins))
                             (keep #(solve-refs % others ds (set (keys pins)) target valid? (grow (dec depth))))
                             (keep settle)
                             (keep fits?)
                             (map (fn [[r ds]] (assoc ds target (conj (get ds target) r))))))))))]
     (fmap (fn [seed]
             (reduce (fn [ds [i table]] (generate-table registry row-gen (by-table table) ds rows (+ seed i) (grow 4)))
                     {}
                     (map-indexed vector (topological ts))))
           @seeds))))
