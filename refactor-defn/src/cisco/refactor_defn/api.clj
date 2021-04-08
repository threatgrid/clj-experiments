(ns cisco.refactor-defn.api
  (:require
   [cisco.refactor-defn.kws :as kws]
   [nedap.speced.def :as speced]
   [nedap.utils.spec.predicates :refer [present-string?]]
   [rewrite-clj.zip :as zip]))

(speced/defn ^present-string? refactor-defns
  "`filename` will not be written to."
  [^::kws/existing-filename filename]
  (let [formatter (fn [node]
                    node)]
    (loop [current-node (zip/of-file filename)]
      (let [formatted-node (zip/postwalk current-node formatter)]
        (if-let [next-node (zip/right formatted-node)]
          (recur next-node)
          (zip/root-string formatted-node))))))
