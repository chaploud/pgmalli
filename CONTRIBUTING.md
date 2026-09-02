# Contributing

Issues and pull requests are welcome.

- `bb test` runs the suite on babashka, `bb test:jvm` on the JVM. Both need docker for the
  database tests; `PGMALLI_SKIP_DB=1` skips them.
- `bb test:matrix` runs the suite against PostgreSQL 14 to 18, which is what CI does.
- `bb lint` runs clj-kondo.
- `nix develop` (or direnv) provides clojure, babashka, clj-kondo and psql.
- When a change touches what becomes what, update the table in README.md and add a
  CHANGELOG entry. Changes to the config keys, the generated file layout or the fact
  vocabulary are breaking and bump the minor version.
