(ns pgmalli-next.impl.ir
  "Reads the structure of one schema from pg_catalog through psql, or through JDBC when the db
   map has :jdbcUrl (with :user and :password) or :datasource (a javax.sql.DataSource); the
   JDBC driver is then the caller's, on the classpath.

   psql's connection settings are its own (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD,
   PGSSLMODE, ~/.pgpass); entries in the db map override them for the call.

   The result is the JSON document resources/pgmalli-next/ir.sql builds, which holds its shape,
   keywordized but for the maps keyed by an object's name (:tables, :constraints, :types). A
   unique index over plain columns is a UNIQUE constraint too, keyed \"<name> (index)\", since an
   index may bear a constraint's name. Expressions are the strings PostgreSQL's deparser produces."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.json :as json]
            [pgmalli-next.impl.types :as types]))

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
  {:host "PGHOST" :port "PGPORT" :dbname "PGDATABASE" :user "PGUSER" :password "PGPASSWORD" :sslmode "PGSSLMODE"})

(defn- query-sql []
  (slurp (or (io/resource "pgmalli-next/ir.sql")
             (error/fail! :pgmalli.error/fault "pgmalli-next/ir.sql is not on the classpath: the pgmalli jar or its resources path is incomplete; reinstall pgmalli"))))

(defn- jdbc-sql
  "ir.sql with its psql variables written in: :\"schema\" an identifier, :'schema' a literal."
  [schema]
  ;; one pass: a second would rewrite the first's output when the name holds :'schema'
  (str/replace (query-sql) #":\"schema\"|:'schema'"
               #(if (= % ":\"schema\"")
                  (str "\"" (str/replace schema "\"" "\"\"") "\"")
                  ;; E'': a backslash is an escape whatever standard_conforming_strings says
                  (str "E'" (-> schema (str/replace "\\" "\\\\") (str/replace "'" "''")) "'"))))

(defn- target
  "host:port/db psql reaches, from the db map or psql's environment."
  [env]
  (str (get env "PGHOST" "local socket") ":" (get env "PGPORT" "5432") "/" (get env "PGDATABASE" (get env "PGUSER" (System/getProperty "user.name")))))

(defn needs
  "What reading a schema needs of the server, for an error that it failed."
  []
  (str "pgmalli needs " (types/version-label (types/oldest-version)) " or later and a user that can read pg_catalog"))

