(ns pgmalli-next.impl.hsyntax
  "HoneySQL query data as sql/format reads it, without a registry: names, parameters, clauses,
   statements, and the expression positions of a statement."
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [pgmalli-next.impl.error :as error]
            [pgmalli-next.impl.shape :as shape]
            [pgmalli-next.impl.types :as types]))

(declare called cast-target formatted-name operator? param-key quoted? raw-relation shorthand-fn sql-value star?)

(defn folded
  "An identifier as PostgreSQL reads what HoneySQL writes of it by default, unquoted: - is _,
   and the case folded."
  [s]
  (some-> s (str/replace "-" "_") str/lower-case))

(defn- unformatted-name
  "The name PostgreSQL reads of s where the loaded HoneySQL formats nothing (a name it refuses,
   :a;b): as written where format quotes, else folded."
  [s format]
  (if (quoted? format) s (folded s)))

(defn pg-name
  "The name PostgreSQL reads of identifier s (a keyword's text, or one dotted part of it) as
   HoneySQL writes it under sql/format's options format: as written where HoneySQL quotes it,
   else folded."
  [s format]
  (or (first (formatted-name (keyword s) format false)) (unformatted-name s format)))

(defn alias? [x] (or (keyword? x) (string? x)))

(defn ident-name
  "pg-name of x, a keyword; a string, which HoneySQL quotes, as written."
  [x format]
  (if (string? x) x (pg-name (name x) format)))

