(ns pgmalli.impl.resolve-test
  (:require [clojure.test :refer [deftest is]]
            [pgmalli.impl.resolve :as r]))

(def ^:private own
  {"app" {:functions {"lower" [{:args ["text"] :result "integer"}]
                      "round" [{:args ["integer"] :result "text"}]
                      "upper" [{:args ["pair"] :result "integer"}]
                      "fresh" [{:args [] :result "bigint" :set true}]}
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
