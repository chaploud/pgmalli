# Reading rows through next.jdbc

`pgmalli.core/as-read` gives the `[:map ...]` of a row as next.jdbc builds it from what
PostgreSQL's JDBC driver returns. The examples use [example/schema.sql](example/schema.sql).

```clojure
(require '[pgmalli.core :as pgmalli]
         '[malli.core :as m])

(def registry (pgmalli/registry "public"))
```

## The default

With no options, the row is what next.jdbc's `as-unqualified-maps` builds with no extension:
keys are column names, dates and timestamps are `java.sql.Date` and `java.sql.Timestamp`
(`inst?`), and arrays, json and intervals are the driver's own objects (`:any`):

```clojure
(m/validate (pgmalli/as-read registry :pg.public/users)
            {:id 1 :group_id 1 :email "ann@example.com" :nick nil :status "active"
             :closed_at nil :created_at (java.sql.Timestamp. 0)}
            {:registry registry})
;; => true
```

## Options

| option | for |
|---|---|
| `:qualified? true` | keys `:users/id`, as `as-maps` builds them |
| `:kebab? true` | kebab-case keys and tables, as the kebab builders do |
| `:lower? true` | lower-case keys and tables, as the lower builders do |
| `:nil-columns :absent` | NULL columns left out, as `next.jdbc.optional` builds rows |
| `:time :instant` | `next.jdbc.date-time/read-as-instant` |
| `:time :local` | `next.jdbc.date-time/read-as-local` |
| `:read {pg-type schema}` | what a `ReadableColumn` extension of yours returns for a column type |
| `:read {:array :vector}` | an extension returning arrays as vectors |
| `:read {:json :parsed}` | an extension returning json parsed |

An option pgmalli does not know is an error, with the name meant:

```clojure
(try (pgmalli/as-read registry :pg.public/users {:qualifed? true})
     (catch clojure.lang.ExceptionInfo e (ex-message e)))
;; => "pgmalli options: {:qualifed? [\"should be spelled :qualified?\"]}"
```

## From a next.jdbc builder

`read-options` gives the options of a next.jdbc builder, named by its symbol or var:

```clojure
(pgmalli/read-options 'next.jdbc.result-set/as-unqualified-kebab-maps)
;; => {:kebab? true}

(pgmalli/read-options 'next.jdbc.optional/as-maps)
;; => {:qualified? true, :nil-columns :absent}
```

```clojure
(-> (pgmalli/as-read registry :pg.public/users (pgmalli/read-options 'next.jdbc.optional/as-maps))
    (nth 3))
;; => [:users/created_at [inst? #:pg{:default [:now], :type "timestamp with time zone"}]]
```

## Extensions of yours

A `ReadableColumn` extension changes what a column type comes back as. Name the type as its
`:pg/type` says, and give the schema the value has:

```clojure
(-> (pgmalli/as-read registry :pg.public/users {:read {"timestamp with time zone" :time/instant}})
    (nth 3))
;; => [:created_at :time/instant]
```

## Decoding instead

`transformer` turns JDBC values into the registry's types, for an application that works
with `java.time`:

```clojure
(def user
  (m/decode :pg.public/users
            {:id 1 :group_id 1 :email "ann@example.com" :nick nil :status "active"
             :closed_at nil :created_at (java.sql.Timestamp. 0)}
            {:registry registry}
            (pgmalli/transformer)))

(= (java.time.Instant/parse "1970-01-01T00:00:00Z") (:created_at user))
;; => true

(m/validate :pg.public/users user {:registry registry})
;; => true
```

An `Instant` landing in a `timestamp` (without time zone) column is read in `:zone`, the JVM's
by default: `(pgmalli/transformer {:zone (java.time.ZoneId/of "UTC")})`.
