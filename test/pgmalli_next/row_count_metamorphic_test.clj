(ns pgmalli-next.row-count-metamorphic-test
  "The row count of each corpus query it tells holds under rewrites that change no count, or
   change it as SQL defines: no PostgreSQL verdict, the properties alone. JVM only (the oracle
   schema's registry is generated from PostgreSQL)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.walk :as walk]
            [pgmalli-next.honeysql :as h]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.types :as types]
            [pgmalli-next.oracle :as oracle]
            [pgmalli-next.test-db :refer [*db* exec-sql! with-postgres]]))

(use-fixtures :once (fn [t] (with-postgres #(do (when *db* (exec-sql! oracle/schema-sql)) (t)))))

(defn- plain-functions
  "pg_catalog's functions of one argument that return one value per row (no set, aggregate or
   window function), of the version registry was generated on."
  [registry]
  (vec (sort (for [[n os] (:functions @types/catalog)
                   :when (and (seq os) (every? #(and (= 1 (count (:args %))) (not (:set %)) (nil? (:kind %))) os)
                              (seq (query/reached (query/reading registry) {:schema "public"} [:functions n [] nil])))]
               n))))

(defn- select? [q] (and (map? q) (or (:select q) (:select-distinct q)) (not (some #(contains? q %) [:insert-into :update :delete-from]))))

(defn- star? [x] (and (keyword? x) (re-find #"(^|\.)\*$" (name x))))

(defn- wrapped-items
  "q with each select item an expression of it no set comes of: f(item), f of plain-functions."
  [fs q i]
  (when (vector? (:select q))
    (let [
          f #(keyword (nth fs (mod (+ i %) (count fs))))]
      (assoc q :select (vec (map-indexed (fn [j x]
                                           (cond (star? x) x
                                                 (keyword? x) [[(f j) x] (keyword (last (re-seq #"[^.]+" (name x))))]
                                                 (and (vector? x) (= 2 (count x))) [[(f j) (first x)] (second x)]
                                                 :else x))
                                         (:select q)))))))

(defn- nested-and [q]
  (when-let [w (:where q)]
    (let [cs (if (and (vector? w) (= :and (first w))) (rest w) [w])]
      (assoc q :where [:and [:and (last cs)] (into [:and] (reverse (butlast cs)))]))))

(defn- three-operands
  "q with each AND and OR of two operands of three, and an item of an AND and an OR of three added
   to its select list."
  [q]
  (let [q (walk/prewalk #(if (and (vector? %) (#{:and :or} (first %)) (= 3 (count %))) (conj % (second %)) %) q)]
    (cond-> q (vector? (:select q)) (update :select conj [[:or [:and [:= 1 1] [:= 2 2] [:= 3 3]] [:= 1 2] [:= 2 3]] :three_x]))))

(defn- array-cast-arguments
  "q with each named argument cast to an array type ([:cast :?x [:raw \"text[]\"]]), which
   HoneySQL writes only so."
  [q]
  (let [q' (walk/postwalk #(if (and (keyword? %) (= \? (first (name %)))) [:cast % [:raw "text[]"]] %) q)]
    (when (not= q q') q')))

(defn- inline-limit [q] (when (integer? (:limit q)) (assoc q :limit [:inline (:limit q)])))

(defn- rewrites
  "[name rewritten expected] of query q of row count before: expected a fn of the rewritten's."
  [fs q before i]
  (let [same #(= before %)
        same-max #(= (:max before) (:max %))]
    (remove (comp nil? second)
            [[:wrapped-items (wrapped-items fs q i) same]
             [:three-operands (three-operands q) same]
             [:array-cast-arguments (array-cast-arguments q) same]
             [:nested-and (nested-and q) same]
             [:inline-limit (inline-limit q) same]
             [:limit (when-not (contains? q :limit) (assoc q :limit 2)) #(= (min 2 (:max before)) (:max %))]
             [:left-join-by-key (when (and (:from q) (not (contains? q :with))) (update q :left-join (fnil into []) [[:lookups :lk_x] [:= :lk_x.lookup_id 1]])) same-max]
             [:left-join-derived-by-key (when (and (:from q) (not (contains? q :with)))
                                          (update q :left-join (fnil into []) [[{:select [:lookup_id] :from [:lookups] :group-by [:lookup_id]} :dk_x] [:= :dk_x.lookup_id 1]]))
              same-max]
             [:subquery {:select [:*] :from [[q :w_x]]} same-max]
             [:cte (when-not (contains? q :with) {:with [[:w_x q]] :select [:*] :from [:w_x]}) same-max]])))

(deftest a-row-count-holds-under-rewrites-that-keep-it
  (when *db*
    (let [registry (oracle/registry *db*)
          fs (plain-functions registry)
          rows #(:rows (h/analyze registry %1 %2))
          failures (for [[i [id q opts]] (map-indexed vector oracle/corpus)
                         :when (select? q)
                         :let [before (rows q opts)]
                         :when before
                         [rewrite q' expected?] (rewrites fs q before i)
                         :let [after (rows q' opts)]
                         :when (not (and after (expected? after)))]
                     {:id id :rewrite rewrite :before before :after after :rewritten q'})]
      (is (empty? failures) (with-out-str (doseq [f failures] (prn f)))))))
