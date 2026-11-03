(ns pgmalli.honeysql
  "HoneySQL query data checked against a registry, without a database: the tables must
   exist, the columns a query selects, returns, inserts, sets or compares must exist, an
   INSERT must carry the required columns, an enum literal must be one of the enum's values;
   and from the same data, the types of the query's parameters and of the rows it returns,
   and of a row written into a table, as malli schemas.

   Scope: tables come from :from, :using, :cross-join, the joins (:join-by is not read),
   :insert-into, :update, :delete-from and :truncate, under their aliases. CTEs (:with) and
   subqueries (LATERAL ones and recursive CTEs too) have the columns their SELECT gives, typed as
   it types them; a table function, a symbol where a table goes and a pg_catalog or
   information_schema table the registry lacks are opaque tables: their columns exist but have
   no type. Every table has the system columns
   (ctid, xmin, ...). A column is :col (unique in the scope), :alias/col or :alias.col; a
   column a subquery cannot resolve is looked for in the enclosing statements, as PostgreSQL
   does. An unqualified table is in :schema (default \"public\"). What code in the query
   builds (a list) is not in the data and is not judged.

   Types are what next.jdbc and PostgreSQL's JDBC driver return, and what :send says the
   application sends (pgjdbc's by default). The functions take
   the options of docs/queries.md#options; a key or value not among them is an error."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            malli.core
            [pgmalli.impl.accept :as accept]
            [pgmalli.impl.error :as error]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.portable :as portable]
            [pgmalli.impl.shape :as shape]
            [pgmalli.impl.types :as types]))

;;; tables and columns of the registry

