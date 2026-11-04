(ns pgmalli-next.catalog-source
  "What resources/pgmalli-next/impl/catalog.edn is read from: the query of pg_catalog, and its result as
   the file holds one version's. bb catalog (dev/catalog.clj) writes the file with them, and
   pgmalli-next.upstream-test checks the file against the server it runs on. An overload is built as
   the schema's own are (pattern/function-overload, pattern/operator-overload)."
  (:require [pgmalli-next.impl.pattern :as pattern]))

(def query
  "One JSON object of pg_catalog's types (an array's facts are its element's: x[]), casts (with
   castcontext: i implicit, a assignment, e explicit), ranges, functions (no procedure), operators and the columns of its tables and views,
   as format_type names them; no extension's."
  "SELECT json_build_object(
     'types', (SELECT json_agg(json_build_array(format_type(oid, NULL), typcategory, typispreferred) ORDER BY 1)
               FROM pg_type WHERE typnamespace = 'pg_catalog'::regnamespace AND typtype <> 'c'
                 AND NOT (typelem <> 0 AND typsubscript = 'array_subscript_handler'::regproc)),
     'casts', (SELECT json_agg(json_build_array(format_type(castsource, NULL), format_type(casttarget, NULL), castcontext) ORDER BY 1)
               FROM pg_cast c
               WHERE NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_cast'::regclass AND d.objid = c.oid AND d.deptype = 'e')),
     'functions', (SELECT json_agg(json_build_object('name', p.proname,
                                                     'args', ARRAY(SELECT format_type(t, NULL) FROM unnest(p.proargtypes::oid[]) WITH ORDINALITY u(t, n) ORDER BY n),
                                                     'result', format_type(p.prorettype, NULL), 'set', p.proretset, 'kind', p.prokind,
                                                     'variadic', p.provariadic <> 0, 'defaults', p.pronargdefaults,
                                                     'non_null', (a.agginitval IS NOT NULL AND a.aggfinalfn = 0) OR (p.prokind = 'w' AND p.pronargs = 0),
                                                     'columns', (SELECT json_agg(json_build_array(u.n, format_type(u.t, NULL)) ORDER BY u.i)
                                                                 FROM unnest(p.proargnames, p.proallargtypes, p.proargmodes) WITH ORDINALITY u(n, t, m, i)
                                                                 WHERE u.m IN ('o', 'b', 't'))) ORDER BY p.proname)
                   FROM pg_proc p LEFT JOIN pg_aggregate a ON a.aggfnoid = p.oid
                   WHERE p.pronamespace = 'pg_catalog'::regnamespace AND p.prokind <> 'p'),
     'ranges', (SELECT json_agg(json_build_array(format_type(rngtypid, NULL), format_type(rngsubtype, NULL), format_type(rngmultitypid, NULL)) ORDER BY 1)
                FROM pg_range r JOIN pg_type t ON t.oid = r.rngtypid WHERE t.typnamespace = 'pg_catalog'::regnamespace),
     'relations', (SELECT json_agg(json_build_array(c.relname,
                                                    (SELECT json_agg(json_build_array(a.attname, format_type(a.atttypid, NULL), a.attnotnull) ORDER BY a.attnum)
                                                     FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped)) ORDER BY 1)
                   FROM pg_class c WHERE c.relnamespace = 'pg_catalog'::regnamespace AND c.relkind IN ('r', 'v')),
     'operators', (SELECT json_agg(json_build_object('name', o.oprname,
                                                     'args', CASE WHEN o.oprleft = 0 THEN json_build_array(format_type(o.oprright, NULL))
                                                                  ELSE json_build_array(format_type(o.oprleft, NULL), format_type(o.oprright, NULL)) END,
                                                     'result', format_type(o.oprresult, NULL),
                                                     'btree', EXISTS (SELECT 1 FROM pg_amop a WHERE a.amopopr IN (o.oid, o.oprnegate) AND a.amopmethod = (SELECT oid FROM pg_am WHERE amname = 'btree'))) ORDER BY o.oprname)
                   FROM pg_operator o WHERE o.oprnamespace = 'pg_catalog'::regnamespace))")

(defn shape
  "A version's catalog as catalog.edn holds it: {:types {name [category preferred?]} :casts
   #{[source target context]} :functions {name #{overload}} :operators {name #{overload}} :relations
   {name [column ...]}}, an overload {:args [type ...] :result type ...}, a column {:name :type
   :not-null}."
  [{:strs [types casts ranges functions operators relations]}]
  {:types (into {} (for [[n c pref] types] [n (cond-> {:category c} pref (assoc :preferred true))]))
   :casts (set casts)
   :ranges (into {} (for [[r sub m] ranges] [r {:subtype sub :multirange m}]))
   :functions (update-vals (group-by #(get % "name") functions) #(set (map (comp pattern/function-overload (fn [f] (update-keys f keyword))) %)))
   :relations (into {} (for [[n cs] relations]
                         [n (vec (for [[c t not-null?] cs] (cond-> {:name c :type t} not-null? (assoc :not-null true))))]))
   :operators (update-vals (group-by #(get % "name") operators) #(set (map (comp pattern/operator-overload (fn [o] (update-keys o keyword))) %)))})
