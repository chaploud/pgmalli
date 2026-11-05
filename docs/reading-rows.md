# Reading rows through next.jdbc

Part of [pgmalli](../README.md): [types](types.md) · [reading rows](reading-rows.md) · [queries](queries.md)

`pgmalli-next.core/as-read` gives the `[:map ...]` of a row as next.jdbc builds it from what
PostgreSQL's JDBC driver returns, read with next.jdbc's `as-unqualified-maps`. The examples use [example/schema.sql](example/schema.sql).

```clojure
(require '[pgmalli-next.core :as pgmalli]
         '[malli.core :as m])

(def registry (pgmalli/registry))
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
([queries](queries.md#the-row-a-query-returns)). A row written into a table has the same keys
([a row written into a table](queries.md#a-row-written-into-a-table)): a column has one key,
read and written, so a row read is written back as it is. Another spelling, such as kebab-case
keys (`:group-id`, which HoneySQL writes to the same column), is your application's to convert,
both ways, where it takes and gives the row.

## The one reading and sending

pgmalli types rows as one reading makes them and arguments as one sending sends them, which
your application installs: pgmalli ships no next.jdbc extension. Install the one below once,
before reading rows or sending arguments (it extends next.jdbc's protocols for the whole JVM):

```clojure
(require '[clojure.data.json :as json]
         '[next.jdbc.prepare :as prepare]
         '[next.jdbc.result-set :as rs])
(import '(java.sql PreparedStatement ResultSetMetaData Types)
        '(java.time Instant LocalDate LocalDateTime OffsetDateTime ZoneOffset)
        '(org.postgresql PGStatement)
        '(org.postgresql.util PGobject))

;;; reading

(defn- json-type? [type-name] (contains? #{"json" "jsonb"} type-name))

(defn- parse-json [s] (json/read-str s :key-fn keyword))

(defn- read-pgobject [^PGobject v]
  (if (json-type? (.getType v)) (parse-json (.getValue v)) v))

(def ^:private object-array-class (class (object-array 0)))

(defn- read-array [^java.sql.Array v read]
  ;; pgjdbc returns a json[]'s elements as their text, a multi-dimensional array's as arrays
  (let [json? (json-type? (.getBaseTypeName v))
        dim (fn dim [a]
              (mapv #(cond (instance? object-array-class %) (dim %)
                           (and json? (string? %)) (parse-json %)
                           :else (read %))
                    a))]
    (dim (.getArray v))))

(defn- read-time [^java.sql.Time v ^ResultSetMetaData rsmeta i]
  ;; pgjdbc returns a time and a timetz alike, the timetz's offset taken off: the column's type tells them
  (if (contains? #{"time" "_time"} (.getColumnTypeName rsmeta i)) (.toLocalTime v) v))

(defn- read-timestamp [^java.sql.Timestamp v ^ResultSetMetaData rsmeta i]
  ;; pgjdbc returns a timestamp and a timestamptz alike: a timestamptz's instant, a timestamp's
  ;; date and time taken in the JVM's time zone, which toLocalDateTime gives back; infinity as
  ;; a time of its own, which pgjdbc's java.time reading reads as MAX (-infinity MIN)
  (let [local? (contains? #{"timestamp" "_timestamp"} (.getColumnTypeName rsmeta i))]
    (condp = (.getTime v)
      PGStatement/DATE_POSITIVE_INFINITY (if local? LocalDateTime/MAX Instant/MAX)
      PGStatement/DATE_NEGATIVE_INFINITY (if local? LocalDateTime/MIN Instant/MIN)
      (if local? (.toLocalDateTime v) (.toInstant v)))))

(defn- read-date [^java.sql.Date v]
  (condp = (.getTime v)
    PGStatement/DATE_POSITIVE_INFINITY LocalDate/MAX
    PGStatement/DATE_NEGATIVE_INFINITY LocalDate/MIN
    (.toLocalDate v)))

(extend-protocol rs/ReadableColumn
  java.sql.Timestamp
  (read-column-by-label [v _] v)
  (read-column-by-index [v rsmeta i] (read-timestamp v rsmeta i))
  java.sql.Date
  (read-column-by-label [v _] (read-date v))
  (read-column-by-index [v _ _] (read-date v))
  java.sql.Time
  (read-column-by-label [v _] v)
  (read-column-by-index [v rsmeta i] (read-time v rsmeta i))
  java.sql.Array
  (read-column-by-label [v label] (read-array v #(rs/read-column-by-label % label)))
  (read-column-by-index [v rsmeta i] (read-array v #(rs/read-column-by-index % rsmeta i)))
  PGobject
  (read-column-by-label [v _] (read-pgobject v))
  (read-column-by-index [v _ _] (read-pgobject v)))

;;; sending: pgjdbc's setObject, which sends java.time's types typed (a LocalDateTime as a
;;; timestamp, a LocalDate as a date, an OffsetDateTime as a timestamptz), an Instant as an
;;; OffsetDateTime in UTC

;; data.json writes an Instant and a UUID as JSON strings; a LocalDate, a LocalDateTime, a
;; LocalTime, an OffsetDateTime and an OffsetTime go as their ISO-8601 text here
(defn- write-temporal [x ^java.io.Writer out _options]
  (if (instance? java.time.temporal.Temporal x)
    (json/write (str x) out)
    (throw (Exception. (str "Don't know how to write JSON of " (class x))))))

(defn- jsonb [x] (doto (PGobject.) (.setType "jsonb") (.setValue (json/write-str x :default-write-fn write-temporal))))

(extend-protocol prepare/SettableParameter
  Instant
  (set-parameter [v ^PreparedStatement s ^long i] (.setObject s i (OffsetDateTime/ofInstant v ZoneOffset/UTC)))
  clojure.lang.Keyword
  (set-parameter [k ^PreparedStatement s ^long i] (.setObject s i (subs (str k) 1) Types/OTHER))
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s ^long i] (.setObject s i (jsonb m)))
  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s ^long i] (.setObject s i (jsonb v))))
```

What each type comes back as:

| type | value |
|---|---|
| `timestamp` | `java.time.LocalDateTime`; `infinity` `LocalDateTime/MAX`, `-infinity` `MIN` |
| `timestamptz` | `java.time.Instant`; `infinity` `Instant/MAX`, `-infinity` `MIN` |
| `date` | `java.time.LocalDate`; `infinity` `LocalDate/MAX`, `-infinity` `MIN` |
| `time` | `java.time.LocalTime` |
| `timetz` | `java.sql.Time` (`inst?`), as pgjdbc returns it: pgjdbc takes the offset off, and the extension cannot put it back |
| an array | a vector of its elements, each read as above, NULL nil; a multi-dimensional array a vector of vectors, which pgmalli types as one-dimensional (PostgreSQL's type does not say the dimensions). pgjdbc cannot read the elements of a `money[]` or a `bit(n)[]` (n > 1): reading one throws; select it cast to `text[]` |
| `json`, `jsonb` | parsed: an object a map of keyword keys, an array a vector, `null` nil, an integer a `Long` (a `BigInt` past it), a fraction a `Double`, booleans and strings as they are |
| an enum type | its label, a `String` |
| every other type | as pgjdbc returns it: `real` a `Float`, `bit(1)` a `Boolean`, a numeric `NaN` a `Double`, an `interval` a `PGInterval` (an interval holds months, days and a time at once, which neither a `Duration` nor a `Period` holds alone), `citext`, `bit(n)` (n > 1), `bit varying`, `inet` and the other types of `[:any]` a `PGobject` (`hstore` a `java.util.Map`) |

A `timestamp` and a `timestamptz` (pgjdbc's `java.sql.Timestamp` both) and a `time` and a
`timetz` (its `java.sql.Time` both) are told apart by the column's type, which the extension sees
where a column is read by index, as next.jdbc's builders read a row (`execute!`,
`execute-one!`). `plan`'s `(:col row)` reads by label, which leaves the `java.sql.Timestamp` and
the `java.sql.Time`: build the row with a builder there (`(rs/datafiable-row row ds {:builder-fn
rs/as-unqualified-maps})`), or convert the column where your application knows its type.

A json or jsonb column's schema is the parsed value's, as an
[override](../README.md#overrides) shapes it: one override for what is read and what is
written. An override that types a JSON number takes what a JSON parser returns: an integer
`integer?`, a fraction `double?`: data.json, as here, gives a `Long` or a `BigInt` and a
`Double`; jsonista an `Integer`, a `Long` or a `BigInteger` and a `Double`.

```clojure
(m/validate (pgmalli/as-read registry :pg.public/users)
            {:id 1 :group_id 1 :email "ann@example.com" :nick nil :status "active"
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
initialization SQL). A `timestamp`'s date and time the JVM's zone skips (02:30 on a day
`America/New_York` moves its clocks forward) reads an hour later: pgjdbc gives the extension a
`java.sql.Timestamp` taken in that zone, and only a result-set builder reading the column with
`getObject(i, LocalDateTime)` would not.

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
to next.jdbc goes as it is. `:builder-fn` there is `as-unqualified-maps` (the function, its
var, or its symbol, which names it where next.jdbc does not load, as in babashka); another
builder, `:label-fn` or `:qualifier-fn` is an error naming the one builder. A key spelled close
to one pgmalli reads and read by no next.jdbc function (`:return-key`) is an error naming the
key meant.
