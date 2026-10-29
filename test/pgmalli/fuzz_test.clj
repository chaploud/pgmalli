(ns pgmalli.fuzz-test
  "Round trip of generated expressions: data -> HoneySQL -> CHECK constraint in PostgreSQL ->
   pg_get_constraintdef -> parse -> canonical, compared modulo the rewrites PostgreSQL makes;
   and the evaluator's verdicts on generated rows compared with PostgreSQL's own.
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
            [pgmalli.impl.json :as json]
            [pgmalli.test-db :as test-db :refer [*container* *db* exec-sql! with-postgres]]
            [pgmalli.test-seed :as ts]))

(use-fixtures :once with-postgres)

(doseq [op ["@>" "<@" "->" "->>" "#>" "#>>" "~" "~*" "!~" "!~*" "&&" "^"]]
  (sql/register-op! (keyword op)))

(def ^:private columns
  "n integer, m integer, s text, t text, e mood, b boolean, d numeric(10,2), j jsonb, arr text[], dt date, ts timestamptz, u uuid, c char(4)")

(def ^:private create-mood
  ;; both tests in this namespace need the type, in either order
  "DO $$ BEGIN CREATE TYPE mood AS ENUM ('happy', 'sad'); EXCEPTION WHEN duplicate_object THEN NULL; END $$;\n")

(def ^:private uuids ["7c9e6679-7425-40de-944b-e07fc1f90ae7" "16fd2706-8baf-433b-82eb-8c7fada847da"])

