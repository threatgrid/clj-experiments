(ns integration-test
  (:require
   [clojure.java.io :as io]
   [clojure.java.shell :refer [sh]]
   [clojure.string :as string]
   [leiningen.resolve-java-sources-and-javadocs])
  (:import
   (java.io File)))

(def env (dissoc (into {} (System/getenv)) "CLASSPATH"))

(def lein (->> [(-> "user.home"
                    (System/getProperty)
                    (io/file "bin" "lein-latest"))
                (-> "user.home"
                    (System/getProperty)
                    (io/file "bin" "lein"))]
               (filter (memfn ^File exists))
               first
               str))

(def project-version (-> "project.clj"
                         slurp
                         read-string
                         (nth 2)))

(assert (string? project-version))

(defn prelude [x & [skip-pedantic?]]
  (cond-> [x
           "with-profile" "-user"

           "update-in"
           ":plugins" "conj" (str "[threatgrid/resolve-java-sources-and-javadocs \""
                                  project-version
                                  "\"]")
           "--"

           "update-in"
           ":middleware" "conj" "leiningen.resolve-java-sources-and-javadocs/add"
           "--"

           "update-in"
           ":" "assoc" ":resolve-java-sources-and-javadocs" "{:classifiers #{\"sources\"}}"
           "--"]
    ;; There is only one instance where we skip :pedantic? (namely: Pedestal), because it's stricly a false alarm:
    ;; it complains about an x being ambiguous vs. x
    ;; (i.e. same coordinates, classifiers, everything) - must be a minor Lein bug.

    ;; ...This issue can only be reproduced with `lein update-in`, not with `lein with-profile +repl`,
    ;; which is how the plugin will be actually used anyway

    ;; (this test ns uses `lein update-in` because it allows to skip ~/.lein, and also <project>/profiles.clj,
    ;; which can interfere)
    skip-pedantic? (conj "update-in"
                         ":" "assoc" ":pedantic?" "false"
                         "--")))

(def commands
  {"metabase"      (conj (prelude lein) "deps")
   "riemann"       (conj (prelude lein) "deps")
   "pallet"        (conj (prelude lein) "deps")
   "aleph"         (conj (prelude lein) "deps")
   ;; uses various plugins:
   "schema"        (conj (prelude lein) "deps")
   ;; uses lein-localrepo:
   "jepsen/jepsen" (conj (prelude lein) "deps")
   ;; uses lein-tools-deps:
   "overtone"      (conj (prelude lein) "deps")
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
                                 (prelude "install," true)
                                 ["deps"]])
   ;; uses lein-monolith:
   "sparkplug"     (reduce into [(prelude lein)
                                 ["monolith"]
                                 (prelude "each")
                                 ["do"
                                  "clean,"
                                  "install,"
                                  "deps"]])})

(defn run-repos! [f]
  (assert (seq commands))
  (doseq [[id command] commands
          :let [_ (println "Exercising" id)
                _ (pr command)
                _ (println)
                {:keys [out exit err]} (apply sh (into command
                                                       [:dir (io/file "integration-testing" id)
                                                        :env env]))]]
    (assert (zero? exit) err)
    (let [lines (->> out string/split-lines (filter (fn [s]
                                                      (string/includes? s "leiningen.resolve-java-sources-and-javadocs"))))
          good (->> lines (filter (fn [s]
                                    (string/includes? s "found"))))
          bad (->> lines (filter (fn [s]
                                   (string/includes? s "could"))))]
      (assert (empty? bad))
      (f id good)
      (println))))

(defn delete-file! []
  (-> leiningen.resolve-java-sources-and-javadocs/cache-filename File. .delete))

(defn suite []

  (when-not *assert*
    (throw (ex-info "." {})))

  (sh "lein" "install" :dir (System/getProperty "user.dir") :env env)

  (delete-file!)

  (run-repos! (fn [id good-lines]
                (assert (seq good-lines)
                        (format "Finds sources in %s" id))
                (println (format "Found %s sources in %s"
                                 (count good-lines)
                                 id))))
  (run-repos! (fn [_ good-lines]
                (assert (empty? good-lines)
                        "Caches the findings")))

  ;; Run one last time, proving that a given project's cache building is accretive:
  (run-repos! (fn [_ good-lines]
                (assert (empty? good-lines)
                        "The cache only accretes - other projects' cache building doesn't undo prior work"))))

(defn -main [& _]
  (suite)
  (System/exit 0))
