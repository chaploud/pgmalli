(ns pgmalli-next.impl.problems
  "The problems analyze reports of a query (README.md's table of :kind)."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [pgmalli-next.impl.accept :as accept]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.hsyntax :as syntax]
            [pgmalli-next.impl.query :as query]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(defn- output-column?
  "Whether a bare :col of :order-by or :group-by may mean an output column of stmt."
  [stmt col format]
  (let [[a c] (syntax/split-column col format)
        opts {:format format}]
    (boolean (and (not a) (some #(or (= c (syntax/item-name % opts)) (some (comp #{c} first) (syntax/over-columns (syntax/item-expression %) opts)))
                                (syntax/selected-items stmt))))))

(defn- output-statement
  "The statement whose select names a statement's output columns: itself, or the first member
   of a set operation (UNION ...)."
  [stmt]
  (if-let [members (some stmt syntax/set-operation-keys)]
    (when (and (vector? members) (map? (first members))) (output-statement (first members)))
    stmt))

(defn- untold-outputs?
  "Whether stmt's select may make output names not read here: a :raw item, or, when stars?
   (the first member of a set operation, whose output names are all ORDER BY sees), a star."
  [stmt stars?]
  (boolean (some #(let [e (syntax/item-expression %)]
                    (or (and (vector? e) (= :raw (first e)))
                        (and stars? (some-> (first (syntax/select-parts %)) syntax/star?))))
                 (syntax/selected-items stmt))))

