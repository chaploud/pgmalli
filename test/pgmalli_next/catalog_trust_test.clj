(ns pgmalli-next.catalog-trust-test
  "Reading a schema whose owner is not trusted: functions and operators it defines that
   ir.sql could resolve to instead of pg_catalog's must not run."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli-next.impl.ir :as ir]
            [pgmalli-next.test-db :refer [*db* datasource exec-sql! jdbcUrl with-postgres]]))

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
    (exec-sql! "CREATE ROLE scs_off LOGIN PASSWORD 'pw'; ALTER ROLE scs_off SET standard_conforming_strings = off;")
    (let [schema "x\\') SELECT 1; CREATE TABLE public.injected_scs (); COMMIT; --"]
      (is (thrown? clojure.lang.ExceptionInfo (ir/from-db {:jdbcUrl (jdbcUrl) :user "scs_off" :password "pw" :schema schema})))
      (is (not (contains? (:tables (ir/from-db (assoc *db* :schema "public"))) "injected_scs"))))))

(def ^:private shadow-types
  "The types ir.sql's operands are of, and arrays of some: an operator of the read schema on any
   pair of them, or an aggregate on any one, is what an exact match would resolve to."
  ["oid" "regtype" "regclass" "regproc" "int2" "int4" "int8" "text" "name" "\"char\"" "bool" "json" "jsonb"
   "numeric" "varchar" "bpchar" "oid[]" "int2[]" "text[]" "name[]" "regtype[]" "\"char\"[]" "int2vector"
   "oidvector" "pg_node_tree"])

(defn- shadowing-everything
  "SQL defining in schema s, for every pair of shadow-types, every infix operator ir.sql may
   write, a prefix operator of each type, and min, max, count, bool_or and bool_and of each."
  [s]
  (let [id #(-> % (str/replace "\"" "") (str/replace "[]" "_a"))
        fs (for [l shadow-types r shadow-types]
             [(str "CREATE FUNCTION " s ".f_" (id l) "__" (id r) "(" l ", " r ") RETURNS boolean LANGUAGE sql AS 'SELECT true'")
              (for [op ["=" "<>" "<" ">" "<=" ">=" "~" "~~" "!~" "~*" "&&" "@>" "<@" "||" "->>" "->" "&" "-" "+" "|" "*" "/" "%"]]
                (str "CREATE OPERATOR " s "." op " (LEFTARG = " l ", RIGHTARG = " r ", FUNCTION = " s ".f_" (id l) "__" (id r) ")"))])
        ps (for [t shadow-types]
             [(str "CREATE FUNCTION " s ".p_" (id t) "(" t ") RETURNS boolean LANGUAGE sql AS 'SELECT true'")
              (for [op ["-" "~" "+"]] (str "CREATE OPERATOR " s "." op " (RIGHTARG = " t ", FUNCTION = " s ".p_" (id t) ")"))
              (str "CREATE FUNCTION " s ".s_" (id t) "(" t ", " t ") RETURNS " t " LANGUAGE sql AS 'SELECT $2'")
              (for [agg ["min" "max" "count" "bool_or" "bool_and"]]
                (str "CREATE AGGREGATE " s "." agg "(" t ") (SFUNC = " s ".s_" (id t) ", STYPE = " t ")"))])]
    (str/join ";\n" (flatten (concat [(str "CREATE SCHEMA " s)] fs ps)))))

(deftest ir-sql-resolves-to-no-operator-or-function-but-pg-catalogs-whatever-the-schema-defines
  ;; ir.sql's query as a view: what its operators and functions resolved to, every one, is in pg_depend
  (when (and *db* (not (System/getProperty "babashka.version")))
    (exec-sql! (shadowing-everything "shadow"))
    (let [ir-sql (slurp (io/resource "pgmalli-next/ir.sql"))
          [_ query] (re-find #"(?s)SET search_path TO [^;]*;\s*(.*);\s*$" ir-sql)
          query (-> query (str/replace ":'schema'" "'shadow'") (str/replace ":\"schema\"" "\"shadow\""))
          jdbc (requiring-resolve 'next.jdbc/execute!)
          outside (with-open [c ((requiring-resolve (quote next.jdbc/get-connection)) (datasource))]
                    (jdbc c ["SET search_path TO pg_catalog, shadow, pg_temp"])
                    (jdbc c [(str "CREATE TEMP VIEW irv AS " query)])
                    (jdbc c ["SET search_path TO pg_catalog"])
                    (jdbc c ["SELECT CASE WHEN d.refclassid = 'pg_operator'::regclass::oid
                                   THEN (SELECT o.oprname || '(' || o.oprleft::regtype::text || ', ' || o.oprright::regtype::text || ')' FROM pg_operator o WHERE o.oid = d.refobjid)
                                   ELSE d.refobjid::regprocedure::text END AS resolved
                              FROM pg_depend d
                              WHERE d.classid = 'pg_rewrite'::regclass::oid
                                AND d.objid = (SELECT r.oid FROM pg_rewrite r WHERE r.ev_class = 'irv'::regclass::oid)
                                AND ((d.refclassid = 'pg_operator'::regclass::oid
                                      AND d.refobjid IN (SELECT o.oid FROM pg_operator o JOIN pg_namespace n ON n.oid = o.oprnamespace WHERE n.nspname <> 'pg_catalog'))
                                  OR (d.refclassid = 'pg_proc'::regclass::oid
                                      AND d.refobjid IN (SELECT p.oid FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname <> 'pg_catalog')))"]))]
      (is (some? query) "ir.sql is SET search_path, then one query")
      (is (= [] outside) "an operator or function of the read schema ir.sql's query would run"))))
