(ns pgmalli.impl.render
  "Facts -> malli schemas.

   registry returns {:registry {name schema} :unrendered [fact] :skipped [fact]} with
     :pg.<schema>/<type>     enum types and domains (a domain CHECK outside the patterns is
                             [:pg/check-value expr], evaluated over the value as :VALUE)
     :pg.<schema>/<table>    a valid row as read from the database
   A row schema is [:map ...] alone, or [:and [:map ...] checks...] when the table has constraints
   across columns: [:multi ...] for branches on one column's value, [:or ...] of map fragments,
   and [:pg/check expr] for everything else pgmalli.impl.eval can evaluate; bytea columns with a
   length CHECK are [:pg/bytes {:min :max}]. Insert schemas are
   derived from row schemas when a registry is loaded (pgmalli.impl.runtime).

   Properties: on the map :pg/table (\"schema.table\"), :pg/primary-key, :pg/unique
   ({:columns} each, :nulls-distinct false for NULLS NOT DISTINCT) and :pg/foreign-keys
   ({:columns :table :to} each, tables schema-qualified, :match :full for MATCH FULL); on columns :pg/type
   :pg/default :pg/identity :pg/generated :pg/constraint, and malli's :default for literal defaults.

   Identifiers that are not plain names become string keys, keeping the output readable EDN.
   overrides is {constraint-name schema-or-{:skip reason}}."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [pgmalli.impl.eval :as check]
            [pgmalli.impl.pattern :as pattern]))

(defn ident-key
  "Column names as row keys: keywords for plain identifiers, strings otherwise."
  [s]
  (if (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s) (keyword s) s))

(defn- plain? [s] (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s))

(defn- schema-key [schema-name s]
  (if (and (plain? schema-name) (plain? s))
    (keyword (str "pg." schema-name) s)
    (str "pg." schema-name "/" s)))

