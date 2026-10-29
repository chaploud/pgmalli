(ns pgmalli.main
  "Command line entry point. Config comes from pgmalli.edn in the working directory or
   the path given after the command.

     clojure -M -m pgmalli.main generate [pgmalli.edn]
     clojure -M -m pgmalli.main check [--edn] [pgmalli.edn]
     bb -m pgmalli.main ...

   Exit codes: 0 the files are current; 1 a file is stale or missing (run generate);
   2 a usage, config or other error; 3 the database or psql is unavailable. Findings go to
   stdout, usage, warnings and errors to stderr (PGMALLI_DEBUG=1 adds the stack trace)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [pgmalli.generate :as generate]
            [pgmalli.impl.error :as error]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.shape :as shape]))

(def ^:private usage "usage: pgmalli.main generate [pgmalli.edn] | check [--edn] [pgmalli.edn]")

(defn- warn [& xs] (binding [*out* *err*] (apply println "pgmalli:" xs)))

(defn- command [& args]
  (str/join " " (concat [(if (System/getProperty "babashka.version") "bb -m pgmalli.main" "clojure -M -m pgmalli.main")]
                        (remove nil? args))))

(defn- read-config [path]
  (let [file (io/file (or path "pgmalli.edn"))]
    (cond (.exists file)
          (let [text (try (slurp file)
                          (catch Exception e
                            (error/fail! :pgmalli.error/invalid-config (str "cannot read " file ": " (ex-message e)) {:given (str file)})))]
            (try (edn/read-string text)
                 (catch Exception e
                   (error/fail! :pgmalli.error/invalid-config (str "cannot read " file " as EDN: " (ex-message e)) {:given (str file)}))))
          path
          (error/fail! :pgmalli.error/invalid-config
                       (str "no config file at " path "; give the path of an existing pgmalli.edn, or none for the defaults")
                       {:given path})
          :else
          (do (warn "no pgmalli.edn here; using the defaults (schema public, files in resources/pgmalli)")
              {}))))

(defn- counts [data]
  (let [entries (vals (:registry data))
        kind (fn [s] (cond (:pg/view (shape/row-props s)) :view
                           (shape/row-schema? s) :table
                           (and (vector? s) (= :enum (first s))) :enum
                           :else :domain))
        f (frequencies (map kind entries))]
    (str (f :table 0) " tables, " (f :view 0) " views, " (f :enum 0) " enums, " (f :domain 0) " domains; "
         (count (:unrendered data)) " unrendered, " (count (:diagnostics data)) " diagnostics")))

(defn- override-key
  "What :overrides takes for an unrendered entry: its constraint's name, or its type as a keyword."
  [{:keys [constraint type]}]
  (if constraint constraint (keyword (str/replace (str/replace (str type) #"^[^.]+\." "") #"\(.*\)$" ""))))

(defn- print-unrendered [schema facts]
  (when (seq facts)
    (println (str schema ": unrendered (" (count facts) "), left out of the schemas:"))
    (doseq [{:keys [table domain constraint column sql type kind] :as f} facts
            :let [k (override-key f)]]
      (println (str "  " (or table domain) " " (or constraint column) ": "
                    (cond sql sql type (str "type " type) :else (name kind))))
      (println (str "    to keep it, add to pgmalli.edn :overrides {" (pr-str k) " <malli schema>}"
                    (when (string? k) (str ", or {" (pr-str k) " {:skip \"<why>\"}} to silence it")))))))

(defn- print-diagnostics [schema ds]
  (when (seq ds)
    (println (str schema ": diagnostics (" (count ds) "):"))
    (doseq [d ds] (println (str "  " (name (:severity d)) " " (:table d) ": " (:message d))))))

(defn- print-difference [schema {:keys [name key column property checks order file db]}]
  (println (str/join " " (remove nil? [schema (pr-str (or key name)) (some-> column pr-str) (some-> property pr-str)
                                       (cond checks "CHECKs" order "column order")])))
  (println "  file:" (pr-str file))
  (println "  db:  " (pr-str db)))

(defn- generate! [config path]
  (let [written (generate/generate! config)]
    (doseq [[schema out] written
            :let [data (files/read-edn out)]]
      (println "wrote" out (str "(" schema ": " (counts data) ")"))
      (print-diagnostics schema (:diagnostics data))
      (print-unrendered schema (:unrendered data)))
    (println (str "done: files written: " (count written) "; commit them, and run `" (command "check" path) "` in CI"))
    0))

(defn- check! [config path edn?]
  (let [{:keys [stale missing unrendered diagnostics] :as result} (generate/check config)
        ;; a missing file differs in every entry; saying it is missing says it all
        stale (not-empty (apply dissoc stale missing))]
    (if edn?
      (prn result)
      (do (doseq [schema missing]
            (println (str schema ": no generated file at " (files/path-for config schema))))
          (doseq [[schema ds] stale, d ds] (print-difference schema d))
          (doseq [[schema un] unrendered] (print-unrendered schema un))
          (doseq [[schema ds] diagnostics] (print-diagnostics schema ds))
          (println (if (or stale missing)
                     (str (str/join ", " (concat (for [[schema ds] stale] (str "stale: " schema " (differences: " (count ds) ")"))
                                                 (for [schema missing] (str "missing: " schema))))
                          "; run `" (command "generate" path) "` and commit the files")
                     (str "ok: the files of " (str/join ", " (:schemas (files/config config))) " match the database")))))
    (if (or stale missing) 1 0)))

(defn- run* [args]
  (let [[cmd & more] args
        flags (filter #(str/starts-with? % "-") more)
        [path & extra] (remove (set flags) more)]
    (cond (#{"help" "-h" "--help"} cmd) (do (println usage) 0)
          (or (not (#{"generate" "check"} cmd)) (seq extra)
              (seq (remove (if (= "check" cmd) #{"--edn"} #{}) flags)))
          (do (binding [*out* *err*] (println usage)) 2)
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

(defn run
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
