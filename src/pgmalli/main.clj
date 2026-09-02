(ns pgmalli.main
  "Command line entry point. Config comes from pgmalli.edn in the working directory or
   the path given as second argument.

     clojure -M -m pgmalli.main generate [pgmalli.edn]
     clojure -M -m pgmalli.main check    [pgmalli.edn]   ; exit 1 when files are stale
     bb -m pgmalli.main ..."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [pgmalli.core :as pgmalli]
            [pgmalli.impl.generate :as gen]))

(defn- read-config [path]
  (let [path (or path "pgmalli.edn")]
    (try (edn/read-string (slurp path))
         (catch java.io.FileNotFoundException _
           (println "no config file at" path "- using defaults (public -> resources/pgmalli)")
           {}))))

(defn -main [& [command path]]
  (let [config (read-config path)]
    (case (or command "generate")
      "generate" (doseq [[schema out] (pgmalli/generate! config)]
                   (println "wrote" out (str "(" schema ")")))
      "check" (let [stale (pgmalli/stale config)]
                (doseq [schema (:schemas (gen/config config))
                        :let [p (pgmalli/path config schema)
                              un (when (.exists (java.io.File. ^String p)) (pgmalli/unrendered p))]
                        :when (seq un)]
                  (println schema ": " (count un) "unrendered fact(s):")
                  (doseq [f un] (println "  " (:table f) (:constraint f (:column f)) (:fact f))))
                (if stale
                  (do (println "generated files are out of date; run generate and commit:")
                      (binding [*print-namespace-maps* false] (pp/pprint stale))
                      (System/exit 1))
                  (println "generated files match the database")))
      (do (println "usage: pgmalli.main (generate|check) [pgmalli.edn]") (System/exit 2)))))
