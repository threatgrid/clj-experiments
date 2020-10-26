(ns integration.formatting-stack.are-linter.impl
  (:require
   [clojure.string :as string]
   [clojure.test :refer [are deftest is testing]]
   [formatting-stack.are-linter.impl :as sut])
  (:import
   (java.io File)))

(deftest aliased-are-names-of
  (is (= '(foo/are)
         (sut/aliased-are-names-of "test/integration/formatting_stack/are_linter/cljs_sample.cljs"))
      "Returns `are` symbols qualified by aliases that refer to `clojure.test`/`cljs.test`"))

(deftest find-are-forms
  (testing "Finds `are` forms, honoring the `filename`'s `#'ns-aliases`, and including line/column metadata"
    (let [filename "test/integration/formatting_stack/are_linter/sample.clj"
          _        (assert (-> filename File. .exists))
          result   (sut/find-are-forms filename)]

      (assert (string/includes? (slurp filename) "(extraneous/are")
              "Makes the next test assertion logically valid")

      (is (= '[(are ...) (clojure.test/are ...) (test/are ...)]
             result)
          "Does not include the `extraneous/are` form, because it's not an existing alias")

      (are [form expected-metadata]
           (let [found (->> result (some (fn [x]
                                           (when (= x form)
                                            ;; return `x` (and not `form`) since that's the relevant metadata:
                                             x))))]
             (is found)
             (is (= expected-metadata
                    (meta found)))
             true)
        '(are ...)              {:line 5, :column 1}

        '(clojure.test/are ...) {:line 8, :column 1}

        '(test/are ...)         {:line 11, :column 1}))))
