(ns org.corfield.shadow-clj-test-runner.interface-test
  (:require [clojure.test :as test :refer :all]
            [org.corfield.shadow-clj-test-runner.interface :as shadow-clj-test-runner]))

(deftest dummy-test
  (is (= 1 1)))
