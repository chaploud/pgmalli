(ns pgmalli-next.sent-test
  "The text pgmalli-next.jdbc/sent writes an array as, element by element, as PostgreSQL's array
   input reads it. JVM only (pgjdbc's PGobject), so bb test does not list it."
  (:require [clojure.test :refer [deftest is]]
            [pgmalli-next.jdbc :as pgmalli-jdbc])
  (:import (org.postgresql.util PGobject)))

(defn- text
  "The text sent of array v, as an untyped parameter's."
  [v]
  ((second (pgmalli-jdbc/sent ["" v]))))

(deftest a-document-jsonb-marks-is-one-element-of-an-array-not-a-dimension
  (is (= "{\"[1,2]\",\"{\\\"a\\\":1}\"}" (text [(pgmalli-jdbc/jsonb [1 2]) {:a 1}]))))

(deftest a-pgobject-of-no-value-is-a-null-element
  (is (= "{NULL,NULL}" (text [nil (PGobject.)]))))

(deftest a-date-of-no-year-or-past-9999-is-written-as-postgresql-reads-it
  (is (= "{\"0001-01-01 BC\",\"0006-03-04 BC\",\"10000-01-01\"}"
         (text [(java.time.LocalDate/of 0 1 1) (java.time.LocalDate/of -5 3 4) (java.time.LocalDate/of 10000 1 1)])))
  (is (= "{\"2026-01-02\"}" (text [(java.time.LocalDate/of 2026 1 2)]))))
