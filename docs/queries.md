# HoneySQL queries

Part of [pgmalli](../README.md): [types](types.md) · [reading rows](reading-rows.md) · [queries](queries.md)

`h/analyze` reads a HoneySQL query as data and compares it with the registry. It gives the
types of the arguments (`:args`, [below](#the-types-of-the-arguments)), the row it returns
(`:row`, [below](#the-row-a-query-returns)), how many rows where the data tells (`:rows`,
[below](#function-schemas)), the problems PostgreSQL would report (`:problems`) and the tables it
reads and writes (`:tables`, [below](#the-tables-a-query-uses)), and the columns the query
uses of a `jsonb_populate_record` document (`:document-columns`,
[below](#document-columns)). The examples use
[example/schema.sql](example/schema.sql).

It reads the data you give `sql/format`, as your code has built it. Code in it is a
`:pgmalli.error/not-query-data` error ([stricter than PostgreSQL](#stricter-than-postgresql)),
whose `:in` says where the symbol is, as `get-in` reads it.

```clojure
(require '[pgmalli-next.core :as pgmalli]
         '[pgmalli-next.honeysql :as h]
         '[malli.core :as m]
         '[next.jdbc.result-set :as rs])

(def registry (pgmalli/registry))

(h/analyze registry {:select [:email] :from [:users] :where [:= :id :?id]})
;; => {:args {:id [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]}, :row [:map [:email [:string #:pg{:max-length 120, :type "character varying"}]]], :rows {:min 0, :max 1}, :problems [], :tables {:reads #{"public.users"}, :writes #{}}, :document-columns {}}
```

## What a name refers to

`h/analyze` resolves names the way PostgreSQL does:

- The tables of a statement are those of `:from`, `:using`, `:cross-join`, the joins
  (`:join-by`'s in its order), `:insert-into`, `:update`, `:delete-from` and `:truncate`, under
  their aliases; `{:table t}` is `SELECT * FROM t`.
- `:email` is a column of one of those tables; `:u.email` and `:u/email` a column of the alias
  `u`. A column a subquery does not find is looked for in the enclosing statements.
- A name (a table, an alias, a column, a CTE) is what PostgreSQL reads of what HoneySQL writes
  under `:format`: unquoted, folded to lower case (`:Users` is `users`, `:group-id` is
  `group_id`); quoted (`:quoted`, a dialect), as written (`:Users` is `"Users"`). A string, which
  HoneySQL quotes, is as written.
- A table is looked up in the `:schema` option (`"public"` by default); `:other.t` names its schema.
  A `:schema` the registry holds no generated schema, table or view of is an error
  naming the ones it holds; where the default `"public"` is one it lacks, an unqualified table is an
  `:unknown-table` naming the schema that holds it.
- A `:with` entry is a table its later entries and the main statement see.
- A subquery in `:from` or `:join` sees the outer query's tables, not its siblings; a LATERAL
  one sees the tables before it.
- `USING` merges the columns it names; `:users` alone in `:select` is the whole row.

```clojure
(:problems (h/analyze registry {:select [:p.id]
                               :from [[:posts :p]]
                               :join [[:users :u] [:= :u.id :p.author_id]]
                               :where [:= :u.title "x"]}))
;; => [{:kind :unknown-column, :column :u.title}]

(:problems (h/analyze registry {:select [:id] :from [:accounts]}))
;; => [{:kind :unknown-table, :table "public.accounts"}]
```

A qualifier that names no table or alias of the statement is the problem, under `:qualifier`. A
table under an alias is named by its alias alone, as PostgreSQL says:

```clojure
(:problems (h/analyze registry {:select [:users.email] :from [[:users :u]]}))
;; => [{:kind :unknown-column, :column :users.email, :qualifier "users", :did-you-mean [:u.email]}]
```

A problem in a subquery or CTE carries `:in`, its path into the query:

```clojure
(def q {:select [:id] :from [[{:select [:id] :from [:users] :where [:= :mail 1]} :s]]})

(:problems (h/analyze registry q))
;; => [{:kind :unknown-column, :column :mail, :did-you-mean [:email], :in [:from 0 0]}]

(get-in q [:from 0 0])
;; => {:select [:id], :from [:users], :where [:= :mail 1]}
```

A clause pgmalli does not read is reported rather than passed over:

```clojure
(:problems (h/analyze registry {:select [:id] :from [:users] :frobnicate [:x]}))
;; => [{:kind :unread-clause, :clause :frobnicate}]

(:problems (h/analyze registry {:selec [:id] :from [:users]}))
;; => [{:kind :unread-clause, :clause :selec, :did-you-mean [:select]}]
```

The columns of a CTE or a subquery are typed as its SELECT types them, read as a query of its
own (a CTE under the ones before it, a subquery under the CTEs of the query it reads, a LATERAL
subquery with the tables before it, a recursive CTE as its first member, NULL allowed); a
column it does not give is `:unknown-column`. A table or
view of `pg_catalog` (`pg_class`) the registry lacks has the columns of the catalog snapshot as
of the PostgreSQL version the registry was generated on (the file's `:database-version`; every
version the snapshot holds where it is not one of them): a
column whose type the versions disagree on is `:any`, and one a version lacks is an optional key
of `SELECT *`. A function of `pg_catalog` or of a generated schema in FROM
has the columns it returns ([The row a query returns](#the-row-a-query-returns)). The columns of
another table function (named by its alias, `WITH ORDINALITY` too) and of an
`information_schema` view the registry lacks exist but have no type. Comparisons with them, or with an expression `:row` leaves untyped, are not
checked. Every table has the system columns (`ctid`, `xmin`, ...); a view has none.

## Writes

An INSERT or UPDATE is checked against what PostgreSQL refuses to write:

```clojure
(:problems (h/analyze registry {:update :users :set {:email nil} :where [:= :id 1]}))
;; => [{:kind :not-null-violation, :table "public.users", :column :email, :clause :set}]

(:problems (h/analyze registry {:update :posts :set {:title_upper "X"} :where [:= :id 1]}))
;; => [{:kind :read-only-column, :table "public.posts", :column :title_upper}]
```

NULL is any of `nil`, `:null` (in any case), `[:inline nil]` and `[:lift nil]`; a `:null` the
format quotes (`:quoted`, a `:dialect`, a `:quoted-always` matching it) is a column named `null`. HoneySQL also writes NULL
for a key one map of `:values` lacks and another has. `users.status`
has a default, but the first row below gets NULL:

```clojure
(:problems (h/analyze registry {:insert-into :users
                               :values [{:group_id 1 :email "ann@example.com"}
                                        {:group_id 1 :email "bob@example.com" :status [:cast "closed" :user_status] :closed_at [:now]}]}))
;; => [{:kind :not-null-violation, :table "public.users", :column :status, :clause :values}]
```

Give `h/analyze` the options you give `sql/format`, as `:format`:

```clojure
(:problems (h/analyze registry {:insert-into :users
                               :values [{:group_id 1 :email "ann@example.com"}
                                        {:group_id 1 :email "bob@example.com" :status [:cast "closed" :user_status] :closed_at [:now]}]}
                     {:format {:values-default-columns #{:status :closed_at}}}))
;; => []
```

A table with a BEFORE row trigger for the statement (`:pg/before-triggers`) gets neither
`:not-null-violation` nor `:missing-required-column`: the trigger may fill the row.

Read, `:t.text` (or `name`, `varchar`, `bpchar`) where `t` has no column of that name is `t`'s
row cast to the type, as PostgreSQL reads `text(t)`: no `:unknown-column`.

A target `:a.b` (`{:set {:p.x 1}}`, `INSERT INTO t (p.x)`) is the field `b` of the composite
column `a`, as PostgreSQL reads it: `a` must be a column of the table, and what goes into the
field is not checked.

`:problems` does not see what an argument holds; [`:args`](#the-types-of-the-arguments)
types it.

## The types of the arguments

`:args` is `{arg schema}` for every named parameter of a query (`:?id`, `[:param :id]`),
keyed as in `sql/format`'s `:params`, as the one sending sends them
([what your application sends](#what-your-application-sends)):

```clojure
(:args (h/analyze registry {:select [:id]
                            :from [:users]
                            :where [:and [:= :group_id :?group] [:in :status :?statuses]]
                            :limit :?n}))
;; => {:group [:or [:int #:pg{:type "integer"}] decimal? integer? :double float?], :n [:maybe [:int {:pg/type "bigint", :min 0}]], :statuses [:or [:sequential {:min 1} [:enum {:default :active, :pg/default "active", :pg/type "user_status"} :active :closed]] [:set {:min 1} [:enum {:default :active, :pg/default "active", :pg/type "user_status"} :active :closed]]]}
```

| where the argument is | its type |
|---|---|
| compared with a column, in any clause (a condition, a select, ORDER BY, SET, RETURNING) | the column's kind without NULL: an enum type's labels kept (as keywords, [what your application sends](#what-your-application-sends)); a `PGobject` (or a subclass's instance) too where the driver reads the column as one (`json`, `jsonb`, `citext`, a domain over them), which pgjdbc sends as the type it names; a `bit` or `bit varying` column's a `PGobject` alone, as no class goes as a bit string (a `String` goes as `varchar`); an array of a domain (`d[]`) a `PGobject` of `d[]` alone |
| compared with an expression `:row` types, or with a column in a row (`[:< [:composite :a :b] [:composite :?a :?b]]`) | as with a column, of the expression's type |
| in `:in` | a non-empty vector, list or set of the column's type |
| cast (`[:cast :?at :timestamptz]`, `[:cast :?ids [:raw "bigint[]"]]` to an array type), or an element of an array cast (`[:array [:?k] :text]`, `ARRAY[?]::text[]`) | what PostgreSQL casts to the type, of what [your application sends](#what-your-application-sends), without the type's range where every use of the cast is compared (PostgreSQL evaluates the cast only where its plan reaches it): a string too, to an array type a Java array of what a cast to its element type takes, or nil (to a domain over an array type any array), an enum type's labels alone, a `bytea` and an integer to each other as PostgreSQL 18 casts them (16 and 17 refuse it); no map or collection sent as `json` or `jsonb` to a type other than those and the string types (PostgreSQL casts a `jsonb` number or boolean, and errors at run time on an object or array); no collection sent untyped (`:unknown`) to a number or boolean type, whose input refuses an array literal (`{1}`; a date, time or `uuid` type reads `{2020-01-01}` past its braces); `:any` for a type whose values pgmalli does not type (json, inet) or an extension's type, whose casts `pg_catalog` does not hold; nil too, unless every use of the cast is compared (NULL matching no row; an operand of `=`, `<`, `IN`, `BETWEEN` ..., not of `IS [NOT] DISTINCT FROM` or with NULL) or assigned where nil may not go (as assigned below) |
| assigned to a column (`:set`, `:values`, an item of `INSERT INTO t (cols) SELECT`) | the column's type as malli's default registry reads it (a `PGobject` too, as compared), NULL allowed when the column allows it, a BEFORE row trigger for the write may fill it (under `ON CONFLICT DO UPDATE`, an inserted value only for a BEFORE INSERT trigger, as PostgreSQL checks NOT NULL before the conflict), or the query tests the argument for NULL ([below](#the-types-of-the-arguments)), whatever rows the SELECT of `INSERT ... SELECT` drops; under `[:unnest :?xs]`, an array of it, a Java array ([what your application sends](#what-your-application-sends)) |
| the document of `jsonb_populate_record`, `json_populate_record` or their `recordset`, bare or cast to `jsonb` or `json`, of a table's or view's row type: `NULL` cast to it (`[[:jsonb_populate_recordset [:cast nil :positions] :?rows] :r]`) or an alias's whole row (`[:jsonb_populate_record :p [:cast :?row :jsonb]]`, `p` the alias of `UPDATE positions p`); or of `jsonb_to_record`, `json_to_record` or their `recordset` in `FROM`, whose alias defines the row's columns (`[[:jsonb_to_recordset :?words] [:w {:with-columns [[:pattern :text] [:role_keys :text :array]]}]]`, each of the type a cast to it names, `:array` an array of it; the row's columns too, nullable) | for a record a map, for a recordset a vector of them, in place of a map or vector the parameter takes there (bare, what goes where the function takes `jsonb` or `json`; cast, what the cast takes), or nil. The map is closed: its keys the names of the row type's columns (`:any` the value of a type pgmalli does not read), each as a keyword or a string (`:sort_order` or `"sort_order"`, not both), each optional, and each value nil or what the column's type's input reads of the JSON value the one sending writes: a `json` or `jsonb` column's value as read (its override's shape), an array column's a sequential of its elements' values or a string, the array's text (`"{1,2}"`); another column's a JSON number (an integer type's input reads no fraction), boolean or string: a `String` of any type, the column's range and enum labels kept, the others where they go untyped or as their type (an `Instant`, a `LocalDate` or a `LocalDateTime` to a date or timestamp column, a `UUID` to a `uuid` one); no `Character` or `PGobject`, which the document does not hold. Where `INSERT INTO t (cols) SELECT` writes a column of `t` from the populated column of its name, a key whose column NULL may not go to is required, its value not nil |
| an argument of an `unnest` in `FROM` (`[[:unnest :?ids] [:x {:columns [:id]}]]`, `[[:unnest :?ids] :id]`) | a Java array of where its column goes, as an argument there: compared, assigned, or cast (an array of what PostgreSQL casts to the type), in a subquery too. Cast to an array of a column's type (`[[:unnest [:cast :?ns [:raw "varchar[]"]]] [:x {:columns [:n]}]]`, its column assigned to a `varchar(50)` column, a domain over `varchar` too, by `SELECT *` or by name), each element as assigned there, or else as cast to the type (a String to a `bigint`); cast to another type, as cast |
| an argument of `coalesce`, `greatest` or `least`, or a value of `CASE` (`[:coalesce :?nick :email]`) | the type the other arguments resolve to, and what PostgreSQL resolves with it to one type (one of its category, `select_common_type`), or nil; of `nullif`, what `=` takes beside the other argument, or nil |
| an argument of a function or an operator of `pg_catalog` or of a generated schema (`[:+ :created_at :?d]`, `[:repeat "a" :?n]`) | the type each function or operator PostgreSQL may resolve the call to takes there, and what PostgreSQL converts to that type implicitly, or nil, by the type each class goes as (`[:repeat "a" :?n]` takes an `Integer`, a `Short` or a `Byte`, `[:and :int [:pg/class ...]]`, not a `Long`, which goes as `bigint`: send `(int n)` or cast, `[:cast :?n :integer]`): a value of each type picks its own (`generate_series(1, ?)` takes an integer, a bigint or a numeric), a `PGobject` where one takes a type whose values pgmalli does not type (`inet`, for `integer + ?`); where PostgreSQL takes the type from the value alone, a value sent typed alone (below) |
| compared with `ANY`, `SOME` or `ALL` of an array (`[:= :?tag [:any :tags]]`) | as with a column of the array's element type |
| a `LIKE` pattern | the column's kind (`:string`) |
| in `:limit`, `:offset` | an integer of at least 0, or nil for none |
| tested with `IS [NOT] TRUE`, `FALSE` or `UNKNOWN` (`[:is-not :?x true]`; not `IS NULL`, which PostgreSQL types nothing by) | a boolean, or nil |
| anywhere else | `:any` |

An argument pgmalli gives no type is `[:any {:pgmalli/untyped why}]`, `why` one of `:position`
(no use of it is one the table above types: an argument of a function the registry lacks, or of
a polymorphic one under `:inline`, is one) and `:type` (it is compared with or cast to what pgmalli knows no type
of: the result of a function the registry lacks, a column of a table it lacks, an extension's
type). An argument where a value of a type pgmalli does not type goes (compared, assigned, or a
function's argument as above) takes a `PGobject` where pgjdbc reads one (a geometric, network or timespan type: `inet`,
`interval`), which it sends as the type it names, and what converts to the type implicitly (a
`LocalTime` to `interval`); otherwise (cast, `json`, `xml`, `money`) it is `[:any {:pg/type ...}]`,
without `:pgmalli/untyped`:

```clojure
(:args (h/analyze registry {:select [[:?label :label]] :from [:users]}))
;; => {:label [:any #:pgmalli{:untyped :position}]}
```

A column's type takes its own values, and what [your application sends](#what-your-application-sends)
that PostgreSQL converts to the column's type implicitly (`pg_cast`'s implicit casts: a `Long` to
`numeric`, an `Integer` to `bigint`). Compared, it also takes what the column's type converts
to implicitly, where PostgreSQL's `=` then compares the two (a `bigint` column with a
`BigDecimal`, a `date` one with an `OffsetDateTime`). PostgreSQL takes more ([stricter than
PostgreSQL](#stricter-than-postgresql)). Assigned or cast, a number is held to a number column's
range; cast to an integer type, a fraction is held to the range half a unit wider, as PostgreSQL
rounds it (a value at exactly half a unit past may round out of range):

```clojure
(:args (h/analyze registry {:select [:id] :from [:users] :where [:> :created_at [:cast :?since :timestamptz]]}))
;; => {:since [:or [:time/instant #:pg{:type "timestamp with time zone"}] :time/offset-date-time :string :time/local-date :time/local-date-time :keyword]}
```

PostgreSQL types a parameter once, so an argument of several uses takes the values each of them
takes: a cast and a comparison, what the comparison takes of what casts. It takes nil only where
every use does, a NULL test aside (below); a use pgmalli does not type takes any value. Where the
uses have no value in common, or their common values cannot be told, the schema is `[:and ...]`
of the uses' (described `a & b`), which takes a value each of them takes.

A comparison leaves out a range: PostgreSQL compares a value beyond it and matches no row.

```clojure
(:args (h/analyze registry {:update :users :set {:nick :?nick} :where [:= :id :?id]}))
;; => {:nick [:maybe [:or [:string #:pg{:max-length 40, :type "character varying"}] :keyword]], :id [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]}
```

A comparison with NULL is never true (it matches no row), so `:?id` above does not take nil.
Compare NULL in the query body: `[:= :id nil]` (HoneySQL writes `IS NULL`), or
`IS NOT DISTINCT FROM`, which matches NULL with NULL and so takes nil:

```clojure
(:args (h/analyze registry {:select [:id] :from [:users] :where [:is-not-distinct-from :id :?id]}))
;; => {:id [:maybe [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]]}
```

An argument the query also tests for NULL takes nil whatever its other uses, as an optional
filter does (`NULL IS NULL OR id = NULL`): under `{:format {:inline true}}`, or where the test
casts it (`[:= [:cast :?id :bigint] nil]`). Sent bare as a parameter it does not: PostgreSQL
refuses a NULL parameter in `? IS NULL`, whose type nothing tells.

```clojure
(:args (h/analyze registry {:select [:id] :from [:users] :where [:or [:= :?id nil] [:= :id :?id]]} {:format {:inline true}}))
;; => {:id [:maybe [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]]}
```

```clojure
(:args (h/analyze registry {:select [:id] :from [:users] :where [:= [:cast :id :text] :?id]}))
;; => {:id [:or [:string #:pg{:type "text"}] :keyword]}

(:args (h/analyze registry {:insert-into [[:posts [:author_id :title]] {:select [[:?author] [[:unnest :?titles]]]}]}))
;; => {:titles [:pg/array #:pgmalli{:type-from-value "unnest"} [:string #:pg{:max-length 200, :type "character varying"}]], :author [:int #:pg{:type "bigint"}]}
```

The `[:map ...]` of `sql/format`'s `:params` is `:args` in a map. Every key is required, as
`sql/format` refuses a missing one:

```clojure
(into [:map] (sort-by key (:args (h/analyze registry {:update :users :set {:nick :?nick} :where [:= :id :?id]}))))
;; => [:map [:id [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]] [:nick [:maybe [:or [:string #:pg{:max-length 40, :type "character varying"}] :keyword]]]]
```

### A row written into a table

Where your code passes a whole row, a map built at run time for `:values` or `:set`,
`h/values-schema` and `h/set-schema` type it. The table is a keyword, as HoneySQL names it
(`:users` in `:schema`, `:app.users`) or as the registry does (`:pg.public/users`). Each column
is typed as an argument assigned to it (what your application sends, held to the column's range) and keyed
by the column's name (`:group_id`), the one key a row is read with
([the builder](reading-rows.md#the-builder)), so a row read is written back as it is, but for an enum
column, read as a String, which pgjdbc sends as `varchar` (write its label as `[:lift :happy]`
or a `PGobject`), a `bit(1)` column, read as a `Boolean` (write a `PGobject` of `bit`), and an
array column, read as a vector (write a Java array); another
spelling (`:group-id`) is refused. Where HoneySQL would not write that
keyword to the column under `:format`, the key is the name as a string
([what a name refers to](#what-a-name-refers-to)). A column the database may fill is optional and takes
DEFAULT too: `[:default]`, or `:default` where the format writes it as the word (not where the format quotes it); one it writes (generated, identity ALWAYS) takes DEFAULT alone. Where a
BEFORE row trigger runs for the write (`:pg/before-triggers`), every column is optional and
takes nil: the trigger may fill it. In
`h/set-schema` every column is optional and the map has at least one key: HoneySQL writes an
empty `:set` as `SET` alone, which PostgreSQL refuses.

A column takes a value, not an expression: write `[:now]`, `[:+ :n 1]` or `:current_timestamp`
in the query body, not in a row. `[:lift v]` is a value too, which HoneySQL sends as one
parameter, `v` of the column's type: an enum label goes as `[:lift :active]`, as HoneySQL
writes a bare keyword as a column. A vector or list not headed by `:lift` is a row of
parameters, which no column takes. `mg/generate` gives values alone, and leaves out a column
of whose values malli generates none (a `PGobject`).
Validate a row under the registry (`{:registry registry}`):

```clojure
(m/validate (h/values-schema registry :users) {:email "ann@example.com" :group_id 1} {:registry registry})
;; => true

(m/validate (h/values-schema registry :users) {:email "ann@example.com" :group_id 1 :created_at :default} {:registry registry})
;; => true

(m/validate (h/values-schema registry :users) {:email "ann@example.com" :group-id 1} {:registry registry})
;; => false

(m/validate (h/set-schema registry :users) {} {:registry registry})
;; => false
```

### What your application sends

An argument takes its column's own values as pgmalli reads them: the `:time` type of a date or
timestamp, an enum's labels, a `varchar`'s strings. What else reaches the column is decided
by the one sending, which your application installs
([reading-rows.md](reading-rows.md#the-one-reading-and-sending)). Each class goes as one type,
`:unknown` for a value sent untyped, which PostgreSQL types from where it goes:

| class | sent as |
|---|---|
| `Long`, `Integer`, `Short`, `Byte` | `bigint`, `integer`, `smallint`, `smallint` |
| `BigDecimal`, `BigInt`, `BigInteger` | `numeric` |
| `Double`, `Float` | `double precision`, `real` |
| `Boolean`, `String`, `Character`, `UUID`, `byte[]` | `boolean`, `varchar`, `varchar`, `uuid`, `bytea` |
| `LocalDate`, `LocalTime`, `LocalDateTime` | `date`, `time`, `timestamp` |
| `OffsetTime`, `OffsetDateTime`, `Instant` | `timetz`, `timestamptz`, `timestamptz` (an `Instant` as an `OffsetDateTime` in UTC) |
| a keyword | untyped, its text (`:ns/a` as `ns/a`) |
| a map, a vector | `jsonb` |
| a Java array (`long[]`, `String[]` ...) | an array of its elements' type (`bigint[]`, `varchar[]`); a `java.sql.Date[]` a `date[]`, a `java.sql.Timestamp[]` a `timestamptz[]` (pgjdbc sends no array of java.time's; a `java.sql.Time[]`, a `timetz[]` of the JVM's offset, is sent nowhere) |
| a `PGobject` | the type it names |

Another class (a set, a list, a symbol; a `java.util.Date`, which pgjdbc refuses, and java.sql's
`Date`, `Time` and `Timestamp`, whose text pgjdbc writes in the JVM's time zone) is sent nowhere. `pgmalli-next.impl.accept/classes` holds
this table. A value reaches an argument through its class's row or one of its superclasses' or
interfaces'.

```clojure
(:args (h/analyze registry {:update :users :set {:status :?status :nick :?nick} :where [:= :id [:any :?ids]]}))
;; => {:nick [:maybe [:or [:string #:pg{:max-length 40, :type "character varying"}] :keyword]], :ids [:pg/array [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]], :status [:enum {:default :active, :pg/default "active", :pg/type "user_status"} :active :closed]}
```

A class adds to what a column takes where its type is the column's or one it takes
([above](#the-types-of-the-arguments)), and in a cast wherever PostgreSQL casts it. A
value sent untyped goes where its text is of the column's type's category: a keyword to a
string column as a string, to an enum as its labels. It goes to a
`bytea` or `"char"` column, whose input takes any text, and to an extension's type pgmalli does
not type (compared, one whose `=` pgmalli's catalog holds, `citext`). Cast, a keyword goes to
any type, as a string does. A jsonb column takes a map and a
vector, held to the shape an [override](../README.md#overrides) gives it; a json or interval column, where pgmalli knows only the driver's object, takes a
`PGobject`.

An array argument takes a Java array, the column's element type in its elements' place: a
vector goes as `jsonb`, no array. Compared (`[:= :id [:any :?ids]]`), it takes the elements its
type compares with; under `unnest`, each element assigned to the column. Compared with an array
column whole (`=`, `&&`, `@>`, whose `anyarray` takes an array of one type alone), it takes a
Java array the one sending sends as the column's type alone (an `int[]` column an `Integer[]` or
an `int[]`, no `long[]`): where none goes so (a `text[]` column, a `String[]` going as
`varchar[]`), a `PGobject` of the type, or cast it (`[:cast :?tags [:raw "text[]"]]`). So does
one compared with `ANY` where no Java array goes as the element type's array (an enum's:
`[:= :mood [:any :?ms]]` takes a `PGobject` of `mood[]`, its elements' schema under
`:pgmalli/element`; cast, `[:cast :?ms [:raw "mood[]"]]`, an array of the labels). Elsewhere,
without such a class, an array argument is `:any`.

An enum argument takes its labels as keywords, which go untyped, or as strings cast
(`[:cast :?m :user_status]`): PostgreSQL neither compares a `varchar` with an enum nor assigns
one to it. `:problems` reports a string literal there as `:uncast-string`.

Where PostgreSQL takes a parameter's type from the value alone, an argument takes only a value
sent with a type the call resolves with, and not nil: PostgreSQL refuses an untyped value there
(`could not determine data type of parameter`, `could not determine polymorphic type`, `not
unique`). That is a function's `"any"` argument (`concat`, `format`, `jsonb_build_object`,
`json_build_array`, `num_nulls`), a polymorphic one nothing else binds (`cardinality(?)`,
`array_to_string(?, ',')`), and one whose candidates share no one type for an untyped value
(`unnest(?)`: `anyarray`, `anymultirange`, `tsvector`; `date_trunc('day', ?)`). Its schema
carries `:pgmalli/type-from-value`, the function's name, for a sender of its own (one sending an
array as untyped text, `'{1,2}'`) to tell. Where its other uses take only values sent untyped
(an enum column's keyword label), it is an `:undetermined-type` problem: cast it in the query
(`[:cast :?m :mood]`, `[:cast :?ids [:raw "bigint[]"]]`).

```clojure
(let [t (:suffix (:args (h/analyze registry {:select [[[:concat :email :?suffix] :s]] :from [:users]})))]
  (mapv #(m/validate t % {:registry registry}) ["-x" 1 :x nil]))
;; => [true true false false]
```

## The row a query returns

`:row` is the `[:map ...]` of one row, as next.jdbc's `as-unqualified-maps` builds
it ([the builder](reading-rows.md#the-builder)): keyed by the labels of its columns, a label
of several columns one key, the last column's (a `:duplicate-column`, [stricter than
PostgreSQL](#stricter-than-postgresql)). A column an outer
join may leave NULL is nullable. A set operation (`:union` ...) takes its first member's column
names; each column is of the type PostgreSQL resolves its members' to, as for
`CASE`. `SELECT *` of a subquery or a CTE lists the columns its SELECT gives:

```clojure
(:row (h/analyze registry {:select [:u.email :p.title]
                           :from [[:users :u]]
                           :left-join [[:posts :p] [:= :p.author_id :u.id]]}))
;; => [:map [:email [:string #:pg{:max-length 120, :type "character varying"}]] [:title [:maybe [:string #:pg{:max-length 200, :type "character varying"}]]]]

(:row (h/analyze registry {:select [:id [[:count :*] :n]] :from [:users] :group-by [:id]}))
;; => [:map [:id [:int #:pg{:identity :always, :type "bigint"}]] [:n [:int #:pg{:type "bigint"}]]]
```

An expression has the type PostgreSQL resolves it to, from the types of what it is made of:

- a literal, as HoneySQL sends it (by its class), or as `[:inline ...]` writes it; an SQL word
  (`current_date`, `current_user` ...) as PostgreSQL's grammar types it, `current_schema` and
  `system_user` nullable ([9.9.5](https://www.postgresql.org/docs/current/functions-datetime.html#FUNCTIONS-DATETIME-CURRENT),
  [9.27](https://www.postgresql.org/docs/current/functions-info.html)); a cast, to a type named
  as PostgreSQL prints it or by its alias (`:bigint`, `:timestamptz`, `:double-precision`,
  `(keyword "text[]")`), with its length (`[:varchar 10]`), or to an enum type of the registry (a cast to a domain is untyped),
  or written `[:raw "bigint[]"]`, one type's name with or without a length and `[]` (the
  reader reads `:bigint[]` as `:bigint` and `[]`, which HoneySQL writes `BIGINT`); and a call of a type's name no function takes the argument of (`[:int4 :x]`, `[:timestamptz :?at]`),
  which PostgreSQL reads as a cast;
  `[:interval "1 day"]` and `[:array [...] :text]`.
- `CASE`, `coalesce`, `greatest`, `least`, `ARRAY[...]` and a set operation's columns, of the
  common type of their values
  ([10.5](https://www.postgresql.org/docs/current/typeconv-union-case.html)), NULL and a literal
  of no type left out as PostgreSQL leaves them out (a set operation's two members at a time
  from the left: `NULL UNION NULL` is `text`, a domain united with NULL its base type); `nullif`, of its first argument as `=` reads it.
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
  `WITH ORDINALITY`) are of no table; of `jsonb_populate_record`, `json_populate_record` and
  their `recordset` of a table's row type (`NULL::t`, or an alias's whole row), the table's
  columns, nullable. pgmalli reads the functions, operators and implicit casts
  from a snapshot of `pg_catalog` (`resources/pgmalli-next/impl/catalog.edn`) of the PostgreSQL versions it
  supports, as of the version the registry was generated on (as for `pg_catalog`'s
  tables above): a call one of them resolves otherwise is untyped, and a function of PostgreSQL
  18 alone (`uuidv7`) is typed for a registry generated on 18. Of an argument whose type is not
  told, a call is typed where every overload of its number of arguments returns one type that is
  not polymorphic (`count`, `jsonb_build_object`). Read parsed, a function of `pg_catalog` that
  builds a `json` or `jsonb` object is a map, one that builds an array (`jsonb_agg`) a sequential.
  Of a `json` or `jsonb` column an override shapes, `->` of an integer literal and
  `json_array_elements` or `jsonb_array_elements` of a sequential is its element's shape, `->` of a string a map's key's,
  `-` of a string or an ARRAY of strings a map's without those keys (a map-of's as it is); any
  other call's value, and coalesce or CASE of it with another value, is of no shape.
- A call of a function or an operator of a generated schema, from the file's `:functions` and
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
  (`RETURNS SETOF users`), or one named after the alias, of no table; `unnest(a, b)` has a
  column of each array's elements.

An argument among the values of `CASE` and the like leaves the expression untyped unless the
type is a string type, as pgjdbc sends a number or a date typed; in a call, it leaves it untyped
unless every function or operator the call may resolve to gives one type. A value computed from
a column of a domain is of the domain's base type. The
catalog does not say when a function returns NULL, so a call is nullable, but `count`, a window
function of no argument (`row_number`) and a comparison (an operator of a btree operator family,
or its negator) of values that are not NULL. An argument of the application's own type
(an extension's, a composite), a function of a schema not generated into the registry, and anything else is nullable
`:any`:

```clojure
(:row (h/analyze registry {:select [[[:coalesce :nick :email] :name] [[:max :created_at] :last]
                                    [[:exists {:select [1] :from [:posts]}] :any_posts] [[:cast "active" :user_status] :s]
                                    [[:upper :nick] :shout]]
                           :from [:users]}))
;; => [:map [:name [:string #:pg{:type "character varying"}]] [:last [:maybe [:time/instant #:pg{:type "timestamp with time zone"}]]] [:any_posts [:boolean #:pg{:type "boolean"}]] [:s [:enum #:pg{:type "user_status"} "active" "closed"]] [:shout [:maybe [:string #:pg{:type "text"}]]]]
```

Its columns are read as `pgmalli-next.core/as-read` reads them ([the one reading](reading-rows.md#the-one-reading-and-sending)):

```clojure
(:row (h/analyze registry {:select [:id :created_at] :from [:users]}))
;; => [:map [:id [:int #:pg{:identity :always, :type "bigint"}]] [:created_at [:time/instant #:pg{:default {:sql "now()"}, :type "timestamp with time zone"}]]]
```

A write with no RETURNING returns the map next.jdbc builds of the driver's update count. Under
next.jdbc's `:return-keys` (in `:jdbc`), pgjdbc adds RETURNING to an INSERT, UPDATE or DELETE:

```clojure
(:row (h/analyze registry {:delete-from :posts :where [:= :author_id 1]}))
;; => [:map [:next.jdbc/update-count :int]]

(:row (h/analyze registry {:delete-from :posts :where [:= :author_id 1]} {:jdbc {:return-keys ["id"]}}))
;; => [:map [:id [:int #:pg{:identity :always, :type "bigint"}]]]
```

`:row` is nil when a selected column cannot be resolved (`:problems` says which),
when a whole row is selected, and for a TRUNCATE under
`:return-keys`, which returns no row:

```clojure
(:row (h/analyze registry {:select [:u] :from [[:users :u]]}))
;; => nil

(:row (h/analyze registry {:truncate :posts} {:jdbc {:return-keys true}}))
;; => nil
```

## The tables a query uses

`:tables` is the tables and views a query reads and writes, in every subquery and CTE,
with aliases and CTE names resolved. Use it to find the queries a change to a table affects.

```clojure
(:tables (h/analyze registry {:update :users :set {:nick "a"} :from [[:groups :g]] :where [:= :g.id :users.group_id]}))
;; => {:reads #{"public.groups"}, :writes #{"public.users"}}
```

## Document columns

`:document-columns` is, for each argument that is the document of `jsonb_populate_record`,
`jsonb_to_record` and the like ([its type](#the-types-of-the-arguments)), the
columns of the row type the query uses of the call: those named of its alias (`r.nick`, or an
unqualified `nick` of it) anywhere in the statement that reads it or one nested in it. The
document's type takes every column's key, as PostgreSQL ignores a key no column of the query
reads; compare a document's keys with these where such a key is a mistake. It is nil where a
use names no column in particular (`r.*`, `SELECT *`, the whole row `r`, `:raw`, columns the
alias lists).

```clojure
(:document-columns (h/analyze registry {:update [:users :p]
                                        :set {[:composite :nick :status] {:select [:r.nick :r.status]
                                                                          :from [[[:jsonb_populate_record :p [:cast :?row :jsonb]] :r]]}}
                                        :where [:= :p.id :?id]}))
;; => {:row #{:nick :status}}

(:document-columns (h/analyze registry {:select [:r.*] :from [[[:jsonb_populate_record [:cast nil :users] :?doc] :r]]}))
;; => {:doc nil}
```

## Options

`h/analyze`, `h/query-schema`, `h/values-schema` and `h/set-schema` take these options; an option a
function does not read is an error. pgmalli reads what you pass to
next.jdbc and `sql/format` as you pass it. pgmalli caches how HoneySQL writes a name for each
`:format`, so a global setting (`set-dialect!`, `set-options!`) changed after the first analysis
is not seen; pass them in `:format`.

| option | for |
|---|---|
| `:schema "app"` | the schema of unqualified tables |
| `:jdbc` | the options you pass to next.jdbc ([reading-rows.md](reading-rows.md#the-options-of-nextjdbc)); its `:return-keys true` or `["col" ...]` adds RETURNING to a write ([the row a query returns](#the-row-a-query-returns)) |
| `:format {...}` | the options you pass to `sql/format`; `:values-default-columns`, `:inline`, `:quoted`, `:dialect`, `:quoted-snake` and `:quoted-always` are read, a name (an alias, a key of `:values`) as the loaded HoneySQL writes it under them |
| `:result :one` | `h/query-schema`'s alone: the return is what `execute-one!` returns |

## Function schemas

`h/query-schema` combines `:args` and `:row` into
`[:=> {:pgmalli/problems [...]} [:cat arg ...] [:sequential row]]`, the arguments in the function's order. The return is
what `execute!` returns on a datasource or connection, `:any` where `:row` is nil.
`{:result :one}` makes it what `execute-one!` returns:

```clojure
(h/query-schema registry [:id] {:select [:email] :from [:users] :where [:= :id :?id]} {:result :one})
;; => [:=> #:pgmalli{:problems []} [:cat [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]] [:maybe [:map [:email [:string #:pg{:max-length 120, :type "character varying"}]]]]]

(h/query-schema registry [:id] {:delete-from :users :where [:= :id :?id]} {:result :one})
;; => [:=> #:pgmalli{:problems []} [:cat [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]] [:map [:next.jdbc/update-count :int]]]
```

Where the data tells how many rows a statement returns, the return says so
(`[:sequential {:max 1} row]`; read the row with `last` or `m/children`, not by position): one
row of a SELECT of aggregates of columns or constants alone (no GROUP BY, HAVING, OFFSET or a
limit a parameter gives), and of a SELECT without FROM, its one row one group under GROUP BY (at
most one with a WHERE or HAVING); a subquery
or EXISTS in the select list is one value; at most one of a statement on one table (no CTE of its name, no table
inheriting it) whose WHERE holds the columns of its primary key or of a UNIQUE constraint or
index (neither partial nor DEFERRABLE) equal to a value, and of one joining (JOIN or LEFT JOIN) or
listing (DELETE's `:using`, UPDATE's `:from`) other tables, each so held to one row, found from
any table until no more is: a key's columns each equal to a constant, an argument, a scalar
subquery (not IN's or `= ANY`'s), a column of a table so held, or a column equal to one of those
(`r.gid = p.gid` and `p.gid = ?`), by the WHERE or an inner join's ON (a LEFT JOIN's ON holds
its own table's columns alone); at most as many as
a subquery or a CTE a SELECT reads first returns, each other table it reads so held to one row; one for each row of a VALUES list, up
to one for each row of an INSERT's `:values` with RETURNING; of a set operation, the sum of its
members' under `:union-all` and at most that under `:union`, at most the fewest under
`:intersect`, at most the first's under `:except`; at most `:limit` or `:fetch` (a number, or `[:inline n]`), whatever else the statement holds. A function that may return a set (one that does, of
`pg_catalog` as `generate_series` or of a generated schema, or one the registry has none of) in the select list, ORDER
BY, GROUP BY or `:values`, raw SQL (`:raw`), or a GROUP BY with ROLLUP, CUBE or GROUPING SETS,
which add groups, makes it say nothing but a limit; any other expression or clause changes no
count. Where it is one
row, `{:result :one}` drops the `[:maybe]`:

```clojure
(h/query-schema registry [:db] {:select [[[:count :*] :n]] :from [:users]} {:result :one})
;; => [:=> #:pgmalli{:problems []} [:cat :any] [:map [:n [:int #:pg{:type "bigint"}]]]]

(last (h/query-schema registry [:db :id] {:select [:email] :from [:users] :where [:= :id :?id]}))
;; => [:sequential {:max 1} [:map [:email [:string #:pg{:max-length 120, :type "character varying"}]]]]
```

Code that builds a schema of its own builds it of `h/analyze`'s parts, as `h/query-schema` does.

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

A query schema's properties hold `:pgmalli/problems`, `[]` for none:

```clojure
(def broken
  (m/schema (h/query-schema registry [:id] {:select [:mail] :from [:users] :where [:= :id :?id]})
            {:registry registry}))

(:pgmalli/problems (m/properties broken))
;; => [{:kind :unknown-column, :column :mail, :did-you-mean [:email]}]

(m/form (first (m/children broken)))
;; => [:cat [:or [:int #:pg{:type "bigint"}] decimal? integer? :double float?]]
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
Write the query so PostgreSQL's reading is explicit instead. The rules on what an argument takes
hold for a literal HoneySQL sends as a parameter (a number, a string, `[:lift v]`) too, which
PostgreSQL cannot tell from one; a literal written into the SQL (`:inline`, `[:inline "s"]`) or sent untyped
(`[:lift :kw]`) is held to an enum's labels alone, as PostgreSQL reads it through the type's input.

- <a id="stricter-assignment"></a>An argument takes what PostgreSQL converts implicitly, not all it converts: a cast
  PostgreSQL makes on assignment alone changes the value without a word (a `Double` or a
  `BigDecimal` into a `bigint` column rounds, an `OffsetDateTime` into a `date` column cuts the
  time off), and a number assigned to a `text` column PostgreSQL converts through its text.
  Cast it in the query (`[:cast :?n :bigint]`) where the conversion is meant. A value of a
  `jsonb_populate_record` document likewise takes its column's own values, which
  PostgreSQL reads through the column type's input: no number to a `boolean` column (PostgreSQL
  reads `1` and `0`), and no `BigDecimal` to an integer column (it reads `1`, not `1.0`); send a
  `Boolean`, or a `Long`. Nor a value other than a string to a `text` or a `bytea` column, which
  PostgreSQL stores as the value's JSON text (`1` as `"1"`, an object as `{"a": 1}`), or as that
  text's bytes: send a string, or the column a `jsonb` one.
- An argument compared with a column does not take nil, unless the query also tests it for NULL
  ([compare NULL in the query body](#the-types-of-the-arguments)).
- An argument `INSERT INTO t (cols) SELECT` writes to a `NOT NULL` column does not take nil, nor
  a value past the column's range, though PostgreSQL takes it where the SELECT
  returns no row: `SELECT ?x WHERE NOT EXISTS (...)`, which skips a duplicate, writes the nil
  and fails where there is none. Test the argument for NULL where nil means "write nothing":
  `{:select [:?x] :where [:is-not [:cast :?x :integer] nil]}` (cast: PostgreSQL refuses a NULL
  parameter in a bare `? IS NOT NULL`, whose type nothing tells).
- <a id="stricter-untyped"></a>A value sent untyped, assigned or compared, where its text is not of the column's type's
  category: a keyword to a `bigint`, `uuid` or `date` column (`:1`, `:2020-01-01`), which
  PostgreSQL reads through the type's input. Taking them would put `:keyword` on every argument.
  Send the value of the column's type (`1`), or cast it in the query.
- An `Instant` (a `timestamptz`) assigned to a `timestamp` or a `date` column, and a
  `LocalDateTime` (a `timestamp`) to a `date` one: PostgreSQL converts it on assignment alone,
  in the session's time zone, which pgjdbc sets to the JVM's, so the value written changes with
  the JVM's time zone without a word. Send the column's own (`LocalDateTime`, `LocalDate`), or
  cast it in the query where the conversion is meant. Compared, a `timestamptz` and a
  `timestamp` or a `date` convert implicitly, in that time zone too
  ([the session's time zone](reading-rows.md#the-sessions-time-zone)).
- A `Character` argument, which pgjdbc sends as `varchar`, to a column other than a `character`
  one: taking it would put `char?` on every string argument (a `"char"` column takes a `varchar`
  on assignment alone, cut to its first byte). Send `(str c)`.
- A key of a `jsonb_populate_record` document (or of `json_populate_record` and their
  `recordset`) that names no column of the row type, which PostgreSQL ignores: almost always a
  misspelled or unconverted key (`:sort-order` for `sort_order`), whose value is then not
  written.
- An UPDATE, or an ON CONFLICT DO UPDATE, writing NULL into a NOT NULL column: a
  `:not-null-violation`, even when it would touch no row or no row conflicts.
- An `INSERT INTO t (cols) SELECT` leaving out a NOT NULL column of no default: a
  `:missing-required-column`, even when the SELECT would return no row.
- Columns of one label in the row a query returns (`{:select [:q.id :s.id] ...}`, or `*` over
  two tables that each have `id`): a `:duplicate-column`. PostgreSQL returns them all, but
  next.jdbc's `as-unqualified-maps` reads them into one key, keeping the last column's value
  without a word. Give each an alias of its own (`[:s.id :s_id]`). The row is a statement's,
  a set operation's first member's (whose labels name its columns), or RETURNING's; a
  subquery's or a CTE's columns count where `*` returns them.
- A symbol in the query, which HoneySQL writes as a name (`[:cast :score integer]`): almost
  always code left unevaluated (a macro's form, whose `[:= :id id]` means a local `id`, or
  `'{:where (when x [:= :id :?id])}`), which read as a name would silently become a column. An
  error: write the name as a keyword (`[:cast :score :integer]`), or analyze the data the code
  builds, each variant separately.

## Not checked

- What [types.md](types.md#names-in-the-registry) leaves to the database: a CHECK and the like.
- A search path other than `pg_catalog` and then `:schema` (one naming `pg_catalog` later, or
  several schemas): a call resolves as under that one. A cast the application defines
  (`CREATE CAST`), and an extension's type's category, are not read: a call whose argument is of
  an extension's type is untyped unless an overload takes the type exactly.

- A literal written into the SQL or sent untyped, beyond an enum's labels ([stricter than
  PostgreSQL](#stricter-than-postgresql)); an empty `[:in :id []]` (an argument in `:in` is typed
  non-empty).
- A value where no column's type reaches, an argument there being `[:any {:pgmalli/untyped
  :position}]`: an item of `INSERT ... SELECT`'s select list, an element of `[:array ...]`, and
  coalesce or CASE of values alone (`[:coalesce "a" 1]`).
- A value beside a column of `xml`, which has no `=`, or of `"char"`, which coalesce and CASE
  match with no `character varying`.
- A call returning a domain over an enum type compared (`[:= [:f :m] "ok"]`): its value is of
  the enum type, which a column of the domain is not (`:no-operator`).
- A raw FROM item of SQL beyond a name (`[[:raw "jsonb_array_elements(doc) AS e(v)"]]`) is not
  read: its name and columns are not told, and a column any qualifier names may be its.
- An ON clause naming a table joined after it in its own join list, or an UPDATE's FROM joining
  on the target table (invalid reference to FROM-clause entry), which PostgreSQL rejects. (An ON
  inside a parenthesized join naming a table outside it is an `:unknown-column` with
  `:outside-join`.)
- A recursive CTE naming itself inside a subquery of its recursive term, which PostgreSQL
  rejects.
- Under ON CONFLICT, whether RETURNING and DO UPDATE WHERE may name the target or `excluded`.
- RETURNING's `old` and `new` are taken on every version; PostgreSQL before 18 refuses them.
- A call of a function PostgreSQL knows none of (`[:lowr :email]`), or of one or an operator no
  candidate of which takes its columns' types; its value is `:any`. A literal HoneySQL sends as a
  parameter where no candidate takes its class is a `:literal-type` (`[:round :price 2]`,
  `[:= :email 1]`'s `character varying = bigint`).
- A column of the query in a frame's offset (`[:score :preceding]`), which PostgreSQL refuses
  whatever its name: an offset holds no variable.
- An argument whose type `:args` leaves `:any` (a cast to `inet`'s), which may be an object of
  the type.
