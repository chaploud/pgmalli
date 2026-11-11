# pgmalli

[![ci](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/chaploud/pgmalli/actions/workflows/ci.yml)

Your PostgreSQL schema as [malli](https://github.com/metosin/malli) schemas: validate rows,
generate test data and check HoneySQL queries, without a database at hand.

- [Quickstart](#quickstart)
- [Installation](#installation) and [Generating](#generating): the registry from psql or JDBC
- [Validating rows and writes](#validating-rows-and-writes): rows, INSERTs and UPDATEs
- [Rows read through next.jdbc](#rows-read-through-nextjdbc)
- [Generating test data](#generating-test-data)
- [Checking HoneySQL queries](#checking-honeysql-queries)
- [Function schemas for queries](#function-schemas-for-queries): instrument query functions
- [Keeping the file current](#keeping-the-file-current): a stale file fails CI
- [Column shapes](#column-shapes)
- [Supported versions](#supported-versions), [Limits](#limits) and [Stability](#stability)
- Reference: [types and constraints](docs/types.md), [reading rows](docs/reading-rows.md),
  [queries](docs/queries.md)

## Quickstart

The examples use [docs/example/schema.sql](docs/example/schema.sql): `groups`, `users`,
`posts` and `events`, with an enum, identity and generated columns, a jsonb column, and
CHECKs. Add pgmalli to your dependencies ([Installation](#installation)), `"resources"` to
`:paths` in `deps.edn` (the generated file goes to `resources/pgmalli.edn`, and the
default `:paths` is `["src"]` alone), and an alias that generates it:

<!-- :test-doc-blocks/skip -->
```clojure
{:aliases {:pgmalli {:exec-fn pgmalli.generate/generate!
                          :exec-args {:schemas ["public"]}}}}
```

To try it, create an empty database, load `docs/example/schema.sql` into it from a clone of
this repository, then generate in your project ([Generating](#generating) has how pgmalli
reaches the database):

```shell
$ createdb pgmalli_example
$ psql -d pgmalli_example -f path/to/pgmalli/docs/example/schema.sql
$ export PGDATABASE=pgmalli_example
$ clojure -X:pgmalli
```

It writes `resources/pgmalli.edn`.

Load the generated file as a registry and validate an INSERT:

```clojure
(require '[pgmalli.core :as pgmalli]
         '[malli.core :as m]
         '[malli.error :as me]
         '[malli.generator :as mg])

(def registry (pgmalli/registry))

(m/validate :pg.public.users/insert
            {:group_id 1 :email "ann@example.com"}
            {:registry registry})
;; => true
```

The schemas hold the columns' types ([what the database checks](docs/types.md#names-in-the-registry)
aside). The registry's `java.time` types carry a message each, which malli has none of:

```clojure
(-> (m/explain :pg.public.users/insert
               {:group_id 1 :email "ann@example.com" :closed_at 1}
               {:registry registry})
    me/humanize)
;; => {:closed_at ["should be a java.time.Instant"]}
```

Generate rows of the table's types:

```clojure
(m/validate :pg.public.users/insert
            (mg/generate :pg.public.users/insert {:registry registry :seed 42})
            {:registry registry})
;; => true
```

Regenerate the file after each migration; [Keeping the file current](#keeping-the-file-current)
shows how CI catches a forgotten one.

## Installation

Depend on a commit of this repository in `deps.edn`'s `:deps`:

<!-- :test-doc-blocks/skip -->
```clojure
io.github.chaploud/pgmalli {:git/url "https://github.com/chaploud/pgmalli" :git/sha "<commit>"}
```

pgmalli depends on malli, test.check and jsonista (on babashka, cheshire, which it ships). Add HoneySQL for `pgmalli.core` and
`pgmalli.honeysql`, and next.jdbc and pgjdbc for `pgmalli.jdbc` ([Supported
versions](#supported-versions) has the oldest each takes); generating needs none of them, but
pgjdbc for a `:db` with `:jdbcUrl`. On babashka, put the same coordinate in `bb.edn`'s `:deps`.

## Generating

`pgmalli.generate/generate!` and `check` take one map, so `clojure -X` passes them an
alias's `:exec-args` and a REPL calls them alike:

<!-- :test-doc-blocks/skip -->
```clojure
{:schemas ["public"]                  ; the default
 :out "resources/pgmalli.edn"    ; the default; keep it on the classpath
 :db {:host "localhost" :port 5432 :dbname "app_dev" :user "app"}}
```

They read the database with `psql`. Without `:db`, psql's own settings apply (`PGHOST`,
`PGDATABASE`, `PGUSER`, `PGPASSWORD`, `~/.pgpass`). `:db` takes `:host`, `:port`, `:dbname`,
`:user`, `:password`, `:sslmode` and `:psql`, the psql binary to run; a next.jdbc db-spec is one
without its `:dbtype`. On the JVM, a `:db` with `:jdbcUrl` reads through pgjdbc (PostgreSQL's
JDBC driver) instead, no psql needed, and `{:datasource ds}` through a `javax.sql.DataSource`;
`{:jdbcUrl ... :user ... :password ...}` is a next.jdbc db-spec too (`jdbc/get-datasource` takes it):

<!-- :test-doc-blocks/skip -->
```clojure
(require '[pgmalli.generate :as generate])

(generate/generate! {:schemas ["public" "app"]
                     :db {:jdbcUrl "jdbc:postgresql://localhost/app_dev" :user "app" :password "secret"}})
;; => {:out "resources/pgmalli.edn", :unrendered nil, :diagnostics nil}
```

One file holds every schema in `:schemas`. `generate!` writes over no file but a generated one,
and returns the diagnostics and the columns left out ([docs/types.md](docs/types.md#the-generated-file)).
`generate!` and `check` print the columns left out, nothing where all is well. Generating needs a
[supported PostgreSQL](#supported-versions). The read runs in a read-only transaction and needs no
privilege but to log in: give `:db` a role of its own, one that owns nothing and writes nothing.

`(pgmalli/registry)` reads `pgmalli.edn` from the classpath. Where `:out` is elsewhere,
pass its data:

<!-- :test-doc-blocks/skip -->
```clojure
(pgmalli/registry (clojure.edn/read-string (slurp "api/pgmalli.edn")))
```

## Validating rows and writes

For each table the registry holds three schemas: the row, what an INSERT may carry, and
what an UPDATE may set. They hold the database's types; a row read and a row your code writes
have schemas of their own:

| to check | use |
|---|---|
| a row read through `pgmalli.jdbc/builder` | `as-read` ([below](#rows-read-through-nextjdbc)) |
| a row your code writes in `:values` or `:set` | `h/values-schema`, `h/set-schema` ([queries](docs/queries.md#a-row-written-into-a-table)) |
| a row of the database's types: another system's (an export, a message) | the registry's row, `/insert` and `/update` schemas (here) |

```clojure
(m/validate :pg.public/users
            {:id 1 :group_id 1 :email "ann@example.com" :nick nil :status "active"
             :closed_at nil :created_at (java.time.Instant/parse "2026-01-01T00:00:00Z")}
            {:registry registry})
;; => true
```

An INSERT schema is closed and leaves out what the database writes
([types.md](docs/types.md#names-in-the-registry)):

```clojure
(-> (m/explain :pg.public.posts/insert
               {:id 1 :author_id 1 :title "Hello" :title_upper "HELLO"}
               {:registry registry})
    (me/humanize))
;; => {:id ["disallowed key"], :title_upper ["disallowed key"]}
```

An UPDATE schema takes the same columns, each optional:

```clojure
(m/validate :pg.public.users/update {:nick "ann"} {:registry registry})
;; => true

(m/validate :pg.public.users/update {:email nil} {:registry registry})
;; => false
```

A `numeric` column takes a BigDecimal (`10M`); a long such as `10` fails. An enum's schema
takes its labels as strings ([docs/types.md](docs/types.md)):

```clojure
(m/validate :pg.public/user_status "closed" {:registry registry})
;; => true

(m/validate :pg.public/user_status "deleted" {:registry registry})
;; => false
```

humanize's messages are for developers: they name tables, columns and types. Before showing an
error to the people using your application, word it yourself.

[docs/types.md](docs/types.md) lists what every type becomes.

## Rows read through next.jdbc

pgjdbc does not return the registry's types. pgmalli types rows as `pgmalli.jdbc/builder`
builds them and arguments as `pgmalli.jdbc/sent` sends them: pass next.jdbc the sqlvec
through `(sent (sql/format q {:params args}))`, and `{:builder-fn (builder registry)}`
([docs/reading-rows.md](docs/reading-rows.md#the-one-reading-and-sending)). `as-read` gives the
row as the builder builds it, to validate results as they come back:

```clojure
(def user-row (pgmalli/as-read registry :pg.public/users))

(m/validate user-row
            {:id 1 :group_id 1 :email "ann@example.com" :nick nil :status :active
             :closed_at nil :created_at (java.time.Instant/EPOCH)}
            {:registry registry})
;; => true
```

A query's row, a join's or some columns', is `(:row (h/analyze ...))`
([queries](docs/queries.md#the-row-a-query-returns)).

[docs/reading-rows.md](docs/reading-rows.md) has what each type comes back as.

## Generating test data

`mg/generate` of `h/values-schema` gives a row HoneySQL writes and PostgreSQL takes, each value
as your application sends it (`[:lift v]` an enum's label or a json document), strings within
their `varchar(n)`'s length ([what the database checks](docs/types.md#names-in-the-registry)
aside):

```clojure
(require '[pgmalli.honeysql :as h])

(let [row (h/values-schema registry :events)]
  (m/validate row (mg/generate row {:registry registry :seed 1}) {:registry registry}))
;; => true
```

Insert it as `{:insert-into :events :values [row]}`.

A column whose CHECK (its own or its domain's) the generated values miss takes a
[shape](#column-shapes) that generates within it, `{:shapes {:public.users/email [:ref
:app/email]}}`, with `:app/email` in your registry ([column shapes](#column-shapes) builds it),
its generator on the whole `[:and ...]` (malli generates an `[:and ...]` from its first child). A
keyword to the column, which goes as its text, is held to it too. A `:re` shape generates only
with `com.gfredericks/test.chuck` on the classpath, as malli's `:re` does.

The registry's `/insert` schema generates the database's types (a label a string, a document a
map), what another system holds rather than what HoneySQL sends.

## Checking HoneySQL queries

`h/analyze` reads HoneySQL query data against the registry, with no database: the arguments'
types, the row, the problems PostgreSQL would report and more ([queries](docs/queries.md)):

```clojure
(require '[pgmalli.honeysql :as h])

(h/analyze registry {:select [:email] :from [:users] :where [:= :id :?id]})
;; => {:args {:id [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]}, :row [:map [:email [:string #:pg{:max-length 120, :type "character varying"}]]], :rows {:min 0, :max 1}, :problems [], :tables {:reads #{"public.users"}, :writes #{}}, :document-columns {}}
```

`:problems` reports what PostgreSQL would refuse, an enum's value outside its labels too:

```clojure
(:problems (h/analyze registry {:select [:id :email] :from [:users] :where [:= :status [:cast "active" :user_status]]}))
;; => []

(:problems (h/analyze registry {:select [:id :mail] :from [:users] :where [:= :status [:cast "gone" :user_status]]}))
;; => [{:kind :unknown-column, :column :mail, :did-you-mean [:email]} {:kind :enum-literal, :value "gone", :allowed ["active" "closed"], :type "user_status"}]
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

`:problems` and `:args` refuse nothing PostgreSQL runs or takes, but for the rules of
[stricter than PostgreSQL](docs/queries.md#stricter-than-postgresql), each saying what to send
or write instead; what they do not catch is [not checked](docs/queries.md#not-checked). The
problems it reports:

| `:kind` | for |
|---|---|
| `:unknown-table` | a table the registry lacks; `:did-you-mean` lists a table of that name in another schema first; `:quoted` true where, under `:quoted`, the name folded to lower case is a table of the registry |
| `:unknown-column` | a column no table in scope has, or a qualifier no table or alias in scope has (`:qualifier`); with `:clause` where a clause names it: `:using` (`:side` `:left`, `:right` or `:both`, `:table` the joined one), `:on-conflict` (`:table`; `:constraint` where a vector headed by `:on-constraint` names one), `:values` (a keyword value, which HoneySQL writes as a column; `:table` the table written, `:target` the value's key, absent past a positional row's columns), `:set` or `:do-update-set` with `:target` (a keyword value likewise, `:target` the column it is assigned to), `:set`, `:do-update-set`, `:columns` or `:values` without `:table` or `:target` (an assigned target qualified by its table or alias: a field of a column to PostgreSQL); `:outside-join` true where an ON inside a parenthesized join names a table of the statement outside it; `:did-you-mean` where a column is spelled close; `:quoted` true where, under `:quoted`, the name folded to lower case is a column |
| `:ambiguous-column` | an unqualified column of two tables, or a column a subquery or CTE has several of, with `:candidates` (that one alone) |
| `:duplicate-column` | columns of one label in the row a query returns, which `pgmalli.jdbc/builder` reads into one key; with `:items`, the select items making them ([stricter than PostgreSQL](docs/queries.md#stricter-than-postgresql)) |
| `:missing-required-column` | an INSERT lacking a required column |
| `:not-null-violation` | NULL written into a NOT NULL column |
| `:row-value` | a vector of two values or more as an operand or a function's argument (`[:= :id [:any [1 2]]]`, `[:array_length [1 2] 1]`), which HoneySQL writes as a row, `(?, ?)`, compared with no row (a composite, another row, a column of a row type); with `:function` and `:value`. Write `[:lift v]` |
| `:unlifted-value` | a map, or a vector of none or of two values or more (none alone into a column of a string type, which takes the row's text), written into a column (`:set`, `:values`, `:do-update-set`), which HoneySQL writes as a subquery or a row of parameters; with `:value` and the column's `:type` |
| `:read-only-column` | a value written into a generated or identity ALWAYS column |
| `:values-arity` | a `:values` row not as long as `:columns` |
| `:enum-literal` | a literal (a string, `[:lift "s"]`, `[:inline "s"]`, `[:lift :kw]`, a number) outside an enum type's labels where PostgreSQL types it as the enum: compared, in an IN list or ANY's array, assigned, beside the enum in `coalesce`, `greatest`, `least`, `nullif` or CASE's values, or cast to it; with `:allowed`, and `:column` (the column, or the expression compared or resolved beside) or, cast, `:type` |
| `:no-operator` | a comparison of a column of a domain over an enum type, which PostgreSQL compares with no value, not even its own; with `:column`, `:op`, `:domain` and `:type`, the enum to cast the column to |
| `:uncast-string` | a string literal (or `[:lift "s"]`), which pgjdbc sends as `varchar`, where the column's type takes none (an enum, a number, a date ...), or a function's or an operator's argument there (with `:function` in place of `:column`; `:column` the column, or the expression compared); not under `:format {:inline true}`. A named argument is typed as `:args` types it |
| `:literal-type` | a literal HoneySQL sends as a parameter (`[:round :price 2]`'s `2`, a `Long` sent as `bigint`; `[:lift [1]]`, a vector sent as an array, to a `jsonb` column) where an argument would refuse it: a function's or an operator's argument, compared, assigned (`:set {:id_uuid 1}`), beside others in `coalesce` or CASE; with `:function` (the function or operator, or the column, also as `:column`, where it is assigned), `:sent`, and `:type` where one type is meant; a `[:lift v]` of a class pgjdbc sends nowhere (a list, a set) wherever it is but in `:in`, `:sent nil`, with no `:function` where no argument takes it; not under `:format {:inline true}` |
| `:undetermined-type` | an argument where PostgreSQL takes its type from the value alone (`[:cardinality :?ids]`, `concat`'s `"any"`), whose other uses take only values sent untyped (a keyword label) or nil; with `:function`, and `:type` where the other uses name one |
| `:unknown-type` | a cast to an unqualified type name of no type of pg_catalog or of the query's schema the registry tells of (an enum, a table's row type, a domain of one of its columns), but of an enum type of another schema; with `:type` and `:did-you-mean`, the qualified names |
| `:column-definition-list` | a function in FROM whose alias lists column types (`{:with-columns [[:v :jsonb]]}`), which PostgreSQL takes of a function returning record alone; with `:function`, `:alias` and `:columns`, the names it lists. Name the columns with no type (`[:r {:columns [:v]}]`) |
| `:unread-clause` | a clause pgmalli does not read, with the one meant when HoneySQL knows none of that name; the rest of the query is still checked |
| `:written-otherwise` | data HoneySQL writes otherwise than it reads: `[:in x a b]`, which it writes `x IN a` (`:op`, `:dropped`, how many it drops); an operator it has not registered (`[:-> a b]` without `honey.sql.pg-ops`), which it writes as a call `->(a, b)` (`:op`, `:call`); a write and a query's clause beside it (`{:insert-into :t :select ...}`), which it writes the query first of (`:clause`, `:before`); a written column of a keyword's name (`:order`), which it writes unquoted, or of a string it writes as another name (`"a.b"` as `"a"."b"`) (`:column`); an ORDER BY item of more than a direction (`[:a :desc :nulls-last]`), which it writes `a DESC` (`:order-by`); a vector headed by a named parameter (`[:?x :?y]`, an operand of an operator or of `:in`, or a written value), which it writes as a call `??X(?)` (`:param`); a string in `:columns`, which it writes as parameters (`:column`, `:columns`); under `:inline`, `[:lift v]` of a collection or a map but a list of one integer, finite float or Boolean, which it writes as its printed text (`[1, 2]`, `:lifted`, v) |
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

[docs/queries.md](docs/queries.md) covers the rest.

## Function schemas for queries

`h/query-schema` gives the function schema of a function that runs a query. Arguments are
HoneySQL's named parameters (`:?id`), keyed as in `sql/format`'s `:params` (`:id`):

```clojure
(def find-user-q {:select [:id :email] :from [:users] :where [:= :id :?id]})

(h/query-schema registry [:id] find-user-q)
;; => [:=> #:pgmalli{:problems []} [:cat [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]] [:sequential {:max 1} [:map [:id [:int #:pg{:identity :always, :type "bigint"}]] [:email [:string #:pg{:max-length 120, :type "character varying"}]]]]]
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
;; => {:args ["bigint | numeric | float8 | real"], :returns [:maybe {:id "bigint", :email "varchar(120)"}]}
```

The options of `pgmalli.honeysql`'s functions are in [docs/queries.md](docs/queries.md#options).

## Keeping the file current

`generate/check` takes the map `generate!` does, reads the database and compares the file
with what it yields now. Where the file differs or is missing, it prints the differences and
throws (`:pgmalli.error/stale`, ex-data `{:out path :missing path :stale [difference ...]}`), so
`clojure -X` exits non-zero in CI; else it returns `{:unrendered :diagnostics}`:

```shell
$ clojure -X:pgmalli pgmalli.generate/check
:pg.public/groups :motto
  file: nil
  db:   [:maybe [:string {:pg/type "text"}]]
...
stale (differences: 1); run pgmalli.generate/generate! with the same map and commit the file
```

`generate/diff` gives the same differences between two generated files, the effect of a
migration:

```clojure
(require '[pgmalli.generate :as generate])

(let [before (pgmalli/generated)
      after (update-in before [:registry :pg.public/groups] conj [:motto [:maybe :string]])]
  (generate/diff before after))
;; => [{:name :pg.public/groups, :column :motto, :file nil, :db [:maybe :string]}]
```

The file also lists the columns of a type pgmalli has no schema for (`:unrendered`) and what
deserves a look (`:diagnostics`, such as a row-level INSERT trigger or an EXCLUDE constraint).
[docs/types.md](docs/types.md#the-generated-file) describes the file and lists them all:

```clojure
(select-keys (pgmalli/generated) [:unrendered :diagnostics])
;; => {:unrendered [], :diagnostics []}
```

## Column shapes

The generated file holds what the database tells, and no more. What it does not tell, a jsonb
column's keys or a CHECK, is your application's: give the registry it, `:shapes`, a key
`:schema.table/column` the schema of that column's value, such as an `inet` column's, which is
otherwise `:any`, or a jsonb column's shape:

<!-- :test-doc-blocks/skip -->
```clojure
(pgmalli/registry (pgmalli/generated)
                  {:shapes {:public.hosts/ip [:re "^[0-9./]+$"]
                            :public.ai_jobs/input [:map [:prompt :string] [:max_tokens {:optional true} :int]]}})
```

pgmalli still adds what the database says of the column: `[:maybe ...]` unless it is NOT
NULL, its default and `:pg/type`. The schema holds where values are read (`as-read`,
`h/analyze`'s `:row`) and written (`h/analyze`'s `:args`, `h/values-schema`, `h/set-schema`),
but for a type the driver reads as an object of its own: an `inet`, read as a PGobject (an
`hstore` as a `java.util.Map`), stays that object where it is read; the `:public.hosts/ip`
shape above holds for what is written. A json or jsonb column is read parsed
([the one reading](docs/reading-rows.md#the-one-reading-and-sending)), so its shape holds for
what is read, and for a map or a vector an argument sends ([what your application
sends](docs/queries.md#what-your-application-sends)). `registry` checks every key against the
schemas generated: a key must name a column, and a value must be a malli schema (a qualified
name passes, as your registry's). An error names the key meant; a type key (`:inet`) names the
columns of the type to shape instead. `pgmalli.jdbc/builder` takes the generated data
alone: reading rows needs no shape.

A `[:ref ...]` to your own schema needs a registry holding it (without one, malli throws
`:malli.core/invalid-ref`); pass it wherever this README passes `registry`:

```clojure
(require '[malli.registry :as mr] '[clojure.string :as str])

(def app-registry
  (mr/composite-registry
   {:app/email [:and {:gen/schema [:string {:min 1 :max 8}] :gen/fmap #(str % "@example.com")}
                :string [:fn #(str/includes? % "@")]]}
   registry))

(m/validate :app/email "ann@example.com" {:registry app-registry})
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

## Limits

- The registry is the schema at generate time; nothing reads the database at run time
  ([Keeping the file current](#keeping-the-file-current)).
- A table with a BEFORE row trigger for a write takes nil in every column of that write and
  leaves each optional, as the trigger may fill any ([docs/types.md](docs/types.md#names-in-the-registry)).
- `h/analyze` reads a query as the data `sql/format` takes: code in it is an error, and each
  variant your code builds is analyzed on its own
  ([docs/queries.md](docs/queries.md#stricter-than-postgresql)).
- What the database checks beyond a row's column types and NOT NULL (PRIMARY KEY, UNIQUE,
  foreign keys, CHECKs, triggers, EXCLUDE, policies), no schema checks: a row schema holds for
  one row ([docs/types.md](docs/types.md#names-in-the-registry), [what is read](docs/types.md#what-is-read)).

## Stability

Changing any of these is breaking; before 1.0 it bumps the minor version:

1. the keys of the map `generate!` and `check` take
2. the generated file and the registry names
3. the kinds of `:unrendered` entries and of `h/analyze`'s problems
4. the public vars of `pgmalli.core`, `pgmalli.generate`, `pgmalli.honeysql`
   and `pgmalli.jdbc`, and the options of their functions
5. the `:pgmalli/error` kinds of the errors pgmalli throws
6. what `check` throws where the file is stale or missing

| `:pgmalli/error` | when |
|---|---|
| `:pgmalli.error/invalid-config` | a map `generate!` or `check` cannot use (its `:db` too), a schema the database lacks, or an `:out` that is a symbolic link or a file generate! did not write |
| `:pgmalli.error/stale` | `check`: the file differs from the database or is missing, with `:out`, `:missing` and `:stale` |
| `:pgmalli.error/invalid-options` | an option or argument it does not take, with the one meant, or a value `pgmalli.jdbc/sent` cannot send: a document it cannot write as jsonb (two keys of one text, a nil key, a NaN or an infinity, a number past what `numeric` holds, a `\u0000`), an array element of a class it has no text for |
| `:pgmalli.error/unknown-schema` | a name the registry lacks, or one of the wrong kind (an insert schema where a row's goes), with the one meant |
| `:pgmalli.error/not-a-registry` | something passed as a registry that is none |
| `:pgmalli.error/not-query-data` | a query that is no HoneySQL data: SQL already formatted, or code (a symbol in it) |
| `:pgmalli.error/not-generated` | a generated file missing from the classpath, or a file that is none (not EDN, or no `:registry` map) |
| `:pgmalli.error/cannot-generate` | a column of a type pgmalli makes no value of |
| `:pgmalli.error/db-unavailable`, `psql-missing` | the database, or psql, out of reach |
| `:pgmalli.error/fault` | pgmalli's own fault (please report it), or reading the catalog failing: an [unsupported PostgreSQL](#supported-versions), or a user without access to pg_catalog; or `builder` reading a json or jsonb document nested deeper than the thread's stack holds, naming the column |

Each error's ex-data also carries `:cognitect.anomalies/category`, and `:given`, `:known` and
`:did-you-mean` (the names probably meant, as a problem of `h/analyze` names them) where they apply.

## Development

See [CONTRIBUTING.md](CONTRIBUTING.md). What changed in each release is in
[CHANGELOG.md](CHANGELOG.md); report a vulnerability as [SECURITY.md](SECURITY.md) says.

## License

MIT
