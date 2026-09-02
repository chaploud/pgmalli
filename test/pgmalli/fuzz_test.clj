(ns pgmalli.fuzz-test
  "Round trip of generated expressions: data -> HoneySQL -> CHECK constraint in PostgreSQL ->
   pg_get_constraintdef -> parse -> canonical, compared modulo the rewrites PostgreSQL makes.
   PGMALLI_FUZZ_SEED changes the seed (default 42, fixed in CI)."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [honey.sql :as sql]
            [malli.generator :as mg]
            [pgmalli.impl.eval :as ev]
            [pgmalli.impl.expr :as x]
            [pgmalli.impl.ir :as ir]
            [pgmalli.test-db :refer [*container* *db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(def ^:private columns "n integer, m integer, s text, t text, e mood, b boolean, d numeric(10,2)")

(def ^:private create-mood
  ;; both tests in this namespace need the type, in either order
  "DO $$ BEGIN CREATE TYPE mood AS ENUM ('happy', 'sad'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;\n")

(def ^:private expr-schema
  [:schema {:registry
            {::int-col [:enum :n :m]
             ::str-col [:enum :s :t]
             ::int-lit [:int {:min -5 :max 100}]
             ::str-lit [:enum "a" "b" "c" "it's"]
             ::cmp [:enum :< :> :<= :>= := :<>]
             ::atom [:or
                     [:tuple ::cmp ::int-col ::int-lit]
                     [:tuple ::cmp ::int-col ::int-col]
                     [:tuple ::cmp ::str-col ::str-lit]
                     [:tuple [:= :in] ::str-col [:vector {:min 1 :max 4} ::str-lit]]
                     [:tuple [:= :in] ::int-col [:vector {:min 1 :max 4} ::int-lit]]
                     [:tuple [:= :in] [:= :e] [:vector {:min 1 :max 2} [:enum "happy" "sad"]]]
                     [:tuple [:= :is] [:or ::int-col ::str-col] :nil]
                     [:tuple [:= :is-not] [:or ::int-col ::str-col] :nil]
                     [:tuple [:= :between] ::int-col ::int-lit ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :length] [:tuple [:= :trim] ::str-col]] [:= 0]]
                     [:tuple [:= :=] [:= :b] :boolean]
                     [:tuple [:= :>=] [:= :d] [:double {:min 0.0 :max 9.99}]]
                     [:tuple [:= :>] [:tuple [:= :+] ::int-col ::int-col] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :-] ::int-col ::int-lit] ::int-lit]
                     [:tuple [:= :<] [:tuple [:= :*] ::int-col ::int-lit] ::int-lit]
                     [:tuple [:= :=] [:tuple [:= :/] ::int-col [:int {:min 1 :max 5}]] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :coalesce] ::int-col ::int-col] ::int-lit]
                     [:tuple [:= :in] ::int-col [:tuple ::int-lit ::int-col]]
                     [:tuple [:= :not-in] ::str-col [:vector {:min 1 :max 3} ::str-lit]]
                     [:tuple [:= :=] [:tuple [:= :lower] ::str-col] ::str-lit]
                     [:tuple [:= :=] [:tuple [:= :btrim] ::str-col] ::str-lit]
                     [:tuple [:= :<=] [:tuple [:= :octet_length] ::str-col] ::int-lit]
                     [:tuple [:= :regex] ::str-col [:enum "^[[:alpha:]]+$" "^a" "'"]]
                     [:tuple [:= :case] [:tuple [:= :=] [:= :e] [:= "happy"]] [:tuple [:= :>] ::int-col [:= 0]] [:= :else] [:tuple [:= :is] ::int-col :nil]]]
             ::expr [:or
                     ::atom
                     [:tuple [:= :not] [:ref ::expr]]
                     [:tuple [:= :and] [:ref ::expr] [:ref ::expr]]
                     [:tuple [:= :or] [:ref ::expr] [:ref ::expr]]]}}
   ::expr])

