# Changelog

pgmalli follows [semantic versioning](https://semver.org). Before 1.0, a minor release may
break what [Stability](README.md#stability) lists; its entry says what to do.

## Unreleased

* `:send` says what an application sends, as pgjdbc's `setObject` decides it by class: a map
  of classes to the type each goes as (a name or alias, an array type `"bigint[]"`, or
  `:unknown` for a value sent untyped), merged over pgjdbc's; `nil` takes a class out. pgjdbc's
  own include its boxed arrays (`Long[]`, `Integer[]` ...), `BigDecimal[]`, `UUID[]` and the
  arrays of java.sql's dates, times and timestamps. A class adds to a column's own values where
  PostgreSQL takes its type as the column's own (a narrower number, another string type, an
  untyped value to a text or enum column), and in a cast all PostgreSQL casts, an array too (to
  text, say). A number is held to a number column's checks whatever its class; a class sent as
  a text type (a keyword as `varchar`) to none of the column's checks on text, which PostgreSQL
  runs on the text an application makes of it.
* An array argument takes a Clojure collection sent as an array type or `:unknown` (or a
  collection interface `:send` names, `{java.util.List :unknown}`, each collection implementing
  it) and a Java array, typed by its elements (`[:pg/array element]`): compared with
  `= ANY(?)`, assigned to an array column, or under `unnest`, which takes a typed array alone.
  An enum type's array takes a typed array of that type alone; a reg* array no integer. A typed
  array of a narrower number (`"bigint[]"`) goes to a `numeric[]`, `real[]` or
  `double precision[]` column, and an integer to a `numeric(p,s)` one, as to a `numeric` one. A
  domain over an enum takes no untyped value compared: PostgreSQL has no `=` for it.
* `:jdbc` takes the options an application passes to next.jdbc: `:builder-fn` (one of
  next.jdbc's map builders, as the function, its var, or its qualified symbol where next.jdbc
  does not load) keys rows as next.jdbc does, and under a modified builder `:label-fn` and
  `:qualifier-fn` too (functions: a symbol is an error); `:return-keys` adds RETURNING to a write.
* `h/values-schema` and `h/set-schema` type a whole row written into a table, for `:values` and
  `:set` built at run time, keyed as the `:jdbc` builder keys a row where HoneySQL writes that
  key back to the column; `h/set-schema` takes a non-empty map. They take no keyword but
  `:default`, nor a vector or a sequence: HoneySQL writes a keyword as a column and a vector as a
  row of parameters, whatever `:send` sends. Where the format quotes names, HoneySQL writes
  `:default` as the column `"default"`, so they take `[:default]` alone there. Where a BEFORE row
  trigger runs for the write, they and an assigned argument take a column left out or NULL, as
  `h/check` already did.
* `h/arg-types` types an argument under a cast (`[:cast :?at :timestamptz]`) with what
  PostgreSQL casts to the type: a string too, to an array type any array, and an enum type's
  labels alone. Assigned or cast to an integer type, a fraction is held to the range half a unit
  wider, as PostgreSQL rounds it.
  An argument of `coalesce` and the like takes the type the other arguments resolve to, and a
  comparison in a select or ORDER BY expression types its argument, which `h/check` reads too.
* An argument takes nil where any of its uses does, whatever their order; in `:limit` or
  `:offset` (none); under `{:compare :lenient}` the elements of one in `:in` or `= ANY` too.
* `h/row-schema` types `coalesce`, `greatest`, `least` and `CASE` of values of one type, or of
  numbers the widest of them (an `integer` and a `bigint` a `bigint`); `nullif` of two of one
  type, as `=` reads it. A string among them takes the others' type; an argument leaves the
  expression untyped unless the type is a string type, as pgjdbc sends numbers and dates typed.
  `CASE` without `:else` may be NULL. An array through them, `min` or `max` is the driver's
  `java.sql.Array`, as the column is, unless `:read` says otherwise. `min` and `max` read a
  domain as its base type, and a `varchar`, `"char"` or `name` as `text`, a `cidr` as `inet` and
  a reg* type as `oid`. `bool_and`, `bool_or` and `every` are `boolean`. A column cast to
  another type is keyed by no table, as an expression; one cast to its own type, which
  PostgreSQL drops, is under `:pgmalli/unknown-keys` under qualified keys. It also types a literal `[:inline ...]`
  writes, and the functions that build json and jsonb (`jsonb_build_object`, `jsonb_agg` ...) as
  a column of the type is read: an object or an array under `{:read {:json :parsed}}`. An
  unaliased CASE is named after its ELSE where that has a name of its own, as PostgreSQL names
  it.
* A cast to a type with its length (`[:cast :?x [:varchar 10]]`) has the type, in
  `h/row-schema` and `h/arg-types`.
* The columns of a CTE or a subquery are typed as its SELECT types them: a LATERAL subquery
  with the tables before it, a recursive CTE as its first member, NULL allowed and a CHECK's
  list read as its base type; a column it does not give is `:unknown-column`. Under qualified
  keys, a CTE's or a subquery's column is keyed by the table PostgreSQL traces it to (an expression's, a set
  operation's and a recursive CTE's by none), where it was under `:pgmalli/unknown-keys`. `SELECT *` of one lists
  its columns. A set operation's column is of the type PostgreSQL resolves its members' to (an
  integer and a bigint a bigint), where it was `:any` unless they agreed. A table function
  (`WITH ORDINALITY` too) is named by its alias. An argument of an `unnest` in `FROM` is an
  array of the type of where its column goes: compared, assigned by `INSERT ... SELECT` or
  `UPDATE ... SET`, or cast.
* `h/check` reports a string sent as any type a column does not take, not only varchar, and a
  string assigned to a reg* column other than regclass. It reads a literal against a CHECK's
  list of numbers as PostgreSQL does (an untyped `"1"` is the label `1`, a number outside the
  list an `:enum-literal`), an alias's column list as renaming the first columns alone
  (`AS u(a)` keeps the others' names), TRIM's column as `btrim`, and `coalesce` of a column
  with a CHECK's list as its base type.
* A domain whose CHECK lists its values (`CREATE DOMAIN lbl AS text CHECK (VALUE IN ('a',
  'b'))`) keeps its base type (`[:enum {:pg/type "text"} "a" "b"]`), and `h/check` no longer
  reads it as an enum type: a string sent as `varchar` to it is no `:uncast-string`.
* `h/query-schema`'s return says how many rows the data tells (`[:sequential {:max 1} row]`):
  one of a SELECT of aggregates alone, at most one where the WHERE holds a primary key or a
  UNIQUE constraint, neither partial nor DEFERRABLE, equal to a value, up to as many as an
  INSERT's `:values` has rows under RETURNING, at most `:limit`; where it is one,
  `{:result :one}` gives no `[:maybe]`.
* A table's map carries `:pg/inherited true` for a table other tables inherit (not as
  partitions), `:pg/primary-key-deferrable true`, and `:deferrable true` on a DEFERRABLE
  `:pg/unique` entry. An invalid unique index (a failed `CREATE INDEX CONCURRENTLY`) is no
  longer in `:pg/unique`.
* A table written in capitals without quoting (`:USERS`) keys a row by the name PostgreSQL
  folds it to (`:users/id`), as the driver returns it.
* `pgmalli/portable` leaves out a `numeric(p,s)`'s precision, which malli's types cannot say.
* `pgmalli.core/describe` refuses a type neither malli nor the registry knows.
* `:unknown-table` names the tables probably meant as `:did-you-mean`, and an option pgmalli does
  not take is an error whose data names it (`:unknown`) and the one meant (`:did-you-mean`). A
  name the registry lacks is an error listing its tables, views and types before the schemas
  derived from them.
* A domain over `bit`, `bit varying` or `numeric(p,s)` is read as the driver returns its base:
  a Boolean for `bit(1)`, a PGobject for another bit string, a Double too for a `numeric`'s NaN.
* `h/check` reads an assignment target `:a.b` as the field `b` of the composite column `a`,
  where it reported `:unknown-column`; the value is not checked.
* An argument compared with or assigned to a column the driver reads as a PGobject (`citext`,
  `bit(n)` for n > 1, `bit varying`, a domain over them), and `h/values-schema` and
  `h/set-schema`, take a PGobject too, so a value read is written back.
* A list or seq of keywords, vectors, maps and values alone (a `:select` built with `map`) is
  read as a vector, as HoneySQL reads it, where it was taken for code and left unchecked; a list
  with a symbol in it is still code.
* `h/row-schema`'s `:pgmalli/untold` property is `:pgmalli/unknown-keys`.
* An error or a problem names the one probably meant as `; did you mean: x`, where it said
  `; did you mean x?`, which doubled the `?` of a name ending in one (`:db?`).
* A schema name the registry lacks given unqualified (`:users`) or of another schema
  (`:pg.public/users`) is an error naming the registry's schema of that name
  (`:pg.sample/users`).
* `:unknown-table` lists a table of the same name in another schema first in `:did-you-mean`,
  and `h/humanize` says to pass its `:schema`.
* `:unknown-column` keeps the qualifier in `:did-you-mean` (`:u.nick` for `:u.nik`). A qualifier
  that names no table or alias of the statement is under `:qualifier`, with the alias probably
  meant (`:u.id` for `:users.id` under `[:users :u]`).
* `:db` in `pgmalli.edn` names the database `:dbname`, as next.jdbc and libpq do, where it was
  `:db`. A `:db` holding next.jdbc's `:dbtype` is an error saying to drop it.
* What to do:
  * In `pgmalli.edn`, `:db {:db "app"}` is `:db {:dbname "app"}`; the old key is an error
    naming the new one.
  * Code reading `:pgmalli/untold` off a row schema reads `:pgmalli/unknown-keys`.
  * Options: `{:stringtype :unspecified}` is `{:send {String :unknown}}`. `:write {"text" [:or
    :string :keyword]}` and `:write {:enum [:string :keyword]}` are `{:send
    {clojure.lang.Keyword :unknown}}` (or the type your extension sends a keyword as).
    `:write {:array [k ...]}` is `{:send {clojure.lang.IPersistentVector :unknown}}` and the
    like for the collections you send (or the array type your extension sends them as,
    `"bigint[]"`, which `unnest` needs). `:write {"T" s}` is the class of `s`'s values mapped
    to `"T"`, left out where pgjdbc sends it so already.
  * `:qualified? false`, `:kebab? true`, `:lower? true` and `:nil-columns :absent` are the
    builder you pass next.jdbc, as `{:jdbc {:builder-fn rs/as-unqualified-kebab-maps}}`;
    `:return-keys` moves into `:jdbc`. `read-options` is gone: pass the builder.
  * `h/explain` is `h/humanize`. An error's ex-data names the one probably meant as
    `:did-you-mean`, a vector, as `h/check`'s problems do, instead of `:suggest`.
  * The old options are an error saying what each is now, when the schema is built (a
    namespace whose `:malli/schema` passes one fails to load).
  * An instrumented function whose argument was `:any` under a cast or in a select or ORDER BY
    comparison, inside `coalesce` and the like or an `unnest` in FROM, or that reads a CTE's or
    a subquery's column, now has its type checked, and one whose return now says a row count
    has it checked; a query whose select or ORDER BY compares an unknown column or an enum with
    a literal outside its labels, that sends a string typed to a column not taking it, compares
    a CHECK's list of numbers with a value outside it, or reads a column a CTE does not give,
    now has problems.
  * Generate again if a schema has a table other tables inherit, a DEFERRABLE primary key or
    unique constraint, an invalid unique index, or a domain whose CHECK lists its values: their
    files change, and `pgmalli.main check` reports them stale.
  * Code reading the row of `h/query-schema`'s return by position (`(second ret)`) reads it with
    `last` or `m/children`: the return may carry properties (`[:sequential {:max 1} row]`).

## 0.3.3 (2026-11-03)

* `h/arg-types` types an argument where
  [docs/queries.md](docs/queries.md#the-types-of-the-arguments) lists: now also compared with an
  expression `h/row-schema` types or with a column in a row (`[:composite ...]`), and as an item
  of `INSERT INTO t (cols) SELECT`. `h/check` reads the same comparisons and items: an uncast
  string reaching an enum-typed expression, a row's element or such an item is
  `:uncast-string`, an unknown column in a row `:unknown-column`.
* `h/row-schema` types a select expression of the forms
  [docs/queries.md](docs/queries.md#the-row-a-query-returns) lists, with its nullability. Any
  other is nullable `:any`, as every expression was.
* `h/row-schema` gives a write with no RETURNING (INSERT, UPDATE, DELETE, TRUNCATE, under
  WITH or ON CONFLICT too) the row next.jdbc returns for it,
  `[:map [:next.jdbc/update-count :int]]`, where it gave nil; `h/query-schema` returned `:any`.
  Under `{:result :one}` that map is not `[:maybe ...]`: `execute-one!` returns it.
* The `:return-keys` option says the write is sent with next.jdbc's `:return-keys`: its row is
  then the table's (`true`) or the columns named, as pgjdbc adds RETURNING; `h/check` reads that
  RETURNING too, and reports a name that is a column only as HoneySQL folds it (`:group-id` for
  `group_id`): pgjdbc sends it quoted, as written, by default (`quoteReturningIdentifiers`).
* What to do: a function whose writes next.jdbc sends with `:return-keys`, passed to `execute!`
  or set with `with-options`, passes the same `:return-keys` to `h/query-schema`. An
  instrumented function that runs a write and returns anything but what `execute!` (or
  `execute-one!`) returns, such as the count itself, needs a return schema of its own: its
  return was `:any`.

## 0.3.2 (2026-10-30)

* `h/check` reports NULL written into a NOT NULL column however HoneySQL spells it: `nil`,
  `:null` (in any case), `[:inline nil]` or `[:lift nil]`. A `:null` the format quotes is a
  column. Thanks to @M-simplifier.
* An unknown column on the left of `IS` / `IS NOT` is reported.
* A column a CHECK narrows to a list (`CHECK (state IN ('open', 'closed'))`) is narrowed only
  where a value outside the list fails or matches no row: a write, `=`, `IN` and
  `IS NOT DISTINCT FROM`. `<>`, `NOT IN`, `<` and the like are no longer reported as
  `:enum-literal`, and `arg-types` gives their arguments the column's base type. An enum
  type keeps its labels in every comparison.
* A comparison under `NOT` is read as its negation: `[:not [:= :state "x"]]` is `<>`.
* An SQL word in another case (`:CURRENT_DATE`, `:TRUE`) is not taken for a column.

## 0.3.1 (2026-10-30)

* Reading a schema runs none of the functions or operators it defines, so its owner cannot
  run code as whoever generates (CVE-2018-1058). Over JDBC, a schema name stays one
  identifier.
* Validation does not stall on a huge numeric (`1E+100000000`) or on a long value under a
  LIKE CHECK, and a long value a regex runs out of stack on does not throw: its CHECK
  passes, a `[:re ...]` schema fails it. A value nested deeper than the stack is unknowable
  to a CHECK.
* Generate again if a schema has a LIKE CHECK with text between two `%`s (`'%-%'`), a
  `col IS NULL OR <pattern>` CHECK, or types or functions named like built-ins (`text`,
  `lower`): their files change, and `pgmalli.main check` reports them stale. The LIKE regex
  matches what it matched; the others were read wrong (a built-in's name taken for the
  schema's own) or said their CHECK's name alone.
* A schema name with `/`, `\` or `..`, or two names differing only in case, is a config
  error instead of writing outside `:out-dir` or sharing one file.
* An invalid config leaves out the values of the keys it does not take, so a misspelt `:db`
  does not show its password.
* `inserts` quotes a column name containing `$`; `read-dataset` throws
  `:pgmalli.error/invalid-dataset` for a file that does not read as a dataset.

## 0.3.0 (2026-10-30)

The first release meant for use. The releases before it were development and are not
listed; the [README](README.md) describes what this one does.

* PostgreSQL schemas as malli: rows, insert and update schemas, enums, domains, keys,
  EXCLUDE constraints and triggers, and CHECKs as malli or evaluated as PostgreSQL evaluates
  them. Generated over psql or JDBC (`generate!`, `pgmalli.main`), read as a registry
  (`registry`, `generated`, `columns`, `portable`), read back as PostgreSQL types (`describe`).
* Rows and datasets generated as the database takes them, written as HoneySQL INSERTs and
  kept as EDN fixtures (`pgmalli.data`: `dataset-schema`, `dataset-generator`, `short-tables`,
  `inserts`, `write-dataset`, `read-dataset`, `readers`).
* Rows typed as next.jdbc builds them from what pgjdbc returns (`as-read`, `read-options`,
  `transformer`).
* HoneySQL queries checked against the schema with no database, their arguments and rows
  typed, and each problem said as what to do (`pgmalli.honeysql`: `check`, `arg-types`,
  `params-schema`, `row-schema`, `query-schema`, `tables`, `schema-problems`, `explain`).
* Generated files kept current in CI (`generate/check`, `generate/diff`, `pgmalli.main check`).

Supported, each and later: PostgreSQL 16, HoneySQL 2.4.1066, next.jdbc 1.3, pgjdbc 42.7,
malli 0.20 and Clojure 1.11; babashka for everything but JDBC.
