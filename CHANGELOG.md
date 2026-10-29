# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Breaking

| was | is | to do |
|---|---|---|
| `as-read` typed dates as `:time/*`, arrays as vectors, json as maps | the driver's types: `inst?`, `:any` | pass `:time`, `:read` for the next.jdbc extensions you use |
| kebab keys `:line-no2` | `:line-no-2`, as next.jdbc's kebab builders name them | rename the keys you wrote by hand |
| an unknown option was ignored | an error naming the option meant | fix the spelling |
| `(row-schema registry body cte-names)` | `(row-schema registry body opts)` | drop the third argument |
| `statements`, `cte-names`, `scope`, `resolve-column`, `problems` public | private | use `check`, `row-schema`, `arg-types` |
| an array argument typed as the column's vector | `:any`, or what `:write`'s `:array` names | pass `{:write {:array [:pg/array :sequential]}}` |
| an `:in` argument typed as one value | a non-empty vector, list or set | pass a collection |
| `read-options` took `'next.jdbc/as-maps` | the builder's own name or var (`'next.jdbc.result-set/as-maps`) | rename the symbol |
| nil, a formatted SQL vector, or args not keywords were accepted | an error | pass HoneySQL data and `[:db :id]` |
| under `:qualified?`, a column through a CTE or subquery was keyed by its alias | nil: the driver keys it by a table pgmalli cannot see | select it from the table |
| `:missing-required-column`'s `:column` was a string, `:enum-literal`'s `:allowed` a set | a keyword; a vector in the enum's order | compare with those |
| `query-schema` was `[:=> in out]` | `[:=> {:pgmalli/problems [...]} in out]` when the query has problems | read the parts with `m/children` |
| `test.chuck` was a dependency | optional: regex strings use it when on the classpath | add it to your test alias |
| `pgmalli.main` exited 1 on any failure, with a stack trace | 1 stale or missing, 2 usage or config, 3 database or psql unavailable; one line on stderr | read the exit code |
| a config path that does not exist meant the defaults | an error | give an existing path, or none |
| `pgmalli.main` printed usage on stdout | stderr | |
| a config value of the wrong shape (`:schemas "public"`) was read | an error | fix the value |
| ex-data keys `:name`, `:unknown`, `:options`, `:builder` | `:given`, `:known`, `:suggest`, with `:pgmalli/error` and `:cognitect.anomalies/category` | dispatch on `:pgmalli/error` |

### Added

- HoneySQL named parameters (`:?id`, `[:param :id]`) in `arg-types` and `query-schema`.
- `:read`, `:write` and `{:compare :lenient}` for next.jdbc extensions and builders
  ([docs/reading-rows.md](docs/reading-rows.md), [docs/queries.md](docs/queries.md)).
- `:pg/array`: what the driver takes as an array parameter.
- `:db` with `:jdbc-url` or `:datasource` reads the catalog over JDBC, without psql.
- `query-schema` carries the query's problems and a `:missing-arg` as `:pgmalli/problems`.
- `check` reports a clause it does not read (`:unread-clause`), from the HoneySQL loaded.
- `explain` says each problem as what is wrong and what to do; `schema-problems` gathers the
  `:pgmalli/problems` of every var's `:malli/schema`, which instrumentation does not read. An
  `:unknown-column` names the columns it likely meant (`:did-you-mean`), and a problem in a
  subquery or CTE says where it sits (`:in`, as `get-in` reads the query).
- `check` reports a string literal compared with or assigned to an enum column without a cast
  (`:uncast-enum-string`): HoneySQL sends it as a parameter, which pgjdbc sends as `varchar`
  unless the connection has `stringtype=unspecified` (`{:stringtype :unspecified}`). An
  argument cast to an enum (`[:cast :?s :status]`) is typed as the enum.
- `check` reports writes PostgreSQL refuses: NULL into a NOT NULL column
  (`:not-null-violation`), including the NULL HoneySQL writes for a key one `:values` map
  lacks, and a value into a generated or identity ALWAYS column (`:read-only-column`).
  `:format` takes the options you pass to `sql/format` (`:values-default-columns` is read).
  A table with a BEFORE row trigger (`:pg/before-triggers`, a new table property) gets neither
  these nor `:missing-required-column`: the trigger may fill the row.
- Supported versions in the README, the oldest of them run in CI.
- A query wrapped in a FROM subquery, a CTE or EXISTS keeps its problems: a test asserts it
  over the oracle's cases.
- `bb oracle`: `check` against PostgreSQL itself on a corpus of queries and mutants of them;
  CI holds its false positives at none.
- `bb upstream` and a weekly workflow: the suite against the newest HoneySQL, next.jdbc, pgjdbc,
  malli and PostgreSQL, each failure naming the upstream change and the pgmalli list to update.
- `pgmalli.main check --edn` prints `generate/check`'s map.
- `pgmalli.main` lists unrendered facts with their expression and the override to write, and
  ends with a summary and the command to run next.
- Errors say what to do in one line: a misspelt registry name, config key or builder suggests
  the one meant; `as-read` of an insert schema names its row schema; a map that is no registry,
  or a generated data map, is an error.
- A database that cannot be reached, by psql or JDBC, is one line naming host:port/db, with
  the driver's error as the cause.
- `pgmalli.catalog-fuzz-test`: random schemas through registry, inserts, as-read and datasets against PostgreSQL (`PGMALLI_FUZZ_SEED`, `PGMALLI_FUZZ_SCHEMAS`, `PGMALLI_FUZZ_CASE`).

### Fixed

- A NULL in a CHECK's `[:or ...]` or `[:multi ...]` leaves the CHECK NULL, which PostgreSQL takes; `a > 5 OR b < 3` refused `{:a nil :b 5}`.
- A partitioned table whose only partition is DEFAULT takes every row; it took none.
- Partitions on an identity or generated column leave it to the database; insert rows needed it.
- A domain over a domain or enum is a reference to it, its CHECKs kept; it was `:any`.
- Types with quoted names (`"Mixed Case"`) are found; they were `:unknown-type`.
- Arrays of enums and domains are `[:vector [:ref ...]]`; they were `[:vector :any]`.
- `inserts` cast enum and domain values to the type as spelt (`CAST(? AS "s"."Mood")`), also under a CHECK; it upper-cased the name, and an array cast went unqualified.
- `inserts` leave `char(n)` values uncast (`CAST(x AS CHARACTER)` cut them to one character) and cast strings of `inet`, `bit`, `money` and the like, which pgjdbc's `varchar` cannot be written to.
- `inserts` write a row of defaults alone as `(col) VALUES (DEFAULT)`; it was `VALUES ()`.
- A column whose literal default its CHECK refuses is required in an insert.
- `numeric IN (1, 2, 5)` generates from the list and takes `2.0`; nothing was generated.
- A `~*` regex generates without test.chuck failing on `(?i)`.
- `timetz` generates offsets PostgreSQL takes; it went past 15:59.
- A `jsonb_typeof = 'array'` column generates JSON values; it generated characters and sets.
- Dates and times in domains and arrays generate within 2025; they were unbounded.
- A bounded `numeric(p, s)` generates at scale `s`; `0.0001` rounded to `0` and failed `> 0`.
- LIST partition keys generate their partitions' values.
- `as-read` reads a domain over a date, time or json as the driver returns it.
- A branch CHECK on a quoted column (`"Mixed Col" = 1 AND ...`) loads; malli read the string `:dispatch` as code.

- A `regoper` column generates `||/`; `+` names several operators and PostgreSQL refused it.
- `:string` bounds and `length`, `substr`, `left`, `right`, `strpos` count characters, as
  PostgreSQL does; `varchar(3)` refused `"😀😀"`.
- A CHECK on a `char(n)` column is evaluated with `char(n)`'s trailing spaces insignificant
  (except in `LIKE`, regexes, `octet_length`); `length(c) <= 2` refused `"ab  "` as pgjdbc reads it.
- A regex `$` matches only at the end (`"abc\n" ~ '^abc$'` passed), and `.` and `LIKE`'s `%`
  and `_` match a newline in the rendered `[:re ...]` (`"a\nb"` was refused).
- A cast to an integer rounds a float's ties to even (`2.5::float8::int` is 2, it was 3).
- `smallint`, `integer` and `bigint` arithmetic over columns of those types fails the row out
  of the type's range, as PostgreSQL raises "integer out of range"; a cast out of range too.
- A cast to boolean takes what PostgreSQL takes: any unique prefix of `true`, `false`, `yes`,
  `no`, `on`, `off` (`'tr'`, `'of'`).
- `trim`, `btrim`, `ltrim` and `rtrim` remove spaces only, and `length(trim(c)) > 0` renders
  `[:re "[^ ]"]`; a tab or newline was refused.
- `date + interval` is a timestamp; the interval's hours were dropped. Unary minus is
  evaluated; it failed the row.
- A `:seed` gives the same rows in every run: times generate within 2025, not the year before
  now. The columns of a CHECK's branches (`[:multi ...]`) generate as the row's columns do;
  a `closed_at` generated in a branch came out near 1970.
- `generate/check` lists the schemas with no generated file under `:missing`; with
  `{:db? false}` a missing file passed.
- `check` reports no error PostgreSQL does not: `USING` columns, schema-qualified tables,
  identifiers in another case or with `-`, whole-row references, system columns, output
  columns in `:order-by`, an inner alias hiding an outer one, the target of an UPDATE under a
  CTE of its name.
- `check` reports errors it missed: a name each part of a statement cannot see (a subquery's
  sibling in `:from`, a later `:with` entry, the insert target in `:values`), an enum literal
  in any comparison, code-built joins making tables nullable.
- `row-schema` names unaliased expressions as PostgreSQL does, reads set operations and
  `{:select [:*]}`, and makes outer-joined columns nullable.
- `pgmalli.honeysql` throws ex-info, not another exception, on a query of any shape (a test
  breaks the oracle corpus at random), and on a registry that is none (`"public"`, nil).
- kebab and camel names as PostgreSQL folds them: an INSERT's `:group-id` key is `group_id`
  (it was a `:missing-required-column`), an alias in `:order-by` / `:group-by` matches its
  folded name, and `row-schema` keys an aliased column as next.jdbc does (`:user_id` for
  `:user-id`). A bare keyword clause value (`{:select :id}`, `:order-by :x`, `:returning :x`)
  is read as HoneySQL reads it.
- `check` reads names as HoneySQL writes them: `:system_user`, `:'x` (written as `x`), a
  namespaced column in `:set` and `:values` (the namespace dropped), a `:values` key a column
  list leaves out (not written); each was reported. A keyword assigned in `:set` is checked as
  a column, and a statement keyed by symbols (`'{select ...}`) is an `:unread-clause`.
