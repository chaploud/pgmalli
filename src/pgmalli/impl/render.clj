(ns pgmalli.impl.render
  "Facts -> malli schemas. Every fact kind has exactly one rendering; there are no options.

   registry returns {:registry {name schema} :unrendered [fact] :skipped [fact]}.
   Names: enum types as :pg.<schema>/<type>, tables as :pg.<schema>/<table>. A table schema
   describes one row as read from the database: every column present, nullable ones as
   [:maybe ...]. Column properties record provenance: :pg/type, :pg/default, :pg/constraint.

   Identifiers that are not plain names (spaces, punctuation) become string keys so the
   result stays readable EDN.

   overrides is {constraint-name schema-or-{:skip reason}}. A schema is added with [:and ...]
   to the column (column-local constraint) or the table (table constraint); :skip drops the
   fact from :unrendered into :skipped."
  (:require [clojure.string :as str]
            [pgmalli.impl.compile :as compile]))

(defn ident-key
  "Keyword for a plain identifier, the string itself otherwise."
  [s]
  (if (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s) (keyword s) s))

(defn- schema-key [schema-name s]
  (if (re-matches #"[A-Za-z_][A-Za-z0-9_]*" s)
    (keyword (str "pg." schema-name) s)
    (str "pg." schema-name "/" s)))

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
      (#{"timestamp" "timestamptz" "timestamp with time zone" "timestamp without time zone"} data-type) 'inst?
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

(defn- apply-fact
  "[schema unrendered] after one fact. Facts that cannot be rendered on this base go to unrendered."
  [[schema unrendered] {:keys [fact] :as f} schema-name]
  (let [base (if (vector? schema) (first schema) schema)
        string? (= :string base)
        number? (#{:int :double} base)
        decimal? (= 'decimal? base)
        enum-base? (or string? number? (= :ref base) (= :enum base))
        as-is [schema (conj unrendered f)]]
    (case fact
      :enum [[:ref (schema-key schema-name (:type-name f))] unrendered]
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
                 ;; decimal? is a predicate schema and ignores :min/:max
                 decimal?
                 [(cond-> [:and schema]
                    min (conj [(if min-exclusive? :> :>=) min])
                    max (conj [(if max-exclusive? :< :<=) max]))
                  unrendered]
                 :else as-is))
      :non-blank (if string? [(with-props schema {:min 1 :pg/trim (when (:trim? f) true)}) unrendered] as-is)
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
      as-is)))

(def ^:private fact-order
  {:enum 0 :max-length 1 :numeric 1 :not-null 1 :in-set 2 :non-blank 3 :range 3 :length 3 :json-type 3 :when-present 4 :regex 5})

(defn- override-for [overrides f]
  (when-let [c (:constraint f)] (get overrides c)))

(defn- render-column [schema-name {:keys [type nullable? default]} facts overrides]
  (let [facts (sort-by (juxt (comp fact-order :fact) :constraint) facts)
        [schema unrendered skipped applied]
        (reduce (fn [[s un sk applied] f]
                  (let [ov (override-for overrides f)]
                    (cond
                      (and (map? ov) (:skip ov)) [s un (conj sk f) applied]
                      ov [[:and s ov] un sk (conj applied (:constraint f))]
                      :else (let [[s2 un2] (apply-fact [s un] f schema-name)]
                              [s2 un2 sk (if (= un2 un) (cond-> applied (:constraint f) (conj (:constraint f))) applied)]))))
                [(base-type (str/replace type #"^[^.]+\." "")) [] [] []]
                facts)
        schema (with-props schema {:pg/type type
                                   :pg/default default
                                   :pg/constraint (when (seq applied) (vec (sort applied)))})
        not-null-check? (some (comp #{:not-null} :fact) facts)
        schema (if (and nullable? (not not-null-check?)) [:maybe schema] schema)]
    {:schema schema :unrendered unrendered :skipped skipped}))

(defn- order [f] [(or (:table f) "") (or (:constraint f) "") (or (:column f) "")])

(defn registry
  ([facts] (registry facts {}))
  ([facts overrides]
   (let [schema-name (:schema (first facts))
         by-table (group-by :table (filter :table facts))
         enums (->> facts (filter (comp #{:enum-type} :fact)) (map (juxt :type-name :values)))
         tables
         (for [[table tfacts] (sort-by key by-table)
               :let [by-column (group-by :column (filter :column tfacts))
                     columns (->> (filter (comp #{:column} :fact) tfacts) (sort-by :column))
                     rendered (map (fn [c] [c (render-column schema-name c (remove (comp #{:column} :fact) (by-column (:column c))) overrides)]) columns)
                     ;; :unparsed facts with a column (bad DEFAULT) are handled on the column side
                     table-checks (sort-by order (filter #(and (#{:table-check :unparsed} (:fact %)) (nil? (:column %))) tfacts))
                     compiled (fn [f] (when (= :table-check (:fact f))
                                        (try [:fn {:pg/constraint (:constraint f)} (compile/check-fn (:expr f))]
                                             (catch Exception _ nil))))
                     grouped (group-by (fn [f] (let [ov (override-for overrides f)]
                                                 (cond (and (map? ov) (:skip ov)) :skipped
                                                       ov :override
                                                       (compiled f) :compiled
                                                       :else :unrendered)))
                                       table-checks)
                     map-schema (into [:map {:pg/table table}]
                                      (map (fn [[c r]] [(ident-key (:column c)) (:schema r)]) rendered))
                     extras (concat (map #(override-for overrides %) (:override grouped))
                                    (map compiled (:compiled grouped)))
                     schema (if (seq extras) (into [:and map-schema] extras) map-schema)]]
           {:name (schema-key schema-name table)
            :schema schema
            :unrendered (concat (mapcat (comp :unrendered second) rendered) (:unrendered grouped))
            :skipped (concat (mapcat (comp :skipped second) rendered) (:skipped grouped))})]
     {:registry (into (sorted-map-by #(compare (str %1) (str %2)))
                      (concat (map (fn [[n vs]] [(schema-key schema-name n) (into [:enum] vs)]) enums)
                              (map (juxt :name :schema) tables)))
      :unrendered (vec (sort-by order (mapcat :unrendered tables)))
      :skipped (vec (sort-by order (mapcat :skipped tables)))})))
