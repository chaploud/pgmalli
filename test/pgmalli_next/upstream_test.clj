(ns pgmalli-next.upstream-test
  "Lists pgmalli keeps of PostgreSQL's types, against the ones running.
   JVM only (next.jdbc), so bb test does not list it."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [malli.core :as m]
            [malli.generator :as mg]
            [pgmalli-next.impl.json :as json]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [pgmalli-next.catalog-source :as source]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]
            [pgmalli-next.test-db :as test-db :refer [*db* datasource]]))

(use-fixtures :once test-db/with-postgres)

(def ^:private needs-setup
  "The extensions of the test image CREATE EXTENSION refuses without more setup, with why.
   None adds a type."
  (merge (zipmap ["plperl" "plperlu" "bool_plperl" "bool_plperlu" "hstore_plperl" "hstore_plperlu" "jsonb_plperl" "jsonb_plperlu"]
                 (repeat "the image has no libperl"))
         (zipmap ["plpython3u" "hstore_plpython3u" "jsonb_plpython3u" "ltree_plpython3u"] (repeat "the image has no libpython"))
         (zipmap ["pltcl" "pltclu"] (repeat "the image has no libtcl"))))

(defn- column [ds sql] (mapv (comp val first) (jdbc/execute! ds [sql])))


(def ^:private unsupported
  "The types of pg_catalog and of the extensions PostgreSQL ships that types/types has no entry
   for yet, with why: what to add next."
  (merge
   (zipmap ["aclitem" "pg_node_tree" "pg_ndistinct" "pg_dependencies" "pg_mcv_list" "pg_brin_bloom_summary" "pg_brin_minmax_multi_summary"]
           (repeat "the catalog's own, which no table column holds"))
   {"refcursor" "a cursor's name, meaningful only inside the transaction that opened it"}
   (zipmap ["gtsvector" "gbtreekey2" "gbtreekey4" "gbtreekey8" "gbtreekey16" "gbtreekey32" "gbtreekey_var" "ghstore" "intbig_gkey" "ltree_gist" "gtrgm"]
           (repeat "a GiST index's storage type, with no input function"))))

