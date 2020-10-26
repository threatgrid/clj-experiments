(ns formatting-stack.are-linter.api
  (:require
   [formatting-stack.are-linter.impl :refer [lint!]]
   [formatting-stack.protocols.linter :as linter]
   [nedap.utils.modular.api :refer [implement]]))

(defn new []
  (implement {:id ::id}
    linter/--lint! lint!))
