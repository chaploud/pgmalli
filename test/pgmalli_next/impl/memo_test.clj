(ns pgmalli-next.impl.memo-test
  (:require [clojure.test :refer [deftest is]]
            [pgmalli-next.impl.memo :as memo]))

(deftest a-bounded-cache-forgets-past-its-limit
  (let [calls (atom 0)
        f (memo/bounded (fn [x] (swap! calls inc) x))]
    (f 0)
    (f 0)
    (is (= 1 @calls) "a result held")
    (dotimes [i 20000] (f (inc i)))
    (f 0)
    (is (= 20002 @calls) "and forgotten past the limit, however many keys come")))