(defn- psql-error
  "Why psql failed, of its stderr: a line the server wrote (FATAL:), else the last address psql
   tried (it tries each address a host name has in turn, the last the one that answered), else
   its first line."
  [err]
  (let [lines (remove str/blank? (str/split-lines (str err)))]
    (str/replace (or (some #(when (str/includes? % "FATAL:") %) lines)
                     (last (filter #(str/includes? % "failed:") lines))
                     (first lines) "")
                 #"^psql: (error: )?" "")))

(defn- psql-out [{:keys [schema psql] :or {psql "psql"} :as db}]
  (let [;; shell/sh replaces the whole environment, so layer the overrides on the current one
        env (into (into {} (System/getenv)) (keep (fn [[k e]] (when-some [v (get db k)] [e (str v)])) env-keys))
        ;; -1: ir.sql's one transaction, which it makes read only
        args (cond-> [psql "-X" "-1" "-q" "-A" "-t" "-v" "ON_ERROR_STOP=1" "-v" (str "schema=" schema) "-f" "-"
                      :in (query-sql) :env env])
        {:keys [exit out err]} (try (apply shell/sh args)
                                    (catch java.io.IOException e
                                      (error/fail! :pgmalli.error/psql-missing
                                                   (str "cannot run psql (" psql "): " (ex-message e)
                                                        "; install the PostgreSQL client, set :db :psql to its path, or give :db :jdbcUrl")
                                                   {:given psql} e)))
        why (error/without-urls (psql-error err))
        err (error/without-urls err)]
    (cond (zero? exit) out
          ;; psql exits 2 when it cannot connect, 3 when the script fails
          (= 2 exit) (error/fail! :pgmalli.error/db-unavailable
                                  (str "cannot connect to " (target env) " with psql: " why
                                       "; check :db (or PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD) and that PostgreSQL is running")
                                  {:given (target env) :exit exit :err err})
          :else (error/fail! :pgmalli.error/fault
                             (str "psql failed reading schema " schema " on " (target env) " (exit " exit "): " why "; " (needs))
                             {:given schema :exit exit :err err}))))

(def ^:private db-keys
  {:psql (set (concat (keys env-keys) [:psql])) :jdbc #{:jdbcUrl :user :password} :datasource #{:datasource}})

(defn- via
  "How a db map reads the database, :psql, :jdbc or :datasource; a key none of them takes, or
   keys of two, is an error, since the others would be left out and psql reach the database its
   environment names."
  [db]
  (when (contains? db :jdbc-url)
    (error/fail! :pgmalli.error/invalid-config
                 ":db's :jdbc-url is not read: the URL is :jdbcUrl, as next.jdbc names it"
                 {:given [:jdbc-url] :did-you-mean [:jdbcUrl]}))
  (when (contains? db :db)
    (error/fail! :pgmalli.error/invalid-config
                 ":db's :db is not read: the database name is :dbname, as next.jdbc and libpq name it"
                 {:given [:db] :did-you-mean [:dbname]}))
  ;; not echoed: libpq takes a :dbname or a :host as a name, so a URI or a conninfo there, which
  ;; may carry a password, would be in the error
  (when (some #(re-find #"://|=" (str %)) (map db [:dbname :host]))
    (error/fail! :pgmalli.error/invalid-config
                 ":db's :dbname or :host is a URI or a conninfo, which psql reads as a name: give :host, :port, :dbname, :user and :password apart, or :jdbcUrl"
                 {:given [:dbname :host]}))
  (let [via (cond (contains? db :datasource) :datasource (contains? db :jdbcUrl) :jdbc :else :psql)
        stray (remove (conj (db-keys via) :schema) (keys db))]
    (when (seq stray)
      (error/fail! :pgmalli.error/invalid-config
                   (str (if (contains? db :dbtype)
                          (str ":db keys " (vec stray) " are next.jdbc's, which pgmalli does not read (it reads PostgreSQL alone); drop them")
                          (str ":db keys " (vec stray) " do not go with " (name via)
                               (error/did-you-mean (some #(error/suggest % (db-keys via)) stray))))
                        "; psql takes " (vec (sort (db-keys :psql))) ", JDBC :jdbcUrl :user :password, or :datasource alone")
                   {:given (vec stray) :known (vec (sort (db-keys via)))}))
    (when (and (not= :psql via) (System/getProperty "babashka.version"))
      (error/fail! :pgmalli.error/invalid-config "babashka has no JDBC: give :db psql settings instead, or run on the JVM" {:given via}))
    via))

(defn from-db
  "Structure of the schema named by :schema (default \"public\"). :psql selects the binary;
   :jdbcUrl (with :user and :password) or :datasource reads over JDBC instead."
  [{:keys [schema] :or {schema "public"} :as db}]
  (let [out (if (= :psql (via db)) (psql-out (assoc db :schema schema)) ((requiring-resolve 'pgmalli-next.impl.ir-jdbc/out) db (jdbc-sql schema)))
        ir (keywordize (json/parse (str/trim out)) false)]
    (when-not (:exists ir)
      (let [meant (error/suggest schema (:schemas ir))]
        (error/fail! :pgmalli.error/invalid-config (str "schema " (pr-str schema) " does not exist in the database; check :schemas"
                                                        (error/did-you-mean (some-> meant pr-str)))
                     (cond-> {:given schema} meant (assoc :did-you-mean [meant])))))
    (dissoc ir :exists :schemas)))
