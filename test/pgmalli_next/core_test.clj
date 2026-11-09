(ns pgmalli-next.core-test
  (:require [pgmalli-next.sample]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
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
            [pgmalli-next.impl.types :as types]
            [pgmalli-next.test-db :refer [generated *db* exec-sql! with-postgres]])
  (:import (java.io File)))

(use-fixtures :once with-postgres)

(defn- temp-out [prefix] (str (doto (File/createTempFile prefix "") .delete) "/pgmalli-next.edn"))

(defn- refusal
  "The ex-data of check's refusal; nil when it returns."
  [config]
  (try (with-out-str (generate/check config)) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest generate-check-roundtrip
  (when *db*
    (let [out (temp-out "pgmalli-gen")
          config {:db *db* :out out}]
      (exec-sql! "CREATE TYPE mood AS ENUM ('happy', 'sad');
                  CREATE TABLE users (id bigint PRIMARY KEY, mood mood NOT NULL DEFAULT 'happy',
                                      age integer CHECK (age >= 0), nick varchar(40), closed_at timestamptz,
                                      CONSTRAINT closed CHECK (mood = 'sad' OR closed_at IS NULL));
                  CREATE UNIQUE INDEX users_nick_idx ON users (nick);
                  CREATE UNIQUE INDEX users_nick_lower_idx ON users (lower(nick));
                  CREATE UNIQUE INDEX users_age_open_idx ON users (age) WHERE closed_at IS NULL;
                  CREATE UNIQUE INDEX users_age_inc_idx ON users (age) INCLUDE (nick);
                  CREATE UNIQUE INDEX closed ON users (closed_at);
                  CREATE TABLE prices (id integer PRIMARY KEY, p1 numeric(3,1));
                  CREATE TABLE parted (k integer NOT NULL, v text) PARTITION BY RANGE (k);
                  CREATE TABLE parted_low PARTITION OF parted FOR VALUES FROM (0) TO (100);
                  CREATE TABLE parted_high PARTITION OF parted FOR VALUES FROM (100) TO (200);
                  CREATE FUNCTION touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
                  CREATE TRIGGER users_touch BEFORE INSERT ON users FOR EACH ROW EXECUTE FUNCTION touch();")
      (testing "a missing file is missing"
        (is (= {:pgmalli/error :pgmalli.error/stale :missing out :stale nil} (select-keys (refusal config) [:pgmalli/error :missing :stale]))))
      (testing "generate, read back, validate"
        (is (= out (:out (generate/generate! config))))
        (let [data (registry/read-generated-file out)
              reg (pgmalli/registry data)]
          (is (= data (:data (files/generated config))))
          (is (= (slurp out) (do (generate/generate! config) (slurp out))) "byte-for-byte deterministic")
          (is (= ["public"] (:schemas data)))
          (is (empty? (:unrendered data)) "a CHECK is no unrendered entry: the database checks it")
          (is (m/validate :pg.public/users {:id 1 :mood "happy" :age -1 :nick "n" :closed_at (java.time.Instant/now)} {:registry reg})
              "the CHECKs are the database's")
          (is (not (m/validate :pg.public/users {:id 1 :mood "angry" :age 1 :nick "n" :closed_at nil} {:registry reg})) "an enum's labels hold")
          (is (m/validate :pg.public.users/insert {:id 1 :nick "n"} {:registry reg}) "defaults and nullable columns may be omitted")
          (is (= [{:columns ["age"] :index true} {:columns ["age"] :index true :where [[:is-null "closed_at"]]} {:columns ["closed_at"] :index true} {:columns ["nick"] :index true}]
                 (:pg/unique (second (:pg.public/users reg))))
              "unique indexes over plain columns count, key columns only, even one named as a constraint is, a partial one with its predicate; expression ones do not")
          (is (m/validate :pg.public/prices {:id 1 :p1 99.95M} {:registry reg}) "numeric(3,1)'s precision is the database's")
          (is (= [] (:problems (h/analyze reg {:select [:k :v] :from [:parted_low] :where [:= :k :?k]})))
              "a query reads a partition by name, a row of its table")
          (is (= [{:kind :unknown-column :column :nope}] (map #(select-keys % [:kind :column]) (:problems (h/analyze reg {:select [:nope] :from [:parted_high]})))))
          (is (m/validate :pg.public.parted_low/insert {:k 5} {:registry reg}))
          (is (= [{:kind :row-trigger :table "public.users"} {:kind :redundant-unique :constraint "users_age_open_idx"}]
                 (map #(select-keys % [:kind (if (= :redundant-unique (:kind %)) :constraint :table)]) (:diagnostics data)))
              "the file says what deserves a look")))
      (testing "stale"
        (is (nil? (refusal config)))
        (exec-sql! "ALTER TABLE users ADD COLUMN extra int;")
        (let [diffs (:stale (refusal config))]
          (is (= [[:pg.public/users :extra]] (map (juxt :name :column) diffs))
              "a new column shows as itself, in the row schema (the insert schema is derived at load, not in the file)")
          (is (every? (comp nil? :file) diffs)))))))

(deftest a-foreign-table-or-a-sequence-is-a-relation-a-query-reads
  (when *db*
    (let [config {:db *db* :out (temp-out "pgmalli-ft")}]
      (exec-sql! "CREATE EXTENSION IF NOT EXISTS file_fdw; CREATE SERVER ft_files FOREIGN DATA WRAPPER file_fdw;
                  CREATE SCHEMA ft; CREATE FOREIGN TABLE ft.lines (a text) SERVER ft_files OPTIONS (program 'echo hi', format 'text');
                  CREATE SEQUENCE ft.counter;")
      (generate/generate! (assoc config :schemas ["ft"]))
      (let [reg (pgmalli/registry (registry/read-generated-file (:out config)))]
        (is (= [] (:problems (h/analyze reg {:select [:a] :from [:lines]} {:schema "ft"}))))
        (is (= [:map [:last_value [:int {:pg/type "bigint"}]] [:is_called [:boolean {:pg/type "boolean"}]]]
               (:row (h/analyze reg {:select [:last_value :is_called] :from [:counter]} {:schema "ft"}))))))))

(deftest generate-and-check-print-what-is-left-out-and-what-differs
  (when *db*
    (exec-sql! "CREATE SCHEMA pr; CREATE TYPE pr.pair AS (a int, b int); CREATE TABLE pr.t (id int PRIMARY KEY, p pr.pair);")
    (let [config {:db *db* :schemas ["pr"] :out (temp-out "pgmalli-pr")}
          left-out "unrendered (1), left out of the schemas:\n  pr.t p: type pair\n    to type it, give it a schema: (pgmalli-next.core/registry generated {:shapes {:pr.t/p <malli schema>}})\n"]
      (is (= left-out (with-out-str (generate/generate! config))))
      (exec-sql! "ALTER TABLE pr.t ADD COLUMN n text")
      (is (= (str ":pg.pr/t :n\n  file: nil\n  db:   [:maybe [:string {:pg/type \"text\"}]]\n" left-out)
             (with-out-str (try (generate/check config) (catch clojure.lang.ExceptionInfo _))))))))

(deftest check-reads-everything-once
  (when *db*
    (let [config {:db *db* :out (temp-out "pgmalli-chk") :schemas ["chk" "chk2"]}]
      (exec-sql! "CREATE SCHEMA chk; CREATE TABLE chk.t (k integer); CREATE SCHEMA chk2; CREATE TABLE chk2.u (k integer);
                  CREATE FUNCTION chk.f() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
                  CREATE TRIGGER t_f AFTER INSERT ON chk.t FOR EACH ROW EXECUTE FUNCTION chk.f();")
      (generate/generate! config)
      (let [{:keys [unrendered diagnostics]} (generate/check config)]
        (is (nil? unrendered))
        (is (= [:row-trigger] (map :kind diagnostics))))
      (is (= ["chk" "chk2"] (:schemas (registry/read-generated-file (:out config)))) "one file of the schemas")
      (let [ks [:schemas :database-version :diagnostics :registry :functions :operators :sequences :unrendered]
            text (slurp (:out config))]
        (is (= (set ks) (set (keys (registry/read-generated-file (:out config))))))
        (is (apply < (map #(str/index-of text (str "\n " %)) (rest ks))) "read from the top: what it is, what deserves a look, the schemas")))))

(defn- registry-of [schema ddl]
  (exec-sql! ddl)
  (let [data (generated {:db *db*} schema)]
    [(pgmalli/registry data) data]))

(deftest an-enum-and-a-sequence-of-one-name-keep-the-enum
  (when *db*
    (let [[reg data] (registry-of "se" "CREATE SCHEMA se; CREATE SEQUENCE se.n; CREATE TYPE se.n AS ENUM ('a'); CREATE TABLE se.t (id int PRIMARY KEY, k se.n NOT NULL);")]
      (is (m/validate :pg.se/t {:id 1 :k "a"} {:registry reg}))
      (is (not (m/validate :pg.se/t {:id 1 :k "b"} {:registry reg})))
      (is (= [:sequence-named-as-enum] (map :kind (:diagnostics data)))))))

(deftest an-operator-returning-a-set-or-of-no-overload-tells-no-row-count
  (let [reg (pgmalli/registry {:schemas ["public"]
                               :operators {"public" {"###" [{:args ["integer" "integer"] :result "integer" :set true}]
                                                     "%%%" [{:args ["integer" "integer"] :result "integer"}]}}
                               :registry {:pg.public/t [:map {:pg/table "public.t" :pg/primary-key ["id"]} [:id [:int {:pg/type "integer"}]]]}})
        rows #(:rows (h/analyze reg {:select [[[(keyword %) :id 3] :n]] :from [:t] :where [:= :id 1]} {:schema "public"}))]
    (is (nil? (rows "###")))
    (is (nil? (rows "<<<>>>")) "an operator the registry has none of may")
    (is (= {:min 0 :max 1} (rows "%%%")))))

(deftest an-operator-of-a-function-returning-a-set-returns-rows-of-its-own
  (when *db*
    (let [[reg data] (registry-of "so" "CREATE SCHEMA so; CREATE TABLE so.t (id int PRIMARY KEY);
                                        CREATE FUNCTION so.upto(a int, b int) RETURNS SETOF int LANGUAGE sql AS 'SELECT generate_series(a, b)';
                                        CREATE OPERATOR so.### (LEFTARG = int, RIGHTARG = int, FUNCTION = so.upto);")]
      (is (= {"###" [{:args ["integer" "integer"] :result "integer" :set true}]} (get-in data [:operators "so"])))
      (is (nil? (:rows (h/analyze reg {:select [[[(keyword "###") :id 3] :n]] :from [:t] :where [:= :id 1]} {:schema "so"}))) "a row count it does not tell"))))

(deftest a-partial-indexs-predicate-is-told-where-each-condition-is-read-alone
  (when *db*
    (let [[reg] (registry-of "pi" "CREATE SCHEMA pi;
                                  CREATE TABLE pi.t (id int PRIMARY KEY, g int, kind text, status text, gone_at timestamptz, n int, h int, k int, vc varchar(9), ch char(3));
                                  CREATE UNIQUE INDEX pi_varchar ON pi.t (h) WHERE vc = 'on';
                                  CREATE UNIQUE INDEX pi_char_text ON pi.t (k) WHERE ch::text = 'abc';
                                  CREATE UNIQUE INDEX pi_any ON pi.t (g) WHERE kind = 'x' AND status = ANY (ARRAY['queued', 'running']);
                                  CREATE UNIQUE INDEX pi_in ON pi.t (g) WHERE kind = 'x' AND status IN ('queued', 'running');
                                  CREATE UNIQUE INDEX pi_or ON pi.t (g) WHERE kind = 'x' AND (status = 'a' OR status = 'b');
                                  CREATE UNIQUE INDEX pi_read ON pi.t (n) WHERE kind = 'x' AND gone_at IS NULL;")
          rows #(:rows (h/analyze reg {:select [:id] :from [:t] :where %} {:schema "pi"}))]
      (is (= [{:columns ["g"] :index true :where true} {:columns ["g"] :index true :where true} {:columns ["g"] :index true :where true}
              {:columns ["h"] :index true :where [[:= "vc" "on"]]} {:columns ["k"] :index true :where true}
              {:columns ["n"] :index true :where [[:= "kind" "x"] [:is-null "gone_at"]]}]
             (:pg/unique (second (:pg.pi/t reg))))
          (str "a predicate with a condition of another form (= ANY, IN, OR) is no predicate told; a varchar's cast to text, "
               "which changes no value, is read, a char(n)'s, which drops trailing blanks, not"))
      (is (= {:min 0 :max 1} (rows [:and [:= :h 1] [:= :vc "on"]])))
      (is (nil? (rows [:and [:= :k 1] [:= [:cast :ch :text] "abc"]])))
      (is (nil? (rows [:and [:= :g 1] [:= :kind "x"] [:= :status "queued"]])) "nor a key")
      (is (= {:min 0 :max 1} (rows [:and [:= :n 1] [:= :kind "x"] [:= :gone_at nil]]))))))

(deftest a-schemas-functions-are-the-ones-sql-calls-built-as-pg-catalogs-are
  (when *db*
    (let [[_ data] (registry-of "fx" "CREATE SCHEMA fx;
                                      CREATE FUNCTION fx.add_one(n bigint, m bigint) RETURNS bigint LANGUAGE sql AS 'SELECT n + 1';
                                      CREATE AGGREGATE fx.total(bigint) (SFUNC = fx.add_one, STYPE = bigint, INITCOND = '0');
                                      CREATE AGGREGATE fx.maybe_total(bigint) (SFUNC = fx.add_one, STYPE = bigint);
                                      CREATE FUNCTION fx.text_in(cstring) RETURNS text LANGUAGE internal IMMUTABLE STRICT AS 'textin';
                                      CREATE FUNCTION fx.am(internal) RETURNS index_am_handler LANGUAGE internal AS 'bthandler';
                                      CREATE DOMAIN fx.pos AS bigint; CREATE DOMAIN fx.pos2 AS fx.pos;
                                      CREATE FUNCTION fx.twice(n fx.pos2) RETURNS fx.pos LANGUAGE sql AS 'SELECT n * 2';
                                      CREATE DOMAIN fx.poss AS bigint[];
                                      CREATE FUNCTION fx.firsts(a fx.pos2[], b fx.poss[], OUT c fx.pos[]) LANGUAGE sql AS 'SELECT a';")]
      (is (= {"add_one" [{:args ["bigint" "bigint"] :result "bigint"}]
              "firsts" [{:args ["bigint[]" "bigint[]"] :result "bigint[]" :columns [["c" "bigint[]"]]}]
              "maybe_total" [{:args ["bigint"] :result "bigint" :kind :a}]
              "total" [{:args ["bigint"] :result "bigint" :kind :a}]
              "twice" [{:args ["bigint"] :result "bigint"}]}
             (get-in data [:functions "fx"]))
          (str "internal and cstring are no values of SQL; a domain is its base type, an array of one the array of its base; "
               "a schema's aggregate is no count: its transition function may return NULL whatever it starts from"))
      (is (= [:map [:x [:maybe [:int {:pg/type "bigint"}]]]] (:row (h/analyze (pgmalli/registry data) {:select [[[:fx.twice 2] :x]]} {:schema "fx"})))))))

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
      (let [row (mg/generate :pg.pid.p/insert {:registry reg :seed 1})]
        (is (not (contains? row :id)))
        (exec-sql! (str "INSERT INTO pid.p (x) VALUES (" (or (:x row) "NULL") ")"))))))

(deftest a-partition-is-a-table-of-its-own-schema
  (when *db*
    (exec-sql! "CREATE SCHEMA pt; CREATE SCHEMA pt_old;
                CREATE FUNCTION pt.touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN NEW.x := 1; RETURN NEW; END $$;
                CREATE TABLE pt.\"Parent X\" (id int, x int NOT NULL, PRIMARY KEY (id)) PARTITION BY RANGE (id);
                CREATE TABLE pt.\"Pq One\" PARTITION OF pt.\"Parent X\" (x DEFAULT 7) FOR VALUES FROM (0) TO (10);
                CREATE TABLE pt.p2 PARTITION OF pt.\"Parent X\" FOR VALUES FROM (10) TO (20);
                CREATE TRIGGER fill BEFORE INSERT ON pt.p2 FOR EACH ROW EXECUTE FUNCTION pt.touch();
                CREATE TABLE pt_old.p0 PARTITION OF pt.\"Parent X\" FOR VALUES FROM (-10) TO (0);")
    (let [data (generated {:db *db*} "pt" "pt_old")
          reg (pgmalli/registry data)
          row #(get-in data [:registry %])]
      (is (= [:map {:pg/table "pt.Parent X" :pg/primary-key ["id"] :pg/before-triggers {:insert ["fill"]}}
              [:id [:pg/integer {:pg/type "integer"}]] [:x [:pg/integer {:pg/type "integer"}]]]
             (row "pg.pt/Parent X"))
          "the partitioned table names none of its partitions, but the BEFORE triggers a row written to it fires")
      (is (= [] (:problems (h/analyze reg {:insert-into (keyword "pt.Parent X") :values [{:id 11}]}))) "which may fill it")
      (is (= {:default 7 :pg/default 7 :pg/type "integer"} (shape/column-props (last (last (row "pg.pt/Pq One"))))) "a partition's own DEFAULT")
      (is (m/validate "pg.pt.Pq One/insert" {:id 1} {:registry reg}))
      (is (= {:insert ["fill"]} (:pg/before-triggers (shape/row-props (row :pg.pt/p2)))) "a partition's own BEFORE trigger")
      (is (= [] (:problems (h/analyze reg {:insert-into :pt.p2 :values [{:id 11}]}))) "which may fill a NOT NULL column")
      (is (seq (:problems (h/analyze reg {:insert-into :pt_old.p0 :values [{:id -1}]}))) "a partition without one may not")
      (is (= [] (:problems (h/analyze reg {:select [:id :x] :from [:pt_old.p0]}))) "a partition in another schema, in its schema")
      (is (= ["id"] (:pg/primary-key (shape/row-props (row :pg.pt_old/p0))))))))

(deftest a-trigger-on-a-partitioned-table-is-its-partitions-once
  (when *db*
    (let [[_ data] (registry-of "pc" "CREATE SCHEMA pc; CREATE FUNCTION pc.touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
                                      CREATE TABLE pc.p (id int) PARTITION BY RANGE (id); CREATE TABLE pc.p1 PARTITION OF pc.p FOR VALUES FROM (0) TO (10);
                                      CREATE TRIGGER fill BEFORE INSERT ON pc.p FOR EACH ROW EXECUTE FUNCTION pc.touch();")]
      (is (= [{:insert ["fill"]} {:insert ["fill"]}] (map #(:pg/before-triggers (shape/row-props (get-in data [:registry %]))) [:pg.pc/p :pg.pc/p1]))))))

(deftest a-trigger-that-fires-for-replication-alone-is-none
  (when *db*
    (let [[_ data] (registry-of "rp" "CREATE SCHEMA rp; CREATE FUNCTION rp.touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
                                      CREATE TABLE rp.t (id int NOT NULL); CREATE TABLE rp.a (id int NOT NULL);
                                      CREATE TRIGGER fill BEFORE INSERT ON rp.t FOR EACH ROW EXECUTE FUNCTION rp.touch();
                                      CREATE TRIGGER fill BEFORE INSERT ON rp.a FOR EACH ROW EXECUTE FUNCTION rp.touch();
                                      ALTER TABLE rp.t ENABLE REPLICA TRIGGER fill; ALTER TABLE rp.a ENABLE ALWAYS TRIGGER fill;")]
      (is (nil? (:pg/before-triggers (shape/row-props (get-in data [:registry :pg.rp/t])))))
      (is (= {:insert ["fill"]} (:pg/before-triggers (shape/row-props (get-in data [:registry :pg.rp/a]))))))))

(deftest an-unnamed-out-argument-is-the-column-postgresql-names-column-n
  (when *db*
    (let [[_ data] (registry-of "uo" "CREATE SCHEMA uo;
                                      CREATE FUNCTION uo.outs(OUT int, OUT text) LANGUAGE sql AS $$SELECT 1, 'a'$$;
                                      CREATE FUNCTION uo.outs2(OUT x int, OUT text) LANGUAGE sql AS $$SELECT 1, 'a'$$;
                                      CREATE FUNCTION uo.out1(OUT int) LANGUAGE sql AS $$SELECT 1$$;
                                      CREATE FUNCTION uo.out1x(OUT x int) LANGUAGE sql AS $$SELECT 1$$;
                                      CREATE TYPE uo.pair AS (id int, name text);
                                      CREATE FUNCTION uo.out1c(OUT x uo.pair) LANGUAGE sql AS $$SELECT ROW(1, 'a')::uo.pair$$;
                                      CREATE FUNCTION uo.tab1c() RETURNS TABLE (x uo.pair) LANGUAGE sql AS $$SELECT ROW(1, 'a')::uo.pair$$;")]
      (is (= [[["column1" "integer"] ["column2" "text"]] [["x" "integer"] ["column2" "text"]] nil [["x" "integer"]]
              [["id" "integer"] ["name" "text"]] [["id" "integer"] ["name" "text"]]]
             (map #(get-in data [:functions "uo" % 0 :columns]) ["outs" "outs2" "out1" "out1x" "out1c" "tab1c"]))
          "one unnamed is the function's value, no column; one of a composite type its type's columns"))))

(deftest a-not-null-not-valid-column-is-nullable-as-the-rows-before-it-may-be
  (when *db*
    (let [[_ data] (registry-of "nv" "CREATE SCHEMA nv; CREATE TABLE nv.t (a int);")]
      ;; NOT NULL NOT VALID is PostgreSQL 18's
      (when (<= 18 (parse-long (types/label-version (:database-version data))))
        (let [[_ data] (registry-of "nv2" "CREATE SCHEMA nv2; CREATE TABLE nv2.t (a int); INSERT INTO nv2.t VALUES (NULL);
                                           ALTER TABLE nv2.t ADD CONSTRAINT a_nn NOT NULL a NOT VALID;")]
          (is (shape/maybe? (last (get-in data [:registry :pg.nv2/t 2]))))
          (is (= [[:not-valid "a_nn"]] (map (juxt :kind :constraint) (:diagnostics data)))))))))

(deftest a-trigger-of-one-name-on-two-partitions-is-named-once
  (when *db*
    (let [[_ data] (registry-of "tg" "CREATE SCHEMA tg; CREATE FUNCTION tg.touch() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RETURN NEW; END $$;
                                      CREATE TABLE tg.p (id int) PARTITION BY RANGE (id);
                                      CREATE TABLE tg.p1 PARTITION OF tg.p FOR VALUES FROM (0) TO (10); CREATE TABLE tg.p2 PARTITION OF tg.p FOR VALUES FROM (10) TO (20);
                                      CREATE TRIGGER trg BEFORE INSERT ON tg.p1 FOR EACH ROW EXECUTE FUNCTION tg.touch();
                                      CREATE TRIGGER trg BEFORE INSERT OR UPDATE ON tg.p2 FOR EACH ROW EXECUTE FUNCTION tg.touch();")]
      (is (= {:insert ["trg"] :update ["trg"]} (:pg/before-triggers (shape/row-props (get-in data [:registry :pg.tg/p]))))))))

(deftest a-key-whose-index-is-invalid-is-no-key
  (when *db*
    (let [[reg data] (registry-of "ik" "CREATE SCHEMA ik;
                                        CREATE TABLE ik.p (id int NOT NULL, k int) PARTITION BY RANGE (id); CREATE TABLE ik.p1 PARTITION OF ik.p FOR VALUES FROM (0) TO (10);
                                        ALTER TABLE ONLY ik.p ADD PRIMARY KEY (id);
                                        CREATE TABLE ik.u (id int, k int) PARTITION BY RANGE (id); CREATE TABLE ik.u1 PARTITION OF ik.u FOR VALUES FROM (0) TO (10);
                                        ALTER TABLE ONLY ik.u ADD UNIQUE (id, k);")]
      (is (= [{:pg/table "ik.p"} {:pg/table "ik.u"}] (map #(shape/row-props (get-in data [:registry %])) [:pg.ik/p :pg.ik/u])))
      (is (nil? (:rows (h/analyze reg {:select [:*] :from [:p] :where [:= :id 1]} {:schema "ik"})))
          "ALTER TABLE ONLY leaves the index invalid until each partition's is attached: rows may repeat the key"))))

(deftest a-type-named-as-an-extensions-is-the-extensions-alone-where-the-extension-made-it
  (when *db*
    (let [[reg data] (registry-of "xn" "CREATE SCHEMA xn; CREATE EXTENSION IF NOT EXISTS citext SCHEMA public;
                                        CREATE TYPE xn.citext AS (a int); CREATE TABLE xn.t (c xn.citext, d public.citext);")]
      (is (= [:maybe [:any {:pg/type "citext"}]] (last (first (shape/column-entries (get-in data [:registry :pg.xn/t]))))))
      (is (= [{:kind :type :table "xn.t" :column "c" :type "citext"}] (:unrendered data)))
      (is (= [:maybe [:string {:pg/type "public.citext"}]] (last (second (shape/column-entries (get-in data [:registry :pg.xn/t]))))))
      (is (m/validate :pg.xn/t {:c nil :d "a"} {:registry reg})))))

(deftest a-unique-index-a-foreign-key-references-is-a-key
  (when *db*
    (let [[reg] (registry-of "fu" "CREATE SCHEMA fu; CREATE TABLE fu.r (id int PRIMARY KEY, code text NOT NULL);
                                   CREATE UNIQUE INDEX r_code ON fu.r (code);
                                   CREATE TABLE fu.s (id int PRIMARY KEY, code text REFERENCES fu.r (code));")]
      (is (= [{:columns ["code"] :index true}] (:pg/unique (shape/row-props (shape/schema-of reg :pg.fu/r)))))
      (is (= {:min 0 :max 1} (:rows (h/analyze reg {:select [:id] :from [:r] :where [:= :code "a"]} {:schema "fu"})))))))

(deftest types-of-the-schema-are-found-by-any-name-in-arrays-and-under-domains
  (when *db*
    (let [[reg data] (registry-of "tn" "CREATE SCHEMA tn;
                                        CREATE DOMAIN tn.d0 AS varchar(10) CHECK (length(VALUE) > 1); CREATE DOMAIN tn.d1 AS tn.d0;
                                        CREATE TYPE tn.\"Mixed Case\" AS ENUM ('a'); CREATE TYPE tn.\"Mood\" AS ENUM ('ok', 'bad');
                                        CREATE TABLE tn.t (d tn.d1, mc tn.\"Mixed Case\", ma tn.\"Mood\"[], da tn.d0[]);")
          ok? #(m/validate :pg.tn/t (merge {:d nil :mc nil :ma nil :da nil} %) {:registry reg})]
      (is (empty? (:unrendered data)))
      (is (ok? {:d "ab" :mc "a" :ma ["ok" "bad"] :da ["xy"]}))
      (is (ok? {:d "a"}) "a domain over a domain is its base type: its CHECK is the database's")
      (is (not (ok? {:mc "b"})))
      (is (not (ok? {:ma ["nope"]})) "an array of an enum holds its labels")
      (is (not (ok? {:da [1]})) "an array of a domain holds its base type's values"))))

(deftest a-domain-reads-as-the-driver-reads-its-base
  (when *db*
    (let [[reg] (registry-of "dr" "CREATE SCHEMA dr; CREATE EXTENSION IF NOT EXISTS citext SCHEMA public;
                                   CREATE DOMAIN dr.email AS public.citext CHECK (VALUE ~ '@'); CREATE DOMAIN dr.doc AS jsonb;
                                   CREATE TABLE dr.t (id int PRIMARY KEY, e dr.email, j dr.doc);")]
      (is (= [:map {:pg/primary-key ["id"] :pg/table "dr.t"}
              [:e [:maybe [:any {:pg/type "public.citext"}]]] [:id [:pg/integer {:pg/type "integer"}]] [:j [:maybe [:any {:pg/domain "doc" :pg/type "jsonb"}]]]]
             (pgmalli/as-read reg :pg.dr/t {}))
          "a citext a PGobject, as pgjdbc returns it; a domain its base type"))))

(deftest an-exclude-constraint-is-on-the-table
  (when *db*
    (exec-sql! "CREATE EXTENSION IF NOT EXISTS btree_gist; CREATE SCHEMA ex;
                CREATE TABLE ex.slots (id int PRIMARY KEY, room int NOT NULL CHECK (room BETWEEN 1 AND 3), seat int NOT NULL,
                  EXCLUDE USING gist (room WITH =, seat WITH <>));")
    (let [g (generated {:db *db*} "ex")
          registry (pgmalli/registry g)
          props (shape/row-props (shape/schema-of registry :pg.ex/slots))]
      (is (= [{:constraint "slots_room_seat_excl" :using "gist" :elements [{:column "room" :op "="} {:column "seat" :op "<>"}]}]
             (:pg/exclude props)))
      (is (= [:exclude] (map :kind (:diagnostics g))) "the schemas do not check it, and say so"))))

(deftest a-type-of-another-schema-says-its-schema-and-an-override-refers-to-it
  (when *db*
    (exec-sql! "CREATE SCHEMA catalog; CREATE SCHEMA wh;
                CREATE DOMAIN catalog.sku AS text CHECK (VALUE ~ '^[A-Z]{3}$'); CREATE TYPE catalog.kind AS ENUM ('a', 'b');
                CREATE TABLE wh.stock (id int PRIMARY KEY, sku catalog.sku NOT NULL, k catalog.kind);")
    (is (= [{:kind :type :table "wh.stock" :column "k" :type "catalog.kind" :type-schema "catalog"}]
           (:unrendered (generated {:db *db*} "wh")))
        "another schema's domain is its base type, as one of the schema's own is")
    (is (= [] (:unrendered (generated {:db *db*} "catalog" "wh"))) "an enum of another schema of the file is no column left out")
    (let [reg (pgmalli/registry (:data (files/generated {:db *db* :schemas ["catalog" "wh"]})) {:shapes {:wh.stock/k [:ref :pg.catalog/kind]}})]
      (is (not (m/validate :pg.wh/stock {:id 1 :sku nil :k nil} {:registry reg})))
      (is (not (m/validate :pg.wh/stock {:id 1 :sku "ABC" :k "c"} {:registry reg})))
      (is (m/validate :pg.wh/stock {:id 1 :sku "ABC" :k "a"} {:registry reg}))
      (exec-sql! "INSERT INTO wh.stock VALUES (1, 'ABC', 'a')"))))

(deftest an-enum-of-another-schema-is-its-type-whatever-its-name-and-nullability
  (when *db*
    (exec-sql! "CREATE SCHEMA \"Ot s\"; CREATE SCHEMA uses;
                CREATE TYPE \"Ot s\".date AS ENUM ('d1'); CREATE TYPE \"Ot s\".\"Mood\" AS ENUM ('q');
                CREATE TABLE uses.t (id int PRIMARY KEY, d \"Ot s\".date, dn \"Ot s\".date NOT NULL,
                                     q \"Ot s\".\"Mood\" NOT NULL, qs \"Ot s\".\"Mood\"[] NOT NULL, real date);")
    (let [reg (pgmalli/registry (generated {:db *db*} "Ot s" "uses"))
          read (pgmalli/as-read reg :pg.uses/t)]
      (is (= {:d [:maybe [:enum {:pg/type "\"Ot s\".date"} :d1]]
              :dn [:enum {:pg/type "\"Ot s\".date"} :d1]
              :q [:enum {:pg/type "\"Ot s\".\"Mood\""} :q]
              :qs [:vector {:pg/type "\"Ot s\".\"Mood\"[]"} [:maybe [:enum :q]]]
              :real [:maybe [:time/local-date {:pg/type "date"}]]}
             (dissoc (into {} (drop 2 read)) :id))
          "a date of another schema is no pg_catalog date; a NOT NULL column and a quoted name of one alike"))))

(deftest a-column-shape-shapes-reads-and-writes-of-that-column
  (when *db*
    (exec-sql! "CREATE SCHEMA ov; CREATE TABLE ov.jobs (id int PRIMARY KEY, input jsonb NOT NULL, meta jsonb, n int NOT NULL CHECK (n < 100));")
    (let [data (:data (files/generated {:db *db* :schemas ["ov"]}))
          reg (pgmalli/registry data {:shapes {:ov.jobs/input [:map [:prompt :string]] :ov.jobs/n [:int {:min 1}]}})
          o {:schema "ov"}
          sends o
          row {:id 1 :input {:prompt "x"} :meta nil :n 5}
          read (fn [k opts] (last (second (:row (h/analyze reg {:select [k] :from [:jobs]} opts)))))]
      (is (m/validate :pg.ov/jobs row {:registry reg}))
      (doseq [bad [{:input {:prompt 1}} {:input nil} {:n 0}]]
        (is (not (m/validate :pg.ov/jobs (merge row bad) {:registry reg})) (pr-str bad)))
      (testing "read: the declared shape, parsed"
        (is (= [:map {:pg/type "jsonb"} [:prompt :string]] (read :input o)))
        (is (= [:int {:min 1 :pg/type "integer"}] (read :n o))))
      (testing "write: the declared shape for a map sent as jsonb"
        (is (= [:or [:map {:pg/type "jsonb"} [:prompt :string]] jdbc/pg-object] (:i (:args (h/analyze reg {:update :jobs :set {:input :?i}} sends)))))
        ;; HoneySQL writes a map in :values or :set as a subquery: the document is [:lift m]
        (is (m/validate (h/values-schema reg :jobs sends) (update row :input #(vector :lift %)) {:registry reg}))
        (is (not (m/validate (h/values-schema reg :jobs sends) row {:registry reg})))
        (is (not (m/validate (h/set-schema reg :jobs sends) {:input [:lift {:prompt 1}]} {:registry reg}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":shapes: :ov.jobs/inptu names no column of ov.jobs; did you mean: :ov.jobs/input"
                            (pgmalli/registry data {:shapes {:ov.jobs/inptu :map}}))))))

(deftest an-unqualified-name-means-the-generated-schema-of-that-name
  (let [registry (pgmalli/registry pgmalli-next.sample/sample-data)
        meant (fn [f] (try (f) (catch clojure.lang.ExceptionInfo e [(ex-message e) (:did-you-mean (ex-data e))])))]
    (is (= ["the registry holds no schema \"public\"; pass {:schema \"sample\"}" nil]
           (meant #(pgmalli/as-read registry :users))))
    (is (= (pgmalli/as-read registry :pg.sample/users) (pgmalli/as-read registry :sample.users) (pgmalli/as-read registry :users {:schema "sample"}))
        "as values-schema names a table")
    (is (= [:pg.sample/users] (second (meant #(pgmalli/describe registry :users)))))
    (is (= [:pg.sample/users] (second (meant #(pgmalli/as-read registry :pg.public/users))))
        "a name of another schema means the one of the registry")))
