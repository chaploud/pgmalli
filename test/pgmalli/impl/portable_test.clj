(ns pgmalli.impl.portable-test
  "The same schemas in other shapes: portable, as-read and one column, on the checked-in
   test/resources/pgmalli/sample.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            malli.experimental.time
            [pgmalli.core :as pgmalli]
            [pgmalli.sample :refer [opts registry user]]))

(deftest other-shapes-of-the-same-schema
  (testing "portable: what malli's default registry reads"
    (let [p (pgmalli/portable registry :pg.sample/users)
          opts {:registry (merge (m/default-schemas) (malli.experimental.time/schemas))}]
      (is (= [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} "happy" "sad"] (get-in p [1 7 1])) "the enum is inlined")
      (is (= [:int {:pg/type "integer" :min -2147483648 :max 2147483647}] (get-in p [1 4 1])) "integers carry their range")
      (is (= :multi (first (nth p 2))) "the branching CHECK stays")
      (is (= 3 (count p)) "the :pg/check constraints are left out")
      (is (m/validate p (assoc user :group_name "a") opts))
      (is (not (m/validate p (assoc user :group_id 2147483648) opts)))
      (is (= 'bytes? (get-in (pgmalli/portable (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:d [:pg/bytes {:min 32 :max 32 :pg/type "bytea"}]]]}}) :pg.public/t) [2 1])))
      (is (not-any? #(and (map? %) (or (:gen/min %) (:gen/max %))) (tree-seq coll? seq p)) "no generation hints")))
  (testing "as-read: the map as next.jdbc builds it"
    (let [r (pgmalli/as-read registry :pg.sample/users {:qualified? true :nil-columns :absent :time :instant})]
      (is (= [:users/group_name {:optional true} [:string {:pg/type "text"}]] (nth r 5)) "NULL columns absent, keys qualified")
      (is (= [:users/updated_at {:optional true} [:time/instant {:pg/type "timestamp"}]] (last r)) "timestamps as Instants")
      (is (= [:users/born {:optional true} ['inst? {:pg/type "date"}]] (nth r 2)) "dates stay java.sql.Date under read-as-instant")
      (is (m/validate r {:users/id 1 :users/group_id 1 :users/mood "sad" :users/seq 1 :users/score 1 :users/total 2} opts)))
    (is (some #{[:nick-upper [:maybe [:string {:pg/generated [:upper [:cast :nick :text]] :pg/type "text"}]]]} (pgmalli/as-read registry :pg.sample/users {:kebab? true})))
    (is (= :order-items/line-no (first (nth (pgmalli/as-read (pgmalli/registry {:registry {:pg.public/order_items [:map {:pg/table "public.order_items"} [:line_no [:int {:pg/type "integer"}]]]}}) :pg.public/order_items {:qualified? true :kebab? true}) 2)))
        "the table half is kebab-cased too"))
  (testing "portable converts what it inlines"
    (let [reg (pgmalli/registry {:registry {:pg.public/code [:and :string [:pg/check-value {:pg/constraint "c"} [:<> :VALUE ""]]]
                                            :pg.public/t [:map {:pg/table "public.t"} [:c [:ref {:pg/type "code"} :pg.public/code]]]}})]
      (is (= [:map {:pg/table "public.t"} [:c [:string {:pg/type "code"}]]] (pgmalli/portable reg :pg.public/t)))))
  (testing "column and non-null"
    (is (= [:maybe [:string {:max 40 :pg/type "character varying"}]] (pgmalli/column registry :pg.sample/users :nick)))
    (is (= [:string {:max 40 :pg/type "character varying"}] (pgmalli/non-null (pgmalli/column registry :pg.sample/users "nick"))))
    (is (nil? (pgmalli/column registry :pg.sample/users :nope)))))

(deftest unknown-names-are-an-error
  (doseq [[what f] {:portable #(pgmalli/portable registry :pg.sample/nope)
                    :column #(pgmalli/column registry :pg.sample/nope :id)
                    :columns #(pgmalli/columns registry :pg.sample/nope)
                    :as-read #(pgmalli/as-read registry :pg.sample/nope)}]
    (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no schema named" (f)) (str what))]
      (is (= :pg.sample/nope (:name (ex-data e))) (str what))
      (is (some #{:pg.sample/users} (:known (ex-data e))) (str what)))))

(deftest as-read-defaults-its-options
  (is (= (pgmalli/as-read registry :pg.sample/users {}) (pgmalli/as-read registry :pg.sample/users))))
