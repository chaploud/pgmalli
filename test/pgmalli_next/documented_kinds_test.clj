(ns pgmalli-next.documented-kinds-test
  "Each :kind the source makes is the one its table in the docs says, and humanize explains
   each problem's."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pgmalli-next.honeysql :as h]))

(defn- made
  "The kinds a map literal {:kind :k ...} of files makes, re the pattern after {:kind :k."
  [files after]
  (set (for [f files, [_ k] (re-seq (re-pattern (str "\\{:kind :([a-z-]+)" after)) (slurp f))] (keyword k))))

(defn- table
  "The kinds of the table of file whose header row is header, its rows | `:k` | ..."
  [file header]
  (let [lines (drop-while #(not= header %) (str/split-lines (slurp file)))]
    (set (for [l (take-while #(str/starts-with? % "|") (drop 2 lines))]
           (keyword (second (re-find #"^\| `:([a-z-]+)`" l)))))))

(deftest every-problem-kind-is-explained-and-in-readme
  (let [kinds (made ["src/pgmalli_next/honeysql.clj" "src/pgmalli_next/impl/problems.clj" "src/pgmalli_next/impl/args.clj" "src/pgmalli_next/impl/query.clj"] "")]
    (is (= kinds (set (keys @#'h/explanations))) "explanations in src/pgmalli_next/honeysql.clj")
    (is (= kinds (table "README.md" "| `:kind` | for |")) "the table of :kind in README.md")))

(deftest every-diagnostic-kind-is-in-the-types-doc
  (is (= (made ["src/pgmalli_next/impl/render.clj"] " :severity :")
         (table "docs/types.md" "| `:kind` | severity | when |"))))
