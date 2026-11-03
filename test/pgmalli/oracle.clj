(ns pgmalli.oracle
  "pgmalli.honeysql/check against PostgreSQL itself: the queries of oracle/corpus.edn, and
   mutants of them (a column, an alias or a table swapped, a qualifier dropped or added), are
   formatted with HoneySQL and EXPLAINed (then run, rolled back, for NOT NULL) on the database
   of oracle/schema.sql, and what PostgreSQL says is set beside what check reports. A mutant is
   drawn from a seeded java.util.Random, so a seed gives the same queries wherever it runs.

   Outcomes: :agree-ok, :agree-error, :false-positive (PostgreSQL takes it, check reports a
   problem: check's promise broken), :miss (PostgreSQL refuses it, check is silent: often a
   part check does not judge by design, code or an opaque table), :mismatch (both refuse, for
   different reasons), :other-silent / :other-flagged (PostgreSQL refuses it for something
   check does not judge, a type or syntax), :reported-by-design (PostgreSQL runs it, check
   reports a literal outside a CHECK's list, which matches no row, or NULL that ON CONFLICT DO
   UPDATE sets into a NOT NULL column, which fails once a row conflicts).

   Where PostgreSQL runs a case, the return of query-schema (default and {:result :one}) must
   take what next.jdbc's execute! and execute-one! returned, read with the default builder and,
   for a corpus query (not a mutant), with each of builders: a case it refuses carries
   :return-rejections; where row-schema, under the unqualified builder, names or types a column
   otherwise than the ResultSet's metadata, :column-mismatches. arg-matrix sends fixed values
   as each argument beside each column of the schema, whatever arg-types says of it.

   bb oracle [seed] [mutants-per-query] prints the counts and every case but the agreeing ones;
   pgmalli.oracle-test holds the false positives, the return rejections, the column mismatches
   and arg-matrix's failures at none."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [honey.sql :as sql]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [next.jdbc :as jdbc]
            next.jdbc.optional
            [next.jdbc.result-set :as rs]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.accept :as accept]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.jdbc :as pjdbc]
            [pgmalli.impl.types :as types]
            [pgmalli.test-db :as test-db])
  (:import (java.util Random)
           (org.postgresql.util PSQLException)))

(def schema-sql (slurp (io/resource "pgmalli/oracle/schema.sql")))
(def corpus (edn/read-string (slurp (io/resource "pgmalli/oracle/corpus.edn"))))

