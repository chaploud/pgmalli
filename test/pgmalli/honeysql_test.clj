(ns pgmalli.honeysql-test
  "HoneySQL query data against the checked-in sample registry."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.experimental.time :as time]
            [pgmalli.honeysql :as h]
            [pgmalli.sample :refer [registry]]))

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

(deftest named-parameters-are-arguments-under-their-params-key
  (let [body {:select [:id] :from [:users] :where [:and [:= :id :?id] [:in :mood :?moods]] :limit :?n}]
    (is (= [] (h/check registry body opts)))
    (is (= [] (h/check registry {:insert-into [[:users [:group_id :mood :score]] {:select [[:?g] [:?m :mood] [[:unnest :?s]]]}]} opts))
        "selected, as INSERT ... SELECT passes values")
    (is (= {:id [:int {:pg/identity :always :pg/type "bigint"}]
            :moods [:or [:sequential {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]] [:set {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]]]
            :n :int}
           (h/arg-types registry body opts)))
    (is (= {:g/id [:maybe [:string {:max 40 :pg/type "character varying"}]]}
           (h/arg-types registry {:update :users :set {:nick :?g/id} :where [:= :id 1]} opts))
        "a qualified one keeps its namespace, as sql/format's :params does")
    (is (= (h/arg-types registry body opts)
           (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :id [:param :id]] [:in :mood ?moods]] :limit ?n} opts))
        "[:param :id] and ?id are the same parameters")
    (is (= [:cat [:int {:pg/identity :always :pg/type "bigint"}] :int]
           (second (h/query-schema registry [:id :n] body opts))))))

(deftest every-table-has-the-system-columns
  (is (= [] (h/check registry {:delete-from :users :where [:in :ctid {:select [:ctid] :from [:users] :where [:= :xmin 1]}]} opts)))
  (is (= {:t [:int {:pg/type "oid"}]} (h/arg-types registry {:select [:id] :from [:users] :where [:= :tableoid :?t]} opts))))

(deftest an-unqualified-pg-name-is-pg-catalogs-first
  (is (= [] (h/check registry {:select [:pg_class.relname] :from [:pg_class] :join [:pg_roles [:= :pg_class.relowner :pg_roles.oid]]} opts))
      "opaque when the registry does not hold pg_catalog")
  (is (= [] (h/check registry {:select [:relname] :from [:pg_catalog.pg_class]} opts)))
  (is (= [{:kind :unknown-table :table "sample.nopg_class"}] (h/check registry {:select [:x] :from [:nopg_class]} opts))))

(deftest a-bare-name-in-order-by-is-an-output-column-first
  (let [body {:select [:u.id [:g.name :gname]] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]]}]
    (is (= [] (h/check registry (assoc body :order-by [:id [:gname :desc]]) opts)))
    (is (= [{:kind :ambiguous-column :column :id :candidates ["sample.users" "sample.groups"]}]
           (h/check registry (assoc body :select [:u.nick] :order-by [:id]) opts))
        "a name no output column has is an input column"))
  (is (= [] (h/check registry {:select [[:nick :n] [[:count :*] :c]] :from [:users] :group-by [:n]} opts))
      "in :group-by only when no input column has it"))

