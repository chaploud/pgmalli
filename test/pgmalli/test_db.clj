(ns pgmalli.test-db
  "One throwaway PostgreSQL container per test run, one fresh database per namespace.
   Data lives on tmpfs with durability off, as usual for CI."
  (:require [babashka.process :as p]
            [clojure.string :as str])
  (:import (java.net ServerSocket)))

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
                                "-p" (str port ":5432") image
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

(defn with-postgres
  "clojure.test :once fixture. Binds *db* to an empty database for the namespace.
   Set PGMALLI_SKIP_DB to run without docker; *db* is then nil."
  [f]
  (if (System/getenv "PGMALLI_SKIP_DB")
    (f)
    (let [{:keys [name port]} @container
          dbname (str "t_" (System/nanoTime))]
      (exec-in! name "postgres" (str "CREATE DATABASE " dbname))
      (binding [*db* {:host "localhost" :port port :db dbname :user "postgres" :password "pw"}
                *container* name]
        (f)))))

(defn exec-sql!
  "Runs SQL against the namespace's database through the container's psql."
  [sql]
  (exec-in! *container* (:db *db*) sql))
