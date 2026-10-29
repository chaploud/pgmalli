(ns pgmalli.honeysql-test
  "HoneySQL query data against the checked-in sample registry."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.experimental.time :as time]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.sample :refer [registry]]))

;; the sample's enum literals are strings; how pgjdbc sends them is its own test
(def opts {:schema "sample" :stringtype :unspecified})
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
  (is (= [{:kind :missing-required-column :table "sample.users" :column :score}]
         (h/check registry {:insert-into :users :values [{:group_id 1 :mood "sad"}]} opts))
      "an INSERT must carry the columns the insert schema requires")
  (is (= [{:kind :enum-literal :column :mood :value "angry" :allowed ["happy" "sad"]}]
         (h/check registry {:select [:id] :from [:users] :where [:= :mood [:cast "angry" :mood]]} opts)))
  (is (= [{:kind :enum-literal :column :mood :value "angry" :allowed ["happy" "sad"]}]
         (h/check registry {:update :users :set {:mood "angry"} :where [:= :id 1]} opts)) "assigned literals too")
  (is (= [{:kind :not-null-violation :table "sample.users" :column :score}]
         (h/check registry {:insert-into :users :values [{:group_id 1 :mood "sad"} {:group_id 1 :mood "sad" :score 1}]} opts))
      "a multi-row INSERT carries the union of its columns, NULL where a row lacks one")
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
    (is (= {:id [:int {:pg/type "bigint"}]
            :moods [:or [:sequential {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]] [:set {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]]]
            :n [:int {:min 0}]}
           (h/arg-types registry body opts)))
    (is (= {:g/id [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]}
           (h/arg-types registry {:update :users :set {:nick :?g/id} :where [:= :id 1]} opts))
        "a qualified one keeps its namespace, as sql/format's :params does")
    (is (= (h/arg-types registry body opts)
           (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :id [:param :id]] [:in :mood ?moods]] :limit ?n} opts))
        "[:param :id] and ?id are the same parameters")
    (let [s (h/query-schema registry [:id :n] body opts)]
      (is (= [:cat [:int {:pg/type "bigint"}] [:int {:min 0}]] (nth s 2)))
      (is (= {:pgmalli/problems [{:kind :missing-arg :arg :moods}]} (second s)) "the one left out of args"))))

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
                                 :from [:users]} opts))
      "?column? once: the driver keeps the last of the columns a name repeats")
  (is (nil? (h/row-schema registry {:select [[[:cast 1 :int]]] :from [:users]} opts)) "a cast value is named after its type, not read here")
  (is (= [:map [:users/id [:int {:pg/identity :always :pg/type "bigint"}]] [:top-id [:maybe :any]]]
         (h/row-schema registry {:select [:id [[:max :id] :top_id]] :from [:users]} (assoc opts :qualified? true :kebab? true)))
      "an expression has no table: its key is unqualified, kebab-cased as the builder does")
  (is (nil? (h/row-schema registry {:select [[[:cast :nick :text]]] :from [:users]} (assoc opts :qualified? true)))
      "a cast to the column's own type is dropped and the key keeps the table: not told without the types"))

