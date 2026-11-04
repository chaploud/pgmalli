(ns pgmalli-next.pipeline
  "The steps bb sweep and pgmalli-next.catalog-fuzz-test take a schema through, and the findings
   they share: what did not go through, one map each with its :kind.")

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
