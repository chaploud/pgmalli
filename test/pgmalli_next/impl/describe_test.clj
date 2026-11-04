(ns pgmalli-next.impl.describe-test
  "pgmalli-next.core/describe: a schema read back as PostgreSQL types."
  (:require [clojure.test :refer [deftest is]]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.sample :refer [registry]]))

(deftest describe-reads-as-postgresql-types
  (is (= {:id "bigint" :nick "varchar(40)?"} (select-keys (pgmalli/describe registry :pg.sample/users) [:id :nick])))
  (is (= "mood: happy | sad" (:mood (pgmalli/describe registry :pg.sample/users))) "an enum reference with its labels")
  (is (= {:args ["bigint" "integer[] (array, sequential)"] :returns [{:id "bigint"}]}
         (pgmalli/describe [:=> [:cat [:int {:pg/type "bigint"}] [:or [:pg/array [:int {:pg/type "integer"}]] [:sequential [:int {:pg/type "integer"}]]]]
                            [:sequential [:map [:id [:int {:pg/type "bigint"}]]]]])))
  (is (= [:id :nick] (take 2 (keys (pgmalli/describe [:map [:id [:int {:pg/type "bigint"}]] [:nick {:optional true} [:maybe [:string {:pg/type "text"}]]]])))))
  (is (= ["any" "int" "string?"] (map #(pgmalli/describe registry %) [:any [:int {:min 0}] [:maybe :string]])) "malli's own types by their names")
  (is (= ["numeric" "numeric"] [(pgmalli/describe [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]) (pgmalli/describe 'decimal?)]))
  (is (= "numeric(14,2)?" (pgmalli/describe [:maybe [:and {:pg/type "numeric"} 'decimal? [:pg/numeric {:precision 14 :scale 2}]]])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did you mean: :pg.sample/users" (pgmalli/describe registry :pg.sample/usres)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"through a registry" (pgmalli/describe :pg.sample/users)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":pg.sample/mood is read through a registry" (pgmalli/describe (pgmalli/as-read registry :pg.sample/users))))
  (is (= ["text[]" "posint[]"] (map #(pgmalli/describe %) [[:vector {:pg/type "text[]"} :string] [:vector {:pg/type "posint[]"} :int]]))
      "an array by its PostgreSQL type")
  (is (= {:k [:pg.x/t]} (pgmalli/describe (merge registry {:pg.x/t [:map [:k [:vector [:ref :pg.x/t]]]]}) :pg.x/t))
      "a recursive schema names itself where it recurs")
  (is (= "text? (optional)" (:nick (pgmalli/describe [:map [:nick {:optional true} [:maybe [:string {:pg/type "text"}]]]])))))

(deftest describe-of-a-type-nobody-knows-is-an-error
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"describe: :foo is no type malli or the registry knows" (pgmalli/describe :foo)))]
    (is (= :pgmalli.error/unknown-schema (:pgmalli/error (ex-data e)))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is no type malli or the registry knows" (pgmalli/describe registry [:map [:id :pg.sample/nope]]))
      "a reference inside a schema too"))
