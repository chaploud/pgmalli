(ns pgmalli.impl.jdbc-test
  "The transformer that decodes what a JDBC driver returns, and the builder table."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [pgmalli.core :as pgmalli]))

(def registry (pgmalli/registry "sample"))
(def opts {:registry registry})

(def ^:private user
  {:id 1 :group_id 1 :group_name nil :updated_at nil :mood "sad" :nick nil :born nil :closed_at (java.time.Instant/now)
   :referrer_id nil :seq 1 :nick_upper nil :score 1 :total 2})

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

(deftest transformer-parses-json-text
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:params [:any {:pg/type "jsonb"}]] [:note [:maybe [:string {:pg/type "text"}]]]]}})
        decoded (m/decode :pg.public/t {:params "{\"a\": [1, 2]}" :note "{\"b\": 1}"} {:registry reg} (pgmalli/transformer))]
    (is (= {"a" [1 2]} (:params decoded)) "JSON text in a jsonb column is parsed")
    (is (= "{\"b\": 1}" (:note decoded)) "text stays text")))

(deftest text-decodes-into-the-bounded-numbers
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/t [:map {:pg/table "public.t"} [:a [:pg/integer {:pg/type "integer"}]] [:b [:pg/smallint {:pg/type "smallint"}]]
                                                        [:c [:and {:pg/type "numeric"} 'decimal? [:pg/numeric {:precision 5 :scale 2}]]] [:d [:int {:pg/type "bigint"}]]]}})]
    (is (= {:a 5 :b 7 :c 1.25M :d 9} (m/decode :pg.public/t {:a "5" :b "7" :c "1.25" :d "9"} {:registry reg} (pgmalli/transformer))))
    (is (= {:a "x" :b 7 :c "y" :d 9} (m/decode :pg.public/t {:a "x" :b 7 :c "y" :d "9"} {:registry reg} (pgmalli/transformer))) "what does not parse stays as it was")))

(deftest reading-options-of-a-next-jdbc-builder
  (is (= {:qualified? true} (pgmalli/read-options 'next.jdbc/as-maps)))
  (is (= {} (pgmalli/read-options 'next.jdbc/as-unqualified-lower-maps)))
  (is (= {:kebab? true :nil-columns :absent} (pgmalli/read-options 'next.jdbc.optional/as-unqualified-kebab-maps)))
  (is (nil? (pgmalli/read-options 'next.jdbc/as-arrays)) "no map, no options")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a next.jdbc" (pgmalli/read-options 'my.ns/as-things)))
  (is (= (pgmalli/as-read registry :pg.sample/users (pgmalli/read-options 'next.jdbc.optional/as-unqualified-kebab-maps))
         (pgmalli/as-read registry :pg.sample/users {:kebab? true :nil-columns :absent}))
      "the options are as-read's"))
