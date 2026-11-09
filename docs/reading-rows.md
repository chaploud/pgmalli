# Reading rows through next.jdbc

Part of [pgmalli](../README.md): [types](types.md) · [reading rows](reading-rows.md) · [queries](queries.md)

`pgmalli-next.core/as-read` gives the `[:map ...]` of a row as `pgmalli-next.jdbc/builder` builds
it from what PostgreSQL's JDBC driver returns. The examples use [example/schema.sql](example/schema.sql).

```clojure
(require '[pgmalli-next.core :as pgmalli]
         '[malli.core :as m])

(def registry (pgmalli/registry))
```

## The one reading and sending

pgmalli types rows as one reading builds them and arguments as one sending sends them, both in
`pgmalli-next.jdbc`, which needs next.jdbc and pgjdbc in your application:

<!-- :test-doc-blocks/skip -->
```clojure
(require '[honey.sql :as sql]
         '[next.jdbc :as jdbc]
         '[pgmalli-next.jdbc :as pgmalli-jdbc])

(pgmalli-jdbc/install-sending!)

(def jdbc-opts {:builder-fn (pgmalli-jdbc/builder registry)})

(jdbc/execute! ds (sql/format q) jdbc-opts)
```

`builder` takes the generated data too (`(builder (edn/read-string (slurp "api/pgmalli-next.edn")))`):
an application that reads rows alone, without checking queries, loads no more than
`pgmalli-next.jdbc` (no `pgmalli-next.core`, nor the analysis). `builder` is next.jdbc's
`as-unqualified-maps`, each column read by its type as below. It
extends no protocol: what your application extends `next.jdbc.result-set/ReadableColumn` with
reads no column it builds. Pass it wherever your application reads rows.

