(ns pgmalli.upstream-test
  "Lists pgmalli keeps of PostgreSQL's types and next.jdbc's builders, against the ones running.
   JVM only (next.jdbc), so bb test does not list it."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check.generators :as tcg]
            [honey.sql :as sql]
            [malli.core :as m]
            [clojure.data.json :as json]
            [next.jdbc :as jdbc]
            next.jdbc.date-time
            next.jdbc.optional
            next.jdbc.result-set
            [pgmalli.catalog-source :as source]
            [pgmalli.core :as pgmalli]
            [pgmalli.data :as data]
            [pgmalli.impl.files :as files]
            [pgmalli.impl.jdbc :as pjdbc]
            [pgmalli.impl.types :as types]
            [pgmalli.test-db :as test-db :refer [*db* datasource]]))

(use-fixtures :once test-db/with-postgres)

(def ^:private needs-setup
  "The extensions of the test image CREATE EXTENSION refuses without more setup, with why.
   None adds a type."
  (merge (zipmap ["plperl" "plperlu" "bool_plperl" "bool_plperlu" "hstore_plperl" "hstore_plperlu" "jsonb_plperl" "jsonb_plperlu"]
                 (repeat "the image has no libperl"))
         (zipmap ["plpython3u" "hstore_plpython3u" "jsonb_plpython3u" "ltree_plpython3u"] (repeat "the image has no libpython"))
         (zipmap ["pltcl" "pltclu"] (repeat "the image has no libtcl"))))

(defn- column [ds sql] (mapv (comp val first) (jdbc/execute! ds [sql])))


