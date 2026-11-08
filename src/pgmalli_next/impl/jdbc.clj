(ns pgmalli-next.impl.jdbc
  "The JDBC side: what a column comes back as and what PostgreSQL takes for it under the one
   reading and sending (pgmalli-next.jdbc), and the row keys its builder gives.
   as-read and the query namespaces both read columns and key rows here."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [clojure.walk]
            [malli.core :as m]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.memo :as memo]
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
   [:values-default-columns [:or set? sequential?]]
   ;; false: [:= a nil] is a = NULL, not a IS NULL
   [:transform-null-equals :boolean]])

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

(def ^:private one-pattern
  "The regex of a text and flags, one per text: a Pattern equals none but itself."
  (memo/bounded (fn [^String s flags] (java.util.regex.Pattern/compile s (int flags)))))

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
  (str "pgmalli types rows as pgmalli-next.jdbc/builder builds them, keyed by the columns' names;"
       " pass it to next.jdbc as {:builder-fn (pgmalli-next.jdbc/builder registry)}"))

(def ^:private reading-rows-doc
  "https://github.com/chaploud/pgmalli-next/blob/main/docs/reading-rows.md#the-one-reading-and-sending")

(def ^:private one-reading (str "pgmalli types rows as pgmalli-next.jdbc/builder reads them (" reading-rows-doc ")"))

(def ^:private one-sending (str "pgmalli types arguments as pgmalli-next.jdbc/install-sending! sends them (" reading-rows-doc ")"))

(defn- the-builder?
  "Whether f is a builder pgmalli-next.jdbc/builder made: it carries the names of the enums it reads
   as keywords (pgmalli-next.jdbc does not load where pgjdbc is not)."
  [f]
  (set? (:pgmalli-next.jdbc/builder (meta f))))

(defn builder!
  "opts when their :jdbc's :builder-fn, if any, reads the enums of registry as keywords: one of a
   registry of other enums reads an enum it lacks as a String, which the types say is a keyword."
  [registry opts]
  (when-let [enums (:pgmalli-next.jdbc/builder (meta (:builder-fn (:jdbc opts))))]
    (let [ours (shape/enum-type-names registry)]
      (when (not= enums ours)
        (error/fail! :pgmalli.error/invalid-options
                     (str "pgmalli options: :jdbc's :builder-fn was made of a registry of other enums ("
                          (str/join ", " (sort (into (remove ours enums) (remove enums ours))))
                          "): pass (pgmalli-next.jdbc/builder registry) of the registry the rows are typed with")
                     {:given (sort (into (remove ours enums) (remove enums ours)))}))))
  opts)

(defn options!
  "opts when the function they go to takes them, the options of ks (option-entries' keys, in
   order) closed: a misspelt key or value is an error rather than a check silently weakened. An
   error names what it does not take, with the one meant; an option of no meaning to pgmalli
   (:write, :stringtype, :compare ...), what does that work instead."
  [opts ks]
  (when-not (or (nil? opts) (map? opts)) (error/checked! (options-schema ks) opts))
  (let [fail! #(error/fail! :pgmalli.error/invalid-options (str "pgmalli options: " %1) {:given %2})]
    (when-let [old (not-empty (select-keys opts [:write :stringtype]))]
      (fail! (str (pr-str old) " is not read: " one-sending) old))
    (when-let [old (not-empty (select-keys opts [:qualified? :kebab? :lower? :nil-columns]))]
      (fail! (str (pr-str old) " is not read: " builder-only) old))
    (when-let [old (not-empty (select-keys opts [:time :read]))]
      (fail! (str (pr-str old) " is not read: " one-reading) old))
    (when (contains? opts :compare)
      (fail! ":compare is not read: a compared argument takes no nil; compare NULL with [:= :x nil] or IS NULL in the query body"
             (select-keys opts [:compare])))
    (when (contains? opts :return-keys)
      (fail! ":return-keys is not read: pass it as {:jdbc {:return-keys ...}}"
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
      (when-let [given (not-empty (cond-> (select-keys (when (map? jdbc) jdbc) [:builder-fn :label-fn :qualifier-fn])
                                    (the-builder? (:builder-fn jdbc)) (dissoc :builder-fn)))]
        (fail! (str ":jdbc's " (str/join ", " (keys given)) " is not read: " builder-only)
               given)))
    (error/checked! (options-schema ks) opts)
    ;; :format as the keys pgmalli reads, one regex per text: what is memoized by it stays bounded
    ;; whatever else an application's sql/format options hold (:params)
    (cond-> opts
      (map? (:format opts)) (update :format #(cond-> (select-keys % format-read)
                                               (:quoted-always %) (update :quoted-always (fn [^java.util.regex.Pattern p] (one-pattern (.pattern p) (.flags p)))))))))

