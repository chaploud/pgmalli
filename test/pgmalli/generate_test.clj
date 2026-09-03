(ns pgmalli.generate-test
  "The generation side read from files alone; no database."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [pgmalli.core :as pgmalli]
            [pgmalli.generate :as generate]
            [pgmalli.impl.files :as files]))

(deftest a-migration-read-from-two-files
  (let [before (pgmalli/generated "sample")
        after (assoc-in before [:registry :pg.sample/groups] (conj (get-in before [:registry :pg.sample/groups]) [:motto [:maybe [:string {:pg/type "text"}]]]))]
    (is (= [{:name :pg.sample/groups :column :motto :file nil :db [:maybe [:string {:pg/type "text"}]]}]
           (generate/diff before after)))
    (is (= [] (generate/diff before before)))))

(deftest check-without-a-database-reads-the-files
  (let [out-dir (str (doto (java.io.File/createTempFile "pgmalli-nodb" "") .delete))
        config {:out-dir out-dir :schemas ["sample"]}
        data (assoc (pgmalli/generated "sample")
                    :unrendered [{:kind :check :table "sample.users"}]
                    :diagnostics [{:table "sample.users" :kind :row-trigger}])]
    (spit (doto (files/path-for config "sample") (io/make-parents)) (files/edn-string data))
    (is (= {:stale nil
            :unrendered {"sample" [{:kind :check :table "sample.users"}]}
            :diagnostics {"sample" [{:table "sample.users" :kind :row-trigger}]}}
           (generate/check config {:db? false})))))
