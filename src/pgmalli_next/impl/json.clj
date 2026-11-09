(ns pgmalli-next.impl.json
  "JSON with whichever library the runtime has: jsonista on the JVM (json-jvm), cheshire on
   babashka, which ships it and has no Jackson for jsonista. Writing is the JVM's alone: what
   writes (pgmalli-next.jdbc) runs on the JVM.")

(def ^:private bb? (System/getProperty "babashka.version"))

(def ^:private jvm-parse (when-not bb? (requiring-resolve 'pgmalli-next.impl.json-jvm/parse)))

(defn parse
  "JSON text as data, keys as strings, or keywords where keywords? is true."
  ([s] (parse s false))
  ([s keywords?]
   (if bb?
     ((requiring-resolve 'cheshire.core/parse-string) s keywords?)
     (jvm-parse s keywords?))))

(defn write
  "Data as JSON text, on the JVM."
  [x]
  ((requiring-resolve 'pgmalli-next.impl.json-jvm/write) x))
