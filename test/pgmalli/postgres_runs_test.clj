(ns pgmalli.postgres-runs-test
  "Queries and values PostgreSQL runs through pgjdbc, each run (and rolled back), which pgmalli
   does not refuse. JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
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
                         CREATE DOMAIN mail AS citext CHECK (VALUE ~ '@');
                         CREATE TABLE accounts (id bigserial PRIMARY KEY, email text NOT NULL, group_id int,
                           status status NOT NULL DEFAULT 'draft', amount numeric(10,2), plain numeric,
                           flags varbit, created timestamptz, tags status[], name citext, nick ci, mail mail);
                         INSERT INTO accounts (email, mail, name, nick) VALUES ('a', 'a@x', 'n', 'k'), ('b', NULL, NULL, NULL);
                         CREATE DOMAIN d3 AS varchar(3);
                         CREATE DOMAIN dpos AS int CHECK (VALUE BETWEEN 1 AND 9);
                         CREATE TABLE dd (a d3, n dpos);
                         INSERT INTO dd VALUES ('abc', 1);
                         CREATE TABLE uq (id int PRIMARY KEY, code text UNIQUE, a int, b int, UNIQUE (a, b));
                         INSERT INTO uq VALUES (1, NULL, 1, NULL), (2, NULL, 1, NULL);
                         CREATE TABLE arr (xs int[], cs citext[]);
                         INSERT INTO arr VALUES ('{1,NULL}', '{a,NULL}');
                         CREATE TABLE typed (id int, i int, b bigint, n numeric, u uuid, d date, ts timestamp, tz timestamptz,
                           ok boolean, j jsonb, s status, p dpos, xs int[], ip inet, t time, tt timetz, tts timetz[]);
                         INSERT INTO typed (id) VALUES (1);
                         CREATE SCHEMA app;
                         CREATE TABLE app.items (id serial PRIMARY KEY, status public.status);"))
           (f)))))

(defn- registry [] (pgmalli/registry (files/from-database {:db *db*} "public") (files/from-database {:db *db*} "app")))

(defn- pg-object [type value] (doto (PGobject.) (.setType type) (.setValue value)))

(defn- runs!
  "Runs q with params and opts' :format, rolled back, on a stringtype=unspecified connection
   where opts send a String :unknown, each parameter set with setObject, as next.jdbc sets it
   without a SettableParameter extension (next.jdbc.date-time, which another test may load,
   cannot set a java.sql.Time); throws what PostgreSQL refuses. Each java.sql.Array is
   read as a vector while connected (an array of citext needs the connection), as a
   ReadableColumn extension under {:read {:array :vector}} reads it."
  [q params opts]
  (with-open [con (jdbc/get-connection (datasource (cond-> *db* (= :unknown (get-in opts [:send String])) (assoc :url-params "stringtype=unspecified"))))]
    (.setAutoCommit con false)
    (try (walk/postwalk #(if (instance? java.sql.Array %) (vec (.getArray ^java.sql.Array %)) %)
                        (let [[sql & ps] (sql/format q (assoc (:format opts) :params params))]
                          (with-open [st (jdbc/prepare con [sql])]
                            (doseq [[i v] (map-indexed vector ps)] (.setObject st (int (inc i)) v))
                            (jdbc/execute! st))))
         (finally (.rollback con)))))

(defn- accepted?
  "Whether check finds no problem in q and every argument's type takes its value."
  [reg q params opts]
  (let [types (h/arg-types reg q opts)]
    (and (= [] (h/check reg q opts))
         (every? (fn [[k v]] (m/validate (get types k :any) v {:registry reg})) params))))

(defn- returned?
  "Whether query-schema's return takes the rows PostgreSQL returns for q."
  [reg q params opts]
  (m/validate (last (m/children (m/schema (h/query-schema reg [] q opts) {:registry reg}))) (runs! q params opts)))

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

