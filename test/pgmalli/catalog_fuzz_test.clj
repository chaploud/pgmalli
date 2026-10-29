(ns pgmalli.catalog-fuzz-test
  "Random schemas through the whole pipeline against PostgreSQL itself: a test.check generator
   makes a schema model (enums, domain chains, many column types, CHECKs of the patterns
   pgmalli.impl.pattern matches, partitions, identity and generated columns, quoted names,
   views), rendered to DDL. For each schema the registry is built from the database, and:
     :rejected       an insert row the database refused
     :false-positive a row the database took that the insert schema rejects
     :read-back      a row read back that as-read rejects
     :rejected-dataset / :short  a generated dataset the database refused, or that came out short
   plus what pgmalli.pipeline finds in the catalog. JVM only (JDBC). PGMALLI_FUZZ_SEED changes
   the seed (default 42, fixed in CI), PGMALLI_FUZZ_SCHEMAS the number of schemas (default 30);
   PGMALLI_FUZZ_CASE runs one, as a failure's report says."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check.generators :as tcg]
            [honey.sql :as sql]
            [malli.core :as m]
            [malli.error :as me]
            [malli.generator :as mg]
            [next.jdbc :as jdbc]
            next.jdbc.date-time
            [next.jdbc.prepare :as prep]
            [next.jdbc.result-set :as rs]
            [pgmalli.core :as pgmalli]
            [pgmalli.data :as data]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.shape :as shape]
            [pgmalli.pipeline :as pipeline]
            [pgmalli.test-db :refer [*db* exec-sql! sql-literal with-postgres]]
            [pgmalli.test-seed :as ts])
  (:import (java.time Duration Instant LocalDate LocalDateTime LocalTime OffsetTime)))

(use-fixtures :once with-postgres)

;;; the model

