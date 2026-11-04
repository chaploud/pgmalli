(ns mutate
  "bb mutate --file src/... [--kind table|logic] [--ns ns ...] [--fn name ...] [--limit N] [--jvm] [--db]
   [--timeout secs] [--out report.edn]: mutation testing. Each mutant of a source file (an element
   left out of a literal collection in a def; and/or, when/when-not, true/false, =/not= swapped)
   is written over the file in a temporary copy of the working tree, where the tests run; the tree
   itself is never written. A mutant the tests pass on survived: a test that pins it is missing,
   or the element does nothing."
  (:require [babashka.cli :as cli]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [bb-suite]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [rewrite-clj.node :as n]
            [rewrite-clj.zip :as z]))

(defn- locs [zloc]
  (take-while (complement z/end?) (iterate z/next zloc)))

(defn- top-form [loc]
  (loop [loc loc] (if (some-> (z/up loc) z/up) (recur (z/up loc)) loc)))

(defn- def-name [loc]
  (let [top (top-form loc)]
    (when (= :list (z/tag top)) (some-> top z/down z/right z/sexpr str))))

(defn- children [loc]
  (take-while some? (iterate z/right (z/down loc))))

(defn- line [loc] (first (z/position loc)))

(defn- short-str [loc]
  (let [s (str/replace (z/string loc) #"\s+" " ")]
    (if (< 60 (count s)) (str (subs s 0 57) "...") s)))

(defn- in-def? [loc]
  (#{"def" "def-"} (some-> (top-form loc) z/down z/sexpr str)))

(defn- table-mutants
  "Each element (a key and its value in a map) left out of each literal collection inside a def;
   a vector that is a vector's element is a row, left out whole but not cut."
  [zloc]
  (for [loc (locs zloc)
        :let [tag (z/tag loc)]
        :when (and (#{:vector :map :set} tag) (in-def? loc)
                   (not (and (= :vector tag) (= :vector (some-> (z/up loc) z/tag)))))
        :let [elems (children loc)
              groups (if (= :map tag) (partition 2 elems) (map vector elems))
              ctor ({:vector n/vector-node :map n/map-node :set n/set-node} tag)]
        i (range (count groups))
        :let [kept (mapcat identity (concat (take i groups) (drop (inc i) groups)))
              gone (first (nth groups i))]]
    {:kind :table
     :line (line gone)
     :def (def-name loc)
     :mutation (str "remove " (str/join " " (map short-str (nth groups i))))
     :source (z/root-string (z/replace loc (ctor (interpose (n/whitespace-node " ") (map z/node kept)))))}))

(def ^:private swaps
  {'and 'or, 'or 'and, 'when 'when-not, 'when-not 'when, '= 'not=, 'not= '=, 'true 'false, 'false 'true})

(defn- logic-mutants
  "Each and/or, when/when-not and =/not= at a call's head, and each true/false, swapped."
  [zloc]
  (for [loc (locs zloc)
        :when (= :token (z/tag loc))
        :let [form (z/sexpr loc)
              to (when (or (boolean? form) (symbol? form)) (swaps (if (boolean? form) (symbol (str form)) form)))]
        :when (and to (or (boolean? form)
                          (and (= :list (some-> (z/up loc) z/tag)) (nil? (z/left loc)))))]
    {:kind :logic
     :line (line loc)
     :def (def-name loc)
     :mutation (str form " -> " to)
     :source (z/root-string (z/replace loc (n/token-node to)))}))

(defn mutants [file kinds fns]
  (let [zloc (z/of-string (slurp file) {:track-position? true})]
    (->> (concat (when (kinds "table") (table-mutants zloc)) (when (kinds "logic") (logic-mutants zloc)))
         (filter #(or (empty? fns) (contains? fns (:def %))))
         (map #(assoc % :file file)))))

(defn- test-command [{:keys [jvm ns]}]
  (if jvm
    (into ["clojure" "-M:test"] (mapcat #(vector "-n" %) ns))
    ["bb" "-e" (str "(require 'clojure.test) (babashka.classpath/add-classpath \"dev\") (apply require '" (pr-str (map symbol ns)) ")"
                    " (let [{:keys [fail error]} (apply clojure.test/run-tests '" (pr-str (map symbol ns)) ")]"
                    " (System/exit (if (pos? (+ fail error)) 1 0)))")]))

(defn- run-tests
  "{:passed? :timeout? :secs :output} of cmd run in dir; a run past timeout is killed. Without
   db?, the tests that need a database are skipped (PGMALLI_SKIP_DB=1)."
  [cmd dir timeout db?]
  (let [start (System/currentTimeMillis)
        proc (p/process cmd (cond-> {:out :string :err :string :dir dir} (not db?) (assoc :extra-env {"PGMALLI_SKIP_DB" "1"})))
        result (deref proc (* 1000 timeout) ::timeout)]
    (when (= ::timeout result) (p/destroy-tree proc))
    {:passed? (and (not= ::timeout result) (zero? (:exit result)))
     :timeout? (= ::timeout result)
     :secs (/ (- (System/currentTimeMillis) start) 1000.0)
     :output (when (map? result) (str (:out result) (:err result)))}))

(defn- copy-of-the-tree
  "A temporary copy of the working tree's files, git's tracked and untracked ones as they are
   now: the mutants are written there, so the tree itself is never touched, whatever happens to
   the run."
  []
  (let [dir (fs/create-temp-dir {:prefix "pgmalli-mutate-"})
        files (str/split-lines (:out (p/sh ["git" "ls-files" "--cached" "--others" "--exclude-standard"])))]
    (doseq [f files :when (fs/regular-file? f)]
      (fs/create-dirs (fs/parent (fs/path dir f)))
      (fs/copy f (fs/path dir f)))
    (str dir)))

(def ^:private tree
  (delay (let [t (copy-of-the-tree)]
           (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable #(fs/delete-tree t)))
           t)))

(defn -main [& args]
  (let [opts (cli/parse-opts args {:coerce {:file [] :ns [] :fn [] :limit :long :timeout :long :jvm :boolean :db :boolean}
                                   :alias {:f :file :k :kind :n :ns}})
        {:keys [file kind limit timeout out] :or {kind "table,logic" timeout 600 out "target/mutate.edn"}} opts
        nss (or (seq (:ns opts)) (map str (bb-suite/namespaces (boolean (:jvm opts)))))
        cmd (test-command (assoc opts :ns nss))
        all (cond->> (mapcat #(mutants % (set (str/split kind #",")) (set (:fn opts))) file) limit (take limit))]
    (when (empty? file) (binding [*out* *err*] (println "--file is required")) (System/exit 2))
    (println (count all) "mutants;" (str/join " " nss))
    (let [baseline (run-tests cmd @tree timeout (:db opts))]
      (when-not (:passed? baseline)
        (binding [*out* *err*]
          (println (str/join "\n" (take-last 30 (str/split-lines (str (:output baseline))))))
          (println "the tests fail with no mutant:" (str/join " " cmd)))
        (System/exit 2))
      (println "baseline" (:secs baseline) "s"))
    (let [originals (into {} (map (juxt identity slurp)) file)
          in-tree #(str (fs/path @tree %))
          results (vec (for [[i m] (map-indexed vector all)]
                         (let [r (try (spit (in-tree (:file m)) (:source m))
                                      (run-tests cmd @tree timeout (:db opts))
                                      (finally (spit (in-tree (:file m)) (originals (:file m)))))
                               m (-> (dissoc m :source)
                                     (assoc :status (cond (:timeout? r) :timeout (:passed? r) :survived :else :killed)
                                            :secs (:secs r)))]
                           (println (format "%d/%d %s %s:%d %s %s" (inc i) (count all) (name (:status m))
                                            (:file m) (:line m) (:def m) (:mutation m)))
                           m)))
          survived (filter #(= :survived (:status %)) results)]
      (some-> (java.io.File. ^String out) .getParentFile .mkdirs)
      (spit out (with-out-str (pp/pprint {:command cmd :results results})))
      (println (format "\n%d killed, %d survived, %d timed out; report %s"
                       (count (filter #(= :killed (:status %)) results)) (count survived)
                       (count (filter #(= :timeout (:status %)) results)) out))
      (doseq [m survived]
        (println (format "survived %s:%d (%s) %s" (:file m) (:line m) (:def m) (:mutation m)))))))

(apply -main *command-line-args*)
