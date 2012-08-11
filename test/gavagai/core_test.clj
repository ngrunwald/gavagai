(ns gavagai.core-test
  (:use [clojure.test])
  (:require [gavagai.core :as g]))

(g/register-converters
 ["java.util.Date" :exclude [:class] :add {:string str}]
 ["java.awt.Color" :only [:green :red :blue] :add {:string str} :lazy? false]
)

(deftest basic-tests
  (testing "basic translations tests"
    (let [dt (java.util.Date.)]
      (g/with-translator-ns gavagai.core-test
        (let [tdt (g/translate dt)]
          (is (instance? lazymap.core.LazyPersistentMap tdt))
          (is (= (.getMinutes dt) (:minutes tdt)))
          (is (nil? (:class tdt)) )
          (is (= (.toString dt) (:string tdt)))))))
  (testing "testing non lazy translation and only"
    (let [c (java.awt.Color. 10 10 10)]
      (g/with-translator-ns gavagai.core-test
        (let [tc (g/translate c)]
          (is (instance? clojure.lang.PersistentArrayMap tc))
          (is (= #{:green :red :blue :string} (into #{} (keys tc)))))))))
