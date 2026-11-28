(ns pgmalli.driver-types-test
  "as-read and query-schema against the rows next.jdbc builds from PostgreSQL's JDBC driver: the
   rows of matrix.types (pgmalli.oracle/matrix-schema!: every type pgmalli renders, as itself, an
   array, a domain, an array of the domain), read through pgmalli.jdbc/builder. JVM only (babashka has
   no java.sql), so bb test does not list it."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [honey.sql :as sql]
            [malli.core :as m]
            [pgmalli.test-jdbc :as jdbc]
            [next.jdbc.prepare :as prepare]
            [pgmalli.core :as pgmalli]
            [pgmalli.honeysql :as h]
            pgmalli.jdbc
            [pgmalli.oracle :as oracle]
            [pgmalli.test-db :refer [builder generated *db* datasource exec-sql! jdbcUrl with-postgres]]))

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
                                 (try (prepare/set-parameter (second ((requiring-resolve 'pgmalli.jdbc/sent) ["" v])) ps 1) (.executeUpdate ps) true
                                      (catch java.sql.SQLException e (oracle/refused e) false)
                                      ;; a vector of PGobjects (a citext[]'s) has no JSON
                                      (catch Exception _ false)
                                      (finally (.rollback con)))))
              {taken true not-taken false} (group-by #(taken? (key %) (val %)) (dissoc row :id))]
          ;; an enum's label reads as a keyword, a json document as a map, an array as a vector, which
          ;; HoneySQL writes in :values and :set as a column, a subquery and a row: [:lift v]
          (is (= [] (vec (for [[k v] taken
                               :let [v (if (or (keyword? v) (map? v) (vector? v)) [:lift v] v)]
                               :when (not (and (m/validate set {k v}) (m/validate values (assoc (select-keys row [:id]) k v))))]
                           [k (some-> v class .getName)]))))
          (let [array? #(re-find #"^d?a_" (name %))]
            ;; bit(1) reads as a Boolean, which goes back as no bit
            (is (= [:c_bit :d_bit] (sort (remove array? (keys not-taken)))) "the values PostgreSQL does not take back")
            ;; bit(1)'s elements read as Booleans, whose text bit's input reads not; xml's as SQLXMLs, of no text sent
            (is (= [:a_bit :a_xml] (sort (filter array? (keys not-taken)))) "an array goes back as the vector it reads as")))))))

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


(defn- read-array
  "What read (pgmalli.jdbc's read-array or read-elements) reads of the array sql selects, on con,
   its statement's prepareThreshold threshold."
  [read con sql threshold enums]
  (with-open [ps (.prepareStatement con (str "SELECT " sql))]
    (.setPrepareThreshold (.unwrap ps org.postgresql.PGStatement) threshold)
    (with-open [rs (.executeQuery ps)]
      (.next rs)
      (read (.getObject rs 1) enums "a"))))

(deftest an-array-read-through-getarray-is-what-its-result-set-reads
  (when *db*
    (let [arrays ["'{1,NULL,-2147483648}'::int4[]" "'{{1,2},{3,4}}'::int4[]" "'{{{1,2}},{{3,NULL}}}'::int4[]" "'[2:3]={1,2}'::int4[]" "'{}'::int4[]"
                  "'{{{9223372036854775807,NULL}},{{1,2}}}'::int8[]"
                  "'{NaN,Infinity,-Infinity,1.5,NULL}'::float4[]" "'{NaN,Infinity,-Infinity,1e300,NULL}'::float8[]"
                  "'{t,f,NULL}'::bool[]"
                  "'{\"a,b\",\"\",NULL,\"NULL\",\"x\\\"y\"}'::text[]" "'{{a,b},{c,NULL}}'::text[]"
                  "'{ab,NULL}'::varchar(5)[]" "'{a,\"b  \",NULL}'::char(3)[]"
                  "'{a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11,NULL}'::uuid[]"]
          classes (fn classes [v] (if (vector? v) (mapv classes v) (some-> v class .getName)))]
      ;; pgjdbc reads an array as text, or as binary where prepareThreshold is -1 and the array type
      ;; is among its binary ones, which bool[] and uuid[] are not but by binaryTransferEnable; a
      ;; binary bpchar[] it reads neither way (getArray is unimplemented, getResultSet overflows)
      (with-open [con (jdbc/get-connection {:jdbcUrl (str (jdbcUrl) "?binaryTransferEnable=1000,2951")
                                            :user (:user *db*) :password (:password *db*)})]
        (doseq [threshold [0 -1] a arrays]
          (testing (str a " " threshold)
            (let [read (read-array #'pgmalli.jdbc/read-array con a threshold #{})
                  elements (read-array #'pgmalli.jdbc/read-elements con a threshold #{})]
              ;; NaN is no NaN's =
              (is (= (pr-str elements) (pr-str read)))
              (is (= (classes elements) (classes read))))))
        (is (= @#'pgmalli.jdbc/decoded-element-types
               (set (for [a arrays]
                      (with-open [st (.createStatement con) rs (.executeQuery st (str "SELECT " a))]
                        (.next rs)
                        (.getBaseTypeName ^java.sql.Array (.getObject rs 1))))))
            "each element type read through getArray is among the arrays")))))

(deftest an-enum-pgjdbc-names-as-a-type-getarray-decodes-reads-as-keywords
  (when *db*
    (exec-sql! "CREATE SCHEMA e; CREATE TYPE e.int4 AS ENUM ('a'); CREATE TYPE e.uuid AS ENUM ('x');")
    (with-open [con (jdbc/get-connection (datasource))]
      (jdbc/execute! con ["SET search_path TO e"])
      (doseq [threshold [0 -1]]
        (is (= [:a nil] (read-array #'pgmalli.jdbc/read-array con "'{a,NULL}'::e.int4[]" threshold #{"int4"})))
        (is (= [:x] (read-array #'pgmalli.jdbc/read-array con "'{x}'::e.uuid[]" threshold #{"uuid"})))))))

(deftest a-schema-read-failing-over-jdbc-says-what-pgmalli-needs
  (when *db*
    (let [e (is (thrown? clojure.lang.ExceptionInfo
                         ((requiring-resolve 'pgmalli.impl.ir-jdbc/out) {:datasource (datasource)} "SELECT no_such_column FROM pg_class")))]
      (is (= :pgmalli.error/fault (:pgmalli/error (ex-data e))))
      (is (str/includes? (ex-message e) ((requiring-resolve 'pgmalli.impl.ir/needs)))))))

(deftest a-builder-of-the-generated-data-reads-enums-as-of-its-registry
  (let [b (requiring-resolve 'pgmalli.jdbc/builder)
        data @(requiring-resolve 'pgmalli.sample/sample-data)]
    (is (seq (:pgmalli.jdbc/builder (meta (b data)))))
    (is (= (:pgmalli.jdbc/builder (meta (b (pgmalli/registry data)))) (:pgmalli.jdbc/builder (meta (b data)))))))
