(ns pgmalli.expression-types-test
  "The types and names h/row-schema gives a select expression, against what PostgreSQL gives:
   coalesce, greatest, least, CASE, nullif and UNION over pairs of the columns of every type (of
   the type, an array, a domain, an array of the domain), the aggregates over each, every
   function and operator of pgmalli's catalog snapshot over columns of its argument types (and
   NULL or a string literal, unknowns), SQL words, literals and parameters. pgmalli may type an
   expression :any; where it names a type, PostgreSQL names it too, and it types about as many
   as it did. And arg-types takes what PostgreSQL takes for an argument of a call, or of two
   comparisons. JVM only (next.jdbc), so bb test does not list it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            next.jdbc.result-set
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.accept :as accept]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.jdbc :as pjdbc]
            [pgmalli.impl.resolve :as resolve]
            [pgmalli.impl.types :as types]
            [pgmalli.oracle :as oracle]
            [pgmalli.test-db :refer [*db* datasource exec-sql! with-postgres]]))

(def ^:private matrix
  "[name type sample] of pgmalli.oracle/matrix-types: u has c_<name> of the type, a_<name> an
   array of it, d_<name> of the domain matrix.d_<name> over it and da_<name> an array of that."
  (oracle/matrix-types (constantly true)))

(def ^:private columns
  "{column type} of u: matrix.types' (pgmalli.oracle/matrix-schema!), and c_mood of an enum."
  (into (sorted-map)
        (concat (for [[n t] matrix
                      [prefix ty] [["c_" t] ["a_" (str t "[]")] ["d_" (str "matrix.d_" n)] ["da_" (str "matrix.d_" n "[]")]]]
                  [(keyword (str prefix n)) ty])
                [[:c_mood "feeling"]])))

(use-fixtures :once
  (fn [t]
    (with-postgres
      #(do (when *db*
             ;; in public, where the types are named unqualified
             (exec-sql! (apply str (for [e (set (keep :extension (vals types/types)))]
                                     (str "CREATE EXTENSION IF NOT EXISTS \"" e "\";\n"))))
             (with-open [con (jdbc/get-connection (datasource))] (oracle/matrix-schema! con))
             ;; one row, NULL but where a domain refuses it
             (let [cs (str/join ", " (cons "id" (for [[n] matrix] (str "d_" n))))]
               (exec-sql! (str "CREATE TYPE feeling AS ENUM ('a', 'b');\n"
                               "CREATE TABLE u (LIKE matrix.types, c_mood feeling);\n"
                               "INSERT INTO u (" cs ") SELECT " cs " FROM matrix.types;"))))
           (t)))))

