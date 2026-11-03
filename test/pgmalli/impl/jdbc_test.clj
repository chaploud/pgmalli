(ns pgmalli.impl.jdbc-test
  "The transformer that decodes what a JDBC driver returns, and the keys of a row."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [pgmalli.core :as pgmalli]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.shape :as shape]
            [pgmalli.sample :refer [opts registry user]]))

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

(deftest a-domain-over-a-date-or-timestamp-takes-what-the-driver-takes-for-its-base
  (let [reg (pgmalli/registry {:registry {:pg.public/at [:and [:time/instant {:pg/type "timestamptz"}] [:fn {:pg/constraint "c"} 'any?]]
                                          :pg.public/day [:time/local-date {:pg/type "date"}]}})
        schemas (shape/schemas-of reg)
        takes? (fn [k v] (m/validate (jdbc/column-write-type schemas [:ref k] {}) v {:registry reg}))]
    (is (takes? :pg.public/at (java.util.Date.)))
    (is (takes? :pg.public/at (java.time.OffsetDateTime/now)))
    (is (takes? :pg.public/at (java.time.Instant/now)))
    (is (takes? :pg.public/day (java.util.Date.)))
    (is (takes? :pg.public/day (java.time.LocalDate/now)))
    (is (not (takes? :pg.public/day "2026-01-01")))))

(deftest transformer-parses-json-text
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:params [:any {:pg/type "jsonb"}]] [:note [:maybe [:string {:pg/type "text"}]]]]}})
        decoded (m/decode :pg.public/t {:params "{\"a\": [1, 2]}" :note "{\"b\": 1}"} {:registry reg} (pgmalli/transformer))]
    (is (= {"a" [1 2]} (:params decoded)) "JSON text in a jsonb column is parsed")
    (is (= "{\"b\": 1}" (:note decoded)) "text stays text")))

(deftest a-shaped-json-column-takes-its-shape-from-what-send-sends-as-its-type
  (let [shaped [:map {:pg/type "jsonb"} [:prompt :string]]
        sends {:send {clojure.lang.IPersistentMap "jsonb" clojure.lang.IPersistentVector "jsonb"}}]
    (is (= shaped (jdbc/write-type shaped sends)))
    (is (= [:any {:pg/type "jsonb"}] (jdbc/write-type shaped {})) "nothing sent as jsonb: the driver's object")
    (is (= [:or [:map-of :any :any] [:vector :any]] (jdbc/write-type [:any {:pg/type "jsonb"}] sends)) "an unshaped one takes what is sent")
    (is (= shaped (jdbc/read-type shaped {:read {:json :parsed}})))
    (is (= [:any {:pg/type "jsonb"}] (jdbc/read-type shaped {})) "read as the driver's object without an extension")))

(deftest text-decodes-into-the-bounded-numbers
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/t [:map {:pg/table "public.t"} [:a [:pg/integer {:pg/type "integer"}]] [:b [:pg/smallint {:pg/type "smallint"}]]
                                                        [:c [:and {:pg/type "numeric"} 'decimal? [:pg/numeric {:precision 5 :scale 2}]]] [:d [:int {:pg/type "bigint"}]]]}})]
    (is (= {:a 5 :b 7 :c 1.25M :d 9} (m/decode :pg.public/t {:a "5" :b "7" :c "1.25" :d "9"} {:registry reg} (pgmalli/transformer))))
    (is (= {:a "x" :b 7 :c "y" :d 9} (m/decode :pg.public/t {:a "x" :b 7 :c "y" :d "9"} {:registry reg} (pgmalli/transformer))) "what does not parse stays as it was")))

(deftest a-row-is-keyed-by-the-columns-names
  (is (= [:born :closed_at] (take 2 (map first (drop 2 (pgmalli/as-read registry :pg.sample/users))))))
  (is (= [:born [:maybe ['inst? {:pg/type "date"}]]] (nth (pgmalli/as-read registry :pg.sample/users) 2))
      "a NULL column keeps its key"))

(deftest as-read-takes-the-options-map-every-function-shares
  (is (= (pgmalli.core/as-read registry :pg.sample/users)
         (pgmalli.core/as-read registry :pg.sample/users {:compare :lenient :send {String :unknown}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"compare"
                        (pgmalli.core/as-read registry :pg.sample/users {:compare :sometimes}))))
