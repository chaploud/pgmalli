(ns pgmalli.impl.eval-test
  (:require [clojure.test :refer [deftest is testing]]
            [pgmalli.impl.eval :as ev]
            [pgmalli.impl.expr :as x]))

(defn- passes? [clause row] (ev/passes? (x/check-clause clause) row))

(deftest three-valued-logic
  (let [clause "CHECK (status = 'pending'::approval_status AND closed_at IS NULL OR status = 'approved'::approval_status AND closed_at IS NOT NULL)"]
    (is (passes? clause {:status "pending" :closed_at nil}))
    (is (not (passes? clause {:status "pending" :closed_at #inst "2026"})))
    (is (passes? clause {:status "approved" :closed_at #inst "2026"}))
    (is (passes? clause {:status nil :closed_at nil}) "NULL makes the CHECK pass, as in PostgreSQL"))
  (testing "IN with NULLs and mixed numeric types"
    (is (passes? "CHECK (n IN (1, 2))" {:n 1M}) "numeric columns arrive as BigDecimal")
    (is (passes? "CHECK (n IN (1, 2))" {:n 1.0}))
    (is (not (passes? "CHECK (n IN (1, 2))" {:n 3})))
    (is (passes? "CHECK (n IN (a, b))" {:n 3 :a 1 :b nil}) "no match with a NULL among the values is NULL")
    (is (not (passes? "CHECK (n NOT IN (1, 2))" {:n 2})))
    (is (passes? "CHECK (n NOT IN (a, b))" {:n 3 :a 1 :b nil}))))

(deftest cross-column-and-functions
  (is (passes? "CHECK (score >= 0 AND score <= total)" {:score 3 :total 5}))
  (is (not (passes? "CHECK (score >= 0 AND score <= total)" {:score 6 :total 5})))
  (is (passes? "CHECK (parent_id IS NULL OR parent_id <> id)" {:parent_id nil :id 1}))
  (is (not (passes? "CHECK (parent_id IS NULL OR parent_id <> id)" {:parent_id 1 :id 1})))
  (is (passes? "CHECK (expires_at > created_at)" {:expires_at #inst "2027" :created_at #inst "2026"}))
  (is (not (passes? "CHECK (\"line no\" > 0)" {"line no" 0})) "odd identifiers are string keys in rows")
  (is (passes? "CHECK (CASE kind WHEN 'a'::text THEN (params ->> 'id'::text) ~ '^[1-9][0-9]*$'::text ELSE false END)"
               {:kind "a" :params {"id" 12}}))
  (is (not (passes? "CHECK (CASE kind WHEN 'a'::text THEN (params ->> 'id'::text) ~ '^[1-9][0-9]*$'::text ELSE false END)"
                    {:kind "b" :params {}})))
  (testing "coalesce keeps false"
    (is (passes? "CHECK (coalesce(a, b) > 0)" {:a nil :b 1}))
    (is (not (passes? "CHECK (coalesce(flag, true))" {:flag false}))))
  (testing "strings"
    (is (passes? "CHECK (octet_length(digest) = 4)" {:digest (byte-array 4)}))
    (is (passes? "CHECK (octet_length(c) = 3)" {:c "あ"}) "octet_length counts UTF-8 bytes")
    (is (not (passes? "CHECK (octet_length(c) = 1)" {:c "あ"})))
    (is (passes? "CHECK (btrim(name, ' '::text) <> ''::text)" {:name "ok"}))
    (is (not (passes? "CHECK (btrim(name, 'x'::text) <> ''::text)" {:name "xxx"})))
    (is (not (passes? "CHECK (TRIM(BOTH 'x'::text FROM name) <> ''::text)" {:name "xxx"})))
    (is (passes? "CHECK (lower(code) = 'ab'::text)" {:code "AB"})))
  (testing "arithmetic as PostgreSQL does it"
    (is (passes? "CHECK ((a / b) = 2)" {:a 5 :b 2}) "integer division truncates")
    (is (passes? "CHECK ((a / b) = 2.5)" {:a 5M :b 2}))
    (is (not (passes? "CHECK ((a / b) = 2)" {:a 5 :b 0})) "division by zero fails the row, as the INSERT would")))

(deftest casts
  (is (passes? "CHECK (((m ->> 'a'::text))::integer > ((m ->> 'b'::text))::integer)" {:m {"a" "10" "b" "9"}}))
  (is (not (passes? "CHECK (((m ->> 'a'::text))::integer > 9)" {:m {"a" "x"}})) "a cast that does not parse fails the row")
  (is (passes? "CHECK (length((id)::text) = 2)" {:id 42}))
  (is (passes? "CHECK ((id)::text <> ''::text)" {:id 42}))
  (is (passes? "CHECK ((flag)::boolean)" {:flag "yes"}))
  (is (passes? "CHECK (mood = 'sad'::mood)" {:mood "sad"}) "an enum literal is its string")
  (testing "temporal, uuid, jsonb and array literals"
    (is (passes? "CHECK (born > '2026-01-01'::date)" {:born (java.time.LocalDate/parse "2026-02-01")}))
    (is (not (passes? "CHECK (born > '2026-01-01'::date)" {:born (java.time.LocalDate/parse "2025-02-01")})))
    (is (passes? "CHECK (ts >= '2026-01-01 09:00:00+09'::timestamp with time zone)" {:ts (java.time.Instant/parse "2026-01-01T00:00:00Z")}))
    (is (passes? "CHECK (expires_at > (created_at + '01:30:00'::interval))"
                 {:created_at (java.time.Instant/parse "2026-01-01T00:00:00Z") :expires_at (java.time.Instant/parse "2026-01-01T02:00:00Z")}))
    (is (not (passes? "CHECK (expires_at > (created_at + '1 day'::interval))"
                      {:created_at (java.time.Instant/parse "2026-01-01T00:00:00Z") :expires_at (java.time.Instant/parse "2026-01-01T02:00:00Z")})))
    (is (not (ev/supported? (x/check-clause "CHECK (ts > (now() - '1 mon'::interval))"))) "months have no fixed length")
    (is (passes? "CHECK ((born - '2026-01-01'::date) >= 0)" {:born (java.time.LocalDate/parse "2026-01-02")}))
    (is (passes? "CHECK (id <> '00000000-0000-0000-0000-000000000000'::uuid)" {:id (java.util.UUID/randomUUID)}))
    (is (passes? "CHECK (tags = '{a,b}'::text[])" {:tags ["a" "b"]}))
    (is (passes? "CHECK (params = '{\"a\": 1}'::jsonb)" {:params {"a" 1.0}}) "jsonb equality is numeric")
    (is (ev/supported? (x/check-clause "CHECK (created_at <= now())")) "now() is the validation time")))

(deftest cross-type-and-short-circuit
  (let [dt (java.time.LocalDateTime/parse "2026-01-01T10:00")]
    (is (passes? "CHECK (created_at <= now())" {:created_at dt}) "a timestamp against now() compares in the JVM's zone")
    (is (passes? "CHECK (created_at <= CURRENT_DATE)" {:created_at dt}))
    (is (passes? "CHECK (born <= now())" {:born (java.time.LocalDate/parse "2026-01-01")}))
    (is (not (passes? "CHECK (born > '2026-01-01 12:00:00'::timestamp without time zone)" {:born (java.time.LocalDate/parse "2026-01-01")}))
        "a date is its midnight"))
  (is (passes? "CHECK ((a)::numeric / b >= 0.5)" {:a 1 :b 2}) "a numeric cast of an integer divides as numeric")
  (is (passes? "CHECK ((a)::double precision / b >= 0.5)" {:a 1 :b 2}))
  (is (ev/supported? (x/check-clause "CHECK ((a)::numeric(10,2) > 0)")) "parameterized casts")
  (is (not (ev/supported? (x/check-clause "CHECK (n <@ '[1,10)'::int4range)"))) "unknown types are not values as they are")
  (is (ev/supported? (x/check-clause "CHECK (mood = 'sad'::mood)") #{"mood"}) "the schema's own types are")
  (is (not (ev/supported? (x/check-clause "CHECK (mood = 'sad'::mood)"))))
  (is (passes? "CHECK ((a - NULL::integer) IS NULL)" {:a 1}) "a literal NULL operand is not a unary minus")
  (is (passes? "CHECK (round(d) = 2)" {:d 2.5}) "double precision rounds half to even")
  (is (passes? "CHECK (round(d) = 3)" {:d 2.5M}) "numeric away from zero")
  (is (passes? "CHECK (d = 0 OR (t / d) > 1)" {:d 0 :t 5}) "OR stops at the first true operand")
  (is (passes? "CHECK (coalesce(t, t / d) > 1)" {:d 0 :t 5})))

(deftest strings-and-numbers
  (is (passes? "CHECK (code ~~ 'ab%'::text)" {:code "abc"}))
  (is (not (passes? "CHECK (code ~~ 'ab\\_'::text)" {:code "abc"})) "an escaped underscore is literal")
  (is (passes? "CHECK (code ~~* 'AB%'::text)" {:code "abc"}))
  (is (passes? "CHECK (code !~~ 'x%'::text)" {:code "abc"}))
  (is (passes? "CHECK (code !~ '^x'::text)" {:code "abc"}))
  (is (passes? "CHECK ((first_name || last_name) <> ''::text)" {:first_name "a" :last_name ""}))
  (is (passes? "CHECK ((n % 2) = 0)" {:n 4}))
  (is (passes? "CHECK ((n % 2) = -1)" {:n -3}) "the sign follows the dividend")
  (is (passes? "CHECK (nullif(n, 0) IS NULL)" {:n 0}))
  (is (passes? "CHECK (greatest(a, b) = 3)" {:a nil :b 3}) "greatest ignores NULLs")
  (is (passes? "CHECK (least(a, b) IS NULL)" {:a nil :b nil}))
  (is (passes? "CHECK (abs(n) < 5)" {:n -4}))
  (is (passes? "CHECK (round(d) = 3)" {:d 2.5M}) "half away from zero")
  (is (passes? "CHECK (substr(s, 2, 2) = 'bc'::text)" {:s "abcd"}))
  (is (passes? "CHECK (\"left\"(s, 1) = 'a'::text)" {:s "abcd"}))
  (is (passes? "CHECK (replace(s, 'b'::text, 'x'::text) = 'axcd'::text)" {:s "abcd"}))
  (is (passes? "CHECK (n BETWEEN SYMMETRIC 10 AND 1)" {:n 5}))
  (is (not (passes? "CHECK (n BETWEEN 10 AND 1)" {:n 5})))
  (is (passes? "CHECK (a IS DISTINCT FROM b)" {:a nil :b 1}))
  (is (not (passes? "CHECK (a IS DISTINCT FROM b)" {:a nil :b nil})))
  (is (passes? "CHECK ((d / 3) > 0.7)" {:d 2.25M}) "numeric division does not throw on non-terminating decimals"))

(deftest arrays-and-jsonb
  (is (passes? "CHECK (array_length(tags, 1) = 2)" {:tags ["a" "b"]}))
  (is (passes? "CHECK (array_length(tags, 1) IS NULL)" {:tags []}) "an empty array has no length")
  (is (passes? "CHECK (cardinality(tags) = 0)" {:tags []}))
  (is (passes? "CHECK (tags[1] = 'a'::text)" {:tags ["a"]}))
  (is (passes? "CHECK ('a'::text = ANY (tags))" {:tags ["b" "a"]}))
  (is (passes? "CHECK (tags && ARRAY['a'::text])" {:tags ["b" "a"]}))
  (is (passes? "CHECK (tags @> ARRAY['a'::text, 'a'::text])" {:tags ["a"]}) "array containment ignores duplicates")
  (is (passes? "CHECK (params @> '{\"a\": {\"b\": 1}}'::jsonb)" {:params {"a" {"b" 1 "c" 2}}}))
  (testing "jsonb read with keyword keys, as next.jdbc is often set up to"
    (is (passes? "CHECK ((params ->> 'manual-id'::text) ~ '^[1-9][0-9]*$'::text)" {:params {:manual-id 1423}}))
    (is (passes? "CHECK ((params - 'manual-id'::text) = '{}'::jsonb)" {:params {:manual-id 1423}}))
    (is (passes? "CHECK (params ? 'a'::text)" {:params {:a nil}}))
    (is (passes? "CHECK (params = '{\"a\": {\"b\": 1}}'::jsonb)" {:params {:a {:b 1}}})))
  (is (not (passes? "CHECK (params @> '{\"a\": [1]}'::jsonb)" {:params {"a" 2}})))
  (is (passes? "CHECK (params @> '[1]'::jsonb)" {:params [1 2]}))
  (is (passes? "CHECK (params ? 'a'::text)" {:params {"a" nil}}))
  (is (passes? "CHECK (params ?| ARRAY['x'::text, 'a'::text])" {:params {"a" 1}}))
  (is (not (passes? "CHECK (params ?& ARRAY['x'::text, 'a'::text])" {:params {"a" 1}})))
  (is (passes? "CHECK ((params #>> '{a,b}'::text[]) = '1'::text)" {:params {"a" {"b" 1}}}))
  (is (passes? "CHECK ((params ->> 'a'::text) = '{\"b\": [1, \"x\"]}'::text)" {:params {"a" {"b" [1 "x"]}}}) "containers print as PostgreSQL does")
  (is (passes? "CHECK ((params -> 0) = '1'::jsonb)" {:params [1 2]}))
  (is (passes? "CHECK ((params - 'a'::text) = '{}'::jsonb)" {:params {"a" 1}}))
  (is (passes? "CHECK (jsonb_array_length(params) = 2)" {:params [1 2]}))
  (is (not (passes? "CHECK (jsonb_array_length(params) = 2)" {:params {"a" 1}})) "not an array: the database errors, so the row fails"))

(deftest regexes
  (is (passes? "CHECK (code ~ '^[[:digit:]]+$'::text)" {:code "12"}))
  (is (not (passes? "CHECK (code ~ '^[[:digit:]]+$'::text)" {:code "d"})) "POSIX classes are translated")
  (is (not (passes? "CHECK (code ~ '^[[:alnum:]]+$'::text)" {:code ":::"})))
  (is (passes? "CHECK (code ~* '^ab$'::text)" {:code "AB"}))
  (is (= "^[\\p{Alpha}]+$" (ev/java-regex "^[[:alpha:]]+$")))
  (is (nil? (ev/java-regex "\\mword\\M")) "PostgreSQL-only escapes are not translated")
  (is (not (ev/supported? (x/check-clause "CHECK (code ~ '\\mword\\M'::text)")))))

(deftest vocabulary
  (is (ev/supported? (x/parse "a <> ALL (ARRAY['x'::text]) AND b IN (1, 2)")))
  (is (not (ev/supported? (x/parse "st_area(geom) > 0")))))
