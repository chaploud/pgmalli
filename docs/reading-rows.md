# Reading rows through next.jdbc

Part of [pgmalli](../README.md): [types](types.md) · [reading rows](reading-rows.md) · [queries](queries.md) · [datasets](datasets.md)

`pgmalli.core/as-read` gives the `[:map ...]` of a row as next.jdbc builds it from what
PostgreSQL's JDBC driver returns. The examples use [example/schema.sql](example/schema.sql).

```clojure
(require '[pgmalli.core :as pgmalli]
         '[malli.core :as m]
         '[malli.util :as mu])

(def registry (pgmalli/registry "public"))
```

## The default

With no options, the row is what next.jdbc's default builder, `as-maps`, builds with no
extension: keys are `:table/column`, dates and timestamps are `java.sql.Date` and
`java.sql.Timestamp` (`inst?`), and arrays, json, intervals, `citext` and `bit varying` are the
driver's own objects (`:any`). `real` is a `Float`, `bit(1)` a `Boolean`, and a numeric `NaN` a
`Double`. `time` and `timetz` are a `java.sql.Time` under every `:time`: `next.jdbc.date-time`
converts dates and timestamps alone:

```clojure
(m/validate (pgmalli/as-read registry :pg.public/users)
            #:users{:id 1 :group_id 1 :email "ann@example.com" :nick nil :status "active"
                    :closed_at nil :created_at (java.sql.Timestamp. 0)}
            {:registry registry})
;; => true
```

## Options

| option | for |
|---|---|
| `:jdbc {...}` | the options you pass to next.jdbc: its `:builder-fn` keys the row (`as-maps` by default), with `:label-fn` and `:qualifier-fn` under a modified builder |
| `:time :inst` | the default: dates and timestamps as `java.sql.Date` and `Timestamp` (`inst?`) |
| `:time :instant` | `next.jdbc.date-time/read-as-instant` |
| `:time :local` | `next.jdbc.date-time/read-as-local` |
| `:read {pg-type schema}` | what your own `ReadableColumn` extension returns for a column type |
| `:read {:array :vector}` | an extension returning arrays as vectors (`:sequential` for any sequence) |
| `:read {:json :parsed}` | an extension returning json parsed |

An option pgmalli does not know is an error that suggests the intended name:

```clojure
(try (pgmalli/as-read registry :pg.public/users {:tmie :instant})
     (catch clojure.lang.ExceptionInfo e (ex-message e)))
;; => "pgmalli options: {:tmie [\"disallowed key\"]}; did you mean :time?"
```

## Keys from a next.jdbc builder

Pass the options you pass to next.jdbc as `:jdbc`. Its `:builder-fn` is one of next.jdbc's map
builders (`next.jdbc.result-set`'s and `next.jdbc.optional`'s), which keys the row as next.jdbc
does: unqualified, kebab-case or lower-case keys, a NULL column left out under
`next.jdbc.optional`, keys named by your `:label-fn` and `:qualifier-fn` under a modified
builder. Another builder (an array builder, your own) is an error, as pgmalli cannot tell its
keys:

```clojure
(require '[next.jdbc.result-set :as rs]
         '[next.jdbc.optional :as opt]
         '[clojure.string :as str])

(pgmalli/describe registry (pgmalli/as-read registry :pg.public/posts {:jdbc {:builder-fn rs/as-unqualified-kebab-maps}}))
;; => {:author-id "bigint", :id "bigint", :likes "integer", :title "varchar(200)", :title-upper "text?", :views "integer"}

(pgmalli/describe registry (pgmalli/as-read registry :pg.public/posts {:jdbc {:builder-fn rs/as-modified-maps
                                                                              :label-fn str/upper-case
                                                                              :qualifier-fn #(str "app." %)}}))
;; => #:app.posts{:AUTHOR_ID "bigint", :ID "bigint", :LIKES "integer", :TITLE "varchar(200)", :TITLE_UPPER "text?", :VIEWS "integer"}

(pgmalli/describe registry (pgmalli/as-read registry :pg.public/users {:jdbc {:builder-fn opt/as-maps}}))
;; => #:users{:closed_at "timestamptz (optional)", :created_at "timestamptz", :email "varchar(120)", :group_id "integer", :id "bigint", :nick "varchar(40) (optional)", :status "user_status: active | closed"}
```

Where next.jdbc does not load (babashka), name the builder by its qualified symbol:
`{:jdbc {:builder-fn 'next.jdbc.result-set/as-unqualified-maps}}`.

## Your own extensions

A `ReadableColumn` extension changes what a column type comes back as. Key it by the
`:pg/type` and give the schema of the value returned:

```clojure
(-> (pgmalli/as-read registry :pg.public/users {:read {"timestamp with time zone" :time/instant}})
    (m/schema {:registry registry})
    (mu/get :users/created_at)
    (m/form))
;; => :time/instant
```

## Decoding instead

`transformer` turns JDBC values into the registry's types, for an application that works
with `java.time`. It converts rows with unqualified keys, as `as-unqualified-maps` builds them:
the registry's row schemas have unqualified keys, so next.jdbc's default `#:users{...}` rows
are left as they are:

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
