(ns pgmalli.impl.memo-test
  (:require [clojure.test :refer [deftest is]]
            [pgmalli.impl.memo :as memo]))

(deftest a-bounded-cache-forgets-past-its-limit
  (let [calls (atom 0)
        f (memo/bounded (fn [x] (swap! calls inc) x))]
    (f 0)
    (f 0)
    (is (= 1 @calls) "a result held")
    (dotimes [i 20000] (f (inc i)))
    (f 0)
    (is (= 20002 @calls) "and forgotten past the limit, however many keys come")))

(deftest a-name-longer-than-postgresql-holds-is-not-held
  (let [calls (atom 0)
        f (memo/bounded (fn [x] (swap! calls inc) x))
        long (keyword (apply str (repeat 300 "a")))]
    (f long)
    (f long)
    (is (= 2 @calls))))
