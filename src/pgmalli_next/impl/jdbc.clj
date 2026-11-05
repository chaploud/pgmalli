(ns pgmalli-next.impl.jdbc
  "The JDBC side: what a column comes back as and what PostgreSQL takes for it under the one
   reading and sending (docs/reading-rows.md), and the row keys as-unqualified-maps gives.
   as-read and the query namespaces both read columns and key rows here."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [clojure.walk]
            [malli.core :as m]
            [malli.error :as me]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.pgtypes :as pgtypes]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

;;; the options

(defn- registered-dialect?
  "Whether sql/format takes dialect d."
  [d]
  (try (sql/format {:select [:a]} {:dialect d}) true (catch Exception _ false)))

(def ^:private format-entries
  "The keys of sql/format's options pgmalli reads (docs/queries.md#options), each with what
   sql/format takes of it: a dialect it registers, a regex."
  [[:quoted :boolean] [:inline :boolean] [:quoted-snake :boolean]
   [:dialect [:and :keyword [:fn {:error/message "should be a dialect HoneySQL registers (:ansi, :mysql, :oracle ...)"} registered-dialect?]]]
   [:quoted-always [:fn {:error/message "should be a regex (#\"^null$\")"} #(instance? java.util.regex.Pattern %)]]
   [:values-default-columns [:or set? sequential?]]])

(def ^:private option-entries
  "Each option pgmalli takes. :jdbc and :format are the maps an application passes to next.jdbc
   and sql/format, open: pgmalli reads the keys of them it names."
  {:schema [:schema {:optional true} :string]
   :jdbc [:jdbc {:optional true} [:map {:error/message "should be the map of options you pass to next.jdbc"}
                                  [:return-keys {:optional true}
                                   (let [m {:error/message "should be true, false or a vector of column names ([\"id\"], [:id])"}]
                                     [:or [:boolean m] [:vector (assoc m :min 1) [:or :string :keyword]]])]]]
   :result [:result {:optional true} [:enum :one]]
   :format [:format {:optional true} (into [:map {:error/message "should be the map of options you pass to sql/format"}]
                                           (for [[k v] format-entries] [k {:optional true} [:maybe v]]))]})

(def ^:private format-read (mapv first format-entries))

(defn checked!
  "opts (nil is none) when schema takes them; an error naming what it does not, a misspelt key
   with the one meant, otherwise. kind and label say whose they are (options by default).
   A config's :db, which may hold a password, is left out of the error, and so are the values of
   keys schema does not take (a misspelt :db is one)."
  ([schema opts] (checked! schema opts :pgmalli.error/invalid-options "pgmalli options"))
  ([schema opts kind label]
   (if (m/validate schema (or opts {}))
     opts
     (let [errors (if-let [shape (when-not (or (nil? opts) (map? opts)) (:pgmalli/not-a-map (m/properties schema)))]
                    [shape]
                    (clojure.walk/postwalk #(if (and (vector? %) (every? string? %)) (vec (distinct %)) %)
                                           (me/humanize (m/explain schema opts))))
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

;; compiled once per set of keys: malli keeps a schema's validator, and options are checked on every call
(def ^:private options-schema
  (memoize (fn [ks] (m/schema (into [:map {:closed true :pgmalli/not-a-map "should be a map of options"}] (map option-entries) ks)))))

;;; what an application sends

(defn- class-fn
  "[:fn] of the values of class name n, pred telling them."
  [n pred]
  [:fn {:pg/class n :error/message (str "should be an " n)} pred])

(def ^:private class-schema
  ;; memoized: a class's [:fn] is one function, so the schemas of one call equal the next's
  (memoize (fn [^Class c]
             (or (get (accept/row-of c accept/classes) 2)
                 ;; a Java array (but byte[], a bytea): :pg/array reads its elements by value, as the
                 ;; column's, which array-type puts in
                 (when (and (.isArray c) (not= c accept/bytes-class)) [:pg/array :pgmalli/element])
                 (class-fn (.getName c) (fn [x] (instance? c x)))))))

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

(def ^:private the-extension
  "https://github.com/chaploud/pgmalli-next/blob/main/docs/reading-rows.md#the-one-reading-and-sending")

(def ^:private one-reading (str "pgmalli types rows as the ReadableColumn extension of " the-extension " reads them"))

(def ^:private one-sending (str "pgmalli types arguments as the SettableParameter extension of " the-extension " sends them"))

(defn- the-builder?
  "Whether f is next.jdbc.result-set/as-unqualified-maps: the function, its var or its symbol,
   which names it where next.jdbc does not load (babashka)."
  [f]
  (let [sym 'next.jdbc.result-set/as-unqualified-maps
        v (try (requiring-resolve sym) (catch Exception _ nil))]
    (or (= sym f) (and v (or (= v f) (= @v f))))))

(defn options!
  "opts when the function they go to takes them, the options of ks (option-entries' keys, in
   order) closed: a misspelt key or value is an error rather than a check silently weakened. An
   error names what it does not take, with the one meant; an option of an earlier version what
   replaced it."
  [opts ks]
  (when-not (or (nil? opts) (map? opts)) (checked! (options-schema ks) opts))
  (let [fail! #(error/fail! :pgmalli.error/invalid-options (str "pgmalli options: " %1) {:given %2})]
    (when-let [old (not-empty (select-keys opts [:write :stringtype]))]
      (fail! (str (pr-str old) " is no longer read: " one-sending) old))
    (when-let [old (not-empty (select-keys opts [:qualified? :kebab? :lower? :nil-columns]))]
      (fail! (str (pr-str old) " is no longer read: " builder-only) old))
    (when-let [old (not-empty (select-keys opts [:time :read]))]
      (fail! (str (pr-str old) " is no longer read: " one-reading) old))
    (when (contains? opts :compare)
      (fail! ":compare is no longer read: a compared argument takes no nil; compare NULL with [:= :x nil] or IS NULL in the query body"
             (select-keys opts [:compare])))
    (when (contains? opts :return-keys)
      (fail! ":return-keys is no longer read: pass it as {:jdbc {:return-keys ...}}"
             (select-keys opts [:return-keys])))
    (let [jdbc (:jdbc opts)]
      (doseq [k (when (map? jdbc) (keys jdbc))
              :let [meant (when-not (next-jdbc-options k) (error/suggest k jdbc-read))]
              :when meant]
        (error/fail! :pgmalli.error/invalid-options
                     (str "pgmalli options: :jdbc's " (pr-str k) " is no next.jdbc option" (error/did-you-mean meant))
                     {:given [k] :did-you-mean [meant]}))
      ;; a key close to one pgmalli reads, misspelt, would change how names resolve without a word
      (doseq [k (when (map? (:format opts)) (keys (:format opts)))
              :let [meant (when-not (some #{k} format-read) (error/suggest k format-read))]
              :when meant]
        (error/fail! :pgmalli.error/invalid-options
                     (str "pgmalli options: :format's " (pr-str k) " is no sql/format option pgmalli reads" (error/did-you-mean meant))
                     {:given [k] :did-you-mean [meant]}))
      (when-let [given (not-empty (select-keys (when (map? jdbc) jdbc) [:builder-fn :label-fn :qualifier-fn]))]
        (when-not (and (= [:builder-fn] (keys given)) (the-builder? (:builder-fn given)))
          (fail! (str ":jdbc's " (str/join ", " (keys given)) " is not read: " builder-only)
                 given))))
    (checked! (options-schema ks) opts)
    opts))

(def ^:private sends
  "{class type} the one sending sends (accept/class-types) but a Character, which a char? on
   every string argument would say nothing of: it is taken by a character column
   alone (::characters)."
  (dissoc (into {} accept/class-types) Character))

(defn- rows-of
  "{array? [[schema type class] ...]} of [class type] rows, in their order: an array argument's (a
   Java array but byte[]) and another's."
  [rows]
  (group-by (fn [[s]] (= [:pg/array :pgmalli/element] s))
            (for [[c t] rows] [(class-schema c) t c])))

(def ^:private sent-rows (rows-of (filter (comp sends first) accept/class-types)))

(def ^:private character-rows (rows-of accept/class-types))

(def ^:private json-rows (rows-of accept/json-classes))

(defn write-rows
  "[schema type class] for each class the one sending sends, in accept/class-types' order, by array?
   an array argument's (a Java array, :pgmalli/element in its elements' place) or another's;
   none under ::nothing-sent, where a type is taken as the driver reads it alone; under ::json,
   a value of a JSON document as accept/json-classes writes it, no array; a Character under
   ::characters alone (sends)."
  [opts array?]
  (cond (::nothing-sent opts) []
        (::json opts) (if array? [] (get json-rows false []))
        (::characters opts) (get character-rows array? [])
        :else (get sent-rows array? [])))

(defn sent-as
  "The alternatives of the schemas of the classes, no array, the one sending sends as type t; with
   keep?, of those classes keep? takes (accept/reads?)."
  ([opts t] (sent-as opts t (constantly true)))
  ([opts t keep?]
   (distinct (for [[s q c] (write-rows opts false) :when (and (= t q) (keep? c))
                   a (shape/alternatives s)]
               a))))

(defn sent-type
  "The type a literal value goes to PostgreSQL as: :unknown under sql/format's :inline, which
   writes it into the SQL; otherwise as the one sending sends its class, nil for a class it does
   not send."
  [value {:keys [format]}]
  (if (:inline format)
    :unknown
    (some-> (class value) (accept/row-of (filter (comp sends first) accept/classes)) second)))

(defn string-sends
  "The types a String is sent as."
  [opts]
  (vec (distinct (for [[_ t c] (write-rows opts false) :when (= String c)] t))))

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
   LocalDate, times a LocalTime, a timestamp a LocalDateTime, a timestamptz an Instant, an array a vector of its
   elements so read, json and jsonb parsed as the registry shapes them; or, pgjdbc?, as pgjdbc
   returns them with no extension (dates and timestamps inst?, an array a java.sql.Array, json a
   PGobject, time a java.sql.Time). Either way: timetz a java.sql.Time, an interval a PGInterval, citext,
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
          (= :time/offset-time head) ['inst? {:pg/type t}]
          :else (driver-type schema pgjdbc?))))

(declare write-type)

(def pg-object
  "A PGobject or an instance of a subclass, by name: pgjdbc need not be loaded. pgjdbc sends it as
   the type its getType names, which is known at run time alone."
  (class-fn "org.postgresql.util.PGobject"
            (fn [x] (boolean (some #(= "org.postgresql.util.PGobject" (.getName ^Class %)) (take-while some? (iterate #(.getSuperclass ^Class %) (class x))))))))

(defn- value-kind
  "The kind of alternative a as a value: pgmalli's integer types (:pg/integer) an :int, as malli's is."
  [a]
  (let [k (shape/kind a)] (if (pgtypes/int-ranges k) :int k)))

(defn array-type
  "An array argument of elements t, used as use (:compare, = ANY(?); :whole, compared with an
   array column; :assign, to an array column; :common, beside an array column in coalesce or
   CASE; :unnest, a column of unnest(?), assigned or compared): the Java arrays the one sending sends (write-rows), t in place of :pgmalli/element, of those PostgreSQL takes
   there: an untyped one but under unnest (unnest(unknown) is ambiguous), a typed one whose
   element type is one of t's own (accept/holds?, assigned; under :common, as coalesce resolves it; compared, t is what a comparison takes already), its elements
   then also that type's values (a bigint[]'s Longs to a numeric[] column); of an enum type,
   an array of that type alone; where t takes a PGobject (of a type known at run time alone), a
   PGobject too. ::element-type in opts names t's type where t's schema names none (numeric[]'s
   decimal?). :any without one."
  [t opts use]
  ;; t may take NULL (a nullable column's elements); its type is the one without
  ;; an array column names its elements' type (a domain's array its base's), which goes first
  (let [p (or (::element-type opts) (some pg-type (shape/alternatives (shape/non-null t))))
        elem (some-> p types/entry :name)
        takes? (fn [sent]
                 (if (= :unknown sent)
                   (not= :unnest use)
                   (cond ;; a whole array compared (=, &&, @>): anyarray's operators take an array of its type alone
                         (and elem (= :whole use)) (= elem (some-> (types/element sent) types/entry :name))
                         (and elem (accept/answers? elem)) (accept/holds? elem (types/element sent) (if (= :common use) :common :assign))
                         ;; an enum type's array takes an array of that type alone; a domain reads as its base
                         (and p (not elem) (= :enum (shape/kind (shape/non-null t)))) (= p (types/element sent))
                         :else true)))
        untyped (set (map shape/kind (sent-as opts :unknown)))
        ;; a typed array's elements are its type's, never a scalar sent untyped (a String's, where
        ;; t takes one): t without those; a Java array's are its component class's (a Timestamp[]'s
        ;; java.sql.Timestamps, inst?), which t takes too
        typed (fn [x ^Class c]
                (let [cs (some-> c accept/element-schema shape/alternatives)
                      own (set (map shape/kind (or cs (some-> x types/entry :name types/type-schema (write-type opts) shape/alternatives))))
                      strip (fn [s]
                              (let [as (shape/alternatives s)
                                    kinds (set (map value-kind as))]
                                (shape/either (concat (remove (fn [a] (and (untyped (shape/kind a)) (not (own (shape/kind a))))) as)
                                                      (remove (comp kinds value-kind) cs))
                                              s)))]
                  (if (shape/maybe? t) [:maybe (strip (shape/non-null t))] (strip t))))
        ;; a typed array's elements are also the values of its element type, where the column
        ;; takes them as its own (a bigint[]'s Longs to a numeric[] column)
        element (fn [sent c]
                  (let [x (when (string? sent) (types/element sent))
                        t (if (= :unknown sent) t (typed x c))
                        ;; an element of no known type (an opaque column's) stays so
                        e (when (and x elem (not= x elem)) (types/entry x))]
                    (if e
                      (let [own (set (map value-kind (shape/alternatives (shape/non-null t))))
                            more (remove #(own (value-kind %)) (shape/alternatives (write-type (types/type-schema (:name e)) opts)))]
                        (shape/either (distinct (concat (shape/alternatives t) more)) t))
                      t)))
        alts (distinct (for [[s sent c] (write-rows opts true) :when (takes? sent)
                             a (shape/alternatives s)]
                         (cond-> (clojure.walk/postwalk-replace {:pgmalli/element (element sent c)} a)
                           ;; elements alike (a long[]'s and an Integer[]'s), the array's class tells the type sent
                           (and (= :whole use) (instance? Class c) (= :pg/array (shape/head a)))
                           (shape/merge-props {:pg/classes #{(.getName ^Class c)}}))))
        ;; arrays of like elements (a long[] and a Long[]) one alternative, of both classes
        alts (if (= :whole use)
               (let [like #(if (and (= :pg/array (shape/head %)) (map? (second %))) (update % 1 dissoc :pg/classes) %)]
                 (for [[_ as] (group-by like alts)]
                   (if (next as) (shape/merge-props (first as) {:pg/classes (into #{} (mapcat (comp :pg/classes shape/props)) as)}) (first as))))
               alts)]
    (cond (seq alts) (shape/either (cond-> alts (some #{pg-object} (shape/alternatives (shape/non-null t))) (concat [pg-object])) :any)
          ;; no Java array of the type (text[]'s, String[] going as varchar[]; an enum's): a PGobject
          ;; of it, which PostgreSQL takes as the array a comparison infers, its elements t's
          (and p (#{:whole :compare} use))
          (let [a (types/short-name (types/array-of (or elem p)))]
            (shape/merge-props pg-object
                               {:error/message (str "should be a PGobject of type " a " (no Java array goes as " a ", which "
                                                    (if (= :whole use) "a comparison of arrays" "= ANY(?)")
                                                    " takes alone), or the argument cast in the query: [:cast <the argument> [:raw \"" a "\"]]")
                                :pgmalli/describe (str "PGobject of " a)
                                :pgmalli/element (shape/non-null t)}))
          :else :any)))

(defn- base-type-name
  "The type a column schema names, on itself or on the base of its [:and base checks...]
   (numeric(10,2) keeps it on its decimal?), without typmod or brackets."
  [schema]
  (some #(shape/type-name (shape/column-props %)) [schema (shape/domain-base schema)]))

(def ^:private numbers
  "The kinds of the number classes a CHECK on a number column reads: the number types' schemas'
   and the number classes'."
  (into (set (for [[t e] types/types :when (accept/number-type? t)] (shape/kind (:schema e))))
        (for [[_ t s] accept/classes :when (accept/number-type? t)] (shape/kind s))))

(defn text-held
  "Alternative a of a keyword or a Character, held to string column schema t (its length, a
   CHECK's) through the text it goes as (:pg/text); a as it is for another a, or a t that checks
   nothing beyond being a string."
  [a t]
  (let [k (shape/kind a)
        u (if (= :pg/held (shape/head t)) (last t) t)]
    (if (and (#{:keyword 'char?} k)
             (= :string (shape/kind (shape/domain-base u)))
             (or (not= u (shape/domain-base u)) (some #{:max :min} (keys (shape/props u)))))
      [:and a [:pg/text t]]
      a)))

(defn- typmod-of
  "{:pg/max-length n :pg/scale s :pg/precision p} of column schema t's typmod, as :pg/held reads
   it: a varchar(n)'s or char(n)'s length, a numeric(p,s)'s precision and scale; :pg/cut? under
   cut? (a cast, which cuts a string to n); nil for none."
  [t cut?]
  (let [nodes (filter #(and (vector? %) (map? (second %))) (tree-seq vector? seq t))
        n (some #(when (#{:string :enum} (first %)) (:pg/max-length (second %))) nodes)
        num (some #(when (= :pg/numeric (first %)) (second %)) nodes)]
    (cond num {:pg/precision (:precision num) :pg/scale (:scale num 0)}
          n (cond-> {:pg/max-length n} cut? (assoc :pg/cut? true)))))

(defn typmod-held
  "Argument schema t (alternatives) of a column of typmod (typmod-of), each alternative a value
   of that typmod reads otherwise once the column holds it in [:pg/held typmod a]: a number
   under a scale; a string, under a length, where a CHECK reads it or a cast cuts it (a plain
   varchar(n)'s :pg/max-length cuts its spaces already)."
  [t typmod]
  (if-not typmod
    t
    (let [number? (fn [a] (every? (comp numbers shape/kind) (shape/alternatives (shape/domain-base a))))
          checked? (fn [a] (or (= :enum (shape/kind a)) (and (= :and (shape/head a)) (next (shape/entries a)))))
          held (fn [a] (cond (= :pg/held (shape/head a)) a
                             (and (:pg/scale typmod) (number? a)) [:pg/held typmod a]
                             (and (:pg/max-length typmod) (#{:string :enum} (shape/kind a)) (or (:pg/cut? typmod) (checked? a))) [:pg/held typmod a]
                             :else a))]
      (if (shape/maybe? t)
        [:maybe (shape/either (map held (shape/alternatives (shape/non-null t))) (shape/non-null t))]
        (shape/either (map held (shape/alternatives t)) t)))))

(defn write-type
  "A column's schema (without NULL) as an argument the driver takes: an array column's
   argument is an array of its elements' (array-type, used as ::array-use, :assign by default); otherwise the types read-type shares
   with it, and what the one sending sends as its type, which is all of it
   where read-type has the driver's object (:any)."
  [schema opts]
  (if (array-column? schema)
    ;; the elements' type is the column's, where their schema names none (numeric[]'s decimal?)
    (array-type (write-type (last schema) opts) (assoc opts ::element-type (types/element (pg-type schema))) (::array-use opts :assign))
    (let [k (shape/kind schema)
          t (driver-type schema true)
          ;; a date or time column takes the java.time value its type is, which the one reading
          ;; reads it as; a timetz one also the java.sql.Time the one reading leaves it as (inst?)
          t (cond (= :time/offset-time k) (shape/update-base t #(shape/either (distinct [(shape/domain-base schema) %]) %))
                  (and (keyword? k) (= "time" (namespace k)) (not= :time/duration k)) (shape/update-base t (constantly (shape/domain-base schema)))
                  :else t)
          n (some-> (base-type-name schema) types/entry :name)
          ;; labels (an enum's, a CHECK's list) are the column's values; an argument's type
          ;; keeps those a class sent goes as (pgmalli-next.impl.args/labelled-type)
          sent (when (and n (not= :enum (shape/kind t))) (sent-as opts n))
          typmod (when-not (= :none (::typmod opts)) (typmod-of schema (= :cut (::typmod opts))))]
      (typmod-held
       (cond (empty? sent) t
            ;; a json column an override or a CHECK shapes: what is sent as its type is held to that shape
            (and (types/json? (pg-type schema)) (not (#{:any :some} (shape/head schema)))) schema
            (= :any (shape/head t)) (shape/either sent t)
            :else
            (let [base (shape/domain-base t)
                  ;; a kind the base has already takes its bounds (a varchar(n)'s length)
                  more (remove #(= (value-kind %) (value-kind base)) sent)
                  ;; a CHECK reads a number as the number it is (a BigInt's > 0); a keyword or a
                  ;; Character as the text it goes as (text-held)
                  [held free] ((juxt filter remove) #(and (numbers (shape/kind base)) (numbers (shape/kind %))) more)]
              (shape/either (concat (shape/alternatives (shape/update-base t #(shape/either (distinct (cons % held)) %)))
                                    (map #(text-held % t) free))
                            t)))
       typmod))))

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

(defn- through-domain
  "A column of a domain whose type the driver returns as something else (a date as a
   java.sql.Date, a citext as a PGobject) as that type, without the domain's CHECKs, which read
   the registry's types; a domain pgjdbc returns (or, where f is write-type, takes) as the registry
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
        ;; (a number domain's: its checks read the number; a date and time one's, of a type more than
        ;; one class goes as (a timestamptz an Instant and an OffsetDateTime); one of a typmod's, which
        ;; holds the value before its checks read it (typmod-held); another's may hold code only the
        ;; registry runs)
        s (if (and d (or (some-> (base-type-name d) types/entry :name accept/number-type?)
                         (some-> (shape/kind (shape/domain-base d)) (#(and (keyword? %) (= "time" (namespace %)))))
                         (and (not= :none (::typmod opts)) (typmod-of d false))))
            d s)]
    (write-type (through-domain schemas s #(write-type % (assoc opts ::nothing-sent true))) opts)))

(def untyped-kinds
  "The kinds of the values the one sending sends untyped (a keyword, an Instant ...)."
  (set (for [rows (vals sent-rows) [s t] rows :when (= :unknown t)] (shape/kind s))))

(defn typed-where
  "The values the one sending sends with a type of their own (nil and a value sent untyped are
   not) whose type takes? takes, an array's elements any, and a PGobject, which names its own:
   what a parameter PostgreSQL takes the type of from the value alone takes (a function's
   \"any\" or unbound polymorphic argument)."
  [takes?]
  (shape/either (distinct (concat (for [rows (vals sent-rows) [s t] rows :when (and (not= :unknown t) (takes? t))]
                                    (clojure.walk/postwalk-replace {:pgmalli/element :any} s))
                                  [pg-object]))
                :any))

(defn column-write-type
  "column-write-type*: a column pgjdbc returns as a PGobject (json, jsonb, citext, bit(n > 1), bit varying)
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
   pgmalli-next.core/as-read."
  [registry name opts]
  (let [m (shape/without-gen (data-columns registry name))
        props (when (map? (second m)) (second m))
        schemas (shape/schemas-of registry)
        entry (fn [[k p s]]
                (row-entry (row-key (clojure.core/name k)) p (column-read-type schemas (shape/non-null s) opts)
                           (shape/maybe? s)))]
    (into (if props [:map props] [:map]) (map entry (shape/column-entries m)))))
