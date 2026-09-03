(ns pgmalli.impl.registry-test
  "Generated files as malli registries, on the checked-in test/resources/pgmalli/{sample,other}.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as tcg]
            [malli.core :as m]
            [malli.error :as me]
            [malli.registry]
            malli.experimental.time
            [pgmalli.core :as pgmalli]
            [pgmalli.data :as data]
            [pgmalli.honeysql :as h]
            [pgmalli.sample :refer [good opts registry user]]))

(deftest registry-from-classpath
  (is (m/validate :pg.sample/users user opts))
  (is (= {:pg/table "sample.users" :pg/primary-key ["id"]
          :pg/foreign-keys [{:columns ["group_id" "group_name"] :table "sample.groups" :to ["id" "name"]}
                            {:columns ["group_id"] :table "sample.groups" :to ["id"]}
                            {:columns ["referrer_id"] :table "sample.users" :to ["id"]}]}
         (m/properties (pgmalli/columns registry :pg.sample/users))))
  (is (= {:closed_at ["closed_check"]}
         (me/humanize (m/explain :pg.sample/users (assoc user :closed_at nil) opts)))
      "errors name the constraint")
  (is (= ["score_check"]
         (me/humanize (m/explain :pg.sample/users (assoc user :score 3) opts))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not on the classpath" (pgmalli/registry "nope")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"older pgmalli" (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "t"} [:a :int]]}}))
      "files from before the schema-qualified :pg/table are refused, not half-read")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"older pgmalli" (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/unique [["a"]]} [:a :int]]}}))
      "so are files with the older key shapes"))

(deftest insert-schemas
  (let [insert (fn [row] (m/validate :pg.sample.users/insert row opts))]
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1}) "defaults, serials and nullable columns may be omitted")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now)})) "score has no default")
    (is (not (insert {:id 1 :group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1})) "identity ALWAYS cannot be inserted")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1 :nick_upper "X"})) "nor a generated column")
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1 :seq 7 :total 9}) "serials and defaults may be given")
    (is (not (insert {:group_id 1 :mood "happy" :closed_at (java.time.Instant/now) :score 1})) "table constraints apply")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 5 :total 2})) ":pg/check too")
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 5}) "score <= total holds with the default total 10")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 15})) "and fails against it, as the INSERT would")
    (is (not (insert {:group_id 1 :closed_at (java.time.Instant/now) :score 1})) "omitting mood means happy, which forbids closed_at")
    (is (insert {:group_id 1 :score 1}) "happy with no closed_at")
    (is (not (insert {:group_id 1 :mood "sad" :score 1})) "an omitted column without a default is NULL, which sad forbids"))
  (testing "from generated data instead of the classpath"
    (let [reg (pgmalli/registry {:registry {"pg.public/Order Items" [:map {:pg/table "public.Order Items"} ["line no" [:pg/integer {:pg/type "integer" :pg/default 1}]]]
                                            :pg.public/t [:and [:map {:pg/table "public.t"}
                                                                [:a [:pg/integer {:pg/type "integer"}]]
                                                                [:b [:maybe [:pg/integer {:pg/type "integer" :pg/default 5 :default 5}]]]]
                                                          [:or [:map [:a {:error/message "c"} [:int {:min 1}]]] [:map [:b :nil]]]]}})]
      (is (m/validate "pg.public.Order Items/insert" {} {:registry reg}) "string keys follow the same naming")
      (is (m/validate :pg.public.t/insert {:a 0 :b nil} {:registry reg}) "entries with properties inside fragments survive")
      (is (not (m/validate :pg.public.t/insert {:a 0} {:registry reg})) "an omitted b is 5, not NULL, so the :or has no alternative left")
      (is (not (m/validate :pg.public.t/insert {:a 0 :b 1} {:registry reg})))))
  (testing "what an omitted column stands for"
    (let [reg (pgmalli/registry {:registry {:pg.public/t [:and [:map {:pg/table "public.t"}
                                                                [:status [:string {:pg/type "text" :pg/default "approved" :default "approved"}]]
                                                                [:approver [:maybe [:string {:pg/type "text"}]]]
                                                                [:a [:pg/integer {:pg/type "integer"}]]
                                                                [:b [:pg/integer {:pg/type "integer" :pg/default 5 :default 5}]]
                                                                [:c [:pg/integer {:pg/type "integer" :pg/default [:nextval "s"]}]]]
                                                          [:multi {:dispatch :status}
                                                           ["approved" [:map [:approver :string]]]
                                                           [:malli.core/default [:map [:approver :nil]]]]
                                                          [:or [:map [:a [:int {:min 1}]]] [:map [:b [:int {:min 1}]]]]
                                                          [:pg/check [:<= :a :b]]
                                                          [:pg/check [:<= :a :c]]]}})
          insert (fn [row] (m/validate :pg.public.t/insert row {:registry reg}))]
      (is (insert {:status "pending" :approver nil :a 0}) "a value without a branch of its own still takes the default branch")
      (is (not (insert {:approver nil :a 0})) "omitted status is approved, which needs an approver")
      (is (insert {:approver "x" :a 0}) "the default b satisfies the :or, so it may be omitted")
      (is (insert {:approver "x" :a 5}) "a :pg/check sees the default b")
      (is (not (insert {:approver "x" :a 6})))
      (is (insert {:approver "x" :a 6 :b 9}))
      (is (not (insert {:approver "x" :a 1 :b nil})) "an explicit NULL wins over the default")
      (is (insert {:approver "x" :a 100 :b 200}) "an expression default is unknown, so the :pg/check on c cannot fail"))))

