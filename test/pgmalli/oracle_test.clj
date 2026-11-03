(ns pgmalli.oracle-test
  "check reports nothing for a query PostgreSQL takes, query-schema's return takes the rows
   next.jdbc returns for it and row-schema names and types its columns as PostgreSQL describes
   them: the corpus, seeded mutants of it and the queries generated from the oracle schema's
   catalog, against a real PostgreSQL, sent inlined and as parameters (see pgmalli.oracle; bb
   oracle for the misses as well). arg-types takes each value PostgreSQL takes for an argument
   but those the stricter rule leaves out (pgmalli.oracle/arg-matrix). JVM only: babashka has
   no JDBC, so bb test does not list it."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli.honeysql :as h]
            [pgmalli.oracle :as oracle]
            [pgmalli.test-db :as test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once (fn [t] (with-postgres #(do (when *db* (exec-sql! oracle/schema-sql)) (t)))))

(def ^:private seed (parse-long (or (System/getenv "PGMALLI_FUZZ_SEED") "42")))
(def ^:private mutants (parse-long (or (System/getenv "PGMALLI_ORACLE_MUTANTS") "3")))

(defn- re-run [under]
  (str "\n  re-run: bb oracle " seed " " mutants " (" under "), or PGMALLI_FUZZ_SEED=" seed
       " PGMALLI_ORACLE_MUTANTS=" mutants " clojure -M:test -n pgmalli.oracle-test"))

(def ^:private results (delay (oracle/run *db* seed mutants)))
(def ^:private arg-failures (delay (oracle/arg-matrix *db*)))

(def pending
  "False rejections the oracle finds in src that src does not fix yet, held out of the failing
   tests until it does: {:test :id :reason :repro}, :test the deftest's failures it holds
   (:false-positive, :return, :column, :arg), :id a pattern of the failure's signature: the
   case's id (its corpus id for a mutant), for :return followed by the keys the return
   refuses; \"table.column context\" for :arg. pending-entries-still-fail fails on an entry
   that no longer matches a failure, so a fix empties it."
  [])

(defn- held
  "The pending entry of test that holds the failure of signature id, or nil."
  [test id]
  (some #(when (and (= test (:test %)) (re-find (:id %) id)) %) pending))

(defn- case-id [r] (name (or (:base r) (:id r))))
(defn- arg-id [f] (str (:table f) "." (:column f) " " (name (:context f))))

(defn- return-id
  "The signature of return rejection x of case r: its case-id and the keys whose values the
   return refuses."
  [r x]
  (str (case-id r) " " (str/join "," (sort (distinct (map name (mapcat #(when (map? %) (keys %)) (tree-seq sequential? seq (:explain x)))))))))

(defn- failures
  "[test signature] of each failure of the run, pending or not."
  []
  (concat (for [r @results :when (= :false-positive (:outcome r))] [:false-positive (case-id r)])
          (for [r @results x (:return-rejections r)] [:return (return-id r x)])
          (for [r @results :when (seq (:column-mismatches r))] [:column (case-id r)])
          (for [f @arg-failures] [:arg (arg-id f)])))

(deftest corpus-queries-are-distinct
  (doseq [[k f] {"id" first "query" second}
          [x n] (frequencies (map f oracle/corpus))
          :when (< 1 n)]
    (is false (str "corpus.edn has the " k " " (pr-str x) " " n " times"))))

(def ^:private pinned-honeysql
  (get-in (edn/read-string (slurp "deps.edn")) [:aliases :test :extra-deps 'com.github.seancorfield/honeysql :mvn/version]))

(deftest every-query-ran-in-both-formats
  (when *db*
    (let [ran (group-by (juxt :id :format) @results)
          ;; an older HoneySQL (bb test:floor) cannot write every corpus query
          pinned? (= pinned-honeysql (test-db/version "honeysql"))]
      (doseq [[id] oracle/corpus
              fmt (keys oracle/formats)
              :let [[r] (ran [id fmt])]]
        (is r (str id " did not run " (name fmt)))
        (when (and r pinned?)
          (is (not= :unformatted (:outcome r)) (str id " sent " (name fmt) ": HoneySQL " pinned-honeysql " cannot write it: " (:format-error r)))))
      (doseq [r @results :when (and (:generated? r) (= :unformatted (:outcome r)))]
        (is false (str "the generated " (:id r) " does not format: " (:format-error r) "\n  query: " (pr-str (:query r))))))))

(deftest check-reports-nothing-postgresql-takes
  (when *db*
    (doseq [r (filter #(= :false-positive (:outcome %)) @results)
            :when (not (held :false-positive (case-id r)))]
      (is false (str "PostgreSQL takes " (:id r) (some->> (:mutation r) (str " mutated by ")) " sent " (name (:format r))
                     "\n  SQL:    " (:sql r) (when (seq (:params r)) (str "\n  params: " (pr-str (:params r))))
                     "\n  check:  " (pr-str (:problems r))
                     (re-run "the case is under :false-positive"))))))

(deftest query-schema-return-takes-what-postgresql-returns
  (when *db*
    (doseq [r @results
            x (:return-rejections r)
            :when (not (held :return (return-id r x)))]
      (is false (str (if (= :keys (:result x))
                       "row-schema's keys are not the result set's for "
                       "query-schema's return refuses what ")
                     (:id r) (some->> (:mutation r) (str " mutated by ")) " sent " (name (:format r)) " returned"
                     " with the " (name (:builder x)) " builder under " (case (:result x) :one "{:result :one}" :many "the default result" :keys "its keys")
                     "\n  SQL:    " (:sql r) (when (seq (:params r)) (str "\n  params: " (pr-str (:params r))))
                     "\n  rows:   " (:rows r)
                     (cond (:error x) (str "\n  error:  " (:error x))
                           (= :keys (:result x)) (str "\n  " (pr-str (dissoc x :builder :result)))
                           :else (str "\n  schema: " (pr-str (:schema x)) "\n  value:  " (pr-str (:value x)) "\n  explain: " (pr-str (:explain x))))
                     (re-run "the case is under return rejected"))))))

(deftest row-schema-describes-the-columns-postgresql-returns
  (when *db*
    (doseq [r @results
            :when (not (held :column (case-id r)))
            x (:column-mismatches r)]
      (is false (str "row-schema, under the unqualified builder, describes the columns of " (:id r) (some->> (:mutation r) (str " mutated by "))
                     " sent " (name (:format r)) " otherwise than PostgreSQL's ResultSet metadata"
                     "\n  SQL:      " (:sql r) (when (seq (:params r)) (str "\n  params:   " (pr-str (:params r))))
                     "\n  mismatch: " (pr-str x)
                     (re-run "the case is under columns described otherwise"))))))

(deftest arg-types-take-what-postgresql-takes
  (when *db*
    (doseq [f @arg-failures :when (not (held :arg (arg-id f)))]
      (is false (str (oracle/arg-failure-text f) (re-run "under argument matrix failures"))))))

(deftest pending-entries-still-fail
  (when *db*
    (let [fs (failures)]
      (doseq [{:keys [test id reason]} pending
              :when (not-any? (fn [[t i]] (and (= test t) (re-find id i))) fs)]
        (is false (str "pending " test " " id " no longer fails: remove it from pgmalli.oracle-test/pending (" reason ")"))))))

(deftest cases-over-several-workers-come-back-as-over-one
  (when *db*
    (let [cs (take 24 (oracle/cases (oracle/mutation-names *db*) seed 1))
          over #(binding [oracle/*workers* %] (oracle/run-cases *db* cs seed false))]
      (is (= (over 1) (over 3))))))

(def ^:private wrappers
  "Places a query keeps its problems in: a FROM subquery, a CTE body, EXISTS."
  {:from #(hash-map :select [:*] :from [[% :sub]])
   :cte #(hash-map :with [[:w %]] :select [:*] :from [:w])
   :exists #(hash-map :select [1] :where [:exists %])})

(deftest a-query-wrapped-keeps-its-problems
  (when *db*
    (let [registry (oracle/registry *db*)
          problems (fn [q opts] (set (map (fn [p] (dissoc p :in)) (remove (comp #{:unread-clause} :kind) (h/check registry q opts)))))]
      (doseq [{:keys [id mutation query opts]} (oracle/cases (oracle/mutation-names *db*) seed mutants)
              :when (and (map? query) (some query [:select :union :union-all :intersect :except]))
              :let [opts (update opts :format merge {:inline true})
                    alone (problems query opts)]
              [wrap w] wrappers]
        (is (= alone (problems (w query) opts))
            (str id (some->> mutation (str " mutated by ")) " wrapped in " (name wrap) "\n  query: " (pr-str query)))))))
