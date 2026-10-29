(ns pgmalli.impl.describe
  "Schemas as short data people and tools read: PostgreSQL type names, ? for NULL, an enum's
   labels, maps as {key description}."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [pgmalli.impl.shape :as shape]))

(def ^:private short-names
  {"timestamp with time zone" "timestamptz" "timestamp without time zone" "timestamp"
   "time with time zone" "timetz" "time without time zone" "time"
   "character varying" "varchar" "character" "char" "double precision" "float8"})

(defn- type-label
  "The PostgreSQL type of props, short: \"varchar(40)\", \"timestamptz\"; nil without one."
  [props]
  (when-let [t (:pg/type props)]
    (let [[_ base typmod brackets] (re-matches #"(.*?)(\(.*\))?((?:\[\])*)" t)
          n (:pg/max-length props)]
      (str (get short-names base base) (or typmod (when n (str "(" n ")"))) brackets))))

(declare describe)

(defn- props-of [s] (when (and (vector? s) (map? (second s))) (second s)))

(defn- leaf [s]
  (cond (keyword? s) (name s)
        (symbol? s) (str s)
        (vector? s) (name (first s))
        :else (pr-str s)))

(defn- ordered [pairs] (apply array-map (mapcat identity pairs)))

(defn describe
  "A description of schema (data or a malli schema), resolving references through schemas."
  [schemas s]
  (let [s (if (m/schema? s) (m/form s) s)
        p (props-of s)
        head (when (vector? s) (first s))
        children (when (vector? s) (shape/entries s))]
    (cond
      (and (keyword? s) (contains? schemas s)) (describe schemas (get schemas s))
      (string? s) (if (contains? schemas s) (describe schemas (get schemas s)) s)
      (= :maybe head) (let [d (describe schemas (first children))] (if (string? d) (str d "?") [:maybe d]))
      (= :map head) (ordered (for [[k ep v] (map shape/entry-parts children)]
                               [k (cond-> (describe schemas v) (and (:optional ep) (string? (describe schemas v))) (str " (optional)"))]))
      ;; a table with CHECKs: its columns
      (and (= :and head) (vector? (first children)) (= :map (ffirst children))) (describe schemas (first children))
      (#{:sequential :vector :set} head) [(describe schemas (first children))]
      (= :enum head) (str (or (type-label p) "enum") ": " (str/join " | " children))
      (= :=> head) {:args (mapv #(describe schemas %) (shape/entries (first children))) :returns (describe schemas (second children))}
      (= :function head) (mapv #(describe schemas %) children)
      (= :cat head) (mapv #(describe schemas %) children)
      ;; what :write lets an array argument be
      (and (= :or head) (every? #(and (vector? %) (#{:pg/array :sequential :set :vector} (first %))) children))
      (let [elem (describe schemas (last (first children)))]
        (str elem "[] (" (str/join ", " (map #(name (first %)) children)) ")"))
      (= :or head) (str/join " | " (map #(let [d (describe schemas %)] (if (string? d) d (pr-str d))) children))
      (= :ref head) (let [target (get schemas (last s))]
                      (if (and (vector? target) (= :enum (first target)))
                        (str (or (type-label p) "enum") ": " (str/join " | " (shape/entries target)))
                        (or (type-label p) (describe schemas (last s)))))
      (= :and head) (or (type-label p) (describe schemas (first children)))
      :else (or (type-label p) (leaf s)))))
