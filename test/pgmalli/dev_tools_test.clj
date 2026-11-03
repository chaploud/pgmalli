(ns pgmalli.dev-tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [pgmalli.impl.types :as types]
            [pgmalli.test-db :as test-db]
            [update-docs]
            [update-expected]))

(def ^:private src
  "(deftest a
  (is (= 0 code)))

(deftest b
  (is (= 0 code))
  (is (= {'m :a} (f)))
  (is (= 1 n))
  (is (= 1 n)))
")

(defn- failure [test expected actual & [line]]
  {:test test :expected expected :actual (list 'not (list '= (second expected) actual)) :line line})

(deftest update-expected-rewrites-only-the-failing-deftests-form
  (let [[out rewrote left] (update-expected/rewrite src [(failure 'b '(= 0 code) 1)])]
    (is (= 1 rewrote))
    (is (= [] left))
    (is (str/includes? out "(deftest a\n  (is (= 0 code)))"))
    (is (str/includes? out "(is (= 1 code))"))))

(deftest update-expected-writes-a-quoted-symbol-and-a-seq-as-a-vector
  (let [[out] (update-expected/rewrite src [(failure 'b '(= {(quote m) :a} (f)) {'n '(x y)})])]
    (is (str/includes? out "(is (= {'n ['x 'y]} (f)))"))))

(deftest update-expected-leaves-a-form-that-occurs-twice-unless-the-line-says-which
  (let [[_ rewrote left] (update-expected/rewrite src [(failure 'b '(= 1 n) 2)])]
    (is (= 0 rewrote))
    (is (str/starts-with? (:reason (first left)) "ambiguous")))
  (let [[out rewrote] (update-expected/rewrite src [(failure 'b '(= 1 n) 2 8)])]
    (is (= 1 rewrote))
    (is (str/includes? out "(is (= 1 n))\n  (is (= 2 n))"))))

(deftest update-docs-rewrites-a-wrong-result-line
  (is (= ";; => 2" (second (first (#'update-docs/update-block ["(+ 1 1)" ";; => 3"]))))))

(deftest the-catalog-snapshot-is-of-the-postgresql-versions-ci-runs
  (is (= (:versions @types/catalog)
         (re-seq #"\d+" (second (re-find #"(?m)^\s+pg: \[(.*)\]" (slurp ".github/workflows/ci.yml")))))
      "a version added to or dropped from CI's matrix: run bb catalog"))

(deftest a-test-run-removes-the-containers-of-its-hosts-dead-runs-alone
  (is (= ["b"] (test-db/orphans ["a other:7" "b me:7" "c me:8" "d 7"] "me" #{8}))))
