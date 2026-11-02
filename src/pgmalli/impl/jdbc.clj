(ns pgmalli.impl.jdbc
  "The JDBC side: the transformer decoding driver and string values into the registry's types;
   what PostgreSQL's JDBC driver returns and takes for a column, under the options that say what
   an application's next.jdbc does differently; and the row shapes its result set builders
   return. as-read and pgmalli.honeysql both read columns and key rows here."
  (:require [camel-snake-kebab.core :as csk]
            [clojure.string :as str]
            [clojure.walk]
            [malli.core :as m]
            [malli.error :as me]
            [malli.transform :as mt]
            [pgmalli.impl.accept :as accept]
            [pgmalli.impl.error :as error]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.shape :as shape]
            [pgmalli.impl.types :as types]))

;;; the options

(def read-options-schema
  "The options as-read takes, closed: a misspelt key or value is an error rather than a check
   silently weakened."
  [:map {:closed true}
   [:qualified? {:optional true} :boolean]
   [:kebab? {:optional true} :boolean]
   [:lower? {:optional true} :boolean]
   [:nil-columns {:optional true} [:enum :absent]]
   [:time {:optional true} [:enum :inst :instant :local]]
   [:read {:optional true} [:and [:map-of [:or :string [:enum :array :json]] :any]
                            [:map [:array {:optional true} [:enum :vector :sequential]]
                             [:json {:optional true} [:enum :parsed]]]]]])

(def options-schema
  "The options pgmalli.honeysql takes: as-read's, and those of docs/queries.md#options."
  (into read-options-schema
        [[:schema {:optional true} :string]
         [:write {:optional true} [:sequential [:tuple :any [:or :string [:= :unknown]]]]]
         [:compare {:optional true} [:enum :strict :lenient]]
         [:result {:optional true} [:enum :one]]
         [:return-keys {:optional true} [:or :boolean [:vector {:min 1} [:or :string :keyword]]]]
         [:format {:optional true} :map]
         [:accept {:optional true} [:enum :native :postgresql]]]))

