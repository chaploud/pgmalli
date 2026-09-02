(ns pgmalli.runtime-test
  "The application side, on the checked-in generated files test/resources/pgmalli/{sample,other}.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as tcg]
            [malli.core :as m]
            [malli.error :as me]
            [pgmalli.core :as pgmalli]))

(def registry (pgmalli/registry "sample"))
(def opts {:registry registry})

(def ^:private user
  {:id 1 :group_id 1 :group_name nil :updated_at nil :mood "sad" :nick nil :born nil :closed_at (java.time.Instant/now)
   :referrer_id nil :seq 1 :nick_upper nil :score 1 :total 2})

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
      "files from before the schema-qualified :pg/table are refused, not half-read"))

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
    (let [reg (pgmalli/registry {:registry {"pg.public/Order Items" [:map {:pg/table "public.Order Items"} ["line no" [:int {:pg/type "integer" :pg/default 1}]]]
                                            :pg.public/t [:and [:map {:pg/table "public.t"}
                                                                [:a [:int {:pg/type "integer"}]]
                                                                [:b [:maybe [:int {:pg/type "integer" :pg/default 5 :default 5}]]]]
                                                          [:or [:map [:a {:error/message "c"} [:int {:min 1}]]] [:map [:b :nil]]]]}})]
      (is (m/validate "pg.public.Order Items/insert" {} {:registry reg}) "string keys follow the same naming")
      (is (m/validate :pg.public.t/insert {:a 0 :b nil} {:registry reg}) "entries with properties inside fragments survive")
      (is (not (m/validate :pg.public.t/insert {:a 0} {:registry reg})) "an omitted b is 5, not NULL, so the :or has no alternative left")
      (is (not (m/validate :pg.public.t/insert {:a 0 :b 1} {:registry reg})))))
  (testing "what an omitted column stands for"
    (let [reg (pgmalli/registry {:registry {:pg.public/t [:and [:map {:pg/table "public.t"}
                                                                [:status [:string {:pg/type "text" :pg/default "approved" :default "approved"}]]
                                                                [:approver [:maybe [:string {:pg/type "text"}]]]
                                                                [:a [:int {:pg/type "integer"}]]
                                                                [:b [:int {:pg/type "integer" :pg/default 5 :default 5}]]
                                                                [:c [:int {:pg/type "integer" :pg/default [:nextval "s"]}]]]
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

(deftest transformer-decodes-jdbc-values
  ;; babashka cannot construct java.sql.Timestamp; the JVM run covers this
  (when-not (System/getProperty "babashka.version")
    (let [tokyo (java.time.ZoneId/of "Asia/Tokyo")
          at (java.time.Instant/parse "2026-01-01T23:04:05Z")
          row (assoc user :born (java.sql.Date/valueOf "2026-01-02") :closed_at (java.sql.Timestamp. (.toEpochMilli at)))
          decode (fn [row t] (m/decode :pg.sample/users row opts t))
          decoded (decode row (pgmalli/transformer))]
      (is (= (java.time.LocalDate/parse "2026-01-02") (:born decoded)))
      (is (= at (:closed_at decoded)))
      (is (m/validate :pg.sample/users decoded opts))
      (is (= 42 (:id (decode (assoc row :id "42") (pgmalli/transformer)))) "strings too")
      (testing "wall-clock values are read in :zone"
        (is (= (java.time.LocalDateTime/parse "2026-01-02T08:04:05")
               (:updated_at (decode (assoc row :updated_at at) (pgmalli/transformer {:zone tokyo})))))
        (is (= (java.time.LocalDate/parse "2026-01-02")
               (:born (decode (assoc row :born (java.util.Date/from at)) (pgmalli/transformer {:zone tokyo})))))
        (is (= (java.time.LocalDateTime/ofInstant at (java.time.ZoneId/systemDefault))
               (:updated_at (decode (assoc row :updated_at at) (pgmalli/transformer))))
            "default: the JVM's zone, as JDBC's read-as-instant used")))))

(def ^:private good
  {"sample.groups" [{:id 1 :name "a"} {:id 2 :name "b"}]
   "sample.users" [(assoc user :group_name "a")
                   (assoc user :id 2 :group_name "a" :referrer_id 1)]})

(deftest datasets
  (let [ds (pgmalli/dataset-schema registry)]
    (is (m/validate ds good opts))
    (is (not (m/validate ds (assoc good "sample.groups" [{:id 1 :name "a"} {:id 1 :name "b"}]) opts)) "duplicate primary key")
    (is (not (m/validate ds (assoc good "sample.groups" [{:id 1 :name "a"} {:id 2 :name "a"}]) opts)) "duplicate unique")
    (is (not (m/validate ds (assoc-in good ["sample.users" 0 :group_id] 9) opts)) "dangling foreign key")
    (is (not (m/validate ds (assoc-in good ["sample.users" 0 :group_name] "b") opts)) "composite foreign key checked as a whole")
    (is (m/validate ds (assoc-in good ["sample.users" 0 :group_name] nil) opts) "a NULL in a composite key is not checked")
    (is (not (m/validate ds (assoc-in good ["sample.users" 1 :referrer_id] 9) opts)) "self-reference")
    (testing "generated datasets satisfy all of it"
      (doseq [sample (tcg/sample (pgmalli/dataset-generator registry {:rows 6}) 8)]
        (is (m/validate ds sample opts) (pr-str sample))))))

(deftest several-schemas
  (let [registry (pgmalli/registry "sample" "other")
        opts {:registry registry}
        ds (pgmalli/dataset-schema registry)]
    (is (m/validate :pg.other.notes/insert {:id 1 :user_id 1} opts))
    (is (m/validate ds (assoc good "other.notes" [{:id 1 :user_id 1}]) opts))
    (is (not (m/validate ds (assoc good "other.notes" [{:id 1 :user_id 9}]) opts)) "a reference into another schema")
    (doseq [sample (tcg/sample (pgmalli/dataset-generator registry {:rows 4}) 4)]
      (is (m/validate ds sample opts) (pr-str sample)))
    (is (m/validate (pgmalli/dataset-schema (pgmalli/registry "other")) {"other.notes" [{:id 1 :user_id 9}]} {:registry (pgmalli/registry "other")})
        "a reference to a table outside the registry is not checked")))
