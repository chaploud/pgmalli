# PostgreSQL types and constraints in malli

Part of [pgmalli](../README.md): [types](types.md) · [reading rows](reading-rows.md) · [queries](queries.md) · [datasets](datasets.md)

What each part of a PostgreSQL schema becomes in the registry.

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

A row schema checks one row. PRIMARY KEY, UNIQUE and FOREIGN KEY hold across rows;
[`data/dataset-schema`](datasets.md#a-dataset) checks them.

An **insert schema** is a closed map. Identity ALWAYS and generated columns are left out.
Identity BY DEFAULT, defaulted and nullable columns are optional. The table's CHECKs read an
omitted column as what the database stores in it: its literal default, else NULL.

An **update schema** is a closed map of the same columns, every one optional. A NOT NULL
column cannot be set to NULL. It carries no table CHECK: those hold on the whole updated row,
which an UPDATE does not send.

A **view's** columns are all `[:maybe ...]`. Views have no insert schema and are not part of
datasets.

A **domain** is its base type with its CHECKs applied. The column decides whether NULL is
allowed. A domain's NOT NULL and DEFAULT apply to every column of the domain.

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
| `:pg/constraints` | the names of the CHECKs that shaped the column (a CHECK's own schema carries `:pg/constraint`, its name) |

The table's map carries:

| property | value |
|---|---|
| `:pg/table` | `"public.users"` (`:pg/view` for a view) |
| `:pg/primary-key` | `["id"]` |
| `:pg/unique` | `[{:columns ["email"]}]`; `:nulls-distinct false` for `NULLS NOT DISTINCT`, `:index true` for a unique index |
| `:pg/before-triggers` | `{:insert ["fill_owner"]}`: BEFORE row triggers, which may fill a row before NOT NULL is checked |
| `:pg/exclude` | `[{:constraint "slots_room_seat_excl" :using "gist" :elements [{:column "room" :op "="} ...]}]`; `:where true` for a partial one |
| `:pg/foreign-keys` | `[{:columns ["group_id"] :table "public.groups" :to ["id"]}]`; `:match :full` for `MATCH FULL` |

An identifier that is not a plain name (`Order Items`) makes a string key:
`"pg.public/Order Items"`, `"pg.public.Order Items/insert"`. Names keep the database's
spelling.

## Column types

| PostgreSQL | malli |
|---|---|
| NOT NULL | no `[:maybe ...]` |
| a column of an enum or domain type (an array of one) | `[:ref :pg.<schema>/<type>]` (`[:vector [:ref ...]]`) |
| a domain over an enum or domain | `[:ref ...]` to it, with its own CHECKs |
| `smallint`, `integer` | `:pg/smallint`, `:pg/integer` (bounded to the type's range) |
| `bigint` | `:int` |
| `numeric(p, s)` | `[:and decimal? [:pg/numeric {:precision p :scale s}]]` |
| `real`, `double precision` | `:double` |
| `text`, `varchar(n)`, `char(n)`, `name`, `"char"` | `:string`, `[:string {:max n :pg/max-length n}]` |
| `boolean` | `:boolean` |
| `uuid` | `:uuid` |
| `date`, `time`, `timetz` | `:time/local-date`, `:time/local-time`, `:time/offset-time` |
| `timestamp`, `timestamptz`, `interval` | `:time/local-date-time`, `:time/instant`, `:time/duration` |
| `json`, `jsonb` | `:any` |
| `bytea` | `bytes?` |
| `T[]` | `[:vector <T>]` |
| `oid` | `[:int {:min 0 :max 4294967295}]` |
| `bit(n)`, `bit varying(n)` | `[:string {:min n :max n}]`, `[:string {:max n}]` |
| `inet`, `cidr`, `macaddr`, `money`, `xml`, `tsvector`, geometric, range, multirange, `reg*` and other types | `[:any {:pg/type "..."}]` |
| `citext` | `:string` |
| `hstore`, `ltree`, `lquery`, `ltxtquery`, `cube`, `seg`, `query_int`, `isbn`, `isbn13`, `ean13`, `ismn`, `ismn13`, `issn`, `issn13`, `upc` | `[:any {:pg/type "..."}]` |
| a composite type, a type pgmalli does not support | `[:any {:pg/type "..."}]`, listed in `:unrendered` |

`:string`'s `:min` and `:max` count characters (code points), as `varchar(n)` and `length`
do; malli's own `:string`, in `portable` output, counts UTF-16 units. `:pg/max-length n` is the
type's `n`: as PostgreSQL does, it cuts spaces past `n` off (`"abcd  "` fits `varchar(4)`),
then `:min` and `:max` count what it keeps.

`:pg/numeric` rounds to `s` places, half up as PostgreSQL does, then allows fewer than
`p - s` digits before the point. `s` may be negative or larger than `p`, as PostgreSQL allows.

A row schema differs from PostgreSQL in a few places:

- An array's elements are not NULL: PostgreSQL takes NULL elements, a row schema refuses them.
- A `numeric(p, s)` value is checked by the column's CHECKs before PostgreSQL rounds it to `s`
  places.
- A `varchar(n)` value with spaces past `n` is checked by its CHECKs before those spaces are
  cut.

The types without a malli counterpart generate from a fixed list of literals PostgreSQL
accepts, and are inserted as text cast to the type (an interval too, in ISO 8601). A NOT NULL
column of a type pgmalli cannot make a value of fails to generate, naming it:
`public.t.p: pgmalli cannot make a value of type pair; give one with :overrides in pgmalli.edn`.
In a dataset its table comes out short with that reason.

Dates and times generate within 2025 (in domains and arrays too), `timetz` offsets within
what PostgreSQL takes, a `jsonb_typeof = 'array'` column an array of JSON values.

## Extensions

The types of these extensions are supported, in whatever schema the extension is in:
`citext`, `hstore`, `ltree`, `cube`, `seg`, `intarray` (`query_int`) and `isn`. `pgjdbc`
returns their values as `PGobject`s (`hstore` as a `java.util.Map`), which `as-read` takes.

Every type pgmalli supports is one entry of `types` in
[src/pgmalli/impl/types.clj](../src/pgmalli/impl/types.clj): its malli schema, the literals
it generates, the cast on insert and what the driver returns. The types it does not support
yet are listed under `unsupported` there, each with why
([CONTRIBUTING.md](../CONTRIBUTING.md#following-upstream) says how to add one).

## jsonb columns

PostgreSQL holds a jsonb column's shape in CHECKs at most (`jsonb_typeof(payload) =
'object'` becomes `:map`); its keys and their types are the application's. Write them as
malli and join them to the generated schema, with no copy of either:

```clojure
(require '[pgmalli.core :as pgmalli]
         '[malli.core :as m]
         '[malli.util :as mu]
         '[malli.error :as me]
         '[malli.generator :as mg])

(def registry (pgmalli/registry "public"))

(def evidence-v1 [:map [:course :string] [:score :int]])
(def evidence-v2 [:map [:course :string] [:score :int] [:graded_by :string]])
```

A payload whose shape another column names (a version) is a `:multi` on that column beside
the row schema:

```clojure
(def event
  (m/schema [:and :pg.public/events
             [:multi {:dispatch :schema_version}
              [1 [:map [:payload evidence-v1]]]
              [2 [:map [:payload evidence-v2]]]]]
            {:registry registry}))

(m/validate event {:id 1 :user_id 1 :schema_version 1 :payload {:course "sql" :score 3}})
;; => true

(me/humanize (m/explain event {:id 1 :user_id 1 :schema_version 2 :payload {:course "sql" :score 3}}))
;; => {:payload {:graded_by ["missing required key"]}}
```

Generate a version's rows from its own schema, so the generator need not guess the shape:

```clojure
(def new-event-v2
  (-> (pgmalli/columns registry :pg.public.events/insert)
      (mu/assoc :schema_version [:= 2])
      (mu/assoc :payload evidence-v2)))

(m/validate event (assoc (mg/generate new-event-v2 {:registry registry :seed 1}) :id 1)
            {:registry registry})
;; => true
```

## CHECK constraints

| CHECK | malli |
|---|---|
| `col IN (...)`, `col = 'x'` | `[:enum ...]`; `[:and [:ref ...] [:enum ...]]` on an enum or domain; `[:pg/check-value [:in :VALUE [...]]]` on a `numeric`, which equals `2.0` to `2` |
| `col NOT IN (...)`, `col <> 'x'` | `[:and <type> [:not [:enum ...]]]` |
| `col >= a AND col <= b`, `BETWEEN`, one-sided bounds | `{:min a :max b}`; an exclusive bound as `[:> a]` |
| `length(trim(col)) > 0` | `[:and [:string {:min 1}] [:re "[^ ]"]]` |
| `col <> ''` | `[:string {:min 1}]` |
| `length(col) <= n` | `[:string {:max n}]` |
| `cardinality(col) BETWEEN a AND b`, `array_length(col, 1) <= n` | `[:vector {:min a :max b} <T>]` |
| `jsonb_typeof(col) = 'object'` (`'array'`) | `:map` (`[:sequential :any]`) |
| `col ~ 're'` (`~*`) | `[:and :string [:re "re"]]` (with `(?iu)`; `.` as `[\s\S]`, as it matches a newline; `$` as `\z`, as Java's also matches before a final newline; a class such as `\w` or `[[:alpha:]]` as its ASCII members plus every non-ASCII character, see below) |
| `col LIKE 'a%'` (`ILIKE`) | `[:and :string [:re "^a[\s\S]*\z"]]` (with `(?iu)`) |
| `col IS NULL OR <a pattern>` | the pattern, or NULL |
| `col IS NOT NULL` | no `[:maybe ...]` |
| patterns joined with `AND` | each pattern |
| `status = 'a' AND ... OR status = 'b' AND ...` | `[:multi {:dispatch :status} ...]` |
| other ORs of patterns | `[:or [:map ...] [:map ...]]` |
| any other CHECK (`likes <= views`, arithmetic, `CASE`, jsonb operators) | `[:pg/check expr]` |
| a domain CHECK no pattern covers | `[:pg/check-value expr]`, `VALUE` as `:VALUE` |
| `NOT ENFORCED` (PostgreSQL 18) | nothing; listed in `:diagnostics` |
| `NOT VALID` | `[:pg/check {:pg/not-valid true} ...]` |

Outside ASCII, a class's members depend on the server's C library and locale: `[[:punct:]]`
takes `€` on glibc but not under the C locale. So a class takes every non-ASCII character
when a value is checked, and none when one is generated; a `[:pg/check]` whose result depends on
it is NULL, and passes.

A NULL makes a pattern NULL, not false: in `[:multi ...]` and `[:or ...]` a nullable column takes
`[:maybe ...]`, and a NULL dispatch value takes any branch.

Several CHECKs on one column only tighten it. A CHECK on a `char(n)` column, or on a domain over
`char(n)`, is a `[:pg/check expr]` (`[:pg/check-value expr]`): `char(n)` compares and measures
without its trailing spaces. Its column patterns still say how values generate: one of an
`IN`'s values, or strings of its regex or `LIKE`. A partitioned table's row schema carries a CHECK
named `<table> (partitions)`: the OR of its partitions' bounds.

> NOTE: a `NOT VALID` CHECK is enforced, as the database enforces it for every new row. A row
> from before it may fail; skip the CHECK with an override if such rows must pass.

Strings are generated from a regex on the JVM when `com.gfredericks/test.chuck` is on the
classpath (without the flags and `\z` test.chuck refuses, and with the ASCII members of a
class). Without it, and on babashka, strings are drawn and filtered.

## How `:pg/check` evaluates

`[:pg/check expr]` holds the CHECK as data (`[:<= :likes :views]`) and evaluates it as
PostgreSQL does:

- NULL passes; `AND`, `OR` and `COALESCE` stop at the first operand that decides.
- Integer division truncates; `smallint`, `integer` and `bigint` arithmetic out of its
  type's range fails the row. A cast to an integer rounds a float's ties to even, a numeric's
  away from zero.
- A literal with a point or an exponent is a `numeric`, as in PostgreSQL: `round(2.5)` is 3.
- Casts convert: dates, timestamps, fixed-length intervals, uuids, jsonb, arrays, and the
  schema's own enum and domain literals.
- A domain's CHECK reads `VALUE` as the domain's type: `char(n)` and the integer types as for
  a column.
- A `char(n)` column's trailing spaces count only for `LIKE`, regexes and `octet_length`.
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
> zone. Give [`transformer`](reading-rows.md#decoding-instead) the zone your JDBC driver
> used (`{:zone ...}`).

## What is read

Tables (regular and partitioned), views, materialized views, columns, CHECK, PRIMARY KEY,
UNIQUE, FOREIGN KEY and EXCLUDE constraints, unique indexes over plain columns, row-level
triggers, enum and domain types.

An EXCLUDE is a table property, not a check: a row it would reject validates all the same,
and each EXCLUDE is listed in `:diagnostics`. Datasets keep its `=` columns distinct, so their
rows never conflict, except for one with no `=` column, an expression or a WHERE. A row a
trigger would reject validates too; a row-level INSERT trigger is listed in `:diagnostics`.

Not read: other indexes, policies and privileges.

A generated column is left to the database. A range built from two columns
(`tsrange(valid_from, valid_until)`) gives them a CHECK: lower bound not above the upper.

## Registries

`pgmalli.core/registry` returns a map: malli's default schemas, `malli.util`'s,
`malli.experimental.time`'s, pgmalli's `:pg/*` types, and the generated and derived names.
pgmalli's names are all under `:pg`, so it hides none of malli's. A name defined in two of
the schemas passed keeps the later schema's.

Every pgmalli function taking a registry takes any malli registry holding the generated
names, a `malli.registry/composite-registry` included. In a composite registry the first
registry holding a name wins.

`pgmalli.core/columns` gives the `[:map ...]` of a row or insert schema without its table
CHECKs, which is what `malli.util` takes.

## The generated file

`pgmalli.core/generated` reads a schema's file as data:

| key | holds |
|---|---|
| `:schema` | the schema's name, `"public"` |
| `:database-version` | the major version it was read from, `"PostgreSQL 17"` |
| `:diagnostics` | what deserves a look in the database |
| `:registry` | `{name schema}`: the rows, enums and domains; the insert and update schemas are derived when it is loaded |
| `:unrendered` | what pgmalli cannot express, left out of the schemas |
| `:skipped` | what `:overrides` skips |

A diagnostic is `{:table :kind :severity :message}`, with `:constraint`, `:column` or
`:trigger` where it applies:

| `:kind` | severity | when |
|---|---|---|
| `:contradiction` | `:error` | a column's CHECKs contradict each other: no value fits |
| `:check-false` | `:warning` | `CHECK (false)`: the table takes no row |
| `:no-partition` | `:warning` | a partitioned table with no partition |
| `:unreachable-partition` | `:warning` | a partition whose bounds and its parent's do not overlap |
| `:unreachable-alternative` | `:warning` | an alternative of an OR CHECK that can match no row |
| `:not-valid` | `:info` | a `NOT VALID` constraint: rows from before it may violate it |
| `:not-enforced` | `:info` | a `NOT ENFORCED` CHECK, which the schema leaves out |
| `:row-trigger` | `:info` | a row-level INSERT trigger, which may reject or change rows |
| `:exclude` | `:info` | an EXCLUDE constraint, which the schemas do not check |
| `:redundant-unique` | `:info` | a UNIQUE on the columns of the primary key or of another UNIQUE |

An `:unrendered` or `:skipped` entry is `{:kind :table :column :constraint :sql :type}`, the
keys that apply; a domain's has `:domain` in place of `:table`.
