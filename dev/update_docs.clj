(ns update-docs
  "bb test:docs:update [file.md ...]: run the Clojure blocks of the docs bb test:docs runs and
   write each ;; => line back with the value its form gives. Read the git diff before
   committing: a rewritten line is a changed behaviour."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- docs []
  (-> (edn/read-string (slurp "deps.edn")) :aliases :test-doc-blocks :exec-args :docs))

(defn- update-block
  "The lines of one block with each ;; => after a form replaced by what the form gives, run in
   the current namespace; [lines changed left], left being [{:line :reason}] with 1-based lines
   of the block."
  [lines]
  (let [r (clojure.lang.LineNumberingPushbackReader. (java.io.StringReader. (str/join "\n" lines)))
        out (transient (into [] lines))
        changed (volatile! 0)
        left (volatile! [])
        leave! (fn [line reason] (vswap! left conj {:line line :reason reason}))]
    (loop []
      (let [form (try (read {:eof ::eof :read-cond :allow} r)
                      (catch Exception e (leave! (.getLineNumber r) (str "does not read, the rest of the block is left: " (ex-message e))) ::eof))]
        (when-not (= ::eof form)
          (let [v (try (eval form) (catch Throwable e (leave! (or (:line (meta form)) (.getLineNumber r)) (str "throws: " (ex-message e))) ::threw))
                ;; the line after the form's last one (getLineNumber is 1-based, so it is the next index)
                i (.getLineNumber r)
                i (if (str/starts-with? (str/trim (get lines (dec i) "")) ";; =>") (dec i) i)
                ;; ;; lines right after a ;; => continue it
                run (take-while #(and (str/starts-with? % ";;") (not (str/starts-with? % ";; =>"))) (drop (inc i) lines))]
            (when (and (not= ::threw v) (str/starts-with? (get lines i "") ";; =>"))
              (let [written (str/join " " (cons (subs (get lines i) 5) (map #(subs % 2) run)))]
                ;; a line that reads as the value stays as written (a map's order, #:pg{} or not)
                (when (not= v (try (read-string written) (catch Exception _ ::unreadable)))
                  (if (seq run)
                    (leave! (inc i) (str "the ;; => continues over " (inc (count run)) " lines; it gives " (pr-str v)))
                    (do (vswap! changed inc)
                        (assoc! out i (str ";; => " (binding [*print-namespace-maps* true] (pr-str v)))))))))
            (recur)))))
    [(persistent! out) @changed @left]))

(defn- update-file
  "Rewrites file; the number of ;; => it left."
  [file]
  (let [lines (str/split-lines (slurp file))
        n (create-ns (gensym "docs"))]
    (binding [*ns* n]
      (refer-clojure)
      (loop [i 0 acc [] changed 0 left 0 skip? false]
        (if (>= i (count lines))
          (do (when (pos? changed) (spit file (str (str/join "\n" acc) "\n")))
              (println (str file ": rewrote " changed ", left " left))
              left)
          (let [line (get lines i)]
            (cond (str/includes? line ":test-doc-blocks/skip") (recur (inc i) (conj acc line) changed left true)
                  (= "```clojure" (str/trim line))
                  (let [end (+ i 1 (count (take-while #(not= "```" (str/trim %)) (subvec lines (inc i)))))
                        block (subvec lines (inc i) end)
                        [block' c l] (if skip? [block 0 []] (update-block block))]
                    (doseq [{:keys [line reason]} l]
                      (println (str file ":" (+ i 1 line) " left: " reason)))
                    (recur (inc end) (-> acc (conj line) (into block') (conj (get lines end))) (+ changed c) (+ left (count l)) false))
                  :else (recur (inc i) (conj acc line) changed left (and skip? (str/blank? line))))))))))

(defn -main [& files]
  (let [left (reduce + (map update-file (or (seq files) (docs))))]
    (println "Next: read git diff, then run bb test:docs")
    (shutdown-agents)
    (System/exit (if (pos? left) 1 0))))
