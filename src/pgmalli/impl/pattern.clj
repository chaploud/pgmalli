(ns pgmalli.impl.pattern
  "Facts about a schema, derived from the structure read by pgmalli.impl.ir.

   A fact is a map with :fact (the kind), :schema, and :table / :column / :constraint where they
   apply. CHECK constraints are matched against a fixed set of patterns (column patterns, then
   :branch-check and :or-check); anything else is kept as :table-check with the expression data,
   never dropped.

   Kinds:
     :enum-type    an enum type of the schema           {:type-name :values}
     :column       a column                              {:type :position :nullable? :default :identity :generated}
                   :identity is :always, :default or :serial (a nextval default); :generated is the expression
     :enum         column of an enum type                {:type-name :values}
     :domain-ref   column of a domain type               {:type-name}
     :unknown-type type outside the mapping table       {:type}
     :max-length   varchar(n)                            {:max}
     :numeric      numeric(p,s)                          {:precision :scale}
     :in-set       col IN (...) / col = v                {:values}
     :range        col >= a AND col <= b, BETWEEN, ...   {:min :max :min-exclusive? :max-exclusive?}
     :non-blank    length(trim(col)) > 0 / col <> ''     {:trim?}
     :length       length(col) <= n / octet_length = n   {:fn :min :max :exact}
     :json-type    jsonb_typeof(col) = 'object'          {:json-type}
     :regex        col ~ 're'                            {:re :case-insensitive?}
     :not-null     col IS NOT NULL
     :null         col IS NULL
     :when-present col IS NULL OR <column pattern>       {:fact-when-present}
     :primary-key  table primary key                      {:columns}
     :unique       UNIQUE constraint                      {:columns}
     :references   FOREIGN KEY                            {:columns :to {:schema :table :columns}}
     :domain       domain type                            {:type-name :base :not-null? :facts (column patterns over VALUE)}
     :branch-check CHECK of the form col = v AND ... OR col = w AND ...
                                                          {:dispatch :branches [{:values :facts}] :default}
     :or-check     CHECK whose OR alternatives are each an AND of column patterns
                                                          {:alternatives [[fact ...] ...]}
     :table-check  CHECK that matched no pattern         {:expr :columns :valid?}
     :unparsed     expression that could not be read     {:input :error}

   One CHECK can yield several facts (column patterns joined by AND). NOT VALID constraints
   are never matched, since existing rows may violate them."
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

(defn- referenced-columns [e]
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

(defn- literal? [v] (or (string? v) (number? v)))

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
       (when (and (= :and op) (= 3 (count e))
                  (let [[[o1 c1 t] [o2 c2 s]] (rest e)]
                    (and (= := o1) (= :<> o2) (column-ref? c1) (= c1 c2) (= t [:btrim c1]) (= "" s))))
         {:column (second a) :fact :non-blank :trim? true})

       (when (and (#{:< :<= :> :>= :=} fop) (vector? fa) (#{:length :char_length :octet_length} (first fa))
                  (column-ref? (second fa)) (number? fb))
         (merge {:column (second fa) :fact :length :fn (first fa)}
                (case fop
                  := {:exact fb}
                  :<= {:max fb} :< {:max (dec fb)}
                  :>= {:min fb} :> {:min (inc fb)})))

       (when (and (= := op) (vector? a) (= :jsonb_typeof (first a)) (column-ref? (second a)) (string? b))
         {:column (second a) :fact :json-type :json-type b})

       (when (and (#{:regex :iregex} op) (column-ref? a) (string? b))
         {:column a :fact :regex :re b :case-insensitive? (= :iregex op)})

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
                        d (some dispatch-term ts)
                        n (some null-term ts)
                        pin (or d (when n {:column n :null true}))
                        rest-facts (when pin
                                     (let [others (remove #(or (= pin (dispatch-term %)) (and (:null pin) (= n (null-term %)))) ts)
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
    "timestamp" "timestamptz" "timestamp with time zone" "timestamp without time zone" "date" "time" "timetz" "interval"
    "bytea" "json" "jsonb" "tsrange" "tstzrange" "daterange" "int4range" "int8range" "numrange" "inet" "cidr" "macaddr"
    "xml" "money" "oid" "tsvector"})

(defn- parsed
  "{:expr data} for an expression PostgreSQL printed, or {:unparsed fact} when it cannot be read."
  [base s]
  (let [{:keys [expr error]} (x/try-parse s)]
    (if error {:unparsed (merge base {:fact :unparsed :input s :error error})} {:expr expr})))

(defn- column-facts [base {cname :name :keys [data_type type_schema is_nullable default_value max_length precision scale identity generated_expr position]} enums domains]
  (let [type-name (str/replace data_type #"^[^.]+\." "")
        elem-type (str/replace type-name #"\[\]$" "")
        builtin? (or (nil? type_schema) (= "pg_catalog" type_schema))
        base (assoc base :column cname)
        default (when default_value (parsed base default_value))
        generated (when generated_expr (parsed base generated_expr))]
    (cond-> [(merge base {:fact :column :type data_type :position position :nullable? (boolean is_nullable)}
                    (when (contains? default :expr) {:default (:expr default)})
                    (cond identity {:identity ({"ALWAYS" :always "BY DEFAULT" :default} identity)}
                          (and (vector? (:expr default)) (= :nextval (first (:expr default)))) {:identity :serial})
                    (when (contains? generated :expr) {:generated (:expr generated)}))]
      (:unparsed default) (conj (:unparsed default))
      (:unparsed generated) (conj (:unparsed generated))
      (contains? enums type-name) (conj (merge base {:fact :enum :type-name type-name :values (get enums type-name)}))
      (contains? domains type-name) (conj (merge base {:fact :domain-ref :type-name type-name}))
      (and (not (contains? enums type-name)) (not (contains? domains type-name)) (not (and builtin? (known-types elem-type))))
      (conj (merge base {:fact :unknown-type :type data_type}))
      (and max_length (re-find #"^(varchar|character varying|char|character|bpchar)$" data_type))
      (conj (merge base {:fact :max-length :max max_length}))
      (and precision (= "numeric" data_type)) (conj (merge base {:fact :numeric :precision precision :scale scale})))))

(defn- key-facts [base {cname :name :keys [type columns references]}]
  (case type
    "PRIMARY KEY" [(merge base {:fact :primary-key :constraint cname :columns columns})]
    "UNIQUE" [(merge base {:fact :unique :constraint cname :columns columns})]
    "FOREIGN KEY" [(merge base {:fact :references :constraint cname :columns columns
                                :to (select-keys references [:schema :table :columns])})]
    []))

(defn- domain-facts [schema-name [tname {:keys [base_type not_null constraints]}]]
  (let [parsed (map (fn [{:keys [definition]}]
                      (try (match-columns (normalize (x/check-clause definition)))
                           (catch Exception _ nil)))
                    constraints)]
    (when (every? some? parsed)
      [{:fact :domain :schema schema-name :type-name tname :base base_type :not-null? (boolean not_null)
        :facts (vec (map #(dissoc % :column) (apply concat parsed)))}])))

(defn- check-facts [base {cname :name :keys [check_clause is_valid]}]
  (let [base (assoc base :constraint cname)
        stringify (fn [m] (walk/postwalk #(if (and (map? %) (keyword? (:column %))) (update % :column name) %) m))
        parsed (try {:expr (x/check-clause check_clause)}
                    (catch Exception e {:error (or (ex-message e) (str (class e)))}))]
    (if-let [e (:expr parsed)]
      (let [n (when (not= false is_valid) (normalize e))]
        (if-let [ms (some-> n match-columns)]
          (mapv #(merge base (stringify %)) ms)
          (if-let [b (some-> n match-branches)]
            [(merge base {:fact :branch-check} (stringify b) {:dispatch (name (:dispatch b))})]
            (if-let [alts (some-> n match-alternatives)]
              [(merge base {:fact :or-check :alternatives (stringify alts)})]
              [(cond-> (merge base {:fact :table-check :expr (x/canonical e) :columns (referenced-columns e)})
                 (false? is_valid) (assoc :valid? false))]))))
      [(merge base {:fact :unparsed :input check_clause :error (:error parsed)})])))

;;; API

(defn facts
  "Facts of one schema, ordered by type name, table name, column name and constraint name.
   Column positions are not used for ordering because they depend on migration order."
  [schema]
  (let [schema-name (:name schema)
        enums (into (sorted-map) (keep (fn [[n t]] (when (= "ENUM" (:kind t)) [n (vec (:enum_values t))])) (:types schema)))
        domains (set (keep (fn [[n t]] (when (= "DOMAIN" (:kind t)) n)) (:types schema)))]
    (vec
     (concat
      (for [[n vs] enums] {:fact :enum-type :schema schema-name :type-name n :values vs})
      (mapcat #(domain-facts schema-name %) (sort-by key (filter (comp #{"DOMAIN"} :kind val) (:types schema))))
      (for [[tname t] (sort-by key (:tables schema))
            :let [base {:schema schema-name :table tname}
                  constraints (sort-by :name (vals (:constraints t)))]
            f (concat (mapcat #(column-facts base % enums domains) (sort-by :name (:columns t)))
                      (mapcat #(key-facts base %) constraints)
                      (mapcat #(check-facts base %) (filter #(= "CHECK" (:type %)) constraints)))]
        f)))))

(defn coverage
  "Counts per fact kind; :checks restricts to facts that came from CHECK constraints."
  [facts]
  {:all (frequencies (map :fact facts))
   :checks (frequencies (map :fact (filter :constraint facts)))})
