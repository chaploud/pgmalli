(ns pgmalli.impl.files
  "Config -> generated EDN files, one per schema. The config is documented on pgmalli.generate.

   File contents, in this order:
     {:schema \"public\" :database-version \"PostgreSQL 17\" :diagnostics [...]
      :registry {name schema} :functions {name [overload ...]} :operators {name [overload ...]}
      :unrendered [entry ...] :skipped [entry ...]}

   :functions and :operators are the schema's own, which pgmalli.honeysql resolves a call to
   besides pg_catalog's: an overload as pgmalli.impl.types/catalog holds one.

   An :unrendered or :skipped entry is {:kind :table or :domain :column :constraint :sql :type},
   the keys that apply.

   Output is deterministic (sorted maps, pprint). Overrides that are not EDN (functions)
   are rejected at generation time."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.pprint :as pp]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.registry :as mr]
            [pgmalli.impl.error :as error]
            [pgmalli.impl.ir :as ir]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.pattern :as pattern]
            [pgmalli.impl.registry :as registry]
            [pgmalli.impl.render :as render]
            [pgmalli.impl.types :as types]))

(def default-config {:schemas ["public"] :out-dir "resources/pgmalli" :overrides {} :db {}})

(defn- file-name?
  "A schema name that is one file's name in :out-dir: no separator, NUL, . or .."
  [s]
  (not (or (#{"" "." ".."} s) (re-find #"[/\\\x00]" s))))

;; a case-insensitive file system (macOS, Windows) keeps one of "public" and "Public"
(defn- distinct-files? [ss]
  (apply distinct? (map str/lower-case ss)))

(def config-schema
  [:map {:closed true}
   [:schemas {:optional true}
    [:and
     [:sequential {:min 1 :error/message "should be a vector of schema names, such as [\"public\"]"}
      [:and :string [:fn {:error/message "should be a schema name that is a file name: no /, \\, NUL, . or .."} file-name?]]]
     [:fn {:error/message "should not hold two names that differ only in case: their files would be one on a case-insensitive file system"} distinct-files?]]]
   [:out-dir {:optional true} :string]
   [:overrides {:optional true} [:map-of [:or :string :keyword] :any]]
   [:db {:optional true} [:map-of :keyword :any]]])

(defn config
  "Config with defaults applied. A key or value config-schema does not take is an error."
  [c]
  (jdbc/checked! config-schema c :pgmalli.error/invalid-config "invalid config")
  (merge default-config c))

(defn- sort-maps [x]
  (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) %) %) x))

(defn- left-out
  "An :unrendered or :skipped entry as the file keeps it: what was left out and where. :kind is
   :unparsed (a constraint pgmalli cannot read), :type (a column type it has no schema for) or
   :check (a constraint it reads but cannot express)."
  [{:keys [schema table type-name column constraint sql input type type-schema fact]}]
  (cond-> {:kind (case fact :unparsed :unparsed :unknown-type :type :check)}
    table (assoc :table (str schema "." table))
    (and type-name (not table)) (assoc :domain (str schema "." type-name))
    column (assoc :column column)
    constraint (assoc :constraint constraint)
    (or sql input) (assoc :sql (or sql input))
    type (assoc :type type)
    type-schema (assoc :type-schema type-schema)))

(defn assemble
  "The file's data from the rendering of a schema's facts, in reading order: what it is, then
   what deserves a look, then the schemas, then what was left out."
  [schema database-version facts overrides]
  (let [r (sort-maps (render/rendered facts (or overrides {})))]
    (array-map :schema schema :database-version database-version
               :diagnostics (:diagnostics r) :registry (:registry r)
               :functions (:functions r) :operators (:operators r)
               :unrendered (mapv left-out (:unrendered r)) :skipped (mapv left-out (:skipped r)))))

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
  "overrides when each key names what it overrides in facts: a column (:schema.table/column)
   with a malli schema, or a constraint name with {:skip reason}. An error names what to write
   instead."
  [overrides facts]
  (let [column-facts (filter #(= :column (:fact %)) facts)
        key-of #(render/column-override-key (:schema %) (:table %) (:column %))
        columns (map key-of column-facts)
        of-type (fn [t] (sort (for [f column-facts :when (= t (render/type-name (:type f)))] (key-of f))))
        by-table (group-by namespace columns)
        schemas (distinct (keep :schema facts))
        generated (str "the schemas generated: " (str/join ", " schemas))
        constraints (distinct (keep :constraint facts))
        listed #(str/join ", " %)
        fail! (fn [message given meant]
                (error/fail! :pgmalli.error/invalid-config (str ":overrides: " (pr-str given) " " message (error/did-you-mean (some-> meant pr-str)))
                             (cond-> {:given given} meant (assoc :did-you-mean [meant]))))]
    (doseq [[k v] overrides]
      (cond (string? k)
            (cond (not (some #{k} constraints))
                  (fail! (str "names no constraint of " generated) k (error/suggest k constraints))
                  (not (and (map? v) (contains? v :skip)))
                  (let [f (some #(when (= k (:constraint %)) %) facts)
                        cols (cond (:column f) [(key-of f)]
                                   (and (:type-name f) (not (:table f))) (of-type (:type-name f)))]
                    (fail! (str (pr-str v) ": a constraint takes {:skip reason} alone"
                                (if (seq cols)
                                  (str "; skip it and give its column a schema (" (listed cols) ")")
                                  ": a CHECK across columns is kept or skipped whole"))
                           k nil)))
            (simple-keyword? k)
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
            (and (map? v) (contains? v :skip))
            (fail! (str (pr-str v) ": only a constraint is skipped; give the column a malli schema") k nil)
            :else
            (try (m/schema v {:registry override-registry})
                 (catch Exception e
                   (error/fail! :pgmalli.error/invalid-config
                                (str ":overrides: " (pr-str k) "'s schema " (pr-str v) " is no malli schema: " (if-let [t (:type (ex-data e))] (str (name t) " " (pr-str (:data (ex-data e)))) (ex-message e)))
                                {:given k} e)))))
    overrides))

(defn major-version
  "\"PostgreSQL 17\" of the server's \"PostgreSQL 17.6 (Debian ...)\": a minor upgrade changes no
   schema. A server older than 16 is an error."
  [version]
  (let [major (parse-long (or (re-find #"\d+" version) "0"))]
    (when (< major 16)
      (error/fail! :pgmalli.error/fault (str version " is older than pgmalli reads: generate from PostgreSQL 16 or later") {:given version}))
    (str "PostgreSQL " major)))

(defn- read-schema
  "[database-version facts] of one schema, read from the database."
  [db schema]
  (let [ir (ir/from-db (assoc db :schema schema))]
    [(major-version (:database_version ir)) (pattern/facts ir)]))

(defn from-database
  "Generated data for one schema, read from the database."
  [{:keys [db overrides]} schema]
  (let [[version facts] (read-schema db schema)]
    (assemble schema version facts overrides)))

(defn path-for
  "Where a schema's file goes: <out-dir>/<schema>.edn; the config as given or with its
   defaults applied."
  [c schema]
  (str (io/file (:out-dir c (:out-dir default-config)) (str schema ".edn"))))

(defn generated-all
  "{schema {:path :data}} for every schema in the config."
  [c]
  (let [c (config c)
        read (into {} (for [s (:schemas c)] [s (read-schema (:db c) s)]))]
    (checked-overrides! (:overrides c) (mapcat second (vals read)))
    (into {} (for [[s [version facts]] read] [s {:path (path-for c s) :data (assemble s version facts (:overrides c))}]))))

(defn edn-string
  "The file's text: a deterministic pprint of the data behind the do-not-edit header. Data that
   does not read back as EDN is an error."
  [data]
  (let [s (binding [pp/*print-right-margin* 100
                    *print-namespace-maps* false]
            (with-out-str (pp/pprint data)))]
    (when-not (= data (try (edn/read-string s) (catch Exception _ ::unreadable)))
      (error/fail! :pgmalli.error/invalid-config "generated data is not readable EDN; :overrides must be data such as [:ref name], not functions"))
    (str ";; generated by pgmalli; do not edit (adjust :overrides instead)\n" s)))

(defn generate!
  "Writes every schema's file. Returns {schema path}."
  [c]
  (into {} (for [[schema {:keys [path data]}] (generated-all c)]
             (do (io/make-parents path)
                 (spit path (edn-string data))
                 [schema path]))))

(defn read-edn
  "The generated data in a file, by path (pgmalli.impl.registry reads the classpath instead)."
  [path]
  (edn/read-string (slurp path)))
