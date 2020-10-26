(ns formatting-stack.are-linter.impl
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as spec]
   [clojure.string :as string]
   [clojure.tools.reader :as tools.reader]
   [clojure.tools.reader.reader-types :refer [indexing-push-back-reader push-back-reader]]
   [clojure.walk :as walk]
   [formatting-stack.linters.ns-aliases]
   [formatting-stack.strategies.impl]
   [formatting-stack.util :refer [ensure-sequential process-in-parallel! rcomp]]
   [nedap.speced.def :as speced])
  (:import
   (clojure.lang Namespace)))

(spec/def ::argv (spec/and (spec/coll-of any? :kind vector? :min-count 1)
                           (partial apply distinct?)))

(defn is-or-contains-list? [x]
  (and (coll? x)
       (let [r (atom false)]
         (->> x
              (walk/postwalk (fn [x]
                               (when (list? x)
                                 (reset! r true))
                               x)))
         @r)))

(speced/defn lint [filename [_are
                             ^::argv argv
                             ^some? expr
                             & ^{::speced/spec (spec/coll-of any? :kind sequential? :min-count 1)} args
                             :as are-form]]
  {:pre [(zero? (mod (count args) (count argv)))]}
  (let [result (->> args
                    (partition (count argv))
                    (keep (fn [row]
                            (let [m (->> (interleave argv row)
                                         (partition 2)
                                         (map vec)
                                         (into {}))
                                  counts (atom {})]
                              (->> expr
                                   (walk/postwalk (fn [x]
                                                    (when (is-or-contains-list? (get m x))
                                                      (swap! counts update x (fnil inc 0)))
                                                    x)))
                              (when (->> @counts
                                         vals
                                         (some (fn [x]
                                                 (> x 1))))
                                [row @counts])))))
        {:keys [line column]} (meta are-form)]
    (when (seq result)
      (cond-> {:filename filename
               :source   :formatting-stack.are-linter.api/id
               :level    :warning
               :msg      (->> result
                              (map (fn [[row _result]]
                                     (apply str
                                            "The following row invokes a function/macro more than once within the test body: "
                                            (apply pr-str row))))
                              (string/join \newline))}
        line   (assoc :line line)
        column (assoc :column column)))))

(speced/defn ^{::speced/spec (spec/coll-of (spec/and qualified-symbol?
                                                     (rcomp namespace (complement #{"clojure.test"
                                                                                    "cljs.test"}))
                                                     (rcomp name #{"are"})))}
  aliased-are-names-of [filename]
  (->> filename
       formatting-stack.util/read-ns-decl
       formatting-stack.util/require-from-ns-decl
       (rest)
       (map formatting-stack.linters.ns-aliases/name-and-alias)
       (keep (speced/fn [[^simple-symbol? ns-name, ^simple-symbol? ns-alias]]
               (when (and (#{'clojure.test 'cljs.test} ns-name)
                          alias)
                 (symbol (str ns-alias)
                         "are"))))))

(speced/defn ^{::speced/spec (spec/coll-of (spec/and list?
                                                     (rcomp first name #{"are"})))}
  find-are-forms [^string? filename]
  (let [ns-obj (-> filename
                   formatting-stack.strategies.impl/filename->ns)
        known #{'clojure.test/are
                'cljs.test/are
                'are}
        are-names (cond-> known
                    ns-obj       (into (->> (ns-aliases ns-obj)
                                            (keep (speced/fn [[^symbol? alias, ^Namespace n]]
                                                    (when (->> known
                                                               (keep namespace)
                                                               (some #{(-> n str)}))
                                                      (symbol (str alias) "are"))))))
                    (not ns-obj) (into (aliased-are-names-of filename)))
        are-forms (atom [])
        reader (-> filename io/reader push-back-reader indexing-push-back-reader)]
    (loop []
      (speced/let [x (tools.reader/read reader false ::eof)
                   ^set? known-are-names are-names]
        (when (list? x)
          (let [result (atom false)]
            (->> x
                 (walk/postwalk (fn [v]
                                  (when (and (list? v)
                                             (-> v first known-are-names))
                                    (swap! are-forms conj (with-meta v
                                                            (-> x meta (select-keys [:line :column])))))
                                  v)))
            @result))
        (when-not (#{::eof} x)
          (recur))))
    @are-forms))

(defn lint! [this filenames]
  (->> filenames
       (process-in-parallel! (fn [filename]
                               (->> filename
                                    find-are-forms
                                    (keep (partial lint filename))
                                    (vec))))
       (mapcat ensure-sequential)))
