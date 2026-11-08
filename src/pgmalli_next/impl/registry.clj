(ns pgmalli-next.impl.registry
  "The generated file as a malli registry: the file read (from the classpath, or a path), the generation hints
   and the insert and update schemas derived from the row schemas, and the registry they make.

   \"registry\" is two things here: the file's :registry key is a plain {name schema} map of
   schema data, and a malli registry is what registry builds from it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [malli.core :as m]
            malli.experimental.time.generator
            [malli.util :as mu]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.pgtypes :as pgtypes]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(defn- generated? [x] (and (map? x) (map? (:registry x))))

(defn generated-data
  "x when it is generated data, a map with a :registry map; else an error saying what takes it."
  [x what]
  (if (generated? x)
    x
    (error/fail! :pgmalli.error/invalid-options
                 (str what " takes generated data, such as (pgmalli-next.core/generated); given "
                      (if (map? x) "a map without :registry (a registry?)" (pr-str x)))
                 {:given (if (map? x) (vec (take 5 (keys x))) x)})))

(defn read-generated-file
  "The generated data in the text of file f (a path or a URL); an error naming f where it is none."
  [f]
  (let [x (try (edn/read-string (slurp f))
               (catch RuntimeException e
                 (error/fail! :pgmalli.error/not-generated
                              (str f " is not a generated file (" (ex-message e) "); run pgmalli-next.generate/generate!")
                              {:given (str f)} e)))]
    (if (generated? x)
      x
      (error/fail! :pgmalli.error/not-generated
                   (str f " is not a generated file (no :registry map); run pgmalli-next.generate/generate!")
                   {:given (str f)}))))

(def resource
  "Where generate!'s file is on the classpath by default (its :out under resources/)."
  "pgmalli-next.edn")

(defn read-generated
  "The generated data on the classpath (resource)."
  []
  (read-generated-file (or (io/resource resource)
                           (error/fail! :pgmalli.error/not-generated
                                        (str resource " is not on the classpath; run pgmalli-next.generate/generate!, or read the file :out names and pass its data")
                                        {:given resource}))))

(defn- insert-entry
  "A column of an INSERT: optional when the database fills it, unless with a literal default
   the column itself refuses (past its varchar(n)'s length too)."
  [registry e]
  (let [[k p s] (shape/entry-parts e)
        props (shape/column-props s)
        d (:default props)
        optional? (if (contains? props :default)
                    ;; a varchar(n)'s length is no schema's check: a longer default is refused too
                    (and (m/validate s d {:registry registry})
                         (not (and (string? d) (:pg/max-length props) (< (:pg/max-length props) (.codePointCount ^String d 0 (count d))))))
                    (or (:pg/identity props) (contains? props :pg/default) (shape/maybe? s)))]
    (when-not (shape/written-by-database? props)
      [k (cond-> p optional? (assoc :optional true)) s])))

(defn- filled
  "Column entry e of a write a BEFORE row trigger may fill: optional, and nullable."
  [e]
  (let [[k p s] (shape/entry-parts e)]
    [k (assoc p :optional true) (if (shape/maybe? s) s [:maybe s])]))

(defn derived-schema
  "The derived schema (\"insert\", \"update\") of table row schema row, of malli registry
   registry: what an INSERT may carry (identity ALWAYS and generated columns removed, columns
   with a default or NULL optional) or an UPDATE may set (those columns, every one optional, each
   holding what the column holds), closed map. Where a BEFORE row trigger runs for the write,
   every column is optional and nullable, unless strict?: the trigger may fill it; generated as
   without the trigger, which fills what it fills alone."
  [row derived registry strict?]
  (let [[_ props & entries] row
        entries (keep (case derived
                        "insert" #(insert-entry registry %)
                        "update" #(let [[k p s] (shape/entry-parts %)]
                                    (when-not (shape/written-by-database? (shape/column-props s)) [k (assoc p :optional true) s])))
                      entries)
        schema #(into [:map (assoc (if (= "insert" derived) props (select-keys props [:pg/table])) :closed true)] %)]
    (if (and (not strict?) (shape/filled-by-trigger? props (keyword derived)))
      (shape/merge-props (schema (map filled entries)) {:gen/schema (schema entries)})
      (schema entries))))

(defn- with-derived
  "The registry with the insert and update schemas of its tables: a sequence is written by its
   functions alone."
  [registry]
  (into registry (for [[k s] registry :when (and (shape/row-schema? s) (not (:pg/sequence (shape/row-props s))))
                       d ["insert" "update"]]
                   [(shape/derived-name k d) (derived-schema s d registry false)])))

(def ^:private json-value
  "What a json or jsonb column with no override to shape it generates: small JSON values."
  [:or :string :int :boolean [:map-of {:max 3} :string [:or :string :int]] [:vector {:max 3} [:or :string :int]]])

(defn- cannot-make
  "A generator of no values (malli leaves out an optional key of it, and a [:maybe] generates
   NULL), which throws, naming where, when a value is wanted."
  [where type-name]
  (with-meta (gen/sized (fn [_] (error/fail! :pgmalli.error/cannot-generate
                                             (str where ": pgmalli cannot make a value of type " type-name "; give one with :overrides")
                                             {:given where :type type-name})))
    {:malli.generator/never-gen true}))

