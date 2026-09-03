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

(defn- registry-with [r] (merge (m/default-schemas) (mu/schemas) (time/schemas)
                                 {:pg/check rt/check-schema :pg/check-value rt/check-value-schema :pg/bytes rt/bytes-schema
                                  :pg/smallint rt/smallint-schema :pg/integer rt/integer-schema :pg/numeric rt/numeric-schema} r))

(deftest row-schema
  (let [{:keys [registry unrendered]} (r/registry facts)
        users (:pg.public/users registry)]
    (is (= [:enum "happy" "sad"] (:pg.public/mood registry)))
    (is (= [:maybe [:and :string [:re "@"]]] (:pg.public/email registry)) "domain = base type + its CHECK")
    (is (= :and (first users)) "columns, then the table constraints")
    (is (= [:map {:pg/table "public.users" :pg/primary-key ["id"] :pg/unique [{:columns ["nick"]}] :pg/foreign-keys [{:columns ["group_id"] :table "public.groups" :to ["id"]}]}
            [:age [:maybe [:pg/integer {:min 0 :max 150 :pg/type "integer" :pg/constraint ["age_check"]}]]]
            [:born [:maybe [:time/local-date {:pg/type "date"}]]]
            [:closed_at [:maybe [:time/instant {:pg/type "timestamptz"}]]]
            [:created_at [:time/instant {:pg/type "timestamptz" :pg/default [:now]}]]
            [:flag [:boolean {:pg/type "boolean" :pg/default false :default false}]]
            [:full_name [:maybe [:string {:pg/type "text" :pg/generated true}]]]
            [:group_id [:pg/integer {:pg/type "integer"}]]
            [:id [:int {:pg/type "bigint" :pg/identity :always}]]
            [:mail [:maybe [:ref {:pg/type "email"} :pg.public/email]]]
            [:mood [:ref {:pg/type "mood" :pg/default "happy" :default "happy"} :pg.public/mood]]
            [:nick [:maybe [:string {:max 40 :pg/type "character varying"}]]]
            [:price ['decimal? {:pg/type "numeric" :pg/default 0 :default 0M}]]
            [:score [:pg/integer {:pg/type "integer"}]]
            [:seq [:pg/integer {:pg/type "integer" :pg/default [:nextval [:cast "users_seq_seq" :regclass]] :pg/identity :serial}]]
            [:since [:time/local-date {:pg/type "date" :pg/default "2020-01-01"}]]
            [:title [:and {:pg/type "text" :pg/constraint ["title_check"]} [:string {:min 1}] [:re "\\S"]]]
            [:total [:pg/integer {:pg/type "integer"}]]]
           (second users)))
    (is (= [:multi {:dispatch :mood :error/message "closed_check"}
            ["sad" [:map [:closed_at [:time/instant {:error/message "closed_check"}]]]]
            ["happy" [:map [:closed_at [:nil {:error/message "closed_check"}]]]]
            [:malli.core/default [:map [:mood :nil]]]]
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

(deftest not-valid-checks-are-enforced-and-marked
  (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {}
                                                            :tables {"t" {:columns [{:name "a" :position 1 :data_type "integer" :is_nullable false}
                                                                                    {:name "b" :position 2 :data_type "integer" :is_nullable false}]
                                                                          :constraints {"k" {:name "k" :type "CHECK" :check_clause "CHECK (a <= b)" :is_valid false}}}}}))
        reg (registry-with registry)]
    (is (= [:and [:map {:pg/table "public.t"} [:a [:pg/integer {:pg/type "integer"}]] [:b [:pg/integer {:pg/type "integer"}]]]
            [:pg/check {:pg/constraint "k" :error/message "k" :pg/not-valid true} [:<= :a :b]]]
           (:pg.public/t registry))
        "the database enforces it for new rows, so the schema does; rows from before it may not validate")
    (is (not (m/validate :pg.public/t {:a 2 :b 1} {:registry reg})))
    (is (empty? unrendered))))

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
            [:malli.core/default [:map [:kind :nil]]]]
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
    (is (= [:pg/integer {:min 10 :pg/type "integer" :pg/constraint ["k0" "k1"]}]
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
    (is (= [:maybe [:and :pg/integer [:pg/check-value {:pg/constraint "even_int_check" :error/message "even_int_check"} [:= [:mod :VALUE 2] 0]]]]
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
      (is (= [:maybe [:and :pg/integer [:pg/check-value {:pg/constraint "pos_check" :error/message "pos_check" :pg/not-valid true} [:> :VALUE 0]]]] (:pg.public/pos registry))
        "a NOT VALID domain CHECK is enforced (the database enforces it for every new value), marked")
      (is (empty? unrendered)))
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
    (is (= [:and {:pg/type "numeric" :pg/constraint ["a_check"]} 'decimal? [:pg/numeric {:precision 3 :scale 1}] [:>= 0.5]] (get-in t [2 1]))
        "numeric(3,1) rounds to one place and holds fewer than two digits before the point; a CHECK narrows further")
    (is (= [:and {:pg/type "numeric"} 'decimal? [:pg/numeric {:precision 4 :scale 0}]] (get-in t [3 1])))
    (is (= [:pg/smallint {:pg/type "smallint"}] (get-in t [4 1])))
    (let [reg (registry-with registry)]
      (is (m/validate :pg.public/t {:a 1.5M :b 1M :c 32767 :d nil :e nil} {:registry reg}))
      (is (not (m/validate :pg.public/t {:a 1.5M :b 1M :c 32768 :d nil :e nil} {:registry reg})) "smallint keeps its range")
      (is (not (m/validate :pg.public/t {:a 1.5M :b 1M :c 1 :d nil :e 2147483648} {:registry reg})) "so does integer")
      (is (every? #(<= -32768 (:c %) 32767) (mg/sample :pg.public/t {:registry (rt/registry {:database-version "x" :registry registry}) :size 20}))
          "and generates within it (the loaded registry's hints keep the bounded numeric from failing the search)")))
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
            [:malli.core/default [:map [:kind :nil]]]]
           (nth (:pg.public/t registry) 2))
        "a branch on the column being NULL dispatches on nil; a value with no branch fails the CHECK")
    (is (m/validate :pg.public/t {:kind nil :note nil :at nil :tz nil} {:registry reg}))
    (is (not (m/validate :pg.public/t {:kind nil :note "x" :at nil :tz nil} {:registry reg})))
    (is (not (m/validate :pg.public/t {:kind "b" :note nil :at nil :tz nil} {:registry reg})) "PostgreSQL rejects it too: the OR is false")
    (is (= [:maybe [:time/local-time {:pg/type "time without time zone"}]] (get-in registry [:pg.public/t 1 2 1])))
    (is (= [:maybe [:time/offset-time {:pg/type "time with time zone"}]] (get-in registry [:pg.public/t 1 5 1])))
    (is (empty? unrendered))))

(deftest views
  (let [{:keys [registry]} (r/registry (p/facts {:name "public" :types {}
                                                 :tables {"v" {:kind "VIEW" :columns [{:name "id" :position 1 :data_type "integer" :is_nullable true}
                                                                                      {:name "name" :position 2 :data_type "text" :is_nullable true}]
                                                               :constraints {}}
                                                          "mv" {:kind "MATERIALIZED VIEW" :columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}] :constraints {}}}}))]
    (is (= [:map {:pg/view "public.v"} [:id [:maybe [:pg/integer {:pg/type "integer"}]]] [:name [:maybe [:string {:pg/type "text"}]]]] (:pg.public/v registry)))
    (is (= [:map {:pg/view "public.mv"} [:id [:maybe [:pg/integer {:pg/type "integer"}]]]] (:pg.public/mv registry))
        "every column of a view may be NULL, whatever the catalog says")))

(deftest odd-identifiers
  (let [{:keys [registry]} (r/registry (p/facts {:name "public" :types {}
                                                 :tables {"Order Items" {:columns [{:name "line no" :position 1 :data_type "integer" :is_nullable false}] :constraints {}}}}))]
    (is (= [:map {:pg/table "public.Order Items"} ["line no" [:pg/integer {:pg/type "integer"}]]] (get registry "pg.public/Order Items"))))
  (is (= ["pg.odd schema/t"] (keys (:registry (r/registry (p/facts {:name "odd schema" :types {} :tables {"t" {:columns [{:name "a" :position 1 :data_type "text" :is_nullable true}] :constraints {}}}})))))
      "a schema name that is not a plain identifier makes string keys too"))

(deftest deterministic
  (is (= (r/registry facts) (r/registry (shuffle facts)))))

(deftest branches-without-their-own-value-and-columns-of-any-type
  (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {}
                                                            :tables {"t" {:columns [{:name "status" :position 1 :data_type "text" :is_nullable false}
                                                                                    {:name "result" :position 2 :data_type "jsonb" :is_nullable true}]
                                                                          :constraints {"k" {:name "k" :type "CHECK" :check_clause "CHECK (status = 'open'::text AND result IS NULL OR status = 'done'::text AND result IS NOT NULL)"}}}}}))
        reg (registry-with registry)]
    (is (= [:malli.core/default [:map [:status :nil]]] (last (nth (:pg.public/t registry) 2)))
        "a NOT NULL dispatch column can only take the values that have a branch")
    (is (not (m/validate :pg.public/t {:status "other" :result nil} {:registry reg})))
    (is (= [:map [:result [:some {:error/message "k"}]]] (get-in registry [:pg.public/t 2 3 1])) "IS NOT NULL on a jsonb column is :some, not :any")
    (is (not (m/validate :pg.public/t {:status "done" :result nil} {:registry reg})))
    (is (m/validate :pg.public/t {:status "done" :result {"a" 1}} {:registry reg}))
    (is (empty? unrendered))))

