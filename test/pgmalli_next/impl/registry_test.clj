(ns pgmalli-next.impl.registry-test
  "The generated file as a malli registry, on the checked-in test/resources/pgmalli-next/{sample,other,both}.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [pgmalli-next.impl.files :as files]
            [pgmalli-next.impl.pattern :as p]
            [pgmalli-next.impl.portable :as portable]
            [pgmalli-next.impl.registry :as registry]
            [pgmalli-next.impl.shape :as shape]
            [malli.registry]
            malli.experimental.time
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.sample :refer [opts registry user]]))

(deftest a-registry-of-generated-data
  (is (m/validate :pg.sample/users user opts))
  (is (= {:pg/table "sample.users" :pg/primary-key ["id"]
          :pg/foreign-keys [{:columns ["group_id" "group_name"] :table "sample.groups" :to ["id" "name"]}
                            {:columns ["group_id"] :table "sample.groups" :to ["id"]}
                            {:columns ["referrer_id"] :table "sample.users" :to ["id"]}]}
         (shape/row-props (shape/schema-of registry :pg.sample/users))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"registry takes generated data" (pgmalli/registry "sample")) "no schema name: the data, or the classpath's file"))

(deftest a-map-of-no-schemas-is-not-a-registry
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a registry: :a maps to 1, not a schema; pass \(pgmalli-next.core/registry"
                                (:problems (h/analyze {:a 1} {:select [:*] :from [:users]}))))]
    (is (= :pgmalli.error/not-a-registry (:pgmalli/error (ex-data e))))))

(deftest generated-data-in-place-of-a-registry-names-the-call-to-make
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"given the generated data of schemas \[\"sample\"\], not a registry; pass \(pgmalli-next.core/registry <that data>\)"
                        (pgmalli/as-read pgmalli-next.sample/sample-data :pg.sample/users))))

(deftest insert-schemas
  (let [insert (fn [row] (m/validate :pg.sample.users/insert row opts))]
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1}) "defaults, serials and nullable columns may be omitted")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now)})) "score has no default")
    (is (not (insert {:id 1 :group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1})) "identity ALWAYS cannot be inserted")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1 :nick_upper "X"})) "nor a generated column")
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1 :seq 7 :total 9}) "serials and defaults may be given")
    (is (insert {:group_id 1 :score 1}) "an omitted mood takes its default"))
  (testing "from generated data instead of the classpath"
    (let [reg (pgmalli/registry {:registry {"pg.public/Order Items" [:map {:pg/table "public.Order Items"} ["line no" [:pg/integer {:pg/type "integer" :pg/default 1}]]]
                                            :pg.public/t [:map {:pg/table "public.t"}
                                                          [:a [:pg/integer {:pg/type "integer"}]]
                                                          [:b [:maybe [:pg/integer {:pg/type "integer" :pg/default 5 :default 5}]]]]}})]
      (is (m/validate "pg.public.Order Items/insert" {} {:registry reg}) "string keys follow the same naming")
      (is (m/validate :pg.public.t/insert {:a 0} {:registry reg}) "a column with a default may be omitted")
      (is (not (m/validate :pg.public.t/insert {:b 1} {:registry reg})) "one without a default may not"))))

