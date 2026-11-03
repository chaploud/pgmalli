(ns pgmalli.impl.portable-test
  "The same schemas in other shapes: portable-data and as-read, on the checked-in
   test/resources/pgmalli/sample.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            malli.experimental.time
            [pgmalli.core :as pgmalli]
            [pgmalli.impl.portable :as portable]
            [pgmalli.impl.shape :as shape]
            [pgmalli.sample :refer [opts registry user]]))

(defn- portable [registry name] (portable/portable-data registry (shape/schema-of registry name)))

(deftest other-shapes-of-the-same-schema
  (testing "portable: what malli's default registry reads"
    (let [p (portable registry :pg.sample/users)
          opts {:registry (merge (m/default-schemas) (malli.experimental.time/schemas))}]
      (is (= [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} "happy" "sad"] (get-in p [1 7 1])) "the enum is inlined")
      (is (= [:int {:pg/type "integer" :min -2147483648 :max 2147483647}] (get-in p [1 4 1])) "integers carry their range")
      (is (= :multi (first (nth p 2))) "the branching CHECK stays")
      (is (= 3 (count p)) "the :pg/check constraints are left out")
      (is (m/validate p (assoc user :group_name "a") opts))
      (is (not (m/validate p (assoc user :group_id 2147483648) opts)))
      (is (= 'bytes? (get-in (portable (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:d [:pg/bytes {:min 32 :max 32 :pg/type "bytea"}]]]}}) :pg.public/t) [2 1])))
      (is (not-any? #(and (map? %) (or (:gen/min %) (:gen/max %))) (tree-seq coll? seq p)) "no generation hints")))
  (testing "as-read: the map as next.jdbc builds it"
    (let [r (pgmalli/as-read registry :pg.sample/users)]
      (is (= [:group_name [:maybe [:string {:pg/type "text"}]]] (nth r 5)))
      (is (= [:updated_at [:maybe [:time/instant {:pg/type "timestamp"}]]] (last r)) "timestamps as Instants")
      (is (= [:born [:maybe [:time/local-date {:pg/type "date"}]]] (nth r 2)) "dates stay java.sql.Date under read-as-instant")
      (is (m/validate r {:id 1 :group_id 1 :mood "sad" :seq 1 :score 1 :total 2 :born nil :closed_at nil :group_name nil :nick nil :referrer_id nil :updated_at nil :nick_upper nil} opts)))
    (is (some #{[:nick_upper [:maybe [:string {:pg/generated [:upper [:cast :nick :text]] :pg/type "text"}]]]} (pgmalli/as-read registry :pg.sample/users {})))
    (is (= :line_no (first (nth (pgmalli/as-read (pgmalli/registry {:registry {:pg.public/order_items [:map {:pg/table "public.order_items"} [:line_no [:int {:pg/type "integer"}]]]}}) :pg.public/order_items {}) 2)))
        "_ read as -"))
  (testing "portable converts what it inlines"
    (let [reg (pgmalli/registry {:registry {:pg.public/code [:and :string [:pg/check-value {:pg/constraint "c"} [:<> :VALUE ""]]]
                                            :pg.public/t [:map {:pg/table "public.t"} [:c [:ref {:pg/type "code"} :pg.public/code]]]}})]
      (is (= [:map {:pg/table "public.t"} [:c [:string {:pg/type "code"}]]] (portable reg :pg.public/t))))))

(deftest unknown-names-are-an-error
  (doseq [[what f] {:portable #(portable registry :pg.sample/nope)
                    :as-read #(pgmalli/as-read registry :pg.sample/nope)}]
    (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no schema named" (f)) (str what))]
      (is (= :pgmalli.error/unknown-schema (:pgmalli/error (ex-data e))) (str what))
      (is (= :pg.sample/nope (:given (ex-data e))) (str what))
      (is (some #{:pg.sample/users} (:known (ex-data e))) (str what)))))

(deftest a-misspelt-name-suggests-the-one-meant
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did you mean: :pg.sample.users/insert" (pgmalli/as-read registry :pg.sample/users_insert)))]
    (is (= [:pg.sample.users/insert] (:did-you-mean (ex-data e))))))

(deftest as-read-defaults-its-options
  (is (= (pgmalli/as-read registry :pg.sample/users {}) (pgmalli/as-read registry :pg.sample/users))))

(deftest as-read-takes-row-schemas
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no table's or view's row schema; as-read takes one such as \[:pg.sample/" (pgmalli/as-read registry :pg.sample/mood))))

(deftest as-read-given-an-insert-schema-names-the-row-schema
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"what is sent to sample.users; as-read takes its row schema :pg.sample/users"
                                (pgmalli/as-read registry :pg.sample.users/insert)))]
    (is (= [:pg.sample/users] (:did-you-mean (ex-data e))))))

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