- `as-read` and `row-schema` type what the driver returns for `real` (`Float`), `bit(1)`
  (`Boolean`), `bit(n)`, `bit varying` and `citext` (`PGobject`), a numeric `NaN` (`Double`),
  and `time` and `timetz` under `:time :instant` / `:local` (`java.sql.Time`): those rows
  failed. A test reads one row of every type through every builder and `:time`.
- The JDBC catalog read reports the query's own failure and names a missing driver.
- `pgmalli.main` no longer takes a minute to exit after psql.
- The jar carries no file generated in the checkout.

## [0.2.45] - 2026-09-04

### Changed

- `pgmalli.impl.runtime` is split by concern into `pgmalli.impl.shape` (schema data as shapes),
  `pgmalli.impl.pgtypes` (the `:pg/*` schema types), `pgmalli.impl.registry` (generated files as
  registries), `pgmalli.impl.portable`, `pgmalli.impl.jdbc`, `pgmalli.impl.dataset` and
  `pgmalli.impl.insert`. The public namespaces are unchanged.
- `pgmalli.impl.render/registry` is `rendered` (what it returns is the file's `:registry` map,
  not a malli registry), `pgmalli.impl.generate` is `pgmalli.impl.files`, and the diff algorithm
  behind `pgmalli.generate` is `pgmalli.impl.diff`.
