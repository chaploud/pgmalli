(ns pgmalli-next.impl.args
  "The types of a query's arguments (docs/queries.md#the-types-of-the-arguments), and the type
   of an argument where a value of a column goes (accepted-type), which values-schema and
   set-schema type a row's columns with."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.registry :as registry]
            [pgmalli-next.impl.resolve :as resolve]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(declare accepted-type cast-arrays typed-only)

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
  "The type of an argument op compares with side, as compared-schema narrows side's schema; :any
   where side's is not told."
  [registry sc side op opts]
  (if-let [s (query/side-schema registry sc side opts)]
    (accepted-type registry (query/compared-schema registry s op) :compare opts)
    :any))

(defn- unlabel
  "t without the :pg/type it names, which an alternative of another type's values would misname."
  [t]
  (cond (= :or (shape/head t)) (into [:or] (map unlabel (shape/alternatives t)))
        (and (vector? t) (map? (second t))) (let [p (dissoc (second t) :pg/type)] (if (and (empty? p) (= 2 (count t))) (first t) (assoc t 1 p)))
        :else t))

(defn- within
  "Alternative a, a number sent as type p, held to the range a column of type t's own assigned
   values have: their :min and :max, and the checks of an [:and base checks...]. PostgreSQL
   rounds a fraction going to an integer type first, so a fraction is held to the bounds half
   a unit wider, and not to the checks."
  [a p t own]
  (let [base (shape/domain-base own)
        ;; a base's own kind among them (the decimal? a numeric(p,s) domain also holds) bounds no other kind
        checks (when (= :and (shape/head own)) (remove #(= (shape/kind %) (shape/kind base)) (rest (shape/entries own))))
        {:keys [min max]} (when (and (vector? base) (map? (second base))) (second base))
        rounded? (and (accept/integer-type? t) (not (accept/integer-type? p)))
        [min max] (if rounded? [(some-> min (- 0.5)) (some-> max (+ 0.5))] [min max])
        bounds (cond-> {} min (assoc :min min) max (assoc :max max))
        a (cond (empty? bounds) a
                (#{:int :double} (shape/head a)) (if (vector? a) (update a 1 merge bounds) [a bounds])
                :else (into [:and a] (remove nil? [(when min [:>= min]) (when max [:<= max])])))]
    (if (and (seq checks) (not rounded?)) (into [:and a] checks) a)))

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
    [q (walk/postwalk-replace {:pgmalli/element e} schema)]))

(defn- typed-pg-object
  "jdbc/pg-object of type t: what it says where a value of t is refused."
  [t]
  (shape/merge-props jdbc/pg-object
                     {:error/message (str "should be a PGobject of type " t " (a String goes as varchar, which " t
                                          " does not take)")
                      :pgmalli/describe (str "PGobject of " (types/short-name t))}))

(defn- labelled-type
  "The type of an argument where a value of own values own, labels (an enum's, a CHECK's list),
   goes in context, alts the [type alternative] of each type sent it takes, strings? whether a
   String goes as a type that takes the labels: a text label as a String only then, as a keyword
   where a row of keywords goes, unless keywords? is false (HoneySQL writes a keyword in :values
   as a column); a number as the number class of each number row (1.0 as 1 where a bigint goes
   to a double precision, 1 as 1.0 where a double is compared with an integer, as a Float in an
   :enum of its own where a real is). Where no label is sent (an enum's under pgjdbc's
   default, which sends a String as varchar), a PGobject, which pgjdbc sends as the type it names."
  [own alts context strings? keywords?]
  (let [e (shape/domain-base own)
        props (shape/props e)
        labels (shape/entries e)
        kinds (set (map (comp shape/kind second) alts))
        kept (filter #(or strings? (not (string? %))) labels)
        as (fn [l] (concat (when (and keywords? (string? l) (some #(:keyword (shape/value-classes (second %))) alts)) [(keyword l)])
                           ;; a number label as a string, which PostgreSQL reads as the number where a string goes (untyped, or cast)
                           (when (and (number? l) (some #(:string (shape/value-classes (second %))) alts)) [(str l)])
                           (when (number? l)
                             (concat (when (and (kinds :int) (== l (Math/rint (double l)))) [(long l)])
                                     (when (kinds 'decimal?) [(bigdec l)])
                                     (when (kinds :double) [(double l)])))))
        sent (concat kept (remove (set kept) (distinct (mapcat as labels))))
        ;; a default (malli's default-value transformer fills it) as one of the labels sent
        props (if (and (contains? props :default) (not (some #{(:default props)} sent)))
                (if-let [d (first (filter (set sent) (as (:default props))))] (assoc props :default d) (dissoc props :default))
                props)
        labelled (cond (empty? sent) nil
                       (= sent labels) own
                       ;; a domain's checks stay around its labels; a CHECK's list takes a label as each is sent
                       :else (let [l (shape/update-base own (constantly (into (cond-> [:enum] (seq props) (conj props)) sent)))
                                   as-sent #(if (= :enum (shape/head %)) (into % (remove (set (shape/entries %)) (mapcat as (shape/entries %)))) %)]
                               (if (= :and (shape/head l))
                                 (into [(first l)] (map-indexed (fn [i x] (if (> i (if (map? (second l)) 1 0)) (as-sent x) x)) (rest l)))
                                 l)))
        ;; cast, PostgreSQL converts another kind to the number type first (1.5 to 2, true to 1)
        ;; and checks the list after: those values are not held to the labels here
        converted (when (and (= :cast context) (every? number? labels))
                    (distinct (remove #(#{:int 'integer? :string} (shape/kind %)) (map second alts))))
        ;; a set holds 1.0 or a Float 1.0, which are =, not both: an :enum of its own
        floats (when (and labelled (kinds 'float?)) (seq (distinct (map float (filter number? labels)))))
        all (concat (when labelled [labelled]) (when floats [(into [:enum] floats)]) converted (when (nil? labelled) [(if-let [t (:pg/type props)] (typed-pg-object t) jdbc/pg-object)]))]
    (if (next all) (into [:or] all) (first all))))

(defn- untyped-type-values
  "The values of a type whose own pgmalli does not type (interval, inet), s its schema: a
   PGobject, and the values of each type sent that accept/holds? says the type holds in context
   (a LocalTime for interval)."
  [registry s context opts]
  (let [t (query/slot-type registry s)
        pg-object (typed-pg-object t)]
    (distinct (cons pg-object (for [[x p] (jdbc/write-rows opts false)
                                         :when (and (string? p) (not= p t) (accept/holds? t p context))
                                         a (shape/alternatives x)]
                                     (unlabel a))))))

(defn- class-held
  "A function's argument's alternative a held to the classes of its schema the one sending sends
   typed as t or a type takes? takes (ntile(?) takes an Integer, sent as integer, not a Long, sent
   as bigint, which PostgreSQL does not convert to integer implicitly); nil where it sends none of
   them so (a real argument's Double, sent as double precision)."
  [a t takes? opts]
  (let [k (shape/kind a)
        rows (filter (fn [[s p]] (and (not= :unknown p) (some #(= k (shape/kind %)) (shape/alternatives s)))) (jdbc/write-rows opts false))
        [in out] ((juxt filter remove) (fn [[_ p]] (or (= t p) (takes? p))) rows)
        named (fn [[_ _ ^Class c]] (let [n (.getSimpleName c)] (str (if (#{\A \E \I \O \U} (first n)) "an " "a ") n)))
        hint (some (fn [[_ _ c]] ({Integer "(int n)" Short "(short n)"} c)) in)]
    (cond (or (empty? out) (not (string? t))) a
          (empty? in) nil
          :else (let [cs (map (fn [[_ _ ^Class c]] (.getName c)) in)]
                  [:and {:gen/schema (into [:pg/class] cs)} a
                   (into [:pg/class {:error/message (str "should be " (str/join " or " (map named in))
                                                         " (sent as " (str/join " or " (distinct (map second in))) ", which PostgreSQL takes as " t "); "
                                                         (str/join ", " (map #(str (named %) " goes as " (second %)) out))
                                                         ", which it does not convert to " t " implicitly: send " (when hint (str hint " or "))
                                                         "cast the parameter, [:cast <parameter> :" (str/replace t " " "-") "]")}]
                         cs)]))))

(defn- class-names [a] (when (and (= :and (shape/head a)) (= :pg/class (shape/head (last a)))) (shape/entries (last a))))

(defn- classes-joined
  "Alternatives as of several candidates' types, a kind held to classes (class-held) in one held
   to theirs together, or free where one is free (generate_series(1, ?) takes an Integer as
   integer, a Long as bigint)."
  [as]
  (let [by-kind (group-by shape/kind as)]
    (distinct (for [a as :let [same (by-kind (shape/kind a))]]
                (cond (not-every? class-names same) (first (remove class-names same))
                      :else (let [[b c] (shape/entries (first same))
                                  cs (distinct (mapcat class-names same))]
                              [:and {:gen/schema (into [:pg/class] cs)} b (into [:pg/class (shape/props c)] cs)]))))))

(defn accepted-type
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
               (= :json held-in) (assoc ::jdbc/json true)
               ;; a cast cuts a string to a varchar(n)'s or a char(n)'s length; its CHECKs read what is left.
               ;; Compared, PostgreSQL holds no value to the column's typmod
               (= :cast held-in) (assoc ::jdbc/typmod :cut)
               (#{:compare :call :common} held-in) (assoc ::jdbc/typmod :none))
        own (cond-> (query/driver-schema registry jdbc/column-write-type s opts)
              (= :compare context) query/loose
              (= :cast context) shape/without-gen)
        ;; cast to an array type, PostgreSQL casts an array element by element, NULL too, to the
        ;; element type; a scalar through the array type's input alike for every element type
        es (when (= :cast context) (some->> (types/element (:pg/type (shape/column-props s))) (query/named-type-schema registry opts)))
        t (or t (when es "text[]"))
        takes? (if (= :cast context) #(accept/accepts? t % context) #(accept/holds? t % held-in))
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
                    a (if (= :unknown p)
                        (jdbc/sent-as opts :unknown #(accept/reads? t % context))
                        (shape/alternatives (unlabel (query/loose (query/driver-schema registry jdbc/column-write-type (types/type-schema p) opts)))))
                    :when (and (not (query/untyped? a))
                               (not (and (= :cast context) (accept/scalar-cast? t p) (shape/collection-kinds (shape/kind a)))))]
                [p a])
              arrays)]
    (cond
      ;; a cast takes a string and every explicit cast, of types pgmalli-next.impl.accept does not answer for
      (and (nil? t) (= :cast context)) :any
      ;; compared, assigned, a function's argument or a :common operand, of a type whose values
      ;; pgmalli does not type and pgjdbc reads as a PGobject (a geometric, network or timespan one:
      ;; inet, interval), that and what converts to it, not any value
      (and (string? t) (query/untyped? own) (not es) (#{:compare :assign} context) (#{"G" "I" "T"} (types/category t)))
      (shape/either (untyped-type-values registry s held-in opts) :any)
      (or (nil? t) (and (query/untyped? own) (not es))) own
      ;; a list's labels, held as the column's typmod holds them (a varchar(n)'s spaces past n cut)
      (= :enum (shape/kind own))
      (let [[typmod list] (if (= :pg/held (shape/head own)) [(second own) (last own)] [nil own])]
        (jdbc/typmod-held (labelled-type list alts context (boolean (some #(or (= t %) (takes? %)) (jdbc/string-sends opts))) (not (::keywords-columns opts)))
                          typmod))
      :else
      (let [own-alts (when-not (query/untyped? own) (shape/alternatives own))
            ;; a number held to a number column's range, which PostgreSQL checks after converting it, a
            ;; keyword or a Character to a string column's through its text (jdbc/text-held); a
            ;; date's cannot be said of the value sent
            ;; and held as the column's typmod holds it (a Long rounded to a numeric(6,2)'s scale, held to its precision)
            typmod (some #(when (= :pg/held (shape/head %)) (second %)) (tree-seq vector? seq own))
            alts (for [[p a] alts] (cond-> a
                                     (and (not= :compare context) (accept/number-type? t) (accept/number-type? p)) (within p t own)
                                     (not= :compare context) (jdbc/text-held own)
                                     (not= :compare context) (jdbc/typmod-held typmod)))
            ;; a cast's array of any elements is broader than an array of the type's own
            cast-kinds (set (map (comp shape/kind second) arrays))
            all (cond->> (shape/distinct-kinds (concat (remove (comp cast-kinds shape/kind) own-alts) alts))
                  (and (= :call held-in) (not (::common-type opts))) (keep #(class-held % t takes? opts)))]
        (cond (or (= all own-alts) (empty? all)) own (next all) (into [:or] all) :else (first all))))))

(defn- compared-type
  "The type of an argument compared with a column by op, t as accepted-type gives it compared
   (its CHECKs, lengths and ranges left out: PostgreSQL compares a value beyond them, matching
   no row), without NULL; :in's a non-empty vector, list or set of it, as
   sql/format expands it (IN () is not SQL); an array's the Java arrays the one sending sends (jdbc/array-type).
   NULL is compared with IS NULL in the query body."
  [op t array? opts]
  (cond-> (cond array? (jdbc/array-type t opts :compare)
                (and (syntax/in-ops op) (not (query/untyped? t))) [:or [:sequential {:min 1} t] [:set {:min 1} t]]
                :else t)
    (syntax/distinct-ops op) query/nullable))

(defn- value-arg
  "[argument array?] of a value where an argument goes: an argument itself, or a column of
   unnest(?) of arguments (array? true: the argument's elements go there); nil for any other."
  [registry chain x]
  (cond (syntax/param-key x) [x false]
        (syntax/column-keyword? x) (let [e (:element (query/resolve-column registry chain x))] (when (syntax/param-key e) [e true]))))

(defn- comparison-args
  "An argument compared with a column or a typed expression, as compared-type types it; an
   unnest's column, its argument an array of that."
  [registry ps opts]
  (for [[op side value chain] (syntax/comparison-sides ps)
        :let [x (array-arg value)
              [k elements?] (if x [x false] (value-arg registry chain value))]
        :when (and k (not (and elements? (or (syntax/in-ops op) (second (value-arg registry chain side))))))
        :let [;; ANY(?)'s elements of a domain's base type, which a CHECK's list there reads as (a list over varchar takes a String[])
              base (when (and x (not elements?)) (some->> (query/side-schema registry chain side opts) (query/slot-type registry)))
              t (compared-type op (compared-arg-type registry chain side op opts) (when-not elements? x)
                               (cond-> opts (string? base) (assoc ::jdbc/element-type base)))]
        :when (not (and elements? (query/untyped? t)))]
    [k (if elements? (jdbc/array-type t opts :unnest) t)]))

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
  "[:cast :?x :timestamptz]: what PostgreSQL casts to the type, its CHECKs, lengths and ranges
   left out where every use of the cast is compared; nil too unless every use of the
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
                    :let [[k elements?] (value-arg registry chain (second x))]
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
                      (some->> (for [s (map types/type-schema (resolve/argument-types [:free] 0 os)) :when s
                                     a (shape/alternatives (shape/non-null (accepted-type registry s :call opts)))]
                                 (unlabel a))
                               seq shape/distinct-kinds)))]
    (for [[x chain k elements?] (distinct casts)
          :let [s (:schema (query/expression-type registry chain x opts))]
          :when s
          :let [e (when-not elements? (unnest-cast-element registry ps x opts))
                t (accepted-type registry s :cast (cond-> opts e (assoc ::cast-element e)))
                t (if-let [more (some-> (calls x) call-type)]
                    (shape/either (shape/distinct-kinds (concat (shape/alternatives (shape/non-null t)) more)) t)
                    t)
                t (cond-> t
                    ;; compared, PostgreSQL evaluates the cast only where its plan reaches it (a row
                    ;; the other conditions keep, or once ahead of the scan), so its CHECKs and ranges may never run
                    (= (get compared x 0) (uses x)) query/loose
                    (< (get refusing x 0) (uses x)) query/nullable)]]
      [k (if elements? (cast-arrays registry s t opts) t)])))

(defn- assignment-args
  "An argument assigned to a column; unnest(?), of a typed array (unnest(unknown) is ambiguous),
   or an unnest's column, each element assigned to it."
  [registry ps opts]
  (for [{:keys [x chain] :as a} (syntax/assignments ps)
        :let [t (assigned-type registry a :assign opts)
              u (array-arg x #{:unnest})
              [k elements?] (if u [u true] (value-arg registry chain x))]
        :when k]
    [k (if elements? (jdbc/array-type t opts :unnest) t)]))

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
          (types/element p) [:or [:sequential (query/nullable (json-field-type registry (last s) opts))]
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
   the row type's table straight from it and NULL may not go there
   (assigned-column). columns, the set of the columns the statements reading the call's alias
   name of it (resolved as a column of it, in the statement or one nested in it); nil where a
   use names none in particular: a star, its whole row, :raw, or a list of columns renaming
   them."
  [registry body opts]
  (for [sps [(query/once registry query/statement-positions body opts)]
        [_ c _ ps] sps
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
              required (set (for [{c :x :as a} (syntax/assignments ps)
                                  :when (and alias (syntax/column-keyword? c))
                                  :let [{:keys [column opaque?] from :table} (query/resolve-column registry (:chain a) c)
                                        {written :column t :table :keys [nullable? schema]} (query/assigned-column registry a)]
                                  :when (and opaque? (= alias from) (or (nil? table) (= table t)) schema (not nullable?) (= column written))]
                              column))
              fields (for [[c cs] columns] [c (if cs (json-field-type registry cs opts) :any) (contains? required c)])
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

(defn- resolving-operands
  "The operands x has PostgreSQL resolves to one type: coalesce's, greatest's, least's,
   nullif's, CASE's values."
  [x]
  (cond (or (query/common-type-functions (syntax/called x)) (= "nullif" (syntax/called x))) (rest x)
        (and (vector? x) (= :case (first x)) (even? (count (rest x)))) (map second (partition 2 (rest x)))))

(defn- resolving-args
  "coalesce(?, col), CASE ... ELSE col and the like: PostgreSQL resolves an argument to the
   others' type; nil is a value there."
  [registry ps opts]
  (for [{:keys [x chain]} (syntax/expression-nodes ps)
        :let [operands (resolving-operands x)]
        :when (some syntax/param-key operands)
        :let [r (query/common registry (map #(query/expression-type registry chain % opts) (remove syntax/param-key operands)))]
        :when (:schema r)
        o operands
        :when (syntax/param-key o)]
    ;; the others' values join a CHECK's list: its base type
    [o (query/nullable (accepted-type registry (query/beyond-list registry (:schema r)) :common opts))]))

(defn- call-arg-type
  "What a value takes at argument i of a call PostgreSQL may resolve to candidates os, ss the
   schemas of the types they take there: a value of each type, which a :pg/type of one would
   misname; nil may go there too."
  [registry ss os i opts]
  (let [;; anyarray there (&&, @>): an array of the type it binds alone, no array PostgreSQL would convert
        opts (cond-> opts
               (some #(= "anyarray" (get (:args %) i)) os) (assoc ::jdbc/array-use :whole)
               ;; anycompatible: of the common type the argument takes part in deciding (array_append(int[], bigint))
               (some #(str/starts-with? (str (get (:args %) i)) "anycompatible") os) (assoc ::common-type true))]
    (cond (empty? ss) nil
          (next ss) (query/nullable
                     ;; beside others, a type pgmalli does not type (pg_lsn for +) takes a PGobject, not any value
                     (shape/either (shape/distinct-kinds
                                    (classes-joined (for [s ss, x (shape/alternatives (accepted-type registry s :call opts))]
                                                      (if (= :any (shape/head x)) jdbc/pg-object (unlabel x)))))
                                   :any))
          :else (query/nullable (accepted-type registry (first ss) :call opts)))))

(defn- sent-literal
  "The value of x where HoneySQL sends it as a parameter (2, \"a\", [:lift 2]) of a class the one
   sending sends typed; nil for another x, or under sql/format's :inline."
  [x opts]
  (let [v (if (and (vector? x) (= :lift (first x)) (= 2 (count x))) (second x) x)]
    (when (and (or (number? v) (string? v) (boolean? v))
               (string? (jdbc/sent-type v opts)))
      v)))

(defn- refuses?
  "Whether argument schema t refuses literal v."
  [registry t v]
  (not (try (m/validate t v {:registry (merge registry/base (::query/schemas registry))}) (catch Exception _ true))))

(defn- literal-comparison-problems
  "Of a number or a boolean compared with a column or a typed expression, as compared-arg-type
   types an argument there ([:= :nick 1]: character varying = bigint does not exist); a string's
   is an :uncast-string, a label list's an :enum-literal."
  [registry ps opts]
  (for [[op side value chain] (syntax/comparison-sides ps)
        :let [v (sent-literal value opts)]
        :when (and (some? v) (not (string? v)))
        :let [s (some-> (query/side-schema registry chain side opts) (#(query/compared-schema registry % op)))]
        :when (and s (empty? (query/enum-values registry s)) (refuses? registry (accepted-type registry s :compare opts) v))]
    {:kind :literal-type :function (name op) :value v :sent (jdbc/sent-type v opts)
     :type (:pg/type (shape/column-props s))}))

(defn- literal-call-problems
  "Of each literal of a call at an argument that refuses it (call-arg-type), the call's literals
   taken as its arguments are: [:round :price 2] sends 2 as a Long, a bigint, which
   round(numeric, integer) does not take."
  [registry ps opts]
  (for [{:keys [x chain clause]} (syntax/expression-nodes ps)
        :let [[_ n args :as parts] (when-not (or (syntax/comparison? x) (query/type-call registry chain x opts)) (syntax/call-parts x))]
        :when (and n (some #(sent-literal % opts) args)
                   (not (and (= "unnest" n) (some #{clause} (concat syntax/from-keys syntax/join-keys [:join-by])))))
        :let [ins (mapv #(if (sent-literal % opts) :free (:type (query/expression-type registry chain % opts))) args)
              os (query/reached registry opts parts)]
        [i a] (map-indexed vector args)
        :let [v (sent-literal a opts)]
        :when (some? v)
        :let [ss (map types/type-schema (resolve/argument-types ins i os))
              t (when (every? some? ss) (call-arg-type registry ss os i opts))]
        :when (and t (refuses? registry t v))]
    (cond-> {:kind :literal-type :function n :value v :sent (jdbc/sent-type v opts)}
      (= 1 (count ss)) (assoc :type (:pg/type (shape/column-props (first ss)))))))

(defn literal-problems
  "A :literal-type problem of each literal HoneySQL sends as a parameter where an argument there
   would refuse it: PostgreSQL resolves the call or the comparison by the type pgjdbc sends it as."
  [registry body opts]
  (vec (for [[_ _ _ ps] (query/once registry query/statement-positions body opts)
             p (concat (literal-comparison-problems registry ps opts) (literal-call-problems registry ps opts))]
         p)))

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
        :when (and kind (some syntax/param-key args))
        :let [ins (mapv #(:type (query/expression-type registry chain % opts)) args)
              os (query/reached registry opts parts)]
        [j a] (map-indexed vector args)
        :when (syntax/param-key a)
        ;; unnest(a, b) in FROM is ROWS FROM (unnest(a), unnest(b)) to PostgreSQL: each argument its own call
        :let [[ins i] (if (and (= "unnest" n) (next args) (some #{clause} (concat syntax/from-keys syntax/join-keys [:join-by]))) [[(nth ins j)] 0] [ins j])
              ss (map types/type-schema (resolve/argument-types ins i os))
              ;; another parameter of the call is of a type its value picks: refused whatever type it is
              sibling-ins (reduce (fn [inss j] (if (and (not= j i) (= :free (nth ins j)))
                                                 (let [ts (resolve/argument-types ins j os)]
                                                   (if (seq ts) (for [x inss t ts] (assoc x j t)) inss))
                                                 inss))
                                  [ins] (range (count ins)))
              refused? (and (not (:inline (:format opts))) (every? #(resolve/untyped-refused? kind % i os) sibling-ins))
              t (call-arg-type registry ss os i opts)]
        :when (or refused? (and t (every? some? ss)))]
    (if refused?
      [a (or (some-> t typed-only) (jdbc/typed-where #(resolve/takes-at? ins i os %))) {:typed-only n}]
      [a t])))

(defn- null-tested-args
  "The arguments a statement among positions ps tests for NULL ([:= :?x nil], which HoneySQL
   writes ? IS NULL, and [:is :?x nil], IS NOT NULL alike): NULL IS NULL OR id = NULL runs,
   whatever the other uses. Sent as a parameter, NULL there has no type, which PostgreSQL
   refuses (42P18): tested so under sql/format's :inline alone, or cast ([:= [:cast :?x t] nil])."
  [ps {:keys [format]}]
  (for [{:keys [x]} (syntax/expression-nodes ps)
        :when (and (vector? x) (= 3 (count x)) (#{:= :<> :not= :!= :is :is-not} (first x)))
        :let [[_ a b] x]
        [v other] [[a b] [b a]]
        :when (syntax/null-value? other format)
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
  (memoize
   (fn [x]
     (let [x (unlabel x)
           props (shape/props x)]
       (cond (= :pg/array (shape/head x)) #{::array}
             (= :enum (shape/kind x)) (set (map #(.getName (class %)) (shape/entries (shape/domain-base x))))
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
  (let [nullable? (every? #(or (query/untyped? %) (shape/maybe? %)) [a b])
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
      :and (some->> (typed-only (shape/domain-base t)) constantly (shape/update-base t))
      :or (some-> (seq (keep typed-only (shape/alternatives t))) (shape/either nil))
      :enum (let [ls (remove keyword? (shape/entries t))]
              (when (seq ls) (into (enum-head t) ls)))
      (when-not (jdbc/untyped-kinds (shape/kind t)) t))))

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
    {:args (into {} (for [[k t] types
                          :let [only (only-typed k)
                                ;; where it goes untyped elsewhere, the typed-only uses' values; else those of both
                                t (cond (nil? only) t
                                        (or (= :any t) (nil? (left k))) (reduce both-uses (map second only))
                                        :else (reduce both-uses (left k) (map second only)))]]
                      [k (cond-> (if (= :any t)
                                   [:any {:pgmalli/untyped (if (typed-keys k) :type :position)}]
                                   (cond-> t (and (null-tested k) (not only)) query/nullable))
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

(defn arg-problems
  "An :undetermined-type problem of each argument at a position PostgreSQL takes its type from
   the value alone (cardinality(?), concat(?)), whose other uses take only values sent untyped
   (a date sent as a LocalDate, a keyword label) or nil."
  [registry body opts]
  (vec (for [[k p] (:untyped (query/once registry analysis body opts))]
         (into {:kind :undetermined-type :arg k} (remove (comp nil? val)) p))))
