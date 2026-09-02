;; Writes test/resources/pgmalli/sample.edn from an in-memory schema (no database needed).
(require '[pgmalli.impl.pattern :as p] '[pgmalli.impl.render :as r] '[pgmalli.impl.generate :as gen] '[clojure.walk :as walk])
(def schema
  {:name "sample"
   :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}}
   :tables {"groups" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}
                                {:name "name" :position 2 :data_type "text" :is_nullable false}]
                      :constraints {"groups_pkey" {:name "groups_pkey" :type "PRIMARY KEY" :columns ["id"]}
                                    "groups_name_key" {:name "groups_name_key" :type "UNIQUE" :columns ["name"]}}}
            "users" {:columns [{:name "id" :position 1 :data_type "bigint" :is_nullable false :identity "ALWAYS"}
                               {:name "group_id" :position 2 :data_type "integer" :is_nullable false}
                               {:name "mood" :position 3 :data_type "mood" :type_schema "sample" :is_nullable false :default_value "'happy'::mood"}
                               {:name "nick" :position 4 :data_type "character varying" :is_nullable true :max_length 40}
                               {:name "born" :position 5 :data_type "date" :is_nullable true}
                               {:name "closed_at" :position 6 :data_type "timestamptz" :is_nullable true}]
                     :constraints {"users_pkey" {:name "users_pkey" :type "PRIMARY KEY" :columns ["id"]}
                                   "users_group_id_fkey" {:name "users_group_id_fkey" :type "FOREIGN KEY" :columns ["group_id"]
                                                          :references {:schema "sample" :table "groups" :columns ["id"]}}
                                   "closed_check" {:name "closed_check" :type "CHECK"
                                                   :check_clause "CHECK (mood = 'sad'::mood AND closed_at IS NOT NULL OR mood = 'happy'::mood AND closed_at IS NULL)"}}}}})
(spit "test/resources/pgmalli/sample.edn"
      (gen/edn-string (walk/postwalk #(if (map? %) (into (sorted-map-by (fn [a b] (compare (str a) (str b)))) %) %)
                                     (merge {:schema "sample" :database-version "none"} (r/registry (p/facts schema))))))
(println "wrote test/resources/pgmalli/sample.edn")
