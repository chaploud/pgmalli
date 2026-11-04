(ns pgmalli-next.accept-test
  "pgmalli-next.impl.accept against the one sending and PostgreSQL themselves: the type a value of
   each class goes as, and each sent type prepared where a value of each type goes.
   JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as rs]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.files :as files]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.jdbc :as pgjdbc]
            [pgmalli-next.impl.types :as types]
            [pgmalli-next.oracle :as oracle]
            [pgmalli-next.test-db :refer [*db* datasource exec-sql! with-postgres]])
  (:import (java.lang.reflect Array)
           (java.time Instant LocalDate LocalDateTime LocalTime OffsetDateTime OffsetTime)))

(use-fixtures :once with-postgres)

(def ^:private candidates
  "Classes besides class-types' an application may send."
  (concat (keys oracle/values)
          (for [e (concat (keys oracle/boxed) (vals oracle/boxed) [String java.math.BigDecimal java.math.BigInteger java.util.UUID (Class/forName "[B")
                                                     LocalDate LocalTime OffsetTime LocalDateTime OffsetDateTime Instant
                                                     java.sql.Date java.sql.Time java.sql.Timestamp java.util.Date])]
            (class (Array/newInstance ^Class e 0)))))

(def ^:private left-out
  "Classes the one sending sends that class-types leaves out, with why."
  {org.postgresql.util.PGobject "a PGobject names its type itself, value by value"})

(defn- type-name
  "t by the name types keys it by."
  [t]
  (if-let [e (types/element t)] (str (type-name e) "[]") (:name (types/entry t) t)))

(defn- sent-as
  "The type PostgreSQL gets v as, sent as the one sending sends it (the extension of
   docs/reading-rows.md, which pgmalli-next.test-db installs): :unknown for a value sent untyped, nil
   for one it cannot send."
  [con v]
  (with-open [ps (.prepareStatement con "SELECT pg_typeof(?)::text")]
    (when (try (prepare/set-parameter v ps 1) true (catch Exception _ false))
      (try (with-open [rs (.executeQuery ps)] (.next rs) (type-name (.getString rs 1)))
           (catch java.sql.SQLException e
             (if (= "42P18" (.getSQLState e)) :unknown (throw e)))))))

