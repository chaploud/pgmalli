(ns pgmalli.impl.eval
  "Evaluates CHECK expression data against a row the way PostgreSQL does: nil is NULL,
   comparisons and functions yield nil when an operand is nil, and a CHECK passes unless its
   result is false. An expression PostgreSQL would fail on (division by zero, a cast that does
   not parse) fails the row, since the database would reject it too. Rows hold the registry's
   types (java.time, UUID, jsonb as maps and vectors).

   Only the vocabulary that occurs in CHECK constraints is implemented; supported? tells
   whether an expression is inside it, and render leaves the others unrendered."
  (:require [clojure.string :as str]
            [pgmalli.impl.expr :as x]
            [pgmalli.impl.json :as json])
  (:import (java.time Duration Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneOffset)
           (java.util UUID)))

;;; three-valued logic and comparison

(defn- and3 [xs] (cond (some false? xs) false (some nil? xs) nil :else true))
(defn- or3 [xs] (cond (some true? xs) true (some nil? xs) nil :else false))
(defn- not3 [v] (when (some? v) (not v)))
(defn- fn3 [f & xs] (when (every? some? xs) (apply f xs)))

(defn- json-number [n] (.stripTrailingZeros (bigdec n)))

(defn- json-normal
  "jsonb equality is structural with numeric numbers."
  [v]
  (cond (map? v) (into {} (map (fn [[k x]] [k (json-normal x)])) v)
        (sequential? v) (mapv json-normal v)
        (number? v) (json-number v)
        :else v))

(defn- same? [a b]
  (cond (or (map? a) (map? b) (sequential? a) (sequential? b)) (= (json-normal a) (json-normal b))
        (and (number? a) (number? b)) (zero? (compare a b))
        :else (= a b)))

(defn- cmp [op a b]
  (when (and (some? a) (some? b))
    (case op
      := (same? a b)
      :<> (not (same? a b))
      (let [c (compare a b)] (case op :< (neg? c) :> (pos? c) :<= (not (pos? c)) :>= (not (neg? c)))))))

