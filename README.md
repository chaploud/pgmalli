# pgmalli

[![ci](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml/badge.svg)](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml)
[![Clojars](https://img.shields.io/clojars/v/io.github.chaploud/pgmalli.svg)](https://clojars.org/io.github.chaploud/pgmalli)

The applied PostgreSQL schema, as [malli](https://github.com/metosin/malli) schemas.

pgmalli reads a database once (through `psql`), writes one EDN file per schema, and your
application loads those files as a malli registry. Tables, columns, types, defaults, keys and
constraints are all in there, so code and tests can be written and checked against the real
contract without a database at hand.

```clojure
(require '[pgmalli.core :as pgmalli] '[malli.core :as m] '[malli.error :as me] '[malli.generator :as mg])

(def registry (pgmalli/registry "public"))

;; a row as read from the database
(m/validate :pg.public/users row {:registry registry})

;; what an INSERT may carry: no identity or generated columns, defaults optional, closed map
(-> (m/explain :pg.public.users/insert {:email "x" :status "closed"} {:registry registry})
    me/humanize)
;; => {:closed_at ["chk_closed_at_matches_status"]}

;; test data that satisfies the constraints
(mg/generate :pg.public.users/insert {:registry registry})
```

## Setup

```clojure
io.github.chaploud/pgmalli {:mvn/version "..."}   ; deps.edn or bb.edn
```

Generating needs PostgreSQL 16 or later and `psql`; connection settings are psql's own
(`PGHOST`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `~/.pgpass`). Applications only need the
generated files.

```
clojure -M -m pgmalli.main generate   # writes resources/pgmalli/<schema>.edn
clojure -M -m pgmalli.main check      # exit 1 when the files no longer match the database
```

Both read `pgmalli.edn` in the working directory when present:

```clojure
{:schemas ["public"]                 ; default
 :out-dir "resources/pgmalli"        ; default; keep it on the classpath
 :overrides {"chk_legacy_flag" {:skip "removed together with the column in 2027"}
             "chk_scores" [:ref :app/scores-consistent]}
 :db {:host "localhost" :port 5432 :db "app_dev" :user "app"}}   ; optional
```

The same from Clojure: `(pgmalli/generate! config)` and `(pgmalli/stale config)`.

Convention: regenerate right after migrating and commit the files; in CI assert
`(nil? (pgmalli/stale config))`.

## What you get

For a schema `public`, the registry contains:

| name | schema |
|---|---|
| `:pg.public/<enum>` | `[:enum ...]` |
| `:pg.public/<domain>` | base type with the domain's CHECK applied, `[:maybe ...]` unless the domain is NOT NULL |
| `:pg.public/<table>` | a valid row: `[:map ...]`, wrapped in `[:and ...]` with the table's constraints when it has any |
| `:pg.public.<table>/insert` | what an INSERT may carry: identity ALWAYS and generated columns removed, columns with a default or NULL optional, `{:closed true}`. Derived from the row schema when the registry is loaded, so the files hold rows only |
| `:pg/check` | the schema type behind `[:pg/check expr]` |

Column schemas carry provenance in their properties: `:pg/type`, `:pg/default` (a literal or
the default expression as data), `:pg/identity` (`:always`, `:default` or `:serial`),
`:pg/generated`, `:pg/constraint` (names of the CHECKs that shaped it). Literal defaults also
set malli's `:default`. The map carries `:pg/table`, `:pg/primary-key`, `:pg/unique` and
`:pg/foreign-keys` as `[[columns] "table" [columns]]`, composite keys included.

Identifiers that are not plain names (`Order Items`) become string keys. Spelling is left as
the database has it.

### PostgreSQL to malli

| PostgreSQL | malli |
|---|---|
| NOT NULL | no `[:maybe ...]` |
| column of an enum or domain type | `[:ref :pg.<schema>/<type>]` |
| `CHECK (col IN (...))`, `CHECK (col = 'x')` | `[:enum ...]` |
| `CHECK (col >= a AND col <= b)`, `BETWEEN`, one-sided bounds | `[:int {:min a :max b}]`; `:double` likewise, an exclusive bound as `[:and :double [:> a]]`; `numeric` as `[:and decimal? [:>= a] [:<= b]]` |
| `CHECK (length(trim(col)) > 0)` | `[:and [:string {:min 1}] [:re "\S"]]`; `col <> ''` is `[:string {:min 1}]` |
| `varchar(n)`, `CHECK (length(col) <= n)` | `[:string {:max n}]` |
| `CHECK (jsonb_typeof(col) = 'object')` | `:map` (`'array'` becomes `[:sequential :any]`) |
| `CHECK (col ~ 're')` | `[:and :string [:re "re"]]` (`~*` adds `(?i)`; POSIX classes such as `[[:digit:]]` in their Java form) |
| `CHECK (col IS NULL OR <any of the above>)` | `[:maybe ...]` |
| `CHECK (col IS NOT NULL)` | no `[:maybe ...]` |
| column patterns joined with `AND` | each part |
| `CHECK (status = 'a' AND ... OR status = 'b' AND ...)` | `[:multi {:dispatch :status} ["a" [:map ...]] ["b" [:map ...]]]` |
| `CHECK (x IS NULL OR y = 'v' AND ...)` and other ORs of column patterns | `[:or [:map ...] [:map ...]]` |
| any other CHECK (`score <= total`, arithmetic, `CASE`, jsonb operators) | `[:pg/check expr]`: the expression as data, validated by a schema type pgmalli registers |
| `NOT VALID` CHECK | kept in `:unrendered` |
| `date`, `time`, `timetz`, `timestamp`, `timestamptz`, `interval` | `:time/local-date`, `:time/local-time`, `:time/offset-time`, `:time/local-date-time`, `:time/instant`, `:time/duration` |
| `json`, `jsonb` | `:any` |
| `bytea` | `bytes?` |
| `T[]` | `[:vector <T>]` |
| other types | `[:any {:pg/type ...}]`, listed in `:unrendered` |

`(pgmalli/unrendered "public")` lists the facts that have no rendering, each with the
constraint's expression as data. Give them one through `:overrides`, keyed by constraint name:
a malli schema (`[:ref :app/name]` defined in your own registry, for instance) or
`{:skip "reason"}`.

`:pg/check` keeps the expression as data (`[:<= :score :total]`, HoneySQL-style) and
evaluates it as PostgreSQL would: NULL passes, integer division truncates, casts convert, an
expression the database would fail on (division by zero, a cast that does not parse) fails
the row. A column missing from the map is NULL to it, so an insert that omits a defaulted
column is checked as if the column were NULL. The test suite compares its verdicts with
PostgreSQL's on generated rows. A CHECK using an operator, cast or regex syntax outside its
vocabulary stays in `:unrendered`.

## Working with the registry

```clojure
(pgmalli/registry "public" "auth")          ; several schemas, plus malli's defaults, malli.util and malli.experimental.time
(pgmalli/columns registry :pg.public/users) ; the [:map ...] alone, for malli.util
(m/decode :pg.public/users jdbc-row {:registry registry} (pgmalli/transformer))
                                            ; java.sql.Date / Timestamp, Instant (as next.jdbc's read-as-instant
                                            ; returns for timestamp columns) and strings into the registry's types
(pgmalli/transformer {:zone (java.time.ZoneId/of "UTC")})
                                            ; the zone Instants are read in for timestamp columns; default: the JVM's
```

Datasets (fixtures, seeds) are checked as a whole: primary keys and unique constraints within
a table, foreign keys across tables, including tables of other schemas in the registry.

```clojure
(def dataset (pgmalli/dataset-schema registry))          ; {"public.groups" [...] "public.users" [...]}
(m/validate dataset {"public.groups" [{:id 1 ...}] "public.users" [{:group_id 1 ...}]} {:registry registry})
(clojure.test.check.generators/sample (pgmalli/dataset-generator registry {:rows 5}))
;; tables in foreign-key order, referencing columns drawn from generated rows;
;; :rows are tried per table, rows that end up violating a constraint are dropped
```

`dataset-schema` and `dataset-generator` are built at runtime and contain functions; the
generated files stay data.

## Contract

Kept compatible; a change bumps the minor version.

1. The config keys `:schemas` `:out-dir` `:overrides` `:db`.
2. The generated file: `{:schema :database-version :registry :unrendered :skipped}` and the
   registry names above (insert schemas are derived at load time, not stored).
3. The fact vocabulary of `:unrendered` (`pgmalli.impl.pattern`).

`pgmalli.impl.*` may change without notice.

## Scope

Tables (regular and partition parents), columns, CHECK, PRIMARY KEY, UNIQUE and FOREIGN KEY
constraints, enum and domain types. Views, indexes, triggers, policies and privileges are not
read. Expressions are read in the form PostgreSQL's deparser prints them.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md). The suite includes property-based round trips through
PostgreSQL: expressions are stored as CHECK constraints and read back, and `:pg/check`
verdicts are compared with PostgreSQL's own on generated rows.

## License

MIT
