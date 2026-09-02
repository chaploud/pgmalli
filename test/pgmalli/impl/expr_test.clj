(ns pgmalli.impl.expr-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [pgmalli.impl.expr :as x]
            [pgmalli.impl.ir :as ir]
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(def ^:private golden
  [["difficulty >= 1 AND difficulty <= 5" [:and [:>= :difficulty 1] [:<= :difficulty 5]]]
   ["a = 1 AND b = 2 OR c = 3" [:or [:and [:= :a 1] [:= :b 2]] [:= :c 3]]]
   ["status::text IN ('a'::character varying, 'b'::character varying)"
    [:in [:cast :status :text] [[:cast "a" :character-varying] [:cast "b" :character-varying]]]]
   ["status <> ALL (ARRAY['x'::approval_status])" [:<> :status [:all [:array [[:cast "x" :approval_status]]]]]]
   ["length(TRIM(BOTH FROM title)) > 0" [:> [:length [:trim :title]] 0]]
   ["\"position\" > 0" [:> :position 0]]
   ["'-infinity'::timestamp with time zone" [:cast "-infinity" :timestamp-with-time-zone]]
   ["x::numeric(10,2)" [:cast :x [:numeric 10 2]]]
   ["x::text[]" [:cast :x :text-array]]
   ["CASE t WHEN 'a' THEN x > 0 ELSE y IS NULL END" [:case [:= :t "a"] [:> :x 0] :else [:is :y nil]]]
   ["COALESCE(unit_id, 0::bigint)" [:coalesce :unit_id [:cast 0 :bigint]]]
   ["(a IS NULL) = (b IS NULL)" [:= [:is :a nil] [:is :b nil]]]
   ["NOT a AND b" [:and [:not :a] :b]]
   ["x BETWEEN 1 AND 5" [:between :x 1 5]]
   ["x NOT IN ('a')" [:not-in :x ["a"]]]
   ["group_id = app.current_group_id()" [:= :group_id [:app.current_group_id]]]
   ["'it''s'" "it's"]
   ["-1" -1]
   ["a - 1" [:- :a 1]]
   ["CURRENT_TIMESTAMP" [:raw "CURRENT_TIMESTAMP"]]
   ["x ~* '^a'" [:iregex :x "^a"]]
   ["a @> b AND c % 2 = 0" [:and [:contains :a :b] [:= [:mod :c 2] 0]]]
   ["ARRAY[ARRAY[1], ARRAY[]]" [:array [[:array [1]] [:array []]]]]
   ["(now() AT TIME ZONE 'utc'::text)" [:at-time-zone [:now] [:cast "utc" :text]]]
   ["(VALUE).x <= (VALUE).y" [:<= [:field :VALUE :x] [:field :VALUE :y]]]
   ["(VALUE)[1] < (VALUE)[2]" [:< [:subscript :VALUE 1] [:subscript :VALUE 2]]]
   ["\"名前\" <> 名前2" [:<> :名前 :名前2]]])

(deftest parses-deparser-output
  (doseq [[s e] golden]
    (is (= e (x/parse s)) s)))

(deftest data-is-readable-edn
  (let [e (x/parse "a ~* 'x' AND (j #> '{a}'::text[]) @> '1'::jsonb")]
    (is (= e (edn/read-string (pr-str e))))
    (is (= [:and [(keyword "~*") :a "x"] [(keyword "@>") [(keyword "#>") :j [:cast "{a}" :text-array]] [:cast "1" :jsonb]]]
           (x/->honeysql e)))))

(deftest canonical-undoes-deparser-rewrites
  (is (= [:>= :n -1] (x/canonical (x/parse "n >= '-1'::integer"))))
  (is (= [:> :d -0.5M] (x/canonical (x/parse "d > '-0.5'::numeric"))))
  (is (= [:in :m [1 2 3]] (x/canonical (x/parse "m = ANY (ARRAY[1, 2, 3])"))))
  (is (= [:in [:cast :v :text] [[:cast "a" :character-varying]]]
         (x/canonical (x/parse "v::text = ANY ((ARRAY['a'::character varying])::text[])"))))
  (is (= [:not-in :s [[:cast "a" :text] [:cast "b" :text]]] (x/canonical (x/parse "s <> ALL (ARRAY['a'::text, 'b'::text])"))))
  (is (= [:= :s [:cast "a" :text]] (x/canonical (x/parse "s IN ('a'::text)"))))
  (is (= [:= :s "x"] (x/canonical (x/parse "s = 'x'")))))

(deftest check-clause-wrapper
  (is (= [:>= :age 0] (x/check-clause "CHECK (age >= 0)")))
  (is (= [:>= :age 0] (x/check-clause "CHECK (age >= 0) NOT VALID")))
  (is (thrown? clojure.lang.ExceptionInfo (x/check-clause "age >= 0"))))

(deftest errors-are-reported-not-thrown-by-try-parse
  (is (:error (x/try-parse "a +")))
  (is (:error (x/try-parse "a ) b")))
  (is (:error (x/try-parse "'unterminated"))))

(def ^:private roundtrip-columns
  "n integer, big bigint, s text, v varchar(40), e mood, t timestamptz, j jsonb, b boolean, d numeric(10,2)")

(def ^:private roundtrip-exprs
  "[input expected]; expected defaults to input. Casts PostgreSQL adds show up here."
  [[[:>= :n 0]]
   [[:and [:>= :n 1] [:<= :n 5]]]
   [[:between :n 1 5] [:and [:>= :n 1] [:<= :n 5]]]
   [[:in :s ["a" "b"]] [:in :s [[:cast "a" :text] [:cast "b" :text]]]]
   [[:in :e ["happy" "sad"]] [:in :e [[:cast "happy" :mood] [:cast "sad" :mood]]]]
   [[:in :v ["a"]] [:= [:cast :v :text] [:cast "a" :text]]]
   [[:in :v ["a" "b"]] [:in [:cast :v :text] [[:cast "a" :character-varying] [:cast "b" :character-varying]]]]
   [[:> [:length [:trim :s]] 0]]
   [[:= [:jsonb_typeof :j] "object"] [:= [:jsonb_typeof :j] [:cast "object" :text]]]
   [[:or [:is :n nil] [:> :n 0]]]
   [[:or [:and [:= :e "happy"] [:is :t nil]] [:and [:= :e "sad"] [:is-not :t nil]]]
    [:or [:and [:= :e [:cast "happy" :mood]] [:is :t nil]] [:and [:= :e [:cast "sad" :mood]] [:is-not :t nil]]]]
   [[:<> :s ""] [:<> :s [:cast "" :text]]]
   [[:regex :s "^[a-z]+$"] [:regex :s [:cast "^[a-z]+$" :text]]]
   [[:= [:is :n nil] [:is :big nil]]]
   [[:not [:= :b true]]]
   [[:> :d 0.5]]
   [[:= [:coalesce :big 0] 0] [:= [:coalesce :big [:cast 0 :bigint]] 0]]])

(deftest roundtrip-through-postgres
  (when *db*
    (let [ddl (str "CREATE TYPE mood AS ENUM ('happy', 'sad');\nCREATE TABLE rt (" roundtrip-columns
                   (apply str (map-indexed (fn [i [e _]]
                                             (str ",\n  CONSTRAINT c" i " CHECK (" (first (sql/format (x/->honeysql e) {:inline true})) ")"))
                                           roundtrip-exprs))
                   ");")
          _ (exec-sql! ddl)
          constraints (get-in (ir/from-db *db*) [:tables "rt" :constraints])]
      (doseq [[i [e expected]] (map-indexed vector roundtrip-exprs)]
        (testing (pr-str e)
          (is (= (or expected e)
                 (x/canonical (x/check-clause (get-in constraints [(str "c" i) :check_clause]))))))))))
