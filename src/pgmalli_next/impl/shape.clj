(ns pgmalli-next.impl.shape
  "Reading schema data as shapes: the map of a row schema, its column entries and their
   properties, the names derived from a row's, and the rewrites other namespaces read it by."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.registry :as mr]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.types :as types]))

(defn- schema-form? [x]
  (or (vector? x) (keyword? x) (string? x) (symbol? x) (fn? x) (map? x) (m/schema? x) (m/into-schema? x)))

(def ^:private checked-registry
  "The last registry map schemas-of checked: callers pass the same one over and over."
  (atom nil))

(defn schemas-of
  "The name -> schema map of a registry: a map as it is, a malli Registry (a composite one, say)
   through its schemas. Anything else is an error: a generated data map, a map whose values are
   not schemas."
  [registry]
  (cond (and (map? registry) (contains? registry :registry) (contains? registry :schemas))
        (error/fail! :pgmalli.error/not-a-registry
                     (str "given the generated data of schemas " (pr-str (:schemas registry)) ", not a registry; pass (pgmalli-next.core/registry <that data>)")
                     {:given (:schemas registry)})
        (identical? registry @checked-registry) registry
        (map? registry)
        (if-let [[k v] (first (remove (comp schema-form? val) registry))]
          (error/fail! :pgmalli.error/not-a-registry
                       (str "not a registry: " (pr-str k) " maps to " (pr-str v) ", not a schema; pass (pgmalli-next.core/registry)")
                       {:given k})
          (reset! checked-registry registry))
        :else
        (if-let [r (mr/registry registry)]
          (mr/-schemas r)
          (error/fail! :pgmalli.error/not-a-registry
                       (str "not a registry: " (pr-str (type registry)) "; pass (pgmalli-next.core/registry)")
                       {:given (type registry)}))))

(defn generated-name?
  "Whether x is a name pgmalli generates: :pg.public/users, \"pg.public/Order Items\"."
  [x]
  (boolean (or (and (keyword? x) (some-> (namespace x) (str/starts-with? "pg.")))
               (and (string? x) (re-find #"^pg\.[^/]+/" x)))))

(defn- generated-names
  "The generated names of a registry: the pg.* ones, all of them when there are none; tables,
   views and types before the schemas derived from them (:pg.public.users/insert)."
  [schemas]
  (let [ks (sort-by (juxt #(count (re-seq #"\." (str (when (keyword? %) (namespace %))))) str) (keys schemas))
        pg (filter generated-name? ks)]
    (vec (or (seq pg) ks))))

(defn meant
  "The names among known probably meant by one a registry lacks: the closest, else those of
   its name in another namespace (:pg.public/users for :users or :pg.app/users). nil for none."
  [given known]
  (or (some-> (error/suggest given known) vector)
      (when (keyword? given) (seq (filter #(and (keyword? %) (= (name given) (name %))) known)))))

(defn schema-of
  "The schema a registry holds under a name; a name it does not hold is an error naming the
   ones it does."
  [registry name]
  (let [schemas (schemas-of registry)]
    (when-not (contains? schemas name)
      (let [known (generated-names schemas)
            suggest (meant name known)]
        (error/fail! :pgmalli.error/unknown-schema
                     (str "the registry has no schema named " (pr-str name)
                          (if suggest
                            (error/did-you-mean (str/join " or " (map pr-str suggest)))
                            (str "; it has " (pr-str (vec (take 5 known))) (when (< 5 (count known)) " ..."))))
                     (cond-> {:given name :known known} suggest (assoc :did-you-mean (vec suggest))))))
    (get schemas name)))

(defn entries
  "The children of a schema vector: everything after the tag, and after the property map when
   the second element is one."
  [schema] (drop (if (map? (second schema)) 2 1) schema))

(defn entry-parts
  "[key props schema] of a map entry, props defaulted to {}."
  [[k p s]]
  (if (map? p) [k p s] [k {} p]))

(defn written-by-database?
  "Whether the database alone writes a column (a generated one, an identity ALWAYS one): no
   INSERT or UPDATE carries it."
  [column-props]
  (boolean (or (:pg/generated column-props) (= :always (:pg/identity column-props)))))

(defn props
  "The property map of schema vector s; nil for none."
  [s]
  (when (and (vector? s) (map? (second s))) (second s)))

(defn row-props
  "The properties of a row (or insert, update) schema's map: :pg/table or :pg/view, keys..."
  [s]
  (props (when (vector? s) s)))

(defn before-triggers
  "The names of the BEFORE row triggers that run, for event (:insert, :update), on the table of
   row properties props, which may fill a column before NOT NULL is checked; nil for none."
  [props event]
  (not-empty (get-in props [:pg/before-triggers event])))

(defn filled-by-trigger? [props event] (boolean (before-triggers props event)))

(defn maybe?
  "Whether a column schema takes NULL: [:maybe ...]."
  [s]
  (and (vector? s) (= :maybe (first s))))

(defn column-props
  "Properties of a column schema, looking through [:maybe ...]."
  [s]
  (or (props (if (maybe? s) (last s) s)) {}))

(defn and-base
  "s's first schema where it is [:and x ...] (an argument held to a range or to classes, a
   shape), else s."
  [s]
  (if (and (vector? s) (= :and (first s))) (first (entries s)) s))

(defn ref-target
  "The registry schema [:ref ... name] s refers to; nil for another s."
  [schemas s]
  (when (and (vector? s) (= :ref (first s))) (get schemas (last s))))

(defn update-and-base
  "s with f applied to and-base's schema."
  [s f]
  (if (and (vector? s) (= :and (first s))) (update s (if (map? (second s)) 2 1) f) (f s)))

(defn column-entries
  "[[column props schema] ...] of a row or insert schema as data."
  [schema]
  (map entry-parts (filter vector? (entries schema))))

(defn read-row?
  "Whether a schema is a row a query returns: a table's or a view's, not an insert or update
   schema (closed)."
  [s]
  (let [props (row-props s)]
    (boolean (and (or (:pg/table props) (:pg/view props)) (not (:closed props))))))

(defn row-schema?
  "Row schemas carry :pg/table; their inserts do too, but closed."
  [s]
  (let [props (row-props s)]
    (and (string? (:pg/table props)) (not (:closed props)))))

(defn derived-name
  "The name of a schema derived from a row's: :pg.<schema>/<table> -> :pg.<schema>.<table>/<suffix>
   (\"insert\", \"update\"), string keys alike."
  [row-name suffix]
  (if (keyword? row-name)
    (keyword (str (namespace row-name) "." (name row-name)) suffix)
    (let [[_ sch n] (re-matches #"(pg\.(?:[^\\/]|\\.)*)/(.*)" row-name)]
      (str sch "." n "/" suffix))))

(defn plain?
  "Whether an identifier reads as a keyword (letters of either case, digits, _, no digit first):
   a row key or a registry name of it is a keyword, a string otherwise. Upper case needs quoting
   in PostgreSQL all the same (hsyntax/keyword-name?)."
  [s]
  (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s))

(defn ident-key
  "Column names as row keys: keywords for plain identifiers, strings otherwise."
  [s]
  (if (plain? s) (keyword s) s))

(defn schema-key
  "A table, view or type of a schema as a registry key: :pg.<schema>/<name>, a string key when
   either name is not a plain identifier, a \\, a / or a . of its schema's name escaped by a \\
   (the key read back by schema-and-name alone, and no derived-name of another's)."
  [schema-name s]
  (if (and (plain? schema-name) (plain? s))
    (keyword (str "pg." schema-name) s)
    (str "pg." (str/replace schema-name #"[\\/.]" #(str "\\" %)) "/" s)))

(defn routines-key
  "The registry key of a schema's functions and operators, the generated file's :functions and
   :operators as the properties of an :any (a malli registry holds schemas alone)."
  [schema-name]
  (keyword "pgmalli.routines" schema-name))

(defn schema-and-name
  "[schema name] of a registry key schema-key made: :pg.ins/mood and \"pg.a.b/m\" are [\"ins\"
   \"mood\"] and [\"a.b\" \"m\"], \"pg.a\\/b/m\" [\"a/b\" \"m\"]."
  [k]
  (if (keyword? k)
    [(subs (namespace k) 3) (name k)]
    (let [[_ sch n] (re-matches #"pg\.((?:[^\\/]|\\.)*)/(.*)" k)]
      [(str/replace sch #"\\(.)" "$1") n])))

(def ^:private varchar-names
  "The pg_catalog types pgjdbc returns, as it returns an enum, as a VARCHAR, by these names."
  #{"name" "text" "varchar"})

(defn enum-type-names
  "The names pgjdbc may give each enum type of registry: \"schema\".\"name\" (a quote in a name as
   it is), and its name, where the session's search_path finds it; but the name of a pg_catalog
   type pgjdbc returns as a VARCHAR too (varchar-names), which names that type."
  [registry]
  (set (for [[k s] (schemas-of registry)
             :when (and (generated-name? k) (vector? s) (= :enum (first s)))
             :let [[schema n] (schema-and-name k)]
             x (cond-> [(str "\"" schema "\".\"" n "\"")] (not (varchar-names n)) (conj n))]
         x)))

(defn table-key
  "\"public.users\" -> :pg.public/users: schema-key over a qualified name."
  [table]
  (let [[schema t] (str/split table #"\." 2)]
    (schema-key schema t)))

(defn not-a-row!
  "The error for a schema that is no table's or view's row: an insert or update schema names
   its row, anything else the rows the registry holds."
  [registry name what]
  (let [s (get (schemas-of registry) name)
        props (row-props s)
        rows (vec (sort-by str (for [[k s] (schemas-of registry) :when (read-row? s)] k)))
        row (when (:closed props) (some-> (:pg/table props) table-key))]
    (error/fail! :pgmalli.error/unknown-schema
                 (if row
                   (str (pr-str name) " is the schema of what is sent to " (:pg/table props) "; " what " takes its row schema " (pr-str row))
                   (str (pr-str name) " is no table's or view's row schema; " what " takes one such as " (pr-str (vec (take 3 rows)))))
                 (cond-> {:given name :known rows} row (assoc :did-you-mean [row])))))

(defn head
  "A schema's type: its first element, or itself."
  [t]
  (if (vector? t) (first t) t))

(defn kind
  "The kind of value t takes: its head, through [:and t ...]; a
   function schema ([:fn f]) is a kind of its own."
  [t]
  (case (head t)
    :and (kind (and-base t))
    :fn t
    (head t)))

(defn value-classes
  "Which of #{:string :keyword} a value alternative t may take: its kind's, both for a kind
   pgmalli does not tell (a reference, [:maybe ...], [:fn ...])."
  [t]
  (let [k (kind t)]
    (cond (#{:string 'string? :re} k) #{:string}
          (#{:keyword 'keyword? 'simple-keyword? 'qualified-keyword?} k) #{:keyword}
          ;; malli.experimental.time's types, and pgmalli's own (pgtypes/schemas): none takes a string
          (or (and (keyword? k) (#{"time" "pg"} (namespace k)))
              (#{:int :double :float :boolean :uuid :symbol :nil :map :map-of :tuple :sequential :vector :set
                 'int? 'integer? 'decimal? 'double? 'float? 'number? 'boolean? 'uuid? 'bytes? 'inst? 'nil?} k)) #{}
          :else #{:string :keyword})))

(defn without-lengths
  "t without a string's length (its :min, :max and :pg/max-length), its other bounds kept: an
   element of an array sent untyped, which PostgreSQL resolves to an array type of no type
   modifier."
  [t]
  (walk/postwalk #(if (and (vector? %) (= :string (first %)) (map? (second %)))
                            (let [p (dissoc (second %) :min :max :pg/max-length)]
                              (if (empty? p) (into [:string] (drop 2 %)) (assoc % 1 p)))
                            %)
                         t))

(defn either
  "One schema of alternatives ts: an :or of them, the one alone, empty when there are none."
  [ts empty]
  (case (count ts) 0 empty 1 (first ts) (into [:or] ts)))

(defn alternatives
  "The alternatives of t: an :or's children, its properties left out; t alone otherwise."
  [t]
  (if (and (vector? t) (= :or (first t))) (remove map? (rest t)) [t]))

(def collection-kinds #{:map :map-of :vector :sequential :set :tuple})

(defn value-kind
  "kind, the strings' of :re and string?, as a shape writes a string column's schema."
  [t]
  (let [k (kind t)] (get {:re :string 'string? :string} k k)))

(defn distinct-kinds
  "Alternatives ts, the first of each kind, in their order (an argument's own values come
   first, with their :pg/type and bounds); collections of different contents (two shapes of a
   json column, the arrays of two element types) are each kept, a kind as value-kind
   names it."
  [ts]
  (first (reduce (fn [[out seen] t]
                   (let [k (value-kind t)
                         k (if (and (or (collection-kinds k) (= :pg/array k)) (vector? t)) [k (vec (entries t))] k)]
                     (if (seen k) [out seen] [(conj out t) (conj seen k)])))
                 [[] #{}] ts)))

(defn non-null
  "A column schema without its [:maybe ...]: what a value must be when it is not NULL."
  [schema]
  (if (maybe? schema) (last schema) schema))

(defn merge-props
  "Schema data with properties merged in (plainly; render's with-props narrows bounds)."
  [s p]
  (cond (empty? p) s
        (and (vector? s) (map? (second s))) (assoc s 1 (merge (second s) p))
        (vector? s) (into [(first s) p] (rest s))
        :else [s p]))

(defn without-gen
  "Schema data without what the registry added when it was loaded: generation hints, the
   function a string :dispatch became."
  [schema]
  (walk/postwalk (fn [f] (cond (map? f) (cond-> (dissoc f :gen/min :gen/max :gen/schema :gen/elements :gen/fmap :gen/gen :pg/dispatch)
                                          (:pg/dispatch f) (assoc :dispatch (:pg/dispatch f)))
                               ;; a property map the hints emptied goes too
                               (and (vector? f) (map? (second f)) (empty? (second f)) (not (#{:map :enum} (first f))))
                               (if (= 2 (count f)) (first f) (into [(first f)] (drop 2 f)))
                               :else f))
                 schema))

(defn read-time
  "Schema data with the time types as a driver returns them: :instant as the one reading
   reads them (as the registry types them), :inst as pgjdbc does (java.sql's, inst?)."
  [time schema]
  (walk/postwalk (fn [f] (if (and (= :inst time) (keyword? f) (= "time" (namespace f))) 'inst? f)) schema))

(defn type-name
  "The type of a column schema without its typmod or array brackets: \"bit(8)[]\" -> \"bit\",
   \"s.d[]\" -> \"s.d\"."
  [p]
  (when-let [[schema n] (types/type-parts (:pg/type p))] (cond->> n schema (str schema "."))))
