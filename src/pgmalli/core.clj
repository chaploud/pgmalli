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
  "The generated file of a schema (from the classpath), as data: {:schema :database-version
   :registry {name schema} :unrendered [fact ...] :skipped [fact ...] :diagnostics [...]}.
   :registry is the map of names to schemas a malli registry is made of (registry builds one
   from it, with malli's own schemas); :unrendered are the facts with no malli rendering;
   :diagnostics what the database stores but deserves a look (a partitioned table with no
   partition, a partition its parent's bounds make unreachable, a CHECK (false), CHECKs on a column that contradict each other, a NOT VALID or
   NOT ENFORCED constraint, a unique index repeating a key, a row-level INSERT trigger), each
   {:table :kind :confidence :severity :message ...}."
  [schema-name]
  (reg/read-generated schema-name))

(defn registry
  "malli registry holding the generated schemas of the named schemas (read from the classpath;
   generated data maps are accepted too), insert schemas derived from them, plus malli's
   defaults, malli.util and malli.experimental.time."
  [& schemas]
  (apply reg/registry schemas))

(defn install!
  "Makes the registry of the named schemas malli's default one, so schema data anywhere reads
   the generated names; process-wide, and so hiding a schema's reliance on them (see the
   README). Returns the schemas the default registry held before, which
   malli.registry/set-default-registry! puts back."
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
  "The [:map ...] of a row as next.jdbc builds it from what PostgreSQL's JDBC driver returns:
   by default plain next.jdbc, keys unqualified, dates and timestamps inst?, arrays, json and
   intervals (driver objects) :any. Options, the ones pgmalli.honeysql takes too (read-options
   gives them for a next.jdbc builder; its own, :schema :write :compare :result, are taken and
   do nothing here), a key or value not among them an error: :qualified? (keys as
   :table/column, as-maps), :kebab? and :lower? (as those builders), :nil-columns :absent
   (next.jdbc.optional), :time :instant or :local (next.jdbc.date-time's read-as-instant,
   read-as-local), :read for what a ReadableColumn extension returns: {pg-type schema} by a
   column's :pg/type, {:array :vector} arrays as vectors, {:json :parsed} json parsed."
  ([registry name] (as-read registry name {}))
  ([registry name opts] (jdbc/as-read registry name opts)))

(defn transformer
  "malli transformer decoding JDBC and string values into the registry's types. Instants and
   java.util.Dates that land in date or timestamp (without time zone) columns are read in
   :zone, default the JVM's; JSON text in json and jsonb columns is parsed."
  ([] (jdbc/transformer))
  ([opts] (jdbc/transformer opts)))
