(ns pgmalli.impl.render
  "Facts -> malli schemas. Every fact kind has exactly one rendering.

   registry returns {:registry {name schema} :unrendered [fact] :skipped [fact]} with
     :pg.<schema>/<type>            enum types and domains
     :pg.<schema>/<table>           a valid row as read from the database
     :pg.<schema>.<table>/insert    what an INSERT may carry: closed map, generated and identity
                                    ALWAYS columns removed, columns with defaults or NULL optional
   A row schema is [:map ...] alone, or [:and [:map ...] checks...] when the table has constraints
   across columns. Those are data: [:multi ...] for branches on one column's value, [:or ...] of
   map fragments otherwise. With {:checks :fn} the remaining ones are compiled into [:fn ...].

   Properties record provenance and keys: on the map :pg/table :pg/primary-key :pg/unique; on
   columns :pg/type :pg/default :pg/identity :pg/generated :pg/references :pg/constraint.
   Literal defaults also get malli's :default.

   Identifiers that are not plain names become string keys, keeping the output readable EDN.
   overrides is {constraint-name schema-or-{:skip reason}}."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [pgmalli.impl.compile :as compile]))

(defn ident-key [s]
  (if (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s) (keyword s) s))

(defn- schema-key
  ([schema-name s] (schema-key schema-name nil s))
  ([schema-name table s]
   (let [ns-part (str/join "." (remove nil? ["pg" schema-name table]))]
     (if (and (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s) (or (nil? table) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" table)))
       (keyword ns-part s)
       (str ns-part "/" s)))))

(def ^:private time-types
  {"date" :time/local-date "time" :time/local-time "timetz" :time/offset-time
   "timestamp" :time/local-date-time "timestamp without time zone" :time/local-date-time
   "timestamptz" :time/instant "timestamp with time zone" :time/instant "interval" :time/duration})

(defn- base-type [data-type]
  (let [[_ elem] (re-matches #"(.+)\[\]" data-type)]
    (cond
      elem [:vector (base-type elem)]
      (#{"smallint" "integer" "bigint" "int2" "int4" "int8"} data-type) :int
      (#{"numeric" "decimal"} data-type) 'decimal?
      (#{"real" "double precision" "float4" "float8"} data-type) :double
      (= "boolean" data-type) :boolean
      (#{"text" "varchar" "character varying" "char" "character" "bpchar" "citext" "name"} data-type) :string
      (= "uuid" data-type) :uuid
      (time-types data-type) (time-types data-type)
      (= "bytea" data-type) 'bytes?
      :else :any)))

(defn- with-props [schema props]
  (let [props (into {} (remove (comp nil? val) props))]
    (cond
      (empty? props) schema
      (vector? schema) (if (map? (second schema))
                         (assoc schema 1 (merge (second schema) props))
                         (into [(first schema) props] (rest schema)))
      :else [schema props])))

(defn- literal [e]
  (let [e (walk/postwalk #(if (and (vector? %) (= :cast (first %))) (second %) %) e)]
    (when (or (string? e) (number? e) (boolean? e)) e)))

(defn- apply-fact
  "[schema unrendered] after one fact on a column schema."
  [[schema unrendered] {:keys [fact] :as f} schema-name]
  (let [base (if (vector? schema) (first schema) schema)
        string? (= :string base)
        number? (#{:int :double} base)
        decimal? (= 'decimal? base)
        enum-base? (or string? number? (= :ref base) (= :enum base))
        as-is [schema (conj unrendered f)]]
    (case fact
      :enum [[:ref (schema-key schema-name (:type-name f))] unrendered]
      :domain-ref [[:ref (schema-key schema-name (:type-name f))] unrendered]
      :in-set (if enum-base? [(into [:enum] (:values f)) unrendered] as-is)
      :range (let [{:keys [min max min-exclusive? max-exclusive?]} f
                   int? (= :int base)]
               (cond
                 number?
                 [(cond-> (with-props schema {:min (when min (if (and min-exclusive? int?) (inc min) (when-not min-exclusive? min)))
                                              :max (when max (if (and max-exclusive? int?) (dec max) (when-not max-exclusive? max)))})
                    (and min min-exclusive? (not int?)) (as-> s [:and s [:> min]])
                    (and max max-exclusive? (not int?)) (as-> s [:and s [:< max]]))
                  unrendered]
                 decimal?
                 [(cond-> [:and schema]
                    min (conj [(if min-exclusive? :> :>=) min])
                    max (conj [(if max-exclusive? :< :<=) max]))
                  unrendered]
                 :else as-is))
      :non-blank (if string?
                   [(if (:trim? f) [:and (with-props schema {:min 1}) [:re "\\S"]] (with-props schema {:min 1})) unrendered]
                   as-is)
      :max-length (if string? [(with-props schema {:max (:max f)}) unrendered] as-is)
      :length (if (and string? (#{:length :char_length} (:fn f)))
                (let [{:keys [min max exact]} f]
                  [(with-props schema {:min (or exact min) :max (or exact max)}) unrendered])
                as-is)
      :json-type (if (= :any base)
                   (case (:json-type f)
                     "object" [:map unrendered]
                     "array" [[:sequential :any] unrendered]
                     as-is)
                   as-is)
      :regex (if string? [[:and schema [:re (str (when (:case-insensitive? f) "(?i)") (:re f))]] unrendered] as-is)
      :when-present (let [[inner un] (apply-fact [schema unrendered] (assoc (:fact-when-present f) :column (:column f) :constraint (:constraint f)) schema-name)]
                      (if (= un unrendered) [inner unrendered] as-is))
      (:numeric :not-null) [schema unrendered]
      :null [:nil unrendered]
      as-is)))

(def ^:private fact-order
  {:enum 0 :domain-ref 0 :max-length 1 :numeric 1 :not-null 1 :null 1 :in-set 2 :non-blank 3 :range 3 :length 3 :json-type 3 :when-present 4 :regex 5})

(defn- override-for [overrides f]
  (when-let [c (:constraint f)] (get overrides c)))

(defn- fold-facts
  "Reduces facts over a base schema. Returns [schema unrendered skipped applied-constraints]."
  [schema-name base facts overrides]
  (reduce (fn [[s un sk applied] f]
            (let [ov (override-for overrides f)]
              (cond
                (and (map? ov) (:skip ov)) [s un (conj sk f) applied]
                ov [[:and s ov] un sk (conj applied (:constraint f))]
                :else (let [[s2 un2] (apply-fact [s un] f schema-name)]
                        [s2 un2 sk (if (= un2 un) (cond-> applied (:constraint f) (conj (:constraint f))) applied)]))))
          [base [] [] []]
          (sort-by (juxt (comp fact-order :fact) :constraint) facts)))

(defn- column-schema
  "Schema of one column with provenance properties; nullable columns wrapped in [:maybe ...]."
  [schema-name {:keys [type nullable? default identity generated] :as column} facts references overrides]
  (let [[schema unrendered skipped applied] (fold-facts schema-name (base-type (str/replace type #"^[^.]+\." "")) facts overrides)
        lit (some-> default literal)
        schema (with-props schema {:pg/type type
                                   :pg/default (if (some? lit) lit default)
                                   :default lit
                                   :pg/identity identity
                                   :pg/generated (when generated true)
                                   :pg/references (get references (:column column))
                                   :pg/constraint (when (seq applied) (vec (sort applied)))})
        not-null-check? (some (comp #{:not-null} :fact) facts)]
    {:schema (if (and nullable? (not not-null-check?)) [:maybe schema] schema)
     :unrendered unrendered :skipped skipped}))

(def ^:private type-facts #{:enum :domain-ref :max-length :numeric})

(defn- column-base
  "Base schema of a column: its type, shaped by the facts that come from the type itself."
  [schema-name column by-column]
  (first (fold-facts schema-name (base-type (str/replace (:type column "text") #"^[^.]+\." ""))
                     (filter (comp type-facts :fact) (by-column (:column column))) {})))

(defn- fragment
  "[:map ...] constraining only the columns named in facts (used inside :multi and :or).
   Each column schema names the constraint in :error/message so humanized errors point at it.
   For insert schemas the keys are optional: an omitted column is NULL."
  [schema-name columns by-column facts optional? constraint]
  (into [:map]
        (for [[col fs] (sort-by key (group-by :column facts))
              :let [base (if (some (comp #{:null} :fact) fs) :nil (column-base schema-name (get columns col) by-column))
                    [s _ _ _] (fold-facts schema-name base (remove (comp #{:null} :fact) fs) {})
                    s (with-props s {:error/message constraint})]]
          (if optional? [(ident-key col) {:optional true} s] [(ident-key col) s]))))

(defn- table-constraint
  "Schema for a table-level check fact, or nil when it cannot be rendered under these options."
  [schema-name columns by-column {:keys [fact constraint] :as f} {:keys [checks]} optional?]
  (case fact
    :branch-check (let [{:keys [dispatch branches default]} f]
                    (into [:multi {:dispatch (ident-key dispatch) :error/message constraint}]
                          (concat (for [b branches, v (:values b)] [v (fragment schema-name columns by-column (:facts b) optional? constraint)])
                                  [[:malli.core/default (if default (fragment schema-name columns by-column default optional? constraint) :any)]])))
    :or-check (into [:or {:error/message constraint}] (map #(fragment schema-name columns by-column % optional? constraint) (:alternatives f)))
    :table-check (when (= :fn checks)
                   (try [:fn {:pg/constraint constraint} (compile/check-fn (:expr f))]
                        (catch Exception _ nil)))
    nil))

(defn- order [f] [(or (:table f) "") (or (:constraint f) "") (or (:column f) "")])

(defn- references-by-column
  "{column [table column]} for single-column foreign keys; other schemas are prefixed."
  [schema-name facts]
  (into {} (for [f facts :when (and (= :references (:fact f)) (= 1 (count (:columns f))))
                 :let [{:keys [schema table columns]} (:to f)]]
             [(first (:columns f)) [(if (= schema schema-name) table (str schema "." table)) (first columns)]])))

(defn- render-table [schema-name table tfacts overrides options]
  (let [columns (into {} (map (juxt :column identity) (filter (comp #{:column} :fact) tfacts)))
        by-column (group-by :column (filter :column tfacts))
        references (references-by-column schema-name tfacts)
        rendered (into (sorted-map)
                       (for [[col c] columns]
                         [col (column-schema schema-name c (remove (comp #{:column} :fact) (by-column col)) references overrides)]))
        keys-props {:pg/table table
                    :pg/primary-key (some #(when (= :primary-key (:fact %)) (:columns %)) tfacts)
                    :pg/unique (let [u (vec (sort (map :columns (filter (comp #{:unique} :fact) tfacts))))] (when (seq u) u))}
        row-map (into [:map (into {} (remove (comp nil? val) keys-props))]
                      (for [[col r] rendered] [(ident-key col) (:schema r)]))
        insert-map (into [:map {:closed true}]
                         (for [[col r] rendered
                               :let [c (columns col)]
                               :when (not (or (:generated c) (= :always (:identity c))))]
                           [(ident-key col)
                            (if (or (:identity c) (some? (:default c)) (:nullable? c)) {:optional true} {})
                            (:schema r)]))
        table-facts (sort-by order (filter #(and (#{:branch-check :or-check :table-check :unparsed} (:fact %)) (nil? (:column %))) tfacts))
        grouped (group-by (fn [f] (let [ov (override-for overrides f)]
                                    (cond (and (map? ov) (:skip ov)) :skipped
                                          ov :override
                                          (table-constraint schema-name columns by-column f options false) :rendered
                                          :else :unrendered)))
                          table-facts)
        extras (fn [optional?]
                 (concat (map #(override-for overrides %) (:override grouped))
                         (map #(table-constraint schema-name columns by-column % options optional?) (:rendered grouped))))
        with-checks (fn [m optional?] (let [xs (extras optional?)] (if (seq xs) (into [:and m] xs) m)))]
    {:entries [[(schema-key schema-name table) (with-checks row-map false)]
               [(schema-key schema-name table "insert") (with-checks insert-map true)]]
     :unrendered (concat (mapcat (comp :unrendered val) rendered) (:unrendered grouped))
     :skipped (concat (mapcat (comp :skipped val) rendered) (:skipped grouped))}))

(defn- render-domain [schema-name {:keys [type-name base not-null? facts]}]
  (let [[s _ _ _] (fold-facts schema-name (base-type base) facts {})]
    [(schema-key schema-name type-name) (if not-null? s [:maybe s])]))

(defn registry
  "facts -> {:registry :unrendered :skipped}. options: {:checks :data|:fn} (default :data)."
  ([facts] (registry facts {} {}))
  ([facts overrides] (registry facts overrides {}))
  ([facts overrides options]
   (let [schema-name (:schema (first facts))
         tables (for [[table tfacts] (sort-by key (group-by :table (filter :table facts)))]
                  (render-table schema-name table tfacts overrides (merge {:checks :data} options)))]
     {:registry (into (sorted-map-by #(compare (str %1) (str %2)))
                      (concat (for [f facts :when (= :enum-type (:fact f))] [(schema-key schema-name (:type-name f)) (into [:enum] (:values f))])
                              (for [f facts :when (= :domain (:fact f))] (render-domain schema-name f))
                              (mapcat :entries tables)))
      :unrendered (vec (sort-by order (mapcat :unrendered tables)))
      :skipped (vec (sort-by order (mapcat :skipped tables)))})))
