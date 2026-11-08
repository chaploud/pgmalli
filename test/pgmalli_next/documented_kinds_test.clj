(ns pgmalli-next.documented-kinds-test
  "Each :kind the source makes is the one its table in the docs says, and humanize explains
   each problem's."
  (:require [clojure.edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.error :as error]
            pgmalli-next.impl.jdbc))

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

(deftest every-error-kind-thrown-has-a-category-and-a-row-in-readme
  (let [thrown (set (for [f (file-seq (java.io.File. "src")) :when (str/ends-with? (str f) ".clj")
                          k (re-seq #":pgmalli\.error/[a-z-]+" (slurp f))]
                      (keyword (subs k 1))))
        lines (drop-while #(not= "| `:pgmalli/error` | when |" %) (str/split-lines (slurp "README.md")))
        documented (set (for [l (take-while #(str/starts-with? % "|") (drop 2 lines))
                              :let [cell (second (str/split l #"\|"))]
                              [_ k] (re-seq #"`(?::pgmalli\.error/)?([a-z-]+)`" cell)]
                          (keyword "pgmalli.error" k)))]
    (is (= thrown (set (keys error/categories))) "error/categories")
    (is (= thrown documented) "the table of :pgmalli/error in README.md")))

(deftest the-options-table-is-the-options-pgmalli-takes
  (let [lines (drop-while #(not= "| option | for |" %) (str/split-lines (slurp "docs/queries.md")))
        rows (take-while #(str/starts-with? % "|") (drop 2 lines))
        option (fn [l] (keyword (second (re-find #"^\| `:([a-z-]+)" l))))
        format-row (some #(when (= :format (option %)) %) rows)]
    (is (= (set (keys @#'pgmalli-next.impl.jdbc/option-entries)) (set (map option rows))) "docs/queries.md's table of options")
    (is (= (set @#'pgmalli-next.impl.jdbc/format-read)
           (set (map (comp keyword second) (re-seq #"`:([a-z-]+)`" (first (str/split (nth (str/split format-row #"\|") 2) #" are read"))))))
        "the :format keys the row says are read")))

(defn- slug [heading] (-> heading str/lower-case (str/replace #"[^\w\- ]" "") (str/replace " " "-")))

(deftest every-anchor-a-doc-or-a-message-names-is-in-its-file
  (let [md (into {"README.md" (slurp "README.md")} (for [f (.listFiles (java.io.File. "docs")) :when (str/ends-with? (.getName f) ".md")] [(.getName f) (slurp f)]))
        anchors (fn [text] (into (set (for [[_ h] (re-seq #"(?m)^#+ (.+)$" text)] (slug h))) (map second (re-seq #"<a id=\"([^\"]+)\"></a>" text))))
        sources (concat (for [f (file-seq (java.io.File. "src")) :when (str/ends-with? (str f) ".clj")] [(str f) (slurp f)]) md)]
    (doseq [[from text] sources
            [_ file anchor] (re-seq #"([A-Za-z-]+\.md)#([a-z0-9-]+)" text)
            :let [target (get md (if (= "README.md" file) "README.md" file))]
            :when target]
      (is (contains? (anchors target) anchor) (str from ": " file "#" anchor)))))

(deftest bb-runs-the-versions-of-deps-edn
  (let [deps (clojure.edn/read-string (slurp "deps.edn"))
        versions (merge (:deps deps) (get-in deps [:aliases :test :extra-deps]))]
    (doseq [[lib v] (:deps (clojure.edn/read-string (slurp "bb.edn")))]
      (is (= v (get versions lib)) (str lib " in bb.edn and deps.edn")))))
