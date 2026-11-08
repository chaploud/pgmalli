# Debt

What pgmalli-next knows it gets wrong and has not fixed yet. This repository is a temporary
branch of pgmalli: these would be issues there. Move them to issues, or delete this file, when the
work goes back.

None of these refuses what PostgreSQL takes. Each lets through something PostgreSQL refuses
(it fails when the query runs), or leaves a type untold. Fix one, then delete its entry.

- An enum named `text`, `name` or `varchar` in a schema on the session's search_path reads as a
  `String` (`pgmalli-next.jdbc/builder`), while its row type says keyword labels: pgjdbc names it
  as it names that pg_catalog type, both a `VARCHAR`, and tells no type OID through
  `ResultSetMetaData`. Off the search_path it is named `"schema"."text"` and reads as keywords.
- A `character` column of no length (`char`, which is `char(1)`) takes a `String` of any length,
  and an array of a type of a length (`varchar(5)[]`, `char(3)[]`) elements of any length: the
  registry keeps no length of either. PostgreSQL refuses the value (`value too long`).
- An array of a type pgmalli does not type (`ltree[]`, `isbn13[]`) takes any Java array, a
  `Long[]` too, which PostgreSQL refuses (`column is of type ltree[] but expression is of type
  bigint[]`); values-schema generates no value of it.
