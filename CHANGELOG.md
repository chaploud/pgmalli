# Changelog

pgmalli follows [semantic versioning](https://semver.org). Before 1.0, a minor release may
break what [Stability](README.md#stability) lists; its entry says what to do.

## Unreleased

* `h/arg-types` types an argument under a cast (`[:cast :?at :timestamptz]`) with what
  PostgreSQL casts to the type: a string too, and an enum type's labels alone. A `numeric`,
  `real` or `double precision` column's argument takes an integer too, a `double precision`
  one a `real` too.
* `{:accept :postgresql}` types an argument with all PostgreSQL takes where it goes (a `bigint`
  compared with a `numeric`, anything assigned to a `text` column); by default (`:native`) it
  takes its column's own values, as before. `:sends` names the types an application's values
  reach PostgreSQL as, for a cast's argument and under `:accept :postgresql`; a name pgmalli
  does not know is an error.
* An argument in `:limit` or `:offset` takes nil (no limit). A comparison in a select or ORDER BY
  expression types its argument, and `h/check` reads it: its unknown columns, uncast strings and
  enum literals are problems now.
* `h/check` reports a string assigned to a reg* column other than regclass (regtype, regproc
  ...) as `:uncast-string`: PostgreSQL takes none.
* What to do: an instrumented function whose argument was `:any` under a cast or in a select or
  ORDER BY comparison now has its type checked; a query whose select or ORDER BY compares an
  unknown column or an enum with a literal outside its labels now has problems.

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
