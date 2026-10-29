(ns pgmalli.honeysql
  "HoneySQL query data checked against a registry, without a database: the tables must
   exist, the columns a query selects, returns, inserts, sets or compares must exist, an
   INSERT must carry the required columns, an enum literal must be one of the enum's values;
   and from the same data, the types of the query's parameters and of the rows it returns,
   as malli schemas.

   Scope: tables come from :from, :using, :cross-join, the joins (:join, :left-join,
   :right-join, :inner-join, :full-join, :outer-join; :join-by is not read), :insert-into,
   :update and :delete-from, under their aliases. CTEs (:with), subqueries, table functions,
   a symbol where a table goes and a pg_catalog table the registry lacks are opaque tables:
   their columns exist but have no type. Every table has the system columns (ctid, xmin, ...). A column is :col (unique in the scope), :alias/col or
   :alias.col; a column a subquery cannot resolve is looked for in the enclosing statements,
   as PostgreSQL does. An unqualified table is in :schema (default \"public\").

   The entry points are check, arg-types, row-schema and query-schema; the other public vars
   are the parts they are built from.

   Types are data malli's default registry reads (see pgmalli.core/portable): date and
   timestamp columns are inst?. :time :instant or :local, or :read, gives the types they name
   (the malli.experimental.time ones), as as-read does; the default registry lacks those, so
   such a schema is made an object over pgmalli's registry."
  (:require [clojure.string :as str]
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

(def ^:private statement-keys
  #{:select :select-distinct :select-distinct-on :select-top :select-distinct-top
    :insert-into :update :delete-from :union :union-all :intersect :except})
(def ^:private join-keys [:join :left-join :inner-join :right-join :outer-join :full-join])

(defn- statement?
  "A query map; one holding only a set operation is one too, its members statements of their own."
  [x]
  (and (map? x) (some statement-keys (keys x))))

(defn- own-nodes
  "The nodes of one statement's part, not descending into the statements nested in it: those
   are statements of their own, judged in their own scope."
  [part]
  (tree-seq #(and (coll? %) (not (statement? %))) seq part))

(defn statements
  "The statements in a query (a map, or Clojure data holding maps); a subquery is one too."
  [body]
  (into [] (filter statement?) (tree-seq coll? seq body)))

(defn- cte-name [entry]
  (when (vector? entry)
    (let [n (if (vector? (first entry)) (ffirst entry) (first entry))]
      (when (keyword? n) (name n)))))

(defn- cte-entries
  "The CTE entries a node holds: the items of :with and :with-recursive, and a [name statement]
   pair on its own (a CTE built up in code before it is put under :with)."
  [x]
  (cond
    (map? x) (mapcat #(let [w (get x %)] (when (vector? w) w)) [:with :with-recursive])
    (and (vector? x) (<= 2 (count x) 3) (statement? (second x))) [x]))

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
        [table listed] (if (and (vector? target) (keyword? (first target))) [(first target) (second target)] [target nil])]
    (when (or (keyword? table) (symbol? table))
      {:table table :columns (or listed (let [c (:columns stmt)] (when (vector? c) c))) :select select})))

(defn- cte-names*
  "CTE names in a query: the :with entries of its statements when with? holds, and the
   [name statement] pairs sitting outside any :with (CTEs built up in code). The value of
   :insert-into, [target {:select ...}], has that shape too and is not one."
  [x with?]
  (cond (statement? x) (concat (when with? (keep cte-name (cte-entries x)))
                               (mapcat #(cte-names* % with?) (vals (dissoc x :with :with-recursive :insert-into)))
                               (cte-names* (:select (insert-parts x)) with?))
        (and (vector? x) (<= 2 (count x) 3) (statement? (second x)) (cte-name x)) (cons (cte-name x) (cte-names* (second x) with?))
        (coll? x) (mapcat #(cte-names* % with?) (seq x))))

(defn cte-names
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
    (vector? x)
    (let [[a b] x
          ;; [:alias {:columns [...]}]
          [b listed] (if (and (vector? b) (keyword? (first b)) (map? (second b))) [(first b) (mapv name (:columns (second b)))] [b nil])]
      (cond
        ;; :t / [:t :alias] / INSERT INTO t (columns...)
        (and (keyword? a) (or (nil? b) (keyword? b) (vector? b))) [(qualified a schema) (name (if (keyword? b) b a)) (written a) listed]
        (keyword? a) [nil (name a) nil listed]
        (keyword? b) [nil (name b) nil listed]
        ;; [[:f args...]]: a table function, named after the function
        (and (nil? b) (vector? a) (keyword? (first a))) [nil (name (first a)) nil]))))

(defn- insert-target [stmt] (:table (insert-parts stmt)))

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

(defn scope
  "{alias {:table \"schema.table\" :opaque? bool :cte? bool}} of one statement; ctes are the
   query's CTE names, which shadow a table only when the reference is written without a schema."
  [registry stmt ctes {:keys [schema] :or {schema "public"}}]
  (let [registry (reading registry)
        refs (concat (for [k [:from :using :cross-join] :let [f (get stmt k)] :when f, t (if (or (keyword? f) (symbol? f)) [f] f)] t)
                     (for [k join-keys :let [j (get stmt k)] :when (vector? j), t (map first (partition 2 j))] t)
                     [(insert-target stmt) (:update stmt) (:delete-from stmt)])
        sc (into {}
                 (for [[table alias written listed] (keep #(table-ref % schema) refs)
                       :let [cte? (boolean (and written (ctes written)))
                             table (when table (catalog-table registry table written))]]
                   [alias (cond-> {:table table :cte? cte?
                                   :opaque? (boolean (or (nil? table) cte? (nil? (table-columns registry table))))}
                            ;; the columns an alias lists are the ones it has, typeless
                            listed (assoc :columns listed))]))]
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
                                       chain (cons (scope registry x ctes opts) outer)]
                                   (cons [x chain]
                                         (concat
                                          ;; INSERT ... SELECT: the SELECT does not see the table inserted into
                                          (walk (:insert-into x) outer ctes false true)
                                          (mapcat #(walk % chain ctes false true) (vals (dissoc x :insert-into))))))
                  (coll? x) (mapcat #(walk % outer ctes (or in-vector? (vector? x)) under?) (seq x))))]
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
  "[[alias table opaque?] ...] of the tables of one scope a column may belong to."
  [registry scope col]
  (let [[alias column] (split-column col)]
    (if alias
      (when-let [{:keys [table opaque?] :as entry} (get scope alias)]
        (when (has-column? registry entry column) [[alias table opaque?]]))
      ;; an unqualified column is never EXCLUDED's: that row is only reached through its name
      (distinct (for [[a {:keys [table opaque? excluded?] :as entry}] scope
                      :when (and (not excluded?) (has-column? registry entry column))]
                  [a table opaque?])))))

(defn- column-hits
  "The hits of the innermost scope (a map, or the chain of a statement and its enclosing
   ones) that has any."
  [registry scopes col]
  (let [[sc & outer] (if (map? scopes) [scopes] scopes)
        hits (own-hits registry sc col)
        [alias] (split-column col)]
    (cond (or (seq hits) (empty? outer)) hits
          ;; the enclosing statement is not in the data: a column not of this scope's tables is
          ;; one of its, untyped
          (= :open (first outer)) (if (and alias (contains? sc alias)) hits [[alias nil true]])
          :else (column-hits registry outer col))))

(defn resolve-column
  "{:table :column :schema} for a column keyword in a scope (or the chain of scopes of a
   nested statement), :table the alias for an opaque table and :schema nil there; nil when
   the column resolves to no table or to several."
  [registry scope col]
  (let [registry (reading registry)
        hits (column-hits registry scope col)]
    (when (= 1 (count hits))
      (let [[alias table opaque?] (first hits)
            column (second (split-column col))]
        {:table (or table alias) :column column :schema (when-not opaque? (get (table-columns registry table) column))}))))

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

(defn- column-keyword? [x] (and (keyword? x) (not (sql-values x)) (not (param-key x))))

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

(def ^:private comparison-ops #{:= :<> :< :> :<= :>= :in :like :ilike})

(defn- comparisons
  "[op column other] for each column side of the comparisons in a statement's conditions: a
   column on either side, the other side whatever it is (a value, a symbol, a column)."
  [stmt]
  (for [x (own-nodes (cond-> (select-keys stmt (into [:where :having :on-conflict] join-keys))
                       ;; the condition of ON CONFLICT DO UPDATE SET {:fields ... :where ...}
                       (map? (:do-update-set stmt)) (assoc :do-update-where (:where (:do-update-set stmt)))))
        :when (and (vector? x) (= 3 (count x)) (comparison-ops (first x)))
        :let [[op a b] x]
        [col other] (cond-> [] (column-keyword? a) (conj [a b]) (column-keyword? b) (conj [b a]))]
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
    (select-keys (first scopes) [alias])))

;;; the name PostgreSQL gives an output column

(def ^:private operators
  "HoneySQL's infix operators and the special syntax that is an operator in SQL: PostgreSQL
   names their column ?column?."
  (into #{} (map keyword)
        ["and" "or" "xor" "<>" "<=" ">=" "||" "<->" "like" "not-like" "regexp" "regex" "~" "&&" "ilike"
         "not-ilike" "similar-to" "not-similar-to" "is" "is-not" "not=" "!=" "is-distinct-from"
         "is-not-distinct-from" "+" "-" "*" "%" "|" "&" "^" "=" "<" ">" "/" "in" "not-in" "not"
         "between" "not-between" "inline" "lift" "param"]))

(def ^:private special-syntax
  "HoneySQL's special syntax that is not a function call; a column it makes is not named here."
  #{:entity :alias :at :at-time-zone :call :composite :distinct :escape :filter :get-in
    :ignore-nulls :interval :join :lateral :nest :object :record :order-by :over :raw
    :respect-nulls :within-group :xtql :dot :default :foreign-key :references :unique
    :primary-key :index :constraint})

(defn- cast-of-column? [x]
  (and (vector? x) (= :cast (first x))
       (or (column-keyword? (second x)) (cast-of-column? (second x)))))

(defn- expression-name
  "The column name PostgreSQL gives a select expression with no alias: a function's name, case,
   array, a cast column's name, ?column? for a value or an operator. nil when this cannot tell
   (a cast value is named after its type, a subquery after its column, :raw anything)."
  [x]
  (cond (or (number? x) (string? x) (boolean? x) (nil? x) (param-key x) (#{:null :true :false} x)) "?column?"
        (keyword? x) (when (sql-values x) (name x))
        (not (and (vector? x) (keyword? (first x)))) nil
        :else (let [[h a] x]
                (cond (operators h) "?column?"
                      (#{:case :case-expr} h) "case"
                      (= :array h) "array"
                      (= :cast h) (cond (column-keyword? a) (second (split-column a))
                                        (vector? a) (expression-name a))
                      (special-syntax h) nil
                      ;; a function, as HoneySQL writes its name (- is _) and PostgreSQL folds it
                      :else (-> (name h) (str/split #"\.") last (str/replace "-" "_") str/lower-case)))))

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

(defn- output-name
  "The name of the output column a bare :col of :order-by or :group-by may mean (a select
   alias, or a selected column's name), nil when there is none."
  [stmt col]
  (when-not (first (split-column col))
    (some (fn [item]
            (let [[c alias] (select-parts item)]
              (when (= (name col) (if alias (name alias) (some-> c split-column second))) col)))
          (selected-items stmt))))

(defn- output-statement
  "The statement whose select names a statement's output columns: itself, or the first member
   of a set operation (UNION ...); nil when that member is built by code."
  [stmt]
  (if-let [members (some stmt [:union :union-all :intersect :except])]
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
                  :when (and (column-keyword? c) (not (and (output-name stmt c) (empty? (column-hits registry sc c)))))]
              c)
            (when out
              (for [o (vectorish (:order-by stmt)) :let [c (if (vector? o) (first o) o)]
                    :when (and (column-keyword? c) (not (output-name out c)))]
                c)))))

(defn- column-problems [registry sc stmt schema]
  (for [[col scope] (distinct (concat (for [c (keep (comp first select-parts) (selected-items stmt))] [c sc])
                                      (for [c (ordering-columns registry sc stmt)] [c sc])
                                      (for [[_ c] (comparisons stmt)] [c sc])
                                      (for [c (:columns (insert-parts stmt)) :when (column-keyword? c)] [c (target-scope sc stmt schema)])
                                      (for [[c] (assignments stmt)] [c (target-scope sc stmt schema)])))
        :when (and (not (star? col)) (nil? (resolve-column registry scope col)))
        :let [hits (column-hits registry scope col)]]
    (if (< 1 (count hits))
      {:kind :ambiguous-column :column col :candidates (mapv (fn [[alias table]] (or table alias)) hits)}
      {:kind :unknown-column :column col})))

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
  (for [[col value scope] (concat (for [[op col value] (comparisons stmt) :when (#{:= :<> :in} op)] [col value sc])
                                  (for [[col value] (assignments stmt)] [col value (target-scope sc stmt schema)]))
        :let [{s :schema} (resolve-column registry scope col)
              values (cond (string? value) [value]
                           (and (vector? value) (= :cast (first value)) (string? (second value))) [(second value)]
                           (and (vector? value) (every? string? value)) value)
              allowed (when s (enum-values registry s))]
        :when (and allowed (seq values))
        v values :when (not (contains? allowed v))]
    {:kind :enum-literal :column col :value v :allowed allowed}))

(defn problems
  "The problems of one statement: unknown tables; columns selected, returned, inserted, set
   or compared that are unknown, or ambiguous (unqualified and in more than one table in
   scope, those under :candidates), in :select, :where, :having, join conditions, :set, :values,
   ON CONFLICT DO UPDATE SET, :group-by and :order-by (bare columns there); required INSERT
   columns missing, or a row of :values not as long as :columns; enum literals outside the enum (compared with,
   or assigned to, the column). scope is the statement's (from scope) or, for a nested
   statement, the chain of its own and the enclosing ones. Empty when the statement agrees
   with the registry."
  [registry stmt scope {:keys [schema] :or {schema "public"}}]
  (let [registry (reading registry)
        scopes (if (map? scope) [scope] scope)]
    (vec (concat (for [[_ {:keys [table cte?]}] (first scopes)
                       :when (and table (not cte?) (not (catalog? table)) (nil? (table-columns registry table)))]
                   {:kind :unknown-table :table table})
                 (column-problems registry scopes stmt schema)
                 (insert-problems registry stmt schema)
                 (enum-problems registry scopes stmt schema)))))

(defn check
  "The problems of every statement of a query. A query whose :with, or one of its entries, is
   built elsewhere (not data) cannot have its unknown tables judged, so those are left out for
   it. Code in the query is never read as columns: what it builds is not in the data."
  ([registry body] (check registry body {}))
  ([registry body opts]
   (let [registry (reading registry)
         opaque? (some (fn [stmt] (some #(let [w (get stmt %)] (and (some? w) (or (not (vector? w)) (not-every? vector? w)))) [:with :with-recursive])) (statements body))
         found (mapcat (fn [[stmt scopes]] (problems registry stmt scopes opts)) (statement-chains registry body opts))]
     (vec (if opaque? (remove #(= :unknown-table (:kind %)) found) found)))))

;;; types

(defn- read-shape
  "A column schema as a driver returns it and as malli's default registry reads it; an
   interval is a driver object, :any, unless :read says otherwise."
  [registry schema {:keys [time read] :or {time :inst}}]
  (let [t (if (vector? schema) (first schema) schema)]
    (portable/portable-data (::schemas registry) (or (shape/read-as read schema)
                                                     (shape/read-time time (if (= :time/duration t) :any schema))))))

(defn- column-type
  "The type of a column for a value compared with it (NULL never matches, so no [:maybe]) or,
   with nullable?, assigned to it."
  [registry sc col nullable? opts]
  (if-let [s (:schema (resolve-column registry sc col))]
    (let [inner (read-shape registry (shape/non-null s) opts)]
      (if (and nullable? (= :maybe (first s))) [:maybe inner] inner))
    :any))

(defn arg-types
  "{arg schema} for the arguments of a query: its HoneySQL named parameters (:?id, ?id,
   [:param :id]) under the key sql/format's :params takes (:id), and its other symbols. An argument compared with a column has the column's
   type (:in a :sequential of it), one assigned to it the column's type with NULL, one in
   :limit / :offset :int. The first typed use wins; a use that gives no type (:any) never
   hides one that does."
  ([registry body] (arg-types registry body {}))
  ([registry body {:keys [schema] :or {schema "public"} :as opts}]
   (let [registry (reading registry)
         typed (for [[stmt sc] (statement-chains registry body opts)
                     pair (concat (for [[op col value] (comparisons stmt)
                                        :let [t (column-type registry sc col false opts)]]
                                    [value (if (and (= :in op) (not= :any t)) [:sequential t] t)])
                                  (for [[_ v] (select-keys stmt [:limit :offset])] [v :int])
                                  (for [[col v] (assignments stmt)] [v (column-type registry (target-scope sc stmt schema) col true opts)]))]
                 pair)]
     (reduce (fn [acc [x t]]
               (let [k (or (param-key x) (when (symbol? x) x))]
                 (if (and k (contains? #{nil :any} (acc k))) (assoc acc k t) acc)))
             {} typed))))

(declare select-row set-operation-row)

(defn row-schema
  "The [:map ...] of one row a statement returns, as the driver builds it (as-read's
   :qualified?, :kebab?, :nil-columns and :time; an opaque source's columns under its alias)
   and as malli's default registry reads it; an expression under an alias is nullable :any;
   :* and :t/* are the columns of the tables; an expression with no alias is under the name
   PostgreSQL gives it. A set operation's row is named by its first member, typed where its
   members agree, nullable where one is, and unqualified: its columns come from no table. nil
   when a selected column cannot be resolved."
  ([registry stmt ctes] (row-schema registry stmt ctes {}))
  ([registry stmt ctes opts]
   (if-let [members (some stmt [:union :union-all :intersect :except])]
     (set-operation-row registry members ctes opts)
     (select-row registry stmt ctes opts))))

(defn- select-row [registry stmt ctes {:keys [qualified? kebab? nil-columns] :as opts}]
   (let [registry (reading registry)
         sc (scope registry stmt ctes opts)
         kebab (fn [s] (cond-> s kebab? (str/replace "_" "-")))
         ;; an expression has no table, so the driver keys it unqualified
         key-of (fn [table column] (if (and qualified? table) (keyword (kebab (last (str/split table #"\."))) (kebab column)) (keyword (kebab column))))
         entry (fn [k schema]
                 (let [nullable? (or (nil? schema) (= :maybe (first schema)))
                       inner (if schema (read-shape registry (shape/non-null schema) opts) :any)]
                   (cond (not nullable?) [k inner]
                         (= :absent nil-columns) [k {:optional true} inner]
                         :else [k [:maybe inner]])))
         star (fn [col]
                (let [[a] (split-column col)
                      tables (if a (some-> (get sc a) vector) (vals sc))]
                  (when (and (seq tables) (every? #(and % (not (:opaque? %))) tables))
                    (for [{:keys [table]} tables, [c s] (column-entries registry table)] (entry (key-of table c) s)))))
         items (for [item (selected-items stmt)
                     :let [[col alias] (select-parts item)]]
                 (cond
                   (and alias (nil? col)) [(entry (key-of nil (name alias)) nil)]
                   (nil? col) (let [x (if (and (vector? item) (= 1 (count item))) (first item) item)]
                                ;; a cast PostgreSQL drops (to the column's own type) leaves the column, keyed
                                ;; by its table, so whether the key is qualified is not told here
                                (when-let [n (when-not (and qualified? (cast-of-column? x)) (expression-name x))]
                                  [(entry (key-of nil n) nil)]))
                   (star? col) (star col)
                   :else (when-let [{:keys [table column schema]} (resolve-column registry sc col)]
                           ;; a column under an alias is still keyed by its table: the driver reads the table from the field
                           [(entry (key-of table (if alias (name alias) column)) schema)])))]
     (when (and (seq items) (every? some? items))
       ;; a name selected twice is one key, the driver keeping the last column's value
       (let [entries (apply concat items)
             last-of (into {} (map (juxt first identity)) entries)]
         (into [:map] (map last-of) (distinct (map first entries)))))))

(defn- set-operation-row [registry members ctes {:keys [nil-columns] :as opts}]
  (when (and (vector? members) (seq members) (every? map? members))
    (let [rows (for [m members] (row-schema registry m (into ctes (cte-names m)) (assoc opts :qualified? false :nil-columns nil)))]
      (when (and (every? some? rows) (apply = (map count rows)))
        (into [:map]
              (for [entries (apply map vector (map rest rows))
                    :let [k (ffirst entries)
                          schemas (map last entries)
                          inners (distinct (map shape/non-null schemas))
                          inner (if (= 1 (count inners)) (first inners) :any)]]
                (cond (not-any? #(and (vector? %) (= :maybe (first %))) schemas) [k inner]
                      (= :absent nil-columns) [k {:optional true} inner]
                      :else [k [:maybe inner]])))))))

(defn query-schema
  "[:=> [:cat arg-types...] [:sequential row]] for a function taking args (keys of arg-types)
   and running body: a
   malli function schema for instrumentation; with {:result :one} the return is [:maybe row],
   for a function returning one row or nil. A return that cannot be resolved is :any."
  ([registry args body] (query-schema registry args body {}))
  ([registry args body {:keys [result] :as opts}]
   (let [registry (reading registry)
         types (arg-types registry body opts)
         row (when (map? body) (row-schema registry body (cte-names body) opts))]
     [:=> (into [:cat] (map #(get types % :any)) args)
      (cond (nil? row) :any (= :one result) [:maybe row] :else [:sequential row])])))
