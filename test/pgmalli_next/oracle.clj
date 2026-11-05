(ns pgmalli-next.oracle
  "h/analyze's :problems (check) against PostgreSQL itself: the queries of oracle/corpus.edn, mutants
   of them (a column, an alias or a table swapped, a qualifier dropped or added), and the
   queries generated-corpus builds from the catalog (each kind of column through ~25 shapes,
   each pair of kinds PostgreSQL unites through 6, each pg_catalog relation and column of it)
   are formatted with HoneySQL and EXPLAINed (then run, rolled back, for NOT NULL) on the
   database of oracle/schema.sql, and what PostgreSQL says is set beside what check reports. A mutant is drawn from a seeded
   java.util.Random, so a seed gives the same queries wherever it runs.

   Outcomes: :agree-ok, :agree-error, :false-positive (PostgreSQL takes it, check reports a
   problem: check's promise broken), :miss (PostgreSQL refuses it, check is silent: often a
   part check does not judge by design, or an opaque table), :mismatch (both refuse, for
   different reasons), :other-silent / :other-flagged (PostgreSQL refuses it for something
   check does not judge, a type or syntax), :reported-by-design (PostgreSQL runs it, check
   reports NULL that ON CONFLICT DO UPDATE sets into a NOT NULL column, which fails once a
   row conflicts), :unformatted (HoneySQL cannot write it), :timeout (it outran
   in-parallel's statement_timeout).

   Where PostgreSQL runs a case, the return of query-schema (default and {:result :one}) must
   take what next.jdbc's execute! and execute-one! returned, and the keys of row-schema's map
   must be those the builder gives the result set's columns, read with
   next.jdbc's as-unqualified-maps: a case it refuses carries
   :return-rejections; where row-schema names or types a column
   otherwise than the ResultSet's metadata, :column-mismatches (and how many it types,
   :columns-typed). arg-matrix sends fixed values as each argument beside each column of the
   schema and of a column of each type pgmalli-next.impl.types lists, whatever arg-types says of it;
   shared-args sends each argument of several uses of the corpus a value every use's own schema
   takes, and fails where the argument's schema refuses one PostgreSQL takes.

   bb oracle [seed] [mutants-per-query] prints the counts and every case but the agreeing ones;
   pgmalli-next.oracle-test holds the false positives, the return rejections, the column mismatches
   and arg-failures at none, and the columns typed near as many as they were."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [honey.sql :as sql]
            honey.sql.pg-ops
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prep]
            [next.jdbc.result-set :as rs]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.jdbc :as pjdbc]
            [pgmalli-next.impl.types :as types]
            [pgmalli-next.test-db :as test-db])
  (:import (java.util Random)
           (org.postgresql.util PSQLException)))

(def schema-sql (slurp (io/resource "pgmalli-next/oracle/schema.sql")))
(def corpus (edn/read-string (slurp (io/resource "pgmalli-next/oracle/corpus.edn"))))

(def ^:private stray-names
  "Names a mutant puts in place of an identifier, none of the schema's but n, x and a, which
   corpus queries alias with."
  ["nosuch" "n" "x" "a" "zz"])
