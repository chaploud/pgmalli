(ns pgmalli-next.impl.render
  "Facts -> malli schemas: rendered. A row schema is [:map ...] of its columns' types; a CHECK is
   no part of it (docs/types.md). Identifiers that are not plain names become string keys.
   Properties are docs/types.md#properties. overrides is {:schema.table/column schema}."
  (:require [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(defn- base-type [data-type]
  (let [t (types/bare-name (or data-type ""))]
    (if-let [elem (types/element t)]
      [:vector (base-type elem)]
      (:schema (types/entry t) :any))))

(defn- prune [m] (into {} (remove (comp nil? val)) m))

(defn- with-props [schema props]
  (let [props (prune props)]
    (cond (empty? props) schema
          (vector? schema) (if (map? (second schema))
                             (update schema 1 merge props)
                             (into [(first schema) props] (rest schema)))
          :else [schema props])))

(def ^:private integer-kinds
  "The schema kinds of the integer types."
  (set (for [[_ e] types/types :when (= :integer (:value e))] (shape/kind (:schema e)))))

(defn- default-value
  "A literal default as a value of the column's schema (malli's :default), or nil when the
   literal is not one (a date as a string)."
  [schema lit]
  (let [base (shape/kind schema)]
    (cond
      (and (integer-kinds base) (integer? lit)) lit
      (and (= :double base) (number? lit)) (double lit)
      (and (= 'decimal? base) (number? lit)) (bigdec lit)
      (and (#{:string :ref :enum} base) (string? lit)) lit
      (and (= :boolean base) (boolean? lit)) lit)))

(defn- rejecting-nil
  "A column of any type still has to be something: :any takes nil, :some does not."
  [schema]
  (cond (= :any schema) :some
        (and (vector? schema) (= :any (first schema))) (assoc schema 0 :some)
        :else schema))

(defn type-name
  "The type a column type names, without schema or typmod, by the name pgmalli-next.impl.types keys
   it by (\"public.inet\" is inet, \"int4\" integer)."
  [data-type]
  (types/canonical-name (types/bare-name data-type)))

(defn column-override-key
  "The :overrides key of one column: :schema.table/column."
  [schema table column]
  (keyword (str schema "." table) column))

(defn- order [f] [(or (:table f) (:type-name f) "") (or (:constraint f) "") (or (:column f) "")])

(defn- column-schema
  "{:schema :unrendered} of one column: its type (an enum type's a reference to it), or an
   override of the column in its place, with provenance properties; nullable in [:maybe ...]."
  [schema-name {:keys [schema table column type domain nullable? default default-sql identity generated]} facts overrides]
  (let [override (get overrides (column-override-key schema table column))
        enum (some #(when (= :enum (:fact %)) %) facts)
        unknown (some #(when (= :unknown-type (:fact %)) %) facts)
        n (some #(when (= :max-length (:fact %)) (:max %)) facts)
        base (cond override override
                   enum (cond->> [:ref (shape/schema-key schema-name (:type-name enum))] (:array? enum) (vector :vector))
                   ;; a type of another schema named as a pg_catalog type (s.date) is no pg_catalog type
                   unknown (if (types/element type) [:vector :any] :any)
                   :else (base-type type))
        s (with-props base {:pg/type type
                            :pg/domain domain
                            ;; a varchar(n)'s, char(n)'s or bit's n, which no schema checks: the database does
                            :pg/max-length (when-not override n)
                            :pg/default (cond (some? default) default default-sql {:sql default-sql})
                            ;; a column override's schema is the user's: the SQL literal is no value known of it
                            :default (when (and (some? default) (not override)) (default-value base default))
                            :pg/identity identity
                            :pg/generated (some->> generated (hash-map :sql))})]
    {:schema (if nullable? [:maybe s] (rejecting-nil s))
     :unrendered (when (and unknown (not override)) [unknown])}))

(defn- map-props [schema-name table tfacts]
  (if-let [v (some #(when (= :view (:fact %)) %) tfacts)]
    (cond-> {:pg/view (str schema-name "." table)} (:materialized? v) (assoc :pg/materialized true))
    (prune {:pg/table (str schema-name "." table)
            :pg/primary-key (some #(when (= :primary-key (:fact %)) (:columns %)) tfacts)
            :pg/primary-key-deferrable (when (some #(and (= :primary-key (:fact %)) (:deferrable? %)) tfacts) true)
            :pg/unique (not-empty (vec (for [f (sort-by :columns (filter (comp #{:unique} :fact) tfacts))]
                                         (cond-> {:columns (:columns f)}
                                           (false? (:nulls-distinct? f)) (assoc :nulls-distinct false)
                                           ;; from a unique index rather than a constraint
                                           (:index? f) (assoc :index true)
                                           ;; a partial index: unique among the rows its predicate holds for, the
                                           ;; predicate where ir.sql reads it
                                           (:partial? f) (assoc :where (or (:predicate f) true))
                                           (:deferrable? f) (assoc :deferrable true)))))
            :pg/foreign-keys (not-empty (vec (for [f (sort-by :constraint (filter (comp #{:references} :fact) tfacts))]
                                               (cond-> {:columns (:columns f)
                                                        :table (str (get-in f [:to :schema]) "." (get-in f [:to :table]))
                                                        :to (get-in f [:to :columns])}
                                                 (= :full (:match f)) (assoc :match :full)))))
            :pg/exclude (not-empty (vec (for [f (sort-by :constraint (filter (comp #{:exclude} :fact) tfacts))]
                                          (cond-> {:constraint (:constraint f) :using (:method f) :elements (:elements f)}
                                            (:partial? f) (assoc :where true)))))
            ;; other tables inherit it: a query reads their rows too, which its keys do not span
            :pg/inherited (when (some (comp #{:inherited} :fact) tfacts) true)
            ;; a BEFORE row trigger runs before NOT NULL is checked: it may fill what a write leaves out
            :pg/before-triggers (not-empty (into {} (for [event [:insert :update]
                                                          :let [names (vec (for [f tfacts :when (and (= :trigger (:fact f)) (:before f) (event f))] (:name f)))]
                                                          :when (seq names)]
                                                      [event names])))})))

(defn- table-diagnostics
  "What deserves a look in a table: [{:kind :severity :message ...} ...] of its facts."
  [tfacts]
  (let [;; a partial unique index repeats a key on its columns; no key repeats it
        keys-of (fn [fact] (map :columns (remove :partial? (filter (comp #{fact} :fact) tfacts))))]
    (concat
     (for [f tfacts :when (false? (:valid? f))]
       {:kind :not-valid :severity :info :constraint (:constraint f)
        :message (str (:constraint f) " is NOT VALID: rows from before it may violate it, new rows may not")})
     (for [f tfacts :when (and (= :trigger (:fact f)) (:insert f))]
       {:kind :row-trigger :severity :info :trigger (:name f)
        :message (str "trigger " (:name f) " runs for every inserted row: its code may reject or change rows the schema accepts")})
     (for [f tfacts :when (= :exclude (:fact f))]
       {:kind :exclude :severity :info :constraint (:constraint f)
        :message (str "EXCLUDE " (:constraint f) " is not checked by the schemas: a row it refuses still validates")})
     (for [f tfacts :when (= :not-enforced (:fact f))]
       {:kind :not-enforced :severity :info :constraint (:constraint f)
        :message (str (:constraint f) " is NOT ENFORCED: the database never checks it, so the schema does not either")})
     (for [f (filter (comp #{:unique} :fact) tfacts)
           :when (or (some #{(:columns f)} (keys-of :primary-key))
                     (< (if (:partial? f) 0 1) (count (filter #{(:columns f)} (keys-of :unique)))))]
       {:kind :redundant-unique :severity :info :constraint (:constraint f)
        :message (str (:constraint f) " repeats a key on the same columns " (pr-str (:columns f)))}))))

(defn- render-table [schema-name table tfacts overrides]
  (let [by-column (group-by :column (filter :column tfacts))
        columns (sort-by first (for [f tfacts :when (= :column (:fact f))]
                               [(:column f) (column-schema schema-name f (by-column (:column f)) overrides)]))]
    {:entry [(shape/schema-key schema-name table)
             (into [:map (map-props schema-name table tfacts)] (for [[col r] columns] [(shape/ident-key col) (:schema r)]))]
     :unrendered (mapcat (comp :unrendered second) columns)
     :diagnostics (map #(assoc % :table (str schema-name "." table)) (table-diagnostics tfacts))}))

(defn- overloads
  "{name [overload ...]} of the facts of kind (:function or :operator)."
  [facts kind]
  (into (sorted-map) (update-vals (group-by :name (filter #(= kind (:fact %)) facts)) #(vec (sort-by pr-str (map :overload %))))))

(defn rendered
  "facts -> {:registry :functions :operators :sequences :unrendered :diagnostics}, :registry being
   the file's :registry map (names to schemas) rather than a malli registry; a diagnostic is what
   deserves a look in the database: {:table :kind :severity :message ...}."
  ([facts] (rendered facts {}))
  ([facts overrides]
   (let [schema-name (:schema (first facts))
         tables (for [[table tfacts] (sort-by key (group-by :table (filter :table facts)))]
                  (render-table schema-name table tfacts overrides))]
     {:registry (into (sorted-map-by #(compare (str %1) (str %2)))
                      (concat (for [f facts :when (= :enum-type (:fact f))] [(shape/schema-key schema-name (:type-name f)) (into [:enum] (:values f))])
                              (map :entry tables)))
      :functions (overloads facts :function)
      :operators (overloads facts :operator)
      :sequences (vec (sort (for [f facts :when (= :sequence (:fact f))] (:name f))))
      :unrendered (vec (sort-by order (mapcat :unrendered tables)))
      :diagnostics (vec (sort-by (juxt :table :constraint :kind) (mapcat :diagnostics tables)))})))
