(ns pgmalli-next.impl.portable
  "The same schemas in another shape: as data malli's default registry reads, with pgmalli's
   types as malli's and references inlined. The row shapes next.jdbc returns are
   pgmalli-next.impl.jdbc."
  (:require [clojure.walk :as walk]
            [pgmalli-next.impl.pgtypes :as pgtypes]
            [pgmalli-next.impl.shape :as shape]))

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
      ;; without numeric(p,s)'s precision, which malli's types cannot say (decimal? in its
      ;; place would refuse an integer a base also takes)
      (let [parts (distinct (map #(portable-node schemas %) (remove #(and (vector? %) (= :pg/numeric (first %))) (shape/entries f))))]
        (if (= 1 (count parts)) (shape/merge-props (first parts) p) (into (if p [:and p] [:and]) parts)))
      :else f)))

(defn portable-data
  "Schema data from the registry as data malli's default registry reads (with
   malli.experimental.time for the time types): references to the schema's own types inlined,
   :pg/smallint and :pg/integer as bounded :int, :pg/bytes as bytes?, generation hints dropped.
   numeric(p,s)'s precision (:pg/numeric) is left out."
  [registry schema]
  (let [schemas (shape/schemas-of registry)]
    (shape/without-gen (walk/prewalk #(portable-node schemas %) (shape/without-gen schema)))))
