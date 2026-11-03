# Reading rows through next.jdbc

Part of [pgmalli](../README.md): [types](types.md) · [reading rows](reading-rows.md) · [queries](queries.md) · [datasets](datasets.md)

`pgmalli.core/as-read` gives the `[:map ...]` of a row as next.jdbc builds it from what
PostgreSQL's JDBC driver returns, read with next.jdbc's `as-unqualified-maps`. The examples use [example/schema.sql](example/schema.sql).

```clojure
(require '[pgmalli.core :as pgmalli]
         '[malli.core :as m]
         '[malli.util :as mu])

(def registry (pgmalli/registry "public"))
```

## The builder

pgmalli types rows as one builder builds them: next.jdbc's `as-unqualified-maps`. Pass it to
next.jdbc wherever your application reads rows:

```clojure
(require '[next.jdbc.result-set :as rs])

(def jdbc-opts {:builder-fn rs/as-unqualified-maps})
```

A row's keys are its columns' names as PostgreSQL labels them, unqualified (`:group_id`), and a
NULL column keeps its key. A query's row has the keys of its labels likewise
([queries](queries.md#the-row-a-query-returns)), and a row read is written back as it is
([a row written into a table](queries.md#a-row-written-into-a-table)). Another spelling, such
as kebab-case keys, is your application's to convert where it takes the row.

With no extension, dates and timestamps are `java.sql.Date` and
`java.sql.Timestamp` (`inst?`), and arrays, json, intervals, `citext` and `bit varying` are the
driver's own objects (`:any`). `real` is a `Float`, `bit(1)` a `Boolean`, and a numeric `NaN` a
`Double`. `time` and `timetz` are a `java.sql.Time` under every `:time`: `next.jdbc.date-time`
converts dates and timestamps alone:

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
| `:jdbc {...}` | the options you pass to next.jdbc: pgmalli reads `:return-keys` ([queries](queries.md#options)) |
| `:time :inst` | the default: dates and timestamps as `java.sql.Date` and `Timestamp` (`inst?`) |
| `:time :instant` | `next.jdbc.date-time/read-as-instant` |
| `:time :local` | `next.jdbc.date-time/read-as-local` |
| `:read {pg-type schema}` | what your own `ReadableColumn` extension returns for a column type |
| `:read {:array :vector}` | an extension returning arrays as vectors (`:sequential` for any sequence) |
| `:read {:json :parsed}` | an extension returning json parsed: a json column's schema, as a CHECK or an [override](../README.md#overrides) shapes it |
| `:read {:enum :keyword}` | an extension returning an enum type's value as a keyword: its labels as keywords, of a domain over one too |

An option pgmalli does not know is an error that suggests the intended name:

```clojure
(try (pgmalli/as-read registry :pg.public/users {:tmie :instant})
     (catch clojure.lang.ExceptionInfo e (ex-message e)))
;; => "pgmalli options: {:tmie [\"disallowed key\"]}; did you mean: :time"
```

## The options of next.jdbc

pgmalli passes over the keys of `:jdbc` it does not read, so the map your application passes
to next.jdbc goes as it is. `:builder-fn` there is `as-unqualified-maps` (the function, its
var, or its symbol, which names it where next.jdbc does not load, as in babashka); another
builder, `:label-fn` or `:qualifier-fn` is an error naming the one builder. A key spelled close
to one pgmalli reads and read by no next.jdbc function (`:return-key`) is an error naming the
key meant.

## Your own extensions

A `ReadableColumn` extension changes what a column type comes back as. Key it by the
`:pg/type` and give the schema of the value returned:

```clojure
(-> (pgmalli/as-read registry :pg.public/users {:read {"timestamp with time zone" :time/instant}})
    (m/schema {:registry registry})
    (mu/get :created_at)
    (m/form))
;; => :time/instant
```

An extension that reads every enum type's value as a keyword, as `(keyword s)` makes it, is
`{:read {:enum :keyword}}`:

```clojure
(-> (pgmalli/as-read registry :pg.public/users {:read {:enum :keyword}})
    (m/schema {:registry registry})
    (mu/get :status)
    (m/form))
;; => [:enum {:default :active, :pg/default "active", :pg/type "user_status"} :active :closed]
```

## Decoding instead

`transformer` turns JDBC values into the registry's types, for an application that works
with `java.time`. It converts rows as `as-unqualified-maps` builds them, keyed as the
registry's row schemas are:

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

`:zone` (the JVM's by default) is the zone some values convert in:
`(pgmalli/transformer {:zone (java.time.ZoneId/of "UTC")})`.

- In a `timestamp` (without time zone) column, a `java.sql.Timestamp`, which pgjdbc returns,
  keeps its date and time and does not use `:zone`. A `java.util.Date` or an `Instant` converts
  in `:zone`.
- In a `date` column, a `java.sql.Date` keeps its date. A `java.sql.Timestamp`, a
  `java.util.Date` or an `Instant` converts in `:zone`.
