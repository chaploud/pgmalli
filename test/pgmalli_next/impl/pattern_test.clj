(ns pgmalli-next.impl.pattern-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [pgmalli-next.impl.pattern :as p]
            [pgmalli-next.impl.types :as types]
            [pgmalli-next.small-stack :refer [on-small-stack]]))

(def ^:private schema
  {:name "public"
   :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}}
   :tables {"t" {:columns [{:name "c" :position 1 :data_type "integer" :is_nullable true}
                           {:name "m" :position 2 :data_type "mood" :type_schema "public" :is_nullable false :default_value "'happy'::mood"}
                           {:name "v" :position 3 :data_type "character varying" :is_nullable true :max_length 40}]
                 :constraints {}}}})

(deftest facts-from-types-and-columns
  (is (= [{:fact :enum-type :schema "public" :type-name "mood" :values ["happy" "sad"]}
          {:fact :column :schema "public" :table "t" :column "c" :type "integer" :position 1 :nullable? true}
          {:fact :column :schema "public" :table "t" :column "m" :type "mood" :position 2 :nullable? false :default "happy" :default-sql "'happy'::mood"}
          {:fact :enum :schema "public" :table "t" :column "m" :type-name "mood" :values ["happy" "sad"]}
          {:fact :column :schema "public" :table "t" :column "v" :type "character varying" :position 3 :nullable? true}
          {:fact :max-length :schema "public" :table "t" :column "v" :max 40}]
         (p/facts schema))))

