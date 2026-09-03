(ns pgmalli.impl.pghash
  "PostgreSQL's hash functions (src/common/hashfn.c, Bob Jenkins' lookup3, little-endian) as
   the extended, seeded, 64-bit variants hash partitioning uses, and the routing of a row to a
   hash partition (satisfies_hash_partition). Values hash as the database hashes them:
   integers through hashint8extended (which agrees with hashint4 and hashint2 for the values
   they share), text through its bytes (a deterministic collation), booleans and dates as
   32-bit integers, timestamps as microseconds since 2000, uuids as their 16 bytes.")

(def ^:private mask32 0xFFFFFFFF)

(defn- u32 [x] (bit-and x mask32))

(defn- rot [x k] (u32 (bit-or (bit-shift-left x k) (unsigned-bit-shift-right (u32 x) (- 32 k)))))

(defn- mix [a b c]
  (let [a (u32 (- a c)) a (bit-xor a (rot c 4)) c (u32 (+ c b))
        b (u32 (- b a)) b (bit-xor b (rot a 6)) a (u32 (+ a c))
        c (u32 (- c b)) c (bit-xor c (rot b 8)) b (u32 (+ b a))
        a (u32 (- a c)) a (bit-xor a (rot c 16)) c (u32 (+ c b))
        b (u32 (- b a)) b (bit-xor b (rot a 19)) a (u32 (+ a c))
        c (u32 (- c b)) c (bit-xor c (rot b 4)) b (u32 (+ b a))]
    [a b c]))

(defn- final [a b c]
  (let [c (bit-xor c b) c (u32 (- c (rot b 14)))
        a (bit-xor a c) a (u32 (- a (rot c 11)))
        b (bit-xor b a) b (u32 (- b (rot a 25)))
        c (bit-xor c b) c (u32 (- c (rot b 16)))
        a (bit-xor a c) a (u32 (- a (rot c 4)))
        b (bit-xor b a) b (u32 (- b (rot a 14)))
        c (bit-xor c b) c (u32 (- c (rot b 24)))]
    [a b c]))

(defn- seeded
  "The initial state, perturbed by the seed as hash_bytes_extended does."
  [len seed]
  (let [init (u32 (+ 0x9e3779b9 len 3923095))]
    (if (zero? seed)
      [init init init]
      (mix (u32 (+ init (u32 (unsigned-bit-shift-right seed 32)))) (u32 (+ init (u32 seed))) init))))

(defn- word [^bytes k i]
  (u32 (+ (bit-and (aget k i) 0xff)
          (bit-shift-left (bit-and (aget k (+ i 1)) 0xff) 8)
          (bit-shift-left (bit-and (aget k (+ i 2)) 0xff) 16)
          (bit-shift-left (bit-and (aget k (+ i 3)) 0xff) 24))))

(defn- result [b c] (bit-or (bit-shift-left b 32) c))

(defn hash-bytes-extended
  "hash_bytes_extended: a 64-bit hash of bytes with a seed."
  [^bytes k seed]
  (let [len (alength k)
        byte (fn [i] (bit-and (aget k i) 0xff))]
    (loop [[a b c] (seeded len seed) i 0 left len]
      (if (>= left 12)
        (recur (mix (u32 (+ a (word k i))) (u32 (+ b (word k (+ i 4)))) (u32 (+ c (word k (+ i 8))))) (+ i 12) (- left 12))
        (let [;; the last 11 bytes, little-endian
              c (cond-> c (>= left 11) (+ (bit-shift-left (byte (+ i 10)) 24))
                          (>= left 10) (+ (bit-shift-left (byte (+ i 9)) 16))
                          (>= left 9) (+ (bit-shift-left (byte (+ i 8)) 8)))
              b (cond-> b (>= left 8) (+ (word k (+ i 4)))
                          (= left 7) (+ (bit-shift-left (byte (+ i 6)) 16))
                          (#{7 6} left) (+ (bit-shift-left (byte (+ i 5)) 8))
                          (#{7 6 5} left) (+ (byte (+ i 4))))
              a (cond-> a (>= left 4) (+ (word k i))
                          (= left 3) (+ (bit-shift-left (byte (+ i 2)) 16))
                          (#{3 2} left) (+ (bit-shift-left (byte (+ i 1)) 8))
                          (#{3 2 1} left) (+ (byte i)))
              [_ b c] (final (u32 a) (u32 b) (u32 c))]
          (result b c))))))

(defn hash-uint32-extended
  "hash_bytes_uint32_extended: a 64-bit hash of one 32-bit value with a seed."
  [k seed]
  (let [[a b c] (seeded 4 seed)
        [_ b c] (final (u32 (+ a (u32 k))) b c)]
    (result b c)))

(defn hash-int8-extended
  "hashint8extended: the high half folded into the low, so an int4 and an int8 of the same
   value hash alike."
  [v seed]
  (let [lo (u32 v) hi (u32 (bit-shift-right v 32))]
    (hash-uint32-extended (bit-xor lo (if (neg? v) (u32 (bit-not hi)) hi)) seed)))

(defn combine64
  "hash_combine64."
  [a b]
  (bit-xor a (unchecked-add (unchecked-add (unchecked-add b (unchecked-long 0x49a0f4dd15e5a8e3)) (bit-shift-left a 54)) (unsigned-bit-shift-right a 7))))

(def partition-seed (unchecked-long 0x7A5B22367996DCFD))

(def ^:private epoch-2000 (java.time.LocalDate/of 2000 1 1))

(defn hash-value
  "The database's hash of a partition key value with the partition seed, or nil for a value
   pgmalli cannot hash like the database (a numeric, an enum, an interval)."
  [v]
  (cond (nil? v) nil
        (int? v) (hash-int8-extended (long v) partition-seed)
        (string? v) (hash-bytes-extended (.getBytes ^String v "UTF-8") partition-seed)
        (boolean? v) (hash-uint32-extended (if v 1 0) partition-seed)
        (uuid? v) (let [bb (java.nio.ByteBuffer/allocate 16)]
                    (.putLong bb (.getMostSignificantBits ^java.util.UUID v))
                    (.putLong bb (.getLeastSignificantBits ^java.util.UUID v))
                    (hash-bytes-extended (.array bb) partition-seed))
        (bytes? v) (hash-bytes-extended v partition-seed)
        (instance? java.time.LocalDate v) (hash-uint32-extended (.toEpochDay (.minusDays ^java.time.LocalDate v (.toEpochDay epoch-2000))) partition-seed)
        (instance? java.time.Instant v) (let [i ^java.time.Instant v
                                              micros (+ (* (- (.getEpochSecond i) 946684800) 1000000) (quot (.getNano i) 1000))]
                                          (hash-int8-extended micros partition-seed))
        (instance? java.time.LocalDateTime v) (hash-value (.toInstant ^java.time.LocalDateTime v java.time.ZoneOffset/UTC))
        :else nil))

(defn satisfies-hash-partition?
  "Whether a row whose partition key values are vs lands in the partition of the given modulus
   and remainder: the database's satisfies_hash_partition. nil when a value cannot be hashed
   like the database."
  [modulus remainder vs]
  (let [hashes (map hash-value (remove nil? vs))]
    (when (every? some? hashes)
      (let [row-hash (reduce combine64 0 hashes)]
        (= remainder (Long/remainderUnsigned row-hash modulus))))))
