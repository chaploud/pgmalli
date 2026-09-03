(ns pgmalli.impl.ir
  "Reads the structure of one schema from pg_catalog through psql.

   Connection settings are psql's own (PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD,
   PGSSLMODE, ~/.pgpass); entries in the db map override them for the call.

   Result shape:
     {:name \"public\" :database_version \"PostgreSQL 17.6\"
      :tables {\"users\" {:name \"users\" :kind \"TABLE\"
                        :columns [{:name :position :data_type :type_schema :is_nullable
                                   :default_value :generated_expr :identity :max_length :precision :scale}]
                        :constraints {\"users_age_check\" {:name :type :columns :check_clause :is_valid :nulls_not_distinct :references}}}}
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
     (let [ir (keywordize (json/parse (str/trim out)) false)]
       (when-not (:exists ir)
         (throw (ex-info (str "schema does not exist: " schema) {:schema schema})))
       (dissoc ir :exists)))))
