# HoneySQL queries

Part of [pgmalli](../README.md): [types](types.md) · [reading rows](reading-rows.md) · [queries](queries.md) · [datasets](datasets.md)

`pgmalli.honeysql` reads a HoneySQL query as data and compares it with the registry. The
examples use [example/schema.sql](example/schema.sql).

```clojure
(require '[pgmalli.core :as pgmalli]
         '[pgmalli.honeysql :as h]
         '[malli.core :as m])

(def registry (pgmalli/registry "public"))
```

## What a name refers to

`h/check` resolves names the way PostgreSQL does:

- `:email` is a column of a table in `:from` or `:join`; `:u.email` a column of the alias `u`.
- A table is looked up in the `:schema` option (`"public"` by default); `:other.t` names its schema.
- A `:with` entry is a table its later entries and the main statement see.
- A subquery in `:from` or `:join` sees the outer query's tables, not its siblings; a LATERAL
  one sees the tables before it.
- `USING` merges the columns it names; `:users` alone in `:select` is the whole row.
- Names are compared as HoneySQL formats them: `:group-id` is `group_id`.

```clojure
(h/check registry {:select [:p.id]
                   :from [[:posts :p]]
                   :join [[:users :u] [:= :u.id :p.author_id]]
                   :where [:= :u.title "x"]})
;; => [{:kind :unknown-column, :column :u.title}]

(h/check registry {:select [:id] :from [:accounts]})
;; => [{:kind :unknown-table, :table "public.accounts"}]
```

A problem in a subquery or CTE carries `:in`, its path into the query:

```clojure
(def q {:select [:id] :from [[{:select [:id] :from [:users] :where [:= :mail 1]} :s]]})

(h/check registry q)
;; => [{:kind :unknown-column, :column :mail, :did-you-mean [:email], :in [:from 0 0]}]

(get-in q [:from 0 0])
;; => {:select [:id], :from [:users], :where [:= :mail 1]}
```

A clause pgmalli does not read is reported rather than passed over:

```clojure
(h/check registry {:select [:id] :from [:users] :frobnicate [:x]})
;; => [{:kind :unread-clause, :clause :frobnicate}]
```

The columns of a CTE, a subquery, a table function, or a `pg_catalog` table the registry
lacks exist but have no type. Comparisons with them, or with an expression `h/row-schema`
leaves untyped, are not checked. Every table has the system columns (`ctid`, `xmin`, ...).

## Writes

An INSERT or UPDATE is checked against what PostgreSQL refuses to write:

```clojure
(h/check registry {:update :users :set {:email nil} :where [:= :id 1]})
;; => [{:kind :not-null-violation, :table "public.users", :column :email, :clause :set}]

(h/check registry {:update :posts :set {:title_upper "X"} :where [:= :id 1]})
;; => [{:kind :read-only-column, :table "public.posts", :column :title_upper}]
```

NULL is any of `nil`, `:null` (in any case), `[:inline nil]` and `[:lift nil]`; a `:null` the
format quotes (`:quoted`, or a `:dialect`) is a column named `null`. HoneySQL also writes NULL
for a key one map of `:values` lacks and another has. `users.status`
has a default, but the first row below gets NULL:

```clojure
(h/check registry {:insert-into :users
                   :values [{:group_id 1 :email "ann@example.com"}
                            {:group_id 1 :email "bob@example.com" :status [:cast "closed" :user_status] :closed_at [:now]}]})
;; => [{:kind :not-null-violation, :table "public.users", :column :status, :clause :values}]
```

Give `h/check` the options you give `sql/format`, as `:format`:

```clojure
(h/check registry {:insert-into :users
                   :values [{:group_id 1 :email "ann@example.com"}
                            {:group_id 1 :email "bob@example.com" :status [:cast "closed" :user_status] :closed_at [:now]}]}
         {:format {:values-default-columns #{:status :closed_at}}})
;; => []
```

A table with a BEFORE row trigger for the statement (`:pg/before-triggers`) gets neither
`:not-null-violation` nor `:missing-required-column`: the trigger may fill the row.

`h/check` does not see what an argument holds; `h/arg-types` types one assigned to a NOT NULL
column without nil.

## The types of the arguments

`h/arg-types` gives `{arg schema}` for a query's named parameters (`:?id`, `[:param :id]`),
keyed as in `sql/format`'s `:params`:

```clojure
(h/arg-types registry {:select [:id]
                       :from [:users]
                       :where [:and [:= :group_id :?group] [:in :status :?statuses]]
                       :limit :?n})
;; => {:group [:int #:pg{:type "integer"}], :statuses [:or [:sequential {:min 1} [:enum {:default "active", :pg/default "active", :pg/type "user_status"} "active" "closed"]] [:set {:min 1} [:enum {:default "active", :pg/default "active", :pg/type "user_status"} "active" "closed"]]], :n [:maybe [:int {:pg/type "bigint", :min 0}]]}
```

| where the argument is | its type |
|---|---|
| compared with a column, in a condition or in a select or ORDER BY expression | the column's kind without NULL: an enum type's labels kept, a CHECK's list under `=`, `IN` and `IS NOT DISTINCT FROM`; other comparisons take the column's base type |
| compared with an expression `h/row-schema` types, or with a column in a row (`[:< [:composite :a :b] [:composite :?a :?b]]`) | as with a column, of the expression's type |
| in `:in` | a non-empty vector, list or set of the column's type |
| cast (`[:cast :?at :timestamptz]`) | what PostgreSQL casts to the type, of the types `:sends` names: a string too, and an enum type's labels alone; `:any` for a type whose values pgmalli does not type (json, inet); nil too, unless every use of the cast is compared (NULL matching no row; an operand of `=`, `<`, `IN`, `BETWEEN` ..., not of `IS [NOT] DISTINCT FROM` or with NULL) or assigned to a NOT NULL column |
| assigned to a column (`:set`, `:values`, an item of `INSERT INTO t (cols) SELECT`) | the column's type as malli's default registry reads it, NULL allowed when the column allows it: a `numeric(p,s)`'s precision and the CHECKs only pgmalli evaluates (`[:pg/check]`, a `char(n)` column's) are left out; under `[:unnest :?xs]`, an array of it, as `:write`'s `:array` says |
| a `LIKE` pattern | the column's kind (`:string`) |
| in `:limit`, `:offset` | an integer of at least 0, or nil for none; under `:accept :postgresql` a decimal, double or float of at least 0 too, which PostgreSQL rounds |
| used only inside code (`(when x ...)`) | its type or nil |
| anywhere else | `:any` |

A column's type takes its own values, a `numeric`, `real` or `double precision` one an integer
too, a `double precision` one a `real` too, of the types `:sends` names: the same number. PostgreSQL takes more, a `bigint` compared with a `numeric`, any value
assigned to a `text` column, which hides a mix-up a type would catch. `{:accept :postgresql}`
types an argument with all of it, of the types `:sends` names, the alternatives held to the
column's range:

```clojure
(h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?id]} {:accept :postgresql})
;; => {:id [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]}

(h/arg-types registry {:select [:id] :from [:users] :where [:> :created_at [:cast :?since :timestamptz]]}
             {:sends ["timestamp with time zone" "character varying"]})
;; => {:since [:or [inst? #:pg{:type "timestamp with time zone"}] :time/offset-date-time :string char?]}
```

Where its uses type an argument differently, the first statement wins (an enclosing one before
its subqueries), and within it a comparison (the first), then `:limit` / `:offset`, a cast, an
assignment.

A comparison leaves out CHECKs and lengths: PostgreSQL compares a value beyond them and
matches no row.

```clojure
(h/arg-types registry {:update :users :set {:nick :?nick} :where [:= :id :?id]})
;; => {:id [:int #:pg{:type "bigint"}], :nick [:maybe [:string {:max 40, :pg/max-length 40, :pg/type "character varying"}]]}
```

A comparison with NULL matches no row, so `:?id` above does not take nil. To take nil, pass
`{:compare :lenient}`:

