(ns pgmalli.impl.error
  "The errors pgmalli throws. ex-data carries :pgmalli/error, a kind a program dispatches on,
   its :cognitect.anomalies/category, and where they apply :given (the input at fault),
   :known (what was expected) and :suggest (the one probably meant). Messages say what is
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
  (let [n (count a) m (count b)
        d (reduce (fn [d [i j]]
                    (let [cost (if (= (.charAt a (dec i)) (.charAt b (dec j))) 0 1)
                          v (min (inc (d [(dec i) j])) (inc (d [i (dec j)])) (+ (d [(dec i) (dec j)]) cost))
                          v (if (and (> i 1) (> j 1) (= (.charAt a (dec i)) (.charAt b (- j 2))) (= (.charAt a (- i 2)) (.charAt b (dec j))))
                              (min v (inc (d [(- i 2) (- j 2)])))
                              v)]
                      (assoc d [i j] v)))
                  (into {} (concat (for [i (range (inc n))] [[i 0] i]) (for [j (range (inc m))] [[0 j] j])))
                  (for [i (range 1 (inc n)), j (range 1 (inc m))] [i j]))]
    (d [n m])))

(defn close-enough
  "How many edits a name may be from the one meant: 2 for five letters or more, 1 below."
  [s]
  (if (<= 5 (count s)) 2 1))

(defn suggest
  "The known name closest to a misspelt one, nil when none is close."
  [given known]
  (let [g (str given)]
    (->> known
         (keep #(let [d (edit-distance g (str %))] (when (<= d (close-enough g)) [% d])))
         (sort-by second) ffirst)))
