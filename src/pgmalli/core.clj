(ns pgmalli.core
  "The application side of pgmalli: the schemas generated from a PostgreSQL schema (files on
   the classpath as pgmalli/<schema>.edn), read and used. Generation and freshness, which need
   psql, are pgmalli.generate; datasets, which need test.check, pgmalli.data; HoneySQL queries
   checked and typed, pgmalli.honeysql.

   The config (pgmalli.generate), the generated file layout and the fact vocabulary
   (pgmalli.impl.pattern) are the stable contract; pgmalli.impl.* may change without notice."
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
   :diagnostics what the database stores but
   deserves a look (a partitioned table with no partition, a partition its parent's bounds make
   unreachable, a CHECK (false), CHECKs on a column that contradict each other, a NOT VALID or
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
  "Makes the registry of the named schemas malli's default registry (it holds malli's own
   schemas, malli.util and malli.experimental.time too), so :malli/schema metadata,
   malli.dev/start! and malli.instrument read :pg.public/users and the other generated names
   directly. Process-wide, like malli's default registry: for an application with this one
   registry; one with a registry of its own composes the two (malli.registry/composite-registry)
   or uses portable, which touches nothing. Returns the schemas the default registry held
   before, which malli.registry/set-default-registry! puts back."
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
  "The schema of one column of a row or insert schema, as data, [:maybe ...] included."
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
   other places the registry cannot follow."
  [registry name]
  (portable/portable registry name))

(defn read-options
  "The reading options (see as-read) of a next.jdbc result set builder named by its symbol:
   next.jdbc/as-unqualified-lower-maps, next.jdbc.optional/as-kebab-maps (NULL columns absent)
   and the like. nil for a builder that builds no map (as-arrays); an error for one pgmalli
   does not know."
  [builder]
  (jdbc/read-options builder))

(defn as-read
  "The [:map ...] of a row as a JDBC result builder returns it. Options, the ones
   pgmalli.honeysql takes too (read-options gives them for a next.jdbc builder): :qualified?
   (keys as :table/column, next.jdbc's as-maps), :kebab? (keys and table names in kebab-case),
   :nil-columns :absent (NULL columns missing, next.jdbc.optional), :time :instant
   (next.jdbc.date-time/read-as-instant: timestamps as Instants, dates stay java.sql.Date, so
   inst?) or :local (read-as-local: timestamptz as LocalDateTime); without :time every date and
   timestamp is inst?, which malli's default registry reads."
  [registry name opts]
  (portable/as-read registry name opts))

(defn transformer
  "malli transformer decoding JDBC and string values into the registry's types. Instants and
   java.util.Dates that land in date or timestamp (without time zone) columns are read in
   :zone, default the JVM's; JSON text in json and jsonb columns is parsed."
  ([] (jdbc/transformer))
  ([opts] (jdbc/transformer opts)))
