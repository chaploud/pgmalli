# Contributing

Issues and pull requests are welcome.

## Setup

`nix develop` (or direnv) provides clojure, babashka, clj-kondo and psql. The database tests
need docker: they start a throwaway PostgreSQL container (`PGMALLI_PG_IMAGE` selects the
image).

## Tests

| command | runs |
|---|---|
| `bb lint` | clj-kondo |
| `bb test` | the suite on babashka (`PGMALLI_SKIP_DB=1` skips the database tests) |
| `bb test:jvm` | the suite on the JVM |
| `bb test:matrix` | the suite on every PostgreSQL version CI covers |
| `bb test:docs` | the code blocks of README.md and docs/, each `;; =>` asserted |
| `bb oracle [seed] [mutants]` | `pgmalli.honeysql/check` against PostgreSQL itself |

## Documentation

The examples run against [docs/example/schema.sql](docs/example/schema.sql). After changing
it, `bb docs:example` generates `docs/example/pgmalli/` again; a test fails until it does. A
block that cannot run (a shell command, a database call) gets `<!-- :test-doc-blocks/skip -->`
before it.

When a change touches what a type or CHECK becomes, update [docs/types.md](docs/types.md)
and add a CHANGELOG entry.

## Randomized tests

`fuzz-test`, `honeysql-robust-test` and the oracle draw their cases from a seed, 42 in CI.
`PGMALLI_FUZZ_SEED=<n> bb test:jvm` runs other seeds. A failure prints its seed, the case
(shrunk where test.check shrinks it), where it failed and the command that runs it again.

## The PostgreSQL oracle

`bb oracle` (default seed 42, 8 mutants per query, about 2000 queries) runs the queries of
`test/resources/pgmalli/oracle/corpus.edn`, and seeded mutants of them, on PostgreSQL, each
sent twice: its values inlined, and as parameters the way HoneySQL and pgjdbc send them. It
prints where `check` disagrees: a false positive (PostgreSQL takes the query, `check` faults
it) or a miss (PostgreSQL refuses it, `check` lets it pass). `pgmalli.oracle-test` holds the
false positives at none in `bb test:jvm`.

After changing how `pgmalli.honeysql` resolves scopes or names, run `bb oracle` with a few
seeds. A query it got wrong goes into the corpus.

## Other tasks

- `bb harvest` rebuilds the expression corpus (`PGMALLI_PGSCHEMA_DIR` adds a checkout of
  pgschema's testdata).
- `bb sweep <sql-dir> <out-dir> [glob]` takes SQL files through the whole pipeline and writes
  what did not go through as EDN; `bb sweep:report <out-dir>` groups it for reading.
- `bb fixture` rewrites the checked-in `test/resources/pgmalli/*.edn` the tests read.

## Releases

A release is a pushed `vX.Y.Z` tag; the tag is the version. Choose it by semantic
versioning: patch for fixes, minor for additions and, before 1.0, any change to the
contract. Changes to the config keys or the generated file layout are breaking. Add the
version's section to CHANGELOG.md in the same commit. `clojure -T:build jar` builds a
snapshot of the last tag. Renovate updates the dependencies.
