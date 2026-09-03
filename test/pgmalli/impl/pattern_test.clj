(ns pgmalli.impl.pattern-test
  (:require [clojure.test :refer [deftest is]]
            [pgmalli.impl.pattern :as p]))

(defn- schema-with-checks [& clauses]
  {:name "public"
   :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}}
   :tables {"t" {:columns [{:name "c" :position 1 :data_type "integer" :is_nullable true}
                           {:name "m" :position 2 :data_type "mood" :type_schema "public" :is_nullable false :default_value "'happy'::mood"}
                           {:name "v" :position 3 :data_type "character varying" :is_nullable true :max_length 40}]
                 :constraints (into {} (map-indexed (fn [i c] [(str "k" i) {:name (str "k" i) :type "CHECK" :check_clause (str "CHECK (" c ")")}]) clauses))}}})

(defn- check-facts [& clauses]
  (->> (p/facts (apply schema-with-checks clauses)) (filter :constraint) (map #(cond-> (dissoc % :schema :table :constraint) (not= :table-check (:fact %)) (dissoc :expr))) vec))

(deftest facts-from-types-and-columns
  (is (= [{:fact :enum-type :schema "public" :type-name "mood" :values ["happy" "sad"]}
          {:fact :column :schema "public" :table "t" :column "c" :type "integer" :position 1 :nullable? true}
          {:fact :column :schema "public" :table "t" :column "m" :type "mood" :position 2 :nullable? false :default [:cast "happy" :mood]}
          {:fact :enum :schema "public" :table "t" :column "m" :type-name "mood" :values ["happy" "sad"]}
          {:fact :column :schema "public" :table "t" :column "v" :type "character varying" :position 3 :nullable? true}
          {:fact :max-length :schema "public" :table "t" :column "v" :max 40}]
         (p/facts (schema-with-checks)))))

(deftest unknown-types-and-unreadable-defaults-are-kept
  (let [fs (p/facts {:name "public" :types {}
                     :tables {"t" {:columns [{:name "e" :position 1 :data_type "email" :type_schema "public" :is_nullable false :default_value "CASE WHEN"}
                                             {:name "x" :position 2 :data_type "text" :type_schema "other" :is_nullable true}]
                                   :constraints {}}}})]
    (is (= [:column :unparsed :unknown-type :column :unknown-type] (map :fact fs)))
    (is (= "CASE WHEN" (:input (second fs))))))

(deftest column-patterns
  (is (= [{:fact :not-null :column "c"}] (check-facts "c IS NOT NULL")))
  (is (= [{:fact :in-set :column "c" :values [1 2]} {:fact :range :column "m" :min 1 :min-exclusive? false}]
         (check-facts "c = ANY (ARRAY[1, 2]) AND m >= 1")) "PostgreSQL's rewrites are undone below the top level too")
  (is (= [{:fact :when-present :column "c" :fact-when-present {:fact :in-set :values [1 2]}}]
         (check-facts "c IS NULL OR c = ANY (ARRAY[1, 2])")))
  (is (= [{:fact :in-set :column "c" :values ["a" "b"]}] (check-facts "c IN ('a'::text, 'b'::text)")))
  (is (= [{:fact :in-set :column "c" :values ["a"]}] (check-facts "c = 'a'::mood")))
  (is (= [{:fact :in-set :column "c" :values ["a" "b"]}] (check-facts "c::text IN ('a'::character varying, 'b'::character varying)")))
  (is (= [{:fact :in-set :column "c" :values ["a" "b" "c"]}] (check-facts "c = ANY (ARRAY['a'::text, 'b'::text, 'c'::text])")))
  (is (= [{:fact :in-set :column "c" :values [0 1 2]}] (check-facts "c = ANY (ARRAY[0, 1, 2])")))
  (is (= [{:fact :range :column "c" :min 1 :min-exclusive? false :max 5 :max-exclusive? false}] (check-facts "c >= 1 AND c <= 5")))
  (is (= [{:fact :range :column "c" :min 1 :min-exclusive? false :max 5 :max-exclusive? false}] (check-facts "c BETWEEN 1 AND 5")))
  (is (= [{:fact :range :column "c" :min 0 :min-exclusive? true}] (check-facts "c > 0")))
  (is (= [{:fact :range :column "c" :min -1 :min-exclusive? false}] (check-facts "c >= '-1'::integer")))
  (is (= [{:fact :range :column "c" :max 10 :max-exclusive? false}] (check-facts "10 >= c")))
  (is (= [{:fact :non-blank :column "c" :trim? true}] (check-facts "length(TRIM(BOTH FROM c)) > 0")))
  (is (= [{:fact :non-blank :column "c" :trim? true}] (check-facts "c::text = btrim(c::text) AND c::text <> ''::text")))
  (is (= [{:fact :non-blank :column "c" :trim? false}] (check-facts "c <> ''::text")))
  (is (= [{:fact :length :column "c" :fn :octet_length :exact 32}] (check-facts "octet_length(c) = 32")))
  (is (= [{:fact :length :column "c" :fn :length :max 9}] (check-facts "length(c) < 10")))
  (is (= [{:fact :json-type :column "c" :json-type "object"}] (check-facts "jsonb_typeof(c) = 'object'::text")))
  (is (= [{:fact :regex :column "c" :re "^[a-z]+$" :case-insensitive? false}] (check-facts "c ~ '^[a-z]+$'::text")))
  (is (= [{:fact :when-present :column "c" :fact-when-present {:fact :range :min 0 :min-exclusive? true}}]
         (check-facts "c IS NULL OR c > 0")))
  (is (= [{:fact :when-present :column "c" :fact-when-present {:fact :range :min 0 :min-exclusive? true}}]
         (check-facts "c > 0 OR c IS NULL"))))

(deftest and-of-column-patterns-yields-several-facts
  (is (= [{:fact :non-blank :column "c" :trim? true} {:fact :length :column "c" :fn :length :max 100}]
         (check-facts "length(TRIM(BOTH FROM c)) > 0 AND length(c) <= 100")))
  (is (= [{:fact :range :column "c" :min 0 :min-exclusive? false} {:fact :range :column "m" :min 1 :min-exclusive? false}]
         (check-facts "c >= 0 AND m >= 1"))))

(deftest everything-else-stays-a-table-check
  (is (= [{:fact :table-check :expr [:or [:is :a nil] [:<> :a :b]] :columns ["a" "b"]}]
         (check-facts "a IS NULL OR a <> b")))
  (is (= [{:fact :table-check :expr [:and [:>= :c 0] [:<= :c :total]] :columns ["c" "total"]}]
         (check-facts "c >= 0 AND c <= total")))
  (is (= [{:fact :table-check :expr [:in :x [:a :b]] :columns ["x" "a" "b"]}] (check-facts "x IN (a, b)")))
  (is (= [{:fact :table-check :expr :c :columns ["c"]}] (check-facts "c")) "a bare boolean column")
  (is (= ["c" "p"] (:columns (first (check-facts "CASE c WHEN 'a'::text THEN (p ->> 'x'::text) = 'y'::text ELSE false END"))))))

(deftest not-valid-is-never-matched
  (let [fs (p/facts {:name "public" :types {}
                     :tables {"t" {:columns [{:name "c" :position 1 :data_type "integer" :is_nullable true}]
                                   :constraints {"k" {:name "k" :type "CHECK" :check_clause "CHECK (c >= 0)" :is_valid false}}}}})]
    (is (= {:fact :table-check :valid? false :columns ["c"] :expr [:>= :c 0]}
           (dissoc (last fs) :schema :table :constraint)))))

(deftest keys-and-references
  (let [fs (p/facts {:name "public" :types {}
                     :tables {"m" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false :default_value "nextval('m_id_seq'::regclass)"}
                                             {:name "g" :position 2 :data_type "integer" :is_nullable false}]
                                   :constraints {"m_pkey" {:name "m_pkey" :type "PRIMARY KEY" :columns ["id"]}
                                                 "m_g_key" {:name "m_g_key" :type "UNIQUE" :columns ["g"]}
                                                 "m_g_fkey" {:name "m_g_fkey" :type "FOREIGN KEY" :columns ["g"]
                                                             :references {:schema "public" :table "groups" :columns ["id"]}}}}}})]
    (is (= :serial (:identity (first (filter (comp #{"id"} :column) fs)))) "a nextval default is a serial column")
    (is (= [[:references ["g"]] [:unique ["g"]] [:primary-key ["id"]]]
           (map (juxt :fact :columns) (filter :constraint fs))) "constraints in name order")
    (is (= {:schema "public" :table "groups" :columns ["id"]} (:to (first (filter (comp #{:references} :fact) fs)))))))

(deftest domains
  (let [fs (p/facts {:name "public"
                     :types {"email" {:kind "DOMAIN" :base_type "text" :not_null false
                                      :constraints [{:name "email_check" :definition "CHECK (VALUE ~ '@'::text)"}]}}
                     :tables {"t" {:columns [{:name "mail" :position 1 :data_type "email" :type_schema "public" :is_nullable true}] :constraints {}}}})]
    (is (= {:fact :domain :schema "public" :type-name "email" :base "text" :not-null? false
            :facts [{:fact :regex :re "@" :case-insensitive? false :constraint "email_check" :schema "public" :type-name "email"
                     :expr [:regex :VALUE [:cast "@" :text]]}]}
           (first fs)))
    (is (= [{:fact :column} {:fact :domain-ref :base "text"}] (map #(select-keys % [:fact :base]) (rest fs))))))

(deftest branch-checks
  (is (= [{:fact :branch-check :dispatch "status"
           :branches [{:values ["pending"] :facts [{:fact :null :column "closed_at"}]}
                      {:values ["approved" "rejected"] :facts [{:fact :not-null :column "closed_at"}]}]
           :default nil}]
         (check-facts "status = 'pending'::approval_status AND closed_at IS NULL OR (status IN ('approved'::approval_status, 'rejected'::approval_status)) AND closed_at IS NOT NULL")))
  (is (= [{:fact :branch-check :dispatch "status"
           :branches [{:values ["approved" "rejected"] :facts [{:fact :not-null :column "approver"}]}]
           :default [{:fact :null :column "approver"}]}]
         (check-facts "(status IN ('approved'::t, 'rejected'::t)) AND approver IS NOT NULL OR (status <> ALL (ARRAY['approved'::t, 'rejected'::t])) AND approver IS NULL")))
  (is (= [{:fact :or-check
           :alternatives [[{:fact :null :column "pin"}]
                          [{:fact :in-set :column "kind" :values ["skill"]} {:fact :in-set :column "verb" :values ["acquired"]}]]}]
         (check-facts "pin IS NULL OR kind = 'skill'::text AND verb = 'acquired'::text"))
      "alternatives over different columns become an :or-check")
  (is (= :table-check (:fact (first (check-facts "a IS NULL OR a <> b"))))))
