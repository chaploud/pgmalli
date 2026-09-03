;; bb -cp dev -e '(load-file "dev/sweep_report.clj")' -- <out-dir>: what a sweep found, grouped.
(require '[clojure.edn :as edn] '[babashka.fs :as fs] '[clojure.string :as str])
(defn- cut [s n] (let [s (str s)] (subs s 0 (min n (count s)))))
(let [[dir] *command-line-args*
      fs (map #(edn/read-string (slurp (str %))) (fs/glob dir "*.edn"))
      all (for [{:keys [file findings]} fs, x findings] (assoc x :file file))]
  (println "files" (count fs) "findings" (frequencies (map :kind all)))
  (println "\n## unknown types") (println (sort-by (comp - val) (frequencies (map :type (filter #(= :unknown-type (:kind %)) all)))))
  (println "\n## errors") (doseq [x (filter #(= :error (:kind %)) all)] (println " " (:file x) (:step x) (cut (:message x) 160) "|" (cut (:data x) 200)))
  (println "\n## unparsed") (doseq [x (filter #(= :unparsed (:kind %)) all)] (println " " (:file x) (cut (:input x) 160) "->" (:error x)))
  (println "\n## unrendered") (doseq [x (filter #(= :unrendered (:kind %)) all)] (println " " (:file x) (:fact x) (:constraint x) (cut (pr-str (:expr x)) 200)))
  (println "\n## short") (doseq [x (filter #(= :short (:kind %)) all)] (println " " (:file x) (:table x) (:got x) "/" (:wanted x) (cut (pr-str (:reasons x)) 200)))
  (println "\n## rejected, by first line of the error")
  (doseq [[msg xs] (sort-by (comp - count val) (group-by #(cut (first (str/split-lines (str (:message %)))) 70) (filter #(= :rejected (:kind %)) all)))]
    (println " " (count xs) msg "  e.g." (:file (first xs)) (:table (first xs)))))
