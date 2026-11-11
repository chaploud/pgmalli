(ns pgmalli.postgres-runs-test
  "Queries and values PostgreSQL runs through pgjdbc, each run (and rolled back), which pgmalli
   does not refuse. JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [malli.generator :as mg]
            [next.jdbc]
            [pgmalli.jdbc :refer [jsonb stored]]
            [pgmalli.test-jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.registry :as registry]
            [pgmalli.impl.shape :as shape]
            [pgmalli.impl.types :as types]
            [pgmalli.oracle :as oracle]
            [pgmalli.small-stack :refer [on-small-stack]]
            [pgmalli.test-db :refer [builder generated *db* datasource exec-sql! with-postgres]])
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

(defn- registry [] (pgmalli/registry (generated {:db *db*} "public" "app")))

(defn- pg-object [type value] (doto (PGobject.) (.setType type) (.setValue value)))

(def ^:private builder-of
  ;; the schema's enum types are the fixture's: one registry per database
  (memoize (fn [db] (builder (pgmalli/registry (generated {:db db} "public" "app"))))))

(defn- runs!
  "Runs q with params and opts' :format, rolled back; throws what PostgreSQL refuses."
  [q params opts]
  (with-open [con (jdbc/get-connection (datasource))]
    (.setAutoCommit con false)
    (try (jdbc/execute! con (sql/format q (assoc (:format opts) :params params)) {:builder-fn (builder-of *db*)})
         (finally (.rollback con)))))

(defn- refused?
  "Whether PostgreSQL refuses q, run as runs! runs it: for the statement (oracle/refused), not the
   server or the connection, which it throws."
  [q params opts]
  (try (runs! q params opts) false (catch java.sql.SQLException e (boolean (oracle/refused e)))))

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
      (is (refused? q {} {}) "PostgreSQL has no status = varchar")
      (is (= [:uncast-string] (map :kind (:problems (h/analyze reg q)))))
      (is (some? (runs! q {} {:format {:inline true}})))
      (is (= [] (:problems (h/analyze reg q {:format {:inline true}})))))))

(deftest an-enum-of-another-schema-is-the-columns-type
  (when *db*
    (let [reg (registry)
          q {:select [:id] :from [:app.items] :where [:= :status "live"]}]
      (is (refused? q {} {}))
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
      (is (refused? {:select [[[:cast :?m :mail] :v]]} {:m "a"} {})))))

(deftest a-date-time-or-timestamp-goes-to-a-date-time-or-timestamp
  (when *db*
    (let [reg (registry)
          instant (java.time.Instant/ofEpochSecond 0)
          local (java.time.LocalDateTime/of 1990 1 2 3 4)
          ld (java.time.LocalDate/of 1990 1 2)
          lt (java.time.LocalTime/of 10 0)
          ot (java.time.OffsetTime/of 10 0 0 0 java.time.ZoneOffset/UTC)]
      (doseq [[q params] [[{:select [:id] :from [:typed] :where [:>= :tz :?x]} {:x instant}]
                          [{:select [:id] :from [:typed] :where [:< :ts :?x]} {:x local}]
                          [{:select [:id] :from [:typed] :where [:= :d :?x]} {:x ld}]
                          [{:select [:id] :from [:typed] :where [:< :t :?x]} {:x lt}]
                          [{:select [:id] :from [:typed] :where [:= :tt :?x]} {:x ot}]
                          [{:select [:id] :from [:typed] :where [:< :tz :?x]} {:x ld}]
                          [{:select [:id] :from [:typed] :where [:< :tz :?x]} {:x local}]
                          [{:update :typed :set {:tz :?x} :where [:= :id 1]} {:x instant}]
                          [{:update :typed :set {:tz :?x :ts :?x} :where [:= :id 1]} {:x local}]
                          [{:update :typed :set {:d :?x :ts :?x} :where [:= :id 1]} {:x ld}]
                          [{:update :typed :set {:t :?x} :where [:= :id 1]} {:x lt}]
                          [{:update :typed :set {:tt :?x} :where [:= :id 1]} {:x ot}]
                          [{:select [:id] :from [:typed] :where [:< [:composite :tz :id] [:composite :?c :?i]]} {:c instant :i 1}]]]
        (testing (pr-str q params)
          (is (some? (runs! q params {})))
          (is (accepted? reg q params {}))))
      ;; converted on assignment alone, in the session's time zone: stricter than PostgreSQL
      (doseq [[q params] [[{:update :typed :set {:ts :?x} :where [:= :id 1]} {:x instant}]
                          [{:update :typed :set {:d :?x} :where [:= :id 1]} {:x instant}]
                          [{:update :typed :set {:d :?x} :where [:= :id 1]} {:x local}]]]
        (testing (str "stricter than PostgreSQL: " (pr-str q params))
          (is (some? (runs! q params {})))
          (is (not (accepted? reg q params {}))))))))

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
                     [{:update :typed :set {:fs :?x} :where [:= :id 1]} [1.5M]]
                     [{:update :typed :set {:fs :?x} :where [:= :id 1]} [1.5]]
                     [{:select [:r.i] :from [[[:jsonb_populate_record [:cast nil :typed] :?x] :r]]} {:i 2N}]]]
        (testing (pr-str q x)
          (is (some? (runs! q {:x x} {})))
          (is (accepted? reg q {:x x} {})))))))

