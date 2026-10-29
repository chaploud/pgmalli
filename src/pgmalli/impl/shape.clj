(ns pgmalli.impl.shape
  "Reading schema data as shapes: the map of a row schema, its column entries and their
   properties, the names derived from a row's, and the rewrites other namespaces read it by."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.registry :as mr]
            [pgmalli.impl.error :as error]))

(defn- schema-form? [x]
  (or (vector? x) (keyword? x) (string? x) (symbol? x) (fn? x) (map? x) (m/schema? x) (m/into-schema? x)))

(defn schemas-of
  "The name -> schema map of a registry: a map as it is, a malli Registry (a composite one, say)
   through its schemas. Anything else is an error: a generated data map, a map whose values are
   not schemas."
  [registry]
  (cond (and (map? registry) (contains? registry :registry) (contains? registry :schema))
        (error/fail! :pgmalli.error/not-a-registry
                     (str "given the generated data of schema " (pr-str (:schema registry)) ", not a registry; pass (pgmalli.core/registry " (pr-str (:schema registry)) ")")
                     {:given (:schema registry)})
        (map? registry)
        (if-let [[k v] (first (remove (comp schema-form? val) registry))]
          (error/fail! :pgmalli.error/not-a-registry
                       (str "not a registry: " (pr-str k) " maps to " (pr-str v) ", not a schema; pass (pgmalli.core/registry \"<schema>\")")
                       {:given k})
          registry)
        :else
        (if-let [r (mr/registry registry)]
          (mr/-schemas r)
          (error/fail! :pgmalli.error/not-a-registry
                       (str "not a registry: " (pr-str (type registry)) "; pass (pgmalli.core/registry \"<schema>\")")
                       {:given (type registry)}))))

(defn- generated-names
  "The generated names of a registry: the pg.* ones, all of them when there are none."
  [schemas]
  (let [ks (sort-by str (keys schemas))
        pg (filter #(str/starts-with? (str %) (if (keyword? %) ":pg." "pg.")) ks)]
    (vec (or (seq pg) ks))))

(defn schema-of
  "The schema a registry holds under a name; a name it does not hold is an error naming the
   ones it does."
  [registry name]
  (let [schemas (schemas-of registry)]
    (when-not (contains? schemas name)
      (let [known (generated-names schemas)
            suggest (error/suggest name known)]
        (error/fail! :pgmalli.error/unknown-schema
                     (str "the registry has no schema named " (pr-str name)
                          (if suggest
                            (str "; did you mean " (pr-str suggest) "?")
                            (str "; it has " (pr-str (vec (take 5 known))) (when (< 5 (count known)) " ..."))))
                     (cond-> {:given name :known known} suggest (assoc :suggest suggest)))))
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

(defn row-props
  "The properties of a row (or insert, update) schema's map: :pg/table or :pg/view, keys..."
  [s]
  (let [m (when (vector? s) (row-map s))]
    (when (and (vector? m) (map? (second m))) (second m))))

(defn maybe?
  "Whether a column schema takes NULL: [:maybe ...]."
  [s]
  (and (vector? s) (= :maybe (first s))))

(defn column-props
  "Properties of a column schema, looking through [:maybe ...]."
  [s]
  (let [s (if (maybe? s) (last s) s)]
    (if (and (vector? s) (map? (second s))) (second s) {})))

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

(defn dispatch-key
  "The column a :multi's properties dispatch on: :dispatch, or :pg/dispatch when the registry
   gave a string key a function."
  [props]
  (or (:pg/dispatch props) (:dispatch props)))

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
                 (cond-> {:given name :known rows} row (assoc :suggest row)))))

(defn non-null
  "A column schema without its [:maybe ...]; see pgmalli.core/non-null."
  [schema]
  (if (maybe? schema) (last schema) schema))

(defn read-as
  "A column schema under :read, {pg-type schema}: the schema a type is read as when a driver
   extension (next.jdbc's ReadableColumn) reads it otherwise, NULL still allowed where it was.
   nil when the column's :pg/type is not among them."
  [read schema]
  (let [inner (non-null schema)]
    (when-let [s (get read (when (and (vector? inner) (map? (second inner))) (:pg/type (second inner))))]
      (if (identical? inner schema) s [:maybe s]))))

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
  "Schema data with the time types a driver returns under :time: :instant when timestamps
   arrive as Instants (dates stay java.sql.Date, hence inst?), :local when timestamptz arrives
   as LocalDateTime, :inst when the schema is read without malli.experimental.time."
  [time schema]
  (walk/postwalk (fn [f] (case [time f]
                           [:instant :time/local-date-time] :time/instant
                           [:instant :time/local-date] 'inst?
                           [:local :time/instant] :time/local-date-time
                           (if (and (= :inst time) (keyword? f) (= "time" (namespace f))) 'inst? f)))
                 schema))

(defn type-name
  "The type of a column schema without its typmod or array brackets: \"bit(8)[]\" -> \"bit\"."
  [p]
  (some-> (:pg/type p) (str/replace #"\(.*\)|\[\]" "") str/trim))
