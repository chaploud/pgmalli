# Contributing

Issues and pull requests are welcome.

- `bb test` runs the suite on babashka, `bb test:jvm` on the JVM. Both need docker for the
  database tests; `PGMALLI_SKIP_DB=1` skips them.
- `bb test:matrix` runs the suite against every PostgreSQL version CI covers.
- `bb lint` runs clj-kondo. `bb harvest` rebuilds the expression corpus (needs docker;
  `PGMALLI_PGSCHEMA_DIR` adds a checkout of pgschema's testdata to it).
- `bb sweep <sql-dir> <out-dir> [glob]` takes SQL files through the whole pipeline and writes
  what did not go through as EDN (needs docker); `bb sweep:report <out-dir>` groups those
  findings for reading.
- `bb fixture` rewrites the checked-in `test/resources/pgmalli/*.edn` the tests read.
- `bb oracle [seed] [mutants-per-query]` (default 42 and 8, about 2000 queries; needs docker)
  runs the queries of `test/resources/pgmalli/oracle/corpus.edn`, and seeded mutants of them, on
  PostgreSQL beside `pgmalli.honeysql/check`, and prints what disagrees: a false positive (a
  query PostgreSQL takes that `check` faults), a miss (one it refuses that `check` lets pass).
  `pgmalli.oracle-test`, in `bb test:jvm`, holds the false positives at none. A change to how
  `pgmalli.honeysql` reads scopes or names gets a run of `bb oracle` with a few seeds; a query
  it got wrong goes into the corpus.
- Database tests start a throwaway PostgreSQL container; `PGMALLI_PG_IMAGE` selects the image.
- `clojure -T:build jar` builds a snapshot of the last tag. A release is a pushed `vX.Y.Z` tag:
  the tag is the version, chosen by hand along semantic versioning (patch: fixes; minor: additions,
  and before 1.0 anything that changes the contract; major: contract changes after 1.0). Add
  the version's section to CHANGELOG.md in the same commit. Dependency updates come from Renovate.
- `nix develop` (or direnv) provides clojure, babashka, clj-kondo and psql.
- When a change touches what becomes what, update the table in README.md and add a
  CHANGELOG entry. Changes to the config keys, the generated file layout or the fact
  vocabulary are breaking and bump the minor version.