(deftest an-argument-the-query-tests-for-null-takes-nil-inlined-and-not-as-a-parameter
  (when *db*
    (let [reg (registry)
          inline {:format {:inline true}}]
      (doseq [w [[:or [:= :?id nil] [:= :id :?id]] [:or [:is :?id nil] [:= :id :?id]] [:or [:= nil :?id] [:= :id :?id]]]
              :let [q {:select [:id] :from [:accounts] :where w}]]
        (testing (pr-str w)
          (is (= 2 (count (runs! q {:id nil} inline))))
          (is (accepted? reg q {:id nil} inline))
          (is (thrown-with-msg? Exception #"could not determine data type" (runs! q {:id nil} {})))
          (is (not (accepted? reg q {:id nil} {}))))))))

(deftest a-unique-column-compared-with-nil-matches-many-rows
  (when *db*
    (let [reg (registry)]
      (doseq [[q n] [[{:select [:id] :from [:uq] :where [:= :code nil]} 2]
                     [{:select [:id] :from [:uq] :where [:= nil :code]} 2]
                     [{:select [:id] :from [:uq] :where [:and [:= :a 1] [:= :b nil]]} 2]
                     [{:update :uq :set {:id [:+ :id 10]} :where [:= :code nil] :returning [:id]} 2]
                     [{:delete-from :uq :where [:= :code nil] :returning [:id]} 2]]]
        (testing (pr-str q)
          (is (= n (count (runs! q {} {}))))
          (is (returned? reg q {} {})))))))

(deftest a-set-operation-of-a-domain-and-a-literal-is-of-the-domains-base-type
  (when *db*
    (let [reg (registry)]
      (doseq [q [{:union [{:select [:a] :from [:dd]} {:select [[[:inline "abcdefg"]]]}]}
                 {:union-all [{:select [:n] :from [:dd]} {:select [[[:inline 100]]]}]}]]
        (testing (pr-str q)
          (is (returned? reg q {} {})))))))

(deftest an-array-read-as-a-vector-holds-null-elements
  (when *db*
    (let [reg (registry)
          o {:read {:array :vector}}]
      (doseq [q [{:select [:xs] :from [:arr]}
                 {:select [[[:array_agg :group_id] :g]] :from [:accounts]}
                 {:select [[[:array [1 nil]] :x]]}
                 {:select [[[:array_append :xs nil] :x]] :from [:arr]}]]
        (testing (pr-str q)
          (is (returned? reg q {} o)))))))

(deftest an-array-of-citext-or-of-a-domain-read-as-a-vector-holds-the-drivers-objects
  (when *db*
    (let [reg (registry)
          o {:read {:array :vector}}]
      (doseq [q [{:select [:cs] :from [:arr]}
                 {:select [[[:array_agg :name] :x]] :from [:accounts]}
                 {:select [[[:array_agg :nick] :x]] :from [:accounts]}
                 {:select [[[:array_agg :n] :x]] :from [:dd]}
                 {:select [[[:array_agg :a] :x]] :from [:dd]}
                 {:select [[[:array [:n]] :x]] :from [:dd]}
                 ;; array_agg of an array adds a dimension: {{1,NULL}}
                 {:select [[[:array_agg :xs] :x]] :from [:arr]}
                 {:select [[[:array_agg :cs] :x]] :from [:arr]}]]
        (testing (pr-str q)
          (is (some? (runs! q {} {})))
          (is (returned? reg q {} o))))
      (is (m/validate [:sequential (pgmalli/as-read reg :pg.public/arr o)]
                      (runs! {:select [:*] :from [:arr]} {} {})
                      {:registry reg})))))

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

(deftest a-cast-compared-leaves-out-its-domains-check-which-runs-only-where-the-plan-reaches-it
  (when *db*
    (let [reg (registry)
          q {:select [:id] :from [:accounts] :where [:and [:= :mail :?m] [:= :mail [:cast :?m :mail]]]}]
      (is (some? (runs! q {:m "a"} {})) "mail = 'a' (as text) keeps no row, so the cast is never evaluated")
      (is (accepted? reg q {:m "a"} {}))
      (is (thrown? Exception (runs! {:select [[[:cast :?m :mail] :v]]} {:m "a"} {})))
      (is (not (m/validate (:m (h/arg-types reg {:select [[[:cast :?m :mail] :v]]})) "a" {:registry reg}))))))

(deftest a-java-sql-date-time-or-timestamp-goes-to-a-date-time-or-timestamp-under-every-time
  (when *db*
    (let [reg (registry)
          ts (java.sql.Timestamp. 0)
          sd (java.sql.Date/valueOf "1990-01-02")
          st (java.sql.Time/valueOf "10:00:00")]
      (doseq [opts [{} {:time :local} {:time :instant}]
              [q params] [[{:select [:id] :from [:typed] :where [:>= :tz :?x]} {:x ts}]
                          [{:select [:id] :from [:typed] :where [:< :ts :?x]} {:x ts}]
                          [{:select [:id] :from [:typed] :where [:= :d :?x]} {:x sd}]
                          [{:select [:id] :from [:typed] :where [:< :t :?x]} {:x st}]
                          [{:select [:id] :from [:typed] :where [:= :tt :?x]} {:x st}]
                          [{:update :typed :set {:tz :?x :ts :?x} :where [:= :id 1]} {:x ts}]
                          [{:update :typed :set {:d :?x} :where [:= :id 1]} {:x sd}]
                          [{:update :typed :set {:t :?x :tt :?x} :where [:= :id 1]} {:x st}]
                          [{:update :typed :set {:tts :?x} :where [:= :id 1]} {:x (into-array java.sql.Time [st])}]
                          [{:insert-into :typed :values [{:tts :?x}]} {:x (into-array java.sql.Time [st])}]
                          [{:select [:id] :from [:typed] :where [:< [:composite :tz :id] [:composite :?c :?i]]} {:c ts :i 1}]]]
        (testing (pr-str opts q)
          (is (some? (runs! q params opts)))
          (is (accepted? reg q params opts)))))))

(deftest a-java-sql-date-time-or-timestamp-goes-untyped-to-a-column-of-any-type
  (when *db*
    (let [reg (registry)]
      (doseq [opts [{} {:time :local} {:time :instant}]
              v [(java.sql.Timestamp. 0) (java.sql.Date/valueOf "1990-01-02") (java.sql.Time/valueOf "10:00:00")]
              q [{:select [:id] :from [:accounts] :where [:= :email :?x]}
                 {:update :accounts :set {:email :?x} :where [:= :id 1]}
                 {:insert-into :accounts :values [{:email :?x}]}]]
        (testing (pr-str opts q v)
          (is (some? (runs! q {:x v} opts)))
          (is (accepted? reg q {:x v} opts)))))))

(deftest a-string-sent-untyped-goes-to-a-column-of-any-type
  (when *db*
    (let [reg (registry)
          opts {:send {String :unknown}}]
      (doseq [[c v] {:i "1" :b "1" :n "1.5" :u "00000000-0000-0000-0000-000000000000" :d "2020-01-01"
                     :tz "2020-01-01 00:00:00+00" :ok "true" :j "{\"a\": 1}" :s "live" :p "1" :xs "{1,2}" :ip "10.0.0.1"}
              q [{:select [:id] :from [:typed] :where [:= c :?x]}
                 {:update :typed :set {c :?x} :where [:= :id 1]}]]
        (testing (pr-str q v)
          (is (some? (runs! q {:x v} opts)))
          (is (accepted? reg q {:x v} opts)))))))
