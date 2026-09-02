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
  (is (not (ev/supported? (x/check-clause "CHECK (created_at > '2026-01-01'::date)"))) "no conversion for temporal literals")
  (is (not (ev/supported? (x/check-clause "CHECK (tags = '{a}'::text[])")))))

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
