(ns pgmalli.impl.pattern-test
  (:require [clojure.test :refer [deftest is]]
            [pgmalli.impl.pattern :as p]))

(defn- schema-with-checks [& clauses]
  {:name "public"
   :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}}
   :tables {"t" {:columns [{:name "c" :position 1 :data_type "integer" :is_nullable true}
                           {:name "m" :position 2 :data_type "mood" :type_schema "public" :is_nullable false :default_value "'happy'::mood"}
                           {:name "v" :position 3 :data_type "character varying" :is_nullable true :max_length 40}]
                 :constraints (into {} (map-indexed (fn [i c] [(str "k" i) {:name (str "k" i) :type "CHECK" :check_clause (str "CHECK (" c ")")}]) clauses))}}})

(defn- check-facts [& clauses]
  (->> (p/facts (apply schema-with-checks clauses)) (filter :constraint) (map #(dissoc % :schema :table :constraint)) vec))

(deftest facts-from-types-and-columns
  (is (= [{:fact :enum-type :schema "public" :type-name "mood" :values ["happy" "sad"]}
          {:fact :column :schema "public" :table "t" :column "c" :type "integer" :position 1 :nullable? true}
          {:fact :column :schema "public" :table "t" :column "m" :type "mood" :position 2 :nullable? false :default [:cast "happy" :mood]}
          {:fact :enum :schema "public" :table "t" :column "m" :type-name "mood" :values ["happy" "sad"]}
          {:fact :column :schema "public" :table "t" :column "v" :type "character varying" :position 3 :nullable? true}
          {:fact :max-length :schema "public" :table "t" :column "v" :max 40}]
         (p/facts (schema-with-checks)))))

(deftest unknown-types-and-unreadable-defaults-are-kept
  (let [fs (p/facts {:name "public" :types {}
                     :tables {"t" {:columns [{:name "e" :position 1 :data_type "email" :type_schema "public" :is_nullable false :default_value "CASE WHEN"}
                                             {:name "x" :position 2 :data_type "text" :type_schema "other" :is_nullable true}]
                                   :constraints {}}}})]
    (is (= [:column :unparsed :unknown-type :column :unknown-type] (map :fact fs)))
    (is (= "CASE WHEN" (:input (second fs))))))

(deftest column-patterns
  (is (= [{:fact :not-null :column "c"}] (check-facts "c IS NOT NULL")))
  (is (= [{:fact :in-set :column "c" :values ["a" "b"]}] (check-facts "c IN ('a'::text, 'b'::text)")))
  (is (= [{:fact :in-set :column "c" :values ["a"]}] (check-facts "c = 'a'::mood")))
  (is (= [{:fact :in-set :column "c" :values ["a" "b"]}] (check-facts "c::text IN ('a'::character varying, 'b'::character varying)")))
  (is (= [{:fact :in-set :column "c" :values ["a" "b" "c"]}] (check-facts "c = ANY (ARRAY['a'::text, 'b'::text, 'c'::text])")))
  (is (= [{:fact :in-set :column "c" :values [0 1 2]}] (check-facts "c = ANY (ARRAY[0, 1, 2])")))
  (is (= [{:fact :range :column "c" :min 1 :min-exclusive? false :max 5 :max-exclusive? false}] (check-facts "c >= 1 AND c <= 5")))
  (is (= [{:fact :range :column "c" :min 1 :min-exclusive? false :max 5 :max-exclusive? false}] (check-facts "c BETWEEN 1 AND 5")))
  (is (= [{:fact :range :column "c" :min 0 :min-exclusive? true}] (check-facts "c > 0")))
  (is (= [{:fact :range :column "c" :min -1 :min-exclusive? false}] (check-facts "c >= '-1'::integer")))
  (is (= [{:fact :range :column "c" :max 10 :max-exclusive? false}] (check-facts "10 >= c")))
  (is (= [{:fact :non-blank :column "c" :trim? true}] (check-facts "length(TRIM(BOTH FROM c)) > 0")))
  (is (= [{:fact :non-blank :column "c" :trim? true}] (check-facts "c::text = btrim(c::text) AND c::text <> ''::text")))
  (is (= [{:fact :non-blank :column "c" :trim? false}] (check-facts "c <> ''::text")))
  (is (= [{:fact :length :column "c" :fn :octet_length :exact 32}] (check-facts "octet_length(c) = 32")))
  (is (= [{:fact :length :column "c" :fn :length :max 9}] (check-facts "length(c) < 10")))
  (is (= [{:fact :json-type :column "c" :json-type "object"}] (check-facts "jsonb_typeof(c) = 'object'::text")))
  (is (= [{:fact :regex :column "c" :re "^[a-z]+$" :case-insensitive? false}] (check-facts "c ~ '^[a-z]+$'::text")))
  (is (= [{:fact :when-present :column "c" :fact-when-present {:fact :range :min 0 :min-exclusive? true}}]
         (check-facts "c IS NULL OR c > 0")))
  (is (= [{:fact :when-present :column "c" :fact-when-present {:fact :range :min 0 :min-exclusive? true}}]
         (check-facts "c > 0 OR c IS NULL"))))

(deftest and-of-column-patterns-yields-several-facts
  (is (= [{:fact :non-blank :column "c" :trim? true} {:fact :length :column "c" :fn :length :max 100}]
         (check-facts "length(TRIM(BOTH FROM c)) > 0 AND length(c) <= 100")))
  (is (= [{:fact :range :column "c" :min 0 :min-exclusive? false} {:fact :range :column "m" :min 1 :min-exclusive? false}]
         (check-facts "c >= 0 AND m >= 1"))))

(deftest everything-else-stays-a-table-check
  (is (= [{:fact :table-check :expr [:or [:is :a nil] [:<> :a :b]] :columns ["a" "b"]}]
         (check-facts "a IS NULL OR a <> b")))
  (is (= [{:fact :table-check :expr [:and [:>= :c 0] [:<= :c :total]] :columns ["c" "total"]}]
         (check-facts "c >= 0 AND c <= total")))
  (is (= [{:fact :table-check :expr [:in :x [:a :b]] :columns ["x" "a" "b"]}] (check-facts "x IN (a, b)")))
  (is (= ["c" "p"] (:columns (first (check-facts "CASE c WHEN 'a'::text THEN (p ->> 'x'::text) = 'y'::text ELSE false END"))))))

(deftest not-valid-is-never-matched
  (let [fs (p/facts {:name "public" :types {}
                     :tables {"t" {:columns [{:name "c" :position 1 :data_type "integer" :is_nullable true}]
                                   :constraints {"k" {:name "k" :type "CHECK" :check_clause "CHECK (c >= 0)" :is_valid false}}}}})]
    (is (= {:fact :table-check :valid? false :columns ["c"] :expr [:>= :c 0]}
           (dissoc (last fs) :schema :table :constraint)))))

(deftest coverage-counts
  (is (= {:all {:enum-type 1 :column 3 :enum 1 :max-length 1 :in-set 1 :table-check 1}
          :checks {:in-set 1 :table-check 1}}
         (p/coverage (p/facts (schema-with-checks "c IN ('a'::text)" "a < b"))))))