(def ^:private expr-schema
  [:schema {:registry
            {::int-col [:enum :n :m]
             ::str-col [:enum :s :t]
             ::int-lit [:int {:min -5 :max 100}]
             ::str-lit [:enum "a" "b" "c" "it's"]
             ::cmp [:enum :< :> :<= :>= := :<>]
             ::jkey [:enum "a" "b" "c"]
             ;; literals in the text PostgreSQL prints, so the round trip compares equal
             ::jlit [:enum "{}" "{\"a\": 1}" "{\"a\": \"x\"}" "[1, 2]" "{\"a\": {\"b\": 1}}" "[]" "[1]" "{\"a\": [1]}"]
             ::date-lit [:enum "2020-01-01" "2020-06-15" "2021-01-01"]
             ::ts-lit [:enum "2020-01-01 00:00:00+00" "2020-06-15 12:30:00+00"]
             ::interval-lit [:enum "1 day" "01:30:00" "2 days" "00:00:30"]
             ::like-lit [:enum "a%" "%b" "_" "a\\_b" "%'%" "it%" "é%"]
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
                     [:tuple [:= :>=] [:= :d] [:enum 0.0 0.5 2.25 9.99]]
                     [:tuple [:= :>] [:tuple [:= :+] ::int-col ::int-col] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :-] ::int-col ::int-lit] ::int-lit]
                     [:tuple [:= :<] [:tuple [:= :*] ::int-col ::int-lit] ::int-lit]
                     [:tuple [:= :=] [:tuple [:= :/] ::int-col [:int {:min 1 :max 5}]] ::int-lit]
                     [:tuple [:= :=] [:tuple [:= :mod] ::int-col [:int {:min 1 :max 5}]] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :pow] ::int-col [:int {:min 0 :max 3}]] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :abs] ::int-col] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :coalesce] ::int-col ::int-col] ::int-lit]
                     [:tuple [:= :=] [:tuple [:= :nullif] ::int-col ::int-col] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :greatest] ::int-col ::int-col] ::int-lit]
                     [:tuple [:= :<] [:tuple [:= :least] ::int-col ::int-lit] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :round] [:= :d]] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :floor] [:= :d]] ::int-lit]
                     [:tuple [:= :>] [:tuple [:= :/] [:= :d] [:int {:min 1 :max 5}]] [:enum 0.0 0.75 1.5]]
                     [:tuple [:= :in] ::int-col [:tuple ::int-lit ::int-col]]
                     [:tuple [:= :not-in] ::str-col [:vector {:min 1 :max 3} ::str-lit]]
                     [:tuple [:= :=] [:tuple [:= :lower] ::str-col] ::str-lit]
                     [:tuple [:= :=] [:tuple [:= :btrim] ::str-col] ::str-lit]
                     [:tuple [:= :<=] [:tuple [:= :octet_length] ::str-col] ::int-lit]
                     [:tuple [:= :=] [:tuple [:= :||] ::str-col ::str-col] ::str-lit]
                     [:tuple [:= :=] [:tuple [:= :substr] ::str-col [:int {:min 1 :max 3}] [:int {:min 0 :max 3}]] ::str-lit]
                     [:tuple [:= :=] [:tuple [:= :left] ::str-col [:int {:min -2 :max 3}]] ::str-lit]
                     [:tuple [:= :=] [:tuple [:= :replace] ::str-col ::str-lit ::str-lit] ::str-lit]
                     [:tuple [:= :is-distinct-from] ::str-col ::str-col]
                     [:tuple [:= :regex] ::str-col [:enum "^[[:alpha:]]+$" "^a" "'" "^\\w+$" "^a$"]]
                     [:tuple [:= :iregex] ::str-col [:enum "^é" "^a$"]]
                     [:tuple [:= :=] [:tuple [:= :cast] [:tuple [:= :*] ::int-col [:enum 0.5M 1.5M]] [:= :integer]] ::int-lit]
                     [:tuple [:= :=] [:tuple [:= :round] [:tuple [:= :+] [:= :d] [:enum 2.5M -0.5M 1.25M]]] ::int-lit]
                     [:tuple [:= :not-regex] ::str-col [:enum "^a" "b$"]]
                     [:tuple [:enum :like :ilike :not-like :not-ilike] ::str-col ::like-lit]
                     [:tuple [:= :=] [:tuple [:= :->>] [:= :j] ::jkey] ::str-lit]
                     [:tuple [:= :=] [:tuple [:= :jsonb_typeof] [:tuple [:= :->] [:= :j] ::jkey]] [:enum "number" "string" "object" "array" "null"]]
                     [:tuple [:= :contains] [:= :j] [:tuple [:= :cast] ::jlit [:= :jsonb]]]
                     [:tuple [:= :contained-by] [:= :j] [:tuple [:= :cast] ::jlit [:= :jsonb]]]
                     [:tuple [:= :=] [:= :j] [:tuple [:= :cast] ::jlit [:= :jsonb]]]
                     [:tuple [:= :=] [:tuple [:= :-] [:= :j] ::jkey] [:tuple [:= :cast] ::jlit [:= :jsonb]]]
                     [:tuple [:= :=] [:tuple [:= :json-path-text] [:= :j] [:tuple [:= :cast] [:enum "{a,b}" "{a}" "{0}"] [:= :text-array]]] ::str-lit]
                     [:tuple [:= :=] [:tuple [:= :array_length] [:= :arr] [:= 1]] ::int-lit]
                     [:tuple [:= :=] [:tuple [:= :cardinality] [:= :arr]] ::int-lit]
                     [:tuple [:= :=] [:tuple [:= :subscript] [:= :arr] [:int {:min 1 :max 3}]] ::str-lit]
                     [:tuple [:= :=] ::str-lit [:tuple [:= :any] [:= :arr]]]
                     [:tuple [:= :overlaps] [:= :arr] [:tuple [:= :array] [:vector {:min 1 :max 2} ::str-lit]]]
                     [:tuple [:= :contains] [:= :arr] [:tuple [:= :array] [:vector {:min 1 :max 2} ::str-lit]]]
                     [:tuple ::cmp [:= :dt] [:tuple [:= :cast] ::date-lit [:= :date]]]
                     [:tuple ::cmp [:= :ts] [:tuple [:= :cast] ::ts-lit [:= :timestamptz]]]
                     [:tuple ::cmp [:tuple [:= :+] [:= :dt] [:tuple [:= :cast] ::interval-lit [:= :interval]]] [:tuple [:= :cast] ::date-lit [:= :date]]]
                     [:tuple ::cmp [:tuple [:= :-] [:= :dt] [:tuple [:= :cast] ::date-lit [:= :date]]] ::int-lit]
                     [:tuple ::cmp [:tuple [:= :+] [:= :ts] [:tuple [:= :cast] ::interval-lit [:= :interval]]] [:tuple [:= :cast] ::ts-lit [:= :timestamptz]]]
                     [:tuple [:enum := :<>] [:= :u] [:tuple [:= :cast] [:enum "7c9e6679-7425-40de-944b-e07fc1f90ae7" "16fd2706-8baf-433b-82eb-8c7fada847da"] [:= :uuid]]]
                     [:tuple ::cmp [:tuple [:= :length] ::str-col] [:int {:min 0 :max 3}]]
                     [:tuple [:= :=] [:tuple [:= :cast] ::str-col [:= :boolean]] :boolean]
                     [:tuple [:enum := :<>] [:= :c] [:enum "ab" "a" "ab  " ""]]
                     [:tuple [:= :=] [:= :c] ::str-col]
                     [:tuple ::cmp [:tuple [:enum :length :octet_length] [:= :c]] [:int {:min 0 :max 4}]]
                     [:tuple [:enum :like :not-like] [:= :c] [:enum "a%" "%b" "%b%" "ab__"]]
                     [:tuple [:enum :regex :not-regex] [:= :c] [:enum "b$" "^a" "b *$"]]
                     [:tuple [:= :=] [:tuple [:= :||] [:= :c] [:= "x"]] [:enum "abx" "ab  x"]]
                     [:tuple [:= :>] [:tuple [:= :+] ::int-col [:= 2147483647]] [:= 0]]
                     [:tuple [:= :<] [:tuple [:= :*] ::int-col [:= 100000000]] [:= 0]]
                     [:tuple [:= :<] [:tuple [:= :-] ::int-col] ::int-lit]
                     [:tuple [:= :=] [:tuple [:= :cast] [:= :d] [:= :integer]] [:int {:min -1 :max 10}]]
                     [:tuple [:= :=] [:tuple [:= :cast] [:tuple [:= :cast] [:= :d] [:= :double-precision]] [:= :integer]] [:int {:min -1 :max 10}]]
                     [:tuple [:= :case] [:tuple [:= :=] [:= :e] [:= "happy"]] [:tuple [:= :>] ::int-col [:= 0]] [:= :else] [:tuple [:= :is] ::int-col :nil]]
                     [:tuple ::cmp [:tuple [:= :+] [:= :ts] [:tuple [:enum :* :/] [:tuple [:= :cast] ::interval-lit [:= :interval]] [:enum 2 3 0.5M 1.5M]]] [:tuple [:= :+] [:= :ts] [:tuple [:= :cast] ::interval-lit [:= :interval]]]]
                     [:tuple [:= :=] [:tuple [:= :trunc] [:= :d] [:int {:min -1 :max 2}]] [:enum 0 2 2.2M 2.25M 9.9M]]
                     [:tuple [:= :=] [:tuple [:= :/] [:= :d] [:enum 3 7]] [:enum 0.33333333333333333333M -0.33333333333333333333M 0.83333333333333333333M 0.75M]]
                     [:tuple [:= :=] [:tuple [:= :->>] [:= :j] ::jkey] [:enum "1" "1.50" "1.5"]]
                     [:tuple [:= :=] [:tuple [:enum :btrim :ltrim :rtrim] ::str-col [:enum "😀" "a"]] [:enum "" "a" "😀a"]]
                     [:tuple [:= :=] [:tuple [:= :-] [:= :j] [:tuple [:= :cast] [:enum "{a,b}" "{a}"] [:= :text-array]]] [:tuple [:= :cast] ::jlit [:= :jsonb]]]
                     [:tuple [:= :=] [:tuple [:= :jsonb_typeof] [:tuple [:= :||] [:= :j] [:tuple [:= :cast] ::jlit [:= :jsonb]]]] [:enum "array" "object"]]
                     [:tuple [:= :=] [:tuple [:= :concat] ::str-col [:= :b]] [:enum "at" "af" "atrue"]]
                     [:tuple [:= :=] [:tuple [:= :cast] [:enum :b :dt] [:= :text]] [:enum "true" "t" "2020-01-01"]]
                     [:tuple [:= :>] [:tuple [:= :pow] [:tuple [:= :cast] ::int-col [:= :double-precision]] [:enum 2 160]] [:= 0]]
                     [:tuple [:= :=] [:tuple [:= :cast] [:enum 0.1M 0.5M] [:= :real]] [:tuple [:= :cast] [:enum 0.1M 0.5M] [:= :double-precision]]]
                     [:tuple ::cmp [:tuple [:= :cast] [:= "NaN"] [:= :double-precision]] [:tuple [:= :cast] ::int-col [:= :double-precision]]]]
             ::expr [:or
                     ::atom
                     [:tuple [:= :not] [:ref ::expr]]
                     [:tuple [:= :and] [:ref ::expr] [:ref ::expr]]
                     [:tuple [:= :or] [:ref ::expr] [:ref ::expr]]]}}
   ::expr])