`install-sending!` extends next.jdbc's `SettableParameter` for the whole JVM, for `Instant`,
keywords, maps and vectors, sent as [what your application
sends](queries.md#what-your-application-sends) lists: `Instant/MAX` and `MIN` as `infinity` and
`-infinity`, a map and a vector written with jsonista (an `Instant` and java.time's dates and
times as their ISO-8601 text, a `UUID`, a keyword and a `Character` as their text; a document JSON cannot hold
an error: [two keys of one text](queries.md#stricter-json-keys), a nil key, a NaN, an infinity, a
value of another class, a `PGobject`). Every other class goes as
pgjdbc's `setObject` sends it. `(install-sending! {:refused [java.time.LocalDateTime]})` makes classes your application does not send (a date and time of no time zone) an error to send, and no argument takes one. Call it once, before sending arguments and before analyzing queries: without it, pgjdbc refuses
a keyword and a vector (a `PSQLException` of SQLState `07006`, which names the class) and sends a
map as `hstore` (an error naming hstore where that extension is not installed). It throws where another extension already holds a class pgmalli sends
(`next.jdbc.date-time`'s `LocalDate`), which would go otherwise than pgmalli types it; one made
after it is not seen, so call it after loading the namespaces that extend `SettableParameter`. An application that sends its own way uses `builder` alone,
and types its arguments from the facts pgmalli gives a sender of its own ([what your
application sends](queries.md#what-your-application-sends)).

A row's keys are its columns' names as PostgreSQL labels them, unqualified (`:group_id`), and a
NULL column keeps its key. A query's row has the keys of its labels likewise
([queries](queries.md#the-row-a-query-returns)). A row written into a table has the same keys
([a row written into a table](queries.md#a-row-written-into-a-table), which lists the values
written otherwise than read): a column has one key, read and written. Another spelling, such as kebab-case
keys (`:group-id`, which HoneySQL writes to the same column), is your application's to convert,
both ways, where it takes and gives the row.

A row read through another builder is not the one `as-read` types: next.jdbc's default qualifies
every key (`:users/id`, each missing), and its values are pgjdbc's classes (a `java.sql.Timestamp`,
an enum a `String`). Read it through `builder`.

What each type comes back as:

| type | value |
|---|---|
| `timestamp` | `java.time.LocalDateTime`; `infinity` `LocalDateTime/MAX`, `-infinity` `MIN` |
| `timestamptz` | `java.time.Instant`; `infinity` `Instant/MAX`, `-infinity` `MIN` |
| `date` | `java.time.LocalDate`; `infinity` `LocalDate/MAX`, `-infinity` `MIN` |
| `time` | `java.time.LocalTime`; `24:00` `LocalTime/MAX` |
| `timetz` | `java.time.OffsetTime`; `24:00` `OffsetTime/MAX`, as pgjdbc reads it, its offset lost (`-18:00`), which PostgreSQL refuses written back |
| an array | a vector of its elements, each read as above, NULL nil; a multi-dimensional array a vector of vectors, which pgmalli types as one-dimensional (PostgreSQL's type does not say the dimensions); an array of a domain's elements as pgjdbc returns them, `PGobject`s of the domain |
| `money` | a `PGobject` of `money`, its text (`$1,000.50`, as the server's `lc_monetary` writes it), which pgjdbc's `Double` neither holds exactly nor reads past 999.99 |
| `json`, `jsonb` | parsed: an object a map of keyword keys, an array a vector, `null` nil, an integer an `Integer`, a `Long` past it, a `BigInteger` past that (jsonista's), a fraction a `Double`, booleans and strings as they are; a document nested deeper than the thread's stack holds a `:pgmalli.error/fault` naming the column |
| an enum type, a domain over one | its label, a keyword (`:active`), of an enum type of the registry `builder` is given; another enum's a `String` |
| `interval` | whole: of years, months and days alone a `Period`, of a time alone a `Duration` (its fraction and sign kept), of both (`1 day 02:00`, whose day is no 24 hours across a change of offset) pgjdbc's `PGInterval` |
| every other type | as pgjdbc returns it: `real` a `Float`, `bit(1)` a `Boolean`, a numeric `NaN` or infinity a `Double`, `citext`, `bit(n)` (n > 1), `bit varying`, `inet` and the other types of `[:any]` a `PGobject` (`hstore` a `java.util.Map`, `xml` a `java.sql.SQLXML`) |

`builder` reads where next.jdbc builds a row (`execute!`, `execute-one!`). `plan`'s `(:col
row)` builds none and reads the column as pgjdbc returns it (a `java.sql.Timestamp`, a
`java.sql.Array`, a json's `PGobject`, an enum's `String`): pass `jdbc-opts` to `plan` and build
the row there (`(next.jdbc.result-set/datafiable-row row ds {})`), or convert the column where
your application knows its type.

Under a [shape](../README.md#column-shapes), an enum a json column's shape refers to holds its
labels as strings, as JSON does, and a JSON number is what `builder` parses (above): an integer
`integer?`, a fraction `double?`.

```clojure
(m/validate (pgmalli/as-read registry :pg.public/users)
            {:id 1 :group_id 1 :email "ann@example.com" :nick nil :status :active
             :closed_at nil :created_at (java.time.Instant/EPOCH)}
            {:registry registry})
;; => true
```

### The session's time zone

pgjdbc sets the session's time zone to the JVM's when it connects; a URL's `options=-c
TimeZone=UTC` does not change it, as pgjdbc's own setting comes after. A date and time sent and
read as above is the same whatever that zone is, but SQL that converts between a `timestamptz`
and a `timestamp` or a `date` converts in it (`created_at::date`, `date_trunc('day',
created_at)`, a `timestamptz` compared with a `date`), so where days begin follows the JVM's
zone. Fix it where your application connects, in one of two ways: run the JVM with
`-Duser.timezone=UTC`, or run `SET TIME ZONE 'UTC'` once connected (a connection pool's
initialization SQL).

## Options

| option | for |
|---|---|
| `:schema "app"` | `pgmalli/as-read`'s: the schema of a table named without one (`:users`) |
| `:jdbc {...}` | the options you pass to next.jdbc ([below](#the-options-of-nextjdbc)); `:return-keys`, a write's RETURNING, is an error here |

There is no option for how a value is read or sent.

An option pgmalli does not know is an error that suggests the intended name:

```clojure
(try (pgmalli/as-read registry :pg.public/users {:jdcb {}})
     (catch clojure.lang.ExceptionInfo e (ex-message e)))
;; => "pgmalli options: {:jdcb [\"disallowed key\"]}; did you mean: :jdbc"
```

## The options of next.jdbc

pgmalli passes over the keys of `:jdbc` it does not read, so the map your application passes
to next.jdbc goes as it is. `:builder-fn` there is one `pgmalli-next.jdbc/builder` returned; one
of a registry of other enums (which reads an enum it lacks as a `String`), another builder,
`:label-fn` or `:qualifier-fn` is an error. A key spelled close
to one pgmalli reads and read by no next.jdbc function (`:return-key`) is an error naming the
key meant.
