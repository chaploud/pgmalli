(ns sweep
  "Runs SQL files (PostgreSQL's own regression suite, or any DDL) against a throwaway
   PostgreSQL and takes every schema they leave through the whole of pgmalli: catalog ->
   facts -> registry -> EDN -> load -> datasets -> INSERTs back into the database. What does
   not go through is a finding, one EDN map each, written under out-dir/<file>.edn:

     :unparsed        an expression pgmalli's parser refused         (cannot handle)
     :unknown-type    a column type with no malli rendering          (cannot handle)
     :unrendered      a CHECK evaluated whole or reported, not rendered (better rendering possible)
     :error           an exception anywhere in the pipeline          (bug)
     :short           a table the dataset generator could not fill   (bug or limit)
     :rejected        a generated row the database refused           (bug: the schema accepts what the database rejects)

   bb -cp src:resources:test:dev -e \"(load-file \\\"dev/sweep.clj\\\")\" -- <sql-dir> <out-dir> [file-glob]
   PGMALLI_PG_IMAGE picks the PostgreSQL image, as for the tests."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.test.check.generators :as tcg]
            [honey.sql :as sql]
            [pgmalli.core :as pgmalli]
            [pgmalli.impl.generate :as gen]
            [pgmalli.test-db :as db]))

(def ^:private statement-timeout "20000")

