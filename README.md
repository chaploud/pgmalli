# pgmalli

[![ci](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml/badge.svg)](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml)
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

The examples use [docs/example/schema.sql](docs/example/schema.sql): `groups`, `users` and
`posts`, with an enum, identity and generated columns, and CHECKs. Add pgmalli to your
dependencies ([Installation](#installation)) and keep `resources` on the classpath:

```shell
$ psql -f docs/example/schema.sql
$ clojure -M -m pgmalli.main generate
wrote resources/pgmalli/public.edn (public: 3 tables, 0 views, 1 enums, 0 domains; 0 unrendered, 0 diagnostics)
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
;; => {:closed_at ["closed_at_matches_status"]}
```

A CHECK across columns too:

```clojure
(-> (m/explain :pg.public.posts/insert
               {:author_id 1 :title "Hello" :likes 5 :views 3}
               {:registry registry})
    (me/humanize))
;; => ["likes_within_views"]
```

Generate rows that satisfy every constraint:

```clojure
(m/validate :pg.public.users/insert
            (mg/generate :pg.public.users/insert {:registry registry :seed 42})
            {:registry registry})
;; => true
```

Tests can now build and check data against the real schema, with no database running.
Regenerate the file after each migration; [Keeping the files current](#keeping-the-files-current)
shows how CI catches a forgotten one.

## Installation

<!-- :test-doc-blocks/skip -->
```clojure
io.github.chaploud/pgmalli {:mvn/version "0.2.45"}
```

`pgmalli.main` reads `pgmalli.edn` from the working directory:

```clojure
{:schemas ["public"]                  ; the default
 :out-dir "resources/pgmalli"         ; the default; keep it on the classpath
 :db {:host "localhost" :port 5432 :db "app_dev" :user "app"}}
```

It reads the database with `psql`. Without `:db`, psql's own settings apply (`PGHOST`,
`PGDATABASE`, `PGUSER`, `PGPASSWORD`, `~/.pgpass`). On the JVM, a `:db` with `:jdbc-url`
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

For strings matching a regex CHECK, add `com.gfredericks/test.chuck` to your test alias;
without it they are drawn at random and filtered. pgmalli runs on babashka too, except for
JDBC.

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

[docs/types.md](docs/types.md) lists what every type and CHECK becomes.

## Rows read through next.jdbc

PostgreSQL's JDBC driver does not return the registry's types: a timestamp comes back as a
`java.sql.Timestamp`, an array as a `java.sql.Array`, a jsonb as a `PGobject`. `as-read`
gives the row as next.jdbc builds it, to validate results as they come back:

```clojure
(def user-row (pgmalli/as-read registry :pg.public/users))

(m/validate user-row
            {:id 1 :group_id 1 :email "ann@example.com" :nick nil :status "active"
             :closed_at nil :created_at (java.sql.Timestamp. 0)}
            {:registry registry})
;; => true
```

Its options match the builder your application uses. `read-options` gives them for a
next.jdbc builder:

```clojure
(pgmalli/read-options 'next.jdbc.result-set/as-maps)
;; => {:qualified? true}

(mu/keys (m/schema (pgmalli/as-read registry :pg.public/users {:qualified? true})
                   {:registry registry}))
;; => (:users/closed_at :users/created_at :users/email :users/group_id :users/id :users/nick :users/status)
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
;; => (3 3 3)

(m/validate (data/dataset-schema registry) sample {:registry registry})
;; => true
```

`write-dataset` keeps it as EDN, `read-dataset` reads it back, and `inserts` turns it into
HoneySQL INSERTs in an order the database accepts:

```clojure
(data/write-dataset "target/fixture.edn" sample)

(= sample (data/read-dataset "target/fixture.edn"))
;; => true

(map :insert-into (data/inserts registry sample))
;; => (:public.groups [{:overriding-value :system} :public.users] [{:overriding-value :system} :public.posts])
```

<!-- :test-doc-blocks/skip -->
```clojure
(require '[next.jdbc :as jdbc] 'next.jdbc.date-time '[honey.sql :as sql])

(doseq [q (data/inserts registry sample)]
  (jdbc/execute! db (sql/format q)))
```

`next.jdbc.date-time` lets the driver take the `java.time` values. [docs/datasets.md](docs/datasets.md)
covers `:except`, short tables and how foreign keys are filled.

## Checking HoneySQL queries

`h/check` reads HoneySQL query data against the registry, with no database. It returns the
problems PostgreSQL would also report:

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
| `:enum-literal` | an enum literal outside the enum, with `:allowed` |
| `:uncast-enum-string` | a string sent to an enum column as `varchar` |
| `:unread-clause` | a clause pgmalli does not read; the rest of the query is still checked |

`h/explain` says each problem as what is wrong and what to do:

```clojure
(h/explain (h/check registry {:select [:nik] :from [:users]}))
;; => ["unknown column nik; did you mean nick?"]
```

Every query of a project checks in one test, its failures explained:

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

What a subquery, a CTE or code in the query (a list, `(when x ...)`) sees, and how arguments are typed:
[docs/queries.md](docs/queries.md).

## Function schemas for queries

`h/query-schema` gives the function schema of a function that runs a query. Arguments are
HoneySQL's named parameters (`:?id`), keyed as in `sql/format`'s `:params` (`:id`):

```clojure
(def find-user-q {:select [:id :email] :from [:users] :where [:= :id :?id]})

(h/query-schema registry [:id] find-user-q)
;; => [:=> [:cat [:int #:pg{:identity :always, :type "bigint"}]] [:sequential [:map [:id [:int #:pg{:identity :always, :type "bigint"}]] [:email [:string {:max 120, :pg/type "character varying"}]]]]]
```

Put it on the function as a schema object over the registry. `malli.instrument` and
`malli.dev` then check calls, with no change to malli's default registry:

```clojure
(require '[malli.instrument :as mi])

(defn find-user
  {:malli/schema (m/schema (h/query-schema registry [:db :id] find-user-q {:result :one})
                           {:registry registry})}
  [db id]
  {:id id :email "ann@example.com"})

(mi/collect! {:ns (all-ns)})
(mi/instrument!)

(find-user nil 1)
;; => {:id 1, :email "ann@example.com"}

(try (find-user nil "1") (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))
;; => :malli.core/invalid-input

(mi/unstrument!)
```

`:db` is not in the query, so it is `:any`; with `{:result :one}` the function returns one
row or nil. A
problem `h/check` finds goes in the schema's `:pgmalli/problems` property, not an exception,
so a namespace still loads with a broken query:

```clojure
(m/properties (m/schema (h/query-schema registry [:id] {:select [:mail] :from [:users] :where [:= :id :?id]})))
;; => {:pgmalli/problems [{:kind :unknown-column, :column :mail, :did-you-mean [:email]}]}
```

> NOTE: the property makes the schema vector one element longer. Read its parts with
> `m/children`, not by position.

Instrumentation does not read the property. `h/schema-problems` gathers it from every var's
`:malli/schema`, for one test:

<!-- :test-doc-blocks/skip -->
```clojure
(deftest query-functions-agree-with-the-schema
  (let [problems (h/schema-problems)]
    (is (= [] problems) (h/explain problems))))
```

The rows and arguments take the options of `as-read`, plus `:write` and `:compare`: see
[docs/queries.md](docs/queries.md).

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

`clojure -M -m pgmalli.main check` exits 1 when a file differs or is missing, and prints what:

```shell
$ clojure -M -m pgmalli.main check
generated files are out of date; run generate and commit:
  public :pg.public/groups motto file nil db [:maybe :string]
```

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
contradict each other, a constraint left `NOT VALID`, a row-level INSERT trigger):

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
             :inet [:re "^[0-9.]+$"]}}
```

A constraint name replaces that CHECK; a type keyword (`:inet`) replaces the schema of every
column of that type. A `[:ref ...]` to a schema of your own needs a registry holding it:

```clojure
(require '[malli.registry :as mr])

(def app-registry
  (mr/composite-registry {:app/scores-consistent [:fn map?]} registry))

(m/validate :app/scores-consistent {} {:registry app-registry})
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

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

MIT
