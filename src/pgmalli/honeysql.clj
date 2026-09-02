(ns pgmalli.honeysql
  "HoneySQL query data checked against a registry, without a database: the tables must
   exist, the columns a query selects, returns, inserts, sets or compares must exist, an
   INSERT must carry the required columns, an enum literal must be one of the enum's values;
   and from the same data, the types of the query's parameters and of the rows it returns,
   as malli schemas.

   Scope: tables come from :from, :using, the joins, :insert-into, :update and :delete-from,
   under their aliases. CTEs (:with), subqueries and table functions are opaque tables: their
   columns exist but have no type. A column is :col (unique in the scope), :alias/col or
   :alias.col. An unqualified table is in :schema (default \"public\").

   Types are data malli's default registry reads (see pgmalli.core/portable). Date and
   timestamp columns are inst? by default; :time :instant or :local gives the
   malli.experimental.time types, as as-read does."
  (:require [clojure.string :as str]
            [pgmalli.impl.runtime :as rt]))

;;; tables and columns of the registry

(defn- table-key
  "\"public.users\" -> :pg.public/users (a string key when the names are not plain identifiers)."
  [table]
  (let [[schema t] (str/split table #"\." 2)
        plain? #(re-matches #"[A-Za-z_][A-Za-z0-9_]*" %)]
    (if (and (plain? schema) (plain? t)) (keyword (str "pg." schema) t) (str "pg." schema "/" t))))

(defn- table-columns
  "{column-name schema} of a table (or view) in the registry, nil when it is not there."
  [registry table]
  (when-let [row (get registry (table-key table))]
    (into {} (map (fn [[k _ s]] [(name k) s])) (rt/column-entries row))))

;;; statements, CTEs and scope

(def ^:private statement-keys #{:select :select-distinct :insert-into :update :delete-from})
(def ^:private set-ops #{:union :union-all :intersect :except})
(def ^:private cte-body-keys (into statement-keys set-ops))
(def ^:private join-keys [:join :left-join :inner-join :right-join :full-join])

(defn- statement? [x] (and (map? x) (some statement-keys (keys x))))

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
    (and (vector? x) (= 2 (count x)) (map? (second x)) (some cte-body-keys (keys (second x)))) [x]))

(defn cte-names
  "Names of the CTEs a query defines, at any depth."
  [body]
  (into #{} (keep cte-name) (mapcat cte-entries (nodes body))))

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
  "[table alias written] of a :from / join / target item: the table's qualified name, its alias,
   and the name as written (nil when qualified by a schema); an opaque item (subquery, function)
   has no table."
  [x schema]
  (cond
    (keyword? x) [(qualified x schema) (name x) (written x)]
    (vector? x)
    (let [[a b] x]
      (cond
        ;; :t / [:t :alias] / INSERT INTO t (columns...)
        (and (keyword? a) (or (nil? b) (keyword? b) (vector? b))) [(qualified a schema) (name (if (keyword? b) b a)) (written a)]
        (keyword? a) [nil (name a) nil]
        (keyword? b) [nil (name b) nil]
        (vector? a) (table-ref a schema)))))

(defn- insert-target
  "The :insert-into item naming the table, :t or [:t [:a :b]]; with a SELECT it is the first of a pair."
  [stmt]
  (let [i (:insert-into stmt)]
    (if (and (vector? i) (map? (second i))) (first i) i)))

(defn scope
  "{alias {:table \"schema.table\" :opaque? bool :cte? bool}} of one statement; ctes are the
   query's CTE names, which shadow a table only when the reference is written without a schema."
  [registry stmt ctes {:keys [schema] :or {schema "public"}}]
  (let [refs (concat (for [k [:from :using :cross-join] :let [f (get stmt k)] :when f, t (if (keyword? f) [f] f)] t)
                     (for [k join-keys :let [j (get stmt k)] :when (vector? j), t (map first (partition 2 j))] t)
                     [(insert-target stmt) (:update stmt) (:delete-from stmt)])]
    (into {}
          (for [[table alias written] (keep #(table-ref % schema) refs)
                :let [cte? (boolean (and written (ctes written)))]]
            [alias {:table table :cte? cte?
                    :opaque? (boolean (or (nil? table) cte? (nil? (table-columns registry table))))}]))))

(defn- split-column
  "[alias column] of a column keyword: :a/b and :a.b name a table, :b does not."
  [col]
  (let [s (if (namespace col) (str (namespace col) "." (name col)) (name col))]
    (if (str/includes? s ".") (str/split s #"\." 2) [nil s])))

(defn- column-hits
  "[[alias table opaque?] ...] of the tables in scope a column may belong to."
  [registry scope col]
  (let [[alias column] (split-column col)]
    (if alias
      (when-let [{:keys [table opaque?]} (get scope alias)]
        (when (or opaque? (contains? (table-columns registry table) column)) [[alias table opaque?]]))
      (distinct (for [[a {:keys [table opaque?]}] scope
                      :when (or opaque? (contains? (table-columns registry table) column))]
                  [a table opaque?])))))

(defn resolve-column
  "{:table :column :schema} for a column keyword in a scope, :table the alias for an opaque
   table and :schema nil there; nil when the column resolves to no table or to several."
  [registry scope col]
  (let [hits (column-hits registry scope col)]
    (when (= 1 (count hits))
      (let [[alias table opaque?] (first hits)
            column (second (split-column col))]
        {:table (or table alias) :column column :schema (when-not opaque? (get (table-columns registry table) column))}))))

;;; what a statement selects, compares and assigns

(defn- selected-items [stmt]
  (let [s (or (:select stmt) (:select-distinct stmt) (:returning stmt))] (when (vector? s) s)))

(defn- select-parts
  "[column alias] of a select item: a column, a column under an alias, or an aliased
   expression, which has no column."
  [item]
  (cond
    (keyword? item) [item nil]
    (not (vector? item)) nil
    (and (keyword? (first item)) (keyword? (second item))) [(first item) (second item)]
    (keyword? (first item)) [(first item) nil]
    (keyword? (second item)) [nil (second item)]))

(defn- comparisons
  "[op column value] of the comparisons in a statement's conditions."
  [stmt]
  (for [x (own-nodes (select-keys stmt (into [:where :having :on-conflict] join-keys)))
        :when (and (vector? x) (keyword? (first x)) (keyword? (second x)))
        :let [[op col value] x]
        :when (#{:= :<> :< :> :<= :>= :in :like :ilike} op)]
    [op col value]))

(defn- assignments
  "[column value] pairs a statement assigns: :set, and the maps of :values (which may be a
   symbol, rows passed in, saying nothing about the columns)."
  [stmt]
  (concat (when (map? (:set stmt)) (:set stmt))
          (when (vector? (:values stmt)) (mapcat identity (filter map? (:values stmt))))))

;;; problems

(defn- enum-values [registry schema]
  (let [s (rt/non-null schema)
        s (if (and (vector? s) (= :ref (first s))) (get registry (last s)) s)]
    (when (and (vector? s) (= :enum (first s))) (set (remove map? (rest s))))))

(defn- column-problems [registry sc stmt]
  (for [col (distinct (concat (keep (comp first select-parts) (selected-items stmt))
                              (map second (comparisons stmt))
                              (map first (assignments stmt))))
        :when (and (not= :* col) (nil? (resolve-column registry sc col)))]
    {:kind (if (< 1 (count (column-hits registry sc col))) :ambiguous-column :unknown-column) :column col}))

(defn- insert-problems [registry stmt schema]
  (when-let [[table] (some-> (insert-target stmt) (table-ref schema))]
    (let [columns (table-columns registry table)
          target (insert-target stmt)
          ;; INSERT INTO t (a b) ... : [:t [:a :b]], or [[:t [:a :b]] {:select ...}] with a SELECT
          listed (when (vector? target) (second target))
          values (:values stmt)
          ;; HoneySQL inserts the union of the columns of all value maps, NULL where a row lacks one
          given (or listed (when (and (vector? values) (map? (first values))) (distinct (mapcat keys (filter map? values)))))
          given-names (set (map name given))
          required (set (for [[k p] (some->> (table-key table) rt/insert-name (get registry) rt/column-entries)
                              :when (not (:optional p))]
                          (name k)))]
      (concat
       ;; the columns of :values are checked with the other assignments
       (for [c listed :when (and columns (not (contains? columns (name c))))]
         {:kind :unknown-column :table table :column c})
       (when (seq given)
         (for [c required :when (not (given-names c))]
           {:kind :missing-required-column :table table :column c}))))))

(defn- enum-problems [registry sc stmt]
  (for [[col value] (concat (for [[op col value] (comparisons stmt) :when (#{:= :<> :in} op)] [col value])
                            (assignments stmt))
        :let [{s :schema} (resolve-column registry sc col)
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
   scope); required INSERT columns missing; enum literals outside the enum (compared with,
   or assigned to, the column). Empty when the statement agrees with the registry."
  [registry stmt ctes {:keys [schema] :or {schema "public"} :as opts}]
  (let [sc (scope registry stmt ctes opts)]
    (vec (concat (for [[_ {:keys [table cte?]}] sc
                       :when (and table (not cte?) (nil? (table-columns registry table)))]
                   {:kind :unknown-table :table table})
                 (column-problems registry sc stmt)
                 (insert-problems registry stmt schema)
                 (enum-problems registry sc stmt)))))

(defn check
  "The problems of every statement of a query. A query whose :with is built elsewhere (not
   data) cannot have its unknown tables judged, so those are left out for it."
  ([registry body] (check registry body {}))
  ([registry body opts]
   (let [ctes (cte-names body)
         opaque? (some (fn [stmt] (some #(let [w (get stmt %)] (and (some? w) (not (vector? w)))) [:with :with-recursive])) (statements body))
         found (mapcat #(problems registry % ctes opts) (statements body))]
     (vec (if opaque? (remove #(= :unknown-table (:kind %)) found) found)))))

;;; types

(defn- read-shape
  "A column schema as a driver returns it and as malli's default registry reads it; an
   interval is a driver object, :any."
  [registry schema {:keys [time] :or {time :inst}}]
  (let [t (if (vector? schema) (first schema) schema)]
    (rt/portable-data registry (rt/read-time time (if (= :time/duration t) :any schema)))))

(defn- column-type
  "The type of a column for a value compared with it (NULL never matches, so no [:maybe]) or,
   with nullable?, assigned to it."
  [registry sc col nullable? opts]
  (if-let [s (:schema (resolve-column registry sc col))]
    (let [inner (read-shape registry (rt/non-null s) opts)]
      (if (and nullable? (= :maybe (first s))) [:maybe inner] inner))
    :any))

(defn arg-types
  "{symbol schema} for the symbols of a query: a symbol compared with a column has the
   column's type (:in a :sequential of it), one assigned to it the column's type with NULL,
   one in :limit / :offset :int. The first typed use wins; a use that gives no type (:any)
   never hides one that does."
  ([registry body] (arg-types registry body {}))
  ([registry body opts]
   (let [ctes (cte-names body)
         typed (for [stmt (statements body)
                     :let [sc (scope registry stmt ctes opts)]
                     pair (concat (for [[op col value] (comparisons stmt)
                                        :let [t (column-type registry sc col false opts)]]
                                    [value (if (and (= :in op) (not= :any t)) [:sequential t] t)])
                                  (for [[_ v] (select-keys stmt [:limit :offset])] [v :int])
                                  (for [[col v] (assignments stmt)] [v (column-type registry sc col true opts)]))]
                 pair)]
     (reduce (fn [acc [sym t]] (if (and (symbol? sym) (contains? #{nil :any} (acc sym))) (assoc acc sym t) acc)) {} typed))))

(defn row-schema
  "The [:map ...] of one row a statement returns, as the driver builds it (as-read's
   :qualified?, :kebab?, :nil-columns and :time; an opaque source's columns under its alias)
   and as malli's default registry reads it; an expression under an alias is nullable :any.
   nil when a selected column cannot be resolved."
  ([registry stmt ctes] (row-schema registry stmt ctes {}))
  ([registry stmt ctes {:keys [qualified? kebab? nil-columns] :as opts}]
   (let [sc (scope registry stmt ctes opts)
         kebab (fn [s] (cond-> s kebab? (str/replace "_" "-")))
         key-of (fn [table column] (if qualified? (keyword (kebab (last (str/split table #"\."))) (kebab column)) (keyword (kebab column))))
         entry (fn [k schema]
                 (let [nullable? (or (nil? schema) (= :maybe (first schema)))
                       inner (if schema (read-shape registry (rt/non-null schema) opts) :any)]
                   (cond (not nullable?) [k inner]
                         (= :absent nil-columns) [k {:optional true} inner]
                         :else [k [:maybe inner]])))
         entries (for [item (selected-items stmt)
                       :let [[col alias] (select-parts item)]]
                   (if col
                     (when-let [{:keys [table column schema]} (resolve-column registry sc col)]
                       ;; a column under an alias is still keyed by its table: the driver reads the table from the field
                       (entry (key-of table (if alias (name alias) column)) schema))
                     (when alias (entry alias nil))))]
     (when (and (seq entries) (every? some? entries))
       (into [:map] entries)))))

(defn query-schema
  "[:=> [:cat arg-types...] [:sequential row]] for a function taking args and running body: a
   malli function schema for instrumentation. A return that cannot be resolved is :any."
  ([registry args body] (query-schema registry args body {}))
  ([registry args body opts]
   (let [types (arg-types registry body opts)
         row (when (map? body) (row-schema registry body (cte-names body) opts))]
     [:=> (into [:cat] (map #(get types % :any)) args) (if row [:sequential row] :any)])))
