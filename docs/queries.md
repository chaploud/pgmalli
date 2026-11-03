# HoneySQL queries

Part of [pgmalli](../README.md): [types](types.md) · [reading rows](reading-rows.md) · [queries](queries.md) · [datasets](datasets.md)

`pgmalli.honeysql` reads a HoneySQL query as data and compares it with the registry. The
examples use [example/schema.sql](example/schema.sql).

```clojure
(require '[pgmalli.core :as pgmalli]
         '[pgmalli.honeysql :as h]
         '[malli.core :as m]
         '[next.jdbc.result-set :as rs])

(def registry (pgmalli/registry "public"))
```

## What a name refers to

`h/check` resolves names the way PostgreSQL does:

- The tables of a statement are those of `:from`, `:using`, `:cross-join`, the joins (not
  `:join-by`), `:insert-into`, `:update`, `:delete-from` and `:truncate`, under their aliases.
- `:email` is a column of one of those tables; `:u.email` and `:u/email` a column of the alias
  `u`. A column a subquery does not find is looked for in the enclosing statements.
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

A qualifier that names no table or alias of the statement is the problem, under `:qualifier`. A
table under an alias is named by its alias alone, as PostgreSQL says:

```clojure
(h/check registry {:select [:users.email] :from [[:users :u]]})
;; => [{:kind :unknown-column, :column :users.email, :qualifier "users", :did-you-mean [:u.email]}]
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

(h/check registry {:selec [:id] :from [:users]})
;; => [{:kind :unread-clause, :clause :selec, :did-you-mean [:select]}]
```

