(ns pgmalli.impl.render-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [pgmalli.impl.pattern :as p]
            [pgmalli.impl.render :as r]))

(def ^:private schema
  {:name "public"
   :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}
           "unused" {:kind "ENUM" :enum_values ["x"]}}
   :tables {"users" {:columns [{:name "id" :position 1 :data_type "bigint" :is_nullable false :identity "ALWAYS"}
                               {:name "mood" :position 2 :data_type "mood" :type_schema "public" :is_nullable false :default_value "'happy'::mood"}
                               {:name "nick" :position 3 :data_type "character varying" :is_nullable true :max_length 40}
                               {:name "age" :position 4 :data_type "integer" :is_nullable true}
                               {:name "kind" :position 5 :data_type "text" :is_nullable false}
                               {:name "title" :position 6 :data_type "text" :is_nullable false}
                               {:name "meta" :position 7 :data_type "jsonb" :is_nullable false}
                               {:name "closed_at" :position 8 :data_type "timestamptz" :is_nullable true}
                               {:name "tags" :position 9 :data_type "text[]" :is_nullable false}
                               {:name "ratio" :position 10 :data_type "double precision" :is_nullable false}
                               {:name "level" :position 11 :data_type "smallint" :is_nullable false}
                               {:name "bad_default" :position 12 :data_type "text" :is_nullable true :default_value "CASE WHEN"}
                               {:name "odd name" :position 13 :data_type "text" :is_nullable true}]
                     :constraints {"age_check" {:name "age_check" :type "CHECK" :check_clause "CHECK (age IS NULL OR age >= 0 AND age <= 150)"}
                                   "kind_check" {:name "kind_check" :type "CHECK" :check_clause "CHECK (kind IN ('a'::text, 'b'::text))"}
                                   "title_check" {:name "title_check" :type "CHECK" :check_clause "CHECK (length(TRIM(BOTH FROM title)) > 0)"}
                                   "meta_check" {:name "meta_check" :type "CHECK" :check_clause "CHECK (jsonb_typeof(meta) = 'object'::text)"}
                                   "ratio_check" {:name "ratio_check" :type "CHECK" :check_clause "CHECK (ratio > 0::double precision)"}
                                   "closed_check" {:name "closed_check" :type "CHECK" :check_clause "CHECK (mood = 'sad'::mood OR closed_at IS NULL)"}
                                   "nick_nn" {:name "nick_nn" :type "CHECK" :check_clause "CHECK (nick IS NOT NULL)"}
                                   "level_check" {:name "level_check" :type "CHECK" :check_clause "CHECK (level = ANY (ARRAY[1, 2, 3]))"}}}
            "Order Items" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false}] :constraints {}}}})

(def ^:private facts (p/facts schema))

(deftest fixed-rendering
  (let [{:keys [registry unrendered skipped]} (r/registry facts)]
    (is (= [:enum "happy" "sad"] (:pg.public/mood registry)))
    (is (= [:enum "x"] (:pg.public/unused registry)) "enum types are listed even when no column uses them")
    (is (= [:map {:pg/table "users"}
            [:age [:maybe [:int {:min 0 :max 150 :pg/type "integer" :pg/constraint ["age_check"]}]]]
            [:bad_default [:maybe [:string {:pg/type "text"}]]]
            [:closed_at [:maybe ['inst? {:pg/type "timestamptz"}]]]
            [:id [:int {:pg/type "bigint"}]]
            [:kind [:enum {:pg/type "text" :pg/constraint ["kind_check"]} "a" "b"]]
            [:level [:enum {:pg/type "smallint" :pg/constraint ["level_check"]} 1 2 3]]
            [:meta [:map {:pg/type "jsonb" :pg/constraint ["meta_check"]}]]
            [:mood [:ref {:pg/type "mood" :pg/default [:cast "happy" :mood]} :pg.public/mood]]
            [:nick [:string {:max 40 :pg/type "character varying" :pg/constraint ["nick_nn"]}]]
            ["odd name" [:maybe [:string {:pg/type "text"}]]]
            [:ratio [:and {:pg/type "double precision" :pg/constraint ["ratio_check"]} :double [:> 0]]]
            [:tags [:vector {:pg/type "text[]"} :string]]
            [:title [:string {:min 1 :pg/trim true :pg/type "text" :pg/constraint ["title_check"]}]]]
           (:pg.public/users registry)))
    (is (= [:map {:pg/table "Order Items"} [:id [:int {:pg/type "integer"}]]] (get registry "pg.public/Order Items")))
    (is (= [[:unparsed "bad_default"] [:table-check "closed_check"]]
           (map (juxt :fact #(or (:constraint %) (:column %))) unrendered)))
    (is (empty? skipped))
    (testing "the result is valid malli that validates a row"
      (let [reg (merge (m/default-schemas) registry)
            row {:id 1 :mood "happy" :nick "n" :age 30 :kind "a" :title "x" :meta {} :closed_at nil
                 :tags ["t"] :ratio 0.5 :level 2 :bad_default nil "odd name" nil}]
        (is (m/validate :pg.public/users row {:registry reg}))
        (is (not (m/validate :pg.public/users (assoc row :kind "zzz") {:registry reg})))
        (is (m/validate "pg.public/Order Items" {:id 1} {:registry reg}))))))

(deftest overrides
  (let [{:keys [registry unrendered skipped]}
        (r/registry facts {"closed_check" [:ref :app/closed-consistent]
                           "title_check" {:skip "guaranteed by the application"}})]
    (is (= [[:unparsed "bad_default"]] (map (juxt :fact :column) unrendered)))
    (is (= ["title_check"] (map :constraint skipped)))
    (is (= :and (first (:pg.public/users registry))))
    (is (= [:string {:pg/type "text"}]
           (some (fn [e] (when (and (vector? e) (= :title (first e))) (last e))) (second (:pg.public/users registry)))))))

(deftest deterministic
  (is (= (r/registry facts) (r/registry (shuffle facts)))))
