(ns pgmalli.accept-test
  "pgmalli.impl.accept against PostgreSQL itself: each sent type prepared where each type goes.
   JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [pgmalli.core :as pgmalli]
            [pgmalli.impl.accept :as accept]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(def ^:private scalars accept/covered)

(def ^:private targets
  "[column type, the type accept/accepts? takes for it]: a domain is its base type."
  (concat (map (juxt identity identity) (concat scalars accept/covered-arrays))
          [["feel" [:enum "feel"]] ["positive" "integer"]]))

(def ^:private sents (concat scalars accept/covered-arrays ["feel" :unknown]))

(defn- column [t] (str "c_" (str/replace (str/replace t "[]" "_array") #"\W" "_")))

(defn- taken?
  "Whether PostgreSQL prepares sql with $1 of type sent: false on a type error."
  [con sent sql]
  (try (jdbc/execute! con [(str "PREPARE probe(" (if (= :unknown sent) "unknown" sent) ") AS " sql)])
       (jdbc/execute! con ["DEALLOCATE probe"])
       true
       (catch java.sql.SQLException e
         (if (#{"42883" "42804" "42846" "42725"} (.getSQLState e)) false (throw e)))))

(deftest accept-is-postgresqls
  (when *db*
    (exec-sql! (str "CREATE TYPE feel AS ENUM ('ok', 'meh'); CREATE DOMAIN positive AS integer CHECK (VALUE > 0);"
                    "CREATE TABLE probe_target (" (str/join ", " (for [[t] targets] (str (column t) " " t))) ");"))
    (let [{:keys [host port db user password]} *db*]
      (with-open [con (jdbc/get-connection {:jdbcUrl (str "jdbc:postgresql://" host ":" port "/" db) :user user :password password})]
        (doseq [[t accepted-t] targets, sent sents
                :let [sent-t (if (= "feel" sent) [:enum "feel"] sent)]
                [context sql] {:compare (str "SELECT 1 FROM probe_target WHERE " (column t) " = $1")
                               :assign (str "INSERT INTO probe_target (" (column t) ") VALUES ($1)")
                               :cast (str "SELECT CAST($1 AS " t ")")}
                ;; PostgreSQL 18 adds explicit casts between bytea and the integers
                :when (not (and (= :cast context) (some #{"bytea"} [accepted-t sent]) (some #{"smallint" "integer" "bigint"} [accepted-t sent])))]
          (is (= (taken? con sent sql) (accept/accepts? accepted-t sent-t context))
              (str (pr-str sent) " " context " " t)))))))

(deftest sent-type-is-pgjdbcs
  (when *db*
    (let [{:keys [host port db user password]} *db*]
      (with-open [con (jdbc/get-connection {:jdbcUrl (str "jdbc:postgresql://" host ":" port "/" db) :user user :password password})]
        (doseq [v [1 (int 1) (short 1) 1M 1N (biginteger 1) 1.0 (float 1) true "1" \1 (java.util.UUID/randomUUID) (.getBytes "a")
                   (java.time.LocalDate/of 2026 1 1) (java.time.LocalTime/of 1 0) (java.time.OffsetTime/of 1 0 0 0 java.time.ZoneOffset/UTC)
                   (java.time.LocalDateTime/of 2026 1 1 0 0) (java.time.OffsetDateTime/of 2026 1 1 0 0 0 0 java.time.ZoneOffset/UTC)]
                t scalars
                ;; PostgreSQL 18 adds explicit casts between bytea and the integers
                :when (not (or (and (= "bytea" t) (integer? v)) (and (bytes? v) (#{"smallint" "integer" "bigint"} t))))
                ;; pgjdbc's setObject itself: next.jdbc.date-time, which another test may load, sends some java.time values otherwise
                :let [taken? (try (with-open [ps (.prepareStatement con (str "SELECT CAST(? AS " t ")"))]
                                    (.setObject ps 1 v)
                                    (.execute ps))
                                  true
                                  ;; another error is of the value, which the type took ("1" is no regclass)
                                  (catch java.sql.SQLException e (not (#{"42883" "42804" "42846" "42725"} (.getSQLState e)))))]]
          (is (= taken? (accept/accepts? t (accept/sent-type v {}) :cast)) (str (pr-str v) " " (class v) " " t)))))))

(deftest pgjdbc-write-says-what-goes-as-which-type
  (let [registry (pgmalli/registry {:registry {}})]
    (doseq [v [1 1M 1.0 true "1" (java.util.UUID/randomUUID) (.getBytes "a")
               (java.time.LocalDate/of 2026 1 1) (java.time.LocalTime/of 1 0) (java.time.OffsetTime/of 1 0 0 0 java.time.ZoneOffset/UTC)
               (java.time.LocalDateTime/of 2026 1 1 0 0) (java.time.OffsetDateTime/of 2026 1 1 0 0 0 0 java.time.ZoneOffset/UTC)]]
      (is (= [(accept/sent-type v {})] (for [[s t] accept/default-write :when (m/validate s v {:registry registry})] t))
          (str (class v) " goes as one type")))))

(deftest a-string-goes-untyped-under-stringtype-unspecified
  (when *db*
    (exec-sql! "CREATE TYPE untyped_mood AS ENUM ('ok'); CREATE TABLE untyped_probe (e untyped_mood, t text, i integer)")
    (let [{:keys [host port db user password]} *db*
          sent (accept/sent-type "1" {:write (accept/pgjdbc-write {:stringtype :unspecified})})]
      (is (= :unknown sent))
      (with-open [con (jdbc/get-connection {:jdbcUrl (str "jdbc:postgresql://" host ":" port "/" db "?stringtype=unspecified") :user user :password password})]
        (doseq [[col t v] [["e" [:enum "untyped_mood"] "ok"] ["t" "text" "1"] ["i" "integer" "1"]]
                [context sql] {:compare (str "SELECT 1 FROM untyped_probe WHERE " col " = ?")
                               :assign (str "INSERT INTO untyped_probe (" col ") VALUES (?)")}]
          (is (accept/accepts? t sent context))
          (is (with-open [ps (.prepareStatement con sql)] (.setObject ps 1 v) (.execute ps) true) (str col " " context)))))))
