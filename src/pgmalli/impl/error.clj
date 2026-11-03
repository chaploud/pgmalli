(ns pgmalli.impl.error
  "The errors pgmalli throws. ex-data carries :pgmalli/error, a kind a program dispatches on,
   its :cognitect.anomalies/category, and where they apply :given (the input at fault),
   :known (what was expected) and :did-you-mean (a vector of the ones probably meant). Messages say what is
   wrong, with which input, and what to do, in one line. Neither carries a db map, a URL or a
   password.")

(def categories
  {:pgmalli.error/invalid-config :cognitect.anomalies/incorrect
   :pgmalli.error/invalid-options :cognitect.anomalies/incorrect
   :pgmalli.error/unknown-schema :cognitect.anomalies/not-found
   :pgmalli.error/not-a-registry :cognitect.anomalies/incorrect
   :pgmalli.error/not-query-data :cognitect.anomalies/incorrect
   :pgmalli.error/invalid-dataset :cognitect.anomalies/incorrect
   :pgmalli.error/reference-cycle :cognitect.anomalies/unsupported
   :pgmalli.error/not-generated :cognitect.anomalies/not-found
   :pgmalli.error/cannot-generate :cognitect.anomalies/unsupported
   :pgmalli.error/db-unavailable :cognitect.anomalies/unavailable
   :pgmalli.error/psql-missing :cognitect.anomalies/unavailable
   :pgmalli.error/fault :cognitect.anomalies/fault})

(def changelog "https://github.com/chaploud/pgmalli/blob/main/CHANGELOG.md")

(defn fail!
  ([kind message] (fail! kind message {} nil))
  ([kind message data] (fail! kind message data nil))
  ([kind message data cause]
   (throw (ex-info message (assoc data :pgmalli/error kind :cognitect.anomalies/category (categories kind :cognitect.anomalies/fault))
                   cause))))

(defn edit-distance
  "The edits from a to b: insertions, deletions, substitutions and swaps of adjacent letters
   (nmae is one edit from name)."
  [^String a ^String b]
  (let [n (count a) m (count b) w (inc m)
        d (long-array (* (inc n) w))
        at (fn [i j] (aget d (+ (* i w) j)))]
    (dotimes [i (inc n)] (aset d (* i w) (long i)))
    (dotimes [j w] (aset d j (long j)))
    (doseq [i (range 1 (inc n)), j (range 1 w)]
      (let [cost (if (= (.charAt a (dec i)) (.charAt b (dec j))) 0 1)
            v (min (inc (at (dec i) j)) (inc (at i (dec j))) (+ (at (dec i) (dec j)) cost))
            v (if (and (> i 1) (> j 1) (= (.charAt a (dec i)) (.charAt b (- j 2))) (= (.charAt a (- i 2)) (.charAt b (dec j))))
                (min v (inc (at (- i 2) (- j 2))))
                v)]
        (aset d (+ (* i w) j) (long v))))
    (at n m)))

(defn- close-enough
  "How many edits a name may be from the one meant: 2 for five letters or more, 1 below."
  [s]
  (if (<= 5 (count s)) 2 1))

(defn suggestions
  "Up to n of the known names closest to a misspelt one, closest first; nil when none is close."
  [given known n]
  (let [g (str given)]
    (->> known
         (keep #(let [d (edit-distance g (str %))] (when (<= d (close-enough g)) [% d])))
         (sort-by second) (take n) (map first) seq)))

(defn suggest
  "The known name closest to a misspelt one, nil when none is close."
  [given known]
  (first (suggestions given known 1)))

(defn did-you-mean
  "\"; did you mean x?\" for the name x printed as it should appear, \"\" for none."
  [x]
  (if x (str "; did you mean " x "?") ""))
