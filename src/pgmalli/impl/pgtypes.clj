(ns pgmalli.impl.pgtypes
  "The schema types pgmalli registers for PostgreSQL's own: the CHECK types, a bytea of bounded
   length and the bounded numbers, with the ranges the integer types hold."
  (:require [malli.core :as m]
            [pgmalli.impl.eval :as check]))

(def check-schema
  "[:pg/check expr]: a row passes when the CHECK expression data does (pgmalli.impl.eval).
   A column missing from the row is NULL, or its value in the :pg/defaults property."
  (m/-simple-schema
   {:type :pg/check
    :compile (fn [{:keys [pg/defaults]} [expr] _]
               (let [pass? (check/checker expr)]
                 {:pred (fn [row] (and (map? row) (pass? (merge defaults row)))) :min 1 :max 1}))}))

(def bytes-schema
  "[:pg/bytes {:min n :max n}]: a byte array of bounded length, generated as such (malli's bytes?
   has no length)."
  (m/-simple-schema
   {:type :pg/bytes
    :compile (fn [{:keys [min max]} _ _]
               (let [fmap (requiring-resolve 'clojure.test.check.generators/fmap)
                     vector-of (requiring-resolve 'clojure.test.check.generators/vector)
                     byte-gen @(requiring-resolve 'clojure.test.check.generators/byte)]
                 {:pred (fn [v] (and (bytes? v) (<= (or min 0) (alength ^bytes v) (or max Integer/MAX_VALUE))))
                  :type-properties {:error/message (str "bytes, " (or min 0) " to " (or max "any") " of them")
                                    :gen/gen (fmap byte-array (vector-of byte-gen (or min 0) (or max (+ (or min 0) 16))))}
                  :min 0 :max 0}))}))

(def int-ranges
  "The range each integer type pgmalli registers holds (a bigint is exactly a long, so it is :int)."
  {:pg/smallint [-32768 32767] :pg/integer [-2147483648 2147483647]})

(defn- bounded-int
  "A schema type for an integer type of PostgreSQL: its range, narrowed by :min and :max
   properties from CHECKs, generated within :gen/min and :gen/max when given."
  [type]
  (let [[lo hi] (int-ranges type)]
    (m/-simple-schema
     {:type type
      :compile (fn [{:keys [min max] gen-min :gen/min gen-max :gen/max} _ _]
                 (let [lo (clojure.core/max lo (or min lo)) hi (clojure.core/min hi (or max hi))
                       large-integer* (requiring-resolve 'clojure.test.check.generators/large-integer*)]
                   {:pred (fn [v] (and (int? v) (<= lo v hi)))
                    :type-properties {:error/message (str "should be an integer between " lo " and " hi)
                                      :gen/gen (large-integer* {:min (clojure.core/max lo (or gen-min lo)) :max (clojure.core/min hi (or gen-max hi))})}
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
                     scaled (fn [d] (.longValueExact (.setScale (.movePointRight (bigdec d) (int scale)) 0 java.math.RoundingMode/HALF_UP)))
                     fmap (requiring-resolve 'clojure.test.check.generators/fmap)
                     large-integer* (requiring-resolve 'clojure.test.check.generators/large-integer*)]
                 {:pred (fn [v] (and (decimal? v) (< (.abs (.setScale ^BigDecimal v (int scale) java.math.RoundingMode/HALF_UP)) limit)))
                  :type-properties {:error/message (str "should fit numeric(" precision ", " scale ")")
                                    :gen/gen (fmap #(.movePointLeft (BigDecimal/valueOf ^long %) (int scale))
                                                   (large-integer* {:min (clojure.core/max (- bound) (if gen-min (scaled gen-min) (- bound)))
                                                                    :max (clojure.core/min bound (if gen-max (scaled gen-max) bound))}))}
                  :min 0 :max 0}))}))

(def integer-schema (bounded-int :pg/integer))

(def check-value-schema
  "[:pg/check-value expr]: a domain CHECK, the value standing for VALUE."
  (m/-simple-schema
   {:type :pg/check-value
    :compile (fn [_ [expr] _]
               (let [pass? (check/checker expr)]
                 {:pred (fn [v] (pass? {:VALUE v})) :min 1 :max 1}))}))

(def schemas
  "The schema types pgmalli registers, by name."
  {:pg/check check-schema :pg/check-value check-value-schema :pg/bytes bytes-schema
   :pg/smallint smallint-schema :pg/integer integer-schema :pg/numeric numeric-schema})
