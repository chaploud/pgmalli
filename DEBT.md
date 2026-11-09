# Debt

What pgmalli-next knows it gets wrong and has not fixed yet. This repository is a temporary
branch of pgmalli: these would be issues there. Move them to issues, or delete this file, when the
work goes back.

Each lets through something PostgreSQL refuses (it fails when the query runs), refuses
something PostgreSQL takes, leaves a type untold, or tells a type other than the value read. Fix one, then delete its entry.

- An enum named `text`, `name` or `varchar` in a schema on the session's search_path reads as a
  `String` (`pgmalli-next.jdbc/builder`), while its row type says keyword labels: pgjdbc names it
  as it names that pg_catalog type, both a `VARCHAR`, and tells no type OID through
  `ResultSetMetaData`. Off the search_path it is named `"schema"."text"` and reads as keywords.
- A column of a `NOT NULL NOT VALID` constraint (PostgreSQL 18) is nullable, as rows from
  before it may hold NULL, so a write takes nil there too, which PostgreSQL refuses.
- A map or a vector `pgmalli-next.jdbc/jsonb` marks to a `json` or `jsonb` column is not checked for what the one sending
  refuses to write (accept/json-problem): two keys of one text (`:a` and `"a"`), a nil key inside
  the document (the map's own is refused), a NaN or an infinity, a value of a class JSON has no form for (a `PGobject`). Sending it is an error of `:pgmalli.error/invalid-options`.
- A column of a pg_catalog type `types/types` does not list (`refcursor`, `aclitem`, the ones
  upstream_test's `unsupported` names) takes any value: PostgreSQL refuses a `varchar` there.
- `as-read` refuses a multi-dimensional array `builder` reads (`[[1 2] [3 4]]`): it types an
  array as one-dimensional, as PostgreSQL's type does not say the dimensions.
- An `OffsetTime` or `OffsetDateTime` of an offset past ±15:59, and a `LocalDate`,
  `LocalDateTime` or `Instant` past PostgreSQL's range (other than the MAX and MIN that mean
  infinity), are taken: PostgreSQL refuses the one past its maximum and stores the one before its
  minimum as `-infinity`.
- Under `{:format {:inline true}}`, a `String` compared with a column of an alias of `oid`
  (`regclass`) is taken: PostgreSQL resolves `regclass = unknown` as `oid = oid`, reading the
  text as a number.
- A temporal foreign key (PostgreSQL 18, `PERIOD`) reads as `:pg/foreign-keys` of its columns,
  equality of each, the period's overlap not told.
- A composite type of a pg_catalog type's name (`public."date"`) reads as a `PGobject` its
  type named as pgjdbc names it (`"date"`), which PostgreSQL takes back as `pg_catalog`'s type and
  refuses.
- A `jsonb_populate_record` document's array of more dimensions than one (`{:ia [[1 2] [3 4]]}`)
  is refused, which PostgreSQL reads: its type is of one dimension, as `as-read`'s.
- A `PGobject` is taken wherever one is, whatever type its `getType` names (one of `point` to an
  `inet` column, of `jsonb` beside a `json` one in `coalesce`), which PostgreSQL refuses.
- An argument of `lower(?)` or `upper(?)`, overloaded on text and on ranges, is untyped, and
  takes what PostgreSQL refuses (an `Integer`: `lower(integer)` does not exist).
- A `character` column of no length (`char`, which is `char(1)`) takes a `String` of any length,
  and an array of a type of a length (`varchar(5)[]`, `char(3)[]`) elements of any length: no
  schema checks a length, which generation alone keeps to (`:pg/max-length`, the array's for its
  elements). PostgreSQL refuses the value (`value too long`).
- An array of a type pgmalli does not type (`ltree[]`, `isbn13[]`) takes a vector of any
  elements, sent untyped, whose text the type's input may refuse (`{"a b"}` to `ltree[]`);
  values-schema generates no value of it.
- `values-schema` and `set-schema` refuse a vector of two values or more to a column of a string
  type (`{:nick [1 2]}`), which HoneySQL writes as a row, `(?, ?)`, and PostgreSQL takes as the
  row's text (`(1,2)`); `analyze` takes it.
- A `real[]` column's elements are held neither to a real's range nor, assigned, to a `Float`:
  a `Double[]` or a `BigDecimal[]` of `1e300` is taken, which PostgreSQL refuses (out of range),
  and a `Double[]` of `0.1`, which it rounds (a scalar `real` refuses both). A `double precision`
  column takes a `BigDecimal` past its range (`1e400M`).
- An INSERT of a query that is a VALUES (`{:insert-into [[:t [:o]] {:values [[:?x]]}]}`, which
  HoneySQL writes as `{:insert-into :t :columns [:o] :values [[:?x]]}` is) is read as
  INSERT ... SELECT: its values are not typed by the columns, and a NOT NULL or required column
  is not checked.
- A `BigDecimal` or a `BigInt` beside a `real` column in `coalesce` or CASE, which PostgreSQL
  resolves to `real`, is not held to a real's range.
- A call named as a type of a parameter (`[:bool :?x]`, `int4(?)`) is typed as a cast, so it
  takes nil and a keyword, while PostgreSQL resolves the call's overloads for an untyped
  parameter (`function bool(unknown) is not unique`; `date(?)` is `date(timestamptz)`).
- A call named as a type PostgreSQL's grammar keeps as a keyword (`[:int :n]`, `:numeric`,
  `:varchar`, `:timestamp`), which HoneySQL writes unquoted and PostgreSQL does not parse, is
  typed as a cast.
- A cast PostgreSQL added in a version later than the registry's (`bytea` to `integer`, 18) is
  taken: `types/cast-contexts` holds every version's.
