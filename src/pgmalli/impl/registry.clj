(ns pgmalli.impl.registry
  "Generated files as malli registries: the file read from the classpath, the generation hints
   and the insert and update schemas derived from the row schemas, and the registry they make."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.experimental.time :as time]
            malli.experimental.time.generator
            [malli.util :as mu]
            [pgmalli.impl.pgtypes :as pgtypes]
            [pgmalli.impl.render :as render]
            [pgmalli.impl.shape :as shape]))

(defn read-generated
  "The generated data for a schema name, from the classpath (pgmalli/<schema>.edn)."
  [schema]
  (let [path (str "pgmalli/" schema ".edn")]
    (edn/read-string (slurp (or (io/resource path)
                                (throw (ex-info (str path " is not on the classpath; run generate! and add its :out-dir to :paths")
                                                {:schema schema})))))))

(defn- insert-entry [e]
  (let [[k p s] (shape/entry-parts e)
        props (shape/column-props s)
        nullable? (and (vector? s) (= :maybe (first s)))]
    (when-not (or (:pg/generated props) (= :always (:pg/identity props)))
      [k (cond-> p (or (:pg/identity props) (contains? props :pg/default) nullable?) (assoc :optional true)) s])))

(defn- omitted-values
  "What the database stores for a column left out of an INSERT: {column literal} for literal
   defaults; columns whose default is an expression are in :unknown."
  [entries]
  (reduce (fn [acc e]
            (let [[k _ s] (shape/entry-parts e) props (shape/column-props s)]
              (cond (contains? props :default) (assoc-in acc [:literal k] (:default props))
                    (contains? props :pg/default) (update acc :unknown conj k)
                    :else acc)))
          {:literal {} :unknown #{}}
          entries))

(defn- as-insert-sees-it
  "A table constraint with omitted columns standing for what the database stores in them:
   fragment keys are optional unless NULL (or the literal default) would violate them, a :multi
   on a column with a literal default takes that value's branch when the column is absent, and
   a :pg/check evaluates the literal defaults."
  [schema {:keys [literal unknown]} registry]
  (letfn [(entry [e] (let [[k p s] (shape/entry-parts e)]
                       (if (or (unknown k) (m/validate s (get literal k) {:registry registry}))
                         [k (assoc p :optional true) (go s)]
                         [k p (go s)])))
          (go [f]
            (if-not (vector? f)
              f
              (case (first f)
                :pg/check (let [[_ p e] (if (map? (second f)) f [:pg/check {} (second f)])]
                            [:pg/check (assoc p :pg/defaults literal) e])
                :map (if (map? (second f)) f (into [:map] (map entry (rest f))))
                :multi (let [f (mapv go f)
                             absent (some (fn [[v s]] (when (= v (get literal (:dispatch (second f)))) s)) (drop 2 f))]
                         (cond-> f absent (conj [nil absent])))
                (mapv go f))))]
    (go schema)))

(defn- insert-schema
  "What an INSERT may carry: identity ALWAYS and generated columns removed, columns with a
   default or NULL optional, closed map; then the table constraints, see as-insert-sees-it."
  [row registry]
  (let [[_ props & entries] (shape/row-map row)
        m (into [:map (assoc props :closed true)] (keep insert-entry entries))
        omitted (omitted-values entries)]
    (if (= :and (first row))
      (into [:and m] (map #(as-insert-sees-it % omitted registry) (drop 2 row)))
      m)))

(defn- update-schema
  "What an UPDATE may set: the columns an INSERT may carry, every one optional (an update sends
   the columns it changes), each holding what the column holds (a NOT NULL column cannot be set
   to NULL), closed map. The table constraints are left out: they hold on the updated row,
   which the columns sent do not show."
  [row]
  (let [[_ props & entries] (shape/row-map row)]
    (into [:map (assoc (select-keys props [:pg/table]) :closed true)]
          (for [e entries :let [[k p s] (shape/entry-parts e) cp (shape/column-props s)]
                :when (not (or (:pg/generated cp) (= :always (:pg/identity cp))))]
            [k (assoc p :optional true) s]))))

(defn- with-inserts [registry]
  (into registry (for [[k s] registry :when (shape/row-schema? s)
                       e [[(shape/insert-name k) (insert-schema s registry)] [(shape/update-name k) (update-schema s)]]]
                   e)))

(def ^:private json-value
  "What a json or jsonb column with no CHECK to shape it generates: small JSON values."
  [:or :string :int :boolean [:map-of {:max 3} :string [:or :string :int]] [:vector {:max 3} [:or :string :int]]])

(def ^:private opaque-literals
  "Literals the database reads for the types rendered :any: what a dataset column of such a
   type generates (any of them, as text; the driver's own objects come back on read)."
  {"inet" ["10.0.0.1" "192.168.1.0/24" "::1"] "cidr" ["10.0.0.0/8" "192.168.1.0/24" "2001:db8::/32"]
   "macaddr" ["08:00:2b:01:02:03" "08-00-2b-01-02-04"] "macaddr8" ["08:00:2b:01:02:03:04:05"]
   "money" ["12.34" "0.00" "-5.50"] "xml" ["<a/>" "<a b=\"1\">x</a>"] "tsvector" ["'a' 'b'" "'fat':2 'rat':3"] "tsquery" ["'a' & 'b'" "'fat' | 'rat'"]
   "jsonpath" ["$.a" "$[*] ? (@ > 1)"] "pg_lsn" ["0/16B3748" "1/0"] "tid" ["(0,1)" "(1,2)"] "pg_snapshot" ["10:20:" "10:20:10,14,15"] "txid_snapshot" ["10:20:"]
   "xid" ["1" "1234"] "xid8" ["1" "1234"] "cid" ["0" "3"]
   "point" ["(1,2)" "(0,0)" "(-1.5,2.5)"] "line" ["{1,-1,0}" "{0,1,-2}"] "lseg" ["[(0,0),(1,1)]"] "box" ["((0,0),(1,1))" "((1,1),(2,3))"]
   "path" ["[(0,0),(1,1),(2,0)]" "((0,0),(1,1),(2,0))"] "polygon" ["((0,0),(1,0),(1,1))"] "circle" ["<(0,0),1>" "<(1,1),2.5>"]
   ;; no empty range: a WITHOUT OVERLAPS key refuses one
   "int4range" ["[1,10)" "[20,30)" "(,5]"] "int8range" ["[1,10)" "[20,30)"] "numrange" ["[1.5,2.5]" "[3,4)"]
   "tsrange" ["[2020-01-01 00:00,2020-01-02 00:00)" "[2021-01-01 00:00,2021-06-01 00:00)"] "tstzrange" ["[2020-01-01 00:00+00,2020-01-02 00:00+00)" "[2021-01-01 00:00+00,2021-06-01 00:00+00)"]
   "daterange" ["[2020-01-01,2020-02-01)" "[2021-01-01,2021-06-01)"]
   "int4multirange" ["{[1,3),[5,7)}" "{[10,20)}"] "int8multirange" ["{[1,3)}" "{[10,20)}"] "nummultirange" ["{[1.5,2.5]}" "{[3,4)}"]
   "tsmultirange" ["{[2020-01-01 00:00,2020-01-02 00:00)}"] "tstzmultirange" ["{[2020-01-01 00:00+00,2020-01-02 00:00+00)}"]
   "datemultirange" ["{[2020-01-01,2020-02-01)}" "{[2021-01-01,2021-06-01)}"]
   "regclass" ["pg_class" "pg_type"] "regtype" ["integer" "text"] "regrole" ["postgres"] "regproc" ["now"] "regprocedure" ["now()"]
   "regoper" ["+"] "regoperator" ["+(integer,integer)"] "regnamespace" ["public"] "regconfig" ["english"] "regdictionary" ["simple"] "regcollation" ["\"C\""]})

(defn- gen-hints
  "Generation hints (:gen/min, :gen/max, :gen/schema, :gen/elements, :gen/fmap) for a column
   schema: key and identity integers are small and positive, strings short, times within the
   last year, an unshaped json column a JSON value, an opaque type one of a few literals, a bit
   string digits. Other columns keep the schema's own bounds."
  [s key?]
  (let [[t p] (if (and (vector? s) (map? (second s))) [(first s) (second s)] [(if (vector? s) (first s) s) {}])
        now (java.time.Instant/now)
        ;; a numeric bounded by CHECKs ([:and decimal? [:> 1] [:< 1000]]) generates within them: the
        ;; bounds go to the head as :gen/min and :gen/max, on a :pg/numeric when the head is bare
        numeric-bounds (when (= :and t)
                         (let [parts (if (map? (second s)) (drop 2 s) (rest s))
                               head (first parts)
                               lo (some (fn [[op v]] (when (and (#{:> :>=} op) (number? v)) v)) (rest parts))
                               hi (some (fn [[op v]] (when (and (#{:< :<=} op) (number? v)) v)) (rest parts))]
                           (when (and (or lo hi) (or (= 'decimal? head) (and (vector? head) (= :pg/numeric (first head)))))
                             [head (cond-> {} lo (assoc :gen/min (bigdec lo)) hi (assoc :gen/max (bigdec hi)))])))
        hints (case t
                (:int :pg/integer :pg/smallint) (when key?
                       (let [lo (max 1 (:min p Long/MIN_VALUE)) hi (min 100000 (:max p Long/MAX_VALUE))]
                         (when (<= lo hi) {:gen/min lo :gen/max hi})))
                :string (if (#{"bit" "bit varying" "varbit"} (shape/type-name p))
                          {:gen/schema [:vector {:min (:min p 1) :max (:max p (:min p 8))} [:enum "0" "1"]] :gen/fmap #(apply str %)}
                          (when (or (nil? (:max p)) (> (:max p) 24)) {:gen/max (max (:min p 0) 24)}))
                :time/instant {:gen/min (.minus now (java.time.Duration/ofDays 365)) :gen/max now}
                :time/local-date-time (let [n (java.time.LocalDateTime/now)] {:gen/min (.minusDays n 365) :gen/max n})
                :time/local-date (let [n (java.time.LocalDate/now)] {:gen/min (.minusDays n 365) :gen/max n})
                ;; a type pgmalli cannot write a value of generates NULL; a NOT NULL column of it
                ;; leaves its table short, with the reason, rather than carrying a value the
                ;; database refuses
                ;; an array of a type pgmalli cannot write a value of: empty, which every array type takes
                :vector (when (= :any (last s)) {:gen/elements [[]]})
                (:any :some) (cond (#{"json" "jsonb"} (:pg/type p)) {:gen/schema json-value}
                                   (opaque-literals (shape/type-name p)) {:gen/elements (opaque-literals (shape/type-name p))}
                                   (:pg/type p) {:gen/elements [nil]})
                nil)]
    (cond
      numeric-bounds
      (let [[head bounds] numeric-bounds
            head* (if (= 'decimal? head)
                    ['decimal? {:gen/schema [:pg/numeric (merge {:precision 18 :scale 4} bounds)]}]
                    (assoc head 1 (merge (second head) bounds)))
            i (if (map? (second s)) 2 1)]
        (assoc s i head*))
      (and hints (not-any? #(contains? p %) [:gen/min :gen/max :gen/gen :gen/schema :gen/elements]))
      (if (map? (second s)) (assoc s 1 (merge p hints)) (into [t hints] (rest s)))
      :else s)))

(defn- with-gen-hints [row]
  (let [[_ props & entries] (shape/row-map row)
        key-cols (set (map render/ident-key (concat (:pg/primary-key props) (mapcat :columns (:pg/unique props)) (mapcat :columns (:pg/foreign-keys props)))))
        m (into [:map props]
                (map (fn [e] (let [[k p s] (shape/entry-parts e)
                                   key? (or (key-cols k) (:pg/identity (shape/column-props s)))
                                   s (if (and (vector? s) (= :maybe (first s))) [:maybe (gen-hints (last s) key?)] (gen-hints s key?))]
                               (if (empty? p) [k s] [k p s])))
                     entries))]
    (if (= :and (first row)) (into [:and m] (drop 2 row)) m)))

(def regex-generation?
  "Whether strings can be generated from a regex here: test.chuck's generator, which runs on
   the JVM (babashka falls back to drawing strings and filtering them)."
  (delay (boolean (try (requiring-resolve 'com.gfredericks.test.chuck.generators/string-from-regex) (catch Exception _ nil)))))

(defn- generate-from-part
  "[:and ...] schemas with a part a generator should start from: a regex (test.chuck makes
   strings that match it, where filtering random strings would not) or a reference (an
   override, a domain), as :gen/schema on the :and."
  [f]
  (if (and (vector? f) (= :and (first f)) (not (and (map? (second f)) (some #{:gen/schema :gen/gen :gen/fmap :gen/elements} (keys (second f))))))
    (let [props (when (map? (second f)) (second f))
          parts (if props (drop 2 f) (rest f))
          from (or (when @regex-generation? (some #(when (and (vector? %) (= :re (first %))) %) parts))
                   (some #(when (and (vector? %) (= :ref (first %))) %) parts))]
      ;; generated from that part first, the other parts filtering: malli's :and generator
      (if from (into [:and (assoc (or props {}) :gen/schema (into [:and from] (remove #{from} parts)))] parts) f))
    f))

(defn registry
  "The registry pgmalli.core/registry documents."
  [& schemas]
  (let [base (merge (m/default-schemas) (mu/schemas) (time/schemas)
                    {:pg/check pgtypes/check-schema :pg/check-value pgtypes/check-value-schema :pg/bytes pgtypes/bytes-schema
                     :pg/smallint pgtypes/smallint-schema :pg/integer pgtypes/integer-schema :pg/numeric pgtypes/numeric-schema})
        generated (apply merge (map #(:registry (if (map? %) % (read-generated %))) schemas))]
    (doseq [[k s] generated :when (shape/row-schema? s)
            :let [{:keys [pg/table pg/unique pg/foreign-keys]} (second (shape/row-map s))]
            :when (or (not (str/includes? table ".")) (some vector? unique) (some vector? foreign-keys))]
      (throw (ex-info (str k " was generated by an older pgmalli; run generate! again") {:name k})))
    (let [generated (into {} (map (fn [[k s]] [k (walk/postwalk generate-from-part (if (shape/row-schema? s) (with-gen-hints s) s))])) generated)]
      (merge base (with-inserts (merge base generated)) generated))))

(defn columns
  "The [:map ...] of a row or insert schema, without the table-level constraints."
  [registry name]
  (let [s (m/deref (m/schema name {:registry registry}))]
    (if (= :and (m/type s)) (first (m/children s)) s)))
