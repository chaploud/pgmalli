(ns pgmalli.driver-types-test
  "as-read against the rows next.jdbc builds from PostgreSQL's JDBC driver: one row of every
   type pgmalli renders, read through every builder read-options knows, under every :time.
   JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            next.jdbc.date-time
            next.jdbc.optional
            next.jdbc.result-set
            [pgmalli.core :as pgmalli]
            [pgmalli.impl.files :as files]
            pgmalli.impl.jdbc
            [pgmalli.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once with-postgres)

(def ^:private schema-sql
  "CREATE EXTENSION citext;
   CREATE TYPE mood AS ENUM ('a', 'b');
   CREATE DOMAIN posint AS int CHECK (VALUE > 0);
   CREATE TABLE t (id int PRIMARY KEY, i2 smallint, i8 bigint, num numeric(10,2), nn numeric, r real, d double precision,
     b1 bit(1), b8 bit(8), vb varbit, ct citext, bp char(3), vc varchar(5), tx text, u uuid,
     dt date, tm time, ttz timetz, ts timestamp, tstz timestamptz, iv interval, js json, jb jsonb,
     arr int[], tarr text[], e mood, dom posint, by bytea, o oid, bo boolean);
   CREATE VIEW v AS SELECT id, ct, r FROM t;
   INSERT INTO t VALUES
     (1, 1, 2, 3.45, 1.5, 1.5, 2.5, B'1', B'10101010', B'101', 'Ab', 'ab', 'abc', 'x', gen_random_uuid(),
      '2026-01-01', '12:00', '12:00+09', '2026-01-01 00:00', '2026-01-01 00:00+00', '1 day', '{\"a\":1}', '{\"a\":1}',
      '{1,2}', '{a}', 'a', 5, '\\x00', 26, true),
     (2, NULL, NULL, NULL, 'NaN', 'NaN', 'Infinity', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
      NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);")

(def ^:private time-readers
  {:inst next.jdbc.date-time/read-as-default
   :instant next.jdbc.date-time/read-as-instant
   :local next.jdbc.date-time/read-as-local})

(defn- disagreements
  "[column class value] of each column of rows the schema refuses."
  [registry schema rows]
  (for [row rows
        e (:errors (m/explain [:sequential schema] rows {:registry registry}))
        :let [[_ col] (:in e) v (get-in rows (:in e))]
        :when (= row (nth rows (first (:in e))))]
    [col (some-> v class .getName) v]))

(deftest as-read-is-what-next-jdbc-builds
  (when *db*
    (exec-sql! schema-sql)
    (let [{:keys [host port db user password]} *db*
          ds (jdbc/get-datasource {:jdbcUrl (str "jdbc:postgresql://" host ":" port "/" db) :user user :password password})
          registry (pgmalli/registry (files/from-database {:db *db*} "public"))]
      (try
        (doseq [[time read-as] time-readers
                builder (keys @#'pgmalli.impl.jdbc/builders)
                table [:pg.public/t :pg.public/v]
                :let [_ (read-as)
                      rows (jdbc/execute! ds [(str "SELECT * FROM " (name table) " ORDER BY id")]
                                          {:builder-fn @(requiring-resolve builder)})
                      schema (pgmalli/as-read registry table (assoc (pgmalli/read-options builder) :time time))]]
          (testing (str builder " :time " time " " table)
            (is (= [] (vec (distinct (disagreements registry schema rows)))))))
        (finally (next.jdbc.date-time/read-as-default))))))