(deftest a-date-or-time-to-a-text-column-is-refused-though-postgresql-assigns-it
  (when *db*
    (let [reg (registry)
          writes [{:update :accounts :set {:email :?x} :where [:= :id 1]}
                  {:insert-into :accounts :values [{:email :?x}]}]]
      ;; java.time's go typed: assigned through the text input alone ('a'), compared not at all
      (doseq [v [(java.time.Instant/ofEpochSecond 0) (java.time.LocalDate/of 1990 1 2) (java.time.LocalDateTime/of 1990 1 2 3 4)]]
        (doseq [q writes]
          (testing (pr-str q v)
            (is (some? (runs! q {:x v} {})))
            (is (not (accepted? reg q {:x v} {})))))
        (is (not (accepted? reg {:select [:id] :from [:accounts] :where [:= :email :?x]} {:x v} {}))))
      ;; java.sql's go untyped, their text in the JVM's time zone: sent nowhere
      (doseq [v [(java.sql.Timestamp. 0) (java.sql.Date/valueOf "1990-01-02") (java.sql.Time/valueOf "10:00:00")]
              q (cons {:select [:id] :from [:accounts] :where [:= :email :?x]} writes)]
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
      (doseq [[q params] [[pair {:ids [1 2] :names ["a" "b"]}]
                          [pair {:ids "{1,2}" :names "{a,b}"}]
                          [{:select [:id] :from [:accounts] :where [:= :id [:any [:cast :?xs [:raw "bigint[]"]]]]} {:xs [1]}]
                          [{:select [:id] :from [:accounts] :where [:= :id [:any [:cast :?xs [:raw "bigint[]"]]]]} {:xs "{1}"}]
                          [{:select [[[:cast :?j [:raw "jsonb[]"]] :j]]} {:j "{\"{}\"}"}]
                          [{:select [:id] :from [:accounts] :where [:= :tags [:cast :?t [:raw "status[]"]]]} {:t [:live]}]
                          [{:select [[[:cast :?t [:raw "status[]"]] :t]]} {:t "{draft}"}]]]
        (testing (pr-str q params)
          (is (some? (runs! q params {})))
          (is (accepted? reg q params {}))
          (is (returned? reg q params {})))))))

(deftest an-array-cast-casts-each-element-as-a-cast-to-the-element-type
  (when *db*
    (let [reg (registry)
          cast-to #(hash-map :select [[[:cast :?x [:raw %]] :x]])]
      (doseq [[t x] [["bigint[]" [1 nil]] ["bigint[]" ["1"]] ["bigint[]" [(int 1)]]
                     ["jsonb[]" ["{}"]] ["jsonb[]" "{\"{}\"}"]
                     ["status[]" ["live" nil]] ["status[]" [:live]] ["dpos[]" [(int 9) nil]]]]
        (testing (str t " " (pr-str x))
          (is (some? (runs! (cast-to t) {:x x} {})))
          (is (accepted? reg (cast-to t) {:x x} {}))))
      (doseq [[t x] [["bigint[]" [true]] ["bigint[]" [1.5]]
                     ["status[]" ["gone"]]]]
        (testing (str t " " (pr-str x))
          (is (refused? (cast-to t) {:x x} {}))
          (is (not (accepted? reg (cast-to t) {:x x} {}))))))))

(deftest an-unnest-of-an-array-cast-to-the-columns-type-holds-each-element-to-the-column
  (when *db*
    (let [reg (registry)
          star (fn [c t] {:insert-into [[:ul [c]] {:select [:*] :from [[[:unnest [:cast :?xs [:raw t]]] [:r {:columns [c]}]]]}]})
          items (fn [c t] {:insert-into [[:ul [c]] {:select [(keyword (str "r." (name c)))] :from [[[:unnest [:cast :?xs [:raw t]]] [:r {:columns [c]}]]]}]})]
      (doseq [q [star items]
              [c t ok bad] [[:v "varchar[]" ["abc" nil]]
                            [:n "int[]" [9] [3000000000]]
                            [:j "jsonb[]" ["{}"] [true]]]]
        (testing (pr-str (q c t))
          (is (some? (runs! (q c t) {:xs ok} {})))
          (is (accepted? reg (q c t) {:xs ok} {}))
          (when bad
            (is (refused? (q c t) {:xs bad} {}))
            (is (not (accepted? reg (q c t) {:xs bad} {}))))))
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
          ;; a recordset's document, a vector of rows, is jsonb-marked: a vector alone goes as an array
          docs (fn [params] (update-vals params #(cond-> % (and (vector? %) (every? map? %)) jsonb)))
          [runs*! accepted*? refused*?] [runs! accepted? refused?]
          runs! (fn [q p o] (runs*! q (docs p) o))
          accepted? (fn [r q p o] (accepted*? r q (docs p) o))
          refused? (fn [q p o] (refused*? q (docs p) o))
          insert (fn [t cols] {:insert-into [[t cols] {:select cols :from [[[:jsonb_populate_recordset [:cast nil t] :?rows] :r]]}]})
          q (insert :positions [:group_id :name :sort_order])]
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
      (doseq [row [{:group_id 1 :name "a" :sort_order 3000000000} {:group_id 1 :name "a" :sort_order 1.5}
                   {:group_id 1}]]
        (testing (pr-str row)
          (is (refused? q {:rows [row]} {}))
          (is (not (accepted? reg q {:rows [row]} {})))))
      (testing "a jsonb column's CHECK is the database's"
        (let [q (insert :ul [:j])]
          (is (some? (runs! q {:rows [{:j {:a 1}}]} {})))
          (is (accepted? reg q {:rows [{:j {:a 1}}]} {}))
          (is (refused? q {:rows [{:j [1]}]} {}))
          (is (accepted? reg q {:rows [{:j [1]}]} {}))))
      (testing "java.time values and a UUID, written as their text, read by the column type's input"
        (let [q (insert :typed [:u :d :ts :tz :t])
              row {:u (java.util.UUID/randomUUID) :d (java.time.LocalDate/now) :ts (java.time.LocalDateTime/now)
                   :tz (java.time.Instant/now) :t (java.time.LocalTime/now)}]
          (doseq [row [row (assoc row :tz (java.time.OffsetDateTime/now))]]
            (is (some? (runs! q {:rows [row]} {})))
            (is (accepted? reg q {:rows [row]} {})))
          (let [row (assoc row :tz (java.time.LocalDate/now))]
            (is (some? (runs! q {:rows [row]} {})))
            (is (not (accepted? reg q {:rows [row]} {})) "a date's text to a timestamptz reads in the session's time zone"))))
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
          ;; a recordset's document, a vector of rows, is jsonb-marked: a vector alone goes as an array
          docs (fn [params] (update-vals params #(cond-> % (and (vector? %) (every? map? %)) jsonb)))
          [runs*! accepted*? refused*? returned*?] [runs! accepted? refused? returned?]
          runs! (fn [q p o] (runs*! q (docs p) o))
          accepted? (fn [r q p o] (accepted*? r q (docs p) o))
          refused? (fn [q p o] (refused*? q (docs p) o))
          returned? (fn [r q p o] (returned*? r q (docs p) o))
          q {:select [:w.pattern :w.role_keys :w.hide :w.n]
             :from [[[:jsonb_to_recordset :?words] [:w {:with-columns [[:pattern :text] [:role_keys :text :array] [:hide :boolean] [:n :int]]}]]]}
          ok [{:pattern "a" :role_keys ["x" nil] :hide true :n 1} {"pattern" "b"} {}]]
      (is (= [{:pattern "a" :role_keys ["x" nil] :hide true :n 1} {:pattern "b" :role_keys nil :hide nil :n nil} {:pattern nil :role_keys nil :hide nil :n nil}]
             (runs! q {:words ok} {})))
      (is (accepted? reg q {:words ok} {}))
      (is (returned? reg q {:words ok} {}))
      (doseq [row [{:n 1.5} {:n 3000000000} {:role_keys "x"}]]
        (testing (pr-str row)
          (is (refused? q {:words [row]} {}))
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

(deftest a-row-count-holds-what-postgresql-returns
  (when *db*
    (let [reg (registry)
          one {:select [[[:cast "12:34:56" :time] :t]]}
          ids {:select [:id] :from [:accounts]}
          id {:select [[[:cast 1 :bigint] :id]]}]
      (doseq [[q rows n] [[one {:min 1 :max 1} 1]
                          [(assoc one :limit 1) {:min 1 :max 1} 1]
                          [(assoc one :where false) {:min 0 :max 1} 0]
                          [{:select-distinct [[1 :x]]} {:min 1 :max 1} 1]
                          [{:select [[[:count :*] :n]] :where false} {:min 1 :max 1} 1]
                          [{:select [[1 :x]] :group-by [:x]} {:min 1 :max 1} 1]
                          [{:select [[1 :x]] :group-by [:x] :having false} {:min 0 :max 1} 0]
                          [{:select [[1 :x]] :where false :group-by [:x]} {:min 0 :max 1} 0]
                          [{:select [[1 :x]] :group-by [[:generate_series 1 3]]} nil 3]
                          [{:select [[1 :x]] :group-by [[:rollup :x]]} nil 2]
                          [{:select [:id] :from [:uq] :where [:= :id 1] :group-by [[:rollup :id]]} nil 2]
                          [{:select [:id] :from [:uq] :where [:= :id 1] :group-by [:id [:generate_series 1 3]]} nil 3]
                          [{:select [[[:generate_series 1 3] :g]]} nil 3]
                          [(assoc ids :fetch 1) {:min 0 :max 1} 1]
                          [(assoc ids :limit 5 :offset 1) {:min 0 :max 5} 1]
                          [(assoc ids :limit :all) nil 2]
                          [{:values [[1] [2] [3]]} {:min 3 :max 3} 3]
                          [{:union-all [one one one]} {:min 3 :max 3} 3]
                          [{:union [one one]} {:min 1 :max 2} 1]
                          [{:union [(assoc one :where false) (assoc one :where false)]} {:min 0 :max 2} 0]
                          [{:intersect [id ids]} {:min 0 :max 1} 1]
                          [{:except [id ids]} {:min 0 :max 1} 0]
                          [{:except-all [id ids]} {:min 0 :max 1} 0]
                          [{:union-all [id ids]} nil 3]
                          [{:union-all [id ids] :limit 2} {:min 0 :max 2} 2]]]
        (testing (pr-str q)
          (is (= rows (:rows (h/analyze reg q))))
          (is (= n (count (runs! q {} {})))))))))

(deftest a-date-and-time-sent-and-read-is-the-same-whatever-the-jvms-time-zone
  (when *db*
    (let [instant (java.time.Instant/parse "2026-03-08T07:30:00.123456Z")
          local (java.time.LocalDateTime/of 2026 1 1 0 30 0 123456000)
          day (java.time.LocalDate/of 2026 1 1)
          time (java.time.LocalTime/of 1 30 0 123456000)
          offset-time (java.time.OffsetTime/of 1 30 0 500000000 (java.time.ZoneOffset/ofHours 9))
          saved (java.util.TimeZone/getDefault)]
      (try
        (doseq [zone ["UTC" "Asia/Tokyo" "America/New_York"]]
          ;; pgjdbc sets the session's time zone to the JVM's when it connects
          (java.util.TimeZone/setDefault (java.util.TimeZone/getTimeZone zone))
          (with-open [c (jdbc/get-connection (datasource *db*))]
            (jdbc/execute! c ["CREATE TEMP TABLE when_ (tz timestamptz, ts timestamp, d date, t time, tt timetz, ta time[])"])
            (jdbc/execute! c ["INSERT INTO when_ VALUES (?, ?, ?, ?, ?, ARRAY[?::time])" instant local day time offset-time time])
            (is (= {:tz instant :ts local :d day :t time :tt offset-time :ta [time]}
                   (jdbc/execute-one! c ["SELECT * FROM when_"] {:builder-fn (builder-of *db*)}))
                zone)
            (is (= {:tz "2026-03-08 07:30:00.123456" :ts "2026-01-01 00:30:00.123456" :d "2026-01-01" :tt "01:30:00.5+09"}
                   (jdbc/execute-one! c ["SELECT (tz AT TIME ZONE 'UTC')::text AS tz, ts::text AS ts, d::text AS d, tt::text AS tt FROM when_"]
                                      {:builder-fn (builder-of *db*)}))
                (str zone ": the values PostgreSQL holds"))))
        (finally (java.util.TimeZone/setDefault saved))))))

(deftest infinity-reads-as-the-java-time-types-max-and-min-and-is-written-back-as-infinity
  (when *db*
    (with-open [c (jdbc/get-connection (datasource *db*))]
      (let [row (jdbc/execute-one! c ["SELECT 'infinity'::timestamp AS ts, 'infinity'::timestamptz AS tz, 'infinity'::date AS d,
                                              '-infinity'::timestamp AS nts, '-infinity'::timestamptz AS ntz, '-infinity'::date AS nd,
                                              ARRAY['infinity'::timestamptz] AS tza"]
                                   {:builder-fn (builder-of *db*)})]
        (is (= {:ts java.time.LocalDateTime/MAX :tz java.time.Instant/MAX :d java.time.LocalDate/MAX
                :nts java.time.LocalDateTime/MIN :ntz java.time.Instant/MIN :nd java.time.LocalDate/MIN
                :tza [java.time.Instant/MAX]}
               row)
            "pgjdbc reads infinity as OffsetDateTime's MAX and MIN, which no Instant is")
        (is (= {:ts "infinity" :tz "infinity" :d "infinity" :nts "-infinity" :ntz "-infinity" :nd "-infinity"}
               (jdbc/execute-one! c ["SELECT ?::timestamp::text AS ts, ?::timestamptz::text AS tz, ?::date::text AS d,
                                             ?::timestamp::text AS nts, ?::timestamptz::text AS ntz, ?::date::text AS nd"
                                     (:ts row) (:tz row) (:d row) (:nts row) (:ntz row) (:nd row)]
                                    {:builder-fn (builder-of *db*)}))
            "an Instant's MAX and MIN go as infinity, which no OffsetDateTime in UTC holds")))))


(deftest sent-sends-a-vector-or-a-set-as-an-array-and-a-marked-vector-as-jsonb
  (when *db*
    (let [sent (requiring-resolve 'pgmalli.jdbc/sent)
          jsonb (requiring-resolve 'pgmalli.jdbc/jsonb)
          one #((requiring-resolve 'next.jdbc/execute-one!) (datasource) (sent %) {:builder-fn rs/as-unqualified-maps})]
      (is (= {:n 3} (one ["SELECT cardinality(?::bigint[]) AS n" [1 nil 3]])) "NULL an element")
      (is (= {:t "{{1,2},{3,4}}"} (one ["SELECT (?::int[])::text AS t" [[1 2] [3 4]]])) "a vector of vectors, multi-dimensional")
      (is (= {:t "{\"a\\\"b\",c}"} (one ["SELECT (?::text[])::text AS t" ["a\"b" "c"]])) "quoted, a quote escaped")
      (is (= {:b true} (one ["SELECT 2 = ANY(?) AS b" #{1 2}])) "a set, untyped: = ANY reads it as the other side's array")
      (is (= {:b true} (one ["SELECT 2 = ANY(?) AS b" (map inc [0 1])])) "a seq, as a vector")
      (is (= {:t "{ns/a,b}"} (one ["SELECT (?::text[])::text AS t" [:ns/a :b]])) "a keyword element's text, its namespace kept")
      (is (= {:t "array"} (one ["SELECT jsonb_typeof(?) AS t" (jsonb [1 2])])) "a vector jsonb marks, a document")
      (is (= {:t "object"} (one ["SELECT jsonb_typeof(?) AS t" {:a 1}])))
      (is (thrown? java.sql.SQLException (one ["SELECT jsonb_typeof(?) AS t" [1 2]])) "a vector alone, an array literal jsonb's input refuses")
      (is (= {:t "\\x6162"} (one ["SELECT (?::bytea[])[1]::text AS t" [(.getBytes "ab")]])) "a byte[] element as bytea's hex")
      (is (= {:t "array"} (one ["SELECT jsonb_typeof((?::jsonb[])[1]) AS t" [(jsonb [1 2])]])) "a marked document, one jsonb element")
      (is (= {:b true} (one ["SELECT (?::date[]) = ARRAY['0001-01-01 BC'::date, '0006-03-04 BC', '10000-01-01'] AS b"
                             [(java.time.LocalDate/of 0 1 1) (java.time.LocalDate/of -5 3 4) (java.time.LocalDate/of 10000 1 1)]]))
          "a date of no year, or past 9999, as date's input reads it")
      (is (= {:b true} (one ["SELECT (?::timestamptz[])[1] = '0002-01-01 00:00:00+00 BC'::timestamptz AS b"
                             [(java.time.OffsetDateTime/of -1 1 1 0 0 0 0 java.time.ZoneOffset/UTC)]]))))))

(deftest stored-is-the-document-a-jsonb-column-holds-as-the-builder-reads-it
  (when *db*
    (doseq [doc [{:a 1.0E7} {:a 1E+3M} {:a -0.0} {:a 1.5M} {:a 1.0E300} {:a (float 1.1)} {:a 1.0E-7} {:a 22/7}
                 {:a 12345678901234567890N} {:a "\ud800"} {"\ud800" 1} (jsonb [1 "a" nil {:x nil}])
                 {:a/b #{1 2} :t (java.time.Instant/parse "2026-01-01T00:00:00.123456789Z") :c \x
                  :d (java.time.LocalDate/of 2026 1 2) :u (random-uuid) :s "日本"}]]
      (is (= (:doc (jdbc/execute-one! (datasource) ["SELECT ?::jsonb AS doc" doc] {:builder-fn (builder {})}))
             (stored doc))
          (pr-str doc)))
    (doseq [v [:ns/draft "a" 1.0E7 22/7 nil [1 #{2}] true {1.0E7 1 [-0.0] 2} #{:a "a"} #{1.0E7 10000000}]]
      (is (= (:v (jdbc/execute-one! (datasource) ["SELECT (?::jsonb)->'v' AS v" {:v v}] {:builder-fn (builder {})}))
             (stored v))
          (str "a value within a document: " (pr-str v))))
    (is (thrown? clojure.lang.ExceptionInfo (stored "a\u0000")) "what sent refuses in a document")))

(deftest a-jsonb-nested-past-the-stack-is-an-error-naming-the-column
  (when *db*
    (let [deep (str (apply str (repeat 5000 "[")) (apply str (repeat 5000 "]")))
          e (on-small-stack #(jdbc/execute-one! (datasource) ["SELECT ?::jsonb AS doc" deep] {:builder-fn (builder {})}))]
      (is (instance? clojure.lang.ExceptionInfo e) (pr-str (class e)))
      (is (= [:pgmalli.error/fault "doc"] [(:pgmalli/error (ex-data e)) (:given (ex-data e))]))
      (is (= "docs" (:given (ex-data (on-small-stack #(jdbc/execute-one! (datasource) ["SELECT ARRAY[?::jsonb] AS docs" deep] {:builder-fn (builder {})})))))
          "an array's element, named as the array's column"))))

(deftest a-document-json-holds-not-is-refused-not-sent-as-another
  (when *db*
    (doseq [[m says] [[{:role "user" "role" "admin"} #"two keys of text"] [{:a/role 1 'a/role 2} #"two keys of text"] [{1 :x "1" :y} #"two keys of text"]
                      [{:a "x\u0000"} #"u0000"] [{"\u0000" 1} #"u0000"] [{:a ##NaN} #"NaN"] [{:a (/ (biginteger (.pow 10M 400)) 3)} #"infinity"] [{1/2 :x "1/2" :y} #"two keys of text"]
                      [{:a 1E-16384M} #"numeric"] [{:a 1E+131072M} #"numeric"] [[Float/POSITIVE_INFINITY] #"infinity"] [{nil 1} #"nil key"]]]
      (let [e (is (thrown? clojure.lang.ExceptionInfo (jdbc/execute-one! (datasource) ["SELECT ?::jsonb AS doc" {:m m}])) (pr-str m))]
        (is (re-find says (str (ex-message e))) (pr-str m))))
    (let [deep (reduce (fn [v _] [v]) [] (range 5000))]
      (is (instance? clojure.lang.ExceptionInfo (on-small-stack #(jdbc/execute-one! (datasource) ["SELECT ?::jsonb AS doc" (jsonb deep)]))) "nested deeper than the stack walks")
      (is (instance? clojure.lang.ExceptionInfo (on-small-stack #(jdbc/execute-one! (datasource) ["SELECT ?::int[] AS a" deep]))) "an array likewise"))))

(deftest a-time-of-24-reads-as-the-java-time-types-max
  (when *db*
    (is (= {:t java.time.LocalTime/MAX :tt java.time.OffsetTime/MAX}
           (jdbc/execute-one! (datasource) ["SELECT '24:00'::time AS t, '24:00+05'::timetz AS tt"] {:builder-fn (builder {})}))
        "pgjdbc reads a timetz of 24:00 as OffsetTime/MAX, whatever its offset")))

(deftest a-row-values-schema-and-set-schema-generate-is-written
  ;; the docs' example tables and matrix.types, of every type, written into copies without their
  ;; constraints (LIKE), whose keys, CHECKs and foreign keys no generated row keeps; a table a BEFORE
  ;; trigger fills
  (when *db*
    (exec-sql! (str "CREATE SCHEMA ex; SET search_path TO ex; " (slurp "docs/example/schema.sql")
                    " CREATE SCHEMA exw; CREATE TABLE exw.groups (LIKE ex.groups INCLUDING ALL EXCLUDING CONSTRAINTS EXCLUDING INDEXES);"
                    " CREATE TABLE exw.users (LIKE ex.users INCLUDING ALL EXCLUDING CONSTRAINTS EXCLUDING INDEXES);"
                    " CREATE TABLE exw.posts (LIKE ex.posts INCLUDING ALL EXCLUDING CONSTRAINTS EXCLUDING INDEXES);"
                    " CREATE TABLE exw.events (LIKE ex.events INCLUDING ALL EXCLUDING CONSTRAINTS EXCLUDING INDEXES);"
                    " CREATE TABLE ex.filled (id int, slug text NOT NULL, name text NOT NULL);"
                    " CREATE FUNCTION ex.fill() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.slug := coalesce(NEW.slug, 's'); RETURN NEW; END $$;"
                    " CREATE TRIGGER fill BEFORE INSERT OR UPDATE ON ex.filled FOR EACH ROW EXECUTE FUNCTION ex.fill();"
                    " CREATE TABLE exw.filled (LIKE ex.filled);"
                    " CREATE TRIGGER fill BEFORE INSERT OR UPDATE ON exw.filled FOR EACH ROW EXECUTE FUNCTION ex.fill();"))
    (with-open [con (jdbc/get-connection (datasource))]
      ((requiring-resolve 'pgmalli.oracle/matrix-schema!) con)
      (jdbc/execute! con ["CREATE TABLE exw.types (LIKE matrix.types INCLUDING DEFAULTS)"]))
    (let [reg (pgmalli/registry (generated {:db *db*} "ex" "matrix"))
          written (fn [q]
                    (with-open [con (jdbc/get-connection (datasource))]
                      (.setAutoCommit con false)
                      (try (jdbc/execute! con (sql/format q)) nil
                           (catch Exception e (ex-message e))
                           (finally (.rollback con)))))]
      (doseq [[schema table target] [["ex" :groups :exw.groups] ["ex" :users :exw.users] ["ex" :posts :exw.posts] ["ex" :events :exw.events]
                                   ["ex" :filled :exw.filled] ["matrix" :types :exw.types]]
              [f q] [[h/values-schema #(hash-map :insert-into target :values [%])]
                     [h/set-schema #(hash-map :update target :set % :where [:= 1 0])]]
              :let [s (f reg table {:schema schema})
                    rows (try (doall (mg/sample s {:registry reg :size 10 :seed 7}))
                              (catch Exception e (is (nil? e) (str table " generates nothing: " (ex-message e)))))]
              row rows
              ;; matrix's domains are CHECK (VALUE IS NOT NULL), which no schema tells; and the
              ;; columns of #26's types taking values PostgreSQL refuses
              :let [row (into {} (remove #(re-find #"^da?_|^[ca]_character$|^a_(varchar|char)_" (name (key %)))) row)]
              :when (seq row)]
        (is (m/validate s row {:registry reg}))
        (is (nil? (written (q row))) (str table " " (pr-str row)))))))

(deftest an-array-column-takes-null-elements-a-pgobject-and-an-empty-vector
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                       [:is [:vector {:pg/type "integer[]"} :pg/integer]]
                                                       [:ts [:vector {:pg/type "timestamp with time zone[]"} [:time/instant {:pg/type "timestamp with time zone"}]]]]}})
        ok? (fn [c v] (m/validate (:x (:args (h/analyze reg {:update :t :set {c :?x}}))) v {:registry reg}))]
    (is (ok? :is [1 nil]) "PostgreSQL's arrays hold NULLs")
    (is (ok? :is (pg-object "integer[]" "{1,NULL}")))
    (is (ok? :ts []) "untyped, an empty array of the column's type")
    (is (ok? :ts [nil java.time.Instant/EPOCH]))
    (is (not (ok? :ts [1])) "a number timestamptz's input reads not")))

(deftest money-reads-as-a-pgobject-of-its-text-which-it-is-written-back-as
  (when *db*
    (let [row (jdbc/execute-one! (datasource) ["SELECT '1000.5'::money AS m, ARRAY['12.34'::money] AS ms"] {:builder-fn (builder {})})]
      (is (= ["money" "money"] [(.getType ^PGobject (:m row)) (.getType ^PGobject (first (:ms row)))]))
      (is (= {:ok true} (jdbc/execute-one! (datasource) ["SELECT ?::money = '1000.5'::money AS ok" (:m row)]))))))

(deftest a-composite-of-a-pg-catalog-types-name-reads-as-a-pgobject-of-its-text
  (when *db*
    (doseq [n ["jsonb" "date" "interval" "bytea"]]
      (jdbc/execute! (datasource) [(str "CREATE TYPE public.\"" n "\" AS (a int, b text)")])
      (try
        (let [c (:c (jdbc/execute-one! (datasource) [(str "SELECT ROW(1, 'x')::public.\"" n "\" AS c")] {:builder-fn (builder {})}))]
          (is (= "(1,x)" (.getValue ^PGobject c)) n))
        (finally (jdbc/execute! (datasource) [(str "DROP TYPE public.\"" n "\"")]))))))

(deftest each-sql-value-word-is-of-the-type-postgresql-gives-it
  (when *db*
    (doseq [[w t] types/sql-words]
      (is (= t (:t (jdbc/execute-one! (datasource) [(str "SELECT pg_typeof(" w ")::text AS t")]))) w))))

(deftest a-sequences-row-has-the-columns-postgresql-reads-of-one
  (when *db*
    (jdbc/execute! (datasource) ["CREATE SEQUENCE IF NOT EXISTS seq_columns"])
    (let [row (get (#'registry/sequence-rows {:sequences {"public" ["seq_columns"]}}) :pg.public/seq_columns)
          md (with-open [c (jdbc/get-connection (datasource))]
               (let [m (.getMetaData (.executeQuery (.createStatement c) "SELECT * FROM seq_columns"))]
                 (vec (for [i (range 1 (inc (.getColumnCount m)))] [(keyword (.getColumnLabel m i)) (types/canonical-name (.getColumnTypeName m i))]))))]
      (is (= md (for [[k _ s] (shape/column-entries row)] [k (:pg/type (shape/column-props s))]))))))

(deftest a-not-null-json-columns-document-null-reads-as-nil-which-as-read-takes
  (when *db*
    (jdbc/execute! (datasource) ["CREATE TABLE jn (id int PRIMARY KEY, j jsonb NOT NULL, jj json NOT NULL); INSERT INTO jn VALUES (1, 'null', 'null')"])
    (try
      (let [reg (pgmalli/registry (generated {:db *db*} "public"))
            row (jdbc/execute-one! (datasource) ["SELECT * FROM jn"] {:builder-fn (builder reg)})]
        (is (= {:id 1 :j nil :jj nil} row))
        (is (m/validate (pgmalli/as-read reg :pg.public/jn) row {:registry reg})))
      (finally (jdbc/execute! (datasource) ["DROP TABLE jn"])))))

(deftest an-inlined-row-values-schema-generates-is-written
  (when *db*
    (let [reg (pgmalli/registry (generated {:db *db*} "public" "app"))
          q #(sql/format {:insert-into :accounts :values [%]} {:inline true})]
      (doseq [row (mg/sample (h/values-schema reg :accounts {:format {:inline true}}) {:registry reg :size 30 :seed 3})]
        ;; a key or a CHECK is the database's to hold (docs/types.md)
        (is (nil? (try (next.jdbc/with-transaction [tx (datasource) {:rollback-only true}] (jdbc/execute! tx (q row)) nil)
                       (catch java.sql.SQLException e (when-not (#{"23505" "23503" "23514"} (.getSQLState e)) (ex-message e)))))
            (pr-str row))))))

(deftest the-registry-refused-a-class-takes-no-argument-of-it
  (let [data {:registry {:pg.public/t [:map {:pg/table "public.t"} [:ts [:time/instant {:pg/type "timestamp with time zone"}]]]} :schemas ["public"]}
        ts (fn [opts] (let [reg (pgmalli/registry data opts)] [reg (:ts (:args (h/analyze reg {:update :t :set {:ts :?ts}})))]))
        ldt (java.time.LocalDateTime/of 2026 1 1 0 0)]
    (let [[reg t] (ts {:refused [java.time.LocalDateTime]})]
      (is (not (m/validate t ldt {:registry reg})) "an argument takes no value of it")
      (is (m/validate t java.time.Instant/EPOCH {:registry reg})))
    (let [[reg t] (ts {})] (is (m/validate t ldt {:registry reg}) "of a registry not refusing it, an argument takes it"))))

(deftest an-interval-is-sent-of-a-duration-or-a-period-and-read-whole
  (let [rows (jdbc/execute-one! (datasource) ["SELECT ?::interval::text AS d, ?::interval::text AS nd, ?::interval::text AS p,
                                                     interval '1 hour 2.5 seconds' AS rd, interval '-0.5 seconds' AS rn,
                                                     interval '1 year 2 mons 3 days' AS rp, interval '1 day 02:00' AS rm"
                                                (java.time.Duration/ofMinutes -90) (.plusNanos (java.time.Duration/ofSeconds 3) 123456789) (java.time.Period/of 1 2 3)]
                                    {:builder-fn (builder-of *db*)})]
    (is (= ["-01:30:00" "00:00:03.123457" "1 year 2 mons 3 days"] [(:d rows) (:nd rows) (:p rows)]) "its ISO-8601, which interval's input reads, to the microsecond")
    (is (= [(.plusMillis (java.time.Duration/ofHours 1) 2500) (java.time.Duration/ofMillis -500) (java.time.Period/of 1 2 3)] [(:rd rows) (:rn rows) (:rp rows)])
        "a time alone a Duration, its fraction and sign kept; years, months and days alone a Period")
    (is (instance? org.postgresql.util.PGInterval (:rm rows)) "of both, whose day is no 24 hours, pgjdbc's PGInterval")))
