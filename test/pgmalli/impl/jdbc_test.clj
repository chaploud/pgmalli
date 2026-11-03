(ns pgmalli.impl.jdbc-test
  "What a column is read and written as, and the keys of a row."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [pgmalli.core :as pgmalli]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.shape :as shape]
            [pgmalli.sample :refer [registry]]))

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

(deftest a-shaped-json-column-takes-its-shape-from-a-map-or-vector-sent-as-jsonb
  (let [shaped [:map {:pg/type "jsonb"} [:prompt :string]]]
    (is (= shaped (jdbc/write-type shaped {})))
    (is (= [:or [:map-of :any :any] [:vector :any]] (jdbc/write-type [:any {:pg/type "jsonb"}] {})) "an unshaped one takes what is sent")
    (is (= [:any {:pg/type "json"}] (jdbc/write-type [:any {:pg/type "json"}] {})) "nothing goes as json: the driver's object")
    (is (= shaped (jdbc/read-type shaped false)) "read parsed, in the shape that is written")
    (is (= [:any {:pg/type "jsonb"}] (jdbc/read-type shaped true)) "as pgjdbc returns it: the driver's object")))

(deftest a-row-is-keyed-by-the-columns-names
  (is (= [:born :closed_at] (take 2 (map first (drop 2 (pgmalli/as-read registry :pg.sample/users))))))
  (is (= [:born [:maybe [:time/local-date {:pg/type "date"}]]] (nth (pgmalli/as-read registry :pg.sample/users) 2))
      "a NULL column keeps its key"))

(deftest as-read-takes-the-options-map-every-function-shares
  (is (= (pgmalli.core/as-read registry :pg.sample/users)
         (pgmalli.core/as-read registry :pg.sample/users {:result :one})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"result"
                        (pgmalli.core/as-read registry :pg.sample/users {:result :sometimes}))))

(deftest how-a-value-is-read-is-no-option
  (doseq [o [{:time :instant} {:read {:json :parsed}}]]
    (let [e (try (pgmalli/as-read registry :pg.sample/users o) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))))
      (is (re-find #"no longer read: pgmalli types rows as one reading" (ex-message e))))))

(deftest a-comparison-is-strict-and-no-option
  (doseq [c [:strict :lenient]]
    (let [e (try (pgmalli/as-read registry :pg.sample/users {:compare c}) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))))
      (is (re-find #":compare is no longer read: a compared argument takes no nil" (ex-message e))))))
