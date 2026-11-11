(ns pgmalli.impl.json-jvm
  "JSON on the JVM: jsonista, of a mapper whose Jackson limits are PostgreSQL's, not Jackson's
   (a jsonb document holds a string of any length, nesting past 1000): loaded on the JVM alone,
   as babashka has no Jackson."
  (:require [jsonista.core :as j])
  (:import (com.fasterxml.jackson.core JsonFactory StreamReadConstraints StreamWriteConstraints)))

(def ^:private factory
  (-> (JsonFactory/builder)
      (.streamReadConstraints (-> (StreamReadConstraints/builder)
                                  (.maxNestingDepth Integer/MAX_VALUE)
                                  (.maxStringLength Integer/MAX_VALUE)
                                  (.maxNumberLength Integer/MAX_VALUE)
                                  (.maxNameLength Integer/MAX_VALUE)
                                  (.maxDocumentLength -1)
                                  .build))
      (.streamWriteConstraints (-> (StreamWriteConstraints/builder) (.maxNestingDepth Integer/MAX_VALUE) .build))
      .build))

(def ^:private strings (j/object-mapper {:factory factory}))

(def ^:private keywords (j/object-mapper {:factory factory :decode-key-fn keyword}))

(defn parse
  "JSON text as data, keys as strings, or keywords where keywords? is true."
  ([s] (parse s false))
  ([s keywords?] (j/read-value s (if keywords? keywords strings))))

(defn write
  "Data (strings, numbers, booleans, nil, maps and collections) as JSON text."
  [x]
  (j/write-value-as-string x strings))