(defn checked!
  "opts (nil is none) when schema takes them; an error naming what it does not, a misspelt key
   with the one meant, otherwise. kind and label say whose they are (options by default).
   A config's :db, which may hold a password, is left out of the error, and so are the values of
   keys schema does not take (a misspelt :db is one)."
  ([schema opts] (checked! schema opts :pgmalli.error/invalid-options "pgmalli options"))
  ([schema opts kind label]
   (if (m/validate schema (or opts {}))
     opts
     (let [errors (-> (m/explain schema opts) me/with-spell-checking me/humanize)
           known (when (= :map (m/type schema)) (map first (m/children (m/schema schema))))
           ;; malli's spelling check leaves out short keys (:dbb for :db)
           stray (when (map? opts) (remove (set known) (keys opts)))
           suggest (some #(error/suggest % known) stray)]
       (error/fail! kind (str label ": " (pr-str errors)
                              (cond (str/includes? (pr-str errors) "should be spelled") ""
                                    suggest (error/did-you-mean suggest)
                                    (and (seq stray) (seq known)) (str "; it takes " (str/join ", " known))))
                    (cond-> {:given (cond-> opts
                                      (and (map? opts) known) (select-keys known)
                                      (map? opts) (dissoc :db))
                             :errors errors}
                      known (assoc :known (vec known))))))))

;; compiled once: malli keeps a schema's validator, and these are checked on every call
(def ^:private options (delay (m/schema options-schema)))
(def ^:private read-options* (delay (m/schema read-options-schema)))

(defn- element-row?
  "A row of :write whose schema holds :pgmalli/element: a collection of an array's elements."
  [[s]]
  (boolean (some #{:pgmalli/element} (tree-seq coll? seq s))))

(def ^:private migration
  "How the :write and :stringtype of pgmalli 0.3 read as :write's rows."
  (str "; :write is a vector of [schema type] rows, pgjdbc's (h/pgjdbc-write) by default: {:stringtype :unspecified} is"
       " {:write (h/pgjdbc-write {:stringtype :unspecified})}, a keyword an extension sends untyped a row [:keyword :unknown],"
       " :array's kinds rows [[kind :pgmalli/element] :unknown], {\"jsonb\" s} a row [s \"jsonb\"]"
       " (docs/queries.md#what-your-application-sends)"))

(defn options!
  "opts when pgmalli.honeysql takes them: their keys and values, each type :write names one
   types knows (a name or an alias), a row's schema one registry (a malli registry) reads; an
   error naming what it does not, with the one meant or, for :write and :stringtype of pgmalli
   0.3, what they are now."
  ([opts] (options! opts nil))
  ([opts registry]
   (let [write (:write opts)]
     (when (or (contains? opts :stringtype) (map? write) (and (vector? write) (seq write) (not (vector? (first write)))))
       (error/fail! :pgmalli.error/invalid-options
                    (str "pgmalli options: " (pr-str (select-keys opts [:write :stringtype])) " is no longer read" migration)
                    {:given (select-keys opts [:write :stringtype])})))
   (checked! @options opts)
   (doseq [[s t :as row] (:write opts)]
     (when-not (or (= :unknown t) (and (not (element-row? row)) (types/entry t)))
       (let [known (mapcat (fn [[n e]] (cons n (:aliases e))) types/types)]
         (error/fail! :pgmalli.error/invalid-options
                      (str "pgmalli options: :write sends " (pr-str s) " as " (pr-str t)
                           (if (element-row? row)
                             ", but a collection row goes untyped (:unknown): PostgreSQL types the array from where it goes"
                             (str ", which is no type pgmalli knows (docs/types.md)" (error/did-you-mean (some-> (error/suggest t known) pr-str)))))
                      {:given row})))
     (when registry
       (try (m/schema (clojure.walk/postwalk-replace {:pgmalli/element :any} s) {:registry registry})
            (catch Exception e
              (error/fail! :pgmalli.error/invalid-options
                           (str "pgmalli options: :write sends " (pr-str s) ", which is no malli schema the registry reads: " (ex-message e))
                           {:given row})))))
   opts))

(defn write-rows
  "The rows of :write (accept/write-rows), each type by its name, of a collection (element-row?)
   or not."
  [opts array?]
  (for [[s t :as row] (accept/write-rows opts)
        :when (= array? (element-row? row))]
    [s (if (= :unknown t) t (:name (types/entry t)))]))

;;; what the driver returns and takes

(defn- pg-type [s] (when (and (vector? s) (map? (second s))) (:pg/type (second s))))

(defn- array-column?
  "An array column's schema: [:vector {:pg/type \"text[]\"} :string]."
  [s]
  (and (vector? s) (= :vector (first s)) (some-> (pg-type s) (str/ends-with? "[]"))))

(defn- driver-type
  "What read-type and write-type share: dates and timestamps under :time, arrays, json and
   intervals the driver's objects (:any)."
  [schema {:keys [time read] :or {time :inst} :as opts} element]
  (let [driver-object [:any {:pg/type (pg-type schema)}]]
    (cond (array-column? schema) (if-let [kind (:array read)] [kind (element (last schema) opts)] driver-object)
          (and (types/json? (pg-type schema)) (not= :parsed (:json read))) driver-object
          (= :time/duration (if (vector? schema) (first schema) schema)) driver-object
          :else (shape/read-time time schema))))

(defn read-type
  "A column's schema (without NULL) as PostgreSQL's JDBC driver returns it through next.jdbc:
   dates and timestamps under :time (inst? by default), an array a java.sql.Array, json and
   jsonb a PGobject, an interval a PGInterval, citext, bit(n > 1) and bit varying a PGobject
   (these :any); real a Float, bit(1) a Boolean, numeric a Double for NaN, time and timetz a
   java.sql.Time under every :time (next.jdbc.date-time converts dates and timestamps alone).
   :read names what a ReadableColumn extension returns instead: a :pg/type's schema,
   {:array :vector} for arrays as vectors of their elements, {:json :parsed} for json as the
   data the registry shapes."
  [schema {:keys [read] :as opts}]
  (let [t (pg-type schema)
        head (if (vector? schema) (first schema) schema)
        driver-read (:read (types/entry t))]
    (or (shape/read-as read schema)
        (cond (= :bit driver-read) (if (= 1 (:max (second schema))) [:boolean {:pg/type t}] [:any {:pg/type t}])
              (= :or-double driver-read) [:or (driver-type schema opts read-type) ['double? {:pg/type t}]]
              driver-read [driver-read {:pg/type t}]
              (#{:time/local-time :time/offset-time} head) ['inst? {:pg/type t}]
              :else (driver-type schema opts read-type)))))

(defn array-type
  "An array argument of elements t: the collections :write's rows with :pgmalli/element send,
   t in its place ([[:sequential :pgmalli/element] :unknown]); :any without one."
  [t opts]
  (let [alts (for [[s] (write-rows opts true)] (clojure.walk/postwalk-replace {:pgmalli/element t} s))]
    (case (count alts) 0 :any 1 (first alts) (into [:or] alts))))

(defn write-type
  "A column's schema (without NULL) as an argument the driver takes: an array column's
   argument is an array of its elements' (array-type); otherwise the types read-type shares
   with it (:time extends both sides), and what :write sends as its type, which is all of it
   where read-type has the driver's object (:any)."
  [schema opts]
  (if (array-column? schema)
    (array-type (write-type (last schema) opts) opts)
    (let [t (driver-type schema (dissoc opts :read) write-type)
          n (some-> (shape/type-name (shape/column-props schema)) types/entry :name)
          alts (concat (when-not (= :any (if (vector? t) (first t) t)) [t])
                       ;; labels (an enum's, a CHECK's list) are the column's values whatever is sent
                       (for [[s sent] (write-rows opts false) :when (and (= n sent) (not= :enum (shape/kind t)))
                             a (if (and (vector? s) (= :or (first s))) (remove map? (rest s)) [s])]
                         a))]
      (case (count alts) 0 t 1 (first alts) (into [:or] alts)))))

;;; rows as next.jdbc builds them

(defn row-key
  "The key next.jdbc gives a column: qualified by its table when the column has one, unless
   :qualified? is false; table is [schema name], or \"schema.table\" split at its first dot; kebab-cased (:kebab?) or lower-cased
   (:lower?) as its builders do it."
  [table column {:keys [qualified? kebab? lower?] :or {qualified? true}}]
  (let [k (cond kebab? csk/->kebab-case-string lower? str/lower-case :else identity)]
    (if (and qualified? table)
      (keyword (k (if (vector? table) (second table) (last (str/split table #"\." 2)))) (k column))
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
                                  (when (types/json? (:pg/type (m/properties schema)))
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
  (let [maps {"as-maps" {:qualified? true} "as-unqualified-maps" {:qualified? false}
              "as-lower-maps" {:qualified? true :lower? true} "as-unqualified-lower-maps" {:qualified? false :lower? true}}]
    (merge (update-keys (merge maps {"as-kebab-maps" {:qualified? true :kebab? true} "as-unqualified-kebab-maps" {:qualified? false :kebab? true}})
                        #(symbol "next.jdbc.result-set" %))
           (update-keys (update-vals maps #(assoc % :nil-columns :absent)) #(symbol "next.jdbc.optional" %)))))

(defn read-options
  "The reading options of a next.jdbc result set builder named by its symbol; documented on
   pgmalli.core/read-options."
  [builder]
  (let [b (if (var? builder) (symbol builder) builder)]
    (or (get builders b)
        (let [known (vec (sort (keys builders)))
              suggest (error/suggest b known)]
          (error/fail! :pgmalli.error/invalid-options
                       (str b (cond (str/includes? (str b) "modified") " keys rows by functions of yours: give :qualified? and :kebab? as they do"
                                    suggest (str " is not one of next.jdbc's map builders; did you mean " suggest "?")
                                    :else (str " is not one of next.jdbc's map builders pgmalli knows; it knows " known)))
                       (cond-> {:given b :known known} suggest (assoc :suggest suggest)))))))

(defn- data-columns
  "The row map of a generated schema as data (columns gives the malli schema)."
  [registry name]
  (let [s (shape/schema-of registry name)]
    (when-not (shape/read-row? s)
      (shape/not-a-row! registry name "as-read"))
    (shape/row-map s)))

(defn- through-domain
  "A column of a domain whose type the driver returns as something else (a date as a
   java.sql.Date, a citext as a PGobject) as that type, without the domain's CHECKs, which read
   the registry's types; a domain the driver returns (or, f write-type, takes) as the registry
   holds it stays a reference."
  [schemas s opts f]
  (if-let [d (when (and (vector? s) (= :ref (first s))) (get schemas (last s)))]
    (let [base (through-domain schemas (shape/domain-base d) opts f)]
      (if (= base (f base opts)) s (shape/merge-props (shape/without-gen base) (dissoc (shape/column-props s) :pg/type))))
    s))

(defn column-read-type
  "read-type of a column schema (without NULL) of a registry's schemas, a domain read as the
   driver reads its base; :read may still name the domain's type."
  [schemas s {:keys [read] :as opts}]
  (or (shape/read-as read s) (read-type (through-domain schemas s opts read-type) opts)))

(defn column-write-type
  "write-type of a column schema (without NULL) of a registry's schemas, a domain taken as the
   driver takes its base."
  [schemas s opts]
  (write-type (through-domain schemas s opts write-type) opts))

(defn as-read
  "The [:map ...] of a row as a JDBC result builder returns it; the options are documented on
   pgmalli.core/as-read."
  [registry name opts]
  (checked! @read-options* opts)
  (let [m (shape/without-gen (data-columns registry name))
        props (when (map? (second m)) (second m))
        table (when (or (:pg/table props) (:pg/view props)) (shape/schema-and-name name))
        schemas (shape/schemas-of registry)
        entry (fn [[k p s]]
                (row-entry (row-key table (clojure.core/name k) opts) p (column-read-type schemas (shape/non-null s) opts)
                           (shape/maybe? s) opts))]
    (into (if props [:map props] [:map]) (map entry (shape/column-entries m)))))
