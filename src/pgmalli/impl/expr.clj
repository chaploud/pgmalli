(ns pgmalli.impl.expr
  "Turns expressions as printed by PostgreSQL's deparser (pg_get_constraintdef, pg_get_expr)
   into HoneySQL-style data. Only that output format is supported, not arbitrary SQL.

   Covered: comparison, logical, arithmetic, string, array, jsonb and regex operators (see
   edn-safe-ops); IS [NOT] NULL/TRUE/FALSE; IS [NOT] DISTINCT FROM; [NOT] IN; ANY/ALL (ARRAY[...]);
   [NOT] BETWEEN [SYMMETRIC]; [NOT] LIKE/ILIKE; ::casts; function calls; TRIM(BOTH FROM x);
   CASE; CURRENT_TIMESTAMP and friends; AT TIME ZONE; (x).field; x[i]; quoted identifiers;
   string, number, boolean and NULL literals.

   Operators whose symbol is not a readable EDN keyword (~, @>, ...) get a named keyword
   (see edn-safe-ops); ->honeysql maps them back when SQL has to be generated."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]))

;;; tokens

(def ^:private keywords
  #{"AND" "OR" "NOT" "IS" "NULL" "IN" "ANY" "ALL" "SOME" "ARRAY" "CASE" "WHEN" "THEN" "ELSE" "END"
    "TRUE" "FALSE" "LIKE" "ILIKE" "BETWEEN" "SIMILAR" "TO" "FROM" "BOTH" "LEADING" "TRAILING"
    "DISTINCT" "ESCAPE" "SYMMETRIC" "AT" "TIME" "ZONE"
    "CURRENT_TIMESTAMP" "CURRENT_DATE" "CURRENT_TIME" "LOCALTIMESTAMP" "LOCALTIME"
    "CURRENT_USER" "SESSION_USER" "CURRENT_ROLE"})

(def ^:private ops
  ;; longest first
  ["::" "=>" "<>" "!=" "<=" ">=" "!~~*" "!~~" "~~*" "~~" "!~*" "!~" "~*" "~" "||" "&&" "->>" "->" "#>>" "#>" "@>" "<@" "?|" "?&" "?"
   "=" "<" ">" "+" "-" "*" "/" "%" "^"])

(defn- quoted [s i quote-char what]
  (let [end (loop [j (inc i)]
              (cond (>= j (count s)) (throw (ex-info (str "unterminated " what) {:pos i :input s}))
                    (and (= (.charAt ^String s j) quote-char) (< (inc j) (count s))
                         (= (.charAt ^String s (inc j)) quote-char)) (recur (+ j 2))
                    (= (.charAt ^String s j) quote-char) j
                    :else (recur (inc j))))]
    [(str/replace (subs s (inc i) end) (str quote-char quote-char) (str quote-char)) (inc end)]))

