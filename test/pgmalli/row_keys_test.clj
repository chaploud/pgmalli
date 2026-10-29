(ns pgmalli.row-keys-test
  "row-schema's keys against the rows next.jdbc builds from PostgreSQL's JDBC driver. JVM only
   (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.set]
            [clojure.test :refer [deftest is use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            next.jdbc.optional
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
   {:select [[[:cast :nick :text]]] :from [:users]}
   {:select [:u.id :g.name :g/*] :from [[:users :u]] :left-join [[:groups :g] [:= :g.id -1]]}
   {:select [:u.nick :g.id] :from [[:users :u]] :right-join [[:groups :g] [:= :g.id -1]]}])

(deftest row-schema-keys-are-the-drivers
  (when *db*
    (exec-sql! "CREATE TABLE groups (id int PRIMARY KEY, name text NOT NULL);
                CREATE TABLE users (id int PRIMARY KEY, group_id int NOT NULL REFERENCES groups, nick text);
                INSERT INTO groups VALUES (1, 'g'); INSERT INTO users VALUES (1, 1, 'a');")
    (let [{:keys [host port db user password]} *db*
          ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:postgresql://" host ":" port "/" db) :user user :password password})
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))]
      (doseq [q queries, builder ['next.jdbc.result-set/as-maps 'next.jdbc.result-set/as-unqualified-kebab-maps 'next.jdbc.optional/as-maps]
              :let [row (first (jdbc/execute! ds (sql/format q) {:builder-fn @(requiring-resolve builder)}))
                    schema (h/row-schema registry q (h/cte-names q) (pgmalli/read-options builder))]]
        (if schema
          (do (is (clojure.set/subset? (set (keys row)) (set (map first (rest schema)))) (pr-str builder q))
              (is (clojure.set/subset? (set (for [[k p] (rest schema) :when (not (and (map? p) (:optional p)))] k)) (set (keys row)))
                  (pr-str builder q))
              (is (m/validate schema row) (pr-str builder q row)))
          (is (and (not= 'next.jdbc.result-set/as-unqualified-kebab-maps builder) (= :cast (first (ffirst (:select q)))))
              (str "only a qualified cast is untold: " (pr-str q))))))))

(deftest pg-array-is-what-the-driver-takes
  (when *db*
    (let [{:keys [host port db user password]} *db*
          ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:postgresql://" host ":" port "/" db) :user user :password password})
          s (m/schema [:pg/array :int] {:registry (pgmalli/registry {:registry {}})})]
      (with-open [c (jdbc/get-connection ds)]
        (doseq [v [(long-array [1 2]) (into-array Long [1]) (int-array []) (.createArrayOf c "bigint" (object-array [1]))
                   (object-array [1]) [1 2] #{1}]
                :let [taken? (try (jdbc/execute-one! c ["SELECT 1 AS one WHERE 1 = ANY(?)" v]) true (catch Exception _ false))]]
          (is (= taken? (m/validate s v)) (str (class v))))))))

(deftest arrays-and-json-are-what-the-driver-returns-and-takes
  (when *db*
    (exec-sql! "CREATE TABLE docs (id int PRIMARY KEY, paths text[], meta jsonb NOT NULL CHECK (jsonb_typeof(meta) = 'object'));")
    (let [{:keys [host port db user password]} *db*
          ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:postgresql://" host ":" port "/" db) :user user :password password})
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))
          ins {:insert-into :docs :values [{:id :?id :paths :?paths :meta [:cast :?meta :jsonb]}]}
          args (h/arg-types registry ins {:write {:array [:pg/array]}})
          v (into-array String ["a" "b"])]
      (is (m/validate (:paths args) v {:registry registry}) "a String[] for a text[] column")
      (is (not (m/validate (:paths args) ["a"] {:registry registry})) "a vector the driver refuses")
      (jdbc/execute! ds (sql/format ins {:params {:id 1 :paths v :meta "{\"a\": 1}"}}))
      (let [q {:select [:paths :meta] :from [:docs]}
            row (first (jdbc/execute! ds (sql/format q) {:builder-fn next.jdbc.result-set/as-unqualified-maps}))]
        (is (m/validate (h/row-schema registry q #{}) row) (pr-str (update-vals row class)))))))
