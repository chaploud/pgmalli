(ns pgmalli.impl.insert
  "Datasets into the database: a dataset as INSERT statements, its tables and rows in an order
   the database accepts and its values in the form the driver needs."
  (:require [clojure.string :as str]
            [pgmalli.impl.dataset :as ds]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.shape :as shape]))

(defn- ref-type
  "The type of the schema a [:ref ...] names, as an entity HoneySQL quotes whatever its options
   (:pg.ins/mood is \"ins\".\"mood\"); nil for another schema."
  [s]
  (when (vector? s)
    (case (first s)
      :ref (let [k (last s) [ns n] (if (keyword? k) [(namespace k) (name k)] (str/split k #"/" 2))]
             [:entity (str (subs ns 3) "." n)])
      ;; CHECKs narrow a type as [:and type ...]
      :and (ref-type (first (shape/entries s)))
      nil)))

(def ^:private cast-type
  ;; character and bit name char(1) and bit(1) in a cast, which cut a longer value
  {"character" :bpchar "bit" :varbit})

(defn- builtin-cast
  "The type a string parameter is cast to for a built-in type a string parameter (typed varchar
   by the driver) cannot be written to; nil for the string types."
  [type-name]
  (when (or (shape/opaque-types type-name) (#{"bit" "bit varying"} type-name))
    (get cast-type type-name (keyword type-name))))

(defn- insert-value
  "A dataset value in the form an INSERT needs: a value of an enum or domain cast to it, a
   string of a type strings are not cast to implicitly (inet, bit ...) cast to it, json
   written and cast, an array with its element type; anything else as it is."
  [schema v]
  (let [s (shape/non-null schema)
        t (:pg/type (shape/column-props s))
        type-name (shape/type-name (shape/column-props s))]
    (cond (nil? v) nil
          (ref-type s) [:cast v (ref-type s)]
          (shape/json-types t) [:cast (json/write v) (keyword t)]
          (and (vector? s) (= :vector (first s)))
          (if-let [elem (ref-type (shape/non-null (last s)))]
            (if (seq v)
              [:array (mapv #(vector :cast % elem) v)]
              [:array {:select [[[:cast nil elem]]] :where false}])
            (if type-name [:array (vec v) (get cast-type type-name (keyword type-name))] [:array (vec v)]))
          (string? v) (if-let [c (builtin-cast type-name)] [:cast v c] v)
          :else v)))

(defn- parents-first
  "Tables ordered so every table comes after the tables it references, among the given ones
   (a parent not given is already in the database)."
  [ts]
  (loop [out [] left ts given (set (map :table ts))]
    (if (empty? left)
      out
      (let [placed (set (map :table out))
            ready (filter (fn [t] (every? #(or (= (:table %) (:table t)) (not (given (:table %))) (placed (:table %))) (:refs t))) left)]
        (when (empty? ready)
          (throw (ex-info "tables reference each other in a cycle" {:tables (mapv :table left)})))
        (recur (into out ready) (remove (set ready) left) given)))))

(defn- rows-parents-first
  "Rows of one table ordered so a row comes after the rows it references through refs (the
   table's references to itself); a NULL reference, or one to the row itself, waits for nothing."
  [rows refs]
  (if (empty? refs)
    rows
    (loop [out [] left rows]
      (if (empty? left)
        out
        (let [targets (set (for [r refs, row out] [(:to r) (ds/key-of row (:to r))]))
              ready (filter (fn [row] (every? (fn [{:keys [columns to]}]
                                                (let [k (ds/key-of row columns)]
                                                  (or (some nil? k) (= k (ds/key-of row to)) (targets [to k]))))
                                              refs))
                            left)]
          (if (empty? ready)
            (into out left)
            (recur (into out ready) (remove (set ready) left))))))))

(defn inserts
  "[{:insert-into ... :values [row ...]} ...] for a dataset: its tables, parents before the
   tables referencing them and, within a table, rows before the rows referencing them (rows
   referencing each other in a cycle are fine in one INSERT: the database checks foreign keys
   at the end of the statement); enum, json and array values in the form the driver needs.
   Generated columns are left out; a table with an identity column gets OVERRIDING SYSTEM
   VALUE, so the ids the rows carry (and the references to them) hold; a column a row lacks is
   DEFAULT. A table the registry does not have, or a column the table does not have, is an
   error thrown by the call (the vector is built eagerly). Option :on-conflict :nothing adds
   ON CONFLICT DO NOTHING."
  [registry dataset {:keys [on-conflict]}]
  (let [all (ds/tables registry)
        ts (filter #(seq (get dataset (:table %))) all)]
    (when-let [unknown (seq (remove (set (map :table all)) (keys dataset)))]
      (throw (ex-info (str "dataset holds tables the registry does not: " (pr-str unknown)) {:tables unknown})))
    (vec
     (for [{:keys [name table refs]} (parents-first ts)
           :let [entries (shape/column-entries (get (shape/schemas-of registry) name))
                 columns (into {} (map (fn [[k _ s]] [k s])) entries)
                 generated (set (for [[k _ s] entries :when (:pg/generated (shape/column-props s))] k))
                 identity? (some (fn [[_ _ s]] (= :always (:pg/identity (shape/column-props s)))) entries)
                 self (filter #(= table (:table %)) refs)
                 rows (rows-parents-first (get dataset table) self)
                 unknown (into {} (for [[i row] (map-indexed vector rows)
                                        :let [u (remove #(contains? columns %) (keys row))] :when (seq u)]
                                    [i (vec u)]))
                 _ (when (seq unknown)
                     (throw (ex-info (str table " rows carry columns the table does not have: " (pr-str (distinct (mapcat val unknown))))
                                     {:table table :rows unknown})))
                 ;; rows of defaults alone still name a column: VALUES () is no SQL, DEFAULT VALUES one row only
                 used (or (seq (remove generated (distinct (mapcat keys rows)))) (take 1 (map first entries)))]]
       (cond-> {:insert-into (if identity? [{:overriding-value :system} (keyword table)] (keyword table))
                :values (mapv (fn [row] (into {} (for [k used] [k (if (contains? row k) (insert-value (get columns k) (get row k)) [:default])]))) rows)}
           (= :nothing on-conflict) (assoc :on-conflict [] :do-nothing []))))))
