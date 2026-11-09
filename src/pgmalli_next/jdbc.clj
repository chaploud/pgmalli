(ns pgmalli-next.jdbc
  "The one reading and sending pgmalli types rows and arguments as (docs/reading-rows.md):
   builder, the :builder-fn your application passes next.jdbc, and sent, the parameters of a
   sqlvec as pgmalli sends them where pgjdbc sends them otherwise. Your application adds
   next.jdbc and pgjdbc."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            next.jdbc.types
            [next.jdbc.result-set :as rs]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.json :as json]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types])
  (:import (java.sql Array ResultSet ResultSetMetaData Types)
           (java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneOffset)
           (org.postgresql.util PGInterval PGobject)))

;;; reading

(defn- parse-json
  "A json or jsonb column's text as data; nesting past what the thread's stack holds (PostgreSQL
   stores jsonb thousands deep) an error naming the column, not a StackOverflowError."
  [s label]
  (try (json/parse s true)
       (catch StackOverflowError _
         (error/fail! :pgmalli.error/fault (str "column " label " holds a JSON document nested too deep to read") {:given label}))))

(defn- instant
  "A timestamptz as an Instant; pgjdbc reads infinity as OffsetDateTime's MAX and MIN, which no
   Instant is."
  [^OffsetDateTime v]
  (condp = v
    OffsetDateTime/MAX Instant/MAX
    OffsetDateTime/MIN Instant/MIN
    (.toInstant v)))

(defn- interval
  "An interval as java.time holds it whole: a Period of years, months and days alone, a Duration
   of a time alone (its fraction and sign too); one of both (1 day 02:00, whose day is no 24
   hours where it is added across a change of offset) pgjdbc's PGInterval."
  [^PGInterval v]
  (let [y (.getYears v) mo (.getMonths v) d (.getDays v)
        time? (or (not (zero? (.getHours v))) (not (zero? (.getMinutes v))) (not (zero? (.getWholeSeconds v))) (not (zero? (.getMicroSeconds v))))]
    (cond (and (zero? y) (zero? mo) (zero? d)) (-> (java.time.Duration/ofHours (.getHours v)) (.plusMinutes (.getMinutes v))
                                                    (.plusSeconds (.getWholeSeconds v)) (.plusNanos (* 1000 (.getMicroSeconds v))))
          (not time?) (java.time.Period/of y mo d)
          :else v)))

(def ^:private by-kind
  "(fn [^ResultSet rs i]) reading a column of a type of each schema kind (types/types') as the one
   reading reads it, through pgjdbc's java.time reading."
  {:time/instant (fn [^ResultSet rs i] (some-> (.getObject rs (int i) OffsetDateTime) instant))
   :time/local-date-time (fn [^ResultSet rs i] (.getObject rs (int i) LocalDateTime))
   :time/local-date (fn [^ResultSet rs i] (.getObject rs (int i) LocalDate))
   :time/local-time (fn [^ResultSet rs i] (.getObject rs (int i) LocalTime))
   :time/offset-time (fn [^ResultSet rs i] (.getObject rs (int i) OffsetTime))
   :time/duration (fn [^ResultSet rs i] (some-> (.getObject rs (int i)) interval))})

(declare read-array)

