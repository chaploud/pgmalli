(ns pgmalli-next.jdbc
  "The one reading and sending pgmalli types rows and arguments as (docs/reading-rows.md):
   builder, the :builder-fn your application passes next.jdbc, and install-sending!, which
   extends next.jdbc's SettableParameter for the classes pgmalli sends otherwise than pgjdbc.
   Your application adds next.jdbc and pgjdbc."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as rs]
            [pgmalli-next.impl.shape :as shape])
  (:import (java.sql Array PreparedStatement ResultSet ResultSetMetaData Types)
           (java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneOffset)
           (org.postgresql.util PGobject)))

;;; reading

(defn- parse-json [s] (json/read-str s :key-fn keyword))

(defn- instant
  "A timestamptz as an Instant; pgjdbc reads infinity as OffsetDateTime's MAX and MIN, which no
   Instant is."
  [^OffsetDateTime v]
  (condp = v
    OffsetDateTime/MAX Instant/MAX
    OffsetDateTime/MIN Instant/MIN
    (.toInstant v)))

(defn- schema-and-name
  "[schema name] of each type registry key k may be (shape/schema-key's): a string key's \"/\" may
   be either name's."
  [k]
  (cond (and (qualified-keyword? k) (str/starts-with? (namespace k) "pg.")) [[(subs (namespace k) 3) (name k)]]
        (and (string? k) (str/starts-with? k "pg."))
        (let [x (subs k 3)]
          (for [i (range (count x)) :when (= \/ (.charAt ^String x i))
                :let [sn [(subs x 0 i) (subs x (inc i))]]
                :when (= k (apply shape/schema-key sn))]
            sn))))

(defn- enum-names
  "The names pgjdbc gives the enum types of registry: the type's name where the session's
   search_path finds it, \"schema\".\"name\" otherwise (a quote in a name as it is)."
  [registry]
  ;; ponytail: an unqualified name is taken for the enum whichever schema holds it; a type of that
  ;; name the search_path finds first in another schema reads as the enum's labels
  (set (for [[k s] (shape/schemas-of registry)
             :when (and (vector? s) (= :enum (first s)))
             [schema n] (schema-and-name k)
             x [n (str "\"" schema "\".\"" n "\"")]]
         x)))

(declare array-reader)

(defn- reader
  "(fn [^ResultSet rs i]) reading column i of type t (as pgjdbc names it) as the one reading reads
   it, an element of an array at index 2 of its result set; enums the names of enum types."
  [t enums]
  (case t
    "timestamptz" (fn [^ResultSet rs i] (some-> (.getObject rs (int i) OffsetDateTime) instant))
    "timestamp" (fn [^ResultSet rs i] (.getObject rs (int i) LocalDateTime))
    "date" (fn [^ResultSet rs i] (.getObject rs (int i) LocalDate))
    "time" (fn [^ResultSet rs i] (.getObject rs (int i) LocalTime))
    "timetz" (fn [^ResultSet rs i] (.getObject rs (int i) OffsetTime))
    ("json" "jsonb") (fn [^ResultSet rs i] (let [v (.getObject rs (int i))]
                                             (some-> (if (instance? PGobject v) (.getValue ^PGobject v) v) parse-json)))
    (cond (contains? enums t) (fn [^ResultSet rs i] (some-> (.getString rs (int i)) keyword))
          :else (fn [^ResultSet rs i]
                  (let [v (.getObject rs (int i))]
                    (if (instance? Array v) ((array-reader enums) v) v))))))

(defn- array-reader
  "(fn [java.sql.Array]) of its elements as a vector, each read as a column of the element type,
   an element array (a multi-dimensional array's) as a vector likewise."
  [enums]
  (fn read-array [^Array a]
    (with-open [r (.getResultSet a)]
      (let [read (reader (.getColumnTypeName (.getMetaData r) 2) enums)]
        (loop [acc (transient [])]
          (if (.next r)
            (recur (conj! acc (read r 2)))
            (persistent! acc)))))))

(defn builder
  "The :builder-fn pgmalli types rows as: next.jdbc's as-unqualified-maps, each column read as
   docs/reading-rows.md says, an enum of registry (pgmalli-next.core/registry's) as a keyword.

   (jdbc/execute! ds (sql/format q) {:builder-fn (pgmalli-next.jdbc/builder registry)})"
  [registry]
  (let [enums (enum-names registry)
        ;; [metadata readers] of the last result set: its columns are read alike in every row
        last-read (volatile! nil)
        readers (fn [^ResultSetMetaData md]
                  (let [[m rd] @last-read]
                    (if (identical? m md)
                      rd
                      (let [rd (mapv #(reader (.getColumnTypeName md (int %)) enums) (range 1 (inc (.getColumnCount md))))]
                        (vreset! last-read [md rd])
                        rd))))]
    (vary-meta (rs/builder-adapter rs/as-unqualified-maps
                                   (fn [builder ^ResultSet r i] ((nth (readers (:rsmeta builder)) (dec i)) r i)))
               assoc ::builder true)))

;;; sending: pgjdbc's setObject, which sends java.time's types typed (a LocalDateTime as a
;;; timestamp, a LocalDate as a date, an OffsetDateTime as a timestamptz), an Instant as an
;;; OffsetDateTime in UTC

;; data.json writes an Instant and a UUID as JSON strings; a LocalDate, a LocalDateTime, a
;; LocalTime, an OffsetDateTime and an OffsetTime go as their ISO-8601 text here
(defn- write-temporal [x ^java.io.Writer out _options]
  (if (instance? java.time.temporal.Temporal x)
    (json/write (str x) out)
    (throw (Exception. (str "Don't know how to write JSON of " (class x))))))

(defn- jsonb [x] (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-str x :default-write-fn write-temporal))))

(defn- offset-date-time
  "An Instant as an OffsetDateTime in UTC; infinity as OffsetDateTime's MAX and MIN, which pgjdbc
   sends as infinity (no OffsetDateTime in UTC holds Instant's MAX or MIN)."
  [^Instant v]
  (condp = v
    Instant/MAX OffsetDateTime/MAX
    Instant/MIN OffsetDateTime/MIN
    (OffsetDateTime/ofInstant v ZoneOffset/UTC)))

(defn install-sending!
  "Extends next.jdbc's SettableParameter for the whole JVM: an Instant goes as a timestamptz, a
   keyword untyped as its text (:ns/a as ns/a), a map and a vector as jsonb. Call it once,
   before sending arguments."
  []
  (extend-protocol prepare/SettableParameter
    Instant
    (set-parameter [v ^PreparedStatement s ^long i] (.setObject s i (offset-date-time v)))
    clojure.lang.Keyword
    (set-parameter [k ^PreparedStatement s ^long i] (.setObject s i (subs (str k) 1) Types/OTHER))
    clojure.lang.IPersistentMap
    (set-parameter [m ^PreparedStatement s ^long i] (.setObject s i (jsonb m)))
    clojure.lang.IPersistentVector
    (set-parameter [v ^PreparedStatement s ^long i] (.setObject s i (jsonb v)))))
