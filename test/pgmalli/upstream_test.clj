(ns pgmalli.upstream-test
  "Lists pgmalli keeps of PostgreSQL's types and next.jdbc's builders, against the ones running.
   JVM only (next.jdbc), so bb test does not list it."
  (:require [babashka.process :as p]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            next.jdbc.optional
            next.jdbc.result-set
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.render :as render]
            [pgmalli.impl.shape :as shape]
            [pgmalli.test-db :as test-db :refer [*container* *db*]]))

(use-fixtures :once test-db/with-postgres)

(def ^:private rendered-any
  "pg_catalog types pgmalli renders :any with no literals to generate: the planner's and the
   catalog's own, never a table column's in practice."
  #{"aclitem" "gtsvector" "pg_brin_bloom_summary" "pg_brin_minmax_multi_summary" "pg_dependencies"
    "pg_mcv_list" "pg_ndistinct" "pg_node_tree" "refcursor"})

(defn- psql-lines [sql]
  (str/split-lines (:out (p/sh ["docker" "exec" *container* "psql" "-X" "-tA" "-U" "postgres" "-d" (:db *db*) "-c" sql]))))

(deftest every-postgresql-type-is-rendered-or-opaque
  (when *db*
    (let [version (first (psql-lines "SHOW server_version"))
          ;; arrays are rendered from their element type
          types (psql-lines "SELECT format_type(oid, NULL) FROM pg_type WHERE typtype IN ('b', 'r', 'm')
                             AND typnamespace = 'pg_catalog'::regnamespace AND NOT (typelem <> 0 AND typlen = -1)")
          known (set/union (set (keys @#'render/base-types)) shape/opaque-types shape/json-types rendered-any)]
      (is (< 50 (count types)))
      (doseq [t (sort (remove known types))]
        (is (nil? t) (str "PostgreSQL " version " has type " t " that pgmalli neither renders nor treats as opaque: add it to "
                          "base-types in src/pgmalli/impl/render.clj or opaque-literals in src/pgmalli/impl/shape.clj"))))))

(deftest every-nextjdbc-map-builder-is-known
  ;; as-modified-maps and the like key rows by functions of the caller's
  (let [next-jdbc (str "next.jdbc " (test-db/version "next.jdbc"))
        theirs (set (for [n ['next.jdbc.result-set 'next.jdbc.optional]
                          s (keys (ns-publics n))
                          :when (and (re-matches #"as-.*maps" (name s)) (not (str/includes? (name s) "modified")))]
                      (symbol (name n) (name s))))
        ours (set (keys @#'jdbc/builders))]
    (doseq [b (sort (set/difference theirs ours))]
      (is (nil? b) (str next-jdbc " has builder " b " that pgmalli does not know: add how it keys a row to builders in src/pgmalli/impl/jdbc.clj")))
    (doseq [b (sort (set/difference ours theirs))]
      (is (nil? b) (str next-jdbc " no longer has builder " b ": remove it from builders in src/pgmalli/impl/jdbc.clj")))))
