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
  (is (passes? "CHECK (ts = '2026-01-01'::date)" {:ts (java.time.LocalDateTime/parse "2026-01-01T00:00")}) "equality converts across types too")
  (is (passes? "CHECK (greatest(ts, '2026-01-01'::date) = ts)" {:ts (java.time.LocalDateTime/parse "2026-01-02T00:00")}))
  (is (not (passes? "CHECK ((a + (1 / 0)) IS NULL)" {:a nil})) "every argument of a strict function is evaluated, so the error counts")
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

(deftest a-decimal-literal-is-a-numeric-whose-ties-go-away-from-zero
  (let [check "CHECK (b = ((a)::numeric * 0.5)::integer)"]
    (is (passes? check {:a 5 :b 3}))
    (is (not (passes? check {:a 5 :b 2}))))
  (is (passes? "CHECK (n = round(2.5))" {:n 3M}))
  (is (not (passes? "CHECK (n = round(2.5))" {:n 2M})))
  (is (passes? "CHECK (n = (2.5)::integer)" {:n 3})))

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
  (is (passes? "CHECK (code ~ '^[[:xdigit:]]+$'::text)" {:code "0aF"}))
  (is (nil? (ev/java-regex "\\mword\\M")) "PostgreSQL-only escapes are not translated")
  (is (not (ev/supported? (x/check-clause "CHECK (code ~ '\\mword\\M'::text)")))))

(deftest classes-and-case-take-every-letter-as-a-utf8-database-does
  (doseq [[check s] [["CHECK (c ~ '^[[:alpha:]]+$'::text)" "é"]
                     ["CHECK (c ~ '^\\w+$'::text)" "é"]
                     ["CHECK (c ~* '^é$'::text)" "É"]
                     ["CHECK (c ~~* 'é%'::text)" "Éa"]]]
    (is (passes? check {:c s}) check))
  (is (re-find (re-pattern (ev/java-regex "^[[:alpha:]]+$")) "é") "the rendered regex too")
  (is (re-find (re-pattern (ev/case-insensitive (ev/like-regex "é%"))) "Éa"))
  (is (not (passes? "CHECK (c ~ '^[[:alpha:]]+$'::text)" {:c "1"}))))

(deftest a-class-outside-ascii-is-left-to-the-server-and-never-rejects-what-it-may-take
  (doseq [[check s] [["CHECK (s ~ '^[[:punct:]]+$'::text)" "$<=>€×"]
                     ["CHECK (s !~ '\\s'::text)" "a\u00A0b"]
                     ["CHECK (s !~ '[[:blank:]]'::text)" "a\u2003b"]
                     ["CHECK (s ~ '^\\d+$'::text)" "١٢"]
                     ["CHECK ((s ~ '^\\w+$') OR n > 0)" "é"]
                     ["CHECK (NOT (s ~ '^\\w+$') OR n > 0)" "é"]]]
    (is (passes? check {:s s :n 0}) check))
  (is (not (passes? "CHECK (s ~ '^[[:alpha:]]+$'::text)" {:s "café!"})) "an ASCII character outside the class still fails")
  (is (not (passes? "CHECK (NOT (s ~ '^\\w+$') OR n > 0)" {:s "ab" :n 0})))
  (is (re-find (re-pattern (ev/java-regex "^[[:punct:]]+$")) "$€"))
  (is (not (re-find (re-pattern (ev/strict-regex (ev/java-regex "^[[:punct:]]+$"))) "€")) "generation keeps to ASCII members"))

(deftest backslash-b-is-a-backspace-and-backslash-capital-b-a-backslash
  (is (passes? "CHECK (s ~ 'a\\bb'::text)" {:s "a\bb"}))
  (is (not (passes? "CHECK (s ~ 'a\\bb'::text)" {:s "ab"})))
  (is (passes? "CHECK (s ~ '^a\\Bb$'::text)" {:s "a\\b"})))

(deftest dollar-matches-only-at-the-end-and-dot-matches-a-newline
  (is (not (passes? "CHECK (s ~ '^abc$'::text)" {:s "abc\n"})))
  (is (passes? "CHECK (s ~ '^a[$]c$'::text)" {:s "a$c"}) "a $ in brackets is the character")
  (is (not (passes? "CHECK (s ~~ 'a%'::text)" {:s "b\na"})))
  (is (not (passes? "CHECK (s ~~ 'a'::text)" {:s "a\n"})))
  (is (re-find (re-pattern (ev/java-regex "^a.b$")) "a\nb") "the rendered regex too")
  (is (not (re-find (re-pattern (ev/java-regex "^abc$")) "abc\n")))
  (is (re-find (re-pattern (ev/like-regex "a%")) "a\nb"))
  (is (not (re-find (re-pattern (ev/like-regex "a")) "a\n"))))

(deftest lengths-and-positions-count-characters
  (is (passes? "CHECK (length(s) = 2)" {:s "😀😀"}))
  (is (passes? "CHECK (substr(s, 2, 1) = 'b'::text)" {:s "😀b"}))
  (is (passes? "CHECK (\"left\"(s, 1) = '😀'::text AND \"right\"(s, 1) = 'b'::text)" {:s "😀b"}))
  (is (passes? "CHECK (strpos(s, 'b'::text) = 2)" {:s "😀b"})))

