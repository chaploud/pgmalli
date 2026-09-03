(ns pgmalli.impl.portable
  "The same schemas in other shapes: as data malli's default registry reads (pgmalli's types as
   malli's, references inlined) and as a JDBC result set builder returns a row."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [pgmalli.impl.pgtypes :as pgtypes]
            [pgmalli.impl.shape :as shape]))

(defn- data-columns
  "The row map of a generated schema as data (columns gives the malli schema)."
  [registry name]
  (let [s (get (shape/schemas-of registry) name)]
    (when-not (vector? s) (throw (ex-info (str name " is not a generated schema") {:name name})))
    (shape/row-map s)))

(defn column
  "The schema of one column of a row or insert schema, as data (with its [:maybe ...])."
  [registry name col]
  (let [k (shape/ident-key (clojure.core/name col))]
    (some (fn [[ek _ s]] (when (= k ek) (shape/without-gen s))) (shape/column-entries (get (shape/schemas-of registry) name)))))

(defn- portable-node [schemas f]
  (let [[t p] (if (vector? f) [(first f) (when (map? (second f)) (second f))] [f nil])]
    (cond
      (pgtypes/int-ranges t) (let [[lo hi] (pgtypes/int-ranges t) p (or p {})]
                       [:int (assoc p :min (max lo (:min p lo)) :max (min hi (:max p hi)))])
      (and (vector? f) (= :pg/bytes t)) 'bytes?
      (and (vector? f) (= :pg/numeric t)) 'decimal?
      (and (vector? f) (= :ref t) (contains? schemas (last f)))
      ;; the inlined target is converted here: prewalk walks its children, not the node itself
      (portable-node schemas (shape/merge-props (get schemas (last f)) p))
      (and (vector? f) (= :and t))
      ;; without the CHECKs only pgmalli evaluates
      ;; the parts are converted here so two that become the same (decimal? and :pg/numeric) fold
      (let [parts (distinct (map #(portable-node schemas %) (remove #(and (vector? %) (#{:pg/check :pg/check-value} (first %))) (shape/entries f))))]
        (if (= 1 (count parts)) (shape/merge-props (first parts) p) (into (if p [:and p] [:and]) parts)))
      :else f)))

(defn portable-data
  "Schema data from the registry as data malli's default registry reads; see portable."
  [registry schema]
  (let [schemas (shape/schemas-of registry)]
    (shape/without-gen (walk/prewalk #(portable-node schemas %) (shape/without-gen schema)))))

(defn portable
  "The schema named in the registry as data malli's default registry reads (with
   malli.experimental.time for the time types): references to the schema's own types inlined,
   :pg/smallint and :pg/integer as bounded :int, :pg/bytes as bytes?, generation hints dropped.
   The CHECKs only pgmalli evaluates (:pg/check, :pg/check-value) are left out, so this is
   weaker than the registry's schema; use it where the registry cannot follow."
  [registry name]
  (portable-data registry (get (shape/schemas-of registry) name)))

(defn as-read
  "The [:map ...] of a row as a JDBC result builder returns it; the options are documented on
   pgmalli.core/as-read."
  [registry name {:keys [qualified? kebab? nil-columns time]}]
  (let [m (shape/without-gen (data-columns registry name))
        props (when (map? (second m)) (second m))
        kebab (fn [s] (cond-> s kebab? (str/replace "_" "-")))
        table (some-> (or (:pg/table props) (:pg/view props)) (str/split #"\." 2) second kebab)
        key* (fn [k] (let [s (kebab (clojure.core/name k))]
                       (cond (not qualified?) (if (keyword? k) (keyword s) s)
                             (keyword? k) (keyword table s)
                             :else (str table "/" s))))
        entry (fn [[k p s]] (let [s (shape/read-time time s)
                            absent? (and (= :absent nil-columns) (vector? s) (= :maybe (first s)))
                            p (cond-> p absent? (assoc :optional true))
                            s (if absent? (last s) s)]
                        (if (empty? p) [(key* k) s] [(key* k) p s])))]
    (into (if props [:map props] [:map]) (map entry (shape/column-entries m)))))
