(ns pgmalli.core
  "The application side of pgmalli: the schemas generated from a PostgreSQL schema (files on
   the classpath as pgmalli/<schema>.edn), read and used. Generation and freshness, which need
   psql, are pgmalli.generate; datasets, which need test.check, pgmalli.data; HoneySQL queries
   checked and typed, pgmalli.honeysql.

   The config (pgmalli.generate) and the generated file layout are the stable contract, and so
   is the fact vocabulary (pgmalli.impl.pattern), which is the one exception to the rule that
   pgmalli.impl.* may change without notice."
  (:require [malli.core :as m]
            [malli.registry :as mr]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.portable :as portable]
            [pgmalli.impl.registry :as reg]
            [pgmalli.impl.shape :as shape]))

(defn generated
  "The generated file of a schema, read from the classpath, as data:
   {:schema :database-version :registry {name schema} :unrendered [fact ...]
    :skipped [fact ...] :diagnostics [{:table :kind :severity :message ...} ...]}.

   :unrendered lists the constraints pgmalli cannot express in malli; :diagnostics what
   deserves a look in the database (a NOT VALID constraint, an unreachable partition, a
   row-level INSERT trigger ...)."
  [schema-name]
  (reg/read-generated schema-name))

(defn registry
  "A malli registry of the named schemas' generated files, with the insert and update
   schemas derived from them, malli's default schemas, malli.util and malli.experimental.time.

   (registry \"public\")

   A generated data map (see generated) is taken in place of a name."
  [& schemas]
  (apply reg/registry schemas))

(defn install!
  "Makes the registry of the named schemas malli's default registry, for the whole process.
   Returns the schemas the default registry held, for malli.registry/set-default-registry!
   to put back.

   Code using the generated names then validates without {:registry ...}, and no longer shows
   that it needs them."
  [& schemas]
  (let [before (mr/-schemas m/default-registry)]
    (mr/set-default-registry! (apply reg/registry schemas))
    before))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints: what
   malli.util's select-keys, optional-keys and the like take (the [:and ...] of a table with
   constraints is not a map to them)."
  [registry name]
  (reg/columns registry name))

(defn column
  "The schema of one column of a row or insert schema, as data, [:maybe ...] included; nil for
   a column the schema does not have. A name the registry does not hold is an error."
  [registry name col]
  (portable/column registry name col))

(defn non-null
  "A column schema without its [:maybe ...]: what a value must be when it is not NULL."
  [schema]
  (shape/non-null schema))

(defn portable
  "The named schema as data malli's default registry reads (plus malli.experimental.time):
   the schema's own types inlined, pgmalli's types as their malli counterparts, generation
   hints dropped, the CHECKs only pgmalli evaluates left out. For :malli/schema metadata and
   other places the registry cannot follow. A name the registry does not hold is an error."
  [registry name]
  (portable/portable registry name))

(defn read-options
  "The reading options (see as-read) of a next.jdbc map builder, named by its symbol or var
   (#'rs/as-maps):
   'next.jdbc.result-set/as-unqualified-kebab-maps, 'next.jdbc.optional/as-maps (NULL columns
   absent) and the like. An error for another (the modified builders key rows by functions of
   yours, the array builders build no map)."
  [builder]
  (jdbc/read-options builder))

(defn as-read
  "The [:map ...] of a table's or view's row as next.jdbc builds it from what PostgreSQL's
   JDBC driver returns. Validate with {:registry registry}.

   (as-read registry :pg.public/users {:qualified? true})

   By default, as as-unqualified-maps builds rows with no extension: dates and timestamps
   inst?, arrays, json and intervals :any. Options (read-options gives those of a builder):
     :qualified? true       keys as :table/column
     :kebab? true           kebab-case keys and tables
     :lower? true           lower-case keys and tables
     :nil-columns :absent   NULL columns left out (next.jdbc.optional)
     :time :instant/:local  next.jdbc.date-time's read-as-instant / read-as-local
     :read {pg-type schema} what a ReadableColumn extension returns; also {:array :vector},
                            {:json :parsed}

   Throws when an option is unknown, or name is no table's or view's row schema.
   See docs/reading-rows.md."
  ([registry name] (as-read registry name {}))
  ([registry name opts] (jdbc/as-read registry name opts)))

(defn transformer
  "malli transformer decoding JDBC and string values into the registry's types. Instants and
   java.util.Dates that land in date or timestamp (without time zone) columns are read in
   :zone, default the JVM's; JSON text in json and jsonb columns is parsed."
  ([] (jdbc/transformer))
  ([opts]
   (jdbc/checked! [:map {:closed true} [:zone {:optional true} [:fn {:error/message "should be a java.time.ZoneId"} #(instance? java.time.ZoneId %)]]] opts)
   (jdbc/transformer opts)))
