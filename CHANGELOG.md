# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- The registry is loaded from the classpath by schema name: `(pgmalli/registry "public")`.
  malli is now a dependency of the library. `pgmalli/path` and the by-path forms of
  `registry` and `unrendered` are gone.
- Row schemas carry `:pg/table` (`"schema.table"`), `:pg/primary-key`, `:pg/unique` and
  `:pg/foreign-keys` (`{:columns :table :to}`, composite keys included) on the map, and
  `:pg/identity` (`:always`, `:default`, `:serial`) and `:pg/generated` on columns.
- CHECKs that branch on one column's value become `[:multi ...]`; ORs of column patterns
  become `[:or ...]`; every other CHECK is `[:pg/check expr]`, a schema type registered by
  pgmalli that evaluates the expression data with PostgreSQL's semantics. Facts that could not
  be rendered inside a branch are listed in `:unrendered` like column facts.
- `date`, `time`, `timetz`, `timestamp`, `timestamptz` and `interval` map to
  `malli.experimental.time` schemas.
- Domain types are registry entries built from their base type and CHECK.
- Trimmed non-blank strings are `[:and [:string {:min 1}] [:re "\S"]]`.
- POSIX character classes in regexes (`[[:digit:]]`) are translated to their Java form;
  patterns using PostgreSQL-only escapes stay unrendered.
- `smallint` and `integer` carry their range; `numeric(p, s)` its magnitude bound.
- Bounds and value sets from several constraints tighten and intersect instead of the last one
  winning.
- Column patterns for `LIKE`, `NOT IN`, `cardinality`, `array_length`, boolean and uuid value
  sets. A CHECK whose column pattern has no rendering on its column's type is evaluated whole.
- Domains are always registry entries; a domain CHECK outside the patterns is
  `[:pg/check-value expr]`; a domain's NOT NULL and DEFAULT reach its columns; `:overrides`
  apply to domain CHECKs.
- `:pg/unique` entries are `{:columns ...}` maps (`:nulls-distinct false` for `NULLS NOT
  DISTINCT`); `:pg/foreign-keys` entries carry `:match :full` for `MATCH FULL`; datasets
  respect both, check every constraint separately and name it in the error.
- The dataset generator solves references that share columns together (tenant-style
  composite keys) and adds generation hints for realistic values.
- Types outside the mapping table are reported as `:unknown-type` instead of silently
  becoming `:any`.
- Schema names that are not plain identifiers make string registry keys.
- `BETWEEN SYMMETRIC` is evaluated as such.

### Added

- `:pg.<schema>.<table>/insert` schemas, derived when a registry is loaded: identity ALWAYS
  and generated columns removed, defaulted and nullable columns optional, closed maps; table
  constraints see omitted columns as their literal defaults.
- `pgmalli/columns`, `pgmalli/transformer` (with a `:zone` option), `pgmalli/dataset-schema`,
  `pgmalli/dataset-generator`.

## [0.1.9] - 2026-09-02

### Added

- Generate malli registries from an applied PostgreSQL schema through `psql`.
- Public API `pgmalli.core` (`generate!`, `path`, `registry`, `stale`, `unrendered`) and the
  `pgmalli.main` command line.
- Fixed renderings for enum types, value sets, ranges, non-blank strings, lengths, jsonb types,
  regexes and conditional nullability; everything else is reported as unrendered.
