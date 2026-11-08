(ns pgmalli-next.impl.describe
  "Schemas as short data people and tools read: PostgreSQL type names, ? for NULL, an enum's
   labels, maps as {key description}."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(defn- type-label
  "The PostgreSQL type of props, short: \"varchar(40)\", \"timestamptz\"; nil without one."
  [props]
  (when-let [t (:pg/type props)]
    (syntax/short-type t (some->> (:pg/max-length props) (format "(%d)")))))

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

(defn- classes-label
  "The types the one sending sends the Java arrays :pg/classes of props names as, short."
  [props]
  (when-let [cs (seq (:pg/classes props))]
    (str/join " | " (distinct (for [n (sort cs), [c t] accept/classes :when (= n (.getName ^Class c))] (types/short-name t))))))

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
                              ;; (a | b)[] is one type already
                              (and (str/includes? d " | ") (not (re-matches #"\(.*\)\[\]" d))) (str "(" d ")?")
                              :else (str d "?")))
      (= :map head) (ordered (for [[k ep v] (map shape/entry-parts children)]
                               [k (cond-> (describe schemas v) (and (:optional ep) (string? (describe schemas v))) (str " (optional)"))]))
      (#{:sequential :vector :set :pg/array} head)
      (or (type-label p) (classes-label p)
          (let [d (describe schemas (first children))]
            ;; a Java array of elements of a type, as PostgreSQL names an array of it
            (if (and (= :pg/array head) (string? d)) (str (if (re-find #"[ ?]" d) (str "(" d ")") d) "[]") [d])))
      ;; 1.5 and 1.5M, 1.0 and a Float 1.0, a label as each class sends it, print alike
      (= :enum head) (str (or (type-label p) "enum") ": " (str/join " | " (distinct (map str children))))
      (= :=> head) (cond-> {:args (mapv #(describe schemas %) (shape/entries (first children))) :returns (describe schemas (second children))}
                     (seq (:pgmalli/problems p)) (assoc :pgmalli/problems (:pgmalli/problems p)))
      (= :function head) (mapv #(describe schemas %) children)
      ;; written-row's [:lift v], which sends v as one parameter
      (and (= :tuple head) (= [:= :lift] (first children))) (let [d (describe schemas (second children))] (str "[:lift " (if (string? d) d (pr-str d)) "]"))
      (= :cat head) (mapv #(describe schemas %) children)
      ;; an argument of several collections (IN's)
      (and (= :or head) (every? #(and (vector? %) (#{:pg/array :sequential :set :vector} (first %))) children)
           (not-every? (comp :pg/classes props-of) children)
           (apply = (map #(describe schemas (last %)) children)))
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
      (= :and head) (if (:pgmalli/uses p)
                      (str/join " & " (map #(let [d (describe schemas %)] (if (string? d) (str "(" d ")") (pr-str d))) children))
                      (or (type-label p) (describe schemas (first children))))
      :else (or (type-label p) (leaf s)))))