(def ^:private inline-integers
  "The types PostgreSQL gives an integer literal, in order: the first whose range (types/types'
   :range) holds it, else numeric (make_const)."
  ["integer" "bigint"])

(defn inline-type
  "The type PostgreSQL gives the literal sql/format's :inline writes value v as: a quoted text
   (a String's, a UUID's) :unknown, TRUE or FALSE a boolean, an integer the first of
   inline-integers whose range holds it, else numeric, another number numeric; nil where it
   writes no literal (a keyword as a name, a date or a map as its text unquoted, NaN and
   Infinity as names)."
  [v]
  (let [s (try (first (sql/format-expr [:inline v])) (catch Exception _ nil))]
    (cond (not (string? s)) nil
          (str/starts-with? s "'") :unknown
          (#{"TRUE" "FALSE"} s) "boolean"
          (re-matches #"-?\d+" s) (let [n (bigint s)]
                                    (or (some #(let [[lo hi] (:range (types/types %))] (when (<= lo n hi) %)) inline-integers) "numeric"))
          (re-matches #"-?(\d+\.?\d*|\.\d+)([eE][-+]?\d+)?" s) "numeric")))

(defn inline-finite
  "Alternative a of a Double or a Float, held to the values sql/format's :inline writes as a
   literal (inline-type): finite, within a double's bounds, which NaN and the infinities, written
   as names, are not."
  [a]
  (let [[lo hi] [(- Double/MAX_VALUE) Double/MAX_VALUE]]
    (cond (and (= :double (shape/head a)) (not-any? (or (shape/props a) {}) [:min :max])) (shape/merge-props a {:min lo :max hi})
          (= :double (shape/head a)) a
          :else [:and a [:>= lo] [:<= hi]])))

(def inline-classes
  "[class type schema] of the values sql/format's :inline writes into the SQL as a literal, as
   inline-type types them, in accept/class-types' order: a String and a UUID untyped; an integer as
   each type of inline-integers within its range (a schema tells no class: an Integer's too), and as numeric where none holds it; a
   BigDecimal of scale 0 (1M, written 1) as an integer alike, another numeric; a Double and a
   Float numeric, finite (inline-finite); a boolean a boolean."
  (let [whole [:fn {:error/message "should be of scale 0 (1M), which sql/format's :inline writes as an integer"} #(zero? (.scale ^java.math.BigDecimal %))]
        ranged (fn [c base f]
                 (for [t inline-integers :let [[lo hi] (:range (types/types t))]]
                   [c t (if (= :int base)
                          [:int {:min lo :max hi}]
                          (into [:and {:gen/schema [:int {:min lo :max hi}] :gen/fmap f} base] (concat (when (= 'decimal? base) [whole]) [[:>= lo] [:<= hi]])))]))
        rows {String [[String :unknown :string]]
              java.util.UUID [[java.util.UUID :unknown :uuid]]
              Long (ranged Long :int nil) Integer (ranged Integer :int nil) Short (ranged Short :int nil) Byte (ranged Byte :int nil)
              clojure.lang.BigInt (concat (ranged clojure.lang.BigInt 'integer? bigint) [[clojure.lang.BigInt "numeric" 'integer?]])
              java.math.BigInteger (concat (ranged java.math.BigInteger 'integer? biginteger) [[java.math.BigInteger "numeric" 'integer?]])
              java.math.BigDecimal (concat (ranged java.math.BigDecimal 'decimal? bigdec) [[java.math.BigDecimal "numeric" 'decimal?]])
              Double [[Double "numeric" (inline-finite :double)]]
              Float [[Float "numeric" (inline-finite 'float?)]]
              Boolean [[Boolean "boolean" :boolean]]}]
    (vec (for [[c] accept/class-types r (rows c)] r))))

(def ^:private sends
  "{class type} the one sending sends (accept/class-types) but a Character, which a char? on
   every string argument would say nothing of: it is taken by a character column
   alone (::characters)."
  (dissoc (into {} accept/class-types) Character))

(defn- rows-of
  "{array? [[schema type class] ...]} of [class type schema?] rows, in their order, schema the
   row's or its class's: an array argument's (a Java array but byte[]) and another's."
  [rows]
  (group-by (fn [[s]] (= [:pg/array :pgmalli/element] s))
            (for [[c t s] rows] [(or s (class-schema c)) t c])))

(def ^:private sent-rows (rows-of (filter (comp sends first) accept/class-types)))

(def ^:private character-rows (rows-of accept/class-types))

(def ^:private json-rows (rows-of accept/json-classes))

(def ^:private inline-rows (rows-of inline-classes))

(defn write-rows
  "[schema type class] for each class the one sending sends, in accept/class-types' order, by array?
   an array argument's (a Java array, :pgmalli/element in its elements' place) or another's;
   under ::json, a value of a JSON document as accept/json-classes writes it, no array; under
   sql/format's :inline, a value it writes as a literal (inline-classes), no array; a Character
   under ::characters alone (sends)."
  [opts array?]
  (cond (::json opts) (if array? [] (get json-rows false []))
        (:inline (:format opts)) (get inline-rows array? [])
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
  "The type the one sending sends a literal value's class as, nil for a class it does not send;
   under sql/format's :inline, which sends no parameter, :unknown (inline-type gives the type
   PostgreSQL gives the literal written)."
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
  "A column's schema (without NULL) as the one reading (pgmalli-next.jdbc/builder) makes it: dates a
   LocalDate, times a LocalTime, a timetz an OffsetTime, a timestamp a LocalDateTime, a timestamptz an Instant, an array a vector of its
   elements so read, json and jsonb parsed as the registry shapes them; or, pgjdbc?, as pgjdbc's
   getObject returns them (dates, timestamps and times inst?, an array a java.sql.Array, json a
   PGobject). Either way: an interval a PGInterval, citext,
   bit(n > 1) and bit varying a PGobject (these :any), real a Float, bit(1) a Boolean, numeric
   a Double for NaN and the infinities."
  [schema pgjdbc?]
  (let [t (pg-type schema)
        head (if (vector? schema) (first schema) schema)
                driver-read (when-not (array-column? schema) (:read (types/entry (shape/type-name {:pg/type t}))))]
    (cond (= :bit driver-read) (if (or (= 1 (:pg/max-length (shape/column-props schema))) (str/ends-with? t "(1)")) [:boolean {:pg/type t}] [:any {:pg/type t}])
          ;; a domain's name on the :or, where the type is read
          (= :or-double driver-read) (let [d (:pg/domain (shape/props schema))]
                                       (cond-> [:or (driver-type (cond-> schema d (update 1 dissoc :pg/domain)) pgjdbc?) ['double? {:pg/type t}]]
                                         d (shape/merge-props {:pg/domain d})))
          ;; money: a PGobject of its text (types/types' :read), the driver's object
          (= :text driver-read) [(if (= :some head) :some :any) {:pg/type t}]
          driver-read [driver-read {:pg/type t}]
          ;; the driver's own object, whatever an override says the value is
          (and (= :any (:schema (types/entry t))) (not (types/json? t)) (not (array-column? schema))) [(if (= :some head) :some :any) {:pg/type t}]
          :else (driver-type schema pgjdbc?))))

(declare write-type)

(def pg-object
  "A PGobject or an instance of a subclass, by name: pgjdbc need not be loaded. pgjdbc sends it as
   the type its getType names, which is known at run time alone."
  (class-fn "org.postgresql.util.PGobject"
            (fn [x] (boolean (some #(= "org.postgresql.util.PGobject" (.getName ^Class %)) (take-while some? (iterate #(.getSuperclass ^Class %) (class x))))))))

(def sqlxml
  "A java.sql.SQLXML (pgjdbc's PgSQLXML, which it reads an xml as), by name: pgjdbc sends it as
   xml."
  (class-fn "java.sql.SQLXML" (fn [x] (boolean (some #(= "java.sql.SQLXML" (.getName ^Class %)) (supers (class x)))))))

(defn value-kind
  "The kind of alternative a as a value: pgmalli's integer types (:pg/integer) an :int, as malli's is."
  [a]
  (let [k (shape/kind a)] (if (pgtypes/int-ranges k) :int k)))

(defn array-pg-object
  "pg-object of array type a, compared as a whole or under = ANY(?) (use :whole or :compare), or
   going where a value of it does, where no Java array goes as a."
  [a use]
  (shape/merge-props pg-object
                     {:error/message (str "should be a PGobject of type " a " (no Java array goes as " a ", which "
                                          (case use :whole "a comparison of arrays" :compare "= ANY(?)" "it")
                                          " takes alone), or the argument cast in the query: [:cast <the argument> [:raw \"" a "\"]]")
                      :pgmalli/describe (str "PGobject of " a)}))

(defn- none [_] false)

(defn inline-none
  "[:fn] that takes no value, as sql/format's :inline writes none where it goes as a literal
   PostgreSQL takes: why says so."
  [why]
  [:fn {:error/message (str "should be no value: " why)} none])

(defn array-type
  "An array argument of elements t, used as use (:compare, = ANY(?); :whole, compared with an
   array column; :assign, to an array column; :common, beside an array column in coalesce or
   CASE; :unnest, a column of unnest(?), assigned or compared): the Java arrays the one sending sends (write-rows), t in place of :pgmalli/element, of those PostgreSQL takes
   there: an untyped one but under unnest (unnest(unknown) is ambiguous), a typed one whose
   element type is one of t's own (accept/holds?, assigned; under :common, as coalesce resolves it; under = ANY(?), as = resolves an element with it), its elements
   then also that type's values (a bigint[]'s Longs to a numeric[] column); of an enum type,
   an array of that type alone; where t takes a PGobject (of a type known at run time alone), a
   PGobject too; where no Java array goes, a PGobject of the array type alone (array-pg-object).
   ::element-type in opts names t's type where t's schema names none (numeric[]'s decimal?);
   :any where none does. Under sql/format's :inline, which writes a Java array as no
   literal, the quoted text the array type's input reads ('{1,2}'), but under unnest, which
   takes no untyped value."
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
                         (and elem (accept/answers? elem)) (accept/holds? elem (types/element sent) (case use :common :common :compare :compare :assign))
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
                         (cond-> (clojure.walk/postwalk-replace {:pgmalli/element (let [e (element sent c)] (if (and (::null-elements opts) (not (shape/maybe? e))) [:maybe e] e))} a)
                           ;; pgjdbc types an array by its class: compared whole, the classes of the
                           ;; types taken alone; assigned (or beside a column), where its elements
                           ;; tell nothing (none, or NULLs alone), as elements of a value go where
                           ;; their class's would
                           (and (instance? Class c) (= :pg/array (shape/head a)) (#{:whole :assign :common} use))
                           (shape/merge-props {(if (= :whole use) :pg/classes :pg/empty-classes) #{(.getName ^Class c)}}))))
        ;; arrays of like elements (a long[] and a Long[]) one alternative, of both classes
        alts (let [ks [:pg/classes :pg/empty-classes]
                   like #(if (and (= :pg/array (shape/head %)) (map? (second %))) (update % 1 (fn [p] (apply dissoc p ks))) %)]
               (for [[_ as] (group-by like alts)]
                 (if (next as)
                   (shape/merge-props (first as) (into {} (for [k ks :let [cs (into #{} (mapcat (comp k shape/props)) as)] :when (seq cs)] [k cs])))
                   (first as))))]
    (cond (:inline (:format opts))
          (if (= :unnest use)
            (inline-none "unnest of the quoted text sql/format's :inline writes is ambiguous to PostgreSQL (unnest(unknown)): write the array in the query, or cast it")
            (shape/either (sent-as opts :unknown) :any))
          ;; a PGobject of the array type too, its text ('{1,NULL}') what the type's input reads
          (seq alts) (shape/either (cond-> alts
                                     (and p (#{:assign :whole :common} use)) (concat [(let [a (types/array-of (types/short-name (or elem p)))]
                                                                           (shape/merge-props pg-object {:error/message (str "should be a PGobject of type " a)
                                                                                                         :pgmalli/describe (str "PGobject of " a)
                                                                                                         :pgmalli/element (shape/non-null t)}))])
                                     (and (not p) (some #{pg-object} (shape/alternatives (shape/non-null t)))) (concat [pg-object]))
                                   :any)
          ;; no Java array of the type (text[]'s compared, String[] going as varchar[]; an enum's; a
          ;; time[]'s, json[]'s or box[]'s wherever it goes): a PGobject of it, which PostgreSQL takes
          ;; as the array type it names, its elements t's
          p
          (shape/merge-props (array-pg-object (types/array-of (types/short-name (or elem p))) use)
                             {:pgmalli/element (shape/non-null t)})
          :else :any)))

(defn write-type
  "A column's schema (without NULL) as an argument the driver takes: an array column's
   argument is an array of its elements' (array-type, used as ::array-use, :assign by default); otherwise the types read-type shares
   with it, and what the one sending sends as its type, which is all of it
   where read-type has the driver's object (:any)."
  [schema opts]
  (if (array-column? schema)
    ;; the elements' type is the column's, where their schema names none (numeric[]'s decimal?);
    ;; an element of an array column's value may be NULL
    (array-type (write-type (last schema) opts) (assoc opts ::element-type (types/element (pg-type schema)) ::null-elements true) (::array-use opts :assign))
    (let [k (shape/kind schema)
          t (driver-type schema true)
          ;; a date or time column takes the java.time value its type is, which the one reading
          ;; reads it as
          t (if (and (keyword? k) (= "time" (namespace k)) (not= :time/duration k))
              (shape/update-and-base t (constantly (shape/and-base schema)))
              t)
          n (some-> (shape/type-name (shape/column-props schema)) types/entry :name)
          ;; an enum's labels are the column's values; an argument's type keeps those a class sent
          ;; goes as (pgmalli-next.impl.args/labelled-type)
          sent (when (and n (not= :enum (shape/kind t))) (sent-as opts n))]
      (cond ;; a json or jsonb column an override shapes: what is sent as its type is held to that shape
            (and (types/json? (pg-type schema)) (not (#{:any :some} (shape/head schema)))) schema
            (empty? sent) t
            (= :any (shape/head t)) (shape/either sent t)
            ;; a kind the column's own values have already takes their bounds (an integer's range)
            :else (shape/either (concat (shape/alternatives t) (remove #(= (value-kind %) (value-kind (shape/and-base t))) sent)) t)))))

;;; rows as next.jdbc builds them

(defn row-key
  "The key pgmalli-next.jdbc/builder gives a column labelled column: its name."
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
    s))

(defn- column-read-type*
  [s pgjdbc?]
  (let [;; the driver returns the elements of an array of a domain (d[]) as PGobjects
        d (when (array-column? s) (some-> (:pg/domain (shape/column-props s)) types/element))]
    (cond-> (read-type (cond-> s d (assoc (dec (count s)) [:any {:pg/type d}])) pgjdbc?)
      d (shape/merge-props {:pg/domain (types/array-of d)}))))

(defn keyword-enum
  "[:enum props ...] of labels as keywords; a default among props (malli's default-value
   transformer fills it) a keyword label too."
  [props labels]
  (let [p (cond-> props (string? (:default props)) (update :default keyword))]
    (into (cond-> [:enum] (seq p) (conj p)) (map keyword labels))))

(defn- keyword-labels
  "s with each reference to an enum type of the registry's schemas as the type's labels as
   keywords, as the one reading reads a column of the type; a json column's, whose values are
   JSON's (an override's reference too), as it is."
  [schemas s]
  (clojure.walk/postwalk (fn [f]
                           (let [target (when-not (types/json? (pg-type s)) (shape/ref-target schemas f))]
                             (if (and (= :enum (shape/head target)) (shape/generated-name? (last f)))
                               (keyword-enum (shape/props f) (shape/entries target))
                               f)))
                         s))

(defn column-read-type
  "read-type of a column schema (without NULL) under the one reading. opts (a write type's) are
   not read: the arguments are column-write-type's."
  [schemas s _opts]
  (keyword-labels schemas (column-read-type* s false)))

(defn- column-write-type*
  "write-type of a column schema (without NULL) of a registry's schemas."
  [schemas s opts]
  (let [;; an array's elements of an enum type are its labels: array-type takes an array of that type alone
        e (when (array-column? s) (last s))
        target (shape/ref-target schemas e)
        s (cond-> s
            (= :enum (shape/head target)) (assoc (dec (count s)) (into [:enum {:pg/type (pg-type e)}] (shape/entries target))))]
    (write-type s opts)))

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

(def typed-pg-object
  "pg-object of type t: what it says where a value of t is refused, why a String is where one is
   given. Memoized: a type's schema of one call equals the next's."
  (memo/bounded
   (fn [t]
     (shape/merge-props (update pg-object 1 dissoc :error/message)
                        {:error/fn (fn [{:keys [value]} _]
                                     (str "should be a PGobject of type " t
                                          (when (string? value) (str " (a String goes as varchar, which " t " does not take)"))))
                         :pgmalli/describe (str "PGobject of " (types/short-name t))}))))

(defn column-write-type
  "column-write-type*: a column pgjdbc returns as a PGobject (json, jsonb, citext, bit(n > 1), bit varying)
   takes one too, so a value read without an extension is written back. A bit string column
   takes no String, which goes as varchar: a PGobject of its type alone."
  [schemas s opts]
  (let [w (column-write-type* schemas s opts)
        r (column-read-type* s true)
        t (pg-type r)
        e (types/entry (shape/type-name (shape/column-props s)))]
    (cond (and (:cast e) (not (array-column? s))) (typed-pg-object (:name e))
          (and (not= :any (shape/head w)) (= :any (shape/head r)) t (not (types/element t))
               (or (types/json? t) (#{:any :bit} (:read (types/entry (shape/type-name {:pg/type t}))))))
          (shape/either (concat (shape/alternatives w) [pg-object]) w)
          :else w)))

(defn as-read
  "The [:map ...] of a row as pgmalli-next.jdbc/builder builds it; the options are documented on
   pgmalli-next.core/as-read."
  [registry name opts]
  (let [m (shape/without-gen (data-columns registry name))
        props (when (map? (second m)) (second m))
        schemas (shape/schemas-of registry)
        entry (fn [[k p s]]
                (row-entry (row-key (clojure.core/name k)) p (column-read-type schemas (shape/non-null s) opts)
                           (shape/maybe? s)))]
    (into (if props [:map props] [:map]) (map entry (shape/column-entries m)))))
