(ns pgmalli-next.postgres-runs-test
  "Queries and values PostgreSQL runs through pgjdbc, each run (and rolled back), which pgmalli
   does not refuse. JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.files :as files]
            [pgmalli-next.test-db :refer [*db* datasource exec-sql! with-postgres]])
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
                         CREATE DOMAIN dlist AS int CHECK (VALUE IN (1, 2));
                         CREATE TABLE dd (a d3, n dpos, l dlist);
                         INSERT INTO dd VALUES ('abc', 1, 2);
                         CREATE TABLE uq (id int PRIMARY KEY, code text UNIQUE, a int, b int, UNIQUE (a, b));
                         INSERT INTO uq VALUES (1, NULL, 1, NULL), (2, NULL, 1, NULL);
                         CREATE TABLE arr (xs int[], cs citext[]);
                         INSERT INTO arr VALUES ('{1,NULL}', '{a,NULL}');
                         CREATE TABLE typed (id int, i int, b bigint, n numeric, f double precision, fs double precision[], u uuid, d date, ts timestamp, tz timestamptz,
                           ok boolean, j jsonb, s status, p dpos, xs int[], ip inet, t time, tt timetz, tts timetz[], c1 char(1));
                         INSERT INTO typed (id) VALUES (1);
                         CREATE TABLE ul (v varchar(5), n int CHECK (n BETWEEN 1 AND 9), j jsonb CHECK (jsonb_typeof(j) = 'object'), x numeric);
                         CREATE TABLE positions (id bigserial PRIMARY KEY, group_id bigint NOT NULL, name varchar(100) NOT NULL,
                           sort_order int, created_at timestamptz DEFAULT now());
                         INSERT INTO positions (group_id, name) VALUES (1, 'a');
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
    (try (jdbc/execute! con (sql/format q (assoc (:format opts) :params params)) {:builder-fn rs/as-unqualified-maps})
         (finally (.rollback con)))))

(defn- accepted?
  "Whether analyze finds no problem in q and every argument's type takes its value."
  [reg q params opts]
  (let [{types :args :keys [problems]} (h/analyze reg q opts)]
    (and (= [] problems)
         (every? (fn [[k v]] (m/validate (get types k :any) v {:registry reg})) params))))

(defn- returned?
  "Whether query-schema's return takes the rows PostgreSQL returns for q."
  [reg q params opts]
  (m/validate (last (m/children (m/schema (h/query-schema reg [] q opts) {:registry reg}))) (runs! q params opts)))

