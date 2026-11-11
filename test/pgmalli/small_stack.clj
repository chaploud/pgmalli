(ns pgmalli.small-stack
  "Code run on a thread of a small stack, so that what overflows a stack overflows it whatever
   the JVM's default (-Xss, babashka's).")

(defn on-small-stack
  "What f returns on a thread of a 256 KB stack, or the Throwable it throws; the caller's dynamic
   bindings (a test's database) are f's."
  [f]
  (let [f (bound-fn* f)
        result (promise)
        t (Thread. nil #(deliver result (try (f) (catch Throwable e e))) "small-stack" (* 256 1024))]
    (.start t)
    (.join t)
    @result))
