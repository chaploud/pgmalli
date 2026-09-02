# Contributing

Issues and pull requests are welcome.

- `bb test` runs the suite on babashka, `bb test:jvm` on the JVM. Both need docker for the
  database tests; `PGMALLI_SKIP_DB=1` skips them.
- `bb test:matrix` runs the suite against every PostgreSQL version CI covers.
- `bb lint` runs clj-kondo. `bb harvest` rebuilds the expression corpus (needs docker).
- Database tests start a throwaway PostgreSQL container; `PGMALLI_PG_IMAGE` selects the image.
- `clojure -T:build jar` builds; a release is a pushed `v*` tag. Dependency updates come from
  Renovate.
- `nix develop` (or direnv) provides clojure, babashka, clj-kondo and psql.
- When a change touches what becomes what, update the table in README.md and add a
  CHANGELOG entry. Changes to the config keys, the generated file layout or the fact
  vocabulary are breaking and bump the minor version.