(deftest a-generated-range-orders-its-bounds
  (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {}
                                                            :tables {"t" {:columns [{:name "valid_from" :position 1 :data_type "timestamptz" :is_nullable false}
                                                                                    {:name "valid_until" :position 2 :data_type "timestamptz" :is_nullable true}
                                                                                    {:name "validity" :position 3 :data_type "tstzrange" :is_nullable true
                                                                                     :generated_expr "tstzrange(valid_from, valid_until)"}]
                                                                          :constraints {}}}}))
        reg (registry-with registry)
        t1 (java.time.Instant/parse "2024-01-01T00:00:00Z") t2 (java.time.Instant/parse "2024-02-01T00:00:00Z")]
    (is (= [:pg/check {:pg/constraint "validity_generated" :error/message "validity_generated"} [:<= :valid_from :valid_until]]
           (nth (:pg.public/t registry) 2)))
    (is (m/validate :pg.public/t {:valid_from t1 :valid_until t2 :validity nil} {:registry reg}))
    (is (m/validate :pg.public/t {:valid_from t1 :valid_until nil :validity nil} {:registry reg}) "an open bound is fine")
    (is (not (m/validate :pg.public/t {:valid_from t2 :valid_until t1 :validity nil} {:registry reg})) "the database would refuse to build the range")
    (is (= [:maybe [:any {:pg/type "tstzrange" :pg/generated true}]] (last (get-in registry [:pg.public/t 1 4]))) "the range column itself is opaque: :any with its type")
    (is (empty? unrendered))))

