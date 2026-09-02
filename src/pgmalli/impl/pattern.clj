(ns pgmalli.impl.pattern
  "Facts about a schema, derived from the structure read by pgmalli.impl.ir.

   A fact is a map with :fact (the kind), :schema, and :table / :column / :constraint where they
   apply. Column-local CHECK constraints are matched against a fixed set of patterns; anything
   else is kept as :table-check with the expression data, never dropped.

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
     :null         col IS NULL (only inside branches of a :branch-check)
     :when-present col IS NULL OR <column pattern>       {:fact-when-present}
     :primary-key  table primary key                      {:columns}
     :unique       UNIQUE constraint                      {:columns}
     :references   FOREIGN KEY                            {:columns :to {:schema :table :columns}}
     :domain       domain type                            {:type-name :base :facts (column patterns over VALUE)}
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
    (->> (walk (strip-casts (if (vector? e) e [:x e])))
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
  "One column-local pattern, or nil."
  [e]
  (let [e (strip-casts (x/canonical e))]
    (or
     (when (and (vector? e) (= :is-not (first e)) (column-ref? (second e)) (nil? (nth e 2)))
       {:column (second e) :fact :not-null})
     (when (and (vector? e) (= :is (first e)) (column-ref? (second e)) (nil? (nth e 2)))
       {:column (second e) :fact :null})

     (when (and (vector? e) (= :in (first e)) (column-ref? (second e)) (every? literal? (nth e 2)))
       {:column (second e) :fact :in-set :values (vec (nth e 2))})
     (when (and (vector? e) (= := (first e)) (column-ref? (second e)) (literal? (nth e 2)))
       {:column (second e) :fact :in-set :values [(nth e 2)]})

     (when (and (vector? e) (= :between (first e)) (column-ref? (second e)) (number? (nth e 2)) (number? (nth e 3)))
       {:column (second e) :fact :range :min (nth e 2) :max (nth e 3) :min-exclusive? false :max-exclusive? false})
     (when (and (vector? e) (= :and (first e)) (= 3 (count e)))
       (let [[a b] (map bound (rest e))]
         (when (and a b (= (:column a) (:column b)) (not= (contains? a :min) (contains? b :min)))
           (merge a b))))
     (bound e)

     (when (vector? e)
       (let [[op l r] e]
         (cond
           (and (= :> op) (= 0 r) (vector? l) (#{:length :char_length} (first l))
                (vector? (second l)) (#{:trim :btrim} (first (second l))) (column-ref? (second (second l))))
           {:column (second (second l)) :fact :non-blank :trim? true}
           (and (= :<> op) (= "" r) (vector? l) (= :btrim (first l)) (column-ref? (second l)))
           {:column (second l) :fact :non-blank :trim? true}
           (and (= :<> op) (= "" r) (column-ref? l))
           {:column l :fact :non-blank :trim? false}
           (and (= :and op) (= 3 (count e))
                (let [[[o1 c1 t] [o2 c2 s]] (rest e)]
                  (and (= := o1) (= :<> o2) (column-ref? c1) (= c1 c2) (= t [:btrim c1]) (= "" s))))
           {:column (second (second e)) :fact :non-blank :trim? true}
           :else nil)))

     (when (vector? e)
       (let [[op l r] (flip e)]
         (when (and (#{:< :<= :> :>= :=} op) (vector? l) (#{:length :char_length :octet_length} (first l))
                    (column-ref? (second l)) (number? r))
           (merge {:column (second l) :fact :length :fn (first l)}
                  (case op
                    := {:exact r}
                    :<= {:max r} :< {:max (dec r)}
                    :>= {:min r} :> {:min (inc r)})))))

     (when (and (vector? e) (= := (first e)) (vector? (second e)) (= :jsonb_typeof (first (second e)))
                (column-ref? (second (second e))) (string? (nth e 2)))
       {:column (second (second e)) :fact :json-type :json-type (nth e 2)})

     (when (and (vector? e) (#{:regex :iregex} (first e)) (column-ref? (second e)) (string? (nth e 2)))
       {:column (second e) :fact :regex :re (nth e 2) :case-insensitive? (= :iregex (first e))})

     (when (and (vector? e) (= :or (first e)) (= 3 (count e)))
       (let [is-null? (fn [a] (and (vector? a) (= :is (first a)) (nil? (nth a 2)) (column-ref? (second a))))
             [a b] (rest e)
             [[_ c] inner] (cond (is-null? a) [a b] (is-null? b) [b a])
             m (when c (match-column inner))]
         (when (and m (= c (:column m)))
           {:column c :fact :when-present :fact-when-present (dissoc m :column)}))))))

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

(defn- match-branches
  "CHECK whose alternatives each pin one column to literal values (or its nullness) and constrain
   other columns with column patterns. Returns {:dispatch col :branches [...] :default facts} or nil."
  [e]
  (let [e (strip-casts (x/canonical e))
        alts (if (and (vector? e) (= :or (first e))) (rest e) [e])
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
  (let [e (strip-casts (x/canonical e))]
    (when (and (vector? e) (= :or (first e)))
      (let [alts (map match-columns (rest e))]
        (when (every? some? alts) (vec alts))))))

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

(defn- parsed-or-unparsed [base s]
  (let [{:keys [expr error]} (x/try-parse s)]
    (if error [nil (merge base {:fact :unparsed :input s :error error})] [expr nil])))

(defn- column-facts [base {:keys [name data_type type_schema is_nullable default_value max_length precision scale identity generated_expr position]} enums domains]
  (let [type-name (str/replace data_type #"^[^.]+\." "")
        elem-type (str/replace type-name #"\[\]$" "")
        builtin? (or (nil? type_schema) (= "pg_catalog" type_schema))
        base (assoc base :column name)
        [default default-unparsed] (when default_value (parsed-or-unparsed base default_value))
        [generated generated-unparsed] (when generated_expr (parsed-or-unparsed base generated_expr))]
    (cond-> [(merge base {:fact :column :type data_type :position position :nullable? (boolean is_nullable)}
                    (when default {:default default})
                    (cond identity {:identity ({"ALWAYS" :always "BY DEFAULT" :default} identity)}
                          (and (vector? default) (= :nextval (first default))) {:identity :serial})
                    (when generated {:generated generated}))]
      default-unparsed (conj default-unparsed)
      generated-unparsed (conj generated-unparsed)
      (contains? enums type-name) (conj (merge base {:fact :enum :type-name type-name :values (get enums type-name)}))
      (contains? domains type-name) (conj (merge base {:fact :domain-ref :type-name type-name}))
      (and (not (contains? enums type-name)) (not (contains? domains type-name)) (not (and builtin? (known-types elem-type))))
      (conj (merge base {:fact :unknown-type :type data_type}))
      (and max_length (re-find #"^(varchar|character varying|char|character|bpchar)$" data_type))
      (conj (merge base {:fact :max-length :max max_length}))
      (and precision (= "numeric" data_type)) (conj (merge base {:fact :numeric :precision precision :scale scale})))))

(defn- key-facts [base {:keys [name type columns references]}]
  (case type
    "PRIMARY KEY" [(merge base {:fact :primary-key :constraint name :columns columns})]
    "UNIQUE" [(merge base {:fact :unique :constraint name :columns columns})]
    "FOREIGN KEY" [(merge base {:fact :references :constraint name :columns columns
                                :to (select-keys references [:schema :table :columns])})]
    []))

(defn- domain-facts [schema-name [tname {:keys [base_type not_null constraints]}]]
  (let [parsed (map (fn [{:keys [definition]}]
                      (try (match-columns (x/check-clause definition))
                           (catch Exception _ nil)))
                    constraints)]
    (when (every? some? parsed)
      [{:fact :domain :schema schema-name :type-name tname :base base_type :not-null? (boolean not_null)
        :facts (vec (map #(dissoc % :column) (apply concat parsed)))}])))

(defn- check-facts [base {:keys [name check_clause is_valid]}]
  (let [base (assoc base :constraint name)
        parsed (try {:expr (x/check-clause check_clause)}
                    (catch Exception e {:error (or (ex-message e) (str (class e)))}))]
    (if-let [e (:expr parsed)]
      (if-let [ms (when (not= false is_valid) (match-columns e))]
        (mapv #(merge base % {:column (clojure.core/name (:column %))}) ms)
        (let [stringify (fn [m] (walk/postwalk #(if (and (map? %) (keyword? (:column %))) (update % :column clojure.core/name) %) m))]
          (if-let [b (when (not= false is_valid) (match-branches e))]
            [(merge base {:fact :branch-check} (stringify b) {:dispatch (clojure.core/name (:dispatch b))})]
            (if-let [alts (when (not= false is_valid) (match-alternatives e))]
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
