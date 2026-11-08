(ns pgmalli-next.impl.memo
  "Caches keyed on what queries hold (names, clauses, values), which an application may build from
   its input: bounded however many queries it analyzes.")

(def ^:private limit 10000)

(defn bounded
  "memoize of f holding at most limit results; past it, it forgets them all."
  [f]
  (let [cache (atom {})]
    (fn [& args]
      (let [c @cache]
        (if (contains? c args)
          (get c args)
          (let [v (apply f args)]
            (swap! cache #(assoc (if (<= limit (count %)) {} %) args v))
            v))))))