(deftest a-json-column-both-not-null-and-shaped
  (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {}
                                                            :tables {"t" {:columns [{:name "payload" :position 1 :data_type "jsonb" :is_nullable true}]
                                                                          :constraints {"nn" {:name "nn" :type "CHECK" :check_clause "CHECK (payload IS NOT NULL)"}
                                                                                        "obj" {:name "obj" :type "CHECK" :check_clause "CHECK (jsonb_typeof(payload) = 'object'::text)"}}}}}))]
    (is (= [:map {:pg/type "jsonb" :pg/constraint ["nn" "obj"]}] (get-in registry [:pg.public/t 2 1])) "IS NOT NULL and jsonb_typeof together are still [:map]")
    (is (empty? unrendered))))

(deftest numeric-rounds-before-it-counts-digits
  (let [reg (registry-with {})
        fits? (fn [p s v] (m/validate [:pg/numeric {:precision p :scale s}] v {:registry reg}))]
    (is (fits? 3 1 99.94M) "rounds to 99.9")
    (is (not (fits? 3 1 99.95M)) "rounds to 100.0, three digits before the point: PostgreSQL says numeric_value_out_of_range")
    (is (fits? 3 5 0.00999M) "scale above precision")
    (is (not (fits? 3 5 0.01M)))
    (is (fits? 2 -3 99000M) "negative scale")
    (is (not (fits? 2 -3 99500M)) "rounds to 100000")
    (is (not (fits? 3 1 1.0)) "a double is not what the driver returns")
    (doseq [[p s] [[3 1] [3 5] [2 -3] [30 10]]]
      (is (every? #(fits? p s %) (mg/sample [:pg/numeric {:precision p :scale s}] {:registry reg :size 50})) (str "generates within numeric(" p "," s ")")))))

(deftest types-the-regression-suite-uses
  (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {}
                                                            :tables {"t" {:columns [{:name "o" :position 1 :data_type "oid" :is_nullable false}
                                                                                    {:name "c" :position 2 :data_type "\"char\"" :is_nullable false}
                                                                                    {:name "b" :position 3 :data_type "bit" :is_nullable false :max_length 4}
                                                                                    {:name "vb" :position 4 :data_type "bit varying" :is_nullable false :max_length 6}
                                                                                    {:name "ip" :position 5 :data_type "inet" :is_nullable false}
                                                                                    {:name "tags" :position 6 :data_type "character varying[]" :is_nullable false :max_length 5}]
                                                                          :constraints {}}}}))
        reg (registry-with registry)
        col (fn [k] (last (some #(when (= k (first %)) %) (drop 2 (:pg.public/t registry)))))]
    (is (= [:int {:pg/type "oid"}] (col :o)))
    (is (nil? (some #(= :xid (last (last %))) (drop 2 (:pg.public/t registry)))) "xid is not an integer to the database")
    (is (= [:string {:pg/type "\"char\""}] (col :c)))
    (is (= [:string {:pg/type "bit" :min 4 :max 4}] (col :b)) "bit(n) is exactly n digits")
    (is (= [:string {:pg/type "bit varying" :max 6}] (col :vb)))
    (is (= [:any {:pg/type "inet"}] (col :ip)) "a type the driver hands over as its own object")
    (is (= [:vector {:pg/type "character varying[]"} [:string {:max 5}]] (col :tags)) "varchar(5)[] bounds the elements")
    (is (empty? unrendered) "none of them is unknown")
    (is (m/validate :pg.public/t {:o 1 :c "x" :b "0101" :vb "01" :ip "10.0.0.1" :tags ["abcde"]} {:registry reg}))
    (is (not (m/validate :pg.public/t {:o 1 :c "x" :b "01" :vb "01" :ip "10.0.0.1" :tags ["abcdef"]} {:registry reg})))))

(deftest a-check-on-a-generated-column-checks-its-expression
  (let [{:keys [registry unrendered]} (r/registry (p/facts {:name "public" :types {"positive" {:kind "DOMAIN" :base_type "integer" :not_null false
                                                                                             :constraints [{:name "positive_check" :definition "CHECK (VALUE > 0)"}]}}
                                                            :tables {"t" {:columns [{:name "a" :position 1 :data_type "integer" :is_nullable false}
                                                                                    {:name "b" :position 2 :data_type "integer" :is_nullable true :generated_expr "(a * 2)"}
                                                                                    {:name "p" :position 3 :data_type "positive" :type_schema "public" :is_nullable true :generated_expr "(a - 10)"}]
                                                                          :constraints {"b_small" {:name "b_small" :type "CHECK" :check_clause "CHECK (b < 50)"}}}}}))
        reg (registry-with registry)]
    (is (= [:pg/check {:pg/constraint "b_small" :error/message "b_small"} [:< [:* :a 2] 50]] (nth (:pg.public/t registry) 2))
        "b is a * 2 to the database, so the CHECK is on a")
    (is (= [:pg/check {:pg/constraint "p positive_check" :error/message "p positive_check"} [:> [:- :a 10] 0]] (nth (:pg.public/t registry) 3))
        "the domain of a generated column checks the expression's value")
    (is (m/validate :pg.public/t {:a 20 :b nil :p nil} {:registry reg}))
    (is (not (m/validate :pg.public/t {:a 30 :b nil :p nil} {:registry reg})) "30 * 2 is not below 50")
    (is (not (m/validate :pg.public/t {:a 5 :b nil :p nil} {:registry reg})) "5 - 10 is not positive")
    (is (empty? unrendered))))

(deftest an-alternative-no-row-can-match-is-left-out
  (let [{:keys [registry]} (r/registry (p/facts {:name "public" :types {}
                                                 :tables {"t" {:columns [{:name "id" :position 1 :data_type "bigint" :is_nullable false}]
                                                               ;; as a partition whose parent was re-attached above it renders: 30 <= id < 30
                                                               :constraints {"p" {:name "p" :type "CHECK"
                                                                                  :check_clause "CHECK (((id >= 0) AND (id < 10)) OR ((id >= 30) AND (id < 40) AND (id >= 20) AND (id < 30)))"}}}}}))
        reg (registry-with registry)]
    (is (= [:or {:error/message "p"} [:map [:id [:int {:min 0 :max 9 :error/message "p"}]]]] (nth (:pg.public/t registry) 2)))
    (is (m/validate :pg.public/t {:id 5} {:registry reg}))
    (is (not (m/validate :pg.public/t {:id 30} {:registry reg})))))

(deftest nested-list-partitions-pin-their-common-values
  (let [{:keys [registry]} (r/registry (p/facts {:name "public" :types {}
                                                 :tables {"t" {:columns [{:name "a" :position 1 :data_type "integer" :is_nullable false}]
                                                               ;; a LIST partition (1, 2) sub-partitioned by LIST: leaf (1), leaf (2)
                                                               :constraints {"p" {:name "p" :type "CHECK"
                                                                                  :check_clause "CHECK (((a IS NOT NULL) AND (a = ANY (ARRAY[1, 2])) AND (a IS NOT NULL) AND (a = 1)) OR ((a IS NOT NULL) AND (a = ANY (ARRAY[1, 2])) AND (a IS NOT NULL) AND (a = 2)) OR ((a IS NOT NULL) AND (a = 3)))"}}}}}))
        reg (registry-with registry)]
    (is (= [1 2 3 :malli.core/default] (map first (drop 2 (nth (:pg.public/t registry) 2)))) "each value once")
    (is (m/validate :pg.public/t {:a 2} {:registry reg}))
    (is (not (m/validate :pg.public/t {:a 4} {:registry reg})))))

(deftest diagnostics-name-what-no-row-can-satisfy
  (let [{:keys [diagnostics]} (r/registry (p/facts {:name "public" :types {}
                                                    :tables {"t" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}
                                                                            {:name "n" :position 2 :data_type "integer" :is_nullable false}]
                                                                  :constraints {"t_pkey" {:name "t_pkey" :type "PRIMARY KEY" :columns ["id"]}
                                                                                "t_id_idx" {:name "t_id_idx" :type "UNIQUE" :columns ["id"]}
                                                                                "t (partitions)" {:name "t (partitions)" :type "CHECK"
                                                                                                  :check_clause "CHECK (((id >= 0) AND (id < 10)) OR ((id >= 30) AND (id < 40) AND (id >= 20) AND (id < 30)))"}
                                                                                "n_big" {:name "n_big" :type "CHECK" :check_clause "CHECK (n > 10)"}
                                                                                "n_small" {:name "n_small" :type "CHECK" :check_clause "CHECK (n < 5)"}
                                                                                "nv" {:name "nv" :type "CHECK" :check_clause "CHECK (n <> 7)" :is_valid false}}}
                                                             "e" {:columns [{:name "k" :position 1 :data_type "integer" :is_nullable false}]
                                                                  :constraints {"e (partitions)" {:name "e (partitions)" :type "CHECK" :check_clause "CHECK (false)"}}}}}))]
    (is (= #{[:no-partition "public.e"] [:contradiction "public.t"] [:unreachable-partition "public.t"] [:not-valid "public.t"] [:redundant-unique "public.t"]}
           (set (map (juxt :kind :table) diagnostics))))
    (is (= 5 (count diagnostics)))
    (is (every? #(and (keyword? (:severity %)) (= :proven (:confidence %)) (string? (:message %))) diagnostics))))

(deftest a-not-enforced-constraint-is-noted-not-applied
  (let [{:keys [registry unrendered diagnostics]} (r/registry (p/facts {:name "public" :types {}
                                                                        :tables {"p" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}]
                                                                                      :constraints {"p_pkey" {:name "p_pkey" :type "PRIMARY KEY" :columns ["id"]}}}
                                                                                 "t" {:columns [{:name "b" :position 1 :data_type "integer" :is_nullable false}
                                                                                                {:name "p_id" :position 2 :data_type "integer" :is_nullable true}]
                                                                                      :constraints {"b_check" {:name "b_check" :type "CHECK" :check_clause "CHECK (b > 10) NOT ENFORCED" :is_valid true :is_enforced false}
                                                                                                    "t_p_id_fkey" {:name "t_p_id_fkey" :type "FOREIGN KEY" :columns ["p_id"] :is_enforced false
                                                                                                                   :references {:schema "public" :table "p" :columns ["id"]}}}}}}))]
    (is (= [:map {:pg/table "public.t"} [:b [:pg/integer {:pg/type "integer"}]] [:p_id [:maybe [:pg/integer {:pg/type "integer"}]]]] (:pg.public/t registry))
        "neither the CHECK nor the foreign key is applied: the database never checks them")
    (is (empty? unrendered))
    (is (= #{["b_check" :not-enforced] ["t_p_id_fkey" :not-enforced]} (set (map (juxt :constraint :kind) diagnostics))))))

(deftest a-row-trigger-is-noted
  (let [{:keys [diagnostics]} (r/registry (p/facts {:name "public" :types {}
                                                    :tables {"t" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}]
                                                                  :constraints {}
                                                                  :triggers [{:name "t_audit" :insert true :update true :delete false}
                                                                             {:name "t_on_delete" :insert false :update false :delete true}]}}}))]
    (is (= [{:kind :row-trigger :trigger "t_audit" :table "public.t"}] (map #(select-keys % [:kind :trigger :table]) diagnostics))
        "the INSERT trigger is noted, a DELETE one is no concern of a dataset")))
