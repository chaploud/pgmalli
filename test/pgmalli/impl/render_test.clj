(ns pgmalli.impl.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.experimental.time :as time]
            [malli.generator :as mg]
            [malli.util :as mu]
            [pgmalli.impl.pattern :as p]
            [pgmalli.impl.render :as r]
            [pgmalli.impl.runtime :as rt]))

(def ^:private schema
  {:name "public"
   :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}
           "email" {:kind "DOMAIN" :base_type "text" :not_null false :constraints [{:name "email_check" :definition "CHECK (VALUE ~ '@'::text)"}]}}
   :tables {"groups" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}]
                      :constraints {"groups_pkey" {:name "groups_pkey" :type "PRIMARY KEY" :columns ["id"]}}}
            "users" {:columns [{:name "id" :position 1 :data_type "bigint" :is_nullable false :identity "ALWAYS"}
                               {:name "group_id" :position 2 :data_type "integer" :is_nullable false}
                               {:name "mood" :position 3 :data_type "mood" :type_schema "public" :is_nullable false :default_value "'happy'::mood"}
                               {:name "mail" :position 4 :data_type "email" :type_schema "public" :is_nullable true}
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
                                                          :references {:schema "public" :table "groups" :columns ["id"]}}
                                   "age_check" {:name "age_check" :type "CHECK" :check_clause "CHECK (age IS NULL OR age >= 0 AND age <= 150)"}
                                   "title_check" {:name "title_check" :type "CHECK" :check_clause "CHECK (length(TRIM(BOTH FROM title)) > 0)"}
                                   "closed_check" {:name "closed_check" :type "CHECK"
                                                   :check_clause "CHECK (mood = 'sad'::mood AND closed_at IS NOT NULL OR mood = 'happy'::mood AND closed_at IS NULL)"}
                                   "score_check" {:name "score_check" :type "CHECK" :check_clause "CHECK (score <= total)"}}}}})

(def ^:private facts (p/facts schema))

(defn- registry-with [r] (merge (m/default-schemas) (mu/schemas) (time/schemas) {:pg/check rt/check-schema :pg/check-value rt/check-value-schema :pg/bytes rt/bytes-schema} r))

