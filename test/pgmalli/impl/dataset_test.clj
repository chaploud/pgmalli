(ns pgmalli.impl.dataset-test
  "Datasets checked and generated, on the checked-in test/resources/pgmalli/sample.edn."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check.generators :as tcg]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [pgmalli.core :as pgmalli]
            [pgmalli.data :as data]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.registry]
            [pgmalli.sample :refer [good opts registry]]))

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
