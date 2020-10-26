(ns integration.formatting-stack.are-linter.sample-2)

(comment
  (deftest good-are
    (are [x y] (= x y)
      1 1
      2 2)))

(comment
  (deftest bad-are
    (are [x y] (do
                 x
                 (is (= x y))
                 false)

      1   1
      (a) 1
      1   (a) ;; this part is ok
      (b) 2)))