(defn- ordering-columns
  "The input columns :order-by and :group-by name as they are (:col, or [:col :asc]);
   expressions there are not read. As PostgreSQL reads them, a bare name in :order-by is an
   output column first (of a set operation, its first member's), one in :group-by an input
   column first."
  [registry sc stmt]
  (let [out (output-statement stmt)
        format (::query/format registry)
        output? (fn [s c stars?] (or (output-column? s c format) (and (nil? (first (syntax/split-column c format))) (untold-outputs? s stars?))))]
    (concat (for [c (syntax/vectorish (:group-by stmt))
                  :when (and (syntax/column-keyword? c format) (not (and (output? stmt c false) (empty? (query/column-hits registry sc c)))))]
              c)
            (when out
              (let [;; t.* outputs each column of t under its name
                    starred (for [i (syntax/selected-items out) :let [[col] (syntax/select-parts i)]
                                  :when (and col (syntax/star? col))
                                  :let [[a] (syntax/split-column col format)] :when a]
                              a)
                    star-output? (fn [c] (let [[a n] (syntax/split-column c format)]
                                           (and (nil? a) (some #(query/resolve-column registry sc (keyword (str % "." n))) starred))))]
                (for [o (syntax/vectorish (:order-by stmt)) :let [c (syntax/bare o)]
                      :when (and (syntax/column-keyword? c format) (not (output? out c (not= out stmt))) (not (and (= out stmt) (star-output? c))))]
                  c))))))

(defn- whole-row?
  "Whether an unqualified :x names a table of the scope rather than a column: its whole row."
  [scope col format]
  (let [[a column] (syntax/split-column col format)]
    (boolean (and (nil? a) (some #(and (map? %) (contains? % column)) (syntax/chain scope))))))

(defn- near-columns
  "Up to three columns of the scope's tables spelled close to col's name, qualified as col is:
   what it likely meant."
  [registry scope col]
  (let [[a c] (syntax/split-column col (::query/format registry))
        ;; a qualified name looks in the innermost table of its alias, which hides outer ones
        scopes (filter map? (syntax/chain scope))
        scopes (if a (take 1 (filter #(contains? % a) scopes)) scopes)
        names (distinct (for [sc scopes
                              [alias {:keys [table]}] sc :when (and table (or (nil? a) (= a alias)))
                              n (keys (query/table-columns registry table)) :when (not (query/system-columns n))]
                          n))]
    (some->> (error/suggestions c names 3) (mapv #(keyword (if a (str a "." %) %))))))

(defn- near-in
  "Up to three columns of table spelled close to column name n."
  [registry table n]
  (some->> (error/suggestions n (remove query/system-columns (keys (query/table-columns registry table))) 3) (mapv keyword)))

(defn- unknown-qualifier
  "[qualifier meant] when no table or alias in scope has col's qualifier, nil otherwise. meant
   is col's column qualified by the aliases in scope whose table has it and whose alias or table
   name is the qualifier or two edits from it, closest first: an aliased table named by its
   name, as PostgreSQL's \"Perhaps you meant to reference the table alias\", or a misspelt alias."
  [registry scope col]
  (let [[a c] (syntax/split-column col (::query/format registry))
        scopes (filter map? (syntax/chain scope))]
    (when (and a (not (some #{:open} (syntax/chain scope))) (not-any? #(contains? % a) scopes))
      (let [meant (for [sc scopes
                        [alias {:keys [table order]}] sc
                        :when (or (and table (contains? (query/table-columns registry table) c)) (some #{c} order))
                        :let [d (apply min (map #(error/edit-distance a %) (remove nil? [alias (some-> table syntax/table-name)])))]
                        :when (<= d 2)]
                    [(keyword (str alias "." c)) d])]
        [a (some->> (seq meant) (sort-by second) (map first) distinct (take 3) vec)]))))

(def ^:private sort-directions
  "The directions HoneySQL writes after an ORDER BY item ([:score :desc])."
  #{:asc :desc :nulls-first :nulls-last :asc-nulls-first :asc-nulls-last :desc-nulls-first :desc-nulls-last})

(defn- listed-expressions
  "The expressions x lists where HoneySQL writes a list of ORDER BY's items, each expr or [expr
   direction], no call: a window's :partition-by and :order-by ({:partition-by [:a]}, in OVER or
   WINDOW), an aggregate's [:order-by e items...] and [:distinct e] (HoneySQL writes no more), a frame's bound
   [offset :preceding], and the elements of [:array [e ...]]; nil for another x."
  [x clause]
  (cond (and (map? x) (some #{:partition-by :order-by} (keys x)) (not (syntax/statement? x)))
        (map syntax/bare (mapcat #(syntax/vectorish (get x %)) [:partition-by :order-by]))
        (not (vector? x)) nil
        (= :order-by (first x)) (cons (second x) (map syntax/bare (drop 2 x)))
        (= :distinct (first x)) [(second x)]
        (and (= :array (first x)) (sequential? (second x))) (second x)
        (and (= :frame clause) (= 2 (count x)) (#{:preceding :following} (second x))) [(first x)]
        (and (= 2 (count x)) (keyword? (second x)) (sort-directions (keyword (str/lower-case (name (second x)))))) [(first x)]))

(defn- column-problems [registry sc stmt ps {:keys [format] :as opts}]
  (let [target (syntax/target-scope sc stmt opts)
        assigned (when (seq target) (concat (map #(or (syntax/field-column %) %) (filter syntax/column-keyword? (:columns (syntax/insert-parts stmt))))
                                            (filter syntax/column-keyword? (map (comp syntax/target-column first) (syntax/writes stmt)))))
        ;; a target a.b as written (writes has it as its column a): field b of a column a to PostgreSQL
        written (:written (syntax/insert-parts stmt))
        ;; a keyword assigned in SET, which HoneySQL writes as a column: the clause and the target column
        set-values (into {} (for [{:keys [x clause assign]} (syntax/assignments ps)
                                  :when (and (syntax/assignment-clauses clause) (syntax/column-keyword? x format))]
                              [x {:clause clause :target assign}]))
        dotted (into {} (for [[clause ts] [[:columns (:columns (syntax/insert-parts stmt))]
                                           [:values (let [v (:values stmt)] (when (vector? v) (mapcat keys (filter map? v))))]
                                           [:set (let [st (:set stmt)] (when (map? st) (keys st)))]
                                           [:do-update-set (let [d (:do-update-set stmt)] (when (map? d) (keys d)))]]
                              t ts
                              :let [c (syntax/field-column t)] :when c]
                          [c [t clause]]))]
    (for [[col scope] (distinct (concat (for [c (keep #(first (syntax/select-parts % opts)) (syntax/selected-items stmt))] [c sc])
                                        (for [c (ordering-columns registry sc stmt)] [c sc])
                                        ;; read as ORDER BY reads them: a bare name an output column first
                                        (for [c (syntax/distinct-on-expressions stmt) :when (and (syntax/column-keyword? c format) (not (output-column? stmt c format)))] [c sc])
                                        (for [[_ c _ chain] (syntax/comparison-sides ps) :when (syntax/column-keyword? c format)] [c chain])
                                        (for [{:keys [x chain]} (syntax/expression-nodes ps)
                                              :when (and (vector? x) (= 3 (count x)) (#{:is :is-not} (first x))
                                                         (syntax/column-keyword? (second x) format))]
                                          [(second x) chain])
                                        ;; a column a call's argument names ([:upper :email]), in a clause of expressions;
                                        ;; an item of SELECT, ORDER BY or RETURNING is [expression alias|direction], no call
                                        ;; the value of a list clause ([:a :b] of ORDER BY) is its items, no call
                                        (for [{:keys [x chain item? clause expression?]} (syntax/expression-nodes ps)
                                              :let [items? (or (syntax/select-list-clauses clause) (= :order-by clause))]
                                              :when (and expression? (not (and item? items?)))
                                              :let [listed (listed-expressions x clause)
                                                    ;; [:cast x t]: x alone, t a type's name
                                                    cast? (and (vector? x) (= :cast (first x)) (= 3 (count x)))
                                                    [kind _ args] (cond listed [:order [] listed]
                                                                        cast? [:cast [] [(second x)]]
                                                                        :else (when-not (syntax/comparison? x) (syntax/call-parts x)))]
                                              :when kind
                                              a args
                                              ;; ROLLUP's, CUBE's and GROUPING SETS' items are GROUP BY's: an output column too
                                              :when (and (syntax/column-keyword? a format)
                                                         (not (and (= :group-by clause) (vector? x) (syntax/grouping-sets (first x))
                                                                   (output-column? stmt a format)
                                                                   (empty? (query/column-hits registry chain a)))))]
                                          [a chain])
                                        ;; a keyword assigned in SET is a column the statement sees
                                        (for [{:keys [x chain clause]} (syntax/assignments ps)
                                              :when (and (syntax/assignment-clauses clause) (syntax/column-keyword? x format))]
                                          [x chain])
                                        (for [c assigned] [c target])))
          ;; an assignment's target names a column, never a table: SET users.score is field score of a column users
          :when (if (or (syntax/star? col) (and (not (identical? scope target)) (whole-row? scope col format)))
                  ;; :t.* names a table of the scope, unless the data does not show them all
                  (let [[a] (syntax/split-column col format)]
                    (and (syntax/star? col) a (not (some #{:open} (syntax/chain scope))) (not-any? #(and (map? %) (contains? % a)) (syntax/chain scope))))
                  (nil? (query/resolve-column registry scope col)))
          :let [hits (query/column-hits registry scope col)]]
      (if (< 1 (count hits))
        (if (apply = (map first hits))
          ;; one subquery's or CTE's columns of that name
          {:kind :ambiguous-column :column col :candidates [(ffirst hits)]}
          {:kind :ambiguous-column :column col :candidates (mapv (fn [[alias table]] (or table alias)) hits)})
        (let [[qualifier meant] (unknown-qualifier registry scope col)
              ;; a table of the statement an ON inside a parenthesized join does not see
              outside? (and qualifier (contains? (first sc) qualifier) (not (contains? (first scope) qualifier)))
              near (cond outside? nil qualifier meant :else (near-columns registry scope col))]
          (cond-> {:kind :unknown-column :column col}
            (and (set-values col) (not qualifier)) (merge (set-values col))
            qualifier (assoc :qualifier qualifier)
            outside? (assoc :outside-join true)
            near (assoc :did-you-mean near)
            ;; a target a.b whose a is the table or its alias: field b of a column a to PostgreSQL
            (and (identical? scope target) (dotted col) (whole-row? scope col format))
            (-> (dissoc :did-you-mean)
                (merge (let [[t clause] (dotted col)
                             field (last (str/split (name t) #"\."))
                             table (some #(when (map? %) (:table (get % (second (syntax/split-column col format))))) (syntax/chain scope))
                             meant (if (contains? (query/table-columns registry table) field) [(keyword field)] (near-in registry table field))]
                         (cond-> {:column (get written t t) :clause clause} meant (assoc :did-you-mean meant)))))
            (and (syntax/quoted? format) (not= (syntax/split-column col format) (syntax/split-column col nil))
                 (some? (query/resolve-column registry scope (keyword (str/join "." (remove nil? (syntax/split-column col nil)))))))
            (assoc :quoted true)))))))

(defn- insert-problems [registry stmt {:keys [format] :as opts}]
  (let [{:keys [table columns]} (syntax/insert-parts stmt)
        [table] (some-> table (syntax/table-ref opts))]
    (when table
      (let [values (:values stmt)
            rows (when (vector? values) values)
            ;; HoneySQL inserts the union of the columns of all value maps, NULL where a row lacks one
            given (or columns (when (map? (first rows)) (distinct (mapcat keys (filter map? rows)))))
            given (when (every? keyword? given) given)
            given-names (into #{} (map #(syntax/ident-name % format)) given)
            required (set (for [[k p] (some-> (get (::query/schemas registry) (shape/derived-name (shape/table-key table) "insert")) shape/column-entries)
                                :when (not (:optional p))]
                            (name k)))]
        (concat
         (when columns
           (for [[i row] (map-indexed vector (filter vector? rows)) :when (not= (count row) (count columns))]
             {:kind :values-arity :table table :row i :columns (count columns) :values (count row)}))
         (when (and (seq given) (not (shape/filled-by-trigger? (query/table-props registry table) :insert)))
           (for [c required :when (not (given-names c))]
             {:kind :missing-required-column :table table :column (keyword c)}))
         ;; ON CONFLICT (col, ...) names columns of the target table; a vector headed by :on-constraint
         ;; is two columns to HoneySQL, ON CONFLICT (on_constraint, name)
         (when-let [cols (query/table-columns registry table)]
           (let [oc (syntax/vectorish (:on-conflict stmt))]
             (if (= :on-constraint (first oc))
               [{:kind :unknown-column :column :on-constraint :clause :on-conflict :table table :constraint (second oc)}]
               (for [k oc
                     :let [n (when (keyword? k) (syntax/ident-name k format))]
                     :when (and n (not (contains? cols n)))]
                 (cond-> {:kind :unknown-column :column k :clause :on-conflict :table table}
                   (near-in registry table n) (assoc :did-you-mean (near-in registry table n)))))))
         ;; HoneySQL writes a keyword value as a column, which a VALUES row has none of in scope
         (distinct (for [row rows
                         [target v] (cond (map? row) row (vector? row) (map-indexed (fn [i v] [(get (vec columns) i) v]) row))
                         :when (and (keyword? v) (not (syntax/value-keyword? v format)))]
                     (cond-> {:kind :unknown-column :column v :clause :values :table table}
                       target (assoc :target target)))))))))

(defn- write-problems
  "Writes PostgreSQL refuses: NULL into a NOT NULL column, a map or a vector of values into a
   column of a type pgmalli types (:unlifted-value), and a value other than DEFAULT into a column only
   the database writes (generated, or identity ALWAYS without OVERRIDING SYSTEM
   or USER VALUE, which the first writes and the second ignores). A column one map of :values
   lacks and another has is NULL, as HoneySQL writes it, DEFAULT under sql/format's
   :values-default-columns (in :format). Under a column list, which map keys reach which column
   depends on the HoneySQL version (2.8.85 follows the list): missing keys are not judged there."
  [registry scopes stmt {:keys [format] :as opts}]
  (let [target (syntax/target-scope scopes stmt opts)
        i (:insert-into stmt)
        overriding? (and (vector? i) (map? (first i)) (#{:system :user} (:overriding-value (first i))))
        defaults (set (:values-default-columns format))]
    (when (seq target)
      (distinct
       (for [[col v how clause] (syntax/writes stmt #(when (defaults %) [:default]))
             :when (syntax/column-keyword? col)
             :let [{:keys [table] s :schema} (query/resolve-column registry target col)
                   cp (some-> s shape/column-props)
                   p (cond (or (nil? s) (syntax/default-value? v format)) nil
                           (and (shape/written-by-database? cp)
                                (not (and overriding? (= :insert how) (= :always (:pg/identity cp)))))
                           {:kind :read-only-column :table table :column col}
                           (and (syntax/null-value? v format) (not (shape/maybe? s)) (not (shape/filled-by-trigger? (query/table-props registry table) how)))
                           {:kind :not-null-violation :table table :column col :clause clause}
                           ;; HoneySQL writes a map as a subquery and a vector of values as a row, (?, ?), which
                           ;; a column of a type accept answers for takes not (one of a row type may take the row,
                           ;; one of a string type the row's text)
                           (and (or (and (map? v) (not (syntax/statement? v)) (not (contains? v :values)))
                                    ;; [], which HoneySQL writes ()
                                    (= [] v)
                                    (and (vector? v) (next v) (not (keyword? (first v))) (not (accept/string-type? (query/slot-type registry s)))))
                                (query/slot-type registry s))
                           {:kind :unlifted-value :table table :column col :clause clause :value v :type (query/cast-type-name s opts)})]
             :when p]
         p)))))

(defn- operator-problems
  "Of each comparison of a column or an expression of a domain over an enum type, which
   PostgreSQL compares with nothing (dmood = unknown, dmood = mood and dmood = dmood do not
   exist): :no-operator, with the enum type to cast it to."
  [registry ps opts]
  (distinct
   (for [[op side other chain] (syntax/comparison-sides ps)
         ;; PostgreSQL reads IS [NOT] DISTINCT FROM NULL as IS [NOT] NULL
         :when (not (or (syntax/null-test [op side other] (:format opts))
                        (and (syntax/distinct-ops op) (syntax/null-value? other (:format opts)))))
         :let [s (query/side-schema registry chain side opts)
               t (when s (query/slot-type registry s))]
         :when (and (vector? t) (:domain? (get t 2)))]
     {:kind :no-operator :column side :op op :domain (query/domain-of s) :type (query/cast-type-name s opts)})))

(defn- type-problems
  "{:kind :unknown-type :type n :did-you-mean [...]} of each cast to an unqualified type name n
   that names no type pg_catalog or the :schema has, but an enum type of another schema
   (query/type-suggestions)."
  [registry ps {:keys [schema] :as opts}]
  (distinct
   (for [{:keys [x]} (syntax/expression-nodes ps)
         :when (and (vector? x) (= :cast (first x)) (= 3 (count x)))
         :let [n (syntax/cast-target (nth x 2))]
         :when (and n (not (str/includes? n ".")) (nil? (query/named-type-schema registry opts n)))
         :let [e (types/element n)
               meant (some->> (query/type-suggestions registry (or e n) schema) (mapv #(cond-> % e types/array-of)))]
         :when meant]
     {:kind :unknown-type :type n :did-you-mean meant})))

(defn- column-definition-problems
  "{:kind :column-definition-list :function f :alias a :columns [c ...]} for each function in FROM of a statement
   whose alias writes a column definition list ({:with-columns ...}) where no candidate of it
   returns record, as PostgreSQL takes one for alone: of no type, a list of names
   ([:r {:columns [:v]}]) renames its columns. columns, the names the list gives."
  [registry stmt opts]
  (for [item (syntax/table-items stmt)
        :when (syntax/column-definitions item)
        :let [[f al] (syntax/function-alias item)
              [kind n :as parts] (syntax/call-parts f)
              os (when (= :functions kind) (query/reached registry opts parts))]
        :when (and (seq os) (not-any? #(= "record" (:result %)) os))]
    {:kind :column-definition-list :function n :alias (syntax/alias-label al opts)
     :columns (mapv #(keyword (syntax/ident-name (first %) (:format opts))) (syntax/column-definitions item))}))

(defn- problems
  "The problems of one statement (README.md's table of :kind), scope the statement's (from
   scope) or, for a nested statement, the chain of its own and the enclosing ones."
  [registry stmt scope ps opts]
  (let [scopes (syntax/chain scope)]
    (vec (concat (for [table (distinct (for [[_ {:keys [table unknown?]}] (first scopes) :when unknown?] table))
                       :let [meant (query/table-suggestions registry table)]]
                   (cond-> {:kind :unknown-table :table table}
                     meant (assoc :did-you-mean meant)
                     (query/quoted-table? registry table (:format opts)) (assoc :quoted true)))
                 (column-problems registry scopes stmt ps opts)
                 ;; a column both sides lack is one problem
                 (for [[column ms] (group-by :column (query/using-missing registry stmt (first scopes) opts))
                       :let [right (some :table ms)
                             side (if (next ms) :both (:side (first ms)))
                             left (for [[_ {:keys [table]}] (first scopes) :when (and table (not= table right))] table)
                             meant (some->> (concat (when (not= :left side) [right]) (when (not= :right side) left))
                                            (keep identity) (mapcat #(near-in registry % (name column))) distinct (take 3) seq vec)]]
                   (cond-> {:kind :unknown-column :column column :clause :using :side side}
                     right (assoc :table right)
                     meant (assoc :did-you-mean meant)))
                 (insert-problems registry stmt opts)
                 (write-problems registry scopes stmt opts)
                 (operator-problems registry ps opts)
                 (type-problems registry ps opts)
                 (column-definition-problems registry stmt opts)))))

(defn- unread-clauses
  "{:kind :unread-clause :clause k} for a clause the query uses that is read nowhere here, so
   what it does goes unjudged: in the query map and every statement, and in a map of
   HoneySQL's clauses alone ({:table :t}, a clause registered with register-clause!). The rows of
   :values, :set and :do-update-set are maps of columns, not of clauses, though a subquery in
   them is read, as is the column definition list of a function's alias (syntax/column-definitions)."
  [body]
  (let [clauses (set (sql/clause-order))
        rows (set (for [st (syntax/statements body)
                        r (concat (when (vector? (:values st)) (:values st)) [(:set st) (:do-update-set st)]
                                  (for [item (syntax/table-items st) :when (syntax/column-definitions item)] (nth (syntax/function-alias item) 2)))
                        :when (map? r)]
                    r))
        maps (tree-seq syntax/branch? seq body)]
    (distinct
     (for [m maps
           ;; the query itself is clauses, however unknown ({:selct [:id]})
           :when (and (map? m) (seq m) (not (rows m)) (or (identical? m body) (syntax/statement? m) (every? clauses (keys m))))
           k (keys m)
           :when (syntax/unread-clause? k)
           :let [meant (when-not (contains? clauses k)
                         (error/suggest k (concat syntax/read-clauses syntax/ignored-clauses clauses)))]]
       (cond-> {:kind :unread-clause :clause k} meant (assoc :did-you-mean [meant]))))))

(defn- return-key-problems
  "pgjdbc quotes the names of :return-keys as written (its quoteReturningIdentifiers, true by
   default): one that is a column of the target only folded (:group-id for group_id) is none."
  [registry stmt scopes {{:keys [return-keys]} :jdbc :as opts}]
  (when (and (sequential? return-keys) (map? stmt) (not= stmt (query/sent stmt opts)))
    (let [[_ alias] (some-> (first (syntax/write-targets stmt)) (syntax/table-ref opts))
          {:keys [table opaque?]} (get (first (syntax/chain scopes)) alias)
          cs (when (and table (not opaque?)) (query/table-columns registry table))]
      (for [k return-keys :let [n (name k)]
            :when (and cs (not (contains? cs n)) (contains? cs (syntax/folded n)))]
        {:kind :unknown-column :column (keyword n) :did-you-mean [(keyword (syntax/folded n))]}))))

(defn- written-otherwise
  "{:kind :written-otherwise ...} of each call HoneySQL writes otherwise than its data reads:
   [:in x a b], whose arguments past a it drops (x IN a), and a name of an operator it has not
   registered ([:-> a b] without honey.sql.pg-ops), which it writes as a call of a function of
   that name (->(a, b)) PostgreSQL has not, and a vector headed by a named parameter
   ([:?x :?y]) an expression or a written value is, which it writes as a call (??X(?))."
  [body]
  (distinct
   (for [x (concat (tree-seq syntax/branch? seq body)
                   ;; a written value, as an operand
                   (for [st (syntax/statements body) [_ v] (syntax/writes st (constantly nil))] [::written v]))
         :when (and (vector? x) (keyword? (first x)))
         :let [h (first x)
               n (name h)]
         p (concat
            ;; an operand of an operator (=, IN) or a written value
            (when (and (not (map-entry? x)) (or (= ::written h) (syntax/in-ops h) (syntax/operator-name? h)))
              (for [a (rest x) :when (and (vector? a) (some->> (syntax/param-key (first a)) name (re-find #"^[A-Za-z_]")))]
                {:kind :written-otherwise :param (first a)}))
            [(when (and (syntax/in-ops h) (< 3 (count x)))
              {:kind :written-otherwise :op h :dropped (- (count x) 3)})
            ;; HoneySQL escapes a ? of a name as ??
            (when (and (syntax/operator-name? h) (< 2 (count x))
                       (some-> (try (first (sql/format-expr (into [h] (repeat (dec (count x)) ::x)))) (catch Exception _ nil))
                               (str/starts-with? (str (str/replace n "?" "??") "("))))
              {:kind :written-otherwise :op h :call true})])
         :when p]
     p)))

(def ^:private select-keys-of-a-query
  [:select :select-distinct :select-distinct-on :union :union-all :intersect :except :table])

(def ^:private select-first?
  "Whether the loaded HoneySQL writes a statement of write clause k (:insert-into, :update,
   :delete-from) and a SELECT of it beside as the SELECT first (SELECT x INSERT INTO t ...)."
  (memoize (fn [k s] (boolean (some-> (try (first (sql/format {k :t s (if (= :table s) :u [:x])})) (catch Exception _ nil))
                                      str/upper-case (str/starts-with? "SELECT"))))))

(defn- written-first
  "{:kind :written-otherwise :clause write :before select} of each statement of a write clause and
   a query's clause beside, which HoneySQL writes the query first of, a statement PostgreSQL reads
   otherwise (SELECT ... INTO) or not at all."
  [body]
  (distinct
   (for [st (syntax/statements body)
         w [:insert-into :update :delete-from]
         :when (contains? st w)
         s select-keys-of-a-query
         :when (and (contains? st s) (select-first? w s))]
     {:kind :written-otherwise :clause w :before s})))

(defn- written-inline
  "{:kind :written-otherwise :op :in :lifted true} of each IN of a [:lift coll], which sql/format's
   :inline writes as the collection's text (IN [1, 2]), no list."
  [body format]
  (when (:inline format)
    (distinct (for [x (tree-seq syntax/branch? seq body)
                    :when (and (vector? x) (syntax/in-ops (first x)) (syntax/lifted-coll? (nth x 2 nil)))]
                {:kind :written-otherwise :op (first x) :lifted true}))))

(defn- written-keywords
  "{:kind :written-otherwise :column k} of each column a write names by keyword k (:values,
   :set, :columns, the target's list) that HoneySQL writes unquoted as a word PostgreSQL reads as a keyword (order,
   user), not as the column, or by a string it writes as another name."
  [body format]
  (distinct
   (concat
    ;; a string in a statement's :columns, which HoneySQL writes as parameters, one per character
    (for [st (syntax/statements body) :when (sequential? (:columns st)) k (:columns st) :when (string? k)]
      {:kind :written-otherwise :column k :columns true})
    (for [st (syntax/statements body)
         k (concat (map first (syntax/writes st (constantly nil))) (vals (:written (syntax/insert-parts st))) (when (sequential? (:columns st)) (remove string? (:columns st))))
         :when (or (keyword? k) (string? k))
         :let [[text quoted?] (syntax/formatted-name k format false)]
         ;; a string HoneySQL writes as another name (a.b as "a"."b", a-b as a_b under :quoted-snake)
         :when (if (string? k) (not= k text) (and (not quoted?) (types/reserved-word? text)))]
     {:kind :written-otherwise :column k}))))

(defn check
  "[problem ...] of a query against the registry; [] when there are none. A problem in a nested
   statement (a subquery, a CTE) carries :in, where the statement sits: (get-in q (:in p)) for
   data. A clause pgmalli does not read is an :unread-clause problem."
  [registry body opts]
  (let [sent-body (or (query/sent body opts) body)
        chains (query/once registry query/statement-positions sent-body opts)
        found (mapcat (fn [[stmt scopes path ps]]
                        (cond->> (problems registry stmt scopes ps opts) (seq path) (map #(assoc % :in (vec path)))))
                      chains)]
    (vec (concat found (query/duplicate-columns registry body opts) (return-key-problems registry body (second (first chains)) opts)
                 (unread-clauses sent-body) (written-otherwise sent-body) (written-first sent-body) (written-keywords sent-body (:format opts)) (written-inline sent-body (:format opts))))))
