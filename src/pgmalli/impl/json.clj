(ns pgmalli.impl.json
  "JSON text -> data, with whichever parser the runtime has: babashka ships cheshire and cannot
   load data.json; the JVM uses data.json.")

(def ^:private bb? (System/getProperty "babashka.version"))

(def parse @(requiring-resolve (if bb? 'cheshire.core/parse-string 'clojure.data.json/read-str)))

(def write @(requiring-resolve (if bb? 'cheshire.core/generate-string 'clojure.data.json/write-str)))
