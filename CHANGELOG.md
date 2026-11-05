# Changelog

pgmalli follows [semantic versioning](https://semver.org). Before 1.0, a minor release may
break what [Stability](README.md#stability) lists.

## Unreleased

Breaking; regenerate the file.

* A CHECK, a domain's CHECK, a partition's bounds, a length and a scale are the database's:
  no schema checks them. A domain's column is of its base type, with `:pg/domain`.
* One generated file, `pgmalli-next.edn`, holds every schema; `registry` reads it from the
  classpath, or takes its data.
* No config file nor `pgmalli-next.main`: `generate!` and `check` take one map, which
  `clojure -X` passes; `check` throws `:pgmalli.error/stale` where the file is out of date.
* An enum literal is held to its labels wherever PostgreSQL types it as the enum.

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
