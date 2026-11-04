(ns pgmalli-next.impl.describe
  "Schemas as short data people and tools read: PostgreSQL type names, ? for NULL, an enum's
   labels, maps as {key description}."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [pgmalli-next.impl.shape :as shape]))

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
  ;; a number class's predicate as the type PostgreSQL gets it as, not a name ending in ?, which reads as NULL
  (cond (keyword? s) (get {:double "float8"} s (name s))
        (symbol? s) (get '{decimal? "numeric" double? "float8" integer? "numeric" float? "real" inst? "inst"} s (str s))
        (and (vector? s) (= :fn (first s)) (:pg/class (props-of s))) (:pg/class (props-of s))
        (vector? s) (name (first s))
        :else (pr-str s)))

(def ^:private ^:dynamic *described* #{})

(defn- ordered [pairs] (apply array-map (mapcat identity pairs)))

(defn describe
  "A description of schema (data or a malli schema), resolving references through schemas."
  [schemas s]
  (let [s (if (m/schema? s) (m/form s) s)
        p (props-of s)
        head (when (vector? s) (first s))
        children (when (vector? s) (shape/entries s))]
    (cond
      ;; a name the registry defines as data; malli's own types (:int, :any) are their names
      ;; a recursive schema: a name already being described is the name
      (and (or (keyword? s) (string? s)) (vector? (get schemas s)))
      (if (*described* s) s (binding [*described* (conj *described* s)] (describe schemas (get schemas s))))
      (= :maybe head) (let [d (describe schemas (first children))]
                        (cond (not (string? d)) [:maybe d]
                              (str/includes? d " | ") (str "(" d ")?")
                              :else (str d "?")))
      (= :map head) (ordered (for [[k ep v] (map shape/entry-parts children)]
                               [k (cond-> (describe schemas v) (and (:optional ep) (string? (describe schemas v))) (str " (optional)"))]))
      ;; a table with CHECKs: its columns
      (and (= :and head) (vector? (first children)) (= :map (ffirst children))) (describe schemas (first children))
      (#{:sequential :vector :set :pg/array} head) (or (type-label p) [(describe schemas (first children))])
      ;; 1.5 and 1.5M, 1.0 and a Float 1.0, a label as each class sends it, print alike
      (= :enum head) (str (or (type-label p) "enum") ": " (str/join " | " (distinct (map str children))))
      (= :=> head) (cond-> {:args (mapv #(describe schemas %) (shape/entries (first children))) :returns (describe schemas (second children))}
                     (seq (:pgmalli/problems p)) (assoc :pgmalli/problems (:pgmalli/problems p)))
      (= :function head) (mapv #(describe schemas %) children)
      (= :cat head) (mapv #(describe schemas %) children)
      ;; an argument of several collections (IN's)
      (and (= :or head) (every? #(and (vector? %) (#{:pg/array :sequential :set :vector} (first %))) children))
      (let [elem (describe schemas (last (first children)))]
        (str elem "[] (" (str/join ", " (map #(name (first %)) children)) ")"))
      (= :or head) (str/join " | " (distinct (map #(let [d (describe schemas %)]
                                                     (cond (string? d) d
                                                           (and (vector? d) (string? (first d))) (str (first d) "[]")
                                                           :else (pr-str d)))
                                                  children)))
      (= :ref head) (let [target (get schemas (last s))]
                      (if (and (vector? target) (= :enum (first target)))
                        (str (or (type-label p) "enum") ": " (str/join " | " (shape/entries target)))
                        (or (type-label p) (describe schemas (last s)))))
      (= :and head) (let [n (some #(when (and (vector? %) (= :pg/numeric (first %))) (second %)) children)
                          label (or (type-label p) (describe schemas (first children)))]
                      ;; numeric(p,s) keeps its typmod in a [:pg/numeric ...] part, not in :pg/type
                      (if (and n (= "numeric" label)) (str "numeric(" (:precision n) "," (:scale n) ")") label))
      :else (or (type-label p) (leaf s)))))
