(ns pgmalli.catalog-trust-test
  "Reading a schema whose owner is not trusted: functions and operators it defines that
   ir.sql could resolve to instead of pg_catalog's must not run."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli.impl.ir :as ir]
            [pgmalli.test-db :refer [*db* datasource exec-sql! jdbcUrl with-postgres]]))

(use-fixtures :once with-postgres)

(def ^:private shadowing
  "Objects in schema evil, each raising when called: an overload of a function ir.sql calls, and
   operators with the exact argument types of comparisons pg_catalog only matches by coercion."
  "CREATE SCHEMA evil;
   CREATE TYPE evil.mood AS ENUM ('ok');
   CREATE DOMAIN evil.word AS text CHECK (VALUE <> '');
   CREATE TABLE evil.t (id int PRIMARY KEY, w evil.word, m evil.mood, n varchar(3) UNIQUE, CHECK (id > 0));
   CREATE TABLE evil.p (id int) PARTITION BY RANGE (id);
   CREATE TABLE evil.p1 PARTITION OF evil.p FOR VALUES FROM (0) TO (10);
   CREATE FUNCTION evil.trg() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END';
   CREATE TRIGGER tr BEFORE INSERT ON evil.t FOR EACH ROW EXECUTE FUNCTION evil.trg();
   CREATE FUNCTION evil.pwned(what text) RETURNS boolean LANGUAGE plpgsql AS
     $$BEGIN RAISE EXCEPTION 'pwned by %', what; END$$;
   CREATE FUNCTION evil.current_setting(text) RETURNS text LANGUAGE plpgsql AS
     $$BEGIN PERFORM evil.pwned('current_setting'); RETURN ''; END$$;
   CREATE FUNCTION evil.to_jsonb(pg_catalog.pg_constraint) RETURNS jsonb LANGUAGE plpgsql AS
     $$BEGIN PERFORM evil.pwned('to_jsonb'); RETURN NULL; END$$;
   CREATE FUNCTION evil.oid_int(oid, integer) RETURNS boolean LANGUAGE plpgsql AS
     $$BEGIN RETURN evil.pwned('oid = integer'); END$$;
   CREATE OPERATOR evil.= (LEFTARG = oid, RIGHTARG = integer, FUNCTION = evil.oid_int);
   CREATE FUNCTION evil.oid_regtype(oid, regtype) RETURNS boolean LANGUAGE plpgsql AS
     $$BEGIN RETURN evil.pwned('oid = regtype'); END$$;
   CREATE OPERATOR evil.= (LEFTARG = oid, RIGHTARG = regtype, FUNCTION = evil.oid_regtype);
   CREATE FUNCTION evil.oid_regclass(oid, regclass) RETURNS boolean LANGUAGE plpgsql AS
     $$BEGIN RETURN evil.pwned('oid = regclass'); END$$;
   CREATE OPERATOR evil.= (LEFTARG = oid, RIGHTARG = regclass, FUNCTION = evil.oid_regclass);
   CREATE FUNCTION evil.name_name(name, name) RETURNS text LANGUAGE plpgsql AS
     $$BEGIN PERFORM evil.pwned('name || name'); RETURN ''; END$$;
   CREATE OPERATOR evil.|| (LEFTARG = name, RIGHTARG = name, FUNCTION = evil.name_name);
   CREATE FUNCTION evil.int2_int4(int2, int4) RETURNS int2 LANGUAGE plpgsql AS
     $$BEGIN PERFORM evil.pwned('int2 & int4'); RETURN 0; END$$;
   CREATE OPERATOR evil.& (LEFTARG = int2, RIGHTARG = int4, FUNCTION = evil.int2_int4);")

(deftest a-schema-cannot-run-its-own-functions-or-operators-in-the-reading-session
  (when *db*
    (exec-sql! shadowing)
    (let [ir (ir/from-db (assoc *db* :schema "evil"))]
      (is (= #{"t" "p" "p1"} (set (keys (:tables ir)))))
      (is (= #{"mood"} (set (keys (:types ir))))))
    (when-not (System/getProperty "babashka.version")
      (let [{:keys [user password]} *db*
            ir (ir/from-db {:jdbcUrl (jdbcUrl) :user user :password password :schema "evil"})]
        (is (= #{"t" "p" "p1"} (set (keys (:tables ir)))))))))

(deftest a-schema-name-written-into-the-jdbc-query-stays-one-identifier
  (when (and *db* (not (System/getProperty "babashka.version")))
    (let [{:keys [user password]} *db*
          schema ":'schema'\"; CREATE TABLE public.injected (); COMMIT; --"]
      (is (= :pgmalli.error/invalid-config
             (:pgmalli/error (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                                   (ir/from-db {:jdbcUrl (jdbcUrl) :user user :password password :schema schema})))))))
      (is (not (contains? (:tables (ir/from-db (assoc *db* :schema "public"))) "injected"))))))

(deftest a-schema-name-stays-one-literal-whatever-standard-conforming-strings-says
  (when (and *db* (not (System/getProperty "babashka.version")))
    (exec-sql! "DROP ROLE IF EXISTS scs_off; CREATE ROLE scs_off LOGIN PASSWORD 'pw'; ALTER ROLE scs_off SET standard_conforming_strings = off;")
    (let [schema "x\\') SELECT 1; CREATE TABLE public.injected_scs (); COMMIT; --"]
      (is (thrown? clojure.lang.ExceptionInfo (ir/from-db {:jdbcUrl (jdbcUrl) :user "scs_off" :password "pw" :schema schema})))
      (is (not (contains? (:tables (ir/from-db (assoc *db* :schema "public"))) "injected_scs"))))
    (exec-sql! "DROP SCHEMA IF EXISTS scs CASCADE; CREATE SCHEMA scs; CREATE TABLE scs.t (k int, s text); CREATE UNIQUE INDEX t_k ON scs.t (k) WHERE s = 'a';")
    (is (contains? (get-in (ir/from-db {:jdbcUrl (jdbcUrl) :user "scs_off" :password "pw" :schema "scs"}) [:tables "t" :constraints]) "t_k (index)")
        "ir.sql's own literals read as written: the partial index's predicate is parsed")))

(def ^:private shadow-types
  "The types ir.sql's operands are of, and arrays of some: an operator of the read schema on any
   pair of them, or an aggregate on any one, is what an exact match would resolve to."
  ["oid" "regtype" "regclass" "regproc" "int2" "int4" "int8" "text" "name" "\"char\"" "bool" "json" "jsonb"
   "numeric" "varchar" "bpchar" "oid[]" "int2[]" "text[]" "name[]" "regtype[]" "\"char\"[]" "int2vector"
   "oidvector" "pg_node_tree"])

(def ^:private infix-operators
  ["=" "<>" "<" ">" "<=" ">=" "~" "~~" "!~" "~*" "&&" "@>" "<@" "||" "->>" "->" "&" "-" "+" "|" "*" "/" "%"])

(def ^:private prefix-operators ["-" "~" "+"])

(def ^:private aggregates ["min" "max" "count" "bool_or" "bool_and"])

(defn- shadowing-everything
  "SQL defining in schema s, for every pair of shadow-types, every infix operator ir.sql may
   write, a prefix operator of each type, and min, max, count, bool_or and bool_and of each."
  [s]
  (let [id #(-> % (str/replace "\"" "") (str/replace "[]" "_a"))
        fs (for [l shadow-types r shadow-types]
             [(str "CREATE FUNCTION " s ".f_" (id l) "__" (id r) "(" l ", " r ") RETURNS boolean LANGUAGE sql AS 'SELECT true'")
              (for [op infix-operators]
                (str "CREATE OPERATOR " s "." op " (LEFTARG = " l ", RIGHTARG = " r ", FUNCTION = " s ".f_" (id l) "__" (id r) ")"))])
        ps (for [t shadow-types]
             [(str "CREATE FUNCTION " s ".p_" (id t) "(" t ") RETURNS boolean LANGUAGE sql AS 'SELECT true'")
              (for [op prefix-operators] (str "CREATE OPERATOR " s "." op " (RIGHTARG = " t ", FUNCTION = " s ".p_" (id t) ")"))
              (str "CREATE FUNCTION " s ".s_" (id t) "(" t ", " t ") RETURNS " t " LANGUAGE sql AS 'SELECT $2'")
              (for [agg aggregates]
                (str "CREATE AGGREGATE " s "." agg "(" t ") (SFUNC = " s ".s_" (id t) ", STYPE = " t ")"))])]
    (str/join ";\n" (flatten (concat [(str "CREATE SCHEMA " s)] fs ps)))))

(defn- ir-view-deps
  "Rows of sql over ir.sql's query made a temporary view irv, with the read schema s on the
   search_path as ir.sql sets it: d, pg_depend's rows of the view, where what its operators and
   functions resolved to is but pg_catalog's (pinned, of no row), and r, its rewrite rule's
   query tree as text, where every operator (:opno) and aggregate (:aggfnoid) it calls is."
  [s sql]
  (let [ir-sql (slurp (io/resource "pgmalli/ir.sql"))
        [_ query] (re-find #"(?s)SET TRANSACTION READ ONLY;\s*(.*);\s*$" ir-sql)
        query (-> query (str/replace ":'schema'" (str "'" s "'")) (str/replace ":\"schema\"" (str "\"" s "\"")))
        jdbc (requiring-resolve 'next.jdbc/execute!)]
    (with-open [c ((requiring-resolve 'next.jdbc/get-connection) (datasource))]
      (jdbc c [(str "SET search_path TO pg_catalog, \"" s "\", pg_temp")])
      (jdbc c [(str "CREATE TEMP VIEW irv AS " query)])
      (jdbc c ["SET search_path TO pg_catalog"])
      (jdbc c [(str "WITH d AS (SELECT d.refclassid, d.refobjid FROM pg_depend d
                               WHERE d.classid = 'pg_rewrite'::regclass::oid
                                 AND d.objid = (SELECT r.oid FROM pg_rewrite r WHERE r.ev_class = 'irv'::regclass::oid)),
                    r AS (SELECT ev_action::text AS a FROM pg_rewrite WHERE ev_class = 'irv'::regclass::oid) " sql)]
            {:builder-fn (requiring-resolve 'next.jdbc.result-set/as-unqualified-maps)}))))

(deftest every-operator-ir-sql-calls-is-one-the-shadowing-test-defines-over-its-types-and-every-aggregate-qualified
  ;; else the test below would pass for the want of a shadow
  (when (and *db* (not (System/getProperty "babashka.version")))
    (let [shadowed (set (map :t ((requiring-resolve 'next.jdbc/execute!) (datasource)
                                 [(str "SELECT t::regtype::text AS t FROM unnest(ARRAY[" (str/join ", " (map #(str "'" (str/replace % "'" "''") "'") shadow-types)) "]) t")]
                                 {:builder-fn (requiring-resolve 'next.jdbc.result-set/as-unqualified-maps)})))
          ops (ir-view-deps "public" "SELECT o.oprname AS op, o.oprleft::regtype::text AS l, o.oprright::regtype::text AS r FROM pg_operator o
                                      WHERE o.oid::text IN (SELECT (regexp_matches(a, ':opno (\\d+)', 'g'))[1] FROM r)")
          aggs (ir-view-deps "public" "SELECT p.proname AS f FROM pg_proc p
                                       WHERE p.oid::text IN (SELECT (regexp_matches(a, ':aggfnoid (\\d+)', 'g'))[1] FROM r)")]
      (is (seq ops))
      (is (seq aggs))
      (doseq [{:keys [op l r]} ops
              ;; OPERATOR(pg_catalog.op) resolves in pg_catalog alone
              :when (not (str/includes? (slurp (io/resource "pgmalli/ir.sql")) (str "OPERATOR(pg_catalog." op ")")))]
        (is (some #{op} (concat infix-operators prefix-operators)) op)
        (is (every? #(or (= "-" %) (shadowed %)) [l r]) (str op "(" l ", " r ")")))
      (doseq [{:keys [f]} aggs]
        (is (not (re-find (re-pattern (str "(?<![.\\w])" f "\\(")) (slurp (io/resource "pgmalli/ir.sql"))))
            (str f " is called qualified, pg_catalog." f ", as an aggregate of the read schema of its name would be called otherwise"))))))

(deftest ir-sql-resolves-to-no-operator-or-function-but-pg-catalogs-whatever-the-schema-defines
  (when (and *db* (not (System/getProperty "babashka.version")))
    (exec-sql! (shadowing-everything "shadow"))
    (let [outside (ir-view-deps "shadow" "SELECT CASE WHEN d.refclassid = 'pg_operator'::regclass::oid
                                   THEN (SELECT o.oprname || '(' || o.oprleft::regtype::text || ', ' || o.oprright::regtype::text || ')' FROM pg_operator o WHERE o.oid = d.refobjid)
                                   ELSE d.refobjid::regprocedure::text END AS resolved
                              FROM d
                              WHERE ((d.refclassid = 'pg_operator'::regclass::oid
                                      AND d.refobjid IN (SELECT o.oid FROM pg_operator o JOIN pg_namespace n ON n.oid = o.oprnamespace WHERE n.nspname <> 'pg_catalog'))
                                  OR (d.refclassid = 'pg_proc'::regclass::oid
                                      AND d.refobjid IN (SELECT p.oid FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname <> 'pg_catalog')))")]
      (is (= [] outside) "an operator or function of the read schema ir.sql's query would run"))))
