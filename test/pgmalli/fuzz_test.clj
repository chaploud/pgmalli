(ns pgmalli.fuzz-test
  "Round trip of generated expressions: data -> HoneySQL -> CHECK constraint in PostgreSQL ->
   pg_get_constraintdef -> parse -> canonical, compared modulo the rewrites PostgreSQL makes.
   PGMALLI_FUZZ_SEED changes the seed (default 42, fixed in CI)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.walk :as walk]
            [honey.sql :as sql]
            [malli.generator :as mg]
            [pgmalli.impl.expr :as x]
            [pgmalli.impl.ir :as ir]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(def ^:private columns "n integer, m integer, s text, t text, e mood, b boolean, d numeric(10,2)")

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
                     [:tuple [:= :>=] [:= :d] [:double {:min 0.0 :max 9.99}]]]
             ::expr [:or
                     ::atom
                     [:tuple [:= :not] [:ref ::expr]]
                     [:tuple [:= :and] [:ref ::expr] [:ref ::expr]]
                     [:tuple [:= :or] [:ref ::expr] [:ref ::expr]]]}}
   ::expr])

(defn- normalize
  "Both sides: canonical, casts removed, BETWEEN expanded, nested AND/OR flattened,
   doubles as two-decimal strings (numeric(10,2) rounds)."
  [e]
  (walk/postwalk
   (fn [f]
     (cond
       (and (vector? f) (= :cast (first f))) (second f)
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
          ddl (str "CREATE TYPE mood AS ENUM ('happy', 'sad');\nCREATE TABLE fz (" columns
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
