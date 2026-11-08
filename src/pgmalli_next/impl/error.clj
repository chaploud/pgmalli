(ns pgmalli-next.impl.error
  "The errors pgmalli throws. ex-data carries :pgmalli/error, a kind a program dispatches on,
   its :cognitect.anomalies/category, and where they apply :given (the input at fault),
   :known (what was expected) and :did-you-mean (a vector of the ones probably meant). Messages say what is
   wrong, with which input, and what to do, in one line. Neither carries a db map, a URL or a
   password."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.error :as me]))

(def categories
  {:pgmalli.error/invalid-config :cognitect.anomalies/incorrect
   :pgmalli.error/invalid-options :cognitect.anomalies/incorrect
   :pgmalli.error/unknown-schema :cognitect.anomalies/not-found
   :pgmalli.error/not-a-registry :cognitect.anomalies/incorrect
   :pgmalli.error/not-query-data :cognitect.anomalies/incorrect
   :pgmalli.error/not-generated :cognitect.anomalies/not-found
   :pgmalli.error/cannot-generate :cognitect.anomalies/unsupported
   :pgmalli.error/db-unavailable :cognitect.anomalies/unavailable
   :pgmalli.error/psql-missing :cognitect.anomalies/unavailable
   :pgmalli.error/stale :cognitect.anomalies/conflict
   :pgmalli.error/other-sending :cognitect.anomalies/conflict
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

(defn- close-enough [s]
  (if (<= 5 (count s)) 2 1))

(defn suggestions
  "Up to n of the known names closest to a misspelt one, closest first; nil when none is close."
  [given known n]
  (let [g (str given)
        most (close-enough g)]
    (->> known
         ;; the edits are at least the difference in length
         (keep #(let [k (str %)]
                  (when (<= (abs (- (count g) (count k))) most)
                    (let [d (edit-distance g k)] (when (<= d most) [% d])))))
         (sort-by second) (take n) (map first) seq)))

(defn suggest
  "The known name closest to a misspelt one, nil when none is close."
  [given known]
  (first (suggestions given known 1)))

(defn named
  "A name with its article, as a sentence names a thing of it: \"a java.time.Instant\", \"an Integer\"."
  [n]
  (str (if (#{\a \e \i \o \u \A \E \I \O \U} (first n)) "an " "a ") n))

(defn did-you-mean
  "\"; did you mean: x\" for the name x printed as it should appear, \"\" for none. No ? follows:
   a name may end in one (:nullable?)."
  [x]
  (if x (str "; did you mean: " x) ""))

(defn checked!
  "opts (nil is none) when schema takes them; an error naming what it does not, a misspelt key
   with the one meant, otherwise. kind and label say whose they are (options by default).
   A config's :db, which may hold a password, is left out of the error, and so are the values of
   keys schema does not take (a misspelt :db is one)."
  ([schema opts] (checked! schema opts :pgmalli.error/invalid-options "pgmalli options"))
  ([schema opts kind label]
   (if (m/validate schema (or opts {}))
     opts
     (let [errors (if-let [shape (when-not (or (nil? opts) (map? opts)) (:pgmalli/not-a-map (m/properties schema)))]
                    [shape]
                    (walk/postwalk #(if (and (vector? %) (every? string? %)) (vec (distinct %)) %)
                                           (me/humanize (m/explain schema opts))))
           known (when (= :map (m/type schema)) (map first (m/children (m/schema schema))))
           stray (when (map? opts) (remove (set known) (keys opts)))
           suggest (some #(suggest % known) stray)]
       (fail! kind (str label ": " (pr-str errors)
                              (cond suggest (did-you-mean suggest)
                                    (and (seq stray) (seq known)) (str "; it takes " (str/join ", " known))))
                    (cond-> {:given (cond-> opts
                                      (and (map? opts) known) (select-keys known)
                                      (map? opts) (dissoc :db))
                             :errors errors}
                      known (assoc :known (vec known))
                      ;; the names of the keys it does not take, not their values
                      (seq stray) (assoc :unknown (vec stray))
                      suggest (assoc :did-you-mean [suggest])))))))
