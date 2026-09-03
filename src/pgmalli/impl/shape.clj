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
      (throw (ex-info (str "no schema named " name) {:name name :known (generated-names schemas)})))
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

(defn table-key
  "\"public.users\" -> :pg.public/users: schema-key over a qualified name."
  [table]
  (let [[schema t] (str/split table #"\." 2)]
    (schema-key schema t)))

(defn non-null
  "A column schema without its [:maybe ...]; see pgmalli.core/non-null."
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

(def opaque-literals
  "Literals the database reads for the types rendered :any: what a dataset column of such a
   type generates (any of them, as text; the driver's own objects come back on read)."
  {"inet" ["10.0.0.1" "192.168.1.0/24" "::1"] "cidr" ["10.0.0.0/8" "192.168.1.0/24" "2001:db8::/32"]
   "macaddr" ["08:00:2b:01:02:03" "08-00-2b-01-02-04"] "macaddr8" ["08:00:2b:01:02:03:04:05"]
   "money" ["12.34" "0.00" "-5.50"] "xml" ["<a/>" "<a b=\"1\">x</a>"] "tsvector" ["'a' 'b'" "'fat':2 'rat':3"] "tsquery" ["'a' & 'b'" "'fat' | 'rat'"]
   "jsonpath" ["$.a" "$[*] ? (@ > 1)"] "pg_lsn" ["0/16B3748" "1/0"] "tid" ["(0,1)" "(1,2)"] "pg_snapshot" ["10:20:" "10:20:10,14,15"] "txid_snapshot" ["10:20:"]
   "xid" ["1" "1234"] "xid8" ["1" "1234"] "cid" ["0" "3"]
   "point" ["(1,2)" "(0,0)" "(-1.5,2.5)"] "line" ["{1,-1,0}" "{0,1,-2}"] "lseg" ["[(0,0),(1,1)]"] "box" ["((0,0),(1,1))" "((1,1),(2,3))"]
   "path" ["[(0,0),(1,1),(2,0)]" "((0,0),(1,1),(2,0))"] "polygon" ["((0,0),(1,0),(1,1))"] "circle" ["<(0,0),1>" "<(1,1),2.5>"]
   ;; no empty range: a WITHOUT OVERLAPS key refuses one
   "int4range" ["[1,10)" "[20,30)" "(,5]"] "int8range" ["[1,10)" "[20,30)"] "numrange" ["[1.5,2.5]" "[3,4)"]
   "tsrange" ["[2020-01-01 00:00,2020-01-02 00:00)" "[2021-01-01 00:00,2021-06-01 00:00)"] "tstzrange" ["[2020-01-01 00:00+00,2020-01-02 00:00+00)" "[2021-01-01 00:00+00,2021-06-01 00:00+00)"]
   "daterange" ["[2020-01-01,2020-02-01)" "[2021-01-01,2021-06-01)"]
   "int4multirange" ["{[1,3),[5,7)}" "{[10,20)}"] "int8multirange" ["{[1,3)}" "{[10,20)}"] "nummultirange" ["{[1.5,2.5]}" "{[3,4)}"]
   "tsmultirange" ["{[2020-01-01 00:00,2020-01-02 00:00)}"] "tstzmultirange" ["{[2020-01-01 00:00+00,2020-01-02 00:00+00)}"]
   "datemultirange" ["{[2020-01-01,2020-02-01)}" "{[2021-01-01,2021-06-01)}"]
   "regclass" ["pg_class" "pg_type"] "regtype" ["integer" "text"] "regrole" ["postgres"] "regproc" ["now"] "regprocedure" ["now()"]
   "regoper" ["+"] "regoperator" ["+(integer,integer)"] "regnamespace" ["public"] "regconfig" ["english"] "regdictionary" ["simple"] "regcollation" ["\"C\""]})

(def opaque-types
  "Types a driver hands over as objects of its own (PGobject and the like): rendered :any with
   their :pg/type, and generated from those literals."
  (set (keys opaque-literals)))

(defn type-name
  "The type of a column schema without its typmod or array brackets: \"bit(8)[]\" -> \"bit\"."
  [p]
  (some-> (:pg/type p) (str/replace #"\(.*\)|\[\]" "") str/trim))
