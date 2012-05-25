(ns gavagai.core
  (:use [lazymap.core])
  (:require [clojure.string :as str])
  (:import [java.beans Introspector]))

(defprotocol Clojurable
  "Protocol for conversion of Classes"
  (convert [response] "convert response to Clojure"))

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

(defn- translate-value
  [src]
  (try
    (cond
     (or (nil? src) (number? src) (string? src) (coll? src) (true? src) (false? src)) src
     (instance? java.util.Map src) (persistent! (reduce (fn [acc ^java.util.Map$Entry e]
                                                          (assoc! acc
                                                                  (translate-value (.getKey e))
                                                                  (translate-value (.getValue e))))
                                                        (transient {}) src))
     (or (instance? java.util.List src)
         (.startsWith (.getName (type src)) "[L")) (persistent! (reduce
                                                                 (fn [acc obj]
                                                                   (conj! acc (translate-value obj)))
                                                                 (transient []) src))
     (extends? Clojurable (class src)) (convert src)
     :else src)
    (catch Exception e
      (println e)
      src)))

(defmacro make-converter
  [class-name]
  (let [klass (Class/forName class-name)
        read-methods (get-read-methods klass)
        sig (reduce (fn [acc m]
                      (let [m-name (.getName m)]
                        (assoc acc
                          (keyword (method->arg m))
                          (symbol (str "." m-name)))))
                    {} read-methods)
        return (gensym "return")]
    `(fn
       [~(with-meta return {:tag klass})]
       (let [res# (lazy-hash-map
                   ~@(let [gets (for [[kw getter] sig]
                                  `(~kw (translate-value (~getter ~return))))]
                       (apply concat gets)))]
         res#))))

(defmacro register-converters
  ^{:private true}
  [& conv-defs]
  `(do ~@(for [class-name conv-defs]
           `(let [conv# (make-converter ~class-name)]
              (extend ~(symbol class-name)
                  Clojurable
                  {:convert (fn [return#]
                              (conv# return#))})))))

(def-converters
  "java.lang.reflect.Method"
  "java.lang.annotation.Annotation"
  "java.util.concurrent.ThreadPoolExecutor"
  "java.util.concurrent.LinkedBlockingQueue")

