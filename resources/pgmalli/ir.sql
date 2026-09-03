-- One schema as a single JSON document. Run with: psql -At -v schema=NAME -f ir.sql
-- Reads tables (regular and partition parents), views and materialized views, columns,
-- CHECK / PRIMARY KEY / UNIQUE / FOREIGN KEY constraints, enum and domain types. Expressions
-- are kept as PostgreSQL's deparser prints them.
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
           -- the typmod of an array column is its element type's
           'max_length', CASE WHEN COALESCE(NULLIF(t.typelem, 0), t.oid) IN ('varchar'::regtype, 'bpchar'::regtype) AND a.atttypmod > 4 THEN a.atttypmod - 4
                              WHEN COALESCE(NULLIF(t.typelem, 0), t.oid) IN ('bit'::regtype, 'varbit'::regtype) AND a.atttypmod > 0 THEN a.atttypmod END,
           'precision', CASE WHEN a.atttypid = 'numeric'::regtype AND a.atttypmod > 4 THEN ((a.atttypmod - 4) >> 16) & 65535 END,
           -- the scale is the low 11 bits of the typmod, signed (numeric(2,-3) is allowed)
           'scale', CASE WHEN a.atttypid = 'numeric'::regtype AND a.atttypmod > 4
                    THEN CASE WHEN ((a.atttypmod - 4) & 2047) >= 1024 THEN ((a.atttypmod - 4) & 2047) - 2048 ELSE (a.atttypmod - 4) & 2047 END END
         ) AS col
  FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
  JOIN pg_catalog.pg_namespace tn ON tn.oid = t.typnamespace
  LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
  WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p', 'v', 'm') AND NOT c.relispartition
),
cons AS (
  SELECT k.conrelid AS relid,
         json_build_object(
           'name', k.conname,
           'type', CASE k.contype WHEN 'c' THEN 'CHECK' WHEN 'p' THEN 'PRIMARY KEY' WHEN 'u' THEN 'UNIQUE' WHEN 'f' THEN 'FOREIGN KEY' END,
           'columns', (SELECT json_agg(a.attname ORDER BY ord)
                       FROM unnest(k.conkey) WITH ORDINALITY AS c(attnum, ord)
                       JOIN pg_catalog.pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = c.attnum),
           'check_clause', CASE WHEN k.contype = 'c' THEN pg_get_constraintdef(k.oid, true) END,
           'is_valid', k.convalidated,
           -- NOT ENFORCED (PostgreSQL 18): conenforced is absent before, hence read through jsonb
           'is_enforced', COALESCE((to_jsonb(k) ->> 'conenforced')::boolean, true),
           'nulls_not_distinct', CASE WHEN k.contype = 'u' THEN (SELECT i.indnullsnotdistinct FROM pg_catalog.pg_index i WHERE i.indexrelid = k.conindid) END,
           'references', CASE WHEN k.contype = 'f' THEN json_build_object(
                           'match', CASE k.confmatchtype WHEN 'f' THEN 'FULL' WHEN 'p' THEN 'PARTIAL' ELSE 'SIMPLE' END,
                           'schema', (SELECT nspname FROM pg_catalog.pg_class r JOIN pg_catalog.pg_namespace rn ON rn.oid = r.relnamespace WHERE r.oid = k.confrelid),
                           'table', (SELECT relname FROM pg_catalog.pg_class WHERE oid = k.confrelid),
                           'columns', (SELECT json_agg(a.attname ORDER BY ord)
                                       FROM unnest(k.confkey) WITH ORDINALITY AS c(attnum, ord)
                                       JOIN pg_catalog.pg_attribute a ON a.attrelid = k.confrelid AND a.attnum = c.attnum)) END
         ) AS con
  FROM pg_catalog.pg_constraint k
  JOIN pg_catalog.pg_namespace n ON n.oid = k.connamespace
  WHERE n.nspname = :'schema' AND k.contype IN ('c', 'p', 'u', 'f') AND k.conparentid = 0
  UNION ALL
  -- a unique index over plain columns, without a predicate, constrains rows as a UNIQUE constraint does;
  -- its key columns only (INCLUDE columns are not part of the key)
  SELECT i.indrelid AS relid,
         json_build_object(
           'name', ic.relname,
           'type', 'UNIQUE',
           'columns', (SELECT json_agg(a.attname ORDER BY ord)
                       FROM unnest(i.indkey::int2[]) WITH ORDINALITY AS c(attnum, ord)
                       JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = c.attnum
                       WHERE c.ord <= i.indnkeyatts),
           'is_valid', i.indisvalid,
           'nulls_not_distinct', i.indnullsnotdistinct,
           'index', true
         ) AS con
  FROM pg_catalog.pg_index i
  JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
  JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = :'schema' AND i.indisunique AND NOT i.indisprimary
    AND i.indpred IS NULL AND i.indexprs IS NULL
    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.conindid = i.indexrelid)
  UNION ALL
  -- a partitioned table takes a row only when one of its leaf partitions does: their bounds
  -- (each including its ancestors') and the leaf's own CHECKs, as one CHECK; none, false
  SELECT p.oid AS relid,
         json_build_object(
           'name', p.relname || ' (partitions)',
           'type', 'CHECK',
           'check_clause', 'CHECK (' || COALESCE(leaves.clause, 'false') || ')',
           'is_valid', true
         ) AS con
  FROM pg_catalog.pg_class p
  JOIN pg_catalog.pg_namespace n ON n.oid = p.relnamespace
  JOIN LATERAL (
    WITH RECURSIVE tree AS (
      SELECT inhrelid FROM pg_catalog.pg_inherits WHERE inhparent = p.oid
      UNION ALL
      SELECT i.inhrelid FROM pg_catalog.pg_inherits i JOIN tree ON i.inhparent = tree.inhrelid
    )
    SELECT string_agg('(' || pg_catalog.pg_get_partition_constraintdef(t.inhrelid)
                          || COALESCE((SELECT string_agg(' AND (' || pg_catalog.pg_get_expr(k.conbin, k.conrelid) || ')', '')
                                       FROM pg_catalog.pg_constraint k WHERE k.conrelid = t.inhrelid AND k.contype = 'c'), '')
                          || ')', ' OR ') AS clause
    FROM tree t JOIN pg_catalog.pg_class l ON l.oid = t.inhrelid
    WHERE l.relkind IN ('r', 'f')
  ) leaves ON true
  WHERE n.nspname = :'schema' AND p.relkind = 'p'
),
tables AS (
  SELECT c.relname,
         json_build_object(
           'name', c.relname,
           'kind', CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END,
           'columns', (SELECT COALESCE(json_agg(col ORDER BY attnum), '[]'::json) FROM cols WHERE cols.relid = c.oid),
           -- an index may bear a constraint's name (they are different namespaces), so it is keyed apart
           'constraints', (SELECT COALESCE(json_object_agg(CASE WHEN (con->>'index')::boolean THEN con->>'name' || ' (index)' ELSE con->>'name' END, con), '{}'::json) FROM cons WHERE cons.relid = c.oid)
         ) AS tbl
  FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p', 'v', 'm') AND NOT c.relispartition
),
types AS (
  SELECT t.typname,
         CASE t.typtype
           WHEN 'e' THEN json_build_object('name', t.typname, 'kind', 'ENUM',
                           'enum_values', (SELECT json_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_catalog.pg_enum e WHERE e.enumtypid = t.oid))
           WHEN 'd' THEN json_build_object('name', t.typname, 'kind', 'DOMAIN',
                           'base_type', pg_catalog.format_type(t.typbasetype, t.typtypmod),
                           'not_null', t.typnotnull,
                           'default', t.typdefault,
                           'constraints', (SELECT COALESCE(json_agg(json_build_object('name', k.conname, 'definition', pg_get_constraintdef(k.oid, true), 'is_valid', k.convalidated)), '[]'::json)
                                           FROM pg_catalog.pg_constraint k WHERE k.contypid = t.oid AND k.contype = 'c'))
         END AS typ
  FROM pg_catalog.pg_type t
  JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
  WHERE n.nspname = :'schema' AND t.typtype IN ('e', 'd')
)
SELECT json_build_object(
  'name', :'schema',
  'exists', EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = :'schema'),
  'database_version', 'PostgreSQL ' || current_setting('server_version'),
  'tables', (SELECT COALESCE(json_object_agg(relname, tbl), '{}'::json) FROM tables),
  'types', (SELECT COALESCE(json_object_agg(typname, typ), '{}'::json) FROM types)
);