(deftest trim-removes-spaces-only
  (is (passes? "CHECK (length(TRIM(BOTH FROM s)) > 0)" {:s "\t"}))
  (is (passes? "CHECK (btrim(s) = s)" {:s "\na\n"}))
  (is (passes? "CHECK (ltrim(s) = s AND rtrim(s) = s)" {:s "\ta\t"})))

(deftest boolean-input-takes-unique-prefixes
  (doseq [w ["tr" "TRU" " ye " "on" "1"]] (is (passes? "CHECK ((s)::boolean)" {:s w}) w))
  (doseq [w ["of" "fA" "n" "0"]] (is (not (passes? "CHECK ((s)::boolean)" {:s w})) w))
  (doseq [w ["o" "01" "" "tru e"]] (is (not (passes? "CHECK ((s)::boolean IS NOT NULL)" {:s w})) (str w " is no boolean"))))

(deftest casts-to-integer-round-as-postgresql-does
  (is (passes? "CHECK (((d)::double precision)::integer = 2)" {:d 2.5M}) "float ties to even")
  (is (passes? "CHECK (((d)::double precision)::integer = 4)" {:d 3.5M}))
  (is (passes? "CHECK ((d)::integer = 3)" {:d 2.5M}) "numeric ties away from zero")
  (is (passes? "CHECK ((d)::integer = '-3'::integer)" {:d -2.5M}))
  (is (not (passes? "CHECK ((d)::integer IS NOT NULL)" {:d 3000000000M})) "integer out of range"))

(deftest integer-arithmetic-overflows-at-its-types-range
  (let [e (x/check-clause "CHECK ((n + m) > 0)")]
    (is (not (ev/passes? e {:n 2147483647 :m 1} {:n :integer :m :integer})) "integer + integer")
    (is (ev/passes? e {:n 2147483647 :m 1} {:n :integer :m :bigint}) "integer + bigint is bigint")
    (is (ev/passes? e {:n 2147483647 :m 1}) "operand types unknown: no range"))
  (is (not (ev/passes? (x/check-clause "CHECK ((n * 100000) > 0)") {:n 100000} {:n :integer})))
  (is (ev/passes? (x/check-clause "CHECK ((n + 1) > 0)") {:n 32767} {:n :smallint}) "smallint + integer literal is integer")
  (is (not (ev/passes? (x/check-clause "CHECK ((n + m) > 0)") {:n 32767 :m 1} {:n :smallint :m :smallint})))
  (is (ev/passes? (x/check-clause "CHECK ((- n) < 0)") {:n 5}) "unary minus"))

(deftest char-n-ignores-trailing-spaces-except-where-padded
  (let [types {:c [:character 4]}
        ok? (fn [clause v] (ev/passes? (x/check-clause clause) {:c v} types))]
    (doseq [v ["ab" "ab  "]]
      (is (ok? "CHECK (c = 'ab'::bpchar)" v))
      (is (ok? "CHECK (c = 'ab  '::bpchar)" v))
      (is (ok? "CHECK (length(c) = 2)" v))
      (is (ok? "CHECK (((c)::text || 'x'::text) = 'abx'::text)" v))
      (is (ok? "CHECK (octet_length(c) = 4)" v) "octet_length reads it padded")
      (is (ok? "CHECK (c ~~ 'ab  '::text)" v) "LIKE reads it padded")
      (is (not (ok? "CHECK (c ~ 'b$'::text)" v)) "a regex reads it padded"))))

(deftest date-plus-interval-is-a-timestamp
  (is (not (passes? "CHECK ((d + '01:30:00'::interval) <= '2020-06-15'::date)" {:d (java.time.LocalDate/parse "2020-06-15")}))))

(deftest vocabulary
  (is (ev/supported? (x/parse "a <> ALL (ARRAY['x'::text]) AND b IN (1, 2)")))
  (is (not (ev/supported? (x/parse "st_area(geom) > 0")))))

(deftest every-supported-op-is-evaluated
  ;; supported-ops and ev's case are two lists of the same vocabulary. An op that reaches only
  ;; the table throws "unsupported in CHECK", which checker catches as a failing row: a row the
  ;; database accepts would be dropped. Placeholder arguments may fail any other way.
  (let [ev @#'ev/ev
        evaluated? (fn [op] (nil? (:unsupported (ex-data (try (ev [op 1 2 3] {:a 1}) nil (catch Exception e e))))))]
    (doseq [op (remove (set (keys @#'ev/strict)) @#'ev/supported-ops)]
      (is (evaluated? op) (str op " is in supported-ops but ev does not evaluate it")))
    (is (not (evaluated? :no-such-op)) "an op in neither is unsupported, as the check needs")))

(deftest a-json-null-is-not-sql-null
  (is (not (passes? "CHECK (jsonb_typeof(j -> 'a') = 'string')" {:j {"a" nil}})) "jsonb_typeof is 'null'")
  (is (passes? "CHECK (jsonb_typeof(j -> 'b') = 'string')" {:j {"a" nil}}) "a missing key is SQL NULL")
  (is (passes? "CHECK ((j ->> 'a') IS NULL)" {:j {"a" nil}}))
  (is (passes? "CHECK ((j -> 'a') = 'null'::jsonb)" {:j {"a" nil}})))
