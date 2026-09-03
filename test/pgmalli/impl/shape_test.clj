(ns pgmalli.impl.shape-test
  "The names derived from a table's, on plain and on quoted identifiers."
  (:require [clojure.test :refer [deftest is]]
            [pgmalli.impl.shape :as shape]))

(deftest names-of-plain-and-odd-identifiers
  (is (= :pg.public/users (shape/schema-key "public" "users")))
  (is (= "pg.public/order items" (shape/schema-key "public" "order items")) "a name needing quotes is a string key")
  (is (= "pg.my schema/users" (shape/schema-key "my schema" "users")) "either half is enough")
  (is (= :pg.public/users (shape/table-key "public.users")))
  (is (= "pg.public/order items" (shape/table-key "public.order items")))
  (is (= :id (shape/ident-key "id")))
  (is (= "line no" (shape/ident-key "line no")))
  (is (= :pg.public.users/insert (shape/insert-name :pg.public/users)))
  (is (= :pg.public.users/update (shape/update-name :pg.public/users)))
  (is (= "pg.public.order items/insert" (shape/insert-name "pg.public/order items")))
  (is (= "pg.public.order items/update" (shape/update-name "pg.public/order items"))))
