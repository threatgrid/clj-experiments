(ns leiningen.resolve-java-sources-and-javadocs
  (:require
   [cemerick.pomegranate.aether]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.walk :as walk]
   [fipp.clojure]
   [leiningen.resolve-java-sources-and-javadocs.collections :refer [add-exclusions-if-classified divide-by ensure-no-lists flatten-deps maybe-normalize safe-sort]]
   [leiningen.resolve-java-sources-and-javadocs.logging :refer [debug info]])
  (:import
   (java.io File RandomAccessFile)
   (java.net InetAddress UnknownHostException URI)
   (java.nio.channels FileLock)
   (java.util.concurrent ExecutionException)))

(def ^String cache-filename
  (-> "user.home"
      System/getProperty
      (File. ".lein-source-and-javadocs-cache")
      (str)))

(def in-process-lock
  "Although Lein invocation concurrency is primarily inter-process, it can also be in-process:
  https://github.com/amperity/lein-monolith/blob/47e31f3081dafc517b533ff927be3c7fed0e12dd/src/lein_monolith/task/each.clj#L345-L347

  This lock guards against in-process concurrent acquisition of a FileLock, which would otherwise throw a `java.nio.channels.OverlappingFileLockException`."
  (Object.))

(defn locking-file [^String filename f]
  (locking in-process-lock
    (with-open [raf (-> filename io/file (RandomAccessFile. "rw"))
                channel (-> raf .getChannel)]
      (loop [retry 0]
        (if-let [^FileLock lock (-> channel .tryLock)]
          (try
            (f (slurp filename))
            (finally
              (-> lock .release)))
          (if (= retry 1000)
            (throw (Exception. "Locked by other thread or process."))
            (do
              (Thread/sleep 5)
              (recur (inc retry)))))))))

(defn read-file! [filename]
  (locking-file filename identity))

(defn write-file! [filename merge-fn]
  (locking-file filename (fn [s]
                           (let [v (merge-fn s)]
                             (spit filename v)
                             v))))
(defn serialize
  "Turns any contained coll into a vector, sorting it.

  This ensures that stable values are peristed to the file caches."
  [x]
  {:pre  [(map? x)]
   :post [(vector? %)]}
  (->> x
       (mapv (fn outer [[k v]]
               [(ensure-no-lists k)
                (some->> v
                         (mapv (fn inner [[kk vv]]
                                 [(ensure-no-lists kk) (some->> vv
                                                                (map ensure-no-lists)
                                                                safe-sort
                                                                vec)]))
                         (safe-sort)
                         vec)]))
       safe-sort
       vec))

(defn deserialize
  "Undoes the work of `#'serialize`.

  Note that only certain vectors must be turned back into hashmaps - others must remain as-is."
  [x]
  {:post [(map? %)]}
  (assert (vector? x) (class x))
  (->> x
       (map (fn [[k v]]
              [k (if (and v (empty? v))
                   []
                   (some->> v
                            (map (fn [[kk vv]]
                                   [kk (some->> vv
                                                set)]))
                            (into {})))]))
       (into {})))

(defn safe-read-string [x]
  {:pre [(string? x)]}
  (if (string/blank? x)
    []
    (let [v (read-string x)]
      (assert (vector? v))
      v)))

(defn ppr-str [x]
  (with-out-str
    (fipp.clojure/pprint x)))

(defn make-merge-fn [cache-atom]
  {:pre [@cache-atom]}
  (fn [^String prev-val]
    {:pre  [(string? prev-val)]
     :post [(string? %)]}
    (-> prev-val
        safe-read-string
        deserialize
        (merge @cache-atom)
        serialize
        ppr-str)))

