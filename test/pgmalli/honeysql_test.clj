(ns pgmalli.honeysql-test
  "HoneySQL query data against the checked-in sample registry."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [malli.experimental.time :as time]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]))

(def registry (pgmalli/registry "sample"))
(def opts {:schema "sample"})
(def malli-opts {:registry (merge (m/default-schemas) (time/schemas))})

(deftest queries-that-agree-have-no-problems
  (is (= [] (h/check registry {:select [:id :nick] :from [:users] :where [:= :group_id 'g]} opts)))
  (is (= [] (h/check registry {:select [:u.id [:g.name :group]] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]]} opts)))
  (is (= [] (h/check registry {:insert-into :users :values [{:group_id 1 :mood "sad" :score 1 :closed_at 'now}] :returning [:id]} opts)))
  (is (= [] (h/check registry {:with [[:recent {:select [:id] :from [:users]}]] :select [:id] :from [:recent]} opts)) "a CTE is an opaque table")
  (is (= [] (h/check registry '(cond-> {:select [:id] :from [:tree]} deep? (assoc :with-recursive [[:tree {:union-all [{:select [:id] :from [:users]} {:select [:id] :from [:tree]}]}]])) opts))
      "a CTE built up in code is one too")
  (is (= [] (h/check registry {:insert-into [[:users [:group_id :mood :score]] {:select [:group_id :mood :score] :from [:users]}]} opts))
      "INSERT INTO table (columns) SELECT")
  (is (= [] (h/check registry '{:insert-into :users :values rows} opts)) "rows passed in say nothing about the columns")
  (is (= {} (h/arg-types registry '{:insert-into :users :values rows} opts))))

(deftest problems-name-what-disagrees
  (is (= [{:kind :unknown-table :table "sample.nope"}] (h/check registry {:select [:*] :from [:nope]} opts)))
  (is (= [{:kind :unknown-column :column :nick_name}] (h/check registry {:select [:nick_name] :from [:users]} opts)))
  (is (= [{:kind :unknown-column :column :nope}] (h/check registry {:select [:id] :from [:users] :where [:= :nope 1]} opts)) "compared columns too")
  (is (= [{:kind :unknown-column :column :nope}] (h/check registry {:update :users :set {:nope 1} :where [:= :id 1]} opts)) "and set ones")
  (is (= [{:kind :missing-required-column :table "sample.users" :column "score"}]
         (h/check registry {:insert-into :users :values [{:group_id 1 :mood "sad"}]} opts))
      "an INSERT must carry the columns the insert schema requires")
  (is (= [{:kind :enum-literal :column :mood :value "angry" :allowed #{"happy" "sad"}}]
         (h/check registry {:select [:id] :from [:users] :where [:= :mood [:cast "angry" :mood]]} opts)))
  (is (= [{:kind :enum-literal :column :mood :value "angry" :allowed #{"happy" "sad"}}]
         (h/check registry {:update :users :set {:mood "angry"} :where [:= :id 1]} opts)) "assigned literals too")
  (is (= [] (h/check registry {:insert-into :users :values [{:group_id 1 :mood "sad"} {:group_id 1 :mood "sad" :score 1}]} opts))
      "a multi-row INSERT carries the union of its columns")
  (is (= [{:kind :ambiguous-column :column :id}] (h/check registry {:select [:id] :from [:users] :join [:groups [:= :groups.id :users.group_id]]} opts)))
  (is (= [{:kind :unknown-column :column :nope}] (h/check registry {:with [[:users {:select [:id] :from [:groups]}]] :select [:nope] :from [:sample.users]} opts))
      "a CTE shadows a table only when the reference is written without a schema")
  (is (= [] (h/check registry {:select [:id] :from [:users] :where [:in :mood ["happy" "sad"]]} opts))))

(deftest types-of-parameters-and-rows
  (let [body {:select [:id :nick :closed_at [:group_id :g]] :from [:users] :where [:and [:= :id 'id] [:in :mood 'moods] [:> :score 'min]] :limit 'n}]
    (is (= {'id [:int {:pg/identity :always :pg/type "bigint"}]
            'moods [:sequential [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]]
            'min [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]
            'n :int}
           (h/arg-types registry body opts)))
    (let [row (h/row-schema registry body #{} (assoc opts :qualified? true :nil-columns :absent :time :instant))]
      (is (= [:map [:users/id [:int {:pg/identity :always :pg/type "bigint"}]]
              [:users/nick {:optional true} [:string {:max 40 :pg/type "character varying"}]]
              [:users/closed_at {:optional true} [:time/instant {:pg/type "timestamptz"}]]
              [:users/g [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]
             row)
          "a column under an alias keeps its table in the key, as the driver does")
      (is (m/validate row {:users/id 1 :users/g 2} malli-opts) "as malli's default registry reads it"))
    (is (= [:map [:sub/id [:maybe :any]]] (h/row-schema registry '{:select [:id] :from [[{:select [:id] :from [:users]} :sub]]} #{} (assoc opts :qualified? true))))
    (is (= [:map [:users/group-id [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]] (h/row-schema registry '{:select [:group_id] :from [:users]} #{} (assoc opts :qualified? true :kebab? true))))
    (is (= {'x [:int {:pg/identity :always :pg/type "bigint"}]} (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :nope x] [:= :id x]]} opts))
        "an untyped use never hides a typed one")
    (let [row (h/row-schema registry body #{} (assoc opts :nil-columns :absent))]
      (is (= [:closed_at {:optional true} ['inst? {:pg/type "timestamptz"}]] (nth row 3)) "by default time columns are inst?, which the default registry has")
      (is (m/validate row {:id 1 :g 2 :closed_at (java.util.Date.)}) "and no registry is needed"))
    (is (= [:=> [:cat [:int {:pg/identity :always :pg/type "bigint"}] :any] [:sequential [:map [:n [:maybe :any]]]]]
           (h/query-schema registry '[id extra] '{:select [[[:count :*] :n]] :from [:users] :where [:= :id id]} opts))
        "an expression under an alias is :any; a symbol used nowhere is :any")
    (is (= [:sequential [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]]]
           (last (h/query-schema registry '[id] '{:select [:id] :from [:users] :where [:= :id id]} opts))))))
