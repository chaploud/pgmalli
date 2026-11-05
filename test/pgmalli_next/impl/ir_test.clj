(ns pgmalli-next.impl.ir-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli-next.impl.ir :as ir]
            [pgmalli-next.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(deftest reads-tables-columns-keys-and-types-and-no-check
  (when *db*
    (exec-sql! "CREATE TYPE mood AS ENUM ('happy', 'sad');
                CREATE DOMAIN email AS text CHECK (VALUE ~ '@');
                CREATE SCHEMA elsewhere; CREATE DOMAIN elsewhere.code AS varchar(3) NOT NULL DEFAULT 'abc';
                CREATE DOMAIN work_code AS elsewhere.code; CREATE DOMAIN feel AS mood; CREATE DOMAIN codes AS varchar(2)[];
                CREATE TABLE users (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, mood mood NOT NULL DEFAULT 'happy',
                                    age integer CHECK (age >= 0), nick varchar(40), price numeric(10,2),
                                    mail email, full_name text GENERATED ALWAYS AS (nick || '!') STORED,
                                    code work_code, codes work_code[], f feel, cs codes);")
    (let [s (ir/from-db *db*)
          users (get-in s [:tables "users"])
          col #(first (filter (comp #{%} :name) (:columns users)))]
      (is (= "public" (:name s)))
      (is (re-matches #"PostgreSQL \d+(\.\d+)?" (:database_version s)) "the server version alone, the same on every machine")
      (is (= ["happy" "sad"] (get-in s [:types "mood" :enum_values])))
      (is (= {:data_type "text" :domain "email" :type_schema "pg_catalog" :is_nullable true} (select-keys (col "mail") [:data_type :domain :type_schema :is_nullable])))
      (is (= {:data_type "character varying" :domain "work_code" :is_nullable false :default_value "'abc'::character varying" :max_length 3}
             (select-keys (col "code") [:data_type :domain :is_nullable :default_value :max_length]))
          "a domain over another schema's domain: the base type under both, the NOT NULL and DEFAULT of the one under it")
      (is (= {:data_type "character varying[]" :domain "work_code[]" :is_nullable true :default_value nil :max_length 3}
             (select-keys (col "codes") [:data_type :domain :is_nullable :default_value :max_length]))
          "an array of a domain takes neither its NOT NULL nor its DEFAULT")
      (is (= ["mood" "feel"] ((juxt :data_type :domain) (col "f"))) "a domain over an enum is of the enum")
      (is (= ["character varying[]" "codes" 2] ((juxt :data_type :domain :max_length) (col "cs"))) "a domain over an array is no array of a domain")
      (is (= #{"mood"} (set (keys (:types s)))) "the types are the enum types")
      (is (= ["mood" "public"] ((juxt :data_type :type_schema) (col "mood"))))
      (is (= "'happy'::mood" (:default_value (col "mood"))))
      (is (= "ALWAYS" (:identity (col "id"))))
      (is (true? (:is_nullable (col "age"))))
      (is (= 40 (:max_length (col "nick"))))
      (is (= "((nick)::text || '!'::text)" (:generated_expr (col "full_name"))))
      (is (nil? (:default_value (col "full_name"))))
      (is (= {:name "users_pkey" :type "PRIMARY KEY" :columns ["id"] :is_valid true :deferrable false :is_enforced true :nulls_not_distinct nil :references nil :exclude nil}
             (get-in users [:constraints "users_pkey"])))
      (is (= #{"users_pkey"} (set (keys (:constraints users)))) "a CHECK is not read"))))

(deftest views-and-materialized-views
  (when *db*
    (exec-sql! "CREATE TABLE t (id int PRIMARY KEY, name text NOT NULL);
                CREATE VIEW v AS SELECT id, name, length(name) AS len FROM t;
                CREATE MATERIALIZED VIEW mv AS SELECT id FROM t;")
    (let [tables (:tables (ir/from-db *db*))]
      (is (= ["TABLE" "VIEW" "MATERIALIZED VIEW"] (map #(get-in tables [% :kind]) ["t" "v" "mv"])))
      (is (= [["id" "integer" true] ["name" "text" true] ["len" "integer" true]]
             (map (juxt :name :data_type :is_nullable) (get-in tables ["v" :columns]))))
      (is (= {} (get-in tables ["v" :constraints]))))))

(deftest unique-and-foreign-keys
  (when *db*
    (exec-sql! "CREATE TABLE groups (id int PRIMARY KEY, code text UNIQUE, UNIQUE (id, code));
                CREATE TABLE members (id int, group_id int REFERENCES groups (id), code text, tag text,
                                      CONSTRAINT members_uniq UNIQUE (group_id, code),
                                      CONSTRAINT members_tag_key UNIQUE NULLS NOT DISTINCT (tag),
                                      CONSTRAINT members_group_code_fkey FOREIGN KEY (group_id, code) REFERENCES groups (id, code) MATCH FULL);")
    (let [members (get-in (ir/from-db *db*) [:tables "members" :constraints])]
      (is (= ["group_id" "code"] (get-in members ["members_uniq" :columns])))
      (is (false? (get-in members ["members_uniq" :nulls_not_distinct])))
      (is (true? (get-in members ["members_tag_key" :nulls_not_distinct])))
      (is (= {:match "SIMPLE" :schema "public" :table "groups" :columns ["id"]} (get-in members ["members_group_id_fkey" :references])))
      (is (= {:match "FULL" :schema "public" :table "groups" :columns ["id" "code"]} (get-in members ["members_group_code_fkey" :references]))))))

(deftest skips-partition-children-and-qualifies-foreign-types
  (when *db*
    (exec-sql! "CREATE SCHEMA other; CREATE TYPE other.color AS ENUM ('r');
                CREATE TABLE p (id int, c other.color) PARTITION BY RANGE (id);
                CREATE TABLE p_1 PARTITION OF p FOR VALUES FROM (0) TO (10);")
    (let [s (ir/from-db *db*)]
      (is (contains? (:tables s) "p"))
      (is (not (contains? (:tables s) "p_1")))
      (is (= "other.color" (:data_type (first (filter (comp #{"c"} :name) (get-in s [:tables "p" :columns])))))))))

(deftest unknown-schema
  (when *db*
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema \"nope\" does not exist in the database; check :schemas" (ir/from-db (assoc *db* :schema "nope"))))
    (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^schema \"pubic\" does not exist in the database; check :schemas; did you mean: \"public\"$"
                                  (ir/from-db (assoc *db* :schema "pubic"))))]
      (is (= ["public"] (:did-you-mean (ex-data e)))))))

(deftest missing-psql
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot run psql \(/nonexistent/psql\).*install the PostgreSQL client" (ir/from-db {:psql "/nonexistent/psql"})))]
    (is (= :pgmalli.error/psql-missing (:pgmalli/error (ex-data e))))
    (is (instance? java.io.IOException (ex-cause e)) "the cause is kept")))

(deftest psql-that-cannot-connect-names-the-target-on-one-line
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^cannot connect to localhost:1/nope with psql: [^\n]*; check :db"
                                (ir/from-db {:host "localhost" :port 1 :dbname "nope" :user "u" :password "s3cret"})))]
    (is (= :pgmalli.error/db-unavailable (:pgmalli/error (ex-data e))))
    (is (not (str/includes? (str (ex-message e) (pr-str (ex-data e))) "s3cret")))))

(deftest jdbc-that-cannot-connect-names-the-target-and-keeps-the-cause-without-the-url
  (when-not (System/getProperty "babashka.version")
    (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^cannot connect to localhost:1/nope: .*; check :db :jdbc-url"
                                  (ir/from-db {:jdbc-url "jdbc:postgresql://localhost:1/nope?password=s3cret" :user "u" :password "s3cret"})))]
      (is (= :pgmalli.error/db-unavailable (:pgmalli/error (ex-data e))))
      (is (= :cognitect.anomalies/unavailable (:cognitect.anomalies/category (ex-data e))))
      (is (some? (.getSQLState (ex-cause e))) "the cause keeps the SQLState")
      (is (not (str/includes? (pr-str (Throwable->map e)) "s3cret")) "nor the cause carries the password")))
  (when-not (System/getProperty "babashka.version")
    (let [ds ((requiring-resolve 'next.jdbc/get-datasource) {:jdbcUrl "jdbc:nope://u:s3cret@localhost:1/x"})
          e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^cannot connect through :db :datasource"
                                  (ir/from-db {:datasource ds})))]
      (is (not (str/includes? (pr-str (Throwable->map e)) "s3cret")) "a DataSource's URL is not printed either"))))

(deftest jdbc-reads-what-psql-reads
  ;; babashka has no java.sql
  (when (and *db* (not (System/getProperty "babashka.version")))
    (exec-sql! "CREATE SCHEMA \"o'dd\"\"s\"; CREATE TABLE \"o'dd\"\"s\".t (id int PRIMARY KEY CHECK (id > 0), name text);")
    (let [{:keys [host port dbname user password]} *db*
          jdbc {:jdbc-url (str "jdbc:postgresql://" host ":" port "/" dbname) :user user :password password :schema "o'dd\"s"}]
      (is (= (ir/from-db (assoc *db* :schema "o'dd\"s")) (ir/from-db jdbc)))
      (is (= ["id" "name"] (map :name (get-in (ir/from-db jdbc) [:tables "t" :columns])))))))

(deftest a-db-map-is-one-way-to-the-database
  (doseq [[db re] [[{:jdbc_url "x"} #"do not go with psql"]
                   [{:jdbc-url "x" :host "h"} #"do not go with jdbc"]
                   [{:datasource :ds :user "u"} #"do not go with datasource"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo re (ir/from-db db)) (pr-str db))))

(deftest the-database-name-is-dbname-and-the-old-key-names-it
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^:db's :db is no longer read: the database name is :dbname"
                                (ir/from-db {:host "h" :db "app"})))]
    (is (= [:dbname] (:did-you-mean (ex-data e))))
    (is (not (str/includes? (pr-str (ex-data e)) "app")))))

(deftest a-next-jdbc-db-spec-says-to-drop-its-dbtype
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^:db keys \[:dbtype\] are next.jdbc's, which pgmalli does not read .*; drop them"
                        (ir/from-db {:dbtype "postgresql" :dbname "app" :host "h" :psql "/nonexistent/psql"}))))
