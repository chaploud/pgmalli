(ns pgmalli.impl.registry-test
  "Generated files as malli registries, on the checked-in test/resources/pgmalli/{sample,other}.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as tcg]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.pattern :as p]
            [pgmalli.impl.portable :as portable]
            [pgmalli.impl.registry :as reg]
            [malli.registry]
            malli.experimental.time
            [pgmalli.core :as pgmalli]
            [pgmalli.data :as data]
            [pgmalli.honeysql :as h]
            [pgmalli.sample :refer [good opts registry user]]))

(deftest registry-from-classpath
  (is (m/validate :pg.sample/users user opts))
  (is (= {:pg/table "sample.users" :pg/primary-key ["id"]
          :pg/foreign-keys [{:columns ["group_id" "group_name"] :table "sample.groups" :to ["id" "name"]}
                            {:columns ["group_id"] :table "sample.groups" :to ["id"]}
                            {:columns ["referrer_id"] :table "sample.users" :to ["id"]}]}
         (m/properties (pgmalli/columns registry :pg.sample/users))))
  (is (= {:closed_at ["violates check constraint closed_check: CHECK (mood = 'sad'::mood AND closed_at IS NOT NULL OR mood = 'happy'::mood AND closed_at IS NULL)"]}
         (me/humanize (m/explain :pg.sample/users (assoc user :closed_at nil) opts)))
      "errors name the constraint")
  (is (= ["violates check constraint score_check: CHECK (score >= 0 AND score <= total)"]
         (me/humanize (m/explain :pg.sample/users (assoc user :score 3) opts))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not on the classpath" (pgmalli/registry "nope"))))

(deftest a-map-of-no-schemas-is-not-a-registry
  (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a registry: :a maps to 1, not a schema; pass \(pgmalli.core/registry"
                                (h/check {:a 1} {:select [:*] :from [:users]})))]
    (is (= :pgmalli.error/not-a-registry (:pgmalli/error (ex-data e))))))

(deftest generated-data-in-place-of-a-registry-names-the-call-to-make
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"given the generated data of schema \"sample\", not a registry; pass \(pgmalli.core/registry \"sample\"\)"
                        (pgmalli/as-read (pgmalli/generated "sample") :pg.sample/users))))

