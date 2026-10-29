(ns pgmalli.row-keys-test
  "row-schema's keys against the rows next.jdbc builds from PostgreSQL's JDBC driver. JVM only
   (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            next.jdbc.result-set
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.files :as files]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(def queries
  [{:select [:id [:nick :name]] :from [:users]}
   {:select [:u.id [:g.name :group_name]] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]]}
   {:union-all [{:select [:id [:nick :name]] :from [:users]} {:select [:id :name] :from [:groups]}]}
   {:select [[[:count :*]] [[:max :id] :top_id] [[:+ :id 1]] [[:coalesce :nick "x"]] [[:cast :id :bigint] :big_id] [[:upper :nick]]]
    :from [:users] :group-by [:id :nick]}
   {:select [[[:cast :nick :text]]] :from [:users]}])

(deftest row-schema-keys-are-the-drivers
  (when *db*
    (exec-sql! "CREATE TABLE groups (id int PRIMARY KEY, name text NOT NULL);
                CREATE TABLE users (id int PRIMARY KEY, group_id int NOT NULL REFERENCES groups, nick text);
                INSERT INTO groups VALUES (1, 'g'); INSERT INTO users VALUES (1, 1, 'a');")
    (let [{:keys [host port db user password]} *db*
          ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:postgresql://" host ":" port "/" db) :user user :password password})
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))]
      (doseq [q queries, builder ['next.jdbc.result-set/as-maps 'next.jdbc.result-set/as-unqualified-kebab-maps]
              :let [row (first (jdbc/execute! ds (sql/format q) {:builder-fn @(requiring-resolve builder)}))
                    schema (h/row-schema registry q (h/cte-names q) (pgmalli/read-options builder))]]
        (if schema
          (do (is (= (set (keys row)) (set (map first (rest schema)))) (pr-str builder q))
              (is (m/validate schema row) (pr-str builder q row)))
          (is (and (= 'next.jdbc.result-set/as-maps builder) (= :cast (first (ffirst (:select q)))))
              (str "only a qualified cast is untold: " (pr-str q))))))))
