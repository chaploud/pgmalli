(ns pgmalli.impl.pattern
  "Facts about a schema, derived from the structure read by pgmalli.impl.ir.

   A fact is a map with :fact (the kind), :schema, and :table / :column / :constraint where they
   apply. CHECK constraints are matched against a fixed set of patterns (column patterns, then
   :branch-check and :or-check); anything else is kept as :table-check with the expression data,
   never dropped.

   Kinds:
     :enum-type    an enum type of the schema           {:type-name :values}
     :view         a view or materialized view          {:materialized?}; its columns follow, all nullable
     :column       a column                              {:type :position :nullable? :default :identity :generated}
                   :identity is :always, :default or :serial (a nextval default); :generated is the expression
     :enum         column of an enum type                {:type-name :values}
     :domain-ref   column of a domain type               {:type-name :base}
     :unknown-type type outside the mapping table (rendered as :any) {:type}
     :max-length   varchar(n)                            {:max}
     :numeric      numeric(p,s)                          {:precision :scale}
     :in-set       col IN (...) / col = v                {:values}
     :not-in-set   col NOT IN (...) / col <> v            {:values}
     :range        col >= a AND col <= b, BETWEEN, ...   {:min :max :min-exclusive? :max-exclusive?}
     :non-blank    length(trim(col)) > 0 / col <> ''     {:trim?}
     :length       length(col) <= n, cardinality(col) > 0 {:fn :min :max :exact}
     :json-type    jsonb_typeof(col) = 'object'          {:json-type}
     :regex        col ~ 're'                            {:re :case-insensitive?}
     :like         col LIKE 'p'                          {:pattern :case-insensitive?}
     :not-null     col IS NOT NULL
     :null         col IS NULL
     :when-present col IS NULL OR <column pattern>       {:fact-when-present}
     :primary-key  table primary key                      {:columns}
     :unique       UNIQUE constraint                      {:columns :nulls-distinct? (false for NULLS NOT DISTINCT)}
     :references   FOREIGN KEY                            {:columns :match (:simple or :full) :to {:schema :table :columns}}
     :domain       domain type                            {:type-name :base :not-null? :default :facts (column patterns over VALUE)}
     :domain-check CHECK of a domain that matched no pattern {:type-name :constraint :expr :valid?}
     :branch-check CHECK of the form col = v AND ... OR col = w AND ...
                                                          {:dispatch :branches [{:values :facts} or {:null true :facts}] :default}
     :or-check     CHECK whose OR alternatives are each an AND of column patterns
                                                          {:alternatives [[fact ...] ...]}
     :table-check  CHECK that matched no pattern         {:expr :columns :valid?}
     :not-enforced a NOT ENFORCED CHECK or FOREIGN KEY   {:constraint :input} (nothing to render)
                   (also lower <= upper for a generated range column, named <column>_generated)
     :unparsed     expression that could not be read     {:input :error}

   One CHECK can yield several facts (column patterns joined by AND); every fact from a CHECK
   carries the whole expression as :expr. NOT VALID constraints are never matched, since
   existing rows may violate them."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [pgmalli.impl.expr :as x]))


;;; expression helpers

(defn- strip-casts [e]
  (walk/postwalk (fn [f] (if (and (vector? f) (= :cast (first f)) (= 3 (count f))) (second f) f)) e))

(defn- column-ref? [e] (and (keyword? e) (nil? (namespace e))))

(defn- flip
  "Comparisons with the constant on the left (1 <= x) turned around."
  [[op a b :as e]]
  (if (and (#{:< :> :<= :>=} op) (not (column-ref? a)) (column-ref? b))
    [({:< :> :> :< :<= :>= :>= :<=} op) b a]
    e))

(defn referenced-columns
  "Names of the columns an expression reads."
  [e]
  (letfn [(walk [f] (cond (vector? f) (case (first f)
                                        (:in :not-in) (concat (walk (second f)) (mapcat walk (nth f 2)))
                                        :array (mapcat walk (second f))
                                        (mapcat walk (rest f)))
                          (keyword? f) [f]
                          :else nil))]
    (->> (walk (strip-casts e))
         (remove #{:else :*})
         (map name) distinct vec)))

;;; column patterns

(defn- bound [e]
  (let [[op c v] (flip e)]
    (when (and (#{:< :> :<= :>=} op) (column-ref? c) (number? v))
      {:column c
       :fact :range
       (if (#{:> :>=} op) :min :max) v
       (if (#{:> :>=} op) :min-exclusive? :max-exclusive?) (boolean (#{:> :<} op))})))

(defn- literal? [v] (or (string? v) (number? v) (boolean? v)))

(def ^:private length-fns #{:length :char_length :octet_length :cardinality :array_length})

(defn- match-column
  "One column-local pattern of a normalized expression, or nil."
  [e]
  (when (vector? e)
    (let [[op a b c] e
          [fop fa fb] (flip e)]
      (or
       (when (and (= :is-not op) (column-ref? a) (nil? b)) {:column a :fact :not-null})
       (when (and (= :is op) (column-ref? a) (nil? b)) {:column a :fact :null})

       (when (and (= :in op) (column-ref? a) (every? literal? b)) {:column a :fact :in-set :values (vec b)})
       (when (and (= := op) (column-ref? a) (literal? b)) {:column a :fact :in-set :values [b]})
       (when (and (= :not-in op) (column-ref? a) (every? literal? b)) {:column a :fact :not-in-set :values (vec b)})
       (when (and (= :<> op) (column-ref? a) (literal? b) (not= "" b)) {:column a :fact :not-in-set :values [b]})

       (when (and (= :between op) (column-ref? a) (number? b) (number? c))
         {:column a :fact :range :min b :max c :min-exclusive? false :max-exclusive? false})
       (when (and (= :and op) (= 3 (count e)))
         (let [[lo hi] (map bound (rest e))]
           (when (and lo hi (= (:column lo) (:column hi)) (not= (contains? lo :min) (contains? hi :min)))
             (merge lo hi))))
       (bound e)

       (when (and (= :> op) (= 0 b) (vector? a) (#{:length :char_length} (first a))
                  (vector? (second a)) (#{:trim :btrim} (first (second a))) (column-ref? (second (second a))))
         {:column (second (second a)) :fact :non-blank :trim? true})
       (when (and (= :<> op) (= "" b) (vector? a) (= :btrim (first a)) (column-ref? (second a)))
         {:column (second a) :fact :non-blank :trim? true})
       (when (and (= :<> op) (= "" b) (column-ref? a))
         {:column a :fact :non-blank :trim? false})
       ;; col = btrim(col) AND col <> '': a trimmed, non-empty string
       (when (and (= :and op) (= 3 (count e)))
         (let [[[o1 c1 t] [o2 c2 s]] (rest e)]
           (when (and (= := o1) (= :<> o2) (column-ref? c1) (= c1 c2) (= t [:btrim c1]) (= "" s))
             {:column c1 :fact :non-blank :trim? true})))

       (when (and (#{:< :<= :> :>= :=} fop) (vector? fa) (length-fns (first fa))
                  (column-ref? (second fa)) (number? fb))
         (merge {:column (second fa) :fact :length :fn (first fa)}
                (case fop
                  := {:exact fb}
                  :<= {:max fb} :< {:max (dec fb)}
                  :>= {:min fb} :> {:min (inc fb)})))
       (when (and (= :between op) (vector? a) (length-fns (first a))
                  (column-ref? (second a)) (number? b) (number? c))
         {:column (second a) :fact :length :fn (first a) :min b :max c})

       (when (and (= := op) (vector? a) (= :jsonb_typeof (first a)) (column-ref? (second a)) (string? b))
         {:column (second a) :fact :json-type :json-type b})

       (when (and (#{:regex :iregex} op) (column-ref? a) (string? b))
         {:column a :fact :regex :re b :case-insensitive? (= :iregex op)})
       (when (and (#{:like :ilike} op) (column-ref? a) (string? b))
         {:column a :fact :like :pattern b :case-insensitive? (= :ilike op)})

       (when (and (= :or op) (= 3 (count e)))
         (let [is-null? (fn [x] (and (vector? x) (= :is (first x)) (nil? (nth x 2)) (column-ref? (second x))))
               [[_ col] inner] (cond (is-null? a) [a b] (is-null? b) [b a])
               m (when col (match-column inner))]
           (when (and m (= col (:column m)))
             {:column col :fact :when-present :fact-when-present (dissoc m :column)})))))))

(defn- terms [e] (if (and (vector? e) (= :and (first e))) (rest e) [e]))

(defn- dispatch-term
  "col = 'v' or col IN (...) with literal values, or col <> ALL (...) as the default branch."
  [t]
  (when (and (vector? t) (column-ref? (second t)))
    (case (first t)
      := (when (literal? (nth t 2)) {:column (second t) :values [(nth t 2)]})
      :in (when (every? literal? (nth t 2)) {:column (second t) :values (vec (nth t 2))})
      :<> (when (literal? (nth t 2)) {:column (second t) :default true})
      :not-in (when (every? literal? (nth t 2)) {:column (second t) :default true})
      nil)))

(defn- null-term [t]
  (when (and (vector? t) (= :is (first t)) (column-ref? (second t)) (nil? (nth t 2))) (second t)))

(declare match-columns)

(defn- normalize
  "The form the matchers read: PostgreSQL's rewrites undone, casts removed."
  [e]
  (strip-casts (x/canonical e)))

(defn- match-branches
  "CHECK whose alternatives each pin one column to literal values (or its nullness) and constrain
   other columns with column patterns. Returns {:dispatch col :branches [...] :default facts} or nil."
  [e]
  (let [alts (if (and (vector? e) (= :or (first e))) (rest e) [e])
        analyse (fn [alt]
                  (let [ts (terms alt)
                        ;; several pins of one column (a IN (1, 2) AND a = 1, as nested LIST partitions
                        ;; render) pin their common values
                        ds (keep dispatch-term ts)
                        same (filter #(= (:column (first ds)) (:column %)) ds)
                        d (when (seq same)
                            (if (and (< 1 (count same)) (every? :values same))
                              (assoc (first same) :values (vec (reduce (fn [acc vs] (filter (set vs) acc)) (:values (first same)) (map :values (rest same)))))
                              (first same)))
                        n (some null-term ts)
                        pin (or d (when n {:column n :null true}))
                        ;; pins with no common value match nothing: no branch
                        rest-facts (when (and pin (not (and (contains? pin :values) (empty? (:values pin)))))
                                     (let [others (remove #(or (some (fn [s] (= s (dispatch-term %))) same) (and (:null pin) (= n (null-term %)))) ts)
                                           fs (map match-columns others)]
                                       (when (every? some? fs) (vec (apply concat fs)))))]
                    (when (and pin rest-facts) (assoc pin :facts rest-facts))))
        bs (map analyse alts)]
    (when (and (>= (count alts) 2) (every? some? bs) (= 1 (count (distinct (map :column bs))))
               (some :values bs))
      (let [defaults (filter :default bs)]
        (when (<= (count defaults) 1)
          {:dispatch (:column (first bs))
           :branches (vec (for [b bs :when (not (:default b))]
                            (if (:null b) {:null true :facts (:facts b)} {:values (:values b) :facts (:facts b)})))
           :default (:facts (first defaults))})))))

(defn- match-alternatives
  "OR whose alternatives are each an AND of column patterns: [[fact ...] ...], or nil."
  [e]
  (when (and (vector? e) (= :or (first e)))
    (let [alts (map match-columns (rest e))]
      (when (every? some? alts) (vec alts)))))

(defn- match-columns
  "All column facts of an expression, or nil if any AND branch matches nothing."
  [e]
  (if-let [m (match-column e)]
    [m]
    (when (and (vector? e) (= :and (first e)))
      (let [ms (map match-columns (rest e))]
        (when (every? some? ms) (vec (apply concat ms)))))))

;;; facts from columns and constraints

(def ^:private known-types
  #{"smallint" "integer" "bigint" "int2" "int4" "int8" "numeric" "decimal" "real" "double precision" "float4" "float8"
    "boolean" "text" "varchar" "character varying" "char" "character" "bpchar" "citext" "name" "uuid"
    "timestamp" "timestamptz" "timestamp with time zone" "timestamp without time zone" "date" "time" "timetz"
    "time without time zone" "time with time zone" "interval"
    "bytea" "json" "jsonb" "oid" "\"char\"" "bit" "bit varying" "varbit"})

(def opaque-types
  "Types a driver hands over as objects of its own (PGobject and the like): rendered :any with
   their :pg/type, and generated from a few literals the database reads."
  #{"inet" "cidr" "macaddr" "macaddr8" "money" "xml" "tsvector" "tsquery" "jsonpath"
    "point" "line" "lseg" "box" "path" "polygon" "circle" "pg_lsn" "tid" "xid" "xid8" "cid" "pg_snapshot" "txid_snapshot"
    "int4range" "int8range" "numrange" "tsrange" "tstzrange" "daterange"
    "int4multirange" "int8multirange" "nummultirange" "tsmultirange" "tstzmultirange" "datemultirange"
    "regclass" "regtype" "regrole" "regproc" "regprocedure" "regoper" "regoperator" "regnamespace" "regconfig" "regdictionary" "regcollation"})

(defn- parsed
  "{:expr data} for an expression PostgreSQL printed, or {:unparsed fact} when it cannot be read."
  [base s]
  (let [{:keys [expr error]} (x/try-parse s)]
    (if error {:unparsed (merge base {:fact :unparsed :input s :error error})} {:expr expr})))

(defn- try-clause
  "{:expr data} for a CHECK clause PostgreSQL printed, or {:error message}."
  [s]
  (try {:expr (x/check-clause s)}
       (catch Exception e {:error (or (ex-message e) (str (class e)))})))

(defn- column-facts [base {cname :name :keys [data_type type_schema is_nullable default_value max_length precision scale identity generated_expr position]} enums domains]
  (let [type-name (str/replace data_type #"^[^.]+\." "")
        elem-type (str/replace type-name #"\[\]$" "")
        builtin? (or (nil? type_schema) (= "pg_catalog" type_schema))
        base (assoc base :column cname)
        domain (get domains type-name)
        mapped? (or (contains? enums type-name) (contains? domains type-name) (and builtin? (or (known-types elem-type) (opaque-types elem-type))))
        char-type? (#{"varchar" "character varying" "char" "character" "bpchar"} elem-type)
        ;; a domain's NOT NULL and DEFAULT reach the columns of that type
        default (cond default_value (parsed base default_value)
                      (contains? domain :default) {:expr (:default domain)})
        generated (when generated_expr (parsed base generated_expr))
        ;; a range built from two columns needs them ordered: the generated column's own CHECK
        range-check (let [e (:expr generated) [f lo hi] (when (vector? e) e)]
                      (when (and (#{:tsrange :tstzrange :daterange :int4range :int8range :numrange} f) (keyword? lo) (keyword? hi))
                        (merge (dissoc base :column)
                               {:fact :table-check :constraint (str cname "_generated") :expr [:<= lo hi] :columns [(name lo) (name hi)]})))]
    (cond-> [(merge base {:fact :column :type data_type :position position :nullable? (boolean (and is_nullable (not (:not-null? domain))))}
                    (when (contains? default :expr) {:default (:expr default)})
                    (cond identity {:identity ({"ALWAYS" :always "BY DEFAULT" :default} identity)}
                          (and (vector? (:expr default)) (= :nextval (first (:expr default)))) {:identity :serial})
                    (when (contains? generated :expr) {:generated (:expr generated)}))]
      (:unparsed default) (conj (:unparsed default))
      (:unparsed generated) (conj (:unparsed generated))
      range-check (conj range-check)
      (contains? enums type-name) (conj (merge base {:fact :enum :type-name type-name :values (get enums type-name)}))
      domain (conj (merge base {:fact :domain-ref :type-name type-name :base (:base domain)}))
      (not mapped?) (conj (merge base {:fact :unknown-type :type data_type}))
      (and max_length char-type?) (conj (merge base {:fact :max-length :max max_length}))
      ;; bit(n) is exactly n digits, bit varying(n) at most n
      (and max_length (= "bit" elem-type)) (conj (merge base {:fact :length :fn :length :exact max_length}))
      (and max_length (#{"bit varying" "varbit"} elem-type)) (conj (merge base {:fact :length :fn :length :max max_length}))
      (and precision (= "numeric" data_type)) (conj (merge base {:fact :numeric :precision precision :scale scale})))))

(defn- key-facts [base {cname :name :keys [type columns references nulls_not_distinct is_enforced]}]
  (case type
    "PRIMARY KEY" [(merge base {:fact :primary-key :constraint cname :columns columns})]
    "UNIQUE" [(merge base {:fact :unique :constraint cname :columns columns :nulls-distinct? (not nulls_not_distinct)})]
    ;; NOT ENFORCED: the database never checks it, so it is not a reference, only noted
    "FOREIGN KEY" (if (false? is_enforced)
                    [(merge base {:fact :not-enforced :constraint cname :input (str "FOREIGN KEY " (pr-str columns))})]
                    [(merge base {:fact :references :constraint cname :columns columns
                                  :match (if (= "FULL" (:match references)) :full :simple)
                                  :to (select-keys references [:schema :table :columns])})])
    []))

(defn- domain-facts
  "The :domain fact, then a :domain-check (or :unparsed) for every CHECK that matched no pattern."
  [schema-name [tname {:keys [base_type not_null default constraints]}]]
  (let [base {:schema schema-name :type-name tname}
        checks (for [{cname :name :keys [definition is_valid]} constraints
                     :let [{:keys [expr error]} (try-clause definition)
                           ;; NOT VALID: existing values may violate it, so it is reported, not applied
                           ms (when (not= false is_valid) (some-> expr normalize match-columns))
                           check (when expr (merge base {:constraint cname :expr (x/canonical expr)}))]]
                 (cond ms {:facts (map #(merge check (dissoc % :column)) ms)}
                       expr {:check (cond-> (assoc check :fact :domain-check) (false? is_valid) (assoc :valid? false))}
                       :else {:check (merge base {:fact :unparsed :constraint cname :input definition :error error})}))
        dflt (when default (parsed base default))]
    (into [(cond-> (merge base {:fact :domain :base base_type :not-null? (boolean not_null) :facts (vec (mapcat :facts checks))})
             (contains? dflt :expr) (assoc :default (:expr dflt)))]
          (concat (keep :check checks) (some-> (:unparsed dflt) vector)))))

(defn- substitute
  "The expression with the columns of subst (keyword -> expression) replaced by their
   expressions, everywhere but at the head of a form (an operator or function)."
  [e subst]
  (cond (vector? e) (into [(first e)] (map #(substitute % subst) (rest e)))
        (keyword? e) (get subst e e)
        (sequential? e) (map #(substitute % subst) e)
        :else e))

(defn- check-facts [base {cname :name :keys [check_clause is_valid is_enforced]} generated]
  (let [base (assoc base :constraint cname)
        stringify (fn [m] (walk/postwalk #(if (and (map? %) (keyword? (:column %))) (update % :column name) %) m))
        parsed (try-clause check_clause)]
    (cond
      ;; NOT ENFORCED: the database never checks it, so nothing is rendered; it is noted
      (false? is_enforced) [(merge base {:fact :not-enforced :input check_clause})]
      (contains? parsed :expr)
      (let [;; a generated column holds its expression's value: the CHECK is on that
            e (substitute (:expr parsed) generated)
            n (when (not= false is_valid) (normalize e))
            base (assoc base :expr (x/canonical e))]
        (if-let [ms (some-> n match-columns)]
          (mapv #(merge base (stringify %)) ms)
          (if-let [b (some-> n match-branches)]
            [(merge base {:fact :branch-check} (stringify b) {:dispatch (name (:dispatch b))})]
            (if-let [alts (some-> n match-alternatives)]
              [(merge base {:fact :or-check :alternatives (stringify alts)})]
              [(cond-> (merge base {:fact :table-check :columns (referenced-columns e)})
                 (false? is_valid) (assoc :valid? false))]))))
      :else [(merge base {:fact :unparsed :input check_clause :error (:error parsed)})])))

;;; API

(defn facts
  "Facts of one schema, ordered by type name, table name, column name and constraint name.
   Column positions are not used for ordering because they depend on migration order."
  [schema]
  (let [schema-name (:name schema)
        enums (into (sorted-map) (keep (fn [[n t]] (when (= "ENUM" (:kind t)) [n (vec (:enum_values t))])) (:types schema)))
        domain-facts* (mapcat #(domain-facts schema-name %) (sort-by key (filter (comp #{"DOMAIN"} :kind val) (:types schema))))
        domains (into {} (for [f domain-facts* :when (= :domain (:fact f))] [(:type-name f) (select-keys f [:base :not-null? :default])]))]
    (vec
     (concat
      (for [[n vs] enums] {:fact :enum-type :schema schema-name :type-name n :values vs})
      domain-facts*
      (for [[tname t] (sort-by key (:tables schema))
            :let [base {:schema schema-name :table tname}
                  view? (contains? #{"VIEW" "MATERIALIZED VIEW"} (:kind t))
                  constraints (sort-by :name (vals (:constraints t)))]
            f (concat (when view? [(assoc base :fact :view :materialized? (= "MATERIALIZED VIEW" (:kind t)))])
                      ;; nothing marks a view's column NOT NULL in the catalog, so every one may be NULL
                      (mapcat #(column-facts base (cond-> % view? (assoc :is_nullable true)) enums domains) (sort-by :name (:columns t)))
                      (mapcat #(key-facts base %) constraints)
                      (let [generated (into {} (for [c (:columns t) :when (:generated_expr c)
                                                     :let [{:keys [expr]} (parsed base (:generated_expr c))] :when (some? expr)]
                                                 [(keyword (:name c)) expr]))]
                        (concat (mapcat #(check-facts base % generated) (filter #(= "CHECK" (:type %)) constraints))
                                ;; a domain on a generated column checks the expression's value
                                (for [c (:columns t)
                                      :let [expr (get generated (keyword (:name c)))
                                            type-name (str/replace (:data_type c) #"^[^.]+\." "")]
                                      :when (and expr (contains? domains type-name))
                                      k (get-in schema [:types type-name :constraints])
                                      f (check-facts base {:name (str (:name c) " " (:name k)) :check_clause (:definition k) :is_valid (:is_valid k true)}
                                                     {:VALUE expr})]
                                  f))))]
        f)))))

(defn coverage
  "Counts per fact kind; :checks restricts to facts that came from CHECK constraints."
  [facts]
  {:all (frequencies (map :fact facts))
   :checks (frequencies (map :fact (filter :constraint facts)))})
