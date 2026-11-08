(ns pgmalli-next.honeysql-test
  "HoneySQL query data against the checked-in sample registry."
  (:refer-clojure :exclude [bigint])
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [clojure.test :refer [deftest is testing]]
            [honey.sql.pg-ops]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [malli.experimental.time :as time]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.args :as args]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.sample :refer [registry]]))

(def opts {:schema "sample"})

(defn- written-keys
  "The keys of a values-schema or set-schema map, of every column."
  [s]
  (map first (drop 2 (if (= :map (first s)) s (some #(when (and (vector? %) (= :map (first %))) %) s)))))

(def malli-opts {:registry (merge (m/default-schemas) (time/schemas))})

(defn- check ([q] (check q opts)) ([q o] (:problems (h/analyze registry q o))))
(defn- args ([q] (args q opts)) ([q o] (:args (h/analyze registry q o))))
(defn- row ([q] (row q opts)) ([q o] (:row (h/analyze registry q o))))

(def bigint [:int {:pg/type "bigint"}])
(def integer [:int {:pg/type "integer"}])
(def int4 [:int {:pg/type "integer" :min -2147483648 :max 2147483647}])
;; compared, = takes a number of any number type: PostgreSQL converts one side implicitly
(defn- compared [t] [:or t 'decimal? 'integer? :double 'float?])
(def nick-assigned
  (let [s [:string {:pg/max-length 40 :pg/type "character varying"}]] [:maybe [:or s :keyword]]))
(def int-call
  (let [[_ held] (:x (args {:select [[[:ntile :?x] :n]] :from [:users]}))] held))
(def bigint-cmp (compared bigint))
(def integer-cmp (compared integer))
(def text [:string {:pg/type "text"}])
;; a label as read, and as an argument takes it: a keyword goes untyped, a String as varchar, which an enum refuses
(def mood [:enum {:pg/type "mood" :default :happy :pg/default "happy"} :happy :sad])
(def pg-object jdbc/pg-object)
(defn in-list [s] [:or [:sequential {:min 1} s] [:set {:min 1} s]])

(deftest queries-that-agree-have-no-problems
  (is (= [] (check {:select [:id :nick] :from [:users] :where [:= :group_id :?g]})))
  (is (= [] (check {:select [:u.id [:g.name :group]] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]]})))
  (is (= [{:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check {:insert-into :users :values [{:group_id 1 :mood "sad" :score 1 :closed_at :?now}] :returning [:id]})))
  (is (= [] (check {:with [[:recent {:select [:id] :from [:users]}]] :select [:id] :from [:recent]})) "a CTE is an opaque table")
  (is (= [] (check {:insert-into [[:users [:group_id :mood :score]] {:select [:group_id :mood :score] :from [:users]}]}))
      "INSERT INTO table (columns) SELECT"))

(deftest a-list-of-plain-items-is-read-as-honeysql-reads-a-vector
  (is (= [{:kind :unknown-column :column :nope}] (check {:select '(:id :nope) :from [:users]})))
  (is (= [{:kind :unknown-column :column :nope}] (check {:select [:id] :from '(:users) :where [:= :nope 1]})))
  (is (= [{:kind :unknown-column :column :nope}] (check {:select [:id] :from [:users] :where '(:= :nope 1)})))
  (is (= (row {:select [[:id :x]] :from [:users]}) (row {:select (list [:id :x]) :from (list :users)})))
  (is (= (row {:select [:id] :from [:users]}) (row {:select (map keyword ["id"]) :from [:users]})) "a seq built at run time"))

(deftest an-enum-literal-is-held-to-the-labels-wherever-postgresql-types-it
  (let [kinds (fn [q] (map (juxt :kind :value) (check q)))
        where (fn [w] (kinds {:select [:id] :from [:users] :where w}))]
    (doseq [[w why] [[[:= :mood [:lift :angry]] "a lifted keyword"]
                     [[:= :mood [:inline "angry"]] "an inlined string"]
                     [[:in :mood ["sad" [:inline "angry"]]] "each of an IN list"]
                     [[:= :mood [:any [:array [[:inline "sad"] [:inline "angry"]]]]] "each element of ANY's array"]
                     [[:= :mood [:coalesce :mood [:inline "angry"]]] "an operand resolved to the others' type"]
                     [[:= :mood [:case [:= :id 1] :mood :else [:inline "angry"]]] "CASE's ELSE"]
                     [[:= [:case [:= :id 1] [:inline "angry"] :else :mood] :mood] "CASE's THEN"]]]
      (is (some #{[:enum-literal (if (= [:lift :angry] (last w)) :angry "angry")]} (where w)) why))
    (is (= [] (where [:= :mood [:lift :sad]])) "a keyword goes untyped, which enum takes")
    (is (= [[:uncast-string "sad"]] (where [:= :mood [:lift "sad"]])) "a lifted string goes as varchar")
    (is (= [[:enum-literal :angry]] (kinds {:update :users :set {:mood [:lift :angry]} :where [:= :id 1]})) "assigned")
    (is (= [] (kinds {:select [[[:coalesce :nick [:inline "angry"]] :n]] :from [:users]})) "a string column takes any")))

(deftest a-column-of-a-domain-over-an-enum-is-compared-with-nothing
  (let [reg (pgmalli/registry {:schemas ["public"] :registry {:pg.public/mood [:enum "ok" "sad"]
                                                             :pg.public/t [:map {:pg/table "public.t"}
                                                                           [:m [:maybe [:ref {:pg/type "mood" :pg/domain "dmood"} :pg.public/mood]]]
                                                                           [:k [:maybe [:ref {:pg/type "mood"} :pg.public/mood]]]
                                                                           [:ms [:maybe [:vector {:pg/type "mood[]" :pg/domain "dmood[]"} [:ref :pg.public/mood]]]]]}})
        kinds (fn [w] (map :kind (:problems (h/analyze reg {:select [:k] :from [:t] :where w}))))]
    (doseq [w [[:= :m [:inline "sad"]] [:in :m [[:inline "sad"]]] [:= :m :k] [:<> [:inline "sad"] :m] [:= :m :?x] [:= :?x [:any :ms]]]]
      (is (= [:no-operator] (kinds w)) (str (pr-str w) ": dmood = unknown, = mood and = dmood do not exist")))
    (doseq [w [[:= [:cast :m :mood] [:inline "sad"]] [:= :m nil] [:is-distinct-from :m nil] [:= [:coalesce :m [:inline "ok"]] [:inline "ok"]]]]
      (is (= [] (kinds w)) (pr-str w)))
    (is (m/validate (:x (:args (h/analyze reg {:select [[[:coalesce :m :?x] :c]] :from [:t]}))) :sad {:registry reg})
        "resolved beside it, coalesce(dmood, ?) takes a label sent untyped")
    (is (= ["PostgreSQL has no = for m, of domain dmood over enum mood; compare the enum: [:cast :m :mood]"]
           (h/humanize (:problems (h/analyze reg {:select [:k] :from [:t] :where [:= :m [:inline "sad"]]})))))))

(deftest a-bit-string-column-takes-a-pgobject-of-its-type-no-string
  (let [reg (pgmalli/registry {:schemas ["public"] :registry {:pg.public/t [:map {:pg/table "public.t"}
                                                                           [:b1 [:string {:pg/type "bit" :pg/max-length 1}]]
                                                                           [:vb [:string {:pg/type "bit varying" :pg/max-length 3}]]]}})]
    (doseq [q [{:update :t :set {:b1 :?x}} {:select [:b1] :from [:t] :where [:= :vb :?x]}]
            :let [x (:x (:args (h/analyze reg q)))]]
      (is (not (m/validate x "1" {:registry reg})) (str (pr-str q) ": bit = character varying does not exist"))
      (is (not (m/validate x true {:registry reg})) "nor does bit take a boolean")
      (is (re-matches #"should be a PGobject of type bit( varying)? \(a String goes as varchar, which bit( varying)? does not take\)"
                      (first (me/humanize (m/explain (shape/non-null (if (= :or (first x)) (second x) x)) "1" {:registry reg}))))
          "a String is told why"))))

(deftest an-array-of-a-domain-is-compared-with-a-pgobject-of-the-domains-array-alone
  (let [reg (pgmalli/registry {:schemas ["public"] :registry {:pg.public/t [:map {:pg/table "public.t"} [:n [:vector {:pg/type "integer[]" :pg/domain "dint[]"} :pg/integer]]]}})
        x (:x (:args (h/analyze reg {:select [:n] :from [:t] :where [:= :n :?x]})))]
    (is (not (m/validate x (int-array [1]) {:registry reg})) "dint[] = integer[] does not exist")
    (is (m/validate (:x (:args (h/analyze reg {:select [[[:coalesce :n :?x] :c]] :from [:t]}))) (int-array [1]) {:registry reg})
        "beside it in coalesce, an integer[] resolves to it")
    (is (re-find #"PGobject of type dint\[\]" (pr-str x)))))

(deftest a-lifted-string-is-inlined-under-inline-which-an-enum-takes
  (is (= [] (check {:select [:id] :from [:users] :where [:= :mood [:lift "sad"]]} (assoc opts :format {:inline true})))))

(deftest a-set-operations-column-of-one-type-reads-each-value-as-its-member-reads-it
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:b1 [:string {:pg/type "bit" :pg/max-length 1}]]
                                                        [:b4 [:string {:pg/type "bit" :pg/max-length 4}]]]}})
        row #(:row (h/analyze reg % {}))]
    (is (= [:map [:x [:or [:boolean {:pg/type "bit"}] [:any {:pg/type "bit"}]]]]
           (row {:union-all [{:select [[:b1 :x]] :from [:t]} {:select [[:b4 :x]] :from [:t]}]}))
        "bit(1) a Boolean, bit(4) a PGobject: the result's typmod is gone, and pgjdbc reads each value")))

(deftest a-literal-honeysql-sends-is-judged-as-an-argument-there-is
  (let [reg (pgmalli/registry {:schemas ["public"] :registry {:pg.public/mood [:enum "ok" "sad"]
                                                             :pg.public/t [:map {:pg/table "public.t"} [:id [:pg/integer {:pg/type "integer"}]]
                                                                           [:u [:maybe [:uuid {:pg/type "uuid"}]]] [:n [:maybe [:string {:pg/type "text"}]]]
                                                                           [:m [:maybe [:ref {:pg/type "mood"} :pg.public/mood]]]
                                                                           [:r [:maybe [:any {:pg/type "regclass"}]]]]}})
        problems (fn [q] (map (juxt :kind :value) (:problems (h/analyze reg q {:schema "public"}))))]
    (is (= [[:literal-type 1]] (problems {:update :t :set {:u 1}})) "assigned: uuid takes no bigint")
    (is (= [[:literal-type 1]] (problems {:update :t :set {:n 1}})) "stricter: a number to a text column, as an argument")
    (is (= [[:uncast-string "w"]] (problems {:select [:id] :from [:t] :where [:= :r "w"]})) "regclass = varchar does not exist")
    (is (= [] (problems {:update :t :set {:r "w"}})) "regclass takes varchar by assignment")
    (testing "one problem of each literal, wherever it sits"
      (is (= [[:enum-literal "x"]] (problems {:select [:id] :from [:t] :where [:= :m [:coalesce :m [:inline "x"]]]})))
      (is (= [[:enum-literal "x"]] (problems {:select [:id] :from [:t] :where [:= [:case [:= :id 1] [:inline "x"] :else :m] :m]})))
      (is (= [[:enum-literal "zz"]] (problems {:update :t :set {:m [:cast "zz" :mood]}})))
      (is (= [[:literal-type 2]] (problems {:select [:id] :from [:t] :where [:= [:round :id 2] 1]}))))
    (is (= [[:uncast-string "sad"] [:uncast-string "sad"]] (problems {:insert-into :t :values [{:id 1 :m "sad"} {:id 2 :m "sad"}]}))
        "each occurrence its own")
    (is (= [[:enum-literal "zz"] [:enum-literal "zz"]] (problems {:insert-into :t :values [{:id 1 :m [:cast "zz" :mood]} {:id 2 :m [:cast "zz" :mood]}]}))
        "a cast written twice, twice")
    (is (= [[:literal-type true] [:literal-type false]] (problems {:insert-into :t :values [{:id 1 :u true} {:id 2 :u false}]}))
        "a boolean, false too, written as TRUE or FALSE")
    (is (= [] (:problems (h/analyze reg {:update :t :set {:u 1}} {:schema "public" :format {:inline true}}))) "inlined, untyped")
    (testing "one problem of a literal of an IN list, ANY's array, nullif, greatest"
      (is (= [[:enum-literal "zz"]] (problems {:select [:id] :from [:t] :where [:in :m [[:inline "ok"] [:inline "zz"]]]})))
      (is (= [[:enum-literal "zz"]] (problems {:select [:id] :from [:t] :where [:= :m [:any [:array [[:inline "zz"]]]]]})))
      (is (= [[:enum-literal "zz"]] (problems {:select [[[:nullif :m [:inline "zz"]] :x]] :from [:t]})))
      (is (= [[:enum-literal "zz"]] (problems {:select [[[:greatest :m [:inline "zz"]] :x]] :from [:t]}))))
    (is (= [[:literal-type 1]] (problems {:select [:id] :from [:t] :where [:like :n 1]})) "text ~~ bigint does not exist")
    (is (= [[:literal-type "o%"]] (problems {:select [:id] :from [:t] :where [:like :m "o%"]})) "mood ~~ character varying does not exist")
    (is (= [[:literal-type 1]] (problems {:select [[[:coalesce :u 1] :x]] :from [:t]})) "uuid and bigint have no common type")
    (let [xml (pgmalli/registry {:registry {:pg.public/x [:map {:pg/table "public.x"} [:d [:maybe [:any {:pg/type "xml"}]]]]}})]
      (is (= [:uncast-string :uncast-string] (map :kind (concat (:problems (h/analyze xml {:select [:d] :from [:x] :where [:= :d "<a/>"]}))
                                                                (:problems (h/analyze xml {:update :x :set {:d "<a/>"}})))))
          "a type pgmalli does not type takes no varchar: xml = varchar does not exist"))
    (is (= ["1 goes to column u as a parameter, which pgjdbc sends as bigint, a type column u takes there through no implicit conversion; cast it, [:cast 1 :uuid]"]
           (h/humanize (:problems (h/analyze reg {:update :t :set {:u 1}} {:schema "public"})))))
    (is (= [{:kind :uncast-string :value "x" :type "uuid" :function "uuid_send"}]
           (:problems (h/analyze reg {:select [[[:uuid_send "x"] :b]]} {:schema "public"}))))
    (is (= ["\"x\" reaches an argument of uuid_send as a typed string, which its type uuid does not take (HoneySQL sends a string as a parameter, which pgjdbc sends as varchar); write [:cast \"x\" :uuid]"]
           (h/humanize (:problems (h/analyze reg {:select [[[:uuid_send "x"] :b]]} {:schema "public"})))))))

(deftest an-anycompatible-argument-takes-what-resolves-with-the-others-and-nullifs-what-equals-takes
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:ints [:vector {:pg/type "integer[]"} :pg/integer]]
                                                        [:iv [:any {:pg/type "interval"}]]]}})
        x (fn [q] (:x (:args (h/analyze reg q))))]
    (is (m/validate (x {:select [[[:array_append :ints :?x] :a]] :from [:t]}) 1.5 {:registry reg})
        "array_append(int[], 1.5) resolves to double precision[]")
    (is (= [] (:problems (h/analyze reg {:select [[[:array_append :ints 1.5] :a]] :from [:t]}))))
    (is (m/validate (x {:select [[[:|| :ints :?x] :a]] :from [:t]}) (double-array [1.5]) {:registry reg})
        "int[] || double[] resolves to double precision[], as array_cat does: || has several candidates")
    (is (m/validate (x {:select [[[:nullif :iv :?x] :n]] :from [:t]}) (java.time.LocalTime/of 1 0) {:registry reg})
        "nullif(interval, time) is interval = time, which resolves")))

(deftest a-citext-column-goes-where-text-does-in-a-call
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:ci [:string {:pg/type "citext"}]]]}})
        x (fn [q] (:x (:args (h/analyze reg q))))]
    (is (m/validate (x {:select [[[:substr :ci :?x] :s]] :from [:t]}) (int 2) {:registry reg}) "substr(text, integer), citext cast to text")
    (is (m/validate (x {:select [[[:|| :ci :?x] :s]] :from [:t]}) 1 {:registry reg}) "text || anynonarray")
    (is (= [] (:problems (h/analyze reg {:select [[[:|| :ci 1] :s]] :from [:t]}))))))

(deftest problems-name-what-disagrees
  (is (= [{:kind :unknown-table :table "sample.nope"}] (check {:select [:*] :from [:nope]})))
  (is (= [{:kind :unknown-column :column :nick_name}] (check {:select [:nick_name] :from [:users]})))
  (is (= [{:kind :unknown-column :column :nope}] (check {:select [:id] :from [:users] :where [:= :nope 1]})) "compared columns too")
  (is (= [{:kind :unknown-column :column :nope}] (check {:update :users :set {:nope 1} :where [:= :id 1]})) "and set ones")
  (is (= [{:kind :missing-required-column :table "sample.users" :column :score}
          {:kind :uncast-string :column :mood :value "sad" :type "mood"}]
         (check {:insert-into :users :values [{:group_id 1 :mood "sad"}]}))
      "an INSERT must carry the columns the insert schema requires")
  (is (= [{:kind :enum-literal :value "angry" :allowed ["happy" "sad"] :type "mood"}]
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
    (is (= {:id bigint-cmp
            :moods (in-list mood)
            :n [:maybe [:int {:pg/type "bigint" :min 0}]]}
           (args body)))
    (is (= {:g/id nick-assigned}
           (args {:update :users :set {:nick :?g/id} :where [:= :id 1]}))
        "a qualified one keeps its namespace, as sql/format's :params does")
    (is (= (args body)
           (args {:select [:id] :from [:users] :where [:and [:= :id [:param :id]] [:in :mood :?moods]] :limit :?n}))
        "[:param :id] and :?id are the same parameter")
    (let [s (h/query-schema registry [:id :n] body opts)]
      (is (= [:cat bigint-cmp [:maybe [:int {:pg/type "bigint" :min 0}]]] (nth s 2)))
      (is (= {:pgmalli/problems [{:kind :missing-arg :arg :moods}]} (second s)) "the one left out of args"))))

(deftest every-table-has-the-system-columns
  (is (= [] (check {:delete-from :users :where [:in :ctid {:select [:ctid] :from [:users] :where [:= :xmin 1]}]})))
  (is (= {:t [:int {:min 0 :max 4294967295 :pg/type "oid"}]} (args {:select [:id] :from [:users] :where [:= :tableoid :?t]}))))

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

(deftest a-table-function-without-an-alias-is-named-after-it
  (is (= [] (check {:select [:*] :from [[[:public.claim_token :?user-id :?token]]]})))
  (is (= [] (check {:select [:claim_token.x] :from [[[:claim_token 1]]]}))))

(deftest a-set-operations-order-by-names-its-first-members-output
  (let [body {:union-all [{:select [[:id :uid] [0 :bucket]] :from [:users]} {:select [:id 1] :from [:groups]}]}]
    (is (= [] (check (assoc body :order-by [:bucket [:uid :desc]]))))
    (is (= [{:kind :unknown-column :column :nick}] (check (assoc body :order-by [:nick])))
        "the tables' columns are not in scope there")))

(deftest code-in-a-query-is-an-error-naming-where-it-is
  (doseq [[body path] ['[{:select [:id] :from [:users] :where (when x [:= :id :?id])} [:where 0]]
                       '[{:insert-into :users :values rows} [:values]]
                       '[{select [:id]} [select]]]
          f [check #(h/query-schema registry [:db] % opts)]]
    (let [e (try (f body) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= [:pgmalli.error/not-query-data path] [(:pgmalli/error (ex-data e)) (:in (ex-data e))]) (pr-str body))
      (is (str/includes? (ex-message e) "write a name as a keyword ([:cast :score :integer]), or, for code, pass the data it builds") (pr-str body)))))

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
    (is (= [:map
            [:id [:int {:pg/identity :always :pg/type "bigint"}]]
            [:name [:maybe [:string {:pg/max-length 40 :pg/type "character varying"}]]]]
           (row body opts)))
    (is (= [:map [:id [:maybe [:int {:pg/identity :always :pg/type "bigint"}]]]] (row {:union [{:select [:id] :from [:users]} {:select [[nil :id]]}]}))
        "of the members' type, NULL left out, nullable where one is")))

(deftest an-in-argument-is-what-sql-format-expands
  (let [t (:ids (args {:select [:id] :from [:users] :where [:in :id :?ids]}))]
    (doseq [v [[1 2] '(1) #{1 2} (map inc [0 1])]] (is (m/validate t v) (pr-str v)))
    (doseq [v [[] #{} 1 nil (long-array [1])]] (is (not (m/validate t v)) (pr-str v)) "IN (), or not expanded")))

(deftest write-names-what-an-extension-sends
  (let [o opts
        n [:or [:string {:pg/type "character varying"}] :keyword]]
    (is (= {:n n :ns (in-list n)
            :v nick-assigned}
           (args {:update :users :set {:nick :?v} :where [:and [:= :nick :?n] [:in :nick :?ns]]} o)))
    (is (= {:id bigint-cmp} (args {:select [:id] :from [:users] :where [:= :id :?id]} o))
        "a keyword sent untyped is no bigint's own value")))

(deftest an-outer-join-may-leave-a-tables-columns-null
  (let [id [:int {:pg/identity :always :pg/type "bigint"}]
        row #(:row (h/analyze registry (merge {:select [[:u.id :uid] [:g.id :gid]] :from [[:users :u]]} %) opts))]
    (is (= [:map [:uid id] [:gid [:maybe int4]]] (row {:left-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:uid [:maybe id]] [:gid int4]] (row {:right-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:uid [:maybe id]] [:gid [:maybe int4]]] (row {:full-join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:uid id] [:gid int4]] (row {:join [[:groups :g] [:= :g.id :u.group_id]]})))
    (is (= [:map [:uid id] [:gid [:maybe int4]]]
           (row {:left-join [[:users :x] [:= :x.id :u.id] [:groups :g] [:= :g.id :u.group_id]]}))
        "every pair of a :left-join")
    (is (= [:maybe int4] (second (nth (:row (h/analyze registry {:select [:g/*] :from [:users] :left-join [[:groups :g] [:= :g.id :users.group_id]]} opts)) 1)))
        "a table's * too")
    (is (= [:map [:id [:maybe int4]]] (row {:select [:u.id :g.id] :left-join [[:groups :g] [:= :g.id :u.group_id]]}))
        "a name selected twice is one key, the last column's, as the builder keeps it")))

(deftest columns-of-one-label-are-a-duplicate-column-as-the-builder-keeps-one
  (let [join {:from [[:users :q]] :join [[:groups :s] [:= :s.id :q.group_id]]}]
    (is (= [{:kind :duplicate-column :column :id :items [:q.id :s.id]}] (check (merge join {:select [:q.id :s.id]}))))
    (is (= [":q.id and :s.id make several columns labelled id; pgmalli-next.jdbc/builder reads them into one key :id, keeping the last one's value; give one an alias of its own: [:s.id :s_id]"]
           (h/humanize (check (merge join {:select [:q.id :s.id]})))))
    (is (= [] (check (merge join {:select [:q.id [:s.id :s_id]]}))))
    (is (= [{:kind :duplicate-column :column :id :items [:*]}] (check (merge join {:select [:*]}))) "a star's columns")
    (is (= [{:kind :duplicate-column :column :id :items [:q.* :s.id]}] (check (merge join {:select [:q.* :s.id]}))))
    (is (= [] (check {:select [:*] :from [:users] :join [:groups [:using :id]]})) "USING lists its column once")
    (is (= [{:kind :duplicate-column :column :count :items [[[:count :*]] [[:count :id]]]}] (check {:select [[[:count :*]] [[:count :id]]] :from [:users]}))
        "an expression's label")
    (is (= [{:kind :duplicate-column :column :id :items [:id [:nick :id]]}] (check {:select [:id [:nick :id]] :from [:users]})))
    (is (= [{:kind :duplicate-column :column :id :items [:id [:group_id :id]]}] (check {:update :users :set {:nick "a"} :where [:= :id 1] :returning [:id [:group_id :id]]}))
        "RETURNING")
    (let [q {:insert-into :users :columns [:group_id :score] :select [[:?g] [:?s]] :returning [:id]}]
      (is (= [] (check q)) "an INSERT's SELECT is not returned")
      (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]] (row q)) "its RETURNING is"))
    (is (= [{:kind :duplicate-column :column :id :items [:q.id :s.id] :in [:union 0]}]
           (check {:union [(merge join {:select [:q.id :s.id]}) {:select [:id :group_id] :from [:users]}]}))
        "a set operation's first member names its columns")
    (is (= [] (check {:union [{:select [:id :group_id] :from [:users]} (merge join {:select [:q.id :s.id]})]})) "the others none")
    (is (= [{:kind :duplicate-column :column :id :items [:*]}] (check {:with [[:t (merge join {:select [:q.id :s.id]})]] :select [:*] :from [:t]}))
        "a CTE's columns a star returns")
    (is (= [] (check {:with [[:t (merge join {:select [:q.id :s.id]})]] :select [[[:count :*] :n]] :from [:t]})) "a CTE's columns not returned")
    (is (= [] (check {:select [:id] :from [:users] :where [:exists {:select [:id :id] :from [:users]}]})) "a subquery's")))

(def docs
  (pgmalli/registry {:registry {:pg.public/docs [:map {:pg/table "public.docs"}
                                                      [:id [:int {:pg/type "bigint"}]]
                                                      [:paths [:maybe [:vector {:pg/type "text[]"} [:string {:pg/type "text"}]]]]
                                                      [:meta [:map {:pg/type "jsonb"} [:a :int]]]]}}))

(deftest a-value-taken-from-a-shaped-jsonb-column-is-of-the-part-it-takes
  (let [reg (pgmalli/registry {:registry {:pg.public/d [:map {:pg/table "public.d"}
                                                        [:items [:vector {:pg/type "jsonb"} [:map [:k :string]]]]
                                                        [:doc [:map {:pg/type "jsonb"} [:a [:vector :int]] [:b :string]]]
                                                        [:raw [:any {:pg/type "jsonb"}]]
                                                        [:tags [:map-of {:pg/type "jsonb"} :keyword :string]]]}})
        v #(last (last (:row (h/analyze reg {:select [[% :v]] :from [:d]} {}))))
        element [:maybe [:map {:pg/type "jsonb"} [:k :string]]]
        plain [:maybe [:any {:pg/type "jsonb"}]]]
    (is (= element (v [:-> :items [:inline 0]])) "an array's element")
    (is (= element (v [:jsonb_array_elements :items])))
    (is (= [[:value element] [:ordinality [:int {:pg/type "bigint"}]]]
           (rest (:row (h/analyze reg {:select [:e.value :e.ordinality] :from [:d [[:with-ordinality [:jsonb_array_elements :items]] :e]]} {}))))
        "a function in FROM, as in the select list")
    (is (= [:maybe [:vector {:pg/type "jsonb"} :int]] (v [:-> :doc [:inline "a"]])) "a map's key's")
    (is (= [:maybe [:map {:pg/type "jsonb"} [:b :string]]] (v [:- :doc [:inline "a"]])) "a map without the key -")
    (is (= [:maybe [:map {:pg/type "jsonb"}]] (v [:- [:-> :items [:inline 0]] [:array [[:inline "k"]]]])) "or without the keys of an ARRAY")
    (is (= [:maybe [:map-of {:pg/type "jsonb"} :keyword :string]] (v [:- :tags [:inline "a"]])) "a map-of as it is")
    (doseq [x [[:-> :doc [:inline "zz"]] [:- :items [:inline 0]] [:#> :doc [:inline "{a}"]] [:jsonb_path_query :doc [:inline "$.a"]]
               [:|| :items :items] [:to_jsonb :items]]]
      (is (= plain (v x)) (str (pr-str x) ": a value PostgreSQL computes is no part of the column's shape")))
    (is (= [:any {:pg/type "jsonb"}] (v [:coalesce :doc [:inline "{}"]])) "a literal beside it need not be of its shape")
    (is (= [:any {:pg/type "jsonb"}] (v [:coalesce :doc :raw])) "nor another column")))

(deftest a-shape-an-override-names-is-the-shape-of-its-name
  (let [reg (mr/composite-registry
             (pgmalli/registry {:registry {:pg.public/d [:map {:pg/table "public.d"}
                                                         [:o [:ref {:pg/type "jsonb"} :app/options]]
                                                         [:rs [:ref {:pg/type "jsonb"} :app/rows]]]}})
             {:app/options [:map [:bundle :string] [:keep :int]] :app/rows [:vector [:ref :app/row]] :app/row [:map [:k :string]]})
        v #(last (last (:row (h/analyze reg {:select [[% :v]] :from [:d]} {}))))]
    (is (= [:maybe [:map {:pg/type "jsonb"} [:keep :int]]] (v [:- :o [:inline "bundle"]])))
    (is (= [:maybe [:int {:pg/type "jsonb"}]] (v [:-> :o [:inline "keep"]])))
    (is (= [:maybe [:map {:pg/type "jsonb"} [:k :string]]] (v [:-> :rs [:inline 0]])))
    (is (= [:maybe [:map {:pg/type "jsonb"} [:k :string]]] (v [:jsonb_array_elements :rs])))))

(deftest min-max-and-nullif-of-a-reg-array-are-not-oid
  (let [reg (pgmalli/registry {:registry {:pg.public/rels [:map {:pg/table "public.rels"}
                                                           [:rs [:vector {:pg/type "regclass[]"} [:any {:pg/type "regclass"}]]]]}})
        row #(:row (h/analyze reg {:select % :from [:rels]} {}))]
    (is (not (str/includes? (pr-str (row [[[:min :rs] :m] [[:max :rs] :x] [[:nullif :rs :rs] :n]])) "oid")))))

(deftest an-array-is-read-as-a-vector-and-json-parsed
  (is (= [:map [:paths [:maybe [:vector {:pg/type "text[]"} [:maybe text]]]] [:meta [:map {:pg/type "jsonb"} [:a :int]]]]
         (:row (h/analyze docs {:select [:paths :meta] :from [:docs]} {})))))

(deftest an-array-through-coalesce-min-max-case-or-greatest-is-still-a-vector
  (doseq [e [[:coalesce :paths :paths] [:max :paths] [:min :paths] [:case [:= :id 1] :paths :else :paths] [:greatest :paths :paths]]]
    (is (= [:map [:x [:maybe [:vector {:pg/type "text[]"} [:maybe text]]]]] (:row (h/analyze docs {:select [[e :x]] :from [:docs]} {}))) (pr-str e))))

(deftest an-array-columns-argument-is-an-array
  (let [body {:insert-into :docs :values [{:id 1 :paths :?paths :meta :?meta}]}]
    (is (= {:paths [:maybe [:or [:pg/array {:pg/empty-classes #{"[Ljava.lang.String;"}} [:maybe text]]
                            (shape/merge-props pg-object {:error/message "should be a PGobject of type text[]" :pgmalli/describe "PGobject of text[]" :pgmalli/element text})]]
            :meta [:or [:map {:pg/type "jsonb"} [:a :int]] pg-object]}
           (select-keys (:args (h/analyze docs body)) [:paths :meta]))
        "an assigned array is a Java array of elements or NULLs, or a PGobject of the array type: a vector goes as jsonb")
    (is (= "PGobject of text[]" (pgmalli/describe docs (:p (:args (h/analyze docs {:select [:id] :from [:docs] :where [:= :paths :?p]})))))
        "compared, an array of text[]'s type alone, which no Java array goes as (a String[] goes as varchar[])")))

(deftest an-array-argument-takes-no-byte-array-which-goes-as-a-bytea
  (doseq [q [{:select [:id] :from [:users] :where [:= :id [:any :?x]]}
             {:select [:x] :from [[[:unnest :?x] :x]]}]]
    (is (not (m/validate (:x (args q)) (.getBytes "a") {:registry registry})) (pr-str q))
    (is (m/validate (:x (args q)) (long-array [1]) {:registry registry}) (pr-str q))))

(deftest a-java-array-of-arrays-goes-as-the-array-type-its-component-does
  (let [t (:x (args {:select [:id] :from [:users] :where [:= :id [:any :?x]]}))
        ok? #(m/validate t % {:registry registry})]
    (is (ok? (into-array (Class/forName "[J") [(long-array [1 2]) (long-array [3 4])])) "pgjdbc sends a long[][] as a bigint[]")
    (is (not (ok? (into-array (Class/forName "[J") [(long-array [1 2]) (long-array [3])]))) "arrays not all as long, which pgjdbc cannot send")
    (is (not (ok? (into-array (Class/forName "[Ljava.lang.String;") [(into-array String ["a"])]))) "a varchar[]")))

(deftest a-whole-array-compared-takes-an-array-of-the-columns-type-alone
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                       [:ints [:vector {:pg/type "integer[]"} :pg/integer]]
                                                       [:bigs [:vector {:pg/type "bigint[]"} :int]]
                                                       [:vcs [:vector {:pg/type "character varying[]"} :string]]]}})
        ok? (fn [q v] (m/validate (:x (:args (h/analyze reg q))) v {:registry reg}))]
    (is (ok? {:select [:*] :from [:t] :where [:= :ints :?x]} (into-array Integer [(int 1)])))
    (is (not (ok? {:select [:*] :from [:t] :where [:= :ints :?x]} (long-array [1]))) "integer[] = bigint[] does not resolve")
    (is (not (ok? {:select [:*] :from [:t] :where [(keyword "&&") :ints :?x]} (long-array [1]))) "&& too, anyarray's")
    (is (ok? {:select [:*] :from [:t] :where [(keyword "@>") :bigs :?x]} (long-array [1])))
    (is (ok? {:select [:*] :from [:t] :where [:= :bigs :?x]} (into-array Long [1])) "a Long[] too, which goes as bigint[] as a long[] does")
    (is (ok? {:select [:*] :from [:t] :where [(keyword "&&") :vcs :?x]} (into-array String ["a"])))
    (is (ok? {:update :t :set {:ints :?x}} (long-array [1])) "assigned, element by element")
    (is (ok? {:select [:*] :from [:t] :where [:= :bigs [:any :?x]]} (into-array Integer [(int 1)])) "= ANY compares an element")))

(deftest an-any-or-all-argument-is-an-array
  (let [body {:select [:id] :from [:users] :where [:and [:= :id [:any :?ids]] [:<> :group_id [:all :?gs]]]}]
    (is (= {:ids [:pg/array bigint-cmp] :gs [:pg/array integer-cmp]} (args body))
        "the Java arrays pgjdbc sends as an array of the column's type: a long[] as bigint[], an int[] as integer[]")
    (let [t (:ids (args body))]
      (doseq [v [(long-array [1 2]) (into-array Long [1]) (long-array [])]] (is (m/validate t v {:registry registry}) (pr-str v)))
      (doseq [v [(object-array [1]) (into-array String ["a"]) [1] '(1) #{1} nil]] (is (not (m/validate t v {:registry registry})) (pr-str v)))
      (is (= [:pg/array bigint-cmp] t)
          ":pg/array is what the driver takes (Object[] it refuses); a vector goes as jsonb, no array"))))

(deftest types-of-parameters-and-rows
  (let [body {:select [:id :nick :closed_at [:group_id :g]] :from [:users] :where [:and [:= :id :?id] [:in :mood :?moods] [:> :score :?min]] :limit :?n}]
    (is (= {:id bigint-cmp
            :moods (in-list mood)
            :min integer-cmp
            :n [:maybe [:int {:pg/type "bigint" :min 0}]]}
           (args body)))
    (let [row (:row (h/analyze registry body opts))]
      (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]
              [:nick [:maybe [:string {:pg/max-length 40 :pg/type "character varying"}]]]
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
    (is (= {:x bigint-cmp} (args {:select [:id] :from [:users] :where [:and [:= :nope :?x] [:= :id :?x]]}))
        "an untyped use never hides a typed one")
    (is (= [:=> {:pgmalli/problems []} [:cat bigint-cmp :any] [:sequential {:max 1 :min 1} [:map [:n bigint]]]]
           (h/query-schema registry [:id :extra] {:select [[[:count :*] :n]] :from [:users] :where [:= :id :?id]} opts))
        "an arg used nowhere is :any")
    (is (= [:sequential {:max 1} [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]]]
           (last (h/query-schema registry [:id] {:select [:id] :from [:users] :where [:= :id :?id]} opts))))))

(deftest scope-follows-the-statement
  (is (= [] (check {:delete-from :users :where [:in :group_id {:select [:id] :from [:groups] :where [:= :name :?n]}]}))
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
  (let [with-interval (assoc registry :pg.sample/spans [:map {:pg/table "sample.spans"} [:id :int] [:span [:time/duration {:pg/type "interval"}]]])]
    (is (= [:map [:span [:any {:pg/type "interval"}]]] (:row (h/analyze with-interval {:select [:span] :from [:spans]} opts))) "an interval is a driver object"))
  (is (= {:ids bigint-cmp}
         (args {:select [:id] :from [:users] :where [:and [:in :nope :?ids] [:= :id :?ids]]}))
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
                    :left-join [[{:select-distinct-on [[:group_id] :group_id :score] :from [:users] :where [:= :group_id :?g] :order-by [[:group_id :asc] [:id :desc]]} :latest]
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
    [{:with [[:f {:select [:id] :from [:users]} :materialized] [:g {:select [:id] :from [:f]}]] :select [:id] :from [:g]}
     []
     "a CTE with a qualifier"]
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
    (is (= {:m mood}
           (args {:insert-into :users :columns [:group_id :mood :score] :values [[1 :?m 2]]})))
    (is (= {:gid integer-cmp}
           (args {:select [:id] :from [:users] :where [:= :?gid :group_id]}))
        "an argument on the left takes the column's type")))

(deftest an-expression-is-a-statement-the-data-does-not-show
  (is (= [] (check [:exists {:select [1] :from [:groups] :where [:= :groups.id :skills/group_id]}]))
      "the enclosing statement is not in the data: its columns cannot be judged")
  (is (= [{:kind :unknown-column :column :groups.nope :in [1]}]
         (check [:exists {:select [1] :from [:groups] :where [:= :groups.nope :skills/group_id]}]))
      "its own columns still are"))

(deftest one-row-results
  (is (= [:=> {:pgmalli/problems []} [:cat bigint-cmp] [:maybe [:map [:nick [:maybe [:string {:pg/type "character varying" :pg/max-length 40}]]]]]]
         (h/query-schema registry [:id] {:select [:nick] :from [:users] :where [:= :id :?id]} (assoc opts :result :one)))
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
      (is (= [:=> {:pgmalli/problems []} [:cat] counted] (h/query-schema registry [] q (assoc opts :result :one))) "execute-one! returns it, never nil"))
    (is (= [:map [:id int4]]
           (row {:delete-from :groups :returning [:id]})))
    (is (= [:map [:id int4] [:name text]]
           (row {:delete-from :groups} (assoc-in opts [:jdbc :return-keys] true)))
        "pgjdbc adds RETURNING * under :return-keys true")
    (is (= [:map [:name text]]
           (row {:update :groups :set {:name "b"}} (assoc-in opts [:jdbc :return-keys] ["name"]))))
    (is (= [:=> {:pgmalli/problems []} [:cat] [:maybe [:map [:name text]]]]
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
    (is (= [{:kind :unknown-column :column :nmae :did-you-mean [:name]}]
           (check {:delete-from :groups} (assoc-in opts [:jdbc :return-keys] ["nmae"])))
        "check reads the RETURNING :return-keys adds")
    (is (= [{:kind :unknown-column :column :group-id :did-you-mean [:group_id]}]
           (check {:update :users :set {:nick "a"}} (assoc-in opts [:jdbc :return-keys] [:group-id])))
        "pgjdbc sends a :return-keys name quoted, as written")
    (is (= [] (check {:update :users :set {:nick "a"} :returning [:id]} (assoc-in opts [:jdbc :return-keys] [:group-id])))
        "an explicit RETURNING leaves :return-keys unused")))

(deftest an-expression-has-the-type-postgresql-gives-it
  (let [row #(:row (h/analyze registry {:select % :from [:users]} opts))
        nick [:string {:pg/type "character varying"}]]
    (is (= [:map [:n bigint]] (row [[[:count :nick] :n]])) "count is never NULL")
    (is (= [:map [:s [:maybe bigint]]] (row [[[:sum :group_id] :s]])) "sum of an integer is a bigint, NULL over no row")
    (is (= [:map [:a [:maybe [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]] (row [[[:avg :group_id] :a]])))
    (is (= [:map [:n [:maybe [:string {:pg/max-length 40 :pg/type "character varying"}]]]] (row [[[:coalesce :nick] :n]]))
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
    (is (= [:map [:i int4] [:b [:int {:pg/type "bigint"}]] [:n [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]] [:u text] [:s text] [:nan [:maybe :any]]]
           (:row (h/analyze registry {:select [[1 :i] [3000000000 :b] [1.5 :n] [#uuid "00000000-0000-0000-0000-000000000001" :u] ["s" :s] [##NaN :nan]] :from [:users]}
                            (assoc opts :format {:inline true}))))
        "an inlined number is of the type PostgreSQL gives its literal, an integer by its range; a UUID and a string are quoted, text; NaN is no literal (a name)")
    (is (= [:map [:s [:string {:pg/type "character varying"}]]] (:row (h/analyze registry {:select [["s" :s]] :from [:users]} opts))))
    (is (= [:map [:b [:maybe [:int {:pg/type "bigint"}]]]] (row [[[(keyword "~") :id] :b]])) "~ alone is bitwise NOT, of a bigint a bigint")
    (is (= [:map [:k [:maybe text]] [:v [:maybe :any]]] (row [[[:->> [:cast :nick :jsonb] "k"] :k] [[:->> :nick "k"] :v]]))
        "jsonb ->> text is text; PostgreSQL has no ->> of a varchar")
    (is (= [:map [:s [:maybe [:or ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]] (row [[[:sum :id] :s]]))
        "sum of a bigint is a numeric")
    (is (= [:map [:mood [:maybe mood]] [:n [:maybe bigint]]]
           (:row (h/analyze registry {:select [:mood [[:count :*] :n]] :from [:users] :group-by [[:rollup :mood]]} opts)))
        "ROLLUP adds rows whose columns are NULL")))

(deftest an-argument-compared-with-an-expression-takes-its-type
  (let [args #(:args (h/analyze registry {:select [:id] :from [:users] :where %} opts))
        text [:or text :keyword]]
    (is (= {:k text} (args [:= [:->> [:cast :nick :jsonb] "k"] :?k])))
    (is (= {:t text} (args [:not [:= [:cast :id :text] :?t]])))
    (is (= {:ts (in-list text)} (args [:in [:cast :id :text] :?ts])))
    (is (= {:s integer-cmp :i bigint-cmp}
           (args [:< [:composite :score :id] [:composite :?s :?i]]))
        "a row compares element by element")
    (is (= {:a text :b text} (args [:between [:cast :id :text] :?a :?b])))
    (is (= {:x text} (args [:= [:upper :nick] :?x])) "upper of a varchar is text")
    (is (= {:x [:any {:pgmalli/untyped :type}]} (args [:= [:public.my-fn :nick] :?x])) "an expression of no known type types nothing")
    (let [check #(:problems (h/analyze registry {:select [:id] :from [:users] :where %} opts))]
      (is (= [] (check [:= [:cast :nick :mood] :?m]))
          "an argument is arg-types'")
      (is (= [] (check [:= [:composite :mood :id] [:composite :?m :?i]]))
          "a row is checked element by element")
      (is (= [{:kind :unknown-column :column :nope}] (check [:< [:composite :nope :id] [:composite 1 2]]))))))

(deftest an-insert-select-assigns-its-items-to-the-columns-listed
  (let [q {:insert-into [[:users [:group_id :mood :score :nick]] {:select [[:?g] [[:cast :?m :mood]] [[:unnest :?s]] [:?n :nick_alias]]}]}]
    (is (= {:g int4
            :m [:enum {:pg/type "mood" :pgmalli/cast "mood"} "happy" "sad" :happy :sad]
            :s [:pg/array {:pgmalli/type-from-value "unnest"} int4]
            :n nick-assigned}
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
  (is (= [:map [:m [:enum {:pg/type "mood"} :happy :sad]] [:i int4]
          [:b [:boolean {:pg/type "boolean"}]] [:f [:double {:pg/type "double precision"}]]]
         (row {:select [[[:cast "sad" :mood] :m] [[:cast :id :int] :i] [[:cast true :bool] :b] [[:cast :score :float] :f]] :from [:users]}))))

(deftest a-raw-cast-target-is-read-only-as-one-types-name
  (is (= [:map [:a [:vector {:pg/type "bigint[]"} [:maybe :int]]]
          [:v [:vector {:pg/type "character varying[]"} [:maybe :string]]]
          [:t [:time/instant {:pg/type "timestamp with time zone"}]] [:d [:double {:pg/type "double precision"}]]
          [:m [:vector {:pg/type "mood[]"} [:maybe [:enum {:pg/type "mood"} :happy :sad]]]]
          [:l [:maybe :any]]]
         (row {:select [[[:cast :id [:raw " BIGINT [ ] "]] :a] [[:cast :id [:raw "varchar(10)[]"]] :v]
                        [[:cast :id [:raw "timestamp(3) with time zone"]] :t] [[:cast :id [:raw "double precision"]] :d]
                        [[:cast :id [:raw "mood[]"]] :m] [[:cast :id [:raw "lower(x)"]] :l]]
               :from [:users]}))))

(deftest an-argument-takes-its-slots-own-values
  (let [native opts
        args #(:args (h/analyze registry {:select [:id] :from [:users] :where %} (merge native %2)))
        numeric ['decimal? {:pg/type "numeric"}]]
    (is (= {:x bigint-cmp} (args [:= :id :?x] {})) "a column's own values")
    (is (= {:x [:or numeric 'integer? :int :double 'float?]} (args [:= [:cast :id :numeric] :?x] {}))
        "a numeric takes an integer too, a BigInt and a BigInteger as pgjdbc sends any Number: the same number")
    (is (= {:x bigint-cmp} (args [:= :id :?x] {})) "an untyped string is no bigint's own value")))

(deftest a-cast-argument-takes-what-postgresql-casts
  (let [args #(:args (h/analyze registry % opts))
        cast-to-bigint [:or {:pgmalli/cast "bigint"} bigint 'decimal? 'integer? :double 'float? :string 'bytes? :keyword pg-object]]
    (is (= {:x cast-to-bigint} (args {:select [:id] :from [:users] :where [:= :id [:cast :?x :bigint]]}))
        "compared: NULL never matches")
    (is (= {:x [:maybe cast-to-bigint]} (args {:select [[[:cast :?x :bigint] :n]]})) "selected: NULL is a value")
    (let [{:keys [x n]} (args {:insert-into [[:users [:score :nick]] {:select [[[:cast :?x :bigint]] [[:cast :?n :text]]]}]})]
      (is (= cast-to-bigint x) "assigned to a NOT NULL column: no NULL")
      (is (= :maybe (first n)) "assigned to a nullable one: NULL"))
    (is (= {:x [:enum {:pg/type "mood" :pgmalli/cast "mood"} "happy" "sad" :happy :sad]} (args {:select [:id] :from [:users] :where [:= :mood [:cast :?x :mood]]})))
    (is (= {:x [:maybe [:enum {:pg/type "mood" :pgmalli/cast "mood"} "happy" "sad" :happy :sad]]} (args {:select [[[:cast :?x :mood] :m]]}))
        "cast, an enum's labels as strings")))

(deftest limit-and-offset-take-nil-for-none
  (let [t (:n (args {:select [:id] :from [:users] :limit :?n}))]
    (is (= [:maybe [:int {:pg/type "bigint" :min 0}]] t))
    (is (every? #(m/validate t %) [nil 0 1]))
    (is (not (m/validate t -1)) "PostgreSQL refuses a negative LIMIT")))

(deftest an-argument-is-true-false-or-unknown-tests-is-a-boolean-and-one-is-null-tests-untyped
  (doseq [w [[:is-not :?x true] [:is :?x false] [:is-not :?x [:inline false]] [:is :?x :unknown] [:is :?x :TRUE]]]
    (is (= {:x [:maybe [:boolean {:pg/type "boolean"}]]} (args {:select [:id] :from [:users] :where w})) (pr-str w)))
  (doseq [w [[:is :?x nil] [:is-not :?x nil]]]
    (is (= {:x [:any {:pgmalli/untyped :position}]} (args {:select [:id] :from [:users] :where w})) (pr-str w)))
  (is (= {:x [:any {:pgmalli/untyped :position}]} (args {:select [:id] :from [:users] :where [:is :?x :true]} (assoc opts :format {:quoted true})))
      "quoted, :true is a column"))

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
  (doseq [[o says] [[{:write [[:keyword :unknown]]} #":write.* is not read: pgmalli types arguments as pgmalli-next.jdbc/install-sending! sends them"]
                    [{:stringtype :unspecified} #":stringtype.* is not read: pgmalli types arguments as pgmalli-next.jdbc/install-sending! sends them"]
                    [{:qualified? false} #":qualified\? false.* is not read: .*\{:builder-fn \(pgmalli-next.jdbc/builder registry\)\}"]
                    [{:kebab? true} #":kebab\? true.* is not read"]
                    [{:return-keys true} #":return-keys is not read: pass it as \{:jdbc \{:return-keys ...\}\}$"]]]
    (let [e (try (args {:select [:id] :from [:users]} o) nil (catch clojure.lang.ExceptionInfo e e))]
      (is (re-find says (str (ex-message e))) (pr-str o))
      (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))) (pr-str o)))))

(deftest a-schema-the-registry-lacks-is-an-error-however-the-table-is-named
  (doseq [t [:users :pg.sample/users]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the registry holds no schema \"nope\"" (h/values-schema registry t {:schema "nope"})) (pr-str t))))

(deftest a-written-table-is-named-as-postgresql-reads-it
  (is (= (h/values-schema registry :users opts) (h/values-schema registry :Users opts) (h/values-schema registry :sample.USERS opts))
      "an unquoted name folded")
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes a table as :table or :schema.table; :sample.users.x names more"
                                (h/set-schema registry :sample.users.x opts)))]
    (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))))))

(deftest no-registry-is-said-so-whatever-was-read-before
  (reset! @#'query/last-read nil)
  (is (= :pgmalli.error/not-a-registry
         (:pgmalli/error (ex-data (is (thrown? clojure.lang.ExceptionInfo (h/analyze nil {:select [:id] :from [:users]}))))))))

(deftest a-registry-where-the-query-goes-names-the-order
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes the registry first, then the query"
                        (h/analyze {:select [:id] :from [:users]} registry))))

(deftest schema-problems-names-what-it-takes
  (doseq [o [{:ns "pgmalli-next.honeysql-test"} {:ns :x} {:namespace 'x} 'pgmalli-next.honeysql-test]]
    (let [e (is (thrown? clojure.lang.ExceptionInfo (h/schema-problems o)) (pr-str o))]
      (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))) (pr-str o))))
  (is (= [:unknown-column] (map :kind (h/schema-problems {:ns #{'pgmalli-next.honeysql-test}}))) "a set of symbols"))

(deftest an-option-the-function-does-not-read-is-an-error
  (doseq [[f o] [[#(pgmalli/as-read registry :pg.sample/users %) {:result :one}]
                 [#(h/analyze registry {:select [:id] :from [:users]} %) {:result :one}]
                 [#(h/values-schema registry :users %) {:result :one}]
                 [#(h/set-schema registry :users %) {:result :one}]]]
    (let [e (is (thrown? clojure.lang.ExceptionInfo (f o)) (pr-str o))]
      (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))) (pr-str o))
      (is (re-find #"disallowed key" (ex-message e)) (pr-str o))))
  (is (= :any (last (h/query-schema registry [] {:select [:id] :from [:nope]} (assoc opts :result :one)))) "query-schema takes :result")
  (doseq [o ["sample" [:schema "sample"] #{:schema}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be a map of options" (h/analyze registry {:select [:id] :from [:users]} o)) (pr-str o)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be the map of options you pass to next.jdbc" (h/analyze registry {:select [:id] :from [:users]} {:jdbc []}))))

(deftest a-builder-other-than-pgmallis-is-an-error-naming-it
  (let [pgmallis (with-meta (fn [_ _]) {:pgmalli-next.jdbc/builder (shape/enum-type-names registry)})]
    (doseq [[jdbc refused] [[{:builder-fn 'next.jdbc.result-set/as-unqualified-maps} ":builder-fn"]
                            [{:builder-fn 'pgmalli-next.jdbc/builder} ":builder-fn"]
                            [{:builder-fn (fn [_ _])} ":builder-fn"]
                            [{:label-fn str/lower-case} ":label-fn"]
                            [{:builder-fn pgmallis :qualifier-fn str/lower-case} ":qualifier-fn"]]]
      (let [e (try (row {:select [:id] :from [:users]} (assoc opts :jdbc jdbc)) nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= (str ":jdbc's " refused " is not read: pgmalli types rows as pgmalli-next.jdbc/builder builds them")
               (re-find #":jdbc's .* builds them" (str (ex-message e))))
            (pr-str jdbc))
        (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))))))
    (is (= (row {:select [:id] :from [:users]})
           (row {:select [:id] :from [:users]} (assoc opts :jdbc {:builder-fn pgmallis})))
        "pgmalli's builder, as the map an application passes next.jdbc holds it")
    (let [e (try (row {:select [:id] :from [:users]} (assoc opts :jdbc {:builder-fn (vary-meta pgmallis update :pgmalli-next.jdbc/builder disj "mood")}))
                 nil (catch clojure.lang.ExceptionInfo e e))]
      (is (re-find #"was made of a registry of other enums \(mood\)" (str (ex-message e)))
          "a builder of another registry reads an enum it lacks as a String"))))

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
  (is (= {:ids [:pg/array bigint-cmp]} (args {:select [:id] :from [:users] :where [:= :id [:any :?ids]]} {:schema "sample"}))
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
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:at [:time/instant {:pg/type "timestamp with time zone" :pg/domain "at3"}]]]}})]
    (is (m/validate (:x (:args (h/analyze reg {:update :t :set {:at :?x}}))) (java.time.OffsetDateTime/now) {:registry reg}))))

(deftest a-keyword-goes-in-a-cast-too
  (let [o {:schema "sample"}]
    (is (m/validate (:x (args {:select [[[:cast :?x :text] :t]]} o)) :skill {:registry registry}))
    (is (= [:double {:pg/type "double precision"}]
           (second (:x (args {:select [:id] :from [:users] :where [:= [:cast :id :double-precision] :?x]} o))))
        "a column's own values are kept whole, first")))

(deftest a-comparison-in-a-select-or-order-by-is-read
  (is (= {:t bigint-cmp :o integer-cmp}
         (args {:select [:id [[:case [:= :id :?t] 1 :else 0] :c]] :from [:users] :order-by [[[:case [:= :score :?o] 0 :else 1]]]})))
  (is (= [{:kind :unknown-column :column :nope}] (check {:select [[[:case [:= :nope 1] 1 :else 0] :c]] :from [:users]}))))

(deftest a-map-goes-as-jsonb
  (is (= {:m [:or [:map {:pg/type "jsonb"} [:a :int]] pg-object]}
         (:args (h/analyze docs {:update :docs :set {:meta :?m} :where [:= :id 1]} {})))
      "what goes as a shaped json column's type takes its shape")
  (is (= {:m [:or [:map-of :some :any] [:vector :any] pg-object]}
         (:args (h/analyze (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:j [:any {:pg/type "jsonb"}]]]}})
                      {:update :t :set {:j :?m}} {})))
      "what goes as a type pgmalli has the driver's object for is all its argument takes"))

(deftest a-string-of-another-string-type-is-a-string-columns-own
  (is (= {:n [:maybe [:or text :keyword]]}
         (args {:update :users :set {:group_name :?n} :where [:= :id 1]} {:schema "sample"}))))

(deftest an-enum-domain-takes-its-labels-alone
  (let [reg (pgmalli/registry {:registry {:pg.public/mood [:enum "ok" "sad"]
                                          :pg.public/t [:map {:pg/table "public.t"} [:g [:ref {:pg/type "mood" :pg/domain "good"} :pg.public/mood]]]}})
        t (:g (:args (h/analyze reg {:update :t :set {:g :?g}} {})))]
    (is (m/validate t :sad {:registry reg}))
    (is (not (m/validate t :glad {:registry reg})) "no keyword but a label")))

(deftest min-and-max-of-a-domain-are-of-its-base-type
  (let [reg (pgmalli/registry {:registry {:pg.public/mood [:enum "ok" "sad"]
                                          :pg.public/t [:map {:pg/table "public.t"}
                                                        [:c [:string {:pg/type "character varying" :pg/max-length 3 :pg/domain "code3"}]]
                                                        [:p [:pg/integer {:pg/type "integer" :pg/domain "pos"}]]
                                                        [:m [:ref {:pg/type "mood" :pg/domain "mood_d"} :pg.public/mood]]]}})
        row #(:row (h/analyze reg {:select [[[:max %] :x]] :from [:t]} {}))]
    (is (= [:map [:x [:maybe text]]] (row :c)) "max(varchar) is max(text)")
    (is (= [:map [:x [:maybe [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]] (row :p)))
    (is (= [:map [:x [:maybe [:enum {:pg/type "mood"} :ok :sad]]]] (row :m)) "a domain over an enum is the enum")))

(deftest a-domain-over-bit-or-numeric-is-read-as-the-driver-reads-its-base
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:d1 [:string {:pg/type "bit" :pg/max-length 1 :pg/domain "d1"}]]
                                                        [:d4 [:string {:pg/type "bit" :pg/max-length 4 :pg/domain "d4"}]]
                                                        [:dv [:string {:pg/type "bit varying" :pg/max-length 5 :pg/domain "dv"}]]]}})]
    (is (= [:map [:d1 [:boolean {:pg/type "bit"}]] [:d4 [:any {:pg/type "bit"}]] [:dv [:any {:pg/type "bit varying"}]]]
           (:row (h/analyze reg {:select [:d1 :d4 :dv] :from [:t]} {})))
        "a Boolean for bit(1), a PGobject otherwise"))
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:n ['decimal? {:pg/type "numeric" :pg/domain "nm"}]]]}})]
    (is (= [:map [:n [:or {:pg/domain "nm"} ['decimal? {:pg/type "numeric"}] ['double? {:pg/type "numeric"}]]]]
           (:row (h/analyze reg {:select [:n] :from [:t]} {})))
        "a numeric domain's NaN is a Double")))

(deftest a-field-of-a-composite-column-is-assigned-to-that-column-its-value-unchecked
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:id :int]
                                                        [:p [:maybe [:map {:pg/type "pair"} [:a :int] [:b :string]]]]]}})
        check #(:problems (h/analyze reg % {}))]
    (is (= [] (check {:update :t :set {:p.a 5} :where [:= :id 1]})))
    (is (= [] (check {:update :t :set {:p.b :?v} :where [:= :id 1]})))
    (is (= [] (check {:insert-into [[:t [:id :p.a]] {:values [[1 5]]}]})))
    (is (= [] (check {:insert-into :t :columns [:id :p.a] :values [[1 5]]})))
    (is (= [] (check {:insert-into :t :values [{:id 1 :p.a 5}]})))
    (is (= [{:kind :unknown-column :column :q :did-you-mean [:p]}] (check {:update :t :set {:q.a 5} :where [:= :id 1]}))
        "the column of the field is looked for")
    (is (= {:v [:any {:pgmalli/untyped :position}]} (:args (h/analyze reg {:update :t :set {:p.b :?v} :where [:= :id 1]}))))))

(deftest an-array-cast-to-an-array-domain-is-any-array-postgresql-casts
  (let [reg (pgmalli/registry {:registry {:pg.public/ints_d [:vector {:pg/type "integer[]"} :pg/integer]}})
        t (:x (:args (h/analyze reg {:select [[[:cast :?x :ints_d] :c]]})))]
    (doseq [v [(double-array [1.0]) (into-array BigDecimal [1M]) (boolean-array [true]) (into-array String ["1"]) (long-array [1])]]
      (is (m/validate t v {:registry reg}) (pr-str (class v))))))

(deftest an-untyped-column-written-takes-no-keyword-vector-or-sequence
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:id :int]
                                                        [:j [:any {:pg/type "json"}]]
                                                        [:ip [:maybe [:any {:pg/type "inet"}]]]]}})]
    (doseq [[o default] [[{} :default] [{:format {:quoted true}} [:default]]]
            [f row] [[h/values-schema {:id 1 :j [:lift {}]}] [h/set-schema {}]]
            :let [ok? #(m/validate (f reg :t o) (merge row %) {:registry reg})]]
      (is (every? ok? [{:j [:lift {}]} {:j [:lift [1]]} {:ip nil}]) "a json document as jsonb, which PostgreSQL assigns to json")
      (is (not-any? ok? [{:j "{}"} {:j :k} {:ip :k} {:j [1 2]} {:ip '("a")} {:j (list 1)}]) (pr-str f o))
      (is (not (ok? {:ip "10.0.0.1"})) "a String goes as varchar, which an inet column does not take: a PGobject of inet")
      (is (ok? {:ip default}) "HoneySQL's DEFAULT where the database fills a column"))))

(deftest an-integer-is-a-bigint-columns-own
  (is (m/validate (:x (args {:select [:id] :from [:users] :where [:= :id :?x]} {:schema "sample"})) (int 1) {:registry registry})))

(deftest a-registry-without-the-derived-schema-says-so
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"the registry has no insert schema"
                        (h/values-schema (merge (m/default-schemas) {:pg.public/t [:map {:pg/table "public.t"} [:id :int]]}) :pg.public/t))
      "a hand-written row schema has none"))

(deftest an-untyped-value-is-a-text-or-enum-columns-own
  (is (= {:x [:enum {:default :happy :pg/default "happy" :pg/type "mood"} :happy :sad]}
         (args {:select [:id] :from [:users] :where [:= :mood :?x]} {:schema "sample"}))
      "a keyword sent untyped is an enum's label, a String sent as varchar is not")
  (is (= {:x [:any {:pg/type "json"}]}
         (:args (h/analyze (pgmalli/registry {:registry {:pg.public/j [:map {:pg/table "public.j"} [:doc [:any {:pg/type "json"}]]]}})
                      {:select [:doc] :from [:j] :where [:= :doc :?x]} {})))
      "json has no =, untyped or not")
  (is (= [:uncast-string] (map :kind (check {:select [:id] :from [:users] :where [:= :mood "sad"]} {:schema "sample"})))
      "a String literal goes as varchar")
  (is (= [] (check {:select [:id] :from [:users] :where [:= :mood :?m]} {:schema "sample"}))
      "an argument is arg-types'"))

(deftest a-cast-to-a-type-pgmalli-does-not-answer-for-takes-anything
  (let [reg (pgmalli/registry {:registry {}})]
    (is (= {:x [:any {:pgmalli/untyped :type}]} (:args (h/analyze reg {:select [[[:cast :?x :ltree] :b]]}))) "an extension's casts are not in pg_catalog")
    (is (m/validate (:x (:args (h/analyze reg {:select [[[:cast :?x :bit] :b]]}))) 1 {:registry reg}) "PostgreSQL casts a bigint to bit")))

(deftest a-json-document-cast-to-a-scalar-type-is-not-taken
  (let [reg (pgmalli/registry {:registry {}})
        o {}
        x (fn [t] (:x (:args (h/analyze reg {:select [[[:cast :?x t] :b]]} o))))]
    (doseq [t [:int :numeric :boolean :double-precision]]
      (is (not (m/validate (x t) {:a 1} {:registry reg})) (str "a jsonb object cast to " t " errors"))
      (is (not (m/validate (x t) [1] {:registry reg})) (str "a jsonb array cast to " t " errors")))
    (doseq [t [:text :varchar :jsonb]]
      (is (m/validate (x t) {:a 1} {:registry reg}) (str "a jsonb object casts to " t))
      (is (m/validate (x t) [1] {:registry reg}) (str "a jsonb array casts to " t)))))

(deftest an-arrays-elements-are-read-as-the-columns-type
  (let [t (:xs (args {:select [:id] :from [:users] :where [:= :id [:any :?xs]]} {:schema "sample"}))]
    (is (m/validate t (long-array [1]) {:registry registry}))
    (is (not (m/validate t (into-array String ["1"]) {:registry registry})) "a varchar[] is no bigint[]'s own"))
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
    (is (= [:born :closed_at :group_id :group_name :mood :nick :referrer_id :score :seq :total :updated_at :id :nick_upper] (map first (rest (shape/and-base (:gen/schema (second v))))))
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
    (is (= {:group-id ["disallowed key: the column is :group_id, the key a row is read with"]} (me/humanize (m/explain v {:group-id 1 :group_id 1 :score 0} {:registry registry}))))
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

(deftest an-opaque-table-beside-a-known-one-makes-no-column-ambiguous
  (let [t [[:jsonb_to_record :?doc] [:t {:columns [[:raw "id bigint"]]}]]]
    (is (= [] (check {:select [:nick] :from [:users] :join [t [:= :t.id :users.id]]})))
    (is (= [:map [:nick [:maybe :any]]] (row {:select [:nick] :from [:users] :join [t [:= :t.id :users.id]]}))
        "untyped: which table PostgreSQL takes it from is not in the data")
    (is (= [{:kind :unknown-column :column :u.nope}] (check {:select [:u.nope] :from [[:users :u]] :join [t [:= 1 1]]}))
        "a known table's unknown column is still one")))

(deftest expression-names-follow-postgresql
  (let [row #(:row (h/analyze registry {:select [%] :from [:users]} opts))]
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
  (let [nullable #(into {} (for [[a v] (#'query/scope (query/reading registry) % #{} opts)] [a (boolean (:nullable? v))]))]
    (is (= {"u" false "x" true "g" false "i" false}
           (nullable {:select [:*] :from [[:users :u] [:groups :x]] :right-join [[:groups :g] [:= 1 1]] :inner-join [[:groups :i] [:= 1 1]]}))
        "a comma binds looser than JOIN: the right join attaches to the last FROM item; an inner join after it nulls nothing")))

(deftest a-typed-use-wins-over-a-driver-object
  (is (= {:x [:and {:pgmalli/uses true} bigint-cmp [:or [:map {:pg/type "jsonb"} [:a :int]] pg-object]]} (:args (h/analyze docs {:select [:id] :from [:docs] :where [:and [:= :meta :?x] [:= :id :?x]]})))
      "a jsonb compared: the values sent as jsonb"))

(deftest a-set-operations-row-keeps-a-null-columns-key
  (is (= [:map
          [:id [:int {:pg/identity :always :pg/type "bigint"}]]
          [:nick [:maybe [:string {:pg/max-length 40 :pg/type "character varying"}]]]]
         (row {:union [{:select [:id :nick] :from [:users]} {:select [:id :nick] :from [:users]}]} opts))))

(deftest query-schema-carries-the-problems-check-finds
  (let [s (h/query-schema registry [:id] {:select [:nick_name] :from [:users] :where [:= :id :?id]} opts)]
    (is (= {:pgmalli/problems [{:kind :unknown-column :column :nick_name}]} (m/properties (m/schema s))))
    (is (= :any (last s)) "the row it cannot tell"))
  (is (= {:pgmalli/problems []} (m/properties (m/schema (h/query-schema registry [:id] {:select [:nick] :from [:users] :where [:= :id :?id]} opts) {:registry registry})))
      "none on a query that agrees"))

(deftest nil-options-are-none
  (is (= [] (:problems (h/analyze {:app/x :int} {:select [1]} nil))))
  (is (= (pgmalli/as-read registry :pg.sample/users) (pgmalli/as-read registry :pg.sample/users nil))))

(deftest a-like-pattern-is-no-value-of-the-column
  (let [overridden (pgmalli/registry {:registry {:pg.public/roles [:map {:pg/table "public.roles"}
                                                                   [:role_key [:and {:pg/type "character varying"} :string [:re "^[a-z_]+$"]]]]}})]
    (is (m/validate (:x (:args (h/analyze overridden {:select [:role_key] :from [:roles] :where [:like :role_key :?x]}))) "%adm%")
        "an override's regex holds for a value, not for a pattern"))
  (is (m/validate (:n (args {:select [:id] :from [:users] :where [:ilike :nick :?n]})) (str "%" (apply str (repeat 40 "a")) "%"))
      "longer than the column's 40"))

(deftest insert-into-a-table-as-an-alias-with-columns
  (is (= [{:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check {:insert-into [[:users :u] [:group_id :mood :score]] :values [[:?a "sad" :?c]] :returning [:u.id]}))))

(deftest a-subquery-alone-in-from-is-opaque
  (is (= [] (check {:select [:id] :from [{:select [:id] :from [:users]}]}))))

(deftest every-comparison-honeysql-writes-types-its-arguments
  (let [types (args {:select [:id] :from [:users]
                     :where [:and [:not-in :id :?ids] [:between :score :?lo :?hi] [:not-like :nick :?p] [:is-distinct-from :group_id :?g]]})]
    (is (= [:sequential {:min 1} bigint-cmp] (second (:ids types))) ":not-in as :in")
    (is (= (:lo types) (:hi types) integer-cmp))
    (is (= [:maybe (:lo types)] (:g types)) "IS DISTINCT FROM compares NULL as a value")
    (is (= [:or [:string {:pg/type "character varying"}] :keyword] (:p types)))))

(deftest a-select-of-every-set-operation-and-the-dot
  (is (= [:map [:id [:int {:pg/identity :always :pg/type "bigint"}]]]
         (row {:except-all [{:select [:id] :from [:users]} {:select [:id] :from [:users]}]})))
  (is (= [:map [:nick [:maybe :any]]] (row {:select [[[:. :u :nick]]] :from [[:users :u]]}))
      "[:. a b] is a.b, named b"))

(deftest a-clause-read-nowhere-is-a-problem
  (is (= [{:kind :unread-clause :clause :fetch-next}] (check {:select [:id] :from [:users] :fetch-next 1})))
  (is (= [] (check {:select [:id] :from [:users] :for :update :window [:w {:partition-by [:id]}]})) "clauses that change nothing read here")
  (is (= [{:kind :unknown-column :column :table}
          {:kind :uncast-string :column :mood :value "sad" :type "mood"}] (check {:insert-into :users :values [{:table 1 :group_id 1 :mood "sad" :score 1}]}))
      "a row's column named like a clause is a column"))

(deftest query-schema-names-an-argument-its-args-lack
  (is (= {:pgmalli/problems [{:kind :missing-arg :arg :id}]}
         (m/properties (m/schema (h/query-schema registry [:db :iid] {:select [:id] :from [:users] :where [:= :id :?id]} opts))))))

(deftest a-misspelt-option-names-the-one-meant
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\{:reslt \[\"disallowed key\"\]\}; did you mean: :result" (h/query-schema registry [] {} {:reslt :one}))))

(deftest a-lifted-value-is-data
  (is (= [{:kind :literal-type :function "=" :value {:select true :update true :delete false} :sent "jsonb" :type "bigint"}]
         (check {:select [:id] :from [:users] :where [:= :id [:lift {:select true :update true :delete false}]]}))
      "its keys no clauses: a map sent as jsonb, which a bigint compares with by no =")
  (is (= [] (check {:select [:id] :from [:users] :where [:= :id [:inline {:table "x" :distinct 1}]]}))))

(deftest a-column-definition-list-is-read-or-left-alone
  (is (vector? (check {:select [:a] :from [[[:jsonb_to_record :x] [:r {:columns [[:raw "a int"]]}]]]}))))

(deftest a-call-in-the-wrong-shape-is-an-error
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"takes the function's args first" (h/query-schema registry {:select [:id] :from [:users]} opts)))
  (doseq [body ["SELECT 1" ["SELECT 1"] nil]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"reads HoneySQL query data" (check body)) (pr-str body))))

(deftest a-row-of-a-table-the-registry-lacks-is-nil
  (is (nil? (:row (h/analyze (pgmalli/registry pgmalli-next.sample/both-data) {:select [:id] :from [:users]} {:schema "other"})))))

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
    (is (= [{:kind :ambiguous-column :column :id :candidates ["sample.groups" "sample.users"] :in [:set 0 1]}]
           (check {:update :users :set {:group_id sub}})))))

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
      (is (= [(nn :group_id :values)] (check {:insert-into :users :values [{:group_id nil :mood [:cast "sad" :mood] :score 1 :closed_at :?now}]})))
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

(def ^:private nrql-registered? (try (sql/format {:select [:a]} {:dialect :nrql}) true (catch Exception _ false)))

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
                    {:dialect :nrql :quoted true}]
            ;; a dialect the loaded HoneySQL lacks (:nrql before 2.6) is an error, as sql/format's
            :let [nrql? (= :nrql (:dialect format))]
            :when (or (not nrql?) nrql-registered?)]
      (is (= [] (:problems (h/analyze reg q {:format format}))) (pr-str format)))
    (doseq [format [{} {:quoted-always #"^id$"}
                    {:dialect :ansi :quoted false}
                    {:dialect :ansi :quoted nil}
                    {:dialect :ansi :quoted false :quoted-always #"^id$"}
                    {:dialect :nrql}]
            :when (or (not= :nrql (:dialect format)) nrql-registered?)]
      (is (= [{:kind :not-null-violation :table "public.t" :column :id :clause :set}]
             (:problems (h/analyze reg q {:format format}))) (pr-str format)))
    (when-not nrql-registered?
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be a dialect HoneySQL registers" (h/analyze reg q {:format {:dialect :nrql}}))))))

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
      (is (= [] (:problems (h/analyze reg {:select [:flag] :from [:t] :where [op :flag v]}))) (pr-str [op v])))))

(deftest a-before-trigger-may-fill-what-a-write-leaves-null
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:insert ["fill"]}}
                                                        [:id [:int {:pg/type "integer"}]] [:g [:int {:pg/type "integer"}]]]}})]
    (is (= [] (:problems (h/analyze reg {:insert-into :t :values [{:id 1}]}))) "no :missing-required-column")
    (doseq [v [nil :null [:inline nil] [:lift nil]]]
      (is (= [] (:problems (h/analyze reg {:insert-into :t :values [{:id 1 :g v}]}))) (pr-str v)))
    (is (= [{:kind :not-null-violation :table "public.t" :column :g :clause :set}] (:problems (h/analyze reg {:update :t :set {:g nil}})))
        "an UPDATE has no BEFORE UPDATE trigger here")))

(deftest names-as-honeysql-writes-them
  (is (= [] (check {:select [:system_user :current_user :current_role] :from [:users]})) "SQL value functions are no columns")
  (is (= [] (check {:select [:'nick] :from [:users]})) ":'x is written as x")
  (is (= [] (check {:update :users :set {:x/nick "a"} :where [:= :id 1]})) "HoneySQL drops the namespace of a SET column")
  (is (= [] (check {:insert-into :users :values [{:x/group_id 1 :x/score 1}]})) "and of an INSERT column")
  (is (= [] (check {:insert-into :users :columns [:group_id :score] :values [{:group_id 1 :score 1 :nope 1}]}))
      "a key the column list lacks is not written")
  (is (= [{:kind :unknown-column :column :nope :clause :set :target :nick}] (check {:update :users :set {:nick :nope} :where [:= :id 1]}))
      "a keyword assigned is a column")
  (is (= [] (check {:update :users :set {:nick :group_name} :where [:= :id 1]}))))

(deftest a-string-reaches-an-enum-column-as-varchar
  (let [check #(:problems (h/analyze registry %1 (merge opts %2)))
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
  (is (= {:m [:enum {:pg/type "mood" :pgmalli/cast "mood"} "happy" "sad" :happy :sad]}
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
  (let [ps (:problems (h/analyze (pgmalli/registry pgmalli-next.sample/both-data) {:select [:id] :from [:users]} {:schema "other"}))]
    (is (= [{:kind :unknown-table :table "other.users" :did-you-mean ["sample.users"]}] ps))
    (is (= ["unknown table other.users; sample.users exists; pass {:schema \"sample\"}, or name it :sample.users"] (h/humanize ps)))))

(deftest a-schema-the-registry-holds-nothing-of-is-an-error-naming-the-one-it-holds
  (let [q {:select [:id] :from [:users]}]
    (is (= ["unknown table public.users; sample.users exists; pass {:schema \"sample\"}, or name it :sample.users"]
           (h/humanize (:problems (h/analyze registry q))))
        "a query's unqualified table the default schema lacks is a problem")
    (is (= [] (:problems (h/analyze registry {:select [:id] :from [:sample.users]}))) "every table qualified needs no :schema")
    (doseq [f [#(h/values-schema registry :users %) #(h/set-schema registry :users %)]]
      (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^the registry holds no schema \"public\"; pass \{:schema \"sample\"\}$" (f {})))]
        (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e)))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^the registry holds no schema \"other\"; it holds \"a\", \"b\"$"
                            (:problems (h/analyze (pgmalli/registry {:registry {:pg.a/t [:map {:pg/table "a.t"} [:id :int]] :pg.b/t [:map {:pg/table "b.t"} [:id :int]]}}) q {:schema "other"}))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"; did you mean: \"sample\"$"
                            (:problems (h/analyze (pgmalli/registry pgmalli-next.sample/both-data) q {:schema "smaple"})))))
    (is (= [] (:problems (h/analyze {:app/x :int} {:select [1]}))) "a registry of no generated schema names none")))

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
          "\"sad\" reaches column mood as a typed string, which its type mood does not take (HoneySQL sends a string as a parameter, which pgjdbc sends as varchar); write [:lift :sad]"]
         (h/humanize (check {:select [:nik] :from [:users] :where [:= :mood "sad"]} opts))))
  (is (every? string? (h/humanize [{:kind :something-new}])) "a kind with no sentence still prints"))

(def ^{:malli/schema (h/query-schema registry [:id] {:select [:nik] :from [:users] :where [:= :id :?id]} opts)} a-broken-query-fn identity)

(deftest schema-problems-finds-what-instrumentation-does-not-show
  (is (= [{:kind :unknown-column :column :nik :did-you-mean [:nick] :var #'a-broken-query-fn}]
         (h/schema-problems {:ns 'pgmalli-next.honeysql-test}))))

(deftest a-function-in-from-takes-the-names-its-alias-lists-and-no-types-but-of-record
  (let [reg (pgmalli/registry {:registry {:pg.public/d [:map {:pg/table "public.d"} [:items [:vector {:pg/type "jsonb"} [:map [:k :string]]]]]}})
        a #(h/analyze reg {:select [:r.v] :from [:d [[:jsonb_array_elements :items] [:r %]]]} {})
        element [:maybe [:map {:pg/type "jsonb"} [:k :string]]]]
    (doseq [listed [{:columns [:v]} [:composite :v]]]
      (is (= [:map [:v element]] (:row (a listed))) (pr-str listed)))
    (is (= [:map [:v element] [:n [:int {:pg/type "bigint"}]]]
           (:row (h/analyze reg {:select [:r.v :r.n] :from [:d [[:with-ordinality [:jsonb_array_elements :items] [:r :v :n]]]]} {})))
        "under WITH ORDINALITY, the alias as a call, as HoneySQL writes it there")
    (is (= [{:kind :column-definition-list :function "jsonb_array_elements" :alias "r" :columns [:value]}]
           (:problems (h/analyze reg {:select [:r.value] :from [:d [[:jsonb_array_elements :items] [:r {:with-columns [[:value :jsonb]]}]]]} {})))
        "column types of a function returning no record, which PostgreSQL refuses")
    (is (= [] (:problems (h/analyze reg {:select [:w.k] :from [[[:jsonb_to_record :?doc] [:w {:with-columns [[:k :text]]}]]]} {})))
        "of one returning record")))

(deftest a-function-alias-is-not-held-to-the-columns-it-lists
  (is (= [{:kind :unknown-column :column :t.name}] (check {:select [:t.name] :from [[[:unnest [:array [1 2]]] [:t {:columns [:x]}]]]}))
      "PostgreSQL reads t.name as name(t), almost always a mistake: reported (docs/queries.md, Stricter than PostgreSQL)"))

(deftest write-enum-names-what-an-extension-sends-for-an-enum
  (let [types #(update-vals (args % opts) m/form)]
    (is (= [:enum {:default :happy :pg/default "happy" :pg/type "mood"} :happy :sad]
           (:m (types {:select [:id] :from [:users] :where [:= :mood :?m]})))
        "the enum's labels kept, as keywords")
    (is (= [:enum {:pg/type "mood" :pgmalli/cast "mood"} "happy" "sad" :happy :sad] (:m (types {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]})))
        "an argument cast to an enum, as strings too")))

(deftest keywords-sent-untyped-take-a-columns-labels
  (let [reg (pgmalli/registry {:registry {:pg.public/attempts [:map {:pg/table "public.attempts"}
                                                               [:id [:int {:pg/type "bigint"}]]
                                                               [:role [:string {:pg/type "character varying"}]]
                                                               [:status [:enum {:pg/type "character varying"} "active" "submitted"]]]}})
        o {}]
    (is (= {:status [:enum {:pg/type "character varying"} "active" "submitted" :active :submitted]}
           (update-vals (:args (h/analyze reg {:select [:id] :from [:attempts] :where [:= :status :?status]} o)) m/form))
        "a column with labels keeps them, as keywords too")
    (is (= {:role [:or [:string {:pg/type "character varying"}] :keyword]}
           (update-vals (:args (h/analyze reg {:select [:id] :from [:attempts] :where [:= :role :?role]} o)) m/form))
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
    (is (= [{:kind :uncast-string :column :mood :value "sad" :type "mood"}]
           (check {:select [:id] :from [:users] :where [:= :mood [:lift "sad"]]} opts))
        "[:lift s] is a String sent as varchar")
    (is (= [] (check {:select [:id] :from [:users] :where [:= :mood [:cast :?m :mood]]} opts))))
  (testing ":join-by's joins are read as the join clauses'"
    (is (= [] (check {:select [:u.nick :g.name] :from [[:users :u]] :join-by [:left [[:groups :g] [:= :g.id :u.group_id]]]}))))
  (testing "ROLLUP's total row is NULL in its grouped columns"
    (is (= [:maybe int4]
           (second (second (row {:select [:group_id [[:count :*] :n]] :from [:users] :group-by [[:rollup :group_id]]})))))))

(deftest a-row-is-nullable-where-a-grouping-pgmalli-does-not-read-may-null-it
  (is (every? #(= :maybe (first (last %))) (drop 1 (row {:select [:group_id] :from [:users] :group-by [[:raw "CUBE(group_id)"]]})))))

(deftest join-by-joins-in-its-order-as-the-join-clauses
  (let [q {:select [:u.nick :g.name] :from [[:users :u]] :join-by [:right [[:groups :g] [:= :g.id 9]]]}]
    (is (= [:map [:nick [:maybe [:string {:pg/max-length 40 :pg/type "character varying"}]]] [:name text]] (row q))
        "a RIGHT JOIN leaves the tables before it NULL")
    (is (= (row q) (row (assoc q :join-by [{:right [[:groups :g] [:= :g.id 9]]}]))) "maps of a join type and its clauses")
    (is (= [{:kind :unknown-column :column :g.nope}] (check (assoc q :select [:g.nope]))))
    (is (= [{:kind :unknown-column :column :nope :in [:join-by 1 0]}]
           (check {:select [:u.id] :from [[:users :u]] :join-by [:left [{:select [:nope] :from [:groups]} [:= 1 1]]]}))
        "a subquery joined, where it sits")
    (is (= [] (check {:select [:u.id :g.name] :from [[:users :u]] :join-by [:cross [[:groups :g]]]})))))

(deftest table-is-select-star-from
  (is (= (row {:select [:*] :from [:groups]}) (row {:table :groups})))
  (is (= [] (check {:union [{:table :groups} {:table :groups}]})))
  (is (= [{:kind :unknown-table :table "sample.nope"}] (check {:table :nope}))))

(deftest an-argument-no-use-types-is-any
  (is (= {:e [:any {:pgmalli/untyped :type}] :n [:maybe [:int {:pg/type "bigint" :min 0}]]} (args {:select [:id] :from [:users] :where [:= [:public.my-fn :nick] :?e] :offset :?n}))))

(deftest tables-are-what-a-query-reads-and-writes-not-its-aliases
  (is (= {:reads #{"sample.users"} :writes #{}} (:tables (h/analyze registry {:select [:*] :from [[:users :groups]]} opts))) "an alias is not the table it names")
  (is (= {:reads #{"sample.groups"} :writes #{"sample.users"}}
         (:tables (h/analyze registry {:update :users :set {:nick "a"} :from [[:groups :g]] :where [:= :g.id :users.group_id]} opts))))
  (is (= {:reads #{"sample.users"} :writes #{}}
         (:tables (h/analyze registry {:with [[:groups {:select [:id] :from [:users]}]] :select [:*] :from [:groups]} opts))) "a CTE is no table")
  (is (= {:reads #{} :writes #{"sample.users"}} (:tables (h/analyze registry {:insert-into :users :values [{:id 1}] :on-conflict [:id] :do-update-set [:nick]} opts))))
  (is (= {:reads #{} :writes #{"sample.users"}} (:tables (h/analyze registry {:truncate :users} opts))))
  (is (= {:reads #{} :writes #{"sample.users" "sample.groups"}} (:tables (h/analyze registry {:truncate [[:users :groups] :cascade]} opts))))
  (is (= {:reads #{"sample.users"} :writes #{}} (:tables (h/analyze registry {:select [:excluded.id] :from [[:users :excluded]]} opts)))
      "excluded is a table's alias outside ON CONFLICT"))

(deftest string-names-are-as-honeysql-quotes-them
  (is (= [] (check {:select [:u.nick] :from [[:users "u"]]})))
  (is (= {:reads #{"sample.users"} :writes #{}} (:tables (h/analyze registry {:select [:u.nick] :from [[:users "u"]]} opts))))
  (is (= {:reads #{"sample.users"} :writes #{}} (:tables (h/analyze registry {:with [["c" {:select [:id] :from [:users]}]] :select [:id] :from [:c]} opts))))
  (is (= [{:kind :unknown-table :table "sample.c"}] (check {:with [["C" {:select [:id] :from [:users]}]] :select [:*] :from [:c]}))))

(deftest a-string-reaches-a-type-that-takes-no-varchar
  (let [reg (pgmalli/registry {:registry {:pg.public/hosts [:map {:pg/table "public.hosts"}
                                                            [:id [:int {:pg/type "integer"}]]
                                                            [:ip [:any {:pg/type "inet"}]]
                                                            [:flags [:string {:pg/type "bit" :pg/max-length 4}]]
                                                            [:mask [:maybe [:string {:pg/type "bit"}]]]
                                                            [:doc [:any {:pg/type "jsonb"}]]
                                                            [:note [:string {:pg/type "text"}]]
                                                            [:born [:maybe [inst? {:pg/type "date"}]]]
                                                            [:seen [:maybe [inst? {:pg/type "timestamp with time zone"}]]]
                                                            [:paths [:maybe [:vector {:pg/type "text[]"} :string]]]]}})
        check #(:problems (h/analyze reg %1 (merge {:schema "public"} %2)))]
    (testing "a type of types but the string ones"
      (is (= [{:kind :uncast-string :column :born :value "2020-01-01" :type "date"}] (check {:select [:id] :from [:hosts] :where [:= :born "2020-01-01"]} {})))
      (is (= [{:kind :uncast-string :column :seen :value "2020-01-01" :type "timestamp with time zone"}
              {:kind :uncast-string :column :seen :value "2021-01-01" :type "timestamp with time zone"}]
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

(deftest a-date-argument-takes-a-local-date
  (let [t (:b (args {:update :users :set {:born :?b} :where [:= :id 1]}))]
    (is (m/validate t (java.time.LocalDate/parse "2020-01-01") malli-opts))
    (is (not (m/validate t (java.util.Date.) malli-opts)) "sent nowhere: pgjdbc refuses it")
    (is (not (m/validate t "2020-01-01" malli-opts)))))

(deftest a-domain-argument-takes-what-pgjdbc-takes-for-its-base
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:d [:time/local-date {:pg/type "date" :pg/domain "day"}]]]}})
        t (:d (:args (h/analyze reg {:update :t :set {:d :?d}})))]
    (is (not (m/validate t (java.util.Date.) malli-opts)))
    (is (m/validate t (java.time.LocalDate/parse "2020-01-01") malli-opts))))

(deftest a-string-assigned-to-a-regclass-column-is-taken
  (let [reg (pgmalli/registry {:registry {:pg.public/rc [:map {:pg/table "public.rc"} [:id :int] [:r [:any {:pg/type "regclass"}]]
                                                         [:rt [:any {:pg/type "regtype"}]]]}})]
    (is (= [] (:problems (h/analyze reg {:update :rc :set {:r "w"}}))))
    (is (= [:uncast-string] (map :kind (:problems (h/analyze reg {:select [:id] :from [:rc] :where [:= :r "w"]})))))
    (is (= [:uncast-string] (map :kind (:problems (h/analyze reg {:update :rc :set {:rt "integer"}})))) "regclass alone takes text by assignment")))

(deftest an-argument-compared-with-an-enum-takes-its-labels-however-compared
  (let [reg (pgmalli/registry {:registry {:pg.public/mood [:enum "ok" "sad"]
                                          :pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]] [:m [:ref {:pg/type "mood"} :pg.public/mood]]]}})]
    (is (= {:x [:enum {:pg/type "mood"} :ok :sad]} (:args (h/analyze reg {:select [:id] :from [:t] :where [:<> :m :?x]} {}))))))

(deftest null-in-any-case-is-sql-null
  (is (= [{:kind :not-null-violation :table "sample.users" :column :score :clause :set}]
         (check {:update :users :set {:score :NULL} :where [:= :id 1]})))
  (is (= [] (check {:select [:id] :from [:users] :where [:is :nick :NULL]})) "not a column named NULL"))

(deftest an-sql-word-in-any-case-names-its-column-as-postgresql-does
  (is (= [:map [:current_date [:time/local-date {:pg/type "date"}]]] (row {:select [:CURRENT_DATE] :from [:users]})))
  (is (= [:map [:?column? [:maybe :any]]] (row {:select [:NULL] :from [:users]}))))

(def ^:private kinds-registry
  (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                              [:r [:string {:pg/type "regclass"}]] [:i [:string {:pg/type "inet"}]]
                                              [:re [:double {:pg/type "real"}]]
                                              [:p [:pg/smallint {:pg/type "smallint"}]]
                                              [:j [:any {:pg/type "json"}]]]}}))

(deftest each-rule-of-what-an-argument-takes-holds
  (let [reg kinds-registry
        ok? #(m/validate %1 %2 {:registry reg})]
    (is (= [] (:problems (h/analyze reg {:update :t :set {:r :?x}}))) "a regclass takes a varchar argument")
    (is (ok? (:r (:args (h/analyze reg {:update :t :set {:re :?r}}))) 1) "a real takes an integer")
    (is (ok? (:p (:args (h/analyze reg {:update :t :set {:p [:cast :?p :smallint]}}))) 32767.4) "a fraction rounded to an integer type's range")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"disallowed key" (h/values-schema registry :users {:schema "sample" :nope 1})))
    (is (= [:map [:s [:string {:pg/type "character varying"}]]] (row {:select [["s" :s]] :from [:users]} {:schema "sample"}))
        "a String goes as varchar by default")
    (is (= {:x [:or (jdbc/typed-pg-object "json") [:map-of :some :any] [:vector :any]]} (:args (h/analyze reg {:update :t :set {:j :?x}} {})))
        "a json column takes a PGobject of json, and a map and a vector sent as jsonb, which PostgreSQL assigns to it")))

(deftest each-rule-of-a-row-written-holds
  (is (m/validate (h/values-schema registry :pg.sample/users {:schema "sample"})
                  {:group_id 1 :score 0 :nick_upper :default} {:registry registry})
      "a column the database writes keyed as the others")
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
        keys #(map first (drop 2 (shape/and-base (h/values-schema reg :t %))))]
    (is (= ["MixedCase"] (keys {})) "unquoted, only a string reaches a column with capitals")
    (is (= [:MixedCase] (keys {:format {:quoted true}})))))

(deftest the-same-query-and-options-give-equal-schemas
  (let [q {:update :users :set {:score [:cast :?x :integer] :nick :?n} :where [:= :id 1]}]
    (is (= (args q opts) (args q opts)) "a class's schema is one [:fn] per class")))

(deftest an-argument-of-a-resolving-function-takes-the-others-type
  (is (= {:x [:maybe bigint-cmp]} (args {:select [:id] :from [:users] :where [:> [:greatest :id :?x] 5]})))
  (is (= {:n [:maybe [:or [:string {:pg/type "character varying"}] :keyword]]} (args {:update :users :set {:nick [:coalesce :?n :nick]} :where [:= :id 1]})))
  (is (= {:x [:maybe bigint-cmp]} (args {:select [:id] :from [:users] :where [:= :id [:nullif :?x 0]]})))
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
    (is (= 1 (:max (second (ret {:select-distinct-on [[:mood] :nick] :from [:users] :limit 1} {})))) "a limit whatever else the statement holds")
    (is (= 2 (:max (second (ret {:select [[[:unnest [:array [1 2 3]]] :x]] :from [:users] :limit 2} {})))) "a function returning a set too")
    (is (not (map? (second (ret {:select [:id] :from [:users] :limit 1 :raw "UNION ALL SELECT 2"} {})))) "raw SQL may write rows past it")
    (is (= {:max 1 :min 1} (second (ret {:select [[[:exists {:select [1] :from [:users] :where [:= :id :?id]}] :authorized]]} {})))
        "a SELECT without FROM: one row, EXISTS one value")
    (is (= {:max 1 :min 1} (second (ret {:select [[[:not [:exists {:select [1] :from [:users] :where [:= :id :?id]}]] :v]]} {})))
        "NOT EXISTS too")
    (is (= {:max 1} (second (ret {:select [[1 :x]] :where [:= 1 :?n]} {}))) "WHERE may leave none")
    (is (= 1 (:max (second (ret {:select [:u.nick] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]] :where [:= :u.id :?id]} {}))))
        "a join on the joined table's key equal to a column of the row: a row for that row")
    (is (= 1 (:max (second (ret {:select [:u.nick] :from [[:users :u]] :left-join [[:groups :g] [:= :g.id :u.group_id]] :where [:= :u.id :?id]} {})))))
    (is (not (map? (second (ret {:select [:u.nick] :from [[:users :u]] :join [[:users :o] [:= :o.group_id :u.group_id]] :where [:= :u.id :?id]} {}))))
        "a join on other columns may repeat a row")
    (is (not (map? (second (ret {:select [:u.nick] :from [[:users :u]] :right-join [[:groups :g] [:= :g.id :u.group_id]] :where [:= :u.id :?id]} {}))))
        "a RIGHT JOIN keeps rows of its own")
    (is (= 1 (:max (second (ret {:delete-from :users :using [:groups] :where [:and [:= :groups.id :users.group_id] [:= :users.id :?id]] :returning [:users.id]} {}))))
        "USING's table by the WHERE, as a join's by its ON")
    (is (= 1 (:max (second (ret {:select [[[:in :id [1 2]] :a] [[:or [:= :score 1] [:not [:= :id [:any :?ids]]]] :b]] :from [:users] :where [:= :id :?id]} {}))))
        "an expression of the row's values is one value of it")
    (is (= 1 (:max (second (ret {:select [[[:case [:in :nick [:inline ["a" "b"]]] [:inline 1] :else [:inline 2]] :a] [[:= [:composite :id :score] [:composite 1 2]] :b]] :from [:users] :where [:= :id :?id]} {}))))
        "a list of literals, a row")
    (is (= 1 (:max (second (ret {:select [:nick] :from [:users] :where [:and [:and [:= :id :?id] [:> :score 1]] [:= 1 1]]} {}))))
        "an AND inside an AND")
    (is (= {:min 1 :max 1} (second (ret {:select [[[:count [:distinct :mood]] :n]] :from [:users] :where [:= :group_id :?g]} {})))
        "an aggregate of DISTINCT values: one row")
    (is (= {:min 1 :max 1} (second (ret {:with-recursive [[:r {:select [[1 :n]]}]] :select [[[:count :*] :n]] :from [:r]} {})))
        "of a recursive CTE too")
    (is (= {:min 1 :max 1} (second (ret {:select [[[:filter [:count :*] {:where [:= :id 1]}] :n]] :from [:users]} {})))
        "an aggregate with FILTER: one row")
    (is (= {:min 1 :max 1} (second (ret {:select [[[:count [:case [:and [:= :id 1] [:not [:= :score 2]]] 1]] :n]] :from [:users]} {})))
        "an aggregate of an expression of the row's values")
    (is (= {:max 2} (second (ret {:select [:id] :from [:users] :limit [:inline 2] :offset [:inline 1]} {}))) "an inline limit, which HoneySQL writes as the number")
    (let [latest {:select [:id :score] :from [:users] :order-by [[:score :desc]] :limit 1}]
      (is (= {:max 1} (second (ret {:select [:l.id] :from [[latest :l]] :where [:= :l.score 1]} {})))
          "a subquery alone in FROM: as many rows as it at most")
      (is (= {:max 1} (second (ret {:with [[:l latest]] :select [:id] :from [:l]} {}))) "a CTE alike")
      (is (= {:max 1} (second (ret {:select [:id] :from [[{:select [:id] :from [[latest :i]]} :o]]} {}))) "nested")
      (is (not (map? (second (ret {:select [:l.id] :from [[latest :l]] :join [[:users :u] [:= :u.group_id :l.id]]} {})))) "not joined")
      (is (not (map? (second (ret {:with [[:users {:select [:id] :from [:groups]}]] :select [:id] :from [[{:select [:id] :from [:users] :where [:= :id 1]} :q]]} {}))))
          "a CTE of a table's name is no table, in a subquery too"))
    (is (= :maybe (first (ret {:insert-into :users :values [{:group_id 1 :mood "sad" :score 1}] :returning [:id]} {:result :one})))
        "an INSERT returns up to a row for each of its rows: a trigger, a rule or ON CONFLICT may skip one")
    (is (= :maybe (first (ret {:insert-into :users :values [{:group_id 1 :mood "sad" :score 1}] :on-conflict [] :do-nothing true :returning [:id]} {:result :one})))
        "unless ON CONFLICT skips it")))

(deftest a-subquerys-or-ctes-columns-are-typed-by-its-select
  (let [recent {:with [[:recent {:select [:id :nick] :from [:users]}]]}]
    (is (= [:map
            [:id [:int {:pg/identity :always :pg/type "bigint"}]]
            [:nick [:maybe [:string {:pg/max-length 40 :pg/type "character varying"}]]]]
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
        ids [:pg/array {:pgmalli/type-from-value "unnest"} bigint-cmp]]
    (is (= {:ids ids} (args {:select [:id] :from [:users] :join [[[:unnest :?ids] :i] [:= :users.id :i]]} o))
        "an unnest of one, named by its alias")
    (is (= {:ids ids} (args {:update :users :set {:nick "x"} :from [[[:unnest :?ids] [:x {:columns [:i]}]]] :where [:= :users.id :x.i]} o))
        "a column its alias lists")
    (is (= {:ids [:or {:pgmalli/type-from-value "unnest"} [:pg/array :any] pg-object]} (args {:select [:i] :from [[[:unnest :?ids] :i]]} o))
        "a column that goes nowhere typed: an array sent typed, unnest(unknown) being not unique (anyarray, anymultirange, tsvector)")))

(deftest an-argument-whose-type-postgresql-takes-from-the-value-alone-takes-a-value-sent-typed
  (is (= {:ids [:or {:pgmalli/type-from-value "cardinality"} [:pg/array :any] pg-object]} (args {:select [[[:cardinality :?ids] :n]]}))
      "cardinality(anyarray): an array of a type, no keyword or date sent untyped, no nil")
  (is (= {:ids [:pg/array {:pgmalli/type-from-value "cardinality"} bigint-cmp]}
         (args {:select [:id] :from [:users] :where [:and [:= :id [:any :?ids]] [:>= [:cardinality :?ids] 0]]}))
      "another use's own type, the position told")
  (is (= {:ids [:pg/array bigint-cmp] :n [:or {:pgmalli/type-from-value "cardinality"} [:pg/array :any] pg-object]}
         (args {:select [:id] :from [:users] :where [:and [:= :id [:any :?ids]] [:= :id [:cardinality :?n]]]}))
      "another use's own type where it has one")
  (is (= [] (check {:select [:id] :from [:users] :where [:= 2 [:cardinality :?ids]]}))))

(deftest an-argument-whose-type-postgresql-takes-from-the-value-alone-and-whose-other-uses-send-it-untyped-is-a-problem
  (let [q {:update :users :set {:mood :?d} :where [:= :id 1] :returning [[[:concat :?d] :s]]}]
    (is (= [{:kind :undetermined-type :arg :d :function "concat" :type "mood"}] (check q)) "an enum column's keyword label goes untyped, nil too")
    (is (str/ends-with? (first (h/humanize (check q))) "cast it in the query: [:cast :?d :mood]")))
  (is (= [] (check {:update :users :set {:closed_at :?t} :where [:= :id 1] :returning [[[:concat :?t] :s]]}))
      "a timestamptz column's Instant goes typed")
  (is (= [] (check {:update :users :set {:born :?d} :where [:= :id 1] :returning [[[:concat :?d] :s]]}))
      "a date column's LocalDate goes typed"))

(deftest a-json-builder-is-json-as-its-type-is-read
  (let [q {:select [[[:jsonb_build_object "a" :id] :o] [[:jsonb_agg :id] :a] [[:json_build_array :id] :b]] :from [:users]}]
    (is (= [:map
            [:o [:maybe [:map {:pg/type "jsonb"}]]]
            [:a [:maybe [:sequential {:pg/type "jsonb"} :any]]]
            [:b [:maybe [:sequential {:pg/type "json"} :any]]]]
           (row q))
        "parsed, an object or an array, which may be NULL: the catalog does not say when a function returns NULL")))

(deftest an-aggregate-anywhere-in-a-select-without-group-by-makes-one-row-which-having-may-drop
  (let [rows #(:rows (h/analyze registry % opts))]
    (doseq [s [[[:coalesce [:sum :score] 0] :s] [[:+ [:sum :score] 1] :s] [[:case [:> [:count :*] 0] 1 :else 0] :c]
               [[:cast [:count :*] :int] :n] [[:greatest [:max :score] 0] :m] [[:nullif :%count.* 0] :n]]]
      (is (= {:min 1 :max 1} (rows {:select [s] :from [:users] :where [:= :group_id 1]})) (pr-str s)))
    (is (= {:min 1 :max 1} (rows {:select [[1 :x]] :from [:users] :order-by [[[:count :*]]]})) "in ORDER BY too")
    (is (= {:min 0 :max 1} (rows {:select [[[:count :*] :n]] :from [:users] :having [:> [:count :*] 1]})))
    (is (= {:min 0 :max 1} (rows {:select [[1 :x]] :from [:users] :having true})) "HAVING alone makes one group")
    (is (= {:min 0 :max 1} (rows {:select [[[:count :*] :n]] :from [:users] :offset 1})))
    (is (= {:min 0 :max 0} (rows {:select [[[:coalesce [:sum :score] 0] :s]] :from [:users] :limit 0})))
    (is (= {:min 0 :max 1} (rows {:insert-into [[:users [:score]]] :select [[[:count :*]]] :from [:groups] :returning [:id]}))
        "a trigger, a rule or ON CONFLICT may skip the row a write's SELECT gives")
    (is (nil? (rows {:select [[[:over [[:count :*] {} :w]]]] :from [:users]})) "a window call is no aggregate of the query")
    (is (nil? (rows {:select [:id [{:select [[[:count :*]]] :from [:groups]} :c]] :from [:users]})) "nor one in a subquery")
    (is (nil? (rows {:select [[[:count :*] :n] [[:generate_series 1 3] :g]] :from [:users]})) "a set-returning function repeats the row")))

(deftest a-row-count-is-told-of-values-set-operations-a-select-without-from-and-fetch
  (let [rows #(:rows (h/analyze registry % opts))
        one {:select [[[:cast "12:34:56" :time] :t]]}
        users {:select [:id] :from [:users]}]
    (is (= {:min 1 :max 1} (rows one)))
    (is (= {:min 1 :max 1} (rows (assoc one :limit 1))))
    (is (= {:min 0 :max 1} (rows (assoc one :where [:= 1 :?x]))))
    (is (= {:min 1 :max 1} (rows {:select-distinct [[1 :x]]})))
    (is (= {:min 1 :max 1} (rows {:select [[1 :x]] :group-by [:x]})) "one group of its one row")
    (is (= {:min 0 :max 1} (rows {:select [[1 :x]] :group-by [:x] :having [:= 1 :?x]})))
    (is (nil? (rows {:select [[1 :x]] :group-by [[:generate_series 1 3]]})) "a set-returning function in GROUP BY repeats the group")
    (is (nil? (rows {:select [[1 :x]] :group-by [[:rollup :x]]})) "ROLLUP adds a group")
    (is (nil? (rows {:select [:id] :from [:users] :where [:= :id 1] :group-by [[:rollup :id]]})) "even of one row")
    (is (nil? (rows {:select [:id] :from [:users] :where [:= :id 1] :group-by [:id [:generate_series 1 3]]})))
    (is (= {:min 0 :max 2} (rows (assoc users :fetch 2))))
    (is (= {:min 0 :max 1} (rows (assoc one :fetch 2 :offset 1))))
    (is (nil? (rows (assoc users :fetch :?n))))
    (is (nil? (rows (assoc users :limit :all))))
    (is (= {:min 3 :max 3} (rows {:values [[1] [2] [3]]})))
    (is (= {:min 2 :max 2} (rows {:union-all [one one]})))
    (is (= {:min 1 :max 2} (rows {:union [one one]})) "UNION drops the duplicate")
    (is (= {:min 0 :max 2} (rows {:union [(assoc one :where [:= 1 :?x]) (assoc one :where [:= 1 :?x])]})))
    (is (= {:min 0 :max 1} (rows {:intersect [one users]})))
    (is (= {:min 0 :max 1} (rows {:except [one users]})))
    (is (= {:min 0 :max 1} (rows {:except-all [one users]})))
    (is (= {:min 0 :max 1} (rows {:union-all [one users] :limit 1})) "a limit bounds a member of no count")
    (is (nil? (rows {:union-all [one users]})) "a member of no count")
    (is (nil? (rows {:union-all [one {:select [[[:generate_series 1 3] :t]]}]})))))

(deftest a-row-count-is-told-only-where-nothing-may-change-it
  (let [ret #(last (h/query-schema registry [:db] % (merge opts %2)))
        counted? #(map? (second (ret % {})))]
    (is (not (counted? {:select [[[:generate_series 1 3] :x]] :from [:users] :where [:= :id 1]}))
        "a set-returning function in the select list repeats a keyed row")
    (is (not (counted? {:select [:users.nick] :from [:users] :join-by [:join [[:users :u2] [:= :u2.group_id :users.group_id]]] :where [:= :users.id 1]})))
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
  (is (= {:n integer-cmp} (args {:with [[:users {:select [[[:cast 1 :integer] :nick]]}]] :select [:s.nick] :from [[{:select [:nick] :from [:users]} :s]] :where [:= :s.nick :?n]}))
      "users there is the CTE, which the subquery on its own would read as the table"))

(deftest an-insert-selects-arguments-are-typed-in-its-selects-scope
  (is (= {:x [:maybe integer-cmp]} (args {:insert-into [[:users [:score]] {:select [[[:coalesce :?x :id]]] :from [:groups]}]}))))

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
  (let [d (ex-data (try (:problems (h/analyze registry {:select [:id] :from [:users]} {:jdcb {}})) (catch clojure.lang.ExceptionInfo e e)))]
    (is (= [:jdbc] (:did-you-mean d)))
    (is (= [:jdcb] (:unknown d)))))

(deftest a-column-a-before-trigger-may-fill-takes-nil-or-nothing
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:insert ["fill"] :update ["fill"]}}
                                                        [:id [:int {:pg/type "integer"}]] [:slug [:string {:pg/type "text"}]]]}})]
    (is (m/validate (h/values-schema reg :t) {:id 1} {:registry reg}))
    (is (m/validate (h/values-schema reg :t) {:id 1 :slug nil} {:registry reg}))
    (is (m/validate (h/set-schema reg :t) {:slug nil} {:registry reg}))
    (is (= :maybe (first (:s (:args (h/analyze reg {:insert-into :t :values [{:id 1 :slug :?s}]}))))))))

(deftest an-argument-assigned-to-an-unknown-table-is-any
  (is (= [:any {:pgmalli/untyped :type}] (:n (args {:update :nosuch :set {:nick :?n} :where [:= :id :?id]})))))

(deftest a-numeric-with-a-precision-takes-an-integer-assigned
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:n ['decimal? {:pg/type "numeric"}]]]}})]
    (doseq [q [{:insert-into :t :values [{:n :?v}]} {:update :t :set {:n :?v}}]
            v [12 (int 12) 12N 12.5M]]
      (is (m/validate (:v (:args (h/analyze reg q))) v {:registry reg}) (pr-str q v)))
    (is (m/validate (h/values-schema reg :t) {:n 12} {:registry reg}))))

(deftest an-array-column-takes-a-narrower-numbers-typed-array
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]]
                                                        [:na [:maybe [:vector {:pg/type "numeric[]"} [decimal? {:pg/type "numeric"}]]]]
                                                        [:da [:maybe [:vector {:pg/type "double precision[]"} [:double {:pg/type "double precision"}]]]]]}})
        o {}]
    (doseq [c [:na :da]]
      (is (m/validate (:v (:args (h/analyze reg {:update :t :set {c :?v} :where [:= :id 1]} o))) (long-array [1 2]) {:registry reg}) (name c)))))

(deftest a-written-row-takes-no-keyword-honeysql-would-write-as-a-column
  (let [s (h/values-schema registry :users opts)]
    (is (not (m/validate s {:group_id 1 :score 1 :mood :sad} {:registry registry})))
    (is (not (m/validate s {:group_id 1 :score 1 :mood "sad"} {:registry registry})) "a String goes as varchar, which an enum column refuses")
    (is (m/validate s {:group_id 1 :score 1 :mood [:lift :sad]} {:registry registry}) "a keyword label as one parameter, untyped")))

(deftest an-argument-takes-nil-where-every-use-does
  (is (= {:v bigint-cmp} (args {:select [:id] :from [:users] :where [:or [:= :id :?v] [:is-distinct-from :id :?v]]}))
      "= NULL matches no row")
  (is (= {:v bigint-cmp} (args {:select [:id] :from [:users] :where [:or [:is-distinct-from :id :?v] [:= :id :?v]]}))
      "whatever the order")
  (is (= {:v [:maybe bigint-cmp]} (args {:select [:id] :from [:users] :where [:or [:is-distinct-from :id :?v] [:not [:is-distinct-from :id :?v]]]}))
      "nil where both take it"))

(deftest an-argument-carries-the-cast-and-the-triggers-every-use-of-it-shares
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:insert ["fill_g"]}}
                                                        [:id [:int {:pg/type "bigint"}]] [:g [:int {:pg/type "bigint"}]] [:n [:maybe [:int {:pg/type "bigint"}]]]]}})
        args #(:args (h/analyze reg % {}))
        p (fn [s k] (some k (keep shape/props (tree-seq vector? seq s))))]
    (is (= "bigint[]" (p (:ids (args {:select [[[:unnest [:cast :?ids [:raw "int8[]"]]] :x]]})) :pgmalli/cast)) "as PostgreSQL names the type")
    (is (= :pg/array (first (some #(when (:pgmalli/cast (shape/props %)) %) (tree-seq vector? seq (:ids (args {:select [[[:unnest [:cast :?ids [:raw "bigint[]"]]] :x]]}))))))
        "on the array, its elements the values the cast takes")
    (is (nil? (p (:x (args {:select [:id] :from [:t] :where [:or [:= :id [:cast :?x :bigint]] [:= :g :?x]]})) :pgmalli/cast)) "every use a cast")
    (is (nil? (p (:x (args {:select [:id] :from [:t] :where [:or [:= :id [:cast :?x :bigint]] [:= :g [:cast :?x :integer]]]})) :pgmalli/cast)) "to one type")
    (let [{:keys [g n]} (args {:insert-into :t :values [{:id 1 :g :?g :n :?n}]})]
      (is (= [:maybe [:int {:pg/type "bigint" :pgmalli/before-triggers ["fill_g"]}]] g) "the NOT NULL column's type, made nullable by the triggers")
      (is (nil? (p n :pgmalli/before-triggers)) "a nullable column's is nullable of itself"))))

(deftest a-partial-unique-index-is-a-key-where-the-where-holds-its-predicate
  (let [reg (pgmalli/registry {:registry {:pg.public/st [:enum "active" "gone"]
                                          :pg.public/t [:map {:pg/table "public.t" :pg/primary-key ["id"]
                                                              :pg/unique [{:columns ["g"] :index true :where [[:= "status" "active"] [:is-null "gone_at"]]}
                                                                          {:columns ["n"] :index true :where true}]}
                                                        [:id [:int {:pg/type "integer"}]] [:g [:int {:pg/type "integer"}]] [:n [:int {:pg/type "integer"}]]
                                                        [:status [:ref {:pg/type "st"} :pg.public/st]] [:gone_at [:maybe [:any {:pg/type "timestamptz"}]]]]}})
        rows #(:rows (h/analyze reg {:select [:id] :from [:t] :where %} {}))]
    (is (= {:min 0 :max 1} (rows [:and [:= :g :?g] [:and [:= :status [:cast "active" :st]] [:= :gone_at nil]]])))
    (is (nil? (rows [:and [:= :g :?g] [:= :status [:cast "active" :st]]])) "the predicate's every condition")
    (is (nil? (rows [:and [:= :g :?g] [:= :status "gone"] [:= :gone_at nil]])) "of its value")
    (is (nil? (rows [:= :n :?n])) "a predicate the generated file does not tell is none"))
  (doseq [w [[[:= "kind" "x"] [nil]] [[:= "kind" "x"] [:= "kind"]] [[:= "kind" "x"] [:in "status" ["a"]]] [[:= "kind" "x"] [:= "status" nil]]]]
    (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/primary-key ["id"] :pg/unique [{:columns ["g"] :index true :where w}]}
                                                          [:id [:int {:pg/type "integer"}]] [:g [:int {:pg/type "integer"}]] [:kind [:string {:pg/type "text"}]]
                                                          [:status [:string {:pg/type "text"}]]]}})]
      (is (nil? (:rows (h/analyze reg {:select [:id] :from [:t] :where [:and [:= :g 1] [:= :kind "x"] [:= :status nil]]} {})))
          (str (pr-str w) ": a condition of a form it does not know makes the index no key")))))

(deftest a-statement-reads-one-row-where-each-table-is-held-to-one-by-a-key
  (let [reg (pgmalli/registry {:registry {:pg.public/p [:map {:pg/table "public.p" :pg/primary-key ["gid" "rid"]} [:gid [:int {:pg/type "bigint"}]] [:rid [:int {:pg/type "bigint"}]]]
                                          :pg.public/r [:map {:pg/table "public.r" :pg/primary-key ["id"] :pg/unique [{:columns ["gid" "rkey"]}]}
                                                        [:id [:int {:pg/type "bigint"}]] [:gid [:int {:pg/type "bigint"}]] [:rkey [:string {:pg/type "text"}]]]
                                          :pg.public/u [:map {:pg/table "public.u"} [:gid [:int {:pg/type "bigint"}]]]}})
        rows #(:rows (h/analyze reg % {}))]
    (is (= {:min 0 :max 1} (rows {:select [:p.gid] :from [:p] :join [:r [:and [:= :r.id :p.rid] [:= :r.gid :p.gid]]] :where [:and [:= :p.gid :?g] [:= :r.rkey :?k]]}))
        "the joined table held first, by a column equal to one held: the first table by its column")
    (is (nil? (rows {:select [:p.gid] :from [:p] :join [:r [:= :r.id :p.rid]] :where [:= :p.gid :?g]})) "each held by the other alone: neither")
    (is (= {:min 0 :max 1} (rows {:select [:p.gid] :from [:p] :where [:and [:= :p.gid :?g] [:= :p.rid {:select [:gid] :from [:u]}]]}))
        "a scalar subquery is one value")
    (doseq [sub [{:select [[[:max :x.rid]]] :from [[:p :x]] :where [:= :x.gid :p.gid]}
                 {:select [[[:max :rid]]] :from [:u] :where [:= :u.gid :gid]}
                 {:select [:rid]}]]
      (is (nil? (rows {:select [:p.gid] :from [:p] :where [:and [:= :p.gid :?g] [:= :p.rid sub]]}))
          (str (pr-str sub) " reads a column of the row around it: a value for each row, as the latest of each group")))
    (is (= {:min 0 :max 1} (rows {:select [:g.gid] :from [[{:select [:gid [[:count :*] :n]] :from [:u] :group-by [:gid]} :g]] :where [:= :g.gid :?g]}))
        "a subquery's GROUP BY columns are a key of its rows")
    (is (= {:min 0 :max 1} (rows {:select [:gid [[:count :*] :n]] :from [:u] :group-by [:gid] :having [:= :gid :?g]})) "a group by its HAVING")
    (is (nil? (rows {:select [:gid [[:count :*] :n]] :from [:u] :group-by [:gid]})))
    (is (= {:min 0 :max 1} (rows {:select [:d.gid] :from [[{:select-distinct-on [[:gid] :gid] :from [:u]} :d]] :where [:= :d.gid :?g]})) "DISTINCT ON's")
    (is (= {:min 0 :max 1} (rows {:with [[:h {:delete-from :r :where [:= :id :?id] :returning [:gid]}]] :select [:h.gid :p.rid] :from [:h] :join [:p [:and [:= :p.gid :h.gid] [:= :p.rid 1]]]}))
        "a CTE of a row at most, joined to a table by its key")
    (doseq [w [[:in :p.rid {:select [:gid] :from [:u]}] [:= :p.rid [:any {:select [:gid] :from [:u]}]]]]
      (is (nil? (rows {:select [:p.gid] :from [:p] :where [:and [:= :p.gid :?g] w]})) (str (pr-str w) " is many")))))

(deftest an-argument-of-a-function-taking-an-enum-type-is-a-label-of-it
  (let [reg (pgmalli/registry {:schemas ["public"]
                               :functions {"public" {"claim" [{:args ["bigint" "kind"] :result "bigint" :set true}]}}
                               :registry {:pg.public/kind [:enum "a" "b"]}})
        p (:p (:args (h/analyze reg {:select [:t] :from [[[:public.claim :?n :?p] :t]]} {:schema "public"})))]
    (is (m/validate p :a {:registry reg}))
    (is (not (m/validate p :c {:registry reg})))
    (is (not (m/validate p "a" {:registry reg})) "a String goes as varchar, which claim(bigint, kind) does not take")))

(deftest an-enum-arrays-argument-assigned-is-a-pgobject-of-the-enum-array-alone
  (let [reg (pgmalli/registry {:registry {:pg.public/feel [:enum "a" "b"]
                                          :pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]]
                                                        [:fs [:maybe [:vector {:pg/type "feel[]"} [:ref :pg.public/feel]]]]]}})
        t (:v (:args (h/analyze reg {:update :t :set {:fs :?v} :where [:= :id 1]})))]
    (is (not (m/validate t (into-array String ["a"]) {:registry reg})) "a String[] goes as varchar[], which feel[] does not take")
    (is (re-find #"PGobject of type feel\[\]" (pr-str t)))))

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
    (is (= {:mood ["should be [:lift v], v one of :happy :sad; a String goes as varchar, which an enum takes not: write the label as a keyword, [:lift :label]" "should be a PGobject of type mood (a String goes as varchar, which mood does not take)" "should be DEFAULT as HoneySQL writes it ([:default])"]}
           (me/humanize (m/explain v (assoc row :mood "glad") {:registry registry}))))
    (let [rows (concat (mg/sample v {:registry registry :seed 1 :size 30}) (mg/sample s {:registry registry :seed 1 :size 30}))]
      (is (not-any? (fn [r] (some #(and (sequential? %) (not= [:default] %) (not= :lift (first %))) (vals r))) rows)
          "generated as the values a column takes, [:lift v] and DEFAULT")
      (is (some (fn [r] (some #(= [:lift :sad] %) (vals r))) rows) "an enum's label as [:lift :label]"))))

(deftest an-enum-column-takes-its-labels-as-keywords-not-as-strings
  (let [o opts
        q {:select [:id] :from [:users] :where [:= :mood :?m]}
        ok? #(m/validate %1 %2 {:registry registry})]
    (is (not (ok? (:m (args q o)) "sad")) "pgjdbc sends a String as varchar, which PostgreSQL neither compares with nor assigns to an enum")
    (is (not (ok? (h/set-schema registry :users o) {:mood "sad"})))
    (is (= [] (check q o)) "an argument is arg-types'")
    (is (= [:enum {:default :happy :pg/default "happy" :pg/type "mood"} :happy :sad]
           (:m (args q o))) "a keyword sent untyped as the label it names")
    (is (ok? (:x (args {:select [[[:cast :?x :mood] :m]]} o)) "sad") "PostgreSQL casts a varchar to an enum")))

(deftest a-boxed-or-other-java-array-is-typed-by-its-elements
  (is (m/validate (:ids (args {:select [:id] :from [:users] :where [:= :id [:any :?ids]]})) (into-array Long [1 2]) {:registry registry})
      "pgjdbc sends a Long[] as a bigint[]"))

(deftest a-domains-array-argument-takes-its-base-types-typed-array
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]] [:di [:maybe [:pg/integer {:pg/type "integer" :pg/domain "d_int"}]]]]}})]
    (is (m/validate (:v (:args (h/analyze reg {:select [:id] :from [:t] :where [:= :di [:any :?v]]}))) (int-array [1 2]) {:registry reg}))))

(deftest a-case-resolves-its-values-as-coalesce-does
  (is (= [:map [:c text]] (row {:select [[[:case [:= :id 1] [:inline "a"] :else [:inline "b"]] :c]] :from [:users]})))
  (is (= [:map [:c [:maybe text]]] (row {:select [[[:case [:= :id 1] [:inline "a"]] :c]] :from [:users]})) "NULL without :else")
  (is (= [:map [:n bigint]] (row {:select [[[:coalesce [:max :group_id] 0] :n]] :from [:users]}))
      "an integer and a bigint resolve to the bigint"))

(deftest a-ctes-columns-are-typed-inside-expressions
  (is (= [:map [:m [:maybe int4]]]
         (row {:with [[:s {:update :users :set {:score 1} :returning [:score]}]] :select [[[:max :score] :m]] :from [:s]}))))

(deftest a-not-null-key-compared-with-null-matches-at-most-one-row
  (is (= {:max 1} (second (last (h/query-schema registry [:db] {:select [:nick] :from [:users] :where [:= :id nil]} opts))))))

(deftest sql-syntax-is-the-function-the-grammar-calls-and-names-its-column-after
  (is (= [] (check {:select [:s.btrim] :from [[{:select [[[:trim :nick]]] :from [:users]} :s]]})))
  (is (= [:map [:btrim [:maybe text]] [:ltrim [:maybe text]] [:rtrim [:maybe text]] [:pg_collation_for [:maybe text]]]
         (row {:select [[[:trim :!both "x" :!from :nick]] [[:trim :!leading "x" :!from :nick]] [[:trim :!trailing :!from :nick]] [[:collation-for :nick]]] :from [:users]})))
  (is (= [:map [:p [:maybe int4]]] (row {:select [[[:position [:in "a" :nick]] :p]] :from [:users]})))
  (testing "POSITION(? IN t) is position(t, ?): ? a string, no IN list"
    (is (= [] (check {:select [[[:position [:in :?a :nick]] :p]] :from [:users]})))
    (is (m/validate (:a (args {:select [[[:position [:in :?a :nick]] :p]] :from [:users]})) "a" malli-opts))
    (is (= [{:kind :unknown-column :column :nope}] (check {:select [[[:position [:in :nope :nick]] :p]] :from [:users]}))))
  (is (= [{:kind :unknown-column :column :nope :in [:select 0 0 1 1]}]
         (mapv #(dissoc % :did-you-mean) (check {:select [[[:position [:in {:select [:nick] :from [:users] :where [:= :nope 1]} :nick]] :p]] :from [:users]})))
      "a statement in [:in a b] is at its path as written"))

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
  (is (= {:ids [:pg/array {:pgmalli/type-from-value "unnest"} bigint-cmp] :ss [:pg/array {:pgmalli/type-from-value "unnest"} int4]}
         (args {:update :users :set {:score :r.s} :from [[[:unnest :?ids :?ss] [:r {:columns [:i :s]}]]] :where [:= :users.id :r.i]} opts)))
  (is (m/validate (:ns (args {:update :users :set {:nick [:cast :r.n :text]} :from [[[:unnest :?ids :?ns] [:r {:columns [:i :n]}]]] :where [:= :users.id :r.i]} opts))
                  (into-array String ["a"]) {:registry registry})
      "an array of what casts to the type"))

(deftest an-unnest-of-an-array-cast-to-the-columns-type-holds-each-element-to-the-column
  (let [q (fn [select more] {:insert-into [[:users [:group_id :nick]] (merge {:select select :from [[[:unnest [:cast :?gs [:raw "integer[]"]] [:cast :?ns [:raw "varchar[]"]]] [:r {:columns [:group_id :nick]}]]]} more)]})
        ok? (fn [query k v] (m/validate (k (args query)) v {:registry registry}))
        long-nick (into-array String [(apply str (repeat 41 "a"))])]
    (doseq [select [[:*] [:r.group_id :r.nick]]]
      (is (ok? (q select {}) :ns long-nick) "longer than the column's varchar(40): the database's to refuse")
      (is (ok? (q select {}) :ns (into-array String ["a" nil])))
      (is (not (ok? (q select {}) :gs (into-array Integer [nil]))) "NULL into a NOT NULL column")
      (is (not (ok? (q select {:where [:= :r.group_id 1]}) :gs (into-array Integer [nil]))) "as on a row the WHERE lets through"))
    (is (ok? {:insert-into [[:users [:group_id]] {:select [:*] :from [[[:unnest [:cast :?gs [:raw "bigint[]"]]] [:r {:columns [:group_id]}]]]}]}
             :gs (long-array [Long/MAX_VALUE]))
        "into a column of another type, the cast's type")))

(deftest a-populate-records-document-is-a-closed-map-of-the-row-types-columns-each-as-its-input-reads-it
  (let [ok? (fn [query k v] (m/validate (k (args query)) v {:registry registry}))
        record (fn [t doc] {:select [:r.*] :from [[[:jsonb_populate_record [:cast nil t] doc] :r]]})
        q (record :users [:cast :?doc :jsonb])]
    (doseq [doc [{} {:nick "ann" :group_id 1 :mood "sad"} {:mood :sad :nick nil} {:group_id "12"}
                 {:closed_at (java.time.Instant/now) :born (java.time.LocalDate/now) :updated_at (java.time.LocalDateTime/now)}
                 nil "{\"nick\": \"ann\"}"]]
      (is (ok? q :doc doc) (pr-str doc)))
    (doseq [[doc why] [[{:nik "ann"} "a key of no column, which PostgreSQL ignores"]
                       [{:nick "ann" "nick" "bob"} "a column's key both as a keyword and as a string"]
                       [{:group_id 3000000000} "beyond the integer's range"]
                       [{:group_id 1.5} "a fraction, which an integer's input does not read"]
                       [{:mood "angry"} "no label of the enum"]
                       [{:nick 1} "a number to a string column, as assigned"]
                       [{:nick \a} "a Character, which data.json does not write"]
                       [[{:nick "ann"}] "an array, which a record is not"]]]
      (is (not (ok? q :doc doc)) why))
    (is (ok? q :doc {"nick" "ann" :group_id 1}) "a string key equal to a column's name, as JSON's keys are")
    (is (not (ok? q :doc {"nik" "ann"})) "a string key of no column")
    (is (ok? (record :sample.users :?doc) :doc {:nick "ann"}) "a bare argument, the table schema-qualified")
    (is (not (ok? (record :sample.users :?doc) :doc "{}")) "a String goes as varchar, which the function does not take")
    (is (= [:maybe int4] (some (fn [[k s]] (when (= :group_id k) s)) (rest (row q))))
        "the row's columns are the row type's, NULL where the document lacks a key")))

(deftest a-populate-recordsets-document-is-a-vector-of-them-a-key-required-where-insert-writes-its-not-null-column
  (let [ok? (fn [query k v] (m/validate (k (args query)) v {:registry registry}))
        insert (fn [select more] {:insert-into [[:users [:group_id :score :nick]]
                                                (merge {:select select :from [[[:jsonb_populate_recordset [:cast nil :users] :?rows] :r]]} more)]})]
    (doseq [select [[:group_id :score :nick] [:r.group_id :r.score :r.nick]]]
      (is (ok? (insert select {}) :rows [{:group_id 1 :score 0} {:group_id 2 :score 1 :nick "ann"}]))
      (is (not (ok? (insert select {}) :rows [{:group_id 1 :nick "ann"}])) "score is NOT NULL")
      (is (ok? (insert select {}) :rows [{"group_id" 1 "score" 0}]) "a required key as a string")
      (is (not (ok? (insert select {}) :rows [{"group_id" 1 "score" nil}])) "nil to a NOT NULL column, as a string key")
      (is (not (ok? (insert select {}) :rows [{:group_id 1 :score nil}])) "nil to a NOT NULL column")
      (is (not (ok? (insert select {}) :rows {:group_id 1 :score 0})) "a map, which a recordset is not")
      (is (not (ok? (insert select {:where [:> :r.score 0]}) :rows [{:group_id 1}])) "as on a row the WHERE lets through"))
    (is (ok? (insert [:r.group_id :r.score [:upper :r.nick]] {}) :rows [{:group_id 1 :score 0}]) "a column not written straight from the document")
    (let [star {:insert-into [[:groups [:id :name]] {:select [:*] :from [[[:jsonb_populate_recordset [:cast nil :groups] :?rows] :r]]}]}]
      (is (ok? star :rows [{:id 1 :name "a"}]))
      (is (not (ok? star :rows [{:id 1}])) "SELECT * writes every column of the row type"))
    (let [update {:update [:users :p] :set {[:composite :nick :score] {:select [:r.nick :r.score] :from [[[:jsonb_populate_record :p [:cast :?row :jsonb]] :r]]}}
                  :where [:= :p.id 1]}]
      (is (ok? update :row {:nick "ann"}) "the whole row of the UPDATE's table, every key optional")
      (is (not (ok? update :row {:nick "ann" :scor 1}))))))

(deftest a-populate-documents-columns-the-statement-uses-are-analyzes-document-columns-nil-where-a-use-names-none
  (let [cols (fn [query k] (get (:document-columns (h/analyze registry query opts)) k ::absent))
        insert (fn [select more] {:insert-into [[:users [:group_id :score]]
                                                (merge {:select select :from [[[:jsonb_populate_recordset [:cast nil :users] :?rows] :r]]} more)]})
        record (fn [select more] (merge {:select select :from [[[:jsonb_populate_record [:cast nil :users] :?doc] :r]]} more))]
    (is (= {:row #{:nick :score}}
           (:document-columns (h/analyze registry {:update [:users :p] :set {[:composite :nick :score] {:select [:r.nick :r.score] :from [[[:jsonb_populate_record :p [:cast :?row :jsonb]] :r]]}}
                                                   :where [:= :p.id :?id]}
                                         opts)))
        "an UPDATE's SET of a row: the columns its subquery selects; only the document's argument")
    (is (= {} (:document-columns (h/analyze registry {:select [:id] :from [:users]} opts))) "no document")
    (is (= #{:group_id :score} (cols (insert [:r.group_id :r.score] {}) :rows)) "INSERT ... SELECT")
    (is (= #{:group_id :score} (cols (insert [:group_id :score] {}) :rows)) "unqualified")
    (is (= #{:group_id :score :mood} (cols (insert [:r.group_id :r.score] {:where [:= :r.mood "sad"]}) :rows)) "a column WHERE reads")
    (is (= #{:nick :group_id} (cols (record [[[:upper :r.nick]]] {:order-by [:r.group_id]}) :doc)) "a column of an expression, and ORDER BY")
    (is (= #{:nick :id} (cols (record [:r.nick] {:where [:exists {:select [1] :from [:groups] :where [:= :groups.id :r.id]}]}) :doc))
        "a column a subquery reads")
    (is (= #{:nick :born} (cols (record [:r.nick :j.k] {:from [[[:jsonb_populate_record [:cast nil :users] :?doc] :r] [[:lateral {:select [[:r.born :k]]}] :j]]}) :doc))
        "a column a LATERAL subquery reads")
    (is (= #{:nick} (cols (record [:r.nick] {:where [:exists {:select [1] :from [[:groups :r]] :where [:= :r.id 1]}]}) :doc))
        "not a column of a nearer table of the alias's name")
    (doseq [select [[:r.*] [:*] [[[:to_jsonb :r]]]]]
      (is (nil? (cols (record select {}) :doc)) (str "nil under " select)))
    (is (nil? (cols (record [:r.nick] {:where [:= [:raw "r.score"] 1]}) :doc)) "nil under :raw")
    (is (nil? (cols {:select [:r.nick :x] :from [[[:jsonb_populate_record [:cast nil :users] :?doc] [:r {:columns [:x]}]]]} :doc))
        "nil where the alias's list renames them")
    (is (nil? (cols {:select [[[:jsonb_populate_record [:cast nil :users] :?doc] :x]]} :doc)) "nil for a call in no FROM")))

(deftest a-populate-documents-keys-are-every-column-of-the-row-type-a-key-of-no-column-refused
  (let [ok? (fn [query v] (m/validate (:rows (args query)) v {:registry registry}))
        q {:insert-into [[:users [:group_id :score]] {:select [:r.group_id :r.score] :from [[[:jsonb_populate_recordset [:cast nil :users] :?rows] :r]]}]}]
    (is (ok? q [{:group_id 1 :score 0 :nick "ann"}]) "a column the statement does not use, which PostgreSQL ignores")
    (is (ok? q [{"group_id" 1 "score" 0 "nick" "ann"}]) "string keys")
    (is (not (ok? q [{:group_id 1 :score 0 :nik "ann"}])) "a key of no column")
    (is (not (ok? q [{:group_id 1}])) "a NOT NULL column's key still required")))

(deftest a-to-recordsets-alias-defines-its-columns-and-its-document-is-typed-as-a-populate-documents
  (let [ok? (fn [query k v] (m/validate (k (args query)) v {:registry registry}))
        words (fn [select more] (merge {:select select
                                        :from [[[:jsonb_to_recordset :?words] [:w {:with-columns [[:pattern :text] [:role_keys :text :array] [:hide :boolean]]}]]]}
                                       more))
        q (words [:w.pattern :w.role_keys :w.hide] {})
        {:keys [problems document-columns]} (h/analyze registry q opts)]
    (is (= [] problems) "the column definition list is read")
    (is (= [:map [:pattern [:maybe text]] [:role_keys [:maybe [:vector {:pg/type "text[]"} [:maybe :string]]]] [:hide [:maybe [:boolean {:pg/type "boolean"}]]]]
           (row q))
        "each column of the type written, [:x :text :array] text[]")
    (is (= {:words #{:pattern :role_keys :hide}} document-columns))
    (is (= {:words #{:pattern}} (:document-columns (h/analyze registry (words [:w.pattern] {}) opts))))
    (is (= {:words nil} (:document-columns (h/analyze registry (words [:*] {}) opts))))
    (doseq [v [[] [{}] [{:pattern "a" :role_keys ["x" nil] :hide true}] [{"pattern" "a"}] nil]]
      (is (ok? q :words v) (pr-str v)))
    (doseq [[v why] [[{:pattern "a"} "a map, which a recordset is not"]
                     [[{:patern "a"}] "a key of no column"]
                     [[{:hide 1}] "a number to a boolean column"]
                     [[{:role_keys 1}] "a number to an array column"]]]
      (is (not (ok? q :words v)) why))
    (is (ok? q :words [{:role_keys "{a,b}"}]) "a string, the array's text, which its type's input reads")
    (let [record {:select [:*] :from [[[:jsonb_to_record [:cast :?doc :jsonb]] [:r {:with-columns [[:n :int] [:x :nosuch]]}]]]}]
      (is (ok? record :doc {:n 1 :x [1]}) "a type not read, :any")
      (is (not (ok? record :doc {:n 1.5})) "a fraction, which an integer's input does not read")
      (is (not (ok? record :doc [{:n 1}])) "a vector, which a record is not")
      (is (= [:maybe :any] (last (last (row record))))))
    (let [insert {:insert-into [[:users [:group_id :score]]
                                {:select [:r.group_id :r.score] :from [[[:jsonb_to_recordset :?rows] [:r {:with-columns [[:group_id :int] [:score :int]]}]]]}]}]
      (is (ok? insert :rows [{:group_id 1 :score 0}]))
      (is (not (ok? insert :rows [{:group_id 1}])) "score is NOT NULL"))))

(deftest a-numeric-or-domain-array-column-takes-a-narrower-numbers-array-assigned
  (let [reg (pgmalli/registry {:registry {:pg.public/z [:map {:pg/table "public.z"} [:id [:int {:pg/type "integer"}]]
                                                        [:nn [:maybe [:vector {:pg/type "numeric[]"} 'decimal?]]]
                                                        [:dn [:maybe [:vector {:pg/type "numeric[]" :pg/domain "dnum[]"} 'decimal?]]]]}})]
    (doseq [c [:nn :dn]]
      (is (m/validate (:p (:args (h/analyze reg {:update :z :set {c :?p} :where [:= :id 1]}))) (long-array [1]) {:registry reg}) (name c)))))

(deftest an-array-compared-takes-elements-beyond-the-columns-length
  (let [reg (pgmalli/registry {:registry {:pg.public/z [:map {:pg/table "public.z"} [:id [:int {:pg/type "integer"}]]
                                                        [:vs [:maybe [:vector {:pg/type "character varying[]"} [:string {:max 5 :pg/max-length 5 :pg/type "character varying"}]]]]]}})]
    (is (m/validate (:p (:args (h/analyze reg {:select [:id] :from [:z] :where [:= :vs :?p]}))) (into-array String ["abcdefg"]) {:registry reg}))))

(deftest a-domains-value-is-sent-as-its-base-types
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]]
                                                        [:n ['decimal? {:pg/type "numeric" :pg/domain "dn"}]]]}})]
    (is (m/validate (:x (:args (h/analyze reg {:select [:id] :from [:t] :where [:= :n :?x]}))) 1N {:registry reg}) "a BigInt as a numeric")
    (is (m/validate (:x (:args (h/analyze reg {:update :t :set {:n :?x} :where [:= :id 1]}))) 1 {:registry reg}) "a Long assigned, as to a numeric")))

(deftest a-domain-arrays-elements-read-as-the-drivers-objects
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:ps [:vector {:pg/type "integer[]" :pg/domain "posint[]"} [:int {:pg/type "integer"}]]]]}})]
    (is (m/validate (pgmalli/as-read reg :pg.public/t)
                    {:ps [(Object.)]} {:registry reg})
        "pgjdbc returns a domain's elements as PGobjects")))

(deftest an-array-assigneds-elements-are-held-to-the-element-types-range
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:ps [:vector {:pg/type "integer[]" :pg/domain "posint[]"} :pg/integer]]
                                                        [:is [:vector {:pg/type "integer[]"} :pg/integer]]]}})
        ok? (fn [c v] (m/validate (:x (:args (h/analyze reg {:update :t :set {c :?x}}))) v {:registry reg}))]
    (is (ok? :ps (long-array [1])))
    (is (not (ok? :ps (long-array [3000000000]))) "a domain's elements, its base type's range")
    (is (ok? :is (long-array [2147483647])))
    (is (not (ok? :is (long-array [3000000000]))))))

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
  (pgmalli/registry (assoc pgmalli-next.sample/sample-data :database-version v)))

(deftest a-pg-catalog-column-not-in-every-version-the-registry-is-of-is-optional
  (let [frozen #(some (fn [e] (when (= :relallfrozen (first e)) e)) (rest (:row (h/analyze % {:select [:*] :from [:pg_class]} opts))))]
    (is (= [:relallfrozen {:optional true} int4] (frozen registry)) "a file of no version known: any version the catalog holds")
    (is (= [:relallfrozen int4] (frozen (generated-on "PostgreSQL 18"))))
    (is (nil? (frozen (generated-on "PostgreSQL 17"))))
    (is (= [{:kind :unknown-column :column :relallfrozen}]
           (:problems (h/analyze (generated-on "PostgreSQL 17") {:select [:relallfrozen] :from [:pg_class]} opts))))
    (is (= [] (check {:select [:relallfrozen] :from [:pg_class]})))))

(deftest a-function-of-the-version-the-registry-is-of-alone-is-typed-there
  (let [q {:select [[[:uuidv7] :u] [[:casefold [:inline "a"]] :c]]}]
    (is (= [:map [:u [:maybe [:uuid {:pg/type "uuid"}]]] [:c [:maybe [:string {:pg/type "text"}]]]]
           (:row (h/analyze (generated-on "PostgreSQL 18") q opts))))
    (is (= [:map [:u [:maybe :any]] [:c [:maybe :any]]] (row q)) "PostgreSQL 16 and 17 have neither")))

(deftest functions-and-operators-have-the-type-pg-catalog-resolves-them-to
  (let [row #(:row (h/analyze registry {:select (vec (for [[k e] %] [e k])) :from [:users]} opts))
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
            [:b [:maybe [:boolean {:pg/type "boolean"}]]]] (:row (h/analyze registry {:select [[[:filter [:count :*] {:where [:= :id 1]}] :n] [[:filter [:min :closed_at] {:where [:= :id 1]}] :m]
                                                [[:sum :score] :s] [[:avg :id] :a] [[:bool_or [:> :score 1]] :b]]
                                       :from [:users]} opts)))
        "an aggregate with FILTER has the aggregate's type; count is never NULL")
    (is (= [:map
            [:id [:int {:pg/identity :always :pg/type "bigint"}]]
            [:rn [:int {:pg/type "bigint"}]]
            [:prev [:maybe [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]] (:row (h/analyze registry {:select [:id [[:over [[:row_number] {:partition-by [:group_id]} :rn] [[:lag :score] {} :prev]]]] :from [:users]} opts)))
        "a window function: row_number is a bigint")
    (is (= [:map
            [:value [:maybe [:any {:pg/type "jsonb"}]]]
            [:ordinality [:int {:pg/type "bigint"}]]
            [:g [:maybe [:int {:pg/type "bigint"}]]]] (:row (h/analyze registry {:select [:e.value :e.ordinality :g]
                                       :from [[[:with-ordinality [:jsonb_array_elements [:cast :nick :jsonb]] :e]]
                                              [[:generate_series 1 3] :g]]}
                             opts)))
        "a function's columns in FROM, of no table")
    (is (= {:max 1} (-> (h/query-schema registry [:id] {:select [[[:lower :nick] :l]] :from [:users] :where [:= :id :?id]} opts) last second))
        "a function that returns no set keeps one row")))

(deftest a-values-list-is-of-its-columns-common-types
  (is (= [:map
          [:column1 [:double {:pg/type "double precision"}]]
          [:column2 [:maybe [:string {:pg/type "text"}]]]] (:row (h/analyze registry {:select [:*] :from [[{:values [[1 [:inline "a"]] [2.5 nil]]} :v]]} opts)))))

(deftest a-schemas-own-functions-and-operators-resolve-with-pg-catalogs
  (let [row #(:row (h/analyze registry {:select (vec (for [[k e] %] [e k])) :from [:users]} opts))
        both (pgmalli/registry pgmalli-next.sample/both-data)]
    (is (= [:map [:d [:maybe [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]] (row {:d [:double_it :score]})))
    (is (= [:map [:r [:maybe [:string {:pg/type "text"}]]]] (row {:r [:round :score]}))
        "an exact match of the schema's beats pg_catalog's round(double precision), which an integer converts to")
    (is (= [:map [:l [:maybe [:string {:pg/type "text"}]]]] (row {:l [:lower :nick]}))
        "pg_catalog comes first on the search path: the schema's lower(text) gives way to its")
    (is (= [:map [:u [:maybe [:string {:pg/type "text"}]]]] (row {:u [:upper :mood]})) "an overload taking the schema's enum type")
    (is (= [:map [:m [:maybe [:enum {:pg/type "mood"} :happy :sad]]]] (row {:m [:pick_mood]})) "a result of the schema's enum type")
    (is (= [:map [:o [:maybe [:enum {:default :happy :pg/default "happy" :pg/type "mood"} :happy :sad]]]] (row {:o [:+ :mood [:inline 1]]}))
        "an operator of the schema's, of its enum type as its operand's")
    (is (= [:map
            [:uid [:maybe [:int {:pg/type "bigint"}]]]
            [:feel [:maybe [:enum {:pg/type "mood"} :happy :sad]]]] (:row (h/analyze registry {:select [:*] :from [[[:user_rows] :r]]} opts)))
        "RETURNS TABLE: its columns, the last argument left to its default")
    (is (= [:map [:n [:maybe [:int {:pg/type "bigint"}]]]] (:row (h/analyze both {:select [[[:other.note_count] :n]]} opts)))
        "a function qualified with its schema")
    (is (= [:map [:n [:maybe :any]]] (:row (h/analyze both {:select [[[:note_count] :n]]} opts)))
        "a schema off the search path (:schema) is not searched")
    (is (= {:x [:maybe int-call]} (args {:select [[[:double_it :?x] :d]] :from [:users]})))
    (is (map? (second (last (h/query-schema registry [:db] {:select [[[:double_it :score] :d]] :from [:users] :where [:= :id 1]} opts))))
        "a function of the schema's that returns no set keeps the row count")
    (is (not (map? (second (last (h/query-schema registry [:db] {:select [[[:user_rows] :d]] :from [:users] :where [:= :id 1]} opts)))))
        "one that returns a set does not")))

(deftest a-schemas-own-function-is-nullable-and-of-its-declared-type-alone
  (let [reg (pgmalli/registry (update-in pgmalli-next.sample/sample-data [:functions "sample"] assoc
                                         "my_rank" [{:args [] :result "bigint" :kind :w}]
                                         "jsonb_object" [{:args ["integer"] :result "jsonb"}]))]
    (is (= [:map [:r [:maybe [:int {:pg/type "bigint"}]]]]
           (:row (h/analyze reg {:select [[[:over [[:my_rank] {} :r]]]] :from [:users]} opts)))
        "a window function of no argument of the schema's may return NULL; row_number never does")
    (is (= [:map [:o [:maybe [:any {:pg/type "jsonb"}]]]]
           (:row (h/analyze reg {:select [[[:jsonb_object :score] :o]] :from [:users]} opts)))
        "a json builder's name of the schema's says nothing of the shape it returns")))

(deftest some-of-an-argument-is-typed-as-any-of-it
  (let [any (args {:select [:id] :from [:users] :where [:= :id [:any :?ids]]})]
    (is (m/validate (:ids any) (into-array Long [1]) {:registry registry}) "a Java array of the column's type")
    (is (not (m/validate (:ids any) [1] {:registry registry})) "a vector goes as jsonb")
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
    (is (= [:map [:x [:enum {:pg/type "mood"} :happy :sad]]] (row {:select [[[:mood [:inline "sad"]] :x]]}))
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
    (is (= [:map [:ip [:any {:pg/type "inet"}]] [:h [:any {:pg/type "hstore"}]]] (:row (h/analyze reg {:select [:ip :h] :from [:t]} o)))
        "pgjdbc reads inet as a PGobject, hstore as a java.util.Map")
    (is (= [:map {:pg/table "public.t"} [:ip [:any {:pg/type "inet"}]] [:h [:any {:pg/type "hstore"}]]] (pgmalli/as-read reg :pg.public/t o)))
    (let [reg (pgmalli/registry {:registry {:pg.public/r [:map {:pg/table "public.r"} [:v [:some {:pg/type "tstzrange"}]]]}})]
      (is (= [:map [:v [:some {:pg/type "tstzrange"}]]] (:row (h/analyze reg {:select [:v] :from [:r]} o))) "a column never NULL stays so"))
    (is (= {:ip ip :h hs} (:args (h/analyze reg {:update :t :set {:ip :?ip :h :?h}} o))))))

(deftest a-value-of-the-wrong-class-for-a-driver-object-says-the-class-it-should-be
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:c [:string {:pg/type "citext"}]]]}})
        x (:x (:args (h/analyze reg {:update :t :set {:c :?x}})))]
    (is (some #(str/includes? (str %) "should be an org.postgresql.util.PGobject")
              (flatten [((requiring-resolve 'malli.error/humanize) (m/explain x 42 malli-opts))])))))

(deftest an-integer-argument-of-a-function-takes-no-long-postgresql-converts-no-bigint-to-integer-implicitly
  (let [ok? (fn [q v] (m/validate (:x (args q)) v {:registry registry}))]
    (doseq [q [{:select [[[:ntile :?x] :n]] :from [:users]} {:select [[[:round :score :?x] :r]] :from [:users]}
               {:select [[[:left :nick :?x] :l]] :from [:users]}]]
      (is (ok? q (int 2)) (pr-str q))
      (is (ok? q (short 2)) (pr-str q))
      (is (not (ok? q 2)) (pr-str q))
      (is (not (ok? q "2")) (pr-str q)))
    (is (ok? {:select [:*] :from [[[:generate_series 1 :?x] :g]]} 2) "a candidate takes a bigint")
    (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:is [:vector {:pg/type "integer[]"} :pg/integer]] [:i :pg/integer]]}})]
      (doseq [q [{:select [[[:array_append :is :?x] :a]] :from [:t]} {:select [[[:over [[:lag :i [:inline 1] :?x] {}]] :a]] :from [:t]}]]
        (is (m/validate (:x (:args (h/analyze reg q))) 2 {:registry reg}) (str "anycompatible: of the common type a bigint takes part in deciding " (pr-str q)))))
    (is (ok? {:update :users :set {:score :?x} :where [:= :id 1]} 2) "assigned, PostgreSQL converts a bigint to an integer")
    (is (str/includes? (first ((requiring-resolve 'malli.error/humanize) (m/explain (:x (args {:select [[[:ntile :?x] :n]] :from [:users]})) 2 {:registry registry})))
                       "[:cast <parameter> :integer]"))
    (is (every? #(ok? {:select [[[:ntile :?x] :n]] :from [:users]} %) (mg/sample (:x (args {:select [[[:ntile :?x] :n]] :from [:users]})) {:registry registry :seed 1}))
        "it generates")))

(deftest a-window-and-an-aggregate-read-their-lists-as-order-by-items
  (doseq [q [{:select [[[:over [[:rank] {:partition-by [:nope] :order-by [:id]}]] :r]] :from [:users]}
             {:select [[[:over [[:rank] {:partition-by [:id :nope]}]] :r]] :from [:users]}
             {:select [[[:over [[:rank] {:order-by [:nope]}]] :r]] :from [:users]}
             {:select [[[:over [[:rank] {:order-by [:id [:nope :desc]]}]] :r]] :from [:users]}
             {:select [[[:over [[:rank] :w]] :r]] :from [:users] :window [:w {:partition-by [:nope]}]}
             {:select [[[:over [[:sum :id] {:order-by [:id] :frame [:rows :between [:nope :preceding] :current-row]}]] :r]] :from [:users]}
             {:select [[[:array_agg [:order-by :id :nope]] :a]] :from [:users]}
             {:select [[[:array_agg [:order-by :nope :id]] :a]] :from [:users]}
             {:select [[[:array_agg {:distinct :nope :order-by [:id]}] :a]] :from [:users]}
             {:select [[[:count [:distinct :nope]] :a]] :from [:users]}]]
    (is (= [[:unknown-column :nope]] (map (juxt :kind :column) (check q))) (pr-str q)))
  (doseq [q [{:select [[[:over [[:rank] {:partition-by [:group_id [[:lower :nick]]] :order-by [:id [:score :desc]]}]] :r]] :from [:users]}
             {:select [[[:over [[:rank] :w]] :r]] :from [:users] :window [:w {:partition-by [:group_id] :order-by [:id]}]}
             {:select [[[:over [[:sum :id] {:order-by [:id] :frame [:rows :between [:?n :preceding] :current-row]}]] :r]] :from [:users]}
             {:select [[[:over [[:sum :id] {:order-by [:id] :frame [:rows :between :unbounded-preceding :current-row]}]] :r]] :from [:users]}
             {:select [[[:array_agg [:order-by :nick [:id :desc]]] :a]] :from [:users]}
             {:select [[[:count [:distinct :nick]] :a]] :from [:users]}]]
    (is (= [] (check q)) (pr-str q))))

(deftest an-enum-compared-with-any-takes-a-pgobject-of-its-array-type-its-elements-the-labels
  (let [x (:ms (args {:select [:id] :from [:users] :where [:= :mood [:any :?ms]]}))]
    (is (= "PGobject of mood[]" (:pgmalli/describe (shape/props x))))
    (is (= [:happy :sad] (vec (shape/entries (:pgmalli/element (shape/props x))))) "the elements' schema, for an application sending them its own way")
    (is (not (m/validate x (into-array String ["happy"]) {:registry registry})) "a String[] goes as varchar[]: mood = character varying does not exist")))

(deftest an-unknown-table-is-reported-once-though-returning-sees-it-as-old-and-new-too
  (doseq [q [{:update :userz :set {:nick "a"} :returning [:id]} {:delete-from :userz :returning [:id]}]]
    (is (= [:unknown-table] (map :kind (check q))) (pr-str q))))

(deftest a-column-whose-checks-contradict-each-other-takes-no-integer-but-its-row-validates
  (let [reg (pgmalli/registry {:registry {:pg.public/pairs [:map {:pg/table "public.pairs"} [:a [:maybe [:pg/integer {:pg/type "integer" :min 6 :max 2}]]]]}})]
    (is (m/validate :pg.public/pairs {:a nil} {:registry reg}))
    (is (not (m/validate :pg.public/pairs {:a 4} {:registry reg})))))

(deftest a-calls-column-in-a-join-condition-limit-or-offset-is-checked-as-in-where
  (doseq [[q c] [[{:select [:u.id] :from [[:users :u]] :join [[:groups :g] [:= [:upper :g.nmae] "x"]]} :g.nmae]
                 [{:select [:id] :from [:users] :limit [:abs :nikc]} :nikc]
                 [{:select [:id] :from [:users] :offset [:abs :nikc]} :nikc]]]
    (is (= [[:unknown-column c]] (map (juxt :kind :column) (check q))) (pr-str q)))
  (is (= [] (check {:select [:u.id] :from [[:users :u]] :left-join [[:groups :g] [:= [:upper :g.name] "x"]]}))))

(deftest a-number-keyword-is-a-number-not-a-column
  (is (= [] (check {:select [[:10 :n]] :from [:users]}))))

(deftest a-literal-honeysql-sends-to-an-argument-that-takes-no-value-of-its-class-is-a-problem
  (let [lits #(map (juxt :value :sent :type) (filter (comp #{:literal-type} :kind) (check %)))]
    (is (= [[2 "bigint" "integer"]] (lits {:select [[[:round :score 2] :r]] :from [:users]})) "round(integer, bigint) does not exist")
    (is (= [[1 "bigint" nil] [2 "bigint" nil]] (lits {:select [[[:substring :nick 1 2] :s]] :from [:users]})))
    (is (= [[1 "bigint" nil]] (lits {:select [[[:+ :born 1] :d]] :from [:users]})) "date + bigint does not exist")
    (is (= [[1 "bigint" "character varying"]] (lits {:select [:id] :from [:users] :where [:= :nick 1]})) "character varying = bigint does not exist")
    (is (empty? (lits {:select [:id] :from [:users] :where [:and [:= :score 1] [:= :id 1] [:< :score 2.5]]})) "integer = bigint, integer < double precision exist")
    (doseq [q [{:select [[[:+ :score 1] :d]] :from [:users]} {:select [[[:+ 1 2] :d]] :from [:users]}
               {:select [[[:generate_series 1 3] :g]] :from [:users]} {:select [[[:round :score [:inline 2]] :r]] :from [:users]}
               {:select [[[:round :score [:cast 2 :integer]] :r]] :from [:users]} {:select [[[:date_trunc "month" :closed_at] :c]] :from [:users]}]]
      (is (empty? (lits q)) (pr-str q)))
    (is (empty? (map :kind (filter (comp #{:literal-type} :kind) (check {:select [[[:round :score 2] :r]] :from [:users]} (assoc opts :format {:inline true})))))
        "written into the SQL, 2 is an integer")
    (is (= ["2 goes to round as a parameter, which pgjdbc sends as bigint, a type round takes there through no implicit conversion; cast it, [:cast 2 :integer]"]
           (h/humanize (filter (comp #{:literal-type} :kind) (check {:select [[[:round :score 2] :r]] :from [:users]})))))))

(deftest an-argument-of-a-function-or-operator-takes-the-type-it-resolves-to
  (is (= {:now [:or
                [:time/instant {:pg/type "timestamptz"}]
                :time/offset-date-time
                :time/local-date
                :time/local-date-time]
          :d [:maybe [:or (jdbc/typed-pg-object "interval") :time/local-time]]
          :n [:maybe int-call]} (args {:select [:id] :from [:users]
                    :where [:and [:> :?now [:coalesce :closed_at [:- :current_timestamp [:interval "1 day"]]]]
                            [:< [:+ :closed_at :?d] :current_timestamp]
                            [:= :nick [:repeat "a" :?n]]]})))
  (is (= "(PGobject of interval | time | timestamptz | date | timestamp)?"
         (:d (into {} (map vector [:now :d] (:args (pgmalli/describe registry (h/query-schema registry [:now :d] {:select [:id] :from [:users] :where [:> :?now [:- :current_timestamp :?d]]} opts))))))))
  (is (= {:x [:maybe integer-cmp]} (args {:select [[[:case [:= :id 1] :?x :else :score] :c]] :from [:users]}))
      "CASE's values resolve as coalesce's arguments"))

(deftest a-json-column-of-several-shapes-keeps-each-shape
  (let [shapes [:or [:map {:closed true} [:a :string]] [:map {:closed true} [:b :int]]]
        reg (pgmalli/registry {:registry {:app/input shapes
                                          :pg.public/jobs [:map {:pg/table "public.jobs"}
                                                           [:input [:ref {:pg/type "jsonb"} :app/input]]]}})
        o {}
        ok? #(m/validate %1 %2 {:registry reg})
        arg (:input (:args (h/analyze reg {:insert-into :jobs :values [{:input :?input}]} o)))
        row (:row (h/analyze reg {:select [:input] :from [:jobs]} o))]
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

(deftest an-insert-selects-argument-takes-what-its-column-takes-nil-only-where-the-statement-tests-it-for-null
  (let [q (fn [select] {:insert-into [[:users [:group_id :score]] select]})
        g #(:g (args (q %)))
        ss #(:ss (args (q %) opts))
        plain (g {:select [:?g :?s]})]
    (is (= plain (:g (args {:insert-into :users :values [{:group_id :?g :score :?s}]}))) "a row of arguments reaches the column, as one of :values does")
    (is (not (m/validate (ss {:select [:?g :x.s] :from [[[:unnest :?ss] [:x {:columns [:s]}]]]}) (into-array Integer [(int 1) nil]) {:registry registry}))
        "unnest of arguments drops no element")
    (doseq [more [{:where [:is-not :?g nil]} {:having true} {:limit 1} {:offset 1} {:group-by [:?s]} {:from [:groups]}
                  {:where [:not [:exists {:select [1] :from [:users] :where [:= :score 5]}]]}]]
      (is (= plain (g (merge {:select [:?g :?s]} more))) (str (pr-str more) ": a row it writes takes no nil, PostgreSQL refusing it there")))
    (doseq [more [{:where [:= :x.s 1]} {:join [:groups [:= :groups.id 1]]}]]
      (is (not (m/validate (ss (merge {:select [:?g :x.s] :from [[[:unnest :?ss] [:x {:columns [:s]}]]]} more)) (into-array Integer [(int 1) nil]) {:registry registry})) (pr-str more)))
    (let [t (g {:select [:?g :?s] :where [:is-not [:cast :?g :integer] nil]})]
      (is (every? #(m/validate t % {:registry registry}) [nil 1])
          "tested for NULL, cast so that PostgreSQL types it (a bare ? IS NOT NULL is 42P18 for a NULL): nil writes no row")
      (is (not (m/validate t 3000000000 {:registry registry}))))
    (is (not (m/validate (:g (args {:insert-into [[:users [:group_id]] {:select [:?g] :where false}]})) 3000000000 {:registry registry}))
        "a range holds as on a row it writes")))

(deftest an-inserted-value-takes-nil-only-from-a-before-insert-trigger
  ;; PostgreSQL checks NOT NULL on the row proposed for insertion before it looks for a conflict
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:update ["touch"]}}
                                                        [:id [:int {:pg/type "integer"}]] [:g [:int {:pg/type "integer"}]]]}})
        a #(:args (h/analyze reg %))
        conflict {:on-conflict [:id] :do-update-set {:fields [:g] :where [:is-distinct-from [:composite :t.g] [:composite :excluded.g]]}}]
    (doseq [q [{:insert-into :t :values [{:id :?id :g :?g}]}
               {:insert-into [[:t [:id :g]] {:select [:?id :?g]}]}
               {:insert-into [[:t [:id :g]] {:select [:x.i :?g] :from [[[:unnest :?id] [:x {:columns [:i]}]]]}]}]]
      (is (= (a q) (a (merge q conflict))) (pr-str q)))
    (is (= :maybe (first (:g (a {:insert-into :t :values [{:id 1 :g 1}] :on-conflict [:id] :do-update-set {:g :?g}}))))
        "the BEFORE UPDATE trigger may fill what DO UPDATE sets"))
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:insert ["fill"]}}
                                                        [:id [:int {:pg/type "integer"}]] [:g [:int {:pg/type "integer"}]]]}})]
    (is (= :maybe (first (:g (:args (h/analyze reg {:insert-into :t :values [{:id 1 :g [:cast :?g :integer]}]})))))
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
               [:or :time/local-date-time :time/local-date :time/offset-time :time/local-time :time/instant :time/offset-date-time]]}
         (args {:select [[[:timezone :nick :?at] :t]] :from [:users]}))))

(deftest an-array-cast-casts-each-element
  (is (= (args {:select [[[:cast :?k :text] :a]]}) (args {:select [[[:array [:?k] :text] :a]]}))))

(deftest an-argument-of-several-uses-takes-what-each-takes
  (let [native opts]
    (is (= {:id integer-cmp}
           (args {:select [[[:cast :?id :bigint] :a]] :from [:groups] :where [:= :id :?id]} native))
        "a cast takes what converts to a bigint, a comparison an integer column's values; nil neither, = NULL matching no row")
    (is (= (args {:select [:id] :from [:groups] :where [:and [:= :id :?id] [:= :id [:cast :?id :bigint]]]} native)
           (args {:select [:id] :from [:groups] :where [:and [:= :id [:cast :?id :bigint]] [:= :id :?id]]} native))
        "whatever the order")
    (is (= {:id integer-cmp}
           (args {:select [:id] :from [:groups] :where [:and [:= :id :?id] [:is-distinct-from :name [:cast :?id :text]]]} native))
        "nil only where every use takes it")))

(deftest a-pg-catalog-relation-has-the-columns-of-the-catalog-snapshot
  (is (= {:names [:pg/array [:string {:pg/type "name"}]]}
         (args {:select [:c.oid] :from [[:pg_class :c]] :where [:= :c.relname [:any :?names]]})))
  (is (= [:map [:relname [:string {:pg/type "name"}]]]
         (row {:select [:c.relname] :from [[:pg_class :c]]} opts)))
  (is (= [{:kind :unknown-column :column :c.nope}] (check {:select [:c.nope] :from [[:pg_class :c]]})))
  (is (= [] (check {:select [:attstattarget] :from [:pg_attribute]})) "a column one version has"))

(deftest analyze-gives-what-check-arg-types-row-schema-and-tables-give-in-one-map
  (let [q {:select [:nick] :from [:users] :where [:= :id :?id]}]
    (is (= {:args (args q) :row (row q) :rows {:min 0 :max 1} :problems [] :tables (:tables (h/analyze registry q opts)) :document-columns {}} (h/analyze registry q opts)))
    (is (= {:reads #{"sample.users"} :writes #{}} (:tables (h/analyze registry q opts)))))
  (let [q {:select [:nick_name] :from [:users] :where [:= :group_id :?g]}]
    (is (= {:args (args q) :row nil :rows nil :problems (check q) :tables (:tables (h/analyze registry q opts)) :document-columns {}} (h/analyze registry q opts))))
  (is (= {:min 1 :max 1} (:rows (h/analyze registry {:select [[[:count :*] :n]] :from [:users]} opts)))))

(deftest an-argument-no-use-types-says-why
  (is (= {:y [:any {:pgmalli/untyped :position}]} (args {:select [[:?y :y]] :from [:users]})))
  (is (= [:any {:pgmalli/untyped :type}] (:x (args {:update :users :set {:no_such :?x} :where [:= :id 1]}))))
  (is (= [:=> {:pgmalli/problems []} [:cat :any [:any {:pgmalli/untyped :position}]] [:sequential [:map [:y [:maybe :any]]]]]
         (h/query-schema registry [:db :y] {:select [[:?y :y]] :from [:users]} opts))
      "an argument the query lacks stays :any"))

(deftest an-argument-where-a-whole-row-goes-is-untyped-as-honeysql-cannot-format-it
  (is (= {:row [:any {:pgmalli/untyped :position}]} (args {:insert-into :users :values [:?row]})))
  (is (= {:rows [:any {:pgmalli/untyped :position}]} (args {:insert-into :users :values :?rows})))
  (is (= [:any {:pgmalli/untyped :position}] (:row (args {:update :users :set :?row :where [:= :id :?id]})))))

(deftest a-column-of-another-schemas-enum-type-is-of-that-type-and-of-its-row-type-the-drivers-object
  (let [reg (pgmalli/registry {:schemas ["a" "b"]
                               :registry {:pg.a/mood [:enum "x" "y"]
                                          :pg.a/v [:map {:pg/view "a.v"} [:id :int]]
                                          :pg.a/t [:map {:pg/table "a.t"} [:id :int]]
                                          :pg.b/t [:map {:pg/table "b.t"} [:m [:any {:pg/type "a.mood"}]] [:ms [:vector {:pg/type "a.mood[]"} :any]]
                                                   [:v [:any {:pg/type "a.v"}]] [:t [:any {:pg/type "a.t"}]]]}})]
    (is (= [:map [:m [:enum {:pg/type "a.mood"} :x :y]] [:ms [:vector {:pg/type "a.mood[]"} [:maybe [:enum :x :y]]]] [:v [:any {:pg/type "a.v"}]] [:t [:any {:pg/type "a.t"}]]]
           (:row (h/analyze reg {:select [:m :ms :v :t] :from [:t]} {:schema "b"}))))))

(deftest an-argument-tested-for-null-alone-stays-untyped-and-takes-nil-inlined-where-compared-too
  (let [inline (assoc opts :format {:inline true})]
    (is (= {:id [:any {:pgmalli/untyped :position}]} (args {:select [:id] :from [:users] :where [:is :?id nil]} inline)))
    (is (= {:id [:maybe [:or [:int {:pg/type "bigint"}] 'decimal? 'integer? (jdbc/inline-finite :double) (jdbc/inline-finite 'float?) :string :uuid]]}
           (args {:select [:id] :from [:users] :where [:or [:<> :?id nil] [:= :id :?id]]} inline))
        "inlined, a String is a quoted literal, which a bigint's input reads; a Double finite, as NaN is written as a name")
    (is (= {:id bigint-cmp} (args {:select [:id] :from [:users] :where [:or [:= :?id nil] [:= :id :?id]]})))))

(deftest a-wrong-input-fails-saying-what-to-do
  (let [fails #(try (%) nil (catch clojure.lang.ExceptionInfo e [(:pgmalli/error (ex-data e)) (ex-message e)]))]
    (is (re-find #"should be true, false or a vector of column names" (second (fails #(check {:select [:id] :from [:users]} {:jdbc {:return-keys "id"}})))))
    (is (= :pgmalli.error/invalid-options (first (fails #(h/schema-problems {:ns 'no.such.ns})))))
    (is (= :pgmalli.error/not-generated (first (fails #(pgmalli/registry)))))
    (let [q {:select [:id] :from [:users] :where [:= :id :?id]}]
      (is (= (repeat 3 :pgmalli.error/not-a-registry)
             (map #(first (fails (fn [] (% (m/default-schemas))))) [#(h/analyze % q) #(h/values-schema % :users) #(h/set-schema % :users)]))
          "a table no registry pgmalli generates can hold")
      (is (= [:map [:relname [:string {:pg/type "name"}]]] (:row (h/analyze (m/default-schemas) {:select [:relname] :from [:pg_class]})))
          "pg_catalog's"))
    (doseq [[body said] [[nil #"not nil"] [["SELECT 1"] #"not SQL already formatted"] [{:pg.sample/users []} #"registry first"]]]
      (is (= :pgmalli.error/not-query-data (first (fails #(h/analyze registry body opts)))) (pr-str body))
      (is (re-find said (second (fails #(h/analyze registry body opts)))) (pr-str body)))
    (is (= "SELECT * FROM u WHERE pw = ?" (:given (ex-data (is (thrown? clojure.lang.ExceptionInfo (h/analyze registry ["SELECT * FROM u WHERE pw = ?" "hunter2"] opts))))))
        "formatted SQL's text alone: its parameters may be secrets")
    (doseq [[given said] [[{:problems []} #"pass \(:problems result\)"] [[:=> [:cat] :any] #"pass \(:pgmalli/problems"]
                          [{:kind :x} #"got one map"] ["x" #"got java.lang.String"]]]
      (is (= [:pgmalli.error/invalid-options true] (update (fails #(h/humanize given)) 1 (comp boolean (partial re-find said)))) (pr-str given)))
    (is (= :pgmalli.error/invalid-options (first (fails #(pgmalli/as-read registry :pg.sample/users {:jdbc {:return-keys true}}))))
        "as-read reads no :return-keys"))
  (is (= ["clojure.core/map: the query takes argument :y, which the function's args lack; a typo in args?"]
         (h/humanize [{:kind :missing-arg :arg :y :var #'map}]))
      "schema-problems' :var first")
  (is (= {:pgmalli/problems []} (second (h/query-schema registry [] {:select [:id] :from [:users]} opts)))
      "a query schema's parts are where they are, problems or not")
  (is (= {:args ["(bigint | numeric | float8 | real)[]"] :returns [{:id "bigint"}]}
         (pgmalli/describe registry (h/query-schema registry [:ids] {:select [:id] :from [:users] :where [:= :id [:any :?ids]]} opts)))))

(deftest special-syntax-writing-an-operator-names-its-column-?column?
  (doseq [e [[:in :id [1 2]] [:not-in :id [1 2]] [:not true] [:between :id 1 2] [:not-between :id 1 2]
             [:lift 1] [:param :x] [:inline 1]]]
    (is (= [:?column?] (map first (rest (row {:select [[e]] :from [:users]})))) (pr-str e))))

(deftest special-syntax-is-no-function-call
  (is (= [:any #:pgmalli{:untyped :position}] (:x (args {:select [[[:interval :?x]]]})))
      "INTERVAL ? is no call of a function or a cast named interval"))

(deftest an-unquoted-name-is-the-folded-one-whatever-else-the-registry-holds
  (let [reg (pgmalli/registry {:registry {:pg.public/Dup [:map {:pg/table "public.Dup"} [:v [:string {:pg/type "text"}]]]
                                          :pg.public/dup [:map {:pg/table "public.dup"} [:v [:int {:pg/type "bigint"}]]]}})]
    (is (= [:int {:pg/type "bigint"}] (:v (:args (h/analyze reg {:insert-into :Dup :values [{:v :?v}]})))) "INSERT INTO Dup is dup's")
    (is (= [:or [:string {:pg/type "text"}] :keyword] (:v (:args (h/analyze reg {:insert-into :Dup :values [{:v :?v}]} {:format {:quoted true}})))) "quoted, \"Dup\"'s")))

(deftest a-quoted-name-is-looked-up-as-written
  (let [o (assoc opts :format {:quoted true})
        ps (check {:select [:id] :from [:Users]} o)]
    (is (= [{:kind :unknown-table :table "sample.Users" :did-you-mean ["sample.users"] :quoted true}] ps))
    (is (= ["unknown table sample.Users; under :quoted, PostgreSQL looks the name up as written: for the table sample.users write :sample.users"] (h/humanize ps)))
    (is (= [{:kind :unknown-column :column :Nick :did-you-mean [:nick] :quoted true}] (check {:select [:Nick] :from [:users]} o)))
    (is (= ["unknown column Nick; under :quoted, PostgreSQL looks the name up as written: for the column nick write :nick"]
           (h/humanize (check {:select [:Nick] :from [:users]} o))))))

(deftest an-array-argument-beside-an-array-column-takes-the-arrays-postgresql-resolves-them-with
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                         [:ids [:vector {:pg/type "integer[]"} [:maybe [:int {:pg/type "integer"}]]]]]}})
        t (:x (:args (h/analyze reg {:select [[[:coalesce :ids :?x] :v]] :from [:t]})))]
    (is (m/validate t (int-array [1]) {:registry reg}))
    (is (m/validate t (double-array [1.5]) {:registry reg}) "coalesce(integer[], double precision[]) is a double precision[]")
    (is (not (m/validate t (into-array String ["a"]) {:registry reg})) "text[] is of another category")))

(deftest an-argument-where-every-candidate-type-refuses-an-untyped-value-takes-a-typed-one-alone
  (let [ok? #(m/validate %1 %2 {:registry registry})
        t (:x (args {:select [[[:date_trunc "day" :?x] :d]]}))]
    (is (= "date_trunc" (:pgmalli/type-from-value (m/properties (m/schema t {:registry registry})))))
    (doseq [v [(java.time.OffsetDateTime/now) (java.time.LocalDateTime/now) (java.time.LocalDate/now) (java.time.Instant/now)]]
      (is (ok? t v) (str "sent typed: " (class v))))
    (is (not (ok? t nil)) "date_trunc(varchar, unknown) is not unique")))

(deftest a-type-pgmalli-does-not-type-among-a-calls-candidates-takes-a-pgobject-not-any-value
  (let [t (:d (args {:update :users :set {:score [:+ :score :?d]} :where [:= :id 1]}))]
    (doseq [v ["abc" :kw true]]
      (is (not (m/validate t v {:registry registry})) (str "integer + inet and the like take no " (pr-str v))))
    (is (m/validate t 1 {:registry registry}))))

(deftest an-argument-whose-type-comes-from-the-value-alone-keeps-that-uses-values-beside-another
  (let [ok? #(m/validate %1 %2 {:registry registry})
        t (:x (args {:select [[[:cardinality :?x] :n]] :from [:users] :where [:= :?x :score]}))]
    (is (not (ok? t 5)) "cardinality(integer) does not exist")
    (is (not (ok? t (long-array [1]))) "nor is an array an integer column's")
    (let [q {:update :users :set {:mood :?d} :where [:= :id 1] :returning [[[:cardinality :?d] :n]]}]
      (is (= [:undetermined-type] (map :kind (check q))))
      (is (not (ok? (:d (args q)) :happy)) "the label goes untyped, which cardinality refuses"))))

(deftest a-cast-type-is-read-as-honeysql-writes-it-upper-case-a-dash-a-space-which-postgresql-folds
  (let [q #(args {:select [:id] :from [:users] :where [:= :mood [:cast :?x %]]})]
    (is (= [:enum {:pg/type "mood" :pgmalli/cast "mood"} "happy" "sad" :happy :sad] (:x (q :mood)) (:x (q :MOOD))))
    (is (= [:enum {:pg/type "sample.mood" :pgmalli/cast "sample.mood"} "happy" "sad" :happy :sad] (:x (q :sample.Mood)))))
  (is (= [:any {:pgmalli/untyped :position}] (:x (args {:select [[[:cast :?x :my-mood] :m]]})))
      "MY MOOD names no type"))

(deftest clauses-naming-columns-postgresql-checks-are-checked
  (let [kinds #(set (map (juxt :kind :column) (check %)))]
    (is (contains? (kinds {:select [:*] :from [:users] :join [[:groups :g] [:using :nope]]}) [:unknown-column :nope])
        "column nope specified in USING clause does not exist")
    (is (= [{:kind :unknown-column :column :name :clause :using :side :left :table "sample.groups"}] (check {:select [:nick] :from [:users] :join [[:groups :g] [:using :name]]}))
        "users lacks name")
    (is (= [] (check {:select [:*] :from [:users] :join [[:groups :g] [:using :id]]})))
    (is (contains? (kinds {:insert-into :users :values [{:score 1 :group_id 1}] :on-conflict [:nope] :do-nothing true}) [:unknown-column :nope]))
    (is (contains? (kinds {:insert-into :users :values [{:score 1 :group_id 1 :mood :sad}]}) [:unknown-column :sad])
        "HoneySQL writes a keyword value as a column: VALUES (sad)")
    (is (= #{} (kinds {:insert-into :users :values [{:score 1 :group_id 1 :mood :default :nick :null :born :?b}]})))
    (is (= [{:kind :unknown-column :column :users.score :clause :set :did-you-mean [:score]}] (check {:update :users :set {:users.score 1} :where [:= :id 1]}))
        "SET users.score is field score of a column users")
    (is (= ["unknown column users.nick: PostgreSQL reads an assigned users.nick as a field of a column users; assign it unqualified: nick"]
           (h/humanize (check {:insert-into :users :values [{:group_id 1 :score 1 :users.nick "a"}]}))))))

(deftest a-cast-to-float-of-a-precision-up-to-24-is-a-real-and-dec-is-numeric
  (let [row-type #(second (second (row {:select [[[:cast 1 %] :x]]})))]
    (is (= (row-type :real) (row-type [:float [:inline 10]]) (row-type [:raw "float(24)"])) "gram.y opt_float")
    (is (= (row-type :double-precision) (row-type [:float [:inline 25]]) (row-type :float)))
    (is (= (row-type :numeric) (row-type :dec)))))

(deftest a-table-has-system-columns-a-view-has-none
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]]]
                                          :pg.public/v [:map {:pg/view "public.v"} [:id [:int {:pg/type "integer"}]]]}})
        problems #(:problems (h/analyze reg {:select [:ctid :tableoid] :from [%]}))]
    (is (= [] (problems :t)))
    (is (= [:ctid :tableoid] (map :column (problems :v))) "column ctid does not exist")))

(deftest an-argument-in-a-clause-analyze-does-not-read-stays-untyped
  (is (= {:n [:any {:pgmalli/untyped :position}]}
         (args {:merge-into :users :using [[:groups :g]] :on [:= :users.group_id :g.id] :when-matched {:update {:set {:name :?n}}}}))
      ":name is a column there, not a cast to the type name"))

(deftest a-document-value-of-an-array-column-is-a-json-array-or-the-arrays-text
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                         [:a [:maybe [:vector {:pg/type "integer[]"} [:maybe [:pg/integer {:pg/type "integer"}]]]]]]}})
        d (:doc (:args (h/analyze reg {:select [:*] :from [[[:jsonb_populate_record [:cast nil :t] :?doc] :r]]})))]
    (is (m/validate d {:a [1 2]} {:registry reg}))
    (is (m/validate d {:a "{1,2}"} {:registry reg}) "jsonb_populate_record reads a JSON string through integer[]'s input")
    (is (not (m/validate d {:a 1} {:registry reg})))))

(deftest an-unknown-column-a-clause-names-says-which-clause-and-what-was-meant
  (is (= [{:kind :unknown-column :column :nmae :clause :using :side :both :table "sample.groups" :did-you-mean [:name]}]
         (check {:select [:nick] :from [:users] :join [[:groups :g] [:using :nmae]]}))
      "one problem where both sides lack it")
  (is (= [{:kind :unknown-column :column :scroe :clause :on-conflict :table "sample.users" :did-you-mean [:score]}]
         (filter #(= :unknown-column (:kind %)) (check {:insert-into :users :values [{:score 1 :group_id 1}] :on-conflict [:scroe] :do-nothing true}))))
  (is (= [{:kind :unknown-column :column :on-constraint :clause :on-conflict :table "sample.users" :constraint :users_pkey}]
         (filter #(= :unknown-column (:kind %)) (check {:insert-into :users :values [{:score 1 :group_id 1}] :on-conflict [:on-constraint :users_pkey] :do-nothing true})))
      "HoneySQL writes ON CONFLICT (on_constraint, users_pkey)")
  (is (= [{:kind :unknown-column :column :sad :clause :values :table "sample.users" :target :mood}]
         (check {:insert-into :users :values [{:score 1 :group_id 1 :mood :sad}]})))
  (is (= [] (check {:insert-into :users :values [{:score 1 :group_id 1 :mood :DEFAULT :nick :Null}]}))
      "HoneySQL writes :DEFAULT as DEFAULT, :Null as NULL"))

(deftest a-clause-analyze-does-not-read-is-left-whole-a-clauses-value-it-reads-is-walked-into
  (is (= bigint-cmp (:x (args {:update :users :set {:score {:select [[[:max :score]]] :from [:users] :where [:= :id :?x]}} :where [:= :id 1]})))
      "a subquery in SET: :score is a column there, no clause")
  (is (= [{:kind :unread-clause :clause :qualify}]
         (check {:select [:id] :from [:users] :qualify [:and [:= :mood "angry"] [:= :nopee 1]]}))
      "no other problem of what it holds")
  (is (= bigint-cmp (:x (args {:select [:id] :from [:users] :window [:w {:partition-by [[:= :id :?x]]}]})))
      "a clause that changes no table or column is read all the same"))

(deftest a-materialized-view-has-system-columns-as-a-table-does
  (let [reg (pgmalli/registry {:registry {:pg.public/mv [:map {:pg/view "public.mv" :pg/materialized true} [:id [:int {:pg/type "integer"}]]]}})]
    (is (= [] (:problems (h/analyze reg {:select [:ctid :xmin] :from [:mv]}))))))

(deftest a-quoted-hint-names-a-name-the-registry-holds-and-a-misspelt-format-key-is-an-error
  (let [q #(check % {:schema "sample" :format {:quoted true}})]
    (is (:quoted (first (q {:select [:id] :from [:Users]}))))
    (is (not (contains? (first (q {:select [:id] :from [:Nope]})) :quoted)) "sample.nope is no table either")
    (is (:quoted (first (q {:select [:Nick] :from [:users]}))))
    (is (not (contains? (first (q {:select [:Nope] :from [:users]})) :quoted))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"did you mean: :quoted" (check {:select [1]} {:format {:quote true}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"pgmalli options.*:quoted" (check {:select [1]} {:format {:quoted "yes"}}))))

(deftest using-names-no-system-column
  (is (= [:unknown-column] (distinct (map :kind (check {:select [:nick] :from [:users] :join [:groups [:using :ctid]]}))))))

(deftest another-parameter-of-the-call-refuses-an-untyped-value-only-where-every-type-it-may-be-does
  (let [ok? #(m/validate %1 %2 {:registry registry})]
    (is (ok? (:unit (args {:select [[[:date_trunc :?unit :?ts] :d]]})) :day) "date_trunc(unknown, timestamptz) resolves to (text, timestamptz)")
    (is (ok? (:fmt (args {:select [[[:to_char :?ts :?fmt] :d]]})) nil))
    (is (ok? (:x (args {:select [[[:date_trunc "day" :?x] :d]]})) (java.time.LocalDateTime/now)) "sent as timestamp")))

(deftest a-raw-from-item-of-only-a-table-is-that-table
  (is (= [] (check {:select [:id :users.nick] :from [[[:raw "ONLY users"]]]})) "FROM ONLY users is referred to as users")
  (is (= [:id :nick] (map first (rest (row {:select [:id :nick] :from [[[:raw "users *"]]]})))))
  (is (= [:unknown-column] (map :kind (check {:select [:nope.nick] :from [[[:raw "ONLY users"]]]})))))

(deftest a-call-of-a-types-name-is-a-cast-to-the-type-postgresql-folds
  (let [x #(:x (args {:select [:id] :from [:users] :where [:= :mood [% :?x]]}))]
    (is (= (x :mood) (x :MOOD)) "HoneySQL writes MOOD(?), mood to PostgreSQL")))

(deftest a-column-a-call-names-is-checked
  (is (= [{:kind :unknown-column :column :email}] (check {:select [[[:upper :email] :s]] :from [:users]})))
  (is (= [:unknown-column] (map :kind (check {:select [:id] :from [:users] :where [:= [:+ :scroe 1] 2]}))))
  (is (= [:unknown-column] (map :kind (check {:update :users :set {:score [:+ :scroe 1]} :where [:= :id 1]}))))
  (is (= [] (check {:select [[:nick :n] [[:upper :nick] :u]] :from [:users] :group-by [[:upper :nick] :nick] :order-by [[[:lower :nick] :desc]]}))
      "a select list's [column alias] and ORDER BY's [expression direction] are no calls"))

(deftest a-written-row-and-analyze-agree-on-null-and-name-a-table-as-written
  (let [v (h/values-schema registry :users opts)]
    (is (m/validate v {:group_id 1 :score 1 :nick :null} {:registry registry}) "HoneySQL writes VALUES (..., null)")
    (is (= [] (check {:insert-into :users :values [{:group_id 1 :score 1 :nick :null}]}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"^values-schema: unknown table sample.usres; did you mean: sample.users$"
                        (h/values-schema registry :usres opts))))

(deftest an-sql-word-is-a-value-as-honeysql-writes-it-under-the-format
  (is (= [] (check {:update :users :set {:id :DEFAULT} :where [:= :id 1]})) "HoneySQL writes :DEFAULT as DEFAULT")
  (is (m/validate (h/values-schema registry :users opts) {:group_id 1 :score 1 :total :DEFAULT} {:registry registry}))
  (is (= [:unknown-column] (map :kind (check {:select [:id] :from [:users] :where [:= :nick :current_user]} (assoc opts :format {:quoted true}))))
      "quoted, :current_user is a column \"current_user\"")
  (is (= [:unknown-column] (map :kind (check {:insert-into :users :values [{:group_id 1 :score 1 :nick :null}]} (assoc opts :format {:quoted-always #"^null$"}))))
      ":quoted-always matching it writes \"null\"")
  (is (m/validate (h/values-schema registry :users (assoc opts :format {:quoted-always #"^(null|default)$"})) {:group_id 1 :score 1 :nick :NULL} {:registry registry})
      "HoneySQL writes :NULL as NULL where it quotes null")
  (is (= [:unknown-column] (map :kind (check {:insert-into :users :values [[1 1 :foo]]}))) "a positional row's keyword past any column list"))

(deftest an-order-by-items-direction-inside-over-or-an-aggregate-is-no-column
  (doseq [q [{:select [[[:over [[:row_number] {:partition-by [:group_id] :order-by [[:score :desc]]}]] :rn]] :from [:users]}
             {:select [[[:array_agg [:order-by :nick [:id :desc]]] :a]] :from [:users]}
             {:select [[[:string_agg :nick [:order-by "," [:score :desc-nulls-last]]] :a]] :from [:users]}
             {:select [[[:within-group [:percentile_cont 0.5] {:order-by [[:score :desc]]}] :p]] :from [:users]}
             {:select [[[:over [[:row_number] {:order-by [[:score :DESC]]}]] :rn]] :from [:users]}]]
    (is (= [] (check q)) (pr-str q))))

(deftest the-forms-honeysqls-docs-write-are-read-as-the-sql-they-are
  (doseq [q [{:select [:id [[:over [[:sum :score] {:order-by [:id] :frame [:rows :between :unbounded-preceding :current-row]} :t]]]] :from [:users]}
             {:select [[[:at-time-zone :closed_at :UTC] :z]] :from [:users]}
             {:with-recursive [[[:t {:columns [:n]}] {:union-all [{:select [[1]]} {:select [[[:+ :n 1]]] :from [:t] :where [:< :n 5]}]} :search-depth-first-by :n :set :ord]] :select [:n] :from [:t] :order-by [:ord]}
             {:with-recursive [[[:t {:columns [:n]}] {:union-all [{:select [[1]]} {:select [[[:+ :n 1]]] :from [:t] :where [:< :n 5]}]} :cycle [:n] :set :looped :using :path]] :select [:n :looped :path] :from [:t]}
             {:update :users :set {:score :v.score} :from [[{:values [[1 10]]} [:v [:composite :id :score]]]] :where [:= :users.id :v.id]}
             {:select [:u.id :x.a] :from [[:users :u]] :cross-join [[[:lateral [:jsonb_to_record [:cast "{}" :jsonb]]] [[:raw "x(a int)"]]]]}
             {:select [:g.name :u.nick] :from [[:groups :g]] :left-join [[[:join [:users :u] {:join [[:groups :h] [:= :h.id :u.group_id]]}]] [:= :u.group_id :g.id]]}
             {:union-all [{:nest {:select [:id] :from [:users] :order-by [:id] :limit 1}} {:nest {:select [:id] :from [:groups] :limit 1}}]}
             {:select [[[:array_agg {:distinct :nick :order-by [:nick]}] :nicks]] :from [:users]}
             {:select [[[:filter :%count.* {:where [:< :score 100]}] :%count.c]] :from [:users]}
             {:update :users :set {:score 1} :where [:= :id 1] :returning [:old.score :new]}]]
    (is (= [] (check q)) (pr-str q)))
  (is (= [:looped] (map first (rest (row {:with-recursive [[[:t {:columns [:n]}] {:union-all [{:select [[1]]} {:select [[[:+ :n 1]]] :from [:t] :where [:< :n 5]}]} :cycle [:n] :set :looped :using :path]] :select [:looped] :from [:t]})))))
  (is (= [[:boolean {:pg/type "boolean"}]] (map (comp last last) (rest (row {:with-recursive [[[:t {:columns [:n]}] {:union-all [{:select [[1]]} {:select [[[:+ :n 1]]] :from [:t] :where [:< :n 5]}]} :cycle [:n] :set :looped :using :path]] :select [:looped] :from [:t]})))))
  (is (nil? (row {:select [[[:. [:nest :p] :*]]] :from [[{:select [[[:row 1 2] :p]]} :s]]})) "(p).* is the fields of p")
  (is (= [:unknown-column] (map :kind (check {:select [:u.nick] :from [[:groups :g]] :left-join [[[:join [:users :u] {:join [[:groups :h] [:= :h.id :u.grop_id]]}]] [:= :u.group_id :g.id]]})))
      "a column of the nested join's ON is checked")
  (is (= [:unknown-column] (map :kind (check {:update :users :set {:score 1} :where [:= :old.id 1]}))) "old is RETURNING's alone"))

(deftest a-parenthesized-join-is-one-item-to-the-join-it-is-in
  (let [maybe? #(shape/maybe? (get (into {} (map (juxt first last)) (rest (row %1))) %2))
        group (fn [inner] {:select [:g.id [:u.nick :un] [:h.name :hn]] :from [[:groups :g]]
                           :left-join [[[:join [:users :u] inner]] [:= :u.group_id :g.id]]})]
    (is (maybe? (group {:right-join [[:groups :h] [:= :h.id :u.group_id]]}) :hn) "the outer LEFT JOIN extends the whole group")
    (is (maybe? (group {:join [[:groups :h] [:= :h.id :u.group_id]]}) :un))
    (is (not (maybe? {:select [:g.id [:u.nick :un]] :from [[:groups :g]] :join [[[:join [:users :u] {:right-join [[:groups :h] [:= :h.id :u.group_id]]}]] [:= :u.group_id :g.id]]} :id))
        "a RIGHT JOIN inside extends the tables inside alone")
    (is (= [] (check {:select [:g.id :u.nick :h.name] :from [[:groups :g]] :left-join [[[:join [:users :u] {:cross-join [[:groups :h]]}]] [:= :u.group_id :g.id]]})))
    (is (= [] (check {:select [:u.nick :g.name] :from [[[:join [:users :u] {:join [[:groups :g] [:= :g.id :u.group_id]]}]]]})) "in FROM too")
    (is (not (maybe? {:select [[:x.name :xn]] :from [[[:join [:users :u] {:right-join [[:groups :h] [:= :h.id :u.group_id]]}]] [:groups :x]]} :xn))
        "a RIGHT JOIN inside a FROM item extends no other FROM item")
    (is (= [] (check {:delete-from :users :using [[[:join [:groups :g] {:join [[:groups :h] [:= :h.id :g.id]]}]]] :where [:= :h.id :users.group_id]})) "in USING")
    (is (= [] (check {:select [:u.nick :g.name] :from [[:groups :x]] :cross-join [[[:join [:users :u] {:join [[:groups :g] [:= :g.id :u.group_id]]}]]]})) "in CROSS JOIN")
    (is (= [] (check {:select [:x.id] :from [[:groups :x]] :left-join [[[:join [:groups :g] {:join [[:users :u] [:= :u.group_id :name]]}]] [:= :g.id :x.id]]}))
        "an ON inside sees the tables inside alone: name is g's")
    (is (= [:unknown-column] (map :kind (check {:select [:x.id] :from [[:groups :x]] :left-join [[[:join [:groups :g] {:join [[:users :u] [:= :u.group_id :x.id]]}]] [:= :g.id :x.id]]})))
        "invalid reference to FROM-clause entry for table x")
    (is (= [:unknown-column] (map :kind (check {:select [:x.id] :from [[:groups :x]] :join [[[:join [:users :u] {:join [[{:select [:y.id] :from [[:users :y]] :where [:= :y.group_id :x.id]} :s] [:= :s.id :u.id]]}]] [:= :u.group_id :x.id]]})))
        "a subquery inside, not LATERAL, sees no item of the statement")))

(deftest the-forms-honeysqls-tests-write-are-read-as-the-sql-they-are
  (doseq [q [{:select [:x.id] :from [[{:select [:id] :from [:users]} [[:raw "x"]]]]}
             {:select [[[:trim :!leading :!from :nick] :t] [[:make_interval [:=> :secs 1]] :i]] :from [:users]}
             {:delete-from :users :where [:> :score :10]}
             {:select [[:%abs.1 :a]] :from [:users]}
             {:insert-into [[:raw "groups"]] :values [{:id 1 :name "a"}]}
             {:insert-into :groups :columns [:x/id :x/name] :values [[1 "a"]]}
             {:select [:u.* :g.name] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]] :order-by [:nick]}
             {:select [[:nick :a] [:score :b]] :from [:users] :order-by [:a :b]}
             {:select [[:nick :a] [[:count :id] :n]] :from [:users] :group-by [:a] :order-by [:n :a]}
             {:select [[[:extract :year :!from :closed_at] :y]] :from [:users]}
             {:select [:id [[:over [[:rank] {:order-by [[:score :desc]]} :r]]]] :from [:users] :order-by [:r :id]}
             {:select [:g.name :lp.nick] :from [[:groups :g]]
              :join [[[:join [:users :u] {:cross-join [[[:lateral {:select [:nick] :from [:users] :where [:> :users.id :g.id] :limit 1}] :lp]]}]] [:= :u.group_id :g.id]]}]]
    (is (= [] (check q)) (pr-str q)))
  (is (= [] (check {:select [:Rec.a] :from [[[:jsonb_to_record [:cast "{}" :jsonb]] [[:raw "\"Rec\"(a int)"]]]]} (assoc opts :format {:quoted true}))))
  (is (= [:unknown-column] (map :kind (check {:select [:id] :from [:users] :where [:> :score :10]} (assoc opts :format {:quoted true})))) "quoted, :10 is a column \"10\"")
  (is (not (contains? (args {:select [[[:at-time-zone :closed_at :?z] :t]] :from [:users]}) :z)) "HoneySQL writes AT TIME ZONE '?z'")
  (is (= [{:kind :unknown-column :column :groups/id :clause :columns :did-you-mean [:id]}]
         (filter #(= :unknown-column (:kind %)) (check {:insert-into [:groups [:groups/id :name]] :values [[1 "a"]]})))
      "HoneySQL writes the target's :groups/id as groups.id")
  (is (= [:nope] (map :column (check {:select-distinct-on [[:id :nope] :nick] :from [:users]}))) "DISTINCT ON's expressions are no [expr alias]")
  (is (= [:nik] (map :column (check {:select [:id] :from [:users] :group-by [[:upper :nik]]}))) "a call in GROUP BY is checked")
  (is (= [:nik] (map :column (check {:select [[[:cast :nik :text] :x]] :from [:users]}))) "a column cast is checked, the type's name no column")
  (is (= [] (check {:select [[1 :x]] :group-by [[:rollup :x]]})) "ROLLUP's item may be an output column, as GROUP BY's")
  (is (= [:y] (map :column (check {:select [[1 :x]] :group-by [[:rollup :y]]}))))
  (is (= [] (check {:select [[:%count.id :n]] :from [:users] :group-by [:%lower.nick]})) "a call's shorthand in GROUP BY")
  (let [ps (check {:with [[:s {:select [:u.id :g.id] :from [[:users :u]] :join [[:groups :g] [:= :g.id :u.group_id]]}]] :select [:s.id] :from [:s]})]
    (is (= [{:kind :ambiguous-column :column :s.id :candidates ["s"]}] ps) "column reference id is ambiguous")
    (is (= ["column s.id names several columns of s; give them names apart there ([expr alias])"] (h/humanize ps))))
  (let [q {:select [:id] :from [:users] :where [:in :id {:nest {:select [:nope] :from [:users]}}]}]
    (is (= {:select [:nope] :from [:users]} (get-in q (:in (first (check q))))) ":in reaches the statement through :nest")))

(deftest an-argument-used-twice-takes-what-both-uses-take
  (let [t (args {:select [:id] :from [:users] :where [:or [:= [:cast :?ids [:raw "bigint[]"]] nil] [:= :id [:any :?ids]]]})
        ids (:ids t)
        ok? #(m/validate ids % {:registry registry})]
    (is (ok? (long-array [1 2])))
    (is (not (ok? (into-array String ["1"]))) "the cast reads a String, = ANY sends it as varchar[]")
    (is (ok? nil) "the query tests it for NULL")
    (is (= "(bigint | numeric | float8 | real)[]?" (pgmalli/describe registry ids)) "described as the common type"))
  (is (= "varchar(40) | keyword" (pgmalli/describe registry (:x (args {:update :users :set {:nick :?x} :where [:= :nick :?x]}))))
      "an assignment's varchar(40) within a comparison's varchar")
  (is (= "(varchar | keyword) & (date | timestamp | timestamptz)" (pgmalli/describe registry (:x (args {:select [:id] :from [:users] :where [:and [:= :nick :?x] [:= :born :?x]]}))))
      "uses with no common value, each described")
  (is (str/includes? (pgmalli/describe registry (:d (args {:select [:id] :from [:users] :where [:or [:< :closed_at [:- :current_timestamp :?d]] [:= [:cast :?d :text] "x"]]})))
                     "PGobject of interval")
      "a cast to text takes a PGobject of any type, one of interval too")
  (is (= "(mood: :happy | :sad)?" (pgmalli/describe registry (:m (args {:select [:id] :from [:users] :where [:or [:= [:cast :?m :mood] nil] [:= :mood :?m]]}))))
      "a cast to mood reads a String, compared with an enum column it goes as varchar: the labels as keywords alone"))

(deftest a-raw-from-item-of-sql-beyond-a-name-is-not-read
  (doseq [[raw q] [["jsonb_array_elements('[1]'::jsonb) AS e(value)" [:e.value]]
                   ["jsonb_array_elements('[1]'::jsonb) WITH ORDINALITY AS e(value, n)" [:e.value :e.n]]
                   ["generate_series(1, 2) g" [:g]]]]
    (is (= [] (check {:select q :from [[[:raw raw]]]})) raw))
  (is (= [] (check {:select [:u.nick :x.n] :from [[:users :u] [[:raw "generate_series(1, 2) AS g(n)"]]]}))
      "its name not read, any qualifier may be it")
  (is (= [:unknown-column] (map :kind (check {:select [:u.nik] :from [[:users :u] [[:raw "generate_series(1, 2) AS g(n)"]]]})))
      "a known item's column still checked"))

(deftest a-lifted-collection-is-typed-as-the-one-sending-sends-it
  (is (= [:map [:p [:any {:pg/type "jsonb"}]]] (row {:select [[[:lift {:a 1}] :p]]})))
  (is (= "(jsonb | org.postgresql.util.PGobject)?"
         (pgmalli/describe registry (:doc (args {:select [[[:jsonb_set [:coalesce [:cast nil :jsonb] [:lift {}]] [:cast [:inline "{a}"] [:raw "text[]"]] :?doc] :x]]}))))
      "beside it, a jsonb argument"))

(deftest returning-old-and-new-are-null-where-the-statement-has-no-such-row
  (let [nullable #(shape/maybe? (last (second (row %))))]
    (is (not (nullable {:update :users :set {:score 1} :where [:= :id 1] :returning [:new.score]})))
    (is (not (nullable {:update :users :set {:score 1} :where [:= :id 1] :returning [:old.score]})))
    (is (nullable {:insert-into :users :values [{:group_id 1 :score 1}] :returning [:old.score]}))
    (is (nullable {:delete-from :users :where [:= :id 1] :returning [:new.score]}))))

(deftest a-written-row-generates-what-it-takes-under-the-format
  (doseq [f [{:quoted true} {:quoted-always #"^default$"}]
          :let [v (h/values-schema registry :users (assoc opts :format f))]]
    (is (every? #(m/validate v % {:registry registry}) (mg/sample v {:registry registry :size 20 :seed 3})) (pr-str f))))

(deftest a-row-written-holds-values-as-honeysql-writes-them
  (let [reg (pgmalli/registry {:schemas ["public"]
                               :registry {:pg.public/mood [:enum "ok" "sad"]
                                          :pg.public/t [:map {:pg/table "public.t" :pg/primary-key ["id"]}
                                                        [:id [:pg/integer {:pg/type "integer"}]]
                                                        [:j [:maybe [:any {:pg/type "jsonb"}]]]
                                                        [:tags [:maybe [:vector {:pg/type "text[]"} :string]]]
                                                        [:m [:maybe [:ref {:pg/type "mood"} :pg.public/mood]]]]}})
        values (h/values-schema reg :t)
        set (h/set-schema reg :t)
        ok? (fn [s row] (m/validate s row {:registry reg}))]
    (testing "a json document is [:lift m]: HoneySQL writes a map there as a subquery"
      (is (not (ok? values {:id 1 :j {:a 1}})))
      (is (not (ok? set {:j {:a 1}})))
      (is (ok? values {:id 1 :j [:lift {:a 1}]}))
      (is (ok? set {:j [:lift [1]]})))
    (testing "a vector lifted to an array column goes as jsonb, which PostgreSQL does not assign"
      (is (= [{:kind :literal-type :function "tags" :column :tags :value ["a"] :sent "jsonb" :type "text[]"}]
             (:problems (h/analyze reg {:update :t :set {:tags [:lift ["a"]]} :where [:= :id 1]}))))
      (is (= [] (:problems (h/analyze reg {:update :t :set {:j [:lift {:a 1}]} :where [:= :id 1]})))))
    (testing "an enum label in :set is [:lift :ok], as in :values"
      (is (= [{:kind :unknown-column :column :ok :clause :set :target :m}]
             (:problems (h/analyze reg {:update :t :set {:m :ok} :where [:= :id 1]}))))
      (is (re-find #"in :set for m: HoneySQL writes a keyword there as a column"
                   (first (h/humanize (:problems (h/analyze reg {:update :t :set {:m :ok} :where [:= :id 1]})))))))))

(def ^:private a-call-in-each-clause
  "A query whose clause (the key) holds a call naming the column :nope, which no table has."
  (let [e [:upper :nope]
        set-op (fn [k] {k [{:select [[e]] :from [:users]} {:select [:nick] :from [:users]}]})
        joined (fn [k] {:select [:users.id] :from [:users] k [:groups [:= e "a"]]})]
    (merge (into {} (for [k [:union :union-all :intersect :except :except-all]] [k (set-op k)]))
           (into {} (for [k [:join :left-join :right-join :inner-join :outer-join :full-join]] [k (joined k)]))
           {:select {:select [[e]] :from [:users]}
            :select-distinct {:select-distinct [[e]] :from [:users]}
            :select-distinct-on {:select-distinct-on [[e] :id] :from [:users]}
            :select-top {:select-top [1 [e]] :from [:users]}
            :select-distinct-top {:select-distinct-top [1 [e]] :from [:users]}
            :with {:with [[:c {:select [[e]] :from [:users]}]] :select [:*] :from [:c]}
            :with-recursive {:with-recursive [[:c {:select [[e]] :from [:users]}]] :select [:*] :from [:c]}
            :from {:select [:id] :from [:users [e :x]]}
            :using {:delete-from :users :using [[e :x]]}
            :cross-join {:select [:id] :from [:users] :cross-join [[e :x]]}
            :join-by {:select [:users.id] :from [:users] :join-by [:join [:groups [:= e "a"]]]}
            :where {:select [:id] :from [:users] :where [:= e "a"]}
            :having {:select [:id] :from [:users] :group-by [:id] :having [:= e "a"]}
            :group-by {:select [:id] :from [:users] :group-by [e]}
            :order-by {:select [:id] :from [:users] :order-by [[e :desc]]}
            :limit {:select [:id] :from [:users] :limit [:abs :nope]}
            :offset {:select [:id] :from [:users] :offset [:abs :nope]}
            :fetch {:select [:id] :from [:users] :fetch [:abs :nope]}
            :values {:insert-into :users :values [{:nick e}]}
            :set {:update :users :set {:nick e}}
            :on-conflict {:insert-into :users :values [{:nick "a"}] :on-conflict [e] :do-nothing true}
            :do-update-set {:insert-into :users :values [{:nick "a"}] :on-conflict [:nick] :do-update-set {:nick e}}
            :returning {:delete-from :users :returning [[e]]}
            :window {:select [:id] :from [:users] :window {:w {:partition-by [[e]]}}}
            :partition-by {:select [:id] :from [:users] :partition-by [[e]]}
            :frame {:select [[[:over [[:sum :score] {:order-by [:id] :frame [:rows [[:abs :nope] :preceding]]} :s]]]] :from [:users]}})))

(deftest a-column-a-call-names-is-told-in-every-clause-but-the-expressionless
  (is (= (sort (remove syntax/expressionless-clauses (into syntax/read-clauses syntax/ignored-clauses)))
         (sort (keys a-call-in-each-clause)))
      "a clause analyze reads or ignores holds expressions, with a query in a-call-in-each-clause, or is in hsyntax/expressionless-clauses")
  (doseq [[k q] a-call-in-each-clause]
    (is (some #(= :nope (:column %)) (check q)) (str k " " (pr-str q))))
  (is (= [] (check {:select [:u.id] :from [[:users :u]] :join [[:groups :g] [:using :id]]})) "USING lists columns")
  (is (= [] (filter #(= :unknown-column (:kind %)) (check {:insert-into :groups :values [{:name "a"}] :on-conflict {:on-constraint :groups_pkey} :do-nothing true})))
      "a map's entries are clauses, no call")
  (is (= [] (check {:select [:u.id] :from [[:users :u] [[:with-ordinality [:upper :u.nick] [:x :v :n]]]]})) "an alias is no call")
  (is (= [{:kind :unknown-column :column :nope}] (check {:select [:id] :from [:users] :where [:= [:cardinality [:array [:nope]]] 1]})) "ARRAY[...]'s elements"))

(deftest a-literal-a-schema-cannot-judge-is-not-refused
  (is (false? (#'args/refuses? registry [:ref :pg.nowhere/x] "a")))
  (is (true? (#'args/refuses? registry :int "a"))))

(def ^:private two-schemas
  (pgmalli/registry {:schemas ["app" "billing"]
                     :registry {:pg.app/mood [:enum "happy" "sad"]
                                :pg.app/dmood [:enum "x"]
                                :pg.billing/state [:enum "draft" "sent" "in progress"]
                                :pg.billing/invoices [:map {:pg/table "billing.invoices"}
                                                      [:id [:int {:pg/type "bigint"}]]
                                                      [:state [:ref {:pg/type "state"} :pg.billing/state]]
                                                      [:states [:maybe [:vector {:pg/type "state[]"} [:ref {:pg/type "state"} :pg.billing/state]]]]
                                                      [:payer_mood [:maybe [:ref {:pg/type "app.mood"} :pg.app/mood]]]]}}))

(deftest a-type-a-problem-names-is-qualified-where-the-query-reads-another-schema
  (let [say #(h/humanize (:problems (h/analyze two-schemas %1 %2)))]
    (is (= [{:kind :uncast-string :column :state :value "sent" :type "billing.state"}]
           (:problems (h/analyze two-schemas {:select [:id] :from [:billing.invoices] :where [:= :state "sent"]}))))
    (is (= {:type "state"} (select-keys (first (:problems (h/analyze two-schemas {:select [:id] :from [:invoices] :where [:= :state "sent"]} {:schema "billing"}))) [:type])))
    (testing "an enum's label goes as [:lift :label], as values-schema says; one no keyword spells, cast"
      (is (str/ends-with? (first (say {:select [:id] :from [:billing.invoices] :where [:= :state "sent"]} {})) "write [:lift :sent]"))
      (is (str/ends-with? (first (say {:select [:id] :from [:billing.invoices] :where [:= :state "in progress"]} {})) "write [:cast \"in progress\" :billing.state]"))
      (is (= [] (:problems (h/analyze two-schemas {:select [:id] :from [:billing.invoices] :where [:= :state [:lift :sent]]})))))
    (is (= "billing.state[]" (:type (first (:problems (h/analyze two-schemas {:update :billing.invoices :set {:states [:lift ["sent"]]}})))))))
  (testing "a cast to a type of no schema the query reads, which an enum of another has the name of"
    (is (= [{:kind :unknown-type :type "mood" :did-you-mean ["app.mood"]}]
           (:problems (h/analyze two-schemas {:select [:id] :from [:invoices] :where [:= :payer_mood [:cast "happy" :mood]]} {:schema "billing"}))))
    (is (= [{:kind :unknown-type :type "mood[]" :did-you-mean ["app.mood[]"]}]
           (:problems (h/analyze two-schemas {:select [[[:cast :?m [:raw "mood[]"]] :m]]} {:schema "billing"}))))
    (is (= [] (:problems (h/analyze two-schemas {:select [:id] :from [:invoices] :where [:= :payer_mood [:cast "happy" :app.mood]]} {:schema "billing"}))))
    (is (= [] (:problems (h/analyze two-schemas {:select [[[:cast "x" :thing] :t]]} {:schema "billing"}))) "a type the registry tells nothing of")
    (is (= [] (:problems (h/analyze two-schemas {:select [[[:cast "happy" :mood] :t]]} {:schema "app"}))))))

(deftest a-value-of-no-literal-syntax-is-held-as-a-literal-is
  (let [t (java.time.Instant/parse "2026-01-01T00:00:00Z")]
    (is (= [{:kind :literal-type :function "updated_at" :column :updated_at :value t :sent "timestamp with time zone" :type "timestamp"}]
           (check {:update :users :set {:updated_at t} :where [:= :id 1]})))
    (is (= [:literal-type] (map :kind (check {:update :users :set {:born [:lift t]} :where [:= :id 1]}))))
    (is (= [] (check {:update :users :set {:closed_at t :updated_at (java.time.LocalDateTime/of 2026 1 1 0 0)} :where [:= :id 1]})))
    (is (= [] (check {:select [:id] :from [:users] :where [:> :updated_at t]})) "compared, timestamp converts to timestamptz implicitly")
    (is (= ["a java.time.Instant (2026-01-01T00:00:00Z) goes to column updated_at as a parameter, which pgjdbc sends as timestamp with time zone, a type column updated_at takes there through no implicit conversion; cast it, [:cast <the Instant> :timestamp]"]
           (h/humanize (check {:update :users :set {:updated_at t} :where [:= :id 1]}))))))

(deftest a-write-problem-says-what-to-write
  (is (= ["INSERT into sample.users lacks score, which is NOT NULL with no default; give every row a value of score"]
         (h/humanize (check {:insert-into :users :values [{:group_id 1}]}))))
  (is (= ["NULL written into sample.users.score, which is NOT NULL; write a value, or [:default] where the column has a default"]
         (h/humanize (check {:insert-into :users :values [{:group_id 1 :score nil}]}))))
  (is (= ["NULL written into sample.users.score, which is NOT NULL; write a value"]
         (h/humanize (check {:update :users :set {:score nil}}))))
  (is (str/ends-with? (first (h/humanize [{:kind :column-definition-list :function "f" :alias "r" :columns [:k :v]}]))
                      "name the columns with no type: [:r {:columns [:k :v]}]")))

(deftest a-from-item-that-is-no-call-names-no-column
  (is (= [] (check {:select [:id] :from [[[:only :users]]]})) "ONLY (t) is the table t")
  (is (= [] (check {:select [:g.name :s.n] :from [[:groups :g]] :left-join [[[:join [:users :u] {:cross-join [[[:generate_series 1 :g.id] [:s {:columns [:n]}]]]}]] [:= :u.group_id :g.id]]}))
      "a function inside a parenthesized join sees the tables before the join"))

(deftest a-sequence-an-enums-name-hides-is-a-table-of-untold-columns
  (let [reg (pgmalli/registry {:schemas ["public"] :registry {:pg.public/seqenum [:enum "z"]}})]
    (is (= {:problems [] :row [:map [:last_value [:maybe :any]]]}
           (select-keys (h/analyze reg {:select [:last_value] :from [:seqenum]}) [:problems :row]))
        "PostgreSQL holds a sequence and an enum of one name; the registry the enum's")))

(deftest an-inlined-argument-takes-the-literals-of-the-type-postgresql-gives-them
  (let [inline (assoc opts :format {:inline true})
        ok? (fn [q v] (m/validate (:x (args q inline)) v {:registry registry}))
        set-score {:update :users :set {:score :?x}}]
    (is (ok? set-score 2147483647))
    (is (not (ok? set-score 2147483648)) "past int4's range a literal is a bigint, which an integer column takes on assignment alone")
    (is (ok? set-score 1M) "1M is written 1, an integer")
    (is (not (ok? set-score 1.5M)) "1.5 is a numeric")
    (is (ok? {:update :users :set {:referrer_id :?x}} 3000000000) "a bigint column takes a bigint")
    (doseq [v [##NaN ##Inf]]
      (is (not (ok? {:select [:id] :from [:users] :where [:= :score :?x]} v)) "sql/format writes NaN and Infinity as names"))
    (is (ok? {:select [:id] :from [:users] :where [:= :id [:any :?x]]} "{1,2}") "= ANY of the quoted text of an array")
    (is (not (ok? {:select [:id] :from [:users] :where [:= :id [:any :?x]]} (long-array [1]))) "sql/format writes a Java array as no literal")
    (is (not-any? #(ok? {:select [:id] :from [:users] :where [:in :id :?x]} %) [[1] #{1} 1]) "sql/format writes IN [1, 2]")
    (is (str/includes? (first (me/humanize (m/explain (:x (args {:select [:id] :from [:users] :where [:in :id :?x]} inline)) [1] {:registry registry})))
                       "write the list in the query"))))

(deftest an-inlined-row-generates-as-it-validates
  (let [s (h/values-schema registry :users (assoc opts :format {:inline true}))]
    (is (every? #(m/validate s % {:registry registry}) (mg/sample s {:registry registry :size 30 :seed 1})))))

(deftest a-map-or-a-vector-of-values-written-to-a-column-is-a-problem
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:tags [:maybe [:vector {:pg/type "text[]"} :string]]]
                                                         [:doc [:maybe [:any {:pg/type "jsonb"}]]] [:p [:maybe [:any {:pg/type "pair"}]]] [:n [:maybe [:int {:pg/type "integer"}]]]
                                                         [:s [:maybe [:string {:pg/type "text"}]]] [:ci [:maybe [:string {:pg/type "citext"}]]]]}})
        problems #(:problems (h/analyze reg %))]
    (is (= [{:kind :unlifted-value :table "public.t" :column :tags :clause :set :value ["a" "b"] :type "text[]"}]
           (problems {:update :t :set {:tags ["a" "b"]}})))
    (is (= ["{:a 1} in :values for public.t.doc: HoneySQL writes a map there as a subquery; write [:lift {:a 1}], sent as one parameter (jsonb)"]
           (h/humanize (problems {:insert-into :t :values [{:doc {:a 1}}]}))))
    (is (= [] (problems {:update :t :set {:n [1] :p [1 2] :doc [:lift {:a 1}]}}))
        "(?) is one value; a row type's column may take a row")
    (is (= [] (problems {:update :t :set {:s ["a" "b"] :ci ["a" "b"]}})) "a string column takes the row's text, (a,b), an extension's of strings too")
    (is (= [] (problems {:insert-into :t :values [{:n {:values [[1]]}}]})) "VALUES is a subquery")))

(deftest distinct-on-reads-an-output-column-as-order-by-does
  (is (= [] (check {:select-distinct-on [[:s] [:score :s]] :from [:users] :order-by [:s]}))))

(deftest a-call-of-sql-syntax-is-told-by-the-name-sql-format-writes
  (is (= [:map [:pg_collation_for [:maybe text]]] (row {:select [[[:collation-for :nick]]] :from [:users]}))
      "COLLATION FOR(x) is pg_collation_for(x)")
  (is (= [:map [:collation_for [:maybe :any]]] (row {:select [[[:collation_for :nick]]] :from [:users]}))
      "COLLATION_FOR(x) is a function of that name, which pg_catalog lacks"))

(deftest a-value-of-a-written-row-says-what-to-write
  (let [explain #(me/humanize (m/explain (h/set-schema registry :users opts) % {:registry registry}))]
    (is (str/includes? (first (:mood (explain {:mood :sad}))) "HoneySQL writes a bare keyword as a column: write [:lift :sad]"))
    (is (str/includes? (first (:mood (explain {:mood "sad"}))) "write the label as a keyword, [:lift :sad]"))
    (is (str/includes? (first (:updated_at (explain {:updated_at java.time.Instant/EPOCH}))) "a java.time.Instant goes as timestamp with time zone"))))

(deftest a-json-column-an-override-shapes-takes-that-shape-as-jsonb-does
  (doseq [t ["json" "jsonb"]
          :let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:j [:map {:pg/type t} [:a :int]]]]}})
                s (:x (:args (h/analyze reg {:update :t :set {:j :?x}})))]]
    (is (m/validate s {:a 1} {:registry reg}) t)
    (is (not (m/validate s {:a "x"} {:registry reg})) t)))

(deftest a-not-null-column-of-a-type-pgmalli-does-not-type-takes-what-a-nullable-one-does
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:i [:some {:pg/type "inet"}]] [:n [:maybe [:any {:pg/type "inet"}]]]]}})
        {:keys [i n]} (:args (h/analyze reg {:update :t :set {:i :?i :n :?n}}))]
    (is (= [:maybe i] n))
    (is (not (m/validate i "10.0.0.1" {:registry reg})) "a String goes as varchar, which inet takes not")))

(deftest an-array-column-of-a-type-no-java-array-goes-as-takes-a-pgobject-of-it
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:ts [:maybe [:vector {:pg/type "time[]"} :time/local-time]]]
                                                         [:ns [:maybe [:vector {:pg/type "numeric[]"} 'decimal?]]]]}})
        {:keys [ts ns]} (:args (h/analyze reg {:update :t :set {:ts :?ts :ns :?ns}}))]
    (is (= "PGobject of time[]?" (pgmalli/describe ts)) "pgjdbc sends no Java array as time[]")
    (is (m/validate ns (into-array Double [##NaN]) {:registry reg}) "float8[] to numeric[], element by element: NaN as it is")
    (is (not (m/validate ns (into-array Double [1.5]) {:registry reg})) "1.5 would change")))

(deftest a-generated-row-is-within-the-bounds-the-registry-generates-a-column-in
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:v [:maybe [:string {:pg/max-length 3 :pg/type "character varying"}]]]
                                                         [:at [:maybe [:time/instant {:pg/type "timestamp with time zone"}]]]
                                                         [:n [:maybe [decimal? {:pg/type "numeric"}]]]]}})
        rows (mg/sample (h/values-schema reg :t) {:registry reg :size 60 :seed 7})
        value #(let [v (get % %2)] (if (vector? v) (second v) v))]
    (is (every? #(<= (count (name %)) 3) (keep #(let [v (value % :v)] (when (and (or (string? v) (keyword? v)) (not= :null v)) v)) rows)) "varchar(3): 3 characters at most, a keyword too")
    (is (every? #(= 2025 (.getYear (.atZone ^java.time.Instant % java.time.ZoneOffset/UTC))) (filter inst? (keep #(value % :at) rows))) "an Instant in 2025, as the registry's")
    (is (not-any? #(and (double? %) (Double/isInfinite %)) (keep #(value % :n) rows)) "no infinity, which a numeric(p, s) holds not")))

(deftest a-java-array-and-a-schema-made-under-a-registry-are-described
  (is (= "bigint[] | integer[]" (pgmalli/describe [:or [:pg/array [:int {:pg/type "bigint"}]] [:pg/array [:int {:pg/type "integer"}]]])))
  (is (= "bigint" (:id (pgmalli/describe (m/schema [:ref :pg.sample/users] {:registry registry})))) "its columns"))

(deftest a-table-named-outside-the-default-schema-is-named-where-it-is
  (let [reg (pgmalli/registry {:schemas ["a" "b"] :registry {:pg.a/t [:map {:pg/table "a.t"} [:id :int]] :pg.b/u [:map {:pg/table "b.u"} [:id :int]]}})
        e (try (h/values-schema reg :t) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (= [:a.t] (:did-you-mean (ex-data e))))
    (is (str/includes? (ex-message e) "write :a.t"))))


(deftest a-call-honeysql-writes-otherwise-than-it-reads-is-a-problem
  (is (= [{:kind :written-otherwise :op :in :dropped 1}] (check {:select [:id] :from [:users] :where [:in :mood [:lift :happy] [:lift :sad]]}))
      "sql/format writes mood IN ?, :sad dropped")
  (is (= [{:kind :written-otherwise :op (keyword "~~~") :call true}] (check {:select [:id] :from [:users] :where [(keyword "~~~") :nick "a"]}))
      "an operator HoneySQL has not registered, written ~~~(nick, ?)")
  (is (= [] (check {:select [:id] :from [:users] :where [:in :mood [[:lift :happy] [:lift :sad]]]}))))

(deftest an-argument-to-a-not-null-column-of-a-type-pgmalli-does-not-type-takes-no-nil
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:x [:some {:pg/type "xml"}]]]}})]
    (is (not (m/validate (:x (:args (h/analyze reg {:insert-into :t :values [{:x :?x}]}))) nil {:registry reg})))))

(deftest a-number-to-an-oid-is-held-to-oids-range-wherever-it-goes
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:o [:int {:pg/type "oid" :min 0 :max 4294967295}]] [:rc [:any {:pg/type "regclass"}]]]}})
        t #(:x (:args (h/analyze reg %)))]
    (doseq [q [{:select [:*] :from [:t] :where [:= :o :?x]} {:select [:*] :from [:t] :where [:= :rc :?x]}
               {:select [[[:coalesce :o :?x] :c]] :from [:t]} {:select [[[:coalesce :rc :?x] :c]] :from [:t]}]]
      (is (= [false false true] (map #(m/validate (t q) % {:registry reg}) [-1 4294967296 1])) (pr-str q)))))

(deftest a-subquery-nested-deep-is-counted-once
  (let [nested (reduce (fn [q _] {:select [:id] :from [[q :q]]}) {:select [:id] :from [:users]} (range 30))
        f (future (h/analyze registry nested opts))]
    (is (not= ::slow (deref f 20000 ::slow)) "each statement's rows counted once, not once per way an enclosing one reads them")
    (future-cancel f)))

(deftest a-problems-sentence-says-a-long-literal-by-its-class
  (let [long-label (apply str (repeat 1000 "x"))
        said (first (h/humanize (check {:select [:id] :from [:users] :where [:= :mood long-label]})))]
    (is (< (count said) 400) said)
    (is (str/includes? said "<a String>"))))

(deftest a-number-to-a-real-is-held-to-single-precisions-range-whatever-its-class
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:r [:double {:pg/type "real"}]]]}})
        t (:x (:args (h/analyze reg {:update :t :set {:r :?x}})))]
    (is (= [false false true true false false false]
           (map #(m/validate t % {:registry reg}) [3.5e38 1.5 (float 1.5) 1 1E39M 1E400M (clojure.core/bigint 1e39)]))
        "a Double, which goes as double precision, which PostgreSQL rounds on assignment alone; a BigDecimal or a BigInt past its range, which PostgreSQL converts to a real and refuses")))

(deftest a-null-test-is-read-as-transform-null-equals-has-honeysql-write-it
  ;; HoneySQL 2.4 reads no :transform-null-equals
  (when (= "SELECT x WHERE a = NULL" (first (sql/format {:select [:x] :where [:= :a nil]} {:transform-null-equals false})))
    (is (= [] (check {:select [:id] :from [:users] :where [:= :nick nil]})))
    (is (not= (args {:select [:id] :from [:users] :where [:or [:= :?n nil] [:= :id :?n]]})
              (args {:select [:id] :from [:users] :where [:or [:= :?n nil] [:= :id :?n]]} {:format {:transform-null-equals false}}))
        "false: ? = NULL, which tests nothing for NULL")))

(deftest a-populate-documents-key-of-a-column-a-trigger-may-fill-names-the-triggers-as-an-argument-does
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/before-triggers {:insert ["fill"]}}
                                                        [:group_id [:int {:pg/type "bigint"}]] [:nick [:maybe [:string {:pg/type "text"}]]]]}})
        doc (-> (:args (h/analyze reg {:insert-into [[:t [:group_id :nick]] {:select [:group_id :nick] :from [[[:jsonb_populate_recordset [:cast nil :t] :?rows] :r]]}]}))
                :rows shape/non-null shape/alternatives first last shape/and-base)
        key-type #(shape/non-null (last (some (fn [e] (when (and (vector? e) (= % (first e))) e)) (rest doc))))]
    (is (= ["fill"] (:pgmalli/before-triggers (shape/props (key-type :group_id)))))
    (is (nil? (:pgmalli/before-triggers (shape/props (key-type :nick)))) "a nullable column's is nullable of itself")))

(deftest a-keyword-to-a-checked-string-column-is-held-to-the-check-its-text-meets
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:e [:and {:pg/type "character varying" :pg/domain "email"} :string [:re "@"]]]]}})
        t (:x (:args (h/analyze reg {:update :t :set {:e :?x}})))
        greg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"}
                                                         [:e [:and {:pg/type "character varying" :pg/domain "email" :gen/schema [:string {:min 1 :max 5}] :gen/fmap #(str % "@x")}
                                                              :string [:fn #(str/includes? % "@")]]]]}})
        v (h/values-schema greg :t)]
    (is (= [true false true false] (map #(m/validate t % {:registry reg}) ["a@b" "nope" (keyword "a@b") :nope]))
        "a keyword goes as its text, which the column gets")
    (is (every? #(re-find #"@" (let [e (:e %)] (if (vector? e) (name (second e)) e))) (mg/sample v {:registry greg :size 20 :seed 3}))
        "generated within the check")))

(deftest a-literal-of-the-columns-type-out-of-its-shape-says-so
  (let [said (first (h/humanize (:problems (h/analyze docs {:update :docs :set {:meta [:lift {:a "x"}]} :where [:= :id 1]}))))]
    (is (str/includes? said "its own type, but is none of the values its schema takes") said)))
