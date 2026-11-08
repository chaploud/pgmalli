(ns pgmalli-next.impl.types
  "PostgreSQL's types as pgmalli supports them: one entry per type, keyed by the name
   format_type prints (what a column's :pg/type holds, without a schema or typmod).

     :aliases    other names of the type (pg_type's typname, a cast's spelling)
     :schema     the malli schema of a value; :any for a type the driver hands over as an
                 object of its own
     :value      the kind of value: :integer (with :range), :exact, :float, :json; a string type has none (its category, S, says it)
     :typmod     what the typmod n of a column bounds: :max-length (varchar(n), char(n)),
                 :bits (bit(n): exactly n), :max-bits (bit varying(n))
     :literals   values as text the database reads, generated for an :any column
     :cast       the type :uncast-string says to cast a string to, for a type whose :schema is
                 :string but which takes no varchar: bit's varbit (which keeps its length), bit
                 varying itself
     :read       what pgjdbc returns instead of :schema's values: a schema, :bit (a Boolean
                 for bit(1), else a PGobject), :or-double (a Double for NaN or an infinity),
                 :text (a PGobject of the type of its text, as pgmalli-next.jdbc/builder reads
                 money: pgjdbc's Double cannot hold it, and refuses its text past 999.99)
     :extension  the extension that adds the type, outside pg_catalog

   catalog is what PostgreSQL's own catalog says of pg_catalog's types (typcategory,
   typispreferred), casts, ranges, functions and operators, which bb catalog writes to
   resources/pgmalli-next/impl/catalog.edn from each PostgreSQL version CI runs."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def types
  {"smallint" {:aliases ["int2"] :schema :pg/smallint :value :integer :range [-32768 32767]}
   "integer" {:aliases ["int4" "int"] :schema :pg/integer :value :integer :range [-2147483648 2147483647]}
   ;; a bigint is exactly a long
   "bigint" {:aliases ["int8"] :schema :int :value :integer :range [Long/MIN_VALUE Long/MAX_VALUE]}
   "oid" (let [[lo hi :as range] [0 4294967295]] {:schema [:int {:min lo :max hi}] :value :integer :range range})
   "numeric" {:aliases ["decimal" "dec"] :schema 'decimal? :value :exact :read :or-double}
   "real" {:aliases ["float4"] :schema :double :value :float :read 'float?}
   "double precision" {:aliases ["float8" "float"] :schema :double :value :float}
   "text" {:schema :string}
   "character varying" {:aliases ["varchar" "char varying" "national character varying" "national char varying" "nchar varying"] :schema :string :typmod :max-length}
   "character" {:aliases ["char" "bpchar" "national character" "national char" "nchar"] :schema :string :typmod :max-length}
   "name" {:schema :string}
   ;; one byte (pgtypes/char-value?): PostgreSQL keeps a longer string's first alone, without a word
   "\"char\"" {:schema [:string {:pg/one-byte true}]}
   "bit" {:schema :string :typmod :bits :cast :varbit :read :bit}
   "bit varying" {:aliases ["varbit"] :schema :string :typmod :max-bits :cast (keyword "bit varying") :read :any}
   "boolean" {:aliases ["bool"] :schema :boolean}
   "uuid" {:schema :uuid}
   "bytea" {:schema 'bytes?}
   "date" {:schema :time/local-date}
   "time without time zone" {:aliases ["time"] :schema :time/local-time}
   "time with time zone" {:aliases ["timetz"] :schema :time/offset-time}
   "timestamp without time zone" {:aliases ["timestamp"] :schema :time/local-date-time}
   "timestamp with time zone" {:aliases ["timestamptz"] :schema :time/instant}
   "interval" {:schema :time/duration}
   "json" {:schema :any :value :json}
   "jsonb" {:schema :any :value :json}

   "inet" {:schema :any :literals ["10.0.0.1" "192.168.1.0/24" "::1"]}
   "cidr" {:schema :any :literals ["10.0.0.0/8" "192.168.1.0/24" "2001:db8::/32"]}
   "macaddr" {:schema :any :literals ["08:00:2b:01:02:03" "08-00-2b-01-02-04"]}
   "macaddr8" {:schema :any :literals ["08:00:2b:01:02:03:04:05"]}
   "money" {:schema :any :literals ["12.34" "0.00" "-5.50"] :read :text}
   "xml" {:schema :any :literals ["<a/>" "<a b=\"1\">x</a>"]}
   "tsvector" {:schema :any :literals ["'a' 'b'" "'fat':2 'rat':3"]}
   "tsquery" {:schema :any :literals ["'a' & 'b'" "'fat' | 'rat'"]}
   "jsonpath" {:schema :any :literals ["$.a" "$[*] ? (@ > 1)"]}
   "pg_lsn" {:schema :any :literals ["0/16B3748" "1/0"]}
   "tid" {:schema :any :literals ["(0,1)" "(1,2)"]}
   "pg_snapshot" {:schema :any :literals ["10:20:" "10:20:10,14,15"]}
   "txid_snapshot" {:schema :any :literals ["10:20:"]}
   "xid" {:schema :any :literals ["1" "1234"]}
   "xid8" {:schema :any :literals ["1" "1234"]}
   "cid" {:schema :any :literals ["0" "3"]}
   "point" {:schema :any :literals ["(1,2)" "(0,0)" "(-1.5,2.5)"]}
   "line" {:schema :any :literals ["{1,-1,0}" "{0,1,-2}"]}
   "lseg" {:schema :any :literals ["[(0,0),(1,1)]"]}
   "box" {:schema :any :literals ["((0,0),(1,1))" "((1,1),(2,3))"]}
   "path" {:schema :any :literals ["[(0,0),(1,1),(2,0)]" "((0,0),(1,1),(2,0))"]}
   "polygon" {:schema :any :literals ["((0,0),(1,0),(1,1))"]}
   "circle" {:schema :any :literals ["<(0,0),1>" "<(1,1),2.5>"]}
   ;; no empty range: a WITHOUT OVERLAPS key refuses one
   "int4range" {:schema :any :literals ["[1,10)" "[20,30)" "(,5]"]}
   "int8range" {:schema :any :literals ["[1,10)" "[20,30)"]}
   "numrange" {:schema :any :literals ["[1.5,2.5]" "[3,4)"]}
   "tsrange" {:schema :any :literals ["[2020-01-01 00:00,2020-01-02 00:00)" "[2021-01-01 00:00,2021-06-01 00:00)"]}
   "tstzrange" {:schema :any :literals ["[2020-01-01 00:00+00,2020-01-02 00:00+00)" "[2021-01-01 00:00+00,2021-06-01 00:00+00)"]}
   "daterange" {:schema :any :literals ["[2020-01-01,2020-02-01)" "[2021-01-01,2021-06-01)"]}
   "int4multirange" {:schema :any :literals ["{[1,3),[5,7)}" "{[10,20)}"]}
   "int8multirange" {:schema :any :literals ["{[1,3)}" "{[10,20)}"]}
   "nummultirange" {:schema :any :literals ["{[1.5,2.5]}" "{[3,4)}"]}
   "tsmultirange" {:schema :any :literals ["{[2020-01-01 00:00,2020-01-02 00:00)}"]}
   "tstzmultirange" {:schema :any :literals ["{[2020-01-01 00:00+00,2020-01-02 00:00+00)}"]}
   "datemultirange" {:schema :any :literals ["{[2020-01-01,2020-02-01)}" "{[2021-01-01,2021-06-01)}"]}
   "regclass" {:schema :any :literals ["pg_class" "pg_type"]}
   "regtype" {:schema :any :literals ["integer" "text"]}
   "regrole" {:schema :any :literals ["pg_monitor"]}
   "regproc" {:schema :any :literals ["now"]}
   "regprocedure" {:schema :any :literals ["now()"]}
   ;; one operator of the name: '+' names several
   "regoper" {:schema :any :literals ["||/" "|/"]}
   "regoperator" {:schema :any :literals ["+(integer,integer)"]}
   "regnamespace" {:schema :any :literals ["pg_catalog"]}
   "regconfig" {:schema :any :literals ["english"]}
   "regdictionary" {:schema :any :literals ["simple"]}
   "regcollation" {:schema :any :literals ["\"C\""]}

   ;; strings compare case-insensitively in the database, which a :string does not
   "citext" {:schema :string :read :any :extension "citext"}
   ;; pgjdbc returns a java.util.Map
   "hstore" {:schema :any :literals ["a=>1" "\"b c\"=>NULL" ""] :extension "hstore"}
   "ltree" {:schema :any :literals ["top" "top.a.b" "a_1.B2"] :extension "ltree"}
   "lquery" {:schema :any :literals ["*.a.*" "top.*"] :extension "ltree"}
   "ltxtquery" {:schema :any :literals ["a & b" "top"] :extension "ltree"}
   "cube" {:schema :any :literals ["(1,2),(3,4)" "1" "(0,0,0)"] :extension "cube"}
   "seg" {:schema :any :literals ["1 .. 2" "0.5" "<1"] :extension "seg"}
   "query_int" {:schema :any :literals ["1&2" "1|(2&3)"] :extension "intarray"}
   "ean13" {:schema :any :literals ["9780306406157" "4006381333931"] :extension "isn"}
   "isbn13" {:schema :any :literals ["9780306406157"] :extension "isn"}
   "isbn" {:schema :any :literals ["0306406152"] :extension "isn"}
   "ismn13" {:schema :any :literals ["9790260000438"] :extension "isn"}
   "ismn" {:schema :any :literals ["M-2600-0043-8"] :extension "isn"}
   "issn13" {:schema :any :literals ["9770317847001"] :extension "isn"}
   "issn" {:schema :any :literals ["0317-8471"] :extension "isn"}
   "upc" {:schema :any :literals ["012345678905"] :extension "isn"}})

(defn number-entry?
  "Whether types entry e is a number type's: an integer, exact or float :value."
  [e]
  (boolean (#{:integer :exact :float} (:value e))))

(def sql-words
  "The type of each SQL value word PostgreSQL's grammar reads (docs 9.9.5, 9.27), lower-cased."
  {"current_timestamp" "timestamp with time zone" "current_date" "date" "current_time" "time with time zone"
   "localtimestamp" "timestamp without time zone" "localtime" "time without time zone"
   "current_user" "name" "session_user" "name" "current_role" "name" "user" "name"
   "current_schema" "name" "current_catalog" "name" "system_user" "text"})

(def ^:private by-name
  (into {} (for [[t e] types, n (cons t (:aliases e))] [n (assoc e :name t)])))

(defn type-parts
  "[schema name array?] of a type as format_type prints it (or a cast spells it): the schema it
   is qualified with (a type outside the search path: ext.citext), nil for none; its name
   without its typmod, wherever format_type prints it (timestamp(3) with time zone is
   timestamp with time zone), a quoted one as printed, quotes and all; whether it is an array
   (bit(8)[]). nil for no string."
  [t]
  (when-let [[_ schema n brackets] (and (string? t) (re-matches #"(?s)(?:(\"(?:[^\"]|\"\")*\"|[^.\"]+?)\.)?(\"(?:[^\"]|\"\")*\"|[^\"]+?)((?:\[\])*)" t))]
    [schema (cond-> n (not (str/starts-with? n "\"")) (str/replace #"\([^)]*\)" "")) (boolean (seq brackets))]))

(defn unquoted
  "An identifier as type-parts gives it, without its quotes: the name its catalog holds."
  [ident]
  (if (str/starts-with? ident "\"") (str/replace (subs ident 1 (dec (count ident))) "\"\"" "\"") ident))

(defn bare-name
  "A type's name without its schema or typmod (type-parts), \"x[]\" for an array:
   \"public.bit(8)[]\" is \"bit[]\"."
  [t]
  (when-let [[_ n array?] (type-parts t)] (cond-> n array? (str "[]"))))

(defn entry
  "The entry of a type, by its name or an alias, without its typmod (the :name it is keyed by
   added); nil for another type: an array, a type of another schema named as a pg_catalog type
   (\"s\".date), but an extension's in whatever schema it is."
  [type-name]
  (when-let [[schema n array?] (type-parts type-name)]
    (when-let [e (when-not array? (by-name n))]
      (when (or (nil? schema) (= "pg_catalog" schema) (:extension e)) e))))

(defn known?
  "Whether a column type has an entry: a pg_catalog type in pg_catalog (type-schema nil or
   \"pg_catalog\"), an extension's in whatever schema the extension went to, where the type is
   that extension's (type-extension, the one pg_depend says made it)."
  [type-name type-schema type-extension]
  (when-let [e (entry type-name)]
    (if (:extension e)
      (= (:extension e) type-extension)
      (or (nil? type-schema) (= "pg_catalog" type-schema)))))

(defn json?
  "Whether a column of the type holds a JSON document."
  [type-name]
  (= :json (:value (entry type-name))))

(defn by-keyword
  "{name entry} of the types whose entry satisfies pred, every name and alias spelt as
   HoneySQL spells a cast's type (:double-precision)."
  [pred]
  (into {} (for [[n e] by-name :when (pred e)] [(keyword (str/replace n " " "-")) e])))

(def catalog
  "pg_catalog of the PostgreSQL versions pgmalli supports, from
   resources/pgmalli-next/impl/catalog.edn (bb catalog writes it):

   :versions   [v ...]
   :types      {name {:category c :preferred true}}
   :casts      #{[source target context]}, context pg_cast's castcontext: \"i\" implicit,
               \"a\" assignment, \"e\" explicit
   :ranges     {range {:subtype t :multirange m}}
   :functions  {name [overload ...]}
   :operators  {name [overload ...]}
   :relations  {name [{:name :type :not-null} ...]}, pg_catalog's tables and views

   An overload is {:args [type ...] :result type} with :set, :kind (:a an aggregate, :w a window
   function), :variadic, :defaults (how many of the last arguments may be left out), :non-null
   (never NULL: count, row_number ...), :columns ([[name type] ...] it returns in FROM) and :btree (an
   operator of a btree operator family, or its negator). A type, range, cast ({:in ...} its
   fourth element), overload or column not every version has carries :in, the versions that do."
  (delay (edn/read-string (slurp (io/resource "pgmalli-next/impl/catalog.edn")))))

(defn version-label
  "How the generated file names PostgreSQL major version major (\"17\"): \"PostgreSQL 17\"."
  [major]
  (str "PostgreSQL " major))

(defn label-version
  "The major version a version-label names, nil for none."
  [label]
  (some->> label str (re-matches #"PostgreSQL (\d+)") second))

(defn oldest-version
  "The oldest PostgreSQL major version pgmalli reads, the catalog's first (\"16\")."
  []
  (first (:versions @catalog)))

(defn element
  "The element type of an array type t (\"bigint[]\" is bigint); nil for another."
  [t]
  (when (string? t) (second (re-matches #"(.+)\[\]" t))))

(defn array-of
  "The array type of elements of type t: \"x[]\", t itself for an array (PostgreSQL's array types
   take any number of dimensions); nil for no name."
  [t]
  (cond (element t) t (string? t) (str t "[]")))

(defn canonical-name
  "The name of type t as format_type prints it: a type of types (timestamptz, varchar(10)) by its
   name, without a schema or typmod, an array of one as \"x[]\"; another as it is."
  [t]
  (when (string? t)
    (if-let [e (element t)]
      (some-> (canonical-name e) array-of)
      (:name (entry t) t))))

(defn typname
  "Type n as pg_type names it (integer as int4, double precision[] as float8[]), the name pgjdbc
   takes of a PGobject's type; n where the catalog holds no other."
  [n]
  (if-let [e (element n)]
    (str (typname e) "[]")
    (get-in @catalog [:types n :typname] n)))

(defn short-name
  "The short name of type n a cast and a description write: one of spaces as its first alias
   (bit varying as varbit); n otherwise."
  [n]
  (or (when (str/includes? n " ") (first (:aliases (entry n)))) n))

(defn cast-name
  "canonical-name of the type a cast spells t (:double-precision, :TIMESTAMPTZ, \"bpchar\"):
   HoneySQL writes - as a space, and PostgreSQL folds the case."
  [t]
  (canonical-name (str/lower-case (str/replace (name t) "-" " "))))

(defn type-schema
  "A column's schema for a value of type t (a name or an alias, \"x[]\" an array), as the registry
   writes one; nil for a type types lacks."
  [t]
  (cond
    ;; pgjdbc reads a void value as a PGobject of ""
    (= "void" t) [:any {:pg/type "void"}]
    (element t) (when-let [{n :name s :schema} (entry (element t))]
                  [:vector {:pg/type (array-of n)} s])
    :else (when-let [{n :name s :schema} (some-> t entry)]
            (if (vector? s) (into [(first s) (assoc (second s) :pg/type n)] (drop 2 s)) [s {:pg/type n}]))))

(defn category
  "PostgreSQL's typcategory of type t (a name as format_type prints it, \"x[]\", or [:enum
   name]): \"A\" for an array, \"E\" an enum; nil for a type pg_catalog lacks."
  [t]
  (cond (vector? t) (when (= :enum (first t)) "E")
        (element t) "A"
        :else (get-in @catalog [:types t :category])))

(defn preferred?
  "Whether t is the preferred type of its category (typispreferred)."
  [t]
  (boolean (and (string? t) (get-in @catalog [:types t :preferred]))))

(defn extension-string?
  "Whether t is an extension's type of strings (citext), which pg_catalog's catalog does not
   hold."
  [t]
  (boolean (and (not= "S" (category t))
                (when-let [e (and (string? t) (entry t))] (and (:extension e) (= :string (:schema e)))))))

(defn implicit?
  "Whether PostgreSQL converts a value of type from to type to implicitly (pg_cast's castcontext
   'i', an array element by element); an extension's type of strings to text, as citext's own
   casts, which the catalog does not hold, do."
  [from to]
  (or (= from to)
      (contains? (:casts @catalog) [from to "i"])
      (and (= "text" to) (extension-string? from))
      (let [a (element from) b (element to)] (boolean (and a b (implicit? a b))))))

(def ^:private keywords (delay (set (:keywords @catalog))))

(defn keyword-word?
  "Whether word s (lower case) is one PostgreSQL reads as a keyword where a column's name goes
   (pg_get_keywords' reserved and type or function names): a column of the name is quoted."
  [s]
  (contains? @keywords s))

(def ^:private oid-aliases
  (delay (set (for [[from to c] (:casts @catalog) :when (and (= "oid" from) (= "i" c))] to))))

(defn oid-alias?
  "Whether type t is an alias of oid (regclass, regproc ...): a type oid converts to
   implicitly, as pg_cast holds it."
  [t]
  (contains? @oid-aliases t))

(defn oid-like?
  "Whether type t is oid or an alias of it: a number goes to it as an oid, within oid's range."
  [t]
  (or (= "oid" t) (oid-alias? t)))

(def ^:private contexts
  (delay (reduce (fn [m [from to c]] (update m [from to] (fnil conj #{}) c)) {} (:casts @catalog))))

(defn cast-contexts
  "The castcontexts of pg_cast's casts from type from to type to, in any version the catalog
   holds: a set of \"i\", \"a\" and \"e\"."
  [from to]
  (get @contexts [from to] #{}))
