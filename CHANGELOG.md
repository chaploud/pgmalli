# Changelog

pgmalli follows [semantic versioning](https://semver.org). Before 1.0, a minor release may
break what [Stability](README.md#stability) lists; its entry says what to do.

## Unreleased

### Breaking changes and what to do

* `h/check`, `h/arg-types`, `h/row-schema`, `h/tables` and `h/params-schema` are removed:
  `h/analyze` gives what they gave, under `:problems`, `:args`, `:row` and `:tables`
  (`(:problems (h/analyze registry q))`); the `[:map ...]` of `sql/format`'s `:params` is
  `(into [:map] (:args (h/analyze registry q)))`. The entries below that name the removed
  functions hold for these keys.
* **Options.** Each removed option is an error that names its replacement. The error is
  raised when the schema is built, so a namespace whose `:malli/schema` passes one fails to
  load.
  * `:write` and `:stringtype` are removed: an error names the one sending (below).
  * `:qualified?`, `:kebab?`, `:lower?` and `:nil-columns` are removed: pass
    `{:builder-fn next.jdbc.result-set/as-unqualified-maps}` to next.jdbc
    ([docs/reading-rows.md](docs/reading-rows.md#the-builder)).
  * `:return-keys` moves into `:jdbc`: `{:jdbc {:return-keys true}}`.
* **Rows are read with one builder,** next.jdbc's `as-unqualified-maps`: `as-read`,
  `h/row-schema` and `h/query-schema`'s return key a row by its columns' names (`:group_id`), not
  `:users/group_id`, and a NULL column keeps its key. Read rows with
  `{:builder-fn next.jdbc.result-set/as-unqualified-maps}`; convert keys to another spelling in
  your application. A query selecting one name twice (`:u.id :g.id`) is one key, the last
  column's, as the builder keeps it: alias one of them.
* **Rows are read one way and arguments sent one way,** as the extension of
  [docs/reading-rows.md](docs/reading-rows.md#the-one-reading-and-sending) reads and sends them.
  pgmalli ships no extension: install that page's code in your application, once.
  * Read: timestamps (with and without time zone) as `Instant`s, dates as `LocalDate`s, times as
    `LocalTime`s (a `timetz` as pgjdbc's `java.sql.Time`, an `interval` as its `PGInterval`),
    arrays as vectors of their elements, json and jsonb parsed with keyword keys, enum values as
    strings, every other type as pgjdbc returns it. A json column's override or CHECK shape now
    holds for what is read.
  * Sent: pgjdbc's `setObject` (a `String` as `varchar`, a Java array as an array of its
    elements' type), `next.jdbc.date-time` (an `Instant`, a `LocalDate`, a `LocalDateTime`, a
    `java.util.Date` untyped), a keyword's text untyped, a map and a vector as `jsonb`
    ([docs/queries.md](docs/queries.md#what-your-application-sends)). A vector is no array: pass
    a Java array (`(long-array ids)`, `(into-array String xs)`) where an array goes. A connection
    with `stringtype=unspecified` sends otherwise than pgmalli types.
  * An enum argument takes a keyword label (`:active`), which goes untyped, or a string cast
    (`[:cast :?s :user_status]`), not a string, which goes as `varchar`. In a row written into
    a table, give a label as `[:lift :active]`: HoneySQL writes a keyword in `:values` as a
    column. A date or a time column's argument takes its type's java.time value too (a
    `LocalDate` a `date`'s), and a string literal compared with one is `:uncast-string`.
  * An argument of a function's `"any"` parameter (`concat`, `format`, `jsonb_build_object`)
    takes a value sent typed alone, not a keyword, a date sent untyped or nil: PostgreSQL
    cannot type the parameter then.
  * `:time`, `:read` and `:send` are removed: an error names the one reading or the one sending.
  * `pgmalli-next.core/transformer` is removed: read rows with the extension instead of decoding them.
* `:compare` is removed: a comparison is strict, and an argument compared with a column takes
  no nil, as on 0.3.3 by default. `{:compare :lenient}` (or any `:compare`) is an
  `:pgmalli.error/invalid-options` error. Compare NULL in the query body (`[:= :x nil]`, which
  HoneySQL writes as IS NULL, or `IS NOT DISTINCT FROM`).
* `pgmalli-next.core/read-options` is removed.
* Datasets are removed: the namespace `pgmalli.data` (`dataset-schema`, `dataset-generator`,
  `short-tables`, `inserts`, `write-dataset`, `read-dataset`, `readers`) and the error kinds
  `:pgmalli.error/invalid-dataset` and `:pgmalli.error/reference-cycle`. `mg/generate` still
  gives a row of a table.
* `pgmalli-next.core/portable` and `pgmalli-next.core/columns` are removed. A table's schema is
  `(m/deref (m/schema :pg.public/users {:registry registry}))`; with CHECKs across columns it is
  `[:and [:map ...] ...]`, its map the first child.
* `h/row-schema`'s `:pgmalli/untold` property is removed: with unqualified keys, every key is
  told.
* pgmalli no longer depends on camel-snake-kebab.
* `h/explain` is renamed `h/humanize`.
* An error's ex-data lists the names probably meant under `:did-you-mean`, a vector, as
  `h/check`'s problems do, instead of `:suggest`.
* `h/query-schema`'s return states the row count where the data tells it
  (`[:sequential {:max 1} row]`): an instrumented function's return is checked against it, and
  code that reads the row by position (`(second ret)`) reads it with `last` or `m/children`.
  Its properties map is always there (`{:pgmalli/problems []}` for none), so the arguments are
  `(nth s 2)` and the return `(nth s 3)`.
* In `pgmalli-next.edn`, the database name under `:db` is `:dbname`, as in next.jdbc and libpq:
  `:db {:db "app"}` is `:db {:dbname "app"}`. The old key is an error naming the new one.
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
  * An instrumented function's return is checked against more: `h/row-schema` types what was
    nullable `:any`, such as a call of a function or an operator or a CTE's or a subquery's
    column.
  * `h/check` now reports a select or ORDER BY expression comparing an unknown column, or an
    enum with a literal outside its labels; a string literal assigned to a reg* column
    other than `regclass`; a CHECK's list of numbers compared with a value outside it; and a
    column a CTE or a subquery does not give.
* `:overrides` takes two keys: a column, `:schema.table/column`, with its schema, and a
  constraint name with `{:skip reason}`. The others are `:pgmalli.error/invalid-config` errors
  saying what to write instead:
  * A type key (`{:inet s}`) names the columns of the type: give each its schema
    (`{:public.hosts/ip s}`).
  * A schema under a constraint name names its column: skip the CHECK and give the column the
    schema. A CHECK across columns is kept or skipped whole.
* `generate` checks every `:overrides` key: a constraint name or a column the schemas do not
  have, `{:skip reason}` under a column key and a value that is no malli schema are errors
  naming the key meant. Drop the keys of what was dropped from the database.
* Generate again: every file holds the schema's functions and operators (`:functions`,
  `:operators`), and `pgmalli-next.main check` reports a file without them stale, in one line; until
  then, a call is typed by `pg_catalog`'s functions alone. A file also changes where its schema
  has a domain over `timestamp(n) with time zone` or `time(n)`, a table other tables inherit, a DEFERRABLE primary key or
  unique constraint, an invalid unique index, or a domain whose CHECK lists its values. A
  function or operator that differs is reported by its name
  (`{:key :functions :name "f" :file ... :db ...}`).

### Added

* A cast to `[:raw "bigint[]"]`, how an array cast is written in HoneySQL, has its type: its
  argument takes what PostgreSQL casts to it, and `unnest` of it, of several arrays too, has
  columns of the elements' types. An argument cast to an array type, `jsonb[]` and an enum's
  too, takes a Java array of what a cast to the element type takes, or nil. Under `unnest` in
  `FROM` of `INSERT ... SELECT`, its column written to a column of the element type (or a
  domain over it), each element is held to the column as an argument assigned to it is: its
  length, range, CHECK, override and NOT NULL. `SELECT *` of such an `unnest` alone, cast or
  not, types its arguments as the columns named one by one do.
* `h/analyze` gives what one reading of a query tells, `{:args :row :rows :problems :tables}`:
  the arguments' types, the row, how many rows where the data tells (not where a UNIQUE column
  is compared with `nil`, which HoneySQL writes `IS NULL` and matches many rows), the problems
  PostgreSQL would report and the tables it reads and writes
  ([docs/queries.md](docs/queries.md)). `h/query-schema` is built of it.
* The one sending includes pgjdbc's boxed arrays (`Long[]`, `Integer[]` ...), `BigDecimal[]`,
  `UUID[]` and the arrays of java.sql dates, times and timestamps.
* `:jdbc` takes the options your application passes to next.jdbc
  ([docs/reading-rows.md](docs/reading-rows.md#the-options-of-nextjdbc)): `:return-keys` adds
  RETURNING to a write; `:builder-fn` is `as-unqualified-maps`, and another builder,
  `:label-fn` or `:qualifier-fn` is an error naming it. A key spelled close to one pgmalli reads
  and read by no next.jdbc function (`:return-key`) is an error naming the key meant.
* `h/values-schema` and `h/set-schema` type a whole row written into a table, for `:values` and
  `:set` maps built at run time, keyed as a row is read (`:group_id`), so a row read is written
  back as it is (the name as a string where HoneySQL would not write the keyword to the column);
  a column takes its values, `:default` where the database may fill it, and `[:lift v]` with
  `v` of the column's type, not an expression, which belongs in the query body; it generates
  values alone
  ([docs/queries.md](docs/queries.md#a-row-written-into-a-table)).
* `h/analyze` reads `:join-by`'s joins as the join clauses', in its order, and `{:table t}` as
  `SELECT * FROM t`.
* A column of an enum type or a domain of another schema the registry holds is of that type:
  `:problems` reads its labels, and an argument takes them as one of the schema's own.
* `:format` also reads `:dialect`, `:quoted-snake` and `:quoted-always`.
* Array arguments: a Java array, typed by its elements (`[:pg/array element]`), where it is
  compared with `= ANY(?)`, assigned to an array column, or passed to `unnest`, which takes
  only a typed array ([docs/queries.md](docs/queries.md#what-your-application-sends)).
* `h/arg-types` types more arguments
  ([docs/queries.md](docs/queries.md#the-types-of-the-arguments)): under a cast, with what
  PostgreSQL casts to the type, its CHECKs and ranges left out where every use of the cast is
  compared; an argument of `coalesce`, `greatest`, `least` or `nullif`; one
  compared in a select or ORDER BY expression; one of an `unnest` in FROM, as an array of the
  type of where its column goes; one compared with `ANY(array)` as the array's element; an
  element of `[:array [...] :text]`, as cast; one of a function or an operator PostgreSQL may
  resolve several ways, the values of each (`generate_series(1, ?)`, `timezone(zone, ?)`).
* `h/row-schema` types an expression as PostgreSQL resolves it
  ([docs/queries.md](docs/queries.md#the-row-a-query-returns)): `CASE`, `coalesce`, `greatest`,
  `least`, `ARRAY[...]`, `VALUES` and a set operation's columns of their values' common type, and
  every function and operator of `pg_catalog` (aggregates, with `FILTER` too, window functions
  under `:over`, polymorphic ones) of the type it resolves to, from a snapshot of `pg_catalog`
  of PostgreSQL 16, 17 and 18; a literal `[:inline ...]` writes; `INTERVAL '1 day'`; a cast to a type with its length
  (`[:varchar 10]`) or to an array type; `current_user` and the like as `name`. A function of
  `pg_catalog` in FROM has its columns. The columns of a CTE or a subquery are typed as
  its SELECT types them; a set operation's
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
* `pgmalli-next.main`: `--help` prints the usage to stdout and exits 0; an unknown option is named
  before the usage, exit 2. The usage shows the command to run, the exit codes and
  `PGMALLI_DEBUG`.
* `:overrides` takes one column, `:schema.table/column`: its schema replaces that column's
  value, read and written, NULL-ability and CHECKs still the database's
  ([Overrides](README.md#overrides)). A json or jsonb column's shape is the parsed value's:
  read parsed, written for a map and a vector, which go as `jsonb`.
* `pgmalli-next.main` suggests the column's `:overrides` key for an unrendered type, and
  `{:skip reason}` for an unrendered CHECK.

### Changed

* A value sent untyped goes where its text is of the column's type's category: a date or a time
  (an `Instant`, a `LocalDate`, a `LocalDateTime`, a `java.util.Date`, java.sql's) to a date,
  time or timestamp column of any of these types, not only its own (a `LocalDate` to a
  `timestamptz` too); a keyword, as before, to a string or enum column; either to a `bytea` or
  `"char"` column and to an extension's type. Cast, a date or a time goes to a string type too.
  A keyword to a `bigint` column and a date to a `text` one stay refused, listed under
  [Stricter than PostgreSQL](docs/queries.md#stricter-than-postgresql).
* An array argument's elements are its Java array's component class's: a `String[]` holds no
  keyword, a `java.sql.Time[]` its `java.sql.Time`s.
* `h/arg-types` gives an argument it cannot type as `[:any {:pgmalli/untyped why}]`, `why` saying
  whether no use of it is typed, the type where it is used is unknown, or only code uses it
  ([docs/queries.md](docs/queries.md#the-types-of-the-arguments)). It validates as `:any` did.
* `now()`, the functions that build json and a function call in general are nullable: the
  catalog does not say when a function returns NULL. `current_timestamp` and the like (but
  `current_schema` and `system_user`, which may be NULL), `count`, `pg_catalog`'s `row_number`
  and a comparison of values that are not NULL are not.
* A jsonb column a CHECK or an override shapes takes that shape as an argument for a map and a
  vector, not any value of the class.
* A domain whose CHECK lists its values
  (`CREATE DOMAIN lbl AS text CHECK (VALUE IN ('a', 'b'))`) keeps its base type
  (`[:enum {:pg/type "text"} "a" "b"]`). `h/check` no longer reads it as an enum type, so a
  string sent as `varchar` to it is not `:uncast-string`.
* A number is checked against a number column's CHECKs whatever its class. A keyword is not
  checked against the column's CHECKs on text: PostgreSQL checks the text sent of it.
* A `citext` column, or a domain over one, takes as an argument what a `text` column takes.
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
* An argument compared with or assigned to a column the driver reads as a PGobject (`json`,
  `jsonb`, `citext`, `bit(n)` for n > 1, `bit varying`, a domain over them) takes a PGobject or
  an instance of a subclass, so a value read without an extension can be written back. An array argument whose elements take a
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
* An override of a column of a type the driver reads as an object of its own (an `inet` a
  PGobject, an `hstore` a `java.util.Map`) holds for writes; a read stays the driver's object (`:some` where the column is never NULL)
  unless `:read` names the type: `{:read {"tstzrange" :string}}` for an extension reading a range as its text.
* A `:schema` the registry holds no generated file, table or view of is an
  `:pgmalli.error/invalid-options` error naming the ones it holds, in every function of
  `pgmalli-next.honeysql`; before, each did something of its own (an unknown table, `:any`, nil).
  `pgmalli-next.core/describe` of a query schema shows its `:pgmalli/problems`.
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
* `pgmalli-next.core/describe` refuses a type neither malli nor the registry knows.
* An option pgmalli does not take is an error whose data names it (`:unknown`) and the one
  meant (`:did-you-mean`).
* Messages name the one probably meant as `; did you mean: x`, which no longer doubles the `?`
  of a name ending in one (`:db?`). A misspelt option or config key reads
  `{:shcema ["disallowed key"]}; did you mean: :schema`.
* A `:db` holding next.jdbc's `:dbtype` is an error saying to drop it.
* A schema of `:schemas` the database lacks is an error naming the schema meant.
* `pgmalli-next.main generate` prints its counts as `tables: 4, views: 0, enums: 1 ...`, and warns of
  a PostgreSQL newer than 18: its new functions and types are not typed. A PostgreSQL older
  than 16 is an error.
* A file's `:database-version` is the major version for a server whose version names its build
  too (`17.6 (Debian 17.6-1.pgdg120+1)`), which a minor upgrade no longer makes stale.
* A column overridden by its type is no longer listed in `:unrendered`.
* A domain over a type with a typmod inside its name (`timestamp(3) with time zone`,
  `time(0)`) has the type's schema instead of `:any`.
* `:problems` leaves a named argument to `:args`, which types it by the class sent (a keyword
  or a `PGobject` reaches an enum column): `:uncast-string` is a literal string's alone,
  `[:lift "s"]` too.
* An expression of a `numeric(p,s)` column (`round`, `coalesce`, `-`) is typed as `numeric`: an
  argument compared with it takes an integer, and its value read a Double for NaN.
* A `citext` column, or a domain over one, takes as an argument what a `text` column takes.
* An argument cast where every use of the cast is compared (`[:= :mail [:cast :?m :email]]`)
  leaves out the CHECKs, lengths and ranges of the type cast to: PostgreSQL evaluates the cast
  only where its plan reaches it, for a row the other conditions keep or once ahead of the scan.
* `coalesce`, `greatest`, `least`, `CASE` and a set operation of columns all of one domain are of
  that domain, as PostgreSQL resolves them.
* An array read as a vector takes nil elements: PostgreSQL returns NULL elements of
  `array_agg`, `ARRAY[...]` and an array column. An element of `citext`, or of a domain but
  over an enum, is the driver's `PGobject`, and `array_agg` and `ARRAY[...]` of a domain's
  values are an array of the domain, as PostgreSQL types them. `array_agg` of an array,
  which adds a dimension its type does not name, is `:any`.
* A set operation of a domain's column and an untyped literal is of the domain's base type,
  without the domain's CHECKs or its base type's length, as PostgreSQL resolves it.
* Under `{:format {:inline true}}`, an argument the query tests for NULL
  (`[:or [:= :?id nil] [:= :id :?id]]`, `NULL IS NULL OR id = NULL`) takes nil, whatever its
  other uses.
* A table of a query, or of `h/values-schema` and `h/set-schema`, read against a registry
  holding no schema pgmalli generates is a `:pgmalli.error/not-a-registry` error from every
  function, not `:unknown-table` or an untyped result; a table of `pg_catalog` still reads; `(pgmalli-next.core/registry)` of no
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
  `lower`): their files change, and `pgmalli-next.main check` reports them stale. The LIKE regex
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
  them. Generated over psql or JDBC (`generate!`, `pgmalli-next.main`), read as a registry
  (`registry`, `generated`, `columns`, `portable`), read back as PostgreSQL types (`describe`).
* Rows and datasets generated as the database takes them, written as HoneySQL INSERTs and
  kept as EDN fixtures (`pgmalli.data`: `dataset-schema`, `dataset-generator`, `short-tables`,
  `inserts`, `write-dataset`, `read-dataset`, `readers`).
* Rows typed as next.jdbc builds them from what pgjdbc returns (`as-read`, `read-options`,
  `transformer`).
* HoneySQL queries checked against the schema with no database, their arguments and rows
  typed, and each problem said as what to do (`pgmalli-next.honeysql`: `check`, `arg-types`,
  `params-schema`, `row-schema`, `query-schema`, `tables`, `schema-problems`, `explain`).
* Generated files kept current in CI (`generate/check`, `generate/diff`, `pgmalli-next.main check`).

Supported, each and later: PostgreSQL 16, HoneySQL 2.4.1066, next.jdbc 1.3, pgjdbc 42.7,
malli 0.20 and Clojure 1.11; babashka for everything but JDBC.
