(ns pgmalli.impl.types
  "PostgreSQL's types as pgmalli supports them: one entry per type, keyed by the name
   format_type prints (what a column's :pg/type holds, without a schema or typmod).

     :aliases    other names of the type (pg_type's typname, a cast's spelling)
     :schema     the malli schema of a value; :any for a type the driver hands over as an
                 object of its own
     :eval       the casts pgmalli.impl.eval computes: :integer (with :range), :exact, :float,
                 :text, :json, :temporal (a date, time, timestamp or interval)
     :typmod     what the typmod n of a column bounds: :max-length (varchar(n), char(n)),
                 :bits (bit(n): exactly n), :max-bits (bit varying(n))
     :gen        how a value is generated when the schema alone does not: :bits (0s and 1s)
     :literals   values as text the database reads, generated for an :any column
     :cast       the type a string (or Duration) value is cast to on insert: the driver sends a
                 string as varchar, which the database converts to the string types alone; an
                 array's elements are cast to :array-cast, else :cast, else the type itself
     :read       what pgjdbc returns instead of :schema's values: a schema, :bit (a Boolean
                 for bit(1), else a PGobject), :or-double (a Double for NaN)
     :extension  the extension that adds the type, outside pg_catalog

   unsupported lists the types pgmalli has no entry for yet, each with why. pgmalli.upstream-test
   checks every type of pg_catalog and of the extensions PostgreSQL ships is in one of them."
  (:require [clojure.string :as str]))

