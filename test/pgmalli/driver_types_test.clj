(ns pgmalli.driver-types-test
  "as-read and query-schema against the rows next.jdbc builds from PostgreSQL's JDBC driver: the
   rows of matrix.types (pgmalli.oracle/matrix-schema!: every type pgmalli renders, as itself, an
   array, a domain, an array of the domain), read through every next.jdbc map builder pgmalli
   knows, under every :time. JVM only (babashka has no java.sql), so bb test does not list it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            next.jdbc.date-time
            next.jdbc.optional
            next.jdbc.result-set
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.files :as files]
            pgmalli.impl.jdbc
            [pgmalli.oracle :as oracle]
            [pgmalli.test-db :refer [*db* datasource exec-sql! jdbc-url with-postgres]]))

(use-fixtures :once with-postgres
  (fn [f]
    (when *db*
      (with-open [con (jdbc/get-connection (datasource))] (oracle/matrix-schema! con))
      ;; beside the row of samples, one of NULLs (a domain's default where it refuses NULL) and
      ;; one of NaN and Infinity
      (exec-sql! "CREATE TYPE matrix.mood AS ENUM ('a', 'b');
                  ALTER TABLE matrix.types ADD e matrix.mood;
                  UPDATE matrix.types SET e = 'a';
                  INSERT INTO matrix.types DEFAULT VALUES;
                  INSERT INTO matrix.types (c_numeric, c_real, c_double_precision) VALUES ('NaN', 'NaN', 'Infinity');
                  CREATE VIEW matrix.v AS SELECT * FROM matrix.types;"))
    (f)))

(def ^:private time-readers
  {:inst next.jdbc.date-time/read-as-default
   :instant next.jdbc.date-time/read-as-instant
   :local next.jdbc.date-time/read-as-local})

(defn- disagreements
  "[column class value] of each column of rows schema, of a sequence of rows, refuses."
  [registry schema rows]
  (for [row rows
        e (:errors (m/explain schema rows {:registry registry}))
        :let [[_ col] (:in e) v (get-in rows (:in e))]
        :when (= row (nth rows (first (:in e))))]
    [col (some-> v class .getName) v]))

(deftest as-read-is-what-next-jdbc-builds
  (when *db*
    (with-open [con (jdbc/get-connection (datasource))]
      (try
        (doseq [:let [registry (pgmalli/registry (files/from-database {:db *db*} "matrix"))]
                [time read-as] time-readers
                builder (keys @#'pgmalli.impl.jdbc/builders)
                table [:types :v]
                :let [_ (read-as)
                      jdbc-opts (cond-> {:builder-fn @(requiring-resolve builder)}
                                  (str/includes? (name builder) "modified") (assoc :label-fn str/upper-case :qualifier-fn #(str "t_" %)))
                      q {:select [:*] :from [(keyword (str "matrix." (name table)))] :order-by [:id]}
                      rows (jdbc/execute! con (sql/format q) jdbc-opts)
                      opts {:jdbc jdbc-opts :time time :schema "matrix"}]]
          (testing (str builder " :time " time " " table)
            (is (= [] (vec (distinct (disagreements registry [:sequential (pgmalli/as-read registry (keyword "pg.matrix" (name table)) opts)] rows)))))
            (is (= [] (vec (distinct (disagreements registry (last (m/children (m/schema (h/query-schema registry [] q opts) {:registry registry}))) rows)))))))
        (finally (next.jdbc.date-time/read-as-default))))))

(deftest a-value-read-is-written-back-where-postgresql-takes-it
  (when *db*
    (let [ds (datasource)
          registry (pgmalli/registry (files/from-database {:db *db*} "matrix"))
          o {:jdbc {:builder-fn next.jdbc.result-set/as-unqualified-maps} :schema "matrix"}
          row (jdbc/execute-one! ds ["SELECT * FROM matrix.types WHERE id = 1"] (:jdbc o))
          values (m/schema (h/values-schema registry :types o) {:registry registry})
          set (m/schema (h/set-schema registry :types o) {:registry registry})]
      (with-open [con (jdbc/get-connection ds)]
        (.setAutoCommit con false)
        (let [;; another test may load next.jdbc.date-time, which cannot send a java.sql.Time
              taken? (fn [k v] (try (jdbc/execute! con [(str "UPDATE matrix.types SET " (name k) " = ? WHERE id = 2") v]) true
                                    (catch Exception _ false)
                                    (finally (.rollback con))))]
          (is (= [] (vec (for [[k v] (dissoc row :id)
                               :when (and (taken? k v)
                                          (not (and (m/validate set {k v}) (m/validate values (assoc (select-keys row [:id]) k v)))))]
                           [k (some-> v class .getName)])))))))))

(defn- taken?
  "Whether PostgreSQL takes v as sql's parameter, run and rolled back."
  [ds sql v]
  (with-open [con (jdbc/get-connection ds)]
    (.setAutoCommit con false)
    (try (jdbc/execute! con [sql v]) true
         (catch Exception _ false)
         (finally (.rollback con)))))

(deftest an-enum-label-goes-as-a-string-only-where-pgjdbc-sends-it-untyped
  (when *db*
    (let [registry (pgmalli/registry (files/from-database {:db *db*} "matrix"))
          unspecified (jdbc/get-datasource {:jdbcUrl (str (jdbc-url) "?stringtype=unspecified") :user (:user *db*) :password (:password *db*)})
          typed (doto (org.postgresql.util.PGobject.) (.setType "matrix.mood") (.setValue "a"))
          ok? #(m/validate %1 %2 {:registry registry})]
      (doseq [[ds o vs] [[(datasource) {:schema "matrix"} ["a" typed]] [unspecified {:schema "matrix" :send {String :unknown}} ["a"]]]
              v vs
              [sql valid?] [["UPDATE matrix.types SET e = ? WHERE id = 2" #(ok? (:v (h/arg-types registry {:update :types :set {:e :?v} :where [:= :id 2]} o)) %)]
                            ["UPDATE matrix.types SET e = ? WHERE id = 2" #(ok? (h/set-schema registry :types o) {:e %})]
                            ["SELECT id FROM matrix.types WHERE e = ?" #(ok? (:v (h/arg-types registry {:select [:id] :from [:types] :where [:= :e :?v]} o)) %)]]]
        (testing (str sql " " (pr-str o) " " (class v))
          (is (= (taken? ds sql v) (valid? v))))))))

(deftest a-json-column-takes-a-pgobject-whatever-is-sent-as-json
  (when *db*
    (let [registry (pgmalli/registry (files/from-database {:db *db*} "matrix"))
          v (doto (org.postgresql.util.PGobject.) (.setType "jsonb") (.setValue "{}"))]
      (is (every? #(taken? (datasource) (str "UPDATE matrix.types SET " % " = ? WHERE id = 2") v) ["c_jsonb" "c_json"]))
      (doseq [o [{:schema "matrix"} {:schema "matrix" :send {clojure.lang.IPersistentMap "jsonb"}}]]
        (is (m/validate (h/set-schema registry :types o) {:c_jsonb v :c_json v} {:registry registry}))
        (is (m/validate (:v (h/arg-types registry {:update :types :set {:c_jsonb :?v} :where [:= :id 2]} o)) v {:registry registry}))))))