(deftest what-postgresql-runs-pgmalli-takes
  (when *db*
    (let [reg (registry)]
      (doseq [[q params] [[{:select [:id] :from [:accounts] :where [:= :status :?s]} {:s :live}]
                          [{:select [:id] :from [:accounts] :where [:in :status :?s]} {:s [:live]}]
                          [{:update :accounts :set {:status :?s} :where [:= :id 1]} {:s :live}]
                          [{:update :accounts :set {:flags :?f} :where [:= :id 1]} {:f (pg-object "varbit" "101")}]
                          [{:select [:id] :from [:accounts] :where [:= :status [:any :?s]]} {:s (pg-object "status[]" "{draft,live}")}]
                          [{:select [:id] :from [:accounts] :where [:= [:round :amount] :?n]} {:n 1}]
                          [{:select [:id] :from [:accounts] :where [:= [:coalesce :amount 0] :?n]} {:n 1}]
                          [{:select [:id] :from [:accounts] :where [:= [:- :amount] :?n]} {:n 1}]
                          [{:select [:a.id [:b.id :b_id]] :from [[:accounts :a]] :join-by [:left [[:accounts :b] [:= :b.id :a.id]]]} {}]
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
    (let [reg (registry)]
      (doseq [q [{:select [:xs] :from [:arr]}
                 {:select [[[:array_agg :group_id] :g]] :from [:accounts]}
                 {:select [[[:array [1 nil]] :x]]}
                 {:select [[[:array_append :xs nil] :x]] :from [:arr]}]]
        (testing (pr-str q)
          (is (returned? reg q {} {})))))))

(deftest an-array-of-citext-or-of-a-domain-holds-the-drivers-objects
  (when *db*
    (let [reg (registry)]
      (doseq [q [{:select [:cs] :from [:arr]}
                 {:select [[[:array_agg :name] :x]] :from [:accounts]}
                 {:select [[[:array_agg :nick] :x]] :from [:accounts]}
                 {:select [[[:array_agg :n] :x]] :from [:dd]}
                 {:select [[[:array_agg :a] :x]] :from [:dd]}
                 {:select [[[:array_agg :l] :x]] :from [:dd]}
                 {:select [[[:array [:l]] :x]] :from [:dd]}
                 {:select [[[:array [:l :l]] :x]] :from [:dd]}
                 {:select [[[:array_agg [:coalesce :l :l]] :x]] :from [:dd]}
                 {:select [[[:array [[:case [:= :l 1] :l :else :l]]] :x]] :from [:dd]}
                 {:select [[[:array [:n]] :x]] :from [:dd]}
                 ;; array_agg of an array adds a dimension: {{1,NULL}}
                 {:select [[[:array_agg :xs] :x]] :from [:arr]}
                 {:select [[[:array_agg :cs] :x]] :from [:arr]}]]
        (testing (pr-str q)
          (is (some? (runs! q {} {})))
          (is (returned? reg q {} {}))))
      (is (m/validate [:sequential (pgmalli/as-read reg :pg.public/arr {})]
                      (runs! {:select [:*] :from [:arr]} {} {})
                      {:registry reg})))))

(deftest a-named-argument-is-left-to-arg-types-and-a-lifted-string-is-a-literal
  (when *db*
    (let [reg (registry)
          q {:select [:id] :from [:accounts] :where [:= :status [:lift "live"]]}]
      (is (thrown? Exception (runs! q {} {})) "PostgreSQL has no status = varchar")
      (is (= [:uncast-string] (map :kind (:problems (h/analyze reg q)))))
      (is (some? (runs! q {} {:format {:inline true}})))
      (is (= [] (:problems (h/analyze reg q {:format {:inline true}})))))))

(deftest an-enum-of-another-schema-is-the-columns-type
  (when *db*
    (let [reg (registry)
          q {:select [:id] :from [:app.items] :where [:= :status "live"]}]
      (is (thrown? Exception (runs! q {} {})))
      (is (= [{:kind :uncast-string :column :status :value "live" :type "public.status"}] (:problems (h/analyze reg q))))
      (is (accepted? reg {:select [:id] :from [:app.items] :where [:= :status :?s]} {:s :live} {})))))

(deftest a-citext-column-takes-what-goes-untyped
  (when *db*
    (let [reg (registry)]
      (doseq [c [:name :nick]
              :let [q {:select [:id] :from [:accounts] :where [:= c :?x]}]]
        (is (m/validate (:x (:args (h/analyze reg q))) :a {:registry reg}) (str c " = 'a'"))
        (is (some? (runs! q {:x :a} {})))))))

(deftest a-cast-compared-leaves-out-its-domains-check-which-runs-only-where-the-plan-reaches-it
  (when *db*
    (let [reg (registry)
          q {:select [:id] :from [:accounts] :where [:and [:= :mail :?m] [:= :mail [:cast :?m :mail]]]}]
      (is (some? (runs! q {:m "a"} {})) "mail = 'a' (as text) keeps no row, so the cast is never evaluated")
      (is (accepted? reg q {:m "a"} {}))
      (is (thrown? Exception (runs! {:select [[[:cast :?m :mail] :v]]} {:m "a"} {})))
      (is (not (m/validate (:m (:args (h/analyze reg {:select [[[:cast :?m :mail] :v]]}))) "a" {:registry reg}))))))

(deftest a-date-time-or-timestamp-goes-to-a-date-time-or-timestamp
  (when *db*
    (let [reg (registry)
          ts (java.sql.Timestamp. 0)
          sd (java.sql.Date/valueOf "1990-01-02")
          st (java.sql.Time/valueOf "10:00:00")
          ld (java.time.LocalDate/of 1990 1 2)]
      (doseq [[q params] [[{:select [:id] :from [:typed] :where [:>= :tz :?x]} {:x ts}]
                          [{:select [:id] :from [:typed] :where [:< :ts :?x]} {:x ts}]
                          [{:select [:id] :from [:typed] :where [:= :d :?x]} {:x sd}]
                          [{:select [:id] :from [:typed] :where [:< :t :?x]} {:x st}]
                          [{:select [:id] :from [:typed] :where [:= :tt :?x]} {:x st}]
                          [{:select [:id] :from [:typed] :where [:< :tz :?x]} {:x ld}]
                          [{:update :typed :set {:tz :?x :ts :?x} :where [:= :id 1]} {:x ts}]
                          [{:update :typed :set {:d :?x :ts :?x} :where [:= :id 1]} {:x ld}]
                          [{:update :typed :set {:d :?x} :where [:= :id 1]} {:x sd}]
                          [{:update :typed :set {:t :?x :tt :?x} :where [:= :id 1]} {:x st}]
                          [{:update :typed :set {:tts :?x} :where [:= :id 1]} {:x (into-array java.sql.Time [st])}]
                          [{:insert-into :typed :values [{:tts :?x}]} {:x (into-array java.sql.Time [st])}]
                          [{:select [:id] :from [:typed] :where [:< [:composite :tz :id] [:composite :?c :?i]]} {:c ts :i 1}]]]
        (testing (pr-str q params)
          (is (some? (runs! q params {})))
          (is (accepted? reg q params {})))))))

(deftest a-character-goes-to-a-character-column-alone
  (when *db*
    (let [reg (registry)]
      (doseq [[q taken?] [[{:select [:id] :from [:typed] :where [:= :c1 :?x]} true]
                          [{:update :typed :set {:c1 :?x} :where [:= :id 1]} true]
                          [{:select [:id] :from [:accounts] :where [:= :email :?x]} false]
                          [{:update :accounts :set {:email :?x} :where [:= :id 1]} false]]]
        (testing (pr-str q)
          (is (some? (runs! q {:x \a} {})))
          (is (= taken? (accepted? reg q {:x \a} {}))))))))

(deftest a-big-decimal-or-its-array-goes-to-a-float-column-and-a-big-int-into-a-populated-integer
  (when *db*
    (let [reg (registry)]
      (doseq [[q x] [[{:update :typed :set {:f :?x} :where [:= :id 1]} 1.5M]
                     [{:update :typed :set {:fs :?x} :where [:= :id 1]} (into-array BigDecimal [1.5M])]
                     [{:update :typed :set {:fs :?x} :where [:= :id 1]} (into-array Double [1.5])]
                     [{:select [:r.i] :from [[[:jsonb_populate_record [:cast nil :typed] :?x] :r]]} {:i 2N}]]]
        (testing (pr-str q x)
          (is (some? (runs! q {:x x} {})))
          (is (accepted? reg q {:x x} {})))))))

(deftest a-date-or-time-sent-untyped-to-a-text-column-is-refused-though-postgresql-takes-it
  (when *db*
    (let [reg (registry)]
      (doseq [v [(java.sql.Timestamp. 0) (java.sql.Date/valueOf "1990-01-02") (java.sql.Time/valueOf "10:00:00")
                 (java.time.Instant/ofEpochSecond 0) (java.time.LocalDate/of 1990 1 2) (java.time.LocalDateTime/of 1990 1 2 3 4)
                 (java.util.Date. 0)]
              q [{:select [:id] :from [:accounts] :where [:= :email :?x]}
                 {:update :accounts :set {:email :?x} :where [:= :id 1]}
                 {:insert-into :accounts :values [{:email :?x}]}]]
        (testing (pr-str q v)
          (is (some? (runs! q {:x v} {})))
          (is (not (accepted? reg q {:x v} {}))))))))

(deftest a-keyword-goes-where-a-labels-text-is-read-not-where-a-number-or-dates-is
  (when *db*
    (let [reg (registry)]
      (doseq [[c v] {:s :live :c1 :a}
              q [{:select [:id] :from [:typed] :where [:= c :?x]}
                 {:update :typed :set {c :?x} :where [:= :id 1]}]]
        (testing (pr-str q v)
          (is (some? (runs! q {:x v} {})))
          (is (accepted? reg q {:x v} {}))))
      (doseq [[c text] {:i "1" :b "1" :n "1.5" :d "2020-01-01" :u "00000000-0000-0000-0000-000000000000" :ok "true"}
              :let [v (keyword text)]
              q [{:select [:id] :from [:typed] :where [:= c :?x]}
                 {:update :typed :set {c :?x} :where [:= :id 1]}]]
        (testing (str "stricter than PostgreSQL: " (pr-str q v))
          (is (some? (runs! q {:x v} {})))
          (is (not (accepted? reg q {:x v} {}))))))))

(deftest an-array-cast-written-raw-types-its-argument-and-the-unnests-columns
  (when *db*
    (let [reg (registry)
          pair {:select [:r.id :r.name] :from [[[:unnest [:cast :?ids [:raw "bigint[]"]] [:cast :?names [:raw "text[]"]]] [:r {:columns [:id :name]}]]]}]
      (is (= [:map [:id [:maybe [:int {:pg/type "bigint"}]]] [:name [:maybe [:string {:pg/type "text"}]]]] (:row (h/analyze reg pair))))
      (doseq [[q params] [[pair {:ids (long-array [1 2]) :names (into-array String ["a" "b"])}]
                          [pair {:ids "{1,2}" :names "{a,b}"}]
                          [{:select [:id] :from [:accounts] :where [:= :id [:any [:cast :?xs [:raw "bigint[]"]]]]} {:xs (long-array [1])}]
                          [{:select [:id] :from [:accounts] :where [:= :id [:any [:cast :?xs [:raw "bigint[]"]]]]} {:xs "{1}"}]
                          [{:select [[[:cast :?j [:raw "jsonb[]"]] :j]]} {:j "{\"{}\"}"}]
                          [{:select [:id] :from [:accounts] :where [:= :tags [:cast :?t [:raw "status[]"]]]} {:t (into-array String ["live"])}]
                          [{:select [[[:cast :?t [:raw "status[]"]] :t]]} {:t "{draft}"}]]]
        (testing (pr-str q params)
          (is (some? (runs! q params {})))
          (is (accepted? reg q params {}))
          (is (returned? reg q params {})))))))

(deftest an-array-cast-casts-each-element-as-a-cast-to-the-element-type
  (when *db*
    (let [reg (registry)
          cast-to #(hash-map :select [[[:cast :?x [:raw %]] :x]])]
      (doseq [[t x] [["bigint[]" (into-array Long [1 nil])] ["bigint[]" (into-array String ["1"])] ["bigint[]" (into-array Integer [(int 1)])]
                     ["jsonb[]" (into-array String ["{}"])] ["jsonb[]" "{\"{}\"}"]
                     ["status[]" (into-array String ["live" nil])] ["dpos[]" (into-array Integer [(int 9) nil])]]]
        (testing (str t " " (pr-str (if (string? x) x (vec x))))
          (is (some? (runs! (cast-to t) {:x x} {})))
          (is (accepted? reg (cast-to t) {:x x} {}))))
      (doseq [[t x] [["bigint[]" (into-array Boolean [true])] ["jsonb[]" (long-array [1])]
                     ["status[]" (into-array String ["gone"])] ["dpos[]" (into-array Integer [(int 10)])]]]
        (testing (str t " " (pr-str (vec x)))
          (is (thrown? Exception (runs! (cast-to t) {:x x} {})))
          (is (not (accepted? reg (cast-to t) {:x x} {}))))))))

(deftest an-unnest-of-an-array-cast-to-the-columns-type-holds-each-element-to-the-column
  (when *db*
    (let [reg (registry)
          star (fn [c t] {:insert-into [[:ul [c]] {:select [:*] :from [[[:unnest [:cast :?xs [:raw t]]] [:r {:columns [c]}]]]}]})
          items (fn [c t] {:insert-into [[:ul [c]] {:select [(keyword (str "r." (name c)))] :from [[[:unnest [:cast :?xs [:raw t]]] [:r {:columns [c]}]]]}]})]
      (doseq [q [star items]
              [c t ok bad] [[:v "varchar[]" (into-array String ["abc" nil]) (into-array String ["abcdef"])]
                            [:n "int[]" (into-array Integer [(int 9)]) (into-array Integer [(int 10)])]
                            [:j "jsonb[]" (into-array String ["{}"]) (long-array [1])]]]
        (testing (pr-str (q c t))
          (is (some? (runs! (q c t) {:xs ok} {})))
          (is (accepted? reg (q c t) {:xs ok} {}))
          (is (thrown? Exception (runs! (q c t) {:xs bad} {})))
          (is (not (accepted? reg (q c t) {:xs bad} {})))))
      (let [heads (set (keep #(when (vector? %) (first %)) (tree-seq vector? seq (get-in (h/analyze reg (star :j "jsonb[]")) [:args :xs]))))]
        (is (and (heads :map) (not (heads :map-of))) "the column's CHECK shapes a map element"))
      (let [dropping (assoc-in (star :v "varchar[]") [:insert-into 1 :where] [:= :r.v "abc"])]
        (is (some? (runs! dropping {:xs (into-array String ["abcdef"])} {})))
        (is (accepted? reg dropping {:xs (into-array String ["abcdef"])} {}) "a row the WHERE may drop is not held to the length"))
      (is (= (get-in (h/analyze reg {:select [[[:cast :?xs [:raw "bigint[]"]] :x]]}) [:args :xs])
             (get-in (h/analyze reg (star :x "bigint[]")) [:args :xs]))
          "into a column of another type, the cast's type"))))

(deftest an-aggregate-anywhere-in-a-select-without-group-by-makes-one-row-which-having-may-drop
  (when *db*
    (let [reg (registry)]
      (doseq [[q rows n] [[{:select [[[:coalesce [:sum :amount] 0] :s]] :from [:accounts] :where [:= :group_id 1]} {:min 1 :max 1} 1]
                          [{:select [[[:+ [:sum :amount] 1] :s]] :from [:accounts]} {:min 1 :max 1} 1]
                          [{:select [[[:case [:> [:count :*] 0] "some" :else "none"] :c]] :from [:accounts]} {:min 1 :max 1} 1]
                          [{:select [[[:cast [:count :*] :int] :n]] :from [:accounts]} {:min 1 :max 1} 1]
                          [{:select [[[:count :*] :n]] :from [:accounts] :having [:> [:count :*] 5]} {:min 0 :max 1} 0]
                          [{:select [[[:count :*] :n]] :from [:accounts] :having [:> [:count :*] 0]} {:min 0 :max 1} 1]
                          [{:select [[[:over [[:count :*] {} :w]]]] :from [:accounts]} nil 2]
                          [{:select [:id [{:select [[[:count :*]]] :from [:uq]} :c]] :from [:accounts]} nil 2]
                          [{:select [[[:count :*] :n] [[:generate_series 1 3] :g]] :from [:accounts]} nil 3]]]
        (testing (pr-str q)
          (is (= rows (:rows (h/analyze reg q))))
          (is (= n (count (runs! q {} {})))))))))

(deftest a-populate-recordsets-document-takes-the-row-types-columns-as-the-one-sending-writes-them
  (when *db*
    (let [reg (registry)
          insert (fn [t cols] {:insert-into [[t cols] {:select cols :from [[[:jsonb_populate_recordset [:cast nil t] :?rows] :r]]}]})
          q (insert :positions [:group_id :name :sort_order])
          long-name (apply str (repeat 101 "a"))]
      (is (some? (runs! q {:rows [{:group_id 1 :name "a" :sort_order 1} {:group_id 2 :name "b"}]} {})))
      (is (accepted? reg q {:rows [{:group_id 1 :name "a" :sort_order 1} {:group_id 2 :name "b"}]} {}))
      (testing "a key of no column: PostgreSQL ignores it, pgmalli refuses it"
        (is (some? (runs! q {:rows [{:group_id 1 :name "a" :sortOrder 1}]} {})))
        (is (not (accepted? reg q {:rows [{:group_id 1 :name "a" :sortOrder 1}]} {}))))
      (testing "a key of a column the statement does not use: PostgreSQL ignores it, pgmalli takes it"
        (let [q (insert :positions [:group_id :name])
              rows [{:group_id 1 :name "a" :sort_order 1}]]
          (is (some? (runs! q {:rows rows} {})))
          (is (accepted? reg q {:rows rows} {}))))
      (testing "string keys, which PostgreSQL matches as JSON's keys"
        (let [rows [{"group_id" 1 "name" "a" "sort_order" 1}]]
          (is (= [{:name "a" :sort_order 1}] (runs! (assoc q :returning [:name :sort_order]) {:rows rows} {})))
          (is (accepted? reg q {:rows rows} {}))))
      (doseq [row [{:group_id 1 :name long-name} {:group_id 1 :name "a" :sort_order 3000000000} {:group_id 1 :name "a" :sort_order 1.5}
                   {:group_id 1}]]
        (testing (pr-str row)
          (is (thrown? Exception (runs! q {:rows [row]} {})))
          (is (not (accepted? reg q {:rows [row]} {})))))
      (testing "a jsonb column's CHECK shapes its value"
        (let [q (insert :ul [:j])]
          (is (some? (runs! q {:rows [{:j {:a 1}}]} {})))
          (is (accepted? reg q {:rows [{:j {:a 1}}]} {}))
          (is (thrown? Exception (runs! q {:rows [{:j [1]}]} {})))
          (is (not (accepted? reg q {:rows [{:j [1]}]} {})))))
      (testing "java.time values and a UUID, written as their text, read by the column type's input"
        (let [q (insert :typed [:u :d :ts :tz :t])
              row {:u (java.util.UUID/randomUUID) :d (java.time.LocalDate/now) :ts (java.time.LocalDateTime/now)
                   :tz (java.time.Instant/now) :t (java.time.LocalTime/now)}]
          (doseq [row [row (assoc row :tz (java.time.OffsetDateTime/now)) (assoc row :tz (java.time.LocalDate/now))]]
            (is (some? (runs! q {:rows [row]} {})))
            (is (accepted? reg q {:rows [row]} {})))))
      (testing "the whole row of the UPDATE's table"
        (let [q {:update [:positions :p] :set {:sort_order {:select [:r.sort_order] :from [[[:jsonb_populate_record :p [:cast :?row :jsonb]] :r]]}}
                 :where [:= :p.id 1]}]
          (is (= [{:next.jdbc/update-count 1}] (runs! q {:row {:sort_order 2}} {})))
          (is (accepted? reg q {:row {:sort_order 2}} {}))
          (is (not (accepted? reg q {:row {:sort-order 2}} {})))
          (is (= {:row #{:sort_order}} (:document-columns (h/analyze reg q))) "the columns the statement uses of the document"))))))

(deftest a-to-recordsets-document-takes-the-columns-its-alias-defines-as-the-one-sending-writes-them
  (when *db*
    (let [reg (registry)
          q {:select [:w.pattern :w.role_keys :w.hide :w.n]
             :from [[[:jsonb_to_recordset :?words] [:w {:with-columns [[:pattern :text] [:role_keys :text :array] [:hide :boolean] [:n :int]]}]]]}
          ok [{:pattern "a" :role_keys ["x" nil] :hide true :n 1} {"pattern" "b"} {}]]
      (is (= [{:pattern "a" :role_keys ["x" nil] :hide true :n 1} {:pattern "b" :role_keys nil :hide nil :n nil} {:pattern nil :role_keys nil :hide nil :n nil}]
             (runs! q {:words ok} {})))
      (is (accepted? reg q {:words ok} {}))
      (is (returned? reg q {:words ok} {}))
      (doseq [row [{:n 1.5} {:n 3000000000} {:role_keys "x"}]]
        (testing (pr-str row)
          (is (thrown? Exception (runs! q {:words [row]} {})))
          (is (not (accepted? reg q {:words [row]} {})))))
      (testing "a record, its document cast to json"
        (let [q {:select [:r.d] :from [[[:json_to_record [:cast :?doc :json]] [:r {:with-columns [[:d :date]]}]]]}]
          (is (= [{:d (java.time.LocalDate/of 2020 1 2)}] (runs! q {:doc {:d (java.time.LocalDate/of 2020 1 2)}} {})))
          (is (accepted? reg q {:doc {:d (java.time.LocalDate/of 2020 1 2)}} {})))))))

(deftest columns-of-one-label-postgresql-returns-the-builder-reads-into-one-key
  (when *db*
    (let [reg (registry)
          join {:from [[:accounts :a]] :join [[:uq :u] [:= :u.id :a.id]]}]
      (doseq [[q label] [[(merge join {:select [:a.id :u.id]}) :id]
                         [(merge join {:select [:*]}) :id]
                         [{:select [[[:count :*]] [[:count :id]]] :from [:accounts]} :count]
                         [{:union [(merge join {:select [:a.id :u.id]}) {:select [:id :a] :from [:uq]}]} :id]
                         [{:with [[:t (merge join {:select [:a.id :u.id]})]] :select [:*] :from [:t]} :id]]]
        (testing (pr-str q)
          (is (= 2 (get (frequencies (first (jdbc/execute! (datasource) (sql/format q) {:builder-fn rs/as-unqualified-arrays}))) label)))
          (is (= 1 (count (filter #{label} (keys (first (runs! q {} {})))))))
          (is (= [[:duplicate-column label]] (map (juxt :kind :column) (:problems (h/analyze reg q))))))))))
