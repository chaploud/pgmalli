(ns pgmalli-next.impl.args
  "The types of a query's arguments (docs/queries.md#the-types-of-the-arguments), and the type
   of an argument where a value of a column goes (accepted-type), which values-schema and
   set-schema type a row's columns with."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.memo :as memo]
            [pgmalli-next.impl.pgtypes :as pgtypes]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.registry :as registry]
            [pgmalli-next.impl.resolve :as resolve]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(declare accepted-type cast-arrays malli-options typed-only)

(defn- array-arg
  "The argument x of [h x] with h one of heads, sent as one array: of array-comparisons by
   default (= ANY(?), <> ALL(?))."
  ([v] (array-arg v syntax/array-comparisons))
  ([v heads] (when (and (vector? v) (= 2 (count v)) (heads (first v)) (syntax/param-key (second v))) (second v))))

(defn- assigned-type
  "The type of an argument assignment a (positions) writes to its column, in context (:assign,
   or :cast where it is cast to the column's type first): NULL where assigned-column says it
   may go."
  [registry a context opts]
  (let [{s :schema :keys [nullable?]} (query/assigned-column registry a)]
    (if s
      (let [inner (accepted-type registry s context opts)]
        (if nullable? [:maybe inner] inner))
      :any)))

(defn- compared-arg-type
  "The type of an argument compared with side, of side's schema; :any where side's is not told."
  [registry sc side opts]
  (if-let [s (query/side-schema registry sc side opts)]
    (accepted-type registry s :compare opts)
    :any))

(defn- unlabel
  "t without the :pg/type it names, which an alternative of another type's values would misname."
  [t]
  (cond (= :or (shape/head t)) (into [:or] (map unlabel (shape/alternatives t)))
        (and (vector? t) (map? (second t))) (let [p (dissoc (second t) :pg/type)] (if (and (empty? p) (= 2 (count t))) (first t) (assoc t 1 p)))
        :else t))

