(ns pgmalli.accept-test
  "pgmalli.impl.accept against PostgreSQL itself: each sent type prepared where each type goes.
   JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [next.jdbc :as jdbc]
            [pgmalli.impl.accept :as accept]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(def ^:private scalars accept/covered)

(def ^:private targets
  "[column type, the type accept/accepts? takes for it]: a domain is its base type."
  (concat (map (juxt identity identity) (concat scalars (filter accept/known? ["bigint[]" "character varying[]" "text[]" "integer[]"])))
          [["feel" [:enum "feel"]] ["positive" "integer"]]))

(def ^:private sents (concat scalars ["bigint[]" "character varying[]" "text[]" "integer[]" "feel" :unknown]))

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
                :when (not (and (= :cast context) (some #{"bytea"} [t sent]) (some #{"smallint" "integer" "bigint"} [t sent])))]
          (is (= (taken? con sent sql) (accept/accepts? accepted-t sent-t context))
              (str (pr-str sent) " " context " " t)))))))