(defn resolve-with-timeout! [coordinates repositories]
  {:pre [(vector? coordinates)
         (-> coordinates count #{1})]}
  (try
    (deref (future
             (cemerick.pomegranate.aether/resolve-dependencies :coordinates coordinates
                                                               :repositories repositories))
           ;; timing out should be very rare, it's not here for a strong reason
           27500
           ::timed-out)
    (catch ExecutionException e
      (-> e .getCause throw))))

(defn maybe-add-exclusions* [x]
  (->> x
       (walk/postwalk (fn [item]
                        (cond-> item
                          (and (vector? item)
                               (some #{:classifier} item))

                          add-exclusions-if-classified)))))

(def maybe-add-exclusions (memoize maybe-add-exclusions*))

(defn resolve! [cache-atom repositories classifiers x]
  (let [v (or (get @cache-atom x)
              (get @cache-atom (maybe-normalize x))
              (try
                (let [x (maybe-add-exclusions x)
                      _ (debug (str ::resolving " " (pr-str x)))
                      v (resolve-with-timeout! x repositories)
                      [x] x]
                  (if (= v ::timed-out)
                    (do
                      (info (str ::timed-out " " x))
                      [])
                    (do
                      (when (and (find v x)
                                 (-> x (get 3) classifiers))
                        (info (str ::found " " (pr-str x))))
                      ;; ensure the cache gets set to something:
                      (doto v assert))))
                (catch AbstractMethodError e
                  ;; Catches:

                  ;; "Tried to use insecure HTTP repository without TLS:
                  ;; This is almost certainly a mistake; for details see
                  ;; https://github.com/technomancy/leiningen/blob/master/doc/FAQ.md"

                  ;; (apparently it's a bit hard to add some kind of conditional for only catching *that* AbstractMethodError,
                  ;; but AbstractMethodErrors are rare enough that we can simply assume they have a single possible cause)
                  [])
                (catch Exception e
                  (if (#{(Class/forName "org.eclipse.aether.resolution.DependencyResolutionException")
                         (Class/forName "org.eclipse.aether.transfer.ArtifactNotFoundException")
                         (Class/forName "org.eclipse.aether.resolution.ArtifactResolutionException")}
                       (class e))
                    []
                    (do
                      (-> e .printStackTrace)
                      nil)))
                (finally
                  (debug (str ::resolved "  " (pr-str x))))))]
    (when v
      (swap! cache-atom assoc x v))
    v))

(defn derivatives [classifiers managed-dependencies memoized-resolve! [dep version & args :as original]]
  (let [version (or version
                    (->> managed-dependencies
                         (filter (fn [[a]]
                                   (= dep a)))
                         first
                         second))]
    (if-not version ;; handles managed-dependencies
      [original]
      (let [transitive (->> (memoized-resolve! [(assoc-in original [1] version)])
                            flatten-deps)]
        (->> transitive
             (mapcat (fn [[dep version :as original]]
                       (assert version (pr-str original))
                       (->> classifiers
                            (mapv (partial vector dep version :classifier))
                            (into [original])
                            (distinct)
                            (remove (comp nil? second))
                            (filter (fn [x]
                                      (->> (memoized-resolve! [x])
                                           flatten-deps
                                           (filter #{x})
                                           first
                                           some?))))))
             (distinct)
             (vec))))))

(defn matches-version? [deps [s-or-j-name s-or-j-version :as s-or-j]]
  (let [[_ matching-version :as matching] (->> deps
                                               (filter (fn [[matching-name]]
                                                         (= matching-name
                                                            s-or-j-name)))
                                               first)]
    (if matching
      (= s-or-j-version matching-version)
      true)))

(defn choose-one-artifact
  "Prevents Lein `:pedantic` faults by picking one source."
  [deps managed-dependencies equivalent-deps]
  {:post [(if %
            (contains? (set equivalent-deps) %)
            true)]}
  (let [pred (fn [xs [inner-dep inner-version classifier-keyword classifier]]
               {:pre [inner-dep
                      inner-version
                      (#{:classifier} classifier-keyword)
                      (string? classifier)]}
               (->> xs
                    (some (fn [[dep version]]
                            {:pre [dep]}
                            (and (= dep inner-dep)
                                 (= version inner-version))))))]
    (or (->> equivalent-deps
             (filter (partial pred deps))
             (first))
        (->> equivalent-deps
             (filter (partial pred managed-dependencies))
             (first))
        (->> equivalent-deps
             (sort-by second)
             (last)))))

(def parallelism-factor
  "A reasonable factor for parallel Maven resolution, which tries to maximise efficiency
  while keeping thread count low which seems detrimental for both us and the Maven servers."
  (if (find-ns 'lein-monolith.task.each)
    1
    4))

(defn private-repository? [[_ {:keys [url] :as x}]]
  (or (->> x keys (some #{:password}))
      ;; Some domains may be behind under a VPN we are disconnected from:
      (try
        (when-let [{:keys [host]} (some-> url URI. bean)]
          (InetAddress/getByName host)
          false)
        (catch UnknownHostException _
          true))))

(defn add [{:keys                                        [repositories managed-dependencies]
            {:keys [classifiers]
             :or   {classifiers #{"javadoc" "sources"}}} :resolve-java-sources-and-javadocs
            :as                                          project}]

  (debug (str [::classifiers classifiers]))

  (-> cache-filename java.io.File. .createNewFile)

  (let [classifiers (set classifiers)
        repositories  (into {}
                            (remove private-repository?)
                            repositories)
        initial-cache-value (-> cache-filename read-file! safe-read-string deserialize)
        cache-atom (atom initial-cache-value)]
    (update project
            :dependencies
            (fn [deps]
              (let [memoized-resolve! (memoize (partial resolve! cache-atom repositories classifiers))
                    additions (->> deps
                                   (divide-by parallelism-factor)
                                   (pmap (fn [work]
                                           (->> work
                                                (mapcat (partial derivatives
                                                                 classifiers
                                                                 managed-dependencies
                                                                 memoized-resolve!)))))
                                   (apply concat)
                                   (distinct)
                                   (filter (fn [[_ _ _ x]]
                                             (classifiers x)))
                                   (filter (partial matches-version? deps))
                                   (group-by (fn [[dep version _ classifier]]
                                               [dep classifier]))
                                   (vals)
                                   (map (partial choose-one-artifact deps managed-dependencies))
                                   (mapv (fn [x]
                                           (conj x :exclusions '[[*]]))))]
                (when-not (= initial-cache-value @cache-atom)
                  (write-file! cache-filename
                               (make-merge-fn cache-atom)))
                ;; order can be sensitive
                (into additions deps))))))

(defn resolve-java-sources-and-javadocs
  [project & args]
  (add project))