(defn- normalize
  "Both sides: canonical, casts removed, BETWEEN expanded, IN over columns as ORs of =
   (PostgreSQL stores it so), nested AND/OR flattened, doubles as two-decimal strings
   (numeric(10,2) rounds)."
  [e]
  (walk/postwalk
   (fn [f]
     (cond
       (and (vector? f) (= :cast (first f))) (second f)
       (and (vector? f) (= :in (first f)) (some keyword? (nth f 2))) (into [:or] (map (fn [v] [:= (second f) v]) (nth f 2)))
       (and (vector? f) (= :between (first f))) [:and [:>= (second f) (nth f 2)] [:<= (second f) (nth f 3)]]
       (and (vector? f) (#{:and :or} (first f)))
       (into [(first f)] (mapcat (fn [g] (if (and (vector? g) (= (first f) (first g))) (rest g) [g])) (rest f)))
       (double? f) (format "%.2f" f)
       (and (number? f) (not (integer? f))) (format "%.2f" (double f))
       :else f))
   (x/canonical e)))

(deftest expression-roundtrip
  (when *db*
    (let [seed (parse-long (or (System/getenv "PGMALLI_FUZZ_SEED") "42"))
          ;; identical constraint definitions collapse into one, so keep the expressions distinct
          exprs (vec (distinct (mg/sample expr-schema {:size 60 :seed seed})))
          ddl (str create-mood "CREATE TABLE fz (" columns
                   (apply str (map-indexed (fn [i e] (str ",\n  CONSTRAINT c" i " CHECK (" (first (sql/format (x/->honeysql e) {:inline true})) ")")) exprs))
                   ");")
          _ (exec-sql! ddl)
          constraints (get-in (ir/from-db *db*) [:tables "fz" :constraints])]
      (testing (str "seed " seed ", " (count exprs) " expressions")
        (doseq [[i e] (map-indexed vector exprs)]
          (let [clause (get-in constraints [(str "c" i) :check_clause])
                parsed (if clause (x/try-parse (subs clause 6)) {:error "constraint missing from the catalog"})]
            (is (nil? (:error parsed)) (str "c" i " " (pr-str e) " " clause))
            (is (= (normalize e) (normalize (:expr parsed)))
                (str "input " (pr-str e) "\npg    " clause))))))))

(def ^:private row-schema
  [:map [:n [:maybe [:int {:min -6 :max 101}]]] [:m [:maybe [:int {:min -6 :max 101}]]]
   [:s [:maybe [:enum "a" "b" "c" "it's" " a " ""]]] [:t [:maybe [:enum "a" "b" "c" "it's" " a " ""]]]
   [:e [:maybe [:enum "happy" "sad"]]] [:b [:maybe :boolean]]
   ;; only values numeric(10,2) can hold, as rows read from the database would be
   [:d [:maybe [:enum -1.0 0.0 0.01 0.5 2.25 9.99]]]])

(defn- sql-literal [column v]
  (cond (nil? v) "NULL"
        (string? v) (str "'" (str/replace v "'" "''") "'" (when (= column :e) "::mood"))
        (double? v) (format "%.2f" v)
        :else (str v)))

(defn- postgres-verdicts
  "For every row, whether each expression IS NOT FALSE according to PostgreSQL."
  [exprs rows]
  (let [cols [:n :m :s :t :e :b :d]
        values (str/join ",\n" (map (fn [r] (str "(" (str/join ", " (map #(sql-literal % (get r %)) cols)) ")")) rows))
        selects (str/join ", " (map #(str "((" (first (sql/format (x/->honeysql %) {:inline true})) ") IS NOT FALSE)") exprs))
        sql (str create-mood "CREATE TABLE judge (id serial, " columns ");\n"
                 "INSERT INTO judge (n, m, s, t, e, b, d) VALUES\n" values ";\n"
                 "SELECT " selects " FROM judge ORDER BY id;")
        {:keys [exit out err]} (p/sh ["docker" "exec" "-i" *container* "psql" "-X" "-q" "-A" "-t" "-F" "\t"
                                      "-v" "ON_ERROR_STOP=1" "-U" "postgres" "-d" (:db *db*)]
                                     {:in sql})]
    (when-not (zero? exit) (throw (ex-info err {})))
    (mapv #(mapv (fn [v] (= "t" v)) (str/split % #"\t")) (str/split-lines (str/trim out)))))

(deftest evaluated-checks-agree-with-postgres
  (when *db*
    (let [seed (parse-long (or (System/getenv "PGMALLI_FUZZ_SEED") "42"))
          exprs (vec (distinct (mg/sample expr-schema {:size 80 :seed seed})))
          rows (vec (distinct (mg/sample row-schema {:size 60 :seed seed})))
          verdicts (postgres-verdicts exprs rows)]
      (testing (str "seed " seed ", " (count exprs) " expressions x " (count rows) " rows")
        (doseq [[j row] (map-indexed vector rows)
                [i e] (map-indexed vector exprs)]
          (is (= (get-in verdicts [j i]) (ev/passes? e row))
              (str (pr-str e) " on " (pr-str row))))))))
