(ns pgmalli-next.sample
  "The registry of the checked-in test/resources/pgmalli-next/sample.edn and the row values
   that have to match it, shared by the test namespaces that read it."
  (:require [pgmalli-next.core :as pgmalli]))

(def registry (pgmalli/registry "sample"))

(def opts {:registry registry})

(def user
  {:id 1 :group_id 1 :group_name nil :updated_at nil :mood "sad" :nick nil :born nil :closed_at (java.time.Instant/now)
   :referrer_id nil :seq 1 :nick_upper nil :score 1 :total 2})