(def ^:private base-types
  ;; smallint and integer are schema types pgmalli registers, carrying PostgreSQL's range; a
  ;; bigint is exactly a long, so it is :int
  (merge (zipmap ["smallint" "int2"] (repeat :pg/smallint))
         (zipmap ["integer" "int4"] (repeat :pg/integer))
         (zipmap ["bigint" "int8"] (repeat :int))
         (zipmap ["numeric" "decimal"] (repeat 'decimal?))
         (zipmap ["real" "double precision" "float4" "float8"] (repeat :double))
         (zipmap ["text" "varchar" "character varying" "char" "character" "bpchar" "citext" "name"] (repeat :string))
         {"boolean" :boolean "uuid" :uuid "bytea" 'bytes?
          "date" :time/local-date "time" :time/local-time "time without time zone" :time/local-time
          "timetz" :time/offset-time "time with time zone" :time/offset-time
          "timestamp" :time/local-date-time "timestamp without time zone" :time/local-date-time
          "timestamptz" :time/instant "timestamp with time zone" :time/instant "interval" :time/duration}))

(defn- base-type [data-type]
  (let [t (str/replace (or data-type "") #"^[^.]+\." "")]
    (if-let [[_ elem] (re-matches #"(.+)\[\]" t)]
      [:vector (base-type elem)]
      (get base-types t :any))))

(defn- tighten
  "Properties merged onto existing ones; :min and :max only ever narrow, since a CHECK cannot
   widen what the type or another CHECK already bounds."
  [old props]
  (reduce-kv (fn [m k v]
               (assoc m k (case k
                            :min (if (contains? m :min) (max (m :min) v) v)
                            :max (if (contains? m :max) (min (m :max) v) v)
                            v)))
             old props))

(defn- prune [m] (into {} (remove (comp nil? val)) m))

(defn- with-props
  "Properties on a column schema. On [:and type ...] the bounds malli reads (:min, :max) go on
   the type; provenance stays on the :and, where the insert derivation reads it."
  [schema props]
  (let [props (prune props)
        bounds (select-keys props [:min :max])]
    (cond
      (empty? props) schema
      (and (vector? schema) (= :and (first schema)) (not (map? (second schema))) (seq bounds))
      (with-props (update schema 1 with-props bounds) (apply dissoc props (keys bounds)))
      (vector? schema) (if (map? (second schema))
                         (assoc schema 1 (tighten (second schema) props))
                         (into [(first schema) props] (rest schema)))
      :else [schema props])))

(defn- enum-values [schema] (if (map? (second schema)) (drop 2 schema) (rest schema)))

(defn- uuid-string? [v] (and (string? v) (re-matches #"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" v)))

(defn- strip-casts [e]
  (walk/postwalk #(if (and (vector? %) (= :cast (first %))) (second %) %) e))

(defn- literal [e]
  (let [e (strip-casts e)]
    (when (or (string? e) (number? e) (boolean? e)) e)))

(defn- default-value
  "A literal default as a value of the column's schema (malli's :default), or nil when the
   literal is not one (a date as a string)."
  [schema lit]
  (let [base (if (vector? schema) (first schema) schema)]
    (cond
      (and (#{:int :pg/integer :pg/smallint} base) (integer? lit)) lit
      (and (= :double base) (number? lit)) (double lit)
      (and (= 'decimal? base) (number? lit)) (bigdec lit)
      (and (#{:string :ref :enum} base) (string? lit)) lit
      (and (= :boolean base) (boolean? lit)) lit)))

(defn- schema-base
  "The type at the head of a column schema. Facts append bounds as [:and type ...], never
   prepend, so the type stays reachable."
  [schema]
  (let [head #(if (vector? %) (first %) %)
        b (head schema)]
    (if (and (= :and b) (not (map? (second schema)))) (head (second schema)) b)))

(defn- apply-fact
  "[schema unrendered] after one fact on a column schema."
  [[schema unrendered] {:keys [fact] :as f} schema-name]
  (let [base (schema-base schema)
        string-base? (= :string base)
        number-base? (#{:int :pg/integer :pg/smallint :double} base)
        decimal-base? (= 'decimal? base)
        enum-base? (or string-base? number-base? (#{:ref :boolean} base))
        as-is [schema (conj unrendered f)]
        ;; the values of an IN as members of the column's type; nil when they are not
        members (cond (and (= :uuid base) (every? uuid-string? (:values f))) (map parse-uuid (:values f))
                      enum-base? (:values f)
                      (= :enum base) (:values f))]
    (case fact
      (:enum :domain-ref) [[:ref (schema-key schema-name (:type-name f))] unrendered]
      ;; numeric(p, s) holds |v| < 10^(p-s); the scale rounds, it does not reject
      :numeric (if (and decimal-base? (:precision f))
                 (let [limit (.pow 10M (int (- (:precision f) (or (:scale f) 0))))]
                   [[:and schema [:> (- limit)] [:< limit]] unrendered])
                 [schema unrendered])
      :in-set (cond (nil? members) as-is
                    ;; a second IN on the same column intersects
                    (= :enum base) (let [vs (filter (set members) (enum-values schema))] (if (seq vs) [(into [:enum] vs) unrendered] as-is))
                    :else [(into [:enum] members) unrendered])
      :not-in-set (cond (nil? members) as-is
                        (= :enum base) (let [vs (remove (set members) (enum-values schema))] (if (seq vs) [(into [:enum] vs) unrendered] as-is))
                        :else [[:and schema [:not (into [:enum] members)]] unrendered])
      :range (let [{:keys [min max min-exclusive? max-exclusive?]} f
                   int? (#{:int :pg/integer :pg/smallint} base)]
               (cond
                 number-base?
                 [(cond-> (with-props schema {:min (when min (if (and min-exclusive? int?) (inc min) (when-not min-exclusive? min)))
                                              :max (when max (if (and max-exclusive? int?) (dec max) (when-not max-exclusive? max)))})
                    (and min min-exclusive? (not int?)) (as-> s [:and s [:> min]])
                    (and max max-exclusive? (not int?)) (as-> s [:and s [:< max]]))
                  unrendered]
                 decimal-base?
                 [(cond-> (if (and (vector? schema) (= :and (first schema))) schema [:and schema])
                    min (conj [(if min-exclusive? :> :>=) min])
                    max (conj [(if max-exclusive? :< :<=) max]))
                  unrendered]
                 :else as-is))
      :non-blank (if string-base?
                   [(if (:trim? f) [:and (with-props schema {:min 1}) [:re "\\S"]] (with-props schema {:min 1})) unrendered]
                   as-is)
      :max-length (if string-base? [(with-props schema {:max (:max f)}) unrendered] as-is)
      :length (let [{:keys [fn min max exact]} f
                    bounds {:min (or exact min) :max (or exact max)}]
                (cond (and string-base? (#{:length :char_length} fn)) [(with-props schema bounds) unrendered]
                      (and (#{'bytes? :pg/bytes} base) (= :octet_length fn)) [(with-props (if (= :pg/bytes base) schema [:pg/bytes]) bounds) unrendered]
                      (and (= :vector base) (= :cardinality fn)) [(with-props schema bounds) unrendered]
                      ;; array_length of an empty array is NULL, so only an upper bound means what the CHECK means
                      (and (= :vector base) (= :array_length fn) (nil? (:min bounds))) [(with-props schema bounds) unrendered]
                      :else as-is))
      :json-type (if (= :any base)
                   (case (:json-type f)
                     "object" [:map unrendered]
                     "array" [[:sequential :any] unrendered]
                     as-is)
                   as-is)
      :regex (if-let [re (when string-base? (check/java-regex (:re f)))]
               [[:and schema [:re (str (when (:case-insensitive? f) "(?i)") re)]] unrendered]
               as-is)
      :like (if string-base?
              [[:and schema [:re (str (when (:case-insensitive? f) "(?i)") (check/like-regex (:pattern f)))]] unrendered]
              as-is)
      :when-present (let [[inner un] (apply-fact [schema unrendered] (assoc (:fact-when-present f) :column (:column f) :constraint (:constraint f)) schema-name)]
                      (if (= un unrendered) [inner unrendered] as-is))
      :not-null [schema unrendered]
      :null [:nil unrendered]
      as-is)))

(def ^:private fact-order
  {:enum 0 :domain-ref 0 :max-length 1 :numeric 1 :not-null 1 :null 1 :in-set 2 :not-in-set 3 :non-blank 3 :range 3 :length 3 :json-type 3 :when-present 4 :regex 5 :like 5})

(defn- override-for [overrides f]
  (when-let [c (:constraint f)] (get overrides c)))

(defn- fold-facts
  "Reduces facts over a base schema: {:schema :unrendered :skipped :applied}, :applied being the
   names of the constraints that shaped the schema."
  [schema-name base facts overrides]
  (reduce (fn [{:keys [schema unrendered] :as acc} f]
            (let [ov (override-for overrides f)]
              (cond
                (and (map? ov) (:skip ov)) (update acc :skipped conj f)
                ov (-> acc (assoc :schema [:and schema ov]) (update :applied conj (:constraint f)))
                :else (let [[s un] (apply-fact [schema unrendered] f schema-name)]
                        (cond-> (assoc acc :schema s :unrendered un)
                          (and (= un unrendered) (:constraint f)) (update :applied conj (:constraint f)))))))
          {:schema base :unrendered [] :skipped [] :applied []}
          (sort-by (juxt (comp fact-order :fact) :constraint) facts)))

(defn- column-schema
  "Schema of one column with provenance properties; nullable columns wrapped in [:maybe ...]."
  [schema-name {:keys [type nullable? default identity generated]} facts overrides]
  (let [{:keys [schema unrendered skipped applied]} (fold-facts schema-name (base-type type) facts overrides)
        lit (some-> default literal)
        schema (with-props schema {:pg/type type
                                   :pg/default (if (some? lit) lit default)
                                   :default (when (some? lit)
                                              (default-value (if-let [d (some #(when (= :domain-ref (:fact %)) %) facts)] (base-type (:base d)) schema) lit))
                                   :pg/identity identity
                                   :pg/generated (when generated true)
                                   :pg/constraint (when (seq applied) (vec (sort (distinct applied))))})
        not-null-check? (some (comp #{:not-null} :fact) facts)]
    {:schema (if (and nullable? (not not-null-check?)) [:maybe schema] schema)
     :unrendered unrendered :skipped skipped}))

(def ^:private type-facts #{:enum :domain-ref :max-length :numeric})

(defn- column-base
  "Base schema of a column: its type, shaped by the facts that come from the type itself."
  [schema-name column by-column]
  (:schema (fold-facts schema-name (base-type (:type column))
                       (filter (comp type-facts :fact) (by-column (:column column))) {})))

(defn- fragment
  "{:schema [:map ...] :unrendered :skipped} constraining only the columns named in facts (used
   inside :multi and :or). Column schemas name the constraint in :error/message so humanized
   errors point at it."
  [schema-name columns by-column facts constraint overrides]
  (let [parts (for [[col fs] (sort-by key (group-by :column facts))
                    :let [base (if (some (comp #{:null} :fact) fs) :nil (column-base schema-name (get columns col) by-column))
                          r (fold-facts schema-name base (remove (comp #{:null} :fact) fs) overrides)]]
                (assoc r :entry [(ident-key col) (with-props (:schema r) {:error/message constraint})]))]
    {:schema (into [:map] (map :entry parts))
     :unrendered (mapcat :unrendered parts)
     :skipped (mapcat :skipped parts)}))

(defn- pg-check [{:keys [constraint expr]}]
  [:pg/check {:pg/constraint constraint :error/message constraint} expr])

(defn- table-constraint
  "{:schema :unrendered :skipped} for a table-level check fact; :schema nil when it cannot be
   rendered. A branch that lost a fact is never enforced partially: the CHECK is evaluated
   whole, or reported whole."
  [schema-name columns by-column {:keys [fact constraint] :as f} overrides types]
  (let [frag #(fragment schema-name columns by-column (map (partial merge (select-keys f [:schema :table :constraint :expr])) %) constraint overrides)
        whole (fn [r] (cond (empty? (:unrendered r)) r
                            (check/supported? (:expr f) types) {:schema (pg-check f) :skipped (:skipped r)}
                            :else {:schema nil :unrendered [f] :skipped (:skipped r)}))]
    (case fact
      :branch-check (let [{:keys [dispatch branches default]} f
                          ;; a branch on the column being NULL dispatches on nil
                          bs (for [b branches, v (if (:null b) [nil] (:values b))] (assoc (frag (:facts b)) :value v))
                          d (when default (frag default))]
                      (whole {:schema (into [:multi {:dispatch (ident-key dispatch) :error/message constraint}]
                                            (concat (map (juxt :value :schema) bs) [[:malli.core/default (if d (:schema d) :any)]]))
                              :unrendered (mapcat :unrendered (cond-> bs d (conj d)))
                              :skipped (mapcat :skipped (cond-> bs d (conj d)))}))
      :or-check (let [alts (map frag (:alternatives f))]
                  (whole {:schema (into [:or {:error/message constraint}] (map :schema alts))
                          :unrendered (mapcat :unrendered alts)
                          :skipped (mapcat :skipped alts)}))
      :table-check {:schema (when (and (not (false? (:valid? f))) (check/supported? (:expr f) types)) (pg-check f))}
      {})))

(defn- order [f] [(or (:table f) (:type-name f) "") (or (:constraint f) "") (or (:column f) "")])

(defn- map-props [schema-name table tfacts]
  (prune {:pg/table (str schema-name "." table)
         :pg/primary-key (some #(when (= :primary-key (:fact %)) (:columns %)) tfacts)
         :pg/unique (not-empty (vec (for [f (sort-by :columns (filter (comp #{:unique} :fact) tfacts))]
                                      (cond-> {:columns (:columns f)} (false? (:nulls-distinct? f)) (assoc :nulls-distinct false)))))
         :pg/foreign-keys (not-empty (vec (for [f (sort-by :constraint (filter (comp #{:references} :fact) tfacts))]
                                            (cond-> {:columns (:columns f)
                                                     :table (str (get-in f [:to :schema]) "." (get-in f [:to :table]))
                                                     :to (get-in f [:to :columns])}
                                              (= :full (:match f)) (assoc :match :full)))))}))

(defn- table-checks
  "{:schemas :unrendered :skipped} of the constraints that span columns."
  [schema-name columns by-column tfacts overrides types]
  (let [results (for [f (sort-by order (filter #(and (#{:branch-check :or-check :table-check :unparsed} (:fact %)) (nil? (:column %))) tfacts))
                      :let [ov (override-for overrides f)]]
                  (cond (and (map? ov) (:skip ov)) {:skipped [f]}
                        ov {:schema ov}
                        :else (let [r (table-constraint schema-name columns by-column f overrides types)]
                                (if (or (:schema r) (seq (:unrendered r))) r (update r :unrendered conj f)))))]
    {:schemas (keep :schema results)
     :unrendered (mapcat :unrendered results)
     :skipped (mapcat :skipped results)}))

(defn- fold-columns [schema-name columns tfacts overrides]
  (let [by-column (group-by :column (filter :column tfacts))]
    (into (sorted-map)
          (for [[col c] columns]
            [col (column-schema schema-name c (remove (comp #{:column} :fact) (by-column col)) overrides)]))))

(defn- lost-checks
  "Names of the CHECKs among unrendered facts that lost a column fact in rendering and that
   the evaluator covers whole: their column facts give way to one :table-check each."
  [unrendered types]
  (set (keep #(when (and (:expr %) (check/supported? (:expr %) types)) (:constraint %)) unrendered)))

(defn- as-whole-checks
  "The facts of the CHECKs named in lost replaced by one :table-check each."
  [facts lost extra]
  (concat (remove #(and (:expr %) (lost (:constraint %))) facts)
          (for [c (sort lost) :let [f (some #(when (= c (:constraint %)) %) facts)]]
            (merge extra {:fact :table-check :constraint c :expr (:expr f) :columns (pattern/referenced-columns (:expr f))}))))

(defn- render-table [schema-name table tfacts overrides types]
  (let [columns (into {} (map (juxt :column identity) (filter (comp #{:column} :fact) tfacts)))
        ;; a first pass only tells which CHECKs lost a fact; those are re-folded as whole checks
        first-pass (fold-columns schema-name columns tfacts overrides)
        lost (lost-checks (mapcat (comp :unrendered val) first-pass) types)
        tfacts (if (empty? lost) tfacts (as-whole-checks tfacts lost {:schema schema-name :table table}))
        by-column (group-by :column (filter :column tfacts))
        rendered (if (empty? lost) first-pass (fold-columns schema-name columns tfacts overrides))
        row (into [:map (map-props schema-name table tfacts)] (for [[col r] rendered] [(ident-key col) (:schema r)]))
        checks (table-checks schema-name columns by-column tfacts overrides types)]
    {:entry [(schema-key schema-name table) (if (seq (:schemas checks)) (into [:and row] (:schemas checks)) row)]
     :unrendered (concat (mapcat (comp :unrendered val) rendered) (:unrendered checks))
     :skipped (concat (mapcat (comp :skipped val) rendered) (:skipped checks))}))

(defn- render-domain
  "{:entry :unrendered :skipped} of a domain: its base type shaped by the CHECKs that matched
   patterns, then [:pg/check-value expr] for the others the evaluator covers. As for tables,
   a CHECK that lost a fact in rendering is evaluated whole."
  [schema-name {:keys [type-name base not-null? facts]} checks overrides types]
  (let [first-pass (fold-facts schema-name (base-type base) facts overrides)
        lost (lost-checks (:unrendered first-pass) types)
        facts (if (empty? lost) facts (remove #(lost (:constraint %)) facts))
        checks (concat checks (for [c (sort lost) :let [f (some #(when (= c (:constraint %)) %) (:unrendered first-pass))]]
                                (assoc (select-keys f [:schema :type-name :constraint :expr]) :fact :domain-check)))
        {:keys [schema unrendered skipped]} (if (empty? lost) first-pass (fold-facts schema-name (base-type base) facts overrides))
        results (for [c checks :let [ov (override-for overrides c)]]
                  (cond (and (map? ov) (:skip ov)) {:skipped [c]}
                        ov {:schema ov}
                        (and (= :domain-check (:fact c)) (not (false? (:valid? c))) (check/supported? (:expr c) types))
                        {:schema [:pg/check-value {:pg/constraint (:constraint c) :error/message (:constraint c)} (:expr c)]}
                        :else {:unrendered [c]}))
        extras (keep :schema results)
        s (cond (empty? extras) schema
                (and (vector? schema) (= :and (first schema)) (not (map? (second schema)))) (into schema extras)
                :else (into [:and schema] extras))]
    {:entry [(schema-key schema-name type-name) (if not-null? s [:maybe s])]
     :unrendered (concat unrendered (mapcat :unrendered results))
     :skipped (concat skipped (mapcat :skipped results))}))

(defn registry
  "facts -> {:registry :unrendered :skipped}."
  ([facts] (registry facts {}))
  ([facts overrides]
   (let [schema-name (:schema (first facts))
         ;; literals of the schema's own types ('sad'::mood) are values as they are
         types (set (keep #(when (#{:enum-type :domain} (:fact %)) (:type-name %)) facts))
         tables (for [[table tfacts] (sort-by key (group-by :table (filter :table facts)))]
                  (render-table schema-name table tfacts overrides types))
         domains (for [f facts :when (= :domain (:fact f))]
                   (render-domain schema-name f (filter #(and (= (:type-name f) (:type-name %)) (#{:domain-check :unparsed} (:fact %))) facts) overrides types))
         parts (concat domains tables)]
     {:registry (into (sorted-map-by #(compare (str %1) (str %2)))
                      (concat (for [f facts :when (= :enum-type (:fact f))] [(schema-key schema-name (:type-name f)) (into [:enum] (:values f))])
                              (map :entry parts)))
      :unrendered (vec (sort-by order (mapcat :unrendered parts)))
      :skipped (vec (sort-by order (mapcat :skipped parts)))})))
