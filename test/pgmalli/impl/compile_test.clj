(ns pgmalli.impl.compile-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [pgmalli.impl.compile :as c]
            [pgmalli.impl.expr :as x]))

(defn- passes? [clause row]
  (m/validate [:fn (c/check-fn (x/check-clause clause))] row))

(deftest three-valued-logic
  (let [clause "CHECK (status = 'pending'::approval_status AND closed_at IS NULL OR status = 'approved'::approval_status AND closed_at IS NOT NULL)"]
    (is (passes? clause {:status "pending" :closed_at nil}))
    (is (not (passes? clause {:status "pending" :closed_at #inst "2026"})))
    (is (passes? clause {:status "approved" :closed_at #inst "2026"}))
    (is (passes? clause {:status nil :closed_at nil}) "NULL makes the CHECK pass, as in PostgreSQL")))

(deftest cross-column-and-functions
  (is (passes? "CHECK (score >= 0 AND score <= total)" {:score 3 :total 5}))
  (is (not (passes? "CHECK (score >= 0 AND score <= total)" {:score 6 :total 5})))
  (is (passes? "CHECK (parent_id IS NULL OR parent_id <> id)" {:parent_id nil :id 1}))
  (is (not (passes? "CHECK (parent_id IS NULL OR parent_id <> id)" {:parent_id 1 :id 1})))
  (is (passes? "CHECK (expires_at > created_at)" {:expires_at #inst "2027" :created_at #inst "2026"}))
  (is (passes? "CHECK (octet_length(digest) = 4)" {:digest (byte-array 4)}))
  (is (passes? "CHECK (CASE kind WHEN 'a'::text THEN (params ->> 'id'::text) ~ '^[1-9][0-9]*$'::text ELSE false END)"
               {:kind "a" :params {"id" 12}}))
  (is (not (passes? "CHECK (CASE kind WHEN 'a'::text THEN (params ->> 'id'::text) ~ '^[1-9][0-9]*$'::text ELSE false END)"
                    {:kind "b" :params {}}))))

(deftest forms-are-edn
  (let [f (c/check-fn (x/check-clause "CHECK (a <> ''::text AND b ~* 'x')"))]
    (is (= f (edn/read-string (pr-str f))))))

(deftest unsupported-is-an-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unsupported" (c/check-fn (x/parse "st_area(geom) > 0")))))