(def ^:private opts {:jdbc {:builder-fn 'next.jdbc.result-set/as-unqualified-maps}})

(defn- normal
  "A type's name as types keys it: integer for int4, x[] for an array of one."
  [t]
  (when t
    (let [[_ base dims] (re-matches #"(.*?)((?:\[\])*)" t)]
      (str (:name (types/entry base) base) dims))))

(defn- pg-type [schema]
  (some #(when (map? %) (:pg/type %)) (tree-seq vector? seq schema)))

(defn- query
  "The query selecting items, [alias expression] each; under :union an expression is a pair, a
   UNION of its two."
  [kind items]
  (case kind
    :select {:select (vec (for [[a e] items] [e a])) :from [:u]}
    :union {:union [{:select (vec (for [[a [l _]] items] [l a])) :from [:u]}
                    {:select (vec (for [[a [_ r]] items] [r a])) :from [:u]}]}))

(defn- pgmalli-types
  "{alias type} of the items pgmalli types, :any left out."
  [registry kind items]
  (into {} (for [[k & more] (rest (h/row-schema registry (query kind items) opts))
                 :let [t (normal (pg-type (last more)))] :when t]
             [k t])))

(defn- pg-types
  "{alias type} PostgreSQL gives items, an expression it refuses left out: a batch it refuses
   is split in two until each part runs."
  [ds kind items]
  (try (update-keys (jdbc/execute-one! ds (sql/format {:select (for [[a] items] [[:cast [:pg_typeof a] :text] a])
                                                       :from [[(query kind items) :s]]})
                                       {:builder-fn next.jdbc.result-set/as-unqualified-maps})
                    keyword)
       (catch Exception _
         (when (next items)
           (let [[l r] (split-at (quot (count items) 2) items)]
             (merge (pg-types ds kind l) (pg-types ds kind r)))))))

(defn- compared
  "[expression pgmalli postgresql] for each of exprs both type, a batch of them at a time."
  [registry ds kind exprs]
  (let [items (vec (map-indexed (fn [i e] [(keyword (str "e" i)) e]) exprs))
        both (fn [items] (let [ours (pgmalli-types registry kind items)]
                           [ours (pg-types ds kind (filter (comp ours first) items))]))
        [ours theirs] (reduce (fn [[o t] [o' t']] [(merge o o') (merge t t')]) [{} {}]
                              (pmap both (partition-all 1000 items)))]
    (for [[a e] items
          :let [o (ours a) t (some-> (theirs a) normal)]
          :when (and o t)]
      [e o t])))

(def ^:private domain-bases
  "{domain base}: matrix.d_<name>'s base type, as normal names it."
  (into {} (for [[n t] matrix] [(str "matrix.d_" n) (normal t)])))

(def pending
  "Mismatches in src that src does not fix yet, which report holds out until it does: {:label
   :match :reason :repro}, :match a predicate of [expression pgmalli postgresql] under report's
   label. report fails on an entry that matches no mismatch, so a fix empties it."
  [])

(defn- report
  "Fails where pgmalli types an expression of cs otherwise than PostgreSQL but as a pending entry
   holds, or types no more than at-least of them."
  [label at-least cs]
  (is (< at-least (count cs)) (str label ": pgmalli types " (count cs) " of them"))
  ;; a column of a domain the driver reads otherwise than the registry holds it (a date as a
  ;; java.sql.Date) reads as its base type, which RowDescription names, where pg_typeof names the domain
  (let [ms (remove (fn [[_ o t]] (or (= o t) (= o (domain-bases t)))) cs)
        held (filter #(= label (:label %)) pending)]
    (when-let [ms (seq (remove (fn [m] (some #((:match %) m) held)) ms))]
      (is false (str label ": pgmalli types these otherwise than PostgreSQL (expression, pgmalli, PostgreSQL)\n  "
                     (str/join "\n  " (map pr-str ms)))))
    (doseq [{:keys [reason match]} held :when (not-any? match ms)]
      (is false (str "a pending entry of " label " no longer fails: remove it from pgmalli.expression-types-test/pending (" reason ")")))))

(def ^:private cols (keys columns))

(defn- col [t] (some (fn [[c x]] (when (= t x) c)) columns))

(defn- registry [] (pgmalli/registry (files/from-database {:db *db*} "public") (files/from-database {:db *db*} "matrix")))

(def ^:private pairs
  "[a b]: each pair of the plain columns (c_), and each pair of columns of any form whose types are
   of one category, both arrays or neither."
  (let [kind (into {} (for [[n t] matrix
                            :let [cat (or (types/category (str/replace t #"\(.*\)$" "")) t)]
                            [prefix array?] [["c_" false] ["d_" false] ["a_" true] ["da_" true]]]
                        [(keyword (str prefix n)) [cat array?]]))
        plain (filter #(str/starts-with? (name %) "c_") cols)]
    (vec (distinct (concat (for [a plain b plain] [a b])
                           (for [a cols b cols :when (and (kind a) (= (kind a) (kind b)))] [a b]))))))

(deftest every-pair-of-types-resolves-as-postgresql-resolves-it
  (when *db*
    (let [registry (registry)
          ds (datasource)]
      (report "coalesce, greatest, least, CASE, nullif" 3300
              (compared registry ds :select
                          (for [[a b] pairs, f [#(vector :coalesce %1 %2) #(vector :greatest %1 %2) #(vector :least %1 %2)
                                                #(vector :case [:= 1 1] %1 :else %2) #(vector :nullif %1 %2)]]
                            (f a b))))
      (report "UNION" 640 (compared registry ds :union pairs))
      (report "UNION with NULL" 590 (compared registry ds :union (concat (for [c cols] [c nil]) (for [c cols] [nil c])))))))

(deftest every-aggregate-of-every-type-is-typed-as-postgresql-types-it
  (when *db*
    (report "sum, avg, min, max, count" 420
            (compared (registry) (datasource) :select
                        (for [c cols, f [:sum :avg :min :max :count]] [f c])))))

(def ^:private values
  "SQL words, json builders, literals, parameters, scalar subqueries, a parenthesized call, aggregates with ORDER BY."
  [:current_timestamp :current_date :current_time :localtimestamp :localtime :true :false
   :current_user :session_user [:now] :%now [:count :*] :%count.*
   [:jsonb_build_object "a" (col "integer")] [:json_build_object "a" (col "integer")] [:jsonb_build_array (col "integer")] [:json_build_array (col "integer")]
   [:jsonb_agg (col "integer")] [:json_agg (col "integer")] [:jsonb_object_agg "a" (col "integer")] [:json_object_agg "a" (col "integer")]
   [:jsonb_object [:raw "'{a,b}'::text[]"]] [:json_object [:raw "'{a,b}'::text[]"]]
   [:inline 1] [:inline 10000000000] [:inline 100000000000000000000N] [:inline 1.5M] [:inline 1.5] [:inline 1/3]
   [:inline "s"] [:inline true] [:inline false]
   1 10000000000 1.5M 1.5 "s" true
   [:lift 1] [:lift (int 1)] [:lift (short 1)] [:lift 10000000000] [:lift 1.5M] [:lift 1.5] [:lift (float 1.5)]
   {:select [[[:max (col "integer")]]] :from [:u]} {:select [(col "text")] :from [:u]} [[:count (col "integer")]]
   [:array_agg [:order-by (col "integer") (col "text")]] [:string_agg (col "text") [:order-by [:inline ","] (col "integer")]]
   [:lift 1N] [:lift "s"] [:lift true] [:lift (java.util.UUID/randomUUID)] [:lift (java.time.LocalDate/of 2026 1 1)]])

(deftest words-json-builders-literals-and-parameters-are-typed-as-postgresql-types-them
  (when *db*
    (let [registry (registry)
          ds (datasource)]
      ;; one at a time: an aggregate beside a column outside one wants a GROUP BY
      (report "words, json builders, literals, parameters" 50
              (mapcat #(compared registry ds :select [%]) values)))))

(def ^:private named
  "Expressions selected with no alias, for the name PostgreSQL gives each."
  (let [a (col "integer") b (col "text") n (col "bigint") ts (col "timestamp with time zone")]
    (concat
     (for [f [:coalesce :greatest :least :nullif]] [f a n])
     (for [f [:sum :avg :min :max :count]] [f a])
     [[:case [:= 1 1] a :else n] [:case [:= 1 1] a :else [:inline 1]] [:case [:= 1 1] a :else [:+ a 1]] [:case [:= 1 1] a]
      [:case [:= 1 1] ts :else [:now]]
      [:trim b] [:lower b] [:upper b] [:length b] [:abs a] [:pg_catalog.now] [:now] :%now :%count.*
      [:cast a :text] [:cast [:lower b] :text] [:cast [:inline 1] :integer] [:array [a]]
      [:+ a 1] [:= a a] [:inline 1] [:inline "s"] [:inline true] 1 "s" [:lift 1]
      :current_date :current_timestamp :current_user :true
      [:jsonb_build_object "a" a] [:json_agg a]])))

(deftest an-unaliased-expression-is-named-as-postgresql-names-it
  (when *db*
    (let [registry (registry)
          cs (with-open [con (jdbc/get-connection (datasource))]
               (doall
                (for [e named
                      :let [q {:select [[e]] :from [:u]}
                            ours (some-> (h/row-schema registry q opts) second first name)]
                      :when ours
                      :let [theirs (try (with-open [ps (jdbc/prepare con (sql/format q))]
                                          (.getColumnLabel (.getMetaData ps) 1))
                                        (catch Exception _ nil))]
                      :when theirs]
                  [e ours theirs])))]
      (is (< 38 (count cs)) (str "pgmalli and PostgreSQL name " (count cs) " of the expressions"))
      (when-let [ms (seq (remove (fn [[_ o t]] (= o t)) cs))]
        (is false (str "pgmalli names these otherwise than PostgreSQL (expression, pgmalli, PostgreSQL)\n  "
                       (str/join "\n  " (map pr-str ms))))))))

(defn- described
  "{alias type} PostgreSQL describes items as (prepared, not run, so no function runs), an item
   it refuses left out: a batch it refuses is split in two until each part prepares."
  [con typnames items]
  (try (with-open [ps (jdbc/prepare con (sql/format {:select (for [[a e] items] [e a]) :from [:u]}))]
         (let [md (.getMetaData ps)]
           (into {} (for [i (range 1 (inc (.getColumnCount md)))]
                      [(keyword (.getColumnLabel md i)) (typnames (.getColumnTypeName md i))]))))
       (catch Exception _
         (when (next items)
           (let [[l r] (split-at (quot (count items) 2) items)]
             (merge (described con typnames l) (described con typnames r)))))))

(def ^:private stand-ins
  "Expressions for each polymorphic argument type: of an element, an array, a range, a
   multirange, an enum, a row."
  {"anyelement" [:c_integer :c_text] "anynonarray" [:c_integer :c_text] "anycompatible" [:c_integer :c_text]
   "anycompatiblenonarray" [:c_integer] "\"any\"" [:c_integer :c_text]
   "anyarray" [:a_integer :a_text] "anycompatiblearray" [:a_integer :a_text]
   "anyrange" [:c_int4range] "anycompatiblerange" [:c_int4range] "anymultirange" [:c_int4multirange]
   "anycompatiblemultirange" [:c_int4multirange] "anyenum" [:c_mood] "record" [[:row :c_integer :c_text]]})

(def ^:private by-type
  "{type [column ...]}: the columns of a type, its own and its domain's."
  (into {} (for [[n t] matrix
                 [k ps] [[t ["c_" "d_"]] [(str t "[]") ["a_" "da_"]]]]
             [k (mapv #(keyword (str % n)) ps)])))

(def ^:private literal
  "{type text}: an unknown literal PostgreSQL reads as a value of the type."
  (into {} (map (fn [[_ t v]] [t v])) matrix))

(defn- calls
  "[kind expressions] of the overloads of the catalog the server's version has, each called with
   columns of its argument types (stand-ins for a polymorphic one) and with their domains', with
   NULL or a string literal, unknowns, in one argument, and with one argument as :raw, which
   pgmalli does not type: aggregates apart, as an aggregate beside a column wants a GROUP BY."
  [version]
  (let [arg (fn [d] (or (by-type d) (stand-ins d)))
        exprs (fn [n {:keys [args]}]
                (let [cols (map arg args)
                      call (fn [as] (into [(keyword n)] as))]
                  (when (every? seq cols)
                    (concat [(call (map first cols))]
                            (when (every? next cols) [(call (map second cols))])
                            (for [i (range (count args))] (call (assoc (vec (map first cols)) i nil)))
                            (for [i (range (count args))] (call (assoc (vec (map first cols)) i [:inline (literal (nth args i) "1")])))
                            ;; an argument pgmalli does not type: :raw
                            (for [i (range (count args))] (call (update (vec (map first cols)) i #(vector :raw (first (sql/format-expr %))))))))))]
    (group-by first
              (for [kind [:functions :operators]
                    [n os] (kind @types/catalog)
                    o os
                    :when (and (or (nil? (:in o)) (some #{version} (:in o)))
                               (not (#{:w} (:kind o)))
                               (or (= :functions kind) (re-matches #"[+\-*/<>=~!@#%^&|`?]+" n)))
                    e (exprs n o)]
                [(if (= :a (:kind o)) :aggregates :plain) e]))))

(defn- server-version [ds]
  (first (str/split (:server_version (jdbc/execute-one! ds ["SHOW server_version"])) #"\.")))

(deftest every-function-and-operator-of-the-catalog-is-typed-as-postgresql-types-it
  (when *db*
    (let [registry (registry)
          ds (datasource)
          version (server-version ds)
          typnames (into {} (map (juxt :typname :t)) (jdbc/execute! ds ["SELECT typname, format_type(oid, NULL) AS t FROM pg_type"]
                                                                   {:builder-fn next.jdbc.result-set/as-unqualified-maps}))
          ;; RowDescription describes a domain's value as of its base type
          base (into {} (map (juxt :d :b)) (jdbc/execute! ds ["SELECT format_type(oid, NULL) AS d, format_type(typbasetype, NULL) AS b FROM pg_type WHERE typtype = 'd'"]
                                                         {:builder-fn next.jdbc.result-set/as-unqualified-maps}))
          cs (with-open [con (jdbc/get-connection ds)]
               (doall
                (for [[_ es] (calls version)
                      batch (partition-all 300 (distinct (map second es)))
                      :let [items (vec (map-indexed (fn [i e] [(keyword (str "e" i)) e]) batch))
                            ours (pgmalli-types registry :select items)
                            theirs (described con typnames (filter (comp ours first) items))]
                      [a e] items
                      :let [o (get base (ours a) (ours a)) t (some-> (theirs a) normal)]
                      :when (and o t)]
                  [e o t])))]
      (report "functions and operators" 16000 cs))))

(defn- refused?
  "Whether PostgreSQL refuses q, :params params, as pgjdbc sends them: run on the one row of u,
   rolled back."
  [^java.sql.Connection con q params]
  (try (jdbc/execute! con (sql/format q {:params params})) false
       (catch java.sql.SQLException e (some? (oracle/refused e)))
       (finally (.rollback con))))

(defn- argument-failures
  "[query class] of each value of pgmalli.oracle/values sent as :?x of query q of places [q
   held?] that PostgreSQL takes but arg-types refuses, where held? says a value sent as that
   type is one the places of :?x hold as their own (otherwise refused by design)."
  [registry ds places]
  (let [refusals (doall (pmap (fn [[q held?]]
                                (let [s (m/schema (:x (h/arg-types registry q opts)) {:registry registry})]
                                  [q (for [v (vals oracle/values)
                                           :let [sent (pjdbc/sent-type v {})]
                                           :when (and sent (held? sent) (not (m/validate s v)))]
                                       v)]))
                              places))]
    (with-open [con (jdbc/get-connection ds)]
      (jdbc/execute! con ["SET statement_timeout = 2000"])
      (.setAutoCommit con false)
      (doall (for [[q vs] refusals, v vs :when (not (refused? con q {:x v}))] [q (class v)])))))

(deftest an-argument-of-a-call-takes-what-postgresql-takes
  (when *db*
    (let [registry (registry)
          ds (datasource)
          version (server-version ds)
          ;; each overload of 1 to 3 arguments (of a function, or an operator HoneySQL writes) with
          ;; columns of its argument types, an argument in one position, and the types the candidates
          ;; PostgreSQL may resolve it to take there
          places (distinct
                  (for [kind [:functions :operators]
                        [n os] (kind @types/catalog)
                        {:keys [args] :as o} os
                        :when (and (or (nil? (:in o)) (some #{version} (:in o))) (not= :w (:kind o)) (<= 1 (count args) 3)
                                   (or (= :functions kind) (re-matches #"[+\-*/<>=~!@#%^&|`?]+" n)))
                        :let [cs (map (comp first by-type) args)]
                        :when (every? some? cs)
                        i (range (count cs))
                        :let [ts (resolve/argument-types (assoc (vec args) i :free) i (resolve/overloads kind n))]
                        :when (seq ts)]
                    ;; refused by design: a value no candidate holds as its own; of an operator, one
                    ;; its type there or the other operand's does not (an argument compared with a
                    ;; column is of its type)
                    [{:select [[(into [(keyword n)] (assoc (vec cs) i :?x)) :e]] :from [:u]}
                     (if (= :operators kind)
                       (fn [sent] (every? #(accept/holds? % sent :compare) args))
                       (fn [sent] (some #(accept/holds? % sent :compare) ts)))]))
          fails (argument-failures registry ds places)]
      (is (< 4600 (count places)) (str (count places) " calls"))
      (is (empty? fails) (str "PostgreSQL takes these, arg-types does not (query, class)\n  " (str/join "\n  " (map pr-str fails)))))))

(deftest an-argument-of-two-uses-takes-what-postgresql-takes-in-both
  (when *db*
    (let [registry (registry)
          ;; each plain column, by the type pgmalli.impl.accept names it
          plain (for [[n t] matrix] [(keyword (str "c_" n)) (str/replace t #"\(.*\)$" "")])
          places (for [[a ta] plain [b tb] plain
                       e [[:and [:= a :?x] [:= b :?x]] [:and [:= a :?x] [:= [:coalesce b :?x] b]]]]
                   [{:select [:*] :from [:u] :where e} (fn [sent] (every? #(accept/holds? % sent :compare) [ta tb]))])
          fails (argument-failures registry (datasource) places)]
      (is (empty? fails) (str "PostgreSQL takes these, arg-types does not (query, class)\n  " (str/join "\n  " (map pr-str fails)))))))

(def ^:private own-schema
  "A schema's own functions and operators: overloads of pg_catalog's names of other argument
   types (round, upper, concat), one of the same (lower, which pg_catalog's shadows), table
   functions."
  "CREATE SCHEMA fx;
   CREATE TYPE fx.mood AS ENUM ('happy', 'sad');
   CREATE TABLE fx.t (id int PRIMARY KEY, n int NOT NULL, s text NOT NULL, m fx.mood NOT NULL);
   INSERT INTO fx.t VALUES (1, 2, 'a', 'happy');
   CREATE FUNCTION fx.double_it(x int) RETURNS int LANGUAGE sql AS 'SELECT x * 2';
   CREATE FUNCTION fx.round(x int) RETURNS text LANGUAGE sql AS 'SELECT x::text';
   CREATE FUNCTION fx.lower(x text) RETURNS int LANGUAGE sql AS 'SELECT 1';
   CREATE FUNCTION fx.upper(x fx.mood) RETURNS text LANGUAGE sql AS 'SELECT x::text';
   CREATE FUNCTION fx.concat(a text, b text) RETURNS int LANGUAGE sql AS 'SELECT 1';
   CREATE FUNCTION fx.pick() RETURNS fx.mood LANGUAGE sql AS 'SELECT ''sad''::fx.mood';
   CREATE FUNCTION fx.vsum(VARIADIC xs int[]) RETURNS bigint LANGUAGE sql AS 'SELECT sum(x) FROM unnest(xs) x';
   CREATE FUNCTION fx.mood_plus(m fx.mood, i int) RETURNS fx.mood LANGUAGE sql AS 'SELECT m';
   CREATE OPERATOR fx.+ (LEFTARG = fx.mood, RIGHTARG = int, FUNCTION = fx.mood_plus);
   CREATE FUNCTION fx.labels(lim int DEFAULT 10) RETURNS TABLE (uid int, label text) LANGUAGE sql AS 'SELECT id, s FROM fx.t LIMIT lim';
   CREATE FUNCTION fx.all_t() RETURNS SETOF fx.t LANGUAGE sql AS 'SELECT * FROM fx.t';
   CREATE FUNCTION fx.one_out(OUT v bigint) LANGUAGE sql AS 'SELECT 1::bigint';")

(defn- own-described
  "[[label type table] ...] of the columns PostgreSQL describes q as, the schema fx on the search
   path (its types named as its generated file names them, unqualified); nil where it refuses it."
  [con typnames q]
  (jdbc/execute! con ["SET search_path TO fx"])
  (try (with-open [ps (jdbc/prepare con (sql/format q))]
         (let [md (.getMetaData ps)]
           (vec (for [i (range 1 (inc (.getColumnCount md)))]
                  [(.getColumnLabel md i) (normal (str/replace (typnames (.getColumnTypeName md i)) #"^fx\." "")) (.getTableName md i)]))))
       (catch Exception _ nil)))

(deftest a-schemas-own-functions-and-operators-are-typed-as-postgresql-types-them
  (when *db*
    (exec-sql! own-schema)
    (let [registry (pgmalli/registry (files/from-database {:db *db*} "fx"))
          o (assoc opts :schema "fx")
          typnames (into {} (map (juxt :typname :t)) (jdbc/execute! (datasource) ["SELECT typname, format_type(oid, NULL) AS t FROM pg_type"]
                                                                   {:builder-fn next.jdbc.result-set/as-unqualified-maps}))
          exprs [[:double_it :n] [:round :n] [:pg_catalog.round :n] [:lower :s] [:fx.lower :s] [:upper :m] [:upper :s]
                 [:concat :s :s] [:pick] [:vsum :n :n :n] [:+ :m [:inline 1]]]
          froms [{:select [:*] :from [[[:labels] :r]]}
                 {:select [:*] :from [[[:labels 1] :r]]}
                 {:select [:*] :from [[[:all_t] :a]]}
                 {:select [:*] :from [[[:one_out] :x]]}
                 {:select [:*] :from [[[:double_it 1] :d]]}
                 {:select [:*] :from [[[:pick] :p]]}
                 {:select [:*] :from [[[:with-ordinality [:labels] :r]]]}]
          ours (fn [q] (vec (for [[k & more] (rest (h/row-schema registry q o))] [(name k) (normal (pg-type (last more)))])))]
      (with-open [con (jdbc/get-connection (datasource))]
        (doseq [e exprs
                :let [q {:select [[e :e]] :from [:t]}]]
          (is (= (mapv pop (own-described con typnames q)) (ours q)) (pr-str e)))
        (doseq [q froms]
          (let [theirs (own-described con typnames q)]
            (is (= (mapv pop theirs) (ours q)) (pr-str q))
            (is (every? #{""} (map peek theirs)) (str "of no table: " (pr-str q))))))
      (is (= {:x [:maybe [:or [:int {:pg/type "integer"}] 'inst?]]} (h/arg-types registry {:select [[[:double_it :?x] :d]] :from [:t]} o))))))

