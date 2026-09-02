(ns pgmalli.impl.compile
  "Expression data -> a Clojure form for malli's :fn. The form is plain data (no functions),
   so it survives EDN, and malli evaluates it itself (natively on babashka, through
   org.babashka/sci on the JVM).

   PostgreSQL's three-valued logic is reproduced with a letfn prelude: nil stands for NULL,
   comparisons and functions yield nil when any operand is nil, and the CHECK passes unless
   the result is false. Expressions using anything outside the supported vocabulary are
   rejected with ex-info, so the caller can leave them unrendered."
  (:require [pgmalli.impl.expr :as x]))

(def ^:private prelude
  '[(cmp [op a b] (when (and (some? a) (some? b)) (op (compare a b) 0)))
    (and3 [& xs] (cond (some false? xs) false (some nil? xs) nil :else true))
    (or3 [& xs] (cond (some true? xs) true (some nil? xs) nil :else false))
    (not3 [a] (when (some? a) (not a)))
    (in3 [a s] (when (some? a) (contains? s a)))
    (fn3 [f & xs] (when (every? some? xs) (apply f xs)))
    (json-type [v] (cond (nil? v) nil (map? v) "object" (sequential? v) "array" (string? v) "string"
                         (number? v) "number" (boolean? v) "boolean" :else "null"))])

(def ^:private comparators {:= '= :<> 'not= :< '< :> '> :<= '<= :>= '>=})
(def ^:private arithmetic {:+ '+ :- '- :* '* :/ '/})

(defn- unsupported [what] (throw (ex-info (str "unsupported in CHECK compilation: " what) {:unsupported what})))

(defn- col [k] (list 'get 'row (keyword (name k))))

(defn- form
  "Form for one expression node."
  [e]
  (cond
    (or (string? e) (number? e) (boolean? e) (nil? e)) e
    (keyword? e) (col e)
    (vector? e)
    (let [[op & args] e
          a (delay (form (first args)))
          b (delay (form (second args)))]
      (case op
        :cast (form (first args))
        :and (list* 'and3 (map form args))
        :or (list* 'or3 (map form args))
        :not (list 'not3 @a)
        (:= :<> :< :> :<= :>=) (list 'cmp (comparators op) @a @b)
        :is (if (nil? (second args)) (list 'nil? @a) (list '= @a (second args)))
        :is-not (if (nil? (second args)) (list 'some? @a) (list 'not= @a (second args)))
        :in (list 'in3 @a (set (map form (second args))))
        :not-in (list 'not3 (list 'in3 @a (set (map form (second args)))))
        :between (let [[x lo hi] (map form args)] (list 'and3 (list 'cmp '>= x lo) (list 'cmp '<= x hi)))
        (:+ :- :* :/) (list* 'fn3 (arithmetic op) (map form args))
        (:length :char_length :octet_length) (list 'fn3 'count @a)
        (:trim :btrim) (list 'fn3 'clojure.string/trim (form (last args)))
        :lower (list 'fn3 'clojure.string/lower-case @a)
        :upper (list 'fn3 'clojure.string/upper-case @a)
        :coalesce (list* 'or (map form args))
        :jsonb_typeof (list 'json-type @a)
        :-> (list 'fn3 'get @a @b)
        :->> (list 'fn3 '(fn [m k] (some-> (get m k) str)) @a @b)
        :regex (list 'fn3 '(fn [s re] (some? (re-find (re-pattern re) s))) @a @b)
        :iregex (list 'fn3 '(fn [s re] (some? (re-find (re-pattern (str "(?i)" re)) s))) @a @b)
        :case (let [[clauses else] (if (= :else (last (butlast args)))
                                     [(butlast (butlast args)) (last args)]
                                     [args nil])]
                (list* 'cond (concat (mapcat (fn [[c r]] [(list 'true? (form c)) (form r)]) (partition 2 clauses))
                                     [:else (form else)])))
        (unsupported op)))
    :else (unsupported (pr-str e))))

(defn check-fn
  "(fn [row] ...) form that is true when the row satisfies the CHECK expression."
  [e]
  (list 'fn '[row] (list 'letfn prelude (list 'not (list 'false? (form (x/canonical e)))))))
