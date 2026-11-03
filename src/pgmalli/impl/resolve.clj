(ns pgmalli.impl.resolve
  "The type PostgreSQL resolves an expression to, over pg_catalog as pgmalli.impl.types/catalog
   holds it: the common type of UNION, CASE and the like (select_common_type, docs 10.5), a
   function call (10.3) and an operator (10.2), polymorphic types bound as docs 38.2.5 says.

   A type is a name as format_type prints it (\"x[]\" for an array), [:enum name] for an enum
   type, :unknown for an untyped literal or NULL, and, where pgmalli.honeysql asks what a call
   takes, :free for a parameter of any type. A domain is passed as its base type. nil means
   PostgreSQL may refuse it, or resolve it otherwise in one of the versions the catalog holds."
  (:require [pgmalli.impl.types :as types]))

(def ^:private element-family #{"anyelement" "anyarray" "anynonarray" "anyenum" "anyrange" "anymultirange"})
(def ^:private compatible-family #{"anycompatible" "anycompatiblearray" "anycompatiblenonarray" "anycompatiblerange" "anycompatiblemultirange"})
(def ^:private polymorphic (into element-family compatible-family))

(defn- unbound? [t] (contains? #{:unknown :free} t))

(defn- coerces?
  "can_coerce_type: whether PostgreSQL takes a value of type in where decl goes, implicitly;
   a polymorphic decl as far as the type alone tells."
  [in decl]
  (or (unbound? in) (= in decl) (= "\"any\"" decl) (contains? polymorphic decl)
      (and (string? in) (string? decl) (types/implicit? in decl))))

(defn common-type
  "select_common_type of types ts: the first known type, replaced by a later one of its category
   it converts to implicitly and not back, unless it is the category's preferred type; text for
   none known; nil where the categories differ or a type does not convert to the result."
  [ts]
  (let [known (remove unbound? ts)]
    (if (empty? known)
      "text"
      (let [t (reduce (fn [p n]
                        (cond (= p n) p
                              (not (and (types/category p) (= (types/category p) (types/category n)))) (reduced nil)
                              (and (not (types/preferred? p)) (coerces? p n) (not (coerces? n p))) n
                              :else p))
                      (first known) (rest known))]
        (when (and t (every? #(coerces? % t) ts)) t)))))

(defn- array-of [t] (cond (types/element t) t (string? t) (str t "[]")))

(defn- range-of [m] (some (fn [[r {mr :multirange}]] (when (= m mr) r)) (:ranges @types/catalog)))

(defn- alike? [xs] (or (empty? xs) (apply = xs)))

(defn- bind
  "check_generic_type_consistency: {:elem :array :range :multirange :compat :compat-range} the
   polymorphic decls bind for ins, {} with none; nil where ins are not consistent."
  [decls ins]
  (let [pairs (remove (comp unbound? second) (map vector decls ins))
        of (fn [& ds] (for [[d in] pairs :when (some #{d} ds)] in))
        ranges (:ranges @types/catalog)
        subtype #(get-in ranges [% :subtype])
        arrays (of "anyarray") rs (concat (of "anyrange") (map range-of (of "anymultirange")))
        elems (concat (of "anyelement" "anynonarray" "anyenum") (map types/element arrays) (map subtype rs))
        c-arrays (of "anycompatiblearray")
        c-rs (concat (of "anycompatiblerange") (map range-of (of "anycompatiblemultirange")))
        cs (concat (of "anycompatible" "anycompatiblenonarray") (map types/element c-arrays))
        elem (first elems)
        c-range (first c-rs)
        compat (if c-range
                 (when (every? #(coerces? % (subtype c-range)) cs) (subtype c-range))
                 (when (seq cs) (common-type cs)))]
    (when (and (every? some? elems) (alike? elems)
               (every? some? rs) (alike? rs)
               (every? some? c-rs) (alike? c-rs)
               (or (empty? cs) (some? compat))
               (not (and elem (some #{"anynonarray"} decls) (types/element elem)))
               (not (and elem (some #{"anyenum"} decls) (not= "E" (types/category elem))))
               (not (and compat (some #{"anycompatiblenonarray"} decls) (types/element compat))))
      (cond-> {}
        elem (assoc :elem elem :array (or (first arrays) (array-of elem)))
        (first rs) (assoc :range (first rs))
        (seq (of "anymultirange")) (assoc :multirange (first (of "anymultirange")))
        compat (assoc :compat compat)
        c-range (assoc :compat-range c-range)))))

(defn- bound
  "The type decl is under bindings b; nil for a polymorphic one they leave open."
  [decl b]
  (let [multirange #(get-in @types/catalog [:ranges % :multirange])]
    (case decl
      ("anyelement" "anynonarray" "anyenum") (:elem b)
      "anyarray" (:array b)
      "anyrange" (:range b)
      "anymultirange" (or (:multirange b) (some-> (:range b) multirange))
      ("anycompatible" "anycompatiblenonarray") (:compat b)
      "anycompatiblearray" (some-> (:compat b) array-of)
      "anycompatiblerange" (:compat-range b)
      "anycompatiblemultirange" (some-> (:compat-range b) multirange)
      decl)))

(defn- forms
  "The argument lists an overload takes n arguments as: its own, without the last arguments it
   has defaults for, a variadic one with its last argument given as one or more elements."
  [{:keys [args variadic defaults]} n]
  (let [k (count args)]
    (cond variadic (when (>= n k)
                     (let [l (last args)
                           e (cond (= "\"any\"" l) l (= "anyarray" l) "anyelement" (= "anycompatiblearray" l) "anycompatible" :else (types/element l))]
                       [(vec (concat (butlast args) (repeat (- n (dec k)) e)))]))
          (= n k) [args]
          (<= (- k (or defaults 0)) n k) [(subvec args 0 n)])))

(defn- candidates
  "{:decls [type ...] :overload o} of overloads for n arguments; of two with one argument list, a
   variadic one gives way to another, and two others are ambiguous (:ambiguous)."
  [overloads n]
  (for [[decls os] (group-by first (for [o overloads, f (forms o n)] [f o]))
        :let [os (map second os)
              plain (remove :variadic os)
              o (cond (= 1 (count os)) (first os) (= 1 (count plain)) (first plain))]]
    (if o {:decls decls :overload o} {:decls decls :overload (first os) :ambiguous true})))

(defn- viable? [{:keys [decls]} ins]
  (and (every? true? (map coerces? ins decls)) (some? (bind decls ins))))

(defn- most
  "The candidates scoring highest by f."
  [f cs]
  (let [scored (map (juxt f identity) cs)
        top (apply max (map first scored))]
    (for [[s c] scored :when (= s top)] c)))

(defn- select
  "func_select_candidate: one of several viable candidates, as PostgreSQL chooses for ins; nil
   where it finds them ambiguous."
  [cs ins]
  (let [one #(when (= 1 (count %)) (first %))
        known (keep-indexed (fn [i t] (when-not (unbound? t) i)) ins)
        unknown (keep-indexed (fn [i t] (when (unbound? t) i)) ins)
        cs (most (fn [{:keys [decls]}] (count (filter #(= (nth ins %) (nth decls %)) known))) cs)
        cs (if (one cs) cs
               (most (fn [{:keys [decls]}]
                       (count (filter #(let [d (nth decls %) in (nth ins %)]
                                         (or (= in d) (and (types/preferred? d) (= (types/category in) (types/category d)))))
                                      known)))
                     cs))
        ;; an unknown argument: the string category where a candidate takes one there, else the one
        ;; category all take, and its preferred type where one takes it
        slots (when (and (not (one cs)) (seq unknown))
                (reduce (fn [acc i]
                          (let [cats (map #(types/category (nth (:decls %) i)) cs)
                                c (cond (some #{"S"} cats) "S" (apply = cats) (first cats))]
                            (if c
                              (assoc acc i [c (boolean (some #(and (= c (types/category (nth (:decls %) i))) (types/preferred? (nth (:decls %) i))) cs))])
                              (reduced nil))))
                        {} unknown))
        cs (if slots
             (or (seq (filter (fn [{:keys [decls]}]
                                (every? (fn [[i [c pref?]]] (let [d (nth decls i)] (and (= c (types/category d)) (or (not pref?) (types/preferred? d)))))
                                        slots))
                              cs))
                 cs)
             cs)
        ;; known arguments of one type: the unknown ones taken as of it too
        same (distinct (map #(nth ins %) known))]
    (or (one cs)
        (when (and (seq unknown) (= 1 (count same)))
          (let [as (vec (repeat (count ins) (first same)))]
            (one (filter #(viable? % as) cs)))))))

(defn- chosen
  "The candidate PostgreSQL calls for ins among overloads (of one version): an exact match, the
   one viable candidate, or select's choice; for an operator of one unknown argument of two, an
   exact match taking it as the other's type."
  [overloads ins operator?]
  (let [cs (candidates overloads (count ins))
        exact-ins (if (and operator? (= 2 (count ins)) (= 1 (count (filter #{:unknown} ins))))
                    (let [t (first (remove #{:unknown} ins))] (mapv #(if (= :unknown %) t %) ins))
                    ins)
        exact (when (not-any? unbound? exact-ins) (some #(when (= exact-ins (:decls %)) %) cs))]
    (or exact
        (let [vs (filter #(viable? % ins) cs)]
          (if (next vs) (select vs ins) (first vs))))))

(defn- outcome
  "{:result type :overload o} of candidate c for ins; nil where it is ambiguous or its result
   type is left open."
  [c ins]
  (when (and c (not (:ambiguous c)))
    (when-let [r (bound (:result (:overload c)) (bind (:decls c) ins))]
      {:result r :overload (dissoc (:overload c) :in)})))

(defn- in-every-version
  "(f overloads) of each version's overloads, where all agree; nil otherwise."
  [overloads f]
  (if (not-any? :in overloads)
    (f overloads)
    (let [rs (for [v (:versions @types/catalog)] (f (filter #(or (nil? (:in %)) (some #{v} (:in %))) overloads)))]
      (when (apply = rs) (first rs)))))

(defn overloads
  "The overloads pg_catalog has of a function (kind :functions) or operator (:operators)."
  [kind n]
  (get-in @types/catalog [kind n]))

(defn- type-name? [n] (boolean (or (types/entry n) (get-in @types/catalog [:types n]))))

(defn call
  "{:result type :overload o} of calling function or operator n (kind :functions or :operators)
   with arguments of types ins; nil where PostgreSQL refuses it, finds it ambiguous, or one of
   the versions resolves it otherwise. With a :free argument, the result every candidate it may
   resolve to agrees on."
  [kind n ins]
  (let [os (overloads kind n)
        operator? (= :operators kind)]
    (when (and (seq os) (every? some? ins))
      (in-every-version
       os
       (fn [os]
         (if (some #{:free} ins)
           (let [rs (distinct (for [c (candidates os (count ins)) :when (viable? c ins)] (outcome c ins)))]
             (when (= 1 (count rs)) (first rs)))
           (let [c (chosen os ins operator?)]
             ;; f(x) of a type's name f, no f taking x as it is, is a cast to the type
             (when-not (and (not operator?) (= 1 (count ins)) (type-name? n) (not= ins (:decls c)))
               (outcome c ins)))))))))

(defn argument-type
  "The type PostgreSQL resolves a parameter at position i of a call (as call takes it, :free
   there) to: the one type every candidate it may resolve to takes there; nil for none or more."
  [kind n ins i]
  (let [os (overloads kind n)]
    (when (and (seq os) (every? some? ins))
      (in-every-version
       os
       (fn [os]
         (let [ts (distinct (for [c (candidates os (count ins)) :when (viable? c ins)]
                              (when-not (:ambiguous c) (bound (nth (:decls c) i) (bind (:decls c) ins)))))]
           (when (and (= 1 (count ts)) (string? (first ts)) (not (polymorphic (first ts))) (not= "\"any\"" (first ts)))
             (first ts))))))))

(defn set-returning?
  "Whether a function of name n may return a set: one of its overloads does, or pg_catalog has
   none of the name."
  [n]
  (let [os (overloads :functions n)]
    (or (empty? os) (boolean (some :set os)))))

(defn aggregate?
  "Whether every function of name n is an aggregate."
  [n]
  (let [os (overloads :functions n)]
    (boolean (and (seq os) (every? #(= :a (:kind %)) os)))))
