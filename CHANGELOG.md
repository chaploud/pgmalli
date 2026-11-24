# Changelog

pgmalli follows [semantic versioning](https://semver.org). Before 1.0, a minor release may
break what [Stability](README.md#stability) lists; its entry says what to do.

## Unreleased

* PostgreSQL 19: its `pg_catalog` (functions, operators, casts, tables and views), and its types
  `oid8` and `regdatabase`.
* A call resolves with the implicit casts of the version the registry was generated on. A cast
  that not every version has was read as no implicit cast at all, so a value it converts
  (a `regdatabase` compared with an integer, on 19) was refused.
* A file generated on a PostgreSQL version newer than pgmalli knows holds a `:newer-version`
  diagnostic, besides the warning `generate!` and `check` print, and its queries'
  `pg_catalog` is typed as the newest version pgmalli knows has it, as the warning says: it was
  any version's.

## 0.4.1

* A column of no table in `:row` (an expression, a scalar subquery, a set operation's column,
  a recursive CTE's, the column `FULL JOIN ... USING` merges) has no `:pg/identity`,
  `:pg/generated`, `:pg/default` or `:default` of the column it comes from, as PostgreSQL names
  no table of it. A `UNION` of an identity column and another of its type was an `:or` of the
  two.
* A set operation's column is typed where a member is, or reads, a set operation whose column is
  of two schemas of one type: it was `:any`. Read through a CTE or a subquery, such a column
  (bit(1) beside bit(4)) is each value as its member reads it, as read directly: it was the first
  member's.
* A cast of a column to its own type and type modifiers, which PostgreSQL leaves out, is the
  column, its `:pg/identity` and default kept (not a timestamp's or an interval's, whose
  modifiers the generated file does not hold).
* A shape with a property of an empty map (`{:default {}}`) is read: analyze threw.
* A value compared with a set operation's column of one type takes what one compared with a
  column of the type takes (a `Double` beside `numeric` and `numeric(6,2)`).

## 0.4.0

* A rewrite: [README](README.md) and [docs](docs/) describe it as it is. Most of 0.3's API is
  gone or changed.
* `pgmalli.jdbc`: `builder`, the `:builder-fn` that reads rows as the registry types them;
  `sent`, a sqlvec's parameters as pgmalli sends them; `jsonb`; `stored`.
* `pgmalli.honeysql/analyze` gives a query's `:args`, `:row`, `:rows`, `:problems` and
  `:tables` in one map, in place of `check`, `arg-types`, `row-schema`, `params-schema`,
  `tables` and `explain`; `humanize` writes its problems; `values-schema` and `set-schema` type
  a write's rows.
* `pgmalli.main`, `pgmalli.data` and `pgmalli.core`'s `columns`, `portable`, `read-options` and
  `transformer` are gone.
* What to do: generate through `clojure -X` with `pgmalli.generate/generate!`
  ([Quickstart](README.md#quickstart)); a `pgmalli.edn` of 0.3's settings in the working
  directory is no longer read, and the generated file is one, `resources/pgmalli.edn`, in place
  of `resources/pgmalli/<schema>.edn`. Read rows through `pgmalli.jdbc/builder` and send
  parameters through `sent`; replace each removed `pgmalli.honeysql` call with the key of
  `analyze` it gave.

## 0.3.3 (2026-11-03)

* More arguments and select expressions typed; a write with no RETURNING returns its update
  count; `:return-keys`.

## 0.3.2 (2026-10-30)

* NULL into a NOT NULL column reported however HoneySQL spells it; a CHECK's list narrows only
  where a value outside it fails; a comparison under `NOT` read as its negation.

## 0.3.1 (2026-10-30)

* Reading a schema runs none of its functions (CVE-2018-1058); validation does not stall on a
  huge numeric or a long LIKE value; unsafe schema names are a config error.

## 0.3.0 (2026-10-30)

* The first release meant for use: PostgreSQL schemas as malli, generated rows, rows typed as
  next.jdbc builds them, HoneySQL queries checked with no database, files kept current in CI.
