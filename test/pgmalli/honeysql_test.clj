(ns pgmalli.honeysql-test
  "HoneySQL query data against the checked-in sample registry."
  (:refer-clojure :exclude [bigint])
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [honey.sql.pg-ops]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [malli.experimental.time :as time]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.sample :refer [registry]]))

(def opts {:schema "sample"})

(defn- written-keys
  "The keys of a values-schema or set-schema map, of every column."
  [s]
  (map first (drop 2 (if (= :map (first s)) s (some #(when (and (vector? %) (= :map (first %))) %) s)))))

(def malli-opts {:registry (merge (m/default-schemas) (time/schemas))})

(defn- check ([q] (check q opts)) ([q o] (h/check registry q o)))
(defn- args ([q] (args q opts)) ([q o] (h/arg-types registry q o)))
(defn- row ([q] (row q opts)) ([q o] (h/row-schema registry q o)))

(def bigint [:int {:pg/type "bigint"}])
(def integer [:int {:pg/type "integer"}])
(def int4 [:int {:pg/type "integer" :min -2147483648 :max 2147483647}])
(def text [:string {:pg/type "text"}])
(def mood [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} "happy" "sad"])
;; an argument's: a keyword label goes untyped, a String as varchar, which an enum refuses
(def mood-arg [:enum {:pg/type "mood" :default "happy" :pg/default "happy"} :happy :sad])
(def pg-object jdbc/pg-object)
(defn in-list [s] [:or [:sequential {:min 1} s] [:set {:min 1} s]])

(deftest queries-that-agree-have-no-problems
  (is (= [] (check {:select [:id :nick] :from [:users] :where [:= :group_id 'g]})))
  (is (= [] (check {:select [:u.id [:g.name :group]] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]]})))
  (is (= [{:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check {:insert-into :users :values [{:group_id 1 :mood "sad" :score 1 :closed_at 'now}] :returning [:id]})))
  (is (= [] (check {:with [[:recent {:select [:id] :from [:users]}]] :select [:id] :from [:recent]})) "a CTE is an opaque table")
  (is (= [] (check '(cond-> {:select [:id] :from [:tree]} deep? (assoc :with-recursive [[:tree {:union-all [{:select [:id] :from [:users]} {:select [:id] :from [:tree]}]}]]))))
      "a CTE built up in code is one too")
  (is (= [] (check {:insert-into [[:users [:group_id :mood :score]] {:select [:group_id :mood :score] :from [:users]}]}))
      "INSERT INTO table (columns) SELECT")
  (is (= [] (check '{:insert-into :users :values rows})) "rows passed in say nothing about the columns")
  (is (= {'rows [:sequential {:min 1} (h/values-schema registry :users opts)]} (args '{:insert-into :users :values rows}))))

(deftest a-list-of-plain-items-is-read-as-honeysql-reads-a-vector
  (is (= [{:kind :unknown-column :column :nope}] (check {:select '(:id :nope) :from [:users]})))
  (is (= [{:kind :unknown-column :column :nope}] (check {:select [:id] :from '(:users) :where [:= :nope 1]})))
  (is (= [{:kind :unknown-column :column :nope}] (check {:select [:id] :from [:users] :where '(:= :nope 1)})))
  (is (= (row {:select [[:id :x]] :from [:users]}) (row {:select (list [:id :x]) :from (list :users)})))
  (is (= (row {:select [:id] :from [:users]}) (row {:select (map keyword ["id"]) :from [:users]})) "a seq built at run time")
  (is (= {} (args '{:select [:id] :from [:users] :where (:= :id ids)})) "a list holding a symbol is code"))

(deftest problems-name-what-disagrees
  (is (= [{:kind :unknown-table :table "sample.nope"}] (check {:select [:*] :from [:nope]})))
  (is (= [{:kind :unknown-column :column :nick_name}] (check {:select [:nick_name] :from [:users]})))
  (is (= [{:kind :unknown-column :column :nope}] (check {:select [:id] :from [:users] :where [:= :nope 1]})) "compared columns too")
  (is (= [{:kind :unknown-column :column :nope}] (check {:update :users :set {:nope 1} :where [:= :id 1]})) "and set ones")
  (is (= [{:kind :missing-required-column :table "sample.users" :column :score}
          {:kind :uncast-string :column :mood :value "sad" :type "mood"}]
         (check {:insert-into :users :values [{:group_id 1 :mood "sad"}]}))
      "an INSERT must carry the columns the insert schema requires")
  (is (= [{:kind :enum-literal :column :mood :value "angry" :allowed ["happy" "sad"]}]
         (check {:select [:id] :from [:users] :where [:= :mood [:cast "angry" :mood]]})))
  (is (= [{:kind :enum-literal :column :mood :value "angry" :allowed ["happy" "sad"]}]
         (check {:update :users :set {:mood "angry"} :where [:= :id 1]})) "assigned literals too")
  (is (= [{:kind :not-null-violation :table "sample.users" :column :score :clause :values}
          {:kind :uncast-string :column :mood :value "sad" :type "mood"}
          {:kind :uncast-string :column :mood :value "sad" :type "mood"}]
         (check {:insert-into :users :values [{:group_id 1 :mood "sad"} {:group_id 1 :mood "sad" :score 1}]}))
      "a multi-row INSERT carries the union of its columns, NULL where a row lacks one")
  (is (= [{:kind :ambiguous-column :column :id :candidates ["sample.users" "sample.groups"]}]
         (check {:select [:id] :from [:users] :join [:groups [:= :groups.id :users.group_id]]})))
  (is (= [{:kind :unknown-column :column :nope}] (check {:with [[:users {:select [:id] :from [:groups]}]] :select [:nope] :from [:sample.users]}))
      "a CTE shadows a table only when the reference is written without a schema")
  (is (= [{:kind :uncast-string :column :mood :value "happy" :type "mood"}
          {:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check {:select [:id] :from [:users] :where [:in :mood ["happy" "sad"]]}))))

(deftest named-parameters-are-arguments-under-their-params-key
  (let [body {:select [:id] :from [:users] :where [:and [:= :id :?id] [:in :mood :?moods]] :limit :?n}]
    (is (= [] (check body)))
    (is (= [] (check {:insert-into [[:users [:group_id :mood :score]] {:select [[:?g] [:?m :mood] [[:unnest :?s]]]}]}))
        "selected, as INSERT ... SELECT passes values")
    (is (= {:id bigint
            :moods (in-list mood-arg)
            :n [:maybe [:int {:pg/type "bigint" :min 0}]]}
           (args body)))
    (is (= {:g/id [:maybe
                   [:or
                    [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]
                    :keyword]]}
           (args {:update :users :set {:nick :?g/id} :where [:= :id 1]}))
        "a qualified one keeps its namespace, as sql/format's :params does")
    (is (= (args body)
           (args '{:select [:id] :from [:users] :where [:and [:= :id [:param :id]] [:in :mood ?moods]] :limit ?n}))
        "[:param :id] and ?id are the same parameters")
    (let [s (h/query-schema registry [:id :n] body opts)]
      (is (= [:cat bigint [:maybe [:int {:pg/type "bigint" :min 0}]]] (nth s 2)))
      (is (= {:pgmalli/problems [{:kind :missing-arg :arg :moods}]} (second s)) "the one left out of args"))))

(deftest every-table-has-the-system-columns
  (is (= [] (check {:delete-from :users :where [:in :ctid {:select [:ctid] :from [:users] :where [:= :xmin 1]}]})))
  (is (= {:t [:int {:pg/type "oid"}]} (args {:select [:id] :from [:users] :where [:= :tableoid :?t]}))))

(deftest an-unqualified-pg-name-is-pg-catalogs-first
  (is (= [] (check {:select [:pg_class.relname] :from [:pg_class] :join [:pg_roles [:= :pg_class.relowner :pg_roles.oid]]}))
      "opaque when the registry does not hold pg_catalog")
  (is (= [] (check {:select [:relname] :from [:pg_catalog.pg_class]})))
  (is (= [{:kind :unknown-table :table "sample.nopg_class"}] (check {:select [:x] :from [:nopg_class]}))))

(deftest a-bare-name-in-order-by-is-an-output-column-first
  (let [body {:select [:u.id [:g.name :gname]] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]]}]
    (is (= [] (check (assoc body :order-by [:id [:gname :desc]]))))
    (is (= [{:kind :ambiguous-column :column :id :candidates ["sample.users" "sample.groups"]}]
           (check (assoc body :select [:u.nick] :order-by [:id])))
        "a name no output column has is an input column"))
  (is (= [] (check {:select [[:nick :n] [[:count :*] :c]] :from [:users] :group-by [:n]}))
      "in :group-by only when no input column has it"))

(deftest a-symbol-where-a-table-goes-is-a-table-chosen-at-run-time
  (is (= [] (check '{:select [:id] :from [table] :where [:= :group_id :?g] :for :update})))
  (is (= [] (check '{:select [:t.id] :from [[table :t]]})))
  (is (= [] (check '{:update table :set {:x :?x} :where [:= :id 1]})))
  (is (= [] (check '{:delete-from table :where [:= :id 1]})))
  (is (= [] (check '{:insert-into table :values [{:x 1}]})))
  (is (= [] (check '{:insert-into [table [:x]] :values [[1]]}))))

(deftest code-in-a-query-is-not-read-as-columns
  (is (= [] (check '{:with [(recent-cte) [:x {:select [:id] :from [:users]}]] :select [:id] :from [:recent]}))
      "a :with entry built by code may define any table")
  (is (= [] (check '{:select [:id] :from [:users] :order-by (case sort :nick [[:nick :asc]] [[:id :desc]])})))
  (is (= [] (check '{:select [:id] :from [:users] :group-by (if x [:nick] [:id])})))
  (is (= [] (check '{:insert-into [[:users [:group_id (case k :a :mood :score)]] {:select [[:?g] [:?v]]}]}))
      "a column built by code may be any one")
  (is (= [] (check '{:update :users :set {(col-of k) 1} :where [:= :id 1]}))))

(deftest a-table-function-without-an-alias-is-named-after-it
  (is (= [] (check {:select [:*] :from [[[:public.claim_token :?user-id :?token]]]})))
  (is (= [] (check {:select [:claim_token.x] :from [[[:claim_token 1]]]}))))

(deftest a-set-operations-order-by-names-its-first-members-output
  (let [body {:union-all [{:select [[:id :uid] [0 :bucket]] :from [:users]} {:select [:id 1] :from [:groups]}]}]
    (is (= [] (check (assoc body :order-by [:bucket [:uid :desc]]))))
    (is (= [{:kind :unknown-column :column :nick}] (check (assoc body :order-by [:nick])))
        "the tables' columns are not in scope there")
    (is (= [] (check '{:union [(base) {:select [:id] :from [:users]}] :order-by [:x]})))))

(deftest check-never-throws-on-code
  (doseq [body '[{:insert-into [[:users [(f) "s" 1 nil]] {:select [(g)]}]}
                 {:insert-into [:users [(f)]] :values [[1]]}
                 {:select [(f) "s" 1] :from [(t) "u"] :where [:= (c) "x"] :order-by [(o)] :group-by [(g)]}
                 {:update (t) :set {"a" 1 (k) 2} :returning [(r)]}
                 {:select [:id] :from [:users] :join [(j) [:= (a) :id]] :with [(w)] :limit (n)}]]
    (is (vector? (check body)) (pr-str body))
    (is (map? (args body)) (pr-str body))
    (is (vector? (h/query-schema registry '[] body opts)) (pr-str body))))

(deftest an-expression-with-no-alias-has-the-name-postgresql-gives-it
  (is (= [:map [:count bigint] [:?column? [:maybe bigint]] [:my_fn [:maybe :any]] [:nick [:maybe text]]
          [:case [:maybe bigint]] [:exists [:boolean {:pg/type "boolean"}]] [:current_timestamp [:time/instant {:pg/type "timestamp with time zone"}]]
          [:coalesce bigint]]
         (row {:select [[[:count :*]] [[:+ :id 1]] [[:public.my-fn 1]] [[:cast :nick :text]] [[:case [:= :id 1] 2]]
                        [[:exists {:select [1]}]] :current_timestamp [[:coalesce :id 0]]]
               :from [:users]}))
      "?column? once: the driver keeps the last of the columns a name repeats")
  (is (nil? (row {:select [[[:cast 1 :int]]] :from [:users]})) "a cast value is named after its type, not read here")
  (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]] [:top_id [:maybe bigint]]]
         (row {:select [:id [[:max :id] :top_id]] :from [:users]} opts))
      "a key's _ read as -")
  (is (= [:map [:name [:string {:pg/type "text"}]]] (row {:select [[[:cast :name :text]]] :from [:groups]} opts)))
  (is (= [:map [:n [:string {:pg/type "text"}]]] (row {:select [[[:cast :name :text] :n]] :from [:groups]} opts)))
  (is (= [:map [:nick [:maybe text]]] (row {:select [[[:cast :nick :text]]] :from [:users]} opts))
      "a varchar cast to text is an expression of no table"))

(deftest a-set-operations-row-is-named-by-its-first-member
  (let [body {:union-all [{:select [:id [:nick :name]] :from [:users]} {:select [:id [:nick :n]] :from [:users]}]}]
    (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]] [:name [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]]]
           (row body opts)))
    (is (= [:map [:id [:maybe [:int {:pg/identity :always :pg/type "bigint"}]]]] (row {:union [{:select [:id] :from [:users]} {:select [[nil :id]]}]}))
        "of the members' type, NULL left out, nullable where one is")
    (is (nil? (row {:union [{:select [:id] :from [:users]} '(other)]})))))

(deftest an-in-argument-is-what-sql-format-expands
  (let [t (:ids (args {:select [:id] :from [:users] :where [:in :id :?ids]}))]
    (doseq [v [[1 2] '(1) #{1 2} (map inc [0 1])]] (is (m/validate t v) (pr-str v)))
    (doseq [v [[] #{} 1 nil (long-array [1])]] (is (not (m/validate t v)) (pr-str v)) "IN (), or not expanded")))

(deftest write-names-what-an-extension-sends
  (let [o opts
        n [:or [:string {:pg/type "character varying"}] :keyword]]
    (is (= {:n n :ns (in-list n)
            :v [:maybe [:or [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}] :keyword]]}
           (args {:update :users :set {:nick :?v} :where [:and [:= :nick :?n] [:in :nick :?ns]]} o)))
    (is (= {:id bigint} (args {:select [:id] :from [:users] :where [:= :id :?id]} o))
        "a keyword sent untyped is no bigint's own value")))

(deftest an-outer-join-may-leave-a-tables-columns-null
  (let [id [:int {:pg/identity :always :pg/type "bigint"}]
        row #(h/row-schema registry (merge {:select [[:u.id :uid] [:g.id :gid]] :from [[:users :u]]} %) opts)]
    (is (= [:map [:uid id] [:gid [:maybe int4]]] (row {:left-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:uid [:maybe id]] [:gid int4]] (row {:right-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:uid [:maybe id]] [:gid [:maybe int4]]] (row {:full-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:uid id] [:gid int4]] (row {:join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:uid id] [:gid [:maybe int4]]]
           (row {:left-join [[:users :x] [:= :x.id :u.id] [:groups :g] [:= :g.id :u.group_id]]}))
        "every pair of a :left-join")
    (is (= [:maybe int4] (second (nth (h/row-schema registry {:select [:g/*] :from [:users] :left-join [[:groups :g] [:= :g.id :users.group_id]]} opts) 1)))
        "a table's * too")
    (is (= [:map [:id [:maybe int4]]] (row {:select [:u.id :g.id] :left-join [[:groups :g] [:= :g.id :u.group_id]]}))
        "a name selected twice is one key, the last column's, as the builder keeps it")))

(deftest an-argument-only-code-uses-may-be-nil
  (is (= {:g integer :from [:maybe bigint]}
         (args '{:select [:id] :from [:users] :where [:and [:= :group_id :?g] (when from [:>= :id :?from])]})))
  (is (= {:g integer}
         (args '{:select [:id] :from [:users] :where [:and [:= :group_id :?g] (when x [:= :group_id :?g])]}))
      "one use in the data is enough"))

(def docs
  (pgmalli/registry {:registry {:pg.public/docs [:map {:pg/table "public.docs"}
                                                      [:id [:int {:pg/type "bigint"}]]
                                                      [:paths [:maybe [:vector {:pg/type "text[]"} [:string {:pg/type "text"}]]]]
                                                      [:meta [:map {:pg/type "jsonb"} [:a :int]]]]}}))

(deftest min-max-and-nullif-of-a-reg-array-are-not-oid
  (let [reg (pgmalli/registry {:registry {:pg.public/rels [:map {:pg/table "public.rels"}
                                                           [:rs [:vector {:pg/type "regclass[]"} [:any {:pg/type "regclass"}]]]]}})
        row #(h/row-schema reg {:select % :from [:rels]} {})]
    (is (not (str/includes? (pr-str (row [[[:min :rs] :m] [[:max :rs] :x] [[:nullif :rs :rs] :n]])) "oid")))))

(deftest an-array-is-read-as-a-vector-and-json-parsed
  (is (= [:map [:paths [:maybe [:vector {:pg/type "text[]"} [:maybe text]]]] [:meta [:map {:pg/type "jsonb"} [:a :int]]]]
         (h/row-schema docs {:select [:paths :meta] :from [:docs]} {}))))

(deftest an-array-through-coalesce-min-max-case-or-greatest-is-still-a-vector
  (doseq [e [[:coalesce :paths :paths] [:max :paths] [:min :paths] [:case [:= :id 1] :paths :else :paths] [:greatest :paths :paths]]]
    (is (= [:map [:x [:maybe [:vector {:pg/type "text[]"} [:maybe text]]]]] (h/row-schema docs {:select [[e :x]] :from [:docs]} {})) (pr-str e))))

(deftest an-array-columns-argument-is-an-array
  (let [body {:insert-into :docs :values [{:id 1 :paths :?paths :meta :?meta}]}]
    (is (= {:paths [:maybe [:pg/array text]] :meta [:or [:map {:pg/type "jsonb"} [:a :int]] pg-object]} (select-keys (h/arg-types docs body) [:paths :meta]))
        "an assigned array is a Java array: a vector goes as jsonb")
    (is (= {:p [:pg/array text]} (h/arg-types docs {:select [:id] :from [:docs] :where [:= :paths :?p]}))
        "compared too")))

(deftest an-any-or-all-argument-is-an-array
  (let [body {:select [:id] :from [:users] :where [:and [:= :id [:any :?ids]] [:<> :group_id [:all :?gs]]]}]
    (is (= {:ids [:pg/array bigint] :gs [:pg/array integer]} (args body))
        "the Java arrays pgjdbc sends as an array of the column's type: a long[] as bigint[], an int[] as integer[]")
    (let [t (:ids (args body))]
      (doseq [v [(long-array [1 2]) (into-array Long [1]) (long-array [])]] (is (m/validate t v {:registry registry}) (pr-str v)))
      (doseq [v [(object-array [1]) (into-array String ["a"]) [1] '(1) #{1} nil]] (is (not (m/validate t v {:registry registry})) (pr-str v)))
      (is (= [:pg/array bigint] t)
          ":pg/array is what the driver takes (Object[] it refuses); a vector goes as jsonb, no array"))))

(deftest types-of-parameters-and-rows
  (let [body {:select [:id :nick :closed_at [:group_id :g]] :from [:users] :where [:and [:= :id 'id] [:in :mood 'moods] [:> :score 'min]] :limit 'n}]
    (is (= {'id bigint
            'moods (in-list mood-arg)
            'min integer
            'n [:maybe [:int {:pg/type "bigint" :min 0}]]}
           (args body)))
    (let [row (h/row-schema registry body opts)]
      (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]
              [:nick [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]]
              [:closed_at [:maybe [:time/instant {:pg/type "timestamptz"}]]]
              [:g int4]]
             row))
      (is (m/validate row {:id 1 :nick nil :closed_at nil :g 2} malli-opts) "as malli's default registry reads it"))
    (is (= (row {:select [:users/*] :from [:users]}) (row {:select [:*] :from [:users]})))
    (is (= [:map [:depth [:maybe int4]]]
           (row {:with-recursive [[:t {:union-all [{:select [[[:inline 0] :depth]]} {:select [[[:+ :t/depth [:inline 1]] :depth]] :from [:t]}]}]]
                 :select [:t/depth] :from [:t]} opts))
        "an expression's column, or a set operation's (a recursive CTE's), comes from no table")
    (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]] (row '{:select [:id] :from [[{:select [:id] :from [:users]} :sub]]})))
    (is (= [:map [:group_id int4]] (row '{:select [:group_id] :from [:users]} opts)))
    (is (= {'x bigint} (args '{:select [:id] :from [:users] :where [:and [:= :nope x] [:= :id x]]}))
        "an untyped use never hides a typed one")
    (is (= [:=> [:cat bigint :any] [:sequential {:max 1 :min 1} [:map [:n bigint]]]]
           (h/query-schema registry '[id extra] '{:select [[[:count :*] :n]] :from [:users] :where [:= :id id]} opts))
        "a symbol used nowhere is :any")
    (is (= [:sequential {:max 1} [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]]]
           (last (h/query-schema registry '[id] '{:select [:id] :from [:users] :where [:= :id id]} opts))))))

(deftest scope-follows-the-statement
  (is (= [] (check {:delete-from :users :where [:in :group_id {:select [:id] :from [:groups] :where [:= :name 'n]}]}))
      "a nested statement's columns are judged in its own scope, not the outer one")
  (is (= [{:kind :unknown-column :column :nope :in [:where 2]}]
         (check {:select [:id] :from [:users] :where [:in :group_id {:select [:id] :from [:groups] :where [:= :nope 1]}]})))
  (is (= [] (check {:delete-from [:users :u] :using [[:groups :g]] :where [:= :g.id :u.group_id]})) ":using")
  (is (= [] (check {:update :users :set {:score 1} :from [[:groups :g]] :where [:= :g.id :group_id]})) "UPDATE ... FROM")
  (is (= [] (check {:select [:u.id :g.name] :from [[:users :u]] :cross-join [[:groups :g]]})) ":cross-join")
  (is (= [] (check {:select [:u.id :g.name] :from [[:users :u]] :outer-join [[:groups :g] [:= :g.id :u.group_id]]})) ":outer-join")
  (is (= [{:kind :unknown-column :column :u.nope}] (check {:select [:u.nope] :from [[:users :u]]}))
      "a qualified column must exist in its table")
  (is (= [{:kind :unknown-column :column :nope}]
         (check {:insert-into [[:users [:group_id :mood :score :nope]] {:select [:group_id :mood :score 1] :from [:users]}]}))
      "the column list of INSERT ... SELECT")
  (is (= [{:kind :unknown-column :column :nope}
          {:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check {:insert-into :users :values [{:group_id 1 :mood "sad" :score 1 :nope 1}]}))
      "an unknown :values column is reported once")
  (is (= [] (check '{:insert-into tbl :values rows})) "a target passed in says nothing")
  (let [with-interval (assoc registry :pg.sample/spans [:map {:pg/table "sample.spans"} [:id :int] [:span [:time/duration {:pg/type "interval"}]]])]
    (is (= [:map [:span [:any {:pg/type "interval"}]]] (h/row-schema with-interval {:select [:span] :from [:spans]} opts)) "an interval is a driver object"))
  (is (= {'ids bigint}
         (args '{:select [:id] :from [:users] :where [:and [:in :nope ids] [:= :id ids]]}))
      "an :in on an unknown column gives no type either"))

(deftest nested-statements-see-the-enclosing-ones
  (is (= [] (check {:select [:id] :from [:users] :where [:not-exists {:select [1] :from [:groups] :where [:= :groups.id :users.group_id]}]}))
      "a correlated subquery resolves the outer table")
  (is (= [] (check {:select [:id] :from [:users] :where [:not-exists {:select [1] :from [:groups] :where [:= :id :users.group_id]}]}))
      "inner tables come first: :id is groups.id, not ambiguous with users.id")
  (is (= [] (check {:update [:users :u] :set {:score [:+ :r.score 1]} :from [[{:select [:score] :from [:users]} :r]] :where [:= :u.id 1]}))
      "a :set key belongs to the updated table only, whatever else is in scope")
  (is (= [{:kind :unknown-column :column :nick}]
         (check {:update :groups :set {:nick "x"} :from [[:users :u]] :where [:= :u.group_id :groups.id]})))
  (is (= [] (check {:select [:users/* [[:count :g.id] :n]] :from [:users] :left-join [[:groups :g] [:= :g.id :users.group_id]] :group-by [:users/id]})))
  (is (= [:map [:id int4] [:name text]]
         (row {:select [:groups/*] :from [:groups]})) ":t/* is the table's columns")
  (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]] (row {:select [:*] :from [[{:select [:id] :from [:users]} :r]]}))
      "a subquery's * is the columns its SELECT gives")
  (is (nil? (row {:select [:*] :from [[[:public.my-fn 1 3] :r]]})) "an opaque table's * is unknown")
    (is (= [:map [:r [:maybe bigint]]] (row {:select [:*] :from [[[:generate_series 1 3] :r]]})) "a function's of pg_catalog is its columns")
  (is (= [] (check {:select [:cp.id] :from [[:users :cp]] :join [[:groups :c] [:= :c.id :cp.group_id]]
                    :left-join [[{:select-distinct-on [[:group_id] :group_id :score] :from [:users] :where [:= :group_id 'g] :order-by [[:group_id :asc] [:id :desc]]} :latest]
                                [:= :latest.group_id :cp.group_id]]}))
      "a :select-distinct-on subquery is a statement of its own")
  (is (= [{:kind :unknown-column :column :nope}] (check {:select-distinct-on [[:group_id] :group_id :nope] :from [:users]}))))

(def ^:private shapes
  "[query expected-problems note]: the shapes of HoneySQL data the checker must read."
  '[[{:insert-into [{:overriding-value :system} :users] :values [{:group_id 1 :mood [:cast "sad" :mood] :score 1 :nope 1}]}
     [{:kind :unknown-column :column :nope}]
     "the option map inserts itself emits"]
    [{:insert-into :users :columns [:group_id :mood :score] :values [[1 [:cast "sad" :mood] 2]]}
     []
     ":columns with positional rows"]
    [{:insert-into :users :columns [:group_id :mood :nope] :values [[1 [:cast "sad" :mood] 2]]}
     [{:kind :unknown-column :column :nope} {:kind :missing-required-column :table "sample.users" :column :score}]
     "an unknown column in :columns leaves the column it stands in for missing"]
    [{:insert-into :users :columns [:group_id :mood :score] :values [[1 [:cast "sad" :mood]]]}
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
      (is (= expected (check query)))))
  (testing "the types the same shapes give"
    (is (= {'m mood-arg}
           (args '{:insert-into :users :columns [:group_id :mood :score] :values [[1 m 2]]})))
    (is (= {'gid integer}
           (args '{:select [:id] :from [:users] :where [:= gid :group_id]}))
        "a symbol on the left takes the column's type")))

(deftest a-subquery-added-to-a-query-built-elsewhere
  (is (= [] (check '(update query :select conj [[:exists {:select [1] :from [:groups] :where [:= :groups.id :skills/group_id]}] :flag])))
      "the enclosing statement is not in the data: its columns cannot be judged")
  (is (= [{:kind :unknown-column :column :groups.nope :in [4 0 1]}]
         (check '(update query :select conj [[:exists {:select [1] :from [:groups] :where [:= :groups.nope :skills/group_id]}] :flag])))
      "its own columns still are")
  (is (= [{:kind :unknown-column :column :nope :in [1]}] (check '(cond-> {:select [:nope] :from [:users]} deep? (assoc :limit 1))))
      "a statement built in code at the top is still the statement"))

(deftest a-cte-body-bound-apart-from-its-with
  (is (= [] (check '(let [paged (cond-> {:select [:id] :from [[:f :user_skills]]} (:limit page) (assoc :limit (:limit page)))]
                       {:with [[:f filtered-query :materialized] [:p paged]]
                        :select [:total/n]
                        :from [[{:select [[[:count :*] :n]] :from [:f]} :total]]})))
      "a CTE body bound in a let sees its sibling CTEs")
  (is (= [:map [:id [:maybe :any]]]
         (h/row-schema registry '{:with [[:f (helper group-id) :materialized] [:p paged]] :select [:id] :from [:p]} opts))
      "a body that is code, after another CTE, is a table whose columns are not known"))

(deftest one-row-results
  (is (= [:=> [:cat bigint] [:maybe [:map [:nick [:maybe [:string {:pg/type "character varying" :max 40 :pg/max-length 40}]]]]]]
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
      (is (= counted (row q)) (pr-str q))
      (is (= counted (row q {:schema "sample"})) "whatever the builder: next.jdbc builds this map itself")
      (is (= [:=> [:cat] counted] (h/query-schema registry [] q (assoc opts :result :one))) "execute-one! returns it, never nil"))
    (is (= [:map [:id int4]]
           (row {:delete-from :groups :returning [:id]})))
    (is (= [:map [:id int4] [:name text]]
           (row {:delete-from :groups} (assoc-in opts [:jdbc :return-keys] true)))
        "pgjdbc adds RETURNING * under :return-keys true")
    (is (= [:map [:name text]]
           (row {:update :groups :set {:name "b"}} (assoc-in opts [:jdbc :return-keys] ["name"]))))
    (is (= [:=> [:cat] [:maybe [:map [:name text]]]]
           (h/query-schema registry [] {:delete-from :groups} (-> opts (assoc-in [:jdbc :return-keys] ["name"]) (assoc :result :one))))
        "a write returning rows may return none")
    (is (nil? (row {:truncate :users} (assoc-in opts [:jdbc :return-keys] true)))
        "next.jdbc reads TRUNCATE's generated keys, which have no column")
    (is (= (row {:update :groups :set {:name "b"}} (assoc-in opts [:jdbc :return-keys] ["name"]))
           (row {:update :groups :set {:name "b"}} (assoc-in opts [:jdbc :return-keys] [:groups/name])))
        "a keyword names its column, as next.jdbc takes it")
    (is (= [:map [:id int4]]
           (row {:delete-from :groups :returning [:id]} (assoc-in opts [:jdbc :return-keys] ["name"])))
        "pgjdbc adds no RETURNING to a statement that has one")
    (is (= (row {:select [:id] :from [:users]})
           (row {:select [:id] :from [:users]} (assoc-in opts [:jdbc :return-keys] true))))
    (is (nil? (row '{:delete-from :users :returning ?cols})) "a RETURNING code builds may return rows")
    (is (= [{:kind :unknown-column :column :nmae :did-you-mean [:name]}]
           (check {:delete-from :groups} (assoc-in opts [:jdbc :return-keys] ["nmae"])))
        "check reads the RETURNING :return-keys adds")
    (is (= [{:kind :unknown-column :column :group-id :did-you-mean [:group_id]}]
           (check {:update :users :set {:nick "a"}} (assoc-in opts [:jdbc :return-keys] [:group-id])))
        "pgjdbc sends a :return-keys name quoted, as written")
    (is (= [] (check {:update :users :set {:nick "a"} :returning [:id]} (assoc-in opts [:jdbc :return-keys] [:group-id])))
        "an explicit RETURNING leaves :return-keys unused")))

(deftest an-expression-has-the-type-postgresql-gives-it
  (let [row #(h/row-schema registry {:select % :from [:users]} opts)
        nick [:string {:pg/type "character varying"}]]
    (is (= [:map [:n bigint]] (row [[[:count :nick] :n]])) "count is never NULL")
    (is (= [:map [:s [:maybe bigint]]] (row [[[:sum :group_id] :s]])) "sum of an integer is a bigint, NULL over no row")
    (is (= [:map [:a [:maybe [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]] (row [[[:avg :group_id] :a]])))
    (is (= [:map [:n [:maybe [:string {:pg/type "character varying" :max 40 :pg/max-length 40}]]]] (row [[[:coalesce :nick] :n]]))
        "coalesce of one column is that column")
    (is (= [:map [:n nick]] (row [[[:coalesce :nick "x"] :n]]))
        "a value that is not NULL makes it NOT NULL; a literal's length is not the column's")
    (is (= [:map [:n [:maybe :any]]] (row [[[:coalesce :nick :id] :n]])) "columns of different types are not resolved here")
    (is (= [:map [:g bigint] [:l [:maybe :any]]] (row [[[:greatest :id 3] :g] [[:least :id :?x] :l]]))
        "greatest and least resolve as coalesce, NULL only where every operand is; an argument may widen a number")
    (is (= [:map [:n [:maybe text]]] (row [[[:nullif :nick "x"] :n]])) "nullif makes NULL; a varchar's = is text's")
    (is (= [:map [:b [:boolean {:pg/type "boolean"}]] [:c [:maybe [:boolean {:pg/type "boolean"}]]]]
           (row [[[:= :id 1] :b] [[:= :nick "a"] :c]]))
        "a comparison is NULL where an operand may be")
    (is (= [:map [:u [:maybe text]] [:p [:maybe :any]] [:f [:maybe :any]]] (row [[[:upper :nick] :u] [:?x :p] [[:public.my-fn :nick] :f]]))
        "a function of pg_catalog has the type it resolves to; an argument, or the application's function, is untyped")
    (is (= [:map [:n [:double {:pg/type "double precision"}]]] (row [[[:coalesce :score 1.5] :n]]))
        "a number is a typed parameter, which PostgreSQL resolves with the column's type to the wider")
    (is (= [:map [:i int4] [:f ['float? {:pg/type "real"}]] [:d [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]
           (row [[(int 1) :i] [(float 1.5) :f] [1.5M :d]]))
        "pgjdbc types a parameter by its class")
    (is (= [:map [:i [:maybe :any]] [:s text]]
           (h/row-schema registry {:select [[1 :i] ["s" :s]] :from [:users]} (assoc opts :format {:inline true})))
        "an inlined number is typed by its text, which is not read here; an inlined string is text")
    (is (= [:map [:s [:string {:pg/type "character varying"}]]] (h/row-schema registry {:select [["s" :s]] :from [:users]} opts)))
    (is (= [:map [:b [:maybe [:int {:pg/type "bigint"}]]]] (row [[[(keyword "~") :id] :b]])) "~ alone is bitwise NOT, of a bigint a bigint")
    (is (= [:map [:k [:maybe text]] [:v [:maybe :any]]] (row [[[:->> [:cast :nick :jsonb] "k"] :k] [[:->> :nick "k"] :v]]))
        "jsonb ->> text is text; PostgreSQL has no ->> of a varchar")
    (is (= [:map [:s [:maybe [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]] (row [[[:sum :id] :s]]))
        "sum of a bigint is a numeric")
    (is (= [:map [:mood [:maybe mood]] [:n [:maybe bigint]]]
           (h/row-schema registry {:select [:mood [[:count :*] :n]] :from [:users] :group-by [[:rollup :mood]]} opts))
        "ROLLUP adds rows whose columns are NULL")))

(deftest an-argument-compared-with-an-expression-takes-its-type
  (let [args #(h/arg-types registry {:select [:id] :from [:users] :where %} opts)
        text [:or text :keyword]]
    (is (= {:k text} (args [:= [:->> [:cast :nick :jsonb] "k"] :?k])))
    (is (= {:t text} (args [:not [:= [:cast :id :text] :?t]])))
    (is (= {:ts (in-list text)} (args [:in [:cast :id :text] :?ts])))
    (is (= {:s integer :i bigint}
           (args [:< [:composite :score :id] [:composite :?s :?i]]))
        "a row compares element by element")
    (is (= {:a text :b text} (args [:between [:cast :id :text] :?a :?b])))
    (is (= {:x text} (args [:= [:upper :nick] :?x])) "upper of a varchar is text")
    (is (= {:x [:any {:pgmalli/untyped :type}]} (args [:= [:public.my-fn :nick] :?x])) "an expression of no known type types nothing")
    (let [check #(h/check registry {:select [:id] :from [:users] :where %} opts)]
      (is (= [] (check [:= [:cast :nick :mood] :?m]))
          "an argument is arg-types'")
      (is (= [] (check [:= [:composite :mood :id] [:composite :?m :?i]]))
          "a row is checked element by element")
      (is (= [{:kind :unknown-column :column :nope}] (check [:< [:composite :nope :id] [:composite 1 2]]))))))

(deftest an-insert-select-assigns-its-items-to-the-columns-listed
  (let [q {:insert-into [[:users [:group_id :mood :score :nick]] {:select [[:?g] [[:cast :?m :mood]] [[:unnest :?s]] [:?n :nick_alias]]}]}]
    (is (= {:g int4
            :m [:enum {:pg/type "mood"} "happy" "sad" :happy :sad]
            :s [:pg/array int4]
            :n [:maybe [:or [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}] :keyword]]}
           (args q opts))
        "by position: an argument takes its column's type, an unnest one an array of it")
    (is (= [] (check q opts)))
    (is (= []
           (check {:insert-into [[:users [:group_id :mood :score]] {:select [[:?g] [:?m] [:?s]]}]} opts))
        "in the SELECT, where the argument is")
    (is (= [] (check {:insert-into [[:users [:group_id :mood :score]] {:select [[:?g] [[:unnest :?m]] [:?s]]}]} opts))
        "an array is not judged: a java.sql.Array of the enum type is taken, a String[] is not")
    (is (= {:s [:any {:pgmalli/untyped :position}]} (args {:insert-into [[:users [:group_id :nick :score]] {:select [:g.* :?s] :from [[:groups :g]]}]}))
        "a star stands for columns the data does not count")))

(deftest a-cast-to-an-alias-or-an-enum-has-its-type
  (is (= [:map [:m [:enum {:pg/type "mood"} "happy" "sad"]] [:i int4]
          [:b [:boolean {:pg/type "boolean"}]] [:f [:double {:pg/type "double precision"}]]]
         (row {:select [[[:cast "sad" :mood] :m] [[:cast :id :int] :i] [[:cast true :bool] :b] [[:cast :score :float] :f]] :from [:users]}))))

(deftest an-argument-takes-its-slots-own-values
  (let [native opts
        args #(h/arg-types registry {:select [:id] :from [:users] :where %} (merge native %2))
        numeric ['decimal? {:pg/type "numeric"}]]
    (is (= {:x bigint} (args [:= :id :?x] {})) "a column's own values")
    (is (= {:x [:or numeric 'integer? :int]} (args [:= [:cast :id :numeric] :?x] {}))
        "a numeric takes an integer too, a BigInt and a BigInteger as pgjdbc sends any Number: the same number")
    (is (= {:x bigint} (args [:= :id :?x] {})) "an untyped string is no bigint's own value")))

(deftest a-cast-argument-takes-what-postgresql-casts
  (let [args #(h/arg-types registry % opts)
        cast-to-bigint [:or bigint 'decimal? 'integer? :double 'float? :string 'bytes? :keyword pg-object]]
    (is (= {:x cast-to-bigint} (args {:select [:id] :from [:users] :where [:= :id [:cast :?x :bigint]]}))
        "compared: NULL never matches")
    (is (= {:x [:maybe cast-to-bigint]} (args {:select [[[:cast :?x :bigint] :n]]})) "selected: NULL is a value")
    (let [{:keys [x n]} (args {:insert-into [[:users [:score :nick]] {:select [[[:cast :?x :bigint]] [[:cast :?n :text]]]}]})]
      (is (= cast-to-bigint x) "assigned to a NOT NULL column: no NULL")
      (is (= :maybe (first n)) "assigned to a nullable one: NULL"))
    (is (= {:x [:enum {:pg/type "mood"} "happy" "sad" :happy :sad]} (args {:select [:id] :from [:users] :where [:= :mood [:cast :?x :mood]]})))
    (is (= {:x [:maybe [:enum {:pg/type "mood"} "happy" "sad" :happy :sad]]} (args {:select [[[:cast :?x :mood] :m]]}))
        "cast, an enum's labels as strings")))

(deftest limit-and-offset-take-nil-for-none
  (let [t (:n (args {:select [:id] :from [:users] :limit :?n}))]
    (is (= [:maybe [:int {:pg/type "bigint" :min 0}]] t))
    (is (every? #(m/validate t %) [nil 0 1]))
    (is (not (m/validate t -1)) "PostgreSQL refuses a negative LIMIT")))

(deftest an-alternative-is-held-to-its-columns-range
  (let [t (:x (args {:update :users :set {:score [:cast :?x :integer]} :where [:= :id 1]}))]
    (is (m/validate t 3M))
    (is (not (m/validate t 1000000000000M)) "beyond an integer's range, PostgreSQL refuses it")
    (is (not (m/validate t 1.0E12)))))

(deftest only-a-number-is-held-to-a-number-columns-range
  (is (m/validate (:x (args {:select [[[:cast :?x :integer] :n]]} {:schema "sample"})) "5" {:registry registry})
      "a string cast to an integer is read as one, then checked")
  (let [t (:x (args {:update :users :set {:nick [:cast :?x :varchar]} :where [:= :id 1]} {:schema "sample"}))]
    (is (every? #(m/validate t % {:registry registry}) [50 true]) "a varchar's length bounds no number")))

(deftest an-option-of-pgmalli-0-3-names-what-replaces-it
  (doseq [[o says] [[{:write [[:keyword :unknown]]} #":write.* is no longer read: pgmalli types arguments as one sending sends them"]
                    [{:stringtype :unspecified} #":stringtype.* is no longer read: pgmalli types arguments as one sending sends them"]
                    [{:send {String :unknown}} #":send.* is no longer read: pgmalli types arguments as one sending sends them: .*docs/reading-rows.md#the-one-reading-and-sending"]
                    [{:qualified? false} #":qualified\? false.* is no longer read: .*\{:builder-fn next.jdbc.result-set/as-unqualified-maps\}"]
                    [{:kebab? true} #":kebab\? true.* is no longer read"]
                    [{:return-keys true} #":return-keys is no longer read: pass it as \{:jdbc \{:return-keys ...\}\}; see https://github.com/chaploud/pgmalli/blob/main/CHANGELOG.md$"]]]
    (let [e (try (args {:select [:id] :from [:users]} o) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (re-find says (str (ex-message e))) (pr-str o))
      (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))) (pr-str o)))))

(deftest a-builder-other-than-as-unqualified-maps-is-an-error-naming-it
  (doseq [jdbc [{:builder-fn 'next.jdbc.result-set/as-unqualified-kebab-maps} {:builder-fn (fn [_ _])} {:label-fn str/lower-case} {:qualifier-fn str/lower-case}]]
    (let [e (try (row {:select [:id] :from [:users]} (assoc opts :jdbc jdbc)) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (re-find #"is no longer read: pgmalli types rows as next.jdbc.result-set/as-unqualified-maps builds them" (str (ex-message e))) (pr-str jdbc))
      (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))))))
  (is (= (row {:select [:id] :from [:users]})
         (row {:select [:id] :from [:users]} (assoc opts :jdbc {:builder-fn 'next.jdbc.result-set/as-unqualified-maps})))
      "the one builder, as the map an application passes next.jdbc holds it"))

(deftest a-derived-tables-and-a-set-operations-columns-are-typed
  (is (= [:map [:pending [:boolean {:pg/type "boolean"}]] [:all [:maybe [:boolean {:pg/type "boolean"}]]]]
         (row {:select [[[:coalesce [:bool_or [:= :id 1]] false] :pending] [[:bool_and [:> :score 0]] :all]] :from [:users]}))
      "bool_or, bool_and and every are boolean, NULL over no row")
  (is (= [:map [:token [:string {:pg/type "text"}]]] (row {:select [[[:cast :id :text] :token]] :from [:users]} opts)))
  (is (= [:map [:n [:maybe [:string {:pg/type "character varying"}]]]] (row {:select [[[:cast :nick :varchar] :n]] :from [:users]} opts)))
  (is (= [:map [:uid [:int {:pg/identity :always :pg/type "bigint"}]] [:one int4]]
         (row {:select [:*] :from [[{:select [[:id :uid] [[:inline 1] :one]] :from [:users]} :m]]}))
      "SELECT * of a subquery lists its columns in order")
  (is (= [:map [:uid [:int {:pg/identity :always :pg/type "bigint"}]] [:n [:maybe [:string {:pg/type "character varying"}]]] [:one int4]]
         (row {:select [:m/*] :from [[{:select [[:id :uid] [[:cast :nick :varchar] :n] [[:inline 1] :one]] :from [:users]} :m]]}
              opts)))
  (is (= [:map [:x bigint]] (row {:select [:x] :from [[{:union [{:select [[:id :x]] :from [:users]} {:select [[:score :x]] :from [:users]}]} :s]]}))
      "a set operation's column is of the type PostgreSQL resolves its members' to: numbers the widest"))

(deftest the-one-sending-sends-a-local-date-time-and-a-long-array
  (let [at #(:x (args {:update :users :set {:updated_at :?x} :where [:= :id 1]} {:schema "sample"}))]
    (is (m/validate (at) (java.time.LocalDateTime/of 2026 1 1 0 0) {:registry registry}) "next.jdbc.date-time sends a LocalDateTime untyped, a timestamp's own value"))
  (is (= {:ids [:pg/array bigint]} (args {:select [:id] :from [:users] :where [:= :id [:any :?ids]]} {:schema "sample"}))
      "pgjdbc sends a long[] as a bigint[]"))

(deftest a-cast-arguments-nil-follows-every-use-of-the-cast
  (let [nullable? #(= :maybe (first (:x (args % (merge {:schema "sample"} %2)))))
        cast [:cast :?x :bigint]]
    (is (not (nullable? {:select [:id] :from [:users] :where [:= :id cast]} {})))
    (is (nullable? {:select [:id] :from [:users] :where [:= cast nil]} {}) "IS NULL")
    (is (nullable? {:select [:id] :from [:users] :where [:or [:is cast nil] [:= :id cast]]} {}) "an optional filter")
    (is (nullable? {:select [:id] :from [:users] :where [:is-distinct-from :referrer_id cast]} {}))
    (is (nullable? {:insert-into :nowhere :values [{:a cast}]} {}) "a column the registry lacks")
    (is (not (nullable? {:select [:id] :from [:users] :where [:in :id {:select [:id] :from [:users] :where [:= :id cast]}]} {}))
        "a subquery's comparison is the cast's use there alone")))

(deftest a-domain-over-a-timestamp-with-a-precision-takes-its-java-time-type
  (let [reg (pgmalli/registry {:registry {:pg.public/at3 [:time/instant {:pg/type "timestamp(3) with time zone"}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:at [:ref {:pg/type "at3"} :pg.public/at3]]]}})]
    (is (m/validate (:x (h/arg-types reg {:update :t :set {:at :?x}})) (java.time.OffsetDateTime/now) {:registry reg}))))

(deftest a-keyword-goes-in-a-cast-too
  (let [o {:schema "sample"}]
    (is (m/validate (:x (args {:select [[[:cast :?x :text] :t]]} o)) :skill {:registry registry}))
    (is (= [:double {:pg/type "double precision"}]
           (second (:x (args {:select [:id] :from [:users] :where [:= [:cast :id :double-precision] :?x]} o))))
        "a column's own values are kept whole, first")))

(deftest a-comparison-in-a-select-or-order-by-is-read
  (is (= {:t bigint :o integer}
         (args {:select [:id [[:case [:= :id :?t] 1 :else 0] :c]] :from [:users] :order-by [[[:case [:= :score :?o] 0 :else 1]]]})))
  (is (= [{:kind :unknown-column :column :nope}] (check {:select [[[:case [:= :nope 1] 1 :else 0] :c]] :from [:users]}))))

(deftest a-map-goes-as-jsonb
  (is (= {:m [:or [:map {:pg/type "jsonb"} [:a :int]] pg-object]}
         (h/arg-types docs {:update :docs :set {:meta :?m} :where [:= :id 1]} {}))
      "what goes as a shaped json column's type takes its shape")
  (is (= {:m [:or [:map-of :any :any] [:vector :any] pg-object]}
         (h/arg-types (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:j [:any {:pg/type "jsonb"}]]]}})
                      {:update :t :set {:j :?m}} {}))
      "what goes as a type pgmalli has the driver's object for is all its argument takes"))

(deftest a-domain-keeps-its-checks-whatever-send-sends
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
    (is (not (ok? (h/values-schema reg :pg.public/t) {:p -3 :a 1M})))
    (is (not (ok? (:x (h/arg-types reg {:select [[[:cast :?x :amount] :a]]})) -3M)))))

(deftest a-cast-to-an-integer-domain-with-a-check-list-takes-what-postgresql-converts
  (let [reg (pgmalli/registry {:registry {:pg.public/lvl [:enum {:pg/type "integer"} 1 2]}})
        x (:x (h/arg-types reg {:select [[[:cast :?x :lvl] :v]]}))
        ok? #(m/validate x % {:registry reg})]
    ;; PostgreSQL converts the value to integer, rounding, then checks the list
    (is (every? ok? [1 2 1.0 (float 1) 1.5 1.5M true]))
    (is (not (ok? 3)) "a Long outside the list stays refused")))

(deftest an-argument-a-row-may-not-carry-to-a-check-list-of-a-type-with-a-typmod-takes-the-base-type
  (let [reg (pgmalli/registry {:registry {:pg.public/wd [:enum {:pg/type "character varying(10)"} "a" "b"]
                                          :pg.public/lists [:map {:pg/table "public.lists"}
                                                            [:w [:ref {:pg/type "wd"} :pg.public/wd]]
                                                            [:v [:enum {:pg/type "character varying(10)"} "a"]]]}})]
    (doseq [c [:w :v]
            :let [x (:x (h/arg-types reg {:insert-into [[:lists [c]] {:select [:?x] :where false}]}))]]
      (is (= [:maybe [:or [:string {:pg/type "character varying"}] :keyword]] x) (str c)))))

(deftest a-number-check-lists-labels-go-as-each-number-sent
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                         [:d [:enum {:pg/type "double precision" :pg/constraints ["t_d_check"]} 1.0 2.0]]
                                                         [:i [:enum {:pg/type "integer" :pg/constraints ["t_i_check"]} 1 2]]]}})
        ok? #(m/validate %1 %2 {:registry reg})
        {:keys [d]} (h/arg-types reg {:update :t :set {:d :?d}})
        {:keys [i]} (h/arg-types reg {:update :t :set {:i [:cast :?i :integer]}})]
    (is (ok? d 1) "a bigint 1 goes to a double precision as 1.0")
    (is (not (ok? d 3)))
    (is (every? #(ok? i %) [1 1M 1.0]) "PostgreSQL casts a numeric or a double that is a label")))

(deftest a-string-of-another-string-type-is-a-string-columns-own
  (is (= {:n [:maybe [:or text :keyword]]}
         (args {:update :users :set {:group_name :?n} :where [:= :id 1]} {:schema "sample"}))))

(deftest an-enum-domain-takes-its-labels-alone
  (let [reg (pgmalli/registry {:registry {:pg.public/mood [:enum "ok" "sad"]
                                          :pg.public/good [:and [:ref {:pg/type "mood"} :pg.public/mood] [:not= "ok"]]
                                          :pg.public/t [:map {:pg/table "public.t"} [:g [:ref {:pg/type "good"} :pg.public/good]]]}})
        t (:g (h/arg-types reg {:update :t :set {:g :?g}} {}))]
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

(deftest min-and-max-of-a-domain-are-of-its-base-type
  (let [reg (pgmalli/registry {:registry {:pg.public/code3 [:enum {:pg/type "character varying(3)"} "aaa" "bbb"]
                                          :pg.public/pos [:pg/integer {:min 1 :pg/type "integer"}]
                                          :pg.public/mood [:enum "ok" "sad"]
                                          :pg.public/mood_d [:ref :pg.public/mood]
                                          :pg.public/t [:map {:pg/table "public.t"}
                                                        [:c [:ref {:pg/type "code3"} :pg.public/code3]]
                                                        [:p [:ref {:pg/type "pos"} :pg.public/pos]]
                                                        [:m [:ref {:pg/type "mood_d"} :pg.public/mood_d]]]}})
        row #(h/row-schema reg {:select [[[:max %] :x]] :from [:t]} {})]
    (is (= [:map [:x [:maybe text]]] (row :c)) "max(varchar) is max(text)")
    (is (= [:map [:x [:maybe [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]] (row :p)))
    (is (= [:map [:x [:maybe [:enum {:pg/type "mood"} "ok" "sad"]]]] (row :m)) "a domain over an enum is the enum")))

(deftest a-domain-over-bit-or-numeric-is-read-as-the-driver-reads-its-base
  (let [reg (pgmalli/registry {:registry {:pg.public/d1 [:string {:pg/type "bit(1)"}]
                                          :pg.public/d4 [:string {:pg/type "bit(4)"}]
                                          :pg.public/dv [:string {:pg/type "bit varying(5)"}]
                                          :pg.public/t [:map {:pg/table "public.t"}
                                                        [:d1 [:ref {:pg/type "d1"} :pg.public/d1]]
                                                        [:d4 [:ref {:pg/type "d4"} :pg.public/d4]]
                                                        [:dv [:ref {:pg/type "dv"} :pg.public/dv]]]}})]
    (is (= [:map [:d1 [:boolean {:pg/type "bit(1)"}]] [:d4 [:any {:pg/type "bit(4)"}]] [:dv [:any {:pg/type "bit varying(5)"}]]]
           (h/row-schema reg {:select [:d1 :d4 :dv] :from [:t]} {}))
        "a Boolean for bit(1), a PGobject otherwise"))
  (let [reg (pgmalli/registry {:registry {:pg.public/nm [:and ['decimal? {:pg/type "numeric(6,2)"}] [:pg/numeric {:precision 6 :scale 2}]]
                                          :pg.public/t [:map {:pg/table "public.t"} [:n [:ref {:pg/type "nm"} :pg.public/nm]]]}})]
    (is (= [:map [:n [:or ['decimal? {:pg/type "numeric(6,2)"}] ['double? {:pg/type "numeric(6,2)"}]]]]
           (h/row-schema reg {:select [:n] :from [:t]} {}))
        "a numeric domain's NaN is a Double")))

(deftest a-field-of-a-composite-column-is-assigned-to-that-column-its-value-unchecked
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:id :int]
                                                        [:p [:maybe [:map {:pg/type "pair"} [:a :int] [:b :string]]]]]}})
        check #(h/check reg % {})]
    (is (= [] (check {:update :t :set {:p.a 5} :where [:= :id 1]})))
    (is (= [] (check {:update :t :set {:p.b :?v} :where [:= :id 1]})))
    (is (= [] (check {:insert-into [[:t [:id :p.a]] {:values [[1 5]]}]})))
    (is (= [] (check {:insert-into :t :columns [:id :p.a] :values [[1 5]]})))
    (is (= [] (check {:insert-into :t :values [{:id 1 :p.a 5}]})))
    (is (= [{:kind :unknown-column :column :q :did-you-mean [:p]}] (check {:update :t :set {:q.a 5} :where [:= :id 1]}))
        "the column of the field is looked for")
    (is (= {:v [:any {:pgmalli/untyped :position}]} (h/arg-types reg {:update :t :set {:p.b :?v} :where [:= :id 1]})))))

(deftest an-array-cast-to-an-array-domain-is-any-array-postgresql-casts
  (let [reg (pgmalli/registry {:registry {:pg.public/ints_d [:vector {:pg/type "integer[]"} :pg/integer]}})
        t (:x (h/arg-types reg {:select [[[:cast :?x :ints_d] :c]]}))]
    (doseq [v [(double-array [1.0]) (into-array BigDecimal [1M]) (boolean-array [true]) (into-array String ["1"]) (long-array [1])]]
      (is (m/validate t v {:registry reg}) (pr-str (class v))))))

(deftest an-untyped-column-written-takes-no-keyword-vector-or-sequence
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:id :int]
                                                        [:j [:any {:pg/type "json"}]]
                                                        [:ip [:maybe [:any {:pg/type "inet"}]]]]}})]
    (doseq [[o default] [[{} :default] [{:format {:quoted true}} [:default]]]
            [f row] [[h/values-schema {:id 1 :j "{}"}] [h/set-schema {}]]
            :let [ok? #(m/validate (f reg :t o) (merge row %) {:registry reg})]]
      (is (every? ok? [{:j "{}"} {:ip "10.0.0.1"} {:ip nil}]))
      (is (not-any? ok? [{:j :k} {:ip :k} {:j [1 2]} {:ip '("a")} {:j (list 1)}]) (pr-str f o))
      (is (ok? {:ip default}) "HoneySQL's DEFAULT where the database fills a column"))))

(deftest a-number-label-is-read-from-a-string-sent-untyped
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:l [:enum {:pg/type "integer" :pg/constraints ["t_l_check"]} 1 2 3]]]}})]
    (is (m/validate (:l (h/arg-types reg {:update :t :set {:l [:cast :?l :integer]}} {})) "1" {:registry reg}))))

(deftest an-integer-is-a-bigint-columns-own
  (is (m/validate (:x (args {:select [:id] :from [:users] :where [:= :id :?x]} {:schema "sample"})) (int 1) {:registry registry})))

(deftest a-registry-without-the-derived-schema-says-so
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the registry has no insert schema"
                        (h/values-schema (merge (m/default-schemas) {:pg.public/t [:map {:pg/table "public.t"} [:id :int]]}) :pg.public/t))
      "a hand-written row schema has none"))

(deftest an-untyped-value-is-a-text-or-enum-columns-own
  (is (= {:x [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} :happy :sad]}
         (args {:select [:id] :from [:users] :where [:= :mood :?x]} {:schema "sample"}))
      "a keyword sent untyped is an enum's label, a String sent as varchar is not")
  (is (= {:x [:any {:pg/type "json"}]}
         (h/arg-types (pgmalli/registry {:registry {:pg.public/j [:map {:pg/table "public.j"} [:doc [:any {:pg/type "json"}]]]}})
                      {:select [:doc] :from [:j] :where [:= :doc :?x]} {}))
      "json has no =, untyped or not")
  (is (= [:uncast-string] (map :kind (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {:schema "sample"})))
      "a String literal goes as varchar")
  (is (= [] (check {:select [:id] :from [:users] :where [:= :mood :?m]} {:schema "sample"}))
      "an argument is arg-types'"))

(deftest a-number-going-to-a-checked-number-column-is-held-to-the-check
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                         [:n [:and {:pg/type "numeric" :pg/constraints ["t_n_check"]} 'decimal? [:> 0]]]
                                                         [:k [:enum {:pg/type "integer" :pg/constraints ["t_k_check"]} 1 2]]]}})
        {:keys [n k]} (h/arg-types reg {:update :t :set {:n :?n :k :?k}} {})]
    (is (m/validate n 3 {:registry reg}))
    (is (not (m/validate n -3 {:registry reg})) "PostgreSQL checks the CHECK after converting it")
    (is (= [:enum {:pg/type "integer" :pg/constraints ["t_k_check"]} 1 2] k) "a number label has no keyword")))

(deftest a-cast-to-a-type-pgmalli-does-not-answer-for-takes-anything
  (let [reg (pgmalli/registry {:registry {}})]
    (is (= {:x [:any {:pgmalli/untyped :type}]} (h/arg-types reg {:select [[[:cast :?x :ltree] :b]]})) "an extension's casts are not in pg_catalog")
    (is (m/validate (:x (h/arg-types reg {:select [[[:cast :?x :bit] :b]]})) 1 {:registry reg}) "PostgreSQL casts a bigint to bit")))

(deftest a-json-document-cast-to-a-scalar-type-is-not-taken
  (let [reg (pgmalli/registry {:registry {}})
        o {}
        x (fn [t] (:x (h/arg-types reg {:select [[[:cast :?x t] :b]]} o)))]
    (doseq [t [:int :numeric :boolean :double-precision]]
      (is (not (m/validate (x t) {:a 1} {:registry reg})) (str "a jsonb object cast to " t " errors"))
      (is (not (m/validate (x t) [1] {:registry reg})) (str "a jsonb array cast to " t " errors")))
    (doseq [t [:text :varchar :jsonb]]
      (is (m/validate (x t) {:a 1} {:registry reg}) (str "a jsonb object casts to " t))
      (is (m/validate (x t) [1] {:registry reg}) (str "a jsonb array casts to " t)))))

(deftest an-arrays-elements-are-read-as-the-columns-type
  (let [t (:xs (args {:select [:id] :from [:users] :where [:= :id [:any :?xs]]} {:schema "sample"}))]
    (is (m/validate t (long-array [1]) {:registry registry}))
    (is (not (m/validate t (into-array java.math.BigDecimal [1.5M]) {:registry registry})) "a numeric[] is no bigint[]'s own"))
  (let [t (:xs (args {:insert-into [[:users [:group_id :score]] {:select [[:?g] [[:unnest :?xs]]]}]} {:schema "sample"}))]
    (is (not (m/validate t (into-array java.math.BigDecimal [1M]) {:registry registry}))
        "unnest takes a typed array whose elements the column holds as its own, which a numeric is not for an integer")))

(deftest a-types-own-values-come-first-however-many-kinds-it-takes
  (is (= text (second (second (:x (args {:select [[[:cast :?x :text] :t]]} {:schema "sample"})))))))

(deftest an-inlined-string-is-text
  (is (= [:map [:s text]]
         (row {:select [["s" :s]] :from [:users]} {:schema "sample" :format {:inline true}}))))

(deftest a-fraction-going-to-an-integer-is-rounded-first
  (let [t (:x (args {:select [[[:cast :?x :integer] :n]]} {:schema "sample"}))]
    (is (m/validate t 2147483647.4 {:registry registry}) "PostgreSQL rounds it to 2147483647")
    (is (not (m/validate t 2147483648.0 {:registry registry})))))

(deftest a-cast-into-a-not-null-column-takes-no-nil
  (let [x #(:x (args % {:schema "sample"}))
        cast [:cast :?x :bigint]]
    (is (not= :maybe (first (x {:select [:id] :from [:users] :where [:in :id [cast 2]]}))))
    (is (not= :maybe (first (x {:select [:id] :from [:users] :where [:between :id cast 9]}))))
    (is (not= :maybe (first (x {:update :users :set {:score [:cast :?x :integer]} :where [:= :id 1]}))))
    (is (= :maybe (first (x {:update :users :set {:referrer_id cast} :where [:= :id 1]}))))))

(deftest values-and-set-schemas-type-a-row-written
  (let [v (h/values-schema registry :pg.sample/users {:schema "sample"})
        s (h/set-schema registry :pg.sample/users {:schema "sample"})
        ok? #(m/validate %1 %2 {:registry registry})]
    (is (= [:born :closed_at :group_id :group_name :mood :nick :referrer_id :score :seq :total :updated_at :id :nick_upper] (map first (rest (:gen/schema (second v)))))
        "the columns the database writes last")
    (is (ok? v {:group_id 1 :score 0 :id :default :nick_upper [:default] :total :default}) "HoneySQL's :default where the database fills or writes a column")
    (is (not-any? #(ok? v %) [{:group_id 1 :score 0 :id 1} {:group_id :default :score 0}]) "a value where the database writes a column, :default where it fills none")
    (is (ok? s {:total :default}))
    (is (not (ok? s {:score :default})) "a NOT NULL column with no default has no DEFAULT to set")
    (is (ok? v {:group_id 1 :score 0}) "those it fills optional")
    (is (not-any? #(ok? v %) [{:score 0} {:group_id 1 :score 0 :id 1} {:group_id 1 :score 0 :mood "glad"} {:group_id 1 :score 0 :nick nil :born "x"}]))
    (is (every? #(ok? s %) [{:nick nil} {:mood :default} {:nick "abc"}]) "every column optional")
    (is (not-any? #(ok? s %) [{:mood :sad} {:nick :abc}]) "HoneySQL writes a keyword in :set as a column")
    (is (not-any? #(ok? s %) [{:score nil} {:id 1} {:mood :glad}]))
    (is (not (ok? s {})) "HoneySQL writes an empty :set as SET alone, which PostgreSQL refuses")
    (is (= ["should not be {}"] (me/humanize (m/explain s {} {:registry registry}))))
    (is (not-any? #{:any} (flatten s)) "at least one column is said without :any, which reads as an untyped part")
    (is (= s (m/form (m/schema s {:registry registry}))) "malli keeps the form as it is")
    (is (every? #(and (seq %) (ok? s %)) (mg/sample s {:registry registry :seed 1})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes its row schema :pg.sample/users" (h/values-schema registry :pg.sample.users/insert)))
    (is (= [:born :closed_at :group_id] (take 3 (written-keys (h/values-schema registry :pg.sample/users {:schema "sample"}))))
        "keyed as a row is read")
    (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:address_line1 :string] [:lv1 :string]]}})]
      (is (= [:address_line1 :lv1] (written-keys (h/values-schema reg :t {}))))
      (is (= [:address_line1 :lv1] (written-keys (h/values-schema reg :t {:format {:quoted true}})))
          "quoted alike"))
    (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} ["a-b" :string]]}})]
      (is (= ["a-b"] (written-keys (h/values-schema reg :t {})))
          "a column keyed by a string, which HoneySQL quotes as it is"))
    (is (= v (h/values-schema registry :users {:schema "sample"}) (h/values-schema registry :sample.users) (h/values-schema registry :sample/users))
        "a table as HoneySQL names it, in :schema")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"values-schema takes a table as a keyword" (h/values-schema registry "users" {:schema "sample"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did you mean" (h/values-schema registry :pg.sample.users)))
    (let [reg (pgmalli/registry {:registry {:pg.public/v [:map {:pg/view "public.v"} [:id [:maybe [:int {:pg/type "bigint"}]]]]}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"a view has no insert schema" (h/values-schema reg :pg.public/v))))))

(deftest a-row-is-written-keyed-as-it-is-read
  (let [o {:schema "sample"}
        v (h/values-schema registry :users o)
        s (h/set-schema registry :users o)
        ok? #(m/validate %1 %2 {:registry registry})]
    (is (every? #(ok? v %) [{:group_id 1 :score 0} {:group_id 1 :score 0 :updated_at nil :nick_upper :default}]))
    (is (not-any? #(ok? v %) [{:score 0} {:group_id nil :score 0} {:group-id 1 :score 0}])
        "the kebab spelling is not the key a row is read with")
    (is (= {:group-id ["disallowed key"]} (me/humanize (m/explain v {:group-id 1 :group_id 1 :score 0} {:registry registry}))))
    (is (every? #(ok? s %) [{:group_id 2} {:nick_upper :default}]))
    (is (not-any? #(ok? s %) [{} {:group-id 2} {:nope 1}]))
    (is (every? #(ok? v %) (mg/sample v {:registry registry :seed 1})))
    (is (every? #(and (seq %) (ok? s %)) (mg/sample s {:registry registry :seed 1})))))

(deftest every-shape-of-do-update-set
  (is (= [] (check {:insert-into :groups :values [{:id 1 :name "g"}] :on-conflict [:id]
                    :do-update-set {:fields [:name] :where [:<= :groups.name :EXCLUDED.name]} :returning [:id]}))
      "{:fields :where}: the fields are the columns taken from EXCLUDED, the row proposed for insertion")
  (is (= [{:kind :unknown-column :column :EXCLUDED.nope}]
         (check {:insert-into :groups :values [{:id 1 :name "g"}] :on-conflict [:id]
                 :do-update-set {:fields [:name] :where [:= :groups.name :EXCLUDED.nope]}}))
      "EXCLUDED has the target's columns, no others")
  (is (= [] (check {:insert-into :groups :values [{:id 1 :name "g"}] :on-conflict [:id] :do-update-set {:name :excluded.name}}))))

(deftest a-misspelt-option-is-an-error
  (doseq [o [{:qualifed? true} {:time :instnat} {:read {:json :parse}} {:result :first} {:jdbc {:return-keys []}} {:jdbc {:return-keys "id"}}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pgmalli options" (args {:select [:id] :from [:users]} (merge opts o))) (pr-str o))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pgmalli options" (check {:select [:id] :from [:users]} (merge opts o))) (pr-str o))))

(deftest insert-into-a-table-as-an-alias
  (let [body {:insert-into [:users :u] :values [{:group_id :?g :mood "sad" :score 1}] :on-conflict [:id] :do-update-set {:score :?s} :where [:> :u.score 0]}]
    (is (= [{:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check body)))
    (is (= int4 (:g (args body))))))

(deftest a-from-of-any-shape-is-read-or-left-alone
  (is (= [] (check {:select [:*] :from {:select [:id] :from [:users]}})) "a subquery alone")
  (doseq [f [1 true "users"]] (is (vector? (check {:select [:id] :from f})) (pr-str f))))

(deftest a-table-code-brings-in-has-the-columns-the-query-names
  (is (= [] (check '{:select [:u.id :g.name] :from [[:users :u]] :left-join (when x [[:groups :g] [:= :g.id :u.group_id]])})))
  (is (= [] (check '{:select [:u.id] :from (if x [[:users :u]] [[:users :u]])}))))

(deftest an-opaque-table-beside-a-known-one-makes-no-column-ambiguous
  (is (= [] (check '{:select [:nick] :from [:users] :join [t [:= :t.id :users.id]]})))
  (is (= [:map [:nick [:maybe :any]]] (row '{:select [:nick] :from [:users] :join [t [:= :t.id :users.id]]}))
      "untyped: which table PostgreSQL takes it from is not in the data")
  (is (= [{:kind :unknown-column :column :u.nope}] (check '{:select [:u.nope] :from [[:users :u]] :join [t [:= 1 1]]}))
      "a known table's unknown column is still one"))

(deftest expression-names-follow-postgresql
  (let [row #(h/row-schema registry {:select [%] :from [:users]} opts)]
    (is (nil? (row [[:cast [:+ :id 1] :int]])) "an operation cast is named after the type")
    (is (nil? (row [[:cast [:case [:= :id 1] 2] :int]])))
    (is (= [:map [:coalesce int4]] (row [[:cast [:coalesce :id 0] :int]])) "a function cast keeps its name")
    (is (= [:map [:?column? [:maybe :any]]] (row [[:-> :nick "a"]])) "an operator not an identifier")
    (is (= [:map [:max [:maybe [:int {:pg/type "bigint"}]]]] (row :%max.id)) ":%f.args calls f")
    (is (nil? (row [[:raw "now()"]])) ":raw may be named anything")
    (is (nil? (row [{:select [1]}])) "a subquery is named after its column"))
  (is (= [] (check {:select [:%max.id [:%count.* :c]] :from [:users]})))
  (is (= [] (check {:select [[[:lower :nick]]] :from [:users] :order-by [:lower]})) "an expression's name is an output column"))

(deftest a-right-join-nulls-the-tables-it-attaches-to
  (let [nullable #(into {} (for [[a v] (#'h/scope (#'h/reading registry) % #{} opts)] [a (boolean (:nullable? v))]))]
    (is (= {"u" false "x" true "g" false "i" false}
           (nullable {:select [:*] :from [[:users :u] [:groups :x]] :right-join [[:groups :g] [:= 1 1]] :inner-join [[:groups :i] [:= 1 1]]}))
        "a comma binds looser than JOIN: the right join attaches to the last FROM item; an inner join after it nulls nothing")))

(deftest a-symbol-table-in-joins-and-from-alone
  (is (= [] (check '{:select [:x] :from table})))
  (is (= [] (check '{:select [:t.x :u.id] :from [[:users :u]] :left-join [t [:= :t.uid :u.id]]}))))

(deftest a-typed-use-wins-over-a-driver-object
  (is (= {:x [:and bigint [:or [:map {:pg/type "jsonb"} [:a :int]] pg-object]]} (h/arg-types docs {:select [:id] :from [:docs] :where [:and [:= :meta :?x] [:= :id :?x]]}))
      "a jsonb compared: the values sent as jsonb"))

(deftest a-set-operations-row-keeps-a-null-columns-key
  (is (= [:map
          [:id [:int {:pg/identity :always :pg/type "bigint"}]]
          [:nick [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]]]
         (row {:union [{:select [:id :nick] :from [:users]} {:select [:id :nick] :from [:users]}]} opts))))

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

(deftest nil-options-are-none
  (is (= [] (h/check {:app/x :int} {:select [1]} nil)))
  (is (= (pgmalli/as-read registry :pg.sample/users) (pgmalli/as-read registry :pg.sample/users nil))))

(deftest a-like-pattern-is-no-value-of-the-column
  (is (m/validate (:x (h/arg-types checked {:select [:role_key] :from [:roles] :where [:like :role_key :?x]})) "%adm%"))
  (is (m/validate (:n (args {:select [:id] :from [:users] :where [:ilike :nick :?n]})) (str "%" (apply str (repeat 40 "a")) "%"))
      "longer than the column's 40"))

(deftest insert-into-a-table-as-an-alias-with-columns
  (is (= [{:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check '{:insert-into [[:users :u] [:group_id :mood :score]] :values [[?a "sad" ?c]] :returning [:u.id]})))
  (is (= [] (check '{:insert-into (t) :values [{:a 1}]})) "a target code names: its columns are not judged")
  (is (= [] (check '{:update (t) :set {:a 1} :where [:= :id 1]}))))

(deftest a-subquery-alone-in-from-is-opaque
  (is (= [] (check {:select [:id] :from [{:select [:id] :from [:users]}]}))))

(deftest every-comparison-honeysql-writes-types-its-arguments
  (let [types (args {:select [:id] :from [:users]
                     :where [:and [:not-in :id :?ids] [:between :score :?lo :?hi] [:not-like :nick :?p] [:is-distinct-from :group_id :?g]]})]
    (is (= [:sequential {:min 1} bigint] (second (:ids types))) ":not-in as :in")
    (is (= (:lo types) (:hi types) integer))
    (is (= [:maybe (:lo types)] (:g types)) "IS DISTINCT FROM compares NULL as a value")
    (is (= [:or [:string {:pg/type "character varying"}] :keyword] (:p types)))))

(deftest a-select-of-every-set-operation-and-the-dot
  (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]]
         (row {:except-all [{:select [:id] :from [:users]} {:select [:id] :from [:users]}]})))
  (is (= [:map [:nick [:maybe :any]]] (row {:select [[[:. :u :nick]]] :from [[:users :u]]}))
      "[:. a b] is a.b, named b"))

(deftest a-clause-read-nowhere-is-a-problem
  (is (= [{:kind :unread-clause :clause :join-by}] (check {:select [:id] :from [:users] :join-by [:left [:groups [:= 1 1]]]})))
  (is (= [] (check {:select [:id] :from [:users] :for :update :window [:w {:partition-by [:id]}]})) "clauses that change nothing read here")
  (is (= [{:kind :unknown-column :column :table}
          {:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check {:insert-into :users :values [{:table 1 :group_id 1 :mood "sad" :score 1}]}))
      "a row's column named like a clause is a column"))

(deftest query-schema-names-an-argument-its-args-lack
  (is (= {:pgmalli/problems [{:kind :missing-arg :arg :id}]}
         (m/properties (m/schema (h/query-schema registry [:db :iid] {:select [:id] :from [:users] :where [:= :id :?id]} opts))))))

(deftest a-misspelt-option-names-the-one-meant
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\{:reslt \[\"disallowed key\"\]\}; did you mean: :result" (check {} {:reslt :one}))))

(deftest a-lifted-value-is-data
  (is (= [] (check {:select [:id] :from [:users] :where [:= :id [:lift {:select true :update true :delete false}]]})))
  (is (= [] (check {:select [:id] :from [:users] :where [:= :id [:inline {:table "x" :distinct 1}]]}))))

(deftest a-column-definition-list-is-read-or-left-alone
  (is (vector? (check {:select [:a] :from [[[:jsonb_to_record :x] [:r {:columns [[:raw "a int"]]}]]]}))))

(deftest a-local-code-binds-is-no-missing-arg
  (is (nil? (m/properties (m/schema (h/query-schema registry '[ids] '{:select [:id] :from [:users] :where (into [:or] (for [i ids] [:= :id i]))} opts))))))

(deftest a-call-in-the-wrong-shape-is-an-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes the function's args first" (h/query-schema registry {:select [:id] :from [:users]} opts)))
  (doseq [body ["SELECT 1" ["SELECT 1"] nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reads HoneySQL query data" (check body)) (pr-str body))))

(deftest a-row-of-a-table-the-registry-lacks-is-nil
  (is (nil? (h/row-schema (pgmalli/registry "sample" "other") {:select [:id] :from [:users]} {:schema "other"}))))

(deftest a-cte-code-builds-may-be-any-unqualified-table
  (is (= [:map [:id [:maybe :any]]] (row '{:with ctes :select [:id] :from :recent})))
  (is (= [] (check '{:with ctes :select [:id] :from :recent})))
  (is (= [{:kind :unknown-table :table "other.nope"}] (check '{:with ctes :select [:id] :from :other.nope}))
      "a table qualified by a schema is no CTE"))

(deftest a-ctes-visibility-is-one-judgment
  (let [body {:select [:id :mood] :from [:users] :where [:exists {:with [[:users {:select [:id] :from [:groups]}]] :select [:id] :from [:users]}]}]
    (is (= [] (check body)))
    (is (= mood (last (last (row body))))
        "the outer users is the table, as check reads it: the inner :with is not visible outside"))
  (is (= [{:kind :unknown-column :column :nick :in [:with 0 1]}] (check {:with [[:c {:select [:nick] :from [:groups]}]] :select [:id] :from [:users]}))
      "a CTE body does not see the tables of the statement its :with belongs to"))

(deftest every-comparison-reads-its-value-as-the-columns
  (doseq [w [[:< :mood "bogus"] [:between :mood [:cast "happy" :mood] "bogus"] [:is-distinct-from :mood "bogus"]]]
    (is (= [:enum-literal] (map :kind (check {:select [:id] :from [:users] :where w}))) (pr-str w))))

(deftest a-subquery-in-a-row-is-read
  (let [sub {:select [:id] :from [:groups] :join-by [:left [:users [:= 1 1]]]}]
    (is (some #{{:kind :unread-clause :clause :join-by}} (check {:update :users :set {:group_id sub}})))))

(deftest a-right-join-code-builds-nulls-the-tables-before-it
  (is (= [:map [:id [:maybe [:int {:pg/identity :always :pg/type "bigint"}]]]]
         (row '{:select [:users.id] :from [:users] :right-join (when x [[:groups :g] [:= 1 1]])}))))

(deftest each-part-of-a-statement-sees-what-postgresql-lets-it
  (is (= [{:kind :unknown-column :column :nope :in [:from 0 0]}] (check {:select [:s.id] :from [[{:select [:id :nope] :from :users} :s]]}))
      "a subquery in :from sees no table beside it, nor its own alias")
  (is (= [{:kind :unknown-column :column :name :in [:from 1 0]}] (check {:select [:id] :from [:groups [{:select [:mood] :from :users :where [:= :name "x"]} :s]]})))
  (is (= [] (check {:select [:u.id :x.n] :from [[:users :u]] :join [[[:lateral {:select [[:u.id :n]]}] :x] true]}))
      "LATERAL does")
  (is (= [{:kind :unknown-column :column :nope :in [:with 0 1]}] (check {:with [[:users {:select [:nope] :from :users}]] :select [:id] :from :users}))
      "a :with entry does not see itself")
  (is (= [{:kind :unknown-table :table "sample.b" :in [:with 0 1]}] (check {:with [[:a {:select [:id] :from :b}] [:b {:select [:id] :from :users}]] :select [:id] :from :a}))
      "nor the entries after it")
  (is (= [] (check {:with-recursive [[:t {:union-all [{:select [:id] :from :users} {:select [:id] :from :t}]}]] :select [:id] :from :t}))
      "a :with-recursive one does")
  (is (= [{:kind :unknown-column :column :groups.id :qualifier "groups" :in [:values 0 0 1]}]
         (check {:insert-into :groups :values [{:id {:select [:id] :from :users :where [:= :users.id :groups.id]} :name "x"}]}))
      "a row of :values does not see the table inserted into"))

(deftest rows-read-each-statement-where-it-sits
  (let [q {:select [:id] :from :groups}]
    ;; read in the CTE's scope, the first member would be a varchar, which a bigint does not resolve with
    (is (= [:map [:id [:int {:pg/type "bigint"}]]] (row {:union [q {:with [[:groups {:select [[:nick :id]] :from :users}]] :select [:id] :from :users :where [:in :id q]}]}))
        "the same statement twice, each in its own scope")))

(deftest code-anywhere-leaves-its-part-unjudged
  (is (= [{:kind :unread-clause :clause :frob :did-you-mean [:from]}] (check '(cond-> {:select [:id] :from [:users] :frob 1} x (assoc :limit 1)))))
  (is (= [] (check '{:insert-into [(tbl) [:a]] :values [[1]] :returning [:a]})))
  (is (= [] (check '{:with [[(nm) {:select [:id] :from :users}]] :select [:id] :from :c}))))

(deftest a-star-names-a-table-of-the-scope
  (is (= [{:kind :unknown-column :column :x.* :qualifier "x"}] (check {:select [:x.*] :from :users})))
  (is (= [] (check {:select [:id] :from [[{:select [:id] :from :users}]]})) "[subquery] has no alias"))

(deftest what-postgresql-accepts-checks-clean
  (doseq [q [{:select [:id] :from [:users] :join [:groups [:using :id]]}
             {:select [:users.id] :from [:sample.users]}
             {:select [:sample.users.id] :from [:users]}
             {:select [:ID :Nick] :from [:USERS] :where [:= :Users.ID 1]}
             {:select [:group-id] :from [:users]}
             {:select [:u] :from [[:users :u]]}
             {:select [:users] :from [:users]}]]
    (is (= [] (check q)) (pr-str q))))

(deftest a-scope-hides-what-postgresql-hides
  (is (= [{:kind :unknown-column :column :nosuch :in [:from 1 0 1]}]
         (check {:select [:u.id] :from [[:users :u] [[:lateral {:select [:nosuch] :from [:groups]}] :p]]}))
      "a LATERAL subquery does not see itself")
  (is (= [{:kind :unknown-column :column :u.nick :in [:where 1]}]
         (check {:select [:id] :from [[:users :u]] :where [:exists {:select [1] :from [[:groups :u]] :where [:= :u.nick "x"]}]}))
      "an inner alias hides the outer one of its name")
  (is (= [{:kind :unknown-column :column :nosuch}]
         (check {:with [[:users {:select [[1 :x]]}]] :update :users :set {:nosuch "x"} :where [:= :id 1]}))
      "the table an UPDATE names, which a CTE does not hide"))

(deftest a-write-postgresql-refuses-is-a-problem
  (let [nn (fn [col clause] {:kind :not-null-violation :table "sample.users" :column col :clause clause})
        ro (fn [col] {:kind :read-only-column :table "sample.users" :column col})]
    (testing "NULL into a NOT NULL column"
      (is (= [(nn :score :set)] (check {:update :users :set {:score nil} :where [:= :id 1]})))
      (is (= [(nn :group_id :values)] (check {:insert-into :users :values [{:group_id nil :mood [:cast "sad" :mood] :score 1 :closed_at 'now}]})))
      (is (= [(nn :group_id :values)] (check {:insert-into :users :columns [:group_id :mood :score] :values [[nil [:cast "happy" :mood] 1]]})))
      (is (= [(nn :score :do-update-set)] (check {:insert-into :users :values [{:group_id 1 :score 1}] :on-conflict [:id] :do-update-set {:score nil}})))
      (is (= [(nn :mood :values)] (check {:insert-into :users :values [{:group_id 1 :score 1} {:group_id 1 :score 1 :mood [:cast "happy" :mood]}]}))
          "HoneySQL writes NULL for a key one row of :values lacks and another has")
      (is (= [] (check {:update :users :set {:nick nil :score :?score} :where [:= :id 1]})) "a nullable column, an argument")
      (is (= [{:kind :uncast-string :column :mood :value "happy" :type "mood"}] (check {:insert-into :users :values [{:group_id 1 :score 1} {:group_id 1 :score 1 :mood "happy"}]}
                       (assoc opts :format {:values-default-columns #{:mood}})))
          "with sql/format's :values-default-columns the missing key is DEFAULT")
      (is (= [{:kind :uncast-string :column :mood :value "happy" :type "mood"}] (check {:insert-into :users :columns [:group_id :score :mood] :values [{:group_id 1 :score 1} {:group_id 1 :score 1 :mood "happy"}]}))
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
           (check q))
        (pr-str q)))
  (doseq [v [nil :null [:inline nil] [:lift nil]]]
    (is (= [] (check {:update :users :set {:nick v} :where [:= :id 1]})) (pr-str v))))

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
    (is (= [{:kind :unknown-column :column :nosuch}] (check q)) (pr-str q)))
  (doseq [op [:is :is-not]]
    (is (= [{:kind :unknown-column :column :u.nosuch :in [:where 1]}]
           (check {:select [:id] :from [[:users :u]]
                   :where [:exists {:select [1] :from [:groups] :where [op :u.nosuch nil]}]})))
    (is (= [] (check {:select [:id] :from [[:users :u]]
                      :where [:exists {:select [1] :from [:groups] :where [op :u.nick nil]}]})))))

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
  (is (= [] (check {:update :users :set {:nick :group_name} :where [:= :id 1]}))))

(deftest a-string-reaches-an-enum-column-as-varchar
  (let [check #(h/check registry %1 (merge opts %2))
        uncast (fn [v] {:kind :uncast-string :column :mood :value v :type "mood"})]
    (is (= [(uncast "sad")] (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {}))
        "pgjdbc sends a String as varchar, which no enum takes without a cast")
    (is (= [(uncast "sad")] (check {:update :users :set {:mood "sad"} :where [:= :id 1]} {})))
    (is (= [(uncast "sad") (uncast "happy")] (check {:select [:id] :from [:users] :where [:in :mood ["sad" "happy"]]} {})))
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood [:cast "sad" :mood]]} {})) "a cast is what it takes")
    (is (= [{:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {})) "or stringtype=unspecified")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {:format {:inline true}})) "or the value inlined")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood :?m]} {}))
        "an argument too is untyped under stringtype=unspecified")))

(deftest an-argument-cast-to-an-enum-is-of-the-enum
  (is (= {:m [:enum {:pg/type "mood"} "happy" "sad" :happy :sad]}
         (update-vals (args {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]}) m/form))))

(deftest kebab-and-camel-names-as-postgresql-folds-them
  (is (= [] (check {:insert-into :users :values [{:group-id 1 :score 1}]})) "HoneySQL writes :group-id as group_id")
  (is (= [] (check {:insert-into :users :columns [:group-id :score] :values [[1 1]]})))
  (is (= [] (check {:select [[:nick :the-nick]] :from [:users] :order-by [:the_nick]})) "an alias is folded too")
  (is (= [] (check {:select [[:nick :userNick]] :from [:users] :order-by [:usernick]})))
  (is (= [] (check {:select [[:mood :user-mood] [[:count :*] :n]] :from [:users] :group-by [:user_mood]})))
  (is (= [:user_id :usernick] (map first (drop 1 (row {:select [[:id :user-id] [:nick :userNick]] :from [:users]}))))
      "next.jdbc keys the row by the folded label"))

(deftest a-bare-keyword-clause-value-is-read
  (is (= [{:kind :unknown-column :column :nosuch}] (check {:select :nosuch :from :users})))
  (is (= [{:kind :unknown-column :column :nosuch}] (check {:select [:id] :from :users :order-by :nosuch})))
  (is (= [{:kind :unknown-column :column :nosuch}] (check {:update :users :set {:nick "a"} :returning :nosuch})))
  (is (= [:id] (map first (drop 1 (row {:select :id :from :users}))))))

(deftest an-unknown-column-names-the-columns-it-likely-meant
  (is (= [{:kind :unknown-column :column :nik :did-you-mean [:nick]}]
         (check {:select [:nik] :from [:users]})))
  (is (= [{:kind :unknown-column :column :u.scroe :did-you-mean [:u.score]}]
         (check {:select [:u.scroe] :from [[:users :u]]})))
  (is (= [{:kind :unknown-column :column :zzz}] (check {:select [:zzz] :from [:users]}))
      "nothing close, nothing suggested"))

(deftest describe-shows-the-problems-a-query-schema-carries
  (is (= [{:kind :unknown-column :column :nope}]
         (:pgmalli/problems (pgmalli/describe registry (h/query-schema registry [] {:select [:nope] :from [:users]} opts))))))

(deftest an-alias-is-the-name-honeysql-writes-under-the-format-options
  (let [q {:select [[:id :user-id]] :from [:users]}
        key #(ffirst (rest (row q (assoc opts :format %))))]
    (is (= :user_id (key {})))
    (is (= :user-id (key {:quoted true})))
    (is (= :user_id (key {:quoted true :quoted-snake true})) ":quoted-snake writes it with _")
    (is (= :user-id (key {:dialect :ansi})) "a dialect quotes")
    (is (= :user_id (key {:dialect :ansi :quoted false})))
    (is (= :%count.c (ffirst (rest (row {:select [[:%count.* :%count.c]] :from [:users]})))) "an alias is no column: its dots stay")))

(deftest a-table-of-another-schema-is-the-one-meant-when-schema-is-left-out
  (let [ps (h/check (pgmalli/registry "sample" "other") {:select [:id] :from [:users]} {:schema "other"})]
    (is (= [{:kind :unknown-table :table "other.users" :did-you-mean ["sample.users"]}] ps))
    (is (= ["unknown table other.users; sample.users exists; pass {:schema \"sample\"}, or name it :sample.users"] (h/humanize ps)))))

(deftest a-schema-the-registry-holds-nothing-of-is-an-error-naming-the-one-it-holds
  (let [q {:select [:id] :from [:users]}]
    (doseq [f [#(h/check registry q %) #(h/arg-types registry q %) #(h/row-schema registry q %) #(h/tables registry q %)
               #(h/params-schema registry q %) #(h/query-schema registry [] q %) #(h/values-schema registry :users %) #(h/set-schema registry :users %)]]
      (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^the registry holds no schema \"public\"; pass \{:schema \"sample\"\}$" (f {})))]
        (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^the registry holds no schema \"other\"; it holds \"a\", \"b\"$"
                            (h/check (pgmalli/registry {:registry {:pg.a/t [:map {:pg/table "a.t"} [:id :int]] :pg.b/t [:map {:pg/table "b.t"} [:id :int]]}}) q {:schema "other"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"; did you mean: \"sample\"$"
                            (h/check (pgmalli/registry "sample" "other") q {:schema "smaple"}))))
    (is (= [] (h/check {:app/x :int} {:select [1]})) "a registry of no generated schema names none")))

(deftest a-qualifier-no-table-or-alias-has-is-the-problem-with-the-alias-meant
  (is (= [{:kind :unknown-column :column :users.id :qualifier "users" :did-you-mean [:u.id]}]
         (check {:select [:users.id] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]]}))
      "an aliased table named by its name, as PostgreSQL hints")
  (is (= ["unknown column usr.id; no table or alias usr in the statement; did you mean: u.id"]
         (h/humanize (check {:select [:usr.id] :from [[:users :u]]}))))
  (is (= [{:kind :unknown-column :column :x.id :qualifier "x"}] (check {:select [:x.id] :from [:users]}))
      "no alias close: nothing suggested"))

(deftest each-problem-reads-as-what-to-do
  (is (= ["unknown column nik; did you mean: nick"
          "\"sad\" reaches column mood as a typed string, which its type mood does not take (HoneySQL sends a string as a parameter, which pgjdbc sends as varchar); write [:cast \"sad\" :mood]"]
         (h/humanize (check {:select [:nik] :from [:users] :where [:= :mood "sad"]} opts))))
  (is (every? string? (h/humanize [{:kind :something-new}])) "a kind with no sentence still prints"))

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
  (is (= [{:kind :unknown-column :column :t.name}] (check {:select [:t.name] :from [[[:unnest [:array [1 2]]] [:t {:columns [:x]}]]]}))
      "PostgreSQL reads t.name as name(t)"))

(deftest write-enum-names-what-an-extension-sends-for-an-enum
  (let [types #(update-vals (args % opts) m/form)]
    (is (= [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} :happy :sad]
           (:m (types {:select [:id] :from [:users] :where [:= :mood :?m]})))
        "the enum's labels kept, as keywords")
    (is (= [:enum {:pg/type "mood"} "happy" "sad" :happy :sad] (:m (types {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]})))
        "an argument cast to an enum, as strings too")))

(deftest keywords-sent-untyped-take-a-columns-labels
  (let [reg (pgmalli/registry {:registry {:pg.public/attempts [:map {:pg/table "public.attempts"}
                                                               [:id [:int {:pg/type "bigint"}]]
                                                               [:role [:string {:pg/type "character varying"}]]
                                                               [:status [:enum {:pg/constraints ["attempts_status_check"] :pg/type "character varying"} "active" "submitted"]]]}})
        o {}]
    (is (= {:status [:enum {:pg/constraints ["attempts_status_check"] :pg/type "character varying"} "active" "submitted" :active :submitted]}
           (update-vals (h/arg-types reg {:select [:id] :from [:attempts] :where [:= :status :?status]} o) m/form))
        "a column with labels keeps them, as keywords too")
    (is (= {:role [:or [:string {:pg/type "character varying"}] :keyword]}
           (update-vals (h/arg-types reg {:select [:id] :from [:attempts] :where [:= :role :?role]} o) m/form))
        "a column without labels takes keywords")))

(deftest review-round-1-honeysql
  (testing "a row is keyed by the column's name, not the spelling written"
    (is (= [:group_id] (map first (drop 1 (row {:select [:group-id] :from [:users]})))))
    (is (= [:group_id] (map first (drop 1 (row {:select [:GROUP_ID] :from [:users]}))))))
  (testing "quoted output keeps an alias as written"
    (is (= [:E :e-mail] (map first (drop 1 (row {:select [[:nick :E] [:nick :e-mail]] :from [:users]}
                                                 (assoc opts :format {:quoted true})))))))
  (testing "IS [NOT] DISTINCT FROM takes NULL"
    (is (= [:maybe [:or [:string {:pg/type "character varying"}] :keyword]]
           (m/form (:n (args {:select [:id] :from [:users] :where [:is-distinct-from :nick :?n]}))))))
  (testing "an argument reaching an enum type's column as a String"
    (is (= []
           (check {:select [:id] :from [:users] :where [:= :mood :?m]} opts)))
    (is (= []
           (check {:select [:id] :from [:users] :where [:= :mood :?m]} opts))
        "an extension sending a keyword as a String sends varchar all the same")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]} opts))))
  (testing ":join-by leaves its tables open"
    (is (= [{:kind :unread-clause :clause :join-by}]
           (check {:select [:u.nick :g.name] :from [[:users :u]] :join-by [:left [[:groups :g] [:= :g.id :u.group_id]]]}))))
  (testing "ROLLUP's total row is NULL in its grouped columns"
    (is (= [:maybe int4]
           (second (second (row {:select [:group_id [[:count :*] :n]] :from [:users] :group-by [[:rollup :group_id]]})))))))

(deftest a-row-is-nullable-where-a-join-or-grouping-pgmalli-does-not-read-may-null-it
  (let [nullable? (fn [q] (every? #(= :maybe (first (last %))) (drop 1 (row q))))]
    (is (nullable? {:select [:u.nick :g.name] :from [[:users :u]] :join-by [:right [[:groups :g] [:= :g.id 9]]]}))
    (is (nullable? {:select [:group_id] :from [:users] :group-by [[:raw "CUBE(group_id)"]]}))))

(deftest an-argument-no-use-types-is-any
  (is (= {:e [:any {:pgmalli/untyped :type}] :n [:maybe [:int {:pg/type "bigint" :min 0}]]} (args {:select [:id] :from [:users] :where [:= [:public.my-fn :nick] :?e] :offset :?n}))))

(deftest params-schema-is-sql-formats-params-map
  (let [body {:update :users :set {:nick :?nick} :where [:= :id :?id]}
        s (h/params-schema registry body opts)]
    (is (= (into [:map] (sort-by (comp str key) (args body))) s))
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
  (is (= [] (check {:select [:u.nick] :from [[:users "u"]]})))
  (is (= {:reads #{"sample.users"} :writes #{}} (h/tables registry {:select [:u.nick] :from [[:users "u"]]} opts)))
  (is (= {:reads #{"sample.users"} :writes #{}} (h/tables registry {:with [["c" {:select [:id] :from [:users]}]] :select [:id] :from [:c]} opts)))
  (is (= [{:kind :unknown-table :table "sample.c"}] (check {:with [["C" {:select [:id] :from [:users]}]] :select [:*] :from [:c]}))))

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
      (is (= [{:kind :uncast-string :column :born :value "2020-01-01" :type "date"}] (check {:select [:id] :from [:hosts] :where [:= :born "2020-01-01"]} {})) "sent untyped")
      (is (= [] (check {:select [:id] :from [:hosts] :where [:= :born :?b]} {})) "an argument arg-types types as a date"))
    (is (= [{:kind :uncast-string :column :ip :value "10.0.0.1" :type "inet"}] (check {:select [:id] :from [:hosts] :where [:= :ip "10.0.0.1"]} {})))
    (is (= [{:kind :uncast-string :column :flags :value "1010" :type "varbit"}] (check {:update :hosts :set {:flags "1010"}} {}))
        "bit takes varbit, which keeps the length")
    (is (= [{:kind :uncast-string :column :doc :value "{}" :type "jsonb"}] (check {:update :hosts :set {:doc "{}"}} {})))
    (is (= [] (check {:select [:id] :from [:hosts] :where [:= :flags :?f]} {})))
    (is (= [] (check {:select [:id] :from [:hosts] :where [:= :ip :?ip]} {})) "an argument arg-types leaves :any may be an object of the type")
    (is (= [] (check {:update :hosts :set {:mask :?m} :where [:= :id 1]} {}))
        "an argument assigned to a nullable column may be nil, which PostgreSQL takes")
    (is (= [] (check {:select [:id] :from [:hosts] :where [:and [:= :ip [:cast "10.0.0.1" :inet]] [:= :note "a"]]} {})))
    (is (= [] (check {:select [:id] :from [:hosts] :where [:= :ip "10.0.0.1"]} {:format {:inline true}})))))

(deftest truncate-names-tables
  (is (= [] (check {:truncate [:users :restart :identity]})))
  (is (= [{:kind :unknown-table :table "sample.nosuch"}] (check {:truncate [[:users :nosuch]]}))))

(deftest an-inlined-enum-literal-is-one-too
  (is (= [{:kind :enum-literal :column :mood :value "zz" :allowed ["happy" "sad"]}]
         (check {:select [:id] :from [:users] :where [:= :mood [:inline "zz"]]}))))

(deftest an-unknown-clause-of-the-query-is-unread
  (is (= [{:kind :unread-clause :clause :selct :did-you-mean [:select]}] (check {:selct [:id]}))))

(deftest explain-says-what-to-do-for-arity-and-unread-clauses
  (is (= [":values row 0 has 2 values for 3 :columns; give each row as many values as :columns"
          "clause :frob is not read by pgmalli, so what it does is not checked; check the clause name, or check that part by hand"]
         (h/humanize [{:kind :values-arity :table "sample.users" :row 0 :columns 3 :values 2} {:kind :unread-clause :clause :frob}]))))

(deftest a-quoted-sql-word-is-a-column
  (is (= [] (check {:select [:user :nick] :from [:users]})))
  (is (= [{:kind :unknown-column :column :user}] (check {:select [:user :nick] :from [:users]} (assoc opts :format {:quoted true}))))
  (is (nil? (row {:select [:user :nick] :from [:users]} (assoc opts :format {:quoted true})))))

(deftest a-string-select-alias-is-the-key
  (is (= [:nn] (map first (drop 1 (row {:select [[:nick "nn"]] :from [:users]} opts))))))

(deftest the-question-mark-operators-are-no-arguments
  (is (= [:id] (keys (args {:select [:id] :from [:users] :where [:and [:? :nick "a"] [:?| :nick :nick] [:?& :nick :nick] [:= :id :?id]]})))))

(deftest information-schema-is-opaque-as-pg-catalog
  (is (= [] (check {:select [:table_name] :from [:information_schema.tables]}))))

(deftest a-date-argument-takes-what-pgjdbc-sends
  (let [t (:b (args {:update :users :set {:born :?b} :where [:= :id 1]}))]
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
  (let [q #(h/check check-list-registry {:select [:id] :from [:t] :where %} {})]
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
  (let [args #(h/arg-types check-list-registry {:select [:id] :from [:t] :where %})
        text [:or text :keyword]]
    (is (= {:x [:enum
                {:pg/type "text" :pg/constraints ["t_state_check"]}
                "open"
                "closed"
                :open
                :closed]} (args [:= :state :?x])))
    (is (= {:x text} (args [:<> :state :?x])))
    (is (= {:x text} (args [:< :state :?x])))
    (is (= {:x (in-list text)}
           (args [:not-in :state :?x])))
    (is (= {:x [:enum {:pg/type "mood"} :ok :sad]} (h/arg-types check-list-registry {:select [:id] :from [:t] :where [:<> :m :?x]} {}))
        "an enum type keeps its labels")
    (is (= {:x (in-list [:enum {:pg/type "text" :pg/constraints ["t_state_check"]} "open" "closed" :open :closed])}
           (args [:in :state :?x])))
    (is (= {:lo text :hi text} (args [:between :state :?lo :?hi])))
    (is (= {:x [:maybe text]} (args [:is-distinct-from :state :?x])))
    (is (= {:x text} (args [:not [:= :state :?x]])) "NOT = is <>")
    (is (= {:x integer} (args [:<> :n :?x])) "an integer column's list, its base type")
    (is (= {:x [:enum {:pg/type "integer" :pg/constraints ["t_n_check"]} 1 2]} (args [:= :n :?x])))
    (is (= {:x [:enum {:pg/type "text" :pg/constraints ["t_state_check"]} "open" "closed" :open :closed] :y text}
           (h/arg-types check-list-registry {:update :t :set {:state :?x} :where [:<> :state :?y]}))
        "the write keeps the list, the comparison in the same statement does not")
    (is (= {:x text}
           (h/arg-types check-list-registry {:select [:id] :from [:t] :where [:<> :state :?x]} {}))
        "a keyword, sent untyped, compared with the base type")))

(deftest null-in-any-case-is-sql-null
  (is (= [{:kind :not-null-violation :table "sample.users" :column :score :clause :set}]
         (check {:update :users :set {:score :NULL} :where [:= :id 1]})))
  (is (= [] (check {:select [:id] :from [:users] :where [:is :nick :NULL]})) "not a column named NULL"))

(deftest not-negates-through-and-or-only-and-each-occurrence-on-its-own
  (let [q #(map :kind (h/check check-list-registry {:select [:id] :from [:t] :where %} {}))
        c [:= :state "archived"]]
    (is (= [:enum-literal] (q [:not [:case [:= :state "archived"] true :else false]])) "a CASE's condition is not negated")
    (is (= [:enum-literal] (q [:not [:= :id [:case [:= :state "archived"] 1 :else 2]]])))
    (is (= [:enum-literal] (q [:or c [:not c]])) "the same vector, once plain and once negated")
    (is (= [] (q [:not [:or [:= :state "archived"] [:= :id 1]]])))))

(deftest an-sql-word-in-any-case-names-its-column-as-postgresql-does
  (is (= [:map [:current_date [:time/local-date {:pg/type "date"}]]] (row {:select [:CURRENT_DATE] :from [:users]})))
  (is (= [:map [:?column? [:maybe :any]]] (row {:select [:NULL] :from [:users]}))))

(deftest a-check-lists-labels-go-as-keywords-assigned-too
  (let [s (:v (h/arg-types check-list-registry {:update :t :set {:state :?v}} {}))]
    (is (m/validate s :open {:registry check-list-registry}))
    (is (not (m/validate s :archived {:registry check-list-registry})))))

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
        ok? #(m/validate %1 %2 {:registry reg})]
    (is (= [] (h/check reg {:update :t :set {:r :?x}})) "a regclass takes a varchar argument")
    (is (ok? (:r (h/arg-types reg {:update :t :set {:re :?r}})) 1) "a real takes an integer")
    (is (ok? (:p (h/arg-types reg {:update :t :set {:p [:cast :?p :pos]}})) 0.6) "a fraction rounded to an integer domain's range")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key" (h/values-schema registry :users {:schema "sample" :nope 1})))
    (is (= [:map [:s [:string {:pg/type "character varying"}]]] (row {:select [["s" :s]] :from [:users]} {:schema "sample"}))
        "a String goes as varchar by default")
    (is (not (ok? (:d (h/arg-types reg {:update :t :set {:dl :?d}})) 1)) "1.5 is no integer label")
    (is (= {:x [:any {:pg/type "json"}]} (h/arg-types reg {:update :t :set {:j :?x}} {})))))

(deftest an-enum-domains-checks-stay-around-its-labels
  (let [reg (pgmalli/registry {:registry {:pg.public/mood [:enum "ok" "sad"]
                                          :pg.public/good [:and [:ref {:pg/type "mood"} :pg.public/mood] [:not= "ok"]]
                                          :pg.public/t [:map {:pg/table "public.t"} [:g [:ref {:pg/type "good"} :pg.public/good]]]}})
        t (:g (h/arg-types reg {:update :t :set {:g :?g}} {}))]
    (is (not (m/validate t "ok" {:registry reg})))))

(deftest each-rule-of-a-row-written-holds
  (is (m/validate (h/values-schema registry :pg.sample/users {:schema "sample"})
                  {:group_id 1 :score 0 :nick_upper :default} {:registry registry})
      "a column the database writes keyed as the others")
  (let [reg (pgmalli/registry {:registry {:pg.public/short [:string {:pg/type "character varying(10)" :max 5 :pg/max-length 10}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:c [:ref {:pg/type "short"} :pg.public/short]]]}})]
    (is (not (m/validate (:x (h/arg-types reg {:select [[[:cast :?x :short] :c]]})) "abcdef" {:registry reg})) "a cast keeps a CHECK's :max"))
  (is (m/validate (:x (args {:select [:id] :from [:users] :where [:= :group_id :?x]} {:schema "sample"})) (short 1) {:registry registry})
      "an integer column takes a smallint")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes a table as a keyword" (h/values-schema registry 'users {:schema "sample"}))))

(deftest a-java-array-goes-where-postgresql-takes-its-array
  (let [unnest {:insert-into [[:users [:group_id :score]] {:select [[:?g] [[:unnest :?xs]]]}]}
        any {:select [:id] :from [:users] :where [:= :score [:any :?xs]]}
        ok? #(m/validate (:xs (args %1 {:schema "sample"})) %2 {:registry registry})]
    (is (not (ok? unnest (into-array String ["1"]))) "a text is no integer, assigned")
    (is (ok? unnest (short-array [1])) "a smallint is")
    (is (not (ok? any (into-array String ["1"]))) "integer = text has no operator")))

(deftest a-row-schema-keys-as-each-format-writes-it-back
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:address_line :string]]}})
        keys #(written-keys (h/values-schema reg :t %))]
    (is (= [:address_line] (keys {})) "unquoted, HoneySQL writes - as _")
    (is (= [:address_line] (keys {:format {:quoted true :quoted-snake true}})) ":quoted-snake writes - back as _")
    (is (= [:address_line] (keys {:format {:dialect :ansi}})) "a dialect quotes names")
    (is (= [:address_line] (keys {:format {:quoted-always #"^address_line$"}})) ":quoted-always quotes the name HoneySQL writes, - already _"))
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:MixedCase :int]]}})
        keys #(map first (drop 2 (h/values-schema reg :t %)))]
    (is (= ["MixedCase"] (keys {})) "unquoted, only a string reaches a column with capitals")
    (is (= [:MixedCase] (keys {:format {:quoted true}})))))

(deftest the-same-query-and-options-give-equal-schemas
  (let [q {:update :users :set {:score [:cast :?x :integer] :nick :?n} :where [:= :id 1]}]
    (is (= (args q opts) (args q opts)) "a class's schema is one [:fn] per class")))

(deftest an-argument-of-a-resolving-function-takes-the-others-type
  (is (= {:x [:maybe bigint]} (args {:select [:id] :from [:users] :where [:> [:greatest :id :?x] 5]})))
  (is (= {:n [:maybe [:or [:string {:pg/type "character varying"}] :keyword]]} (args {:update :users :set {:nick [:coalesce :?n :nick]} :where [:= :id 1]})))
  (is (= {:x [:maybe bigint]} (args {:select [:id] :from [:users] :where [:= :id [:nullif :?x 0]]})))
  (is (= {:a [:any {:pgmalli/untyped :position}] :b [:any {:pgmalli/untyped :position}]} (args {:select [[[:least :?a :?b] :m]]})) "arguments alone resolve to nothing pgmalli tells"))

(deftest the-return-says-how-many-rows-the-data-tells
  (let [ret #(last (h/query-schema registry [:db] % (merge opts %2)))
        n [:map [:n bigint]]]
    (is (= [:sequential {:max 1 :min 1} n] (ret {:select [[[:count :*] :n]] :from [:users]} {})) "aggregates alone: one row")
    (is (= n (ret {:select [[[:count :*] :n]] :from [:users]} {:result :one})) "so execute-one! returns it")
    (is (= [:sequential n] (ret {:select [[[:count :*] :n]] :from [:users] :group-by [:mood]} {})) "a row per group")
    (is (= 1 (:max (second (ret {:select [:nick] :from [[:users :u]] :where [:and [:= :u.id :?id] [:> :score 1]]} {}))))
        "the primary key equal to a value: at most one")
    (is (not (map? (second (ret {:select [:nick] :from [:users] :where [:= :id [:+ :score 1]]} {}))))
        "a key equal to an expression of the row matches many")
    (is (= 5 (:max (second (ret {:select [:nick] :from [:users] :limit 5} {})))))
    (is (= {:max 1 :min 1} (second (ret {:select [[[:exists {:select [1] :from [:users] :where [:= :id :?id]}] :authorized]]} {})))
        "a SELECT without FROM: one row, EXISTS one value")
    (is (= {:max 1} (second (ret {:select [[1 :x]] :where [:= 1 :?n]} {}))) "WHERE may leave none")
    (is (not (map? (second (ret {:select [:u.nick] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]] :where [:= :u.id :?id]} {}))))
        "a join may repeat a row")
    (is (= :maybe (first (ret {:insert-into :users :values [{:group_id 1 :mood "sad" :score 1}] :returning [:id]} {:result :one})))
        "an INSERT returns up to a row for each of its rows: a trigger, a rule or ON CONFLICT may skip one")
    (is (= :maybe (first (ret {:insert-into :users :values [{:group_id 1 :mood "sad" :score 1}] :on-conflict [] :do-nothing true :returning [:id]} {:result :one})))
        "unless ON CONFLICT skips it")))

(deftest a-subquerys-or-ctes-columns-are-typed-by-its-select
  (let [recent {:with [[:recent {:select [:id :nick] :from [:users]}]]}]
    (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]] [:nick [:maybe [:string {:max 40 :pg/max-length 40 :pg/type "character varying"}]]]]
           (row (merge recent {:select [:id :nick] :from [:recent]}))))
    (is (= {:n [:or [:string {:pg/type "character varying"}] :keyword]} (args (merge recent {:select [:id] :from [:recent] :where [:= :nick :?n]}))))
    (is (= [{:kind :unknown-column :column :mail}] (check (merge recent {:select [:mail] :from [:recent]})))
        "a column its SELECT does not give is none")
    (is (= [:map [:id bigint]] (row {:with [[:a {:select [[[:cast :id :bigint] :id]] :from [:users]}] [:b {:select [:id] :from [:a]}]] :select [:id] :from [:b]}))
        "a CTE reads the ones before it")
    (is (= [:map [:id bigint]] (row {:with [[:a {:select [[[:cast :id :bigint] :id]] :from [:users]}]
                                            [:b {:with [[:c {:select [:id] :from [:a]} :materialized]] :select-distinct [:id] :from [:c]} :materialized]]
                                     :select [:id] :from [:b]}))
        "a CTE's body with a :with of its own sees the ones before it too")
    (is (= [] (check {:with [[[:c {:columns [:x]}] {:select [:id :nick] :from [:users]}]] :select [:x :nick] :from [:c]}))
        "a CTE's name may rename its columns, by position")
    (is (= [:map [:x bigint]] (row {:with [[[:c {:columns [:x]}] {:select [[[:cast :id :bigint] :id]] :from [:users]}]] :select [:x] :from [:c]})))
    (is (= [:map [:n bigint]] (row {:select [:s.n] :from [[{:select [:id [[:count :*] :n]] :from [:users] :group-by [:id]} :s]]})))
    (is (= [:map [:id [:maybe [:int {:pg/identity :always :pg/type "bigint"}]]]] (row {:with-recursive [[:t {:union-all [{:select [:id] :from [:users]} {:select [:id] :from [:t]}]}]] :select [:id] :from [:t]}))
        "a recursive CTE has its first member's types, NULL where later rows may hold it")
    (is (= [:map [:n bigint]] (row {:select [:x.n] :from [:users [[:lateral {:select [[[:cast :id :bigint] :n]] :from [:groups] :where [:= :groups.id :users.group_id]}] :x]]}))
        "a LATERAL subquery sees the tables before it")
    (is (= [] (check {:select [:x.n] :from [:users [[:lateral {:select [[:id :n]] :from [:groups] :where [:= :groups.id :users.group_id]}] :x]]})))))

(deftest an-unnest-tables-argument-is-an-array-of-where-its-column-goes
  (let [o opts
        ids [:pg/array bigint]]
    (is (= {:ids ids} (args {:select [:id] :from [:users] :join [[[:unnest :?ids] :i] [:= :users.id :i]]} o))
        "an unnest of one, named by its alias")
    (is (= {:ids ids} (args {:update :users :set {:nick "x"} :from [[[:unnest :?ids] [:x {:columns [:i]}]]] :where [:= :users.id :x.i]} o))
        "a column its alias lists")
    (is (= {:ids [:any {:pgmalli/untyped :position}]} (args {:select [:i] :from [[[:unnest :?ids] :i]]} o)) "a column that goes nowhere typed")))

(deftest a-json-builder-is-json-as-its-type-is-read
  (let [q {:select [[[:jsonb_build_object "a" :id] :o] [[:jsonb_agg :id] :a] [[:json_build_array :id] :b]] :from [:users]}]
    (is (= [:map
            [:o [:maybe [:map {:pg/type "jsonb"}]]]
            [:a [:maybe [:sequential {:pg/type "jsonb"} :any]]]
            [:b [:maybe [:sequential {:pg/type "json"} :any]]]]
           (row q))
        "parsed, an object or an array, which may be NULL: the catalog does not say when a function returns NULL")))

(deftest a-row-count-is-told-only-where-nothing-may-change-it
  (let [ret #(last (h/query-schema registry [:db] % (merge opts %2)))
        counted? #(map? (second (ret % {})))]
    (is (not (counted? {:select [[[:generate_series 1 3] :x]] :from [:users] :where [:= :id 1]}))
        "a set-returning function in the select list repeats a keyed row")
    (is (not (counted? {:select [:users.nick] :from [:users] :join-by [:join [[:users :u2] [:= :u2.group_id :users.group_id]]] :where [:= :users.id 1]})))
    (is (not (counted? '{:select [:groups.name] :from [:groups] :join (joins) :where [:= :groups.id 1]})) "code may join")
    (is (not (counted? {:with [[:users {:select [[:group_id :id]] :from [:sample.users]}]] :select [:id] :from [:users] :where [:= :id 1]}))
        "a CTE named as a table has none of its keys")
    (is (not (counted? {:select [:nick] :from [:users] :where [:= :id 1] :order-by [[[:generate_series 1 3]]]})) "in ORDER BY too")
    (is (not (counted? {:select [[[:count :*] :n]] :from [:users] :order-by [[[:generate_series 1 3]]]})))
    (is (not (counted? {:insert-into :users :values [{:group_id [:generate_series 1 3] :mood "sad" :score 1}] :returning [:id]})) "and in :values")
    (doseq [q [{:limit :?n} {:limit [:inline 0]} {:fetch 0}]]
      (is (= :maybe (first (ret (merge {:select [[[:count :*] :n]] :from [:users]} q) {:result :one}))) (pr-str q)))))

(deftest an-unnest-into-a-nullable-column-takes-nil-elements
  (let [t (:n (args {:insert-into [[:users [:group_id :mood :score :nick]] {:select [[:?g] [[:cast :?m :mood]] [:?s] [[:unnest :?n]]]}]}
                    opts))]
    (is (m/validate t (into-array String ["a" nil]) {:registry registry}))))

(deftest a-subquery-reading-a-cte-is-read-under-it
  (is (= {:n integer} (args {:with [[:users {:select [[[:cast 1 :integer] :nick]]}]] :select [:s.nick] :from [[{:select [:nick] :from [:users]} :s]] :where [:= :s.nick :?n]}))
      "users there is the CTE, which the subquery on its own would read as the table"))

(deftest an-insert-selects-arguments-are-typed-in-its-selects-scope
  (is (= {:x [:maybe integer]} (args {:insert-into [[:users [:score]] {:select [[[:coalesce :?x :id]]] :from [:groups]}]}))))

(deftest the-same-query-in-another-key-order-has-the-same-types
  (let [q1 (array-map :where [:= :id {:select [:id] :from [:users] :where [:= :id :?x]}] :select [[{:select [:nick] :from [:users] :where [:= :nick :?x]} :n]] :from [:users])
        q2 (array-map :select (:select q1) :where (:where q1) :from [:users])]
    (is (= (args q1) (args q2)) "the first use is the first in the SQL"))
  (let [q1 (array-map :where [:= :id [:cast :?x :integer]] :select [[[:cast :?x :text] :t]] :from [:users])
        q2 (array-map :select (:select q1) :where (:where q1) :from [:users])]
    (is (= (args q1) (args q2)) "casts too")))

(deftest a-key-is-unique-only-over-the-whole-table
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/primary-key ["id"] :pg/unique [{:columns ["e"] :where true}]}
                                                        [:id [:int {:pg/type "integer"}]] [:e [:string {:pg/type "text"}]]]
                                          :pg.public/p [:map {:pg/table "public.p" :pg/primary-key ["id"] :pg/inherited true} [:id [:int {:pg/type "integer"}]]]}})
        ret #(last (h/query-schema reg [:db] %))]
    (is (= {:max 1} (second (ret {:select [:id] :from [:t] :where [:= :id 1]}))))
    (is (not (map? (second (ret {:select [:id] :from [:t] :where [:= :e "a"]})))) "a partial UNIQUE index holds over some rows")
    (is (not (map? (second (ret {:select [:id] :from [:p] :where [:= :id 1]})))) "a table inheriting it may hold the same key")
    (is (not (map? (second (ret {:select [:id :%generate_series.1.3] :from [:t] :where [:= :id 1]})))) ":%f.a is a function")
    (let [reg (pgmalli/registry {:registry {:pg.public/d [:map {:pg/table "public.d" :pg/primary-key ["id"] :pg/primary-key-deferrable true} [:id [:int {:pg/type "integer"}]]]}})]
      (is (not (map? (second (last (h/query-schema reg [:db] {:select [:id] :from [:d] :where [:= :id 1]}))))) "a DEFERRABLE key holds at commit alone"))))

(deftest an-unknown-table-names-the-one-meant
  (is (= [{:kind :unknown-table :table "sample.usres" :did-you-mean ["sample.users"]}] (check {:select [:id] :from [:usres]})))
  (is (= ["unknown table sample.usres; did you mean: sample.users"] (h/humanize (check {:select [:id] :from [:usres]})))))

(deftest a-misspelt-option-names-the-one-meant-in-its-data
  (let [d (ex-data (try (h/check registry {:select [:id] :from [:users]} {:jdcb {}}) (catch clojure.lang.ExceptionInfo e e)))]
    (is (= [:jdbc] (:did-you-mean d)))
    (is (= [:jdcb] (:unknown d)))))

(deftest a-column-a-before-trigger-may-fill-takes-nil-or-nothing
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:insert ["fill"] :update ["fill"]}}
                                                        [:id [:int {:pg/type "integer"}]] [:slug [:string {:pg/type "text"}]]]}})]
    (is (m/validate (h/values-schema reg :t) {:id 1} {:registry reg}))
    (is (m/validate (h/values-schema reg :t) {:id 1 :slug nil} {:registry reg}))
    (is (m/validate (h/set-schema reg :t) {:slug nil} {:registry reg}))
    (is (= :maybe (first (:s (h/arg-types reg {:insert-into :t :values [{:id 1 :slug :?s}]})))))))

(deftest an-argument-assigned-to-an-unknown-table-is-any
  (is (= [:any {:pgmalli/untyped :type}] (:n (args {:update :nosuch :set {:nick :?n} :where [:= :id :?id]})))))

(deftest a-numeric-with-a-precision-takes-an-integer-assigned
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:n [:and {:pg/type "numeric"} 'decimal? [:pg/numeric {:precision 6 :scale 2}]]]]}})]
    (doseq [q [{:insert-into :t :values [{:n :?v}]} {:update :t :set {:n :?v}}]
            v [12 (int 12) 12N 12.5M]]
      (is (m/validate (:v (h/arg-types reg q)) v {:registry reg}) (pr-str q v)))
    (is (m/validate (h/values-schema reg :t) {:n 12} {:registry reg}))))

(deftest an-array-column-takes-a-narrower-numbers-typed-array
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]]
                                                        [:na [:maybe [:vector {:pg/type "numeric[]"} [decimal? {:pg/type "numeric"}]]]]
                                                        [:da [:maybe [:vector {:pg/type "double precision[]"} [:double {:pg/type "double precision"}]]]]]}})
        o {}]
    (doseq [c [:na :da]]
      (is (m/validate (:v (h/arg-types reg {:update :t :set {c :?v} :where [:= :id 1]} o)) (long-array [1 2]) {:registry reg}) (name c)))))

(deftest a-written-row-takes-no-keyword-honeysql-would-write-as-a-column
  (let [s (h/values-schema registry :users opts)]
    (is (not (m/validate s {:group_id 1 :score 1 :mood :sad} {:registry registry})))
    (is (not (m/validate s {:group_id 1 :score 1 :mood "sad"} {:registry registry})) "a String goes as varchar, which an enum column refuses")
    (is (m/validate s {:group_id 1 :score 1 :mood [:lift :sad]} {:registry registry}) "a keyword label as one parameter, untyped")))

(deftest an-argument-takes-nil-where-any-use-does
  (is (= {:v [:maybe bigint]} (args {:select [:id] :from [:users] :where [:or [:= :id :?v] [:is-distinct-from :id :?v]]})))
  (is (= {:v [:maybe bigint]} (args {:select [:id] :from [:users] :where [:or [:is-distinct-from :id :?v] [:= :id :?v]]}))
      "whatever the order"))

(deftest an-enum-arrays-argument-is-an-array-of-the-enum-alone
  (let [reg (pgmalli/registry {:registry {:pg.public/feel [:enum "a" "b"]
                                          :pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]]
                                                        [:fs [:maybe [:vector {:pg/type "feel[]"} [:ref {:pg/type "feel"} :pg.public/feel]]]]]}})
        t (:v (h/arg-types reg {:update :t :set {:fs :?v} :where [:= :id 1]}))]
    (is (= [:any {:pgmalli/untyped :type}] t) "no Java array goes as feel[]: a String[] goes as varchar[], which feel[] does not take, a vector as jsonb")))

(deftest a-written-row-takes-no-vector-honeysql-would-write-as-a-row
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]]
                                                        [:tags [:maybe [:vector {:pg/type "text[]"} [:string {:pg/type "text"}]]]]]}})
        s (h/values-schema reg :t)]
    (is (not (m/validate s {:id 1 :tags ["a"]} {:registry reg})))
    (is (m/validate s {:id 1 :tags (into-array String ["a"])} {:registry reg}))))

(deftest a-written-row-takes-values-and-lift-alone-no-expression
  (let [v (h/values-schema registry :users opts)
        s (h/set-schema registry :users opts)
        ok? #(m/validate %1 %2 {:registry registry})
        row {:group_id 1 :score 1}]
    (is (every? #(ok? v (merge row %)) [{:score [:lift 1]} {:nick [:lift nil]} {:mood [:lift :sad]} {:mood :default}])
        "[:lift v] is v as one parameter, an enum label as a keyword")
    (is (not-any? #(ok? v (merge row %)) [{:mood [:cast "sad" :mood]} {:closed_at [:now]} {:score [:raw "1"]} {:score '(:abs -1)}
                                           {:closed_at :current_timestamp} {:score :seq}])
        "an expression, an SQL word or a column belongs in the query body")
    (is (not-any? #(ok? s %) [{:score [:+ :score 1]} {:score :seq} {:score {:select [1]}}]))
    (is (not-any? #(ok? v (merge row %)) [{:score [:lift "1"]} {:mood [:lift "glad"]} {:score [1 2]} {:score '(1 2)} {:score [:default]}])
        "[:lift v] holds v to the column's type; a vector not headed by :lift a row of parameters; DEFAULT where the database fills nothing")
    (is (= {:mood ["should be an org.postgresql.util.PGobject" "should be either :default or [:default]" "should be [:lift v], v as the column takes it"]}
           (me/humanize (m/explain v (assoc row :mood "glad") {:registry registry}))))
    (is (not-any? (fn [r] (some #(and (sequential? %) (not= [:default] %)) (vals r)))
                  (concat (mg/sample v {:registry registry :seed 1 :size 30}) (mg/sample s {:registry registry :seed 1 :size 30})))
        "generated as the values a column takes")))

(deftest an-enum-column-takes-its-labels-as-keywords-not-as-strings
  (let [o opts
        q {:select [:id] :from [:users] :where [:= :mood :?m]}
        ok? #(m/validate %1 %2 {:registry registry})]
    (is (not (ok? (:m (args q o)) "sad")) "pgjdbc sends a String as varchar, which PostgreSQL neither compares with nor assigns to an enum")
    (is (not (ok? (h/set-schema registry :users o) {:mood "sad"})))
    (is (= [] (check q o)) "an argument is arg-types'")
    (is (= [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} :happy :sad]
           (:m (args q o))) "a keyword sent untyped as the label it names")
    (is (ok? (:x (args {:select [[[:cast :?x :mood] :m]]} o)) "sad") "PostgreSQL casts a varchar to an enum")))

(deftest a-class-sent-as-text-is-held-to-no-check-on-text
  (let [reg (pgmalli/registry {:registry {:pg.public/roles [:map {:pg/table "public.roles"}
                                                            [:k [:and {:pg/constraints ["f"] :pg/type "character varying"} [:string {:max 255 :pg/type "character varying"}] [:re "^[a-z0-9-]+$"]]]
                                                            [:n [:and {:pg/type "numeric"} 'decimal? [:> 0]]]]}})
        a (h/arg-types reg {:insert-into :roles :values [{:k :?k :n :?n}]})]
    (is (m/validate (:k a) :store-manager {:registry reg}) "the text an application makes of a keyword is its own")
    (is (not (m/validate (:k a) "Bad!" {:registry reg})) "a string is held to the CHECK")
    (is (not (m/validate (:n a) -3 {:registry reg})) "a number to a number column's CHECK, whatever its class")))

(deftest a-boxed-or-other-java-array-is-typed-by-its-elements
  (is (m/validate (:ids (args {:select [:id] :from [:users] :where [:= :id [:any :?ids]]})) (into-array Long [1 2]) {:registry registry})
      "pgjdbc sends a Long[] as a bigint[]"))

(deftest a-domains-array-argument-takes-its-base-types-typed-array
  (let [reg (pgmalli/registry {:registry {:pg.public/d_int [:pg/integer {:pg/type "integer"}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]] [:di [:maybe [:ref {:pg/type "d_int"} :pg.public/d_int]]]]}})]
    (is (m/validate (:v (h/arg-types reg {:select [:id] :from [:t] :where [:= :di [:any :?v]]})) (int-array [1 2]) {:registry reg}))))

(deftest a-case-resolves-its-values-as-coalesce-does
  (is (= [:map [:c text]] (row {:select [[[:case [:= :id 1] [:inline "a"] :else [:inline "b"]] :c]] :from [:users]})))
  (is (= [:map [:c [:maybe text]]] (row {:select [[[:case [:= :id 1] [:inline "a"]] :c]] :from [:users]})) "NULL without :else")
  (is (= [:map [:n bigint]] (row {:select [[[:coalesce [:max :group_id] 0] :n]] :from [:users]}))
      "an integer and a bigint resolve to the bigint"))

(deftest a-ctes-columns-are-typed-inside-expressions
  (is (= [:map [:m [:maybe int4]]]
         (row {:with [[:s {:update :users :set {:score 1} :returning [:score]}]] :select [[[:max :score] :m]] :from [:s]}))))

(deftest a-key-compared-with-null-matches-at-most-one-row
  (is (= {:max 1} (second (last (h/query-schema registry [:db] {:select [:nick] :from [:users] :where [:= :id nil]} opts))))))

(deftest trim-names-its-column-btrim
  (is (= [] (check {:select [:s.btrim] :from [[{:select [[[:trim :nick]]] :from [:users]} :s]]}))))

(deftest an-alias-list-renames-the-first-columns-alone
  (is (= [] (check {:select [:u.nick :u.a] :from [[:users [:u {:columns [:a]}]]]})))
  (is (= [] (check {:select [:s.nick] :from [[{:select [:id :nick] :from [:users]} [:s {:columns [:a]}]]]}))))

(deftest a-set-operation-under-with-recursive-reading-no-cte-resolves-its-members
  (is (not= [:map [:m [:maybe int4]]]
            (row {:with-recursive [[:c {:union-all [{:select [[[:cast :id :integer] :n]] :from [:users]} {:select [[[:cast :id :numeric] :n]] :from [:users]}]}]]
                  :select [[[:max :n] :m]] :from [:c]}))))

(deftest an-inline-or-lifted-value-is-typed-as-honeysql-writes-it
  (is (= [:map [:i int4] [:b int4]] (row {:select [[[:inline 1M] :i] [[:inline 1N] :b]]})))
  (is (= [:map [:l [:maybe :any]]] (row {:select [[[:lift :nick] :l]] :from [:users]})) "a keyword lifted is a parameter, no column"))

(deftest a-resolved-value-with-a-typed-argument-is-not-held-to-the-others-type
  (is (= [:map [:c [:maybe :any]]] (row {:select [[[:case [:= :id 1] :group_id :else :?p] :c]] :from [:users]}))
      "pgjdbc sends a Long as bigint and a Double as double precision, which widen it")
  (is (= [:map [:c [:maybe :any]]] (row {:select [[[:greatest :group_id :?p] :c]] :from [:users]}))))

(deftest a-case-is-named-after-its-else
  (is (= [:nick] (map first (rest (row {:select [[[:case [:= :id 1] "a" :else :nick]]] :from [:users]})))))
  (is (= [:case] (map first (rest (row {:select [[[:case [:= :id 1] "a" :else "b"]]] :from [:users]}))))))

(deftest an-unnest-tables-column-assigned-by-update-or-cast-types-its-argument
  (is (= {:ids [:pg/array bigint] :ss [:pg/array int4]}
         (args {:update :users :set {:score :r.s} :from [[[:unnest :?ids :?ss] [:r {:columns [:i :s]}]]] :where [:= :users.id :r.i]} opts)))
  (is (m/validate (:ns (args {:update :users :set {:nick [:cast :r.n :text]} :from [[[:unnest :?ids :?ns] [:r {:columns [:i :n]}]]] :where [:= :users.id :r.i]} opts))
                  (into-array String ["a"]) {:registry registry})
      "an array of what casts to the type"))

(deftest a-numeric-or-domain-array-column-takes-a-narrower-numbers-array-assigned
  (let [reg (pgmalli/registry {:registry {:pg.public/dnum [:and {:pg/type "numeric(10,2)"} 'decimal? [:> 0]]
                                          :pg.public/z [:map {:pg/table "public.z"} [:id [:int {:pg/type "integer"}]]
                                                        [:nn [:maybe [:vector {:pg/type "numeric[]"} 'decimal?]]]
                                                        [:dn [:maybe [:vector {:pg/type "dnum[]"} [:ref {:pg/type "dnum"} :pg.public/dnum]]]]]}})]
    (doseq [c [:nn :dn]]
      (is (m/validate (:p (h/arg-types reg {:update :z :set {c :?p} :where [:= :id 1]})) (long-array [1]) {:registry reg}) (name c)))))

(deftest an-array-compared-takes-elements-beyond-the-columns-length
  (let [reg (pgmalli/registry {:registry {:pg.public/z [:map {:pg/table "public.z"} [:id [:int {:pg/type "integer"}]]
                                                        [:vs [:maybe [:vector {:pg/type "character varying[]"} [:string {:max 5 :pg/max-length 5 :pg/type "character varying"}]]]]]}})]
    (is (m/validate (:p (h/arg-types reg {:select [:id] :from [:z] :where [:= :vs :?p]})) (into-array String ["abcdefg"]) {:registry reg}))))

(deftest a-domains-value-is-sent-as-its-base-types
  (let [reg (pgmalli/registry {:registry {:pg.public/dn [:and {:pg/type "numeric"} 'decimal? [:> 0]]
                                          :pg.public/dd [:time/local-date {:pg/type "date"}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]]
                                                        [:n [:ref {:pg/type "dn"} :pg.public/dn]]
                                                        [:ds [:maybe [:vector {:pg/type "dd[]"} [:ref {:pg/type "dd"} :pg.public/dd]]]]]}})]
    (is (m/validate (:x (h/arg-types reg {:select [:id] :from [:t] :where [:= :n :?x]})) 1N {:registry reg}) "a BigInt as a numeric")
    (is (not (m/validate (:x (h/arg-types reg {:update :t :set {:n :?x} :where [:= :id 1]})) -1N {:registry reg})) "held to the domain's CHECK")))

(deftest a-domain-arrays-elements-read-as-the-drivers-objects
  (let [reg (pgmalli/registry {:registry {:pg.public/posint [:int {:pg/type "integer" :min 1}]
                                          :pg.public/t [:map {:pg/table "public.t"} [:ps [:vector {:pg/type "posint[]"} [:ref {:pg/type "posint"} :pg.public/posint]]]]}})]
    (is (m/validate (pgmalli/as-read reg :pg.public/t)
                    {:ps [(Object.)]} {:registry reg})
        "pgjdbc returns a domain's elements as PGobjects")))

(deftest a-with-ordinality-table-function-is-named-by-its-alias
  (is (= [] (check {:select [:r.value :r.ordinality] :from [:users [[:with-ordinality [:jsonb_array_elements :users.nick] :r]]]}))))

(deftest a-jdbc-key-close-to-one-pgmalli-reads-is-an-error-unless-next-jdbc-reads-it
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^pgmalli options: :jdbc's :return-key is no next.jdbc option; did you mean: :return-keys$"
                                (row {:select [:id] :from [:users]} (assoc opts :jdbc {:return-key true}))))]
    (is (= [:return-keys] (:did-you-mean (ex-data e)))))
  (is (some? (row {:select [:id] :from [:users]} (assoc opts :jdbc {:fetch-size 10 :timeout 5 :my-own 1})))
      "next.jdbc's other options and an application's own pass"))

(deftest a-misspelt-clause-names-the-clause-meant
  (let [ps (check {:selec [:id] :from [:users]})]
    (is (= [{:kind :unread-clause :clause :selec :did-you-mean [:select]}] ps))
    (is (= ["clause :selec is no clause HoneySQL or pgmalli knows; did you mean: :select"] (h/humanize ps))))
  (is (= [{:kind :unread-clause :clause :frobnicate}] (check {:select [:id] :from [:users] :frobnicate [:x]}))
      "nothing close, nothing suggested"))

(deftest current-schema-and-system-user-may-be-null
  ;; CURRENT_SCHEMA is NULL under an empty search_path, SYSTEM_USER under trust authentication (docs 9.27)
  (is (= [:map [:s [:maybe [:string {:pg/type "name"}]]] [:u [:maybe [:string {:pg/type "text"}]]] [:c [:string {:pg/type "name"}]]]
         (row {:select [[:current_schema :s] [:system_user :u] [:current_catalog :c]]}))))

(defn- generated-on
  "The sample registry, its file generated on PostgreSQL version v."
  [v]
  (pgmalli/registry (assoc (pgmalli/generated "sample") :database-version v)))

(deftest a-pg-catalog-column-not-in-every-version-the-registry-is-of-is-optional
  (let [frozen #(some (fn [e] (when (= :relallfrozen (first e)) e)) (rest (h/row-schema % {:select [:*] :from [:pg_class]} opts)))]
    (is (= [:relallfrozen {:optional true} int4] (frozen registry)) "a file of no version known: any version the catalog holds")
    (is (= [:relallfrozen int4] (frozen (generated-on "PostgreSQL 18"))))
    (is (nil? (frozen (generated-on "PostgreSQL 17"))))
    (is (= [{:kind :unknown-column :column :relallfrozen}]
           (h/check (generated-on "PostgreSQL 17") {:select [:relallfrozen] :from [:pg_class]} opts)))
    (is (= [] (check {:select [:relallfrozen] :from [:pg_class]})))))

(deftest a-function-of-the-version-the-registry-is-of-alone-is-typed-there
  (let [q {:select [[[:uuidv7] :u] [[:casefold [:inline "a"]] :c]]}]
    (is (= [:map [:u [:maybe [:uuid {:pg/type "uuid"}]]] [:c [:maybe [:string {:pg/type "text"}]]]]
           (h/row-schema (generated-on "PostgreSQL 18") q opts)))
    (is (= [:map [:u [:maybe :any]] [:c [:maybe :any]]] (row q)) "PostgreSQL 16 and 17 have neither")))

(deftest functions-and-operators-have-the-type-pg-catalog-resolves-them-to
  (let [row #(h/row-schema registry {:select (vec (for [[k e] %] [e k])) :from [:users]} opts)
        day [:cast [:inline "1 day"] :interval]]
    (is (= [:map
            [:i [:any {:pg/type "interval"}]]
            [:w [:maybe [:any {:pg/type "interval"}]]]
            [:later [:maybe [:time/instant {:pg/type "timestamptz"}]]]
            [:ago [:maybe [:time/instant {:pg/type "timestamp with time zone"}]]]
            [:user [:string {:pg/type "name"}]]
            [:sys [:maybe [:string {:pg/type "text"}]]]] (row {:i [:interval "1 day"] :w [:* 7 day] :later [:+ :closed_at [:cast [:inline "7 days"] :interval]]
                     :ago [:- :current_timestamp day] :user :current_user :sys :system_user}))
        "INTERVAL '1 day'; a bigint times an interval; timestamptz plus or minus an interval")
    (is (= [:map
            [:epoch [:maybe [:double {:pg/type "double precision"}]]]
            [:nicks [:vector {:pg/type "text[]"} [:maybe :string]]]
            [:ids [:vector {:pg/type "bigint[]"} [:maybe :int]]]
            [:l [:maybe [:string {:pg/type "text"}]]]
            [:ts [:maybe [:vector {:pg/type "text[]"} [:maybe :string]]]]
            [:c [:maybe [:string {:pg/type "text"}]]]
            [:d [:double {:pg/type "double precision"}]]
            [:minutes [:maybe [:double {:pg/type "double precision"}]]]
            [:p [:maybe [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]
            [:none [:vector {:pg/type "text[]"} [:maybe :string]]]] (row {:epoch [:date_part [:inline "epoch"] [:- [:now] :closed_at]]
                     :minutes [:/ [:coalesce [:date_part [:inline "epoch"] [:- [:now] :closed_at]] 0] 60]
                     :d [:cast :score :double-precision] :ts [:cast :nick (keyword "text[]")]
                     :none [:array [] :text] :nicks [:array [:nick] :text] :ids [:array [:id :score]]
                     :l [:lower :nick] :p [:position "a" :nick] :c [:concat :nick 1]}))
        "date_part of an interval is a double precision; ARRAY[]::text[]; ARRAY of the elements' common type")
    (is (= [:map
            [:n [:int {:pg/type "bigint"}]]
            [:m [:maybe [:time/instant {:pg/type "timestamptz"}]]]
            [:s [:maybe [:int {:pg/type "bigint"}]]]
            [:a [:maybe [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]
            [:b [:maybe [:boolean {:pg/type "boolean"}]]]] (h/row-schema registry {:select [[[:filter [:count :*] {:where [:= :id 1]}] :n] [[:filter [:min :closed_at] {:where [:= :id 1]}] :m]
                                                [[:sum :score] :s] [[:avg :id] :a] [[:bool_or [:> :score 1]] :b]]
                                       :from [:users]} opts))
        "an aggregate with FILTER has the aggregate's type; count is never NULL")
    (is (= [:map
            [:id [:int {:pg/identity :always :pg/type "bigint"}]]
            [:rn [:int {:pg/type "bigint"}]]
            [:prev [:maybe [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]] (h/row-schema registry {:select [:id [[:over [[:row_number] {:partition-by [:group_id]} :rn] [[:lag :score] {} :prev]]]] :from [:users]} opts))
        "a window function: row_number is a bigint")
    (is (= [:map
            [:value [:maybe [:any {:pg/type "jsonb"}]]]
            [:ordinality [:int {:pg/type "bigint"}]]
            [:g [:maybe [:int {:pg/type "bigint"}]]]] (h/row-schema registry {:select [:e.value :e.ordinality :g]
                                       :from [[[:with-ordinality [:jsonb_array_elements [:cast :nick :jsonb]] :e]]
                                              [[:generate_series 1 3] :g]]}
                             opts))
        "a function's columns in FROM, of no table")
    (is (= {:max 1} (-> (h/query-schema registry [:id] {:select [[[:lower :nick] :l]] :from [:users] :where [:= :id :?id]} opts) last second))
        "a function that returns no set keeps one row")))

(deftest a-values-list-is-of-its-columns-common-types
  (is (= [:map
          [:column1 [:double {:pg/type "double precision"}]]
          [:column2 [:maybe [:string {:pg/type "text"}]]]] (h/row-schema registry {:select [:*] :from [[{:values [[1 [:inline "a"]] [2.5 nil]]} :v]]} opts))))

(deftest a-schemas-own-functions-and-operators-resolve-with-pg-catalogs
  (let [row #(h/row-schema registry {:select (vec (for [[k e] %] [e k])) :from [:users]} opts)
        both (pgmalli/registry "sample" "other")]
    (is (= [:map [:d [:maybe [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]] (row {:d [:double_it :score]})))
    (is (= [:map [:r [:maybe [:string {:pg/type "text"}]]]] (row {:r [:round :score]}))
        "an exact match of the schema's beats pg_catalog's round(double precision), which an integer converts to")
    (is (= [:map [:l [:maybe [:string {:pg/type "text"}]]]] (row {:l [:lower :nick]}))
        "pg_catalog comes first on the search path: the schema's lower(text) gives way to its")
    (is (= [:map [:u [:maybe [:string {:pg/type "text"}]]]] (row {:u [:upper :mood]})) "an overload taking the schema's enum type")
    (is (= [:map [:m [:maybe [:enum {:pg/type "mood"} "happy" "sad"]]]] (row {:m [:pick_mood]})) "a result of the schema's enum type")
    (is (= [:map [:o [:maybe [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} "happy" "sad"]]]] (row {:o [:+ :mood [:inline 1]]}))
        "an operator of the schema's, of its enum type as its operand's")
    (is (= [:map
            [:uid [:maybe [:int {:pg/type "bigint"}]]]
            [:feel [:maybe [:enum {:pg/type "mood"} "happy" "sad"]]]] (h/row-schema registry {:select [:*] :from [[[:user_rows] :r]]} opts))
        "RETURNS TABLE: its columns, the last argument left to its default")
    (is (= [:map [:n [:maybe [:int {:pg/type "bigint"}]]]] (h/row-schema both {:select [[[:other.note_count] :n]]} opts))
        "a function qualified with its schema")
    (is (= [:map [:n [:maybe :any]]] (h/row-schema both {:select [[[:note_count] :n]]} opts))
        "a schema off the search path (:schema) is not searched")
    (is (= {:x [:maybe [:int {:pg/type "integer"}]]} (args {:select [[[:double_it :?x] :d]] :from [:users]})))
    (is (map? (second (last (h/query-schema registry [:db] {:select [[[:double_it :score] :d]] :from [:users] :where [:= :id 1]} opts))))
        "a function of the schema's that returns no set keeps the row count")
    (is (not (map? (second (last (h/query-schema registry [:db] {:select [[[:user_rows] :d]] :from [:users] :where [:= :id 1]} opts)))))
        "one that returns a set does not")))

(deftest a-schemas-own-function-is-nullable-and-of-its-declared-type-alone
  (let [reg (pgmalli/registry (update (pgmalli/generated "sample") :functions assoc
                                      "my_rank" [{:args [] :result "bigint" :kind :w}]
                                      "jsonb_object" [{:args ["integer"] :result "jsonb"}]))]
    (is (= [:map [:r [:maybe [:int {:pg/type "bigint"}]]]]
           (h/row-schema reg {:select [[[:over [[:my_rank] {} :r]]]] :from [:users]} opts))
        "a window function of no argument of the schema's may return NULL; row_number never does")
    (is (= [:map [:o [:maybe [:any {:pg/type "jsonb"}]]]]
           (h/row-schema reg {:select [[[:jsonb_object :score] :o]] :from [:users]} opts))
        "a json builder's name of the schema's says nothing of the shape it returns")))

(deftest some-of-an-argument-is-typed-as-any-of-it
  (let [any (args {:select [:id] :from [:users] :where [:= :id [:any :?ids]]})]
    (is (some? (second (first any))))
    (is (= any (args {:select [:id] :from [:users] :where [:= :id [:some :?ids]]})))))

(deftest a-set-operation-resolves-its-members-two-at-a-time-from-the-left
  (is (= [:map [:a [:maybe :any]]] (row {:union [{:select [[nil :a]]} {:select [[nil :a]]} {:select [[[:inline 1] :a]]}]}))
      "NULL UNION NULL is text, which PostgreSQL does not unite with an integer")
  (is (= [:map [:a [:maybe int4]]] (row {:union [{:select [[nil :a]]} {:select [[[:inline 1] :a]]} {:select [[nil :a]]}]}))))

(deftest an-untyped-column-of-pg-catalog-or-of-a-function-may-be-null
  (let [o opts]
    (is (= [:map [:relacl [:maybe :any]]] (row {:select [:relacl] :from [:pg_class]} o)))
    (is (= [:map [:client_addr [:maybe [:any {:pg/type "inet"}]]]]
           (row {:select [:client_addr] :from [[[:pg_stat_get_activity nil] :k]]} o)))))

(deftest a-call-of-a-types-name-no-function-takes-the-argument-of-is-a-cast
  (let [qualified opts]
    (doseq [f [:int4 :pg_catalog.int4]]
      (is (= (row {:select [[[:cast :score :integer] :x]] :from [:users]} qualified)
             (row {:select [[[f :score] :x]] :from [:users]} qualified))
          (str f "(score) is score cast to its own type, which keeps its table"))
      (is (= [:map [:x int4]] (row {:select [[[f :score] :x]] :from [:users]}))))
    (is (= [:map [:x [:enum {:pg/type "mood"} "happy" "sad"]]] (row {:select [[[:mood [:inline "sad"]] :x]]}))
        "a type of the registry's")
    (is (= [:map [:x [:maybe [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]] (row {:select [[[:int4 :?n] :x]]})))
    (is (= [:map [:x [:maybe [:time/local-date {:pg/type "date"}]]]] (row {:select [[[:date :closed_at] :x]] :from [:users]}))
        "date(timestamptz) is a function: no cast")
    (let [{:keys [v d]} (args {:select [[[:timestamptz :?v] :x] [[:date :?d] :y]]})]
      (is (m/validate v (java.time.OffsetDateTime/now) malli-opts) "timestamptz($1) is a cast, which takes a timestamptz")
      (is (m/validate d (java.time.LocalDate/now) malli-opts)))))

(deftest an-argument-compared-with-a-literal-of-no-type-takes-its-own-type
  (is (= {:w [:any {:pgmalli/untyped :type}]} (args {:select [[1 :x]] :where [:= :?w [:inline "1"]]}))
      "'1' is unknown, which PostgreSQL reads as the argument's type"))

(deftest an-operator-of-one-operand-honeysql-writes-as-the-operand-is-the-operand
  (let [qualified opts]
    (is (= (row {:select [[:score :x]] :from [:users]} qualified) (row {:select [[[:and :score] :x]] :from [:users]} qualified)))
    (is (= (row {:select [:score] :from [:users]} qualified) (row {:select [[[:* [:or :score]]]] :from [:users]} qualified)))
    (is (= (row {:select [[:score :x]] :from [:users]}) (row {:select [[[:|| :score] :x]] :from [:users]})))
    (is (= [:map [:x [:maybe int4]]] (row {:select [[[:- [:inline 1]] :x]]})) "- x is an operator")))

(deftest an-override-of-a-type-the-driver-returns-as-an-object-holds-for-writes-alone
  (let [ip [:re {:pg/type "inet"} "^x$"]
        hs [:map-of {:pg/type "hstore"} :string :string]
        reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:ip ip] [:h hs]]}})
        o {}]
    (is (= [:map [:ip [:any {:pg/type "inet"}]] [:h [:any {:pg/type "hstore"}]]] (h/row-schema reg {:select [:ip :h] :from [:t]} o))
        "pgjdbc reads inet as a PGobject, hstore as a java.util.Map")
    (is (= [:map {:pg/table "public.t"} [:ip [:any {:pg/type "inet"}]] [:h [:any {:pg/type "hstore"}]]] (pgmalli/as-read reg :pg.public/t o)))
    (let [reg (pgmalli/registry {:registry {:pg.public/r [:map {:pg/table "public.r"} [:v [:some {:pg/type "tstzrange"}]]]}})]
      (is (= [:map [:v [:some {:pg/type "tstzrange"}]]] (h/row-schema reg {:select [:v] :from [:r]} o)) "a column never NULL stays so"))
    (is (= {:ip ip :h hs} (h/arg-types reg {:update :t :set {:ip :?ip :h :?h}} o)))))

(deftest a-value-of-the-wrong-class-for-a-driver-object-says-the-class-it-should-be
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:c [:string {:pg/type "citext"}]]]}})
        x (:x (h/arg-types reg {:update :t :set {:c :?x}}))]
    (is (some #(str/includes? (str %) "should be an org.postgresql.util.PGobject")
              (flatten [((requiring-resolve 'malli.error/humanize) (m/explain x 42 malli-opts))])))))

(deftest an-argument-of-a-function-or-operator-takes-the-type-it-resolves-to
  (is (= {:now [:or
                [:time/instant {:pg/type "timestamptz"}]
                ['inst? {:pg/type "timestamptz"}]
                :time/offset-date-time]
          :d [:any {:pg/type "interval"}]
          :n [:maybe [:int {:pg/type "integer"}]]} (args {:select [:id] :from [:users]
                    :where [:and [:> :?now [:coalesce :closed_at [:- :current_timestamp [:interval "1 day"]]]]
                            [:< [:+ :closed_at :?d] :current_timestamp]
                            [:= :nick [:repeat "a" :?n]]]})))
  (is (= {:x [:maybe [:int {:pg/type "integer"}]]} (args {:select [[[:case [:= :id 1] :?x :else :score] :c]] :from [:users]}))
      "CASE's values resolve as coalesce's arguments"))

(deftest a-json-column-of-several-shapes-keeps-each-shape
  (let [shapes [:or [:map {:closed true} [:a :string]] [:map {:closed true} [:b :int]]]
        reg (pgmalli/registry {:registry {:app/input shapes
                                          :pg.public/jobs [:map {:pg/table "public.jobs"}
                                                           [:input [:ref {:pg/type "jsonb"} :app/input]]]}})
        o {}
        ok? #(m/validate %1 %2 {:registry reg})
        arg (:input (h/arg-types reg {:insert-into :jobs :values [{:input :?input}]} o))
        row (h/row-schema reg {:select [:input] :from [:jobs]} o)]
    (is (ok? arg {:a "x"}))
    (is (ok? arg {:b 1}) "the second shape too")
    (is (ok? row {:input {:b 1}}))))

(deftest a-comparison-is-read-in-every-clause-as-in-where
  (let [where (:s (args {:select [:id] :from [:users] :where [:= :score :?s]}))]
    (doseq [q [{:update :users :set {:nick [:case [:= :score :?s] "a" :else "b"]} :where [:= :id 1]}
               {:update :users :set {:nick "a"} :returning [[[:= :score :?s] :hit]]}
               {:delete-from :users :returning [[[:= :score :?s] :hit]]}]]
      (is (= where (:s (args q))) (pr-str q))))
  (is (= [{:kind :enum-literal :column :mood :value "angry" :allowed ["happy" "sad"]}]
         (check {:update :users :set {:nick "a"} :returning [[[:= :mood "angry"] :m]]})))
  (is (= [{:kind :unknown-column :column :nope}]
         (check {:update :users :set {:nick [:case [:= :nope 1] "a" :else "b"]}}))))

(deftest the-rows-of-values-do-not-see-the-table-inserted-into
  ;; PostgreSQL: column "score" does not exist, there is one in users, but it cannot be referenced
  ;; from this part of the query
  (is (= [{:kind :unknown-column :column :score}]
         (check {:insert-into :users :values [{:group_id 1 :score 1 :nick [:case [:= :score 1] "a" :else "b"]}]})))
  (is (= [:any {:pgmalli/untyped :type}] (:s (args {:insert-into :users :values [{:group_id 1 :score 1 :nick [:case [:= :score :?s] "a" :else "b"]}]})))))

(deftest an-unnest-tables-column-is-read-as-a-parameter-is
  (let [ids #(:ids (args % opts))
        q (fn [op] {:select [:id] :from [:users] :join [[[:unnest :?ids] :i] [op :users.id :i]]})]
    (is (m/validate (ids (q :is-not-distinct-from)) (into-array Long [1 nil]) {:registry registry}) "IS NOT DISTINCT FROM takes NULL")
    (is (not (m/validate (ids (q :=)) (into-array Long [1 nil]) {:registry registry})) "= matches no NULL")
    (is (= (ids {:select [:id] :from [:users] :where [:= :users.group_id [:cast :x.i :integer]] :join [[[:unnest :?ids] [:x {:columns [:i]}]] true]})
           (ids {:select [:id] :from [:users] :join [[[:unnest :?ids] [:x {:columns [:i]}]] true]
                 :where [:exists {:select [1] :from [:groups] :where [:= :groups.id [:cast :x.i :integer]]}]}))
        "a subquery reads it in the scope it sees")))

(deftest an-insert-selects-argument-takes-nil-where-the-select-may-drop-its-row
  (let [q (fn [select] {:insert-into [[:users [:group_id :score]] select]})
        g #(:g (args (q %)))
        ss #(:ss (args (q %) opts))
        plain (g {:select [:?g :?s]})
        elements (ss {:select [:?g :x.s] :from [[[:unnest :?ss] [:x {:columns [:s]}]]]})]
    (is (= plain (:g (args {:insert-into :users :values [{:group_id :?g :score :?s}]}))) "a row of arguments reaches the column, as one of :values does")
    (is (not (m/validate elements (into-array Integer [(int 1) nil]) {:registry registry})) "unnest of arguments drops no element")
    (is (= plain (g {:select [:?g :x.s] :from [[[:unnest :?ss] [:x {:columns [:s]}]]]})))
    (doseq [more [{:where [:is-not :?g nil]} {:having true} {:limit 1} {:offset 1} {:group-by [:?s]}
                  {:from [[[:unnest :?ss] [:x {:columns [:s]}]] :groups]}
                  {:from [[[:unnest :?ss] [:x {:columns [:s]}]]] :join [:groups [:= :groups.id 1]]}]]
      (is (= [:maybe integer] (g (merge {:select [:?g :?s]} more))) (str (pr-str more) ": nil, and no bounds"))
      (is (m/validate (ss (merge {:select [:?g :x.s] :from [[[:unnest :?ss] [:x {:columns [:s]}]]]} more)) (into-array Integer [(int 1) nil]) {:registry registry}) (pr-str more)))
    (is (= [:maybe integer] (g {:select-distinct [:?g :?s]})))
    (is (m/validate (:n (args {:insert-into [[:users [:nick]] {:select [:?n] :where false}]})) (apply str (repeat 41 "x")))
        "PostgreSQL checks a length, a range or a CHECK only on a row it writes")))

(deftest an-inserted-value-takes-nil-only-from-a-before-insert-trigger
  ;; PostgreSQL checks NOT NULL on the row proposed for insertion before it looks for a conflict
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:update ["touch"]}}
                                                        [:id [:int {:pg/type "integer"}]] [:g [:int {:pg/type "integer"}]]]}})
        a #(h/arg-types reg %)
        conflict {:on-conflict [:id] :do-update-set {:fields [:g] :where [:is-distinct-from [:composite :t.g] [:composite :excluded.g]]}}]
    (doseq [q [{:insert-into :t :values [{:id :?id :g :?g}]}
               {:insert-into [[:t [:id :g]] {:select [:?id :?g]}]}
               {:insert-into [[:t [:id :g]] {:select [:x.i :?g] :from [[[:unnest :?id] [:x {:columns [:i]}]]]}]}]]
      (is (= (a q) (a (merge q conflict))) (pr-str q)))
    (is (= :maybe (first (:g (a {:insert-into :t :values [{:id 1 :g 1}] :on-conflict [:id] :do-update-set {:g :?g}}))))
        "the BEFORE UPDATE trigger may fill what DO UPDATE sets"))
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:insert ["fill"]}}
                                                        [:id [:int {:pg/type "integer"}]] [:g [:int {:pg/type "integer"}]]]}})]
    (is (= :maybe (first (:g (h/arg-types reg {:insert-into :t :values [{:id 1 :g [:cast :?g :integer]}]}))))
        "nor a cast's argument: the trigger may fill the NULL it makes")))

(deftest limit-types-its-own-argument-not-one-inside-an-expression
  (is (not= (:o (args {:select [:id] :from [:users] :offset :?o}))
            (:a (args {:select [:id] :from [:users] :offset [:+ :?a 1]})))
      "a + 1 is at least 0, a need not be"))

(deftest a-scalar-subquery-has-its-columns-type-and-may-be-null
  (is (= [:map [:n [:maybe bigint]] [:g [:maybe text]] [:two [:maybe :any]]]
         (row {:select [[{:select [[[:count :*]]] :from [:groups]} :n]
                        [{:select [:g.name] :from [[:groups :g]] :where [:= :g.id :u.group_id]} :g]
                        [{:select [:id :nick] :from [:users]} :two]]
               :from [[:users :u]]}))
      "no row is NULL; a subquery of two columns is no value")
  (is (= [:map [:n [:maybe bigint]]]
         (row {:with [[:c {:select [:id] :from [:users]}]] :select [[{:select [[[:max :id]]] :from [:c]} :n]] :from [:groups]}))
      "one reading a CTE of the query is read under it")
  (is (= [:map [:n bigint]] (row {:select [[[:coalesce [[:count :id]] 0] :n]] :from [:users]}))
      "[[f x]] is (f(x)), of f's type"))

(deftest a-null-member-of-a-set-operation-takes-the-others-type
  (is (= [:map [:x [:maybe int4]] [:y text]]
         (row {:union-all [{:select [[nil :x] [[:inline "a"] :y]]}
                           {:select [[:score :x] [:name :y]] :from [:groups :users]}]}))
      "NULL and a literal of no type are left out where PostgreSQL resolves the column")
  (is (= [:map [:z [:maybe [:string {:pg/type "character varying"}]]]]
         (row {:union-all [{:select [[[:inline "a"] :z]]} {:select [[:nick :z]] :from [:users]}]}))
      "a literal is no value of the column's length"))

(deftest a-void-function-is-of-type-void-which-the-driver-reads-as-an-object
  (is (= [:map [:l [:maybe [:any {:pg/type "void"}]]]] (row {:select [[[:pg_advisory_xact_lock :id] :l]] :from [:users]}))))

(deftest a-recursive-cte-keeps-its-first-members-types-through-what-reads-it
  (let [r [:r {:union-all [{:select [:id :nick] :from [:users] :where [:= :id 1]}
                           {:select [:u.id :u.nick] :from [[:users :u]] :join [:r [:= :r.id :u.referrer_id]]}]}]
        id [:maybe [:int {:pg/identity :always :pg/type "bigint"}]]]
    (is (= [:map [:id id]] (row {:with-recursive [r [:x {:select [:r.id] :from [:r]}]] :select [:id] :from [:x]}))
        "another CTE")
    (is (= [:map [:id id]] (row {:with-recursive [r] :select [:d.id] :from [[{:select [:id] :from [:r]} :d]]}))
        "a derived table")
    (is (= [:map [:id id]] (row {:with-recursive [r] :select [:d.id] :from [[{:select [:id] :from [:r]} :d]]} opts))
        "of no table: a UNION's")))

(deftest a-function-whose-result-no-argument-decides-has-it-of-arguments-not-told
  (let [r (row {:select [[[:count [:distinct :x.v]] :n] [[:jsonb_build_object "k" :x.v] :j] [[:upper :x.v] :u]]
                :from [[{:select [[[:raw "1"] :v]]} :x]]})]
    (is (= [:n bigint] (nth r 1)) "count is a bigint, never NULL")
    (is (= [:j [:maybe [:map {:pg/type "jsonb"}]]] (nth r 2)))
    (is (= [:u [:maybe :any]] (nth r 3)) "upper of a range is a range")))

(deftest an-aggregates-order-by-leaves-its-type
  (is (= [:map [:ids [:maybe [:vector {:pg/type "bigint[]"} [:maybe :int]]]] [:s [:maybe text]]]
         (row {:select [[[:array_agg [:order-by :id [:id :desc]]] :ids] [[:string_agg [:cast :id :text] [:order-by [:inline ","] :id]] :s]]
               :from [:users]}))))

(deftest an-operator-over-any-or-all-is-boolean-and-compares-the-elements
  (is (= [:map [:hit [:maybe [:boolean {:pg/type "boolean"}]]]] (row {:select [[[:= :id [:any [:array [1 2]]]] :hit]] :from [:users]})))
  (is (= {:x [:or text :keyword]} (args {:select [:id] :from [:users] :where [:= :?x [:any [:array [[:cast :nick :text]]]]]}))
      "x = ANY(a) takes a's element type"))

(deftest a-parameter-a-call-may-resolve-several-ways-takes-the-values-of-each
  (is (= {:n [:maybe [:or 'decimal? 'integer? :int]]} (args {:select [:*] :from [[[:generate_series 1 :?n] :g]]}))
      "an int, an int8 or a numeric each picks its overload")
  (is (= {:at [:maybe
               [:or :time/local-date-time 'inst? :time/offset-time :time/instant :time/offset-date-time]]}
         (args {:select [[[:timezone :nick :?at] :t]] :from [:users]}))))

(deftest an-array-cast-casts-each-element
  (is (= (args {:select [[[:cast :?k :text] :a]]}) (args {:select [[[:array [:?k] :text] :a]]}))))

(deftest an-argument-of-several-uses-takes-what-each-takes
  (let [native opts]
    (is (= {:id [:maybe integer]}
           (args {:select [[[:cast :?id :bigint] :a]] :from [:groups] :where [:= :id :?id]} native))
        "a cast takes what converts to a bigint, a comparison an integer column's values; the selected cast nil")
    (is (= (args {:select [:id] :from [:groups] :where [:and [:= :id :?id] [:= :id [:cast :?id :bigint]]]} native)
           (args {:select [:id] :from [:groups] :where [:and [:= :id [:cast :?id :bigint]] [:= :id :?id]]} native))
        "whatever the order")
    (is (= {:id [:maybe integer]}
           (args {:select [:id] :from [:groups] :where [:and [:= :id :?id] [:is-distinct-from :name [:cast :?id :text]]]} native))
        "nil where any use takes it")))

(deftest a-pg-catalog-relation-has-the-columns-of-the-catalog-snapshot
  (is (= {:names [:pg/array [:or [:string {:pg/type "name"}] :keyword]]}
         (args {:select [:c.oid] :from [[:pg_class :c]] :where [:= :c.relname [:any :?names]]})))
  (is (= [:map [:relname [:string {:pg/type "name"}]]]
         (row {:select [:c.relname] :from [[:pg_class :c]]} opts)))
  (is (= [{:kind :unknown-column :column :c.nope}] (check {:select [:c.nope] :from [[:pg_class :c]]})))
  (is (= [] (check {:select [:attstattarget] :from [:pg_attribute]})) "a column one version has"))

(deftest analyze-gives-what-check-arg-types-row-schema-and-tables-give-in-one-map
  (let [q {:select [:nick] :from [:users] :where [:= :id :?id]}]
    (is (= {:args (args q) :row (row q) :rows {:min 0 :max 1} :problems [] :tables (h/tables registry q opts)} (h/analyze registry q opts)))
    (is (= {:reads #{"sample.users"} :writes #{}} (:tables (h/analyze registry q opts)))))
  (let [q {:select [:nick_name] :from [:users] :where [:= :group_id :?g]}]
    (is (= {:args (args q) :row nil :rows nil :problems (check q) :tables (h/tables registry q opts)} (h/analyze registry q opts))))
  (is (= {:min 1 :max 1} (:rows (h/analyze registry {:select [[[:count :*] :n]] :from [:users]} opts)))))

(deftest an-argument-no-use-types-says-why
  (is (= {:y [:any {:pgmalli/untyped :position}]} (args {:select [[:?y :y]] :from [:users]})))
  (is (= [:any {:pgmalli/untyped :code}] (:x (args '{:select [:id] :from [:users] :where [:= :id (inc :?x)]}))))
  (is (= [:any {:pgmalli/untyped :type}] (:x (args {:update :users :set {:no_such :?x} :where [:= :id 1]}))))
  (is (= [:=> [:cat :any [:any {:pgmalli/untyped :position}]] [:sequential [:map [:y [:maybe :any]]]]]
         (h/query-schema registry [:db :y] {:select [[:?y :y]] :from [:users]} opts))
      "an argument the query lacks stays :any"))

(deftest a-whole-row-argument-is-typed-as-the-row-written
  (let [values (h/values-schema registry :users opts)]
    (is (= values (:row (args {:insert-into :users :values [:?row]}))))
    (is (= values (get (args '{:insert-into :users :values [row]}) 'row)) "a symbol, as code puts it in")
    (is (= [:sequential {:min 1} values] (:rows (args {:insert-into :users :values :?rows}))))
    (is (= (h/set-schema registry :users opts) (:row (args {:update [:users :u] :set :?row :where [:= :u.id :?id]}))))
    (is (= [:=> [:cat :any values] [:sequential {:max 1} [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]]]]
           (h/query-schema registry [:db :row] {:insert-into :users :values [:?row] :returning [:id]} opts))))
  (is (= (h/values-schema registry :users opts) (:row (args {:insert-into :users :values [:?row]} opts))))
  (is (= [:any {:pgmalli/untyped :position}] (:row (args {:insert-into [:users [:group_id :mood]] :values [:?row]})))
      "a row of listed columns is values, not a map")
  (is (= [:any {:pgmalli/untyped :position}] (get (args '{:insert-into t :values [:?row]}) :row))
      "a table code names"))

