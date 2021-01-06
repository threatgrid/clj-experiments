(defproject threatgrid/resolve-java-sources-and-javadocs "1.0.1"
  :description "Makes available .jars with Java sources and javadocs for a given project."

  :url "https://github.com/threatgrid/clj-experiments"

  :license {:name "EPL-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}

  ;; Temporary fork addressing https://github.com/brandonbloom/fipp/issues/72
  :dependencies [[threatgrid/fipp "0.6.24" :exclusions [org.clojure/clojure]]]

  :eval-in-leiningen ~(nil? (System/getenv "no_eval_in_leiningen"))

  ;; Eases developing the plugin when (false? eval-in-leiningen):
  :profiles {:dev                 {:dependencies [[clj-commons/pomegranate "1.2.0"]
                                                  [org.clojure/clojure "1.10.1"]]}

             :integration-testing {:source-paths ["integration-testing"]}}

  :aliases {"integration-test" ["with-profile" "+integration-testing" "run" "-m" "integration-test"]})
