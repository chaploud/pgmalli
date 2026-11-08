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
(defn- char-length
  "The length of v as PostgreSQL counts it: a string's in code points."
  [v]
  (if (string? v) (.codePointCount ^String v 0 (count v)) (count v)))

(defn- float4?
  "Whether a double fits a real: single precision neither overflows to infinity nor
   underflows to zero, where PostgreSQL rejects the value."
  [d]
  (let [f (unchecked-float d)]
    (not (or (and (Float/isInfinite f) (not (Double/isInfinite d))) (and (zero? f) (not (zero? d)))))))

(def string-schema
  ":string with :min and :max counting characters (code points), as PostgreSQL's length does,
   where malli's counts UTF-16 units. A bit string (:pg/type bit or bit varying) holds 0s and 1s
   alone."
  (m/-simple-schema
   {:type :string :pred string?
    :property-pred (fn [props]
                     (let [bounds ((m/-min-max-pred char-length) props)]
                       (if (#{:bits :max-bits} (:typmod (types/entry (shape/type-name props))))
                         (fn [s] (boolean (and (re-matches #"[01]*" s) (or (nil? bounds) (bounds s)))))
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

(defn- array-class?
  "Whether the one sending sends a Java array of class c as an array type (accept/row-of)."
  [^Class c]
  (boolean (and c (.isArray c) (some-> (accept/row-of c accept/classes) second string?)
                (some? (types/element (second (accept/row-of c accept/classes)))))))

(defn- elements
  "The elements of Java array v, of an array of arrays (accept/multi-dimensional?) its innermost
   ones; nil where its arrays are not all as long, which pgjdbc cannot send."
  [v]
  (if (accept/multi-dimensional? (class v))
    (let [subs (seq v)]
      (when (apply = (map count subs))
        (reduce (fn [acc s] (if-let [es (elements s)] (into acc es) (reduced nil))) [] subs)))
    (vec (seq v))))

(def ^:private array-schema
  "[:pg/array t]: what PostgreSQL's JDBC driver takes as an array parameter: a Java array of a
   class the one sending sends as an array type (accept/classes: long[], Long[], String[] ...; not
   a byte[], a bytea, nor Object[]) of t's, of one of the classes :pg/classes names where it names
   some, or a java.sql.Array, whose elements are not read."
  (m/-simple-schema
   {:type :pg/array
    :compile (fn [{:pg/keys [classes]} [child] options]
               (let [valid? (m/validator child options)
                     ;; the arrays whose elements t takes, as their component class's value tells
                     fits (for [[^Class c] accept/classes
                                :let [e (some-> c .getComponentType) v (get accept/values (get accept/boxed e e))]
                                :when (and (array-class? c) (some? v) (or (nil? classes) (contains? classes (.getName c)))
                                           (try (valid? v) (catch Exception _ false)))]
                            (.getSimpleName c))]
                 {:pred (fn [v] (or (sql-array? v)
                                    (let [c (class v)]
                                      (boolean (and (array-class? c)
                                                    (or (nil? classes) (contains? classes (.getName ^Class c)))
                                                    (some->> (elements v) (every? valid?)))))))
                  :type-properties {:error/message (if (seq fits)
                                                     (str "should be a Java array (" (str/join ", " (distinct fits)) ") or a java.sql.Array of its elements; a vector goes as jsonb")
                                                     "should be a typed Java array or a java.sql.Array of its elements; a vector goes as jsonb")}
                  :min 1 :max 1}))}))

(def int-ranges
  "The range each integer type pgmalli registers holds."
  (into {} (for [e (vals types/types) :when (and (:range e) (qualified-keyword? (:schema e)) (= "pg" (namespace (:schema e))))] [(:schema e) (:range e)])))

(defn- bounded-int
  "A schema type for an integer type of PostgreSQL: its range, narrowed by :min and :max
   properties (an override's), generated within :gen/min and :gen/max when given."
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

(defn- found-on-a-large-stack
  "(re-find p x) on a thread whose stack holds it: java.util.regex recurses once per character
   for a regex such as ^([a-z0-9]|-)+$, past a thread's default stack on a long value that
   PostgreSQL's regex engine matches. true where even that stack runs out: the value is not
   refused for what java.util.regex cannot tell."
  [p x]
  (let [result (promise)
        t (Thread. nil #(deliver result (try (some? (re-find p x)) (catch StackOverflowError _ true)))
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

(def ^:private class-schema
  "[:pg/class \"java.lang.Integer\" ...]: a value of one of the classes named, where the classes
   of one schema (:int, a Long's and an Integer's) go as types PostgreSQL takes apart."
  (m/-simple-schema
   {:type :pg/class
    :compile (fn [_ names _]
               (let [cs (set names)]
                 {:pred (fn [v] (contains? cs (.getName (class v))))
                  :type-properties {:error/message (str "should be one of " (str/join ", " names))
                                    :gen/gen (gen/elements (keep (fn [[c v]] (when (cs (.getName ^Class c)) v)) accept/values))}
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
