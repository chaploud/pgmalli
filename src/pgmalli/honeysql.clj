(ns pgmalli.honeysql
  "HoneySQL query data checked against a registry, without a database: the tables must
   exist, the columns a query selects, returns, inserts, sets or compares must exist, an
   INSERT must carry the required columns, an enum literal must be one of the enum's values;
   and from the same data, the types of the query's parameters and of the rows it returns,
   as malli schemas.

   Scope: tables come from :from, :using, :cross-join, the joins (:join-by is not read),
   :insert-into, :update and :delete-from, under their aliases. CTEs (:with), subqueries,
   table functions, a symbol where a table goes and a pg_catalog table the registry lacks are
   opaque tables: their columns exist but have no type. Every table has the system columns
   (ctid, xmin, ...). A column is :col (unique in the scope), :alias/col or :alias.col; a
   column a subquery cannot resolve is looked for in the enclosing statements, as PostgreSQL
   does. An unqualified table is in :schema (default \"public\"). What code in the query
   builds (a list) is not in the data and is not judged.

   The functions are check, arg-types, row-schema and query-schema. Types are what next.jdbc and PostgreSQL's JDBC driver
   return and take, under pgmalli.core/as-read's options, plus :schema, :write, :compare and
   :result (see arg-types and query-schema); a key or value not among them is an error."
  (:require [clojure.string :as str]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.portable :as portable]
            [pgmalli.impl.shape :as shape]))

;;; tables and columns of the registry

(defn- column-entries
  "[[column-name schema] ...] of a table (or view) in the registry, in its order; nil when it
   is not there."
  [registry table]
  (some->> (get (::schemas registry) (shape/table-key table)) shape/column-entries (map (fn [[k _ s]] [(name k) s]))))

(def ^:private system-columns
  "The columns every table has besides its own."
  {"ctid" [:some {:pg/type "tid"}] "xmin" [:some {:pg/type "xid"}] "xmax" [:some {:pg/type "xid"}]
   "cmin" [:some {:pg/type "cid"}] "cmax" [:some {:pg/type "cid"}] "tableoid" [:int {:pg/type "oid"}]})

