# HoneySQL queries

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

A clause pgmalli does not read is reported rather than passed over:

```clojure
(h/check registry {:select [:id] :from [:users] :frobnicate [:x]})
;; => [{:kind :unread-clause, :clause :frobnicate}]
```

A CTE or subquery has the types of what it selects. An expression (`[:count :*]`) has no
type: comparisons with it are not checked.

## Writes

An INSERT or UPDATE is checked against what PostgreSQL refuses to write:

```clojure
(h/check registry {:update :users :set {:email nil} :where [:= :id 1]})
;; => [{:kind :not-null-violation, :table "public.users", :column :email}]

(h/check registry {:update :posts :set {:title_upper "X"} :where [:= :id 1]})
;; => [{:kind :read-only-column, :table "public.posts", :column :title_upper}]
```

HoneySQL writes NULL for a key one map of `:values` lacks and another has. `users.status`
has a default, but the first row below gets NULL:

```clojure
(h/check registry {:insert-into :users
                   :values [{:group_id 1 :email "ann@example.com"}
                            {:group_id 1 :email "bob@example.com" :status [:cast "closed" :user_status] :closed_at [:now]}]})
;; => [{:kind :not-null-violation, :table "public.users", :column :status}]
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
;; => {:group [:int #:pg{:type "integer"}], :statuses [:or [:sequential {:min 1} [:enum {:default "active", :pg/default "active", :pg/type "user_status"} "active" "closed"]] [:set {:min 1} [:enum {:default "active", :pg/default "active", :pg/type "user_status"} "active" "closed"]]], :n [:int {:min 0}]}
```

| where the argument is | its type |
|---|---|
| compared with a column | the column's kind (an enum's labels kept), without NULL; its CHECKs and lengths are left out, as PostgreSQL compares a value beyond them and matches no row |
| in `:in` | a non-empty vector, list or set of the column's type |
| assigned to a column (`:set`, `:values`) | the column's type, NULL allowed when the column allows it |
| a `LIKE` pattern | the column's kind (`:string`) |
| in `:limit`, `:offset` | `[:int {:min 0}]` |
| used only inside code (`(when x ...)`) | its type or nil |
| anywhere else | `:any` |

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

## The row a query returns

`h/row-schema` gives the `[:map ...]` of one row, as next.jdbc builds it. A column an outer
join may leave NULL is nullable; an expression is nullable `:any`:

```clojure
(h/row-schema registry {:select [:u.email :p.title]
                        :from [[:users :u]]
                        :left-join [[:posts :p] [:= :p.author_id :u.id]]})
;; => [:map [:users/email [:string {:max 120, :pg/max-length 120, :pg/type "character varying"}]] [:posts/title [:maybe [:string {:max 200, :pg/max-length 200, :pg/type "character varying"}]]]]

(h/row-schema registry {:select [:id [[:count :*] :n]] :from [:users] :group-by [:id]})
;; => [:map [:users/id [:int #:pg{:identity :always, :type "bigint"}]] [:n [:maybe :any]]]
```

It takes the options of `pgmalli.core/as-read` ([reading-rows.md](reading-rows.md)):

```clojure
(h/row-schema registry {:select [:id :created_at] :from [:users]} {:time :instant :kebab? true})
;; => [:map [:users/id [:int #:pg{:identity :always, :type "bigint"}]] [:users/created-at [:time/instant #:pg{:default [:now], :type "timestamp with time zone"}]]]
```

`h/row-schema` returns nil when a selected column cannot be resolved; `h/check` says which.

## Options

`h/check`, `h/arg-types`, `h/row-schema` and `h/query-schema` take the same options map:

| option | for |
|---|---|
| `:schema "app"` | the schema of unqualified tables |
| the options of `as-read` | how next.jdbc builds rows and the driver reads values ([reading-rows.md](reading-rows.md#options)) |
| `:write {pg-type schema}` | what a `SettableParameter` extension of yours takes for a type |
| `:write {:enum [:string :keyword]}` | what an extension sends for a list of labels (an enum type's, a CHECK's): the labels kept, as strings, keywords or both |
| `:write {:array [:pg/array :sequential :vector :set]}` | what an array argument (`[:any :?ids]`) may be sent as: a Java array, a vector, list or set |
| `:stringtype :unspecified` | your connection's `stringtype`: strings reach enum columns untyped (default `:varchar`, as pgjdbc's) |
| `:compare :lenient` | arguments compared with a column take nil too (`:strict` by default) |
| `:format {...}` | the options you pass to `sql/format`; `:values-default-columns` and `:inline` are read |
| `:result :one` | `h/query-schema` only: the return is one row or nil |

## Function schemas

`h/query-schema` combines `arg-types` and `row-schema`: the arguments are the function's, in order, and keys the
query lacks (`:db`) are `:any`:

```clojure
(h/query-schema registry [:db :ids]
                {:select [:id] :from [:users] :where [:in :id :?ids]}
                {:result :one})
;; => [:=> [:cat :any [:or [:sequential {:min 1} [:int #:pg{:type "bigint"}]] [:set {:min 1} [:int #:pg{:type "bigint"}]]]] [:maybe [:map [:users/id [:int #:pg{:identity :always, :type "bigint"}]]]]]
```

The schema may use types malli's default registry lacks (`:time/instant`). Give it the
registry when you make it, so `:malli/schema` holds a schema that needs none:

```clojure
(def find-users-schema
  (m/schema (h/query-schema registry [:db :ids] {:select [:id] :from [:users] :where [:in :id :?ids]})
            {:registry registry}))

(m/validate (first (m/children find-users-schema)) [nil #{1 2}])
;; => true

(m/validate (first (m/children find-users-schema)) [nil #{}])
;; => false
```

A query with problems still gives a schema, carrying them as `:pgmalli/problems`. The
property makes the vector one longer, so read its parts with `m/children`:

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

## Reported though PostgreSQL runs them

These match no row or touch none, which is a mistake far more often than intended:

- A comparison of a text column a CHECK narrows to a list (`CHECK (state IN ('open',
  'closed'))`) with a value outside it: an `:enum-literal`.
- An UPDATE, or an ON CONFLICT DO UPDATE, writing NULL into a NOT NULL column: a
  `:not-null-violation`, even when it would touch no row.

## Not checked

- Columns inside code (a list, `(when x [:= :id x])`): what it builds is not data.
- A statement keyed by symbols (`'{select [...]}`): symbols are values here, so it is
  reported as `:unread-clause`.
- Values written in the query beyond NULL and enum literals: an empty `[:in :id []]` is not
  reported (an argument in `:in` is typed non-empty).
- An ON clause naming a table joined after it, which PostgreSQL rejects.
- Under ON CONFLICT, whether RETURNING and DO UPDATE WHERE may name the target or `excluded`.
