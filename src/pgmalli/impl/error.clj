(ns pgmalli.impl.error
  "The errors pgmalli throws. ex-data carries :pgmalli/error, a kind a program dispatches on,
   its :cognitect.anomalies/category, and where they apply :given (the input at fault),
   :known (what was expected) and :suggest (the one probably meant). Messages say what is
   wrong, with which input, and what to do, in one line. Neither carries a db map, a URL or a
   password."
  (:require [malli.error :as me]))

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
   :pgmalli.error/fault :cognitect.anomalies/fault})

(defn fail!
  ([kind message] (fail! kind message {} nil))
  ([kind message data] (fail! kind message data nil))
  ([kind message data cause]
   (throw (ex-info message (assoc data :pgmalli/error kind :cognitect.anomalies/category (categories kind :cognitect.anomalies/fault))
                   cause))))

(defn suggest
  "The known name closest to a misspelt one, nil when none is close."
  [given known]
  (first (me/-most-similar-to #{} given (set known))))
