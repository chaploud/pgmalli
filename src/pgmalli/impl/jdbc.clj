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
            [pgmalli.impl.pgtypes :as pgtypes]
            [pgmalli.impl.shape :as shape]
            [pgmalli.impl.types :as types]))

;;; the options

(def options-schema
  "The options pgmalli takes, closed: a misspelt key or value is an error rather than a check
   silently weakened. :jdbc and :format are the maps an application passes to next.jdbc and
   sql/format, open: pgmalli reads the keys of them it names."
  [:map {:closed true}
   [:schema {:optional true} :string]
   [:time {:optional true} [:enum :inst :instant :local]]
   [:read {:optional true} [:and [:map-of [:or :string [:enum :array :json]] :any]
                            [:map [:array {:optional true} [:enum :vector :sequential]]
                             [:json {:optional true} [:enum :parsed]]]]]
   [:jdbc {:optional true} [:map
                            [:builder-fn {:optional true} [:or ifn? qualified-symbol?]]
                            [:label-fn {:optional true} [:or fn? [:fn var?]]]
                            [:qualifier-fn {:optional true} [:or fn? [:fn var?]]]
                            [:return-keys {:optional true} [:or :boolean [:vector {:min 1} [:or :string :keyword]]]]]]
   ;; {class type}, which options! checks
   [:send {:optional true} [:map-of :any :any]]
   [:compare {:optional true} [:enum :strict :lenient]]
   [:result {:optional true} [:enum :one]]
   [:format {:optional true} :map]])

