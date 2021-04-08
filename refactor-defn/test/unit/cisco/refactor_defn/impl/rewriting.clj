(ns unit.cisco.refactor-defn.impl.rewriting
  (:require
   [cisco.refactor-defn.impl.rewriting :as sut]
   [clojure.test :refer [are deftest is testing]]
   [rewrite-clj.zip :as zip]))

(deftest process-defn
  (are [input expected] (testing input
                          (let [s (->> input
                                       read-string
                                       rest
                                       (apply list `sut/tails-schemas)
                                       eval)]
                            (is (= expected
                                   (zip/root-string (sut/process-defn (zip/of-string input)
                                                                      s)))))
                          true)
    "(defn foo [] 1 2)"                      "(defn ^:nedap.speced.def/schema foo [] 1 2)"
    "(defn foo ([] 1 2))"                    "(defn ^:nedap.speced.def/schema foo ([] 1 2))"

    "(defn foo :- s/Str [] 1 2)"             "(defn ^:nedap.speced.def/schema ^s/Str foo [] 1 2)"
    "(defn foo :- s/Str ([] 1 2))"           "(defn ^:nedap.speced.def/schema ^s/Str foo ([] 1 2))"

    "(defn foo [a :- s/Str] 1 2)"            "(defn ^:nedap.speced.def/schema foo [^s/Str a  ] 1 2)"
    "(defn foo ([a :- s/Str] 1 2))"          "(defn ^:nedap.speced.def/schema foo ([^s/Str a  ] 1 2))"

    "(defn foo :- s/Str [a :- s/Str] 1 2)"   "(defn ^:nedap.speced.def/schema ^s/Str foo [^s/Str a  ] 1 2)"
    "(defn foo :- s/Str ([a :- s/Str] 1 2))" "(defn ^:nedap.speced.def/schema ^s/Str foo ([^s/Str a  ] 1 2))"

    "(defn foo :- s/Str ([a :- s/Str] 1 2) ([a :- s/Str b :- s/Int] 3 4))"
    "(defn ^:nedap.speced.def/schema ^s/Str foo ([^s/Str a  ] 1 2) ([^s/Str a   ^s/Int b  ] 3 4))"

    "(defn foo :- s/Str ([a :- s/Str] 1 2) ([]) ([a :- s/Str b c :- s/Int] 3 4))"
    "(defn ^:nedap.speced.def/schema ^s/Str foo ([^s/Str a  ] 1 2) ([]) ([^s/Str a   b ^s/Int c  ] 3 4))"))

(deftest add-defn-meta
  (are [input schema expected] (testing input
                                 (is (= expected
                                        (-> input
                                            zip/of-string
                                            (sut/add-defn-meta schema)
                                            (zip/root-string))))
                                 true)
    "(defn foo [] 1 2)" nil    "(defn ^:nedap.speced.def/schema foo [] 1 2)"
    "(defn foo [] 1 2)" 's/Str "(defn ^:nedap.speced.def/schema ^s/Str foo [] 1 2)"))

(deftest add-input-annotations
  (are [input schema expected] (testing input
                                 (is (= expected
                                        (-> input
                                            zip/of-string
                                            (sut/add-input-annotations schema)
                                            (zip/root-string))))
                                 true)
    "[]"    []           "[]"
    "[a]"   [nil]        "[a]"
    "[a]"   ['s/Str]     "[^s/Str a]"
    "[a b]" ['s/Str nil] "[^s/Str a b]"
    "[a b]" [nil 's/Str] "[a ^s/Str b]"))

(deftest remove-schema-return-annotation
  (let [clean "(defn foo [] 1 2)"]
    (are [input expected] (testing input
                            (is (= expected
                                   (-> input
                                       zip/of-string
                                       sut/remove-schema-return-annotation
                                       zip/root-string)))
                            true)
      clean                        clean
      "(defn foo :- s/Str [] 1 2)" clean)))

(deftest remove-all-schema-annotations-in-vector
  (are [input expected] (testing input
                          (is (= expected
                                 (-> input
                                     zip/of-string
                                     sut/remove-all-schema-annotations-in-vector
                                     zip/root-string)))
                          true)
    "[]"                                          "[]"
    "[a]"                                         "[a]"
    "[a b c {:keys [x y z]} e]"                   "[a b c {:keys [x y z]} e]"
    "[a :- A]"                                    "[a  ]"
    "[a :- A b]"                                  "[a   b]"
    "[a :- A b :- B]"                             "[a   b  ]"
    "[A b :- B]"                                  "[A b  ]"
    "[A b :- B c :- C]"                           "[A b   c  ]"
    "[a :- A b {:keys [c d]} :- C e f :- F g]"    "[a   b {:keys [c d]}   e f   g]"
    "[a :- A b {:keys [c d]} :- C e ee f :- F g]" "[a   b {:keys [c d]}   e ee f   g]"))
