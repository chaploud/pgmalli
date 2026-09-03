(ns pgmalli.honeysql
  "HoneySQL query data checked against a registry, without a database: the tables must
   exist, the columns a query selects, returns, inserts, sets or compares must exist, an
   INSERT must carry the required columns, an enum literal must be one of the enum's values;
   and from the same data, the types of the query's parameters and of the rows it returns,
   as malli schemas.

   Scope: tables come from :from, :using, the joins, :insert-into, :update and :delete-from,
   under their aliases. CTEs (:with), subqueries and table functions are opaque tables: their
   columns exist but have no type. A column is :col (unique in the scope), :alias/col or
   :alias.col; a column a subquery cannot resolve is looked for in the enclosing statements,
   as PostgreSQL does. An unqualified table is in :schema (default \"public\").

   Types are data malli's default registry reads (see pgmalli.core/portable). Date and
   timestamp columns are inst? by default; :time :instant or :local gives the
   malli.experimental.time types, as as-read does."
  (:require [clojure.string :as str]
            [pgmalli.impl.portable :as portable]
            [pgmalli.impl.shape :as shape]))

;;; tables and columns of the registry

(defn- column-entries
  "[[column-name schema] ...] of a table (or view) in the registry, in its order; nil when it
   is not there."
  [registry table]
  (some->> (get (::schemas registry) (shape/table-key table)) shape/column-entries (map (fn [[k _ s]] [(name k) s]))))

(defn- reading
  "The registry as this namespace reads it: its schemas taken once (a composite registry merges
   them on every lookup) and the columns of a table memoized, since a column is looked for in
   every table in scope. The public functions take a registry and make one of it; one passed on
   again is left as it is."
  [registry]
  (if (::schemas registry)
    registry
    (let [r {::schemas (shape/schemas-of registry)}]
      (assoc r ::table-columns (memoize #(some->> (column-entries r %) (into {})))))))

(defn- table-columns [registry table] ((::table-columns registry) table))

;;; statements, CTEs and scope

(def ^:private statement-keys
  #{:select :select-distinct :select-distinct-on :select-top :select-distinct-top
    :insert-into :update :delete-from :union :union-all :intersect :except})
(def ^:private join-keys [:join :left-join :inner-join :right-join :full-join])

(defn- statement?
  "A query map; one holding only a set operation is one too, its members statements of their own."
  [x]
  (and (map? x) (some statement-keys (keys x))))

(defn- nodes [body] (tree-seq coll? seq body))

(defn- own-nodes
  "The nodes of one statement's part, not descending into the statements nested in it: those
   are statements of their own, judged in their own scope."
  [part]
  (tree-seq #(and (coll? %) (not (statement? %))) seq part))

(defn statements
  "The statements in a query (a map, or Clojure data holding maps); a subquery is one too."
  [body]
  (into [] (filter statement?) (nodes body)))

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

(declare insert-parts)

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
   lists ([:v {:columns [:id :n]}] over a VALUES); an opaque item (subquery, function) has no
   table."
  [x schema]
  (cond
    (keyword? x) [(qualified x schema) (name x) (written x)]
    (vector? x)
    (let [[a b] x
          ;; [:alias {:columns [...]}]
          [b listed] (if (and (vector? b) (keyword? (first b)) (map? (second b))) [(first b) (mapv name (:columns (second b)))] [b nil])]
      (cond
        ;; :t / [:t :alias] / INSERT INTO t (columns...)
        (and (keyword? a) (or (nil? b) (keyword? b) (vector? b))) [(qualified a schema) (name (if (keyword? b) b a)) (written a) listed]
        (keyword? a) [nil (name a) nil listed]
        (keyword? b) [nil (name b) nil listed]
        (vector? a) (table-ref a schema)))))

(defn- insert-parts
  "{:table :columns :select} of :insert-into in its HoneySQL shapes: :t, [:t [:a :b]],
   [target {:select ...}], any of them behind an option map ({:overriding-value :system}); the
   statement's :columns when the target lists none. nil when the target is not a table name
   (a symbol, rows built elsewhere)."
  [stmt]
  (let [i (:insert-into stmt)
        i (if (and (vector? i) (map? (first i))) (vec (rest i)) i)
        i (if (and (vector? i) (= 1 (count i))) (first i) i)
        [target select] (if (and (vector? i) (map? (second i))) [(first i) (second i)] [i nil])
        [table listed] (if (and (vector? target) (keyword? (first target))) [(first target) (second target)] [target nil])]
    (when (keyword? table)
      {:table table :columns (or listed (let [c (:columns stmt)] (when (vector? c) c))) :select select})))

(defn- insert-target [stmt] (:table (insert-parts stmt)))

(defn scope
  "{alias {:table \"schema.table\" :opaque? bool :cte? bool}} of one statement; ctes are the
   query's CTE names, which shadow a table only when the reference is written without a schema."
  [registry stmt ctes {:keys [schema] :or {schema "public"}}]
  (let [registry (reading registry)
        refs (concat (for [k [:from :using :cross-join] :let [f (get stmt k)] :when f, t (if (keyword? f) [f] f)] t)
                     (for [k join-keys :let [j (get stmt k)] :when (vector? j), t (map first (partition 2 j))] t)
                     [(insert-target stmt) (:update stmt) (:delete-from stmt)])
        sc (into {}
                 (for [[table alias written listed] (keep #(table-ref % schema) refs)
                       :let [cte? (boolean (and written (ctes written)))]]
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

(defn- column-keyword? [x] (and (keyword? x) (not (sql-values x))))

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
    (keyword? item) (when (column-keyword? item) [item nil])
    (not (vector? item)) nil
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
    (concat (when (map? (:set stmt)) (:set stmt))
            ;; ON CONFLICT DO UPDATE SET: a map of assignments, the columns taken from EXCLUDED, or
            ;; {:fields [...] :where ...} (the columns taken from EXCLUDED, under a condition)
            (cond (and (map? do-update) (contains? do-update :fields)) (for [c (:fields do-update) :when (keyword? c)] [c nil])
                  (map? do-update) do-update
                  (vector? do-update) (for [c do-update :when (keyword? c)] [c nil]))
            (when (vector? values)
              (concat (mapcat identity (filter map? values))
                      ;; positional rows under the listed columns
                      (when columns (mapcat #(map vector columns %) (filter vector? values))))))))

(defn- target-scope
  "The scope an assigned column is resolved in: the table of :update / :insert-into alone."
  [scopes stmt schema]
  (let [[_ alias] (some-> (or (:update stmt) (insert-target stmt)) (table-ref schema))]
    (select-keys (first scopes) [alias])))

;;; problems

(defn- enum-values [registry schema]
  (let [s (shape/non-null schema)
        s (if (and (vector? s) (= :ref (first s))) (get (::schemas registry) (last s)) s)]
    (when (and (vector? s) (= :enum (first s))) (set (remove map? (rest s))))))

(defn- star? [col] (= "*" (second (split-column col))))

(defn- ordering-columns
  "The columns :order-by and :group-by name as they are (:col, or [:col :asc]); expressions
   there are not read."
  [stmt]
  (concat (for [c (:group-by stmt) :when (column-keyword? c)] c)
          (for [o (:order-by stmt) :let [c (if (vector? o) (first o) o)] :when (column-keyword? c)] c)))

(defn- column-problems [registry sc stmt schema]
  (for [[col scope] (distinct (concat (for [c (keep (comp first select-parts) (selected-items stmt))] [c sc])
                                      (for [c (ordering-columns stmt)] [c sc])
                                      (for [[_ c] (comparisons stmt)] [c sc])
                                      (for [c (:columns (insert-parts stmt))] [c (target-scope sc stmt schema)])
                                      (for [[c] (assignments stmt)] [c (target-scope sc stmt schema)])))
        :when (and (not (star? col)) (nil? (resolve-column registry scope col)))
        :let [hits (column-hits registry scope col)]]
    (if (< 1 (count hits))
      {:kind :ambiguous-column :column col :candidates (mapv (fn [[alias table]] (or table alias)) hits)}
      {:kind :unknown-column :column col})))

(defn- insert-problems [registry stmt schema]
  (when-let [{:keys [table columns]} (insert-parts stmt)]
    (let [[table] (table-ref table schema)
          values (:values stmt)
          rows (when (vector? values) values)
          ;; HoneySQL inserts the union of the columns of all value maps, NULL where a row lacks one
          given (or columns (when (map? (first rows)) (distinct (mapcat keys (filter map? rows)))))
          given-names (set (map name given))
          required (set (for [[k p] (some->> (shape/table-key table) shape/insert-name (get (::schemas registry)) shape/column-entries)
                              :when (not (:optional p))]
                          (name k)))]
      (concat
       (when columns
         (for [[i row] (map-indexed vector (filter vector? rows)) :when (not= (count row) (count columns))]
           {:kind :values-arity :table table :row i :columns (count columns) :values (count row)}))
       (when (seq given)
         (for [c required :when (not (given-names c))]
           {:kind :missing-required-column :table table :column c}))))))

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
                       :when (and table (not cte?) (nil? (table-columns registry table)))]
                   {:kind :unknown-table :table table})
                 (column-problems registry scopes stmt schema)
                 (insert-problems registry stmt schema)
                 (enum-problems registry scopes stmt schema)))))

(defn check
  "The problems of every statement of a query. A query whose :with is built elsewhere (not
   data) cannot have its unknown tables judged, so those are left out for it."
  ([registry body] (check registry body {}))
  ([registry body opts]
   (let [registry (reading registry)
         opaque? (some (fn [stmt] (some #(let [w (get stmt %)] (and (some? w) (not (vector? w)))) [:with :with-recursive])) (statements body))
         found (mapcat (fn [[stmt scopes]] (problems registry stmt scopes opts)) (statement-chains registry body opts))]
     (vec (if opaque? (remove #(= :unknown-table (:kind %)) found) found)))))

;;; types

(defn- read-shape
  "A column schema as a driver returns it and as malli's default registry reads it; an
   interval is a driver object, :any."
  [registry schema {:keys [time] :or {time :inst}}]
  (let [t (if (vector? schema) (first schema) schema)]
    (portable/portable-data (::schemas registry) (shape/read-time time (if (= :time/duration t) :any schema)))))

(defn- column-type
  "The type of a column for a value compared with it (NULL never matches, so no [:maybe]) or,
   with nullable?, assigned to it."
  [registry sc col nullable? opts]
  (if-let [s (:schema (resolve-column registry sc col))]
    (let [inner (read-shape registry (shape/non-null s) opts)]
      (if (and nullable? (= :maybe (first s))) [:maybe inner] inner))
    :any))

(defn arg-types
  "{symbol schema} for the symbols of a query: a symbol compared with a column has the
   column's type (:in a :sequential of it), one assigned to it the column's type with NULL,
   one in :limit / :offset :int. The first typed use wins; a use that gives no type (:any)
   never hides one that does."
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
     (reduce (fn [acc [sym t]] (if (and (symbol? sym) (contains? #{nil :any} (acc sym))) (assoc acc sym t) acc)) {} typed))))

(defn row-schema
  "The [:map ...] of one row a statement returns, as the driver builds it (as-read's
   :qualified?, :kebab?, :nil-columns and :time; an opaque source's columns under its alias)
   and as malli's default registry reads it; an expression under an alias is nullable :any;
   :* and :t/* are the columns of the tables. nil when a selected column cannot be resolved."
  ([registry stmt ctes] (row-schema registry stmt ctes {}))
  ([registry stmt ctes {:keys [qualified? kebab? nil-columns] :as opts}]
   (let [registry (reading registry)
         sc (scope registry stmt ctes opts)
         kebab (fn [s] (cond-> s kebab? (str/replace "_" "-")))
         key-of (fn [table column] (if qualified? (keyword (kebab (last (str/split table #"\."))) (kebab column)) (keyword (kebab column))))
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
                   (nil? col) (when alias [(entry alias nil)])
                   (star? col) (star col)
                   :else (when-let [{:keys [table column schema]} (resolve-column registry sc col)]
                           ;; a column under an alias is still keyed by its table: the driver reads the table from the field
                           [(entry (key-of table (if alias (name alias) column)) schema)])))]
     (when (and (seq items) (every? some? items))
       (into [:map] cat items)))))

(defn query-schema
  "[:=> [:cat arg-types...] [:sequential row]] for a function taking args and running body: a
   malli function schema for instrumentation; with {:result :one} the return is [:maybe row],
   for a function returning one row or nil. A return that cannot be resolved is :any."
  ([registry args body] (query-schema registry args body {}))
  ([registry args body {:keys [result] :as opts}]
   (let [registry (reading registry)
         types (arg-types registry body opts)
         row (when (map? body) (row-schema registry body (cte-names body) opts))]
     [:=> (into [:cat] (map #(get types % :any)) args)
      (cond (nil? row) :any (= :one result) [:maybe row] :else [:sequential row])])))
