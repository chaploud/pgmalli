(ns pgmalli.pipeline
  "The steps bb sweep and pgmalli.catalog-fuzz-test take a schema through, and the findings
   they share: what did not go through, one map each with its :kind."
  (:require [pgmalli.impl.shape :as shape]))

(defn try-step
  "[value finding]: the step's value, or a finding of kind :error naming the step."
  [step f]
  (try [(f) nil]
       (catch Throwable e
         [nil {:kind :error :step step :message (ex-message e) :class (.getName (class e))
               :data (let [d (pr-str (ex-data e))] (subs d 0 (min 1200 (count d))))}])))

(defn catalog-findings
  "The facts a generated file could not render, as findings: :unparsed, :unknown-type, :unrendered."
  [data]
  (for [f (:unrendered data)]
    (case (:fact f)
      :unparsed {:kind :unparsed :table (:table f) :type-name (:type-name f) :column (:column f) :constraint (:constraint f) :input (:input f) :error (:error f)}
      :unknown-type {:kind :unknown-type :table (:table f) :column (:column f) :type (:type f)}
      {:kind :unrendered :table (:table f) :type-name (:type-name f) :column (:column f) :constraint (:constraint f) :fact (:fact f) :expr (:expr f)})))

(defn row-names
  "The names of a generated file's row schemas (tables, not views)."
  [data]
  (filter #(shape/row-schema? (get (:registry data) %)) (sort-by str (keys (:registry data)))))

(defn short-findings
  "The tables a generated dataset came out short of, as findings."
  [dataset]
  (for [[t {:keys [wanted got reasons]}] (some-> dataset meta :pgmalli/short)]
    {:kind :short :table t :wanted wanted :got got :reasons reasons}))