(deftest what-an-update-may-set
  (is (= [:map {:pg/table "sample.users" :closed true}
          [:born {:optional true} [:maybe [:time/local-date {:pg/type "date"}]]]
          [:closed_at {:optional true} [:maybe [:time/instant {:pg/type "timestamptz"}]]]
          [:group_id {:optional true} [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]
         (vec (take 5 (portable/portable-data registry (shape/schema-of registry :pg.sample.users/update))))))
  (is (m/validate :pg.sample.users/update {:nick "n"} opts) "any subset of the columns")
  (is (not (m/validate :pg.sample.users/update {:score nil} opts)) "a NOT NULL column cannot be set to NULL")
  (is (not (m/validate :pg.sample.users/update {:id 1} opts)) "an identity ALWAYS column cannot be set")
  (is (not (m/validate :pg.sample.users/update {:nick_upper "x"} opts)) "nor a generated one")
  (is (not (m/validate :pg.sample.users/update {:nope 1} opts)) "closed"))

(deftest several-schemas
  (let [registry (pgmalli/registry pgmalli-next.sample/both-data)
        opts {:registry registry}]
    (is (m/validate :pg.other.notes/insert {:id 1 :user_id 1} opts))
    (is (m/validate :pg.sample/users user opts))))

(deftest any-malli-registry-will-do
  (let [composite (malli.registry/composite-registry registry {:app/flag :boolean})]
    (is (m/validate :pg.sample/groups {:id 1 :name "g"} {:registry composite}))
    (is (= [] (:problems (h/analyze composite {:select [:id] :from [:groups]} {:schema "sample"}))))
    (is (= (portable/portable-data registry (shape/schema-of registry :pg.sample/users)) (portable/portable-data composite (shape/schema-of composite :pg.sample/users)))
        "portable-data inlines references through a composite registry")
    (is (= (h/query-schema registry [:id] {:select [:id] :from [:groups]} {:schema "sample"})
           (h/query-schema composite [:id] {:select [:id] :from [:groups]} {:schema "sample"})))))

(def ^:private generated
  (let [col (fn [n t & {:as more}] (merge {:name n :position 1 :data_type t :is_nullable false} more))
        reg (pgmalli/registry
             (files/assemble "x" [["public"
                             (p/facts {:name "public"
                                       :types {}
                                       :tables {"t" {:columns [(col "n" "numeric") (col "sm" "numeric" :domain "small") (col "s" "text")
                                                               (col "tz" "time with time zone") (col "j" "jsonb") (col "d" "date" :domain "day")
                                                               (col "ds" "date[]") (col "b" "bit" :max_length 3) (col "p" "numeric")
                                                               (col "v" "character varying" :max_length 5) (col "vb" "bit varying" :max_length 2)]
                                                     :constraints {}}}})]]
                             {}))]
    {:registry reg :rows (try (vec (mg/sample :pg.public.t/insert {:registry reg :size 30 :seed 1})) (catch Exception e (ex-message e)))}))

(defn- generated-values [k] (let [rows (:rows generated)] (if (string? rows) [rows] (map k rows))))

(deftest a-timetz-generates-an-offset-postgresql-takes
  (is (every? #(<= (Math/abs (.getTotalSeconds (.getOffset ^java.time.OffsetTime %))) (+ (* 15 3600) (* 59 60) 59)) (generated-values :tz))))

(deftest dates-in-domains-and-arrays-generate-within-2025
  (is (every? #(= 2025 (.getYear ^java.time.LocalDate %)) (concat (generated-values :d) (mapcat identity (generated-values :ds))))))

(deftest a-domain-over-a-type-the-driver-returns-otherwise-is-read-as-the-driver-returns-it
  (let [read (pgmalli/as-read (:registry generated) :pg.public/t {})
        row {:n 1M :sm 2M :s "A" :tz (java.time.OffsetTime/of 1 0 0 0 java.time.ZoneOffset/UTC) :j [] :d (java.time.LocalDate/of 2025 1 1) :ds [(java.time.LocalDate/of 2025 1 1)] :b :pgobject :p 1.5M :v "a" :vb :pgobject}]
    (is (m/validate read row {:registry (:registry generated)}) (pr-str (me/humanize (m/explain read row {:registry (:registry generated)}))))))

(deftest a-bit-string-generates-digits-within-its-length
  (is (every? #(re-matches #"[01]{3}" %) (generated-values :b)) "bit(3): exactly 3")
  (is (every? #(re-matches #"[01]{1,2}" %) (generated-values :vb)) "bit varying(2): at most 2"))

(deftest a-string-generates-within-its-varchar-length-which-no-schema-checks
  (is (every? #(<= (count %) 5) (generated-values :v)))
  (is (m/validate :pg.public/t (assoc (first (:rows generated)) :v "longer than five") {:registry (:registry generated)})))

(deftest the-registry-of-no-argument-is-the-classpaths-generated-file
  (with-redefs [registry/resource "pgmalli-next/sample.edn"]
    (is (= (pgmalli/registry pgmalli-next.sample/sample-data) (pgmalli/registry)))))

(defn- registry-of [types columns]
  (pgmalli/registry (files/assemble "x" [["public" (p/facts {:name "public" :types types :tables {"t" {:columns columns :constraints {}}}})]] {})))

(deftest an-re-in-the-registry-round-trips-through-malli-ast
  (let [reg (pgmalli/registry {:registry {}})
        s (m/schema [:map [:a [:re {:error/message "m"} "x+"]]] {:registry reg})]
    (is (= (m/form s) (m/form (m/from-ast (m/ast s) {:registry reg}))))))

(deftest a-regex-running-out-of-the-threads-stack-matches-on-a-larger-one
  (let [s (m/schema [:re "^([a-z0-9]|-)+$"] {:registry (pgmalli/registry {:registry {}})})
        long-value (apply str (repeat 200000 "a"))]
    (is (thrown? StackOverflowError (re-find #"^([a-z0-9]|-)+$" long-value)) "the thread's own stack runs out")
    (is (m/validate s long-value))
    (is (not (m/validate s (str long-value "!"))))))

(deftest a-default-pgmalli-cannot-parse-is-its-sql-and-the-column-optional-in-insert
  (let [reg (registry-of {} [{:name "c" :position 1 :data_type "text" :is_nullable false :default_value "CASE WHEN"}])
        c (some #(when (= :c (first %)) %) (m/children (m/deref (m/schema :pg.public/t {:registry reg}))))]
    (is (= {:sql "CASE WHEN"} (:pg/default (m/properties (last c)))))
    (is (m/validate :pg.public.t/insert {} {:registry reg}))))

(deftest a-not-null-column-of-a-type-pgmalli-has-no-value-of-fails-to-generate-naming-it
  (let [e (is (thrown? clojure.lang.ExceptionInfo
                       (mg/generate :pg.public/t {:registry (registry-of {} [{:name "p" :position 1 :data_type "pair" :type_schema "public" :is_nullable false}]) :seed 1})))]
    (is (= ["public.t.p: pgmalli cannot make a value of type pair; give one with :overrides" :pgmalli.error/cannot-generate]
           [(ex-message e) (:pgmalli/error (ex-data e))])))
  (is (= {:q nil} (mg/generate :pg.public/t {:registry (registry-of {} [{:name "q" :position 1 :data_type "pair" :type_schema "public" :is_nullable true}]) :seed 1}))
      "a nullable one is NULL"))

(deftest a-time-type-says-what-it-takes-as-malli-error-says-nothing-of-it
  (is (= {:closed_at ["should be a java.time.Instant"] :born ["should be a java.time.LocalDate"]}
         (me/humanize (m/explain :pg.sample/users (assoc user :closed_at 1 :born "2025-01-01") opts)))))

(deftest a-sequence-of-an-enums-name-leaves-the-registrys-name-to-the-enum
  (let [data (files/assemble "x" [["public" (p/facts {:name "public" :types {"s" {:kind "ENUM" :enum_values ["a"]}} :sequences ["s" "t"] :tables {}})]] {})
        reg (pgmalli/registry data)]
    (is (= [{:kind :sequence-named-as-enum :table "public.s"}] (map #(select-keys % [:kind :table]) (:diagnostics data))))
    (is (= [:enum "a"] (shape/schema-of reg :pg.public/s)))
    (is (:pg/sequence (shape/row-props (shape/schema-of reg :pg.public/t))))
    (is (not-any? #(contains? reg %) [:pg.public.t/insert :pg.public.t/update]) "a sequence is written by its functions alone")
    (is (not-any? #{:pg.public.t/insert} (:known (ex-data (try (pgmalli/as-read reg :pg.public/zz) (catch clojure.lang.ExceptionInfo e e))))))))
