(ns pgmalli.accept-test
  "pgmalli.impl.accept against pgjdbc and PostgreSQL themselves: the type setObject sends a
   value of each class as, and each sent type prepared where a value of each type goes.
   JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [pgmalli.core :as pgmalli]
            [pgmalli.impl.accept :as accept]
            [pgmalli.impl.jdbc :as pgjdbc]
            [pgmalli.impl.types :as types]
            [pgmalli.oracle :as oracle]
            [pgmalli.test-db :refer [*db* datasource exec-sql! with-postgres]])
  (:import (java.lang.reflect Array)
           (java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime)))

(use-fixtures :once with-postgres)

(def ^:private candidates
  "Classes besides class-types' an application may hand to setObject."
  (concat (keys oracle/values)
          (for [e (concat (keys oracle/boxed) (vals oracle/boxed) [String java.math.BigDecimal java.math.BigInteger java.util.UUID (Class/forName "[B")
                                                     LocalDate LocalTime OffsetTime LocalDateTime OffsetDateTime Instant
                                                     java.sql.Date java.sql.Time java.sql.Timestamp java.util.Date])]
            (class (Array/newInstance ^Class e 0)))))

(def ^:private left-out
  "Classes setObject sends that class-types leaves out, with why."
  {org.postgresql.util.PGobject "a PGobject names its type itself, value by value"
   java.sql.Date "goes untyped: a date column takes it as its own value (inst?); to a text column a date is a cast"
   java.sql.Time "goes untyped: a time column takes it as its own value (inst?); to a text column a time is a cast"
   java.sql.Timestamp "goes untyped: a timestamp column takes it as its own value (inst?); to a text column a timestamp is a cast"
   clojure.lang.PersistentArrayMap "a map goes as hstore only where that extension is there"
   clojure.lang.PersistentHashMap "a map goes as hstore only where that extension is there"})

(defn- type-name
  "t by the name types keys it by."
  [t]
  (if-let [e (types/element t)] (str (type-name e) "[]") (:name (types/entry t) t)))

(defn- sent-as
  "The type PostgreSQL gets v as through setObject: :unknown for a value pgjdbc sends untyped,
   nil for one setObject refuses."
  [con v]
  (with-open [ps (.prepareStatement con "SELECT pg_typeof(?)::text")]
    (when (try (.setObject ps 1 v) true (catch java.sql.SQLException _ false))
      (try (with-open [rs (.executeQuery ps)] (.next rs) (type-name (.getString rs 1)))
           (catch java.sql.SQLException e
             (if (= "42P18" (.getSQLState e)) :unknown (throw e)))))))

(deftest class-types-are-the-types-pgjdbc-sends
  (when *db*
    ;; pgjdbc sends a java.util.Map as an hstore where the database has one
    (exec-sql! "CREATE EXTENSION IF NOT EXISTS hstore")
    (with-open [con (jdbc/get-connection (datasource))]
      (doseq [c (distinct (concat (map first accept/class-types) candidates))
              :let [v (oracle/value c)]
              :when (not (left-out c))]
        ;; pgjdbc's setObject itself: next.jdbc.date-time, which another test may load, sends some java.time values otherwise
        (is (= (sent-as con v) (some (fn [[c t]] (when (instance? c v) t)) accept/class-types)) (.getName ^Class c))))))

