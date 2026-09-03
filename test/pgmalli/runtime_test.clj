(ns pgmalli.runtime-test
  "The application side, on the checked-in generated files test/resources/pgmalli/{sample,other}.edn."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.test.check.generators :as tcg]
            [malli.generator :as mg]
            [malli.core :as m]
            [malli.registry]
            [malli.error :as me]
            malli.experimental.time
            honey.sql
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.generate]
            [pgmalli.impl.registry]
            [pgmalli.data :as data]
            [pgmalli.impl.json :as json]))

(def registry (pgmalli/registry "sample"))
(def opts {:registry registry})

(def ^:private user
  {:id 1 :group_id 1 :group_name nil :updated_at nil :mood "sad" :nick nil :born nil :closed_at (java.time.Instant/now)
   :referrer_id nil :seq 1 :nick_upper nil :score 1 :total 2})

(deftest registry-from-classpath
  (is (m/validate :pg.sample/users user opts))
  (is (= {:pg/table "sample.users" :pg/primary-key ["id"]
          :pg/foreign-keys [{:columns ["group_id" "group_name"] :table "sample.groups" :to ["id" "name"]}
                            {:columns ["group_id"] :table "sample.groups" :to ["id"]}
                            {:columns ["referrer_id"] :table "sample.users" :to ["id"]}]}
         (m/properties (pgmalli/columns registry :pg.sample/users))))
  (is (= {:closed_at ["closed_check"]}
         (me/humanize (m/explain :pg.sample/users (assoc user :closed_at nil) opts)))
      "errors name the constraint")
  (is (= ["score_check"]
         (me/humanize (m/explain :pg.sample/users (assoc user :score 3) opts))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not on the classpath" (pgmalli/registry "nope")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"older pgmalli" (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "t"} [:a :int]]}}))
      "files from before the schema-qualified :pg/table are refused, not half-read")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"older pgmalli" (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/unique [["a"]]} [:a :int]]}}))
      "so are files with the older key shapes"))

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

(deftest transformer-decodes-jdbc-values
  ;; babashka cannot construct java.sql.Timestamp; the JVM run covers this
  (when-not (System/getProperty "babashka.version")
    (let [tokyo (java.time.ZoneId/of "Asia/Tokyo")
          at (java.time.Instant/parse "2026-01-01T23:04:05Z")
          row (assoc user :born (java.sql.Date/valueOf "2026-01-02") :closed_at (java.sql.Timestamp. (.toEpochMilli at)))
          decode (fn [row t] (m/decode :pg.sample/users row opts t))
          decoded (decode row (pgmalli/transformer))]
      (is (= (java.time.LocalDate/parse "2026-01-02") (:born decoded)))
      (is (= at (:closed_at decoded)))
      (is (m/validate :pg.sample/users decoded opts))
      (is (= 42 (:id (decode (assoc row :id "42") (pgmalli/transformer)))) "strings too")
      (testing "wall-clock values are read in :zone"
        (is (= (java.time.LocalDateTime/parse "2026-01-02T08:04:05")
               (:updated_at (decode (assoc row :updated_at at) (pgmalli/transformer {:zone tokyo})))))
        (is (= (java.time.LocalDate/parse "2026-01-02")
               (:born (decode (assoc row :born (java.util.Date/from at)) (pgmalli/transformer {:zone tokyo})))))
        (is (= (java.time.LocalDateTime/ofInstant at (java.time.ZoneId/systemDefault))
               (:updated_at (decode (assoc row :updated_at at) (pgmalli/transformer))))
            "default: the JVM's zone, as JDBC's read-as-instant used")))))

(def ^:private good
  {"sample.groups" [{:id 1 :name "a"} {:id 2 :name "b"}]
   "sample.users" [(assoc user :group_name "a")
                   (assoc user :id 2 :group_name "a" :referrer_id 1)]})

(deftest datasets
  (let [ds (data/dataset-schema registry)]
    (is (m/validate ds good opts))
    (is (not (m/validate ds (assoc good "sample.groups" [{:id 1 :name "a"} {:id 1 :name "b"}]) opts)) "duplicate primary key")
    (is (not (m/validate ds (assoc good "sample.groups" [{:id 1 :name "a"} {:id 2 :name "a"}]) opts)) "duplicate unique")
    (is (not (m/validate ds (assoc-in good ["sample.users" 0 :group_id] 9) opts)) "dangling foreign key")
    (is (not (m/validate ds (assoc-in good ["sample.users" 0 :group_name] "b") opts)) "composite foreign key checked as a whole")
    (is (m/validate ds (assoc-in good ["sample.users" 0 :group_name] nil) opts) "a NULL in a composite key is not checked")
    (is (not (m/validate ds (assoc-in good ["sample.users" 1 :referrer_id] 9) opts)) "self-reference")
    (testing "generated datasets satisfy all of it"
      (doseq [sample (tcg/sample (data/dataset-generator registry {:rows 6}) 8)]
        (is (m/validate ds sample opts) (pr-str sample))))))

