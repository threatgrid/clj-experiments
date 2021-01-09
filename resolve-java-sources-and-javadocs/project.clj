(defproject threatgrid/resolve-java-sources-and-javadocs "1.2.0"
  :description "Makes available .jars with Java sources and javadocs for a given project."

  :url "https://github.com/threatgrid/clj-experiments"

  :license {:name "EPL-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}

  ;; Temporary fork addressing https://github.com/brandonbloom/fipp/issues/72
  :dependencies [[threatgrid/fipp "0.6.24" :exclusions [org.clojure/clojure]]]

  :eval-in-leiningen ~(nil? (System/getenv "no_eval_in_leiningen"))

  :profiles {;; These developing the plugin when (false? eval-in-leiningen):
             :dev                 {:dependencies [[clj-commons/pomegranate "1.2.0"]
                                                  [org.clojure/clojure "1.10.1"]]}

             :integration-testing {:source-paths ["integration-testing"]}

             :self-test           {:middleware   [leiningen.resolve-java-sources-and-javadocs/add]
                                   ;; ensure that at least one dependency will fetch sources:
                                   :dependencies [[puppetlabs/trapperkeeper-webserver-jetty9 "4.1.0"]]}}

  :aliases {"integration-test" ["with-profile" "+integration-testing" "run" "-m" "integration-test"]})
