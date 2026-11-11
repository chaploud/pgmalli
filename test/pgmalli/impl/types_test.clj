(ns pgmalli.impl.types-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pgmalli.impl.types :as types]))

(deftest the-aliases-of-oid-are-the-reg-types
  (is (= (set (filter #(str/starts-with? % "reg") (keys types/types)))
         (set (filter types/oid-alias? (keys types/types))))))

(deftest a-version-label-names-its-major-version
  (is (= ["17" nil nil] (map types/label-version [(types/version-label "17") "PostgreSQL 17.6" nil]))))
