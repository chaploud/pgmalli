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

Generating needs PostgreSQL 14 or later and `psql`; connection settings are psql's own
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
 :checks :data                       ; default; :fn also compiles cross-column CHECKs (see below)
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
| `:pg.public/<domain>` | base type with the domain's CHECK applied |
| `:pg.public/<table>` | a valid row: `[:map ...]`, wrapped in `[:and ...]` with the table's constraints when it has any |
| `:pg.public.<table>/insert` | what an INSERT may carry: identity ALWAYS and generated columns removed, columns with a default or NULL optional, `{:closed true}` |

Column schemas carry provenance in their properties: `:pg/type`, `:pg/default` (a literal or
the default expression as data), `:pg/identity` (`:always`, `:default` or `:serial`),
`:pg/generated`, `:pg/references ["table" "column"]`, `:pg/constraint` (names of the CHECKs
that shaped it). Literal defaults also set malli's `:default`. The map carries `:pg/table`,
`:pg/primary-key` and `:pg/unique`.

Identifiers that are not plain names (`Order Items`) become string keys. Spelling is left as
the database has it.

### PostgreSQL to malli

| PostgreSQL | malli |
|---|---|
| NOT NULL | no `[:maybe ...]` |
| column of an enum or domain type | `[:ref :pg.<schema>/<type>]` |
| `CHECK (col IN (...))`, `CHECK (col = 'x')` | `[:enum ...]` |
| `CHECK (col >= a AND col <= b)`, `BETWEEN`, one-sided bounds | `[:int {:min a :max b}]`, `:double` likewise, `numeric` as `[:and decimal? [:>= a] [:<= b]]` |
| `CHECK (length(trim(col)) > 0)` | `[:and [:string {:min 1}] [:re "\S"]]`; `col <> ''` is `[:string {:min 1}]` |
| `varchar(n)`, `CHECK (length(col) <= n)` | `[:string {:max n}]` |
| `CHECK (jsonb_typeof(col) = 'object')` | `[:map]` (`'array'` becomes `[:sequential :any]`) |
| `CHECK (col ~ 're')` | `[:and :string [:re "re"]]` (`~*` adds `(?i)`) |
| `CHECK (col IS NULL OR <any of the above>)` | `[:maybe ...]` |
| `CHECK (col IS NOT NULL)` | no `[:maybe ...]` |
| column patterns joined with `AND` | each part |
| `CHECK (status = 'a' AND ... OR status = 'b' AND ...)` | `[:multi {:dispatch :status} ["a" [:map ...]] ["b" [:map ...]]]` |
| `CHECK (x IS NULL OR y = 'v' AND ...)` and other ORs of column patterns | `[:or [:map ...] [:map ...]]` |
| CHECK comparing columns (`score <= total`) | not data; kept in `:unrendered`, or `[:fn ...]` with `:checks :fn` |
| `NOT VALID` CHECK | kept in `:unrendered` |
| `date`, `time`, `timestamp`, `timestamptz`, `interval` | `:time/local-date`, `:time/local-time`, `:time/local-date-time`, `:time/instant`, `:time/duration` |
| `json`, `jsonb` | `:any` |
| `bytea` | `bytes?` |
| `T[]` | `[:vector <T>]` |
| other types | `[:any {:pg/type ...}]`, listed in `:unrendered` |

`(pgmalli/unrendered "public")` lists the facts that have no rendering, each with the
constraint's expression as data. Give them one through `:overrides`, keyed by constraint name:
a malli schema (`[:ref :app/name]` defined in your own registry, for instance) or
`{:skip "reason"}`.

With `:checks :fn`, CHECKs comparing columns are compiled into `(fn [row] ...)` forms that
malli evaluates with PostgreSQL's NULL semantics. The forms are data, but evaluating them needs
`org.babashka/sci` on the JVM (babashka has it built in), and the files can no longer be
shared with ClojureScript. The default `:data` keeps everything plain data.

## Working with the registry

```clojure
(pgmalli/registry "public" "auth")          ; several schemas, plus malli's defaults, malli.util and malli.experimental.time
(pgmalli/columns registry :pg.public/users) ; the [:map ...] alone, for malli.util
(m/decode :pg.public/users jdbc-row {:registry registry} pgmalli/transformer)
                                            ; java.sql.Date / Timestamp and strings into the registry's types
```

Datasets (fixtures, seeds) are checked as a whole: primary keys and unique constraints within
a table, foreign keys across tables.

```clojure
(def dataset (pgmalli/dataset-schema registry))          ; {"groups" [...] "users" [...]}
(m/validate dataset {"groups" [{:id 1 ...}] "users" [{:group_id 1 ...}]} {:registry registry})
(clojure.test.check.generators/sample (pgmalli/dataset-generator registry {:rows 5}))
;; tables in foreign-key order, referencing columns drawn from generated rows
```

`dataset-schema` and `dataset-generator` are built at runtime and contain functions; the
generated files stay data.

## Contract

Kept compatible; a change bumps the minor version.

1. The config keys `:schemas` `:out-dir` `:checks` `:overrides` `:db`.
2. The generated file: `{:schema :database-version :registry :unrendered :skipped}` and the
   registry names above.
3. The fact vocabulary of `:unrendered` (`pgmalli.impl.pattern`).

`pgmalli.impl.*` may change without notice.

## Scope

Tables (regular and partition parents), columns, CHECK, PRIMARY KEY, UNIQUE and FOREIGN KEY
constraints, enum and domain types. Views, indexes, triggers, policies and privileges are not
read. Expressions are read in the form PostgreSQL's deparser prints them.

## Development

```
bb test          # babashka
bb test:jvm      # JVM
bb test:matrix   # PostgreSQL 14 to 18, as CI does
bb lint          # clj-kondo
bb harvest       # rebuild the expression corpus (test/corpus/harvested.edn)
clojure -T:build jar
```

Database tests start a throwaway PostgreSQL container with docker (`PGMALLI_PG_IMAGE`
selects the image). The suite includes property-based round trips through PostgreSQL:
expressions are stored as CHECK constraints and read back, and compiled checks are compared
with PostgreSQL's own verdict on generated rows. `nix develop` provides the tools. Releases:
push a `v*` tag. Dependency updates come from Renovate.

## License

MIT
