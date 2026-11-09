(ns pgmalli-next.honeysql
  "HoneySQL query data read against a registry, without a database (analyze): the problems
   PostgreSQL would report, the types of the query's arguments and of the rows it returns, and
   the tables it reads and writes; query-schema makes a function schema of them. values-schema
   and set-schema type a row written into a table.

   Names resolve as PostgreSQL resolves them (docs/queries.md#what-a-name-refers-to). Types are
   what the one reading reads and the one sending sends (pgmalli-next.jdbc, docs/reading-rows.md). The functions
   take the options of docs/queries.md#options; a key or value not among them is an error."
  (:require [clojure.string :as str]
            malli.core
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.args :as args]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.memo :as memo]
            [pgmalli-next.impl.pgtypes :as pgtypes]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.problems :as problems]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.registry :as registry]
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
     (let [opts (jdbc/builder! registry (jdbc/options! opts ks))]
       [(assoc (query/reading registry) ::query/format (:format opts)) opts])))
  ([registry body opts ks]
   (if (::query/schemas registry)
     [registry body opts]
     (let [[r opts] (prepared registry opts ks)]
       [r (syntax/query! body) (query/with-defaults r opts)]))))

(defn- as-written
  "problems with each :in a path in written, the query as given."
  [written problems]
  (mapv #(cond-> % (:in %) (update :in (partial syntax/written-path written))) problems))

(defn analyze
  "{:args :row :rows :problems :tables :document-columns} of a HoneySQL query against the registry, without a
   database:

   :args      {arg schema} for every named parameter (:?id, [:param :id]), keyed as sql/format's
              :params takes them; one pgmalli cannot type is [:any {:pgmalli/untyped why}]
              (docs/queries.md#the-types-of-the-arguments)
   :row       the [:map ...] of one row it returns, as pgmalli-next.jdbc/builder builds it
              (a write with no RETURNING, its update count); nil when that cannot be told
              (docs/queries.md#the-row-a-query-returns)
   :rows      {:min n :max m} of the rows it returns where the data tells, nil otherwise
   :problems  [problem ...] PostgreSQL would report, [] when none; one in a nested statement
              carries :in, where it sits (get-in q (:in p)); humanize says each as a sentence
   :tables    {:reads #{\"schema.table\" ...} :writes #{...}}, aliases and CTEs resolved
   :document-columns
              {arg #{column ...}} for every argument that is the document of a
              jsonb_populate_record, jsonb_to_record and the like, nil where a use names no
              column in particular (docs/queries.md#document-columns)

   Names resolve as PostgreSQL resolves them. query-schema builds a function schema of it.
   Options: docs/queries.md#options. Throws when body is no HoneySQL data: SQL already
   formatted, or code (a symbol in it): docs/queries.md#stricter-than-postgresql.

   (analyze registry {:select [:email] :from [:users] :where [:= :id :?id]})"
  ([registry body] (analyze registry body {}))
  ([registry written opts]
   (let [[registry body opts] (prepared registry written opts option-keys)]
     (binding [syntax/*scans* (or syntax/*scans* (::query/scans registry))]
     {:args (args/arg-types registry body opts)
      :row (query/row-schema registry body opts)
      :rows (query/row-count registry body opts)
      :problems (as-written written (let [ps (concat (problems/check registry body opts) (args/arg-problems registry body opts) (args/literal-problems registry body opts))]
                                      (concat ps (problems/unsent-lifted body ps opts))))
      :tables (query/tables registry body opts)
      :document-columns (args/document-columns registry body opts)}))))

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
  ([registry args written opts]
   (when-not (and (sequential? args) (every? keyword? args))
     (error/fail! :pgmalli.error/invalid-options
                  (str "query-schema takes the function's args first, keywords ([:db :id]), not " (pr-str args))
                  {:given args}))
   (let [[registry body {:keys [result] :as opts}] (prepared registry written opts (conj option-keys :result))
         {types :args :keys [row rows problems]} (analyze registry body opts)
         problems (as-written written problems)
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
   a string, which HoneySQL quotes; nil where neither writes it (a.b, which HoneySQL writes as a
   qualified name, a-b under :quoted-snake)."
  [column {:keys [format]}]
  (let [n (name column)
        ;; HoneySQL writes it as n, and PostgreSQL reads that as the column: quoted, or unquoted a
        ;; name of an identifier's characters and no keyword (order, user)
        writes? #(let [[text quoted?] (syntax/formatted-name % format false)]
                   (and (= n text) (not (str/includes? n "."))
                        (or quoted? (and (re-matches #"[a-z_][a-z0-9_$]*" n) (not (types/reserved-word? n))))))
        k (jdbc/row-key n)]
    (cond (writes? k) k (writes? n) n)))

(defn- unwritten
  "The type of a required column no key writes (written-key's nil): no value, said so."
  [column]
  [:fn {:error/message (str "HoneySQL writes no key of a map as column " column " under this :format")} (constantly false)])

(def ^:private in-a-row
  "What HoneySQL writes a value of a kind as in a row (:values, :set): no value but a column, a
   row of parameters or a subquery."
  {:keyword "a column" :vector "a row of parameters, (?, ?)" :map "a subquery"})

(defn- lift-message
  "The :error/fn of [:tuple {:pg/type column-type} [:= :lift] t], a column's [:lift v]: v one of t's labels, or as the
   column takes it; and, of a value given bare, why HoneySQL writes it as no value there (a
   keyword as a column, a map as a subquery), or why the column takes it not as its class goes
   (a String as varchar to an enum, an Instant as a timestamptz to a timestamp), with what to
   write instead."
  [{:keys [schema value]} _]
  (let [lifted (shape/non-null (malli.core/form (last (malli.core/children schema))))
        labels (let [e (shape/and-base lifted)] (when (= :enum (shape/head e)) (shape/entries e)))
        type (:pg/type (malli.core/properties schema))
        sent (when (some? value) (jdbc/sent-type value {}))
        c (class value)
        ;; the value given is said by a label of the schema alone: it may be a request's, of any size
        label (when (or (keyword? value) (string? value)) (some #(when (= (name value) (name %)) (keyword (name %))) labels))]
    (str "should be [:lift v], v " (if labels (str "one of " (str/join " " (map pr-str labels))) "as the column takes it")
         (cond (or (nil? value) (vector? value)) nil
               (keyword? value) (str "; HoneySQL writes a bare keyword as " (in-a-row :keyword)
                                     (cond label (str ": write " (pr-str [:lift label]))
                                           labels ", and the keyword is no label"
                                           :else ": write [:lift v]"))
               (map? value) (str "; HoneySQL writes a map there as " (in-a-row :map) ": write [:lift <the map>]")
               (and labels (string? value)) (str "; a String goes as varchar, which an enum takes not: write the label as a keyword, "
                                                 (if label (pr-str [:lift label]) "[:lift :label]"))
               (and (string? sent) type (accept/takes? type sent c :assign))
               "; the value is none the column's schema takes (a column's shape, a range): write one it takes"
               (and (string? sent) type) (str "; " (error/named (.getName c)) " goes as " sent ", which " type
                                              " takes through no implicit conversion (docs/queries.md#stricter-than-postgresql): send the column's own value")
               :else (str "; " (error/named (.getName c)) " is none of these")))))

(defn- extra-key-message
  "The :error/fn of a written row's map: of a key no column has, the column's key it spells
   otherwise (:group-id for :group_id), which HoneySQL writes as that column but a row is not
   read with, or the string of its name (\"order\" for :order), which HoneySQL would write as no
   column; nil for another error."
  [{:keys [schema type in]} _]
  (let [k (last in)
        kebab #(when (keyword? %) (keyword (str/replace (name %) "_" "-")))
        ks (map first (malli.core/children schema))]
    (when (and (= :malli.core/extra-key type) (keyword? k))
      (if-let [c (some #(when (and (not= % k) (= k (kebab %))) %) ks)]
        (str "disallowed key: the column is " (pr-str c) ", the key a row is read with")
        (when-let [n (some #{(subs (str k) 1)} ks)]
          (str "disallowed key: the column is " (pr-str n) ", a string, as HoneySQL writes " (pr-str k) " as no column"))))))

(def ^:private not-one-value
  "What HoneySQL writes in :values or :set as something else than one value: a keyword as a
   column, a Clojure vector or sequence as a row of parameters, (?, ?), which no column takes, a
   map as a subquery or a clause."
  [:or :keyword [:sequential :any] [:map-of :any :any]])

(defn- one-value
  "t without not-one-value's values (:default is added after), nil where nothing is left (a jsonb
   column's map, a vector); a set, or a Java array, is one value."
  [t]
  (case (shape/head t)
    :maybe (if-let [x (one-value (last t))] [:maybe x] :nil)
    :and (when (one-value (shape/and-base t)) (shape/update-and-base t one-value))
    :or (some-> (seq (keep one-value (remove #(or (= :keyword (shape/kind %)) (#{:vector :sequential :map :map-of} (shape/head %)))
                                             (shape/alternatives t))))
                (shape/either t))
    (:map :map-of :vector :sequential) nil
    (:any :some) [:and t [:not {:error/message (str "should be one value: HoneySQL writes a keyword in a row as " (in-a-row :keyword) ", a vector as " (in-a-row :vector) ", a map as " (in-a-row :map) "; write [:lift v]")}
                          not-one-value]]
    t))

(defn- java-array
  "A Java array of the elements of v, of the first class the one sending sends as an array
   (accept/classes, of no primitive) that valid? takes, a java.time value as the java.sql one of
   it (a LocalDate a java.sql.Date); nil for none."
  [valid? v]
  (some (fn [[^Class c]]
          (when (and c (.isArray c) (not (.isPrimitive (.getComponentType c))))
            (when-let [a (try (into-array (.getComponentType c)
                                          (map #(condp instance? %
                                                  java.time.LocalDate (java.sql.Date/valueOf ^java.time.LocalDate %)
                                                  java.time.LocalDateTime (java.sql.Timestamp/valueOf ^java.time.LocalDateTime %)
                                                  java.time.Instant (java.sql.Timestamp/from ^java.time.Instant %)
                                                  java.util.Date (if (= java.sql.Date (.getComponentType c))
                                                                   (java.sql.Date. (.getTime ^java.util.Date %))
                                                                   (java.sql.Timestamp. (.getTime ^java.util.Date %)))
                                                  %)
                                               v))
                              (catch Exception _ nil))]
              (when (valid? a) a))))
        accept/classes))

(def ^:private json-scalar
  "What generation makes of a value a map or a vector holds that may be any (jsonb's [:map-of
   :some :any]): a value JSON holds as itself."
  [:or :string :int :boolean :nil])

(def ^:private gen-keys [:gen/min :gen/max :gen/schema :gen/elements :gen/fmap :gen/gen])

(defn- generated-values
  "t, where a value of column schema own goes, without the values malli generates nothing the one
   sending sends of: a predicate of no :gen/ property (a class alone, a PGobject; what
   sql/format's :inline writes no literal of), any value (:any, :some: of a type pgmalli does
   not type, alone or among alternatives); its Java arrays ([:pg/array e]) generated as one of
   the elements e generates (java-array), of registry r, and the maps and vectors of any values
   (jsonb's) as JSON holds them (json-scalar); a value of own's kind within the bounds the
   registry generates own's in (a varchar(n)'s n, 2025's dates), a keyword as short, and no date
   or time, nor a numeric(p, s)'s or a real's number, of another kind, which it bounds not, a string of a type of other values one of its
   literals (types/types', a regclass's that names a relation), nor a value of another kind held to a
   predicate; nil where nothing is left."
  [r own t]
  (let [h (shape/head t)
        own (shape/non-null own)
        hints (select-keys (shape/props own) gen-keys)
        k (shape/kind t)]
    (cond ;; an override's reference to a schema of the user's registry, inlined in t: generated
          ;; as that schema generates (portable-data drops its hints)
          (and (= :ref (shape/head own)) (not (shape/generated-name? (last own)))
               (some->> (get (::query/schemas r) (last own)) shape/kind (= (shape/kind t))))
          (shape/merge-props t {:gen/schema [:ref (last own)]})
          (and (= :fn h) (not-any? #(= "gen" (namespace %)) (keys (shape/props t)))) nil
          (and (= :pg/class h) (empty? (pgtypes/class-values (shape/entries t)))) nil
          ;; held to classes ([:and float? [:pg/class "java.lang.Float"]]): those classes' values
          (and (= :and h) (some #(= :pg/class (shape/head %)) (shape/entries t)))
          (let [cs (set (mapcat #(when (= :pg/class (shape/head %)) (shape/entries %)) (shape/entries t)))]
            (cond (= #{"java.lang.Float"} cs) (shape/merge-props t {:gen/schema [:double {:min -1e6 :max 1e6}] :gen/fmap float})
                  :else (when-let [vs (seq (pgtypes/class-values cs))] (shape/merge-props t {:gen/elements (vec vs)}))))
          (#{:any :some} h) nil
          (= :maybe h) (if-let [x (generated-values r own (last t))] [:maybe x] :nil)
          ;; a numeric(p, s)'s or a real's alternative of a generator of its own (an inlined literal's),
          ;; which it bounds not: its own values, of own's hints, are generated
          (and (let [cp (shape/column-props own)] (or (:pg/precision cp) (types/single-precision? (shape/type-name cp))))
               (= :and h) (seq (select-keys (shape/props t) gen-keys)))
          nil
          ;; of alternatives one of which is any value, the type is untold: none generated
          (= :or h) (when-not (some #(#{:any :some} (shape/head %)) (shape/alternatives t))
                      (some-> (seq (keep #(generated-values r own %) (shape/alternatives t))) (shape/either t)))
          ;; of another kind than own's, a check malli generates for by retrying may never hold (a
          ;; BigDecimal of scale 0): its own kind's values are generated
          (and (= :and h) (not= k (shape/kind own)) (some #(= :fn (shape/head %)) (rest (shape/entries t)))) nil
          (= :and h) (when-let [b (generated-values r own (shape/and-base t))] (shape/update-and-base t (constantly b)))
          (= :pg/array h)
          ;; of elements but NULL, an array of NULLs alone telling nothing of its type
          ;; its elements of the array's precision (a numeric(p, s)[]'s)
          (when-let [e (generated-values r (when (= :vector (shape/head own))
                                             (shape/merge-props (last own) (select-keys (shape/props own) [:pg/precision :pg/scale])))
                                         (shape/non-null (last t)))]
            (let [valid? (malli.core/validator t (args/malli-options r))]
              (shape/merge-props t {:gen/schema [:vector {:min 1 :max 3} e] :gen/fmap #(java-array valid? %)})))
          (and (#{:map-of :vector :sequential} h) (#{:any :some} (shape/head (last t))))
          (shape/merge-props t {:gen/schema (if (= :map-of h) [:map-of {:max 3} :string json-scalar] [:vector {:max 3} json-scalar])})
          (and (seq hints) (= k (shape/kind own)) (empty? (select-keys (shape/props t) gen-keys))) (shape/merge-props t hints)
          (and (= :keyword k) (= :string (shape/kind own)) (:gen/max hints))
          (shape/merge-props t {:gen/schema [:string {:min 1 :max (:gen/max hints)}] :gen/fmap keyword})
          (and (keyword? k) (= "time" (namespace k)) (not= k (shape/kind own))) nil
          ;; a numeric(p, s)'s or a real's values of another kind than its own, which they bound not
          (and (let [cp (shape/column-props own)] (or (:pg/precision cp) (types/single-precision? (shape/type-name cp))))
               (not= k (shape/kind own)))
          nil
          ;; the text of a type of no string values (a regclass's, any type's under :inline, a UUID's
          ;; too), its input reads: one of its literals, none where it has none
          (and (#{:string :uuid} k) (not= k (shape/kind own)) (not= :enum (shape/kind own)) (some? own))
          (when-let [ls (some-> own shape/column-props shape/type-name types/entry :literals)]
            (shape/merge-props t {:gen/elements ls}))
          :else t)))

(def ^:private sql-word-schema
  "[:fn] of what HoneySQL writes as NULL or DEFAULT (word) under format, one per format so that
   the schemas of one call equal the next's: DEFAULT generated as [:default], which every format
   writes so; nil for NULL where HoneySQL writes no keyword as NULL under format (:quoted)."
  (memo/bounded
   (fn [word format]
     (let [[pred candidates] (case word
                               :null [#(and (keyword? %) (syntax/null-value? % format)) [:null :NULL]]
                               :default [#(syntax/default-value? % format) [[:default]]])]
       (when-let [gen (first (filter pred candidates))]
         [:fn {:error/message (str "should be " (str/upper-case (name word)) " as HoneySQL writes it (" (pr-str gen) ")")
               :gen/elements [gen] :pgmalli/describe (pr-str gen)}
          pred])))))

(defn- written-row*
  "The closed map of the derived schema (\"insert\", \"update\") of table row name (:pg.public/users)
   of registry r as reading reads it: each column typed as an argument assigned to it, optional
   as the derived schema has it, keyed by written-key; one the database may fill takes DEFAULT too, and one
   it writes DEFAULT alone. A non-empty map: HoneySQL writes an empty :set as SET alone, an empty
   row as VALUES (), which PostgreSQL refuses. nil where r has no such schema."
  [r name derived opts]
  (when (get (::query/schemas r) (shape/derived-name name derived))
    (let [schemas (::query/schemas r)
          s (get schemas name)
          ;; the derived schemas as without a trigger, which fills what it fills alone
          strict #(shape/column-entries (registry/derived-schema s % schemas true))
          written (strict derived)
          default (sql-word-schema :default (:format opts))
          ;; the columns the database may fill: optional in what an INSERT may carry
          fills (set (for [[k props] (strict "insert") :when (:optional props)] k))
          triggered? (shape/filled-by-trigger? (shape/row-props s) (keyword derived))
          ;; [plain lift lifted] of column c, nullable? where NULL may go there: plain its values as
          ;; HoneySQL writes them in a map (a keyword is a column), lift [:lift v] of lifted's
          typed (fn [k c nullable?]
                  (let [assigned #(cond-> (args/accepted-type r c :assign %) nullable? query/nullable)
                        a (assigned (assoc opts ::args/keywords-columns true))
                        ;; a nullable column takes a keyword HoneySQL writes as NULL (:null, :NULL) too
                        one (one-value a)
                        alts (remove nil? [one
                                           (when nullable? (sql-word-schema :null (:format opts)))
                                           (when (fills k) default)])
                        lifted (assigned opts)]
                    [(when (seq alts) (if (next alts) (into [:or] alts) (first alts)))
                     ;; [:lift v] sends v as one parameter, a keyword label and a map too
                     [:tuple {:error/fn lift-message :pg/type (:pg/type (shape/column-props c))} [:= {:error/message (str "should be :lift, [:lift v]: HoneySQL writes a vector of values as " (in-a-row :vector) ", which no column takes")} :lift] lifted]
                     lifted
                     ;; [x] or (x), which HoneySQL writes (?), x's own value
                     [:sequential {:min 1 :max 1 :error/message "should be [v], which HoneySQL writes (?), v one value"}
                      (shape/either (remove nil? [one [:tuple [:= :lift] lifted]]) nil)]]))
          ;; [key required? type generated generated-required?] of each column, generated the values
          ;; it takes that malli generates (nil for none): of a column a trigger may fill, as
          ;; without the trigger, which fills what it fills alone
          columns (concat
                   (for [[k props c] written
                         :let [[plain lift _ one-written] (typed k c (or (shape/maybe? c) triggered?))
                               one-written (when plain one-written)
                               [gen-plain _ gen-lifted] (typed k c (shape/maybe? c))
                               gen (some->> [(some->> gen-plain (generated-values r c))
                                             (some->> gen-lifted (generated-values r c) (vector :tuple [:= :lift]))]
                                            (remove nil?) seq (#(if (next %) (into [:or] %) (first %))))
                               wk (written-key k opts)
                               required? (not (or (:optional props) triggered?))]
                         :when (or wk required?)]
                     (if wk
                       [wk required?
                        (if plain (into [:or lift plain] (when one-written [one-written])) lift)
                        gen
                        (not (:optional props))]
                       [(clojure.core/name k) true (unwritten (clojure.core/name k)) nil true]))
                   (for [[k] (shape/column-entries s) :when (not-any? #(= k (first %)) written)
                         :let [wk (written-key k opts)] :when wk]
                     [wk false default default false]))
          entry (fn [k required? t] (if required? [k t] [k {:optional true} t]))
          row (into [:map {:closed true :error/fn extra-key-message}] (for [[k required? t] columns] (entry k required? t)))
          ;; a column that generates nothing is left out where it may be; a required one fails to generate
          generated (into [:map] (for [[k _ _ g required?] columns :when (or g required?)] (entry k required? (or g [:fn {:pgmalli/no-values k} any?]))))
          ;; HoneySQL writes an empty :set as SET alone, an empty row as VALUES (), which PostgreSQL refuses
          non-empty #(vector :and % [:not [:= nil {}]])]
      (shape/merge-props (non-empty row) {:gen/schema (non-empty generated)}))))

(defn- written-row
  "written-row* of the table query/row-name reads name as."
  [registry name derived what opts]
  (let [[r opts] (prepared registry opts option-keys)
        name (query/row-name r name what opts)
        registry (::query/schemas r)
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

(defn- column-name
  "c as HoneySQL writes it in SQL: :u/nick as u.nick."
  [c]
  (cond (and (keyword? c) (namespace c)) (str (namespace c) "." (name c)) (keyword? c) (name c) :else (str c)))

(defn- field-target-text
  "The sentence of an assigned target qualified by its table or alias, a field of a column to PostgreSQL."
  [column did-you-mean]
  (str ": PostgreSQL reads an assigned " (column-name column) " as a field of a column "
       (first (str/split (column-name column) #"\.")) "; assign "
       (if did-you-mean (str "it unqualified: " (column-name (first did-you-mean))) "the column unqualified")))

(defn- shown
  "A literal of a query as a problem's sentence says it: its text up to 60 characters, a longer
   one by its class (a request's value may be any size)."
  [v]
  (let [s (pr-str v)] (if (< 60 (count s)) (str "<a " (.getSimpleName (class v)) ">") s)))

(def ^:private explanations
  "The sentence for each kind of problem: what is wrong, then what to do."
  {:unknown-table (fn [{:keys [table did-you-mean quoted]}] (query/unknown-table-text table did-you-mean quoted))
   :unknown-column (fn [{:keys [column did-you-mean qualifier quoted clause side table target constraint outside-join]}]
                     (let [meant (when did-you-mean (error/did-you-mean (str/join " or " (map column-name did-you-mean))))]
                       (str (if outside-join "column " "unknown column ") (column-name column)
                            (when qualifier
                              (if outside-join
                                (str " is out of reach: " qualifier " is outside the parenthesized join, whose ONs see its own tables alone")
                                (str "; no table or alias " qualifier " in the statement")))
                            (case clause
                              :using (str " in USING: " (case side :left "no table before the join has it"
                                                              :right (str table " lacks it")
                                                              :both (str "neither the tables before the join nor " table " has it"))
                                          meant)
                              :on-conflict (if constraint
                                             (str " in ON CONFLICT: HoneySQL writes a vector as columns; for a constraint write {:on-constraint "
                                                  (keyword (name constraint)) "}")
                                             (str " in ON CONFLICT: " table " lacks it" meant))
                              (:values :set :do-update-set) (if (or (and (= :values clause) table) target)
                                                              (str " in " clause (when target (str " for " (column-name target)))
                                                                   ": HoneySQL writes a keyword there as a column; write the value itself"
                                                                   " ([:lift " column "] for an enum label, a string for text)")
                                                              (field-target-text column did-you-mean))
                              :columns (field-target-text column did-you-mean)
                              (cond quoted (str "; under :quoted, PostgreSQL looks the name up as written: for the column "
                                                (syntax/folded (column-name column)) " write :" (syntax/folded (column-name column)))
                                    meant meant
                                    (not qualifier) "; no table of the statement has it")))))
   :ambiguous-column (fn [{:keys [column candidates]}]
                       (if (next candidates)
                         (str "column " (column-name column) " is in " (str/join " and " candidates) "; qualify it with the table's alias")
                         (str "column " (column-name column) " names several columns of " (first candidates) "; give them names apart there ([expr alias])")))
   :duplicate-column (fn [{:keys [column items]}]
                       (let [label (name column)
                             x (last items)
                             star? (and (keyword? x) (str/ends-with? (name x) "*"))
                             alias (when-not star?
                                     (let [a (if (syntax/column-keyword? x) (str/replace (name x) "." "_") label)]
                                       (keyword (cond (not= a label) a (syntax/keyword-name? a) (str a "_2") :else "column_2"))))]
                         (str (str/join " and " (map pr-str items)) (if (next items) " make" " makes") " several columns labelled " label
                              "; pgmalli-next.jdbc/builder reads them into one key " column ", keeping the last one's value; "
                              (if star?
                                (str "list the columns of " x " instead, giving one an alias of its own")
                                (str "give one an alias of its own: " (pr-str [(if (keyword? x) x (first x)) alias]))))))
   :missing-required-column (fn [{:keys [table column]}]
                              (str "INSERT into " table " lacks " (column-name column) ", which is NOT NULL with no default"
                                   "; give every row a value of " (column-name column)))
   :values-arity (fn [{:keys [row columns values]}]
                   (str ":values row " row " has " values (if (= 1 values) " value" " values") " for " columns " :columns; give each row as many values as :columns"))
   :enum-literal (fn [{:keys [column type value allowed]}]
                   (str (shown value) " is no value of " (if column (column-name column) (str "type " type)) "; it takes " (str/join ", " (map pr-str allowed))))
   :no-operator (fn [{:keys [column op domain type]}]
                  (str "PostgreSQL has no " (if (syntax/in-ops op) "=" (name op)) " for " (column-name column) ", of domain " domain " over enum " type
                       "; compare the enum: " (syntax/cast-form (pr-str column) type)))
   :uncast-string (fn [{:keys [column function value type]}]
                    (str (shown value) " reaches " (cond (keyword? column) (str "column " (column-name column))
                                                          column (pr-str column)
                                                          :else (str "an argument of " function))
                         " as a typed string, which its type " type " does not take"
                         " (HoneySQL sends a string as a parameter, which pgjdbc sends as varchar); write "
                         ;; an enum's label: sent untyped, as values-schema says
                         (if (and (nil? (types/entry type)) (re-matches #"[A-Za-z_][\w-]*" value))
                           (pr-str [:lift (keyword value)])
                           (syntax/cast-form (shown value) type))))
   :literal-type (fn [{:keys [function column value sent type]}]
                   (let [to (if column (str "column " (column-name column)) function)
                         ;; a value of no literal syntax (an Instant) by its class
                         printed? (or (string? value) (number? value) (boolean? value) (coll? value) (uuid? value))
                         x (if printed? (shown value) (str "<the " (.getSimpleName (class value)) ">"))]
                     (cond
                       (not sent)
                       (str (shown value) (if to (str " goes to " to " as a parameter") " is sent as a parameter") ", which pgjdbc sends no " (.getSimpleName (class value))
                            " as; send a vector (jsonb) or a Java array (an array type)")
                       ;; of the type taken, out of the values the schema holds it to (a column's shape)
                       (= sent type)
                       (str (shown value) " goes to " to " as " sent ", its own type, but is none of the values its schema takes"
                            " (a column's shape, a range): write one it takes")
                       :else
                     (str (if printed? (shown value) (str "a " (.getName (class value)) " (" value ")")) " goes to " to " as a parameter, which pgjdbc sends as " sent ", a type " to
                          " takes there through no implicit conversion; "
                          (if (coll? value)
                            ;; jsonb, which casts to no array or enum
                            (str "send the value of " (or type "the type meant") " itself (an array as a Java array, (into-array ...); a label as [:lift :label])")
                            (str "cast it, " (if type (syntax/cast-form x type) (str "[:cast " x " <the type meant>]"))))))))
   :unknown-type (fn [{:keys [type did-you-mean]}]
                   (str "type " type " is in neither pg_catalog nor the query's schema" (error/did-you-mean (str/join " or " did-you-mean))
                        "; qualify it, " (syntax/cast-form "x" (first did-you-mean)) ", or pass that schema as :schema"))
   :not-null-violation (fn [{:keys [table column clause]}]
                         (str "NULL written into " table "." (column-name column) ", which is NOT NULL; write a value"
                              (when (= :values clause) ", or [:default] where the column has a default")))
   :unlifted-value (fn [{:keys [table column clause value type]}]
                     (str (shown value) " in " clause " for " table "." (column-name column) ": HoneySQL writes a "
                          (if (map? value) (str "map there as " (in-a-row :map)) (str "vector there as " (in-a-row :vector)))
                          "; write " (cond (types/json? type) (str (shown [:lift value]) ", sent as one parameter (jsonb)")
                                           (types/element type) (str "a Java array of " type ", (into-array ...)")
                                           :else (str "a value of " type))))
   :read-only-column (fn [{:keys [table column]}]
                       (str table "." (column-name column) " is written by the database (generated, or identity ALWAYS);"
                            " leave it out, or write [:default]"))
   :written-otherwise (fn [{:keys [op dropped call column clause before param columns order-by] :as p}]
                        (cond
                          order-by
                          (let [[x d & more] order-by]
                            (str (pr-str order-by) " is written " (pr-str x) " " (str/upper-case (name d)) ", " (str/join " " (map pr-str more)) " dropped:"
                                 " write the direction as one keyword, " (pr-str [x (keyword (str (name d) "-" (str/join "-" (map name more))))])))
                          (contains? p :lifted)
                          (str "[:lift v] of a collection or a map is written under :inline as its printed text ([1, 2], {a: 1}), no SQL:"
                               " write a list in the query ([:in :x [1 2]]), or send the value as an argument, without :inline")
                          columns
                          (str (pr-str column) " in :columns is written as parameters, one per character, not as a column:"
                               " list the columns in the target, {:insert-into [:t [" (pr-str column) " ...]]}")
                          param
                          (str "a vector headed by " (pr-str param) " is written as a call of a function " (subs (str param) 2) ", not as the parameter:"
                               " write the parameter alone, or a list of them in :in as [:lift coll]")
                          clause
                          (str "a statement of " clause " and " before " is written " before " first, which PostgreSQL reads otherwise or not at all:"
                               " write the query inside the write, {:insert-into [[:t [:a]] {:select [:a] ...}]}")
                          (string? column)
                          (str (pr-str column) " is written as another name, " (first (syntax/formatted-name column nil false)) ": HoneySQL writes no key of a map as the column")
                          column
                          (str (pr-str column) " is written " (name column) ", a word PostgreSQL reads as a keyword, not as a column:"
                                " write it as a string, " (pr-str (name column)) ", which HoneySQL quotes (in :columns, list the columns in the target, {:insert-into [:t [" (pr-str (name column)) " ...]]}), or quote names ({:format {:quoted true}})")
                          call
                          (str (pr-str [op :a :b]) " is written as a call of a function " (name op) "(a, b), which PostgreSQL has not:"
                               " HoneySQL has not registered the operator; load honey.sql.pg-ops, or register it with sql/register-op!")
                          :else
                          (str (pr-str [op :x :a :b]) " is written x " (str/upper-case (str/replace (name op) "-" " ")) " a, the " dropped
                               " past a dropped; write the list as one vector, " (pr-str [op :x [:a :b]]))))
   :unread-clause (fn [{:keys [clause did-you-mean]}]
                    (if did-you-mean
                      (str "clause " clause " is no clause HoneySQL or pgmalli knows" (error/did-you-mean (first did-you-mean)))
                      (str "clause " clause " is not read by pgmalli, so what it does is not checked; check the clause name, or check that part by hand")))
   :column-definition-list (fn [{:keys [function alias columns]}]
                             (str "the alias " alias " of " function " lists column types, which PostgreSQL takes of a function returning record"
                                  " alone (a column definition list is only allowed for functions returning record); name the columns with no type: "
                                  (pr-str [(keyword alias) {:columns columns}])))
   :missing-arg (fn [{:keys [arg]}] (str "the query takes argument " arg ", which the function's args lack; a typo in args?"))
   :undetermined-type (fn [{:keys [arg function type]}]
                        (let [x (keyword (str "?" (name arg)))]
                          (str "argument " arg " goes to " function ", which takes its type from the value sent, but every value"
                               " its other uses take is sent untyped (a keyword label) or is nil, which PostgreSQL"
                               " cannot type there (could not determine data type of parameter $n, or could not determine polymorphic type);"
                               " cast it in the query: " (if type (syntax/cast-form x type) (str "[:cast " x " <the type meant>]")))))})

(defn humanize
  "A sentence for each problem of analyze's :problems (or of query-schema's :pgmalli/problems):
   what is wrong and what to do.

   (let [{:keys [problems]} (analyze registry q)] (is (= [] problems) (humanize problems)))"
  [problems]
  (when-not (and (sequential? problems) (every? #(and (map? %) (contains? % :kind)) problems))
    (error/fail! :pgmalli.error/invalid-options
                 (str "humanize takes analyze's :problems or query-schema's :pgmalli/problems, a vector of problem maps; "
                      (cond (and (map? problems) (contains? problems :problems)) "pass (:problems result), not analyze's result"
                            (and (vector? problems) (= :=> (first problems))) "pass (:pgmalli/problems (m/properties schema)), not query-schema's schema"
                            (map? problems) "got one map; pass a vector of them"
                            :else (str "got " (pr-str (type problems)))))
                 {:given (type problems)}))
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
