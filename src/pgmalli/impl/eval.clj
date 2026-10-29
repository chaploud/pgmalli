(ns pgmalli.impl.eval
  "Evaluates CHECK expression data against a row the way PostgreSQL does: nil is NULL,
   comparisons and functions yield nil when an operand is nil, and a CHECK passes unless its
   result is false. AND, OR and COALESCE stop at the first decisive operand, left to right. An
   expression PostgreSQL would fail on (division by zero, a cast that does not parse) fails
   the row, since the database would reject it too. Rows hold the registry's types (java.time,
   UUID, jsonb as maps and vectors).

   Only the vocabulary that occurs in CHECK constraints is implemented; supported? tells
   whether an expression is inside it, and render leaves the others unrendered."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [pgmalli.impl.expr :as x]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.pghash :as pghash]
            [pgmalli.impl.types :as types])
  (:import (java.math BigDecimal RoundingMode)
           (java.time Duration Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneId ZonedDateTime)
           (java.time.format DateTimeFormatter DateTimeFormatterBuilder)
           (java.time.temporal ChronoField)
           (java.util UUID)))

(defn- unknowable
  "Gives up on the row: its verdict depends on what the evaluator does not know (an enum's
   order, a numeric column's scale), so checker passes it rather than reject what PostgreSQL
   may accept."
  [what]
  (throw (ex-info (str "unknowable: " what) {::unknowable what})))

;;; three-valued logic and comparison

(defn- and3
  "Left to right, returning at the first false without touching the operands after it; nil
   when an operand was NULL and none false. rest, not next: next would realize the operand
   after a decisive one."
  [xs]
  (loop [xs (seq xs) r true]
    (if xs
      (let [v (first xs)]
        (cond (false? v) false
              (nil? v) (recur (seq (rest xs)) nil)
              :else (recur (seq (rest xs)) r)))
      r)))

(defn- or3 [xs]
  (loop [xs (seq xs) r false]
    (if xs
      (let [v (first xs)]
        (cond (true? v) true
              (nil? v) (recur (seq (rest xs)) nil)
              :else (recur (seq (rest xs)) r)))
      r)))

(defn- not3 [v] (when (some? v) (not v)))

(defn- json-normal
  "A jsonb value as the evaluator reads it: string keys (applications often read them as
   keywords), numbers as BigDecimals with the scale they were written with (->> prints it)."
  ([v] (json-normal v bigdec))
  ([v num]
   (cond (map? v) (into {} (map (fn [[k x]] [(if (keyword? k) (name k) k) (json-normal x num)])) v)
         (sequential? v) (mapv #(json-normal % num) v)
         (number? v) (num v)
         :else v)))

(def ^:private json-null
  "A JSON null as -> returns it: not SQL NULL, which a missing key gives. Inside a container a
   JSON null is nil."
  ::json-null)

(defn- nan? [v] (and (float? v) (Double/isNaN (double v))))

