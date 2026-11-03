(ns update-docs
  "bb test:docs:update [file.md ...]: run the Clojure blocks of README.md and docs/*.md as
   bb test:docs does and write each ;; => line back with the value its form gives. Read the git
   diff before committing: a rewritten line is a changed behaviour."
  (:require [clojure.string :as str]))

(def ^:private docs ["README.md" "docs/reading-rows.md" "docs/queries.md" "docs/datasets.md" "docs/types.md"])

(defn- update-block
  "The lines of one block with each ;; => after a form replaced by what the form gives, run in
   the current namespace; [lines changed]."
  [lines]
  (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (str/join "\n" lines)))
        out (transient (into [] lines))
        changed (volatile! 0)]
    (loop []
      (let [form (read {:eof ::eof :read-cond :allow} r)]
        (when-not (= ::eof form)
          (let [v (try (eval form) (catch Throwable e (println "  throws:" (pr-str form) (ex-message e)) ::threw))
                ;; the line after the form's last one (getLineNumber is 1-based, so it is the next index)
                i (.getLineNumber r)
                i (if (str/starts-with? (str/trim (get lines (dec i) "")) ";; =>") (dec i) i)]
            (when (and (not= ::threw v) (str/starts-with? (get lines i "") ";; =>"))
              (let [line (str ";; => " (binding [*print-namespace-maps* true] (pr-str v)))]
                ;; a line that reads as the value stays as written (a map's order, #:pg{} or not)
                (when (not= v (try (read-string (subs (get lines i) 5)) (catch Exception _ ::unreadable)))
                  (vswap! changed inc)
                  (assoc! out i line))))
            (recur)))))
    [(persistent! out) @changed]))

(defn- update-file [file]
  (let [lines (str/split-lines (slurp file))
        n (create-ns (gensym "docs"))]
    (binding [*ns* n]
      (refer-clojure)
      (loop [i 0 acc [] changed 0 skip? false]
        (if (>= i (count lines))
          (do (spit file (str (str/join "\n" acc) "\n"))
              (println (str file ": rewrote " changed)))
          (let [line (get lines i)]
            (cond (str/includes? line ":test-doc-blocks/skip") (recur (inc i) (conj acc line) changed true)
                  (= "```clojure" (str/trim line))
                  (let [end (+ i 1 (count (take-while #(not= "```" (str/trim %)) (subvec lines (inc i)))))
                        block (subvec lines (inc i) end)
                        [block' c] (if skip? [block 0] (update-block block))]
                    (recur (inc end) (-> acc (conj line) (into block') (conj (get lines end))) (+ changed c) false))
                  :else (recur (inc i) (conj acc line) changed (and skip? (str/blank? line))))))))))

(defn -main [& files]
  (doseq [f (or (seq files) docs)] (update-file f))
  (shutdown-agents))
