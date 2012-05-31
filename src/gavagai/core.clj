(ns gavagai.core
  (:use [lazymap.core])
  (:require [clojure.string :as str])
  (:import [java.beans Introspector]))

(defprotocol Clojurable
  "Protocol for conversion of Classes"
  (translate-object [obj opts depth] "convert response to Clojure"))

(defn- method->arg
  [method]
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

(defn translate
  ([obj {:keys [max-depth save-original?] :or {depth 0} :as opts} depth]
     (if (and max-depth (>= depth max-depth))
       obj
       (try
         (cond
          (or (nil? obj) (number? obj) (string? obj) (coll? obj) (true? obj) (false? obj)) obj
          (instance? java.util.Map obj) (persistent! (reduce (fn [acc ^java.util.Map$Entry e]
                                                                (assoc! acc
                                                                        (translate (.getKey e) opts (inc depth))
                                                                        (translate (.getValue e) opts (inc depth))))
                                                             (transient {}) obj))
          (or (instance? java.util.List obj)
               (.startsWith (.getName (type obj)) "[L")) (persistent! (reduce
                                                                       (fn [acc obj]
                                                                         (conj! acc (translate obj opts (inc depth))))
                                                                       (transient []) obj))
               (extends? Clojurable (class obj)) (translate-object obj opts (inc depth))
               :else obj)
         (catch Exception e
            (println e)
            obj))))
  ([obj opts] (translate obj opts 0))
  ([obj] (translate obj {} 0)))

(defmacro make-converter
  [class-name & [{:keys [only exclude add] :or {only [] exclude [] add {}}}]]
  (let [klass (Class/forName class-name)
        read-methods (get-read-methods klass)
        sig (reduce (fn [acc m]
                      (let [m-name (.getName m)
                            k-name (keyword (method->arg m))
                            m-call (symbol (str "." m-name))]
                        (cond
                         ((into #{} exclude) k-name) acc
                         ((into #{} only) k-name) (assoc acc k-name m-call)
                         (not (empty? only)) acc
                         :else (assoc acc k-name m-call))))
                    {} read-methods)
        obj (gensym "obj")
        opts (gensym "opts")
        depth (gensym "depth")]
    `(fn
       [~(with-meta obj {:tag klass}) ~opts ~depth]
       (let [return# (lazy-hash-map
                      ~@(let [gets (for [[kw getter] sig]
                                     `(~kw (translate (~getter ~obj) ~opts ~depth)))
                              adds (for [[kw func] add]
                                    `(~kw (translate (~func ~obj) ~opts ~depth)))]
                          (apply concat (concat gets adds))))
             return# (if (:save-original? ~opts) (assoc return# ::original ~obj) return#)]
         return#))))

(defmacro register-converters
  ^{:private true}
  [& conv-defs]
  `(do ~@(for [[class-name & {:keys [only exclude] :or {only [] exclude []}}] conv-defs]
           `(let [conv# (make-converter ~class-name {:only ~only :exclude ~exclude})]
              (extend ~(symbol class-name)
                  Clojurable
                  {:translate-object (fn [return# opts# depth#]
                                       (conv# return# opts# depth#))})))))

(register-converters
 ["java.lang.String" :exclude [:class]]
;  "java.lang.reflect.Method"
;  "java.lang.annotation.Annotation"
;  "java.util.concurrent.ThreadPoolExecutor"
;  "java.util.concurrent.LinkedBlockingQueue"
 )

