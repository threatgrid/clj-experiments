(ns unit.formatting-stack.are-linter.impl
  (:require
   [clojure.test :refer [are deftest is]]
   [formatting-stack.are-linter.impl :as sut]))

(deftest lint
  (let [good '(are [x y] (do
                           (is (= x y))
                           true)
                1   1
                (a) 1)
        ;; tricky: `(a)` is twice but not repeated after template expansion
        also-good '(are [x y] (= x y)
                     (a) (a))
        bad  '(are [x y] (do
                           x
                           (is (= x y))
                           false)

                1   1
                (a) 1
                1   (a) ;; this part is ok
                (b) 2)]
    (are [input expected] (= expected
                             (sut/lint "filename.clj" input))
      good      nil
      also-good nil
      bad       {:filename "filename.clj",
                 :source   :formatting-stack.are-linter.api/id,
                 :level    :warning,
                 :column   15,
                 :line     15,
                 :msg
                 "The following row invokes a function/macro more than once within the test body: (a) 1\nThe following row invokes a function/macro more than once within the test body: (b) 2"})))