(defn- in3
  "x IN (...): nil when nothing matched and a NULL was among the values."
  [v vs]
  (when (some? v)
    (cond (some #(cmp := v %) vs) true
          (some nil? vs) nil
          :else false)))

;;; arithmetic, temporal and string helpers

(defn- plus [a b]
  (cond (and (number? a) (number? b)) (+ a b)
        (instance? Duration b) (cond (instance? LocalDate a) (.plusDays ^LocalDate a (.toDays ^Duration b))
                                    (instance? Instant a) (.plus ^Instant a ^Duration b)
                                    (instance? LocalDateTime a) (.plus ^LocalDateTime a ^Duration b)
                                    (instance? Duration a) (.plus ^Duration a ^Duration b)
                                    :else (throw (ex-info "cannot add" {:a a :b b})))
        (instance? Duration a) (plus b a)
        (and (instance? LocalDate a) (integer? b)) (.plusDays ^LocalDate a b)
        (and (integer? a) (instance? LocalDate b)) (.plusDays ^LocalDate b a)
        :else (throw (ex-info "cannot add" {:a a :b b}))))

(defn- minus [a b]
  (cond (and (number? a) (number? b)) (- a b)
        (instance? Duration b) (plus a (.negated ^Duration b))
        (and (instance? LocalDate a) (integer? b)) (.minusDays ^LocalDate a b)
        (and (instance? LocalDate a) (instance? LocalDate b)) (- (.toEpochDay ^LocalDate a) (.toEpochDay ^LocalDate b))
        (and (instance? Instant a) (instance? Instant b)) (Duration/between b a)
        (and (instance? LocalDateTime a) (instance? LocalDateTime b)) (Duration/between b a)
        (and (map? a) (string? b)) (dissoc a b)
        (and (map? a) (sequential? b)) (apply dissoc a b)
        (and (vector? a) (integer? b)) (let [i (if (neg? b) (+ (count a) b) b)]
                                        (if (< -1 i (count a)) (into (subvec a 0 i) (subvec a (inc i))) a))
        (and (vector? a) (string? b)) (vec (remove #(= b %) a))
        :else (throw (ex-info "cannot subtract" {:a a :b b}))))

(defn- divide [a b]
  (cond (and (integer? a) (integer? b)) (quot a b)
        (or (decimal? a) (decimal? b)) (.divide (bigdec a) (bigdec b) java.math.MathContext/DECIMAL64)
        :else (/ a b)))

(defn- round-half-up [v digits]
  (let [r (.setScale (bigdec v) (int digits) java.math.RoundingMode/HALF_UP)]
    (cond (integer? v) v (double? v) (double r) :else r)))

(defn- floor* [v] (cond (integer? v) v (double? v) (Math/floor v) :else (.setScale (bigdec v) 0 java.math.RoundingMode/FLOOR)))
(defn- ceil* [v] (cond (integer? v) v (double? v) (Math/ceil v) :else (.setScale (bigdec v) 0 java.math.RoundingMode/CEILING)))
(defn- trunc* [v] (cond (integer? v) v (double? v) (double (long v)) :else (.setScale (bigdec v) 0 java.math.RoundingMode/DOWN)))

(defn- octets [v] (if (string? v) (alength (.getBytes ^String v "UTF-8")) (count v)))

(defn- trim-chars [chars s]
  (let [cs (set chars)]
    (->> s (drop-while cs) reverse (drop-while cs) reverse (apply str))))

(defn- substr
  "substring(s, from[, count]): 1-based, clipped like PostgreSQL."
  ([s from] (substr s from nil))
  ([s from n]
   (when (and n (neg? n)) (throw (ex-info "negative substring length" {})))
   (let [len (count s)
         start (min len (max 0 (dec from)))
         end (if n (min len (max start (+ (dec from) n))) len)]
     (subs s start end))))

(defn- left* [s n] (let [len (count s)] (if (neg? n) (subs s 0 (max 0 (+ len n))) (subs s 0 (min len n)))))
(defn- right* [s n] (let [len (count s)] (if (neg? n) (subs s (min len (- n))) (subs s (max 0 (- len n))))))

(defn- concat* [a b]
  (cond (and (string? a) (string? b)) (str a b)
        (and (vector? a) (vector? b)) (into a b)
        (and (map? a) (map? b)) (merge a b)
        (vector? a) (conj a b)
        (vector? b) (into [a] b)
        :else (str a b)))

;;; casts

(def ^:private integer-types #{:smallint :integer :bigint :int2 :int4 :int8})
(def ^:private decimal-types #{:numeric :decimal :real :double-precision :float4 :float8})
(def ^:private text-types #{:text :varchar :character-varying :char :character :bpchar :name})
(def ^:private temporal-types #{:date :timestamp :timestamp-without-time-zone :timestamptz :timestamp-with-time-zone :time :interval})
(def ^:private boolean-words {"t" true "true" true "y" true "yes" true "on" true "1" true
                              "f" false "false" false "n" false "no" false "off" false "0" false})

(def ^:private interval-units
  {"day" 86400 "days" 86400 "d" 86400 "hour" 3600 "hours" 3600 "h" 3600 "hr" 3600 "hrs" 3600
   "min" 60 "mins" 60 "minute" 60 "minutes" 60 "m" 60 "sec" 1 "secs" 1 "second" 1 "seconds" 1 "s" 1
   "week" 604800 "weeks" 604800 "w" 604800})

(defn- parse-interval
  "'1 day 02:03:04', '3 days', '01:00:00', '90 secs' as a Duration; months and years have no
   fixed length and are rejected."
  [s]
  (let [s (str/lower-case (str/trim s))
        [_ before hh mm ss after] (or (re-matches #"(?s)(.*?)(-?\d+):(\d\d)(?::(\d\d(?:\.\d+)?))?\s*(.*)" s) [nil s nil nil nil ""])
        words (remove str/blank? (str/split (str before " " after) #"\s+"))
        secs (loop [ws words acc 0]
               (cond (empty? ws) acc
                     (and (next ws) (re-matches #"[+-]?\d+(\.\d+)?" (first ws)) (interval-units (second ws)))
                     (recur (nnext ws) (+ acc (* (bigdec (first ws)) (interval-units (second ws)))))
                     :else (throw (ex-info "unsupported interval" {:interval s}))))
        clock (if hh
                (* (if (str/starts-with? hh "-") -1 1)
                   (+ (* 3600 (abs (parse-long hh))) (* 60 (parse-long mm)) (if ss (bigdec ss) 0)))
                0)]
    (Duration/ofMillis (long (* 1000 (+ secs clock))))))

(defn- parse-timestamptz [s]
  (let [s (str/replace (str/trim s) #"^(\S+) " "$1T")
        s (if (re-find #"[+-]\d\d$" s) (str s ":00") s)]
    (.toInstant (OffsetDateTime/parse s))))

(defn- parse-array
  "'{a,b}' as a vector of strings; elements may be double-quoted."
  [s]
  (let [s (str/trim s)
        inner (subs s 1 (dec (count s)))]
    (if (str/blank? inner)
      []
      (mapv (fn [e] (let [e (str/trim e)]
                      (cond (and (str/starts-with? e "\"") (str/ends-with? e "\"")) (str/replace (subs e 1 (dec (count e))) #"\\(.)" "$1")
                            (= "NULL" e) nil
                            :else e)))
            (re-seq #"\"(?:[^\"\\]|\\.)*\"|[^,]+" inner)))))

(defn- cast-to [v t]
  (cond
    (nil? v) nil
    (integer-types t) (cond (integer? v) v
                            (number? v) (long (.setScale (bigdec v) 0 java.math.RoundingMode/HALF_UP))
                            (boolean? v) (if v 1 0)
                            :else (Long/parseLong (str/trim v)))
    (decimal-types t) (if (number? v) v (bigdec (str/trim v)))
    (text-types t) (cond (string? v) v
                         (instance? LocalDateTime v) (str/replace (str v) "T" " ")
                         (instance? Instant v) (-> (str (.atOffset ^Instant v ZoneOffset/UTC)) (str/replace "T" " ") (str/replace #"Z$" "+00"))
                         :else (str v))
    (= :boolean t) (if (boolean? v)
                     v
                     (let [b (boolean-words (str/lower-case (str/trim v)))]
                       (if (nil? b) (throw (ex-info "not a boolean" {:value v})) b)))
    (= :date t) (cond (instance? LocalDate v) v
                      (instance? LocalDateTime v) (.toLocalDate ^LocalDateTime v)
                      :else (LocalDate/parse (str/trim v)))
    (#{:timestamp :timestamp-without-time-zone} t) (cond (instance? LocalDateTime v) v
                                                         (instance? LocalDate v) (.atStartOfDay ^LocalDate v)
                                                         :else (LocalDateTime/parse (str/replace (str/trim v) " " "T")))
    (#{:timestamptz :timestamp-with-time-zone} t) (if (instance? Instant v) v (parse-timestamptz v))
    (= :time t) (if (instance? LocalTime v) v (LocalTime/parse (str/trim v)))
    (= :interval t) (if (instance? Duration v) v (parse-interval v))
    (= :uuid t) (if (instance? UUID v) v (UUID/fromString (str/trim v)))
    (#{:json :jsonb} t) (if (string? v) (json/parse v) v)
    (str/ends-with? (name t) "-array") (if (string? v)
                                         (let [elem (keyword (subs (name t) 0 (- (count (name t)) 6)))]
                                           (mapv #(cast-to % elem) (parse-array v)))
                                         v)
    :else v))

;;; patterns

(defn- posix-classes
  "[[:digit:]] and friends as the \\p{Digit} classes Java understands."
  [re]
  (str/replace re #"\[:(alpha|digit|alnum|upper|lower|space|punct|xdigit|cntrl|print|graph|blank):\]"
               (fn [[_ c]] (str "\\p{" (str/capitalize c) "}"))))

(defn java-regex
  "The Java regex for a PostgreSQL pattern, or nil when it uses syntax Java reads differently."
  [re]
  (let [j (posix-classes re)]
    (when (and (not (re-find #"\\[mMyYZA]" re)) (try (re-pattern j) (catch Exception _ nil)))
      j)))

(defn like-regex
  "The anchored Java regex of a LIKE pattern (backslash escapes % and _)."
  [pattern]
  (str "^"
       (str/replace pattern #"\\(.)|%|_|[^%_\\]+"
                    (fn [[m esc]] (cond esc (java.util.regex.Pattern/quote esc)
                                        (= m "%") ".*"
                                        (= m "_") "."
                                        :else (java.util.regex.Pattern/quote m))))
       "$"))

(defn- matches? [s re] (some? (re-find (re-pattern (str "(?s)" re)) s)))

;;; jsonb

(declare json-text)

(defn- json-text*
  "A value inside a container, as PostgreSQL prints jsonb."
  [v]
  (cond (nil? v) "null" (string? v) (pr-str v) :else (json-text v)))

(defn- json-text
  "->> and #>>: scalars as text, containers as PostgreSQL prints jsonb (keys by length, then bytes)."
  [v]
  (cond (nil? v) nil
        (string? v) v
        (boolean? v) (str v)
        (number? v) (let [d (json-number v)] (if (zero? (.scale d)) (str (.toBigInteger d)) (.toPlainString d)))
        (map? v) (str "{" (str/join ", " (for [[k x] (sort-by (fn [[k _]] [(count k) k]) v)] (str (pr-str k) ": " (json-text* x)))) "}")
        (sequential? v) (str "[" (str/join ", " (map json-text* v)) "]")))

(defn- json-get [v k]
  (cond (and (map? v) (string? k)) (get v k)
        (and (sequential? v) (integer? k)) (let [i (if (neg? k) (+ (count v) k) k)] (when (< -1 i (count v)) (nth v i)))
        :else nil))

(defn- json-path [v path]
  (reduce (fn [v k] (json-get v (if (and (sequential? v) (string? k) (re-matches #"-?\d+" k)) (parse-long k) k))) v path))

(defn- json-contains?
  "a @> b, for jsonb and arrays alike: object keys of b in a with contained values, every
   element of array b contained in some element of array a, a top-level array may contain a scalar."
  [a b]
  (cond (and (map? a) (map? b)) (every? (fn [[k x]] (and (contains? a k) (json-contains? (get a k) x))) b)
        (and (sequential? a) (sequential? b)) (every? (fn [x] (some #(json-contains? % x) a)) b)
        (and (sequential? a) (not (map? b))) (boolean (some #(same? % b) a))
        (or (map? a) (map? b) (sequential? a) (sequential? b)) false
        :else (same? a b)))

(defn- json-has-key? [v k]
  (cond (map? v) (contains? v k)
        (sequential? v) (boolean (some #(= k %) v))
        :else (= k v)))

(defn- json-type [v]
  (cond (nil? v) nil (map? v) "object" (sequential? v) "array" (string? v) "string"
        (number? v) "number" (boolean? v) "boolean" :else "null"))

;;; evaluation

(defn- ev [e row]
  (cond
    (or (string? e) (number? e) (boolean? e) (nil? e)) e
    ;; columns whose names are not plain identifiers are string keys in rows
    (keyword? e) (if (contains? row e) (get row e) (get row (name e)))
    :else
    (let [[op & args] e
          a #(ev (first args) row)
          b #(ev (second args) row)
          c #(ev (nth args 2) row)
          all #(map (fn [x] (ev x row)) args)]
      (case op
        :cast (cast-to (a) (let [t (second args)] (if (vector? t) (first t) t)))
        :array (mapv #(ev % row) (first args))
        :raw (case (first args)
               "CURRENT_TIMESTAMP" (Instant/now) "CURRENT_DATE" (LocalDate/now) "LOCALTIMESTAMP" (LocalDateTime/now)
               "CURRENT_TIME" (OffsetTime/now) "LOCALTIME" (LocalTime/now))
        (:now :transaction_timestamp :statement_timestamp :clock_timestamp) (Instant/now)
        :and (and3 (all))
        :or (or3 (all))
        :not (not3 (a))
        (:= :<> :< :> :<= :>=) (let [r (second args)]
                                 (cond (and (vector? r) (= :any (first r))) (fn3 (fn [v vs] (or3 (map #(cmp op v %) vs))) (a) (ev (second r) row))
                                       (and (vector? r) (= :all (first r))) (fn3 (fn [v vs] (and3 (map #(cmp op v %) vs))) (a) (ev (second r) row))
                                       :else (cmp op (a) (b))))
        :is (let [v (a)] (if (nil? (second args)) (nil? v) (= v (second args))))
        :is-not (let [v (a)] (if (nil? (second args)) (some? v) (not= v (second args))))
        :is-distinct-from (let [l (a) r (b)] (not (or (and (nil? l) (nil? r)) (and (some? l) (some? r) (same? l r)))))
        :is-not-distinct-from (let [l (a) r (b)] (or (and (nil? l) (nil? r)) (and (some? l) (some? r) (same? l r))))
        :in (in3 (a) (map #(ev % row) (second args)))
        :not-in (not3 (in3 (a) (map #(ev % row) (second args))))
        :between (let [[v lo hi] (all)] (and3 [(cmp :>= v lo) (cmp :<= v hi)]))
        :between-symmetric (let [[v lo hi] (all)] (or3 [(and3 [(cmp :>= v lo) (cmp :<= v hi)]) (and3 [(cmp :>= v hi) (cmp :<= v lo)])]))
        :+ (fn3 plus (a) (b))
        :- (if (second args) (fn3 minus (a) (b)) (fn3 - (a)))
        :* (fn3 * (a) (b))
        :/ (fn3 divide (a) (b))
        :mod (fn3 rem (a) (b))
        :pow (fn3 (fn [x y] (let [r (Math/pow (double x) (double y))] (if (and (integer? x) (integer? y) (not (neg? y))) (bigdec r) r))) (a) (b))
        :abs (fn3 abs (a))
        :round (if (second args) (fn3 round-half-up (a) (b)) (fn3 #(round-half-up % 0) (a)))
        :floor (fn3 floor* (a))
        (:ceil :ceiling) (fn3 ceil* (a))
        :trunc (fn3 trunc* (a))
        :nullif (let [l (a) r (b)] (when-not (and (some? l) (some? r) (same? l r)) l))
        :greatest (let [vs (remove nil? (all))] (when (seq vs) (reduce #(if (pos? (compare %2 %1)) %2 %1) vs)))
        :least (let [vs (remove nil? (all))] (when (seq vs) (reduce #(if (neg? (compare %2 %1)) %2 %1) vs)))
        :coalesce (first (filter some? (all)))
        (:length :char_length :character_length) (fn3 count (a))
        :octet_length (fn3 octets (a))
        ;; TRIM(BOTH chars FROM x) parses as [:trim chars x]; btrim(x, chars) keeps the call order
        :trim (if (second args) (fn3 trim-chars (a) (b)) (fn3 str/trim (a)))
        :btrim (if (second args) (fn3 trim-chars (b) (a)) (fn3 str/trim (a)))
        :ltrim (if (second args) (fn3 (fn [s cs] (apply str (drop-while (set cs) s))) (a) (b)) (fn3 str/triml (a)))
        :rtrim (if (second args) (fn3 (fn [s cs] (apply str (reverse (drop-while (set cs) (reverse s))))) (a) (b)) (fn3 str/trimr (a)))
        :lower (fn3 str/lower-case (a))
        :upper (fn3 str/upper-case (a))
        :reverse (fn3 str/reverse (a))
        (:substr :substring) (if (nth args 2 nil) (fn3 substr (a) (b) (c)) (fn3 substr (a) (b)))
        :left (fn3 left* (a) (b))
        :right (fn3 right* (a) (b))
        :replace (fn3 (fn [s from to] (if (= "" from) s (str/replace s from to))) (a) (b) (c))
        :starts_with (fn3 (fn [s p] (str/starts-with? s p)) (a) (b))
        :strpos (fn3 (fn [s p] (inc (or (str/index-of s p) -1))) (a) (b))
        :concat (apply str (remove nil? (all)))
        :|| (fn3 concat* (a) (b))
        :like (fn3 (fn [s p] (matches? s (like-regex p))) (a) (b))
        :ilike (fn3 (fn [s p] (matches? s (str "(?i)" (like-regex p)))) (a) (b))
        :not-like (fn3 (fn [s p] (not (matches? s (like-regex p)))) (a) (b))
        :not-ilike (fn3 (fn [s p] (not (matches? s (str "(?i)" (like-regex p))))) (a) (b))
        :regex (fn3 (fn [s re] (matches? s (posix-classes re))) (a) (b))
        :iregex (fn3 (fn [s re] (matches? s (str "(?i)" (posix-classes re)))) (a) (b))
        :not-regex (fn3 (fn [s re] (not (matches? s (posix-classes re)))) (a) (b))
        :not-iregex (fn3 (fn [s re] (not (matches? s (str "(?i)" (posix-classes re))))) (a) (b))
        :subscript (fn3 (fn [v i] (when (<= 1 i (count v)) (nth v (dec i)))) (a) (b))
        :array_length (fn3 (fn [v _] (when (seq v) (count v))) (a) (b))
        :cardinality (fn3 count (a))
        :array_position (fn3 (fn [v x] (some (fn [[i y]] (when (same? x y) (inc i))) (map-indexed vector v))) (a) (b))
        :overlaps (fn3 (fn [l r] (boolean (some (fn [x] (some #(same? x %) r)) l))) (a) (b))
        :contains (fn3 json-contains? (a) (b))
        :contained-by (fn3 (fn [l r] (json-contains? r l)) (a) (b))
        :jsonb_typeof (json-type (a))
        :jsonb_array_length (fn3 (fn [v] (if (sequential? v) (count v) (throw (ex-info "not a JSON array" {})))) (a))
        :-> (fn3 json-get (a) (b))
        :->> (fn3 (fn [v k] (json-text (json-get v k))) (a) (b))
        :json-path (fn3 json-path (a) (b))
        :json-path-text (fn3 (fn [v p] (json-text (json-path v p))) (a) (b))
        :has-key (fn3 json-has-key? (a) (b))
        :has-any-key (fn3 (fn [v ks] (boolean (some #(json-has-key? v %) ks))) (a) (b))
        :has-all-keys (fn3 (fn [v ks] (every? #(json-has-key? v %) ks)) (a) (b))
        :case (let [[clauses else] (if (= :else (last (butlast args)))
                                     [(butlast (butlast args)) (last args)]
                                     [args nil])
                    ;; the first true condition selects the branch; its result may itself be NULL or false
                    branch (some (fn [[cnd r]] (when (true? (ev cnd row)) [r])) (partition 2 clauses))]
                (ev (if branch (first branch) else) row))
        (throw (ex-info (str "unsupported in CHECK: " op) {:unsupported op}))))))

;;; vocabulary

(def ^:private supported-ops
  #{:cast :array :raw :now :transaction_timestamp :statement_timestamp :clock_timestamp
    :and :or :not := :<> :< :> :<= :>= :is :is-not :is-distinct-from :is-not-distinct-from :in :not-in :between :between-symmetric
    :+ :- :* :/ :mod :pow :abs :round :floor :ceil :ceiling :trunc :nullif :greatest :least :coalesce
    :length :char_length :character_length :octet_length :trim :btrim :ltrim :rtrim :lower :upper :reverse
    :substr :substring :left :right :replace :starts_with :strpos :concat :||
    :like :ilike :not-like :not-ilike :regex :iregex :not-regex :not-iregex
    :subscript :array_length :cardinality :array_position :overlaps :contains :contained-by
    :jsonb_typeof :jsonb_array_length :-> :->> :json-path :json-path-text :has-key :has-any-key :has-all-keys
    :case})

(def ^:private raw-values #{"CURRENT_TIMESTAMP" "CURRENT_DATE" "LOCALTIMESTAMP" "CURRENT_TIME" "LOCALTIME"})

(def ^:private opaque-types
  ;; a literal cast to any type but these is an enum or domain value
  #{:regclass :oid :bytea :inet :cidr :macaddr :money :xml :tsvector})

(defn- literal-arg [v] (if (and (vector? v) (= :cast (first v))) (second v) v))

(defn- castable? [a t]
  (let [t (if (vector? t) (first t) t)
        converted? (or (temporal-types t) (#{:uuid :json :jsonb} t) (str/ends-with? (name t) "-array"))]
    (boolean
     (cond (or (integer-types t) (decimal-types t) (text-types t) (= :boolean t)) true
           converted? (or (not (string? a)) (try (cast-to a t) true (catch Exception _ false)))
           :else (and (string? a) (not (opaque-types t)))))))

(defn- supported-node? [[op a t]]
  (case op
    :cast (castable? a t)
    (:regex :iregex :not-regex :not-iregex) (let [re (literal-arg t)] (and (string? re) (some? (java-regex re))))
    (:like :ilike :not-like :not-ilike) (string? (literal-arg t))
    :raw (contains? raw-values a)
    (contains? supported-ops op)))

(defn supported?
  "Whether every operator, cast and pattern in the expression is implemented."
  [e]
  (every? #(or (not (vector? %)) (supported-node? %))
          (tree-seq vector?
                    (fn [v] (case (first v) (:in :not-in) (cons (second v) (nth v 2)) :array (second v) (rest v)))
                    (x/canonical e))))

(defn checker
  "Row predicate of a CHECK expression: true when the result is true or NULL, as in PostgreSQL."
  [e]
  (let [e (x/canonical e)]
    (fn [row] (not (false? (try (ev e row) (catch Exception _ false)))))))

(defn passes? [e row] ((checker e) row))