(defn- reader
  "(fn [^ResultSet rs i]) reading column i, of type t and java.sql.Types type, as pgjdbc names them,
   as the one reading reads it (an array's element is column 2 of its result set); enums the
   names of enum types, which pgjdbc returns as a VARCHAR; label the column's, which an error names."
  [t sql-type enums label]
  ;; a type pgjdbc names "schema"."name" is no pg_catalog type
  (let [entry (when-not (str/starts-with? t "\"") (types/entry t))]
    ;; an enum of the name of a pg_catalog type (date) on the search_path is named as that type, a
    ;; VARCHAR, as no json or date or time type is
    ;; a composite type is a STRUCT whatever its name: of one named as a pg_catalog type (a date of
    ;; the search_path's schema), pgjdbc's getObject reads the text as that type's
    (cond (= Types/STRUCT sql-type) (fn [^ResultSet rs i] (when-some [s (.getString rs (int i))] (doto (PGobject.) (.setType t) (.setValue s))))
          (= Types/VARCHAR sql-type) (if (contains? enums t)
                                       (fn [^ResultSet rs i] (some-> (.getString rs (int i)) keyword))
                                       (fn [^ResultSet rs i] (.getString rs (int i))))
          (types/json? (:name entry)) (fn [^ResultSet rs i] (let [v (.getObject rs (int i))]
                                                             (some-> (if (instance? PGobject v) (.getValue ^PGobject v) v) (parse-json label))))
          (by-kind (:schema entry)) (by-kind (:schema entry))
          (= :text (:read entry)) (fn [^ResultSet rs i] (when-some [s (.getString rs (int i))] (doto (PGobject.) (.setType (:name entry)) (.setValue s))))
          :else (fn [^ResultSet rs i]
                  (let [v (.getObject rs (int i))]
                    (if (instance? Array v) (read-array v enums label) v))))))

(defn- readers
  "The reader of each column of a result set's metadata, labelled as label says of column i (an
   array's elements as the array's column)."
  [^ResultSetMetaData md enums label]
  (mapv #(reader (.getColumnTypeName md (int %)) (.getColumnType md (int %)) enums (label %)) (range 1 (inc (.getColumnCount md)))))

(defn- read-array
  "The elements of a java.sql.Array as a vector, each read as a column of the element type, an
   element array (a multi-dimensional array's) as a vector likewise."
  [^Array a enums label]
  (with-open [r (.getResultSet a)]
    (let [read (nth (readers (.getMetaData r) enums (constantly label)) 1)]
      (loop [acc (transient [])]
        (if (.next r)
          (recur (conj! acc (read r 2)))
          (persistent! acc))))))

(defn builder
  "The :builder-fn pgmalli types rows as: next.jdbc's as-unqualified-maps, each column read as
   docs/reading-rows.md says, an enum of registry (pgmalli-next.core/registry's) as a keyword.
   registry may be the generated data itself, its enums read alike, which an application that
   reads rows alone gives without loading pgmalli-next.core and the analysis.

   (jdbc/execute! ds (sql/format q) {:builder-fn (pgmalli-next.jdbc/builder registry)})
   (pgmalli-next.jdbc/builder (clojure.edn/read-string (slurp \"api/pgmalli-next.edn\")))"
  [registry]
  (let [generated? (and (map? registry) (contains? registry :registry) (contains? registry :schemas))
        enums (shape/enum-type-names (if generated? (:registry registry) registry))]
    (with-meta (fn [^ResultSet rs opts]
                 (let [md (.getMetaData rs)
                       rd (readers md enums #(.getColumnLabel md (int %)))]
                   ((rs/builder-adapter rs/as-unqualified-maps (fn [_ r i] ((nth rd (dec i)) r i))) rs opts)))
      ;; the enums pgmalli-next.impl.jdbc/builder! holds against the registry rows are typed with
      {::builder enums})))

;;; sending: pgjdbc's setObject, which sends java.time's types typed (a LocalDateTime as a
;;; timestamp, a LocalDate as a date, an OffsetDateTime as a timestamptz), but what sent changes

(defn- json-text
  "x as JSON holds it: a keyword's or a symbol's text as accept/json-key-text writes it (:n/a as
   n/a), a Character's, java.time's and a UUID's text (an Instant's to the nanosecond, as its
   ISO-8601), a Ratio a double; a value JSON has no form for an error."
  [x]
  (cond (or (keyword? x) (symbol? x)) (accept/json-key-text x)
        (or (char? x) (instance? java.time.temporal.Temporal x) (uuid? x)) (str x)
        (ratio? x) (double x)
        (instance? clojure.lang.BigInt x) (biginteger x)
        (or (nil? x) (boolean? x) (number? x) (string? x) (map? x) (map-entry? x) (coll? x)) x
        :else (error/fail! :pgmalli.error/invalid-options
                           (str "a jsonb document holds no " (.getName (class x)) ": send its text, or the value as its own argument")
                           {:given (.getName (class x))})))

(defn- jsonb-object
  "Document x as a jsonb PGobject; one JSON cannot hold an error (docs/queries.md#stricter-json-keys)."
  [x]
  (when-let [why (accept/json-problem x)]
    (error/fail! :pgmalli.error/invalid-options (str "jsonb holds no document of " why)
                 {:given (.getName (class x))}))
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (try (json/write (walk/postwalk json-text x))
                    (catch StackOverflowError _
                      (error/fail! :pgmalli.error/invalid-options (str "jsonb holds no document of " accept/too-deep)
                                   {:given (.getName (class x))}))))))

(defn- offset-date-time
  "An Instant as an OffsetDateTime in UTC; infinity as OffsetDateTime's MAX and MIN, which pgjdbc
   sends as infinity (no OffsetDateTime in UTC holds Instant's MAX or MIN)."
  [^Instant v]
  (condp = v
    Instant/MAX OffsetDateTime/MAX
    Instant/MIN OffsetDateTime/MIN
    (OffsetDateTime/ofInstant v ZoneOffset/UTC)))

(defn jsonb
  "Sequential x as a vector marked a jsonb document, which sent sends as jsonb, not as an array
   as it does a vector, and an argument of jsonb takes; a map, which goes as jsonb, as it is.

   (pgmalli-next.jdbc/jsonb [{:id 1} {:id 2}])"
  [x]
  (accept/jsonb x))

(defn- untyped
  "x's text sent untyped (java.sql.Types/OTHER), which PostgreSQL types where it goes."
  [text]
  (next.jdbc.types/as-other text))

(defn- interval-object [v]
  (doto (PGobject.) (.setType "interval") (.setValue (str v))))

(defn- infinite-text
  "infinity or -infinity for the MAX or MIN of a date and time the one reading reads them as, nil
   otherwise."
  [x]
  (condp contains? x
    #{Instant/MAX LocalDate/MAX LocalDateTime/MAX OffsetDateTime/MAX} "infinity"
    #{Instant/MIN LocalDate/MIN LocalDateTime/MIN OffsetDateTime/MIN} "-infinity"
    nil))

(declare array-text)

(defn- element-text
  "Element x of an array literal: NULL, an array, or its text quoted."
  [x]
  (cond (nil? x) "NULL"
        (or (sequential? x) (set? x)) (array-text x)
        :else
        (let [t (cond (keyword? x) (accept/sent-text x)
                      ;; a domain's element as pgjdbc reads it
                      (instance? PGobject x) (.getValue ^PGobject x)
                      (infinite-text x) (infinite-text x)
                      (map? x) (.getValue ^PGobject (jsonb-object x))
                      (bytes? x) (str "\\x" (apply str (map #(format "%02x" (bit-and 0xff %)) x)))
                      (decimal? x) (.toPlainString ^java.math.BigDecimal x)
                      (ratio? x) (str (double x))
                      (or (string? x) (number? x) (boolean? x) (uuid? x) (char? x)
                          (instance? java.time.temporal.Temporal x) (instance? java.time.temporal.TemporalAmount x)) (str x)
                      :else (error/fail! :pgmalli.error/invalid-options
                                         (str "a vector or a set goes as an array, which holds no " (.getName (class x)) ": send its text")
                                         {:given (.getName (class x))}))]
          (str \" (str/replace t #"[\"\\]" "\\\\$0") \"))))

(defn- array-text
  "The array literal of sequential or set xs ('{\"1\",NULL}'), a set's elements in its order."
  [xs]
  (str "{" (str/join "," (map element-text xs)) "}"))

(defn- sent-value [v]
  (cond (instance? Instant v) (offset-date-time v)
        (keyword? v) (untyped (accept/sent-text v))
        (or (instance? java.time.Duration v) (instance? java.time.Period v)) (interval-object v)
        (or (map? v) (accept/jsonb? v)) (jsonb-object v)
        (or (sequential? v) (set? v)) (untyped (try (array-text v)
                                                (catch StackOverflowError _
                                                  (error/fail! :pgmalli.error/invalid-options (str "an array nested " accept/too-deep)
                                                               {:given (.getName (class v))}))))
        :else v))

(defn sent
  "sqlvec, [sql & params] (sql/format's), with each parameter as the one sending sends it, as
   docs/reading-rows.md#the-one-reading-and-sending lists: a vector, a seq (a set) an array, untyped,
   which PostgreSQL types where it goes; a map, and a vector jsonb marks, jsonb; a keyword its text (an enum's label);
   an Instant a timestamptz; a Duration and a Period an interval; another value as pgjdbc's
   setObject sends it.

   (jdbc/execute! ds (pgmalli-next.jdbc/sent (sql/format q {:params args})) opts)"
  [[sql & params]]
  (into [sql] (map sent-value) params))
