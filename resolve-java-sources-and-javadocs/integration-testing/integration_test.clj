(ns integration-test
  (:refer-clojure :exclude [time])
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as string]
   [leiningen.resolve-java-sources-and-javadocs :as sut]
   [leiningen.resolve-java-sources-and-javadocs.collections :refer [divide-by]]
   [leiningen.resolve-java-sources-and-javadocs.logging :refer [info]])
  (:import
   (java.io File)))

(def env (-> (into {} (System/getenv))
             (dissoc "CLASSPATH")
             ;; for Lein logging:
             (assoc "DEBUG" "true")))

(def lein (->> [(io/file "/usr" "local" "bin" "lein") ;; github actions
                (-> "user.home" ;; personal setup
                    (System/getProperty)
                    (io/file "bin" "lein-latest"))
                (-> "user.home" ;; standard
                    (System/getProperty)
                    (io/file "bin" "lein"))]
               (filter (memfn ^File exists))
               first
               str))

(assert (seq lein))

(def project-version (-> "project.clj"
                         slurp
                         read-string
                         (nth 2)))

(assert (string? project-version))

(defn prelude [x]
  (cond-> [x
           "with-profile" "-user"

           "update-in"
           ":plugins" "conj" (str "[threatgrid/resolve-java-sources-and-javadocs \""
                                  project-version
                                  "\"]")
           "--"

           "update-in"
           ":" "assoc" ":resolve-java-sources-and-javadocs" "{:classifiers #{\"sources\"}}"
           "--"

           "update-in"
           ":middleware" "conj" "leiningen.resolve-java-sources-and-javadocs/add"
           "--"]))

(def vanilla-lein-deps
  (conj (prelude lein) "deps"))

(def commands
  (sort ;; ensure stable tests
   {"aleph"         vanilla-lein-deps
    "amazonica"     vanilla-lein-deps
    "carmine"       vanilla-lein-deps
    "cassaforte"    vanilla-lein-deps
    "cider-nrepl"   vanilla-lein-deps
    "elastisch"     vanilla-lein-deps
    "http-kit"      vanilla-lein-deps
    "jackdaw"       vanilla-lein-deps
    "langohr"       vanilla-lein-deps
    "machine_head"  vanilla-lein-deps
    "metabase"      vanilla-lein-deps
    "monger"        vanilla-lein-deps
    "pallet"        vanilla-lein-deps
    "quartzite"     vanilla-lein-deps
    "riemann"       vanilla-lein-deps
    "welle"         vanilla-lein-deps
    ;; uses various plugins:
    "schema"        vanilla-lein-deps
    ;; uses lein-parent:
    "trapperkeeper" vanilla-lein-deps
    ;; uses lein-parent:
    "jepsen/jepsen" vanilla-lein-deps
    ;; uses lein-tools-deps:
    "overtone"      vanilla-lein-deps
    ;; uses lein-sub, lein-modules:
    "incanter"      (reduce into [(prelude lein)
                                  ["sub" "do"]
                                  (prelude "install,")
                                  ["deps"]])
    ;; uses lein-sub:
    "icepick"       (reduce into [(prelude lein)
                                  (prelude "sub")
                                  ["deps"]])
    ;; uses lein-sub:
    "crux"          (reduce into [(prelude lein)
                                  (prelude "sub")
                                  ["deps"]])
    ;; uses lein-sub:
    "pedestal"      (reduce into [(prelude lein)
                                  ["sub" "do"]
                                  (prelude "install,")
                                  ["deps"]])
    ;; uses lein-monolith:
    "sparkplug"     (reduce into [(prelude lein)
                                  ["monolith"]
                                  (prelude "each")
                                  ["do"
                                   "clean,"
                                   "install,"
                                   "deps"]])}))

(defmacro time
  {:style/indent 1}
  [id expr]
  `(let [start# (. System (nanoTime))
         ret# ~expr]
     (println (format "Ran %s in %.2f minutes." ~id (-> System
                                                        (. (nanoTime))
                                                        (- start#)
                                                        double
                                                        (/ 1000000.0)
                                                        (/ 60000.0))))
     ret#))

(def parallelism-factor
  (-> "leiningen.resolve-java-sources-and-javadocs.test.parallelism"
      (System/getProperty "1")
      read-string))

(defn run-repos! [f]
  (assert (pos? parallelism-factor))
  (assert (seq commands))
  (->> commands

       (divide-by parallelism-factor)

       (pmap (fn [chunks]
               (->> chunks
                    (mapv (fn [[id command]]
                            (let [_ (info (str "Exercising " id))
                                  _ (info (pr-str command))
                                  {:keys [out exit err]} (time id
                                                               (apply sh (into command
                                                                               [:dir (io/file "integration-testing" id)
                                                                                :env env])))]
                              (assert (zero? exit) [id (pr-str (-> err (doto println)))])
                              (let [lines (->> out
                                               string/split-lines
                                               (filter (fn [s]
                                                         (string/includes? s "leiningen.resolve-java-sources-and-javadocs"))))
                                    good (->> lines (filter (fn [s]
                                                              (string/includes? s "/found"))))
                                    bad (->> lines (filter (fn [s]
                                                             (string/includes? s "/could-not")
                                                             (string/includes? s "/timed-out")
                                                             ;; #{"sources"} is specified in `#'prelude`
                                                             (string/includes? s ":classifier \"javadoc\""))))]
                                (assert (empty? bad)
                                        (pr-str [id bad]))
                                (f id good)
                                (info "")
                                id)))))))

       (apply concat)

       doall))

(defn suite []

  (when-not *assert*
    (throw (ex-info "." {})))

  (sh "lein" "install" :dir (System/getProperty "user.dir") :env env)

  (-> sut/cache-filename File. .delete)

  (run-repos! (fn [id good-lines]
                (when (#{1} parallelism-factor)
                  ;; This assertion cannot be guaranteed in parallel runs, since different orderings mean work can get "stolen"
                  ;; from one project to another (which is perfectly normal and desirable)
                  (assert (seq good-lines)
                          (format "Finds sources in %s" id)))
                (info (format "Found %s sources in %s."
                              (count good-lines)
                              id))))

  (run-repos! (fn [id good-lines]
                (assert (empty? good-lines)
                        [id
                         "Caches the findings"
                         (count good-lines)])))

  ;; Run one last time, proving that a given project's cache building is accretive:
  (run-repos! (fn [id good-lines]
                (run! info good-lines)
                (assert (empty? good-lines)
                        [id
                         "The cache only accretes - other projects' cache building doesn't undo prior work"
                         (count good-lines)])))

  (let [v (-> sut/cache-filename sut/read-file! sut/safe-read-string)]
    (assert (= v
               (-> v sut/deserialize sut/serialize))
            "Roundtrip")
    (assert (= v
               (-> v sut/deserialize sut/serialize sut/deserialize sut/serialize))
            "Longer roundtrip")))

(defn -main [& _]

  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread ex]
       (-> ex .printStackTrace)
       (System/exit 1))))

  (suite)

  (shutdown-agents)

  (System/exit 0))
