# PostgreSQL types and constraints in malli

What each part of a PostgreSQL schema becomes in the registry. The README shows how to use
them; this page is the reference.

## Names in the registry

For a schema `public`:

| name | holds |
|---|---|
| `:pg.public/<table>` | a row: `[:map ...]`, inside `[:and ...]` when the table has CHECKs |
| `:pg.public.<table>/insert` | what an INSERT may carry |
| `:pg.public.<table>/update` | what an UPDATE may set |
| `:pg.public/<view>` | a row of a view or materialized view |
| `:pg.public/<enum>` | `[:enum ...]` |
| `:pg.public/<domain>` | a non-NULL value of the domain |

An **insert schema** is a closed map. Identity ALWAYS and generated columns are left out.
Identity BY DEFAULT, defaulted and nullable columns are optional. The table's CHECKs read an
omitted column as what the database stores in it: its literal default, else NULL.

An **update schema** is a closed map of the same columns, every one optional. A NOT NULL
column cannot be set to NULL. It carries no table CHECK: those hold on the whole updated row,
which an UPDATE does not send.

A **view's** columns are all `[:maybe ...]`. Views have no insert schema and are not part of
datasets.

A **domain** is its base type with its CHECKs applied. Whether NULL is allowed is the
column's: a domain's NOT NULL and DEFAULT reach every column of that type.

pgmalli's own schema types are in the registry too: `:pg/check`, `:pg/check-value`,
`:pg/bytes`, `:pg/smallint`, `:pg/integer`, `:pg/numeric` and `:pg/array`.

## Properties

Column schemas carry where they came from:

| property | value |
|---|---|
| `:pg/type` | the column's type, as PostgreSQL prints it (`"character varying"`) |
| `:pg/default` | a literal, or the default expression as data |
| `:default` | malli's default, for a literal default |
| `:pg/identity` | `:always`, `:default` or `:serial` |
| `:pg/generated` | the expression the database computes the column from |
| `:pg/constraint` | the names of the CHECKs that shaped the column |

The table's map carries:

| property | value |
|---|---|
| `:pg/table` | `"public.users"` (`:pg/view` for a view) |
| `:pg/primary-key` | `["id"]` |
| `:pg/unique` | `[{:columns ["email"]}]`; `:nulls-distinct false` for `NULLS NOT DISTINCT`, `:index true` for a unique index |
| `:pg/foreign-keys` | `[{:columns ["group_id"] :table "public.groups" :to ["id"]}]`; `:match :full` for `MATCH FULL` |

An identifier that is no plain name (`Order Items`) is a string key. Names keep the
database's spelling.

## Column types