(defn- within
  "Alternative a, a number sent as type p, held to the range a column of type t's own assigned
   values have (their :min and :max; a real's, single precision's, which its :double holds). PostgreSQL rounds a fraction going to an integer type
   first, so a fraction is held to the bounds half a unit wider."
  [a p t own]
  (let [base (shape/and-base own)
        ;; of several (an inlined column's literals), the one of the bounds
        base (or (when (= :or (shape/head base)) (some #(when (some (or (shape/props %) {}) [:min :max]) %) (shape/alternatives base))) base)
        {:keys [min max]} (when (and (vector? base) (map? (second base))) (second base))
        [min max] (if (and (accept/integer-type? t) (not (accept/integer-type? p))) [(some-> min (- 0.5)) (some-> max (+ 0.5))] [min max])
        bounds (cond-> {} min (assoc :min min) max (assoc :max max))]
    (cond (types/single-precision? t) (if (and (= :double (shape/head a)) (types/single-precision? (:pg/type (shape/props a))))
                         a
                         [:and a [:fn {:error/message "should be within a real's range"} pgtypes/real-number?]])
          (empty? bounds) a
          (#{:int :double} (shape/head a)) (if (vector? a) (update a 1 merge bounds) [a bounds])
          :else (into [:and a] (remove nil? [(when min [:>= min]) (when max [:<= max])])))))

(defn- text-held
  "Alternative a of a value sent untyped, as its text (a keyword's, :n/a as n/a), held to what own,
   a column's type, takes of a string where an override or a domain checks it ([:and :string
   ...]), or a \"char\" its one byte: the text is the value the column gets."
  [a own registry]
  (let [checked (filter #(and (or (#{:and :re} (shape/head %)) (:pg/one-byte (shape/props %))) (= :string (shape/value-kind %))) (shape/alternatives (shape/non-null own)))]
    (if (and (seq checked) (= :keyword (shape/kind a)))
      (let [valid? (m/validator (shape/either checked nil) (malli-options registry))]
        [:and a [:fn {:error/message "should be a keyword whose text the column's string takes"} #(valid? (accept/sent-text %))]])
      a)))

(defn- arrays-sent
  "[type schema] of the arrays the one sending sends (jdbc/write-rows) as a type pred takes, element in
   their elements' place, and the values of the array's own elements of a class sent nowhere alone
   (accept/element-schema: a java.sql.Date[]'s inst?), which element, of the classes sent, does not
   hold; those of a class sent alone element holds as its type takes them (an enum's labels)."
  [opts pred element]
  (for [[schema q ^Class c] (jdbc/write-rows opts true) :when (pred q)
        :let [own (when (and c (.isArray c) (nil? (accept/row-of (.getComponentType c) accept/classes)))
                    (some-> c accept/element-schema shape/alternatives))
              kinds (set (map shape/kind (shape/alternatives (shape/non-null element))))
              more (remove (comp kinds shape/kind) own)
              e (if (and (seq more) (not (query/untyped? element)))
                  (cond-> (shape/either (concat (shape/alternatives (shape/non-null element)) more) element) (shape/maybe? element) query/nullable)
                  element)]]
    [q (walk/postwalk-replace {:pgmalli/element e} schema) c]))

(defn- labelled-type
  "The type of an argument where a value of own, an enum type's labels, goes, alts the [type
   alternative] of each type sent it takes: a label as a String where strings? (a String goes as a
   type that takes it), as a keyword where a row of keywords goes, unless keywords? is false
   (HoneySQL writes a keyword in :values as a column). Where no label is sent (pgjdbc's default
   sends a String as varchar), a PGobject, which pgjdbc sends as the type it names."
  [own alts strings? keywords?]
  (let [props (shape/props own)
        labels (shape/entries own)
        keywords? (and keywords? (some #(:keyword (shape/value-classes (second %))) alts))]
    (cond (not (or strings? keywords?)) (if-let [t (:pg/type props)] (jdbc/typed-pg-object t) jdbc/pg-object)
          (not keywords?) own
          (not strings?) (jdbc/keyword-enum props labels)
          :else (let [e (jdbc/keyword-enum props labels)]
                  (into (cond-> [:enum] (shape/props e) (conj (shape/props e))) (concat labels (shape/entries e)))))))

(defn- untyped-type-values
  "The values of a type whose own pgmalli does not type (interval, inet), s its schema: a
   PGobject, and the values of each type sent that accept/holds? says the type holds in context
   (a LocalTime for interval); under sql/format's :inline, which writes no PGobject, a String,
   written as the type's input reads it, and those held."
  [registry s context opts]
  (let [t (query/slot-type registry s)
        inline? (:inline (:format opts))
        rows (jdbc/write-rows opts false)
        untyped (when inline? (for [[x p] rows :when (= :unknown p) a (shape/alternatives x)] a))
        ;; a number goes to an oid alias (regclass, regproc) as an oid, within oid's range, wherever
        ;; it goes (docs/queries.md#stricter-oid)
        oid (when (types/oid-like? t) (types/type-schema "oid"))]
    (distinct (concat (if inline? untyped [(jdbc/typed-pg-object t)])
                      ;; pgjdbc reads an xml as a java.sql.SQLXML, which it sends as xml
                      (when (and (= "xml" t) (not inline?)) [jdbc/sqlxml])
                      (for [[x p c] rows
                            :when (and (not= p t) (accept/takes? t p c context))
                            a (shape/alternatives x)]
                        (cond-> (unlabel a) (and oid (accept/number-type? p)) (within p "oid" oid)))))))

(defn- class-held
  "A function's argument's alternative a held to the classes of its schema the one sending sends
   typed as t or a type takes? takes (ntile(?) takes an Integer, sent as integer, not a Long, sent
   as bigint, which PostgreSQL does not convert to integer implicitly); nil where it sends none of
   them so (a real argument's Double, sent as double precision)."
  [a t takes? opts]
  (let [k (shape/kind a)
        rows (filter (fn [[s p]] (and (not= :unknown p) (some #(= k (shape/kind %)) (shape/alternatives s)))) (jdbc/write-rows opts false))
        [in out] ((juxt filter remove) (fn [[_ p]] (or (= t p) (takes? p))) rows)
        named (fn [[_ _ ^Class c]] (error/named (.getSimpleName c)))
        hint (some (fn [[_ _ c]] ({Integer "(int n)" Short "(short n)"} c)) in)]
    (cond (or (empty? out) (not (string? t))) a
          (empty? in) nil
          :else (let [cs (map (fn [[_ _ ^Class c]] (.getName c)) in)]
                  [:and {:gen/schema (into [:pg/class] cs)} a
                   (into [:pg/class {:error/message (str "should be " (str/join " or " (map named in))
                                                         " (sent as " (str/join " or " (distinct (map second in))) ", which PostgreSQL takes as " t "); "
                                                         (str/join ", " (map #(str (named %) " goes as " (second %)) out))
                                                         ", which it does not convert to " t " implicitly: send " (when hint (str hint " or "))
                                                         "cast the parameter, " (syntax/cast-form "<parameter>" t))}]
                         cs)]))))

(defn- class-names [a] (when (and (= :and (shape/head a)) (= :pg/class (shape/head (last a)))) (shape/entries (last a))))

(defn- classes-joined
  "Alternatives as of several candidates' types, a kind held to classes (class-held) in one held
   to theirs together, or free where one is free (generate_series(1, ?) takes an Integer as
   integer, a Long as bigint); arrays and other collections each kept, as their elements differ
   (int[] || ? takes a double[] as well as an int[])."
  [as]
  (let [by-kind (group-by shape/kind as)]
    (distinct (for [a as :let [k (shape/kind a) same (by-kind k)]]
                (cond (or (shape/collection-kinds k) (= :pg/array k)) a
                      (not-every? class-names same) (first (remove class-names same))
                      :else (let [[b c] (shape/entries (first same))
                                  cs (distinct (mapcat class-names same))]
                              [:and {:gen/schema (into [:pg/class] cs)} b (into [:pg/class (shape/props c)] cs)]))))))


(def ^:private numeric-special
  "A Double NaN or infinity, which numeric holds as it is (no other Double), generated NaN alone: a
   numeric(p, s), whose typmod the registry keeps not, holds no infinity."
  [:and {:gen/elements [##NaN]} [:double {:pg/type "numeric"}]
   [:fn {:error/message "should be a Double NaN or infinity alone, which numeric holds as it is"} #(or (Double/isNaN %) (Double/isInfinite %))]])

(defn- one-per-kind
  "Alternatives as, of a kind the first (shape/distinct-kinds); under sql/format's :inline, as an
   inlined number of a kind is of several types by its value, each of its own range
   (jdbc/inline-classes), each of a kind, but that a kind's first alternative of no check
   (decimal?) takes them all."
  [opts as]
  (if (:inline (:format opts))
    (let [plain? #(let [u (unlabel %)] (or (keyword? u) (symbol? u)))
          free (into {} (for [a (reverse as) :when (plain? a)] [(jdbc/value-kind a) a]))]
      (distinct (for [a as :let [k (jdbc/value-kind a)]] (get free k a))))
    (shape/distinct-kinds as)))

(defn- accepted-type*
  "The type of an argument where a value of column schema s goes, in context (:compare, :call,
   :common, :assign, :cast or :json), as docs/queries.md#the-types-of-the-arguments says: its
   own values (jdbc/write-type), and the values of each type sent that the column holds
   (accept/holds?) or, cast, that PostgreSQL casts (accept/accepts?); labels as labelled-type
   sends them. A function's argument (:call) and an operand resolved to the others' type
   (:common) are compared but for the types they hold."
  [registry s held-in opts]
  (let [context (if (#{:call :common} held-in) :compare held-in)
        t (query/slot-type registry s)
        opts (cond-> opts
               (= "character" t) (assoc ::jdbc/characters true)
               (= :common held-in) (assoc ::jdbc/array-use :common)
               (= :compare held-in) (assoc ::jdbc/array-use :whole)
               (= :json held-in) (assoc ::jdbc/json true))
        own (cond-> (query/driver-schema registry jdbc/column-write-type s opts)
              (= :compare context) query/loose
              (= :cast context) shape/without-gen)
        ;; cast to an array type, PostgreSQL casts an array element by element, NULL too, to the
        ;; element type; a scalar through the array type's input alike for every element type
        es (when (= :cast context) (some->> (types/element (:pg/type (shape/column-props s))) (query/named-type-schema registry opts)))
        t (or t (when es "text[]"))
        takes? #(accept/sent-held? t % (if (= :cast context) :cast held-in))
        sent (distinct (for [[_ p] (jdbc/write-rows opts false) :when (and (not= p t) (takes? p))] p))
        arrays (when (= :cast context)
                 (for [a (shape/alternatives
                          (if es
                            (cast-arrays registry es (or (::cast-element opts) (query/nullable (accepted-type registry es :cast opts))) opts)
                            ;; to another type (text, say), its elements any
                            (shape/either (map second (arrays-sent opts #(and (takes? %) (not (accept/scalar-cast? t %))) :any)) :any)))
                       :when (not (query/untyped? a))]
                   [nil a]))
        alts (concat
              (for [p sent
                    a (cond (= :unknown p)
                            ;; under :inline, a String or a UUID is a quoted literal, which every type's input reads
                            (map #(text-held % own registry) (jdbc/sent-as opts :unknown #(or (:inline (:format opts)) (accept/takes? t :unknown % (if (= :cast context) :cast held-in)))))
                            ;; a literal is of its type by its value (jdbc/inline-classes): those values alone
                            (:inline (:format opts)) (map unlabel (jdbc/sent-as opts p))
                            :else (shape/alternatives (unlabel (query/loose (query/driver-schema registry jdbc/column-write-type (types/type-schema p) opts)))))
                    :when (and (not (query/untyped? a))
                               (not (and (= :cast context) (accept/scalar-cast? t p) (shape/collection-kinds (shape/kind a))))
                               ;; a document of a json column an override shapes is of that shape, as whichever type
                               (not (and (types/json? t) (types/json? p) (not (query/untyped? own)))))]
                [p a])
              arrays)]
    (cond
      ;; a cast takes a string and every explicit cast, of types pgmalli-next.impl.accept does not answer for
      (and (nil? t) (= :cast context)) :any
      ;; an array of a domain (d[]) is compared with a d[] alone: no Java array goes as one
      (and (= :compare held-in) (some-> (query/domain-of s) types/element))
      (jdbc/array-pg-object (query/domain-of s) :whole)
      ;; compared, assigned, a function's argument or a :common operand, of a type whose values
      ;; pgmalli does not type and pgjdbc reads as a PGobject or a String (a geometric, network,
      ;; timespan, numeric, range or other pg_catalog one: inet, interval, regclass, money,
      ;; int4range, xml, tsvector, macaddr; or json), a PGobject of it and
      ;; what it holds (a map or a vector, sent as jsonb, to json: accept/unchanged-assignments), not any value
      ;; under sql/format's :inline, of any type but cast: the quoted text its input reads
      (and (string? t) (query/untyped? own) (not es)
           (or (and (#{:compare :assign} context) (#{"G" "I" "T" "N" "R" "U"} (types/category t)) (not= "json" t))
               ;; json: assigned, or beside a json column in coalesce or CASE (which resolves no other type to json)
               (and (or (= :assign context) (= :common held-in)) (= "json" t))
               (and (:inline (:format opts)) (not (#{:cast :json} context)))))
      (shape/either (untyped-type-values registry s held-in opts) :any)
      (or (nil? t) (and (query/untyped? own) (not es))) own
      (= :enum (shape/kind own))
      (labelled-type own alts (boolean (some #(or (= t %) (takes? %)) (jdbc/string-sends opts))) (not (::keywords-columns opts)))
      :else
      (let [own-alts (when-not (query/untyped? own) (shape/alternatives own))
            ;; a String of the column's own :string goes as varchar, which a type of another
            ;; category may not take there (regclass = varchar does not exist)
            own-alts (if (or (= :cast context) (some #(or (= t %) (accept/accepts? t % context)) (jdbc/string-sends opts)))
                       own-alts
                       (remove #(= :string (shape/kind %)) own-alts))
            ;; assigned to a real, a Double goes as double precision, which PostgreSQL rounds to it on
            ;; assignment alone (docs/queries.md#stricter-assignment): a Float
            own-alts (if (and (types/single-precision? t) (= :assign context))
                       (map #(if (= :double (shape/head %)) [:and % [:pg/class "java.lang.Float"]] %) own-alts)
                       own-alts)
            ;; a number held to a number column's range, which PostgreSQL checks after converting it
            alts (for [[p a] alts] (cond-> a
                                     ;; an oid's compared too: no operator compares it with another number, which converts to it
                                     (and (or (not= :compare context) (types/oid-like? t)) (accept/number-type? t) (accept/number-type? p)) (within p t own)))
            ;; a cast's array of any elements is broader than an array of the type's own
            cast-kinds (set (map (comp shape/kind second) arrays))
            ;; numeric's NaN and infinities, which the one reading reads as a Double: PostgreSQL
            ;; assigns them exactly, as no other Double; sql/format's :inline writes them as names
            special? #(and (= :assign context) (= :or-double (:read (types/entry %))) (not (:inline (:format opts))))
            specials (concat (when (special? t) [numeric-special])
                             ;; a numeric[]'s too, of a Java array of them alone (float8[] to numeric[] by element)
                             (when (and (string? t) (special? (types/element t)))
                               ;; of its own class, as an array of no value is typed (jdbc/array-type)
                               (distinct (for [[_ s ^Class c] (arrays-sent opts #{"double precision[]"} (query/nullable numeric-special))]
                                           (cond-> s (and c (= :pg/array (shape/head s))) (shape/merge-props {:pg/empty-classes #{(.getName c)}}))))))
            all (cond->> (one-per-kind opts (concat (remove (comp cast-kinds shape/kind) own-alts) alts))
                  ;; inlined, a literal is of a type by its value, whatever its class
                  (and (or (= :call held-in) (= :cast context)) (not (::common-type opts)) (not (:inline (:format opts)))) (keep #(class-held % t takes? opts)))
            all (concat all specials)]
        (cond (or (= all own-alts) (empty? all)) own (next all) (into [:or] all) :else (first all))))))

(def ^:private inline-rows (jdbc/write-rows {:format {:inline true}} false))

(def ^:private inline-kinds
  "The kinds of value sql/format's :inline writes into the SQL as a literal (jdbc/inline-classes'),
   an enum's labels too, which it takes as strings there."
  (conj (set (map (comp jdbc/value-kind first) inline-rows)) :enum))

(def ^:private inline-row-schemas (set (map first inline-rows)))

(def ^:private finite-kinds
  "The kinds of inline-kinds of a Double and a Float, whose values but NaN and the infinities it
   writes as a literal (jdbc/inline-finite)."
  (set (for [[s _ c] inline-rows :when (#{Double Float} c)] (jdbc/value-kind s))))

(defn- inline-only
  "t, held-in as accepted-type's, without the values sql/format's :inline writes as no literal (a
   keyword as a name, a date, a PGobject or a map as their text unquoted, a Double NaN as a name);
   any value, of a type whose values pgmalli does not type (int4range, an extension's), the
   quoted text the type's input reads, but cast (a number goes to money) or in a JSON document."
  [t held-in opts]
  (cond
    (= :nil (shape/head t)) t
    (query/untyped? t) (if (#{:cast :json} held-in) t (shape/either (jdbc/sent-as opts :unknown) t))
    :else
    (let [inner (shape/non-null t)
          as (for [a (shape/alternatives inner) :let [k (jdbc/value-kind a)] :when (inline-kinds k)]
               (cond-> a (and (finite-kinds k) (not (inline-row-schemas a))) jdbc/inline-finite))
          kept (cond (= as (shape/alternatives inner)) inner
                     (seq as) (shape/either as inner)
                     :else (jdbc/inline-none "sql/format's :inline writes a value of this type as no literal: send a string, a number or a boolean"))]
      (if (shape/maybe? t) [:maybe kept] kept))))

(defn accepted-type
  "accepted-type*; under sql/format's :inline, the values it writes as a literal alone."
  [registry s held-in opts]
  (cond-> (accepted-type* registry s held-in opts)
    (:inline (:format opts)) (inline-only held-in opts)))

(defn- compared-type
  "The type of an argument compared with a column by op, t as accepted-type gives it compared
   (its ranges left out: PostgreSQL compares a value beyond them, matching
   no row), without NULL; :in's a non-empty vector, list or set of it, as
   sql/format expands it (IN () is not SQL), none under its :inline, which writes no list of it;
   an array's the Java arrays the one sending sends (jdbc/array-type). NULL is compared with IS
   NULL in the query body."
  [op t array? opts]
  (cond-> (cond array? (jdbc/array-type t opts :compare)
                (and (syntax/in-ops op) (:inline (:format opts)))
                (jdbc/inline-none "sql/format's :inline writes a collection in IN as no list PostgreSQL reads (IN [1, 2]): write the list in the query, [:in :id [1 2]]")
                (and (syntax/in-ops op) (not (query/untyped? t))) [:or [:sequential {:min 1} t] [:set {:min 1} t]]
                :else t)
    (syntax/distinct-ops op) query/nullable))

(defn- literal
  "{:value v} of a literal x, a value written in the query: a string, a number or another value
   of no HoneySQL syntax (an Instant, a UUID), bare or [:lift v] (HoneySQL sends it as a
   parameter, sql/format's :inline aside), a boolean (bare, HoneySQL writes TRUE or FALSE),
   [:lift :kw] (sent untyped as its text, :text, :ns/a's ns/a), [:lift coll] (a map or a vector,
   sent as the one sending sends its class: jsonb), or [:inline s] (written into the SQL,
   :inline); nil for another x (a bare keyword is a column)."
  [x]
  (let [v (when (and (vector? x) (= 2 (count x))) (second x))
        value? #(not (or (nil? %) (keyword? %) (symbol? %) (coll? %)))]
    (cond (value? x) {:value x}
          (= :lift (when (vector? x) (first x)))
          (cond (or (value? v) (coll? v)) {:value v}
                (keyword? v) {:value v :text (accept/sent-text v)})
          (and (= :inline (when (vector? x) (first x))) (string? v)) {:value v :inline true})))

(defn- literal-key
  "The key of literal x at a position, with where it goes (:column, :function, :schema) for the
   problem it may make."
  [x where]
  (assoc where ::literal x))

(defn- value-arg
  "[argument array?] of a value where an argument goes: an argument itself, or a column of
   unnest(?) of arguments (array? true: the argument's elements go there); nil for any other."
  [registry chain x]
  (cond (syntax/param-key x) [x false]
        (syntax/column-keyword? x) (let [e (:element (query/resolve-column registry chain x))] (when (syntax/param-key e) [e true]))))

(defn- compared-literals
  "The literals of value compared with a side: itself, each of an IN list, each of an IN's
   [:lift coll], which HoneySQL writes as a list of its elements, each element of
   [:any [:array [...]]]; under sql/format's :inline, no [:lift coll], which it writes as no list
   (IN [1, 2])."
  [op value inline?]
  (filter literal (cond (and (vector? value) (= :any (first value)) (vector? (second value)) (= :array (first (second value))) (sequential? (second (second value))))
                        (second (second value))
                        (and (syntax/in-ops op) (syntax/lifted-coll? value))
                        (when-not inline? (map #(vector :lift %) (second value)))
                        (and (syntax/in-ops op) (vector? value) (not (keyword? (first value)))) value
                        :else [value])))

(defn- comparison-args
  "An argument compared with a column or a typed expression, as compared-type types it; an
   unnest's column, its argument an array of that. A literal compared there, as an argument."
  [registry ps opts]
  (for [[op side value chain] (syntax/comparison-sides ps)
        :let [x (array-arg value)
              [k elements?] (if x [x false] (value-arg registry chain value))
              lits (when-not k (compared-literals op value (:inline (:format opts))))]
        :when (or (seq lits) (and k (not (and elements? (or (syntax/in-ops op) (second (value-arg registry chain side)))))))
        entry (if (seq lits)
                (let [s (query/side-schema registry chain side opts)]
                  ;; a LIKE pattern is no value of the column: its type alone, no labels
                  (for [l lits :when s] [(literal-key l {:column side :function (name op) :context :compare :schema (when-not (syntax/like-ops op) s)})
                                         (compared-arg-type registry chain side opts)]))
                (let [;; ANY(?)'s elements of the column's type (a varchar column's takes a String[])
                      base (when (and x (not elements?)) (some->> (query/side-schema registry chain side opts) (query/slot-type registry)))
                      t (compared-type op (compared-arg-type registry chain side opts) (when-not elements? x)
                                       (cond-> opts (string? base) (assoc ::jdbc/element-type base)))]
                  (when-not (and elements? (query/untyped? t))
                    [[k (if elements? (jdbc/array-type t opts :unnest) t)]])))]
    entry))

(defn- limit-args
  "LIMIT and OFFSET take a bigint of at least 0 by assignment, NULL for none."
  [registry ps opts]
  (for [{:keys [x clause top?]} (syntax/expression-nodes ps)
        :when (and top? (#{:limit :offset} clause) (syntax/param-key x))]
    [x (query/nullable (accepted-type registry [:int {:pg/type "bigint" :min 0}] :assign opts))]))

(defn- truth-tested-args
  "? IS [NOT] TRUE, FALSE or UNKNOWN: PostgreSQL takes the argument as a boolean, as assigned
   (coerce_to_boolean); NULL too."
  [registry ps opts]
  (for [{:keys [x]} (syntax/expression-nodes ps)
        :when (and (vector? x) (= 3 (count x)) (#{:is :is-not} (first x)) (syntax/param-key (second x))
                   (syntax/truth-value? (nth x 2) (:format opts)))]
    [(second x) (query/nullable (accepted-type registry (types/type-schema "boolean") :assign opts))]))

(defn- cast-arrays
  "The arrays whose elements PostgreSQL casts to the type of column schema s, elements of
   element type, typed; :any for none."
  [registry s element opts]
  (let [t (query/slot-type registry s)]
    (shape/either (distinct (map second (arrays-sent opts #(and t (string? %) (accept/accepts? t (types/element %) :cast)) element)))
                  :any)))

(defn- unnest-cast-element
  "The type of an element of x, an argument cast to an array of T in unnest(?, ...), whose
   column goes to one column of type T or a domain over it: as assigned there (assigned-type),
   or else as cast to T, which converts a value assignment does not (a String to a bigint);
   nil where its column goes elsewhere."
  [registry ps x opts]
  (let [as (for [{c :x :keys [chain] :as a} (syntax/assignments ps)
                 :when (and (syntax/column-keyword? c) (= x (:element (query/resolve-column registry chain c))))]
             a)]
    (when (= 1 (count as))
      (let [a (first as)
            es (some->> (syntax/cast-target (nth x 2)) types/element (query/named-type-schema registry opts))
            r (query/resolver-type registry es)]
        (when (and r (query/own-type? {:type r :schema es} r) (= r (query/resolver-type registry (:schema (query/assigned-column registry a)))))
          (let [[assigned cast] (map #(assigned-type registry a % opts) [:assign :cast])]
            (cond-> (shape/either (shape/distinct-kinds (mapcat (comp shape/alternatives shape/non-null) [assigned cast])) :any)
              (shape/maybe? assigned) query/nullable)))))))

(defn- cast-args
  "[:cast :?x :timestamptz]: what PostgreSQL casts to the type, its ranges left out where every
   use of the cast is compared; nil too unless every use of the
   cast is compared (not under IS [NOT] DISTINCT FROM, not with NULL) or assigned where NULL may not go (assigned-column). [:cast :x.a :date] of an
   unnest's column: an array of that, typed."
  [registry ps opts]
  (let [nodes (syntax/expression-nodes ps)
        ;; populate-documents types these
        populated (set (for [{:keys [x chain]} nodes :let [{:keys [json arg]} (query/populate-call registry chain x opts)] :when arg] json))
        casts (for [{:keys [x chain]} nodes
                    :when (not (populated x))
                    ;; ARRAY[...]::t[] casts each element to t (transformArrayExpr)
                    x (cond (and (vector? x) (= :cast (first x)) (= 3 (count x))) [x]
                            (and (vector? x) (= :array (first x)) (= 3 (count x)) (sequential? (second x)) (keyword? (nth x 2)))
                            (for [e (second x)] [:cast e (nth x 2)])
                            :else (some-> (query/type-call registry chain x opts) vector))
                    :let [[k elements?] (or (value-arg registry chain (second x)) (when (literal (second x)) [(second x) false]))]
                    :when k]
                [x chain k elements?])
        ;; each operand of a comparison once: BETWEEN's bounds, IN's items too
        operands (fn [x] (for [o (rest x) o (if (and (syntax/in-ops (first x)) (vector? o) (not (keyword? (first o)))) o [o])] o))
        refusing (frequencies
                  (concat (for [{:keys [x]} nodes
                                :when (and (syntax/comparison? x)
                                           (not (or (syntax/distinct-ops (first x)) (some #(syntax/null-value? % (:format opts)) (rest x)))))
                                o (operands x)]
                            o)
                          (for [a (syntax/assignments ps)
                                :let [{c :schema :keys [nullable?]} (query/assigned-column registry a)]
                                :when (and c (not nullable?))]
                            (:x a))))
        compared (frequencies (for [{:keys [x]} nodes :when (syntax/comparison? x) o (operands x)] o))
        uses (frequencies (map first casts))
        ;; [:f a] read as a cast to type f: the call it is, which func_get_detail resolves as a function
        ;; where a's type has no cast to f (a typed date to timetz(timestamptz))
        calls (into {} (for [{:keys [x chain]} nodes :let [c (query/type-call registry chain x opts)] :when c] [c x]))
        call-type (fn [call]
                    (let [os (query/reached registry opts (syntax/call-parts call))]
                      (some->> (for [s (map #(query/named-type-schema registry opts %) (resolve/argument-types [:free] 0 os)) :when s
                                     a (shape/alternatives (shape/non-null (accepted-type registry s :call opts)))]
                                 (unlabel a))
                               seq shape/distinct-kinds)))]
    ;; a cast of an argument once, a literal's each time it is written
    (for [[x chain k elements?] (let [arg? (fn [[_ _ k e]] (or (syntax/param-key k) e))] (concat (distinct (filter arg? casts)) (remove arg? casts)))
          :let [s (:schema (query/expression-type registry chain x opts))]
          :when (and s (or (syntax/param-key k) elements? (query/enum-values registry s)))
          :let [e (when-not elements? (unnest-cast-element registry ps x opts))
                t (accepted-type registry s :cast (cond-> opts e (assoc ::cast-element e)))
                ;; a call of a type's name (bool(?)) converts its argument implicitly first: its own
                ;; classes ahead of the cast's (an Integer alone casts to boolean)
                t (if-let [more (some-> (calls x) call-type)]
                    (shape/either (shape/distinct-kinds (concat more (shape/alternatives (shape/non-null t)))) t)
                    t)
                t (cond-> t
                    ;; compared, PostgreSQL evaluates the cast only where its plan reaches it (a row
                    ;; the other conditions keep, or once ahead of the scan), so its ranges may never be checked
                    (= (get compared x 0) (uses x)) query/loose
                    (< (get refusing x 0) (uses x)) query/nullable)]]
      (if (or (syntax/param-key k) elements?)
        [k (if elements?
             (cast-arrays registry s t opts)
             (cond-> t (and (vector? t) (= :cast (first x)))
               (vary-meta assoc
                          ::cast (some-> (syntax/cast-target (nth x 2)) types/canonical-name)
                          ;; an enum's its labels, as a PGobject's :pgmalli/element names them
                          ::cast-element (when-let [es (some->> (syntax/cast-target (nth x 2)) types/element (query/named-type-schema registry opts) shape/non-null)]
                                           (if-let [ls (seq (query/enum-values registry es))]
                                             (into [:enum (select-keys (shape/column-props es) [:pg/type])] ls)
                                             es)))))]
        ;; a literal cast to an enum type: its labels
        [(literal-key k {:type (query/enum-type registry s) :function "CAST" :schema s}) t]))))

(defn- assignment-args
  "An argument assigned to a column; unnest(?), of a typed array (unnest(unknown) is ambiguous),
   or an unnest's column, each element assigned to it."
  [registry ps opts]
  (for [{:keys [x chain assign event] :as a} (syntax/assignments ps)
        :let [{c :schema :keys [table nullable?]} (query/assigned-column registry a)
              ;; a NOT NULL column a BEFORE row trigger may fill: nil goes there for it alone
              triggers (when (and c nullable? (not (shape/maybe? c))) (shape/before-triggers (query/table-props registry table) event))
              t (assigned-type registry a :assign opts)
              t (cond-> t (and triggers (vector? t)) (vary-meta assoc ::before-triggers triggers))
              u (array-arg x #{:unnest})
              [k elements?] (if u [u true] (value-arg registry chain x))
              s (when (and (not k) (literal x)) (:schema (query/assigned-column registry a)))]
        :when (or k s)]
    (if k
      [k (if elements? (jdbc/array-type t opts :unnest) t)]
      [(literal-key x {:column assign :function (name assign) :context :assign :schema s}) t])))

(defn- json-field-type
  "The type of a value of a key of a JSON document json_populate_record reads into a column of
   schema s, as the one sending's jsonb writes it (accept/json-classes): of a json or jsonb
   column, as read back; of an array column, a sequential of its elements' (NULL too) or a
   string, the array's text; of
   another, what its type's input reads of a JSON number, boolean or string (accepted-type
   under :json), no PGobject, which the document does not hold."
  [registry s opts]
  (let [s (shape/non-null s)
        p (:pg/type (shape/column-props s))]
    (cond (types/json? p) (query/driver-schema registry jdbc/column-read-type s opts)
          ;; the array input's text is in braces, after its dimensions where it names them ([1:2]={1,2})
          ;; each element as a value of the element type's column, which its schema names not (a mood[]'s [:ref mood])
          (types/element p) [:or [:sequential (query/nullable (json-field-type registry (shape/merge-props (last s) {:pg/type (types/element p)}) opts))]
                             [:re {:error/message "should be an array's text, {1,2}"} #"(?s)^\s*(\[[^\]]*\]\s*=\s*)?\{.*\}\s*$"]]
          :else (let [t (accepted-type registry s :json opts)]
                  (shape/either (remove #{jdbc/pg-object} (shape/alternatives t)) t)))))

(defn- one-key-per-column?
  "Whether document m holds no column's key both as a keyword and as a string."
  [m]
  (not-any? #(and (keyword? %) (contains? m (name %))) (keys m)))

(defn- populate-documents
  "[{:arg :schema :columns} ...] of the documents of the calls populate-call tells. schema, of
   arg: for a record, a closed map of the row type's columns (populated-columns), each keyed by
   its name as a keyword or a string (not both), each a json-field-type (:any of a type not
   read) or nil; for a recordset, a vector of
   them; in place of the collections a json or jsonb value of the parameter takes there (bare, a
   value sent as the function's json or jsonb; cast, what the cast takes), or nil. A key is
   required, its value not nil, where an INSERT ... SELECT writes the column of that name of
   the row type's table straight from it, or through a derived table selecting it, and NULL may
   not go there
   (assigned-column). columns, the set of the columns the statements reading the call's alias
   name of it (resolved as a column of it, in the statement or one nested in it); nil where a
   use names none in particular: a star, its whole row, :raw, or a list of columns renaming
   them."
  [registry body opts]
  (for [sps [(query/once registry query/statement-positions body opts)]
        [stmt c _ ps] sps
        {:keys [x chain]} (syntax/expression-nodes ps)
        :let [{:keys [table set? type json arg] :as call} (query/populate-call registry chain x opts)]
        :when arg
        :let [item (some (fn [{i :x top? :top?}] (when (and top? (vector? i) (identical? x (first i))) i)) ps)
              columns (query/populated-columns registry call item opts)]
        :when columns
        :let [s (if (syntax/param-key json) (types/type-schema type) (:schema (query/expression-type registry chain json opts)))
              base (accepted-type registry s (if (syntax/param-key json) :compare :cast) opts)
              alias (when item (second (syntax/table-ref item opts)))
              ;; whether alias names, in a chain, the call's item: not where a scope nearer has an
              ;; alias of that name, nor in the item itself
              sees? (fn [chain] (identical? (get (first c) alias) (some #(when (map? %) (get % alias)) chain)))
              reads (for [[_ c' _ ps'] sps :when (and alias (sees? c')), n (syntax/expression-nodes ps')] n)
              untold? (fn [{y :x chain :chain}]
                        (or (and (vector? y) (= :raw (first y)))
                            (and (keyword? y) (let [[a column] (syntax/split-column y (:format opts))]
                                                (or (and (syntax/star? y) (contains? #{nil alias} a))
                                                    (and (nil? a) (= alias column) (sees? chain)))))))
              used (when (and alias (or (nil? table) (not (vector? (second item)))) (not-any? untold? reads))
                     (set (for [{y :x chain :chain} reads :when (syntax/column-keyword? y)
                                :let [{:keys [column opaque?] from :table} (query/resolve-column registry chain y)]
                                :when (and opaque? (= alias from))]
                            (keyword column))))
              ;; {output column, call's column} of the statement's select items that are columns of
              ;; the call: a statement over it as a derived table (FROM (SELECT j.a ... LIMIT n) r) writes r.a
              through (when alias
                        (into {} (for [item (syntax/selected-items stmt)
                                       :let [[col] (syntax/select-parts item opts)
                                             {:keys [column opaque?] from :table} (when (syntax/column-keyword? col) (query/resolve-column registry c col))]
                                       :when (and opaque? (= alias from))]
                                   [(syntax/item-name item opts) column])))
              ;; [assignment column] of each write of a column of the call, straight or through a
              ;; derived table of the statement
              writes (concat
                      (for [a (syntax/assignments ps) :when (and alias (syntax/column-keyword? (:x a)))
                            :let [{:keys [column opaque?] from :table} (query/resolve-column registry (:chain a) (:x a))]
                            :when (and opaque? (= alias from))]
                        [a column])
                      (for [[s2 _ _ ps2] sps
                            :let [ds (set (for [i (syntax/from-items s2 [:from]) :when (and (vector? i) (identical? stmt (first i)))]
                                            (second (syntax/table-ref i opts))))]
                            :when (seq ds)
                            a (syntax/assignments ps2) :when (syntax/column-keyword? (:x a))
                            :let [{:keys [column] from :table} (query/resolve-column registry (:chain a) (:x a))
                                  inner (get through column)]
                            :when (and (ds from) inner)]
                        [a inner]))
              ;; {column nullable?} of the columns of the document the statement writes, a NOT NULL
              ;; column a BEFORE row trigger may fill with the triggers' names
              written (into {} (for [[{event :event :as a} column] writes
                                     :let [{written :column t :table :keys [nullable? schema]} (query/assigned-column registry a)]
                                     :when (and (or (nil? table) (= table t)) schema (= column written))]
                                 [column (if (not nullable?)
                                           false
                                           (or (when-not (shape/maybe? schema) (shape/before-triggers (query/table-props registry t) event)) true))]))
              fields (for [[c cs] columns
                           :let [w (get written c)
                                 t (if cs (json-field-type registry cs opts) :any)]]
                       [c (cond-> t (vector? w) (shape/merge-props {:pgmalli/before-triggers w})) (false? w)])
              doc (-> [:and
                       (into [:map {:closed true}]
                             (for [[c t req?] fields, k [(keyword c) c]]
                               (jdbc/row-entry k {:optional true} t (not req?))))
                       [:fn {:error/message "should hold a column's key once, as a keyword or a string"} one-key-per-column?]]
                      (into (for [[c t req?] fields :when req?]
                              [:or [:map [(keyword c) t]] [:map [c t]]])))
              [kept dropped] (if set? [:vector :map-of] [:map-of :vector])]]
    {:arg arg
     :columns used
     :schema (query/nullable (shape/either (for [a (shape/alternatives (shape/non-null base))
                                           :when (not= dropped (shape/kind a))]
                                       (if (= kept (shape/kind a)) (if set? [:vector doc] doc) a))
                                     :any))}))

(defn document-columns
  "{arg columns} of populate-documents, keyed as :params takes them; of an argument two calls
   take, the columns of both, nil where either is nil."
  [registry body opts]
  (apply merge-with (fn [a b] (when (and a b) (into a b)))
         {}
         (for [{:keys [arg columns]} (query/once registry populate-documents body opts)]
           {(syntax/param-key arg) columns})))

(defn- resolving-args
  "coalesce(?, col), CASE ... ELSE col and the like: PostgreSQL resolves an argument to the
   others' type; nullif's as = reads it beside the other; nil is a value there."
  [registry ps opts]
  (for [{:keys [x chain]} (syntax/expression-nodes ps)
        :let [operands (query/resolving-operands x)
              slot? #(or (syntax/param-key %) (literal %))]
        :when (some slot? operands)
        :let [type-of #(query/expression-type registry chain % opts)
              ;; the type of the operands no value decides, else of the literals too (nullif(?, 0))
              typed (remove slot? operands)
              r (or (query/common (map type-of typed)) (query/common (map type-of (remove syntax/param-key operands))))]
        :when (:schema r)
        o operands
        :when (slot? o)
        :let [t (query/nullable (accepted-type registry (:schema r) (if (= "nullif" (syntax/called x)) :compare :common) opts))]]
    (if (syntax/param-key o)
      [o t]
      [(literal-key o {:column (first (filter syntax/column-keyword? typed)) :function (or (syntax/called x) "CASE") :context :compare :schema (:schema r)}) t])))

(defn- call-arg-type
  "What a value takes at argument i of a call PostgreSQL may resolve to candidates os, ss the
   schemas of the types they take there: a value of each type, which a :pg/type of one would
   misname; nil may go there too."
  [registry ss os i opts]
  (let [;; anyarray there (&&, @>): an array of the type it binds alone, no array PostgreSQL would convert
        opts (cond-> opts
               (some #(= "anyarray" (get (:args %) i)) os) (assoc ::jdbc/array-use :whole)
               ;; anycompatible: of the common type the argument takes part in deciding (array_append(int[], bigint))
               (some #(str/starts-with? (str (get (:args %) i)) "anycompatible") os) (assoc ::common-type true))
        ;; anycompatible: what PostgreSQL resolves with the others to one type (array_append(int[], 1.5) is a double precision[])
        held-in (if (::common-type opts) :common :call)]
    (cond (empty? ss) nil
          (next ss) (query/nullable
                     ;; beside others, a type pgmalli does not type (pg_lsn for +) takes a PGobject, not any value
                     (shape/either (one-per-kind opts
                                    ((if (:inline (:format opts)) identity classes-joined)
                                     (for [s ss, x (shape/alternatives (accepted-type registry s held-in opts))]
                                       ;; an array of such a type takes the Java arrays sent as it
                                       (if (and (= :any (shape/head x)) (not (types/element (:pg/type (shape/column-props s)))))
                                         jdbc/pg-object
                                         (unlabel x)))))
                                   :any))
          :else (query/nullable (accepted-type registry (first ss) held-in opts)))))

(defn malli-options
  "The malli options a schema of registry r (an argument's, a row's) is validated under."
  [r]
  {:registry (merge registry/base (::query/schemas r))})

(defn- refuses?
  "Whether argument schema t refuses literal v; not where malli cannot tell (validate throws)."
  [registry t v]
  (false? (try (m/validate t v (malli-options registry)) (catch Exception _ nil))))

(defn- uncast-type
  "The type to cast a string to for column s: an enum type (or a domain over one, as
   query/cast-type-name names it under opts), an array, a type of types' :cast (bit takes
   varbit, which keeps its length), or any other type types has; nil for a string type (text,
   varchar, char, name, citext) or a type types lacks."
  [registry s opts]
  (let [p (shape/column-props s)
        e (types/entry (shape/type-name p))]
    (or (when (query/enum-type registry s) (query/cast-type-name s opts))
        (when e
          (cond (types/element (:pg/type p)) (types/array-of (:name e))
                (:cast e) (name (:cast e))
                (not= :string (:schema e)) (:name e))))))

(defn- literal-sent-type
  "The type literal x goes to PostgreSQL as: a bare boolean's boolean, written into the SQL; a
   value sent as a parameter, the type the one sending sends it as; nil where x goes untyped
   (written into the SQL as a string, sent as a keyword, or under sql/format's :inline)."
  [x opts]
  (let [{:keys [value inline]} (literal x)]
    (cond (boolean? x) "boolean"
          (and (some? value) (not inline)) (let [t (jdbc/sent-type value opts)] (when (string? t) t)))))

(defn- sent-typed? [x opts] (some? (literal-sent-type x opts)))

(defn- literal-problem
  "The problem of literal x where a value of type t goes (an argument there takes t), of a column
   or expression of schema s: outside an enum's labels however it is sent, an :enum-literal;
   sent typed where an argument there would refuse it, an :uncast-string for a string, a
   :literal-type otherwise. A literal written into the SQL or sent untyped is held to the labels
   alone, as PostgreSQL reads it as the type's input."
  [registry {x ::literal :keys [column type function context] s :schema} t opts]
  (let [{:keys [value text]} (literal x)
        labels (some->> s (query/enum-values registry))
        ;; of a type pgmalli does not type, an argument takes any value: whether its slot takes a String
        slot (when (and s context (query/untyped? (shape/non-null t))) (query/slot-type registry s))
        refused? (if slot
                   (and (string? value) (not-any? #(accept/accepts? slot % context) (jdbc/string-sends opts)))
                   (refuses? registry t value))]
    (cond ;; a set or a list, which the one sending sends nowhere (pgjdbc infers no type of it)
          (and (coll? value) (not (:inline (:format opts))) (nil? (jdbc/sent-type (syntax/given value) opts)))
          (cond-> {:kind :literal-type :function function :value value :sent nil}
            (= :assign context) (assoc :column column))
          (and labels (not (coll? value)) (not (some #{(or text value)} labels)))
          (cond-> {:kind :enum-literal :value value :allowed labels} column (assoc :column column) type (assoc :type type))
          (and (sent-typed? x opts) refused?)
          (let [u (when (and s (string? value)) (uncast-type registry s opts))]
            (if u
              (cond-> {:kind :uncast-string :value value :type u} column (assoc :column column) (not column) (assoc :function function))
              (cond-> {:kind :literal-type :function function :value value :sent (literal-sent-type x opts)}
                (= :assign context) (assoc :column column)
                (some-> s shape/column-props :pg/type) (assoc :type (query/cast-type-name s opts))))))))

(defn- call-args
  "An argument of a function or an operator: of the types the candidates PostgreSQL may resolve
   the call to take there, as a value of each type sent picks one; nil may go there too. Where
   PostgreSQL refuses a value sent untyped (resolve/untyped-refused?: concat's \"any\",
   cardinality's anyarray), a value sent with a type a candidate takes there (jdbc/typed-where),
   nil neither, {:typed-only function} third: the argument's other uses take no value sent
   untyped either."
  [registry ps opts]
  (for [{:keys [x chain clause]} (syntax/expression-nodes ps)
        :let [[kind n args :as parts] (when-not (or (syntax/comparison? x) (query/type-call registry chain x opts)) (syntax/call-parts x))]
        :when (and kind (some #(or (syntax/param-key %) (sent-typed? % opts)) args))
        :let [type-of #(:type (query/expression-type registry chain % opts))
              ;; a literal judged as an argument: the call's literals taken as its arguments are
              literal-ins (mapv #(if (sent-typed? % opts) :free (type-of %)) args)
              param-ins (mapv type-of args)
              os (query/reached registry opts parts)]
        [j a] (map-indexed vector args)
        :when (or (syntax/param-key a) (sent-typed? a opts))
        :let [all-ins (if (syntax/param-key a) param-ins literal-ins)
              [ins i] (if (and (syntax/rows-from? n args) (some #{clause} (concat syntax/from-keys syntax/join-keys [:join-by]))) [[(nth all-ins j)] 0] [all-ins j])
              ss (map #(query/named-type-schema registry opts %) (resolve/argument-types ins i os))
              ;; another parameter of the call is of a type its value picks: refused whatever type it is
              sibling-ins (reduce (fn [inss j] (if (and (not= j i) (= :free (nth ins j)))
                                                 (let [ts (resolve/argument-types ins j os)]
                                                   (if (seq ts) (for [x inss t ts] (assoc x j t)) inss))
                                                 inss))
                                  [ins] (range (count ins)))
              refused? (and (syntax/param-key a) (not (:inline (:format opts))) (every? #(resolve/untyped-refused? kind % i os) sibling-ins))
              t (call-arg-type registry ss os i opts)]
        :when (or refused? (and t (every? some? ss)))]
    (cond refused? [a (or (some-> t typed-only) (jdbc/typed-where #(resolve/takes-at? ins i os %))) {:typed-only n}]
          (syntax/param-key a) [a t]
          :else [(literal-key a {:function n :schema (when (= 1 (count ss)) (first ss))}) t])))

(defn- null-tested-args
  "The arguments a statement among positions ps tests for NULL ([:= :?x nil], which HoneySQL
   writes ? IS NULL, and [:is :?x nil], IS NOT NULL alike): NULL IS NULL OR id = NULL runs,
   whatever the other uses. Sent as a parameter, NULL there has no type, which PostgreSQL
   refuses (42P18): tested so under sql/format's :inline alone, or cast ([:= [:cast :?x t] nil])."
  [ps {:keys [format]}]
  (for [{:keys [x]} (syntax/expression-nodes ps)
        :let [[v] (syntax/null-test x format)]
        :when v
        :let [cast? (and (vector? v) (= :cast (first v)) (= 3 (count v)))
              k (syntax/param-key (if cast? (second v) v))]
        :when (and k (or cast? (:inline format)))]
    k))

(defn- bare
  "The kind of alternative x as a schema of its own, its checks and properties left out: the
   values of the classes of that kind; nil for a kind whose values cannot be told so."
  [x]
  (let [k (shape/kind x)]
    (cond (#{:map-of} k) [:map-of :any :any]
          (#{:sequential :vector :set} k) [k :any]
          (= :tuple k) [:fn vector?]
          (vector? k) (when (= :fn (first k)) k)
          (or (keyword? k) (symbol? k)) k)))

(defn- enum-head
  "[:enum] of enum x, its properties too: x but its labels."
  [x]
  (subvec x 0 (if (map? (second x)) 2 1)))

(def ^:private classes-of
  "The classes of accept/values whose value alternative x's kind takes, by name; of a PGobject
   predicate, its class; of an enum, its labels' classes; of an array, ::array; ::untold where
   they cannot be told."
  (memo/bounded
   (fn [x]
     (let [x (unlabel x)
           props (shape/props x)]
       (cond (= :pg/array (shape/head x)) #{::array}
             (= :enum (shape/kind x)) (set (map #(.getName (class %)) (shape/entries (shape/and-base x))))
             (and (= :fn (shape/head x)) (:pg/class props)) #{(:pg/class props)}
             :else (if-let [b (bare x)]
                     (try (set (for [[c v] accept/values :when (m/validate b v {:registry registry/base})] (.getName ^Class c)))
                          (catch Exception _ ::untold))
                     ::untold))))))

(defn- meet
  "The alternatives of a and b whose values the other takes too, a's label kept of two alike: of
   two that share a class of accept/values, the one within the other (its classes among the
   other's, the other checking nothing beyond its kind), an enum's labels the other takes, else
   both ([:and x y]); for two arrays, an array of the elements both take. () where none share a
   class; nil where the classes of one cannot be told."
  [a b]
  (let [array? #(= :pg/array (shape/head %))
        plain? #(let [u (unlabel %)] (or (keyword? u) (symbol? u) (= u (bare u))))
        within? (fn [y cx cy] (and (every? cy cx) (plain? y)))
        enum? #(and (vector? %) (= :enum (first %)))
        ;; an enum's labels the other takes: of its classes (cy), or among its labels
        labels (fn [x keep?] (let [ls (filter keep? (shape/entries x))] (when (seq ls) (into (enum-head x) ls))))
        elements (fn [x y]
                   (let [[ex ey] [(last x) (last y)]
                         m (cond (query/untyped? ey) [ex]
                                 (query/untyped? ex) [ey]
                                 :else (meet (shape/non-null ex) (shape/non-null ey)))]
                     (cond (nil? m) ::untold
                           (seq m) (assoc x (dec (count x)) (cond-> (shape/either m nil) (and (shape/maybe? ex) (shape/maybe? ey)) query/nullable)))))
        pairs (for [x (shape/alternatives a), y (shape/alternatives b)
                    :let [cx (classes-of x) cy (classes-of y)]]
                (cond (or (= ::untold cx) (= ::untold cy)) ::untold
                      (not-any? cy cx) nil
                      (and (array? x) (array? y)) (elements x y)
                      (= (unlabel x) (unlabel y)) x
                      ;; of two of the same classes, the one that tells more (a PGobject of interval)
                      (and (within? y cx cy) (within? x cy cx)) (max-key #(count (shape/props %)) y x)
                      (within? y cx cy) x
                      (within? x cy cx) y
                      (and (enum? x) (enum? y)) (labels x (set (shape/entries y)))
                      (and (enum? x) (plain? y)) (labels x #(cy (.getName (class %))))
                      (and (enum? y) (plain? x)) (labels y #(cx (.getName (class %))))
                      :else [:and x y]))]
    (when-not (some #{::untold} pairs)
      ;; an enum's labels kept of two alternatives are one enum; of two alike but for their label, the first
      (let [ps (remove nil? pairs)
            enums (group-by enum-head (filter enum? ps))]
        (->> ps
             (map #(if (enum? %) (into (enum-head %) (distinct (mapcat shape/entries (enums (enum-head %))))) %))
             (reduce (fn [[out seen] p] (let [u (unlabel p)] (if (seen u) [out seen] [(conj out p) (conj seen u)]))) [[] #{}])
             first)))))

(defn- both-uses
  "The type of an argument two of its uses type a and b: PostgreSQL types a parameter once, so
   a value goes where each use takes it; a use that types nothing takes any, nil too."
  [a b]
  (let [;; a NOT NULL column's untyped value ([:some]) takes no nil
        nullable? (every? #(or (= :any (shape/head %)) (shape/maybe? %)) [a b])
        [a b] (map shape/non-null [a b])
        t (cond (query/untyped? a) b
                (query/untyped? b) a
                ;; uses with no value in common, or whose common values cannot be told: each use's (describe's a & b)
                :else (if-let [m (seq (meet a b))] (shape/either m nil) (into [:and {:pgmalli/uses true}] (sort-by pr-str [a b]))))]
    (cond-> t nullable? query/nullable)))

(defn- typed-only
  "t without the values the one sending sends untyped (jdbc/untyped-kinds, a keyword label) and
   nil, which PostgreSQL cannot type where it takes a parameter's type from the value alone; nil
   where none is left."
  [t]
  (let [t (shape/non-null t)]
    (case (shape/head t)
      :and (some->> (typed-only (shape/and-base t)) constantly (shape/update-and-base t))
      :or (some-> (seq (keep typed-only (shape/alternatives t))) (shape/either nil))
      :enum (let [ls (remove keyword? (shape/entries t))]
              (when (seq ls) (into (enum-head t) ls)))
      (when-not (jdbc/untyped-kinds (shape/kind t)) t))))

(defn- with-facts
  "Argument schema t with the facts every use of it in uses ([x t] ...) shares, beside the type
   they relax, for a caller who knows more to narrow it by: :pgmalli/cast, the type each casts it
   to, on the array ([:pg/array ...]) or the values the cast takes, and on the array, of a cast to
   an array type, :pgmalli/element, the type of its elements; :pgmalli/before-triggers, the
   BEFORE row triggers each NOT NULL column it is assigned to has, on that column's type inside
   the [:maybe ...] they make it."
  [t uses]
  (let [shared (fn [k] (let [vs (map #(get (meta (second %)) k) uses)]
                         (when (and (seq vs) (every? some? vs) (apply = vs)) (first vs))))
        cast (shared ::cast)
        element (shared ::cast-element)
        triggers (shared ::before-triggers)
        in-maybe (fn [s f] (if (= :maybe (shape/head s)) (assoc s (dec (count s)) (f (last s))) (f s)))
        on-cast (fn [s p] (if (and (= :or (shape/head s)) (some #(= :pg/array (shape/head %)) (shape/entries s)))
                            (into (subvec s 0 (- (count s) (count (shape/entries s))))
                                  (map #(if (= :pg/array (shape/head %)) (shape/merge-props % p) %) (shape/entries s)))
                            (shape/merge-props s p)))]
    (cond-> t
      cast (in-maybe #(on-cast % (cond-> {:pgmalli/cast cast} element (assoc :pgmalli/element element))))
      (and triggers (= :maybe (shape/head t))) (in-maybe #(shape/merge-props % {:pgmalli/before-triggers triggers})))))

(defn- analysis
  "{:args {arg schema} :untyped {arg {:function f :type t}}} of arg-types, an argument at a position PostgreSQL
   takes its type from the value alone (call-args' :typed-only) with :pgmalli/type-from-value,
   the function, on its schema; :untyped, such an argument whose other uses take no value sent
   with a type, and the type they name."
  [registry body opts]
  (let [uses [comparison-args limit-args truth-tested-args cast-args assignment-args resolving-args call-args]
        sps (query/once registry query/statement-positions body opts)
        typed (concat (for [[_ _ _ ps] sps
                            f uses
                            use (f registry ps opts)]
                        use)
                      (for [{:keys [arg schema]} (query/once registry populate-documents body opts)] [arg schema]))
        only-typed (group-by (comp syntax/param-key first) (filter #(get % 2) typed))
        untyped (into {} (for [k (syntax/query-args body)] [k :any]))
        types (reduce (fn [acc [x t]] (if-let [k (syntax/param-key x)] (update acc k both-uses t) acc))
                      untyped (remove #(get % 2) typed))
        typed-keys (set (keep (comp syntax/param-key first) typed))
        null-tested (set (for [[_ _ _ ps] sps, k (null-tested-args ps opts)] k))
        left (into {} (for [[k t] types] [k (if (and (only-typed k) (not= :any t)) (typed-only t) t)]))]
    {:literals (for [[k t] typed :when (and (map? k) (contains? k ::literal))] [k t])
     :args (into {} (for [[k t] types
                          :let [only (only-typed k)
                                ;; where it goes untyped elsewhere, the typed-only uses' values; else those of both
                                t (cond (nil? only) t
                                        (or (= :any t) (nil? (left k))) (reduce both-uses (map second only))
                                        :else (reduce both-uses (left k) (map second only)))]]
                      [k (cond-> (if (= :any t)
                                   [:any {:pgmalli/untyped (if (typed-keys k) :type :position)}]
                                   (-> (cond-> t (and (null-tested k) (not only)) query/nullable)
                                       (with-facts (filter #(= k (syntax/param-key (first %))) typed))))
                           only (shape/merge-props {:pgmalli/type-from-value (:typed-only (get (first only) 2))}))]))
     :untyped (into {} (for [[k uses] only-typed :when (nil? (left k))]
                         [k {:function (:typed-only (get (first uses) 2))
                             :type (some (comp :pg/type shape/props) (shape/alternatives (shape/non-null (types k))))}]))}))

(defn arg-types
  "{arg schema} for the named parameters of a query (:?id, [:param :id]), keyed as sql/format's
   :params takes them (:id): every one of them, one pgmalli cannot type [:any {:pgmalli/untyped
   why}]. docs/queries.md#the-types-of-the-arguments says where an argument is typed and how."
  [registry body opts]
  (:args (query/once registry analysis body opts)))

(defn literal-problems
  "The problem of each literal at a position PostgreSQL types it at, as literal-problem judges it
   against the type an argument there takes (docs/queries.md#stricter-than-postgresql)."
  [registry body opts]
  (vec (keep (fn [[k t]] (literal-problem registry k t opts)) (:literals (query/once registry analysis body opts)))))

(defn arg-problems
  "An :undetermined-type problem of each argument at a position PostgreSQL takes its type from
   the value alone (cardinality(?), concat(?)), whose other uses take only values sent untyped
   (a keyword label) or nil."
  [registry body opts]
  (vec (for [[k p] (:untyped (query/once registry analysis body opts))]
         (into {:kind :undetermined-type :arg k} (remove (comp nil? val)) p))))
