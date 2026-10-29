# pgmalli

[![ci](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml)
[![Clojars](https://img.shields.io/clojars/v/io.github.chaploud/pgmalli.svg)](https://clojars.org/io.github.chaploud/pgmalli)

Your PostgreSQL schema as [malli](https://github.com/metosin/malli) schemas: validate rows,
generate test data and check HoneySQL queries, without a database at hand.

- [Quickstart](#quickstart)
- [Installation](#installation): generate the registry with psql or JDBC
- [Validating rows and writes](#validating-rows-and-writes): rows, INSERTs and UPDATEs
- [Rows read through next.jdbc](#rows-read-through-nextjdbc)
- [Generating test data](#generating-test-data): rows and whole datasets
- [Checking HoneySQL queries](#checking-honeysql-queries)
- [Function schemas for queries](#function-schemas-for-queries): instrument query functions
- [Keeping the files current](#keeping-the-files-current): stale files fail CI
- [Overrides](#overrides)
- [Supported versions](#supported-versions) and [Stability](#stability)
- Reference: [types and constraints](docs/types.md), [reading rows](docs/reading-rows.md),
  [queries](docs/queries.md), [datasets](docs/datasets.md)

## Quickstart

The examples use [docs/example/schema.sql](docs/example/schema.sql): `groups`, `users`,
`posts` and `events`, with an enum, identity and generated columns, a jsonb column, and CHECKs. Add pgmalli to your
dependencies ([Installation](#installation)) and keep `resources` on the classpath:

```shell
$ psql -f docs/example/schema.sql
$ clojure -M -m pgmalli.main generate
wrote resources/pgmalli/public.edn (public: 4 tables, 0 views, 1 enums, 0 domains; 0 unrendered, 0 diagnostics)
done: files written: 1; commit them, and run `clojure -M -m pgmalli.main check` in CI
```

Load the generated file as a registry and validate an INSERT:

```clojure
(require '[pgmalli.core :as pgmalli]
         '[malli.core :as m]
         '[malli.error :as me]
         '[malli.generator :as mg])

(def registry (pgmalli/registry "public"))

(m/validate :pg.public.users/insert
            {:group_id 1 :email "ann@example.com"}
            {:registry registry})
;; => true
```

The CHECKs are enforced. A closed user needs a `closed_at`:

```clojure
(-> (m/explain :pg.public.users/insert
               {:group_id 1 :email "ann@example.com" :status "closed" :closed_at nil}
               {:registry registry})
    (me/humanize))
;; => {:closed_at ["violates check constraint closed_at_matches_status: CHECK (status = 'active'::user_status AND closed_at IS NULL OR status = 'closed'::user_status AND closed_at IS NOT NULL)"]}
```

A CHECK across columns too:

```clojure
(-> (m/explain :pg.public.posts/insert
               {:author_id 1 :title "Hello" :likes 5 :views 3}
               {:registry registry})
    (me/humanize))
;; => ["violates check constraint likes_within_views: CHECK (likes <= views)"]
```

Generate rows that satisfy every constraint:

```clojure
(m/validate :pg.public.users/insert
            (mg/generate :pg.public.users/insert {:registry registry :seed 42})
            {:registry registry})
;; => true
```

Regenerate the file after each migration; [Keeping the files current](#keeping-the-files-current)
shows how CI catches a forgotten one.

## Installation

<!-- :test-doc-blocks/skip -->
```clojure
io.github.chaploud/pgmalli {:mvn/version "0.2.45"}
```

`pgmalli.main` reads `pgmalli.edn` from the working directory, or the file given after the
command (`generate ci.edn`):

```clojure
{:schemas ["public"]                  ; the default
 :out-dir "resources/pgmalli"         ; the default; keep it on the classpath
 :db {:host "localhost" :port 5432 :db "app_dev" :user "app"}}
```

It reads the database with `psql`. Without `:db`, psql's own settings apply (`PGHOST`,
`PGDATABASE`, `PGUSER`, `PGPASSWORD`, `~/.pgpass`). `:db` takes `:host`, `:port`, `:db`, `:user`,
`:password`, `:sslmode` and `:psql`, the psql binary to run. On the JVM, a `:db` with `:jdbc-url`
reads through PostgreSQL's JDBC driver instead, no psql needed:

```clojure
{:db {:jdbc-url "jdbc:postgresql://localhost/app_dev" :user "app" :password "secret"}}
```

`:db {:datasource ds}` reads through a `javax.sql.DataSource`. Generating needs PostgreSQL 16 or later;
applications only need the generated files.

The same from Clojure:

<!-- :test-doc-blocks/skip -->
```clojure
(require '[pgmalli.generate :as generate])

(generate/generate! {:schemas ["public"]})
;; => {"public" "resources/pgmalli/public.edn"}
```

On babashka, put the same coordinate in `bb.edn`'s `:deps` and run `bb -m pgmalli.main generate`.
Everything but JDBC works there.

With `com.gfredericks/test.chuck` on the classpath, strings of a regex CHECK generate from the
regex ([docs/types.md](docs/types.md#check-constraints)).

## Validating rows and writes

For each table the registry holds three schemas: the row, what an INSERT may carry, and
what an UPDATE may set.

```clojure
(m/validate :pg.public/users
            {:id 1 :group_id 1 :email "ann@example.com" :nick nil :status "active"
             :closed_at nil :created_at (java.time.Instant/parse "2026-01-01T00:00:00Z")}
            {:registry registry})
;; => true
```

An INSERT schema is closed. It leaves out identity ALWAYS and generated columns, and makes
defaulted and nullable columns optional:

```clojure
(-> (m/explain :pg.public.posts/insert
               {:id 1 :author_id 1 :title "Hello" :title_upper "HELLO"}
               {:registry registry})
    (me/humanize))
;; => {:id ["disallowed key"], :title_upper ["disallowed key"]}
```

An UPDATE schema has every column optional, and no NOT NULL column takes nil:

```clojure
(m/validate :pg.public.users/update {:nick "ann"} {:registry registry})
;; => true

(m/validate :pg.public.users/update {:email nil} {:registry registry})
;; => false
```

Enum values are strings:

```clojure
(m/validate :pg.public/user_status "closed" {:registry registry})
;; => true

(m/validate :pg.public/user_status "deleted" {:registry registry})
;; => false
```

`columns` gives the `[:map ...]` of a table without its table-level CHECKs, which is what
`malli.util` takes:

```clojure
(require '[malli.util :as mu])

(m/validate (mu/select-keys (pgmalli/columns registry :pg.public/users) [:email])
            {:email "ann@example.com"}
            {:registry registry})
;; => true
```

These messages are for developers: they name tables, columns, types and the CHECK's SQL.
Before showing an error to the people using your application, word it yourself. A CHECK's
schema names its constraint in `:pg/constraint`, which humanize's `:wrap` can read:

```clojure
(def user-messages {"likes_within_views" "A post cannot have more likes than views."})

(defn for-people [{:keys [schema message]}]
  (if-let [c (:pg/constraint (m/properties schema))]
    (get user-messages c "Please check what you entered.")
    message))

(-> (m/explain :pg.public.posts/insert {:author_id 1 :title "Hello" :likes 5 :views 3} {:registry registry})
    (me/humanize {:wrap for-people}))
;; => ["A post cannot have more likes than views."]
```

[docs/types.md](docs/types.md) lists what every type and CHECK becomes.

## Rows read through next.jdbc

PostgreSQL's JDBC driver does not return the registry's types: a timestamp comes back as a
`java.sql.Timestamp`, an array as a `java.sql.Array`, a jsonb as a `PGobject`. `as-read`
gives the row as next.jdbc builds it, to validate results as they come back:

```clojure
(def user-row (pgmalli/as-read registry :pg.public/users))

(m/validate user-row
            #:users{:id 1 :group_id 1 :email "ann@example.com" :nick nil :status "active"
                    :closed_at nil :created_at (java.sql.Timestamp. 0)}
            {:registry registry})
;; => true
```

By default the keys are those of next.jdbc's default builder, `as-maps`. Another builder's
options come from `read-options`:

```clojure
(pgmalli/read-options 'next.jdbc.result-set/as-unqualified-maps)
;; => {:qualified? false}

(mu/keys (m/schema (pgmalli/as-read registry :pg.public/users {:qualified? false})
                   {:registry registry}))
;; => (:closed_at :created_at :email :group_id :id :nick :status)
```

There are three ways to check a row read from the database:

| way | for |
|---|---|
| `as-read` | the rows of one table, as next.jdbc returns them (recommended) |
| `h/row-schema` ([queries](docs/queries.md#the-row-a-query-returns)) | the rows of a query: a join, some columns |
| `m/decode` with `pgmalli/transformer` ([reading rows](docs/reading-rows.md#decoding-instead)) | rows turned into the registry's types first |

[docs/reading-rows.md](docs/reading-rows.md) has every option: `next.jdbc.date-time`,
`next.jdbc.optional`, and the types a `ReadableColumn` extension of yours returns.

## Generating test data

`mg/generate` gives a row that satisfies the table's constraints:

```clojure
(m/validate :pg.public.posts/insert
            (mg/generate :pg.public.posts/insert {:registry registry :seed 1})
            {:registry registry})
;; => true
```

A dataset is several tables at once, foreign keys pointing at generated rows:

```clojure
(require '[pgmalli.data :as data]
         '[clojure.test.check.generators :as gen])

(def sample (gen/generate (data/dataset-generator registry {:rows 3}) 30 42))

(map count (vals sample))
;; => (3 3 3 3)

(m/validate (data/dataset-schema registry) sample {:registry registry})
;; => true
```

`inserts` turns it into HoneySQL INSERTs in an order the database accepts:

```clojure
(map :insert-into (data/inserts registry sample))
;; => (:public.groups [{:overriding-value :system} :public.users] [{:overriding-value :system} :public.events] [{:overriding-value :system} :public.posts])
```

[docs/datasets.md](docs/datasets.md) covers running them, keeping a dataset as a fixture
file, `:except`, short tables and how foreign keys are filled.

## Checking HoneySQL queries

`h/check` reads HoneySQL query data against the registry, with no database. It returns the
problems PostgreSQL would report, and a few it runs but that can match no row ([docs/queries.md](docs/queries.md#reported-though-postgresql-runs-them)):

```clojure
(require '[pgmalli.honeysql :as h])

(h/check registry {:select [:id :email] :from [:users] :where [:= :status [:cast "active" :user_status]]})
;; => []

(h/check registry {:select [:id :mail] :from [:users] :where [:= :status [:cast "gone" :user_status]]})
;; => [{:kind :unknown-column, :column :mail, :did-you-mean [:email]} {:kind :enum-literal, :column :status, :value "gone", :allowed ["active" "closed"]}]
```

HoneySQL sends a string as a parameter, and PostgreSQL's JDBC driver sends it as `varchar`,
which no enum column takes. Cast it, or tell `h/check` your connection has
`stringtype=unspecified`:

```clojure
(h/check registry {:select [:id] :from [:users] :where [:= :status "active"]})
;; => [{:kind :uncast-enum-string, :column :status, :value "active", :type "user_status"}]

(h/check registry {:select [:id] :from [:users] :where [:= :status "active"]} {:stringtype :unspecified})
;; => []
```

An INSERT must carry the columns its insert schema requires:

```clojure
(h/check registry {:insert-into :users :values [{:email "ann@example.com"}]})
;; => [{:kind :missing-required-column, :table "public.users", :column :group_id}]
```

The problems it reports:

| `:kind` | for |
|---|---|
| `:unknown-table` | a table the registry lacks |
| `:unknown-column` | a column no table in scope has |
| `:ambiguous-column` | an unqualified column of two tables, with `:candidates` |
| `:missing-required-column` | an INSERT lacking a required column |
| `:not-null-violation` | NULL written into a NOT NULL column |
| `:read-only-column` | a value written into a generated or identity ALWAYS column |
| `:values-arity` | a `:values` row not as long as `:columns` |
| `:enum-literal` | a literal outside an enum type's labels or a CHECK's list, with `:allowed` |
| `:uncast-enum-string` | a string, literal or argument, sent as `varchar` to a column of an enum type; not under `:stringtype :unspecified` or `:format {:inline true}` |
| `:unread-clause` | a clause pgmalli does not read; the rest of the query is still checked |
| `:missing-arg` | `h/query-schema` only: a query argument the function's args lack |

`h/explain` says each problem as what is wrong and what to do:

```clojure
(h/explain (h/check registry {:select [:nik] :from [:users]}))
;; => ["unknown column nik; did you mean nick?"]
```

Check every query of a project in one test:

```clojure
(require '[clojure.test :refer [deftest is]])

(def queries
  {:active-users {:select [:id :email] :from [:users] :where [:= :status [:cast "active" :user_status]]}
   :user-posts {:select [:p.title] :from [[:posts :p]] :join [[:users :u] [:= :u.id :p.author_id]]}})

(deftest queries-agree-with-the-schema
  (doseq [[k q] queries]
    (let [problems (h/check registry q)]
      (is (= [] problems) (str k ": " (h/explain problems))))))
```

[docs/queries.md](docs/queries.md) covers subqueries, CTEs, code in a query and argument types.

## Function schemas for queries

`h/query-schema` gives the function schema of a function that runs a query. Arguments are
HoneySQL's named parameters (`:?id`), keyed as in `sql/format`'s `:params` (`:id`):

```clojure
(def find-user-q {:select [:id :email] :from [:users] :where [:= :id :?id]})

(h/query-schema registry [:id] find-user-q)
;; => [:=> [:cat [:int #:pg{:type "bigint"}]] [:sequential [:map [:users/id [:int #:pg{:identity :always, :type "bigint"}]] [:users/email [:string {:max 120, :pg/max-length 120, :pg/type "character varying"}]]]]]
```

Put it on the function as a schema object over the registry. `malli.instrument` and
`malli.dev` then check calls, with no change to malli's default registry:

```clojure
(require '[malli.instrument :as mi])

(defn find-user
  {:malli/schema (m/schema (h/query-schema registry [:db :id] find-user-q {:result :one})
                           {:registry registry})}
  [db id]
  #:users{:id id :email "ann@example.com"})

(mi/collect! {:ns (all-ns)})
(mi/instrument!)

(find-user nil 1)
;; => #:users{:id 1, :email "ann@example.com"}

(try (find-user nil "1") (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
;; => :malli.core/invalid-input

(mi/unstrument!)
```

`:db` is not in the query, so it is `:any`; with `{:result :one}` the function returns one
row or nil. A problem `h/check` finds goes in the schema's `:pgmalli/problems` property, not
an exception, so a namespace still loads with a broken query; `h/schema-problems` gathers them
for one test ([docs/queries.md](docs/queries.md#problems-in-function-schemas)).

`pgmalli/portable` gives a named schema as data that malli's default registry and
`malli.experimental.time` read: pgmalli's types as malli's, the CHECKs only pgmalli evaluates
left out. Use it where a schema cannot carry the registry, such as `:malli/schema` written as
data:

```clojure
(m/validate (pgmalli/portable registry :pg.public/user_status) "closed")
;; => true
```

`pgmalli/describe` reads a schema back as PostgreSQL types, to see what a function takes and
returns:

```clojure
(pgmalli/describe (h/query-schema registry [:id] find-user-q {:result :one}))
;; => {:args ["bigint"], :returns [:maybe #:users{:id "bigint", :email "varchar(120)"}]}
```

The options of `h/check`, `h/arg-types`, `h/params-schema`, `h/row-schema` and `h/query-schema` are in
[docs/queries.md](docs/queries.md#options).

## Keeping the files current

`generate/check` compares the files with the database. In CI:

<!-- :test-doc-blocks/skip -->
```clojure
(is (nil? (:stale (generate/check {:schemas ["public"]}))))
```

Without a database, `{:db? false}` reads the files alone; `:missing` lists the schemas with
no file:

<!-- :test-doc-blocks/skip -->
```clojure
(is (nil? (:missing (generate/check {:schemas ["public"]} {:db? false}))))
```

`clojure -M -m pgmalli.main check` prints each difference, then a line with the command to
run:

```shell
$ clojure -M -m pgmalli.main check
public :pg.public/groups :motto
  file: nil
  db:   [:maybe [:string {:pg/type "text"}]]
stale: public (differences: 1); run `clojure -M -m pgmalli.main generate` and commit the files
```

| exit | when |
|---|---|
| 0 | the files match the database |
| 1 | a file differs or is missing: run `generate` |
| 2 | a usage, config or other error |
| 3 | the database or psql is unavailable |

Findings go to stdout; usage, warnings and errors go to stderr, one line each
(`PGMALLI_DEBUG=1` adds the stack trace). `check --edn` prints `generate/check`'s map instead.
A config path that does not exist is an error; only with no path does a missing `pgmalli.edn`
mean the defaults.

`generate/diff` gives the same differences between two generated files, the effect of a
migration:

```clojure
(require '[pgmalli.generate :as generate])

(let [before (pgmalli/generated "public")
      after (update-in before [:registry :pg.public/groups] conj [:motto [:maybe :string]])]
  (generate/diff before after))
;; => [{:name :pg.public/groups, :column :motto, :file nil, :db [:maybe :string]}]
```

The file also lists what pgmalli could not express (`:unrendered`) and what deserves a look
(`:diagnostics`: a partitioned table with no partition, `CHECK (false)`, CHECKs that
contradict each other, a constraint left `NOT VALID`, a row-level INSERT trigger).
[docs/types.md](docs/types.md#the-generated-file) describes the file:

```clojure
(select-keys (pgmalli/generated "public") [:unrendered :diagnostics])
;; => {:unrendered [], :diagnostics []}
```

## Overrides

A CHECK pgmalli cannot express is listed in `:unrendered` and left out of the schema. Give
it a schema of your own, or skip it with a reason, in `pgmalli.edn`:

<!-- :test-doc-blocks/skip -->
```clojure
{:overrides {"chk_legacy_flag" {:skip "dropped with the column in 2027"}
             "chk_scores" [:ref :app/scores-consistent]
             :inet [:re "^(\\d{1,3}\\.){3}\\d{1,3}(/\\d{1,2})?$"]}}
```

A constraint name replaces that CHECK; a type keyword (`:inet`) replaces the schema of every
column of that type. A `[:ref ...]` to a schema of your own needs a registry holding it:

```clojure
(require '[malli.registry :as mr])

(def app-registry
  (mr/composite-registry
   {:app/scores-consistent [:fn (fn [{:keys [min_score max_score]}] (<= min_score max_score))]}
   registry))

(m/validate :app/scores-consistent {:min_score 1 :max_score 3} {:registry app-registry})
;; => true
```

## Supported versions

| | |
|---|---|
| PostgreSQL | 16 and later |
| HoneySQL | 2.4.1066 and later |
| next.jdbc, PostgreSQL's JDBC driver | 1.3 and later, 42.7 and later |
| malli | 0.20 and later |
| Clojure, babashka | 1.11 and later; babashka for everything but JDBC |

pgmalli depends on malli, test.check, camel-snake-kebab and data.json. HoneySQL, next.jdbc
and the JDBC driver are yours.

## Stability

Changing any of these is breaking; before 1.0 it bumps the minor version:

1. the keys of `pgmalli.edn`
2. the generated file and the registry names
3. the kinds of `:unrendered` entries and of `check`'s problems
4. the tags `write-dataset` writes (`#pgmalli/instant` and the others)
5. the public functions of `pgmalli.core`, `pgmalli.generate`, `pgmalli.data` and
   `pgmalli.honeysql`, and their options
6. the `:pgmalli/error` kinds of the errors pgmalli throws
7. the command line: the `generate` and `check` commands, `check --edn`'s output and the exit
   codes 0 to 3

| `:pgmalli/error` | when |
|---|---|
| `:pgmalli.error/invalid-config` | `pgmalli.edn` or a `:db` map pgmalli cannot use |
| `:pgmalli.error/invalid-options` | an option or argument it does not take, with the one meant |
| `:pgmalli.error/unknown-schema` | a registry name the registry lacks, with the one meant |
| `:pgmalli.error/not-a-registry` | something passed as a registry that is none |
| `:pgmalli.error/not-query-data` | a query that is no HoneySQL data |
| `:pgmalli.error/not-generated` | a generated file missing from the classpath |
| `:pgmalli.error/invalid-dataset` | a dataset naming tables or columns the registry lacks |
| `:pgmalli.error/reference-cycle` | tables referencing each other in a cycle |
| `:pgmalli.error/cannot-generate` | a column of a type pgmalli makes no value of |
| `:pgmalli.error/db-unavailable`, `psql-missing` | the database, or psql, out of reach |
| `:pgmalli.error/fault` | pgmalli's own fault (please report it), or psql failing to read the catalog: a PostgreSQL before 16, or a user without access to pg_catalog |

Each error's ex-data also carries `:cognitect.anomalies/category`, and `:given`, `:known` and
`:suggest` where they apply.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT
