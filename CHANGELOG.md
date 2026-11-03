# Changelog

pgmalli follows [semantic versioning](https://semver.org). Before 1.0, a minor release may
break what [Stability](README.md#stability) lists; its entry says what to do.

## Unreleased

* `:send` says what an application sends, as pgjdbc's `setObject` decides it by class: a map
  of classes to the type each goes as (a name or alias, an array type `"bigint[]"`, or
  `:unknown` for a value sent untyped), merged over pgjdbc's; `nil` takes a class out. A class
  adds to a column's own values where PostgreSQL takes its type as the column's own (a narrower
  number, another string type, an untyped value to a text or enum column), and in a cast all
  PostgreSQL casts. A Clojure collection sent as an array type or `:unknown`, and a Java array,
  is an array argument: compared with `= ANY(?)`, assigned to an array column, or under
  `unnest`, which takes a typed array alone.
* `:jdbc` takes the options an application passes to next.jdbc: `:builder-fn` (one of
  next.jdbc's map builders, as the function, its var, or its qualified symbol where next.jdbc
  does not load) keys rows as next.jdbc does, and under a modified builder `:label-fn` and
  `:qualifier-fn` too; `:return-keys` adds RETURNING to a write.
* `h/values-schema` and `h/set-schema` type a whole row written into a table, for `:values` and
  `:set` built at run time, keyed as the `:jdbc` builder keys a row where HoneySQL writes that
  key back to the column. `h/set-schema` takes a non-empty map.
* `h/arg-types` types an argument under a cast (`[:cast :?at :timestamptz]`) with what
  PostgreSQL casts to the type: a string too, and an enum type's labels alone. Assigned or cast
  to an integer type, a fraction is held to the range half a unit wider, as PostgreSQL rounds it.
* An argument in `:limit` or `:offset` takes nil (none). A comparison in a select or ORDER BY
  expression types its argument, and `h/check` reads it.
* `h/check` reports a string sent as any type a column does not take, not only varchar, and a
  string assigned to a reg* column other than regclass.
* `pgmalli.core/describe` refuses a type neither malli nor the registry knows.
* A domain whose CHECK lists its values (`CREATE DOMAIN lbl AS text CHECK (VALUE IN ('a',
  'b'))`) keeps its base type (`[:enum {:pg/type "text"} "a" "b"]`), and `h/check` no longer
  reads it as an enum type: a string sent as `varchar` to it is no `:uncast-string`.
* `h/check` reads a literal against a CHECK's list of numbers as PostgreSQL does: an untyped
  `"1"` is the label `1`, and a number outside the list is an `:enum-literal`.
* `h/row-schema` types `greatest`, `least` and `nullif` as `coalesce`, and `h/arg-types` types
  an argument of any of them with the type the other arguments resolve to.
* `h/query-schema`'s return says how many rows the data tells (`[:sequential {:max 1} row]`):
  one of a SELECT of aggregates alone, at most one where the WHERE holds a primary key or a
  UNIQUE constraint equal to a value, up to as many as an INSERT's `:values` has rows under RETURNING,
  at most `:limit`; where it is one, `{:result :one}` gives no `[:maybe]`. A table other tables
  inherit has `:pg/inherited true`, a DEFERRABLE primary key `:pg/primary-key-deferrable true`
  and a DEFERRABLE UNIQUE constraint `:deferrable true`; an invalid unique index (a failed
  `CREATE INDEX CONCURRENTLY`) is no longer in `:pg/unique`.
* A table written in capitals without quoting (`:USERS`) keys a row by the name PostgreSQL
  folds it to (`:users/id`), as the driver returns it.
* `:unknown-table` names the tables probably meant as `:did-you-mean`, and an option pgmalli does
  not take is an error whose data names it (`:unknown`) and the one meant (`:did-you-mean`).
* The columns of a CTE or a subquery are typed as its SELECT types them; a column it does not
  give is `:unknown-column`. An argument of an `unnest` in `FROM` is an array of the type of
  where its column goes.
* `h/row-schema` types the functions that build json and jsonb (`jsonb_build_object`,
  `jsonb_agg` ...) as a column of the type is read: an object or an array under `{:read {:json
  :parsed}}`.
* A cast to a type with its length (`[:cast :?x [:varchar 10]]`) has the type.
* Where the format quotes names, HoneySQL writes `:default` as the column `"default"`:
  `h/values-schema` and `h/set-schema` take `[:default]` alone there.
* A name the registry lacks is an error listing its tables, views and types before the schemas
  derived from them.
* What to do:
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
