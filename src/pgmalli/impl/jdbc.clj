(ns pgmalli.impl.jdbc
  "The JDBC side: what a column comes back as under the one reading (docs/reading-rows.md) and
   what PostgreSQL's JDBC driver takes for it, under the options that say what an application
   sends; and the row shapes next.jdbc's as-unqualified-maps returns. as-read and
   pgmalli.honeysql both read columns and key rows here."
  (:require [clojure.string :as str]
            [clojure.walk]
            [malli.core :as m]
            [malli.error :as me]
            [pgmalli.impl.accept :as accept]
            [pgmalli.impl.error :as error]
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
   [:jdbc {:optional true} [:map
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

(defn- class-fn
  "[:fn] of the values of class name n, pred telling them."
  [n pred]
  [:fn {:pg/class n :error/message (str "should be an " n)} pred])

(def ^:private class-schema
  ;; memoized: a class's [:fn] is one function, so the schemas of one call equal the next's
  (memoize (fn [^Class c]
             (or (some (fn [[k _ s]] (when (= k c) s)) accept/classes)
                 ;; java.sql's dates, times and timestamps are java.util.Dates
                 (when (.isAssignableFrom java.util.Date c) 'inst?)
                 ;; a Java array (but byte[], a bytea): :pg/array reads its elements by value, as the
                 ;; column's, which array-type puts in
                 (when (and (.isArray c) (not= c accept/bytes-class)) [:pg/array :pgmalli/element])
                 (class-fn (.getName c) (fn [x] (instance? c x)))))))

(defn- sent-name
  "A :send type by its name (types/canonical-name): :unknown, a type types knows or an array of
   one; nil otherwise."
  [t]
  (if (= :unknown t) t (when (types/entry (or (types/element t) (when (string? t) t))) (types/canonical-name t))))

(defn send-map
  "{class type} an application sends: pgjdbc's (accept/class-types) but a Character, which a
   char? on every string argument would say nothing of, with :send merged over it, a class
   :send maps to nil left out."
  [{:keys [send]}]
  (into {} (remove (comp nil? val)) (merge (dissoc (into {} accept/class-types) Character) send)))

(def ^:private jdbc-read
  "The keys of :jdbc pgmalli reads."
  [:return-keys])

(def ^:private next-jdbc-options
  "The options next.jdbc reads (its own and its PreparedStatement's): a :jdbc key among them,
   though spelled close to one of jdbc-read, is no misspelling."
  #{:auto-commit :batch :batch-size :builder-fn :column-fn :columns :concurrency :cursors :fetch :fetch-size
    :isolation :label-fn :large :limit :max-rows :multi-rs :name-fn :offset :order-by :qualifier-fn :read-only
    :result-type :return-generated-keys :return-keys :rollback-only :schema :schema-opts :suffix :table-fn
    :timeout :top})

(def ^:private builder-only
  (str "pgmalli types rows as next.jdbc.result-set/as-unqualified-maps builds them, keyed by the columns' names;"
       " pass it to next.jdbc as {:builder-fn next.jdbc.result-set/as-unqualified-maps}"))

(def ^:private one-reading
  (str "pgmalli types rows as one reading makes them: timestamps as Instants (next.jdbc.date-time/read-as-instant),"
       " arrays as vectors, json and jsonb parsed (keyword keys), enum values as strings, every other"
       " type as pgjdbc returns it; the ReadableColumn extension that reads so is in"
       " https://github.com/chaploud/pgmalli/blob/main/docs/reading-rows.md"))

(defn- the-builder?
  "Whether f is next.jdbc.result-set/as-unqualified-maps: the function, its var or its symbol,
   which names it where next.jdbc does not load (babashka)."
  [f]
  (let [sym 'next.jdbc.result-set/as-unqualified-maps
        v (try (requiring-resolve sym) (catch Exception _ nil))]
    (or (= sym f) (and v (or (= v f) (= @v f))))))

(defn options!
  "opts when pgmalli takes them: their keys and values; :send a map of classes to a type types
   knows (a name or an alias), an array of one (\"bigint[]\"), :unknown or nil. An error names
   what it does not take, with the one meant; :write and :stringtype of pgmalli 0.3 name :send."
  [opts]
  (let [fail! #(error/fail! :pgmalli.error/invalid-options (str "pgmalli options: " %1) {:given %2})]
    (when-let [old (not-empty (select-keys opts [:write :stringtype]))]
      (fail! (str (pr-str old) " is no longer read: what an application sends is :send, a map of classes to the type"
                  " each goes as ({String :unknown} for stringtype=unspecified); see https://github.com/chaploud/pgmalli/blob/main/docs/queries.md#what-your-application-sends"
                  " and " error/changelog)
             old))
    (when-let [old (not-empty (select-keys opts [:qualified? :kebab? :lower? :nil-columns]))]
      (fail! (str (pr-str old) " is no longer read: " builder-only "; see " error/changelog) old))
    (when-let [old (not-empty (select-keys opts [:time :read]))]
      (fail! (str (pr-str old) " is no longer read: " one-reading "; see " error/changelog) old))
    (when (contains? opts :return-keys)
      (fail! (str ":return-keys is no longer read: pass it as {:jdbc {:return-keys ...}}; see " error/changelog)
             (select-keys opts [:return-keys])))
    (when (contains? opts :send)
      (when-not (map? (:send opts))
        (fail! (str ":send is a map of classes to types, {clojure.lang.Keyword :unknown}; got " (pr-str (:send opts))) (:send opts)))
      (doseq [[c t :as e] (:send opts)]
        (cond (not (class? c))
              (fail! (str ":send's key " (pr-str c) " is no class; a key is the class of a value sent (String, clojure.lang.Keyword)") e)
              (and (some? t) (not (sent-name t)))
              (fail! (str ":send's " (pr-str c) " goes as " (pr-str t) ", which is no type of docs/types.md, an array of one (\"bigint[]\")"
                          " or :unknown for a value sent untyped (an enum's or a domain's value too)"
                          (when (string? t) (error/did-you-mean (some-> (error/suggest t (mapcat (fn [[n e]] (cons n (:aliases e))) types/types)) pr-str))))
                     e))))
    (let [jdbc (:jdbc opts)]
      (doseq [k (when (map? jdbc) (keys jdbc))
              :let [meant (when-not (next-jdbc-options k) (error/suggest k jdbc-read))]
              :when meant]
        (error/fail! :pgmalli.error/invalid-options
                     (str "pgmalli options: :jdbc's " (pr-str k) " is no next.jdbc option" (error/did-you-mean meant))
                     {:given [k] :did-you-mean [meant]}))
      (when-let [given (not-empty (select-keys (when (map? jdbc) jdbc) [:builder-fn :label-fn :qualifier-fn]))]
        (when-not (and (= [:builder-fn] (keys given)) (the-builder? (:builder-fn given)))
          (fail! (str ":jdbc's " (str/join ", " (keys given)) " is no longer read: " builder-only "; see " error/changelog)
                 given))))
    (checked! @options opts)
    opts))

(def ^:private class-order (zipmap (map first accept/class-types) (range)))

(def ^:private sends
  "[m entries] of a :send: send-map with each type by its name (sent-name), and its entries in
   pgjdbc's order (accept/class-types), then by class name. Memoized: every argument reads them."
  (memoize (fn [send]
             (let [m (update-vals (send-map {:send send}) sent-name)]
               [m (sort-by (fn [[c]] [(class-order c Long/MAX_VALUE) (str c)]) m)]))))

(def ^:private write-rows*
  (memoize (fn [send array?]
             (vec (for [[c t] (second (sends send))
                        :let [coll (collection-schema c)
                              array (or (.isArray ^Class c) (and coll (or (= :unknown t) (types/element t))))]
                        :when (= array? (boolean (and array (not= c accept/bytes-class))))]
                    [(if (and array coll) coll (class-schema c)) t])))))

(defn write-rows
  "[schema type] for each class send-map sends, in pgjdbc's order (accept/class-types) and then
   by name, by array? an array argument's (a Clojure
   collection or a Java array sent as an array type or untyped, the collection's elements
   :pgmalli/element) or another's."
  [opts array?]
  (write-rows* (:send opts) array?))

(defn sent-type
  "The type a literal value goes to PostgreSQL as: :unknown under sql/format's :inline, which
   writes it into the SQL; otherwise as send-map sends its class, nil for a class it does not
   list."
  [value {:keys [format] :as opts}]
  (if (:inline format)
    :unknown
    (let [[m entries] (sends (:send opts))]
      ;; its class's entry, else its superclasses' and interfaces' in pgjdbc's order (write-rows's)
      (or (get m (class value))
          (some (fn [[c t]] (when (instance? c value) t)) entries)))))

(defn string-sends
  "The types a String is sent as: send-map's for String and Character."
  [opts]
  (vec (distinct (keep (first (sends (:send opts))) [String Character]))))

(defn- pg-type [s] (:pg/type (shape/props s)))

(defn- array-column?
  "An array column's schema: [:vector {:pg/type \"text[]\"} :string]."
  [s]
  (and (vector? s) (= :vector (first s)) (types/element (pg-type s))))

(declare read-type)

(defn- element
  "An array column's element schema, with the element's :pg/type where pgjdbc returns that type
   otherwise than the registry's schema says (a citext[]'s PGobjects, a bit[]'s Booleans)."
  [schema]
  (let [e (last schema)
        t (types/element (pg-type schema))
        entry (types/entry (shape/type-name {:pg/type t}))]
    (if (and (nil? (pg-type e)) (or (:read entry) (= :any (:schema entry))))
      (shape/merge-props e {:pg/type t})
      e)))

(defn- driver-type
  "What read-type and write-type share: dates and timestamps as the one reading makes them, or,
   pgjdbc?, as pgjdbc returns them (inst?); arrays and json as the one reading makes them, or
   the driver's objects (:any); intervals the driver's objects."
  [schema pgjdbc?]
  (let [driver-object [:any {:pg/type (pg-type schema)}]]
    (cond (array-column? schema) (if pgjdbc? driver-object [:vector {:pg/type (pg-type schema)} [:maybe (read-type (element schema) false)]])
          (types/json? (pg-type schema)) (if pgjdbc? driver-object schema)
          (= :time/duration (if (vector? schema) (first schema) schema)) driver-object
          :else (shape/read-time (if pgjdbc? :inst :instant) schema))))

(defn read-type
  "A column's schema (without NULL) as the one reading (docs/reading-rows.md) makes it: dates a
   java.sql.Date, timestamps (with and without time zone) an Instant, an array a vector of its
   elements so read, json and jsonb parsed as the registry shapes them; or, pgjdbc?, as pgjdbc
   returns them with no extension (dates and timestamps inst?, an array a java.sql.Array, json a
   PGobject). Either way: time and timetz a java.sql.Time, an interval a PGInterval, citext,
   bit(n > 1) and bit varying a PGobject (these :any), real a Float, bit(1) a Boolean, numeric
   a Double for NaN."
  [schema pgjdbc?]
  (let [t (pg-type schema)
        head (if (vector? schema) (first schema) schema)
        ;; a domain's base comes with its typmod: "bit(4)"
        driver-read (when-not (array-column? schema) (:read (types/entry (shape/type-name {:pg/type t}))))]
    (cond (= :bit driver-read) (if (or (= 1 (:max (second schema))) (str/ends-with? t "(1)")) [:boolean {:pg/type t}] [:any {:pg/type t}])
          (= :or-double driver-read) [:or (driver-type schema pgjdbc?) ['double? {:pg/type t}]]
          driver-read [driver-read {:pg/type t}]
          ;; the driver's own object, whatever an override says the value is
          (and (= :any (:schema (types/entry t))) (not (types/json? t)) (not (array-column? schema))) [(if (= :some head) :some :any) {:pg/type t}]
          (#{:time/local-time :time/offset-time} head) ['inst? {:pg/type t}]
          :else (driver-type schema pgjdbc?))))

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
                   (cond (and elem (accept/answers? elem)) (accept/holds? elem (types/element sent))
                         ;; an enum type's array takes an array of that type alone; a domain reads as its base
                         (and p (not elem) (= :enum (shape/kind (shape/non-null t)))) (= p (types/element sent))
                         :else true)))
        ;; a typed array's elements are also the values of its element type, where the column
        ;; takes them as its own (a bigint[]'s Longs to a numeric[] column)
        element (fn [sent]
                  (let [x (when (string? sent) (types/element sent))
                        ;; an element of no known type (an opaque column's) stays so
                        e (when (and x elem (not= x elem)) (types/entry x))]
                    (if e
                      (let [;; pgmalli's integer types are integers, as :int is
                            kind #(let [k (shape/kind %)] (if (pgtypes/int-ranges k) :int k))
                            own (set (map kind (shape/alternatives (shape/non-null t))))
                            more (remove #(own (kind %)) (shape/alternatives (write-type (types/type-schema (:name e)) opts)))]
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
    (array-type (write-type (last schema) opts) (assoc opts ::element-type (types/element (pg-type schema))) :assign)
    (let [t (driver-type schema true)
          n (some-> (base-type-name schema) types/entry :name)
          ;; labels (an enum's, a CHECK's list) are the column's values; an argument's type
          ;; keeps those a class sent goes as (pgmalli.honeysql's labelled-type)
          sent (for [[s sent] (write-rows opts false) :when (and (= n sent) (not= :enum (shape/kind t)))
                     a (shape/alternatives s)]
                 a)]
      (cond (empty? sent) t
            ;; a json column an override or a CHECK shapes: what is sent as its type is held to that shape
            (and (types/json? (pg-type schema)) (not (#{:any :some} (shape/head schema)))) schema
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

(defn row-key
  "The key next.jdbc's as-unqualified-maps gives a column labelled column: its name."
  [column]
  (keyword column))

(defn row-entry
  "A row map's entry for a column read as inner, NULL a [:maybe]."
  ([k inner nullable?] (row-entry k nil inner nullable?))
  ([k props inner nullable?]
   (let [s (if nullable? [:maybe inner] inner)]
     (if (empty? props) [k s] [k props s]))))

(defn- data-columns
  "The row map of a generated schema as data (columns gives the malli schema)."
  [registry name]
  (let [s (shape/schema-of registry name)]
    (when-not (shape/read-row? s)
      (shape/not-a-row! registry name "as-read"))
    (shape/row-map s)))

(def ^:private no-sends (zipmap (map first accept/class-types) (repeat nil)))

(defn- through-domain
  "A column of a domain whose type the driver returns as something else (a date as a
   java.sql.Date, a citext as a PGobject) as that type, without the domain's CHECKs, which read
   the registry's types; a domain the driver returns (or, f a write type, takes) as the registry
   holds it stays a reference. f is called with nothing sent, as sends add values to the base
   type and do not change how the driver reads it."
  [schemas s f]
  (if-let [d (shape/ref-target schemas s)]
    (let [base (through-domain schemas (shape/domain-base d) f)]
      (if (= base (f base))
        s
        (shape/merge-props (shape/without-gen base) (dissoc (shape/column-props s) :pg/type))))
    s))

(defn- column-read-type*
  [schemas s pgjdbc?]
  (let [;; the driver returns an array's elements of a domain (but an enum type's) as PGobjects;
        ;; a domain that is a CHECK's list alone is an [:enum] of its base's :pg/type
        x (when (array-column? s) (last s))
        target (shape/ref-target schemas x)
        enum-type? (and (= :enum (shape/head target)) (nil? (pg-type target)))
        s (if (and target (not enum-type?)) (assoc s (dec (count s)) [:any {:pg/type (pg-type x)}]) s)]
    (read-type (through-domain schemas s #(read-type % pgjdbc?)) pgjdbc?)))

(defn column-read-type
  "read-type of a column schema (without NULL) of a registry's schemas under the one reading, a
   domain read as its base is. opts are a write type's, which the one reading does not read."
  [schemas s _opts]
  (column-read-type* schemas s false))

(defn- column-write-type*
  "write-type of a column schema (without NULL) of a registry's schemas, a domain taken as the
   driver takes its base."
  [schemas s opts]
  (let [;; an array's elements of an enum type are its labels: array-type takes an array of that type alone
        e (when (array-column? s) (last s))
        target (shape/ref-target schemas e)
        s (cond (nil? target) s
                (= :enum (shape/head target))
                (assoc s (dec (count s)) (into [:enum {:pg/type (pg-type e)}] (shape/entries target)))
                ;; a domain's array reads as its base type's, elements and all
                :else (if-let [base (base-type-name target)]
                        (-> s (assoc-in [1 :pg/type] (types/array-of base)) (assoc (dec (count s)) (shape/domain-base target)))
                        s))
        ;; a domain's value is sent as its base type's: the classes sent as that type, held to the
        ;; domain's checks, its definition in place of the reference
        d (shape/ref-target schemas (shape/non-null s))
        ;; (a number domain's: its checks read the number; another's may hold code only the registry runs)
        s (if (and d (some-> (base-type-name d) types/entry :name accept/number-type?)) d s)]
    (write-type (through-domain schemas s #(write-type % (assoc opts :send no-sends))) opts)))

(def pg-object
  "A PGobject or an instance of a subclass, by name: pgjdbc need not be loaded. pgjdbc sends it as
   the type its getType names, which is known at run time alone."
  (class-fn "org.postgresql.util.PGobject"
            (fn [x] (boolean (some #(= "org.postgresql.util.PGobject" (.getName ^Class %)) (take-while some? (iterate #(.getSuperclass ^Class %) (class x))))))))

(defn column-write-type
  "column-write-type*: a column pgjdbc returns as a PGobject (json, jsonb, citext, bit(n > 1))
   takes one too, so a value read without an extension is written back."
  [schemas s opts]
  (let [w (column-write-type* schemas s opts)
        r (column-read-type* schemas s true)
        t (pg-type r)]
    (if (and (not= :any (shape/head w)) (= :any (shape/head r)) t (not (types/element t))
             (or (types/json? t) (#{:any :bit} (:read (types/entry (shape/type-name {:pg/type t}))))))
      (shape/either (concat (shape/alternatives w) [pg-object]) w)
      w)))

(defn as-read
  "The [:map ...] of a row as next.jdbc's as-unqualified-maps builds it; the options are documented on
   pgmalli.core/as-read."
  [registry name opts]
  (options! opts)
  (let [m (shape/without-gen (data-columns registry name))
        props (when (map? (second m)) (second m))
        schemas (shape/schemas-of registry)
        entry (fn [[k p s]]
                (row-entry (row-key (clojure.core/name k)) p (column-read-type schemas (shape/non-null s) opts)
                           (shape/maybe? s)))]
    (into (if props [:map props] [:map]) (map entry (shape/column-entries m)))))
