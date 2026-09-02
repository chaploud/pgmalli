(ns pgmalli.honeysql
  "HoneySQL query data checked against a registry, without a database: the tables and
   columns a query touches must exist, an INSERT must carry the required columns, an enum
   literal must be one of the enum's values; and from the same data, the types of the
   query's parameters and of the rows it returns, as malli schemas.

   Scope: tables come from :from, the joins, :insert-into, :update and :delete-from, under
   their aliases. CTEs (:with), subqueries and table functions are opaque tables: their
   columns exist but have no type. A column is :col (unique in the scope), :alias/col or
   :alias.col. An unqualified table is in :schema (default \"public\")."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
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
    (into {} (for [e (rest (#'rt/row-map row)) :when (vector? e)
                   :let [[k _ s] (#'rt/entry-parts e)]]
               [(name k) s]))))

;;; statements and scope

(def ^:private statement-keys #{:select :select-distinct :insert-into :update :delete-from})

(defn statements
  "The statements in a query (a map, or Clojure data holding maps); a subquery is one too."
  [body]
  (let [acc (atom [])]
    (walk/prewalk (fn [x] (when (and (map? x) (some statement-keys (keys x))) (swap! acc conj x)) x) body)
    @acc))

(defn cte-names
  "Names of the CTEs a query defines, at any depth."
  [body]
  (let [acc (atom #{})]
    (walk/prewalk (fn [x]
                    (when (map? x)
                      (doseq [k [:with :with-recursive] :let [w (get x k)] :when (vector? w)
                              entry w :when (vector? entry)
                              :let [n (if (vector? (first entry)) (ffirst entry) (first entry))]
                              :when (keyword? n)]
                        (swap! acc conj (name n))))
                    x)
                  body)
    @acc))

(defn- qualified [k schema] (if (namespace k) (str (namespace k) "." (name k)) (str schema "." (name k))))

(defn- table-ref
  "[table alias] of a :from / join item; an opaque item (subquery, function) has no table."
  [x schema]
  (cond
    (keyword? x) [(qualified x schema) (name x)]
    (and (vector? x) (keyword? (first x)) (or (nil? (second x)) (keyword? (second x)))) [(qualified (first x) schema) (name (or (second x) (first x)))]
    (and (vector? x) (keyword? (first x))) [nil (name (first x))]
    (and (vector? x) (keyword? (second x))) [nil (name (second x))]
    (and (vector? x) (vector? (first x))) (table-ref (first x) schema)
    :else nil))

(defn scope
  "{alias {:table \"schema.table\" :opaque? bool}} of one statement; ctes are the query's CTE names."
  [registry stmt ctes {:keys [schema] :or {schema "public"}}]
  (let [from (let [f (:from stmt)] (if (keyword? f) [f] f))
        joins (for [k [:join :left-join :inner-join :right-join :full-join :cross-join]
                    :let [j (get stmt k)] :when (vector? j)
                    [t _] (partition 2 j)]
                t)
        targets [(let [i (:insert-into stmt)] (if (vector? i) (first i) i)) (:update stmt) (:delete-from stmt)]]
    (into {}
          (for [[table alias] (keep #(table-ref % schema) (concat from joins targets))]
            [alias {:table table
                    :opaque? (boolean (or (nil? table) (ctes (name (last (str/split table #"\.")))) (nil? (table-columns registry table))))}]))))

(defn resolve-column
  "{:table :column :schema} for a column keyword in a scope; :schema nil on an opaque table;
   nil when the column resolves to no table or to several."
  [registry scope col]
  (let [s (if (namespace col) (str (namespace col) "." (name col)) (name col))
        [alias column] (if (str/includes? s ".") (str/split s #"\." 2) [nil s])]
    (if alias
      (when-let [{:keys [table opaque?]} (get scope alias)]
        {:table (or table alias) :column column :schema (when-not opaque? (get (table-columns registry table) column))})
      (let [hits (distinct (for [[_ {:keys [table opaque?]}] scope
                                 :when (or opaque? (contains? (table-columns registry table) column))]
                             [table opaque?]))]
        (when (= 1 (count hits))
          (let [[table opaque?] (first hits)]
            {:table (or table column) :column column :schema (when-not opaque? (get (table-columns registry table) column))}))))))

;;; problems

(defn- enum-values [registry schema]
  (let [s (rt/non-null schema)
        s (if (and (vector? s) (= :ref (first s))) (get registry (last s)) s)]
    (when (and (vector? s) (= :enum (first s))) (set (remove map? (rest s))))))

(defn- insert-problems [registry stmt schema]
  (when-let [i (:insert-into stmt)]
    (let [[table] (table-ref (if (vector? i) (first i) i) schema)
          columns (table-columns registry table)
          values (:values stmt)
          given (cond (and (vector? i) (vector? (second i))) (second i)
                      (and (vector? values) (map? (first values))) (keys (first values)))
          insert (when columns (get registry (#'rt/insert-name (table-key table))))
          required (when insert
                     (set (for [e (rest (#'rt/row-map insert)) :when (vector? e)
                                :let [[k p _] (#'rt/entry-parts e)] :when (not (:optional p))]
                            (name k))))]
      (concat
       (for [c given :when (and columns (not (contains? columns (name c))))]
         {:kind :unknown-column :table table :column c})
       (when (seq given)
         (for [c required :when (not (contains? (set (map name given)) c))]
           {:kind :missing-required-column :table table :column c}))))))

(defn problems
  "The problems of one statement: unknown tables and columns, required INSERT columns
   missing, enum literals outside the enum. Empty when the statement agrees with the registry."
  [registry stmt ctes {:keys [schema] :or {schema "public"} :as opts}]
  (let [sc (scope registry stmt ctes opts)
        unknown-tables (for [[_ {:keys [table]}] sc
                             :when (and table (nil? (table-columns registry table)) (not (ctes (last (str/split table #"\.")))))]
                         {:kind :unknown-table :table table})
        selected (let [s (or (:select stmt) (:select-distinct stmt) (:returning stmt))] (when (vector? s) s))
        select-items (for [item selected
                           :let [col (cond (keyword? item) item
                                           (and (vector? item) (keyword? (first item))) (first item))]
                           :when (and col (not= :* col) (nil? (resolve-column registry sc col)))]
                       {:kind :unknown-column :column col})
        literals (for [[op col value] (filter #(and (vector? %) (keyword? (first %))) (tree-seq coll? seq (select-keys stmt [:where :having :set :values])))
                       :when (and (#{:= :<> :in} op) (keyword? col))
                       :let [{s :schema} (resolve-column registry sc col)
                             values (cond (string? value) [value]
                                          (and (vector? value) (= :cast (first value)) (string? (second value))) [(second value)]
                                          (and (= :in op) (vector? value) (every? string? value)) value)
                             allowed (when s (enum-values registry s))]
                       :when (and allowed (seq values))
                       v values :when (not (contains? allowed v))]
                   {:kind :enum-literal :column col :value v :allowed allowed})]
    (vec (concat unknown-tables select-items (insert-problems registry stmt schema) literals))))

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
  "A column schema as a driver returns it and as malli's default registry reads it."
  [registry schema {:keys [time]}]
  (rt/portable-data registry (walk/postwalk (fn [f] (case [time f] [:instant :time/local-date-time] :time/instant [:local :time/instant] :time/local-date-time f)) schema)))

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
   column's type, one assigned to it the column's type with NULL, one in :limit / :offset :int."
  ([registry body] (arg-types registry body {}))
  ([registry body opts]
   (let [acc (atom {})
         note! (fn [sym t] (when (symbol? sym) (swap! acc update sym #(or % t))))
         ctes (cte-names body)]
     (doseq [stmt (statements body)
             :let [sc (scope registry stmt ctes opts)]]
       (walk/prewalk
        (fn [x]
          (when (and (vector? x) (keyword? (first x)))
            (let [[op a b] x]
              (cond
                (and (#{:= :<> :< :> :<= :>= :like :ilike} op) (keyword? a)) (note! b (column-type registry sc a false opts))
                (and (= :in op) (keyword? a)) (note! b [:sequential (column-type registry sc a false opts)])
                (and (= :cast op) (symbol? a)) (note! a :any))))
          x)
        (select-keys stmt [:where :having :on-conflict :join :left-join :inner-join]))
       (doseq [[_ v] (select-keys stmt [:limit :offset])] (note! v :int))
       (doseq [row (when (vector? (:values stmt)) (:values stmt)) :when (map? row), [col v] row]
         (note! v (column-type registry sc col true opts)))
       (doseq [[col v] (when (map? (:set stmt)) (:set stmt))]
         (note! v (column-type registry sc col true opts))))
     @acc)))

(defn row-schema
  "The [:map ...] of one row a statement returns, as the driver builds it (as-read's
   :qualified?, :nil-columns and :time) and as malli's default registry reads it; expressions
   under an alias are :any. nil when a selected column cannot be resolved."
  ([registry stmt ctes] (row-schema registry stmt ctes {}))
  ([registry stmt ctes {:keys [qualified? nil-columns] :as opts}]
   (let [sc (scope registry stmt ctes opts)
         selected (let [s (or (:select stmt) (:select-distinct stmt) (:returning stmt))] (when (vector? s) s))
         key-of (fn [table column] (if qualified? (keyword (last (str/split table #"\.")) column) (keyword column)))
         entry (fn [k schema]
                 (let [nullable? (or (nil? schema) (= :maybe (first schema)))
                       inner (if schema (read-shape registry (rt/non-null schema) opts) :any)]
                   (cond (not nullable?) [k inner]
                         (= :absent nil-columns) [k {:optional true} inner]
                         :else [k [:maybe inner]])))
         entries (for [item selected]
                   (cond
                     (keyword? item) (when-let [{:keys [table column schema]} (resolve-column registry sc item)] (entry (key-of table column) schema))
                     (and (vector? item) (keyword? (first item)) (keyword? (second item)))
                     (when-let [{:keys [schema]} (resolve-column registry sc (first item))] (entry (second item) schema))
                     (and (vector? item) (keyword? (second item))) (entry (second item) nil)))]
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
