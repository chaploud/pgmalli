(ns pgmalli.impl.render-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.experimental.time :as time]
            [malli.generator :as mg]
            [malli.util :as mu]
            [pgmalli.impl.pattern :as p]
            [pgmalli.impl.render :as r]
            [pgmalli.impl.pgtypes :as pgtypes]
            [pgmalli.impl.registry :as reg]
            [pgmalli.impl.shape :as shape]
            [pgmalli.impl.types :as types]))

(def ^:private schema
  {:name "public"
   :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}}
   :tables {"groups" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}]
                      :constraints {"groups_pkey" {:name "groups_pkey" :type "PRIMARY KEY" :columns ["id"]}}}
            "users" {:columns [{:name "id" :position 1 :data_type "bigint" :is_nullable false :identity "ALWAYS"}
                               {:name "group_id" :position 2 :data_type "integer" :is_nullable false}
                               {:name "mood" :position 3 :data_type "mood" :type_schema "public" :is_nullable false :default_value "'happy'::mood"}
                               {:name "mail" :position 4 :data_type "text" :domain "email" :type_schema "pg_catalog" :is_nullable true}
                               {:name "nick" :position 5 :data_type "character varying" :is_nullable true :max_length 40}
                               {:name "age" :position 6 :data_type "integer" :is_nullable true}
                               {:name "title" :position 7 :data_type "text" :is_nullable false}
                               {:name "born" :position 8 :data_type "date" :is_nullable true}
                               {:name "closed_at" :position 9 :data_type "timestamptz" :is_nullable true}
                               {:name "created_at" :position 10 :data_type "timestamptz" :is_nullable false :default_value "now()"}
                               {:name "seq" :position 11 :data_type "integer" :is_nullable false :default_value "nextval('users_seq_seq'::regclass)"}
                               {:name "full_name" :position 12 :data_type "text" :is_nullable true :generated_expr "(nick || 'x'::text)"}
                               {:name "score" :position 13 :data_type "integer" :is_nullable false}
                               {:name "total" :position 14 :data_type "integer" :is_nullable false}
                               {:name "price" :position 15 :data_type "numeric" :is_nullable false :default_value "0"}
                               {:name "since" :position 16 :data_type "date" :is_nullable false :default_value "'2020-01-01'::date"}
                               {:name "flag" :position 17 :data_type "boolean" :is_nullable false :default_value "false"}]
                     :constraints {"users_pkey" {:name "users_pkey" :type "PRIMARY KEY" :columns ["id"]}
                                   "users_nick_key" {:name "users_nick_key" :type "UNIQUE" :columns ["nick"]}
                                   "users_group_id_fkey" {:name "users_group_id_fkey" :type "FOREIGN KEY" :columns ["group_id"]
                                                          :references {:schema "public" :table "groups" :columns ["id"]}}}}}})

(def ^:private facts (p/facts schema))

(defn- registry-with [r] (merge (m/default-schemas) (mu/schemas) (time/schemas) pgtypes/schemas r))

(deftest row-schema
  (let [{:keys [registry unrendered]} (r/rendered facts)
        users (:pg.public/users registry)]
    (is (= [:enum "happy" "sad"] (:pg.public/mood registry)))
    (is (nil? (:pg.public/email registry)) "a domain is no entry: its columns are of its base type")
    (is (= :map (first users)) "its columns alone: a CHECK is no part of it")
    (is (= {:pg/foreign-keys [{:columns ["group_id"] :table "public.groups" :to ["id"]}]
            :pg/unique [{:columns ["nick"]}]
            :pg/table "public.users"
            :pg/primary-key ["id"]}
           (second users)))
    (is (= [:age [:maybe [:pg/integer {:pg/type "integer"}]]]
           (nth users 2)))
    (is (= 19 (count users)))
    (is (= [] unrendered))
    (testing "validation and generation through malli"
      (let [reg (registry-with registry)
            row {:id 1 :group_id 1 :mood "sad" :mail "a@b" :nick "n" :age 30 :title "t" :born (java.time.LocalDate/now)
                 :closed_at (java.time.Instant/now) :created_at (java.time.Instant/now) :seq 1 :full_name nil :score 1 :total 2
                 :price 1M :since (java.time.LocalDate/now) :flag true}]
        (is (m/validate :pg.public/users row {:registry reg}))
        (is (not (m/validate :pg.public/users (assoc row :title nil) {:registry reg})) "NOT NULL holds")))))

