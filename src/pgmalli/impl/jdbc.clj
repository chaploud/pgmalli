(ns pgmalli.impl.jdbc
  "The JDBC side: the transformer decoding driver and string values into the registry's types;
   what PostgreSQL's JDBC driver returns and takes for a column, under the options that say what
   an application's next.jdbc does differently; and the row shapes its result set builders
   return. as-read and pgmalli.honeysql both read columns and key rows here."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.shape :as shape]))

;;; the options

(def options-schema
  "The options as-read and pgmalli.honeysql take, closed: a misspelt key or value is an error
   rather than a check silently weakened."
  [:map {:closed true}
   [:schema {:optional true} :string]
   [:qualified? {:optional true} :boolean]
   [:kebab? {:optional true} :boolean]
   [:lower? {:optional true} :boolean]
   [:nil-columns {:optional true} [:enum :absent]]
   [:time {:optional true} [:enum :inst :instant :local]]
   [:read {:optional true} [:and [:map-of [:or :string [:enum :array :json]] :any]
                            [:map [:array {:optional true} [:enum :vector :sequential]]
                             [:json {:optional true} [:enum :parsed]]]]]
   [:write {:optional true} [:and [:map-of [:or :string [:enum :array]] :any]
                             [:map [:array {:optional true} [:vector {:min 1} [:enum :pg/array :sequential :vector :set]]]]]]
   [:compare {:optional true} [:enum :strict :lenient]]
   [:result {:optional true} [:enum :one]]])

(defn checked!
  "opts (nil is none) when schema takes them; an error naming what it does not, a misspelt key
   with the one meant, otherwise."
  [schema opts]
  (if (m/validate schema (or opts {}))
    opts
    (let [errors (-> (m/explain schema opts) me/with-spell-checking me/humanize)]
      (throw (ex-info (str "pgmalli options: " (pr-str errors)) {:options opts :errors errors})))))

(defn options! [opts] (checked! options-schema opts))

;;; what the driver returns and takes

(defn- pg-type [s] (when (and (vector? s) (map? (second s))) (:pg/type (second s))))

(defn array-column?
  "An array column's schema: [:vector {:pg/type \"text[]\"} :string]."
  [s]
  (and (vector? s) (= :vector (first s)) (some-> (pg-type s) (str/ends-with? "[]"))))

(defn read-type
  "A column's schema (without NULL) as PostgreSQL's JDBC driver returns it through next.jdbc:
   dates and timestamps under :time (inst? by default), an array a java.sql.Array, json and
   jsonb a PGobject, an interval a PGInterval (these :any). :read names what a ReadableColumn
   extension returns instead: a :pg/type's schema, {:array :vector} for arrays as vectors of
   their elements, {:json :parsed} for json as the data the registry shapes."
  [schema {:keys [time read] :or {time :inst} :as opts}]
  (let [driver-object [:any {:pg/type (pg-type schema)}]]
    (or (shape/read-as read schema)
        (cond (array-column? schema) (if-let [kind (:array read)] [kind (read-type (last schema) opts)] driver-object)
              (and (shape/json-types (pg-type schema)) (not= :parsed (:json read))) driver-object
              (= :time/duration (if (vector? schema) (first schema) schema)) driver-object
              :else (shape/read-time time schema)))))

(defn array-type
  "An array argument of elements t: what :write's :array names an application sends
   ([:pg/array :sequential :set]: a typed Java array, a vector, list or set of t); :any without
   it, malli's default registry having nothing for what the driver takes (:pg/array is
   pgmalli's registry's)."
  [t {:keys [write]}]
  (if-let [kinds (:array write)]
    (let [alts (map #(vector % t) kinds)] (if (next alts) (into [:or] alts) (first alts)))
    :any))

(defn write-type
  "A column's schema (without NULL) as an argument the driver takes: :write names what a
   SettableParameter extension sends for a :pg/type; an array column's argument is an array of
   its elements' (array-type); otherwise what the column reads as without :read, which is the
   ReadableColumn side (:time extends both)."
  [schema {:keys [write] :as opts}]
  (or (shape/read-as write schema)
      (when (array-column? schema) (array-type (write-type (last schema) opts) opts))
      (read-type schema (dissoc opts :read))))

;;; rows as next.jdbc builds them

(defn row-key
  "The key next.jdbc gives a column: qualified by its table (the last part of schema.table)
   under :qualified? when the column has one, kebab-cased (:kebab?) or lower-cased (:lower?)
   as its builders do it."
  [table column {:keys [qualified? kebab? lower?]}]
  (let [k (cond kebab? csk/->kebab-case-string lower? str/lower-case :else identity)]
    (if (and qualified? table)
      (keyword (k (last (str/split table #"\." 2))) (k column))
      (keyword (k column)))))

(defn row-entry
  "A row map's entry for a column read as inner: NULL a [:maybe], or the key absent under
   :nil-columns :absent (next.jdbc.optional)."
  ([k inner nullable? opts] (row-entry k nil inner nullable? opts))
  ([k props inner nullable? {:keys [nil-columns]}]
   (let [absent? (and nullable? (= :absent nil-columns))
         props (cond-> props absent? (assoc :optional true))
         s (if (and nullable? (not absent?)) [:maybe inner] inner)]
     (if (empty? props) [k s] [k props s]))))

(defn transformer
  "Decodes JDBC and string values into the registry's types: java.sql.Timestamp and
   java.util.Date -> Instant, java.sql.Date -> LocalDate, an Instant or java.util.Date landing
   in a date or timestamp (without time zone) column is read in :zone, default the JVM's;
   JSON text in a json or jsonb column is parsed."
  ([] (transformer {}))
  ([{:keys [zone] :or {zone (java.time.ZoneId/systemDefault)}}]
   (let [instant (fn [x] (cond (instance? java.sql.Date x) (.toInstant (.atStartOfDay (.toLocalDate ^java.sql.Date x) zone))
                               (instance? java.util.Date x) (.toInstant ^java.util.Date x)
                               :else x))]
     (mt/transformer
      mt/string-transformer
      {:name :pgmalli
       :decoders {:any {:compile (fn [schema _]
                                  (when (shape/json-types (:pg/type (m/properties schema)))
                                    (fn [x] (if (string? x) (json/parse x) x))))}
                  ;; the bounded numbers, from text as :int and :double are
                  :pg/integer (fn [x] (if (string? x) (or (parse-long x) x) x))
                  :pg/smallint (fn [x] (if (string? x) (or (parse-long x) x) x))
                  :pg/numeric (fn [x] (if (string? x) (try (bigdec x) (catch Exception _ x)) x))
                  :time/instant instant
                  :time/local-date (fn [x] (cond (instance? java.sql.Date x) (.toLocalDate ^java.sql.Date x)
                                                 (or (instance? java.util.Date x) (instance? java.time.Instant x)) (.toLocalDate (.atZone ^java.time.Instant (instant x) zone))
                                                 :else x))
                  :time/local-date-time (fn [x] (cond (instance? java.sql.Timestamp x) (.toLocalDateTime ^java.sql.Timestamp x)
                                                      (or (instance? java.util.Date x) (instance? java.time.Instant x)) (java.time.LocalDateTime/ofInstant (instant x) zone)
                                                      :else x))}}))))

(def ^:private builders
  "next.jdbc's map builders -> how they key a row (next.jdbc 1.3)."
  (let [maps {"as-maps" {:qualified? true} "as-unqualified-maps" {}
              "as-lower-maps" {:qualified? true :lower? true} "as-unqualified-lower-maps" {:lower? true}}]
    (merge (update-keys (merge maps {"as-kebab-maps" {:qualified? true :kebab? true} "as-unqualified-kebab-maps" {:kebab? true}})
                        #(symbol "next.jdbc.result-set" %))
           (update-keys (update-vals maps #(assoc % :nil-columns :absent)) #(symbol "next.jdbc.optional" %)))))

(defn read-options
  "The reading options of a next.jdbc result set builder named by its symbol; documented on
   pgmalli.core/read-options."
  [builder]
  (let [b (if (var? builder) (symbol builder) builder)]
    (or (get builders b)
        (throw (ex-info (str b (if (str/includes? (str b) "modified")
                                 " keys rows by functions of yours: give :qualified? and :kebab? as they do"
                                 " is not one of next.jdbc's map builders pgmalli knows")
                             "; it knows " (vec (sort (keys builders))))
                        {:builder b})))))

(defn- data-columns
  "The row map of a generated schema as data (columns gives the malli schema)."
  [registry name]
  (let [s (shape/schema-of registry name)
        props (shape/row-props s)]
    (when-not (and (or (:pg/table props) (:pg/view props)) (not (:closed props)))
      (throw (ex-info (str name " is no table's or view's row schema, which as-read reads (an insert or update schema is what is sent, not read)")
                      {:name name})))
    (shape/row-map s)))

(defn as-read
  "The [:map ...] of a row as a JDBC result builder returns it; the options are documented on
   pgmalli.core/as-read."
  [registry name opts]
  (options! opts)
  (let [m (shape/without-gen (data-columns registry name))
        props (when (map? (second m)) (second m))
        table (or (:pg/table props) (:pg/view props))
        entry (fn [[k p s]]
                (row-entry (row-key table (clojure.core/name k) opts) p (read-type (shape/non-null s) opts)
                           (shape/maybe? s) opts))]
    (into (if props [:map props] [:map]) (map entry (shape/column-entries m)))))
