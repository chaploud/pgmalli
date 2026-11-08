(ns pgmalli-next.impl.accept
  "Which parameter goes where, in two steps: the type the one sending sends a value of a class as, and
   whether PostgreSQL takes a parameter of that type where a value of another goes: compared
   with it (an operator), assigned to it (INSERT, UPDATE), passed to a function (:call), resolved
   with it to one type (:common: coalesce, CASE), read from a JSON document (:json) or cast to it. Types are the names
   pgmalli-next.impl.types keys them by, \"x[]\" for an array of x, [:enum name] for an enum type;
   a caller passes a domain as its base type, a domain over an enum as [:enum name {:domain? true}]. pgmalli-next.accept-test holds both against the one
   sending and PostgreSQL."
  (:require [pgmalli-next.impl.resolve :as resolve]
            [pgmalli-next.impl.types :as types]))

;;; what an application sends

(defn json-key-text
  "The text of key k of a map the one sending writes as jsonb: a keyword's or a symbol's with its
   namespace (:n/a as n/a), as it sends a keyword alone; another's its str."
  [k]
  (cond (keyword? k) (subs (str k) 1) (symbol? k) (str k) :else (str k)))

(defn json-problem
  "Why the one sending cannot write document x (a map or a vector) as jsonb, nil where it can: a
   map of two keys of one text (:a and \"a\"), of which the document would keep one, a nil key,
   a NaN or an infinity, which JSON has not, or nesting deeper than the stack walks."
  [x]
  (letfn [(walk [v]
            (cond (map? v) (or (when (contains? v nil) "a nil key, which JSON has not")
                               (some (fn [[t n]] (when (> n 1) (str "two keys of text " (pr-str t) ", of which the document would keep one")))
                                     (frequencies (map json-key-text (keys v))))
                               (some walk (vals v)))
                  (coll? v) (some walk v)
                  (and (or (double? v) (float? v)) (or (Double/isNaN (double v)) (Double/isInfinite (double v)))) "a NaN or an infinity, which JSON has not"))]
    (try (walk x) (catch StackOverflowError _ "nesting deeper than the stack walks"))))

(defn- array "The class of an array of c." [c] (class (make-array c 0)))
(defn- named "The class named n; nil where the runtime has none." [n] (try (Class/forName n) (catch Throwable _ nil)))
(defn- array-of "The class of an array of c; nil where the runtime makes none." [c] (try (array c) (catch Throwable _ nil)))

(def bytes-class "The class of byte[], which pgjdbc sends as a bytea." (Class/forName "[B"))

(def boxed
  "The boxed class of each primitive one."
  {Long/TYPE Long Integer/TYPE Integer Short/TYPE Short Byte/TYPE Byte Double/TYPE Double Float/TYPE Float
   Boolean/TYPE Boolean Character/TYPE Character})

(def classes
  "[class type schema categories]: the one sending (pgmalli-next.jdbc/install-sending!), the
   type a value of class goes to PostgreSQL as, :unknown for one sent untyped, which PostgreSQL
   types from where it goes; schema is the malli schema of a value of class, nil for a Java
   array. A value goes as its class's row, else as the first row of a superclass or interface of it.
   categories, of a class sent untyped, are the typcategories of the types it goes to as its text
   (reads?): a keyword's a string type's and an enum's (\"S\", \"E\"), a label's name. A date
   and time goes typed, java.time's alone: pgjdbc sends a java.sql value untyped, its text in the
   JVM's time zone."
  (filterv some? [[Long "bigint" :int] [Integer "integer" :int] [Short "smallint" :int] [Byte "smallint" :int]
   [java.math.BigDecimal "numeric" 'decimal?] [clojure.lang.BigInt "numeric" 'integer?] [java.math.BigInteger "numeric" 'integer?]
   [Double "double precision" :double] [Float "real" [:and 'float? [:pg/class "java.lang.Float"]]]
   [Boolean "boolean" :boolean] [String "character varying" :string] [Character "character varying" 'char?]
   [java.util.UUID "uuid" :uuid] [bytes-class "bytea" 'bytes?] [java.time.LocalDate "date" :time/local-date]
   [java.time.LocalTime "time without time zone" :time/local-time] [java.time.OffsetTime "time with time zone" :time/offset-time]
   [java.time.LocalDateTime "timestamp without time zone" :time/local-date-time]
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
   ;; (a java.sql.Time[] goes as a timetz[] of the JVM's offset, which changes with the JVM's time zone)
   (some-> (named "java.sql.Timestamp") array-of (vector "timestamp with time zone[]"))
   ;; as an OffsetDateTime in UTC, which pgjdbc sends as a timestamptz: an Instant pgjdbc refuses alone
   [java.time.Instant "timestamp with time zone" :time/instant]
   [clojure.lang.Keyword :unknown :keyword #{"S" "E"}]
   ;; the one sending's jsonb writes a key as its text (a keyword's namespace kept): no nil key
   [clojure.lang.IPersistentMap "jsonb" [:map-of :some :any]] [clojure.lang.IPersistentVector "jsonb" [:vector :any]]]))

(def values
  "A value of each class of classes with a schema (a byte[] too), by class."
  (into {} (filter (comp some? key))
        {Long 1 Integer (int 1) Short (short 1) Byte (byte 1) java.math.BigDecimal 1M clojure.lang.BigInt 1N
         java.math.BigInteger (biginteger 1) Double 1.0 Float (float 1) Boolean true String "a" Character \a
         java.util.UUID (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
         java.time.LocalDate (java.time.LocalDate/of 2026 1 1) java.time.LocalTime (java.time.LocalTime/of 1 0)
         java.time.OffsetTime (java.time.OffsetTime/of 1 0 0 0 java.time.ZoneOffset/UTC)
         java.time.LocalDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
         java.time.OffsetDateTime (java.time.OffsetDateTime/of 2026 1 1 0 0 0 0 java.time.ZoneOffset/UTC)
         java.time.Instant java.time.Instant/EPOCH
         bytes-class (.getBytes "a") clojure.lang.Keyword :a
         clojure.lang.IPersistentMap {:a 1} clojure.lang.IPersistentVector [1]}))

(defn- array-type-row?
  "Whether row r sends its class as an array type."
  [r]
  (and (string? (second r)) (some? (types/element (second r)))))

(defn row-of
  "The row of rows (classes', json-classes') a value of class c is sent by: its class's, else the
   first of a class it is one of, in their order (java.sql.Date's java.util.Date row); of an array
   of arrays, its component's where that goes as an array type, as pgjdbc sends a long[][] as a
   bigint[] (PostgreSQL's array types tell no dimensions); nil for none."
  [^Class c rows]
  (or (some #(when (= c (first %)) %) rows)
      (some #(when (.isAssignableFrom ^Class (first %) c) %) rows)
      (when-let [e (when (.isArray c) (.getComponentType c))]
        (when (.isArray e)
          (when-let [r (row-of e rows)] (when (array-type-row? r) (assoc r 0 c)))))))

(defn array-class?
  "Whether the one sending sends a Java array of class c as an array type (row-of)."
  [^Class c]
  (boolean (and c (.isArray c) (some-> (row-of c classes) array-type-row?))))

(defn multi-dimensional?
  "Whether a Java array of class c goes as the array type its component goes as (row-of)."
  [^Class c]
  (boolean (when-let [e (when (.isArray c) (.getComponentType c))]
             (and (.isArray e) (some-> (row-of e classes) array-type-row?)))))

(defn element-schema
  "The schema of an element of a Java array of class c the one sending sends: its component class's
   row's (of an array of arrays, its innermost elements'), else, of a java.sql.Date[] or a
   Timestamp[] (pgjdbc's typed date[] and timestamptz[]), a java.util.Date's (inst?), which goes
   nowhere alone; nil for another c."
  [^Class c]
  (when-let [e (some-> c .getComponentType)]
    (if (multi-dimensional? c)
      (element-schema e)
      (or (get (row-of e classes) 2)
          (when (.isAssignableFrom java.util.Date e) 'inst?)))))

(def class-types
  "[class type] of classes, in its order."
  (mapv (fn [[c t]] [c t]) classes))

(def json-classes
  "classes as the one sending's jsonb writes a value of class into a JSON document (data.json, an
   ISO-8601 string of a java.time value), which json_populate_record reads into a column through
   its type's input: a String as a JSON string, whose text every type's input reads (\"*\");
   a BigInt and a BigInteger as the integer data.json writes; a number, a boolean and a JSON
   string of another class as the one sending sends the class.
   A Character and a byte[], which it does not write as a value of theirs, are
   left out."
  (vec (keep (fn [[c :as row]]
               (cond (#{Character bytes-class} c) nil
                     (= String c) [c :unknown :string #{"*"}]
                     (#{clojure.lang.BigInt java.math.BigInteger} c) (assoc row 1 "bigint")
                     :else row))
             classes)))

;;; what PostgreSQL takes

(defn string-type?
  "Whether t is of PostgreSQL's string category, or an extension's type of strings."
  [t]
  (or (= "S" (types/category t)) (types/extension-string? t)))

(defn answers?
  "Whether accepts? answers for type t: an enum, a type of pg_catalog or an array of one, or an
   extension's type of strings (citext)."
  [t]
  (boolean (or (vector? t) (get-in @types/catalog [:types (or (types/element t) t)]) (string-type? t))))

(def ^:private numbers (set (for [[t e] types/types :when (types/number-entry? e)] t)))

(defn number-type? [t] (contains? numbers t))

(defn integer-type? [t] (and (string? t) (= :integer (:value (types/types t)))))

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
           [t sent] (map #(if (types/extension-string? %) "text" %) [t sent])]
       (boolean (some #(resolve/call :operators "=" [t sent] (resolve/of-version os %)) (:versions @types/catalog)))))))

(defn accepts?
  "Whether a parameter of type sent goes where a value of type t does, in context (:compare,
   :assign or :cast). sent :unknown is a value sent untyped, which PostgreSQL
   types from the context. A type sent that pgmalli does not know (an extension's) is taken: its
   extension's casts are not in the catalog."
  [t sent context]
  (cond (not (or (= :unknown sent) (answers? sent))) true
        (= :compare context) (or (and (string-type? t) (string-type? sent)) (compares? t sent))
        :else (or (= :unknown sent) (converts? t sent context))))

(def ^:private any-text
  "The types outside the string category whose input takes any text: bytea's escape format,
   \"char\"'s first byte."
  #{"bytea" "\"char\""})

(defn reads?
  "Whether a value of class c sent untyped goes to type t as its text, in context (:json, a
   value of a JSON document json_populate_record reads, by json-classes' categories): where t's
   category is one of c's row's categories (classes; a string type's \"S\", citext's too, every
   type's \"*\"), t's
   input takes any text (any-text), or answers? does not answer for t (an extension's type); cast,
   as converts? casts a string, also a string's text (a keyword's) to any type and any text to a
   string type. The rest PostgreSQL reads through t's input is left out:
   docs/queries.md#stricter-than-postgresql."
  [t ^Class c context]
  (let [ks (or (get (row-of c (if (= :json context) json-classes classes)) 3) #{})]
    (boolean (or (ks "*")
                 (ks (if (string-type? t) "S" (types/category t)))
                 (and (= :cast context) (or (ks "S") (string-type? t)))
                 (contains? any-text t)
                 (not (answers? t))))))

(def unchanged-assignments
  "[from to] of the casts PostgreSQL makes on assignment alone (pg_cast's castcontext 'a') that
   change no value they do not refuse: jsonb to json, the same document; a wider integer to a
   narrower one, which refuses a value past its range (a number assigned is held to the
   column's range); varchar to \"char\", of one character (the column's own string is held to one)."
  #{["jsonb" "json"] ["bigint" "integer"] ["bigint" "smallint"] ["integer" "smallint"] ["character varying" "\"char\""]})

(defn holds?
  "Whether a value sent as type sent is one of type t's own: its type; one PostgreSQL converts to
   t implicitly (pg_cast's castcontext 'i', an array element by element), assigned or as a
   function's argument (:call), and assigned, by a cast of unchanged-assignments (of arrays,
   element by element); compared, one = resolves with (compares?), through casts to a
   third type too (regclass = regoper as oid = oid, xid = integer as xid = int4; an array of t's
   type alone: anyarray's = takes no other); resolved with t to one type (:common:
   coalesce's, CASE's), one of t's category select_common_type takes; a string of another string
   type; or one sent untyped, which PostgreSQL reads through t's input function, which every type
   has; compared, a function's argument or resolved with t, where t has an = of its own the
   catalog holds (json, point and an extension's type but citext have none here), not where t is a
   domain over an enum ([:enum name {:domain? true}]) compared or a function's argument, which has
   no = for an untyped value (resolved with it, coalesce(dmood, 'sad'), it is one). Under
   :json (a JSON number read through t's input), a number of any number type, but an integer
   type's input reads no fraction."
  [t sent context]
  (or (= t sent)
      (case context
        :json (and (number-type? t) (number-type? sent) (or (not (integer-type? t)) (integer-type? sent)))
        :compare (and (string? t) (string? sent) (compares? t sent))
        :common (and (string? t) (string? sent) (some? (resolve/common-type [t sent])))
        (and (string? t) (string? sent) (or (types/implicit? sent t) (and (= :assign context) (contains? unchanged-assignments (if (types/element t) [(types/element sent) (types/element t)] [sent t]))))))
      ;; resolved with t, two of a type pgmalli's catalog holds no casts of (citext) by text or varchar
      ;; alone, which it converts to and from: coalesce(citext, name) has no type
      (and (string-type? t) (string-type? sent)
           (or (not= :common context) (some? (resolve/common-type [t sent])) (some #{"text" "character varying"} [t sent])))
      (and (= :unknown sent)
           (or (not (#{:compare :call :common} context))
               (string-type? t)
               (if (vector? t) (or (= :common context) (not (:domain? (get t 2)))) (compares? t t))))))

(defn takes?
  "Whether a value of class c, sent as type sent, goes where a value of type t does in context:
   holds? (cast, accepts?), and, sent untyped, its text read there (reads?,
   docs/queries.md#stricter-untyped)."
  [t sent ^Class c context]
  (and (if (= :cast context) (accepts? t sent :cast) (holds? t sent context))
       (or (not= :unknown sent) (reads? t c context))))
