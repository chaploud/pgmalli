;; bb upstream: the JVM suite against the newest stable HoneySQL, next.jdbc, pgjdbc and malli and
;; the newest PostgreSQL image, with what to update grouped by upstream.
(ns upstream
  (:require [babashka.fs :as fs] [babashka.process :as p] [clojure.edn :as edn] [clojure.string :as str]))

(def libs '[com.github.seancorfield/honeysql com.github.seancorfield/next.jdbc org.postgresql/postgresql metosin/malli])

(defn latest [lib]
  (let [out (:out (p/sh "clojure" "-X:deps" "find-versions" ":lib" (str lib)))]
    (->> (str/split-lines out) (keep #(:mvn/version (edn/read-string %)))
         (filter #(re-matches #"\d+(\.\d+)*" %)) last)))

(let [versions (into {} (for [l libs] [l {:mvn/version (or (latest l) (throw (ex-info "no stable version" {:lib l})))}]))
      image (or (System/getenv "PGMALLI_PG_IMAGE") "postgres:alpine")
      out (str (fs/create-temp-file {:prefix "pgmalli-upstream-" :suffix ".txt"}))
      _ (println "running the JVM suite on" image "and" (pr-str versions) "\n output:" out)
      {:keys [exit]} (p/shell {:continue true :out out :err out :extra-env {"PGMALLI_PG_IMAGE" image}}
                              "clojure" "-Sdeps" (pr-str {:aliases {:upstream {:override-deps versions}}}) "-M:test:upstream")
      ;; a clojure.test report: "FAIL in (test) (file:line)", the testing contexts, the message, expected, actual
      reports (rest (str/split (slurp out) #"(?m)^(?=(FAIL|ERROR) in )"))
      todo (fn [r] (some #(re-matches #"(HoneySQL|PostgreSQL|next\.jdbc|pgjdbc|malli) \d\S* .*" %) (str/split-lines r)))
      {classified true unclassified false} (group-by (comp boolean todo) reports)]
  ;; one cause fails many tests: a few lines of each, and how many more
  (doseq [[upstream items] (sort (group-by (comp second todo) classified))
          :let [ms (sort (distinct (map (comp first todo) items)))]]
    (println (str "\n## " upstream))
    (doseq [m (take 20 ms)] (println "-" m))
    (when (next (drop 19 ms)) (println "- and" (- (count ms) 20) "more")))
  (when (seq unclassified)
    (println "\n## unclassified: failures that name no upstream (give their message one)")
    (doseq [[test rs] (sort-by key (group-by #(re-find #"^\S+ in \(\S+\)" %) unclassified))]
      (println "-" test (count rs) "failures, the first:" (str/join " | " (take 3 (drop 1 (remove str/blank? (str/split-lines (first rs)))))))))
  (println (if (zero? exit) "\nall green" (str "\nthe suite failed; the whole output is in " out)))
  (System/exit exit))
