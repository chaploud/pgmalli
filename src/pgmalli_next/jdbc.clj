(ns pgmalli-next.jdbc
  "The one reading and sending pgmalli types rows and arguments as (docs/reading-rows.md):
   builder, the :builder-fn your application passes next.jdbc, and install-sending!, which
   extends next.jdbc's SettableParameter for the classes pgmalli sends otherwise than pgjdbc.
   Your application adds next.jdbc and pgjdbc."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as rs]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.resolve :as resolve]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types])
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

(def ^:private by-kind
  "(fn [^ResultSet rs i]) reading a column of a type of each schema kind (types/types') as the one
   reading reads it, through pgjdbc's java.time reading."
  {:time/instant (fn [^ResultSet rs i] (some-> (.getObject rs (int i) OffsetDateTime) instant))
   :time/local-date-time (fn [^ResultSet rs i] (.getObject rs (int i) LocalDateTime))
   :time/local-date (fn [^ResultSet rs i] (.getObject rs (int i) LocalDate))
   :time/local-time (fn [^ResultSet rs i] (.getObject rs (int i) LocalTime))
   :time/offset-time (fn [^ResultSet rs i] (.getObject rs (int i) OffsetTime))})

(declare read-array)

(defn- reader
  "(fn [^ResultSet rs i]) reading column i, of type t and java.sql.Types type, as pgjdbc names them,
   as the one reading reads it (an array's element is column 2 of its result set); enums the
   names of enum types, which pgjdbc returns as a VARCHAR."
  [t sql-type enums]
  ;; a type pgjdbc names "schema"."name" is no pg_catalog type
  (let [entry (when-not (str/starts-with? t "\"") (types/entry t))]
    (cond (types/json? (:name entry)) (fn [^ResultSet rs i] (let [v (.getObject rs (int i))]
                                                             (some-> (if (instance? PGobject v) (.getValue ^PGobject v) v) parse-json)))
          (by-kind (:schema entry)) (by-kind (:schema entry))
          (and (= Types/VARCHAR sql-type) (contains? enums t)) (fn [^ResultSet rs i] (some-> (.getString rs (int i)) keyword))
          :else (fn [^ResultSet rs i]
                  (let [v (.getObject rs (int i))]
                    (if (instance? Array v) (read-array v enums) v))))))

(defn- readers
  "The reader of each column of a result set's metadata."
  [^ResultSetMetaData md enums]
  (mapv #(reader (.getColumnTypeName md (int %)) (.getColumnType md (int %)) enums) (range 1 (inc (.getColumnCount md)))))

(defn- read-array
  "The elements of a java.sql.Array as a vector, each read as a column of the element type, an
   element array (a multi-dimensional array's) as a vector likewise."
  [^Array a enums]
  (with-open [r (.getResultSet a)]
    (let [read (nth (readers (.getMetaData r) enums) 1)]
      (loop [acc (transient [])]
        (if (.next r)
          (recur (conj! acc (read r 2)))
          (persistent! acc))))))

(defn builder
  "The :builder-fn pgmalli types rows as: next.jdbc's as-unqualified-maps, each column read as
   docs/reading-rows.md says, an enum of registry (pgmalli-next.core/registry's) as a keyword.

   (jdbc/execute! ds (sql/format q) {:builder-fn (pgmalli-next.jdbc/builder registry)})"
  [registry]
  (let [enums (shape/enum-type-names registry resolve/type-name?)]
    (with-meta (fn [^ResultSet rs opts]
                 (let [rd (readers (.getMetaData rs) enums)]
                   ((rs/builder-adapter rs/as-unqualified-maps (fn [_ r i] ((nth rd (dec i)) r i))) rs opts)))
      ;; the enums pgmalli-next.impl.jdbc/builder! holds against the registry rows are typed with
      {::builder enums})))

;;; sending: pgjdbc's setObject, which sends java.time's types typed (a LocalDateTime as a
;;; timestamp, a LocalDate as a date, an OffsetDateTime as a timestamptz), an Instant as an
;;; OffsetDateTime in UTC

;; data.json writes an Instant and a UUID as JSON strings; a LocalDate, a LocalDateTime, a
;; LocalTime, an OffsetDateTime and an OffsetTime go as their ISO-8601 text here
(defn- write-temporal [x ^java.io.Writer out _options]
  (if (instance? java.time.temporal.Temporal x)
    (json/write (str x) out)
    (error/fail! :pgmalli.error/invalid-options
                 (str "a map or vector goes as jsonb, which holds no " (.getName (class x)) ": send its text, or the value as its own argument")
                 {:given (.getName (class x))})))

(defn- json-text
  "A keyword's text, its namespace kept (:n/a as n/a), as the one sending sends a keyword alone;
   data.json writes its name alone."
  [x]
  (if (keyword? x) (subs (str x) 1) x))

(defn- jsonb [x]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str (walk/postwalk json-text x) :default-write-fn write-temporal))))

(defn- offset-date-time
  "An Instant as an OffsetDateTime in UTC; infinity as OffsetDateTime's MAX and MIN, which pgjdbc
   sends as infinity (no OffsetDateTime in UTC holds Instant's MAX or MIN)."
  [^Instant v]
  (condp = v
    Instant/MAX OffsetDateTime/MAX
    Instant/MIN OffsetDateTime/MIN
    (OffsetDateTime/ofInstant v ZoneOffset/UTC)))

(def ^:private sending
  "The extension install-sending! makes: {class set-parameter}, each flagged as pgmalli's (a
   namespace loaded again makes other functions)."
  (update-vals {Instant (fn [v ^PreparedStatement s ^long i] (.setObject s i (offset-date-time v)))
                clojure.lang.Keyword (fn [k ^PreparedStatement s ^long i] (.setObject s i (subs (str k) 1) Types/OTHER))
                clojure.lang.IPersistentMap (fn [m ^PreparedStatement s ^long i] (.setObject s i (jsonb m)))
                clojure.lang.IPersistentVector (fn [v ^PreparedStatement s ^long i] (.setObject s i (jsonb v)))}
               #(with-meta % {::sending true})))

(defn install-sending!
  "Extends next.jdbc's SettableParameter, for the whole JVM, for the classes
   docs/reading-rows.md#the-one-reading-and-sending lists. Call it once, before sending arguments.
   Throws where another extension of it holds a class pgmalli sends (accept/classes'), which
   would go otherwise than pgmalli types it: next.jdbc.date-time's, an application's own."
  []
  (let [sent (into (set (keys sending)) (filter class?) (map first accept/classes))
        others (sort-by str (for [[c {f :set-parameter}] (:impls prepare/SettableParameter)
                                  :when (and (sent c) (not (::sending (meta f))))]
                              c))]
    (when (seq others)
      (error/fail! :pgmalli.error/other-sending
                   (str "install-sending!: next.jdbc's SettableParameter is already extended for "
                        (str/join ", " (map #(.getName ^Class %) others))
                        ", which pgmalli types as pgjdbc's setObject or install-sending! sends them; drop that extension"
                        " (next.jdbc.date-time extends Instant, LocalDate and LocalDateTime when it loads)")
                   {:given (mapv #(.getName ^Class %) others)})))
  (doseq [[c f] sending]
    (extend c prepare/SettableParameter {:set-parameter f})))
