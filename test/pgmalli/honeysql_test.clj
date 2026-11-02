(ns pgmalli.honeysql-test
  "HoneySQL query data against the checked-in sample registry."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.experimental.time :as time]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.sample :refer [registry]]))

;; the sample's enum literals are strings; how pgjdbc sends them is its own test
(def unspecified (h/pgjdbc-write {:stringtype :unspecified}))
(def opts {:schema "sample" :write unspecified :qualified? false})

(defn- sends
  "o with rows added to what its :write sends (pgjdbc's by default)."
  [o & rows]
  (update o :write (fnil into (h/pgjdbc-write)) rows))

(defn- collections [& kinds] (for [k kinds] [[k :pgmalli/element] :unknown]))
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
  (is (= [{:kind :not-null-violation :table "sample.users" :column :score :clause :values}]
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
            :n [:maybe [:int {:pg/type "bigint" :min 0}]]}
           (h/arg-types registry body opts)))
    (is (= {:g/id [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]}
           (h/arg-types registry {:update :users :set {:nick :?g/id} :where [:= :id 1]} opts))
        "a qualified one keeps its namespace, as sql/format's :params does")
    (is (= (h/arg-types registry body opts)
           (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :id [:param :id]] [:in :mood ?moods]] :limit ?n} opts))
        "[:param :id] and ?id are the same parameters")
    (let [s (h/query-schema registry [:id :n] body opts)]
      (is (= [:cat [:int {:pg/type "bigint"}] [:maybe [:int {:pg/type "bigint" :min 0}]]] (nth s 2)))
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
  (is (= [:map [:count [:int {:pg/type "bigint"}]] [:?column? [:maybe :any]] [:my_fn [:maybe :any]] [:nick [:maybe [:string {:pg/type "text"}]]]
          [:case [:maybe :any]] [:exists [:boolean {:pg/type "boolean"}]] [:current_timestamp ['inst? {:pg/type "timestamp with time zone"}]]
          [:coalesce [:int {:pg/type "bigint"}]]]
         (h/row-schema registry {:select [[[:count :*]] [[:+ :id 1]] [[:public.my-fn 1]] [[:cast :nick :text]] [[:case [:= :id 1] 2]]
                                          [[:exists {:select [1]}]] :current_timestamp [[:coalesce :id 0]]]
                                 :from [:users]} opts))
      "?column? once: the driver keeps the last of the columns a name repeats")
  (is (nil? (h/row-schema registry {:select [[[:cast 1 :int]]] :from [:users]} opts)) "a cast value is named after its type, not read here")
  (is (= [:map [:users/id [:int {:pg/identity :always :pg/type "bigint"}]] [:top-id [:maybe [:int {:pg/type "bigint"}]]]]
         (h/row-schema registry {:select [:id [[:max :id] :top_id]] :from [:users]} (assoc opts :qualified? true :kebab? true)))
      "an expression has no table: its key is unqualified, kebab-cased as the builder does")
  (is (= [:map {:pgmalli/untold [:nick]}] (h/row-schema registry {:select [[[:cast :nick :text]]] :from [:users]} (assoc opts :qualified? true)))
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

(deftest write-names-what-an-extension-sends
  (let [o (sends opts [:keyword :unknown])
        n [:or [:string {:pg/type "character varying"}] :keyword]]
    (is (= {:n n :ns [:or [:sequential {:min 1} n] [:set {:min 1} n]]
            :v [:maybe [:or [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}] :keyword]]}
           (h/arg-types registry {:update :users :set {:nick :?v} :where [:and [:= :nick :?n] [:in :nick :?ns]]} o)))
    (is (= {:id [:int {:pg/type "bigint"}]} (h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?id]} o))
        "a keyword sent untyped is no bigint's own value")))

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
    (is (= [:maybe [:int {:pg/type "bigint" :min 0}]] (:n types)))
    (is (= {:v [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]}
           (h/arg-types registry {:update :users :set {:nick :?v} :where [:= :id 1]} (assoc opts :compare :lenient)))
        "an assigned one keeps the column's bounds")
    (is (= {:ids [:maybe [:sequential [:int {:pg/type "bigint"}]]]}
           (h/arg-types registry {:select [:id] :from [:users] :where [:= :id [:any :?ids]]} (apply sends (assoc opts :compare :lenient) (collections :sequential))))
        "an array's too")))

(def docs
  (pgmalli/registry {:registry {:pg.public/docs [:map {:pg/table "public.docs"}
                                                      [:id [:int {:pg/type "bigint"}]]
                                                      [:paths [:maybe [:vector {:pg/type "text[]"} [:string {:pg/type "text"}]]]]
                                                      [:meta [:map {:pg/type "jsonb"} [:a :int]]]]}}))

(deftest arrays-and-json-are-the-drivers-objects-unless-read-says-otherwise
  (let [body {:select [:paths :meta] :from [:docs]}]
    (is (= [:map [:paths [:maybe [:any {:pg/type "text[]"}]]] [:meta [:any {:pg/type "jsonb"}]]] (h/row-schema docs body {:qualified? false}))
        "a java.sql.Array and a PGobject")
    (is (= [:map [:paths [:maybe [:vector [:string {:pg/type "text"}]]]] [:meta [:map {:pg/type "jsonb"} [:a :int]]]]
           (h/row-schema docs body {:qualified? false :read {:array :vector :json :parsed}})))
    (is (= [:map [:paths [:maybe [:sequential :string]]] [:meta [:any {:pg/type "jsonb"}]]]
           (h/row-schema docs body {:qualified? false :read {"text[]" [:sequential :string]}}))
        "a :pg/type over :array")))

(deftest an-array-columns-argument-is-an-array
  (let [body {:insert-into :docs :values [{:id 1 :paths :?paths :meta :?meta}]}]
    (is (= {:paths [:maybe :any] :meta [:any {:pg/type "jsonb"}]} (select-keys (h/arg-types docs body) [:paths :meta]))
        "an assigned array is one the driver takes, :any by default")
    (is (= {:paths [:maybe [:or [:pg/array [:string {:pg/type "text"}]] [:vector [:string {:pg/type "text"}]]]]}
           (select-keys (h/arg-types docs body (apply sends {} (collections :pg/array :vector))) [:paths])))
    (is (= {:p [:or [:pg/array [:string {:pg/type "text"}]] [:vector [:string {:pg/type "text"}]]]}
           (h/arg-types docs {:select [:id] :from [:docs] :where [:= :paths :?p]} (apply sends {} (collections :pg/array :vector))))
        "compared too")))

