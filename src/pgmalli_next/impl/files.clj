(ns pgmalli-next.impl.files
  "Config -> the generated EDN file of its schemas: README.md#generating, and the file
   docs/types.md#the-generated-file. Output is deterministic (sorted maps, pprint); overrides
   that are not EDN (functions) are rejected at generation time."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.registry :as mr]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.ir :as ir]
            [pgmalli-next.impl.pattern :as pattern]
            [pgmalli-next.impl.registry :as registry]
            [pgmalli-next.impl.render :as render]
            [pgmalli-next.impl.types :as types]))

(def default-config {:schemas ["public"] :out (str "resources/" registry/resource) :overrides {} :db {}})

(def config-schema
  [:map {:closed true :pgmalli/not-a-map "should be a map, such as {:schemas [\"public\"]} (README.md#generating)"}
   [:schemas {:optional true} [:and [:sequential {:error/message "should be a vector of schema names, such as [\"public\"]"} [:string {:min 1}]]
                               [:fn {:error/message "should name at least one schema, each once"} #(and (seq %) (apply distinct? %))]]]
   [:out {:optional true} :string]
   [:overrides {:optional true} [:map-of [:keyword {:error/message "should be a column's keyword, :schema.table/column"}] :any]]
   [:db {:optional true} [:map-of {:error/message "should be a map: psql's keys (:host :port :dbname ...), {:jdbcUrl ...} or {:datasource ds}"} :keyword :any]]])

(defn config
  "Config with defaults applied. A key or value config-schema does not take is an error."
  [c]
  (error/checked! config-schema c :pgmalli.error/invalid-config "invalid config")
  (when (contains? (:db c) :schema)
    (error/fail! :pgmalli.error/invalid-config ":db's :schema is not read: the schemas generated are :schemas, such as {:schemas [\"public\"]}"
                 {:given [:schema] :did-you-mean [:schemas]}))
  (merge default-config c))

(defn- sort-maps [x]
  (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) %) %) x))

(defn- left-out
  "An :unrendered entry as the file keeps it: a column of a type pgmalli has no schema for."
  [{:keys [schema table column type type-schema]}]
  (cond-> {:kind :type :table (str schema "." table) :column column :type type}
    type-schema (assoc :type-schema type-schema)))