(defn- to-sql
  "HoneySQL text of an expression; what HoneySQL has no syntax for goes through :raw."
  [e]
  (first (sql/format (walk/postwalk (fn [f]
                                      (cond (and (vector? f) (= :subscript (first f))) [:raw [(name (second f)) "[" (nth f 2) "]"]]
                                            (and (vector? f) (= :cast (first f)) (= :text-array (nth f 2))) [:raw (str "'" (second f) "'::text[]")]
                                            :else f))
                                    (x/->honeysql e))
                     {:inline true})))

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
    (let [seed (ts/seed)
          ;; identical constraint definitions collapse into one, so keep the expressions distinct
          exprs (vec (distinct (mg/sample expr-schema {:size 120 :seed seed})))
          ddl (str create-mood "CREATE TABLE fz (" columns
                   (apply str (map-indexed (fn [i e] (str ",\n  CONSTRAINT c" i " CHECK (" (to-sql e) ")")) exprs))
                   ");")
          _ (exec-sql! ddl)
          constraints (get-in (ir/from-db *db*) [:tables "fz" :constraints])]
      (testing (str "seed " seed ", " (count exprs) " expressions")
        (doseq [[i e] (map-indexed vector exprs)]
          (let [clause (get-in constraints [(str "c" i) :check_clause])
                parsed (if clause (x/try-parse (subs clause 6)) {:error "constraint missing from the catalog"})]
            (is (nil? (:error parsed)) (str "c" i " " (pr-str e) " " clause "\n" (ts/rerun #'expression-roundtrip seed)))
            (is (= (normalize e) (normalize (:expr parsed)))
                (str "input " (pr-str e) "\npg    " clause "\n" (ts/rerun #'expression-roundtrip seed)))))))))

(def ^:private row-schema
  [:map [:n [:maybe [:int {:min -6 :max 101}]]] [:m [:maybe [:int {:min -6 :max 101}]]]
   [:s [:maybe [:enum "a" "b" "c" "it's" " a " "" "a_b" "ab" "AB" "a\n" "😀😀" "😀a😁" "tr" "of" "o" " YE " "é" "É" "Éa"]]] [:t [:maybe [:enum "a" "b" "c" "it's" " a " ""]]]
   [:e [:maybe [:enum "happy" "sad"]]] [:b [:maybe :boolean]]
   ;; only values numeric(10,2) can hold, as BigDecimals, as rows read from the database would be
   [:d [:maybe [:enum -1.0M 0.0M 0.01M 0.5M 2.25M 2.5M 9.99M]]]
   ;; containers only: jsonb - on a scalar makes the whole SELECT fail
   [:j [:maybe [:enum {} {"a" 1} {"a" "x" "b" [1 2]} {"a" {"b" 1}} {"a" nil "c" true} [1 "a"] [] [{"a" 1}] {"a" 1.50M} {"a" [1 2]} [[1]]]]]
   [:arr [:maybe [:enum [] ["a"] ["a" "b"] ["b" "a" "a"] ["it's"]]]]
   [:dt [:maybe [:enum (java.time.LocalDate/parse "2019-12-31") (java.time.LocalDate/parse "2020-01-01") (java.time.LocalDate/parse "2020-01-02") (java.time.LocalDate/parse "2020-06-15")]]]
   [:ts [:maybe [:enum (java.time.Instant/parse "2019-12-31T23:30:00Z") (java.time.Instant/parse "2020-01-01T00:00:00Z") (java.time.Instant/parse "2020-01-02T01:30:00Z") (java.time.Instant/parse "2020-06-15T12:30:00Z")]]]
   [:u [:maybe [:enum (java.util.UUID/fromString (first uuids)) (java.util.UUID/fromString (second uuids))]]]
   ;; char(4) as pgjdbc reads it (padded) and as an application may write it
   [:c [:maybe [:enum "ab" "ab  " "a" "abcd" "" " a" "é"]]]])


(defn- sql-literal [column v]
  (cond (nil? v) "NULL"
        (= column :j) (str (test-db/sql-literal (json/write v)) "::jsonb")
        (= column :arr) (if (empty? v) "'{}'::text[]" (str "ARRAY[" (str/join ", " (map test-db/sql-literal v)) "]::text[]"))
        (= column :e) (str (test-db/sql-literal v) "::mood")
        (instance? java.time.LocalDate v) (str (test-db/sql-literal v) "::date")
        (instance? java.time.Instant v) (str (test-db/sql-literal v) "::timestamptz")
        (instance? java.util.UUID v) (str (test-db/sql-literal v) "::uuid")
        (string? v) (test-db/sql-literal v)
        (double? v) (format "%.2f" v)
        :else (str v)))

(def ^:private column-types
  "The types the evaluator reads for the columns, as a registry gives them."
  {:n :integer :m :integer :c [:character 4]})

(defn- postgres-verdicts
  "For every row, whether each expression IS NOT FALSE according to PostgreSQL; an expression
   that raises an error is false, as a CHECK that raises one rejects the row."
  [exprs rows]
  (let [cols [:n :m :s :t :e :b :d :j :arr :dt :ts :u :c]
        values (str/join ",\n" (map (fn [r] (str "(" (str/join ", " (map #(sql-literal % (get r %)) cols)) ")")) rows))
        selects (str/join ", " (map #(str "verdict($q$SELECT (" (to-sql %) ") IS NOT FALSE FROM judge WHERE id = $1$q$, id)") exprs))
        sql (str create-mood "CREATE TABLE judge (id serial, " columns ");\n"
                 "INSERT INTO judge (" (str/join ", " (map name cols)) ") VALUES\n" values ";\n"
                 "CREATE FUNCTION verdict(q text, rid int) RETURNS boolean LANGUAGE plpgsql AS $f$\n"
                 "DECLARE r boolean; BEGIN EXECUTE q INTO r USING rid; RETURN r; EXCEPTION WHEN OTHERS THEN RETURN false; END $f$;\n"
                 "SELECT " selects " FROM judge ORDER BY id;")
        {:keys [exit out err]} (p/sh ["docker" "exec" "-i" *container* "psql" "-X" "-q" "-A" "-t" "-F" "\t"
                                      "-v" "ON_ERROR_STOP=1" "-U" "postgres" "-d" (:db *db*)]
                                     {:in sql})]
    (when-not (zero? exit) (throw (ex-info err {:sql sql})))
    (mapv #(mapv (fn [v] (= "t" v)) (str/split % #"\t")) (str/split-lines (str/trim out)))))

(deftest evaluated-checks-agree-with-postgres
  (when *db*
    (let [seed (ts/seed)
          exprs (vec (distinct (mg/sample expr-schema {:size 240 :seed seed})))
          rows (vec (distinct (mg/sample row-schema {:size 80 :seed seed})))
          verdicts (postgres-verdicts exprs rows)]
      (testing (str "seed " seed ", " (count exprs) " expressions x " (count rows) " rows")
        (doseq [[j row] (map-indexed vector rows)
                [i e] (map-indexed vector exprs)]
          (is (let [pg (get-in verdicts [j i]) ours (ev/passes? e row column-types)]
                (or (= pg ours)
                    ;; a verdict that depends on what pgmalli cannot know passes the row
                    (ev/unknowable? e row column-types)
                    ;; outside ASCII a class is the server's: pgmalli passes the row, unless
                    ;; another arm fails it with an error PostgreSQL's evaluation order skips
                    (and (re-find #"\\[wWdDsS]|\[:" (pr-str e)) (some #(and (string? %) (re-find #"[^\x00-\x7F]" %)) (vals row)))))
              (str "CHECK " (to-sql e) "\n  data  " (pr-str e) "\n  row   " (pr-str row)
                   "\n  PostgreSQL " (get-in verdicts [j i]) ", pgmalli " (not (get-in verdicts [j i]))
                   "\n  " (ts/rerun #'evaluated-checks-agree-with-postgres seed))))))))

(deftest classes-agree-with-postgres-in-ascii-and-never-reject-what-it-takes-outside
  (when *db*
    (let [patterns (vec (concat (for [c ["alpha" "digit" "alnum" "upper" "lower" "space" "punct" "xdigit" "cntrl" "print" "graph" "blank"]]
                                  (str "^[[:" c ":]]$"))
                                ["^[^[:alpha:]]$" "^[[:alpha:]_-]$" "^\\w$" "^\\W$" "^\\d$" "^\\D$" "^\\s$" "^\\S$" "^[\\w.]$" "^[^\\s]$" "^\\b$"]))
          cps (concat (range 1 128) [0xA0 0xAA 0xB2 0xD7 0xDF 0xE9 0xAD 0x1C5 0x301 0x663 0x1680 0x2007 0x2028 0x202F 0x203F 0x20AC 0x3000 0x3042 0x1F600])
          sql (str "SELECT p.i, c, chr(c) ~ p.pat FROM unnest(ARRAY[" (str/join ", " (map test-db/sql-literal patterns)) "]) WITH ORDINALITY p(pat, i), "
                   "unnest(ARRAY[" (str/join ", " cps) "]) c;")
          {:keys [exit out err]} (p/sh ["docker" "exec" "-i" *container* "psql" "-X" "-q" "-A" "-t" "-F" "\t" "-U" "postgres" "-d" (:db *db*)] {:in sql})]
      (is (zero? exit) err)
      (doseq [line (str/split-lines (str/trim out))
              :let [[i c v] (str/split line #"\t")
                    pattern (patterns (dec (parse-long i)))
                    s (Character/toString (int (parse-long c)))
                    clause (x/check-clause (str "CHECK (s ~ " (test-db/sql-literal pattern) "::text)"))
                    pg? (= "t" v)
                    accepted? (ev/passes? clause {:s s})]]
        (if (< (parse-long c) 128)
          (is (= pg? accepted? (some? (re-find (re-pattern (ev/java-regex pattern)) s))) (str pattern " on " (pr-str s)))
          (when pg? (is (and accepted? (re-find (re-pattern (ev/java-regex pattern)) s)) (str pattern " on U+" (format "%04X" (parse-long c))))))))))
