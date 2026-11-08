(ns pgmalli-next.impl.error-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pgmalli-next.impl.error :as error]))

(deftest a-message-keeps-no-url-nor-conninfo-password
  (doseq [s ["connection to jdbc:postgresql://u:s3cret@h/db failed"
             "psql: error: postgresql://u:s3cret@h/db"
             "psql: error: invalid connection option: password=s3cret host=h"
             "psql: error: password = 's3 cr\\'et' host=h"]]
    (is (not (str/includes? (error/without-urls s) "s3")) s)))