(deftest class-types-are-the-types-the-one-sending-sends
  (when *db*
    ;; pgjdbc sends a java.util.Map as an hstore where the database has one: the extension sends a map as jsonb before it
    (exec-sql! "CREATE EXTENSION IF NOT EXISTS hstore")
    (with-open [con (jdbc/get-connection (datasource))]
      (doseq [c (distinct (concat (map first accept/class-types) candidates [clojure.lang.PersistentVector clojure.lang.PersistentHashMap]))
              :let [v (case (.getName ^Class c) "clojure.lang.PersistentVector" [1] "clojure.lang.PersistentHashMap" (hash-map :a 1) (oracle/value c))]
              :when (not (left-out c))]
        (is (= (sent-as con v) (some (fn [[c t]] (when (instance? c v) t)) accept/class-types)) (.getName ^Class c)))
      (is (= :unknown (sent-as con :ns/a)) "a qualified keyword goes as its text, ns/a"))))

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
   extension's) that pgmalli-next.impl.accept answers for (answers?), and an enum's and none."
  (distinct (concat (map second accept/class-types)
                    (filter #(and (string? %) (accept/answers? %)) (map first targets))
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

(def ^:private stricter-file "test/resources/pgmalli-next/stricter.edn")

(defn- stricter
  "{[sent context] [t ...]}: of verdicts vs, where PostgreSQL takes a parameter sent compared or
   assigned that holds? leaves out (docs/queries.md \"Stricter than PostgreSQL\")."
  [vs]
  (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
        (update-vals (group-by (juxt :sent :context)
                               (filter (fn [{:keys [accepted-t sent context taken]}]
                                         (and taken (not= :cast context)
                                              (not (accept/holds? accepted-t (if (= "feel" sent) [:enum "feel"] sent) context))))
                                       vs))
                     #(vec (sort (map :t %))))))

(defn- triples [m] (set (for [[[sent context] ts] m t ts] [sent context t])))

(deftest accept-is-postgresqls
  (when *db*
    (let [vs (verdicts)
          compares-itself (set (for [{:keys [t sent context taken]} vs :when (and taken (= :compare context) (= t sent))] t))]
      (doseq [{:keys [t accepted-t sent context taken]} vs
              :let [sent-t (if (= "feel" sent) [:enum "feel"] sent)
                    pair (str (pr-str sent) " " context " " t)]]
        ;; accepts? may take what PostgreSQL refuses (PostgreSQL 18 casts bytea and the integers), never the
        ;; other way; a type it does not answer for (answers? false) types its arguments :any
        (when (and taken (accept/answers? accepted-t)) (is (accept/accepts? accepted-t sent-t context) (str "PostgreSQL takes " pair)))
        ;; a type with no = (json, point, xml) compares with nothing, its own type neither
        (when (and (not= :cast context) (accept/holds? accepted-t sent-t) (not (and (= :compare context) (not (compares-itself t)))))
          (is taken (str "PostgreSQL refuses " pair))))
      ;; the other way, where holds? is stricter than PostgreSQL, pinned: a change of it is a choice
      (let [now (stricter vs)]
        (if (System/getenv "PGMALLI_UPDATE_STRICTER")
          (spit stricter-file (with-out-str (println "{") (doseq [[k ts] now] (pr k) (print " ") (prn ts)) (println "}")))
          (let [was (triples (edn/read-string (slurp stricter-file)))
                [left-out held] (map #(sort-by pr-str %) [(remove was (triples now)) (remove (triples now) was)])]
            (is (and (empty? left-out) (empty? held))
                (str "where PostgreSQL takes a parameter, holds? leaves out otherwise than " stricter-file
                     " ([sent context column-type]); bb test:stricter rewrites it"
                     (when (seq left-out) (str "\n  now left out: " (str/join " " (map pr-str left-out))))
                     (when (seq held) (str "\n  now held: " (str/join " " (map pr-str held))))))))))))

(deftest a-jsonb-cast-to-a-scalar-type-takes-a-scalar-alone
  (when *db*
    (with-open [con (jdbc/get-connection (datasource))]
      (let [casts? (fn [doc t] (try (jdbc/execute! con [(str "SELECT CAST(?::jsonb AS " t ")") doc]) true
                                    (catch java.sql.SQLException _ false)))]
        (doseq [t ["integer" "numeric" "double precision" "boolean"]
                doc ["{\"a\": 1}" "[1]"]]
          (is (accept/accepts? t "jsonb" :cast) t)
          (is (not (casts? doc t)) (str doc " to " t))
          (is (accept/scalar-cast? t "jsonb") t))
        (doseq [t ["text" "character varying" "json" "jsonb"]
                doc ["{\"a\": 1}" "[1]"]]
          (is (casts? doc t) (str doc " to " t))
          (is (not (accept/scalar-cast? t "jsonb")) t))
        (is (casts? "1" "integer"))
        (is (casts? "true" "boolean"))))))

(deftest an-untyped-array-literal-cast-to-a-number-or-boolean-errors
  (when *db*
    (with-open [con (jdbc/get-connection (datasource))]
      (let [casts? (fn [s t] (try (with-open [ps (.prepareStatement con (str "SELECT CAST(? AS " t ")"))]
                                    (.setObject ps 1 s java.sql.Types/OTHER)
                                    (.close (.executeQuery ps))
                                    true)
                                  (catch java.sql.SQLException _ false)))]
        (doseq [t ["integer" "bigint" "numeric" "double precision" "money" "oid" "regclass" "boolean"]
                s ["{}" "{1}" "{1,2}" "{true}" "{pg_class}"]]
          (is (accept/scalar-cast? t :unknown) t)
          (is (not (casts? s t)) (str s " to " t)))
        ;; the date and time parser skips the braces
        (doseq [[s t] [["{2020-01-01}" "timestamp with time zone"] ["{2020-01-01}" "date"] ["{1 day}" "interval"]
                       ["{a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11}" "uuid"] ["{1}" "text"] ["{1}" "integer[]"]]]
          (is (not (accept/scalar-cast? t :unknown)) t)
          (is (casts? s t) (str s " to " t)))))))

(deftest the-schema-of-each-class-sent-takes-its-values
  (doseq [:let [registry (pgmalli/registry {:registry {}})]
          v [1 (int 1) (short 1) 1M 1N (biginteger 1) 1.0 (float 1) true "1" (java.util.UUID/randomUUID) (.getBytes "a")
             (java.time.LocalDate/of 2026 1 1) (java.time.LocalTime/of 1 0) (java.time.OffsetTime/of 1 0 0 0 java.time.ZoneOffset/UTC)
             (java.time.LocalDateTime/of 2026 1 1 0 0) (java.time.OffsetDateTime/of 2026 1 1 0 0 0 0 java.time.ZoneOffset/UTC)]]
    (is (some (fn [[s t]] (and (= t (pgjdbc/sent-type v {})) (m/validate s v {:registry registry}))) (pgjdbc/write-rows {} false))
        (str (class v) " goes as its type"))))

(defn- refusal
  "PostgreSQL's refusal of sqlvec sent as the one sending sends it: its SQLSTATE, nil where it runs it."
  [con sqlvec]
  (try (jdbc/execute! con sqlvec) nil
       (catch java.sql.SQLException e (.getSQLState e))))

(deftest a-parameter-postgresql-types-from-the-value-alone-takes-a-value-sent-typed
  ;; "any" (concat, format, jsonb_build_object): PostgreSQL types the parameter from what is sent
  (when *db*
    (let [registry (pgmalli/registry {:registry {}})
          values [1 "a" 1.5M true (java.util.UUID/randomUUID) (java.time.OffsetDateTime/now) {:a 1} [1] (long-array [1])
                  :a nil (java.time.Instant/now) (java.time.LocalDate/of 2026 1 1) (java.time.LocalDateTime/of 2026 1 1 0 0) (java.util.Date.)]]
      (with-open [con (jdbc/get-connection (datasource))]
        (doseq [q [{:select [[[:concat :?x] :v]]} {:select [[[:format "%s" :?x] :v]]} {:select [[[:jsonb_build_object "k" :?x] :v]]}
                   {:select [[[:json_build_array 1 :?x] :v]]} {:select [[[:num_nulls :?x] :v]]}]
                :let [s (:x (:args (h/analyze registry q)))]
                v values
                :let [state (refusal con (sql/format q {:params {:x v}}))]]
          (testing (pr-str q (cond-> v (some-> v class .isArray) vec))
            (is (= (nil? state) (m/validate s v {:registry registry})) (str "PostgreSQL: " (or state "takes it")))
            (when state (is (= "42P18" state) "could not determine data type of parameter"))))))))

(deftest an-enum-takes-a-keyword-label-and-a-string-label-cast
  (when *db*
    (exec-sql! "CREATE TYPE sent_mood AS ENUM ('ok', 'meh'); CREATE TABLE sent_probe (e sent_mood)")
    (let [registry (pgmalli/registry (files/from-database {:db *db*} "public"))]
      (with-open [con (jdbc/get-connection (datasource))]
        (doseq [q [{:select [:e] :from [:sent_probe] :where [:= :e :?x]}
                   {:insert-into :sent_probe :values [{:e :?x}]}
                   {:update :sent_probe :set {:e :?x}}
                   {:select [:e] :from [:sent_probe] :where [:= :e [:cast :?x :sent_mood]]}]
                v [:ok "ok"]
                :let [s (:x (:args (h/analyze registry q)))
                      state (do (.setAutoCommit con false) (try (refusal con (sql/format q {:params {:x v}})) (finally (.rollback con) (.setAutoCommit con true))))]]
          (testing (pr-str q v)
            (is (= (nil? state) (m/validate s v {:registry registry})) (str "PostgreSQL: " (or state "takes it")))))))))

(deftest the-docs-table-of-what-is-sent-is-accepts-class-types
  ;; docs/queries.md's "What your application sends": each row of classes and types written as
  ;; `Class` and `type`, one type for all its classes or one for each
  (let [section (second (re-find #"(?s)### What your application sends(.*?)\nAnother class" (slurp "docs/queries.md")))
        by-name (into {} (for [[c t] accept/class-types] [(.getSimpleName ^Class c) t]))
        rows (for [l (str/split-lines section)
                   :let [[_ cs ts] (re-matches #"\| (.*?) \| (.*?) \|" l)]
                   :when (and cs (re-find #"^`[A-Z]" cs))
                   :let [classes (map second (re-seq #"`([A-Za-z][\w.]*)`" cs))
                         sent (map second (re-seq #"`([a-z][\w ]*)`" ts))]
                   :when (and (seq sent) (not (str/starts-with? ts "untyped")))]
               [classes sent])]
    (is (< 3 (count rows)) "the table is found")
    (doseq [[classes sent] rows
            [c t] (map vector classes (if (next sent) sent (repeat (first sent))))]
      (is (= (types/canonical-name t) (types/canonical-name (by-name c))) (str c " in docs/queries.md")))))