- Dataset generation binds one validator and one row generator per table instead of building
  them per row, and `pgmalli.impl.eval` compiles a CHECK's regexes once instead of once per row;
  generated datasets are unchanged.
- `as-read` lives in `pgmalli.impl.jdbc` beside the other JDBC row shapes, and
  `pgmalli.core/as-read` takes the options as every other public function does (default `{}`).
- `pgmalli.data/inserts` returns a vector, so the errors its docstring names are thrown by the
  call rather than on realizing the result.
- `portable`, `column`, `columns` and `as-read` throw `no schema named X` (ex-data `{:name
  :known}`) for a name the registry does not hold, where two of them returned nil.
- `bb sweep:report` and `bb fixture` are tasks, `bb harvest` takes the pgschema corpus from
  `PGMALLI_PGSCHEMA_DIR` and leaves it out (saying so) when it is not there.
- `pgmalli.honeysql` reads `:outer-join`, so its tables are in scope; the opaque types are one
  list, the insert and update names one function, and the strip-casts, entry-access and
  `:any`-to-`:some` steps one each.

### Fixed

- A `NOT NULL` column whose type renders `:any` (a `jsonb` column with no CHECK to shape it, an
  opaque type) is `:some`, so it rejects NULL as the README says; it took nil, and a generated
  row the database refused.