(def ^:private row-keys #{:set :do-update-set :values})

;;; mutation

(defn- quals [q]
  (->> (tree-seq coll? seq q) (filter keyword?) (map name)
       (keep #(when (str/includes? % ".") (first (str/split % #"\.")))) set))

(defn- aliases
  "The names q gives a table, a subquery or a column: [x alias] in a clause."
  [q]
  (->> (tree-seq coll? seq q)
       (keep #(when (and (vector? %) (not (map-entry? %)) (= 2 (count %)) (simple-keyword? (second %))
                         (not (str/includes? (name (second %)) "."))
                         (or (keyword? (first %)) (map? (first %)) (vector? (first %))))
                (name (second %))))
       set))

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
  [^Random rnd {:keys [columns tables]} q]
  (let [pick #(nth (vec %) (.nextInt rnd (count %)))
        pool (into columns stray-names)
        known (-> (set pool) (into (mapcat #(str/split % #"\.") tables)) (into ["excluded" "EXCLUDED"])
                  (into (quals q)) (into (aliases q)))
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
  "The corpus' queries and, for each, up to per-query distinct mutants drawn from names
   (mutation-names): a query's mutants depend on seed and its id, and on the earlier queries only
   through a duplicate, which runs once."
  [names seed per-query]
  (let [;; a mutant that is a corpus query, or another mutant, runs once
        seen (volatile! (set (map second corpus)))]
    (vec (for [[id q opts] corpus
               :let [rnd (Random. (+ (long seed) (.hashCode (name id))))]
               c (cons {:id id :query q :opts opts}
                       (->> (repeatedly (* 4 per-query) #(mutant rnd names q)) (remove nil?) distinct
                            (remove (fn [[_ m]] (= m q))) (take per-query)
                            (map-indexed (fn [i [op m]] {:id (keyword (str (name id) "--" i)) :query m :opts opts :mutation op :base id}))))
               :when (or (not (:mutation c)) (when-not (@seen (:query c)) (vswap! seen conj (:query c))))]
           c))))

;;; the catalog

(def ^:private unqualified {:builder-fn rs/as-unqualified-maps})

(defn- schemas
  "The oracle schema's schemas: all but PostgreSQL's own and the argument matrix's."
  [conn]
  (mapv :nspname (jdbc/execute! conn ["SELECT nspname FROM pg_namespace WHERE nspname !~ '^(pg_|information_schema$|matrix$)' ORDER BY 1"] unqualified)))

(defn registry
  "The registry of db's schemas, and of the schemas extra."
  [db & extra]
  (let [ss (with-open [conn (jdbc/get-connection (test-db/datasource db))] (schemas conn))]
    (pgmalli/registry (apply test-db/generated {:db db} (concat ss extra)))))

(defn- quoted-name?
  "Whether a name is PostgreSQL's only when quoted."
  [n]
  (not (re-matches #"[a-z_][a-z0-9_]*" n)))

(defn- relations
  "Each column of the oracle schema's tables, views and partitioned tables (no partition: the
   registry lists none, docs/queries.md): :sch :tbl :relkind :col, its type as declared
   (:declared, with its typmod) and by name (:typname :typschema), :notnull, :typtype, :checks
   (the definitions of its CHECKs, NOT VALID ones too, its own or its domain's), :labels (its
   enum's or its domain's enum's) and :lists (the values each valid CHECK of it alone that is a
   list allows)."
  [conn]
  (let [rows (jdbc/execute! conn ["SELECT n.nspname AS sch, c.relname AS tbl, c.relkind::text AS relkind, a.attname AS col,
                                          format_type(a.atttypid, a.atttypmod) AS declared, t.typname, tn.nspname AS typschema,
                                          a.attnotnull AS notnull, t.typtype::text AS typtype,
                                          (SELECT array_agg(e.enumlabel::text) FROM pg_enum e WHERE e.enumtypid = coalesce(nullif(t.typbasetype, 0), t.oid)) AS labels,
                                          ARRAY(SELECT pg_get_constraintdef(k.oid) || CASE WHEN k.convalidated THEN '' ELSE ' NOT VALID' END
                                                FROM pg_constraint k
                                                WHERE k.contype = 'c' AND ((k.conrelid = c.oid AND a.attnum = ANY (k.conkey)) OR k.contypid = t.oid)
                                                ORDER BY 1) AS checks,
                                          ARRAY(SELECT pg_get_constraintdef(k.oid) FROM pg_constraint k
                                                WHERE k.contype = 'c' AND k.convalidated
                                                  AND ((k.conrelid = c.oid AND k.conkey = ARRAY[a.attnum]) OR k.contypid = t.oid)) AS alone
                                   FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid JOIN pg_namespace n ON n.oid = c.relnamespace
                                   JOIN pg_type t ON t.oid = a.atttypid JOIN pg_namespace tn ON tn.oid = t.typnamespace
                                   WHERE n.nspname = ANY (?) AND c.relkind IN ('r', 'v', 'p') AND NOT c.relispartition AND a.attnum > 0 AND NOT a.attisdropped
                                   ORDER BY 1, 2, a.attnum"
                                  (into-array String (schemas conn))] unqualified)
        arr vec
        list-of (fn [def] (when-let [[_ items] (re-find #"ARRAY\[(.*)\]" def)]
                            (vec (for [[_ s n] (re-seq #"'([^']*)'|(-?\d+(?:\.\d+)?)" items)] (if s s (bigdec n))))))]
    (vec (for [r rows]
           (-> r
               (assoc :labels (arr (:labels r)) :checks (arr (:checks r)))
               (assoc :lists (vec (for [d (arr (:alone r))
                                        :when (and (str/includes? d "= ANY (") (not (re-find #" (OR|AND) " d)))]
                                    (list-of d))))
               (dissoc :alone))))))

(defn- relation-name
  "A relation as a query names it, schema.table; the schema left out for public."
  [sch tbl]
  (if (= "public" sch) tbl (str sch "." tbl)))

(defn mutation-names
  "{:columns :tables}: what a mutant swaps an identifier for, from the catalog: the columns and
   relations of the oracle schema but those whose names need quoting and kinds, the argument
   matrix's table of further types (left out, so it does not change the mutants a seed draws)."
  [db]
  (let [rows (with-open [conn (jdbc/get-connection (test-db/datasource db))] (relations conn))
        rows (remove #(or (= "kinds" (:tbl %)) (some quoted-name? ((juxt :sch :tbl :col) %))) rows)]
    {:columns (vec (sort (distinct (map :col rows))))
     :tables (vec (distinct (map #(relation-name (:sch %) (:tbl %)) rows)))}))

(defn- listed
  "{[relation column-name] #{labels}}: the labels (as strings) each column of the oracle schema
   of an enum allows, its relation named as a query names it."
  [relations]
  (reduce (fn [m {:keys [sch tbl col labels]}]
            (cond-> m (seq labels) (update [(relation-name sch tbl) col] (fnil conj #{}) (set (map str labels)))))
          {} relations))

;;; PostgreSQL's verdict and check's

(defn refused
  "e, an SQLException of a statement, when PostgreSQL refuses the statement for its data, its
   constraints, its text or the objects it names; throws it where the server or the connection
   failed it whatever it says (SQLSTATE class 08, 25, 40, 53, 57, 58, or none): a statement
   timed out (57014), a deadlock or a lost connection says nothing of the statement. XX000 is
   the class of an error PostgreSQL raises without one, for a statement too (a NUMA function
   where the platform has none)."
  [^java.sql.SQLException e]
  (let [c (some-> (.getSQLState e) (subs 0 2))]
    (if (and c (not (#{"08" "25" "40" "53" "57" "58"} c))) e (throw e))))

(defn- pg-kind [state message]
  (case state
    "42P01" :unknown-table
    "42703" :unknown-column
    "42702" :ambiguous-column
    "23502" :not-null-violation
    "428C9" :read-only-column
    (if (and (= "22P02" state) (str/includes? message "enum")) :enum-literal :other)))

(defn- uncast-string
  "v, a verdict of the parameter form, as :uncast-string where the parameters' types alone make
   PostgreSQL refuse it: no operator or no assignment for them (42883, 42804) where the inline
   form plans."
  [v inline]
  (cond-> v (and (= :other (:pg v)) (#{"42883" "42804"} (:state v)) (:planned? inline)) (assoc :pg :uncast-string)))

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
      (let [f (m/form (m/schema s {:registry registry}))
            inner (if (= :maybe (first f)) (last f) s)
            alts (if (and (vector? inner) (= :or (first inner))) (remove map? (rest inner)) [inner])]
        ;; none generates (an array of no known element type): NULL, as an :any argument gets
        (some #(let [v (gen %)] (when (not= ::none v) v)) alts)))))

(defn- verdict
  "PostgreSQL's verdict on [sql & params]: :ok with the :rows it matched (selected or
   written) and the :result execute! returned, or the kind of problem check would report for it,
   or :other; :planned? where it plans. :timeout where it ran out of time, which says neither."
  [^java.sql.Connection conn [sql & params :as sqlvec]]
  (try
    (jdbc/execute! conn (into [(str "EXPLAIN " sql)] params))
    (assoc (try (let [r (jdbc/execute! conn sqlvec unqualified)]
                  {:pg :ok :rows (or (:next.jdbc/update-count (first r)) (count r)) :result r})
                (catch PSQLException e
                  (case (.getSQLState e)
                    "23502" {:pg :not-null-violation :state "23502" :message (.getMessage e)}
                    "57014" {:pg :timeout :state "57014"}
                    ;; it plans: what fails as it runs is the rows' (a division by zero)
                    (do (refused e) {:pg :ok}))))
           :planned? true)
    (catch PSQLException e
      (if (= "57014" (.getSQLState e))
        {:pg :timeout :state "57014"}
        {:pg (pg-kind (.getSQLState (refused e)) (.getMessage e)) :state (.getSQLState e) :message (.getMessage e)}))
    (finally (.rollback conn))))

;; PostgreSQL says 42P01 "missing FROM-clause entry for table x" where check says
;; {:kind :unknown-column :column :x.c}: one class, a name that resolves to nothing; and
;; 23502 for a NULL written into a NOT NULL column, given or left out
(defn- name-class [k]
  (cond (#{:unknown-table :unknown-column} k) :unknown-name
        (#{:missing-required-column :not-null-violation} k) :not-null-violation
        ;; the parameters' types alone refuse it (42883, 42804): a string's, or a literal's at a call
        (#{:uncast-string :literal-type} k) :uncast-string
        :else k))

(def ^:private enum-literal-by-design
  "Corpus ids whose :enum-literal check reports by design though the compared expression is no
   column of the schema of that enum: PostgreSQL traces the value to one through a subquery, a
   CTE or a view's alias."
  #{})

(defn- result-labels
  "The labels of the columns of sqlvec's result set, in order, as PostgreSQL describes the
   statement without running it (a row it refuses as it runs, out of every partition, says nothing
   of them); nil without one."
  [^java.sql.Connection conn sqlvec]
  (try (with-open [ps (jdbc/prepare conn sqlvec)]
         (when-let [md (.getMetaData ps)]
           (mapv #(.getColumnLabel md (int %)) (range 1 (inc (.getColumnCount md))))))
       (finally (.rollback conn))))

(defn- by-design?
  "Whether check reports problem p by design (docs/queries.md \"Stricter than PostgreSQL\"): a
   literal outside an enum's labels where PostgreSQL never evaluates it (the statement may still
   run through OR or CASE); NULL set into a NOT NULL column by an UPDATE or ON CONFLICT DO UPDATE, which fails
   once a row is touched or conflicts; a label the result set's columns (labels, a delay) have
   more than once. listed: listed of the schema; the column is one of a relation query names."
  [listed id query labels p]
  (case (:kind p)
    :duplicate-column (< 1 (count (filter #{(name (:column p))} @labels)))
    :enum-literal (or (enum-literal-by-design id)
                      (let [c (:column p)
                            named (set (map name (filter keyword? (tree-seq coll? seq query))))]
                        (and (keyword? c)
                             (some (fn [[[rel col] ls]]
                                     (and (= col (last (str/split (name c) #"\."))) (named rel) (contains? ls (set (map str (:allowed p))))))
                                   listed))))
;; a literal sent typed that PostgreSQL converts on assignment alone, which an argument there
    ;; does not take (docs/queries.md "Stricter than PostgreSQL")
    :literal-type (boolean (and (:type p) (accept/accepts? (:type p) (:sent p) :assign) (not (accept/holds? (:type p) (:sent p) :assign))))
        ;; an UPDATE's SET or ON CONFLICT DO UPDATE's, even where it touches no row or none conflicts
    :not-null-violation (contains? #{:set :do-update-set} (:clause p))
    false))

(defn- outcome [{:keys [pg]} problems by-design?]
  (let [pg (name-class pg)
        ks (set (map (comp name-class :kind) problems))]
    (cond (= :timeout pg) :timeout
          (and (= :ok pg) (empty? ks)) :agree-ok
          (and (= :ok pg) (seq problems) (every? by-design? problems)) :reported-by-design
          (= :ok pg) :false-positive
          (= :other pg) (if (empty? ks) :other-silent :other-flagged)
          (contains? ks pg) :agree-error
          (empty? ks) :miss
          :else :mismatch)))

(defn- result-keys
  "The keys next.jdbc's as-unqualified-maps gives the columns of sqlvec's result set; nil without one."
  [^java.sql.Connection conn sqlvec]
  (try (with-open [ps (jdbc/prepare conn sqlvec)]
         (when (.execute ps)
           (with-open [rs (.getResultSet ps)]
             (set (:cols (rs/as-unqualified-maps rs unqualified))))))
       (finally (.rollback conn))))

(defn- key-mismatch
  "Where the keys of row-schema's map differ from those the builder gives the result set's
   columns: a column whose key the map does not hold (:unexpected), a required key no column
   has (:missing)."
  [cols row]
  (let [entries (remove map? (rest row))
        ks (set (map first entries))
        unexpected (sort-by str (remove ks cols))
        missing (sort-by str (for [[k p] entries :when (not (and (map? p) (:optional p))) :when (not (cols k))] k))]
    (when (or (seq unexpected) (seq missing))
      {:keys (sort-by str cols) :unexpected unexpected :missing missing})))

(defn- return-rejections
  "Where the return schema of query-schema refuses what next.jdbc returned for sqlvec (rows,
   what execute! returned, and execute-one!, run and rolled back), or the keys of row-schema's
   map are not those of the result set's columns (key-mismatch, as :result :keys)."
  [registry ^java.sql.Connection conn {:keys [query]} opts arg-keys sqlvec rows]
  (vec (for [[result run] [[:many (constantly rows)]
                           [:one #(jdbc/execute-one! conn sqlvec unqualified)]
                           [:keys nil]]
             :let [opts (cond-> opts (= :one result) (assoc :result :one))
                   r (try (if run
                            (let [ret (last (m/children (m/schema (h/query-schema registry arg-keys query opts) {:registry registry})))
                                  x (try (run) (finally (.rollback conn)))]
                              (when-not (m/validate ret x)
                                {:schema (m/form ret) :value x :explain (me/humanize (m/explain ret x))}))
                            (let [row (:row (h/analyze registry query opts))]
                              (when (and (vector? row) (= :map (first row)))
                                (some-> (result-keys conn sqlvec) (key-mismatch row)))))
                          (catch Exception e {:error (ex-message e)}))]
             :when r]
         (assoc r :result result))))

;;; the columns PostgreSQL describes

(defn- domains
  "{domain base}: the type names a domain's values are described as (RowDescription carries its base type)."
  [conn]
  (into {} (map (juxt :typname :base))
        (jdbc/execute! conn ["SELECT typname, format_type(typbasetype, NULL) AS base FROM pg_type WHERE typtype = 'd'"] unqualified)))

(defn- canonical
  "A type name as pgmalli-next.impl.types names it: pgjdbc's short names (int4, varchar, _int4 for
   an array, serial for a serial column's, char for \"char\") and a domain's as its base type's; an enum's as itself."
  [domains n]
  (let [;; pgjdbc names a type of another schema quoted: "app"."prio"
        base #(let [[_ sch n] (re-matches #"(?:(\"[^\"]+\"|[^.\"]+)\.)?(.*)" %)
                    n (str/replace (cond->> n sch (re-find #"^\"?(.*?)\"?$") sch second) #"\(.*\)$" "")
                    ;; char is the one-byte "char" (character is bpchar)
                    n ({"serial" "int4" "bigserial" "int8" "smallserial" "int2" "char" "\"char\""} n n)
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
  "{:mismatches :typed}: where row-schema differs from the columns PostgreSQL returned for sqlvec
   (the ResultSet's metadata), its keys from their labels
   or an entry's :pg/type from the column's type; and how many columns it names a :pg/type of. A
   write without a result set, or a row-schema that is no map, is left out (nil); row-schema
   throwing, or the run, is a mismatch {:error}."
  [registry ^java.sql.Connection conn domains query opts sqlvec]
  (let [row (try (some-> (:row (h/analyze registry query opts)) (m/schema {:registry registry}))
                 (catch Exception e {:error (str "row-schema throws: " (ex-message e))}))
        cols (when (and (m/schema? row) (= :map (m/type row)))
               (try (with-open [ps (jdbc/prepare conn sqlvec)]
                      (when (.execute ps)
                        (let [md (.getMetaData (.getResultSet ps))]
                          (vec (for [i (range 1 (inc (.getColumnCount md)))]
                                 [(.getColumnLabel md i) (.getColumnTypeName md i)])))))
                    (catch Exception e {:error (str "the run throws: " (ex-message e))})
                    (finally (.rollback conn))))]
    (cond
      (:error row) {:typed 0 :mismatches [row]}
      (:error cols) {:typed 0 :mismatches [cols]}
      cols
      (let [entries (into {} (map (fn [[k _ s]] [(name k) (m/form s)])) (m/children row))
            labels (set (map first cols))
            typed (for [[label pg] (into {} cols)
                        :let [f (entries label)
                              ts (distinct (map #(canonical domains %) (claimed f)))]
                        :when (seq ts)]
                    {:label label :pg pg :schema f :ok? (= [(canonical domains pg)] ts)})]
        {:typed (count typed)
         :mismatches (vec (concat
                           (when (not= labels (set (keys entries)))
                             [{:labels (sort labels) :keys (sort (keys entries))}])
                           (map #(dissoc % :ok?) (remove :ok? typed))))}))))

;;; the generated corpus

(defn- representatives
  "One column of each kind the oracle schema has: by its type as declared, CHECKs, NOT NULL,
   kind of type, kind of relation, and whether its names need quoting or a schema."
  [relations]
  (->> relations
       (group-by (juxt :declared :checks :notnull :typtype :relkind
                       #(boolean (some quoted-name? ((juxt :sch :tbl :col) %))) #(= "public" (:sch %))))
       vals (map first) (sort-by (juxt :sch :tbl :col)) vec))

(defn- table-ref [{:keys [sch tbl]}] (keyword (relation-name sch tbl)))
(defn- column-ref [{:keys [col] :as c}] (keyword (str (name (table-ref c)) "." col)))
(defn- column-id [{:keys [sch tbl col]}] (str (relation-name sch tbl) "." col))

(defn- quoting
  "The options of a query over columns cs: quoted names where one needs it."
  [& cs]
  (when (some #(some quoted-name? ((juxt :sch :tbl :col) %)) cs) {:format {:quoted true}}))

(defn- column-shapes
  "[form query] of column c: cast to its type spelled four ways, read through a subquery, outer
   joins, aggregates, GROUP BY, DISTINCT, a scalar subquery, CTEs, LATERAL, set operations and
   RETURNING."
  [{:keys [col declared typname typschema] :as c}]
  (let [t (table-ref c) k (column-ref c) own (keyword col)]
    (concat
     (for [spelling (distinct [declared typname (str typschema "." typname) (str "pg_catalog." declared)])
           ;; numeric(6,2) as HoneySQL writes a type with two typmods
           :let [[_ n p s] (re-matches #"(.*)\((\d+),(\d+)\)" spelling)
                 to (if n [(keyword (str/replace n " " "-")) (parse-long p) (parse-long s)] (keyword (str/replace spelling " " "-")))]
           :when (or n (not (str/includes? spelling ",")))]
       [(str "cast:" spelling) {:select [[[:cast k to] :x]] :from [t]}])
     [[:star-sub {:select [:*] :from [[{:select [k] :from [t]} :s]]}]
      [:left-join {:select [k] :from [:groups] :left-join [t false]}]
      [:right-join {:select [k] :from [:groups] :right-join [t false]}]
      [:full-join {:select [k] :from [:groups] :full-join [t false]}]
      [:left-star {:select [:*] :from [:groups] :left-join [t false]}]
      [:max {:select [[[:max k] :x]] :from [t]}]
      [:array-agg {:select [[[:array_agg k] :x]] :from [t]}]
      [:group {:select [k [[:count :*] :n]] :from [t] :group-by [k]}]
      [:distinct {:select-distinct [k] :from [t]}]
      [:scalar-sub {:select [[{:select [k] :from [t] :limit 1} :x]]}]
      [:scalar-sub-empty {:select [[{:select [k] :from [t] :where false} :x]]}]
      [:nullif {:select [[[:nullif k k] :x]] :from [t]}]
      [:cte {:with [[:c {:select [k] :from [t]}]] :select [:*] :from [:c]}]
      [:cte-cols {:with [[[:c {:columns [:z]}] {:select [k] :from [t]}]] :select [:c.z] :from [:c]}]
      [:lateral {:select [:x.*] :from [:groups [[:lateral {:select [k] :from [t]}] :x]]}]
      [:union-null {:union-all [{:select [k] :from [t]} {:select [nil]}]}]
      [:intersect {:intersect [{:select [k] :from [t]} {:select [k] :from [t]}]}]
      [:except {:except [{:select [k] :from [t]} {:select [k] :from [t] :where false}]}]
      [:update-returning {:update t :set {own own} :where false :returning [own]}]
      [:update-returning-star {:update t :set {own own} :returning [:*]}]
      [:delete-returning {:delete-from t :where false :returning [:*]}]])))

(defn- pair-shapes
  "[form query] of columns a and b together: union, a union read through *, CASE, coalesce,
   greatest, except."
  [a b]
  (let [[ta tb ka kb] [(table-ref a) (table-ref b) (column-ref a) (column-ref b)]
        from (vec (distinct [ta tb]))]
    [[:union-all {:union-all [{:select [[ka :x]] :from [ta]} {:select [[kb :x]] :from [tb]}]}]
     [:union-star {:select [:*] :from [[{:union [{:select [[ka :x]] :from [ta]} {:select [[kb :x]] :from [tb]}]} :w]]}]
     [:case {:select [[[:case [:= 1 1] ka :else kb] :x]] :from from}]
     [:coalesce {:select [[[:coalesce ka kb] :x]] :from from}]
     [:greatest {:select [[[:greatest ka kb] :x]] :from from}]
     [:except {:except [{:select [[ka :x]] :from [ta]} {:select [[kb :x]] :from [tb]}]}]]))

(defn- catalog-shapes
  "[id query] of each pg_catalog relation of the catalog snapshot the server has: SELECT * of it
   named plain and qualified, and of each of its columns there."
  [conn]
  (let [rows (jdbc/execute! conn ["SELECT c.relname AS tbl, a.attname AS col FROM pg_class c JOIN pg_attribute a ON a.attrelid = c.oid
                                   WHERE c.relnamespace = 'pg_catalog'::regnamespace AND c.relname = ANY (?) AND a.attnum > 0 AND NOT a.attisdropped
                                   ORDER BY 1, a.attnum"
                                  (into-array String (keys (:relations @types/catalog)))] unqualified)]
    (concat (for [tbl (distinct (map :tbl rows))
                  [form t] [["star" tbl] ["star-qualified" (str "pg_catalog." tbl)]]]
              [(str "gen-catalog-" form "-" tbl) {:select [:*] :from [(keyword t)]}])
            (for [{:keys [tbl col]} rows]
              [(str "gen-catalog-" tbl "." col) {:select [(keyword col)] :from [(keyword tbl)]}]))))

(defn generated-corpus
  "[id query opts] over the oracle schema's catalog (conn, autocommit): column-shapes of a
   representative of each kind of column, pair-shapes of each unordered pair of them (a column with itself too) whose types
   PostgreSQL unites (EXPLAIN of their union, which refuses the rest up front), and catalog-shapes."
  [^java.sql.Connection conn]
  (let [reps (representatives (relations conn))
        unites? (fn [a b]
                  (let [[sql] (sql/format (second (first (pair-shapes a b))) (merge {:inline true} (:format (quoting a b))))]
                    (try (jdbc/execute! conn [(str "EXPLAIN " sql)]) true (catch PSQLException _ false))))]
    (->> (concat (for [c reps [form q] (column-shapes c)]
                   [(keyword (str "gen-" (name form) "-" (column-id c))) q (quoting c)])
                 (for [[i a] (map-indexed vector reps) b (drop i reps) :when (unites? a b) [form q] (pair-shapes a b)]
                   [(keyword (str "gen-" (name form) "-" (column-id a) "+" (column-id b))) q (quoting a b)])
                 (for [[id q] (catalog-shapes conn)] [(keyword id) q nil]))
         (reduce (fn [[seen out :as acc] [_ q :as e]] (if (seen q) acc [(conj seen q) (conj out e)])) [#{} []])
         second)))

(def formats
  "How a case is sent: its values inlined, and as HoneySQL sends them by default, parameters,
   which pgjdbc types as it does (a String as varchar)."
  {:inline {:inline true} :params {}})

(def ^:dynamic *workers*
  "How many connections run and arg-matrix send their cases over, each to its own copy of the
   database: PGMALLI_ORACLE_WORKERS, or the processors up to 4."
  (or (some-> (System/getenv "PGMALLI_ORACLE_WORKERS") parse-long)
      (min 4 (.availableProcessors (Runtime/getRuntime)))))

(defn in-parallel
  "(f conn x) for each of xs, in xs' order: x i on worker i mod *workers*, each over a
   connection (autocommit off, statement_timeout 3s) to its own copy of db, or to db where
   there is one worker. Every case rolls back what it does, so what a worker ran before does not
   change a case; only sequences move, which no outcome reads."
  [db xs f]
  (let [xs (vec xs)
        n (max 1 (min *workers* (count xs)))
        dbs (if (= 1 n) [db] (test-db/copies! db n))]
    (try
      (let [parts (mapv deref (doall (for [k (range n)]
                                       (future
                                         (with-open [conn (jdbc/get-connection (test-db/datasource (dbs k)))]
                                           (jdbc/execute! conn ["SET statement_timeout = 3000"])
                                           (.setAutoCommit conn false)
                                           (mapv #(f conn %) (take-nth n (drop k xs))))))))]
        (mapv #((parts (mod % n)) (quot % n)) (range (count xs))))
      (finally (when (< 1 n) (test-db/drop! dbs))))))

(defn run-cases
  "run over cs, the cases of cases and generated-corpus's ({:id :query :opts}, :generated? for
   the latter), on db."
  [db cs seed]
  (let [registry (registry db)
        [doms listed] (with-open [conn (jdbc/get-connection (test-db/datasource db))]
                        [(domains conn) (listed (relations conn))])]
    (into []
          cat
          (in-parallel
           db cs
           (fn [conn {:keys [id base query] :as c}]
             (let [before (volatile! nil)]
               (vec (for [[mode fmt] formats
                          :let [fmt (merge fmt (:format (:opts c)))
                                opts (assoc (:opts c) :format fmt)
                                ;; a named argument takes a value of the type arg-types gives it; one it
                                ;; types not ([:any {:pgmalli/untyped why}]) goes as NULL
                                args (into {} (for [[k s] (:args (h/analyze registry query opts))]
                                                [k (when-not (= :any (if (vector? s) (first s) s)) (generated s registry seed))]))
                                sqlvec (try (sql/format query (assoc fmt :params args)) (catch Exception e e))]]
                      (cond
                        (instance? Exception sqlvec) (assoc c :format mode :outcome :unformatted :format-error (ex-message sqlvec))
                        (= sqlvec (:sqlvec @before)) (assoc (:result @before) :format mode)
                        :else
                        (let [{:keys [result] :as v} (cond-> (verdict conn sqlvec) (= :params mode) (uncast-string (:verdict @before)))
                              problems (vec (remove #(= :unread-clause (:kind %)) (:problems (h/analyze registry query opts))))
                              returned (when result (return-rejections registry conn c opts (vec (keys args)) sqlvec result))
                              {described :mismatches typed :typed} (when result (column-mismatches registry conn doms query opts sqlvec))
                              r (merge c (dissoc v :result)
                                       {:return-rejections returned :column-mismatches described :columns-typed typed :format mode :sql (first sqlvec) :params (vec (rest sqlvec))
                                        :problems problems :outcome (outcome v problems (partial by-design? listed (or base id) query (delay (result-labels conn sqlvec))))})]
                          (vreset! before {:sqlvec sqlvec :verdict v :result r})
                          r))))))))))

(defn run
  "Every case's :sql, PostgreSQL's verdict, check's :problems (:unread-clause aside: PostgreSQL
   has none) and :outcome, on db (a pgmalli :db map of a database oracle/schema.sql is loaded
   into), once per formats entry (:format): the corpus' queries with per-query mutants each
   drawn with seed, and generated-corpus. A format that writes the SQL and parameters of the one
   before it repeats its result. A query the HoneySQL on the classpath cannot write is
   :unformatted."
  [db seed per-query]
  (run-cases db
             (concat (cases (mutation-names db) seed per-query)
                     (for [[id q opts] (with-open [conn (jdbc/get-connection (test-db/datasource db))] (generated-corpus conn))]
                       {:id id :query q :opts opts :generated? true}))
             seed))

;;; the argument matrix

(def values
  "A value of each class that is no array: accept/values', and others an application may hand
   to setObject."
  (merge accept/values
         {java.time.ZonedDateTime (java.time.ZonedDateTime/of 2026 1 1 0 0 0 0 java.time.ZoneOffset/UTC)
          java.time.Duration (java.time.Duration/ofSeconds 1) java.time.Period (java.time.Period/ofDays 1)
          java.sql.Date (java.sql.Date/valueOf "2026-01-01") java.sql.Time (java.sql.Time/valueOf "01:00:00")
          java.util.Date (java.util.Date. 0)
          java.sql.Timestamp (java.sql.Timestamp/valueOf "2026-01-01 00:00:00")
          clojure.lang.PersistentArrayMap {"a" "b"}
          org.postgresql.util.PGobject (doto (org.postgresql.util.PGobject.) (.setType "json") (.setValue "{}"))}))

(def boxed
  "The boxed class of each primitive one."
  {Long/TYPE Long Integer/TYPE Integer Short/TYPE Short Byte/TYPE Byte Double/TYPE Double Float/TYPE Float
   Boolean/TYPE Boolean Character/TYPE Character})

(defn value
  "A value of class c: of values, or an array of one element; throws for another class, (accept-test
   holds accept/values to accept/classes)."
  [^Class c]
  (if (.isArray c)
    (let [e (.getComponentType c)]
      (doto (java.lang.reflect.Array/newInstance e 1) (java.lang.reflect.Array/set 0 (value (get boxed e e)))))
    (or (values c) (throw (ex-info "no value of the class" {:class c})))))

(defn- matrix-values
  "What each argument is sent: a value of each class the one sending sends and of the others of
   values, nil, numbers at the integer types' bounds and a fraction, an empty string, a number as
   a string, and each label of the schema's enums and CHECK lists, alone, as a keyword and in a
   String[]."
  [labels]
  (vec (concat (map (comp value first) accept/class-types)
               (vals (apply dissoc values (map first accept/class-types)))
               [nil 0 1.5 1.5M 2147483648 -2147483649 Long/MAX_VALUE Long/MIN_VALUE 9223372036854775808N "" "1"]
               labels
               (map keyword (filter string? labels))
               (map #(into-array String [%]) labels))))

(def ^:private samples
  "A value as text of each type of pgmalli-next.impl.types without :literals, for the row of matrix.types."
  {"smallint" "1" "integer" "1" "bigint" "1" "oid" "1" "numeric" "1.5" "real" "1.5" "double precision" "1.5"
   "text" "a" "character varying" "a" "character" "a" "name" "a" "\"char\"" "a" "citext" "a"
   "bit" "1" "bit varying" "101" "boolean" "true" "uuid" "00000000-0000-0000-0000-000000000001" "bytea" "\\x01"
   "date" "2026-01-01" "time without time zone" "01:00" "time with time zone" "01:00+00"
   "timestamp without time zone" "2026-01-01 00:00" "timestamp with time zone" "2026-01-01 00:00+00"
   "interval" "1 day" "json" "{}" "jsonb" "{}"})

(def ^:private typmods
  "[name type sample]: the typmods a column bounds its values with."
  [["numeric_10_2" "numeric(10,2)" "1.5"] ["varchar_5" "character varying(5)" "a"] ["char_3" "character(3)" "a"]
   ["timestamp_3" "timestamp(3) without time zone" "2026-01-01 00:00"] ["bit_4" "bit(4)" "1010"]])

(defn matrix-types
  "[name type sample] of each type of pgmalli-next.impl.types whose extension, if any, available?
   holds, and each of typmods: the columns of matrix.types (matrix-schema!) are of these."
  [available?]
  (concat (for [[t e] (sort-by key types/types)
                :when (or (nil? (:extension e)) (available? (:extension e)))]
            [(str/replace (str/replace t #"[^a-z0-9]+" "_") #"^_|_$" "")
             t
             (or (samples t) (first (:literals e)) (throw (ex-info "no sample for a type" {:type t})))])
          typmods))

(defn matrix-schema!
  "The schema matrix: its table types holds, for each of matrix-types (an extension's where the
   server has it), c_<name> of the type, a_<name> an array of it, d_<name> of a domain over it
   CHECK (VALUE IS NOT NULL), da_<name> an array of the domain; one row of a sample of each."
  [conn]
  (let [available (set (map :name (jdbc/execute! conn ["SELECT name FROM pg_available_extensions"] unqualified)))
        exts (filter available (distinct (keep :extension (vals types/types))))
        _ (jdbc/execute! conn ["DROP SCHEMA IF EXISTS matrix CASCADE; CREATE SCHEMA matrix; CREATE TABLE matrix.types (id serial PRIMARY KEY); INSERT INTO matrix.types DEFAULT VALUES"])
        _ (doseq [e exts] (jdbc/execute! conn [(str "CREATE EXTENSION IF NOT EXISTS \"" e "\" SCHEMA matrix")]))
        ;; an extension the oracle schema created already stays in its schema
        installed (into {} (map (juxt :extname :nspname))
                        (jdbc/execute! conn ["SELECT extname, nspname FROM pg_extension JOIN pg_namespace n ON n.oid = extnamespace"] unqualified))
        lit test-db/sql-literal
        ts (for [[n t v] (matrix-types available)
                 :let [ext (:extension (types/types t))]]
             [n (cond->> t ext (str (installed ext) ".")) v])]
    (doseq [[n t v] ts :let [d (str "matrix.d_" n)]]
      (jdbc/execute! conn [(str "CREATE DOMAIN " d " AS " t " CHECK (VALUE IS NOT NULL);"
                                "ALTER TABLE matrix.types ADD c_" n " " t ", ADD a_" n " " t "[], ADD d_" n " " d " DEFAULT " (lit v) "::text::" d ", ADD da_" n " " d "[];"
                                "UPDATE matrix.types SET c_" n " = " (lit v) ", a_" n " = ARRAY[" (lit v) "::" t "], da_" n " = ARRAY[" (lit v) "::" d "]")]))))

(defn- matrix-columns
  "Each column of the oracle schema's tables, and of matrix.types: its type as
   pgmalli-next.impl.accept names it (a domain as its base type, [:enum name] for an enum), the type
   it is declared with, whether a write may set it, whether it is NOT NULL, the values of a
   CHECK list on it (relations), and whether its names need quoting."
  [conn]
  (let [lists (into {} (for [r (relations conn)] [[(relation-name (:sch r) (:tbl r)) (:col r)] (first (:lists r))]))]
    (vec (for [{:keys [tbl col declared base enum domain domain_array writable notnull]}
               (jdbc/execute! conn ["SELECT CASE n.nspname WHEN 'public' THEN c.relname ELSE n.nspname || '.' || c.relname END AS tbl,
                                       a.attname AS col, format_type(a.atttypid, NULL) AS declared,
                                       -- an array of a domain reads as an array of its base
                                       CASE WHEN b.typcategory = 'A' THEN (SELECT format_type(coalesce(nullif(e.typbasetype, 0), e.oid), NULL) || '[]'
                                                                           FROM pg_type e WHERE e.oid = b.typelem)
                                            ELSE format_type(b.oid, NULL) END AS base,
                                       b.typtype = 'e' AS enum, t.typtype = 'd' AS domain,
                                       b.typcategory = 'A' AND (SELECT e.typtype = 'd' FROM pg_type e WHERE e.oid = b.typelem) AS domain_array,
                                       a.attgenerated = '' AND a.attidentity <> 'a' AS writable, a.attnotnull AS notnull
                                     FROM pg_attribute a JOIN pg_class c ON c.oid = a.attrelid
                                     JOIN pg_namespace n ON n.oid = c.relnamespace JOIN pg_type t ON t.oid = a.atttypid
                                     JOIN pg_type b ON b.oid = coalesce(nullif(t.typbasetype, 0), t.oid)
                                     WHERE n.nspname = ANY (?) AND c.relkind IN ('r', 'p') AND NOT c.relispartition AND a.attnum > 0 AND NOT a.attisdropped
                                     ORDER BY 1, a.attnum" (into-array String (conj (schemas conn) "matrix"))] unqualified)]
           {:table tbl :column col :declared declared :writable writable :notnull notnull
            :type (if enum (cond-> [:enum base] domain (conj {:domain? true})) (canonical {} base))
            ;; compared, a domain's array is its own type, no array of the base's
            :compared-type (when domain_array declared)
            :list (lists [tbl col])
            :quoted? (boolean (or (some quoted-name? (str/split tbl #"\.")) (quoted-name? col)))}))))

(defn- matrix-queries
  "[context query] for each place an argument :?x goes with column c: compared (=, <>, IN, =
   ANY, in RETURNING, an element of unnest(?) joined), assigned (UPDATE SET, INSERT VALUES,
   INSERT ... SELECT, and one whose WHERE tests it for NULL), beside it (coalesce, CASE, greatest),
   cast to its type, compared and cast at once, compared and passed where PostgreSQL takes its
   type from the value alone (concat's \"any\", cardinality's anyarray), compared and passed to
   a function an extension overloads (regexp_replace); and LIMIT."
  [{:keys [table column declared writable]}]
  (let [t (keyword table) c (keyword column) where #(hash-map :select [:*] :from [t] :where %)]
    (remove nil? (concat [[:= (where [:= c :?x])] [:<> (where [:<> c :?x])] [:in (where [:in c :?x])] [:any (where [:= c [:any :?x]])]
             [:returning {:delete-from t :returning [[[:= c :?x] :v]]}]
             [:unnest {:select [:*] :from [t] :join [[[:unnest :?x] [:u {:columns [:e]}]] [:= c :u.e]]}]]
            (when writable [[:update {:update t :set {c :?x}}] [:insert {:insert-into t :values [{c :?x}]}]
                            [:insert-select {:insert-into [[t [c]] {:select [:?x]}]}]
                            ;; nil writes no row where the statement tests it for NULL, cast so that PostgreSQL types it
                            [:insert-select-tested {:insert-into [[t [c]] {:select [:?x] :where [:is-not [:cast :?x (keyword (str/replace declared " " "-"))] nil]}]}]])
            [[:coalesce {:select [[[:coalesce c :?x] :v]] :from [t]}]
             [:case {:select [[[:case [:= c nil] :?x :else c] :v]] :from [t]}]
             [:greatest {:select [[[:greatest c :?x] :v]] :from [t]}]
             [:cast {:select [[[:cast :?x (keyword (str/replace declared " " "-"))] :v]]}]
             ;; one argument of two uses, which it goes through both
             [:compared-and-cast (where [:and [:= c :?x] [:= c [:cast :?x (keyword (str/replace declared " " "-"))]]])]
             ;; compared, and where PostgreSQL takes its type from the value alone: "any", a polymorphic type
             [:compared-and-any (where [:and [:= c :?x] [:is-not [:concat :?x] nil]])]
             (when (str/ends-with? declared "[]") [:compared-and-polymorphic (where [:and [:= c :?x] [:>= [:cardinality :?x] 0]])])
             ;; compared, and passed to a function an extension overloads (citext's regexp_replace beside text's)
             (when (#{"text" "citext" "character varying"} declared) [:compared-and-overloaded (where [:and [:= c :?x] [:is-not [:regexp_replace :?x "a" "b"] nil]])])]))))

(defn- send!
  "Runs [sql & params] on conn, each parameter set as the one sending (docs/reading-rows.md, which
   pgmalli-next.test-db installs) sets it."
  [^java.sql.Connection conn [sql & params]]
  (with-open [ps (.prepareStatement conn sql)]
    (doseq [[i v] (map-indexed vector params)] (prep/set-parameter v ps (inc i)))
    (.execute ps)))

(defn- typeof
  "The type PostgreSQL gets v as, set as send! sets it: :unknown for one sent untyped, nil
   where it cannot be set."
  [^java.sql.Connection conn v]
  (try (with-open [ps (.prepareStatement conn "SELECT pg_typeof(?)::text")]
         (when (try (prep/set-parameter v ps 1) true (catch java.sql.SQLException _ false))
           (with-open [rs (.executeQuery ps)] (.next rs) (canonical {} (.getString rs 1)))))
       (catch PSQLException e
         (if (= "42P18" (.getSQLState e)) :unknown (throw e)))
       (finally (.rollback conn))))

(defn- refusal
  "PostgreSQL's refusal of sqlvec, sent as send! sends it, run and rolled back, {:state
   :message}; nil where it takes the value. A write's foreign or unique key, and NULL in another
   NOT NULL column (which an INSERT leaves out) where the argument is no NULL in a NOT NULL
   column, are the rows', not the argument's. A timeout (57014) is no refusal: {:timeout? true}."
  [^java.sql.Connection conn sqlvec column null-refused?]
  (try (send! conn sqlvec) nil
       (catch PSQLException e
         (let [state (.getSQLState e)]
           (when-not (or (#{"23503" "23505"} state)
                         (and (= "23502" state) (not null-refused?) (not= column (some-> (.getServerErrorMessage e) .getColumn))))
             (if (= "57014" state)
               {:state state :timeout? true}
               {:state (.getSQLState (refused e)) :message (first (str/split-lines (.getMessage e)))}))))
       (finally (.rollback conn))))

(def ^:private compared-contexts #{:= :<> :in :any :returning :unnest :compared-and-cast :compared-and-any :compared-and-polymorphic :compared-and-overloaded})

(defn- written-refuses
  "(f opts table column context v): whether the schema of the row a write of context (:update,
   :insert) carries, set-schema's or values-schema's under opts, refuses v in column
   (another column's entry aside: the write leaves it out), keyed by the column's name as the
   schema takes it (a string where HoneySQL would not write the keyword to it); nil for another
   context."
  [registry]
  (let [schema (memoize (fn [opts table context]
                          (let [[a b] (str/split table #"\.")
                                n (if b (keyword (str "pg." a) b) (keyword "pg.public" a))
                                f (if (= :update context) h/set-schema h/values-schema)]
                            (m/schema (f registry n opts) {:registry registry}))))]
    (fn [opts table column context v]
      (when (#{:update :insert} context)
        (let [s (schema opts table context)
              extra? (fn [k] (some #(= ::m/extra-key (:type %)) (:errors (m/explain s {k v}))))
              k (if (extra? (keyword column)) column (keyword column))
              row {k v}]
          (when-not (m/validate s row)
            (some #(= k (first (:in %))) (:errors (m/explain s row)))))))))

(defn- sent-failures
  "Where pgjdbc/sent-type names a type other than pg_typeof's of the value sent so (typeof):
   :sent failures, of vs."
  [^java.sql.Connection conn vs]
  (vec (for [v vs
             :let [sent (pjdbc/sent-type v {})]
             :when sent
             :let [pg (typeof conn v)]
             :when (not= sent pg)]
         {:kind :sent :value v :class (some-> v class .getName) :sent sent :pg pg})))

(def ^:private type-refusals
  "The SQLSTATEs of PostgreSQL refusing a parameter's type: no operator or function, a type
   mismatch, no coercion, ambiguity. A type's input function refusing an untyped value's text
   (tsvector's and ltree's 42601, regclass's 42602) is the value's, which pgmalli does not read."
  #{"42883" "42804" "42846" "42725"})

(defn- own-refused?
  "Whether PostgreSQL refuses the type of place's column where the argument goes, its column in
   the argument's place (path <> path, of a type with = and no <>): no argument is taken there."
  [conn {:keys [context query column quoted?]}]
  (let [c (keyword column)]
    (boolean (when-let [own (case context (:= :<> :returning) c :in [c] nil)]
               (type-refusals (:state (refusal conn (sql/format (walk/postwalk-replace {:?x own} query) (if quoted? {:quoted true} {}))
                                               column false)))))))

(defn- described?
  "Whether PostgreSQL parses sqlvec's statement, its parameters untyped: then a refusal of a value
   sent untyped is its text's, which a type's input function refuses (regproc's 42883), not its
   type's."
  [^java.sql.Connection conn [sql]]
  (try (with-open [ps (.prepareStatement conn sql)] (.getParameterMetaData ps) true)
       (catch PSQLException _ false)
       (finally (.rollback conn))))

(defn arg-matrix
  "Each argument of the queries of matrix-queries, over every column of the oracle schema and
   of matrix.types (matrix-schema!, which it creates first), sent each of matrix-values as
   parameters as the one sending sends them. :refused: PostgreSQL takes the value, the argument's schema from
   arg-types refuses it, and the stricter rule of docs/queries.md does not explain it: a class
   sent as a type the column does not hold as its own (accept/holds?; under a cast, one
   PostgreSQL does not cast, accept/accepts?), a comparison with NULL. :written: as :refused, the schema of the row an UPDATE's :set or an INSERT's
   :values carries (set-schema, values-schema). :rule: holds? (accepts? under a cast) says
   PostgreSQL takes a value there, and PostgreSQL refuses its type (type-refusals).
   :untyped: arg-types throws or gives the argument no schema (:schema says which). :timeout:
   PostgreSQL ran out of time, which says neither. :sent: sent-failures."
  [db]
  (let [[cols labels] (with-open [conn (jdbc/get-connection (test-db/datasource db))]
                        (matrix-schema! conn)
                        (let [cols (matrix-columns conn)]
                          [cols (distinct (concat (map :enumlabel (jdbc/execute! conn ["SELECT enumlabel FROM pg_enum ORDER BY oid"] unqualified))
                                                  (filter string? (mapcat :list cols))))]))
        registry (registry db "matrix")
        all-values (matrix-values labels)
        written-refuses (written-refuses registry)
        fmt #(if % {:quoted true} {})
        ;; one place per query, in the order of its text, so that a run repeats
        places (->> (concat (for [col cols [context q] (matrix-queries col)] (assoc col :context context :query q))
                            [{:context :limit :type "bigint" :query {:select [:*] :from [:users] :limit :?x}}
                             {:context :truth-test :type "boolean" :query {:select [:*] :from [:users] :where [:is-not :?x true]}}])
                    (into (sorted-map) (map (juxt (comp pr-str :query) identity)))
                    vals)]
    (into (with-open [conn (jdbc/get-connection (test-db/datasource db))]
            (.setAutoCommit conn false)
            (sent-failures conn all-values))
          cat
          (in-parallel
           db places
           (fn [conn {:keys [context query table column notnull compared-type quoted?] t :type :as place}]
             (let [opts {:format (fmt quoted?)}
                   s (try (or (some-> (:x (:args (h/analyze registry query opts))) (m/schema {:registry registry}))
                              "arg-types gives :x no schema")
                          (catch Exception e (str "arg-types throws: " (ex-message e))))
                   failure #(merge (select-keys place [:table :column :context :type]) %)]
               (if (string? s)
                 [(failure {:kind :untyped :sql (pr-str query) :schema s})]
                 (vec (for [v all-values
                            :let [;; IN takes a collection, which sql/format expands
                                  arg (if (= :in context) [v] v)
                                  sent (pjdbc/sent-type v opts)
                                  cast? (= :cast context)
                                  takes? (and sent (or (not= :unknown sent) (accept/reads? t (class v) (if cast? :cast :assign))) (if cast?
                                                     (accept/accepts? t sent :cast)
                                                     ;; an array assigned to an array column: element by element, as array-type reads it
                                                     (let [assign? (#{:update :insert :insert-select :insert-select-tested :limit} context)
                                                           elements? (and assign? (string? t) (types/element t) (string? sent) (types/element sent))]
                                                       (accept/holds? (cond elements? (types/element t) (and compared-type (not assign?)) compared-type :else t)
                                                                      (if (or (#{:any :unnest} context) elements?) (types/element sent) sent)
                                                                      (cond assign? :assign (#{:coalesce :case :greatest} context) :common :else :compare)))))
                                  refused? (not (try (m/validate s arg) (catch Exception _ false)))
                                  row-refused? (written-refuses opts table column context v)
                                  ;; NULL in a NOT NULL column fails once a row is written; the table may have none
                                  null-refused? (and (nil? v) notnull (#{:update :insert :insert-select} context))
                                  ;; a keyword in a written row is a column to HoneySQL, a vector a row of parameters
                                  honeysql? (or (keyword? v) (and (vector? v) (not (keyword? (first v)))))
                                  ;; sent untyped, or nil, where PostgreSQL takes the type from the value
                                  untyped-there? (and (#{:compared-and-any :compared-and-polymorphic} context) (or (nil? v) (= :unknown sent)))
                                  explained? (or (and (some? v) (not takes?))
                                                 untyped-there?
                                                 (and row-refused? (not refused?) honeysql?)
                                                 (and (compared-contexts context) (nil? v))
                                                 null-refused?)
                                  ;; accept-test holds accepts? (a cast) to PostgreSQL, of the version it runs
                                  rule? (and takes? (not cast?) (not untyped-there?))]
                            :when (or rule? (and (or refused? row-refused?) (not explained?)))
                            :let [sqlvec (sql/format query (assoc (fmt quoted?) :params {:x arg}))
                                  pg (refusal conn sqlvec column null-refused?)
                                  taken? (and (nil? pg) (not explained?))
                                  kind (cond (:timeout? pg) :timeout
                                             (and refused? taken?) :refused
                                             (and row-refused? taken?) :written
                                             (and rule? (type-refusals (:state pg)) (not (own-refused? conn place))
                                                  (not (and (= :unknown sent) (described? conn sqlvec)))) :rule)]
                            :when kind]
                        (failure {:kind kind :sql (first sqlvec) :value v :class (some-> v class .getName) :sent sent
                                  :schema (if (= :written kind) (str (if (= :update context) "set-schema" "values-schema") " refuses it") (m/form s))
                                  :pg (or pg :ok)}))))))))))

(defn- each-use
  "q with each place of each argument its own argument, :?k__i of the i-th, and {k [k__i ...]}
   of the arguments of several uses."
  [q]
  (let [n (atom {})
        own (fn [k] (let [i (get (swap! n update k (fnil inc -1)) k)] (keyword (str (name k) "__" i))))
        q' (walk/prewalk (fn [x] (cond (and (keyword? x) (str/starts-with? (name x) "?") (nil? (namespace x)))
                                       (keyword (str "?" (name (own (keyword (subs (name x) 1))))))
                                       (and (vector? x) (= :param (first x)) (keyword? (second x))) [:param (own (second x))]
                                       :else x))
                         q)]
    [q' (into {} (for [[k c] @n :when (pos? c)] [k (mapv #(keyword (str (name k) "__" %)) (range (inc c)))]))]))

(defn shared-args
  "Each argument of several uses in a corpus query, sent each of matrix-values but nil, and a
   PGobject of interval and of inet: :shared where every use's own schema (each-use's) takes the
   value, the argument's schema refuses it, and PostgreSQL takes it. nil is taken only where every
   use takes it, or the query tests it for NULL."
  [db]
  (let [registry (registry db)
        pg (fn [t v] (doto (org.postgresql.util.PGobject.) (.setType t) (.setValue v)))
        ;; PGobjects of types beside values' json
        vs (concat (remove nil? (matrix-values (with-open [conn (jdbc/get-connection (test-db/datasource db))]
                                                 (map :enumlabel (jdbc/execute! conn ["SELECT enumlabel FROM pg_enum ORDER BY oid"] unqualified)))))
                   [(pg "interval" "1 day") (pg "inet" "127.0.0.1")])
        ok? (fn [s v] (try (m/validate s v {:registry registry}) (catch Exception _ false)))]
    (with-open [conn (jdbc/get-connection (test-db/datasource db))]
      (.setAutoCommit conn false)
      (vec (for [[id q opts] corpus
                 :let [[q' uses] (each-use q)]
                 :when (seq uses)
                 :let [opts (merge {:schema "public"} opts)
                       args (try (:args (h/analyze registry q opts)) (catch Exception _ nil))
                       own (try (:args (h/analyze registry q' opts)) (catch Exception _ nil))]
                 :when (and args own)
                 [k ks] uses
                 :let [s (get args k)]
                 :when (and s (every? own ks))
                 v vs
                 :when (and (every? #(ok? (own %) v) ks) (not (ok? s v)))
                 :let [params (into {k v} (for [[a t] args :when (not= a k)] [a (generated t registry 42)]))
                       sqlvec (try (sql/format q (assoc (:format opts) :params params)) (catch Exception _ nil))]
                 :when (and sqlvec (nil? (refusal conn sqlvec nil false)))]
             {:kind :shared :id id :arg k :sql (first sqlvec) :value v :class (some-> v class .getName)
              :schema (m/form (m/schema s {:registry registry}))})))))

(defn arg-failures
  "arg-matrix's failures and shared-args'."
  [db]
  (into (arg-matrix db) (shared-args db)))

(defn arg-failure-text
  "An arg-failures failure as bb oracle and pgmalli-next.oracle-test print it."
  [{:keys [kind table column context type sql value sent schema pg id arg] cls :class}]
  (case kind
    :shared
    (str kind " " id " argument " arg ": every use takes it, the argument's schema refuses it, PostgreSQL takes it"
         "\n  SQL:        " sql
         "\n  value:      " (pr-str (cond-> value (some-> value class .isArray) vec)) " " cls
         "\n  arg schema: " (pr-str (walk/postwalk #(if (fn? %) 'fn %) schema)))
    :sent
    (str kind " " cls ": sent-type says " (pr-str sent) ", PostgreSQL gets " (pr-str pg)
         "\n  value: " (pr-str (cond-> value (some-> value class .isArray) vec)))
    (str kind " " table "." column " " context " (column type " (pr-str type) ")"
         "\n  SQL:        " sql
         "\n  value:      " (pr-str (cond-> value (some-> value class .isArray) vec)) " " cls " sent as " (pr-str sent)
         "\n  arg schema: " (pr-str (walk/postwalk #(if (fn? %) 'fn %) schema))
         "\n  PostgreSQL: " (pr-str pg))))

(defn -main
  "bb oracle [seed] [mutants-per-query]: the run on a throwaway PostgreSQL (docker, as the tests
   start it), its counts and every case that does not agree, exiting 1 on what
   pgmalli-next.oracle-test fails on. The seed defaults to PGMALLI_FUZZ_SEED, then 42; the
   mutants to PGMALLI_ORACLE_MUTANTS, then 8."
  [& [seed per-query]]
  (let [seed (parse-long (or seed (System/getenv "PGMALLI_FUZZ_SEED") "42"))
        per-query (parse-long (or per-query (System/getenv "PGMALLI_ORACLE_MUTANTS") "8"))]
    (test-db/with-postgres
      (fn []
        (test-db/exec-sql! schema-sql)
        (let [results (run test-db/*db* seed per-query)
              fs (arg-failures test-db/*db*)
              failed? (or (seq fs) (some (some-fn (comp seq :return-rejections) (comp seq :column-mismatches)
                                                  (comp #{:false-positive :timeout} :outcome))
                                         results))]
          (println "seed" seed "per query" per-query "cases" (count results))
          (pp/pprint (frequencies (map :outcome results)))
          (let [rs (filter (comp seq :return-rejections) results)]
            (println "return rejected" (count rs))
            (doseq [r rs]
              (println "\n" (:id r) (or (:mutation r) "") (:format r) "\n  SQL:" (:sql r) (when (seq (:params r)) (pr-str (:params r)))
                       "\n  rows:" (:rows r))
              (doseq [x (:return-rejections r)]
                (println "  " (:result x) (pr-str (dissoc x :result))))))
          (let [rs (filter (comp seq :column-mismatches) results)]
            (println "\ncolumns described otherwise" (count rs))
            (doseq [r rs]
              (println (:id r) (or (:mutation r) "") (:format r) "\n  SQL:" (:sql r) (when (seq (:params r)) (pr-str (:params r))))
              (doseq [x (:column-mismatches r)] (println "  " (pr-str x)))))
          (println "\nargument matrix failures" (count fs))
          (doseq [f fs] (println (arg-failure-text f)))
          (doseq [o [:false-positive :timeout :unformatted :mismatch :miss :other-flagged]
                  :let [rs (filter #(= o (:outcome %)) results)]
                  :when (seq rs)]
            (println "\n=====" o (count rs))
            (doseq [r rs]
              (println (:id r) (or (:mutation r) "") (:format r) "\n  SQL:" (:sql r) (when (seq (:params r)) (pr-str (:params r)))
                       "\n  PostgreSQL:" (:pg r) (:state r) (some-> (:message r) (str/replace #"\n" " | ")) (or (:format-error r) "")
                       "\n  check:" (pr-str (:problems r)))))
          (println (if failed?
                     "\nFAIL: a return rejected, a column described otherwise, an argument matrix failure, a false positive or a timeout above"
                     "\nPASS: no return rejected, column described otherwise, argument matrix failure, false positive or timeout"))
          (shutdown-agents)
          (when failed? (System/exit 1)))))))
