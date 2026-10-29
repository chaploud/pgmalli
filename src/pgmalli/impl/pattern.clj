(ns pgmalli.impl.pattern
  "Facts about a schema, derived from the structure read by pgmalli.impl.ir.

   A fact is a map with :fact (the kind), :schema, and :table / :column / :constraint where they
   apply. CHECK constraints are matched against a fixed set of patterns (column patterns, then
   :branch-check and :or-check); anything else is kept as :table-check with the expression data,
   never dropped.

   Kinds:
     :enum-type    an enum type of the schema           {:type-name :values}
     :view         a view or materialized view          {:materialized?}; its columns follow, all nullable
     :column       a column                              {:type :position :nullable? :default :identity :generated}
                   :identity is :always, :default or :serial (a nextval default); :generated is the expression
     :enum         column of an enum type (or an array of it) {:type-name :values :array?}
     :domain-ref   column of a domain type (or an array of it) {:type-name :base (the type under its domains) :array?}
     :unknown-type type outside the mapping table (rendered as :any) {:type :type-schema (when not pg_catalog's or the schema's own)}
     :max-length   varchar(n)                            {:max}
     :numeric      numeric(p,s)                          {:precision :scale}
     :in-set       col IN (...) / col = v                {:values}
     :not-in-set   col NOT IN (...) / col <> v            {:values}
     :range        col >= a AND col <= b, BETWEEN, ...   {:min :max :min-exclusive? :max-exclusive?}
     :non-blank    length(trim(col)) > 0 / col <> ''     {:trim?}
     :length       length(col) <= n, cardinality(col) > 0 {:fn :min :max :exact}
     :json-type    jsonb_typeof(col) = 'object'          {:json-type}
     :regex        col ~ 're'                            {:re :case-insensitive?}
     :like         col LIKE 'p'                          {:pattern :case-insensitive?}
     :not-null     col IS NOT NULL
     :null         col IS NULL
     :when-present col IS NULL OR <column pattern>       {:fact-when-present}
     :primary-key  table primary key                      {:columns}
     :exclude      EXCLUDE constraint                     {:method :partial? :elements [{:column :op}]}
     :unique       UNIQUE constraint                      {:columns :nulls-distinct? (false for NULLS NOT DISTINCT)
                                                           :index? (from a unique index, not a constraint)
                                                           :partial? (the index has a predicate)}
     :references   FOREIGN KEY                            {:columns :match (:simple or :full) :to {:schema :table :columns} :valid? (false for NOT VALID)}
     :domain       domain type                            {:type-name :base :not-null? :default :facts (column patterns over VALUE;
                                                           :enum or :domain-ref for a base type of the schema)
                                                           :value-type (what its CHECKs read VALUE as, pgmalli.impl.eval/column-type)}
     :domain-check CHECK of a domain that matched no pattern {:type-name :constraint :expr :valid?}
     :branch-check CHECK of the form col = v AND ... OR col = w AND ...
                                                          {:dispatch :branches [{:values :facts} or {:null true :facts}] :default}
     :or-check     CHECK whose OR alternatives are each an AND of column patterns
                                                          {:alternatives [[fact ...] ...]}
     :table-check  CHECK that matched no pattern         {:expr :columns :valid?}
                   (also lower <= upper for a generated range column, named <column>_generated)
     :not-enforced a NOT ENFORCED CHECK or FOREIGN KEY   {:constraint :input} (nothing to render)
     :trigger      a row-level trigger left enabled       {:name :before :insert :update :delete} (nothing to render)
     :unparsed     expression that could not be read     {:input :error}

   One CHECK can yield several facts (column patterns joined by AND); every fact from a CHECK
   carries the whole expression as :expr. NOT VALID constraints are never matched, since
   existing rows may violate them."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [pgmalli.impl.eval :as ev]
            [pgmalli.impl.expr :as x]
            [pgmalli.impl.types :as types]))


;;; expression helpers

(defn- column-ref? [e] (and (keyword? e) (nil? (namespace e))))

