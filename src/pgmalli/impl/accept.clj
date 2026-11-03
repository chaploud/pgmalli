(ns pgmalli.impl.accept
  "Which parameter goes where, in two steps: the type pgjdbc sends a value of a class as, and
   whether PostgreSQL takes a parameter of that type where a value of another goes: compared
   with it (an operator), assigned to it (INSERT, UPDATE) or cast to it. Types are the names
   pgmalli.impl.types keys them by, \"x[]\" for an array of x, [:enum name] for an enum type;
   a caller passes a domain as its base type. pgmalli.accept-test holds both against pgjdbc
   and PostgreSQL."
  (:require [clojure.string :as str]))

;;; what pgjdbc sends

(defn- array "The class of an array of c." [c] (class (make-array c 0)))
(defn- named "The class named n; nil where the runtime has none." [n] (try (Class/forName n) (catch Throwable _ nil)))
(defn- array-of "The class of an array of c; nil where the runtime makes none." [c] (try (array c) (catch Throwable _ nil)))

(def class-types
  "[class type]: the type pgjdbc's setObject sends a value of class as, with no extension and
   its default stringtype (varchar): BigInt and BigInteger as setNumber sends a Number, a
   Character with setString, a Java array as an array of its elements' type."
  (vec (remove nil? [[Long "bigint"] [Integer "integer"] [Short "smallint"] [Byte "smallint"] [java.math.BigDecimal "numeric"]
   [clojure.lang.BigInt "numeric"] [java.math.BigInteger "numeric"] [Double "double precision"] [Float "real"]
   [Boolean "boolean"] [String "character varying"] [Character "character varying"] [java.util.UUID "uuid"]
   [(Class/forName "[B") "bytea"] [java.time.LocalDate "date"] [java.time.LocalTime "time without time zone"]
   [java.time.OffsetTime "time with time zone"] [java.time.LocalDateTime "timestamp without time zone"]
   [java.time.OffsetDateTime "timestamp with time zone"]
   ;; the arrays pgjdbc's ArrayEncoding encodes, primitive and boxed
   [(array Long/TYPE) "bigint[]"] [(array Long) "bigint[]"] [(array Integer/TYPE) "integer[]"] [(array Integer) "integer[]"]
   [(array Short/TYPE) "smallint[]"] [(array Short) "smallint[]"] [(array Double/TYPE) "double precision[]"]
   [(array Double) "double precision[]"] [(array Float/TYPE) "real[]"] [(array Float) "real[]"]
   [(array Boolean/TYPE) "boolean[]"] [(array Boolean) "boolean[]"] [(array String) "character varying[]"]
   [(class (make-array Byte/TYPE 0 0)) "bytea[]"] [(array java.math.BigDecimal) "numeric[]"]
   ;; babashka makes no UUID[]: where it cannot, the row is left out
   (some-> (array-of java.util.UUID) (vector "uuid[]"))
   ;; java.sql's arrays (babashka has not all of these classes)
   (some-> (named "java.sql.Date") array-of (vector "date[]"))
   (some-> (named "java.sql.Time") array-of (vector "time with time zone[]"))
   (some-> (named "java.sql.Timestamp") array-of (vector "timestamp with time zone[]"))])))

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

(def ^:private integers #{"smallint" "integer" "bigint"})
(def ^:private numbers (into integers ["real" "double precision" "numeric"]))
(def strings "The string types." #{"character varying" "text" "character" "name" "citext"})

(defn number-type? [t] (contains? numbers t))

(defn integer-type? [t] (contains? integers t))

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
  ;; PostgreSQL 18 casts bytea and the integers both ways; earlier versions refuse it
  (merge-with into (zipmap numbers (repeat #{"jsonb"})) (zipmap integers (repeat #{"bytea"}))
              {"boolean" #{"integer" "jsonb"} "integer" #{"boolean"} "bytea" integers}))

(defn- oid? [t] (and (string? t) (str/starts-with? t "reg") (not (str/ends-with? t "[]"))))

(defn element
  "The element type of an array type t (\"bigint[]\" is bigint); nil for another."
  [t]
  (when (string? t) (second (re-matches #"(.+)\[\]" t))))

(defn accepts?
  "Whether a parameter of type sent goes where a value of type t does, in context
   (:compare, :assign or :cast). sent :unknown is a value sent untyped (:send's :unknown), which PostgreSQL
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
  "The number types a number type takes as its own besides its own: the integers to a wider
   number type, real to double precision. PostgreSQL also converts numeric to real and double
   precision implicitly; pgmalli leaves that out, a decimal sent to a float column being the
   mix-up a type would catch."
  {"bigint" #{"smallint" "integer"} "integer" #{"smallint"}
   "numeric" integers "double precision" (conj integers "real") "real" integers})

(defn holds?
  "Whether a value sent as type sent is one of type t's own: its type, a number of a narrower
   kind (a narrower integer, an integer for a numeric, real or double precision, a real for a
   double precision), a string of another string type, or one sent untyped where t's values are text
   (a string type, an enum), which PostgreSQL reads as the value itself; compared, not where t is
   a domain over an enum ([:enum name {:domain? true}]), which has no = for an untyped value."
  ([t sent] (holds? t sent :assign))
  ([t sent context]
   (or (= t sent) (contains? (widened t) sent) (and (strings t) (strings sent))
       (and (= :unknown sent)
            (or (contains? strings t)
                (and (vector? t) (not (and (= :compare context) (:domain? (get t 2))))))))))
