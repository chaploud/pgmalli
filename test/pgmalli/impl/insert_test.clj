(ns pgmalli.impl.insert-test
  "Datasets as INSERT statements."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check.generators :as tcg]
            [malli.generator :as mg]
            honey.sql
            [pgmalli.core :as pgmalli]
            [pgmalli.data :as data]
            [pgmalli.impl.json :as json]
            [pgmalli.sample :refer [registry]]))

(deftest inserts-in-the-order-the-database-accepts
  (let [ds (tcg/generate (data/dataset-generator registry {:rows 4}) 30 42)
        stmts (data/inserts registry ds)
        by-table (into {} (map (juxt #(let [i (:insert-into %)] (if (vector? i) (last i) i)) identity)) stmts)]
    (is (= [:sample.groups [{:overriding-value :system} :sample.users]] (map :insert-into stmts)) "parents first; identity columns kept")
    (is (every? #(and (vector? %) (= [:cast :sample.mood] [(first %) (last %)])) (keep :mood (:values (by-table :sample.users)))) "an enum is cast")
    (is (not-any? #(contains? % :nick_upper) (:values (by-table :sample.users))) "generated columns are left out")
    (is (= [[{:overriding-value :system} :sample.users]] (map :insert-into (data/inserts registry (select-keys ds ["sample.users"]))))
        "a parent left out is already in the database")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not" (data/inserts registry {"sample.nope" [{:id 1}]})))
    (is (= [] (data/inserts registry {"sample.groups" []})) "no rows, no INSERT")
    (is (every? (fn [[i row]] (or (nil? (:referrer_id row)) (= (:referrer_id row) (:id row))
                                  (some #(= (:referrer_id row) (:id %)) (take i (:values (by-table :sample.users))))))
                (map-indexed vector (:values (by-table :sample.users))))
        "a row comes after the row it refers to")
    (is (every? #(string? (first (honey.sql/format %))) stmts)))
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/docs [:map {:pg/table "public.docs" :pg/primary-key ["id"]}
                                                           [:id [:int {:pg/type "integer"}]]
                                                           [:body [:any {:pg/type "jsonb"}]]
                                                           [:tags [:maybe [:vector {:pg/type "text[]"} :string]]]]}})
        [{:keys [values]}] (data/inserts reg {"public.docs" [{:id 1 :body {"a" 1} :tags ["x"]} {:id 2 :body [] :tags nil}]})]
    (is (= [{:id 1 :body [:cast "{\"a\":1}" :jsonb] :tags [:array ["x"] :text]} {:id 2 :body [:cast "[]" :jsonb] :tags nil}] values)
        "json written and cast, arrays with their element type, NULL as it is")
    (is (every? #(try (json/write %) true (catch Exception _ false))
                (map :body (tcg/sample (mg/generator :pg.public/docs {:registry reg}) 50)))
        "an unshaped jsonb column generates values JSON can carry")
    (is (= [[{:id 1 :body [:cast "1" :jsonb]} {:id 2 :body [:default]}]]
           (map :values (data/inserts reg {"public.docs" [{:id 1 :body 1} {:id 2}]})))
        "one INSERT per table; a column a row lacks is DEFAULT")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"columns the table does not have"
                          (doall (data/inserts reg {"public.docs" [{:id 1 :nope 2}]})))
        "a column the table does not have never reaches the database")))

(deftest columns-the-table-does-not-have
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"rows carry columns the table does not have"
                        (data/inserts registry {"sample.groups" [{:id 1 :name "a" :motto "x"}]}))
      "thrown by the call, not on realizing the result")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dataset holds tables the registry does not"
                        (data/inserts registry {"sample.nope" [{:id 1}]}))))
