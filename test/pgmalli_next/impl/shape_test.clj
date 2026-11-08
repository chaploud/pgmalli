(ns pgmalli-next.impl.shape-test
  "The names derived from a table's, on plain and on quoted identifiers."
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [pgmalli-next.impl.shape :as shape]))

(deftest names-of-plain-and-odd-identifiers
  (is (= :pg.public/users (shape/schema-key "public" "users")))
  (is (= "pg.public/order items" (shape/schema-key "public" "order items")) "a name needing quotes is a string key")
  (is (= "pg.my schema/users" (shape/schema-key "my schema" "users")) "either half is enough")
  (is (= :pg.public/users (shape/table-key "public.users")))
  (is (= "pg.public/order items" (shape/table-key "public.order items")))
  (is (= :id (shape/ident-key "id")))
  (is (= "line no" (shape/ident-key "line no")))
  (is (= :pg.public.users/insert (shape/derived-name :pg.public/users "insert")))
  (is (= :pg.public.users/update (shape/derived-name :pg.public/users "update")))
  (is (= "pg.public.order items/insert" (shape/derived-name "pg.public/order items" "insert")))
  (is (= "pg.public.order items/update" (shape/derived-name "pg.public/order items" "update"))))

(deftest a-registry-key-reads-back-as-its-schema-and-name-whatever-they-hold
  (let [part (gen/fmap #(apply str %) (gen/vector (gen/elements [\a \B \_ \1 \/ \\ \. \space \"]) 1 6))
        result (tc/quick-check 2000 (prop/for-all [s part n part]
                                      (= [s n] (shape/schema-and-name (shape/schema-key s n))))
                               :seed 42)]
    (is (:pass? result) (pr-str (:shrunk result))))
  (is (not= (shape/schema-key "a/b" "c") (shape/schema-key "a" "b/c")) "a / of the schema's name is escaped"))
