(ns gavagai.core-test
  (:use [clojure.test])
  (:require [gavagai.core :as g])
  (:import [java.util AbstractMap$SimpleEntry]))

(deftest basic-tests
  (let [trans (g/register-converters
               {:exclude [:class]}
               [["java.util.Date" :add {:string str}]
                ["java.awt.Color"
                 :only [:green #"r.d" :blue]
                 :add {:string str} :lazy? false]
                ["java.util.GregorianCalendar"]
                ["java.util.Currency" :custom-converter (fn [_ o _] (keyword (.getSymbol o)))]])]
    (g/with-translator trans
      (testing "basic translations tests"
        (let [dt (java.util.Date.)]
          (let [tdt (g/translate dt)]
            (is (instance? lazymap.core.LazyPersistentMap tdt))
            (is (= (.getMinutes dt) (:minutes tdt)))
            (is (nil? (:class tdt)))
            (is (= (.toString dt) (:string tdt))))))
      (testing "unboxing boolean"
        (let [cal (java.util.GregorianCalendar.)
              calt (g/translate cal)]
          (is (true? (:lenient? calt)))))
      (testing "testing runtime override of lazyness"
        (let [dt (java.util.Date.)]
          (let [tdt (g/translate dt {:lazy? false})]
            (is (instance? clojure.lang.PersistentHashMap tdt))
            (is (= (.getMinutes dt) (:minutes tdt)))
            (is (nil? (:class tdt)) )
            (is (= (.toString dt) (:string tdt))))))
      (testing "testing non lazy translation and only"
        (let [c (java.awt.Color. 10 10 10)]
          (let [tc (g/translate c)]
            (is (instance? clojure.lang.PersistentArrayMap tc))
            (is (= #{:green :red :blue :string} (into #{} (keys tc)))))))
      (testing "meta handling"
        (is (map? (g/get-class-meta java.awt.Color)))
        (is (= #{:green :red :blue :string} (g/get-class-fields "java.awt.Color")))
        (is (map? (g/get-class-options "java.awt.Color")))
        (is (nil? (g/get-class-meta "java.awt.Colour"))))
      (testing "custom converter"
        (let [cur (java.util.Currency/getInstance "EUR")]
          (is :EUR (g/translate cur)))))))

(deftest lenient-converter
  (let [etr (g/register-converters
             {:throw? false}
             [["foo"]])
        etr (g/register-converters
             etr
             [["bar" :throw? false]])]
    (is (instance? gavagai.core.Translator etr))
    (is (thrown? ClassNotFoundException
                 (g/register-converters
                  [["foo"]])))))

(deftest greedy-tests
  (let [trans (g/register-converters
               {:exclude [:class]}
               [["java.awt.Button" :translate-seqs? true :lazy? false]
                ["java.awt.Button$AccessibleAWTButton"
                 :exclude [#"loc.le"] :lazy? false]])]
    (testing "testing recursivity, max-depth and array translations"
      (let [b (java.awt.Button. "test")]
        (g/with-translator trans
          (let [tb (g/translate b {:max-depth 3})]
            (is (nil? (:locale tb)))
            (is (instance? java.awt.Button$AccessibleAWTButton
                           (get-in tb [:accessible-context :accessible-action :accessible-action])))
            (is (vector? (:action-listeners tb))))))))
  (let [trans (g/register-converters
               {:exclude [:class] :super? true}
               [["java.awt.Button" :translate-seq [:action-listeners]]])]
    (testing "testing array translations compat"
      (let [b (java.awt.Button. "test")]
        (g/with-translator trans
          (let [tb (g/translate b {:max-depth 3})]
            (is (seq? (:action-listeners tb)))))))))

(deftest interface-tests
  (let [cs (java.util.zip.CRC32.)
        trans (g/register-converters
               {:lazy? false :super? true}
               [["java.util.zip.Checksum"]
                ["java.lang.Object" :only [:class]]])]
    (.update cs 8)
    (g/with-translator trans
      (let [cst (g/translate cs {:super? true :max-depth 1})]
        (is (number? (:value cst)))
        (is (= java.util.zip.CRC32 (:class cst)))))))


(defn make-pair
  [k v]
  (proxy [AbstractMap$SimpleEntry] [k v]
    (toString [] (str k))))

(deftest cyclic-ref-tests
  (let [trans (g/register-converters
               {:lazy? false :super? true}
               [["java.util.Map$Entry"]])
        a (make-pair "A" nil)
        b (make-pair "B" a)]
    (.setValue a b)
    (g/with-translator trans
      (let [bean (g/translate a {:omit-cyclic-ref? true})]
        (is (= "A" (:key bean)))
        (is (= "B" (get-in bean [:value :key])))
        (is (= a (get-in bean [:value :value])))))))


