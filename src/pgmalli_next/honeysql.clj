(ns pgmalli-next.honeysql
  "HoneySQL query data read against a registry, without a database (analyze): the problems
   PostgreSQL would report, the types of the query's arguments and of the rows it returns, and
   the tables it reads and writes; query-schema makes a function schema of them. values-schema
   and set-schema type a row written into a table.

   Names resolve as PostgreSQL resolves them (docs/queries.md#what-a-name-refers-to). Types are
   what the one reading reads and the one sending sends (docs/reading-rows.md). The functions
   take the options of docs/queries.md#options; a key or value not among them is an error."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            malli.core
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.args :as args]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

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
     {:args (args/arg-types registry body opts)
      :row (query/row-schema registry body opts)
      :rows (query/row-count registry body opts)
      :problems (check registry body opts)
      :tables (tables registry body opts)
      :document-columns (args/document-columns registry body opts)})))

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
                         :let [assigned #(cond-> (args/accepted-type r c :assign %) (or (shape/maybe? c) triggered?) query/nullable)
                               a (assigned (assoc opts ::args/keywords-columns true))
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
