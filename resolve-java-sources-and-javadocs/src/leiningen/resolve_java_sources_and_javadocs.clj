(ns leiningen.resolve-java-sources-and-javadocs
  (:require
   [cemerick.pomegranate.aether]
   [clojure.string :as string]
   [clojure.walk :as walk])
  (:import
   (clojure.lang IFn)
   (java.io File)
   (java.net URI InetAddress UnknownHostException)
   (java.nio ByteBuffer)
   (java.nio.channels FileChannel FileLock)
   (java.nio.file Paths StandardOpenOption)))

(def info-lock (Object.))

;; These logging helpers ease developing the plugin itself (since leiningen.core cannot be required in rich repls)
(defn info [x]
  (locking info-lock
    (try
      (require 'leiningen.core.main)
      (-> 'leiningen.core.main/info ^IFn resolve (.invoke x))
      (catch Exception e
        (println x)))))

(def warn-lock (Object.))

(defn warn [x]
  (locking warn-lock
    (try
      (require 'leiningen.core.main)
      (-> 'leiningen.core.main/warn ^IFn resolve (.invoke x))
      (catch Exception e
        (println x)))))

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

(defn index [coll item]
  {:pre  [(vector? coll)]
   :post [(or (pos? %) ;; note: there's no nat-int? in old versions of Lein
              (zero? %))]}
  (->> coll
       (map-indexed (fn [i x]
                      (when (= x item)
                        i)))
       (filter some?)
       first))

(defn normalize-exclusions [exclusions]
  {:pre [(sequential? exclusions)]}
  (->> exclusions
       (mapv (fn [x]
               (cond-> x
                 (not (vector? x)) vector)))))

(defn maybe-normalize [x]
  (->> x
       (walk/postwalk (fn [item]
                        (cond-> item
                          (and (vector? item)
                               (some #{:exclusions} item))
                          (update (inc (index item :exclusions)) normalize-exclusions))))))

(defn safe-sort
  "Guards against errors when comparing objects of different classes."
  [coll]
  (try
    (->> coll
         (sort (fn inner-compare [x y]
                 (try
                   (cond
                     (and (vector? x) (not (coll? y)))
                     (inner-compare x [y])

                     (and (vector? y) (not (coll? x)))
                     (inner-compare [x] y)

                     true
                     (->> [x y]
                          (map maybe-normalize)
                          (apply compare)))
                   (catch Exception e
                     (warn (pr-str [::could-not-sort x y]))
                     (when (System/getProperty "leiningen.resolve-java-sources-and-javadocs.throw")
                       (throw e))
                     0)))))
    (catch Exception e
      (warn (pr-str [::could-not-sort coll]))
      (when (System/getProperty "leiningen.resolve-java-sources-and-javadocs.throw")
        (throw e))
      coll)))

(defn ensure-no-lists [x]
  {:pre [(vector? x)]}
  (->> x (mapv (fn [y]
                 (let [v (cond-> y
                           (sequential? y) vec)]
                   (cond-> v
                     (vector? v) ensure-no-lists))))))

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
        pr-str)))

(defn resolve! [cache-atom repositories classifiers x]
  (let [v (or (get @cache-atom x)
              (get @cache-atom (maybe-normalize x))
              (try
                (let [v (cemerick.pomegranate.aether/resolve-dependencies :coordinates x
                                                                          :repositories repositories)
                      [x] x]
                  (when (and (find v x)
                             (-> x (get 3) classifiers))
                    (info (str ::found " " (pr-str x))))
                  ;; ensure the cache gets set to something:
                  (doto v assert))
                (catch Exception e
                  (if (#{(Class/forName "org.eclipse.aether.resolution.DependencyResolutionException")
                         (Class/forName "org.eclipse.aether.transfer.ArtifactNotFoundException")
                         (Class/forName "org.eclipse.aether.resolution.ArtifactResolutionException")}
                       (class e))
                    []
                    (do
                      (-> e .printStackTrace)
                      nil)))))]
    (when v
      (swap! cache-atom assoc x v))
    v))

(defn flatten-deps [xs]
  (->> xs
       (mapcat (fn [[k v]]
                 (apply list k v)))))

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

(defn divide-by
  "Divides `coll` in `n` parts. The parts can have disparate sizes if the division isn't exact."
  {:author  "https://github.com/nedap/utils.collections"
   :license "Eclipse Public License 2.0"}
  [n
   coll]
  (let [the-count (count coll)
        seed [(-> the-count double (/ n) Math/floor)
              (rem the-count n)
              []
              coll]
        recipe (iterate (fn [[quotient remainder output input]]
                          (let [chunk-size (+ quotient (if (pos? remainder)
                                                         1
                                                         0))
                                addition (take chunk-size input)
                                result (cond-> output
                                         (seq addition) (conj addition))]
                            [quotient
                             (dec remainder)
                             result
                             (drop chunk-size input)]))
                        seed)
        index (inc n)]
    (-> recipe
        (nth index)
        (nth 2))))

(def parallelism-factor
  "A reasonable factor for parallel Maven resolution, which tries to maximise efficiency
  while keeping thread count low which seems detrimental for both us and the Maven servers."
  (if (find-ns 'lein-monolith.task.each)
    1
    4))

(defn add [{:keys                                        [repositories managed-dependencies]
            {:keys [classifiers]
             :or   {classifiers #{"javadoc" "sources"}}} :resolve-java-sources-and-javadocs
            :as                                          project}]

  (-> cache-filename java.io.File. .createNewFile)

  (let [classifiers (set classifiers)
        repositories (->> repositories
                          (remove (fn [[_ {:keys [url] :as x}]]
                                    (or (->> x keys (some #{:password}))
                                        ;; Some domains may be behind under a VPN we are disconnected from:
                                        (try
                                          (when-let [{:keys [host]} (some-> url URI. bean)]
                                            (InetAddress/getByName host)
                                            false)
                                          (catch UnknownHostException _
                                            true)))))
                          (into {}))
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
                                                (mapcat (partial derivatives classifiers managed-dependencies memoized-resolve!)))))
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
                  ;; the likelihood for it is very low (`lein deps` takes seconds if not minutes),
                  ;; while the window for a race is of about 1ms.
                  ;; At most one risks dropping some additions (which can always be re-cached later),
                  ;; but no actual issues/errors can arise.

                  ;; A possible fix would be to make `write-file!` also use StandardOpenOption/READ
                  ;; (which was problematic on its own as it resulted in files being appended to, instead of overwritten)
                  (write-file! cache-filename
                               (read-file! cache-filename)
                               (make-merge-fn cache-atom)))
                ;; order can be sensitive
                (into additions deps))))))

(defn resolve-java-sources-and-javadocs
  [project & args]
  (add project))