(def types
  {"smallint" {:aliases ["int2"] :schema :pg/smallint :eval :integer :range [-32768 32767]}
   "integer" {:aliases ["int4"] :schema :pg/integer :eval :integer :range [-2147483648 2147483647]}
   ;; a bigint is exactly a long
   "bigint" {:aliases ["int8"] :schema :int :eval :integer :range [Long/MIN_VALUE Long/MAX_VALUE]}
   "oid" {:schema [:int {:min 0 :max 4294967295}] :eval :integer}
   "numeric" {:aliases ["decimal"] :schema 'decimal? :eval :exact :read :or-double}
   "real" {:aliases ["float4"] :schema :double :eval :float :read 'float?}
   "double precision" {:aliases ["float8"] :schema :double :eval :float}
   "text" {:schema :string :eval :text}
   "character varying" {:aliases ["varchar"] :schema :string :eval :text :typmod :max-length}
   "character" {:aliases ["char" "bpchar"] :schema :string :eval :text :typmod :max-length :array-cast :bpchar}
   "name" {:schema :string :eval :text}
   "\"char\"" {:schema :string}
   "bit" {:schema :string :typmod :bits :gen :bits :cast :varbit :read :bit}
   "bit varying" {:aliases ["varbit"] :schema :string :typmod :max-bits :gen :bits :cast (keyword "bit varying") :read :any}
   "boolean" {:schema :boolean}
   "uuid" {:schema :uuid}
   "bytea" {:schema 'bytes?}
   "date" {:schema :time/local-date :eval :temporal}
   "time without time zone" {:aliases ["time"] :schema :time/local-time :eval :temporal}
   "time with time zone" {:aliases ["timetz"] :schema :time/offset-time}
   "timestamp without time zone" {:aliases ["timestamp"] :schema :time/local-date-time :eval :temporal}
   "timestamp with time zone" {:aliases ["timestamptz"] :schema :time/instant :eval :temporal}
   ;; pgjdbc takes no Duration: it goes as text, in ISO 8601
   "interval" {:schema :time/duration :cast :interval :eval :temporal}
   "json" {:schema :any :eval :json}
   "jsonb" {:schema :any :eval :json}

   "inet" {:schema :any :literals ["10.0.0.1" "192.168.1.0/24" "::1"] :cast :inet}
   "cidr" {:schema :any :literals ["10.0.0.0/8" "192.168.1.0/24" "2001:db8::/32"] :cast :cidr}
   "macaddr" {:schema :any :literals ["08:00:2b:01:02:03" "08-00-2b-01-02-04"] :cast :macaddr}
   "macaddr8" {:schema :any :literals ["08:00:2b:01:02:03:04:05"] :cast :macaddr8}
   "money" {:schema :any :literals ["12.34" "0.00" "-5.50"] :cast :money}
   "xml" {:schema :any :literals ["<a/>" "<a b=\"1\">x</a>"] :cast :xml}
   "tsvector" {:schema :any :literals ["'a' 'b'" "'fat':2 'rat':3"] :cast :tsvector}
   "tsquery" {:schema :any :literals ["'a' & 'b'" "'fat' | 'rat'"] :cast :tsquery}
   "jsonpath" {:schema :any :literals ["$.a" "$[*] ? (@ > 1)"] :cast :jsonpath}
   "pg_lsn" {:schema :any :literals ["0/16B3748" "1/0"] :cast :pg_lsn}
   "tid" {:schema :any :literals ["(0,1)" "(1,2)"] :cast :tid}
   "pg_snapshot" {:schema :any :literals ["10:20:" "10:20:10,14,15"] :cast :pg_snapshot}
   "txid_snapshot" {:schema :any :literals ["10:20:"] :cast :txid_snapshot}
   "xid" {:schema :any :literals ["1" "1234"] :cast :xid}
   "xid8" {:schema :any :literals ["1" "1234"] :cast :xid8}
   "cid" {:schema :any :literals ["0" "3"] :cast :cid}
   "point" {:schema :any :literals ["(1,2)" "(0,0)" "(-1.5,2.5)"] :cast :point}
   "line" {:schema :any :literals ["{1,-1,0}" "{0,1,-2}"] :cast :line}
   "lseg" {:schema :any :literals ["[(0,0),(1,1)]"] :cast :lseg}
   "box" {:schema :any :literals ["((0,0),(1,1))" "((1,1),(2,3))"] :cast :box}
   "path" {:schema :any :literals ["[(0,0),(1,1),(2,0)]" "((0,0),(1,1),(2,0))"] :cast :path}
   "polygon" {:schema :any :literals ["((0,0),(1,0),(1,1))"] :cast :polygon}
   "circle" {:schema :any :literals ["<(0,0),1>" "<(1,1),2.5>"] :cast :circle}
   ;; no empty range: a WITHOUT OVERLAPS key refuses one
   "int4range" {:schema :any :literals ["[1,10)" "[20,30)" "(,5]"] :cast :int4range}
   "int8range" {:schema :any :literals ["[1,10)" "[20,30)"] :cast :int8range}
   "numrange" {:schema :any :literals ["[1.5,2.5]" "[3,4)"] :cast :numrange}
   "tsrange" {:schema :any :literals ["[2020-01-01 00:00,2020-01-02 00:00)" "[2021-01-01 00:00,2021-06-01 00:00)"] :cast :tsrange}
   "tstzrange" {:schema :any :literals ["[2020-01-01 00:00+00,2020-01-02 00:00+00)" "[2021-01-01 00:00+00,2021-06-01 00:00+00)"] :cast :tstzrange}
   "daterange" {:schema :any :literals ["[2020-01-01,2020-02-01)" "[2021-01-01,2021-06-01)"] :cast :daterange}
   "int4multirange" {:schema :any :literals ["{[1,3),[5,7)}" "{[10,20)}"] :cast :int4multirange}
   "int8multirange" {:schema :any :literals ["{[1,3)}" "{[10,20)}"] :cast :int8multirange}
   "nummultirange" {:schema :any :literals ["{[1.5,2.5]}" "{[3,4)}"] :cast :nummultirange}
   "tsmultirange" {:schema :any :literals ["{[2020-01-01 00:00,2020-01-02 00:00)}"] :cast :tsmultirange}
   "tstzmultirange" {:schema :any :literals ["{[2020-01-01 00:00+00,2020-01-02 00:00+00)}"] :cast :tstzmultirange}
   "datemultirange" {:schema :any :literals ["{[2020-01-01,2020-02-01)}" "{[2021-01-01,2021-06-01)}"] :cast :datemultirange}
   "regclass" {:schema :any :literals ["pg_class" "pg_type"] :cast :regclass}
   "regtype" {:schema :any :literals ["integer" "text"] :cast :regtype}
   "regrole" {:schema :any :literals ["postgres"] :cast :regrole}
   "regproc" {:schema :any :literals ["now"] :cast :regproc}
   "regprocedure" {:schema :any :literals ["now()"] :cast :regprocedure}
   ;; one operator of the name: '+' names several
   "regoper" {:schema :any :literals ["||/" "|/"] :cast :regoper}
   "regoperator" {:schema :any :literals ["+(integer,integer)"] :cast :regoperator}
   "regnamespace" {:schema :any :literals ["public"] :cast :regnamespace}
   "regconfig" {:schema :any :literals ["english"] :cast :regconfig}
   "regdictionary" {:schema :any :literals ["simple"] :cast :regdictionary}
   "regcollation" {:schema :any :literals ["\"C\""] :cast :regcollation}

   ;; strings compare case-insensitively in the database, which a :string does not
   "citext" {:schema :string :read :any :extension "citext"}
   ;; pgjdbc returns a java.util.Map
   "hstore" {:schema :any :literals ["a=>1" "\"b c\"=>NULL" ""] :cast :hstore :extension "hstore"}
   "ltree" {:schema :any :literals ["top" "top.a.b" "a_1.B2"] :cast :ltree :extension "ltree"}
   "lquery" {:schema :any :literals ["*.a.*" "top.*"] :cast :lquery :extension "ltree"}
   "ltxtquery" {:schema :any :literals ["a & b" "top"] :cast :ltxtquery :extension "ltree"}
   "cube" {:schema :any :literals ["(1,2),(3,4)" "1" "(0,0,0)"] :cast :cube :extension "cube"}
   "seg" {:schema :any :literals ["1 .. 2" "0.5" "<1"] :cast :seg :extension "seg"}
   "query_int" {:schema :any :literals ["1&2" "1|(2&3)"] :cast :query_int :extension "intarray"}
   "ean13" {:schema :any :literals ["9780306406157" "4006381333931"] :cast :ean13 :extension "isn"}
   "isbn13" {:schema :any :literals ["9780306406157"] :cast :isbn13 :extension "isn"}
   "isbn" {:schema :any :literals ["0306406152"] :cast :isbn :extension "isn"}
   "ismn13" {:schema :any :literals ["9790260000438"] :cast :ismn13 :extension "isn"}
   "ismn" {:schema :any :literals ["M-2600-0043-8"] :cast :ismn :extension "isn"}
   "issn13" {:schema :any :literals ["9770317847001"] :cast :issn13 :extension "isn"}
   "issn" {:schema :any :literals ["0317-8471"] :cast :issn :extension "isn"}
   "upc" {:schema :any :literals ["012345678905"] :cast :upc :extension "isn"}})

(def unsupported
  "The types of pg_catalog and of the extensions PostgreSQL ships that have no entry yet, with
   why: what to add next. A column of one is :any, listed in :unrendered, and generates no value."
  (merge
   (zipmap ["aclitem" "pg_node_tree" "pg_ndistinct" "pg_dependencies" "pg_mcv_list" "pg_brin_bloom_summary" "pg_brin_minmax_multi_summary"]
           (repeat "the catalog's own, which no table column holds"))
   {"refcursor" "a cursor's name, meaningful only inside the transaction that opened it"}
   (zipmap ["gtsvector" "gbtreekey2" "gbtreekey4" "gbtreekey8" "gbtreekey16" "gbtreekey32" "gbtreekey_var" "ghstore" "intbig_gkey" "ltree_gist" "gtrgm"]
           (repeat "a GiST index's storage type, with no input function"))))

(def ^:private by-name
  (into {} (for [[t e] types, n (cons t (:aliases e))] [n (assoc e :name t)])))

(defn entry
  "The entry of a type, by its name or an alias (the :name it is keyed by added); nil for
   another type."
  [type-name]
  ;; a type outside the search path comes schema-qualified: "ext.citext"
  (by-name (some-> type-name (str/replace #"^[^.\"]+\." ""))))

(defn known?
  "Whether a column type has an entry: a pg_catalog type in pg_catalog (type-schema nil or
   \"pg_catalog\"), an extension's in whatever schema the extension went to."
  [type-name type-schema]
  (when-let [e (entry type-name)]
    (or (:extension e) (nil? type-schema) (= "pg_catalog" type-schema))))

(defn json?
  "Whether a column of the type holds a JSON document."
  [type-name]
  (= :json (:eval (entry type-name))))

(defn by-keyword
  "{name entry} of the types whose entry satisfies pred, every name and alias spelt as
   pgmalli.impl.expr spells a cast's type (:double-precision)."
  [pred]
  (into {} (for [[n e] by-name :when (pred e)] [(keyword (str/replace n " " "-")) e])))

(defn keywords
  "The names by-keyword gives."
  [pred]
  (set (keys (by-keyword pred))))