- `portable`, and `pgmalli.honeysql`'s `row-schema` and `query-schema`, threw on a composite
  registry (`malli.registry/composite-registry`) instead of inlining its references.

## [0.2.44] - 2026-09-04

### Changed

- Three namespaces by what they need: `pgmalli.core` reads the generated files (`registry`,
  `generated`, `install!`, `columns`, `column`, `non-null`, `portable`, `as-read`,
  `read-options`, `transformer`); `pgmalli.generate` needs psql (`generate!`, `stale`, `check`);
  `pgmalli.data` needs test.check (`dataset-schema`, `dataset-generator`, `short-tables`,
  `inserts`, `write-dataset`, `read-dataset`). `unrendered` and `diagnostics` are keys of
  `generated`'s map.
- The generated file is written in reading order: `:schema`, `:database-version`,
  `:diagnostics`, `:registry`, `:unrendered`, `:skipped`. `generate` prints what it found.

### Fixed

- `pgmalli.honeysql`: the SELECT of an `INSERT ... SELECT` saw the table inserted into as an
  enclosing scope, so a column of the target's went unreported; it no longer does. Columns
  assigned by `ON CONFLICT DO UPDATE SET` (a map, a vector, or `{:fields [...] :where ...}`,
  where `EXCLUDED` is the row proposed for insertion) and named as they are in `:group-by` and
  `:order-by` are checked too.
- `transformer` decodes text into `:pg/integer`, `:pg/smallint` and `:pg/numeric` columns, as
  it did into `:int` ones.
- A column or domain with a regex CHECK (`col ~ '...'`, `LIKE`) could not be generated: malli
  drew random strings and filtered them. The registry now generates such a schema from the
  regex (test.chuck, a dependency now), and an `[:and ...]` holding a reference (an override,
  a domain) from the reference.
