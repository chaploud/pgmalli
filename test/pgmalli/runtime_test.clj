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
  (is (= {:pg/table "users" :pg/primary-key ["id"]
          :pg/foreign-keys [[["group_id" "group_name"] "groups" ["id" "name"]] [["group_id"] "groups" ["id"]] [["referrer_id"] "users" ["id"]]]}
         (m/properties (pgmalli/columns registry :pg.sample/users))))
  (is (= {:closed_at ["closed_check"]}
         (me/humanize (m/explain :pg.sample/users (assoc user :closed_at nil) opts)))
      "errors name the constraint")
  (is (= ["score_check"]
         (me/humanize (m/explain :pg.sample/users (assoc user :score 3) opts))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not on the classpath" (pgmalli/registry "nope"))))

(deftest insert-schemas
  (let [insert (fn [row] (m/validate :pg.sample.users/insert row opts))]
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1}) "defaults, serials and nullable columns may be omitted")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now)})) "score has no default")
    (is (not (insert {:id 1 :group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1})) "identity ALWAYS cannot be inserted")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1 :nick_upper "X"})) "nor a generated column")
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1 :seq 7 :total 9}) "serials and defaults may be given")
    (is (not (insert {:group_id 1 :mood "happy" :closed_at (java.time.Instant/now) :score 1})) "table constraints apply")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 5 :total 2})) ":pg/check too")
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 5}) "an omitted column is NULL to a :pg/check, even with a default"))
  (testing "from generated data instead of the classpath"
    (let [reg (pgmalli/registry {:registry {"pg.public/Order Items" [:map {:pg/table "Order Items"} ["line no" [:int {:pg/type "integer" :pg/default 1}]]]
                                            :pg.public/t [:and [:map {:pg/table "t"} [:a [:int {:pg/type "integer"}]] [:b [:maybe [:int {:pg/type "integer"}]]]]
                                                          [:or [:map [:a {:error/message "c"} [:int {:min 1}]]] [:map [:b :nil]]]]}})]
      (is (m/validate "pg.public.Order Items/insert" {} {:registry reg}) "string keys follow the same naming")
      (is (m/validate :pg.public.t/insert {:a 0} {:registry reg}) "entries with properties inside fragments survive")
      (is (not (m/validate :pg.public.t/insert {:a 0 :b 1} {:registry reg}))))))

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