(deftest insert-schemas
  (let [insert (fn [row] (m/validate :pg.sample.users/insert row opts))]
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1}) "defaults, serials and nullable columns may be omitted")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now)})) "score has no default")
    (is (not (insert {:id 1 :group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1})) "identity ALWAYS cannot be inserted")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1 :nick_upper "X"})) "nor a generated column")
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 1 :seq 7 :total 9}) "serials and defaults may be given")
    (is (not (insert {:group_id 1 :mood "happy" :closed_at (java.time.Instant/now) :score 1})) "table constraints apply")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 5 :total 2})) ":pg/check too")
    (is (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 5}) "score <= total holds with the default total 10")
    (is (not (insert {:group_id 1 :mood "sad" :closed_at (java.time.Instant/now) :score 15})) "and fails against it, as the INSERT would")
    (is (not (insert {:group_id 1 :closed_at (java.time.Instant/now) :score 1})) "omitting mood means happy, which forbids closed_at")
    (is (insert {:group_id 1 :score 1}) "happy with no closed_at")
    (is (not (insert {:group_id 1 :mood "sad" :score 1})) "an omitted column without a default is NULL, which sad forbids"))
  (testing "from generated data instead of the classpath"
    (let [reg (pgmalli/registry {:registry {"pg.public/Order Items" [:map {:pg/table "public.Order Items"} ["line no" [:pg/integer {:pg/type "integer" :pg/default 1}]]]
                                            :pg.public/t [:and [:map {:pg/table "public.t"}
                                                                [:a [:pg/integer {:pg/type "integer"}]]
                                                                [:b [:maybe [:pg/integer {:pg/type "integer" :pg/default 5 :default 5}]]]]
                                                          [:or [:map [:a {:error/message "c"} [:int {:min 1}]]] [:map [:b :nil]]]]}})]
      (is (m/validate "pg.public.Order Items/insert" {} {:registry reg}) "string keys follow the same naming")
      (is (m/validate :pg.public.t/insert {:a 0 :b nil} {:registry reg}) "entries with properties inside fragments survive")
      (is (not (m/validate :pg.public.t/insert {:a 0} {:registry reg})) "an omitted b is 5, not NULL, so the :or has no alternative left")
      (is (not (m/validate :pg.public.t/insert {:a 0 :b 1} {:registry reg})))))
  (testing "what an omitted column stands for"
    (let [reg (pgmalli/registry {:registry {:pg.public/t [:and [:map {:pg/table "public.t"}
                                                                [:status [:string {:pg/type "text" :pg/default "approved" :default "approved"}]]
                                                                [:approver [:maybe [:string {:pg/type "text"}]]]
                                                                [:a [:pg/integer {:pg/type "integer"}]]
                                                                [:b [:pg/integer {:pg/type "integer" :pg/default 5 :default 5}]]
                                                                [:c [:pg/integer {:pg/type "integer" :pg/default [:nextval "s"]}]]]
                                                          [:multi {:dispatch :status}
                                                           ["approved" [:map [:approver :string]]]
                                                           [:malli.core/default [:map [:approver :nil]]]]
                                                          [:or [:map [:a [:int {:min 1}]]] [:map [:b [:int {:min 1}]]]]
                                                          [:pg/check [:<= :a :b]]
                                                          [:pg/check [:<= :a :c]]]}})
          insert (fn [row] (m/validate :pg.public.t/insert row {:registry reg}))]
      (is (insert {:status "pending" :approver nil :a 0}) "a value without a branch of its own still takes the default branch")
      (is (not (insert {:approver nil :a 0})) "omitted status is approved, which needs an approver")
      (is (insert {:approver "x" :a 0}) "the default b satisfies the :or, so it may be omitted")
      (is (insert {:approver "x" :a 5}) "a :pg/check sees the default b")
      (is (not (insert {:approver "x" :a 6})))
      (is (insert {:approver "x" :a 6 :b 9}))
      (is (not (insert {:approver "x" :a 1 :b nil})) "an explicit NULL wins over the default")
      (is (insert {:approver "x" :a 100 :b 200}) "an expression default is unknown, so the :pg/check on c cannot fail"))))

(deftest what-an-update-may-set
  (is (= [:map {:pg/table "sample.users" :closed true}
          [:born {:optional true} [:maybe [:time/local-date {:pg/type "date"}]]]
          [:closed_at {:optional true} [:maybe [:time/instant {:pg/type "timestamptz"}]]]
          [:group_id {:optional true} [:int {:pg/type "integer" :min -2147483648 :max 2147483647}]]]
         (vec (take 5 (pgmalli/portable registry :pg.sample.users/update)))))
  (is (m/validate :pg.sample.users/update {:nick "n"} opts) "any subset of the columns")
  (is (not (m/validate :pg.sample.users/update {:score nil} opts)) "a NOT NULL column cannot be set to NULL")
  (is (not (m/validate :pg.sample.users/update {:id 1} opts)) "an identity ALWAYS column cannot be set")
  (is (not (m/validate :pg.sample.users/update {:nick_upper "x"} opts)) "nor a generated one")
  (is (not (m/validate :pg.sample.users/update {:nope 1} opts)) "closed"))