(defn- gen-hints
  "Generation hints (:gen/min, :gen/max, :gen/schema, :gen/elements, :gen/fmap, :gen/gen) for a column
   schema: key and identity integers are small and positive, strings short, times within 2025
   (fixed, so a seed gives the same rows in every run), an unshaped json column a JSON value,
   an opaque type one of a few literals, a bit string digits. A NOT NULL column of a type
   pgmalli cannot make a value of generates nothing (where names it in the error). Other
   columns keep the schema's own bounds."
  [s key? where]
  (let [[t p] (if (and (vector? s) (map? (second s))) [(first s) (second s)] [(if (vector? s) (first s) s) {}])
        hints (case t
                (:int :pg/integer :pg/smallint) (when key?
                       (let [lo (max 1 (:min p Long/MIN_VALUE)) hi (min 100000 (:max p Long/MAX_VALUE))]
                         (when (<= lo hi) {:gen/min lo :gen/max hi})))
                :string (let [n (:pg/max-length p)
                              typmod (:typmod (types/entry (shape/type-name p)))]
                          (cond (= :bits typmod) {:gen/schema [:vector {:min (or n 1) :max (or n 8)} [:enum "0" "1"]] :gen/fmap #(apply str %)}
                                (= :max-bits typmod) {:gen/schema [:vector {:min 1 :max (min 8 (or n 8))} [:enum "0" "1"]] :gen/fmap #(apply str %)}
                                ;; within a varchar(n)'s n, which the schema does not check: the database refuses a longer value
                                (or (nil? (:max p)) (> (:max p) 24)) {:gen/max (max (:min p 0) (min 24 (or n 24)))}))
                ;; a numeric(p, s)'s within it, of its scale: the database refuses a longer one
                decimal? (when-let [pr (:pg/precision p)]
                           (let [s (:pg/scale p 0)
                                 lim (- (.pow 10M (int (- pr s))) (.movePointLeft 1M (int s)))]
                             {:gen/schema [:double {:min (- (double lim)) :max (double lim)}]
                              :gen/fmap #(let [d (.setScale (bigdec %) (int s) java.math.RoundingMode/DOWN)] (if (pos? (.compareTo (.abs d) lim)) lim d))}))
                ;; a real: a Float within the range a value of one is held to
                :double (when (= "real" (:name (types/entry (shape/type-name p))))
                          {:gen/schema [:double {:min -1e6 :max 1e6}] :gen/fmap float})
                :time/instant {:gen/min (java.time.Instant/parse "2025-01-01T00:00:00Z") :gen/max (java.time.Instant/parse "2025-12-31T23:59:59Z")}
                :time/local-date-time {:gen/min (java.time.LocalDateTime/parse "2025-01-01T00:00:00") :gen/max (java.time.LocalDateTime/parse "2025-12-31T23:59:59")}
                :time/local-date {:gen/min (java.time.LocalDate/parse "2025-01-01") :gen/max (java.time.LocalDate/parse "2025-12-31")}
                ;; PostgreSQL takes offsets up to 15:59:59, java.time up to 18:00
                :time/offset-time {:gen/schema [:tuple :time/local-time [:enum -5 0 9]]
                                   :gen/fmap (fn [[t h]] (java.time.OffsetTime/of t (java.time.ZoneOffset/ofHours h)))}
                :sequential (when (types/json? (:pg/type p)) {:gen/schema [:vector {:max 3} json-value]})
                ;; an array of a type pgmalli cannot write a value of: empty, which every array type takes
                :vector (when (= :any (last s)) {:gen/elements [[]]})
                (:any :some) (cond (types/json? (:pg/type p)) {:gen/schema json-value}
                                   (:literals (types/entry (shape/type-name p))) {:gen/elements (:literals (types/entry (shape/type-name p)))}
                                   ;; NULL where the column takes it; a NOT NULL one has no value to generate
                                   (= :any t) (when (:pg/type p) {:gen/elements [nil]})
                                   (:pg/type p) {:gen/gen (cannot-make where (:pg/type p))})
                nil)]
    (cond
      (and hints (not-any? #(contains? p %) [:gen/min :gen/max :gen/gen :gen/schema :gen/elements]))
      (cond (not (vector? s)) [t hints] (map? (second s)) (assoc s 1 (merge p hints)) :else (into [t hints] (rest s)))
      ;; an array generates from its element's, of the array's element type and length
      ;; (varchar(n)[]'s n, bit(n)[]'s), which the array's properties hold
      (and (= :vector t) (vector? s))
      (let [e (last s)
            told (shape/merge-props e (cond-> (select-keys p [:pg/max-length :pg/precision :pg/scale])
                                        (and (not (:pg/type (shape/props e))) (types/element (:pg/type p))) (assoc :pg/type (types/element (:pg/type p)))))
            hinted (select-keys (shape/props (gen-hints told false where)) [:gen/min :gen/max :gen/gen :gen/schema :gen/elements :gen/fmap])]
        (assoc s (dec (count s)) (cond-> (gen-hints e false where) (seq hinted) (shape/merge-props hinted))))
      :else s)))

(defn- with-gen-hints
  "The row with generation hints on its columns."
  [row]
  (let [[_ props & entries] row
        key-cols (set (map shape/ident-key (concat (:pg/primary-key props) (mapcat :columns (:pg/unique props)) (mapcat :columns (:pg/foreign-keys props)))))
        hinted (fn [e] (let [[k p s] (shape/entry-parts e)
                             key? (or (key-cols k) (:pg/identity (shape/column-props s)))
                             where (str (:pg/table props) "." (name k))
                             s (if (shape/maybe? s) [:maybe (gen-hints (last s) key? where)] (gen-hints s key? where))]
                         (if (empty? p) [k s] [k p s])))
        m (into [:map props] (map hinted) entries)]
    m))

(def base
  "The schemas a generated schema or an override names besides the generated ones."
  (merge (m/default-schemas) (mu/schemas) pgtypes/schemas))

(defn other-schemas-enum
  "The key among schemas (the file's :registry) of the enum type t names, a :pg/type of another
   schema (\"s\".\"Mood\", an array's element's for \"s.e[]\"), nil for none: generation reads each
   schema alone, so it types a column of one :any."
  [schemas t]
  (when-let [[s n] (types/type-parts (or (types/element t) t))]
    (when s
      (let [k (shape/schema-key (types/unquoted s) (types/unquoted n))]
        ;; a table's, a view's or a composite type's row is a PGobject
        (when (some-> (get schemas k) shape/column-entries empty?) k)))))

(defn- with-other-schemas-types
  "The generated schemas with each :any or :some column whose :pg/type names an enum type of another
   schema of the file (other-schemas-enum) made a [:ref] to it."
  [schemas]
  (let [own #(when-not (types/element %) (other-schemas-enum schemas %))
        typed (fn [f]
                (let [p (when (vector? f) (second f))
                      t (:pg/type p)]
                  ;; :some, a NOT NULL column's
                  (cond (and (#{:any :some} (first f)) (= 2 (count f)) (own t)) [:ref p (own t)]
                        (and (= :vector (first f)) (= 3 (count f)) (#{:any :some} (last f)) (other-schemas-enum schemas t))
                        [:vector p [:ref (other-schemas-enum schemas t)]]
                        :else f)))]
    (update-vals schemas #(if (shape/row-schema? %) (walk/postwalk (fn [f] (if (and (vector? f) (map? (second f))) (typed f) f)) %) %))))

(defn- sequence-rows
  "The row schema of each of generated data d's :sequences, the columns every sequence has
   (PostgreSQL 16 to 18 alike), which a query reads by its name."
  [d]
  (into {} (for [[schema ns] (:sequences d), n ns]
             [(shape/schema-key schema n)
              [:map {:pg/table (str schema "." n) :pg/sequence true}
               [:last_value [:int {:pg/type "bigint"}]] [:log_cnt [:int {:pg/type "bigint"}]] [:is_called [:boolean {:pg/type "boolean"}]]]])))

(defn registry
  "The registry pgmalli-next.core/registry documents: of generated data, or of the classpath's
   (read-generated) with none."
  ([] (registry (read-generated)))
  ([data]
   (let [d (generated-data data "registry")
         generated (into {} (map (fn [[k s]] [k (if (shape/row-schema? s) (with-gen-hints s) (gen-hints s false (str k)))]))
                         (with-other-schemas-types (merge (sequence-rows d) (:registry d))))]
     (merge base
            (apply dissoc (merge (with-derived (merge base generated)) generated) (keys base))
            (into {} (for [s (:schemas d)]
                      [(shape/routines-key s)
                        [:any {:pg/functions (get-in d [:functions s]) :pg/operators (get-in d [:operators s]) :pg/database-version (:database-version d)}]]))))))
