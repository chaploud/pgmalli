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
    (is (every? #(and (vector? %) (= [:cast [:raw "\"sample\".\"mood\""]] [(first %) (last %)])) (keep :mood (:values (by-table :sample.users)))) "an enum is cast")
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
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dataset names tables the registry does not"
                        (data/inserts registry {"sample.nope" [{:id 1}]}))))

(def ^:private typed
  (pgmalli/registry {:database-version "x"
                     :registry {:pg.public/t [:map {:pg/table "public.t"}
                                              [:ip [:maybe [:any {:pg/type "inet"}]]]
                                              [:b [:maybe [:string {:pg/type "bit" :min 3 :max 3}]]]
                                              [:v [:maybe [:string {:pg/type "bit varying" :max 4}]]]
                                              [:c [:maybe [:enum {:pg/type "character"} "a" "bb"]]]
                                              [:ca [:maybe [:vector {:pg/type "character[]"} :string]]]
                                              [:s [:maybe [:string {:pg/type "text"}]]]]
                                :pg.public/e [:map {:pg/table "public.e"} [:id [:int {:pg/type "integer" :pg/default [:nextval "e_id_seq"]}]]]}}))

(deftest a-string-of-a-type-a-string-parameter-cannot-be-written-to-is-cast-to-it
  (is (= [{:ip [:cast "10.0.0.1" :inet] :b [:cast "101" :varbit] :v [:cast "1" (keyword "bit varying")] :s "x"}]
         (:values (first (data/inserts typed {"public.t" [{:ip "10.0.0.1" :b "101" :v "1" :s "x"}]}))))))

(deftest an-interval-goes-as-iso-8601-text-cast-to-interval
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:i [:maybe [:time/duration {:pg/type "interval"}]]]
                                                        [:ia [:maybe [:vector {:pg/type "interval[]"} :time/duration]]]
                                                        [:r [:maybe [:any {:pg/type "tstzrange"}]]]
                                                        [:l [:maybe [:any {:pg/type "ltree"}]]]]}})]
    (is (= [{:i [:cast "PT1H" :interval] :ia [:array ["PT2S"] :interval] :r [:cast "[2020-01-01 00:00+00,)" :tstzrange] :l [:cast "a.b" :ltree]}]
           (:values (first (data/inserts reg {"public.t" [{:i (java.time.Duration/parse "PT1H") :ia [(java.time.Duration/parse "PT2S")]
                                                           :r "[2020-01-01 00:00+00,)" :l "a.b"}]})))))
    (is (= [{:i [:cast "1 day" :interval]}] (:values (first (data/inserts reg {"public.t" [{:i "1 day"}]})))))))

(deftest a-char-n-value-is-not-cut-to-one-character
  (is (= [{:c "bb" :ca [:array ["bb"] :bpchar]}] (:values (first (data/inserts typed {"public.t" [{:c "bb" :ca ["bb"]}]}))))))

(deftest rows-of-defaults-alone-name-a-column-as-default
  (is (= ["INSERT INTO public.e (id) VALUES (DEFAULT), (DEFAULT)"] (honey.sql/format (first (data/inserts typed {"public.e" [{} {}]}))))))

(deftest a-misspelt-column-or-table-in-a-dataset-names-the-one-meant
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[:nmae\]; did you mean :name\?"
                        (data/inserts registry {"sample.groups" [{:id 1 :nmae "x"}]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did you mean sample.groups\?"
                        (data/inserts registry {"sample.group" [{:id 1}]})))
  (let [weird (pgmalli/registry {:registry {"pg.public/Weird T" [:map {:pg/table "public.Weird T"} [:Id :int] ["a b" :string]]}})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\[:zz\]; it has \[:Id \"a b\"\]"
                          (data/inserts weird {"public.Weird T" [{:Id 1 "a b" "x" :zz 1}]})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did you mean \"a b\"\?"
                          (data/inserts weird {"public.Weird T" [{:Id 1 "a c" "x"}]})))))

(deftest a-column-with-a-dollar-sign-is-quoted-in-the-column-list
  ;; "Big" needs quotes, so the INSERT names its columns
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:id :int] ["a$b" :int] ["Big" :int]]}})
        [sql & params] (honey.sql/format (first (data/inserts reg {"public.t" [{:id 1 "a$b" 2 "Big" 3}]})))]
    (is (re-find #"\"a\$b\"" sql) sql)
    (is (= 3 (count params)) sql)))