(deftest a-symbol-where-a-table-goes-is-a-table-chosen-at-run-time
  (is (= [] (h/check registry '{:select [:id] :from [table] :where [:= :group_id :?g] :for :update} opts)))
  (is (= [] (h/check registry '{:select [:t.id] :from [[table :t]]} opts)))
  (is (= [] (h/check registry '{:update table :set {:x :?x} :where [:= :id 1]} opts)))
  (is (= [] (h/check registry '{:delete-from table :where [:= :id 1]} opts)))
  (is (= [] (h/check registry '{:insert-into table :values [{:x 1}]} opts)))
  (is (= [] (h/check registry '{:insert-into [table [:x]] :values [[1]]} opts))))

(deftest code-in-a-query-is-not-read-as-columns
  (is (= [] (h/check registry '{:with [(recent-cte) [:x {:select [:id] :from [:users]}]] :select [:id] :from [:recent]} opts))
      "a :with entry built by code may define any table")
  (is (= [] (h/check registry '{:select [:id] :from [:users] :order-by (case sort :nick [[:nick :asc]] [[:id :desc]])} opts)))
  (is (= [] (h/check registry '{:select [:id] :from [:users] :group-by (if x [:nick] [:id])} opts)))
  (is (= [] (h/check registry '{:insert-into [[:users [:group_id (case k :a :mood :score)]] {:select [[:?g] [:?v]]}]} opts))
      "a column built by code may be any one")
  (is (= [] (h/check registry '{:update :users :set {(col-of k) 1} :where [:= :id 1]} opts))))

(deftest a-table-function-without-an-alias-is-named-after-it
  (is (= [] (h/check registry {:select [:*] :from [[[:public.claim_token :?user-id :?token]]]} opts)))
  (is (= [] (h/check registry {:select [:claim_token.x] :from [[[:claim_token 1]]]} opts))))

(deftest a-set-operations-order-by-names-its-first-members-output
  (let [body {:union-all [{:select [[:id :uid] [0 :bucket]] :from [:users]} {:select [:id 1] :from [:groups]}]}]
    (is (= [] (h/check registry (assoc body :order-by [:bucket [:uid :desc]]) opts)))
    (is (= [{:kind :unknown-column :column :nick}] (h/check registry (assoc body :order-by [:nick]) opts))
        "the tables' columns are not in scope there")
    (is (= [] (h/check registry '{:union [(base) {:select [:id] :from [:users]}] :order-by [:x]} opts)))))

(deftest check-never-throws-on-code
  (doseq [body '[{:insert-into [[:users [(f) "s" 1 nil]] {:select [(g)]}]}
                 {:insert-into [:users [(f)]] :values [[1]]}
                 {:select [(f) "s" 1] :from [(t) "u"] :where [:= (c) "x"] :order-by [(o)] :group-by [(g)]}
                 {:update (t) :set {"a" 1 (k) 2} :returning [(r)]}
                 {:select [:id] :from [:users] :join [(j) [:= (a) :id]] :with [(w)] :limit (n)}]]
    (is (vector? (h/check registry body opts)) (pr-str body))
    (is (map? (h/arg-types registry body opts)) (pr-str body))
    (is (vector? (h/query-schema registry '[] body opts)) (pr-str body))))

(deftest an-expression-with-no-alias-has-the-name-postgresql-gives-it
  (is (= [:map [:count [:maybe :any]] [:?column? [:maybe :any]] [:my_fn [:maybe :any]] [:nick [:maybe :any]] [:case [:maybe :any]]
          [:exists [:maybe :any]] [:current_timestamp [:maybe :any]] [:coalesce [:maybe :any]]]
         (h/row-schema registry {:select [[[:count :*]] [[:+ :id 1]] [[:public.my-fn 1]] [[:cast :nick :text]] [[:case [:= :id 1] 2]]
                                          [[:exists {:select [1]}]] :current_timestamp [[:coalesce :id 0]]]
                                 :from [:users]} #{} opts))
      "?column? once: the driver keeps the last of the columns a name repeats")
  (is (nil? (h/row-schema registry {:select [[[:cast 1 :int]]] :from [:users]} #{} opts)) "a cast value is named after its type, not read here")
  (is (= [:map [:users/id [:int {:pg/identity :always :pg/type "bigint"}]] [:top-id [:maybe :any]]]
         (h/row-schema registry {:select [:id [[:max :id] :top_id]] :from [:users]} #{} (assoc opts :qualified? true :kebab? true)))
      "an expression has no table: its key is unqualified, kebab-cased as the builder does")
  (is (nil? (h/row-schema registry {:select [[[:cast :nick :text]]] :from [:users]} #{} (assoc opts :qualified? true)))
      "a cast to the column's own type is dropped and the key keeps the table: not told without the types"))

(deftest a-set-operations-row-is-named-by-its-first-member
  (let [body {:union-all [{:select [:id [:nick :name]] :from [:users]} {:select [:id [:nick :n]] :from [:users]}]}]
    (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]] [:name [:maybe [:string {:max 40 :pg/type "character varying"}]]]]
           (h/row-schema registry body #{} (assoc opts :qualified? true))))
    (is (= [:map [:id [:maybe :any]]] (h/row-schema registry {:union [{:select [:id] :from [:users]} {:select [[nil :id]]}]} #{} opts))
        "typed where the members agree, nullable where one is")
    (is (nil? (h/row-schema registry {:union [{:select [:id] :from [:users]} '(other)]} #{} opts)))))

(deftest an-in-argument-is-what-sql-format-expands
  (let [t (:ids (h/arg-types registry {:select [:id] :from [:users] :where [:in :id :?ids]} opts))]
    (doseq [v [[1 2] '(1) #{1 2} (map inc [0 1])]] (is (m/validate t v) (pr-str v)))
    (doseq [v [[] #{} 1 nil (long-array [1])]] (is (not (m/validate t v)) (pr-str v)) "IN (), or not expanded")))

(deftest write-names-what-an-extension-sends-for-a-type
  (let [o (assoc opts :write {"character varying" [:or :string :keyword]})]
    (is (= {:n [:or :string :keyword] :ns [:or [:sequential {:min 1} [:or :string :keyword]] [:set {:min 1} [:or :string :keyword]]]
            :v [:maybe [:or :string :keyword]]}
           (h/arg-types registry {:update :users :set {:nick :?v} :where [:and [:= :nick :?n] [:in :nick :?ns]]} o)))
    (is (= {:id [:int {:pg/identity :always :pg/type "bigint"}]} (h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?id]} o))
        "the other types as before")))

(deftest an-outer-join-may-leave-a-tables-columns-null
  (let [id [:int {:pg/identity :always :pg/type "bigint"}]
        gid [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]
        row #(h/row-schema registry (merge {:select [:u.id :g.id] :from [[:users :u]]} %) #{} (assoc opts :qualified? true))]
    (is (= [:map [:users/id id] [:groups/id [:maybe gid]]] (row {:left-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:users/id [:maybe id]] [:groups/id gid]] (row {:right-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:users/id [:maybe id]] [:groups/id [:maybe gid]]] (row {:full-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:users/id id] [:groups/id gid]] (row {:join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:users/id id] [:groups/id [:maybe gid]]]
           (row {:left-join [[:users :x] [:= :x.id :u.id] [:groups :g] [:= :g.id :u.group_id]]}))
        "every pair of a :left-join")
    (is (= {:optional true} (second (nth (h/row-schema registry {:select [:g/*] :from [:users] :left-join [[:groups :g] [:= :g.id :users.group_id]]} #{} (assoc opts :nil-columns :absent)) 1)))
        "a table's * too, absent under :nil-columns :absent")))

(deftest an-argument-only-code-uses-may-be-nil
  (is (= {:g [:int {:pg/type "integer" :min -2147483648 :max 2147483647}] :from [:maybe [:int {:pg/identity :always :pg/type "bigint"}]]}
         (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :group_id :?g] (when from [:>= :id :?from])]} opts)))
  (is (= {:g [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]}
         (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :group_id :?g] (when x [:= :group_id :?g])]} opts))
      "one use in the data is enough"))

(deftest an-any-or-all-argument-is-an-array
  (let [body {:select [:id] :from [:users] :where [:and [:= :id [:any :?ids]] [:<> :group_id [:all :?gs]]]}]
    (is (= {:ids :any :gs :any} (h/arg-types registry body opts)) "the driver takes typed Java arrays, which data cannot name")
    (is (= {:ids [:or [:sequential [:int {:pg/identity :always :pg/type "bigint"}]] [:set [:int {:pg/identity :always :pg/type "bigint"}]]]}
           (select-keys (h/arg-types registry body (assoc opts :write {:array [:sequential :set]})) [:ids]))
        "the collections an extension sends instead; empty is an empty array, which SQL takes")
    (let [t (:ids (h/arg-types registry body (assoc opts :write {:array [:pg/array :sequential :nil]})))]
      (doseq [v [(long-array [1 2]) (into-array Long [1]) (long-array []) [1] '() nil]] (is (m/validate t v {:registry registry}) (pr-str v)))
      (doseq [v [(object-array [1]) (into-array String ["a"]) #{1}]] (is (not (m/validate t v {:registry registry})) (pr-str v)))
      (is (= [:or [:pg/array [:int {:pg/identity :always :pg/type "bigint"}]] [:sequential [:int {:pg/identity :always :pg/type "bigint"}]] :nil] t)
          ":pg/array is what the driver takes (Object[] it refuses), :nil the extension's"))
    (is (= {:ids [:vector [:or :int :string]]}
           (h/arg-types registry {:select [:id] :from [:users] :where [:= :id [:any :?ids]]} (assoc opts :write {:array [:vector] "bigint" [:or :int :string]}))))))

(deftest read-names-the-types-an-extension-reads-otherwise
  (is (= [:map [:born [:maybe :time/local-date]] [:closed_at [:maybe ['inst? {:pg/type "timestamptz"}]]]]
         (h/row-schema registry {:select [:born :closed_at] :from [:users]} #{} (assoc opts :read {"date" :time/local-date})))))

(deftest types-of-parameters-and-rows
  (let [body {:select [:id :nick :closed_at [:group_id :g]] :from [:users] :where [:and [:= :id 'id] [:in :mood 'moods] [:> :score 'min]] :limit 'n}]
    (is (= {'id [:int {:pg/identity :always :pg/type "bigint"}]
            'moods [:or [:sequential {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]] [:set {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]]]
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
    (is (= (h/row-schema registry {:select [:users/*] :from [:users]} #{} opts) (h/row-schema registry {:select [:*] :from [:users]} #{} opts)))
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
  (is (= [] (h/check registry {:select [:u.id :g.name] :from [[:users :u]] :outer-join [[:groups :g] [:= :g.id :u.group_id]]} opts)) ":outer-join")
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

(def ^:private shapes
  "[query expected-problems note]: the shapes of HoneySQL data the checker must read."
  '[[{:insert-into [{:overriding-value :system} :users] :values [{:group_id 1 :mood "sad" :score 1 :nope 1}]}
     [{:kind :unknown-column :column :nope}]
     "the option map inserts itself emits"]
    [{:insert-into :users :columns [:group_id :mood :score] :values [[1 "sad" 2]]}
     []
     ":columns with positional rows"]
    [{:insert-into :users :columns [:group_id :mood :nope] :values [[1 "sad" 2]]}
     [{:kind :unknown-column :column :nope} {:kind :missing-required-column :table "sample.users" :column "score"}]
     "an unknown column in :columns leaves the column it stands in for missing"]
    [{:insert-into :users :columns [:group_id :mood :score] :values [[1 "sad"]]}
     [{:kind :values-arity :table "sample.users" :row 0 :columns 3 :values 2}]
     "a row shorter than the column list"]
    [{:insert-into :users :columns [:group_id :mood :score] :values [[1 "angry" 2]]}
     [{:kind :enum-literal :column :mood :value "angry" :allowed #{"happy" "sad"}}]
     "positional values are assignments"]
    [{:select [:u.id] :from [[:users :u]] :join [[:groups :g] [:= :u.group_id :g.nope]]}
     [{:kind :unknown-column :column :g.nope}]
     "both sides of a comparison are columns"]
    [{:select-top [10 :nope] :from [:users]}
     [{:kind :unknown-column :column :nope}]
     "TOP n, then the items"]
    [{:select [:nope] :from [:users]
      :where [:in :id {:with [[:users {:select [:id] :from [:groups]}]] :select [:id] :from [:users]}]}
     [{:kind :unknown-column :column :nope}]
     "a CTE of an inner statement is not visible outside it: the outer :users is the table"]
    [{:update [:users :u] :set {:score :v.score} :from [[{:values [[1 2]]} [:v {:columns [:id :score]}]]] :where [:= :u.id :v.id]}
     []
     "an alias listing its columns over VALUES has those columns"]
    [{:update [:users :u] :set {:score :v.score} :from [[{:values [[1 2]]} [:v {:columns [:id :score]}]]] :where [:= :u.id :v.nope]}
     [{:kind :unknown-column :column :v.nope}]
     "a column that alias does not list"]
    [{:select [:id :current_timestamp] :from [:users] :where [:> :closed_at :current_timestamp]}
     []
     "CURRENT_TIMESTAMP is a SQL word, not a column"]
    [(cond-> {:select [:id] :from [:g]} true (assoc :with [[:f {:select [:id] :from [:users]} :materialized] [:g {:select [:id] :from [:f]}]]))
     []
     "a CTE with a qualifier, built in code"]
    [{:insert-into [:groups {:select [:id :name] :from [:users]}]}
     [{:kind :unknown-column :column :name}]
     "the SELECT of an INSERT ... SELECT does not see the table inserted into"]
    [{:insert-into :groups :values [{:id 1 :name "g"}] :on-conflict [:id] :do-update-set {:nope 1}}
     [{:kind :unknown-column :column :nope}]
     "ON CONFLICT DO UPDATE SET assigns columns too"]
    [{:insert-into :groups :values [{:id 1 :name "g"}] :on-conflict [:id] :do-update-set [:name :nope]}
     [{:kind :unknown-column :column :nope}]
     "as a list of columns too"]
    [{:select [:id] :from [:users] :order-by [[:nope :asc]]}
     [{:kind :unknown-column :column :nope}]
     "ORDER BY names columns"]
    [{:select [:group_id] :from [:users] :group-by [:nope]}
     [{:kind :unknown-column :column :nope}]
     "GROUP BY too"]
    [{:select [:group_id] :from [:users] :group-by [[:lower :nick]] :order-by [[[:count :*] :desc]]}
     []
     "expressions there are not read"]])

(deftest shapes-the-checker-reads
  (doseq [[query expected note] shapes]
    (testing note
      (is (= expected (h/check registry query opts)))))
  (testing "the types the same shapes give"
    (is (= {'m [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]}
           (h/arg-types registry '{:insert-into :users :columns [:group_id :mood :score] :values [[1 m 2]]} opts)))
    (is (= {'gid [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]}
           (h/arg-types registry '{:select [:id] :from [:users] :where [:= gid :group_id]} opts))
        "a symbol on the left takes the column's type")))

(deftest a-subquery-added-to-a-query-built-elsewhere
  (is (= [] (h/check registry '(update query :select conj [[:exists {:select [1] :from [:groups] :where [:= :groups.id :skills/group_id]}] :flag]) opts))
      "the enclosing statement is not in the data: its columns cannot be judged")
  (is (= [{:kind :unknown-column :column :groups.nope}]
         (h/check registry '(update query :select conj [[:exists {:select [1] :from [:groups] :where [:= :groups.nope :skills/group_id]}] :flag]) opts))
      "its own columns still are")
  (is (= [{:kind :unknown-column :column :nope}] (h/check registry '(cond-> {:select [:nope] :from [:users]} deep? (assoc :limit 1)) opts))
      "a statement built in code at the top is still the statement"))

(deftest a-cte-body-bound-apart-from-its-with
  (is (= [] (h/check registry '(let [paged (cond-> {:select [:id] :from [[:f :user_skills]]} (:limit page) (assoc :limit (:limit page)))]
                                  {:with [[:f filtered-query :materialized] [:p paged]]
                                   :select [:total/n]
                                   :from [[{:select [[[:count :*] :n]] :from [:f]} :total]]}) opts))
      "a CTE body bound in a let sees its sibling CTEs"))

(deftest one-row-results
  (is (= [:=> [:cat [:int {:pg/identity :always :pg/type "bigint"}]] [:maybe [:map [:nick [:maybe [:string {:pg/type "character varying" :max 40}]]]]]]
         (h/query-schema registry '[id] '{:select [:nick] :from [:users] :where [:= :id id]} (assoc opts :result :one)))
      "{:result :one} for a function returning one row or nil"))

(deftest every-shape-of-do-update-set
  (is (= [] (h/check registry {:insert-into :groups :values [{:id 1 :name "g"}] :on-conflict [:id]
                               :do-update-set {:fields [:name] :where [:<= :groups.name :EXCLUDED.name]} :returning [:id]} opts))
      "{:fields :where}: the fields are the columns taken from EXCLUDED, the row proposed for insertion")
  (is (= [{:kind :unknown-column :column :EXCLUDED.nope}]
         (h/check registry {:insert-into :groups :values [{:id 1 :name "g"}] :on-conflict [:id]
                            :do-update-set {:fields [:name] :where [:= :groups.name :EXCLUDED.nope]}} opts))
      "EXCLUDED has the target's columns, no others")
  (is (= [] (h/check registry {:insert-into :groups :values [{:id 1 :name "g"}] :on-conflict [:id] :do-update-set {:name :excluded.name}} opts))))
