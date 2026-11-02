(ns pgmalli.impl.accept
  "Which type of parameter PostgreSQL takes where a value of a type goes: compared with it
   (an operator), assigned to it (INSERT, UPDATE) or cast to it. Types are the names
   pgmalli.impl.types keys them by, \"x[]\" for an array of x, [:enum name] for an enum type;
   a domain is its base type. pgmalli.accept-test holds this against PostgreSQL itself."
  (:require [clojure.string :as str]))

(def covered
  "The scalar types accepts? answers for (known? adds a few arrays and every enum); for
   another type a caller keeps its own rule."
  ["smallint" "integer" "bigint" "real" "double precision" "numeric" "character varying" "text" "character" "name"
   "boolean" "bytea" "uuid" "date" "time without time zone" "time with time zone" "timestamp without time zone"
   "timestamp with time zone" "interval" "json" "jsonb" "inet" "regclass"])

(def ^:private covered-arrays #{"integer[]" "bigint[]" "character varying[]" "text[]"})

(defn known?
  "Whether accepts? answers for type t: one covered, an array of one of covered-arrays, an enum."
  [t]
  (boolean (or (vector? t) (some #{t} covered) (covered-arrays t))))

(def ^:private families
  "Types whose comparison operators take each other: implicit casts and cross-type operators."
  [#{"smallint" "integer" "bigint" "real" "double precision" "numeric"}
   #{"character varying" "text" "character" "name" "citext"}
   #{"date" "timestamp without time zone" "timestamp with time zone"}])

(def ^:private strings (second families))

(def ^:private compared
  "Types a type's comparison operators take besides its own and its family's."
  {"time without time zone" #{"time with time zone" "interval"}
   "time with time zone" #{"time without time zone"}
   "interval" #{"time without time zone"}})

(def ^:private assigned
  "Types an assignment cast turns into a type, besides what its comparisons take and what
   every type of strings takes."
  {"time without time zone" #{"timestamp without time zone" "timestamp with time zone"}
   "time with time zone" #{"timestamp with time zone"}
   "json" #{"jsonb"}
   "jsonb" #{"json"}})

(def ^:private explicit
  "Types an explicit cast turns into a type, besides what an assignment takes and the I/O
   conversion from strings."
  {"boolean" #{"integer" "jsonb"}
   "integer" #{"boolean" "jsonb"}
   "smallint" #{"jsonb"} "bigint" #{"jsonb"} "real" #{"jsonb"} "double precision" #{"jsonb"} "numeric" #{"jsonb"}})

(defn- oid? [t] (and (string? t) (str/starts-with? t "reg")))

(defn- element [t] (when (string? t) (second (re-matches #"(.+)\[\]" t))))

(defn accepts?
  "Whether a parameter of type sent goes where a value of type t does, in context
   (:compare, :assign or :cast). sent is :unknown for one pgjdbc sends untyped (nil, a String
   under stringtype=unspecified), which PostgreSQL types from the context: anywhere but a
   comparison with json, which has no operator."
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
                    ;; text and varchar cast to a reg* type (regclass ...) by assignment
                    (and (oid? t) (#{"character varying" "text"} sent))
                    (and (element t) (element sent) (accepts? (element t) (element sent) :assign))))
           (and (= :cast context)
                (or (contains? strings sent)
                    (contains? (explicit t) sent)
                    (and (element t) (element sent) (accepts? (element t) (element sent) :cast)))))))))
