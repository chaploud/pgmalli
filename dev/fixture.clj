;; Writes test/resources/pgmalli/{sample,other}.edn from in-memory schemas (no database needed).
(require '[pgmalli.impl.pattern :as p] '[pgmalli.impl.files :as gen])
(def sample
  {:name "sample"
   :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}}
   :tables {"groups" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}
                                {:name "name" :position 2 :data_type "text" :is_nullable false}]
                      :constraints {"groups_pkey" {:name "groups_pkey" :type "PRIMARY KEY" :columns ["id"]}
                                    "groups_name_key" {:name "groups_name_key" :type "UNIQUE" :columns ["name"]}
                                    "groups_id_name_key" {:name "groups_id_name_key" :type "UNIQUE" :columns ["id" "name"]}}}
            "users" {:columns [{:name "id" :position 1 :data_type "bigint" :is_nullable false :identity "ALWAYS"}
                               {:name "group_id" :position 2 :data_type "integer" :is_nullable false}
                               {:name "group_name" :position 7 :data_type "text" :is_nullable true}
                               {:name "updated_at" :position 8 :data_type "timestamp" :is_nullable true}
                               {:name "mood" :position 3 :data_type "mood" :type_schema "sample" :is_nullable false :default_value "'happy'::mood"}
                               {:name "nick" :position 4 :data_type "character varying" :is_nullable true :max_length 40}
                               {:name "born" :position 5 :data_type "date" :is_nullable true}
                               {:name "closed_at" :position 6 :data_type "timestamptz" :is_nullable true}
                               {:name "referrer_id" :position 9 :data_type "bigint" :is_nullable true}
                               {:name "seq" :position 10 :data_type "integer" :is_nullable false :default_value "nextval('users_seq_seq'::regclass)"}
                               {:name "nick_upper" :position 11 :data_type "text" :is_nullable true :generated_expr "upper((nick)::text)"}
                               {:name "score" :position 12 :data_type "integer" :is_nullable false}
                               {:name "total" :position 13 :data_type "integer" :is_nullable false :default_value "10"}]
                     :constraints {"users_pkey" {:name "users_pkey" :type "PRIMARY KEY" :columns ["id"]}
                                   "users_group_id_fkey" {:name "users_group_id_fkey" :type "FOREIGN KEY" :columns ["group_id"]
                                                          :references {:schema "sample" :table "groups" :columns ["id"]}}
                                   "users_group_composite_fkey" {:name "users_group_composite_fkey" :type "FOREIGN KEY" :columns ["group_id" "group_name"]
                                                                 :references {:schema "sample" :table "groups" :columns ["id" "name"]}}
                                   "users_referrer_id_fkey" {:name "users_referrer_id_fkey" :type "FOREIGN KEY" :columns ["referrer_id"]
                                                             :references {:schema "sample" :table "users" :columns ["id"]}}
                                   "closed_check" {:name "closed_check" :type "CHECK"
                                                   :check_clause "CHECK (mood = 'sad'::mood AND closed_at IS NOT NULL OR mood = 'happy'::mood AND closed_at IS NULL)"}
                                   "score_check" {:name "score_check" :type "CHECK" :check_clause "CHECK (score >= 0 AND score <= total)"}
                                   "referrer_check" {:name "referrer_check" :type "CHECK" :check_clause "CHECK (referrer_id <> id)"}}}}})
(def other
  {:name "other"
   :types {}
   :tables {"notes" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}
                               {:name "user_id" :position 2 :data_type "bigint" :is_nullable false}]
                     :constraints {"notes_pkey" {:name "notes_pkey" :type "PRIMARY KEY" :columns ["id"]}
                                   "notes_user_id_fkey" {:name "notes_user_id_fkey" :type "FOREIGN KEY" :columns ["user_id"]
                                                         :references {:schema "sample" :table "users" :columns ["id"]}}}}}})
(doseq [schema [sample other]]
  (let [path (str "test/resources/pgmalli/" (:name schema) ".edn")]
    (spit path (gen/edn-string (gen/assemble (:name schema) "none" (p/facts schema) {})))
    (println "wrote" path)))