(deftest a-set-operations-row-is-named-by-its-first-member
  (let [body {:union-all [{:select [:id [:nick :name]] :from [:users]} {:select [:id [:nick :n]] :from [:users]}]}]
    (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]] [:name [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]]]
           (h/row-schema registry body (assoc opts :qualified? true))))
    (is (= [:map [:id [:maybe :any]]] (h/row-schema registry {:union [{:select [:id] :from [:users]} {:select [[nil :id]]}]} opts))
        "typed where the members agree, nullable where one is")
    (is (nil? (h/row-schema registry {:union [{:select [:id] :from [:users]} '(other)]} opts)))))

(deftest an-in-argument-is-what-sql-format-expands
  (let [t (:ids (h/arg-types registry {:select [:id] :from [:users] :where [:in :id :?ids]} opts))]
    (doseq [v [[1 2] '(1) #{1 2} (map inc [0 1])]] (is (m/validate t v) (pr-str v)))
    (doseq [v [[] #{} 1 nil (long-array [1])]] (is (not (m/validate t v)) (pr-str v)) "IN (), or not expanded")))

(deftest write-names-what-an-extension-sends-for-a-type
  (let [o (assoc opts :write {"character varying" [:or :string :keyword]})]
    (is (= {:n [:or :string :keyword] :ns [:or [:sequential {:min 1} [:or :string :keyword]] [:set {:min 1} [:or :string :keyword]]]
            :v [:maybe [:or :string :keyword]]}
           (h/arg-types registry {:update :users :set {:nick :?v} :where [:and [:= :nick :?n] [:in :nick :?ns]]} o)))
    (is (= {:id [:int {:pg/type "bigint"}]} (h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?id]} o))
        "the other types as before")))

(deftest an-outer-join-may-leave-a-tables-columns-null
  (let [id [:int {:pg/identity :always :pg/type "bigint"}]
        gid [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]
        row #(h/row-schema registry (merge {:select [:u.id :g.id] :from [[:users :u]]} %) (assoc opts :qualified? true))]
    (is (= [:map [:users/id id] [:groups/id [:maybe gid]]] (row {:left-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:users/id [:maybe id]] [:groups/id gid]] (row {:right-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:users/id [:maybe id]] [:groups/id [:maybe gid]]] (row {:full-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:users/id id] [:groups/id gid]] (row {:join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:users/id id] [:groups/id [:maybe gid]]]
           (row {:left-join [[:users :x] [:= :x.id :u.id] [:groups :g] [:= :g.id :u.group_id]]}))
        "every pair of a :left-join")
    (is (= {:optional true} (second (nth (h/row-schema registry {:select [:g/*] :from [:users] :left-join [[:groups :g] [:= :g.id :users.group_id]]} (assoc opts :nil-columns :absent)) 1)))
        "a table's * too, absent under :nil-columns :absent")))

(deftest an-argument-only-code-uses-may-be-nil
  (is (= {:g [:int {:pg/type "integer"}] :from [:maybe [:int {:pg/type "bigint"}]]}
         (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :group_id :?g] (when from [:>= :id :?from])]} opts)))
  (is (= {:g [:int {:pg/type "integer"}]}
         (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :group_id :?g] (when x [:= :group_id :?g])]} opts))
      "one use in the data is enough"))

(deftest a-lenient-comparison-takes-any-value-of-the-columns-kind
  (let [body {:select [:id] :from [:users] :where [:and [:= :id :?id] [:in :group_id :?gs] [:> :score :?min] [:= :nick :?nick] [:= :mood :?mood]] :limit :?n}
        types (h/arg-types registry body (assoc opts :compare :lenient))]
    (is (= [:maybe [:int {:pg/type "bigint"}]] (:id types)))
    (is (= [:maybe [:int {:pg/type "integer"}]] (:min types)) "no range")
    (is (= [:maybe [:string {:pg/type "character varying"}]] (:nick types)) "no length")
    (is (= [:maybe [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]] (:mood types))
        "an enum stays: PostgreSQL refuses a value outside it")
    (is (= [:or [:sequential {:min 1} [:int {:pg/type "integer"}]] [:set {:min 1} [:int {:pg/type "integer"}]]] (:gs types))
        "nil not in :in's: it formats as IN ?")
    (is (= [:int {:min 0}] (:n types)))
    (is (= {:v [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]}
           (h/arg-types registry {:update :users :set {:nick :?v} :where [:= :id 1]} (assoc opts :compare :lenient)))
        "an assigned one keeps the column's bounds")
    (is (= {:ids [:maybe [:sequential [:int {:pg/type "bigint"}]]]}
           (h/arg-types registry {:select [:id] :from [:users] :where [:= :id [:any :?ids]]} (assoc opts :compare :lenient :write {:array [:sequential]})))
        "an array's too")))

(def docs
  (pgmalli/registry {:registry {:pg.public/docs [:map {:pg/table "public.docs"}
                                                      [:id [:int {:pg/type "bigint"}]]
                                                      [:paths [:maybe [:vector {:pg/type "text[]"} [:string {:pg/type "text"}]]]]
                                                      [:meta [:map {:pg/type "jsonb"} [:a :int]]]]}}))

(deftest arrays-and-json-are-the-drivers-objects-unless-read-says-otherwise
  (let [body {:select [:paths :meta] :from [:docs]}]
    (is (= [:map [:paths [:maybe [:any {:pg/type "text[]"}]]] [:meta [:any {:pg/type "jsonb"}]]] (h/row-schema docs body))
        "a java.sql.Array and a PGobject")
    (is (= [:map [:paths [:maybe [:vector [:string {:pg/type "text"}]]]] [:meta [:map {:pg/type "jsonb"} [:a :int]]]]
           (h/row-schema docs body {:read {:array :vector :json :parsed}})))
    (is (= [:map [:paths [:maybe [:sequential :string]]] [:meta [:any {:pg/type "jsonb"}]]]
           (h/row-schema docs body {:read {"text[]" [:sequential :string]}}))
        "a :pg/type over :array")))

(deftest an-array-columns-argument-is-an-array
  (let [body {:insert-into :docs :values [{:id 1 :paths :?paths :meta :?meta}]}]
    (is (= {:paths [:maybe :any] :meta [:any {:pg/type "jsonb"}]} (select-keys (h/arg-types docs body) [:paths :meta]))
        "an assigned array is one the driver takes, :any by default")
    (is (= {:paths [:maybe [:or [:pg/array [:string {:pg/type "text"}]] [:vector [:string {:pg/type "text"}]]]]}
           (select-keys (h/arg-types docs body {:write {:array [:pg/array :vector]}}) [:paths])))
    (is (= {:p [:or [:pg/array [:string {:pg/type "text"}]] [:vector [:string {:pg/type "text"}]]]}
           (h/arg-types docs {:select [:id] :from [:docs] :where [:= :paths :?p]} {:write {:array [:pg/array :vector]}}))
        "compared too")))

(deftest an-any-or-all-argument-is-an-array
  (let [body {:select [:id] :from [:users] :where [:and [:= :id [:any :?ids]] [:<> :group_id [:all :?gs]]]}]
    (is (= {:ids :any :gs :any} (h/arg-types registry body opts)) "the driver takes typed Java arrays, which data cannot name")
    (is (= {:ids [:or [:sequential [:int {:pg/type "bigint"}]] [:set [:int {:pg/type "bigint"}]]]}
           (select-keys (h/arg-types registry body (assoc opts :write {:array [:sequential :set]})) [:ids]))
        "the collections an extension sends instead; empty is an empty array, which SQL takes")
    (let [t (:ids (h/arg-types registry body (assoc opts :write {:array [:pg/array :sequential]})))]
      (doseq [v [(long-array [1 2]) (into-array Long [1]) (long-array []) [1] '()]] (is (m/validate t v {:registry registry}) (pr-str v)))
      (doseq [v [(object-array [1]) (into-array String ["a"]) #{1} nil]] (is (not (m/validate t v {:registry registry})) (pr-str v)))
      (is (= [:or [:pg/array [:int {:pg/type "bigint"}]] [:sequential [:int {:pg/type "bigint"}]]] t)
          ":pg/array is what the driver takes (Object[] it refuses)"))
    (is (= {:ids [:vector [:or :int :string]]}
           (h/arg-types registry {:select [:id] :from [:users] :where [:= :id [:any :?ids]]} (assoc opts :write {:array [:vector] "bigint" [:or :int :string]}))))))

(deftest read-names-the-types-an-extension-reads-otherwise
  (is (= [:map [:born [:maybe :time/local-date]] [:closed_at [:maybe ['inst? {:pg/type "timestamptz"}]]]]
         (h/row-schema registry {:select [:born :closed_at] :from [:users]} (assoc opts :read {"date" :time/local-date})))))

(deftest types-of-parameters-and-rows
  (let [body {:select [:id :nick :closed_at [:group_id :g]] :from [:users] :where [:and [:= :id 'id] [:in :mood 'moods] [:> :score 'min]] :limit 'n}]
    (is (= {'id [:int {:pg/type "bigint"}]
            'moods [:or [:sequential {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]] [:set {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]]]
            'min [:int {:pg/type "integer"}]
            'n [:int {:min 0}]}
           (h/arg-types registry body opts)))
    (let [row (h/row-schema registry body (assoc opts :qualified? true :nil-columns :absent :time :instant))]
      (is (= [:map [:users/id [:int {:pg/identity :always :pg/type "bigint"}]]
              [:users/nick {:optional true} [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]
              [:users/closed_at {:optional true} [:time/instant {:pg/type "timestamptz"}]]
              [:users/g [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]
             row)
          "a column under an alias keeps its table in the key, as the driver does")
      (is (m/validate row {:users/id 1 :users/g 2} malli-opts) "as malli's default registry reads it"))
    (is (= (h/row-schema registry {:select [:users/*] :from [:users]} opts) (h/row-schema registry {:select [:*] :from [:users]} opts)))
    (is (nil? (h/row-schema registry '{:select [:id] :from [[{:select [:id] :from [:users]} :sub]]} (assoc opts :qualified? true)))
        "the driver keys a column through a subquery by its table at the bottom, which an opaque one hides")
    (is (= [:map [:id [:maybe :any]]] (h/row-schema registry '{:select [:id] :from [[{:select [:id] :from [:users]} :sub]]} opts)))
    (is (= [:map [:users/group-id [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]] (h/row-schema registry '{:select [:group_id] :from [:users]} (assoc opts :qualified? true :kebab? true))))
    (is (= {'x [:int {:pg/type "bigint"}]} (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :nope x] [:= :id x]]} opts))
        "an untyped use never hides a typed one")
    (let [row (h/row-schema registry body (assoc opts :nil-columns :absent))]
      (is (= [:closed_at {:optional true} ['inst? {:pg/type "timestamptz"}]] (nth row 3)) "by default time columns are inst?, which the default registry has")
      (is (m/validate row {:id 1 :g 2 :closed_at (java.util.Date.)}) "and no registry is needed"))
    (is (= [:=> [:cat [:int {:pg/type "bigint"}] :any] [:sequential [:map [:n [:maybe :any]]]]]
           (h/query-schema registry '[id extra] '{:select [[[:count :*] :n]] :from [:users] :where [:= :id id]} opts))
        "an expression under an alias is :any; a symbol used nowhere is :any")
    (is (= [:sequential [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]]]
           (last (h/query-schema registry '[id] '{:select [:id] :from [:users] :where [:= :id id]} opts))))))

(deftest scope-follows-the-statement
  (is (= [] (h/check registry {:delete-from :users :where [:in :group_id {:select [:id] :from [:groups] :where [:= :name 'n]}]} opts))
      "a nested statement's columns are judged in its own scope, not the outer one")
  (is (= [{:kind :unknown-column :column :nope :in [:where 2]}]
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
    (is (= [:map [:span [:any {:pg/type "interval"}]]] (h/row-schema with-interval {:select [:span] :from [:spans]} opts)) "an interval is a driver object"))
  (is (= {'ids [:int {:pg/type "bigint"}]}
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
         (h/row-schema registry {:select [:groups/*] :from [:groups]} opts)) ":t/* is the table's columns")
  (is (nil? (h/row-schema registry {:select [:*] :from [[{:select [:id] :from [:users]} :r]]} opts)) "an opaque table's * is unknown")
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
     [{:kind :unknown-column :column :nope} {:kind :missing-required-column :table "sample.users" :column :score}]
     "an unknown column in :columns leaves the column it stands in for missing"]
    [{:insert-into :users :columns [:group_id :mood :score] :values [[1 "sad"]]}
     [{:kind :values-arity :table "sample.users" :row 0 :columns 3 :values 2}]
     "a row shorter than the column list"]
    [{:insert-into :users :columns [:group_id :mood :score] :values [[1 "angry" 2]]}
     [{:kind :enum-literal :column :mood :value "angry" :allowed ["happy" "sad"]}]
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
     [{:kind :unknown-column :column :name :in [:insert-into 1]}]
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
    (is (= {'gid [:int {:pg/type "integer"}]}
           (h/arg-types registry '{:select [:id] :from [:users] :where [:= gid :group_id]} opts))
        "a symbol on the left takes the column's type")))

(deftest a-subquery-added-to-a-query-built-elsewhere
  (is (= [] (h/check registry '(update query :select conj [[:exists {:select [1] :from [:groups] :where [:= :groups.id :skills/group_id]}] :flag]) opts))
      "the enclosing statement is not in the data: its columns cannot be judged")
  (is (= [{:kind :unknown-column :column :groups.nope :in [4 0 1]}]
         (h/check registry '(update query :select conj [[:exists {:select [1] :from [:groups] :where [:= :groups.nope :skills/group_id]}] :flag]) opts))
      "its own columns still are")
  (is (= [{:kind :unknown-column :column :nope :in [1]}] (h/check registry '(cond-> {:select [:nope] :from [:users]} deep? (assoc :limit 1)) opts))
      "a statement built in code at the top is still the statement"))

(deftest a-cte-body-bound-apart-from-its-with
  (is (= [] (h/check registry '(let [paged (cond-> {:select [:id] :from [[:f :user_skills]]} (:limit page) (assoc :limit (:limit page)))]
                                  {:with [[:f filtered-query :materialized] [:p paged]]
                                   :select [:total/n]
                                   :from [[{:select [[[:count :*] :n]] :from [:f]} :total]]}) opts))
      "a CTE body bound in a let sees its sibling CTEs"))

(deftest one-row-results
  (is (= [:=> [:cat [:int {:pg/type "bigint"}]] [:maybe [:map [:nick [:maybe [:string {:pg/type "character varying" :max 40 :pg/max-length 40}]]]]]]
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

(deftest a-misspelt-option-is-an-error
  (doseq [o [{:qualifed? true} {:time :instnat} {:read {:json :parse}} {:write {:array [:vectr]}} {:compare :loose} {:result :first}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pgmalli options" (h/arg-types registry {:select [:id] :from [:users]} (merge opts o))) (pr-str o))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pgmalli options" (h/check registry {:select [:id] :from [:users]} (merge opts o))) (pr-str o))))

(deftest insert-into-a-table-as-an-alias
  (let [body {:insert-into [:users :u] :values [{:group_id :?g :mood "sad" :score 1}] :on-conflict [:id] :do-update-set {:score :?s} :where [:> :u.score 0]}]
    (is (= [] (h/check registry body opts)))
    (is (= [:int {:pg/type "integer" :min -2147483648 :max 2147483647}] (:g (h/arg-types registry body opts))))))

(deftest a-from-of-any-shape-is-read-or-left-alone
  (is (= [] (h/check registry {:select [:*] :from {:select [:id] :from [:users]}} opts)) "a subquery alone")
  (doseq [f [1 true "users"]] (is (vector? (h/check registry {:select [:id] :from f} opts)) (pr-str f))))

(deftest a-table-code-brings-in-has-the-columns-the-query-names
  (is (= [] (h/check registry '{:select [:u.id :g.name] :from [[:users :u]] :left-join (when x [[:groups :g] [:= :g.id :u.group_id]])} opts)))
  (is (= [] (h/check registry '{:select [:u.id] :from (if x [[:users :u]] [[:users :u]])} opts))))

(deftest an-opaque-table-beside-a-known-one-makes-no-column-ambiguous
  (is (= [] (h/check registry '{:select [:nick] :from [:users] :join [t [:= :t.id :users.id]]} opts)))
  (is (= [:map [:nick [:maybe :any]]] (h/row-schema registry '{:select [:nick] :from [:users] :join [t [:= :t.id :users.id]]} opts))
      "untyped: which table PostgreSQL takes it from is not in the data")
  (is (= [{:kind :unknown-column :column :u.nope}] (h/check registry '{:select [:u.nope] :from [[:users :u]] :join [t [:= 1 1]]} opts))
      "a known table's unknown column is still one"))

(deftest expression-names-follow-postgresql
  (let [row #(h/row-schema registry {:select [%] :from [:users]} opts)]
    (is (nil? (row [[:cast [:+ :id 1] :int]])) "an operation cast is named after the type")
    (is (nil? (row [[:cast [:case [:= :id 1] 2] :int]])))
    (is (= [:map [:coalesce [:maybe :any]]] (row [[:cast [:coalesce :id 0] :int]])) "a function cast keeps its name")
    (is (= [:map [:?column? [:maybe :any]]] (row [[:-> :nick "a"]])) "an operator not an identifier")
    (is (= [:map [:max [:maybe :any]]] (row :%max.id)) ":%f.args calls f")
    (is (nil? (row [[:raw "now()"]])) ":raw may be named anything")
    (is (nil? (row [{:select [1]}])) "a subquery is named after its column"))
  (is (= [] (h/check registry {:select [:%max.id [:%count.* :c]] :from [:users]} opts)))
  (is (= [] (h/check registry {:select [[[:lower :nick]]] :from [:users] :order-by [:lower]} opts)) "an expression's name is an output column"))

(deftest a-right-join-nulls-the-tables-it-attaches-to
  (let [nullable #(into {} (for [[a v] (#'h/scope registry % #{} opts)] [a (boolean (:nullable? v))]))]
    (is (= {"u" false "x" true "g" false "i" false}
           (nullable {:select [:*] :from [[:users :u] [:groups :x]] :right-join [[:groups :g] [:= 1 1]] :inner-join [[:groups :i] [:= 1 1]]}))
        "a comma binds looser than JOIN: the right join attaches to the last FROM item; an inner join after it nulls nothing")))

(deftest a-symbol-table-in-joins-and-from-alone
  (is (= [] (h/check registry '{:select [:x] :from table} opts)))
  (is (= [] (h/check registry '{:select [:t.x :u.id] :from [[:users :u]] :left-join [t [:= :t.uid :u.id]]} opts))))

(deftest a-typed-use-wins-over-a-driver-object
  (is (= {:x [:int {:pg/type "bigint"}]} (h/arg-types docs {:select [:id] :from [:docs] :where [:and [:= :meta :?x] [:= :id :?x]]}))
      "[:any {...}] says nothing either"))

(deftest a-set-operations-row-under-absent-nil-columns
  (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]] [:nick {:optional true} [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]]
         (h/row-schema registry {:union [{:select [:id :nick] :from [:users]} {:select [:id :nick] :from [:users]}]} (assoc opts :nil-columns :absent)))))

(deftest an-array-columns-elements-take-overrides
  (is (= {:p [:maybe [:vector :keyword]]} (h/arg-types docs {:update :docs :set {:paths :?p} :where [:= :id 1]} {:write {:array [:vector] "text" :keyword}})))
  (is (= [:map [:paths [:maybe [:vector :keyword]]]] (h/row-schema docs {:select [:paths] :from [:docs]} {:read {:array :vector "text" :keyword}})))
  (is (= {:p [:maybe [:vector [:string {:pg/type "text"}]]]} (h/arg-types docs {:update :docs :set {:paths :?p} :where [:= :id 1]} {:read {"text" :keyword} :write {:array [:vector]}}))
      ":read is the rows' alone"))

(deftest query-schema-carries-the-problems-check-finds
  (let [s (h/query-schema registry [:id] {:select [:nick_name] :from [:users] :where [:= :id :?id]} opts)]
    (is (= {:pgmalli/problems [{:kind :unknown-column :column :nick_name}]} (m/properties (m/schema s))))
    (is (= :any (last s)) "the row it cannot tell"))
  (is (nil? (m/properties (m/schema (h/query-schema registry [:id] {:select [:nick] :from [:users] :where [:= :id :?id]} opts) {:registry registry})))
      "none on a query that agrees"))

(def checked
  (pgmalli/registry {:registry {:pg.public/email [:and :string [:pg/check-value {:pg/constraint "email_check"} [:like :VALUE "%@%"]]]
                                :pg.public/roles [:map {:pg/table "public.roles"}
                                                  [:role_key [:and {:pg/constraints ["roles_role_key_format"] :pg/type "character varying"}
                                                              [:string {:max 255}] [:re "^[a-z_]+$"]]]
                                                  [:nick [:maybe [:string {:max 40 :pg/type "character varying"}]]]
                                                  [:mail [:ref {:pg/type "email"} :pg.public/email]]
                                                  [:level [:int {:pg/type "integer" :min 1 :max 10}]]
                                                  [:kind [:enum {:pg/type "kind"} "a" "b"]]]}}))

(deftest a-lenient-comparison-is-a-schema-for-every-kind-of-column
  (doseq [col [:role_key :nick :mail :level :kind]
          o [{:compare :lenient} {:compare :lenient :write {"character varying" [:or :string :keyword]}}]
          :let [t (:x (h/arg-types checked {:select [:role_key] :from [:roles] :where [:= col :?x]} o))]]
    (is (m/schema t {:registry checked}) (pr-str col o t)))
  (is (= [:maybe [:string {:pg/type "character varying"}]]
         (:x (h/arg-types checked {:select [:role_key] :from [:roles] :where [:= :role_key :?x]} {:compare :lenient})))
      "a CHECKed column's base type, without the CHECK and the length")
  (is (= [:maybe [:or :string :keyword]]
         (:x (h/arg-types checked {:select [:role_key] :from [:roles] :where [:= :role_key :?x]} {:compare :lenient :write {"character varying" [:or :string :keyword]}})))
      ":write's type stays as it is"))

(deftest nil-options-are-none
  (is (= [] (h/check registry {:select [:id] :from [:sample.users]} nil)))
  (is (= (pgmalli/as-read registry :pg.sample/users) (pgmalli/as-read registry :pg.sample/users nil))))

(deftest a-like-pattern-is-no-value-of-the-column
  (is (m/validate (:x (h/arg-types checked {:select [:role_key] :from [:roles] :where [:like :role_key :?x]})) "%adm%"))
  (is (m/validate (:n (h/arg-types registry {:select [:id] :from [:users] :where [:ilike :nick :?n]} opts)) (str "%" (apply str (repeat 40 "a")) "%"))
      "longer than the column's 40"))

(deftest insert-into-a-table-as-an-alias-with-columns
  (is (= [] (h/check registry '{:insert-into [[:users :u] [:group_id :mood :score]] :values [[?a "sad" ?c]] :returning [:u.id]} opts)))
  (is (= [] (h/check registry '{:insert-into (t) :values [{:a 1}]} opts)) "a target code names: its columns are not judged")
  (is (= [] (h/check registry '{:update (t) :set {:a 1} :where [:= :id 1]} opts))))

(deftest a-subquery-alone-in-from-is-opaque
  (is (= [] (h/check registry {:select [:id] :from [{:select [:id] :from [:users]}]} opts))))

(deftest every-comparison-honeysql-writes-types-its-arguments
  (let [types (h/arg-types registry {:select [:id] :from [:users]
                                     :where [:and [:not-in :id :?ids] [:between :score :?lo :?hi] [:not-like :nick :?p] [:is-distinct-from :group_id :?g]]} opts)]
    (is (= [:sequential {:min 1} [:int {:pg/type "bigint"}]] (second (:ids types))) ":not-in as :in")
    (is (= (:lo types) (:hi types) [:int {:pg/type "integer"}]))
    (is (= [:maybe (:lo types)] (:g types)) "IS DISTINCT FROM compares NULL as a value")
    (is (= [:string {:pg/type "character varying"}] (:p types)))))

(deftest a-select-of-every-set-operation-and-the-dot
  (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]]
         (h/row-schema registry {:except-all [{:select [:id] :from [:users]} {:select [:id] :from [:users]}]} opts)))
  (is (= [:map [:nick [:maybe :any]]] (h/row-schema registry {:select [[[:. :u :nick]]] :from [[:users :u]]} opts))
      "[:. a b] is a.b, named b"))

(deftest a-clause-read-nowhere-is-a-problem
  (is (= [{:kind :unread-clause :clause :join-by}] (h/check registry {:select [:id] :from [:users] :join-by [:left [:groups [:= 1 1]]]} opts)))
  (is (= [] (h/check registry {:select [:id] :from [:users] :for :update :window [:w {:partition-by [:id]}]} opts)) "clauses that change nothing read here")
  (is (= [{:kind :unknown-column :column :table}] (h/check registry {:insert-into :users :values [{:table 1 :group_id 1 :mood "sad" :score 1}]} opts))
      "a row's column named like a clause is a column"))

(deftest query-schema-names-an-argument-its-args-lack
  (is (= {:pgmalli/problems [{:kind :missing-arg :arg :id}]}
         (m/properties (m/schema (h/query-schema registry [:db :iid] {:select [:id] :from [:users] :where [:= :id :?id]} opts))))))

(deftest a-misspelt-option-names-the-one-meant
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be spelled :qualified\?" (h/check registry {} {:qualifed? true}))))

(deftest a-lifted-value-is-data
  (is (= [] (h/check registry {:select [:id] :from [:users] :where [:= :id [:lift {:select true :update true :delete false}]]} opts)))
  (is (= [] (h/check registry {:select [:id] :from [:users] :where [:= :id [:inline {:table "x" :distinct 1}]]} opts))))

(deftest a-column-definition-list-is-read-or-left-alone
  (is (vector? (h/check registry {:select [:a] :from [[[:jsonb_to_record :x] [:r {:columns [[:raw "a int"]]}]]]} opts))))

(deftest a-local-code-binds-is-no-missing-arg
  (is (nil? (m/properties (m/schema (h/query-schema registry '[ids] '{:select [:id] :from [:users] :where (into [:or] (for [i ids] [:= :id i]))} opts))))))

(deftest a-call-in-the-wrong-shape-is-an-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes the function's args first" (h/query-schema registry {:select [:id] :from [:users]} opts)))
  (doseq [body ["SELECT 1" ["SELECT 1"] nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reads HoneySQL query data" (h/check registry body opts)) (pr-str body))))

(deftest a-row-of-a-table-the-registry-lacks-is-untold
  (is (nil? (h/row-schema registry {:select [:id] :from [:users]})) ":schema left out: no sample here")
  (is (= [{:kind :unknown-table :table "public.users"}] (h/check registry {:select [:id] :from [:users]}))))

(deftest a-cte-code-builds-may-be-any-unqualified-table
  (is (= [:map [:id [:maybe :any]]] (h/row-schema registry '{:with ctes :select [:id] :from :recent} opts)))
  (is (= [] (h/check registry '{:with ctes :select [:id] :from :recent} opts)))
  (is (= [{:kind :unknown-table :table "other.nope"}] (h/check registry '{:with ctes :select [:id] :from :other.nope} opts))
      "a table qualified by a schema is no CTE"))

(deftest a-ctes-visibility-is-one-judgment
  (let [body {:select [:id :mood] :from [:users] :where [:exists {:with [[:users {:select [:id] :from [:groups]}]] :select [:id] :from [:users]}]}]
    (is (= [] (h/check registry body opts)))
    (is (= [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} "happy" "sad"] (last (last (h/row-schema registry body opts))))
        "the outer users is the table, as check reads it: the inner :with is not visible outside"))
  (is (= [{:kind :unknown-column :column :nick :in [:with 0 1]}] (h/check registry {:with [[:c {:select [:nick] :from [:groups]}]] :select [:id] :from [:users]} opts))
      "a CTE body does not see the tables of the statement its :with belongs to"))

(deftest every-comparison-reads-its-value-as-the-columns
  (doseq [w [[:< :mood "bogus"] [:between :mood "happy" "bogus"] [:is-distinct-from :mood "bogus"]]]
    (is (= [:enum-literal] (map :kind (h/check registry {:select [:id] :from [:users] :where w} opts))) (pr-str w))))

(deftest a-subquery-in-a-row-is-read
  (let [sub {:select [:id] :from [:groups] :join-by [:left [:users [:= 1 1]]]}]
    (is (some #{{:kind :unread-clause :clause :join-by}} (h/check registry {:update :users :set {:group_id sub}} opts)))))

(deftest a-right-join-code-builds-nulls-the-tables-before-it
  (is (= [:map [:id [:maybe [:int {:pg/identity :always :pg/type "bigint"}]]]]
         (h/row-schema registry '{:select [:users.id] :from [:users] :right-join (when x [[:groups :g] [:= 1 1]])} opts))))

(deftest each-part-of-a-statement-sees-what-postgresql-lets-it
  (is (= [{:kind :unknown-column :column :nope :in [:from 0 0]}] (h/check registry {:select [:s.id] :from [[{:select [:id :nope] :from :users} :s]]} opts))
      "a subquery in :from sees no table beside it, nor its own alias")
  (is (= [{:kind :unknown-column :column :name :in [:from 1 0]}] (h/check registry {:select [:id] :from [:groups [{:select [:mood] :from :users :where [:= :name "x"]} :s]]} opts)))
  (is (= [] (h/check registry {:select [:u.id :x.n] :from [[:users :u]] :join [[[:lateral {:select [[:u.id :n]]}] :x] true]} opts))
      "LATERAL does")
  (is (= [{:kind :unknown-column :column :nope :in [:with 0 1]}] (h/check registry {:with [[:users {:select [:nope] :from :users}]] :select [:id] :from :users} opts))
      "a :with entry does not see itself")
  (is (= [{:kind :unknown-table :table "sample.b" :in [:with 0 1]}] (h/check registry {:with [[:a {:select [:id] :from :b}] [:b {:select [:id] :from :users}]] :select [:id] :from :a} opts))
      "nor the entries after it")
  (is (= [] (h/check registry {:with-recursive [[:t {:union-all [{:select [:id] :from :users} {:select [:id] :from :t}]}]] :select [:id] :from :t} opts))
      "a :with-recursive one does")
  (is (= [{:kind :unknown-column :column :groups.id :in [:values 0 0 1]}]
         (h/check registry {:insert-into :groups :values [{:id {:select [:id] :from :users :where [:= :users.id :groups.id]} :name "x"}]} opts))
      "a row of :values does not see the table inserted into"))

(deftest rows-read-each-statement-where-it-sits
  (let [q {:select [:id] :from :groups}]
    (is (= [:map [:id :any]] (h/row-schema registry {:union [q {:with [[:groups {:select [:id] :from :users}]] :select [:id] :from :users :where [:in :id q]}]} opts))
        "the same statement twice, each in its own scope")))

(deftest code-anywhere-leaves-its-part-unjudged
  (is (= [{:kind :unread-clause :clause :frob}] (h/check registry '(cond-> {:select [:id] :from [:users] :frob 1} x (assoc :limit 1)) opts)))
  (is (= [] (h/check registry '{:insert-into [(tbl) [:a]] :values [[1]] :returning [:a]} opts)))
  (is (= [] (h/check registry '{:with [[(nm) {:select [:id] :from :users}]] :select [:id] :from :c} opts))))

(deftest a-star-names-a-table-of-the-scope
  (is (= [{:kind :unknown-column :column :x.*}] (h/check registry {:select [:x.*] :from :users} opts)))
  (is (= [] (h/check registry {:select [:id] :from [[{:select [:id] :from :users}]]} opts)) "[subquery] has no alias"))

(deftest what-postgresql-accepts-checks-clean
  (doseq [q [{:select [:id] :from [:users] :join [:groups [:using :id]]}
             {:select [:users.id] :from [:sample.users]}
             {:select [:sample.users.id] :from [:users]}
             {:select [:ID :Nick] :from [:USERS] :where [:= :Users.ID 1]}
             {:select [:group-id] :from [:users]}
             {:select [:u] :from [[:users :u]]}
             {:select [:users] :from [:users]}]]
    (is (= [] (h/check registry q opts)) (pr-str q))))

(deftest a-scope-hides-what-postgresql-hides
  (is (= [{:kind :unknown-column :column :nosuch :in [:from 1 0 1]}]
         (h/check registry {:select [:u.id] :from [[:users :u] [[:lateral {:select [:nosuch] :from [:groups]}] :p]]} opts))
      "a LATERAL subquery does not see itself")
  (is (= [{:kind :unknown-column :column :u.nick :in [:where 1]}]
         (h/check registry {:select [:id] :from [[:users :u]] :where [:exists {:select [1] :from [[:groups :u]] :where [:= :u.nick "x"]}]} opts))
      "an inner alias hides the outer one of its name")
  (is (= [{:kind :unknown-column :column :nosuch}]
         (h/check registry {:with [[:users {:select [[1 :x]]}]] :update :users :set {:nosuch "x"} :where [:= :id 1]} opts))
      "the table an UPDATE names, which a CTE does not hide"))

(deftest a-write-postgresql-refuses-is-a-problem
  (let [check #(h/check registry % opts)
        nn (fn [col] {:kind :not-null-violation :table "sample.users" :column col})
        ro (fn [col] {:kind :read-only-column :table "sample.users" :column col})]
    (testing "NULL into a NOT NULL column"
      (is (= [(nn :score)] (check {:update :users :set {:score nil} :where [:= :id 1]})))
      (is (= [(nn :group_id)] (check {:insert-into :users :values [{:group_id nil :mood "sad" :score 1 :closed_at 'now}]})))
      (is (= [(nn :group_id)] (check {:insert-into :users :columns [:group_id :mood :score] :values [[nil "happy" 1]]})))
      (is (= [(nn :score)] (check {:insert-into :users :values [{:group_id 1 :score 1}] :on-conflict [:id] :do-update-set {:score nil}})))
      (is (= [(nn :mood)] (check {:insert-into :users :values [{:group_id 1 :score 1} {:group_id 1 :score 1 :mood "happy"}]}))
          "HoneySQL writes NULL for a key one row of :values lacks and another has")
      (is (= [] (check {:update :users :set {:nick nil :score :?score} :where [:= :id 1]})) "a nullable column, an argument")
      (is (= [] (h/check registry {:insert-into :users :values [{:group_id 1 :score 1} {:group_id 1 :score 1 :mood "happy"}]}
                         (assoc opts :format {:values-default-columns #{:mood}})))
          "with sql/format's :values-default-columns the missing key is DEFAULT")
      (is (= [] (check {:insert-into :users :columns [:group_id :score :mood] :values [{:group_id 1 :score 1} {:group_id 1 :score 1 :mood "happy"}]}))
          "under a column list, the keys reaching each column depend on the HoneySQL version"))
    (testing "a column only the database writes"
      (is (= [(ro :id)] (check {:update :users :set {:id 5} :where [:= :id 1]})))
      (is (= [(ro :nick_upper)] (check {:update :users :set {:nick_upper "X"} :where [:= :id 1]})))
      (is (= [(ro :id)] (check {:insert-into :users :values [{:id 3 :group_id 1 :score 1}]})))
      (is (= [] (check {:insert-into [{:overriding-value :system} :users] :values [{:id 3 :group_id 1 :score 1}]}))
          "OVERRIDING SYSTEM VALUE writes an identity ALWAYS column")
      (is (= [] (check {:insert-into [{:overriding-value :user} :users] :values [{:id 3 :group_id 1 :score 1}]}))
          "OVERRIDING USER VALUE ignores the value given")
      (is (= [] (check {:update :users :set {:nick_upper :default :id [:default]} :where [:= :id 1]})) "DEFAULT is allowed"))))

(deftest a-before-trigger-may-fill-what-a-write-leaves-null
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:insert ["fill"]}}
                                                        [:id [:int {:pg/type "integer"}]] [:g [:int {:pg/type "integer"}]]]}})]
    (is (= [] (h/check reg {:insert-into :t :values [{:id 1}]})) "no :missing-required-column")
    (is (= [] (h/check reg {:insert-into :t :values [{:id 1 :g nil}]})) "no :not-null-violation")
    (is (= [{:kind :not-null-violation :table "public.t" :column :g}] (h/check reg {:update :t :set {:g nil}}))
        "an UPDATE has no BEFORE UPDATE trigger here")))

(deftest names-as-honeysql-writes-them
  (let [check #(h/check registry % opts)]
    (is (= [] (check {:select [:system_user :current_user :current_role] :from [:users]})) "SQL value functions are no columns")
    (is (= [] (check {:select [:'nick] :from [:users]})) ":'x is written as x")
    (is (= [] (check {:update :users :set {:x/nick "a"} :where [:= :id 1]})) "HoneySQL drops the namespace of a SET column")
    (is (= [] (check {:insert-into :users :values [{:x/group_id 1 :x/score 1}]})) "and of an INSERT column")
    (is (= [] (check {:insert-into :users :columns [:group_id :score] :values [{:group_id 1 :score 1 :nope 1}]}))
        "a key the column list lacks is not written")
    (is (= [{:kind :unknown-column :column :nope}] (check {:update :users :set {:nick :nope} :where [:= :id 1]}))
        "a keyword assigned is a column")
    (is (= [{:kind :unread-clause :clause 'select} {:kind :unread-clause :clause 'from}] (check '{select [nope] from [users]}))
        "a statement keyed by symbols, as HoneySQL takes it, is said to be unread")
    (is (= [] (check {:update :users :set {:nick :group_name} :where [:= :id 1]})))))

(deftest a-string-reaches-an-enum-column-as-varchar
  (let [check #(h/check registry %1 (merge opts {:stringtype :varchar} %2))
        uncast (fn [v] {:kind :uncast-enum-string :column :mood :value v :type "mood"})]
    (is (= [(uncast "sad")] (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {}))
        "pgjdbc sends a String as varchar, which no enum takes without a cast")
    (is (= [(uncast "sad")] (check {:update :users :set {:mood "sad"} :where [:= :id 1]} {})))
    (is (= [(uncast "sad") (uncast "happy")] (check {:select [:id] :from [:users] :where [:in :mood ["sad" "happy"]]} {})))
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood [:cast "sad" :mood]]} {})) "a cast is what it takes")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {:stringtype :unspecified})) "or stringtype=unspecified")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {:format {:inline true}})) "or the value inlined")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood :?m]} {:stringtype :unspecified}))
        "an argument too is untyped under stringtype=unspecified")))

(deftest an-argument-cast-to-an-enum-is-of-the-enum
  (is (= {:m [:enum "happy" "sad"]}
         (update-vals (h/arg-types registry {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]} opts) m/form))))

(deftest kebab-and-camel-names-as-postgresql-folds-them
  (let [check #(h/check registry % opts)]
    (is (= [] (check {:insert-into :users :values [{:group-id 1 :score 1}]})) "HoneySQL writes :group-id as group_id")
    (is (= [] (check {:insert-into :users :columns [:group-id :score] :values [[1 1]]})))
    (is (= [] (check {:select [[:nick :the-nick]] :from [:users] :order-by [:the_nick]})) "an alias is folded too")
    (is (= [] (check {:select [[:nick :userNick]] :from [:users] :order-by [:usernick]})))
    (is (= [] (check {:select [[:mood :user-mood] [[:count :*] :n]] :from [:users] :group-by [:user_mood]})))
    (is (= [:user_id :usernick] (map first (drop 1 (h/row-schema registry {:select [[:id :user-id] [:nick :userNick]] :from [:users]} opts))))
        "next.jdbc keys the row by the folded label")))

(deftest a-bare-keyword-clause-value-is-read
  (let [check #(h/check registry % opts)]
    (is (= [{:kind :unknown-column :column :nosuch}] (check {:select :nosuch :from :users})))
    (is (= [{:kind :unknown-column :column :nosuch}] (check {:select [:id] :from :users :order-by :nosuch})))
    (is (= [{:kind :unknown-column :column :nosuch}] (check {:update :users :set {:nick "a"} :returning :nosuch})))
    (is (= [:id] (map first (drop 1 (h/row-schema registry {:select :id :from :users} opts)))))))

(deftest an-unknown-column-names-the-columns-it-likely-meant
  (is (= [{:kind :unknown-column :column :nik :did-you-mean [:nick]}]
         (h/check registry {:select [:nik] :from [:users]} opts)))
  (is (= [{:kind :unknown-column :column :u.scroe :did-you-mean [:score]}]
         (h/check registry {:select [:u.scroe] :from [[:users :u]]} opts)))
  (is (= [{:kind :unknown-column :column :zzz}] (h/check registry {:select [:zzz] :from [:users]} opts))
      "nothing close, nothing suggested"))

(deftest each-problem-reads-as-what-to-do
  (is (= ["unknown column nik; did you mean nick?"
          "\"sad\" reaches enum column mood as varchar, which PostgreSQL refuses (HoneySQL sends a string as a parameter, and pgjdbc types it varchar); write [:cast \"sad\" :mood], or pass {:stringtype :unspecified} when the connection sets stringtype=unspecified"]
         (h/explain (h/check registry {:select [:nik] :from [:users] :where [:= :mood "sad"]} (assoc opts :stringtype :varchar)))))
  (is (every? string? (h/explain [{:kind :something-new}])) "a kind with no sentence still prints"))

(def ^{:malli/schema (h/query-schema registry [:id] {:select [:nik] :from [:users] :where [:= :id :?id]} opts)} a-broken-query-fn identity)

(deftest schema-problems-finds-what-instrumentation-does-not-show
  (is (= [{:kind :unknown-column :column :nik :did-you-mean [:nick] :var #'a-broken-query-fn}]
         (h/schema-problems {:ns 'pgmalli.honeysql-test}))))

(deftest a-string-to-a-text-column-a-check-narrows-is-fine
  (let [reg (pgmalli/registry {:registry {:pg.public/attempts [:map {:pg/table "public.attempts"}
                                                               [:id [:int {:pg/type "bigint"}]]
                                                               [:status [:enum {:default "active" :pg/constraints ["attempts_status_check"] :pg/default "active" :pg/type "character varying"}
                                                                         "active" "submitted"]]]}})]
    (is (= [] (h/check reg {:select [:id] :from [:attempts] :where [:= :status "active"]}))
        "varchar = varchar needs no cast: only an enum type does")
    (is (= [] (h/check reg {:update :attempts :set {:status "submitted"} :where [:= :id 1]})))
    (is (= [{:kind :enum-literal :column :status :value "gone" :allowed ["active" "submitted"]}]
           (h/check reg {:select [:id] :from [:attempts] :where [:= :status "gone"]}))
        "a value outside the CHECK's list is still reported")))

(deftest a-function-alias-is-not-held-to-the-columns-it-lists
  (is (= [] (h/check registry {:select [:t.name] :from [[[:unnest [:array [1 2]]] [:t {:columns [:x]}]]]} opts))
      "PostgreSQL reads t.name as name(t)"))

(deftest write-enum-names-what-an-extension-sends-for-an-enum
  (let [types #(update-vals (h/arg-types registry % (assoc opts :write {:enum [:string :keyword]})) m/form)]
    (is (= [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} "happy" "sad" :happy :sad]
           (:m (types {:select [:id] :from [:users] :where [:= :mood :?m]})))
        "the enum's labels kept, as strings and as keywords")
    (is (= [:enum "happy" "sad" :happy :sad] (:m (types {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]})))
        "an argument cast to an enum too")
    (is (= {:m [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} "happy" "sad"]}
           (update-vals (h/arg-types registry {:select [:id] :from [:users] :where [:= :mood :?m]} opts) m/form))
        "without it, the labels as strings")))

(deftest write-enum-wins-over-the-type-for-a-column-with-labels
  (let [reg (pgmalli/registry {:registry {:pg.public/attempts [:map {:pg/table "public.attempts"}
                                                               [:id [:int {:pg/type "bigint"}]]
                                                               [:role [:string {:pg/type "character varying"}]]
                                                               [:status [:enum {:pg/constraints ["attempts_status_check"] :pg/type "character varying"} "active" "submitted"]]]}})
        o {:write {"character varying" [:or :string :keyword] :enum [:string :keyword]}}]
    (is (= {:status [:enum {:pg/constraints ["attempts_status_check"] :pg/type "character varying"} "active" "submitted" :active :submitted]}
           (update-vals (h/arg-types reg {:select [:id] :from [:attempts] :where [:= :status :?status]} o) m/form))
        "a column with labels keeps them: :enum says how its values are sent")
    (is (= {:role [:or :string :keyword]}
           (update-vals (h/arg-types reg {:select [:id] :from [:attempts] :where [:= :role :?role]} o) m/form))
        "a column without labels takes the type's")))

(deftest review-round-1-honeysql
  (testing "a row is keyed by the column's name, not the spelling written"
    (is (= [:group_id] (map first (drop 1 (h/row-schema registry {:select [:group-id] :from [:users]} opts)))))
    (is (= [:group_id] (map first (drop 1 (h/row-schema registry {:select [:GROUP_ID] :from [:users]} opts))))))
  (testing "quoted output keeps an alias as written"
    (is (= [:E :e-mail] (map first (drop 1 (h/row-schema registry {:select [[:nick :E] [:nick :e-mail]] :from [:users]}
                                                          (assoc opts :format {:quoted true})))))))
  (testing "IS [NOT] DISTINCT FROM takes NULL"
    (is (= [:maybe [:string {:pg/type "character varying"}]]
           (m/form (:n (h/arg-types registry {:select [:id] :from [:users] :where [:is-distinct-from :nick :?n]} opts))))))
  (testing "an argument reaching an enum type's column as a String"
    (is (= [{:kind :uncast-enum-string :column :mood :arg :m :type "mood"}]
           (h/check registry {:select [:id] :from [:users] :where [:= :mood :?m]} (assoc opts :stringtype :varchar))))
    (is (= [{:kind :uncast-enum-string :column :mood :arg :m :type "mood"}]
           (h/check registry {:select [:id] :from [:users] :where [:= :mood :?m]} (assoc opts :stringtype :varchar :write {:enum [:keyword]})))
        "an extension sending a keyword as a String sends varchar all the same")
    (is (= [] (h/check registry {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]} (assoc opts :stringtype :varchar)))))
  (testing ":join-by leaves its tables open"
    (is (= [{:kind :unread-clause :clause :join-by}]
           (h/check registry {:select [:u.nick :g.name] :from [[:users :u]] :join-by [:left [[:groups :g] [:= :g.id :u.group_id]]]} opts))))
  (testing "ROLLUP's total row is NULL in its grouped columns"
    (is (= [:maybe [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]
           (second (second (h/row-schema registry {:select [:group_id [[:count :*] :n]] :from [:users] :group-by [[:rollup :group_id]]} opts)))))))

(deftest a-row-is-nullable-where-a-join-or-grouping-pgmalli-does-not-read-may-null-it
  (let [nullable? (fn [q] (every? #(= :maybe (first (last %))) (drop 1 (h/row-schema registry q opts))))]
    (is (nullable? {:select [:u.nick :g.name] :from [[:users :u]] :join-by [:right [[:groups :g] [:= :g.id 9]]]}))
    (is (nullable? {:select [:group_id] :from [:users] :group-by [[:raw "CUBE(group_id)"]]}))))

(deftest an-argument-no-use-types-is-any
  (is (= {:e :any :n [:int {:min 0}]} (h/arg-types registry {:select [:id] :from [:users] :where [:= [:lower :nick] :?e] :offset :?n} opts))))
