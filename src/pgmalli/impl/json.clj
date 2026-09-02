(ns pgmalli.impl.json
  "JSON text -> data, with whichever parser the runtime has: babashka ships cheshire and cannot
   load data.json; the JVM uses data.json.")

(def parse
  (if (System/getProperty "babashka.version")
    (let [f (requiring-resolve 'cheshire.core/parse-string)] #(f %))
    (let [f (requiring-resolve 'clojure.data.json/read-str)] #(f %))))

(def write
  (if (System/getProperty "babashka.version")
    (let [f (requiring-resolve 'cheshire.core/generate-string)] #(f %))
    (let [f (requiring-resolve 'clojure.data.json/write-str)] #(f %))))
