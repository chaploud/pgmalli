(ns pgmalli.impl.shape
  "Reading schema data as shapes: the map of a row schema, its column entries and their
   properties, the names derived from a row's, and the rewrites other namespaces read it by."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.registry :as mr]))

(defn schemas-of
  "The name -> schema map of a registry: a map as it is, a malli Registry (a composite one, say)
   through its schemas."
  [registry]
  (if (map? registry) registry (mr/-schemas (mr/registry registry))))

(defn row-map [schema] (if (= :and (first schema)) (second schema) schema))

(defn entries [schema] (drop (if (map? (second schema)) 2 1) schema))

(defn entry-parts
  "[key props schema] of a map entry, props defaulted to {}."
  [[k p s]]
  (if (map? p) [k p s] [k {} p]))

(defn column-props
  "Properties of a column schema, looking through [:maybe ...]."
  [s]
  (let [s (if (and (vector? s) (= :maybe (first s))) (last s) s)]
    (if (and (vector? s) (map? (second s))) (second s) {})))

(defn column-entries
  "[[column props schema] ...] of a row or insert schema as data."
  [schema]
  (map entry-parts (filter vector? (entries (row-map schema)))))

(defn row-schema?
  "Row schemas carry :pg/table; their inserts do too, but closed."
  [s]
  (let [m (when (vector? s) (row-map s))
        props (when (vector? m) (second m))]
    (and (map? props) (string? (:pg/table props)) (not (:closed props)))))

(defn insert-name
  ":pg.<schema>/<table> -> :pg.<schema>.<table>/insert, string keys alike."
  [row-name]
  (if (keyword? row-name)
    (keyword (str (namespace row-name) "." (name row-name)) "insert")
    (str (str/replace-first row-name "/" ".") "/insert")))

(defn update-name
  ":pg.<schema>/<table> -> :pg.<schema>.<table>/update, string keys alike."
  [row-name]
  (if (keyword? row-name)
    (keyword (str (namespace row-name) "." (name row-name)) "update")
    (str (str/replace-first row-name "/" ".") "/update")))

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

(defn non-null
  "A column schema without its [:maybe ...]: the type a value must have when it is not NULL."
  [schema]
  (if (and (vector? schema) (= :maybe (first schema))) (last schema) schema))

(defn merge-props
  "Schema data with properties merged in (plainly; render's with-props narrows bounds)."
  [s p]
  (cond (empty? p) s
        (and (vector? s) (map? (second s))) (assoc s 1 (merge (second s) p))
        (vector? s) (into [(first s) p] (rest s))
        :else [s p]))

(defn without-gen
  "Schema data without the generation hints the registry added when it was loaded."
  [schema]
  (walk/postwalk (fn [f] (cond (map? f) (dissoc f :gen/min :gen/max :gen/schema :gen/elements :gen/fmap)
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
