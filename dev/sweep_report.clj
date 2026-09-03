;; bb sweep:report <out-dir>: what a sweep found, grouped.
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
  (println "\n## rejected, by cause")
  (let [cause (fn [m] (cond (re-find #"trigger|slotname|Room|called by|RI error|FOR loop|manual manipulation|does not exist|no handler|infinite recursion|NOTICE" m) :environment
                            (re-find #"no partition of relation" m) :partition
                            (re-find #"foreign key" m) :foreign-key
                            (re-find #"check constraint" m) :check
                            (re-find #"unique constraint" m) :unique
                            :else :other))]
    (doseq [[c xs] (sort-by (comp - count val) (group-by #(cause (str (:message %))) (filter #(= :rejected (:kind %)) all)))]
      (println (str "\n### " (name c) " (" (count xs) ")"))
      (doseq [[msg ys] (sort-by (comp - count val) (group-by #(cut (first (str/split-lines (str (:message %)))) 90) xs))]
        (println " " (count ys) msg "  e.g." (:file (first ys)) (:table (first ys)))))))
