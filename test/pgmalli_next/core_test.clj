(ns pgmalli-next.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            honey.sql
            [malli.core :as m]
            [malli.generator :as mg]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.generate :as generate]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.files :as files]
            [pgmalli-next.impl.registry :as registry]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.test-db :refer [*db* exec-sql! sql-literal with-postgres]])
  (:import (java.io File)))

(use-fixtures :once with-postgres)

(deftest generate-check-roundtrip
  (when *db*
    (let [out-dir (str (doto (File/createTempFile "pgmalli-gen" "") .delete))
          config {:db *db* :out-dir out-dir}
          out (files/path-for config "public")]
      (exec-sql! "CREATE TYPE mood AS ENUM ('happy', 'sad');
                  CREATE TABLE users (id bigint PRIMARY KEY, mood mood NOT NULL DEFAULT 'happy',
                                      age integer CHECK (age >= 0), nick varchar(40) CHECK (length(TRIM(BOTH FROM nick)) > 0),
                                      closed_at timestamptz, CONSTRAINT closed CHECK (mood = 'sad' OR closed_at IS NULL));
                  CREATE UNIQUE INDEX users_nick_idx ON users (nick);
                  CREATE UNIQUE INDEX users_nick_lower_idx ON users (lower(nick));
                  CREATE UNIQUE INDEX users_age_open_idx ON users (age) WHERE closed_at IS NULL;
                  CREATE UNIQUE INDEX users_age_inc_idx ON users (age) INCLUDE (nick);
                  CREATE UNIQUE INDEX closed ON users (closed_at);
                  CREATE TABLE prices (id integer PRIMARY KEY, p1 numeric(3,1), p2 numeric(3,5), p3 numeric(2,-3));
                  CREATE TABLE parted (k integer NOT NULL, v text, CONSTRAINT k_nonneg CHECK (k >= 0)) PARTITION BY RANGE (k);
                  CREATE TABLE parted_low PARTITION OF parted FOR VALUES FROM (0) TO (100);
                  CREATE TABLE parted_high PARTITION OF parted FOR VALUES FROM (100) TO (200);
                  CREATE TABLE nopart (k integer) PARTITION BY LIST (k);
                  CREATE FUNCTION touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
                  CREATE TRIGGER users_touch BEFORE INSERT ON users FOR EACH ROW EXECUTE FUNCTION touch();")
      (testing "a missing file is stale"
        (is (every? (comp nil? :file) (get (:stale (generate/check config)) "public"))))
      (testing "generate, read back, validate"
        (is (= {"public" out} (generate/generate! config)))
        (let [data (registry/read-generated-file out)]
          (is (= data (get-in (files/generated-all config) ["public" :data])))
          (is (= (slurp out) (do (generate/generate! config) (slurp out))) "byte-for-byte deterministic")
          (is (empty? (:unrendered data)) "the table CHECK is a :multi")
          (let [reg (pgmalli/registry data)]
            (is (m/validate :pg.public/users {:id 1 :mood "sad" :age 3 :nick "n" :closed_at nil} {:registry reg}))
            (is (not (m/validate :pg.public/users {:id 1 :mood "sad" :age -1 :nick "n" :closed_at nil} {:registry reg})))
            (is (not (m/validate :pg.public/users {:id 1 :mood "happy" :age 1 :nick "n" :closed_at (java.time.Instant/now)} {:registry reg}))
                "closed_at only when sad")
            (is (m/validate :pg.public.users/insert {:id 1 :nick "n"} {:registry reg}) "defaults and nullable columns may be omitted")
            (is (= [{:columns ["age"] :index true} {:columns ["age"] :index true :where true} {:columns ["closed_at"] :index true} {:columns ["nick"] :index true}] (let [s (:pg.public/users reg)] (:pg/unique (second (if (= :and (first s)) (second s) s)))))
                "unique indexes over plain columns count, key columns only, even one named as a constraint is, a partial one marked; expression ones do not")
            (is (some #(= "closed" (:pg/constraint (second %))) (drop 2 (:pg.public/users reg))) "the CHECK of that name is there too")
            (let [col (fn [k] (shape/non-null (some (fn [[c _ s]] (when (= k c) s)) (shape/column-entries (shape/schema-of reg :pg.public/prices)))))]
              (is (= [[:pg/numeric {:precision 3 :scale 1}] [:pg/numeric {:precision 3 :scale 5}] [:pg/numeric {:precision 2 :scale -3}]]
                     (map #(last (col %)) [:p1 :p2 :p3]))
                  "the scale is read as PostgreSQL stores it, signed")
              (doseq [[i [k ok bad]] (map-indexed vector [[:p1 99.94M 99.95M] [:p2 0.00999M 0.01M] [:p3 99000M 99500M]])]
                (is (m/validate (col k) ok {:registry reg}))
                (is (not (m/validate (col k) bad {:registry reg})))
                (exec-sql! (str "INSERT INTO prices (id, " (name k) ") VALUES (" (* 2 i) ", " ok ")"))
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"numeric field overflow"
                                      (exec-sql! (str "INSERT INTO prices (id, " (name k) ") VALUES (" (inc (* 2 i)) ", " bad ")")))
                    (str k ": the database draws the line where the schema does")))
              (is (m/validate :pg.public/parted {:k 150 :v nil} {:registry reg}))
              (is (not (m/validate :pg.public/parted {:k 250 :v nil} {:registry reg})) "no partition takes 250: the row schema says so")
              (is (= 1 (count (re-seq #"k_nonneg" (pr-str (:pg.public/parted reg))))) "the parent's own CHECK is not repeated from every partition")
              (is (= [{:kind :no-partition :table "public.nopart"} {:kind :row-trigger :table "public.users"} {:kind :redundant-unique :constraint "users_age_open_idx"}]
                     (map #(select-keys % [:kind (if (= :redundant-unique (:kind %)) :constraint :table)]) (:diagnostics data)))
                  "the file says what deserves a look")))))
      (testing "stale and unrendered"
        (is (nil? (:stale (generate/check config))))
        (is (empty? (:unrendered (registry/read-generated-file out))))
        (exec-sql! "ALTER TABLE users ADD COLUMN extra int;")
        (let [diffs (get (:stale (generate/check config)) "public")]
          (is (= [[:pg.public/users :extra]] (map (juxt :name :column) diffs))
              "a new column shows as itself, in the row schema (the insert schema is derived at load, not in the file)")
          (is (every? (comp nil? :file) diffs)))))))

(deftest a-foreign-table-is-a-table-a-query-reads
  (when *db*
    (let [config {:db *db* :out-dir (str (doto (File/createTempFile "pgmalli-ft" "") .delete))}]
      (exec-sql! "CREATE EXTENSION IF NOT EXISTS file_fdw; CREATE SERVER ft_files FOREIGN DATA WRAPPER file_fdw;
                  CREATE SCHEMA ft; CREATE FOREIGN TABLE ft.lines (a text) SERVER ft_files OPTIONS (program 'echo hi', format 'text');")
      (generate/generate! (assoc config :schemas ["ft"]))
      (let [reg (pgmalli/registry (registry/read-generated-file (files/path-for config "ft")))]
        (is (= [] (:problems (h/analyze reg {:select [:a] :from [:lines]} {:schema "ft"}))))))))

(deftest a-char-n-list-holds-without-trailing-spaces-other-char-n-and-arithmetic-checks-are-left-to-the-database
  (when *db*
    (let [out-dir (str (doto (File/createTempFile "pgmalli-ch" "") .delete))
          config {:db *db* :out-dir out-dir}
          sql-row (fn [{:keys [c k v n]}] (str "INSERT INTO ch.t VALUES ('" c "', '" k "', '" v "', " n ")"))]
      (exec-sql! "CREATE SCHEMA ch;
                  CREATE TABLE ch.t (c char(4) CHECK (length(c) <= 2), k char(4) CHECK (k IN ('ab', 'cd')), v varchar(3),
                                     n integer, CONSTRAINT no_overflow CHECK (n + 2147483000 > 0));")
      (generate/generate! (assoc config :schemas ["ch"]))
      (let [data (registry/read-generated-file (files/path-for config "ch"))
            reg (pgmalli/registry data)]
        (is (= ["no_overflow" "t_c_check"] (sort (map :constraint (:unrendered data)))))
        (doseq [row [{:c "ab  " :k "ab  " :v "😀😀" :n 1} {:c "ab" :k "cd" :v "abc" :n 647} {:c "ab" :k "cd " :v "a" :n 1}]]
          (is (m/validate :pg.ch/t row {:registry reg}) (pr-str row))
          (exec-sql! (sql-row row)))
        (doseq [row [{:c "a" :k "ab" :v "abcd" :n 1} {:c "a" :k " ab" :v "a" :n 1} {:c "a" :k "a" :v "a" :n 1}]]
          (is (not (m/validate :pg.ch/t row {:registry reg})) (pr-str row))
          (is (thrown? clojure.lang.ExceptionInfo (exec-sql! (sql-row row)))))))))

(deftest column-checks-take-the-strings-postgresql-takes
  (when *db*
    (let [out-dir (str (doto (File/createTempFile "pgmalli-cs" "") .delete))
          config {:db *db* :out-dir out-dir}
          checks {"r" "r ~ '^a.b$'" "l" "l LIKE 'a%'" "u" "length(trim(u)) > 0" "w" "length(w) <= 2"
                  "x" "x <> ''" "y" "btrim(y) <> ''" "z" "z ~ '^[[:alpha:]]+$'" "e" "e ~ 'b$'"
                  "o" "o ~ '^\\w+$'" "i" "i ~* '^é'" "k" "k ILIKE 'é%'"}
          strings ["a\nb" "a\tb" "\n" "\t" "\u00a0" "😀😀" "😀😀😀" "ab  " "" " " "a" "ab" "a\n" "ab\n" "abc" "abcd  " "é" "Éa" "ÉA"]]
      (exec-sql! (str "CREATE SCHEMA cs; CREATE TABLE cs.t (v varchar(3)"
                      (apply str (for [[c chk] checks] (str ", " c " text CHECK (" chk ")"))) ");"
                      "CREATE FUNCTION cs.takes(col text, val text) RETURNS boolean LANGUAGE plpgsql AS $$
                       BEGIN EXECUTE format('INSERT INTO cs.t (%I) VALUES (%L)', col, val); RETURN true;
                       EXCEPTION WHEN check_violation OR string_data_right_truncation THEN RETURN false; END $$;"))
      (generate/generate! (assoc config :schemas ["cs"]))
      (let [reg (pgmalli/registry (registry/read-generated-file (files/path-for config "cs")))
            cases (for [c (cons "v" (keys checks)), s strings] [c s (m/validate :pg.cs/t (assoc (zipmap (map keyword (cons "v" (keys checks))) (repeat nil)) (keyword c) s) {:registry reg})])]
        (is (= :map (first (:pg.cs/t reg))) "the CHECKs are column schemas")
        (exec-sql! (str "DO $$ BEGIN "
                        ;; outside ASCII a class is the server's: the schema takes what it may take
                        (apply str (for [[c s ok] cases :when (not (and ok (#{"z" "o"} c) (re-find #"[^\x00-\x7F]" s)))]
                                     (str "IF cs.takes('" c "', " (sql-literal s) ") <> " ok
                                          " THEN RAISE EXCEPTION 'schema says " ok " for % in " c "', " (sql-literal s) "; END IF;")))
                        " END $$;"))
        (is true)))))

(deftest check-reads-everything-once
  (when *db*
    (let [out-dir (str (doto (File/createTempFile "pgmalli-chk" "") .delete))
          config {:db *db* :out-dir out-dir :schemas ["chk"]}]
      (exec-sql! "CREATE SCHEMA chk; CREATE TABLE chk.empty_parted (k integer) PARTITION BY LIST (k);")
      (generate/generate! config)
      (let [{:keys [stale unrendered diagnostics]} (generate/check config)]
        (is (nil? stale))
        (is (nil? unrendered))
        (is (= [:no-partition] (map :kind (get diagnostics "chk")))))
      (is (not (contains? (generate/check config {:db? false}) :stale)) "without the database, only the files")
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key" (generate/check config {:db false})) "a misspelt option is an error")
      (let [data (registry/read-generated-file (files/path-for config "chk"))]
        (is (= [:schema :database-version :diagnostics :registry :functions :operators :unrendered :skipped] (keys data)) "read from the top: what it is, what deserves a look, the schemas")))))

(defn- registry-of [schema ddl]
  (exec-sql! ddl)
  (let [data (files/from-database {:db *db*} schema)]
    [(pgmalli/registry data) data]))

(deftest a-schemas-functions-are-the-ones-sql-calls-built-as-pg-catalogs-are
  (when *db*
    (let [[_ data] (registry-of "fx" "CREATE SCHEMA fx;
                                      CREATE FUNCTION fx.add_one(n bigint, m bigint) RETURNS bigint LANGUAGE sql AS 'SELECT n + 1';
                                      CREATE AGGREGATE fx.total(bigint) (SFUNC = fx.add_one, STYPE = bigint, INITCOND = '0');
                                      CREATE AGGREGATE fx.maybe_total(bigint) (SFUNC = fx.add_one, STYPE = bigint);
                                      CREATE FUNCTION fx.text_in(cstring) RETURNS text LANGUAGE internal IMMUTABLE STRICT AS 'textin';
                                      CREATE FUNCTION fx.am(internal) RETURNS index_am_handler LANGUAGE internal AS 'bthandler';")]
      (is (= {"add_one" [{:args ["bigint" "bigint"] :result "bigint"}]
              "maybe_total" [{:args ["bigint"] :result "bigint" :kind :a}]
              "total" [{:args ["bigint"] :result "bigint" :kind :a :non-null true}]}
             (:functions data))
          "an aggregate starting from a value is never NULL, as count; internal and cstring are no values of SQL"))))

(deftest a-partitioned-table-whose-only-partition-is-default-takes-every-row
  (when *db*
    (let [[reg] (registry-of "pdef" "CREATE SCHEMA pdef;
                                     CREATE TABLE pdef.l (id int, x int) PARTITION BY LIST (id); CREATE TABLE pdef.l1 PARTITION OF pdef.l DEFAULT;
                                     CREATE TABLE pdef.r (id int, x int) PARTITION BY RANGE (id); CREATE TABLE pdef.r1 PARTITION OF pdef.r DEFAULT;")]
      (is (m/validate :pg.pdef.l/insert {:id 1 :x 2} {:registry reg}))
      (is (m/validate :pg.pdef.r/insert {:id nil} {:registry reg})))))

(deftest a-partition-key-the-database-writes-is-left-to-it
  (when *db*
    (let [[reg] (registry-of "pid" "CREATE SCHEMA pid;
                                    CREATE TABLE pid.p (id bigint GENERATED ALWAYS AS IDENTITY, x int) PARTITION BY RANGE (id);
                                    CREATE TABLE pid.p1 PARTITION OF pid.p FOR VALUES FROM (MINVALUE) TO (100);
                                    CREATE TABLE pid.p2 PARTITION OF pid.p FOR VALUES FROM (100) TO (MAXVALUE);")]
      (is (m/validate :pg.pid.p/insert {:x 1} {:registry reg}))
      (is (some? (mg/generate :pg.pid.p/insert {:registry reg :seed 1}))))))

(deftest types-of-the-schema-are-found-by-any-name-in-arrays-and-under-domains
  (when *db*
    (let [[reg data] (registry-of "tn" "CREATE SCHEMA tn;
                                        CREATE DOMAIN tn.d0 AS varchar(10) CHECK (length(VALUE) > 1); CREATE DOMAIN tn.d1 AS tn.d0;
                                        CREATE TYPE tn.\"Mixed Case\" AS ENUM ('a'); CREATE TYPE tn.\"Mood\" AS ENUM ('ok', 'bad');
                                        CREATE TABLE tn.t (d tn.d1, mc tn.\"Mixed Case\", ma tn.\"Mood\"[], da tn.d0[]);")
          ok? #(m/validate :pg.tn/t (merge {:d nil :mc nil :ma nil :da nil} %) {:registry reg})]
      (is (empty? (:unrendered data)))
      (is (ok? {:d "ab" :mc "a" :ma ["ok" "bad"] :da ["xy"]}))
      (is (not (ok? {:d "a"})) "a domain over a domain keeps the inner domain's CHECK")
      (is (not (ok? {:mc "b"})))
      (is (not (ok? {:ma ["nope"]})) "an array of an enum holds its labels")
      (is (not (ok? {:da ["x"]})) "an array of a domain holds its values"))))

(deftest citext-checks-take-what-postgresql-takes
  (when *db*
    (let [[reg data] (registry-of "ci" "CREATE SCHEMA ci; CREATE EXTENSION IF NOT EXISTS citext SCHEMA public;
                                        CREATE DOMAIN ci.mail AS public.citext CHECK (VALUE ~ '^[a-z]+@x$');
                                        CREATE DOMAIN ci.tmail AS public.citext CHECK (VALUE::text ~ '^[a-z]+@x$');
                                        CREATE TABLE ci.t (id int PRIMARY KEY, r public.citext CHECK (r ~ '^[a-z]+$'),
                                                           l public.citext CHECK (l LIKE 'ab%'), c public.citext CHECK (c IN ('Red', 'Blue')), m ci.mail,
                                                           tr public.citext CHECK (tr::text ~ '^[a-z]+$'), tl public.citext CHECK (tl::text LIKE 'ab%'), tm ci.tmail);
                                        CREATE FUNCTION ci.takes(col text, val text) RETURNS boolean LANGUAGE plpgsql AS $$
                                        BEGIN EXECUTE format('INSERT INTO ci.t (id, %I) VALUES ((SELECT coalesce(max(id), 0) + 1 FROM ci.t), %L)', col, val); RETURN true;
                                        EXCEPTION WHEN check_violation THEN RETURN false; END $$;")
          verdicts (for [[col v] [[:r "ABC"] [:r "abc"] [:r "a1"] [:l "ABx"] [:l "xab"] [:c "red"] [:m "AB@X"] [:m "ab@y"]
                                  [:tr "ABC"] [:tr "abc"] [:tl "ABx"] [:tl "abx"] [:tm "AB@X"] [:tm "ab@x"]]]
                     [col v (m/validate :pg.ci/t (assoc {:id 1 :r nil :l nil :c nil :m nil :tr nil :tl nil :tm nil} col v) {:registry reg})])]
      (is (= ["t_c_check"] (keep :constraint (:unrendered data))) "IN on citext compares ignoring case: not rendered")
      (exec-sql! (str "DO $$ BEGIN "
                      (apply str (for [[col v ok] verdicts]
                                   (str "IF ci.takes('" (name col) "', " (sql-literal v) ") <> " ok
                                        " THEN RAISE EXCEPTION 'schema says " ok " for % in " (name col) "', " (sql-literal v) "; END IF;")))
                      " END $$;"))
      (is true "the schema agrees with PostgreSQL on every value; a citext cast to text compares with case"))))

(deftest a-domain-reads-as-the-driver-reads-its-base
  (when *db*
    (let [[reg] (registry-of "dr" "CREATE SCHEMA dr; CREATE EXTENSION IF NOT EXISTS citext SCHEMA public;
                                   CREATE DOMAIN dr.email AS public.citext CHECK (VALUE ~ '@'); CREATE DOMAIN dr.doc AS jsonb;
                                   CREATE TABLE dr.t (id int PRIMARY KEY, e dr.email, j dr.doc);")]
      (is (= [:map {:pg/primary-key ["id"] :pg/table "dr.t"}
              [:e [:maybe [:any {:pg/type "public.citext"}]]] [:id [:pg/integer {:pg/type "integer"}]] [:j [:maybe [:ref {:pg/type "doc"} :pg.dr/doc]]]]
             (pgmalli/as-read reg :pg.dr/t {}))
          "a citext a PGobject, as pgjdbc returns it; jsonb parsed, the domain's own"))))

(deftest an-exclude-constraint-is-on-the-table
  (when *db*
    (exec-sql! "CREATE EXTENSION IF NOT EXISTS btree_gist; CREATE SCHEMA ex;
                CREATE TABLE ex.slots (id int PRIMARY KEY, room int NOT NULL CHECK (room BETWEEN 1 AND 3), seat int NOT NULL,
                  EXCLUDE USING gist (room WITH =, seat WITH <>));")
    (let [g (files/from-database {:db *db*} "ex")
          registry (pgmalli/registry g)
          props (shape/row-props (shape/schema-of registry :pg.ex/slots))]
      (is (= [{:constraint "slots_room_seat_excl" :using "gist" :elements [{:column "room" :op "="} {:column "seat" :op "<>"}]}]
             (:pg/exclude props)))
      (is (= [:exclude] (map :kind (:diagnostics g))) "the schemas do not check it, and say so"))))

(deftest char-n-integer-and-domain-checks-never-refuse-what-postgresql-takes
  (when *db*
    (let [[reg] (registry-of "cn" "CREATE SCHEMA cn;
                                   CREATE DOMAIN cn.d4 AS char(4) CHECK (char_length(VALUE) <= 2);
                                   CREATE DOMAIN cn.r4 AS char(4) CHECK (VALUE ~ '^.{4}$');
                                   CREATE DOMAIN cn.di AS integer CHECK (VALUE * 2 > 0);
                                   CREATE DOMAIN cn.cc AS char(2) CHECK (VALUE ~ '^[A-Z]{2}$');
                                   CREATE DOMAIN cn.dom AS integer CHECK (VALUE <> 3);
                                   CREATE DOMAIN cn.o AS cn.dom CHECK (VALUE IN (1, 2, 5));
                                   CREATE TABLE cn.t (country char(2) NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
                                                      cur char(3) NOT NULL CHECK (cur IN ('JPY', 'USD', 'EUR')),
                                                      d cn.d4, r cn.r4, i cn.di, c cn.cc NOT NULL, o cn.o NOT NULL,
                                                      a int, b int, CHECK (b = (a * 0.5)::int), n int CHECK (n = 2.5::integer));")
          base {:country "JP" :cur "JPY" :c "JP" :o 1}
          takes? (fn [row] (try (exec-sql! (first (honey.sql/format {:insert-into :cn.t :values [row]} {:inline true}))) true
                                (catch clojure.lang.ExceptionInfo _ false)))]
      (doseq [row [{:d "ab  "} {:d "abc"} {:r "ab"} {:i 5} {:i 2000000000} {:a 5 :b 3} {:a 5 :b 2} {:n 3} {:n 2}]
              :let [row (merge base row)]]
        (when (takes? row) (is (m/validate :pg.cn.t/insert row {:registry reg}) (pr-str row)))))))

(deftest a-type-of-another-schema-says-its-schema-and-an-override-refers-to-it
  (when *db*
    (exec-sql! "CREATE SCHEMA catalog; CREATE SCHEMA wh;
                CREATE DOMAIN catalog.sku AS text CHECK (VALUE ~ '^[A-Z]{3}$'); CREATE TYPE catalog.kind AS ENUM ('a', 'b');
                CREATE TABLE wh.stock (id int PRIMARY KEY, sku catalog.sku NOT NULL, k catalog.kind);")
    (is (= [{:kind :type :table "wh.stock" :column "k" :type "catalog.kind" :type-schema "catalog"}
            {:kind :type :table "wh.stock" :column "sku" :type "catalog.sku" :type-schema "catalog"}]
           (:unrendered (files/from-database {:db *db*} "wh"))))
    (let [config {:db *db* :overrides {:wh.stock/sku [:ref :pg.catalog/sku] :wh.stock/k [:ref :pg.catalog/kind]}}
          reg (pgmalli/registry (files/from-database config "catalog") (files/from-database config "wh"))]
      (is (not (m/validate :pg.wh/stock {:id 1 :sku "abc" :k nil} {:registry reg})))
      (is (not (m/validate :pg.wh/stock {:id 1 :sku "ABC" :k "c"} {:registry reg})))
      (is (m/validate :pg.wh/stock {:id 1 :sku "ABC" :k "a"} {:registry reg}))
      (exec-sql! "INSERT INTO wh.stock VALUES (1, 'ABC', 'a')"))))

(deftest a-column-override-shapes-reads-and-writes-of-that-column
  (when *db*
    (exec-sql! "CREATE SCHEMA ov; CREATE TABLE ov.jobs (id int PRIMARY KEY, input jsonb NOT NULL, meta jsonb, n int NOT NULL CHECK (n < 100));")
    (let [config {:db *db* :schemas ["ov"] :overrides {:ov.jobs/input [:map [:prompt :string]] :ov.jobs/n [:int {:min 1}]}}
          reg (pgmalli/registry (get-in (files/generated-all config) ["ov" :data]))
          o {:schema "ov"}
          sends o
          row {:id 1 :input {:prompt "x"} :meta nil :n 5}
          read (fn [k opts] (last (second (:row (h/analyze reg {:select [k] :from [:jobs]} opts)))))]
      (is (m/validate :pg.ov/jobs row {:registry reg}))
      (doseq [bad [{:input {:prompt 1}} {:input nil} {:n 0} {:n 100}]]
        (is (not (m/validate :pg.ov/jobs (merge row bad) {:registry reg})) (pr-str bad)))
      (testing "read: the declared shape, parsed"
        (is (= [:map {:pg/type "jsonb"} [:prompt :string]] (read :input o)))
        (is (= [:int {:min 1 :max 99 :pg/type "integer" :pg/constraints ["jobs_n_check"]}] (read :n o))))
      (testing "write: the declared shape for a map sent as jsonb"
        (is (= [:or [:map {:pg/type "jsonb"} [:prompt :string]] jdbc/pg-object] (:i (:args (h/analyze reg {:update :jobs :set {:input :?i}} sends)))))
        (is (m/validate (h/values-schema reg :jobs sends) row {:registry reg}))
        (is (not (m/validate (h/set-schema reg :jobs sends) {:input {:prompt 1}} {:registry reg})))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #":overrides: :ov.jobs/inptu names no column of ov.jobs; did you mean: :ov.jobs/input"
                          (files/generated-all {:db *db* :schemas ["ov"] :overrides {:ov.jobs/inptu :map}})))))

(deftest like-with-an-escape-takes-what-postgresql-takes
  (when *db*
    (let [[reg data] (registry-of "le" "CREATE SCHEMA le; CREATE TABLE le.t (id int PRIMARY KEY,
                                          a text NOT NULL CHECK (a LIKE 'a!%%' ESCAPE '!'), b text NOT NULL CHECK (b LIKE 'b\\_%' ESCAPE '\\'),
                                          c text NOT NULL CHECK (c LIKE 'c\\%' ESCAPE ''), d text NOT NULL CHECK (d ILIKE 'd!!_' ESCAPE '!'));")
          base {:id 1 :a "a%" :b "b_" :c "c\\" :d "d!x"}
          row-sql (fn [row] (first (honey.sql/format {:insert-into :le.t :values [row]} {:inline true})))]
      (is (empty? (:unrendered data)))
      (doseq [[k v ok] [[:a "a%x" true] [:a "ax" false] [:b "b_x" true] [:b "bx" false] [:c "c\\x" true] [:c "cx" false] [:d "D!y" true] [:d "dxy" false]]
              :let [row (assoc base k v)]]
        (is (= ok (m/validate :pg.le/t row {:registry reg})) (pr-str row))
        (is (= ok (try (exec-sql! (str (row-sql row) "; DELETE FROM le.t;")) true (catch clojure.lang.ExceptionInfo _ false))) (pr-str row))))))

(deftest a-check-through-a-cast-that-changes-the-value-takes-what-postgresql-takes
  (when *db*
    (let [cases [["f" "float8" "f::integer = 2" 2.5] ["n" "numeric" "n::integer = 3" 2.5M] ["t" "text" "t::varchar(3) = 'abc'" "abcd"]
                 ["l" "text" "length(l::varchar(2)) = 2" "abc"] ["c" "text" "c::char(3) IN ('abc')" "abc  "] ["b" "boolean" "b::text = 'true'" true]
                 ["r" "real" "r::text = '0.1'" 0.1] ["g" "numeric" "g::bigint IN (1, 2)" 1.4M] ["w" "float8" "w >= 0" ##NaN]]
          [reg data] (registry-of "vc" (str "CREATE SCHEMA vc; CREATE TABLE vc.t (id int PRIMARY KEY"
                                            (apply str (for [[c t chk] cases] (str ", " c " " t " CHECK (" chk ")"))) ");"))
          unrendered (set (keep :constraint (:unrendered data)))
          base (zipmap (cons :id (map (comp keyword first) cases)) (cons 1 (repeat nil)))]
      (doseq [[i [c _ chk v]] (map-indexed vector cases)]
        (exec-sql! (str "INSERT INTO vc.t (id, " c ") VALUES (" (+ 10 i) ", " (cond (string? v) (sql-literal v) (and (float? v) (Double/isNaN v)) "'NaN'" :else v) ")"))
        (is (or (unrendered (str "t_" c "_check")) (m/validate :pg.vc/t (assoc base (keyword c) v) {:registry reg}))
            (str chk " takes " (pr-str v)))))))

(deftest an-unqualified-name-means-the-generated-schema-of-that-name
  (let [registry (pgmalli/registry "sample")
        meant (fn [f] (try (f) (catch clojure.lang.ExceptionInfo e [(ex-message e) (:did-you-mean (ex-data e))])))]
    (is (= ["the registry holds no schema \"public\"; pass {:schema \"sample\"}" nil]
           (meant #(pgmalli/as-read registry :users))))
    (is (= (pgmalli/as-read registry :pg.sample/users) (pgmalli/as-read registry :sample.users) (pgmalli/as-read registry :users {:schema "sample"}))
        "as values-schema names a table")
    (is (= [:pg.sample/users] (second (meant #(pgmalli/describe registry :users)))))
    (is (= [:pg.sample/users] (second (meant #(pgmalli/as-read registry :pg.public/users))))
        "a name of another schema means the one of the registry")))
