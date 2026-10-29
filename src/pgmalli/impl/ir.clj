(ns pgmalli.impl.ir
  "Reads the structure of one schema from pg_catalog through psql, or through JDBC when the db
   map has :jdbc-url (with :user and :password) or :datasource (a javax.sql.DataSource); the
   JDBC driver is then the caller's, on the classpath.

   psql's connection settings are its own (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD,
   PGSSLMODE, ~/.pgpass); entries in the db map override them for the call.

   Result shape:
     {:name \"public\" :database_version \"PostgreSQL 17.6\"
      :tables {\"users\" {:name \"users\" :kind \"TABLE\"
                        :columns [{:name :position :data_type :type_schema :is_nullable
                                   :default_value :generated_expr :identity :max_length :precision :scale}]
                        :constraints {\"users_age_check\" {:name :type :columns :check_clause :is_valid :nulls_not_distinct :references}}
                        :triggers [{:name :insert :update :delete}]}}
      :types {\"mood\" {:kind \"ENUM\" :enum_values [...]}
              \"email\" {:kind \"DOMAIN\" :base_type :not_null :default :constraints [{:name :definition}]}}}
   :kind is \"TABLE\", \"VIEW\" or \"MATERIALIZED VIEW\" (views have columns and no constraints);
   :type is one of \"CHECK\", \"PRIMARY KEY\", \"UNIQUE\", \"FOREIGN KEY\"; :references is
   {:match :schema :table :columns} for foreign keys, :match one of \"SIMPLE\", \"FULL\", \"PARTIAL\";
   :is_enforced is false for a NOT ENFORCED constraint (PostgreSQL 18);
   :nulls_not_distinct is set for UNIQUE constraints; a unique index over plain columns with
   no predicate is listed as a UNIQUE constraint too, with :index true, keyed \"<name> (index)\"
   since an index may bear a constraint's name. A partitioned table carries a CHECK named
   \"<table> (partitions)\": the OR of its partitions' bounds, since it takes a row only when
   one of them does. :max_length is the typmod of varchar, bpchar, bit and varbit columns,
   of the element type for an array column. Maps keyed by object name (:tables, :constraints,
   :types) keep string keys; everything else is keywordized. Expressions are the strings
   PostgreSQL's deparser produces."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [pgmalli.impl.json :as json]))

(def ^:private named-maps #{:tables :constraints :types})

(defn- keywordize [x name-keyed?]
  (cond
    (map? x) (into {} (map (fn [[k v]]
                             (if name-keyed?
                               [k (keywordize v false)]
                               (let [k (keyword k)]
                                 [k (keywordize v (contains? named-maps k))]))))
                   x)
    (sequential? x) (mapv #(keywordize % false) x)
    :else x))

(def ^:private env-keys
  {:host "PGHOST" :port "PGPORT" :db "PGDATABASE" :user "PGUSER" :password "PGPASSWORD" :sslmode "PGSSLMODE"})

(defn- query-sql []
  (slurp (or (io/resource "pgmalli/ir.sql")
             (throw (ex-info "pgmalli/ir.sql is not on the classpath; the pgmalli jar or its resources path is incomplete"
                             {:resource "pgmalli/ir.sql"})))))

(defn- jdbc-sql
  "ir.sql with its psql variables written in: :\"schema\" an identifier, :'schema' a literal."
  [schema]
  (-> (query-sql)
      (str/replace ":\"schema\"" (str "\"" (str/replace schema "\"" "\"\"") "\""))
      (str/replace ":'schema'" (str "'" (str/replace schema "'" "''") "'"))))

(defn- psql-out [{:keys [schema psql dir] :or {psql "psql"} :as db}]
  (let [;; shell/sh replaces the whole environment, so layer the overrides on the current one
        env (into (into {} (System/getenv)) (keep (fn [[k e]] (when-some [v (get db k)] [e (str v)])) env-keys))
        args (cond-> [psql "-X" "-q" "-A" "-t" "-v" "ON_ERROR_STOP=1" "-v" (str "schema=" schema) "-f" "-"
                      :in (query-sql) :env env]
               dir (conj :dir dir))
        {:keys [exit out err]} (try (apply shell/sh args)
                                    (catch java.io.IOException e
                                      (throw (ex-info (str "cannot run psql (" psql "): " (ex-message e)
                                                           ". Install the PostgreSQL client, set :psql, or give :jdbc-url")
                                                      {:psql psql} e))))]
    (when-not (zero? exit)
      (throw (ex-info (str "psql failed (exit " exit "): " (str/trim err)) {:exit exit :err err :schema schema})))
    out))

(def ^:private db-keys
  {:psql (set (concat (keys env-keys) [:psql :dir])) :jdbc #{:jdbc-url :user :password} :datasource #{:datasource}})

(defn- via
  "How a db map reads the database, :psql, :jdbc or :datasource; a key none of them takes, or
   keys of two, is an error, since the others would be left out and psql reach the database its
   environment names."
  [db]
  (let [via (cond (contains? db :datasource) :datasource (contains? db :jdbc-url) :jdbc :else :psql)
        stray (remove (conj (db-keys via) :schema) (keys db))]
    (when (seq stray)
      (throw (ex-info (str ":db keys " (vec stray) " do not go with " (name via) ": psql takes " (vec (sort (db-keys :psql)))
                           ", JDBC :jdbc-url :user :password, or :datasource alone")
                      {:keys (vec stray) :via via})))
    (when (and (not= :psql via) (System/getProperty "babashka.version"))
      (throw (ex-info "babashka has no JDBC: give psql settings, or run on the JVM" {:via via})))
    via))

(defn from-db
  "Structure of the schema named by :schema (default \"public\"). :psql selects the binary;
   :jdbc-url (with :user and :password) or :datasource reads over JDBC instead."
  ([] (from-db {}))
  ([{:keys [schema] :or {schema "public"} :as db}]
   (let [out (if (= :psql (via db)) (psql-out (assoc db :schema schema)) ((requiring-resolve 'pgmalli.impl.ir-jdbc/out) db (jdbc-sql schema)))
         ir (keywordize (json/parse (str/trim out)) false)]
     (when-not (:exists ir)
       (throw (ex-info (str "schema does not exist: " schema) {:schema schema})))
     (dissoc ir :exists))))
