(ns gavagai.core
  (:require [clojure.string :as str]
            [lazymap.core :as lz]
            [clojure.set :as set])
  (:import [java.beans Introspector]
           [java.lang.reflect Method]
           [java.util.regex Pattern]))

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

(defn translate-with
  [translator ^Object obj {:keys [depth max-depth] :as opts}]
  (when-not (nil? obj)
    (let [klass (.getClass obj)]
      (if-let [converter (get-in translator [:registry klass])]
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

(defn translate-seq
  [translator obj {:keys [depth max-depth] :as opts}]
  (persistent!
   (reduce
    (fn [acc obj]
      (conj! acc (translate
                  translator
                  obj
                  (if max-depth
                    (assoc opts
                      :depth (safe-inc depth))
                    opts))))
    (transient []) obj)))

(defn translate
  "Recursively translates a Java object to Clojure data structures.
   Arguments
     :max-depth         (integer) -> for recursive graph objects, to avoid infinite loops
     :lazy?             (bool)    -> overrides the param given in the spec
                                     (true by default)"
  ([translator obj {:keys [max-depth] :as opts}]
     (if (and max-depth (>= (get opts :depth 0) max-depth))
       obj
       (try
         (let [conv (translate-with translator obj opts)]
           (cond
            conv conv
            (and (:translate-seqs? opts)
                 (instance? any-array-class obj))
            (translate-seq translator obj opts)
            :else (translate* obj translator opts)))
         (catch Exception e
           (throw e)))))
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
                (translate
                 translator
                 (.getKey e)
                 (if max-depth
                   (assoc opts :depth (safe-inc depth))
                   opts))
                (translate
                 translator
                 (.getValue e)
                 (if max-depth
                   (assoc opts :depth (safe-inc depth))
                   opts))))
      (transient {}) obj))))

(extend-type java.util.List
  Translatable
  (translate* [obj translator opts] (translate-seq translator obj opts)))

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
  ([^Method m {:keys [translate-seqs?]}]
     (let [r-type (.getReturnType m)]
       (if (and translate-seqs?
                (or (.isArray r-type)
                    (is-iterable-class? r-type)))
         (fn [translator obj opts]
           (translate-seq translator (.invoke m obj empty-array) opts))
         (fn [translator obj opts]
           (translate translator (.invoke m obj empty-array) opts)))))
  ([m] (make-getter m {})))

(defn- convert-to-pattern
  [elt]
  (if (instance? Pattern elt)
    elt
    (Pattern/compile (Pattern/quote (name elt)))))

(defn make-converter
  [class-name & [{:keys [only exclude add lazy? translate-seqs?]
                  :or {exclude [] add {} lazy? true} :as opts}]]
  (let [klass (Class/forName class-name)
        klass-symb (symbol class-name)
        read-methods (get-read-methods klass)
        conf {:translate-seqs? translate-seqs?}
        full-exclude (map convert-to-pattern exclude)
        full-only (map convert-to-pattern only)
        fields-nb (if only
                    (+ (count only) (count add))
                    (- (+ (count read-methods) (count add)) (count exclude)))
        hash-fn (if (> fields-nb 8) hash-map array-map)
        mets (reduce (fn [acc ^Method m]
                       (let [m-name (.getName m)
                             k-name (keyword (method->arg m))]
                         (cond
                          (some #(re-matches % (name k-name)) full-exclude) acc
                          (and only (some #(re-matches % (name k-name)) full-only))
                          (assoc acc k-name (make-getter m conf))
                          (not (empty? only)) acc
                          :else (assoc acc k-name (make-getter m conf)))))
                     {} read-methods)]
    (fn
      [translator obj opts]
      (let [lazy-over? (get opts :lazy? lazy?)
            map-fn (if lazy-over?
                     lz/lazy-hash-map*
                     hash-fn)
            return (apply
                    map-fn
                    (concat
                     (mapcat (fn [[kw getter]]
                               (list kw (getter translator obj (merge opts conf)))) mets)
                     (mapcat (fn [[kw f]]
                               (list kw (f obj))) add)))]
        return))))

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

(defn register-converter
  "Registers a converter for class-name."
  ([translator [class-name opts]]
     (let [klass (Class/forName class-name)
           converter (make-converter class-name opts)
           full-converter (vary-meta converter assoc :gavagai-spec opts)]
       (update-in translator [:registry] assoc klass full-converter)))
  ([spec] (register-converter *translator* spec)))

(defn unregister-converter
  "Unregisters the class-name from the translator"
  ([translator class-name]
     (let [klass (Class/forName class-name)]
       (dissoc-in translator [:registry klass])))
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
     - :translate-seqs? (bool) -> translate native arrays to vectors
                                  (false by default)
     - :super?  (bool)    -> should the created translator check ancestors and
                             interfaces for converters (false by default and not used
                             if a Translator is explicitely given)
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
         (let [given-opts (default-map opt-spec
                            {:exclude [] :add {} :lazy? true})
               full-opts (merge-with
                          (fn [default over]
                            (cond
                             (every? map? [default over]) (merge default over)
                             (every? coll? [default over]) (distinct (concat default over))
                             :else over))
                          default given-opts)]
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
