(ns pgmalli-next.impl.memo
  "Caches keyed on what queries hold (names, clauses, values), which an application may build from
   its input: bounded however many queries it analyzes.")

(def ^:private limit 10000)

(defn- long-name?
  "A name longer than any PostgreSQL holds (63 bytes, a schema's and a table's together): held,
   10000 of a request's size would fill the heap."
  [x]
  (cond (string? x) (< 256 (count ^String x))
        (ident? x) (< 256 (+ (count (name x)) (count (namespace x))))
        :else false))

(defn bounded
  "memoize of f holding at most limit results, none of an argument long-name?; past the limit, it
   forgets them all."
  [f]
  (let [cache (atom {})]
    (fn [& args]
      (if-let [[_ v] (find @cache args)]
        v
        (let [v (apply f args)]
          (when-not (some long-name? args)
            (swap! cache #(assoc (if (<= limit (count %)) {} %) args v)))
          v)))))
