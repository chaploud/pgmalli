(ns pgmalli.postgres-runs-test
  "Queries and values PostgreSQL runs through pgjdbc, each run (and rolled back), which pgmalli
   does not refuse. JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.files :as files]
            [pgmalli.test-db :refer [*db* datasource exec-sql! with-postgres]])
  (:import (org.postgresql.util PGobject)))

(use-fixtures :once
  (fn [f]
    (with-postgres
      #(do (when *db*
             (exec-sql! "CREATE EXTENSION citext;
                         CREATE TYPE status AS ENUM ('draft', 'live');
                         CREATE DOMAIN ci AS citext;
                         CREATE TABLE accounts (id bigserial PRIMARY KEY, email text NOT NULL, group_id int,
                           status status NOT NULL DEFAULT 'draft', amount numeric(10,2), plain numeric,
                           flags varbit, created timestamptz, tags status[], name citext, nick ci);
                         CREATE SCHEMA app;
                         CREATE TABLE app.items (id serial PRIMARY KEY, status public.status);"))
           (f)))))

(defn- registry [] (pgmalli/registry (files/from-database {:db *db*} "public") (files/from-database {:db *db*} "app")))

(defn- pg-object [type value] (doto (PGobject.) (.setType type) (.setValue value)))

(defn- runs!
  "Runs q with params and opts' :format, rolled back; throws what PostgreSQL refuses."
  [q params opts]
  (with-open [con (jdbc/get-connection (datasource))]
    (.setAutoCommit con false)
    (try (jdbc/execute! con (sql/format q (assoc (:format opts) :params params)))
         (finally (.rollback con)))))

(defn- accepted?
  "Whether check finds no problem in q and every argument's type takes its value."
  [reg q params opts]
  (let [types (h/arg-types reg q opts)]
    (and (= [] (h/check reg q opts))
         (every? (fn [[k v]] (m/validate (get types k :any) v {:registry reg})) params))))

(deftest what-postgresql-runs-pgmalli-takes
  (when *db*
    (let [reg (registry)
          live (pg-object "status" "live")]
      (doseq [[q params] [[{:select [:id] :from [:accounts] :where [:= :status :?s]} {:s live}]
                          [{:select [:id] :from [:accounts] :where [:in :status :?s]} {:s [live]}]
                          [{:update :accounts :set {:status :?s} :where [:= :id 1]} {:s live}]
                          [{:update :accounts :set {:flags :?f} :where [:= :id 1]} {:f (pg-object "varbit" "101")}]
                          [{:select [:id] :from [:accounts] :where [:= :status [:any :?s]]} {:s (pg-object "status[]" "{draft,live}")}]
                          [{:select [:id] :from [:accounts] :where [:= [:round :amount] :?n]} {:n 1}]
                          [{:select [:id] :from [:accounts] :where [:= [:coalesce :amount 0] :?n]} {:n 1}]
                          [{:select [:id] :from [:accounts] :where [:= [:- :amount] :?n]} {:n 1}]
                          [{:select [:a.id :b.id] :from [[:accounts :a]] :join-by [:left [[:accounts :b] [:= :b.id :a.id]]]} {}]
                          [{:table :accounts} {}]
                          [{:union [{:table :accounts} {:table :accounts}]} {}]]]
        (testing (pr-str q)
          (is (some? (runs! q params {})))
          (is (accepted? reg q params {})))))))

(deftest a-named-argument-is-left-to-arg-types-and-a-lifted-string-is-a-literal
  (when *db*
    (let [reg (registry)
          q {:select [:id] :from [:accounts] :where [:= :status [:lift "live"]]}]
      (is (thrown? Exception (runs! q {} {})) "PostgreSQL has no status = varchar")
      (is (= [:uncast-string] (map :kind (h/check reg q {}))))
      (is (some? (runs! q {} {:format {:inline true}})))
      (is (= [] (h/check reg q {:format {:inline true}}))))))

(deftest a-written-row-takes-a-subquery-and-the-keys-honeysql-writes-to-a-column
  (when *db*
    (let [reg (registry)
          v (h/values-schema reg :accounts)
          s (h/set-schema reg :accounts)
          sub {:select [[[:min :id]]] :from [:accounts]}]
      (doseq [[schema q row] [[v :insert-into {:email "a" :group_id sub}]
                              [v :insert-into {:accounts/email "a"}]
                              [v :insert-into {"email" "a"}]
                              [s :update {:group_id sub}]
                              [s :update {:accounts/group_id 1 "email" "b"}]]]
        (testing (pr-str row)
          (is (some? (runs! (if (= :update q) {:update :accounts :set row} {:insert-into :accounts :values [row]}) {} {})))
          (is (m/validate schema row {:registry reg})))))))

(deftest an-enum-of-another-schema-is-the-columns-type
  (when *db*
    (let [reg (registry)
          q {:select [:id] :from [:app.items] :where [:= :status "live"]}]
      (is (thrown? Exception (runs! q {} {})))
      (is (= [{:kind :uncast-string :column :status :value "live" :type "public.status"}] (h/check reg q)))
      (is (accepted? reg {:select [:id] :from [:app.items] :where [:= :status :?s]} {:s (pg-object "status" "live")} {})))))

(deftest a-citext-column-takes-what-goes-untyped
  (when *db*
    (let [reg (registry)
          o {:send {java.time.LocalDate :unknown}}]
      (doseq [c [:name :nick]]
        (is (m/validate (:x (h/arg-types reg {:select [:id] :from [:accounts] :where [:= c :?x]} o))
                        (java.time.LocalDate/of 2020 1 1) {:registry reg})
            (str c " = '2020-01-01'")))
      (is (some? (runs! {:select [:id] :from [:accounts] :where [:= :name [:inline "2020-01-01"]]} {} {}))))))