(deftest every-type-of-postgresql-and-its-extensions-is-supported-or-listed-as-unsupported
  (when *db*
    (let [ds (datasource)
          version (first (column ds "SHOW server_version"))]
      (doseq [e (column ds "SELECT name FROM pg_available_extensions WHERE installed_version IS NULL ORDER BY name")
              :when (not (needs-setup e))
              :let [err (try (jdbc/execute! ds [(str "CREATE EXTENSION IF NOT EXISTS \"" e "\" CASCADE")]) nil
                             (catch Exception x (ex-message x)))]]
        (is (nil? err) (str "PostgreSQL " version " extension " e " cannot be created (" err "): list it in needs-setup in test/pgmalli_next/upstream_test.clj, with why")))
      ;; arrays are rendered from their element type, domains (earth, lo) from their base type
      (let [rows (jdbc/execute! ds ["SELECT x.extname AS ext, format_type(t.oid, NULL) AS type
                                     FROM pg_type t
                                     LEFT JOIN pg_depend d ON d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e'
                                     LEFT JOIN pg_extension x ON x.oid = d.refobjid
                                     WHERE t.typtype IN ('b', 'r', 'm') AND NOT (t.typelem <> 0 AND t.typlen = -1)
                                       AND (t.typnamespace = 'pg_catalog'::regnamespace OR x.oid IS NOT NULL)
                                     ORDER BY 1, 2"]
                                   {:builder-fn rs/as-unqualified-maps})]
        (is (< 50 (count rows)))
        (doseq [{:keys [ext type]} rows
                :let [e (types/entry type)]]
          (is (or e (unsupported type))
              (str "PostgreSQL " version (if ext (str " extension " ext " adds type ") " has type ") type
                   " that pgmalli neither supports nor lists as unsupported: add an entry to src/pgmalli_next/impl/types.clj/types"
                   " (or, with why, to this test's unsupported)"))
          (when e
            (is (= ext (:extension e)) (str type "'s :extension in src/pgmalli_next/impl/types.clj/types is " (pr-str (:extension e)) ", PostgreSQL " version " says " (pr-str ext)))))
        (testing "every entry names a type there is"
          (is (= #{} (set/difference (set (keys types/types)) (set (map :type rows))))))))))

(deftest every-literal-is-one-postgresql-reads
  (when *db*
    (let [ds (datasource)]
      (doseq [e (set (keep :extension (vals types/types)))]
        (jdbc/execute! ds [(str "CREATE EXTENSION IF NOT EXISTS \"" e "\"")]))
      (doseq [[t {:keys [literals]}] types/types, l literals
              :let [err (try (jdbc/execute! ds [(str "SELECT " (test-db/sql-literal l) "::" t)]) nil (catch Exception x (ex-message x)))]]
        (is (nil? err) (str "PostgreSQL does not read " (pr-str l) " as " t " (" err "): fix its :literals in src/pgmalli_next/impl/types.clj/types"))))))

(defn- text-of
  "A generated value of type t as text PostgreSQL reads as t."
  [t v]
  (cond (#{"json" "jsonb"} t) (json/write v)
        (map? v) (str/join ", " (for [[k x] v :let [q #(str "\"" (str/replace (str %) #"[\\\"]" "\\\\$0") "\"")]]
                                        (str (q k) "=>" (if (nil? x) "NULL" (q x)))))
        (bytes? v) (apply str "\\x" (map #(format "%02x" (bit-and % 0xff)) v))
        :else (str v)))

(deftest a-not-null-column-of-every-supported-type-generates-inserts-and-reads-back
  (when *db*
    (let [ds (datasource)
          tables (into (sorted-map) (map-indexed (fn [i t] [(str "t" i) t]) (sort (keys types/types))))]
      (doseq [e (set (keep :extension (vals types/types)))]
        (jdbc/execute! ds [(str "CREATE EXTENSION IF NOT EXISTS \"" e "\"")]))
      (jdbc/execute! ds [(str/join ";\n" (for [[table t] tables] (str "CREATE TABLE " table " (id integer PRIMARY KEY, c " t " NOT NULL)")))])
      (let [registry (pgmalli/registry (test-db/generated {:db *db*} "public"))]
        (doseq [[table t] tables
                [id {:keys [c]}] (map-indexed vector (mg/sample (keyword (str "pg.public." table) "insert") {:registry registry :size 3 :seed 42}))]
          (testing t
            (is (nil? (try (jdbc/execute! ds [(str "INSERT INTO " table " VALUES (" id ", " (test-db/sql-literal (text-of t c)) "::" t ")")]) nil (catch Exception x (ex-message x))))
                (str "PostgreSQL refuses a generated " t " " (pr-str c) ": fix its entry in src/pgmalli_next/impl/types.clj/types"))))
        (doseq [[table t] tables]
          (testing t
            (let [rows (jdbc/execute! ds [(str "SELECT * FROM " table)] {:builder-fn (test-db/builder registry)})
                  schema (pgmalli/as-read registry (keyword "pg.public" table))]
              (is (= 3 (count rows)))
              (is (m/validate [:sequential schema] rows {:registry registry})
                  (str "pgjdbc returns a " t " as " (some-> (:c (first rows)) class .getName) ": fix its :read in src/pgmalli_next/impl/types.clj/types")))))))))

(deftest the-catalog-snapshot-is-the-servers
  (when *db*
    (let [ds (datasource)
          version (first (str/split (first (column ds "SHOW server_version")) #"\."))
          server (source/shape (json/parse (first (column ds (str "SELECT (" source/query ")::text")))))
          snapshot @types/catalog
          here? (fn [{:keys [in]}] (or (nil? in) (some #{version} in)))
          ;; what the snapshot says this version has
          mine (fn [os] (set (for [o os :when (here? o)] (dissoc o :in))))
          ours {:types (into {} (for [[n t] (:types snapshot) :when (here? t)] [n (dissoc t :in)]))
                :ranges (into {} (for [[n r] (:ranges snapshot) :when (here? r)] [n (dissoc r :in)]))
                :casts (set (for [[s t c in] (:casts snapshot) :when (here? in)] [s t c]))}
          hint (str "PostgreSQL " version " differs from resources/pgmalli-next/impl/catalog.edn (run bb catalog): ")
          differs (fn [what theirs ours]
                    (str hint what "\n  server only: " (set/difference (set theirs) (set ours)) "\n  snapshot only: " (set/difference (set ours) (set theirs))))]
      (is (some #{version} (:versions snapshot)) (str "PostgreSQL " version " is not among the catalog's versions: add it to CI and run bb catalog"))
      (doseq [k [:types :casts :ranges]
              :when (not= (get server k) (get ours k))]
        (is false (differs (name k) (get server k) (get ours k))))
      (doseq [k [:functions :operators]
              n (set (concat (keys (get server k)) (keys (get snapshot k))))
              :let [theirs (get-in server [k n] #{}) ours (mine (get-in snapshot [k n]))]
              :when (not= theirs ours)]
        (is false (differs (str (name k) " " n) theirs ours)))
      (doseq [n (set (concat (keys (:relations server)) (keys (:relations snapshot))))
              :let [theirs (get-in server [:relations n] []) ours (mine (get-in snapshot [:relations n]))]
              :when (not= (set theirs) ours)]
        (is false (differs (str "relation " n) theirs ours))))))

(deftest every-types-typname-is-among-its-aliases
  ;; :aliases names a type as pg_type's typname too (int4, float8, timestamptz), which casts and
  ;; the problems' casts spell; the grammar's spellings (int, decimal, float) are by hand
  (when *db*
    (let [ds (datasource)
          typnames (into {} (map (juxt :t :n))
                         (jdbc/execute! ds ["SELECT format_type(oid, NULL) AS t, typname AS n FROM pg_type WHERE typnamespace = 'pg_catalog'::regnamespace"]
                                        {:builder-fn rs/as-unqualified-maps}))]
      (doseq [[t {:keys [aliases]}] types/types
              :let [n (typnames t)]
              ;; but one the grammar reads as another type (char is character's, "char" quoted)
              :when (and n (not= n t) (not-any? (fn [[t2 e]] (and (not= t t2) (some #{n} (:aliases e)))) types/types))]
        (is (some #{n} aliases) (str t "'s typname " n " is not among its :aliases in pgmalli-next.impl.types"))))))

(def ^:private grammar-spellings
  "The spellings of a type gram.y reads as another type's name (SQL's own: INT, DEC, FLOAT, CHAR
   VARYING, NATIONAL CHARACTER ...), which no catalog holds: a cast may spell a type so."
  ["int" "integer" "smallint" "bigint" "real" "float" "double precision" "dec" "decimal" "numeric" "boolean"
   "char" "character" "varchar" "char varying" "character varying" "national character" "national char" "nchar"
   "national character varying" "national char varying" "nchar varying" "bit varying"
   "time" "time without time zone" "time with time zone" "timestamp" "timestamp without time zone" "timestamp with time zone"])

(deftest every-name-and-alias-of-a-type-is-one-postgresql-reads-as-it
  (when *db*
    (let [ds (datasource)
          read-as #(:t (jdbc/execute-one! ds ["SELECT format_type(to_regtype(?), NULL) AS t" %] {:builder-fn rs/as-unqualified-maps}))]
      (doseq [[t e] types/types :when (not (:extension e)), n (cons t (:aliases e))]
        (is (= t (read-as n)) (str n " of " t " in pgmalli-next.impl.types is not a name PostgreSQL reads as " t)))
      (doseq [n grammar-spellings]
        (is (= (read-as n) (:name (types/entry n))) (str "PostgreSQL reads " n " as " (read-as n) ": add it to that type's :aliases in pgmalli-next.impl.types"))))))

(deftest the-types-pgjdbc-names-as-varchar-are-shapes-varchar-names
  (when *db*
    (with-open [conn (jdbc/get-connection (datasource))]
      (let [info (.getTypeInfo ^org.postgresql.PGConnection (.unwrap conn org.postgresql.PGConnection))
            names (map :typname (jdbc/execute! conn ["SELECT typname FROM pg_type WHERE typnamespace = 'pg_catalog'::regnamespace AND typtype = 'b'"]
                                               {:builder-fn rs/as-unqualified-maps}))]
        (is (= (set (filter #(= java.sql.Types/VARCHAR (.getSQLType info ^String %)) names)) @#'shape/varchar-names))))))

(deftest the-system-columns-are-a-tables-columns-of-negative-attnum
  (when *db*
    (let [ds (datasource)]
      (jdbc/execute! ds ["CREATE TABLE sys_cols (id int)"])
      (is (= (into {} (for [[c s] query/system-columns] [c (:pg/type (shape/column-props s))]))
             (into {} (map (juxt :attname :t))
                   (jdbc/execute! ds ["SELECT attname, format_type(atttypid, NULL) AS t FROM pg_attribute WHERE attrelid = 'sys_cols'::regclass AND attnum < 0"]
                                  {:builder-fn rs/as-unqualified-maps})))))))
