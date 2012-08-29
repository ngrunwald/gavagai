(ns gavagai.core
  (:require [clojure.string :as str]
            [lazymap.core :as lz])
  (:import [java.beans Introspector]
           [java.lang.reflect Method]))

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

(defmacro with-translator-ns
  "Sets the namespace to use for translate call within the body"
  [nspace & body]
  `(binding [*translator-ns* (if (instance? clojure.lang.Namespace '~nspace)
                                '~nspace
                                (find-ns '~nspace))]
     ~@body))

;; (defmacro with-current-ns
;;   [& body]
;;   `(binding [*translator-ns* '~*ns*]
;;      ~@body))

(defn type-array-of
  [t]
  (.getClass (java.lang.reflect.Array/newInstance t 0)))

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

;; TODO Find out why this does not work
;; (extend-type (type-array-of Object)
;;   Translator
;;   (translate* [obj {:keys [translate-arrays?] :as opts}]
;;     (if translate-arrays?
;;       (translate-list obj opts)
;;       obj)))

(extend-type (type-array-of String)
  Translator
  (translate* [obj {:keys [translate-arrays?] :as opts}]
    (if translate-arrays?
      (translate-list obj opts)
      obj)))

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
  ([obj {:keys [max-depth translate-arrays? depth nspace] :as opts
         :or {depth 0}}]
     (if (and max-depth (>= depth max-depth))
       obj
       (try
         (let [nspace (or nspace *translator-ns*)
               opts* (if (:nspace opts) opts (assoc opts :nspace nspace))]
           (println "Clojurable" (extends? (get-var-in-ns nspace 'Clojurable) (class obj)))
           (if (extends? (get-var-in-ns nspace 'Clojurable) (class obj))
             ((get-var-in-ns nspace 'translate-object) obj (assoc opts* :depth (inc depth)))
             (translate* obj opts*)))
         (catch Exception e
           (println e)
           (throw e)
           obj))))
  ([obj] (translate obj {})))

(defmacro make-converter
  [class-name & [{:keys [only exclude add lazy?]
                  :or {exclude [] add {} lazy? true}}]]
  (let [klass (Class/forName class-name)
        klass-symb (symbol class-name)
        read-methods (get-read-methods klass)
        fields-nb (if only
                    (+ (count only) (count add))
                    (- (+ (count read-methods) (count add)) (count exclude)))
        sig (reduce (fn [acc ^Method m]
                      (let [m-name (.getName m)
                            k-name (keyword (method->arg m))
                            m-call (symbol (str "." m-name))]
                        (cond
                         ((into #{} exclude) k-name) acc
                         (and only ((into #{} only) k-name)) (assoc acc k-name m-call)
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
                                     `(~kw (translate (~getter ~obj) ~opts)))
                              adds (for [[kw func] add]
                                     `(~kw (~func ~obj)))]
                          (apply concat (concat gets adds))))]
         return#))))

(defmacro register-converters
  "Registers a converter for a given Java class given as a String
   Optional arguments
     - :only    (vector) -> only translate these methods
     - :exclude (vector) -> exclude the methods from translation
     - :add     (hash)   -> add under the given key the result of applying
                            the function given as val to the object
     - :lazy?   (bool)   -> should the returned map be lazy or not
                            (lazy by default)
   Example
     (register-converters
       [\"java.util.Date\" :exclude [:class] :add {:string str} :lazy? false])"
  [& conv-defs]
  (let [added (count conv-defs)]
    `(do
      (init-translator!)
      ~@(for [[class-name & {:keys [only exclude add lazy?]
                             :or {exclude [] add {} lazy? true}}] conv-defs]
          `(let [conv# (make-converter ~class-name {:only ~only :exclude ~exclude :add ~add :lazy? ~lazy?})]
             (extend ~(symbol class-name)
               ~'Clojurable
               {:translate-object conv#})))
      ~added)))

(comment
  (register-converters
   ["java.util.Date" :exclude [:class] :add {:string str}])
  (with-translator-ns gavagai.core (translate (java.util.Date.))))
