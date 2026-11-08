(ns pgmalli-next.generate-test
  "The generation side read from files alone; no database."
  (:require [pgmalli-next.sample]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [pgmalli-next.core :as pgmalli]
            [pgmalli-next.generate :as generate]
            [pgmalli-next.impl.files :as files]))

(deftest a-migration-read-from-two-files
  (let [before pgmalli-next.sample/sample-data
        after (assoc-in before [:registry :pg.sample/groups] (conj (get-in before [:registry :pg.sample/groups]) [:motto [:maybe [:string {:pg/type "text"}]]]))]
    (is (= [{:name :pg.sample/groups :column :motto :file nil :db [:maybe [:string {:pg/type "text"}]]}]
           (generate/diff before after)))
    (is (= [] (generate/diff before before)))))

(deftest a-difference-names-what-differs-of-an-entry
  (let [before pgmalli-next.sample/sample-data
        groups (get-in before [:registry :pg.sample/groups])
        with #(assoc-in before [:registry :pg.sample/groups] %)]
    (is (= [{:name :pg.sample/groups :property :pg/primary-key :file ["id"] :db nil}]
           (generate/diff before (with (update groups 1 dissoc :pg/primary-key))))
        "a property of the row")
    (is (= [{:name :pg.sample/groups :column :name :property :pg/type :file "text" :db "varchar"}]
           (generate/diff before (with (assoc-in groups [3 1 1 :pg/type] "varchar"))))
        "a property of a column")
    (is (= [{:name :pg.sample/groups :column :name :file [:string {:pg/type "text"}] :db [:maybe [:string {:pg/type "text"}]]}]
           (generate/diff before (with (assoc-in groups [3 1] [:maybe [:string {:pg/type "text"}]]))))
        "a column's schema")
    (is (= [{:name :pg.sample/groups :order true :file [:id :name] :db [:name :id]}]
           (generate/diff before (with (assoc groups 2 (groups 3) 3 (groups 2))))))
    (is (= [{:name :pg.sample/gone :file nil :db [:enum "x"]}]
           (generate/diff before (assoc-in before [:registry :pg.sample/gone] [:enum "x"])))
        "an entry added, and removed the other way")
    (is (= [{:name :pg.sample/groups :file groups :db nil}]
           (generate/diff before (update before :registry dissoc :pg.sample/groups))))
    (is (= [{:key :database-version :file "PostgreSQL 17" :db "PostgreSQL 16"} {:key :sequences :file {"sample" []} :db {"sample" ["n"]}}]
           (generate/diff (assoc before :database-version "PostgreSQL 17" :sequences {"sample" []})
                          (assoc before :database-version "PostgreSQL 16" :sequences {"sample" ["n"]})))
        "the server's version, which types a query's pg_catalog calls, and the file's other parts as wholes")))

(deftest generate-writes-no-file-where-out-is-a-directory
  (let [dir (doto (java.io.File/createTempFile "pgmalli-out" "") .delete .mkdirs)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is a file generate! did not write; give :out a path of its own"
                          (generate/generate! {:out (str dir) :db {:psql "/nonexistent/psql"}})))
    (is (.isDirectory dir))))

(deftest functions-and-operators-differ-by-name
  (let [now pgmalli-next.sample/other-data
        before (-> now (assoc-in [:functions "other" "gone"] [{:args [] :result "text"}])
                   (assoc-in [:functions "other" "note_count" 0 :result] "integer")
                   (assoc-in [:operators "other" "~~~"] [{:args ["text" "text"] :result "boolean"}]))]
    (is (= [{:key :functions :schema "other" :name "gone" :file [{:args [] :result "text"}] :db nil}
            {:key :functions :schema "other" :name "note_count" :file [{:args [] :result "integer"}] :db [{:args [] :result "bigint"}]}
            {:key :operators :schema "other" :name "~~~" :file [{:args ["text" "text"] :result "boolean"}] :db nil}]
           (generate/diff before now)))))

(deftest diff-and-registry-take-generated-data-maps
  (doseq [x [42 "x" [nil] nil {} {:registry 1}]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"diff takes generated data" (generate/diff x {:registry {}})) (pr-str x)))
  (doseq [x [{} {:registry 1} (pgmalli/registry pgmalli-next.sample/sample-data)]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"registry takes generated data" (pgmalli/registry x)))))

(deftest a-file-that-is-not-generated-data-is-an-error-naming-it
  (let [path (str (doto (java.io.File/createTempFile "pgmalli-not-generated" "") .delete) "/app.edn")
        config {:schemas ["sample"] :out path}]
    (doseq [text ["{:schema" "[1 2]"]]
      (spit (doto path (io/make-parents)) text)
      (let [e (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is not a generated file.*run pgmalli-next.generate/generate!"
                                    (generate/check config)))]
        (is (= :pgmalli.error/not-generated (:pgmalli/error (ex-data e))) text)
        (is (re-find #"app.edn" (ex-message e)) text)))))

(deftest a-config-of-the-wrong-shape-says-the-shape
  (doseq [[c says] [["public" #"should be a map, such as \{:schemas"]
                    [{:schemas "public"} #"should be a vector of schema names"]
                    [{:db "postgres://localhost/app"} #":db \[\"should be a map: psql's keys"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo says (files/config c)) (pr-str c))))

(deftest a-misspelt-db-keeps-its-password-out-of-the-error
  (let [e (is (thrown? clojure.lang.ExceptionInfo (files/config {:dbb {:password "s3cret"}})))]
    (is (not (re-find #"s3cret" (pr-str (ex-data e)))))
    (is (not (re-find #"s3cret" (str e))))))

(deftest generate-writes-over-no-file-it-did-not-write
  (let [path (str (doto (java.io.File/createTempFile "pgmalli-not-generated" ".edn") (spit "{:paths [\"src\"]}")))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"is a file generate! did not write; give :out a path of its own"
                          (generate/generate! {:out path :db {:psql "/nonexistent/psql"}}))
        "refused before the database is read")
    (is (= "{:paths [\"src\"]}" (slurp path)))))

(deftest an-override-key-is-a-columns-keyword-checked-before-the-database-is-read
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should be a column's keyword, :schema.table/column"
                        (files/config {:overrides {"users.email" :string}}))))

(deftest schemas-name-each-schema-once
  (doseq [s [[] ["public" "public"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"should name at least one schema, each once" (files/config {:schemas s})) (pr-str s))))
