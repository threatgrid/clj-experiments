(ns integration.formatting-stack.are-linter.api
  (:require
   [clojure.test :refer [deftest is testing]]
   [formatting-stack.are-linter.api :as sut]
   [formatting-stack.protocols.linter :as linter]))

(deftest lint!
  (let [filename "test/integration/formatting_stack/are_linter/sample_2.clj"
        result (-> (sut/new)
                   (linter/lint! [filename]))
        all-content (str "[" (-> filename slurp) "]")]
    (assert (->> all-content read-string flatten (filter #{'deftest}) (count) #{2})
            "There are two deftests, a good one and a bad one")
    (assert (= 1 (count result))
            "Only the bad one shows up in the results")
    (testing "Parsing of `are` forms works, including line/column metadata, resulting in a correct report."
      (is (= '({:filename
                "test/integration/formatting_stack/are_linter/sample_2.clj",
                :source :formatting-stack.are-linter.api/id,
                :level  :warning,
                :msg
                "The following row invokes a function/macro more than once within the test body: (a) 1\nThe following row invokes a function/macro more than once within the test body: (b) 2",
                :line   9,
                :column 1})
             result))))

  (testing "cljs ns form parsing"
    (let [filename "test/integration/formatting_stack/are_linter/cljs_sample_2.cljs"
          result (-> (sut/new)
                     (linter/lint! [filename]))
          all-content (str "[" (-> filename slurp) "]")]
      (assert (->> all-content read-string flatten (filter #{'deftest}) (count) #{4})
              "There are four deftests, a good one and three bad ones")
      (assert (= 3 (count result))
              "Only the bad ones show up in the results")
      (testing "Parsing of `are` forms works, including line/column metadata, resulting in a correct report."
        (is (= '({:filename
                  "test/integration/formatting_stack/are_linter/cljs_sample_2.cljs",
                  :source :formatting-stack.are-linter.api/id,
                  :level  :warning,
                  :msg    "The following row invokes a function/macro more than once within the test body: (a) 1\nThe following row invokes a function/macro more than once within the test body: (b) 2",
                  :line   11,
                  :column 1}
                 {:filename
                  "test/integration/formatting_stack/are_linter/cljs_sample_2.cljs",
                  :source :formatting-stack.are-linter.api/id,
                  :level  :warning,
                  :msg
                  "The following row invokes a function/macro more than once within the test body: (a) 1\nThe following row invokes a function/macro more than once within the test body: (b) 2",
                  :line   23,
                  :column 1}
                 {:filename
                  "test/integration/formatting_stack/are_linter/cljs_sample_2.cljs",
                  :source :formatting-stack.are-linter.api/id,
                  :level  :warning,
                  :msg
                  "The following row invokes a function/macro more than once within the test body: (a) 1\nThe following row invokes a function/macro more than once within the test body: (b) 2",
                  :line   35,
                  :column 1})
               result))))))
