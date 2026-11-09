(ns pgmalli-next.impl.pgtypes
  "The schema types pgmalli registers for PostgreSQL's own: a bytea of bounded
   length, the bounded numbers (with the ranges the integer types hold), the arrays the
   JDBC driver takes, the values of some classes of one schema (:pg/class), and
   malli.experimental.time's types with an error message each."
  (:require [clojure.string :as str]
            [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [malli.experimental.time :as time]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

;; PostgreSQL counts and slices strings by character (code point), Java by UTF-16 unit
(defn char-length
  "The length of v as PostgreSQL counts it: a string's in code points."
  [v]
  (if (string? v) (.codePointCount ^String v 0 (count v)) (count v)))

(defn float4?
  "Whether a double fits a real: single precision neither overflows to infinity nor
   underflows to zero, where PostgreSQL rejects the value."
  [d]
  (let [f (unchecked-float d)]
    (not (or (and (Float/isInfinite f) (not (Double/isInfinite d))) (and (zero? f) (not (zero? d)))))))

(defn char-value?
  "Whether string s is a value of PostgreSQL's \"char\", one byte: none, one ASCII character, or
   the \\ooo its output writes another byte as (which its input reads back); a longer one, or a
   character of more bytes (é), it cuts to its first byte without a word."
  [s]
  (boolean (or (re-matches #"[\x00-\x7F]?" s) (re-matches #"\\[0-3][0-7]{2}" s))))

(def string-schema
  ":string with :min and :max counting characters (code points), as PostgreSQL's length does,
   where malli's counts UTF-16 units. A bit string (:pg/type bit or bit varying) holds 0s and 1s
   alone, one of :pg/one-byte (a \"char\"'s, a bound a comparison drops) one byte (char-value?)."
  (m/-simple-schema
   {:type :string :pred string?
    :type-properties {:error/fn (fn [{:keys [schema value]} _]
                                  (when (and (string? value) (:pg/one-byte (m/properties schema)))
                                    "should be one byte: an ASCII character, or \\ooo"))}
    :property-pred (fn [props]
                     (let [bounds ((m/-min-max-pred char-length) props)]
                       (cond (#{:bits :max-bits} (:typmod (types/entry (shape/type-name props))))
                             (fn [s] (boolean (and (re-matches #"[01]*" s) (or (nil? bounds) (bounds s)))))
                             (:pg/one-byte props) char-value?
                             :else bounds)))}))

(defn real-number?
  "Whether number n (a Long, a BigDecimal ...) converts to a real: finite as a double, and
   within single precision's range."
  [n]
  (let [d (double n)] (and (not (Double/isInfinite d)) (float4? d))))

(def double-schema
  ":double, a real (:pg/type) within single precision's range: PostgreSQL rejects a value that
   overflows it or underflows to zero."
  (m/-simple-schema
   {:type :double :pred #(or (double? %) (instance? Float %))
    :property-pred (fn [props]
                     (let [bounds ((m/-min-max-pred nil) props)]
                       (if (types/single-precision? (shape/type-name props))
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

(defn- elements
  "The elements of array v, a sequential's or a set's; of a sequential of sequentials (a
   multi-dimensional array) its innermost ones, nil where they are not all as long, which
   PostgreSQL's array input refuses."
  [v]
  (if (and (sequential? v) (seq v) (every? sequential? v))
    (when (apply = (map count v))
      (reduce (fn [acc s] (if-let [es (elements s)] (into acc es) (reduced nil))) [] v))
    (vec v)))

(def ^:private array-schema
  "[:pg/array t]: an array argument: a vector or a seq of t's (of vectors, all as long, a
   multi-dimensional one: PostgreSQL's array types tell no dimensions), which the one sending
   sends as an array literal's text; under {:pg/set true}, where no order is seen, a set too."
  (m/-simple-schema
   {:type :pg/array
    :compile (fn [{sets? :pg/set} [child] options]
               (let [valid? (m/validator child options)]
                 {:pred (fn [v] (boolean (and (or (vector? v) (seq? v) (and sets? (set? v)))
                                              (some->> (elements v) (every? valid?)))))
                  :type-properties {:error/message (if sets? "should be a vector or a set of its elements" "should be a vector of its elements")}
                  :min 1 :max 1}))}))

(def int-ranges
  "The range each integer type pgmalli registers holds."
  (into {} (for [e (vals types/types) :when (and (:range e) (qualified-keyword? (:schema e)) (= "pg" (namespace (:schema e))))] [(:schema e) (:range e)])))

(defn- bounded-int
  "A schema type for an integer type of PostgreSQL: its range, narrowed by :min and :max
   properties (a shape's), generated within :gen/min and :gen/max when given."
  [type]
  (let [[lo hi] (int-ranges type)]
    (m/-simple-schema
     {:type type
      :compile (fn [{:keys [min max] gen-min :gen/min gen-max :gen/max} _ _]
                 (let [lo (clojure.core/max lo (or min lo)) hi (clojure.core/min hi (or max hi))]
                   {:pred (fn [v] (and (int? v) (<= lo v hi)))
                    ;; bounds that contradict each other leave no value, none generated
                    :type-properties (cond-> {:error/message (if (<= lo hi) (str "should be an integer between " lo " and " hi) "no integer fits: its bounds contradict each other")}
                                       (<= lo hi) (assoc :gen/gen (gen/large-integer* {:min (clojure.core/max lo (or gen-min lo)) :max (clojure.core/min hi (or gen-max hi))})))
                    :min 0 :max 0}))})))

(def smallint-schema (bounded-int :pg/smallint))

(def integer-schema (bounded-int :pg/integer))

(def re-schema
  "[:re re] as malli's, except a value java.util.regex runs out of the caller's stack on (it
   recurses once per character for a pattern such as ^([a-z0-9]|-)+$) is refused, never let
   through, where malli's throws StackOverflowError. No larger stack: a JVM unwinding an overflow
   of compiled frames takes native memory in proportion to the stack, which it keeps."
  (m/-simple-schema
   {:type :re
    :to-ast m/-to-value-ast
    :compile (fn [_ [re] _]
               (let [p (re-pattern re)]
                 {:pred (fn [x] (and (instance? CharSequence x)
                                     (try (some? (re-find p x)) (catch StackOverflowError _ false))))
                  :min 1 :max 1}))}))

(defn class-values
  "The values of accept/values of the classes names (\"java.lang.Integer\" ...), which a
   [:pg/class ...] generates; none of a class pgmalli has no value of (java.sql.SQLXML)."
  [names]
  (let [cs (set names)] (keep (fn [[c v]] (when (cs (.getName ^Class c)) v)) accept/values)))

(def ^:private class-schema
  "[:pg/class \"java.lang.Integer\" ...]: a value of one of the classes named, where the classes
   of one schema (:int, a Long's and an Integer's) go as types PostgreSQL takes apart."
  (m/-simple-schema
   {:type :pg/class
    :compile (fn [_ names _]
               (let [cs (set names)]
                 {:pred (fn [v] (contains? cs (some-> v class .getName)))
                  :type-properties (cond-> {:error/message (str "should be one of " (str/join ", " names))}
                                     (seq (class-values names)) (assoc :gen/gen (gen/elements (class-values names))))
                  :min 1 :max Long/MAX_VALUE}))}))

(defn- with-message
  "Schema type t as it is, with an :error/message: malli.error has none of malli.experimental.time's,
   which me/humanize then calls an unknown error."
  [t message]
  (m/-simple-schema
   {:type (m/-type t)
    :type-properties (assoc (m/-type-properties t) :error/message message)
    :compile (fn [props children opts] {:pred (m/validator (m/-into-schema t props children opts))})}))

(def time-schemas
  "malli.experimental.time's types, each with a message naming its class (\"should be a
   java.time.LocalDate\")."
  (into {} (for [[k t] (time/schemas)]
             [k (with-message t (str "should be a java.time." (str/join (map str/capitalize (str/split (name k) #"-")))))])))

(def schemas
  "The schema types pgmalli registers, by name."
  (merge time-schemas
         {:re re-schema :string string-schema :double double-schema :pg/bytes bytes-schema
          :pg/smallint smallint-schema :pg/integer integer-schema :pg/array array-schema
          :pg/class class-schema}))
