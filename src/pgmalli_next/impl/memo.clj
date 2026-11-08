(ns pgmalli-next.impl.memo
  "Caches keyed on what queries hold (names, clauses, values), which an application may build from
   its input: bounded however many queries it analyzes.")

(def ^:private limit 10000)

(defn- long-name?
  "A name longer than any PostgreSQL holds (63 bytes, a schema's and a table's together): held,
   10000 of a request's size would fill the heap."
  [x]
  (and (or (string? x) (ident? x)) (< 256 (count (str x)))))

(defn bounded
  "memoize of f holding at most limit results, none of an argument long-name?; past the limit, it
   forgets them all."
  [f]
  (let [cache (atom {})]
    (fn [& args]
      (let [c @cache]
        (cond
          (some long-name? args) (apply f args)
          (contains? c args)
          (get c args)
          :else
          (let [v (apply f args)]
            (swap! cache #(assoc (if (<= limit (count %)) {} %) args v))
            v))))))
