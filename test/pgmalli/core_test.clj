(ns pgmalli.core-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            clojure.test.check.generators
            honey.sql
            [malli.core :as m]
            [malli.generator :as mg]
            [pgmalli.core :as pgmalli]
            [pgmalli.data :as data]
            [pgmalli.generate :as generate]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.shape :as shape]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]])
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
        (is (every? (comp nil? :file) (get (generate/stale config) "public"))))
      (testing "generate, read back, validate"
        (is (= {"public" out} (generate/generate! config)))
        (let [data (files/read-edn out)]
          (is (= data (get-in (files/generated-all config) ["public" :data])))
          (is (= (slurp out) (do (generate/generate! config) (slurp out))) "byte-for-byte deterministic")
          (is (empty? (:unrendered data)) "the table CHECK is a :multi")
          (let [reg (pgmalli/registry data)]
            (is (m/validate :pg.public/users {:id 1 :mood "sad" :age 3 :nick "n" :closed_at nil} {:registry reg}))
            (is (not (m/validate :pg.public/users {:id 1 :mood "sad" :age -1 :nick "n" :closed_at nil} {:registry reg})))
            (is (not (m/validate :pg.public/users {:id 1 :mood "happy" :age 1 :nick "n" :closed_at (java.time.Instant/now)} {:registry reg}))
                "closed_at only when sad")
            (is (m/validate :pg.public.users/insert {:id 1 :nick "n"} {:registry reg}) "defaults and nullable columns may be omitted")
            (is (= [{:columns ["age"] :index true} {:columns ["closed_at"] :index true} {:columns ["nick"] :index true}] (let [s (:pg.public/users reg)] (:pg/unique (second (if (= :and (first s)) (second s) s)))))
                "unique indexes over plain columns count, key columns only, even one named as a constraint is; expression and partial ones do not")
            (is (some #(= "closed" (:error/message (second %))) (drop 2 (:pg.public/users reg))) "the CHECK of that name is there too")
            (let [col (fn [k] (pgmalli/non-null (pgmalli/column reg :pg.public/prices k)))]
              (is (= [[:pg/numeric {:precision 3 :scale 1}] [:pg/numeric {:precision 3 :scale 5}] [:pg/numeric {:precision 2 :scale -3}]]
                     (map #(last (col %)) [:p1 :p2 :p3]))
                  "the scale is read as PostgreSQL stores it, signed")
              (doseq [[k ok bad] [[:p1 99.94M 99.95M] [:p2 0.00999M 0.01M] [:p3 99000M 99500M]]]
                (is (m/validate (col k) ok {:registry reg}))
                (is (not (m/validate (col k) bad {:registry reg})))
                (exec-sql! (str "INSERT INTO prices (id, " (name k) ") VALUES (" (rand-int 1000000) ", " ok ")"))
                (is (thrown-with-msg? clojure.lang.ExceptionInfo #"numeric field overflow"
                                      (exec-sql! (str "INSERT INTO prices (id, " (name k) ") VALUES (" (rand-int 1000000) ", " bad ")")))
                    (str k ": the database draws the line where the schema does")))
              (is (m/validate :pg.public/parted {:k 150 :v nil} {:registry reg}))
              (is (not (m/validate :pg.public/parted {:k 250 :v nil} {:registry reg})) "no partition takes 250: the row schema says so")
              (is (every? #(< -1 (:k %) 200) (get (clojure.test.check.generators/generate (data/dataset-generator reg {:rows 5}) 30 1) "public.parted"))
                  "generated rows land in a partition")
              (is (= 1 (count (re-seq #"k_nonneg" (pr-str (:pg.public/parted reg))))) "the parent's own CHECK is not repeated from every partition")
              (is (= [{:kind :no-partition :table "public.nopart"} {:kind :row-trigger :table "public.users"}] (map #(select-keys % [:kind :table]) (:diagnostics data)))
                  "the file says what deserves a look")))))
      (testing "stale and unrendered"
        (is (nil? (generate/stale config)))
        (is (empty? (:unrendered (files/read-edn out))))
        (exec-sql! "ALTER TABLE users ADD COLUMN extra int;")
        (let [diffs (get (generate/stale config) "public")]
          (is (= [[:pg.public/users :extra]] (map (juxt :name :column) diffs))
              "a new column shows as itself, in the row schema (the insert schema is derived at load, not in the file)")
          (is (every? (comp nil? :file) diffs)))))))

(deftest inserts-load-into-the-database
  (when *db*
    (let [out-dir (str (doto (File/createTempFile "pgmalli-ins" "") .delete))
          config {:db *db* :out-dir out-dir}]
      (exec-sql! "CREATE SCHEMA ins;
                  CREATE TYPE ins.mood AS ENUM ('happy', 'sad');
                  CREATE TABLE ins.groups (id integer PRIMARY KEY, name text NOT NULL UNIQUE);
                  CREATE TABLE ins.users (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                          group_id integer NOT NULL REFERENCES ins.groups,
                                          parent_id bigint REFERENCES ins.users,
                                          mood ins.mood NOT NULL DEFAULT 'happy',
                                          nick text NOT NULL CHECK (nick <> ''),
                                          nick_upper text GENERATED ALWAYS AS (upper(nick)) STORED,
                                          tags text[] NOT NULL DEFAULT '{}',
                                          meta jsonb);
                  CREATE TABLE ins.hashed (k integer NOT NULL, s text NOT NULL, u uuid) PARTITION BY HASH (k, s, u);
                  CREATE TABLE ins.hashed_0 PARTITION OF ins.hashed FOR VALUES WITH (MODULUS 4, REMAINDER 0);
                  CREATE TABLE ins.hashed_1 PARTITION OF ins.hashed FOR VALUES WITH (MODULUS 4, REMAINDER 1);
                  CREATE TABLE ins.hashed_3 PARTITION OF ins.hashed FOR VALUES WITH (MODULUS 4, REMAINDER 3);")
      (generate/generate! (assoc config :schemas ["ins"]))
      (let [registry (pgmalli/registry (files/read-edn (files/path-for config "ins")))
            dataset (clojure.test.check.generators/generate (data/dataset-generator registry {:rows 4}) 30 7)
            statements (data/inserts registry dataset)
            counts (fn [] (str/join " " (for [t ["ins.groups" "ins.users" "ins.hashed"]]
                                          (str "IF (SELECT count(*) FROM " t ") <> " (count (get dataset t))
                                               " THEN RAISE EXCEPTION '" t " has the wrong number of rows'; END IF;"))))]
        (is (= [:ins.groups :ins.hashed [{:overriding-value :system} :ins.users]] (map :insert-into statements)))
        (is (= 4 (count (get dataset "ins.hashed"))) "every generated row lands in one of the three partitions (remainder 2 has none): routed as the database routes")
        (exec-sql! (str (str/join ";\n" (map #(first (honey.sql/format % {:inline true})) statements)) ";\n"
                        "DO $$ BEGIN " (counts) " END $$;"))
        (is true "every INSERT ran, identity ids kept, generated column left to the database")))))

(defn- pr-str-sql [s] (str "'" (str/replace s "'" "''") "'"))

(deftest every-opaque-literal-is-one-postgresql-reads
  (when *db*
    (exec-sql! (str "SELECT " (str/join ", " (for [[t lits] shape/opaque-literals, l lits] (str "'" (str/replace l "'" "''") "'::" t)))))
    (is true)))

(deftest char-varchar-and-integer-checks-hold-as-postgresql-holds-them
  (when *db*
    (let [out-dir (str (doto (File/createTempFile "pgmalli-ch" "") .delete))
          config {:db *db* :out-dir out-dir}
          sql-row (fn [{:keys [c k v n]}] (str "INSERT INTO ch.t VALUES ('" c "', '" k "', '" v "', " n ")"))]
      (exec-sql! "CREATE SCHEMA ch;
                  CREATE TABLE ch.t (c char(4) CHECK (length(c) <= 2), k char(4) CHECK (k IN ('ab', 'cd')), v varchar(3),
                                     n integer, CONSTRAINT no_overflow CHECK (n + 2147483000 > 0));")
      (generate/generate! (assoc config :schemas ["ch"]))
      (let [reg (pgmalli/registry (files/read-edn (files/path-for config "ch")))]
        (doseq [row [{:c "ab  " :k "ab  " :v "😀😀" :n 1} {:c "ab" :k "cd" :v "abc" :n 647}]]
          (is (m/validate :pg.ch/t row {:registry reg}) (pr-str row))
          (exec-sql! (sql-row row)))
        (doseq [row [{:c "abc " :k "ab" :v "a" :n 1} {:c "a" :k "ef" :v "a" :n 1} {:c "a" :k "ab" :v "abcd" :n 1} {:c "a" :k "ab" :v "a" :n 648}]]
          (is (not (m/validate :pg.ch/t row {:registry reg})) (pr-str row))
          (is (thrown? clojure.lang.ExceptionInfo (exec-sql! (sql-row row))) (pr-str row)))))))

(deftest column-checks-take-the-strings-postgresql-takes
  (when *db*
    (let [out-dir (str (doto (File/createTempFile "pgmalli-cs" "") .delete))
          config {:db *db* :out-dir out-dir}
          checks {"r" "r ~ '^a.b$'" "l" "l LIKE 'a%'" "u" "length(trim(u)) > 0" "w" "length(w) <= 2"
                  "x" "x <> ''" "y" "btrim(y) <> ''" "z" "z ~ '^[[:alpha:]]+$'" "e" "e ~ 'b$'"}
          strings ["a\nb" "a\tb" "\n" "\t" "\u00a0" "😀😀" "😀😀😀" "ab  " "" " " "a" "ab" "a\n" "ab\n" "abc"]]
      (exec-sql! (str "CREATE SCHEMA cs; CREATE TABLE cs.t (v varchar(3)"
                      (apply str (for [[c chk] checks] (str ", " c " text CHECK (" chk ")"))) ");"
                      "CREATE FUNCTION cs.takes(col text, val text) RETURNS boolean LANGUAGE plpgsql AS $$
                       BEGIN EXECUTE format('INSERT INTO cs.t (%I) VALUES (%L)', col, val); RETURN true;
                       EXCEPTION WHEN check_violation OR string_data_right_truncation THEN RETURN false; END $$;"))
      (generate/generate! (assoc config :schemas ["cs"]))
      (let [reg (pgmalli/registry (files/read-edn (files/path-for config "cs")))
            ;; varchar(n) cuts spaces past n off instead of refusing them; the schema refuses them
            cases (for [c (cons "v" (keys checks)), s strings :when (not= ["v" "ab  "] [c s])] [c s (m/validate :pg.cs/t (assoc (zipmap (map keyword (cons "v" (keys checks))) (repeat nil)) (keyword c) s) {:registry reg})])]
        (is (= :map (first (:pg.cs/t reg))) "the CHECKs are column schemas, not evaluated")
        (exec-sql! (str "DO $$ BEGIN "
                        (apply str (for [[c s ok] cases
                                         ;; malli's $ matches before a final newline too, PostgreSQL's does not: there
                                         ;; the schema may only be the more lenient
                                         :when (not (and ok (#{"r" "z" "e"} c) (str/ends-with? s "\n")))]
                                     (str "IF cs.takes('" c "', " (pr-str-sql s) ") <> " ok
                                          " THEN RAISE EXCEPTION 'schema says " ok " for % in " c "', " (pr-str-sql s) "; END IF;")))
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
        (is (= {} unrendered))
        (is (= [:no-partition] (map :kind (get diagnostics "chk")))))
      (is (nil? (:stale (generate/check config {:db? false}))) "without the database, only the files")
      (let [data (files/read-edn (files/path-for config "chk"))]
        (is (= [:schema :database-version :diagnostics :registry :unrendered :skipped] (keys data)) "read from the top: what it is, what deserves a look, the schemas")))))

(defn- registry-of [schema ddl]
  (exec-sql! ddl)
  (let [data (files/from-database {:db *db*} schema)]
    [(pgmalli/registry data) data]))

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

(deftest inserts-name-the-types-of-the-schema-as-they-are-spelt
  (when *db*
    (let [[reg] (registry-of "tq" "CREATE SCHEMA tq; CREATE TYPE tq.\"MixedE\" AS ENUM ('a', 'b'); CREATE DOMAIN tq.\"Dom\" AS int CHECK (VALUE > 0);
                                   CREATE TABLE tq.t (k int PRIMARY KEY, e tq.\"MixedE\" NOT NULL, ea tq.\"MixedE\"[], de tq.\"MixedE\"[], d tq.\"Dom\", da tq.\"Dom\"[], c char(3));
                                   CREATE TABLE tq.e (id serial PRIMARY KEY);")
          rows {"tq.t" [{:k 1 :e "b" :ea ["a" "b"] :de [] :d 1 :da [2] :c "bb"}] "tq.e" [{} {}]}]
      (exec-sql! (str (str/join ";\n" (map #(first (honey.sql/format % {:inline true})) (data/inserts reg rows))) ";\n"
                      "DO $$ BEGIN IF (SELECT c FROM tq.t) <> 'bb' THEN RAISE EXCEPTION 'char(3) cut'; END IF;"
                      " IF (SELECT count(*) FROM tq.e) <> 2 THEN RAISE EXCEPTION 'rows of defaults missing'; END IF; END $$;"))
      (is true "every INSERT ran: quoted types, arrays of them, empty arrays, char(n) whole, rows of defaults"))))
