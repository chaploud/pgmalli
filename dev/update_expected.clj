(ns update-expected
  "bb test:update [ns ...] (bb test:update:jvm on the JVM): run the suite and write each failing
   (is (= expected actual)) back with the value it got, where the expected side is a literal.
   Read the git diff before committing: a rewritten expectation is a changed behaviour."
  (:require [bb-suite]
            [clojure.string :as str]
            [clojure.test :as t]
            [rewrite-clj.zip :as z]))

(defn- quoted-symbol? [form]
  (and (seq? form) (= 'quote (first form)) (= 2 (count form)) (symbol? (second form))))

(defn- literal? [form]
  (cond (quoted-symbol? form) true
        (or (symbol? form) (seq? form)) false
        (map? form) (every? literal? (mapcat identity form))
        (coll? form) (every? literal? form)
        :else true))

(defn- flat [v]
  (cond (symbol? v) (str "'" v)
        (map? v) (str "{" (str/join " " (map (fn [[k x]] (str (flat k) " " (flat x))) v)) "}")
        (set? v) (str "#{" (str/join " " (map flat v)) "}")
        ;; a seq printed as a list would read back as a call
        (sequential? v) (str "[" (str/join " " (map flat v)) "]")
        :else (binding [*print-namespace-maps* false] (pr-str v))))

(defn- print-literal
  "v as source that reads back as an equal value, starting at column col; a map, or a collection
   of collections, that would pass column 100 has one element per line."
  [v col]
  (let [s (flat v)]
    (if (or (<= (+ col (count s)) 100) (not (or (map? v) (some coll? v))))
      s
      (let [open (cond (map? v) "{" (set? v) "#{" :else "[")
            c (+ col (count open))
            pad (str "\n" (apply str (repeat (dec c) \space)))]
        (str open
             (str/join pad (if (map? v)
                             (map (fn [[k x]] (let [ks (print-literal k c)] (str ks " " (print-literal x (+ c (count ks) 1))))) v)
                             (map #(print-literal % c) v)))
             (if (or (map? v) (set? v)) "}" "]"))))))

(defn- readable? [v]
  (not-any? #(or (fn? %) (and (not (coll? %)) (str/starts-with? (pr-str %) "#object"))) (tree-seq coll? seq v)))

(defn- test-name
  "The name of the top-level (deftest name ...) zloc is in."
  [zloc]
  (let [top (loop [z zloc] (let [u (z/up z)] (if (and u (z/up u)) (recur u) z)))
        head (try (some-> top z/down z/sexpr) (catch Exception _ nil))]
    (when (and (symbol? head) (= "deftest" (name head)))
      (try (some-> top z/down z/right z/sexpr) (catch Exception _ nil)))))

(defn rewrite
  "src with the literal side of each failing (= a b) replaced by the value the other side gave;
   [src' rewrote left]. A failure is {:test name, :expected (= a b), :actual (not (= va vb)),
   :line n}; it is written only where its form occurs inside its deftest, once (or once on its
   line), and with one value. left is [{:line :expected :reason}]."
  [src failures]
  (let [forms (set (map :expected failures))
        found (loop [zloc (z/of-string src {:track-position? true}) acc {}]
                (if (z/end? zloc)
                  acc
                  (let [f (when (= :list (z/tag zloc)) (try (z/sexpr zloc) (catch Exception _ nil)))]
                    (recur (z/next zloc)
                           (if (contains? forms f)
                             (update acc [(test-name zloc) f] (fnil conj []) (z/position zloc))
                             acc)))))
        decided (for [[[test f] fs] (group-by (juxt :test :expected) failures)
                      :let [occ (get found [test f])]
                      [pos fs] (group-by (fn [{:keys [line]}]
                                           (if (= 1 (count occ))
                                             (first occ)
                                             (let [on-line (filter #(= line (first %)) occ)]
                                               (when (= 1 (count on-line)) (first on-line)))))
                                         fs)
                      :let [eq? (and (seq? f) (= '= (first f)) (= 3 (count f)))
                            [_ a b] (when eq? f)
                            [_ [_ va vb]] (when eq? (:actual (first fs)))
                            [i v] (when eq? (cond (literal? a) [1 vb] (literal? b) [2 va]))
                            reason (cond (not eq?) "not (= expected actual)"
                                         (empty? occ) (str "not found inside (deftest " test ") (in a helper or a macro?)")
                                         (nil? pos) (str "ambiguous: it occurs " (count occ) " times in " test)
                                         (not (apply = (map :actual fs))) "several values (an is in a loop?)"
                                         (nil? i) "neither side is a literal"
                                         (not (readable? v)) (str "the value does not read back: " (pr-str v)))]]
                  (if reason
                    {:left (for [x fs] {:line (or (:line x) (first pos)) :expected f :reason reason})}
                    {:edit [pos i v]}))
        root (reduce (fn [node [pos i v]]
                       (let [side (nth (iterate z/right (z/down (z/find-last-by-pos (z/of-node node {:track-position? true}) pos))) i)]
                         (z/root (z/replace side (z/node (z/of-string (print-literal v (second (z/position side)))))))))
                     (z/root (z/of-string src))
                     (sort-by first #(compare %2 %1) (keep :edit decided)))]
    [(str root) (count (keep :edit decided)) (sort-by :line (distinct (mapcat :left decided)))]))

(defn -main [& args]
  (let [bb? (System/getProperty "babashka.version")
        nss (if (seq args) (map symbol args) (bb-suite/namespaces (not bb?)))
        jvm (when bb? (filter bb-suite/jvm-only nss))
        failures (atom [])
        report t/report]
    (when (seq jvm)
      (println (str (str/join " " jvm) " need next.jdbc: bb test:update:jvm " (str/join " " jvm)))
      (System/exit 1))
    (apply require nss)
    (let [{:keys [error]} (binding [t/report (fn [m]
                                               (when-let [v (and (= :fail (:type m)) (first t/*testing-vars*))]
                                                 (swap! failures conj (assoc m :ns (ns-name (:ns (meta v))) :test (:name (meta v)))))
                                               (report m))]
                            (apply t/run-tests nss))
          results (doall
                   (for [[n fs] (group-by :ns @failures)
                         :let [file (str "test/" (-> (str n) (str/replace "-" "_") (str/replace "." "/")) ".clj")
                               src (slurp file)
                               [src' rewrote left] (rewrite src fs)]]
                     (do (when (not= src src') (spit file src'))
                         (println (str file ": rewrote " rewrote ", left " (count left)))
                         (doseq [{:keys [line expected reason]} left]
                           (println (str file ":" line " left: " (pr-str expected) " — " reason)))
                         [rewrote (count left)])))
          rewrote (reduce + (map first results))
          left (reduce + (map second results))]
      (when (pos? rewrote)
        (println (str "Next: read git diff, then run " (if bb? "bb test" "bb test:jvm"))))
      (System/exit (if (pos? (+ left error)) 1 0)))))