(defn- tokenize [s]
  (loop [i 0 out []]
    (if (>= i (count s))
      out
      (let [c (.charAt ^String s i)]
        (cond
          (Character/isWhitespace c) (recur (inc i) out)
          (= c \') (let [[v next] (quoted s i \' "string")] (recur next (conj out {:t :str :v v})))
          (= c \") (let [[v next] (quoted s i \" "identifier")] (recur next (conj out {:t :ident :v v})))

          (or (Character/isDigit c) (and (= c \.) (< (inc i) (count s)) (Character/isDigit (.charAt ^String s (inc i)))))
          (let [m (re-find #"^\d*\.?\d+(?:[eE][+-]?\d+)?|^\d+" (subs s i))
                v (if (re-find #"[.eE]" m) (Double/parseDouble m) (try (Long/parseLong m) (catch Exception _ (bigint m))))]
            (recur (+ i (count m)) (conj out {:t :num :v v})))

          (or (Character/isLetter c) (= c \_))
          (let [m (re-find #"^[\p{L}_][\p{L}\p{N}_$]*" (subs s i))
                up (str/upper-case m)]
            (recur (+ i (count m)) (conj out (if (keywords up) {:t :kw :v up} {:t :ident :v m}))))

          :else
          (if-let [op (some #(when (str/starts-with? (subs s i) %) %) ops)]
            (recur (+ i (count op)) (conj out {:t :op :v op}))
            (case c
              \( (recur (inc i) (conj out {:t :lparen}))
              \) (recur (inc i) (conj out {:t :rparen}))
              \[ (recur (inc i) (conj out {:t :lbracket}))
              \] (recur (inc i) (conj out {:t :rbracket}))
              \, (recur (inc i) (conj out {:t :comma}))
              \. (recur (inc i) (conj out {:t :dot}))
              (throw (ex-info (str "unexpected character: " c) {:pos i :input s})))))))))

;;; Pratt parser

(def edn-safe-ops
  "PostgreSQL operators whose keyword form is not readable EDN, and the names used instead."
  {"~" :regex "~*" :iregex "!~" :not-regex "!~*" :not-iregex
   "~~" :like "~~*" :ilike "!~~" :not-like "!~~*" :not-ilike
   "#>" :json-path "#>>" :json-path-text "@>" :contains "<@" :contained-by
   "?" :has-key "?|" :has-any-key "?&" :has-all-keys "&&" :overlaps "%" :mod "^" :pow})

(def ^:private binary-ops
  ;; operator -> [precedence keyword], following PostgreSQL precedence
  (merge
   {"OR" [1 :or] "AND" [2 :and]
    "=" [5 :=] "<>" [5 :<>] "!=" [5 :<>] "<" [5 :<] ">" [5 :>] "<=" [5 :<=] ">=" [5 :>=]
    "LIKE" [6 :like] "ILIKE" [6 :ilike]
    "||" [7 :||] "->" [7 :->] "->>" [7 :->>]
    "+" [8 :+] "-" [8 :-] "*" [9 :*] "/" [9 :/]}
   (into {} (map (fn [[op k]] [op [(case op "^" 10 "%" 9 7) k]]) edn-safe-ops))))

(def ^:private prec-not 3)
(def ^:private prec-is 4)
(def ^:private prec-in 6)
(def ^:private prec-unary-minus 10)
(def ^:private prec-postfix 12)

(declare parse-expr)

(defn- peek-tok [st] (first (:toks @st)))
(defn- next-tok! [st] (let [t (peek-tok st)] (swap! st update :toks rest) t))
(defn- kw? [tok v] (and (= :kw (:t tok)) (= v (:v tok))))
(defn- op? [tok v] (and (= :op (:t tok)) (= v (:v tok))))
(defn- expect! [st pred what]
  (let [t (next-tok! st)]
    (when-not (pred t) (throw (ex-info (str "expected " what) {:got t :input (:input @st)})))
    t))

(defn- parse-list
  "Comma-separated expressions up to and including the closing token."
  [st close]
  (if (= close (:t (peek-tok st)))
    (do (next-tok! st) [])
    (loop [acc [(parse-expr st 0)]]
      (let [t (next-tok! st)]
        (cond (= :comma (:t t)) (recur (conj acc (parse-expr st 0)))
              (= close (:t t)) acc
              :else (throw (ex-info (str "expected ',' or " (name close)) {:got t :input (:input @st)})))))))

(defn- ident-name
  "Dotted qualification (schema.func) folded into one name."
  [st first-tok]
  (loop [parts [(:v first-tok)]]
    (if (and (= :dot (:t (peek-tok st))) (= :ident (:t (second (:toks @st)))))
      (do (next-tok! st) (recur (conj parts (:v (next-tok! st)))))
      (str/join "." parts))))

(defn- parse-type
  "Type name after ::. Handles multi-word names, (n[,m]) modifiers and [] dimensions."
  [st]
  (let [t (next-tok! st)
        _ (when-not (= :ident (:t t)) (throw (ex-info "expected a type name" {:got t :input (:input @st)})))
        base (ident-name st t)
        words (loop [ws [base]]
                (let [n (peek-tok st)
                      nv (some-> (:v n) str/lower-case)
                      prev (str/lower-case (peek ws))]
                  (if (and (#{:ident :kw} (:t n))
                           (or (and (#{"character" "bit"} prev) (= nv "varying"))
                               (and (= "double" prev) (= nv "precision"))
                               (and (#{"timestamp" "time"} prev) (#{"with" "without"} nv))
                               (and (#{"with" "without"} prev) (= nv "time"))
                               (and (= "time" prev) (= nv "zone") (>= (count ws) 3))))
                    (do (next-tok! st) (recur (conj ws (:v n))))
                    ws)))
        mods (when (= :lparen (:t (peek-tok st))) (next-tok! st) (vec (parse-list st :rparen)))
        dims (loop [n 0]
               (if (= :lbracket (:t (peek-tok st)))
                 (do (next-tok! st) (expect! st #(= :rbracket (:t %)) "']'") (recur (inc n)))
                 n))
        k (keyword (str (str/join "-" (map str/lower-case words)) (apply str (repeat dims "-array"))))]
    (if (seq mods) (into [k] mods) k)))

(defn- parse-case [st]
  (let [simple (when-not (kw? (peek-tok st) "WHEN") (parse-expr st 0))
        clauses (loop [acc []]
                  (if (kw? (peek-tok st) "WHEN")
                    (do (next-tok! st)
                        (let [c (parse-expr st 0)
                              _ (expect! st #(kw? % "THEN") "THEN")
                              r (parse-expr st 0)]
                          (recur (conj acc (if simple [:= simple c] c) r))))
                    acc))
        else (when (kw? (peek-tok st) "ELSE") (next-tok! st) (parse-expr st 0))]
    (expect! st #(kw? % "END") "END")
    (cond-> (into [:case] clauses) (some? else) (conj :else else))))

(defn- parse-call [st name]
  (cond
    (and (= "trim" (str/lower-case name)) (kw? (peek-tok st) "BOTH"))
    (do (next-tok! st)
        (let [chars (when-not (kw? (peek-tok st) "FROM") (parse-expr st 0))
              _ (expect! st #(kw? % "FROM") "FROM")
              x (parse-expr st 0)]
          (expect! st #(= :rparen (:t %)) "')'")
          (if chars [:trim chars x] [:trim x])))
    (op? (peek-tok st) "*")
    (do (next-tok! st) (expect! st #(= :rparen (:t %)) "')'") [(keyword (str/lower-case name)) :*])
    :else (into [(keyword (str/lower-case name))] (parse-list st :rparen))))

(defn- parse-primary [st]
  (let [t (next-tok! st)]
    (case (:t t)
      :str (:v t)
      :num (:v t)
      :lparen (let [e (parse-expr st 0)] (expect! st #(= :rparen (:t %)) "')'") e)
      :op (if (= "-" (:v t))
            (let [e (parse-expr st prec-unary-minus)] (if (number? e) (- e) [:- e]))
            (throw (ex-info (str "operator at start of expression: " (:v t)) {:input (:input @st)})))
      :kw (case (:v t)
            "NULL" nil "TRUE" true "FALSE" false
            "NOT" [:not (parse-expr st prec-not)]
            ("CURRENT_TIMESTAMP" "CURRENT_DATE" "CURRENT_TIME" "LOCALTIMESTAMP" "LOCALTIME"
             "CURRENT_USER" "SESSION_USER" "CURRENT_ROLE") [:raw (:v t)]
            "ARRAY" (do (expect! st #(= :lbracket (:t %)) "'['") [:array (parse-list st :rbracket)])
            ("ANY" "ALL" "SOME") (do (expect! st #(= :lparen (:t %)) "'('")
                                     (let [e (parse-expr st 0)]
                                       (expect! st #(= :rparen (:t %)) "')'")
                                       [(if (= "ALL" (:v t)) :all :any) e]))
            "CASE" (parse-case st)
            (throw (ex-info (str "unexpected keyword: " (:v t)) {:input (:input @st)})))
      :ident (let [name (ident-name st t)]
               (if (= :lparen (:t (peek-tok st)))
                 (do (next-tok! st) (parse-call st name))
                 (keyword name)))
      (throw (ex-info "expected an expression" {:got t :input (:input @st)})))))

(defn- flatten-op [op a b]
  (into [op] (concat (if (and (vector? a) (= op (first a))) (rest a) [a])
                     (if (and (vector? b) (= op (first b))) (rest b) [b]))))

(defn- parse-expr [st min-prec]
  (loop [left (parse-primary st)]
    (let [t (peek-tok st)]
      (cond
        (op? t "::")
        (if (< min-prec prec-postfix) (do (next-tok! st) (recur [:cast left (parse-type st)])) left)

        (and (= :dot (:t t)) (= :ident (:t (second (:toks @st)))))
        (if (< min-prec prec-postfix) (do (next-tok! st) (recur [:field left (keyword (:v (next-tok! st)))])) left)

        ;; t.* : the whole row, as an argument
        (and (= :dot (:t t)) (op? (second (:toks @st)) "*"))
        (if (< min-prec prec-postfix) (do (next-tok! st) (next-tok! st) (recur [:row left])) left)

        ;; name => value : a named argument
        (op? t "=>")
        (if (keyword? left) (do (next-tok! st) (recur [:named left (parse-expr st 0)])) left)

        (= :lbracket (:t t))
        (if (< min-prec prec-postfix)
          (do (next-tok! st)
              (let [i (parse-expr st 0)] (expect! st #(= :rbracket (:t %)) "']'") (recur [:subscript left i])))
          left)

        (kw? t "AT")
        (if (<= min-prec prec-postfix)
          (do (next-tok! st) (expect! st #(kw? % "TIME") "TIME") (expect! st #(kw? % "ZONE") "ZONE")
              (recur [:at-time-zone left (parse-expr st (dec prec-postfix))]))
          left)

        (kw? t "IS")
        (if (<= min-prec prec-is)
          (do (next-tok! st)
              (let [neg (when (kw? (peek-tok st) "NOT") (next-tok! st) true)
                    n (next-tok! st)]
                (cond
                  (kw? n "DISTINCT") (do (expect! st #(kw? % "FROM") "FROM")
                                         (recur [(if neg :is-not-distinct-from :is-distinct-from) left (parse-expr st (inc prec-is))]))
                  ;; IS [NOT] JSON [VALUE | ARRAY | OBJECT | SCALAR] [WITH | WITHOUT UNIQUE [KEYS]]
                  (= "JSON" (str/upper-case (str (:v n))))
                  (let [words (loop [ws []]
                                (let [w (str/upper-case (str (:v (peek-tok st))))]
                                  (if (#{"VALUE" "ARRAY" "OBJECT" "SCALAR" "WITH" "WITHOUT" "UNIQUE" "KEYS"} w)
                                    (do (next-tok! st) (recur (conj ws w)))
                                    ws)))]
                    (recur (into [(if neg :is-not-json :is-json) left] (map #(keyword (str/lower-case %)) words))))
                  (#{"NULL" "TRUE" "FALSE"} (:v n))
                  (recur [(if neg :is-not :is) left ({"NULL" nil "TRUE" true "FALSE" false} (:v n))])
                  :else (throw (ex-info "unexpected token after IS" {:got n :input (:input @st)})))))
          left)

        (or (kw? t "NOT") (kw? t "IN") (kw? t "BETWEEN") (kw? t "LIKE") (kw? t "ILIKE"))
        (if (<= min-prec prec-in)
          (let [neg (when (kw? t "NOT") (next-tok! st) true)
                t (next-tok! st)]
            (case (:v t)
              "IN" (do (expect! st #(= :lparen (:t %)) "'('")
                       (recur [(if neg :not-in :in) left (parse-list st :rparen)]))
              "BETWEEN" (let [symmetric (when (kw? (peek-tok st) "SYMMETRIC") (next-tok! st) true)
                              lo (parse-expr st (inc prec-in))
                              _ (expect! st #(kw? % "AND") "AND")
                              hi (parse-expr st (inc prec-in))
                              e [(if symmetric :between-symmetric :between) left lo hi]]
                          (recur (if neg [:not e] e)))
              ("LIKE" "ILIKE") (let [e [(if (= "LIKE" (:v t)) :like :ilike) left (parse-expr st (inc prec-in))]]
                                 (recur (if neg [:not e] e)))
              (throw (ex-info "unexpected token after NOT" {:got t :input (:input @st)}))))
          left)

        (and (#{:op :kw} (:t t)) (binary-ops (:v t)))
        (let [[prec hop] (binary-ops (:v t))]
          (if (<= min-prec prec)
            (do (next-tok! st)
                (let [right (parse-expr st (inc prec))]
                  (recur (if (#{:and :or} hop) (flatten-op hop left right) [hop left right]))))
            left))

        :else left))))

;;; API

(defn parse
  "Expression string -> data. Throws ex-info with :input (and :pos or :got) when it cannot be read."
  [s]
  (let [st (atom {:toks (tokenize s) :input s})
        e (parse-expr st 0)]
    (when-let [t (peek-tok st)]
      (throw (ex-info (str "trailing token: " (pr-str t)) {:got t :input s})))
    e))

(defn try-parse
  "Like parse, but returns {:expr e} or {:error message :input s}."
  [s]
  (try {:expr (parse s)}
       (catch Exception e {:error (or (ex-message e) (str (class e))) :input s})))

(defn check-clause
  "The expression inside \"CHECK (...)\" as data. Trailing NOT VALID / NO INHERIT are ignored."
  [s]
  (let [[_ inner] (re-matches #"(?s)CHECK\s*\((.*)\)(?:\s+(?:NOT VALID|NO INHERIT))*\s*" s)]
    (when-not inner (throw (ex-info "not a CHECK (...) clause" {:input s})))
    (parse inner)))

(def ^:private numeric-types
  #{:smallint :integer :bigint :int2 :int4 :int8 :numeric :decimal :real :double-precision :float4 :float8})

(defn canonical
  "Undoes rewrites PostgreSQL applies when deparsing, without changing meaning:
   numeric literals printed as casts ('-1'::integer -> -1), array casts,
   col = ANY (ARRAY[...]) -> [:in col [...]], col <> ALL (ARRAY[...]) -> [:not-in col [...]],
   and a one-element IN -> = (NOT IN -> <>)."
  [e]
  (walk/postwalk
   (fn [f]
     (cond
       (and (vector? f) (= :cast (first f)) (vector? (second f)) (= :array (first (second f))))
       (second f)

       (and (vector? f) (= :cast (first f)) (string? (second f)) (numeric-types (nth f 2))
            (re-matches #"-?\d+(\.\d+)?([eE][+-]?\d+)?" (second f)))
       (let [s (second f)] (if (re-find #"[.eE]" s) (bigdec s) (parse-long s)))

       (and (vector? f) (= := (first f)) (vector? (nth f 2)) (= :any (first (nth f 2)))
            (vector? (second (nth f 2))) (= :array (first (second (nth f 2)))))
       [:in (second f) (second (second (nth f 2)))]

       (and (vector? f) (= :<> (first f)) (vector? (nth f 2)) (= :all (first (nth f 2)))
            (vector? (second (nth f 2))) (= :array (first (second (nth f 2)))))
       [:not-in (second f) (second (second (nth f 2)))]

       (and (vector? f) (= :in (first f)) (= 1 (count (nth f 2))))
       [:= (second f) (first (nth f 2))]

       (and (vector? f) (= :not-in (first f)) (= 1 (count (nth f 2))))
       [:<> (second f) (first (nth f 2))]

       :else f))
   e))

(defn ->honeysql
  "Replaces the EDN-safe operator names with the symbols HoneySQL formats."
  [e]
  (let [back (into {} (keep (fn [[op k]] (when-not (#{:like :ilike :not-like :not-ilike} k) [k (keyword op)])) edn-safe-ops))]
    (walk/postwalk (fn [f] (if (and (vector? f) (contains? back (first f))) (assoc f 0 (back (first f))) f)) e)))
