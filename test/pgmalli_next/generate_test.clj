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
                    [{:schemas []} #"\{:schemas \[\"should be a vector of schema names, such as \[\\\"public\\\"\]\"\]\}$"]
                    [{:db "postgres://localhost/app"} #":db \[\"should be a map: psql's keys"]]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo says (files/config c)) (pr-str c))))

(deftest a-misspelt-db-keeps-its-password-out-of-the-error
  (let [e (is (thrown? clojure.lang.ExceptionInfo (files/config {:dbb {:password "s3cret"}})))]
    (is (not (re-find #"s3cret" (pr-str (ex-data e)))))
    (is (not (re-find #"s3cret" (str e))))))
