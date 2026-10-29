(ns pgmalli.impl.registry
  "Generated files as malli registries: the file read from the classpath, the generation hints
   and the insert and update schemas derived from the row schemas, and the registry they make.

   \"registry\" is two things here: the file's :registry key is a plain {name schema} map of
   schema data, and a malli registry is what registry builds from it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.experimental.time :as time]
            malli.experimental.time.generator
            [malli.util :as mu]
            [pgmalli.impl.eval :as ev]
            [pgmalli.impl.error :as error]
            [pgmalli.impl.pgtypes :as pgtypes]
            [pgmalli.impl.shape :as shape]
            [pgmalli.impl.types :as types]))

(defn read-generated
  "The generated data for a schema name, from the classpath (pgmalli/<schema>.edn)."
  [schema]
  (let [path (str "pgmalli/" schema ".edn")]
    (edn/read-string (slurp (or (io/resource path)
                                (error/fail! :pgmalli.error/not-generated
                                             (str path " is not on the classpath; run pgmalli.main generate, and put its :out-dir's parent on :paths")
                                             {:given schema}))))))

(defn- insert-entry
  "A column of an INSERT: optional when the database fills it, unless with a literal default
   the column itself refuses."
  [registry e]
  (let [[k p s] (shape/entry-parts e)
        props (shape/column-props s)
        optional? (if (contains? props :default)
                    (m/validate s (:default props) {:registry registry})
                    (or (:pg/identity props) (contains? props :pg/default) (shape/maybe? s)))]
    (when-not (shape/written-by-database? props)
      [k (cond-> p optional? (assoc :optional true)) s])))

(defn- omitted-values
  "What the database stores for a column left out of an INSERT: {column literal} for literal
   defaults; columns whose default is an expression, identities and generated columns are in
   :unknown."
  [entries]
  (reduce (fn [acc e]
            (let [[k _ s] (shape/entry-parts e) props (shape/column-props s)]
              (cond (contains? props :default) (assoc-in acc [:literal k] (:default props))
                    (or (contains? props :pg/default) (:pg/identity props) (:pg/generated props)) (update acc :unknown conj k)
                    :else acc)))
          {:literal {} :unknown #{}}
          entries))

(defn- as-insert-sees-it
  "A table constraint with omitted columns standing for what the database stores in them:
   fragment keys are optional unless NULL (or the literal default) would violate them, a :multi
   on a column with a literal default takes that value's branch when the column is absent, and
   a :pg/check evaluates the literal defaults."
  [schema {:keys [literal unknown]} registry]
  (letfn [(entry [e] (let [[k p s] (shape/entry-parts e)]
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
                ;; nil dispatches both a NULL and an absent column, which takes the default's branch
                :multi (let [f (mapv go f)
                             k (:dispatch (second f))
                             absent (when (contains? literal k) (some (fn [[v s]] (when (= v (get literal k)) s)) (drop 2 f)))]
                         (cond (not absent) f
                               (some #(nil? (first %)) (drop 2 f))
                               (mapv #(if (and (vector? %) (nil? (first %)))
                                        [nil [:or [:and [:map [k {:optional true} [:not :any]]] absent] [:and [:map [k :nil]] (second %)]]]
                                        %)
                                     f)
                               :else (conj f [nil absent])))
                (mapv go f))))]
    (go schema)))

