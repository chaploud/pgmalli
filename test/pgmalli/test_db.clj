(ns pgmalli.test-db
  "One throwaway PostgreSQL container per test run, one fresh database per namespace.
   Data lives on tmpfs with durability off, as usual for CI."
  (:require [babashka.process :as p]
            [clojure.string :as str]
            [clojure.test :as t]
            [honey.sql :as sql]
            [pgmalli.impl.types :as types])
  (:import (java.net ServerSocket)))

;; HoneySQL writes an operator it has not registered as a function call. Registered here, on
;; load, since the registry is global: a namespace that registered them in a test would change
;; what the namespaces run after it format.
(doseq [[n] (:operators @types/catalog)]
  (when-not (sql/registered-op? (keyword n)) (sql/register-op! (keyword n))))

(def ^:dynamic *db* nil)
(def ^:dynamic *container* nil)

(def image (or (System/getenv "PGMALLI_PG_IMAGE") "postgres:17-alpine"))

(defn- free-port []
  (with-open [s (ServerSocket. 0)] (.getLocalPort s)))

(defn- start-container! []
  ;; the port can be taken between free-port and docker run, so retry with a new one
  (loop [attempt 1]
    (let [port (free-port)
          name (str "pgmalli-test-" port)
          {:keys [exit]} (p/sh ["docker" "run" "-d" "--rm" "--name" name
                                "-e" "POSTGRES_PASSWORD=pw" "-e" "POSTGRES_DB=t"
                                "--tmpfs" "/var/lib/postgresql:rw,size=256m"
                                "-p" (str "127.0.0.1:" port ":5432") image
                                "-c" "fsync=off" "-c" "synchronous_commit=off" "-c" "full_page_writes=off"])]
      (cond (zero? exit) {:name name :port port}
            (< attempt 3) (recur (inc attempt))
            :else (throw (ex-info "could not start the postgres container" {:port port :image image}))))))

(defn- wait-ready! [name]
  ;; the init-time temporary server only listens on a unix socket, so check over TCP
  (loop [n 60]
    (when-not (zero? (:exit (p/sh ["docker" "exec" name "pg_isready" "-U" "postgres" "-h" "127.0.0.1" "-q"])))
      (when (zero? n) (throw (ex-info "postgres did not become ready" {:name name})))
      (Thread/sleep 500)
      (recur (dec n)))))

(def ^:private container
  (delay
    (let [c (start-container!)]
      ;; the thread pool behind babashka.process is gone at shutdown, so use ProcessBuilder here
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. #(.waitFor (.start (ProcessBuilder. ["docker" "rm" "-f" (:name c)])))))
      (wait-ready! (:name c))
      c)))

(defn- exec-in! [container dbname sql]
  (let [{:keys [exit err]} (p/sh ["docker" "exec" "-i" container "psql" "-X" "-q" "-v" "ON_ERROR_STOP=1" "-U" "postgres" "-d" dbname]
                                 {:in sql})]
    (when-not (zero? exit) (throw (ex-info (str "SQL failed: " (str/trim err)) {:sql sql})))))

(defn version
  "The version of a jar on the classpath, by its artifact name (\"honeysql\"); nil when absent."
  [artifact]
  (second (re-find (re-pattern (str "/" artifact "-([^/:]+)\\.jar")) (System/getProperty "java.class.path"))))

(defn- server-version [container]
  (let [{:keys [out]} (p/sh ["docker" "exec" container "psql" "-X" "-tA" "-U" "postgres" "-c" "SHOW server_version"])]
    (str/trim out)))

(def ^:private upstream
  (delay (str "upstream: PostgreSQL " (server-version (:name @container))
              (apply str (for [[label artifact] [["pgjdbc" "postgresql"] ["next.jdbc" "next.jdbc"] ["HoneySQL" "honeysql"] ["malli" "malli"]]]
                           (str ", " label " " (or (version artifact) "absent")))))))

(def ^:private said-skipped
  (delay (println "PGMALLI_SKIP_DB is set: the tests that need a database are skipped")))

(def ^:private one-reading-and-sending
  ;; the doc's code itself, so what the tests read rows with is what the doc tells applications to install
  (delay (let [doc (slurp "docs/reading-rows.md")
               block (second (re-find #"(?s)## The one reading.*?```clojure\n(.*?)```" doc))]
           (binding [*ns* (create-ns 'pgmalli.test-db.one-reading)]
             (refer-clojure)
             (load-string block)))))

(defn with-postgres
  "clojure.test :once fixture. Binds *db* to an empty database for the namespace, and names the
   upstream versions that ran in every failure under it. On the JVM, rows are read as
   docs/reading-rows.md#the-one-reading-and-sending reads and sends them. Set PGMALLI_SKIP_DB to run without docker;
   *db* is then nil."
  [f]
  (if (System/getenv "PGMALLI_SKIP_DB")
    (do @said-skipped (f))
    (let [{:keys [name port]} @container
          _ (when-not (System/getProperty "babashka.version") @one-reading-and-sending)
          dbname (str "t_" (System/nanoTime))]
      (exec-in! name "postgres" (str "CREATE DATABASE " dbname))
      (binding [*db* {:host "localhost" :port port :dbname dbname :user "postgres" :password "pw"}
                *container* name]
        (t/testing @upstream (f))))))

(defn sql-literal
  "x as an SQL string literal."
  [x]
  (str "'" (str/replace (str x) "'" "''") "'"))

(defn exec-sql!
  "Runs SQL against the namespace's database through the container's psql."
  [sql]
  (exec-in! *container* (:dbname *db*) sql))

(defn copies!
  "n new databases copied from db (CREATE DATABASE ... TEMPLATE), as :db maps. Nothing may be
   connected to db; PostgreSQL waits a few seconds for a closed connection's backend to go."
  [db n]
  (let [dbs (for [k (range n)] (assoc db :dbname (str (:dbname db) "_" (System/nanoTime) "_" k)))]
    (exec-in! *container* "postgres" (apply str (for [d dbs] (str "CREATE DATABASE " (:dbname d) " TEMPLATE " (:dbname db) ";\n"))))
    (vec dbs)))

(defn drop!
  "Drops the databases dbs (:db maps), closing what is connected to them."
  [dbs]
  (exec-in! *container* "postgres" (apply str (for [d dbs] (str "DROP DATABASE IF EXISTS " (:dbname d) " WITH (FORCE);\n")))))

(defn jdbc-url
  "The JDBC URL of db, *db* by default, with db's :url-params (\"stringtype=unspecified\"), which
   a :db map pgmalli reads may not carry."
  ([] (jdbc-url *db*))
  ([{:keys [host port dbname url-params]}] (str "jdbc:postgresql://" host ":" port "/" dbname (some->> url-params (str "?")))))

(defn datasource
  "A next.jdbc datasource of db, *db* by default (JVM only: babashka has no next.jdbc)."
  ([] (datasource *db*))
  ([{:keys [user password] :as db}]
   ((requiring-resolve 'next.jdbc/get-datasource) {:jdbcUrl (jdbc-url db) :user user :password password})))