- Every function taking a registry takes any malli registry (a `composite-registry` of
  pgmalli's and your own included), not only the map `registry` returns.
- A NOT NULL column of a domain type let NULL through: the domain rendered `[:maybe ...]`
  unless it was NOT NULL itself, and `[:ref ...]` reads the domain as it is. A domain is now
  what a non-NULL value must be; whether NULL is allowed is the column's.
- A domain over `numeric(12,2)` or `varchar(80)` lost the precision or the length.
- A partitioned table's partition CHECK repeated the parent's own CHECKs from every leaf.

### Changed

- `:pg/generated` holds the expression the database computes the column from (`true` when it
  could not be read); a unique index in `:pg/unique` is marked `:index true`.

### Added

- `:pg.public.<table>/update`: what an UPDATE may set, next to the insert schema.
- `:overrides` with a keyword key names a type: every column of that type gets the schema.
- `pgmalli.generate/diff`: the differences between two generated files, as `stale` reports
  them, a migration's effect read from the files alone.
- `pgmalli.core/install!`: the registry as malli's default one, so `:malli/schema`,
  `malli.dev/start!` and `malli.instrument` read the generated names directly; it returns what
  the default registry held before, for putting back.
- `generate` prints the diagnostics after writing a file.
- `pgmalli.core/read-options`: the reading options of a next.jdbc result set builder, by name.
- `pgmalli.generate/check`: `{:stale :unrendered :diagnostics}` in one read, what CI asks.
- `pgmalli.data/write-dataset` and `read-dataset`: a dataset as EDN with `java.time` values
  and byte arrays under pgmalli's tags, for fixtures kept in the repository.
- `pgmalli.data/short-tables`: the tables a generated dataset could not fill, and why.
- `pgmalli.honeysql/query-schema` takes `{:result :one}` for a function returning one row.
- `:diagnostics` names a table's row-level INSERT triggers: their code may reject or change
  rows the schema accepts, which the catalog does not show.

## [0.2.43] - 2026-09-03

### Added

- `:diagnostics` in the generated file, `pgmalli/diagnostics`, printed by `check`: states the
  database stores but no row can satisfy or that deserve a look (a partitioned table with no
  partition, an unreachable partition, `CHECK (false)`, contradicting CHECKs on a column, a
  NOT VALID constraint, a unique index repeating a key), each with a kind, severity and
  confidence.

### Changed

- `numeric(p, s)` columns are `[:pg/numeric {:precision p :scale s}]`: the value is rounded to
  `s` places as PostgreSQL rounds it, then must have fewer than `p - s` digits before the
  point. The scale is read signed, so `numeric(2,-3)` and `numeric(3,5)` are right.
- `pgmalli/inserts` emits one INSERT per table, `DEFAULT` for a column a row lacks, and
  refuses a column the table does not have. Rows referencing each other in a cycle load.
- `pgmalli.honeysql` reads an alias listing its columns (`[:v {:columns [...]}]` over VALUES),
  takes `:current_timestamp` and the other argument-less SQL words for what they are rather
  than columns, and a CTE entry with a qualifier (`[:name query :materialized]`).
- `pgmalli.honeysql` reads every `:insert-into` shape HoneySQL accepts (an option map such as
  `{:overriding-value :system}`, `:columns` with positional rows, which it checks for arity,
  unknown and required columns, enum literals and parameter types), both sides of a
  comparison, `:select-top`, and CTEs as lexical: a `:with` is visible inside its statement
  only.
- A unique index bearing a CHECK constraint's name is read as well as the constraint.
- A partitioned table's row schema carries the OR of its leaf partitions' bounds as a CHECK,
  so a generated row lands in a partition (a sub-partitioned child with no leaves takes none).
  A hash partition's bound (`satisfies_hash_partition`) is evaluated with PostgreSQL's own hash
  functions (`pgmalli.impl.pghash`, checked against the database), for integer, text, boolean,
  date, timestamp, uuid and bytea keys.
- A CHECK reading a generated column, and a domain on a generated column, are checked on the
  column's expression, as the database computes it: `b GENERATED ALWAYS AS (a * 2)` with
  `CHECK (b < 50)` bounds `a`.
- `xid`, `xid8` and `cid` are opaque (the database takes no integer for them).
- A `NOT VALID` CHECK is enforced, as a whole `[:pg/check {:pg/not-valid true} ...]`: the
  database rejects a new row that violates it, so a dataset must not carry one. It was kept
  in `:unrendered` before.
- A partitioned table with no partition takes no row (`CHECK (false)`); a leaf partition's own
  CHECKs are part of the parent's partition CHECK.
- An array of a type pgmalli cannot write a value of generates an empty array.
- A numeric column bounded by CHECKs (`[:and decimal? [:> 1] [:< 1000]]`) generates within the
  bounds instead of drawing any decimal and failing to find one that fits.
- More types: `oid`, `xid`, `xid8`, `cid` are `:int`; `"char"` a string; `bit(n)` and `bit
  varying(n)` strings of digits with their length; `inet`, `cidr`, `macaddr`, `money`, `xml`,
  `tsvector`, `tsquery`, `jsonpath`, the geometric, range and multirange types, `pg_lsn` and
  the `reg*` types are `:any` with their `:pg/type` (no longer unknown) and generate literals
  the database reads. `varchar(n)[]` bounds its elements.
- The CHECK parser reads `IS [NOT] JSON ...`, named arguments (`min => 10`), `t.*` and
  `COLLATE` (left out: the value is the same); a `CHECK (false)` is no longer taken as unparsed.
- A column of a type pgmalli cannot write a value of generates NULL; a NOT NULL column of such
  a type leaves its table short, with the reason, instead of carrying a value the database
  refuses.
- A `NOT ENFORCED` CHECK or FOREIGN KEY (PostgreSQL 18) is not applied, since the database
  never checks it; it is noted in `:diagnostics`.
- `pgmalli.impl.ir` names every catalog table with `pg_catalog.`, so a schema defining a table
  of the same name does not break the read.
- `test.check` is a direct dependency, as the generators use it directly.

### Fixed

- `numeric(3,5)` and `numeric(2,-3)`, which PostgreSQL allows, threw when the registry loaded.
- An alternative of an OR that no row can match (a partition left unreachable by its parent's
  bounds renders as `30 <= id < 30`) is left out of `[:or ...]`; a generator looked for a row
  in it forever.
- Nested LIST partitions (`a IN (1, 2)` then `a = 1`) pinned every value of the outer list,
  so two branches carried the same value and the registry failed to load with duplicate keys;
  the pins are intersected, and two alternatives pinning one value become `[:or ...]`.
- A branch that names its own dispatch column (as a LIST partition's bounds do, `c IS NOT NULL
  AND c = 'x'`) had the value that picked the branch regenerated, so every row fell to the
  default branch and the table came out empty.
- A `NOT NULL` jsonb column with no shaping CHECK generated non-JSON values.

## [0.2.42] - 2026-09-03

### Added

- `pgmalli/inserts`: a dataset as HoneySQL INSERT maps, parents first and rows referred to
  first, enum / json / array values in the form the driver needs, generated columns left out,
  identity values kept.

- Unique indexes over plain columns (no expression, no predicate) are read as `:pg/unique`,
  since datasets must respect them as much as UNIQUE constraints.
- A generated range column (`tsrange(valid_from, valid_until)`) gives its two columns a CHECK,
  named `<column>_generated`, since the database refuses a range whose bounds are reversed.
- `pgmalli/inserts` takes `{:on-conflict :nothing}` for a database that already holds some rows.

### Fixed

- A branching CHECK (`col = 'a' AND ... OR col = 'b' AND ...`) let every other value of the
  column through; now only NULL passes without a branch, as the database has it.
- `col IS NOT NULL` on a json or jsonb column (or any column of type `:any`) did not reject
  NULL; it is `:some` now, and the dataset generator draws such a column from the column's
  own type rather than from `:some`.

### Changed

- `pgmalli/stale` returns the differences by registry entry and, for row and insert schemas,
  by column, property and CHECKs, each with its file and database sides; `check` prints one
  line per difference instead of the EDN diff.
- A json or jsonb column with no CHECK shaping it generates JSON values (strings, numbers,
  booleans, small maps and vectors) rather than any value.
- `pgmalli.honeysql`: an ambiguous column problem carries the tables it could belong to, under
  `:candidates`.

## [0.2.41] - 2026-09-03

### Added

- `pgmalli/column` and `pgmalli/non-null`: one column's schema, with and without its
  `[:maybe ...]`.
- `pgmalli/as-read`: the row map as a JDBC result builder returns it (qualified keys, absent
  NULL columns, kebab-case keys, timestamps as Instants or LocalDateTimes).
- `pgmalli/portable`: the named schema as data malli's default registry reads, for
  `:malli/schema` metadata and tools that cannot take the registry.
- `pgmalli.honeysql`: HoneySQL query data checked against the registry (tables; selected,
  inserted, set and compared columns; required INSERT columns; enum literals) and typed
  (parameters, rows, a function schema for instrumentation), without a database.

## [0.2.40] - 2026-09-03

### Added

- Views and materialized views as `:pg.<schema>/<view>` row schemas: columns and types, every
  column nullable, `:pg/view` on the map. They get no insert schema and are not part of
  datasets.

## [0.2.38] - 2026-09-03

### Changed

- `smallint` and `integer` columns are `:pg/smallint` and `:pg/integer`, schema types
  pgmalli registers with the PostgreSQL range inside, narrowed by the `:min` and `:max` a
  CHECK adds; the generated files no longer spell the range out.
- `:database-version` is the server version alone (`"PostgreSQL 17.6"`), the same on every
  machine that generates.

## [0.2.35] - 2026-09-03

### Added

- `:pg/check` and `:pg/check-value`: every CHECK that no column pattern covers is kept as
  expression data and evaluated with PostgreSQL's semantics (NULL passes, `AND`, `OR` and
  `COALESCE` stop at the first decisive operand, casts convert, the schema's own enum and
  domain literals are values, `now()` is the validation time). The vocabulary covers
  comparison, logic, arithmetic, the common string, numeric, array and jsonb functions and
  operators, `LIKE`, regexes and `CASE`; a CHECK outside it is listed in `:unrendered`.
  jsonb values with string or keyword keys are read alike.
- CHECKs that branch on one column's value become `[:multi ...]`; ORs of column patterns
  `[:or ...]`; neither is ever enforced in part.
- `:pg.<schema>.<table>/insert` schemas, derived when a registry is loaded: identity ALWAYS
  and generated columns removed, identity BY DEFAULT, defaulted and nullable columns
  optional, closed maps; the table's constraints see omitted columns as their literal
  defaults, else NULL.
- Row schemas carry `:pg/table` (`"schema.table"`), `:pg/primary-key`, `:pg/unique`
  (`{:columns}` maps, `:nulls-distinct false` for NULLS NOT DISTINCT) and `:pg/foreign-keys`
  (`{:columns :table :to}` maps, `:match :full` for MATCH FULL) on the map; columns carry
  `:pg/identity` (`:always`, `:default`, `:serial`) and `:pg/generated`.
- Domain types as registry entries: base type, column patterns, else `[:pg/check-value expr]`;
  a domain's NOT NULL and DEFAULT reach its columns; `:overrides` apply to domain CHECKs.
- Column patterns for `LIKE`, `NOT IN`, `cardinality`, `array_length`, boolean and uuid value
  sets; bounds and value sets from several constraints tighten and intersect.
- `smallint` and `integer` carry their range; `numeric(p, s)` its magnitude bound; `bytea`
  with a length CHECK is `[:pg/bytes {:min :max}]`, a type that generates byte arrays.
- `date`, `time`, `timetz`, `timestamp`, `timestamptz` and `interval` map to
  `malli.experimental.time` schemas.
- `pgmalli/columns`, `pgmalli/transformer` (with a `:zone` option; JSON text in json and
  jsonb columns is parsed), `pgmalli/dataset-schema` (every key set and reference a named
  check; NULLS NOT DISTINCT and MATCH FULL respected) and `pgmalli/dataset-generator`
  (`:rows`, `:except`; references sharing columns solved together, self-references included;
  a reference that finds no fitting row grows its target table; tables that come out short
  are listed in the dataset's metadata with the reasons). Registries add generation hints when
  loaded, so key columns are small positive integers, strings short and times recent.
- POSIX character classes in regexes are translated to their Java form; patterns using
  PostgreSQL-only escapes stay unrendered.
- Types outside the mapping table are reported as `:unknown-type`.

### Changed

- Files generated by 0.1 are refused when loaded; regenerate them.
- Registries load from the classpath by schema name: `(pgmalli/registry "public")`. malli is
  a dependency of the library.
- Trimmed non-blank strings are `[:and [:string {:min 1}] [:re "\S"]]`.
- Schema names that are not plain identifiers make string registry keys.
- Generating needs PostgreSQL 16 or later.

### Removed

- `pgmalli/path` and the by-path forms of `registry` and `unrendered`.

## [0.1.9] - 2026-09-02

### Added

- Generate malli registries from an applied PostgreSQL schema through `psql`.
- Public API `pgmalli.core` (`generate!`, `path`, `registry`, `stale`, `unrendered`) and the
  `pgmalli.main` command line.
- Fixed renderings for enum types, value sets, ranges, non-blank strings, lengths, jsonb types,
  regexes and conditional nullability; everything else is reported as unrendered.