(defn- psql!
  "Runs sql in dbname through the container's psql; errors do not stop it. Returns stderr."
  [dbname sql]
  (:err (p/sh ["docker" "exec" "-i" "-e" (str "PGOPTIONS=-c statement_timeout=" statement-timeout)
               db/*container* "psql" "-X" "-q" "-U" "postgres" "-d" dbname]
              {:in sql})))

(defn- literal
  "A generated value as a SQL literal psql can read (the tests go through JDBC, this round
   trip through text): java.time values as strings, a Duration in microseconds, bytes as hex."
  [v]
  (cond (instance? java.time.Duration v) (str (quot (.toNanos ^java.time.Duration v) 1000) " microseconds")
        (instance? java.time.temporal.Temporal v) (str v)
        (bytes? v) [:cast (str "\\x" (apply str (map #(format "%02x" (bit-and % 0xff)) v))) :bytea]
        :else v))

(defn- schemas [dbname]
  (->> (p/sh ["docker" "exec" db/*container* "psql" "-X" "-A" "-t" "-U" "postgres" "-d" dbname "-c"
              "SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg\\_%' AND nspname <> 'information_schema' ORDER BY 1"])
       :out str/split-lines (remove str/blank?)))

(defn- try-step
  "[value finding]: the step's value, or a finding of kind :error naming the step."
  [step f]
  (try [(f) nil]
       (catch Throwable e
         [nil {:kind :error :step step :message (ex-message e) :class (.getName (class e))
               :data (let [d (pr-str (ex-data e))] (subs d 0 (min 1200 (count d))))}])))

(defn- inserts-round-trip
  "Findings from loading a generated dataset back into the database: one per statement the
   database refused, in one transaction (a refused statement is rolled back to its savepoint,
   the rest goes on; the whole is rolled back at the end)."
  [dbname registry dataset]
  (let [dataset (into {} (for [[t rows] dataset] [t (mapv #(into {} (for [[k v] %] [k (literal v)])) rows)]))
        [stmts err] (try-step :inserts #(vec (pgmalli/inserts registry dataset)))
        formatted (when-not err (mapv (fn [q] (let [[t e] (try-step :format #(first (sql/format q {:inline true})))]
                                                (or t (assoc e :table (let [t (:insert-into q)] (if (vector? t) (last t) t)))))) stmts))
        [texts err2] [(filter string? formatted) (seq (remove string? formatted))]
        stmts (when-not err (filterv #(string? (nth formatted (.indexOf ^java.util.List stmts %))) stmts))]
    (cond err [err]
          err2 err2
          :else
          ;; the script's own rows would take keys the dataset uses: the tables are emptied first (rolled back too)
          (let [script (str "\\set ON_ERROR_ROLLBACK on\nBEGIN;\n"
                            "\\warn STMT truncate\nTRUNCATE " (str/join ", " (map (fn [q] (let [t (:insert-into q)] (name (if (vector? t) (last t) t)))) stmts)) " CASCADE;\n"
                            (str/join "\n" (map-indexed (fn [i t] (str "\\warn STMT " i "\n" t ";")) texts))
                            "\nROLLBACK;\n")
                {:keys [err]} (p/sh ["docker" "exec" "-i" db/*container* "psql" "-X" "-q" "-U" "postgres" "-d" dbname] {:in script})
                ;; stderr carries the markers and the errors in order
                groups (rest (str/split (str err) #"(?m)^STMT "))]
            (for [g groups
                  :let [[n & lines] (str/split-lines g)
                        errors (filter #(str/starts-with? % "ERROR") lines)]
                  :when (seq errors)
                  :let [i (parse-long (str/trim n))]
                  :when i
                  :let [q (nth stmts i)]]
              {:kind :rejected :table (let [t (:insert-into q)] (if (vector? t) (last t) t))
               :message (str/join "\n" (remove #(str/starts-with? % "STMT") lines))
               :statement (let [t (nth texts i)] (subs t 0 (min 800 (count t))))})))))

(defn- sweep-schema
  "Every finding of one schema of a database."
  [dbname schema]
  (let [config {:db (assoc db/*db* :db dbname)}
        [data e1] (try-step :generate #(gen/generated config schema))]
    (if e1
      [e1]
      (let [facts-findings (concat (for [f (:unrendered data)]
                                     (if (= :unparsed (:fact f))
                                       {:kind :unparsed :table (:table f) :type-name (:type-name f) :column (:column f) :constraint (:constraint f) :input (:input f) :error (:error f)}
                                       (if (= :unknown-type (:fact f))
                                         {:kind :unknown-type :table (:table f) :column (:column f) :type (:type f)}
                                         {:kind :unrendered :table (:table f) :type-name (:type-name f) :column (:column f) :constraint (:constraint f) :fact (:fact f) :expr (:expr f)}))))
            [_ e2] (try-step :edn #(gen/edn-string data))
            [registry e3] (when-not e2 (try-step :load #(pgmalli/registry data)))
            tables (when registry (filter #(and (keyword? %) (= "pg" (subs (namespace %) 0 2)) (not (str/ends-with? (namespace %) "insert"))) (keys (:registry data))))
            [dataset e4] (when (and registry (seq tables))
                           (try-step :dataset #(tcg/generate (pgmalli/dataset-generator registry {:rows 3}) 20 7)))
            short (for [[t {:keys [wanted got reasons]}] (some-> dataset meta :pgmalli/short)]
                    {:kind :short :table t :wanted wanted :got got :reasons reasons})
            rejected (when dataset (inserts-round-trip dbname registry dataset))]
        (concat facts-findings (remove nil? [e2 e3 e4]) short rejected)))))

(defn- sweep-file [i f]
  (let [dbname (str "s_" i)
        _ (psql! "postgres" (str "CREATE DATABASE " dbname))
        ;; regression scripts drop their objects at the end; keep them
        _ (psql! dbname (str/replace (slurp f) #"(?m)^\s*DROP\b" "-- DROP"))
        findings (vec (for [s (schemas dbname) f (sweep-schema dbname s)] (assoc f :schema s)))]
    (psql! "postgres" (str "DROP DATABASE " dbname " WITH (FORCE)"))
    findings))

(defn sweep [sql-dir out-dir glob]
  (fs/create-dirs out-dir)
  (db/with-postgres
    (fn []
      (doseq [[i f] (map-indexed vector (sort (map str (fs/glob sql-dir (or glob "*.sql")))))
              :let [name (fs/file-name f)
                    out (fs/file out-dir (str name ".edn"))]
              ;; a run cut short is resumed: files already swept are kept
              :when (not (fs/exists? out))]
        (let [start (System/currentTimeMillis)
              findings (try (sweep-file i f) (catch Throwable e [{:kind :error :step :file :message (ex-message e)}]))]
          (spit out (with-out-str (binding [*print-namespace-maps* false pp/*print-right-margin* 110]
                                    (pp/pprint {:file name :image db/image :ms (- (System/currentTimeMillis) start)
                                                :findings findings}))))
          (println name (count findings) (pr-str (frequencies (map :kind findings))) (str (- (System/currentTimeMillis) start) "ms")))))))

(let [[sql-dir out-dir glob] *command-line-args*]
  (sweep sql-dir out-dir glob))
