(ns pgmalli-next.impl.pattern
  "Facts about a schema, derived from the structure read by pgmalli-next.impl.ir: its types,
   tables, columns, keys and routines. A CHECK is not read: the database checks it.

   A fact is a map with :fact (the kind), :schema, and :table / :column / :constraint where they
   apply. Kinds:
     :enum-type    an enum type of the schema           {:type-name :values}
     :view         a view or materialized view          {:materialized?}; its columns follow, all nullable
     :relation     a table of no column, which no fact else names
     :column       a column                              {:type :domain :position :nullable? :default :default-sql :identity :generated}
                   :type is a domain's base type under all its domains, as ir.sql reads it (:domain
                   the type the column declares: the domain, or d[] for an array of domain d), :default the default's literal value, :default-sql its SQL; :identity is
                   :always, :default or :serial (a nextval default); :generated is the SQL of the expression
     :enum         column of an enum type (or an array of it) {:type-name :values :array?}
     :unknown-type type outside the mapping table (rendered as :any) {:type :type-schema (when not pg_catalog's or the schema's own)}
     :max-length   varchar(n), char(n), bit(n) or bit varying(n) {:max}
     :precision    numeric(p, s) {:precision p :scale s}
     :not-null     NOT NULL NOT VALID (PostgreSQL 18), the column nullable {:constraint :valid? false}
     :primary-key  table primary key                      {:columns :deferrable?}
     :exclude      EXCLUDE constraint                     {:method :partial? :elements [{:column :op}]}
     :unique       UNIQUE constraint                      {:columns :nulls-distinct? (false for NULLS NOT DISTINCT)
                                                           :index? (from a unique index, not a constraint)
                                                           :partial? (the index has a predicate)
                                                           :predicate (its predicate, where ir.sql reads it: [[:= column value] [:is-null column] ...])
                                                           :deferrable?}
     :references   FOREIGN KEY                            {:columns :match (:simple or :full) :to {:schema :table :columns} :valid? (false for NOT VALID)}
     :not-enforced a NOT ENFORCED FOREIGN KEY             {:constraint :input} (nothing to render)
     :trigger      a row-level trigger left enabled       {:name :before :insert :update :delete} (nothing to render)
     :inherited    a table other tables inherit (not as partitions) {} (nothing to render)
     :sequence     a sequence of the schema                {:name}
     :function     a function a query may call            {:name :overload}
     :operator     an operator                            {:name :overload}
                   :overload as pgmalli-next.impl.types/catalog holds pg_catalog's, its types named as a
                   column's :type is"
  (:require [clojure.string :as str]
            [pgmalli-next.impl.types :as types]))

