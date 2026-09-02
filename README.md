# pgmalli

Generate [malli](https://github.com/metosin/malli) schemas from an applied PostgreSQL schema.

- The database is the source of truth. The output is one EDN file per schema that your
  application loads as a malli registry.
- The only external requirement is `psql`. On the JVM the library depends on
  `org.clojure/data.json`; on babashka it has no dependencies.
- No migrations. Use whatever you use (ragtime, pgschema, plain SQL); pgmalli only asks
  you to regenerate after migrating.

## Install

```clojure
io.github.chaploud/pgmalli {:mvn/version "..."}
```

PostgreSQL 14 or later and `psql`. Connection settings are psql's: `PGHOST`, `PGPORT`,
`PGDATABASE`, `PGUSER`, `PGPASSWORD` and `~/.pgpass`.

## Usage

```clojure
(require '[pgmalli.core :as pgmalli] '[malli.core :as m])

;; Four keys, all optional
(def config {:schemas ["public"]                 ; default ["public"]
             :out-dir "resources/pgmalli"        ; default; one file per schema, <out-dir>/<schema>.edn
             :overrides {"chk_closed_at_matches_status" [:ref :app/closed-consistent]
                         "chk_legacy_flag" {:skip "removed together with the column in 2027"}}
             :db {:host "localhost" :port 5432 :db "app_dev" :user "app" :password "secret"}})

;; Right after migrating: write the files and commit them
(pgmalli/generate! config)   ; => {"public" "resources/pgmalli/public.edn"}

;; In the application
(def registry (merge (m/default-schemas) (pgmalli/registry (pgmalli/path config "public"))))
(m/validate :pg.public/users row {:registry registry})

;; In CI
(is (nil? (pgmalli/stale config)))                       ; files match the database
(pgmalli/unrendered (pgmalli/path config "public"))      ; constraints without a malli rendering
```

The same from the command line, with the config map in `pgmalli.edn`:

```
clojure -M -m pgmalli.main generate
clojure -M -m pgmalli.main check      # exit 1 when files are stale; lists unrendered facts
bb -m pgmalli.main generate           # identical on babashka
```

## Generated file

```clojure
{:schema "public"
 :database-version "PostgreSQL 17.11 ..."
 :registry {:pg.public/approval_status [:enum "pending" "approved" "rejected" "canceled"]
            :pg.public/users
            [:map {:pg/table "users"}
             [:age [:maybe [:int {:min 0 :pg/type "integer" :pg/constraint ["users_age_check"]}]]]
             [:id [:int {:pg/type "bigint"}]]
             [:status [:ref {:pg/type "approval_status" :pg/default [:cast "pending" :approval_status]} :pg.public/approval_status]]
             [:title [:string {:min 1 :max 255 :pg/trim true :pg/type "character varying" :pg/constraint ["chk_title"]}]]]}
 :unrendered [{:fact :table-check :schema "public" :table "users" :constraint "chk_closed_at_matches_status"
               :expr [:or [:and [:= :status "pending"] [:is :closed_at nil]] ...] :columns ["status" "closed_at"]}]
 :skipped []}
```

- A table schema describes one row as read from the database. Nullable columns are `[:maybe ...]`.
- Column properties record provenance: `:pg/type`, `:pg/default` (expression data) and
  `:pg/constraint` (names of the constraints that shaped the schema), so a malli error can be
  traced back to the database constraint.
- Every enum type of the schema is a registry entry `:pg.<schema>/<type>`; columns refer to it
  with `[:ref ...]`.
- Identifiers that are not plain names (`Order Items`) become string keys.
- Ordering is by name (enum values keep their declared order) and maps are sorted, so the same
  database and config always produce the same bytes.

## What becomes what

| PostgreSQL | malli |
|---|---|
| column of an enum type | `[:ref :pg.<schema>/<type>]` |
| `CHECK (col IN (...))`, `CHECK (col = 'x')`, strings or numbers | `[:enum ...]` |
| `CHECK (col >= a AND col <= b)`, `BETWEEN`, one-sided bounds | `[:int {:min a :max b}]`; `:double` likewise; `numeric` as `[:and decimal? [:>= a] [:<= b]]` |
| `CHECK (length(trim(col)) > 0)`, `col <> ''` | `[:string {:min 1}]` (`:pg/trim true` when trimmed) |
| `varchar(n)`, `CHECK (length(col) <= n)` | `[:string {:max n}]` |
| `CHECK (jsonb_typeof(col) = 'object')` | `[:map]` (`'array'` becomes `[:sequential :any]`) |
| `CHECK (col ~ 're')` | `[:and :string [:re "re"]]` (`~*` adds `(?i)`) |
| `CHECK (col IS NULL OR <any of the above>)` | `[:maybe <rendering>]` |
| `CHECK (col IS NOT NULL)` | no `[:maybe]` |
| column patterns joined with `AND` | each part rendered |
| CHECK across several columns, `NOT VALID` CHECK | not rendered; kept in `:unrendered` with the expression data |
| domain, composite and foreign-schema types | `[:any {:pg/type ...}]`, listed in `:unrendered` |
| `timestamp`, `timestamptz` | `inst?` |
| `date`, `time`, `interval`, range types | `[:any {:pg/type ...}]` |
| `json`, `jsonb` | `:any` |
| `bytea` | `bytes?` |
| `T[]` | `[:vector <T>]` |

Anything in `:unrendered` can be given a rendering through `:overrides`, keyed by constraint
name: a malli schema (typically `[:ref :app/name]`, defined in your own registry) or
`{:skip "reason"}`. A constraint name shared by several tables applies to all of them.

Spelling is left as the database has it. There is no keyword or kebab-case conversion; do that
on your side if you need it.

## Contract

These are kept compatible; a change bumps the minor version.

1. The four config keys.
2. The layout of the generated file (`:schema`, `:database-version`, `:registry`, `:unrendered`, `:skipped`).
3. The fact vocabulary (elements of `:unrendered`), documented in `pgmalli.impl.pattern`.

`pgmalli.impl.*` is internal and may change without notice.

## Conventions

1. After migrating, run `generate!` and commit the files.
2. In CI, assert `(nil? (stale config))`.
3. Review changes to `:unrendered` in pull request diffs.

## Limitations

- Reads tables (regular and partition parents), columns, CHECK constraints, enum and domain
  types. Views, indexes, triggers, policies and privileges are not read.
- Expressions are read in the form PostgreSQL's deparser prints them; arbitrary SQL is not parsed.
- Overrides must be data (the files are EDN), so a function cannot be an override; use
  `[:ref :app/name]` and define it in your registry.

## Development

```
bb test          # babashka
bb test:jvm      # JVM
PGMALLI_SKIP_DB=1 bb test   # only what runs without docker
```

Database tests start a throwaway PostgreSQL container with docker (`PGMALLI_PG_IMAGE`
selects the image, default `postgres:17-alpine`).

## License

MIT