```clojure
(h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?id]} {:compare :lenient})
;; => {:id [:maybe [:int #:pg{:type "bigint"}]]}
```

```clojure
(h/arg-types registry {:select [:id] :from [:users] :where [:= [:cast :id :text] :?id]})
;; => {:id [:string #:pg{:type "text"}]}

(h/arg-types registry {:insert-into [[:posts [:author_id :title]] {:select [[:?author] [[:unnest :?titles]]]}]}
             {:write {:array [:sequential]}})
;; => {:author [:int #:pg{:type "bigint"}], :titles [:sequential [:string {:max 200, :pg/max-length 200, :pg/type "character varying"}]]}
```

`h/params-schema` gives the same types as the `[:map ...]` of `sql/format`'s `:params`. Every
key is required, as `sql/format` refuses a missing one:

```clojure
(h/params-schema registry {:update :users :set {:nick :?nick} :where [:= :id :?id]})
;; => [:map [:id [:int #:pg{:type "bigint"}]] [:nick [:maybe [:string {:max 40, :pg/max-length 40, :pg/type "character varying"}]]]]
```

## The row a query returns

`h/row-schema` gives the `[:map ...]` of one row, as next.jdbc builds it. A column an outer
join may leave NULL is nullable. A set operation (`:union` ...) takes its first member's column
names, as unqualified keys:

```clojure
(h/row-schema registry {:select [:u.email :p.title]
                        :from [[:users :u]]
                        :left-join [[:posts :p] [:= :p.author_id :u.id]]})
;; => [:map [:users/email [:string {:max 120, :pg/max-length 120, :pg/type "character varying"}]] [:posts/title [:maybe [:string {:max 200, :pg/max-length 200, :pg/type "character varying"}]]]]

(h/row-schema registry {:select [:id [[:count :*] :n]] :from [:users] :group-by [:id]})
;; => [:map [:users/id [:int #:pg{:identity :always, :type "bigint"}]] [:n [:int #:pg{:type "bigint"}]]]
```

An expression has its type and nullability for these forms: a literal (as HoneySQL sends it,
by its class), a cast to a type named as PostgreSQL prints it or by its alias (`:bigint`,
`:timestamptz`) or to an enum type or domain of the registry, `count`, `sum`, `avg`, `min`,
`max`, `coalesce` of one type, an operator that makes a boolean (`=`, `IN`, `LIKE`, `BETWEEN`, `AND` ..., `EXISTS`,
`IS`), the clock's values (`now()`, `current_date` ...) and `->>`. Any other is nullable `:any`:

```clojure
(h/row-schema registry {:select [[[:coalesce :nick :email] :name] [[:max :created_at] :last]
                                 [[:exists {:select [1] :from [:posts]}] :any_posts] [[:cast "active" :user_status] :s]
                                 [[:upper :nick] :shout]]
                        :from [:users]})
;; => [:map [:name [:string #:pg{:type "character varying"}]] [:last [:maybe [inst? #:pg{:type "timestamp with time zone"}]]] [:any_posts [:boolean #:pg{:type "boolean"}]] [:s [:enum #:pg{:type "user_status"} "active" "closed"]] [:shout [:maybe :any]]]
```

It takes the options of `pgmalli.core/as-read` ([reading-rows.md](reading-rows.md)):

```clojure
(h/row-schema registry {:select [:id :created_at] :from [:users]} {:time :instant :kebab? true})
;; => [:map [:users/id [:int #:pg{:identity :always, :type "bigint"}]] [:users/created-at [:time/instant #:pg{:default [:now], :type "timestamp with time zone"}]]]
```

A write with no RETURNING returns the map next.jdbc builds of the driver's update count. Under
next.jdbc's `:return-keys`, pgjdbc adds RETURNING to an INSERT, UPDATE or DELETE:

```clojure
(h/row-schema registry {:delete-from :posts :where [:= :author_id 1]})
;; => [:map [:next.jdbc/update-count :int]]

(h/row-schema registry {:delete-from :posts :where [:= :author_id 1]} {:return-keys ["id"]})
;; => [:map [:posts/id [:int #:pg{:identity :always, :type "bigint"}]]]
```