(deftest what-an-update-may-set
  (is (= [:map {:pg/table "sample.users" :closed true}
          [:born {:optional true} [:maybe [:time/local-date {:pg/type "date"}]]]
          [:closed_at {:optional true} [:maybe [:time/instant {:pg/type "timestamptz"}]]]
          [:group_id {:optional true} [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]
         (vec (take 5 (pgmalli/portable registry :pg.sample.users/update)))))
  (is (m/validate :pg.sample.users/update {:nick "n"} opts) "any subset of the columns")
  (is (not (m/validate :pg.sample.users/update {:score nil} opts)) "a NOT NULL column cannot be set to NULL")
  (is (not (m/validate :pg.sample.users/update {:id 1} opts)) "an identity ALWAYS column cannot be set")
  (is (not (m/validate :pg.sample.users/update {:nick_upper "x"} opts)) "nor a generated one")
  (is (not (m/validate :pg.sample.users/update {:nope 1} opts)) "closed"))

(deftest several-schemas
  (let [registry (pgmalli/registry "sample" "other")
        opts {:registry registry}
        ds (data/dataset-schema registry)]
    (is (m/validate :pg.other.notes/insert {:id 1 :user_id 1} opts))
    (is (m/validate ds (assoc good "other.notes" [{:id 1 :user_id 1}]) opts))
    (is (not (m/validate ds (assoc good "other.notes" [{:id 1 :user_id 9}]) opts)) "a reference into another schema")
    (doseq [sample (tcg/sample (data/dataset-generator registry {:rows 4}) 4)]
      (is (m/validate ds sample opts) (pr-str sample)))
    (is (m/validate (data/dataset-schema (pgmalli/registry "other")) {"other.notes" [{:id 1 :user_id 9}]} {:registry (pgmalli/registry "other")})
        "a reference to a table outside the registry is not checked")))

(deftest install-makes-the-names-readable-everywhere
  (let [before (pgmalli/install! "sample")]
    (is (m/validate :pg.sample/groups {:id 1 :name "g"}) "no registry passed: malli's default one has it now")
    (is (not (m/validate :pg.sample/groups {:id "x" :name "g"})))
    (is (map? before) "what the default registry held, to put back")
    (malli.registry/set-default-registry! before)
    (is (thrown? Exception (m/validate :pg.sample/groups {:id 1 :name "g"})) "put back: the names are gone again")))

(deftest any-malli-registry-will-do
  (let [composite (malli.registry/composite-registry registry {:app/flag :boolean})]
    (is (m/validate :pg.sample/groups {:id 1 :name "g"} {:registry composite}))
    (is (= [] (h/check composite {:select [:id] :from [:groups]} {:schema "sample"})))
    (is (= 2 (count (keys (tcg/generate (data/dataset-generator composite {:rows 2}) 20 1)))) "datasets from a composite registry")
    (is (= (pgmalli/column registry :pg.sample/users :nick) (pgmalli/column composite :pg.sample/users :nick)))
    (is (= (pgmalli/portable registry :pg.sample/users) (pgmalli/portable composite :pg.sample/users)) "portable inlines references through a composite registry")
    (is (= (h/query-schema registry '[id] {:select [:id] :from [:groups]} {:schema "sample"})
           (h/query-schema composite '[id] {:select [:id] :from [:groups]} {:schema "sample"})))))
