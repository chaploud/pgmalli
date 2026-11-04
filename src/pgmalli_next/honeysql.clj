(ns pgmalli-next.honeysql
  "HoneySQL query data read against a registry, without a database (analyze): the problems
   PostgreSQL would report, the types of the query's arguments and of the rows it returns, and
   the tables it reads and writes; query-schema makes a function schema of them. values-schema
   and set-schema type a row written into a table.

   Names resolve as PostgreSQL resolves them (docs/queries.md#what-a-name-refers-to). Types are
   what the one reading reads and the one sending sends (docs/reading-rows.md). The functions
   take the options of docs/queries.md#options; a key or value not among them is an error."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [honey.sql :as sql]
            malli.core
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.resolve :as resolve]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(declare accepted-type cast-arrays)

(defn- output-column?
  "Whether a bare :col of :order-by or :group-by may mean an output column of stmt."
  [stmt col]
  (boolean (and (not (first (syntax/split-column col)))
                (some #(= (syntax/folded (name col)) (syntax/item-name %)) (syntax/selected-items stmt)))))

(defn- output-statement
  "The statement whose select names a statement's output columns: itself, or the first member
   of a set operation (UNION ...)."
  [stmt]
  (if-let [members (some stmt syntax/set-operation-keys)]
    (when (and (vector? members) (map? (first members))) (output-statement (first members)))
    stmt))

(defn- untold-outputs?
  "Whether stmt's select may make output names not read here: a :raw item, or, when stars?
   (the first member of a set operation, whose output names are all ORDER BY sees), a star."
  [stmt stars?]
  (boolean (some #(let [e (syntax/item-expression %)]
                    (or (and (vector? e) (= :raw (first e)))
                        (and stars? (some-> (first (syntax/select-parts %)) syntax/star?))))
                 (syntax/selected-items stmt))))

(defn- ordering-columns
  "The input columns :order-by and :group-by name as they are (:col, or [:col :asc]);
   expressions there are not read. As PostgreSQL reads them, a bare name in :order-by is an
   output column first (of a set operation, its first member's), one in :group-by an input
   column first."
  [registry sc stmt]
  (let [out (output-statement stmt)
        output? (fn [s c stars?] (or (output-column? s c) (and (nil? (first (syntax/split-column c))) (untold-outputs? s stars?))))]
    (concat (for [c (syntax/vectorish (:group-by stmt))
                  :when (and (syntax/column-keyword? c) (not (and (output? stmt c false) (empty? (query/column-hits registry sc c)))))]
              c)
            (when out
              (for [o (syntax/vectorish (:order-by stmt)) :let [c (syntax/bare o)]
                    :when (and (syntax/column-keyword? c) (not (output? out c (not= out stmt))))]
                c)))))

(defn- whole-row?
  "Whether an unqualified :x names a table of the scope rather than a column: its whole row."
  [scope col]
  (let [[a column] (syntax/split-column col)]
    (boolean (and (nil? a) (some #(and (map? %) (contains? % (syntax/folded column))) (syntax/chain scope))))))

(defn- near-columns
  "Up to three columns of the scope's tables spelled close to col's name, qualified as col is:
   what it likely meant."
  [registry scope col]
  (let [[a c] (syntax/split-column col)
        c (syntax/folded c)
        ;; a qualified name looks in the innermost table of its alias, which hides outer ones
        scopes (filter map? (syntax/chain scope))
        scopes (if a (take 1 (filter #(contains? % a) scopes)) scopes)
        names (distinct (for [sc scopes
                              [alias {:keys [table]}] sc :when (and table (or (nil? a) (= a alias)))
                              n (keys (query/table-columns registry table)) :when (not (query/system-columns n))]
                          n))]
    (some->> (error/suggestions c names 3) (mapv #(keyword (if a (str a "." %) %))))))

(defn- unknown-qualifier
  "[qualifier meant] when no table or alias in scope has col's qualifier, nil otherwise. meant
   is col's column qualified by the aliases in scope whose table has it and whose alias or table
   name is the qualifier or two edits from it, closest first: an aliased table named by its
   name, as PostgreSQL's \"Perhaps you meant to reference the table alias\", or a misspelt alias."
  [registry scope col]
  (let [[a c] (syntax/split-column col)
        scopes (filter map? (syntax/chain scope))]
    (when (and a (not (some #{:open} (syntax/chain scope))) (not-any? #(contains? % a) scopes))
      (let [c (syntax/folded c)
            meant (for [sc scopes
                        [alias {:keys [table order]}] sc
                        :when (or (and table (contains? (query/table-columns registry table) c)) (some #{c} order))
                        :let [d (apply min (map #(error/edit-distance a %) (remove nil? [alias (some-> table syntax/table-name)])))]
                        :when (<= d 2)]
                    [(keyword (str alias "." c)) d])]
        [a (some->> (seq meant) (sort-by second) (map first) distinct (take 3) vec)]))))

(defn- column-problems [registry sc stmt ps {:keys [schema] :as opts}]
  (let [target (syntax/target-scope sc stmt schema)
        assigned (when (seq target) (concat (map #(or (syntax/field-column %) %) (filter syntax/column-keyword? (:columns (syntax/insert-parts stmt))))
                                            (filter syntax/column-keyword? (map (comp syntax/target-column first) (syntax/writes stmt)))))]
    (for [[col scope] (distinct (concat (for [c (keep #(first (syntax/select-parts % opts)) (syntax/selected-items stmt))] [c sc])
                                        (for [c (ordering-columns registry sc stmt)] [c sc])
                                        (for [[_ c _ chain] (syntax/comparison-sides ps) :when (syntax/column-keyword? c)] [c chain])
                                        (for [{:keys [x chain]} (syntax/expression-nodes ps)
                                              :when (and (vector? x) (= 3 (count x)) (#{:is :is-not} (first x))
                                                         (syntax/column-keyword? (second x)))]
                                          [(second x) chain])
                                        ;; a keyword assigned in SET is a column the statement sees
                                        (for [{:keys [x chain clause]} (syntax/assignments ps)
                                              :when (and (#{:set :do-update-set} clause) (syntax/column-keyword? x))]
                                          [x chain])
                                        (for [c assigned] [c target])))
          :when (if (or (syntax/star? col) (whole-row? scope col))
                  ;; :t.* names a table of the scope, unless the data does not show them all
                  (let [[a] (syntax/split-column col)]
                    (and (syntax/star? col) a (not (some #{:open} (syntax/chain scope))) (not-any? #(and (map? %) (contains? % a)) (syntax/chain scope))))
                  (nil? (query/resolve-column registry scope col)))
          :let [hits (query/column-hits registry scope col)]]
      (if (< 1 (count hits))
        {:kind :ambiguous-column :column col :candidates (mapv (fn [[alias table]] (or table alias)) hits)}
        (let [[qualifier meant] (unknown-qualifier registry scope col)
              near (if qualifier meant (near-columns registry scope col))]
          (cond-> {:kind :unknown-column :column col}
            qualifier (assoc :qualifier qualifier)
            near (assoc :did-you-mean near)))))))

(defn- insert-problems [registry stmt schema]
  (let [{:keys [table columns]} (syntax/insert-parts stmt)
        [table] (some-> table (syntax/table-ref schema))]
    (when table
      (let [values (:values stmt)
            rows (when (vector? values) values)
            ;; HoneySQL inserts the union of the columns of all value maps, NULL where a row lacks one
            given (or columns (when (map? (first rows)) (distinct (mapcat keys (filter map? rows)))))
            given (when (every? keyword? given) given)
            ;; HoneySQL writes :group-id as group_id
            given-names (into #{} (mapcat (fn [k] [(name k) (syntax/folded (name k))])) given)
            required (set (for [[k p] (some-> (get (::query/schemas registry) (shape/derived-name (shape/table-key table) "insert")) shape/column-entries)
                                :when (not (:optional p))]
                            (name k)))]
        (concat
         (when columns
           (for [[i row] (map-indexed vector (filter vector? rows)) :when (not= (count row) (count columns))]
             {:kind :values-arity :table table :row i :columns (count columns) :values (count row)}))
         (when (and (seq given) (not (query/filled-by-trigger? (query/table-props registry table) :insert)))
           (for [c required :when (not (given-names c))]
             {:kind :missing-required-column :table table :column (keyword c)})))))))

(defn- write-problems
  "Writes PostgreSQL refuses: NULL into a NOT NULL column, and a value other than DEFAULT into
   a column only the database writes (generated, or identity ALWAYS without OVERRIDING SYSTEM
   or USER VALUE, which the first writes and the second ignores). A column one map of :values
   lacks and another has is NULL, as HoneySQL writes it, DEFAULT under sql/format's
   :values-default-columns (in :format). Under a column list, which map keys reach which column
   depends on the HoneySQL version (2.8.85 follows the list): missing keys are not judged there."
  [registry scopes stmt {:keys [schema format]}]
  (let [target (syntax/target-scope scopes stmt schema)
        i (:insert-into stmt)
        overriding? (and (vector? i) (map? (first i)) (#{:system :user} (:overriding-value (first i))))
        defaults (set (:values-default-columns format))]
    (when (seq target)
      (distinct
       (for [[col v how clause] (syntax/writes stmt #(when (defaults %) [:default]))
             :when (syntax/column-keyword? col)
             :let [{:keys [table] s :schema} (query/resolve-column registry target col)
                   cp (some-> s shape/column-props)
                   p (cond (or (nil? s) ((syntax/default-values format) v)) nil
                           (and (shape/written-by-database? cp)
                                (not (and overriding? (= :insert how) (= :always (:pg/identity cp)))))
                           {:kind :read-only-column :table table :column col}
                           (and (syntax/null-value? v format) (not (shape/maybe? s)) (not (query/filled-by-trigger? (query/table-props registry table) how)))
                           {:kind :not-null-violation :table table :column col :clause clause})]
             :when p]
         p)))))

(defn- uncast-type
  "The type to cast a string to for column s: an enum type (or a domain over one), an array, a
   type of types' :cast (bit takes varbit, which keeps its length), or any other type types
   has; nil for a string type (text, varchar, char, name, citext) or a type types lacks (a
   domain over a base type among them)."
  [registry s]
  (let [p (shape/column-props s)
        e (types/entry (shape/type-name p))
        ;; :bit-varying or :timestamp-with-time-zone is no type HoneySQL writes; its alias is
        spelt #(if (str/includes? % " ") (first (:aliases (types/entry %))) %)]
    (or (query/enum-type registry s)
        (when e
          (cond (types/element (:pg/type p)) (types/array-of (spelt (:name e)))
                (:cast e) (spelt (name (:cast e)))
                (not= :string (:schema e)) (spelt (:name e)))))))

(defn- takes-string?
  "Whether a column of schema s takes a string sent as one of types, compared or assigned; nil
   where pgmalli-next.impl.accept does not answer."
  [registry s assigned? types]
  (when-let [t (query/slot-type registry s)] (boolean (some #(accept/accepts? t % (if assigned? :assign :compare)) types))))

(defn- parse-number
  "The number string v is as PostgreSQL reads a numeric input: spaces around it, PostgreSQL 16's
   underscores between digits or after a base prefix, and 0x, 0o and 0b integers; nil for none."
  [v]
  (let [t (str/replace (str/trim v) #"(?<=[0-9a-fA-FxXoObB])_(?=[0-9a-fA-F])" "")
        [_ sign base digits] (re-matches #"([+-]?)0([xXoObB])([0-9a-fA-F]+)" t)]
    (try (if base
           (cond-> (bigint (BigInteger. ^String digits (int (case (str/lower-case base) "x" 16 "o" 8 "b" 2)))) (= "-" sign) -)
           (bigdec t))
         (catch NumberFormatException _ nil))))

(defn- label=
  "Whether literal v is label l as PostgreSQL reads it: a number of the same value, or a string,
   which it reads as a number label's type ('1' is 1)."
  [l v]
  (or (= l v)
      (and (number? l) (number? v) (== l v))
      (and (number? l) (string? v) (some-> (parse-number v) (== l)))))

(defn- string-problems
  "Literals outside an enum type's labels, compared or assigned, or outside a CHECK's list,
   assigned or under equality-ops; and a string literal HoneySQL sends as a parameter (not
   inlined, not cast; [:lift s] too), which the one sending sends as varchar, to a column
   PostgreSQL takes no such string for (enum = varchar does not exist, nor inet = varchar). A
   named argument is arg-types', which types it by the class sent."
  [registry ps opts]
  (let [uses (concat (for [[op side value chain] (syntax/comparison-sides ps)
                           :when (not (syntax/like-ops op))
                           :let [s (query/side-schema registry chain side opts)]
                           :when (or s (syntax/column-keyword? side))]
                       {:col side :value value :op op :schema s})
                     (for [{:keys [assign x target] :as a} (syntax/assignments ps) :when (seq target)
                           :let [{s :schema} (query/assigned-column registry a)]]
                       {:col assign :value x :assigned? true :schema s}))]
    (for [{:keys [col assigned? op] s :schema :as use} uses
          :let [lifted? #(and (vector? %) (= :lift (first %)) (string? (second %)))
                unlift #(if (lifted? %) (second %) %)
                value (let [v (:value use)] (if (and (vector? v) (not (keyword? (first v)))) (mapv unlift v) (unlift v)))
                strings? (or (string? value) (and (vector? value) (every? string? value)))
                numbers? (or (number? value) (and (vector? value) (seq value) (every? number? value)))
                values (cond (or strings? numbers?) (if (vector? value) value [value])
                             (and (vector? value) (#{:cast :inline} (first value)) (or (string? (second value)) (number? (second value)))) [(second value)])
                allowed (when s (query/enum-values registry (if assigned? s (query/compared-schema registry s op))))
                uncast (when (and s strings? (not (:inline (:format opts))) (not (takes-string? registry s assigned? (jdbc/string-sends opts))))
                         (uncast-type registry s))]
          :when (seq values)
          v values
          :let [invalid? (and allowed (not (some #(label= % v) allowed)))]
          p [(when invalid? {:kind :enum-literal :column col :value v :allowed allowed})
             ;; a value outside the enum fails however it is sent
             (when (and uncast (not invalid?)) {:kind :uncast-string :column col :value v :type uncast})]
          :when p]
      p)))

(defn- problems
  "The problems of one statement (README.md's table of :kind), scope the statement's (from
   scope) or, for a nested statement, the chain of its own and the enclosing ones."
  [registry stmt scope ps {:keys [schema] :as opts}]
  (let [scopes (syntax/chain scope)
        known (for [[k v] (::query/schemas registry) :when (shape/read-row? v)]
                (str/join "." (shape/schema-and-name k)))]
    (vec (concat (for [[_ {:keys [table unknown?]}] (first scopes) :when unknown?
                       :let [same-name (sort (filter #(= (syntax/table-name %) (syntax/table-name table)) known))
                             meant (seq (take 3 (distinct (concat same-name (error/suggestions table known 3)))))]]
                   (cond-> {:kind :unknown-table :table table} meant (assoc :did-you-mean (vec meant))))
                 (column-problems registry scopes stmt ps opts)
                 (insert-problems registry stmt schema)
                 (write-problems registry scopes stmt opts)
                 (string-problems registry ps opts)))))

(def ^:private read-clauses
  "The clauses check, arg-types and row-schema read."
  (into syntax/statement-keys (concat syntax/with-keys syntax/from-keys syntax/join-keys
                               [:join-by :where :having :group-by :order-by :limit :offset :values :columns :set
                                :on-conflict :do-update-set :returning])))

(def ^:private ignored-clauses
  "Clauses that change no table, column or type these read."
  #{:do-nothing :on-constraint :for :lock :fetch :window :partition-by :frame})

(defn- unread-clauses
  "{:kind :unread-clause :clause k} for a clause the query uses that is read nowhere here, so
   what it does goes unjudged: in the query map and every statement, and in a map of
   HoneySQL's clauses alone ({:table :t}, a clause registered with register-clause!). The rows of
   :values, :set and :do-update-set are maps of columns, not of clauses, though a subquery in
   them is read."
  [body]
  (let [clauses (set (sql/clause-order))
        rows (set (for [st (syntax/statements body)
                        r (concat (when (vector? (:values st)) (:values st)) [(:set st) (:do-update-set st)])
                        :when (map? r)]
                    r))
        maps (tree-seq syntax/branch? seq body)]
    (distinct
     (for [m maps
           ;; the query itself is clauses, however unknown ({:selct [:id]})
           :when (and (map? m) (seq m) (not (rows m)) (or (identical? m body) (syntax/statement? m) (every? clauses (keys m))))
           k (keys m)
           :when (and (keyword? k) (not (read-clauses k)) (not (ignored-clauses k)))
           :let [meant (when-not (contains? clauses k)
                         (error/suggest k (concat read-clauses ignored-clauses clauses)))]]
       (cond-> {:kind :unread-clause :clause k} meant (assoc :did-you-mean [meant]))))))

(def ^:private option-keys
  "The options of analyze, values-schema and set-schema; query-schema's add :result."
  [:schema :jdbc :format])

(defn- prepared
  "[registry body opts] as a public function reads them: the registry as reading reads it, body
   as query! reads it, opts checked (jdbc/options!, of ks) and with-defaults'. Without a body,
   opts without with-defaults: a table named with its schema needs none. A public function
   another one calls with what it read (a registry reading made) reads them as they are."
  ([registry opts ks]
   (if (::query/schemas registry)
     [registry opts]
     [(query/reading registry) (jdbc/options! opts ks)]))
  ([registry body opts ks]
   (if (::query/schemas registry)
     [registry body opts]
     (let [[r opts] (prepared registry opts ks)]
       [r (syntax/query! body) (query/with-defaults r opts)]))))

(defn- return-key-problems
  "pgjdbc quotes the names of :return-keys as written (its quoteReturningIdentifiers, true by
   default): one that is a column of the target only folded (:group-id for group_id) is none."
  [registry stmt scopes {{:keys [return-keys]} :jdbc :keys [schema] :as opts}]
  (when (and (sequential? return-keys) (map? stmt) (not= stmt (query/sent stmt opts)))
    (let [[_ alias] (some-> (first (syntax/write-targets stmt)) (syntax/table-ref schema))
          {:keys [table opaque?]} (get (first (syntax/chain scopes)) alias)
          cs (when (and table (not opaque?)) (query/table-columns registry table))]
      (for [k return-keys :let [n (name k)]
            :when (and cs (not (contains? cs n)) (contains? cs (syntax/folded n)))]
        {:kind :unknown-column :column (keyword n) :did-you-mean [(keyword (syntax/folded n))]}))))

(defn- check
  "[problem ...] of a query against the registry; [] when there are none. A problem in a nested
   statement (a subquery, a CTE) carries :in, where the statement sits: (get-in q (:in p)) for
   data. A clause pgmalli does not read is an :unread-clause problem."
  [registry body opts]
  (let [sent-body (or (query/sent body opts) body)
        chains (query/once registry query/statement-positions sent-body opts)
        found (mapcat (fn [[stmt scopes path ps]]
                        ;; :in, where a nested statement sits, as get-in reads it (malli's :in)
                        (cond->> (problems registry stmt scopes ps opts) (seq path) (map #(assoc % :in (vec path)))))
                      chains)]
    (vec (concat found (return-key-problems registry body (second (first chains)) opts) (unread-clauses sent-body)))))

(defn- tables
  "{:reads #{\"schema.table\" ...} :writes #{...}}: the tables and views a query reads (FROM,
   joins, USING, in every subquery and CTE) and writes (INSERT INTO, UPDATE, DELETE FROM,
   TRUNCATE), aliases and CTE names resolved."
  [registry body {:keys [schema] :as opts}]
  (let [found (for [[stmt scopes] (query/once registry query/statement-chains body opts)
                    :let [own (first scopes)
                          targets (set (keep #(some-> % (syntax/table-ref schema) second) (syntax/write-targets stmt)))]
                    [alias {:keys [table cte? excluded?]}] (when (map? own) own)
                    :when (and table (not cte?) (not excluded?))]
                [(if (targets alias) :writes :reads) table])]
    (merge {:reads #{} :writes #{}}
           (update-vals (group-by first found) #(set (map second %))))))

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

(defn- accepted-type
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
                    ;; ARRAY[...]::query/t[] casts each element to t (transformArrayExpr)
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

(defn- document-columns
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

(defn- arg-types
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

(defn analyze
  "{:args :row :rows :problems :tables :document-columns} of a HoneySQL query against the registry, without a
   database:

   :args      {arg schema} for every named parameter (:?id, [:param :id]), keyed as sql/format's
              :params takes them; one pgmalli cannot type is [:any {:pgmalli/untyped why}]
              (docs/queries.md#the-types-of-the-arguments)
   :row       the [:map ...] of one row it returns, as next.jdbc's as-unqualified-maps builds it
              (a write with no RETURNING, its update count); nil when that cannot be told
              (docs/queries.md#the-row-a-query-returns)
   :rows      {:min n :max m} of the rows it returns where the data tells, nil otherwise
   :problems  [problem ...] PostgreSQL would report, [] when none; one in a nested statement
              carries :in, where it sits (get-in q (:in p)); humanize says each as a sentence
   :tables    {:reads #{\"schema.table\" ...} :writes #{...}}, aliases and CTEs resolved
   :document-columns
              {arg #{column ...}} for every argument that is the document of a
              jsonb_populate_record, json_populate_record or their recordset: the columns of
              the row type the statement uses of the call, as keywords; nil where a use names
              none in particular (r.*, the whole row, :raw) (docs/queries.md#the-columns-a-document-fills)

   Names resolve as PostgreSQL resolves them. query-schema builds a function schema of it.
   Options: docs/queries.md#options. Throws when body is no HoneySQL data: SQL already
   formatted, or code (a symbol in it): pass the data the code builds.

   (analyze registry {:select [:email] :from [:users] :where [:= :id :?id]})
   ;; => {:args {:id [:int #:pg{:type \"bigint\"}]}, :row [:map [:email ...]], :rows {:min 0, :max 1}, :problems [], :tables {:reads #{\"public.users\"}, :writes #{}}, :document-columns {}}"
  ([registry body] (analyze registry body {}))
  ([registry body opts]
   (let [[registry body opts] (prepared registry body opts option-keys)]
     {:args (arg-types registry body opts)
      :row (query/row-schema registry body opts)
      :rows (query/row-count registry body opts)
      :problems (check registry body opts)
      :tables (tables registry body opts)
      :document-columns (document-columns registry body opts)})))

(defn query-schema
  "[:=> {:pgmalli/problems [...]} [:cat arg ...] [:sequential row]], the malli function schema of a function taking args
   and running body, of what analyze tells: the args' types (:any for a key the query lacks), the
   row (:any when it cannot be resolved), [:sequential {:max n} row] where the data tells how
   many rows (docs/queries.md#function-schemas): under {:result :one} no [:maybe] where it tells
   one.

   (query-schema registry [:db :id] {:select [:email] :from [:users] :where [:= :id :?id]})

   The problems analyze finds, and a query argument args lack (:missing-arg), are the schema's
   :pgmalli/problems property ([] for none), not an error. The return is what execute! returns on a datasource or connection, under
   {:result :one} what execute-one! returns. Options: docs/queries.md#options. Throws when args are
   not keywords."
  ([registry args body] (query-schema registry args body {}))
  ([registry args body opts]
   (when-not (and (sequential? args) (every? keyword? args))
     (error/fail! :pgmalli.error/invalid-options
                  (str "query-schema takes the function's args first, keywords ([:db :id]), not " (pr-str args))
                  {:given args}))
   (let [[registry body {:keys [result] :as opts}] (prepared registry body opts (conj option-keys :result))
         {types :args :keys [row rows problems]} (analyze registry body opts)
         problems (into problems
                        (for [k (sort-by str (syntax/query-args body)) :when (and (contains? types k) (not (some #{k} args)))] {:kind :missing-arg :arg k}))]
     (into [:=> {:pgmalli/problems problems}]
           [(into [:cat] (map #(get types % :any)) args)
            (let [{:keys [min max]} rows]
              (cond (nil? row) :any
                    (= :one result) (if (or (= query/update-count row) (= 1 min)) row [:maybe row])
                    max [:sequential (cond-> {:max max} (pos? min) (assoc :min min)) row]
                    :else [:sequential row]))]))))

(defn- written-key
  "The key of column (a keyword, or a string where the registry keys it so) in a :values or
   :set map: the key a row is read with (jdbc/row-key, :group_id), so a row read is written
   back, where HoneySQL writes it as column under sql/format's opts (the format; unquoted,
   PostgreSQL folds a name to lower case, and HoneySQL writes - as _); else the column's name as
   a string, which HoneySQL quotes."
  [column {:keys [format]}]
  (let [n (name column)
        writes? #(= n (first (syntax/formatted-name % format false)))
        k (jdbc/row-key n)]
    (cond (writes? k) k (or (string? column) (writes? n)) n :else k)))

(defn- one-value
  "t without what HoneySQL writes in :values or :set as something else than one value: a keyword
   as a column (:default is added after), a Clojure vector or sequence as a row of parameters,
   (?, ?), which no column takes; a set, or a Java array, is one value."
  [t]
  (case (shape/head t)
    :maybe [:maybe (one-value (last t))]
    :and (shape/update-base t one-value)
    :or (shape/either (remove #(or (= :keyword (shape/kind %)) (#{:vector :sequential} (shape/head %)))
                              (map one-value (shape/alternatives t)))
                      t)
    :any [:and t [:not [:or :keyword [:sequential :any]]]]
    t))

(defn- generated-values
  "t without the values of a class alone (a PGobject), which malli does not generate; nil where
   nothing is left."
  [t]
  (cond (and (= :fn (shape/head t)) (:pg/class (shape/column-props t))) nil
        (= :maybe (shape/head t)) (if-let [x (generated-values (last t))] [:maybe x] :nil)
        (= :or (shape/head t)) (some-> (seq (keep generated-values (shape/alternatives t))) (shape/either t))
        :else t))

(defn- written-row*
  "The closed map of the derived schema (\"insert\", \"update\") of table row name (:pg.public/users)
   of registry r as reading reads it: each column typed as an argument assigned to it, optional
   as the derived schema has it, keyed by written-key; one the database may fill takes :default too, and one
   it writes :default alone. update takes a non-empty map: HoneySQL writes an empty :set as SET
   alone, which PostgreSQL refuses. nil where r has no such schema."
  [r name derived opts]
  (when-let [d (get (::query/schemas r) (shape/derived-name name derived))]
    (let [s (get (::query/schemas r) name)
          written (shape/column-entries d)
          default (into [:enum] (sort-by vector? (syntax/default-values (:format opts))))
          ;; the columns the database may fill: optional in what an INSERT may carry
          fills (set (for [[k props] (shape/column-entries (get (::query/schemas r) (shape/derived-name name "insert"))) :when (:optional props)] k))
          triggered? (query/filled-by-trigger? (shape/row-props s) (keyword derived))
          ;; [keys required? type generated] of each column, generated the values it takes that
          ;; malli generates (nil for none)
          columns (concat
                   ;; one the database may fill takes HoneySQL's :default (DEFAULT) too
                   (for [[k props c] written
                         :let [assigned #(cond-> (accepted-type r c :assign %) (or (shape/maybe? c) triggered?) query/nullable)
                               a (assigned (assoc opts ::keywords-columns true))
                               t (one-value a)
                               plain (if (fills k) [:or t default] t)]]
                     ;; [:lift v] sends v as one parameter, a keyword label too
                     [(written-key k opts) (not (or (:optional props) triggered?)) [:or plain [:tuple {:error/message "should be [:lift v], v as the column takes it"} [:= :lift] (assigned opts)]] (generated-values plain)])
                   ;; one the database writes (generated, identity ALWAYS) takes it alone
                   (for [[k] (shape/column-entries s) :when (not-any? #(= k (first %)) written)]
                     [(written-key k opts) false default default]))
          entry (fn [k required? t] (if required? [k t] [k {:optional true} t]))
          row (into [:map {:closed true}] (for [[k required? t] columns] (entry k required? t)))
          ;; a column that generates nothing is left out where it may be; a required one fails to generate
          generated (into [:map] (for [[k required? t g] columns :when (or g required?)] (entry k required? (or g t))))
          nonempty [:not [:= nil {}]]
          non-empty #(if (= "update" derived) [:and % nonempty] %)]
      (shape/merge-props (non-empty row) {:gen/schema (non-empty generated)}))))

(defn- written-row
  "written-row* of the table name names (a registry row name, or a table keyword as HoneySQL
   names it, in :schema); an error naming what was meant for another name."
  [registry name derived what opts]
  (when-not (keyword? name)
    (error/fail! :pgmalli.error/invalid-options
                 (str what " takes a table as a keyword, as HoneySQL names it (:users, :app.users) or its row schema (:pg.public/users); got " (pr-str name))
                 {:given name}))
  (let [[r opts] (prepared registry opts option-keys)
        registry (::query/schemas r)
        ;; a registry name (:pg.app/users), or a mistyped one (:pg.public.users) as it is, for schema-of to
        ;; name the one meant; a table as HoneySQL names it (:users, :app.users) qualified in :schema,
        ;; and folded where only that is the registry's, as PostgreSQL reads an unquoted :Users
        name (if (str/starts-with? (subs (str name) 1) "pg.")
               name
               (let [t (if (or (namespace name) (str/includes? (clojure.core/name name) "."))
                         (syntax/qualified name nil)
                         (syntax/qualified name (:schema (query/with-defaults r opts))))]
                 (when (< 2 (count (str/split t #"\.")))
                   (error/fail! :pgmalli.error/invalid-options
                                (str what " takes a table as :table or :schema.table; " (pr-str name) " names more")
                                {:given name}))
                 (or (some #(let [k (shape/table-key %)] (when (contains? registry k) k)) [t (syntax/folded t)])
                     (shape/table-key t))))
        _ (when-not (contains? registry name)
            (query/unknown-table! r (if (namespace name) (str/join "." (shape/schema-and-name name)) (clojure.core/name name))))
        s (shape/schema-of registry name)]
    (cond (shape/row-schema? s) nil
          (shape/read-row? s) (error/fail! :pgmalli.error/unknown-schema
                                           (str (pr-str name) " is a view's row schema, and a view has no " derived " schema; " what " takes a table's")
                                           {:given name})
          :else (shape/not-a-row! registry name what))
    (or (written-row* r name derived opts)
        (let [dn (shape/derived-name name derived)]
          (error/fail! :pgmalli.error/unknown-schema
                       (str "the registry has no " derived " schema " (pr-str dn) " for " (pr-str name) "; pgmalli-next.core/registry derives it")
                       {:given dn})))))

(defn values-schema
  "The schema of one row an INSERT's :values may carry into a table, named as HoneySQL names
   it (:users in :schema) or by its row schema (:pg.public/users), typed as analyze types an
   argument: each column as an argument assigned to it, keyed by the column's name (:group_id),
   the one key a row is read with, so a row read is written back as it is
   (docs/queries.md#a-row-written-into-a-table); those the database may fill (a default,
   identity BY DEFAULT, NULL) optional and taking HoneySQL's :default too, those it writes
   (generated, identity ALWAYS) taking :default alone. Where a BEFORE row trigger runs for the
   write, every column is optional and takes nil. Options: docs/queries.md#options.

   (values-schema registry :users)"
  ([registry name] (values-schema registry name {}))
  ([registry name opts] (written-row registry name "insert" "values-schema" opts)))

(defn set-schema
  "The schema of the map an UPDATE's :set may carry into a table: as values-schema, every column
   optional, at least one.

   (set-schema registry :users)"
  ([registry name] (set-schema registry name {}))
  ([registry name opts] (written-row registry name "update" "set-schema" opts)))

(defn- column-name [c] (if (keyword? c) (subs (str c) 1) (str c)))

(def ^:private explanations
  "The sentence for each kind of problem: what is wrong, then what to do."
  {:unknown-table (fn [{:keys [table did-you-mean]}]
                   (let [elsewhere (first (filter #(= (syntax/table-name %) (syntax/table-name table)) did-you-mean))]
                     (str "unknown table " table
                          (cond elsewhere (str "; " elsewhere " exists; pass {:schema " (pr-str (subs elsewhere 0 (str/last-index-of elsewhere "."))) "}"
                                               ", or name it :" elsewhere)
                                did-you-mean (error/did-you-mean (str/join " or " did-you-mean))
                                :else "; check its name, or :schema when it is in another schema"))))
   :unknown-column (fn [{:keys [column did-you-mean qualifier]}]
                     (str "unknown column " (column-name column)
                          (when qualifier (str "; no table or alias " qualifier " in the statement"))
                          (cond did-you-mean (error/did-you-mean (str/join " or " (map column-name did-you-mean)))
                                (not qualifier) "; no table of the statement has it")))
   :ambiguous-column (fn [{:keys [column candidates]}]
                       (str "column " (column-name column) " is in " (str/join " and " candidates) "; qualify it with the table's alias"))
   :missing-required-column (fn [{:keys [table column]}]
                              (str "INSERT into " table " lacks " (column-name column) ", which is NOT NULL with no default"))
   :values-arity (fn [{:keys [row columns values]}]
                   (str ":values row " row " has " values " values for " columns " :columns; give each row as many values as :columns"))
   :enum-literal (fn [{:keys [column value allowed]}]
                   (str (pr-str value) " is no value of " (column-name column) "; it takes " (str/join ", " (map pr-str allowed))))
   :uncast-string (fn [{:keys [column value type]}]
                    (str (pr-str value) " reaches column " (column-name column) " as a typed string, which its type " type " does not take"
                         " (HoneySQL sends a string as a parameter, which pgjdbc sends as varchar);"
                         " write [:cast " (pr-str value) " "
                         (if (types/element type) (str "[:raw " (pr-str type) "]") (str ":" type))
                         "]"))
   :not-null-violation (fn [{:keys [table column]}] (str "NULL written into " table "." (column-name column) ", which is NOT NULL"))
   :read-only-column (fn [{:keys [table column]}]
                       (str table "." (column-name column) " is written by the database (generated, or identity ALWAYS);"
                            " leave it out, or write :default"))
   :unread-clause (fn [{:keys [clause did-you-mean]}]
                    (if did-you-mean
                      (str "clause " clause " is no clause HoneySQL or pgmalli knows" (error/did-you-mean (first did-you-mean)))
                      (str "clause " clause " is not read by pgmalli, so what it does is not checked; check the clause name, or check that part by hand")))
   :missing-arg (fn [{:keys [arg]}] (str "the query takes argument " arg ", which the function's args lack; a typo in args?"))})

(defn humanize
  "A sentence for each problem of analyze's :problems (or of query-schema's :pgmalli/problems):
   what is wrong and what to do.

   (let [{:keys [problems]} (analyze registry q)] (is (= [] problems) (humanize problems)))"
  [problems]
  (mapv (fn [{:keys [kind in var] :as p}]
          (str (when var (str (symbol var) ": "))
               (if-let [f (explanations kind)] (f p) (pr-str p)) (when in (str " (in the statement at " in ")"))))
        problems))

(defn schema-problems
  "[problem ...] of the :malli/schema of every var in the namespaces (all loaded ones by
   default), each with its :var: query-schema's :pgmalli/problems, which a schema carries
   rather than throws, so a namespace still loads. One test asserts there are none:

   (is (= [] (schema-problems)))"
  ([] (schema-problems {}))
  ([{:keys [ns] :or {ns (all-ns)} :as opts}]
   (when-not (and (map? opts) (every? #{:ns} (keys opts)))
     (error/fail! :pgmalli.error/invalid-options
                  (str "schema-problems takes {:ns n}, n a namespace's symbol, a namespace or a collection of them; given " (pr-str opts))
                  {:given opts}))
   (vec (for [n (if (coll? ns) ns [ns])
              [_ v] (sort-by key (ns-interns (or (cond (symbol? n) (find-ns n) (instance? clojure.lang.Namespace n) n)
                                                 (error/fail! :pgmalli.error/invalid-options
                                                              (str "schema-problems reads loaded namespaces, named by symbols; "
                                                                   (pr-str n) " is none" (when (symbol? n) " (require it first)"))
                                                              {:given n}))))
              :let [s (:malli/schema (meta v))
                    props (cond (satisfies? malli.core/Schema s) (malli.core/properties s)
                                (and (vector? s) (map? (second s))) (second s))]
              p (:pgmalli/problems props)]
          (assoc p :var v)))))
