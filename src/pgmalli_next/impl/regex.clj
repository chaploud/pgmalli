(ns pgmalli-next.impl.regex
  "PostgreSQL's regular expressions and LIKE patterns as Java regexes, for the [:re] a CHECK's
   ~ or LIKE renders to and for the strings a generator makes of them."
  (:require [clojure.string :as str]))

(defn- posix-classes
  "[[:digit:]] and friends as the \\p{Digit} classes Java understands."
  [re]
  (str/replace re #"\[:(alpha|digit|alnum|upper|lower|space|punct|xdigit|cntrl|print|graph|blank):\]"
               (fn [[_ c]] (str "\\p{" (if (= "xdigit" c) "XDigit" (str/capitalize c)) "}"))))

(def ^:private non-ascii "\\x{80}-\\x{10FFFF}")

(defn- ascii-set
  "A bracket expression of the ASCII characters the Java regex atom matches, and extra."
  [atom extra]
  (if-let [p (try (re-pattern atom) (catch Exception _ nil))]
    (let [cs (filter #(re-matches p (str (char %))) (range 1 128))]
      (if (or (seq cs) (seq extra))
        (str "[" (apply str (map #(format "\\x%02X" %) cs)) extra "]")
        "(?!)"))
    atom))

(def ^:private escape-or-bracket
  "An escape or a bracket expression of a Java regex: what generation-regex's rewrites leave whole."
  "\\\\.|\\[\\^?\\]?(?:\\\\.|[^\\]\\\\])*\\]")

(def ^:private regex-token
  "An escape, a bracket expression (with its [:class:]es), a (? group opener, {, or a character."
  #"\\.|\[\^?\]?(?:\[:\w+:\]|\\.|[^\]\\])*\]|\(\?<?.?|\{,|[\s\S]")

(defn- java-reads-differently?
  "Whether a token of a PostgreSQL pattern means something else to Java, or nothing:
   - the escapes \\m \\M \\y \\Y (word boundaries), \\Z, \\A, \\v (a vertical tab), \\x (every hex
     digit that follows), \\u \\U \\c, and \\0 to \\9 (octal or back-references), also inside brackets;
   - a [ inside brackets ([[:<:]], [[.-.]], [[=a=]], which Java nests) and && (an intersection);
   - (? other than a group or a lookaround: embedded options;
   - {,n}, which Java reads as text."
  [t]
  (or (some #(re-matches #"\\[mMyYZAvxuUc0-9]" %) (re-seq #"\\." t))
      (and (str/starts-with? t "[") (< 1 (count t))
           (some? (re-find #"\[|&&" (str/replace (subs t 1) #"\\.|\[:\w+:\]" ""))))
      (and (str/starts-with? t "(?") (not (#{"(?:" "(?=" "(?!" "(?<=" "(?<!"} t)))
      (= "{," t)))

(def ^:private regex-forms
  "[loose strict] Java regexes for a PostgreSQL pattern, or nil when it uses syntax Java reads
   differently (java-reads-differently?; a *** director Java rejects). A class (\\w, [[:alpha:]] ...) takes
   its POSIX members in ASCII; outside ASCII the members depend on the server's C library and
   locale, so loose takes every non-ASCII character and strict none: a string strict matches
   PostgreSQL's matches, and one loose does not match PostgreSQL's does not. . is [\\s\\S], as
   PostgreSQL's matches a newline too; $ is \\z, as Java's $ also matches before a final newline;
   \\b is a backspace and \\B a backslash. Memoized: the keys are the regexes of a schema, a
   finite set."
  (memoize
   (fn [re]
     (let [tokens (re-seq regex-token re)
           form (fn [extra]
                  (apply str (for [t tokens]
                               (cond (= "." t) "[\\s\\S]"
                                     (= "$" t) "\\z"
                                     (= "\\b" t) "\\x08"
                                     (= "\\B" t) "\\\\"
                                     (re-matches #"\\[wWdDsS]" t) (ascii-set t extra)
                                     (and (str/starts-with? t "[") (< 1 (count t)))
                                     (let [t (str "[" (posix-classes (subs t 1)))]
                                       (if (re-find #"\\[wWdDsS]|\\p\{" t) (ascii-set t extra) t))
                                     :else t))))
           forms [(form non-ascii) (form "")]]
       (when (and (not-any? java-reads-differently? tokens)
                  (every? #(try (re-pattern %) (catch Exception _ nil)) forms))
         forms)))))

(defn java-regex
  "The Java regex a value PostgreSQL's pattern may match matches (see regex-forms); nil when
   the pattern has syntax Java reads differently."
  [re]
  (first (regex-forms re)))

(defn strict-regex
  "The Java regex java-regex gives, its classes without the non-ASCII characters: a string it
   matches PostgreSQL's pattern matches on any server."
  [re]
  (-> re (str/replace (str "[" non-ascii "]") "(?!)") (str/replace non-ascii "")))

(defn backtracking-regex
  "like-regex's output without its atomic groups, which test.chuck and joined-regex do not read:
   the same strings, as [\\s\\S]* between the parts."
  [re]
  (str/replace re #"\(\?>\[\\s\\S\]\*\?((?:\\.|[^\\()])*)\)" (fn [[_ part]] (str "[\\s\\S]*" part))))

(defn generation-regex
  "The regex a string generator reads, from java-regex's (case-insensitive or not): its strings
   match the regex given. Without the flags and \\z, which test.chuck refuses; classes as their
   ASCII members alone (which every server's classes have); any character a printable ASCII
   one; a case-insensitive one's letters as [aA]; repeats at most 4 more than their least."
  [re]
  (let [ci? (re-find #"^\(\?iu?\)" re)]
    (cond-> (-> re
                (str/replace #"^\(\?iu?\)" "")
                (str/replace #"(?<!\\)\\z" "")
                backtracking-regex
                strict-regex
                (str/replace "[\\s\\S]" "[\\x20-\\x7E]"))
      ci? (str/replace (re-pattern (str escape-or-bracket "|\\p{L}"))
                       #(if (and (= 1 (count %) (count (str/lower-case %)) (count (str/upper-case %))) (not= (str/lower-case %) (str/upper-case %)))
                          (str "[" (str/lower-case %) (str/upper-case %) "]")
                          %))
      ;; repeats bounded: strings short enough for the other parts (a length, a domain) to keep
      true (str/replace (re-pattern (str escape-or-bracket "|\\*|\\+|\\{(\\d+),\\}"))
                        (fn [[m n]] (case m "*" "{0,4}" "+" "{1,4}" (if n (str "{" n "," (+ 4 (parse-long n)) "}") m)))))))

(defn- escape-regex
  "Literal text as a regex: each metacharacter backslashed (not \\Q...\\E, which a string
   generator does not read)."
  [s]
  (str/replace s #"[\\^$.|?*+()\[\]{}]" "\\\\$0"))

(def like-regex
  "The anchored Java regex of a LIKE pattern (backslash escapes % and _), memoized as java-regex is.
   The text between two %s is fixed-length, so its first occurrence is the one to take: an atomic
   group commits to it, which keeps matching linear where [\\s\\S]* between them backtracks
   polynomially ('%a%a%a%b' on a long value)."
  (memoize
   (fn [pattern]
     (let [segments (str/split (str/replace pattern #"\\(.)|%|_|[^%_\\]+"
                                            (fn [[m esc]] (cond esc (escape-regex esc)
                                                                (= m "%") "\u0000"
                                                                (= m "_") "[\\s\\S]"
                                                                :else (escape-regex m))))
                               #"\u0000" -1)]
       (if (= 1 (count segments))
         (str "^" (first segments) "\\z")
         (str "^" (first segments)
              (apply str (for [s (butlast (rest segments)) :when (seq s)] (str "(?>[\\s\\S]*?" s ")")))
              "[\\s\\S]*" (last segments) "\\z"))))))

(defn case-insensitive
  "A Java regex matching regardless of case, of every letter as in the database (~*, ILIKE)."
  [re]
  (str "(?iu)" re))
