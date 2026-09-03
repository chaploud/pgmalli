(ns pgmalli.impl.diff
  "Generated files against the database and against each other: what differs, entry by entry."
  (:require [clojure.java.io :as io]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.shape :as shape]))

(defn- row-parts
  "{:columns {name entry} :props :checks} of a row or insert schema, nil for other entries."
  [s]
  (let [m (shape/row-map s)]
    (when (and (vector? m) (= :map (first m)) (map? (second m)))
      {:columns (into {} (map (fn [[k p s]] [k (if (seq p) [p s] s)])) (shape/column-entries s))
       :props (second m)
       :checks (when (= :and (first s)) (vec (drop 2 s)))})))

(defn- differences
  "The differences between the file's and the database's version of one registry entry, a
   row or insert schema by column, property and CHECKs, anything else as a whole."
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
          (and f d) (let [ds (concat (by :columns :column) (by :props :property)
                                     (when (not= (:checks f) (:checks d)) [{:name name :checks true :file (:checks f) :db (:checks d)}]))]
                      (if (seq ds) ds [{:name name :order true :file (order file) :db (order db)}]))
          :else [{:name name :file file :db db}])))

(defn diff
  "The differences between two generated data maps, as pgmalli.generate/diff documents them."
  [before after]
  (vec (concat (for [k (distinct (concat (keys (:registry before)) (keys (:registry after))))
                     d (differences k (get-in before [:registry k]) (get-in after [:registry k]))]
                 d)
               (for [k (distinct (concat (keys before) (keys after)))
                     :when (and (not (#{:registry :database-version :schema} k)) (not= (get before k) (get after k)))]
                 {:key k :file (get before k) :db (get after k)}))))

(defn- files [config]
  (into {} (for [schema (:schemas (files/config config))
                 :let [p (files/path-for config schema)]
                 :when (.exists (io/file p))]
             [schema (files/read-edn p)])))

(defn- stale* [config files]
  (let [diffs (for [[schema {:keys [data]}] (files/generated-all config)
                    :let [ds (diff (get files schema) data)]
                    :when (seq ds)]
                [schema ds])]
    (when (seq diffs) (into {} diffs))))

(defn stale
  "The differences pgmalli.generate/stale documents."
  [config]
  (stale* config (files config)))

(defn check
  "What pgmalli.generate/check reports."
  ([config] (check config {}))
  ([config {:keys [db?] :or {db? true}}]
   (let [files (files config)]
     {:stale (when db? (stale* config files))
      :unrendered (into {} (for [[s f] files :when (seq (:unrendered f))] [s (:unrendered f)]))
      :diagnostics (into {} (for [[s f] files :when (seq (:diagnostics f))] [s (:diagnostics f)]))})))
