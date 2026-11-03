(ns pgmalli.honeysql
  "HoneySQL query data checked against a registry, without a database (check): the tables and
   columns a query names must exist, an INSERT must carry the required columns, and NULL, enum
   literals and strings must be ones the column takes. From the same data, the types of the
   query's arguments, of the rows it returns and of a row written into a table, as malli
   schemas.

   Names resolve as PostgreSQL resolves them (docs/queries.md#what-a-name-refers-to). Types are
   what next.jdbc and pgjdbc return, and what :send says the application sends. The functions
   take the options of docs/queries.md#options; a key or value not among them is an error."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            malli.core
            [pgmalli.impl.accept :as accept]
            [pgmalli.impl.error :as error]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.portable :as portable]
            [pgmalli.impl.resolve :as resolve]
            [pgmalli.impl.shape :as shape]
            [pgmalli.impl.types :as types]))

;;; tables and columns of the registry

(defn- folded
  "An identifier as PostgreSQL reads what HoneySQL writes of it by default, unquoted: - is _,
   and the case folded. Only where the name as it is matches nothing (a quoted mixed-case one
   matches as it is)."
  [s]
  (some-> s (str/replace "-" "_") str/lower-case))

(defn- last-part
  "The last part of a dotted name (:public.users, \"pg_catalog.now\"), folded."
  [x]
  (folded (last (str/split (name x) #"\."))))

(declare type-schema)

(defn- versions
  "The versions of the catalog snapshot the registry's generated files are of: each file's
   PostgreSQL major, every version the snapshot holds where a file's is none of them, or there
   is no file."
  [schemas]
  (let [all (:versions @types/catalog)
        vs (for [[k s] schemas :when (and (keyword? k) (= (namespace (shape/routines-key "")) (namespace k)))]
             (some->> (:pg/database-version (second s)) str (re-find #"^PostgreSQL (\d+)") second))]
    (if (and (seq vs) (every? (set all) vs)) (vec (distinct vs)) all)))

(defn- in-versions
  "Overloads or columns xs of the catalog snapshot as of versions vs: one of none of them
   left out, :in narrowed to vs, and left out where it holds them all."
  [xs vs]
  (for [x xs :let [in (when (:in x) (filter (set (:in x)) vs))] :when (or (nil? (:in x)) (seq in))]
    (if (or (nil? (:in x)) (= (count in) (count vs))) (dissoc x :in) (assoc x :in (vec in)))))

(defn- catalog-columns
  "[[column-name schema props] ...] of a table or view of pg_catalog (\"pg_catalog.pg_class\"),
   as the catalog snapshot holds it in versions vs: a column of a type they disagree on, or
   pgmalli does not type, :any; nullable where one version's is (an :any too: a NULL is a key
   an optional builder leaves out); optional where one lacks it."
  [table vs]
  (when-let [cs (some->> table (re-matches #"pg_catalog\.(.+)") second (get (:relations @types/catalog)))]
    (let [cs (in-versions cs vs)
          by-name (group-by :name cs)]
      (for [c (distinct (map :name cs))
            :let [es (by-name c)
                  ts (distinct (map :type es))
                  s (when-not (next ts) (type-schema (first ts)))]]
        [c (if (every? :not-null es) (or s :any) [:maybe (or s :any)])
         (when (and (every? :in es) (not= (count vs) (count (distinct (mapcat :in es))))) {:optional true})]))))

(defn- column-entries
  "[[column-name schema props] ...] of a table (or view) in the registry, in its order, or of
   pg_catalog's; nil when it is not there."
  [registry table]
  (or (some->> (get (::schemas registry) (shape/table-key table)) shape/column-entries (map (fn [[k _ s]] [(name k) s])))
      (catalog-columns table (::versions registry))))

(def ^:private system-columns
  "The columns every table has besides its own."
  {"ctid" [:some {:pg/type "tid"}] "xmin" [:some {:pg/type "xid"}] "xmax" [:some {:pg/type "xid"}]
   "cmin" [:some {:pg/type "cid"}] "cmax" [:some {:pg/type "cid"}] "tableoid" [:int {:pg/type "oid"}]})


(declare own-routines)

(defn- reading
  "The registry as this namespace reads it: its schemas taken once (a composite registry merges
   them on every lookup), the columns of a table memoized, since a column is looked for in
   every table in scope, and the schemas' own functions and operators read once. The public
   functions take a registry and make one of it; one passed on again is left as it is."
  [registry]
  (if (::schemas registry)
    registry
    (let [schemas (shape/schemas-of registry)
          r {::schemas schemas ::versions (versions schemas) ::derived (atom {})}]
      (assoc r
             ::table-columns (memoize #(some->> (or (column-entries r %) (column-entries r (folded %))) (map (fn [[c s]] [c s])) (into system-columns)))
             ::routines (delay (own-routines r))))))

(defn- table-columns [registry table] ((::table-columns registry) table))

(defn- table-name
  "The table's name of \"schema.table\"."
  [t]
  (if-let [i (str/last-index-of t ".")] (subs t (inc i)) t))

;;; statements, CTEs and scope

(def ^:private set-operation-keys [:union :union-all :intersect :except :except-all])
(def ^:private with-keys [:with :with-recursive])
(def ^:private write-keys [:insert-into :update :delete-from :truncate])
(def ^:private statement-keys
  (into #{:select :select-distinct :select-distinct-on :select-top :select-distinct-top}
        (concat write-keys set-operation-keys)))
(def ^:private default-schema "public")
(defn- with-defaults [opts] (merge {:schema default-schema} opts))
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

(defn- joins
  "[join-key item condition] of every join of a statement, in join-keys' order."
  [stmt]
  (for [k join-keys :let [j (get stmt k)] :when (vector? j), [t c] (partition 2 j)] [k t c]))

(defn- table-items
  "The table items of a statement's FROM (and USING, CROSS JOIN) and joins."
  [stmt]
  (concat (from-items stmt from-keys) (map second (joins stmt))))

(declare insert-parts star? arg-key loose expression-type accepted-type driver-type quoted? call-parts reached own-type-schema type-call registry-type)

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
                                  (map second (joins stmt))))
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

(defn- unqualified-name
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
    (keyword? x) [(qualified x schema) (last-part x) (unqualified-name x)]
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
        (and (keyword? a) (or (nil? b) (alias? b) (vector? b))) [(qualified a schema) (if (alias? b) (alias-name b) (last-part a)) (unqualified-name a) listed]
        (keyword? a) [nil (folded (name a)) nil listed]
        ;; [[:unnest ...] [:t {:columns [:x]}]]: t.f reads as f(t), the function on a scalar result,
        ;; so a function's alias is not held to the columns it lists
        (alias? b) [nil (alias-name b) nil (when-not (vector? a) listed)]
        ;; [[:with-ordinality [:f args...] alias]]: f WITH ORDINALITY alias, named after the function
        ;; without one
        (and (nil? b) (vector? a) (= :with-ordinality (first a)))
        (let [[_ f al] a]
          [nil (cond (alias? al) (alias-name al)
                     (and (vector? f) (keyword? (first f))) (last-part (first f))
                     :else "with_ordinality")
           nil])
        ;; [[:f args...]]: a table function, named after the function
        (and (nil? b) (vector? a) (keyword? (first a))) [nil (last-part (first a)) nil]
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

(defn- join-base
  "The alias of the item the joins attach to: the last FROM item, a comma binding looser than
   JOIN."
  [stmt schema]
  (take-last 1 (map #(second (table-ref % schema)) (from-items stmt [:from :using]))))

(defn- outer-aliases
  "The aliases an outer join may leave NULL: a LEFT JOIN's table, every table before a RIGHT
   JOIN's, both sides of a FULL (and :outer-join) one."
  [stmt schema]
  (let [alias #(second (table-ref % schema))]
    (:nullable
     (reduce (fn [{:keys [before] :as acc} [k t]]
               (let [a (when t (alias t))]
                 (-> (case k
                       :left-join (update acc :nullable conj a)
                       :right-join (update acc :nullable into before)
                       (:outer-join :full-join) (update acc :nullable into (cond-> before a (conj a)))
                       acc)
                     (update :before conj a))))
             {:before (vec (join-base stmt schema)) :nullable #{}}
             ;; a RIGHT or FULL JOIN code builds nulls the tables before it all the same
             (concat (joins stmt)
                     (for [k [:right-join :full-join :outer-join] :when (seq? (get stmt k))] [k nil]))))))

(defn- unnest-elements
  "{column argument} of a FROM item unnest(?, ...) of arguments: the column each argument's
   elements become, by position in the columns its alias lists ([:x {:columns [:a]}]), or the
   alias itself of an unnest of one ([[:unnest :?ids] :id])."
  [item]
  (when (and (vector? item) (vector? (first item)) (= :unnest (ffirst item)))
    (let [ps (rest (first item))
          b (second item)
          cs (cond (and (vector? b) (map? (second b))) (:columns (second b))
                   (and (alias? b) (= 1 (count ps))) [b])]
      (when (and (seq ps) (every? arg-key ps) (sequential? cs))
        (not-empty (into {} (map (fn [p c] [(folded (name c)) p]) ps cs)))))))

(defn- scope
  "{alias {:table \"schema.table\" :opaque? bool :cte? bool :nullable? bool}} of one statement;
   :elements {column argument} of an unnest of arguments (unnest-elements);
   ctes are the query's CTE names, which shadow a table only when the reference is written
   without a schema. :nullable? marks a table an outer join may leave NULL."
  [registry stmt ctes {:keys [schema]}]
  (let [registry (reading registry)
        refs (concat (from-items stmt from-keys)
                     (map second (joins stmt))
                     (write-targets stmt))
        nullable (outer-aliases stmt schema)
        ;; UPDATE, DELETE FROM and INSERT INTO name a table, which a CTE of the same name does not hide
        targets (set (write-targets stmt))
        using (set (for [[_ _ j] (joins stmt) :when (and (vector? j) (= :using (first j))) c (rest j) :when (keyword? c)]
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
                            (nullable alias) (assoc :nullable? true)
                            (unnest-elements r) (assoc :elements (unnest-elements r)))]))]
    ;; ON CONFLICT: EXCLUDED is the row proposed for insertion, with the target's columns
    (if-let [[_ alias] (when (contains? stmt :on-conflict) (some-> (insert-target stmt) (table-ref schema)))]
      (let [target (assoc (get sc alias) :excluded? true)] (assoc sc "excluded" target))
      sc)))

(defn- lateral?
  "Whether a FROM or join item sees the items before it: LATERAL, or a function call, which is
   implicitly LATERAL."
  [item]
  (and (vector? item) (or (= :lateral (first item)) (and (vector? (first item)) (keyword? (ffirst item))))))

(declare row-schema* derived-types derived-types* nullable honeysql beyond-list alias-label)

(defn- cte-statement
  "[statement columns recursive?] of CTE name: its body as read on its own, under the entries of
   its :with before it, and the columns its name lists ([[:c {:columns [:x]}] ...]); of a
   recursive one (a set operation under :with-recursive one of whose members reads the CTE
   itself), its first member, whose types PostgreSQL gives the columns. nil where the query defines name more than once, or the body is
   no statement."
  [body name]
  (let [found (for [x (tree-seq branch? seq body) :when (map? x)
                    k with-keys :let [w (get x k)] :when (vector? w)
                    [i e] (map-indexed vector w) :when (= name (cte-name e))]
                [(second e) (subvec w 0 i) (when (vector? (first e)) (:columns (second (first e)))) k])]
    (when-let [[[stmt before listed k]] (when (= 1 (count found)) found)]
      (let [set-op (when (and (= :with-recursive k) (map? stmt)) (some #(when (vector? (get stmt %)) %) set-operation-keys))
            ;; recursive when a member reads the CTE itself; otherwise its members resolve together
            self? (some (fn [m] (some #(let [w (nth (table-ref % default-schema) 2)] (and w (= name (folded w))))
                                      (mapcat table-items (statements m))))
                        (when set-op (get stmt set-op)))
            op (when self? set-op)
            stmt (if op (first (get stmt op)) stmt)]
        (when (and (map? stmt) (not-any? #(contains? stmt %) with-keys) (or (nil? listed) (sequential? listed)))
          [(cond-> stmt (seq before) (assoc k before)) (map #(folded (clojure.core/name %)) listed) (boolean op)])))))

(defn- function-columns
  "[[column schema origin] ...] of a function of pg_catalog or of a generated schema in FROM
   ([[:f args] alias], [[:with-ordinality [:f args] alias]]): its OUT columns (a generated
   schema's: its composite type's too), or one of its result type named after
   the alias (the function without one), each nullable and of no table, renamed by the names the
   alias lists; WITH ORDINALITY adds ordinality, a bigint. nil where that is not told."
  [registry item chain opts]
  (let [[a b] (if (vector? item) item [item])
        [f ordinality? al] (if (and (vector? a) (= :with-ordinality (first a))) [(second a) true (get a 2)] [a false b])
        [al listed] (if (and (vector? al) (map? (second al))) [(first al) (:columns (second al))] [al nil])
        [kind n args :as parts] (when (vector? f) (call-parts f))]
    (when (and (= :functions kind) (or (nil? listed) (and (sequential? listed) (every? ident? listed))))
      (when-let [{t :result o :overload} (resolve/call kind n (mapv #(:type (expression-type registry chain % opts)) args) (reached registry opts parts))]
        (let [cs (or (seq (:columns o)) (when-not (= "record" t) [[(if (alias? al) (alias-label al opts) n) t]]))
              cs (cond-> (vec cs) ordinality? (conj ["ordinality" "bigint"]))
              own #(own-type-schema registry % (:schema o))]
          (when (and (seq cs) (every? #(or (types/category (second %)) (own (second %))) cs))
            (vec (map-indexed (fn [i [c ct]]
                                [(if (< i (count listed)) (folded (name (nth listed i))) c)
                                 (when-let [s (or (when (string? ct) (type-schema ct)) (own ct))] (cond->> s (not= "ordinality" c) (vector :maybe)))
                                 ""])
                              cs))))))))

(defn- standalone
  "Statement inner as a query of its own: itself where it reads no CTE of ctes (those its place
   in query body sees), else under the entries of the one :with of body that define them, up to
   the last; nil where they are not there, or inner has a :with of its own."
  [body inner ctes schema]
  (let [defined (cte-names inner)
        read (set (for [i (mapcat table-items (statements inner))
                        :let [[_ _ written] (table-ref i schema)]
                        :when (and written (not (defined (folded written)))
                                   (or (contains? ctes (folded written)) (contains? ctes ::code-ctes)))]
                    (folded written)))]
    (if (empty? read)
      inner
      (let [found (for [x (tree-seq branch? seq body) :when (map? x)
                        k with-keys :let [w (get x k)] :when (vector? w)
                        [i e] (map-indexed vector w) :when (read (cte-name e))]
                    [w k i])]
        (when (and (= (count read) (count found)) (= read (set (map (fn [[w _ i]] (cte-name (nth w i))) found)))
                   (apply = (map first found)) (apply = (map second found))
                   (not-any? #(contains? inner %) with-keys))
          (let [[w k] (first found)]
            (assoc inner k (subvec w 0 (inc (apply max (map #(nth % 2) found)))))))))))

(defn- typed-scope
  "sc with :types ({column schema}, a column whose type is not told nil) and :origins ({column
   table}, the table PostgreSQL traces the column to, \"\" none; one it does not tell absent) on
   the subqueries and CTEs of stmt whose SELECT types its columns, read as a query of their own,
   under the CTEs of ctes it reads (standalone)."
  [registry body stmt sc ctes outer {:keys [schema] :as opts}]
  (let [items (table-items stmt)]
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
                    ;; a CTE's body comes under the entries before it already: the CTEs it reads beyond them are outer ones
                    own (when inner (let [o (standalone body inner ctes schema)] (when (or (not cte?) (identical? o inner)) o)))
                    ts (cond inner
                             (when own
                               (if lateral
                                 ;; a LATERAL subquery sees the tables before it
                                 (derived-types registry own opts (cons (dissoc sc alias) outer))
                                 (derived-types registry own opts)))
                             (and entry (nil? inner) (nil? table) (not cte?) (vector? item) (not (map? (first item))))
                             ;; a function sees the tables before it too
                             (function-columns registry item (cons (dissoc sc alias) outer) opts))
                    named (map-indexed (fn [i [c t o]] [(nth listed i c) t o]) ts)
                    ;; a CTE's name may list new names for its columns, by position; a recursive
                    ;; one's later rows may hold NULL where its first member's do not
                    types (when ts (into {} (for [[c t] named] [c (if (and t recursive?) (nullable (beyond-list registry t)) t)])))
                    ;; a recursive CTE's columns come from a UNION: from no table
                    origins (into {} (for [[c _ o] named :when (or recursive? o)] [c (if recursive? "" o)]))]
                (cond-> sc types (update alias assoc :types types :origins origins :order (mapv first named)))))
            sc items)))

(def ^:private clause-rank
  (delay (let [order (some-> (honeysql 'honey.sql/clause-order) (apply []))]
           (zipmap order (range)))))

(defn- clause-entries
  "The entries of statement x in the order sql/format writes its clauses (others after, by
   name): what comes first in the SQL comes first here, whatever order the map holds."
  [x]
  (sort-by (fn [[k]] [(get @clause-rank k Long/MAX_VALUE) (str k)]) x))

(defn- clause-parts
  "[path part sees] for the clauses of statement x but :with, in the order sql/format writes
   them, path where part sits in x: what each part sees, decided here alone. :own, the
   statement's tables and the enclosing ones (its conditions, select, assignments); :outer, only
   the enclosing ones (INSERT ... SELECT, the rows of :values and a table item of :from or a
   join: none sees the statement's own tables); :lateral, those and the tables before it (a
   LATERAL item, a function)."
  [x]
  (for [[k v] (clause-entries x) :when (not (some #{k} with-keys))
        part (cond (#{:insert-into :values} k) [[[k] v :outer]]
                   (and (vector? v) (some #{k} from-keys))
                   (map-indexed (fn [i t] [[k i] t (if (lateral? t) :lateral :outer)]) v)
                   (and (vector? v) (some #{k} join-keys))
                   (map-indexed (fn [i t] [[k i] t (cond (odd? i) :own (lateral? t) :lateral :else :outer)]) v)
                   (some #{k} from-keys) [[[k] v :outer]]
                   ;; code builds the joins: their conditions, which see the statement's tables, are in it
                   :else [[[k] v :own]])]
    part))

(defn- part-chain
  "The chain of scopes a part (clause-parts) of a statement of chain sees, outer the chain of the
   statements enclosing it. A LATERAL item sees its statement's tables, but not itself."
  [sees part chain outer schema]
  (case sees
    :own chain
    :outer outer
    :lateral (cons (dissoc (first chain) (second (table-ref part schema))) (rest chain))))

(defn- statement-chains
  "[[statement scopes path outer] ...] for the statements of a query, scopes the statement's own
   scope followed by those of the statements enclosing it (outer), path where it sits in the
   query. What a part of a statement sees is clause-parts'; the body of a CTE sees the
   enclosing statements, a :with entry the entries before it, a :with-recursive one all of
   them. A statement found inside a vector
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
                        chain (cons (vary-meta (typed-scope registry body x (scope registry x ctes opts) ctes outer opts) assoc ::ctes ctes ::query body)
                                     (cond->> outer (code-sources? x) (cons :open)))]
                    (cons [x chain path outer]
                          (concat
                           (for [k with-keys :let [w (get x k)] :when (some? w)
                                 found (if (vector? w)
                                         (apply concat (map-indexed (fn [i e] (walk e (conj path k i) outer (into base (keep cte-name) (if (= :with-recursive k) w (take i w))) false true)) w))
                                         (walk w (conj path k) outer ctes false true))]
                             found)
                           (for [[ks v sees] (clause-parts x)
                                 found (walk v (into path ks) (part-chain sees v chain outer (:schema opts)) ctes false true)]
                             found))))
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
  "[[alias table opaque? nullable? types origins elements] ...] of the tables of one scope a column may belong to."
  [registry scope col]
  (let [[alias column] (split-column col)]
    (if alias
      (when-let [{:keys [table opaque? nullable? types origins elements] :as entry} (get scope alias)]
        (when (has-column? registry entry column) [[alias table opaque? nullable? types origins elements]]))
      ;; an unqualified column is never EXCLUDED's: that row is only reached through its name
      (distinct (for [[a {:keys [table opaque? excluded? nullable? types origins elements] :as entry}] scope
                      :when (and (not excluded?) (has-column? registry entry column))]
                  [a table opaque? nullable? types origins elements])))))

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
  "{:table :column :schema :nullable? :opaque? :origin} for a column keyword in a scope (or the chain of
   scopes of a nested statement), :table the alias for an opaque table, :schema the type a
   subquery's or CTE's SELECT gives the column (nil for an opaque table without one), :origin
   the table its SELECT takes it from as PostgreSQL tells the driver (\"\" none, nil not told),
   :nullable? when an outer join may leave the table NULL, :element the argument whose elements
   an unnest's column is; nil when the column resolves to no
   table or to several known ones. A column an opaque table may hold besides one known table is
   the opaque one's, untyped: which of them PostgreSQL takes is not in the data."
  [registry scope col]
  (let [registry (reading registry)
        hits (column-hits registry scope col)
        column (second (split-column col))
        [opaque & _] (filter #(nth % 2) hits)]
    (cond (= 1 (count hits))
          (let [[alias table opaque? nullable? types origins elements] (first hits)
                ;; a subquery's or a CTE's columns, as its SELECT types them
                cs (or types (when-not opaque? (table-columns registry table)))
                ;; the column's own name, as next.jdbc keys the row: :group-id is group_id
                column (if (or (nil? cs) (contains? cs column)) column (folded column))]
            {:table (or table alias) :column column
             :schema (get cs column)
             :nullable? (boolean nullable?) :opaque? (boolean opaque?) :origin (get origins column)
             :element (get elements (folded column))})
          (and opaque (>= 1 (count (remove #(nth % 2) hits))))
          (let [[alias table] opaque] {:table (or table alias) :column column :schema nil :nullable? true :opaque? true}))))

;;; what a statement selects, compares and assigns

(def ^:private sql-value-types
  "The type of an SQL word (docs 9.9.5, 9.27)."
  {:true "boolean" :false "boolean" :current_timestamp "timestamp with time zone" :current_date "date"
   :current_time "time with time zone" :localtimestamp "timestamp without time zone"
   :localtime "time without time zone" :current_user "name" :session_user "name" :current_role "name"
   :user "name" :current_schema "name" :current_catalog "name" :system_user "text"})

(def ^:private nullable-sql-values
  "The SQL words that may be NULL: CURRENT_SCHEMA under a search_path of no existing schema,
   SYSTEM_USER under trust authentication (docs 9.27)."
  #{:current_schema :system_user})

(def ^:private sql-values
  "Keywords HoneySQL writes as SQL words, not columns."
  (into #{:null :default :*} (keys sql-value-types)))

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
(def ^:private distinct-ops
  "IS [NOT] DISTINCT FROM, which compares NULL as a value."
  #{:is-distinct-from :is-not-distinct-from})
(def ^:private comparison-ops
  (into #{:= :<> :not= :!= :< :> :<= :>=} (concat distinct-ops in-ops like-ops)))
;; a value outside a CHECK's list makes these match no row; the others compare the column's base
;; type, which takes any value
(def ^:private equality-ops #{:= :in :is-not-distinct-from})
(def ^:private negated-op
  {:= :<> :<> := :not= := :!= := :in :not-in :not-in :in
   :is-not-distinct-from :is-distinct-from :is-distinct-from :is-not-distinct-from})

(defn- comparison?
  "A comparison node: [op a b] of comparison-ops, or [:between a b c]."
  [x]
  (and (vector? x) (or (and (= 3 (count x)) (comparison-ops (first x)))
                       (and (= 4 (count x)) (#{:between :not-between} (first x))))))

(defn- target-column
  "A column as HoneySQL writes it where a statement assigns it: without its namespace."
  [c]
  (if (and (keyword? c) (namespace c)) (keyword (name c)) c))

(defn- field-column
  "The column of an assignment target :a.b, a field of the composite column a, as PostgreSQL
   reads SET a.b and INSERT INTO t (a.b); nil for another target."
  [c]
  (when (and (keyword? c) (nil? (namespace c)))
    (let [[a & fields] (str/split (name c) #"\.")]
      (when fields (keyword a)))))

(defn- to-field
  "A write [column value ...] to a field as one to its column, its value ::untold: the field's
   type is not in the registry."
  [[c & more :as w]]
  (if-let [a (field-column c)] (into [a ::untold] (rest more)) w))

(defn- value-rows
  "The maps of :values as HoneySQL writes them under the statement's column list: only the
   listed keys."
  [stmt columns]
  (let [maps (map #(update-keys % target-column) (filter map? (when (vector? (:values stmt)) (:values stmt))))]
    (if columns (map #(select-keys % (map target-column columns)) maps) maps)))

(defn- writes
  "[column value event clause] of what a statement writes: :set, ON CONFLICT DO UPDATE SET (a
   map of assignments, or the columns taken from EXCLUDED, valued ::untold: a vector of them,
   or {:fields [...] :where ...}), and the rows of :values, maps or positional rows under the
   columns listed (:values may be a symbol, rows passed in, saying nothing about the columns).
   Under missing, a column one map of :values lacks and another has is written (missing column)."
  ([stmt] (writes stmt nil))
  ([stmt missing]
   (let [{:keys [columns]} (insert-parts stmt)
         do-update (:do-update-set stmt)
         maps (value-rows stmt columns)
         union (distinct (mapcat keys maps))]
     (map to-field
      (concat (when (map? (:set stmt)) (for [[c v] (:set stmt)] [(target-column c) v :update :set]))
              (for [[c v] (cond (and (map? do-update) (contains? do-update :fields)) (for [c (vectorish (:fields do-update))] [c ::untold])
                                (map? do-update) (for [[c v] do-update] [(target-column c) v])
                                (vector? do-update) (for [c do-update] [c ::untold]))]
                [c v :update :do-update-set])
              (for [row maps, c (if (or columns (nil? missing)) (keys row) union)]
                [c (if (contains? row c) (get row c) (missing c)) :insert :values])
              (when (and columns (vector? (:values stmt)))
                (for [row (filter vector? (:values stmt)), [c v] (map vector columns row)] [c v :insert :values])))))))

(defn- target-scope
  "The scope an assigned column is resolved in: the table of :update / :insert-into alone."
  [scopes stmt schema]
  (let [[_ alias] (some-> (or (:update stmt) (insert-target stmt)) (table-ref schema))]
    (select-keys (first (chain scopes)) [alias])))

;;; the expression positions of a statement

(defn- unnest-of-arguments?
  "Whether a FROM item is unnest(?, ...) of arguments alone."
  [item]
  (boolean (and (vector? item) (vector? (first item)) (= :unnest (ffirst item)) (every? arg-key (rest (first item))))))

(defn- may-drop-rows?
  "Whether SELECT stmt may return no row where its arguments make one: it filters, groups,
   limits, deduplicates or combines rows, or reads a table. unnest(?) of arguments drops none:
   an empty array makes no row its other arguments go to."
  [stmt]
  (not (and (every? #{:select :from :order-by} (keys stmt))
            (every? unnest-of-arguments? (from-items stmt [:from])))))

(defn- positions
  "The expression positions of statement stmt, one read for check and arg-types: {:x node
   :clause k :chain scopes :negated? bool :top? bool} for every node of every clause sql/format writes, in
   its order, as a walk of each clause's value meets them, not descending into the statements nested in it, scopes the chain clause-parts says the node is read in,
   negated? under an odd number of :not through :and and :or alone ([:not [:= :a 1]] is a <> 1;
   CASE or a function's argument under NOT is not negated), top? for the clause's value itself
   (one of :values, :set, an item of FROM or a join). A value written to a column is
   besides {:x value :assign column :event e :target scope :may-drop? bool}: e the write
   (:insert, :update), scope the target table's, may-drop? where the row may not reach the
   column. feed, for the SELECT of an INSERT ... SELECT, is {:columns :target} of its INSERT:
   its items go to those columns by position."
  [stmt chain outer feed {:keys [schema]}]
  (let [nodes (fn [k x c]
                (letfn [(walk [x neg? top?]
                          (lazy-seq
                           (cons {:x x :clause k :chain c :negated? neg? :top? top?}
                                 (when (and (branch? x) (not (statement? x)))
                                   (let [neg? (case (when (vector? x) (first x)) :not (not neg?) (:and :or) neg? false)]
                                     (mapcat #(walk % neg? false) (seq x)))))))]
                  (walk x false true)))
        target (target-scope chain stmt schema)
        written (group-by #(nth % 3) (writes stmt))
        assigned (fn [clause c]
                   (for [[col v event] (written clause) :when (not= ::untold v)]
                     {:x v :assign col :event event :target target :may-drop? false :chain c :clause clause}))]
    (concat
     (for [[[k] v sees] (clause-parts stmt)
           :let [c (part-chain sees v chain outer schema)]
           p (case k
               (:insert-into :columns) nil
               :values (concat (for [row (when (vector? v) v), x (cond (map? row) (vals row) (vector? row) row)] (nodes k x c))
                               [(assigned :values c)])
               :set (concat (map #(nodes k % c) (when (map? v) (vals v))) [(assigned :set c)])
               :do-update-set (concat (map #(nodes k % c) (cond (and (map? v) (contains? v :fields)) (remove nil? [(:where v)])
                                                                (map? v) (vals v)))
                                      [(assigned :do-update-set c)])
               [(nodes k v c)])
           e p]
       e)
     ;; a star or :raw makes columns the data does not count
     (let [items (map bare (selected-items stmt))]
       (when (and (:columns feed) (not-any? #(or (and (keyword? %) (star? %)) (and (vector? %) (= :raw (first %)))) items))
         (for [[col v] (map (comp to-field vector) (:columns feed) items)
               :when (not= ::untold v)]
           {:x v :assign (target-column col) :event :insert :target (:target feed) :may-drop? (may-drop-rows? stmt)
            :chain chain :clause :select}))))))

(defn- expression-nodes [ps] (remove :assign ps))
(defn- assignments [ps] (filter #(column-keyword? (:assign %)) ps))

(defn- comparison-sides
  "[op side other chain] for each side of the comparisons among positions ps (BETWEEN's first,
   against each bound), element by element where a row meets a row of its length ([:<
   [:composite :a :b] [:composite :?a :?b]]): a side is a column, an expression or a value,
   other whatever it is. Under an odd number of :not, op is its negation (= is <>)."
  [ps]
  (for [{:keys [x negated? chain]} (expression-nodes ps)
        :when (comparison? x)
        :let [[op a b c] x
              row? #(and (vector? %) (= :composite (first %)))]
        [side other] (cond (= 4 (count x)) [[a b] [a c]]
                           (and (row? a) (row? b) (= (count a) (count b))) (mapcat (fn [l r] [[l r] [r l]]) (rest a) (rest b))
                           :else [[a b] [b a]])]
    [(if negated? (negated-op op op) op) side other chain]))

(defn- statement-positions
  "[statement scopes path positions] for the statements of query body (statement-chains), the
   SELECT of an INSERT ... SELECT fed the INSERT's columns."
  [registry body opts]
  (let [chains (statement-chains registry body opts)
        feeds (for [[s c] chains :let [{:keys [columns select]} (insert-parts s)] :when (and columns (map? select))]
                [select {:columns columns :target (target-scope c s (:schema opts))}])]
    (for [[s c path outer] chains]
      [s c path (positions s c outer (some (fn [[select f]] (when (identical? select s) f)) feeds) opts)])))

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

(def ^:private registered-op? (delay (honeysql 'honey.sql/registered-op?)))

(defn- operator?
  "Whether HoneySQL writes [h ...] as an operator: a name not an identifier (:->, :@>), one of
   operators, or one the loaded HoneySQL has registered."
  [h]
  (boolean (or (operators h) (not (re-find #"^[A-Za-z_]" (name h)))
               (some-> @registered-op? (#(% h))))))

(defn- raw? [x] (and (vector? x) (= :raw (first x))))

(defn- cast-of-column?
  "Whether x is a cast of a column, or of :raw SQL, which may be one, as-cast reading a call
   as a cast where PostgreSQL does (type-call)."
  [as-cast x]
  (let [x (as-cast x)]
    (and (vector? x) (= :cast (first x))
         (or (column-keyword? (second x)) (raw? (second x)) (cast-of-column? as-cast (second x))))))

(defn- function-name
  "A function's column name: as HoneySQL writes it (- is _), unqualified, as PostgreSQL folds it;
   TRIM is btrim, which PostgreSQL's grammar calls it."
  [s]
  (let [n (last-part s)]
    (if (= "trim" n) "btrim" n)))

(defn- called
  "The function name of a call [:f ...]; nil for anything else."
  [x]
  (when (and (vector? x) (keyword? (first x))) (function-name (name (first x)))))

(defn- expression-name
  "The column name PostgreSQL gives a select expression with no alias: a function's name, case
   (its ELSE's name where that has one of its own), array, a cast column's or function's name,
   ?column? for a value or an operator (an infix one of HoneySQL's, or any whose name is not an
   identifier: :->, :@>, an op registered with register-op!). nil when this cannot tell (a cast
   value or operation is named after its type, a subquery after its column, :raw anything)."
  [x]
  (cond (or (number? x) (string? x) (boolean? x) (nil? x) (param-key x) (#{:null :true :false} (sql-value x))) "?column?"
        (shorthand-fn x) (function-name (shorthand-fn x))
        (keyword? x) (some-> (sql-value x) name)
        (not (and (vector? x) (keyword? (first x)))) nil
        :else (let [[h a] x]
                (cond (= :. h) (some-> (last x) (#(when (keyword? %) (function-name (name %)))))
                      (operator? h) "?column?"
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
        :ref (labels registry (shape/ref-target (::schemas registry) s))
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
  (or (some-> (list-type registry s) type-schema) s))

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
  "Up to three columns of the scope's tables spelled close to col's name, qualified as col is:
   what it likely meant."
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
    (some->> (error/suggestions c names 3) (mapv #(keyword (if a (str a "." %) %))))))

(defn- unknown-qualifier
  "[qualifier meant] when no table or alias in scope has col's qualifier, nil otherwise. meant
   is col's column qualified by the aliases in scope whose table has it and whose alias or table
   name is the qualifier or two edits from it, closest first: an aliased table named by its
   name, as PostgreSQL's \"Perhaps you meant to reference the table alias\", or a misspelt alias."
  [registry scope col]
  (let [[a c] (split-column col)
        scopes (filter map? (chain scope))]
    (when (and a (not (some #{:open} (chain scope))) (not-any? #(contains? % a) scopes))
      (let [c (folded c)
            meant (for [sc scopes
                        [alias {:keys [table order]}] sc
                        :when (or (and table (contains? (table-columns registry table) c)) (some #{c} order))
                        :let [d (apply min (map #(error/edit-distance a %) (remove nil? [alias (some-> table table-name)])))]
                        :when (<= d 2)]
                    [(keyword (str alias "." c)) d])]
        [a (some->> (seq meant) (sort-by second) (map first) distinct (take 3) vec)]))))

(defn- column-problems [registry sc stmt ps {:keys [schema] :as opts}]
  (let [target (target-scope sc stmt schema)
        ;; a target code names is not in the data: its columns are not judged
        assigned (when (seq target) (concat (map #(or (field-column %) %) (filter column-keyword? (:columns (insert-parts stmt))))
                                            (filter column-keyword? (map (comp target-column first) (writes stmt)))))]
    (for [[col scope] (distinct (concat (for [c (keep #(first (select-parts % opts)) (selected-items stmt))] [c sc])
                                        (for [c (ordering-columns registry sc stmt)] [c sc])
                                        (for [[_ c _ chain] (comparison-sides ps) :when (column-keyword? c)] [c chain])
                                        (for [{:keys [x chain]} (expression-nodes ps)
                                              :when (and (vector? x) (= 3 (count x)) (#{:is :is-not} (first x))
                                                         (column-keyword? (second x)))]
                                          [(second x) chain])
                                        ;; a keyword assigned in SET is a column the statement sees
                                        (for [{:keys [x chain clause]} (assignments ps)
                                              :when (and (#{:set :do-update-set} clause) (column-keyword? x))]
                                          [x chain])
                                        (for [c assigned] [c target])))
          :when (if (or (star? col) (whole-row? scope col))
                  ;; :t.* names a table of the scope, unless the data does not show them all
                  (let [[a] (split-column col)]
                    (and (star? col) a (not (some #{:open} (chain scope))) (not-any? #(and (map? %) (contains? % a)) (chain scope))))
                  (nil? (resolve-column registry scope col)))
          :let [hits (column-hits registry scope col)]]
      (if (< 1 (count hits))
        {:kind :ambiguous-column :column col :candidates (mapv (fn [[alias table]] (or table alias)) hits)}
        (let [[qualifier meant] (unknown-qualifier registry scope col)
              near (if qualifier meant (near-columns registry scope col))]
          (cond-> {:kind :unknown-column :column col}
            qualifier (assoc :qualifier qualifier)
            near (assoc :did-you-mean near)))))))

(defn- table-props
  "The properties of the row schema of table (\"schema.table\")."
  [registry table]
  (shape/row-props (get (::schemas registry) (shape/table-key table))))

(defn- filled-by-trigger?
  "Whether a BEFORE row trigger runs, for event (:insert, :update), on the table of row
   properties props: it may fill a column before NOT NULL is checked."
  [props event]
  (boolean (get-in props [:pg/before-triggers event])))

(defn- assigned-column
  "{:schema :table :opaque? :nullable?} of the column an assignment (positions) writes, nil
   :schema where it is not told; nullable? where NULL may go there: the column takes it, a
   BEFORE row trigger for the write may fill it (an INSERT's value is the row proposed, whose
   NOT NULL PostgreSQL checks before ON CONFLICT DO UPDATE), or the row may not reach it."
  [registry {:keys [assign target event may-drop?]}]
  (let [{s :schema :keys [table opaque?] :as c} (resolve-column registry target assign)]
    (assoc c :nullable? (boolean (or may-drop? (and s (shape/maybe? s))
                                     (and s (not opaque?) (filled-by-trigger? (table-props registry table) event)))))))

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
  [registry scopes stmt {:keys [schema format]}]
  (let [target (target-scope scopes stmt schema)
        i (:insert-into stmt)
        overriding? (and (vector? i) (map? (first i)) (#{:system :user} (:overriding-value (first i))))
        defaults (set (:values-default-columns format))]
    (when (seq target)
      (distinct
       (for [[col v how clause] (writes stmt #(when (defaults %) [:default]))
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
        spelt #(if (str/includes? % " ") (first (:aliases (types/entry %))) %)]
    (or (enum-type registry s)
        (when e
          (cond (str/ends-with? (:pg/type p) "[]") (str (spelt (:name e)) "[]")
                (:cast e) (spelt (name (:cast e)))
                (not= :string (:schema e)) (spelt (:name e)))))))

(defn- slot-type
  "The type of a column schema as pgmalli.impl.accept names it: [:enum name] for an enum type
   (or a domain over one), a domain's base type, \"x[]\" for an array; nil for a type it does
   not answer for."
  [registry s]
  (let [p (shape/column-props s)
        d (shape/ref-target (::schemas registry) (shape/non-null s))
        t (if-let [e (types/entry (shape/type-name p))]
            (str (:name e) (when (str/ends-with? (str (:pg/type p)) "[]") "[]"))
            ;; enum-type's d is an enum type, or a domain over one
            (when-let [n (enum-type registry s)] (cond-> [:enum n] (not= :enum (shape/head d)) (conj {:domain? true}))))]
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

(def ^:private array-comparisons
  "ANY(a), SOME(a) and ALL(a), the right operand of an operator over a's elements (docs 9.24.3)."
  #{:any :some :all})

(defn- array-of-elements?
  "Whether x is one of array-comparisons."
  [x]
  (and (vector? x) (= 2 (count x)) (array-comparisons (first x))))

(defn- side-schema
  "The column schema of one side of a comparison: a column's, or an expression's where
   expression-type tells it; nil otherwise."
  [registry sc side opts]
  (cond (column-keyword? side) (:schema (resolve-column registry sc side))
        ;; x op ANY(array) compares x with the array's elements (docs 9.24.3)
        (array-of-elements? side) (some-> (expression-type registry sc (second side) opts) :type types/element type-schema)
        (vector? side) (:schema (expression-type registry sc side opts))))

(defn- string-problems
  "Literals outside an enum type's labels, compared or assigned, or outside a CHECK's list,
   assigned or under equality-ops; and, where :send sends a String typed (varchar, pgjdbc's
   default), a string HoneySQL sends as a parameter (not inlined, not cast) to a column
   PostgreSQL takes no such string for (enum = varchar does not exist, nor inet = varchar): a
   literal, or an argument to an enum column or to one whose own values are strings."
  [registry ps opts]
  (let [uses (concat (for [[op side value chain] (comparison-sides ps)
                           :when (not (like-ops op))
                           :let [s (side-schema registry chain side opts)]
                           :when (or s (column-keyword? side))]
                       {:col side :value value :op op :schema s})
                     (for [{:keys [assign x target] :as a} (assignments ps) :when (seq target)
                           :let [{s :schema :keys [nullable?]} (assigned-column registry a)]]
                       {:col assign :value x :assigned? true :schema s :nullable? nullable?}))
        sends (jdbc/string-sends opts)
        typed? (not= :unknown (jdbc/sent-type "" opts))]
    (concat
     ;; an argument to an enum type's column, or to one whose own values are strings: a String,
     ;; which goes typed unless :send sends it untyped (stringtype=unspecified)
     (for [{:keys [col value assigned? nullable?] s :schema} uses
           :let [k (param-key value)] :when (and k typed?)
           :let [t (some->> s (uncast-type registry))]
           ;; one assigned where NULL may go may be nil, which PostgreSQL takes
           :when (and t (not (takes-string? registry s assigned? sends)) (not nullable?)
                      (or (enum-type registry s) (some #(= :string (shape/kind %)) (shape/alternatives (driver-type registry jdbc/write-type s opts)))))]
       {:kind :uncast-string :column col :arg k :type t})
     (for [{:keys [col value assigned? op] s :schema} uses
           :let [strings? (or (string? value) (and (vector? value) (every? string? value)))
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
  [registry stmt scope ps {:keys [schema] :as opts}]
  (let [registry (reading registry)
        scopes (chain scope)
        known (for [[k v] (::schemas registry) :when (shape/read-row? v)]
                (str/join "." (shape/schema-and-name k)))]
    (vec (concat (for [[_ {:keys [table unknown?]}] (first scopes) :when unknown?
                       :let [same-name (sort (filter #(= (table-name %) (table-name table)) known))
                             meant (seq (take 3 (distinct (concat same-name (error/suggestions table known 3)))))]]
                   (cond-> {:kind :unknown-table :table table} meant (assoc :did-you-mean (vec meant))))
                 (column-problems registry scopes stmt ps opts)
                 (insert-problems registry stmt schema)
                 (write-problems registry scopes stmt opts)
                 (string-problems registry ps opts)))))

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
                     :when (and (keyword? k) (not (read-clauses k)) (not (ignored-clauses k)))
                     :let [meant (when-not (some-> clauses (contains? k))
                                   (error/suggest k (concat read-clauses ignored-clauses clauses)))]]
                 (cond-> {:kind :unread-clause :clause k} meant (assoc :did-you-mean [meant])))
               ;; HoneySQL takes {select [...]} for {:select [...]}; here a symbol is a value, so
               ;; such a statement is not read, and said so
               (for [m maps :when (and (map? m) (not (rows m)))
                     k (keys m)
                     :when (and (symbol? k) (or (read-clauses (keyword k)) (some-> clauses (contains? (keyword k)))))]
                 {:kind :unread-clause :clause k})))))

(defn- as-vectors
  "x with each list (or other seq) of no symbol and no list in it a vector: HoneySQL reads a
   sequential as it reads a vector, and code is a list with a symbol in it ((vec cols),
   (:cols opts))."
  [x]
  (cond (seq? x) (if (and (seq x) (not-any? #(or (symbol? %) (seq? %)) x))
                   (mapv as-vectors x)
                   (apply list (map as-vectors x)))
        (map? x) (into (empty x) (map (fn [[k v]] [k (as-vectors v)])) x)
        (branch? x) (into (empty x) (map as-vectors) x)
        :else x))

(defn- query!
  "body as HoneySQL query data (as-vectors), when it is that (a map, or code holding maps); an
   error saying so for SQL already formatted or for nothing, which would otherwise check clean."
  [body]
  (when (or (nil? body) (string? body) (and (vector? body) (string? (first body))))
    (error/fail! :pgmalli.error/not-query-data
                 (str "pgmalli.honeysql reads HoneySQL query data (a map such as {:select ...}), not "
                      (if (nil? body) "nil" "SQL already formatted") "; pass the map you give sql/format")
                 {:given body}))
  (as-vectors body))

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
  [registry stmt scopes {{:keys [return-keys]} :jdbc :keys [schema] :as opts}]
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
   (let [body (query! body)
         opts (with-defaults opts)
         registry (reading registry)
         sent-body (or (sent body opts) body)
         chains (statement-positions registry sent-body opts)
         found (mapcat (fn [[stmt scopes path ps]]
                         ;; :in, where a nested statement sits, as get-in reads it (malli's :in)
                         (cond->> (problems registry stmt scopes ps opts) (seq path) (map #(assoc % :in (vec path)))))
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
  ([registry body opts]
   (jdbc/options! opts)
   (let [body (query! body)
         {:keys [schema] :as opts} (with-defaults opts)
         registry (reading registry)
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


(defn- untyped?
  "A type that says nothing: :any, with or without properties."
  [t]
  (or (nil? t) (= :any t) (and (vector? t) (= :any (first t)))))

(defn- nullable [t] (if (or (untyped? t) (shape/maybe? t)) t [:maybe t]))

(defn- array-arg
  "The argument x of [h x] with h one of heads, sent as one array: of array-comparisons by
   default (= ANY(?), <> ALL(?))."
  ([v] (array-arg v array-comparisons))
  ([v heads] (when (and (vector? v) (= 2 (count v)) (heads (first v)) (arg-key (second v))) (second v))))

(defn- assigned-type
  "The type of an argument assignment a (positions) writes to its column: NULL where
   assigned-column says it may go. Where the row may not reach the column, without the CHECKs,
   lengths and ranges PostgreSQL checks only on a row it writes."
  [registry {:keys [may-drop?] :as a} opts]
  (let [{s :schema :keys [nullable?]} (assigned-column registry a)]
    (if s
      (let [inner (cond-> (accepted-type registry (cond->> s may-drop? (beyond-list registry)) :assign opts) may-drop? loose)]
        (if nullable? [:maybe inner] inner))
      :any)))

(defn- compared-arg-type
  "The type of an argument op compares with side, as compared-schema narrows side's schema; :any
   where side's is not told."
  [registry sc side op opts]
  (if-let [s (side-schema registry sc side opts)]
    (accepted-type registry (compared-schema registry s op) :compare opts)
    :any))

(defn- registry-type
  "The registry's key and schema of the enum type or domain a cast names (:mood, :app.mood);
   nil for another name."
  [registry t {:keys [schema]}]
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
            (= :maybe h) [h (loose (last t))]
            ;; an array column's :pg/type is what tells the driver's java.sql.Array from :read's vector
            (#{:vector :sequential :set :pg/array} h) (if-let [pg (:pg/type p)] [h {:pg/type pg} (loose (last t))] [h (loose (last t))])
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
        ;; cast, an array as PostgreSQL casts it (to text, say), its elements any
        cast-arrays (when (= :cast context)
                      (for [[schema q] (jdbc/write-rows opts true) :when (takes? q)
                            a (shape/alternatives (walk/postwalk-replace {:pgmalli/element :any} schema))]
                        [q a]))
        alts (concat
              (for [p sent
                    a (if (= :unknown p)
                        (mapcat (fn [[schema q]] (when (= :unknown q) (shape/alternatives schema))) (jdbc/write-rows opts false))
                        (shape/alternatives (unlabel (loose (driver-type registry jdbc/write-type (type-schema p) opts)))))
                    :when (not (untyped? a))]
                [p a])
              cast-arrays)]
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
            more (remove (set labels) (distinct (mapcat as labels)))
            labelled (if (seq more) (shape/update-base own (constantly (into (cond-> [:enum] props (conj props)) (concat labels more)))) own)
            ;; cast, PostgreSQL converts another kind to the number type first (1.5 to 2, true to 1)
            ;; and checks the list after: those values are not held to the labels here
            converted (when (and (= :cast context) (every? number? labels))
                        (distinct (remove #(#{:int 'integer? :string} (shape/kind %)) (map second alts))))]
        ;; a domain's checks stay around its labels
        (if (seq converted) (into [:or labelled] converted) labelled))
      :else
      (let [own-alts (shape/alternatives own)
            ;; a number held to a number column's range, which PostgreSQL checks after converting it;
            ;; a string's or a date's cannot be said of the value sent
            alts (for [[p a] alts] (cond-> a (and (not= :compare context) (accept/number-type? t) (accept/number-type? p)) (within p t own)))
            ;; a cast's array of any elements is broader than an array of the type's own
            cast-kinds (set (map (comp shape/kind second) cast-arrays))
            all (shape/distinct-kinds (concat (remove (comp cast-kinds shape/kind) own-alts) alts))]
        (if (= all own-alts) own (into [:or] all))))))

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
      (or (and lenient? (not (in-ops op))) (distinct-ops op)) nullable)))

(defn- data-args
  "The arguments the data of a query shows, outside code (a list): code may leave its own out
   at run time, and binds locals of its own, which are no arguments."
  [body]
  (set (keep arg-key (tree-seq #(and (branch? %) (not (seq? %))) seq body))))

(defn- domain-base-type
  "s, or the base type of the domain s refers to (through domains over domains), its :pg/type
   without typmod: the type PostgreSQL resolves a function's argument of a domain to."
  [registry s]
  (let [d (shape/ref-target (::schemas registry) s)
        base (some-> d shape/domain-base)]
    (cond (nil? d) s
          (shape/ref-target (::schemas registry) base)
          (recur registry (shape/merge-props base {:pg/type (second (shape/schema-and-name (last base)))}))
          (:pg/type (shape/column-props base))
          (shape/merge-props base {:pg/type (types/bare-name (:pg/type (shape/column-props base)))})
          :else s)))

(defn- type-name
  "The name of type t as format_type prints it: a known type (timestamptz) by its name, without a
   schema or typmod, an array of one as \"x[]\"; another as it is."
  [t]
  (when (string? t)
    (if-let [e (types/element t)]
      (some-> (type-name e) (str "[]"))
      (:name (types/entry t) t))))

(defn- canonical-type [{s :schema}] (some-> s shape/column-props :pg/type type-name))

(defn- resolver-type
  "The type pgmalli.impl.resolve takes for a value of column schema s: a domain's base type,
   [:enum name] for an enum type; nil where s names none."
  [registry s]
  (when s
    (let [b (domain-base-type registry s)]
      (if-let [n (enum-type registry b)]
        [:enum n]
        (canonical-type {:schema b})))))

(defn- own-type?
  "Whether expression type m is of type t itself, not of a domain over it."
  [m t]
  (and (= t (:type m)) (= (if (vector? t) (second t) t) (canonical-type m))))

(defn- result-schema
  "The schema of a value of resolver type t computed from inputs (expression types): an input's
   where it is of t (a domain's base type, an enum's labels; without a CHECK's list or
   bounds), else t's."
  [registry t inputs]
  (or (some #(let [s (domain-base-type registry (:schema %))]
               ;; a typmod reads otherwise (bit(1) a Boolean), which a value computed from others may not keep
               (when (and (= t (:type %)) (not (str/includes? (str (:pg/type (shape/column-props s))) "(")))
                 (loose (beyond-list registry s))))
            inputs)
      (when (string? t) (type-schema t))))

(defn- common
  "The expression type PostgreSQL resolves expression types ms to, as it resolves UNION, CASE
   and coalesce (pgmalli.impl.resolve/common-type): of one type, the first one's; nil where one
   is not told. An argument takes the others' type where that is a string type: pgjdbc sends a
   number or a date typed, which may widen it beyond the others'."
  [registry ms]
  (when (and (seq ms) (every? :type ms))
    (let [ts (map :type ms)
          t (resolve/common-type ts)
          typed (remove #(#{:unknown :free} (:type %)) ms)]
      (when (and t (or (not-any? #{:free} ts) (= "S" (types/category t))))
        {:type t
         :schema (if (and (seq typed) (apply = (map (juxt :type #(:pg/type (shape/column-props (:schema %)))) typed)) (own-type? (first typed) (:type (first typed))))
                   (cond-> (:schema (first typed)) (next ms) (->> (beyond-list registry) loose))
                   (result-schema registry t typed))}))))

(defn- value-arg
  "[argument array?] of a value where an argument goes: an argument itself, or a column of
   unnest(?) of arguments (array? true: the argument's elements go there); nil for any other."
  [registry chain x]
  (cond (arg-key x) [x false]
        (column-keyword? x) (some-> (:element (resolve-column registry chain x)) (vector true))))

(defn- comparison-args
  "An argument compared with a column or a typed expression, as compared-type types it; an
   unnest's column, its argument an array of that."
  [registry ps opts]
  (for [[op side value chain] (comparison-sides ps)
        :let [x (array-arg value)
              [k elements?] (if x [x false] (value-arg registry chain value))]
        :when (and k (not (and elements? (or (in-ops op) (second (value-arg registry chain side))))))
        :let [t (compared-type op (compared-arg-type registry chain side op opts) (when-not elements? x) opts)]
        :when (not (and elements? (untyped? t)))]
    [k (if elements? (jdbc/array-type t opts :unnest) t)]))

(defn- limit-args
  "LIMIT and OFFSET take a bigint of at least 0 by assignment, NULL for none."
  [registry ps opts]
  (for [{:keys [x clause top?]} (expression-nodes ps)
        :when (and top? (#{:limit :offset} clause) (arg-key x))]
    [x (nullable (accepted-type registry [:int {:pg/type "bigint" :min 0}] :assign opts))]))

(defn- cast-arrays
  "The arrays whose elements PostgreSQL casts to the type of column schema s, elements of
   element type, typed; :any for none."
  [registry s element opts]
  (let [t (slot-type registry s)]
    (shape/either (distinct (for [[sch sent] (jdbc/write-rows opts true)
                                  :when (and t (string? sent) (accept/accepts? t (types/element sent) :cast))]
                              (walk/postwalk-replace {:pgmalli/element element} sch)))
                  :any)))

(defn- cast-args
  "[:cast :?x :timestamptz]: what PostgreSQL casts to the type; nil too unless every use of the
   cast is compared (not under IS [NOT] DISTINCT FROM, not with NULL, not under {:compare
   :lenient}) or assigned where NULL may not go (assigned-column). [:cast :x.a :date] of an
   unnest's column: an array of that, typed."
  [registry ps opts]
  (let [nodes (expression-nodes ps)
        casts (for [{:keys [x chain]} nodes
                    ;; ARRAY[...]::t[] casts each element to t (transformArrayExpr)
                    x (cond (and (vector? x) (= :cast (first x)) (= 3 (count x))) [x]
                            (and (vector? x) (= :array (first x)) (= 3 (count x)) (sequential? (second x)) (keyword? (nth x 2)))
                            (for [e (second x)] [:cast e (nth x 2)])
                            :else (some-> (type-call registry chain x opts) vector))
                    :let [[k elements?] (value-arg registry chain (second x))]
                    :when k]
                [x chain k elements?])
        refusing (frequencies
                  (concat (when-not (= :lenient (:compare opts))
                            ;; each operand of a comparison once: BETWEEN's bounds, IN's items too
                            (for [{:keys [x]} nodes
                                  :when (comparison? x)
                                  :let [[op & operands] x]
                                  :when (not (or (distinct-ops op)
                                                 (some #(null-value? % (:format opts)) operands)))
                                  o operands
                                  o (if (and (in-ops op) (vector? o) (not (keyword? (first o)))) o [o])]
                              o))
                          (for [a (assignments ps)
                                :let [{c :schema :keys [nullable?]} (assigned-column registry a)]
                                :when (and c (not nullable?))]
                            (:x a))))
        uses (frequencies (map first casts))]
    (for [[x chain k elements?] (distinct casts)
          :let [s (:schema (expression-type registry chain x opts))]
          :when s
          :let [t (cond-> (accepted-type registry s :cast opts)
                    (< (get refusing x 0) (uses x)) nullable)]]
      [k (if elements? (cast-arrays registry s t opts) t)])))

(defn- assignment-args
  "An argument assigned to a column; unnest(?), of a typed array (unnest(unknown) is ambiguous),
   or an unnest's column, each element assigned to it."
  [registry ps opts]
  (for [{:keys [x chain] :as a} (assignments ps)
        :let [t (assigned-type registry a opts)
              u (array-arg x #{:unnest})
              [k elements?] (if u [u true] (value-arg registry chain x))]
        :when k]
    [k (if elements? (jdbc/array-type t opts :unnest) t)]))

(defn- resolving-operands
  "The operands x has PostgreSQL resolves to one type: coalesce's, greatest's, least's,
   nullif's, CASE's values."
  [x]
  (cond (#{"coalesce" "greatest" "least" "nullif"} (called x)) (rest x)
        (and (vector? x) (= :case (first x)) (even? (count (rest x)))) (map second (partition 2 (rest x)))))

(defn- resolving-args
  "coalesce(?, col), CASE ... ELSE col and the like: PostgreSQL resolves an argument to the
   others' type; nil is a value there."
  [registry ps opts]
  (for [{:keys [x chain]} (expression-nodes ps)
        :let [operands (resolving-operands x)]
        :when (some param-key operands)
        :let [r (common registry (map #(expression-type registry chain % opts) (remove arg-key operands)))]
        :when (:schema r)
        o operands
        :when (param-key o)]
    ;; the others' values join a CHECK's list: its base type
    [o (nullable (accepted-type registry (beyond-list registry (:schema r)) :compare opts))]))

(defn- call-args
  "An argument of a function or an operator: of the types the candidates PostgreSQL may resolve
   the call to take there, as a value of each type sent picks one; nil may go there too."
  [registry ps opts]
  (for [{:keys [x chain]} (expression-nodes ps)
        :let [[kind n args :as parts] (when-not (or (comparison? x) (type-call registry chain x opts)) (call-parts x))]
        :when (and kind (some arg-key args))
        :let [ins (mapv #(:type (expression-type registry chain % opts)) args)
              os (reached registry opts parts)]
        [i a] (map-indexed vector args)
        :when (arg-key a)
        :let [ss (map type-schema (resolve/argument-types kind n ins i os))]
        :when (and (seq ss) (every? some? ss))]
    [a (nullable (if (next ss)
                   ;; a value of each type, which a :pg/type of one would misname
                   (shape/either (shape/distinct-kinds (mapcat #(map unlabel (shape/alternatives (accepted-type registry % :compare opts))) ss)) :any)
                   (accepted-type registry (first ss) :compare opts)))]))

(defn- both-uses
  "The type of an argument two of its uses type a and b: PostgreSQL types a parameter once, so
   a value goes where each use takes it; a use that types nothing takes any. nil where either
   takes it (an IS DISTINCT FROM, a nullable column it is assigned to)."
  [a b]
  (let [nil? (or (shape/maybe? a) (shape/maybe? b))
        [a b] (map shape/non-null [a b])
        ;; every alternative of x is one of y's, but for the :pg/type it names
        within? (fn [x y] (every? (set (map unlabel (shape/alternatives y))) (map unlabel (shape/alternatives x))))
        t (cond (untyped? a) b
                (untyped? b) a
                (within? b a) b
                (within? a b) a
                :else (into [:and] (sort-by pr-str [a b])))]
    (cond-> t nil? nullable)))

(defn arg-types
  "{arg schema} for the named parameters of a query (:?id, [:param :id]), keyed as sql/format's
   :params takes them (:id).

   (arg-types registry {:select [:id] :from [:users] :where [:= :email :?email]})
   ;; => {:email [:string {:pg/type \"character varying\"}]}

   docs/queries.md#the-types-of-the-arguments says where an argument is typed and how. Options:
   docs/queries.md#options."
  ([registry body] (arg-types registry body {}))
  ([registry body opts]
   (jdbc/options! opts)
   (let [body (query! body)
         opts (with-defaults opts)
         registry (reading registry)
         uses [comparison-args limit-args cast-args assignment-args resolving-args call-args]
         typed (for [[_ _ _ ps] (statement-positions registry body opts)
                     f uses
                     use (f registry ps opts)]
                 use)
         ;; an argument no use types, a function's or an operator's, is :any
         untyped (into {} (for [x (tree-seq branch? seq body) :let [k (param-key x)] :when k] [k :any]))
         types (reduce (fn [acc [x t]] (if-let [k (arg-key x)] (update acc k both-uses t) acc))
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
        col-schema (fn [a column]
                     (when-let [{:keys [table opaque?]} (get sc a)]
                       (when-not opaque? (let [cs (table-columns registry table)] (or (get cs column) (get cs (folded column)))))))
        ;; the type without what does not change it (a default, constraints)
        type-of #(let [s (shape/non-null %)] (if (and (vector? s) (map? (second s))) (assoc s 1 (select-keys (second s) [:pg/type :pg/max-length :min :max])) s))
        table-side (fn [a column] (let [s (col-schema a column)] {:sides [a] :schema s :nullable? (boolean (or (nil? s) (shape/maybe? s) (:nullable? (get sc a))))}))
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
             {:before (vec (join-base stmt schema)) :merges {}}
             (for [[k t c] (joins stmt)] [k (alias t) c])))))

(defn- type-schema
  "A column's schema for a value of the PostgreSQL type t (a name or an alias, \"x[]\" an array),
   as the registry writes one; nil for a type types lacks."
  [t]
  (cond
    ;; pgjdbc reads a void value as a PGobject of ""
    (= "void" t) [:any {:pg/type "void"}]
    (types/element t)
    (when-let [{n :name s :schema} (types/entry (types/element t))]
      [:vector {:pg/type (str n "[]")} s])
    :else
    (when-let [{n :name s :schema} (some-> t types/entry)]
      (if (vector? s) (into [(first s) (assoc (second s) :pg/type n)] (drop 2 s)) [s {:pg/type n}]))))

(def ^:private grammar-operators
  "The operators PostgreSQL's grammar writes words as."
  {"LIKE" "~~" "NOT LIKE" "!~~" "ILIKE" "~~*" "NOT ILIKE" "!~~*"})

(def ^:private format-expr (delay (honeysql 'honey.sql/format-expr)))

(defn- operator-name
  "The SQL operator HoneySQL writes [h a b] with (:not= as <>, :like as ~~), or [h a] as a
   prefix one (- a; [:@@ a] is a alone), as the loaded HoneySQL formats it; without HoneySQL,
   h's own name where that is no identifier. nil for SQL syntax that is no operator (AND, IN,
   IS ...) or one PostgreSQL lacks (REGEXP)."
  [h arity]
  (let [sql (if-let [f @format-expr]
              (some->> (try (first (f (if (= 1 arity) [h ::r] [h ::l ::r]))) (catch Exception _ nil))
                       (re-matches (if (= 1 arity) #"(?s)(.+) \S+" #"(?s)\S+ (.+) \S+")) second)
              (get {:not= "<>" :!= "<>" :like "LIKE" :not-like "NOT LIKE" :ilike "ILIKE" :not-ilike "NOT ILIKE"} h (name h)))]
    (or (grammar-operators sql) (when (and sql (re-matches #"[+\-*/<>=~!@#%^&|`?]+" sql)) sql))))

(defn- call-parts
  "[kind name args schema] of x as pgmalli.impl.resolve/call takes it: a function (:functions),
   count(*) of no argument, AT TIME ZONE as timezone(zone, value), or an operator (:operators),
   a prefix one of one operand; schema the one a function's name is qualified with; nil for
   anything else."
  [x]
  (let [h (when (vector? x) (first x))
        fname (or (called x) (shorthand-fn x))
        args (cond (shorthand-fn x) (map keyword (rest (str/split (subs (str x) 2) #"\.")))
                   (vector? x) (rest x))]
    (cond (not (or h fname)) nil
          (= :at-time-zone h) (let [[_ v z] x] [:functions "timezone" [z v]])
          (and (keyword? h) (operator? h) (#{1 2} (count args)))
          (when-let [n (operator-name h (count args))] [:operators n (vec args)])
          (and fname (not (special-syntax h)))
          [:functions fname (vec (remove #{:*} args)) (some-> (if (keyword? x) (shorthand-fn x) (name h)) (str/split #"\.") butlast last folded)])))

(defn- reached
  "The overloads a call (call-parts) reaches: those of the schema it names, else pg_catalog's and
   :schema's, pg_catalog first on the search path as PostgreSQL puts it when the path does not
   name it (docs 5.9.6). A schema the registry has no :functions of (a file generated before
   them, a schema not generated) has none."
  [registry {:keys [schema]} [kind n _ q]]
  (in-versions (resolve/overloads kind n (some-> (::routines registry) deref) (if q [q] ["pg_catalog" schema]))
               (::versions registry)))

(defn- own-type-schema
  "The column schema of a value of an enum type or a domain of the registry, t named as a
   generated file of schema s names it, or as pgmalli.impl.resolve takes an enum type ([:enum
   name]); nil for another type."
  [registry t s]
  (let [n (if (vector? t) (second t) t)]
    (when-let [[k] (when (string? n) (registry-type registry (keyword n) {:schema s}))]
      [:ref {:pg/type n} k])))

(defn- own-routines
  "{schema {:functions {name [overload ...]} :operators {...}}} of the registry's generated files,
   the argument and result types as pgmalli.impl.resolve takes them (an enum type [:enum name], a
   domain its base type)."
  [registry]
  (into {} (for [[k s] (::schemas registry)
                 :when (and (keyword? k) (= (namespace (shape/routines-key "")) (namespace k)))
                 :let [schema (name k)
                       {fs :pg/functions ops :pg/operators} (second s)
                       resolved #(if-let [r (own-type-schema registry % schema)] (resolver-type registry r) %)
                       each (fn [m] (update-vals m (fn [os] (mapv #(-> % (update :args (partial mapv resolved)) (update :result resolved)) os))))]]
             [schema {:functions (each fs) :operators (each ops)}])))

(def ^:private json-shapes
  "The shape of the json or jsonb value a function builds, as a column of the type is read under
   {:read {:json :parsed}}: an object or an array."
  (into {} (for [p ["json" "jsonb"], [f shape] [["_build_object" :map] ["_build_array" :sequential] ["_agg" :sequential]
                                                ["_object_agg" :map] ["_object" :map]]]
             [(str p f) shape])))

(defn- call-type
  "The expression type of a call (call-parts), its arguments of expression types ts."
  [registry [kind n args :as parts] ts opts]
  (when-let [{t :result o :overload} (let [ins (mapv :type ts) os (reached registry opts parts)]
                                       (if (every? some? ins)
                                         (resolve/call kind n ins os)
                                         ;; of arguments not told, a result no argument decides (count, jsonb_build_object)
                                         (when-let [r (resolve/fixed-result (count ins) os)]
                                           {:result (:result r) :overload (dissoc r :result)})))]
    (let [catalog? (= "pg_catalog" (:schema o))
          schema (if-let [shape (and catalog? (= :functions kind) (#{"json" "jsonb"} t) (json-shapes n))]
                   (if (= :map shape) [:map {:pg/type t}] [:sequential {:pg/type t} :any])
                   (or (result-schema registry t ts) (some->> (:schema o) (own-type-schema registry t))))]
      (when schema
        {:type t :schema schema
         ;; the catalog does not say when a function returns NULL: an aggregate that starts from
         ;; a value (count), a window function of no argument (row_number) and a btree operator
         ;; (a comparison) of values do not
         :nullable? (not (or (:non-null o)
                             (and catalog? (= :w (:kind o)) (empty? args))
                             (and (:btree o) (not-any? :nullable? ts))))}))))

(defn- subquery-type
  "The expression type of scalar subquery stmt in the chain of scopes c (docs 4.2.11): its one
   column's, NULL where it returns no row; nil where that is not told."
  [registry c stmt opts]
  (let [c (chain c)]
    (when-let [stmt (let [{q ::query cs ::ctes} (meta (first c))] (standalone q stmt cs (:schema opts)))]
      (let [[[_ s] more] (derived-types registry stmt opts c)]
        (when (and s (nil? more))
          (let [s (shape/non-null s)] {:schema s :type (resolver-type registry s) :nullable? true}))))))

(defn- expression-type
  "{:schema column-schema :nullable? bool :type t} of an expression, t its type as
   pgmalli.impl.resolve takes it (:unknown for an untyped literal, :free for an argument), as
   docs/queries.md#the-row-a-query-returns says; nil where that is not told."
  [registry chain x opts]
  (let [h (when (vector? x) (first x))
        sub #(expression-type registry chain % opts)
        typed #(when % {:schema (type-schema %) :type %})
        typed-with-nulls (fn [t operands] (assoc (typed t) :nullable? (not-every? #(and % (not (:nullable? %))) (map sub operands))))]
    (cond
      (arg-key x) {:type :free :nullable? true}
      (or (nil? x) (= :null (sql-value x))) {:type :unknown :nullable? true}
      ;; HoneySQL sends a string or number as a parameter, unless :inline writes it into the SQL
      (string? x) (let [t (jdbc/sent-type x opts)] (if (= :unknown t) {:type :unknown :schema (type-schema "text")} (typed t)))
      (boolean? x) (typed "boolean")
      (number? x) (let [t (jdbc/sent-type x opts)] (when-not (= :unknown t) (typed t)))
      (sql-value x) (cond-> (typed (sql-value-types (sql-value x))) (nullable-sql-values (sql-value x)) (assoc :nullable? true))
      (column-keyword? x) (when-let [{s :schema :keys [nullable?]} (resolve-column registry chain x)]
                            ;; a subquery's or a CTE's column has the type its SELECT gives, an opaque table's none
                            (when s {:schema (shape/non-null s) :nullable? (boolean (or nullable? (shape/maybe? s)))
                                     :type (resolver-type registry (shape/non-null s))}))
      (statement? x) (subquery-type registry chain x opts)
      ;; [[f x]], HoneySQL's (f(x))
      (and (vector? h) (= 1 (count x))) (sub h)
      (and (not (keyword? x)) (not (keyword? h))) nil
      ;; [:inline v] writes v into the SQL: a string an unknown literal, a number by its value
      (= :inline h) (let [v (second x)]
                      (cond (string? v) {:type :unknown :schema (type-schema "text")}
                            (boolean? v) (typed "boolean")
                            ;; HoneySQL writes 1M and 1N as 1, an integer literal to PostgreSQL
                            ;; and a ratio as a division of integers
                            (ratio? v) (typed "integer")
                            (and (number? v) (re-matches #"-?\d+" (str v)))
                            (typed (let [n (bigint v)] (cond (<= Integer/MIN_VALUE n Integer/MAX_VALUE) "integer" (<= Long/MIN_VALUE n Long/MAX_VALUE) "bigint" :else "numeric")))
                            (number? v) (typed "numeric")))
      ;; [:lift v] sends v as a parameter as it is, a keyword or a vector too
      (= :lift h) (let [v (second x)] (when-not (or (keyword? v) (coll? v)) (sub v)))
      (= :cast h) (let [[_ v t] x
                        ;; [:varchar 10], a type with its typmod, as HoneySQL writes varchar(10)
                        t (if (and (vector? t) (keyword? (first t))) (first t) t)]
                    (when-let [s (or (when (keyword? t) (type-schema (str/lower-case (str/replace (name t) "-" " "))))
                                     ;; an enum type or a domain of the registry, read as a column of it is
                                     (when-let [[k] (registry-type registry t opts)] [:ref {:pg/type (name t)} k]))]
                      {:schema s :type (resolver-type registry s)
                       :nullable? (if-let [m (sub v)] (boolean (:nullable? m)) true)}))
      ;; INTERVAL '1 day'
      (= :interval h) (typed "interval")
      ;; ARRAY[...], of the elements' common type, or [:array elements type]: ARRAY[...]::type[]
      (= :array h) (let [[_ es t] x]
                     (when (sequential? es)
                       (if (keyword? t)
                         (some-> (typed (str (type-name (str/lower-case (str/replace (name t) "-" " "))) "[]")) (assoc :nullable? false))
                         (when-let [r (when (seq es) (common registry (map sub es)))]
                           (when (and (string? (:type r)) (not (types/element (:type r))))
                             (some-> (typed (str (:type r) "[]")) (assoc :nullable? false)))))))
      (or (#{:exists :not-exists :is :is-not} h) (distinct-ops h)) (typed "boolean")
      (#{:and :or :not :xor :between :not-between :in :not-in :similar-to :not-similar-to} h) (typed-with-nulls "boolean" (rest x))
      ;; an aggregate's FILTER, an argument's DISTINCT, the ORDER BY after an aggregate's last
      ;; argument (docs 4.2.7)
      (#{:filter :distinct :order-by} h) (sub (second x))
      ;; [:case c1 v1 c2 v2 ... :else v]: the values resolve as coalesce's arguments; NULL without :else
      (= :case h) (let [parts (partition-all 2 (rest x))
                        values (map second parts)
                        else? (some #(= :else (first %)) parts)
                        vts (map sub values)]
                    ;; PostgreSQL takes the ELSE value first (transformCaseExpr)
                    (when-let [r (when (every? #(= 2 (count %)) parts) (common registry (cons (last vts) (butlast vts))))]
                      (assoc r :schema (some-> (:schema r) (->> (beyond-list registry) loose))
                             :nullable? (or (not else?) (boolean (some :nullable? vts))))))
      (#{"coalesce" "greatest" "least"} (called x))
      (some-> (common registry (map sub (rest x)))
              ;; greatest and least skip NULLs as coalesce does
              (assoc :nullable? (not-any? #(and % (not (:nullable? %))) (map sub (rest x)))))
      ;; nullif returns its first argument as = reads it
      (= "nullif" (called x)) (let [ts (map sub (rest x))]
                                (when (= 2 (count ts))
                                  (when-let [{[t] :args :as r} (resolve/call :operators "=" (mapv :type ts))]
                                    (when (= "boolean" (:result r))
                                      (when-let [s (result-schema registry t ts)] {:type t :schema s :nullable? true})))))
      ;; x op ANY(array) is boolean (docs 9.24.3), NULL where an element is
      (and (vector? x) (= 3 (count x)) (keyword? h) (operator? h) (array-of-elements? (nth x 2))) (assoc (typed "boolean") :nullable? true)
      ;; a + b + c is (a + b) + c
      (and (keyword? h) (operator? h) (< 2 (count (rest x))) (operator-name h 2)) (sub (into [h [h (nth x 1) (nth x 2)]] (drop 3 x)))
      :else (if-let [c (type-call registry chain x opts)]
              (sub c)
              (when-let [[_ _ args :as parts] (call-parts x)]
                (call-type registry parts (map sub args) opts))))))

(defn- type-call
  "[:cast a f] of a call [:f a] PostgreSQL reads as a cast (func_get_detail): f names a type, of
   pg_catalog or of the registry, and no function f takes a's type as it is; nil for another x."
  [registry chain x opts]
  (when-let [[kind n [a] :as parts] (when (and (vector? x) (= 2 (count x))) (call-parts x))]
    (when (and (= :functions kind) (or (resolve/type-name? n) (registry-type registry (first x) opts)))
      (let [in (:type (expression-type registry chain a opts))]
        (when (not-any? #(= [in] (:args %)) (reached registry opts parts))
          [:cast a (first x)])))))

(defn- select-row
  "The row a SELECT returns. Under a qualifying builder, a cast column, or a column through an
   opaque table whose table this cannot trace, has a key this cannot tell: the driver keys it by
   the table PostgreSQL traces it to, which keeps a column's table under a cast to its own type.
   The row leaves those out, an open map, and names them under :pgmalli/unknown-keys."
  [registry stmt chain {:keys [schema] :as opts}]
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
                                (or nullable? regrouped? (nil? schema) (shape/maybe? schema)) opts))
        unknown-key (fn [n] [[::unknown-key n]])
        merges (using-merges registry stmt sc schema)
        merged (fn [label m]
                 (if (and qualified? (some #(:opaque? (get sc %)) (:tables m)))
                   (unknown-key label)
                   (let [{:keys [sides nullable?] s :schema} m
                         by-key (into {} (for [a sides :let [t (some-> a sc :table)]] [(jdbc/row-key t label opts) t]))
                         ;; keys the driver may give it: each optional
                         props (when (next by-key) {:optional true})]
                     (for [[_ t] by-key] (entry t label s nullable? props)))))
        ;; [[column entry] ...] of a table, or of a typed subquery or CTE, in order; the entry
        ;; [::unknown-key c] for a column whose key is not told
        columns (fn [{:keys [table nullable? opaque? order types origins]}]
                  (if opaque?
                    (for [c order :let [o (get origins c)]]
                      [c (if (and qualified? (nil? o)) [::unknown-key c] (entry [nil o] c (get types c) nullable? nil))])
                    (for [[c s props] (column-entries registry table)] [c (entry table c s nullable? props)])))
        star (fn [col]
               (let [[a] (split-column col)
                     tables (if a (some-> (get sc a) vector) (vals sc))]
                 (when (and (seq tables) (every? #(and % (or (not (:opaque? %)) (:order %))) tables))
                   (if a
                     (map second (mapcat columns tables))
                     ;; SELECT * lists a column USING merges once
                     (concat (mapcat (fn [[c m]] (merged c m)) merges)
                             (for [[alias e] sc, [c en] (columns e)
                                   :when (not (some #{alias} (:tables (get merges c))))]
                               en))))))
        ;; PostgreSQL drops a cast to the type the value has already, which leaves the column
        ;; (and its table) as it is; a cast to another type makes an expression of no table
        as-cast #(or (type-call registry chain % opts) %)
        kept-column? (fn kept? [x]
                       (let [x (as-cast x)]
                         (or (not (cast-of-column? as-cast x))
                             ;; a type named with its schema (:public.mood) is the type of that name
                             (let [[a b] (map #(some-> (canonical-type (expression-type registry chain % opts)) types/bare-name)
                                              [x (second x)])]
                               (and (or (nil? a) (nil? b) (= a b)) (kept? (second x)))))))
        items (for [item (selected-items stmt)
                    :let [[col alias] (select-parts item opts)
                          x (item-expression item)]]
                (cond
                  ;; [:over [f window alias] ...]: a column of each window function
                  (and (nil? col) (nil? alias) (vector? x) (= :over (first x)))
                  (let [cs (for [w (rest x) :let [[e _ a] (when (vector? w) w)
                                                  n (if (alias? a) (alias-label a opts) (expression-name e))]]
                             (when n (let [{s :schema :keys [nullable?]} (expression-type registry chain e opts)] (entry nil n s nullable? nil))))]
                    (when (and (seq cs) (every? some? cs)) cs))
                  (nil? col) (when-let [n (item-name item opts)]
                               (let [x (if alias (first item) (item-expression item))]
                                 (if (and qualified? (or (raw? x) (and (cast-of-column? as-cast x) (kept-column? x))))
                                   (unknown-key n)
                                   (let [{s :schema :keys [nullable?]} (expression-type registry chain x opts)]
                                     [(entry nil n s nullable? nil)]))))
                  (star? col) (star col)
                  :else (when-let [{:keys [table column schema nullable? opaque? origin]} (resolve-column registry chain col)]
                          (let [label (if alias (alias-label alias opts) column)
                                m (when-not (first (split-column col)) (get merges (folded column)))]
                            (cond m (merged label m)
                                  ;; the driver keys a derived table's column by the table PostgreSQL traces it to
                                  (and qualified? opaque? origin) [(entry [nil origin] label schema nullable? nil)]
                                  (and qualified? opaque?) (unknown-key label)
                                  ;; a column under an alias is still keyed by its table: the driver reads the table from the field
                                  :else [(entry table label schema nullable? nil)])))))]
    (when (and (seq items) (every? some? items) (not-any? :unknown? (vals sc)))
      ;; a name selected twice is one key, the driver keeping the last column's value
      (let [entries (apply concat items)
            unknown-keys (vec (distinct (keep #(when (= ::unknown-key (first %)) (keyword (second %))) entries)))
            entries (remove #(= ::unknown-key (first %)) entries)
            last-of (into {} (map (juxt first identity)) entries)]
        (into (cond-> [:map] (seq unknown-keys) (conj {:pgmalli/unknown-keys unknown-keys})) (map last-of) (distinct (map first entries)))))))

(defn- unknown-columns
  "{position literal?} of the columns of SELECT stmt that are NULL or a literal of no type
   (:unknown), which PostgreSQL leaves out when it resolves a set operation's column (docs 10.5)."
  [registry stmt c opts]
  (when-not (or (some #(contains? stmt %) set-operation-keys) (contains? stmt :values))
    (let [items (selected-items stmt)]
      (when-not (some #(let [[col] (select-parts % opts)] (and col (star? col))) items)
        (into {} (keep-indexed (fn [i item]
                                 (let [[col alias] (select-parts item opts)
                                       m (when (nil? col) (expression-type registry c (if alias (first item) (item-expression item)) opts))]
                                   (when (= :unknown (:type m)) [i (some? (:schema m))])))
                               items))))))

(defn- set-operation-row [registry members path chains opts]
  (when (and (vector? members) (seq members) (every? map? members))
    (let [rows (map-indexed (fn [i m] (row-schema* registry m (conj path i) chains (assoc opts ::jdbc/member true))) members)
          unknown (map-indexed (fn [i m] (unknown-columns registry m (get chains (conj path i)) opts)) members)]
      (when (and (every? some? rows) (apply = (map count rows)))
        (into [:map]
              (for [[j entries] (map-indexed vector (apply map vector (map rest rows)))
                    :let [schemas (map last entries)
                          ;; PostgreSQL resolves a UNION b UNION c as (a UNION b) UNION c
                          ts (map (fn [s u] (if (contains? u j) :unknown (resolver-type registry (shape/non-null s)))) schemas unknown)
                          united? (or (not-every? some? ts) (some? (reduce #(some-> %1 (vector %2) resolve/common-type) ts)))
                          known (keep identity (map (fn [s u] (when-not (contains? u j) s)) schemas unknown))
                          ;; a literal is a value of the type, not of the column's bounds
                          literal? (and (seq known) (some #(get % j) unknown))
                          inners (distinct (map (cond->> shape/non-null literal? (comp loose (partial beyond-list registry) shape/non-null))
                                                (or (seq known) schemas)))]]
                ;; PostgreSQL resolves each column's type as CASE's
                (jdbc/row-entry (jdbc/tableless-key (ffirst entries) opts)
                                (cond (not united?) :any
                                      (= 1 (count inners)) (first inners)
                                      ;; the members' schemas are as the driver reads them: one of them, or untold
                                      :else (let [r (:schema (common registry (for [s inners] {:schema s :type (resolver-type registry s)})))]
                                              (if (and r (some #(= r (loose (beyond-list registry %))) inners)) r :any)))
                                (some shape/maybe? schemas) opts)))))))

(defn- values-row
  "The row of a VALUES list of rows (vectors): column1, column2 ... each of its values' common
   type, as PostgreSQL resolves a VALUES column."
  [registry rows chain opts]
  (when (and (seq rows) (every? vector? rows) (apply = (map count rows)))
    (let [registry (reading registry)]
      (into [:map]
            (for [[i xs] (map-indexed vector (apply map vector rows))
                  :let [ts (map #(expression-type registry chain % opts) xs)
                        s (:schema (common registry ts))]]
              (jdbc/row-entry (jdbc/row-key nil (str "column" (inc i)) opts)
                              (cond (nil? s) :any (::columns opts) s :else (driver-type registry jdbc/read-type s opts))
                              (or (nil? s) (boolean (some :nullable? ts))) opts))))))

(defn- row-schema*
  "row-schema of the statement at path in a query, chains its statements' scope chains by path
   (statement-chains), which alone say which tables and CTEs a statement sees."
  [registry stmt path chains opts]
  (if-let [k (some #(when (contains? stmt %) %) set-operation-keys)]
    (set-operation-row registry (get stmt k) (conj path k) chains opts)
    (if (and (= #{:values} (set (keys stmt))) (vector? (:values stmt)))
      (values-row registry (:values stmt) (get chains path) opts)
      (select-row registry stmt (get chains path) opts))))

(defn- derived-types*
  [registry stmt opts outer]
  (let [opts (-> opts (dissoc :jdbc) (assoc ::jdbc/member true ::columns true))
        chains (into {} (map (fn [[_ c p]] [p c])) (statement-chains registry stmt opts outer))]
    (when-let [row (row-schema* registry stmt [] chains opts)]
      ;; read again as the default builder keys it: by the table each column comes from
      (let [keyed (row-schema* registry stmt [] chains (dissoc opts ::jdbc/member))
            unknown-keys (set (map name (:pgmalli/unknown-keys (shape/row-props keyed))))
            tables (group-by name (map first (some-> keyed shape/entries)))
            origin (fn [c] (let [ns (distinct (map namespace (get tables c)))]
                             (when (and (= 1 (count ns)) (not (unknown-keys c))) (or (first ns) ""))))]
        (vec (for [[k _ s] (map shape/entry-parts (shape/entries row))]
               [(name k) (when-not (= :any s) s) (origin (name k))]))))))

(defn- derived-types
  "[[column schema origin] ...] of the row stmt, a query of its own read inside the chain of
   scopes outer (none by default), returns, as the registry types a column (nil for one it
   cannot tell), origin the table PostgreSQL traces the column to (\"\" none, nil not told); nil
   when its row cannot be told, or is asked for again while it is read (a CTE read inside itself)."
  ([registry stmt opts] (derived-types registry stmt opts ()))
  ([registry stmt opts outer]
   (if-let [memo (::derived registry)]
     (let [k [stmt (dissoc opts :jdbc) outer]]
       (if (contains? @memo k)
         (@memo k)
         (do (swap! memo assoc k nil)
             (let [v (derived-types* registry stmt opts outer)] (swap! memo assoc k v) v))))
     (derived-types* registry stmt opts outer))))

(defn row-schema
  "The [:map ...] of one row a query returns, as next.jdbc builds it (for a write with no
   RETURNING, its update count); nil when that cannot be told, as
   docs/queries.md#the-row-a-query-returns lists.

   Columns an outer join may leave NULL are nullable; an expression is typed for the forms
   docs/queries.md#the-row-a-query-returns lists, nullable :any otherwise; a set operation's row
   is its first member's names. Under qualified keys, a column next.jdbc may key by a table the
   query data does not show is left out of the map, which is open, and named under the
   :pgmalli/unknown-keys property: :raw SQL, a column cast to its own type, and a column of a
   table function neither of pg_catalog nor of a generated schema, or of a table the registry lacks. A subquery's or a CTE's column is keyed by
   the table PostgreSQL traces it to. Options: docs/queries.md#options."
  ([registry body] (row-schema registry body {}))
  ([registry body opts]
   (jdbc/options! opts)
   (let [body (query! body)]
     (when (map? body)
       (let [opts (with-defaults opts)]
         (if-let [body (sent body opts)]
           (row-schema* registry body [] (into {} (map (fn [[_ c p]] [p c])) (statement-chains (reading registry) body opts)) opts)
           update-count))))))

(defn params-schema
  "The [:map ...] of sql/format's :params for a query: every named argument, typed as arg-types
   types it, is required, as sql/format refuses a missing one; one that takes nil is
   [:maybe ...]. Options: docs/queries.md#options.

   (params-schema registry {:select [:id] :from [:users] :where [:= :email :?email]})
   ;; => [:map [:email [:string {:pg/type \"character varying\"}]]]"
  ([registry body] (params-schema registry body {}))
  ([registry body opts]
   (into [:map] (sort-by (comp str key) (arg-types registry body opts)))))

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
  "Whether x yields one value per row: a column, a constant, DEFAULT, a cast of one, or a call of
   them to an operator or a function that returns no set, of pg_catalog or a generated schema
   (generate_series and unnest return more rows; a function the registry has none of may)."
  [registry opts x]
  ;; :%f.a is HoneySQL's f(a)
  (or (and (keyword? x) (not (shorthand-fn x))) (constant? x) (nil? x) (= [:default] x)
      (and (vector? x) (#{:inline :lift :param :cast} (first x)) (plain-value? registry opts (second x)))
      (boolean (when-let [[kind n args :as parts] (call-parts x)]
                 (and (or (= :operators kind) (not (resolve/set-returning? n (reached registry opts parts))))
                      (every? #(plain-value? registry opts %) args))))))

(defn- row-count
  "{:min n :max m} of the rows a statement returns where its data tells, nil otherwise; where
   any part may be a function that returns a set (plain-value?), it tells nothing. One of a SELECT of
   aggregates of columns or constants alone (no GROUP BY, HAVING or OFFSET); at most one of a SELECT,
   UPDATE or DELETE on one table (no CTE of its name, no table inheriting it) whose WHERE holds
   the columns of its primary key or a UNIQUE key, neither partial nor DEFERRABLE, equal to a
   value (NULL equals none); up to as many as :values has rows of an INSERT returning (a trigger or ON CONFLICT
   may skip one); at most :limit, from none where a parameter or code gives the limit."
  [registry stmt {:keys [schema] :as opts}]
  ;; a clause this does not read (:join-by, :fetch, a set operation ...) may change the count
  (when (and (map? stmt) (every? row-count-keys (keys stmt)) (not (code-sources? stmt)))
    (let [write? (some #(contains? stmt %) write-keys)
          items (map bare (selected-items stmt))
          order (map bare (vectorish (:order-by stmt)))
          aggregates-only? (every? #(when-let [[_ n :as parts] (call-parts %)] (resolve/aggregate? n (reached registry opts parts))) items)
          rows (when (and (:insert-into stmt) (vector? (:values stmt)) (not (:select (insert-parts stmt)))) (:values stmt))
          plain? (and (every? #(plain-value? registry opts %) (concat order (mapcat #(cond (map? %) (vals %) (sequential? %) % :else [%]) rows)))
                      (or (every? #(plain-value? registry opts %) items)
                          ;; aggregates over plain values
                          (and aggregates-only? (every? (fn [i] (every? #(plain-value? registry opts %) (nth (call-parts i) 2))) items))))
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
                        (and unique? (not (:insert-into stmt)) (or (not write?) (:returning stmt)) (every? #(plain-value? registry opts %) items)) {:min 0 :max 1})
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
   (when-not (and (sequential? args) (every? #(or (keyword? %) (symbol? %)) args))
     (error/fail! :pgmalli.error/invalid-options
                  (str "query-schema takes the function's args first, keywords or symbols ([:db :id]), not " (pr-str args))
                  {:given args}))
   (let [body (query! body)
         opts (with-defaults opts)
         registry (reading registry)
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
    :enum (let [[h & r] t
                [p & labels] (if (map? (first r)) r (cons nil r))]
            (into (cond-> [h] p (conj p)) (remove keyword? labels)))
    :any [:and t [:not [:or :keyword [:sequential :any]]]]
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
               (shape/table-key (qualified name (:schema (with-defaults opts)))))
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
                         :let [t (cond-> (one-value (accepted-type r c :assign opts)) (or (shape/maybe? c) triggered?) nullable)
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
                   (let [elsewhere (first (filter #(= (table-name %) (table-name table)) did-you-mean))]
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
   :unread-clause (fn [{:keys [clause did-you-mean]}]
                    (if did-you-mean
                      (str "clause " clause " is no clause HoneySQL or pgmalli knows" (error/did-you-mean (first did-you-mean)))
                      (str "clause " clause " is not read by pgmalli, so what it does is not checked; check the clause name, or check that part by hand")))
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
