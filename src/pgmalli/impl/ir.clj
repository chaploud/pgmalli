(ns pgmalli.impl.ir
  "Reads the structure of one schema from pg_catalog through psql.

   Connection settings are psql's own (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD,
   PGSSLMODE, ~/.pgpass); entries in the db map override them for the call.

   Result shape:
     {:name \"public\" :database_version \"PostgreSQL 17.x ...\"
      :tables {\"users\" {:name \"users\"
                        :columns [{:name :position :data_type :type_schema :is_nullable
                                   :default_value :generated_expr :identity :max_length :precision :scale}]
                        :constraints {\"users_age_check\" {:name :type :columns :check_clause :is_valid :references}}}}
      :types {\"mood\" {:kind \"ENUM\" :enum_values [...]}
              \"email\" {:kind \"DOMAIN\" :base_type :not_null :default :constraints [{:name :definition}]}}}
   :type is one of \"CHECK\", \"PRIMARY KEY\", \"UNIQUE\", \"FOREIGN KEY\"; :references is
   {:schema :table :columns} for foreign keys. Maps keyed by object name (:tables, :constraints,
   :types) keep string keys; everything else is keywordized. Expressions are the strings
   PostgreSQL's deparser produces."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(def ^:private parse-json
  ;; babashka ships cheshire and cannot load data.json; the JVM uses data.json
  (if (System/getProperty "babashka.version")
    (let [f (requiring-resolve 'cheshire.core/parse-string)] #(f %))
    (let [f (requiring-resolve 'clojure.data.json/read-str)] #(f %))))

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
             (throw (ex-info "pgmalli/ir.sql is not on the classpath" {})))))

(defn from-db
  "Structure of the schema named by :schema (default \"public\"). :psql selects the binary."
  ([] (from-db {}))
  ([{:keys [schema psql dir] :or {schema "public" psql "psql"} :as db}]
   (let [;; shell/sh replaces the whole environment, so layer the overrides on the current one
         env (into (into {} (System/getenv)) (keep (fn [[k e]] (when-some [v (get db k)] [e (str v)])) env-keys))
         args (cond-> [psql "-X" "-q" "-A" "-t" "-v" "ON_ERROR_STOP=1" "-v" (str "schema=" schema) "-f" "-"
                       :in (query-sql) :env env]
                dir (conj :dir dir))
         {:keys [exit out err]} (try (apply shell/sh args)
                                     (catch java.io.IOException e
                                       (throw (ex-info (str "cannot run psql (" psql "): " (ex-message e)
                                                            ". Install the PostgreSQL client or set :psql")
                                                       {:psql psql} e))))]
     (when-not (zero? exit)
       (throw (ex-info (str "psql failed (exit " exit "): " (str/trim err)) {:exit exit :err err :schema schema})))
     (let [ir (keywordize (parse-json (str/trim out)) false)]
       (when-not (:exists ir)
         (throw (ex-info (str "schema does not exist: " schema) {:schema schema})))
       (dissoc ir :exists)))))
