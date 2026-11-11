(ns sweep
  "Runs SQL files (PostgreSQL's own regression suite, or any DDL) against a throwaway
   PostgreSQL and takes every schema they leave through the whole of pgmalli: catalog ->
   facts -> registry -> EDN -> load. What does
   not go through is a finding, one EDN map each, written under out-dir/<file>.edn:

     :unknown-type    a column type with no malli rendering          (cannot handle)
     :error           an exception anywhere in the pipeline          (bug)

   bb sweep <sql-dir> <out-dir> [file-glob]; bb sweep:report <out-dir> groups what it found.
   PGMALLI_PG_IMAGE picks the PostgreSQL image, as for the tests."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [pgmalli.core :as pgmalli]
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

(defn- schemas [dbname]
  (->> (p/sh ["docker" "exec" db/*container* "psql" "-X" "-A" "-t" "-U" "postgres" "-d" dbname "-c"
              "SELECT nspname FROM pg_namespace WHERE nspname NOT LIKE 'pg\\_%' AND nspname <> 'information_schema' ORDER BY 1"])
       :out str/split-lines (remove str/blank?)))

(defn- sweep-schema
  "Every finding of one schema of a database."
  [dbname schema]
  (let [config {:db (assoc db/*db* :dbname dbname)}
        [data e1] (try-step :generate #(db/generated config schema))]
    (if e1
      [e1]
      (let [facts-findings (pipeline/catalog-findings data)
            [_ e2] (try-step :edn #(files/edn-string data))
            [_ e3] (when-not e2 (try-step :load #(pgmalli/registry data)))]
        (concat facts-findings (remove nil? [e2 e3]))))))

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
