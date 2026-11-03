(ns pgmalli.catalog-source
  "What resources/pgmalli/catalog.edn is read from: the query of pg_catalog, and its result as
   the file holds one version's. bb catalog (dev/catalog.clj) writes the file with them, and
   pgmalli.upstream-test checks the file against the server it runs on.")

(def query
  "One JSON object of pg_catalog's types (an array's facts are its element's: x[]), implicit
   casts, ranges, functions (no procedure) and operators, as format_type names them; no
   extension's."
  "SELECT json_build_object(
     'types', (SELECT json_agg(json_build_array(format_type(oid, NULL), typcategory, typispreferred) ORDER BY 1)
               FROM pg_type WHERE typnamespace = 'pg_catalog'::regnamespace AND typtype <> 'c'
                 AND NOT (typelem <> 0 AND typsubscript = 'array_subscript_handler'::regproc)),
     'casts', (SELECT json_agg(json_build_array(format_type(castsource, NULL), format_type(casttarget, NULL)) ORDER BY 1)
               FROM pg_cast c WHERE castcontext = 'i'
                 AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.classid = 'pg_cast'::regclass AND d.objid = c.oid AND d.deptype = 'e')),
     'functions', (SELECT json_agg(json_build_array(p.proname,
                                                    ARRAY(SELECT format_type(t, NULL) FROM unnest(p.proargtypes::oid[]) WITH ORDINALITY u(t, n) ORDER BY n),
                                                    format_type(p.prorettype, NULL), p.proretset, p.prokind,
                                                    p.provariadic <> 0, p.pronargdefaults,
                                                    a.agginitval IS NOT NULL AND a.aggfinalfn = 0,
                                                    (SELECT json_agg(json_build_array(u.n, format_type(u.t, NULL)) ORDER BY u.i)
                                                     FROM unnest(p.proargnames, p.proallargtypes, p.proargmodes) WITH ORDINALITY u(n, t, m, i)
                                                     WHERE u.m IN ('o', 'b', 't'))) ORDER BY 1)
                   FROM pg_proc p LEFT JOIN pg_aggregate a ON a.aggfnoid = p.oid
                   WHERE p.pronamespace = 'pg_catalog'::regnamespace AND p.prokind <> 'p'),
     'ranges', (SELECT json_agg(json_build_array(format_type(rngtypid, NULL), format_type(rngsubtype, NULL), format_type(rngmultitypid, NULL)) ORDER BY 1)
                FROM pg_range r JOIN pg_type t ON t.oid = r.rngtypid WHERE t.typnamespace = 'pg_catalog'::regnamespace),
     'operators', (SELECT json_agg(json_build_array(o.oprname,
                                                    CASE WHEN o.oprleft = 0 THEN NULL ELSE format_type(o.oprleft, NULL) END,
                                                    format_type(o.oprright, NULL), format_type(o.oprresult, NULL),
                                                    EXISTS (SELECT 1 FROM pg_amop a WHERE a.amopopr IN (o.oid, o.oprnegate) AND a.amopmethod = (SELECT oid FROM pg_am WHERE amname = 'btree'))) ORDER BY 1)
                   FROM pg_operator o WHERE o.oprnamespace = 'pg_catalog'::regnamespace))")

(defn shape
  "A version's catalog as catalog.edn holds it: {:types {name [category preferred?]} :casts
   #{[source target]} :functions {name #{overload}} :operators {name #{overload}}}, an overload
   {:args [type ...] :result type ...}."
  [{:strs [types casts ranges functions operators]}]
  {:types (into {} (for [[n c pref] types] [n (cond-> {:category c} pref (assoc :preferred true))]))
   :casts (set casts)
   :ranges (into {} (for [[r sub m] ranges] [r {:subtype sub :multirange m}]))
   :functions (update-vals (group-by first functions)
                           #(set (for [[_ args ret set? kind variadic? defaults non-null? columns] %]
                                   (cond-> {:args args :result ret}
                                     set? (assoc :set true)
                                     (not= "f" kind) (assoc :kind (keyword kind))
                                     variadic? (assoc :variadic true)
                                     (pos? defaults) (assoc :defaults defaults)
                                     non-null? (assoc :non-null true)
                                     columns (assoc :columns columns)))))
   :operators (update-vals (group-by first operators)
                           #(set (for [[_ l r ret btree?] %]
                                   (cond-> {:args (if l [l r] [r]) :result ret} btree? (assoc :btree true)))))})
