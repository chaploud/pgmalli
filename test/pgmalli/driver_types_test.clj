(ns pgmalli.driver-types-test
  "as-read and query-schema against the rows next.jdbc builds from PostgreSQL's JDBC driver: the
   rows of matrix.types (pgmalli.oracle/matrix-schema!: every type pgmalli renders, as itself, an
   array, a domain, an array of the domain), read through next.jdbc's as-unqualified-maps under
   the one reading (docs/reading-rows.md, which pgmalli.test-db installs). JVM only (babashka has
   no java.sql), so bb test does not list it."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as rs]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            [pgmalli.impl.files :as files]
            [pgmalli.oracle :as oracle]
            [pgmalli.test-db :refer [*db* datasource exec-sql! with-postgres]]))

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

(defn- disagreements
  "[column class value] of each column of rows schema, of a sequence of rows, refuses."
  [registry schema rows]
  (for [row rows
        e (:errors (m/explain schema rows {:registry registry}))
        :let [[_ col] (:in e) v (get-in rows (:in e))]
        :when (= row (nth rows (first (:in e))))]
    [col (some-> v class .getName) v]))

(def ^:private unreadable
  "The columns whose arrays pgjdbc's getArray cannot read (docs/reading-rows.md#the-one-reading-and-sending)."
  #{:a_money :a_bit_4})

(deftest as-read-is-what-next-jdbc-builds
  (when *db*
    (with-open [con (jdbc/get-connection (datasource))]
      (doseq [:let [registry (pgmalli/registry (files/from-database {:db *db*} "matrix"))]
              table [:types :v]
              :let [read (into [] (remove #(and (vector? %) (unreadable (first %))))
                               (pgmalli/as-read registry (keyword "pg.matrix" (name table)) {:schema "matrix"}))
                    q {:select (map first (filter vector? (rest read)))
                       :from [(keyword (str "matrix." (name table)))] :order-by [:id]}
                    rows (jdbc/execute! con (sql/format q) {:builder-fn rs/as-unqualified-maps})
                    opts {:schema "matrix"}]]
        (testing table
          (is (= [] (vec (distinct (disagreements registry [:sequential read] rows)))))
          (is (= [] (vec (distinct (disagreements registry (last (m/children (m/schema (h/query-schema registry [] q opts) {:registry registry}))) rows))))))))))

(deftest a-value-read-is-written-back-where-postgresql-takes-it
  (when *db*
    (let [ds (datasource)
          registry (pgmalli/registry (files/from-database {:db *db*} "matrix"))
          o {:schema "matrix"}
          values (m/schema (h/values-schema registry :types o) {:registry registry})
          set (m/schema (h/set-schema registry :types o) {:registry registry})]
      (with-open [con (jdbc/get-connection ds)]
        (.setAutoCommit con false)
        (let [;; as the one reading reads it, sent as the one sending sends it
              cols (remove unreadable (map first (filter vector? (rest (pgmalli/as-read registry :pg.matrix/types o)))))
              row (jdbc/execute-one! con (sql/format {:select cols :from [:matrix.types] :where [:= :id 1]}) {:builder-fn rs/as-unqualified-maps})
              taken? (fn [k v] (with-open [ps (.prepareStatement con (str "UPDATE matrix.types SET " (name k) " = ? WHERE id = 2"))]
                                 (try (prepare/set-parameter v ps 1) (.executeUpdate ps) true
                                      (catch java.sql.SQLException e (oracle/refused e) false)
                                      ;; a vector of PGobjects (a citext[]'s) has no JSON
                                      (catch Exception _ false)
                                      (finally (.rollback con)))))
              {taken true not-taken false} (group-by #(taken? (key %) (val %)) (dissoc row :id))]
          (is (= [] (vec (for [[k v] taken
                               :when (not (and (m/validate set {k v}) (m/validate values (assoc (select-keys row [:id]) k v))))]
                           [k (some-> v class .getName)]))))
          (let [array? #(re-find #"^d?a_" (name %))]
            ;; bit(1) reads as a Boolean, money as a Double, an enum as a String: none goes back as its type
            (is (= [:c_bit :c_money :d_bit :d_money :e] (sort (remove array? (keys not-taken)))) "the values PostgreSQL does not take back")
            ;; an array reads as a vector, which goes as jsonb
            (is (= (sort (filter array? (keys row))) (sort (filter array? (keys not-taken)))) "no array goes back")))))))

(defn- taken?
  "Whether PostgreSQL takes v as sql's parameter, run and rolled back."
  [ds sql v]
  (with-open [con (jdbc/get-connection ds)]
    (.setAutoCommit con false)
    (try (jdbc/execute! con [sql v]) true
         (catch java.sql.SQLException e (oracle/refused e) false)
         (finally (.rollback con)))))

(deftest an-enum-label-goes-as-a-keyword-not-as-a-string
  (when *db*
    (let [registry (pgmalli/registry (files/from-database {:db *db*} "matrix"))
          ok? #(m/validate %1 %2 {:registry registry})]
      (doseq [[ds o vs] [[(datasource) {:schema "matrix"} ["a" :a]]]
              v vs
              [sql valid?] [["UPDATE matrix.types SET e = ? WHERE id = 2" #(ok? (:v (h/arg-types registry {:update :types :set {:e :?v} :where [:= :id 2]} o)) %)]
                            ["UPDATE matrix.types SET e = ? WHERE id = 2" #(ok? (h/set-schema registry :types o) {:e (if (keyword? %) [:lift %] %)})]
                            ["SELECT id FROM matrix.types WHERE e = ?" #(ok? (:v (h/arg-types registry {:select [:id] :from [:types] :where [:= :e :?v]} o)) %)]]]
        (testing (str sql " " (pr-str o) " " (class v))
          (is (= (taken? ds sql v) (valid? v))))))))

(deftest a-json-column-takes-a-pgobject-whatever-is-sent-as-json
  (when *db*
    (let [registry (pgmalli/registry (files/from-database {:db *db*} "matrix"))
          v (doto (org.postgresql.util.PGobject.) (.setType "jsonb") (.setValue "{}"))]
      (is (every? #(taken? (datasource) (str "UPDATE matrix.types SET " % " = ? WHERE id = 2") v) ["c_jsonb" "c_json"]))
      (doseq [o [{:schema "matrix"}]]
        (is (m/validate (h/set-schema registry :types o) {:c_jsonb v :c_json v} {:registry registry}))
        (is (m/validate (:v (h/arg-types registry {:update :types :set {:c_jsonb :?v} :where [:= :id 2]} o)) v {:registry registry}))))))

