(ns pgmalli.impl.ir-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli.impl.ir :as ir]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(deftest reads-tables-columns-checks-and-types
  (when *db*
    (exec-sql! "CREATE TYPE mood AS ENUM ('happy', 'sad');
                CREATE DOMAIN email AS text CHECK (VALUE ~ '@');
                CREATE TABLE users (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, mood mood NOT NULL DEFAULT 'happy',
                                    age integer CHECK (age >= 0), nick varchar(40), price numeric(10,2),
                                    mail email, full_name text GENERATED ALWAYS AS (nick || '!') STORED);")
    (let [s (ir/from-db *db*)
          users (get-in s [:tables "users"])
          col #(first (filter (comp #{%} :name) (:columns users)))]
      (is (= "public" (:name s)))
      (is (re-matches #"PostgreSQL \d+(\.\d+)?" (:database_version s)) "the server version alone, the same on every machine")
      (is (= ["happy" "sad"] (get-in s [:types "mood" :enum_values])))
      (is (= {:kind "DOMAIN" :base_type "text" :not_null false :default nil :name "email"
              :constraints [{:name "email_check" :definition "CHECK (VALUE ~ '@'::text)" :is_valid true}]}
             (get-in s [:types "email"])))
      (is (= ["mood" "public"] ((juxt :data_type :type_schema) (col "mood"))))
      (is (= "'happy'::mood" (:default_value (col "mood"))))
      (is (= "ALWAYS" (:identity (col "id"))))
      (is (true? (:is_nullable (col "age"))))
      (is (= 40 (:max_length (col "nick"))))
      (is (= [10 2] ((juxt :precision :scale) (col "price"))))
      (is (= "((nick)::text || '!'::text)" (:generated_expr (col "full_name"))))
      (is (nil? (:default_value (col "full_name"))))
      (is (= "CHECK (age >= 0)" (get-in users [:constraints "users_age_check" :check_clause])))
      (is (= {:name "users_pkey" :type "PRIMARY KEY" :columns ["id"] :check_clause nil :is_valid true :nulls_not_distinct nil :references nil}
             (get-in users [:constraints "users_pkey"])))
      (is (= #{"users_age_check" "users_pkey"} (set (keys (:constraints users))))))))

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
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"schema does not exist" (ir/from-db (assoc *db* :schema "nope"))))))

(deftest missing-psql
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot run psql" (ir/from-db {:psql "/nonexistent/psql"}))))