(defn- flip
  "Comparisons with the constant on the left (1 <= x) turned around."
  [[op a b :as e]]
  (if (and (#{:< :> :<= :>=} op) (not (column-ref? a)) (column-ref? b))
    [({:< :> :> :< :<= :>= :>= :<=} op) b a]
    e))

(defn referenced-columns
  "Names of the columns an expression reads."
  [e]
  (letfn [(walk [f] (cond (vector? f) (case (first f)
                                        (:in :not-in) (concat (walk (second f)) (mapcat walk (nth f 2)))
                                        :array (mapcat walk (second f))
                                        (mapcat walk (rest f)))
                          (keyword? f) [f]
                          :else nil))]
    (->> (walk (x/strip-casts e))
         (remove #{:else :*})
         (map name) distinct vec)))

;;; column patterns

(defn- bound [e]
  (let [[op c v] (flip e)]
    (when (and (#{:< :> :<= :>=} op) (column-ref? c) (number? v))
      {:column c
       :fact :range
       (if (#{:> :>=} op) :min :max) v
       (if (#{:> :>=} op) :min-exclusive? :max-exclusive?) (boolean (#{:> :<} op))})))

(defn- literal? [v] (or (string? v) (number? v) (boolean? v)))

(def ^:private length-fns #{:length :char_length :octet_length :cardinality :array_length})

(defn- match-column
  "One column-local pattern of a normalized expression, or nil."
  [e]
  (when (vector? e)
    (let [[op a b c] e
          [fop fa fb] (flip e)]
      (or
       (when (and (= :is-not op) (column-ref? a) (nil? b)) {:column a :fact :not-null})
       (when (and (= :is op) (column-ref? a) (nil? b)) {:column a :fact :null})

       (when (and (= :in op) (column-ref? a) (every? literal? b)) {:column a :fact :in-set :values (vec b)})
       (when (and (= := op) (column-ref? a) (literal? b)) {:column a :fact :in-set :values [b]})
       (when (and (= :not-in op) (column-ref? a) (every? literal? b)) {:column a :fact :not-in-set :values (vec b)})
       (when (and (= :<> op) (column-ref? a) (literal? b) (not= "" b)) {:column a :fact :not-in-set :values [b]})

       (when (and (= :between op) (column-ref? a) (number? b) (number? c))
         {:column a :fact :range :min b :max c :min-exclusive? false :max-exclusive? false})
       (when (and (= :and op) (= 3 (count e)))
         (let [[lo hi] (map bound (rest e))]
           (when (and lo hi (= (:column lo) (:column hi)) (not= (contains? lo :min) (contains? hi :min)))
             (merge lo hi))))
       (bound e)

       (when (and (= :> op) (= 0 b) (vector? a) (#{:length :char_length} (first a))
                  (vector? (second a)) (#{:trim :btrim} (first (second a))) (column-ref? (second (second a))))
         {:column (second (second a)) :fact :non-blank :trim? true})
       (when (and (= :<> op) (= "" b) (vector? a) (= :btrim (first a)) (column-ref? (second a)))
         {:column (second a) :fact :non-blank :trim? true})
       (when (and (= :<> op) (= "" b) (column-ref? a))
         {:column a :fact :non-blank :trim? false})
       ;; col = btrim(col) AND col <> '': a trimmed, non-empty string
       (when (and (= :and op) (= 3 (count e)))
         (let [[[o1 c1 t] [o2 c2 s]] (rest e)]
           (when (and (= := o1) (= :<> o2) (column-ref? c1) (= c1 c2) (= t [:btrim c1]) (= "" s))
             {:column c1 :fact :non-blank :trim? true})))

       (when (and (#{:< :<= :> :>= :=} fop) (vector? fa) (length-fns (first fa))
                  (column-ref? (second fa)) (number? fb))
         (merge {:column (second fa) :fact :length :fn (first fa)}
                (case fop
                  := {:exact fb}
                  :<= {:max fb} :< {:max (dec fb)}
                  :>= {:min fb} :> {:min (inc fb)})))
       (when (and (= :between op) (vector? a) (length-fns (first a))
                  (column-ref? (second a)) (number? b) (number? c))
         {:column (second a) :fact :length :fn (first a) :min b :max c})

       (when (and (= := op) (vector? a) (= :jsonb_typeof (first a)) (column-ref? (second a)) (string? b))
         {:column (second a) :fact :json-type :json-type b})

       (when (and (#{:regex :iregex} op) (column-ref? a) (string? b))
         {:column a :fact :regex :re b :case-insensitive? (= :iregex op)})
       (when (and (#{:like :ilike} op) (column-ref? a) (string? b))
         {:column a :fact :like :pattern b :case-insensitive? (= :ilike op)})

       (when (and (= :or op) (= 3 (count e)))
         (let [is-null? (fn [x] (and (vector? x) (= :is (first x)) (nil? (nth x 2)) (column-ref? (second x))))
               [[_ col] inner] (cond (is-null? a) [a b] (is-null? b) [b a])
               m (when col (match-column inner))]
           (when (and m (= col (:column m)))
             {:column col :fact :when-present :fact-when-present (dissoc m :column)})))))))

(defn- terms [e] (if (and (vector? e) (= :and (first e))) (rest e) [e]))

(defn- dispatch-term
  "col = 'v' or col IN (...) with literal values, or col <> ALL (...) as the default branch."
  [t]
  (when (and (vector? t) (column-ref? (second t)))
    (case (first t)
      := (when (literal? (nth t 2)) {:column (second t) :values [(nth t 2)]})
      :in (when (every? literal? (nth t 2)) {:column (second t) :values (vec (nth t 2))})
      :<> (when (literal? (nth t 2)) {:column (second t) :default true})
      :not-in (when (every? literal? (nth t 2)) {:column (second t) :default true})
      nil)))

(defn- null-term [t]
  (when (and (vector? t) (= :is (first t)) (column-ref? (second t)) (nil? (nth t 2))) (second t)))

(defn- backslash-escaped
  "A LIKE pattern with escape character e as one with the default, the backslash (an empty e
   escapes nothing): (like_escape 'a!%' '!') is 'a\\%'."
  [p e]
  (if (= "\\" e)
    p
    (str/replace p (re-pattern (str "(?s)" (when (seq e) (str (java.util.regex.Pattern/quote e) "(.)|")) "\\\\"))
                 #(if-let [c (when (vector? %) (second %))] (str "\\" c) "\\\\"))))

(defn- normalize
  "The form the matchers read: PostgreSQL's rewrites undone, casts removed, LIKE ... ESCAPE
   (like_escape) as a pattern escaped with a backslash."
  [e]
  (walk/postwalk #(if (and (vector? %) (= :like_escape (first %)) (string? (second %)) (string? (last %)) (= 3 (count %)))
                    (backslash-escaped (second %) (last %))
                    %)
                 (x/strip-casts (ev/fold-casts (x/canonical e)))))

(defn- match-columns
  "All column facts of an expression, or nil if any AND branch matches nothing."
  [e]
  (if-let [m (match-column e)]
    [m]
    (when (and (vector? e) (= :and (first e)))
      (let [ms (map match-columns (rest e))]
        (when (every? some? ms) (vec (apply concat ms)))))))

(defn column-patterns
  "The column patterns of an expression, every AND branch one, the columns as keywords; nil
   when a branch matches none."
  [e]
  (some-> e normalize match-columns))

(defn- match-branches
  "CHECK whose alternatives each pin one column to literal values (or its nullness) and constrain
   other columns with column patterns. Returns {:dispatch col :branches [...] :default facts} or nil."
  [e]
  (let [alts (if (and (vector? e) (= :or (first e))) (rest e) [e])
        analyse (fn [alt]
                  (let [ts (terms alt)
                        ;; several pins of one column (a IN (1, 2) AND a = 1, as nested LIST partitions
                        ;; render) pin their common values
                        ds (keep dispatch-term ts)
                        same (filter #(= (:column (first ds)) (:column %)) ds)
                        d (when (seq same)
                            (if (and (< 1 (count same)) (every? :values same))
                              (assoc (first same) :values (vec (reduce (fn [acc vs] (filter (set vs) acc)) (:values (first same)) (map :values (rest same)))))
                              (first same)))
                        n (some null-term ts)
                        pin (or d (when n {:column n :null true}))
                        ;; pins with no common value match nothing: no branch
                        rest-facts (when (and pin (not (and (contains? pin :values) (empty? (:values pin)))))
                                     (let [others (remove #(or (some (fn [s] (= s (dispatch-term %))) same) (and (:null pin) (= n (null-term %)))) ts)
                                           fs (map match-columns others)]
                                       (when (every? some? fs) (vec (apply concat fs)))))]
                    (when (and pin rest-facts) (assoc pin :facts rest-facts))))
        bs (map analyse alts)]
    (when (and (>= (count alts) 2) (every? some? bs) (= 1 (count (distinct (map :column bs))))
               (some :values bs))
      (let [defaults (filter :default bs)]
        (when (<= (count defaults) 1)
          {:dispatch (:column (first bs))
           :branches (vec (for [b bs :when (not (:default b))]
                            (if (:null b) {:null true :facts (:facts b)} {:values (:values b) :facts (:facts b)})))
           :default (:facts (first defaults))})))))

(defn- match-alternatives
  "OR whose alternatives are each an AND of column patterns: [[fact ...] ...], or nil."
  [e]
  (when (and (vector? e) (= :or (first e)))
    (let [alts (map match-columns (rest e))]
      (when (every? some? alts) (vec alts)))))

;;; facts from columns and constraints

(defn- parsed
  "{:expr data} for an expression PostgreSQL printed, or {:unparsed fact} when it cannot be read."
  [base s]
  (let [{:keys [expr error]} (x/try-parse s)]
    (if error {:unparsed (merge base {:fact :unparsed :input s :error error})} {:expr expr})))

(defn- try-clause
  "{:expr data} for a CHECK clause PostgreSQL printed, or {:error message}."
  [s]
  (try {:expr (x/check-clause s)}
       (catch Exception e {:error (or (ex-message e) (str (class e)))})))

(defn- type-ident
  "[name array?] of a type as format_type prints it: the name the catalog keys it by, a quoted
   one unquoted, the schema of one outside the search path dropped (\"Mixed Case\"[] -> [\"Mixed Case\" true])."
  [data-type]
  (let [[_ t brackets] (re-matches #"(.*?)((?:\[\])*)" data-type)]
    [(if-let [[_ quoted] (re-matches #"(?:.*\.)?\"((?:[^\"]|\"\")*)\"" t)]
       (str/replace quoted "\"\"" "\"")
       (str/replace t #"^[^.]+\." ""))
     (boolean (seq brackets))]))

(defn- type-ref
  "The :enum or :domain-ref fact of a type the schema defines (nil for another), :array? when
   the column holds an array of it; a domain's :base is the type under all its domains."
  [base ident array? enums domains]
  (cond-> (cond (contains? enums ident) (merge base {:fact :enum :type-name ident :values (get enums ident)})
                (contains? domains ident) (merge base {:fact :domain-ref :type-name ident :base (:root (get domains ident))}))
    array? (some-> (assoc :array? true))))

(defn- column-facts [base {cname :name :keys [data_type type_schema is_nullable default_value max_length precision scale identity generated_expr position]} enums domains]
  (let [type-name (str/replace data_type #"^[^.]+\." "")
        elem-type (str/replace type-name #"\[\]$" "")
        [ident array?] (type-ident data_type)
        base (assoc base :column cname)
        ;; an array of a domain takes neither its NOT NULL nor its DEFAULT
        domain (when-not array? (get domains ident))
        ref-fact (type-ref base ident array? enums domains)
        mapped? (or ref-fact (types/known? elem-type type_schema))
        typmod (:typmod (types/entry elem-type))
        ;; a domain's NOT NULL and DEFAULT reach the columns of that type
        default (cond default_value (parsed base default_value)
                      (contains? domain :default) {:expr (:default domain)})
        generated (when generated_expr (parsed base generated_expr))
        ;; a range built from two columns needs them ordered: the generated column's own CHECK
        range-check (let [e (:expr generated) [f lo hi] (when (vector? e) e)]
                      (when (and (#{:tsrange :tstzrange :daterange :int4range :int8range :numrange} f) (keyword? lo) (keyword? hi))
                        (merge (dissoc base :column)
                               {:fact :table-check :constraint (str cname "_generated") :expr [:<= lo hi] :columns [(name lo) (name hi)]})))]
    (cond-> [(merge base {:fact :column :type data_type :position position :nullable? (boolean (and is_nullable (not (:not-null? domain))))}
                    (when (contains? default :expr) {:default (:expr default)})
                    (cond identity {:identity ({"ALWAYS" :always "BY DEFAULT" :default} identity)}
                          (and (vector? (:expr default)) (= :nextval (first (:expr default)))) {:identity :serial})
                    ;; the expression as data, or true when it could not be read: the column is generated either way
                    (when generated_expr {:generated (if (contains? generated :expr) (:expr generated) true)}))]
      (:unparsed default) (conj (:unparsed default))
      (:unparsed generated) (conj (:unparsed generated))
      range-check (conj range-check)
      ref-fact (conj ref-fact)
      ;; a type of another schema: each schema's file stands alone, so it cannot refer to it
      (not mapped?) (conj (cond-> (merge base {:fact :unknown-type :type data_type})
                            (not (#{nil "pg_catalog" (:schema base)} type_schema)) (assoc :type-schema type_schema)))
      (and max_length typmod) (conj (merge base (case typmod
                                     :max-length {:fact :max-length :max max_length}
                                     ;; bit(n) is exactly n digits, bit varying(n) at most n
                                     :bits {:fact :length :fn :length :exact max_length}
                                     :max-bits {:fact :length :fn :length :max max_length})))
      (and precision (= "numeric" data_type)) (conj (merge base {:fact :numeric :precision precision :scale scale})))))

(defn- key-facts [base {cname :name :keys [type columns references nulls_not_distinct is_valid is_enforced index partial exclude]}]
  (case type
    "EXCLUDE" [(merge base {:fact :exclude :constraint cname :method (:method exclude) :partial? (boolean (:partial exclude))
                            :elements (mapv #(select-keys % [:column :op]) (:elements exclude))})]
    "PRIMARY KEY" [(merge base {:fact :primary-key :constraint cname :columns columns})]
    "UNIQUE" [(merge base {:fact :unique :constraint cname :columns columns :nulls-distinct? (not nulls_not_distinct) :index? (boolean index) :partial? (boolean partial)})]
    ;; NOT ENFORCED: the database never checks it, so it is not a reference, only noted
    "FOREIGN KEY" (if (false? is_enforced)
                    [(merge base {:fact :not-enforced :constraint cname :input (str "FOREIGN KEY " (pr-str columns))})]
                    [(cond-> (merge base {:fact :references :constraint cname :columns columns
                                          :match (if (= "FULL" (:match references)) :full :simple)
                                          :to (select-keys references [:schema :table :columns])})
                       (false? is_valid) (assoc :valid? false))])
    []))

(defn- citext? [type-name] (boolean (re-find #"(?:^|\.)citext$" (str/replace (str type-name) #"\(.*\)$" ""))))

(defn- citext-fact
  "A column pattern over a citext value as citext reads it: ~ and LIKE ignoring case; nil for
   one citext compares otherwise (=, IN, a range), which pgmalli does not evaluate."
  [f]
  (let [present? (= :when-present (:fact f))
        inner (if present? (:fact-when-present f) f)]
    (when (#{:regex :like :length :non-blank :not-null :null} (:fact inner))
      (let [inner (cond-> inner (#{:regex :like} (:fact inner)) (assoc :case-insensitive? true))]
        (if present? (assoc f :fact-when-present inner) inner)))))

(defn- uncast-columns
  "The columns an expression reads other than through a cast to another type: a citext column
   cast to text compares as text (PostgreSQL also writes the cast when citext's operators were
   not on the search path)."
  [e]
  (cond (and (vector? e) (= :cast (first e)) (keyword? (second e)))
        (if (and (keyword? (last e)) (citext? (name (last e)))) #{(second e)} #{})
        (vector? e) (into #{} (mapcat uncast-columns (rest e)))
        (sequential? e) (into #{} (mapcat uncast-columns e))
        (keyword? e) #{e}
        :else #{}))

(def ^:private citext-error "it compares a citext value, which ignores case where pgmalli's comparisons do not")

(defn- domain-facts
  "The :domain fact, then a :domain-check (or :unparsed) for every CHECK that matched no pattern."
  [schema-name enums roots [tname {:keys [base_type not_null default constraints]}]]
  (let [base {:schema schema-name :type-name tname}
        ;; over an enum or a domain of the schema: what that type holds, its CHECKs included
        over (let [[ident array?] (type-ident base_type)]
               (some-> (type-ref {} ident array? enums roots) (select-keys [:fact :type-name :base :array?])))
        root (:root (get roots tname))
        value-type (ev/column-type {:pg/type (str/replace root #"\(.*\)$" "") :pg/max-length (some-> (re-find #"\((\d+)\)$" root) second parse-long)})
        checks (for [{cname :name :keys [definition is_valid]} constraints
                     :let [{:keys [expr error]} (try-clause definition)
                           citext-value? (and (citext? root) (contains? (uncast-columns (some-> expr x/canonical)) :VALUE))
                           ;; NOT VALID: existing values may violate it, so it is reported, not applied;
                           ;; over char(n) the patterns, which read text, do not apply
                           ms (when (and (not= false is_valid) (not (vector? value-type))) (some-> expr normalize match-columns))
                           ms (if citext-value? (let [cs (map citext-fact ms)] (when (every? some? cs) cs)) ms)
                           check (when expr (merge base {:constraint cname :sql definition :expr (x/canonical expr)}))]]
                 (cond ms {:facts (map #(merge check (dissoc % :column)) ms)}
                       (and expr citext-value?) {:check (merge base {:fact :unparsed :constraint cname :input definition :error citext-error})}
                       expr {:check (cond-> (assoc check :fact :domain-check) (false? is_valid) (assoc :valid? false))}
                       :else {:check (merge base {:fact :unparsed :constraint cname :input definition :error error})}))
        dflt (when default (parsed base default))
        ;; the base type's typmod: numeric(12,2), varchar(80)
        typmod (let [[_ p s] (re-find #"^numeric\((\d+)(?:,(-?\d+))?\)" base_type)
                     [_ n] (re-find #"^(?:character varying|character|varchar|bpchar|char)\((\d+)\)" base_type)]
                 (cond p [(merge base {:fact :numeric :precision (parse-long p) :scale (if s (parse-long s) 0)})]
                       n [(merge base {:fact :max-length :max (parse-long n)})]))]
    (into [(cond-> (merge base {:fact :domain :base base_type :not-null? (boolean not_null) :facts (vec (concat (some-> over vector) typmod (mapcat :facts checks)))})
             (contains? dflt :expr) (assoc :default (:expr dflt))
             value-type (assoc :value-type value-type))]
          (concat (keep :check checks) (some-> (:unparsed dflt) vector)))))

(defn- substitute
  "The expression with the columns of subst (keyword -> expression) replaced by their
   expressions, everywhere but at the head of a form (an operator or function)."
  [e subst]
  (cond (vector? e) (into [(first e)] (map #(substitute % subst) (rest e)))
        (keyword? e) (get subst e e)
        (sequential? e) (map #(substitute % subst) e)
        :else e))

(defn- check-facts
  "The facts of a CHECK. One on a char(n) column (in bpchar) matches no pattern: the patterns
   read text, and char(n) compares and measures without its trailing spaces. On a citext
   column (in citext) read without a cast a regex or LIKE ignores case, and a CHECK comparing it otherwise is not
   evaluated."
  [base {cname :name :keys [check_clause is_valid is_enforced]} generated bpchar citext]
  (let [base (assoc base :constraint cname :sql check_clause)
        stringify (fn [m] (walk/postwalk #(if (and (map? %) (keyword? (:column %))) (update % :column name) %) m))
        parsed (try-clause check_clause)]
    (cond
      ;; NOT ENFORCED: the database never checks it, so nothing is rendered; it is noted
      (false? is_enforced) [(merge base {:fact :not-enforced :input check_clause})]
      (contains? parsed :expr)
      (let [;; a generated column holds its expression's value: the CHECK is on that
            e (substitute (:expr parsed) generated)
            citext (set (filter (comp (uncast-columns (x/canonical e)) keyword) citext))
            n (when (and (not= false is_valid) (not-any? bpchar (referenced-columns e))) (normalize e))
            base (assoc base :expr (x/canonical e))]
        (if-let [ms (some->> (some-> n match-columns)
                             (map #(if (citext (name (:column %))) (citext-fact %) %))
                             (#(when (every? some? %) %)))]
          (mapv #(merge base (stringify %)) ms)
          (if-let [b (when-not (some citext (referenced-columns e)) (some-> n match-branches))]
            [(merge base {:fact :branch-check} (stringify b) {:dispatch (name (:dispatch b))})]
            (if-let [alts (some-> n match-alternatives)]
              [(merge base {:fact :or-check :alternatives (stringify alts)})]
              (if (some citext (referenced-columns e))
                [(merge base {:fact :unparsed :input check_clause :error citext-error})]
                [(cond-> (merge base {:fact :table-check :columns (referenced-columns e)})
                   (false? is_valid) (assoc :valid? false))])))))
      :else [(merge base {:fact :unparsed :input check_clause :error (:error parsed)})])))

;;; API

(defn facts
  "Facts of one schema, ordered by type name, table name, column name and constraint name.
   Column positions are not used for ordering because they depend on migration order."
  [schema]
  (let [schema-name (:name schema)
        enums (into (sorted-map) (keep (fn [[n t]] (when (= "ENUM" (:kind t)) [n (vec (:enum_values t))])) (:types schema)))
        domain-types (sort-by key (filter (comp #{"DOMAIN"} :kind val) (:types schema)))
        roots (into {} (for [[n] domain-types]
                         [n {:root (loop [b (get-in schema [:types n :base_type])]
                                     (let [[i] (type-ident b)] (if (= "DOMAIN" (get-in schema [:types i :kind])) (recur (get-in schema [:types i :base_type])) b)))}]))
        domain-facts* (mapcat #(domain-facts schema-name enums roots %) domain-types)
        declared (into {} (for [f domain-facts* :when (= :domain (:fact f))] [(:type-name f) (merge (select-keys f [:base :not-null? :default]) (get roots (:type-name f)))]))
        ;; a domain over a domain is NOT NULL when a domain under it is
        domains (update-vals declared (fn [d] (assoc d :not-null? (boolean (some :not-null? (take-while some? (iterate #(get declared (first (type-ident (:base %)))) d)))))))]
    (vec
     (concat
      (for [[n vs] enums] {:fact :enum-type :schema schema-name :type-name n :values vs})
      domain-facts*
      (for [[tname t] (sort-by key (:tables schema))
            :let [base {:schema schema-name :table tname}
                  view? (contains? #{"VIEW" "MATERIALIZED VIEW"} (:kind t))
                  constraints (sort-by :name (vals (:constraints t)))]
            f (concat (when view? [(assoc base :fact :view :materialized? (= "MATERIALIZED VIEW" (:kind t)))])
                      (for [tr (:triggers t)] (merge base {:fact :trigger} (select-keys tr [:name :before :insert :update :delete])))
                      ;; nothing marks a view's column NOT NULL in the catalog, so every one may be NULL
                      (mapcat #(column-facts base (cond-> % view? (assoc :is_nullable true)) enums domains) (sort-by :name (:columns t)))
                      (mapcat #(key-facts base %) constraints)
                      (let [generated (into {} (for [c (:columns t) :when (:generated_expr c)
                                                     :let [{:keys [expr]} (parsed base (:generated_expr c))] :when (some? expr)]
                                                 [(keyword (:name c)) expr]))]
                        (concat (mapcat #(check-facts base % generated
                                                      (set (for [c (:columns t) :when (#{"character" "bpchar"} (:data_type c))] (:name c)))
                                                      (set (for [c (:columns t) :let [[i] (type-ident (:data_type c))]
                                                                 :when (or (citext? i) (citext? (get-in roots [i :root])))]
                                                             (:name c))))
                                        (filter #(= "CHECK" (:type %)) constraints))
                                ;; a domain on a generated column checks the expression's value
                                (for [c (:columns t)
                                      :let [expr (get generated (keyword (:name c)))
                                            [type-name array?] (type-ident (:data_type c))]
                                      :when (and expr (not array?) (contains? domains type-name))
                                      k (get-in schema [:types type-name :constraints])
                                      f (check-facts base {:name (str (:name c) " " (:name k)) :check_clause (:definition k) :is_valid (:is_valid k true)}
                                                     {:VALUE expr} #{} #{})]
                                  f))))]
        f)))))

