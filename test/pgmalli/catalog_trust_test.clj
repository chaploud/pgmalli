(ns pgmalli.catalog-trust-test
  "Reading a schema whose owner is not trusted: functions and operators it defines that
   ir.sql could resolve to instead of pg_catalog's must not run."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli.impl.ir :as ir]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

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
      (is (= #{"t" "p"} (set (keys (:tables ir)))))
      (is (= #{"mood" "word"} (set (keys (:types ir))))))
    (when-not (System/getProperty "babashka.version")
      (let [{:keys [host port db user password]} *db*
            ir (ir/from-db {:jdbc-url (str "jdbc:postgresql://" host ":" port "/" db) :user user :password password :schema "evil"})]
        (is (= #{"t" "p"} (set (keys (:tables ir)))))))))

(deftest a-schema-name-written-into-the-jdbc-query-stays-one-identifier
  (when (and *db* (not (System/getProperty "babashka.version")))
    (let [{:keys [host port db user password]} *db*
          schema ":'schema'\"; CREATE TABLE public.injected (); COMMIT; --"]
      (is (= :pgmalli.error/invalid-config
             (:pgmalli/error (ex-data (is (thrown? clojure.lang.ExceptionInfo
                                                   (ir/from-db {:jdbc-url (str "jdbc:postgresql://" host ":" port "/" db) :user user :password password :schema schema})))))))
      (is (not (contains? (:tables (ir/from-db (assoc *db* :schema "public"))) "injected"))))))