(defn- insert-schema
  "What an INSERT may carry: identity ALWAYS and generated columns removed, columns with a
   default or NULL optional, closed map; then the table constraints, see as-insert-sees-it."
  [row registry]
  (let [[_ props & entries] (shape/row-map row)
        m (into [:map (assoc props :closed true)] (keep #(insert-entry registry %) entries))
        omitted (omitted-values entries)]
    (if (= :and (first row))
      (into [:and m] (map #(as-insert-sees-it % omitted registry) (drop 2 row)))
      m)))

(defn- update-schema
  "What an UPDATE may set: the columns an INSERT may carry, every one optional (an update sends
   the columns it changes), each holding what the column holds (a NOT NULL column cannot be set
   to NULL), closed map. The table constraints are left out: they hold on the updated row,
   which the columns sent do not show."
  [row]
  (let [[_ props & entries] (shape/row-map row)]
    (into [:map (assoc (select-keys props [:pg/table]) :closed true)]
          (for [e entries :let [[k p s] (shape/entry-parts e) cp (shape/column-props s)]
                :when (not (shape/written-by-database? cp))]
            [k (assoc p :optional true) s]))))

(defn- with-derived [registry]
  (into registry (for [[k s] registry :when (shape/row-schema? s)
                       e [[(shape/derived-name k "insert") (insert-schema s registry)] [(shape/derived-name k "update") (update-schema s)]]]
                   e)))

(def ^:private json-value
  "What a json or jsonb column with no CHECK to shape it generates: small JSON values."
  [:or :string :int :boolean [:map-of {:max 3} :string [:or :string :int]] [:vector {:max 3} [:or :string :int]]])

(defn- in-values
  "The numbers of a [:pg/check-value [:in :VALUE [...]]], nil for another part."
  [part]
  (when (and (vector? part) (= :pg/check-value (first part)))
    (let [[op v vs] (last part)]
      (when (and (= :in op) (= :VALUE v) (every? number? vs)) vs))))

(defn- cannot-make
  "A generator of no values (malli leaves out an optional key of it, and a [:maybe] generates
   NULL), which throws, naming where, when a value is wanted."
  [where type-name]
  (let [sized (requiring-resolve 'clojure.test.check.generators/sized)]
    (with-meta (sized (fn [_] (error/fail! :pgmalli.error/cannot-generate
                                           (str where ": pgmalli cannot make a value of type " type-name "; give one with :overrides in pgmalli.edn")
                                           {:given where :type type-name})))
      {:malli.generator/never-gen true})))

(defn- gen-hints
  "Generation hints (:gen/min, :gen/max, :gen/schema, :gen/elements, :gen/fmap) for a column
   schema: key and identity integers are small and positive, strings short, times within 2025
   (fixed, so a seed gives the same rows in every run), an unshaped json column a JSON value,
   an opaque type one of a few literals, a bit string digits. A NOT NULL column of a type
   pgmalli cannot make a value of generates nothing (where names it in the error). Other
   columns keep the schema's own bounds."
  [s key? where]
  (let [[t p] (if (and (vector? s) (map? (second s))) [(first s) (second s)] [(if (vector? s) (first s) s) {}])
        ;; a numeric bounded by CHECKs ([:and decimal? [:> 1] [:< 1000]]) generates within them: the
        ;; bounds go to the head as :gen/min and :gen/max, on a :pg/numeric when the head is bare
        numeric-bounds (when (= :and t)
                         (let [parts (shape/entries s)
                               head (first parts)
                               lo (some (fn [[op v]] (when (and (#{:> :>=} op) (number? v)) v)) (rest parts))
                               hi (some (fn [[op v]] (when (and (#{:< :<=} op) (number? v)) v)) (rest parts))]
                           (when (and (or lo hi) (or (= 'decimal? head) (and (vector? head) (= :pg/numeric (first head)))))
                             [head (cond-> {} lo (assoc :gen/min (bigdec lo)) hi (assoc :gen/max (bigdec hi)))])))
        hints (case t
                (:int :pg/integer :pg/smallint) (when key?
                       (let [lo (max 1 (:min p Long/MIN_VALUE)) hi (min 100000 (:max p Long/MAX_VALUE))]
                         (when (<= lo hi) {:gen/min lo :gen/max hi})))
                :string (if (= :bits (:gen (types/entry (shape/type-name p))))
                          {:gen/schema [:vector {:min (:min p 1) :max (:max p (:min p 8))} [:enum "0" "1"]] :gen/fmap #(apply str %)}
                          (when (or (nil? (:max p)) (> (:max p) 24)) {:gen/max (max (:min p 0) 24)}))
                :time/instant {:gen/min (java.time.Instant/parse "2025-01-01T00:00:00Z") :gen/max (java.time.Instant/parse "2025-12-31T23:59:59Z")}
                :time/local-date-time {:gen/min (java.time.LocalDateTime/parse "2025-01-01T00:00:00") :gen/max (java.time.LocalDateTime/parse "2025-12-31T23:59:59")}
                :time/local-date {:gen/min (java.time.LocalDate/parse "2025-01-01") :gen/max (java.time.LocalDate/parse "2025-12-31")}
                ;; PostgreSQL takes offsets up to 15:59:59, java.time up to 18:00
                :time/offset-time {:gen/schema [:tuple :time/local-time [:enum -5 0 9]]
                                   :gen/fmap (fn [[t h]] (java.time.OffsetTime/of t (java.time.ZoneOffset/ofHours h)))}
                :sequential (when (types/json? (:pg/type p)) {:gen/schema [:vector {:max 3} json-value]})
                ;; an array of a type pgmalli cannot write a value of: empty, which every array type takes
                :vector (when (= :any (last s)) {:gen/elements [[]]})
                (:any :some) (cond (types/json? (:pg/type p)) {:gen/schema json-value}
                                   (:literals (types/entry (shape/type-name p))) {:gen/elements (:literals (types/entry (shape/type-name p)))}
                                   ;; NULL where the column takes it; a NOT NULL one has no value to
                                   ;; generate, which leaves a dataset's table short with the reason
                                   (= :any t) (when (:pg/type p) {:gen/elements [nil]})
                                   (:pg/type p) {:gen/gen (cannot-make where (:pg/type p))})
                nil)]
    (cond
      numeric-bounds
      (let [[head bounds] numeric-bounds
            head* (if (= 'decimal? head)
                    ;; at the column's scale: PostgreSQL rounds a finer value, which may then fail the bound
                    ['decimal? {:gen/schema [:pg/numeric (merge {:precision 18 :scale 4} (some #(when (and (vector? %) (= :pg/numeric (first %))) (second %)) (shape/entries s)) bounds)]}]
                    (assoc head 1 (merge (second head) bounds)))
            i (if (map? (second s)) 2 1)]
        (assoc s i head*))
      (and hints (not-any? #(contains? p %) [:gen/min :gen/max :gen/gen :gen/schema :gen/elements]))
      (cond (not (vector? s)) [t hints] (map? (second s)) (assoc s 1 (merge p hints)) :else (into [t hints] (rest s)))
      ;; a numeric IN (see render) generates one of its values
      (and (= :and t) (some in-values (shape/entries s)))
      (shape/merge-props s {:gen/elements (mapv bigdec (some in-values (shape/entries s)))})
      ;; an :and generates from its type, an array from its element's
      (and (= :and t) (seq (shape/entries s))) (let [i (if (map? (second s)) 2 1)] (assoc s i (gen-hints (nth s i) key? where)))
      (and (= :vector t) (vector? s)) (assoc s (dec (count s)) (gen-hints (last s) false where))
      :else s)))

(defn- with-column-types
  "The row with its columns' types on its :pg/checks, as :pg/types: integer arithmetic
   overflows and char(n) compares as in PostgreSQL there."
  [row]
  (let [[_ _ & entries] (shape/row-map row)
        types (into {} (for [e entries :let [[k _ s] (shape/entry-parts e) t (ev/column-type (shape/column-props s))] :when t] [k t]))
        typed (fn [f] (if (and (vector? f) (= :pg/check (first f)))
                        (let [[_ p e] (if (map? (second f)) f [:pg/check {} (second f)])] [:pg/check (assoc p :pg/types types) e])
                        f))]
    (if (and (= :and (first row)) (seq types)) (into [:and (second row)] (map #(walk/postwalk typed %)) (drop 2 row)) row)))

(defn- with-gen-hints
  "The row with generation hints on its columns, and on the columns of the :multi and :or
   fragments its CHECKs became, which generate those columns' values in their branches."
  [row]
  (let [[_ props & entries] (shape/row-map row)
        key-cols (set (map shape/ident-key (concat (:pg/primary-key props) (mapcat :columns (:pg/unique props)) (mapcat :columns (:pg/foreign-keys props)))))
        hinted (fn [e] (let [[k p s] (shape/entry-parts e)
                             key? (or (key-cols k) (:pg/identity (shape/column-props s)))
                             where (str (:pg/table props) "." (name k))
                             s (if (shape/maybe? s) [:maybe (gen-hints (last s) key? where)] (gen-hints s key? where))]
                         (if (empty? p) [k s] [k p s])))
        ;; a column a :multi pins to its branches' values (LIST partitions) generates one of them
        pinned (into {} (for [c (when (= :and (first row)) (drop 2 row))
                              :when (and (vector? c) (= :multi (first c)))
                              :let [k (:dispatch (second c))
                                    branches (drop 2 c)
                                    vs (remove #{nil :malli.core/default} (map first branches))]
                              :when (and (seq vs) (some #(= [:malli.core/default [:map [k :nil]]] %) branches))]
                          [k (vec vs)]))
        pin (fn [[k & more :as e]] (if-let [vs (pinned k)]
                                     (let [s (last more)] (conj (pop e) (if (shape/maybe? s) [:maybe (shape/merge-props (last s) {:gen/elements vs})] (shape/merge-props s {:gen/elements vs}))))
                                     e))
        m (into [:map props] (map (comp pin hinted)) entries)
        fragment (fn [f] (if (and (vector? f) (= :map (first f)))
                           (let [[head entries] (if (map? (second f)) [(subvec f 0 2) (drop 2 f)] [[:map] (rest f)])]
                             (into head (map hinted) entries))
                           f))]
    (if (= :and (first row)) (into [:and m] (map #(walk/postwalk fragment %)) (drop 2 row)) m)))

(def regex-generation?
  "Whether strings can be generated from a regex here: test.chuck's generator, which runs on
   the JVM (babashka falls back to drawing strings and filtering them)."
  (delay (boolean (try (requiring-resolve 'com.gfredericks.test.chuck.generators/string-from-regex) (catch Exception _ nil)))))

(defn- generatable-regex
  "A [:re ...] test.chuck generates from whose strings match the one given: that one, or the
   same without a leading (?i) (which test.chuck refuses); nil when there is none, and the
   strings are drawn and filtered."
  [part]
  (let [string-from-regex (requiring-resolve 'com.gfredericks.test.chuck.generators/string-from-regex)]
    (loop [re (str (last part))]
      (cond
        ;; malli drops the anchors before it calls test.chuck
        (try (string-from-regex (re-pattern (str/replace re #"^\^?(.*?)(\$?)$" "$1"))) true (catch Exception _ false))
        (if (= re (str (last part))) part [:re re])
        (str/starts-with? re "(?i)") (recur (subs re 4))))))

(defn- generate-from-part
  "[:and ...] schemas with a part a generator should start from: a regex (test.chuck makes
   strings that match it, where filtering random strings would not) or a reference (an
   override, a domain), as :gen/schema on the :and."
  [f]
  (if (and (vector? f) (= :and (first f)) (not (and (map? (second f)) (some #{:gen/schema :gen/gen :gen/fmap :gen/elements} (keys (second f))))))
    (let [props (when (map? (second f)) (second f))
          parts (shape/entries f)
          regexes (when @regex-generation? (keep #(when (and (vector? %) (= :re (first %))) (generatable-regex %)) parts))
          ;; a regex without its (?i) generates only some of the strings: after a reference
          from (or (some (set parts) regexes)
                   (some #(when (and (vector? %) (= :ref (first %))) %) parts)
                   (first regexes))]
      ;; generated from that part first, the other parts filtering: malli's :and generator
      (if from (into [:and (assoc (or props {}) :gen/schema (into [:and from] (remove #{from} parts)))] parts) f))
    f))

(defn- dispatch-by-key
  "A :multi dispatching on a column whose key is a string: malli reads a string :dispatch as
   code, so it gets a function, the key kept as :pg/dispatch (shape/dispatch-key)."
  [f]
  (if (and (vector? f) (= :multi (first f)) (string? (:dispatch (second f))))
    (let [k (:dispatch (second f))] (assoc f 1 (assoc (second f) :dispatch #(get % k) :pg/dispatch k)))
    f))

(defn registry
  "The registry pgmalli.core/registry documents."
  [& schemas]
  (let [base (merge (m/default-schemas) (mu/schemas) (time/schemas) pgtypes/schemas)
        generated (apply merge (map #(:registry (if (map? %) % (read-generated %))) schemas))]
    (doseq [[k s] generated :when (shape/row-schema? s)
            :let [{:keys [pg/table pg/unique pg/foreign-keys]} (second (shape/row-map s))]
            :when (or (not (str/includes? table ".")) (some vector? unique) (some vector? foreign-keys))]
      (error/fail! :pgmalli.error/not-generated (str k " was generated by an older pgmalli; run pgmalli.main generate again") {:given k}))
    (let [generated (into {} (map (fn [[k s]] [k (walk/postwalk generate-from-part (if (shape/row-schema? s) (with-gen-hints (with-column-types s)) (gen-hints s false (str k))))])) generated)]
      (merge base (update-vals (apply dissoc (merge (with-derived (merge base generated)) generated) (keys base))
                               #(walk/postwalk dispatch-by-key %))))))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints."
  [registry name]
  (let [props (shape/row-props (shape/schema-of registry name))]
    (when-not (or (:pg/table props) (:pg/view props))
      (shape/not-a-row! registry name "columns")))
  (let [s (m/deref (m/schema name {:registry registry}))]
    (if (= :and (m/type s)) (first (m/children s)) s)))
