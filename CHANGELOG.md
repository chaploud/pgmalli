# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- `numeric(p, s)` columns are `[:pg/numeric {:precision p :scale s}]`: the value is rounded to
  `s` places as PostgreSQL rounds it, then must have fewer than `p - s` digits before the
  point. The scale is read signed, so `numeric(2,-3)` and `numeric(3,5)` are right.
- `pgmalli/inserts` emits one INSERT per table, `DEFAULT` for a column a row lacks, and
  refuses a column the table does not have. Rows referencing each other in a cycle load.
- `pgmalli.honeysql` reads every `:insert-into` shape HoneySQL accepts (an option map such as
  `{:overriding-value :system}`, `:columns` with positional rows, which it checks for arity,
  unknown and required columns, enum literals and parameter types), both sides of a
  comparison, `:select-top`, and CTEs as lexical: a `:with` is visible inside its statement
  only.
- A unique index bearing a CHECK constraint's name is read as well as the constraint.
- A partitioned table's row schema carries the OR of its partitions' bounds as a CHECK, so a
  generated row lands in a partition.
- More types: `oid`, `xid`, `xid8`, `cid` are `:int`; `"char"` a string; `bit(n)` and `bit
  varying(n)` strings of digits with their length; `inet`, `cidr`, `macaddr`, `money`, `xml`,
  `tsvector`, `tsquery`, `jsonpath`, the geometric, range and multirange types, `pg_lsn` and
  the `reg*` types are `:any` with their `:pg/type` (no longer unknown) and generate literals
  the database reads. `varchar(n)[]` bounds its elements.
- The CHECK parser reads `IS [NOT] JSON ...`, named arguments (`min => 10`) and `t.*`; a
  `CHECK (false)` is no longer taken as unparsed.
- `pgmalli.impl.ir` names every catalog table with `pg_catalog.`, so a schema defining a table
  of the same name does not break the read.
- `test.check` is a direct dependency, as the generators use it directly.

### Fixed

- `numeric(3,5)` and `numeric(2,-3)`, which PostgreSQL allows, threw when the registry loaded.
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