(defn checked!
  "opts (nil is none) when schema takes them; an error naming what it does not, a misspelt key
   with the one meant, otherwise. kind and label say whose they are (options by default).
   A config's :db, which may hold a password, is left out of the error, and so are the values of
   keys schema does not take (a misspelt :db is one)."
  ([schema opts] (checked! schema opts :pgmalli.error/invalid-options "pgmalli options"))
  ([schema opts kind label]
   (if (m/validate schema (or opts {}))
     opts
     (let [errors (me/humanize (m/explain schema opts))
           known (when (= :map (m/type schema)) (map first (m/children (m/schema schema))))
           stray (when (map? opts) (remove (set known) (keys opts)))
           suggest (some #(error/suggest % known) stray)]
       (error/fail! kind (str label ": " (pr-str errors)
                              (cond suggest (error/did-you-mean suggest)
                                    (and (seq stray) (seq known)) (str "; it takes " (str/join ", " known))))
                    (cond-> {:given (cond-> opts
                                      (and (map? opts) known) (select-keys known)
                                      (map? opts) (dissoc :db))
                             :errors errors}
                      known (assoc :known (vec known))
                      ;; the names of the keys it does not take, not their values
                      (seq stray) (assoc :unknown (vec stray))
                      suggest (assoc :did-you-mean [suggest])))))))

;; compiled once: malli keeps a schema's validator, and these are checked on every call
(def ^:private options (delay (m/schema options-schema)))

;;; how next.jdbc keys a row

(def ^:private builders
  "next.jdbc's map builders by their symbols -> how they key a row: qualified by the table or
   not, a NULL column's key absent (next.jdbc.optional), the function naming a label and a
   qualifier, or :modified, the :label-fn and :qualifier-fn next.jdbc takes with them."
  (let [maps {"as-maps" {:qualified? true} "as-unqualified-maps" {:qualified? false}
              "as-lower-maps" {:qualified? true :fn str/lower-case} "as-unqualified-lower-maps" {:qualified? false :fn str/lower-case}
              "as-modified-maps" {:qualified? true :fn :modified} "as-unqualified-modified-maps" {:qualified? false :fn :modified}}]
    (merge (update-keys (merge maps {"as-kebab-maps" {:qualified? true :fn csk/->kebab-case-string}
                                     "as-unqualified-kebab-maps" {:qualified? false :fn csk/->kebab-case-string}})
                        #(symbol "next.jdbc.result-set" %))
           (update-keys (update-vals maps #(assoc % :absent? true)) #(symbol "next.jdbc.optional" %)))))

(def ^:private builder-fns
  "{function how} of the builders that load here."
  (delay (into {} (for [[sym how] builders
                        :let [f (some-> (try (requiring-resolve sym) (catch Exception _ nil)) deref)]
                        :when f]
                    [f how]))))

(defn- builder
  "How the next.jdbc builder f keys a row (builders): the function, its var, or its qualified
   symbol, which names it where next.jdbc does not load (babashka); nil for another."
  [f]
  (if (symbol? f) (get builders f) (get @builder-fns (if (var? f) @f f))))

(defn row-keys
  "How a row is keyed under opts: {:qualified? :absent? :label :qualifier}, as :jdbc's
   :builder-fn (next.jdbc's as-maps by default) keys it, a modified builder by :jdbc's
   :label-fn and :qualifier-fn. A set operation's member (::member in opts) is read unqualified
   and with every column, as next.jdbc keys the whole row by the first member's labels."
  [{:keys [jdbc] :as opts}]
  (let [{:keys [builder-fn label-fn qualifier-fn]} jdbc
        how (if builder-fn (builder builder-fn) {:qualified? true})
        f (:fn how)]
    {:qualified? (and (not (::member opts)) (:qualified? how))
     :absent? (and (not (::member opts)) (:absent? how))
     :label (cond (= :modified f) label-fn f f :else identity)
     :qualifier (cond (= :modified f) (or qualifier-fn identity) f f :else identity)}))

;;; what an application sends

(def ^:private collections
  "The Clojure collections an application may send as an array, a value of each, and the malli
   schema of one, :pgmalli/element in an element's place."
  [[[] [:vector :pgmalli/element]] [#{} [:set :pgmalli/element]] ['(nil) [:sequential :pgmalli/element]]])

(defn- collection-schema
  "The schema of the Clojure collections a value of class c may be (an interface such as
   java.util.List or java.util.Collection takes several); nil for none."
  [^Class c]
  (let [ss (for [[v s] collections :when (instance? c v)] s)
        ;; a sequential takes a vector
        ss (if (some #(= :sequential (first %)) ss) (remove #(= :vector (first %)) ss) ss)]
    (when (seq ss) (shape/either ss nil))))

(def ^:private class-schemas
  "[class schema]: the malli schema of a value of class, for the classes pgjdbc sends and those
   an application's extensions commonly do; another class is [:fn instance?]."
  [[Long :int] [Integer :int] [Short :int] [Byte :int] [clojure.lang.BigInt 'integer?] [java.math.BigInteger 'integer?]
   [java.math.BigDecimal 'decimal?] [Double :double] [Float 'float?]
   [Boolean :boolean] [String :string] [Character 'char?] [java.util.UUID :uuid] [accept/bytes-class 'bytes?]
   [java.time.LocalDate :time/local-date] [java.time.LocalTime :time/local-time] [java.time.OffsetTime :time/offset-time]
   [java.time.LocalDateTime :time/local-date-time] [java.time.OffsetDateTime :time/offset-date-time]
   [java.time.Instant :time/instant] [java.util.Date 'inst?] [clojure.lang.Keyword :keyword] [clojure.lang.Symbol :symbol]
   [clojure.lang.IPersistentMap [:map-of :any :any]] [clojure.lang.IPersistentVector [:vector :any]]
   [clojure.lang.IPersistentSet [:set :any]] [clojure.lang.Sequential [:sequential :any]]])

(def ^:private class-schema
  ;; memoized: a class's [:fn] is one function, so the schemas of one call equal the next's
  (memoize (fn [^Class c]
             (or (some (fn [[k s]] (when (= k c) s)) class-schemas)
                 ;; java.sql's dates, times and timestamps are java.util.Dates
                 (when (.isAssignableFrom java.util.Date c) 'inst?)
                 ;; a Java array (but byte[], a bytea): :pg/array reads its elements by value, as the
                 ;; column's, which array-type puts in
                 (when (and (.isArray c) (not= c accept/bytes-class)) [:pg/array :pgmalli/element])
                 [:fn {:pg/class (.getName c)} (fn [x] (instance? c x))]))))

(defn- array-type-name
  "\"varchar[]\" as \"character varying[]\": an array type named by an element type types knows
   (a name or an alias), by that type's name; nil otherwise."
  [t]
  (when-let [e (accept/element t)]
    (some-> (types/entry e) :name (str "[]"))))

(defn- type-name
  "A :send type by its name: a type types knows, an array of one, :unknown; nil otherwise."
  [t]
  (cond (= :unknown t) t
        (array-type-name t) (array-type-name t)
        :else (some-> (types/entry t) :name)))

(defn send-map
  "{class type} an application sends: pgjdbc's (accept/class-types) but a Character, which a
   char? on every string argument would say nothing of, with :send merged over it, a class
   :send maps to nil left out."
  [{:keys [send]}]
  (into {} (remove (comp nil? val)) (merge (dissoc (into {} accept/class-types) Character) send)))

(def ^:private jdbc-read
  "The keys of :jdbc pgmalli reads."
  [:builder-fn :label-fn :qualifier-fn :return-keys])

(def ^:private next-jdbc-options
  "The options next.jdbc reads (its own and its PreparedStatement's): a :jdbc key among them,
   though spelled close to one of jdbc-read, is no misspelling."
  #{:auto-commit :batch :batch-size :builder-fn :column-fn :columns :concurrency :cursors :fetch :fetch-size
    :isolation :label-fn :large :limit :max-rows :multi-rs :name-fn :offset :order-by :qualifier-fn :read-only
    :result-type :return-generated-keys :return-keys :rollback-only :schema :schema-opts :suffix :table-fn
    :timeout :top})

(defn options!
  "opts when pgmalli takes them: their keys and values; :send a map of classes to a type types
   knows (a name or an alias), an array of one (\"bigint[]\"), :unknown or nil; :jdbc's
   :builder-fn one of next.jdbc's map builders, a modified one with :label-fn. An error names
   what it does not take, with the one meant; :write and :stringtype of pgmalli 0.3 name :send."
  [opts]
  (let [fail! #(error/fail! :pgmalli.error/invalid-options (str "pgmalli options: " %1) {:given %2})]
    (when-let [old (not-empty (select-keys opts [:write :stringtype]))]
      (fail! (str (pr-str old) " is no longer read: what an application sends is :send, a map of classes to the type"
                  " each goes as ({String :unknown} for stringtype=unspecified); see CHANGELOG.md and"
                  " docs/queries.md#what-your-application-sends")
             old))
    (when-let [old (not-empty (select-keys opts [:qualified? :kebab? :lower? :nil-columns :return-keys]))]
      (fail! (str (pr-str old) " is no longer read: pass the options you pass to next.jdbc as :jdbc"
                  " ({:jdbc {:builder-fn rs/as-unqualified-kebab-maps :return-keys true}}); see CHANGELOG.md")
             old))
    (when (contains? opts :send)
      (when-not (map? (:send opts))
        (fail! (str ":send is a map of classes to types, {clojure.lang.Keyword :unknown}; got " (pr-str (:send opts))) (:send opts)))
      (doseq [[c t :as e] (:send opts)]
        (cond (not (class? c))
              (fail! (str ":send's key " (pr-str c) " is no class; a key is the class of a value sent (String, clojure.lang.Keyword)") e)
              (and (some? t) (not (type-name t)))
              (fail! (str ":send's " (pr-str c) " goes as " (pr-str t) ", which is no type of docs/types.md, an array of one (\"bigint[]\")"
                          " or :unknown for a value sent untyped (an enum's or a domain's value too)"
                          (when (string? t) (error/did-you-mean (some-> (error/suggest t (mapcat (fn [[n e]] (cons n (:aliases e))) types/types)) pr-str))))
                     e))))
    (let [{:keys [builder-fn label-fn] :as jdbc} (:jdbc opts)]
      (doseq [k (when (map? jdbc) (keys jdbc))
              :let [meant (when-not (next-jdbc-options k) (error/suggest k jdbc-read))]
              :when meant]
        (error/fail! :pgmalli.error/invalid-options
                     (str "pgmalli options: :jdbc's " (pr-str k) " is no next.jdbc option" (error/did-you-mean meant))
                     {:given [k] :did-you-mean [meant]}))
      (doseq [k [:label-fn :qualifier-fn] :let [f (get jdbc k)] :when (symbol? f)]
        (fail! (str ":jdbc's " k " is the function next.jdbc takes, not its symbol: " (pr-str f)
                    " (a symbol is the builder-fn's alone, for babashka)")
               (select-keys jdbc [k])))
      (when builder-fn
        (let [how (builder builder-fn)]
          (cond (nil? how)
                (fail! (str ":jdbc's :builder-fn is none of next.jdbc.result-set's or next.jdbc.optional's map builders ("
                            (str/join ", " (distinct (sort (map name (keys builders))))) "), whose keys pgmalli knows")
                       (select-keys jdbc [:builder-fn]))
                (and (= :modified (:fn how)) (not label-fn))
                (fail! ":jdbc's modified builder takes :label-fn, as next.jdbc's does" (select-keys jdbc [:builder-fn]))))))
    (checked! @options opts)
    opts))

(def ^:private class-order (zipmap (map first accept/class-types) (range)))

(defn- sorted-sends
  "The entries of a send-map in pgjdbc's order (accept/class-types), then by class name."
  [m]
  (sort-by (fn [[c]] [(class-order c Long/MAX_VALUE) (str c)]) m))

(defn write-rows
  "[schema type] for each class send-map sends, in pgjdbc's order (accept/class-types) and then
   by name, by array? an array argument's (a Clojure
   collection or a Java array sent as an array type or untyped, the collection's elements
   :pgmalli/element) or another's."
  [opts array?]
  (for [[c t] (sorted-sends (send-map opts))
        :let [t (type-name t)
              coll (collection-schema c)
              array (or (.isArray ^Class c) (and coll (or (= :unknown t) (array-type-name t))))]
        :when (= array? (boolean (and array (not= c accept/bytes-class))))]
    [(if (and array coll) coll (class-schema c)) t]))

(defn sent-type
  "The type a literal value goes to PostgreSQL as: :unknown under sql/format's :inline, which
   writes it into the SQL; otherwise as send-map sends its class, nil for a class it does not
   list."
  [value {:keys [format] :as opts}]
  (if (:inline format)
    :unknown
    (let [m (send-map opts)]
      ;; its class's entry, else its superclasses' and interfaces' in pgjdbc's order (write-rows's)
      (or (some-> (get m (class value)) type-name)
          (some (fn [[c t]] (when (instance? c value) (type-name t))) (sorted-sends m))))))

(defn string-sends
  "The types a String is sent as: send-map's for String and Character."
  [opts]
  (vec (distinct (keep #(some-> (get (send-map opts) %) type-name) [String Character]))))

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
        ;; a domain's base comes with its typmod: "bit(4)"
        driver-read (when-not (array-column? schema) (:read (types/entry (shape/type-name {:pg/type t}))))]
    (or (shape/read-as read schema)
        (cond (= :bit driver-read) (if (or (= 1 (:max (second schema))) (str/ends-with? t "(1)")) [:boolean {:pg/type t}] [:any {:pg/type t}])
              (= :or-double driver-read) [:or (driver-type schema opts read-type) ['double? {:pg/type t}]]
              driver-read [driver-read {:pg/type t}]
              (#{:time/local-time :time/offset-time} head) ['inst? {:pg/type t}]
              :else (driver-type schema opts read-type)))))

(declare write-type)

(defn array-type
  "An array argument of elements t, used as use (:compare, = ANY(?); :assign, to an array
   column; :unnest, unnest(?) assigned): the collections and Java arrays :send sends as an
   array (write-rows), t in place of :pgmalli/element, of those PostgreSQL takes
   there: an untyped one but under unnest (unnest(unknown) is ambiguous), a typed one whose
   element type is one of t's own (accept/holds?), as a column takes a value, its elements
   then also that type's values (a bigint[]'s Longs to a numeric[] column); of an enum type,
   an array of that type alone. ::element-type in opts names t's type where t's schema names
   none (numeric[]'s decimal?). :any without one."
  [t opts use]
  ;; t may take NULL (a nullable column's elements); its type is the one without
  ;; an array column names its elements' type (a domain's array its base's), which goes first
  (let [p (or (::element-type opts) (some pg-type (shape/alternatives (shape/non-null t))))
        elem (some-> p types/entry :name)
        takes? (fn [sent]
                 (if (= :unknown sent)
                   (not= :unnest use)
                   (cond (and elem (accept/known? elem)) (accept/holds? elem (accept/element sent))
                         ;; an enum type's array takes an array of that type alone; a domain reads as its base
                         (and p (not elem) (= :enum (shape/kind (shape/non-null t)))) (= p (accept/element sent))
                         :else true)))
        ;; a typed array's elements are also the values of its element type, where the column
        ;; takes them as its own (a bigint[]'s Longs to a numeric[] column)
        element (fn [sent]
                  (let [x (when (string? sent) (accept/element sent))
                        ;; an element of no known type (an opaque column's) stays so
                        e (when (and x elem (not= x elem)) (types/entry x))]
                    (if-let [s (:schema e)]
                      (let [;; pgmalli's integer types are integers, as :int is
                            kind #(let [k (shape/kind %)] (if (pgtypes/int-ranges k) :int k))
                            own (set (map kind (shape/alternatives (shape/non-null t))))
                            more (remove #(own (kind %)) (shape/alternatives (write-type (if (keyword? s) [s {:pg/type (:name e)}] s) opts)))]
                        (shape/either (distinct (concat (shape/alternatives t) more)) t))
                      t)))
        alts (distinct (for [[s sent] (write-rows opts true) :when (takes? sent)
                             a (shape/alternatives s)]
                         (clojure.walk/postwalk-replace {:pgmalli/element (element sent)} a)))]
    (shape/either alts :any)))

(defn- base-type-name
  "The type a column schema names, on itself or on the base of its [:and base checks...]
   (numeric(10,2) keeps it on its decimal?), without typmod or brackets."
  [schema]
  (some #(shape/type-name (shape/column-props %)) [schema (shape/domain-base schema)]))

(def ^:private numbers
  "The kinds of the number classes a CHECK on a number column reads."
  #{:int :double 'decimal? 'integer? 'float? :pg/integer :pg/smallint})

(defn write-type
  "A column's schema (without NULL) as an argument the driver takes: an array column's
   argument is an array of its elements' (array-type); otherwise the types read-type shares
   with it (:time extends both sides), and what :send sends as its type, which is all of it
   where read-type has the driver's object (:any)."
  [schema opts]
  (if (array-column? schema)
    ;; the elements' type is the column's, where their schema names none (numeric[]'s decimal?)
    (array-type (write-type (last schema) opts) (assoc opts ::element-type (accept/element (pg-type schema))) :assign)
    (let [t (driver-type schema (dissoc opts :read) write-type)
          n (some-> (base-type-name schema) types/entry :name)
          ;; labels (an enum's, a CHECK's list) are the column's values whatever is sent
          sent (for [[s sent] (write-rows opts false) :when (and (= n sent) (not= :enum (shape/kind t)))
                     a (shape/alternatives s)]
                 a)]
      (cond (empty? sent) t
            (= :any (shape/head t)) (shape/either sent t)
            :else
            (let [base (shape/domain-base t)
                  ;; a kind the base has already takes its bounds (a varchar(n)'s length)
                  more (remove #(= (shape/kind %) (shape/kind base)) sent)
                  ;; a CHECK reads a number as the number it is (a BigInt's > 0); another class goes
                  ;; as the text an application makes of it (a keyword's), which no check here reads
                  [held free] ((juxt filter remove) #(and (numbers (shape/kind base)) (numbers (shape/kind %))) more)]
              (shape/either (concat (shape/alternatives (shape/update-base t #(shape/either (distinct (cons % held)) %))) free) t))))))

;;; rows as next.jdbc builds them

(defn- qualify
  "k qualified, when the builder qualifies (row-keys ks), by what its qualifier makes of table;
   an empty qualifier leaves k unqualified, as next.jdbc's not-empty does."
  [k {:keys [qualified? qualifier]} table]
  (if-let [q (when qualified? (not-empty (qualifier table)))] (keyword q (name k)) k))

(defn tableless-key
  "k, a column's key without a table, as next.jdbc keys a column the driver names no table for
   (a set operation's): qualified by what the qualifier makes of \"\" when that is not empty."
  [k opts]
  (qualify k (row-keys opts) ""))

(defn row-key
  "The key next.jdbc gives a column (row-keys): qualified, when the builder qualifies, by its
   table; a column of none as tableless-key keys it. table is [schema name], or
   \"schema.table\" split at its first dot."
  [table column opts]
  (let [{:keys [label] :as ks} (row-keys opts)
        k (keyword (label column))]
    (qualify k ks (cond (nil? table) "" (vector? table) (second table) :else (last (str/split table #"\." 2))))))

(defn row-entry
  "A row map's entry for a column read as inner: NULL a [:maybe], or the key absent under a
   next.jdbc.optional builder."
  ([k inner nullable? opts] (row-entry k nil inner nullable? opts))
  ([k props inner nullable? opts]
   (let [absent? (and nullable? (:absent? (row-keys opts)))
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
  (if-let [d (shape/ref-target schemas s)]
    (let [base (through-domain schemas (shape/domain-base d) opts f)]
      ;; compared with nothing sent, as sends add values to the base type and do not change how the driver reads it
      (if (= base (f base (assoc opts :send (zipmap (map first accept/class-types) (repeat nil)))))
        s
        (shape/merge-props (shape/without-gen base) (dissoc (shape/column-props s) :pg/type))))
    s))

(defn column-read-type
  "read-type of a column schema (without NULL) of a registry's schemas, a domain read as the
   driver reads its base; :read may still name the domain's type."
  [schemas s {:keys [read] :as opts}]
  (let [;; the driver returns an array's elements of a domain (but an enum's) as PGobjects
        x (when (array-column? s) (last s))
        target (shape/ref-target schemas x)
        s (if (and target (not= :enum (shape/head target))) (assoc s (dec (count s)) [:any {:pg/type (pg-type x)}]) s)]
    (or (shape/read-as read s) (read-type (through-domain schemas s opts read-type) opts))))

(defn- column-write-type*
  "write-type of a column schema (without NULL) of a registry's schemas, a domain taken as the
   driver takes its base."
  [schemas s opts]
  (let [;; an array's elements of an enum type are its labels: array-type takes an array of that type alone
        e (when (array-column? s) (last s))
        target (shape/ref-target schemas e)
        s (cond (nil? target) s
                (= :enum (shape/head target))
                (assoc s (dec (count s)) (into [:enum {:pg/type (pg-type e)}] (remove map? (rest target))))
                ;; a domain's array reads as its base type's, elements and all
                :else (if-let [base (base-type-name target)]
                        (-> s (assoc-in [1 :pg/type] (str base "[]")) (assoc (dec (count s)) (shape/domain-base target)))
                        s))
        ;; a domain's value is sent as its base type's: the classes sent as that type, held to the
        ;; domain's checks, its definition in place of the reference
        d (shape/ref-target schemas (shape/non-null s))
        ;; (a number domain's: its checks read the number; another's may hold code only the registry runs)
        s (if (and d (some-> (base-type-name d) types/entry :name accept/number-type?)) d s)]
    (write-type (through-domain schemas s opts write-type) opts)))

(def ^:private pg-object
  "A PGobject (not a subclass of it, such as PGInterval), by name: pgjdbc need not be loaded."
  [:fn {:pg/class "org.postgresql.util.PGobject"} (fn [x] (= "org.postgresql.util.PGobject" (some-> x class .getName)))])

(defn column-write-type
  "column-write-type*: a column the driver reads as a PGobject (citext, bit(n > 1)) takes
   one too: pgjdbc sends it as the type it names, so a value read is written back."
  [schemas s opts]
  (let [w (column-write-type* schemas s opts)
        r (column-read-type schemas s (dissoc opts :read))
        t (pg-type r)]
    (if (and (not= :any (shape/head w)) (= :any (shape/head r)) t (not (str/ends-with? t "[]"))
             (#{:any :bit} (:read (types/entry (shape/type-name {:pg/type t})))))
      (shape/either (concat (shape/alternatives w) [pg-object]) w)
      w)))

(defn as-read
  "The [:map ...] of a row as a JDBC result builder returns it; the options are documented on
   pgmalli.core/as-read."
  [registry name opts]
  (options! opts)
  (let [m (shape/without-gen (data-columns registry name))
        props (when (map? (second m)) (second m))
        table (when (or (:pg/table props) (:pg/view props)) (shape/schema-and-name name))
        schemas (shape/schemas-of registry)
        entry (fn [[k p s]]
                (row-entry (row-key table (clojure.core/name k) opts) p (column-read-type schemas (shape/non-null s) opts)
                           (shape/maybe? s) opts))]
    (into (if props [:map props] [:map]) (map entry (shape/column-entries m)))))
