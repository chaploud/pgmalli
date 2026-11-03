(ns pgmalli.impl.accept
  "Which parameter goes where, in two steps: the type the one sending sends a value of a class as, and
   whether PostgreSQL takes a parameter of that type where a value of another goes: compared
   with it (an operator), assigned to it (INSERT, UPDATE) or cast to it. Types are the names
   pgmalli.impl.types keys them by, \"x[]\" for an array of x, [:enum name] for an enum type;
   a caller passes a domain as its base type. pgmalli.accept-test holds both against the one
   sending and PostgreSQL."
  (:require [pgmalli.impl.resolve :as resolve]
            [pgmalli.impl.types :as types]))

;;; what an application sends

(defn- array "The class of an array of c." [c] (class (make-array c 0)))
(defn- named "The class named n; nil where the runtime has none." [n] (try (Class/forName n) (catch Throwable _ nil)))
(defn- array-of "The class of an array of c; nil where the runtime makes none." [c] (try (array c) (catch Throwable _ nil)))

(def bytes-class "The class of byte[], which pgjdbc sends as a bytea." (Class/forName "[B"))

(def classes
  "[class type schema]: the one sending (docs/reading-rows.md#the-one-reading-and-sending), the
   type a value of class goes to PostgreSQL as, :unknown for one sent untyped, which PostgreSQL
   types from where it goes; schema is the malli schema of a value of class, nil for a Java
   array. pgjdbc's setObject with its default stringtype (varchar): BigInt and BigInteger as
   setNumber sends a Number, a Character with setString, a Java array as an array of its
   elements' type; next.jdbc.date-time's setTimestamp (an Instant, a LocalDate, a LocalDateTime,
   a java.util.Date) and setDate (a java.sql.Date), untyped; and the doc's extension: a
   java.sql.Time with setTime, untyped, a keyword its text untyped, a map and a vector as jsonb.
   A value goes as its class's row, else as the first row of a superclass or interface of it."
  (filterv some? [[Long "bigint" :int] [Integer "integer" :int] [Short "smallint" :int] [Byte "smallint" :int]
   [java.math.BigDecimal "numeric" 'decimal?] [clojure.lang.BigInt "numeric" 'integer?] [java.math.BigInteger "numeric" 'integer?]
   [Double "double precision" :double] [Float "real" 'float?]
   [Boolean "boolean" :boolean] [String "character varying" :string] [Character "character varying" 'char?]
   [java.util.UUID "uuid" :uuid] [bytes-class "bytea" 'bytes?] [java.time.LocalDate :unknown :time/local-date]
   [java.time.LocalTime "time without time zone" :time/local-time] [java.time.OffsetTime "time with time zone" :time/offset-time]
   [java.time.LocalDateTime :unknown :time/local-date-time]
   [java.time.OffsetDateTime "timestamp with time zone" :time/offset-date-time]
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
   (some-> (named "java.sql.Timestamp") array-of (vector "timestamp with time zone[]"))
   [java.time.Instant :unknown :time/instant]
   ;; before java.util.Date, its superclass
   (some-> (named "java.sql.Time") (vector :unknown 'inst?))
   [java.util.Date :unknown 'inst?]
   [clojure.lang.Keyword :unknown :keyword]
   [clojure.lang.IPersistentMap "jsonb" [:map-of :any :any]] [clojure.lang.IPersistentVector "jsonb" [:vector :any]]]))

(def class-types
  "[class type] of classes, in its order."
  (mapv (fn [[c t]] [c t]) classes))

(def text-untyped
  "The classes sent untyped whose text is any text, which PostgreSQL reads as a value of a string
   type or an enum's label; the others sent untyped are dates and times, a column's own values
   where it is of their type (pgmalli.impl.jdbc's write-type)."
  #{clojure.lang.Keyword})

;;; what PostgreSQL takes

(defn- extension-string?
  "Whether t is an extension's type of strings (citext), which pg_catalog's catalog does not
   hold."
  [t]
  (boolean (and (not= "S" (types/category t))
                (when-let [e (and (string? t) (types/entry t))] (and (:extension e) (= :string (:schema e)))))))

(defn- string-type?
  "Whether t is of PostgreSQL's string category, or an extension's type of strings."
  [t]
  (or (= "S" (types/category t)) (extension-string? t)))

(defn answers?
  "Whether accepts? answers for type t: an enum, a type of pg_catalog or an array of one, or an
   extension's type of strings (citext)."
  [t]
  (boolean (or (vector? t) (get-in @types/catalog [:types (or (types/element t) t)]) (string-type? t))))

(def ^:private numbers (set (for [[t e] types/types :when (#{:integer :exact :float} (:eval e))] t)))

(defn number-type? [t] (contains? numbers t))

(defn integer-type? [t] (and (string? t) (= :integer (:eval (types/types t)))))

(defn- converts?
  "Whether PostgreSQL converts a value of type from to type to in context (:assign or :cast), in
   some version the catalog holds (find_coercion_pathway): by pg_cast (an explicit cast only
   under :cast), an array element by element, by I/O conversion to a string type, and under
   :cast from one."
  [to from context]
  (let [a (types/element to) b (types/element from)]
    (boolean (or (= to from)
                 (some (if (= :cast context) #{"i" "a" "e"} #{"i" "a"}) (types/cast-contexts from to))
                 (and a b (converts? a b context))
                 (string-type? to)
                 (and (= :cast context) (string-type? from))))))

(defn scalar-cast?
  "Whether a cast of a value sent as type sent to type t takes no collection: PostgreSQL casts a
   jsonb number or boolean to a number or boolean type and errors at run time on an object or an
   array (to json, jsonb or a string type any document goes); a number or boolean type's input
   refuses an untyped array literal ({1}), which the date, time and uuid types read past its
   braces."
  [t sent]
  (boolean (and (string? t) (not (types/json? t)) (not (string-type? t))
                (or (and (string? sent) (types/json? sent))
                    (and (= :unknown sent) (#{"N" "B"} (types/category t)))))))

(def ^:private compares?
  "Whether PostgreSQL resolves operator = for a value of type t and one of type sent, in some
   version the catalog holds; an extension's type of strings (citext), whose operators the
   catalog does not hold, as text, which it casts to implicitly."
  (memoize
   (fn [t sent]
     (let [os (resolve/overloads :operators "=")
           [t sent] (map #(if (extension-string? %) "text" %) [t sent])]
       (boolean (some (fn [v] (resolve/call :operators "=" [t sent] (for [o os :when (or (nil? (:in o)) (some #{v} (:in o)))] (dissoc o :in))))
                      (:versions @types/catalog)))))))

(defn accepts?
  "Whether a parameter of type sent goes where a value of type t does, in context (:compare,
   :assign or :cast). sent :unknown is a value sent untyped, which PostgreSQL
   types from the context. A type sent that pgmalli does not know (an extension's) is taken: its
   extension's casts are not in the catalog."
  [t sent context]
  (cond (not (or (= :unknown sent) (answers? sent))) true
        (= :compare context) (or (and (string-type? t) (string-type? sent)) (compares? t sent))
        :else (or (= :unknown sent) (converts? t sent context))))

(defn holds?
  "Whether a value sent as type sent is one of type t's own: its type, a number of a type
   PostgreSQL converts to t implicitly, a string of another string type, or one sent untyped
   where t's values are text (a string type, an enum), which PostgreSQL reads as the value
   itself; compared, not where t is a domain over an enum ([:enum name {:domain? true}]), which
   has no = for an untyped value. Stricter than accepts?: a numeric is not one of a real's or a
   double precision's own, a decimal sent to a float column being the mix-up a type would catch."
  ([t sent] (holds? t sent :assign))
  ([t sent context]
   (or (= t sent)
       (and (number-type? t) (number-type? sent) (types/implicit? sent t)
            (not (and (= "numeric" sent) (= :float (:eval (types/types t))))))
       (and (string-type? t) (string-type? sent))
       (and (= :unknown sent)
            (or (string-type? t)
                (and (vector? t) (not (and (= :compare context) (:domain? (get t 2))))))))))
