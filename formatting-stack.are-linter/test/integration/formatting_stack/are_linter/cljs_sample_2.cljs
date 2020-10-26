(ns integration.formatting-stack.are-linter.cljs-sample-2
  (:require
   [cljs.test :as foo]))

(comment
  (deftest good-are
    (are [x y] (= x y)
      1 1
      2 2)))

(comment
  (deftest bad-are-1
    (are [x y] (do
                 x
                 (is (= x y))
                 false)

      1   1
      (a) 1
      1   (a) ;; this part is ok
      (b) 2)))

(comment
  (deftest bad-are-2
    (cljs.test/are [x y] (do
                           x
                           (is (= x y))
                           false)

      1   1
      (a) 1
      1   (a) ;; this part is ok
      (b) 2)))

(comment
  (deftest bad-are-3
    (foo/are [x y] (do
                     x
                     (is (= x y))
                     false)

      1   1
      (a) 1
      1   (a) ;; this part is ok
      (b) 2)))
