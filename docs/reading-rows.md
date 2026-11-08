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
(require '[next.jdbc :as jdbc]
         '[pgmalli-next.jdbc :as pgmalli-jdbc])

(pgmalli-jdbc/install-sending!)

(def jdbc-opts {:builder-fn (pgmalli-jdbc/builder registry)})

(jdbc/execute! ds (sql/format q) jdbc-opts)
```

`builder` is next.jdbc's `as-unqualified-maps`, each column read by its type as below. It
extends no protocol: what your application extends `next.jdbc.result-set/ReadableColumn` with
reads no column it builds. Pass it wherever your application reads rows. It reads an enum of
the registry it is given as a keyword: give it the registry the rows are typed with.

`install-sending!` extends next.jdbc's `SettableParameter` for the whole JVM, for four classes:
an `Instant` goes as a `timestamptz` (`Instant/MAX` and `MIN` as `infinity` and `-infinity`),
a keyword untyped as its text (`:ns/a` as `ns/a`), a map and a vector as `jsonb`, written with
data.json (an `Instant`, a `UUID` and java.time's dates and times as their ISO-8601 text). Every
other class goes as pgjdbc's `setObject` sends it ([what your application
sends](queries.md#what-your-application-sends)). Call it once, before sending arguments. An
application that sends its own way uses `builder` alone, and types its arguments from the facts
pgmalli gives for a sender of its own ([what your application
sends](queries.md#what-your-application-sends)).

A row's keys are its columns' names as PostgreSQL labels them, unqualified (`:group_id`), and a
NULL column keeps its key. A query's row has the keys of its labels likewise
([queries](queries.md#the-row-a-query-returns)). A row written into a table has the same keys
([a row written into a table](queries.md#a-row-written-into-a-table)): a column has one key,
read and written, so a row read is written back as it is. Another spelling, such as kebab-case
keys (`:group-id`, which HoneySQL writes to the same column), is your application's to convert,
both ways, where it takes and gives the row.

What each type comes back as:

| type | value |
|---|---|
| `timestamp` | `java.time.LocalDateTime`; `infinity` `LocalDateTime/MAX`, `-infinity` `MIN` |
| `timestamptz` | `java.time.Instant`; `infinity` `Instant/MAX`, `-infinity` `MIN` |
| `date` | `java.time.LocalDate`; `infinity` `LocalDate/MAX`, `-infinity` `MIN` |
| `time` | `java.time.LocalTime` |
| `timetz` | `java.time.OffsetTime` |
| an array | a vector of its elements, each read as above, NULL nil; a multi-dimensional array a vector of vectors, which pgmalli types as one-dimensional (PostgreSQL's type does not say the dimensions) |
| `json`, `jsonb` | parsed: an object a map of keyword keys, an array a vector, `null` nil, an integer a `Long` (a `BigInt` past it), a fraction a `Double`, booleans and strings as they are |
| an enum type, a domain over one | its label, a keyword (`:active`), of an enum type of the registry `builder` is given; another enum's a `String` |
| every other type | as pgjdbc returns it: `real` a `Float`, `bit(1)` a `Boolean`, a numeric `NaN` a `Double`, an `interval` a `PGInterval` (an interval holds months, days and a time at once, which neither a `Duration` nor a `Period` holds alone), `citext`, `bit(n)` (n > 1), `bit varying`, `inet` and the other types of `[:any]` a `PGobject` (`hstore` a `java.util.Map`) |

`builder` reads a column by its type, which next.jdbc's builders see where they build a row
(`execute!`, `execute-one!`). `plan`'s `(:col row)` reads the column as pgjdbc returns it (a
`java.sql.Timestamp`, a `java.sql.Array`, a json's `PGobject`): build the row with the builder
there (`(rs/datafiable-row row ds jdbc-opts)`), or convert the column where your application
knows its type.

A json or jsonb column's schema is the parsed value's, as an
[override](../README.md#overrides) shapes it: one override for what is read and what is
written. An override that types a JSON number takes what a JSON parser returns: an integer
`integer?`, a fraction `double?`: data.json, which `builder` parses with, gives a `Long` or a
`BigInt` and a `Double`.

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
to next.jdbc goes as it is. `:builder-fn` there is a builder `pgmalli-next.jdbc/builder` made, or
the symbol `pgmalli-next.jdbc/builder`, which names it where pgmalli-next.jdbc does not load (in
babashka, which has no pgjdbc); another builder, `:label-fn` or `:qualifier-fn` is an error
naming the one builder. A key spelled close
to one pgmalli reads and read by no next.jdbc function (`:return-key`) is an error naming the
key meant.
