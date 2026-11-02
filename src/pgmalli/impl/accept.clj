(ns pgmalli.impl.accept
  "Which parameter goes where, in two steps: the type pgjdbc sends a value of a class as, and
   whether PostgreSQL takes a parameter of that type where a value of another goes: compared
   with it (an operator), assigned to it (INSERT, UPDATE) or cast to it. Types are the names
   pgmalli.impl.types keys them by, \"x[]\" for an array of x, [:enum name] for an enum type;
   a caller passes a domain as its base type. pgmalli.accept-test holds both against pgjdbc
   and PostgreSQL."
  (:require [clojure.string :as str]))

;;; what pgjdbc sends

(def ^:private class-types
  "[class type] pgjdbc's setObject sends a value of class as (any other Number with setNumber);
   a String and a Character go by the connection's stringtype, which :write's rows say."
  [[Long "bigint"] [Integer "integer"] [Short "smallint"] [Byte "smallint"] [java.math.BigDecimal "numeric"]
   [clojure.lang.BigInt "numeric"] [java.math.BigInteger "numeric"] [Double "double precision"] [Float "real"]
   [Boolean "boolean"] [java.util.UUID "uuid"]
   [(Class/forName "[B") "bytea"] [java.time.LocalDate "date"] [java.time.LocalTime "time without time zone"]
   [java.time.OffsetTime "time with time zone"] [java.time.LocalDateTime "timestamp without time zone"]
   [java.time.OffsetDateTime "timestamp with time zone"]])

(defn pgjdbc-write
  "[[schema type] ...]: the values an application commonly sends through pgjdbc with no
   extension, as malli schemas, and the type it sends each as; a String untyped (:unknown) under
   its stringtype=unspecified. pgjdbc sends more (a BigInt as numeric, a Float as real:
   class-types; a Character as a String), which an application adds as rows."
  [{:keys [stringtype]}]
  [[:int "bigint"] ['decimal? "numeric"] [:double "double precision"]
   [:boolean "boolean"] [:string (if (= :unspecified stringtype) :unknown "character varying")]
   [:uuid "uuid"] ['bytes? "bytea"] [:time/local-date "date"] [:time/local-time "time without time zone"]
   [:time/offset-time "time with time zone"] [:time/local-date-time "timestamp without time zone"]
   [:time/offset-date-time "timestamp with time zone"]])

(def default-write (pgjdbc-write {}))

(defn class-type
  "The type pgjdbc sends value as by its class; nil for a class class-types does not list."
  [value]
  (some (fn [[c t]] (when (instance? c value) t)) class-types))

;;; what PostgreSQL takes

(def covered
  "The scalar types pgmalli.accept-test holds accepts? to; a caller asks known? before
   trusting it."
  #{"smallint" "integer" "bigint" "real" "double precision" "numeric" "character varying" "text" "character" "name"
    "boolean" "bytea" "uuid" "date" "time without time zone" "time with time zone" "timestamp without time zone"
    "timestamp with time zone" "interval" "json" "jsonb" "inet" "regclass"})

(def covered-arrays #{"integer[]" "bigint[]" "character varying[]" "text[]"})

(defn known?
  "Whether accepts? answers for type t."
  [t]
  (boolean (or (vector? t) (covered t) (covered-arrays t))))

(def ^:private numbers #{"smallint" "integer" "bigint" "real" "double precision" "numeric"})
(def ^:private strings #{"character varying" "text" "character" "name" "citext"})

(defn number-type? [t] (contains? numbers t))

(defn integer-type? [t] (contains? #{"smallint" "integer" "bigint"} t))

(def ^:private families
  "Types whose comparison operators take each other: implicit casts and cross-type operators."
  [numbers strings #{"date" "timestamp without time zone" "timestamp with time zone"}])

(def ^:private compared
  "Types a type's comparison operators take besides its own and its family's."
  {"time without time zone" #{"time with time zone" "interval"}
   "time with time zone" #{"time without time zone"}
   "interval" #{"time without time zone"}})

(def ^:private assigned
  "Types an assignment cast turns into a type, besides what its comparisons take; a type of
   strings takes any type."
  {"time without time zone" #{"timestamp without time zone" "timestamp with time zone"}
   "time with time zone" #{"timestamp with time zone"}
   "json" #{"jsonb"}
   "jsonb" #{"json"}})

(def ^:private explicit
  "Types an explicit cast turns into a type, besides what an assignment takes and the I/O
   conversion from strings."
  (merge (zipmap numbers (repeat #{"jsonb"}))
         {"boolean" #{"integer" "jsonb"} "integer" #{"boolean" "jsonb"}}))

(defn- oid? [t] (and (string? t) (str/starts-with? t "reg")))

(defn- element [t] (when (string? t) (second (re-matches #"(.+)\[\]" t))))

(defn accepts?
  "Whether a parameter of type sent goes where a value of type t does, in context
   (:compare, :assign or :cast). sent :unknown is a value sent untyped (a :write row's), which PostgreSQL
   types from the context: anywhere but a comparison with json, which has no operator."
  [t sent context]
  (let [json-compared? (and (= "json" t) (= :compare context))]
    (cond
      (= :unknown sent) (not json-compared?)
      json-compared? false
      (= t sent) true
      ;; an enum takes its own type alone, and its labels as text when cast
      (vector? t) (and (= :cast context) (contains? strings sent))
      :else
      (boolean
       (or (some #(and (% t) (% sent)) families)
           ;; a reg* type is an oid: it takes any integer; integer and bigint take it by
           ;; assignment and cast too, smallint compared only
           (let [[_ other] (cond (oid? t) [t sent] (oid? sent) [sent t])]
             (or (#{"integer" "bigint"} other) (and (= "smallint" other) (or (oid? t) (= :compare context)))))
           (contains? (compared t) sent)
           (and (#{:assign :cast} context)
                (or (contains? strings t)
                    (contains? (assigned t) sent)
                    ;; text and varchar go to a regclass by assignment
                    (and (= "regclass" t) (#{"character varying" "text"} sent))
                    (and (element t) (element sent) (accepts? (element t) (element sent) context))))
           (and (= :cast context)
                (or (contains? strings sent)
                    (contains? (explicit t) sent))))))))

(def ^:private widened
  "The types a number type's own values include, besides its own: those it is wider than."
  {"numeric" #{"smallint" "integer" "bigint"}
   "double precision" #{"smallint" "integer" "bigint" "real"}
   "real" #{"smallint" "integer" "bigint"}})

(defn holds?
  "Whether a value sent as type sent is one of type t's own: its type, a number of a narrower
   kind (an integer for a numeric, a real for a double precision), which PostgreSQL converts
   implicitly, or one sent untyped where t's values are text (a string type, an enum), which
   PostgreSQL reads as the value itself."
  [t sent]
  (or (= t sent) (contains? (widened t) sent) (and (= :unknown sent) (or (vector? t) (contains? strings t)))))
