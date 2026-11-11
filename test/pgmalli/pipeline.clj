(ns pgmalli.pipeline
  "The steps bb sweep and pgmalli.catalog-fuzz-test take a schema through, and the findings
   they share: what did not go through, one map each with its :kind.")

(defn try-step
  "[value finding]: the step's value, or a finding of kind :error naming the step."
  [step f]
  (try [(f) nil]
       (catch Throwable e
         [nil {:kind :error :step step :message (ex-message e) :class (.getName (class e))
               :data (let [d (pr-str (ex-data e))] (subs d 0 (min 1200 (count d))))}])))

(defn catalog-findings
  "The columns a generated file left out (:unrendered), as findings of kind :unknown-type."
  [data]
  (for [f (:unrendered data)]
    (assoc (select-keys f [:table :column :type :type-schema]) :kind :unknown-type)))
