(ns pgmalli.labels-test
  "A CHECK's list of values, on a column or a domain, against the literals and arguments
   PostgreSQL reads as its type's values."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.pattern :as p]
            [pgmalli.impl.render :as r]))

(def ^:private generated
  (r/rendered (p/facts {:name "public"
                        :types {"lbl" {:kind "DOMAIN" :base_type "text" :not_null false
                                       :constraints [{:name "lbl_check" :definition "CHECK ((VALUE = ANY (ARRAY['a'::text, 'b'::text])))"}]}
                                "lvl" {:kind "DOMAIN" :base_type "bigint" :not_null false
                                       :constraints [{:name "lvl_check" :definition "CHECK ((VALUE = ANY (ARRAY[(1)::bigint, (2)::bigint])))"}]}}
                        :tables {"t" {:columns [{:name "id" :position 1 :data_type "bigint" :is_nullable false :identity "ALWAYS"}
                                                {:name "l" :position 2 :data_type "lbl" :type_schema "public" :is_nullable false}
                                                {:name "v" :position 3 :data_type "lvl" :type_schema "public" :is_nullable false}
                                                {:name "n" :position 4 :data_type "integer" :is_nullable false}
                                                {:name "s" :position 5 :data_type "character varying" :is_nullable true :max_length 10}]
                                      :constraints {"n_check" {:name "n_check" :type "CHECK" :check_clause "CHECK (n = ANY (ARRAY[1, 2]))"}}}}})))

(def ^:private registry (pgmalli/registry generated))

(deftest a-domains-list-keeps-its-base-type-and-is-no-enum-type
  (is (= [:enum {:pg/type "text"} "a" "b"] (get-in generated [:registry :pg.public/lbl])))
  (is (= [:enum {:pg/type "bigint"} 1 2] (get-in generated [:registry :pg.public/lvl])))
  (is (= [] (h/check registry {:select [:id] :from [:t] :where [:= :l "a"]}))
      "a varchar goes to a text domain, as PostgreSQL reads it")
  (is (= [] (h/check registry {:select [:id] :from [:t] :where [:and [:= :l :?x] [:= :v :?y]]})))
  (is (= [{:kind :enum-literal :column :l :value "c" :allowed ["a" "b"]}]
         (h/check registry {:select [:id] :from [:t] :where [:= :l "c"]}))
      "a value outside the list still matches no row")
  (is (= [] (h/check registry {:select [:id] :from [:t] :where [:or [:<> :l "c"] [:< :l "c"] [:not-in :l ["c"]]]}))
      "other comparisons compare it as its base type"))

(deftest a-literal-is-a-number-label-as-postgresql-reads-it
  (is (= [] (h/check registry {:select [:id] :from [:t] :where [:= :n "1"]} {:send {String :unknown}}))
      "an untyped '1' is 1")
  (is (= [] (h/check registry {:select [:id] :from [:t] :where [:= :v "2"]} {:send {String :unknown}})))
  (is (= [] (h/check registry {:select [:id] :from [:t] :where [:= :n 1.0]})))
  (is (= [] (h/check registry {:select [:id] :from [:t] :where [:in :n ["0x1" "2" " 1 " "0b10" "0x_1"]]} {:send {String :unknown}}))
      "PostgreSQL 16 reads 0x, 0o and 0b integers")
  (is (= [{:kind :enum-literal :column :n :value 3 :allowed [1 2]}]
         (h/check registry {:select [:id] :from [:t] :where [:= :n 3]}))
      "a number outside the list matches no row")
  (is (= [{:kind :enum-literal :column :n :value 3 :allowed [1 2]}]
         (h/check registry {:select [:id] :from [:t] :where [:in :n [1 3]]}))))

(deftest a-cast-to-a-type-with-its-length-has-the-type
  (is (= [:map [:x [:maybe [:string {:pg/type "character varying"}]]]]
         (h/row-schema registry {:select [[[:cast :s [:varchar 5]] :x]] :from [:t]} {:jdbc {:builder-fn 'next.jdbc.result-set/as-unqualified-maps}})))
  (is (= :string (first (second (:x (h/arg-types registry {:select [:id] :from [:t] :where [:= :s [:cast :?x [:varchar 5]]]})))))))

(deftest a-format-that-quotes-names-writes-default-only-as-a-call
  (let [row {:l "a" :v 1 :n 1}]
    (is (m/validate (h/values-schema registry :t) (assoc row :id :default) {:registry registry}))
    (is (not (m/validate (h/values-schema registry :t {:format {:quoted true}}) (assoc row :id :default) {:registry registry}))
        "HoneySQL writes :default as \"default\", a column")
    (is (m/validate (h/values-schema registry :t {:format {:quoted true}}) (assoc row :id [:default]) {:registry registry}))))

(deftest an-unknown-schema-names-tables-before-what-derives-from-them
  (is (= [:pg.public/lbl :pg.public/lvl :pg.public/t]
         (take 3 (:known (ex-data (try (pgmalli/as-read registry :pg.public/zz) (catch clojure.lang.ExceptionInfo e e))))))))

(deftest a-coalesce-of-a-check-list-takes-the-others-values-too
  (is (= [] (h/check registry {:select [:s.id] :from [[{:select [:id [[:coalesce :n 3] :cc]] :from [:t]} :s]] :where [:= :s.cc 3]}))))

(deftest a-recursive-ctes-later-rows-hold-values-beyond-its-first-members-list
  (is (= [] (h/check registry {:with-recursive [[:c {:union-all [{:select [:n] :from [:t]} {:select [[[:+ :n 1] :n]] :from [:c]}]}]]
                               :select [:n] :from [:c] :where [:= :c.n 3]}))))
