(ns pgmalli-next.rendered-table-test
  "Queries against a table rendered from facts as generation renders them: identity, domains,
   a varchar(n)."
  (:require [clojure.test :refer [deftest is]]
            [malli.core :as m]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.pattern :as p]
            [pgmalli-next.impl.render :as r]))

(def ^:private generated
  (r/rendered (p/facts {:name "public"
                        :types {}
                        :tables {"t" {:columns [{:name "id" :position 1 :data_type "bigint" :is_nullable false :identity "ALWAYS"}
                                                {:name "l" :position 2 :data_type "text" :domain "lbl" :is_nullable false}
                                                {:name "v" :position 3 :data_type "bigint" :domain "lvl" :is_nullable false}
                                                {:name "n" :position 4 :data_type "integer" :is_nullable false}
                                                {:name "s" :position 5 :data_type "character varying" :is_nullable true :max_length 10}]
                                      :constraints {}}}})))

(def ^:private registry (pgmalli/registry generated))

(deftest a-cast-to-a-type-with-its-length-has-the-type
  (is (= [:map [:x [:maybe [:string {:pg/type "character varying"}]]]]
         (:row (h/analyze registry {:select [[[:cast :s [:varchar 5]] :x]] :from [:t]} {}))))
  (is (= :string (first (second (:x (:args (h/analyze registry {:select [:id] :from [:t] :where [:= :s [:cast :?x [:varchar 5]]]}))))))))

(deftest a-format-that-quotes-names-writes-default-only-as-a-call
  (let [row {:l "a" :v 1 :n 1}]
    (is (m/validate (h/values-schema registry :t) (assoc row :id :default) {:registry registry}))
    (is (not (m/validate (h/values-schema registry :t {:format {:quoted true}}) (assoc row :id :default) {:registry registry}))
        "HoneySQL writes :default as \"default\", a column")
    (is (m/validate (h/values-schema registry :t {:format {:quoted true}}) (assoc row :id [:default]) {:registry registry}))))

(deftest an-unknown-schema-names-tables-before-what-derives-from-them
  (is (= [:pg.public/t :pg.public.t/insert :pg.public.t/update]
         (take 3 (:known (ex-data (try (pgmalli/as-read registry :pg.public/zz) (catch clojure.lang.ExceptionInfo e e))))))))
