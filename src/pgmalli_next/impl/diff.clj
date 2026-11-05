(ns pgmalli-next.impl.diff
  "The generated file against the database, and two generated files against each other: what
   differs, entry by entry."
  (:require [clojure.java.io :as io]
            [pgmalli-next.impl.files :as files]
            [pgmalli-next.impl.registry :as registry]
            [pgmalli-next.impl.shape :as shape]))

(defn- row-parts
  "{:columns {name entry} :props} of a row schema, nil for other entries."
  [s]
  (when (and (vector? s) (= :map (first s)) (map? (second s)))
    {:columns (into {} (map (fn [[k p c]] [k (if (seq p) [p c] c)])) (shape/column-entries s))
     :props (second s)}))

(defn- differences
  "The differences between the file's and the database's version of one registry entry, a
   row schema by column and property, anything else as a whole."
  [name file db]
  (let [f (row-parts file) d (row-parts db)
        props (fn [s] (when (and (vector? s) (map? (second s))) (second s)))
        props-only? (fn [a b] (and (props a) (props b) (= (assoc a 1 {}) (assoc b 1 {}))))
        by (fn [part label] (for [k (distinct (concat (keys (part f)) (keys (part d))))
                                  :let [a (get (part f) k) b (get (part d) k)]
                                  :when (not= a b)
                                  ;; a column whose properties alone differ: one line per property
                                  d (if (and (= :column label) (props-only? a b))
                                      (for [pk (distinct (concat (keys (props a)) (keys (props b))))
                                            :when (not= (get (props a) pk) (get (props b) pk))]
                                        {:name name :column k :property pk :file (get (props a) pk) :db (get (props b) pk)})
                                      [{:name name label k :file a :db b}])]
                              d))
        order (fn [s] (map first (shape/column-entries s)))]
    (cond (= file db) nil
          (and f d) (let [ds (concat (by :columns :column) (by :props :property))]
                      (if (seq ds) ds [{:name name :order true :file (order file) :db (order db)}]))
          :else [{:name name :file file :db db}])))

(defn diff
  "The differences between two generated data maps, as pgmalli-next.generate/diff documents them."
  [before after]
  (vec (concat (for [k (distinct (concat (keys (:registry before)) (keys (:registry after))))
                     d (differences k (get-in before [:registry k]) (get-in after [:registry k]))]
                 d)
               (for [k (distinct (concat (keys before) (keys after)))
                     :when (and (not (#{:registry :database-version} k)) (not= (get before k) (get after k)))
                     d (if (#{:functions :operators} k)
                         (for [s (sort (distinct (concat (keys (get before k)) (keys (get after k)))))
                               n (sort (distinct (concat (keys (get-in before [k s])) (keys (get-in after [k s])))))
                               :let [a (get-in before [k s n]) b (get-in after [k s n])]
                               :when (not= a b)]
                           {:key k :schema s :name n :file a :db b})
                         [{:key k :file (get before k) :db (get after k)}])]
                 d))))

(defn check
  "What pgmalli-next.generate/check compares: {:missing :stale :unrendered :diagnostics}."
  [config]
  (let [path (:out (files/config config))
        file (when (.exists (io/file path)) (registry/read-generated-file path))
        ;; read where there is no file too: the database unreachable is what CI reports
        db (:data (files/generated config))]
    {:missing (when-not file path)
     ;; a missing file differs in every entry; saying it is missing says it all
     :stale (when file (not-empty (diff file db)))
     :unrendered (not-empty (:unrendered db))
     :diagnostics (not-empty (:diagnostics db))}))
