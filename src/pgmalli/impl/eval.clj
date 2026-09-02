(ns pgmalli.impl.eval
  "Evaluates CHECK expression data against a row the way PostgreSQL does: nil is NULL,
   comparisons and functions yield nil when an operand is nil, and a CHECK passes unless its
   result is false. An expression PostgreSQL would fail on (division by zero, a cast that does
   not parse) fails the row, since the database would reject it too.

   Only the vocabulary that occurs in CHECK constraints is implemented; supported? tells
   whether an expression is inside it, and render leaves the others unrendered."
  (:require [clojure.string :as str]
            [pgmalli.impl.expr :as x]))

(def ^:private comparators {:= = :<> not= :< < :> > :<= <= :>= >=})

(defn- cmp [op a b] (when (and (some? a) (some? b)) (op (compare a b) 0)))
(defn- and3 [xs] (cond (some false? xs) false (some nil? xs) nil :else true))
(defn- or3 [xs] (cond (some true? xs) true (some nil? xs) nil :else false))
(defn- fn3 [f & xs] (when (every? some? xs) (apply f xs)))

(defn- in3
  "x IN (...): nil when nothing matched and a NULL was among the values."
  [v vs]
  (when (some? v)
    (cond (some #(cmp = v %) vs) true
          (some nil? vs) nil
          :else false)))

(defn- divide [a b] (if (and (integer? a) (integer? b)) (quot a b) (/ a b)))

(defn- json-type [v]
  (cond (nil? v) nil (map? v) "object" (sequential? v) "array" (string? v) "string"
        (number? v) "number" (boolean? v) "boolean" :else "null"))

(defn- octets [v] (if (string? v) (alength (.getBytes ^String v "UTF-8")) (count v)))

(defn- trim-chars [chars s]
  (let [cs (set chars)]
    (->> s (drop-while cs) reverse (drop-while cs) reverse (apply str))))

(def ^:private integer-types #{:smallint :integer :bigint :int2 :int4 :int8})
(def ^:private decimal-types #{:numeric :decimal :real :double-precision :float4 :float8})
(def ^:private text-types #{:text :varchar :character-varying :char :character :bpchar :name})
(def ^:private boolean-words {"t" true "true" true "y" true "yes" true "on" true "1" true
                         "f" false "false" false "n" false "no" false "off" false "0" false})

(defn- cast-to [v t]
  (cond
    (nil? v) nil
    (integer-types t) (cond (integer? v) v
                            (number? v) (long (.setScale (bigdec v) 0 java.math.RoundingMode/HALF_UP))
                            (boolean? v) (if v 1 0)
                            :else (Long/parseLong (str/trim v)))
    (decimal-types t) (if (number? v) v (bigdec (str/trim v)))
    (text-types t) (str v)
    (= :boolean t) (if (boolean? v)
                     v
                     (let [b (boolean-words (str/lower-case (str/trim v)))]
                       (if (nil? b) (throw (ex-info "not a boolean" {:value v})) b)))
    :else v))

(defn- posix-classes
  "[[:digit:]] and friends as the \\p{Digit} classes Java understands."
  [re]
  (str/replace re #"\[:(alpha|digit|alnum|upper|lower|space|punct|xdigit|cntrl|print|graph|blank):\]"
               (fn [[_ c]] (str "\\p{" (str/capitalize c) "}"))))

(defn- ev [e row]
  (cond
    (or (string? e) (number? e) (boolean? e) (nil? e)) e
    (keyword? e) (get row e)
    :else
    (let [[op & args] e
          a #(ev (first args) row)
          b #(ev (second args) row)]
      (case op
        :cast (cast-to (a) (let [t (second args)] (if (vector? t) (first t) t)))
        :and (and3 (map #(ev % row) args))
        :or (or3 (map #(ev % row) args))
        :not (let [v (a)] (when (some? v) (not v)))
        (:= :<> :< :> :<= :>=) (cmp (comparators op) (a) (b))
        :is (let [v (a)] (if (nil? (second args)) (nil? v) (= v (second args))))
        :is-not (let [v (a)] (if (nil? (second args)) (some? v) (not= v (second args))))
        :in (in3 (a) (map #(ev % row) (second args)))
        :not-in (let [r (in3 (a) (map #(ev % row) (second args)))] (when (some? r) (not r)))
        :between (let [[v lo hi] (map #(ev % row) args)] (and3 [(cmp >= v lo) (cmp <= v hi)]))
        :+ (fn3 + (a) (b))
        :- (fn3 - (a) (b))
        :* (fn3 * (a) (b))
        :/ (fn3 divide (a) (b))
        (:length :char_length) (fn3 count (a))
        :octet_length (fn3 octets (a))
        ;; TRIM(BOTH chars FROM x) parses as [:trim chars x]; btrim(x, chars) keeps the call order
        :trim (if (second args) (fn3 trim-chars (a) (b)) (fn3 str/trim (a)))
        :btrim (if (second args) (fn3 trim-chars (b) (a)) (fn3 str/trim (a)))
        :lower (fn3 str/lower-case (a))
        :upper (fn3 str/upper-case (a))
        :coalesce (first (filter some? (map #(ev % row) args)))
        :jsonb_typeof (json-type (a))
        :-> (fn3 get (a) (b))
        :->> (fn3 (fn [m k] (some-> (get m k) str)) (a) (b))
        :regex (fn3 (fn [s re] (some? (re-find (re-pattern (posix-classes re)) s))) (a) (b))
        :iregex (fn3 (fn [s re] (some? (re-find (re-pattern (str "(?i)" (posix-classes re))) s))) (a) (b))
        :case (let [[clauses else] (if (= :else (last (butlast args)))
                                     [(butlast (butlast args)) (last args)]
                                     [args nil])
                    ;; the first true condition selects the branch; its result may itself be NULL or false
                    branch (some (fn [[c r]] (when (true? (ev c row)) [r])) (partition 2 clauses))]
                (ev (if branch (first branch) else) row))
        (throw (ex-info (str "unsupported in CHECK: " op) {:unsupported op}))))))

(def ^:private supported-ops
  #{:cast :and :or :not := :<> :< :> :<= :>= :is :is-not :in :not-in :between :+ :- :* :/
    :length :char_length :octet_length :trim :btrim :lower :upper :coalesce :jsonb_typeof
    :-> :->> :regex :iregex :case})

(def ^:private builtin-types
  ;; cast-to has no conversion for these; a literal cast to any other type is an enum or domain value
  #{:date :time :timetz :timestamp :timestamptz :timestamp-without-time-zone :timestamp-with-time-zone
    :interval :uuid :json :jsonb :bytea :inet :cidr :macaddr :money :xml :tsvector :regclass})

(defn java-regex
  "The Java regex for a PostgreSQL pattern, or nil when it uses syntax Java reads differently."
  [re]
  (let [j (posix-classes re)]
    (when (and (not (re-find #"\\[mMyYZA]" re)) (try (re-pattern j) (catch Exception _ nil)))
      j)))

(defn- supported-node? [[op a t]]
  (case op
    :cast (let [t (if (vector? t) (first t) t)]
            (boolean (or (integer-types t) (decimal-types t) (text-types t) (= :boolean t)
                         (and (string? a) (not (builtin-types t)) (not (str/ends-with? (name t) "-array"))))))
    (:regex :iregex) (and (string? t) (some? (java-regex t)))
    (contains? supported-ops op)))

(defn supported?
  "Whether every operator, cast and regex in the expression is implemented."
  [e]
  (every? #(or (not (vector? %)) (supported-node? %))
          (tree-seq vector? (fn [v] (if (#{:in :not-in} (first v)) (cons (second v) (nth v 2)) (rest v))) (x/canonical e))))

(defn checker
  "Row predicate of a CHECK expression: true when the result is true or NULL, as in PostgreSQL."
  [e]
  (let [e (x/canonical e)]
    (fn [row] (not (false? (try (ev e row) (catch Exception _ false)))))))

(defn passes? [e row] ((checker e) row))
