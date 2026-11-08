(ns pgmalli-next.core
  "The application side of pgmalli: the schemas generated from PostgreSQL schemas (the file on
   the classpath as pgmalli-next.edn), read and used. Generation and freshness, which need
   psql, are pgmalli-next.generate; HoneySQL queries checked and typed, pgmalli-next.honeysql."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [pgmalli-next.impl.describe :as d]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.jdbc :as jdbc]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.registry :as reg]
            [pgmalli-next.impl.shape :as shape]))

(defn generated
  "The generated file on the classpath (pgmalli-next.edn), as data:
   {:schemas [name ...] :database-version :registry {name schema}
    :functions {schema {name [overload ...]}} :operators {schema {name [overload ...]}}
    :sequences {schema [name ...]} :unrendered [entry ...]
    :diagnostics [{:table :kind :severity :message ...} ...]},
   its entries and diagnostics as docs/types.md#the-generated-file lists them."
  []
  (reg/read-generated))

(defn registry
  "A malli registry of the generated file on the classpath, or of generated data, with the
   insert and update schemas derived from them, malli's default schemas, malli.util and
   malli.experimental.time (each with an :error/message, which me/humanize says).

   (registry)
   (registry (clojure.edn/read-string (slurp \"api/pgmalli-next.edn\")))"
  ([] (reg/registry))
  ([generated] (reg/registry generated)))

(defn as-read
  "The [:map ...] of a table's or view's row as pgmalli-next.jdbc/builder builds it
   (docs/reading-rows.md): keyed by the columns' names (:group_id). Validate with {:registry registry}. name
   is the row schema's (:pg.public/users) or the table as HoneySQL names it (:users in :schema,
   :public.users), as pgmalli-next.honeysql/values-schema takes it.

   (as-read registry :pg.public/users)

   Options: docs/reading-rows.md#options.

   Throws when an option is unknown, or name is no table's or view's row schema."
  ([registry name] (as-read registry name {}))
  ([registry name opts]
   (let [opts (jdbc/builder! registry (jdbc/options! opts [:schema :jdbc]))]
     (when (contains? (:jdbc opts) :return-keys)
       (error/fail! :pgmalli.error/invalid-options
                    "as-read reads no :return-keys, a write's RETURNING: the row it gives is the table's whole row"
                    {:given (select-keys (:jdbc opts) [:return-keys])}))
     (jdbc/as-read registry (query/row-name (query/reading registry) name "as-read" opts) (dissoc opts :schema)))))

(defn- schema!
  "schema when malli reads it under registry; an error naming the type it does not know."
  [registry schema]
  (try (m/schema schema {:registry registry})
       schema
       (catch clojure.lang.ExceptionInfo e
         (let [{:keys [schema ref]} (:data (ex-data e))
               unknown (or ref schema)]
           (cond
             (not (#{::m/invalid-schema ::m/invalid-ref} (:type (ex-data e)))) (throw e)
             (and (shape/generated-name? unknown) (not-any? shape/generated-name? (keys (shape/schemas-of registry))))
             (error/fail! :pgmalli.error/invalid-options
                          (str "describe: " (pr-str unknown) " is read through a registry: (describe registry schema)")
                          {:given unknown})
             :else
             (let [meant (when (keyword? unknown) (shape/meant unknown (filter keyword? (keys (shape/schemas-of registry)))))]
               (error/fail! :pgmalli.error/unknown-schema
                            (str "describe: " (pr-str unknown) " is no type malli or the registry knows"
                                 (error/did-you-mean (some->> meant (map pr-str) (str/join " or "))))
                            (cond-> {:given unknown} meant (assoc :did-you-mean (vec meant))))))))))

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
   (d/describe {} (schema! reg/base schema)))
  ([registry schema]
   (when (shape/generated-name? schema) (shape/schema-of registry schema))
   (d/describe (shape/schemas-of registry) (schema! registry schema))))
