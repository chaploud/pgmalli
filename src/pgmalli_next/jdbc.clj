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
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types])
  (:import (java.sql Array PreparedStatement ResultSet ResultSetMetaData Types)
           (java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime ZoneOffset)
           (org.postgresql.util PGobject)))

;;; reading

(defn- parse-json
  "A json or jsonb column's text as data; nesting past what the thread's stack holds (PostgreSQL
   stores jsonb thousands deep) an error naming the column, not a StackOverflowError."
  [s label]
  (try (json/read-str s :key-fn keyword)
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
  "A keyword's or a symbol's text as accept/json-key-text writes it, which data.json writes its
   name alone of; a Character as its text; another value as it is."
  [x]
  (cond (or (keyword? x) (symbol? x)) (accept/json-key-text x)
        (char? x) (str x)
        :else x))

(defn- jsonb [x]
  (when-let [why (accept/json-problem x)]
    (error/fail! :pgmalli.error/invalid-options (str "a map or a vector goes as jsonb, which holds no document of " why)
                 {:given (.getName (class x))}))
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (try (json/write-str (walk/postwalk json-text x) :default-write-fn write-temporal)
                    (catch StackOverflowError _
                      (error/fail! :pgmalli.error/invalid-options (str "a map or a vector goes as jsonb, which holds no document of " accept/too-deep)
                                   {:given (.getName (class x))}))))))

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
                clojure.lang.Keyword (fn [k ^PreparedStatement s ^long i] (.setObject s i (accept/sent-text k) Types/OTHER))
                clojure.lang.IPersistentMap (fn [m ^PreparedStatement s ^long i] (.setObject s i (jsonb m)))
                clojure.lang.IPersistentVector (fn [v ^PreparedStatement s ^long i] (.setObject s i (jsonb v)))}
               #(with-meta % {::sending true})))

(defn- dispatched
  "The function a value of class c is sent by under SettableParameter's impls, as a protocol
   dispatches: the class, its superclasses, then the most specific of its interfaces."
  [impls ^Class c]
  (let [impl #(:set-parameter (get impls %))]
    (or (some impl (take-while #(and % (not= Object %)) (iterate #(.getSuperclass ^Class %) c)))
        (some->> (filter impl (disj (supers c) Object))
                 seq
                 (reduce (fn [^Class a ^Class b] (if (.isAssignableFrom a b) b a)))
                 impl)
        (impl Object))))

(defn install-sending!
  "Extends next.jdbc's SettableParameter, for the whole JVM, for the classes
   docs/reading-rows.md#the-one-reading-and-sending lists. Call it once, before sending arguments.
   Throws where another extension of it already holds a class pgmalli sends (accept/classes'),
   which would go otherwise than pgmalli types it: next.jdbc.date-time's, an application's own;
   one made after it is not seen."
  []
  (let [impls (:impls prepare/SettableParameter)
        ours? #(::sending (meta %))
        ;; next.jdbc's own, which an application's extension of Object replaces
        next-jdbc? #(str/starts-with? (.getName (class %)) "next.jdbc.prepare$")
        ;; as values will be sent once this extension is made: a map and a vector by their classes
        after (merge impls (update-vals sending #(hash-map :set-parameter %)))
        sent (concat (filter class? (map first accept/classes))
                     (map class [{} (zipmap (range 9) (range 9)) (sorted-map) [] (subvec [1] 0) (first {1 1})]))
        others (sort-by str (distinct (concat (for [c (keys sending)
                                                    :let [f (get-in impls [c :set-parameter])]
                                                    :when (and f (not (ours? f)))]
                                                c)
                                              (for [c sent
                                                    :let [f (dispatched after c)]
                                                    :when (and f (not (next-jdbc? f)) (not (ours? f)))]
                                                c))))]
    (when (seq others)
      (error/fail! :pgmalli.error/other-sending
                   (str "install-sending!: next.jdbc's SettableParameter is already extended for "
                        (str/join ", " (map #(.getName ^Class %) others))
                        ", which pgmalli types as pgjdbc's setObject or install-sending! sends them; drop that extension"
                        " (next.jdbc.date-time extends Instant, LocalDate and LocalDateTime when it loads)")
                   {:given (mapv #(.getName ^Class %) others)})))
  (doseq [[c f] sending]
    (extend c prepare/SettableParameter {:set-parameter f})))
