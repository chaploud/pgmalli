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

`a-query-wrapped-keeps-its-problems` asks no database: each case wrapped in a FROM subquery,
a CTE or EXISTS must have the problems it had alone.

After changing how `pgmalli.honeysql` resolves scopes or names, run `bb oracle` with a few
seeds. A query it got wrong goes into the corpus.

## Following upstream

`bb upstream` runs the JVM suite against the newest stable HoneySQL, next.jdbc, pgjdbc and
malli and `postgres:alpine` (`PGMALLI_PG_IMAGE` selects another), and prints what to update,
grouped by upstream. The upstream workflow runs it weekly, outside PR CI. Every failure under
the database fixture names the PostgreSQL, pgjdbc, next.jdbc, HoneySQL and malli versions that
ran. The tests that catch a change upstream, each naming the list to update:

| test | catches |
|---|---|
| `pgmalli.honeysql-contract-test` | a clause, operator or special syntax HoneySQL adds or drops; a change in how `sql/format` writes joins, `:in`, parameters and `:values` maps |
| `pgmalli.upstream-test` | a type of `pg_catalog` or of an extension the image ships that is neither in `types` nor `unsupported` (src/pgmalli/impl/types.clj), or that does not generate, insert and read back; a map builder next.jdbc adds or drops |

A type of PostgreSQL's or of an extension is one entry of `types` in
`src/pgmalli/impl/types.clj`, or one of `unsupported` there with why. To add one, move it to
`types` and run `pgmalli.upstream-test`: it creates every extension the PostgreSQL image ships,
fails on a type in neither list, and generates, inserts and reads back a NOT NULL column of
every entry.

A failing weekly run comments on the open issue labelled `upstream` (or opens it) with the
list; the next passing run closes it. GitHub disables a scheduled workflow after 60 days
without activity in the repository: `gh workflow enable upstream.yml` turns it back on.

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
version's section to CHANGELOG.md in the same commit: `## X.Y.Z (date)`, one short bullet per
change a user meets, a breaking one saying what to do. Fixes made before the release are no
entries. `clojure -T:build jar` builds a
snapshot of the last tag. Renovate updates the dependencies.