(def ^:private tables ["users" "groups" "posts" "tags" "post_tags" "active_users" "hosts"])
(def ^:private alias-ish #{"u" "g" "p" "s" "c" "x" "v" "t" "a" "b" "n" "h" "ins" "excluded" "EXCLUDED" "zz" "nosuch"})
(def ^:private row-keys #{:set :do-update-set :values})

;;; mutation

(defn- quals [q]
  (->> (tree-seq coll? seq q) (filter keyword?) (map name)
       (keep #(when (str/includes? % ".") (first (str/split % #"\.")))) set))

(defn- identifier? [known kw]
  (let [n (name kw)]
    (and (not (str/includes? n "*")) (not (str/starts-with? n "%")) (not (namespace kw))
         (every? known (str/split n #"\.")))))

(defn- candidates
  "[:val path] for an identifier keyword at path, [:key path k] for a key k of a row map at path."
  [q known]
  (letfn [(walk [x path row?]
            (cond (keyword? x) (when (identifier? known x) [[:val path]])
                  (map? x) (concat (when row? (for [k (keys x) :when (and (keyword? k) (identifier? known k))] [:key path k]))
                                   (mapcat (fn [[k v]] (walk v (conj path k) (contains? row-keys k))) x))
                  (vector? x) (mapcat (fn [i v] (walk v (conj path i) row?)) (range) x)))]
    (walk q [] false)))

(defn- mutant
  "[mutation query] drawn from q with rnd, or nil when q has no identifier to change."
  [^Random rnd columns q]
  (let [pick #(nth (vec %) (.nextInt rnd (count %)))
        pool (into columns ["nosuch" "n" "x" "a"])
        known (-> (set columns) (into tables) (into alias-ish) (into (quals q)))
        cs (vec (candidates q known))]
    (when (seq cs)
      (let [[kind path k] (pick cs)]
        (if (= :val kind)
          (let [n (name (get-in q path))
                [a b] (str/split n #"\.")
                qs (vec (into (quals q) (concat tables ["zz"])))
                [op n] (cond b (case (pick [:wrong-col :drop-qual :wrong-qual])
                                 :wrong-col [:wrong-col (str a "." (pick pool))]
                                 :drop-qual [:drop-qual b]
                                 :wrong-qual [:wrong-qual (str (pick qs) "." b)])
                             (and (some #{a} tables) (not (some #{a} columns))) [:wrong-table (pick (conj tables "nosuch"))]
                             :else (case (pick [:wrong-col :wrong-col :add-qual])
                                     :wrong-col [:wrong-col (pick pool)]
                                     :add-qual [:add-qual (str (pick qs) "." a)]))]
            [op (assoc-in q path (keyword n))])
          (let [m (get-in q path)
                swapped (-> m (dissoc k) (assoc (keyword (pick pool)) (get m k)))]
            [:wrong-key (if (seq path) (assoc-in q path swapped) swapped)]))))))

(defn cases
  "The corpus' queries and, for each, up to per-query distinct mutants drawn with seed."
  [columns seed per-query]
  (let [rnd (Random. (long seed))
        seen (volatile! #{})]
    (vec (for [[id q] corpus
               c (cons {:id id :query q}
                       (->> (repeatedly (* 4 per-query) #(mutant rnd columns q)) (remove nil?) distinct
                            (remove (fn [[_ m]] (= m q))) (take per-query)
                            (map-indexed (fn [i [op m]] {:id (keyword (str (name id) "--" i)) :query m :mutation op :base id}))))
               :when (when-not (@seen (:query c)) (vswap! seen conj (:query c)))]
           c))))

;;; PostgreSQL's verdict and check's

(defn- pg-kind [state message]
  (case state
    "42P01" :unknown-table
    "42703" :unknown-column
    "42702" :ambiguous-column
    "23502" :not-null-violation
    "428C9" :read-only-column
    (cond (and (= "22P02" state) (str/includes? message "enum")) :enum-literal
          ;; a parameter pgjdbc sent as varchar where the column's type takes none
          (and (#{"42883" "42804"} state) (str/includes? message "character varying")) :uncast-string
          :else :other)))

(defn- generated
  "A value of schema s; of an alternative that generates where one does not (an array of a
   driver's own, :pg/array); nil where none does."
  [s registry seed]
  (let [array (fn [elem]
                ;; the typed Java array pgjdbc sends of the elements' class
                (let [vs (remove nil? (mg/generate [:vector {:min 1 :max 3} elem] {:registry registry :seed seed :size 3}))]
                  (cond (every? int? vs) (long-array vs)
                        (every? string? vs) (into-array String vs)
                        (every? double? vs) (double-array vs)
                        (every? boolean? vs) (boolean-array vs)
                        :else ::none)))
        gen #(try (let [f (m/form (m/schema % {:registry registry}))]
                    (if (and (vector? f) (= :pg/array (first f))) (array (last f)) (mg/generate % {:registry registry :seed seed :size 3})))
                  (catch Exception _ ::none))
        v (gen s)]
    (if (not= ::none v)
      v
      (let [inner (if (= :maybe (first (m/form (m/schema s {:registry registry})))) (last (m/form (m/schema s {:registry registry}))) s)
            alts (if (and (vector? inner) (= :or (first inner))) (remove map? (rest inner)) [inner])]
        ;; none generates (an array of no known element type): NULL, as an :any argument gets
        (some #(let [v (gen %)] (when (not= ::none v) v)) alts)))))

(defn- verdict
  "PostgreSQL's verdict on [sql & params]: :ok with the :rows it matched (selected or
   written) and the :result execute! returned, or the kind of problem check would report for it, or :other."
  [^java.sql.Connection conn [sql & params :as sqlvec]]
  (try
    (jdbc/execute! conn (into [(str "EXPLAIN " sql)] params))
    (try (let [r (jdbc/execute! conn sqlvec)]
           {:pg :ok :rows (or (:next.jdbc/update-count (first r)) (count r)) :result r})
         (catch PSQLException e
           (case (.getSQLState e)
             "23502" {:pg :not-null-violation :state "23502" :message (.getMessage e)}
             ;; a value outside a CHECK's list, which check reports as an enum literal
             "23514" {:pg :enum-literal :state "23514" :message (.getMessage e)}
             {:pg :ok})))
    (catch PSQLException e
      {:pg (pg-kind (.getSQLState e) (.getMessage e)) :state (.getSQLState e) :message (.getMessage e)})
    (finally (.rollback conn))))

;; PostgreSQL says 42P01 "missing FROM-clause entry for table x" where check says
;; {:kind :unknown-column :column :x.c}: one class, a name that resolves to nothing; and
;; 23502 for a NULL written into a NOT NULL column, given or left out
(defn- name-class [k]
  (cond (#{:unknown-table :unknown-column} k) :unknown-name
        (#{:missing-required-column :not-null-violation} k) :not-null-violation
        :else k))

(defn- outcome [{:keys [pg]} problems]
  (let [pg (name-class pg)
        ks (set (map (comp name-class :kind) problems))]
    (cond (and (= :ok pg) (empty? ks)) :agree-ok
          ;; a comparison with a literal outside a CHECK's list is false on every row, which the
          ;; CHECK guarantees (the statement may still return rows through OR or CASE), and NULL
          ;; set into a NOT NULL column by ON CONFLICT DO UPDATE fails once a row conflicts: check
          ;; reports them by design (docs/queries.md "Stricter than PostgreSQL")
          (and (= :ok pg) (seq problems)
               (every? #(or (= :enum-literal (:kind %))
                            (= [:not-null-violation :do-update-set] ((juxt :kind :clause) %)))
                       problems))
          :reported-by-design
          (= :ok pg) :false-positive
          (= :other pg) (if (empty? ks) :other-silent :other-flagged)
          (contains? ks pg) :agree-error
          (empty? ks) :miss
          :else :mismatch)))

(def builders
  "The next.jdbc options a corpus query's rows are read with beside the default builder's, for
   query-schema's return (as :jdbc) and for execute! and execute-one!."
  {:unqualified {:builder-fn rs/as-unqualified-maps}
   :unqualified-kebab {:builder-fn rs/as-unqualified-kebab-maps}
   :modified {:builder-fn rs/as-modified-maps :label-fn str/upper-case :qualifier-fn #(str "q_" %)}
   :optional {:builder-fn next.jdbc.optional/as-maps}})

(defn- return-rejections
  "Where the return schema of query-schema refuses what next.jdbc returned for sqlvec: rows (as
   execute! returned them with the default builder), then, unless the case is a mutant, every
   builders entry; each execute! and execute-one! is run and rolled back."
  [registry ^java.sql.Connection conn {:keys [query mutation]} fmt arg-keys sqlvec rows]
  (vec (for [[builder jdbc-opts] (cons [:default {}] (when-not mutation builders))
             [result run] [[:many #(if (= :default builder) rows (jdbc/execute! conn sqlvec jdbc-opts))]
                           [:one #(jdbc/execute-one! conn sqlvec jdbc-opts)]]
             :let [opts (cond-> {:format fmt} (seq jdbc-opts) (assoc :jdbc jdbc-opts) (= :one result) (assoc :result :one))
                   r (try (let [ret (last (m/children (m/schema (h/query-schema registry arg-keys query opts) {:registry registry})))
                                x (try (run) (finally (.rollback conn)))]
                            (when-not (m/validate ret x)
                              {:schema (m/form ret) :value x :explain (me/humanize (m/explain ret x))}))
                          (catch Exception e {:error (ex-message e)}))]
             :when r]
         (assoc r :builder builder :result result))))

;;; the columns PostgreSQL describes

(def ^:private unqualified (:unqualified builders))

(defn- domains
  "{domain base}: the type names a domain's values are described as (RowDescription carries its base type)."
  [conn]
  (into {} (map (juxt :typname :base))
        (jdbc/execute! conn ["SELECT typname, format_type(typbasetype, NULL) AS base FROM pg_type WHERE typtype = 'd'"] unqualified)))

(defn- canonical
  "A type name as pgmalli.impl.types names it: pgjdbc's short names (int4, varchar, _int4 for
   an array, serial for a serial column's) and a domain's as its base type's; an enum's as itself."
  [domains n]
  (let [base #(let [n (str/replace % #"^[^.\"]+\.|\(.*\)$" "")
                    n ({"serial" "int4" "bigserial" "int8" "smallserial" "int2"} n n)
                    n (get domains n n)]
                (or (:name (types/entry n)) n))]
    (if-let [[_ e] (or (re-matches #"_(.+)" n) (re-matches #"(.+)\[\]" n))]
      (str (base e) "[]")
      (base n))))

(defn- claimed
  "The :pg/type a column's schema names, through :maybe, :or and :and; none for an untyped one."
  [f]
  (when (vector? f)
    (if-let [t (when (map? (second f)) (:pg/type (second f)))]
      [t]
      (when (#{:maybe :or :and} (first f)) (mapcat claimed (rest f))))))

(defn- column-mismatches
  "Where row-schema, under the unqualified builder, differs from the columns PostgreSQL returned
   for sqlvec (the ResultSet's metadata): its keys from their labels, or an entry's :pg/type from
   the column's type. A write without a result set, or a row-schema that is no map, is left out."
  [registry ^java.sql.Connection conn domains query fmt sqlvec]
  (let [row (try (m/schema (h/row-schema registry query {:format fmt :jdbc unqualified}) {:registry registry}) (catch Exception _ nil))]
    (when (and row (= :map (m/type row)))
      (when-let [cols (try (with-open [ps (jdbc/prepare conn sqlvec)]
                             (when (.execute ps)
                               (let [md (.getMetaData (.getResultSet ps))]
                                 (vec (for [i (range 1 (inc (.getColumnCount md)))]
                                        [(.getColumnLabel md i) (.getColumnTypeName md i)])))))
                           (catch Exception _ nil)
                           (finally (.rollback conn)))]
        (let [entries (into {} (map (fn [[k _ s]] [(name k) (m/form s)])) (m/children row))
              labels (set (map first cols))]
          (vec (concat
                (when (not= labels (set (keys entries)))
                  [{:labels (sort labels) :keys (sort (keys entries))}])
                (for [[label pg] (into {} cols)
                      :let [f (entries label)
                            ts (distinct (map #(canonical domains %) (claimed f)))]
                      :when (and (seq ts) (not= [(canonical domains pg)] ts))]
                  {:label label :pg pg :schema f}))))))))

(defn columns
  "The column names of the oracle schema, which mutants draw from: kinds, the argument matrix's
   table of further types, is left out, so it does not change the mutants a seed draws."
  [db]
  (let [ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:postgresql://" (:host db) ":" (:port db) "/" (:db db)) :user (:user db) :password (:password db)})]
    (mapv :column_name (jdbc/execute! ds ["SELECT DISTINCT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name <> 'kinds' ORDER BY 1"]
                                      {:builder-fn rs/as-unqualified-maps}))))

(def formats
  "How a case is sent: its values inlined, and as HoneySQL sends them by default, parameters,
   which pgjdbc types as it does (a String as varchar)."
  {:inline {:inline true} :params {}})

(defn run
  "Every case's :sql, PostgreSQL's verdict, check's :problems (:unread-clause aside: PostgreSQL
   has none) and :outcome, on db (a pgmalli :db map of a database oracle/schema.sql is loaded
   into), once per formats entry (:format)."
  [db seed per-query]
  (let [url (str "jdbc:postgresql://" (:host db) ":" (:port db) "/" (:db db))
        ds (jdbc/get-datasource {:jdbcUrl url :user (:user db) :password (:password db)})
        registry (pgmalli/registry (files/from-database {:db db} "public"))]
    (with-open [conn (jdbc/get-connection ds)]
      (let [columns (columns db)
            doms (domains conn)]
        (jdbc/execute! conn ["SET statement_timeout = 3000"])
        (.setAutoCommit conn false)
        (vec (for [{:keys [query] :as c} (cases columns seed per-query)
                   [mode fmt] formats
                   :let [;; a named argument takes a value of the type arg-types gives it
                         args (into {} (for [[k s] (h/arg-types registry query {:format fmt})]
                                         [k (when-not (= :any s) (generated s registry seed))]))
                         ;; a query the HoneySQL on the classpath cannot write (an older one) is left out
                         sqlvec (try (sql/format query (assoc fmt :params args)) (catch Exception _ nil))]
                   :when sqlvec
                   :let [{:keys [result] :as v} (verdict conn sqlvec)
                         problems (vec (remove #(= :unread-clause (:kind %)) (h/check registry query {:format fmt})))
                         returned (when result (return-rejections registry conn c fmt (vec (keys args)) sqlvec result))
                         described (when result (column-mismatches registry conn doms query fmt sqlvec))]]
               (merge c (dissoc v :result)
                      {:return-rejections returned :column-mismatches described :format mode :sql (first sqlvec) :params (vec (rest sqlvec)) :problems problems :outcome (outcome v problems)})))))))

;;; the argument matrix

(def ^:private boxed
  {Long/TYPE Long Integer/TYPE Integer Short/TYPE Short Byte/TYPE Byte Double/TYPE Double Float/TYPE Float Boolean/TYPE Boolean})

(defn- representative
  "A value of class c, for each class accept/class-types lists (an array of one element); throws
   for another, so a class added there gets one here."
  [^Class c]
  (if (.isArray c)
    (let [e (.getComponentType c)]
      (doto (java.lang.reflect.Array/newInstance e 1) (java.lang.reflect.Array/set 0 (representative (get boxed e e)))))
    (condp = c
      Long 1 Integer (int 1) Short (short 1) Byte (byte 1) java.math.BigDecimal 1M clojure.lang.BigInt 1N
      java.math.BigInteger (biginteger 1) Double 1.0 Float (float 1) Boolean true String "a" Character \a
      java.util.UUID (java.util.UUID/fromString "00000000-0000-0000-0000-000000000001")
      java.time.LocalDate (java.time.LocalDate/of 2026 1 1) java.time.LocalTime (java.time.LocalTime/of 1 0)
      java.time.OffsetTime (java.time.OffsetTime/of 1 0 0 0 java.time.ZoneOffset/UTC)
      java.time.LocalDateTime (java.time.LocalDateTime/of 2026 1 1 0 0)
      java.time.OffsetDateTime (java.time.OffsetDateTime/of 2026 1 1 0 0 0 0 java.time.ZoneOffset/UTC)
      java.sql.Date (java.sql.Date/valueOf "2026-01-01") java.sql.Time (java.sql.Time/valueOf "01:00:00")
      java.sql.Timestamp (java.sql.Timestamp/valueOf "2026-01-01 00:00:00"))))

(defn- matrix-values
  "What each argument is sent: a value of each class pgjdbc sends, nil, numbers at the integer
   types' bounds and a fraction, an empty string, a number as a string, and each label of the
   schema's enums and CHECK lists, alone and in a String[]."
  [labels]
  (vec (concat (map (comp representative first) accept/class-types)
               [nil 0 1.5 1.5M 2147483648 -2147483649 Long/MAX_VALUE Long/MIN_VALUE 9223372036854775808N "" "1"]
               labels
               (map #(into-array String [%]) labels))))

(defn- matrix-columns
  "Each column of the oracle schema's tables: its type as pgmalli.impl.accept names it (a domain
   as its base type, [:enum name] for an enum), the type it is declared with, whether a write
   may set it, whether it is NOT NULL, and the labels of a CHECK (col IN (...)) on it."
  [conn]
  (let [checks (group-by (juxt :tbl :col)
                         (jdbc/execute! conn ["SELECT c.relname AS tbl, a.attname AS col, pg_get_constraintdef(k.oid) AS def
                                               FROM pg_constraint k JOIN pg_class c ON c.oid = k.conrelid
                                               JOIN pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = k.conkey[1]
                                               WHERE k.contype = 'c' AND cardinality(k.conkey) = 1"] unqualified))
        list-of (fn [def] (when-let [[_ items] (re-find #"ARRAY\[(.*)\]" def)]
                            (vec (for [[_ s n] (re-seq #"'([^']*)'|(-?\d+(?:\.\d+)?)" items)] (if s s (bigdec n))))))]
    (vec (for [{:keys [tbl col declared base enum domain writable notnull]}
               (jdbc/execute! conn ["SELECT c.relname AS tbl, a.attname AS col, format_type(a.atttypid, NULL) AS declared,
                                       format_type(b.oid, NULL) AS base, b.typtype = 'e' AS enum, t.typtype = 'd' AS domain,
                                       a.attgenerated = '' AND a.attidentity <> 'a' AS writable, a.attnotnull AS notnull
                                     FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                                     JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_type t ON t.oid = a.atttypid
                                     JOIN pg_type b ON b.oid = coalesce(nullif(t.typbasetype, 0), t.oid)
                                     WHERE n.nspname = 'public' AND c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
                                     ORDER BY 1, a.attnum"] unqualified)]
           {:table tbl :column col :declared declared :writable writable :notnull notnull
            :type (if enum (cond-> [:enum base] domain (conj {:domain? true})) (canonical {} base))
            :list (some (comp list-of :def) (checks [tbl col]))}))))

(defn- matrix-queries
  "[context query] for each place an argument :?x goes with column c: compared (=, <>, IN, =
   ANY), assigned (UPDATE SET, INSERT VALUES), beside it (coalesce, CASE, greatest), cast to its
   type; and LIMIT."
  [{:keys [table column declared writable]}]
  (let [t (keyword table) c (keyword column) where #(hash-map :select [:*] :from [t] :where %)]
    (concat [[:= (where [:= c :?x])] [:<> (where [:<> c :?x])] [:in (where [:in c :?x])] [:any (where [:= c [:any :?x]])]]
            (when writable [[:update {:update t :set {c :?x}}] [:insert {:insert-into t :values [{c :?x}]}]])
            [[:coalesce {:select [[[:coalesce c :?x] :v]] :from [t]}]
             [:case {:select [[[:case [:= c nil] :?x :else c] :v]] :from [t]}]
             [:greatest {:select [[[:greatest c :?x] :v]] :from [t]}]
             [:cast {:select [[[:cast :?x (keyword (str/replace declared " " "-"))] :v]]}]])))

(defn- refusal
  "PostgreSQL's refusal of sqlvec, run and rolled back, {:state :message}; nil where it takes the
   value. A write's foreign or unique key, and NULL in another NOT NULL column (which an INSERT
   leaves out) where the argument is no NULL in a NOT NULL column, are the rows', not the argument's."
  [^java.sql.Connection conn sqlvec column null-refused?]
  (try (jdbc/execute! conn sqlvec) nil
       (catch PSQLException e
         (let [state (.getSQLState e)]
           (when-not (or (#{"23503" "23505"} state)
                         (and (= "23502" state) (not null-refused?) (not= column (some-> (.getServerErrorMessage e) .getColumn))))
             {:state state :message (first (str/split-lines (.getMessage e)))})))
       (finally (.rollback conn))))

(def ^:private compared-contexts #{:= :<> :in :any})

(defn- outside?
  "Whether v (an array's every element) is outside a CHECK's list."
  [list v]
  (let [in? #(some (fn [l] (if (and (number? l) (number? %)) (== l %) (= l %))) list)]
    (if (and v (.isArray (class v))) (not-any? in? (seq v)) (not (in? v)))))

(defn arg-matrix
  "Each argument of the queries of matrix-queries, over every column of the oracle schema, sent
   each of matrix-values as parameters. :refused: PostgreSQL takes the value, the argument's
   schema from arg-types refuses it, and the stricter rule of docs/queries.md does not explain
   it: a class sent as a type the column does not hold as its own (accept/holds?; under a cast,
   one PostgreSQL does not cast, accept/accepts?), a comparison with a value outside a CHECK's
   list or with NULL. :rule: holds? (accepts? under a cast) says PostgreSQL takes a value there,
   and PostgreSQL refuses its type (SQLSTATE class 42)."
  [db]
  (let [ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:postgresql://" (:host db) ":" (:port db) "/" (:db db)) :user (:user db) :password (:password db)})
        registry (pgmalli/registry (files/from-database {:db db} "public"))]
    (with-open [conn (jdbc/get-connection ds)]
      (jdbc/execute! conn ["SET statement_timeout = 3000"])
      (.setAutoCommit conn false)
      (let [cols (matrix-columns conn)
            labels (distinct (concat (map :enumlabel (jdbc/execute! conn ["SELECT enumlabel FROM pg_enum ORDER BY oid"] unqualified))
                                     (filter string? (mapcat :list cols))))
            values (matrix-values labels)
            places (vals (into (sorted-map) (map (juxt (comp pr-str :query) identity)) (concat (for [col cols [context q] (matrix-queries col)]
                                       (assoc col :context context :query q))
                                     [{:context :limit :type "bigint" :query {:select [:*] :from [:users] :limit :?x}}])))]
        (vec (for [{:keys [context query column list notnull] t :type :as place} places
                   :let [s (try (some-> (:x (h/arg-types registry query)) (m/schema {:registry registry})) (catch Exception _ nil))]
                   :when s
                   v values
                   :let [;; IN takes a collection, which sql/format expands
                         arg (if (= :in context) [v] v)
                         sent (pjdbc/sent-type v {})
                         cast? (= :cast context)
                         takes? (and sent (if cast?
                                            (accept/accepts? t sent :cast)
                                            (accept/holds? t (if (= :any context) (accept/element sent) sent) (if (#{:update :insert} context) :assign :compare))))
                         refused? (not (try (m/validate s arg) (catch Exception _ false)))
                         ;; NULL in a NOT NULL column fails once a row is written; the table may have none
                         null-refused? (and (nil? v) notnull (#{:update :insert} context))
                         explained? (or (and (some? v) (not takes?))
                                        (and (compared-contexts context) (or (nil? v) (and list (outside? list v))))
                                        null-refused?)
                         ;; accept-test holds accepts? (a cast) to PostgreSQL, of the version it runs
                         rule? (and takes? (not cast?))]
                   :when (or rule? (and refused? (not explained?)))
                   :let [sqlvec (sql/format query {:params {:x arg}})
                         pg (refusal conn sqlvec column null-refused?)
                         kind (cond (and refused? (not explained?) (nil? pg)) :refused
                                    (and rule? (some-> (:state pg) (str/starts-with? "42"))) :rule)]
                   :when kind]
               (merge (select-keys place [:table :column :context :type])
                      {:kind kind :sql (first sqlvec) :value v :class (some-> v class .getName) :sent sent
                       :schema (m/form s) :pg (or pg :ok)})))))))

(defn arg-failure-text
  "An arg-matrix failure as bb oracle and pgmalli.oracle-test print it."
  [{:keys [kind table column context type sql value sent schema pg] cls :class}]
  (str kind " " table "." column " " context " (column type " (pr-str type) ")"
       "\n  SQL:        " sql
       "\n  value:      " (pr-str (cond-> value (some-> value class .isArray) vec)) " " cls " sent as " (pr-str sent)
       "\n  arg schema: " (pr-str (walk/postwalk #(if (fn? %) 'fn %) schema))
       "\n  PostgreSQL: " (pr-str pg)))

(defn -main
  "bb oracle [seed] [mutants-per-query]: the run on a throwaway PostgreSQL (docker, as the tests
   start it), its counts and every case that does not agree."
  [& [seed per-query]]
  (let [seed (parse-long (or seed "42"))
        per-query (parse-long (or per-query "8"))]
    (test-db/with-postgres
      (fn []
        (test-db/exec-sql! schema-sql)
        (let [results (run test-db/*db* seed per-query)]
          (println "seed" seed "per query" per-query "cases" (count results))
          (pp/pprint (frequencies (map :outcome results)))
          (let [rs (filter (comp seq :return-rejections) results)]
            (println "return rejected" (count rs))
            (doseq [r rs]
              (println "\n" (:id r) (or (:mutation r) "") (:format r) "\n  SQL:" (:sql r) (when (seq (:params r)) (pr-str (:params r)))
                       "\n  rows:" (:rows r))
              (doseq [x (:return-rejections r)]
                (println "  " (:builder x) (:result x) (or (:error x) (str (pr-str (:schema x)) "\n    value: " (pr-str (:value x)) "\n    explain: " (pr-str (:explain x))))))))
          (let [rs (filter (comp seq :column-mismatches) results)]
            (println "\ncolumns described otherwise" (count rs))
            (doseq [r rs]
              (println (:id r) (or (:mutation r) "") (:format r) "\n  SQL:" (:sql r) (when (seq (:params r)) (pr-str (:params r))))
              (doseq [x (:column-mismatches r)] (println "  " (pr-str x)))))
          (let [fs (arg-matrix test-db/*db*)]
            (println "\nargument matrix failures" (count fs))
            (doseq [f fs] (println (arg-failure-text f))))
          (doseq [o [:false-positive :mismatch :miss :other-flagged]
                  :let [rs (filter #(= o (:outcome %)) results)]
                  :when (seq rs)]
            (println "\n=====" o (count rs))
            (doseq [r rs]
              (println (:id r) (or (:mutation r) "") (:format r) "\n  SQL:" (:sql r) (when (seq (:params r)) (pr-str (:params r)))
                       "\n  PostgreSQL:" (:pg r) (:state r) (some-> (:message r) (str/replace #"\n" " | "))
                       "\n  check:" (pr-str (:problems r))))))))
    (shutdown-agents)))
