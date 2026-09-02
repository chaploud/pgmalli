(ns pgmalli.runtime-test
  "The application side, on the checked-in generated file test/resources/pgmalli/sample.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as tcg]
            [malli.core :as m]
            [malli.error :as me]
            [pgmalli.core :as pgmalli]))

(def registry (pgmalli/registry "sample"))
(def opts {:registry registry})

(deftest registry-from-classpath
  (is (m/validate :pg.sample.users/insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now)} opts))
  (is (= [:map {:pg/table "users" :pg/primary-key ["id"]}]
         (take 2 (m/form (pgmalli/columns registry :pg.sample/users)))))
  (is (= {:closed_at ["closed_check"]}
         (me/humanize (m/explain :pg.sample/users {:id 1 :group_id 1 :mood "sad" :nick nil :born nil :closed_at nil} opts)))
      "errors name the constraint")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not on the classpath" (pgmalli/registry "nope"))))

(deftest transformer-decodes-jdbc-values
  ;; babashka cannot construct java.sql.Timestamp; the JVM run covers this
  (when-not (System/getProperty "babashka.version")
   (let [row {:id 1 :group_id 1 :mood "sad" :nick nil
             :born (java.sql.Date/valueOf "2026-01-02")
             :closed_at (java.sql.Timestamp. (.toEpochMilli (java.time.Instant/parse "2026-01-02T03:04:05Z")))}
        decoded (m/decode :pg.sample/users row (assoc opts :registry registry) pgmalli/transformer)]
    (is (= (java.time.LocalDate/parse "2026-01-02") (:born decoded)))
    (is (= (java.time.Instant/parse "2026-01-02T03:04:05Z") (:closed_at decoded)))
    (is (m/validate :pg.sample/users decoded opts))
    (is (= 42 (:id (m/decode :pg.sample/users (assoc row :id "42") opts pgmalli/transformer))) "strings too"))))

(deftest datasets
  (let [ds (pgmalli/dataset-schema registry)
        good {"groups" [{:id 1 :name "a"} {:id 2 :name "b"}]
              "users" [{:id 1 :group_id 1 :mood "happy" :nick nil :born nil :closed_at nil}]}]
    (is (m/validate ds good opts))
    (is (not (m/validate ds (assoc good "groups" [{:id 1 :name "a"} {:id 1 :name "b"}]) opts)) "duplicate primary key")
    (is (not (m/validate ds (assoc good "groups" [{:id 1 :name "a"} {:id 2 :name "a"}]) opts)) "duplicate unique")
    (is (not (m/validate ds (assoc-in good ["users" 0 :group_id] 9) opts)) "dangling foreign key")
    (testing "generated datasets satisfy all of it"
      (doseq [sample (tcg/sample (pgmalli/dataset-generator registry {:rows 4}) 5)]
        (is (m/validate ds sample opts) (pr-str sample))))))
