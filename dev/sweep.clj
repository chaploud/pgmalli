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

   bb sweep <sql-dir> <out-dir> [file-glob]; bb sweep:report <out-dir> groups what it found.
   PGMALLI_PG_IMAGE picks the PostgreSQL image, as for the tests."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.test.check.generators :as tcg]
            [honey.sql :as sql]
            [pgmalli.core :as pgmalli]
            [pgmalli.data :as data]
            [pgmalli.impl.files :as files]
            [pgmalli.pipeline :as pipeline :refer [try-step]]
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

(defn- target
  "The table an INSERT goes into, as SQL: a keyword's name, or the quoted text of [:raw ...]
   (identity tables come as [{:overriding-value :system} t], quoted names as [[:raw ...]])."
  [q]
  (let [t (:insert-into q)
        t (if (and (vector? t) (not= :raw (first t))) (last t) t)]
    (if (vector? t) (second t) (name t))))

(defn- inserts-round-trip
  "Findings from loading a generated dataset back into the database: one per statement the
   database refused, in one transaction (a refused statement is rolled back to its savepoint,
   the rest goes on; the whole is rolled back at the end)."
  [dbname registry dataset]
  (let [dataset (into {} (for [[t rows] dataset] [t (mapv #(into {} (for [[k v] %] [k (literal v)])) rows)]))
        [stmts err] (try-step :inserts #(vec (data/inserts registry dataset)))
        formatted (when-not err (mapv (fn [q] (let [[t e] (try-step :format #(first (sql/format q {:inline true})))]
                                                (or t (assoc e :table (target q))))) stmts))
        [texts err2] [(filter string? formatted) (seq (remove string? formatted))]
        stmts (when-not err (filterv #(string? (nth formatted (.indexOf ^java.util.List stmts %))) stmts))]
    (cond err [err]
          err2 err2
          :else
          ;; the script's own rows would take keys the dataset uses: the tables are emptied first (rolled back too)
          (let [script (str "\\set ON_ERROR_ROLLBACK on\nBEGIN;\n"
                            "\\warn STMT truncate\nTRUNCATE " (str/join ", " (map target stmts)) " CASCADE;\n"
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
              {:kind :rejected :table (target q)
               :message (str/join "\n" (remove #(str/starts-with? % "STMT") lines))
               :statement (let [t (nth texts i)] (subs t 0 (min 800 (count t))))})))))

(defn- sweep-schema
  "Every finding of one schema of a database."
  [dbname schema]
  (let [config {:db (assoc db/*db* :db dbname)}
        [data e1] (try-step :generate #(files/from-database config schema))]
    (if e1
      [e1]
      (let [facts-findings (pipeline/catalog-findings data)
            [_ e2] (try-step :edn #(files/edn-string data))
            [registry e3] (when-not e2 (try-step :load #(pgmalli/registry data)))
            tables (when registry (pipeline/row-names data))
            [dataset e4] (when (and registry (seq tables))
                           (try-step :dataset #(tcg/generate (data/dataset-generator registry {:rows 3}) 20 7)))
            short (pipeline/short-findings dataset)
            rejected (when dataset (inserts-round-trip dbname registry dataset))]
        (concat facts-findings (remove nil? [e2 e3 e4]) short rejected)))))

(defn- sweep-file [i f]
  (let [dbname (str "s_" i)
        _ (psql! "postgres" (str "CREATE DATABASE " dbname))
        ;; regression scripts drop their objects at the end; keep them
        _ (psql! dbname (str/replace (slurp f) #"(?m)^\s*DROP\b" "-- DROP"))
        ;; a schema whose pipeline does not finish in time is a finding, not a stall
        findings (vec (for [s (schemas dbname)
                            f (let [r (deref (future (vec (sweep-schema dbname s))) 120000 ::timeout)]
                                (if (= ::timeout r) [{:kind :error :step :timeout :message "120 s passed"}] r))]
                        (assoc f :schema s)))]
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
