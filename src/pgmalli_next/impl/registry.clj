(ns pgmalli-next.impl.registry
  "Generated files as malli registries: the file read (from the classpath, or a path), the generation hints
   and the insert and update schemas derived from the row schemas, and the registry they make.

   \"registry\" is two things here: the file's :registry key is a plain {name schema} map of
   schema data, and a malli registry is what registry builds from it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test.check.generators :as gen]
            [clojure.walk :as walk]
            [malli.core :as m]
            [malli.experimental.time :as time]
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
   the column itself refuses."
  [registry e]
  (let [[k p s] (shape/entry-parts e)
        props (shape/column-props s)
        optional? (if (contains? props :default)
                    (m/validate s (:default props) {:registry registry})
                    (or (:pg/identity props) (contains? props :pg/default) (shape/maybe? s)))]
    (when-not (shape/written-by-database? props)
      [k (cond-> p optional? (assoc :optional true)) s])))

(defn- insert-schema
  "What an INSERT may carry: identity ALWAYS and generated columns removed, columns with a
   default or NULL optional, closed map."
  [row registry]
  (let [[_ props & entries] row]
    (into [:map (assoc props :closed true)] (keep #(insert-entry registry %) entries))))

(defn- update-schema
  "What an UPDATE may set: the columns an INSERT may carry, every one optional (an update sends
   the columns it changes), each holding what the column holds (a NOT NULL column cannot be set
   to NULL), closed map."
  [row]
  (let [[_ props & entries] row]
    (into [:map (assoc (select-keys props [:pg/table]) :closed true)]
          (for [e entries :let [[k p s] (shape/entry-parts e) cp (shape/column-props s)]
                :when (not (shape/written-by-database? cp))]
            [k (assoc p :optional true) s]))))

(defn- with-derived [registry]
  (into registry (for [[k s] registry :when (shape/row-schema? s)
                       e [[(shape/derived-name k "insert") (insert-schema s registry)] [(shape/derived-name k "update") (update-schema s)]]]
                   e)))

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
      ;; an array generates from its element's
      (and (= :vector t) (vector? s)) (assoc s (dec (count s)) (gen-hints (last s) false where))
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
  (merge (m/default-schemas) (mu/schemas) (time/schemas) pgtypes/schemas))

(defn- with-other-schemas-types
  "The generated schemas with each :any column whose :pg/type names an enum type of another
   schema of the file made a [:ref] to it: generation reads each schema alone, so it types such a
   column :any."
  [schemas]
  (let [own (fn [t] (when-let [[_ s n] (and (string? t) (re-matches #"([a-z_][a-z0-9_$]*)\.([a-z_][a-z0-9_$]*)" t))]
                      (let [k (shape/schema-key s n)]
                        ;; a table's, a view's or a composite type's row is a PGobject
                        (when (some-> (get schemas k) shape/column-entries empty?) k))))
        typed (fn [f]
                (let [p (when (vector? f) (second f))
                      t (:pg/type p)]
                  (cond (and (= :any (first f)) (= 2 (count f)) (own t)) [:ref p (own t)]
                        (and (= :vector (first f)) (= 3 (count f)) (= :any (last f)) (own (types/element t)))
                        [:vector p [:ref (own (types/element t))]]
                        :else f)))]
    (update-vals schemas #(if (shape/row-schema? %) (walk/postwalk (fn [f] (if (and (vector? f) (map? (second f))) (typed f) f)) %) %))))

(defn- with-partitions
  "Row schemas with each :pg/partitions' partition a row schema of its table's (its :pg/table
   its own): a query reads or writes a partition by name."
  [schemas]
  (into schemas (for [[k s] schemas
                      :when (and (keyword? k) (shape/row-schema? s))
                      n (:pg/partitions (shape/row-props s))]
                  [(keyword (namespace k) n)
                   (walk/postwalk #(if (and (map? %) (:pg/partitions %))
                                     (-> % (dissoc :pg/partitions) (assoc :pg/table (str (subs (namespace k) 3) "." n)))
                                     %)
                                  s)])))

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
                         (with-partitions (with-other-schemas-types (merge (:registry d) (sequence-rows d)))))]
     (merge base
            (apply dissoc (merge (with-derived (merge base generated)) generated) (keys base))
            (into {} (for [s (:schemas d)]
                      [(shape/routines-key s)
                        [:any {:pg/functions (get-in d [:functions s]) :pg/operators (get-in d [:operators s]) :pg/database-version (:database-version d)}]]))))))