(deftest every-type-of-postgresql-and-its-extensions-is-supported-or-listed-as-unsupported
  (when *db*
    (let [ds (datasource)
          version (first (column ds "SHOW server_version"))]
      (doseq [e (column ds "SELECT name FROM pg_available_extensions WHERE installed_version IS NULL ORDER BY name")
              :when (not (needs-setup e))
              :let [err (try (jdbc/execute! ds [(str "CREATE EXTENSION IF NOT EXISTS \"" e "\" CASCADE")]) nil
                             (catch Exception x (ex-message x)))]]
        (is (nil? err) (str "PostgreSQL " version " extension " e " cannot be created (" err "): list it in needs-setup in test/pgmalli/upstream_test.clj, with why")))
      ;; arrays are rendered from their element type, domains (earth, lo) from their base type
      (let [rows (jdbc/execute! ds ["SELECT x.extname AS ext, format_type(t.oid, NULL) AS type
                                     FROM pg_type t
                                     LEFT JOIN pg_depend d ON d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e'
                                     LEFT JOIN pg_extension x ON x.oid = d.refobjid
                                     WHERE t.typtype IN ('b', 'r', 'm') AND NOT (t.typelem <> 0 AND t.typlen = -1)
                                       AND (t.typnamespace = 'pg_catalog'::regnamespace OR x.oid IS NOT NULL)
                                     ORDER BY 1, 2"]
                                   {:builder-fn next.jdbc.result-set/as-unqualified-maps})]
        (is (< 50 (count rows)))
        (doseq [{:keys [ext type]} rows
                :let [e (types/entry type)]]
          (is (or e (types/unsupported type))
              (str "PostgreSQL " version (if ext (str " extension " ext " adds type ") " has type ") type
                   " that pgmalli neither supports nor lists as unsupported: add an entry to src/pgmalli/impl/types.clj/types"
                   " (or, with why, to unsupported)"))
          (when e
            (is (= ext (:extension e)) (str type "'s :extension in src/pgmalli/impl/types.clj/types is " (pr-str (:extension e)) ", PostgreSQL " version " says " (pr-str ext)))))
        (testing "every entry names a type there is"
          (is (= #{} (set/difference (set (keys types/types)) (set (map :type rows))))))))))

(deftest every-literal-is-one-postgresql-reads
  (when *db*
    (let [ds (datasource)]
      (doseq [e (set (keep :extension (vals types/types)))]
        (jdbc/execute! ds [(str "CREATE EXTENSION IF NOT EXISTS \"" e "\"")]))
      (doseq [[t {:keys [literals]}] types/types, l literals
              :let [err (try (jdbc/execute! ds [(str "SELECT " (test-db/sql-literal l) "::" t)]) nil (catch Exception x (ex-message x)))]]
        (is (nil? err) (str "PostgreSQL does not read " (pr-str l) " as " t " (" err "): fix its :literals in src/pgmalli/impl/types.clj/types"))))))

(deftest a-not-null-column-of-every-supported-type-generates-inserts-and-reads-back
  (when *db*
    (let [ds (datasource)
          tables (into (sorted-map) (map-indexed (fn [i t] [(str "t" i) t]) (sort (keys types/types))))]
      (doseq [e (set (keep :extension (vals types/types)))]
        (jdbc/execute! ds [(str "CREATE EXTENSION IF NOT EXISTS \"" e "\"")]))
      (jdbc/execute! ds [(str/join ";\n" (for [[table t] tables] (str "CREATE TABLE " table " (id integer PRIMARY KEY, c " t " NOT NULL)")))])
      (let [registry (pgmalli/registry (files/from-database {:db *db*} "public"))
            dataset (tcg/generate (data/dataset-generator registry {:rows 3}) 30 42)
            builder 'next.jdbc.result-set/as-unqualified-maps]
        (is (nil? (data/short-tables dataset)))
        (doseq [stmt (data/inserts registry dataset)
                :let [table (name (:insert-into stmt))
                      t (tables (str/replace table #"^public\." ""))]]
          (testing t
            (is (nil? (try (jdbc/execute! ds (sql/format stmt)) nil (catch Exception x (ex-message x))))
                (str "PostgreSQL refuses a generated " t ": fix its entry in src/pgmalli/impl/types.clj/types"))
            (let [rows (jdbc/execute! ds [(str "SELECT * FROM " table)] {:builder-fn @(requiring-resolve builder)})
                  schema (pgmalli/as-read registry (keyword "pg.public" (str/replace table #"^public\." "")) {:jdbc {:builder-fn builder}})]
              (is (m/validate [:sequential schema] rows {:registry registry})
                  (str "pgjdbc returns a " t " as " (some-> (:c (first rows)) class .getName) ": fix its :read in src/pgmalli/impl/types.clj/types")))))))))

(deftest every-nextjdbc-map-builder-is-known
  (let [next-jdbc (str "next.jdbc " (test-db/version "next.jdbc"))
        theirs (set (for [n ['next.jdbc.result-set 'next.jdbc.optional]
                          s (keys (ns-publics n))
                          :when (re-matches #"as-.*maps" (name s))]
                      (symbol (name n) (name s))))
        ours (set (keys @#'pjdbc/builders))]
    (doseq [b (sort (set/difference theirs ours))]
      (is (nil? b) (str next-jdbc " has builder " b " that pgmalli does not know: add how it keys a row to builders in src/pgmalli/impl/jdbc.clj")))
    (doseq [b (sort (set/difference ours theirs))]
      (is (nil? b) (str next-jdbc " no longer has builder " b ": remove it from builders in src/pgmalli/impl/jdbc.clj")))))

(deftest the-catalog-snapshot-is-the-servers
  (when *db*
    (let [ds (datasource)
          version (first (str/split (first (column ds "SHOW server_version")) #"\."))
          server (source/shape (json/read-str (str (first (column ds source/query)))))
          snapshot @types/catalog
          here? (fn [{:keys [in]}] (or (nil? in) (some #{version} in)))
          ;; what the snapshot says this version has
          mine (fn [os] (set (for [o os :when (here? o)] (dissoc o :in))))
          ours {:types (into {} (for [[n t] (:types snapshot) :when (here? t)] [n (dissoc t :in)]))
                :ranges (into {} (for [[n r] (:ranges snapshot) :when (here? r)] [n (dissoc r :in)]))
                :casts (set (for [[s t in] (:casts snapshot) :when (here? in)] [s t]))}
          hint (str "PostgreSQL " version " differs from resources/pgmalli/impl/catalog.edn (run bb catalog): ")
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
