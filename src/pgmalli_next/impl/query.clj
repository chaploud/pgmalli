(ns pgmalli-next.impl.query
  "A query read against a registry: the registry as a query reads it, what a name refers to
   (docs/queries.md#what-a-name-refers-to) and the tables a query reads and writes, the type of
   an expression and the rows a statement returns (docs/queries.md#the-row-a-query-returns).
   What HoneySQL data says without a registry is pgmalli-next.impl.hsyntax; the arguments'
   types pgmalli-next.impl.args; the problems pgmalli-next.impl.problems."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.portable :as portable]
            [pgmalli-next.impl.resolve :as resolve]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(declare derived-types expression-type loose named-type-schema nullable own-routines own-type-schema reached registry-type row-count row-entries type-call)

(defn- versions
  "The versions of the catalog snapshot the registry's generated schemas are of: their file's
   PostgreSQL major, every version the snapshot holds where it is none of them, or there is no
   generated schema."
  [schemas]
  (let [all (:versions @types/catalog)
        vs (for [[k s] schemas :when (and (keyword? k) (= (namespace (shape/routines-key "")) (namespace k)))]
             (types/label-version (:pg/database-version (second s))))]
    (if (and (seq vs) (every? (set all) vs)) (vec (distinct vs)) all)))

(defn- in-versions
  "Overloads or columns xs of the catalog snapshot as of versions vs: one of none of them
   left out, :in narrowed to vs, and left out where it holds them all; the versions as
   :pgmalli/versions metadata, which pgmalli-next.impl.resolve reads them as of."
  [xs vs]
  (with-meta (vec (for [x xs :let [in (when (:in x) (filter (set (:in x)) vs))] :when (or (nil? (:in x)) (seq in))]
                    (if (or (nil? (:in x)) (= (count in) (count vs))) (dissoc x :in) (assoc x :in (vec in)))))
    {:pgmalli/versions vs}))

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
  ;; a type's schema of the name (an enum named as a sequence) is no table's
  (or (some->> (get (::schemas registry) (shape/table-key table)) (#(when (shape/read-row? %) %)) shape/column-entries (map (fn [[k _ s]] [(name k) s])))
      (catalog-columns table (::versions registry))))

(def system-columns
  "The columns every table (no view) has besides its own (pg_attribute's attnum < 0), typed as a
   column of its type is."
  (into {} (for [[c t] {"ctid" "tid" "xmin" "xid" "xmax" "xid" "cmin" "cid" "cmax" "cid" "tableoid" "oid"}]
             [c (or (types/type-schema t) [:any {:pg/type t}])])))

(defn- view?
  "Whether the registry's row schema of table is a view's, not a materialized one's, which is
   stored as a table is."
  [registry table]
  (let [p (some-> (get (::schemas registry) (shape/table-key table)) shape/row-props)]
    (boolean (and (:pg/view p) (not (:pg/materialized p))))))

(defn- held-schemas
  "The schemas whose routines, tables or views a registry's schemas hold, sorted."
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
   public functions, the rows of derived tables and the statements of a query (::calls)."
  [registry]
  (let [schemas (shape/schemas-of registry)
        [read r] @last-read
        r (if (and r (identical? read schemas))
            r
            (let [r {::schemas schemas ::versions (versions schemas)}
                  r (assoc r
                           ::held (held-schemas schemas)
                           ::generated? (boolean (some shape/generated-name? (keys schemas)))
                           ::table-columns (memoize #(some->> (column-entries r %) (map (fn [[c s]] [c s]))
                                                              (into (if (view? r %) {} system-columns))))
                           ::routines (memoize #(own-routines r %1 %2 %3)))]
              (reset! last-read [schemas r])
              r))]
    (assoc r ::derived (atom {}) ::calls (atom {}))))

(defn once
  "(f registry & args), once per call of a pgmalli-next.honeysql public function (reading's ::calls): the functions a
   call makes of one another read the same statements."
  [registry f & args]
  (let [memo (::calls registry)
        k (into [f] args)]
    (if (contains? @memo k)
      (@memo k)
      (let [v (apply f registry args)] (swap! memo assoc k v) v))))

(defn table-columns [registry table] ((::table-columns registry) table))

(defn- known-tables [registry]
  (for [[k v] (::schemas registry) :when (and (keyword? k) (shape/read-row? v))]
    (str/join "." (shape/schema-and-name k))))

(defn quoted-table?
  "Whether table, under a :quoted format, is a table of the registry once folded to lower case."
  [registry table format]
  (boolean (and (syntax/quoted? format) (not= table (syntax/folded table))
                (some #{(syntax/folded table)} (known-tables registry)))))

(defn type-suggestions
  "The enum types (\"schema.name\") of the registry of name n in a schema other than schema, which
   a cast to n there may mean where schema has no type of that name the registry tells of: no
   enum, no table's row type, no domain one of its columns is of. nil for none."
  [registry n schema]
  (let [mine (into #{} (concat (for [t (known-tables registry) :let [[s tn] (str/split t #"\." 2)] :when (= s schema)
                                     x (cons tn (keep (comp :pg/domain shape/column-props second) (column-entries registry t)))]
                                 x)))]
    (when-not (or (mine n) (registry-type registry (keyword n) {:schema schema}))
      (some->> (for [[k v] (::schemas registry)
                     :when (and (keyword? k) (vector? v) (= :enum (first v)))
                     :let [[s tn] (shape/schema-and-name k)]
                     :when (and (= n tn) (not= s schema))]
                 (str s "." tn))
               sort seq vec))))

(defn table-suggestions
  "Up to three tables or views of the registry (\"schema.table\") a table it lacks may mean:
   one of its name in another schema first, then those spelled close."
  [registry table]
  (let [known (known-tables registry)
        same-name (sort (filter #(= (syntax/table-name %) (syntax/table-name table)) known))]
    (some->> (seq (take 3 (distinct (concat same-name (error/suggestions table known 3))))) vec)))

(defn unknown-table-text
  "What an :unknown-table of table says: under :quoted (quoted?), what to write where its name
   folded is a table; where a table of its name is; or what is spelled close."
  [table did-you-mean quoted?]
  (let [elsewhere (first (filter #(= (syntax/table-name %) (syntax/table-name table)) did-you-mean))]
    (str "unknown table " table
         (cond quoted? (str "; under :quoted, PostgreSQL looks the name up as written: for the table "
                            (syntax/folded table) " write :" (syntax/folded table))
               elsewhere (str "; " elsewhere " exists; pass {:schema " (pr-str (subs elsewhere 0 (str/last-index-of elsewhere "."))) "}"
                              ", or name it :" elsewhere)
               did-you-mean (error/did-you-mean (str/join " or " did-you-mean))
               :else "; check its name, or :schema when it is in another schema"))))

(defn unknown-table!
  "The error for table, which a registry holding no schema pgmalli generates cannot know: it
   is no registry of pgmalli-next.core/registry."
  [registry table]
  (when-not (::generated? registry)
    (error/fail! :pgmalli.error/not-a-registry
                 (str "the registry holds no schema pgmalli generates, so table " table
                      " is unknown; pass (pgmalli-next.core/registry)")
                 {:given table})))

(defn with-defaults
  "opts with :schema defaulted; one given that the registry (as reading reads it) holds nothing
   of is an error, unless it holds no schema at all. The default one it lacks is not: a query
   naming every table with its schema needs none, and an unqualified one is an :unknown-table
   saying which schema holds it; with needed?, where the default names a table, an error, which
   names the tables of that name in the schemas held where needed? is the table's name."
  ([registry given] (with-defaults registry given false))
  ([registry given needed?]
  (let [opts (merge {:schema syntax/default-schema} given)
        s (:schema opts)
        held (::held registry)]
    (when (and (or needed? (contains? given :schema)) (seq held) (not (some #{s} held)) (not= "pg_catalog" s))
      (let [meant (error/suggest s held)
            ;; of one schema held, its name says where; of several, the tables of that name
            elsewhere (when (and (string? needed?) (next held))
                        (seq (for [t (table-suggestions registry (str s "." needed?)) :when (= needed? (syntax/table-name t))] (keyword t))))]
        (error/fail! :pgmalli.error/invalid-options
                     (str "the registry holds no schema " (pr-str s) "; "
                          (cond elsewhere (str "for the table of that name, write " (str/join " or " (map pr-str elsewhere))
                                               ", or pass its schema as :schema")
                                (next held) (str "it holds " (str/join ", " (map pr-str held)) (error/did-you-mean (some-> meant pr-str)))
                                :else (str "pass {:schema " (pr-str (first held)) "}")))
                     (cond-> {:given s :known (vec held)}
                       elsewhere (assoc :did-you-mean (vec elsewhere))
                       (and meant (not elsewhere)) (assoc :did-you-mean [meant])))))
    opts)))

(defn- row-name* [registry name what opts]
  (when-not (keyword? name)
    (error/fail! :pgmalli.error/invalid-options
                 (str what " takes a table as a keyword, as HoneySQL names it (:users, :app.users) or its row schema (:pg.public/users); got " (pr-str name))
                 {:given name}))
  ;; a mistyped registry name (:pg.public.users) as it is, for schema-of to name the one meant
  (let [n (if (str/starts-with? (subs (str name) 1) "pg.")
            name
            (let [t (syntax/qualified name (if (or (namespace name) (str/includes? (clojure.core/name name) "."))
                                             (select-keys opts [:format])
                                             (with-defaults registry opts (clojure.core/name name))))]
              (when (< 2 (count (str/split t #"\.")))
                (error/fail! :pgmalli.error/invalid-options
                             (str what " takes a table as :table or :schema.table; " (pr-str name) " names more")
                             {:given name}))
              (shape/table-key t)))]
    (when-not (contains? (::schemas registry) n)
      (unknown-table! registry (if (namespace n) (str/join "." (shape/schema-and-name n)) (clojure.core/name n)))
      ;; a table named as HoneySQL names it: the :unknown-table sentence analyze gives
      (when (not= n name)
        (let [t (str/join "." (shape/schema-and-name n))
              meant (table-suggestions registry t)]
          (error/fail! :pgmalli.error/unknown-schema (str what ": " (unknown-table-text t meant (quoted-table? registry t (:format opts))))
                       (cond-> {:given name} meant (assoc :did-you-mean meant))))))
    n))

(defn row-name
  "The registry name of the row schema a public function (what) is given as name: a registry
   name (:pg.app/users, or a string the registry keys a name no keyword spells by) as it is, a
   table as HoneySQL names it (:users in :schema, :app.users);
   an error for another name, or one the registry lacks, naming what was meant."
  [registry name what opts]
  (with-defaults registry opts)
  (if (contains? (::schemas registry) name)
    name
    (row-name* registry name what opts)))

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
  [registry stmt ctes {:keys [format] :as opts}]
  (let [refs (concat (syntax/from-items stmt syntax/from-keys)
                     (map second (syntax/joins stmt))
                     (syntax/write-targets stmt))
        nullable (syntax/outer-aliases stmt opts)
        ;; UPDATE, DELETE FROM and INSERT INTO name a table, which a CTE of the same name does not hide
        targets (set (syntax/write-targets stmt))
        using (set (for [[_ _ j] (syntax/joins stmt) :when (and (vector? j) (= :using (first j))) c (rest j) :when (keyword? c)]
                     (syntax/ident-name c format)))
        sc (into (with-meta {} {::using using})
                 (for [r refs :let [[table alias written listed] (syntax/table-ref r opts)] :when alias
                       :let [cte? (boolean (and written (not (targets r)) (ctes written)))
                             table (when table (catalog-table registry table written))
                             ;; a name the registry holds a type of (a sequence an enum's name hides) is opaque, not unknown
                             unknown? (boolean (and table (not cte?) (not (syntax/catalog? table)) (nil? (table-columns registry table))
                                                    (not (contains? (::schemas registry) (shape/table-key table)))))
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
                            (syntax/unnest-elements r format) (assoc :elements (syntax/unnest-elements r format)))]))
        ;; ON CONFLICT's EXCLUDED, the row proposed for insertion, and PostgreSQL 18's RETURNING old and
        ;; new: the target's columns, which only a qualifier names
        [_ target] (some-> (first (syntax/write-targets stmt)) (syntax/table-ref opts))
        row (when target (assoc (get sc target) :qualifier-only? true))]
    (cond-> sc
      (and row (contains? stmt :on-conflict)) (assoc "excluded" row)
      ;; old is NULL for a row an INSERT inserts (DO UPDATE's has one), new always in a DELETE
      (and row (contains? stmt :returning))
      (merge (into {} (for [[n absent?] [["old" (contains? stmt :insert-into)] ["new" (contains? stmt :delete-from)]] :when (not (contains? sc n))]
                        [n (cond-> row absent? (assoc :nullable? true))]))))))

(defn populate-call
  "{:table :set? :type :json :arg} of x, a call of pg_catalog's json_populate_record,
   jsonb_populate_record or their recordset (set? true) of the row type of a table or view of
   the registry (\"schema.table\"): NULL cast to it ([:cast nil :t]), or the whole row of a table
   in scopes (:p, its alias); or of json_to_record, jsonb_to_record or their recordset, whose
   alias defines the row (populated-columns), table nil. json is its argument of the document,
   of type (\"json\" or \"jsonb\"); arg the argument there, bare or cast to json or jsonb, nil
   for another value. nil for another x."
  [registry scopes x {:keys [schema format]}]
  (let [[kind n args q] (syntax/call-parts x)
        to? (some-> n (str/includes? "_to_"))
        [a json :as args] (if to? (cons nil args) args)]
    (when (and (= :functions kind) (contains? #{nil "pg_catalog"} q) (= 2 (count args)) (re-matches syntax/populate-name n))
      (let [table (when-not to?
                    (cond (and (vector? a) (= :cast (first a)) (= 3 (count a)) (or (nil? (second a)) (= :null (syntax/sql-value (second a)))))
                          ;; HoneySQL writes a cast's type unquoted
                          (when-let [t (syntax/cast-target (nth a 2))] (when-not (types/element t) (first (syntax/table-ref (keyword t) {:schema schema}))))
                          (and (syntax/column-keyword? a) (nil? (first (syntax/split-column a format))))
                          (let [{:keys [table opaque?]} (some #(get % (syntax/ident-name a format)) (filter map? (syntax/chain scopes)))]
                            (when-not opaque? table))))]
        (when (or to? (and table (column-entries registry table)))
          {:table table :set? (str/ends-with? n "set") :type (if (str/starts-with? n "jsonb") "jsonb" "json") :json json
           :arg (cond (syntax/param-key json) json
                      (and (vector? json) (= :cast (first json)) (= 3 (count json)) (syntax/param-key (second json))
                           (some->> (syntax/cast-target (nth json 2)) str/lower-case types/json?))
                      (second json))})))))

(defn populated-columns
  "[[column schema] ...] of the row populate-call p makes as FROM item: its table's columns, or
   those its alias's column definition list defines, of the types it names (nil of a type
   named-type-schema does not read); nil where it defines none."
  [registry p item opts]
  (if (:table p)
    (column-entries registry (:table p))
    (some->> (syntax/defined-columns item (:format opts))
             (mapv (fn [[c t]] [c (some->> t (named-type-schema registry opts))])))))

(defn- function-columns
  "[[column schema] ...] of a function of pg_catalog or of a generated schema in FROM
   ([[:f args] alias], [[:with-ordinality [:f args] alias]]): its OUT columns (a generated
   schema's: its composite type's too), or one of its result type named after
   the alias (the function without one), each nullable and of no table, renamed by the names the
   alias lists; WITH ORDINALITY adds ordinality, a bigint; of a populate-call, its
   populated-columns, nullable. nil where that is not told."
  [registry item chain opts]
  (let [[a b] (if (vector? item) item [item])
        [f ordinality? al] (if (and (vector? a) (= :with-ordinality (first a))) [(second a) true (get a 2)] [a false b])
        [al listed] (syntax/alias-columns al ordinality?)
        [kind n args :as parts] (when (vector? f) (syntax/call-parts f))
        renamed (fn [cs] (vec (map-indexed (fn [i [c s]] [(if (< i (count listed)) (syntax/ident-name (nth listed i) (:format opts)) c) s]) cs)))
        populated (when (and (not ordinality?) (or (nil? listed) (sequential? listed)))
                    (when-let [p (populate-call registry chain f opts)] (populated-columns registry p item opts)))]
    (if populated
      ;; the columns of the row type, NULL where the document lacks a key
      (renamed (for [[c s] populated] [c (some-> s nullable)]))
      ;; a column definition list where the function returns no record PostgreSQL refuses
      ;; (:column-definition-list): no column of it told
      (when (and (= :functions kind) (not (syntax/column-definitions item)) (or (nil? listed) (and (sequential? listed) (every? ident? listed))))
        ;; unnest(a, b) is ROWS FROM (unnest(a), unnest(b)), its columns each named unnest
        (when-let [rs (let [rs (for [as (if (syntax/rows-from? n args) (map vector args) [args])]
                                 (resolve/call kind n (mapv #(:type (expression-type registry chain % opts)) as) (reached registry opts parts)))]
                        (when (every? some? rs) rs))]
          (let [label (if (and (syntax/alias? al) (nil? (next rs))) (syntax/alias-label al opts) n)
                cs (vec (mapcat (fn [{t :result o :overload}] (or (seq (:columns o)) (when-not (= "record" t) [[label t]]))) rs))
                ;; a function of one column: of the type its call is in a select list (a jsonb shape's
                ;; part, an enum's labels), which expression-type tells for both
                one (when (and (= 1 (count cs)) (nil? (next rs))) (:schema (expression-type registry chain f opts)))
                cs (cond-> cs ordinality? (conj ["ordinality" "bigint"]))
                own #(own-type-schema registry % (:schema (:overload (first rs))))]
            (when (and (seq cs) (every? #(or (types/category (second %)) (own (second %))) cs))
              (renamed (for [[i [c ct]] (map-indexed vector cs)]
                         [c (when-let [s (or (when (and one (zero? i)) (shape/non-null one)) (when (string? ct) (types/type-schema ct)) (own ct))]
                              (cond->> s (not= "ordinality" c) (vector :maybe)))])))))))))

(defn- typed-scope
  "sc with :types ({column schema}, a column whose type is not told nil) on the subqueries and CTEs of stmt whose SELECT types its columns, read as a query of their own,
   under the CTEs of ctes it reads (standalone)."
  [registry body stmt sc ctes outer opts]
  (let [items (syntax/table-items stmt)]
    (reduce (fn [sc item]
              (let [[_ alias written] (syntax/table-ref item opts)
                    {:keys [table cte? columns] :as entry} (get sc alias)
                    lateral (syntax/lateral? item)
                    [inner listed recursive? added] (cond (or (nil? entry) columns) nil
                                                    cte? (syntax/cte-statement body written opts)
                                                    (and (nil? table) (vector? item) (map? (first item))) [(first item)]
                                                    ;; [[:lateral subquery] alias]
                                                    (and (nil? table) (vector? item) (vector? (first item)) (= :lateral (ffirst item)) (map? (second (first item))))
                                                    [(second (first item))]
                                                    (and (nil? table) (map? item)) [item])
                    ;; a CTE's body comes under the entries before it already: the CTEs it reads beyond them are outer ones
                    own (when inner (let [o (syntax/standalone body inner ctes opts)] (when (or (not cte?) (identical? o inner)) o)))
                    ts (cond inner
                             (when own
                               (if lateral
                                 ;; a LATERAL subquery sees the tables before it
                                 (derived-types registry own opts (cons (dissoc sc alias) outer))
                                 (derived-types registry own opts)))
                             (and entry (nil? inner) (nil? table) (not cte?) (vector? item) (not (map? (first item))))
                             ;; a function sees the tables before it too
                             (function-columns registry item (cons (dissoc sc alias) outer) opts))
                    named (concat (map-indexed (fn [i [c t]] [(nth listed i c) t]) ts)
                                  (when ts (for [[c t] added] [c (some-> t types/type-schema)])))
                    ;; a CTE's name may list new names for its columns, by position; a recursive
                    ;; one's later rows may hold NULL where its first member's do not
                    types (when ts (into {} (for [[c t] named] [c (if (and t recursive?) (nullable t) t)])))]
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
                        base (if open? (syntax/cte-names body (:format opts)) ctes)
                        ctes (into base (keep #(syntax/cte-name % (:format opts))) (syntax/cte-entries x))
                        outer (if open? (list :open) outer)
                        chain (cons (vary-meta (typed-scope registry body x (scope registry x ctes opts) ctes outer opts) assoc ::ctes ctes ::query body)
                                     outer)]
                    (cons [x chain path outer]
                          (concat
                           (for [k syntax/with-keys :let [w (get x k)] :when (some? w)
                                 found (if (vector? w)
                                         (apply concat (map-indexed (fn [i e] (walk e (conj path k i) outer (into base (keep #(syntax/cte-name % (:format opts))) (if (= :with-recursive k) w (take i w))) false true)) w))
                                         (walk w (conj path k) outer ctes false true))]
                             found)
                           (for [[ks v sees] (letfn [(parts [ks v sees]
                                                      (if (= :group sees)
                                                        (mapcat (fn [[p part s]] (parts (into ks p) part s)) (syntax/group-parts v))
                                                        [[ks v sees]]))]
                                               (mapcat #(apply parts %) (syntax/clause-parts x)))
                                 found (walk v (into path ks) (syntax/part-chain sees v chain outer opts) ctes false true)]
                             found))))
                  (syntax/branch? x) (apply concat (map-indexed (fn [i c] (walk c (conj path i) outer ctes (or in-vector? (vector? x)) under?)) (seq x)))))]
    (walk body [] outer #{} false false))))

(defn- has-column?
  "Whether a scope entry may hold a column: one it lists, one its SELECT gives (a typed
   subquery or CTE), one of its table's, or anything for an opaque table. A list renames the
   first columns alone: the others keep their names."
  [registry {:keys [table opaque? columns types all-listed?] :as entry} column]
  (cond columns (or (boolean (some #{column} columns))
                    (and (not all-listed?) (has-column? registry (dissoc entry :columns) column)))
        types (contains? types column)
        opaque? true
        :else (contains? (table-columns registry table) column)))

(defn- own-hits
  "[[alias table opaque? nullable? types elements index] ...] of the tables of one scope a column may belong to."
  [registry scope col]
  (let [[alias column] (syntax/split-column col (::format registry))
        ;; a subquery's or a CTE's columns of one name are as many hits: PostgreSQL finds it ambiguous
        each (fn [a {:keys [table opaque? nullable? types elements order]}]
               (for [i (range (max 1 (count (filter #{column} order))))] [a table opaque? nullable? types elements i]))]
    (if alias
      (if-let [entry (get scope alias)]
        (when (has-column? registry entry column) (vec (each alias entry)))
        ;; a raw FROM item whose name is not read may be the one alias names
        (when-let [[a] (first (filter (comp syntax/unnamed-raw? key) scope))] [[a nil true]]))
      ;; an unqualified column is never EXCLUDED's: that row is only reached through its name
      (distinct (for [[a {:keys [qualifier-only?] :as entry}] scope
                      :when (and (not qualifier-only?) (has-column? registry entry column))
                      hit (each a entry)]
                  hit)))))

(defn column-hits
  "The hits of the innermost scope (a map, or the chain of a statement and its enclosing
   ones) that has any."
  [registry scopes col]
  (let [[sc & outer] (syntax/chain scopes)
        [alias column] (syntax/split-column col (::format registry))
        hits (own-hits registry sc col)
        ;; JOIN ... USING (c) merges the c of the tables it joins into one column
        hits (if (and (next hits) (nil? alias) (contains? (::using (meta sc)) column)) (take 1 hits) hits)]
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
        column (second (syntax/split-column col (::format registry)))
        [opaque & _] (filter #(nth % 2) hits)]
    (cond (= 1 (count hits))
          (let [[alias table opaque? nullable? types elements] (first hits)
                ;; a subquery's or a CTE's columns, as its SELECT types them
                cs (or types (when-not opaque? (table-columns registry table)))]
            {:table (or table alias) :column column
             :schema (get cs column)
             :nullable? (boolean nullable?) :opaque? (boolean opaque?)
             :element (get elements column)})
          ;; one table's columns of that name: ambiguous, whatever is opaque
          (some #(pos? (nth % 6 0)) hits) nil
          (and opaque (>= 1 (count (remove #(nth % 2) hits))))
          (let [[alias table] opaque] {:table (or table alias) :column column :schema nil :nullable? true :opaque? true}))))

(defn tables
  "{:reads #{\"schema.table\" ...} :writes #{...}}: the tables and views a query reads (FROM,
   joins, USING, in every subquery and CTE) and writes (INSERT INTO, UPDATE, DELETE FROM,
   TRUNCATE), aliases and CTE names resolved."
  [registry body opts]
  (let [found (for [[stmt scopes] (once registry statement-chains body opts)
                    :let [own (first scopes)
                          targets (set (keep #(some-> % (syntax/table-ref opts) second) (syntax/write-targets stmt)))]
                    [alias {:keys [table cte? qualifier-only?]}] (when (map? own) own)
                    :when (and table (not cte?) (not qualifier-only?))]
                [(if (targets alias) :writes :reads) table])]
    (merge {:reads #{} :writes #{}}
           (update-vals (group-by first found) #(set (map second %))))))

(defn statement-positions
  "[statement scopes path positions] for the statements of query body (statement-chains), the
   SELECT of an INSERT ... SELECT fed the INSERT's columns; none in a clause analyze does not
   read (an :unread-clause), nor a position of one."
  [registry body opts]
  (let [chains (once registry statement-chains body opts)
        feeds (for [[s c] chains :let [{:keys [columns select]} (syntax/insert-parts s)] :when (and columns (map? select))]
                [select {:columns columns :target (syntax/target-scope c s opts)}])]
    (for [[s c path outer] chains
          :when (not (syntax/under-unread-clause? body path))]
      [s c path (remove (comp syntax/unread-clause? :clause)
                        (syntax/positions s c outer (some (fn [[select f]] (when (identical? select s) f)) feeds) opts))])))

(defn- labels
  "The [:enum ...] of an enum column: an enum type's, or a domain's over one."
  [registry schema]
  (let [s (shape/non-null schema)]
    (when (vector? s)
      (case (first s)
        :enum s
        :ref (labels registry (shape/ref-target (::schemas registry) s))
        nil))))

(defn enum-values
  "The labels of an enum column, in the enum's order."
  [registry schema]
  (some->> (labels registry schema) rest (remove map?) vec))

(defn domain-of
  "The domain a column schema declares: d, or d[] for an array of domain d; nil for none."
  [s]
  (some-> s shape/column-props :pg/domain))

(defn- scalar-domain
  "The domain of a column schema of a domain (over an array too): a value of it is of its base
   type where PostgreSQL resolves types. An array of a domain (d[]) is a type of its own."
  [s]
  (when-let [d (domain-of s)] (when-not (types/element d) d)))

(defn- computed-of-one-type
  "The schema of a value computed from values all of s's type: s without its bounds (loose), but
   a domain's or an enum's stays, as PostgreSQL types the value (and a domain's array, which
   pgjdbc reads as the domain's PGobjects)."
  [s]
  (if (or (domain-of s) (= :ref (shape/head (shape/non-null s)))) s (loose s)))

(defn table-props
  "The properties of the row schema of table (\"schema.table\")."
  [registry table]
  (shape/row-props (get (::schemas registry) (shape/table-key table))))

(defn assigned-column
  "{:schema :table :opaque? :nullable?} of the column an assignment (positions) writes, nil
   :schema where it is not told; nullable? where NULL may go there: the column takes it, a
   BEFORE row trigger for the write may fill it (an INSERT's value is the row proposed, whose
   NOT NULL PostgreSQL checks before ON CONFLICT DO UPDATE)."
  [registry {:keys [assign target event]}]
  (let [{s :schema :keys [table opaque?] :as c} (resolve-column registry target assign)]
    (assoc c :nullable? (boolean (or (and s (shape/maybe? s))
                                     (and s (not opaque?) (shape/filled-by-trigger? (table-props registry table) event)))))))

(defn enum-type
  "The type of an enum type's column, of a domain over one, or of an expression's value of one."
  [registry s]
  (let [base (shape/non-null s)
        n (shape/type-name (shape/column-props s))]
    (cond (and (= :ref (shape/head base)) (labels registry base)) n
          ;; an expression's value of an enum type (coalesce(tier, ?)): its labels with the type's name, which no base type has
          (and (= :enum (shape/head base)) n (nil? (types/entry n))) n)))

(defn cast-type-name
  "The name a query under opts' :schema casts to column schema s's type by: its :pg/type,
   qualified by the schema of the registry's type s refers to ([:ref ... :pg.billing/state])
   where that is not :schema and the name is neither qualified nor pg_catalog's."
  [s {:keys [schema]}]
  (let [n (:pg/type (shape/column-props s))
        k (some #(when (and (vector? %) (= :ref (first %))) (last %)) (tree-seq coll? seq s))
        [sch] (when k (shape/schema-and-name k))]
    (if (and n sch (not= sch schema) (not (str/includes? n ".")) (nil? (types/entry (or (types/element n) n))))
      (str sch "." n)
      n)))

(defn slot-type
  "The type of a column schema as pgmalli-next.impl.accept names it: [:enum name] for an enum type
   (or a domain over one), a domain's base type, \"x[]\" for an array; nil for a type it does
   not answer for."
  [registry s]
  (let [p (shape/column-props s)
        t (if-let [e (types/entry (shape/type-name p))]
            (cond-> (:name e) (types/element (:pg/type p)) types/array-of)
            (when-let [n (enum-type registry s)] (cond-> [:enum n] (:pg/domain p) (conj {:domain? true}))))]
    (when (accept/answers? t) t)))

(defn side-schema
  "The column schema of one side of a comparison: a column's, or an expression's where
   expression-type tells it; nil otherwise."
  [registry sc side opts]
  (cond (syntax/column-keyword? side) (:schema (resolve-column registry sc side))
        ;; x op ANY(array) compares x with the array's elements (docs 9.24.3)
        (syntax/array-of-elements? side)
        (let [{t :type a :schema} (expression-type registry sc (second side) opts)]
          ;; an element of an array of domain d is of d, which = ANY compares as it compares d
          (if-let [d (some-> a domain-of types/element)]
            (shape/merge-props (last (shape/non-null a)) {:pg/type (types/element (:pg/type (shape/column-props a))) :pg/domain d})
            (some-> t types/element types/type-schema)))
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
  "A type that says nothing of a value but NULL: :any, or :some (a NOT NULL column's), with or
   without properties."
  [t]
  (or (nil? t) (contains? #{:any :some} t) (and (vector? t) (contains? #{:any :some} (first t)))))

(defn nullable
  "t or NULL."
  [t]
  (cond (shape/maybe? t) t
        (= :some (shape/head t)) (if (vector? t) (assoc t 0 :any) :any)
        (untyped? t) t
        :else [:maybe t]))

(defn- registry-type
  "The registry's key and schema of the enum type a cast names (:mood, :app.mood);
   nil for another name."
  [registry t {:keys [schema]}]
  (when (ident? t)
    (let [parts (str/split (name t) #"\." 2)
          [sch n] (if (next parts) parts [schema (first parts)])
          k (shape/schema-key sch n)
          s (get (::schemas registry) k)]
      (when (and s (empty? (shape/column-entries s))) [k s]))))

(defn loose
  "A column type without the bounds a comparison need not keep (lengths, ranges): its
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
            ;; an array column's :pg/type is what tells pgjdbc's java.sql.Array (an argument) from a read
            ;; vector; :pg/classes the Java arrays a whole array compared takes
            ;; :pg/domain the domain of its elements, which pgjdbc reads as PGobjects
            (#{:vector :sequential :set :pg/array} h) (let [kept (select-keys p [:pg/type :pg/classes :pg/domain])]
                                                        (if (seq kept) [h kept (loose (last t))] [h (loose (last t))]))
            (= :or h) (into (cond-> [:or] p (conj p)) (map loose children))
            (seq children) t
            :else (if-let [pg (:pg/type p)] [h {:pg/type pg}] h)))))

(defn- domain-base-type
  "s, or the base type of the domain s is of: the type PostgreSQL resolves a function's argument
   of a domain to."
  [s]
  (if (shape/maybe? s)
    [:maybe (domain-base-type (last s))]
    (let [p (shape/props s)]
      (cond (not (scalar-domain s)) s
            (= 1 (count p)) (into [(first s)] (drop 2 s))
            :else (assoc s 1 (dissoc p :pg/domain))))))

(defn- domain-array
  "The schema of an array of values of column schema s where s is of a domain: the domain's
   array, as PostgreSQL keeps it in ARRAY[...] and in a polymorphic function's anyarray bound by
   anyelement (array_agg); nil otherwise."
  [s]
  (let [s (some-> s shape/non-null)]
    (when-let [d (scalar-domain s)]
      [:vector {:pg/type (types/array-of (:pg/type (shape/column-props s))) :pg/domain (types/array-of d)} s])))

(defn- canonical-type [{s :schema}]
  (if (and (domain-of s) (not (scalar-domain s)))
    (domain-of s)
    (some-> s shape/column-props :pg/type types/canonical-name)))

(defn resolver-type
  "The type pgmalli-next.impl.resolve takes for a value of column schema s: a domain's base type,
   [:enum name] for an enum type; nil where s names none."
  [registry s]
  (when s
    (let [b (domain-base-type s)]
      (if-let [n (enum-type registry b)]
        [:enum n]
        (canonical-type {:schema b})))))

(defn own-type?
  "Whether expression type m is of type t itself, not of a domain over it."
  [m t]
  (and (= t (:type m)) (= (if (vector? t) (second t) t) (canonical-type m)) (not (scalar-domain (:schema m)))))

(defn- result-schema
  "The schema of a value of resolver type t computed from inputs (expression types): an input's
   where it is of t (a domain's base type, an enum's labels; without bounds), else t's. A json or
   jsonb input's shape (an override's) is no shape of a value computed from it."
  [t inputs]
  (or (some #(let [s (domain-base-type (:schema %))]
               ;; a typmod reads otherwise (bit(1) a Boolean), which a value computed from others may not keep
               (when (and (= t (:type %)) (not (types/json? t)) (not (str/includes? (str (:pg/type (shape/column-props s))) "("))
                          (not (:pg/max-length (shape/column-props s))))
                 (loose s)))
            inputs)
      (when (string? t) (types/type-schema t))))

(defn common
  "The expression type PostgreSQL resolves expression types ms to, as it resolves UNION, CASE
   and coalesce (pgmalli-next.impl.resolve/common-type): of one type, the first one's; nil where one
   is not told. An argument takes the others' type where that is a string type: pgjdbc sends a
   number or a date typed, which may widen it beyond the others'."
  [ms]
  (when (and (seq ms) (every? :type ms))
    (let [ts (map :type ms)
          t (resolve/common-type ts)
          typed (remove (comp resolve/unbound? :type) ms)]
      (when (and t (or (not-any? #{:free} ts) (= "S" (types/category t))))
        {:type t
         ;; inputs all of one domain resolve to it (select_common_type); with another, to its base type
         :schema (if (and (seq typed) (apply = (map (juxt :type #(:pg/type (shape/column-props (:schema %))) (comp domain-of :schema)) typed))
                          (or (own-type? (first typed) (:type (first typed))) (= (count typed) (count ms)))
                          ;; json or jsonb values of one shape alone
                          (or (not (types/json? t)) (and (= (count typed) (count ms)) (apply = (map (comp shape/non-null :schema) typed)))))
                   (cond->> (:schema (first typed)) (next ms) computed-of-one-type)
                   (result-schema t typed))}))))

(def common-type-functions
  "The functions whose arguments PostgreSQL resolves to one type, and that returns it."
  #{"coalesce" "greatest" "least"})

(defn resolving-operands
  "The operands x has PostgreSQL resolves to one type: coalesce's, greatest's, least's,
   nullif's, CASE's values."
  [x]
  (cond (or (common-type-functions (syntax/called x)) (= "nullif" (syntax/called x))) (rest x)
        (and (vector? x) (= :case (first x)) (even? (count (rest x)))) (map second (partition 2 (rest x)))))

(defn- using-joins
  "{:merges {column {:tables [alias ...] :schema s :nullable? bool}} :missing [{:column c :table
   t} ...]} of a statement's JOIN ... USING, each
   join merging its left side (a table's column, or what an earlier join merged) with the right
   table's in turn: :tables every table merged, :schema nil when the types differ.
   PostgreSQL takes the left column (INNER JOIN the right one when only the left is cast to
   the merged type), RIGHT JOIN the right one, FULL JOIN neither: a COALESCE; a column cast
   to the merged type keeps no table."
  [registry stmt sc opts]
  (let [alias #(second (syntax/table-ref % opts))
        col-schema (fn [a column]
                     (when-let [{:keys [table opaque?]} (get sc a)]
                       (when-not opaque? (get (table-columns registry table) column))))
        ;; the type without what does not change it (a default, constraints)
        type-of #(let [s (shape/non-null %)] (if (and (vector? s) (map? (second s))) (assoc s 1 (select-keys (second s) [:pg/type :pg/max-length :min :max])) s))
        table-side (fn [a column] (let [s (col-schema a column)] {:schema s :nullable? (boolean (or (nil? s) (shape/maybe? s) (:nullable? (get sc a))))}))
        merged-with (fn [join l r]
                ;; a timestamp's or time's precision is not in the registry, so (3) and (6) read as one type
                (let [same? (and (:schema l) (:schema r) (= (type-of (:schema l)) (type-of (:schema r))))
                      side (case join (:join :inner-join :left-join) l :right-join r nil)]
                  (cond (nil? side) {:schema (when same? (:schema l)) :nullable? true}
                        same? side
                        ;; one side cast to the merged type: which one, the registry does not say
                        :else {:schema nil :nullable? true})))]
    (reduce (fn [{:keys [before] :as acc} [k right c]]
               (-> (if (and (vector? c) (= :using (first c)))
                     (reduce (fn [acc col]
                               (let [col (syntax/ident-name col (:format opts))
                                     earlier (get-in acc [:merges col])
                                     ;; USING names a column of the relations' own (no system column)
                                     holders (when-not (system-columns col) (filter #(when-let [e (get sc %)] (has-column? registry e col)) before))
                                     ;; a column the left side or the right table lacks (column ... specified in USING clause does not exist)
                                     acc (cond-> acc
                                           (and (nil? earlier) (empty? holders) (every? #(get sc %) before))
                                           (update :missing conj {:column (keyword col) :side :left :table (:table (get sc right))})
                                           (and (get sc right) (or (system-columns col) (not (has-column? registry (get sc right) col))))
                                           (update :missing conj {:column (keyword col) :side :right :table (:table (get sc right))}))
                                     l (cond earlier earlier
                                             (= 1 (count holders)) (table-side (first holders) col)
                                             :else {:schema nil :nullable? true})]
                                 (assoc-in acc [:merges col] (assoc (merged-with k l (table-side right col))
                                                                    :tables (conj (vec (or (:tables earlier) holders)) right)))))
                             acc (filter keyword? (rest c)))
                     acc)
                   (update :before conj right)))
             {:before (vec (syntax/join-base stmt opts)) :merges {} :missing []}
             (for [[k t c] (syntax/joins stmt)] [k (alias t) c]))))

(defn- using-merges [registry stmt sc opts] (:merges (using-joins registry stmt sc opts)))

(defn using-missing
  "The columns a statement's JOIN ... USING names that its left side or its right table lacks:
   [{:column c :side :left|:right :table t}]."
  [registry stmt sc opts]
  (:missing (using-joins registry stmt sc opts)))

(defn reached
  "The overloads a call (call-parts) reaches: those of the schema it names, else pg_catalog's and
   :schema's, pg_catalog first on the search path as PostgreSQL puts it when the path does not
   name it (docs 5.9.6). A schema the registry has no :functions of (a file generated before
   them, a schema not generated) has none."
  [registry {:keys [schema]} [kind n _ q]]
  (in-versions (resolve/overloads kind n (::routines registry) (if q [q] ["pg_catalog" schema]))
               (::versions registry)))

(defn- own-type-schema
  "The column schema of a value of an enum type of the registry, t named as the generated file
   names it in schema s, or as pgmalli-next.impl.resolve takes an enum type ([:enum
   name]); nil for another type."
  [registry t s]
  (let [n (if (vector? t) (second t) t)]
    (when-let [[k] (when (string? n) (registry-type registry (keyword n) {:schema s}))]
      [:ref {:pg/type n} k])))

(defn- own-routines
  "The overloads of function (kind :functions) or operator (:operators) n of schema s in the
   generated file, the argument and result types as pgmalli-next.impl.resolve takes them (an enum
   type [:enum name]; generation names a domain by its base type)."
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

(defn- json-key-literal
  "The key a json operator's argument a names, written in the query: a string or an inline one."
  [a]
  (cond (string? a) a
        (and (vector? a) (= :inline (first a)) (string? (second a))) (second a)))

(def ^:private json-parts
  "What part of a json or jsonb value of a shape (an override's, without NULL) a call takes, as
   PostgreSQL defines it, by [kind name]: f of the shape and the call's second argument, nil where
   its shape is not told. -> an inline integer (HoneySQL sends a number as a bigint, which ->
   takes none of) an array's element, -> a key a map's value of it; - a key, or an ARRAY of keys,
   the map without them, a map-of as it is; json_array_elements and jsonb_array_elements an
   array's element."
  (let [array? #(#{:vector :sequential} (shape/head %))
        element (fn [s _] (when (array? s) (last s)))
        keys-of (fn [a] (if-let [k (json-key-literal a)]
                          [k]
                          (when (and (vector? a) (= :array (first a)) (sequential? (second a)))
                            (let [ks (map json-key-literal (second a))] (when (every? some? ks) ks)))))]
    {[:operators "->"] (fn [s a]
                         (cond (and (array? s) (vector? a) (= :inline (first a)) (integer? (second a))) (last s)
                               (= :map (shape/head s))
                               (when-let [k (json-key-literal a)]
                                 (some (fn [e] (let [[ek _ es] (shape/entry-parts e)] (when (= k (name ek)) es))) (shape/entries s)))))
     [:operators "-"] (fn [s a]
                        (when-let [ks (some-> (keys-of a) set)]
                          (case (shape/head s)
                            :map (into [:map (into {} (remove (fn [[k]] (or (= "pg" (namespace k)) (= :default k)))) (shape/props s))]
                                       (remove (fn [e] (ks (name (first e)))) (shape/entries s)))
                            :map-of s
                            nil)))
     [:functions "json_array_elements"] element
     [:functions "jsonb_array_elements"] element}))

(defn- json-shape
  "Shape s of a json or jsonb value without NULL, through the references of the registry's
   schemas ([:ref k], k) to the schema they name."
  [registry s]
  (let [schemas (::schemas registry)]
    (loop [s (shape/non-null s) seen #{}]
      (let [k (cond (and (vector? s) (= :ref (first s))) (last s) (and (keyword? s) (namespace s)) s)
            target (when (and k (not (seen k))) (get schemas k))]
        (if target
          (recur (shape/non-null (cond-> target (m/schema? target) m/form)) (conj seen k))
          s)))))

(defn- json-part-schema
  "The schema of the part of a json or jsonb value of shape s that call n (kind) of args, of result
   type t, takes (json-parts); nil for another call or shape."
  [registry kind n args s t]
  (when-let [f (json-parts [kind n])]
    (some-> (f (json-shape registry s) (second args)) (shape/merge-props {:pg/type t}))))

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
          schema (if-let [shape (and catalog? (= :functions kind) (types/json? t) (json-shapes n))]
                   (if (= :map shape) [:map {:pg/type t}] [:sequential {:pg/type t} :any])
                   (or (when (and (types/json? t) (types/json? (:type (first ts))))
                         (json-part-schema registry kind (name n) args (:schema (first ts)) t))
                       (when (= "anyarray" (:result o))
                         (some (fn [[d m]] (when (#{"anyelement" "anynonarray"} d) (domain-array (:schema m))))
                               (map vector (:args o) ts)))
                       ;; array_agg of arrays adds a dimension, which an array type does not name
                       (when (and catalog? (= "array_agg" (name n)) (= ["anyarray"] (:args o)))
                         [:any {:pg/type t}])
                       (result-schema t ts)
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
    (when-let [stmt (let [{q ::query cs ::ctes} (meta (first c))] (syntax/standalone q stmt cs opts))]
      (let [[[_ s] more] (derived-types registry stmt opts c)]
        (when (and s (nil? more))
          (let [s (shape/non-null s)] {:schema s :type (resolver-type registry s) :nullable? true}))))))

(defn- of-type
  "The expression type of a value of type t (a name), nil for none."
  [t]
  (when t {:schema (types/type-schema t) :type t}))

(defn- literal-type
  "The expression type of a literal x, a value of no HoneySQL syntax: a boolean, which HoneySQL
   writes TRUE or FALSE; one HoneySQL sends as a parameter, as the one sending sends its class;
   v of [:inline v] (inline?) or any under sql/format's :inline, written into the SQL, as
   PostgreSQL types the literal (jdbc/inline-type), a quoted text an unknown literal."
  [x inline? opts]
  (let [t (cond (boolean? x) "boolean"
                (or inline? (:inline (:format opts))) (jdbc/inline-type x)
                :else (jdbc/sent-type x opts))]
    (if (= :unknown t) {:type :unknown :schema (types/type-schema "text")} (of-type t))))

(defn named-type-schema
  "The column schema of a value of type n (as cast-target names it, \"x[]\" an array, or as
   pgmalli-next.impl.resolve names an enum type): a type of pg_catalog, or an enum type of the
   registry, read as a column of it is; nil for another (a domain, which the registry holds no
   entry of)."
  [registry opts n]
  (or (when (string? n) (types/type-schema (types/cast-name n)))
      (let [e (when (string? n) (types/element n))]
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
      (when-let [r (when (seq es) (common ms))]
        (when (and (string? (:type r)) (not (types/element (:type r))))
          (some-> (or (some->> (domain-array (:schema r)) (hash-map :type (types/array-of (:type r)) :schema))
                      (of-type (types/array-of (:type r))))
                  (assoc :nullable? false)))))))

(defn- nullif-type
  "The expression type of nullif of arguments of expression types ms: its first argument's, as =
   takes it; of two of one extension's string type (citext), that type, whose own = the catalog
   does not hold."
  [ms]
  (when (= 2 (count ms))
    (if (and (types/extension-string? (:type (first ms))) (apply = (map :type ms)))
      (assoc (first ms) :nullable? true)
    (when-let [{[t] :args :as r} (resolve/call :operators "=" (mapv :type ms) (resolve/overloads :operators "="))]
      (when (= "boolean" (:result r))
        (when-let [s (result-schema t ms)] {:type t :schema s :nullable? true}))))))

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
      (not (or (keyword? x) (symbol? x) (coll? x))) (literal-type x false opts)
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
      (= :inline h) (let [v (second x)] (when-not (or (nil? v) (keyword? v) (symbol? v) (coll? v)) (literal-type v true opts)))
      ;; [:lift v] sends v as a parameter as it is, a keyword or a vector too: a collection as the one
      ;; sending sends its class (a map as jsonb)
      (= :lift h) (let [v (second x)]
                    (cond (keyword? v) nil
                          (coll? v) (let [t (jdbc/sent-type v opts)] (when (string? t) (of-type t)))
                          :else (sub v)))
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
                    (when-let [r (when (every? #(= 2 (count %)) parts) (common (cons (last vts) (butlast vts))))]
                      (assoc r :schema (some->> (:schema r) computed-of-one-type)
                             :nullable? (or (not else?) (boolean (some :nullable? vts))))))
      (common-type-functions (syntax/called x))
      (some-> (common (map sub (rest x)))
              ;; greatest and least skip NULLs as coalesce does
              (assoc :nullable? (not-any? #(and % (not (:nullable? %))) (map sub (rest x)))))
      (= "nullif" (syntax/called x)) (nullif-type (map sub (rest x)))
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
  (when-let [[kind n [a] q :as parts] (when (and (vector? x) (= 2 (count x))) (syntax/call-parts x))]
    ;; the name as PostgreSQL reads a function's HoneySQL writes ([:MOOD a] is MOOD(a), mood)
    (let [t (keyword (if q (str q "." n) n))]
      (when (and (= :functions kind) (or (resolve/type-name? n) (registry-type registry t opts)))
        (let [in (:type (expression-type registry chain a opts))]
          (when (not-any? #(= [in] (:args %)) (reached registry opts parts))
            [:cast a t]))))))

(defn- output-type
  "The type of an output column of schema s (nil: not told), without NULL: as the driver reads it,
   or, under ::columns, as the registry types a column (a derived table's, for its outer
   statement)."
  [registry s opts]
  (cond (nil? s) :any (::columns opts) (shape/non-null s) :else (driver-schema registry jdbc/column-read-type s opts)))

(defn- regrouped?
  "Whether stmt's GROUP BY has ROLLUP, CUBE or GROUPING SETS, which add groups whose grouped
   columns are NULL; :raw there may be one."
  [stmt]
  (boolean (some #(and (vector? %) (or (syntax/grouping-sets (first %)) (= :raw (first %)))) (syntax/vectorish (:group-by stmt)))))

(defn- select-row
  "The entries of the row a SELECT returns, in order, a label selected twice twice, each with
   ::item, the index of the select item that makes it."
  [registry stmt chain opts]
  (let [sc (first chain)
        regrouped (regrouped? stmt)
        entry (fn [column schema nullable? props]
                (jdbc/row-entry (jdbc/row-key column) props (output-type registry schema opts)
                                (or nullable? regrouped (nil? schema) (shape/maybe? schema))))
        merges (using-merges registry stmt sc opts)
        merged (fn [label {:keys [nullable?] s :schema}] [(entry label s nullable? nil)])
        ;; [[column entry] ...] of a table, or of a typed subquery or CTE, in order
        columns (fn [{:keys [table nullable? opaque? order types]}]
                  (if opaque?
                    (for [c order] [c (entry c (get types c) nullable? nil)])
                    (for [[c s props] (column-entries registry table)] [c (entry c s nullable? props)])))
        star (fn [col]
               (let [[a] (syntax/split-column col (:format opts))
                     tables (if a (some-> (get sc a) vector) (vals sc))]
                 (when (and (seq tables) (every? #(and % (or (not (:opaque? %)) (:order %))) tables))
                   (if a
                     (map second (mapcat columns tables))
                     ;; SELECT * lists a column USING merges once
                     (concat (mapcat (fn [[c m]] (merged c m)) merges)
                             (for [[alias e] sc :when (not (:qualifier-only? e)), [c en] (columns e)
                                   :when (not (some #{alias} (:tables (get merges c))))]
                               en))))))
        items (for [item (syntax/selected-items stmt)
                    :let [[col alias] (syntax/select-parts item opts)
                          x (syntax/item-expression item)]]
                (cond
                  ;; [:over [f window alias] ...]: a column of each window function
                  (and (nil? col) (nil? alias) (syntax/over-columns x opts))
                  (let [cs (for [[n e] (syntax/over-columns x opts)]
                             (when n (let [{s :schema :keys [nullable?]} (expression-type registry chain e opts)] (entry n s nullable? nil))))]
                    (when (and (seq cs) (every? some? cs)) cs))
                  (nil? col) (when-let [n (syntax/item-name item opts)]
                               (let [{s :schema :keys [nullable?]} (expression-type registry chain (if alias (first item) x) opts)]
                                 [(entry n s nullable? nil)]))
                  (syntax/star? col) (star col)
                  :else (when-let [{:keys [column schema nullable?]} (resolve-column registry chain col)]
                          (let [label (if alias (syntax/alias-label alias opts) column)]
                            (if-let [m (when-not (first (syntax/split-column col (:format opts))) (get merges column))]
                              (merged label m)
                              [(entry label schema nullable? nil)])))))]
    (when (and (seq items) (every? some? items) (not-any? :unknown? (vals sc)))
      (vec (apply concat (map-indexed (fn [i es] (map #(vary-meta % assoc ::item i) es)) items))))))

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
    (let [rows (map-indexed (fn [i m] (row-entries registry m (conj path i) chains opts)) members)
          unknown (map-indexed (fn [i m] (unknown-columns registry m (get chains (conj path i)) opts)) members)]
      (when (and (every? some? rows) (apply = (map count rows)))
        (vec
              (for [[j entries] (map-indexed vector (apply map vector rows))
                    :let [schemas (map last entries)
                          ;; PostgreSQL resolves a UNION b UNION c as (a UNION b) UNION c
                          ts (map (fn [s u] (if (contains? u j) :unknown (resolver-type registry (shape/non-null s)))) schemas unknown)
                          united? (or (not-every? some? ts) (some? (reduce #(some-> %1 (vector %2) resolve/common-type) ts)))
                          known (keep identity (map (fn [s u] (when-not (contains? u j) s)) schemas unknown))
                          ;; a literal is a value of the type, not of the column's bounds
                          literal? (and (seq known) (some #(get % j) unknown))
                          inners (distinct (map (cond->> shape/non-null literal? (comp loose shape/non-null))
                                                (or (seq known) schemas)))]]
                ;; PostgreSQL resolves each column's type as CASE's
                (with-meta
                 (jdbc/row-entry (ffirst entries)
                                (cond (not united?) :any
                                      ;; with an untyped member, a domain resolves to its base type, as CASE's does
                                      (= 1 (count inners)) (let [[s] inners]
                                                             (if (and (scalar-domain s) (< (count known) (count schemas)))
                                                               (loose (domain-base-type s))
                                                               s))
                                      ;; the members' schemas are as the driver reads them: of one type, each
                                      ;; value as its member's (a bit(1) a Boolean, a bit(4) a PGobject)
                                      (apply = (map (juxt #(:pg/type (shape/column-props %)) domain-of) inners)) (shape/either inners :any)
                                      ;; else one of them, or untold
                                      :else (let [r (:schema (common (for [s inners] {:schema s :type (resolver-type registry s)})))]
                                              (if (and r (some #(= r (loose %)) inners)) r :any)))
                                (some shape/maybe? schemas))
                 (meta (first entries)))))))))

(defn- values-row
  "The entries of the row of a VALUES list of rows (vectors): column1, column2 ... each of its
   values' common type, as PostgreSQL resolves a VALUES column."
  [registry rows chain opts]
  (when (and (seq rows) (every? vector? rows) (apply = (map count rows)))
    (vec
          (for [[i xs] (map-indexed vector (apply map vector rows))
                :let [ts (map #(expression-type registry chain % opts) xs)
                      s (:schema (common ts))]]
            (jdbc/row-entry (jdbc/row-key (str "column" (inc i)))
                            (output-type registry s opts)
                            (or (nil? s) (boolean (some :nullable? ts))))))))

(defn- returning
  "stmt with the items of the row it returns alone: a write's RETURNING, not the SELECT an
   INSERT writes ({:insert-into :t :columns [...] :select [...] :returning [...]})."
  [stmt]
  (cond-> stmt
    (some #(contains? stmt %) syntax/write-keys) (#(apply dissoc % syntax/select-clauses))))

(defn- row-entries
  "The entries of the row of the statement at path in a query, in order, a label twice where its
   columns have it twice; chains its statements' scope chains by path (statement-chains), which
   alone say which tables and CTEs a statement sees. A SELECT's entry, and a set operation's of
   its first member's, has ::in, the path of the SELECT, and ::item, the index of its item."
  [registry stmt path chains opts]
  (if-let [k (some #(when (contains? stmt %) %) syntax/set-operation-keys)]
    (set-operation-row registry (get stmt k) (conj path k) chains opts)
    (if (and (= #{:values} (set (keys stmt))) (vector? (:values stmt)))
      (values-row registry (:values stmt) (get chains path) opts)
      (some->> (select-row registry (returning stmt) (get chains path) opts) (mapv #(vary-meta % assoc ::in path))))))

(defn- derived-types*
  [registry stmt opts outer]
  (let [opts (-> opts (dissoc :jdbc) (assoc ::columns true))
        chains (into {} (map (fn [[_ c p]] [p c])) (statement-chains registry stmt opts outer))]
    (when-let [entries (row-entries registry stmt [] chains opts)]
      (vec (for [[k _ s] (map shape/entry-parts entries)]
             [(name k) (when-not (= :any s) s)])))))

(defn- derived-types
  "[[column schema] ...] of the row stmt, a query of its own read inside the chain of
   scopes outer (none by default), returns, as the registry types a column (nil for one it
   cannot tell); nil
   when its row cannot be told, or is asked for again while it is read (a CTE read inside itself)."
  ([registry stmt opts] (derived-types registry stmt opts ()))
  ([registry stmt opts outer]
   (let [memo (::derived registry)
         k [stmt (dissoc opts :jdbc) outer]]
     (if (contains? @memo k)
       (@memo k)
       (do (swap! memo assoc k nil)
           (let [v (derived-types* registry stmt opts outer)] (swap! memo assoc k v) v))))))

(defn- returned-entries [registry body opts]
  (row-entries registry body [] (into {} (map (fn [[_ c p]] [p c])) (once registry statement-chains body opts)) opts))

(defn row-schema
  "The [:map ...] of one row a query returns, as pgmalli-next.jdbc/builder builds it (for a write with no
   RETURNING, its update count); nil when that cannot be told, as
   docs/queries.md#the-row-a-query-returns lists. Columns an outer join may leave NULL are
   nullable; an expression has the type PostgreSQL resolves it to, nullable :any where pgmalli
   cannot tell; a set operation's row is keyed by its first member's names."
  [registry body opts]
  (when (map? body)
    (if-let [body (sent body opts)]
      ;; a label twice is one key, pgmalli-next.jdbc/builder keeping the last column's value
      (when-let [entries (once registry returned-entries body opts)]
        (let [last-of (into {} (map (juxt first identity)) entries)]
          (into [:map] (map last-of) (distinct (map first entries)))))
      update-count)))

(defn duplicate-columns
  "{:kind :duplicate-column :column k :items [item ...]} for each key k of the row a query
   returns that more than one of its columns have: the select items making them, with :in, the
   path of their statement, when nested (a set operation's first member)."
  [registry body opts]
  (when-let [body (and (map? body) (sent body opts))]
    (for [[k es] (group-by first (once registry returned-entries body opts))
          :when (next es)
          :let [{path ::in} (meta (first es))
                items (vec (syntax/selected-items (returning (get-in body path))))]]
      (cond-> {:kind :duplicate-column :column k :items (mapv items (distinct (map (comp ::item meta) es)))}
        (seq path) (assoc :in path)))))

(def ^:private grammar-calls
  "Names HoneySQL writes as a call that are SQL's grammar, no function of pg_proc."
  (into #{"exists" "not_exists" "any" "some" "all" "nullif"} common-type-functions))

(defn- plain-value?
  "Whether x yields one value per row: it calls no function or operator that may return a set, one
   that does (generate_series, unnest) or one the registry has none of, and writes no raw SQL,
   which may.
   A subquery is one value (an error where it gives more rows) or EXISTS's, of rows of its own."
  [registry opts x]
  (cond (map? x) true
        (and (vector? x) (= :raw (first x))) false
        ;; the type a cast names ([:raw "text[]"]) is no expression
        (and (vector? x) (= :cast (first x))) (plain-value? registry opts (second x))
        (or (vector? x) (and (keyword? x) (syntax/shorthand-fn x)))
        (let [[kind :as parts] (syntax/call-parts x)
              ;; an operator of any number of operands (AND of three) is no function: grammar
              function? (and (= :functions kind) (not (and (vector? x) (keyword? (first x)) (syntax/operator? (first x)))))
              ;; an operator is its function's call: one that returns a set too (CREATE OPERATOR of a
              ;; set-returning function), or one the registry has none of
              call? (or (and function? (not (grammar-calls (second parts)))) (= :operators kind))]
          (and (not (and call? (resolve/set-returning? (reached registry opts parts))))
               (or (keyword? x) (every? #(plain-value? registry opts %) (rest x)))))
        (sequential? x) (every? #(plain-value? registry opts %) x)
        :else true))

(defn- aggregated?
  "Whether x holds a call of an aggregate of its own level: one in a subquery, or in a window
   call, whose own level is not told here, is not counted."
  [registry opts x]
  (boolean (or (when-let [parts (syntax/call-parts x)] (and (= :functions (first parts)) (resolve/aggregate? (reached registry opts parts))))
               (and (vector? x) (not= :over (first x)) (some #(aggregated? registry opts %) x)))))

(defn- predicate-condition?
  "Whether c is a condition of a partial index's predicate the generated file tells: [:= column
   value] of a string or a number, or [:is-null column]; a predicate holding another is none."
  [c]
  (and (vector? c) (string? (second c))
       (case (first c)
         := (and (= 3 (count c)) (let [v (nth c 2)] (or (string? v) (number? v))))
         :is-null (= 2 (count c))
         false)))

(defn- unique-keys
  "The column sets a key holds a row of table to one: its primary key and UNIQUE keys, not
   DEFERRABLE (checked only at commit), a partial one where holds? takes its predicate (it holds
   over the rows the predicate does alone); none of a table others inherit, whose rows a key does
   not span."
  [registry table holds?]
  (let [props (table-props registry table)]
    (when-not (:pg/inherited props)
      (cond-> (map :columns (remove #(or (:deferrable %) (let [w (:where %)] (and w (not (and (vector? w) (every? predicate-condition? w) (holds? w))))))
                                    (:pg/unique props)))
        (and (:pg/primary-key props) (not (:pg/primary-key-deferrable props))) (conj (:pg/primary-key props))))))

(defn- literal-value
  "The value literal x writes ([:inline v], a cast of one), or x."
  [x]
  (if (and (vector? x) (#{:inline :cast} (first x))) (recur (second x)) x))

(defn- predicate-holds?
  "Whether condition c (a WHERE) holds each of a partial index's predicate conditions ([:= column
   value], [:is-null column]) of the table of alias, whose columns are columns: the same = of a
   literal of the value, or IS NULL, among its conjuncts."
  [c alias columns conds format]
  (let [conjuncts (tree-seq #(and (vector? %) (= :and (first %))) rest c)
        of? (fn [k col] (and (syntax/column-keyword? k)
                             (let [[q n] (syntax/split-column k format)]
                               (and (= col n) (if q (= q alias) (contains? columns n))))))
        same? (fn [a b] (if (and (number? a) (number? b)) (== a b) (= a b)))]
    (every? (fn [[op col v]]
              (some (fn [x]
                      (and (vector? x) (= 3 (count x))
                           (case op
                             :is-null (let [[a negated?] (syntax/null-test x format)] (and (false? negated?) (of? a col)))
                             := (and (= := (first x)) (some (fn [[a b]] (and (of? a col) (same? v (literal-value b)))) [[(nth x 1) (nth x 2)] [(nth x 2) (nth x 1)]]))
                             false)))
                    conjuncts))
            conds)))

(defn- equalities
  "[a b] and [b a] of each = among condition c's conjuncts, an AND inside an AND too."
  [c]
  (for [x (tree-seq #(and (vector? %) (= :and (first %))) rest c)
        :when (and (vector? x) (= := (first x)) (= 3 (count x)))
        pair [[(second x) (nth x 2)] [(nth x 2) (second x)]]]
    pair))

(defn- uncorrelated?
  "Whether subquery q reads no column of items, the items of a statement around it ({:alias
   :columns}): no name in it qualified by an alias of them it does not define itself, and no
   name of a column of them its own tables lack, where it reads only tables, whose columns are
   told. A correlated scalar subquery is a value for each row around it, not one value."
  [registry q items {:keys [format] :as opts}]
  (let [refs (for [st (syntax/statements q), x (syntax/table-items st)] (syntax/table-ref x opts))
        ;; an alias hides its table's name
        inner-aliases (set (map second refs))
        inner-columns (when (every? first refs) (set (mapcat (fn [[table]] (keys (table-columns registry table))) refs)))
        outer-aliases (set (map :alias items))
        outer-columns (set (mapcat (comp keys :columns) items))]
    (not-any? (fn [k]
                (when (syntax/column-keyword? k format)
                  (let [[qual n] (syntax/split-column k format)]
                    (if qual
                      (and (outer-aliases qual) (not (inner-aliases qual)))
                      (and (outer-columns n) (not (and inner-columns (inner-columns n))))))))
              (filter keyword? (tree-seq coll? seq q)))))

(defn- output-labels
  "The labels of the columns statement stmt returns, by its select items or RETURNING; nil where
   one is * or of no label told."
  [stmt opts]
  (let [ls (for [x (syntax/selected-items stmt)]
             (cond (and (vector? x) (= 2 (count x))) (when (syntax/alias? (second x)) (syntax/alias-label (second x) opts))
                   (and (syntax/column-keyword? x) (not (syntax/star? x))) (second (syntax/split-column x (:format opts)))))]
    (when (and (seq ls) (every? some? ls)) (vec ls))))

(defn- result-keys
  "The label sets a row statement stmt returns is held to one by: the empty set where it returns
   one row at most, its GROUP BY's columns, its DISTINCT ON's, its DISTINCT's output; nil where it
   tells none."
  [registry stmt opts]
  (when-let [ls (output-labels stmt opts)]
    (let [format (:format opts)
          unqualified #(when (syntax/column-keyword? %) (second (syntax/split-column % format)))
          ;; a column selected as itself: its label
          labels (into {} (keep (fn [[x l]] (when-let [c (unqualified (syntax/bare x))] [c l]))) (map vector (syntax/selected-items stmt) ls))
          of (fn [ks] (let [r (map #(labels (unqualified %)) ks)] (when (and (seq ks) (every? some? r)) (vec r))))]
      (seq (remove nil? [(when (some-> (row-count registry stmt opts) :max (<= 1)) [])
                         (when (:select-distinct stmt) ls)
                         (of (syntax/distinct-on-expressions stmt))
                         (of (syntax/vectorish (:group-by stmt)))])))))

(defn- derived-statement
  "The statement FROM item x of stmt (written its name, nil for a subquery) reads: a subquery's,
   or a CTE's of stmt; nil for another."
  [stmt x written format]
  (cond (and (vector? x) (map? (first x))) (first x)
        written (some #(when (= written (syntax/cte-name % format)) (second %)) (syntax/cte-entries stmt))))

(defn- held-items
  "{:items [[item held?] ...] :held columns :column f} of statement stmt: each item it reads (its
   one, the ones it joins by JOIN or LEFT JOIN, DELETE's USING, UPDATE's FROM), in that order,
   held? whether it gives one row at most for a row of the others: a key of it whose columns are
   each equal to one value, a constant, a scalar subquery, or a column of an item so held, or equal
   to one (r.a = t.a, t.a = ?), found until no more is. A table's keys are unique-keys (no CTE of
   a table's name); a subquery's or a CTE's its result-keys, the empty one where it returns a row
   at most. The equalities are the WHERE's, the HAVING's and an inner join's ON's; a LEFT JOIN's ON
   holds its own item's columns alone. Equal to NULL, which HoneySQL writes IS NULL, a table's
   column NOT NULL alone is held. :held the [alias column] so held, :column the [alias column] a
   column keyword names. nil for a join of another kind (RIGHT, FULL, CROSS)."
  [registry stmt write? {:keys [format] :as opts}]
  (let [ctes (into (syntax/cte-names stmt format) (::ctes opts))
        inner-opts (update opts ::ctes into (keep #(syntax/cte-name % format) (syntax/cte-entries stmt)))
        listed (if write? (concat (syntax/write-targets stmt) (syntax/from-items stmt [:using :from])) (syntax/from-items stmt [:from]))
        joined (syntax/joins stmt)
        items (for [x (concat listed (map second joined))
                    :let [[table alias written] (syntax/table-ref x opts)
                          table (when-not (contains? ctes written) table)
                          inner (when-not table (derived-statement stmt x written format))]]
                (cond table (let [columns (table-columns registry table)]
                              {:alias alias :columns columns
                               :keys (unique-keys registry table #(predicate-holds? (:where stmt) alias columns % format))})
                      (map? inner) (let [ks (result-keys registry inner inner-opts)]
                                     {:alias alias :keys ks
                                      :columns (into {} (map #(vector % [:maybe :any])) (output-labels inner inner-opts))})
                      :else {:alias alias}))
        by-alias (into {} (map (juxt :alias identity)) items)
        column (fn [k] (when (syntax/column-keyword? k)
                         (let [[q col] (syntax/split-column k format)
                               owners (if q (filter #(= q (:alias %)) items) (filter #(contains? (:columns %) col) items))]
                           (when (= 1 (count owners)) [(:alias (first owners)) col]))))
        general (concat (equalities (:where stmt)) (equalities (:having stmt))
                        (mapcat (fn [[k _ c]] (when (#{:join :inner-join} k) (equalities c))) joined))
        own (for [[k t c] joined :when (= :left-join k) :let [alias (second (syntax/table-ref t opts))] pair (equalities c)] [alias pair])
        held-by (fn [held [a b]]
                  (when-let [[alias col :as ac] (column a)]
                    (when (if (syntax/null-test [:= a b] format)
                            (not (shape/maybe? (get-in by-alias [alias :columns col] [:maybe :any])))
                            (or (syntax/constant? b) (and (map? b) (uncorrelated? registry b items opts)) (contains? held (column b))))
                      ac)))
        one-row? (fn [held {:keys [alias keys]}] (some (fn [ks] (every? #(contains? held [alias %]) ks)) keys))
        step (fn [held]
               (let [held (into held (keep #(held-by held %)) general)
                     held (into held (keep (fn [[alias pair]] (let [ac (held-by held pair)] (when (= alias (first ac)) ac)))) own)]
                 (into held (for [{:keys [alias columns] :as item} items :when (one-row? held item), c (clojure.core/keys columns)] [alias c]))))
        held (loop [h #{}] (let [h' (step h)] (if (= h h') h (recur h'))))]
    (when (and (not (some #(contains? stmt %) [:cross-join :join-by]))
               (every? (comp #{:join :left-join :inner-join} first) joined))
      {:items (map (fn [x item] [x (boolean (one-row? held item))]) (concat listed (map second joined)) items)
       :held held
       :column column})))

(defn- unique-row?
  "Whether statement stmt reads one row at most: every item it reads is held to one row
   (held-items), or the columns its GROUP BY or DISTINCT ON groups by are each held to one value."
  [registry stmt write? opts]
  (let [{:keys [items held column]} (held-items registry stmt write? opts)
        by (seq (or (syntax/distinct-on-expressions stmt) (syntax/vectorish (:group-by stmt))))]
    (boolean (or (and (seq items) (every? second items))
                 (and by column (every? #(contains? held (column %)) by))))))

(defn- derived-count
  "{:min 0 :max n} of a SELECT whose first FROM item, a subquery or a CTE of the statement, returns
   n rows at most (row-count, its CTEs those of the statement too), each other item it reads held
   to one row for a row of it (held-items); nil otherwise."
  [registry stmt {:keys [format] :as opts}]
  (let [[[item] & others] (:items (held-items registry stmt false opts))
        written (when (or (keyword? item) (and (vector? item) (keyword? (first item)))) (nth (syntax/table-ref item opts) 2 nil))
        inner (derived-statement stmt item written format)]
    (when (and (map? inner) (every? second others))
      (some->> (:max (row-count registry inner (update opts ::ctes into (keep #(syntax/cte-name % format) (syntax/cte-entries stmt)))))
               (hash-map :min 0 :max)))))

(defn- set-operation-count
  "{:min n :max m} of the rows of set operation k (:union ...) of members of counts (row-count's,
   nil where it tells none), nil where it cannot tell: UNION ALL adds them up, UNION gives at
   most as many (and a row where a member gives one), INTERSECT at most the fewest, EXCEPT at
   most the first's."
  [k counts]
  (let [mins (map #(:min % 0) counts)
        maxes (keep :max counts)
        all? (= (count maxes) (count counts))]
    (case k
      :union-all (when all? {:min (reduce + mins) :max (reduce + maxes)})
      :union (when all? {:min (if (some pos? mins) 1 0) :max (reduce + maxes)})
      :intersect (when (seq maxes) {:min 0 :max (apply min maxes)})
      (:except :except-all) (some->> (:max (first counts)) (hash-map :min 0 :max)))))

(defn- row-limit
  "The number of rows clause k (:limit, :fetch) of stmt allows, written in the query (n, or
   [:inline n], which HoneySQL writes as n alike); nil for another (a parameter)."
  [stmt k]
  (let [n (get stmt k)
        n (if (and (vector? n) (= :inline (first n))) (second n) n)]
    (when (and (integer? n) (<= 0 n)) n)))

(defn- counted-rows
  "row-count's count from the statement's data, nil where it cannot tell."
  [registry stmt opts]
  ;; raw SQL may say anything
  (when (and (map? stmt) (not (contains? stmt :raw)))
    (let [write? (some #(contains? stmt %) syntax/write-keys)
          op (some #(when (contains? stmt %) %) syntax/set-operation-keys)
          members (get stmt op)
          items (map syntax/bare (syntax/selected-items stmt))
          order (map syntax/bare (syntax/vectorish (:order-by stmt)))
          ;; a GROUP BY's items are expressions: generate_series there repeats the groups
          groups (syntax/vectorish (:group-by stmt))
          rows (when (and (vector? (:values stmt)) (not (:select (syntax/insert-parts stmt)))) (:values stmt))
          plain? (every? #(plain-value? registry opts %) (concat items order groups (mapcat #(cond (map? %) (vals %) (sequential? %) % :else [%]) rows)))
          unique? (unique-row? registry stmt write? opts)
          bound #(row-limit stmt %)
          limit (some->> (seq (keep bound [:limit :fetch])) (apply min))
          ;; a limit only a parameter tells may be 0
          unbounded-limit? (some #(and (contains? stmt %) (not (bound %))) [:limit :fetch])
          ;; HoneySQL writes no HAVING of []
          having? (and (contains? stmt :having) (not= [] (:having stmt)))
          ;; one group without GROUP BY: an aggregate of its level or HAVING makes it, which HAVING may drop
          grouped? (and (or (:select stmt) (:select-distinct stmt)) (seq items) (not (contains? stmt :group-by))
                        (or having? (some #(aggregated? registry opts %) (concat items order))))
          ;; SELECT without FROM: one row, none where WHERE or HAVING is false
          fromless? (and (or (:select stmt) (:select-distinct stmt)) (seq items) (not-any? #(contains? stmt %) (cons :from syntax/join-keys)))
          counted (cond (not plain?) nil
                        (regrouped? stmt) nil
                        op (let [cs (when (and (vector? members) (seq members)) (map #(row-count registry % opts) members))]
                             (when (seq cs) (set-operation-count op cs)))
                        grouped? {:min (if having? 0 1) :max 1}
                        fromless? {:min (if (or (contains? stmt :where) having?) 0 1) :max 1}
                        (and rows (not write?) (every? vector? rows)) {:min (count rows) :max (count rows)}
                        (and rows (:insert-into stmt) (:returning stmt)) {:min 0 :max (count rows)}
                        (and unique? (not (:insert-into stmt)) (or (not write?) (:returning stmt)) (every? #(plain-value? registry opts %) items))
                        ;; a subquery or a CTE it reads may return fewer (LIMIT 0)
                        {:min 0 :max (min 1 (:max (when-not write? (derived-count registry stmt opts)) 1))}
                        (not write?) (derived-count registry stmt opts))
          m (cond-> counted
              limit (update :max #(min (or % limit) limit))
              (and limit (not counted)) (assoc :min 0)
              ;; a trigger, a rule or ON CONFLICT may skip a row a write's SELECT gives
              (or unbounded-limit? (contains? stmt :offset) (and write? (:returning stmt))) (assoc :min 0))]
      (when (and plain? (:max m)) (cond-> m (< (:max m) (:min m 0)) (assoc :min (:max m)))))))

(defn row-count
  "{:min n :max m} of the rows a statement returns where its data tells, nil otherwise; where
   any part may be a function that returns a set or raw SQL (plain-value?), or GROUP BY has
   ROLLUP, CUBE or GROUPING SETS, it tells nothing but a limit; another clause changes no count. One of a SELECT with an aggregate of its level and
   no GROUP BY, at most one with HAVING; at most one of a SELECT, UPDATE or DELETE of one row
   (unique-row?), and of a SELECT without FROM (one group of its one row under GROUP BY); as many
   as a VALUES list has rows, up to as many of an INSERT returning (a trigger, a rule or ON
   CONFLICT may skip one); of a set operation, as set-operation-count counts its members'; at most
   :limit or :fetch, from none where a parameter gives the limit or OFFSET skips some, whatever
   else the statement holds."
  [registry stmt opts]
  (or (counted-rows registry stmt opts)
      ;; raw SQL may write rows past the limit (UNION ALL after it)
      (when (and (map? stmt) (not (contains? stmt :raw)))
        (when-let [n (some->> (seq (keep #(row-limit stmt %) [:limit :fetch])) (apply min))]
          {:min 0 :max n}))))