(defn- compare*
  "compare, NaN equal to itself and above every other number, as PostgreSQL orders floats
   and numerics (Clojure's compare takes NaN as equal to anything)."
  [a b]
  (cond (and (nan? a) (nan? b)) 0
        (nan? a) 1
        (nan? b) -1
        :else (compare a b)))

(defn- comparable-pair
  "Temporal operands of different types as PostgreSQL compares them: a date is its midnight,
   a timestamp is read in the session zone (the JVM's here) against a timestamptz."
  [a b]
  (let [zone (ZoneId/systemDefault)
        temporal? #(or (instance? Instant %) (instance? LocalDateTime %) (instance? LocalDate %))
        to-instant #(cond (instance? Instant %) %
                          (instance? LocalDateTime %) (.toInstant (.atZone ^LocalDateTime % zone))
                          (instance? LocalDate %) (.toInstant (.atStartOfDay ^LocalDate % zone)))
        to-local #(if (instance? LocalDate %) (.atStartOfDay ^LocalDate %) %)]
    (cond (= (class a) (class b)) [a b]
          (not (and (temporal? a) (temporal? b))) [a b]
          (or (instance? Instant a) (instance? Instant b)) [(to-instant a) (to-instant b)]
          :else [(to-local a) (to-local b)])))

(defn- same? [a b]
  (let [[a b] (comparable-pair a b)]
    (cond (or (map? a) (map? b) (sequential? a) (sequential? b))
          (let [numeric #(json-normal % (fn [n] (.stripTrailingZeros (bigdec n))))] (= (numeric a) (numeric b)))
          (and (number? a) (number? b)) (zero? (compare* a b))
          :else (= a b))))

(defn- order
  "compare* where PostgreSQL's order is the evaluator's; otherwise unknowable: text orders by
   its collation (digits alone sort alike in every one), an array or jsonb by rules that tell
   them apart, values of different kinds only inside jsonb."
  [a b]
  (let [digits? #(re-matches #"[0-9]*" %)]
    (cond (or (coll? a) (coll? b) (= json-null a) (= json-null b)) (unknowable :order)
          (and (string? a) (string? b) (not (and (digits? a) (digits? b)))) (unknowable :collation)
          ;; PostgreSQL orders a uuid by its bytes unsigned, UUID.compareTo by signed longs
          (and (instance? UUID a) (instance? UUID b)) (compare (str a) (str b))
          :else (try (compare* a b) (catch ClassCastException _ (unknowable :order))))))

(defn- cmp [op a b]
  (when (and (some? a) (some? b))
    (case op
      := (same? a b)
      :<> (not (same? a b))
      (let [[a b] (comparable-pair a b)
            c (order a b)]
        (case op :< (neg? c) :> (pos? c) :<= (not (pos? c)) :>= (not (neg? c)))))))

(defn- in3
  "x IN (...): nil when nothing matched and a NULL was among the values."
  [v vs]
  (when (some? v)
    (cond (some #(cmp := v %) vs) true
          (some nil? vs) nil
          :else false)))

;;; arithmetic, temporal and string helpers

(defn- float-result
  "A double precision result as PostgreSQL gives it: an error when it overflows to infinity
   from finite operands, or, where underflow? says so, rounds to zero from non-zero ones."
  [r operands underflow?]
  (when (double? r)
    (when (and (Double/isInfinite r) (not-any? #(and (float? %) (Double/isInfinite (double %))) operands))
      (throw (ex-info "value out of range: overflow" {})))
    (when (and underflow? (zero? r) (underflow? operands))
      (throw (ex-info "value out of range: underflow" {}))))
  r)

(defn- plus [a b]
  ;; addition commutes: the date, time or number goes first, the duration or day count second
  (let [[a b] (if (or (instance? Duration a) (and (or (integer? a) (instance? LocalTime a) (instance? OffsetTime a)) (instance? LocalDate b))) [b a] [a b])]
    (cond (and (number? a) (number? b)) (float-result (+ a b) [a b] nil)
          ;; date + integer is a date, date + interval a timestamp
          (and (instance? LocalDate a) (integer? b)) (.plusDays ^LocalDate a b)
          ;; date + time is a timestamp, date + timetz a timestamptz
          (and (instance? LocalDate a) (instance? LocalTime b)) (.atTime ^LocalDate a ^LocalTime b)
          (and (instance? LocalDate a) (instance? OffsetTime b)) (.toInstant (.atDate ^OffsetTime b a))
          (instance? LocalDate a) (.plus (.atStartOfDay ^LocalDate a) ^Duration b)
          (instance? Instant a) (.plus ^Instant a ^Duration b)
          (instance? LocalDateTime a) (.plus ^LocalDateTime a ^Duration b)
          ;; a time wraps around midnight
          (instance? LocalTime a) (.plus ^LocalTime a ^Duration b)
          (instance? Duration a) (.plus ^Duration a ^Duration b)
          :else (throw (ex-info "cannot add" {:a a :b b})))))

(defn- minus
  ([a] (if (instance? Duration a) (.negated ^Duration a) (- a)))
  ([a b]
  (cond (and (number? a) (number? b)) (float-result (- a b) [a b] nil)
        (instance? Duration b) (plus a (.negated ^Duration b))
        (and (instance? LocalDate a) (instance? LocalTime b)) (.minusNanos (.atStartOfDay ^LocalDate a) (.toNanoOfDay ^LocalTime b))
        (and (instance? LocalDate a) (integer? b)) (.minusDays ^LocalDate a b)
        (and (instance? LocalDate a) (instance? LocalDate b)) (- (.toEpochDay ^LocalDate a) (.toEpochDay ^LocalDate b))
        (and (instance? Instant a) (instance? Instant b)) (Duration/between b a)
        (and (instance? LocalDateTime a) (instance? LocalDateTime b)) (Duration/between b a)
        (and (instance? LocalTime a) (instance? LocalTime b)) (Duration/between b a)
        ;; jsonb: delete a key, keys, or an element; text[] deletes the string elements it holds
        (and (map? a) (string? b)) (dissoc a b)
        (and (map? a) (sequential? b)) (apply dissoc a b)
        (and (vector? a) (integer? b)) (let [i (if (neg? b) (+ (count a) b) b)]
                                        (if (< -1 i (count a)) (into (subvec a 0 i) (subvec a (inc i))) a))
        (and (vector? a) (string? b)) (vec (remove #(= b %) a))
        (and (vector? a) (sequential? b)) (vec (remove #(and (string? %) (some #{%} b)) a))
        :else (throw (ex-info "cannot subtract" {:a a :b b})))))

(defn- scale-interval
  "interval * or / a number (as double precision): microseconds rounded to even, as interval_mul
   and interval_div round them."
  [^Duration d f op]
  (let [f (double f)
        us (Math/rint (op (+ (* 1e6 (.getSeconds d)) (quot (.getNano d) 1000)) f))]
    (when (or (and (= op /) (zero? f)) (Double/isNaN us) (Double/isInfinite us) (> (abs us) 9.2e18))
      (throw (ex-info "interval out of range" {})))
    (Duration/ofNanos (* 1000 (long us)))))

(defn- times [a b]
  (let [[a b] (if (instance? Duration a) [b a] [a b])]
    (if (instance? Duration b)
      (scale-interval b a *)
      (float-result (* a b) [a b] #(not-any? zero? %)))))

(defn- nbase-head
  "[weight first-digit] of a numeric in PostgreSQL's base 10000 digits ([0 0] for zero)."
  [^BigDecimal x]
  (if (zero? (.signum x))
    [0 0]
    (let [x (.abs x)
          w (Math/floorDiv (- (.precision x) (.scale x) 1) 4)]
      [w (.intValue (.setScale (.movePointLeft x (int (* 4 w))) 0 RoundingMode/DOWN))])))

(defn- numeric-divide
  "numeric / numeric at the scale select_div_scale (numeric.c) picks: at least 16 significant
   digits and the operands' scales, rounded half away from zero."
  [^BigDecimal a ^BigDecimal b]
  (let [[w1 d1] (nbase-head a)
        [w2 d2] (nbase-head b)
        qweight (cond-> (- w1 w2) (<= d1 d2) dec)
        rscale (-> (- 16 (* 4 qweight)) (max (.scale a) (.scale b) 0) (min 1000))]
    (.divide a b (int rscale) RoundingMode/HALF_UP)))

(defn- divide [a b]
  (cond (instance? Duration a) (scale-interval a b /)
        (and (integer? a) (integer? b)) (quot a b)
        (or (float? a) (float? b)) (if (zero? b)
                                     (throw (ex-info "division by zero" {}))
                                     (float-result (/ (double a) (double b)) [a b] (fn [_] (not (zero? a)))))
        :else (numeric-divide (bigdec a) (bigdec b))))

(defn- power
  "^: numeric when an operand is a numeric and neither a double, exact for an exponent of 0 to
   1000 and unknowable otherwise (PostgreSQL rounds a numeric power to its own scale)."
  [x y]
  (if (and (or (decimal? x) (decimal? y)) (not-any? float? [x y]))
    (let [x (bigdec x)
          n (try (.intValueExact (bigdec y)) (catch ArithmeticException _ nil))]
      (if (and n (<= 0 n 1000) (< (* n (max 1 (- (.precision x) (.scale x)))) 100000))
        (.pow x (int n))
        (unknowable :power)))
    (let [r (float-result (Math/pow (double x) (double y)) [x y] (fn [_] (not (zero? x))))]
    (if (and (integer? x) (integer? y) (not (neg? y))) (bigdec r) r))))

(defn- set-scale
  "A numeric at digits places; a negative digits rounds left of the point, leaving scale 0."
  [v digits mode]
  (let [r (.setScale (bigdec v) (int digits) ^RoundingMode mode)]
    (if (neg? digits) (.setScale r 0) r)))

(defn- round*
  "round(v[, digits]): numeric ties go away from zero, double precision ties to even."
  ([v] (round* v 0))
  ([v digits]
   (let [r (set-scale v digits (if (double? v) RoundingMode/HALF_EVEN RoundingMode/HALF_UP))]
     (cond (and (integer? v) (zero? digits)) v (double? v) (double r) :else r))))

(defn- floor* [v] (cond (integer? v) v (double? v) (Math/floor v) :else (.setScale (bigdec v) 0 RoundingMode/FLOOR)))
(defn- ceil* [v] (cond (integer? v) v (double? v) (Math/ceil v) :else (.setScale (bigdec v) 0 RoundingMode/CEILING)))

(defn- trunc*
  "trunc(v): toward zero; trunc(v, digits) exists for numeric alone, an integer read as one."
  ([v] (cond (integer? v) v (double? v) (double (long v)) :else (.setScale (bigdec v) 0 RoundingMode/DOWN)))
  ([v digits] (set-scale v digits RoundingMode/DOWN)))

(defn- octets [v] (if (string? v) (alength (.getBytes ^String v "UTF-8")) (count v)))

;; PostgreSQL counts and slices strings by character (code point), Java by UTF-16 unit
(defn- chars* [v] (if (string? v) (.codePointCount ^String v 0 (count v)) (count v)))
(defn- cp-subs [^String s start end] (subs s (.offsetByCodePoints s 0 (int start)) (.offsetByCodePoints s 0 (int end))))

(defn- bpchar-value
  "A char(n) value as PostgreSQL reads it: trailing spaces insignificant, or, where LIKE, a
   regex or octet_length reads it, padded to n."
  [v n padded?]
  (let [s (str/replace v #" +$" "")]
    (if (and padded? n) (apply str s (repeat (- n (chars* s)) \space)) s)))

(defn- code-points [^String s] (vec (.toArray (.codePoints s))))

(defn- trim-chars
  "s without the characters (code points) of chars at the sides: :both, :leading or :trailing."
  [chars s side]
  (let [cs (set (code-points chars))
        trim #(drop-while cs %)
        v (cond-> (code-points s)
            (not= :trailing side) trim
            (not= :leading side) (-> reverse trim reverse))
        a (int-array v)]
    (String. a 0 (alength a))))

(defn- substr
  "substring(s, from[, count]): 1-based, clipped like PostgreSQL."
  ([s from] (substr s from nil))
  ([s from n]
   (when (and n (neg? n)) (throw (ex-info "negative substring length" {})))
   (let [len (chars* s)
         start (min len (max 0 (dec from)))
         end (if n (min len (max start (+ (dec from) n))) len)]
     (cp-subs s start end))))

(defn- left* [s n] (let [len (chars* s)] (if (neg? n) (cp-subs s 0 (max 0 (+ len n))) (cp-subs s 0 (min len n)))))
(defn- right* [s n] (let [len (chars* s)] (if (neg? n) (cp-subs s (min len (- n)) len) (cp-subs s (max 0 (- len n)) len))))

(declare json-text)

(def ^:private pg-time
  (-> (DateTimeFormatterBuilder.) (.appendPattern "HH:mm:ss") (.appendFraction ChronoField/MICRO_OF_SECOND 0 6 true) .toFormatter))

(def ^:private pg-date (DateTimeFormatter/ofPattern "uuuu-MM-dd"))

(defn- pg-offset
  "A UTC offset as PostgreSQL prints it: +09, -03:30, +00."
  [^ZonedDateTime z]
  (let [s (.getTotalSeconds (.getOffset z))
        a (abs s)]
    (str (if (neg? s) "-" "+") (format "%02d" (quot a 3600))
         (when (pos? (rem a 3600)) (format ":%02d" (quot (rem a 3600) 60)))
         (when (pos? (rem a 60)) (format ":%02d" (rem a 60))))))

(defn- text-out
  "v as PostgreSQL's output function prints it: booleans t and f where concat and || print
   them, true and false where a cast does; timestamptz in the session zone (the JVM's, as
   pgjdbc sets it). A value whose text the evaluator cannot tell is unknowable: a numeric's
   scale is its column's, a double's and an interval's text are PostgreSQL's own, an array
   reads as jsonb or a SQL array."
  [v cast?]
  (let [day? (fn [d] (<= 1 (.getYear ^LocalDate d) 9999))
        micros? (fn [t] (zero? (rem (.getNano ^LocalTime t) 1000)))
        zone (ZoneId/systemDefault)]
    (cond (string? v) v
          (boolean? v) (if cast? (str v) (if v "t" "f"))
          (integer? v) (str v)
          (instance? UUID v) (str v)
          (= json-null v) "null"
          (map? v) (json-text v)
          (and (instance? LocalDate v) (day? v)) (.format pg-date ^LocalDate v)
          (and (instance? LocalTime v) (micros? v)) (.format pg-time ^LocalTime v)
          (and (instance? LocalDateTime v) (day? (.toLocalDate ^LocalDateTime v)) (micros? (.toLocalTime ^LocalDateTime v)))
          (str (.format pg-date ^LocalDateTime v) " " (.format pg-time ^LocalDateTime v))
          (instance? Instant v) (let [z (.atZone ^Instant v zone)]
                                  (if (and (day? (.toLocalDate z)) (micros? (.toLocalTime z)))
                                    (str (.format pg-date z) " " (.format pg-time z) (pg-offset z))
                                    (unknowable :text)))
          :else (unknowable :text))))

(declare json-text*)

(defn- concat*
  "||: jsonb, where two objects merge and anything else concatenates as arrays (a scalar or an
   object as an array of itself); text, a jsonb printed as one, other values cast to text (true,
   not t); or a SQL array and an element. ja and jb tell an operand is jsonb by its expression
   (-> and the like give strings and numbers too); an object is jsonb, and so is anything but a
   string beside a jsonb (text || jsonb takes text alone)."
  ([a b] (concat* a b false false))
  ([a b ja jb]
   (let [ja (or ja (map? a) (= json-null a))
         jb (or jb (map? b) (= json-null b))
         ja (or ja (and jb (not (string? a))))
         jb (or jb (and ja (not (string? b))))
         elem #(if (= json-null %) nil %)]
     (cond (and ja jb) (if (and (map? a) (map? b))
                         (merge a b)
                         (into (if (vector? a) a [(elem a)]) (if (vector? b) b [(elem b)])))
           ja (str (json-text* a) (text-out b true))
           jb (str (text-out a true) (json-text* b))
           (and (string? a) (string? b)) (str a b)
           (or (coll? a) (coll? b)) (into (if (vector? a) a [a]) (if (vector? b) b [b]))
           (or (string? a) (string? b)) (str (text-out a true) (text-out b true))
           :else [a b]))))

;;; casts

(defn- eval-types [kind] (types/keywords #(= kind (:eval %))))
(def ^:private integer-types (eval-types :integer))
(def ^:private exact-types (eval-types :exact))
(def ^:private float-types (eval-types :float))
(def ^:private text-types (eval-types :text))
(def ^:private json-types (eval-types :json))
(def ^:private bpchar-types (types/keywords #(= "character" (:name %))))
(def ^:private temporal-types (eval-types :temporal))
(def ^:private int-ranges
  (update-vals (types/by-keyword :range) :range))

(defn- in-range [t v]
  (let [[lo hi] (int-ranges t)]
    (if (or (nil? lo) (<= lo v hi)) v (throw (ex-info "integer out of range" {:type t :value v})))))

(defn- parse-boolean*
  "bool_in: trimmed, any case, a prefix of true, false, yes or no, at least two letters of
   on or off, or 1 or 0."
  [s]
  (let [w (str/lower-case (str/trim s))
        prefix? (fn [word least] (and (<= least (count w)) (str/starts-with? word w)))]
    (cond (or (prefix? "true" 1) (prefix? "yes" 1) (prefix? "on" 2) (= "1" w)) true
          (or (prefix? "false" 1) (prefix? "no" 1) (prefix? "off" 2) (= "0" w)) false
          :else (throw (ex-info "not a boolean" {:value s})))))

(defn- cast-type [t] (if (vector? t) (first t) t))
(defn- array-elem [t] (when-let [[_ e] (re-matches #"(.+)-array" (name t))] (keyword e)))

(def ^:private interval-units
  {"day" 86400 "days" 86400 "d" 86400 "hour" 3600 "hours" 3600 "h" 3600 "hr" 3600 "hrs" 3600
   "min" 60 "mins" 60 "minute" 60 "minutes" 60 "m" 60 "sec" 1 "secs" 1 "second" 1 "seconds" 1 "s" 1
   "week" 604800 "weeks" 604800 "w" 604800})

(def ^:private interval-clock #"(?s)(.*?)(-?\d+):(\d\d)(?::(\d\d(?:\.\d+)?))?\s*(.*)")

(defn- clock-seconds [hh mm ss]
  (* (if (str/starts-with? hh "-") -1 1)
     (+ (* 3600 (abs (parse-long hh))) (* 60 (parse-long mm)) (if ss (bigdec ss) 0))))

(defn- unit-seconds
  "'3 days 90 secs' as seconds; months and years have no fixed length and are rejected."
  [words]
  (reduce (fn [acc [n u]]
            (if-let [secs (and (re-matches #"[+-]?\d+(\.\d+)?" (str n)) (interval-units u))]
              (+ acc (* (bigdec n) secs))
              (throw (ex-info "unsupported interval" {:words words}))))
          0
          (partition-all 2 words)))

(defn- parse-interval
  "'1 day 02:03:04', '3 days', '01:00:00', '90 secs' as a Duration."
  [s]
  (let [s (str/lower-case (str/trim s))
        [_ before hh mm ss after] (or (re-matches interval-clock s) [nil s nil nil nil ""])
        words (remove str/blank? (str/split (str before " " after) #"\s+"))]
    (Duration/ofMillis (long (* 1000 (+ (unit-seconds words) (if hh (clock-seconds hh mm ss) 0)))))))

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

(def ^:private json-number-or-string
  #"\"(?:[^\"\\]|\\.)*\"|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?")

(defn- parse-json
  "JSON text as data, a number a BigDecimal as written (1.50 keeps its scale, which ->> prints):
   each number goes to the parser as a marked string, as neither parser keeps a scale on
   babashka."
  [s]
  (let [marked (str/replace s json-number-or-string #(if (str/starts-with? % "\"") % (str "\"\u0000" % "\"")))
        p (json/parse marked)]
    (if (nil? p)
      json-null
      ;; arrays as vectors: babashka's cheshire gives lists
      (walk/postwalk #(cond (and (string? %) (str/starts-with? % "\u0000")) (bigdec (subs % 1))
                            (sequential? %) (vec %)
                            :else %)
                     p))))

(defn float4?
  "Whether a double fits a real: single precision neither overflows to infinity nor
   underflows to zero, where PostgreSQL rejects the value."
  [d]
  (let [f (unchecked-float d)]
    (not (or (and (Float/isInfinite f) (not (Double/isInfinite d))) (and (zero? f) (not (zero? d)))))))

(defn- float4
  "A real as PostgreSQL makes it from a number: single precision."
  [d]
  (if (float4? d) (double (unchecked-float d)) (throw (ex-info "value out of range for type real" {}))))

(def ^:private real-types #{:real :float4})

(defn- cast-to [v t]
  (cond
    (nil? v) nil
    ;; float to integer rounds ties to even, numeric to integer away from zero
    (integer-types t) (long (in-range t (cond (integer? v) v
                                              (number? v) (bigint (.setScale (bigdec v) 0 (if (double? v) java.math.RoundingMode/HALF_EVEN java.math.RoundingMode/HALF_UP)))
                                              (boolean? v) (if v 1 0)
                                              :else (Long/parseLong (str/trim v)))))
    (exact-types t) (bigdec (if (number? v) v (str/trim v)))
    (float-types t) (cond-> (double (if (number? v) v (parse-double (str/trim v)))) (real-types t) float4)
    (bpchar-types t) (str/replace (cast-to v :text) #" +$" "")
    (text-types t) (text-out v true)
    (= :boolean t) (cond (boolean? v) v (integer? v) (not (zero? v)) :else (parse-boolean* v))
    ;; timestamptz to the types without a zone: in the session zone, the JVM's (pgjdbc sets it)
    (and (instance? Instant v) (#{:date :timestamp :timestamp-without-time-zone :time :time-without-time-zone} t))
    (cast-to (LocalDateTime/ofInstant v (ZoneId/systemDefault)) t)
    (= :date t) (cond (instance? LocalDate v) v
                      (instance? LocalDateTime v) (.toLocalDate ^LocalDateTime v)
                      :else (LocalDate/parse (str/trim v)))
    (#{:timestamp :timestamp-without-time-zone} t) (cond (instance? LocalDateTime v) v
                                                         (instance? LocalDate v) (.atStartOfDay ^LocalDate v)
                                                         :else (LocalDateTime/parse (str/replace (str/trim v) " " "T")))
    (#{:timestamptz :timestamp-with-time-zone} t) (cond (instance? LocalDateTime v) (.toInstant (.atZone ^LocalDateTime v (ZoneId/systemDefault)))
                                                        (instance? LocalDate v) (.toInstant (.atStartOfDay ^LocalDate v (ZoneId/systemDefault)))
                                                        :else (parse-timestamptz v))
    (#{:time :time-without-time-zone} t) (cond (instance? LocalTime v) v
                      (instance? LocalDateTime v) (.toLocalTime ^LocalDateTime v)
                      (instance? OffsetTime v) (.toLocalTime ^OffsetTime v)
                      :else (LocalTime/parse (str/trim v)))
    (= :interval t) (if (instance? Duration v) v (parse-interval v))
    (= :uuid t) (if (instance? UUID v) v (UUID/fromString (str/trim v)))
    (json-types t) (if (string? v) (parse-json v) v)
    (array-elem t) (if (string? v) (mapv #(cast-to % (array-elem t)) (parse-array v)) v)
    :else v))

(defn fold-casts
  "The expression with its casts of number literals to number types done: (2.5)::integer is 3."
  [e]
  (walk/postwalk (fn [f]
                   (let [t (when (and (vector? f) (= :cast (first f)) (number? (second f))) (cast-type (nth f 2)))]
                     (if (or (integer-types t) (exact-types t) (float-types t))
                       (try (cast-to (second f) t) (catch Exception _ f))
                       f)))
                 e))

;;; patterns

(defn- posix-classes
  "[[:digit:]] and friends as the \\p{Digit} classes Java understands."
  [re]
  (str/replace re #"\[:(alpha|digit|alnum|upper|lower|space|punct|xdigit|cntrl|print|graph|blank):\]"
               (fn [[_ c]] (str "\\p{" (if (= "xdigit" c) "XDigit" (str/capitalize c)) "}"))))

(def ^:private non-ascii "\\x{80}-\\x{10FFFF}")

(defn- ascii-set
  "A bracket expression of the ASCII characters the Java regex atom matches, and extra."
  [atom extra]
  (if-let [p (try (re-pattern atom) (catch Exception _ nil))]
    (let [cs (filter #(re-matches p (str (char %))) (range 1 128))]
      (if (or (seq cs) (seq extra))
        (str "[" (apply str (map #(format "\\x%02X" %) cs)) extra "]")
        "(?!)"))
    atom))

(def ^:private escape-or-bracket
  "An escape or a bracket expression of a Java regex: what generation-regex's rewrites leave whole."
  "\\\\.|\\[\\^?\\]?(?:\\\\.|[^\\]\\\\])*\\]")

(def ^:private regex-token
  "An escape, a bracket expression (with its [:class:]es), a (? group opener, {, or a character."
  #"\\.|\[\^?\]?(?:\[:\w+:\]|\\.|[^\]\\])*\]|\(\?<?.?|\{,|[\s\S]")

(defn- java-reads-differently?
  "Whether a token of a PostgreSQL pattern means something else to Java, or nothing:
   - the escapes \\m \\M \\y \\Y (word boundaries), \\Z, \\A, \\v (a vertical tab), \\x (every hex
     digit that follows), \\u \\U \\c, and \\0 to \\9 (octal or back-references), also inside brackets;
   - a [ inside brackets ([[:<:]], [[.-.]], [[=a=]], which Java nests) and && (an intersection);
   - (? other than a group or a lookaround: embedded options;
   - {,n}, which Java reads as text."
  [t]
  (or (some #(re-matches #"\\[mMyYZAvxuUc0-9]" %) (re-seq #"\\." t))
      (and (str/starts-with? t "[") (< 1 (count t))
           (some? (re-find #"\[|&&" (str/replace (subs t 1) #"\\.|\[:\w+:\]" ""))))
      (and (str/starts-with? t "(?") (not (#{"(?:" "(?=" "(?!" "(?<=" "(?<!"} t)))
      (= "{," t)))

(def ^:private regex-forms
  "[loose strict] Java regexes for a PostgreSQL pattern, or nil when it uses syntax Java reads
   differently (java-reads-differently?; a *** director Java rejects). A class (\\w, [[:alpha:]] ...) takes
   its POSIX members in ASCII; outside ASCII the members depend on the server's C library and
   locale, so loose takes every non-ASCII character and strict none: a string strict matches
   PostgreSQL's matches, and one loose does not match PostgreSQL's does not. . is [\\s\\S], as
   PostgreSQL's matches a newline too; $ is \\z, as Java's $ also matches before a final newline;
   \\b is a backspace and \\B a backslash. Memoized: the keys are the regexes of a schema, a
   finite set."
  (memoize
   (fn [re]
     (let [tokens (re-seq regex-token re)
           form (fn [extra]
                  (apply str (for [t tokens]
                               (cond (= "." t) "[\\s\\S]"
                                     (= "$" t) "\\z"
                                     (= "\\b" t) "\\x08"
                                     (= "\\B" t) "\\\\"
                                     (re-matches #"\\[wWdDsS]" t) (ascii-set t extra)
                                     (and (str/starts-with? t "[") (< 1 (count t)))
                                     (let [t (str "[" (posix-classes (subs t 1)))]
                                       (if (re-find #"\\[wWdDsS]|\\p\{" t) (ascii-set t extra) t))
                                     :else t))))
           forms [(form non-ascii) (form "")]]
       (when (and (not-any? java-reads-differently? tokens)
                  (every? #(try (re-pattern %) (catch Exception _ nil)) forms))
         forms)))))

(defn java-regex
  "The Java regex a value PostgreSQL's pattern may match matches (see regex-forms); nil when
   the pattern has syntax Java reads differently."
  [re]
  (first (regex-forms re)))

(defn strict-regex
  "The Java regex java-regex gives, its classes without the non-ASCII characters: a string it
   matches PostgreSQL's pattern matches on any server."
  [re]
  (-> re (str/replace (str "[" non-ascii "]") "(?!)") (str/replace non-ascii "")))

(defn generation-regex
  "The regex a string generator reads, from java-regex's (case-insensitive or not): its strings
   match the regex given. Without the flags and \\z, which test.chuck refuses; classes as their
   ASCII members alone (which every server's classes have); any character a printable ASCII
   one; a case-insensitive one's letters as [aA]; repeats at most 4 more than their least."
  [re]
  (let [ci? (re-find #"^\(\?iu?\)" re)]
    (cond-> (-> re
                (str/replace #"^\(\?iu?\)" "")
                (str/replace #"(?<!\\)\\z" "")
                strict-regex
                (str/replace "[\\s\\S]" "[\\x20-\\x7E]"))
      ci? (str/replace (re-pattern (str escape-or-bracket "|\\p{L}"))
                       #(if (and (= 1 (count %) (count (str/lower-case %)) (count (str/upper-case %))) (not= (str/lower-case %) (str/upper-case %)))
                          (str "[" (str/lower-case %) (str/upper-case %) "]")
                          %))
      ;; repeats bounded: strings short enough for the other parts (a length, a domain) to keep
      true (str/replace (re-pattern (str escape-or-bracket "|\\*|\\+|\\{(\\d+),\\}"))
                        (fn [[m n]] (case m "*" "{0,4}" "+" "{1,4}" (if n (str "{" n "," (+ 4 (parse-long n)) "}") m)))))))

(defn- escape-regex
  "Literal text as a regex: each metacharacter backslashed (not \\Q...\\E, which a string
   generator does not read)."
  [s]
  (str/replace s #"[\\^$.|?*+()\[\]{}]" "\\\\$0"))

(def like-regex
  "The anchored Java regex of a LIKE pattern (backslash escapes % and _), memoized as java-regex is."
  (memoize
   (fn [pattern]
     (str "^"
          (str/replace pattern #"\\(.)|%|_|[^%_\\]+"
                       (fn [[m esc]] (cond esc (escape-regex esc)
                                           (= m "%") "[\\s\\S]*"
                                           (= m "_") "[\\s\\S]"
                                           :else (escape-regex m))))
          "\\z"))))

(defn case-insensitive
  "A Java regex matching regardless of case, of every letter as in the database (~*, ILIKE)."
  [re]
  (str "(?iu)" re))

(def ^:private compiled (memoize re-pattern))

(defn- matches? [s re] (some? (re-find (compiled re) s)))

(defn- regex-match
  "Whether s matches the pattern; nil (NULL, so a CHECK passes) when that depends on the
   server's classes outside ASCII."
  [s re f]
  (let [[loose strict] (regex-forms re)]
    (cond (matches? s (f strict)) true
          (or (= loose strict) (not (matches? s (f loose)))) false)))

;;; jsonb

(defn- json-string
  "A JSON string as PostgreSQL's escape_json writes it."
  [^String s]
  (str "\"" (str/replace s #"[\"\\\x00-\x1F]"
                          #(case % "\"" "\\\"" "\\" "\\\\" "\b" "\\b" "\f" "\\f" "\n" "\\n" "\r" "\\r" "\t" "\\t"
                                 (format "\\u%04x" (int (first %)))))
       "\""))

(defn- json-text*
  "A jsonb value as PostgreSQL prints it: a string quoted, a JSON null null."
  [v]
  (cond (or (nil? v) (= json-null v)) "null" (string? v) (json-string v) :else (json-text v)))

(defn- json-text
  "->> and #>>: scalars as text, containers as PostgreSQL prints jsonb (keys by length in bytes, then bytes)."
  [v]
  (cond (or (nil? v) (= json-null v)) nil
        (string? v) v
        (boolean? v) (str v)
        (number? v) (.toPlainString (bigdec v))
        (map? v) (str "{" (str/join ", " (for [[k x] (sort-by (fn [[^String k _]] (let [b (.getBytes k "UTF-8")] (into [(alength b)] (map #(bit-and % 0xff)) b))) v)] (str (json-string k) ": " (json-text* x)))) "}")
        (sequential? v) (str "[" (str/join ", " (map json-text* v)) "]")))

(defn- json-get [v k]
  (cond (and (map? v) (string? k)) (if (and (contains? v k) (nil? (get v k))) json-null (get v k))
        (and (sequential? v) (integer? k)) (let [i (if (neg? k) (+ (count v) k) k)] (when (< -1 i (count v)) (or (nth v i) json-null)))
        :else nil))

(defn- json-path [v path]
  (reduce (fn [v k] (json-get v (if (and (sequential? v) (string? k) (re-matches #"-?\d+" k)) (parse-long k) k))) v path))

(defn- json-contains-in
  "a @> b below the top level: object keys of b in a with contained values, every element of
   array b contained in some element of array a, scalars equal."
  [a b]
  (cond (and (map? a) (map? b)) (every? (fn [[k x]] (and (contains? a k) (json-contains-in (get a k) x))) b)
        (and (sequential? a) (sequential? b)) (every? (fn [x] (some #(json-contains-in % x) a)) b)
        (or (map? a) (map? b) (sequential? a) (sequential? b)) false
        :else (same? a b)))

(defn- json-contains?
  "a @> b, for jsonb and arrays alike; only a top-level array contains a scalar."
  [a b]
  (let [elem #(if (= json-null %) nil %) a (elem a) b (elem b)]
  (if (and (sequential? a) (not (coll? b)))
    (boolean (some #(same? % b) a))
    (json-contains-in a b))))

(defn- json-has-key? [v k]
  (cond (map? v) (contains? v k)
        (sequential? v) (boolean (some #(= k %) v))
        :else (= k v)))

(defn- json-type [v]
  (cond (nil? v) nil (= json-null v) "null" (map? v) "object" (sequential? v) "array" (string? v) "string"
        (number? v) "number" (boolean? v) "boolean"))

;;; evaluation

(def ^:private strict
  "Operators that are a function of their evaluated arguments: a NULL argument makes the
   result NULL, an exception fails the row. Arities follow PostgreSQL's."
  {:+ plus :- minus :* times :/ divide :mod rem :pow power :abs abs
   :round round* :floor floor* :ceil ceil* :ceiling ceil* :trunc trunc*
   :length chars* :char_length chars* :character_length chars* :octet_length octets
   :lower str/lower-case :upper str/upper-case :reverse str/reverse
   :substr substr :substring substr :left left* :right right*
   ;; TRIM(BOTH chars FROM x) parses as [:trim chars x]; btrim(x, chars) keeps the call order;
   ;; without chars, only spaces are trimmed
   :trim (fn ([s] (trim-chars " " s :both)) ([cs s] (trim-chars cs s :both)))
   :btrim (fn ([s] (trim-chars " " s :both)) ([s cs] (trim-chars cs s :both)))
   :ltrim (fn ([s] (trim-chars " " s :leading)) ([s cs] (trim-chars cs s :leading)))
   :rtrim (fn ([s] (trim-chars " " s :trailing)) ([s cs] (trim-chars cs s :trailing)))
   :replace (fn [s from to] (if (= "" from) s (str/replace s from to)))
   :starts_with (fn [s p] (str/starts-with? s p))
   :strpos (fn [s p] (if-let [i (str/index-of s p)] (inc (.codePointCount ^String s 0 (int i))) 0))
   :|| concat*
   :like (fn [s p] (matches? s (like-regex p)))
   :ilike (fn [s p] (matches? s (case-insensitive (like-regex p))))
   :not-like (fn [s p] (not (matches? s (like-regex p))))
   :not-ilike (fn [s p] (not (matches? s (case-insensitive (like-regex p)))))
   :regex (fn [s re] (regex-match s re identity))
   :iregex (fn [s re] (regex-match s re case-insensitive))
   :not-regex (fn [s re] (some-> (regex-match s re identity) not))
   :not-iregex (fn [s re] (some-> (regex-match s re case-insensitive) not))
   :subscript (fn [v i] (when (<= 1 i (count v)) (nth v (dec i))))
   :array_length (fn [v dim] (when (and (= 1 dim) (seq v)) (count v)))
   :cardinality count
   :array_position (fn [v x] (some (fn [[i y]] (when (same? x y) (inc i))) (map-indexed vector v)))
   :overlaps (fn [l r] (boolean (some (fn [x] (some #(same? x %) r)) l)))
   :contains json-contains?
   :contained-by (fn [l r] (json-contains? r l))
   :jsonb_typeof json-type
   :jsonb_array_length (fn [v] (if (sequential? v) (count v) (throw (ex-info "not a JSON array" {}))))
   :-> json-get
   :->> (fn [v k] (json-text (json-get v k)))
   :json-path json-path
   :json-path-text (fn [v p] (json-text (json-path v p)))
   :has-key json-has-key?
   :has-any-key (fn [v ks] (boolean (some #(json-has-key? v %) ks)))
   :has-all-keys (fn [v ks] (every? #(json-has-key? v %) ks))})

(def ^:private raw-values
  {"CURRENT_TIMESTAMP" #(Instant/now) "CURRENT_DATE" #(LocalDate/now) "LOCALTIMESTAMP" #(LocalDateTime/now)
   "CURRENT_TIME" #(OffsetTime/now) "LOCALTIME" #(LocalTime/now)})

;; ev's throws ("cannot add" and the like) are control flow, not messages a user sees: an
;; expression PostgreSQL would fail on fails the row, and checker's try/catch turns that into
;; false.
(defn- ev [e row]
  (cond
    (or (string? e) (number? e) (boolean? e) (nil? e)) e
    ;; columns whose names are not plain identifiers are string keys in rows
    (keyword? e) (let [v (if (contains? row e) (get row e) (get row (name e)))]
                   (if (or (map? v) (sequential? v)) (json-normal v) v))
    :else
    (let [[op & args] e
          a #(ev (first args) row)
          b #(ev (second args) row)
          ;; args is a chunked seq; mapping over a list keeps AND, OR and COALESCE from
          ;; evaluating past the first decisive operand
          all #(map (fn [x] (ev x row)) (apply list args))]
      (if-let [f (strict op)]
        ;; every argument is evaluated (an error in any fails the row), then strictness applies
        (let [vs (doall (all))] (when (every? some? vs) (apply f vs)))
        (case op
          :cast (let [t (second args) v (cast-to (a) (cast-type t))]
                  ;; an explicit cast to varchar(n) cuts the value to n characters
                  (cond (and (vector? t) (text-types (cast-type t)) (not (bpchar-types (cast-type t))) (integer? (second t)) (string? v))
                        (let [n (int (second t))] (if (< n (.codePointCount ^String v 0 (count v))) (subs v 0 (.offsetByCodePoints ^String v 0 n)) v))
                        ;; numeric(p,s) rounds to s places, and fails past p digits
                        (and (vector? t) (exact-types (cast-type t)) (integer? (second t)) (decimal? v))
                        (let [[_ p sc] t
                              r (.setScale ^BigDecimal v (int (or sc 0)) java.math.RoundingMode/HALF_UP)]
                          (if (< (- (.precision r) (.scale r)) (inc (- p (or sc 0)))) r (throw (ex-info "numeric field overflow" {}))))
                        :else v))
          :array (mapv #(ev % row) (first args))
          :raw ((raw-values (first args)))
          ::bpchar (let [[col n padded?] args] (some-> (ev col row) (bpchar-value n padded?)))
          ::int (let [[t x] args] (some->> (ev x row) (in-range t)))
          ::jsonb-text (some-> (a) json-text*)
          ::concat (let [[x y jx jy] args vx (ev x row) vy (ev y row)] (when (and (some? vx) (some? vy)) (concat* vx vy jx jy)))
          (:now :transaction_timestamp :statement_timestamp :clock_timestamp) (Instant/now)
          :and (and3 (all))
          :or (or3 (all))
          :not (not3 (a))
          (:= :<> :< :> :<= :>=) (let [r (second args)]
                                   (cond (and (vector? r) (= :any (first r))) (let [v (a) vs (ev (second r) row)] (when (and (some? v) (some? vs)) (or3 (map #(cmp op v %) vs))))
                                         (and (vector? r) (= :all (first r))) (let [v (a) vs (ev (second r) row)] (when (and (some? v) (some? vs)) (and3 (map #(cmp op v %) vs))))
                                         :else (cmp op (a) (b))))
          :is (let [v (a)] (if (nil? (second args)) (nil? v) (= v (second args))))
          :is-not (let [v (a)] (if (nil? (second args)) (some? v) (not= v (second args))))
          :is-distinct-from (let [l (a) r (b)] (not (or (and (nil? l) (nil? r)) (and (some? l) (some? r) (same? l r)))))
          :is-not-distinct-from (let [l (a) r (b)] (or (and (nil? l) (nil? r)) (and (some? l) (some? r) (same? l r))))
          :in (in3 (a) (map #(ev % row) (second args)))
          :not-in (not3 (in3 (a) (map #(ev % row) (second args))))
          :between (let [[v lo hi] (all)] (and3 [(cmp :>= v lo) (cmp :<= v hi)]))
          :between-symmetric (let [[v lo hi] (all)] (or3 [(and3 [(cmp :>= v lo) (cmp :<= v hi)]) (and3 [(cmp :>= v hi) (cmp :<= v lo)])]))
          :nullif (let [l (a) r (b)] (when-not (and (some? l) (some? r) (same? l r)) l))
          :greatest (let [vs (remove nil? (all))] (when (seq vs) (reduce #(if (cmp :> %2 %1) %2 %1) vs)))
          :least (let [vs (remove nil? (all))] (when (seq vs) (reduce #(if (cmp :< %2 %1) %2 %1) vs)))
          :coalesce (first (filter some? (all)))
          :concat (apply str (map #(text-out % false) (remove nil? (all))))
          ::unordered (let [[x cols] args]
                        (if (some #(let [v (ev % row)] (or (string? v) (keyword? v) (coll? v))) cols) (unknowable :order) (ev x row)))
          ;; a hash partition's bound: the row's key hashed as the database hashes it
          :satisfies_hash_partition (let [[_ modulus remainder & vs] (all)] (pghash/satisfies-hash-partition? modulus remainder vs))
          :case (let [[clauses else] (if (= :else (last (butlast args)))
                                       [(butlast (butlast args)) (last args)]
                                       [args nil])
                      ;; the first true condition selects the branch; its result may itself be NULL or false
                      branch (some (fn [[cnd r]] (when (true? (ev cnd row)) [r])) (partition 2 clauses))]
                  (ev (if branch (first branch) else) row))
          (throw (ex-info (str "unsupported in CHECK: " op) {:unsupported op})))))))

;;; vocabulary

(def ^:private supported-ops
  (into (set (keys strict))
        [:cast :array :raw :now :transaction_timestamp :statement_timestamp :clock_timestamp
         :and :or :not := :<> :< :> :<= :>= :is :is-not :is-distinct-from :is-not-distinct-from
         :in :not-in :between :between-symmetric :nullif :greatest :least :coalesce :concat :case :satisfies_hash_partition]))

(defn- literal-arg [v] (if (and (vector? v) (= :cast (first v))) (second v) v))

(defn- castable?
  "Whether cast-to converts to t: the types it knows, or a literal of one of the schema's own
   enum and domain types (types), which is the value itself."
  [a t types]
  (let [t (cast-type t)]
    (boolean
     (cond (or (integer-types t) (exact-types t) (float-types t) (text-types t) (= :boolean t)) true
           (or (temporal-types t) (= :uuid t) (json-types t) (array-elem t))
           (or (not (string? a)) (try (cast-to a t) true (catch Exception _ false)))
           :else (and (string? a) (contains? types (name t)))))))

(def ^:private ordering-ops #{:< :> :<= :>= :between :between-symmetric :greatest :least})

(defn- supported-node? [[op a t :as e] types]
  (if (ordering-ops op)
    ;; a literal of the schema's own type (an enum) ordered: by labels the evaluator does not have
    (not-any? #(and (vector? %) (= :cast (first %)) (contains? types (name (cast-type (nth % 2))))) (rest e))
    (case op
      ;; a cast to char(n) cuts and pads, one to time(p) or timestamp(p) rounds: not evaluated
      :cast (and (castable? a t types) (not (and (vector? t) (or (bpchar-types (cast-type t)) (temporal-types (cast-type t))))))
      (:regex :iregex :not-regex :not-iregex) (let [re (literal-arg t)] (and (string? re) (some? (java-regex re))))
      (:like :ilike :not-like :not-ilike) (string? (literal-arg t))
      :raw (contains? raw-values a)
      (contains? supported-ops op))))

(defn supported?
  "Whether every operator, cast and pattern in the expression is implemented; types are the
   schema's enum and domain names, whose literals are values as they are."
  ([e] (supported? e #{}))
  ([e types]
   (every? #(or (not (vector? %)) (supported-node? % types))
           (tree-seq vector?
                     (fn [v] (case (first v) (:in :not-in) (cons (second v) (nth v 2)) :array (second v) :cast [(second v)] (rest v)))
                     (x/canonical e)))))

(defn column-type
  "The type the evaluator reads for a column, from its schema's properties (:pg/type, and
   :pg/max-length, a char(n)'s n): an integer or char(n) type; :real, whose value PostgreSQL
   stores in single precision before its CHECKs read it; :unordered for a type pgmalli
   has no entry for (an enum, whose order is its labels', or a domain); nil for the others."
  [{:keys [pg/type pg/max-length]}]
  (let [t (some-> type (str/replace " " "-") keyword)]
    (cond (int-ranges t) t
          (bpchar-types t) [t max-length]
          (real-types t) :real
          (and type (nil? (types/entry (str/replace type #"\(.*\)|\[\]" "")))) :unordered)))

(defn- int-type
  "The integer type of an expression whose operand types are known: smallint, integer or
   bigint columns, integer literals, integer casts and arithmetic over them."
  [e types]
  (let [rank {:smallint 0 :int2 0 :integer 1 :int4 1 :bigint 2 :int8 2}
        widest #(when (and (seq %) (every? rank %)) (apply max-key rank %))]
    (cond (keyword? e) (let [t (get types e)] (when (rank t) t))
          (int? e) (if (<= -2147483648 e 2147483647) :integer :bigint)
          (not (vector? e)) nil
          :else (case (first e)
                  ::int (second e)
                  (:+ :- :* :/ :mod :abs) (widest (map #(int-type % types) (rest e)))
                  :cast (let [t (cast-type (nth e 2))] (when (rank t) t))
                  nil))))

(defn- jsonb-expr?
  "Whether e is a jsonb by its form: ->, #>, a cast to jsonb, || or - of jsonb."
  [e]
  (and (vector? e)
       (case (first e)
         (:-> :json-path) true
         :cast (boolean (json-types (cast-type (nth e 2))))
         :|| (every? jsonb-expr? (rest e))
         :- (jsonb-expr? (second e))
         false)))

(def ^:private padded-ops #{:like :ilike :not-like :not-ilike :regex :iregex :not-regex :not-iregex :octet_length})

(defn- typed
  "The expression with its columns' types made explicit: char(n) columns as ::bpchar, integer
   arithmetic as ::int, which fails out of its type's range as PostgreSQL does."
  [e types]
  (let [rw #(typed % types)]
    (cond
      (keyword? e) (let [t (get types e)]
                     (if (and (vector? t) (bpchar-types (first t))) [::bpchar e (second t) false] e))
      (not (vector? e)) e
      :else
      (let [[op & args] e
            e (case op
                :cast (if (and (text-types (cast-type (nth e 2))) (jsonb-expr? (second e)))
                        [::jsonb-text (rw (second e))]
                        (assoc e 1 (rw (second e))))
                :|| [::concat (rw (first args)) (rw (second args)) (jsonb-expr? (first args)) (jsonb-expr? (second args))]
                (:in :not-in) [op (rw (second e)) (mapv rw (nth e 2))]
                :array [op (mapv rw (second e))]
                :raw e
                (into [op] (map #(if (= :else %) % (rw %))) args))
            bpchar? #(and (vector? %) (= ::bpchar (first %)))
            e (cond (and (padded-ops op) (bpchar? (second e))) (assoc-in e [1 3] true)
                    ;; a literal compared with a char(n) is one too
                    (and (#{:= :<> :< :> :<= :>=} op) (some bpchar? (rest e))) (mapv #(if (string? %) (bpchar-value % nil false) %) e)
                    :else e)
            t (when (#{:+ :- :* :/ :mod :abs} op) (int-type e types))
            ;; an enum orders by its labels, which the evaluator does not have
            unordered (when (ordering-ops op) (seq (filter #(and (keyword? %) (= :unordered (get types %))) (rest e))))]
        (cond t [::int t e]
              unordered [::unordered e (vec unordered)]
              :else e)))))

(defn checker
  "Row predicate of a CHECK expression: true when the result is true or NULL, as in PostgreSQL.
   types are the columns' types as column-type gives them ({:n :integer :c [:character 4]})."
  ([e] (checker e {}))
  ([e types]
   (let [e (typed (x/canonical e) types)
         reals (seq (for [[k t] types :when (= :real t)] k))
         stored (if reals
                  (fn [row] (reduce (fn [r k] (let [v (get r k)] (if (and (number? v) (float4? v)) (assoc r k (double (unchecked-float v))) r))) row reals))
                  identity)]
     (fn [row] (not (false? (try (ev e (stored row)) (catch Exception ex (boolean (::unknowable (ex-data ex)))))))))))

(defn unknowable?
  "Whether the row's verdict depends on what the evaluator does not know, so checker passes it."
  [e row types]
  (try (ev (typed (x/canonical e) types) row) false (catch Exception ex (boolean (::unknowable (ex-data ex))))))

(defn passes?
  ([e row] ((checker e) row))
  ([e row types] ((checker e types) row)))
