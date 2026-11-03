# pgmalli

[![ci](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml)
[![Clojars](https://img.shields.io/clojars/v/io.github.chaploud/pgmalli.svg)](https://clojars.org/io.github.chaploud/pgmalli)

Your PostgreSQL schema as [malli](https://github.com/metosin/malli) schemas: validate rows,
generate test data and check HoneySQL queries, without a database at hand.

- [Quickstart](#quickstart)
- [Installation](#installation): generate the registry with psql or JDBC
- [Validating rows and writes](#validating-rows-and-writes): rows, INSERTs and UPDATEs
- [Rows read through next.jdbc](#rows-read-through-nextjdbc)
- [Generating test data](#generating-test-data)
- [Checking HoneySQL queries](#checking-honeysql-queries)
- [Function schemas for queries](#function-schemas-for-queries): instrument query functions
- [Keeping the files current](#keeping-the-files-current): stale files fail CI
- [Overrides](#overrides)
- [Supported versions](#supported-versions), [Limits](#limits) and [Stability](#stability)
- Reference: [types and constraints](docs/types.md), [reading rows](docs/reading-rows.md),
  [queries](docs/queries.md)

## Quickstart

The examples use [docs/example/schema.sql](docs/example/schema.sql): `groups`, `users`,
`posts` and `events`, with an enum, identity and generated columns, a jsonb column, and
CHECKs. Add pgmalli to your dependencies ([Installation](#installation)), and add
`"resources"` to `:paths` in `deps.edn` (and in `bb.edn` for babashka): the generated files go
in `resources/pgmalli`, and the default `:paths` is `["src"]` alone.

To try it, create an empty database, load `docs/example/schema.sql` into it from a clone of
this repository, then run `generate` in your project. pgmalli reads the database with `psql`,
so psql's own settings apply (`PGHOST`, `PGDATABASE`, `PGUSER` ...), or `:db` in `pgmalli.edn`
([Installation](#installation)):

```shell
$ createdb pgmalli_example
$ psql -d pgmalli_example -f path/to/pgmalli/docs/example/schema.sql
$ export PGDATABASE=pgmalli_example
$ clojure -M -m pgmalli.main generate
pgmalli: no pgmalli.edn here; using the defaults (schema public, files in resources/pgmalli)
wrote resources/pgmalli/public.edn (schema public; tables: 4, views: 0, enums: 1, domains: 0; unrendered: 0, diagnostics: 0)
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
io.github.chaploud/pgmalli {:mvn/version "0.3.3"}
```

`pgmalli.main` reads `pgmalli.edn` from the working directory, or the file given after the
command (`generate ci.edn`):

```clojure
{:schemas ["public"]                  ; the default
 :out-dir "resources/pgmalli"         ; the default; keep it on the classpath
 :db {:host "localhost" :port 5432 :dbname "app_dev" :user "app"}}
```

It reads the database with `psql`. Without `:db`, psql's own settings apply (`PGHOST`,
`PGDATABASE`, `PGUSER`, `PGPASSWORD`, `~/.pgpass`). `:db` takes `:host`, `:port`, `:dbname`,
`:user`, `:password`, `:sslmode` and `:psql`, the psql binary to run; a next.jdbc db-spec is one
without its `:dbtype`. On the JVM, a `:db` with `:jdbc-url` reads through pgjdbc (PostgreSQL's
JDBC driver) instead, no psql needed:

```clojure
{:db {:jdbc-url "jdbc:postgresql://localhost/app_dev" :user "app" :password "secret"}}
```

From Clojure:

<!-- :test-doc-blocks/skip -->
```clojure
(require '[pgmalli.generate :as generate])

(generate/generate! {:schemas ["public"]})
;; => {"public" "resources/pgmalli/public.edn"}
```

`generate!` also takes `:db {:datasource ds}`, a `javax.sql.DataSource`. Generating needs a
[supported PostgreSQL](#supported-versions). Applications need only the generated files.

On babashka, put the same coordinate in `bb.edn`'s `:deps` and run `bb -m pgmalli.main generate`.
Everything but JDBC works there.

To generate rows of a column with a regex CHECK (`~` or `LIKE`) on the JVM, add test.chuck to
your test alias:

<!-- :test-doc-blocks/skip -->
```clojure
com.gfredericks/test.chuck {:mvn/version "0.2.15"}
```

Without it, `mg/generate` usually fails on such a column with
`:malli.generator/such-that-failure`. babashka has no test.chuck: there the strings are drawn
and filtered ([docs/types.md](docs/types.md#check-constraints)).

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

An UPDATE schema takes the columns an INSERT may carry, each optional, and no NOT NULL column
takes nil. It leaves out the table's CHECKs: they hold on the updated row, which the columns
sent do not show.

```clojure
(m/validate :pg.public.users/update {:nick "ann"} {:registry registry})
;; => true

(m/validate :pg.public.users/update {:email nil} {:registry registry})
;; => false
```

These schemas hold the database's types. For a row your code builds and HoneySQL sends,
`h/values-schema` and `h/set-schema` type each column as an argument, with what your
application sends ([docs/queries.md](docs/queries.md#a-row-written-into-a-table)).

A `numeric` column takes a BigDecimal (`10M`); a long such as `10` fails. Enum values are
strings:

```clojure
(m/validate :pg.public/user_status "closed" {:registry registry})
;; => true

(m/validate :pg.public/user_status "deleted" {:registry registry})
;; => false
```

humanize's messages are for developers: they name tables, columns, types and the CHECK's SQL.
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

pgjdbc does not return the registry's types. pgmalli types rows as one reading makes them:
timestamps as `Instant`s, dates as `LocalDate`s, times as `LocalTime`s, arrays as vectors, json
and jsonb parsed with keyword keys, enum values as strings, every other type as pgjdbc returns
it; and arguments as one sending sends them: pgjdbc's `setObject` (a `String` as `varchar`),
`next.jdbc.date-time` (an `Instant`, a `LocalDate`, a `LocalDateTime` untyped), a keyword's
text untyped, a map and a vector as `jsonb`. Install the extension of
[docs/reading-rows.md](docs/reading-rows.md#the-one-reading-and-sending) in your application; `as-read`
gives the row as next.jdbc then builds it, to validate results as they come back:

```clojure
(def user-row (pgmalli/as-read registry :pg.public/users))

(m/validate user-row
            {:id 1 :group_id 1 :email "ann@example.com" :nick nil :status "active"
             :closed_at nil :created_at (java.time.Instant/EPOCH)}
            {:registry registry})
;; => true
```

pgmalli types rows as next.jdbc's `as-unqualified-maps` builds them: keyed by the columns'
names (`:group_id`), a NULL column's key kept. Read rows with it:
`(jdbc/execute! ds sqlvec {:builder-fn next.jdbc.result-set/as-unqualified-maps})`. Another
spelling of the keys is your application's to convert where it takes the row
([docs/reading-rows.md](docs/reading-rows.md#the-builder)).

There are two ways to check a row read from the database:

| way | for |
|---|---|
| `as-read` | the rows of one table, as next.jdbc returns them (recommended) |
| `(:row (h/analyze ...))` ([queries](docs/queries.md#the-row-a-query-returns)) | the rows of a query: a join, some columns |

[docs/reading-rows.md](docs/reading-rows.md) has the extension and what each type comes back as.

## Generating test data

`mg/generate` gives a row that satisfies the table's constraints:

```clojure
(m/validate :pg.public.posts/insert
            (mg/generate :pg.public.posts/insert {:registry registry :seed 1})
            {:registry registry})
;; => true
```

## Checking HoneySQL queries

`h/analyze` reads HoneySQL query data against the registry, with no database. It returns the
types of the query's arguments (`:args`), the row it returns (`:row`), how many rows where the
data tells (`:rows`), the problems PostgreSQL would report (`:problems`) and the tables it reads
and writes (`:tables`):

```clojure
(require '[pgmalli.honeysql :as h])

(h/analyze registry {:select [:email] :from [:users] :where [:= :id :?id]})
;; => {:args {:id [:int #:pg{:type "bigint"}]}, :row [:map [:email [:string {:max 120, :pg/max-length 120, :pg/type "character varying"}]]], :rows {:min 0, :max 1}, :problems [], :tables {:reads #{"public.users"}, :writes #{}}}
```

`:problems` also reports a few queries that PostgreSQL runs but that can match no row
([docs/queries.md](docs/queries.md#stricter-than-postgresql)):

```clojure

(:problems (h/analyze registry {:select [:id :email] :from [:users] :where [:= :status [:cast "active" :user_status]]}))
;; => []

(:problems (h/analyze registry {:select [:id :mail] :from [:users] :where [:= :status [:cast "gone" :user_status]]}))
;; => [{:kind :unknown-column, :column :mail, :did-you-mean [:email]} {:kind :enum-literal, :column :status, :value "gone", :allowed ["active" "closed"]}]
```

HoneySQL sends a string as a parameter, and pgjdbc sends it as `varchar`, which no enum column
takes. Cast it; an argument takes a keyword label, which goes untyped
([what your application sends](docs/queries.md#what-your-application-sends)):

```clojure
(:problems (h/analyze registry {:select [:id] :from [:users] :where [:= :status "active"]}))
;; => [{:kind :uncast-string, :column :status, :value "active", :type "user_status"}]
```

An INSERT must carry the columns its insert schema requires:

```clojure
(:problems (h/analyze registry {:insert-into :users :values [{:email "ann@example.com"}]}))
;; => [{:kind :missing-required-column, :table "public.users", :column :group_id}]
```

The problems it reports:

| `:kind` | for |
|---|---|
| `:unknown-table` | a table the registry lacks; `:did-you-mean` lists a table of that name in another schema first |
| `:unknown-column` | a column no table in scope has, or a qualifier no table or alias in scope has (`:qualifier`) |
| `:ambiguous-column` | an unqualified column of two tables, with `:candidates` |
| `:missing-required-column` | an INSERT lacking a required column |
| `:not-null-violation` | NULL written into a NOT NULL column |
| `:read-only-column` | a value written into a generated or identity ALWAYS column |
| `:values-arity` | a `:values` row not as long as `:columns` |
| `:enum-literal` | a literal outside an enum type's labels, or outside a CHECK's list when written into the column or compared with `=`, `IN` or `IS NOT DISTINCT FROM`; with `:allowed` |
| `:uncast-string` | a string literal (or `[:lift "s"]`), which pgjdbc sends as `varchar`, where the column's type takes none (an enum, a number, a date ...); not under `:format {:inline true}`. A named argument is typed as `:args` types it |
| `:unread-clause` | a clause pgmalli does not read, with the one meant when HoneySQL knows none of that name; the rest of the query is still checked |
| `:missing-arg` | `h/query-schema` only: a query argument the function's args lack |

`h/humanize` says each problem as what is wrong and what to do:

```clojure
(h/humanize (:problems (h/analyze registry {:select [:nik] :from [:users]})))
;; => ["unknown column nik; did you mean: nick"]
```

Check every query of a project in one test:

```clojure
(require '[clojure.test :refer [deftest is]])

(def queries
  {:active-users {:select [:id :email] :from [:users] :where [:= :status [:cast "active" :user_status]]}
   :user-posts {:select [:p.title] :from [[:posts :p]] :join [[:users :u] [:= :u.id :p.author_id]]}})

(deftest queries-agree-with-the-schema
  (doseq [[k q] queries]
    (let [problems (:problems (h/analyze registry q))]
      (is (= [] problems) (str k ": " (h/humanize problems))))))
```

[docs/queries.md](docs/queries.md) covers subqueries, CTEs, code in a query, the types of the
arguments and of the row, a row written into a table (`h/values-schema`, `h/set-schema`),
the tables a query reads and writes (`:tables`), the `:params` map of `sql/format`, the
options, and what pgmalli checks more strictly than PostgreSQL or not at all.

## Function schemas for queries

`h/query-schema` gives the function schema of a function that runs a query. Arguments are
HoneySQL's named parameters (`:?id`), keyed as in `sql/format`'s `:params` (`:id`):

```clojure
(def find-user-q {:select [:id :email] :from [:users] :where [:= :id :?id]})

(h/query-schema registry [:id] find-user-q)
;; => [:=> #:pgmalli{:problems []} [:cat [:int #:pg{:type "bigint"}]] [:sequential {:max 1} [:map [:id [:int #:pg{:identity :always, :type "bigint"}]] [:email [:string {:max 120, :pg/max-length 120, :pg/type "character varying"}]]]]]
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

`:db` is not in the query, so its schema is `:any`. A problem `h/analyze` finds goes into the
schema's `:pgmalli/problems` property instead of an exception, so a namespace with a broken
query still loads. `h/schema-problems` gathers them for one test
([docs/queries.md](docs/queries.md#problems-in-function-schemas)).

`h/query-schema` is built of what `h/analyze` gives; for a schema of your own, build it of
those parts ([docs/queries.md](docs/queries.md#function-schemas)).

`pgmalli/describe` reads a schema back as PostgreSQL types, to see what a function takes and
returns:

```clojure
(pgmalli/describe (h/query-schema registry [:id] find-user-q {:result :one}))
;; => {:args ["bigint"], :returns [:maybe {:id "bigint", :email "varchar(120)"}]}
```

The options of `pgmalli.honeysql`'s functions are in [docs/queries.md](docs/queries.md#options).

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
pgmalli: no pgmalli.edn here; using the defaults (schema public, files in resources/pgmalli)
public :pg.public/groups :motto
  file: nil
  db:   [:maybe [:string {:pg/type "text"}]]
stale: public (differences: 1); run `clojure -M -m pgmalli.main generate` and commit the files
```

| exit | when |
|---|---|
| 0 | success; for `check`, the files match the database |
| 1 | a file differs or is missing: run `generate` |
| 2 | a usage, config or other error |
| 3 | the database or psql is unavailable |

Findings go to stdout; warnings and errors go to stderr, one line each (`PGMALLI_DEBUG=1` adds
the stack trace), followed by the usage for a usage error. `--help` prints the usage to stdout.
`check --edn` prints `generate/check`'s map instead of the differences. A config path that
does not exist is an error; only with no path does a missing `pgmalli.edn` mean the defaults.

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
(`:diagnostics`, such as a partitioned table with no partition, `CHECK (false)` or a
row-level INSERT trigger). [docs/types.md](docs/types.md#the-generated-file) describes the
file and lists them all:

```clojure
(select-keys (pgmalli/generated "public") [:unrendered :diagnostics])
;; => {:unrendered [], :diagnostics []}
```

## Overrides

`:overrides` in `pgmalli.edn` replaces what pgmalli generates. A keyword `:schema.table/column`
replaces the schema of that one column's value, its type's schema with it (an enum's, a
`varchar(n)`'s length), such as an `inet` column, which is otherwise `:any`. A constraint name
with `{:skip reason}` leaves that CHECK out; use it for the CHECKs listed in `:unrendered`. To
give a column's CHECK a schema of your own, skip it and override the column; a CHECK across
columns is kept or skipped whole:

<!-- :test-doc-blocks/skip -->
```clojure
{:overrides {"chk_legacy_flag" {:skip "dropped with the column in 2027"}
             :public.hosts/ip [:re "^(\\d{1,3}\\.){3}\\d{1,3}(/\\d{1,2})?$"]
             :public.ai_jobs/input [:map [:prompt :string] [:max_tokens {:optional true} :int]]}}
```

pgmalli still adds what the database says of the column: `[:maybe ...]` unless it is NOT
NULL, its CHECKs, its default and `:pg/type`. The schema holds where values are read
(`as-read`, `h/analyze`'s `:row`) and written (`h/analyze`'s `:args`, `h/values-schema`, `h/set-schema`),
but for a type the driver reads as an object of its own: an `inet`, read as a
PGobject (an `hstore` as a `java.util.Map`), stays that object where it is read; the
`:public.hosts/ip` override above holds for what is written. A json or jsonb column is read
parsed ([the one reading](docs/reading-rows.md#the-one-reading-and-sending)), so its shape holds for what is
read, and an argument to a jsonb column takes it for a map and a vector, which go as `jsonb`; a
json column's argument is the driver's object. `generate` checks every key against
the schemas generated: a column and a constraint name must be theirs, `{:skip reason}` goes
under a constraint name and nothing else does, and a value must be a malli schema (a qualified
name passes, as your registry's). An error names the key meant; a type key (`:inet`) names the
columns of the type to override instead.

A `[:ref ...]` to your own schema needs a registry holding it:

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
| PostgreSQL | 16, 17 and 18, to generate; a newer one is a warning (its new functions and types are not typed), an older one an error |
| Clojure | 1.11.4 and later |
| JDK | 25 (what CI runs) |
| malli | 0.20.0 and later |
| HoneySQL | 2.4.1066 and later |
| next.jdbc | 1.3.1048 and later |
| pgjdbc | 42.7.7 and later |
| babashka | the latest (what CI runs), for everything but JDBC |

CI tests the oldest versions listed here.

pgmalli depends on malli, test.check and data.json. HoneySQL, next.jdbc
and pgjdbc are yours to add.

## Limits

- The registry is the schema at generate time; nothing reads the database at run time
  ([Keeping the files current](#keeping-the-files-current)).
- PRIMARY KEY, UNIQUE and foreign keys are not checked: a row schema holds for one row.
- Triggers, EXCLUDE constraints and policies are not enforced
  ([docs/types.md](docs/types.md#what-is-read)).
- `h/analyze` reads a query as data: of a query that code builds at run time, it checks only the
  parts written as data ([docs/queries.md](docs/queries.md#not-checked)).
- A CHECK whose result depends on what pgmalli cannot know passes
  ([docs/types.md](docs/types.md#how-pgcheck-evaluates)).

## Stability

Changing any of these is breaking; before 1.0 it bumps the minor version:

1. the keys of `pgmalli.edn`
2. the generated file and the registry names
3. the kinds of `:unrendered` entries and of `h/analyze`'s problems
4. the public functions of `pgmalli.core`, `pgmalli.generate` and `pgmalli.honeysql`, and
   their options
5. the `:pgmalli/error` kinds of the errors pgmalli throws
6. the command line: the `generate` and `check` commands, `check --edn`'s output and the exit
   codes 0 to 3

| `:pgmalli/error` | when |
|---|---|
| `:pgmalli.error/invalid-config` | `pgmalli.edn` or a `:db` map pgmalli cannot use, or a schema the database lacks |
| `:pgmalli.error/invalid-options` | an option or argument it does not take, with the one meant |
| `:pgmalli.error/unknown-schema` | a name the registry lacks, or one of the wrong kind (an insert schema where a row's goes), with the one meant |
| `:pgmalli.error/not-a-registry` | something passed as a registry that is none |
| `:pgmalli.error/not-query-data` | a query that is no HoneySQL data |
| `:pgmalli.error/not-generated` | a generated file missing from the classpath |
| `:pgmalli.error/cannot-generate` | a column of a type pgmalli makes no value of |
| `:pgmalli.error/db-unavailable`, `psql-missing` | the database, or psql, out of reach |
| `:pgmalli.error/fault` | pgmalli's own fault (please report it), or reading the catalog failing: an [unsupported PostgreSQL](#supported-versions), or a user without access to pg_catalog |

Each error's ex-data also carries `:cognitect.anomalies/category`, and `:given`, `:known` and
`:did-you-mean` (the names probably meant, as a problem of `h/analyze` names them) where they apply.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md). What changed in each release is in
[CHANGELOG.md](CHANGELOG.md); report a vulnerability as [SECURITY.md](SECURITY.md) says.

## License

MIT
