(ns pgmalli.row-keys-test
  "row-schema's keys against the rows next.jdbc builds from PostgreSQL's JDBC driver. JVM only
   (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            next.jdbc.optional
            next.jdbc.result-set
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.ir :as ir]
            [pgmalli.test-db :refer [*db* datasource exec-sql! jdbc-url with-postgres]]))

(use-fixtures :once with-postgres)

(def queries
  [{:select [:id [:nick :name]] :from [:users]}
   {:select [:u.id [:g.name :group_name]] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]]}
   {:union-all [{:select [:id [:nick :name]] :from [:users]} {:select [:id :name] :from [:groups]}]}
   {:select [[[:count :*]] [[:max :id] :top_id] [[:+ :id 1]] [[:coalesce :nick "x"]] [[:cast :id :bigint] :big_id] [[:upper :nick]]]
    :from [:users] :group-by [:id :nick]}
   {:select [[[:cast :nick :text]]] :from [:users]}
   ;; the types of the expressions row-schema knows, as the driver returns them
   {:select [[[:sum :id] :s] [[:avg :id] :a] [[:min :nick] :mn] [[:count :nick] :c] [[:coalesce :nick "x"] :cn]
             [[:= :id 1] :eq] [[:= :nick "a"] :eqn] [[:exists {:select [1] :from [:posts]}] :ex] [[:is :nick nil] :isn]
             [[:and true [:= :id 1]] :both] [[:now] :t] [:current_date] [:localtimestamp] ["s" :lit] [1 :one] [1.5 :f] [true :b]
             [[:cast :id :text] :idt] [[:cast :id :numeric] :idn] [[:cast :id :double-precision] :idd]
             [(int 1) :int] [(float 1.5) :real] [1.5M :dec] [1N :bigint] [[:coalesce :id 1.5] :mixed]
             [:current_timestamp] [:current_time] [:localtime]]
    :from [:users] :group-by [:id :nick]}
   {:select [[[:sum :id] :s] [[:avg :id] :a] [[:max :title] :m]] :from [:posts]}
   {:select [[[:sum :n] :s] [[:avg :n] :a] [[:max :n] :m] [[:count :*] :c]] :from [:likes] :where [:= :id -1]}
   {:select [:u.id :g.name :g/*] :from [[:users :u]] :left-join [[:groups :g] [:= :g.id -1]]}
   {:select [:u.nick :g.id] :from [[:users :u]] :right-join [[:groups :g] [:= :g.id -1]]}
   {:select [:id [[:count :*] :n]] :from [[{:select [:id] :from [:users]} :sub]] :group-by [:id]}
   {:with [[:c {:select [:id] :from [:groups]}]] :select [:id] :from [:c]}
   {:select [:u.id :x.n] :from [[:users :u]] :join [[[:lateral {:select [[:u.nick :n]]}] :x] true]}
   {:select [[:nick "nn"]] :from [:users]}
   ;; USING merges a column, keyed by the table PostgreSQL takes it from
   {:select [:*] :from [[:users :u]] :join [[:posts :p] [:using :id]]}
   {:select [:id] :from [[:users :u]] :join [[:posts :p] [:using :id]]}
   {:select [:id] :from [[:users :u]] :left-join [[:posts :p] [:using :id]]}
   {:select [:id] :from [[:users :u]] :right-join [[:posts :p] [:using :id]]}
   {:select [:id] :from [[:users :u]] :full-join [[:posts :p] [:using :id]]}
   {:select [:*] :from [[:users :u]] :join [[:likes :l] [:using :id]]}
   {:select [:id] :from [[:users :u]] :right-join [[:likes :l] [:using :id]]}
   {:select [:id] :from [[:users :u]] :left-join [[:likes :l] [:using :id]]}
   ;; a chain of USING joins: each merges what the joins before it merged
   {:select [:id] :from [[:users :a]] :join [[:posts :b] [:using :id]] :left-join [[:users :c] [:using :id]]}
   {:select [:*] :from [[:users :a]] :join [[:posts :b] [:using :id]] :left-join [[:users :c] [:using :id]]}])

(def using-chains
  "Three tables USING one column, every pair of joins, the columns of one type (likes) or not
   (posts' bigint)."
  (for [t [:likes :posts] j1 [:join :left-join :right-join :full-join] j2 [:join :left-join :right-join :full-join]
        sel [[:id] [:*]]
        :let [q {:select sel :from [[:users :a]]}]]
    (if (= j1 j2)
      (assoc q j1 [[t :b] [:using :id] [:likes :c] [:using :id]])
      (assoc q j1 [[t :b] [:using :id]] j2 [[:likes :c] [:using :id]]))))

(deftest row-schema-keys-are-the-drivers
  (when *db*
    (exec-sql! "CREATE TABLE groups (id int PRIMARY KEY, name text NOT NULL);
                CREATE TABLE users (id int PRIMARY KEY, group_id int NOT NULL REFERENCES groups, nick text);
                CREATE TABLE posts (id bigint PRIMARY KEY, title text);
                CREATE TABLE likes (id int PRIMARY KEY, n int);
                INSERT INTO groups VALUES (1, 'g'); INSERT INTO users VALUES (1, 1, 'a'); INSERT INTO posts VALUES (1, 't'), (2, 'u');
                INSERT INTO likes VALUES (1, 1), (2, 2);")
    (let [ds (datasource)
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))]
      (doseq [q (concat queries using-chains), builder ['next.jdbc.result-set/as-maps 'next.jdbc.result-set/as-unqualified-kebab-maps 'next.jdbc.optional/as-maps]
              row (jdbc/execute! ds (sql/format q) {:builder-fn @(requiring-resolve builder)})
              :let [schema (h/row-schema registry q {:jdbc {:builder-fn @(requiring-resolve builder)}})
                    entries (m/children (m/schema schema))
                    untold (:pgmalli/untold (m/properties (m/schema schema)))]]
        (is (= (count untold) (count (remove (set (map first entries)) (keys row))))
            (str "a key the row lacks is one :pgmalli/untold names " (pr-str builder q row)))
        (is (or (empty? untold) (not= builder 'next.jdbc.result-set/as-unqualified-kebab-maps))
            (str "only a qualified key is untold (a cast column, one through a subquery): " (pr-str q)))
        (is (clojure.set/subset? (set (for [[k p] entries :when (not (:optional p))] k)) (set (keys row)))
            (pr-str builder q))
        (is (m/validate schema row) (pr-str builder q row))))))

(deftest a-writes-row-is-what-next-jdbc-returns
  (when *db*
    (exec-sql! "CREATE TABLE tags (id int GENERATED ALWAYS AS IDENTITY PRIMARY KEY, name text NOT NULL);")
    (let [ds (datasource)
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))]
      (doseq [q [{:insert-into :tags :values [{:name "a"} {:name "b"}]}
                 {:insert-into :tags :values [{:name "a"}] :on-conflict [:id] :do-nothing true}
                 {:update :tags :set {:name "c"} :where [:= :name "a"]}
                 {:update :tags :set {:name "c"} :where [:= :name "nothing"]}
                 {:with [[:old {:select [:id] :from [:tags]}]] :delete-from :tags :where [:in :id {:select [:id] :from [:old]}]}
                 {:delete-from :tags :returning [:id]}
                 {:truncate :tags}]
              return-keys [nil true ["name"]]
              builder ['next.jdbc.result-set/as-maps 'next.jdbc.result-set/as-unqualified-kebab-maps 'next.jdbc.optional/as-maps]
              :let [jdbc-opts (cond-> {:builder-fn @(requiring-resolve builder)} return-keys (assoc :return-keys return-keys))
                    opts {:jdbc jdbc-opts}
                    [_ row] (m/children (m/schema (h/query-schema registry [] q opts) {:registry registry}))
                    [_ one] (m/children (m/schema (h/query-schema registry [] q (assoc opts :result :one)) {:registry registry}))]]
        (jdbc/execute! ds ["INSERT INTO tags (name) VALUES ('a')"])
        (let [rows (jdbc/execute! ds (sql/format q) jdbc-opts)]
          (is (m/validate row rows) (pr-str builder return-keys q))
          (when (and return-keys (:insert-into q) (not (:on-conflict q)))
            (is (seq rows) "an INSERT under :return-keys returns its rows")))
        (jdbc/execute! ds ["INSERT INTO tags (name) VALUES ('a')"])
        (is (m/validate one (jdbc/execute-one! ds (sql/format q) jdbc-opts)) (pr-str :one builder return-keys q))))))

(deftest arguments-typed-from-expressions-are-what-the-driver-takes
  (when *db*
    (exec-sql! "CREATE TYPE feel AS ENUM ('ok', 'meh'); CREATE DOMAIN positive AS int CHECK (VALUE > 0);
                CREATE TABLE notes (id int GENERATED ALWAYS AS IDENTITY PRIMARY KEY, n int NOT NULL, feel feel, body text);")
    (let [ds (datasource)
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))
          opts {:jdbc {:builder-fn next.jdbc.result-set/as-unqualified-maps}}]
      (doseq [[q params] [[{:insert-into [[:notes [:n :feel :body]] {:select [[:?n] [[:cast :?feel :feel]] [[:unnest :?bodies]]]}]}
                           {:n 1 :feel "ok" :bodies ["a" "b"]}]
                          [{:select [:id] :from [:notes] :where [:and [:= [:cast :n :text] :?t] [:< [:composite :n :id] [:composite :?n :?id]]]}
                           {:t "1" :n 2 :id 99}]]
              :let [types (h/arg-types registry q opts)
                    params (update-vals params #(if (vector? %) (into-array String %) %))]]
        (is (m/validate (into [:map] types) params {:registry registry}) (pr-str q))
        (is (some? (jdbc/execute! ds (sql/format q {:params params}))) (pr-str q)))
      (let [q {:select [[[:cast "ok" :feel] :f] [[:cast 2 :positive] :p] [[:cast :n :int] :i] [[:cast true :bool] :b]] :from [:notes]}
            row (first (jdbc/execute! ds (sql/format q) {:builder-fn next.jdbc.result-set/as-unqualified-maps}))]
        (is (some? row))
        (is (not-any? #(= [:maybe :any] (last %)) (rest (h/row-schema registry q opts))) "each cast is typed")
        (is (m/validate (h/row-schema registry q opts) row {:registry registry}) (pr-str row))))))

(deftest arguments-typed-as-postgresql-takes-them-run
  (when *db*
    (exec-sql! "CREATE TABLE prices (id bigint PRIMARY KEY, amount numeric NOT NULL, at timestamptz); INSERT INTO prices VALUES (1, 2.5, now());")
    (let [ds (datasource)
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))]
      (doseq [[q opts values] [[{:select [:id] :from [:prices] :where [:= :id :?x]} {} [1 (int 1)]]
                               [{:select [:id] :from [:prices] :where [:> :amount :?x]} {} [1 2.5M]]
                               [{:select [:id] :from [:prices] :where [:< :at [:cast :?x :timestamptz]]} {}
                                ["2026-01-01T00:00:00Z" (java.time.LocalDate/of 2026 1 1) (java.time.LocalDateTime/of 2026 1 1 0 0)
                                 (java.time.OffsetDateTime/of 2026 1 1 0 0 0 0 java.time.ZoneOffset/UTC)]]
                               [{:select [:id] :from [:prices] :limit :?x} {} [nil 1]]]
              :let [t (:x (h/arg-types registry q opts))]
              v values]
        (is (m/validate t v {:registry registry}) (str (pr-str q) " " (pr-str v)))
        (is (vector? (jdbc/execute! ds (sql/format q {:params {:x v}}))) (str (pr-str q) " " (pr-str v))))
      (is (not (m/validate (:x (h/arg-types registry {:select [:id] :from [:prices] :where [:= :id :?x]})) "1"))
          "a varchar compared with a bigint is no argument PostgreSQL takes"))))

(deftest pg-array-is-what-the-driver-takes
  (when *db*
    (let [ds (datasource)
          s (m/schema [:pg/array :int] {:registry (pgmalli/registry {:registry {}})})]
      (with-open [c (jdbc/get-connection ds)]
        (doseq [v [(long-array [1 2]) (into-array Long [1]) (int-array []) (.createArrayOf c "bigint" (object-array [1]))
                   (object-array [1]) [1 2] #{1}]
                :let [taken? (try (jdbc/execute-one! c ["SELECT 1 AS one WHERE 1 = ANY(?)" v]) true (catch Exception _ false))]]
          (is (= taken? (m/validate s v)) (str (class v))))))))

(deftest arrays-and-json-are-what-the-driver-returns-and-takes
  (when *db*
    (exec-sql! "CREATE TABLE docs (id int PRIMARY KEY, paths text[], meta jsonb NOT NULL CHECK (jsonb_typeof(meta) = 'object'));")
    (let [ds (datasource)
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))
          ins {:insert-into :docs :values [{:id :?id :paths :?paths :meta [:cast :?meta :jsonb]}]}
          args (h/arg-types registry ins)
          v (into-array String ["a" "b"])]
      (is (m/validate (:paths args) v {:registry registry}) "a String[] for a text[] column")
      (is (not (m/validate (:paths args) ["a"] {:registry registry})) "a vector the driver refuses")
      (jdbc/execute! ds (sql/format ins {:params {:id 1 :paths v :meta "{\"a\": 1}"}}))
      (let [q {:select [:paths :meta] :from [:docs]}
            row (first (jdbc/execute! ds (sql/format q) {:builder-fn next.jdbc.result-set/as-unqualified-maps}))]
        (is (m/validate (h/row-schema registry q {:jdbc {:builder-fn next.jdbc.result-set/as-unqualified-maps}}) row) (pr-str (update-vals row class)))))))

(deftest as-read-is-the-row-the-driver-returns
  (when *db*
    (exec-sql! "CREATE TABLE odd_keys (id int PRIMARY KEY, line_no2 int, \"userId\" int, tags text[], doc jsonb, span interval, born date);
                INSERT INTO odd_keys VALUES (1, 2, 3, '{a}', '{\"a\": 1}', '1 day', '2026-01-01');")
    (let [ds (datasource)
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))]
      (doseq [builder ['next.jdbc.result-set/as-maps 'next.jdbc.result-set/as-unqualified-kebab-maps 'next.jdbc.optional/as-maps]
              :let [row (first (jdbc/execute! ds ["SELECT * FROM odd_keys"] {:builder-fn @(requiring-resolve builder)}))
                    schema (pgmalli/as-read registry :pg.public/odd_keys {:jdbc {:builder-fn (requiring-resolve builder)}})]]
        (is (= (set (keys row)) (set (map first (drop 2 schema)))) (str builder))
        (is (m/validate schema row {:registry registry}) (str builder " " (pr-str (update-vals row class)))))
      (doseq [[builder jdbc-opts] [['next.jdbc.result-set/as-modified-maps {:label-fn str/upper-case :qualifier-fn #(str "t_" %)}]
                                   ['next.jdbc.result-set/as-unqualified-modified-maps {:label-fn #(str/replace % "_" "")}]]
              :let [jdbc-opts (assoc jdbc-opts :builder-fn @(requiring-resolve builder))
                    row (first (jdbc/execute! ds ["SELECT * FROM odd_keys"] jdbc-opts))]]
        (is (= (set (keys row)) (set (map first (drop 2 (pgmalli/as-read registry :pg.public/odd_keys {:jdbc jdbc-opts})))))
            (str builder " keys a row by its :label-fn and :qualifier-fn"))))))

(deftest a-builder-fn-as-its-symbol-its-var-or-itself-is-the-same
  (let [registry (pgmalli/registry "sample")
        q {:select [:id :nick] :from [:users] :where [:= :id :?id]}]
    (doseq [sym ['next.jdbc.result-set/as-unqualified-kebab-maps 'next.jdbc.optional/as-maps]
            :let [forms [sym (requiring-resolve sym) @(requiring-resolve sym)]]]
      (is (apply = (for [f forms] (pgmalli/as-read registry :pg.sample/users {:jdbc {:builder-fn f}}))) (str sym))
      (is (apply = (for [f forms] (h/row-schema registry q {:schema "sample" :jdbc {:builder-fn f}}))) (str sym)))))

(deftest as-read-keys-a-table-of-a-schema-with-a-dot-by-the-table-alone
  (when *db*
    (exec-sql! "CREATE SCHEMA \"My.S\"; CREATE TABLE \"My.S\".\"T.t\" (id int PRIMARY KEY); INSERT INTO \"My.S\".\"T.t\" VALUES (1);")
    (let [ds (datasource)
          registry (pgmalli/registry (files/from-database {:db *db*} "My.S"))
          row (first (jdbc/execute! ds ["SELECT * FROM \"My.S\".\"T.t\""]))]
      (is (= [:T.t/id] (keys row)))
      (is (= (keys row) (map first (drop 2 (pgmalli/as-read registry "pg.My.S/T.t" {}))))))))

(deftest jdbc-leaves-a-pooled-connection-as-it-was
  (when *db*
    (let [{:keys [user password]} *db*
          c (java.sql.DriverManager/getConnection (jdbc-url) user password)
          ;; a pool hands the same connection out again: close is a no-op here
          kept (java.lang.reflect.Proxy/newProxyInstance (.getClassLoader java.sql.Connection) (into-array Class [java.sql.Connection])
                                                         (reify java.lang.reflect.InvocationHandler
                                                           (invoke [_ _ m args] (when-not (= "close" (.getName m)) (.invoke m c args)))))
          ds (reify javax.sql.DataSource (getConnection [_] kept))
          path #(with-open [st (.createStatement c)] (let [rs (.executeQuery st "SHOW search_path")] (.next rs) (.getString rs 1)))
          before (path)]
      (ir/from-db {:datasource ds :schema "public"})
      (is (= before (path)))
      (is (.getAutoCommit c))
      (.close c))))
