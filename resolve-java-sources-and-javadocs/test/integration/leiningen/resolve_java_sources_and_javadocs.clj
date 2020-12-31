(ns integration.leiningen.resolve-java-sources-and-javadocs
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [are deftest is testing]]
   [leiningen.resolve-java-sources-and-javadocs :as sut])
  (:import
   (java.io File)))

(deftest read-file!
  (testing "Reads file contents"
    (are [input expected] (testing input
                            (is (= expected
                                   (-> input io/resource io/as-file str sut/read-file!)))
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
                                (let [v (sut/write-file! filename
                                                         (sut/read-file! filename)
                                                         (sut/make-merge-fn state))]

                                  (is (= expected v))
                                  (is (= expected (sut/read-file! filename))))
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
    (is (= (-> form sut/serialize)
           (-> form sut/serialize sut/serialize)))
    (is (= form
           (-> serialized sut/deserialize)))))
