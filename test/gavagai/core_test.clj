(ns gavagai.core-test
  (:use [clojure.test])
  (:require [gavagai.core :as g]))

(g/register-converters
 ["java.util.Date" :exclude [:class] :add {:string str}])

(deftest basic-tests
  (testing "basic translations tests"
    (let [dt (java.util.Date.)]
      (is (= 1 (g/register-converters
                ["java.util.Date" :exclude [:class] :add {:string str}])))
      (g/with-translator-ns gavagai.core-test
        (let [t (g/translate dt)]
          (is (= (.getMinutes dt) (:minutes t)))
          (is (nil? (:class t)) )
          (is (= (.toString dt) (:string t))))))))