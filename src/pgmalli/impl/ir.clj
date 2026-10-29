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
                        :constraints {\"users_age_check\" {:name :type :columns :check_clause :is_valid :nulls_not_distinct :references :exclude}}
                        :triggers [{:name :before :insert :update :delete}]}}
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
            [pgmalli.impl.error :as error]
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
             (error/fail! :pgmalli.error/fault "pgmalli/ir.sql is not on the classpath: the pgmalli jar or its resources path is incomplete; reinstall pgmalli"))))

(defn- jdbc-sql
  "ir.sql with its psql variables written in: :\"schema\" an identifier, :'schema' a literal."
  [schema]
  (-> (query-sql)
      (str/replace ":\"schema\"" (str "\"" (str/replace schema "\"" "\"\"") "\""))
      (str/replace ":'schema'" (str "'" (str/replace schema "'" "''") "'"))))

(defn- target
  "host:port/db psql reaches, from the db map or psql's environment."
  [env]
  (str (get env "PGHOST" "local socket") ":" (get env "PGPORT" "5432") "/" (get env "PGDATABASE" (get env "PGUSER" (System/getProperty "user.name")))))

(defn- psql-out [{:keys [schema psql] :or {psql "psql"} :as db}]
  (let [;; shell/sh replaces the whole environment, so layer the overrides on the current one
        env (into (into {} (System/getenv)) (keep (fn [[k e]] (when-some [v (get db k)] [e (str v)])) env-keys))
        args (cond-> [psql "-X" "-q" "-A" "-t" "-v" "ON_ERROR_STOP=1" "-v" (str "schema=" schema) "-f" "-"
                      :in (query-sql) :env env])
        {:keys [exit out err]} (try (apply shell/sh args)
                                    (catch java.io.IOException e
                                      (error/fail! :pgmalli.error/psql-missing
                                                   (str "cannot run psql (" psql "): " (ex-message e)
                                                        "; install the PostgreSQL client, set :db :psql to its path, or give :db :jdbc-url")
                                                   {:given psql} e)))
        ;; psql tells each address it tried in turn: the first says it
        first-err (str/replace (or (first (remove str/blank? (str/split-lines (str err)))) "") #"^psql: (error: )?" "")]
    (cond (zero? exit) out
          ;; psql exits 2 when it cannot connect, 3 when the script fails
          (= 2 exit) (error/fail! :pgmalli.error/db-unavailable
                                  (str "cannot connect to " (target env) " with psql: " first-err
                                       "; check :db in pgmalli.edn (or PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD) and that PostgreSQL is running")
                                  {:given (target env) :exit exit :err err})
          :else (error/fail! :pgmalli.error/fault
                             (str "psql failed reading schema " schema " on " (target env) " (exit " exit "): " first-err
                                  "; pgmalli needs PostgreSQL 16 or later and a user that can read pg_catalog")
                             {:given schema :exit exit :err err}))))

(def ^:private db-keys
  {:psql (set (concat (keys env-keys) [:psql])) :jdbc #{:jdbc-url :user :password} :datasource #{:datasource}})

(defn- via
  "How a db map reads the database, :psql, :jdbc or :datasource; a key none of them takes, or
   keys of two, is an error, since the others would be left out and psql reach the database its
   environment names."
  [db]
  (let [via (cond (contains? db :datasource) :datasource (contains? db :jdbc-url) :jdbc :else :psql)
        stray (remove (conj (db-keys via) :schema) (keys db))]
    (when (seq stray)
      (error/fail! :pgmalli.error/invalid-config
                   (str ":db keys " (vec stray) " do not go with " (name via)
                        (error/did-you-mean (some #(error/suggest % (db-keys via)) stray))
                        "; psql takes " (vec (sort (db-keys :psql))) ", JDBC :jdbc-url :user :password, or :datasource alone")
                   {:given (vec stray) :known (vec (sort (db-keys via)))}))
    (when (and (not= :psql via) (System/getProperty "babashka.version"))
      (error/fail! :pgmalli.error/invalid-config "babashka has no JDBC: give :db psql settings instead, or run on the JVM" {:given via}))
    via))

(defn from-db
  "Structure of the schema named by :schema (default \"public\"). :psql selects the binary;
   :jdbc-url (with :user and :password) or :datasource reads over JDBC instead."
  ([] (from-db {}))
  ([{:keys [schema] :or {schema "public"} :as db}]
   (let [out (if (= :psql (via db)) (psql-out (assoc db :schema schema)) ((requiring-resolve 'pgmalli.impl.ir-jdbc/out) db (jdbc-sql schema)))
         ir (keywordize (json/parse (str/trim out)) false)]
     (when-not (:exists ir)
       (error/fail! :pgmalli.error/invalid-config (str "schema " (pr-str schema) " does not exist in the database; check :schemas in pgmalli.edn") {:given schema}))
     (dissoc ir :exists))))
