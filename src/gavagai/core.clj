(ns gavagai.core
  (:use [lazymap.core])
  (:require [clojure.string :as str])
  (:import [java.beans Introspector]
           [java.lang.reflect Method]))

(declare translate-object)
(declare Clojurable)

(def ^{:dynamic true} *translator-ns*)

(set! *warn-on-reflection* true)

(defmacro init-translator!
  []
  `(defprotocol ~'Clojurable
     "Protocol for conversion of Classes"
     (~'translate-object [~'obj ~'opts] "Convert response to Clojure data")))

(defn- method->arg
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
  `(binding [*translator-ns* (if (instance? clojure.lang.Namespace ~nspace)
                                ~nspace
                                (find-ns ~nspace))]
     ~@body))

(defn translate
  "Recursively translates one Java object to lazy Clojure data.
   Takes an optional :max-depth to handle recursive objects graphs"
  ([obj {:keys [max-depth translate-arrays? depth nspace] :as opts
         :or {depth 0 nspace *translator-ns*}}]
     (if (and max-depth (>= depth max-depth))
       obj
       (try
         (let [new-depth (inc depth)
               ^Class obj-class (type obj)]
           (cond
            (or (nil? obj) (number? obj) (string? obj) (coll? obj) (true? obj) (false? obj)) obj
            (instance? java.util.Map obj) (persistent!
                                           (reduce
                                            (fn [acc ^java.util.Map$Entry e]
                                              (assoc! acc
                                                      (translate (.getKey e)
                                                                 (assoc opts
                                                                   :nspace nspace
                                                                   :depth new-depth))
                                                      (translate (.getValue e)
                                                                 (assoc opts
                                                                   :nspace nspace
                                                                   :depth new-depth))))
                                                               (transient {}) obj))
            (or (instance? java.util.List obj)
                (and translate-arrays? (.startsWith (.getName obj-class) "[L")))
            (persistent! (reduce
                          (fn [acc obj]
                            (conj! acc (translate obj
                                                  (assoc opts
                                                    :nspace nspace
                                                    :depth new-depth))))
                          (transient []) obj))
            (extends? (var-get (ns-resolve nspace 'Clojurable)) (class obj))
            ((var-get (ns-resolve nspace 'translate-object)) obj (assoc opts
                                                                 :nspace nspace
                                                                 :depth new-depth))
            :else obj))
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
  "Registers a converter for a given Java class. Format is: [\"java.util.concurrent.ThreadPoolExecutor\" :exclude [:class]]"
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
   ["java.util.Date" :exclude [:class]]))
