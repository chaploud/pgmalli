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
  (is (= [{:kind :ambiguous-column :column :id :candidates ["sample.users" "sample.groups"]}]
         (h/check registry {:select [:id] :from [:users] :join [:groups [:= :groups.id :users.group_id]]} opts)))
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

(deftest scope-follows-the-statement
  (is (= [] (h/check registry {:delete-from :users :where [:in :group_id {:select [:id] :from [:groups] :where [:= :name 'n]}]} opts))
      "a nested statement's columns are judged in its own scope, not the outer one")
  (is (= [{:kind :unknown-column :column :nope}]
         (h/check registry {:select [:id] :from [:users] :where [:in :group_id {:select [:id] :from [:groups] :where [:= :nope 1]}]} opts)))
  (is (= [] (h/check registry {:delete-from [:users :u] :using [[:groups :g]] :where [:= :g.id :u.group_id]} opts)) ":using")
  (is (= [] (h/check registry {:update :users :set {:score 1} :from [[:groups :g]] :where [:= :g.id :group_id]} opts)) "UPDATE ... FROM")
  (is (= [] (h/check registry {:select [:u.id :g.name] :from [[:users :u]] :cross-join [[:groups :g]]} opts)) ":cross-join")
  (is (= [{:kind :unknown-column :column :u.nope}] (h/check registry {:select [:u.nope] :from [[:users :u]]} opts))
      "a qualified column must exist in its table")
  (is (= [{:kind :unknown-column :column :nope}]
         (h/check registry {:insert-into [[:users [:group_id :mood :score :nope]] {:select [:group_id :mood :score 1] :from [:users]}]} opts))
      "the column list of INSERT ... SELECT")
  (is (= [{:kind :unknown-column :column :nope}] (h/check registry {:insert-into :users :values [{:group_id 1 :mood "sad" :score 1 :nope 1}]} opts))
      "an unknown :values column is reported once")
  (is (= [] (h/check registry '{:insert-into tbl :values rows} opts)) "a target passed in says nothing")
  (let [with-interval (assoc registry :pg.sample/spans [:map {:pg/table "sample.spans"} [:id :int] [:span [:time/duration {:pg/type "interval"}]]])]
    (is (= [:map [:span :any]] (h/row-schema with-interval {:select [:span] :from [:spans]} #{} opts)) "an interval is a driver object"))
  (is (= {'ids [:int {:pg/identity :always :pg/type "bigint"}]}
         (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:in :nope ids] [:= :id ids]]} opts))
      "an :in on an unknown column gives no type either"))

(deftest nested-statements-see-the-enclosing-ones
  (is (= [] (h/check registry {:select [:id] :from [:users] :where [:not-exists {:select [1] :from [:groups] :where [:= :groups.id :users.group_id]}]} opts))
      "a correlated subquery resolves the outer table")
  (is (= [] (h/check registry {:select [:id] :from [:users] :where [:not-exists {:select [1] :from [:groups] :where [:= :id :users.group_id]}]} opts))
      "inner tables come first: :id is groups.id, not ambiguous with users.id")
  (is (= [] (h/check registry {:update [:users :u] :set {:score [:+ :r.score 1]} :from [[{:select [:score] :from [:users]} :r]] :where [:= :u.id 1]} opts))
      "a :set key belongs to the updated table only, whatever else is in scope")
  (is (= [{:kind :unknown-column :column :nick}]
         (h/check registry {:update :groups :set {:nick "x"} :from [[:users :u]] :where [:= :u.group_id :groups.id]} opts)))
  (is (= [] (h/check registry {:select [:users/* [[:count :g.id] :n]] :from [:users] :left-join [[:groups :g] [:= :g.id :users.group_id]] :group-by [:users/id]} opts)))
  (is (= [:map [:id [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]] [:name [:string {:pg/type "text"}]]]
         (h/row-schema registry {:select [:groups/*] :from [:groups]} #{} opts)) ":t/* is the table's columns")
  (is (nil? (h/row-schema registry {:select [:*] :from [[{:select [:id] :from [:users]} :r]]} #{} opts)) "an opaque table's * is unknown")
  (is (= [] (h/check registry {:select [:cp.id] :from [[:users :cp]] :join [[:groups :c] [:= :c.id :cp.group_id]]
                               :left-join [[{:select-distinct-on [[:group_id] :group_id :score] :from [:users] :where [:= :group_id 'g] :order-by [[:group_id :asc] [:id :desc]]} :latest]
                                           [:= :latest.group_id :cp.group_id]]} opts))
      "a :select-distinct-on subquery is a statement of its own")
  (is (= [{:kind :unknown-column :column :nope}] (h/check registry {:select-distinct-on [[:group_id] :group_id :nope] :from [:users]} opts))))

(deftest honeysql-shapes-the-checker-must-read
  (is (= [{:kind :unknown-column :column :nope}]
         (h/check registry {:insert-into [{:overriding-value :system} :users] :values [{:group_id 1 :mood "sad" :score 1 :nope 1}]} opts))
      "the option map inserts itself emits")
  (is (= [] (h/check registry {:insert-into :users :columns [:group_id :mood :score] :values [[1 "sad" 2]]} opts)) ":columns with positional rows")
  (is (= [{:kind :unknown-column :column :nope} {:kind :missing-required-column :table "sample.users" :column "score"}]
         (h/check registry {:insert-into :users :columns [:group_id :mood :nope] :values [[1 "sad" 2]]} opts)))
  (is (= [{:kind :values-arity :table "sample.users" :row 0 :columns 3 :values 2}]
         (h/check registry {:insert-into :users :columns [:group_id :mood :score] :values [[1 "sad"]]} opts)))
  (is (= [{:kind :enum-literal :column :mood :value "angry" :allowed #{"happy" "sad"}}]
         (h/check registry {:insert-into :users :columns [:group_id :mood :score] :values [[1 "angry" 2]]} opts)) "positional values are assignments")
  (is (= {'m [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]}
         (h/arg-types registry '{:insert-into :users :columns [:group_id :mood :score] :values [[1 m 2]]} opts)))
  (is (= [{:kind :unknown-column :column :g.nope}]
         (h/check registry {:select [:u.id] :from [[:users :u]] :join [[:groups :g] [:= :u.group_id :g.nope]]} opts))
      "both sides of a comparison are columns")
  (is (= {'gid [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]}
         (h/arg-types registry '{:select [:id] :from [:users] :where [:= gid :group_id]} opts)) "a symbol on the left takes the column's type")
  (is (= [{:kind :unknown-column :column :nope}] (h/check registry {:select-top [10 :nope] :from [:users]} opts)) "TOP n, then the items")
  (is (= [{:kind :unknown-column :column :nope}]
         (h/check registry {:select [:nope] :from [:users]
                            :where [:in :id {:with [[:users {:select [:id] :from [:groups]}]] :select [:id] :from [:users]}]} opts))
      "a CTE of an inner statement is not visible outside it: the outer :users is the table"))
