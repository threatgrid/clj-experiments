(defproject threatgrid/resolve-java-sources-and-javadocs "0.1.13"
  :description "Makes available .jars with Java sources and javadocs for a given project."

  :url "https://github.com/threatgrid/clj-experiments"

  :license {:name "EPL-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}

  :dependencies [[fipp "0.6.23"]]

  :eval-in-leiningen ~(nil? (System/getenv "no_eval_in_leiningen"))

  ;; Eases developing the plugin when (false? eval-in-leiningen):
  :profiles {:dev        {:dependencies [[clj-commons/pomegranate "1.2.0"]
                                         [org.clojure/clojure "1.10.1"]]}
             :middleware [leiningen.resolve-java-sources-and-javadocs/add]})
