# Contributing

Issues and pull requests are welcome.

## Setup

`nix develop` (or direnv) provides clojure, babashka, clj-kondo and psql. The database tests
need docker: they start a throwaway PostgreSQL container (`PGMALLI_PG_IMAGE` selects the image).
`bb test`, `bb test:jvm`, `bb test:matrix`, `bb test:floor`, `bb oracle`, `bb docs:example`,
`bb harvest`, `bb sweep`, `bb upstream` and `bb mutate --db` need it; `bb lint`, `bb test:docs`
and `bb fixture` do not.

## Tests

| command | runs |
|---|---|
| `bb lint` | clj-kondo |
| `bb test` | the suite on babashka (`PGMALLI_SKIP_DB=1` skips the database tests) |
| `bb test:jvm` | the suite on the JVM |
| `bb test:matrix` | the suite on every PostgreSQL version CI covers |
| `bb test:docs` | the code blocks of README.md and docs/, each `;; =>` asserted |
| `bb test:floor` | the suite on the JVM with the oldest versions README supports |
| `bb oracle [seed] [mutants]` | `pgmalli.honeysql/check` against PostgreSQL itself |
| `bb test:update [ns ...]` | each failing `(is (= literal form))` written back with what it got (`bb test:update:jvm` for the namespaces that need next.jdbc); prints what it left and why |
| `bb test:docs:update [file.md ...]` | each `;; =>` of the docs written back with what its form gives |
| `bb mutate --file src/... [--kind table\|logic] [--ns ns ...] [--fn def ...] [--limit N] [--jvm] [--db] [--timeout secs]` | each mutant of the file (an element left out of a def's literal collection; and/or, when/when-not, =/not=, true/false swapped) against the tests (`--ns`, or the bb suite, or the JVM suite under `--jvm`; database tests only under `--db`), each run killed after `--timeout` seconds, the file written back after each; prints the survivors with file:line and writes `--out` (target/mutate.edn) |

Before a pull request: `bb lint` → `PGMALLI_SKIP_DB=1 bb test` → `bb test` → `bb test:jvm` →
`bb test:floor` → `bb oracle` → `bb test:docs`. After `bb test:update` or `bb test:docs:update`,
read the git diff: a rewritten expectation is a changed behaviour.

`bb test` runs every `*_test` namespace but those that need next.jdbc. One namespace or one test
on the JVM: `clojure -M:test -n pgmalli.honeysql-test`,
`clojure -M:test -v pgmalli.honeysql-test/some-test`.

## Documentation

The examples run against [docs/example/schema.sql](docs/example/schema.sql). After changing
it, `bb docs:example` generates `docs/example/pgmalli/` again; a test fails until it does. A
block that cannot run (a shell command, a database call) gets `<!-- :test-doc-blocks/skip -->`
before it.

When a change touches what a type or CHECK becomes, update [docs/types.md](docs/types.md).

## Randomized tests

`fuzz-test`, `catalog-fuzz-test`, `honeysql-robust-test` and the oracle draw their cases from a
seed, 42 in CI. `PGMALLI_FUZZ_SEED=<n> bb test:jvm` runs other seeds. A failure prints its seed,
the case (shrunk where test.check shrinks it), where it failed and the command that runs it
again, with `PGMALLI_FUZZ_SEED`, `PGMALLI_FUZZ_CASE` or `PGMALLI_FUZZ_SCHEMAS` set.

## The PostgreSQL oracle

`bb oracle [seed] [mutants]` compares `pgmalli.honeysql` with PostgreSQL itself, on the
database of `test/resources/pgmalli/oracle/schema.sql`. The seed is `PGMALLI_FUZZ_SEED` or 42,
the number of mutants per query `PGMALLI_ORACLE_MUTANTS` or 8. The queries are:

- the corpus, `test/resources/pgmalli/oracle/corpus.edn` (`[id query opts?]`, opts merged into
  each call's options);
- seeded mutants of them (a column, an alias or a table swapped, a qualifier dropped or added),
  drawn from the catalog but `matrix`, `kinds` and names that need quoting;
- the queries `pgmalli.oracle/generated-corpus` builds from the catalog: each kind of column
  (type with its typmod, CHECKs, NOT NULL, kind of type and relation, quoting, schema) through
  casts, subqueries, joins, aggregates, CTEs, set operations and RETURNING, and each pair of
  kinds PostgreSQL unites through UNION, CASE, coalesce, greatest and EXCEPT. A column of a new
  kind in `schema.sql` is covered without a corpus entry.

Each query is sent twice: its values inlined, and as parameters as HoneySQL and pgjdbc send
them. The oracle prints:

- a false positive (PostgreSQL takes the query, `check` reports a problem) and a miss
  (PostgreSQL refuses it, `check` passes it). A comparison with a literal outside an enum's or a
  CHECK's list is reported by design.
- where PostgreSQL runs the query, a return `query-schema` refuses: by default it must take what
  `execute!` returned, under `{:result :one}` what `execute-one!` returned.
- the same queries' keys: those of `row-schema`'s map, with the ones under
  `:pgmalli/unknown-keys`, must equal the keys the builder gives the result set's columns,
  under the default builder and every map builder of `pgmalli.impl.jdbc`.
- under the unqualified builder, a `:pg/type` other than the type PostgreSQL describes (a
  domain as its base type); an untyped column passes.

`pgmalli.oracle/arg-matrix` tests `arg-types` with values that do not come from `arg-types`.
An argument goes beside each column of the oracle schema (compared with `=`, `<>`, `IN` and
`= ANY`, set by UPDATE and INSERT, beside it in coalesce, CASE and greatest, cast to its type,
and as LIMIT) and is sent a fixed list of values: one of each class of
`pgmalli.impl.accept/class-types`, NULL, numbers at the integer bounds, a fraction, `""`, `"1"`
and the schema's labels. It fails where PostgreSQL takes a value the argument's schema refuses
and the rules of docs/queries.md#stricter-than-postgresql do not leave out, and where
`accept/holds?` says a value goes and PostgreSQL refuses its type. Besides the oracle schema's
columns, it creates the schema `matrix`, whose table `types` holds, for every type of
`pgmalli.impl.types` (an extension's where the server has it) and the typmods numeric(10,2),
varchar(5), char(3), timestamp(3) and bit(4), a column of the type, of its array, of a domain
over it (`CHECK (VALUE IS NOT NULL)`) and of an array of that domain. A type added to
`pgmalli.impl.types` without `:literals` needs a value in `pgmalli.oracle/samples`. The oracle
schema's table `kinds` adds arrays of domains whose CHECK reads the value (`posint[]`,
`money2[]`).

`pgmalli.oracle-test` runs the same under the seed `PGMALLI_FUZZ_SEED`, with 3 mutants per
query (or `PGMALLI_ORACLE_MUTANTS`) and a sample of the builders per query. It fails `bb test:jvm` on any false positive, refused return,
column described otherwise or argument-matrix failure, except those listed in
`pgmalli.oracle-test/pending`: false rejections in `src` not fixed yet, each with its reason
and a reproduction. `pending-entries-still-fail` fails on an entry that no longer fails, so a
fix removes it. The test also fails on a corpus id or query listed twice, and on a corpus query
that did not run in both formats (one the pinned HoneySQL cannot write).

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
| `pgmalli.accept-test` | a type pgjdbc's `setObject` sends a class as that differs from `accept/class-types`; a type PostgreSQL takes or refuses where `accept/accepts?` says otherwise |
| `pgmalli.expression-types-test` | a type PostgreSQL gives coalesce, greatest, least, CASE, nullif or UNION over two column types, an aggregate, an SQL word, a json builder, a literal or a parameter that differs from the one `h/row-schema` gives (the typing tables in src/pgmalli/honeysql.clj); a name PostgreSQL gives an unaliased expression that differs from `expression-name`'s |

A type of PostgreSQL's or of an extension is one entry of `types` in
`src/pgmalli/impl/types.clj`, or one of `unsupported` there with why. To add one, move it to
`types` and run `pgmalli.upstream-test`: it creates every extension the PostgreSQL image ships,
fails on a type in neither list, and generates, inserts and reads back a NOT NULL column of
every entry.

A failing weekly run comments on the open issue labelled `upstream` (or opens it) with the
list; the next passing run closes it. GitHub disables a scheduled workflow after 60 days
without activity in the repository: `gh workflow enable upstream.yml` turns it back on.

## Other tasks

- `bb harvest` rebuilds the expression corpus from checkouts named by env vars:
  `PGMALLI_PGREGRESS_DIR` (PostgreSQL REL_17_STABLE's `src/test/regress/sql`) and
  `PGMALLI_PGSCHEMA_DIR` (pgschema's `testdata/diff`).
- `bb sweep <sql-dir> <out-dir> [glob]` takes SQL files through the whole pipeline and writes
  what did not go through as EDN; `bb sweep:report <out-dir>` groups it for reading.
- `bb fixture` rewrites `test/resources/pgmalli/{sample,other}.edn`, which the tests read,
  from `dev/fixture.clj`. Edit that file, not the EDN.

## Releases

A release is a pushed `vX.Y.Z` tag; the tag is the version. Choose it by semantic
versioning: patch for fixes, minor for additions and, before 1.0, any change to the
contract; what is breaking is the README's [Stability](README.md#stability) list.

A fix can change what `generate` writes for some schemas, so that `check` finds their files
stale until they are regenerated. Its CHANGELOG entry says which schemas. Choose the version by
what upgrading then costs the users at the time, not by precedent: a patch while regenerating
is a step nobody minds, a minor once it is work people plan for.

1. On main, in one commit: add the version's section to CHANGELOG.md (`## X.Y.Z (date)`) and
   set the coordinate in README.md's Installation to the new version. The section has one
   short bullet per change a user meets; a breaking one says what to do. A fix to something
   not yet released gets no entry.
2. Push the commit, then tag it and push the tag: `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. The release workflow checks that the tag is on main, runs `bb test:jvm` and builds the
   jar; a second job deploys it to Clojars, and a third creates the GitHub release with the
   jar and generated notes. If only the last job fails, re-run it alone: Clojars rejects
   publishing a version twice.

The Clojars deploy token is the secret `CLOJARS_PASSWORD` of the environment `clojars`,
which only `v*` tags can deploy from; only the deploy job sees it. It is chaploud's token,
scoped to `io.github.chaploud/pgmalli`. To replace it, create one with the same scope on
Clojars, run `gh secret set CLOJARS_PASSWORD --env clojars`, and disable the old token.

Never move or delete a published tag; a broken release is fixed by the next version.
`clojure -T:build jar` builds a snapshot of the last tag. Renovate updates the dependencies.
