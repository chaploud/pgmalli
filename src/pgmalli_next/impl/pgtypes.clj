(ns pgmalli-next.impl.pgtypes
  "The schema types pgmalli registers for PostgreSQL's own: a bytea of bounded
   length, the bounded numbers (with the ranges the integer types hold) and the arrays the
   JDBC driver takes."
  (:require [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

;; PostgreSQL counts and slices strings by character (code point), Java by UTF-16 unit
(defn- char-length
  "The length of v as PostgreSQL counts it: a string's in code points."
  [v]
  (if (string? v) (.codePointCount ^String v 0 (count v)) (count v)))

;; A BigDecimal read from a request can be 1e100000000: setScale would spell out its digits
(defn- integer-digits
  "Digits before the point, read off precision and scale; 0 or less when |d| < 1, and fewer than
   any bound for a zero (0E+30's precision and scale say 31)."
  [^BigDecimal d]
  (if (zero? (.signum d)) Integer/MIN_VALUE (- (.precision d) (.scale d))))

(defn- rounded
  "d at scale places, half up; a d too small to reach them is zero, without the division."
  ^BigDecimal [^BigDecimal d scale]
  (if (< (integer-digits d) (- scale)) (BigDecimal/valueOf 0 (int scale)) (.setScale d (int scale) java.math.RoundingMode/HALF_UP)))

(defn- float4?
  "Whether a double fits a real: single precision neither overflows to infinity nor
   underflows to zero, where PostgreSQL rejects the value."
  [d]
  (let [f (unchecked-float d)]
    (not (or (and (Float/isInfinite f) (not (Double/isInfinite d))) (and (zero? f) (not (zero? d)))))))

(def string-schema
  ":string with :min and :max counting characters (code points), as PostgreSQL's length and
   varchar(n) do, where malli's counts UTF-16 units. :pg/max-length n is a varchar(n) or
   char(n): it cuts spaces past n off, then :min and :max count what it keeps. A bit string
   (:pg/type bit or bit varying) holds 0s and 1s alone."
  (m/-simple-schema
   {:type :string :pred string?
    :property-pred (fn [props]
                     (let [bounds ((m/-min-max-pred char-length) props)
                           n (:pg/max-length props)
                           bits? (#{:bits :max-bits} (:typmod (types/entry (shape/type-name props))))
                           bounds (if bits? (fn [s] (boolean (and (re-matches #"[01]*" s) (or (nil? bounds) (bounds s))))) bounds)]
                       (if n
                         (fn [^String s]
                           (let [kept (if (<= (char-length s) n) s (subs s 0 (.offsetByCodePoints s 0 (int n))))]
                             (boolean (and (or (identical? kept s) (re-matches #" *" (subs s (count kept))))
                                           (or (nil? bounds) (bounds kept))))))
                         bounds)))}))

(def double-schema
  ":double, a real (:pg/type) within single precision's range: PostgreSQL rejects a value that
   overflows it or underflows to zero."
  (m/-simple-schema
   {:type :double :pred #(or (double? %) (instance? Float %))
    :property-pred (fn [props]
                     (let [bounds ((m/-min-max-pred nil) props)]
                       (if (= "real" (:name (types/entry (shape/type-name props))))
                         (fn [d] (and (float4? d) (or (nil? bounds) (bounds d))))
                         bounds)))}))

(def ^:private bytes-schema
  "[:pg/bytes {:min n :max n}]: a byte array of bounded length, generated as such (malli's bytes?
   has no length)."
  (m/-simple-schema
   {:type :pg/bytes
    :compile (fn [{:keys [min max]} _ _]
               {:pred (fn [v] (and (bytes? v) (<= (or min 0) (alength ^bytes v) (or max Integer/MAX_VALUE))))
                :type-properties {:error/message (str "bytes, " (or min 0) " to " (or max "any") " of them")
                                  :gen/gen (gen/fmap byte-array (gen/vector gen/byte (or min 0) (or max (+ (or min 0) 16))))}
                :min 0 :max 0})}))

(defn- sql-array? [v]
  ;; by name: babashka has no java.sql
  (boolean (some #(= "java.sql.Array" (.getName ^Class %)) (supers (class v)))))

(def ^:private array-classes
  "The Java array classes the one sending sends as an array type."
  (set (for [[c t] accept/classes :when (and (.isArray ^Class c) (string? t) (types/element t))] c)))

(def ^:private array-schema
  "[:pg/array t]: what PostgreSQL's JDBC driver takes as an array parameter: a Java array of a
   class the one sending sends as an array type (accept/classes: long[], Long[], String[] ...; not
   a byte[], a bytea, nor Object[]) of t's, of one of the classes :pg/classes names where it names
   some, or a java.sql.Array, whose elements are not read."
  (m/-simple-schema
   {:type :pg/array
    :compile (fn [{:pg/keys [classes]} [child] options]
               (let [valid? (m/validator child options)]
                 {:pred (fn [v] (or (sql-array? v)
                                    (let [c (class v)]
                                      (boolean (and (array-classes c)
                                                    (or (nil? classes) (contains? classes (.getName ^Class c)))
                                                    (every? valid? v))))))
                  :type-properties {:error/message "should be a typed Java array (long-array, (into-array String [...])) or a java.sql.Array of such elements; a vector goes as jsonb"}
                  :min 1 :max 1}))}))

(def int-ranges
  "The range each integer type pgmalli registers holds (a bigint is exactly a long, so it is :int)."
  (into {} (for [e (vals types/types) :when (and (:range e) (= "pg" (namespace (:schema e))))] [(:schema e) (:range e)])))

(defn- bounded-int
  "A schema type for an integer type of PostgreSQL: its range, narrowed by :min and :max
   properties from CHECKs, generated within :gen/min and :gen/max when given."
  [type]
  (let [[lo hi] (int-ranges type)]
    (m/-simple-schema
     {:type type
      :compile (fn [{:keys [min max] gen-min :gen/min gen-max :gen/max} _ _]
                 (let [lo (clojure.core/max lo (or min lo)) hi (clojure.core/min hi (or max hi))]
                   {:pred (fn [v] (and (int? v) (<= lo v hi)))
                    :type-properties {:error/message (str "should be an integer between " lo " and " hi)
                                      :gen/gen (gen/large-integer* {:min (clojure.core/max lo (or gen-min lo)) :max (clojure.core/min hi (or gen-max hi))})}
                    :min 0 :max 0}))})))

(def smallint-schema (bounded-int :pg/smallint))

(def numeric-schema
  "[:pg/numeric {:precision p :scale s}]: a BigDecimal a numeric(p, s) column stores: rounded to
   s places (half up, as PostgreSQL rounds on the way in), fewer than p - s digits before the
   point. s may exceed p, or be negative, as PostgreSQL allows."
  (m/-simple-schema
   {:type :pg/numeric
    :compile (fn [{:keys [precision scale] :or {scale 0} gen-min :gen/min gen-max :gen/max} _ _]
               (let [limit (.movePointRight 1M (int (- precision scale)))
                     digits (int (clojure.core/min precision 18))
                     bound (dec (long (.longValueExact (.movePointRight 1M digits))))
                     ;; generated within :gen/min and :gen/max (BigDecimals) when given, at the scale
                     scaled (fn [d] (.longValueExact (.setScale (.movePointRight (bigdec d) (int scale)) 0 java.math.RoundingMode/HALF_UP)))]
                 {:pred (fn [v] (and (decimal? v)
                                     ;; rounding adds a digit at most
                                     (<= (integer-digits v) (inc (- precision scale)))
                                     (< (.abs (rounded v scale)) limit)))
                  :type-properties {:error/message (str "should fit numeric(" precision ", " scale ")")
                                    :gen/gen (gen/fmap #(.movePointLeft (BigDecimal/valueOf ^long %) (int scale))
                                                   (gen/large-integer* {:min (clojure.core/max (- bound) (if gen-min (scaled gen-min) (- bound)))
                                                                    :max (clojure.core/min bound (if gen-max (scaled gen-max) bound))}))}
                  :min 0 :max 0}))}))

(def integer-schema (bounded-int :pg/integer))

(defn- found-on-a-large-stack
  "(re-find p x) on a thread whose stack holds it: java.util.regex recurses once per character
   for a regex such as ^([a-z0-9]|-)+$, past a thread's default stack on a long value that
   PostgreSQL's regex engine matches. false where even that stack runs out."
  [p x]
  (let [result (promise)
        t (Thread. nil #(deliver result (try (some? (re-find p x)) (catch StackOverflowError _ false)))
                   "pgmalli-re" (* 512 1024 1024))]
    (.start t)
    (.join t)
    @result))

(def re-schema
  "[:re re] as malli's, except a value java.util.regex runs out of stack on is matched on a
   larger stack (found-on-a-large-stack) instead of throwing StackOverflowError, which malli,
   catching Exception alone, lets through."
  (m/-simple-schema
   {:type :re
    :to-ast m/-to-value-ast
    :compile (fn [_ [re] _]
               (let [p (re-pattern re)]
                 {:pred (fn [x] (and (instance? CharSequence x)
                                     (try (some? (re-find p x)) (catch StackOverflowError _ (found-on-a-large-stack p x)))))
                  :min 1 :max 1}))}))

(def schemas
  "The schema types pgmalli registers, by name."
  {:re re-schema :string string-schema :double double-schema :pg/bytes bytes-schema
   :pg/smallint smallint-schema :pg/integer integer-schema :pg/numeric numeric-schema :pg/array array-schema})
