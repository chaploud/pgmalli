(ns pgmalli.impl.jdbc-test
  "What a column is read and written as, and the keys of a row."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [pgmalli.core :as pgmalli]
            [pgmalli.impl.accept :as accept]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.shape :as shape]
            [pgmalli.sample :refer [registry]]))

(deftest a-domain-over-a-date-or-timestamp-takes-what-is-sent-as-its-base
  (let [at [:time/instant {:pg/type "timestamptz" :pg/domain "at"}]
        day [:time/local-date {:pg/type "date" :pg/domain "day"}]
        takes? (fn [s v] (m/validate (jdbc/column-write-type (shape/schemas-of registry) s {}) v {:registry registry}))]
    (is (not (takes? at (java.util.Date.))) "sent nowhere")
    (is (takes? at (java.time.OffsetDateTime/now)))
    (is (takes? at (java.time.Instant/now)))
    (is (not (takes? day (java.util.Date.))))
    (is (takes? day (java.time.LocalDate/now)))
    (is (not (takes? day "2026-01-01")))))

(deftest a-shaped-json-column-takes-its-shape-from-a-map-or-a-marked-vector-sent-as-jsonb
  (let [shaped [:map {:pg/type "jsonb"} [:prompt :string]]]
    (is (= shaped (jdbc/write-type shaped {})))
    (is (= [:or [:map-of :some :any] (accept/jsonb-vector :any)] (jdbc/write-type [:any {:pg/type "jsonb"}] {})) "an unshaped one takes what is sent")
    (is (= [:any {:pg/type "json"}] (jdbc/write-type [:any {:pg/type "json"}] {})) "nothing goes as json: the driver's object")
    (is (= shaped (jdbc/read-type shaped false)) "read parsed, in the shape that is written")
    (is (= [:any {:pg/type "jsonb"}] (jdbc/read-type shaped true)) "as pgjdbc returns it: the driver's object")))

(deftest a-row-is-keyed-by-the-columns-names
  (is (= [:born :closed_at] (take 2 (map first (drop 2 (pgmalli/as-read registry :pg.sample/users))))))
  (is (= [:born [:maybe [:time/local-date {:pg/type "date"}]]] (nth (pgmalli/as-read registry :pg.sample/users) 2))
      "a NULL column keeps its key"))

(deftest as-read-takes-jdbc-alone
  (is (= (pgmalli.core/as-read registry :pg.sample/users)
         (pgmalli.core/as-read registry :pg.sample/users {:jdbc {:fetch-size 10}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"\{:result \[\"disallowed key\"\]\}; it takes :schema, :jdbc"
                        (pgmalli.core/as-read registry :pg.sample/users {:result :one}))))

(deftest how-a-value-is-read-is-no-option
  (doseq [o [{:time :instant} {:read {:json :parsed}}]]
    (let [e (try (pgmalli/as-read registry :pg.sample/users o) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))))
      (is (re-find #"not read: pgmalli types rows as pgmalli.jdbc/builder reads them" (ex-message e))))))

(deftest a-comparison-is-strict-and-no-option
  (doseq [c [:strict :lenient]]
    (let [e (try (pgmalli/as-read registry :pg.sample/users {:compare c}) (catch clojure.lang.ExceptionInfo e e))]
      (is (= :pgmalli.error/invalid-options (:pgmalli/error (ex-data e))))
      (is (re-find #":compare is not read: a compared argument takes no nil" (ex-message e))))))


(deftest an-arrays-elements-are-its-elements-type-and-a-set-goes-where-no-order-is-seen
  (let [e [:int {:pg/type "bigint"}]]
    (is (= [:pg/array e] (first (shape/alternatives (jdbc/array-type e {} :assign)))) "a vector of them, sent untyped")
    (is (not-any? #(= :pg/array (shape/head %)) (shape/alternatives (jdbc/array-type e {} :unnest))) "unnest(unknown) is not unique")
    (is (some #(= [:pg/array {:pg/set true} e] %) (shape/alternatives (jdbc/array-type [:maybe e] {} :compare)))
        "= ANY(?): a set too, and no NULL, which matches no row")))

(deftest format-is-held-as-the-keys-pgmalli-reads-one-regex-per-text
  (let [format #(:format (jdbc/options! {:format {:params {:id (str (random-uuid))} :quoted true :quoted-always (re-pattern "^null$")}} [:format]))]
    (is (= {:quoted true} (dissoc (format) :quoted-always)) "sql/format's other options, :params with a request's values, left out")
    (is (identical? (:quoted-always (format)) (:quoted-always (format))))))