(def ^:private types
  "Column types: their SQL and a few values (as the registry holds them), some outside what a
   CHECK or the type allows, so the database has something to refuse."
  {:integer {:sql "integer" :vals [0 1 2 5 -1 100 2147483647]}
   :smallint {:sql "smallint" :vals [0 1 5 -1 32767]}
   :bigint {:sql "bigint" :vals [0 1 5 9007199254740993]}
   :numeric {:sql "numeric" :vals [0M 1M 2M 5M 1.5M -3.25M 1.0M]}
   :numeric62 {:sql "numeric(6,2)" :vals [0M 1.25M 9999.99M -5M 2M]}
   :text {:sql "text" :vals ["" "a" "A" "abc" "Abc" " a " "it's" "bb" "ü" "é" "É" "a\n"]}
   :varchar5 {:sql "varchar(5)" :vals ["" "a" "abc" "abcdef" "Bb" "abcde   " "é"]}
   :char3 {:sql "char(3)" :vals ["a" "bb" "abc" "A" "bb  " "é"]}
   :boolean {:sql "boolean" :vals [true false]}
   :uuid {:sql "uuid" :vals [#uuid "7c9e6679-7425-40de-944b-e07fc1f90ae7" #uuid "16fd2706-8baf-433b-82eb-8c7fada847da"]}
   :date {:sql "date" :vals (mapv #(LocalDate/parse %) ["2025-01-01" "1999-12-31" "2025-06-15"])}
   :timestamp {:sql "timestamp" :vals (mapv #(LocalDateTime/parse %) ["2025-01-01T00:00" "2025-06-15T12:30:01"])}
   :timestamptz {:sql "timestamptz" :vals (mapv #(Instant/parse %) ["2025-01-01T00:00:00Z" "2025-06-15T12:30:00Z"])}
   :time {:sql "time" :vals (mapv #(LocalTime/parse %) ["00:00" "23:59:59"])}
   :timetz {:sql "timetz" :vals (mapv #(OffsetTime/parse %) ["10:00+09:00" "23:59:59Z" "00:00-05:00"])}
   :interval {:sql "interval" :vals (mapv #(Duration/parse %) ["PT1H" "PT0S" "PT26H3S"])}
   :jsonb {:sql "jsonb" :vals [{} {"a" 1} [1 2] [] "s" 1]}
   :json {:sql "json" :vals [{} {"a" "x"} [1] 2]}
   :bytea {:sql "bytea" :vals [(byte-array []) (byte-array [1 2 3])]}
   :inet {:sql "inet" :vals ["10.0.0.1" "::1" "192.168.0.0/24"]}
   :money {:sql "money" :vals ["1.50" "0.00"]}
   :point {:sql "point" :vals ["(1,2)" "(0,-1.5)"]}
   :xml {:sql "xml" :vals ["<a/>" "<a b=\"1\">x</a>"]}
   :bit3 {:sql "bit(3)" :vals ["101" "000" "11"]}
   :varbit4 {:sql "varbit(4)" :vals ["1" "0101" "11111"]}
   :int-arr {:sql "integer[]" :vals [[] [1 2] [5]]}
   :text-arr {:sql "text[]" :vals [[] ["a"] ["a" "bb"]]}})

(def ^:private kinds
  "How CHECKs and values treat a type."
  {:number #{:integer :smallint :bigint :numeric :numeric62}
   :string #{:text :varchar5 :char3}
   :array #{:int-arr :text-arr}})

(defn- kind [t] (some (fn [[k ts]] (when (ts t) k)) kinds))

(def ^:private checks
  "CHECK patterns by kind, $c the column (VALUE in a domain)."
  {:number ["$c IN (1, 2, 5)" "$c BETWEEN 0 AND 10" "$c > 0" "$c >= 1 AND $c <= 100" "$c <> 3" "$c NOT IN (1, 2)"
            "$c >= 0.5" "($c * 0.5)::integer <> 1"]
   :string ["$c IN ('a', 'bb')" "length($c) <= 3" "char_length($c) BETWEEN 1 AND 4" "length(btrim($c)) > 0" "$c <> ''"
            "$c ~ '^[a-z]+$'" "$c ~* '^A'" "$c LIKE 'a%'" "$c ILIKE 'A%'" "$c NOT IN ('x')"
            "$c ~ '^[[:alpha:]]+$'" "$c ~* '^é'" "$c ~ '^[A-Z]{2}'" "$c ~ '^\\w+$'"]
   :jsonb ["jsonb_typeof($c) = 'object'" "jsonb_typeof($c) = 'array'"]
   :array ["cardinality($c) <= 2" "array_length($c, 1) <= 2" "cardinality($c) > 0"]
   :bytea ["octet_length($c) <= 2"]
   :date ["$c >= '2000-01-01'"]
   :enum ["$c <> $l" "$c IN ($l)"]
   :bits ["length($c) = 3"]})

(defn- check-kind [t] (or (kind t) ({:jsonb :jsonb :bytea :bytea :date :date :varbit4 :bits} t)))

(def ^:private gen-column
  (tcg/hash-map :type (tcg/elements (concat (keys types) [:enum :enum :enum-arr :domain :domain :domain-arr]))
                :ref (tcg/choose 0 9)
                :not-null tcg/boolean
                :check (tcg/frequency [[3 (tcg/return nil)] [2 (tcg/choose 0 50)]])
                :when-present tcg/boolean
                :default (tcg/frequency [[5 (tcg/return false)] [1 (tcg/return true)]])))

(def ^:private gen-table
  (tcg/hash-map :columns (tcg/vector gen-column 1 6)
                :pk tcg/boolean
                :identity (tcg/elements [nil nil :always :default :serial])
                :generated tcg/boolean
                :table-check (tcg/elements [nil nil 0 1 2])
                :partition (tcg/frequency [[6 (tcg/return nil)] [1 (tcg/elements [:list :list-default :range :range-default :hash :range-identity])]])
                :fk tcg/boolean
                :view tcg/boolean
                :names (tcg/shuffle ["a" "b" "Col" "user" "from" "Mixed Col" "x_y" "c\"q" "Order"])))

(def ^:private gen-model
  (tcg/hash-map :enums (tcg/vector (tcg/fmap (comp vec distinct) (tcg/vector (tcg/elements ["a" "bb" "it's" "Ü" "b c" "x\"y" "UPPER"]) 1 4)) 0 2)
                :enum-names (tcg/shuffle ["e" "Mood" "Mixed Case" "a\"q"])
                :domains (tcg/vector (tcg/hash-map :base (tcg/elements [:integer :text :varchar5 :char3 :numeric :date :prev :prev])
                                                   :check (tcg/frequency [[1 (tcg/return nil)] [2 (tcg/choose 0 50)]])
                                                   :not-null (tcg/frequency [[4 (tcg/return false)] [1 (tcg/return true)]]))
                                     0 3)
                :domain-names (tcg/shuffle ["d" "Dom" "order" "D q"])
                :tables (tcg/vector gen-table 1 3)
                :table-names (tcg/shuffle ["t" "Order" "user" "Mixed T" "items" "select" "t\"q"])))

;;; SQL

(defn- q [s] (str "\"" (str/replace s "\"" "\"\"") "\""))

(defn- resolve-model
  "The model with its references resolved: domains on a type or an earlier domain, columns on
   types that exist, CHECKs picked from their kind's patterns."
  [{:keys [enums enum-names domains domain-names tables table-names]}]
  (let [enums (mapv (fn [labels n] {:name n :labels labels}) enums enum-names)
        domains (reduce (fn [ds [{:keys [base check not-null]} n]]
                          (let [base (if (= :prev base) (if (seq ds) {:domain (dec (count ds))} :text) base)
                                root (if (map? base) (:root (get ds (:domain base))) base)
                                k (check-kind root)]
                            (conj ds {:name n :base base :not-null not-null :root root
                                      :check (when (and check k) (str/replace (nth (checks k) (mod check (count (checks k)))) "$c" "VALUE"))})))
                        [] (map vector domains domain-names))]
    {:enums enums
     :domains domains
     :tables (vec (for [[i t] (map-indexed vector tables)]
                    (assoc t :name (nth table-names i)
                           :columns (vec (for [[j c] (map-indexed vector (:columns t))
                                               :let [ty (:type c)
                                                     ty (cond (and (#{:enum :enum-arr} ty) (empty? enums)) :text
                                                              (and (#{:domain :domain-arr} ty) (empty? domains)) :integer
                                                              :else ty)
                                                     ref (case ty (:enum :enum-arr) (mod (:ref c) (count enums)) (:domain :domain-arr) (mod (:ref c) (count domains)) nil)]]
                                           (assoc c :name (nth (:names t) j) :type ty :ref ref))))))}))

(defn- type-sql [schema model {:keys [type ref]}]
  (case type
    :enum (str (q schema) "." (q (get-in model [:enums ref :name])))
    :enum-arr (str (q schema) "." (q (get-in model [:enums ref :name])) "[]")
    :domain (str (q schema) "." (q (get-in model [:domains ref :name])))
    :domain-arr (str (q schema) "." (q (get-in model [:domains ref :name])) "[]")
    (get-in types [type :sql])))

(defn- column-check [model {:keys [type ref check when-present] cname :name}]
  (when check
    (let [k (case type :enum :enum :enum-arr :array :domain-arr :array (check-kind type))
          ;; <> the only label leaves a NOT NULL column no value
          ps (if (and (= :enum k) (= 1 (count (get-in model [:enums ref :labels])))) ["$c IN ($l)"] (get checks k))]
      (when ps
        (let [e (-> (nth ps (mod check (count ps)))
                    (str/replace "$c" (q cname))
                    (str/replace "$l" (sql-literal (first (get-in model [:enums ref :labels] ["a"])))))]
          (if when-present (str (q cname) " IS NULL OR (" e ")") e))))))

(defn- domain-root [model ref] (get-in model [:domains ref :root]))

(defn- default-sql [model {:keys [type ref]}]
  (let [t (if (= :domain type) (domain-root model ref) type)]
    (case t
      (:integer :smallint :bigint :numeric :numeric62) "1"
      (:text :varchar5 :char3) "'a'"
      :enum (sql-literal (first (get-in model [:enums ref :labels])))
      :boolean "true"
      nil)))

(defn- integer-columns [t]
  (filter #(#{:integer :smallint :bigint} (:type %)) (:columns t)))

(defn- table-check [t]
  (let [[a b] (map (comp q :name) (integer-columns t))]
    (when (and a b (:table-check t))
      (case (int (:table-check t))
        0 (str a " <= " b)
        1 (str a " > 5 OR " b " < 3")
        2 (str "(" a " = 1 AND " b " > 0) OR (" a " = 2 AND " b " < 0)")))))

(defn- partition-of [t]
  (let [p (:partition t)]
    (if (and (= :range-identity p) (not= :always (:identity t))) :range p)))

(defn- table-columns
  "The model's columns plus the ones a table's options add: pk, id, g, part, ref."
  [model t first-table]
  (let [p (partition-of t)
        pk? (and (:pk t) (not p))]
    (cond-> []
      pk? (conj {:name "pk" :sql "integer PRIMARY KEY" :type :integer :not-null true :pk true})
      (:identity t) (conj {:name "id" :type :bigint :not-null true :identity (:identity t)
                           :sql (case (:identity t) :always "bigint GENERATED ALWAYS AS IDENTITY" :default "bigint GENERATED BY DEFAULT AS IDENTITY" :serial "bigserial")})
      (and pk? (:generated t)) (conj {:name "g" :sql "integer GENERATED ALWAYS AS (pk * 2) STORED" :type :integer :generated true})
      (and p (not= :range-identity p)) (conj {:name "part" :sql "integer" :type :integer})
      (and (:fk t) first-table (not= t first-table) (:pk first-table) (not (partition-of first-table)))
      (conj {:name "ref" :sql (str "integer REFERENCES " (:qualified first-table) " (pk)") :type :integer})
      true (into (for [c (:columns t)]
                   (assoc c :sql (str (type-sql (:schema model) model c)
                                      (when (:not-null c) " NOT NULL")
                                      (when-let [d (when (:default c) (default-sql model c))] (str " DEFAULT " d))
                                      (when-let [e (column-check model c)] (str " CHECK (" e ")")))))))))

(defn- partitions-sql [t]
  (let [parent (:qualified t)
        child (fn [i] (str (q (:schema t)) "." (q (str (:name t) "_p" i))))
        of (fn [i bound] (str "CREATE TABLE " (child i) " PARTITION OF " parent " " bound ";\n"))]
    (case (partition-of t)
      nil ""
      :list (str (of 0 "FOR VALUES IN (1, 2)") (of 1 "FOR VALUES IN (3)"))
      (:list-default :range-default) (of 0 "DEFAULT")
      :range (str (of 0 "FOR VALUES FROM (MINVALUE) TO (10)") (of 1 "FOR VALUES FROM (10) TO (MAXVALUE)"))
      :hash (str (of 0 "FOR VALUES WITH (MODULUS 2, REMAINDER 0)") (of 1 "FOR VALUES WITH (MODULUS 2, REMAINDER 1)"))
      :range-identity (str (of 0 "FOR VALUES FROM (MINVALUE) TO (100)") (of 1 "FOR VALUES FROM (100) TO (MAXVALUE)")))))

(defn- with-tables
  "The resolved model with every table's qualified name and full column list."
  [schema model]
  (let [model (assoc model :schema schema)
        ts (mapv #(assoc % :schema schema :qualified (str (q schema) "." (q (:name %)))) (:tables model))
        first-table (first ts)]
    (assoc model :tables (mapv #(assoc % :all-columns (table-columns model % first-table)) ts))))

(defn- ddl [{:keys [schema enums domains tables] :as model}]
  (str "CREATE SCHEMA " (q schema) ";\n"
       (apply str (for [{:keys [name labels]} enums]
                    (str "CREATE TYPE " (q schema) "." (q name) " AS ENUM (" (str/join ", " (map sql-literal labels)) ");\n")))
       (apply str (for [{:keys [name base not-null check]} domains]
                    (str "CREATE DOMAIN " (q schema) "." (q name) " AS "
                         (if (map? base) (str (q schema) "." (q (get-in model [:domains (:domain base) :name]))) (get-in types [base :sql]))
                         (when not-null " NOT NULL") (when check (str " CHECK (" check ")")) ";\n")))
       (apply str (for [t tables]
                    (str "CREATE TABLE " (:qualified t) " ("
                         (str/join ", " (concat (for [c (:all-columns t)] (str (q (:name c)) " " (:sql c)))
                                                (when-let [c (table-check t)] [(str "CHECK (" c ")")])))
                         ")"
                         (case (partition-of t)
                           nil ""
                           (:list :list-default) " PARTITION BY LIST (part)"
                           (:range :range-default) " PARTITION BY RANGE (part)"
                           :hash " PARTITION BY HASH (part)"
                           :range-identity " PARTITION BY RANGE (id)")
                         ";\n" (partitions-sql t)
                         (when (:view t) (str "CREATE VIEW " (q schema) "." (q (str (:name t) "_v")) " AS SELECT * FROM " (:qualified t) ";\n")))))))

;;; values the database decides on

(defn- values-of [model {:keys [type ref]}]
  (case type
    :enum (get-in model [:enums ref :labels])
    :enum-arr (let [ls (get-in model [:enums ref :labels])] [[] [(first ls)] (vec (take 2 ls))])
    :domain (get-in types [(domain-root model ref) :vals])
    :domain-arr (let [vs (get-in types [(domain-root model ref) :vals])] [[] [(first vs)] (vec (take 2 vs))])
    (get-in types [type :vals])))

(defn- literal [column-type v]
  (cond (nil? v) "NULL"
        (#{"json" "jsonb"} column-type) (sql-literal (json/write v))
        (vector? v) (if (empty? v) "'{}'" (str "ARRAY[" (str/join ", " (map #(literal nil %) v)) "]::" column-type))
        (bytes? v) (sql-literal (str "\\x" (apply str (map #(format "%02x" (bit-and % 0xff)) v))))
        (or (number? v) (boolean? v)) (str v)
        :else (sql-literal v)))

(defn- loose-rows
  "Rows from the columns alone: each column a value its type may hold or not, NULL, or left out."
  [model t seed]
  (let [cs (remove #(or (:generated %) (= :always (:identity %))) (:all-columns t))
        gen (apply tcg/tuple (for [c cs]
                               (tcg/frequency [[1 (tcg/return ::omit)] [1 (tcg/return nil)]
                                               [6 (tcg/elements (or (when (:pk c) [1 2 3 4 5]) (values-of model c) [nil]))]])))]
    (for [vs (tcg/generate (tcg/vector gen 12) 30 seed)]
      (into {} (keep (fn [[c v]] (when-not (= ::omit v) [c v]))) (map vector cs vs)))))

(defn- insert-sql [model t row]
  (if (empty? row)
    (str "INSERT INTO " (:qualified t) " DEFAULT VALUES")
    (str "INSERT INTO " (:qualified t) " (" (str/join ", " (map (comp q :name) (keys row))) ") VALUES ("
         (str/join ", " (map (fn [[c v]] (literal (type-sql (:schema model) model c) v)) row)) ")")))

;;; running

(extend-protocol prep/SettableParameter
  ;; pgjdbc takes no Duration; an interval reads ISO 8601, as an untyped parameter
  Duration
  (set-parameter [v ^java.sql.PreparedStatement ps ^long i] (.setObject ps i (str v) java.sql.Types/OTHER)))

(defn- attempt!
  "nil when the statement went through (kept), else {:sqlstate :message} from the database (rolled
   back to a savepoint)."
  [^java.sql.Connection conn sql-params]
  (let [sp (.setSavepoint conn)]
    (try (jdbc/execute! conn sql-params) (.releaseSavepoint conn sp) nil
         (catch java.sql.SQLException e (.rollback conn sp) {:sqlstate (.getSQLState e) :message (ex-message e)}))))

(defn- try-insert!
  "attempt! for HoneySQL data; a statement that cannot be formatted is a finding too."
  [conn stmt]
  (try (attempt! conn (sql/format stmt {:quoted true}))
       (catch Exception e {:sqlstate "-" :message (str "format: " (ex-message e))})))

(def ^:private unique-violation "23505")

(defn- table-key [schema t] (shape/schema-key schema (:name t)))

(defn- row-key [c] (shape/ident-key (:name c)))

(defn- schema-findings
  "Every finding for one generated model, the schema created in the database first."
  [ds schema model seed]
  (let [ddl-error (try (exec-sql! (ddl model)) nil (catch Exception e (ex-message e)))]
    (if ddl-error
      [{:kind :ddl :message ddl-error}]
      (let [[data e1] (pipeline/try-step :generate #(files/from-database {:db *db*} schema))
            [reg e2] (when data (pipeline/try-step :load #(pgmalli/registry data)))
            opts {:registry reg}]
        (if-not reg
          (remove nil? [e1 e2])
          (concat
           (pipeline/catalog-findings data)
           (jdbc/with-transaction [tx ds {:rollback-only true}]
             (jdbc/execute! tx ["SET LOCAL session_replication_role = replica"])
             (doall
              (concat
               (for [[i t] (map-indexed vector (:tables model))
                     :let [ins (shape/derived-name (table-key schema t) "insert")
                           [rows err] (pipeline/try-step :insert-gen #(vec (mg/sample ins {:registry reg :size 10 :seed (+ seed i)})))]
                     f (if err
                         [(assoc err :table (:name t))]
                         (for [row rows
                               :let [refused (try-insert! tx (first (data/inserts reg {(str schema "." (:name t)) [row]})))]
                               :when (and refused (not= unique-violation (:sqlstate refused)))]
                           (merge {:kind :rejected :table (:name t) :row row} refused)))]
                 f)
               (for [[i t] (map-indexed vector (:tables model))
                     :let [ins (shape/derived-name (table-key schema t) "insert")]
                     row (loose-rows model t (+ seed 100 i))
                     :let [value (into {} (map (fn [[c v]] [(row-key c) v])) row)]
                     :when (and (nil? (attempt! tx [(insert-sql model t row)])) (not (m/validate ins value opts)))]
                 {:kind :false-positive :table (:name t) :row value :message (pr-str (me/humanize (m/explain ins value opts)))})
               (for [t (:tables model)
                     [n k] (cond-> [[(:name t) (table-key schema t)]] (:view t) (conj [(str (:name t) "_v") (shape/schema-key schema (str (:name t) "_v"))]))
                     :let [read (pgmalli/as-read reg k {:qualified? false})]
                     row (jdbc/execute! tx [(str "SELECT * FROM " (q schema) "." (q n))] {:builder-fn rs/as-unqualified-maps})
                     :when (not (m/validate read row opts))]
                 {:kind :read-back :table n :row row :message (pr-str (me/humanize (m/explain read row opts)))}))))
           (let [[dataset err] (pipeline/try-step :dataset #(tcg/generate (data/dataset-generator reg {:rows 3}) 30 seed))]
             (if err
               [err]
               (concat
                (pipeline/short-findings dataset)
                (jdbc/with-transaction [tx ds {:rollback-only true}]
                  (doall (for [stmt (data/inserts reg dataset)
                               :let [refused (try-insert! tx stmt)]
                               :when refused]
                           (merge {:kind :rejected-dataset :table (let [t (:insert-into stmt)] (name (if (vector? t) (last t) t)))} refused)))))))))))))


(defn- cases
  "The case indices to run: PGMALLI_FUZZ_CASE alone, or PGMALLI_FUZZ_SCHEMAS of them (default 30)."
  []
  (if-let [c (System/getenv "PGMALLI_FUZZ_CASE")]
    [(parse-long c)]
    (range (parse-long (or (System/getenv "PGMALLI_FUZZ_SCHEMAS") "30")))))

(defn findings
  "[{:case :ddl :findings}] for the cases with findings, case i built from seed + i."
  [seed cases]
  (let [ds (jdbc/get-datasource {:dbtype "postgresql" :host (:host *db*) :port (:port *db*) :dbname (:db *db*)
                                 :user (:user *db*) :password (:password *db*)})]
    (vec (for [i cases
               :let [schema (str "fz" i "_" (System/nanoTime))
                     model (with-tables schema (resolve-model (tcg/generate gen-model 30 (+ seed i))))
                     fs (try (vec (schema-findings ds schema model (+ seed i)))
                             (catch Exception e [{:kind :error :step :harness :message (ex-message e)}]))]
               :when (seq fs)]
           {:case i :ddl (ddl model) :findings fs}))))

(defn- line [s n] (let [s (first (str/split-lines (str s)))] (if (> (count s) n) (str (subs s 0 n) "...") s)))

(defn- report
  "A few lines a failing case: how to run it alone, its findings by stage, its DDL."
  [seed {:keys [case ddl findings]}]
  (str "case " case " (seed " seed "): PGMALLI_FUZZ_SEED=" seed " PGMALLI_FUZZ_CASE=" case
       " clojure -M:test -v pgmalli.catalog-fuzz-test/random-schemas-go-through-the-pipeline-as-postgresql-decides\n"
       (str/join (for [f (take 8 findings)]
                   (str "  " (name (:kind f)) (when (:step f) (str " at " (name (:step f))))
                        (when (:table f) (str " table " (pr-str (:table f))))
                        (when (:column f) (str " column " (pr-str (:column f))))
                        (when (:sqlstate f) (str " SQLSTATE " (:sqlstate f))) ": "
                        (line (or (:message f) (:type f) (:expr f) (:reasons f)) 200)
                        (when (contains? f :row) (str "\n    row " (line (pr-str (:row f)) 200)))
                        "\n")))
       (when (< 8 (count findings)) (str "  ... " (- (count findings) 8) " more\n"))
       ddl))

(deftest random-schemas-go-through-the-pipeline-as-postgresql-decides
  (when *db*
    (let [seed (ts/seed)
          failed (findings seed (cases))]
      (is (empty? failed)
          (str (count failed) " failing cases; by kind " (pr-str (frequencies (mapcat #(map :kind (:findings %)) failed))) "\n"
               (str/join "\n" (map #(report seed %) (take 3 failed))))))))
