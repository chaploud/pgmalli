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
  "[class type] pgjdbc's setObject sends a value of class as (a Character with setString, any
   other Number with setNumber)."
  [[Long "bigint"] [Integer "integer"] [Short "smallint"] [Byte "smallint"] [java.math.BigDecimal "numeric"]
   [clojure.lang.BigInt "numeric"] [java.math.BigInteger "numeric"] [Double "double precision"] [Float "real"]
   [Boolean "boolean"] [String "character varying"] [Character "character varying"] [java.util.UUID "uuid"]
   [(Class/forName "[B") "bytea"] [java.time.LocalDate "date"] [java.time.LocalTime "time without time zone"]
   [java.time.OffsetTime "time with time zone"] [java.time.LocalDateTime "timestamp without time zone"]
   [java.time.OffsetDateTime "timestamp with time zone"]])

(def default-sends
  "The types pgjdbc sends an application's values as, with no extension, in the order an
   argument's alternatives are listed."
  ["bigint" "numeric" "double precision" "real" "boolean" "character varying" "uuid" "bytea" "date"
   "time without time zone" "time with time zone" "timestamp without time zone" "timestamp with time zone"])

(def sent-values
  "The malli schema of the values pgjdbc sends as each type of default-sends."
  {"bigint" :int
   "numeric" [:or 'decimal? 'integer?]
   "double precision" :double
   "real" 'float?
   "boolean" :boolean
   "character varying" [:or :string 'char?]
   "uuid" :uuid
   "bytea" 'bytes?
   "date" :time/local-date
   "time without time zone" :time/local-time
   "time with time zone" :time/offset-time
   "timestamp without time zone" :time/local-date-time
   "timestamp with time zone" :time/offset-date-time})

(defn sent-type
  "The type value goes to PostgreSQL as: :unknown for one sent untyped, which PostgreSQL types
   from the context (nil; a String under stringtype=unspecified, or written into the SQL under
   sql/format's :inline); nil for a class pgjdbc takes no value of."
  [value {:keys [stringtype format]}]
  (cond (nil? value) :unknown
        (string? value) (if (or (= :unspecified stringtype) (:inline format)) :unknown "character varying")
        :else (some (fn [[c t]] (when (instance? c value) t)) class-types)))

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
   (:compare, :assign or :cast). sent :unknown is one pgjdbc sends untyped, which PostgreSQL
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
           ;; a reg* type is an oid, which the integers compare with, and integer and bigint
           ;; take by assignment and cast too
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
  "Whether a value sent as type sent is one of type t's own: its type, or a number of a
   narrower kind (an integer for a numeric, a real for a double precision), which PostgreSQL
   takes as the same number."
  [t sent]
  (or (= t sent) (contains? (widened t) sent)))
