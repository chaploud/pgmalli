(ns pgmalli-next.driver-types-test
  "as-read and query-schema against the rows next.jdbc builds from PostgreSQL's JDBC driver: the
   rows of matrix.types (pgmalli-next.oracle/matrix-schema!: every type pgmalli renders, as itself, an
   array, a domain, an array of the domain), read through pgmalli-next.jdbc/builder. JVM only (babashka has
   no java.sql), so bb test does not list it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [next.jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.oracle :as oracle]
            [pgmalli-next.test-db :refer [builder generated *db* datasource exec-sql! with-postgres]]))

(use-fixtures :once with-postgres
  (fn [f]
    (when *db*
      (with-open [con (jdbc/get-connection (datasource))] (oracle/matrix-schema! con))
      ;; beside the row of samples, one of NULLs (a domain's default where it refuses NULL) and
      ;; two of NaN and infinities
      (exec-sql! "CREATE TYPE matrix.mood AS ENUM ('a', 'b');
                  ALTER TABLE matrix.types ADD e matrix.mood;
                  UPDATE matrix.types SET e = 'a';
                  INSERT INTO matrix.types DEFAULT VALUES;
                  INSERT INTO matrix.types (c_numeric, c_real, c_double_precision) VALUES ('NaN', 'NaN', 'Infinity'), ('Infinity', 'Infinity', '-Infinity');
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

(deftest as-read-is-what-next-jdbc-builds
  (when *db*
    (with-open [con (jdbc/get-connection (datasource))]
      (doseq [:let [registry (pgmalli/registry (generated {:db *db*} "matrix"))]
              table [:types :v]
              :let [read (pgmalli/as-read registry (keyword "pg.matrix" (name table)))
                    q {:select (map first (filter vector? (rest read)))
                       :from [(keyword (str "matrix." (name table)))] :order-by [:id]}
                    rows (jdbc/execute! con (sql/format q) {:builder-fn (builder registry)})
                    opts {:schema "matrix"}]]
        (testing table
          ;; every column of the first row holds a value: a column read as nil would pass its [:maybe]
          (is (= [] (keep (fn [[k v]] (when (nil? v) k)) (first rows))))
          (is (= [] (vec (distinct (disagreements registry [:sequential read] rows)))))
          (is (= [] (vec (distinct (disagreements registry (last (m/children (m/schema (h/query-schema registry [] q opts) {:registry registry}))) rows))))))))))

(deftest a-value-read-is-written-back-where-postgresql-takes-it
  (when *db*
    (let [ds (datasource)
          registry (pgmalli/registry (generated {:db *db*} "matrix"))
          o {:schema "matrix"}
          values (m/schema (h/values-schema registry :types o) {:registry registry})
          set (m/schema (h/set-schema registry :types o) {:registry registry})]
      (with-open [con (jdbc/get-connection ds)]
        (.setAutoCommit con false)
        (let [;; as the one reading reads it, sent as the one sending sends it
              cols (map first (filter vector? (rest (pgmalli/as-read registry :pg.matrix/types))))
              row (jdbc/execute-one! con (sql/format {:select cols :from [:matrix.types] :where [:= :id 1]}) {:builder-fn (builder registry)})
              taken? (fn [k v] (with-open [ps (.prepareStatement con (str "UPDATE matrix.types SET " (name k) " = ? WHERE id = 2"))]
                                 (try (prepare/set-parameter v ps 1) (.executeUpdate ps) true
                                      (catch java.sql.SQLException e (oracle/refused e) false)
                                      ;; a vector of PGobjects (a citext[]'s) has no JSON
                                      (catch Exception _ false)
                                      (finally (.rollback con)))))
              {taken true not-taken false} (group-by #(taken? (key %) (val %)) (dissoc row :id))]
          ;; an enum's label reads as a keyword, a json document as a map, which HoneySQL writes in :values
          ;; and :set as a column and a subquery: [:lift v]
          (is (= [] (vec (for [[k v] taken
                               :let [v (if (or (keyword? v) (map? v)) [:lift v] v)]
                               :when (not (and (m/validate set {k v}) (m/validate values (assoc (select-keys row [:id]) k v))))]
                           [k (some-> v class .getName)]))))
          (let [array? #(re-find #"^d?a_" (name %))]
            ;; bit(1) reads as a Boolean, which goes back as no bit
            (is (= [:c_bit :d_bit] (sort (remove array? (keys not-taken)))) "the values PostgreSQL does not take back")
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
    (let [registry (pgmalli/registry (generated {:db *db*} "matrix"))
          ok? #(m/validate %1 %2 {:registry registry})]
      (doseq [[ds o vs] [[(datasource) {:schema "matrix"} ["a" :a]]]
              v vs
              [sql valid?] [["UPDATE matrix.types SET e = ? WHERE id = 2" #(ok? (:v (:args (h/analyze registry {:update :types :set {:e :?v} :where [:= :id 2]} o))) %)]
                            ["UPDATE matrix.types SET e = ? WHERE id = 2" #(ok? (h/set-schema registry :types o) {:e (if (keyword? %) [:lift %] %)})]
                            ["SELECT id FROM matrix.types WHERE e = ?" #(ok? (:v (:args (h/analyze registry {:select [:id] :from [:types] :where [:= :e :?v]} o))) %)]]]
        (testing (str sql " " (pr-str o) " " (class v))
          (is (= (taken? ds sql v) (valid? v))))))))

(deftest a-json-column-takes-a-pgobject-whatever-is-sent-as-json
  (when *db*
    (let [registry (pgmalli/registry (generated {:db *db*} "matrix"))
          v (doto (org.postgresql.util.PGobject.) (.setType "jsonb") (.setValue "{}"))]
      (is (every? #(taken? (datasource) (str "UPDATE matrix.types SET " % " = ? WHERE id = 2") v) ["c_jsonb" "c_json"]))
      (doseq [o [{:schema "matrix"}]]
        (is (m/validate (h/set-schema registry :types o) {:c_jsonb v :c_json v} {:registry registry}))
        (is (m/validate (:v (:args (h/analyze registry {:update :types :set {:c_jsonb :?v} :where [:= :id 2]} o))) v {:registry registry}))))))


(deftest a-schema-read-failing-over-jdbc-says-what-pgmalli-needs
  (when *db*
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         ((requiring-resolve 'pgmalli-next.impl.ir-jdbc/out) {:datasource (datasource)} "SELECT no_such_column FROM pg_class")))]
      (is (= :pgmalli.error/fault (:pgmalli/error (ex-data e))))
      (is (str/includes? (ex-message e) ((requiring-resolve 'pgmalli-next.impl.ir/needs)))))))

(deftest a-builder-of-the-generated-data-reads-enums-as-of-its-registry
  (let [b (requiring-resolve 'pgmalli-next.jdbc/builder)
        data @(requiring-resolve 'pgmalli-next.sample/sample-data)]
    (is (seq (:pgmalli-next.jdbc/builder (meta (b data)))))
    (is (= (:pgmalli-next.jdbc/builder (meta (b (pgmalli/registry data)))) (:pgmalli-next.jdbc/builder (meta (b data)))))))
