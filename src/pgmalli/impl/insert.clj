(ns pgmalli.impl.insert
  "Datasets into the database: a dataset as INSERT statements, its tables and rows in an order
   the database accepts and its values in the form the driver needs."
  (:require [clojure.string :as str]
            [pgmalli.impl.dataset :as ds]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.shape :as shape]))

(defn- sql-type
  "The type an INSERT casts to: the referenced type's qualified name (:pg.ins/mood is ins.mood;
   :pg/type carries the name without its schema), else :pg/type."
  [s]
  (if (and (vector? s) (= :ref (first s)))
    (let [k (last s) [ns n] (if (keyword? k) [(namespace k) (name k)] (str/split k #"/" 2))]
      (str (subs ns 3) "." n))
    (:pg/type (shape/column-props s))))

(defn- insert-value
  "A dataset value in the form an INSERT needs: an enum cast to its type (a string parameter
   stays text), json written and cast, an array with its element type; anything else as it is."
  [registry schema v]
  (let [s (shape/non-null schema)
        t (:pg/type (shape/column-props s))
        base (if (and (vector? s) (= :ref (first s))) (get (shape/schemas-of registry) (last s)) s)
        kind (when (vector? base) (first base))]
    (cond (nil? v) nil
          (= :enum kind) [:cast v (keyword (sql-type s))]
          (#{"json" "jsonb"} t) [:cast (json/write v) (keyword t)]
          (= :vector kind) (let [elem (shape/non-null (last base))
                                 elem-type (cond (and (vector? elem) (= :ref (first elem))) (sql-type elem)
                                                 (and t (str/ends-with? t "[]")) (subs t 0 (- (count t) 2)))]
                             (if elem-type [:array (vec v) (keyword elem-type)] [:array (vec v)]))
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
                 used (remove generated (distinct (mapcat keys rows)))]]
       (cond-> {:insert-into (if identity? [{:overriding-value :system} (keyword table)] (keyword table))
                :values (mapv (fn [row] (into {} (for [k used] [k (if (contains? row k) (insert-value registry (get columns k) (get row k)) [:default])]))) rows)}
           (= :nothing on-conflict) (assoc :on-conflict [] :do-nothing []))))))
