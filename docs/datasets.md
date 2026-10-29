# Test datasets

`pgmalli.data` generates rows for several tables at once: primary keys and unique columns
distinct, foreign keys pointing at generated rows. The examples use
[example/schema.sql](example/schema.sql).

```clojure
(require '[pgmalli.core :as pgmalli]
         '[pgmalli.data :as data]
         '[malli.core :as m]
         '[malli.error :as me]
         '[clojure.test.check.generators :as gen]
         '[honey.sql :as sql])

(def registry (pgmalli/registry "public"))
```

## A dataset

A dataset is `{"schema.table" [row ...]}`. `dataset-schema` checks one across tables:

```clojure
(-> (m/explain (data/dataset-schema registry)
               {"public.groups" [{:id 1 :name "staff"}]
                "public.users" [{:id 1 :group_id 2 :email "ann@example.com" :nick nil
                                 :status "active" :closed_at nil
                                 :created_at (java.time.Instant/parse "2026-01-01T00:00:00Z")}]}
               {:registry registry})
    (me/humanize))
;; => ["public.users [\"group_id\"] references public.groups [\"id\"]"]
```

## Generating

`dataset-generator` is a test.check generator. `:rows` is the number of rows per table (5 by
default):

```clojure
(def sample (gen/generate (data/dataset-generator registry {:rows 2}) 30 42))

(keys sample)
;; => ("public.groups" "public.users" "public.posts")

(m/validate (data/dataset-schema registry) sample {:registry registry})
;; => true
```

`:except` leaves tables out. A table referencing one left out is an error:

```clojure
(keys (gen/generate (data/dataset-generator registry {:rows 2 :except #{"public.posts"}}) 30 42))
;; => ("public.groups" "public.users")

(try (data/dataset-generator registry {:except #{"public.users"}})
     (catch clojure.lang.ExceptionInfo e (ex-message e)))
;; => "public.posts references public.users, which :except leaves out"
```

A foreign key is filled from a row of the table it references. A cycle of NOT NULL
foreign keys between tables cannot be generated.

## Short tables

A table whose CHECKs reject most candidates may come out with fewer rows than `:rows`.
`short-tables` lists them with what the candidate rows failed on; nil when every table is
full:

```clojure
(data/short-tables sample)
;; => nil
```

## Keeping a fixture

A seed gives the same dataset in every run with the same versions of pgmalli and malli.
Write it to a file to keep it across upgrades:

```clojure
(data/write-dataset "target/fixture.edn" sample)

(= sample (data/read-dataset "target/fixture.edn"))
;; => true
```

The file holds one row per line, `java.time` values and bytes under pgmalli's tags
(`#pgmalli/instant "..."`, `#pgmalli/bytes "hex"`). Byte arrays compare by identity, so a
dataset with `bytea` values is not `=` to itself read back, though it inserts the same rows.

## Inserting

`inserts` gives one HoneySQL INSERT per table, parents first:

```clojure
(first (sql/format (second (data/inserts registry sample))))
;; => "INSERT INTO public.users (closed_at, created_at, email, group_id, id, nick, status) OVERRIDING SYSTEM VALUE VALUES (?, ?, ?, ?, ?, ?, CAST(? AS \"public\".\"user_status\")), (?, ?, ?, ?, ?, ?, CAST(? AS \"public\".\"user_status\"))"
```

Enum and domain values are cast to their type, quoted as it is spelt; a string of a type the
driver's text parameter cannot be written to (`inet`, `bit`, `money` ...) is cast to it; json
is written and cast, and arrays given their element type. An identity ALWAYS column gets
`OVERRIDING SYSTEM VALUE`. A column a row lacks is `DEFAULT`.

Time values are passed as they are. Require `next.jdbc.date-time` so the driver takes
`java.time`:

<!-- :test-doc-blocks/skip -->
```clojure
(require '[next.jdbc :as jdbc] 'next.jdbc.date-time)

(jdbc/with-transaction [tx db]
  (doseq [q (data/inserts registry sample)]
    (jdbc/execute! tx (sql/format q))))
```

For a database that already holds some rows (seeded by migrations), add
`ON CONFLICT DO NOTHING`:

```clojure
(select-keys (first (data/inserts registry sample {:on-conflict :nothing})) [:on-conflict :do-nothing])
;; => {:on-conflict [], :do-nothing []}
```

> NOTE: identity sequences do not move with the inserted ids. Before inserting rows without
> ids into the same tables, set them:
> `SELECT setval(pg_get_serial_sequence('users', 'id'), max(id)) FROM users`.
