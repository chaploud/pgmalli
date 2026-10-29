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

;; a row as read from the database: every column present, NULL ones as nil
(m/validate :pg.public/users row {:registry registry})

;; what an INSERT may carry: no identity or generated columns, defaults optional, closed map
(-> (m/explain :pg.public.users/insert {:email "x" :status "closed"} {:registry registry})
    me/humanize)
;; => {:closed_at ["chk_closed_at_matches_status"]}

;; test data that satisfies the constraints (a :seed makes it the same every time)
(mg/generate :pg.public.users/insert {:registry registry :seed 42})

;; enum values are strings; cast them when the row goes to SQL (HoneySQL shown)
{:insert-into :users :values [(update row :status #(vector :cast % :user_status))]}
```

## Setup

```clojure
io.github.chaploud/pgmalli {:mvn/version "..."}   ; deps.edn or bb.edn
```

Generating needs PostgreSQL 16 or later and `psql`, wherever `generate` or `check` runs
(the development machine and CI; JVM base images rarely ship it). Connection settings are
psql's own (`PGHOST`, `PGDATABASE`, `PGUSER`, `PGPASSWORD`, `~/.pgpass`) or the `:db` map.
On the JVM, a `:db` of `{:jdbc-url "jdbc:postgresql://..." :user ... :password ...}` or
`{:datasource ds}` reads over the PostgreSQL JDBC driver on the classpath instead, no psql
needed. Applications only need the generated files.

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
 :db {:host "localhost" :port 5432 :db "app_dev" :user "app"}}   ; optional; :password too, else PGPASSWORD or ~/.pgpass
```

The same from Clojure, in `pgmalli.generate`: `(generate/generate! config)` and
`(generate/check config)`, which returns `{:stale :unrendered :diagnostics}` in one read
(`{:db? false}` reads the files alone).

Convention: regenerate right after migrating and commit the files; in CI assert
`(nil? (:stale (generate/check config)))`, or run `check`, which prints one line per column,
property or CHECK that differs between the file and the database, then the unrendered facts
and the diagnostics. `(generate/diff before after)` gives the same differences between two
generated files, a migration's effect on the schemas.

Four namespaces, by what they need: `pgmalli.core` reads the generated files and needs
nothing else; `pgmalli.generate` needs `psql` or a JDBC driver; `pgmalli.data` (datasets) needs test.check;
`pgmalli.honeysql` reads HoneySQL query data.

## What you get

For a schema `public`, the registry contains:

| name | schema |
|---|---|
| `:pg.public/<enum>` | `[:enum ...]` |
| `:pg.public/<domain>` | what a non-NULL value of the domain must be: its base type (`numeric(12,2)`, `varchar(80)` included) with the domain's CHECKs applied (as column patterns, else `[:pg/check-value expr]`). Whether NULL is allowed is the column's: a domain's NOT NULL and DEFAULT reach the columns of that type |
| `:pg.public/<table>` | a valid row: `[:map ...]`, wrapped in `[:and ...]` with the table's constraints when it has any |
| `:pg.public/<view>` | a row of a view or materialized view: columns and types, every column `[:maybe ...]`, `:pg/view` on the map; no insert schema, not part of datasets |
| `:pg.public.<table>/insert` | what an INSERT may carry: identity ALWAYS and generated columns removed, identity BY DEFAULT, defaulted and nullable columns optional, `{:closed true}`; the table's constraints see an omitted column as what the database stores in it (its literal default, else NULL) |
| `:pg.public.<table>/update` | what an UPDATE may set: the same columns, every one optional, each holding what the column holds (a NOT NULL column cannot be set to NULL), `{:closed true}`; no table constraints, since they hold on the updated row, which the columns sent do not show |
| `:pg/check`, `:pg/check-value`, `:pg/bytes`, `:pg/smallint`, `:pg/integer` | the schema types behind `[:pg/check expr]`, `[:pg/check-value expr]`, `[:pg/bytes {:min :max}]` and the two bounded integers |

Column schemas carry provenance in their properties: `:pg/type`, `:pg/default` (a literal or
the default expression as data), `:pg/identity` (`:always`, `:default` or `:serial`),
`:pg/generated` (the expression the database computes the column from, as data),
`:pg/constraint` (names of the CHECKs that shaped it). Literal defaults also
set malli's `:default`. The map carries `:pg/table` (`"public.users"`), `:pg/primary-key`,
`:pg/unique` (`{:columns [...]}`, with `:nulls-distinct false` for `NULLS NOT DISTINCT`; unique
indexes over plain columns without a predicate count as well, marked `:index true`) and
`:pg/foreign-keys` (`{:columns [...] :table "public.groups" :to [...]}`, with `:match :full`
for `MATCH FULL`), composite keys included. Files generated by pgmalli 0.1 are refused when
loaded; regenerate them.

Identifiers that are not plain names (`Order Items`) become string keys. Spelling is left as
the database has it.

### PostgreSQL to malli

| PostgreSQL | malli |
|---|---|
| NOT NULL | no `[:maybe ...]` |
| column of an enum or domain type | `[:ref :pg.<schema>/<type>]` |
| `smallint`, `integer` | `:pg/smallint`, `:pg/integer`: schema types with the PostgreSQL range (CHECK bounds narrow them through `:min` and `:max`); `bigint` is `:int`, exactly a long |
| `numeric(p, s)` | `[:and decimal? [:pg/numeric {:precision p :scale s}]]`: rounded to `s` places (half up, as the database does), then fewer than `p - s` digits before the point; `s` above `p` or negative as PostgreSQL allows |
| `CHECK (col IN (...))`, `CHECK (col = 'x')` | `[:enum ...]`; several on one column intersect; uuid values as `#uuid` |
| `CHECK (col NOT IN (...))`, `CHECK (col <> 'x')` | `[:and <type> [:not [:enum ...]]]`, or removed from an `[:enum ...]` |
| `CHECK (col >= a AND col <= b)`, `BETWEEN`, one-sided bounds | `[:int {:min a :max b}]`; `:double` likewise, an exclusive bound as `[:and :double [:> a]]`; `numeric` as `[:and decimal? [:>= a] [:<= b]]` |
| `CHECK (length(trim(col)) > 0)` | `[:and [:string {:min 1}] [:re "\S"]]`; `col <> ''` is `[:string {:min 1}]` |
| `varchar(n)`, `CHECK (length(col) <= n)` | `[:string {:max n}]`; bounds from several sources only tighten |
| `CHECK (cardinality(col) BETWEEN a AND b)`, `CHECK (array_length(col, 1) <= n)` | `[:vector {:min a :max b} <T>]` |
| `CHECK (jsonb_typeof(col) = 'object')` | `:map` (`'array'` becomes `[:sequential :any]`) |
| `CHECK (col ~ 're')` | `[:and :string [:re "re"]]` (`~*` adds `(?i)`; POSIX classes such as `[[:digit:]]` in their Java form); generated from the regex on the JVM (test.chuck; babashka draws strings and filters them) |
| `CHECK (col LIKE 'a%')` | `[:and :string [:re "^\Qa\E.*$"]]` (`ILIKE` adds `(?i)`) |
| `CHECK (col IS NULL OR <any of the above>)` | the inner pattern applied to the column (NULL passes it); `[:maybe ...]` comes from the column being nullable |
| `CHECK (col IS NOT NULL)` | no `[:maybe ...]` |
| column patterns joined with `AND` | each part |
| `CHECK (status = 'a' AND ... OR status = 'b' AND ...)` | `[:multi {:dispatch :status} ["a" [:map ...]] ["b" [:map ...]]]` |
| `CHECK (x IS NULL OR y = 'v' AND ...)` and other ORs of column patterns | `[:or [:map ...] [:map ...]]` |
| any other CHECK (`score <= total`, arithmetic, `CASE`, jsonb operators), or one of the above on a column whose type has no such rendering (`col = '2020-01-01'::date`) | `[:pg/check expr]`: the expression as data, validated by a schema type pgmalli registers |
| domain `CHECK` outside the patterns | `[:pg/check-value expr]` on the domain, `VALUE` as `:VALUE` |
| `NOT ENFORCED` CHECK or FOREIGN KEY (PostgreSQL 18) | nothing: the database never checks it; noted in `:diagnostics` |
| `NOT VALID` CHECK | enforced as a whole `[:pg/check {:pg/not-valid true} ...]`, since the database enforces it for every new row; a row from before it may not validate (skip it with an override if such rows must) |
| `date`, `time`, `timetz`, `timestamp`, `timestamptz`, `interval` | `:time/local-date`, `:time/local-time`, `:time/offset-time`, `:time/local-date-time`, `:time/instant`, `:time/duration` |
| `json`, `jsonb` | `:any` (`:map` or `[:sequential :any]` when a CHECK pins the type) |
| `bytea` | `bytes?`; with `CHECK (octet_length(col) = n)` `[:pg/bytes {:min n :max n}]`, a type that generates byte arrays of that length |
| `T[]` | `[:vector <T>]`; `varchar(n)[]` bounds the elements |
| `oid` | `:int` |
| `bit(n)`, `bit varying(n)` | `[:string {:min n :max n}]` / `[:string {:max n}]`, generated as digits |
| `inet`, `cidr`, `macaddr`, `money`, `xml`, `tsvector`, `tsquery`, `jsonpath`, geometric, range, multirange, `pg_lsn`, `reg*` | `[:any {:pg/type "..."}]`: the driver hands these over as its own objects; a dataset generates them as literals the database reads |
| a partitioned table | its row schema carries a CHECK named `<table> (partitions)`, the OR of its partitions' bounds: a row it takes is one some partition takes |
| a composite type, an extension's type | `[:any {:pg/type ...}]`, listed in `:unrendered` |

`(:unrendered (pgmalli/generated "public"))` lists the facts that have no rendering, each
with the constraint's expression as data. Give them one through `:overrides`, keyed by constraint name:
a malli schema (`[:ref :app/name]` defined in your own registry, for instance) or
`{:skip "reason"}`. A keyword key names a type instead: `{:inet [:re "^[0-9.]+$"]}` makes
every `inet` column that schema (the column's own CHECKs still apply on top).

`:pg/check` keeps the expression as data (`[:<= :score :total]`, HoneySQL-style) and
evaluates it as PostgreSQL would: NULL passes, `AND`, `OR` and `COALESCE` stop at the first
decisive operand, integer division truncates, casts convert (dates, timestamps, intervals of
fixed length, uuids, jsonb, arrays and the schema's own enum and domain literals included),
`now()` is the validation time, and an expression the database would fail on (division by
zero, a cast that does not parse) fails the row. The vocabulary covers comparison, logic,
`IN`, `BETWEEN`, `IS DISTINCT FROM`, arithmetic, the common string, numeric, array and jsonb
functions and operators, `LIKE`, regexes and `CASE`; a CHECK outside it (user-defined
functions, casts to range or geometric types, composite fields, `AT TIME ZONE`) stays in
`:unrendered`; the column patterns of such a CHECK that did render stay applied, since each
conjunct of an `AND` is necessary on its own, while a `:multi` or `:or` is never enforced in
part. A column missing from the map is NULL to it (its literal default in an insert schema).
Rows hold the registry's types: `java.time` values, `UUID`, jsonb as maps (string or keyword
keys) and vectors. A CHECK comparing `timestamp` with `timestamptz` or `now()` converts in
the JVM's zone; give the transformer the zone your JDBC driver used when it turned timestamps
into Instants (the JVM's, unless you configured otherwise), so both read the same clock.

## Working with the registry

Every function taking a registry takes any malli registry holding the generated names: the
map `registry` returns, or a `malli.registry/composite-registry` of it and your own.

`registry` returns a plain map: malli's default schemas, `malli.util`'s, `malli.experimental.time`'s,
pgmalli's `:pg/*` types, the generated names (`:pg.<schema>/<name>`) and the ones derived from
them (`:pg.<schema>.<table>/insert`, `/update`). Everything pgmalli adds is under `:pg`, so it
hides none of malli's names; a schema given twice, the later one is kept. In a composite
registry the first registry holding a name wins, for malli's lookups and for the schemas
pgmalli reads from it alike.

```clojure
(pgmalli/registry "public" "auth")          ; several schemas, plus malli's defaults, malli.util and malli.experimental.time
(pgmalli/generated "public")                ; the file as data: :schema :database-version :diagnostics :registry :unrendered :skipped
(pgmalli/columns registry :pg.public/users) ; the [:map ...] alone: what malli.util's select-keys, optional-keys and the like take
(m/decode :pg.public/users jdbc-row {:registry registry} (pgmalli/transformer))
                                            ; JDBC (java.sql.Date / Timestamp, Instant) and string values into the registry's types
(pgmalli/transformer {:zone (java.time.ZoneId/of "UTC")})
                                            ; zone for Instants landing in timestamp (without time zone) columns; default the JVM's
(pgmalli/column registry :pg.public/users :nick)      ; => [:maybe [:string {:max 40 :pg/type "character varying"}]]
(pgmalli/non-null (pgmalli/column registry :pg.public/users :nick))   ; what a non-NULL value must be
```

### Rows as the driver returns them

`as-read` gives the `[:map ...]` of a row as next.jdbc builds it from what PostgreSQL's JDBC
driver returns, so results can be validated as they come back. By default that is plain
next.jdbc: keys unqualified, dates and timestamps `inst?` (`java.sql.Date`, `Timestamp`),
arrays (`java.sql.Array`), json and jsonb (`PGobject`) and intervals (`PGInterval`) `:any`.
The options say what your application does differently; `pgmalli.honeysql` takes the same
ones for the rows and arguments of queries (`:read` types rows alone, `:time` both), and both
refuse a key or value they do not know.

| option | for |
|---|---|
| `:qualified? true` | keys `:users/id`, as `as-maps` builds them |
| `:kebab? true` | kebab-cased keys and tables, as the kebab builders do |
| `:lower? true` | lower-cased keys and tables, as the lower builders do |
| `:nil-columns :absent` | NULL columns missing, as `next.jdbc.optional` builds rows |
| `:time :instant` / `:local` | `next.jdbc.date-time`'s `read-as-instant` / `read-as-local` |
| `:read {pg-type schema}` | what a `ReadableColumn` extension of yours returns for a `:pg/type` |
| `:read {:array :vector}` | an extension returning arrays as vectors of their elements |
| `:read {:json :parsed}` | an extension returning json parsed, as the registry shapes it |

```clojure
(pgmalli/as-read registry :pg.public/users {:qualified? true :nil-columns :absent :time :instant})
;; => [:map {...} [:users/id [:pg/integer ...]] [:users/nick {:optional true} [:string ...]] ...]

(pgmalli/read-options 'next.jdbc.result-set/as-unqualified-kebab-maps)
;; => {:kebab? true}, the options a builder's name gives
```

### Queries checked against the registry

`pgmalli.honeysql` reads HoneySQL query data, no database needed: the tables must exist,
the columns a query selects, returns, inserts, sets or compares must exist, an INSERT must
carry the columns its insert schema requires, an enum literal must be one of the enum's
values. From the same data it derives the types of the query's parameters and of the rows
it returns, as a malli function schema for `malli.instrument` or `malli.dev`. CTEs,
subqueries and table functions are opaque: their columns exist but have no type. A
subquery's columns are resolved in the subquery first, then in the statements around it.

```clojure
(require '[pgmalli.honeysql :as h])

(h/check registry {:insert-into :users :values [{:group_id 1 :mood "angry"}]})
;; => [{:kind :missing-required-column :table "public.users" :column "score"}
;;     {:kind :enum-literal :column :mood :value "angry" :allowed #{"happy" "sad"}}]

;; arguments are HoneySQL's named parameters, under the key sql/format's :params takes
(def find-user-q {:select [:id :nick] :from [:users] :where [:= :id :?id]})

(defn find-user
  {:malli/schema (h/query-schema registry [:db :id] find-user-q)}   ; :db is not in the query: :any
  [db id] (jdbc/execute! db (sql/format find-user-q {:params {:id id}})))
;; => [:=> [:cat :any [:int {...}]] [:sequential [:map [:id [:int {...}]] [:nick [:maybe [:string {...}]]]]]]

(h/arg-types registry {:select [:id] :from [:users] :where [:in :mood :?moods] :limit :?n})
;; => {:moods [:or [:sequential {:min 1} [:enum {...} "happy" "sad"]] [:set {:min 1} ...]], :n :int}
```

Rows and arguments are typed as next.jdbc and PostgreSQL's JDBC driver return and take them,
under the options of `as-read` (above). The ones of queries alone:

| option | for |
|---|---|
| `:schema "auth"` | unqualified table names (default `"public"`) |
| `:write {pg-type schema}` | what a `SettableParameter` extension of yours sends for a `:pg/type` (`{"text" [:or :string :keyword]}`); next.jdbc sends no keyword without one |
| `:write {:array [:pg/array :sequential :set]}` | what an array argument may be (an array column's, `[:any x]`'s); `:any` without it. `[:pg/array t]` is what the driver takes: a typed Java array of `t`'s or a `java.sql.Array` |
| `:compare :lenient` | a compared argument takes any value of the column's kind, nil too (not in `:in`): one the column cannot hold matches no row, as NULL does. `:strict` (default) keeps the column's type |
| `:result :one` | `query-schema` for a function returning one row or nil (`[:maybe row]`) |

A compared argument has the column's type without NULL; one in `:in` a non-empty vector,
list or set of it, as `sql/format` expands them (`IN ()` is not SQL); an assigned one the
column's type with NULL; one in `:limit` / `:offset` `:int`. One the data shows only inside
code (`(when from [:>= :id :?from])`) takes nil too, the code possibly leaving it out. A
symbol that is not a named parameter is an argument too, for a query written in code with
locals; where a table goes, a symbol is a table chosen at run time.

Without `:time`, and with no `:read` or `:write` naming them, the schemas are data malli's
default registry reads; `:time :instant` or `:local`, and `:pg/array`, need pgmalli's registry:
make the schema an object over it (below). A problem `check` finds in a query is the function
schema's `:pgmalli/problems` property, its row then `:any` when it cannot be told: the schema
is made as its namespace loads, which a typo should not stop. An ambiguous column comes with
the tables it could belong to, under `:candidates`. `check`, `query-schema`, `row-schema` and
`arg-types` are the functions to use; the rest of the namespace are their parts, for taking a
query apart.

Every query of a project checked in one test, whatever holds the queries (here, a var per
query with the HoneySQL map in its metadata):

```clojure
(deftest queries-agree-with-the-database
  (doseq [v (vals (ns-publics 'app.queries)) :let [q (:query (meta v))] :when q]
    (is (= [] (h/check registry q)) (str v))))
```

### Schemas for `:malli/schema` and `malli.dev`

`malli.instrument` and `malli.dev/start!` read a var's `:malli/schema` with `m/schema` and no
options: schema data through malli's default registry, a schema object as it is, with the
registry it was made over. So an object over pgmalli's registry needs nothing installed, and
the default registry keeps its names:

```clojure
(defn find-user
  {:malli/schema (m/schema (h/query-schema registry [:db :id] find-user-q {:time :instant})
                           {:registry registry})}
  [db id] ...)
```

Data works there too when it names only what the default registry has: `pgmalli.honeysql`'s
schemas without `:time`, `:read` and `:write` naming other types, and `portable` once
`malli.experimental.time` is in the default registry (its time columns stay `:time/*`).
`portable` gives the named schema with its own enums and domains inlined, pgmalli's types as
their malli counterparts and generation hints dropped; the CHECKs only pgmalli evaluates
(`:pg/check`, `:pg/check-value`) are left out of it.

```clojure
(defn find-user
  {:malli/schema [:=> [:cat (pgmalli/non-null (pgmalli/column registry :pg.public/users :id))]
                  [:maybe (pgmalli/portable registry :pg.public/users)]]}
  [id] ...)
```

`(pgmalli/install! "public")` makes pgmalli's registry malli's default one instead, so
`:pg.public/users` works in data anywhere. It is process-wide: every schema in the process then
resolves the `:pg` and `:time` names, including one meant to work without them, whose mistake
it then hides. Keep it for an application with this one registry.

Datasets (fixtures, seeds) are checked as a whole: primary keys and unique constraints
(`NULLS NOT DISTINCT` respected) within a table, foreign keys (`MATCH FULL` respected) across
tables, including tables of other schemas in the registry. Each constraint is a check of its
own, named in the error. The generator points references at generated rows, solving
references that share columns together, and handles self-references; tables in a reference
cycle are not supported. When a registry is loaded it adds generation hints (`:gen/min`,
`:gen/max`; the files carry none) so key columns are small positive integers, strings short
and times recent.

```clojure
(require '[pgmalli.data :as data])

(def dataset (data/dataset-schema registry))             ; {"public.groups" [...] "public.users" [...]}
(m/validate dataset {"public.groups" [{:id 1 ...}] "public.users" [{:group_id 1 ...}]} {:registry registry})
(def sample (clojure.test.check.generators/generate (data/dataset-generator registry {:rows 5 :except #{"public.audit_log"}}) 30 42))
;; :rows wanted per table, picked from many more candidates; a reference that finds no fitting
;; row grows its target table; :except leaves tables out (no kept table may reference them)
(data/short-tables sample)
;; => {"public.jobs" {:wanted 5 :got 0 :reasons [["{:params [\"chk_jobs_params\"]}" 200]]}}
;; tables that came out short, with what their candidate rows failed on; nil when none
```

Rows come from the column schemas, with the branch of a `:multi` or `:or` filled in from its
own fragment; a `:pg/check` holds by rejection, so a CHECK that wants structured jsonb leaves
its table short. Generation costs: 70 tables of 15 columns with 6 CHECKs each at `:rows 5`
(350 rows) take about 1.4 s on a warm JVM, and the time grows faster than linearly in `:rows`.
For a fixture, generate once with a fixed seed, keep it in the repository, and let tests read it:
`write-dataset` and `read-dataset` carry the `java.time` values and byte arrays EDN has no
literal for under pgmalli's tags (`#pgmalli/instant "..."`, `#pgmalli/bytes "hex"`).

```clojure
(data/write-dataset "test/resources/fixture.edn" sample)
(def fixture (data/read-dataset "test/resources/fixture.edn"))   ; short-tables still knows what came out short
```

Byte arrays compare by identity, so a dataset holding `bytea` values is not `=` to itself
read back; its rows load the same.

`inserts` turns a dataset into HoneySQL INSERT maps, one per table, in an order the database
accepts (parents first, and within a table the rows referred to first), enum values cast to
their type, json written and cast, arrays with their element type, a column a row lacks
`DEFAULT`. Generated columns are left out and identity columns kept (`OVERRIDING SYSTEM
VALUE`), so the ids the rows refer to each other by hold:

```clojure
(doseq [q (data/inserts registry fixture)]
  (jdbc/execute! db (honey.sql/format q)))
```

`dataset-schema` and `dataset-generator` are built at runtime and contain functions; the
generated files stay data.

### What the database stores but no row can satisfy

Some states PostgreSQL keeps are worth a look: a partitioned table with no partition (it takes
no row), a partition its parent's bounds make unreachable, a `CHECK (false)`, CHECKs on one
column that contradict each other, a constraint left `NOT VALID`, a unique index repeating a
key, a row-level INSERT trigger (its code may reject or change rows the schema accepts). The
generated file lists them under `:diagnostics` (`(:diagnostics (pgmalli/generated "public"))`),
each with a `:kind`, a `:severity` and a `:confidence` (`:proven` when the catalog alone
shows it), and `check` prints them. Generation goes on regardless: the schemas say what the
database says.

## Contract

Kept compatible; a change bumps the minor version.

1. The config keys `:schemas` `:out-dir` `:overrides` `:db`.
2. The generated file: `{:schema :database-version :diagnostics :registry :unrendered :skipped}`
   and the registry names above (insert schemas are derived at load time, not stored).
3. The fact vocabulary of `:unrendered` (`pgmalli.impl.pattern`).
4. The tags `write-dataset` writes (`#pgmalli/instant`, `#pgmalli/date`, `#pgmalli/date-time`,
   `#pgmalli/time`, `#pgmalli/offset-time`, `#pgmalli/duration`, `#pgmalli/bytes`), since a
   fixture is kept in a repository as long as a generated file is.

`pgmalli.impl.*` may change without notice.

## Scope

Tables (regular and partition parents, whose partitions' bounds become a CHECK; a hash
partition's bound is computed as the database computes it, with its own hash functions,
checked against every supported version), views and
materialized views, columns, CHECK, PRIMARY KEY, UNIQUE and FOREIGN KEY constraints, unique
indexes, enum and domain types. A
generated column is left to the database, except that a range built from two columns
(`tsrange(valid_from, valid_until)`) gives them a CHECK, lower bound not above the upper.
Other indexes, EXCLUDE constraints, triggers, policies and privileges are not read: rows a
trigger or an EXCLUDE would reject validate all the same. Expressions are read in the form
PostgreSQL's deparser prints them.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md). The suite includes property-based round trips through
PostgreSQL: expressions are stored as CHECK constraints and read back, and `:pg/check`
verdicts are compared with PostgreSQL's own on generated rows.

## License

MIT