| PostgreSQL | malli |
|---|---|
| NOT NULL | no `[:maybe ...]` |
| a column of an enum or domain type | `[:ref :pg.<schema>/<type>]` |
| `smallint`, `integer` | `:pg/smallint`, `:pg/integer` (bounded to the type's range) |
| `bigint` | `:int` |
| `numeric(p, s)` | `[:and decimal? [:pg/numeric {:precision p :scale s}]]` |
| `real`, `double precision` | `:double` |
| `text`, `varchar(n)`, `char(n)` | `:string`, `[:string {:max n}]` |
| `boolean` | `:boolean` |
| `uuid` | `:uuid` |
| `date`, `time`, `timetz` | `:time/local-date`, `:time/local-time`, `:time/offset-time` |
| `timestamp`, `timestamptz`, `interval` | `:time/local-date-time`, `:time/instant`, `:time/duration` |
| `json`, `jsonb` | `:any` |
| `bytea` | `bytes?` |
| `T[]` | `[:vector <T>]` |
| `oid` | `:int` |
| `bit(n)`, `bit varying(n)` | `[:string {:min n :max n}]`, `[:string {:max n}]` |
| `inet`, `cidr`, `macaddr`, `money`, `xml`, `tsvector`, geometric, range and other types | `[:any {:pg/type "..."}]` |
| a composite type, an extension's type | `[:any {:pg/type "..."}]`, listed in `:unrendered` |

`:pg/numeric` rounds to `s` places, half up as PostgreSQL does, then allows fewer than
`p - s` digits before the point. `s` may be negative or larger than `p`, as PostgreSQL allows.

The types without a malli counterpart are generated from a fixed list of literals the
database reads.

## CHECK constraints

| CHECK | malli |
|---|---|
| `col IN (...)`, `col = 'x'` | `[:enum ...]` |
| `col NOT IN (...)`, `col <> 'x'` | `[:and <type> [:not [:enum ...]]]` |
| `col >= a AND col <= b`, `BETWEEN`, one-sided bounds | `{:min a :max b}`; an exclusive bound as `[:> a]` |
| `length(trim(col)) > 0` | `[:and [:string {:min 1}] [:re "\S"]]` |
| `col <> ''` | `[:string {:min 1}]` |
| `length(col) <= n` | `[:string {:max n}]` |
| `cardinality(col) BETWEEN a AND b`, `array_length(col, 1) <= n` | `[:vector {:min a :max b} <T>]` |
| `jsonb_typeof(col) = 'object'` (`'array'`) | `:map` (`[:sequential :any]`) |
| `col ~ 're'` (`~*`) | `[:and :string [:re "re"]]` (with `(?i)`) |
| `col LIKE 'a%'` (`ILIKE`) | `[:and :string [:re "^\Qa\E.*$"]]` (with `(?i)`) |
| `col IS NULL OR <a pattern>` | the pattern, NULL passing it |
| `col IS NOT NULL` | no `[:maybe ...]` |
| patterns joined with `AND` | each pattern |
| `status = 'a' AND ... OR status = 'b' AND ...` | `[:multi {:dispatch :status} ...]` |
| other ORs of patterns | `[:or [:map ...] [:map ...]]` |
| any other CHECK (`likes <= views`, arithmetic, `CASE`, jsonb operators) | `[:pg/check expr]` |
| a domain CHECK no pattern covers | `[:pg/check-value expr]`, `VALUE` as `:VALUE` |
| `NOT ENFORCED` (PostgreSQL 18) | nothing; listed in `:diagnostics` |
| `NOT VALID` | `[:pg/check {:pg/not-valid true} ...]` |

Several CHECKs on one column only tighten it. A partitioned table's row schema carries a CHECK
named `<table> (partitions)`: the OR of its partitions' bounds.

> NOTE: a `NOT VALID` CHECK is enforced, as the database enforces it for every new row. A row
> from before it may fail; skip the CHECK with an override if such rows must pass.

Strings are generated from a regex on the JVM when `com.gfredericks/test.chuck` is on the
classpath. Without it, and on babashka, strings are drawn and filtered.

## How `:pg/check` evaluates

`[:pg/check expr]` holds the CHECK as data (`[:<= :likes :views]`) and evaluates it as
PostgreSQL does:

- NULL passes; `AND`, `OR` and `COALESCE` stop at the first operand that decides.
- Integer division truncates. Casts convert: dates, timestamps, fixed-length intervals, uuids,
  jsonb, arrays, and the schema's own enum and domain literals.
- `now()` is the time of validation.
- An expression the database would fail on (division by zero, a cast that does not parse)
  fails the row.
- A column missing from the map is NULL, or its literal default in an insert schema.

The operators covered are comparison, logic, `IN`, `BETWEEN`, `IS DISTINCT FROM`, arithmetic,
`LIKE`, regexes, `CASE`, and the common string, numeric, array and jsonb functions. A CHECK
outside them (a function of your own, a cast to a range type, `AT TIME ZONE`) is listed in
`:unrendered`. The patterns of such a CHECK that did render stay applied.

Values are the registry's types: `java.time`, `UUID`, jsonb as maps and vectors.

> NOTE: a CHECK comparing `timestamp` with `timestamptz` or `now()` converts in the JVM's time
> zone. Give `transformer` the zone your JDBC driver used (`{:zone ...}`).

## What is read

Tables (regular and partitioned), views, materialized views, columns, CHECK, PRIMARY KEY,
UNIQUE and FOREIGN KEY constraints, unique indexes over plain columns, enum and domain types.

Not read: other indexes, EXCLUDE constraints, triggers, policies and privileges. A row a
trigger or an EXCLUDE would reject validates all the same; a row-level INSERT trigger is
listed in `:diagnostics`.

A generated column is left to the database. A range built from two columns
(`tsrange(valid_from, valid_until)`) gives them a CHECK: lower bound not above the upper.

## Registries

`pgmalli.core/registry` returns a map: malli's default schemas, `malli.util`'s,
`malli.experimental.time`'s, pgmalli's `:pg/*` types, and the generated and derived names.
pgmalli's names are all under `:pg`, so it hides none of malli's. Given the same schema twice,
the later one is kept.

Every pgmalli function taking a registry takes any malli registry holding the generated
names, a `malli.registry/composite-registry` included. In a composite registry the first
registry holding a name wins.