(deftest ir-sql-reads-the-length-of-the-types-with-one
  (let [max-length (second (re-find #"(?s)'max_length', CASE(.*?)END" (slurp (io/resource "pgmalli-next/ir.sql"))))]
    (is (= (set (for [[t e] types/types :when (#{:max-length :bits :max-bits} (:typmod e))] t))
           (set (map #(:name (types/entry (second %))) (re-seq #"'(\w+)'::regtype" max-length)))))))

(deftest a-domains-column-is-of-its-base-type-with-the-domain-named
  (let [fs (p/facts {:name "public"
                     :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}}
                     :tables {"t" {:columns [{:name "mail" :position 1 :data_type "character varying" :domain "work_email" :type_schema "pg_catalog"
                                              :is_nullable false :default_value "'a@b'::text" :max_length 80}
                                             {:name "feel" :position 2 :data_type "mood" :domain "feeling" :type_schema "public" :is_nullable true}]
                                   :constraints {}}}})
        col #(some (fn [f] (when (and (= :column (:fact f)) (= % (:column f))) f)) fs)]
    (is (= {:type "character varying" :domain "work_email" :nullable? false :default "a@b"} (select-keys (col "mail") [:type :domain :nullable? :default])))
    (is (= [:max-length 80] ((juxt :fact :max) (some #(when (and (= "mail" (:column %)) (= :max-length (:fact %))) %) fs))))
    (is (= "mood" (:type-name (some #(when (and (= "feel" (:column %)) (= :enum (:fact %))) %) fs))) "a domain over an enum is the enum")))

(deftest an-enum-of-another-schema-is-not-this-schemas-enum-of-its-name
  (let [fs (p/facts {:name "public" :types {"mood" {:kind "ENUM" :enum_values ["happy" "sad"]}}
                     :tables {"t" {:columns [{:name "j" :position 1 :data_type "other.mood" :type_schema "other" :is_nullable true}
                                             {:name "m" :position 2 :data_type "mood" :type_schema "public" :is_nullable true}]
                                   :constraints {}}}})]
    (is (= [["j" :column] ["j" :unknown-type] ["m" :column] ["m" :enum]] (map (juxt :column :fact) (filter :column fs))))))

(deftest unknown-types-are-kept-and-a-default-as-its-sql
  (let [fs (p/facts {:name "public" :types {}
                     :tables {"t" {:columns [{:name "e" :position 1 :data_type "email" :type_schema "public" :is_nullable false :default_value "CASE WHEN"}
                                             {:name "x" :position 2 :data_type "text" :type_schema "other" :is_nullable true}]
                                   :constraints {}}}})]
    (is (= [:column :unknown-type :column :unknown-type] (map :fact fs)))
    (is (= "CASE WHEN" (:default-sql (first fs))) "an unreadable default is kept as its SQL")))

(deftest an-extension-type-is-known-in-its-schema-a-pg-catalog-name-only-in-pg-catalog
  (let [fs (p/facts {:name "public" :types {}
                     :tables {"t" {:columns [{:name "c" :position 1 :data_type "citext" :type_schema "public" :type_extension "citext" :is_nullable true}
                                             {:name "l" :position 2 :data_type "ext.ltree" :type_schema "ext" :type_extension "ltree" :is_nullable true}
                                             {:name "p" :position 3 :data_type "point" :type_schema "public" :is_nullable true}
                                             {:name "u" :position 4 :data_type "u.citext" :type_schema "u" :is_nullable true}]
                                   :constraints {}}}})]
    (is (= [["c" :column] ["l" :column] ["p" :column] ["p" :unknown-type] ["u" :column] ["u" :unknown-type]] (map (juxt :column :fact) fs))
        "a composite type of the schema named point is not pg_catalog's point, nor one named citext no extension made citext's")))

(deftest keys-and-references
  (let [fs (p/facts {:name "public" :types {}
                     :tables {"m" {:columns [{:name "id" :position 1 :data_type "integer" :is_nullable false :default_value "nextval('m_id_seq'::regclass)"}
                                             {:name "g" :position 2 :data_type "integer" :is_nullable false}]
                                   :constraints {"m_pkey" {:name "m_pkey" :type "PRIMARY KEY" :columns ["id"]}
                                                 "m_g_key" {:name "m_g_key" :type "UNIQUE" :columns ["g"]}
                                                 "m_g_fkey" {:name "m_g_fkey" :type "FOREIGN KEY" :columns ["g"]
                                                             :references {:schema "public" :table "groups" :columns ["id"]}}}}}})]
    (is (= :serial (:identity (first (filter (comp #{"id"} :column) fs)))) "a nextval default is a serial column")
    (is (= [[:references ["g"]] [:unique ["g"]] [:primary-key ["id"]]]
           (map (juxt :fact :columns) (filter :constraint fs))) "constraints in name order")
    (is (= {:schema "public" :table "groups" :columns ["id"]} (:to (first (filter (comp #{:references} :fact) fs)))))))

(deftest a-number-default-is-a-number-however-postgresql-writes-it
  (let [defaults ["-1" "'-1'::integer" "'10000000000'::bigint" "'-1.5'::numeric" "1.5" "'1e+300'::double precision"
                  "'100000000000000000000'::numeric" "'NaN'::numeric" "'12'::text" "'happy'::mood"]
        fs (p/facts {:name "public" :types {}
                     :tables {"t" {:columns (map-indexed (fn [i d] {:name (str "c" (+ 10 i)) :position i :data_type "numeric" :is_nullable true :default_value d}) defaults)
                                   :constraints {}}}})]
    (is (= [-1 -1 10000000000 -1.5M 1.5M 1E+300M 100000000000000000000N "NaN" "12" "happy"]
           (map :default (filter (comp #{:column} :fact) fs))))))

(deftest a-long-quoted-default-is-read-without-running-out-of-stack
  (let [s (apply str (repeat 100000 "a"))
        facts (on-small-stack #(p/facts {:name "public" :types {}
                        :tables {"t" {:columns [{:name "c" :position 1 :data_type "text" :type_schema "pg_catalog" :is_nullable true
                                                 :default_value (str "'" s "'::text")}]
                                      :constraints {}}}}))]
    (is (= s (:default (first (filter #(= :column (:fact %)) facts)))))))