(defn- number-literal [s]
  (cond (re-matches #"-?\d+" s) (bigint s)
        (re-matches #"-?\d+(?:\.\d+)?(?:[eE][-+]?\d+)?" s) (bigdec s)))

(defn- default-literal
  "The value of a default PostgreSQL prints as a literal (42, 1.5, true, 'happy'::mood, a
   number it quotes, cast to a number type: '-1'::integer, '10000000000'::bigint); nil for an
   expression (now(), nextval(...)) and for a cast of a length or precision ('abcdef'::varchar(3)),
   which stores another value than the literal (abc)."
  [s]
  (cond (#{"true" "false"} s) (= "true" s)
        (number-literal s) (number-literal s)
        :else (when-let [[_ quoted t] (re-matches #"(?s)'((?:[^']++|'')*+)'(?:::([^(]+))?" s)]
                (let [v (str/replace quoted "''" "'")]
                  (or (when (some-> t types/entry types/number-entry?) (number-literal v)) v)))))

(defn- column-facts
  "The :column fact of a column and those of its type."
  [base {cname :name :keys [data_type domain type_schema type_extension is_nullable not_null_not_valid default_value max_length numeric_typmod identity generated_expr position]} enums]
  (let [[_ n array?] (types/type-parts data_type)
        ident (types/unquoted n)
        base (assoc base :column cname)
        ;; an enum of another schema may share a name with one of this schema's
        enum (when (and (contains? enums ident) (= type_schema (:schema base)))
               (cond-> (merge base {:fact :enum :type-name ident :values (get enums ident)}) array? (assoc :array? true)))
        lit (some-> default_value default-literal)]
    (cond-> [(merge base {:fact :column :type data_type :position position :nullable? (boolean is_nullable)}
                    (when domain {:domain domain})
                    (when default_value {:default-sql default_value})
                    (when (some? lit) {:default (if (and (integer? lit) (<= Long/MIN_VALUE lit Long/MAX_VALUE)) (long lit) lit)})
                    (cond identity {:identity ({"ALWAYS" :always "BY DEFAULT" :default} identity)}
                          (some-> default_value (str/starts-with? "nextval(")) {:identity :serial})
                    (when generated_expr {:generated generated_expr}))]
      enum (conj enum)
      ;; a type of another schema: the schema's facts are read alone, so they cannot refer to it
      (not (or enum (types/known? n type_schema type_extension)))
      (conj (cond-> (merge base {:fact :unknown-type :type data_type})
              (not (#{nil "pg_catalog" (:schema base)} type_schema)) (assoc :type-schema type_schema)))
      (and max_length (:typmod (types/entry n)))
      (conj (merge base {:fact :max-length :max max_length}))
      numeric_typmod
      (conj (merge base {:fact :precision :precision (bit-and (bit-shift-right numeric_typmod 16) 0xffff)
                         :scale (let [s (bit-and numeric_typmod 0xffff)] (if (> s 1023) (- s 2048) s))}))
      ;; the column is nullable: rows from before the constraint may hold NULL
      not_null_not_valid
      (conj (merge base {:fact :not-null :constraint not_null_not_valid :valid? false})))))

(defn- key-facts [base {cname :name :keys [type columns references nulls_not_distinct is_valid is_enforced index partial predicate exclude deferrable]}]
  (case type
    "EXCLUDE" [(merge base {:fact :exclude :constraint cname :method (:method exclude) :partial? (boolean (:partial exclude))
                            :elements (mapv #(select-keys % [:column :op]) (:elements exclude))})]
    ;; an invalid index (a CREATE INDEX CONCURRENTLY that failed, a key added to a partitioned
    ;; table ONLY) holds nothing
    "PRIMARY KEY" (when-not (false? is_valid)
                    [(cond-> (merge base {:fact :primary-key :constraint cname :columns columns}) deferrable (assoc :deferrable? true))])
    "UNIQUE" (when-not (false? is_valid)
               [(cond-> (merge base {:fact :unique :constraint cname :columns columns :nulls-distinct? (not nulls_not_distinct) :index? (boolean index) :partial? (boolean partial)})
                  predicate (assoc :predicate (mapv (fn [[op & more]] (into [(keyword op)] more)) predicate))
                  deferrable (assoc :deferrable? true))])
    ;; NOT ENFORCED: the database never checks it, so it is not a reference, only noted
    "FOREIGN KEY" (if (false? is_enforced)
                    [(merge base {:fact :not-enforced :constraint cname :input (str "FOREIGN KEY " (pr-str columns))})]
                    [(cond-> (merge base {:fact :references :constraint cname :columns columns
                                          :match (if (= "FULL" (:match references)) :full :simple)
                                          :to (select-keys references [:schema :table :columns])})
                       (false? is_valid) (assoc :valid? false))])
    []))

(defn function-overload
  "An overload as pgmalli-next.impl.types/catalog holds one, of a function as ir.sql and the catalog's
   query read it: {:args :result :set :kind :variadic :defaults :non_null :columns}, :non_null the
   catalog's alone: a schema's own aggregate may return NULL from its transition function."
  [{:keys [args result set kind variadic defaults non_null columns]}]
  (cond-> {:args (vec args) :result result}
    set (assoc :set true)
    (not= "f" kind) (assoc :kind (keyword kind))
    variadic (assoc :variadic true)
    (pos? defaults) (assoc :defaults defaults)
    non_null (assoc :non-null true)
    columns (assoc :columns (mapv vec columns))))

(defn operator-overload
  "An operator's overload, of {:args :result :set :btree} as ir.sql and the catalog's query read it."
  [{:keys [args result set btree]}]
  (cond-> {:args (vec args) :result result} set (assoc :set true) btree (assoc :btree true)))

(defn facts
  "Facts of one schema, ordered by type name, table name, column name and constraint name.
   Column positions are not used for ordering because they depend on migration order."
  [schema]
  (let [schema-name (:name schema)
        ;; an enum of no labels, whose column holds NULL alone, is no [:enum] malli takes: a type
        ;; pgmalli does not know
        enums (into (sorted-map) (keep (fn [[n t]] (when (and (= "ENUM" (:kind t)) (seq (:enum_values t))) [n (vec (:enum_values t))])) (:types schema)))]
    (vec
     (concat
      (for [[n vs] enums] {:fact :enum-type :schema schema-name :type-name n :values vs})
      (for [f (:functions schema)] {:fact :function :schema schema-name :name (:name f) :overload (function-overload f)})
      (for [o (:operators schema)] {:fact :operator :schema schema-name :name (:name o) :overload (operator-overload o)})
      (for [n (:sequences schema)] {:fact :sequence :schema schema-name :name n})
      (for [[tname t] (sort-by key (:tables schema))
            :let [base {:schema schema-name :table tname}
                  view? (contains? #{"VIEW" "MATERIALIZED VIEW"} (:kind t))]
            ;; a table of no column is a relation all the same
            f (concat (when (empty? (:columns t)) [(assoc base :fact :relation)])
                      (when view? [(assoc base :fact :view :materialized? (= "MATERIALIZED VIEW" (:kind t)))])
                      (for [tr (:triggers t)] (merge base {:fact :trigger} (select-keys tr [:name :before :insert :update :delete])))
                      (when (:inherited t) [(assoc base :fact :inherited)])
                      ;; nothing marks a view's column NOT NULL in the catalog, so every one may be NULL
                      (mapcat #(column-facts base (cond-> % view? (assoc :is_nullable true)) enums) (sort-by :name (:columns t)))
                      (mapcat #(key-facts base %) (sort-by :name (vals (:constraints t)))))]
        f)))))
