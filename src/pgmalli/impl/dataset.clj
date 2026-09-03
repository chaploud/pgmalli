(ns pgmalli.impl.dataset
  "Datasets: several tables of rows at once, with keys and references checked against the
   registry, and generated so that they hold."
  (:require [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [pgmalli.impl.registry :as reg]
            [pgmalli.impl.shape :as shape]))

(defn tables
  "[{:name :table :key-sets :refs} ...] for every row schema but those in except. A key set is
   {:columns :nulls-distinct? :label}; a reference {:columns :table :to :full? :label},
   references to tables outside the dataset left out."
  ([registry] (tables registry nil))
  ([registry except]
   (let [ts (for [[k s] (sort-by (comp str key) (shape/schemas-of registry))
                  :when (and (shape/row-schema? s) (not (contains? (set except) (:pg/table (second (shape/row-map s))))))
                 :let [{:keys [pg/table pg/primary-key pg/unique pg/foreign-keys]} (second (shape/row-map s))]]
             {:name k
              :table table
              :key-sets (concat (when primary-key
                                  [{:columns (mapv shape/ident-key primary-key) :nulls-distinct? true
                                    :label (str table " primary key " (pr-str primary-key))}])
                                (for [{:keys [columns nulls-distinct]} unique]
                                  {:columns (mapv shape/ident-key columns) :nulls-distinct? (not (false? nulls-distinct))
                                   :label (str table " unique " (pr-str columns))}))
              :refs (for [{:keys [columns to match] target :table} foreign-keys]
                      {:columns (mapv shape/ident-key columns) :table target :to (mapv shape/ident-key to) :full? (= :full match)
                       :label (str table " " (pr-str columns) " references " target " " (pr-str to))})})
         known (set (map :table ts))
         excepted (set except)]
     (doseq [t ts, r (:refs t) :when (excepted (:table r))]
       (throw (ex-info (str (:table t) " references " (:table r) ", which :except leaves out") {:table (:table t) :references (:table r)})))
     (mapv (fn [t] (update t :refs #(vec (filter (comp known :table) %)))) ts))))

(defn key-of [row columns] (mapv #(get row %) columns))

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
        (when (empty? ready) (throw (ex-info "tables reference each other in a cycle" {:tables (vec (sort left))})))
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
        schema (get (shape/schemas-of registry) name)
        columns (into {} (map (fn [[k _ s]] [k s])) (shape/column-entries schema))
        fragment? (fn [f] (and (vector? f) (= :map (first f))))
        ;; a fragment saying only "not NULL" generates from the column, not from :some
        source (fn [k s] (if (= :some (if (vector? s) (first s) s)) (shape/non-null (get columns k s)) s))
        ;; a branch's own dispatch value is kept: the fragment names the column too (col IS NOT NULL)
        fill (fn [row frag i keep]
               (reduce (fn [row [j e]] (let [[k _ s] (shape/entry-parts e)]
                                         (if (= k keep) row (assoc row k (generate (gen-of (source k s)) 30 (+ seed i j))))))
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
                               [v frag] (if hit [(get row dk) hit] (when (seq branches) (nth branches (mod (hash [seed i]) (count branches)))))
                               ;; a branch of several alternatives: one of them
                               frag (if (and (vector? frag) (= :or (first frag)))
                                      (let [alts (filterv fragment? (rest frag))] (when (seq alts) (nth alts (mod (hash [seed i 1]) (count alts)))))
                                      frag)]
                           (if (fragment? frag) (fill (assoc row dk v) frag (* 100 i) dk) row))
                  :or (let [alts (filterv fragment? (drop 2 part))]
                        (if (seq alts) (fill row (nth alts (mod (hash [seed i]) (count alts))) (* 100 i) nil) row))
                  row))
              row
              (map-indexed vector (drop 2 schema)))
      row)))

(defn- candidates
  "Up to n rows from a table's row generator (mem, memoized per table name), generated from seed
   at a size where keys rarely collide, their branching CHECKs filled in. Lazy, in chunks, so a
   table that fills from a few rows never generates the rest; no shrink tree is built, so
   large datasets stay cheap."
  [registry mem name n seed]
  (let [generate (requiring-resolve 'clojure.test.check.generators/generate)
        vector-of (requiring-resolve 'clojure.test.check.generators/vector)
        scale (requiring-resolve 'clojure.test.check.generators/scale)
        row-gen ((:row-gen mem) name)
        chunk 25]
    (->> (range 0 n chunk)
         (mapcat (fn [start] (generate (vector-of (scale #(max % 30) row-gen) (min chunk (- n start))) 30 (+ seed start))))
         (map-indexed (fn [i row] (fill-branches registry (:gen-of mem) name row (+ seed (* 1000 i))))))))

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
  [registry mem {:keys [name table refs key-sets] :as t} ds rows seed grow]
  (let [;; the search is exhaustive per row, so a budget of leaf checks per table keeps a table
        ;; with many references from taking forever, at the cost of rows it might have found
        budget (atom 5000)
        validate ((:validator mem) name)
        valid? (fn [r] (and (pos? (swap! budget dec)) (validate r)))
        grow (when grow (fn [target ds pins] (when (pos? @budget) (grow target ds pins))))
        {self true others false} (group-by #(= table (:table %)) refs)
        settled (set (mapcat :columns others))
        cands (candidates registry mem name (max 200 (* 50 rows)) seed)
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
         ;; one generator and one validator per table (a schema looked up by name is a fresh
         ;; object every time, so memoizing on the schema itself never hits)
         gen-of (memoize (fn [schema] (mg/generator schema opts)))
         mem {:gen-of gen-of
              :row-gen (memoize (fn [name] (gen-of (reg/columns registry name))))
              :validator (memoize (fn [name] (m/validator (m/schema name opts))))}
         ;; datasets with one more row of a parent table each, carrying the pinned columns and
         ;; solved against the dataset so far, growing their own parents up to depth levels deep
         grow (fn grow [depth]
                (when (pos? depth)
                  (fn [target ds pins]
                    (when-let [{:keys [name refs key-sets]} (by-table target)]
                      (let [{self true others false} (group-by #(= target (:table %)) refs)
                            valid? ((:validator mem) name)
                            rs (get ds target)
                            settle (fn [[r ds]] (some-> (if self (first (solve-refs r self ds (set (mapcat :columns others)) target valid? nil)) r) (vector ds)))
                            fits? (fn [[r ds]] (when (= (inc (count rs)) (count (distinct-by-keys (conj rs r) key-sets))) [r ds]))]
                        (->> (candidates registry mem name 20 (hash [target (count rs) pins]))
                             (map #(merge % pins))
                             (keep #(solve-refs % others ds (set (keys pins)) target valid? (grow (dec depth))))
                             (keep settle)
                             (keep fits?)
                             (map (fn [[r ds]] (assoc ds target (conj (get ds target) r))))))))))]
     (fmap (fn [seed]
             (reduce (fn [ds [i table]] (generate-table registry mem (by-table table) ds rows (+ seed i) (grow 4)))
                     {}
                     (map-indexed vector (topological ts))))
           ;; a seed independent of test.check's size, so early samples differ too
           (choose 0 Long/MAX_VALUE)))))
