(ns cisco.refactor-defn.kws
  (:require
   [clojure.spec.alpha :as spec]
   [nedap.speced.def :as speced]
   [nedap.utils.spec.predicates :refer [present-string?]])
  (:import
   (java.io File)))

(spec/def ::existing-filename (spec/and present-string?
                                        (speced/fn [^String s]
                                          (-> s File. .exists))))