The columns of a CTE or a subquery are typed as its SELECT types them, read as a query of its
own (a CTE under the ones before it, a subquery under the CTEs of the query it reads, a LATERAL
subquery with the tables before it, a recursive CTE as its first member, NULL allowed and a
CHECK's list read as its base type); a column it does not give is `:unknown-column`. A table or
view of `pg_catalog` (`pg_class`) the registry lacks has the columns of the catalog snapshot,
those of every version pgmalli supports. A function of `pg_catalog` or of a generated schema in FROM
has the columns it returns ([The row a query returns](#the-row-a-query-returns)). The columns of
another table function (named by its alias, `WITH ORDINALITY` too), of a table written as a symbol
(`'events_2026`, a table chosen at run time) and of an `information_schema` view the registry
lacks exist but have no type. Comparisons with them, or with an expression `h/row-schema` leaves untyped, are not
checked. Every table has the system columns (`ctid`, `xmin`, ...).

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

A target `:a.b` (`{:set {:p.x 1}}`, `INSERT INTO t (p.x)`) is the field `b` of the composite
column `a`, as PostgreSQL reads it: `a` must be a column of the table, and what goes into the
field is not checked.

`h/check` does not see what an argument holds; `h/arg-types` types one assigned to a NOT NULL
column without nil, unless such a trigger may fill it (for the write of the value: an inserted
one under `ON CONFLICT DO UPDATE` too is checked before the conflict) or the SELECT of
`INSERT ... SELECT` may drop the row.

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
| compared with a column, in any clause (a condition, a select, ORDER BY, SET, RETURNING) | the column's kind without NULL: an enum type's labels kept, a CHECK's list under `=`, `IN` and `IS NOT DISTINCT FROM`; other comparisons take the column's base type; a `PGobject` too where the driver reads the column as one (`citext`, `bit(n)` for n > 1, `bit varying`, a domain over them), which pgjdbc sends as the type it names |
| compared with an expression `h/row-schema` types, or with a column in a row (`[:< [:composite :a :b] [:composite :?a :?b]]`) | as with a column, of the expression's type |
| in `:in` | a non-empty vector, list or set of the column's type |
| cast (`[:cast :?at :timestamptz]`), or an element of an array cast (`[:array [:?k] :text]`, `ARRAY[?]::text[]`) | what PostgreSQL casts to the type, of what [`:send`](#what-your-application-sends) sends: a string too, to an array type (or a domain over one) any array, an enum type's labels alone, a `bytea` and an integer to each other as PostgreSQL 18 casts them (16 and 17 refuse it); `:any` for a type whose values pgmalli does not type (json, inet); nil too, unless every use of the cast is compared (not under `{:compare :lenient}`; NULL matching no row; an operand of `=`, `<`, `IN`, `BETWEEN` ..., not of `IS [NOT] DISTINCT FROM` or with NULL) or assigned where nil may not go (as assigned below) |
| assigned to a column (`:set`, `:values`, an item of `INSERT INTO t (cols) SELECT`) | the column's type as malli's default registry reads it (a `PGobject` too, as compared), NULL allowed when the column allows it, a BEFORE row trigger for the write may fill it, or the SELECT of `INSERT ... SELECT` may drop the row (`WHERE`, `HAVING`, `GROUP BY`, `LIMIT`, `OFFSET`, `DISTINCT`, a join, a `FROM` item other than `unnest` of arguments; then without the CHECKs, lengths and ranges, which PostgreSQL checks only on a row it writes): a `numeric(p,s)`'s precision and the CHECKs only pgmalli evaluates (`[:pg/check]`, a `char(n)` column's) are left out; under `[:unnest :?xs]`, an array of it, as [`:send`](#what-your-application-sends) sends one typed |
| an argument of an `unnest` in `FROM` (`[[:unnest :?ids] [:x {:columns [:id]}]]`, `[[:unnest :?ids] :id]`) | an array, typed as `:send` sends one, of where its column goes, as an argument there: compared, assigned, or cast (an array of what PostgreSQL casts to the type), in a subquery too |
| an argument of `coalesce`, `greatest`, `least` or `nullif`, or a value of `CASE` (`[:coalesce :?nick :email]`) | the type the other arguments resolve to, as a comparison types it, or nil |
| an argument of a function or an operator of `pg_catalog` or of a generated schema (`[:+ :created_at :?d]`, `[:repeat "a" :?n]`) | the type each function or operator PostgreSQL may resolve the call to takes there, as a comparison types it, or nil: a value of each type picks its own (`generate_series(1, ?)` takes an integer, a bigint or a numeric); `:any` where one of them takes any type (a polymorphic one) |
| compared with `ANY`, `SOME` or `ALL` of an array (`[:= :?tag [:any :tags]]`) | as with a column of the array's element type |
| a `LIKE` pattern | the column's kind (`:string`) |
| in `:limit`, `:offset` | an integer of at least 0, or nil for none |
| used only inside code (`(when x ...)`) | its type or nil |
| anywhere else | `:any` |

A column's type takes its own values, and what [`:send`](#what-your-application-sends) sends
that PostgreSQL takes as the column's own: a narrower number, another string type, an untyped
value to a text or enum column. PostgreSQL takes more ([stricter than
PostgreSQL](#stricter-than-postgresql)). Assigned or cast, a number is held to a number column's
range (a fraction going to an integer type to the range half a unit wider, as PostgreSQL rounds
it; a value at exactly half a unit past may round out of range):

```clojure
(h/arg-types registry {:select [:id] :from [:users] :where [:> :created_at [:cast :?since :timestamptz]]})
;; => {:since [:or [inst? #:pg{:type "timestamp with time zone"}] :time/offset-date-time :string :time/local-date :time/local-date-time]}
```

PostgreSQL types a parameter once, so an argument of several uses takes the values each of them
takes: a cast and a comparison, what the comparison takes of what casts. It takes nil where any
of its uses does.

A comparison leaves out CHECKs and lengths: PostgreSQL compares a value beyond them and
matches no row.

```clojure
(h/arg-types registry {:update :users :set {:nick :?nick} :where [:= :id :?id]})
;; => {:id [:int #:pg{:type "bigint"}], :nick [:maybe [:string {:max 40, :pg/max-length 40, :pg/type "character varying"}]]}
```

A comparison with NULL is never true (it matches no row), so `:?id` above does not take nil.
To take nil, pass `{:compare :lenient}`:

```clojure
(h/arg-types registry {:select [:id] :from [:users] :where [:= :id :?id]} {:compare :lenient})
;; => {:id [:maybe [:int #:pg{:type "bigint"}]]}
```

```clojure
(h/arg-types registry {:select [:id] :from [:users] :where [:= [:cast :id :text] :?id]})
;; => {:id [:string #:pg{:type "text"}]}

(h/arg-types registry {:insert-into [[:posts [:author_id :title]] {:select [[:?author] [[:unnest :?titles]]]}]}
             {:send {clojure.lang.IPersistentVector "varchar[]"}})
;; => {:author [:int #:pg{:type "bigint"}], :titles [:or [:pg/array [:string {:max 200, :pg/max-length 200, :pg/type "character varying"}]] [:vector [:string {:max 200, :pg/max-length 200, :pg/type "character varying"}]]]}
```

`h/params-schema` gives the same types as the `[:map ...]` of `sql/format`'s `:params`. Every
key is required, as `sql/format` refuses a missing one:

```clojure
(h/params-schema registry {:update :users :set {:nick :?nick} :where [:= :id :?id]})
;; => [:map [:id [:int #:pg{:type "bigint"}]] [:nick [:maybe [:string {:max 40, :pg/max-length 40, :pg/type "character varying"}]]]]
```

### A row written into a table

Where your code passes a whole row, a map built at run time for `:values` or `:set`,
`h/values-schema` and `h/set-schema` type it. The table is a keyword, as HoneySQL names it
(`:users` in `:schema`, `:app.users`) or as the registry does (`:pg.public/users`). Each column
is typed as an argument assigned to it (what `:send` sends, held to the column's range) and keyed
as your rows are, so a row read and written back fits: the key the `:jdbc` builder labels the
column with, unqualified, where HoneySQL writes that key back to the column (`:group-id` under
a kebab builder); otherwise the column's keyword, or its name as a string for a column with
capitals where names are unquoted (PostgreSQL folds an unquoted name to lower case, and HoneySQL
quotes a string key). A column the database may fill is optional and takes HoneySQL's
`:default` too; one it writes (generated, identity ALWAYS) takes `:default` alone. Where a
BEFORE row trigger runs for the write (`:pg/before-triggers`), every column is optional and
takes nil: the trigger may fill it. In
`h/set-schema` every column is optional and the map has at least one key: HoneySQL writes an
empty `:set` as `SET` alone, which PostgreSQL refuses.

They type values: a keyword other than `:default`, which HoneySQL writes as a column, a vector
or a sequence, which it writes as a row of parameters, and a HoneySQL expression other than
`:default` (`[:cast "closed" :user_status]`,
`[:now]`) is not one. Under pgjdbc's default they still take an enum column's label as a
String, which PostgreSQL refuses (it goes as varchar), as `h/check` reports of a query.
Validate a row under the registry (`{:registry registry}`). The table's CHECKs across columns
are not in it; where they matter, validate an inserted row with `:pg.public.users/insert` too.
An UPDATE's `:set` cannot be checked this way: a CHECK applies to the whole updated row, and
`:set` carries only some of its columns:

```clojure
(m/validate (h/values-schema registry :users) {:email "ann@example.com" :group_id 1} {:registry registry})
;; => true

(m/validate (h/values-schema registry :users {:jdbc {:builder-fn rs/as-unqualified-kebab-maps}})
            {:email "ann@example.com" :group-id 1 :created-at :default}
            {:registry registry})
;; => true

(m/validate (h/set-schema registry :users) {} {:registry registry})
;; => false
```

A function that takes such a row and runs the query declares it in its function schema: the
row's schema in the row argument's place, and as the return, what `h/query-schema` gives for the
query without the row:

```clojure
;; the row argument's :any replaced, the problems of the query kept
(m/schema (mapv #(if (and (vector? %) (= :cat (first %))) (assoc % 2 (h/values-schema registry :users)) %)
                (h/query-schema registry [:db :row] {:insert-into :users :returning [:id]} {:result :one}))
          {:registry registry})
```

### What your application sends

An argument takes its column's own values as pgmalli reads them: the `:time` type of a date or
timestamp, an enum's labels, a `varchar(n)`'s strings. What else reaches the column is decided
by what your application sends, as pgjdbc's `setObject` decides by a value's class: a `Long`
goes as `bigint`, a `String` as `varchar`, a `LocalDate` as `date`, a keyword nowhere unless a
`SettableParameter` extension of yours sends it. `:send` says so, a map of classes to the type
each goes as (a name or alias of [types.md](types.md), an array type `"bigint[]"`, or `:unknown`
for a value sent untyped), merged over pgjdbc's; `nil` takes a class out:

```clojure
(def app-send
  {String :unknown                             ; the connection's stringtype=unspecified
   clojure.lang.Keyword :unknown               ; your extension: (setObject (name k) Types/OTHER)
   clojure.lang.IPersistentVector :unknown     ; and a vector as an array
   java.time.LocalDateTime nil})               ; never sent

(h/arg-types registry {:update :users :set {:status :?status :nick :?nick} :where [:= :id [:any :?ids]]}
             {:send app-send})
;; => {:status [:enum {:default "active", :pg/default "active", :pg/type "user_status"} "active" "closed" :active :closed], :nick [:maybe [:or [:string {:max 40, :pg/max-length 40, :pg/type "character varying"}] :keyword]], :ids [:or [:pg/array [:int #:pg{:type "bigint"}]] [:vector [:int #:pg{:type "bigint"}]]]}
```

pgjdbc's classes are those `pgmalli.impl.accept/class-types` lists: the numbers, `Boolean`,
`String`, `UUID`, `byte[]`, the java.time dates and times, and the arrays pgjdbc encodes. A
value reaches an argument only through its class's entry or one of its superclasses' or
interfaces'.

A class adds to what a column takes where its type is the column's or one the column takes as
its own (a narrower number, another string type, an untyped value to a text or enum column), and
in a cast wherever PostgreSQL casts it. `:unknown` is a value whose text PostgreSQL reads as a
value of the type where it goes: a text or varchar column takes it as a string, an enum or a
text column's CHECK list as its labels (a keyword as a label's name). A json, jsonb or interval
column, where pgmalli knows only the driver's object, takes the classes sent as exactly its type
(`{clojure.lang.IPersistentMap "jsonb"}`); `:any` without one. A json or jsonb column a CHECK
(`jsonb_typeof`) or an [override](../README.md#overrides) shapes takes that shape for them.

An array argument takes a Clojure collection (a vector, a set, a list or other sequential) sent
as an array type or `:unknown`, and a Java array, the column's element type in its elements'
place. Compared (`[:= :id [:any :?ids]]`), PostgreSQL types an untyped array from the column and
reads each element as the column's type; a typed one takes the elements its type compares with.
`unnest` takes a typed array alone, each element assigned to the column (PostgreSQL cannot
resolve `unnest` of an untyped one). Without such a class an array argument is `:any`.

Under pgjdbc's default, which sends a `String` as `varchar`, an enum argument is still typed as
its labels as strings, though PostgreSQL takes no varchar for an enum: `h/check` reports it as
`:uncast-string`.

## The row a query returns

`h/row-schema` gives the `[:map ...]` of one row, as next.jdbc builds it. A column an outer
join may leave NULL is nullable. A set operation (`:union` ...) takes its first member's column
names, keyed as next.jdbc keys a column of no table: unqualified, unless a `:qualifier-fn`
makes a name of `""`; each column is of the type PostgreSQL resolves its members' to, as for
`CASE`. `SELECT *` of a subquery or a CTE lists the columns its SELECT gives:

```clojure
(h/row-schema registry {:select [:u.email :p.title]
                        :from [[:users :u]]
                        :left-join [[:posts :p] [:= :p.author_id :u.id]]})
;; => [:map [:users/email [:string {:max 120, :pg/max-length 120, :pg/type "character varying"}]] [:posts/title [:maybe [:string {:max 200, :pg/max-length 200, :pg/type "character varying"}]]]]

(h/row-schema registry {:select [:id [[:count :*] :n]] :from [:users] :group-by [:id]})
;; => [:map [:users/id [:int #:pg{:identity :always, :type "bigint"}]] [:n [:int #:pg{:type "bigint"}]]]
```

An expression has the type PostgreSQL resolves it to, from the types of what it is made of:

- a literal, as HoneySQL sends it (by its class), or as `[:inline ...]` writes it; an SQL word
  (`current_date`, `current_user` ...) as PostgreSQL's grammar types it
  ([9.9.5](https://www.postgresql.org/docs/current/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT),
  [9.27](https://www.postgresql.org/docs/current/functions-info.html)); a cast, to a type named
  as PostgreSQL prints it or by its alias (`:bigint`, `:timestamptz`, `:double-precision`,
  `(keyword "text[]")`), with its length (`[:varchar 10]`), or to an enum type or domain of the registry;
  `[:interval "1 day"]` and `[:array [...] :text]`.
- `CASE`, `coalesce`, `greatest`, `least`, `ARRAY[...]` and a set operation's columns, of the
  common type of their values
  ([10.5](https://www.postgresql.org/docs/current/typeconv-union-case.html)), NULL and a literal
  of no type left out as PostgreSQL leaves them out; `nullif`, of its first argument as `=` reads
  it.
- A scalar subquery, of its one column's type, nullable: no row is NULL
  ([4.2.11](https://www.postgresql.org/docs/current/sql-expressions.html#SQL-SYNTAX-SCALAR-SUBQUERIES));
  `[[f x]]`, HoneySQL's `(f(x))`, of `f(x)`'s. A recursive CTE's columns are of its first
  member's types wherever it is read
  ([7.8.2](https://www.postgresql.org/docs/current/queries-with.html#QUERIES-WITH-RECURSIVE)).
- A comparison is boolean; so is `x op ANY(array)` (and `SOME`, `ALL`), NULL where an element is
  ([9.24.3](https://www.postgresql.org/docs/current/functions-comparisons.html)).
- A call of a function or an operator of `pg_catalog`, of the type PostgreSQL resolves it to
  ([10.2](https://www.postgresql.org/docs/current/typeconv-oper.html),
  [10.3](https://www.postgresql.org/docs/current/typeconv-func.html), polymorphic types as
  [38.2.5](https://www.postgresql.org/docs/current/extend-type-system.html#EXTEND-TYPES-POLYMORPHIC)
  says): aggregates (with `FILTER`, or `ORDER BY` as `[:array_agg [:order-by :x :y]]`), window
  functions under `:over`, a function returning `void` (`[:any {:pg/type "void"}]`: pgjdbc reads
  its value as a `PGobject`), and a function in FROM,
  whose columns (its OUT arguments, or one named after the alias; `ordinality` under
  `WITH ORDINALITY`) are of no table. pgmalli reads the functions, operators and implicit casts
  from a snapshot of `pg_catalog` (`resources/pgmalli/impl/catalog.edn`) of the PostgreSQL versions it
  supports; a call one of them resolves otherwise is untyped. Of an argument whose type is not
  told, a call is typed where every overload of its number of arguments returns one type that is
  not polymorphic (`count`, `jsonb_build_object`). A function of `json` or `jsonb` that
  builds an object or an array is one under `{:read {:json :parsed}}`.
- A call of a function or an operator of a generated schema, from its file's `:functions` and
  `:operators` ([types.md](types.md#the-generated-file)). pgmalli takes the search path to be
  `pg_catalog`, then `:schema`, as PostgreSQL puts `pg_catalog` first when the path does not
  name it ([5.9.6](https://www.postgresql.org/docs/current/ddl-schemas.html#DDL-SCHEMAS-CATALOG)):
  a schema's overload taking the argument types one of `pg_catalog`'s takes gives way to it, and
  one taking others competes with them ([10.3](https://www.postgresql.org/docs/current/typeconv-func.html)
  step 1), so `round(integer)` of the schema is called for an integer and `lower(text)` is not. A
  call qualified with a schema (`[:app.f x]`) resolves among that schema's alone. An overload
  taking a type the catalog has no category of (a composite type, an extension's) leaves a call
  it does not match exactly untyped, as a cast pgmalli does not read may convert to it. In FROM,
  such a function's columns are its OUT or `TABLE` arguments, its composite type's columns
  (`RETURNS SETOF users`), or one named after the alias, of no table.

An argument among the values of `CASE` and the like leaves the expression untyped unless the
type is a string type, as pgjdbc sends a number or a date typed; in a call, it leaves it untyped
unless every function or operator the call may resolve to gives one type. A column of a domain
is of its base type, a CHECK's list of the base type, where values are computed from it. The
catalog does not say when a function returns NULL, so a call is nullable, but `count`, a window
function of no argument (`row_number`) and a comparison (an operator of a btree operator family,
or its negator) of values that are not NULL. An argument of the application's own type
(an extension's, a composite), a function of a schema the registry has no file of, and anything else is nullable
`:any`:

```clojure
(h/row-schema registry {:select [[[:coalesce :nick :email] :name] [[:max :created_at] :last]
                                 [[:exists {:select [1] :from [:posts]}] :any_posts] [[:cast "active" :user_status] :s]
                                 [[:upper :nick] :shout]]
                        :from [:users]})
;; => [:map [:name [:string #:pg{:type "character varying"}]] [:last [:maybe [inst? #:pg{:type "timestamp with time zone"}]]] [:any_posts [:boolean #:pg{:type "boolean"}]] [:s [:enum #:pg{:type "user_status"} "active" "closed"]] [:shout [:maybe [:string #:pg{:type "text"}]]]]
```

It takes the options of `pgmalli.core/as-read` ([reading-rows.md](reading-rows.md)):

```clojure
(h/row-schema registry {:select [:id :created_at] :from [:users]}
              {:time :instant :jdbc {:builder-fn rs/as-kebab-maps}})
;; => [:map [:users/id [:int #:pg{:identity :always, :type "bigint"}]] [:users/created-at [:time/instant #:pg{:default [:now], :type "timestamp with time zone"}]]]
```

A write with no RETURNING returns the map next.jdbc builds of the driver's update count. Under
next.jdbc's `:return-keys` (in `:jdbc`), pgjdbc adds RETURNING to an INSERT, UPDATE or DELETE:

```clojure
(h/row-schema registry {:delete-from :posts :where [:= :author_id 1]})
;; => [:map [:next.jdbc/update-count :int]]

(h/row-schema registry {:delete-from :posts :where [:= :author_id 1]} {:jdbc {:return-keys ["id"]}})
;; => [:map [:posts/id [:int #:pg{:identity :always, :type "bigint"}]]]
```

Under qualified keys (next.jdbc's default), the driver may qualify some columns by a table the
query data does not show: a column cast to its own type, which PostgreSQL drops; `:raw` SQL,
which may be a column; a column of a table function neither of `pg_catalog` nor of a
generated schema, or of a table the registry lacks; or a
subquery's or a CTE's column that is one of these. `h/row-schema` leaves those keys out of the
map, which is open, and lists their unqualified names under the `:pgmalli/unknown-keys`
property:

```clojure
(h/row-schema registry {:select [:id [[:cast :email :varchar] :e]] :from [:users]})
;; => [:map #:pgmalli{:unknown-keys [:e]} [:users/id [:int #:pg{:identity :always, :type "bigint"}]]]
```

A subquery's or a CTE's column is otherwise keyed by the table PostgreSQL traces it to, as the
driver keys it; an expression's, a set operation's and a recursive CTE's by none.

`h/row-schema` returns nil when a selected column cannot be resolved (`h/check` says which),
when a whole row is selected, when code builds the RETURNING, and for a TRUNCATE under
`:return-keys`, which returns no row:

```clojure
(h/row-schema registry {:select [:u] :from [[:users :u]]})
;; => nil

(h/row-schema registry '{:delete-from :posts :returning cols})
;; => nil

(h/row-schema registry {:truncate :posts} {:jdbc {:return-keys true}})
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

`h/check`, `h/arg-types`, `h/params-schema`, `h/row-schema`, `h/query-schema`, `h/values-schema`,
`h/set-schema` and `h/tables` take the same options map. pgmalli reads what you pass to
next.jdbc and `sql/format` as you pass it; HoneySQL's global settings (`set-dialect!`,
`set-options!`) are not read, so pass them in `:format`.

| option | for |
|---|---|
| `:schema "app"` | the schema of unqualified tables |
| `:jdbc`, `:time`, `:read` | how next.jdbc keys the rows and the driver reads values ([reading-rows.md](reading-rows.md#options)); `:jdbc`'s `:return-keys true` or `["col" ...]` adds RETURNING to a write ([the row a query returns](#the-row-a-query-returns)) |
| `:send {Class type}` | what your application sends, and the type each class goes as: [what your application sends](#what-your-application-sends); pgjdbc's by default |
| `:compare :lenient` | arguments compared with a column take nil too, and so do the elements of one in `:in` or `= ANY` (`:strict` by default) |
| `:format {...}` | the options you pass to `sql/format`; `:values-default-columns`, `:inline`, `:quoted`, `:dialect`, `:quoted-snake` and `:quoted-always` are read |
| `:result :one` | `h/query-schema` only: the return is what `execute-one!` returns |

## Function schemas

`h/query-schema` combines `arg-types` and `row-schema` into
`[:=> [:cat arg ...] [:sequential row]]`, the arguments in the function's order. The return is
what `execute!` returns on a datasource or connection, `:any` when `h/row-schema` gives nil.
`{:result :one}` makes it what `execute-one!` returns:

```clojure
(h/query-schema registry [:id] {:select [:email] :from [:users] :where [:= :id :?id]} {:result :one})
;; => [:=> [:cat [:int #:pg{:type "bigint"}]] [:maybe [:map [:users/email [:string {:max 120, :pg/max-length 120, :pg/type "character varying"}]]]]]

(h/query-schema registry [:id] {:delete-from :users :where [:= :id :?id]} {:result :one})
;; => [:=> [:cat [:int #:pg{:type "bigint"}]] [:map [:next.jdbc/update-count :int]]]
```

Where the data tells how many rows a statement returns, the return says so
(`[:sequential {:max 1} row]`; read the row with `last` or `m/children`, not by position): one
row of a SELECT of aggregates of columns or constants alone (no GROUP BY, HAVING, OFFSET or a
limit a parameter gives); at most one of a statement on one table (no CTE of its name, no table
inheriting it) whose WHERE holds the columns of its primary key or of a UNIQUE constraint or
index (neither partial nor DEFERRABLE) equal to a value; up to one for each row of an INSERT's
`:values` with RETURNING; at most `:limit`. A function that may return a set (one that does, of
`pg_catalog` as `generate_series` or of a generated schema, or one the registry has none of) in the select list, ORDER
BY or `:values` makes it say nothing. Where it is one
row, `{:result :one}` drops the `[:maybe]`:

```clojure
(h/query-schema registry [:db] {:select [[[:count :*] :n]] :from [:users]} {:result :one})
;; => [:=> [:cat :any] [:map [:n [:int #:pg{:type "bigint"}]]]]

(last (h/query-schema registry [:db :id] {:select [:email] :from [:users] :where [:= :id :?id]}))
;; => [:sequential {:max 1} [:map [:users/email [:string {:max 120, :pg/max-length 120, :pg/type "character varying"}]]]]
```

A function returning what `plan`, `execute-batch!`, `:multi-rs` or a prepared statement returns,
or a value made of the rows, needs a return schema of its own.
[README.md](../README.md#function-schemas-for-queries) puts it on a function and instruments it.

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
    (is (= [] problems) (h/humanize problems))))
```

## Stricter than PostgreSQL

pgmalli reports or refuses some of what PostgreSQL runs, where it is almost always a mistake.
Write the query so PostgreSQL's reading is explicit instead.

- An argument takes its column's own values, not all PostgreSQL converts: a `bigint` column
  compared with a `numeric` argument, or a number assigned to a `text` column, PostgreSQL runs
  by converting one side, which hides a mix-up the column's type would catch. Cast it in the
  query (`[:cast :?n :numeric]`) where the conversion is meant.
- A comparison with NULL is never true, so an argument compared with a column does not take nil;
  pass `{:compare :lenient}` where it may be nil on purpose.
- An `=`, `IN` or `IS NOT DISTINCT FROM` of a column a CHECK narrows to a list
  (`CHECK (state IN ('open', 'closed'))`) with a value outside it: an `:enum-literal`. `<>`,
  `NOT IN` and `<` match rows and are not reported; nor is `NOT (state = ...)`.
- An UPDATE, or an ON CONFLICT DO UPDATE, writing NULL into a NOT NULL column: a
  `:not-null-violation`, even when it would touch no row or no row conflicts.

## Not checked

- A search path other than `pg_catalog` and then `:schema` (one naming `pg_catalog` later, or
  several schemas): a call resolves as under that one. A cast the application defines
  (`CREATE CAST`), and an extension's type's category, are not read: a call whose argument is of
  an extension's type is untyped unless an overload takes the type exactly.

- What code in the query (a list with a symbol in it) builds at run time: the vectors written
  inside it are checked, but a column it computes (`(keyword c)`) is not. A list or seq of
  keywords, vectors, maps and values alone (`(map keyword cols)` run) is read as a vector, as
  HoneySQL reads it.
- The tables of `:join-by`.
- A statement keyed by symbols (`'{select [...]}`): symbols are values here, so it is
  reported as `:unread-clause`.
- Values written in the query beyond NULL, enum literals and strings sent typed (`varchar`
  under pgjdbc's default): an empty `[:in :id []]` is not reported (an argument in `:in` is
  typed non-empty).
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
- Under qualified keys, the key of a column next.jdbc qualifies by a table the query data does
  not show: `h/row-schema` lists it under `:pgmalli/unknown-keys`
  ([The row a query returns](#the-row-a-query-returns)).