(deftest only-rows-and-types-are-emitted
  (is (= [:pg.public/groups :pg.public/mood :pg.public/users]
         (keys (:registry (r/rendered facts))))))

(deftest unsupported-vocabulary-stays-unrendered
  (let [{:keys [unrendered]} (r/rendered (p/facts {:name "public" :types {}
                                                   :tables {"t" {:columns [{:name "g" :position 1 :data_type "geometry" :type_schema "public" :is_nullable true}]
                                                                 :constraints {}}}}))]
    (is (= [:unknown-type] (map :fact unrendered)))))

(deftest a-domain-is-no-entry-its-columns-carry-its-name
  (let [{:keys [registry unrendered]}
        (r/rendered (p/facts {:name "public" :types {}
                              :tables {"t" {:columns [{:name "even" :position 1 :data_type "integer" :domain "even_int" :type_schema "pg_catalog" :is_nullable true}
                                                      {:name "label" :position 2 :data_type "text" :domain "mandatory" :type_schema "pg_catalog" :is_nullable false :default_value "'n/a'::text"}]
                                            :constraints {}}}}))]
    (is (= [:pg.public/t] (keys registry)))
    (is (= [:maybe [:pg/integer {:pg/type "integer" :pg/domain "even_int"}]] (get-in registry [:pg.public/t 2 1])))
    (is (= [:string {:pg/type "text" :pg/domain "mandatory" :pg/default "n/a" :default "n/a"}] (get-in registry [:pg.public/t 3 1])))
    (is (= [] unrendered))))

