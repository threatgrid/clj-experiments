(ns integration.leiningen.resolve-java-sources-and-javadocs
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [are deftest is testing]]
   [leiningen.resolve-java-sources-and-javadocs.locks :as locks]
   [leiningen.resolve-java-sources-and-javadocs :as sut])
  (:import
   (java.io File)))

(deftest read-file!
  (testing "Reads file contents"
    (are [input expected] (testing input
                            (is (= expected
                                   (-> input io/resource io/as-file str locks/read-file!)))
                            true)
      "integration/leiningen/foo.txt" "42\n")))

(deftest write-file!
  (testing "Writes file contents, while also using a merge function. Sort order is stable"
    (let [filename "test/integration/leiningen/bar.txt"
          file (-> filename File.)
          state (atom {})]
      (-> file .createNewFile)
      (try
        (are [input expected] (testing input
                                (let [v (locks/write-file! filename
                                                           (sut/make-merge-fn state))]

                                  (is (= expected v))
                                  (is (= expected (locks/read-file! filename))))
                                true)
          (swap! state assoc [[4]] {[5 6] nil}) "[[[[4]] [[[5 6] nil]]]]\n"
          (swap! state assoc [[1]] {[2 3] nil}) "[[[[1]] [[[2 3] nil]]] [[[4]] [[[5 6] nil]]]]\n")
        (finally
          (-> file .delete))))))

(deftest serialize-deserialize
  (let [filename "test/integration/leiningen/sample.edn"
        file (-> filename File. slurp)
        form (-> file read-string)
        serialized (-> form sut/serialize)]
    (is (< 100
           (count form))
        "Sanity check")
    (is (not= form serialized))
    (is (= form
           (-> serialized sut/deserialize)))))

(deftest acceptable-repository?
  (are [desc input expected] (testing [desc input]
                               (is (= expected
                                      (sut/acceptable-repository? [:_ input])))
                               true)
    "Basic case"
    {:url "https://example.com"}
    true

    "Rejects entries having passwords, as they generally won't have source .jar (affecting performance)"
    {:url      "https://example.com"
     :password "foo"}
    false)

  (are [desc input expected] (testing [desc input]
                               (is (= expected
                                      (sut/acceptable-repository? [:_ {:url input}])))
                               true)
    "Basic case"
    "https://example.com"         true

    "Rejects non-existing domains, as they cause timeouts"
    "https://example.foooooooooo" false

    "Rejects unencrypted HTTP, as Lein would reject it"
    "http://example.com"          false

    "Rejects git repositories (as used by some plugins),
since a git repo inherently cannot resolve to a .jar artifact"
    "git://github.com"            false))
