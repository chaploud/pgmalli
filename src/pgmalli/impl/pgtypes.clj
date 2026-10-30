(ns pgmalli.impl.pgtypes
  "The schema types pgmalli registers for PostgreSQL's own: the CHECK types, a bytea of bounded
   length, the bounded numbers (with the ranges the integer types hold) and the arrays the
   JDBC driver takes."
  (:require [clojure.test.check.generators :as gen]
            [malli.core :as m]
            [pgmalli.impl.eval :as ev]
            [pgmalli.impl.shape :as shape]
            [pgmalli.impl.types :as types]))

(defn- code-points [^String s] (.codePointCount s 0 (count s)))

(def string-schema
  ":string with :min and :max counting characters (code points), as PostgreSQL's length and
   varchar(n) do, where malli's counts UTF-16 units. :pg/max-length n is a varchar(n) or
   char(n): it cuts spaces past n off, then :min and :max count what it keeps. A bit string
   (:pg/type bit or bit varying) holds 0s and 1s alone."
  (m/-simple-schema
   {:type :string :pred string?
    :property-pred (fn [props]
                     (let [bounds ((m/-min-max-pred code-points) props)
                           n (:pg/max-length props)
                           bits? (#{:bits :max-bits} (:typmod (types/entry (shape/type-name props))))
                           bounds (if bits? (fn [s] (boolean (and (re-matches #"[01]*" s) (or (nil? bounds) (bounds s))))) bounds)]
                       (if n
                         (fn [^String s]
                           (let [kept (if (<= (code-points s) n) s (subs s 0 (.offsetByCodePoints s 0 (int n))))]
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
                       (if (#{"real" "float4"} (shape/type-name props))
                         (fn [d] (and (ev/float4? d) (or (nil? bounds) (bounds d))))
                         bounds)))}))

(def check-schema
  "[:pg/check expr]: a row passes when the CHECK expression data does (pgmalli.impl.eval).
   A column missing from the row is NULL, or its value in the :pg/defaults property; the
   :pg/types property holds the column types the evaluator reads (pgmalli.impl.eval/column-type)."
  (m/-simple-schema
   {:type :pg/check
    :compile (fn [{:keys [pg/defaults pg/types]} [expr] _]
               (let [pass? (ev/checker expr types)]
                 {:pred (fn [row] (and (map? row) (pass? (merge defaults row)))) :min 1 :max 1}))}))

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

(def ^:private array-schema
  "[:pg/array t]: what PostgreSQL's JDBC driver takes as an array parameter: a typed Java array
   (long[], Long[], String[] ...; not Object[], which it refuses) of t's, or a java.sql.Array,
   whose elements are not read."
  (m/-simple-schema
   {:type :pg/array
    :compile (fn [_ [child] options]
               (let [valid? (m/validator child options)]
                 {:pred (fn [v] (or (sql-array? v)
                                    (let [c (class v)]
                                      (boolean (and c (.isArray ^Class c) (not= Object (.getComponentType ^Class c)) (every? valid? v))))))
                  :type-properties {:error/message "a typed Java array or a java.sql.Array"}
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
                                     (<= (ev/integer-digits v) (inc (- precision scale)))
                                     (< (.abs (ev/rounded v scale)) limit)))
                  :type-properties {:error/message (str "should fit numeric(" precision ", " scale ")")
                                    :gen/gen (gen/fmap #(.movePointLeft (BigDecimal/valueOf ^long %) (int scale))
                                                   (gen/large-integer* {:min (clojure.core/max (- bound) (if gen-min (scaled gen-min) (- bound)))
                                                                    :max (clojure.core/min bound (if gen-max (scaled gen-max) bound))}))}
                  :min 0 :max 0}))}))

(def integer-schema (bounded-int :pg/integer))

(def check-value-schema
  "[:pg/check-value expr]: a domain CHECK, the value standing for VALUE, whose type the
   :pg/types property holds as :pg/check's does ({:VALUE :integer})."
  (m/-simple-schema
   {:type :pg/check-value
    :compile (fn [{:keys [pg/types]} [expr] _]
               (let [pass? (ev/checker expr types)]
                 {:pred (fn [v] (pass? {:VALUE v})) :min 1 :max 1}))}))

(def re-schema
  "[:re re] as malli's, except a value java.util.regex runs out of stack on fails instead of
   throwing StackOverflowError (a regex such as ^([a-z0-9]|-)+$ recurses once per character, and
   malli catches Exception alone). It fails rather than passes since the registry holds the
   application's own :re schemas too, which no database CHECK may stand behind."
  (m/-simple-schema
   {:type :re
    :compile (fn [_ [re] _]
               (let [p (re-pattern re)]
                 {:pred (fn [x] (and (instance? CharSequence x)
                                     (try (some? (re-find p x)) (catch StackOverflowError _ false))))
                  :min 1 :max 1}))}))

(def schemas
  "The schema types pgmalli registers, by name."
  {:re re-schema :string string-schema :double double-schema :pg/check check-schema :pg/check-value check-value-schema :pg/bytes bytes-schema
   :pg/smallint smallint-schema :pg/integer integer-schema :pg/numeric numeric-schema :pg/array array-schema})
