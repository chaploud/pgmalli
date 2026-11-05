(ns pgmalli-next.impl.shape
  "Reading schema data as shapes: the map of a row schema, its column entries and their
   properties, the names derived from a row's, and the rewrites other namespaces read it by."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.registry :as mr]
            [pgmalli-next.impl.error :as error]))

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

(defn row-map
  "The [:map ...] of a row schema, looking through the [:and ...] a table with CHECKs is."
  [schema] (if (= :and (first schema)) (second schema) schema))

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
  (props (when (vector? s) (row-map s))))

(defn maybe?
  "Whether a column schema takes NULL: [:maybe ...]."
  [s]
  (and (vector? s) (= :maybe (first s))))

(defn column-props
  "Properties of a column schema, looking through [:maybe ...]."
  [s]
  (or (props (if (maybe? s) (last s) s)) {}))

(defn domain-base
  "The base of a domain's registry schema: the first schema of [:and base checks...]."
  [d]
  (if (and (vector? d) (= :and (first d))) (first (entries d)) d))

(defn ref-target
  "The registry schema [:ref ... name] s refers to; nil for another s."
  [schemas s]
  (when (and (vector? s) (= :ref (first s))) (get schemas (last s))))

(defn update-base
  "d with f applied to its base: the first schema of [:and base checks...], else d itself."
  [d f]
  (if (and (vector? d) (= :and (first d))) (update d (if (map? (second d)) 2 1) f) (f d)))

(defn column-entries
  "[[column props schema] ...] of a row or insert schema as data."
  [schema]
  (map entry-parts (filter vector? (entries (row-map schema)))))

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
    (str (str/replace-first row-name "/" ".") "/" suffix)))

(defn plain?
  "Whether an identifier needs no quoting, so it can be a keyword."
  [s]
  (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s))

(defn ident-key
  "Column names as row keys: keywords for plain identifiers, strings otherwise."
  [s]
  (if (plain? s) (keyword s) s))

(defn schema-key
  "A table, view or type of a schema as a registry key: :pg.<schema>/<name>, a string key when
   either name is not a plain identifier."
  [schema-name s]
  (if (and (plain? schema-name) (plain? s))
    (keyword (str "pg." schema-name) s)
    (str "pg." schema-name "/" s)))

(defn routines-key
  "The registry key of a schema's functions and operators, the generated file's :functions and
   :operators as the properties of an :any (a malli registry holds schemas alone)."
  [schema-name]
  (keyword "pgmalli.routines" schema-name))

(defn schema-and-name
  "[schema name] of a registry key: :pg.ins/mood and \"pg.a.b/m\" are [\"ins\" \"mood\"] and [\"a.b\" \"m\"]."
  [k]
  (let [[ns n] (if (keyword? k) [(namespace k) (name k)] (str/split k #"/" 2))] [(subs ns 3) n]))

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
  "The kind of value t takes: its head, through [:and t checks...] and [:pg/held typmod t]; a
   function schema ([:fn f]) is a kind of its own."
  [t]
  (case (head t)
    :and (kind (domain-base t))
    :pg/held (kind (last t))
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

(defn either
  "One schema of alternatives ts: an :or of them, the one alone, empty when there are none."
  [ts empty]
  (case (count ts) 0 empty 1 (first ts) (into [:or] ts)))

(defn alternatives
  "The alternatives of t: an :or's children, its properties left out; t alone otherwise."
  [t]
  (if (and (vector? t) (= :or (first t))) (remove map? (rest t)) [t]))

(def collection-kinds #{:map :map-of :vector :sequential :set :tuple})

(defn distinct-kinds
  "Alternatives ts, the first of each kind, in their order (an argument's own values come
   first, with their :pg/type and bounds); collections of different contents (two shapes of a
   json column, the Java arrays of two element classes) are each kept."
  [ts]
  (first (reduce (fn [[out seen] t]
                   (let [k (kind t)
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
  "The type of a column schema without its typmod or array brackets: \"bit(8)[]\" -> \"bit\"."
  [p]
  (some-> (:pg/type p) (str/replace #"\(.*\)|\[\]" "") str/trim))
