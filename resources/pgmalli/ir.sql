-- One schema as a single JSON document. Run with: psql -At -v schema=NAME -f ir.sql
-- Reads tables (regular and partition parents), columns, CHECK constraints, enum and
-- domain types. Expressions are kept as PostgreSQL's deparser prints them.
-- search_path is pinned so type qualification does not depend on the connecting user.
SET search_path TO :"schema", pg_catalog;
WITH cols AS (
  SELECT c.oid AS relid, a.attnum,
         json_build_object(
           'name', a.attname,
           'position', a.attnum,
           'data_type', pg_catalog.format_type(a.atttypid, NULL),
           'type_schema', tn.nspname,
           'is_nullable', NOT a.attnotnull,
           'default_value', CASE WHEN a.attgenerated = '' THEN pg_get_expr(d.adbin, d.adrelid) END,
           'generated_expr', CASE WHEN a.attgenerated <> '' THEN pg_get_expr(d.adbin, d.adrelid) END,
           'identity', CASE a.attidentity WHEN 'a' THEN 'ALWAYS' WHEN 'd' THEN 'BY DEFAULT' END,
           'max_length', CASE WHEN a.atttypid IN ('varchar'::regtype, 'bpchar'::regtype) AND a.atttypmod > 4 THEN a.atttypmod - 4 END,
           'precision', CASE WHEN a.atttypid = 'numeric'::regtype AND a.atttypmod > 4 THEN ((a.atttypmod - 4) >> 16) & 65535 END,
           'scale', CASE WHEN a.atttypid = 'numeric'::regtype AND a.atttypmod > 4 THEN (a.atttypmod - 4) & 65535 END
         ) AS col
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  JOIN pg_type t ON t.oid = a.atttypid
  JOIN pg_namespace tn ON tn.oid = t.typnamespace
  LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
  WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
),
checks AS (
  SELECT k.conrelid AS relid,
         json_build_object('name', k.conname, 'type', 'CHECK',
                           'check_clause', pg_get_constraintdef(k.oid, true),
                           'is_valid', k.convalidated) AS con
  FROM pg_constraint k
  JOIN pg_namespace n ON n.oid = k.connamespace
  WHERE n.nspname = :'schema' AND k.contype = 'c' AND k.conparentid = 0
),
tables AS (
  SELECT c.relname,
         json_build_object(
           'name', c.relname,
           'columns', (SELECT COALESCE(json_agg(col ORDER BY attnum), '[]'::json) FROM cols WHERE cols.relid = c.oid),
           'constraints', (SELECT COALESCE(json_object_agg(con->>'name', con), '{}'::json) FROM checks WHERE checks.relid = c.oid)
         ) AS tbl
  FROM pg_class c
  JOIN pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p') AND NOT c.relispartition
),
types AS (
  SELECT t.typname,
         CASE t.typtype
           WHEN 'e' THEN json_build_object('name', t.typname, 'kind', 'ENUM',
                           'enum_values', (SELECT json_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_enum e WHERE e.enumtypid = t.oid))
           WHEN 'd' THEN json_build_object('name', t.typname, 'kind', 'DOMAIN',
                           'base_type', pg_catalog.format_type(t.typbasetype, t.typtypmod),
                           'not_null', t.typnotnull,
                           'default', t.typdefault,
                           'constraints', (SELECT COALESCE(json_agg(json_build_object('name', k.conname, 'definition', pg_get_constraintdef(k.oid, true))), '[]'::json)
                                           FROM pg_constraint k WHERE k.contypid = t.oid AND k.contype = 'c'))
         END AS typ
  FROM pg_type t
  JOIN pg_namespace n ON n.oid = t.typnamespace
  WHERE n.nspname = :'schema' AND t.typtype IN ('e', 'd')
)
SELECT json_build_object(
  'name', :'schema',
  'exists', EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = :'schema'),
  'database_version', version(),
  'tables', (SELECT COALESCE(json_object_agg(relname, tbl), '{}'::json) FROM tables),
  'types', (SELECT COALESCE(json_object_agg(typname, typ), '{}'::json) FROM types)
);