(deftest self-references-with-a-shared-group
  ;; folders: parent_id -> same table, in the same group, never itself, NULL for roots
  (let [reg (pgmalli/registry {:registry {:pg.public/groups [:map {:pg/table "public.groups" :pg/primary-key ["id"]} [:id [:int {:pg/type "integer"}]]]
                                          :pg.public/folders [:and [:map {:pg/table "public.folders" :pg/primary-key ["id"] :pg/unique [{:columns ["id" "group_id"]}]
                                                                          :pg/foreign-keys [{:columns ["group_id"] :table "public.groups" :to ["id"]}
                                                                                            {:columns ["parent_id" "group_id"] :table "public.folders" :to ["id" "group_id"]}]}
                                                                    [:id [:int {:pg/type "integer"}]] [:group_id [:int {:pg/type "integer"}]]
                                                                    [:parent_id [:maybe [:int {:pg/type "integer"}]]]]
                                                              [:pg/check [:or [:is :parent_id nil] [:<> :parent_id :id]]]]}})
        ds (data/dataset-schema reg)]
    (doseq [sample (tcg/sample (data/dataset-generator reg {:rows 5}) 20)]
      (is (m/validate ds sample {:registry reg}) (pr-str sample))
      (is (= 5 (count (get sample "public.folders")))))
    (is (= #{"public.groups"} (set (keys (first (tcg/sample (data/dataset-generator reg {:rows 2 :except #{"public.folders"}}) 1)))))
        ":except leaves a table out")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"references public.groups, which :except leaves out"
                          (doall (tcg/sample (data/dataset-generator reg {:rows 2 :except #{"public.groups"}}) 1)))
        "but not one that kept tables reference")))

(deftest children-pinning-a-parent-column
  ;; exams: (content_id, content_type) -> contents (id, type), with CHECK (content_type = 'exam');
  ;; with few contents none may be an exam, so the reference grows the parent table
  (let [reg (pgmalli/registry {:registry {:pg.public/kinds [:enum "exam" "link" "manual" "quiz"]
                                          :pg.public/groups [:map {:pg/table "public.groups" :pg/primary-key ["id"]} [:id [:int {:pg/type "integer"}]]]
                                          :pg.public/contents [:map {:pg/table "public.contents" :pg/primary-key ["id"] :pg/unique [{:columns ["id" "type"]} {:columns ["id" "group_id"]}]
                                                                     :pg/foreign-keys [{:columns ["group_id"] :table "public.groups" :to ["id"]}]}
                                                               [:id [:int {:pg/type "integer"}]] [:group_id [:int {:pg/type "integer"}]]
                                                               [:type [:ref {:pg/type "kinds"} :pg.public/kinds]]]
                                          :pg.public/exams [:and [:map {:pg/table "public.exams" :pg/primary-key ["content_id"]
                                                                        :pg/foreign-keys [{:columns ["content_id" "content_type"] :table "public.contents" :to ["id" "type"]}
                                                                                          {:columns ["content_id" "group_id"] :table "public.contents" :to ["id" "group_id"]}
                                                                                          {:columns ["group_id"] :table "public.groups" :to ["id"]}]}
                                                                  [:content_id [:int {:pg/type "integer"}]] [:group_id [:int {:pg/type "integer"}]]
                                                                  [:content_type [:ref {:pg/type "kinds"} :pg.public/kinds]] [:score [:int {:pg/type "integer"}]]]
                                                            [:pg/check {:pg/constraint "exams_type"} [:= :content_type [:cast "exam" :kinds]]]]}})
        ds (data/dataset-schema reg)]
    (doseq [sample (tcg/sample (data/dataset-generator reg {:rows 3}) 15)]
      (is (m/validate ds sample {:registry reg}) (pr-str sample))
      (is (= 3 (count (get sample "public.exams"))))
      (is (every? #(= "exam" (:content_type %)) (get sample "public.exams"))))))

(deftest references-whose-columns-share-a-name
  ;; group_id -> contract_groups (group_id): the target column is spelled like the referencing one
  (let [reg (pgmalli/registry {:registry {:pg.public/contract_groups [:map {:pg/table "public.contract_groups" :pg/primary-key ["group_id"]} [:group_id [:int {:pg/type "integer"}]]]
                                          :pg.public/audiences [:map {:pg/table "public.audiences" :pg/primary-key ["id"] :pg/unique [{:columns ["id" "group_id"]}]
                                                                      :pg/foreign-keys [{:columns ["group_id"] :table "public.contract_groups" :to ["group_id"]}]}
                                                                [:id [:int {:pg/type "integer"}]] [:group_id [:int {:pg/type "integer"}]]]
                                          :pg.public/audience_blocks [:map {:pg/table "public.audience_blocks" :pg/primary-key ["id"]
                                                                            :pg/unique [{:columns ["id" "group_id"]} {:columns ["group_id" "audience_id" "sort_order"]}]
                                                                            :pg/foreign-keys [{:columns ["audience_id" "group_id"] :table "public.audiences" :to ["id" "group_id"]}
                                                                                              {:columns ["group_id"] :table "public.contract_groups" :to ["group_id"]}]}
                                                                      [:id [:int {:pg/type "integer"}]] [:group_id [:int {:pg/type "integer"}]]
                                                                      [:audience_id [:int {:pg/type "integer"}]] [:sort_order [:int {:pg/type "integer"}]]]}})
        ds (data/dataset-schema reg)]
    (doseq [sample (tcg/sample (data/dataset-generator reg {:rows 3}) 20)]
      (is (m/validate ds sample {:registry reg}) (pr-str sample))
      (is (= 3 (count (get sample "public.audience_blocks")))))))

(deftest keys-made-only-of-references
  ;; certification_tags: PK (group_id, certification_id, tag_id), two composite references sharing
  ;; group_id, and certifications themselves referencing users of the same group
  (let [t (fn [table pk uniques fks cols] (into [:map (cond-> {:pg/table (str "public." table) :pg/primary-key pk} (seq uniques) (assoc :pg/unique (mapv (fn [u] {:columns u}) uniques)) (seq fks) (assoc :pg/foreign-keys fks))] cols))
        int [:int {:pg/type "integer"}]
        reg (pgmalli/registry {:registry {:pg.public/groups (t "groups" ["id"] [] [] [[:id int]])
                                          :pg.public/users (t "users" ["id"] [["group_id" "tmb_user_id"]] [{:columns ["group_id"] :table "public.groups" :to ["id"]}]
                                                              [[:id int] [:group_id int] [:tmb_user_id int]])
                                          :pg.public/certifications (t "certifications" ["id"] [["id" "group_id"]]
                                                                       [{:columns ["group_id"] :table "public.groups" :to ["id"]}
                                                                        {:columns ["group_id" "updated_by"] :table "public.users" :to ["group_id" "tmb_user_id"]}]
                                                                       [[:id int] [:group_id int] [:updated_by int]])
                                          :pg.public/tags (t "tags" ["id"] [["id" "group_id"]] [{:columns ["group_id"] :table "public.groups" :to ["id"]}] [[:id int] [:group_id int]])
                                          :pg.public/certification_tags (t "certification_tags" ["group_id" "certification_id" "tag_id"] []
                                                                           [{:columns ["certification_id" "group_id"] :table "public.certifications" :to ["id" "group_id"]}
                                                                            {:columns ["tag_id" "group_id"] :table "public.tags" :to ["id" "group_id"]}]
                                                                           [[:group_id int] [:certification_id int] [:tag_id int]])}})
        ds (data/dataset-schema reg)]
    (doseq [sample (tcg/sample (data/dataset-generator reg {:rows 3}) 12)]
      (is (m/validate ds sample {:registry reg}) (pr-str sample))
      (is (= 3 (count (get sample "public.certification_tags")))))))

(deftest branches-are-filled-in
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:and [:map {:pg/table "public.t" :pg/primary-key ["id"]}
                                                              [:id [:int {:pg/type "integer"}]]
                                                              [:status [:enum {:pg/type "text"} "open" "closed"]]
                                                              [:closed_at [:maybe [:time/instant {:pg/type "timestamptz"}]]]
                                                              [:note [:maybe [:string {:pg/type "text"}]]]]
                                                        [:multi {:dispatch :status}
                                                         ["open" [:map [:closed_at :nil]]]
                                                         ["closed" [:map [:closed_at :time/instant] [:note [:string {:min 1}]]]]]]}})
        sample (first (tcg/sample (data/dataset-generator reg {:rows 8}) 1))]
    (is (= 8 (count (get sample "public.t"))) "a branching CHECK is met by construction")
    (is (nil? (-> sample meta :pgmalli/short)))))

(deftest branches-decide-nullable-references
  ;; approval requests: a pending request has no approver, an approved one an approver of the same group
  (let [int [:int {:pg/type "integer"}]
        reg (pgmalli/registry {:registry {:pg.public/groups [:map {:pg/table "public.groups" :pg/primary-key ["id"]} [:id int]]
                                          :pg.public/users [:map {:pg/table "public.users" :pg/primary-key ["id"] :pg/unique [{:columns ["id" "group_id"]}]
                                                                  :pg/foreign-keys [{:columns ["group_id"] :table "public.groups" :to ["id"]}]}
                                                            [:id int] [:group_id int]]
                                          :pg.public/requests [:and [:map {:pg/table "public.requests" :pg/primary-key ["id"]
                                                                           :pg/foreign-keys [{:columns ["group_id"] :table "public.groups" :to ["id"]}
                                                                                             {:columns ["requester_id" "group_id"] :table "public.users" :to ["id" "group_id"]}
                                                                                             {:columns ["approver_id" "group_id"] :table "public.users" :to ["id" "group_id"]}]}
                                                                     [:id int] [:group_id int] [:requester_id int] [:approver_id [:maybe int]]
                                                                     [:status [:enum {:pg/type "text"} "pending" "approved"]]]
                                                               [:multi {:dispatch :status}
                                                                ["pending" [:map [:approver_id :nil]]]
                                                                ["approved" [:map [:approver_id [:int {:min 1}]]]]]]}})
        ds (data/dataset-schema reg)]
    (doseq [sample (tcg/sample (data/dataset-generator reg {:rows 6}) 8)]
      (is (m/validate ds sample {:registry reg}) (pr-str sample))
      (is (= 6 (count (get sample "public.requests"))) (pr-str (-> sample meta :pgmalli/short))))))

(deftest generation-limits-are-recorded
  (let [reg (pgmalli/registry {:registry {:pg.public/never [:and [:map {:pg/table "public.never" :pg/primary-key ["a"]} [:a [:int {:pg/type "integer"}]]] [:pg/check {:pg/constraint "never" :error/message "never"} [:< 2 1]]]
                                          :pg.public/child [:map {:pg/table "public.child" :pg/foreign-keys [{:columns ["never_a"] :table "public.never" :to ["a"]}]} [:never_a [:int {:pg/type "integer"}]]]
                                          :pg.public/big [:map {:pg/table "public.big" :pg/primary-key ["id"]} [:id [:int {:pg/type "integer" :min 1000000 :max 2147483647}]]]}})
        sample (first (tcg/sample (data/dataset-generator reg {:rows 2}) 1))
        short (-> sample meta :pgmalli/short)]
    (is (= [] (get sample "public.never")) "a table no candidate row fits comes out empty")
    (is (= {:wanted 2 :got 0} (dissoc (get short "public.never") :reasons)) "and says so in the metadata")
    (is (str/includes? (get-in short ["public.never" :reasons 0 0]) "never") "naming the constraint")
    (is (= {:wanted 2 :got 0 :reasons [["nothing to reference: public.child [\"never_a\"] references public.never [\"a\"]" 200]]} (get short "public.child"))
        "its children come out short, saying why")
    (is (m/validate (data/dataset-schema reg) sample {:registry reg}) "and the dataset is still valid")
    (is (every? #(>= (:id %) 1000000) (tcg/sample (mg/generator (pgmalli/columns reg :pg.public/big) {:registry reg}) 20))
        "a key bound above the hint range keeps the bound and drops the hint")))

(deftest generated-values-look-like-data
  (let [rows (tcg/sample (mg/generator (pgmalli/columns registry :pg.sample/users) opts) 40)
        year-ago (.minus (java.time.Instant/now) (java.time.Duration/ofDays 366))]
    (is (every? #(pos? (:id %)) rows) "keys are positive")
    (is (every? #(<= (:id %) 100000) rows) "and small")
    (is (every? #(or (nil? (:nick %)) (<= (count (:nick %)) 24)) rows) "strings are short")
    (is (every? #(or (nil? (:closed_at %)) (.isAfter ^java.time.Instant (:closed_at %) year-ago)) rows) "times are recent")
    (is (every? #(pos? (:group_id %)) rows) "referencing columns too")))

(deftest transformer-parses-json-text
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:params [:any {:pg/type "jsonb"}]] [:note [:maybe [:string {:pg/type "text"}]]]]}})
        decoded (m/decode :pg.public/t {:params "{\"a\": [1, 2]}" :note "{\"b\": 1}"} {:registry reg} (pgmalli/transformer))]
    (is (= {"a" [1 2]} (:params decoded)) "JSON text in a jsonb column is parsed")
    (is (= "{\"b\": 1}" (:note decoded)) "text stays text")))

(deftest key-and-reference-rules
  (let [reg (pgmalli/registry {:registry {"pg.public/Order Items" [:map {:pg/table "public.Order Items" :pg/primary-key ["Order ID"] :pg/unique [{:columns ["code"] :nulls-distinct false}]
                                                                        :pg/foreign-keys [{:columns ["Parent ID" "group"] :table "public.Parents" :to ["id" "group"] :match :full}]}
                                                                   ["Order ID" [:int {:pg/type "integer"}]]
                                                                   [:code [:maybe [:string {:pg/type "text"}]]]
                                                                   [:group [:maybe [:int {:pg/type "integer"}]]]
                                                                   ["Parent ID" [:maybe [:int {:pg/type "integer"}]]]]
                                          :pg.public/Parents [:map {:pg/table "public.Parents" :pg/primary-key ["id"]}
                                                              [:id [:int {:pg/type "integer"}]] [:group [:int {:pg/type "integer"}]]]}})
        ds (data/dataset-schema reg)
        parents {"public.Parents" [{:id 1 :group 1}]}
        valid? (fn [rows] (m/validate ds (assoc parents "public.Order Items" rows) {:registry reg}))
        errors (fn [rows] (me/humanize (m/explain ds (assoc parents "public.Order Items" rows) {:registry reg})))
        row (fn [id & [m]] (merge {"Order ID" id :code (str "c" id) :group nil "Parent ID" nil} m))]
    (is (valid? [(row 1) (row 2 {"Parent ID" 1 :group 1})]))
    (is (not (valid? [(row 1) (row 1 {:code "x"})])) "columns that are not plain identifiers are checked like any other")
    (is (= ["public.Order Items primary key [\"Order ID\"]"] (errors [(row 1) (row 1 {:code "x"})])) "each constraint reports under its own name")
    (is (not (valid? [(row 1 {:code nil}) (row 2 {:code nil})])) "NULLS NOT DISTINCT: two NULL codes collide")
    (is (not (valid? [(row 1 {"Parent ID" 9 :group 1})])))
    (is (not (valid? [(row 1 {"Parent ID" 1})])) "MATCH FULL: a partly NULL key is rejected")
    (is (= ["public.Order Items [\"Parent ID\" \"group\"] references public.Parents [\"id\" \"group\"]"] (errors [(row 1 {"Parent ID" 1})])))))

(deftest references-sharing-columns
  ;; the tenant pattern: every table carries group_id, and composite references carry it along
  (let [reg (pgmalli/registry {:registry {:pg.public/groups [:map {:pg/table "public.groups" :pg/primary-key ["id"]} [:id [:int {:pg/type "integer"}]]]
                                          :pg.public/parents [:map {:pg/table "public.parents" :pg/primary-key ["id"] :pg/unique [{:columns ["id" "group_id"]}]
                                                                    :pg/foreign-keys [{:columns ["group_id"] :table "public.groups" :to ["id"]}]}
                                                              [:id [:int {:pg/type "integer"}]] [:group_id [:int {:pg/type "integer"}]]]
                                          :pg.public/children [:map {:pg/table "public.children" :pg/primary-key ["id"] :pg/unique [{:columns ["id" "group_id"]}]
                                                                     :pg/foreign-keys [{:columns ["group_id"] :table "public.groups" :to ["id"]}
                                                                                       {:columns ["parent_id" "group_id"] :table "public.parents" :to ["id" "group_id"]}
                                                                                       {:columns ["sibling_id" "group_id"] :table "public.children" :to ["id" "group_id"]}]}
                                                               [:id [:int {:pg/type "integer"}]] [:group_id [:int {:pg/type "integer"}]] [:parent_id [:int {:pg/type "integer"}]]
                                                               [:sibling_id [:maybe [:int {:pg/type "integer"}]]]]}})
        ds (data/dataset-schema reg)]
    (doseq [sample (tcg/sample (data/dataset-generator reg {:rows 6}) 30)]
      (is (m/validate ds sample {:registry reg}) (pr-str sample)))
    (is (some #(seq (get % "public.children")) (tcg/sample (data/dataset-generator reg {:rows 6}) 10)) "and rows do get generated")))

(deftest views-are-rows-only
  (let [reg (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t" :pg/primary-key ["id"]} [:id [:pg/integer {:pg/type "integer"}]]]
                                          :pg.public/v [:map {:pg/view "public.v"} [:id [:maybe [:pg/integer {:pg/type "integer"}]]]]}})]
    (is (m/validate :pg.public/v {:id nil} {:registry reg}))
    (is (nil? (get reg :pg.public.v/insert)) "no insert schema for a view")
    (is (= #{"public.t"} (set (keys (first (tcg/sample (data/dataset-generator reg {:rows 1}) 1))))) "not part of datasets")))

(deftest other-shapes-of-the-same-schema
  (testing "portable: what malli's default registry reads"
    (let [p (pgmalli/portable registry :pg.sample/users)
          opts {:registry (merge (m/default-schemas) (malli.experimental.time/schemas))}]
      (is (= [:enum {:default "happy" :pg/default "happy" :pg/type "mood"} "happy" "sad"] (get-in p [1 7 1])) "the enum is inlined")
      (is (= [:int {:pg/type "integer" :min -2147483648 :max 2147483647}] (get-in p [1 4 1])) "integers carry their range")
      (is (= :multi (first (nth p 2))) "the branching CHECK stays")
      (is (= 3 (count p)) "the :pg/check constraints are left out")
      (is (m/validate p (assoc user :group_name "a") opts))
      (is (not (m/validate p (assoc user :group_id 2147483648) opts)))
      (is (= 'bytes? (get-in (pgmalli/portable (pgmalli/registry {:registry {:pg.public/t [:map {:pg/table "public.t"} [:d [:pg/bytes {:min 32 :max 32 :pg/type "bytea"}]]]}}) :pg.public/t) [2 1])))
      (is (not-any? #(and (map? %) (or (:gen/min %) (:gen/max %))) (tree-seq coll? seq p)) "no generation hints")))
  (testing "as-read: the map as next.jdbc builds it"
    (let [r (pgmalli/as-read registry :pg.sample/users {:qualified? true :nil-columns :absent :time :instant})]
      (is (= [:users/group_name {:optional true} [:string {:pg/type "text"}]] (nth r 5)) "NULL columns absent, keys qualified")
      (is (= [:users/updated_at {:optional true} [:time/instant {:pg/type "timestamp"}]] (last r)) "timestamps as Instants")
      (is (= [:users/born {:optional true} ['inst? {:pg/type "date"}]] (nth r 2)) "dates stay java.sql.Date under read-as-instant")
      (is (m/validate r {:users/id 1 :users/group_id 1 :users/mood "sad" :users/seq 1 :users/score 1 :users/total 2} opts)))
    (is (some #{[:nick-upper [:maybe [:string {:pg/generated [:upper [:cast :nick :text]] :pg/type "text"}]]]} (pgmalli/as-read registry :pg.sample/users {:kebab? true})))
    (is (= :order-items/line-no (first (nth (pgmalli/as-read (pgmalli/registry {:registry {:pg.public/order_items [:map {:pg/table "public.order_items"} [:line_no [:int {:pg/type "integer"}]]]}}) :pg.public/order_items {:qualified? true :kebab? true}) 2)))
        "the table half is kebab-cased too"))
  (testing "portable converts what it inlines"
    (let [reg (pgmalli/registry {:registry {:pg.public/code [:and :string [:pg/check-value {:pg/constraint "c"} [:<> :VALUE ""]]]
                                            :pg.public/t [:map {:pg/table "public.t"} [:c [:ref {:pg/type "code"} :pg.public/code]]]}})]
      (is (= [:map {:pg/table "public.t"} [:c [:string {:pg/type "code"}]]] (pgmalli/portable reg :pg.public/t)))))
  (testing "column and non-null"
    (is (= [:maybe [:string {:max 40 :pg/type "character varying"}]] (pgmalli/column registry :pg.sample/users :nick)))
    (is (= [:string {:max 40 :pg/type "character varying"}] (pgmalli/non-null (pgmalli/column registry :pg.sample/users "nick"))))
    (is (nil? (pgmalli/column registry :pg.sample/users :nope)))))

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

(deftest inserts-in-the-order-the-database-accepts
  (let [ds (tcg/generate (data/dataset-generator registry {:rows 4}) 30 42)
        stmts (data/inserts registry ds)
        by-table (into {} (map (juxt #(let [i (:insert-into %)] (if (vector? i) (last i) i)) identity)) stmts)]
    (is (= [:sample.groups [{:overriding-value :system} :sample.users]] (map :insert-into stmts)) "parents first; identity columns kept")
    (is (every? #(and (vector? %) (= [:cast :sample.mood] [(first %) (last %)])) (keep :mood (:values (by-table :sample.users)))) "an enum is cast")
    (is (not-any? #(contains? % :nick_upper) (:values (by-table :sample.users))) "generated columns are left out")
    (is (= [[{:overriding-value :system} :sample.users]] (map :insert-into (data/inserts registry (select-keys ds ["sample.users"]))))
        "a parent left out is already in the database")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not" (data/inserts registry {"sample.nope" [{:id 1}]})))
    (is (= [] (data/inserts registry {"sample.groups" []})) "no rows, no INSERT")
    (is (every? (fn [[i row]] (or (nil? (:referrer_id row)) (= (:referrer_id row) (:id row))
                                  (some #(= (:referrer_id row) (:id %)) (take i (:values (by-table :sample.users))))))
                (map-indexed vector (:values (by-table :sample.users))))
        "a row comes after the row it refers to")
    (is (every? #(string? (first (honey.sql/format %))) stmts)))
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/docs [:map {:pg/table "public.docs" :pg/primary-key ["id"]}
                                                           [:id [:int {:pg/type "integer"}]]
                                                           [:body [:any {:pg/type "jsonb"}]]
                                                           [:tags [:maybe [:vector {:pg/type "text[]"} :string]]]]}})
        [{:keys [values]}] (data/inserts reg {"public.docs" [{:id 1 :body {"a" 1} :tags ["x"]} {:id 2 :body [] :tags nil}]})]
    (is (= [{:id 1 :body [:cast "{\"a\":1}" :jsonb] :tags [:array ["x"] :text]} {:id 2 :body [:cast "[]" :jsonb] :tags nil}] values)
        "json written and cast, arrays with their element type, NULL as it is")
    (is (every? #(try (json/write %) true (catch Exception _ false))
                (map :body (tcg/sample (mg/generator :pg.public/docs {:registry reg}) 50)))
        "an unshaped jsonb column generates values JSON can carry")
    (is (= [[{:id 1 :body [:cast "1" :jsonb]} {:id 2 :body [:default]}]]
           (map :values (data/inserts reg {"public.docs" [{:id 1 :body 1} {:id 2}]})))
        "one INSERT per table; a column a row lacks is DEFAULT")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"columns the table does not have"
                          (doall (data/inserts reg {"public.docs" [{:id 1 :nope 2}]})))
        "a column the table does not have never reaches the database")))

(deftest branches-are-filled-even-when-the-dispatch-column-cannot-be-null
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/t [:and [:map {:pg/table "public.t" :pg/primary-key ["id"]}
                                                              [:id [:int {:pg/type "integer"}]]
                                                              [:status [:string {:pg/type "text"}]]
                                                              [:result [:maybe [:any {:pg/type "jsonb"}]]]]
                                                        [:multi {:dispatch :status}
                                                         ["open" [:map [:result :nil]]]
                                                         ["done" [:map [:result :some]]]
                                                         [:malli.core/default [:map [:status :nil]]]]]}})
        ds (tcg/generate (data/dataset-generator reg {:rows 6}) 30 3)
        rows (get ds "public.t")]
    (is (= 6 (count rows)) "no row is lost to the default branch")
    (is (every? #{"open" "done"} (map :status rows)))
    (is (every? #(or (= "open" (:status %)) (some? (:result %))) rows))
    (is (m/validate (data/dataset-schema reg) ds {:registry reg}))))

(deftest a-not-null-jsonb-column-still-generates-json
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/t [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]] [:body [:some {:pg/type "jsonb"}]]]}})]
    (is (every? #(and (some? %) (try (json/write %) true (catch Exception _ false)))
                (map :body (tcg/sample (mg/generator :pg.public/t {:registry reg}) 50)))
        "IS NOT NULL on jsonb: JSON values, none of them nil")))

(deftest opaque-types-and-bit-strings-generate-what-the-database-reads
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:ip [:any {:pg/type "inet"}]]
                                                        [:r [:maybe [:any {:pg/type "int4range"}]]]
                                                        [:b [:string {:pg/type "bit" :min 4 :max 4}]]
                                                        [:vb [:string {:pg/type "bit varying" :max 3}]]]}})
        rows (tcg/sample (mg/generator :pg.public/t {:registry reg}) 40)]
    (is (every? #{"10.0.0.1" "192.168.1.0/24" "::1"} (map :ip rows)))
    (is (every? #(or (nil? %) (#{"[1,10)" "[20,30)" "(,5]"} %)) (map :r rows)))
    (is (every? #(re-matches #"[01]{4}" %) (map :b rows)))
    (is (every? #(re-matches #"[01]{0,3}" %) (map :vb rows)))
    (is (= [:map {:pg/table "public.t"} [:ip [:any {:pg/type "inet"}]] [:r [:maybe [:any {:pg/type "int4range"}]]] [:b [:string {:pg/type "bit" :min 4 :max 4}]] [:vb [:string {:pg/type "bit varying" :max 3}]]]
           (pgmalli/portable reg :pg.public/t)) "the hints stay out of portable data")))

(deftest a-list-partition-keeps-the-value-that-picked-its-branch
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/t [:and [:map {:pg/table "public.t"} [:id [:int {:pg/type "integer"}]] [:c [:maybe [:string {:pg/type "text"}]]]]
                                                        ;; as a LIST partition renders: the branch names the dispatch column (c IS NOT NULL)
                                                        [:multi {:dispatch :c}
                                                         ["0000" [:map [:c :string]]]
                                                         ["0005" [:map [:c :string]]]
                                                         [:malli.core/default [:map [:c :nil]]]]]}})
        ds (tcg/generate (data/dataset-generator reg {:rows 6}) 30 5)]
    (is (= 6 (count (get ds "public.t"))) "no row is lost")
    (is (every? #{"0000" "0005"} (map :c (get ds "public.t"))) "the value that picked the branch is not regenerated")))

(deftest a-numeric-bounded-by-checks-generates-within-them
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/t [:map {:pg/table "public.t"}
                                                        [:a [:and {:pg/type "numeric"} 'decimal? [:> 1] [:< 1000]]]
                                                        [:b [:and {:pg/type "numeric"} 'decimal? [:pg/numeric {:precision 5 :scale 2}] [:>= 0.5]]]]}})
        rows (tcg/sample (mg/generator :pg.public/t {:registry reg}) 60)]
    (is (every? #(< 1 (:a %) 1000) (map identity rows)))
    (is (every? #(<= 0.5M (:b %)) rows))
    (is (every? #(and (decimal? (:a %)) (decimal? (:b %))) rows))
    (is (= [:map {:pg/table "public.t"} [:a [:and {:pg/type "numeric"} 'decimal? [:> 1] [:< 1000]]] [:b [:and {:pg/type "numeric"} 'decimal? [:>= 0.5]]]]
           (pgmalli/portable reg :pg.public/t)) "the hints stay out of portable data; :pg/numeric is decimal? there")))

(deftest reading-options-of-a-next-jdbc-builder
  (is (= {:qualified? true} (pgmalli/read-options 'next.jdbc/as-maps)))
  (is (= {} (pgmalli/read-options 'next.jdbc/as-unqualified-lower-maps)))
  (is (= {:kebab? true :nil-columns :absent} (pgmalli/read-options 'next.jdbc.optional/as-unqualified-kebab-maps)))
  (is (nil? (pgmalli/read-options 'next.jdbc/as-arrays)) "no map, no options")
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not a next.jdbc" (pgmalli/read-options 'my.ns/as-things)))
  (is (= (pgmalli/as-read registry :pg.sample/users (pgmalli/read-options 'next.jdbc.optional/as-unqualified-kebab-maps))
         (pgmalli/as-read registry :pg.sample/users {:kebab? true :nil-columns :absent}))
      "the options are as-read's"))

(deftest a-dataset-kept-as-edn-comes-back-as-it-was
  (let [ds (tcg/generate (data/dataset-generator registry {:rows 3}) 30 11)
        path (str (java.io.File/createTempFile "pgmalli-ds" ".edn"))
        _ (data/write-dataset path ds)
        back (data/read-dataset path)]
    (is (= (update-vals ds vec) (update-vals back vec)) "java.time values round-trip under pgmalli's tags")
    (is (m/validate (data/dataset-schema registry) back opts))
    (is (nil? (data/short-tables ds)) "nothing short in the sample")
    (is (= (data/short-tables ds) (data/short-tables back)) "what came out short survives the file")
    (is (re-find #"#pgmalli/" (slurp path)) "the file carries the tags"))
  (let [bytes-ds {"public.t" [{:id 1 :b (byte-array [1 2 255])}]}
        path (str (java.io.File/createTempFile "pgmalli-bytes" ".edn"))]
    (data/write-dataset path bytes-ds)
    (is (= [1 2 -1] (seq (:b (first (get (data/read-dataset path) "public.t"))))) "bytes as hex")))

(deftest install-makes-the-names-readable-everywhere
  (let [before (pgmalli/install! "sample")]
    (is (m/validate :pg.sample/groups {:id 1 :name "g"}) "no registry passed: malli's default one has it now")
    (is (not (m/validate :pg.sample/groups {:id "x" :name "g"})))
    (is (map? before) "what the default registry held, to put back")
    (malli.registry/set-default-registry! before)
    (is (thrown? Exception (m/validate :pg.sample/groups {:id 1 :name "g"})) "put back: the names are gone again")))

(deftest a-short-dataset-keeps-its-reasons-through-the-file
  (let [ds (with-meta {"public.t" [{:id 1}]} {:pgmalli/short {"public.t" {:wanted 3 :got 1 :reasons [["x" 2]]}}})
        path (str (java.io.File/createTempFile "pgmalli-short" ".edn"))]
    (data/write-dataset path ds)
    (is (= {"public.t" {:wanted 3 :got 1 :reasons [["x" 2]]}} (data/short-tables (data/read-dataset path))))
    (is (= {"public.t" [{:id 1}]} (data/read-dataset path)) "the tables alone are the value")))

(deftest regex-checks-generate-what-matches
  (when @pgmalli.impl.registry/regex-generation?
   (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/email [:and :string [:re "^[^@]+@[^@]+$"]]
                                          :pg.public/t [:map {:pg/table "public.t"}
                                                        [:sku [:and {:pg/type "text"} :string [:re "^[A-Z]{3}-[0-9]{4}$"]]]
                                                        [:mail [:ref {:pg/type "email"} :pg.public/email]]
                                                        [:code [:and {:pg/type "text"} [:string {:max 10}] [:re "^ab.*$"]]]]}})
        rows (mg/sample :pg.public/t {:registry reg :size 30})]
    (is (= 30 (count rows)))
    (is (every? #(re-matches #"[A-Z]{3}-[0-9]{4}" (:sku %)) rows))
    (is (every? #(re-matches #"[^@]+@[^@]+" (:mail %)) rows) "a domain with a regex CHECK too")
    (is (every? #(and (<= (count (:code %)) 10) (str/starts-with? (:code %) "ab")) rows) "a LIKE pattern, within the length")
    (is (= [:map {:pg/table "public.t"} [:sku [:and {:pg/type "text"} :string [:re "^[A-Z]{3}-[0-9]{4}$"]]] [:mail [:and {:pg/type "email"} :string [:re "^[^@]+@[^@]+$"]]] [:code [:and {:pg/type "text"} [:string {:max 10}] [:re "^ab.*$"]]]]
           (pgmalli/portable reg :pg.public/t)) "the hint stays out of portable data"))))

(deftest any-malli-registry-will-do
  (let [composite (malli.registry/composite-registry registry {:app/flag :boolean})]
    (is (m/validate :pg.sample/groups {:id 1 :name "g"} {:registry composite}))
    (is (= [] (h/check composite {:select [:id] :from [:groups]} {:schema "sample"})))
    (is (= 2 (count (keys (tcg/generate (data/dataset-generator composite {:rows 2}) 20 1)))) "datasets from a composite registry")
    (is (= (pgmalli/column registry :pg.sample/users :nick) (pgmalli/column composite :pg.sample/users :nick)))
    (is (= (pgmalli/portable registry :pg.sample/users) (pgmalli/portable composite :pg.sample/users)) "portable inlines references through a composite registry")
    (is (= (h/query-schema registry '[id] {:select [:id] :from [:groups]} {:schema "sample"})
           (h/query-schema composite '[id] {:select [:id] :from [:groups]} {:schema "sample"})))))

(deftest text-decodes-into-the-bounded-numbers
  (let [reg (pgmalli/registry {:database-version "x"
                               :registry {:pg.public/t [:map {:pg/table "public.t"} [:a [:pg/integer {:pg/type "integer"}]] [:b [:pg/smallint {:pg/type "smallint"}]]
                                                        [:c [:and {:pg/type "numeric"} 'decimal? [:pg/numeric {:precision 5 :scale 2}]]] [:d [:int {:pg/type "bigint"}]]]}})]
    (is (= {:a 5 :b 7 :c 1.25M :d 9} (m/decode :pg.public/t {:a "5" :b "7" :c "1.25" :d "9"} {:registry reg} (pgmalli/transformer))))
    (is (= {:a "x" :b 7 :c "y" :d 9} (m/decode :pg.public/t {:a "x" :b 7 :c "y" :d "9"} {:registry reg} (pgmalli/transformer))) "what does not parse stays as it was")))

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

(deftest a-migration-read-from-two-files
  (let [before (pgmalli/generated "sample")
        after (assoc-in before [:registry :pg.sample/groups] (conj (get-in before [:registry :pg.sample/groups]) [:motto [:maybe [:string {:pg/type "text"}]]]))]
    (is (= [{:name :pg.sample/groups :column :motto :file nil :db [:maybe [:string {:pg/type "text"}]]}]
           (pgmalli.generate/diff before after)))
    (is (= [] (pgmalli.generate/diff before before)))))
