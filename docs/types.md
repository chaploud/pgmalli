# PostgreSQL types in malli

Part of [pgmalli](../README.md): [types](types.md) · [reading rows](reading-rows.md) · [queries](queries.md)

What each part of a PostgreSQL schema becomes in the registry.

## Names in the registry

For a schema `public`:

| name | holds |
|---|---|
| `:pg.public/<table>` | a row: `[:map ...]` |
| `:pg.public.<table>/insert` | what an INSERT may carry |
| `:pg.public.<table>/update` | what an UPDATE may set |
| `:pg.public/<view>` | a row of a view or materialized view |
| `:pg.public/<enum>` | `[:enum ...]`, its labels as strings (`"active"`), as PostgreSQL names them; a row read holds them as keywords ([reading rows](reading-rows.md#the-one-reading-and-sending), `as-read`) |

A row schema checks one row. PRIMARY KEY, UNIQUE and FOREIGN KEY hold across rows, and no
schema checks them.

A CHECK is no part of a schema: the database checks it, so a row a CHECK refuses validates.
The same holds for a domain's CHECK, a partition's bounds and the length or scale of a type
(`varchar(n)`, `numeric(p, s)`).

An **insert schema** is a closed map. Identity ALWAYS and generated columns are left out.
Identity BY DEFAULT, defaulted and nullable columns are optional.

An **update schema** is a closed map of the same columns, every one optional. A NOT NULL
column cannot be set to NULL.

Where a BEFORE row trigger runs for the write (`:pg/before-triggers`), every column of its
schema is optional and takes nil: the trigger may fill it.

A sequence has neither schema.

A **view's** columns are all `[:maybe ...]`. Views have no insert schema.

A **domain's** column is of the domain's base type, under all its domains and of whatever
schema; `:pg/domain` names the domain. The domain's NOT NULL and DEFAULT are the column's
(an array of a domain takes neither). The registry holds no entry of a domain.
A column of a NOT NULL domain is not `[:maybe ...]`, although PostgreSQL stores NULL there from an
empty scalar subquery; its documentation calls this a defect not to rely on.

pgmalli's own schema types are in the registry too: `:pg/bytes`, `:pg/smallint`,
`:pg/integer`, `:pg/array` and `:pg/class`. Some of malli's are replaced: `:string` counts
characters (code points, as `length` does; malli's counts UTF-16 units), `:double` with `:pg/type "real"` keeps to a real's range, `:re` matches a
value its regex runs out of the thread's stack on (a long one) on a larger stack, two at a time,
where malli's throws, and refuses one too long for that stack too, and malli.experimental.time's types (`:time/instant` ...) carry an error
message (`"should be a java.time.Instant"`), which malli.error has none of.

## Properties

Column schemas carry where they came from:

| property | value |
|---|---|
| `:pg/type` | the column's type, as PostgreSQL prints it, without its length (`"character varying"`) |
| `:pg/domain` | the domain the column is declared of (`"email"`), or `"email[]"` for an array of it; a domain of another schema schema-qualified |
| `:pg/max-length` | `n` of a `varchar(n)`, `char(n)`, `bit(n)` or `bit varying(n)`, which values generate within |
| `:pg/default` | a literal, or `{:sql "now()"}`, the default expression's SQL as the database prints it |
| `:default` | malli's default, for a literal default |
| `:pg/identity` | `:always`, `:default` or `:serial` |
| `:pg/generated` | `{:sql "..."}`, the SQL of the expression the database computes the column from |

The table's map carries:

| property | value |
|---|---|
| `:pg/table` | `"public.users"` (`:pg/view` for a view, and `:pg/materialized true` for a materialized one) |
| `:pg/primary-key` | `["id"]`; left out while its index is invalid (a key added `ONLY` to a partitioned table) |
| `:pg/primary-key-deferrable` | `true`: the primary key is DEFERRABLE, so it may be checked only at commit |
| `:pg/unique` | `[{:columns ["email"]}]`; `:nulls-distinct false` for `NULLS NOT DISTINCT`, `:index true` for a unique index, for a partial one `:where` its predicate where it is equalities of a column (a `varchar` too, which PostgreSQL casts to `text` there) to a string or integer and `IS NULL`s, ANDed (`[[:= "status" "active"] [:is-null "deleted_at"]]`), `true` otherwise, `:deferrable true` for a DEFERRABLE one; one whose index is invalid is left out |
| `:pg/inherited` | `true`: other tables inherit it (not as partitions), so a query reads their rows too |
| `:pg/before-triggers` | `{:insert ["fill_owner"]}`: BEFORE row triggers, which may fill a row before NOT NULL is checked; a partitioned table's its partitions' too |
| `:pg/exclude` | `[{:constraint "slots_room_seat_excl" :using "gist" :elements [{:column "room" :op "="} ...]}]`; `:where true` for a partial one |
| `:pg/foreign-keys` | `[{:columns ["group_id"] :table "public.groups" :to ["id"]}]`; `:match :full` for `MATCH FULL` |

An identifier that is not a plain name (`Order Items`) makes a string key:
`"pg.public/Order Items"`, `"pg.public.Order Items/insert"`. Names keep the database's
spelling.

## Column types

| PostgreSQL | malli |
|---|---|
| NOT NULL | no `[:maybe ...]`; a type of `:any` is `:some` |
| a column of an enum type (an array of one) | `[:ref :pg.<schema>/<type>]` (`[:vector [:ref ...]]`) |
| a column of a domain | its base type's, with `:pg/domain` |
| `smallint`, `integer` | `:pg/smallint`, `:pg/integer` (bounded to the type's range) |
| `bigint` | `:int` |
| `numeric`, `numeric(p, s)` | `decimal?` |
| `real`, `double precision` | `:double` |
| `text`, `varchar(n)`, `char(n)`, `name`, `"char"` | `:string` (`:pg/max-length n`) |
| `boolean` | `:boolean` |
| `uuid` | `:uuid` |
| `date`, `time`, `timetz` | `:time/local-date`, `:time/local-time`, `:time/offset-time` |
| `timestamp`, `timestamptz`, `interval` | `:time/local-date-time`, `:time/instant`, `:time/duration` |
| `json`, `jsonb` | `:any` |
| `bytea` | `bytes?` |
| `T[]` | `[:vector <T>]` |
| `oid` | `[:int {:min 0 :max 4294967295}]` |
| `bit(n)`, `bit varying(n)` | `:string` of 0s and 1s (`:pg/max-length n`) |
| `citext` | `:string` |
| `inet`, `cidr`, `macaddr`, `money`, `xml`, `tsvector`, geometric, range, multirange, `reg*` and other types | `[:any {:pg/type "..."}]` |
| a composite type, a type pgmalli does not support, a domain over an array of a domain (pgjdbc reads its elements as objects of the element's domain) | `[:any {:pg/type "..."}]`, listed in `:unrendered` |

An enum of another schema (`wh.stock.k` of type `catalog.kind`) is `[:any]` in the file, as each schema is read alone; its `:unrendered` entry names the schema
(`:type-schema "catalog"`). Generated with both schemas in `:schemas`, the registry reads the
column as of that type, an array of it too, and `:unrendered` leaves it out.

An interval is `:time/duration` in the registry, a `Duration`, which pgjdbc sends nowhere: a row
read holds a `PGInterval` ([reading rows](reading-rows.md#the-one-reading-and-sending)), and an
argument takes one.

A row schema differs from PostgreSQL where an array's elements are NULL: PostgreSQL takes NULL
elements, a row schema refuses them. What a row is read as (`as-read`, `analyze`'s `:row`) takes
them.

The types without a malli counterpart generate from a fixed list of literals PostgreSQL
accepts (an interval in ISO 8601). A NOT NULL
column of a type pgmalli cannot make a value of fails to generate, naming it:
`public.t.p: pgmalli cannot make a value of type pair; give one with :overrides`.

Dates and times generate within 2025 (in arrays too), `timetz` offsets within what PostgreSQL
takes, a string within its `:pg/max-length`.

## Extensions

The types of these extensions are supported, in whatever schema the extension is in:
`citext`, `hstore`, `ltree`, `cube`, `seg`, `intarray` (`query_int`) and `isn`. `pgjdbc`
returns their values as `PGobject`s (`hstore` as a `java.util.Map`), which `as-read` takes. A type
of one of their names that the extension did not make (a composite type `citext`) is none of them.

Every type pgmalli supports is one entry of `types` in
[src/pgmalli_next/impl/types.clj](../src/pgmalli_next/impl/types.clj): its malli schema, the literals
it generates, the cast on insert and what the driver returns. The types it does not support
yet are listed under `unsupported` in
[test/pgmalli_next/upstream_test.clj](../test/pgmalli_next/upstream_test.clj), each with why
([CONTRIBUTING.md](../CONTRIBUTING.md#following-upstream) says how to add one).

## jsonb columns

A jsonb column is `:any`: its keys and their types are the application's. An override of the column
(`{:overrides {:public.evidence/payload [:map [:course :string]]}}`) gives it one shape
([Overrides](../README.md#overrides)). For shapes that depend on another column,
write them as malli and join them to the generated schema, with no copy of either:

```clojure
(require '[pgmalli-next.core :as pgmalli]
         '[malli.core :as m]
         '[malli.util :as mu]
         '[malli.error :as me]
         '[malli.generator :as mg])

(def registry (pgmalli/registry))

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
  (-> (m/deref (m/schema :pg.public.events/insert {:registry registry}))
      (mu/assoc :schema_version [:= 2])
      (mu/assoc :payload evidence-v2)))

(m/validate event (assoc (mg/generate new-event-v2 {:registry registry :seed 1}) :id 1)
            {:registry registry})
;; => true
```

## What is read

Tables (regular, partitioned, each partition and foreign), views, materialized views, sequences, columns,
PRIMARY KEY, UNIQUE, FOREIGN KEY and EXCLUDE constraints, unique indexes over plain columns,
row-level triggers and enum types. A domain is read as its base type, with its NOT NULL and
DEFAULT.

An EXCLUDE or a trigger is not enforced: a row it would reject validates
([diagnostics](#the-generated-file)).

Not read: other indexes, policies and privileges, and what [above](#names-in-the-registry) leaves
to the database.

A generated column's expression is not evaluated; `:pg/generated` holds its SQL.

## Registries

`pgmalli-next.core/registry` returns a map: malli's default schemas, `malli.util`'s,
`malli.experimental.time`'s, pgmalli's `:pg/*` types, and the generated and derived names.
Its `:string`, `:double` and `:re` replace malli's ([above](#names-in-the-registry)).

Every pgmalli function taking a registry takes any malli registry holding the generated
names, a `malli.registry/composite-registry` included. In a composite registry the first
registry holding a name wins.

## The generated file

`pgmalli-next.core/generated` reads the generated file on the classpath (`pgmalli-next.edn`) as
data:

| key | holds |
|---|---|
| `:schemas` | the names of the schemas it holds, `["public"]` |
| `:database-version` | the major version it was read from, `"PostgreSQL 17"` |
| `:diagnostics` | what deserves a look in the database |
| `:registry` | `{name schema}`: the rows and enums of every schema; the insert and update schemas are derived when it is loaded |
| `:functions`, `:operators` | `{schema {name [overload ...]}}`: each schema's own functions (no procedure or trigger function) and operators, which [`pgmalli-next.honeysql`](queries.md#the-row-a-query-returns) resolves a call to beside `pg_catalog`'s. An overload is `{:args [type ...] :result type}`, types named as `:pg/type` names them, with `:set true` for a set-returning function, `:kind` (`:a` an aggregate, `:w` a window function), `:variadic true`, `:defaults` (how many of the last arguments may be left out), `:columns` (`[[name type] ...]` it returns in FROM: its OUT or `TABLE` arguments, or its composite type's columns), `:btree true` for a comparison operator |
| `:sequences` | `{schema [name ...]}`: the sequences' names; the registry holds a row schema of each by its name, `last_value` and `log_cnt` (`bigint`) and `is_called` (`boolean`), the columns every sequence has, with `:pg/sequence true` |
| `:unrendered` | the columns of a type pgmalli has no schema for |

A diagnostic is `{:table :kind :severity :message}`, with `:constraint` or `:trigger` where it
applies:

| `:kind` | severity | when |
|---|---|---|
| `:not-valid` | `:info` | a `NOT VALID` foreign key: rows from before it may violate it |
| `:not-enforced` | `:info` | a `NOT ENFORCED` foreign key (PostgreSQL 18), which the database never checks |
| `:row-trigger` | `:info` | a row-level INSERT trigger, which may reject or change rows |
| `:exclude` | `:info` | an EXCLUDE constraint, which the schemas do not check |
| `:redundant-unique` | `:info` | a UNIQUE on the columns of the primary key or of another UNIQUE |
| `:sequence-named-as-enum` | `:info` | a sequence of the name of an enum type of its schema (created before it): the registry's name is the enum's, and the sequence's columns are untyped in a query |

An `:unrendered` entry is `{:kind :type :table :column :type}`, with `:type-schema` for a type of
another schema.