`h/row-schema` returns nil when a selected column cannot be resolved (`h/check` says which),
when a whole row is selected, when code builds the RETURNING, and for a TRUNCATE under
`:return-keys`, which returns no row:

```clojure
(h/row-schema registry {:select [:u] :from [[:users :u]]})
;; => nil

(h/row-schema registry '{:delete-from :posts :returning cols})
;; => nil

(h/row-schema registry {:truncate :posts} {:return-keys true})
;; => nil
```

## The tables a query uses

`h/tables` gives the tables and views a query reads and writes, in every subquery and CTE,
with aliases and CTE names resolved. Use it to find the queries a change to a table affects.

```clojure
(h/tables registry {:update :users :set {:nick "a"} :from [[:groups :g]] :where [:= :g.id :users.group_id]})
;; => {:reads #{"public.groups"}, :writes #{"public.users"}}
```

## Options

`h/check`, `h/arg-types`, `h/params-schema`, `h/row-schema`, `h/query-schema` and `h/tables`
take the same options map:

| option | for |
|---|---|
| `:schema "app"` | the schema of unqualified tables |
| the options of `as-read` | how next.jdbc builds rows and the driver reads values ([reading-rows.md](reading-rows.md#options)) |
| `:write {pg-type schema}` | what your own `SettableParameter` extension takes for a type |
| `:write {:enum [:string :keyword]}` | the form your own `SettableParameter` extension accepts for a value of an enum or a CHECK's list: strings, keywords or both |
| `:write {:array [:pg/array :sequential :vector :set]}` | what an array argument (`[:any :?ids]`) may be sent as: a Java array, a vector, list or set; `:any` without it |
| `:stringtype :unspecified` | your connection's `stringtype`: strings reach every column untyped (default `:varchar`, as pgjdbc's) |
| `:accept :postgresql` | an argument takes all PostgreSQL takes where it goes, not its column's own values alone (`:native`, the default) |
| `:sends ["bigint" ...]` | the types your application's values reach PostgreSQL as (names or aliases of [types.md](types.md)), for a cast's argument, a number column's narrower numbers and under `:accept :postgresql`, the types `:write` names added: by default pgjdbc's for its Java types, `bigint`, `numeric`, `double precision`, `real`, `boolean`, `character varying`, `uuid`, `bytea`, `date`, `time`, `timetz`, `timestamp`, `timestamptz` |
| `:compare :lenient` | arguments compared with a column take nil too (`:strict` by default) |
| `:format {...}` | the options you pass to `sql/format`; `:values-default-columns`, `:inline` and `:quoted` are read |
| `:result :one` | `h/query-schema` only: the return is what `execute-one!` returns |
| `:return-keys true` or `["col" ...]` | the `:return-keys` next.jdbc sends a write with, passed to `execute!` or set with `with-options` |

## Function schemas

`h/query-schema` combines `arg-types` and `row-schema` into `[:=> [:cat arg ...] [:sequential
row]]`, the arguments in the function's order. The return is what `execute!` returns on a
datasource or connection, `:any` when `h/row-schema` gives nil. `{:result :one}` makes it what
`execute-one!` returns:

```clojure
(h/query-schema registry [:id] {:select [:email] :from [:users] :where [:= :id :?id]} {:result :one})
;; => [:=> [:cat [:int #:pg{:type "bigint"}]] [:maybe [:map [:users/email [:string {:max 120, :pg/max-length 120, :pg/type "character varying"}]]]]]

(h/query-schema registry [:id] {:delete-from :users :where [:= :id :?id]} {:result :one})
;; => [:=> [:cat [:int #:pg{:type "bigint"}]] [:map [:next.jdbc/update-count :int]]]
```

A function returning what `plan`, `execute-batch!`, `:multi-rs` or a prepared statement
returns, or a value made of the rows, needs a return schema of its own. [README.md](../README.md#function-schemas-for-queries) puts
it on a function and instruments it.

The schema may use types malli's default registry lacks (`:time/instant`). That is why
`m/schema` takes the registry: the schema object then needs none. Read its parts with
`m/children`:

```clojure
(def find-users-schema
  (m/schema (h/query-schema registry [:db :ids] {:select [:id] :from [:users] :where [:in :id :?ids]})
            {:registry registry}))

(m/validate (first (m/children find-users-schema)) [nil #{1 2}])
;; => true

(m/validate (first (m/children find-users-schema)) [nil #{}])
;; => false
```

### Problems in function schemas

The `:pgmalli/problems` property makes the vector one longer, so read the parts with
`m/children`, not by position:

```clojure
(def broken
  (m/schema (h/query-schema registry [:id] {:select [:mail] :from [:users] :where [:= :id :?id]})
            {:registry registry}))

(:pgmalli/problems (m/properties broken))
;; => [{:kind :unknown-column, :column :mail, :did-you-mean [:email]}]

(m/form (first (m/children broken)))
;; => [:cat [:int #:pg{:type "bigint"}]]
```

An argument of the query that the argument vector (`[:db :idd]`) lacks is `:missing-arg`,
most often a typo:

```clojure
(:pgmalli/problems (m/properties (m/schema (h/query-schema registry [:db :idd] {:select [:id] :from [:users] :where [:= :id :?id]})
                                           {:registry registry})))
;; => [{:kind :missing-arg, :arg :id}]
```

`h/schema-problems` gathers the property from the `:malli/schema` of every var, each problem
naming its `:var`; `{:ns 'app.queries}` (or a list of namespaces) limits it to some:

<!-- :test-doc-blocks/skip -->
```clojure
(deftest query-functions-agree-with-the-schema
  (let [problems (h/schema-problems)]
    (is (= [] problems) (h/explain problems))))
```

## Reported though PostgreSQL runs them

These match or touch no row, which is almost always a mistake:

- An `=`, `IN` or `IS NOT DISTINCT FROM` of a column a CHECK narrows to a list (`CHECK (state
  IN ('open', 'closed'))`) with a value outside it: an `:enum-literal`. `<>`, `NOT IN` and `<`
  match rows and are not reported; nor is `NOT (state = ...)`.
- An UPDATE, or an ON CONFLICT DO UPDATE, writing NULL into a NOT NULL column: a
  `:not-null-violation`, even when it would touch no row or no row conflicts.

## Not checked

- What code in the query (a list) builds at run time: the vectors written inside it are
  checked, but a column it computes (`(keyword c)`) is not.
- The tables of `:join-by`.
- A statement keyed by symbols (`'{select [...]}`): symbols are values here, so it is
  reported as `:unread-clause`.
- Values written in the query beyond NULL, enum literals and strings sent as `varchar`: an
  empty `[:in :id []]` is not reported (an argument in `:in` is typed non-empty).
- An ON clause naming a table joined after it, which PostgreSQL rejects.
- Under ON CONFLICT, whether RETURNING and DO UPDATE WHERE may name the target or `excluded`.
- An argument schema with a regex CHECK (`[:and [:string] [:re ...]]`) does not generate with
  `mg/generate`; it validates only. It lacks the generation hint the registry's column schema
  carries, and test.chuck refuses the regex's `\z`.
- Partitions and sequences: the registry lists neither, so a query reading one by name
  (`:from [:events_2026]`, `:from [:users_id_seq]`) gets `:unknown-table`. Write the name as a
  symbol (`'events_2026`), a table chosen at run time, to leave it unjudged.
- A string sent as `varchar` to a column of a domain over a type other than an enum (a
  domain over `integer`), which PostgreSQL refuses; and an argument whose type `h/arg-types`
  leaves `:any` (an `inet` column's), which may be an object of the type.
- Without `:quoted`, a mixed-case name the registry holds as written (`:wh.Warehouse`):
  PostgreSQL folds it to lower case, and check takes it as written.
- Under qualified keys, the key of a column next.jdbc qualifies by a table the data does not
  show (one read through a subquery or a CTE, a cast column): `h/row-schema` leaves it out of
  the map, which is open, and names it under the `:pgmalli/untold` property.
