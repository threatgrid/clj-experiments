(ns leiningen.resolve-java-sources-and-javadocs
  (:require
   [cemerick.pomegranate.aether]
   [clojure.string :as string]
   [fipp.clojure]
   [leiningen.resolve-java-sources-and-javadocs.collections :refer [add-exclusions-if-classified divide-by ensure-no-lists flatten-deps maybe-normalize safe-sort]]
   [leiningen.resolve-java-sources-and-javadocs.logging :refer [debug info]])
  (:import
   (java.io File)
   (java.net InetAddress UnknownHostException URI)
   (java.nio ByteBuffer)
   (java.nio.channels FileChannel FileLock)
   (java.nio.file Paths StandardOpenOption)
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

(defn read-file!
  "Reads a file, while holding a file-level read-lock.

  Waits when a writer is holding the lock.

  These file locks guard against concurrent Lein executions, which could otherwise corrupt a given file."
  ^String
  [filename]
  (locking in-process-lock
    (let [^FileChannel c (FileChannel/open (Paths/get filename (into-array String []))
                                           (into-array StandardOpenOption [StandardOpenOption/READ]))]
      (if-let [^FileLock read-lock (-> c (.tryLock 0 Long/MAX_VALUE true))]
        (let [size (-> c .size)
              buffer (ByteBuffer/allocate size)]
          (try
            (-> c (.read buffer))
            (-> buffer .array String.)
            (finally
              (-> read-lock .release)
              (-> c .close))))
        (do
          (Thread/sleep 50)
          (read-file! filename))))))

(defn write-file!
  "Writes to a file, while holding a file-level write-lock.
  `merge-fn` will be invoked for deriving a new string value out of `prev-content`
  (or new file contents, if lock acquisition failed).

  Refer to `java.nio.channels.FileLock` for details in shared vs. exclusive locks."
  ^String
  [^String filename, ^String prev-content, merge-fn]
  (locking in-process-lock
    (let [^FileChannel c (FileChannel/open (Paths/get filename (into-array String []))
                                           (into-array StandardOpenOption [StandardOpenOption/WRITE
                                                                           StandardOpenOption/CREATE
                                                                           StandardOpenOption/TRUNCATE_EXISTING]))]
      (if-let [^FileLock write-lock (-> c (.tryLock 0 Long/MAX_VALUE false))]
        (let [^bytes content (-> prev-content ^String (merge-fn) .getBytes)
              size (-> content alength)
              newval (ByteBuffer/wrap content 0 size)]
          (try
            (-> c (.write newval))
            (String. content)
            (finally
              (-> write-lock .release)
              (-> c .close))))
        (do
          (Thread/sleep 50)
          (write-file! filename (read-file! filename) merge-fn))))))

(defn serialize
  "Turns any contained coll into a vector, sorting it.

  This ensures that stable values are peristed to the file caches."
  [x]
  {:pre  [(not (string? x))]
   :post [(not (string? %))]}
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
  {:pre  [(not (string? x))]
   :post [(not (string? %))]}
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
  (if (string/blank? x)
    {}
    (read-string x)))

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

(defn resolve! [cache-atom repositories classifiers x]
  (let [v (or (get @cache-atom x)
              (get @cache-atom (maybe-normalize x))
              (try
                (debug (str ::resolving " " (pr-str x)))
                (let [x (mapv add-exclusions-if-classified x)
                      v (resolve-with-timeout! x repositories)
                      [x] x]
                  (if (= v ::timed-out)
                    []
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

  (-> cache-filename java.io.File. .createNewFile)

  (let [classifiers (set classifiers)
        repositories  (into {}
                            (remove private-repository?)
                            repositories)
        initial-cache-value (-> (read-file! cache-filename) safe-read-string deserialize)
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
                  ;; NOTE: there's a negligible race condition here
                  ;; (as there's a window between read time and write time in which no lock is held).
                  ;; the likelihood for it is very low (`lein deps` takes seconds if not minutes,
                  ;; while the window for a race is of about 1ms).
                  ;; At most one risks dropping some additions (which can always be re-cached later),
                  ;; but no actual issues/errors can arise.

                  ;; A possible fix would be to make `write-file!` also use StandardOpenOption/READ
                  ;; (which was problematic on its own, as it resulted in files being appended to, instead of overwritten)
                  (write-file! cache-filename
                               (read-file! cache-filename)
                               (make-merge-fn cache-atom)))
                ;; order can be sensitive
                (into additions deps))))))

(defn resolve-java-sources-and-javadocs
  [project & args]
  (add project))
