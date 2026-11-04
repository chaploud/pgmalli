(ns pgmalli-next.main-test
  "The command line: exit codes, and what goes to stdout and to stderr."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [pgmalli-next.main :as main]
            [pgmalli-next.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(defn- cli [& args]
  (let [out (java.io.StringWriter.) err (java.io.StringWriter.)
        code (binding [*out* out *err* err] (main/run args))]
    {:code code :out (str out) :err (str err)}))

(defn- temp-dir [] (str (doto (java.io.File/createTempFile "pgmalli-main" "") .delete .mkdirs)))

(defn- config-file [config]
  (let [f (java.io.File. ^String (temp-dir) "pgmalli-next.edn")]
    (spit f (pr-str config))
    (str f)))

(deftest an-unknown-command-prints-usage-on-stderr-and-exits-2
  (is (= {:code 2 :out ""} (dissoc (cli "frob") :err)))
  (is (str/starts-with? (:err (cli "frob")) "pgmalli-next: unknown command frob\nusage:"))
  (is (str/starts-with? (:err (cli "genrate")) "pgmalli-next: unknown command genrate; did you mean: generate"))
  (is (= 2 (:code (cli "generate" "--edn"))) "--edn is check's"))

(deftest help-of-a-command-prints-usage-on-stdout-and-exits-0
  (doseq [args [["generate" "--help"] ["check" "-h"] ["--help"]]]
    (let [{:keys [code out err]} (apply cli args)]
      (is (= [0 ""] [code err]) (pr-str args))
      (is (str/starts-with? out "usage: ") (pr-str args))
      (is (str/includes? out "exit codes: 0 success") (pr-str args))
      (is (str/includes? out "PGMALLI_DEBUG=1") (pr-str args)))))

(deftest an-unknown-option-is-named-before-usage-and-exits-2
  (let [{:keys [code out err]} (cli "check" "--foo")]
    (is (= [2 ""] [code out]))
    (is (str/starts-with? err "pgmalli-next: unknown option --foo\nusage: "))))

(deftest a-config-path-that-does-not-exist-exits-2
  (let [{:keys [code out err]} (cli "generate" "/nonexistent/pgmalli-next.edn")]
    (is (= 2 code))
    (is (= "" out))
    (is (= "pgmalli-next: no config file at /nonexistent/pgmalli-next.edn; give the path of an existing pgmalli-next.edn, or none for the defaults\n" err))))

(deftest malformed-edn-names-the-path
  (let [f (config-file {})]
    (spit f "{:schemas [")
    (let [{:keys [code err]} (cli "check" f)]
      (is (= 2 code))
      (is (str/starts-with? err (str "pgmalli-next: cannot read " f " as EDN: "))))))

(deftest a-misspelt-config-key-exits-2-with-the-key-meant
  (let [{:keys [code err]} (cli "check" (config-file {:outdir "x"}))]
    (is (= 2 code))
    (is (= "pgmalli-next: invalid config: {:outdir [\"disallowed key\"]}; did you mean: :out-dir\n" err))))

(deftest a-missing-psql-exits-3-on-one-line
  (let [{:keys [code out err]} (cli "check" (config-file {:out-dir (temp-dir) :db {:psql "/nonexistent/psql"}}))]
    (is (= 3 code))
    (is (= "" out))
    (is (str/starts-with? err "pgmalli-next: cannot run psql (/nonexistent/psql)"))
    (is (= 1 (count (str/split-lines err))) "no stack trace")))

(deftest an-unreachable-database-exits-3-without-the-password
  (let [{:keys [code err]} (cli "check" (config-file {:out-dir (temp-dir) :db {:host "localhost" :port 1 :dbname "nope" :user "u" :password "s3cret"}}))]
    (is (= 3 code))
    (is (str/starts-with? err "pgmalli-next: cannot connect to localhost:1/nope with psql: "))
    (is (= 1 (count (str/split-lines err))))
    (is (not (str/includes? err "s3cret")))))

(deftest a-function-differs-by-name-and-a-file-without-functions-in-one-line
  (let [printed #(str/split-lines (with-out-str (#'main/print-difference "public" %)))]
    (is (= ["public :functions \"g\"" "  file: [{:args [], :result \"text\"}]" "  db:   nil"]
           (printed {:key :functions :name "g" :file [{:args [] :result "text"}] :db nil})))
    (is (= ["public :operators: the file has none, as an older pgmalli wrote it; generate writes them"]
           (printed {:key :operators :absent :file})))))

(deftest an-unrendered-type-is-overridden-by-its-column
  (is (= ["public: unrendered (2), left out of the schemas:"
          "  public.t ps: type pair[]"
          "    to keep it, add to pgmalli-next.edn :overrides {:public.t/ps <malli schema>}"
          "  public.t p: type pair"
          "    to keep it, add to pgmalli-next.edn :overrides {:public.t/p <malli schema>}"]
         (str/split-lines (with-out-str (#'main/print-unrendered "public" [{:kind :type :table "public.t" :column "ps" :type "pair[]"}
                                                                           {:kind :type :table "public.t" :column "p" :type "pair"}]))))))

(deftest a-server-newer-than-the-catalog-snapshot-is-a-warning
  (is (nil? (#'main/version-warning "PostgreSQL 18")))
  (is (= "PostgreSQL 19 is newer than the versions this pgmalli knows (16, 17, 18): a query's pg_catalog functions and types are typed as PostgreSQL 18 has them"
         (#'main/version-warning "PostgreSQL 19"))))

(deftest check-names-a-missing-file-and-the-command-that-writes-it
  (when *db*
    (let [out-dir (temp-dir)
          f (config-file {:out-dir out-dir :db *db*})
          {:keys [code out err]} (cli "check" f)]
      (is (= 1 code))
      (is (= "" err))
      (is (= [(str "public: no generated file at " out-dir "/public.edn")
              (str "missing: public; run `" (#'main/command "generate" f) "` and commit the files")]
             (str/split-lines out))
          "one line for the file, not every entry as a difference"))))

(deftest generate-then-check-exits-0-and-lists-the-findings-on-stdout
  (when *db*
    (exec-sql! "CREATE FUNCTION is_slug(s text) RETURNS boolean IMMUTABLE LANGUAGE sql AS $$ SELECT s ~ '^[a-z-]+$' $$;
                CREATE TABLE tags (id int PRIMARY KEY, slug text CHECK (is_slug(slug)), weight int);
                ALTER TABLE tags ADD CONSTRAINT weight_positive CHECK (weight > 0) NOT VALID;")
    (let [out-dir (temp-dir)
          f (config-file {:out-dir out-dir :db *db*})
          generated (cli "generate" f)
          {:keys [code out err]} (cli "check" f)]
      (is (= 0 (:code generated)))
      (is (str/starts-with? (:out generated) (str "wrote " out-dir "/public.edn (schema public; tables: 1, views: 0")))
      (is (str/ends-with? (:out generated) (str "; commit them, and run `" (#'main/command "check" f) "` in CI\n")))
      (is (= 0 code))
      (is (= "" err))
      (is (= ["public: unrendered (2), left out of the schemas:"
              "  public.tags tags_slug_check: CHECK (is_slug(slug))"
              "    to silence it, add to pgmalli-next.edn :overrides {\"tags_slug_check\" {:skip \"<why>\"}}"
              "  public.tags weight_positive: CHECK (weight > 0) NOT VALID"
              "    to silence it, add to pgmalli-next.edn :overrides {\"weight_positive\" {:skip \"<why>\"}}"
              "public: diagnostics (1):"
              "  info public.tags: weight_positive is NOT VALID: rows from before it may violate it, new rows may not"
              "ok: the files of public match the database"]
             (str/split-lines out)))
      (is (= {:stale nil :missing nil} (select-keys (edn/read-string (:out (cli "check" "--edn" f))) [:stale :missing]))
          "--edn prints check's map")
      (exec-sql! "ALTER TABLE tags ALTER COLUMN weight SET NOT NULL")
      (let [{:keys [code out]} (cli "check" f)]
        (is (= 1 code))
        (is (= ["public :pg.public/tags :weight"
                "  file: [:maybe [:pg/integer {:pg/type \"integer\"}]]"
                "  db:   [:pg/integer {:pg/type \"integer\"}]"]
               (take 3 (str/split-lines out)))
            "a subject line, then the file's and the database's side")
        (is (str/ends-with? out (str "stale: public (differences: 1); run `" (#'main/command "generate" f) "` and commit the files\n")))))))