(deftest several-schemas
  (let [registry (pgmalli/registry "sample" "other")
        opts {:registry registry}
        ds (data/dataset-schema registry)]
    (is (m/validate :pg.other.notes/insert {:id 1 :user_id 1} opts))
    (is (m/validate ds (assoc good "other.notes" [{:id 1 :user_id 1}]) opts))
    (is (not (m/validate ds (assoc good "other.notes" [{:id 1 :user_id 9}]) opts)) "a reference into another schema")
    (doseq [sample (tcg/sample (data/dataset-generator registry {:rows 4}) 4)]
      (is (m/validate ds sample opts) (pr-str sample)))
    (is (m/validate (data/dataset-schema (pgmalli/registry "other")) {"other.notes" [{:id 1 :user_id 9}]} {:registry (pgmalli/registry "other")})
        "a reference to a table outside the registry is not checked")))

(deftest any-malli-registry-will-do
  (let [composite (malli.registry/composite-registry registry {:app/flag :boolean})]
    (is (m/validate :pg.sample/groups {:id 1 :name "g"} {:registry composite}))
    (is (= [] (h/check composite {:select [:id] :from [:groups]} {:schema "sample"})))
    (is (= 2 (count (keys (tcg/generate (data/dataset-generator composite {:rows 2}) 20 1)))) "datasets from a composite registry")
    (is (= (portable/column registry :pg.sample/users :nick) (portable/column composite :pg.sample/users :nick)))
    (is (= (pgmalli/portable registry :pg.sample/users) (pgmalli/portable composite :pg.sample/users)) "portable inlines references through a composite registry")
    (is (= (h/query-schema registry '[id] {:select [:id] :from [:groups]} {:schema "sample"})
           (h/query-schema composite '[id] {:select [:id] :from [:groups]} {:schema "sample"})))))

(def ^:private generated
  (let [col (fn [n t & {:as more}] (merge {:name n :position 1 :data_type t :is_nullable false} more))
        check (fn [n c] [n {:name n :type "CHECK" :check_clause (str "CHECK (" c ")")}])
        reg (pgmalli/registry
             (files/assemble "public" "x"
                             (p/facts {:name "public"
                                       :types {"day" {:kind "DOMAIN" :base_type "date" :not_null false :constraints []}
                                               "small" {:kind "DOMAIN" :base_type "numeric" :not_null false
                                                        :constraints [{:name "small_check" :definition "CHECK (VALUE = ANY (ARRAY[1::numeric, 2::numeric, 5::numeric]))"}]}}
                                       :tables {"t" {:columns [(col "n" "numeric") (col "sm" "small" :type_schema "public") (col "s" "text")
                                                               (col "tz" "time with time zone") (col "j" "jsonb") (col "d" "day" :type_schema "public")
                                                               (col "ds" "date[]") (col "b" "bit" :max_length 3) (col "p" "numeric" :precision 6 :scale 2)]
                                                     :constraints (into {} (remove nil?) [(check "n_check" "n = ANY (ARRAY[1::numeric, 2::numeric, 5::numeric])")
                                                                            ;; babashka has no test.chuck: a regex is drawn and filtered there, which ^A defeats
                                                                            (when @reg/regex-generation? (check "s_check" "s ~* '^A'::text"))
                                                                            (check "j_check" "jsonb_typeof(j) = 'array'::text")
                                                                            (check "p_check" "p > 0::numeric")])}}})
                             {}))]
    {:registry reg :rows (try (vec (mg/sample :pg.public.t/insert {:registry reg :size 30 :seed 1})) (catch Exception e (ex-message e)))}))

(defn- generated-values [k] (let [rows (:rows generated)] (if (string? rows) [rows] (map k rows))))

(deftest a-numeric-in-a-list-generates-from-the-list-and-takes-any-scale
  (is (every? #{1M 2M 5M} (map #(some-> % .stripTrailingZeros (.setScale 0)) (generated-values :n))) (pr-str (:rows generated)))
  (is (every? #{1M 2M 5M} (map #(some-> % .stripTrailingZeros (.setScale 0)) (generated-values :sm))))
  (is (m/validate :pg.public.t/insert (assoc (first (:rows generated)) :n 2.0M :sm 5.00M) {:registry (:registry generated)}) "2.0 = 2 in PostgreSQL"))

(deftest a-case-insensitive-regex-generates-without-test-chuck-failing
  (when @reg/regex-generation?
    (is (every? #(re-find #"^[Aa]" %) (generated-values :s)))))

(deftest a-timetz-generates-an-offset-postgresql-takes
  (is (every? #(<= (Math/abs (.getTotalSeconds (.getOffset ^java.time.OffsetTime %))) (+ (* 15 3600) (* 59 60) 59)) (generated-values :tz))))

(deftest a-json-array-generates-elements-json-carries
  (is (every? #(and (sequential? %) (json/write %)) (generated-values :j))))

(deftest dates-in-domains-and-arrays-generate-within-2025
  (is (every? #(= 2025 (.getYear ^java.time.LocalDate %)) (concat (generated-values :d) (mapcat identity (generated-values :ds))))))

(deftest a-domain-over-a-type-the-driver-returns-otherwise-is-read-as-the-driver-returns-it
  (let [read (pgmalli/as-read (:registry generated) :pg.public/t {:qualified? false})
        row {:n 1M :sm 2M :s "A" :tz (java.util.Date. 0) :j :pgobject :d (java.util.Date. 0) :ds :array :b :pgobject :p 1.5M}]
    (is (m/validate read row {:registry (:registry generated)}) (pr-str (me/humanize (m/explain read row {:registry (:registry generated)}))))))

(deftest a-column-whose-default-its-check-refuses-is-carried-by-every-insert
  (let [reg (pgmalli/registry (files/assemble "public" "x" (p/facts {:name "public" :types {}
                                                                    :tables {"t" {:columns [{:name "b" :position 1 :data_type "smallint" :is_nullable true :default_value "1"}]
                                                                                  :constraints {"b_check" {:name "b_check" :type "CHECK" :check_clause "CHECK (b IS NULL OR (b <> ALL (ARRAY[1, 2])))"}}}}})
                                              {}))]
    (is (not (m/validate :pg.public.t/insert {} {:registry reg})) "left out, b is 1, which the CHECK refuses")
    (is (m/validate :pg.public.t/insert {:b nil} {:registry reg}))))

(deftest a-branch-on-a-quoted-column-dispatches-on-its-string-key
  (let [reg (pgmalli/registry (files/assemble "public" "x" (p/facts {:name "public" :types {}
                                                                    :tables {"t" {:columns [{:name "Mixed Col" :position 1 :data_type "integer" :is_nullable false}
                                                                                            {:name "b" :position 2 :data_type "integer" :is_nullable false}]
                                                                                  :constraints {"c" {:name "c" :type "CHECK" :check_clause "CHECK (\"Mixed Col\" = 1 AND b > 0 OR \"Mixed Col\" = 2 AND b < 0)"}}}}})
                                              {}))]
    (is (m/validate :pg.public/t {"Mixed Col" 1 :b 1} {:registry reg}))
    (is (not (m/validate :pg.public/t {"Mixed Col" 1 :b -1} {:registry reg})))
    (is (= 2 (count (get (tcg/generate (data/dataset-generator reg {:rows 2}) 20 1) "public.t"))))))

(deftest a-bounded-numeric-generates-at-its-scale
  (is (every? #(and (<= (.scale ^BigDecimal %) 2) (pos? %)) (generated-values :p))))

(deftest a-bit-string-generates-digits-of-its-length
  (is (every? #(re-matches #"[01]{3}" %) (generated-values :b))))

(defn- registry-of [types columns checks]
  (pgmalli/registry (files/assemble "public" "x" (p/facts {:name "public" :types types
                                                          :tables {"t" {:columns columns
                                                                        :constraints (into {} (for [[n c] checks] [n {:name n :type "CHECK" :check_clause (str "CHECK (" c ")")}]))}}})
                                    {})))

(deftest a-char-n-column-generates-as-its-check-says-the-evaluator-holding-it
  (let [col (fn [n len] {:name n :position 1 :data_type "character" :is_nullable false :max_length len})
        reg (registry-of {} (cond-> [(col "cur" 4)] @reg/regex-generation? (conj (col "country" 2)))
                         (cond-> [["cur_check" "cur = ANY (ARRAY['JPY'::bpchar, 'ab  '::bpchar])"]]
                           @reg/regex-generation? (conj ["country_check" "country ~ '^[A-Z]{2}$'::text"])))
        rows (mg/sample :pg.public.t/insert {:registry reg :size 30 :seed 1})]
    (is (every? #{"JPY" "ab"} (map :cur rows)) "an IN's values, without their trailing spaces")
    (when @reg/regex-generation?
      (is (every? #(re-matches #"[A-Z]{2}" %) (map :country rows))))))

(deftest a-char-n-column-whose-check-allows-null-generates-as-its-pattern-says
  (when @reg/regex-generation?
    (let [reg (registry-of {} [{:name "c" :position 1 :data_type "character" :is_nullable false :max_length 3}]
                           [["c_check" "c IS NULL OR c ~* '^A'::text"]])]
      (is (every? #(re-find #"^[aA]" %) (map :c (mg/sample :pg.public.t/insert {:registry reg :size 20 :seed 1})))))))

(deftest a-domain-over-char-n-generates-as-its-check-says
  (let [reg (registry-of {"cc" {:kind "DOMAIN" :base_type "character(3)" :not_null false
                                :constraints [{:name "cc_check" :definition "CHECK (VALUE = ANY (ARRAY['JPY'::bpchar, 'USD'::bpchar]))"}]}}
                         [{:name "c" :position 1 :data_type "cc" :type_schema "public" :is_nullable false}] [])]
    (is (every? #{"JPY" "USD"} (map :c (mg/sample :pg.public.t/insert {:registry reg :size 30 :seed 1}))))))

(deftest an-in-on-a-domain-generates-from-its-values-not-from-the-domain
  (let [reg (registry-of {"dom" {:kind "DOMAIN" :base_type "integer" :not_null false :constraints [{:name "dom_check" :definition "CHECK (VALUE <> 3)"}]}
                          "o" {:kind "DOMAIN" :base_type "dom" :not_null false :constraints [{:name "o_check" :definition "CHECK (VALUE = ANY (ARRAY[1, 2, 5]))"}]}}
                         [{:name "c" :position 1 :data_type "o" :type_schema "public" :is_nullable false}] [])]
    (is (every? #{1 2 5} (map :c (mg/sample :pg.public.t/insert {:registry reg :size 30 :seed 1}))))))

(deftest a-regex-over-a-domain-generates-short-strings-of-either-case-the-domain-keeps
  (when @reg/regex-generation?
    (let [reg (registry-of {"dq" {:kind "DOMAIN" :base_type "character varying(5)" :not_null false
                                  :constraints [{:name "dq_check" :definition "CHECK (VALUE::text ~ '^[[:alpha:]]+$'::text)"}]}
                            "o" {:kind "DOMAIN" :base_type "dq" :not_null false
                                 :constraints [{:name "o_check" :definition "CHECK (VALUE::text ~~* 'A%'::text)"}]}
                            "c3" {:kind "DOMAIN" :base_type "character(3)" :not_null false
                                  :constraints [{:name "c3_check" :definition "CHECK (VALUE ~ '^[a-z]+$'::text)"}]}}
                           [{:name "o" :position 1 :data_type "o" :type_schema "public" :is_nullable false}
                            {:name "c" :position 2 :data_type "c3" :type_schema "public" :is_nullable false}] [])
          rows (mg/sample :pg.public.t/insert {:registry reg :size 30 :seed 1})]
      (is (every? #(re-matches #"[aA]\p{IsAlphabetic}{0,4}" %) (map :o rows)))
      (is (every? #(re-matches #"[a-z]{3}" %) (map :c rows)) "char(3) pads: only three letters match"))))
