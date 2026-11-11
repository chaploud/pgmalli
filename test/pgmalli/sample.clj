(ns pgmalli.sample
  "The registry of the checked-in test/resources/pgmalli/sample.edn (schema sample; other.edn,
   schema other, and both.edn, of the two, beside it) and the row values that have to match it, shared by the test
   namespaces that read it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [pgmalli.core :as pgmalli]))

(def sample-data (edn/read-string (slurp (io/resource "pgmalli/sample.edn"))))

(def other-data (edn/read-string (slurp (io/resource "pgmalli/other.edn"))))

(def both-data (edn/read-string (slurp (io/resource "pgmalli/both.edn"))))

(def registry (pgmalli/registry sample-data))

(def opts {:registry registry})

(def user
  {:id 1 :group_id 1 :group_name nil :updated_at nil :mood "sad" :nick nil :born nil :closed_at (java.time.Instant/now)
   :referrer_id nil :seq 1 :nick_upper nil :score 1 :total 2})

