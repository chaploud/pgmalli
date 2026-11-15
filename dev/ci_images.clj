(ns ci-images
  "The PostgreSQL images CI tests against, by digest, as .github/workflows/ci.yml lists them
   (Renovate updates them there, matching renovate.json's matchStrings): what the tests, bb
   catalog, bb test:matrix and the release run on.")

(defn images
  "{major image} of ci.yml."
  []
  (into (sorted-map) (for [[image major] (re-seq #"postgres:(\d+)-alpine@sha256:[0-9a-f]+" (slurp ".github/workflows/ci.yml"))]
                       [major image])))

(defn -main
  "bb -cp dev -m ci-images <major>: that major's image."
  [major]
  (println (or (get (images) major) (throw (ex-info (str "ci.yml tests no PostgreSQL " major) {:major major})))))
