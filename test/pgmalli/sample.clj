(ns pgmalli.sample
  "The registry of the checked-in test/resources/pgmalli/sample.edn and the row and dataset
   values that have to match it, shared by the test namespaces that read it."
  (:require [pgmalli.core :as pgmalli]))

(def registry (pgmalli/registry "sample"))

(def opts {:registry registry})

(def user
  {:id 1 :group_id 1 :group_name nil :updated_at nil :mood "sad" :nick nil :born nil :closed_at (java.time.Instant/now)
   :referrer_id nil :seq 1 :nick_upper nil :score 1 :total 2})

(def good
  {"sample.groups" [{:id 1 :name "a"} {:id 2 :name "b"}]
   "sample.users" [(assoc user :group_name "a")
                   (assoc user :id 2 :group_name "a" :referrer_id 1)]})
