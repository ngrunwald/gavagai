(ns gavagai.core
  (:use [lazymap.core])
  (:require [clojure.string :as str])
  (:import [java.beans Introspector]
           [java.lang.reflect Method]))

(declare translate-object)
(declare Clojurable)
(declare translate)

(def ^{:dynamic true} *translator-ns*)

(defmacro init-translator!
  []
  `(defprotocol ~'Clojurable
     "Protocol for conversion of Classes"
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

(extend-type (type-array-of Object)
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
  "Recursively translates one Java object to lazy Clojure data.
   Takes an optional :max-depth to handle recursive objects graphs"
  ([obj {:keys [max-depth translate-arrays? depth nspace] :as opts
         :or {depth 0}}]
     (if (and max-depth (>= depth max-depth))
       obj
       (try
         (let [nspace (or nspace *translator-ns*)
               opts* (if (:nspace opts) opts (assoc opts :nspace nspace))]
           (if (extends? (get-var-in-ns nspace 'Clojurable) (class obj))
             ((get-var-in-ns nspace 'translate-object) obj (assoc opts* :depth (inc depth)))
             (translate* obj opts*)))
         (catch Exception e
           (println e)
           (throw e)
           obj))))
  ([obj] (translate obj {})))

(defmacro make-converter
  [class-name & [{:keys [only exclude add] :or {exclude [] add {}}}]]
  (let [klass (Class/forName class-name)
        klass-symb (symbol class-name)
        read-methods (get-read-methods klass)
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
       (let [return# (lazy-hash-map
                      ~@(let [gets (for [[kw getter] sig]
                                     `(~kw (translate (~getter ~obj) ~opts)))
                              adds (for [[kw func] add]
                                     `(~kw (~func ~obj)))]
                          (apply concat (concat gets adds))))]
         return#))))

(defmacro register-converters
  "Registers a converter for a given Java class.
 Format is: [\"java.util.Date [:class]] :add {:string str}"
  [& conv-defs]
  `(do
     (init-translator!)
     ~@(for [[class-name & {:keys [only exclude add] :or {exclude [] add {}}}] conv-defs]
         `(let [conv# (make-converter ~class-name {:only ~only :exclude ~exclude :add ~add})]
            (extend ~(symbol class-name)
              ~'Clojurable
              {:translate-object conv#})))))

(comment
  (register-converters
   ["java.util.Date" :exclude [:class] :add {:string str}])
  (with-translator-ns gavagai.core (translate (java.util.Date.))))