(deftest numeric-and-key-shapes
  (let [{:keys [registry]} (r/rendered (p/facts {:name "public" :types {}
                                                 :tables {"t" {:columns [{:name "a" :position 1 :data_type "numeric" :is_nullable false}
                                                                         {:name "b" :position 2 :data_type "numeric" :is_nullable false}
                                                                         {:name "c" :position 3 :data_type "smallint" :is_nullable false}
                                                                         {:name "d" :position 4 :data_type "text" :is_nullable true}
                                                                         {:name "e" :position 5 :data_type "integer" :is_nullable true}]
                                                               :constraints {"t_d_key" {:name "t_d_key" :type "UNIQUE" :columns ["d"] :nulls_not_distinct true}
                                                                             "t_e_fkey" {:name "t_e_fkey" :type "FOREIGN KEY" :columns ["e"]
                                                                                         :references {:match "FULL" :schema "public" :table "u" :columns ["id"]}}}}}}))
        t (:pg.public/t registry)]
    (is (= {:pg/table "public.t" :pg/unique [{:columns ["d"] :nulls-distinct false}]
            :pg/foreign-keys [{:columns ["e"] :table "public.u" :to ["id"] :match :full}]}
           (second t)))
    (is (= ['decimal? {:pg/type "numeric"}] (get-in t [2 1])))
    (is (= ['decimal? {:pg/type "numeric"}] (get-in t [3 1])))
    (is (= [:pg/smallint {:pg/type "smallint"}] (get-in t [4 1])))
    (let [reg (registry-with registry)]
      (is (m/validate :pg.public/t {:a 1.5M :b 1M :c 32767 :d nil :e nil} {:registry reg}))
      (is (not (m/validate :pg.public/t {:a 1.5M :b 1M :c 32768 :d nil :e nil} {:registry reg})) "smallint keeps its range")
      (is (not (m/validate :pg.public/t {:a 1.5M :b 1M :c 1 :d nil :e 2147483648} {:registry reg})) "so does integer")
      (is (every? #(<= -32768 (:c %) 32767) (mg/sample :pg.public/t {:registry (reg/registry {:database-version "x" :registry registry}) :size 20}))
          "and generates within it"))))

(deftest views
  (let [{:keys [registry]} (r/rendered (p/facts {:name "public" :types {}
                                                 :tables {"v" {:kind "VIEW" :columns [{:name "id" :position 1 :data_type "integer" :is_nullable true}
                                                                                      {:name "name" :position 2 :data_type "text" :is_nullable true}]
                                                               :constraints {}}
                                                          "mv" {:kind "MATERIALIZED VIEW" :columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}] :constraints {}}}}))]
    (is (= [:map {:pg/view "public.v"} [:id [:maybe [:pg/integer {:pg/type "integer"}]]] [:name [:maybe [:string {:pg/type "text"}]]]] (:pg.public/v registry)))
    (is (= [:map {:pg/view "public.mv" :pg/materialized true} [:id [:maybe [:pg/integer {:pg/type "integer"}]]]] (:pg.public/mv registry))
        "every column of a view may be NULL, whatever the catalog says")))

(deftest odd-identifiers
  (let [{:keys [registry]} (r/rendered (p/facts {:name "public" :types {}
                                                 :tables {"Order Items" {:columns [{:name "line no" :position 1 :data_type "integer" :is_nullable false}] :constraints {}}}}))]
    (is (= [:map {:pg/table "public.Order Items"} ["line no" [:pg/integer {:pg/type "integer"}]]] (get registry "pg.public/Order Items"))))
  (is (= ["pg.odd schema/t"] (keys (:registry (r/rendered (p/facts {:name "odd schema" :types {} :tables {"t" {:columns [{:name "a" :position 1 :data_type "text" :is_nullable true}] :constraints {}}}})))))
      "a schema name that is not a plain identifier makes string keys too"))

(deftest deterministic
  (is (= (r/rendered facts) (r/rendered (shuffle facts)))))

(deftest a-generated-range-column-is-opaque
  (let [{:keys [registry unrendered]} (r/rendered (p/facts {:name "public" :types {}
                                                            :tables {"t" {:columns [{:name "valid_from" :position 1 :data_type "timestamptz" :is_nullable false}
                                                                                    {:name "valid_until" :position 2 :data_type "timestamptz" :is_nullable true}
                                                                                    {:name "validity" :position 3 :data_type "tstzrange" :is_nullable true
                                                                                     :generated_expr "tstzrange(valid_from, valid_until)"}]
                                                                          :constraints {}}}}))]
    (is (= [:maybe [:any {:pg/type "tstzrange" :pg/generated {:sql "tstzrange(valid_from, valid_until)"}}]] (last (get-in registry [:pg.public/t 4]))) ":any with its type")
    (is (empty? unrendered))))

(deftest not-null-of-an-opaque-type-rejects-nil
  (let [{:keys [registry]} (r/rendered (p/facts {:name "public" :types {}
                                                 :tables {"t" {:columns [{:name "a" :position 1 :data_type "jsonb" :is_nullable false}
                                                                         {:name "b" :position 2 :data_type "jsonb" :is_nullable true}]
                                                               :constraints {}}}}))
        col (fn [k] (last (some #(when (= k (first %)) %) (drop 2 (:pg.public/t registry)))))]
    (is (= [:some {:pg/type "jsonb"}] (col :a)) "NOT NULL, with nothing to shape it: anything but nil")
    (is (= [:maybe [:any {:pg/type "jsonb"}]] (col :b)))))

(deftest types-the-regression-suite-uses
  (let [{:keys [registry unrendered]} (r/rendered (p/facts {:name "public" :types {}
                                                            :tables {"t" {:columns [{:name "o" :position 1 :data_type "oid" :is_nullable false}
                                                                                    {:name "c" :position 2 :data_type "\"char\"" :is_nullable false}
                                                                                    {:name "b" :position 3 :data_type "bit" :is_nullable false :max_length 4}
                                                                                    {:name "vb" :position 4 :data_type "bit varying" :is_nullable false :max_length 6}
                                                                                    {:name "ip" :position 5 :data_type "inet" :is_nullable false}
                                                                                    {:name "tags" :position 6 :data_type "character varying[]" :is_nullable false :max_length 5}]
                                                                          :constraints {}}}}))
        reg (registry-with registry)
        col (fn [k] (last (some #(when (= k (first %)) %) (drop 2 (:pg.public/t registry)))))]
    (is (= [:int {:min 0 :max 4294967295 :pg/type "oid"}] (col :o)))
    (is (nil? (some #(= :xid (last (last %))) (drop 2 (:pg.public/t registry)))) "xid is not an integer to the database")
    (is (= [:string {:pg/one-byte true :pg/type "\"char\""}] (col :c)))
    (is (= [:string {:pg/type "bit" :pg/max-length 4}] (col :b)) "0s and 1s; bit(n)'s n is the database's")
    (is (= [:string {:pg/type "bit varying" :pg/max-length 6}] (col :vb)))
    (is (= [:some {:pg/type "inet"}] (col :ip)) "a type the driver hands over as its own object")
    (is (= [:vector {:pg/type "character varying[]" :pg/max-length 5} :string] (col :tags)) "varchar(5)[]'s length a property, which no schema checks")
    (is (empty? unrendered) "none of them is unknown")
    (is (m/validate :pg.public/t {:o 1 :c "x" :b "0101" :vb "01" :ip "10.0.0.1" :tags ["abcdef"]} {:registry reg}))
    (is (not (m/validate :pg.public/t {:o 1 :c "x" :b "0102" :vb "01" :ip "10.0.0.1" :tags []} {:registry reg})) "a bit string holds 0s and 1s alone")))

(deftest a-not-valid-foreign-key-is-noted
  (let [{:keys [diagnostics]} (r/rendered (p/facts {:name "public" :types {}
                                                    :tables {"p" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}]
                                                                  :constraints {"p_pkey" {:name "p_pkey" :type "PRIMARY KEY" :columns ["id"]}}}
                                                             "t" {:columns [{:name "n" :position 1 :data_type "integer" :is_nullable false}
                                                                            {:name "p_id" :position 2 :data_type "integer" :is_nullable true}]
                                                                  :constraints {"t_p_id_fkey" {:name "t_p_id_fkey" :type "FOREIGN KEY" :columns ["p_id"] :is_valid false
                                                                                               :references {:schema "public" :table "p" :columns ["id"]}}}}}}))]
    (is (= [[:not-valid "t_p_id_fkey"]] (map (juxt :kind :constraint) diagnostics)))))

(deftest a-not-enforced-constraint-is-noted-not-applied
  (let [{:keys [registry unrendered diagnostics]} (r/rendered (p/facts {:name "public" :types {}
                                                                        :tables {"p" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}]
                                                                                      :constraints {"p_pkey" {:name "p_pkey" :type "PRIMARY KEY" :columns ["id"]}}}
                                                                                 "t" {:columns [{:name "b" :position 1 :data_type "integer" :is_nullable false}
                                                                                                {:name "p_id" :position 2 :data_type "integer" :is_nullable true}]
                                                                                      :constraints {"t_p_id_fkey" {:name "t_p_id_fkey" :type "FOREIGN KEY" :columns ["p_id"] :is_enforced false
                                                                                                                   :references {:schema "public" :table "p" :columns ["id"]}}}}}}))]
    (is (= [:map {:pg/table "public.t"} [:b [:pg/integer {:pg/type "integer"}]] [:p_id [:maybe [:pg/integer {:pg/type "integer"}]]]] (:pg.public/t registry))
        "the foreign key is not applied: the database never checks it")
    (is (empty? unrendered))
    (is (= #{["t_p_id_fkey" :not-enforced]} (set (map (juxt :constraint :kind) diagnostics))))))

(deftest a-row-trigger-is-noted
  (let [{:keys [diagnostics]} (r/rendered (p/facts {:name "public" :types {}
                                                    :tables {"t" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}]
                                                                  :constraints {}
                                                                  :triggers [{:name "t_audit" :insert true :update true :delete false}
                                                                             {:name "t_on_delete" :insert false :update false :delete true}]}}}))]
    (is (= [{:kind :row-trigger :trigger "t_audit" :table "public.t"}] (map #(select-keys % [:kind :trigger :table]) diagnostics))
        "the INSERT trigger is noted, a DELETE one is not")))

(deftest a-before-row-trigger-is-on-the-table
  (let [{:keys [registry]} (r/rendered (p/facts {:name "public" :types {}
                                                 :tables {"t" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}]
                                                               :constraints {}
                                                               :triggers [{:name "fill" :before true :insert true :update false :delete false}
                                                                          {:name "audit" :before false :insert true :update true :delete false}
                                                                          {:name "touch" :before true :insert false :update true :delete false}]}}}))]
    (is (= {:insert ["fill"] :update ["touch"]} (:pg/before-triggers (second (:pg.public/t registry))))
        "a BEFORE trigger may fill a row before NOT NULL is checked; an AFTER one cannot")))

(deftest a-domain-column-that-is-not-null-rejects-null
  (let [{:keys [registry]} (r/rendered (p/facts {:name "public"
                                                 :types {}
                                                 :tables {"t" {:columns [{:name "email" :position 1 :data_type "text" :domain "email" :is_nullable false}
                                                                         {:name "price" :position 2 :data_type "numeric" :domain "money_amount" :is_nullable false}
                                                                         {:name "nick" :position 3 :data_type "character varying" :domain "short_name" :max_length 8 :is_nullable true}
                                                                         {:name "total" :position 4 :data_type "numeric" :is_nullable true :generated_expr "(price * (2)::numeric)"}]
                                                               :constraints {"t_email_key" {:name "t_email_key" :type "UNIQUE" :columns ["email"]}
                                                                             "t_nick_idx" {:name "t_nick_idx" :type "UNIQUE" :columns ["nick"] :index true}}}}}))
        reg (registry-with registry)]
    (is (= [:string {:pg/type "text" :pg/domain "email"}] (get-in registry [:pg.public/t 2 1])) "NOT NULL: no [:maybe]")
    (is (not (m/validate :pg.public/t {:email nil :price 1M :nick nil :total nil} {:registry reg})) "and NULL is rejected")
    (is (m/validate :pg.public/t {:email "nope" :price -1M :nick nil :total nil} {:registry reg}) "the domains' CHECKs are the database's")
    (is (= 8 (:pg/max-length (second (second (last (get-in registry [:pg.public/t 3]))))))  "a varchar(8) domain's length a property")
    (is (= {:sql "(price * (2)::numeric)"} (:pg/generated (second (last (last (get-in registry [:pg.public/t 5])))))) "a generated column carries its expression")
    (is (= [{:columns ["email"]} {:columns ["nick"] :index true}] (:pg/unique (second (:pg.public/t registry)))) "a unique index is marked")))

(defn- shaped [rendered shapes] (#'pgmalli.impl.registry/shaped (:registry rendered) shapes))

(deftest a-shape-keyed-by-column
  (let [registry (shaped (r/rendered (p/facts {:name "public" :types {"mood" {:kind "ENUM" :enum_values ["a" "b"]}}
                                                            :tables {"jobs" {:columns [{:name "input" :position 1 :data_type "jsonb" :is_nullable false}
                                                                                       {:name "meta" :position 2 :data_type "jsonb" :is_nullable true}
                                                                                       {:name "n" :position 3 :data_type "integer" :is_nullable false}
                                                                                       {:name "m" :position 4 :data_type "mood" :type_schema "public" :is_nullable true}
                                                                                       {:name "ip" :position 5 :data_type "inet" :is_nullable true}
                                                                                       {:name "g" :position 6 :data_type "geometry" :type_schema "public" :is_nullable true}]
                                                                             :constraints {}}}}))
                                                  {:public.jobs/input [:map [:prompt :string]]
                                                   :public.jobs/n [:int {:min 1}]
                                                   :public.jobs/m [:enum "a"]
                                                   :public.jobs/ip :string
                                                   :public.jobs/g :string})
        column (fn [k] (some #(when (= k (first %)) (last %)) (drop 2 (:pg.public/jobs registry))))]
    (is (= [:map {:pg/type "jsonb"} [:prompt :string]] (column :input)) "the shape is the column's value, its type kept")
    (is (= [:maybe [:any {:pg/type "jsonb"}]] (column :meta)) "another column of the type is as generated")
    (is (= [:int {:min 1 :pg/type "integer"}] (column :n)) "the shape's own bounds are kept")
    (is (= [:maybe [:enum {:pg/type "mood"} "a"]] (column :m)) "it replaces the type's schema, an enum's reference too")
    (is (= [:maybe [:string {:pg/type "inet"}]] (column :ip)) "a type the driver reads as an object of its own too")
    (is (= [:maybe [:string {:pg/type "geometry"}]] (column :g)) "and one of a type pgmalli does not know")))

(deftest a-shaped-column-with-a-default-may-be-left-out-of-an-insert
  (let [registry (shaped (r/rendered (p/facts {:name "public" :types {}
                                               :tables {"images" {:columns [{:name "variants" :position 1 :data_type "jsonb" :is_nullable false
                                                                             :default_value "'{}'::jsonb"}]}}}))
                         {:public.images/variants [:ref :app/variants]})
        [_ _ s] (shape/entry-parts (first (drop 2 (:pg.public/images registry))))]
    (is (contains? (shape/column-props s) :pg/default))
    (is (not (contains? (shape/column-props s) :default))
        "the SQL literal is no value of the declared schema: no malli :default, which would keep the column required")))

(deftest a-table-others-inherit-says-so
  (let [table {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}] :constraints {}}
        {:keys [registry]} (r/rendered (p/facts {:name "public" :types {} :tables {"par" (assoc table :inherited true) "solo" table}}))]
    (is (true? (:pg/inherited (second (:pg.public/par registry)))))
    (is (not (contains? (second (:pg.public/solo registry)) :pg/inherited)))))

(deftest a-key-checked-at-commit-or-an-invalid-index-says-so
  (let [table {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}
                         {:name "k" :position 2 :data_type "integer" :is_nullable false}]
               :constraints {"t_pkey" {:name "t_pkey" :type "PRIMARY KEY" :columns ["id"] :deferrable true}
                             "t_k_key" {:name "t_k_key" :type "UNIQUE" :columns ["k"] :deferrable true}
                             "t_bad (index)" {:name "t_bad" :type "UNIQUE" :columns ["k" "id"] :index true :is_valid false}}}
        props (second (:pg.public/t (:registry (r/rendered (p/facts {:name "public" :types {} :tables {"t" table}})))))]
    (is (true? (:pg/primary-key-deferrable props)))
    (is (= [{:columns ["k"] :deferrable true}] (:pg/unique props)) "an invalid unique index holds nothing")
    (is (= {:pg/table "public.t"}
           (second (:pg.public/t (:registry (r/rendered (p/facts {:name "public" :types {}
                                                                   :tables {"t" (assoc table :constraints {"t_pkey" {:name "t_pkey" :type "PRIMARY KEY" :columns ["id"] :is_valid false}
                                                                                                           "t_k_key" {:name "t_k_key" :type "UNIQUE" :columns ["k"] :is_valid false}})}}))))))
        "nor a key whose index is")))

(deftest docs-types-tables-of-column-types-and-extensions-are-types-types
  (let [doc (slurp "docs/types.md")
        section #(second (re-find (re-pattern (str "(?s)\\n## " % "\\n(.*?)\\n## ")) doc))
        code #(map second (re-seq #"`([^`]+)`" %))
        ;; [:any {:pg/type "..."}] is :any of the type named
        schema #(let [x (edn/read-string %)] (if (and (vector? x) (= [:pg/type] (keys (second x)))) (first x) x))
        rows (for [[_ l r] (re-seq #"(?m)^\| (.*?) \| (.*?) \|$" (section "Column types"))
                   :let [es (keep types/entry (code l))
                         ss (map schema (code (first (str/split r #" \("))))]
                   :when (seq es)]
               [es ss])]
    (is (< 10 (count rows)))
    (doseq [[es ss] rows, [e s] (map vector es (if (= 1 (count ss)) (repeat (first ss)) ss))]
      (is (= (:schema e) s) (str (:name e) " is " (pr-str (:schema e)) " in types/types, " (pr-str s) " in docs/types.md")))
    (is (= #{} (set (for [[n e] types/types :when (not= :any (:schema e)) :when (not-any? #(= n (:name %)) (mapcat first rows))] n)))
        "every type of a schema of its own has a row")
    (is (= (set (keep :extension (vals types/types)))
           (set (code (str/replace (second (re-find #"(?s)extension is in:\s(.*?)\.\s" (section "Extensions"))) #"\(`[^`]*`\)" ""))))
        "the extensions docs/types.md lists")))

(deftest a-shape-of-a-registrys-name-is-a-reference-to-it
  (let [registry (shaped (r/rendered (p/facts {:name "public" :types {}
                                               :tables {"t" {:columns [{:name "b" :position 1 :data_type "text" :is_nullable false}
                                                                       {:name "c" :position 2 :data_type "text" :is_nullable false}]
                                                             :constraints {}}}}))
                         {:public.t/b :my.app/code :public.t/c :time/instant})
        column (fn [k] (some #(when (= k (first %)) (last %)) (drop 2 (:pg.public/t registry))))]
    (is (= [:ref {:pg/type "text"} :my.app/code] (column :b)) "[k {...}] of a name is no schema malli builds")
    (is (= [:time/instant {:pg/type "text"}] (column :c)) "a type of malli's or pgmalli's own takes properties as it is")))
