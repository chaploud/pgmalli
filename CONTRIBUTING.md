# Contributing

Issues and pull requests are welcome.

## Setup

`nix develop` (or direnv) provides clojure, babashka, clj-kondo and psql. The database tests
need docker: they start a throwaway PostgreSQL container (`PGMALLI_PG_IMAGE` selects the image).
`bb tasks` says which tasks need it.

## Tests

| command | runs |
|---|---|
| `bb lint` | clj-kondo |
| `bb test` | the suite on babashka |
| `bb test:nodb` | `bb test` without the database tests (`PGMALLI_SKIP_DB=1`) |
| `bb test:jvm [main\|oracle]` | the suite on the JVM; `main` all but `pgmalli-next.oracle-test`, `oracle` that alone |
| `bb test:matrix` | the suite on every PostgreSQL version CI covers |
| `bb test:docs` | the code blocks of README.md and docs/, each `;; =>` asserted |
| `bb test:floor [main\|oracle]` | the suite on the JVM with the oldest versions README supports |
| `bb test:all` | `bb lint`, `bb test`, `bb test:jvm`, `bb test:floor`, `bb oracle` and `bb test:docs` at once, each into `target/<task>.log` (`:` as `-`); prints each exit code |
| `bb oracle [seed] [mutants]` | `h/analyze` against PostgreSQL itself; exit 1 on what `pgmalli-next.oracle-test` fails on |
| `bb test:update [ns ...]` | each failing `(is (= literal form))` written back with what it got (`bb test:update:jvm` for the namespaces that need next.jdbc); prints what it left and why |
| `bb test:docs:update [file.md ...]` | each `;; =>` of the docs written back with what its form gives |
| `bb test:stricter` | `test/resources/pgmalli-next/stricter.edn` rewritten: where PostgreSQL takes a parameter that `accept/holds?` leaves out |
| `bb mutate --file src/... [--kind table\|logic] [--ns ns ...] [--fn def ...] [--limit N] [--jvm] [--db] [--timeout secs]` | each mutant of the file (an element left out of a def's literal collection; and/or, when/when-not, =/not=, true/false swapped) against the tests (`--ns`, or the bb suite, or the JVM suite under `--jvm`; database tests only under `--db`), each run killed after `--timeout` seconds, in a temporary copy of the working tree (uncommitted changes too), which it never writes; prints the survivors with file:line and writes `--out` (target/mutate.edn) |

Before a pull request: `bb test:all`, then read the log of each task that did not exit 0. Each
task starts its own PostgreSQL container. On a machine without docker, `bb lint`,
`bb test:nodb` and `bb test:docs` run. After `bb test:update` or `bb test:docs:update`,
read the git diff: a rewritten expectation is a changed behaviour.

`bb mutate` runs the bb suite unless told otherwise, which leaves out the tests that pin much
of a file such as `src/pgmalli_next/impl/resolve.clj` (those comparing with PostgreSQL). Run such a
file against the JVM namespaces that test it, with a database:
`bb mutate --file src/pgmalli_next/impl/resolve.clj --jvm --db --ns pgmalli-next.expression-types-test pgmalli-next.impl.resolve-test pgmalli-next.honeysql-test`,
with `--fn <def>` for one def.

`bb test` runs every `*_test` namespace but those that need next.jdbc. One namespace or one test
on the JVM: `clojure -M:test -n pgmalli-next.honeysql-test`,
`clojure -M:test -v pgmalli-next.honeysql-test/some-test`.

In a REPL on the test classpath (`clojure -M:test:repl`, or an editor's with the `:test` alias),
`(require 'pgmalli-next.honeysql-test :reload)` then `(clojure.test/run-tests 'pgmalli-next.honeysql-test)`
runs one namespace. The first database test starts the container, and later runs reuse it until
the REPL exits; each run of a namespace gets a new database.

## Documentation

The examples run against [docs/example/schema.sql](docs/example/schema.sql). After changing
it, `bb docs:example` generates `docs/example/pgmalli-next.edn` again; a test fails until it does. A
block that cannot run (a shell command, a database call) gets `<!-- :test-doc-blocks/skip -->`
before it.

When a change touches what a type becomes, update [docs/types.md](docs/types.md).

## Randomized tests

`catalog-fuzz-test`, `honeysql-robust-test` and the oracle draw their cases from a
seed, 42 in CI. `PGMALLI_FUZZ_SEED=<n> bb test:jvm` runs other seeds. A failure prints its seed,
the case (shrunk where test.check shrinks it), where it failed and the command that runs it
again, with `PGMALLI_FUZZ_SEED`, `PGMALLI_FUZZ_CASE` or `PGMALLI_FUZZ_SCHEMAS` set.

## The PostgreSQL oracle

`bb oracle [seed] [mutants]` compares `pgmalli-next.honeysql` with PostgreSQL itself, on the
database of `test/resources/pgmalli-next/oracle/schema.sql`. The seed is `PGMALLI_FUZZ_SEED` or 42,
the number of mutants per query `PGMALLI_ORACLE_MUTANTS` or 8. The queries are:

- the corpus, `test/resources/pgmalli-next/oracle/corpus.edn` (`[id query opts?]`, opts merged into
  each call's options);
- seeded mutants of them (a column, an alias or a table swapped, a qualifier dropped or added),
  drawn from the catalog but `matrix`, `kinds` and names that need quoting;
- the queries `pgmalli-next.oracle/generated-corpus` builds from the catalog: each kind of column
  (type with its typmod, NOT NULL, kind of type and relation, quoting, schema) through
  casts, subqueries, joins, aggregates, CTEs, set operations and RETURNING, and each pair of
  kinds PostgreSQL unites through UNION, CASE, coalesce, greatest and EXCEPT. A column of a new
  kind in `schema.sql` is covered without a corpus entry.

Each query is sent twice: its values inlined, and as parameters as HoneySQL and pgjdbc send
them. The oracle prints:

- a false positive (PostgreSQL takes the query, `:problems` reports one) and a miss
  (PostgreSQL refuses it, `:problems` is empty). A comparison with a literal outside an enum's labels is
  reported by design.
- where PostgreSQL runs the query, a return `h/query-schema` refuses: by default it must take what
  `execute!` returned, under `{:result :one}` what `execute-one!` returned.
- the same queries' keys: those of `:row`'s map must equal the keys next.jdbc's
  `as-unqualified-maps` gives the result set's columns.
- a `:pg/type` other than the type PostgreSQL describes (a
  domain as its base type); an untyped column passes.

`pgmalli-next.oracle/arg-matrix` tests `:args` with values that do not come from `:args`.
An argument goes beside each column of the oracle schema (compared with `=`, `<>`, `IN` and
`= ANY`, set by UPDATE and INSERT, beside it in coalesce, CASE and greatest, cast to its type,
and as LIMIT) and is sent a fixed list of values: one of each class of
`pgmalli-next.impl.accept/class-types`, NULL, numbers at the integer bounds, a fraction, `""`, `"1"`
and the schema's labels. It fails where PostgreSQL takes a value the argument's schema refuses
(or, set by UPDATE and INSERT, `set-schema`'s and `values-schema`'s row) and the rules of
docs/queries.md#stricter-than-postgresql do not leave out, and where `accept/holds?` says a
value goes and PostgreSQL refuses its type. It sends each value as the one sending
(docs/reading-rows.md) does, and fails where `sent-type`
names a type other than PostgreSQL's `pg_typeof` of the value sent. Besides the oracle schema's
columns, it creates the schema `matrix`, whose table `types` holds, for every type of
`pgmalli-next.impl.types` (an extension's where the server has it) and the typmods numeric(10,2),
varchar(5), char(3), timestamp(3) and bit(4), a column of the type, of its array, of a domain
over it (`CHECK (VALUE IS NOT NULL)`) and of an array of that domain. A type added to
`pgmalli-next.impl.types` without `:literals` needs a value in `pgmalli-next.oracle/samples`. The oracle
schema's table `kinds` adds arrays of domains whose CHECK reads the value (`posint[]`,
`money2[]`).

`pgmalli-next.oracle-test` runs the same under the seed `PGMALLI_FUZZ_SEED`, with 3 mutants per
query (or `PGMALLI_ORACLE_MUTANTS`) and a sample of the builders per query. The oracle and the
argument matrix send their cases over `PGMALLI_ORACLE_WORKERS` connections (the processors, up to
4), each to its own copy of the database. It fails `bb test:jvm` on any false positive, refused return,
column described otherwise or argument-matrix failure, except those listed in
`pgmalli-next.oracle-test/pending`: false rejections in `src` not fixed yet, each with its reason
and a reproduction. `pending-entries-still-fail` fails on an entry that no longer fails, so a
fix removes it. A statement that runs out of its timeout fails the test, as does a corpus
id or query listed twice, and a corpus query that did not run in both formats (one the pinned
HoneySQL cannot write).

`pgmalli-next.accept-test` holds `accept/accepts?` to PostgreSQL both ways, and `accept/holds?`
one way: where `holds?` says a value goes, PostgreSQL takes it. Where PostgreSQL takes one that
`holds?` leaves out, the stricter rule, is pinned in `test/resources/pgmalli-next/stricter.edn`;
after changing `holds?`, run `bb test:stricter` and read the diff.

`a-query-wrapped-keeps-its-problems` sends no query to PostgreSQL, only reads the oracle
schema: each case wrapped in a FROM subquery, a CTE or EXISTS must have the problems it had
alone.

After changing how `pgmalli-next.impl.query` resolves scopes or names, run `bb oracle` with a few
seeds. A query it got wrong goes into the corpus.

## Following upstream

`bb upstream` runs the JVM suite against the newest stable HoneySQL, next.jdbc, pgjdbc and
malli and `postgres:alpine` (`PGMALLI_PG_IMAGE` selects another), and prints what to update,
grouped by upstream. The upstream workflow runs it weekly, outside PR CI. Every failure under
the database fixture names the PostgreSQL, pgjdbc, next.jdbc, HoneySQL and malli versions that
ran. The tests that catch a change upstream, each naming the list to update:

| test | catches |
|---|---|
| `pgmalli-next.honeysql-contract-test` | a clause HoneySQL adds or drops, or special syntax of `pgmalli-next.impl.hsyntax/operator-syntax` it drops; a change in how `sql/format` writes joins, `:in`, parameters and `:values` maps |
| `pgmalli-next.upstream-test` | a type of `pg_catalog` or of an extension the image ships that is neither in `types` (src/pgmalli_next/impl/types.clj) nor in the test's `unsupported`, or that does not generate, insert and read back; a map builder next.jdbc adds or drops; a type, implicit cast, range, function, operator or relation of `pg_catalog` that differs from the catalog snapshot (`resources/pgmalli-next/impl/catalog.edn`, which `bb catalog` rewrites) |
| `pgmalli-next.accept-test` | a type pgjdbc's `setObject` sends a class as that differs from `accept/class-types`; a type PostgreSQL takes or refuses where `accept/accepts?` says otherwise |
| `pgmalli-next.expression-types-test` | a type PostgreSQL gives coalesce, greatest, least, CASE, nullif or UNION over two column types, an aggregate, an SQL word, a json builder, a literal or a parameter that differs from the one `h/analyze`'s `:row` gives (`pgmalli-next.impl.resolve` over the catalog snapshot, and the grammar pgmalli types itself: `sql-value-types` and `grammar-operators` in `pgmalli-next.impl.hsyntax`, `json-shapes` in `pgmalli-next.impl.query`); a name PostgreSQL gives an unaliased expression that differs from `pgmalli-next.impl.hsyntax/expression-name`'s |

A type of PostgreSQL's or of an extension is one entry of `types` in
`src/pgmalli_next/impl/types.clj`, or one of `unsupported` in `test/pgmalli_next/upstream_test.clj` with why. To add one, move it to
`types` and run `pgmalli-next.upstream-test`: it creates every extension the PostgreSQL image ships,
fails on a type in neither list, and generates, inserts and reads back a NOT NULL column of
every entry.

A new PostgreSQL version: add it to the `pg` matrix of `.github/workflows/ci.yml` (a test holds
the snapshot's versions to it) with its image by digest under `include` (Renovate updates the
digests, not the versions), run `bb catalog`, and read the diff of
`resources/pgmalli-next/impl/catalog.edn`: what is `:in` the new version alone is what it adds.
`bb catalog` stops on a type or range whose facts differ between versions, which
`pgmalli-next.impl.types` reads one of.

A failing weekly run comments on the open issue labelled `upstream` (or opens it) with the
list; the next passing run closes it. GitHub disables a scheduled workflow after 60 days
without activity in the repository: `gh workflow enable upstream.yml` turns it back on.

## Other tasks

- `bb sweep <sql-dir> <out-dir> [glob]` takes SQL files through the whole pipeline and writes
  what did not go through as EDN; `bb sweep:report <out-dir>` groups it for reading.
- `bb catalog` rewrites `resources/pgmalli-next/impl/catalog.edn`, the snapshot of `pg_catalog`
  that types a query's functions, operators and casts, from each PostgreSQL version CI runs.
- `bb fixture` rewrites `test/resources/pgmalli-next/{sample,other,both}.edn`, which the tests read,
  from `dev/fixture.clj`. Edit that file, not the EDN.

## Releases

A release is a pushed `vX.Y.Z` tag; the tag is the version. Choose it by semantic
versioning: patch for fixes, minor for additions and, before 1.0, any change to the
contract; what is breaking is the README's [Stability](README.md#stability) list.

A fix can change what `generate` writes for some schemas, so that `check` finds their file
stale until it is regenerated. Choose the version by
what upgrading then costs the users at the time, not by precedent: a patch while regenerating
is a step nobody minds, a minor once it is work people plan for.

1. On main, in one commit: add the version's section to CHANGELOG.md (`## X.Y.Z (date)`) and
   replace the `:git/sha` coordinate in README.md's Installation with `{:mvn/version "X.Y.Z"}`. The section is written
   then, of the changes since the last tag, in a few short bullets: what a user meets, what a
   breaking one has them do, and whether the file needs regenerating. Nothing is written
   between releases.
2. Push the commit, then tag it and push the tag: `git tag vX.Y.Z && git push origin vX.Y.Z`.
3. The release workflow checks that the tag is on main, runs `bb test:jvm` and builds the
   jar; a second job deploys it to Clojars, and a third creates the GitHub release with the
   jar and generated notes. If only the last job fails, re-run it alone: Clojars rejects
   publishing a version twice.

The Clojars deploy token is the secret `CLOJARS_PASSWORD` of the environment `clojars`,
which only `v*` tags can deploy from; only the deploy job sees it. It is chaploud's token,
scoped to `io.github.chaploud/pgmalli-next`. To replace it, create one with the same scope on
Clojars, run `gh secret set CLOJARS_PASSWORD --env clojars`, and disable the old token.

Never move or delete a published tag; a broken release is fixed by the next version.
`clojure -T:build jar` builds a snapshot of the last tag. Renovate updates the dependencies.
