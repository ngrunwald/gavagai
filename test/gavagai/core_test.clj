(ns gavagai.core-test
  (:use [clojure.test])
  (:require [gavagai.core :as g]))

(deftest basic-tests
  (let [trans (g/register-converters
               {:exclude [:class]}
               [["java.util.Date" :add {:string str}]
                ["java.awt.Color"
                 :only [:green #"r.d" :blue]
                 :add {:string str} :lazy? false]])]
    (g/with-translator trans
      (testing "basic translations tests"
        (let [dt (java.util.Date.)]
          (let [tdt (g/translate dt)]
            (is (instance? lazymap.core.LazyPersistentMap tdt))
            (is (= (.getMinutes dt) (:minutes tdt)))
            (is (nil? (:class tdt)) )
            (is (= (.toString dt) (:string tdt))))))
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
            (is (= #{:green :red :blue :string} (into #{} (keys tc))))))))))

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
            (is (seq? (:action-listeners tb))))))))
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

