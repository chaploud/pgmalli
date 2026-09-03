(ns pgmalli.main
  "Command line entry point. Config comes from pgmalli.edn in the working directory or
   the path given as second argument.

     clojure -M -m pgmalli.main generate [pgmalli.edn]
     clojure -M -m pgmalli.main check    [pgmalli.edn]   ; exit 1 when files are stale
     bb -m pgmalli.main ..."
  (:require [clojure.edn :as edn]
            [pgmalli.generate :as generate]
            [pgmalli.impl.generate :as gen]
            [pgmalli.impl.shape :as shape]))

(defn- read-config [path]
  (let [path (or path "pgmalli.edn")]
    (try (edn/read-string (slurp path))
         (catch java.io.FileNotFoundException _
           (println "no config file at" path "- using defaults (public -> resources/pgmalli)")
           {}))))

(defn- counts [data]
  (let [entries (vals (:registry data))
        kind (fn [s] (let [m (when (vector? s) (shape/row-map s))
                           p (when (and (vector? m) (map? (second m))) (second m))]
                       (cond (:pg/view p) :view (:pg/table p) :table (and (vector? s) (= :enum (first s))) :enum :else :domain)))
        f (frequencies (map kind entries))]
    (str (f :table 0) " tables, " (f :view 0) " views, " (f :enum 0) " enums, " (f :domain 0) " domains; "
         (count (:unrendered data)) " unrendered, " (count (:diagnostics data)) " diagnostics")))

(defn -main [& [command path]]
  (let [config (read-config path)]
    (case (or command "generate")
      "generate" (doseq [[schema out] (generate/generate! config)
                         :let [data (gen/load-file* out)]]
                   (println "wrote" out (str "(" schema ": " (counts data) ")"))
                   (doseq [d (:diagnostics data)] (println "  " (name (:severity d)) (:table d) (:message d))))
      "check" (let [{:keys [stale unrendered diagnostics]} (generate/check config)]
                (when stale
                  (println "generated files are out of date; run generate and commit:")
                  (doseq [[schema ds] stale, {:keys [name column property checks order key file db]} ds]
                    (println " " schema (or key name) (str column (when (and column property) " ") property)
                             (cond checks "checks" order "column order" :else "")
                             "file" (pr-str file) "db" (pr-str db))))
                (doseq [[schema un] unrendered]
                  (println schema ": " (count un) "unrendered fact(s):")
                  (doseq [f un] (println "  " (:table f) (:constraint f (:column f)) (:fact f))))
                (doseq [[schema ds] diagnostics]
                  (println schema ": " (count ds) "diagnostic(s):")
                  (doseq [d ds] (println "  " (name (:severity d)) (:table d) (:message d))))
                (if stale (System/exit 1) (println "generated files match the database")))
      (do (println "usage: pgmalli.main (generate|check) [pgmalli.edn]") (System/exit 2)))))
