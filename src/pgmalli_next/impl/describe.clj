(ns pgmalli-next.impl.describe
  "Schemas as short data people and tools read: PostgreSQL type names, ? for NULL, an enum's
   labels, maps as {key description}."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(defn- type-label
  "The PostgreSQL type of props, short: \"varchar(40)\", \"timestamptz\"; nil without one."
  [props]
  (when-let [t (:pg/type props)]
    (let [[_ base typmod brackets] (re-matches #"(.*?)(\(.*\))?((?:\[\])*)" t)
          n (:pg/max-length props)]
      (str (types/short-name base) (or typmod (when n (str "(" n ")"))) brackets))))

(declare describe)

(defn- props-of [s] (when (and (vector? s) (map? (second s))) (second s)))

(def ^:private class-names
  "The name of the schema of each class the one sending sends (accept/classes), by its kind: the
   type it is sent as, short (a Long's bigint, a byte[]'s bytea), or, one sent untyped, the type
   whose values it is (a LocalDate's date); not a predicate's name, whose ? reads as NULL."
  (reduce (fn [m [_ t s]]
            ;; a schema of a form ([:vector :any], a vector sent as jsonb) by the form, another by its kind
            (let [k (when s (if (vector? s) s (shape/kind s)))
                  n (if (string? t)
                      (types/short-name t)
                      (some (fn [[n e]] (when (= k (shape/kind (:schema e))) (types/short-name n))) types/types))]
              (cond-> m (and k n (not (contains? m k))) (assoc k n))))
          {} accept/classes))

(defn- leaf [s]
  (cond (keyword? s) (get class-names s (name s))
        ;; a predicate no class is sent by as a type (inst?) by its name, without the ?
        (symbol? s) (get class-names s (str/replace (str s) #"\?$" ""))
        (and (vector? s) (= :fn (first s)) (:pgmalli/describe (props-of s))) (:pgmalli/describe (props-of s))
        (and (vector? s) (= :fn (first s)) (:pg/class (props-of s))) (:pg/class (props-of s))
        (vector? s) (get class-names (first s) (name (first s)))
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
      ;; a class's schema of a form ([:map-of :some :any], sent as jsonb)
      (and (vector? s) (class-names s)) (class-names s)
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
      ;; written-row's [:lift v], which sends v as one parameter
      (and (= :tuple head) (= [:= :lift] (first children))) (let [d (describe schemas (second children))] (str "[:lift " (if (string? d) d (pr-str d)) "]"))
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
                          label (or (type-label p) (describe schemas (first children)))
                          uses? (:pgmalli/uses p)]
                      (cond
                        ;; numeric(p,s) keeps its typmod in a [:pg/numeric ...] part, not in :pg/type
                        (and n (= "numeric" label)) (str "numeric(" (:precision n) "," (:scale n) ")")
                        uses? (str/join " & " (map #(let [d (describe schemas %)] (if (string? d) (str "(" d ")") (pr-str d))) children))
                        :else label))
      :else (or (type-label p) (leaf s)))))
