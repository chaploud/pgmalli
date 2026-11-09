(ns pgmalli-next.impl.memo
  "pgmalli's caches, each of a lifetime: one call of a public function (kept, *calls*), dropped
   with it; and the process (bounded), keyed on what queries hold (names, clauses, values), which
   an application may build from its input, bounded however many queries it analyzes. What is
   kept for the schemas of a registry is query/reading's.")

(def ^:dynamic *calls*
  "The atom of what one call of a pgmalli-next.honeysql public function keeps (analyze binds it),
   for code that takes no registry; nil outside one."
  nil)

(defn kept
  "(thunk), kept in atom m under k; (thunk) itself where m is nil. With reentered, k holds it
   while thunk runs: a call of k from within it (a CTE reading itself) gets reentered."
  ([m k thunk]
   (if m
     (if-let [[_ v] (find @m k)] v (let [v (thunk)] (swap! m assoc k v) v))
     (thunk)))
  ([m k reentered thunk]
   (if m
     (if-let [[_ v] (find @m k)] v (do (swap! m assoc k reentered) (let [v (thunk)] (swap! m assoc k v) v)))
     (thunk))))

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
   forgets them all. Keyed by a vector of the arguments, of each arity f is called with."
  [f]
  (let [cache (atom {})
        look (fn [k thunk]
               (if-let [[_ v] (find @cache k)]
                 v
                 (let [v (thunk)]
                   (when-not (some long-name? k)
                     (swap! cache #(assoc (if (<= limit (count %)) {} %) k v)))
                   v)))]
    (fn
      ([a] (look [a] #(f a)))
      ([a b] (look [a b] #(f a b)))
      ([a b c] (look [a b c] #(f a b c)))
      ([a b c d] (look [a b c d] #(f a b c d)))
      ([a b c d & more] (let [k (into [a b c d] more)] (look k #(apply f k)))))))
