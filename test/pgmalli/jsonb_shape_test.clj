(ns pgmalli.jsonb-shape-test
  "A jsonb column's shape (an override's) is the same given as itself or as [:ref k] beside the
   registry's k, and travels to the part of it a call takes, as json-parts tells, and to no other
   call of the catalog."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [clojure.walk :as walk]
            honey.sql.pg-ops
            [malli.registry :as mr]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.query :as query]
            [pgmalli.impl.shape :as shape]
            [pgmalli.impl.types :as types]))

(def ^:private shapes
  {:app/doc [:map [:a [:vector :int]] [:b :string]]
   :app/rows [:vector [:ref :app/row]]
   :app/row [:map [:k :string]]})

(defn- table [doc rows]
  {:pg.public/d [:map {:pg/table "public.d" :pg/primary-key ["id"]}
                 [:id [:int {:pg/type "bigint"}]] [:doc doc] [:rows [:maybe rows]]]})

(def ^:private direct
  (mr/composite-registry (pgmalli/registry {:registry (table (shape/merge-props (:app/doc shapes) {:pg/type "jsonb"})
                                                             [:vector {:pg/type "jsonb"} (:app/row shapes)])})
                         shapes))

(def ^:private by-name
  (mr/composite-registry (pgmalli/registry {:registry (table [:ref {:pg/type "jsonb"} :app/doc] [:ref {:pg/type "jsonb"} :app/rows])})
                         shapes))

(defn- named-out
  "Schema form s with each reference to a name of shapes replaced by the schema it names."
  [s]
  (walk/prewalk (fn [x]
                  (cond (and (vector? x) (= :ref (first x)) (contains? shapes (last x)))
                        (named-out (shape/merge-props (shapes (last x)) (or (shape/props x) {})))
                        (and (keyword? x) (contains? shapes x)) (named-out (shapes x))
                        :else x))
                s))

(deftest a-shape-given-by-name-is-the-shape-given-as-itself
  (doseq [q [{:select [:doc :rows] :from [:d]}
             {:select [[[:-> :doc [:inline "a"]] :a] [[:-> :rows [:inline 0]] :r] [[:- :doc [:inline "b"]] :m]] :from [:d]}
             {:select [[[:jsonb_array_elements :rows] :e]] :from [:d]}
             {:select [[[:coalesce :doc :doc] :c] [[:jsonb_strip_nulls :doc] :s]] :from [:d]}
             {:select [:id] :from [:d] :where [:= :doc :?doc]}
             {:update :d :set {:doc :?doc :rows :?rows} :where [:= :id 1]}
             {:insert-into :d :values [{:id 1 :doc :?doc :rows :?rows}] :returning [:doc]}]
          :let [[a b] (map #(h/analyze % q {:schema "public"}) [direct by-name])]]
    (is (= (named-out (:row a)) (named-out (:row b))) (pr-str q))
    (is (= (named-out (:args a)) (named-out (:args b))) (pr-str q))))

(defn- typed-null
  "NULL cast to type t, which picks the overload taking t; nil for a type no value is cast to."
  [t]
  ;; anyelement, anyarray, anycompatible...: a polymorphic type, no type a value is cast to
  (when-not (or (str/starts-with? t "any") (= "\"any\"" t) (#{"internal" "cstring"} t))
    [:cast nil [:raw t]]))

(deftest no-call-of-the-catalog-but-json-parts-takes-the-shape
  (let [parts (set (keys @#'query/json-parts))
        catalog @types/catalog
        calls (for [kind [:operators :functions]
                    [n os] (kind catalog)
                    :when (not (parts [kind n]))
                    o os
                    :when (and (= "jsonb" (first (:args o))) (= "jsonb" (:result o)))
                    :let [others (map typed-null (rest (:args o)))]
                    :when (every? some? others)]
                (into [(keyword n) :doc] others))]
    (is (seq calls))
    (doseq [c calls
            :let [s (shape/non-null (last (last (:row (h/analyze direct {:select [[c :v]] :from [:d]} {:schema "public"})))))]]
      (is (not (#{:map :map-of :vector :sequential :ref} (shape/head s))) (str (pr-str c) " is of no shape: " (pr-str s))))))
