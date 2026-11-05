(ns pgmalli-next.main
  "Command line entry point. Config comes from pgmalli-next.edn in the working directory or
   the path given after the command.

     clojure -M -m pgmalli-next.main generate [pgmalli-next.edn]
     clojure -M -m pgmalli-next.main check [--edn] [pgmalli-next.edn]
     bb -m pgmalli-next.main ...

   Exit codes and output: README.md#keeping-the-files-current."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pgmalli-next.generate :as generate]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.files :as files]
            [pgmalli-next.impl.registry :as registry]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(defn- warn [& xs] (binding [*out* *err*] (apply println "pgmalli-next:" xs)))

(defn- command [& args]
  (str/join " " (concat [(if (System/getProperty "babashka.version") "bb -m pgmalli-next.main" "clojure -M -m pgmalli-next.main")]
                        (remove nil? args))))

(defn- usage []
  (str/join "\n" [(str "usage: " (command "generate [pgmalli-next.edn]"))
                  (str "       " (command "check [--edn] [pgmalli-next.edn]"))
                  "exit codes: 0 success (for check, the files match the database); 1 a file is stale or missing (run generate);"
                  "            2 a usage, config or other error; 3 the database or psql is unavailable"
                  "PGMALLI_DEBUG=1 prints an error's stack trace"]))

(defn- read-config [path]
  (let [file (io/file (or path "pgmalli-next.edn"))]
    (cond (.exists file)
          (let [text (try (slurp file)
                          (catch Exception e
                            (error/fail! :pgmalli.error/invalid-config (str "cannot read " file ": " (ex-message e)) {:given (str file)})))]
            (try (edn/read-string text)
                 (catch Exception e
                   (error/fail! :pgmalli.error/invalid-config (str "cannot read " file " as EDN: " (ex-message e)) {:given (str file)}))))
          path
          (error/fail! :pgmalli.error/invalid-config
                       (str "no config file at " path "; give the path of an existing pgmalli-next.edn, or none for the defaults")
                       {:given path})
          :else
          (do (warn "no pgmalli-next.edn here; using the defaults (schema public, file resources/pgmalli-next/generated.edn)")
              {}))))

(defn- counts [data]
  (let [entries (vals (:registry data))
        kind (fn [s] (cond (:pg/view (shape/row-props s)) :view
                           (shape/row-schema? s) :table
                           :else :enum))
        f (frequencies (map kind entries))]
    (str "schemas: " (str/join ", " (:schemas data)) "; tables: " (f :table 0) ", views: " (f :view 0) ", enums: " (f :enum 0)
         "; unrendered: " (count (:unrendered data)) ", diagnostics: " (count (:diagnostics data)))))

(defn- print-unrendered [facts]
  (when (seq facts)
    (println (str "unrendered (" (count facts) "), left out of the schemas:"))
    (doseq [{:keys [table column type]} facts]
      (println (str "  " table " " column ": type " type))
      (println (str "    to keep it, add to pgmalli-next.edn :overrides {" (keyword table column) " <malli schema>}")))))

(defn- print-diagnostics [ds]
  (when (seq ds)
    (println (str "diagnostics (" (count ds) "):"))
    (doseq [d ds] (println (str "  " (name (:severity d)) " " (:table d) ": " (:message d))))))

(defn- print-difference [{:keys [name key schema column property order file db]}]
  (println (str/join " " (remove nil? [(some-> key pr-str) schema (some-> name pr-str) (some-> column pr-str) (some-> property pr-str)
                                       (when order "column order")])))
  (println "  file:" (pr-str file))
  (println "  db:  " (pr-str db)))

(defn- version-warning
  "Why a file of a server's version (\"PostgreSQL 19\") is typed as an older one, nil when the
   catalog snapshot has it."
  [database-version]
  (let [versions (:versions @types/catalog)]
    (when-not (some #(= database-version (str "PostgreSQL " %)) versions)
      (str database-version " is newer than the versions this pgmalli knows (" (str/join ", " versions)
           "): a query's pg_catalog functions and types are typed as PostgreSQL " (last versions) " has them"))))

(defn- generate! [config path]
  (let [out (generate/generate! config)
        data (registry/read-generated-file out)]
    (when-let [w (version-warning (:database-version data))] (warn w))
    (println "wrote" out (str "(" (counts data) ")"))
    (print-diagnostics (:diagnostics data))
    (print-unrendered (:unrendered data))
    (println (str "done: commit it, and run `" (command "check" path) "` in CI"))
    0))

(defn- check! [config path edn?]
  (let [{:keys [stale missing unrendered diagnostics] :as result} (generate/check config)]
    (if edn?
      (prn result)
      (do (when missing (println (str "no generated file at " missing)))
          (doseq [d stale] (print-difference d))
          (print-unrendered unrendered)
          (print-diagnostics diagnostics)
          (println (cond missing (str "missing: " missing "; run `" (command "generate" path) "` and commit it")
                         stale (str "stale (differences: " (count stale) "); run `" (command "generate" path) "` and commit the file")
                         :else (str "ok: the file of " (str/join ", " (:schemas (files/config config))) " matches the database")))))
    (if (or stale missing) 1 0)))

(defn- run* [args]
  (let [[cmd & more] args
        flags (filter #(str/starts-with? % "-") more)
        [path & extra] (remove (set flags) more)
        command? (#{"generate" "check"} cmd)
        unknown (first (remove (if (= "check" cmd) #{"--edn"} #{}) flags))]
    (cond (or (#{"help" "-h" "--help"} cmd) (and command? (some #{"-h" "--help"} flags)))
          (do (println (usage)) 0)
          (or (not command?) unknown (seq extra))
          (do (binding [*out* *err*]
                (cond (and cmd (not command?) (not (str/starts-with? cmd "-")))
                      (println (str "pgmalli-next: unknown command " cmd (error/did-you-mean (error/suggest cmd ["generate" "check"]))))
                      (and cmd (not command?)) (println (str "pgmalli-next: unknown option " cmd))
                      unknown (println (str "pgmalli-next: unknown option " unknown))
                      (seq extra) (println (str "pgmalli-next: more than one config path: " (pr-str (vec (cons path extra))))))
                (println (usage)))
              2)
          :else (let [config (read-config path)]
                  (if (= "check" cmd)
                    (check! config path (some #{"--edn"} flags))
                    (generate! config path))))))

(defn- report
  "Prints an error on one line of stderr; the exit code for it."
  [^Throwable e]
  (let [kind (:pgmalli/error (ex-data e))]
    (warn (if kind (ex-message e) (str (.getName (class e)) ": " (ex-message e))))
    (if (System/getenv "PGMALLI_DEBUG")
      (binding [*out* *err*] (.printStackTrace e (java.io.PrintWriter. ^java.io.Writer *out* true)))
      (when-not kind (warn "set PGMALLI_DEBUG=1 for the stack trace")))
    (if (#{:pgmalli.error/db-unavailable :pgmalli.error/psql-missing} kind) 3 2)))

(defn ^:no-doc run
  "Runs a command line, printing to *out* and *err*; returns the exit code."
  [args]
  (binding [*print-namespace-maps* false]
    (try (run* args) (catch Throwable e (report e)))))

(defn -main [& args]
  (let [code (run args)]
    (flush)
    ;; clojure.java.shell runs psql on the agent pool, whose threads keep the JVM up a minute
    (shutdown-agents)
    (System/exit code)))