(defn- folded
  "An identifier as PostgreSQL reads what HoneySQL writes of it by default, unquoted: - is _,
   and the case folded. Only where the name as it is matches nothing (a quoted mixed-case one
   matches as it is)."
  [s]
  (some-> s (str/replace "-" "_") str/lower-case))

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
    (let [r {::schemas (shape/schemas-of registry) ::derived (atom {})}]
      (assoc r ::table-columns (memoize #(some->> (or (column-entries r %) (column-entries r (folded %))) (into system-columns)))))))

(defn- table-columns [registry table] ((::table-columns registry) table))

;;; statements, CTEs and scope

(def ^:private set-operation-keys [:union :union-all :intersect :except :except-all])
(def ^:private with-keys [:with :with-recursive])
(def ^:private write-keys [:insert-into :update :delete-from :truncate])
(def ^:private statement-keys
  (into #{:select :select-distinct :select-distinct-on :select-top :select-distinct-top}
        (concat write-keys set-operation-keys)))
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

(defn- table-items
  "The table items of a statement's FROM (and USING, CROSS JOIN) and joins."
  [stmt]
  (concat (from-items stmt from-keys) (map second (join-items stmt))))

(defn- join-items*
  "[item condition] of every join of a statement."
  [stmt]
  (for [k join-keys :let [j (get stmt k)] :when (vector? j), pair (partition 2 j)] (vec pair)))

(declare insert-parts star? expression-type type-schema accepted-type driver-type quoted?)

(defn- default-values
  "How HoneySQL writes DEFAULT under sql/format's opts, the value a column's default fills:
   :default and [:default], or [:default] alone where the format quotes names (\"default\")."
  [format]
  (if (quoted? format) #{[:default]} #{:default [:default]}))

(defn- code-sources?
  "Whether code (a list), or :join-by (read nowhere here), brings in some of a statement's
   tables, whose columns the data then does not show."
  [stmt]
  (boolean (or (contains? stmt :join-by)
               (some seq? (concat (keep stmt (concat from-keys join-keys write-keys))
                                  (from-items stmt from-keys)
                                  (map second (join-items stmt))))
               ;; an :insert-into target insert-parts finds no table in
               (and (contains? stmt :insert-into) (nil? (insert-parts stmt))))))

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
      (cond (keyword? n) (folded (name n))
            ;; HoneySQL quotes a string name: it is as written
            (string? n) n))))

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

(declare code-ctes?)

(defn- code-ctes
  "#{::code-ctes} when code (not data) gives a statement of the query some of its CTEs: any
   unqualified name may then be one of them, so none is an unknown table."
  [body]
  (when (some code-ctes? (statements body)) #{::code-ctes}))

(defn- query-ctes
  "The CTE names a statement of the query may see, at any depth, code's included."
  [body]
  (into (cte-names body) (code-ctes body)))

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
  (when-not (or (namespace k) (str/includes? (name k) ".")) (folded (name k))))

(defn- alias? [x] (or (keyword? x) (string? x)))

(defn- table-ref
  "[table alias written columns] of a :from / join / target item: the table's qualified name,
   its alias, the name as written (nil when qualified by a schema), and the columns an alias
   lists ([:v {:columns [:id :n]}] over a VALUES); an opaque item (subquery, function, a symbol
   naming a table chosen at run time) has no table."
  [x schema]
  (cond
    ;; FROM public.users is referred to as users
    (keyword? x) [(qualified x schema) (folded (last (str/split (name x) #"\."))) (written x)]
    (symbol? x) [nil (str x) nil]
    ;; a subquery with no alias (PostgreSQL 16): nothing names it, so no query reaches it by name
    (map? x) [nil (str "(subquery " (hash x) ")") nil]
    (vector? x)
    (let [[a b] x
          ;; [:alias {:columns [...]}]; a column definition list ([:alias [:raw "(a int)"]]) names
          ;; no column read here
          [b listed] (if (and (vector? b) (alias? (first b)) (or (map? (second b)) (vector? (second b))))
                       [(first b) (let [cs (:columns (second b))] (when (and (sequential? cs) (every? #(or (keyword? %) (string? %)) cs)) (mapv name cs)))]
                       [b nil])
          ;; HoneySQL quotes a string alias: it is as written
          alias-name #(if (string? %) % (folded (name %)))]
      (cond
        ;; :t / [:t :alias] / INSERT INTO t (columns...)
        (and (keyword? a) (or (nil? b) (alias? b) (vector? b))) [(qualified a schema) (if (alias? b) (alias-name b) (folded (last (str/split (name a) #"\.")))) (written a) listed]
        (keyword? a) [nil (folded (name a)) nil listed]
        ;; [[:unnest ...] [:t {:columns [:x]}]]: t.f reads as f(t), the function on a scalar result,
        ;; so a function's alias is not held to the columns it lists
        (alias? b) [nil (alias-name b) nil (when-not (vector? a) listed)]
        ;; [[:with-ordinality [:f args...] alias]]: f WITH ORDINALITY alias, named after the function
        ;; without one
        (and (nil? b) (vector? a) (= :with-ordinality (first a)))
        (let [[_ f al] a]
          [nil (cond (alias? al) (alias-name al)
                     (and (vector? f) (keyword? (first f))) (folded (last (str/split (name (first f)) #"\.")))
                     :else "with_ordinality")
           nil])
        ;; [[:f args...]]: a table function, named after the function
        (and (nil? b) (vector? a) (keyword? (first a))) [nil (folded (last (str/split (name (first a)) #"\."))) nil]
        ;; [subquery]: one with no alias
        (and (nil? b) (map? a)) (table-ref a schema)))))

(defn- insert-target
  "The target of :insert-into as a :from item: the table, under its alias when it has one."
  [stmt]
  (let [{:keys [table alias]} (insert-parts stmt)]
    (if (and table alias) [table alias] table)))

(defn- write-targets
  "The tables a statement writes, as :from items: INSERT INTO's, UPDATE's, DELETE FROM's and
   TRUNCATE's (:t, [:t options...] or [[:t ...] options...])."
  [stmt]
  (let [t (:truncate stmt)
        t (if (vector? t) (first t) t)]
    (remove nil? (concat [(insert-target stmt) (:update stmt) (:delete-from stmt)]
                         (cond (vector? t) t (some? t) [t])))))

(defn- catalog-table
  "The table an unqualified name means: pg_catalog, implicitly first on the search path, holds
   the pg_ relations; one the registry lacks is \"pg_catalog.<name>\" all the same, opaque."
  [registry table written]
  (if-let [catalog (when (and written (str/starts-with? written "pg_")) (str "pg_catalog." written))]
    (cond (table-columns registry catalog) catalog
          (table-columns registry table) table
          :else catalog)
    table))

(defn- catalog? [table] (boolean (re-find #"^(pg_catalog|information_schema)\." table)))

(defn- outer-aliases
  "The aliases an outer join may leave NULL: a LEFT JOIN's table, every table before a RIGHT
   JOIN's, both sides of a FULL (and :outer-join) one."
  [stmt schema]
  (let [alias #(second (table-ref % schema))
        ;; the joins attach to the last FROM item: a comma binds looser than JOIN
        from (take-last 1 (map alias (from-items stmt [:from :using])))]
    (:nullable
     (reduce (fn [{:keys [before] :as acc} [k t]]
               (let [a (when t (alias t))]
                 (-> (case k
                       :left-join (update acc :nullable conj a)
                       :right-join (update acc :nullable into before)
                       (:outer-join :full-join) (update acc :nullable into (cond-> before a (conj a)))
                       acc)
                     (update :before conj a))))
             {:before (vec from) :nullable #{}}
             ;; a RIGHT or FULL JOIN code builds nulls the tables before it all the same
             (concat (join-items stmt)
                     (for [k [:right-join :full-join :outer-join] :when (seq? (get stmt k))] [k nil]))))))

(defn- scope
  "{alias {:table \"schema.table\" :opaque? bool :cte? bool :nullable? bool}} of one statement;
   ctes are the query's CTE names, which shadow a table only when the reference is written
   without a schema. :nullable? marks a table an outer join may leave NULL."
  [registry stmt ctes {:keys [schema] :or {schema default-schema}}]
  (let [registry (reading registry)
        refs (concat (from-items stmt from-keys)
                     (map second (join-items stmt))
                     (write-targets stmt))
        nullable (outer-aliases stmt schema)
        ;; UPDATE, DELETE FROM and INSERT INTO name a table, which a CTE of the same name does not hide
        targets (set (write-targets stmt))
        using (set (for [[_ j] (join-items* stmt) :when (and (vector? j) (= :using (first j))) c (rest j) :when (keyword? c)]
                     (folded (name c))))
        sc (into (with-meta {} {::using using})
                 (for [r refs :let [[table alias written listed] (table-ref r schema)] :when alias
                       :let [cte? (boolean (and written (not (targets r)) (or (ctes written) (ctes ::code-ctes))))
                             table (when table (catalog-table registry table written))
                             ;; an unquoted :USERS is PostgreSQL's users: the registry's name, which the driver returns
                             table (if (and table (nil? (column-entries registry table)) (column-entries registry (folded table))) (folded table) table)]]
                   [alias (cond-> {:table table :cte? cte?
                                   :opaque? (boolean (or (nil? table) cte? (nil? (table-columns registry table))))
                                   ;; named, but the registry lacks it (pg_catalog's aside): no row can be told of it
                                   :unknown? (boolean (and table (not cte?) (not (catalog? table)) (nil? (table-columns registry table))))}
                            ;; the columns an alias lists rename the first ones, typeless; of a VALUES as
                            ;; wide as the list, they are all it has
                            listed (assoc :columns listed
                                          :all-listed? (let [v (when (and (vector? r) (map? (first r))) (:values (first r)))]
                                                         (boolean (and (vector? v) (seq v) (every? vector? v) (<= (count (first v)) (count listed))))))
                            (nullable alias) (assoc :nullable? true))]))]
    ;; ON CONFLICT: EXCLUDED is the row proposed for insertion, with the target's columns
    (if-let [[_ alias] (when (contains? stmt :on-conflict) (some-> (insert-target stmt) (table-ref schema)))]
      (let [target (assoc (get sc alias) :excluded? true)] (assoc sc "excluded" target))
      sc)))

(defn- lateral?
  "Whether a FROM or join item sees the items before it: LATERAL, or a function call, which is
   implicitly LATERAL."
  [item]
  (and (vector? item) (or (= :lateral (first item)) (and (vector? (first item)) (keyword? (ffirst item))))))

(declare row-schema* derived-types derived-types* nullable honeysql beyond-list)

(defn- cte-statement
  "[statement columns recursive?] of CTE name: its body as read on its own, under the entries of
   its :with before it, and the columns its name lists ([[:c {:columns [:x]}] ...]); of a
   recursive one (a set operation under :with-recursive), its first member, whose types
   PostgreSQL gives the columns. nil where the query defines name more than once, or the body is
   no statement."
  [body name]
  (let [found (for [x (tree-seq branch? seq body) :when (map? x)
                    k with-keys :let [w (get x k)] :when (vector? w)
                    [i e] (map-indexed vector w) :when (= name (cte-name e))]
                [(second e) (subvec w 0 i) (when (vector? (first e)) (:columns (second (first e)))) k])]
    (when-let [[[stmt before listed k]] (when (= 1 (count found)) found)]
      (let [op (when (and (= :with-recursive k) (map? stmt)) (some #(when (vector? (get stmt %)) %) set-operation-keys))
            ;; recursive when a member reads the CTE itself; otherwise its members resolve together
            self? (some (fn [m] (some #(let [w (nth (table-ref % default-schema) 2)] (and w (= name (folded w))))
                                      (mapcat table-items (statements m))))
                        (when op (get stmt op)))
            op (when self? op)
            stmt (if op (first (get stmt op)) stmt)]
        (when (and (map? stmt) (not-any? #(contains? stmt %) with-keys) (or (nil? listed) (sequential? listed)))
          [(cond-> stmt (seq before) (assoc :with before)) (map #(folded (clojure.core/name %)) listed) (boolean op)])))))

(defn- typed-scope
  "sc with :types on the subqueries and CTEs of stmt whose SELECT types its columns
   ({column schema}, a column whose type is not told nil), read as a query of their own; not
   on one that reads a CTE ctes holds (one stmt sees but the query of its own would not)."
  [registry body stmt sc ctes outer {:keys [schema] :or {schema default-schema} :as opts}]
  (let [items (table-items stmt)
        ;; read on its own, a name a CTE seen here holds would be read as a table
        own? (fn [inner]
               (let [defined (cte-names inner)]
                 (not-any? #(let [[_ _ written] (table-ref % schema)]
                              (and written (not (defined (folded written))) (or (ctes (folded written)) (ctes ::code-ctes))))
                           (mapcat table-items (statements inner)))))]
    (reduce (fn [sc item]
              (let [[_ alias written] (table-ref item schema)
                    {:keys [table cte? columns] :as entry} (get sc alias)
                    lateral (lateral? item)
                    [inner listed recursive?] (cond (or (nil? entry) columns) nil
                                                    cte? (cte-statement body written)
                                                    (and (nil? table) (vector? item) (map? (first item))) [(first item)]
                                                    ;; [[:lateral subquery] alias]
                                                    (and (nil? table) (vector? item) (vector? (first item)) (= :lateral (ffirst item)) (map? (second (first item))))
                                                    [(second (first item))]
                                                    (and (nil? table) (map? item)) [item])
                    ts (when (and inner (own? inner))
                         (if lateral
                           ;; a LATERAL subquery sees the tables before it
                           (derived-types* registry inner opts (cons (dissoc sc alias) outer))
                           (derived-types registry inner opts)))
                    types (when ts
                            ;; a CTE's name may list new names for its columns, by position; a recursive
                            ;; one's later rows may hold NULL where its first member's do not
                            (into {} (map-indexed (fn [i [c t]] [(nth (vec listed) i c) (cond->> t (and t recursive?) (beyond-list registry) (and t recursive?) nullable)])) ts))]
                (cond-> sc types (assoc-in [alias :types] types))))
            sc items)))

(def ^:private clause-rank
  (delay (let [order (some-> (honeysql 'honey.sql/clause-order) (apply []))]
           (zipmap order (range)))))

(defn- clause-entries
  "The entries of statement x in the order sql/format writes its clauses (others after, by
   name): what comes first in the SQL comes first here, whatever order the map holds."
  [x]
  (sort-by (fn [[k]] [(get @clause-rank k Long/MAX_VALUE) (str k)]) x))

(defn- statement-chains
  "[[statement scopes path] ...] for the statements of a query, scopes the statement's own scope
   followed by those of the statements enclosing it, path where it sits in the query. What a
   part of a statement sees is decided here alone: its conditions, select and assignments its
   own tables and the enclosing ones; a table item of :from or a join (a subquery), the body of
   a CTE and the rows of :values only the enclosing ones (LATERAL its own too); a :with entry
   the entries before it, a :with-recursive one all of them. A statement found inside a vector
   of a code form, not under a statement (a subquery a helper adds to a query built elsewhere,
   or a CTE body bound apart from its :with), has an enclosing statement this data does not
   show: its chain ends in :open, a column it cannot resolve is taken to be the enclosing
   statement's, and every CTE the query defines anywhere is visible to it. Under outer, body is
   read inside the chain of scopes outer (a LATERAL subquery's)."
  ([registry body opts] (statement-chains registry body opts ()))
  ([registry body opts outer]
  (letfn [(walk [x path outer ctes in-vector? under?]
            (cond (statement? x)
                  (let [open? (and (empty? outer) in-vector? (not under?))
                        base (if open? (query-ctes body) ctes)
                        ctes (into base (keep cte-name) (cte-entries x))
                        outer (if open? (list :open) outer)
                        ;; a column of a table code brings in is the enclosing data's, untyped
                        chain (cons (typed-scope registry body x (scope registry x ctes opts) ctes outer opts) (cond->> outer (code-sources? x) (cons :open)))
                        part (fn [k v scopes cs] (walk v (conj path k) scopes cs false true))
                        ;; a LATERAL item sees its statement's tables, but not itself
                        lateral-chain (fn [t] (cons (dissoc (first chain) (second (table-ref t (:schema opts default-schema)))) (rest chain)))
                        items (fn [k v]
                                (if (vector? v)
                                  (apply concat (map-indexed (fn [i t] (walk t (conj path k i) (if (lateral? t) (lateral-chain t) outer) ctes false true)) v))
                                  (part k v outer ctes)))]
                    (cons [x chain path]
                          (concat
                           (for [k with-keys :let [w (get x k)] :when (some? w)
                                 found (if (vector? w)
                                         (apply concat (map-indexed (fn [i e] (walk e (conj path k i) outer (into base (keep cte-name) (if (= :with-recursive k) w (take i w))) false true)) w))
                                         (part k w outer ctes))]
                             found)
                           ;; INSERT ... SELECT, and the rows of :values, do not see the table inserted into
                           (mapcat #(part % (get x %) outer ctes) (filter #(contains? x %) [:insert-into :values]))
                           (mapcat #(items % (get x %)) (filter #(contains? x %) from-keys))
                           (for [k join-keys :let [j (get x k)] :when (some? j)
                                 found (if (vector? j)
                                         (apply concat (map-indexed (fn [i t] (if (even? i)
                                                                                (walk t (conj path k i) (if (lateral? t) (lateral-chain t) outer) ctes false true)
                                                                                (walk t (conj path k i) chain ctes false true)))
                                                                    j))
                                         (part k j outer ctes))]
                             found)
                           (mapcat (fn [[k v]] (part k v chain ctes))
                                   (clause-entries (apply dissoc x :insert-into :values (concat with-keys from-keys join-keys)))))))
                  (branch? x) (apply concat (map-indexed (fn [i c] (walk c (conj path i) outer ctes (or in-vector? (vector? x)) under?)) (seq x)))))]
    (walk body [] outer (into (free-cte-names body) (code-ctes body)) false false))))

(defn- split-column
  "[alias column] of a column keyword: :a/b and :a.b name a table (:schema.a.b too), :b does
   not. The alias is folded as table-ref folds aliases."
  [col]
  (let [s (if (namespace col) (str (namespace col) "." (name col)) (name col))
        ;; HoneySQL writes :'x as x, as it is
        s (str/replace-first s #"^'" "")
        parts (str/split s #"\.")]
    (if (next parts)
      [(folded (nth parts (- (count parts) 2))) (last parts)]
      [nil s])))

(defn- has-column?
  "Whether a scope entry may hold a column: one it lists, one its SELECT gives (a typed
   subquery or CTE), one of its table's, or anything for an opaque table. A list renames the
   first columns alone: the others keep their names."
  [registry {:keys [table opaque? columns types all-listed?] :as entry} column]
  (cond columns (or (boolean (some #(or (= column %) (= (folded column) %)) columns))
                    (and (not all-listed?) (has-column? registry (dissoc entry :columns) column)))
        types (or (contains? types column) (contains? types (folded column)))
        opaque? true
        :else (let [cs (table-columns registry table)] (or (contains? cs column) (contains? cs (folded column))))))

(defn- own-hits
  "[[alias table opaque? nullable? types] ...] of the tables of one scope a column may belong to."
  [registry scope col]
  (let [[alias column] (split-column col)]
    (if alias
      (when-let [{:keys [table opaque? nullable? types] :as entry} (get scope alias)]
        (when (has-column? registry entry column) [[alias table opaque? nullable? types]]))
      ;; an unqualified column is never EXCLUDED's: that row is only reached through its name
      (distinct (for [[a {:keys [table opaque? excluded? nullable? types] :as entry}] scope
                      :when (and (not excluded?) (has-column? registry entry column))]
                  [a table opaque? nullable? types])))))

(defn- chain [scope] (if (map? scope) [scope] scope))

(defn- column-hits
  "The hits of the innermost scope (a map, or the chain of a statement and its enclosing
   ones) that has any."
  [registry scopes col]
  (let [[sc & outer] (chain scopes)
        [alias column] (split-column col)
        hits (own-hits registry sc col)
        ;; JOIN ... USING (c) merges the c of the tables it joins into one column
        hits (if (and (next hits) (nil? alias) (contains? (::using (meta sc)) (folded column))) (take 1 hits) hits)]
    (cond (or (seq hits) (empty? outer)) hits
          ;; an alias names the nearest table of its name: an outer one of the same name is hidden
          (and alias (contains? sc alias)) hits
          ;; the enclosing statement is not in the data: a column not of this scope's tables is
          ;; one of its, untyped
          (= :open (first outer)) (if (and alias (contains? sc alias)) hits [[alias nil true]])
          :else (column-hits registry outer col))))

(defn- resolve-column
  "{:table :column :schema :nullable? :opaque?} for a column keyword in a scope (or the chain of
   scopes of a nested statement), :table the alias for an opaque table, :schema the type a
   subquery's or CTE's SELECT gives the column (nil for an opaque table without one),
   :nullable? when an outer join may leave the table NULL; nil when the column resolves to no
   table or to several known ones. A column an opaque table may hold besides one known table is
   the opaque one's, untyped: which of them PostgreSQL takes is not in the data."
  [registry scope col]
  (let [registry (reading registry)
        hits (column-hits registry scope col)
        column (second (split-column col))
        [opaque & _] (filter #(nth % 2) hits)]
    (cond (= 1 (count hits))
          (let [[alias table opaque? nullable? types] (first hits)
                ;; a subquery's or a CTE's columns, as its SELECT types them
                cs (or types (when-not opaque? (table-columns registry table)))
                ;; the column's own name, as next.jdbc keys the row: :group-id is group_id
                column (if (or (nil? cs) (contains? cs column)) column (folded column))]
            {:table (or table alias) :column column
             :schema (get cs column)
             :nullable? (boolean nullable?) :opaque? (boolean opaque?)})
          (and opaque (>= 1 (count (remove #(nth % 2) hits))))
          (let [[alias table] opaque] {:table (or table alias) :column column :schema nil :nullable? true :opaque? true}))))

;;; what a statement selects, compares and assigns

(def ^:private sql-values
  "Keywords HoneySQL writes as SQL words, not columns."
  #{:current_timestamp :current_date :current_time :localtime :localtimestamp :current_user :session_user :current_role
    :system_user :current_catalog :current_schema :user :true :false :null :default :*})

(defn- sql-value
  "The SQL word HoneySQL writes for keyword x, lower-cased (:null for :NULL), or nil for a
   column: it writes the name as spelt, and PostgreSQL reads NULL and Null as it reads null."
  [x]
  (when (and (keyword? x) (nil? (namespace x)))
    (sql-values (keyword (str/lower-case (name x))))))

(defn- param-key
  "The :params key of a HoneySQL named parameter, as sql/format looks it up: :?id and ?id are
   :id (:?a/b is :a/b), [:param k] is k. nil for anything else."
  [x]
  (if (and (vector? x) (= 2 (count x)) (= :param (first x)))
    (second x)
    ;; the jsonb and hstore operators ?, ?| and ?&
    (when (and (or (keyword? x) (symbol? x)) (not (#{:? :?| :?&} x)))
      (let [c (if (keyword? x) (subs (str x) 1) (str x))]
        (when (str/starts-with? c "?") (keyword (subs c 1)))))))

(defn- arg-key [x] (or (param-key x) (when (symbol? x) x)))

(defn- shorthand-fn
  "The function a HoneySQL :%f.args keyword calls (:%max.id is MAX(id)); nil for any other."
  [x]
  (when (keyword? x)
    (let [c (subs (str x) 1)]
      (when (str/starts-with? c "%") (first (str/split (subs c 1) #"\."))))))

(defn- column-keyword? [x] (and (keyword? x) (not (sql-value x)) (not (param-key x)) (not (shorthand-fn x))))

(defn- vectorish
  "x when it is a vector, [x] for a bare keyword (HoneySQL takes :order-by :id for [:id]): a
   list there is code, and what it builds is not in the data."
  [x]
  (cond (vector? x) x (keyword? x) [x]))

(defn- bare
  "x of an item [x] or [x alias] of a select list, or [x direction ...] of :order-by."
  [x]
  (if (vector? x) (first x) x))

(defn- selected-items
  "The items of :select, :select-distinct, :select-distinct-on (after its DISTINCT ON columns)
   or :returning."
  [stmt]
  (let [s (or (:select stmt) (:select-distinct stmt) (:returning stmt))
        after-first (or (:select-distinct-on stmt) (:select-top stmt) (:select-distinct-top stmt))]
    (cond (or (vector? s) (keyword? s)) (vectorish s)
          (vector? after-first) (rest after-first))))

(defn- select-parts
  "[column alias] of a select item: a column, a column under an alias (a keyword, or a string
   HoneySQL quotes), or an aliased expression, which has no column. Under sql/format's :quoted
   a keyword HoneySQL otherwise writes as an SQL word (:user) is a column."
  ([item] (select-parts item {}))
  ([item opts]
   (let [column? (if (quoted? (:format opts))
                   #(and (keyword? %) (not (param-key %)) (not (shorthand-fn %)))
                   column-keyword?)]
     (cond
       (keyword? item) (when (or (= :* item) (column? item)) [item nil])
       (not (vector? item)) nil
       (and (keyword? (first item)) (not (column? (first item))) (not= :* (first item))) (when (alias? (second item)) [nil (second item)])
       (and (keyword? (first item)) (alias? (second item))) [(first item) (second item)]
       (keyword? (first item)) [(first item) nil]
       (alias? (second item)) [nil (second item)]))))

(def ^:private in-ops #{:in :not-in})
(def ^:private like-ops #{:like :ilike :not-like :not-ilike})
(def ^:private comparison-ops
  (into #{:= :<> :not= :!= :< :> :<= :>= :is-distinct-from :is-not-distinct-from} (concat in-ops like-ops)))
;; a value outside a CHECK's list makes these match no row; the others compare the column's base
;; type, which takes any value
(def ^:private equality-ops #{:= :in :is-not-distinct-from})
(def ^:private negated-op
  {:= :<> :<> := :not= := :!= := :in :not-in :not-in :in
   :is-not-distinct-from :is-distinct-from :is-distinct-from :is-not-distinct-from})

(defn- conditions
  "The parts of a statement comparisons are read in: its conditions, and the expressions of
   its select and ORDER BY."
  [stmt]
  ;; a vector of them: a map holding :select reads as a statement, which a walk does not enter
  (cond-> (vec (vals (select-keys stmt (into [:where :having :on-conflict :select :select-distinct :select-distinct-on :order-by] join-keys))))
    ;; the condition of ON CONFLICT DO UPDATE SET {:fields ... :where ...}
    (map? (:do-update-set stmt)) (conj (:where (:do-update-set stmt)))))

(defn- condition-nodes [stmt] (own-nodes (conditions stmt)))

(defn- polar-nodes
  "[node negated?] for the nodes of part, as own-nodes walks them: negated? under an odd number
   of :not, through :and and :or alone ([:not [:= :state \"x\"]] is state <> 'x'; a CASE or a
   function's argument under NOT is not negated)."
  [part]
  (letfn [(walk [x neg?]
            (lazy-seq
             (cons [x neg?]
                   (when (and (branch? x) (not (statement? x)))
                     (let [neg? (case (when (vector? x) (first x))
                                  :not (not neg?)
                                  (:and :or) neg?
                                  false)]
                       (mapcat #(walk % neg?) (seq x)))))))]
    (walk part false)))

(defn- comparison?
  "A comparison node: [op a b] of comparison-ops, or [:between a b c]."
  [x]
  (and (vector? x) (or (and (= 3 (count x)) (comparison-ops (first x)))
                       (and (= 4 (count x)) (#{:between :not-between} (first x))))))

(defn- comparison-sides
  "[op side other] for each side of the comparisons in a statement's conditions (BETWEEN's
   first, against each bound), element by element where a row meets a row of its length
   ([:< [:composite :a :b] [:composite :?a :?b]]): a side is a column, an expression or a
   value, other whatever it is. Under an odd number of :not, op is its negation (= is <>)."
  [stmt]
  (for [[x negated?] (polar-nodes (conditions stmt))
        :when (comparison? x)
        :let [[op a b c] x
              row? #(and (vector? %) (= :composite (first %)))]
        [side other] (cond (= 4 (count x)) [[a b] [a c]]
                           (and (row? a) (row? b) (= (count a) (count b))) (mapcat (fn [l r] [[l r] [r l]]) (rest a) (rest b))
                           :else [[a b] [b a]])]
    [(if negated? (negated-op op op) op) side other]))

(defn- comparisons [stmt] (filter (comp column-keyword? second) (comparison-sides stmt)))

(defn- target-column
  "A column as HoneySQL writes it where a statement assigns it: without its namespace."
  [c]
  (if (and (keyword? c) (namespace c)) (keyword (name c)) c))

(defn- value-rows
  "The maps of :values as HoneySQL writes them under the statement's column list: only the
   listed keys."
  [stmt columns]
  (let [maps (map #(update-keys % target-column) (filter map? (when (vector? (:values stmt)) (:values stmt))))]
    (if columns (map #(select-keys % (map target-column columns)) maps) maps)))

(defn- assignments
  "[column value] pairs a statement assigns: :set, the maps of :values (which may be a symbol,
   rows passed in, saying nothing about the columns), and the items of INSERT ... SELECT under
   the columns listed, by position."
  [stmt]
  (let [values (:values stmt)
        {:keys [columns select]} (insert-parts stmt)
        do-update (:do-update-set stmt)]
    (filter (comp column-keyword? first)
            (map (fn [[c v]] [(target-column c) v])
            (concat (when (map? (:set stmt)) (:set stmt))
                    ;; ON CONFLICT DO UPDATE SET: a map of assignments, the columns taken from EXCLUDED, or
                    ;; {:fields [...] :where ...} (the columns taken from EXCLUDED, under a condition)
                    (cond (and (map? do-update) (contains? do-update :fields)) (for [c (vectorish (:fields do-update)) :when (keyword? c)] [c nil])
                          (map? do-update) do-update
                          (vector? do-update) (for [c do-update :when (keyword? c)] [c nil]))
                    (when (vector? values)
                      (concat (mapcat identity (value-rows stmt columns))
                              ;; positional rows under the listed columns
                              (when columns (mapcat #(map vector columns %) (filter vector? values)))))
                    ;; a star or :raw makes columns the data does not count
                    (let [items (map bare (selected-items select))]
                      (when (and columns (map? select) (not-any? #(or (and (keyword? %) (star? %)) (and (vector? %) (= :raw (first %)))) items))
                        (map vector columns items))))))))

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
  "A function's column name: as HoneySQL writes it (- is _), unqualified, as PostgreSQL folds it;
   TRIM is btrim, which PostgreSQL's grammar calls it."
  [s]
  (let [n (folded (last (str/split s #"\.")))]
    (if (= "trim" n) "btrim" n)))

(defn- called
  "The function name of a call [:f ...]; nil for anything else."
  [x]
  (when (and (vector? x) (keyword? (first x))) (function-name (name (first x)))))

(defn- expression-name
  "The column name PostgreSQL gives a select expression with no alias: a function's name, case,
   array, a cast column's or function's name, ?column? for a value or an operator (an infix one
   of HoneySQL's, or any whose name is not an identifier: :->, :@>, an op registered with
   register-op!). nil when this cannot tell (a cast value or operation is named after its type,
   a subquery after its column, :raw anything)."
  [x]
  (cond (or (number? x) (string? x) (boolean? x) (nil? x) (param-key x) (#{:null :true :false} (sql-value x))) "?column?"
        (shorthand-fn x) (function-name (shorthand-fn x))
        (keyword? x) (some-> (sql-value x) name)
        (not (and (vector? x) (keyword? (first x)))) nil
        :else (let [[h a] x]
                (cond (= :. h) (some-> (last x) (#(when (keyword? %) (function-name (name %)))))
                      (operator? h) "?column?"
                      ;; CASE takes its ELSE's name where that has one of its own (a column's, a function's)
                      (#{:case :case-expr} h) (let [e (some #(when (= :else (first %)) (second %)) (partition-all 2 (if (= :case h) (rest x) (drop 2 x))))
                                                    n (cond (nil? e) "case"
                                                            (column-keyword? e) (second (split-column e))
                                                            :else (expression-name e))]
                                                (if (= "?column?" n) "case" n))
                      (= :array h) "array"
                      (= :cast h) (cond (column-keyword? a) (second (split-column a))
                                        (vector? a) (let [n (expression-name a)] (when-not (#{"?column?" "case"} n) n)))
                      (special-syntax h) nil
                      :else (function-name (name h))))))

(defn- item-expression
  "The expression of a select item with no alias: [x] is x."
  [item]
  (if (and (vector? item) (= 1 (count item))) (first item) item))

(defn- alias-label
  "The label PostgreSQL gives an alias: folded as any unquoted name (:user-id labels user_id),
   as it is under sql/format's :quoted or as a string, which HoneySQL quotes."
  [alias opts]
  (if (or (string? alias) (quoted? (:format opts))) (name alias) (folded (name alias))))

(defn- item-name
  "The name of the output column a select item makes: its alias, its column's name, or the name
   PostgreSQL gives the expression; nil when that cannot be told."
  ([item] (item-name item {}))
  ([item opts]
   (let [[col alias] (select-parts item opts)]
     (cond alias (alias-label alias opts)
           col (second (split-column col))
           :else (expression-name (item-expression item))))))

;;; problems

(defn- labels
  "The [:enum ...] of an enum column: a CHECK's list, an enum type, or a domain over either."
  [registry schema]
  (let [s (shape/non-null schema)]
    (when (vector? s)
      (case (first s)
        :enum s
        :ref (labels registry (get (::schemas registry) (last s)))
        ;; a domain's CHECKs narrow its base as [:and base ...]
        :and (labels registry (first (shape/entries s)))
        nil))))

(defn- enum-values
  "The labels of an enum column, in the enum's order."
  [registry schema]
  (some->> (labels registry schema) rest (remove map?) vec))

(defn- list-type
  "The base type of a column's labels that are a CHECK's list (the column's, or its domain's):
   the :pg/type they keep; nil for an enum type's labels, which name none."
  [registry s]
  (some-> (labels registry s) shape/column-props :pg/type))

(defn- beyond-list
  "s, or the base type of a CHECK's list it is: the type of a value computed from it and others
   (coalesce, CASE, a recursive CTE's later rows), which the list does not hold."
  [registry s]
  (if-let [t (list-type registry s)] (type-schema t) s))

(defn- compared-schema
  "The schema a value op compares with a column of schema s must fit: under equality-ops, or
   for an enum type's labels, the column's; for a CHECK's list otherwise, the base type."
  [registry s op]
  (if-let [t (when-not (equality-ops op) (list-type registry s))]
    (let [base (:schema (types/entry t))]
      (cond (keyword? base) [base {:pg/type t}]
            (some? base) base
            :else :any))
    s))

(defn- star? [col] (= "*" (second (split-column col))))

(defn- output-column?
  "Whether a bare :col of :order-by or :group-by may mean an output column of stmt."
  [stmt col]
  (boolean (and (not (first (split-column col)))
                (some #(= (folded (name col)) (item-name %)) (selected-items stmt)))))

(defn- output-statement
  "The statement whose select names a statement's output columns: itself, or the first member
   of a set operation (UNION ...); nil when that member is built by code."
  [stmt]
  (if-let [members (some stmt set-operation-keys)]
    (when (and (vector? members) (map? (first members))) (output-statement (first members)))
    stmt))

(defn- untold-outputs?
  "Whether stmt's select may make output names not read here: a :raw item, or, when stars?
   (the first member of a set operation, whose output names are all ORDER BY sees), a star."
  [stmt stars?]
  (boolean (some #(let [e (item-expression %)]
                    (or (and (vector? e) (= :raw (first e)))
                        (and stars? (some-> (first (select-parts %)) star?))))
                 (selected-items stmt))))

(defn- ordering-columns
  "The input columns :order-by and :group-by name as they are (:col, or [:col :asc]);
   expressions there are not read. As PostgreSQL reads them, a bare name in :order-by is an
   output column first (of a set operation, its first member's), one in :group-by an input
   column first."
  [registry sc stmt]
  (let [out (output-statement stmt)
        output? (fn [s c stars?] (or (output-column? s c) (and (nil? (first (split-column c))) (untold-outputs? s stars?))))]
    (concat (for [c (vectorish (:group-by stmt))
                  :when (and (column-keyword? c) (not (and (output? stmt c false) (empty? (column-hits registry sc c)))))]
              c)
            (when out
              (for [o (vectorish (:order-by stmt)) :let [c (bare o)]
                    :when (and (column-keyword? c) (not (output? out c (not= out stmt))))]
                c)))))

(defn- whole-row?
  "Whether an unqualified :x names a table of the scope rather than a column: its whole row."
  [scope col]
  (let [[a column] (split-column col)]
    (boolean (and (nil? a) (some #(and (map? %) (contains? % (folded column))) (chain scope))))))

(defn- near-columns
  "Up to three columns of the scope's tables spelled close to col's name: what it likely meant."
  [registry scope col]
  (let [[a c] (split-column col)
        c (folded c)
        ;; a qualified name looks in the innermost table of its alias, which hides outer ones
        scopes (filter map? (chain scope))
        scopes (if a (take 1 (filter #(contains? % a) scopes)) scopes)
        names (distinct (for [sc scopes
                              [alias {:keys [table]}] sc :when (and table (or (nil? a) (= a alias)))
                              n (keys (table-columns registry table)) :when (not (system-columns n))]
                          n))]
    (some->> (error/suggestions c names 3) (mapv keyword))))

(defn- column-problems [registry sc stmt {:keys [schema] :or {schema default-schema} :as opts}]
  (let [target (target-scope sc stmt schema)
        ;; a target code names is not in the data: its columns are not judged
        assigned (when (seq target) (concat (filter column-keyword? (:columns (insert-parts stmt))) (map first (assignments stmt))))]
    (for [[col scope] (distinct (concat (for [c (keep #(first (select-parts % opts)) (selected-items stmt))] [c sc])
                                        (for [c (ordering-columns registry sc stmt)] [c sc])
                                        (for [[_ c] (comparisons stmt)] [c sc])
                                        (for [x (condition-nodes stmt)
                                              :when (and (vector? x) (= 3 (count x)) (#{:is :is-not} (first x))
                                                         (column-keyword? (second x)))]
                                          [(second x) sc])
                                        ;; a keyword assigned in SET is a column the statement sees
                                        (for [m [(:set stmt) (:do-update-set stmt)] :when (and (map? m) (not (contains? m :fields)))
                                              [_ v] m :when (column-keyword? v)]
                                          [v sc])
                                        (for [c assigned] [c target])))
          :when (if (or (star? col) (whole-row? scope col))
                  ;; :t.* names a table of the scope, unless the data does not show them all
                  (let [[a] (split-column col)]
                    (and (star? col) a (not (some #{:open} (chain scope))) (not-any? #(and (map? %) (contains? % a)) (chain scope))))
                  (nil? (resolve-column registry scope col)))
          :let [hits (column-hits registry scope col)]]
      (if (< 1 (count hits))
        {:kind :ambiguous-column :column col :candidates (mapv (fn [[alias table]] (or table alias)) hits)}
        (let [near (near-columns registry scope col)]
          (cond-> {:kind :unknown-column :column col} near (assoc :did-you-mean near)))))))

(defn- table-props
  "The properties of the row schema of table (\"schema.table\")."
  [registry table]
  (shape/row-props (get (::schemas registry) (shape/table-key table))))

(defn- filled-by-trigger?
  "Whether a BEFORE row trigger runs, for event (:insert, :update), on the table of row
   properties props: it may fill a column before NOT NULL is checked."
  [props event]
  (boolean (get-in props [:pg/before-triggers event])))

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
            ;; HoneySQL writes :group-id as group_id
            given-names (into #{} (mapcat (fn [k] [(name k) (folded (name k))])) given)
            required (set (for [[k p] (some-> (get (::schemas registry) (shape/derived-name (shape/table-key table) "insert")) shape/column-entries)
                                :when (not (:optional p))]
                            (name k)))]
        (concat
         (when columns
           (for [[i row] (map-indexed vector (filter vector? rows)) :when (not= (count row) (count columns))]
             {:kind :values-arity :table table :row i :columns (count columns) :values (count row)}))
         (when (and (seq given) (not (filled-by-trigger? (table-props registry table) :insert)))
           (for [c required :when (not (given-names c))]
             {:kind :missing-required-column :table table :column (keyword c)})))))))

(defn- quoted?
  "Whether sql/format's options quote a name: :quoted, or a :dialect other than :nrql unless
   :quoted false."
  [{:keys [quoted dialect] :as format}]
  (if (contains? format :quoted) quoted (and (contains? format :dialect) (not= :nrql dialect))))

(defn- null-value?
  "Whether HoneySQL writes v as NULL: nil, [:inline nil], [:lift nil], or :null (any case) where
   the format leaves it unquoted (quoted?), and :quoted-always does not match it."
  [v {:keys [quoted-always] :as format}]
  (or (nil? v) (#{[:inline nil] [:lift nil]} v)
      (and (= :null (sql-value v)) (not (quoted? format))
           (not (some-> quoted-always (re-find (name v)))))))

(defn- write-problems
  "Writes PostgreSQL refuses: NULL into a NOT NULL column, and a value other than DEFAULT into
   a column only the database writes (generated, or identity ALWAYS without OVERRIDING SYSTEM
   or USER VALUE, which the first writes and the second ignores). A column one map of :values
   lacks and another has is NULL, as HoneySQL writes it, DEFAULT under sql/format's
   :values-default-columns (in :format). Under a column list, which map keys reach which column
   depends on the HoneySQL version (2.8.85 follows the list): missing keys are not judged there."
  [registry scopes stmt {:keys [schema format] :or {schema default-schema}}]
  (let [target (target-scope scopes stmt schema)
        i (:insert-into stmt)
        overriding? (and (vector? i) (map? (first i)) (#{:system :user} (:overriding-value (first i))))
        columns (:columns (insert-parts stmt))
        rows (when (vector? (:values stmt)) (:values stmt))
        maps (value-rows stmt columns)
        union (distinct (mapcat keys maps))
        defaults (set (:values-default-columns format))
        do-update (:do-update-set stmt)
        writes (concat (when (map? (:set stmt)) (for [[c v] (:set stmt)] [(target-column c) v :update :set]))
                        (cond (and (map? do-update) (contains? do-update :fields)) (for [c (vectorish (:fields do-update))] [c ::excluded :update :do-update-set])
                              (map? do-update) (for [[c v] do-update] [(target-column c) v :update :do-update-set])
                              (vector? do-update) (for [c do-update] [c ::excluded :update :do-update-set]))
                        (for [row maps, c (if columns (keys row) union)]
                          [c (cond (contains? row c) (get row c) (defaults c) [:default]) :insert :values])
                        (when columns (for [row (filter vector? rows), [c v] (map vector columns row)] [c v :insert :values])))]
    (when (seq target)
      (distinct
       (for [[col v how clause] writes
             :when (column-keyword? col)
             :let [{:keys [table] s :schema} (resolve-column registry target col)
                   cp (some-> s shape/column-props)
                   p (cond (or (nil? s) ((default-values format) v)) nil
                           (and (shape/written-by-database? cp)
                                (not (and overriding? (= :insert how) (= :always (:pg/identity cp)))))
                           {:kind :read-only-column :table table :column col}
                           (and (null-value? v format) (not (shape/maybe? s)) (not (filled-by-trigger? (table-props registry table) how)))
                           {:kind :not-null-violation :table table :column col :clause clause})]
             :when p]
         p)))))

(defn- domain-over-enum?
  "Whether column schema s is of a domain over an enum type."
  [registry s]
  (let [inner (shape/non-null s)
        target (when (and (vector? inner) (= :ref (first inner))) (get (::schemas registry) (last inner)))]
    (boolean (and target (not= :enum (shape/head target)) (labels registry inner) (not (list-type registry inner))))))

(defn- enum-type
  "The type of an enum type's column, or of a domain over one; not of a domain whose CHECK lists
   its base type's values, whose labels name that type."
  [registry s]
  (let [inner (shape/non-null s)]
    (when (and (vector? inner) (= :ref (first inner)) (labels registry inner) (not (list-type registry inner)))
      (shape/type-name (shape/column-props s)))))

(defn- uncast-type
  "The type to cast a string to for column s: an enum type (or a domain over one), an array, a
   type of types' :cast (bit takes varbit, which keeps its length), or any other type types
   has; nil for a string type (text, varchar, char, name, citext) or a type types lacks (a
   domain over a base type among them)."
  [registry s]
  (let [p (shape/column-props s)
        e (types/entry (shape/type-name p))
        ;; :bit-varying or :timestamp-with-time-zone is no type HoneySQL writes; its alias is
        written #(if (str/includes? % " ") (first (:aliases (types/entry %))) %)]
    (or (enum-type registry s)
        (when e
          (cond (str/ends-with? (:pg/type p) "[]") (str (written (:name e)) "[]")
                (:cast e) (written (name (:cast e)))
                (not= :string (:schema e)) (written (:name e)))))))

(defn- slot-type
  "The type of a column schema as pgmalli.impl.accept names it: [:enum name] for an enum type
   (or a domain over one), a domain's base type, \"x[]\" for an array; nil for a type it does
   not answer for."
  [registry s]
  (let [p (shape/column-props s)
        inner (shape/non-null s)
        d (when (and (vector? inner) (= :ref (first inner))) (get (::schemas registry) (last inner)))
        t (if-let [e (types/entry (shape/type-name p))]
            (str (:name e) (when (str/ends-with? (str (:pg/type p)) "[]") "[]"))
            (when-let [n (enum-type registry s)] (cond-> [:enum n] (domain-over-enum? registry s) (conj {:domain? true}))))]
    (cond (accept/known? t) t
          (and d (not (enum-type registry s))) (slot-type registry (shape/domain-base d)))))

(defn- takes-string?
  "Whether a column of schema s takes a string sent as one of types, compared or assigned; nil
   where pgmalli.impl.accept does not answer."
  [registry s assigned? types]
  (when-let [t (slot-type registry s)] (boolean (some #(accept/accepts? t % (if assigned? :assign :compare)) types))))

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
   assigned or under equality-ops; and, where :send sends a String typed (varchar, pgjdbc's
   default), a string HoneySQL sends as a parameter (not inlined, not cast) to a column
   PostgreSQL takes no such string for (enum = varchar does not exist, nor inet = varchar): a
   literal, or an argument to an enum column or to one whose own values are strings."
  [registry sc stmt {:keys [schema] :or {schema default-schema} :as opts}]
  (let [uses (concat (for [[op side value] (comparison-sides stmt)
                           :when (not (like-ops op))
                           ;; an expression's type, where row-schema tells it
                           :let [s (when (and (vector? side) (not (column-keyword? side))) (:schema (expression-type registry sc side opts)))]
                           :when (or s (column-keyword? side))]
                       (cond-> {:col side :value value :scope sc :op op} s (assoc :schema s)))
                     (let [target (target-scope sc stmt schema)]
                       (when (seq target) (for [[col value] (assignments stmt)] {:col col :value value :scope target :assigned? true}))))
        sends (jdbc/string-sends opts)
        typed? (not= :unknown (jdbc/sent-type "" opts))]
    (concat
     ;; an argument to an enum type's column, or to one whose own values are strings: a String,
     ;; which goes typed unless :send sends it untyped (stringtype=unspecified)
     (for [{:keys [col value scope assigned?] :as use} uses
           :let [k (param-key value)] :when (and k typed?)
           :let [s (or (:schema use) (:schema (resolve-column registry scope col)))
                 t (some->> s (uncast-type registry))]
           ;; one assigned to a nullable column may be nil, which PostgreSQL takes
           :when (and t (not (takes-string? registry s assigned? sends)) (not (and assigned? (shape/maybe? s)))
                      (or (enum-type registry s) (some #(= :string (shape/kind %)) (shape/alternatives (driver-type registry jdbc/write-type s opts)))))]
       {:kind :uncast-string :column col :arg k :type t})
     (for [{:keys [col value scope assigned? op] :as use} uses
           :let [s (or (:schema use) (:schema (resolve-column registry scope col)))
                 strings? (or (string? value) (and (vector? value) (every? string? value)))
                 numbers? (or (number? value) (and (vector? value) (seq value) (every? number? value)))
                 values (cond (or strings? numbers?) (if (vector? value) value [value])
                              (and (vector? value) (#{:cast :inline} (first value)) (or (string? (second value)) (number? (second value)))) [(second value)])
                 allowed (when s (enum-values registry (if assigned? s (compared-schema registry s op))))
                 uncast (when (and s typed? strings? (not (takes-string? registry s assigned? sends)))
                          (uncast-type registry s))]
           :when (seq values)
           v values
           :let [invalid? (and allowed (not (some #(label= % v) allowed)))]
           p [(when invalid? {:kind :enum-literal :column col :value v :allowed allowed})
              ;; a value outside the enum fails however it is sent
              (when (and uncast (not invalid?)) {:kind :uncast-string :column col :value v :type uncast})]
           :when p]
       p))))

(defn- problems
  "The problems of one statement: unknown tables; columns selected, returned, inserted, set
   or compared that are unknown, or ambiguous (unqualified and in more than one known table in
   scope, those under :candidates), in :select, :where, :having, join conditions, :set,
   :values, ON CONFLICT DO UPDATE SET, :group-by and :order-by (bare columns there); required
   INSERT columns missing, or a row of :values not as long as :columns; enum literals outside
   the enum (compared with, or assigned to, the column). scope is the statement's (from scope)
   or, for a nested statement, the chain of its own and the enclosing ones. Empty when the
   statement agrees with the registry."
  [registry stmt scope {:keys [schema] :or {schema default-schema} :as opts}]
  (let [registry (reading registry)
        scopes (chain scope)]
    (vec (concat (for [[_ {:keys [table unknown?]}] (first scopes) :when unknown?
                       :let [known (for [[k v] (::schemas registry) :when (shape/read-row? v)]
                                     (str/join "." (shape/schema-and-name k)))
                             meant (error/suggestions table known 3)]]
                   (cond-> {:kind :unknown-table :table table} meant (assoc :did-you-mean (vec meant))))
                 (column-problems registry scopes stmt opts)
                 (insert-problems registry stmt schema)
                 (write-problems registry scopes stmt opts)
                 (string-problems registry scopes stmt opts)))))

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
   what it does goes unjudged: in the query map and every statement, and, when HoneySQL is loaded, in a map of
   HoneySQL's clauses alone ({:table :t}, a clause registered with register-clause!). The rows of
   :values, :set and :do-update-set are maps of columns, not of clauses, though a subquery in
   them is read."
  [body]
  (let [clauses (some-> (honeysql 'honey.sql/clause-order) (#(set (%))))
        rows (set (for [st (statements body)
                        r (concat (when (vector? (:values st)) (:values st)) [(:set st) (:do-update-set st)])
                        :when (map? r)]
                    r))
        maps (tree-seq branch? seq body)]
    (distinct (concat
               (for [m maps
                     ;; the query itself is clauses, however unknown ({:selct [:id]})
                     :when (and (map? m) (seq m) (not (rows m)) (or (identical? m body) (statement? m) (and clauses (every? clauses (keys m)))))
                     k (keys m)
                     :when (and (keyword? k) (not (read-clauses k)) (not (ignored-clauses k)))]
                 {:kind :unread-clause :clause k})
               ;; HoneySQL takes {select [...]} for {:select [...]}; here a symbol is a value, so
               ;; such a statement is not read, and said so
               (for [m maps :when (and (map? m) (not (rows m)))
                     k (keys m)
                     :when (and (symbol? k) (or (read-clauses (keyword k)) (some-> clauses (contains? (keyword k)))))]
                 {:kind :unread-clause :clause k})))))

(defn- query!
  "body when it is HoneySQL query data (a map, or code holding maps); an error saying so for SQL
   already formatted or for nothing, which would otherwise check clean."
  [body]
  (when (or (nil? body) (string? body) (and (vector? body) (string? (first body))))
    (error/fail! :pgmalli.error/not-query-data
                 (str "pgmalli.honeysql reads HoneySQL query data (a map such as {:select ...}), not "
                      (if (nil? body) "nil" "SQL already formatted") "; pass the map you give sql/format")
                 {:given body}))
  body)

(defn- code-ctes?
  "Whether code (not data) gives a statement some of its CTEs, which may then be any table."
  [stmt]
  (some #(let [w (get stmt %)] (and (some? w) (or (not (vector? w)) (some (comp nil? cte-name) w)))) with-keys))

(def ^:private update-count
  "The row next.jdbc returns for a statement that returns none: the driver's update count."
  [:map [:next.jdbc/update-count :int]])

(defn- sent
  "stmt as pgjdbc sends it; nil when next.jdbc returns the update count instead of rows. A write
   with no RETURNING gets RETURNING * under :return-keys true, RETURNING the columns named under a
   vector of them, but for TRUNCATE, whose generated keys have no column."
  [stmt {{:keys [return-keys]} :jdbc}]
  (cond (or (not (map? stmt)) (contains? stmt :returning) (not-any? #(contains? stmt %) write-keys)) stmt
        (not return-keys) nil
        (contains? stmt :truncate) stmt
        :else (assoc stmt :returning (if (true? return-keys) [:*] (mapv #(keyword (name %)) return-keys)))))

(defn- return-key-problems
  "pgjdbc quotes the names of :return-keys as written (its quoteReturningIdentifiers, true by
   default): one that is a column of the target only folded (:group-id for group_id) is none."
  [registry stmt scopes {{:keys [return-keys]} :jdbc :keys [schema] :or {schema default-schema} :as opts}]
  (when (and (sequential? return-keys) (map? stmt) (not= stmt (sent stmt opts)))
    (let [[_ alias] (some-> (first (write-targets stmt)) (table-ref schema))
          {:keys [table opaque?]} (get (first (chain scopes)) alias)
          cs (when (and table (not opaque?)) (table-columns registry table))]
      (for [k return-keys :let [n (name k)]
            :when (and cs (not (contains? cs n)) (contains? cs (folded n)))]
        {:kind :unknown-column :column (keyword n) :did-you-mean [(keyword (folded n))]}))))

(defn check
  "[problem ...] of a HoneySQL query against the registry; [] when there are none.

   (check registry {:select [:id :mail] :from [:users]})
   ;; => [{:kind :unknown-column, :column :mail, :did-you-mean [:email]}]

   A problem in a nested statement (a subquery, a CTE) carries :in, where the statement sits:
   (get-in q (:in p)) for data, the position in the form for code. humanize says each problem
   as a sentence. Names resolve as PostgreSQL resolves them. A clause pgmalli does not read is an
   :unread-clause problem. Code in the query (a list) is not read. Options: docs/queries.md#options.
   Throws when body is no HoneySQL data."
  ([registry body] (check registry body {}))
  ([registry body opts]
   (jdbc/options! opts)
   (query! body)
   (let [registry (reading registry)
         sent-body (or (sent body opts) body)
         chains (statement-chains registry sent-body opts)
         found (mapcat (fn [[stmt scopes path]]
                         ;; :in, where a nested statement sits, as get-in reads it (malli's :in)
                         (cond->> (problems registry stmt scopes opts) (seq path) (map #(assoc % :in (vec path)))))
                       chains)]
     (vec (concat found (return-key-problems registry body (second (first chains)) opts) (unread-clauses sent-body))))))

(defn tables
  "{:reads #{\"schema.table\" ...} :writes #{...}}: the tables and views a query reads (FROM,
   joins, USING, in every subquery and CTE) and writes (INSERT INTO, UPDATE, DELETE FROM,
   TRUNCATE),
   aliases and CTE names resolved as PostgreSQL resolves them. A table code in the query
   brings in is not seen. Options: docs/queries.md#options.

   (tables registry {:update :users :set {:nick \"a\"} :from [[:groups :g]] :where [:= :g.id :users.group_id]})
   ;; => {:reads #{\"public.groups\"}, :writes #{\"public.users\"}}"
  ([registry body] (tables registry body {}))
  ([registry body {:keys [schema] :or {schema default-schema} :as opts}]
   (jdbc/options! opts)
   (query! body)
   (let [registry (reading registry)
         found (for [[stmt scopes] (statement-chains registry body opts)
                     :let [own (first scopes)
                           targets (set (keep #(some-> % (table-ref schema) second) (write-targets stmt)))]
                     [alias {:keys [table cte? excluded?]}] (when (map? own) own)
                     :when (and table (not cte?) (not excluded?))]
                 [(if (targets alias) :writes :reads) table])]
     (merge {:reads #{} :writes #{}}
            (update-vals (group-by first found) #(set (map second %)))))))

;;; types

(defn- driver-type
  "A column schema as jdbc's read-type or write-type (f) gives it, without NULL, as malli's
   default registry reads it."
  [registry f s opts]
  (let [t (portable/portable-data (::schemas registry)
                                  (if (= f jdbc/read-type)
                                    (jdbc/column-read-type (::schemas registry) (shape/non-null s) opts)
                                    (jdbc/column-write-type (::schemas registry) (shape/non-null s) opts)))]
    ;; one of each kind, after pgmalli's own types read as malli's (:pg/bytes as bytes?)
    (if (and (vector? t) (= :or (first t)) (not (map? (second t))))
      (shape/either (shape/distinct-kinds (rest t)) t)
      t)))

(def ^:private maybe? shape/maybe?)

(defn- untyped?
  "A type that says nothing: :any, with or without properties."
  [t]
  (or (nil? t) (= :any t) (and (vector? t) (= :any (first t)))))

(defn- nullable [t] (if (or (untyped? t) (maybe? t)) t [:maybe t]))

(defn- array-arg
  "The argument x of [h x] with h one of heads, sent as one array: [:any x] and [:all x] by
   default (= ANY(?), <> ALL(?))."
  ([v] (array-arg v #{:any :all}))
  ([v heads] (when (and (vector? v) (= 2 (count v)) (heads (first v)) (arg-key (second v))) (second v))))

(defn- written-events
  "The writes a statement's assignments are: :insert, and :update for an UPDATE or ON CONFLICT
   DO UPDATE."
  [stmt]
  (cond-> #{} (:insert-into stmt) (conj :insert) (or (:update stmt) (:do-update-set stmt)) (conj :update)))

(defn- column-type
  "The type of an argument for a column: compared with it (a comparison with NULL is never true, so no
   [:maybe]) or, when assigned?, assigned to it by a write of events (:insert, :update): NULL
   where the column takes it, or a BEFORE row trigger for one of them may fill it."
  ([registry sc col opts] (column-type registry sc col false #{} opts))
  ([registry sc col assigned? events opts]
   (let [{s :schema :keys [table opaque?]} (resolve-column registry sc col)]
     (if s
       (let [inner (accepted-type registry s (if assigned? :assign :compare) opts)]
         (if (and assigned? (or (maybe? s) (and (not opaque?) (some #(filled-by-trigger? (table-props registry table) %) events)))) [:maybe inner] inner))
       :any))))

(defn- compared-schema-type
  "The type of an argument op compares with a value of column schema s, as compared-schema
   narrows it."
  [registry s op opts]
  (accepted-type registry (compared-schema registry s op) :compare opts))

(defn- compared-column-type [registry sc col op opts]
  (if-let [s (:schema (resolve-column registry sc col))] (compared-schema-type registry s op opts) :any))

(defn- registry-type
  "The registry's key and schema of the enum type or domain a cast names (:mood, :app.mood);
   nil for another name."
  [registry t {:keys [schema] :or {schema default-schema}}]
  (when (ident? t)
    (let [parts (str/split (name t) #"\." 2)
          [sch n] (if (next parts) parts [schema (first parts)])
          k (shape/schema-key sch n)
          s (get (::schemas registry) k)]
      (when (and s (empty? (shape/column-entries s))) [k s]))))

(defn- loose
  "A column type without the bounds a comparison need not keep (CHECKs, lengths, ranges): its
   kind and :pg/type, each alternative's of an :or. A type with other children (an enum) stays:
   PostgreSQL refuses a value outside an enum, even compared."
  [t]
  (if-not (vector? t)
    t
    (let [[h & more] t
          [p & children] (if (map? (first more)) more (cons nil more))]
      (cond (= :and h) (let [base (loose (first children))]
                         (if-let [pg (and (vector? base) (not (map? (second base))) (:pg/type p))]
                           (into [(first base) {:pg/type pg}] (rest base))
                           (if (and (keyword? base) (:pg/type p)) [base {:pg/type (:pg/type p)}] base)))
            (#{:maybe :vector :sequential :set :pg/array} h) [h (loose (last t))]
            (= :or h) (into (cond-> [:or] p (conj p)) (map loose children))
            (seq children) t
            :else (if-let [pg (:pg/type p)] [h {:pg/type pg}] h)))))

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

(defn- accepted-type
  "The type of an argument where a value of column schema s goes, in context (:compare,
   :assign or :cast), as docs/queries.md#the-types-of-the-arguments says: its own values
   (jdbc/write-type), and the values of each type :send sends that the column holds as its own
   (accept/holds?) or, cast, that PostgreSQL casts (accept/accepts?): for an untyped one, the
   values sent untyped; for a typed one, the values a column of that type takes (:time's too,
   and the values sent as it); assigned or cast, a number held to a number column's range
   (within). Compared, the values lose the bounds a
   comparison need not keep (loose). Own values with labels (an enum's, a CHECK's list) are the
   labels, a text label as a keyword too where a row of keywords goes, a number label as each
   number class sent and as a string where one goes untyped or cast; untyped own values, or of a type pgmalli.impl.accept does not answer for,
   are the type alone (:any under a cast)."
  [registry s context opts]
  (let [own (cond-> (driver-type registry jdbc/write-type s opts)
              (= :compare context) loose
              ;; a cast cuts a string to a varchar(n)'s or a char(n)'s length; a CHECK's bounds hold after it
              (= :cast context) (->> (walk/postwalk #(if (and (vector? %) (= :string (first %)) (:pg/max-length (second %)))
                                                       (update % 1 (fn [p] (cond-> (dissoc p :pg/max-length) (= (:max p) (:pg/max-length p)) (dissoc :max))))
                                                       %))
                                     shape/without-gen))
        t (slot-type registry s)
        takes? (if (= :cast context) #(accept/accepts? t % context) #(accept/holds? t % context))
        sent (distinct (for [[_ p] (jdbc/write-rows opts false) :when (and (not= p t) (takes? p))] p))
        alts (concat
              (for [p sent
                    a (if (= :unknown p)
                        (mapcat (fn [[schema q]] (when (= :unknown q) (shape/alternatives schema))) (jdbc/write-rows opts false))
                        (shape/alternatives (unlabel (loose (driver-type registry jdbc/write-type (type-schema p) opts)))))
                    :when (not (untyped? a))]
                [p a])
              ;; cast, an array as PostgreSQL casts it (to text, say), its elements any
              (when (= :cast context)
                (for [[schema q] (jdbc/write-rows opts true) :when (takes? q)
                      a (shape/alternatives (walk/postwalk-replace {:pgmalli/element :any} schema))]
                  [q a])))]
    (cond
      ;; a cast takes a string and every explicit cast, of types pgmalli.impl.accept does not answer for
      (and (nil? t) (= :cast context)) :any
      (or (nil? t) (untyped? own)) own
      ;; labels, as each value class taken sends them: a text label as a keyword, a number as
      ;; the number class of each number row (1.0 as 1 where a bigint goes to a double precision)
      (= :enum (shape/kind own))
      (let [e (shape/domain-base own)
            [props labels] (if (map? (second e)) [(second e) (drop 2 e)] [nil (rest e)])
            kinds (set (map (comp shape/kind second) alts))
            as (fn [l] (concat (when (and (string? l) (some #(:keyword (shape/value-classes (second %))) alts)) [(keyword l)])
                               ;; a number label as a string, which PostgreSQL reads as the number where a string goes (untyped, or cast)
                               (when (and (number? l) (some #(:string (shape/value-classes (second %))) alts)) [(str l)])
                               (when (number? l)
                                 (concat (when (and (kinds :int) (== l (Math/rint (double l)))) [(long l)])
                                         (when (kinds 'decimal?) [(bigdec l)])
                                         (when (kinds :double) [(double l)])))))
            more (remove (set labels) (distinct (mapcat as labels)))]
        ;; a domain's checks stay around its labels
        (if (seq more) (shape/update-base own (constantly (into (cond-> [:enum] props (conj props)) (concat labels more)))) own))
      :else
      (let [own-alts (shape/alternatives own)
            ;; a number held to a number column's range, which PostgreSQL checks after converting it;
            ;; a string's or a date's cannot be said of the value sent
            alts (for [[p a] alts] (cond-> a (and (not= :compare context) (accept/number-type? t) (accept/number-type? p)) (within p t own)))
            all (shape/distinct-kinds (concat own-alts alts))]
        (if (= (count all) (count own-alts)) own (into [:or] all))))))

(defn- compared-type
  "The type of an argument compared with a column by op, t as accepted-type gives it compared
   (its CHECKs, lengths and ranges left out: PostgreSQL compares a value beyond them, matching
   no row), without NULL; :in's a non-empty vector, list or set of it, as
   sql/format expands it (IN () is not SQL); an array's the collections and Java arrays :send sends as an array (jdbc/array-type).
   {:compare :lenient} takes nil too: :in's and an array's elements, and the argument itself
   but in :in (nil formats as IN ?)."
  [op t array? {:keys [compare] :as opts}]
  (let [lenient? (= :lenient compare)
        element (cond-> t (and lenient? (not (untyped? t))) nullable)]
    (cond-> (cond array? (jdbc/array-type element opts :compare)
                  (and (in-ops op) (not (untyped? t))) [:or [:sequential {:min 1} element] [:set {:min 1} element]]
                  :else t)
      ;; IS [NOT] DISTINCT FROM compares NULL as a value
      (or (and lenient? (not (in-ops op))) (#{:is-distinct-from :is-not-distinct-from} op)) nullable)))

(defn- data-args
  "The arguments the data of a query shows, outside code (a list): code may leave its own out
   at run time, and binds locals of its own, which are no arguments."
  [body]
  (set (keep arg-key (tree-seq #(and (branch? %) (not (seq? %))) seq body))))

(defn- canonical-type [{s :schema}] (some-> s shape/column-props :pg/type (#(:name (types/entry %) %))))

(def ^:private resolving-fns
  "Functions whose arguments PostgreSQL resolves to one type, as it resolves UNION and CASE:
   their result is of it (nullif's, its first argument's)."
  #{"coalesce" "greatest" "least" "nullif"})

(declare resolved-type)

(defn- result-type
  "The type operands resolve to as a value: resolved-type's, but where an argument is among them
   and the type is no string type, nil: pgjdbc sends a number or a date typed (a Long as bigint),
   which may widen it beyond the others'."
  [operands ts]
  (when-let [r (resolved-type operands ts)]
    (when (or (not-any? arg-key operands) (accept/strings (canonical-type r))) r)))

(def ^:private number-order
  "The number types in the order PostgreSQL's implicit casts widen them."
  (zipmap ["smallint" "integer" "bigint" "numeric" "real" "double precision"] (range)))

(defn- resolved-type
  "The expression type PostgreSQL resolves operands (of types ts) to, as it resolves UNION and
   CASE: that of the typed ones when they agree, or numbers the widest of them (an integer and a
   bigint a bigint); a string or an argument takes it as an unknown value does; nil when one has
   no type pgmalli tells, or they differ otherwise."
  [operands ts]
  (let [typed (for [[o t] (map vector operands ts) :when (not (or (arg-key o) (:string? t)))] t)
        resolved (if (seq typed) typed (remove nil? (map (fn [o t] (when-not (arg-key o) t)) operands ts)))
        names (map canonical-type resolved)]
    (when (and (seq resolved) (not-any? nil? resolved))
      (cond (apply = names) (first resolved)
            (every? number-order names) (let [widest (apply max-key number-order names)]
                                          (some #(when (= widest (canonical-type %)) %) resolved))))))

(defn- unnest-columns
  "{[alias column] arg} of the unnest(?, ...) items of stmt's FROM and joins: the column each
   argument's elements become, by position in the columns its alias lists ([:x {:columns [:a]}]),
   or the alias itself of an unnest of one ([[:unnest :?ids] :id])."
  [stmt]
  (into {} (for [item (table-items stmt)
                 :when (and (vector? item) (vector? (first item)) (= :unnest (ffirst item)))
                 :let [ps (rest (first item))
                       b (second item)
                       [alias cs] (cond (and (vector? b) (map? (second b))) [(first b) (:columns (second b))]
                                        (and (keyword? b) (= 1 (count ps))) [b [b]])]
                 :when (and alias (every? param-key ps) (sequential? cs))
                 [p c] (map vector ps cs)]
             [[(folded (name alias)) (folded (name c))] p])))

(defn- unnest-arg
  "The argument whose elements column x is, of unnest-columns m: x named with its alias or alone."
  [m x]
  (when (column-keyword? x)
    (let [[q c] (split-column x)]
      (some (fn [[[a col] p]] (when (and (= col (folded c)) (or (nil? q) (= q a))) p)) m))))

(defn arg-types
  "{arg schema} for the named parameters of a query (:?id, [:param :id]), keyed as sql/format's
   :params takes them (:id).

   (arg-types registry {:select [:id] :from [:users] :where [:= :email :?email]})
   ;; => {:email [:string {:pg/type \"character varying\"}]}

   docs/queries.md#the-types-of-the-arguments says where an argument is typed and how. Options:
   docs/queries.md#options."
  ([registry body] (arg-types registry body {}))
  ([registry body {:keys [schema] :or {schema default-schema} :as opts}]
   (jdbc/options! opts)
   (query! body)
   (let [registry (reading registry)
         ;; the SELECTs of INSERT ... SELECT, whose items the INSERT types where it assigns them
         sources (set (keep #(:select (insert-parts %)) (statements body)))
         typed (mapcat (fn [[stmt sc]]
                         (concat (for [[op side value] (comparison-sides stmt)
                                       :let [x (array-arg value)]
                                       :when (arg-key (or x value))
                                       :let [t (cond (column-keyword? side) (compared-column-type registry sc side op opts)
                                                     (vector? side) (if-let [s (:schema (expression-type registry sc side opts))] (compared-schema-type registry s op opts) :any)
                                                     :else :any)]]
                                   [(or x value) (compared-type op t x opts)])
                                 ;; LIMIT and OFFSET take a bigint of at least 0 by assignment, NULL for none
                                 (for [[_ v] (select-keys stmt [:limit :offset])]
                                   [v (nullable (accepted-type registry [:int {:pg/type "bigint" :min 0}] :assign opts))])
                                 ;; [:cast :?x :timestamptz]: what PostgreSQL casts to the type; nil too unless every use
                                 ;; of the cast is compared (not under IS [NOT] DISTINCT FROM, not with NULL, not under
                                 ;; {:compare :lenient}) or assigned to a NOT NULL column
                                 (let [casts (filter #(and (vector? %) (= :cast (first %)) (= 3 (count %)) (arg-key (second %)))
                                                     ;; this statement's own and the items INSERT ... SELECT assigns: a
                                                     ;; subquery's casts are its uses
                                                     (mapcat own-nodes (cons (selected-items (:select (insert-parts stmt)))
                                                                             (map val (clause-entries (cond-> stmt (sources stmt) (dissoc :select)))))))
                                       target (target-scope sc stmt schema)
                                       refusing (frequencies
                                                 (concat (when-not (= :lenient (:compare opts))
                                                           ;; each operand of a comparison once: BETWEEN's bounds, IN's items too
                                                           (for [[x] (polar-nodes (conditions stmt))
                                                                 :when (comparison? x)
                                                                 :let [[op & operands] x]
                                                                 :when (not (or (#{:is-distinct-from :is-not-distinct-from} op)
                                                                                (some #(null-value? % (:format opts)) operands)))
                                                                 o operands
                                                                 o (if (and (in-ops op) (vector? o) (not (keyword? (first o)))) o [o])]
                                                             o))
                                                         (for [[col v] (assignments stmt)
                                                               :let [c (:schema (resolve-column registry target col))]
                                                               :when (and c (not (maybe? c)))]
                                                           v)))
                                       uses (frequencies casts)]
                                   (for [x (distinct casts)
                                         :let [s (:schema (expression-type registry sc x opts))]
                                         :when s]
                                     [(second x) (cond-> (accepted-type registry s :cast opts)
                                                   (< (get refusing x 0) (uses x)) nullable)]))
                                 (for [[col v] (assignments stmt)
                                       :let [t (column-type registry (target-scope sc stmt schema) col true (written-events stmt) opts)]]
                                   ;; unnest(?), of a typed array (unnest(unknown) is ambiguous): each element assigned to the column
                                   (if-let [x (array-arg v #{:unnest})] [x (jdbc/array-type t opts :unnest)] [v t]))
                                 ;; unnest(?) AS x(a): the elements of ? are a, typed where a goes, as a typed array
                                 (let [m (unnest-columns stmt)]
                                   (when (seq m)
                                     (concat
                                      (for [[op side value] (comparison-sides stmt)
                                            :let [p (unnest-arg m value)]
                                            :when (and p (column-keyword? side) (not (unnest-arg m side)))
                                            :let [t (compared-column-type registry sc side op opts)]
                                            :when (not (untyped? t))]
                                        [p (jdbc/array-type t opts :unnest)])
                                      ;; UPDATE ... SET col = x.a FROM unnest(?) AS x(a)
                                      (for [[col v] (assignments stmt)
                                            :let [p (unnest-arg m v)]
                                            :when p
                                            :let [t (column-type registry (target-scope sc stmt schema) col true (written-events stmt) opts)]]
                                        [p (jdbc/array-type t opts :unnest)])
                                      ;; [:cast :x.a :date]: an array of what PostgreSQL casts to the type, typed
                                      (for [x (mapcat own-nodes (map val (clause-entries stmt)))
                                            :when (and (vector? x) (= :cast (first x)) (= 3 (count x)))
                                            :let [p (unnest-arg m (second x))]
                                            :when p
                                            :let [s (:schema (expression-type registry sc x opts))
                                                  t (some->> s (slot-type registry))]
                                            :when t
                                            :let [element (accepted-type registry s :cast opts)
                                                  arrays (for [[sch sent] (jdbc/write-rows opts true)
                                                               :when (and (string? sent) (accept/accepts? t (accept/element sent) :cast))]
                                                           (walk/postwalk-replace {:pgmalli/element element} sch))]]
                                        [p (shape/either (distinct arrays) :any)]))))
                                 (let [{:keys [select]} (insert-parts stmt)
                                       m (when (map? select) (unnest-columns select))]
                                   (when (seq m)
                                     (for [[col v] (assignments stmt)
                                           :let [p (unnest-arg m v)]
                                           :when p
                                           :let [t (column-type registry (target-scope sc stmt schema) col true (written-events stmt) opts)]]
                                       [p (jdbc/array-type t opts :unnest)])))
                                 ;; coalesce(?, col) and the like: PostgreSQL resolves an argument to the others' type;
                                 ;; nil is a value there (coalesce and greatest skip it, nullif's first may be NULL)
                                 (for [x (mapcat own-nodes (map val (clause-entries (dissoc stmt :insert-into))))
                                       :when (resolving-fns (called x))
                                       :let [operands (rest x)
                                             r (resolved-type operands (map #(expression-type registry sc % opts) operands))]
                                       :when r
                                       o operands
                                       :when (param-key o)]
                                   [o (nullable (accepted-type registry (beyond-list registry (:schema r)) :compare opts))])))
                       (statement-chains registry body opts))
         ;; an argument no use types, a function's or an operator's, is :any
         untyped (into {} (for [x (tree-seq branch? seq body) :let [k (param-key x)] :when k] [k :any]))
         ;; the first use types an argument; nil where any use takes it (a later IS DISTINCT FROM,
         ;; or a nullable column it is assigned to), which then holds whatever the order
         types (reduce (fn [acc [x t]]
                         (let [k (arg-key x)]
                           (cond (nil? k) acc
                                 (untyped? (acc k)) (assoc acc k t)
                                 (maybe? t) (update acc k nullable)
                                 :else acc)))
                       untyped typed)
         in-data (data-args body)]
     (into {} (for [[k t] types] [k (cond-> t (not (in-data k)) nullable)])))))

(defn- using-merges
  "{column {:tables [alias ...] :sides [alias ...] :schema s :nullable? bool}} of a
   statement's JOIN ... USING, each join merging its left side (a table's column, or what an
   earlier join merged) with the right table's in turn: :tables every table merged, :sides
   the tables next.jdbc may key it by (nil: unqualified), :schema nil when the types differ.
   PostgreSQL takes the left column (INNER JOIN the right one when only the left is cast to
   the merged type), RIGHT JOIN the right one, FULL JOIN neither: a COALESCE; a column cast
   to the merged type keeps no table."
  [registry stmt sc schema]
  (let [alias #(second (table-ref % schema))
        joins (for [k join-keys :let [j (get stmt k)] :when (vector? j), [t c] (partition 2 j)] [k (alias t) c])
        col-schema (fn [a column]
                     (when-let [{:keys [table opaque?]} (get sc a)]
                       (when-not opaque? (let [cs (table-columns registry table)] (or (get cs column) (get cs (folded column)))))))
        ;; the type without what does not change it (a default, constraints)
        type-of #(let [s (shape/non-null %)] (if (and (vector? s) (map? (second s))) (assoc s 1 (select-keys (second s) [:pg/type :pg/max-length :min :max])) s))
        table-side (fn [a column] (let [s (col-schema a column)] {:sides [a] :schema s :nullable? (boolean (or (nil? s) (maybe? s) (:nullable? (get sc a))))}))
        merged-with (fn [join l r]
                ;; ponytail: a timestamp's or time's precision is not in the registry, so (3) and (6) read as one type
                (let [same? (and (:schema l) (:schema r) (= (type-of (:schema l)) (type-of (:schema r))))
                      side (case join (:join :inner-join :left-join) l :right-join r nil)]
                  (cond (nil? side) {:sides [nil] :schema (when same? (:schema l)) :nullable? true}
                        same? side
                        ;; one side cast to the merged type: which one, the registry does not say
                        :else {:sides (distinct (concat (if (#{:join :inner-join} join) (concat (:sides l) (:sides r)) (:sides side)) [nil]))
                               :schema nil :nullable? true})))]
    (:merges
     (reduce (fn [{:keys [before] :as acc} [k right c]]
               (-> (if (and (vector? c) (= :using (first c)))
                     (reduce (fn [acc col]
                               (let [col (folded (name col))
                                     earlier (get-in acc [:merges col])
                                     holders (filter #(when-let [e (get sc %)] (has-column? registry e col)) before)
                                     l (cond earlier earlier
                                             (= 1 (count holders)) (table-side (first holders) col)
                                             :else {:sides holders :schema nil :nullable? true})]
                                 (assoc-in acc [:merges col] (assoc (merged-with k l (table-side right col))
                                                                    :tables (conj (vec (or (:tables earlier) holders)) right)))))
                             acc (filter keyword? (rest c)))
                     acc)
                   (update :before conj right)))
             {:before (vec (take-last 1 (map alias (from-items stmt [:from :using])))) :merges {}}
             joins))))

(defn- type-schema
  "A column's schema for a value of the PostgreSQL type t (a name or an alias), as the registry
   writes one; nil for a type types lacks."
  [t]
  (when-let [{n :name s :schema} (some-> t types/entry)]
    (if (vector? s) (into [(first s) (assoc (second s) :pg/type n)] (drop 2 s)) [s {:pg/type n}])))

(def ^:private read-as
  "Types PostgreSQL reads as another, by what reads them: min and max (defined for text, inet,
   oid), and = (varchar has text's, cidr inet's)."
  {:min-max {"character varying" "text" "\"char\"" "text" "name" "text" "cidr" "inet"}
   := {"character varying" "text" "cidr" "inet"}})

(defn- operator-input
  "The type op (:min-max, :=) reads type n as, as read-as says, a reg* type as oid; n itself for
   another."
  [op n]
  (cond (and n (str/starts-with? n "reg")) "oid"
        :else (get-in read-as [op n] n)))

(def ^:private aggregate-types
  "The type sum and avg give for their argument's."
  {"sum" {"smallint" "bigint" "integer" "bigint" "bigint" "numeric" "numeric" "numeric"
          "real" "real" "double precision" "double precision" "interval" "interval"}
   "avg" {"smallint" "numeric" "integer" "numeric" "bigint" "numeric" "numeric" "numeric"
          "real" "double precision" "double precision" "double precision" "interval" "interval"}})

(def ^:private word-types
  "The type of an SQL word, and of a function of no argument."
  {:true "boolean" :false "boolean" :current_timestamp "timestamp with time zone" :current_date "date"
   :current_time "time with time zone" :localtimestamp "timestamp without time zone"
   :localtime "time without time zone" "now" "timestamp with time zone" "count" "bigint"})

(def ^:private predicates
  "Operators and special syntax that make a boolean, which may be NULL where an operand may be."
  (into #{} (map keyword)
        ["=" "<>" "not=" "!=" "<" ">" "<=" ">=" "and" "or" "not" "in" "not-in" "like" "not-like" "ilike"
         "not-ilike" "between" "not-between" "similar-to" "not-similar-to" "regexp" "regex" "~" "~*" "!~" "!~*"]))

(def ^:private json-builders
  "The functions that build a json or jsonb value: [type shape nullable?], shape the value's as a
   column of the type is read under {:read {:json :parsed}} (an object, an array); an aggregate
   is NULL over no row."
  (into {} (for [[t p] [["jsonb" "jsonb"] ["json" "json"]]
                 [f shape nullable?] [["_build_object" :map false] ["_build_array" :sequential false]
                                      ["_agg" :sequential true] ["_object_agg" :map true] ["_object" :map false]]]
             [(str p f) [t shape nullable?]])))

(defn- expression-type
  "{:schema column-schema :nullable? bool} of a select expression of a form
   docs/queries.md#the-row-a-query-returns lists; nil for another."
  [registry chain x opts]
  (let [h (when (vector? x) (first x))
        fname (or (called x) (shorthand-fn x))
        ts (map #(expression-type registry chain % opts) (when (vector? x) (rest x)))
        typed #(when % {:schema (type-schema %)})]
    (cond
      ;; HoneySQL sends a string or number as a parameter, unless :inline writes it into the SQL
      ;; an untyped string is text
      (string? x) (assoc (typed (let [t (jdbc/sent-type x opts)] (if (= :unknown t) "text" t))) :string? true)
      (boolean? x) (typed "boolean")
      (number? x) (let [t (jdbc/sent-type x opts)] (when-not (= :unknown t) (typed t)))
      (keyword? x) (or (typed (word-types (sql-value x)))
                       (when (shorthand-fn x) (typed (word-types fname)))
                       ;; a subquery's or a CTE's column has the type its SELECT gives, an opaque table's none
                       (when-let [{s :schema :keys [nullable?]} (when (column-keyword? x) (resolve-column registry chain x))]
                         (when s {:schema (shape/non-null s) :nullable? (boolean (or nullable? (maybe? s)))})))
      (not (keyword? h)) nil
      ;; [:inline v] writes v into the SQL: a string an unknown literal, a number by its value
      (= :inline h) (let [v (second x)]
                      (cond (string? v) (assoc (typed "text") :string? true)
                            (boolean? v) (typed "boolean")
                            ;; HoneySQL writes 1M and 1N as 1, an integer literal to PostgreSQL
                            ;; and a ratio as a division of integers
                            (ratio? v) (typed "integer")
                            (and (number? v) (re-matches #"-?\d+" (str v)))
                            (typed (let [n (bigint v)] (cond (<= Integer/MIN_VALUE n Integer/MAX_VALUE) "integer" (<= Long/MIN_VALUE n Long/MAX_VALUE) "bigint" :else "numeric")))
                            (number? v) (typed "numeric")))
      ;; [:lift v] sends v as a parameter as it is, a keyword or a vector too
      (= :lift h) (let [v (second x)] (when-not (or (keyword? v) (coll? v)) (expression-type registry chain v opts)))
      (= :cast h) (let [[_ v t] x
                        ;; [:varchar 10], a type with its typmod, as HoneySQL writes varchar(10)
                        t (if (and (vector? t) (keyword? (first t))) (first t) t)]
                    (when-let [s (or (when (keyword? t) (type-schema (str/lower-case (str/replace (name t) "-" " "))))
                                     ;; an enum type or a domain of the registry, read as a column of it is
                                     (when-let [[k] (registry-type registry t opts)] [:ref {:pg/type (name t)} k]))]
                      {:schema s :nullable? (if-let [m (expression-type registry chain v opts)] (boolean (:nullable? m)) true)}))
      (#{:exists :not-exists :is :is-not :is-distinct-from :is-not-distinct-from} h) (typed "boolean")
      ;; one operand: ~ is bitwise NOT
      (and (predicates h) (or (= :not h) (next ts))) (assoc (typed "boolean") :nullable? (not-every? #(and % (not (:nullable? %))) ts))
      (= :->> h) (assoc (typed "text") :nullable? true)
      (#{"now" "count"} fname) (typed (word-types fname))
      (json-builders fname) (let [[t shape nullable?] (json-builders fname)]
                              {:schema (if (= :map shape) [:map {:pg/type t}] [:sequential {:pg/type t} :any]) :nullable? nullable?})
      ;; min and max of a varchar are text: PostgreSQL has them for text alone
      (#{"min" "max"} fname) (when-let [t (first ts)]
                               (let [n (canonical-type t)]
                                 {:schema (loose (if (= n (operator-input :min-max n)) (:schema t) (type-schema (operator-input :min-max n)))) :nullable? true}))
      (aggregate-types fname) (some-> (get-in aggregate-types [fname (some-> (first ts) canonical-type)]) typed (assoc :nullable? true))
      ;; a string or an argument takes the others' type, as PostgreSQL resolves a varchar or unknown one
      ;; [:case c1 v1 c2 v2 ... :else v]: the values resolve as coalesce's arguments; NULL without :else
      (= :case h) (let [parts (partition-all 2 (rest x))
                        values (map second parts)
                        else? (some #(= :else (first %)) parts)
                        vts (map #(expression-type registry chain % opts) values)]
                    (when-let [r (when (every? #(= 2 (count %)) parts) (result-type values vts))]
                      {:schema (loose (beyond-list registry (:schema r)))
                       :nullable? (or (not else?) (boolean (some #(or (nil? %) (:nullable? %)) vts)))}))
      ;; nullif returns its first argument as the = operator reads it: of both of one type, that
      ;; type as = reads it (a varchar's = is text's); otherwise = may promote it to the other's
      ;; a string literal takes the other's type
      (= "nullif" fname) (let [typed (remove :string? ts) base (if (seq typed) typed ts)]
                           (when (and (= 2 (count ts)) (every? some? ts) (apply = (map canonical-type base)))
                             {:schema (loose (type-schema (operator-input := (canonical-type (first base))))) :nullable? true}))
      (resolving-fns fname) (when-let [r (result-type (rest x) ts)]
                              ;; the others' values join a CHECK's list: its base type
                              {:schema (cond-> (:schema r) (next ts) (->> (beyond-list registry) loose))
                               ;; greatest and least skip NULLs as coalesce does; nullif makes one
                               :nullable? (or (= "nullif" fname) (not-any? #(and % (not (:nullable? %))) ts))}))))

(defn- select-row
  "The row a SELECT returns. Under a qualifying builder, a column through an opaque table, or a cast
   column, has a key this cannot tell: the driver keys it by the table it comes from at the
   bottom (a subquery's, a CTE's), or keeps it when PostgreSQL drops a cast to the column's own
   type. The row leaves those out, an open map, and names them under :pgmalli/untold."
  [registry stmt chain {:keys [schema] :or {schema default-schema} :as opts}]
  (let [registry (reading registry)
        qualified? (:qualified? (jdbc/row-keys opts))
        sc (first chain)
        ;; ROLLUP, CUBE and GROUPING SETS add rows whose grouped columns are NULL; :raw or code
        ;; there may be one, and a join :join-by builds (read nowhere here) may be outer
        regrouped? (or (contains? stmt :join-by)
                       (let [g (:group-by stmt)]
                         (or (seq? g)
                             (some #(or (seq? %) (and (vector? %) (#{:rollup :cube :grouping-sets :raw} (first %))))
                                   (vectorish g)))))
        ;; table nil: an expression comes from no table, and the driver keys it unqualified
        entry (fn [table column schema nullable? props]
                (jdbc/row-entry (jdbc/row-key table column opts) props
                                ;; ::columns: a derived table's columns, as the registry's are, for its outer statement
                                (cond (nil? schema) :any (::columns opts) (shape/non-null schema) :else (driver-type registry jdbc/read-type schema opts))
                                (or nullable? regrouped? (nil? schema) (maybe? schema)) opts))
        untold (fn [n] [[::untold n]])
        merges (using-merges registry stmt sc schema)
        merged (fn [label m]
                 (if (and qualified? (some #(:opaque? (get sc %)) (:tables m)))
                   (untold label)
                   (let [{:keys [sides nullable?] s :schema} m
                         by-key (into {} (for [a sides :let [t (some-> a sc :table)]] [(jdbc/row-key t label opts) t]))
                         ;; keys the driver may give it: each optional
                         props (when (next by-key) {:optional true})]
                     (for [[_ t] by-key] (entry t label s nullable? props)))))
        star (fn [col]
               (let [[a] (split-column col)
                     tables (if a (some-> (get sc a) vector) (vals sc))]
                 (when (and (seq tables) (every? #(and % (not (:opaque? %))) tables))
                   (if a
                     (for [{:keys [table nullable?]} tables, [c s] (column-entries registry table)] (entry table c s nullable? nil))
                     ;; SELECT * lists a column USING merges once
                     (concat (mapcat (fn [[c m]] (merged c m)) merges)
                             (for [[alias {:keys [table nullable?]}] sc, [c s] (column-entries registry table)
                                   :when (not (some #{alias} (:tables (get merges c))))]
                               (entry table c s nullable? nil)))))))
        items (for [item (selected-items stmt)
                    :let [[col alias] (select-parts item opts)]]
                (cond
                  (nil? col) (when-let [n (item-name item opts)]
                               (if (and qualified? (nil? alias) (cast-of-column? (item-expression item)))
                                 (untold n)
                                 (let [{s :schema :keys [nullable?]} (expression-type registry chain (if alias (first item) (item-expression item)) opts)]
                                   [(entry nil n s nullable? nil)])))
                  (star? col) (star col)
                  :else (when-let [{:keys [table column schema nullable? opaque?]} (resolve-column registry chain col)]
                          (let [label (if alias (alias-label alias opts) column)
                                m (when-not (first (split-column col)) (get merges (folded column)))]
                            (cond m (merged label m)
                                  (and qualified? opaque?) (untold label)
                                  ;; a column under an alias is still keyed by its table: the driver reads the table from the field
                                  :else [(entry table label schema nullable? nil)])))))]
    (when (and (seq items) (every? some? items) (not-any? :unknown? (vals sc)))
      ;; a name selected twice is one key, the driver keeping the last column's value
      (let [entries (apply concat items)
            untold (vec (distinct (keep #(when (= ::untold (first %)) (keyword (second %))) entries)))
            entries (remove #(= ::untold (first %)) entries)
            last-of (into {} (map (juxt first identity)) entries)]
        (into (cond-> [:map] (seq untold) (conj {:pgmalli/untold untold})) (map last-of) (distinct (map first entries)))))))

(defn- set-operation-row [registry members path chains opts]
  (when (and (vector? members) (seq members) (every? map? members))
    (let [rows (map-indexed (fn [i m] (row-schema* registry m (conj path i) chains (assoc opts ::jdbc/member true))) members)]
      (when (and (every? some? rows) (apply = (map count rows)))
        (into [:map]
              (for [entries (apply map vector (map rest rows))
                    :let [schemas (map last entries)
                          inners (distinct (map shape/non-null schemas))]]
                (jdbc/row-entry (jdbc/tableless-key (ffirst entries) opts) (if (= 1 (count inners)) (first inners) :any) (some maybe? schemas) opts)))))))

(defn- row-schema*
  "row-schema of the statement at path in a query, chains its statements' scope chains by path
   (statement-chains), which alone say which tables and CTEs a statement sees."
  [registry stmt path chains opts]
  (if-let [k (some #(when (contains? stmt %) %) set-operation-keys)]
    (set-operation-row registry (get stmt k) (conj path k) chains opts)
    (select-row registry stmt (get chains path) opts)))

(defn- derived-types*
  [registry stmt opts outer]
  (let [opts (-> opts (dissoc :jdbc) (assoc ::jdbc/member true ::columns true))
        chains (into {} (map (fn [[_ c p]] [p c])) (statement-chains registry stmt opts outer))]
    (when-let [row (row-schema* registry stmt [] chains opts)]
      (vec (for [[k _ s] (map shape/entry-parts (shape/entries row))]
             [(name k) (when-not (= :any s) s)])))))

(defn- derived-types
  "[[column schema] ...] of the row stmt, a query of its own, returns, as the registry types a
   column (nil for one it cannot tell); nil when its row cannot be told."
  [registry stmt opts]
  (if-let [memo (::derived registry)]
    (let [k [stmt (dissoc opts :jdbc)]]
      (if (contains? @memo k)
        (@memo k)
        (let [v (derived-types* registry stmt opts ())] (swap! memo assoc k v) v)))
    (derived-types* registry stmt opts ())))

(defn row-schema
  "The [:map ...] of one row a query returns, as next.jdbc builds it (for a write with no
   RETURNING, its update count); nil when that cannot be told, as
   docs/queries.md#the-row-a-query-returns lists.

   Columns an outer join may leave NULL are nullable; an expression is typed for the forms
   docs/queries.md#the-row-a-query-returns lists, nullable :any otherwise; a set operation's row
   is its first member's names. Under qualified keys, a column next.jdbc keys
   by a table the data does not show (one through a subquery or a CTE, a cast column) is left
   out of the map, which is open, and named under the :pgmalli/untold property. Options:
   docs/queries.md#options."
  ([registry body] (row-schema registry body {}))
  ([registry body opts]
   (jdbc/options! opts)
   (query! body)
   (when (map? body)
     (if-let [body (sent body opts)]
       (row-schema* registry body [] (into {} (map (fn [[_ c p]] [p c])) (statement-chains (reading registry) body opts)) opts)
       update-count))))

(defn params-schema
  "The [:map ...] of sql/format's :params for a query: every named argument, typed as arg-types
   types it, is required, as sql/format refuses a missing one; one that takes nil is
   [:maybe ...]. Options: docs/queries.md#options.

   (params-schema registry {:select [:id] :from [:users] :where [:= :email :?email]})
   ;; => [:map [:email [:string {:pg/type \"character varying\"}]]]"
  ([registry body] (params-schema registry body {}))
  ([registry body opts]
   (into [:map] (sort-by (comp str key) (arg-types registry body opts)))))

(def ^:private aggregate-fns
  #{"count" "sum" "avg" "min" "max" "bool_and" "bool_or" "every" "string_agg" "array_agg"
    "json_agg" "jsonb_agg" "json_object_agg" "jsonb_object_agg"})

(defn- constant?
  "Whether x is the same value on every row: an argument, a literal (NULL too), or a cast of one."
  [x]
  (or (arg-key x) (nil? x) (number? x) (string? x) (boolean? x) (uuid? x)
      (and (vector? x) (= :cast (first x)) (constant? (second x)))))

(def ^:private row-count-keys
  "The clauses row-count reads, and those that change no count."
  (into #{:select :select-distinct :from :where :group-by :having :order-by :limit :offset :with :returning
          :insert-into :columns :values :on-conflict :on-constraint :do-nothing :do-update-set :update :set :delete-from :for}
        join-keys))

(defn- plain-value?
  "Whether x yields one value per row and names no function, which may return a set
   (generate_series, unnest) and so more rows: a column, a constant, DEFAULT, a cast of one."
  [x]
  ;; :%f.a is HoneySQL's f(a)
  (or (and (keyword? x) (not (shorthand-fn x))) (constant? x) (nil? x) (= [:default] x)
      (and (vector? x) (#{:inline :lift :param :cast} (first x)) (plain-value? (second x)))))

(defn- row-count
  "{:min n :max m} of the rows a statement returns where its data tells, nil otherwise; where
   any part may be a function, which may return a set, it tells nothing. One of a SELECT of
   aggregates of columns or constants alone (no GROUP BY, HAVING or OFFSET); at most one of a SELECT,
   UPDATE or DELETE on one table (no CTE of its name, no table inheriting it) whose WHERE holds
   its primary key's or a (whole) UNIQUE constraint's columns equal to a value (NULL equals
   none); up to as many as :values has rows of an INSERT returning (a trigger or ON CONFLICT
   may skip one); at most :limit, from none where a parameter or code gives the limit."
  [registry stmt {:keys [schema] :or {schema default-schema}}]
  ;; a clause this does not read (:join-by, :fetch, a set operation ...) may change the count
  (when (and (map? stmt) (every? row-count-keys (keys stmt)) (not (code-sources? stmt)))
    (let [write? (some #(contains? stmt %) write-keys)
          items (map bare (selected-items stmt))
          order (map bare (vectorish (:order-by stmt)))
          aggregates-only? (every? #(aggregate-fns (called %)) items)
          rows (when (and (:insert-into stmt) (vector? (:values stmt)) (not (:select (insert-parts stmt)))) (:values stmt))
          plain? (and (every? plain-value? (concat order (mapcat #(cond (map? %) (vals %) (sequential? %) % :else [%]) rows)))
                      (or (every? plain-value? items)
                          ;; aggregates over plain values
                          (and aggregates-only? (every? #(every? plain-value? (rest %)) items))))
          targets (if write? (write-targets stmt) (table-items stmt))
          [table alias written] (when (= 1 (count targets)) (table-ref (first targets) schema))
          ;; a CTE of the table's name hides it
          table (when-not (and written (contains? (query-ctes stmt) (folded written))) table)
          where (:where stmt)
          equal (set (for [c (if (and (vector? where) (= :and (first where))) (rest where) [where])
                           :when (and (vector? c) (= := (first c)) (= 3 (count c)))
                           [a b] [[(second c) (nth c 2)] [(nth c 2) (second c)]]
                           :when (and (column-keyword? a) (constant? b))
                           :let [[q col] (split-column a)]
                           :when (or (nil? q) (= q alias))]
                       (folded col)))
          props (when table (table-props registry table))
          ;; a partial UNIQUE index holds over some rows alone; a DEFERRABLE key may be checked only at commit
          key-columns (cond-> (map :columns (remove #(or (:where %) (:deferrable %)) (:pg/unique props)))
                        (and (:pg/primary-key props) (not (:pg/primary-key-deferrable props))) (conj (:pg/primary-key props)))
          unique? (and (not (:pg/inherited props)) (some #(every? equal %) key-columns))
          limit (let [n (:limit stmt)] (when (and (integer? n) (<= 0 n)) n))
          ;; a limit only a parameter or code tells may be 0
          unbounded-limit? (and (contains? stmt :limit) (not limit))
          aggregates? (and (or (:select stmt) (:select-distinct stmt)) (seq items) (not-any? #(contains? stmt %) [:group-by :having :offset])
                           aggregates-only?)
          counted (cond (not plain?) nil
                        aggregates? {:min 1 :max 1}
                        (and rows (:returning stmt)) {:min 0 :max (count rows)}
                        (and unique? (not (:insert-into stmt)) (or (not write?) (:returning stmt)) (every? plain-value? items)) {:min 0 :max 1})
          m (cond-> counted
              limit (update :max #(min (or % limit) limit))
              (and limit (not counted)) (assoc :min 0)
              unbounded-limit? (assoc :min 0))]
      (when (and plain? (:max m)) (cond-> m (< (:max m) (:min m 0)) (assoc :min (:max m)))))))

(defn query-schema
  "[:=> [:cat arg ...] [:sequential row]], the malli function schema of a function taking args
   and running body: the args' types from arg-types (:any for a key the query lacks), the
   row from row-schema (:any when it cannot be resolved), [:sequential {:max n} row] where the
   data tells how many rows (docs/queries.md#function-schemas): under {:result :one} no
   [:maybe] where it tells one.

   (query-schema registry [:db :id] {:select [:email] :from [:users] :where [:= :id :?id]})

   The problems of check, and a query argument args lack (:missing-arg), are the schema's
   :pgmalli/problems property, not an error: the vector is then one longer, so read its parts
   with m/children. The return is what execute! returns on a datasource or connection, under
   {:result :one} what execute-one! returns. Options: docs/queries.md#options. Throws when args are
   not keywords or symbols."
  ([registry args body] (query-schema registry args body {}))
  ([registry args body {:keys [result] :as opts}]
   (jdbc/options! opts)
   (query! body)
   (when-not (and (sequential? args) (every? #(or (keyword? %) (symbol? %)) args))
     (error/fail! :pgmalli.error/invalid-options
                  (str "query-schema takes the function's args first, keywords or symbols ([:db :id]), not " (pr-str args))
                  {:given args}))
   (let [registry (reading registry)
         types (arg-types registry body opts)
         problems (into (check registry body opts)
                          (for [k (sort-by str (data-args body)) :when (and (contains? types k) (not (some #{k} args)))] {:kind :missing-arg :arg k}))
         row (row-schema registry body opts)]
     (into (cond-> [:=>] (seq problems) (conj {:pgmalli/problems problems}))
           [(into [:cat] (map #(get types % :any)) args)
            (let [{:keys [min max]} (row-count registry body opts)]
              (cond (nil? row) :any
                    (= :one result) (if (or (= update-count row) (= 1 min)) row [:maybe row])
                    max [:sequential (cond-> {:max max} (pos? min) (assoc :min min)) row]
                    :else [:sequential row]))]))))

;;; what an application writes

(defn- written-key
  "The key of column (a keyword, or a string where the registry keys it so) in a :values or :set
   map that HoneySQL writes back to column under sql/format's opts (the format): of the key :jdbc's
   builder reads column as (unqualified), the keyword, the column's name as a string, the first
   it writes as column (unquoted, PostgreSQL folds a name to lower case)."
  [column {:keys [format] :as opts}]
  (let [n (name column)
        fmt (honeysql 'honey.sql/format)
        candidates (distinct [(jdbc/row-key nil n opts) (keyword n) n])
        writes (fn [k]
                 (let [sql (first (fmt {:insert-into :t :values [{k 1}]} (or format {})))
                       col (second (re-find #"\((.*?)\)" sql))]
                   (if-let [[_ q] (re-matches #"[\"`\[](.*)[\"`\]]" col)] q (str/lower-case col))))]
    (if fmt
      (or (some #(when (= n (writes %)) %) candidates) (if (string? column) n (keyword n)))
      (if (string? column) n (keyword n)))))

(defn- without-keywords
  "t without its keyword values: HoneySQL writes a keyword in :values or :set as a column."
  [t]
  (case (shape/head t)
    :maybe [:maybe (without-keywords (last t))]
    :and (shape/update-base t without-keywords)
    :or (shape/either (remove #(= :keyword (shape/kind %)) (map without-keywords (shape/alternatives t))) t)
    :enum (let [[h & r] t
                [p & labels] (if (map? (first r)) r (cons nil r))]
            (into (cond-> [h] p (conj p)) (remove keyword? labels)))
    t))

(defn- one-parameter
  "t without the Clojure vectors and sequences in it: HoneySQL writes one in :values or :set as
   a row of parameters, (?, ?), which no column takes; a set, or a Java array, is one."
  [t]
  (case (shape/head t)
    :maybe [:maybe (one-parameter (last t))]
    :or (shape/either (remove #(#{:vector :sequential} (shape/head %)) (map one-parameter (shape/alternatives t))) t)
    t))

(defn- written-row
  "The [:map ...] of the derived schema (\"insert\", \"update\") of the table name names (a
   registry row name, or a table keyword as HoneySQL names it, in :schema): each column typed as
   an argument assigned to it, optional as the derived schema has it, keyed as HoneySQL writes it
   back to the column (written-key); one the database may fill takes :default too, and one it
   writes :default alone. update takes a non-empty map: HoneySQL writes an empty :set as SET
   alone, which PostgreSQL refuses."
  [registry name derived what opts]
  (jdbc/options! opts)
  (when-not (keyword? name)
    (error/fail! :pgmalli.error/invalid-options
                 (str what " takes a table as a keyword, as HoneySQL names it (:users, :app.users) or its row schema (:pg.public/users); got " (pr-str name))
                 {:given name}))
  (let [r (reading registry)
        ;; a registry name (:pg.app/users), or a mistyped one (:pg.public.users) as it is, for schema-of to
        ;; name the one meant; a table as HoneySQL names it (:users, :app.users) qualified in :schema
        name (if (str/starts-with? (subs (str name) 1) "pg.")
               name
               (shape/table-key (qualified name (:schema opts default-schema))))
        s (shape/schema-of registry name)
        dn (shape/derived-name name derived)]
    (cond (shape/row-schema? s) nil
          (shape/read-row? s) (error/fail! :pgmalli.error/unknown-schema
                                           (str (pr-str name) " is a view's row schema, and a view has no " derived " schema; " what " takes a table's")
                                           {:given name})
          :else (shape/not-a-row! registry name what))
    (let [written (shape/column-entries
                   (or (get (::schemas r) dn)
                       (error/fail! :pgmalli.error/unknown-schema
                                    (str "the registry has no " derived " schema " (pr-str dn) " for " (pr-str name) "; pgmalli.core/registry derives it")
                                    {:given dn})))
          key #(written-key % opts)
          default (into [:enum] (sort-by vector? (default-values (:format opts))))
          ;; the columns the database may fill: optional in what an INSERT may carry
          fills (set (for [[k props] (shape/column-entries (get (::schemas r) (shape/derived-name name "insert"))) :when (:optional props)] k))
          triggered? (filled-by-trigger? (shape/row-props s) (keyword derived))
          m (into [:map {:closed true}]
                  (concat
                   ;; one the database may fill takes HoneySQL's :default (DEFAULT) too
                   (for [[k props c] written
                         :let [t (cond-> (one-parameter (without-keywords (accepted-type r c :assign opts))) (or (maybe? c) triggered?) nullable)
                               t (if (fills k) [:or t default] t)]]
                     (if (or (:optional props) triggered?) [(key k) {:optional true} t] [(key k) t]))
                   ;; one the database writes (generated, identity ALWAYS) takes it alone
                   (for [[k] (shape/column-entries s) :when (not-any? #(= k (first %)) written)]
                     [(key k) {:optional true} default])))]
      (if (= "update" derived) [:and m [:map-of {:min 1} :any :any]] m))))

(defn values-schema
  "The [:map ...] of one row an INSERT's :values may carry into a table, named as HoneySQL names
   it (:users in :schema) or by its row schema (:pg.public/users), typed as arg-types types an
   argument: each column as an argument assigned to it, keyed as HoneySQL writes it back to the
   column (docs/queries.md#a-row-written-into-a-table); those the database may fill (a default,
   identity BY DEFAULT, NULL) optional and taking HoneySQL's :default too, those it writes
   (generated, identity ALWAYS) taking :default alone. Options: docs/queries.md#options.

   (values-schema registry :users)"
  ([registry name] (values-schema registry name {}))
  ([registry name opts] (written-row registry name "insert" "values-schema" opts)))

(defn set-schema
  "The schema of the map an UPDATE's :set may carry into a table: as values-schema, every column
   optional, at least one.

   (set-schema registry :users)"
  ([registry name] (set-schema registry name {}))
  ([registry name opts] (written-row registry name "update" "set-schema" opts)))

;;; problems as text

(defn- column-name [c] (if (keyword? c) (subs (str c) 1) (str c)))

(def ^:private explanations
  "The sentence for each kind of problem: what is wrong, then what to do."
  {:unknown-table (fn [{:keys [table did-you-mean]}]
                   (str "unknown table " table
                        (if did-you-mean
                          (str "; did you mean " (str/join " or " did-you-mean) "?")
                          "; check its name, or :schema when it is in another schema")))
   :unknown-column (fn [{:keys [column did-you-mean]}]
                     (str "unknown column " (column-name column)
                          (if did-you-mean (str "; did you mean " (str/join " or " (map column-name did-you-mean)) "?")
                              "; no table of the statement has it")))
   :ambiguous-column (fn [{:keys [column candidates]}]
                       (str "column " (column-name column) " is in " (str/join " and " candidates) "; qualify it with the table's alias"))
   :missing-required-column (fn [{:keys [table column]}]
                              (str "INSERT into " table " lacks " (column-name column) ", which is NOT NULL with no default"))
   :values-arity (fn [{:keys [row columns values]}]
                   (str ":values row " row " has " values " values for " columns " :columns; give each row as many values as :columns"))
   :enum-literal (fn [{:keys [column value allowed]}]
                   (str (pr-str value) " is no value of " (column-name column) "; it takes " (str/join ", " (map pr-str allowed))))
   :uncast-string (fn [{:keys [column value arg type]}]
                    (let [what (if arg (str "argument " arg) (pr-str value))]
                      (str what " reaches column " (column-name column) " as a typed string, which its type " type " does not take"
                           " (HoneySQL sends a string as a parameter, typed as :send sends it: varchar under pgjdbc's default);"
                           " write [:cast " (if arg (str ":?" (name arg)) (pr-str value)) " "
                           (if (str/ends-with? type "[]") (str "[:raw " (pr-str type) "]") (str ":" type))
                           "], or pass {:send {String :unknown}}"
                           " when the connection sets stringtype=unspecified")))
   :not-null-violation (fn [{:keys [table column]}] (str "NULL written into " table "." (column-name column) ", which is NOT NULL"))
   :read-only-column (fn [{:keys [table column]}]
                       (str table "." (column-name column) " is written by the database (generated, or identity ALWAYS);"
                            " leave it out, or write :default"))
   :unread-clause (fn [{:keys [clause]}] (str "clause " clause " is not read by pgmalli, so what it does is not checked; check the clause name, or check that part by hand"))
   :missing-arg (fn [{:keys [arg]}] (str "the query takes argument " arg ", which the function's args lack; a typo in args?"))})

(defn humanize
  "A sentence for each problem of check (or of query-schema's :pgmalli/problems): what is wrong
   and what to do.

   (is (= [] (check registry q)) (humanize (check registry q)))"
  [problems]
  (mapv (fn [{:keys [kind in] :as p}]
          (str (if-let [f (explanations kind)] (f p) (pr-str p)) (when in (str " (in the statement at " in ")"))))
        problems))

(defn schema-problems
  "[problem ...] of the :malli/schema of every var in the namespaces (all loaded ones by
   default), each with its :var: query-schema's :pgmalli/problems, which a schema carries
   rather than throws, so a namespace still loads. One test asserts there are none:

   (is (= [] (schema-problems)))"
  ([] (schema-problems {}))
  ([{:keys [ns] :or {ns (all-ns)}}]
   (vec (for [n (if (sequential? ns) ns [ns])
              [_ v] (sort-by key (ns-interns (the-ns n)))
              :let [s (:malli/schema (meta v))
                    props (cond (satisfies? malli.core/Schema s) (malli.core/properties s)
                                (and (vector? s) (map? (second s))) (second s))]
              p (:pgmalli/problems props)]
          (assoc p :var v)))))
