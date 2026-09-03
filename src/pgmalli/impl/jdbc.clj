(ns pgmalli.impl.jdbc
  "The JDBC side: the transformer decoding driver and string values into the registry's types,
   and the row shapes next.jdbc's result set builders return."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [malli.transform :as mt]
            [pgmalli.impl.json :as json]
            [pgmalli.impl.shape :as shape]))

(defn transformer
  "Decodes JDBC and string values into the registry's types: java.sql.Timestamp and
   java.util.Date -> Instant, java.sql.Date -> LocalDate, an Instant or java.util.Date landing
   in a date or timestamp (without time zone) column is read in :zone, default the JVM's;
   JSON text in a json or jsonb column is parsed."
  ([] (transformer {}))
  ([{:keys [zone] :or {zone (java.time.ZoneId/systemDefault)}}]
   (let [instant (fn [x] (cond (instance? java.sql.Date x) (.toInstant (.atStartOfDay (.toLocalDate ^java.sql.Date x) zone))
                               (instance? java.util.Date x) (.toInstant ^java.util.Date x)
                               :else x))]
     (mt/transformer
      mt/string-transformer
      {:name :pgmalli
       :decoders {:any {:compile (fn [schema _]
                                  (when (#{"json" "jsonb"} (:pg/type (m/properties schema)))
                                    (fn [x] (if (string? x) (json/parse x) x))))}
                  ;; the bounded numbers, from text as :int and :double are
                  :pg/integer (fn [x] (if (string? x) (or (parse-long x) x) x))
                  :pg/smallint (fn [x] (if (string? x) (or (parse-long x) x) x))
                  :pg/numeric (fn [x] (if (string? x) (try (bigdec x) (catch Exception _ x)) x))
                  :time/instant instant
                  :time/local-date (fn [x] (cond (instance? java.sql.Date x) (.toLocalDate ^java.sql.Date x)
                                                 (or (instance? java.util.Date x) (instance? java.time.Instant x)) (.toLocalDate (.atZone ^java.time.Instant (instant x) zone))
                                                 :else x))
                  :time/local-date-time (fn [x] (cond (instance? java.sql.Timestamp x) (.toLocalDateTime ^java.sql.Timestamp x)
                                                      (or (instance? java.util.Date x) (instance? java.time.Instant x)) (java.time.LocalDateTime/ofInstant (instant x) zone)
                                                      :else x))}}))))

(def ^:private builders
  "next.jdbc result set builders -> how they shape a row."
  {"as-maps" {:qualified? true} "as-unqualified-maps" {} "as-lower-maps" {:qualified? true} "as-unqualified-lower-maps" {}
   "as-modified-maps" {:qualified? true} "as-unqualified-modified-maps" {}
   "as-kebab-maps" {:qualified? true :kebab? true} "as-unqualified-kebab-maps" {:kebab? true}
   "as-arrays" nil "as-unqualified-arrays" nil "as-lower-arrays" nil "as-unqualified-lower-arrays" nil})

(defn read-options
  "The reading options of a next.jdbc result set builder named by its symbol; documented on
   pgmalli.core/read-options."
  [builder]
  (let [ns (namespace builder) n (name builder)]
    (when-not (contains? builders n)
      (throw (ex-info (str "not a next.jdbc result set builder pgmalli knows: " builder) {:builder builder})))
    (when-let [o (get builders n)]
      (cond-> o (= "next.jdbc.optional" ns) (assoc :nil-columns :absent)))))

(defn- data-columns
  "The row map of a generated schema as data (columns gives the malli schema)."
  [registry name]
  (let [s (shape/schema-of registry name)]
    (when-not (vector? s) (throw (ex-info (str name " is not a generated schema") {:name name})))
    (shape/row-map s)))

(defn as-read
  "The [:map ...] of a row as a JDBC result builder returns it; the options are documented on
   pgmalli.core/as-read."
  [registry name {:keys [qualified? kebab? nil-columns time]}]
  (let [m (shape/without-gen (data-columns registry name))
        props (when (map? (second m)) (second m))
        kebab (fn [s] (cond-> s kebab? (str/replace "_" "-")))
        table (some-> (or (:pg/table props) (:pg/view props)) (str/split #"\." 2) second kebab)
        key* (fn [k] (let [s (kebab (clojure.core/name k))]
                       (cond (not qualified?) (if (keyword? k) (keyword s) s)
                             (keyword? k) (keyword table s)
                             :else (str table "/" s))))
        entry (fn [[k p s]] (let [s (shape/read-time time s)
                            absent? (and (= :absent nil-columns) (vector? s) (= :maybe (first s)))
                            p (cond-> p absent? (assoc :optional true))
                            s (if absent? (last s) s)]
                        (if (empty? p) [(key* k) s] [(key* k) p s])))]
    (into (if props [:map props] [:map]) (map entry (shape/column-entries m)))))
