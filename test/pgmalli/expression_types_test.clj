(ns pgmalli.expression-types-test
  "The types and names h/row-schema gives a select expression, against what PostgreSQL gives:
   coalesce, greatest, least, CASE, nullif and UNION over every pair of the types a column holds,
   the aggregates over each, every function and operator of pgmalli's catalog snapshot over
   columns of its argument types (and NULL, an unknown), SQL words, literals and parameters.
   pgmalli may type an expression :any; where it names a type, PostgreSQL names it too. JVM only
   (next.jdbc), so bb test does not list it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            next.jdbc.result-set
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.types :as types]
            [pgmalli.test-db :refer [*db* datasource exec-sql! with-postgres]]))

(def ^:private columns
  "{column type}: one column of every type types lists, and of a few arrays, named after it."
  (into (sorted-map) (for [t (concat (keys types/types) ["integer[]" "text[]" "bigint[]" "jsonb[]"])]
                       [(keyword (str "c_" (str/replace (str/replace t "[]" " array") #"[^a-z0-9]+" "_"))) t])))

(use-fixtures :once
  (fn [t]
    (with-postgres
      #(do (when *db*
             (exec-sql! (str (apply str (for [e (set (keep :extension (vals types/types)))]
                                          (str "CREATE EXTENSION IF NOT EXISTS \"" e "\";\n")))
                             "CREATE TABLE u (" (str/join ", " (for [[c t] columns] (str (name c) " " t))) ");\n"
                             "INSERT INTO u DEFAULT VALUES;")))
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
  "[expression pgmalli postgresql] for each of exprs both type."
  [registry ds kind exprs]
  (let [items (vec (map-indexed (fn [i e] [(keyword (str "e" i)) e]) exprs))
        ours (pgmalli-types registry kind items)
        theirs (pg-types ds kind (filter (comp ours first) items))]
    (for [[a e] items
          :let [o (ours a) t (some-> (theirs a) normal)]
          :when (and o t)]
      [e o t])))

(defn- report [label cs]
  (is (seq cs) (str label ": pgmalli types none of them"))
  (when-let [ms (seq (remove (fn [[_ o t]] (= o t)) cs))]
    (is false (str label ": pgmalli types these otherwise than PostgreSQL (expression, pgmalli, PostgreSQL)\n  "
                   (str/join "\n  " (map pr-str ms))))))

(def ^:private cols (keys columns))

(defn- col [t] (some (fn [[c x]] (when (= t x) c)) columns))

(defn- registry [] (pgmalli/registry (files/from-database {:db *db*} "public")))

(deftest every-pair-of-types-resolves-as-postgresql-resolves-it
  (when *db*
    (let [registry (registry)
          ds (datasource)
          pairs (for [a cols b cols] [a b])]
      (report "coalesce, greatest, least, CASE, nullif"
              (compared registry ds :select
                          (for [[a b] pairs, f [#(vector :coalesce %1 %2) #(vector :greatest %1 %2) #(vector :least %1 %2)
                                                #(vector :case [:= 1 1] %1 :else %2) #(vector :nullif %1 %2)]]
                            (f a b))))
      (report "UNION" (compared registry ds :union pairs)))))

(deftest every-aggregate-of-every-type-is-typed-as-postgresql-types-it
  (when *db*
    (report "sum, avg, min, max, count"
            (compared (registry) (datasource) :select
                        (for [c cols, f [:sum :avg :min :max :count]] [f c])))))

(def ^:private values
  "SQL words, json builders, literals and parameters."
  [:current_timestamp :current_date :current_time :localtimestamp :localtime :true :false
   :current_user :session_user [:now] :%now [:count :*] :%count.*
   [:jsonb_build_object "a" (col "integer")] [:json_build_object "a" (col "integer")] [:jsonb_build_array (col "integer")] [:json_build_array (col "integer")]
   [:jsonb_agg (col "integer")] [:json_agg (col "integer")] [:jsonb_object_agg "a" (col "integer")] [:json_object_agg "a" (col "integer")]
   [:jsonb_object [:raw "'{a,b}'::text[]"]] [:json_object [:raw "'{a,b}'::text[]"]]
   [:inline 1] [:inline 10000000000] [:inline 100000000000000000000N] [:inline 1.5M] [:inline 1.5] [:inline 1/3]
   [:inline "s"] [:inline true] [:inline false]
   1 10000000000 1.5M 1.5 "s" true
   [:lift 1] [:lift (int 1)] [:lift (short 1)] [:lift 10000000000] [:lift 1.5M] [:lift 1.5] [:lift (float 1.5)]
   [:lift 1N] [:lift "s"] [:lift true] [:lift (java.util.UUID/randomUUID)] [:lift (java.time.LocalDate/of 2026 1 1)]])

(deftest words-json-builders-literals-and-parameters-are-typed-as-postgresql-types-them
  (when *db*
    (let [registry (registry)
          ds (datasource)]
      ;; one at a time: an aggregate beside a column outside one wants a GROUP BY
      (report "words, json builders, literals, parameters"
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
      (is (< 35 (count cs)) (str "pgmalli and PostgreSQL name " (count cs) " of the expressions"))
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
  "A column for each polymorphic argument type: of an element, an array, a range, a multirange."
  {"anyelement" ["c_integer" "c_text"] "anynonarray" ["c_integer" "c_text"] "anycompatible" ["c_integer" "c_text"]
   "anycompatiblenonarray" ["c_integer"] "\"any\"" ["c_integer" "c_text"]
   "anyarray" ["c_integer_array" "c_text_array"] "anycompatiblearray" ["c_integer_array" "c_text_array"]
   "anyrange" ["c_int4range"] "anycompatiblerange" ["c_int4range"] "anymultirange" ["c_int4multirange"]
   "anycompatiblemultirange" ["c_int4multirange"]})

(defn- calls
  "[kind expressions] of the overloads of the catalog the server's version has, each called with
   columns of its argument types (stand-ins for a polymorphic one), and with NULL, an unknown, in
   one argument: aggregates apart, as an aggregate beside a column wants a GROUP BY."
  [version]
  (let [by-type (into {} (map (fn [[c t]] [t c])) columns)
        arg (fn [d] (if-let [c (by-type d)] [c] (map keyword (stand-ins d))))
        exprs (fn [n {:keys [args]}]
                (let [cols (map arg args)
                      call (fn [as] (into [(keyword n)] as))]
                  (when (every? seq cols)
                    (concat [(call (map first cols))]
                            (when (every? next cols) [(call (map second cols))])
                            (for [i (range (count args))] (call (assoc (vec (map first cols)) i nil)))))))]
    (group-by first
              (for [kind [:functions :operators]
                    [n os] (kind @types/catalog)
                    o os
                    :when (and (or (nil? (:in o)) (some #{version} (:in o)))
                               (not (#{:w} (:kind o)))
                               (or (= :functions kind) (re-matches #"[+\-*/<>=~!@#%^&|`?]+" n)))
                    e (exprs n o)]
                [(if (= :a (:kind o)) :aggregates :plain) e]))))

(deftest every-function-and-operator-of-the-catalog-is-typed-as-postgresql-types-it
  (when *db*
    (let [registry (registry)
          ds (datasource)
          version (first (str/split (:server_version (jdbc/execute-one! ds ["SHOW server_version"])) #"\."))
          typnames (into {} (map (juxt :typname :t)) (jdbc/execute! ds ["SELECT typname, format_type(oid, NULL) AS t FROM pg_type"]
                                                                   {:builder-fn next.jdbc.result-set/as-unqualified-maps}))
          ;; HoneySQL writes an operator it has not registered as a function call
          _ (doseq [[n] (:operators @types/catalog)] (when-not (sql/registered-op? (keyword n)) (sql/register-op! (keyword n))))
          cs (with-open [con (jdbc/get-connection ds)]
               (doall
                (for [[_ es] (calls version)
                      batch (partition-all 300 (distinct (map second es)))
                      :let [items (vec (map-indexed (fn [i e] [(keyword (str "e" i)) e]) batch))
                            ours (pgmalli-types registry :select items)
                            theirs (described con typnames (filter (comp ours first) items))]
                      [a e] items
                      :let [o (ours a) t (some-> (theirs a) normal)]
                      :when (and o t)]
                  [e o t])))]
      (is (< 3000 (count cs)) (str "pgmalli and PostgreSQL type " (count cs) " of the calls"))
      (report "functions and operators" cs))))
