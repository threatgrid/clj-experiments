(ns unit.formatting-stack.are-linter.impl
  (:require
   [clojure.test :refer [are deftest is testing]]
   [formatting-stack.are-linter.impl :as sut]))

(deftest is-or-contains-list?
  (are [input expected] (testing input
                          (is (= expected
                                 (sut/is-or-contains-list? input)))
                          true)
    nil                                false
    2                                  false
    '2                                 false
    []                                 false
    [1]                                false
    ['()]                              true
    ['(1)]                             true
    '()                                true
    '(1)                               true
    '[(1)]                             true
    ''(1)                              false
    '(some-call ''(1))                 true
    [[[['(some-call)]]]]               true
    [[[['(some-call ''(some-call))]]]] true
    [[[[''(some-call)]]]]              false
    [[[[''(some-call '(some-call))]]]] false))

(deftest lint
  (binding [*ns* (-> ::_ namespace symbol find-ns)]
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
                   :column   17,
                   :line     38,
                   :msg
                   "The following row invokes a function/macro more than once within the test body: (a) 1\nThe following row invokes a function/macro more than once within the test body: (b) 2"}))))