(deftest row-schema
  (let [{:keys [registry unrendered]} (r/registry facts)
        users (:pg.public/users registry)]
    (is (= [:enum "happy" "sad"] (:pg.public/mood registry)))
    (is (= [:maybe [:and :string [:re "@"]]] (:pg.public/email registry)) "domain = base type + its CHECK")
    (is (= :and (first users)) "columns, then the table constraints")
    (is (= [:map {:pg/table "public.users" :pg/primary-key ["id"] :pg/unique [{:columns ["nick"]}] :pg/foreign-keys [{:columns ["group_id"] :table "public.groups" :to ["id"]}]}
            [:age [:maybe [:int {:min 0 :max 150 :pg/type "integer" :pg/constraint ["age_check"]}]]]
            [:born [:maybe [:time/local-date {:pg/type "date"}]]]
            [:closed_at [:maybe [:time/instant {:pg/type "timestamptz"}]]]
            [:created_at [:time/instant {:pg/type "timestamptz" :pg/default [:now]}]]
            [:flag [:boolean {:pg/type "boolean" :pg/default false :default false}]]
            [:full_name [:maybe [:string {:pg/type "text" :pg/generated true}]]]
            [:group_id [:int {:min -2147483648 :max 2147483647 :pg/type "integer"}]]
            [:id [:int {:pg/type "bigint" :pg/identity :always}]]
            [:mail [:maybe [:ref {:pg/type "email"} :pg.public/email]]]
            [:mood [:ref {:pg/type "mood" :pg/default "happy" :default "happy"} :pg.public/mood]]
            [:nick [:maybe [:string {:max 40 :pg/type "character varying"}]]]
            [:price ['decimal? {:pg/type "numeric" :pg/default 0 :default 0M}]]
            [:score [:int {:min -2147483648 :max 2147483647 :pg/type "integer"}]]
            [:seq [:int {:min -2147483648 :max 2147483647 :pg/type "integer" :pg/default [:nextval [:cast "users_seq_seq" :regclass]] :pg/identity :serial}]]
            [:since [:time/local-date {:pg/type "date" :pg/default "2020-01-01"}]]
            [:title [:and {:pg/type "text" :pg/constraint ["title_check"]} [:string {:min 1}] [:re "\\S"]]]
            [:total [:int {:min -2147483648 :max 2147483647 :pg/type "integer"}]]]
           (second users)))
    (is (= [:multi {:dispatch :mood :error/message "closed_check"}
            ["sad" [:map [:closed_at [:time/instant {:error/message "closed_check"}]]]]
            ["happy" [:map [:closed_at [:nil {:error/message "closed_check"}]]]]
            [:malli.core/default :any]]
           (nth users 2)) "a branch check becomes :multi")
    (is (empty? unrendered))
    (is (= [:pg/check {:pg/constraint "score_check" :error/message "score_check"} [:<= :score :total]] (nth users 3))
        "a column comparison is a :pg/check over the expression data")
    (testing "validation and generation through malli"
      (let [reg (registry-with registry)
            row {:id 1 :group_id 1 :mood "sad" :mail "a@b" :nick "n" :age 30 :title "t" :born (java.time.LocalDate/now)
                 :closed_at (java.time.Instant/now) :created_at (java.time.Instant/now) :seq 1 :full_name nil :score 1 :total 2
                 :price 1M :since (java.time.LocalDate/now) :flag true}]
        (is (m/validate :pg.public/users row {:registry reg}))
        (is (not (m/validate :pg.public/users (assoc row :closed_at nil) {:registry reg})) "sad needs closed_at")
        (is (not (m/validate :pg.public/users (assoc row :title "  ") {:registry reg})) "trimmed non-blank")
        (is (not (m/validate :pg.public/users (assoc row :mail "nope") {:registry reg})) "domain check")
        (is (not (m/validate :pg.public/users (assoc row :score 3) {:registry reg})) ":pg/check score <= total")))))

(deftest only-rows-and-types-are-emitted
  (is (= [:pg.public/email :pg.public/groups :pg.public/mood :pg.public/users]
         (keys (:registry (r/registry facts))))))

(deftest unsupported-vocabulary-stays-unrendered
  (let [{:keys [unrendered]} (r/registry (p/facts {:name "public" :types {}
                                                   :tables {"t" {:columns [{:name "g" :position 1 :data_type "geometry" :type_schema "public" :is_nullable true}]
                                                                 :constraints {"k" {:name "k" :type "CHECK" :check_clause "CHECK (st_area(g) > 0)"}}}}}))]
    (is (= [:unknown-type :table-check] (map :fact unrendered)))))

(deftest overrides
  (let [{:keys [registry unrendered skipped]} (r/registry facts {"score_check" [:ref :app/score-within-total]
                                                                   "title_check" {:skip "guaranteed by the application"}})]
    (is (empty? unrendered))
    (is (= ["title_check"] (map :constraint skipped)))
    (is (some #{[:ref :app/score-within-total]} (:pg.public/users registry)) "the override replaces the :pg/check")))

(deftest not-valid-checks-are-reported-not-enforced
  (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {}
                                                            :tables {"t" {:columns [{:name "a" :position 1 :data_type "integer" :is_nullable false}
                                                                                    {:name "b" :position 2 :data_type "integer" :is_nullable false}]
                                                                          :constraints {"k" {:name "k" :type "CHECK" :check_clause "CHECK (a <= b)" :is_valid false}}}}}))]
    (is (= [:map {:pg/table "public.t"} [:a [:int {:min -2147483648 :max 2147483647 :pg/type "integer"}]] [:b [:int {:min -2147483648 :max 2147483647 :pg/type "integer"}]]] (:pg.public/t registry)))
    (is (= [{:fact :table-check :valid? false}] (map #(select-keys % [:fact :valid?]) unrendered)))))

(deftest checks-that-lose-a-fact-are-evaluated-whole
  (let [table (fn [& checks] {:name "public" :types {}
                              :tables {"t" {:columns [{:name "kind" :position 1 :data_type "text" :is_nullable false}
                                                      {:name "tenant" :position 2 :data_type "uuid" :is_nullable true}
                                                      {:name "since" :position 3 :data_type "date" :is_nullable true}]
                                            :constraints (into {} (map-indexed (fn [i c] [(str "k" i) {:name (str "k" i) :type "CHECK" :check_clause c}]) checks))}}})
        {:keys [registry unrendered]} (r/registry (p/facts (table "CHECK (kind = 'a'::text AND tenant = '1f9d0c7e-2a1b-4c3d-8e5f-6a7b8c9d0e1f'::uuid OR kind = 'b'::text AND tenant IS NULL)"
                                                                  "CHECK (kind = 'a'::text AND since = '2020-01-01'::date OR kind = 'b'::text AND since IS NULL)"
                                                                  "CHECK (since >= '2019-01-01'::date)")))]
    (is (= [:multi {:dispatch :kind :error/message "k0"}
            ["a" [:map [:tenant [:enum {:error/message "k0"} #uuid "1f9d0c7e-2a1b-4c3d-8e5f-6a7b8c9d0e1f"]]]]
            ["b" [:map [:tenant [:nil {:error/message "k0"}]]]]
            [:malli.core/default :any]]
           (nth (:pg.public/t registry) 2))
        "a uuid value set is an enum of uuids")
    (is (= [:pg/check {:pg/constraint "k1" :error/message "k1"}
            [:or [:and [:= :kind [:cast "a" :text]] [:= :since [:cast "2020-01-01" :date]]] [:and [:= :kind [:cast "b" :text]] [:is :since nil]]]]
           (nth (:pg.public/t registry) 3))
        "a branch whose fact has no rendering is evaluated whole")
    (is (= [:pg/check {:pg/constraint "k2" :error/message "k2"} [:>= :since [:cast "2019-01-01" :date]]]
           (nth (:pg.public/t registry) 4))
        "so is a column pattern that has none")
    (is (= [:maybe [:time/local-date {:pg/type "date"}]] (get-in registry [:pg.public/t 1 3 1]))
        "and the column keeps its bare type")
    (is (empty? unrendered))))

(deftest constraints-are-conjoined
  (let [t (fn [cols & checks] {:name "public" :types {"mood" {:kind "ENUM" :enum_values ["a" "b" "c"]}}
                               :tables {"t" {:columns cols
                                             :constraints (into {} (map-indexed (fn [i c] [(str "k" i) {:name (str "k" i) :type "CHECK" :check_clause (str "CHECK (" c ")")}]) checks))}}})
        column (fn [reg col] (some (fn [[k s]] (when (= k col) s)) (drop 2 (let [r (:pg.public/t reg)] (if (= :and (first r)) (second r) r)))))
        reg (fn [& args] (:registry (r/registry (p/facts (apply t args)))))]
    (is (= [:int {:min 10 :max 2147483647 :pg/type "integer" :pg/constraint ["k0" "k1"]}]
           (column (reg [{:name "n" :position 1 :data_type "integer" :is_nullable false}] "n >= 10" "n >= 0") :n))
        "the tighter bound wins")
    (is (= [:string {:max 5 :pg/type "character varying" :pg/constraint ["k0"]}]
           (column (reg [{:name "s" :position 1 :data_type "character varying" :is_nullable false :max_length 5}] "length(s) <= 10") :s))
        "a CHECK cannot widen varchar(n)")
    (is (= [:enum {:pg/type "text" :pg/constraint ["k0" "k1"]} "b"]
           (column (reg [{:name "e" :position 1 :data_type "text" :is_nullable false}] "e IN ('a'::text, 'b'::text)" "e IN ('b'::text, 'c'::text)") :e))
        "value sets intersect")
    (is (= [:enum {:pg/type "text" :pg/constraint ["k0" "k1"]} "a"]
           (column (reg [{:name "e" :position 1 :data_type "text" :is_nullable false}] "e IN ('a'::text, 'b'::text)" "e <> 'b'::text") :e))
        "and exclude")
    (is (= [:and {:pg/type "text" :pg/constraint ["k0"]} :string [:not [:enum "x" "y"]]]
           (column (reg [{:name "e" :position 1 :data_type "text" :is_nullable false}] "e <> ALL (ARRAY['x'::text, 'y'::text])") :e)))
    (is (= [:and {:pg/type "text" :pg/constraint ["k0"]} :string [:re "^\\Qab\\E.*$"]]
           (column (reg [{:name "e" :position 1 :data_type "text" :is_nullable false}] "e ~~ 'ab%'::text") :e))
        "LIKE is a regex")
    (is (= [:and {:pg/type "text" :pg/constraint ["k0" "k1"]} [:string {:min 2}] [:re "\\S"]]
           (column (reg [{:name "e" :position 1 :data_type "text" :is_nullable false}] "length(btrim(e)) > 0" "length(e) >= 2") :e))
        "a bound after an [:and ...] lands on the type, where malli reads it")
    (is (= [:enum {:pg/type "boolean" :pg/constraint ["k0"]} true]
           (column (reg [{:name "b" :position 1 :data_type "boolean" :is_nullable false}] "b = true") :b)))
    (is (= [:vector {:min 1 :max 3 :pg/type "text[]" :pg/constraint ["k0"]} :string]
           (column (reg [{:name "tags" :position 1 :data_type "text[]" :is_nullable false}] "cardinality(tags) BETWEEN 1 AND 3") :tags)))
    (is (= [:vector {:max 3 :pg/type "text[]" :pg/constraint ["k0"]} :string]
           (column (reg [{:name "tags" :position 1 :data_type "text[]" :is_nullable false}] "array_length(tags, 1) <= 3") :tags)))
    (is (= [:pg/check {:pg/constraint "k0" :error/message "k0"} [:= [:array_length :tags 1] 3]]
           (nth (:pg.public/t (reg [{:name "tags" :position 1 :data_type "text[]" :is_nullable false}] "array_length(tags, 1) = 3")) 2))
        "array_length of an empty array is NULL, so an exact length is evaluated whole")))

(deftest domains
  (let [{:keys [registry unrendered]}
        (r/registry (p/facts {:name "public"
                              :types {"even_int" {:kind "DOMAIN" :base_type "integer" :not_null false
                                                  :constraints [{:name "even_int_check" :definition "CHECK ((VALUE % 2) = 0)"}]}
                                      "mandatory" {:kind "DOMAIN" :base_type "text" :not_null true :default "'n/a'::text" :constraints []}
                                      "opaque" {:kind "DOMAIN" :base_type "text" :not_null false
                                                :constraints [{:name "opaque_check" :definition "CHECK (validate(VALUE))"}]}}
                              :tables {"t" {:columns [{:name "even" :position 1 :data_type "even_int" :type_schema "public" :is_nullable true}
                                                      {:name "label" :position 2 :data_type "mandatory" :type_schema "public" :is_nullable true}
                                                      {:name "code" :position 3 :data_type "opaque" :type_schema "public" :is_nullable true}]
                                            :constraints {}}}}))
        reg (registry-with registry)]
    (is (= [:maybe [:and [:int {:min -2147483648 :max 2147483647}] [:pg/check-value {:pg/constraint "even_int_check" :error/message "even_int_check"} [:= [:mod :VALUE 2] 0]]]]
           (:pg.public/even_int registry))
        "a domain CHECK outside the patterns is evaluated over the value")
    (is (m/validate :pg.public/even_int 4 {:registry reg}))
    (is (not (m/validate :pg.public/even_int 3 {:registry reg})))
    (is (= :string (:pg.public/mandatory registry)) "NOT NULL domains are not [:maybe]")
    (is (= [:ref {:pg/type "mandatory" :pg/default "n/a" :default "n/a"} :pg.public/mandatory] (get-in registry [:pg.public/t 4 1]))
        "the domain's NOT NULL and DEFAULT reach its columns")
    (is (= [:maybe :string] (:pg.public/opaque registry)) "a domain whose CHECK cannot be evaluated still exists")
    (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {"pos" {:kind "DOMAIN" :base_type "integer" :not_null false
                                                                                            :constraints [{:name "pos_check" :definition "CHECK (VALUE > 0) NOT VALID" :is_valid false}]}}
                                                              :tables {}}))]
      (is (= [:maybe [:int {:min -2147483648 :max 2147483647}]] (:pg.public/pos registry)) "a NOT VALID domain CHECK is not applied")
      (is (= [{:fact :domain-check :valid? false}] (map #(select-keys % [:fact :valid?]) unrendered))))
    (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {"d" {:kind "DOMAIN" :base_type "date" :not_null false
                                                                                          :constraints [{:name "d_check" :definition "CHECK (VALUE >= '2020-01-01'::date)"}]}}
                                                              :tables {}}))]
      (is (= [:maybe [:and :time/local-date [:pg/check-value {:pg/constraint "d_check" :error/message "d_check"} [:>= :VALUE [:cast "2020-01-01" :date]]]]]
             (:pg.public/d registry))
          "a domain CHECK that loses its fact is evaluated whole too")
      (is (empty? unrendered)))
    (is (= [{:fact :domain-check :type-name "opaque" :constraint "opaque_check"}] (map #(select-keys % [:fact :type-name :constraint]) unrendered)))
    (is (= [:maybe [:and :string [:re "x"]]]
           (:pg.public/opaque (:registry (r/registry (p/facts {:name "public" :types {"opaque" {:kind "DOMAIN" :base_type "text" :not_null false
                                                                                                    :constraints [{:name "opaque_check" :definition "CHECK (validate(VALUE))"}]}}
                                                               :tables {}})
                                                     {"opaque_check" [:re "x"]}))))
        "overrides apply to domain CHECKs too")))

(deftest numeric-and-key-shapes
  (let [{:keys [registry]} (r/registry (p/facts {:name "public" :types {}
                                                 :tables {"t" {:columns [{:name "a" :position 1 :data_type "numeric" :is_nullable false :precision 3 :scale 1}
                                                                         {:name "b" :position 2 :data_type "numeric" :is_nullable false :precision 4}
                                                                         {:name "c" :position 3 :data_type "smallint" :is_nullable false}
                                                                         {:name "d" :position 4 :data_type "text" :is_nullable true}
                                                                         {:name "e" :position 5 :data_type "integer" :is_nullable true}]
                                                               :constraints {"t_d_key" {:name "t_d_key" :type "UNIQUE" :columns ["d"] :nulls_not_distinct true}
                                                                             "t_e_fkey" {:name "t_e_fkey" :type "FOREIGN KEY" :columns ["e"]
                                                                                         :references {:match "FULL" :schema "public" :table "u" :columns ["id"]}}
                                                                             "a_check" {:name "a_check" :type "CHECK" :check_clause "CHECK (a >= 0.5)"}}}}}))
        t (:pg.public/t registry)]
    (is (= {:pg/table "public.t" :pg/unique [{:columns ["d"] :nulls-distinct false}]
            :pg/foreign-keys [{:columns ["e"] :table "public.u" :to ["id"] :match :full}]}
           (second t)))
    (is (= [:and {:pg/type "numeric" :pg/constraint ["a_check"]} 'decimal? [:> -100M] [:< 100M] [:>= 0.5]] (get-in t [2 1]))
        "numeric(3,1) holds |v| < 100, and a CHECK narrows further")
    (is (= [:and {:pg/type "numeric"} 'decimal? [:> -10000M] [:< 10000M]] (get-in t [3 1])))
    (is (= [:int {:min -32768 :max 32767 :pg/type "smallint"}] (get-in t [4 1]))))
  (let [{:keys [registry]} (r/registry (p/facts {:name "public" :types {}
                                                 :tables {"t" {:columns [{:name "digest" :position 1 :data_type "bytea" :is_nullable false}]
                                                               :constraints {"digest_check" {:name "digest_check" :type "CHECK" :check_clause "CHECK (octet_length(digest) = 32)"}}}}}))
        reg (registry-with registry)]
    (is (= [:pg/bytes {:min 32 :max 32 :pg/type "bytea" :pg/constraint ["digest_check"]}] (get-in registry [:pg.public/t 2 1])))
    (is (m/validate :pg.public/t {:digest (byte-array 32)} {:registry reg}))
    (is (not (m/validate :pg.public/t {:digest (byte-array 31)} {:registry reg})))
    (is (every? #(= 32 (alength ^bytes (:digest %))) (mg/sample :pg.public/t {:registry reg :size 10})) "and generates the length")))

(deftest partial-checks-are-never-enforced-partially
  (let [t (fn [& checks] {:name "public" :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}}
                          :tables {"t" {:columns [{:name "nm" :position 1 :data_type "text" :is_nullable false}
                                                  {:name "kind" :position 2 :data_type "text" :is_nullable false}
                                                  {:name "mood" :position 3 :data_type "mood" :type_schema "public" :is_nullable false}
                                                  {:name "score" :position 4 :data_type "integer" :is_nullable false}]
                                        :constraints (into {} (map-indexed (fn [i c] [(str "k" i) {:name (str "k" i) :type "CHECK" :check_clause c}]) checks))}}})
        {:keys [registry unrendered]} (r/registry (p/facts (t "CHECK (length(nm) > 3 AND nm ~ '\\mfoo'::text)"
                                                              "CHECK (kind = 'a'::text AND nm ~ '\\mfoo'::text OR kind = 'b'::text AND nm IS NULL)"
                                                              "CHECK (mood = 'sad'::mood OR score > length(nm))")))
        row (:pg.public/t registry)]
    (is (= [:string {:min 4 :pg/type "text" :pg/constraint ["k0"]}] (get-in row [1 4 1]))
        "a CHECK the evaluator cannot take whole keeps its renderable part")
    (is (= [:pg/check {:pg/constraint "k2" :error/message "k2"} [:or [:= :mood [:cast "sad" :mood]] [:> :score [:length :nm]]]] (nth row 2))
        "an enum literal is a value the evaluator knows")
    (is (= [[:regex "nm" "k0"] [:branch-check nil "k1"]] (map (juxt :fact :column :constraint) unrendered))
        "the lost regex is reported; the branch that lost it is reported whole, not enforced partially")
    (is (every? :expr unrendered))))

(deftest null-branches-and-time-types
  (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {}
                                                            :tables {"t" {:columns [{:name "kind" :position 1 :data_type "text" :is_nullable true}
                                                                                    {:name "note" :position 2 :data_type "text" :is_nullable true}
                                                                                    {:name "at" :position 3 :data_type "time without time zone" :is_nullable true}
                                                                                    {:name "tz" :position 4 :data_type "time with time zone" :is_nullable true}]
                                                                          :constraints {"k" {:name "k" :type "CHECK" :check_clause "CHECK (kind IS NULL AND note IS NULL OR kind = 'a'::text AND note IS NOT NULL)"}}}}}))
        reg (registry-with registry)]
    (is (= [:multi {:dispatch :kind :error/message "k"}
            [nil [:map [:note [:nil {:error/message "k"}]]]]
            ["a" [:map [:note [:string {:error/message "k"}]]]]
            [:malli.core/default :any]]
           (nth (:pg.public/t registry) 2))
        "a branch on the column being NULL dispatches on nil")
    (is (m/validate :pg.public/t {:kind nil :note nil :at nil :tz nil} {:registry reg}))
    (is (not (m/validate :pg.public/t {:kind nil :note "x" :at nil :tz nil} {:registry reg})))
    (is (= [:maybe [:time/local-time {:pg/type "time without time zone"}]] (get-in registry [:pg.public/t 1 2 1])))
    (is (= [:maybe [:time/offset-time {:pg/type "time with time zone"}]] (get-in registry [:pg.public/t 1 5 1])))
    (is (empty? unrendered))))

(deftest odd-identifiers
  (let [{:keys [registry]} (r/registry (p/facts {:name "public" :types {}
                                                 :tables {"Order Items" {:columns [{:name "line no" :position 1 :data_type "integer" :is_nullable false}] :constraints {}}}}))]
    (is (= [:map {:pg/table "public.Order Items"} ["line no" [:int {:min -2147483648 :max 2147483647 :pg/type "integer"}]]] (get registry "pg.public/Order Items"))))
  (is (= ["pg.odd schema/t"] (keys (:registry (r/registry (p/facts {:name "odd schema" :types {} :tables {"t" {:columns [{:name "a" :position 1 :data_type "text" :is_nullable true}] :constraints {}}}})))))
      "a schema name that is not a plain identifier makes string keys too"))

(deftest deterministic
  (is (= (r/registry facts) (r/registry (shuffle facts)))))
