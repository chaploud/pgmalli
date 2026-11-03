(ns pgmalli.core
  "The application side of pgmalli: the schemas generated from a PostgreSQL schema (files on
   the classpath as pgmalli/<schema>.edn), read and used. Generation and freshness, which need
   psql, are pgmalli.generate; datasets, pgmalli.data; HoneySQL queries checked and typed,
   pgmalli.honeysql."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [pgmalli.impl.describe :as d]
            [pgmalli.impl.error :as error]
            [pgmalli.impl.jdbc :as jdbc]
            [pgmalli.impl.portable :as portable]
            [pgmalli.impl.registry :as reg]
            [pgmalli.impl.shape :as shape]))

(defn generated
  "The generated file of a schema, read from the classpath, as data:
   {:schema :database-version :registry {name schema} :functions {name [overload ...]}
    :operators {name [overload ...]} :unrendered [entry ...] :skipped [entry ...]
    :diagnostics [{:table :kind :severity :message ...} ...]},
   its entries and diagnostics as docs/types.md#the-generated-file lists them.

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
  (when (empty? schemas)
    (error/fail! :pgmalli.error/invalid-options "registry takes the schemas to read: (registry \"public\")" {:given []}))
  (apply reg/registry schemas))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints: what
   malli.util's select-keys, optional-keys and the like take (the [:and ...] of a table with
   constraints is not a map to them)."
  [registry name]
  (reg/columns registry name))

(defn portable
  "The named schema as data malli's default registry reads (plus malli.experimental.time):
   the schema's own types inlined, pgmalli's types as their malli counterparts, generation
   hints dropped, the CHECKs only pgmalli evaluates and a numeric(p,s)'s precision left out.
   For :malli/schema metadata and other places the registry cannot follow. A name the registry
   does not hold is an error."
  [registry name]
  (portable/portable registry name))

(defn as-read
  "The [:map ...] of a table's or view's row as next.jdbc builds it from what PostgreSQL's
   JDBC driver returns. Validate with {:registry registry}.

   (as-read registry :pg.public/users {:jdbc {:builder-fn rs/as-unqualified-maps}})

   By default, as next.jdbc's default builder (as-maps) builds rows with no extension: keys
   :table/column, the types docs/reading-rows.md lists. :jdbc takes the options passed to
   next.jdbc (its :builder-fn, :label-fn and :qualifier-fn key the row). Options:
   docs/reading-rows.md#options.

   Throws when an option is unknown, or name is no table's or view's row schema."
  ([registry name] (as-read registry name {}))
  ([registry name opts] (jdbc/as-read registry name opts)))

(defn transformer
  "malli transformer decoding JDBC and string values into the registry's types. An Instant or
   java.util.Date in a date or timestamp (without time zone) column is read in :zone, default
   the JVM's, except a java.sql.Timestamp in a timestamp column (its date and time are kept)
   and a java.sql.Date in a date column; JSON text in json and jsonb columns is parsed."
  ([] (jdbc/transformer))
  ([opts]
   (jdbc/checked! [:map {:closed true} [:zone {:optional true} [:fn {:error/message "should be a java.time.ZoneId"} #(instance? java.time.ZoneId %)]]] opts)
   (jdbc/transformer opts)))

(defn- schema!
  "schema when malli reads it under registry; an error naming the type it does not know."
  [registry schema]
  (try (m/schema schema {:registry registry})
       schema
       (catch clojure.lang.ExceptionInfo e
         (let [unknown (-> e ex-data :data :schema)]
           (if (= ::m/invalid-schema (:type (ex-data e)))
             (let [meant (when (keyword? unknown) (shape/meant unknown (filter keyword? (keys (shape/schemas-of registry)))))]
               (error/fail! :pgmalli.error/unknown-schema
                            (str "describe: " (pr-str unknown) " is no type malli or the registry knows"
                                 (error/did-you-mean (some->> meant (map pr-str) (str/join " or "))))
                            (cond-> {:given unknown} meant (assoc :did-you-mean (vec meant)))))
             (throw e))))))

(defn describe
  "A schema as short data for people and tools to read: a column as its PostgreSQL type
   (\"varchar(40)\", \"timestamptz\"), ? when it takes NULL, (optional) when its key may be
   absent, an enum with its type and labels (\"user_status: active | closed\"); a map as
   {key description} in column order, a sequence as [description], a function schema as
   {:args [...] :returns ...} (and :pgmalli/problems, a query schema's). A name, or a reference inside the schema, is read through the registry.

   (describe (h/query-schema registry [:id] q))
   (describe registry :pg.public/users)"
  ([schema]
   (when (shape/generated-name? schema)
     (error/fail! :pgmalli.error/invalid-options (str "describe reads " (pr-str schema) " through a registry: (describe registry " (pr-str schema) ")") {:given schema}))
   (d/describe {} (schema! (reg/registry) schema)))
  ([registry schema]
   (when (shape/generated-name? schema) (shape/schema-of registry schema))
   (d/describe (shape/schemas-of registry) (schema! registry schema))))