(def ^:private targets
  "[column type, the type accept/accepts? takes for it]: every type of pg_catalog a column may be
   of (not a pseudo-type or the catalog's own) and of types, an array of each, an enum, and a domain as its base type."
  (concat (for [t (distinct (concat (for [[t {:keys [category]}] (:types @types/catalog) :when (not (#{"P" "X" "Z"} category))] t)
                                    (keys types/types)))
                t [t (str t "[]")]]
            [t t])
          [["feel" [:enum "feel"]] ["positive" "integer"]]))

(def ^:private sents
  "Every type a value may be sent as: pgjdbc's, and any other :send names (a map as jsonb, an
   extension's) that pgmalli.impl.accept answers for (known?), and an enum's and none."
  (distinct (concat (map second accept/class-types)
                    (filter #(and (string? %) (accept/known? %)) (map first targets))
                    ["feel" :unknown])))

(defn- column [t] (str "c_" (str/replace (str/replace t "[]" "_array") #"\W" "_")))

(defn- verdicts
  "{:t :sent :context :taken}: whether PostgreSQL prepares a parameter of type sent where a
   value of each target's type goes, by context, with accepts? and holds? on it."
  []
  (exec-sql! (str (apply str (for [e (distinct (keep :extension (vals types/types)))] (str "CREATE EXTENSION IF NOT EXISTS " e ";")))
                  "CREATE TYPE feel AS ENUM ('ok', 'meh'); CREATE DOMAIN positive AS integer CHECK (VALUE > 0);"
                  "CREATE TABLE probe_target (" (str/join ", " (for [[t] targets] (str (column t) " " t))) ");"
                  ;; one round trip for every pair: an error other than a type error is thrown
                  "CREATE FUNCTION taken(sql text) RETURNS boolean LANGUAGE plpgsql AS $$ BEGIN"
                  " EXECUTE sql; DEALLOCATE probe; RETURN true;"
                  " EXCEPTION WHEN SQLSTATE '42883' OR SQLSTATE '42804' OR SQLSTATE '42846' OR SQLSTATE '42725' THEN RETURN false; END $$"))
  (let [pairs (for [[t accepted-t] targets, sent sents
                    [context sql] {:compare (str "SELECT 1 FROM probe_target WHERE " (column t) " = $1")
                                   :assign (str "INSERT INTO probe_target (" (column t) ") VALUES ($1)")
                                   :cast (str "SELECT CAST($1 AS " t ")")}]
                {:t t :accepted-t accepted-t :sent sent :context context
                 :sql (str "PREPARE probe(" (if (= :unknown sent) "unknown" sent) ") AS " sql)})]
    (with-open [con (jdbc/get-connection (datasource))]
      (map #(assoc %1 :taken (:taken %2)) pairs
           (jdbc/execute! con ["SELECT taken(s) AS taken FROM unnest(?::text[]) WITH ORDINALITY AS u(s, n) ORDER BY n"
                               (into-array String (map :sql pairs))]
                          {:builder-fn rs/as-unqualified-maps})))))

(deftest accept-is-postgresqls
  (when *db*
    (doseq [:let [vs (verdicts)
                  compares-itself (set (for [{:keys [t sent context taken]} vs :when (and taken (= :compare context) (= t sent))] t))]
            {:keys [t accepted-t sent context taken]} vs
            :let [sent-t (if (= "feel" sent) [:enum "feel"] sent)
                  pair (str (pr-str sent) " " context " " t)]]
      ;; accepts? may take what PostgreSQL refuses (PostgreSQL 18 casts bytea and the integers), never the
      ;; other way; a type it does not answer for (known? false) types its arguments :any
      (when (and taken (accept/known? accepted-t)) (is (accept/accepts? accepted-t sent-t context) (str "PostgreSQL takes " pair)))
      ;; a type with no = (json, point, xml) compares with nothing, its own type neither
      (when (and (not= :cast context) (accept/holds? accepted-t sent-t) (not (and (= :compare context) (not (compares-itself t)))))
        (is taken (str "PostgreSQL refuses " pair))))))

(deftest the-schema-of-each-class-sent-takes-its-values
  (doseq [:let [registry (pgmalli/registry {:registry {}})]
          v [1 (int 1) (short 1) 1M 1N (biginteger 1) 1.0 (float 1) true "1" (java.util.UUID/randomUUID) (.getBytes "a")
             (java.time.LocalDate/of 2026 1 1) (java.time.LocalTime/of 1 0) (java.time.OffsetTime/of 1 0 0 0 java.time.ZoneOffset/UTC)
             (java.time.LocalDateTime/of 2026 1 1 0 0) (java.time.OffsetDateTime/of 2026 1 1 0 0 0 0 java.time.ZoneOffset/UTC)]]
    (is (some (fn [[s t]] (and (= t (pgjdbc/sent-type v {})) (m/validate s v {:registry registry}))) (pgjdbc/write-rows {} false))
        (str (class v) " goes as its type"))))

(deftest a-string-goes-untyped-under-stringtype-unspecified
  (when *db*
    (exec-sql! "CREATE TYPE untyped_mood AS ENUM ('ok'); CREATE TABLE untyped_probe (e untyped_mood, t text, i integer)")
    (let [sent (pgjdbc/sent-type "1" {:send {String :unknown}})]
      (is (= :unknown sent))
      (with-open [con (jdbc/get-connection (datasource (update *db* :dbname str "?stringtype=unspecified")))]
        (doseq [[col t v] [["e" [:enum "untyped_mood"] "ok"] ["t" "text" "1"] ["i" "integer" "1"]]
                [context sql] {:compare (str "SELECT 1 FROM untyped_probe WHERE " col " = ?")
                               :assign (str "INSERT INTO untyped_probe (" col ") VALUES (?)")}]
          (is (accept/accepts? t sent context))
          (is (with-open [ps (.prepareStatement con sql)] (.setObject ps 1 v) (.execute ps) true) (str col " " context)))))))
