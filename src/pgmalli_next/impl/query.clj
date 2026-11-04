(ns pgmalli-next.impl.query
  "A query read against a registry: the registry as a query reads it, what a name refers to
   (docs/queries.md#what-a-name-refers-to) and the tables a query reads and writes, the type of
   an expression and the rows a statement returns (docs/queries.md#the-row-a-query-returns).
   What HoneySQL data says without a registry is pgmalli-next.impl.hsyntax; the arguments'
   types pgmalli-next.impl.args; the problems pgmalli-next.impl.problems."
  (:require [clojure.string :as str]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.portable :as portable]
            [pgmalli-next.impl.resolve :as resolve]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(declare beyond-list derived-types expression-type loose nullable own-routines own-type-schema reached row-schema* type-call)

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
   pgmalli does not type, :any; nullable where one version's is (an :any too); optional where one lacks it."
  [table vs]
  (when-let [cs (some->> table (re-matches #"pg_catalog\.(.+)") second (get (:relations @types/catalog)))]
    (let [cs (in-versions cs vs)
          by-name (group-by :name cs)]
      (for [c (distinct (map :name cs))
            :let [es (by-name c)
                  ts (distinct (map :type es))
                  s (when-not (next ts) (types/type-schema (first ts)))]]
        [c (if (every? :not-null es) (or s :any) [:maybe (or s :any)])
         (when (and (every? :in es) (not= (count vs) (count (distinct (mapcat :in es))))) {:optional true})]))))

(defn column-entries
  "[[column-name schema props] ...] of a table (or view) in the registry, in its order, or of
   pg_catalog's; nil when it is not there."
  [registry table]
  (or (some->> (get (::schemas registry) (shape/table-key table)) shape/column-entries (map (fn [[k _ s]] [(name k) s])))
      (catalog-columns table (::versions registry))))

(def system-columns
  "The columns every table has besides its own."
  {"ctid" [:some {:pg/type "tid"}] "xmin" [:some {:pg/type "xid"}] "xmax" [:some {:pg/type "xid"}]
   "cmin" [:some {:pg/type "cid"}] "cmax" [:some {:pg/type "cid"}] "tableoid" [:int {:pg/type "oid"}]})

(defn- held-schemas
  "The schemas a registry's schemas hold generated files, tables or views of, sorted."
  [schemas]
  (sort (set (keep (fn [[k v]] (cond (and (keyword? k) (= (namespace (shape/routines-key "")) (namespace k))) (name k)
                                      (shape/read-row? v) (first (shape/schema-and-name k))))
                    schemas))))

(def ^:private last-read
  "[schemas r] of the last registry reading read: callers pass the same one over and over."
  (atom nil))

(defn reading
  "The registry as a query is read against it: its schemas taken once (a composite registry
   merges them on every lookup), the columns of a table memoized, since a column is looked for
   in every table in scope, and the schemas' own functions and operators read once, all of it
   kept for the next call with the same schemas; and, for one call of pgmalli-next.honeysql's
   public functions, the rows of derived tables and the statements of a query (::calls). One
   already read is left as it is."
  [registry]
  (if (::schemas registry)
    registry
    (let [schemas (shape/schemas-of registry)
          [read r] @last-read
          r (if (and r (identical? read schemas))
              r
              (let [r {::schemas schemas ::versions (versions schemas)}
                    r (assoc r
                             ::held (held-schemas schemas)
                             ::generated? (boolean (some shape/generated-name? (keys schemas)))
                             ::table-columns (memoize #(some->> (or (column-entries r %) (column-entries r (syntax/folded %))) (map (fn [[c s]] [c s])) (into system-columns)))
                             ::routines (memoize #(own-routines r %1 %2 %3)))]
                (reset! last-read [schemas r])
                r))]
      (assoc r ::derived (atom {}) ::calls (atom {})))))

(defn once
  "(f registry & args), once per call of a pgmalli-next.honeysql public function (reading's ::calls): the functions a
   call makes of one another read the same statements."
  [registry f & args]
  (if-let [memo (::calls registry)]
    (let [k (into [f] args)]
      (if (contains? @memo k)
        (@memo k)
        (let [v (apply f registry args)] (swap! memo assoc k v) v)))
    (apply f registry args)))

(defn table-columns [registry table] ((::table-columns registry) table))

(defn unknown-table!
  "The error for table, which a registry holding no schema pgmalli generates cannot know: it
   is no registry of pgmalli-next.core/registry."
  [registry table]
  (when-not (::generated? registry)
    (error/fail! :pgmalli.error/not-a-registry
                 (str "the registry holds no schema pgmalli generates, so table " table
                      " is unknown; pass (pgmalli-next.core/registry \"<schema>\")")
                 {:given table})))

(defn with-defaults
  "opts with :schema defaulted; one the registry (as reading reads it) holds nothing of is an
   error, unless it holds no schema at all."
  [registry opts]
  (let [opts (merge {:schema syntax/default-schema} opts)
        s (:schema opts)
        held (::held registry)]
    (when (and (seq held) (not (some #{s} held)) (not= "pg_catalog" s))
      (let [meant (error/suggest s held)]
        (error/fail! :pgmalli.error/invalid-options
                     (str "the registry holds no schema " (pr-str s) "; "
                          (if (next held)
                            (str "it holds " (str/join ", " (map pr-str held)) (error/did-you-mean (some-> meant pr-str)))
                            (str "pass {:schema " (pr-str (first held)) "}")))
                     (cond-> {:given s :known (vec held)} meant (assoc :did-you-mean [meant])))))
    opts))

(defn- catalog-table
  "The table an unqualified name means: pg_catalog, implicitly first on the search path, holds
   the pg_ relations; one the registry lacks is \"pg_catalog.<name>\" all the same, opaque."
  [registry table written]
  (if-let [catalog (when (and written (str/starts-with? written "pg_")) (str "pg_catalog." written))]
    (cond (table-columns registry catalog) catalog
          (table-columns registry table) table
          :else catalog)
    table))

(defn- scope
  "{alias {:table \"schema.table\" :opaque? bool :cte? bool :nullable? bool}} of one statement;
   :elements {column argument} of an unnest of arguments (unnest-elements);
   ctes are the query's CTE names, which shadow a table only when the reference is written
   without a schema. :nullable? marks a table an outer join may leave NULL."
  [registry stmt ctes {:keys [schema]}]
  (let [refs (concat (syntax/from-items stmt syntax/from-keys)
                     (map second (syntax/joins stmt))
                     (syntax/write-targets stmt))
        nullable (syntax/outer-aliases stmt schema)
        ;; UPDATE, DELETE FROM and INSERT INTO name a table, which a CTE of the same name does not hide
        targets (set (syntax/write-targets stmt))
        using (set (for [[_ _ j] (syntax/joins stmt) :when (and (vector? j) (= :using (first j))) c (rest j) :when (keyword? c)]
                     (syntax/folded (name c))))
        sc (into (with-meta {} {::using using})
                 (for [r refs :let [[table alias written listed] (syntax/table-ref r schema)] :when alias
                       :let [cte? (boolean (and written (not (targets r)) (ctes written)))
                             table (when table (catalog-table registry table written))
                             ;; an unquoted :USERS is PostgreSQL's users: the registry's name, which the driver returns
                             table (if (and table (nil? (column-entries registry table)) (column-entries registry (syntax/folded table))) (syntax/folded table) table)
                             unknown? (boolean (and table (not cte?) (not (syntax/catalog? table)) (nil? (table-columns registry table))))
                             _ (when unknown? (unknown-table! registry table))]]
                   [alias (cond-> {:table table :cte? cte?
                                   :opaque? (boolean (or (nil? table) cte? (nil? (table-columns registry table))))
                                   ;; named, but the registry lacks it (pg_catalog's aside): no row can be told of it
                                   :unknown? unknown?}
                            ;; the columns an alias lists rename the first ones, typeless; of a VALUES as
                            ;; wide as the list, they are all it has
                            listed (assoc :columns listed
                                          :all-listed? (let [v (when (and (vector? r) (map? (first r))) (:values (first r)))]
                                                         (boolean (and (vector? v) (seq v) (every? vector? v) (<= (count (first v)) (count listed))))))
                            (nullable alias) (assoc :nullable? true)
                            (syntax/unnest-elements r) (assoc :elements (syntax/unnest-elements r)))]))]
    ;; ON CONFLICT: EXCLUDED is the row proposed for insertion, with the target's columns
    (if-let [[_ alias] (when (contains? stmt :on-conflict) (some-> (syntax/insert-target stmt) (syntax/table-ref schema)))]
      (let [target (assoc (get sc alias) :excluded? true)] (assoc sc "excluded" target))
      sc)))

(defn populate-call
  "{:table :set? :type :json :arg} of x, a call of pg_catalog's json_populate_record,
   jsonb_populate_record or their recordset (set? true) of the row type of a table or view of
   the registry (\"schema.table\"): NULL cast to it ([:cast nil :t]), or the whole row of a table
   in scopes (:p, its alias). json is its argument of the document, of type (\"json\" or
   \"jsonb\"); arg the argument there, bare or cast to json or jsonb, nil for another value. nil
   for another x."
  [registry scopes x {:keys [schema]}]
  (let [[kind n [a json :as args] q] (syntax/call-parts x)]
    (when (and (= :functions kind) (contains? #{nil "pg_catalog"} q) (= 2 (count args)) (re-matches syntax/populate-name n))
      (when-let [table (cond (and (vector? a) (= :cast (first a)) (= 3 (count a)) (or (nil? (second a)) (= :null (syntax/sql-value (second a)))))
                             (when-let [t (syntax/cast-target (nth a 2))] (when-not (types/element t) (first (syntax/table-ref (keyword t) schema))))
                             (and (syntax/column-keyword? a) (nil? (first (syntax/split-column a))))
                             (let [{:keys [table opaque?]} (some #(get % (syntax/folded (name a))) (filter map? (syntax/chain scopes)))]
                               (when-not opaque? table)))]
        (when-let [table (some #(when (column-entries registry %) %) [table (syntax/folded table)])]
          {:table table :set? (str/ends-with? n "set") :type (if (str/starts-with? n "jsonb") "jsonb" "json") :json json
           :arg (cond (syntax/param-key json) json
                      (and (vector? json) (= :cast (first json)) (= 3 (count json)) (syntax/param-key (second json))
                           (some->> (syntax/cast-target (nth json 2)) str/lower-case types/json?))
                      (second json))})))))

(defn- function-columns
  "[[column schema] ...] of a function of pg_catalog or of a generated schema in FROM
   ([[:f args] alias], [[:with-ordinality [:f args] alias]]): its OUT columns (a generated
   schema's: its composite type's too), or one of its result type named after
   the alias (the function without one), each nullable and of no table, renamed by the names the
   alias lists; WITH ORDINALITY adds ordinality, a bigint; of a populate-call, its table's
   columns, nullable. nil where that is not told."
  [registry item chain opts]
  (let [[a b] (if (vector? item) item [item])
        [f ordinality? al] (if (and (vector? a) (= :with-ordinality (first a))) [(second a) true (get a 2)] [a false b])
        [al listed] (if (and (vector? al) (map? (second al))) [(first al) (syntax/listed-columns (second al))] [al nil])
        [kind n args :as parts] (when (vector? f) (syntax/call-parts f))
        renamed (fn [cs] (vec (map-indexed (fn [i [c s]] [(if (< i (count listed)) (syntax/folded (name (nth listed i))) c) s]) cs)))
        populated (when (and (not ordinality?) (or (nil? listed) (sequential? listed))) (populate-call registry chain f opts))]
    (if populated
      ;; the columns of the row type, NULL where the document lacks a key
      (renamed (for [[c s] (column-entries registry (:table populated))] [c (nullable s)]))
      (when (and (= :functions kind) (or (nil? listed) (and (sequential? listed) (every? ident? listed))))
        ;; unnest(a, b) is ROWS FROM (unnest(a), unnest(b)), its columns each named unnest
        (when-let [rs (let [rs (for [as (if (and (= "unnest" n) (next args)) (map vector args) [args])]
                                 (resolve/call kind n (mapv #(:type (expression-type registry chain % opts)) as) (reached registry opts parts)))]
                        (when (every? some? rs) rs))]
          (let [label (if (and (syntax/alias? al) (nil? (next rs))) (syntax/alias-label al opts) n)
                cs (mapcat (fn [{t :result o :overload}] (or (seq (:columns o)) (when-not (= "record" t) [[label t]]))) rs)
                cs (cond-> (vec cs) ordinality? (conj ["ordinality" "bigint"]))
                own #(own-type-schema registry % (:schema (:overload (first rs))))]
            (when (and (seq cs) (every? #(or (types/category (second %)) (own (second %))) cs))
              (renamed (for [[c ct] cs]
                         [c (when-let [s (or (when (string? ct) (types/type-schema ct)) (own ct))] (cond->> s (not= "ordinality" c) (vector :maybe)))])))))))))

(defn- typed-scope
  "sc with :types ({column schema}, a column whose type is not told nil) on the subqueries and CTEs of stmt whose SELECT types its columns, read as a query of their own,
   under the CTEs of ctes it reads (standalone)."
  [registry body stmt sc ctes outer {:keys [schema] :as opts}]
  (let [items (syntax/table-items stmt)]
    (reduce (fn [sc item]
              (let [[_ alias written] (syntax/table-ref item schema)
                    {:keys [table cte? columns] :as entry} (get sc alias)
                    lateral (syntax/lateral? item)
                    [inner listed recursive?] (cond (or (nil? entry) columns) nil
                                                    cte? (syntax/cte-statement body written)
                                                    (and (nil? table) (vector? item) (map? (first item))) [(first item)]
                                                    ;; [[:lateral subquery] alias]
                                                    (and (nil? table) (vector? item) (vector? (first item)) (= :lateral (ffirst item)) (map? (second (first item))))
                                                    [(second (first item))]
                                                    (and (nil? table) (map? item)) [item])
                    ;; a CTE's body comes under the entries before it already: the CTEs it reads beyond them are outer ones
                    own (when inner (let [o (syntax/standalone body inner ctes schema)] (when (or (not cte?) (identical? o inner)) o)))
                    ts (cond inner
                             (when own
                               (if lateral
                                 ;; a LATERAL subquery sees the tables before it
                                 (derived-types registry own opts (cons (dissoc sc alias) outer))
                                 (derived-types registry own opts)))
                             (and entry (nil? inner) (nil? table) (not cte?) (vector? item) (not (map? (first item))))
                             ;; a function sees the tables before it too
                             (function-columns registry item (cons (dissoc sc alias) outer) opts))
                    named (map-indexed (fn [i [c t]] [(nth listed i c) t]) ts)
                    ;; a CTE's name may list new names for its columns, by position; a recursive
                    ;; one's later rows may hold NULL where its first member's do not
                    types (when ts (into {} (for [[c t] named] [c (if (and t recursive?) (nullable (beyond-list registry t)) t)])))]
                (cond-> sc types (update alias assoc :types types :order (mapv first named)))))
            sc items)))

(defn statement-chains
  "[[statement scopes path outer] ...] for the statements of a query, scopes the statement's own
   scope followed by those of the statements enclosing it (outer), path where it sits in the
   query. What a part of a statement sees is clause-parts'; the body of a CTE sees the
   enclosing statements, a :with entry the entries before it, a :with-recursive one all of
   them. A statement inside a vector, not under a statement (in an expression sql/format
   formats), has an enclosing statement this data does not show: its chain ends in :open, a
   column it cannot resolve is taken to be the enclosing statement's, and every CTE the query
   defines anywhere is visible to it. Under outer, body is read inside the chain of scopes
   outer (a LATERAL subquery's)."
  ([registry body opts] (statement-chains registry body opts ()))
  ([registry body opts outer]
  (letfn [(walk [x path outer ctes in-vector? under?]
            (cond (syntax/statement? x)
                  (let [open? (and (empty? outer) in-vector? (not under?))
                        base (if open? (syntax/cte-names body) ctes)
                        ctes (into base (keep syntax/cte-name) (syntax/cte-entries x))
                        outer (if open? (list :open) outer)
                        chain (cons (vary-meta (typed-scope registry body x (scope registry x ctes opts) ctes outer opts) assoc ::ctes ctes ::query body)
                                     outer)]
                    (cons [x chain path outer]
                          (concat
                           (for [k syntax/with-keys :let [w (get x k)] :when (some? w)
                                 found (if (vector? w)
                                         (apply concat (map-indexed (fn [i e] (walk e (conj path k i) outer (into base (keep syntax/cte-name) (if (= :with-recursive k) w (take i w))) false true)) w))
                                         (walk w (conj path k) outer ctes false true))]
                             found)
                           (for [[ks v sees] (syntax/clause-parts x)
                                 found (walk v (into path ks) (syntax/part-chain sees v chain outer (:schema opts)) ctes false true)]
                             found))))
                  (syntax/branch? x) (apply concat (map-indexed (fn [i c] (walk c (conj path i) outer ctes (or in-vector? (vector? x)) under?)) (seq x)))))]
    (walk body [] outer #{} false false))))

(defn- has-column?
  "Whether a scope entry may hold a column: one it lists, one its SELECT gives (a typed
   subquery or CTE), one of its table's, or anything for an opaque table. A list renames the
   first columns alone: the others keep their names."
  [registry {:keys [table opaque? columns types all-listed?] :as entry} column]
  (cond columns (or (boolean (some #(or (= column %) (= (syntax/folded column) %)) columns))
                    (and (not all-listed?) (has-column? registry (dissoc entry :columns) column)))
        types (or (contains? types column) (contains? types (syntax/folded column)))
        opaque? true
        :else (let [cs (table-columns registry table)] (or (contains? cs column) (contains? cs (syntax/folded column))))))

(defn- own-hits
  "[[alias table opaque? nullable? types elements] ...] of the tables of one scope a column may belong to."
  [registry scope col]
  (let [[alias column] (syntax/split-column col)]
    (if alias
      (when-let [{:keys [table opaque? nullable? types elements] :as entry} (get scope alias)]
        (when (has-column? registry entry column) [[alias table opaque? nullable? types elements]]))
      ;; an unqualified column is never EXCLUDED's: that row is only reached through its name
      (distinct (for [[a {:keys [table opaque? excluded? nullable? types elements] :as entry}] scope
                      :when (and (not excluded?) (has-column? registry entry column))]
                  [a table opaque? nullable? types elements])))))

(defn column-hits
  "The hits of the innermost scope (a map, or the chain of a statement and its enclosing
   ones) that has any."
  [registry scopes col]
  (let [[sc & outer] (syntax/chain scopes)
        [alias column] (syntax/split-column col)
        hits (own-hits registry sc col)
        ;; JOIN ... USING (c) merges the c of the tables it joins into one column
        hits (if (and (next hits) (nil? alias) (contains? (::using (meta sc)) (syntax/folded column))) (take 1 hits) hits)]
    (cond (or (seq hits) (empty? outer)) hits
          ;; an alias names the nearest table of its name: an outer one of the same name is hidden
          (and alias (contains? sc alias)) hits
          ;; the enclosing statement is not in the data: a column not of this scope's tables is
          ;; one of its, untyped
          (= :open (first outer)) (if (and alias (contains? sc alias)) hits [[alias nil true]])
          :else (column-hits registry outer col))))

(defn resolve-column
  "{:table :column :schema :nullable? :opaque?} for a column keyword in a scope (or the chain of
   scopes of a nested statement), :table the alias for an opaque table, :schema the type a
   subquery's or CTE's SELECT gives the column (nil for an opaque table without one),
   :nullable? when an outer join may leave the table NULL, :element the argument whose elements
   an unnest's column is, cast or not; nil when the column resolves to no
   table or to several known ones. A column an opaque table may hold besides one known table is
   the opaque one's, untyped: which of them PostgreSQL takes is not in the data."
  [registry scope col]
  (let [hits (column-hits registry scope col)
        column (second (syntax/split-column col))
        [opaque & _] (filter #(nth % 2) hits)]
    (cond (= 1 (count hits))
          (let [[alias table opaque? nullable? types elements] (first hits)
                ;; a subquery's or a CTE's columns, as its SELECT types them
                cs (or types (when-not opaque? (table-columns registry table)))
                ;; the column's own name, as next.jdbc keys the row: :group-id is group_id
                column (if (or (nil? cs) (contains? cs column)) column (syntax/folded column))]
            {:table (or table alias) :column column
             :schema (get cs column)
             :nullable? (boolean nullable?) :opaque? (boolean opaque?)
             :element (get elements (syntax/folded column))})
          (and opaque (>= 1 (count (remove #(nth % 2) hits))))
          (let [[alias table] opaque] {:table (or table alias) :column column :schema nil :nullable? true :opaque? true}))))

(defn tables
  "{:reads #{\"schema.table\" ...} :writes #{...}}: the tables and views a query reads (FROM,
   joins, USING, in every subquery and CTE) and writes (INSERT INTO, UPDATE, DELETE FROM,
   TRUNCATE), aliases and CTE names resolved."
  [registry body {:keys [schema] :as opts}]
  (let [found (for [[stmt scopes] (once registry statement-chains body opts)
                    :let [own (first scopes)
                          targets (set (keep #(some-> % (syntax/table-ref schema) second) (syntax/write-targets stmt)))]
                    [alias {:keys [table cte? excluded?]}] (when (map? own) own)
                    :when (and table (not cte?) (not excluded?))]
                [(if (targets alias) :writes :reads) table])]
    (merge {:reads #{} :writes #{}}
           (update-vals (group-by first found) #(set (map second %))))))

(defn statement-positions
  "[statement scopes path positions] for the statements of query body (statement-chains), the
   SELECT of an INSERT ... SELECT fed the INSERT's columns."
  [registry body opts]
  (let [chains (once registry statement-chains body opts)
        feeds (for [[s c] chains :let [{:keys [columns select]} (syntax/insert-parts s)] :when (and columns (map? select))]
                [select {:columns columns :target (syntax/target-scope c s (:schema opts))}])]
    (for [[s c path outer] chains]
      [s c path (syntax/positions s c outer (some (fn [[select f]] (when (identical? select s) f)) feeds) opts)])))

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

(defn enum-values
  "The labels of an enum column, in the enum's order."
  [registry schema]
  (some->> (labels registry schema) rest (remove map?) vec))

(defn- list-type
  "The base type of a column's labels that are a CHECK's list (the column's, or its domain's):
   the :pg/type they keep; nil for an enum type's labels, which name none."
  [registry s]
  (some-> (labels registry s) shape/column-props :pg/type))

(defn beyond-list
  "s, or the base type of a CHECK's list it is: the type of a value computed from it and others
   (coalesce, CASE, a recursive CTE's later rows), which the list does not hold."
  [registry s]
  (or (some-> (list-type registry s) types/type-schema) s))

(defn- computed
  "The schema of a value computed from a value of s and others (coalesce, CASE, a set operation's
   column): its base type where s is a CHECK's list, without the bounds the computation may leave."
  [registry s]
  (loose (beyond-list registry s)))

(defn- computed-of-one-type
  "computed, of a value whose inputs are all of s's type: a domain stays, as PostgreSQL types the
   value (and its array, which pgjdbc reads as the domain's PGobjects)."
  [registry s]
  (if (= :ref (shape/head (shape/non-null s))) s (computed registry s)))

(defn compared-schema
  "The schema a value op compares with a column of schema s must fit: under equality-ops, or
   for an enum type's labels, the column's; for a CHECK's list otherwise, the base type."
  [registry s op]
  (if-let [t (when-not (syntax/equality-ops op) (list-type registry s))]
    (or (types/type-schema t) :any)
    s))

(defn table-props
  "The properties of the row schema of table (\"schema.table\")."
  [registry table]
  (shape/row-props (get (::schemas registry) (shape/table-key table))))

(defn filled-by-trigger?
  "Whether a BEFORE row trigger runs, for event (:insert, :update), on the table of row
   properties props: it may fill a column before NOT NULL is checked."
  [props event]
  (boolean (get-in props [:pg/before-triggers event])))

(defn assigned-column
  "{:schema :table :opaque? :nullable?} of the column an assignment (positions) writes, nil
   :schema where it is not told; nullable? where NULL may go there: the column takes it, a
   BEFORE row trigger for the write may fill it (an INSERT's value is the row proposed, whose
   NOT NULL PostgreSQL checks before ON CONFLICT DO UPDATE), or the row may not reach it."
  [registry {:keys [assign target event may-drop?]}]
  (let [{s :schema :keys [table opaque?] :as c} (resolve-column registry target assign)]
    (assoc c :nullable? (boolean (or may-drop? (and s (shape/maybe? s))
                                     (and s (not opaque?) (filled-by-trigger? (table-props registry table) event)))))))

(defn enum-type
  "The type of an enum type's column (with CHECKs too), of a domain over one, or of an
   expression's value of one; not of a domain whose CHECK lists its base type's values, whose
   labels name that type."
  [registry s]
  (let [inner (shape/non-null s)
        ;; an enum column's CHECK narrows it as [:and base ...]
        base (if (= :and (shape/head inner)) (first (shape/entries inner)) inner)
        n (shape/type-name (shape/column-props s))]
    (cond (and (vector? base) (= :ref (first base)) (labels registry base) (not (list-type registry base))) n
          ;; an expression's value of an enum type (coalesce(tier, ?)): its labels with the type's name, which no base type has
          (and (= :enum (shape/head base)) n (nil? (types/entry n))) n)))

(defn slot-type
  "The type of a column schema as pgmalli-next.impl.accept names it: [:enum name] for an enum type
   (or a domain over one), a domain's base type, \"x[]\" for an array; nil for a type it does
   not answer for."
  [registry s]
  (let [p (shape/column-props s)
        d (shape/ref-target (::schemas registry) (shape/non-null s))
        t (if-let [e (types/entry (shape/type-name p))]
            (cond-> (:name e) (types/element (:pg/type p)) types/array-of)
            ;; enum-type's d is an enum type, or a domain over one
            (when-let [n (enum-type registry s)] (cond-> [:enum n] (and d (not= :enum (shape/head d))) (conj {:domain? true}))))]
    (cond (accept/answers? t) t
          (and d (not (enum-type registry s))) (slot-type registry (shape/domain-base d)))))

(defn side-schema
  "The column schema of one side of a comparison: a column's, or an expression's where
   expression-type tells it; nil otherwise."
  [registry sc side opts]
  (cond (syntax/column-keyword? side) (:schema (resolve-column registry sc side))
        ;; x op ANY(array) compares x with the array's elements (docs 9.24.3)
        (syntax/array-of-elements? side) (some-> (expression-type registry sc (second side) opts) :type types/element types/type-schema)
        ;; a literal of no type takes the other side's
        (vector? side) (let [m (expression-type registry sc side opts)] (when-not (= :unknown (:type m)) (:schema m)))))

(def update-count
  "The row next.jdbc returns for a statement that returns none: the driver's update count."
  [:map [:next.jdbc/update-count :int]])

(defn sent
  "stmt as pgjdbc sends it; nil when next.jdbc returns the update count instead of rows. A write
   with no RETURNING gets RETURNING * under :return-keys true, RETURNING the columns named under a
   vector of them, but for TRUNCATE, whose generated keys have no column."
  [stmt {{:keys [return-keys]} :jdbc}]
  (cond (or (not (map? stmt)) (contains? stmt :returning) (not-any? #(contains? stmt %) syntax/write-keys)) stmt
        (not return-keys) nil
        (contains? stmt :truncate) stmt
        :else (assoc stmt :returning (if (true? return-keys) [:*] (mapv #(keyword (name %)) return-keys)))))

(defn driver-schema
  "A column schema as jdbc's column-read-type or column-write-type (f) gives it, without NULL, as
   malli's default registry reads it."
  [registry f s opts]
  (let [t (portable/portable-data (::schemas registry) (f (::schemas registry) (shape/non-null s) opts))]
    ;; one of each kind, after pgmalli's own types read as malli's (:pg/bytes as bytes?)
    (if (and (vector? t) (= :or (first t)) (not (map? (second t))))
      (shape/either (shape/distinct-kinds (rest t)) t)
      t)))

(defn untyped?
  "A type that says nothing: :any, with or without properties."
  [t]
  (or (nil? t) (= :any t) (and (vector? t) (= :any (first t)))))

(defn nullable [t] (if (or (untyped? t) (shape/maybe? t)) t [:maybe t]))

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

(defn loose
  "A column type without the bounds a comparison need not keep (CHECKs, lengths, ranges): its
   kind and :pg/type, each alternative's of an :or. A type with other children (an enum) stays:
   PostgreSQL refuses a value outside an enum, even compared."
  [t]
  (if-not (vector? t)
    t
    (let [h (first t)
          p (shape/props t)
          children (shape/entries t)]
      (cond (= :and h) (let [base (loose (first children))]
                         (if-let [pg (and (vector? base) (not (map? (second base))) (:pg/type p))]
                           (into [(first base) {:pg/type pg}] (rest base))
                           (if (and (ident? base) (:pg/type p)) [base {:pg/type (:pg/type p)}] base)))
            (= :maybe h) [h (loose (last t))]
            ;; an array column's :pg/type is what tells pgjdbc's java.sql.Array (an argument) from a read vector
            (#{:vector :sequential :set :pg/array} h) (if-let [pg (:pg/type p)] [h {:pg/type pg} (loose (last t))] [h (loose (last t))])
            (= :or h) (into (cond-> [:or] p (conj p)) (map loose children))
            (seq children) t
            :else (if-let [pg (:pg/type p)] [h {:pg/type pg}] h)))))

(defn- domain-base-type
  "s, or the base type of the domain s refers to (through domains over domains), its :pg/type
   without typmod: the type PostgreSQL resolves a function's argument of a domain to."
  [registry s]
  (let [{:keys [base refs]} (shape/resolved (::schemas registry) s)
        t (:pg/type (shape/column-props base))]
    (cond (empty? refs) s
          t (shape/merge-props base {:pg/type (types/bare-name t)})
          (next refs) (shape/merge-props (peek refs) {:pg/type (second (shape/schema-and-name (last (peek refs))))})
          :else s)))

(defn- domain-array
  "The schema of an array of values of column schema s where s is of a domain (a CHECK's list
   too, but not an enum type): the domain's array, as PostgreSQL keeps it in ARRAY[...] and in a
   polymorphic function's anyarray bound by anyelement (array_agg); nil otherwise."
  [registry s]
  (let [s (some-> s shape/non-null)
        {:keys [base refs enum?]} (shape/resolved (::schemas registry) s)]
    (when (and (seq refs) (not (and enum? (nil? (:pg/type (shape/props base))))))
      [:vector {:pg/type (types/array-of (:pg/type (shape/column-props s) (second (shape/schema-and-name (last s)))))} s])))

(defn- canonical-type [{s :schema}] (some-> s shape/column-props :pg/type types/canonical-name))

(defn resolver-type
  "The type pgmalli-next.impl.resolve takes for a value of column schema s: a domain's base type,
   [:enum name] for an enum type; nil where s names none."
  [registry s]
  (when s
    (let [b (domain-base-type registry s)]
      (if-let [n (enum-type registry b)]
        [:enum n]
        (canonical-type {:schema b})))))

(defn own-type?
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
                 (computed registry s)))
            inputs)
      (when (string? t) (types/type-schema t))))

(defn common
  "The expression type PostgreSQL resolves expression types ms to, as it resolves UNION, CASE
   and coalesce (pgmalli-next.impl.resolve/common-type): of one type, the first one's; nil where one
   is not told. An argument takes the others' type where that is a string type: pgjdbc sends a
   number or a date typed, which may widen it beyond the others'."
  [registry ms]
  (when (and (seq ms) (every? :type ms))
    (let [ts (map :type ms)
          t (resolve/common-type ts)
          typed (remove (comp resolve/unbound? :type) ms)]
      (when (and t (or (not-any? #{:free} ts) (= "S" (types/category t))))
        {:type t
         ;; inputs all of one domain resolve to it (select_common_type); with another, to its base type
         :schema (if (and (seq typed) (apply = (map (juxt :type #(:pg/type (shape/column-props (:schema %)))) typed))
                          (or (own-type? (first typed) (:type (first typed))) (= (count typed) (count ms))))
                   (cond->> (:schema (first typed)) (next ms) (computed-of-one-type registry))
                   (result-schema registry t typed))}))))

(def common-type-functions
  "The functions whose arguments PostgreSQL resolves to one type, and that returns it."
  #{"coalesce" "greatest" "least"})

(defn- using-merges
  "{column {:tables [alias ...] :schema s :nullable? bool}} of a statement's JOIN ... USING, each
   join merging its left side (a table's column, or what an earlier join merged) with the right
   table's in turn: :tables every table merged, :schema nil when the types differ.
   PostgreSQL takes the left column (INNER JOIN the right one when only the left is cast to
   the merged type), RIGHT JOIN the right one, FULL JOIN neither: a COALESCE; a column cast
   to the merged type keeps no table."
  [registry stmt sc schema]
  (let [alias #(second (syntax/table-ref % schema))
        col-schema (fn [a column]
                     (when-let [{:keys [table opaque?]} (get sc a)]
                       (when-not opaque? (let [cs (table-columns registry table)] (or (get cs column) (get cs (syntax/folded column)))))))
        ;; the type without what does not change it (a default, constraints)
        type-of #(let [s (shape/non-null %)] (if (and (vector? s) (map? (second s))) (assoc s 1 (select-keys (second s) [:pg/type :pg/max-length :min :max])) s))
        table-side (fn [a column] (let [s (col-schema a column)] {:schema s :nullable? (boolean (or (nil? s) (shape/maybe? s) (:nullable? (get sc a))))}))
        merged-with (fn [join l r]
                ;; ponytail: a timestamp's or time's precision is not in the registry, so (3) and (6) read as one type
                (let [same? (and (:schema l) (:schema r) (= (type-of (:schema l)) (type-of (:schema r))))
                      side (case join (:join :inner-join :left-join) l :right-join r nil)]
                  (cond (nil? side) {:schema (when same? (:schema l)) :nullable? true}
                        same? side
                        ;; one side cast to the merged type: which one, the registry does not say
                        :else {:schema nil :nullable? true})))]
    (:merges
     (reduce (fn [{:keys [before] :as acc} [k right c]]
               (-> (if (and (vector? c) (= :using (first c)))
                     (reduce (fn [acc col]
                               (let [col (syntax/folded (name col))
                                     earlier (get-in acc [:merges col])
                                     holders (filter #(when-let [e (get sc %)] (has-column? registry e col)) before)
                                     l (cond earlier earlier
                                             (= 1 (count holders)) (table-side (first holders) col)
                                             :else {:schema nil :nullable? true})]
                                 (assoc-in acc [:merges col] (assoc (merged-with k l (table-side right col))
                                                                    :tables (conj (vec (or (:tables earlier) holders)) right)))))
                             acc (filter keyword? (rest c)))
                     acc)
                   (update :before conj right)))
             {:before (vec (syntax/join-base stmt schema)) :merges {}}
             (for [[k t c] (syntax/joins stmt)] [k (alias t) c])))))

(defn reached
  "The overloads a call (call-parts) reaches: those of the schema it names, else pg_catalog's and
   :schema's, pg_catalog first on the search path as PostgreSQL puts it when the path does not
   name it (docs 5.9.6). A schema the registry has no :functions of (a file generated before
   them, a schema not generated) has none."
  [registry {:keys [schema]} [kind n _ q]]
  (in-versions (resolve/overloads kind n (::routines registry) (if q [q] ["pg_catalog" schema]))
               (::versions registry)))

(defn- own-type-schema
  "The column schema of a value of an enum type or a domain of the registry, t named as a
   generated file of schema s names it, or as pgmalli-next.impl.resolve takes an enum type ([:enum
   name]); nil for another type."
  [registry t s]
  (let [n (if (vector? t) (second t) t)]
    (when-let [[k] (when (string? n) (registry-type registry (keyword n) {:schema s}))]
      [:ref {:pg/type n} k])))

(defn- own-routines
  "The overloads of function (kind :functions) or operator (:operators) n of schema s's generated
   file, the argument and result types as pgmalli-next.impl.resolve takes them (an enum type [:enum
   name], a domain its base type)."
  [registry s kind n]
  (let [resolved #(if-let [r (own-type-schema registry % s)] (resolver-type registry r) %)]
    (mapv #(-> % (update :args (partial mapv resolved)) (update :result resolved))
          (get-in (second (get (::schemas registry) (shape/routines-key s))) [({:functions :pg/functions :operators :pg/operators} kind) n]))))

(def ^:private json-shapes
  "The shape of the json or jsonb value a function builds, parsed as the one reading reads a column of
   the type: an object or an array."
  (into {} (for [p ["json" "jsonb"], [f shape] [["_build_object" :map] ["_build_array" :sequential] ["_agg" :sequential]
                                                ["_object_agg" :map] ["_object" :map]]]
             [(str p f) shape])))

(defn- call-type
  "The expression type of a call (call-parts), its arguments of expression types ts."
  [registry [kind n :as parts] ts opts]
  (when-let [{t :result o :overload} (let [ins (mapv :type ts) os (reached registry opts parts)]
                                       (if (every? some? ins)
                                         (resolve/call kind n ins os)
                                         ;; of arguments not told, a result no argument decides (count, jsonb_build_object)
                                         (when-let [r (resolve/fixed-result (count ins) os)]
                                           {:result (:result r) :overload (dissoc r :result)})))]
    (let [catalog? (= "pg_catalog" (:schema o))
          schema (if-let [shape (and catalog? (= :functions kind) (#{"json" "jsonb"} t) (json-shapes n))]
                   (if (= :map shape) [:map {:pg/type t}] [:sequential {:pg/type t} :any])
                   (or (when (= "anyarray" (:result o))
                         (some (fn [[d m]] (when (#{"anyelement" "anynonarray"} d) (domain-array registry (:schema m))))
                               (map vector (:args o) ts)))
                       ;; array_agg of arrays adds a dimension, which an array type does not name
                       (when (and catalog? (= "array_agg" (name n)) (= ["anyarray"] (:args o)))
                         [:any {:pg/type t}])
                       (result-schema registry t ts)
                       (some->> (:schema o) (own-type-schema registry t))))]
      (when schema
        {:type t :schema schema
         ;; the catalog does not say when a function returns NULL: pg_catalog's aggregates that
         ;; start from a value (count) and windows of no argument (row_number) do not, and a btree
         ;; operator (a comparison) of values does not; a schema's own may (its transition function may)
         :nullable? (not (or (and catalog? (:non-null o))
                             (and (:btree o) (not-any? :nullable? ts))))}))))

(defn- subquery-type
  "The expression type of scalar subquery stmt in the chain of scopes c (docs 4.2.11): its one
   column's, NULL where it returns no row; nil where that is not told."
  [registry c stmt opts]
  (let [c (syntax/chain c)]
    (when-let [stmt (let [{q ::query cs ::ctes} (meta (first c))] (syntax/standalone q stmt cs (:schema opts)))]
      (let [[[_ s] more] (derived-types registry stmt opts c)]
        (when (and s (nil? more))
          (let [s (shape/non-null s)] {:schema s :type (resolver-type registry s) :nullable? true}))))))

(defn- of-type
  "The expression type of a value of type t (a name), nil for none."
  [t]
  (when t {:schema (types/type-schema t) :type t}))

(defn- literal-type
  "The expression type of a literal x HoneySQL sends as a parameter (a string, a number, a
   boolean), or v of [:inline v], which it writes into the SQL: a string an unknown literal, a
   number by its value."
  [x inline? opts]
  (cond (string? x) (let [t (if inline? :unknown (jdbc/sent-type x opts))] (if (= :unknown t) {:type :unknown :schema (types/type-schema "text")} (of-type t)))
        (boolean? x) (of-type "boolean")
        (not inline?) (let [t (jdbc/sent-type x opts)] (when-not (= :unknown t) (of-type t)))
        ;; HoneySQL writes 1M and 1N as 1, an integer literal to PostgreSQL, and a ratio as a
        ;; division of integers
        (ratio? x) (of-type "integer")
        (and (number? x) (re-matches #"-?\d+" (str x)))
        (of-type (let [n (bigint x)] (cond (<= Integer/MIN_VALUE n Integer/MAX_VALUE) "integer" (<= Long/MIN_VALUE n Long/MAX_VALUE) "bigint" :else "numeric")))
        (number? x) (of-type "numeric")))

(defn named-type-schema
  "The column schema of a value of type n (as cast-target names it, \"x[]\" an array): a type of
   pg_catalog, or an enum type or a domain of the registry, read as a column of it is; nil for
   another."
  [registry opts n]
  (or (types/type-schema (types/cast-name n))
      (let [e (types/element n)]
        (when-let [s (own-type-schema registry (or e n) (:schema opts))]
          (if e [:vector {:pg/type n} s] s)))))

(defn- cast-type
  "The expression type of [:cast v t], v of expression type m, t of cast-target."
  [registry [_ _ t] m opts]
  (when-let [s (some->> (syntax/cast-target t) (named-type-schema registry opts))]
    {:schema s :type (resolver-type registry s)
     :nullable? (if m (boolean (:nullable? m)) true)}))

(defn- array-type
  "The expression type of ARRAY[es...], of the elements' (of expression types ms) common type,
   or of [:array es t], ARRAY[...]::t[]."
  [registry [_ es t] ms opts]
  (when (sequential? es)
    (if (keyword? t)
      (let [n (types/array-of (syntax/cast-target t))]
        (assoc (if-let [s (named-type-schema registry opts n)] {:schema s :type (resolver-type registry s)} (of-type (types/cast-name n)))
               :nullable? false))
      (when-let [r (when (seq es) (common registry ms))]
        (when (and (string? (:type r)) (not (types/element (:type r))))
          (some-> (or (some->> (domain-array registry (:schema r)) (hash-map :type (types/array-of (:type r)) :schema))
                      (of-type (types/array-of (:type r))))
                  (assoc :nullable? false)))))))

(defn- nullif-type
  "The expression type of nullif of arguments of expression types ms: its first argument's, as =
   takes it."
  [registry ms]
  (when (= 2 (count ms))
    (when-let [{[t] :args :as r} (resolve/call :operators "=" (mapv :type ms) (resolve/overloads :operators "="))]
      (when (= "boolean" (:result r))
        (when-let [s (result-schema registry t ms)] {:type t :schema s :nullable? true})))))

(defn expression-type
  "{:schema column-schema :nullable? bool :type t} of an expression, t its type as
   pgmalli-next.impl.resolve takes it (:unknown for an untyped literal, :free for an argument), as
   docs/queries.md#the-row-a-query-returns says; nil where that is not told."
  [registry chain x opts]
  (let [h (when (vector? x) (first x))
        sub #(expression-type registry chain % opts)
        typed-with-nulls (fn [t operands] (assoc (of-type t) :nullable? (not-every? #(and % (not (:nullable? %))) (map sub operands))))]
    (cond
      (syntax/param-key x) {:type :free :nullable? true}
      (or (nil? x) (= :null (syntax/sql-value x))) {:type :unknown :nullable? true}
      (or (string? x) (boolean? x) (number? x)) (literal-type x false opts)
      (syntax/sql-value x) (cond-> (of-type (syntax/sql-value-types (syntax/sql-value x))) (syntax/nullable-sql-values (syntax/sql-value x)) (assoc :nullable? true))
      (syntax/column-keyword? x) (when-let [{s :schema :keys [nullable?]} (resolve-column registry chain x)]
                            ;; a subquery's or a CTE's column has the type its SELECT gives, an opaque table's none
                            (when s {:schema (shape/non-null s) :nullable? (boolean (or nullable? (shape/maybe? s)))
                                     :type (resolver-type registry (shape/non-null s))}))
      (syntax/statement? x) (subquery-type registry chain x opts)
      ;; [[f x]], HoneySQL's (f(x))
      (and (vector? h) (= 1 (count x))) (sub h)
      (not= x (syntax/operand-alone x)) (sub (syntax/operand-alone x))
      (and (not (keyword? x)) (not (keyword? h))) nil
      (= :inline h) (let [v (second x)] (when (or (string? v) (boolean? v) (number? v)) (literal-type v true opts)))
      ;; [:lift v] sends v as a parameter as it is, a keyword or a vector too
      (= :lift h) (let [v (second x)] (when-not (or (keyword? v) (coll? v)) (sub v)))
      (= :cast h) (cast-type registry x (sub (second x)) opts)
      ;; INTERVAL '1 day'
      (= :interval h) (of-type "interval")
      (= :array h) (array-type registry x (when (sequential? (second x)) (map sub (second x))) opts)
      (or (#{:exists :not-exists :is :is-not} h) (syntax/distinct-ops h)) (of-type "boolean")
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
                      (assoc r :schema (some->> (:schema r) (computed-of-one-type registry))
                             :nullable? (or (not else?) (boolean (some :nullable? vts))))))
      (common-type-functions (syntax/called x))
      (some-> (common registry (map sub (rest x)))
              ;; greatest and least skip NULLs as coalesce does
              (assoc :nullable? (not-any? #(and % (not (:nullable? %))) (map sub (rest x)))))
      (= "nullif" (syntax/called x)) (nullif-type registry (map sub (rest x)))
      ;; x op ANY(array) is boolean (docs 9.24.3), NULL where an element is
      (and (vector? x) (= 3 (count x)) (keyword? h) (syntax/operator? h) (syntax/array-of-elements? (nth x 2))) (assoc (of-type "boolean") :nullable? true)
      ;; a + b + c is (a + b) + c
      (and (keyword? h) (syntax/operator? h) (< 2 (count (rest x))) (syntax/operator-name h 2)) (sub (into [h [h (nth x 1) (nth x 2)]] (drop 3 x)))
      :else (if-let [c (type-call registry chain x opts)]
              (sub c)
              (when-let [[_ _ args :as parts] (syntax/call-parts x)]
                (call-type registry parts (map sub args) opts))))))

(defn type-call
  "[:cast a f] of a call [:f a] PostgreSQL reads as a cast (func_get_detail): f names a type, of
   pg_catalog or of the registry, and no function f takes a's type as it is; nil for another x."
  [registry chain x opts]
  (when-let [[kind n [a] :as parts] (when (and (vector? x) (= 2 (count x))) (syntax/call-parts x))]
    (when (and (= :functions kind) (or (resolve/type-name? n) (registry-type registry (first x) opts)))
      (let [in (:type (expression-type registry chain a opts))]
        (when (not-any? #(= [in] (:args %)) (reached registry opts parts))
          [:cast a (first x)])))))

(defn- output-type
  "The type of an output column of schema s (nil: not told), without NULL: as the driver reads it,
   or, under ::columns, as the registry types a column (a derived table's, for its outer
   statement)."
  [registry s opts]
  (cond (nil? s) :any (::columns opts) (shape/non-null s) :else (driver-schema registry jdbc/column-read-type s opts)))

(defn- select-row
  "The row a SELECT returns."
  [registry stmt chain {:keys [schema] :as opts}]
  (let [sc (first chain)
        ;; ROLLUP, CUBE and GROUPING SETS add rows whose grouped columns are NULL; :raw there
        ;; may be one
        regrouped? (some #(and (vector? %) (#{:rollup :cube :grouping-sets :raw} (first %)))
                         (syntax/vectorish (:group-by stmt)))
        entry (fn [column schema nullable? props]
                (jdbc/row-entry (jdbc/row-key column) props (output-type registry schema opts)
                                (or nullable? regrouped? (nil? schema) (shape/maybe? schema))))
        merges (using-merges registry stmt sc schema)
        merged (fn [label {:keys [nullable?] s :schema}] [(entry label s nullable? nil)])
        ;; [[column entry] ...] of a table, or of a typed subquery or CTE, in order
        columns (fn [{:keys [table nullable? opaque? order types]}]
                  (if opaque?
                    (for [c order] [c (entry c (get types c) nullable? nil)])
                    (for [[c s props] (column-entries registry table)] [c (entry c s nullable? props)])))
        star (fn [col]
               (let [[a] (syntax/split-column col)
                     tables (if a (some-> (get sc a) vector) (vals sc))]
                 (when (and (seq tables) (every? #(and % (or (not (:opaque? %)) (:order %))) tables))
                   (if a
                     (map second (mapcat columns tables))
                     ;; SELECT * lists a column USING merges once
                     (concat (mapcat (fn [[c m]] (merged c m)) merges)
                             (for [[alias e] sc, [c en] (columns e)
                                   :when (not (some #{alias} (:tables (get merges c))))]
                               en))))))
        items (for [item (syntax/selected-items stmt)
                    :let [[col alias] (syntax/select-parts item opts)
                          x (syntax/item-expression item)]]
                (cond
                  ;; [:over [f window alias] ...]: a column of each window function
                  (and (nil? col) (nil? alias) (vector? x) (= :over (first x)))
                  (let [cs (for [w (rest x) :let [[e _ a] (when (vector? w) w)
                                                  n (if (syntax/alias? a) (syntax/alias-label a opts) (syntax/expression-name e))]]
                             (when n (let [{s :schema :keys [nullable?]} (expression-type registry chain e opts)] (entry n s nullable? nil))))]
                    (when (and (seq cs) (every? some? cs)) cs))
                  (nil? col) (when-let [n (syntax/item-name item opts)]
                               (let [{s :schema :keys [nullable?]} (expression-type registry chain (if alias (first item) x) opts)]
                                 [(entry n s nullable? nil)]))
                  (syntax/star? col) (star col)
                  :else (when-let [{:keys [column schema nullable?]} (resolve-column registry chain col)]
                          (let [label (if alias (syntax/alias-label alias opts) column)]
                            (if-let [m (when-not (first (syntax/split-column col)) (get merges (syntax/folded column)))]
                              (merged label m)
                              [(entry label schema nullable? nil)])))))]
    (when (and (seq items) (every? some? items) (not-any? :unknown? (vals sc)))
      ;; a name selected twice is one key, the driver keeping the last column's value
      (let [entries (apply concat items)
            last-of (into {} (map (juxt first identity)) entries)]
        (into [:map] (map last-of) (distinct (map first entries)))))))

(defn- unknown-columns
  "{position literal?} of the columns of SELECT stmt that are NULL or a literal of no type
   (:unknown), which PostgreSQL leaves out when it resolves a set operation's column (docs 10.5)."
  [registry stmt c opts]
  (when-not (or (some #(contains? stmt %) syntax/set-operation-keys) (contains? stmt :values))
    (let [items (syntax/selected-items stmt)]
      (when-not (some #(let [[col] (syntax/select-parts % opts)] (and col (syntax/star? col))) items)
        (into {} (keep-indexed (fn [i item]
                                 (let [[col alias] (syntax/select-parts item opts)
                                       m (when (nil? col) (expression-type registry c (if alias (first item) (syntax/item-expression item)) opts))]
                                   (when (= :unknown (:type m)) [i (some? (:schema m))])))
                               items))))))

(defn- set-operation-row [registry members path chains opts]
  (when (and (vector? members) (seq members) (every? map? members))
    (let [rows (map-indexed (fn [i m] (row-schema* registry m (conj path i) chains opts)) members)
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
                          inners (distinct (map (cond->> shape/non-null literal? (comp (partial computed registry) shape/non-null))
                                                (or (seq known) schemas)))]]
                ;; PostgreSQL resolves each column's type as CASE's
                (jdbc/row-entry (ffirst entries)
                                (cond (not united?) :any
                                      ;; with an untyped member, a domain resolves to its base type, as CASE's does
                                      (= 1 (count inners)) (let [[s] inners
                                                                 k (if (= :ref (shape/head s))
                                                                     (last s)
                                                                     (some-> (:pg/type (shape/column-props s)) keyword (#(first (registry-type registry % opts)))))
                                                                 d (get (::schemas registry) k)]
                                                             (if (and d (not= :enum (shape/head d)) (< (count known) (count schemas)))
                                                               (computed registry (domain-base-type registry [:ref k]))
                                                               s))
                                      ;; the members' schemas are as the driver reads them: one of them, or untold
                                      :else (let [r (:schema (common registry (for [s inners] {:schema s :type (resolver-type registry s)})))]
                                              (if (and r (some #(= r (computed registry %)) inners)) r :any)))
                                (some shape/maybe? schemas))))))))

(defn- values-row
  "The row of a VALUES list of rows (vectors): column1, column2 ... each of its values' common
   type, as PostgreSQL resolves a VALUES column."
  [registry rows chain opts]
  (when (and (seq rows) (every? vector? rows) (apply = (map count rows)))
    (into [:map]
          (for [[i xs] (map-indexed vector (apply map vector rows))
                :let [ts (map #(expression-type registry chain % opts) xs)
                      s (:schema (common registry ts))]]
            (jdbc/row-entry (jdbc/row-key (str "column" (inc i)))
                            (output-type registry s opts)
                            (or (nil? s) (boolean (some :nullable? ts))))))))

(defn- row-schema*
  "row-schema of the statement at path in a query, chains its statements' scope chains by path
   (statement-chains), which alone say which tables and CTEs a statement sees."
  [registry stmt path chains opts]
  (if-let [k (some #(when (contains? stmt %) %) syntax/set-operation-keys)]
    (set-operation-row registry (get stmt k) (conj path k) chains opts)
    (if (and (= #{:values} (set (keys stmt))) (vector? (:values stmt)))
      (values-row registry (:values stmt) (get chains path) opts)
      (select-row registry stmt (get chains path) opts))))

(defn- derived-types*
  [registry stmt opts outer]
  (let [opts (-> opts (dissoc :jdbc) (assoc ::columns true))
        chains (into {} (map (fn [[_ c p]] [p c])) (statement-chains registry stmt opts outer))]
    (when-let [row (row-schema* registry stmt [] chains opts)]
      (vec (for [[k _ s] (map shape/entry-parts (shape/entries row))]
             [(name k) (when-not (= :any s) s)])))))

(defn- derived-types
  "[[column schema] ...] of the row stmt, a query of its own read inside the chain of
   scopes outer (none by default), returns, as the registry types a column (nil for one it
   cannot tell); nil
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
  "The [:map ...] of one row a query returns, as next.jdbc's as-unqualified-maps builds it (for a write with no
   RETURNING, its update count); nil when that cannot be told, as
   docs/queries.md#the-row-a-query-returns lists. Columns an outer join may leave NULL are
   nullable; an expression has the type PostgreSQL resolves it to, nullable :any where pgmalli
   cannot tell; a set operation's row is keyed by its first member's names."
  [registry body opts]
  (when (map? body)
    (if-let [body (sent body opts)]
      (row-schema* registry body [] (into {} (map (fn [[_ c p]] [p c])) (once registry statement-chains body opts)) opts)
      update-count)))

(def ^:private row-count-keys
  "The clauses row-count reads, and those that change no count."
  (into #{:select :select-distinct :from :where :group-by :having :order-by :limit :offset :with :returning
          :insert-into :columns :values :on-conflict :on-constraint :do-nothing :do-update-set :update :set :delete-from :for :join-by}
        syntax/join-keys))

(defn- plain-value?
  "Whether x yields one value per row: a column, a constant, DEFAULT, a subquery, EXISTS, a
   cast, CASE, coalesce, greatest, least or nullif of them, or a call of them to an operator or a
   function that returns no set, of pg_catalog or a generated schema (generate_series and unnest
   return more rows; a function the registry has none of may)."
  [registry opts x]
  ;; :%f.a is HoneySQL's f(a)
  (or (and (keyword? x) (not (syntax/shorthand-fn x))) (syntax/constant? x) (nil? x) (= [:default] x)
      ;; a scalar subquery is one value, or an error where it gives more rows
      (map? x) (and (vector? x) (#{:exists :not-exists} (first x)) (map? (second x)))
      (and (vector? x) (#{:inline :lift :param :cast} (first x)) (plain-value? registry opts (second x)))
      ;; grammar, not functions of pg_catalog
      (and (vector? x) (or (#{:case :case-expr} (first x)) (common-type-functions (syntax/called x)) (= "nullif" (syntax/called x)))
           (every? #(plain-value? registry opts %) (rest x)))
      (boolean (when-let [[kind _ args :as parts] (syntax/call-parts x)]
                 (and (or (= :operators kind) (not (resolve/set-returning? (reached registry opts parts))))
                      (every? #(plain-value? registry opts %) args))))))

(defn- aggregated?
  "Whether x holds a call of an aggregate of its own level: one in a subquery, or in a window
   call, whose own level is not told here, is not counted."
  [registry opts x]
  (boolean (or (when-let [parts (syntax/call-parts x)] (and (= :functions (first parts)) (resolve/aggregate? (reached registry opts parts))))
               (and (vector? x) (not= :over (first x)) (some #(aggregated? registry opts %) x)))))

(defn- unique-row?
  "Whether statement stmt on the one table of targets (no CTE of its name, no table inheriting it)
   reads one row at most: its WHERE holds the columns of its primary key or a UNIQUE key, neither
   partial nor DEFERRABLE, equal to a value; to NULL, which HoneySQL writes IS NULL, a column
   NOT NULL alone."
  [registry stmt targets schema]
  (let [[table alias written] (when (= 1 (count targets)) (syntax/table-ref (first targets) schema))
        ;; a CTE of the table's name hides it
        table (when-not (and written (contains? (syntax/cte-names stmt) (syntax/folded written))) table)
        where (:where stmt)
        columns (when table (table-columns registry table))
        equal (set (for [c (if (and (vector? where) (= :and (first where))) (rest where) [where])
                         :when (and (vector? c) (= := (first c)) (= 3 (count c)))
                         [a b] [[(second c) (nth c 2)] [(nth c 2) (second c)]]
                         :when (and (syntax/column-keyword? a) (syntax/constant? b))
                         :let [[q col] (syntax/split-column a)]
                         :when (and (or (nil? q) (= q alias))
                                    (not (and (syntax/null-value? b nil) (shape/maybe? (get columns (syntax/folded col) [:maybe :any])))))]
                     (syntax/folded col)))
        props (when table (table-props registry table))
        ;; a partial UNIQUE index holds over some rows alone; a DEFERRABLE key may be checked only at commit
        key-columns (cond-> (map :columns (remove #(or (:where %) (:deferrable %)) (:pg/unique props)))
                      (and (:pg/primary-key props) (not (:pg/primary-key-deferrable props))) (conj (:pg/primary-key props)))]
    (boolean (and (not (:pg/inherited props)) (some #(every? equal %) key-columns)))))

(defn row-count
  "{:min n :max m} of the rows a statement returns where its data tells, nil otherwise; where
   any part may be a function that returns a set (plain-value?), it tells nothing. One of a SELECT
   with an aggregate of its level and no GROUP BY, at most one with HAVING; at most one of a SELECT,
   UPDATE or DELETE of one row (unique-row?), and of a SELECT without FROM; up to as many as
   :values has rows of an INSERT returning (a trigger, a rule or ON CONFLICT may skip one); at most
   :limit, from none where a parameter gives the limit or OFFSET skips some."
  [registry stmt {:keys [schema] :as opts}]
  ;; a clause this does not read (:fetch, a set operation ...) may change the count
  (when (and (map? stmt) (every? row-count-keys (keys stmt)))
    (let [write? (some #(contains? stmt %) syntax/write-keys)
          items (map syntax/bare (syntax/selected-items stmt))
          order (map syntax/bare (syntax/vectorish (:order-by stmt)))
          rows (when (and (:insert-into stmt) (vector? (:values stmt)) (not (:select (syntax/insert-parts stmt)))) (:values stmt))
          plain? (every? #(plain-value? registry opts %) (concat items order (mapcat #(cond (map? %) (vals %) (sequential? %) % :else [%]) rows)))
          unique? (unique-row? registry stmt (if write? (syntax/write-targets stmt) (syntax/table-items stmt)) schema)
          limit (let [n (:limit stmt)] (when (and (integer? n) (<= 0 n)) n))
          ;; a limit only a parameter tells may be 0
          unbounded-limit? (and (contains? stmt :limit) (not limit))
          ;; HoneySQL writes no HAVING of []
          having? (and (contains? stmt :having) (not= [] (:having stmt)))
          ;; one group without GROUP BY: an aggregate of its level or HAVING makes it, which HAVING may drop
          grouped? (and (or (:select stmt) (:select-distinct stmt)) (seq items) (not (contains? stmt :group-by))
                        (or having? (some #(aggregated? registry opts %) (concat items order))))
          ;; SELECT without FROM: one row, none where WHERE is false
          fromless? (and (:select stmt) (seq items) (not-any? #(contains? stmt %) (concat [:from :group-by :having] syntax/join-keys)))
          counted (cond (not plain?) nil
                        grouped? {:min (if having? 0 1) :max 1}
                        fromless? {:min (if (contains? stmt :where) 0 1) :max 1}
                        (and rows (:returning stmt)) {:min 0 :max (count rows)}
                        (and unique? (not (:insert-into stmt)) (or (not write?) (:returning stmt)) (every? #(plain-value? registry opts %) items)) {:min 0 :max 1})
          m (cond-> counted
              limit (update :max #(min (or % limit) limit))
              (and limit (not counted)) (assoc :min 0)
              ;; a trigger, a rule or ON CONFLICT may skip a row a write's SELECT gives
              (or unbounded-limit? (contains? stmt :offset) (and write? (:returning stmt))) (assoc :min 0))]
      (when (and plain? (:max m)) (cond-> m (< (:max m) (:min m 0)) (assoc :min (:max m)))))))
