# Changelog

pgmalli follows [semantic versioning](https://semver.org). Before 1.0, a minor release may
break what [Stability](README.md#stability) lists; its entry says what to do.

## Unreleased

### Breaking changes and what to do

* **Options.** Each removed option is an error that names its replacement. The error is
  raised when the schema is built, so a namespace whose `:malli/schema` passes one fails to
  load.
  * `{:stringtype :unspecified}` is `{:send {String :unknown}}`.
  * `:write` is `:send`, a map of the classes your application sends to the type each goes as
    ([docs/queries.md](docs/queries.md#what-your-application-sends)):
    * `:write {"text" [:or :string :keyword]}` and `:write {:enum [:string :keyword]}` are
      `{:send {clojure.lang.Keyword :unknown}}`, or the type your extension sends a keyword as.
    * `:write {:array [...]}` is `{:send {clojure.lang.IPersistentVector :unknown}}`, and the
      same for each collection class you send, or the array type your extension sends it as
      (`"bigint[]"`); `unnest` needs a typed one.
    * `:write {"T" s}` is the class of `s`'s values mapped to `"T"`. Leave out a class pgjdbc
      already sends as `"T"`.
  * `:qualified?`, `:kebab?`, `:lower?` and `:nil-columns` are the builder you pass next.jdbc,
    under `:jdbc`. `{:qualified? false}` is `{:jdbc {:builder-fn rs/as-unqualified-maps}}`;
    `{:kebab? true}` is `rs/as-kebab-maps`, `{:lower? true}` is `rs/as-lower-maps`, and
    `{:nil-columns :absent}` is `next.jdbc.optional/as-maps`. Two of them together are the
    builder that does both (`rs/as-unqualified-kebab-maps`).
  * `:return-keys` moves into `:jdbc`: `{:jdbc {:return-keys true}}`.
* A `:read` key that names no type pgmalli knows, none of the registry and no `:pg/type` its
  columns carry, is an `:invalid-options` error, and so are two keys of one type
  (`"integer"` and `"int4"`) and a value that is no malli schema. Such a key read nothing:
  remove it.
* `pgmalli.core/read-options` is removed: pass the builder as `{:jdbc {:builder-fn ...}}`.
* `h/explain` is renamed `h/humanize`.
* An error's ex-data lists the names probably meant under `:did-you-mean`, a vector, as
  `h/check`'s problems do, instead of `:suggest`.
* `h/query-schema`'s return states the row count where the data tells it
  (`[:sequential {:max 1} row]`): an instrumented function's return is checked against it, and
  code that reads the row by position (`(second ret)`) reads it with `last` or `m/children`.
  Its properties map is always there (`{:pgmalli/problems []}` for none), so the arguments are
  `(nth s 2)` and the return `(nth s 3)`.
* In `pgmalli.edn`, the database name under `:db` is `:dbname`, as in next.jdbc and libpq:
  `:db {:db "app"}` is `:db {:dbname "app"}`. The old key is an error naming the new one.
* `h/row-schema`'s `:pgmalli/untold` property is renamed `:pgmalli/unknown-keys`.
* Checks and argument types are stricter, so a test that passed on 0.3.3 may fail; each
  failure is a real mismatch with the schema.
  * An argument that was `:any` now has a type, and an instrumented function checks it: one
    under a cast, compared in a select or ORDER BY expression, inside `coalesce`, `CASE` and the
    like, an argument of a function or an operator, in an `unnest` in FROM, or compared with a
    CTE's or subquery's column. What PostgreSQL takes where a column's value goes is read from
    its catalog (`pg_cast`, and `=` resolved for the two types) for every type of `pg_catalog`
    and its arrays: an argument where a `bit`, `bit varying`, `oid` or `"char"` value goes, or
    an array of one, is typed too.
  * An argument of several uses takes what each of them takes, whatever their order, and nil
    where any of them does; on 0.3.3 its first use typed it.
  * An argument compared with or assigned to an enum column takes its labels as strings only
    where a `String` goes untyped (`{:send {String :unknown}}`) or cast: PostgreSQL refuses a
    varchar there. Under pgjdbc's default it takes a `PGobject` instead. To send labels as
    strings, connect with `stringtype=unspecified` and pass `{:send {String :unknown}}`, or cast
    the argument (`[:cast :?status :user_status]`); write a constant label as
    `[:inline "active"]`.
  * An instrumented function's return is checked against more: `h/row-schema` types what was
    nullable `:any`, such as a call of a function or an operator or a CTE's or a subquery's
    column.
  * `h/check` now reports a select or ORDER BY expression comparing an unknown column, or an
    enum with a literal outside its labels; a string sent as a type the column does not take,
    whatever type `:send` sends it as, not only `varchar`; a string assigned to a reg* column
    other than `regclass`; a CHECK's list of numbers compared with a value outside it; and a
    column a CTE or a subquery does not give.
* `generate` checks every `:overrides` key: a constraint name or a type no column of the
  schemas has, `{:skip reason}` under a type or column key and a value that is no malli schema
  are errors naming the key meant. Drop the keys of what was dropped from the database.
* Generate again: every file holds the schema's functions and operators (`:functions`,
  `:operators`), and `pgmalli.main check` reports a file without them stale, in one line; until
  then, a call is typed by `pg_catalog`'s functions alone. A file also changes where its schema
  has a domain over `timestamp(n) with time zone` or `time(n)`, a column of a type pgmalli does
  not know overridden by its type, a table other tables inherit, a DEFERRABLE primary key or
  unique constraint, an invalid unique index, or a domain whose CHECK lists its values. A
  function or operator that differs is reported by its name
  (`{:key :functions :name "f" :file ... :db ...}`).

### Added

* `h/analyze` gives what one reading of a query tells, `{:args :row :rows :problems :tables}`:
  the arguments' types, the row, how many rows where the data tells, the problems of `h/check`
  and the tables of `h/tables`
  ([docs/queries.md](docs/queries.md#function-schemas)). `h/query-schema` is built of it.
* `h/arg-types` (and `h/query-schema`) type an argument where a whole row goes, `:values
  [:?row]`, `:values :?rows` and `:set :?row`, as `h/values-schema` and `h/set-schema` type the
  row ([docs/queries.md](docs/queries.md#a-row-written-into-a-table)).
* `{:read {:enum :keyword}}`: an extension reading every enum type's value as a keyword
  ([docs/reading-rows.md](docs/reading-rows.md#your-own-extensions)).
* `:send` says what your application sends, as pgjdbc's `setObject` decides it by a value's
  class ([docs/queries.md](docs/queries.md#what-your-application-sends)). pgjdbc's own classes
  include its boxed arrays (`Long[]`, `Integer[]` ...), `BigDecimal[]`, `UUID[]` and the arrays
  of java.sql dates, times and timestamps.
* `:jdbc` takes the options your application passes to next.jdbc
  ([docs/reading-rows.md](docs/reading-rows.md#keys-from-a-nextjdbc-builder)): `:builder-fn`,
  one of next.jdbc's map builders, keys rows as next.jdbc does; under a modified builder,
  `:label-fn` and `:qualifier-fn` too (functions, not symbols); `:return-keys` adds RETURNING to
  a write. A key spelled close to one pgmalli reads and read by no next.jdbc function
  (`:buildr-fn`) is an error naming the key meant.
* `h/values-schema` and `h/set-schema` type a whole row written into a table, for `:values` and
  `:set` maps built at run time, keyed by any one key HoneySQL writes to a column: the label
  the `:jdbc` builder reads it as, unqualified or qualified by the table (`:users/group_id`, as
  next.jdbc's default builder reads it), so a row read is written back, its name (`:group_id`),
  its kebab spelling (`:group-id`) or its name as a string (`"group_id"`). A column takes a
  HoneySQL expression too (`[:now]`, `[:+ :n 1]`, `:current_timestamp`, a column in `:set`,
  `[:lift v]` with `v` of the column's type, a subquery map), and generates values alone
  ([docs/queries.md](docs/queries.md#a-row-written-into-a-table)).
* `h/check` and `h/row-schema` read `:join-by`'s joins as the join clauses', in its order, and
  `{:table t}` as `SELECT * FROM t`.
* A column of an enum type or a domain of another schema the registry holds is of that type:
  `h/check` reads its labels, and an argument takes them as one of the schema's own.
* `:format` also reads `:dialect`, `:quoted-snake` and `:quoted-always`.
* Array arguments: a Clojure collection whose class `:send` sends as an array type or
  `:unknown`, and a Java array, typed by its elements (`[:pg/array element]`), where it is
  compared with `= ANY(?)`, assigned to an array column, or passed to `unnest`, which takes
  only a typed array ([docs/queries.md](docs/queries.md#what-your-application-sends)).
* `h/arg-types` types more arguments
  ([docs/queries.md](docs/queries.md#the-types-of-the-arguments)): under a cast, with what
  PostgreSQL casts to the type (a collection sent untyped not to a number or boolean type, whose
  input refuses an array literal); an argument of `coalesce`, `greatest`, `least` or `nullif`; one
  compared in a select or ORDER BY expression; one of an `unnest` in FROM, as an array of the
  type of where its column goes; one compared with `ANY(array)` as the array's element; an
  element of `[:array [...] :text]`, as cast; one of a function or an operator PostgreSQL may
  resolve several ways, the values of each (`generate_series(1, ?)`, `timezone(zone, ?)`).
  Under `{:compare :lenient}` the elements of one in `:in` or `= ANY` take nil too.
* `h/row-schema` types an expression as PostgreSQL resolves it
  ([docs/queries.md](docs/queries.md#the-row-a-query-returns)): `CASE`, `coalesce`, `greatest`,
  `least`, `ARRAY[...]`, `VALUES` and a set operation's columns of their values' common type, and
  every function and operator of `pg_catalog` (aggregates, with `FILTER` too, window functions
  under `:over`, polymorphic ones) of the type it resolves to, from a snapshot of `pg_catalog`
  of PostgreSQL 16, 17 and 18; a literal `[:inline ...]` writes; `INTERVAL '1 day'`; a cast to a type with its length
  (`[:varchar 10]`) or to an array type; `current_user` and the like as `name`. A function of
  `pg_catalog` in FROM has its columns, of no table, so a qualified builder keys them
  unqualified. The columns of a CTE or a subquery are typed as
  its SELECT types them, and keyed by the table PostgreSQL traces them to; a set operation's
  column is of the type PostgreSQL resolves its members' to; `SELECT *` of a CTE or a subquery
  lists its columns; a table function (`WITH ORDINALITY` too) is named by its alias. Also a
  scalar subquery (its column's type, nullable), `[[f x]]`, an aggregate with `ORDER BY`,
  `x = ANY(array)` (boolean), a function returning `void` (`[:any {:pg/type "void"}]`), a call
  whose overloads all return one type that is not polymorphic (`count`, `jsonb_build_object`)
  even of arguments it cannot type, and a set operation's column of a NULL member. A recursive
  CTE keeps its types through a CTE or a subquery reading it, a subquery reading a CTE is
  read under it, and a CTE whose body has a `:with` of its own is read under the CTEs before it. A table or view of `pg_catalog` has its columns, from the snapshot.
* The snapshot is read as of the PostgreSQL versions the registry's files were generated on
  (`:database-version`): a function of PostgreSQL 18 alone (`uuidv7`, `casefold`) is typed for a
  registry generated on 18, and a column of `pg_catalog`'s tables one supported version lacks
  (`pg_class.relallfrozen`) is an optional key of `SELECT *` for a registry of no version it
  knows, and absent for one generated on that version.
* A schema's own functions and operators are typed as `pg_catalog`'s
  ([docs/queries.md](docs/queries.md#the-row-a-query-returns)): the generated file holds them,
  and a call resolves among `pg_catalog`'s and `:schema`'s as PostgreSQL's search path does,
  `pg_catalog` first: an overload of a `pg_catalog` function's argument types gives way to it,
  one of others competes (`round(integer)` of the schema is called for an integer column). A
  call qualified with a schema resolves among that schema's. In FROM, such a function has its
  columns (`RETURNS TABLE`, OUT arguments, `RETURNS SETOF users`), of no table.
* Where `h/query-schema`'s row count (see Breaking changes) is exactly one, `{:result :one}`
  drops the `[:maybe]` ([docs/queries.md](docs/queries.md#function-schemas)). A function that
  returns no set, of `pg_catalog` or of a generated schema, keeps the count
  (`SELECT lower(email) ... WHERE id = ?` is `{:max 1}`).
* A table's map carries `:pg/inherited true` for a table other tables inherit (not as
  partitions), `:pg/primary-key-deferrable true`, and `:deferrable true` on a DEFERRABLE
  `:pg/unique` entry ([docs/types.md](docs/types.md#properties)).
* Errors name what was probably meant: `:unknown-table` lists a table of the same name in
  another schema first, and `h/humanize` says to pass its `:schema`; `:unknown-column` keeps the
  qualifier (`:u.nick` for `:u.nik`), and a qualifier that names no table or alias of the
  statement is under `:qualifier`, with the alias probably meant; `:unread-clause` names the
  clause probably meant for one neither HoneySQL nor pgmalli knows (`:selec`); a schema name
  given unqualified (`:users`) or of another schema (`:pg.public/users`) names the registry's
  name (`:pg.sample/users`); an error for a name the registry lacks lists its tables, views and
  types before the schemas derived from them.
* `pgmalli.main`: `--help` prints the usage to stdout and exits 0; an unknown option is named
  before the usage, exit 2. The usage shows the command to run, the exit codes and
  `PGMALLI_DEBUG`.
* `:overrides` takes one column, `:schema.table/column`: its schema replaces that column's
  value, read and written, NULL-ability and CHECKs still the database's
  ([Overrides](README.md#overrides)). A json or jsonb column's shape is the parsed value's:
  read under `{:read {:json :parsed}}`, written for what `:send` sends as its type.
* An `:overrides` type key may be the type's alias or a HoneySQL cast's spelling
  (`:timestamptz`, `:timestamp-with-time-zone`); `pgmalli.main` suggests one for an unrendered
  type, or the column's key for an array type.

### Changed

* `h/arg-types` gives an argument it cannot type as `[:any {:pgmalli/untyped why}]`, `why` saying
  whether no use of it is typed, the type where it is used is unknown, or only code uses it
  ([docs/queries.md](docs/queries.md#the-types-of-the-arguments)). It validates as `:any` did.
* `now()`, the functions that build json and a function call in general are nullable: the
  catalog does not say when a function returns NULL. `current_timestamp` and the like (but
  `current_schema` and `system_user`, which may be NULL), `count`, `pg_catalog`'s `row_number`
  and a comparison of values that are not NULL are not.
* A domain whose CHECK lists its values
  (`CREATE DOMAIN lbl AS text CHECK (VALUE IN ('a', 'b'))`) keeps its base type
  (`[:enum {:pg/type "text"} "a" "b"]`). `h/check` no longer reads it as an enum type, so a
  string sent as `varchar` to it is not `:uncast-string`.
* A number is checked against a number column's CHECKs whatever its class. A value sent as a
  text type (a keyword sent as `varchar`) is not checked against the column's CHECKs on text:
  PostgreSQL checks the text your extension makes of it.
* An argument in `:limit` or `:offset` takes nil (no limit).
* Cast to an integer type, a fraction is held to the range half a unit wider, as PostgreSQL
  rounds it. An argument cast to a number domain with a CHECK list takes another
  number class or a boolean, which PostgreSQL converts (1.5 to 2) before the CHECK.
* Where a BEFORE row trigger runs for the write, an argument assigned to a NOT NULL column takes
  nil, as `h/check` already allowed: the trigger may fill it. The write is the value's own: a
  value inserted under `ON CONFLICT DO UPDATE` takes nil for a BEFORE INSERT trigger alone, as
  PostgreSQL checks NOT NULL on the row proposed before it looks for a conflict.
* An argument (or an `unnest`'s element) that `INSERT INTO t (cols) SELECT` assigns to a NOT NULL
  column takes nil where the SELECT may drop the row before it reaches the column: `WHERE`,
  `HAVING`, `GROUP BY`, `LIMIT`, `OFFSET`, `DISTINCT`, a join, or a `FROM` item other than
  `unnest` of arguments; it then takes a value beyond the column's CHECKs, length or range too,
  which PostgreSQL checks only on a row it writes. A SELECT of arguments alone, or from `unnest`
  of them, types it as `:values` does.
* `h/check` and `h/arg-types` read a comparison in every clause, as in `WHERE`: in `SET`,
  `RETURNING` and the rows of `:values` too, which see the tables PostgreSQL lets them (the rows
  of `:values` not the table inserted into). The column of `unnest(?)` in `FROM` is read as its
  argument's elements wherever it goes, in a subquery too, and compared under `IS [NOT] DISTINCT
  FROM` takes nil. A problem with an item of `INSERT ... SELECT` is reported in the SELECT
  (`:in`).
* An argument compared with or assigned to a column the driver reads as a PGobject (`citext`,
  `bit(n)` for n > 1, `bit varying`, a domain over them) takes a PGobject or an instance of a
  subclass, so a value read can be written back. An array argument whose elements take a
  PGobject takes one too (`= ANY(?)` of an enum column, a PGobject of type `"status[]"`).
* A domain over `bit`, `bit varying` or `numeric(p,s)` is read as the driver returns its base
  type: a Boolean for `bit(1)`, a PGobject for another bit string, a Double too for a
  `numeric`'s NaN.
* `h/check` reads a literal against a CHECK's list of numbers as PostgreSQL does (an untyped
  `"1"` is the label `1`), an alias's column list as renaming only the first columns
  (`AS u(a)` keeps the others' names), TRIM's column as `btrim`, and `coalesce` of a column with
  a CHECK's list as its base type.
* A call of a type's name no function takes the argument of (`[:int4 :x]`, `[:timestamptz :?at]`)
  is a cast, as PostgreSQL reads it: its argument takes what the cast takes, and a column cast so
  to its own type keeps its table.
* An operator of one operand HoneySQL writes as the operand alone (`[:and :x]`, `[:* :x]`) is that
  operand: named, keyed and typed as it.
* An argument compared with a literal of no type (`[:= :?w [:inline "1"]]`) takes any value, as
  PostgreSQL reads the literal as the argument's type.
* An override of a type the driver reads as an object of its own (`:inet` a PGobject, `:hstore` a
  `java.util.Map`) holds for writes; a read stays the driver's object (`:some` where the column is never NULL)
  unless `:read` names the type: `{:read {"tstzrange" :string}}` for an extension reading a range as its text.
* A `:schema` the registry holds no generated file, table or view of is an
  `:pgmalli.error/invalid-options` error naming the ones it holds, in every function of
  `pgmalli.honeysql`; before, each did something of its own (an unknown table, `:any`, nil).
  `pgmalli.core/describe` of a query schema shows its `:pgmalli/problems`.
* An alias, `:default`, `:null` and a key of `:values` are read as the loaded HoneySQL writes them
  under `:format` (`:quoted-snake` too) and the dialect `sql/set-dialect!` set.
* An argument under `SOME` (`[:= :id [:some :?ids]]`) is typed as one under `ANY`.
* `h/check` reads an assignment target `:a.b` as the field `b` of the composite column `a`,
  where it reported `:unknown-column`; the value is not checked.
* A list or seq of keywords, vectors, maps and values alone (a `:select` built with `map`) is
  read as a vector, as HoneySQL reads it; before, it was taken for code and not checked. A list
  with a symbol in it is still code.
* A table written in capitals without quoting (`:USERS`) keys a row by the name PostgreSQL
  folds it to (`:users/id`), as the driver returns it.
* An invalid unique index (a failed `CREATE INDEX CONCURRENTLY`) is no longer in `:pg/unique`.
* `pgmalli/portable` leaves out a `numeric(p,s)`'s precision, which malli's types cannot
  express.
* `pgmalli.core/describe` refuses a type neither malli nor the registry knows.
* An option pgmalli does not take is an error whose data names it (`:unknown`) and the one
  meant (`:did-you-mean`).
* Messages name the one probably meant as `; did you mean: x`, which no longer doubles the `?`
  of a name ending in one (`:db?`). A misspelt option or config key reads
  `{:shcema ["disallowed key"]}; did you mean: :schema`.
* A `:db` holding next.jdbc's `:dbtype` is an error saying to drop it.
* A schema of `:schemas` the database lacks is an error naming the schema meant.
* `pgmalli.main generate` prints its counts as `tables: 4, views: 0, enums: 1 ...`, and warns of
  a PostgreSQL newer than 18: its new functions and types are not typed. A PostgreSQL older
  than 16 is an error.
* A file's `:database-version` is the major version for a server whose version names its build
  too (`17.6 (Debian 17.6-1.pgdg120+1)`), which a minor upgrade no longer makes stale.
* A column overridden by its type is no longer listed in `:unrendered`.
* A domain over a type with a typmod inside its name (`timestamp(3) with time zone`,
  `time(0)`) has the type's schema instead of `:any`.
* `h/check` leaves a named argument to `h/arg-types`, which types it by the class sent (a
  `PGobject`, or a value `:send` sends untyped, reaches an enum column): `:uncast-string` is a
  literal string's alone, `[:lift "s"]` too.
* An expression of a `numeric(p,s)` column (`round`, `coalesce`, `-`) is typed as `numeric`: an
  argument compared with it takes an integer, and its value read a Double for NaN.
* A `citext` column, or a domain over one, takes as an argument what a `text` column takes.
* An argument cast where every use of the cast is compared (`[:= :mail [:cast :?m :email]]`)
  leaves out the CHECKs, lengths and ranges of the type cast to: PostgreSQL evaluates the cast
  only where its plan reaches it, for a row the other conditions keep or once ahead of the scan.
* `coalesce`, `greatest`, `least`, `CASE` and a set operation of columns all of one domain are of
  that domain, as PostgreSQL resolves them.
* `:read` names a type by any of its spellings: an alias (`"int4"`, `"timestamptz"`), an enum
  type or a domain of the registry with its schema or without (`"app.mood"` reads a column
  of type `mood` too).
* A table of a query, or of `h/values-schema` and `h/set-schema`, read against a registry
  holding no schema pgmalli generates is a `:pgmalli.error/not-a-registry` error from every
  function, not `:unknown-table` or an untyped result; a table of `pg_catalog` still reads; `(pgmalli.core/registry)` of no
  schema is an `:invalid-options` error.
* `h/humanize` begins a problem of `h/schema-problems` with its var; `h/schema-problems` of a
  namespace not loaded is an `:invalid-options` error.

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
