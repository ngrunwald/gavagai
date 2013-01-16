(ns gavagai.core
  (:require [clojure.string :as str]
            [lazymap.core :as lz]
            [clojure.set :as set])
  (:import [java.beans Introspector]
           [java.lang.reflect Method]
           [java.util.regex Pattern]
           [java.util HashSet]))

(declare translate)

(defrecord Translator [registry super?])

(defprotocol Translatable
  "Protocol to translate generic Java objects"
  (translate* [obj translator opts]))

(def ^:dynamic *translator* nil)

(defn method->arg
  [^Method method]
  (let [name (.getName method)
        typ (.getReturnType method)
        conv (str/replace name #"^get" "")
        norm (str/lower-case (str/replace conv #"(\p{Lower})(\p{Upper})" "$1-$2"))
        full (if (= (.getName typ) "boolean") (-> norm (str "?") (str/replace #"^is-" "")) norm)]
    (keyword full)))

(defn get-read-methods
  [klass]
  (reduce (fn [acc ^java.beans.PropertyDescriptor pd]
            (let [method (.getReadMethod pd)]
              (if (and method
                       (zero? (alength (.getParameterTypes method))))
                (conj acc method)
                acc)))
          #{}
          (seq (.. Introspector
                   (getBeanInfo klass)
                   (getPropertyDescriptors)))))

(defn class-for-name
  [class-name throw?]
  (try
    (Class/forName class-name)
    (catch ClassNotFoundException e
      (if throw?
        (throw e)))))

(defn inspect-class
  "Inspects a class and returns a seq of methods name to their gavagai keyword representation"
  [klass]
  (into {}
        (map (fn [^Method m]
               [(.getName m) (method->arg m)])
             (get-read-methods klass))))

(defn safe-inc
  [n]
  (if n (inc n) 1))

(defn get-super-converter*
  [translator klass]
  (let [known (into #{}
                    (-> translator
                        (:registry)
                        (keys)))
        asc (ancestors klass)
        super (set/intersection known asc)]
    (when-not (empty? super)
      (let [convs (map #((:registry translator) %) super)]
        (fn [trans obj opts]
          (apply merge
                 (map
                  (fn [conv]
                    (conv trans obj opts))
                  convs)))))))

(def get-super-converter (memoize get-super-converter*))

(defn get-converter
  ([translator klass]
     (get-in translator [:registry klass]))
  ([klass]
     (get-converter *translator* klass)))


(defn get-class-meta
  ([translator klass]
     (let [full-klass (if (string? klass)
                        (class-for-name klass false)
                        klass)]
       (if full-klass
         (meta (get-converter translator full-klass)))))
  ([klass] (get-class-meta *translator* klass)))

(defn get-class-fields
  "Returns the fields of the translated map for the given class, with
   all Translators options taken into account. nil if the class is not
   registered."
  ([translator klass]
     (::fields (get-class-meta translator klass)))
  ([klass] (get-class-fields *translator* klass)))

(defn get-class-options
  "Returns the options used when registering the converter for this
   class."
  ([translator klass]
     (::options (get-class-meta translator klass)))
  ([klass] (get-class-options *translator* klass)))


(defn translate-with
  [translator ^Object obj {:keys [depth max-depth] :as opts}]
  (when-not (nil? obj)
    (let [klass (.getClass obj)]
      (if-let [converter (get-converter translator klass)]
        (converter
         translator
         obj
         (if max-depth
           (assoc opts :depth (safe-inc depth))
           opts))
        (when-let [super (and (:super? translator) (get-super-converter translator klass))]
          (super
           translator
           obj
           (if max-depth
             (assoc opts :depth (safe-inc depth))
             opts)))))))


(def ^{:dynamic true :tag HashSet} *cyclic-refs* nil)

(defn translate-impl
  [translator obj {:keys [max-depth depth]
                   :or {depth 0} :as opts}]
  (if (and max-depth (>= (get opts :depth 0) max-depth))
    obj
    (try
      (let [identity-hashcode (System/identityHashCode obj)]
        (if-not (contains? *cyclic-refs* identity-hashcode)
          ;; if cyclic-refs is nul then contains? always returns false
          (do (when *cyclic-refs*
                ;; mark ref
                (.add *cyclic-refs* identity-hashcode))

              (if-let [conv (translate-with translator obj opts)]
                conv
                (translate* obj translator opts)))

          obj))
      (catch Exception e
        (throw e)))))

(defn translate-seq
  [translator obj {:keys [lazy? depth max-depth] :as opts}]
  (let [t-fn (fn [elt]
               (translate-impl translator elt (if max-depth
                                                (assoc opts
                                                  :depth (safe-inc depth))
                                                opts)))]
    (if lazy?
      (map t-fn obj)
      (persistent!
       (reduce (fn [acc elt] (conj! acc (t-fn elt))) (transient []) obj)))))

(defn translate
  "Recursively translates a Java object to Clojure data structures.
   Arguments
     :max-depth         (integer) -> for recursive graph objects, to avoid infinite loops
     :lazy?             (bool)    -> overrides the param given in the spec
                                     (true by default)
     :omit-cyclic-ref?  (bool)    -> when set to true, the translator will not convert references
                                     that were already converted in the object graph.
                                     (default is false)"
  ([translator root-obj {:keys [omit-cyclic-ref?] :as opts}]
     (if omit-cyclic-ref?
       (binding [*cyclic-refs* (HashSet.)]
         (translate-impl translator root-obj opts))
       (translate-impl translator root-obj opts)))
  ([obj opts]
     (translate *translator* obj opts))
  ([obj]
     (translate *translator* obj {})))

(extend-type nil
  Translatable
  (translate* [obj _ _] obj))

(extend-type java.util.Map
  Translatable
  (translate* [obj translator {:keys [depth max-depth] :as opts}]
    (persistent!
     (reduce
      (fn [acc ^java.util.Map$Entry e]
        (assoc! acc
                (translate-impl
                 translator
                 (.getKey e)
                 (if max-depth
                   (assoc opts :depth (safe-inc depth))
                   opts))
                (translate-impl
                 translator
                 (.getValue e)
                 (if max-depth
                   (assoc opts :depth (safe-inc depth))
                   opts))))
      (transient {}) obj))))

(extend-type java.util.List
  Translatable
  (translate* [obj translator opts] (translate-seq translator obj opts)))

(extend-type Boolean
  Translatable
  (translate* [obj _ _] (Boolean/valueOf obj)))

(extend-type Integer
  Translatable
  (translate* [obj _ _] (Integer/valueOf obj)))

(extend-type Double
  Translatable
  (translate* [obj _ _] (Double/valueOf obj)))

(extend-type Long
  Translatable
  (translate* [obj _ _] (Long/valueOf obj)))

(extend-type Float
  Translatable
  (translate* [obj _ _] (Float/valueOf obj)))

(extend-type Byte
  Translatable
  (translate* [obj _ _] (Byte/valueOf obj)))

(extend-type Short
  Translatable
  (translate* [obj _ _] (Short/valueOf obj)))

(extend-type Object
  Translatable
  (translate* [obj _ _]
    obj))

(def empty-array (object-array 0))

(defn invoke-method
  [^Method m obj]
  (.invoke m obj empty-array))

(defn is-iterable-class?
  [^Class klass]
  (let [ifs (into #{} (.getInterfaces klass))]
    (contains? ifs Iterable)))

(defn make-getter
  ([^Method m {:keys [translate-seqs?]} t-seq?]
     (let [r-type (.getReturnType m)]
       (cond
        (and (or translate-seqs? t-seq?)
             (or (.isArray r-type)
                 (is-iterable-class? r-type)))
        (fn [translator obj opts]
          (translate-seq translator (.invoke m obj empty-array) opts))
        :else (fn [translator obj opts]
                (translate-impl translator (.invoke m obj empty-array) opts)))))
  ([m] (make-getter m {} false)))

(defn- convert-to-pattern
  [elt]
  (if (instance? Pattern elt)
    elt
    (Pattern/compile (Pattern/quote (name elt)))))

(defn match-in-list?
  [l n]
  (some #(re-matches % (name n)) l))

(defn- make-converter-name
  [^Class klass]
  (let [nam (.getName klass)]
    (symbol (str "convert-" nam))))

(defn make-converter
  [class-name & [{:keys [only exclude add lazy? translate-seqs? translate-seq throw?]
                  :or {exclude [] add {} lazy? true translate-seq [] throw? true}
                  :as conv-conf}]]
  (if-let [klass (class-for-name class-name throw?)]
    (let [klass-symb (symbol class-name)
          conv-name (make-converter-name klass)
          read-methods (get-read-methods klass)
          conf {:translate-seqs? translate-seqs?}
          full-exclude (map convert-to-pattern exclude)
          full-only (map convert-to-pattern only)
          full-seq (map convert-to-pattern translate-seq)
          fields-nb (if only
                      (+ (count only) (count add))
                      (- (+ (count read-methods) (count add)) (count exclude)))
          hash-fn (if (> fields-nb 8) hash-map array-map)
          mets (reduce (fn [acc ^Method m]
                         (let [m-name (.getName m)
                               k-name (keyword (method->arg m))
                               t-seq? (match-in-list? full-seq k-name)]
                           (cond
                            (match-in-list? full-exclude k-name) acc
                            (and only (match-in-list? full-only k-name))
                            (assoc acc k-name (make-getter m conf t-seq?))
                            (not (empty? only)) acc
                            :else (assoc acc k-name (make-getter m conf t-seq?)))))
                       {} read-methods)]
      (vary-meta
        (fn
          [translator obj opts]
          (let [lazy-over? (get opts :lazy? lazy?)
                map-fn (if lazy-over?
                         lz/lazy-hash-map*
                         hash-fn)
                return (apply
                        map-fn
                        (concat
                         (mapcat
                          (fn [[kw getter]]
                            (list kw
                                  (getter translator obj (merge opts conf {:lazy? lazy-over?}))))
                          mets)
                         (mapcat
                          (fn [[kw f]]
                            (list kw (f obj)))
                          add)))]
            return))
        assoc
        ::fields (into #{} (concat (keys mets) (keys add))) ::options conv-conf
        ::class klass :name conv-name))))

(defn default-map
  [base default]
  (merge-with
   (fn [b d]
     (if (nil? b) d b))
   base default))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn make-translator
  "Creates an empty Translator. Optoional parameter to have translator check
  ancestors and interfaces for converters"
  ([] (Translator. {} false))
  ([super?] (Translator. {} super?)))

(defn add-converter
  "Adds a converter function to a translator for the given Class. The converter
   is a plain Clojure function that takes 3 parameters: [translator object runtime-opts]"
  ([translator class-or-name converter opts]
     (let [klass (cond
                  (instance? java.lang.Class class-or-name) class-or-name
                  (string? class-or-name)
                  (class-for-name class-or-name (:throw? opts))
                  :else (throw
                         (ex-info
                          (str "Not a suitable (String or Class) converter target: " class-or-name)
                          {:arg class-or-name})))]
       (if klass
         (update-in translator [:registry] assoc klass converter)
         translator)))
  ([translator class-or-name converter]
     (add-converter translator class-or-name converter {})))

(defn remove-converter
  "Removes a converter function from a translator for the given Class"
  [translator klass]
  (dissoc-in translator [:registry klass]))

(defn register-converter
  "Registers a converter for class-name."
  ([translator [class-name opts]]
     (if-let [converter (make-converter class-name opts)]
       (let [klass (Class/forName class-name)
             full-converter (vary-meta converter assoc :gavagai-spec opts)]
         (add-converter translator klass converter))
       translator))
  ([spec] (register-converter *translator* spec)))

(defn unregister-converter
  "Unregisters the class-name from the translator"
  ([translator class-name]
     (let [klass (Class/forName class-name)]
       (remove-converter translator klass)))
  ([class-name] (unregister-converter *translator* class-name)))

(defn register-converters
   "Registers a converter for a given Java class given as a String.
   Takes an optional Translator instance (will be created if not
   given) and an optional map defining default options. Individual
   option maps are merged with defaults.
   Optional arguments
     - :only    (vector)  -> only translate these methods
     - :exclude (vector)  -> exclude the methods from translation (keywords or patterns)
     - :add     (hash)    -> add under the given key the result of applying
                             the function given as val to the object
     - :lazy?   (bool)    -> should the returned map be lazy or not
                             (lazy by default)
     - :translate-seqs? (bool)  -> translate seq-like things (iterables and arrays) to seqs
                                   or vectors if not lazy (false by default)
     - :translate-seq (vector)  -> translate seq-like things (iterables and arrays) to seqs
                                   or vector only for these methods
     - :super?  (bool)    -> should the created translator check ancestors and
                             interfaces for converters (false by default and not used
                             if a Translator is explicitely given)
     - :throw? (bool)     -> whether trying to register a converter for a class that does
                             not exist should throw an exception or be silently ignored.
                             (true by default)
   Example
     (register-converters
       [[\"java.util.Date\" :exclude [:class] :add {:string str} :lazy? false]])

   is equivalent to:
    (register-converters
       (make-translator false)
       {:exclude [:class] :lazy? false}
       [[\"java.util.Date\" :add {:string str}]])"
   ([translator default conv-defs]
      (reduce
       (fn [trans [class-name & {:as opt-spec}]]
         (let [full-default (default-map default {:lazy? true :throw? true})
               given-opts (default-map opt-spec
                            {:exclude [] :add {} :translate-seq []})
               full-opts (merge-with
                          (fn [default over]
                            (cond
                             (every? map? [default over]) (merge default over)
                             (every? coll? [default over]) (distinct (concat default over))
                             :else over))
                          full-default given-opts)]
           (register-converter trans [class-name full-opts])))
       translator conv-defs))
   ([param conv-defs]
      (if (instance? Translator param)
        (register-converters param {} conv-defs)
        (register-converters (or *translator* (make-translator (:super? param))) param conv-defs)))
   ([conv-defs]
      (register-converters (or *translator* (make-translator)) {} conv-defs)))

(defmacro with-translator
  [translator & body]
  `(binding [*translator* ~translator]
     ~@body))
