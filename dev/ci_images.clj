(ns ci-images
  "The PostgreSQL images CI tests against, by digest, as .github/workflows/ci.yml's matrix lists
   them (Renovate updates them there): what bb catalog, bb test:matrix and the release run on."
  (:require [clj-yaml.core :as yaml]))

(defn images
  "{major image} of ci.yml's test matrix."
  []
  (into (sorted-map) (for [{:keys [pg image]} (get-in (yaml/parse-string (slurp ".github/workflows/ci.yml")) [:jobs :test :strategy :matrix :include])]
                       [pg image])))

(defn -main
  "bb -cp dev -m ci-images <major>: that major's image."
  [major]
  (println (or (get (images) major) (throw (ex-info (str "ci.yml tests no PostgreSQL " major) {:major major})))))
