(ns pgmalli.impl.jdbc
  "The JDBC side: the transformer decoding driver and string values into the registry's types,
   and the row shapes next.jdbc's result set builders return."
  (:require [malli.core :as m]
            [malli.transform :as mt]
            [pgmalli.impl.json :as json]))

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
  "The reading options (see as-read) of a next.jdbc result set builder named by its symbol:
   next.jdbc/as-unqualified-lower-maps, next.jdbc.optional/as-kebab-maps (NULL columns absent)
   and the like. nil for a builder that builds no map (as-arrays); an error for one pgmalli
   does not know."
  [builder]
  (let [ns (namespace builder) n (name builder)]
    (when-not (contains? builders n)
      (throw (ex-info (str "not a next.jdbc result set builder pgmalli knows: " builder) {:builder builder})))
    (when-let [o (get builders n)]
      (cond-> o (= "next.jdbc.optional" ns) (assoc :nil-columns :absent)))))
