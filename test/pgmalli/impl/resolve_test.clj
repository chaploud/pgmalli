(ns pgmalli.impl.resolve-test
  (:require [clojure.test :refer [deftest is]]
            [pgmalli.impl.resolve :as r]))

(def ^:private own
  {"app" {:functions {"lower" [{:args ["text"] :result "integer"}]
                      "round" [{:args ["integer"] :result "text"}]
                      "upper" [{:args ["pair"] :result "integer"}]
                      "fresh" [{:args [] :result "bigint" :set true}]
                      "regexp_replace" [{:args ["citext" "citext" "text"] :result "text"}]}
          :operators {"=" [{:args ["text" "text"] :result "integer"}]}}})

(defn- in [m] #(get-in m [%1 %2 %3]))

(defn- call [kind n ins path] (:result (r/call kind n ins (r/overloads kind n (in own) path))))

(deftest a-schemas-overload-of-pg-catalogs-argument-types-gives-way-to-it
  (is (= "text" (call :functions "lower" ["text"] ["pg_catalog" "app"])))
  (is (= "boolean" (call :operators "=" ["text" "text"] ["pg_catalog" "app"])))
  (is (= "integer" (call :functions "lower" ["text"] ["app"])) "qualified with its schema, it is the one called"))

(deftest a-schemas-overload-of-other-argument-types-competes-with-pg-catalogs
  (is (= "double precision" (call :functions "round" ["integer"] ["pg_catalog"])))
  (is (= "text" (call :functions "round" ["integer"] ["pg_catalog" "app"])) "an exact match")
  (is (= #{"integer"} (r/argument-types [:free] 0 (r/overloads :functions "round" (in own) ["app"])))))

(deftest an-overload-of-a-type-the-catalog-lacks-leaves-the-call-untyped-unless-it-matches-exactly
  (is (= "text" (call :functions "upper" ["text"] ["pg_catalog" "app"])) "pg_catalog's upper(text) is an exact match")
  (is (nil? (call :functions "upper" ["character varying"] ["pg_catalog" "app"]))
      "a cast pgmalli does not read may take a varchar to pair")
  (is (nil? (r/argument-types [:free] 0 (r/overloads :functions "upper" (in own) ["pg_catalog" "app"])))))

(deftest a-schemas-functions-tell-a-set-and-an-aggregate
  (is (r/set-returning? (r/overloads :functions "fresh" (in own) ["pg_catalog" "app"])))
  (is (not (r/set-returning? (r/overloads :functions "round" (in own) ["pg_catalog" "app"]))))
  (is (r/set-returning? (r/overloads :functions "fresh" (in own) ["pg_catalog" "other"])) "a function of none is taken to return a set")
  (is (not (r/aggregate? (r/overloads :functions "max" (in {"app" {:functions {"max" [{:args ["integer" "integer"] :result "integer"}]}}}) ["pg_catalog" "app"])))))

(deftest a-parameter-may-go-as-each-type-a-candidate-takes-there
  (is (= #{"integer" "bigint" "numeric"} (r/argument-types ["integer" :free] 1 (r/overloads :functions "generate_series"))))
  (is (= #{"timestamp with time zone" "timestamp without time zone" "time with time zone"}
         (r/argument-types ["text" :free] 1 (r/overloads :functions "timezone")))))

(deftest an-unknown-argument-beside-known-ones-of-one-type-is-taken-as-of-that-type
  ;; as PostgreSQL types '12:00'::time + '01:00', '10.0.0.0/8'::cidr - '10.0.0.1' and
  ;; percentile_cont('0.5') WITHIN GROUP (ORDER BY x::float8)
  (is (= "time without time zone" (call :operators "+" ["time without time zone" :unknown] ["pg_catalog"])))
  (is (= "time without time zone" (call :operators "+" [:unknown "time without time zone"] ["pg_catalog"])))
  (is (= "bigint" (call :operators "-" ["cidr" :unknown] ["pg_catalog"])))
  (is (= "double precision" (call :functions "percentile_cont" [:unknown "double precision"] ["pg_catalog"]))))

(deftest a-function-of-one-unknown-argument-finds-no-exact-match-as-an-operator-does
  ;; PostgreSQL: function pg_catalog.bit(unknown, integer) is not unique
  (is (nil? (call :functions "bit" [:unknown "integer"] ["pg_catalog"]))))

(deftest an-overload-of-a-type-the-catalog-lacks-that-matches-fewer-known-arguments-exactly-loses-at-step-4c
  (let [os (r/overloads :functions "regexp_replace" (in own) ["pg_catalog" "app"])]
    (is (= #{"text"} (r/argument-types [:free "text" "text"] 0 os)) "citext's matches the last argument alone, text's both")
    (is (= "text" (call :functions "regexp_replace" [:unknown "text" "text"] ["pg_catalog" "app"])))
    (is (not (r/untyped-refused? :functions [:free "text" "text"] 0 os)))
    (is (nil? (r/argument-types [:free "character varying" "text"] 0 os)) "matching as many (the last alone), it may be the one")))

(deftest an-untyped-value-is-refused-where-the-call-leaves-its-type-open-or-finds-no-one-candidate
  (let [os #(r/overloads :functions % (in own) ["pg_catalog"])]
    (is (r/untyped-refused? :functions [:free] 0 (os "cardinality")) "anyarray, nothing else binding it")
    (is (r/untyped-refused? :functions [:free] 0 (os "unnest")) "anyarray, anymultirange and tsvector: not unique")
    (is (r/untyped-refused? :functions [:free] 0 (os "num_nulls")) "\"any\"")
    (is (not (r/untyped-refused? :functions [:free] 0 (os "lower"))) "text's string category takes it")
    (is (not (r/untyped-refused? :functions [:free :free] 0 (os "lower"))) "no candidate of two arguments: not told")))

(deftest a-call-resolves-as-of-the-versions-the-overloads-are-narrowed-to
  (let [os [{:args ["integer"] :result "text" :in ["16"]} {:args ["integer"] :result "text" :in ["17"]}]]
    (is (nil? (:result (r/call :functions "f" ["integer"] os))) "version 18 has neither")
    (is (= "text" (:result (r/call :functions "f" ["integer"] (with-meta os {:pgmalli/versions ["16" "17"]}))))
        "a registry of 16 and 17 files")))

(deftest the-polymorphic-types-are-the-catalogs
  (let [catalog @@(requiring-resolve 'pgmalli.impl.types/catalog)
        used (set (for [kind [:operators :functions] [_ os] (kind catalog) {as :args :keys [result]} os
                        t (cons result as) :when (and (string? t) (re-matches #"any[a-z]+" t))]
                    t))]
    (is (= used @#'r/polymorphic) "each pseudo-type any... a function or operator of pg_catalog takes or returns")))

(deftest the-grammars-calls-are-no-function-of-the-catalog
  (let [catalog @@(requiring-resolve 'pgmalli.impl.types/catalog)]
    (is (= #{} (set (filter (set (keys (:functions catalog))) @(requiring-resolve 'pgmalli.impl.query/grammar-calls))))
        "pg_proc has none of them: a name of one is the grammar's")))
