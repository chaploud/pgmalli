(ns pgmalli-next.impl.args
  "The types of a query's arguments (docs/queries.md#the-types-of-the-arguments)."
  (:require [clojure.walk :as walk]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.resolve :as resolve]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(declare accepted-type cast-arrays)

(defn- array-arg
  "The argument x of [h x] with h one of heads, sent as one array: of array-comparisons by
   default (= ANY(?), <> ALL(?))."
  ([v] (array-arg v syntax/array-comparisons))
  ([v heads] (when (and (vector? v) (= 2 (count v)) (heads (first v)) (syntax/param-key (second v))) (second v))))

(defn- assigned-type
  "The type of an argument assignment a (positions) writes to its column, in context (:assign,
   or :cast where it is cast to the column's type first): NULL where assigned-column says it
   may go. Where the row may not reach the column, without the CHECKs, lengths and ranges
   PostgreSQL checks only on a row it writes."
  [registry {:keys [may-drop?] :as a} context opts]
  (let [{s :schema :keys [nullable?]} (query/assigned-column registry a)]
    (if s
      (let [inner (cond-> (accepted-type registry (cond->> s may-drop? (query/beyond-list registry)) context opts) may-drop? query/loose)]
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
   their elements' place."
  [opts pred element]
  (for [[schema q] (jdbc/write-rows opts true) :when (pred q)]
    [q (walk/postwalk-replace {:pgmalli/element element} schema)]))

(defn- labelled-type
  "The type of an argument where a value of own values own, labels (an enum's, a CHECK's list),
   goes in context, alts the [type alternative] of each type sent it takes, strings? whether a
   String goes as a type that takes the labels: a text label as a String only then, as a keyword
   where a row of keywords goes, unless keywords? is false (HoneySQL writes a keyword in :values
   as a column); a number as the number class of each number row (1.0 as 1 where a bigint goes
   to a double precision). Where no label is sent (an enum's under pgjdbc's
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
        labelled (cond (empty? sent) nil
                       (= sent labels) own
                       ;; a domain's checks stay around its labels; a CHECK's list takes a label as each is sent
                       :else (let [l (shape/update-base own (constantly (into (cond-> [:enum] props (conj props)) sent)))
                                   as-sent #(if (= :enum (shape/head %)) (into % (remove (set (shape/entries %)) (mapcat as (shape/entries %)))) %)]
                               (if (= :and (shape/head l))
                                 (into [(first l)] (map-indexed (fn [i x] (if (> i (if (map? (second l)) 1 0)) (as-sent x) x)) (rest l)))
                                 l)))
        ;; cast, PostgreSQL converts another kind to the number type first (1.5 to 2, true to 1)
        ;; and checks the list after: those values are not held to the labels here
        converted (when (and (= :cast context) (every? number? labels))
                    (distinct (remove #(#{:int 'integer? :string} (shape/kind %)) (map second alts))))
        all (concat (when labelled [labelled]) converted (when (nil? labelled) [jdbc/pg-object]))]
    (if (next all) (into [:or] all) (first all))))

(defn accepted-type
  "The type of an argument where a value of column schema s goes, in context (:compare,
   :assign, :cast or :json), as docs/queries.md#the-types-of-the-arguments says: its own values
   (jdbc/write-type), and the values of each type sent that the column holds (accept/holds?)
   or, cast, that PostgreSQL casts (accept/accepts?); labels as labelled-type sends them."
  [registry s context opts]
  (let [t (query/slot-type registry s)
        opts (cond-> opts (#{"character" "\"char\""} t) (assoc ::jdbc/characters true))
        own (cond-> (query/driver-schema registry jdbc/column-write-type s opts)
              (= :compare context) query/loose
              ;; a cast cuts a string to a varchar(n)'s or a char(n)'s length; a CHECK's bounds hold after it
              (= :cast context) (->> (walk/postwalk #(if (and (vector? %) (= :string (first %)) (:pg/max-length (second %)))
                                                       (update % 1 (fn [p] (cond-> (dissoc p :pg/max-length) (= (:max p) (:pg/max-length p)) (dissoc :max))))
                                                       %))
                                     shape/without-gen))
        ;; cast to an array type, PostgreSQL casts an array element by element, NULL too, to the
        ;; element type; a scalar through the array type's input alike for every element type
        es (when (= :cast context) (some->> (types/element (:pg/type (shape/column-props s))) (query/named-type-schema registry opts)))
        t (or t (when es "text[]"))
        takes? (if (= :cast context) #(accept/accepts? t % context) #(accept/holds? t % context))
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
      (or (nil? t) (and (query/untyped? own) (not es))) own
      (= :enum (shape/kind own)) (labelled-type own alts context (boolean (some #(or (= t %) (takes? %)) (jdbc/string-sends opts))) (not (::keywords-columns opts)))
      :else
      (let [own-alts (when-not (query/untyped? own) (shape/alternatives own))
            ;; a number held to a number column's range, which PostgreSQL checks after converting it;
            ;; a string's or a date's cannot be said of the value sent
            alts (for [[p a] alts] (cond-> a (and (not= :compare context) (accept/number-type? t) (accept/number-type? p)) (within p t own)))
            ;; a cast's array of any elements is broader than an array of the type's own
            cast-kinds (set (map (comp shape/kind second) arrays))
            all (shape/distinct-kinds (concat (remove (comp cast-kinds shape/kind) own-alts) alts))]
        (if (= all own-alts) own (into [:or] all))))))

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
        :let [t (compared-type op (compared-arg-type registry chain side op opts) (when-not elements? x) opts)]
        :when (not (and elements? (query/untyped? t)))]
    [k (if elements? (jdbc/array-type t opts :unnest) t)]))

(defn- limit-args
  "LIMIT and OFFSET take a bigint of at least 0 by assignment, NULL for none."
  [registry ps opts]
  (for [{:keys [x clause top?]} (syntax/expression-nodes ps)
        :when (and top? (#{:limit :offset} clause) (syntax/param-key x))]
    [x (query/nullable (accepted-type registry [:int {:pg/type "bigint" :min 0}] :assign opts))]))

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
        uses (frequencies (map first casts))]
    (for [[x chain k elements?] (distinct casts)
          :let [s (:schema (query/expression-type registry chain x opts))]
          :when s
          :let [e (when-not elements? (unnest-cast-element registry ps x opts))
                t (cond-> (accepted-type registry s :cast (cond-> opts e (assoc ::cast-element e)))
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
   column, as read back; of an array column, a sequential of its elements' (NULL too); of
   another, what its type's input reads of a JSON number, boolean or string (accepted-type
   under :json), no PGobject, which the document does not hold."
  [registry s opts]
  (let [s (shape/non-null s)
        p (:pg/type (shape/column-props s))]
    (cond (types/json? p) (query/driver-schema registry jdbc/column-read-type s opts)
          (types/element p) [:sequential (query/nullable (json-field-type registry (last s) opts))]
          :else (let [t (accepted-type registry s :json (assoc opts ::jdbc/json true))]
                  (shape/either (remove #{jdbc/pg-object} (shape/alternatives t)) t)))))

(defn- one-key-per-column?
  "Whether document m holds no column's key both as a keyword and as a string."
  [m]
  (not-any? #(and (keyword? %) (contains? m (name %))) (keys m)))

(defn- populate-documents
  "[{:arg :schema :columns} ...] of the documents of the calls populate-call tells. schema, of
   arg: for a record, a closed map of the row type's columns, each keyed by its name as a
   keyword or a string (not both), each a json-field-type or nil; for a recordset, a vector of
   them; in place of the collections a json or jsonb value of the parameter takes there (bare, a
   value sent as the function's json or jsonb; cast, what the cast takes), or nil. A key is
   required, its value not nil, where an INSERT ... SELECT writes the column of that name of
   the row type's table straight from it (positions not :may-drop?) and NULL may not go there
   (assigned-column). columns, the set of the columns the statements reading the call's alias
   name of it (resolved as a column of it, in the statement or one nested in it); nil where a
   use names none in particular: a star, its whole row, :raw, or a list of columns renaming
   them."
  [registry body opts]
  (for [sps [(query/once registry query/statement-positions body opts)]
        [_ c _ ps] sps
        {:keys [x chain]} (syntax/expression-nodes ps)
        :let [{:keys [table set? type json arg]} (query/populate-call registry chain x opts)]
        :when arg
        :let [s (if (syntax/param-key json) (types/type-schema type) (:schema (query/expression-type registry chain json opts)))
              base (accepted-type registry s (if (syntax/param-key json) :compare :cast) opts)
              item (some (fn [{i :x}] (when (and (vector? i) (identical? x (first i))) i)) ps)
              alias (when item (second (syntax/table-ref item (:schema opts))))
              ;; whether alias names, in a chain, the call's item: not where a scope nearer has an
              ;; alias of that name, nor in the item itself
              sees? (fn [chain] (identical? (get (first c) alias) (some #(when (map? %) (get % alias)) chain)))
              reads (for [[_ c' _ ps'] sps :when (and alias (sees? c')), n (syntax/expression-nodes ps')] n)
              untold? (fn [{y :x chain :chain}]
                        (or (and (vector? y) (= :raw (first y)))
                            (and (keyword? y) (let [[a column] (syntax/split-column y)]
                                                (or (and (syntax/star? y) (contains? #{nil alias} a))
                                                    (and (nil? a) (= alias (syntax/folded column)) (sees? chain)))))))
              used (when (and alias (not (vector? (second item))) (not-any? untold? reads))
                     (set (for [{y :x chain :chain} reads :when (syntax/column-keyword? y)
                                :let [{:keys [column opaque?] from :table} (query/resolve-column registry chain y)]
                                :when (and opaque? (= alias from))]
                            (keyword column))))
              required (set (for [{c :x :as a} (syntax/assignments ps)
                                  :when (and alias (not (:may-drop? a)) (syntax/column-keyword? c))
                                  :let [{:keys [column opaque?] from :table} (query/resolve-column registry (:chain a) c)
                                        {written :column t :table :keys [nullable? schema]} (query/assigned-column registry a)]
                                  :when (and opaque? (= alias from) (= table t) schema (not nullable?) (= column written))]
                              column))
              fields (for [[c cs] (query/column-entries registry table)] [c (json-field-type registry cs opts) (contains? required c)])
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
    [o (query/nullable (accepted-type registry (query/beyond-list registry (:schema r)) :compare opts))]))

(defn- call-args
  "An argument of a function or an operator: of the types the candidates PostgreSQL may resolve
   the call to take there, as a value of each type sent picks one; nil may go there too. Where
   each of them takes \"any\" (concat, format, jsonb_build_object), a value sent typed alone
   (jdbc/typed): PostgreSQL cannot type a parameter sent untyped there, nil either."
  [registry ps opts]
  (for [{:keys [x chain]} (syntax/expression-nodes ps)
        :let [[kind _ args :as parts] (when-not (or (syntax/comparison? x) (query/type-call registry chain x opts)) (syntax/call-parts x))]
        :when (and kind (some syntax/param-key args))
        :let [ins (mapv #(:type (query/expression-type registry chain % opts)) args)
              os (query/reached registry opts parts)]
        [i a] (map-indexed vector args)
        :when (syntax/param-key a)
        :let [any? (and (not (:inline (:format opts))) (resolve/any-position? ins i os))
              ss (when-not any? (map types/type-schema (resolve/argument-types ins i os)))]
        :when (or any? (and (seq ss) (every? some? ss)))]
    [a (cond any? jdbc/typed
             (next ss) (query/nullable
                        ;; a value of each type, which a :pg/type of one would misname
                        (shape/either (shape/distinct-kinds (mapcat #(map unlabel (shape/alternatives (accepted-type registry % :compare opts))) ss)) :any))
             :else (query/nullable (accepted-type registry (first ss) :compare opts)))]))

(defn- null-tested-args
  "The arguments a statement among positions ps tests for NULL ([:= :?x nil], which HoneySQL
   writes ? IS NULL, and [:is :?x nil], IS NOT NULL alike) under sql/format's :inline: NULL IS
   NULL OR id = NULL runs, whatever the other uses. Sent as a parameter, NULL there has no type,
   which PostgreSQL refuses (42P18)."
  [ps {:keys [format]}]
  (for [{:keys [x]} (when (:inline format) (syntax/expression-nodes ps))
        :when (and (vector? x) (= 3 (count x)) (#{:= :<> :not= :!= :is :is-not} (first x)))
        :let [[_ a b] x]
        [v other] [[a b] [b a]]
        :when (syntax/null-value? other format)
        :let [k (syntax/param-key v)]
        :when k]
    k))

(defn- both-uses
  "The type of an argument two of its uses type a and b: PostgreSQL types a parameter once, so
   a value goes where each use takes it; a use that types nothing takes any. nil where either
   takes it (an IS DISTINCT FROM, a nullable column it is assigned to)."
  [a b]
  (let [nil? (or (shape/maybe? a) (shape/maybe? b))
        [a b] (map shape/non-null [a b])
        ;; every alternative of x is one of y's, but for the :pg/type it names
        within? (fn [x y] (every? (set (map unlabel (shape/alternatives y))) (map unlabel (shape/alternatives x))))
        t (cond (query/untyped? a) b
                (query/untyped? b) a
                (within? b a) b
                (within? a b) a
                :else (into [:and] (sort-by pr-str [a b])))]
    (cond-> t nil? query/nullable)))

(defn arg-types
  "{arg schema} for the named parameters of a query (:?id, [:param :id]), keyed as sql/format's
   :params takes them (:id): every one of them, one pgmalli cannot type [:any {:pgmalli/untyped
   why}]. docs/queries.md#the-types-of-the-arguments says where an argument is typed and how."
  [registry body opts]
  (let [uses [comparison-args limit-args cast-args assignment-args resolving-args call-args]
        sps (query/once registry query/statement-positions body opts)
        typed (concat (for [[_ _ _ ps] sps
                            f uses
                            use (f registry ps opts)]
                        use)
                      (for [{:keys [arg schema]} (query/once registry populate-documents body opts)] [arg schema]))
        untyped (into {} (for [k (syntax/query-args body)] [k :any]))
        types (reduce (fn [acc [x t]] (if-let [k (syntax/param-key x)] (update acc k both-uses t) acc))
                      untyped typed)
        typed-keys (set (keep (comp syntax/param-key first) typed))
        null-tested (set (for [[_ _ _ ps] (query/once registry query/statement-positions body opts), k (null-tested-args ps opts)] k))]
    (into {} (for [[k t] types]
               [k (if (= :any t)
                    [:any {:pgmalli/untyped (if (typed-keys k) :type :position)}]
                    (cond-> t (null-tested k) query/nullable))]))))