(defn assemble
  "The file's data from the rendering of each schema's facts ({schema facts}, in the config's
   order), in reading order: what it is, then what deserves a look, then the schemas, then what
   was left out: no column of an enum of another schema of the file, which the registry reads."
  [database-version facts-by-schema overrides]
  (let [rs (for [[s facts] facts-by-schema] [s (sort-maps (render/rendered facts (or overrides {})))])
        by-schema (fn [k] (into (sorted-map) (for [[s r] rs] [s (get r k)])))
        schemas (into (sorted-map-by #(compare (str %1) (str %2))) (map (comp :registry second)) rs)]
    (array-map :schemas (mapv first rs) :database-version database-version
               :diagnostics (vec (mapcat (comp :diagnostics second) rs))
               :registry schemas
               :functions (by-schema :functions) :operators (by-schema :operators) :sequences (by-schema :sequences)
               :unrendered (vec (for [[_ r] rs, u (:unrendered r)
                                      :when (not (registry/other-schemas-enum schemas (:type u)))]
                                  (left-out u))))))

(def ^:private override-registry
  "The base schemas, and any qualified name: a reference to the user's registry, which
   generation does not see."
  (mr/composite-registry registry/base (reify mr/Registry (-schema [_ k] (when (qualified-keyword? k) :any)) (-schemas [_] {}))))

(defn- column-meant
  "The column key meant by k: k with its schema (:users/email for :public.users/email) or
   without pg. (:pg.public.users/email), else the closest."
  [k columns schemas]
  (or (some (set columns) (cons (keyword (str/replace (namespace k) #"^pg\." "") (name k))
                                (for [s schemas] (keyword (str s "." (namespace k)) (name k)))))
      (error/suggest k columns)))

(def ^:private by-keyword (types/by-keyword any?))

(defn checked-overrides!
  "overrides when each key names a column of facts (:schema.table/column) with a malli schema.
   An error names what to write instead."
  [overrides facts]
  (let [column-facts (filter #(= :column (:fact %)) facts)
        key-of #(render/column-override-key (:schema %) (:table %) (:column %))
        columns (map key-of column-facts)
        of-type (fn [t] (sort (for [f column-facts :when (= t (render/type-name (:type f)))] (key-of f))))
        by-table (group-by namespace columns)
        schemas (distinct (keep :schema facts))
        generated (str "the schemas generated: " (str/join ", " schemas))
        listed #(str/join ", " %)
        fail! (fn [message given meant]
                (error/fail! :pgmalli.error/invalid-config (str ":overrides: " (pr-str given) " " message (error/did-you-mean (some-> meant pr-str)))
                             (cond-> {:given given} meant (assoc :did-you-mean [meant]))))]
    (doseq [[k v] overrides]
      (cond (simple-keyword? k)
            (let [cols (of-type (:name (by-keyword k) (name k)))]
              (fail! (str "names a type: give each column a schema, :schema.table/column"
                          (if (seq cols) (str " (" (listed cols) ")") (str "; no column of " generated " has it")))
                     k nil))
            (not (some #{k} columns))
            (let [table (namespace k)
                  meant (column-meant k columns schemas)]
              (error/fail! :pgmalli.error/invalid-config
                           (str ":overrides: " k
                                (if (by-table table) (str " names no column of " table) (str " names no table or view of " generated))
                                (cond meant (error/did-you-mean meant)
                                      (by-table table) (str "; it has " (listed (sort (by-table table))))
                                      :else ""))
                           (cond-> {:given k} meant (assoc :did-you-mean [meant]))))
            :else
            (try (m/schema v {:registry override-registry})
                 (catch Exception e
                   (error/fail! :pgmalli.error/invalid-config
                                (str ":overrides: " (pr-str k) "'s schema " (pr-str v) " is no malli schema: " (if-let [t (:type (ex-data e))] (str (name t) " " (pr-str (:data (ex-data e)))) (ex-message e)))
                                {:given k} e)))))
    overrides))

(defn major-version
  "\"PostgreSQL 17\" of the server's \"PostgreSQL 17.6 (Debian ...)\": a minor upgrade changes no
   schema. A server older than the catalog's versions is an error."
  [version]
  (let [major (parse-long (or (re-find #"\d+" version) "0"))
        oldest (types/oldest-version)]
    (when (< major (parse-long oldest))
      (error/fail! :pgmalli.error/fault (str version " is older than pgmalli reads: generate from PostgreSQL " oldest " or later") {:given version}))
    (types/version-label major)))

(defn- read-schema
  "[database-version facts] of one schema, read from the database."
  [db schema]
  (let [ir (ir/from-db (assoc db :schema schema))]
    [(major-version (:database_version ir)) (pattern/facts ir)]))

(defn generated
  "{:path :data} of the config's schemas: where the file goes (:out) and what it holds."
  [c]
  (let [c (config c)
        read (for [s (:schemas c)] [s (read-schema (:db c) s)])]
    (checked-overrides! (:overrides c) (mapcat (comp second second) read))
    {:path (:out c) :data (assemble (first (second (first read))) (for [[s [_ facts]] read] [s facts]) (:overrides c))}))

(def ^:private header ";; generated by pgmalli")

(defn edn-string
  "The file's text: a deterministic pprint of the data behind the do-not-edit header. Data that
   does not read back as EDN is an error."
  [data]
  (let [s (binding [pp/*print-right-margin* 100
                    *print-namespace-maps* false]
            (with-out-str (pp/pprint data)))]
    (when-not (= data (try (edn/read-string s) (catch Exception _ ::unreadable)))
      (error/fail! :pgmalli.error/invalid-config "generated data is not readable EDN; :overrides must be data such as [:ref name], not functions"))
    (str header "; do not edit (adjust :overrides instead)\n" s)))

(defn generate!
  "Writes the config's file, where no file or a generated one is. Returns its path."
  [c]
  (let [f (io/file (:out (config c)))]
    (when (java.nio.file.Files/isSymbolicLink (.toPath f))
      (error/fail! :pgmalli.error/invalid-config (str ":out " f " is a symbolic link; give :out the path of a file") {:given (str f)}))
    (when (and (.exists f) (or (.isDirectory f) (not (str/starts-with? (slurp f) header))))
      (error/fail! :pgmalli.error/invalid-config (str ":out " f " is a file generate! did not write; give :out a path of its own") {:given (str f)}))
    (let [{:keys [path data]} (generated c)
          _ (io/make-parents f)
          ;; written whole beside it, then moved over it: no half-written file, no link followed
          tmp (java.io.File/createTempFile ".pgmalli-next" ".edn" (.getAbsoluteFile (.getParentFile (.getAbsoluteFile f))))]
      (spit tmp (edn-string data))
      (java.nio.file.Files/move (.toPath tmp) (.toPath f)
                                (into-array java.nio.file.CopyOption [java.nio.file.StandardCopyOption/REPLACE_EXISTING
                                                                     java.nio.file.StandardCopyOption/ATOMIC_MOVE]))
      path)))
