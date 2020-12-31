(ns unit.leiningen.resolve-java-sources-and-javadocs
  (:require
   [clojure.test :refer [are deftest is testing]]
   [leiningen.resolve-java-sources-and-javadocs :as sut]))

(deftest index
  (are [coll item expected] (testing coll item
                                     (is (= expected
                                            (sut/index coll item)))
                                     true)
    [:a]    :a 0
    [:_ :a] :a 1))

(deftest normalize-exclusions
  (are [input expected] (testing input
                          (is (= expected
                                 (sut/normalize-exclusions input)))
                          true)
    []                                 []
    '[org.clojure/clojure]             '[[org.clojure/clojure]]
    '[[org.clojure/clojure]]           '[[org.clojure/clojure]]
    '[[foo] org.clojure/clojure [bar]] '[[foo] [org.clojure/clojure] [bar]]))

(deftest safe-sort
  (testing "Normalizes :exclusions"
    (are [input expected] (testing input
                            (is (= expected
                                   (sut/safe-sort input)))
                            true)
      '[[[[org.clojure/tools.nrepl "0.2.12" :exclusions [org.clojure/clojure]]] [[[org.clojure/tools.nrepl "0.2.12" :exclusions [[org.clojure/clojure]]] nil]]]
        [[[org.clojure/tools.nrepl "0.2.12" :exclusions [[org.clojure/clojure]]]] [[[org.clojure/tools.nrepl "0.2.12" :exclusions [[org.clojure/clojure]]] nil]]]]
      '([[[org.clojure/tools.nrepl "0.2.12" :exclusions [org.clojure/clojure]]] [[[org.clojure/tools.nrepl "0.2.12" :exclusions [[org.clojure/clojure]]] nil]]]
        [[[org.clojure/tools.nrepl "0.2.12" :exclusions [[org.clojure/clojure]]]] [[[org.clojure/tools.nrepl "0.2.12" :exclusions [[org.clojure/clojure]]] nil]]])

      '[[[[clojure-complete "0.2.4" :exclusions [[org.clojure/clojure]]]] [[[clojure-complete "0.2.4" :exclusions [[org.clojure/clojure]]] nil]]]
        [[[clojure-complete "0.2.4" :exclusions [org.clojure/clojure]]] [[[clojure-complete "0.2.4" :exclusions [[org.clojure/clojure]]] nil]]]]
      '([[[clojure-complete "0.2.4" :exclusions [[org.clojure/clojure]]]] [[[clojure-complete "0.2.4" :exclusions [[org.clojure/clojure]]] nil]]]
        [[[clojure-complete "0.2.4" :exclusions [org.clojure/clojure]]] [[[clojure-complete "0.2.4" :exclusions [[org.clojure/clojure]]] nil]]]))))

(deftest matches-version?
  (let [a '[foo "2.1.2"]
        b '[foo "2.1.3"]]
    (are [deps artifact expected] (testing [deps artifact]
                                    (is (= expected
                                           (sut/matches-version? deps artifact)))
                                    true)
      []    a true
      [a]   a true
      [b]   a false
      [a b] a true)))

(deftest choose-one-artifact
  (let [a-orig '[foo "2.1.2"]
        b-orig '[foo "2.1.3"]
        a-source  '[foo "2.1.2" :classifier "sources"]
        b-source  '[foo "2.1.3" :classifier "sources"]]
    (are [desc deps managed-dependencies equivalent-deps expected] (testing [deps managed-dependencies equivalent-deps]
                                                                     (is (= expected
                                                                            (sut/choose-one-artifact deps
                                                                                                     managed-dependencies
                                                                                                     equivalent-deps))
                                                                         desc)
                                                                     true)
      "Basic"
      []       []       [a-source]          a-source

      "Basic - chooses the most recent dep (1/2)"
      []       []       [a-source b-source] b-source

      "Basic - chooses the most recent dep (2/2)"
      []       []       [b-source a-source] b-source

      "Can choose from `deps`"
      [a-orig] []       [a-source]          a-source

      "Can choose from `deps`"
      [a-orig] []       [a-source b-source] a-source

      "Can choose from `deps`"
      [b-orig] []       [a-source b-source] b-source

      "Does not choose from `deps` a non-existing source"
      [a-orig] []       [b-source]          b-source

      "Can choose from `managed-deps`"
      []       [a-orig] [a-source]          a-source

      "Can choose from `managed-deps`"
      []       [a-orig] [a-source b-source] a-source

      "Can choose from `managed-deps`"
      []       [b-orig] [a-source b-source] b-source

      "Does not choose from `managed-deps` a non-existing source"
      []       [a-orig] [b-source]          b-source

      "Favors `deps` over `managed-dependencies` even if the latter indicates a more recent version"
      [a-orig] [b-orig] [a-source b-source] a-source)))