(deftest an-any-or-all-argument-is-an-array
  (let [body {:select [:id] :from [:users] :where [:and [:= :id [:any :?ids]] [:<> :group_id [:all :?gs]]]}]
    (is (= {:ids :any :gs :any} (h/arg-types registry body opts)) "the driver takes typed Java arrays, which data cannot name")
    (is (= {:ids [:or [:sequential [:int {:pg/type "bigint"}]] [:set [:int {:pg/type "bigint"}]]]}
           (select-keys (h/arg-types registry body (apply sends opts (collections :sequential :set))) [:ids]))
        "the collections an extension sends instead; empty is an empty array, which SQL takes")
    (let [t (:ids (h/arg-types registry body (apply sends opts (collections :pg/array :sequential))))]
      (doseq [v [(long-array [1 2]) (into-array Long [1]) (long-array []) [1] '()]] (is (m/validate t v {:registry registry}) (pr-str v)))
      (doseq [v [(object-array [1]) (into-array String ["a"]) #{1} nil]] (is (not (m/validate t v {:registry registry})) (pr-str v)))
      (is (= [:or [:pg/array [:int {:pg/type "bigint"}]] [:sequential [:int {:pg/type "bigint"}]]] t)
          ":pg/array is what the driver takes (Object[] it refuses)"))
    (is (= {:ids [:vector [:or [:int {:pg/type "bigint"}] :string]]}
           (h/arg-types registry {:select [:id] :from [:users] :where [:= :id [:any :?ids]]} (apply sends opts [:string "bigint"] (collections :vector)))))))

(deftest read-names-the-types-an-extension-reads-otherwise
  (is (= [:map [:born [:maybe :time/local-date]] [:closed_at [:maybe ['inst? {:pg/type "timestamptz"}]]]]
         (h/row-schema registry {:select [:born :closed_at] :from [:users]} (assoc opts :read {"date" :time/local-date})))))

(deftest types-of-parameters-and-rows
  (let [body {:select [:id :nick :closed_at [:group_id :g]] :from [:users] :where [:and [:= :id 'id] [:in :mood 'moods] [:> :score 'min]] :limit 'n}]
    (is (= {'id [:int {:pg/type "bigint"}]
            'moods [:or [:sequential {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]] [:set {:min 1} [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]]]
            'min [:int {:pg/type "integer"}]
            'n [:maybe [:int {:pg/type "bigint" :min 0}]]}
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
    (is (= [:map {:pgmalli/untold [:id]}] (h/row-schema registry '{:select [:id] :from [[{:select [:id] :from [:users]} :sub]]} (assoc opts :qualified? true)))
        "the driver keys a column through a subquery by its table at the bottom, which an opaque one hides")
    (is (= [:map [:id [:maybe :any]]] (h/row-schema registry '{:select [:id] :from [[{:select [:id] :from [:users]} :sub]]} opts)))
    (is (= [:map [:users/group-id [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]] (h/row-schema registry '{:select [:group_id] :from [:users]} (assoc opts :qualified? true :kebab? true))))
    (is (= {'x [:int {:pg/type "bigint"}]} (h/arg-types registry '{:select [:id] :from [:users] :where [:and [:= :nope x] [:= :id x]]} opts))
        "an untyped use never hides a typed one")
    (let [row (h/row-schema registry body (assoc opts :nil-columns :absent))]
      (is (= [:closed_at {:optional true} ['inst? {:pg/type "timestamptz"}]] (nth row 3)) "by default time columns are inst?, which the default registry has")
      (is (m/validate row {:id 1 :g 2 :closed_at (java.util.Date.)}) "and no registry is needed"))
    (is (= [:=> [:cat [:int {:pg/type "bigint"}] :any] [:sequential [:map [:n [:int {:pg/type "bigint"}]]]]]
           (h/query-schema registry '[id extra] '{:select [[[:count :*] :n]] :from [:users] :where [:= :id id]} opts))
        "a symbol used nowhere is :any")
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

(deftest a-write-without-returning-returns-its-update-count
  (let [counted [:map [:next.jdbc/update-count :int]]]
    (doseq [q [{:insert-into :groups :values [{:id 1 :name "a"}]}
               {:insert-into :groups :values [{:id 1 :name "a"}] :on-conflict [:id] :do-nothing true}
               {:update :users :set {:nick "a"} :where [:= :id 1]}
               {:delete-from :users :where [:= :id 1]}
               {:with [[:old {:select [:id] :from [:users]}]] :delete-from :users :where [:in :id {:select [:id] :from [:old]}]}
               {:truncate :users}]]
      (is (= counted (h/row-schema registry q opts)) (pr-str q))
      (is (= counted (h/row-schema registry q {:schema "sample"})) "whatever the builder: next.jdbc builds this map itself")
      (is (= [:=> [:cat] counted] (h/query-schema registry [] q (assoc opts :result :one))) "execute-one! returns it, never nil"))
    (is (= [:map [:id [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]
           (h/row-schema registry {:delete-from :groups :returning [:id]} opts)))
    (is (= [:map [:id [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]] [:name [:string {:pg/type "text"}]]]
           (h/row-schema registry {:delete-from :groups} (assoc opts :return-keys true)))
        "pgjdbc adds RETURNING * under :return-keys true")
    (is (= [:map [:name [:string {:pg/type "text"}]]]
           (h/row-schema registry {:update :groups :set {:name "b"}} (assoc opts :return-keys ["name"]))))
    (is (= [:=> [:cat] [:maybe [:map [:name [:string {:pg/type "text"}]]]]]
           (h/query-schema registry [] {:delete-from :groups} (assoc opts :return-keys ["name"] :result :one)))
        "a write returning rows may return none")
    (is (nil? (h/row-schema registry {:truncate :users} (assoc opts :return-keys true)))
        "next.jdbc reads TRUNCATE's generated keys, which have no column")
    (is (= (h/row-schema registry {:update :groups :set {:name "b"}} (assoc opts :return-keys ["name"]))
           (h/row-schema registry {:update :groups :set {:name "b"}} (assoc opts :return-keys [:groups/name])))
        "a keyword names its column, as next.jdbc takes it")
    (is (= [:map [:id [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]
           (h/row-schema registry {:delete-from :groups :returning [:id]} (assoc opts :return-keys ["name"])))
        "pgjdbc adds no RETURNING to a statement that has one")
    (is (= (h/row-schema registry {:select [:id] :from [:users]} opts)
           (h/row-schema registry {:select [:id] :from [:users]} (assoc opts :return-keys true))))
    (is (nil? (h/row-schema registry '{:delete-from :users :returning ?cols} opts)) "a RETURNING code builds may return rows")
    (is (= [{:kind :unknown-column :column :nmae :did-you-mean [:name]}]
           (h/check registry {:delete-from :groups} (assoc opts :return-keys ["nmae"])))
        "check reads the RETURNING :return-keys adds")
    (is (= [{:kind :unknown-column :column :group-id :did-you-mean [:group_id]}]
           (h/check registry {:update :users :set {:nick "a"}} (assoc opts :return-keys [:group-id])))
        "pgjdbc sends a :return-keys name quoted, as written")
    (is (= [] (h/check registry {:update :users :set {:nick "a"} :returning [:id]} (assoc opts :return-keys [:group-id])))
        "an explicit RETURNING leaves :return-keys unused")))

(deftest an-expression-has-the-type-postgresql-gives-it
  (let [row #(h/row-schema registry {:select % :from [:users]} opts)
        bigint [:int {:pg/type "bigint"}]
        nick [:string {:pg/type "character varying"}]]
    (is (= [:map [:n bigint]] (row [[[:count :nick] :n]])) "count is never NULL")
    (is (= [:map [:s [:maybe bigint]]] (row [[[:sum :group_id] :s]])) "sum of an integer is a bigint, NULL over no row")
    (is (= [:map [:a [:maybe [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]] (row [[[:avg :group_id] :a]])))
    (is (= [:map [:n [:maybe [:string {:pg/type "character varying" :max 40 :pg/max-length 40}]]]] (row [[[:coalesce :nick] :n]]))
        "coalesce of one column is that column")
    (is (= [:map [:n nick]] (row [[[:coalesce :nick "x"] :n]]))
        "a value that is not NULL makes it NOT NULL; a literal's length is not the column's")
    (is (= [:map [:n [:maybe :any]]] (row [[[:coalesce :nick :id] :n]])) "columns of different types are not resolved here")
    (is (= [:map [:b [:boolean {:pg/type "boolean"}]] [:c [:maybe [:boolean {:pg/type "boolean"}]]]]
           (row [[[:= :id 1] :b] [[:= :nick "a"] :c]]))
        "a comparison is NULL where an operand may be")
    (is (= [:map [:u [:maybe :any]] [:p [:maybe :any]]] (row [[[:upper :nick] :u] [:?x :p]])) "other forms stay untyped")
    (is (= [:map [:n [:maybe :any]]] (row [[[:coalesce :score 1.5] :n]]))
        "a number is a typed parameter, which PostgreSQL resolves with the column's type")
    (is (= [:map [:i [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]] [:f ['float? {:pg/type "real"}]] [:d [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]
           (row [[(int 1) :i] [(float 1.5) :f] [1.5M :d]]))
        "pgjdbc types a parameter by its class")
    (is (= [:map [:i [:maybe :any]] [:s [:string {:pg/type "text"}]]]
           (h/row-schema registry {:select [[1 :i] ["s" :s]] :from [:users]} (assoc opts :format {:inline true})))
        "an inlined number is typed by its text, which is not read here; an inlined string is text")
    (is (= [:map [:s [:string {:pg/type "text"}]]] (h/row-schema registry {:select [["s" :s]] :from [:users]} opts)))
    (is (= [:map [:b [:maybe :any]]] (row [[[(keyword "~") :id] :b]])) "~ alone is bitwise NOT")
    (is (= [:map [:k [:maybe [:string {:pg/type "text"}]]]] (row [[[:->> :nick "k"] :k]])) "->> is text")
    (is (= [:map [:s [:maybe [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]] (row [[[:sum :id] :s]]))
        "sum of a bigint is a numeric")
    (is (= [:map [:mood [:maybe [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]]] [:n [:maybe bigint]]]
           (h/row-schema registry {:select [:mood [[:count :*] :n]] :from [:users] :group-by [[:rollup :mood]]} opts))
        "ROLLUP adds rows whose columns are NULL")))

(deftest an-argument-compared-with-an-expression-takes-its-type
  (let [args #(h/arg-types registry {:select [:id] :from [:users] :where %} opts)
        text [:string {:pg/type "text"}]]
    (is (= {:k text} (args [:= [:->> :nick "k"] :?k])))
    (is (= {:t text} (args [:not [:= [:cast :id :text] :?t]])))
    (is (= {:ts [:or [:sequential {:min 1} text] [:set {:min 1} text]]} (args [:in [:cast :id :text] :?ts])))
    (is (= {:s [:int {:pg/type "integer"}] :i [:int {:pg/type "bigint"}]}
           (args [:< [:composite :score :id] [:composite :?s :?i]]))
        "a row compares element by element")
    (is (= {:a text :b text} (args [:between [:cast :id :text] :?a :?b])))
    (is (= {:x :any} (args [:= [:upper :nick] :?x])) "an expression of no known type types nothing")
    (let [check #(h/check registry {:select [:id] :from [:users] :where %} (dissoc opts :write))]
      (is (= [{:kind :uncast-string :column [:cast :nick :mood] :arg :m :type "mood"}] (check [:= [:cast :nick :mood] :?m]))
          "a string compared with an enum-typed expression reaches it as varchar")
      (is (= [{:kind :uncast-string :column :mood :arg :m :type "mood"}] (check [:= [:composite :mood :id] [:composite :?m :?i]]))
          "a row is checked element by element")
      (is (= [{:kind :unknown-column :column :nope}] (check [:< [:composite :nope :id] [:composite 1 2]]))))))

(deftest an-insert-select-assigns-its-items-to-the-columns-listed
  (let [q {:insert-into [[:users [:group_id :mood :score :nick]] {:select [[:?g] [[:cast :?m :mood]] [[:unnest :?s]] [:?n :nick_alias]]}]}]
    (is (= {:g [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]
            :m [:enum {:pg/type "mood"} "happy" "sad"]
            :s [:sequential [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]
            :n [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]}
           (h/arg-types registry q (apply sends opts (collections :sequential))))
        "by position: an argument takes its column's type, an unnest one an array of it")
    (is (= [] (h/check registry q (dissoc opts :write))))
    (is (= [{:kind :uncast-string :column :mood :arg :m :type "mood"}]
           (h/check registry {:insert-into [[:users [:group_id :mood :score]] {:select [[:?g] [:?m] [:?s]]}]} (dissoc opts :write))))
    (is (= [] (h/check registry {:insert-into [[:users [:group_id :mood :score]] {:select [[:?g] [[:unnest :?m]] [:?s]]}]} (dissoc opts :write)))
        "an array is not judged: a java.sql.Array of the enum type is taken, a String[] is not")
    (is (= {:s :any} (h/arg-types registry {:insert-into [[:users [:group_id :nick :score]] {:select [:g.* :?s] :from [[:groups :g]]}]} opts))
        "a star stands for columns the data does not count")))

(deftest a-cast-to-an-alias-or-an-enum-has-its-type
  (is (= [:map [:m [:enum {:pg/type "mood"} "happy" "sad"]] [:i [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]
          [:b [:boolean {:pg/type "boolean"}]] [:f [:double {:pg/type "double precision"}]]]
         (h/row-schema registry {:select [[[:cast "sad" :mood] :m] [[:cast :id :int] :i] [[:cast true :bool] :b] [[:cast :score :float] :f]] :from [:users]} opts))))

(deftest an-argument-takes-its-slots-own-values-unless-told-otherwise
  (let [native (dissoc opts :write)
        args #(h/arg-types registry {:select [:id] :from [:users] :where %} (merge native %2))
        bigint [:int {:pg/type "bigint"}]
        numeric ['decimal? {:pg/type "numeric"}]]
    (is (= {:x bigint} (args [:= :id :?x] {})) "a column's own values")
    (is (= {:x [:or numeric :int]} (args [:= [:cast :id :numeric] :?x] {})) "a numeric takes an integer too: the same number")
    (is (= {:x [:or bigint 'decimal? :double]} (args [:= :id :?x] {:accept :postgresql}))
        "under :accept :postgresql, what PostgreSQL compares a bigint with")
    (is (= {:x bigint} (args [:= :id :?x] {:accept :postgresql :write [[:int "bigint"] [:string "character varying"]]})) "of the types :write sends")
    (is (= {:x [:or bigint :string]}
           (args [:= :id :?x] {:accept :postgresql :write [[:int "bigint"] [:string :unknown]]}))
        "an untyped string goes anywhere")
    (is (= {:x [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]} (args [:= :mood :?x] {:accept :postgresql}))
        "an enum takes its labels alone")))

(deftest a-cast-argument-takes-what-postgresql-casts
  (let [args #(h/arg-types registry % (dissoc opts :write))
        cast-to-bigint [:or [:int {:pg/type "bigint"}] 'decimal? :double :string 'bytes?]]
    (is (= {:x cast-to-bigint} (args {:select [:id] :from [:users] :where [:= :id [:cast :?x :bigint]]}))
        "compared: NULL never matches")
    (is (= {:x [:maybe cast-to-bigint]} (args {:select [[[:cast :?x :bigint] :n]]})) "selected: NULL is a value")
    (let [{:keys [x n]} (args {:insert-into [[:users [:score :nick]] {:select [[[:cast :?x :bigint]] [[:cast :?n :text]]]}]})]
      (is (= cast-to-bigint x) "assigned to a NOT NULL column: no NULL")
      (is (= :maybe (first n)) "assigned to a nullable one: NULL"))
    (is (= {:x [:enum {:pg/type "mood"} "happy" "sad"]} (args {:select [:id] :from [:users] :where [:= :mood [:cast :?x :mood]]})))
    (is (= {:x [:or [:int {:pg/type "bigint"}] :string]}
           (h/arg-types registry {:select [:id] :from [:users] :where [:= :id [:cast :?x :bigint]]} {:schema "sample" :write [[:int "bigint"] [:string "character varying"]]}))
        ":write narrows a cast's argument")))

(deftest limit-and-offset-take-nil-for-none
  (is (= {:n [:maybe [:int {:pg/type "bigint" :min 0}]]} (h/arg-types registry {:select [:id] :from [:users] :limit :?n} opts)))
  (let [t (:n (h/arg-types registry {:select [:id] :from [:users] :limit :?n} (assoc opts :accept :postgresql)))]
    (is (every? #(m/validate t %) [nil 1 1M 1.0]))
    (is (not-any? #(m/validate t %) [-1 -1M -1.0]) "PostgreSQL refuses a negative LIMIT, whatever its type")))

(deftest an-alternative-is-held-to-its-columns-range
  (let [t (:x (h/arg-types registry {:update :users :set {:score :?x} :where [:= :id 1]} (assoc opts :accept :postgresql)))]
    (is (m/validate t 3M))
    (is (not (m/validate t 1000000000000M)) "beyond an integer's range, PostgreSQL refuses it")
    (is (not (m/validate t 1.0E12)))))

(deftest only-a-number-is-held-to-a-number-columns-range
  (is (m/validate (:x (h/arg-types registry {:select [[[:cast :?x :integer] :n]]} {:schema "sample"})) "5" {:registry registry})
      "a string cast to an integer is read as one, then checked")
  (let [t (:x (h/arg-types registry {:update :users :set {:nick :?x} :where [:= :id 1]} {:schema "sample" :accept :postgresql}))]
    (is (every? #(m/validate t % {:registry registry}) [50 true]) "a varchar's length bounds no number"))
  (is (= [[:fn decimal?] :int [:fn int?]]
         (drop 2 (:x (h/arg-types registry {:select [:id] :from [:users] :where [:= [:cast :id :numeric] :?x]}
                                  {:schema "sample" :write [[[:fn decimal?] "numeric"] [[:fn int?] "bigint"]]}))))
      "function schemas are kinds of their own"))

(deftest a-misspelt-type-in-write-is-an-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did you mean \"timestamp\"" (h/arg-types registry {:select [:id] :from [:users]} {:write [[:time/instant "timestampz"]]})))
  (is (= (h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?x]} {:write [[[:fn int?] "bigint"]]})
         (h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?x]} {:write [[[:fn int?] "int8"]]}))
      "an alias is a name"))

(deftest an-option-of-pgmalli-0-3-says-what-it-is-now
  (doseq [[o says] [[{:stringtype :unspecified} #":stringtype :unspecified is \(h/pgjdbc-write \{:stringtype :unspecified\}\)"]
                    [{:write {"text" [:or :string :keyword]}} #"\"text\" \[:or :string :keyword\] is a row \[:keyword :unknown\]"]
                    [{:write {"mood" :keyword}} #"\"mood\" :keyword is no type of docs/types.md \(an enum's or a domain's name\?\): its value an extension sends untyped is a row \[:keyword :unknown\]"]
                    [{:write {"mood" :string}} #"\"mood\" :string is no type of docs/types.md \(an enum's or a domain's name\?\): a String goes as varchar"]
                    [{:write {"date" :time/local-date}} #"\"date\" :time/local-date is in \(h/pgjdbc-write\) already"]
                    [{:write {"date" :string}} #"\"date\" :string is a row \[:string \"date\"\]"]
                    [{:write {"jsonb" [:map-of :keyword :any]}} #"\"jsonb\" \[:map-of :keyword :any\] is a row \[\[:map-of :keyword :any\] \"jsonb\"\]"]
                    [{:write {:enum [:keyword]}} #":enum \[:keyword\] is a row \[:keyword :unknown\]"]
                    [{:write {:array [:sequential]}} #":array's :sequential is a row \[\[:sequential :pgmalli/element\] :unknown\]"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo says (h/arg-types registry {:select [:id] :from [:users]} o)) (pr-str o)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"All told, :write is \(into \(h/pgjdbc-write \{:stringtype :unspecified\}\) \[\[:keyword :unknown\] \[\[:sequential :pgmalli/element\] :unknown\]\]\)"
                        (h/arg-types registry {:select [:id] :from [:users]}
                                     {:stringtype :unspecified :write {"text" [:or :string :keyword] :enum [:string :keyword] :array [:sequential]}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"All told, :write is \[\[:keyword \"varchar\"\] \[:string :unknown\]\]"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write [[:keyword "varchar"] [:string "varchar"]] :stringtype :unspecified}))
      "rows already given stay, their strings untyped")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"All told, :write is \[\[:keyword :unknown\] \[:string :unknown\]\]"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write [[:keyword :unknown]] :stringtype :unspecified}))
      "a String goes untyped where no row said how")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"All told, :write is \(into \(h/pgjdbc-write \{:stringtype :unspecified\}\) \[\[:keyword :unknown\]\]\)"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write (conj (h/pgjdbc-write) [:keyword :unknown]) :stringtype :unspecified}))
      "rows built on pgjdbc's as the call")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is no \[schema type\] row"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write [:string "varchar"] :stringtype :unspecified}))
      "a row's shape before :stringtype")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":stringtype nil is no option; leave it out"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write [[:string "text"]] :stringtype nil})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":foo is no option of pgmalli 0.3's :write"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write {:foo :int}}))))

(deftest pgjdbc-write-takes-pgjdbcs-stringtype
  (is (= :unknown (second (first (filter (comp #{:string} first) (h/pgjdbc-write {:stringtype :unspecified}))))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pgmalli options" (h/pgjdbc-write {:stringtype :none})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes an options map" (h/pgjdbc-write :unspecified))))

(deftest a-misshapen-write-names-its-row
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":write is a vector of \[schema type\] rows"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write #{[:int "bigint"]} :stringtype :unspecified})))
  (doseq [[w says] [[nil #":write is a vector of \[schema type\] rows"]
                    [#{[:int "bigint"]} #":write is a vector of \[schema type\] rows"]
                    [[:keyword :unknown] #"row 0 :keyword is no \[schema type\] row; :write is a vector of rows: \[\[:keyword :unknown\]\]"]
                    [[[:int "bigint"] [[:string :keyword] :enum]] #"row 1 .*: a type is a name of docs/types.md"]
                    [[[:pgmalli/element :unknown]] #":pgmalli/element goes in a collection"]
                    [[[[:maybe :pgmalli/element] :unknown]] #":pgmalli/element goes in a collection"]
                    [[[:keyword "user_status"]] #"an enum's or a domain's value goes untyped: \[:keyword :unknown\]"]
                    [[[:int :int8]] #"a string: \[:int \"int8\"\]"]
                    [[["int8" :int]] #"a row is \[schema type\]: \[:int \"int8\"\]"]
                    [[[[:vector :int] "bigint[]"]] #"an array goes as a collection of its elements"]
                    [[[[:vector :string] :unknown]] #"a collection sent untyped is an array, its elements :pgmalli/element: \[\[:vector :pgmalli/element\] :unknown\]"]
                    [[[[:or :string [:map [:a :strng]]] "text"]] #"no malli schema the registry reads \(:strng\)"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo says (h/arg-types registry {:select [:id] :from [:users]} {:write w})) (pr-str w))))

(deftest a-row-of-write-is-a-schema-sent-as-a-type
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no malli schema the registry reads"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write [[:strng "varchar"]]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a collection row goes as an array type \(\"bigint\[\]\"\), or untyped"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write [[[:vector :pgmalli/element] "bigint"]]})))
  (is (map? (h/arg-types registry {:select [:id] :from [:users]} {:write [[[:vector :pgmalli/element] "int8[]"]]}))
      "an array type, by an alias of its element too"))

(deftest a-cast-arguments-nil-follows-every-use-of-the-cast
  (let [nullable? #(= :maybe (first (:x (h/arg-types registry % (merge {:schema "sample"} %2)))))
        cast [:cast :?x :bigint]]
    (is (not (nullable? {:select [:id] :from [:users] :where [:= :id cast]} {})))
    (is (nullable? {:select [:id] :from [:users] :where [:= cast nil]} {}) "IS NULL")
    (is (nullable? {:select [:id] :from [:users] :where [:or [:is cast nil] [:= :id cast]]} {}) "an optional filter")
    (is (nullable? {:select [:id] :from [:users] :where [:is-distinct-from :referrer_id cast]} {}))
    (is (nullable? {:select [:id] :from [:users] :where [:= :id cast]} {:compare :lenient}))
    (is (nullable? {:insert-into :nowhere :values [{:a cast}]} {}) "a column the registry lacks")
    (is (not (nullable? {:select [:id] :from [:users] :where [:in :id {:select [:id] :from [:users] :where [:= :id cast]}]} {}))
        "a subquery's comparison is the cast's use there alone")))

(deftest a-domain-over-a-timestamp-with-a-precision-takes-its-java-time-type
  (let [reg (pgmalli/registry {:registry {:pg.public/at3 [:time/instant {:pg/type "timestamp(3) with time zone"}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:at [:ref {:pg/type "at3"} :pg.public/at3]]]}})]
    (is (m/validate (:x (h/arg-types reg {:update :t :set {:at :?x}})) (java.time.OffsetDateTime/now) {:registry reg}))))

(deftest write-names-what-goes-in-a-cast-too
  (let [o (sends {:schema "sample"} [:keyword :unknown])]
    (is (m/validate (:x (h/arg-types registry {:select [[[:cast :?x :text] :t]]} o)) :skill {:registry registry}))
    (is (= [[:double {:pg/type "double precision"}] [:fn double?] [:fn float?]]
           (rest (:x (h/arg-types registry {:select [:id] :from [:users] :where [:= [:cast :id :double-precision] :?x]}
                                  {:schema "sample" :write [[[:fn double?] "double precision"] [[:fn float?] "real"]]}))))
        "a column's own values are kept whole, first")))

(deftest a-comparison-in-a-select-or-order-by-is-read
  (is (= {:t [:int {:pg/type "bigint"}] :o [:int {:pg/type "integer"}]}
         (h/arg-types registry {:select [:id [[:case [:= :id :?t] 1 :else 0] :c]] :from [:users] :order-by [[[:case [:= :score :?o] 0 :else 1]]]} opts)))
  (is (= [{:kind :unknown-column :column :nope}] (h/check registry {:select [[[:case [:= :nope 1] 1 :else 0] :c]] :from [:users]} opts))))

(deftest write-is-all-an-application-sends
  (let [at #(:x (h/arg-types registry {:update :users :set {:updated_at :?x} :where [:= :id 1]} (merge {:schema "sample"} %)))
        local (java.time.LocalDateTime/of 2026 1 1 0 0)]
    (is (m/validate (at {}) local {:registry registry}) "pgjdbc sends a LocalDateTime as a timestamp")
    (is (not (m/validate (at {:write (remove (comp #{"timestamp without time zone"} second) (h/pgjdbc-write))}) local {:registry registry}))
        "a row left out is a value not sent"))
  (is (= {:m [:map-of :keyword :any]}
         (h/arg-types docs {:update :docs :set {:meta :?m} :where [:= :id 1]} {:write (conj (h/pgjdbc-write) [[:map-of :keyword :any] "jsonb"])}))
      "what goes as a type pgmalli has the driver's object for is all its argument takes")
  (is (= {:id [:int {:pg/type "bigint"}]} (h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?id]} {:schema "sample" :write []}))
      "a column's own values, as pgmalli reads them, whatever :write says"))

(deftest a-row-pgmalli-cannot-tell-may-send-a-string-or-a-keyword
  (doseq [rows [[[[:maybe :string] :unknown]] [[[:enum "happy" "sad"] :unknown]]]]
    (is (= [] (h/check registry {:insert-into :users :values [{:group_id 1 :score 1 :mood "happy"}]} {:schema "sample" :write rows}))
        (pr-str rows)))
  (is (some #{:sad} (:x (h/arg-types registry {:select [:id] :from [:users] :where [:= :mood :?x]} {:schema "sample" :write [[[:maybe :keyword] :unknown]]})))
      "keyword labels where a row may send a keyword untyped"))

(deftest a-domain-keeps-its-checks-whatever-write-sends
  (let [reg (pgmalli/registry {:registry {:pg.public/pos [:and [:int {:pg/type "bigint"}] [:> 0]]
                                          :pg.public/amount [:and ['decimal? {:pg/type "numeric"}] [:>= 0]]
                                          :pg.public/t [:map {:pg/table "public.t"}
                                                        [:p [:ref {:pg/type "pos"} :pg.public/pos]]
                                                        [:a [:ref {:pg/type "amount"} :pg.public/amount]]]}})
        {:keys [p a]} (h/arg-types reg {:update :t :set {:p :?p :a :?a}})
        ok? #(m/validate %1 %2 {:registry reg})]
    (is (ok? p 3))
    (is (not (ok? p -3)))
    (is (ok? a 3) "a Long is a numeric's own value, through the domain")
    (is (not (ok? a -3M)))
    (is (not (ok? (h/insert-schema reg :pg.public/t) {:p -3 :a 1M})))
    (is (not (ok? (:x (h/arg-types reg {:select [[[:cast :?x :amount] :a]]})) -3M)))))

(deftest a-number-check-lists-labels-go-as-each-number-sent
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                         [:d [:enum {:pg/type "double precision" :pg/constraints ["t_d_check"]} 1.0 2.0]]
                                                         [:i [:enum {:pg/type "integer" :pg/constraints ["t_i_check"]} 1 2]]]}})
        ok? #(m/validate %1 %2 {:registry reg})
        {:keys [d]} (h/arg-types reg {:update :t :set {:d :?d}})
        {:keys [i]} (h/arg-types reg {:update :t :set {:i :?i}} {:accept :postgresql})]
    (is (ok? d 1) "a bigint 1 goes to a double precision as 1.0")
    (is (not (ok? d 3)))
    (is (every? #(ok? i %) [1 1M 1.0]) "PostgreSQL takes a numeric or a double that is a label")))

(deftest a-string-of-another-string-type-is-a-string-columns-own
  (is (= {:n [:maybe [:or [:string {:pg/type "text"}] :keyword]]}
         (h/arg-types registry {:update :users :set {:group_name :?n} :where [:= :id 1]} {:schema "sample" :write (conj (h/pgjdbc-write) [:keyword "varchar"])}))))

(deftest pgmallis-own-types-are-no-strings
  (is (= [:uncast-string] (map :kind (h/check registry {:select [:id] :from [:users] :where [:= :mood "sad"]}
                                              {:schema "sample" :write [[:pg/integer "integer"] [:string "varchar"]]})))
      "a String still goes as varchar"))

(deftest an-enum-domain-takes-its-labels-alone
  (let [reg (pgmalli/registry {:registry {:pg.public/mood [:enum "ok" "sad"]
                                          :pg.public/good [:and [:ref {:pg/type "mood"} :pg.public/mood] [:not= "ok"]]
                                          :pg.public/t [:map {:pg/table "public.t"} [:g [:ref {:pg/type "good"} :pg.public/good]]]}})
        t (:g (h/arg-types reg {:update :t :set {:g :?g}} {:write (conj (h/pgjdbc-write) [:keyword :unknown])}))]
    (is (m/validate t :sad {:registry reg}))
    (is (not (m/validate t :glad {:registry reg})) "no keyword but a label")))

(deftest a-numeric-domains-integer-is-held-to-its-checks-alone
  (let [reg (pgmalli/registry {:registry {:pg.public/price [:and ['decimal? {:pg/type "numeric(10,2)"}] [:pg/numeric {:precision 10 :scale 2}] [:>= 0M]]
                                          :pg.public/t [:map {:pg/table "public.t"} [:p [:ref {:pg/type "price"} :pg.public/price]]]}})
        ok? #(m/validate %1 %2 {:registry reg})
        p (:p (h/arg-types reg {:update :t :set {:p :?p}}))]
    (is (ok? p 1) "PostgreSQL takes a bigint into a numeric domain")
    (is (not (ok? p -1)))
    (is (ok? (:x (h/arg-types reg {:select [[[:cast :?x :price] :p]]})) 1))))

(deftest a-cast-cuts-a-string-to-its-length
  (let [reg (pgmalli/registry {:registry {:pg.public/code3 [:string {:pg/type "character varying(3)" :max 3 :pg/max-length 3}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:c [:ref {:pg/type "code3"} :pg.public/code3]]]}})]
    (is (m/validate (:x (h/arg-types reg {:select [[[:cast :?x :code3] :c]]})) "abcd" {:registry reg}) "CAST('abcd' AS varchar(3)) is 'abc'")
    (is (not (m/validate (:c (h/arg-types reg {:update :t :set {:c :?c}})) "abcd" {:registry reg})) "assigned, it is too long"))
  (let [reg (pgmalli/registry {:registry {:pg.public/short [:string {:pg/type "character varying(10)" :max 10 :pg/max-length 10 :min 2}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:c [:ref {:pg/type "short"} :pg.public/short]]]}})]
    (is (not (m/validate (:x (h/arg-types reg {:select [[[:cast :?x :short] :c]]})) "a" {:registry reg})) "a cast keeps a CHECK's length")))

(deftest a-number-label-is-read-from-a-string-sent-untyped
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:l [:enum {:pg/type "integer" :pg/constraints ["t_l_check"]} 1 2 3]]]}})]
    (is (m/validate (:l (h/arg-types reg {:update :t :set {:l :?l}} {:accept :postgresql :write (h/pgjdbc-write {:stringtype :unspecified})})) "1" {:registry reg}))))

(deftest an-integer-sent-as-integer-is-a-bigint-columns-own
  (is (= {:x [:or [:int {:pg/type "bigint"}] :symbol]}
         (h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?x]} {:schema "sample" :write (conj (h/pgjdbc-write) [:symbol "integer"])}))))

(deftest a-row-pgmalli-cannot-tell-does-not-type-a-string-literal
  (is (= [:map [:a [:string {:pg/type "character varying"}]]]
         (h/row-schema registry {:select [["x" :a]]} {:schema "sample" :qualified? false :write [[[:fn double?] "double precision"]]}))))

(deftest a-registry-without-the-derived-schema-says-so
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the registry has no insert schema"
                        (h/insert-schema (merge (m/default-schemas) {:pg.public/t [:map {:pg/table "public.t"} [:id :int]]}) :pg.public/t))
      "a hand-written row schema has none"))

(deftest an-untyped-value-is-a-text-or-enum-columns-own
  (is (= {:x [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"]}
         (h/arg-types registry {:select [:id] :from [:users] :where [:= :mood :?x]} (sends {:schema "sample"} [:keyword "character varying"])))
      "a keyword sent as varchar is no enum's label")
  (is (= {:x [:any {:pg/type "json"}]}
         (h/arg-types (pgmalli/registry {:registry {:pg.public/j [:map {:pg/table "public.j"} [:doc [:any {:pg/type "json"}]]]}})
                      {:select [:doc] :from [:j] :where [:= :doc :?x]} {:accept :postgresql :write unspecified}))
      "json has no =, untyped or not")
  (doseq [rows [unspecified [[[:and :string [:fn string?]] :unknown]] [[[:or :keyword :string] :unknown]] [['string? :unknown]]]]
    (is (= [] (h/check registry {:select [:id] :from [:users] :where [:= :mood "sad"]} {:schema "sample" :write rows}))
        (str "a String sent untyped reaches an enum: " (pr-str rows))))
  (doseq [t ["varchar" "text"]]
    (is (= [:uncast-string] (map :kind (h/check registry {:select [:id] :from [:users] :where [:= :mood "sad"]} {:schema "sample" :write [[:string t]]})))
        (str "a String sent as " t)))
  (is (= [:uncast-string] (map :kind (h/check registry {:select [:id] :from [:users] :where [:= :mood :?m]} {:schema "sample" :write [[:string "text"]]})))))

(deftest a-number-going-to-a-checked-number-column-is-held-to-the-check
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                         [:n [:and {:pg/type "numeric" :pg/constraints ["t_n_check"]} 'decimal? [:> 0]]]
                                                         [:k [:enum {:pg/type "integer" :pg/constraints ["t_k_check"]} 1 2]]]}})
        {:keys [n k]} (h/arg-types reg {:update :t :set {:n :?n :k :?k}} {:write (conj (h/pgjdbc-write) [:keyword :unknown])})]
    (is (m/validate n 3 {:registry reg}))
    (is (not (m/validate n -3 {:registry reg})) "PostgreSQL checks the CHECK after converting it")
    (is (= [:enum {:pg/type "integer" :pg/constraints ["t_k_check"]} 1 2] k) "a number label has no keyword")))

(deftest a-cast-to-a-type-pgmalli-does-not-answer-for-takes-anything
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:b [:string {:pg/type "bit"}]]]}})]
    (is (= {:x :any} (h/arg-types reg {:select [[[:cast :?x :bit] :b]]})) "PostgreSQL casts a bigint to bit")))

(deftest an-arrays-elements-are-read-as-the-columns-type
  (let [t (:xs (h/arg-types registry {:select [:id] :from [:users] :where [:= :id [:any :?xs]]}
                            (apply sends {:schema "sample" :accept :postgresql} (collections :sequential))))]
    (is (m/validate t [1] {:registry registry}))
    (is (not (m/validate t [1.5M] {:registry registry})) "'{1.5}'::bigint[] is invalid input"))
  (let [t (:xs (h/arg-types registry {:insert-into [[:users [:group_id :score]] {:select [[:?g] [[:unnest :?xs]]]}]}
                            (sends {:schema "sample" :accept :postgresql} [[:sequential :pgmalli/element] "numeric[]"])))]
    (is (m/validate t [1.5M] {:registry registry}) "unnest assigns each element of a typed array, which PostgreSQL rounds")))

(deftest a-types-own-values-come-first-however-many-kinds-it-takes
  (is (= [:string {:pg/type "text"}] (second (second (:x (h/arg-types registry {:select [[[:cast :?x :text] :t]]} {:schema "sample"})))))))

(deftest an-inlined-string-is-text
  (is (= [:map [:s [:string {:pg/type "text"}]]]
         (h/row-schema registry {:select [["s" :s]] :from [:users]} {:schema "sample" :qualified? false :format {:inline true}}))))

(deftest a-fraction-going-to-an-integer-is-rounded-first
  (let [t (:x (h/arg-types registry {:select [[[:cast :?x :integer] :n]]} {:schema "sample"}))]
    (is (m/validate t 2147483647.4 {:registry registry}) "PostgreSQL rounds it to 2147483647")
    (is (not (m/validate t 2147483648.0 {:registry registry})))))

(deftest a-cast-into-a-not-null-column-takes-no-nil
  (let [x #(:x (h/arg-types registry % {:schema "sample"}))
        cast [:cast :?x :bigint]]
    (is (not= :maybe (first (x {:select [:id] :from [:users] :where [:in :id [cast 2]]}))))
    (is (not= :maybe (first (x {:select [:id] :from [:users] :where [:between :id cast 9]}))))
    (is (not= :maybe (first (x {:update :users :set {:score [:cast :?x :integer]} :where [:= :id 1]}))))
    (is (= :maybe (first (x {:update :users :set {:referrer_id cast} :where [:= :id 1]}))))))

(deftest insert-and-update-schemas-type-a-row-written
  (let [v (h/insert-schema registry :pg.sample/users {:schema "sample"})
        s (h/update-schema registry :pg.sample/users (sends {:schema "sample"} [:keyword :unknown]))
        ok? #(m/validate %1 %2 {:registry registry})]
    (is (= [:born :closed_at :group_id :group_name :mood :nick :referrer_id :score :seq :total :updated_at :id :nick_upper] (map first (drop 2 v)))
        "the columns the database writes last")
    (is (ok? v {:group_id 1 :score 0 :id :default :nick_upper [:default] :total :default}) "HoneySQL's :default where the database fills or writes a column")
    (is (not-any? #(ok? v %) [{:group_id 1 :score 0 :id 1} {:group_id :default :score 0}]) "a value where the database writes a column, :default where it fills none")
    (is (ok? s {:total :default}))
    (is (not (ok? s {:score :default})) "a NOT NULL column with no default has no DEFAULT to set")
    (is (ok? v {:group_id 1 :score 0}) "those it fills optional")
    (is (not-any? #(ok? v %) [{:score 0} {:group_id 1 :score 0 :id 1} {:group_id 1 :score 0 :mood "glad"} {:group_id 1 :score 0 :nick nil :born "x"}]))
    (is (every? #(ok? s %) [{} {:nick nil} {:mood :sad} {:nick :abc}]) "every column optional, as :write sends it")
    (is (not-any? #(ok? s %) [{:score nil} {:id 1} {:mood :glad}]))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes its row schema :pg.sample/users" (h/insert-schema registry :pg.sample.users/insert)))
    (is (= [:born :closed-at :group-id] (take 3 (map first (drop 2 (h/insert-schema registry :pg.sample/users {:schema "sample" :kebab? true})))))
        "keyed as HoneySQL writes a column, kebab-cased as next.jdbc reads it back")
    (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:address_line1 :string]]}})]
      (is (= [:address-line1] (map first (drop 2 (h/insert-schema reg :t {:kebab? true}))))
          "HoneySQL writes :address-line1 as address_line1, :address-line-1 as address_line_1")
      (is (= [:address_line1] (map first (drop 2 (h/insert-schema reg :t {:kebab? true :format {:quoted true}}))))
          "quoted, HoneySQL writes a key as it is"))
    (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} ["a-b" :string]]}})]
      (is (= ["a-b"] (map first (drop 2 (h/insert-schema reg :t {:kebab? true}))))
          "a column keyed by a string, which HoneySQL quotes as it is"))
    (is (= v (h/insert-schema registry :users {:schema "sample"}) (h/insert-schema registry "users" {:schema "sample"})
           (h/insert-schema registry :sample.users) (h/insert-schema registry :sample/users) (h/insert-schema registry "sample.users"))
        "a table as HoneySQL names it, in :schema")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did you mean" (h/insert-schema registry :pg.sample.users)))
    (let [reg (pgmalli/registry {:registry {:pg.public/v [:map {:pg/view "public.v"} [:id [:maybe [:int {:pg/type "bigint"}]]]]}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a view has no insert schema" (h/insert-schema reg :pg.public/v))))))

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
  (doseq [o [{:qualifed? true} {:time :instnat} {:read {:json :parse}} {:write [[:int "bigint" :extra]]} {:compare :loose} {:result :first} {:return-keys []} {:return-keys "id"}]]
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
    (is (= [:map [:coalesce [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]] (row [[:cast [:coalesce :id 0] :int]])) "a function cast keeps its name")
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

(deftest an-array-columns-elements-take-what-write-sends
  (is (= {:p [:maybe [:vector [:or [:string {:pg/type "text"}] :keyword]]]} (h/arg-types docs {:update :docs :set {:paths :?p} :where [:= :id 1]} (apply sends {} [:keyword "text"] (collections :vector)))))
  (is (= [:map [:paths [:maybe [:vector :keyword]]]] (h/row-schema docs {:select [:paths] :from [:docs]} {:qualified? false :read {:array :vector "text" :keyword}})))
  (is (= {:p [:maybe [:vector [:string {:pg/type "text"}]]]} (h/arg-types docs {:update :docs :set {:paths :?p} :where [:= :id 1]} (apply sends {:read {"text" :keyword}} (collections :vector))))
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
          o [{:compare :lenient} (sends {:compare :lenient} [:keyword :unknown])]
          :let [t (:x (h/arg-types checked {:select [:role_key] :from [:roles] :where [:= col :?x]} o))]]
    (is (m/schema t {:registry checked}) (pr-str col o t)))
  (is (= [:maybe [:string {:pg/type "character varying"}]]
         (:x (h/arg-types checked {:select [:role_key] :from [:roles] :where [:= :role_key :?x]} {:compare :lenient})))
      "a CHECKed column's base type, without the CHECK and the length")
  (is (= [:maybe [:or [:string {:pg/type "character varying"}] :keyword]]
         (:x (h/arg-types checked {:select [:role_key] :from [:roles] :where [:= :role_key :?x]} (sends {:compare :lenient} [:keyword :unknown]))))
      "and what :write sends untyped"))

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
        nn (fn [col clause] {:kind :not-null-violation :table "sample.users" :column col :clause clause})
        ro (fn [col] {:kind :read-only-column :table "sample.users" :column col})]
    (testing "NULL into a NOT NULL column"
      (is (= [(nn :score :set)] (check {:update :users :set {:score nil} :where [:= :id 1]})))
      (is (= [(nn :group_id :values)] (check {:insert-into :users :values [{:group_id nil :mood "sad" :score 1 :closed_at 'now}]})))
      (is (= [(nn :group_id :values)] (check {:insert-into :users :columns [:group_id :mood :score] :values [[nil "happy" 1]]})))
      (is (= [(nn :score :do-update-set)] (check {:insert-into :users :values [{:group_id 1 :score 1}] :on-conflict [:id] :do-update-set {:score nil}})))
      (is (= [(nn :mood :values)] (check {:insert-into :users :values [{:group_id 1 :score 1} {:group_id 1 :score 1 :mood "happy"}]}))
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

(deftest null-spellings-are-checked-in-every-write
  (doseq [v [nil :null [:inline nil] [:lift nil]]
          [clause column q]
          [[:set :score {:update :users :set {:score v} :where [:= :id 1]}]
           [:values :group_id {:insert-into :users :values [{:group_id v :score 1}]}]
           [:values :group_id {:insert-into :users :columns [:group_id :score] :values [[v 1]]}]
           [:do-update-set :score {:insert-into :users :values [{:group_id 1 :score 1}]
                                  :on-conflict [:id] :do-update-set {:score v}}]]]
    (is (= [{:kind :not-null-violation :table "sample.users" :column column :clause clause}]
           (h/check registry q opts))
        (pr-str q)))
  (doseq [v [nil :null [:inline nil] [:lift nil]]]
    (is (= [] (h/check registry {:update :users :set {:nick v} :where [:= :id 1]} opts)) (pr-str v))))

(deftest a-quoted-null-is-a-column
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                       [:id [:int {:pg/type "integer"}]]
                                                       [:null [:int {:pg/type "integer"}]]]}})
        q {:update :t :set {:id :null}}]
    (doseq [format [{:quoted true} {:quoted-always #"^null$"}
                    {:dialect :ansi}
                    {:dialect :ansi :quoted true}
                    {:dialect :ansi :quoted-always #"^id$"}
                    {:dialect :ansi :quoted false :quoted-always #"^null$"}
                    {:dialect :ansi :quoted nil :quoted-always #"^null$"}
                    {:dialect :nrql :quoted true}]]
      (is (= [] (h/check reg q {:format format})) (pr-str format)))
    (doseq [format [{} {:quoted-always #"^id$"}
                    {:dialect :ansi :quoted false}
                    {:dialect :ansi :quoted nil}
                    {:dialect :ansi :quoted false :quoted-always #"^id$"}
                    {:dialect :nrql}]]
      (is (= [{:kind :not-null-violation :table "public.t" :column :id :clause :set}]
             (h/check reg q {:format format})) (pr-str format)))))

(deftest null-tests-read-their-column-in-every-condition
  (doseq [op [:is :is-not]
          q [{:select [:id] :from [:users] :where [op :nosuch nil]}
             {:select [:id] :from [:users] :group-by [:id] :having [op :nosuch nil]}
             {:select [:users.id] :from [:users] :join [:groups [op :nosuch nil]]}
             {:insert-into :groups :values [{:id 1 :name "g"}]
              :on-conflict [:id] :do-update-set {:fields [:name] :where [op :nosuch nil]}}]]
    (is (= [{:kind :unknown-column :column :nosuch}] (h/check registry q opts)) (pr-str q)))
  (doseq [op [:is :is-not]]
    (is (= [{:kind :unknown-column :column :u.nosuch :in [:where 1]}]
           (h/check registry {:select [:id] :from [[:users :u]]
                              :where [:exists {:select [1] :from [:groups] :where [op :u.nosuch nil]}]} opts)))
    (is (= [] (h/check registry {:select [:id] :from [[:users :u]]
                                 :where [:exists {:select [1] :from [:groups] :where [op :u.nick nil]}]} opts)))))

(deftest is-true-false-unknown-are-not-columns
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                       [:flag [:maybe [:boolean {:pg/type "boolean"}]]]]}})]
    (doseq [op [:is :is-not], v [nil true false :null :true :false :unknown]]
      (is (= [] (h/check reg {:select [:flag] :from [:t] :where [op :flag v]})) (pr-str [op v])))))

(deftest a-before-trigger-may-fill-what-a-write-leaves-null
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:insert ["fill"]}}
                                                        [:id [:int {:pg/type "integer"}]] [:g [:int {:pg/type "integer"}]]]}})]
    (is (= [] (h/check reg {:insert-into :t :values [{:id 1}]})) "no :missing-required-column")
    (doseq [v [nil :null [:inline nil] [:lift nil]]]
      (is (= [] (h/check reg {:insert-into :t :values [{:id 1 :g v}]})) (pr-str v)))
    (is (= [{:kind :not-null-violation :table "public.t" :column :g :clause :set}] (h/check reg {:update :t :set {:g nil}}))
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
  (let [check #(h/check registry %1 (merge (dissoc opts :write) %2))
        uncast (fn [v] {:kind :uncast-string :column :mood :value v :type "mood"})]
    (is (= [(uncast "sad")] (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {}))
        "pgjdbc sends a String as varchar, which no enum takes without a cast")
    (is (= [(uncast "sad")] (check {:update :users :set {:mood "sad"} :where [:= :id 1]} {})))
    (is (= [(uncast "sad") (uncast "happy")] (check {:select [:id] :from [:users] :where [:in :mood ["sad" "happy"]]} {})))
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood [:cast "sad" :mood]]} {})) "a cast is what it takes")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {:write unspecified})) "or stringtype=unspecified")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {:format {:inline true}})) "or the value inlined")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood :?m]} {:write unspecified}))
        "an argument too is untyped under stringtype=unspecified")))

(deftest an-argument-cast-to-an-enum-is-of-the-enum
  (is (= {:m [:enum {:pg/type "mood"} "happy" "sad"]}
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
          "\"sad\" reaches column mood as a typed string, which its type mood does not take (HoneySQL sends a string as a parameter, typed as :write sends it: varchar under pgjdbc's default); write [:cast \"sad\" :mood], or pass {:write (h/pgjdbc-write {:stringtype :unspecified})} when the connection sets stringtype=unspecified"]
         (h/explain (h/check registry {:select [:nik] :from [:users] :where [:= :mood "sad"]} (dissoc opts :write)))))
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
  (let [types #(update-vals (h/arg-types registry % (sends opts [:keyword :unknown])) m/form)]
    (is (= [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} "happy" "sad" :happy :sad]
           (:m (types {:select [:id] :from [:users] :where [:= :mood :?m]})))
        "the enum's labels kept, as strings and as keywords")
    (is (= [:enum {:pg/type "mood"} "happy" "sad" :happy :sad] (:m (types {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]})))
        "an argument cast to an enum too")
    (is (= {:m [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} "happy" "sad"]}
           (update-vals (h/arg-types registry {:select [:id] :from [:users] :where [:= :mood :?m]} opts) m/form))
        "without it, the labels as strings")))

(deftest keywords-sent-untyped-take-a-columns-labels
  (let [reg (pgmalli/registry {:registry {:pg.public/attempts [:map {:pg/table "public.attempts"}
                                                               [:id [:int {:pg/type "bigint"}]]
                                                               [:role [:string {:pg/type "character varying"}]]
                                                               [:status [:enum {:pg/constraints ["attempts_status_check"] :pg/type "character varying"} "active" "submitted"]]]}})
        o (sends {} [:keyword :unknown])]
    (is (= {:status [:enum {:pg/constraints ["attempts_status_check"] :pg/type "character varying"} "active" "submitted" :active :submitted]}
           (update-vals (h/arg-types reg {:select [:id] :from [:attempts] :where [:= :status :?status]} o) m/form))
        "a column with labels keeps them, as keywords too")
    (is (= {:role [:or [:string {:pg/type "character varying"}] :keyword]}
           (update-vals (h/arg-types reg {:select [:id] :from [:attempts] :where [:= :role :?role]} o) m/form))
        "a column without labels takes keywords")))

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
    (is (= [{:kind :uncast-string :column :mood :arg :m :type "mood"}]
           (h/check registry {:select [:id] :from [:users] :where [:= :mood :?m]} (dissoc opts :write))))
    (is (= [{:kind :uncast-string :column :mood :arg :m :type "mood"}]
           (h/check registry {:select [:id] :from [:users] :where [:= :mood :?m]} (sends (dissoc opts :write) [:keyword :unknown])))
        "an extension sending a keyword as a String sends varchar all the same")
    (is (= [] (h/check registry {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]} (dissoc opts :write)))))
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
  (is (= {:e :any :n [:maybe [:int {:pg/type "bigint" :min 0}]]} (h/arg-types registry {:select [:id] :from [:users] :where [:= [:lower :nick] :?e] :offset :?n} opts))))

(deftest params-schema-is-sql-formats-params-map
  (let [body {:update :users :set {:nick :?nick} :where [:= :id :?id]}
        s (h/params-schema registry body opts)]
    (is (= (into [:map] (sort-by (comp str key) (h/arg-types registry body opts))) s))
    (is (m/validate s {:id 1 :nick nil} malli-opts))
    (is (not (m/validate s {:id 1} malli-opts)) "a key sql/format would miss")
    (is (= [:map] (h/params-schema registry {:select [:id] :from [:users]} opts)))))

(deftest tables-are-what-a-query-reads-and-writes-not-its-aliases
  (is (= {:reads #{"sample.users"} :writes #{}} (h/tables registry {:select [:*] :from [[:users :groups]]} opts)) "an alias is not the table it names")
  (is (= {:reads #{"sample.groups"} :writes #{"sample.users"}}
         (h/tables registry {:update :users :set {:nick "a"} :from [[:groups :g]] :where [:= :g.id :users.group_id]} opts)))
  (is (= {:reads #{"sample.users"} :writes #{}}
         (h/tables registry {:with [[:groups {:select [:id] :from [:users]}]] :select [:*] :from [:groups]} opts)) "a CTE is no table")
  (is (= {:reads #{} :writes #{"sample.users"}} (h/tables registry {:insert-into :users :values [{:id 1}] :on-conflict [:id] :do-update-set [:nick]} opts)))
  (is (= {:reads #{} :writes #{"sample.users"}} (h/tables registry {:truncate :users} opts)))
  (is (= {:reads #{} :writes #{"sample.users" "sample.groups"}} (h/tables registry {:truncate [[:users :groups] :cascade]} opts)))
  (is (= {:reads #{"sample.users"} :writes #{}} (h/tables registry {:select [:excluded.id] :from [[:users :excluded]]} opts))
      "excluded is a table's alias outside ON CONFLICT"))

(deftest string-names-are-as-honeysql-quotes-them
  (is (= [] (h/check registry {:select [:u.nick] :from [[:users "u"]]} opts)))
  (is (= {:reads #{"sample.users"} :writes #{}} (h/tables registry {:select [:u.nick] :from [[:users "u"]]} opts)))
  (is (= {:reads #{"sample.users"} :writes #{}} (h/tables registry {:with [["c" {:select [:id] :from [:users]}]] :select [:id] :from [:c]} opts)))
  (is (= [{:kind :unknown-table :table "sample.c"}] (h/check registry {:with [["C" {:select [:id] :from [:users]}]] :select [:*] :from [:c]} opts))))

(deftest a-string-reaches-a-type-that-takes-no-varchar
  (let [reg (pgmalli/registry {:registry {:pg.public/hosts [:map {:pg/table "public.hosts"}
                                                            [:id [:int {:pg/type "integer"}]]
                                                            [:ip [:any {:pg/type "inet"}]]
                                                            [:flags [:string {:min 4 :max 4 :pg/type "bit"}]]
                                                            [:mask [:maybe [:string {:pg/type "bit"}]]]
                                                            [:doc [:any {:pg/type "jsonb"}]]
                                                            [:note [:string {:pg/type "text"}]]
                                                            [:born [:maybe [inst? {:pg/type "date"}]]]
                                                            [:seen [:maybe [inst? {:pg/type "timestamp with time zone"}]]]
                                                            [:paths [:maybe [:any {:pg/type "text[]"}]]]]}})
        check #(h/check reg %1 (merge {:schema "public"} %2))]
    (testing "a type of types but the string ones"
      (is (= [{:kind :uncast-string :column :born :value "2020-01-01" :type "date"}] (check {:select [:id] :from [:hosts] :where [:= :born "2020-01-01"]} {})))
      (is (= [{:kind :uncast-string :column :seen :value "2020-01-01" :type "timestamptz"}
              {:kind :uncast-string :column :seen :value "2021-01-01" :type "timestamptz"}]
             (check {:select [:id] :from [:hosts] :where [:between :seen "2020-01-01" "2021-01-01"]} {})))
      (is (= [{:kind :uncast-string :column :id :value "1" :type "integer"}] (check {:update :hosts :set {:id "1"}} {})))
      (is (= [{:kind :uncast-string :column :paths :value "{a}" :type "text[]"}] (check {:update :hosts :set {:paths "{a}"}} {})) "an array")
      (is (= [] (check {:select [:id] :from [:hosts] :where [:= :born "2020-01-01"]} {:write unspecified})) "sent untyped")
      (is (= [] (check {:select [:id] :from [:hosts] :where [:= :born :?b]} {})) "an argument arg-types types as a date"))
    (is (= [{:kind :uncast-string :column :ip :value "10.0.0.1" :type "inet"}] (check {:select [:id] :from [:hosts] :where [:= :ip "10.0.0.1"]} {})))
    (is (= [{:kind :uncast-string :column :flags :value "1010" :type "varbit"}] (check {:update :hosts :set {:flags "1010"}} {}))
        "bit takes varbit, which keeps the length")
    (is (= [{:kind :uncast-string :column :doc :value "{}" :type "jsonb"}] (check {:update :hosts :set {:doc "{}"}} {})))
    (is (= [{:kind :uncast-string :column :flags :arg :f :type "varbit"}] (check {:select [:id] :from [:hosts] :where [:= :flags :?f]} {})))
    (is (= [] (check {:select [:id] :from [:hosts] :where [:= :ip :?ip]} {})) "an argument arg-types leaves :any may be an object of the type")
    (is (= [] (check {:update :hosts :set {:mask :?m} :where [:= :id 1]} {}))
        "an argument assigned to a nullable column may be nil, which PostgreSQL takes")
    (is (= [] (check {:select [:id] :from [:hosts] :where [:and [:= :ip [:cast "10.0.0.1" :inet]] [:= :note "a"]]} {})))
    (is (= [] (check {:select [:id] :from [:hosts] :where [:= :ip "10.0.0.1"]} {:format {:inline true}})))))

(deftest truncate-names-tables
  (is (= [] (h/check registry {:truncate [:users :restart :identity]} opts)))
  (is (= [{:kind :unknown-table :table "sample.nosuch"}] (h/check registry {:truncate [[:users :nosuch]]} opts))))

(deftest an-inlined-enum-literal-is-one-too
  (is (= [{:kind :enum-literal :column :mood :value "zz" :allowed ["happy" "sad"]}]
         (h/check registry {:select [:id] :from [:users] :where [:= :mood [:inline "zz"]]} opts))))

(deftest an-unknown-clause-of-the-query-is-unread
  (is (= [{:kind :unread-clause :clause :selct}] (h/check registry {:selct [:id]} opts))))

(deftest explain-says-what-to-do-for-arity-and-unread-clauses
  (is (= [":values row 0 has 2 values for 3 :columns; give each row as many values as :columns"
          "clause :frob is not read by pgmalli, so what it does is not checked; check the clause name, or check that part by hand"]
         (h/explain [{:kind :values-arity :table "sample.users" :row 0 :columns 3 :values 2} {:kind :unread-clause :clause :frob}]))))

(deftest a-quoted-sql-word-is-a-column
  (is (= [] (h/check registry {:select [:user :nick] :from [:users]} opts)))
  (is (= [{:kind :unknown-column :column :user}] (h/check registry {:select [:user :nick] :from [:users]} (assoc opts :format {:quoted true}))))
  (is (nil? (h/row-schema registry {:select [:user :nick] :from [:users]} (assoc opts :format {:quoted true})))))

(deftest a-string-select-alias-is-the-key
  (is (= [:users/nn] (map first (drop 1 (h/row-schema registry {:select [[:nick "nn"]] :from [:users]} (assoc opts :qualified? true)))))))

(deftest the-question-mark-operators-are-no-arguments
  (is (= [:id] (keys (h/arg-types registry {:select [:id] :from [:users] :where [:and [:? :nick "a"] [:?| :nick :nick] [:?& :nick :nick] [:= :id :?id]]} opts)))))

(deftest information-schema-is-opaque-as-pg-catalog
  (is (= [] (h/check registry {:select [:table_name] :from [:information_schema.tables]} opts))))

(deftest a-date-argument-takes-what-pgjdbc-sends
  (let [t (:b (h/arg-types registry {:update :users :set {:born :?b} :where [:= :id 1]} opts))]
    (is (m/validate t (java.time.LocalDate/parse "2020-01-01") malli-opts))
    (is (m/validate t (java.util.Date.) malli-opts))
    (is (not (m/validate t "2020-01-01" malli-opts)))))

(deftest a-domain-argument-takes-what-pgjdbc-takes-for-its-base
  (let [reg (pgmalli/registry {:registry {:pg.public/day [:time/local-date {:pg/type "date"}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:d [:ref {:pg/type "day"} :pg.public/day]]]}})
        t (:d (h/arg-types reg {:update :t :set {:d :?d}}))]
    (is (m/validate t (java.util.Date.) malli-opts))
    (is (m/validate t (java.time.LocalDate/parse "2020-01-01") malli-opts))))

(deftest a-string-assigned-to-a-regclass-column-is-taken
  (let [reg (pgmalli/registry {:registry {:pg.public/rc [:map {:pg/table "public.rc"} [:id :int] [:r [:any {:pg/type "regclass"}]]
                                                         [:rt [:any {:pg/type "regtype"}]]]}})]
    (is (= [] (h/check reg {:update :rc :set {:r "w"}})))
    (is (= [:uncast-string] (map :kind (h/check reg {:select [:id] :from [:rc] :where [:= :r "w"]}))))
    (is (= [:uncast-string] (map :kind (h/check reg {:update :rc :set {:rt "integer"}}))) "regclass alone takes text by assignment")))

(def ^:private check-list-registry
  ;; state is a text column a CHECK narrows to a list; mood an enum type
  (pgmalli/registry {:registry {:pg.public/mood [:enum "ok" "sad"]
                                :pg.public/t [:map {:pg/table "public.t"}
                                              [:id [:int {:pg/type "integer"}]]
                                              [:state [:enum {:pg/type "text" :pg/constraints ["t_state_check"]} "open" "closed"]]
                                              [:n [:enum {:pg/type "integer" :pg/constraints ["t_n_check"]} 1 2]]
                                              [:m [:ref {:pg/type "mood"} :pg.public/mood]]]}}))

(deftest a-check-list-narrows-writes-and-equality-not-other-comparisons
  (let [q #(h/check check-list-registry {:select [:id] :from [:t] :where %} {:write unspecified})]
    (doseq [w [[:= :state "archived"] [:in :state ["archived"]] [:is-not-distinct-from :state "archived"]
               [:= "archived" :state] [:not [:<> :state "archived"]] [:not [:not [:= :state "archived"]]]]]
      (is (= [:enum-literal] (map :kind (q w))) (pr-str w)))
    (doseq [w [[:<> :state "archived"] [:not= :state "archived"] [:not-in :state ["archived"]]
               [:< :state "z"] [:between :state "a" "z"] [:is-distinct-from :state "archived"]
               [:<> "archived" :state] [:not [:= :state "archived"]] [:not [:in :state ["archived"]]]
               [:not [:and [:= :id 1] [:= :state "archived"]]]]]
      (is (= [] (q w)) (str (pr-str w) ": PostgreSQL compares a text, and the query matches rows")))
    (is (= [:enum-literal] (map :kind (h/check check-list-registry {:update :t :set {:state "archived"}})))
        "a write the CHECK refuses")
    (doseq [w [[:<> :m "zz"] [:< :m "zz"] [:not-in :m ["zz"]]]]
      (is (= [:enum-literal] (map :kind (q w))) (str (pr-str w) ": an enum type refuses the value however compared")))))

(deftest an-argument-compared-with-a-check-list-takes-its-base-type-except-under-equality
  (let [args #(h/arg-types check-list-registry {:select [:id] :from [:t] :where %})]
    (is (= {:x [:enum {:pg/type "text" :pg/constraints ["t_state_check"]} "open" "closed"]} (args [:= :state :?x])))
    (is (= {:x [:string {:pg/type "text"}]} (args [:<> :state :?x])))
    (is (= {:x [:string {:pg/type "text"}]} (args [:< :state :?x])))
    (is (= {:x [:or [:sequential {:min 1} [:string {:pg/type "text"}]] [:set {:min 1} [:string {:pg/type "text"}]]]}
           (args [:not-in :state :?x])))
    (is (= {:x [:enum {:pg/type "mood"} "ok" "sad"]} (args [:<> :m :?x])) "an enum type keeps its labels")
    (is (= {:x [:or [:sequential {:min 1} [:enum {:pg/type "text" :pg/constraints ["t_state_check"]} "open" "closed"]]
                [:set {:min 1} [:enum {:pg/type "text" :pg/constraints ["t_state_check"]} "open" "closed"]]]}
           (args [:in :state :?x])))
    (is (= {:lo [:string {:pg/type "text"}] :hi [:string {:pg/type "text"}]} (args [:between :state :?lo :?hi])))
    (is (= {:x [:maybe [:string {:pg/type "text"}]]} (args [:is-distinct-from :state :?x])))
    (is (= {:x [:string {:pg/type "text"}]} (args [:not [:= :state :?x]])) "NOT = is <>")
    (is (= {:x [:int {:pg/type "integer"}]} (args [:<> :n :?x])) "an integer column's list, its base type")
    (is (= {:x [:enum {:pg/type "integer" :pg/constraints ["t_n_check"]} 1 2]} (args [:= :n :?x])))
    (is (= {:x [:enum {:pg/type "text" :pg/constraints ["t_state_check"]} "open" "closed"] :y [:string {:pg/type "text"}]}
           (h/arg-types check-list-registry {:update :t :set {:state :?x} :where [:<> :state :?y]}))
        "the write keeps the list, the comparison in the same statement does not")
    (is (= {:x [:or [:string {:pg/type "text"}] :keyword]}
           (h/arg-types check-list-registry {:select [:id] :from [:t] :where [:<> :state :?x]} (sends {} [:keyword :unknown])))
        "an extension that sends labels as keywords sends any keyword compared with the base type")))

(deftest null-in-any-case-is-sql-null
  (is (= [{:kind :not-null-violation :table "sample.users" :column :score :clause :set}]
         (h/check registry {:update :users :set {:score :NULL} :where [:= :id 1]} opts)))
  (is (= [] (h/check registry {:select [:id] :from [:users] :where [:is :nick :NULL]} opts)) "not a column named NULL"))

(deftest not-negates-through-and-or-only-and-each-occurrence-on-its-own
  (let [q #(map :kind (h/check check-list-registry {:select [:id] :from [:t] :where %} {:write unspecified}))
        c [:= :state "archived"]]
    (is (= [:enum-literal] (q [:not [:case [:= :state "archived"] true :else false]])) "a CASE's condition is not negated")
    (is (= [:enum-literal] (q [:not [:= :id [:case [:= :state "archived"] 1 :else 2]]])))
    (is (= [:enum-literal] (q [:or c [:not c]])) "the same vector, once plain and once negated")
    (is (= [] (q [:not [:or [:= :state "archived"] [:= :id 1]]])))))

(deftest an-sql-word-in-any-case-names-its-column-as-postgresql-does
  (is (= [:map [:current_date ['inst? {:pg/type "date"}]]] (h/row-schema registry {:select [:CURRENT_DATE] :from [:users]} opts)))
  (is (= [:map [:?column? [:maybe :any]]] (h/row-schema registry {:select [:NULL] :from [:users]} opts))))

(deftest a-check-lists-labels-go-as-keywords-assigned-too
  (let [s (h/update-schema check-list-registry :pg.public/t (sends {} [:keyword :unknown]))]
    (is (m/validate s {:state :open} {:registry check-list-registry}))
    (is (not (m/validate s {:state :archived} {:registry check-list-registry})))))

(def ^:private kinds-registry
  (pgmalli/registry {:registry {:pg.public/pos [:and [:int {:pg/type "bigint"}] [:>= 1]]
                                :pg.public/t [:map {:pg/table "public.t"}
                                              [:r [:string {:pg/type "regclass"}]] [:i [:string {:pg/type "inet"}]]
                                              [:re [:double {:pg/type "real"}]]
                                              [:p [:ref {:pg/type "pos"} :pg.public/pos]]
                                              [:dl [:enum {:pg/type "double precision" :pg/constraints ["c"]} 1.5 2.0]]
                                              [:j [:any {:pg/type "json"}]]]}}))

(deftest each-rule-of-what-an-argument-takes-holds
  (let [reg kinds-registry
        w #(into (h/pgjdbc-write) %&)
        ok? #(m/validate %1 %2 {:registry reg})
        uncast #(map :kind (h/check registry {:select [:id] :from [:users] :where [:= :mood "sad"]} {:schema "sample" :write %}))]
    (is (= [] (h/check reg {:update :t :set {:r :?x}})) "a regclass takes a varchar argument")
    (is (ok? (:r (h/arg-types reg {:update :t :set {:re :?r}})) 1) "a real takes an integer")
    (is (= [:uncast-string] (uncast [[:string "varchar"] [[:or {:title "k"} :keyword :boolean] :unknown]])) "an :or's properties are no alternative")
    (is (= [:uncast-string] (uncast [[:string "varchar"] ['keyword? :unknown]])) "keyword? sends no string")
    (is (ok? (:p (h/arg-types reg {:update :t :set {:p :?p}} {:accept :postgresql})) 0.6) "a fraction rounded to an integer domain's range")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key" (h/insert-schema registry :users {:schema "sample" :nope 1})))
    (is (= [:map [:s [:string {:pg/type "character varying"}]]] (h/row-schema registry {:select [["s" :s]] :from [:users]} {:schema "sample" :qualified? false :write []}))
        "a String goes as varchar where no row says")
    (is (= [:map [:s [:string {:pg/type "text"}]]] (h/row-schema registry {:select [["s" :s]] :from [:users]} {:schema "sample" :qualified? false :write (w [[:maybe :string] :unknown])}))
        "untyped where a row may send one so")
    (is (= [:map [:s [:string {:pg/type "character varying"}]]] (h/row-schema registry {:select [["s" :s]] :from [:users]} {:schema "sample" :qualified? false :write [[:string "varchar"] [:string "text"]]}))
        "the first string row's type")
    (is (= [:uncast-string] (map :kind (h/check reg {:update :t :set {:i :?x}} {:write (w [:keyword "inet"])}))) "an inet column with an :or of its own")
    (is (not-any? keyword? (drop 2 (:x (h/arg-types registry {:select [:id] :from [:users] :where [:= :mood :?x]} {:schema "sample" :write [['string? :unknown]]}))))
        "string? sends no keyword")
    (is (not (ok? (:d (h/arg-types reg {:update :t :set {:dl :?d}})) 1)) "1.5 is no integer label")
    (is (not (ok? (:x (h/arg-types registry {:update :users :set {:score :?x} :where [:= :id 1]} {:schema "sample" :accept :postgresql :write (w [:any :unknown])})) "x"))
        "an :any row adds nothing")
    (is (= {:x [:any {:pg/type "json"}]} (h/arg-types reg {:update :t :set {:j :?x}} {:accept :postgresql :write (w [:string :unknown])})))))

(deftest each-migration-line-gives-its-row
  (doseq [[w says] [[{"mood" :keyword} #"All told, :write is \(into \(h/pgjdbc-write\) \[\[:keyword :unknown\]\]\)"]
                    [{"text" [:or :string :keyword]} #"All told, :write is \(into \(h/pgjdbc-write\) \[\[:keyword :unknown\]\]\)"]
                    [{"mood" [:or :string :keyword]} #"All told, :write is \(into \(h/pgjdbc-write\) \[\[:keyword :unknown\]\]\)"]
                    [{:enum [:string]} #":enum \[:string\] needs no row; a column's labels stay its own. All told, :write is \(h/pgjdbc-write\) "]
                    [{:stringtype :unspecified} #"All told, :write is \(h/pgjdbc-write \{:stringtype :unspecified\}\) "]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo says (h/arg-types registry {:select [:id] :from [:users]} {:schema "sample" :write w})) (pr-str w)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"an array goes as a collection of its elements"
                        (h/arg-types registry {:select [:id] :from [:users]} {:write [[[:vector :int] "_int8"]]}))))

(deftest pgmallis-own-integer-type-sends-no-string
  (is (= [:uncast-string] (map :kind (h/check registry {:select [:id] :from [:users] :where [:= :id "1"]}
                                              {:schema "sample" :write [[:pg/integer "integer"] [:string "varchar"]]})))))

(deftest an-enum-domains-checks-stay-around-its-labels
  (let [reg (pgmalli/registry {:registry {:pg.public/mood [:enum "ok" "sad"]
                                          :pg.public/good [:and [:ref {:pg/type "mood"} :pg.public/mood] [:not= "ok"]]
                                          :pg.public/t [:map {:pg/table "public.t"} [:g [:ref {:pg/type "good"} :pg.public/good]]]}})
        t (:g (h/arg-types reg {:update :t :set {:g :?g}} {:write (conj (h/pgjdbc-write) [:keyword :unknown])}))]
    (is (not (m/validate t "ok" {:registry reg})))))

(deftest each-rule-of-a-row-written-holds
  (is (m/validate (h/insert-schema registry :pg.sample/users {:schema "sample" :kebab? true})
                  {:group-id 1 :score 0 :nick-upper :default} {:registry registry})
      "a column the database writes keyed as the others")
  (let [reg (pgmalli/registry {:registry {:pg.public/short [:string {:pg/type "character varying(10)" :max 5 :pg/max-length 10}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:c [:ref {:pg/type "short"} :pg.public/short]]]}})]
    (is (not (m/validate (:x (h/arg-types reg {:select [[[:cast :?x :short] :c]]})) "abcdef" {:registry reg})) "a cast keeps a CHECK's :max"))
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:i [:string {:pg/type "inet"}]]]}})]
    (is (= [] (h/check reg {:update :t :set {:i :?x}} {:write [[[:fn string?] "inet"]]})) "a row that may send a string as inet"))
  (is (= [:map [:a [:string {:pg/type "text"}]]] (h/row-schema registry {:select [["x" :a]]} {:schema "sample" :qualified? false :write [['string? "text"]]})))
  (is (= {:x [:or [:int {:pg/type "integer"}] :symbol]}
         (h/arg-types registry {:select [:id] :from [:users] :where [:= :group_id :?x]} {:schema "sample" :write (conj (h/pgjdbc-write) [:symbol "smallint"])}))
      "an integer column takes a smallint")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no schema named" (h/insert-schema registry 'users {:schema "sample"}))))

(deftest a-stringtype-beside-rows-changes-only-what-it-says
  (let [msg #(try (h/arg-types registry {:select [:id] :from [:users]} %) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (re-find #"\"timestamptz\" inst\? is a row \[inst\? \"timestamptz\"\]" (ex-message (msg {:write {"timestamptz" inst?}}))) "a predicate as its symbol")
    (is (re-find #":stringtype :varchar is pgjdbc's default; leave it out\. All told, :write is \(h/pgjdbc-write\) " (ex-message (msg {:stringtype :varchar}))))
    (is (re-find #":stringtype \"unspecified\" was never an option; pgjdbc's is :varchar or :unspecified\. All told, :write is \(h/pgjdbc-write\) "
                 (ex-message (msg {:stringtype "unspecified"}))))
    (is (re-find #"All told, :write is \[\[:keyword :unknown\]\] " (ex-message (msg {:write [[:keyword :unknown]] :stringtype :varchar}))))
    (is (re-find #"All told, :write is \[\[:string \"text\"\]\] " (ex-message (msg {:write [[:string "text"]] :stringtype :unspecified}))))
    (is (re-find #":stringtype :unspecified sends :write's varchar string rows untyped \(:unknown\)" (ex-message (msg {:write [[:string "text"]] :stringtype :unspecified}))))
    (is (= {:stringtype :unspecified} (:given (ex-data (msg {:write [[:string "text"]] :stringtype :unspecified})))))
    (is (re-find #"All told, :write is \(h/pgjdbc-write\) " (ex-message (msg {:write {"mood" :string}}))))))

(deftest a-row-schema-keys-as-each-format-writes-it-back
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:address_line1 :string]]}})
        keys #(map first (drop 2 (h/insert-schema reg :t (assoc % :kebab? true))))]
    (is (= [:address-line1] (keys {:format {:quoted true :quoted-snake true}})) ":quoted-snake writes - back as _")
    (is (= [:address_line1] (keys {:format {:dialect :ansi}})) "a dialect quotes names"))
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:MixedCase :int]]}})
        keys #(map first (drop 2 (h/insert-schema reg :t %)))]
    (is (= ["MixedCase"] (keys {})) "unquoted, only a string reaches a column with capitals")
    (is (= [:MixedCase] (keys {:format {:quoted true}})))))

(deftest each-migration-line-says-what-it-is
  (let [msg #(try (h/arg-types registry {:select [:id] :from [:users]} %) nil (catch clojure.lang.ExceptionInfo e (ex-message e)))]
    (is (re-find #"; a keyword is a row \[:keyword :unknown\] where a keyword goes untyped" (msg {:write {"mood" [:or :string :keyword]}})))
    (is (re-find #":stringtype :varchar is pgjdbc's default; leave it out\. All told, :write is \[\[:keyword :unknown\]\] " (msg {:write [[:keyword :unknown]] :stringtype :varchar})))
    (is (re-find #"All told, :write is \(h/pgjdbc-write \{:stringtype :unspecified\}\) " (msg {:write (h/pgjdbc-write) :stringtype :unspecified})))
    (is (re-find #"All told, :write is \[\[:int \"bigint\"\]" (msg {:write (h/pgjdbc-write) :stringtype nil})))))
