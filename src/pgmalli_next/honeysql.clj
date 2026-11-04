(ns pgmalli-next.honeysql
  "HoneySQL query data read against a registry, without a database (analyze): the problems
   PostgreSQL would report, the types of the query's arguments and of the rows it returns, and
   the tables it reads and writes; query-schema makes a function schema of them. values-schema
   and set-schema type a row written into a table.

   Names resolve as PostgreSQL resolves them (docs/queries.md#what-a-name-refers-to). Types are
   what the one reading reads and the one sending sends (docs/reading-rows.md). The functions
   take the options of docs/queries.md#options; a key or value not among them is an error."
  (:require [clojure.string :as str]
            malli.core
            [pgmalli-next.impl.args :as args]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.problems :as problems]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(def ^:private option-keys
  "The options of analyze, values-schema and set-schema; query-schema's add :result."
  [:schema :jdbc :format])

(defn- prepared
  "[registry body opts] as a public function reads them. Without a body, opts are not
   query/with-defaults': a table named with its schema needs no :schema."
  ([registry opts ks]
   (if (::query/schemas registry)
     [registry opts]
     (let [opts (jdbc/options! opts ks)]
       [(assoc (query/reading registry) ::query/format (:format opts)) opts])))
  ([registry body opts ks]
   (if (::query/schemas registry)
     [registry body opts]
     (let [[r opts] (prepared registry opts ks)]
       [r (syntax/query! body) (query/with-defaults r opts)]))))

(defn analyze
  "{:args :row :rows :problems :tables :document-columns} of a HoneySQL query against the registry, without a
   database:

   :args      {arg schema} for every named parameter (:?id, [:param :id]), keyed as sql/format's
              :params takes them; one pgmalli cannot type is [:any {:pgmalli/untyped why}]
              (docs/queries.md#the-types-of-the-arguments)
   :row       the [:map ...] of one row it returns, as next.jdbc's as-unqualified-maps builds it
              (a write with no RETURNING, its update count); nil when that cannot be told
              (docs/queries.md#the-row-a-query-returns)
   :rows      {:min n :max m} of the rows it returns where the data tells, nil otherwise
   :problems  [problem ...] PostgreSQL would report, [] when none; one in a nested statement
              carries :in, where it sits (get-in q (:in p)); humanize says each as a sentence
   :tables    {:reads #{\"schema.table\" ...} :writes #{...}}, aliases and CTEs resolved
   :document-columns
              {arg #{column ...}} for every argument that is the document of a
              jsonb_populate_record, json_populate_record or their recordset
              (docs/queries.md#document-columns)

   Names resolve as PostgreSQL resolves them. query-schema builds a function schema of it.
   Options: docs/queries.md#options. Throws when body is no HoneySQL data: SQL already
   formatted, or code (a symbol in it): pass the data the code builds.

   (analyze registry {:select [:email] :from [:users] :where [:= :id :?id]})"
  ([registry body] (analyze registry body {}))
  ([registry body opts]
   (let [[registry body opts] (prepared registry body opts option-keys)]
     {:args (args/arg-types registry body opts)
      :row (query/row-schema registry body opts)
      :rows (query/row-count registry body opts)
      :problems (into (problems/check registry body opts) (args/arg-problems registry body opts))
      :tables (query/tables registry body opts)
      :document-columns (args/document-columns registry body opts)})))

(defn query-schema
  "[:=> {:pgmalli/problems [...]} [:cat arg ...] [:sequential row]], the malli function schema of a function taking args
   and running body, of what analyze tells: the args' types (:any for a key the query lacks), the
   row (:any when it cannot be resolved), [:sequential {:max n} row] where the data tells how
   many rows (docs/queries.md#function-schemas): under {:result :one} no [:maybe] where it tells
   one.

   (query-schema registry [:db :id] {:select [:email] :from [:users] :where [:= :id :?id]})

   The problems analyze finds, and a query argument args lack (:missing-arg), are the schema's
   :pgmalli/problems property ([] for none), not an error. The return is what execute! returns on a datasource or connection, under
   {:result :one} what execute-one! returns. Options: docs/queries.md#options. Throws when args are
   not keywords."
  ([registry args body] (query-schema registry args body {}))
  ([registry args body opts]
   (when-not (and (sequential? args) (every? keyword? args))
     (error/fail! :pgmalli.error/invalid-options
                  (str "query-schema takes the function's args first, keywords ([:db :id]), not " (pr-str args))
                  {:given args}))
   (let [[registry body {:keys [result] :as opts}] (prepared registry body opts (conj option-keys :result))
         {types :args :keys [row rows problems]} (analyze registry body opts)
         problems (into problems
                        (for [k (sort-by str (syntax/query-args body)) :when (and (contains? types k) (not (some #{k} args)))] {:kind :missing-arg :arg k}))]
     (into [:=> {:pgmalli/problems problems}]
           [(into [:cat] (map #(get types % :any)) args)
            (let [{:keys [min max]} rows]
              (cond (nil? row) :any
                    (= :one result) (if (or (= query/update-count row) (= 1 min)) row [:maybe row])
                    max [:sequential (cond-> {:max max} (pos? min) (assoc :min min)) row]
                    :else [:sequential row]))]))))

(defn- written-key
  "The key of column (a keyword, or a string where the registry keys it so) in a :values or
   :set map: the key a row is read with (jdbc/row-key, :group_id), so a row read is written
   back, where HoneySQL writes it as column under sql/format's opts (the format; unquoted,
   PostgreSQL folds a name to lower case, and HoneySQL writes - as _); else the column's name as
   a string, which HoneySQL quotes."
  [column {:keys [format]}]
  (let [n (name column)
        writes? #(= n (first (syntax/formatted-name % format false)))
        k (jdbc/row-key n)]
    (cond (writes? k) k (or (string? column) (writes? n)) n :else k)))

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
    :any [:and t [:not [:or :keyword [:sequential :any]]]]
    t))

(defn- generated-values
  "t without the values of a class alone (a PGobject), which malli does not generate; nil where
   nothing is left."
  [t]
  (cond (and (= :fn (shape/head t)) (:pg/class (shape/column-props t))) nil
        (= :maybe (shape/head t)) (if-let [x (generated-values (last t))] [:maybe x] :nil)
        (= :or (shape/head t)) (some-> (seq (keep generated-values (shape/alternatives t))) (shape/either t))
        :else t))

(defn- written-row*
  "The closed map of the derived schema (\"insert\", \"update\") of table row name (:pg.public/users)
   of registry r as reading reads it: each column typed as an argument assigned to it, optional
   as the derived schema has it, keyed by written-key; one the database may fill takes :default too, and one
   it writes :default alone. update takes a non-empty map: HoneySQL writes an empty :set as SET
   alone, which PostgreSQL refuses. nil where r has no such schema."
  [r name derived opts]
  (when-let [d (get (::query/schemas r) (shape/derived-name name derived))]
    (let [s (get (::query/schemas r) name)
          written (shape/column-entries d)
          default (into [:enum] (sort-by vector? (syntax/default-values (:format opts))))
          ;; the columns the database may fill: optional in what an INSERT may carry
          fills (set (for [[k props] (shape/column-entries (get (::query/schemas r) (shape/derived-name name "insert"))) :when (:optional props)] k))
          triggered? (query/filled-by-trigger? (shape/row-props s) (keyword derived))
          ;; [keys required? type generated] of each column, generated the values it takes that
          ;; malli generates (nil for none)
          columns (concat
                   ;; one the database may fill takes HoneySQL's :default (DEFAULT) too
                   (for [[k props c] written
                         :let [assigned #(cond-> (args/accepted-type r c :assign %) (or (shape/maybe? c) triggered?) query/nullable)
                               a (assigned (assoc opts ::args/keywords-columns true))
                               t (one-value a)
                               plain (if (fills k) [:or t default] t)]]
                     ;; [:lift v] sends v as one parameter, a keyword label too
                     [(written-key k opts) (not (or (:optional props) triggered?)) [:or plain [:tuple {:error/message "should be [:lift v], v as the column takes it"} [:= :lift] (assigned opts)]] (generated-values plain)])
                   ;; one the database writes (generated, identity ALWAYS) takes it alone
                   (for [[k] (shape/column-entries s) :when (not-any? #(= k (first %)) written)]
                     [(written-key k opts) false default default]))
          entry (fn [k required? t] (if required? [k t] [k {:optional true} t]))
          row (into [:map {:closed true}] (for [[k required? t] columns] (entry k required? t)))
          ;; a column that generates nothing is left out where it may be; a required one fails to generate
          generated (into [:map] (for [[k required? t g] columns :when (or g required?)] (entry k required? (or g t))))
          nonempty [:not [:= nil {}]]
          non-empty #(if (= "update" derived) [:and % nonempty] %)]
      (shape/merge-props (non-empty row) {:gen/schema (non-empty generated)}))))

(defn- written-row
  "written-row* of the table name names (a registry row name, or a table keyword as HoneySQL
   names it, in :schema); an error naming what was meant for another name."
  [registry name derived what opts]
  (when-not (keyword? name)
    (error/fail! :pgmalli.error/invalid-options
                 (str what " takes a table as a keyword, as HoneySQL names it (:users, :app.users) or its row schema (:pg.public/users); got " (pr-str name))
                 {:given name}))
  (let [[r opts] (prepared registry opts option-keys)
        _ (when (contains? opts :schema) (query/with-defaults r opts))
        registry (::query/schemas r)
        ;; a registry name (:pg.app/users), or a mistyped one (:pg.public.users) as it is, for schema-of to
        ;; name the one meant; a table as HoneySQL names it (:users, :app.users) qualified in :schema
        name (if (str/starts-with? (subs (str name) 1) "pg.")
               name
               (let [t (syntax/qualified name (if (or (namespace name) (str/includes? (clojure.core/name name) "."))
                                                (select-keys opts [:format])
                                                (query/with-defaults r opts)))]
                 (when (< 2 (count (str/split t #"\.")))
                   (error/fail! :pgmalli.error/invalid-options
                                (str what " takes a table as :table or :schema.table; " (pr-str name) " names more")
                                {:given name}))
                 (shape/table-key t)))
        _ (when-not (contains? registry name)
            (query/unknown-table! r (if (namespace name) (str/join "." (shape/schema-and-name name)) (clojure.core/name name))))
        s (shape/schema-of registry name)]
    (cond (shape/row-schema? s) nil
          (shape/read-row? s) (error/fail! :pgmalli.error/unknown-schema
                                           (str (pr-str name) " is a view's row schema, and a view has no " derived " schema; " what " takes a table's")
                                           {:given name})
          :else (shape/not-a-row! registry name what))
    (or (written-row* r name derived opts)
        (let [dn (shape/derived-name name derived)]
          (error/fail! :pgmalli.error/unknown-schema
                       (str "the registry has no " derived " schema " (pr-str dn) " for " (pr-str name) "; pgmalli-next.core/registry derives it")
                       {:given dn})))))

(defn values-schema
  "The schema of one row an INSERT's :values may carry into a table named as HoneySQL names it
   (:users in :schema) or by its row schema (:pg.public/users):
   docs/queries.md#a-row-written-into-a-table. Options: docs/queries.md#options.

   (values-schema registry :users)"
  ([registry name] (values-schema registry name {}))
  ([registry name opts] (written-row registry name "insert" "values-schema" opts)))

(defn set-schema
  "The schema of the map an UPDATE's :set may carry into a table: as values-schema, every column
   optional, at least one.

   (set-schema registry :users)"
  ([registry name] (set-schema registry name {}))
  ([registry name opts] (written-row registry name "update" "set-schema" opts)))

(defn- column-name [c] (if (keyword? c) (subs (str c) 1) (str c)))

(def ^:private explanations
  "The sentence for each kind of problem: what is wrong, then what to do."
  {:unknown-table (fn [{:keys [table did-you-mean quoted]}]
                   (let [elsewhere (first (filter #(= (syntax/table-name %) (syntax/table-name table)) did-you-mean))]
                     (str "unknown table " table
                          (cond quoted (str "; under :quoted, PostgreSQL looks the name up as written: for the table "
                                            (syntax/folded table) " write :" (syntax/folded (syntax/table-name table)))
                                elsewhere (str "; " elsewhere " exists; pass {:schema " (pr-str (subs elsewhere 0 (str/last-index-of elsewhere "."))) "}"
                                               ", or name it :" elsewhere)
                                did-you-mean (error/did-you-mean (str/join " or " did-you-mean))
                                :else "; check its name, or :schema when it is in another schema"))))
   :unknown-column (fn [{:keys [column did-you-mean qualifier quoted]}]
                     (str "unknown column " (column-name column)
                          (when qualifier (str "; no table or alias " qualifier " in the statement"))
                          (cond quoted (str "; under :quoted, PostgreSQL looks the name up as written: for the column "
                                            (syntax/folded (column-name column)) " write :" (syntax/folded (column-name column)))
                                did-you-mean (error/did-you-mean (str/join " or " (map column-name did-you-mean)))
                                (not qualifier) "; no table of the statement has it")))
   :ambiguous-column (fn [{:keys [column candidates]}]
                       (str "column " (column-name column) " is in " (str/join " and " candidates) "; qualify it with the table's alias"))
   :missing-required-column (fn [{:keys [table column]}]
                              (str "INSERT into " table " lacks " (column-name column) ", which is NOT NULL with no default"))
   :values-arity (fn [{:keys [row columns values]}]
                   (str ":values row " row " has " values " values for " columns " :columns; give each row as many values as :columns"))
   :enum-literal (fn [{:keys [column value allowed]}]
                   (str (pr-str value) " is no value of " (column-name column) "; it takes " (str/join ", " (map pr-str allowed))))
   :uncast-string (fn [{:keys [column value type]}]
                    (str (pr-str value) " reaches column " (column-name column) " as a typed string, which its type " type " does not take"
                         " (HoneySQL sends a string as a parameter, which pgjdbc sends as varchar);"
                         " write [:cast " (pr-str value) " "
                         (if (types/element type) (str "[:raw " (pr-str type) "]") (str ":" type))
                         "]"))
   :not-null-violation (fn [{:keys [table column]}] (str "NULL written into " table "." (column-name column) ", which is NOT NULL"))
   :read-only-column (fn [{:keys [table column]}]
                       (str table "." (column-name column) " is written by the database (generated, or identity ALWAYS);"
                            " leave it out, or write :default"))
   :unread-clause (fn [{:keys [clause did-you-mean]}]
                    (if did-you-mean
                      (str "clause " clause " is no clause HoneySQL or pgmalli knows" (error/did-you-mean (first did-you-mean)))
                      (str "clause " clause " is not read by pgmalli, so what it does is not checked; check the clause name, or check that part by hand")))
   :missing-arg (fn [{:keys [arg]}] (str "the query takes argument " arg ", which the function's args lack; a typo in args?"))
   :undetermined-type (fn [{:keys [arg function type]}]
                        (let [x (keyword (str "?" (name arg)))]
                          (str "argument " arg " goes to " function ", which takes its type from the value sent, but every value"
                               " its other uses take is sent untyped (a LocalDate, a keyword label) or is nil, which PostgreSQL"
                               " cannot type there (could not determine data type); cast it in the query: "
                               (cond (nil? type) (str "[:cast " x " <the type meant>]")
                                     (str/ends-with? type "[]") (str "[:cast " x " [:raw " (pr-str type) "]]")
                                     :else (str "[:cast " x " " (keyword (str/replace type " " "-")) "]")))))})

(defn humanize
  "A sentence for each problem of analyze's :problems (or of query-schema's :pgmalli/problems):
   what is wrong and what to do.

   (let [{:keys [problems]} (analyze registry q)] (is (= [] problems) (humanize problems)))"
  [problems]
  (mapv (fn [{:keys [kind in var] :as p}]
          (str (when var (str (symbol var) ": "))
               (if-let [f (explanations kind)] (f p) (pr-str p)) (when in (str " (in the statement at " in ")"))))
        problems))

(defn schema-problems
  "[problem ...] of the :malli/schema of every var in the namespaces (all loaded ones by
   default), each with its :var: query-schema's :pgmalli/problems, which a schema carries
   rather than throws, so a namespace still loads. One test asserts there are none:

   (is (= [] (schema-problems)))"
  ([] (schema-problems {}))
  ([{:keys [ns] :or {ns (all-ns)} :as opts}]
   (when-not (and (map? opts) (every? #{:ns} (keys opts)))
     (error/fail! :pgmalli.error/invalid-options
                  (str "schema-problems takes {:ns n}, n a namespace's symbol, a namespace or a collection of them; given " (pr-str opts))
                  {:given opts}))
   (vec (for [n (if (coll? ns) ns [ns])
              [_ v] (sort-by key (ns-interns (or (cond (symbol? n) (find-ns n) (instance? clojure.lang.Namespace n) n)
                                                 (error/fail! :pgmalli.error/invalid-options
                                                              (str "schema-problems reads loaded namespaces, named by symbols; "
                                                                   (pr-str n) " is none" (when (symbol? n) " (require it first)"))
                                                              {:given n}))))
              :let [s (:malli/schema (meta v))
                    props (cond (satisfies? malli.core/Schema s) (malli.core/properties s)
                                (and (vector? s) (map? (second s))) (second s))]
              p (:pgmalli/problems props)]
          (assoc p :var v)))))