(defn- last-part
  "The last part of a dotted name (:public.users, \"pg_catalog.now\"), folded: a function's,
   which HoneySQL never quotes."
  [x]
  (folded (last (str/split (name x) #"\."))))

(defn- table-part
  "pg-name of the last part of a dotted table keyword (:public.Users)."
  [x format]
  (pg-name (last (str/split (name x) #"\.")) format))

(defn table-name
  "The table's name of \"schema.table\"."
  [t]
  (if-let [i (str/last-index-of t ".")] (subs t (inc i)) t))

(def set-operation-keys [:union :union-all :intersect :except :except-all])

(def with-keys [:with :with-recursive])

(def write-keys [:insert-into :update :delete-from :truncate])

(def statement-keys
  (into #{:select :select-distinct :select-distinct-on :select-top :select-distinct-top}
        (concat write-keys set-operation-keys)))

(def default-schema "public")

(def from-keys [:from :using :cross-join])

(def join-keys
  "In the order sql/format writes them, which decides the tables a RIGHT JOIN may leave NULL."
  [:join :left-join :right-join :inner-join :outer-join :full-join])

(defn- join-group
  "[base joins] of [[:join base {:join [...]}]], a parenthesized join; nil for another item."
  [t]
  (when (and (vector? t) (vector? (first t)) (= :join (ffirst t)))
    (let [[_ base & inner] (first t)] [base (filter map? inner)])))

(defn- written-items
  "The items under the keys ks (:from, :using, :cross-join) as written: one alone (a table, a
   subquery) or a vector of them."
  [stmt ks]
  (for [k ks :let [f (get stmt k)] :when f
        t (cond (or (keyword? f) (map? f)) [f] (vector? f) f)]
    t))

(defn from-items
  "The table items under the keys ks (:from, :using, :cross-join); of a parenthesized join, its
   first item (its joins are joins')."
  [stmt ks]
  (for [t (written-items stmt ks)] (if-let [[base] (join-group t)] base t)))

(defn- join-by-entries
  "[join-key path clauses] of each join of :join-by v, as sql/format reads it (join types and
   their clauses in turn, or maps of them), the join-key its join-keys' (or :cross-join), path
   where its clauses sit in v."
  [v]
  (let [k #(let [n (keyword (name %))] (get {:left :left-join :right :right-join :inner :inner-join :outer :outer-join :full :full-join :cross :cross-join} n n))]
    (cond (and (vector? v) (every? map? v)) (for [[i m] (map-indexed vector v), [j c] m :when (ident? j)] [(k j) [i j] c])
          (and (vector? v) (even? (count v)) (every? ident? (take-nth 2 v))) (for [i (range 0 (count v) 2)] [(k (nth v i)) [(inc i)] (nth v (inc i))]))))

(defn- own-joins
  "[join-key item condition] of the joins written in statement or join map m, in the order sql/format
   writes them: :join-by's, then join-keys'; a cross join's condition nil."
  [m]
  (concat (for [[k _ c] (join-by-entries (:join-by m)) :when (vector? c)
                [t c] (if (= :cross-join k) (map vector c (repeat nil)) (partition 2 c))]
            [k t c])
          (for [k join-keys :let [j (get m k)] :when (vector? j), [t c] (partition 2 j)] [k t c])))

(declare group-item)

(defn- join-entries
  "own-joins of m, a parenthesized join's item as group-item's."
  [m]
  (for [[k t c] (own-joins m)] [k (group-item t) c]))

(defn- group-joins
  "The join-entries inside a parenthesized join's maps, a cross join among them."
  [maps]
  (mapcat #(concat (join-entries %) (for [t (from-items % [:cross-join])] [:cross-join t nil])) maps))

(defn- group-item
  "{:group [base entries]} of a parenthesized join item, its first item and the group-joins
   inside it; another item as it is."
  [t]
  (if-let [[base js] (join-group t)] {:group [base (group-joins js)]} t))

(defn- attached-item
  "The item the joins attach to, as written: the last FROM item, a comma binding looser than JOIN."
  [stmt]
  (last (written-items stmt [:from :using])))

(defn- from-groups
  "[base entries last?] of each parenthesized join among a statement's FROM, USING and CROSS
   JOIN items, entries its group-joins, last? where it is attached-item."
  [stmt]
  (let [attached (attached-item stmt)]
    (for [t (written-items stmt from-keys) :let [[base js] (join-group t)] :when base]
      [base (group-joins js) (identical? t attached)])))

(defn- flat-entry
  "A join entry, of a parenthesized join its first item's and then those inside it."
  [[k t c :as j]]
  (if-let [[base js] (:group t)] (cons [k base c] (mapcat flat-entry js)) [j]))

(defn joins
  "[join-key item condition] of every join of a statement, in the order sql/format writes them
   (:join-by's, then join-keys'; a cross join's condition nil), those inside a parenthesized join
   among its FROM items first; of a parenthesized join, its first item, then the joins inside it."
  [stmt]
  (mapcat flat-entry (concat (mapcat second (from-groups stmt)) (join-entries stmt))))

(defn table-items
  "The table items of a statement's FROM (and USING, CROSS JOIN) and joins."
  [stmt]
  (concat (from-items stmt from-keys) (map second (joins stmt))))

(declare sql-word?)

(defn default-value?
  "Whether HoneySQL writes v as DEFAULT: [:default], or :default in any case where the format
   writes it as the word (sql-word?)."
  [v format]
  (boolean (or (= [:default] v) (sql-word? v "default" format))))

(defn keyword-word?
  "Whether HoneySQL writes keyword x as an SQL word between a call's arguments (:!from)."
  [x]
  (boolean (and (keyword? x) (nil? (namespace x)) (str/starts-with? (name x) "!"))))

(defn value-keyword?
  "Whether HoneySQL writes keyword v as a value, not a column: a parameter, a call (:%now), an
   SQL word (NULL, DEFAULT, :current_timestamp, :true) as the format writes one (sql-word?), a
   word it writes as it is (:!from), or, unquoted, a number (:10)."
  [v format]
  (boolean (or (param-key v) (shorthand-fn v) (some-> (sql-value v) name (#(sql-word? v % format)))
               (keyword-word? v)
               (and (keyword? v) (not (quoted? format)) (re-matches #"\d+(?:\.\d+)?" (name v))))))

(defn branch?
  "Whether a walk of query data goes into x: a collection, but not a value HoneySQL passes as it
   is ([:lift x], [:inline x]), whose maps are data, not statements or clauses."
  [x]
  (and (coll? x) (not (and (vector? x) (#{:lift :inline} (first x))))))

(defn statement?
  "A query map; one holding only a set operation is one too, its members statements of their own."
  [x]
  (and (map? x) (some statement-keys (keys x))))

(defn statements
  "The statements in a query (a map, or Clojure data holding maps); a subquery is one too."
  [body]
  (into [] (filter statement?) (tree-seq branch? seq body)))

(defn cte-name [entry format]
  (when (vector? entry)
    (let [n (if (vector? (first entry)) (ffirst entry) (first entry))]
      (when (alias? n) (ident-name n format)))))

(defn cte-entries
  "The CTE entries of a statement: the items of :with and :with-recursive."
  [x]
  (mapcat #(let [w (get x %)] (when (vector? w) w)) with-keys))

(defn insert-parts
  "{:table :columns :select} of :insert-into in its HoneySQL shapes: :t, [:t [:a :b]],
   [target {:select ...}], any of them behind an option map ({:overriding-value :system}); the
   statement's :columns when the target lists none. nil when the target is none of them."
  [stmt]
  (let [i (:insert-into stmt)
        i (if (and (vector? i) (map? (first i))) (vec (rest i)) i)
        i (if (and (vector? i) (= 1 (count i))) (first i) i)
        ;; [:raw "t"], a table HoneySQL writes as it is
        i (or (raw-relation i) i)
        [target select] (if (and (vector? i) (map? (second i))) [(first i) (second i)] [i nil])
        [table more] (if (and (vector? target) (or (keyword? (first target)) (vector? (first target))) (not (raw-relation target)))
                       [(first target) (second target)]
                       [target nil])
        table (or (raw-relation table) table)
        ;; [:t :alias] is INSERT INTO t AS alias; [:t [:a :b]] lists the columns; [[:t :alias] [:a :b]] both
        [table alias] (if (vector? table) [(first table) (second table)] [table nil])
        [alias listed] (if (vector? more) [alias more] [more nil])]
    (when (keyword? table)
      ;; HoneySQL writes :t/c as c in :columns, as t.c in the target's list
      (let [unspaced (fn [f c] (mapv #(if (and (keyword? %) (namespace %)) (keyword (f %)) %) c))
            dotted (when listed (unspaced #(str (namespace %) "." (name %)) listed))]
        {:table table :alias alias :select select
         :columns (or dotted (let [c (:columns stmt)] (when (vector? c) (unspaced name c))))
         ;; each listed column read, to the keyword written
         :written (when listed (zipmap dotted listed))}))))

(defn cte-names
  "Names of the CTEs a query defines, at any depth."
  [body format]
  (set (keep #(cte-name % format) (mapcat cte-entries (statements body)))))

(defn qualified
  "\"schema.table\" of a table keyword: :schema/table, :schema.table, or :table in opts' :schema;
   each part as PostgreSQL reads it (pg-name)."
  [k {:keys [schema format]}]
  (let [parts (cond (namespace k) [(namespace k) (name k)]
                    (str/includes? (name k) ".") (str/split (name k) #"\." 2)
                    :else [nil (name k)])
        [s t] parts]
    (str (if s (pg-name s format) schema) "." (str/join "." (map #(pg-name % format) (str/split t #"\."))))))

(defn- unqualified-name
  "The table name when it names no schema, the form a CTE may shadow."
  [k format]
  (when-not (or (namespace k) (str/includes? (name k) ".")) (pg-name (name k) format)))

(defn listed-columns
  "The columns an alias lists ([:v {:columns [:id :n]}] or [:v [:composite :id :n]]), as written; nil for
   none, or a list of anything but names, which no SQL is written of."
  [m]
  (let [cs (cond (map? m) (:columns m) (and (vector? m) (= :composite (first m))) (rest m))]
    (when (and (sequential? cs) (every? #(or (keyword? %) (string? %)) cs)) cs)))

(defn- raw-relation
  "The table keyword [:raw s] names where s is the grammar's relation_expr of an unquoted name
   (ONLY t, t *, schema.t), which PostgreSQL folds; nil for another x."
  [x]
  (when (and (vector? x) (= :raw (first x)) (= 2 (count x)) (string? (second x)))
    (when-let [[_ n] (re-matches #"(?i)\s*(?:ONLY\s+)?([a-z_][\w$]*(?:\.[a-z_][\w$]*)?)\s*(?:\*\s*)?" (second x))]
      (keyword (str/lower-case n)))))

(def ^:private raw-ident "(?:\"((?:[^\"]|\"\")+)\"|([A-Za-z_][A-Za-z0-9_$]*))")

(defn- raw-ident-name [[_ q n]] (if q (str/replace q "\"\"" "\"") (folded n)))

(defn unnamed-raw?
  "Whether alias is that of a raw FROM item of SQL beyond a name, which is not read: the columns
   any name qualifies may be its."
  [alias]
  (and (string? alias) (str/starts-with? alias "(raw ")))

(defn table-ref
  "[table alias written columns] of a :from / join / target item: the table's qualified name,
   its alias, the name as written (nil when qualified by a schema), and the columns an alias
   lists ([:v {:columns [:id :n]}] or [:v [:composite :id :n]] over a VALUES); an opaque item (subquery, function) has no
   table. opts: :schema, the default one, and sql/format's :format."
  [x {:keys [format] :as opts}]
  (cond
    ;; FROM public.users is referred to as users
    (keyword? x) [(qualified x opts) (table-part x format) (unqualified-name x format)]
    ;; [[:raw "ONLY events"]] or [[:raw "events *"] :e], the grammar's relation_expr of an unquoted
    ;; name: the table, children left out or in (a bare [:raw ...] HoneySQL writes as raw AS "...")
    (and (vector? x) (raw-relation (first x))) (table-ref (assoc x 0 (raw-relation (first x))) opts)
    ;; a subquery with no alias (PostgreSQL 16): nothing names it, so no query reaches it by name
    (map? x) [nil (str "(subquery " (hash x) ")") nil]
    (vector? x)
    (let [[a b] x
          ;; [:alias {:columns [...]}]; a column definition list ([:alias [:raw "(a int)"]]) names
          ;; no column read here
          [b listed] (cond (and (vector? b) (alias? (first b)) (or (map? (second b)) (vector? (second b))))
                           [(first b) (some->> (listed-columns (second b)) (mapv #(ident-name % format)))]
                           ;; [[:raw "x(id text, v jsonb)"]] or [[:raw "x"]], written as it is: alias x, maybe with a
                           ;; column definition list
                           (and (vector? b) (vector? (first b)) (= :raw (ffirst b)) (string? (second (first b))))
                           [(some-> (re-find (re-pattern (str "^\\s*" raw-ident "\\s*(?:\\(|$)")) (second (first b))) raw-ident-name) nil]
                           :else [b nil])
          alias-name #(ident-name % format)]
      (cond
        ;; :t / [:t :alias] / INSERT INTO t (columns...)
        (and (keyword? a) (or (nil? b) (alias? b) (vector? b))) [(qualified a opts) (if (alias? b) (alias-name b) (table-part a format)) (unqualified-name a format) listed]
        (keyword? a) [nil (alias-name a) nil listed]
        ;; [[:unnest ...] [:t {:columns [:x]}]]: t.f reads as f(t), the function on a scalar result,
        ;; so a function's alias is not held to the columns it lists; a LATERAL subquery's is
        (alias? b) [nil (alias-name b) nil (when (or (not (vector? a)) (and (= :lateral (first a)) (map? (second a)))) listed)]
        ;; [[:with-ordinality [:f args...] alias]]: f WITH ORDINALITY alias, named after the function
        ;; without one
        (and (nil? b) (vector? a) (= :with-ordinality (first a)))
        (let [[_ f al] a]
          [nil (cond (alias? al) (alias-name al)
                     (and (vector? f) (keyword? (first f))) (last-part (first f))
                     :else "with_ordinality")
           nil])
        ;; [[:raw "f(...) AS e(c)"]], SQL beyond a name, which is not read: its name is not told
        (and (nil? b) (vector? a) (= :raw (first a)) (string? (second a)))
        [nil (str "(raw " (hash a) ")") nil]
        ;; [[:f args...]]: a table function, named after the function
        (and (nil? b) (vector? a) (keyword? (first a))) [nil (last-part (first a)) nil]
        ;; [subquery]: one with no alias
        (and (nil? b) (map? a)) (table-ref a opts)))))

(defn insert-target
  "The target of :insert-into as a :from item: the table, under its alias when it has one."
  [stmt]
  (let [{:keys [table alias]} (insert-parts stmt)]
    (if (and table alias) [table alias] table)))

(defn write-targets
  "The tables a statement writes, as :from items: INSERT INTO's, UPDATE's, DELETE FROM's and
   TRUNCATE's (:t, [:t options...] or [[:t ...] options...])."
  [stmt]
  (let [t (:truncate stmt)
        t (if (vector? t) (first t) t)]
    (remove nil? (concat [(insert-target stmt) (:update stmt) (:delete-from stmt)]
                         (cond (vector? t) t (some? t) [t])))))

(defn catalog? [table] (boolean (re-find #"^(pg_catalog|information_schema)\." table)))

(defn join-base
  "The alias of attached-item (of a parenthesized join, its first item's)."
  [stmt opts]
  (when-let [t (attached-item stmt)]
    [(second (table-ref (if-let [[base] (join-group t)] base t) opts))]))

(defn outer-aliases
  "The aliases an outer join may leave NULL: a LEFT JOIN's table, every table before a RIGHT
   JOIN's, both sides of a FULL (and :outer-join) one."
  [stmt opts]
  (let [alias #(second (table-ref % opts))
        ;; a parenthesized join is one item to the join it is in: its tables are extended
        ;; together, and those its own joins extend besides
        extend (fn extend [acc entries]
                 (reduce (fn [{:keys [before] :as acc} [k t]]
                           (let [[base js] (:group t)
                                 inner (when base (extend {:before [(alias base)] :nullable #{}} js))
                                 as (if inner (:before inner) (when t [(alias t)]))]
                             (-> (case k
                                   :left-join (update acc :nullable into as)
                                   :right-join (update acc :nullable into before)
                                   (:outer-join :full-join) (update acc :nullable into (concat before as))
                                   acc)
                                 (update :nullable into (:nullable inner))
                                 (update :before into as))))
                         acc entries))]
    ;; a parenthesized join among the FROM items extends its own tables; the joins after them
    ;; see its tables where it is the item they attach to
    (:nullable (extend (reduce (fn [acc [base js last?]]
                                 (let [inner (extend {:before [(alias base)] :nullable #{}} js)]
                                   (cond-> (update acc :nullable into (:nullable inner)) last? (assoc :before (:before inner)))))
                               {:before (vec (join-base stmt opts)) :nullable #{}}
                               (from-groups stmt))
                       (join-entries stmt)))))

(defn- array-cast-of-argument?
  "Whether x is an argument cast to an array type ([:cast :?ids [:raw \"bigint[]\"]])."
  [x]
  (and (vector? x) (= :cast (first x)) (= 3 (count x)) (param-key (second x)) (some-> (cast-target (nth x 2)) types/element)))

(defn- unnest-of-arguments?
  "Whether a FROM item is unnest(?, ...) of arguments alone, each maybe cast to an array type."
  [item]
  (boolean (and (vector? item) (vector? (first item)) (= :unnest (ffirst item))
                (every? #(or (param-key %) (array-cast-of-argument? %)) (rest (first item))))))

(defn- unnest-columns
  "[[column argument] ...] of a FROM item unnest(?, ...) of arguments, in order: the column each
   argument's elements become (the argument as written, cast or not), by position in the
   columns its alias lists ([:x {:columns [:a]}]), or the alias itself of an unnest of one
   ([[:unnest :?ids] :id])."
  [item format]
  (when (unnest-of-arguments? item)
    (let [ps (rest (first item))
          b (second item)
          cs (cond (and (vector? b) (map? (second b))) (listed-columns (second b))
                   (and (alias? b) (= 1 (count ps))) [b])]
      (when (and (seq ps) (sequential? cs))
        (map (fn [p c] [(ident-name c format) p]) ps cs)))))

(defn unnest-elements [item format] (some->> (unnest-columns item format) (into {}) not-empty))

(defn lateral?
  "Whether a FROM or join item sees the items before it: LATERAL, or a function call, which is
   implicitly LATERAL."
  [item]
  (and (vector? item) (or (= :lateral (first item)) (and (vector? (first item)) (keyword? (ffirst item)) (not (join-group item))))))

(defn- with-entries
  "[entries key index entry] of each entry of every :with and :with-recursive in query body."
  [body]
  (for [x (tree-seq branch? seq body) :when (map? x)
        k with-keys :let [w (get x k)] :when (vector? w)
        [i e] (map-indexed vector w)]
    [w k i e]))

(defn- search-columns
  "[[name type] ...] of the columns a CTE's SEARCH and CYCLE add ([:c q :search-depth-first-by :x
   :set :ord], [:c q :cycle [:x] :set :looped :using :path]): a record, a record[] or a boolean;
   nil for the mark's type where CYCLE names TO and DEFAULT."
  [entry format]
  (let [tail (drop 2 entry)
        pairs (partition-all 2 (if (#{:materialized 'materialized :not-materialized 'not-materialized} (first tail)) (rest tail) tail))
        by (into {} (for [[k v] pairs :when (keyword? k)] [k v]))
        typed (fn [k t] (when (alias? (by k)) [(ident-name (by k) format) t]))]
    (remove nil? (cond (contains? by :search-depth-first-by) [(typed :set "record[]")]
                       (contains? by :search-breadth-first-by) [(typed :set "record")]
                       (contains? by :cycle) [(typed :set (when-not (contains? by :to) "boolean")) (typed :using "record[]")]))))

(defn cte-statement
  "[statement columns recursive? added] of CTE name: its body as read on its own, under the entries of
   its :with before it, and the columns its name lists ([[:c {:columns [:x]}] ...]); of a
   recursive one (a set operation under :with-recursive one of whose members reads the CTE
   itself), its first member, whose types PostgreSQL gives the columns; added, the columns its SEARCH or CYCLE adds (search-columns). nil where the query defines name more than once, or the body is
   no statement."
  [body name {:keys [format] :as opts}]
  (let [found (for [[w k i e] (with-entries body) :when (= name (cte-name e format))]
                [(second e) (subvec w 0 i) (when (vector? (first e)) (listed-columns (second (first e)))) k (search-columns e format)])]
    (when-let [[[stmt before listed k added]] (when (= 1 (count found)) found)]
      (let [set-op (when (and (= :with-recursive k) (map? stmt)) (some #(when (vector? (get stmt %)) %) set-operation-keys))
            ;; recursive when a member reads the CTE itself; otherwise its members resolve together
            self? (some (fn [m] (some #(= name (nth (table-ref % opts) 2))
                                      (mapcat table-items (statements m))))
                        (when set-op (get stmt set-op)))
            op (when self? set-op)
            stmt (if op (first (get stmt op)) stmt)
            ;; a body's own :with comes after the entries before it, which it sees; one of
            ;; :with-recursive under a :with, or naming one of them again, is not read here
            own (when (map? stmt) (some #(when (contains? stmt %) %) with-keys))
            stmt (cond (not (map? stmt)) nil
                       (or (empty? before) (nil? own)) (cond-> stmt (seq before) (assoc k before))
                       (and (= k own) (vector? (get stmt own)) (not-any? (set (keep #(cte-name % format) before)) (keep #(cte-name % format) (get stmt own))))
                       (assoc stmt k (into before (get stmt own))))]
        (when (and stmt (or (nil? listed) (sequential? listed)))
          [stmt (map #(ident-name % format) listed) (boolean op) added])))))

(def populate-name #"jsonb?_(populate|to)_record(set)?")

(defn- populate-item?
  "Whether a FROM item is a call of json_populate_record, jsonb_populate_record, json_to_record,
   jsonb_to_record or their recordset ([[:jsonb_populate_recordset t ?] :r])."
  [item]
  (boolean (and (vector? item) (some->> (called (first item)) (re-matches populate-name)))))

(defn defined-columns
  "[[column type] ...] of the column definition list the alias of a populate-item writes
   ([[:jsonb_to_record ?] [:w {:with-columns [[:a :text] [:b :text :array]]}]]), each type as
   cast-target reads it (:array makes \"text[]\"), nil where it reads none; nil for no list."
  [item format]
  (let [cs (when (populate-item? item) (let [b (second item)] (when (vector? b) (:with-columns (second b)))))]
    (when (and (sequential? cs) (seq cs) (every? #(and (vector? %) (alias? (first %)) (<= 2 (count %) 3)) cs))
      (for [[c t array] cs]
        [(ident-name c format)
         (when-let [n (cast-target t)] (case array nil n :array (types/array-of n) nil))]))))

(defn standalone
  "Statement inner as a query of its own: itself where it reads no CTE of ctes (those its place
   in query body sees), else under the entries of the one :with of body that define them, up to
   the last; nil where they are not there, or inner has a :with of its own."
  [body inner ctes {:keys [format] :as opts}]
  (let [defined (cte-names inner format)
        read (set (for [i (mapcat table-items (statements inner))
                        :let [[_ _ written] (table-ref i opts)]
                        :when (and written (not (defined written)) (contains? ctes written))]
                    written))]
    (if (empty? read)
      inner
      (let [found (for [[w k i e] (with-entries body) :when (read (cte-name e format))]
                    [w k i])]
        (when (and (= (count read) (count found)) (= read (set (map (fn [[w _ i]] (cte-name (nth w i) format)) found)))
                   (apply = (map first found)) (apply = (map second found))
                   (not-any? #(contains? inner %) with-keys))
          (let [[w k] (first found)]
            (assoc inner k (subvec w 0 (inc (apply max (map #(nth % 2) found)))))))))))

(def ^:private clause-rank (delay (zipmap (sql/clause-order) (range))))

(defn- clause-entries
  "The entries of statement x in the order sql/format writes its clauses (others after, by
   name): what comes first in the SQL comes first here, whatever order the map holds."
  [x]
  (sort-by (fn [[k]] [(get @clause-rank k Long/MAX_VALUE) (str k)]) x))

(defn clause-parts
  "[path part sees] for the clauses of statement x but :with, in the order sql/format writes
   them, path where part sits in x: what each part sees, decided here alone. :own, the
   statement's tables and the enclosing ones (its conditions, select, assignments); :outer, only
   the enclosing ones (INSERT ... SELECT, the rows of :values and a table item of :from or a
   join: none sees the statement's own tables); :lateral, those and the tables before it (a
   LATERAL item, a function); :group, those and a parenthesized join's own tables (its ONs; its
   parts see as group-parts tells)."
  [x]
  (for [[k v] (clause-entries x) :when (not (some #{k} with-keys))
        :let [item-sees #(cond (join-group %) :group (lateral? %) :lateral :else :outer)]
        part (cond (#{:insert-into :values} k) [[[k] v :outer]]
                   (and (vector? v) (some #{k} from-keys))
                   (map-indexed (fn [i t] [[k i] t (item-sees t)]) v)
                   (and (vector? v) (some #{k} join-keys))
                   (map-indexed (fn [i t] [[k i] t (if (odd? i) :own (item-sees t))]) v)
                   (and (= :join-by k) (seq (join-by-entries v)))
                   (for [[jk p c] (join-by-entries v)
                         part (if (vector? c)
                                (map-indexed (fn [i t] [(into [k] (conj p i)) t (if (and (odd? i) (not= :cross-join jk)) :own (item-sees t))]) c)
                                [[(into [k] p) c :own]])]
                     part)
                   (some #{k} from-keys) [[[k] v :outer]]
                   :else [[[k] v :own]])]
    part))

(defn group-parts
  "[path part sees] of the parts of a parenthesized join t, path where each sits in t, as
   clause-parts tells a statement's: its items :outer, :lateral or :group as a statement's
   items are, an ON [:on-of t], seeing t's own tables."
  [t]
  (let [[_ base & inner] (first t)
        item-sees #(cond (join-group %) :group (lateral? %) :lateral :else :outer)]
    (concat [[[0 1] base (item-sees base)]]
            (for [[i m] (map-indexed vector inner) :when (map? m)
                  [k v] m
                  part (cond (and (vector? v) (some #{k} join-keys))
                             (map-indexed (fn [j x] [[0 (+ 2 i) k j] x (if (odd? j) [:on-of t] (item-sees x))]) v)
                             (and (vector? v) (= :cross-join k))
                             (map-indexed (fn [j x] [[0 (+ 2 i) k j] x (item-sees x)]) v)
                             :else [[[0 (+ 2 i) k] v [:on-of t]]])]
              part))))

(defn part-chain
  "The chain of scopes a part (clause-parts) of a statement of chain sees, outer the chain of the
   statements enclosing it. A LATERAL item sees its statement's tables, but not itself."
  [sees part chain outer opts]
  (case (if (vector? sees) (first sees) sees)
    :on-of (part-chain :group (second sees) chain outer opts)
    :own chain
    :outer outer
    :lateral (cons (dissoc (first chain) (second (table-ref part opts))) (rest chain))
    :group (let [[base js] (join-group part)
                 tables (cons base (map second (mapcat flat-entry (group-joins js))))]
             (cons (select-keys (first chain) (map #(second (table-ref % opts)) tables)) outer))))

(defn split-column
  "[alias column] of a column keyword: :a/b and :a.b name a table (:schema.a.b too), :b does
   not; each as PostgreSQL reads it (pg-name under sql/format's options format), * as it is."
  [col format]
  (let [s (if (namespace col) (str (namespace col) "." (name col)) (name col))
        ;; HoneySQL writes :'x as x, as it is, unquoted
        raw? (str/starts-with? s "'")
        parts (str/split (cond-> s raw? (subs 1)) #"\.")
        named #(cond (= "*" %) % raw? (folded %) :else (pg-name % format))]
    (if (next parts)
      [(named (nth parts (- (count parts) 2))) (named (last parts))]
      [nil (named (first parts))])))

(defn chain [scope] (if (map? scope) [scope] scope))

(def sql-value-types
  "types/sql-words keyed as keywords, with TRUE and FALSE."
  (into {:true "boolean" :false "boolean"} (map (fn [[w t]] [(keyword w) t])) types/sql-words))

(def nullable-sql-values
  "The SQL words that may be NULL: CURRENT_SCHEMA under a search_path of no existing schema,
   SYSTEM_USER under trust authentication (docs 9.27)."
  #{:current_schema :system_user})

(def ^:private sql-values
  "Keywords HoneySQL writes as SQL words, not columns."
  (into #{:null :default :*} (keys sql-value-types)))

(defn sql-value
  "The SQL word HoneySQL writes for keyword x, lower-cased (:null for :NULL), or nil for a
   column: it writes the name as spelt, and PostgreSQL reads NULL and Null as it reads null."
  [x]
  (when (and (keyword? x) (nil? (namespace x)))
    (sql-values (keyword (str/lower-case (name x))))))

(defn param-key
  "The :params key of a HoneySQL named parameter, as sql/format looks it up: :?id and ?id are
   :id (:?a/b is :a/b), [:param k] is k. nil for anything else."
  [x]
  (if (and (vector? x) (= 2 (count x)) (= :param (first x)))
    (second x)
    ;; the jsonb and hstore operators ?, ?| and ?&
    (when (and (keyword? x) (not (#{:? :?| :?&} x)))
      (let [c (subs (str x) 1)]
        (when (str/starts-with? c "?") (keyword (subs c 1)))))))

(defn query-args [body] (set (keep param-key (tree-seq branch? seq body))))

(defn shorthand-fn
  "The function a HoneySQL :%f.args keyword calls (:%max.id is MAX(id)); nil for any other."
  [x]
  (when (keyword? x)
    (let [c (subs (str x) 1)]
      (when (str/starts-with? c "%") (first (str/split (subs c 1) #"\."))))))

(defn column-keyword?
  "Whether HoneySQL writes keyword x as a column: no parameter, call or SQL word; under format,
   as it writes one (value-keyword?: under :quoted, :null is a column \"null\")."
  ([x] (column-keyword? x nil))
  ([x format] (and (keyword? x) (not (value-keyword? x format)))))

(defn vectorish
  "x when it is a vector, [x] for a bare keyword (HoneySQL takes :order-by :id for [:id])."
  [x]
  (cond (vector? x) x (keyword? x) [x]))

(defn bare
  "x of an item [x] or [x alias] of a select list, or [x direction ...] of :order-by."
  [x]
  (if (vector? x) (first x) x))

(def ^:private alone?
  (memoize (fn [h] (= (sql/format-expr :operand) (try (sql/format-expr [h :operand]) (catch Exception _ nil))))))

(defn operand-alone
  "The operand of [op x] where the loaded HoneySQL writes it as x alone ([:and x], [:* x]),
   through such ops in turn; x itself otherwise."
  [x]
  (if (and (vector? x) (= 2 (count x)) (keyword? (first x)) (operator? (first x))
           (not (#{:inline :lift :param} (first x))) (alone? (first x)))
    (recur (second x))
    x))

(def select-list-clauses
  "The clauses of a select list, whose items are [expr alias] (selected-items')."
  #{:select :select-distinct :select-distinct-on :select-top :select-distinct-top :returning})

(defn- distinct-on-parts
  "[expressions items] of a :select-distinct-on value: DISTINCT ON's, then the select list's."
  [v]
  (when (vector? v) [(vectorish (first v)) (rest v)]))

(defn distinct-on-expressions
  "The expressions of :select-distinct-on's DISTINCT ON."
  [stmt]
  (first (distinct-on-parts (:select-distinct-on stmt))))

(defn selected-items
  "The items of :select, :select-distinct, :select-distinct-on (after its DISTINCT ON columns)
   or :returning, an expression HoneySQL writes as its operand alone (operand-alone) that
   operand."
  [stmt]
  (let [s (or (:select stmt) (:select-distinct stmt) (:returning stmt))
        after-first (or (:select-top stmt) (:select-distinct-top stmt))]
    (for [item (cond (or (vector? s) (keyword? s)) (vectorish s)
                     (:select-distinct-on stmt) (second (distinct-on-parts (:select-distinct-on stmt)))
                     (vector? after-first) (rest after-first))]
      (if (and (vector? item) (<= 1 (count item) 2))
        (let [e (operand-alone (first item))] (if (and (= 1 (count item)) (keyword? e)) e (assoc item 0 e)))
        item))))

(defn select-parts
  "[column alias] of a select item: a column, a column under an alias (a keyword, or a string
   HoneySQL quotes), or an aliased expression, which has no column. Under sql/format's :quoted
   a keyword HoneySQL otherwise writes as an SQL word (:user) is a column."
  ([item] (select-parts item {}))
  ([item opts]
   (let [column? (if (quoted? (:format opts))
                   #(and (keyword? %) (not (param-key %)) (not (shorthand-fn %)))
                   column-keyword?)]
     (cond
       (keyword? item) (when (or (= :* item) (column? item)) [item nil])
       (not (vector? item)) nil
       (and (keyword? (first item)) (not (column? (first item))) (not= :* (first item))) (when (alias? (second item)) [nil (second item)])
       (and (keyword? (first item)) (alias? (second item))) [(first item) (second item)]
       (keyword? (first item)) [(first item) nil]
       (alias? (second item)) [nil (second item)]))))

(def in-ops #{:in :not-in})

(def like-ops #{:like :ilike :not-like :not-ilike})

(def distinct-ops
  "IS [NOT] DISTINCT FROM, which compares NULL as a value."
  #{:is-distinct-from :is-not-distinct-from})

(def ^:private comparison-ops
  (into #{:= :<> :not= :!= :< :> :<= :>=} (concat distinct-ops in-ops like-ops)))

;; a value outside a CHECK's list makes these match no row; the others compare the column's base
;; type, which takes any value
(def equality-ops #{:= :in :is-not-distinct-from})

(def ^:private negated-op
  {:= :<> :<> := :not= := :!= := :in :not-in :not-in :in
   :is-not-distinct-from :is-distinct-from :is-distinct-from :is-not-distinct-from})

(defn comparison?
  "A comparison node: [op a b] of comparison-ops, or [:between a b c]."
  [x]
  (and (vector? x) (or (and (= 3 (count x)) (comparison-ops (first x)))
                       (and (= 4 (count x)) (#{:between :not-between} (first x))))))

(defn target-column
  "A column as HoneySQL writes it where a statement assigns it: without its namespace."
  [c]
  (if (and (keyword? c) (namespace c)) (keyword (name c)) c))

(defn field-column
  "The column of an assignment target :a.b, a field of the composite column a, as PostgreSQL
   reads SET a.b and INSERT INTO t (a.b); nil for another target."
  [c]
  (when (and (keyword? c) (nil? (namespace c)))
    (let [[a & fields] (str/split (name c) #"\.")]
      (when fields (keyword a)))))

(defn- to-field
  "A write [column value ...] to a field as one to its column, its value ::untold: the field's
   type is not in the registry."
  [[c & more :as w]]
  (if-let [a (field-column c)] (into [a ::untold] (rest more)) w))

(defn- value-rows
  "The maps of :values as HoneySQL writes them under the statement's column list: only the
   listed keys."
  [stmt columns]
  (let [maps (map #(update-keys % target-column) (filter map? (when (vector? (:values stmt)) (:values stmt))))]
    (if columns (map #(select-keys % (map target-column columns)) maps) maps)))

(defn- do-update-parts
  "[assignments expressions] of ON CONFLICT DO UPDATE SET v: [column value] of each column it
   assigns (a map of them, or the columns taken from EXCLUDED, valued ::untold: a vector of
   them, or {:fields [...] :where ...}), and the expressions in it."
  [v]
  (cond (and (map? v) (contains? v :fields)) [(for [c (vectorish (:fields v))] [c ::untold]) (remove nil? [(:where v)])]
        (map? v) [(for [[c x] v] [(target-column c) x]) (vals v)]
        (vector? v) [(for [c v] [c ::untold]) nil]))

(defn writes
  "[column value event clause] of what a statement writes: :set, ON CONFLICT DO UPDATE SET (a
   map of assignments, or the columns taken from EXCLUDED, valued ::untold: a vector of them,
   or {:fields [...] :where ...}), and the rows of :values, maps or positional rows under the
   columns listed.
   Under missing, a column one map of :values lacks and another has is written (missing column)."
  ([stmt] (writes stmt nil))
  ([stmt missing]
   (let [{:keys [columns]} (insert-parts stmt)
         maps (value-rows stmt columns)
         union (distinct (mapcat keys maps))]
     (map to-field
      (concat (when (map? (:set stmt)) (for [[c v] (:set stmt)] [(target-column c) v :update :set]))
              (for [[c v] (first (do-update-parts (:do-update-set stmt)))]
                [c v :update :do-update-set])
              (for [row maps, c (if (or columns (nil? missing)) (keys row) union)]
                [c (if (contains? row c) (get row c) (missing c)) :insert :values])
              (when (and columns (vector? (:values stmt)))
                (for [row (filter vector? (:values stmt)), [c v] (map vector columns row)] [c v :insert :values])))))))

(defn target-scope
  "The scope an assigned column is resolved in: the table of :update / :insert-into alone."
  [scopes stmt opts]
  (let [[_ alias] (some-> (or (:update stmt) (insert-target stmt)) (table-ref opts))]
    (select-keys (first (chain scopes)) [alias])))

(def read-clauses
  "The clauses analyze reads."
  (into statement-keys (concat with-keys from-keys join-keys
                               [:join-by :where :having :group-by :order-by :limit :offset :values :columns :set
                                :on-conflict :do-update-set :returning])))

(def ignored-clauses
  "Clauses that change no table, column or type analyze reads, whose expressions it reads all the same."
  #{:do-nothing :on-constraint :for :lock :fetch :window :partition-by :frame})

(defn unread-clause?
  "Whether analyze neither reads clause k nor ignores it (an :unread-clause)."
  [k]
  (and (keyword? k) (not (read-clauses k)) (not (ignored-clauses k))))

(defn under-unread-clause?
  "Whether the statement at path of body (:in's: a clause's key, an index into a vector, or into
   a map's entries) sits in a clause analyze does not read (MERGE's :when-matched): a statement's
   key checked with unread-clause?, a clause's value (a :set map, a row) walked into."
  [body path]
  (loop [node body [i & more] path]
    ;; the body is a statement whatever its clauses (MERGE INTO's none pgmalli reads)
    (let [clause? #(and (or (identical? node body) (statement? node)) (unread-clause? %))]
      (cond (nil? i) false
            (and (map? node) (keyword? i)) (if (clause? i) true (recur (get node i) more))
            (and (map? node) (int? i)) (let [[k :as e] (nth (seq node) i nil)] (if (clause? k) true (recur e more)))
            (and (sequential? node) (int? i)) (recur (nth (vec node) i nil) more)
            :else false))))

(defn positions
  "The expression positions of statement stmt, one read for the problems and the arguments' types: {:x node
   :clause k :chain scopes :negated? bool :top? bool :item? bool :condition? bool} for every node of every clause sql/format writes, in
   its order, as a walk of each clause's value meets them, not descending into the statements nested in it, scopes the chain clause-parts says the node is read in,
   negated? under an odd number of :not through :and and :or alone ([:not [:= :a 1]] is a <> 1;
   CASE or a function's argument under NOT is not negated), top? for the clause's value itself
   (one of :values, :set, an item of FROM or a join), item? for a node right in it (an item of a
   select list, [expr alias], or of ORDER BY, [expr direction], which is no call). A value written to a column is
   besides {:x value :assign column :event e :target scope}: e the write (:insert, :update),
   scope the target table's. feed, for the SELECT of an INSERT ... SELECT, is {:columns :target} of its INSERT:
   its items go to those columns by position."
  [stmt chain outer feed {:keys [format] :as opts}]
  (let [nodes (fn nodes [k x c]
                (letfn [(walk [x neg? top? item?]
                          (lazy-seq
                           (cons {:x x :clause k :chain c :negated? neg? :top? top? :item? item?}
                                 (when (and (branch? x) (not (statement? x)))
                                   (let [neg? (case (when (vector? x) (first x)) :not (not neg?) (:and :or) neg? false)]
                                     (if (and top? (= :select-distinct-on k) (vector? x))
                                       ;; DISTINCT ON's expressions, then the select list's items
                                       (let [[es items] (distinct-on-parts x)]
                                         (concat (mapcat #(walk % neg? false false) es) (mapcat #(walk % neg? false true) items)))
                                       ;; a window's :frame ([:rows :between :unbounded-preceding :current-row]) is its own clause
                                       (mapcat #(if (and (map? x) (= :frame (key %))) (nodes :frame (val %) c) (walk % neg? false top?))
                                               ;; [expr alias], an item of a select list: the alias (:%count.c too) is no expression
                                               (if (and item? (select-list-clauses k) (vector? x) (= 2 (count x)) (alias? (second x)))
                                                 [(first x)]
                                                 (seq x)))))))))]
                  (walk x false true false)))
        target (target-scope chain stmt opts)
        written (group-by #(nth % 3) (writes stmt))
        assigned (fn [clause c]
                   (for [[col v event] (written clause) :when (not= ::untold v)]
                     {:x v :assign col :event event :target target :chain c :clause clause}))]
    (concat
     (for [[[k i] v sees] (clause-parts stmt)
           :let [c (part-chain sees v chain outer opts)]
           p (if (some #{k} join-keys)
               ;; a join's condition, after its table item
               [(cond->> (nodes k v c) (and (int? i) (odd? i)) (map #(assoc % :condition? true)))]
               (case k
               (:insert-into :columns) nil
               :values (concat (for [row (when (vector? v) v), x (cond (map? row) (vals row) (vector? row) row)] (nodes k x c))
                               [(assigned :values c)])
               :set (concat (map #(nodes k % c) (when (map? v) (vals v))) [(assigned :set c)])
               :do-update-set (concat (map #(nodes k % c) (second (do-update-parts v))) [(assigned :do-update-set c)])
               [(nodes k v c)]))
           e p]
       e)
     ;; a star or :raw makes columns the data does not count, but of unnest(?, ...) alone, a
     ;; column of each argument, and of a populate-item alone, the columns of its row type
     (let [items (map bare (selected-items stmt))
           [item & more] (table-items stmt)
           items (or (when (and (= 1 (count items)) (keyword? (first items)) (star? (first items)) (nil? more))
                       (or (when-let [cs (unnest-columns item format)] (when (= (count cs) (count (rest (first item)))) (map (comp keyword first) cs)))
                           (when (populate-item? item)
                             (let [[_ alias] (table-ref item opts)]
                               (some->> (:order (get (first chain) alias)) (map #(keyword alias %)))))))
                     items)]
       (when (and (:columns feed) (not-any? #(or (and (keyword? %) (star? %)) (and (vector? %) (= :raw (first %)))) items))
         (for [[col v] (map (comp to-field vector) (:columns feed) items)
               :when (not= ::untold v)]
           {:x v :assign (target-column col) :event :insert :target (:target feed)
            :chain chain :clause :select}))))))

(defn expression-nodes [ps] (remove :assign ps))

(defn assignments [ps] (filter #(column-keyword? (:assign %)) ps))

(defn comparison-sides
  "[op side other chain] for each side of the comparisons among positions ps (BETWEEN's first,
   against each bound), element by element where a row meets a row of its length ([:<
   [:composite :a :b] [:composite :?a :?b]]): a side is a column, an expression or a value,
   other whatever it is. Under an odd number of :not, op is its negation (= is <>)."
  [ps]
  (for [{:keys [x negated? chain]} (expression-nodes ps)
        :when (comparison? x)
        :let [[op a b c] x
              row? #(and (vector? %) (= :composite (first %)))]
        [side other] (cond (= 4 (count x)) [[a b] [a c]]
                           (and (row? a) (row? b) (= (count a) (count b))) (mapcat (fn [l r] [[l r] [r l]]) (rest a) (rest b))
                           :else [[a b] [b a]])]
    [(if negated? (negated-op op op) op) side other chain]))

(def ^:private operator-syntax
  "HoneySQL's special syntax that writes an SQL operator, whose column PostgreSQL names ?column?."
  #{:in :not-in :not :between :not-between :inline :lift :param})

(defn operator?
  "Whether HoneySQL writes [h ...] as an operator: a name not an identifier (:->, :@>), one
   HoneySQL has registered (register-op! too), or operator-syntax."
  [h]
  (boolean (or (operator-syntax h) (not (re-find #"^[A-Za-z_]" (name h))) (sql/registered-op? h))))

(defn- function-name
  "A function's column name: as HoneySQL writes it (- is _), unqualified, as PostgreSQL folds it;
   TRIM is btrim, which PostgreSQL's grammar calls it."
  [s]
  (let [n (last-part s)]
    (if (= "trim" n) "btrim" n)))

(defn called
  "The function name of a call [:f ...]; nil for anything else."
  [x]
  (when (and (vector? x) (keyword? (first x))) (function-name (name (first x)))))

(defn expression-name
  "The column name PostgreSQL gives a select expression with no alias: a function's name, case
   (its ELSE's name where that has one of its own), array, a cast column's or function's name,
   ?column? for a value or an operator (an infix one of HoneySQL's, or any whose name is not an
   identifier: :->, :@>, an op registered with register-op!). nil when this cannot tell (a cast
   value or operation is named after its type, a subquery after its column, (x).* its fields, :raw anything). A
   column's name as PostgreSQL reads it under sql/format's options format."
  [x format]
  (cond (or (number? x) (string? x) (boolean? x) (nil? x) (param-key x) (#{:null :true :false} (sql-value x))) "?column?"
        (shorthand-fn x) (function-name (shorthand-fn x))
        (keyword? x) (some-> (sql-value x) name)
        (not (and (vector? x) (keyword? (first x)))) nil
        :else (let [[h a] x]
                (cond (= :. h) (some-> (last x) (#(when (and (keyword? %) (not= :* %)) (function-name (name %)))))
                      (operator? h) "?column?"
                      (#{:case :case-expr} h) (let [e (some #(when (= :else (first %)) (second %)) (partition-all 2 (if (= :case h) (rest x) (drop 2 x))))
                                                    n (cond (nil? e) "case"
                                                            (column-keyword? e) (second (split-column e format))
                                                            :else (expression-name e format))]
                                                (if (= "?column?" n) "case" n))
                      (= :array h) "array"
                      (= :cast h) (cond (column-keyword? a) (second (split-column a format))
                                        (vector? a) (let [n (expression-name a format)] (when-not (#{"?column?" "case"} n) n)))
                      (sql/registered-fn? h) nil
                      :else (function-name (name h))))))

(defn item-expression
  "The expression of a select item with no alias: [x] is x."
  [item]
  (if (and (vector? item) (= 1 (count item))) (first item) item))

(defn alias-label
  "The label PostgreSQL gives an alias: the name the loaded HoneySQL writes under sql/format's
   options (formatted-name: :user-id labels user_id, \"user-id\" quoted); a string as it is,
   which HoneySQL quotes."
  [alias opts]
  (if (string? alias)
    alias
    (or (first (formatted-name alias (:format opts) true)) (unformatted-name (name alias) (:format opts)))))

(defn item-name
  "The name of the output column a select item makes: its alias, its column's name, or the name
   PostgreSQL gives the expression; nil when that cannot be told."
  [item opts]
  (let [[col alias] (select-parts item opts)]
    (cond alias (alias-label alias opts)
          col (second (split-column col (:format opts)))
          :else (expression-name (item-expression item) (:format opts)))))

(defn over-columns
  "[[name expression] ...] of the columns [:over [f window alias] ...], an item of a select list,
   makes: one of each window function, named by its alias or as PostgreSQL names f (nil when that
   cannot be told); nil for another x."
  [x opts]
  (when (and (vector? x) (= :over (first x)))
    (for [w (rest x) :let [[e _ a] (when (vector? w) w)]]
      [(if (alias? a) (alias-label a opts) (expression-name e (:format opts))) e])))

(defn star? [col] (= "*" (second (split-column col nil))))

(def formatted-name
  "[name quoted?] of k (a keyword, or a string) as the loaded HoneySQL writes it under sql/format's
   options format, as a column of :values or, alias? true, as an alias of a select item; name as
   PostgreSQL reads it: a quoted one as it is, else folded to lower case."
  (memoize (fn [k format alias?]
             ;; a name the loaded HoneySQL refuses (:a;b) formats nothing
             (when-let [n (try (let [s (first (sql/format (if alias? {:select [[:a k]]} {:insert-into :t :values [{k 1}]}) (dissoc (or format {}) :params)))]
                                 (second (re-find (if alias? #" AS (.*)$" #"\((.*?)\)") s)))
                               (catch Exception _ nil))]
               (if-let [[_ q] (re-matches #"[\"`\[](.*)[\"`\]]" n)] [q true] [(str/lower-case n) false])))))

(defn quoted?
  "Whether sql/format's options quote a name, as the loaded HoneySQL writes one."
  [format]
  (boolean (second (formatted-name :a format false))))

(defn- sql-word?
  "Whether HoneySQL writes keyword v as the SQL word w (lower-case; v of any case) where the
   format leaves it unquoted (quoted?), and :quoted-always does not match it."
  [v w {:keys [quoted-always] :as format}]
  (and (keyword? v) (nil? (namespace v)) (= w (str/lower-case (name v))) (not (quoted? format))
       (not (some-> quoted-always (re-find (name v))))))

(defn null-value?
  "Whether HoneySQL writes v as NULL: nil, [:inline nil], [:lift nil], or :null."
  [v format]
  (or (nil? v) (#{[:inline nil] [:lift nil]} v) (sql-word? v "null" format)))

(defn truth-value?
  "Whether HoneySQL writes v as TRUE, FALSE or UNKNOWN, which IS [NOT] tests a boolean for:
   true, false, [:inline b], or :true, :false or :unknown."
  [v format]
  (or (boolean? v) (and (vector? v) (= :inline (first v)) (boolean? (second v)))
      (some #(sql-word? v % format) ["true" "false" "unknown"])))

(def array-comparisons
  "ANY(a), SOME(a) and ALL(a), the right operand of an operator over a's elements (docs 9.24.3)."
  #{:any :some :all})

(defn array-of-elements?
  "Whether x is one of array-comparisons."
  [x]
  (and (vector? x) (= 2 (count x)) (array-comparisons (first x))))

(defn- as-vectors
  "x with each seq a vector, as HoneySQL reads a sequential (what map builds); and {:table t},
   TABLE t, as the SELECT * FROM t it is (docs 7.2.1); {:nest q}, (q), as q; a function's argument
   {:distinct x :order-by [...]} as [:order-by [:distinct x] ...]; an :at-time-zone zone named by
   a keyword as the string it is: the SQL HoneySQL writes of each.
   The rows of :values, :set and :do-update-set are maps of columns, not statements."
  [x]
  (let [row #(if (map? %) (update-vals % as-vectors) (as-vectors %))]
    (cond (seq? x) (mapv as-vectors x)
          (map? x) (let [m (into (empty x) (map (fn [[k v]] [k (cond (#{:set :do-update-set} k) (row v)
                                                                      (and (= :values k) (sequential? v)) (mapv row v)
                                                                      :else (as-vectors v))]))
                                 x)]
                     (cond (and (contains? m :table) (not (statement? m)) (not (contains? m :from)))
                           (-> m (dissoc :table) (assoc :select [:*] :from [(:table m)]))
                           (and (= [:nest] (keys m)) (map? (:nest m))) (:nest m)
                           :else m))
          ;; HoneySQL writes a zone it names (:UTC, :?z) as a string literal
          (and (vector? x) (= :at-time-zone (first x)) (= 3 (count x)) (ident? (nth x 2)))
          (as-vectors (assoc x 2 [:inline (name (nth x 2))]))
          (and (vector? x) (keyword? (first x)))
          (into [(first x)] (map #(let [v (as-vectors %)]
                                    (if (and (map? %) (contains? % :distinct) (every? #{:distinct :order-by} (keys %)))
                                      (let [d [:distinct (:distinct v)] ob (:order-by v)]
                                        (if ob (into [:order-by d] (if (vector? ob) ob [ob])) d))
                                      v)))
                (rest x))
          (branch? x) (into (empty x) (map as-vectors) x)
          :else x)))

(defn- code-at
  "[path symbol] of the first symbol in x, path where it sits (a map key's, the key's): a
   symbol is code (a quoted form), not data sql/format takes."
  [x path]
  (cond (symbol? x) [path x]
        (map? x) (some (fn [[k v]] (if (symbol? k) [(conj path k) k] (code-at v (conj path k)))) x)
        (and (sequential? x) (branch? x)) (some (fn [[i v]] (code-at v (conj path i))) (map-indexed vector x))))

(defn written-path
  "path, a path in what query! reads of body, as a path in body as written: through each
   {:nest q} as-vectors reads as q."
  [body path]
  (let [nest? #(and (map? %) (= [:nest] (keys %)))]
    (loop [node body [i :as more] path out []]
      (cond (nest? node) (recur (:nest node) more (conj out :nest))
            (empty? more) out
            :else (recur (if (and (map? node) (int? i)) (nth (seq node) i nil) (get node i)) (rest more) (conj out i))))))

(defn query!
  "body as HoneySQL query data (as-vectors); an error saying so for SQL already formatted, for
   nothing, which would otherwise have no problem, for a registry (the arguments swapped), and for
   code."
  [body]
  (when (some shape/generated-name? (when (map? body) (keys body)))
    (error/fail! :pgmalli.error/not-query-data
                 "pgmalli-next.honeysql takes the registry first, then the query: (analyze registry query)"
                 {:given (vec (take 5 (keys body)))}))
  (when (or (nil? body) (string? body) (and (vector? body) (string? (first body))))
    (error/fail! :pgmalli.error/not-query-data
                 (str "pgmalli-next.honeysql reads HoneySQL query data (a map such as {:select ...}), not "
                      (if (nil? body) "nil" "SQL already formatted") "; pass the map you give sql/format")
                 {:given body}))
  (when-let [[path sym] (code-at body [])]
    (error/fail! :pgmalli.error/not-query-data
                 (str "pgmalli-next.honeysql reads HoneySQL query data, not code: " (pr-str sym) " at " (pr-str path)
                      " is a symbol; write a name as a keyword ([:cast :score :integer]), or, for code, pass the data it builds, as you give it to sql/format, each variant separately")
                 {:given body :in path}))
  (as-vectors body))

(def ^:private grammar-operators
  "The operators PostgreSQL's grammar writes words as."
  {"LIKE" "~~" "NOT LIKE" "!~~" "ILIKE" "~~*" "NOT ILIKE" "!~~*"})

(defn operator-name
  "The SQL operator HoneySQL writes [h a b] with (:not= as <>, :like as ~~), or [h a] as a
   prefix one (- a; [:@@ a] is a alone). nil for SQL syntax that is no operator (AND, IN,
   IS ...) or one PostgreSQL lacks (REGEXP)."
  [h arity]
  (let [s (some->> (try (first (sql/format-expr (if (= 1 arity) [h ::r] [h ::l ::r]))) (catch Exception _ nil))
                   (re-matches (if (= 1 arity) #"(?s)(.+) \S+" #"(?s)\S+ (.+) \S+")) second)]
    (or (grammar-operators s) (when (and s (re-matches #"[+\-*/<>=~!@#%^&|`?]+" s)) s))))

(defn call-parts
  "[kind name args schema] of x as pgmalli-next.impl.resolve/call takes it: a function (:functions),
   count(*) of no argument, AT TIME ZONE as timezone(zone, value), or an operator (:operators),
   a prefix one of one operand; schema the one a function's name is qualified with; nil for
   anything else."
  [x]
  (let [h (when (vector? x) (first x))
        fname (or (called x) (shorthand-fn x))
        ;; :%abs.1 is ABS(1); a word between the arguments (TRIM(LEADING FROM s)) is none, and
        ;; EXTRACT's field before it (:year) a name, no column
        args (remove keyword-word?
                     (cond (shorthand-fn x) (map #(if (re-matches #"\d+" %) [:inline (parse-long %)] (keyword %)) (rest (str/split (subs (str x) 2) #"\.")))
                           (vector? x) (rest x)))
        args (if (and (= "extract" fname) (keyword? (first args)) (not (keyword-word? (first args))))
               (cons [:inline (name (first args))] (rest args))
               args)]
    (cond (not (or h fname)) nil
          ;; name => value, a named argument: no operator
          (= :=> h) nil
          (= :at-time-zone h) (let [[_ v z] x] [:functions "timezone" [z v]])
          (and (keyword? h) (operator? h) (#{1 2} (count args)))
          (when-let [n (operator-name h (count args))] [:operators n (vec args)])
          (and fname (not (and h (sql/registered-fn? h))))
          [:functions fname (vec (remove #{:*} args)) (some-> (if (keyword? x) (shorthand-fn x) (name h)) (str/split #"\.") butlast last folded)])))

(defn- keyword-type [s] (str/lower-case (str/replace s "-" " ")))

(defn- float-precision
  "Type name n, a float(p) of p 1 to 24 a real: the grammar reads float(p) by its precision
   (gram.y opt_float), 25 to 53 a double precision as float is."
  [n p]
  (if (and p (<= 1 p 24) (#{"float" "float[]"} n)) (str/replace n "float" "real") n))

(defn cast-target
  "The name of the type a cast's t names: a keyword (:double-precision, :mood, :app.mood),
   [:varchar 10] with its typmod, or [:raw s], s one type's name with or without a typmod and []
   (\"bigint[]\", \"varchar(10)[]\", \"app.mood\"), as an array cast is written: the reader reads
   :bigint[] as :bigint and []; nil for another t. HoneySQL writes a keyword's name upper-case,
   a dash as a space, never quoted (:my-type as MY TYPE), which PostgreSQL folds."
  [t]
  (cond (ident? t) (keyword-type (name t))
        (and (vector? t) (= :raw (first t)) (= 2 (count t)) (string? (second t)))
        (let [raw (-> (second t) str/trim str/lower-case)
              n (-> raw (str/replace #"\(\s*\d+\s*(,\s*-?\d+\s*)?\)" "")
                    (str/replace #"\s+" " ") (str/replace #" ?\[ ?\]$" "[]") str/trim)
              p (some-> (re-find #"^float\s*\(\s*(\d+)\s*\)" raw) second parse-long)]
          (when (re-matches #"[a-z_][\w$]*(\.[a-z_][\w$]*)?( [a-z_]\w*)*(\[\])?" n) (float-precision n p)))
        (and (vector? t) (keyword? (first t)) (not= :raw (first t)))
        ;; a precision written into the SQL ([:inline 10]); a bare 10 HoneySQL sends as a parameter
        (float-precision (keyword-type (name (first t))) (let [p (second t)] (when (and (vector? p) (= :inline (first p)) (integer? (second p))) (second p))))))

(defn constant?
  "Whether x is the same value on every row: an argument, a literal (NULL too), or a cast of one."
  [x]
  (or (param-key x) (nil? x) (number? x) (string? x) (boolean? x) (uuid? x)
      (and (vector? x) (= :cast (first x)) (constant? (second x)))))
