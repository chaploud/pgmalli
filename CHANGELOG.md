# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased]

### Changed

- The registry is loaded from the classpath by schema name: `(pgmalli/registry "public")`.
  malli is now a dependency of the library; `path`, `registry` by path and `unrendered` by
  path are gone.
- Row schemas carry `:pg/primary-key` and `:pg/unique` on the map and `:pg/references`,
  `:pg/identity` (`:always`, `:default`, `:serial`) and `:pg/generated` on columns.
- CHECKs that branch on one column's value become `[:multi ...]`; ORs of column patterns
  become `[:or ...]`. Compilation into `[:fn ...]` is opt-in with `:checks :fn`.
- `date`, `time`, `timestamp`, `timestamptz` and `interval` map to `malli.experimental.time`
  schemas; `pgmalli/transformer` decodes JDBC and string values into them.
- Domain types are registry entries built from their base type and CHECK.
- Trimmed non-blank strings are `[:and [:string {:min 1}] [:re "\S"]]`.

### Added

- `:pg.<schema>.<table>/insert` schemas: identity ALWAYS and generated columns removed,
  defaulted and nullable columns optional, closed maps.
- `pgmalli/columns`, `pgmalli/transformer`, `pgmalli/dataset-schema`, `pgmalli/dataset-generator`.

- Generate malli registries from an applied PostgreSQL schema through `psql`.
- Public API `pgmalli.core` (`generate!`, `path`, `registry`, `stale`, `unrendered`) and the
  `pgmalli.main` command line.
- Fixed renderings for enum types, value sets, ranges, non-blank strings, lengths, jsonb types,
  regexes and conditional nullability; everything else is reported as unrendered.
