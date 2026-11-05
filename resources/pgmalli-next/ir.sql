-- One schema as a single JSON document. Run with: psql -At -v schema=NAME -f ir.sql
-- Reads tables (regular, partition parents and foreign), views and materialized views, columns,
-- PRIMARY KEY / UNIQUE / FOREIGN KEY / EXCLUDE constraints, enum types, functions,
-- operators and sequences. A CHECK is not read: the database checks it.
-- search_path is pinned so type qualification does not depend on the connecting user, with
-- pg_catalog first so that the schema's own functions and operators never stand in for its: every
-- function is called qualified, and every operator has an exact match in pg_catalog (hence the
-- casts on literals), since a later schema's exact match beats pg_catalog's coercion.
SET search_path TO pg_catalog, :"schema";
WITH cols AS (
  SELECT c.oid AS relid, a.attnum,
         pg_catalog.json_build_object(
           'name', a.attname,
           'position', a.attnum,
           -- a domain's column (an array of a domain's) is of the domain's base type under all its
           -- domains, of any schema; NOT NULL and DEFAULT are a domain's own, not its array's
           'data_type', CASE WHEN dom.root IS NULL THEN pg_catalog.format_type(a.atttypid, NULL)
                             ELSE pg_catalog.format_type(dom.root, NULL) || CASE WHEN e.oid <> t.oid THEN '[]'::text ELSE ''::text END END,
           -- the domain as the column declares it: "d", or "d[]" for an array of one
           'domain', CASE WHEN dom.root IS NOT NULL THEN pg_catalog.format_type(a.atttypid, NULL) END,
           'type_schema', tn.nspname,
           'is_nullable', NOT (a.attnotnull OR (e.oid = t.oid AND COALESCE(dom.not_null, false))),
           'default_value', CASE WHEN a.attgenerated = '' THEN COALESCE(pg_catalog.pg_get_expr(d.adbin, d.adrelid), CASE WHEN e.oid = t.oid THEN dom.default_value END) END,
           'generated_expr', CASE WHEN a.attgenerated <> '' THEN pg_catalog.pg_get_expr(d.adbin, d.adrelid) END,
           'identity', CASE a.attidentity WHEN 'a' THEN 'ALWAYS' WHEN 'd' THEN 'BY DEFAULT' END,
           -- a varchar(n)'s, char(n)'s, bit(n)'s or bit varying(n)'s n, the typmod of an array column its element type's
           'max_length', CASE WHEN COALESCE(NULLIF(b.typelem, 0::oid), b.oid) IN ('varchar'::regtype::oid, 'bpchar'::regtype::oid) AND m.typmod > 4 THEN m.typmod - 4
                              WHEN COALESCE(NULLIF(b.typelem, 0::oid), b.oid) IN ('bit'::regtype::oid, 'varbit'::regtype::oid) AND m.typmod > 0 THEN m.typmod END
         ) AS col
  FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  JOIN pg_catalog.pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
  JOIN pg_catalog.pg_type t ON t.oid = a.atttypid
  -- an array's element type (a domain over an array is no array: it is its own element)
  JOIN pg_catalog.pg_type e ON e.oid = CASE WHEN t.typtype = 'b' AND t.typcategory = 'A' THEN t.typelem ELSE t.oid END
  LEFT JOIN LATERAL (
    WITH RECURSIVE chain AS (
      SELECT x.typbasetype AS base, x.typtypmod AS typmod, x.typnotnull AS not_null, x.typdefault AS default_value, 1 AS depth
      FROM pg_catalog.pg_type x WHERE x.oid = e.oid AND x.typtype = 'd'
      UNION ALL
      SELECT x.typbasetype, x.typtypmod, x.typnotnull, x.typdefault, chain.depth + 1
      FROM chain JOIN pg_catalog.pg_type x ON x.oid = chain.base AND x.typtype = 'd'
    )
    SELECT (SELECT base FROM chain ORDER BY depth DESC LIMIT 1) AS root,
           (SELECT typmod FROM chain ORDER BY depth DESC LIMIT 1) AS typmod,
           (SELECT bool_or(not_null) FROM chain) AS not_null,
           (SELECT default_value FROM chain WHERE default_value IS NOT NULL ORDER BY depth LIMIT 1) AS default_value
  ) dom ON true
  -- the type the column's values are of, and its typmod
  JOIN pg_catalog.pg_type b ON b.oid = COALESCE(dom.root, e.oid)
  CROSS JOIN LATERAL (SELECT CASE WHEN dom.root IS NULL THEN a.atttypmod ELSE dom.typmod END AS typmod) m
  JOIN pg_catalog.pg_namespace tn ON tn.oid = b.typnamespace
  LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
  WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p', 'v', 'm', 'f') AND NOT c.relispartition
),
cons AS (
  SELECT k.conrelid AS relid,
         pg_catalog.json_build_object(
           'name', k.conname,
           'type', CASE k.contype WHEN 'p' THEN 'PRIMARY KEY' WHEN 'u' THEN 'UNIQUE' WHEN 'f' THEN 'FOREIGN KEY' WHEN 'x' THEN 'EXCLUDE' END,
           'columns', (SELECT pg_catalog.json_agg(a.attname ORDER BY ord)
                       FROM pg_catalog.unnest(k.conkey) WITH ORDINALITY AS c(attnum, ord)
                       JOIN pg_catalog.pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = c.attnum),
           'is_valid', k.convalidated,
           'deferrable', k.condeferrable,
           -- NOT ENFORCED (PostgreSQL 18): conenforced is absent before, hence read through jsonb
           'is_enforced', COALESCE((pg_catalog.to_jsonb(k) ->> 'conenforced'::text)::boolean, true),
           -- EXCLUDE: the index method, each element's column (null for an expression) and operator,
           -- and whether a WHERE limits it to some rows
           'exclude', CASE WHEN k.contype = 'x' THEN pg_catalog.json_build_object(
                        'method', (SELECT am.amname FROM pg_catalog.pg_class ic JOIN pg_catalog.pg_am am ON am.oid = ic.relam WHERE ic.oid = k.conindid),
                        'partial', (SELECT i.indpred IS NOT NULL FROM pg_catalog.pg_index i WHERE i.indexrelid = k.conindid),
                        'elements', (SELECT pg_catalog.json_agg(pg_catalog.json_build_object('column', a.attname, 'op', o.oprname) ORDER BY e.ord)
                                     FROM ROWS FROM (pg_catalog.unnest(k.conkey), pg_catalog.unnest(k.conexclop)) WITH ORDINALITY AS e(attnum, opoid, ord)
                                     LEFT JOIN pg_catalog.pg_attribute a ON a.attrelid = k.conrelid AND a.attnum = e.attnum AND e.attnum > 0
                                     JOIN pg_catalog.pg_operator o ON o.oid = e.opoid)) END,
           'nulls_not_distinct', CASE WHEN k.contype = 'u' THEN (SELECT i.indnullsnotdistinct FROM pg_catalog.pg_index i WHERE i.indexrelid = k.conindid) END,
           'references', CASE WHEN k.contype = 'f' THEN pg_catalog.json_build_object(
                           'match', CASE k.confmatchtype WHEN 'f' THEN 'FULL' WHEN 'p' THEN 'PARTIAL' ELSE 'SIMPLE' END,
                           'schema', (SELECT nspname FROM pg_catalog.pg_class r JOIN pg_catalog.pg_namespace rn ON rn.oid = r.relnamespace WHERE r.oid = k.confrelid),
                           'table', (SELECT relname FROM pg_catalog.pg_class WHERE oid = k.confrelid),
                           'columns', (SELECT pg_catalog.json_agg(a.attname ORDER BY ord)
                                       FROM pg_catalog.unnest(k.confkey) WITH ORDINALITY AS c(attnum, ord)
                                       JOIN pg_catalog.pg_attribute a ON a.attrelid = k.confrelid AND a.attnum = c.attnum)) END
         ) AS con
  FROM pg_catalog.pg_constraint k
  JOIN pg_catalog.pg_namespace n ON n.oid = k.connamespace
  WHERE n.nspname = :'schema' AND k.contype IN ('p', 'u', 'f', 'x') AND k.conparentid = 0::oid
  UNION ALL
  -- a unique index over plain columns constrains rows as a UNIQUE constraint does, a partial one
  -- (with a predicate) the rows it covers; its key columns only (INCLUDE columns are not part of the key)
  SELECT i.indrelid AS relid,
         pg_catalog.json_build_object(
           'name', ic.relname,
           'type', 'UNIQUE',
           'columns', (SELECT pg_catalog.json_agg(a.attname ORDER BY ord)
                       FROM pg_catalog.unnest(i.indkey::int2[]) WITH ORDINALITY AS c(attnum, ord)
                       JOIN pg_catalog.pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = c.attnum
                       WHERE c.ord <= i.indnkeyatts),
           'is_valid', i.indisvalid,
           'nulls_not_distinct', i.indnullsnotdistinct,
           'partial', i.indpred IS NOT NULL,
           'index', true
         ) AS con
  FROM pg_catalog.pg_index i
  JOIN pg_catalog.pg_class ic ON ic.oid = i.indexrelid
  JOIN pg_catalog.pg_class c ON c.oid = i.indrelid
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = :'schema' AND i.indisunique AND NOT i.indisprimary
    AND i.indexprs IS NULL
    AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_constraint k WHERE k.conindid = i.indexrelid)
),
tables AS (
  SELECT c.relname,
         pg_catalog.json_build_object(
           'name', c.relname,
           'kind', CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END,
           'columns', (SELECT COALESCE(pg_catalog.json_agg(col ORDER BY attnum), '[]'::json) FROM cols WHERE cols.relid = c.oid),
           -- an index may bear a constraint's name (they are different namespaces), so it is keyed apart
           'constraints', (SELECT COALESCE(pg_catalog.json_object_agg(CASE WHEN (con->>'index'::text)::boolean THEN con->>'name'::text || ' (index)' ELSE con->>'name'::text END, con), '{}'::json) FROM cons WHERE cons.relid = c.oid),
           -- row-level triggers a user wrote and left enabled: their code may reject or change a row
           'triggers', (SELECT COALESCE(pg_catalog.json_agg(pg_catalog.json_build_object('name', t.tgname, 'before', (t.tgtype::int4 & 2) = 2,
                                                                    'insert', (t.tgtype::int4 & 4) = 4, 'update', (t.tgtype::int4 & 16) = 16, 'delete', (t.tgtype::int4 & 8) = 8)
                                                  ORDER BY t.tgname), '[]'::json)
                        FROM pg_catalog.pg_trigger t
                        WHERE t.tgrelid = c.oid AND NOT t.tgisinternal AND t.tgenabled <> 'D' AND (t.tgtype::int4 & 1) = 1),
           -- a table other tables inherit (not as partitions): a query reads their rows too, which its keys do not span
           'inherited', EXISTS (SELECT 1 FROM pg_catalog.pg_inherits i JOIN pg_catalog.pg_class ch ON ch.oid = i.inhrelid
                                WHERE i.inhparent = c.oid AND NOT ch.relispartition),
           -- a partitioned table's partitions of this schema, at any depth: a query reads or writes one by name
           'partitions', (WITH RECURSIVE tree AS (
                            SELECT inhrelid FROM pg_catalog.pg_inherits WHERE inhparent = c.oid
                            UNION ALL
                            SELECT i.inhrelid FROM pg_catalog.pg_inherits i JOIN tree ON i.inhparent = tree.inhrelid)
                          SELECT pg_catalog.json_agg(l.relname ORDER BY l.relname)
                          FROM tree JOIN pg_catalog.pg_class l ON l.oid = tree.inhrelid
                          WHERE c.relkind = 'p' AND l.relnamespace = c.relnamespace)
         ) AS tbl
  FROM pg_catalog.pg_class c
  JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
  WHERE n.nspname = :'schema' AND c.relkind IN ('r', 'p', 'v', 'm', 'f') AND NOT c.relispartition
),
types AS (
  SELECT t.typname,
         pg_catalog.json_build_object('name', t.typname, 'kind', 'ENUM',
                                      'enum_values', (SELECT pg_catalog.json_agg(e.enumlabel ORDER BY e.enumsortorder) FROM pg_catalog.pg_enum e WHERE e.enumtypid = t.oid)) AS typ
  FROM pg_catalog.pg_type t
  JOIN pg_catalog.pg_namespace n ON n.oid = t.typnamespace
  WHERE n.nspname = :'schema' AND t.typtype = 'e'
),
-- what a query calls: no procedure, no trigger function, nothing taking or returning a type
-- SQL has no value of (internal, cstring, an access method's or a language's handler)
functions AS (
  SELECT pg_catalog.json_build_object(
           'name', p.proname,
           'args', ARRAY(SELECT pg_catalog.format_type(u.t, NULL) FROM pg_catalog.unnest(p.proargtypes::pg_catalog.oid[]) WITH ORDINALITY u(t, n) ORDER BY u.n),
           'result', pg_catalog.format_type(p.prorettype, NULL),
           'set', p.proretset,
           'kind', p.prokind,
           'variadic', p.provariadic <> 0::oid,
           'defaults', p.pronargdefaults,
           -- an aggregate whose state starts from a value and is its result (count), a window
           -- function of no argument (row_number)
           'non_null', (a.agginitval IS NOT NULL AND a.aggfinalfn = 0::oid) OR (p.prokind = 'w' AND p.pronargs = 0),
           -- the columns it returns in FROM: its OUT (and TABLE) arguments, else its composite type's
           'columns', COALESCE((SELECT pg_catalog.json_agg(pg_catalog.json_build_array(u.n, pg_catalog.format_type(u.t, NULL)) ORDER BY u.i)
                                FROM ROWS FROM (pg_catalog.unnest(p.proargnames), pg_catalog.unnest(p.proallargtypes), pg_catalog.unnest(p.proargmodes)) WITH ORDINALITY u(n, t, m, i)
                                WHERE u.m IN ('o', 'b', 't')),
                               (SELECT pg_catalog.json_agg(pg_catalog.json_build_array(a.attname, pg_catalog.format_type(a.atttypid, NULL)) ORDER BY a.attnum)
                                FROM pg_catalog.pg_type rt JOIN pg_catalog.pg_attribute a ON a.attrelid = rt.typrelid
                                WHERE rt.oid = p.prorettype AND a.attnum > 0 AND NOT a.attisdropped))
         ) AS fn,
         p.proname, p.oid
  FROM pg_catalog.pg_proc p
  JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
  LEFT JOIN pg_catalog.pg_aggregate a ON a.aggfnoid = p.oid
  WHERE n.nspname = :'schema' AND p.prokind <> 'p'
    AND NOT (ARRAY[p.prorettype] || p.proargtypes::pg_catalog.oid[])
            && ARRAY['trigger', 'event_trigger', 'internal', 'cstring', 'language_handler', 'fdw_handler',
               'index_am_handler', 'table_am_handler', 'tsm_handler']::pg_catalog.regtype[]::pg_catalog.oid[]
),
operators AS (
  SELECT pg_catalog.json_build_object(
           'name', o.oprname,
           'args', CASE WHEN o.oprleft = 0::oid THEN pg_catalog.json_build_array(pg_catalog.format_type(o.oprright, NULL))
                        ELSE pg_catalog.json_build_array(pg_catalog.format_type(o.oprleft, NULL), pg_catalog.format_type(o.oprright, NULL)) END,
           'result', pg_catalog.format_type(o.oprresult, NULL),
           -- a comparison: an operator of a btree operator family, or its negator
           'btree', EXISTS (SELECT 1 FROM pg_catalog.pg_amop a JOIN pg_catalog.pg_am am ON am.oid = a.amopmethod
                            WHERE a.amopopr IN (o.oid, o.oprnegate) AND am.amname = 'btree')
         ) AS op,
         o.oprname, o.oid
  FROM pg_catalog.pg_operator o
  JOIN pg_catalog.pg_namespace n ON n.oid = o.oprnamespace
  WHERE n.nspname = :'schema'
)
SELECT pg_catalog.json_build_object(
  'name', :'schema',
  'exists', EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = :'schema'),
  -- the schema names, for the one meant when it does not exist
  'schemas', (SELECT pg_catalog.json_agg(nspname ORDER BY nspname) FROM pg_catalog.pg_namespace
              WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_namespace WHERE nspname = :'schema')),
  'database_version', 'PostgreSQL ' || pg_catalog.current_setting('server_version'),
  'tables', (SELECT COALESCE(pg_catalog.json_object_agg(relname, tbl), '{}'::json) FROM tables),
  'types', (SELECT COALESCE(pg_catalog.json_object_agg(typname, typ), '{}'::json) FROM types),
  'functions', (SELECT COALESCE(pg_catalog.json_agg(fn ORDER BY proname, oid), '[]'::json) FROM functions),
  'operators', (SELECT COALESCE(pg_catalog.json_agg(op ORDER BY oprname, oid), '[]'::json) FROM operators),
  -- a sequence's columns are every sequence's (last_value, log_cnt, is_called): its name alone
  'sequences', (SELECT COALESCE(pg_catalog.json_agg(c.relname ORDER BY c.relname), '[]'::json)
                FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = :'schema' AND c.relkind = 'S')
);
