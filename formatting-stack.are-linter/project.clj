(defproject threatgrid/formatting-stack.are-linter "0.1.0-alpha1"
  ;; Please keep the dependencies sorted a-z.
  :dependencies [[com.nedap.staffing-solutions/speced.def "2.0.0"]
                 [com.nedap.staffing-solutions/utils.modular "2.1.1"]
                 [com.stuartsierra/component "1.0.0"]
                 [formatting-stack "4.2.1"]
                 [org.clojure/clojure "1.10.1"]
                 [org.clojure/tools.reader "1.3.3"]]

  :exclusions [org.clojure/clojurescript]

  :description "A formatting-stack linter for `clojure.test/are` forms."

  :url "https://github.com/threatgrid/clj-experiments"

  :min-lein-version "2.0.0"

  :license {:name "EPL-2.0"
            :url  "https://www.eclipse.org/legal/epl-2.0/"}

  :deploy-repositories {"clojars" {:url      "https://clojars.org/repo"
                                   :username :env/clojars_user
                                   :password :env/clojars_pass}}

  :target-path "target/%s"

  :test-paths ["src" "test"]

  :monkeypatch-clojure-test false

  :plugins [[lein-pprint "1.1.2"]]

  ;; A variety of common dependencies are bundled with `nedap/lein-template`.
  ;; They are divided into two categories:
  ;; * Dependencies that are possible or likely to be needed in all kind of production projects
  ;;   * The point is that when you realise you needed them, they are already in your classpath, avoiding interrupting your flow
  ;;   * After realising this, please move the dependency up to the top level.
  ;; * Genuinely dev-only dependencies allowing 'basic science'
  ;;   * e.g. criterium, deep-diff, clj-java-decompiler

  ;; NOTE: deps marked with #_"transitive" are there to satisfy the `:pedantic?` option.
  :profiles {:dev        {:dependencies [[cider/cider-nrepl "0.16.0" #_"formatting-stack needs it"]
                                         [cloverage "1.2.0" #_"same version as lein-cloverage"]
                                         [com.clojure-goes-fast/clj-java-decompiler "0.3.0"]
                                         [com.nedap.staffing-solutions/utils.spec.predicates "1.1.0"]
                                         [com.taoensso/timbre "4.10.0"]
                                         [criterium "0.4.5"]
                                         [lambdaisland/deep-diff "0.0-47"]
                                         [medley "1.3.0"]
                                         [org.clojure/core.async "1.0.567"]
                                         [org.clojure/math.combinatorics "0.1.6"]
                                         [org.clojure/test.check "1.0.0"]
                                         [org.clojure/tools.namespace "1.0.0"]
                                         [refactor-nrepl "2.4.0" #_"formatting-stack needs it"]]
                          :jvm-opts     ["-Dclojure.compiler.disable-locals-clearing=true"]
                          :plugins      [[lein-cloverage "1.2.0" #_"same version as cloverage"]]
                          :source-paths ["dev"]
                          :repl-options {:init-ns dev}}

             :check      {:global-vars {*unchecked-math* :warn-on-boxed
                                        ;; avoid warnings that cannot affect production:
                                        *assert*         false}}

             ;; some settings recommended for production applications.
             ;; You may also add :test, but beware of doing that if using this profile while running tests in CI.
             ;; (since that would disable tests altogether)
             :production {:jvm-opts    ["-Dclojure.compiler.elide-meta=[:doc :file :author :line :column :added :deprecated :nedap.speced.def/spec :nedap.speced.def/nilable]"
                                        "-Dclojure.compiler.direct-linking=true"]
                          :global-vars {*assert* false}}

             ;; this profile is necessary since JDK >= 11 removes XML Bind, used by Jackson, which is a very common dep.
             :jdk11      {:dependencies [[javax.xml.bind/jaxb-api "2.3.1"]
                                         [org.glassfish.jaxb/jaxb-runtime "2.3.1"]]}

             :test       {:dependencies [[com.nedap.staffing-solutions/utils.test "1.6.2"]]
                          :jvm-opts     ["-Dclojure.core.async.go-checking=true"
                                         "-Duser.language=en-US"]}

             :ci         {:pedantic? :abort
                          :jvm-opts  ["-Dclojure.main.report=stderr"]}})