(defn- reading
  "The registry as this namespace reads it: its schemas taken once (a composite registry merges
   them on every lookup) and the columns of a table memoized, since a column is looked for in
   every table in scope. The public functions take a registry and make one of it; one passed on
   again is left as it is."
  [registry]
  (if (::schemas registry)
    registry
    (let [r {::schemas (shape/schemas-of registry)}]
      (assoc r ::table-columns (memoize #(some->> (column-entries r %) (into system-columns)))))))

(defn- table-columns [registry table] ((::table-columns registry) table))

;;; statements, CTEs and scope

(def ^:private set-operation-keys [:union :union-all :intersect :except :except-all])
(def ^:private with-keys [:with :with-recursive])
(def ^:private statement-keys
  (into #{:select :select-distinct :select-distinct-on :select-top :select-distinct-top
          :insert-into :update :delete-from} set-operation-keys))
(def ^:private default-schema "public")
(def ^:private from-keys [:from :using :cross-join])
(def ^:private join-keys
  "In the order sql/format writes them, which decides the tables a RIGHT JOIN may leave NULL."
  [:join :left-join :right-join :inner-join :outer-join :full-join])

(defn- from-items
  "The table items under the keys ks (:from, :using, :cross-join): one alone (a table, a symbol,
   a subquery) or a vector of them; anything else (code) is none."
  [stmt ks]
  (for [k ks :let [f (get stmt k)] :when f
        t (cond (or (keyword? f) (symbol? f) (map? f)) [f] (vector? f) f)]
    t))

(defn- join-items
  "[join-key item] for the tables the joins bring in, in join-keys' order."
  [stmt]
  (for [k join-keys :let [j (get stmt k)] :when (vector? j), t (map first (partition 2 j))] [k t]))

(defn- code-sources?
  "Whether code (a list) brings in some of a statement's tables, whose columns the data then
   does not show."
  [stmt]
  (some seq? (concat (keep stmt (concat from-keys join-keys [:update :delete-from :insert-into]))
                     (from-items stmt from-keys)
                     (map second (join-items stmt)))))

(defn- branch?
  "Whether a walk of query data goes into x: a collection, but not a value HoneySQL passes as it
   is ([:lift x], [:inline x]), whose maps are data, not statements or clauses."
  [x]
  (and (coll? x) (not (and (vector? x) (#{:lift :inline} (first x))))))

(defn- statement?
  "A query map; one holding only a set operation is one too, its members statements of their own."
  [x]
  (and (map? x) (some statement-keys (keys x))))

(defn- own-nodes
  "The nodes of one statement's part, not descending into the statements nested in it: those
   are statements of their own, judged in their own scope."
  [part]
  (tree-seq #(and (branch? %) (not (statement? %))) seq part))

(defn- statements
  "The statements in a query (a map, or Clojure data holding maps); a subquery is one too."
  [body]
  (into [] (filter statement?) (tree-seq branch? seq body)))

(defn- cte-name [entry]
  (when (vector? entry)
    (let [n (if (vector? (first entry)) (ffirst entry) (first entry))]
      (when (keyword? n) (name n)))))

(defn- cte-pair? [x] (and (vector? x) (<= 2 (count x) 3) (statement? (second x))))

(defn- cte-entries
  "The CTE entries a node holds: the items of :with and :with-recursive, and a [name statement]
   pair on its own (a CTE built up in code before it is put under :with)."
  [x]
  (cond
    (map? x) (mapcat #(let [w (get x %)] (when (vector? w) w)) with-keys)
    (cte-pair? x) [x]))

(defn- insert-parts
  "{:table :columns :select} of :insert-into in its HoneySQL shapes: :t, [:t [:a :b]],
   [target {:select ...}], any of them behind an option map ({:overriding-value :system}); the
   statement's :columns when the target lists none; the table a symbol when it is chosen at
   run time. nil when the target is neither (rows built elsewhere)."
  [stmt]
  (let [i (:insert-into stmt)
        i (if (and (vector? i) (map? (first i))) (vec (rest i)) i)
        i (if (and (vector? i) (= 1 (count i))) (first i) i)
        [target select] (if (and (vector? i) (map? (second i))) [(first i) (second i)] [i nil])
        [table more] (if (and (vector? target) (or (keyword? (first target)) (symbol? (first target)) (vector? (first target))))
                       [(first target) (second target)]
                       [target nil])
        ;; [:t :alias] is INSERT INTO t AS alias; [:t [:a :b]] lists the columns; [[:t :alias] [:a :b]] both
        [table alias] (if (vector? table) [(first table) (second table)] [table nil])
        [alias listed] (if (vector? more) [alias more] [more nil])]
    (when (or (keyword? table) (symbol? table))
      {:table table :alias alias :columns (or listed (let [c (:columns stmt)] (when (vector? c) c))) :select select})))

(defn- cte-names*
  "CTE names in a query: the :with entries of its statements when with? holds, and the
   [name statement] pairs sitting outside any :with (CTEs built up in code). The value of
   :insert-into, [target {:select ...}], has that shape too and is not one."
  [x with?]
  (cond (statement? x) (concat (when with? (keep cte-name (cte-entries x)))
                               (mapcat #(cte-names* % with?) (vals (apply dissoc x :insert-into with-keys)))
                               (cte-names* (:select (insert-parts x)) with?))
        (and (cte-pair? x) (cte-name x)) (cons (cte-name x) (cte-names* (second x) with?))
        (branch? x) (mapcat #(cte-names* % with?) (seq x))))

(defn- cte-names
  "Names of the CTEs a query defines, at any depth."
  [body]
  (set (cte-names* body true)))

(defn- free-cte-names
  "Names of the CTEs built up in code (pairs outside any :with), which every statement sees."
  [body]
  (set (cte-names* body false)))

(defn- qualified
  "\"schema.table\" of a table keyword: :schema/table, :schema.table, or :table in the default schema."
  [k schema]
  (cond (namespace k) (str (namespace k) "." (name k))
        (str/includes? (name k) ".") (name k)
        :else (str schema "." (name k))))

(defn- written
  "The table name as written when it names no schema, the form a CTE may shadow."
  [k]
  (when-not (or (namespace k) (str/includes? (name k) ".")) (name k)))

(defn- table-ref
  "[table alias written columns] of a :from / join / target item: the table's qualified name,
   its alias, the name as written (nil when qualified by a schema), and the columns an alias
   lists ([:v {:columns [:id :n]}] over a VALUES); an opaque item (subquery, function, a symbol
   naming a table chosen at run time) has no table."
  [x schema]
  (cond
    (keyword? x) [(qualified x schema) (name x) (written x)]
    (symbol? x) [nil (str x) nil]
    ;; a subquery with no alias (PostgreSQL 16): nothing names it, so no query reaches it by name
    (map? x) [nil (str "(subquery " (hash x) ")") nil]
    (vector? x)
    (let [[a b] x
          ;; [:alias {:columns [...]}]; a column definition list ([:raw "a int"]) names no column read here
          [b listed] (if (and (vector? b) (keyword? (first b)) (map? (second b)))
                       [(first b) (let [cs (:columns (second b))] (when (every? #(or (keyword? %) (string? %)) cs) (mapv name cs)))]
                       [b nil])]
      (cond
        ;; :t / [:t :alias] / INSERT INTO t (columns...)
        (and (keyword? a) (or (nil? b) (keyword? b) (vector? b))) [(qualified a schema) (name (if (keyword? b) b a)) (written a) listed]
        (keyword? a) [nil (name a) nil listed]
        (keyword? b) [nil (name b) nil listed]
        ;; [[:f args...]]: a table function, named after the function
        (and (nil? b) (vector? a) (keyword? (first a))) [nil (name (first a)) nil]))))

(defn- insert-target
  "The target of :insert-into as a :from item: the table, under its alias when it has one."
  [stmt]
  (let [{:keys [table alias]} (insert-parts stmt)]
    (if (and table alias) [table alias] table)))

(defn- catalog-table
  "The table an unqualified name means: pg_catalog, implicitly first on the search path, holds
   the pg_ relations; one the registry lacks is \"pg_catalog.<name>\" all the same, opaque."
  [registry table written]
  (if-let [catalog (when (and written (str/starts-with? written "pg_")) (str "pg_catalog." written))]
    (cond (table-columns registry catalog) catalog
          (table-columns registry table) table
          :else catalog)
    table))

(defn- catalog? [table] (str/starts-with? table "pg_catalog."))

(defn- outer-aliases
  "The aliases an outer join may leave NULL: a LEFT JOIN's table, every table before a RIGHT
   JOIN's, both sides of a FULL (and :outer-join) one."
  [stmt schema]
  (let [alias #(second (table-ref % schema))
        ;; the joins attach to the last FROM item: a comma binds looser than JOIN
        from (take-last 1 (map alias (from-items stmt [:from :using])))]
    (:nullable
     (reduce (fn [{:keys [before] :as acc} [k t]]
               (let [a (alias t)]
                 (-> (case k
                       :left-join (update acc :nullable conj a)
                       :right-join (update acc :nullable into before)
                       (:outer-join :full-join) (update acc :nullable into (conj before a))
                       acc)
                     (update :before conj a))))
             {:before (vec from) :nullable #{}}
             (join-items stmt)))))

(defn- scope
  "{alias {:table \"schema.table\" :opaque? bool :cte? bool :nullable? bool}} of one statement;
   ctes are the query's CTE names, which shadow a table only when the reference is written
   without a schema. :nullable? marks a table an outer join may leave NULL."
  [registry stmt ctes {:keys [schema] :or {schema default-schema}}]
  (let [registry (reading registry)
        refs (concat (from-items stmt from-keys)
                     (map second (join-items stmt))
                     [(insert-target stmt) (:update stmt) (:delete-from stmt)])
        nullable (outer-aliases stmt schema)
        sc (into {}
                 (for [[table alias written listed] (keep #(table-ref % schema) refs)
                       :let [cte? (boolean (and written (ctes written)))
                             table (when table (catalog-table registry table written))]]
                   [alias (cond-> {:table table :cte? cte?
                                   :opaque? (boolean (or (nil? table) cte? (nil? (table-columns registry table))))
                                   ;; named, but the registry lacks it (pg_catalog's aside): no row can be told of it
                                   :unknown? (boolean (and table (not cte?) (not (catalog? table)) (nil? (table-columns registry table))))}
                            ;; the columns an alias lists are the ones it has, typeless
                            listed (assoc :columns listed)
                            (nullable alias) (assoc :nullable? true))]))]
    ;; ON CONFLICT: EXCLUDED is the row proposed for insertion, with the target's columns
    (if-let [[_ alias] (when (contains? stmt :on-conflict) (some-> (insert-target stmt) (table-ref schema)))]
      (let [target (assoc (get sc alias) :excluded? true)] (assoc sc "EXCLUDED" target "excluded" target))
      sc)))

(defn- statement-chains
  "[[statement scopes] ...] for the statements of a query, scopes the statement's own scope
   followed by those of the statements enclosing it. A CTE is visible to the statement whose
   :with defines it and to the statements inside it, not outside. A statement found inside a
   vector of a code form, not under a statement (a subquery a helper adds to a query built
   elsewhere, or a CTE body bound apart from its :with), has an enclosing statement this data
   does not show: its chain ends in :open, a column it cannot resolve is taken to be the
   enclosing statement's, and every CTE the query defines anywhere is visible to it."
  [registry body opts]
  (letfn [(walk [x outer ctes in-vector? under?]
            (cond (statement? x) (let [open? (and (empty? outer) in-vector? (not under?))
                                       ctes (into (if open? (cte-names body) ctes) (keep cte-name) (cte-entries x))
                                       outer (if open? (list :open) outer)
                                       ;; a column of a table code brings in is the enclosing data's, untyped
                                       chain (cons (scope registry x ctes opts) (cond->> outer (code-sources? x) (cons :open)))]
                                   (cons [x chain]
                                         (concat
                                          ;; INSERT ... SELECT: the SELECT does not see the table inserted into
                                          (walk (:insert-into x) outer ctes false true)
                                          (mapcat #(walk % chain ctes false true) (vals (dissoc x :insert-into))))))
                  (branch? x) (mapcat #(walk % outer ctes (or in-vector? (vector? x)) under?) (seq x))))]
    (walk body () (free-cte-names body) false false)))

(defn- split-column
  "[alias column] of a column keyword: :a/b and :a.b name a table, :b does not."
  [col]
  (let [s (if (namespace col) (str (namespace col) "." (name col)) (name col))]
    (if (str/includes? s ".") (str/split s #"\." 2) [nil s])))

(defn- has-column?
  "Whether a scope entry may hold a column: one it lists, one of its table's, or anything for
   an opaque table."
  [registry {:keys [table opaque? columns]} column]
  (cond columns (boolean (some #{column} columns))
        opaque? true
        :else (contains? (table-columns registry table) column)))

(defn- own-hits
  "[[alias table opaque? nullable?] ...] of the tables of one scope a column may belong to."
  [registry scope col]
  (let [[alias column] (split-column col)]
    (if alias
      (when-let [{:keys [table opaque? nullable?] :as entry} (get scope alias)]
        (when (has-column? registry entry column) [[alias table opaque? nullable?]]))
      ;; an unqualified column is never EXCLUDED's: that row is only reached through its name
      (distinct (for [[a {:keys [table opaque? excluded? nullable?] :as entry}] scope
                      :when (and (not excluded?) (has-column? registry entry column))]
                  [a table opaque? nullable?])))))

(defn- chain [scope] (if (map? scope) [scope] scope))

(defn- column-hits
  "The hits of the innermost scope (a map, or the chain of a statement and its enclosing
   ones) that has any."
  [registry scopes col]
  (let [[sc & outer] (chain scopes)
        hits (own-hits registry sc col)
        [alias] (split-column col)]
    (cond (or (seq hits) (empty? outer)) hits
          ;; the enclosing statement is not in the data: a column not of this scope's tables is
          ;; one of its, untyped
          (= :open (first outer)) (if (and alias (contains? sc alias)) hits [[alias nil true]])
          :else (column-hits registry outer col))))

(defn- resolve-column
  "{:table :column :schema :nullable? :opaque?} for a column keyword in a scope (or the chain of
   scopes of a nested statement), :table the alias for an opaque table and :schema nil there,
   :nullable? when an outer join may leave the table NULL; nil when the column resolves to no
   table or to several known ones. A column an opaque table may hold besides one known table is
   the opaque one's, untyped: which of them PostgreSQL takes is not in the data."
  [registry scope col]
  (let [registry (reading registry)
        hits (column-hits registry scope col)
        column (second (split-column col))
        [opaque & _] (filter #(nth % 2) hits)]
    (cond (= 1 (count hits))
          (let [[alias table opaque? nullable?] (first hits)]
            {:table (or table alias) :column column :schema (when-not opaque? (get (table-columns registry table) column))
             :nullable? (boolean nullable?) :opaque? (boolean opaque?)})
          (and opaque (>= 1 (count (remove #(nth % 2) hits))))
          (let [[alias table] opaque] {:table (or table alias) :column column :schema nil :nullable? true :opaque? true}))))

;;; what a statement selects, compares and assigns

(def ^:private sql-values
  "Keywords HoneySQL writes as SQL words, not columns."
  #{:current_timestamp :current_date :current_time :localtime :localtimestamp :current_user :session_user :current_role
    :current_catalog :current_schema :user :true :false :null :default :*})

(defn- param-key
  "The :params key of a HoneySQL named parameter, as sql/format looks it up: :?id and ?id are
   :id (:?a/b is :a/b), [:param k] is k. nil for anything else."
  [x]
  (if (and (vector? x) (= 2 (count x)) (= :param (first x)))
    (second x)
    (when (or (keyword? x) (symbol? x))
      (let [c (if (keyword? x) (subs (str x) 1) (str x))]
        (when (str/starts-with? c "?") (keyword (subs c 1)))))))

(defn- shorthand-fn
  "The function a HoneySQL :%f.args keyword calls (:%max.id is MAX(id)); nil for any other."
  [x]
  (when (keyword? x)
    (let [c (subs (str x) 1)]
      (when (str/starts-with? c "%") (first (str/split (subs c 1) #"\."))))))

(defn- column-keyword? [x] (and (keyword? x) (not (sql-values x)) (not (param-key x)) (not (shorthand-fn x))))

(defn- selected-items
  "The items of :select, :select-distinct, :select-distinct-on (after its DISTINCT ON columns)
   or :returning."
  [stmt]
  (let [s (or (:select stmt) (:select-distinct stmt) (:returning stmt))
        after-first (or (:select-distinct-on stmt) (:select-top stmt) (:select-distinct-top stmt))]
    (cond (vector? s) s
          (vector? after-first) (rest after-first))))

(defn- select-parts
  "[column alias] of a select item: a column, a column under an alias, or an aliased
   expression, which has no column."
  [item]
  (cond
    (keyword? item) (when (or (= :* item) (column-keyword? item)) [item nil])
    (not (vector? item)) nil
    (and (keyword? (first item)) (not (column-keyword? (first item))) (not= :* (first item))) (when (keyword? (second item)) [nil (second item)])
    (and (keyword? (first item)) (keyword? (second item))) [(first item) (second item)]
    (keyword? (first item)) [(first item) nil]
    (keyword? (second item)) [nil (second item)]))

(def ^:private in-ops #{:in :not-in})
(def ^:private like-ops #{:like :ilike :not-like :not-ilike})
(def ^:private comparison-ops
  (into #{:= :<> :not= :!= :< :> :<= :>= :is-distinct-from :is-not-distinct-from} (concat in-ops like-ops)))

(defn- comparisons
  "[op column other] for each column side of the comparisons in a statement's conditions: a
   column on either side, the other side whatever it is (a value, a symbol, a column)."
  [stmt]
  (for [x (own-nodes (cond-> (select-keys stmt (into [:where :having :on-conflict] join-keys))
                       ;; the condition of ON CONFLICT DO UPDATE SET {:fields ... :where ...}
                       (map? (:do-update-set stmt)) (assoc :do-update-where (:where (:do-update-set stmt)))))
        :when (and (vector? x) (or (and (= 3 (count x)) (comparison-ops (first x)))
                                   (and (= 4 (count x)) (#{:between :not-between} (first x)))))
        :let [[op a b c] x]
        [col other] (if (= 4 (count x))
                      (when (column-keyword? a) [[a b] [a c]])
                      (cond-> [] (column-keyword? a) (conj [a b]) (column-keyword? b) (conj [b a])))]
    [op col other]))

(defn- assignments
  "[column value] pairs a statement assigns: :set, and the maps of :values (which may be a
   symbol, rows passed in, saying nothing about the columns)."
  [stmt]
  (let [values (:values stmt)
        columns (:columns (insert-parts stmt))
        do-update (:do-update-set stmt)]
    (filter (comp column-keyword? first)
            (concat (when (map? (:set stmt)) (:set stmt))
                    ;; ON CONFLICT DO UPDATE SET: a map of assignments, the columns taken from EXCLUDED, or
                    ;; {:fields [...] :where ...} (the columns taken from EXCLUDED, under a condition)
                    (cond (and (map? do-update) (contains? do-update :fields)) (for [c (:fields do-update) :when (keyword? c)] [c nil])
                          (map? do-update) do-update
                          (vector? do-update) (for [c do-update :when (keyword? c)] [c nil]))
                    (when (vector? values)
                      (concat (mapcat identity (filter map? values))
                              ;; positional rows under the listed columns
                              (when columns (mapcat #(map vector columns %) (filter vector? values)))))))))

(defn- target-scope
  "The scope an assigned column is resolved in: the table of :update / :insert-into alone."
  [scopes stmt schema]
  (let [[_ alias] (some-> (or (:update stmt) (insert-target stmt)) (table-ref schema))]
    (select-keys (first (chain scopes)) [alias])))

;;; the name PostgreSQL gives an output column

(defn- honeysql
  "A var of the HoneySQL on the classpath, deref'd; nil when it is not there, or too old to
   have it (registered-op? came in 2.4.1066)."
  [sym]
  (try (some-> (requiring-resolve sym) deref) (catch Exception _ nil)))

(def ^:private operators
  "HoneySQL's infix operators and the special syntax that is an operator in SQL: PostgreSQL
   names their column ?column?. The operators of the HoneySQL loaded (register-op! included)
   are read from it as well; this is what is known without it."
  (into #{} (map keyword)
        ["and" "or" "xor" "<>" "<=" ">=" "||" "<->" "like" "not-like" "regexp" "regex" "~" "&&" "ilike"
         "not-ilike" "similar-to" "not-similar-to" "is" "is-not" "not=" "!=" "is-distinct-from"
         "is-not-distinct-from" "+" "-" "*" "%" "|" "&" "^" "=" "<" ">" "/" "in" "not-in" "not"
         "between" "not-between" "inline" "lift" "param" "with-ordinality"]))

(def ^:private special-syntax
  "HoneySQL's special syntax that is not a function call; a column it makes is not named here."
  #{:entity :alias :at :at-time-zone :call :composite :distinct :escape :filter :get-in
    :ignore-nulls :interval :join :lateral :nest :object :record :order-by :over :raw
    :respect-nulls :within-group :xtql :default :foreign-key :references :unique
    :primary-key :index :constraint})

(defn- operator?
  "Whether HoneySQL writes [h ...] as an operator: a name not an identifier (:->, :@>), one of
   operators, or one the loaded HoneySQL has registered."
  [h]
  (boolean (or (operators h) (not (re-find #"^[A-Za-z_]" (name h)))
               (some-> (honeysql 'honey.sql/registered-op?) (#(% h))))))

(defn- cast-of-column? [x]
  (and (vector? x) (= :cast (first x))
       (or (column-keyword? (second x)) (cast-of-column? (second x)))))

(defn- function-name
  "A function's column name: as HoneySQL writes it (- is _), unqualified, as PostgreSQL folds it."
  [s]
  (-> s (str/split #"\.") last (str/replace "-" "_") str/lower-case))

(defn- expression-name
  "The column name PostgreSQL gives a select expression with no alias: a function's name, case,
   array, a cast column's or function's name, ?column? for a value or an operator (an infix one
   of HoneySQL's, or any whose name is not an identifier: :->, :@>, an op registered with
   register-op!). nil when this cannot tell (a cast value or operation is named after its type,
   a subquery after its column, :raw anything)."
  [x]
  (cond (or (number? x) (string? x) (boolean? x) (nil? x) (param-key x) (#{:null :true :false} x)) "?column?"
        (shorthand-fn x) (function-name (shorthand-fn x))
        (keyword? x) (when (sql-values x) (name x))
        (not (and (vector? x) (keyword? (first x)))) nil
        :else (let [[h a] x]
                (cond (= :. h) (some-> (last x) (#(when (keyword? %) (function-name (name %)))))
                      (operator? h) "?column?"
                      (#{:case :case-expr} h) "case"
                      (= :array h) "array"
                      (= :cast h) (cond (column-keyword? a) (second (split-column a))
                                        (vector? a) (let [n (expression-name a)] (when-not (#{"?column?" "case"} n) n)))
                      (special-syntax h) nil
                      :else (function-name (name h))))))

(defn- item-expression
  "The expression of a select item with no alias: [x] is x."
  [item]
  (if (and (vector? item) (= 1 (count item))) (first item) item))

(defn- item-name
  "The name of the output column a select item makes: its alias, its column's name, or the name
   PostgreSQL gives the expression; nil when that cannot be told."
  [item]
  (let [[col alias] (select-parts item)]
    (cond alias (name alias)
          col (second (split-column col))
          :else (expression-name (item-expression item)))))

;;; problems

(defn- enum-values [registry schema]
  (let [s (shape/non-null schema)
        s (if (and (vector? s) (= :ref (first s))) (get (::schemas registry) (last s)) s)]
    (when (and (vector? s) (= :enum (first s))) (set (remove map? (rest s))))))

(defn- star? [col] (= "*" (second (split-column col))))

(defn- vectorish
  "x when it is a vector: a list there is code, and what it builds is not in the data."
  [x]
  (when (vector? x) x))

(defn- output-column?
  "Whether a bare :col of :order-by or :group-by may mean an output column of stmt."
  [stmt col]
  (boolean (and (not (first (split-column col)))
                (some #(= (name col) (item-name %)) (selected-items stmt)))))

(defn- output-statement
  "The statement whose select names a statement's output columns: itself, or the first member
   of a set operation (UNION ...); nil when that member is built by code."
  [stmt]
  (if-let [members (some stmt set-operation-keys)]
    (when (and (vector? members) (map? (first members))) (output-statement (first members)))
    stmt))

(defn- ordering-columns
  "The input columns :order-by and :group-by name as they are (:col, or [:col :asc]);
   expressions there are not read. As PostgreSQL reads them, a bare name in :order-by is an
   output column first (of a set operation, its first member's), one in :group-by an input
   column first."
  [registry sc stmt]
  (let [out (output-statement stmt)]
    (concat (for [c (vectorish (:group-by stmt))
                  :when (and (column-keyword? c) (not (and (output-column? stmt c) (empty? (column-hits registry sc c)))))]
              c)
            (when out
              (for [o (vectorish (:order-by stmt)) :let [c (if (vector? o) (first o) o)]
                    :when (and (column-keyword? c) (not (output-column? out c)))]
                c)))))

(defn- column-problems [registry sc stmt schema]
  (let [target (target-scope sc stmt schema)
        ;; a target code names is not in the data: its columns are not judged
        assigned (when (seq target) (concat (filter column-keyword? (:columns (insert-parts stmt))) (map first (assignments stmt))))]
    (for [[col scope] (distinct (concat (for [c (keep (comp first select-parts) (selected-items stmt))] [c sc])
                                        (for [c (ordering-columns registry sc stmt)] [c sc])
                                        (for [[_ c] (comparisons stmt)] [c sc])
                                        (for [c assigned] [c target])))
          :when (and (not (star? col)) (nil? (resolve-column registry scope col)))
          :let [hits (column-hits registry scope col)]]
      (if (< 1 (count hits))
        {:kind :ambiguous-column :column col :candidates (mapv (fn [[alias table]] (or table alias)) hits)}
        {:kind :unknown-column :column col}))))

(defn- insert-problems [registry stmt schema]
  (let [{:keys [table columns]} (insert-parts stmt)
        [table] (some-> table (table-ref schema))]
    (when table
      (let [values (:values stmt)
            rows (when (vector? values) values)
            ;; HoneySQL inserts the union of the columns of all value maps, NULL where a row lacks one
            given (or columns (when (map? (first rows)) (distinct (mapcat keys (filter map? rows)))))
            ;; a column built by code may be any of them
            given (when (every? keyword? given) given)
            given-names (set (map name given))
            required (set (for [[k p] (some-> (get (::schemas registry) (shape/derived-name (shape/table-key table) "insert")) shape/column-entries)
                                :when (not (:optional p))]
                            (name k)))]
        (concat
         (when columns
           (for [[i row] (map-indexed vector (filter vector? rows)) :when (not= (count row) (count columns))]
             {:kind :values-arity :table table :row i :columns (count columns) :values (count row)}))
         (when (seq given)
           (for [c required :when (not (given-names c))]
             {:kind :missing-required-column :table table :column c})))))))

(defn- enum-problems [registry sc stmt schema]
  (for [[col value scope] (concat (for [[op col value] (comparisons stmt) :when (#{:= :<> :not= :!= :in :not-in} op)] [col value sc])
                                  (let [target (target-scope sc stmt schema)]
                                    (when (seq target) (for [[col value] (assignments stmt)] [col value target]))))
        :let [{s :schema} (resolve-column registry scope col)
              values (cond (string? value) [value]
                           (and (vector? value) (= :cast (first value)) (string? (second value))) [(second value)]
                           (and (vector? value) (every? string? value)) value)
              allowed (when s (enum-values registry s))]
        :when (and allowed (seq values))
        v values :when (not (contains? allowed v))]
    {:kind :enum-literal :column col :value v :allowed allowed}))

(defn- problems
  "The problems of one statement: unknown tables; columns selected, returned, inserted, set
   or compared that are unknown, or ambiguous (unqualified and in more than one known table in
   scope, those under :candidates), in :select, :where, :having, join conditions, :set,
   :values, ON CONFLICT DO UPDATE SET, :group-by and :order-by (bare columns there); required
   INSERT columns missing, or a row of :values not as long as :columns; enum literals outside
   the enum (compared with, or assigned to, the column). scope is the statement's (from scope)
   or, for a nested statement, the chain of its own and the enclosing ones. Empty when the
   statement agrees with the registry."
  [registry stmt scope {:keys [schema] :or {schema default-schema}}]
  (let [registry (reading registry)
        scopes (chain scope)]
    (vec (concat (for [[_ {:keys [table cte?]}] (first scopes)
                       :when (and table (not cte?) (not (catalog? table)) (nil? (table-columns registry table)))]
                   {:kind :unknown-table :table table})
                 (column-problems registry scopes stmt schema)
                 (insert-problems registry stmt schema)
                 (enum-problems registry scopes stmt schema)))))

(def ^:private read-clauses
  "The clauses check, arg-types and row-schema read."
  (into statement-keys (concat with-keys from-keys join-keys
                               [:where :having :group-by :order-by :limit :offset :values :columns :set
                                :on-conflict :do-update-set :returning])))

(def ^:private ignored-clauses
  "Clauses that change no table, column or type these read."
  #{:do-nothing :on-constraint :for :lock :fetch :window :partition-by :frame})

(defn- unread-clauses
  "{:kind :unread-clause :clause k} for a clause the query uses that is read nowhere here, so
   what it does goes unjudged: in a statement, and, when HoneySQL is loaded, in a map of
   HoneySQL's clauses alone ({:table :t}, a clause registered with register-clause!). Rows of
   :values and :set are not clauses."
  [body]
  (let [clauses (some-> (honeysql 'honey.sql/clause-order) (#(set (%))))
        maps (tree-seq #(and (branch? %) (not (seq? %)))
                       #(if (map? %) (vals (dissoc % :values :set :do-update-set)) (seq %))
                       body)]
    (distinct (for [m maps
                    :when (and (map? m) (seq m) (or (statement? m) (and clauses (every? clauses (keys m)))))
                    k (keys m)
                    :when (and (keyword? k) (not (read-clauses k)) (not (ignored-clauses k)))]
                {:kind :unread-clause :clause k}))))

(defn- query!
  "body when it is HoneySQL query data (a map, or code holding maps); an error saying so for SQL
   already formatted or for nothing, which would otherwise check clean."
  [body]
  (when (or (nil? body) (string? body) (and (vector? body) (string? (first body))))
    (throw (ex-info (str "pgmalli.honeysql reads HoneySQL query data (a map such as {:select ...}), not "
                         (if (nil? body) "nil" "SQL already formatted"))
                    {:query body})))
  body)

(defn- code-ctes?
  "Whether code (not data) gives a statement some of its CTEs, which may then be any table."
  [stmt]
  (some #(let [w (get stmt %)] (and (some? w) (or (not (vector? w)) (not-every? vector? w)))) with-keys))

(defn check
  "The problems of every statement of a query, and the clauses it uses that are read nowhere
   here (:unread-clause: a HoneySQL clause pgmalli does not know, or one of your own), so a
   query is never judged on part of it in silence. A query whose :with, or one of its entries,
   is built elsewhere (not data) cannot have its unknown tables judged, so those are left out
   for it. Code in the query is never read as columns: what it builds is not in the data."
  ([registry body] (check registry body {}))
  ([registry body opts]
   (jdbc/options! opts)
   (query! body)
   (let [registry (reading registry)
         opaque? (some code-ctes? (statements body))
         found (mapcat (fn [[stmt scopes]] (problems registry stmt scopes opts)) (statement-chains registry body opts))]
     (vec (concat (if opaque? (remove #(= :unknown-table (:kind %)) found) found) (unread-clauses body))))))

;;; types

(defn- driver-type
  "A column schema as jdbc's read-type or write-type (f) gives it, without NULL, as malli's
   default registry reads it."
  [registry f s opts]
  (portable/portable-data (::schemas registry) (f (shape/non-null s) opts)))

(def ^:private maybe? shape/maybe?)

(defn- untyped?
  "A type that says nothing: :any, with or without properties."
  [t]
  (or (nil? t) (= :any t) (and (vector? t) (= :any (first t)))))

(defn- nullable [t] (if (or (untyped? t) (maybe? t)) t [:maybe t]))

(defn- arg-key [x] (or (param-key x) (when (symbol? x) x)))

(defn- array-arg
  "[:any x] or [:all x] (= ANY(?), <> ALL(?)): the argument x, sent as one array."
  [v]
  (when (and (vector? v) (= 2 (count v)) (#{:any :all} (first v)) (arg-key (second v))) (second v)))

(defn- column-type
  "The type of an argument for a column: compared with it (NULL never matches, so no
   [:maybe]) or, when assigned?, assigned to it."
  [registry sc col assigned? opts]
  (if-let [s (:schema (resolve-column registry sc col))]
    (let [inner (driver-type registry jdbc/write-type s opts)]
      (if (and assigned? (maybe? s)) [:maybe inner] inner))
    :any))

(defn- loose
  "A column type without the bounds a comparison need not keep (CHECKs, lengths, ranges): its
   kind and :pg/type. A type with other children (an enum, an :or) stays: PostgreSQL refuses a
   value outside an enum, even compared."
  [t]
  (if-not (vector? t)
    t
    (let [[h & more] t
          [p & children] (if (map? (first more)) more (cons nil more))]
      (cond (= :and h) (let [base (loose (first children))]
                         (if-let [pg (and (vector? base) (not (map? (second base))) (:pg/type p))]
                           (into [(first base) {:pg/type pg}] (rest base))
                           (if (and (keyword? base) (:pg/type p)) [base {:pg/type (:pg/type p)}] base)))
            (#{:maybe :vector :sequential :set} h) [h (loose (last t))]
            (seq children) t
            :else (if-let [pg (:pg/type p)] [h {:pg/type pg}] h)))))

(defn- compared-type
  "The type of an argument compared with a column of type t by op: :in's a non-empty vector,
   list or set of t, as sql/format expands it (IN () is not SQL); an array's what :write's
   :array names; a LIKE pattern's t without its bounds, a pattern being no value of the
   column. {:compare :lenient} takes any value of the column's kind, nil too but in :in (nil
   formats as IN ?): one the column cannot hold matches no row, as NULL does."
  [op t array? {:keys [compare] :as opts}]
  (let [lenient? (= :lenient compare)
        t (cond-> t (or lenient? (like-ops op)) loose)]
    (cond-> (cond array? (jdbc/array-type t opts)
                  (and (in-ops op) (not (untyped? t))) [:or [:sequential {:min 1} t] [:set {:min 1} t]]
                  :else t)
      (and lenient? (not (in-ops op))) nullable)))

(defn- data-args
  "The arguments the data of a query shows, outside code (a list): code may leave its own out
   at run time, and binds locals of its own, which are no arguments."
  [body]
  (set (keep arg-key (tree-seq #(and (branch? %) (not (seq? %))) seq body))))

(defn arg-types
  "{arg schema} for the arguments of a query: its HoneySQL named parameters (:?id, ?id,
   [:param :id]) under the key sql/format's :params takes (:id), and its other symbols. One
   compared with a column has the column's type, without NULL, which matches no row
   ({:compare :lenient} takes any value of the column's kind, and NULL but in :in); a LIKE
   pattern the column's kind; one in :in a non-empty vector, list or set of the column's type;
   one in [:any x] or [:all x], or for an array column, what :write's :array names
   ({:array [:pg/array :sequential :set]}), :any without it; one assigned to a column the
   column's type with NULL; one in :limit / :offset :int. The type is what the driver takes
   (:time, :write; see pgmalli.core/as-read). One used only inside code (a list: (when x
   [...])) takes nil too, the code possibly leaving it out. The first typed use wins."
  ([registry body] (arg-types registry body {}))
  ([registry body {:keys [schema] :or {schema default-schema} :as opts}]
   (jdbc/options! opts)
   (query! body)
   (let [registry (reading registry)
         typed (mapcat (fn [[stmt sc]]
                         (concat (for [[op col value] (comparisons stmt)
                                       :let [x (array-arg value)]]
                                   [(or x value) (compared-type op (column-type registry sc col false opts) x opts)])
                                 (for [[_ v] (select-keys stmt [:limit :offset])] [v :int])
                                 (for [[col v] (assignments stmt)] [v (column-type registry (target-scope sc stmt schema) col true opts)])))
                       (statement-chains registry body opts))
         types (reduce (fn [acc [x t]]
                         (let [k (arg-key x)]
                           (if (and k (untyped? (acc k))) (assoc acc k t) acc)))
                       {} typed)
         in-data (data-args body)]
     (into {} (for [[k t] types] [k (cond-> t (not (in-data k)) nullable)])))))

(defn- select-row
  "The row a SELECT returns. Under :qualified?, a column through an opaque table, or a cast
   column, leaves the row untold: the driver keys it by the table it comes from at the bottom
   (a subquery's, a CTE's), or keeps it when PostgreSQL drops a cast to the column's own type."
  [registry stmt ctes {:keys [qualified?] :as opts}]
  (let [registry (reading registry)
        sc (scope registry stmt ctes opts)
        ;; table nil: an expression comes from no table, and the driver keys it unqualified
        entry (fn [table column schema nullable?]
                (jdbc/row-entry (jdbc/row-key table column opts)
                                (if schema (driver-type registry jdbc/read-type schema opts) :any)
                                (or nullable? (nil? schema) (maybe? schema)) opts))
        star (fn [col]
               (let [[a] (split-column col)
                     tables (if a (some-> (get sc a) vector) (vals sc))]
                 (when (and (seq tables) (every? #(and % (not (:opaque? %))) tables))
                   (for [{:keys [table nullable?]} tables, [c s] (column-entries registry table)]
                     (entry table c s nullable?)))))
        items (for [item (selected-items stmt)
                    :let [[col alias] (select-parts item)]]
                (cond
                  (nil? col) (when-not (and qualified? (nil? alias) (cast-of-column? (item-expression item)))
                               (when-let [n (item-name item)] [(entry nil n nil true)]))
                  (star? col) (star col)
                  :else (when-let [{:keys [table column schema nullable? opaque?]} (resolve-column registry sc col)]
                          (when-not (and qualified? opaque?)
                            ;; a column under an alias is still keyed by its table: the driver reads the table from the field
                            [(entry table (if alias (name alias) column) schema nullable?)]))))]
    (when (and (seq items) (every? some? items) (not-any? :unknown? (vals sc)))
      ;; a name selected twice is one key, the driver keeping the last column's value
      (let [entries (apply concat items)
            last-of (into {} (map (juxt first identity)) entries)]
        (into [:map] (map last-of) (distinct (map first entries)))))))

(declare row-schema*)

(defn- set-operation-row [registry members ctes opts]
  (when (and (vector? members) (seq members) (every? map? members))
    (let [rows (for [m members] (row-schema* registry m (into ctes (cte-names m)) (dissoc opts :qualified? :nil-columns)))]
      (when (and (every? some? rows) (apply = (map count rows)))
        (into [:map]
              (for [entries (apply map vector (map rest rows))
                    :let [schemas (map last entries)
                          inners (distinct (map shape/non-null schemas))]]
                (jdbc/row-entry (ffirst entries) (if (= 1 (count inners)) (first inners) :any) (some maybe? schemas) opts)))))))

(defn- row-schema*
  "row-schema of a statement inside a query whose CTE names are ctes."
  [registry stmt ctes opts]
  (if-let [members (some stmt set-operation-keys)]
    (set-operation-row registry members ctes opts)
    (select-row registry stmt ctes opts)))

(defn row-schema
  "The [:map ...] of one row a query returns, as next.jdbc builds it from what the driver
   returns (the options of pgmalli.core/as-read) and as malli's default registry reads it: the
   columns of the tables, those an outer join may leave NULL nullable; :* and :t/* expanded; an
   expression under an alias, or under the name PostgreSQL gives it, nullable :any. A set
   operation's row is its first member's names, typed where the members agree, nullable where
   one is, unqualified (its columns come from no table). nil when a selected column cannot be
   resolved."
  ([registry body] (row-schema registry body {}))
  ([registry body opts]
   (jdbc/options! opts)
   (query! body)
   (when (map? body) (row-schema* registry body (cte-names body) opts))))

(defn query-schema
  "[:=> [:cat arg-types...] [:sequential row]], the malli function schema of a function taking
   args (keys of arg-types; a key the query lacks is :any) and running body; with
   {:result :one} the return is [:maybe row]. A return that cannot be resolved is :any. The
   problems check finds, and an argument of the query args lack (:missing-arg, a typo in args
   most likely), are the schema's :pgmalli/problems property, not an error: a schema in
   :malli/schema metadata is made as its namespace loads, which a typo, or a registry not yet
   generated again, should not stop. The property makes the vector one longer: read its parts
   with m/children, not by position."
  ([registry args body] (query-schema registry args body {}))
  ([registry args body {:keys [result] :as opts}]
   (jdbc/options! opts)
   (query! body)
   (when-not (and (sequential? args) (every? #(or (keyword? %) (symbol? %)) args))
     (throw (ex-info (str "query-schema takes the function's args first, keywords or symbols ([:db :id]), not " (pr-str args))
                     {:args args})))
   (let [registry (reading registry)
         types (arg-types registry body opts)
         problems (into (check registry body opts)
                          (for [k (sort-by str (data-args body)) :when (and (contains? types k) (not (some #{k} args)))] {:kind :missing-arg :arg k}))
         row (row-schema registry body opts)]
     (into (cond-> [:=>] (seq problems) (conj {:pgmalli/problems problems}))
           [(into [:cat] (map #(get types % :any)) args)
            (cond (nil? row) :any (= :one result) [:maybe row] :else [:sequential row])]))))
