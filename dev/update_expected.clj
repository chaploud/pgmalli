(ns update-expected
  "bb test:update [ns ...]: run the bb suite and write each failing (is (= expected actual)) back
   with the value it got, where the expected side is a literal. Read the git diff before
   committing: a rewritten expectation is a changed behaviour."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :as t]
            [rewrite-clj.zip :as z]))

(defn- literal? [form]
  (not-any? #(or (symbol? %) (seq? %)) (tree-seq coll? seq form)))

(defn- print-literal
  "value as source that reads back as it: a symbol quoted ('decimal?)."
  [v]
  (cond (symbol? v) (str "'" v)
        (map? v) (str "{" (str/join " " (map (fn [[k x]] (str (print-literal k) " " (print-literal x))) v)) "}")
        (vector? v) (str "[" (str/join " " (map print-literal v)) "]")
        (set? v) (str "#{" (str/join " " (map print-literal v)) "}")
        (seq? v) (str "(" (str/join " " (map print-literal v)) ")")
        :else (binding [*print-namespace-maps* false] (pr-str v))))

(defn- readable? [v]
  (not-any? #(or (fn? %) (and (not (coll? %)) (str/starts-with? (pr-str %) "#object"))) (tree-seq coll? seq v)))

(defn- rewrite
  "src with each failing (= a b) of failures whose literal side is replaced by the value the
   other side gave; [src' unwritten]."
  [src failures]
  (let [;; a form failing with several values (an is in a loop) is left for a person
        by-form (into {} (for [[f ms] (group-by :expected failures) :when (apply = (map :actual ms))] [f (first ms)]))
        written (atom #{})
        out (loop [zloc (z/of-string src {:track-position? true})]
              (if (z/end? zloc)
                (z/root-string zloc)
                (let [f (when (= :list (z/tag zloc)) (try (z/sexpr zloc) (catch Exception _ nil)))
                      {:keys [actual] :as failure} (get by-form f)]
                  (if-let [[_ a b] (when failure f)]
                    (let [[_ [_ va vb]] actual
                          [i v] (cond (literal? a) [1 vb] (literal? b) [2 va])]
                      (if (and i (readable? v))
                        (let [side (nth (iterate z/right (z/down zloc)) i)]
                          (swap! written conj f)
                          (recur (z/next (z/up (z/replace side (z/node (z/of-string (print-literal v))))))))
                        (recur (z/next zloc))))
                    (recur (z/next zloc))))))]
    [out (remove #(@written (:expected %)) failures)]))

(defn -main [& nss]
  (let [nss (map symbol (or (seq nss)
                            (->> (fs/glob "test" "**/*_test.clj")
                                 (map #(-> (str (fs/strip-ext (fs/relativize "test" %))) (str/replace "/" ".") (str/replace "_" "-")))
                                 (remove #{"pgmalli.accept-test" "pgmalli.catalog-fuzz-test" "pgmalli.driver-types-test"
                                           "pgmalli.oracle-test" "pgmalli.row-keys-test" "pgmalli.upstream-test"}))))
        failures (atom [])
        report t/report]
    (apply require nss)
    (binding [t/report (fn [m]
                         (when (and (= :fail (:type m)) (seq? (:expected m)) (= '= (first (:expected m)))
                                    (seq? (:actual m)) (= 'not (first (:actual m))))
                           (swap! failures conj (assoc m :ns (ns-name (:ns (meta (first t/*testing-vars*)))))))
                         (report m))]
      (apply t/run-tests nss))
    (doseq [[n fs*] (group-by :ns @failures)
            :let [file (str "test/" (-> (str n) (str/replace "-" "_") (str/replace "." "/")) ".clj")
                  [src unwritten] (rewrite (slurp file) fs*)]]
      (spit file src)
      (println (str file ": rewrote " (- (count fs*) (count unwritten)) ", left " (count unwritten)))
      (doseq [u unwritten] (println "  left:" (pr-str (:expected u)))))))

(apply -main *command-line-args*)
