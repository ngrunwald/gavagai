(ns gavagai.core
  (:require [clojure.string :as str]
            [lazymap.core :as lz])
  (:import [java.beans Introspector]
           [java.lang.reflect Method]
           [java.util.regex Pattern]))

(declare translate-object)
(declare Clojurable)
(declare translate)

(def ^{:dynamic true} *translator-ns*)

(defmacro init-translator!
  []
  `(defprotocol ~'Clojurable
     "Protocol for conversion of custom Java classes"
     (~'translate-object [~'obj ~'opts] "Convert response to Clojure data")))

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
        (map (fn [m]
               [(.getName m) (method->arg m)])
             (get-read-methods klass))))

(defmacro with-translator-ns
  "Sets the namespace to use for translate call within the body"
  [nspace & body]
  `(binding [*translator-ns* (if (instance? clojure.lang.Namespace '~nspace)
                                '~nspace
                                (find-ns '~nspace))]
     ~@body))

(defn type-array-of
  [t]
  (.getClass (java.lang.reflect.Array/newInstance t 0)))

(def any-array-class (type-array-of Object))

(defprotocol Translator
  "Protocol to translate generic Java objects"
  (translate* [obj opts]))

(extend-type nil
  Translator
  (translate* [obj _] obj))

(extend-type java.util.Map
  Translator
  (translate* [obj {:keys [depth nspace] :as opts
                    :or {depth 0 nspace *translator-ns*}}]
    (persistent!
     (let [new-depth (inc depth)]
       (reduce
        (fn [acc ^java.util.Map$Entry e]
          (assoc! acc
                  (translate (.getKey e)
                             (assoc opts :depth new-depth))
                  (translate (.getValue e)
                             (assoc opts :depth new-depth))))
        (transient {}) obj)))))

(defn translate-list
  [obj {:keys [depth nspace] :as opts
        :or {depth 0 nspace *translator-ns*}}]
  (persistent!
   (reduce
    (fn [acc obj]
      (conj! acc (translate obj
                            (assoc opts :depth (inc depth)))))
    (transient []) obj)))

(extend-type java.util.List
  Translator
  (translate* [obj opts] (translate-list obj opts)))

(extend-type Object
  Translator
  (translate* [obj _] obj))

(defn get-var-in-ns
  [nspace symb]
  (var-get (ns-resolve nspace symb)))

(defn translate
  "Recursively translates a Java object to Clojure data structures.
   Arguments
     :max-depth -> integer (for recursive graph objects)
     :nspace    -> symbol or namespace object
                   (useful if the with-translator-ns macro cannot be used)"
  ([obj {:keys [max-depth depth nspace] :as opts
         :or {depth 0}}
    {:keys [translate-arrays?] :as conf}]
     (if (and max-depth (>= depth max-depth))
       obj
       (try
         (let [nspace (or nspace *translator-ns*)
               opts* (if (:nspace opts) opts (assoc opts :nspace nspace))]
           (cond
            (extends? (get-var-in-ns nspace 'Clojurable) (class obj))
            ((get-var-in-ns nspace 'translate-object) obj (assoc opts* :depth (inc depth)))
            (and translate-arrays? (instance? any-array-class obj)) (translate-list obj opts*)
            :else (translate* obj opts*)))
         (catch Exception e
           (println e)
           (throw e)
           obj))))
  ([obj opts] (translate obj opts {}))
  ([obj] (translate obj {} {})))

(defn- convert-to-pattern
  [elt]
  (if (instance? Pattern elt)
    elt
    (Pattern/compile (Pattern/quote (name elt)))))

(defmacro make-converter
  [class-name & [{:keys [only exclude add lazy? translate-arrays?]
                  :or {exclude [] add {} lazy? true} :as opts}]]
  (let [klass (Class/forName class-name)
        klass-symb (symbol class-name)
        read-methods (get-read-methods klass)
        conf {:translate-arrays? translate-arrays?}
        full-exclude (map convert-to-pattern exclude)
        full-only (map convert-to-pattern only)
        fields-nb (if only
                    (+ (count only) (count add))
                    (- (+ (count read-methods) (count add)) (count exclude)))
        sig (reduce (fn [acc ^Method m]
                      (let [m-name (.getName m)
                            k-name (keyword (method->arg m))
                            m-call (symbol (str "." m-name))]
                        (cond
                         (some #(re-matches % (name k-name)) full-exclude) acc
                         (and only (some #(re-matches % (name k-name)) full-only)) (assoc acc k-name m-call)
                         (not (empty? only)) acc
                         :else (assoc acc k-name m-call))))
                    {} read-methods)
        obj (with-meta (gensym "obj") {:tag klass-symb})
        opts (gensym "opts")
        depth (gensym "depth")]
    `(fn
       [~obj ~opts]
       (let [return# (~(if lazy? `lz/lazy-hash-map (if (> fields-nb 8) `hash-map `array-map))
                      ~@(let [gets (for [[kw getter] sig]
                                     `(~kw (translate (~getter ~obj) ~opts ~conf)))
                              adds (for [[kw func] add]
                                     `(~kw (~func ~obj)))]
                          (apply concat (concat gets adds))))]
         return#))))

(defn default-map
  [base default]
  (merge-with
   (fn [b d]
     (if (nil? b) d b))
   base default))

(defmacro register-converters
  "Registers a converter for a given Java class given as a String
   Optional arguments
     - :only    (vector)  -> only translate these methods
     - :exclude (vector)  -> exclude the methods from translation (keywords or patterns)
     - :add     (hash)    -> add under the given key the result of applying
                             the function given as val to the object
     - :lazy?   (bool)    -> should the returned map be lazy or not
                             (lazy by default)
     - :translate-arrays? (bool) -> translate native arrays to vectors
                                    (false by default)
   Example
     (register-converters
       [\"java.util.Date\" :exclude [:class] :add {:string str} :lazy? false])"
  [default & conv-defs]
  (let [[full-conv-defs default-opts] (if (map? default)
                                        [conv-defs default]
                                        [(conj conv-defs default) {}])
        added (count full-conv-defs)]
    `(do
      (init-translator!)
      ~@(for [[class-name & {:as opt-spec}] full-conv-defs
              :let [given-opts (default-map opt-spec
                                 {:exclude [] :add {} :lazy? true})
                    full-opts (merge-with
                               (fn [default over]
                                 (cond
                                  (every? map? [default over]) (merge default over)
                                  (every? coll? [default over]) (distinct (concat default over))
                                  :else over))
                               default-opts given-opts)]]
          `(let [conv# (make-converter ~class-name ~full-opts)]
             (extend ~(symbol class-name)
               ~'Clojurable
               {:translate-object conv#})))
      ~added)))

(comment
  (register-converters
   ["java.util.Date" :exclude [:class] :add {:string str}])
  (with-translator-ns gavagai.core (translate (java.util.Date.))))