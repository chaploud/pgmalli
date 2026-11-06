(ns pgmalli-next.impl.pattern
  "Facts about a schema, derived from the structure read by pgmalli-next.impl.ir: its types,
   tables, columns, keys and routines. A CHECK is not read: the database checks it.

   A fact is a map with :fact (the kind), :schema, and :table / :column / :constraint where they
   apply. Kinds:
     :enum-type    an enum type of the schema           {:type-name :values}
     :view         a view or materialized view          {:materialized?}; its columns follow, all nullable
     :column       a column                              {:type :domain :position :nullable? :default :default-sql :identity :generated}
                   :type is a domain's base type under all its domains, as ir.sql reads it (:domain
                   the type the column declares: the domain, or d[] for an array of domain d), :default the default's literal value, :default-sql its SQL; :identity is
                   :always, :default or :serial (a nextval default); :generated is the SQL of the expression
     :enum         column of an enum type (or an array of it) {:type-name :values :array?}
     :unknown-type type outside the mapping table (rendered as :any) {:type :type-schema (when not pg_catalog's or the schema's own)}
     :max-length   varchar(n), char(n), bit(n) or bit varying(n) {:max}
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
     :partitions   a partitioned table's partitions        {:names}
     :sequence     a sequence of the schema                {:name}
     :function     a function a query may call            {:name :overload}
     :operator     an operator                            {:name :overload}
                   :overload as pgmalli-next.impl.types/catalog holds pg_catalog's, its types named as a
                   column's :type is"
  (:require [clojure.string :as str]
            [pgmalli-next.impl.types :as types]))

(defn- type-ident
  "[name array?] of a type as format_type prints it: the name the catalog keys it by, a quoted
   one unquoted, the schema of one outside the search path dropped (\"Mixed Case\"[] -> [\"Mixed Case\" true])."
  [data-type]
  (let [[_ t brackets] (re-matches #"(.*?)((?:\[\])*)" data-type)]
    [(if-let [[_ quoted] (re-matches #"(?:.*\.)?\"((?:[^\"]|\"\")*)\"" t)]
       (str/replace quoted "\"\"" "\"")
       (str/replace t #"^[^.]+\." ""))
     (boolean (seq brackets))]))

(defn- default-literal
  "The value of a default PostgreSQL prints as a literal (42, 1.5, true, 'happy'::mood, a
   negative number as '-1'::integer); nil for an expression (now(), nextval(...))."
  [s]
  (cond (re-matches #"-?\d+" s) (bigint s)
        (re-matches #"-?\d+\.\d+" s) (bigdec s)
        (#{"true" "false"} s) (= "true" s)
        :else (some-> (re-matches #"(?s)'((?:[^']|'')*)'(?:::.+)?" s) second (str/replace "''" "'"))))

(defn- column-facts
  "The :column fact of a column and those of its type."
  [base {cname :name :keys [data_type domain type_schema is_nullable default_value max_length identity generated_expr position]} enums]
  (let [[ident array?] (type-ident data_type)
        base (assoc base :column cname)
        ;; an enum of another schema may share a name with one of this schema's
        enum (when (and (contains? enums ident) (= type_schema (:schema base)))
               (cond-> (merge base {:fact :enum :type-name ident :values (get enums ident)}) array? (assoc :array? true)))
        lit (some-> default_value default-literal)
        bare (types/bare-name (str/replace data_type #"\[\]$" ""))]
    (cond-> [(merge base {:fact :column :type data_type :position position :nullable? (boolean is_nullable)}
                    (when domain {:domain domain})
                    (when default_value {:default-sql default_value})
                    (when (some? lit) {:default (if (integer? lit) (long lit) lit)})
                    (cond identity {:identity ({"ALWAYS" :always "BY DEFAULT" :default} identity)}
                          (some-> default_value (str/starts-with? "nextval(")) {:identity :serial})
                    (when generated_expr {:generated generated_expr}))]
      enum (conj enum)
      ;; a type of another schema: the schema's facts are read alone, so they cannot refer to it
      (not (or enum (types/known? bare type_schema)))
      (conj (cond-> (merge base {:fact :unknown-type :type data_type})
              (not (#{nil "pg_catalog" (:schema base)} type_schema)) (assoc :type-schema type_schema)))
      (and max_length (:typmod (types/entry bare)))
      (conj (merge base {:fact :max-length :max max_length})))))

(defn- key-facts [base {cname :name :keys [type columns references nulls_not_distinct is_valid is_enforced index partial predicate exclude deferrable]}]
  (case type
    "EXCLUDE" [(merge base {:fact :exclude :constraint cname :method (:method exclude) :partial? (boolean (:partial exclude))
                            :elements (mapv #(select-keys % [:column :op]) (:elements exclude))})]
    "PRIMARY KEY" [(cond-> (merge base {:fact :primary-key :constraint cname :columns columns}) deferrable (assoc :deferrable? true))]
    ;; an invalid unique index (a CREATE INDEX CONCURRENTLY that failed) holds nothing
    "UNIQUE" (when-not (and index (false? is_valid))
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
   query read it: {:args :result :set :kind :variadic :defaults :non_null :columns}."
  [{:keys [args result set kind variadic defaults non_null columns]}]
  (cond-> {:args (vec args) :result result}
    set (assoc :set true)
    (not= "f" kind) (assoc :kind (keyword kind))
    variadic (assoc :variadic true)
    (pos? defaults) (assoc :defaults defaults)
    non_null (assoc :non-null true)
    columns (assoc :columns (mapv vec columns))))

(defn operator-overload
  "An operator's overload, of {:args :result :btree} as ir.sql and the catalog's query read it."
  [{:keys [args result btree]}]
  (cond-> {:args (vec args) :result result} btree (assoc :btree true)))

(defn facts
  "Facts of one schema, ordered by type name, table name, column name and constraint name.
   Column positions are not used for ordering because they depend on migration order."
  [schema]
  (let [schema-name (:name schema)
        enums (into (sorted-map) (keep (fn [[n t]] (when (= "ENUM" (:kind t)) [n (vec (:enum_values t))])) (:types schema)))]
    (vec
     (concat
      (for [[n vs] enums] {:fact :enum-type :schema schema-name :type-name n :values vs})
      (for [f (:functions schema)] {:fact :function :schema schema-name :name (:name f) :overload (function-overload f)})
      (for [o (:operators schema)] {:fact :operator :schema schema-name :name (:name o) :overload (operator-overload o)})
      (for [n (:sequences schema)] {:fact :sequence :schema schema-name :name n})
      (for [[tname t] (sort-by key (:tables schema))
            :let [base {:schema schema-name :table tname}
                  view? (contains? #{"VIEW" "MATERIALIZED VIEW"} (:kind t))]
            f (concat (when view? [(assoc base :fact :view :materialized? (= "MATERIALIZED VIEW" (:kind t)))])
                      (for [tr (:triggers t)] (merge base {:fact :trigger} (select-keys tr [:name :before :insert :update :delete])))
                      (when (:inherited t) [(assoc base :fact :inherited)])
                      (when (seq (:partitions t)) [(assoc base :fact :partitions :names (vec (:partitions t)))])
                      ;; nothing marks a view's column NOT NULL in the catalog, so every one may be NULL
                      (mapcat #(column-facts base (cond-> % view? (assoc :is_nullable true)) enums) (sort-by :name (:columns t)))
                      (mapcat #(key-facts base %) (sort-by :name (vals (:constraints t)))))]
        f)))))
