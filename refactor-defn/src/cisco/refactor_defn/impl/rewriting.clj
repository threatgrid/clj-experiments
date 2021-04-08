(ns cisco.refactor-defn.impl.rewriting
  (:require
   [clojure.string :as string]
   [nedap.speced.def :as speced]
   [rewrite-clj.node :as node]
   [rewrite-clj.node.meta :as node.meta]
   [rewrite-clj.node.protocols :refer [Node]]
   [rewrite-clj.node.string :as node.string]
   [rewrite-clj.zip :as zip]
   [rewrite-clj.zip.base]
   [rewrite-clj.zip.edit]
   [rewrite-clj.zip.move]
   [rewrite-clj.zip.remove]
   [schema.core]
   [schema.macros :as macros]))

(defmacro tails-schemas
  "Syntax exactly like s/defn's.

  Returns the schemas for each tail. Does not include ret val schema."
  [& defn-args]
  (let [[name & more-defn-args] (macros/normalized-defn-args &env defn-args)

        {:keys [outer-bindings]} (macros/process-fn- &env name more-defn-args)
        bindings (->> outer-bindings
                      (partition 2))]
    {:output-schema (list 'quote (->> bindings
                                      (filter (fn [[k v]]
                                                (-> k str (string/starts-with? "output-schema"))))
                                      (map second)
                                      (remove #{'schema.core/Any})
                                      first))
     :input-schema  (list 'quote (->> bindings
                                      (filter (fn [[k v]]
                                                (-> k str (string/starts-with? "input-schema"))))
                                      (map second)
                                      (map (fn [tail]
                                             (->> tail
                                                  (map (fn [[_one schema _]]
                                                         (when-not (#{'schema.core/Any} schema)
                                                           schema))))))))}))

(defn r [n]
  (or (zip/right n)
      n))

(defn safely-move-to-rightmost-node [n]
  (if (zip/rightmost? n)
    n
    (recur (r n))))

(defn defn-node? [[node]]
  (and (-> node node/tag #{:list})
       (-> node node/sexpr first name #{"defn"})))

(speced/defn remove-schema-return-annotation [^defn-node? node]
  (zip/up* (loop [target (zip/down* node)
                  found? false]
             (if found?
               (-> target zip/left zip/remove zip/right zip/remove)
               (if (zip/rightmost? target)
                 target
                 (recur (r target)
                        (= :- (zip/sexpr target))))))))

(defn vector-node? [[node]]
  (some-> node node/tag #{:vector}))

(speced/defn ^vector-node? remove-all-schema-annotations-in-vector [^vector-node? node]
  (if (-> node first node/sexpr #{[]})
    node
    (zip/up* (loop [target (zip/down* node)
                    found? false]
               (if found?
                 ;; remove (no star) is evil, it moves to a non- left/right position (see its doc)
                 (recur (-> target zip/left zip/remove* r zip/remove* r)
                        false)
                 (if (zip/rightmost? target)
                   target
                   (recur (r target)
                          (= :- (zip/sexpr target)))))))))

(speced/defn ^vector-node? add-input-annotations [^vector-node? node schemas]
  {:pre [(= (-> node first node/sexpr count)
            (count schemas))]}
  (if (-> node first node/sexpr #{[]})
    node
    (->> schemas
         (reduce (fn [n s]
                   (r (if-not s
                        n
                        (let [r (node/meta-node s (-> n
                                                      node/sexprs
                                                      first))]
                          (zip/replace n r)))))
                 (zip/down* node))
         zip/up*)))

(speced/defn ^vector-node? next-vector-node [node]
  (loop [c (zip/down* node)]
    (cond
      (vector-node? c) c
      (some? c)        (recur (zip/right c)))))

(defn list-node? [[node]]
  (some-> node node/tag #{:list}))

(speced/defn ^list-node?
  maybe-process-arg-vector [^list-node? node, input-schema]
  (if-not (->> node first node/sexpr (some vector?))
    node
    (-> node
        next-vector-node
        remove-all-schema-annotations-in-vector
        (add-input-annotations input-schema)
        zip/up*)))

(speced/defn ^::speced/nilable ^list-node? maybe-next-list-node [node]
  (loop [c node]
    (cond
      (list-node? c) c
      (some? c)      (recur (zip/right c)))))

(speced/defn ^defn-node? maybe-process-arity-lists
  [^defn-node? node {:keys [input-schema output-schema] :as schemas}]
  (if-not (->> node first node/sexpr (some list?))
    node
    (let [counter (volatile! 0)]
      (zip/up* (loop [n (zip/down* node)]
                 (let [processed (some-> n
                                         maybe-next-list-node
                                         (maybe-process-arg-vector (nth input-schema @counter)))]
                   (vswap! counter inc)
                   (if-not processed
                     n
                     (if-not (zip/right processed)
                       processed
                       (recur (zip/right processed))))))))))

(defn symbol-node? [[node]]
  (and (some-> node node/tag #{:token})
       (-> node node/sexpr symbol?)))

(speced/defn ^symbol-node? next-symbol-node [node]
  (loop [c node]
    (cond
      (symbol-node? c) c
      (some? c)        (recur (zip/right c)))))

(speced/defn ^defn-node? add-defn-meta
  "Adds ::speced/schema + maybe the ret val spec ann"
  [^defn-node? node output-schema]
  (let [n (-> node
              zip/down*
              r
              node/sexprs
              first)
        n (->> [output-schema :nedap.speced.def/schema]
               (filter identity)
               (reduce (fn [acc meta-thing]
                         (node/meta-node meta-thing acc))
                       n))]
    (-> node
        zip/down*
        r
        next-symbol-node
        (zip/replace n)
        zip/up*)))

(defn process-defn [node {:keys [input-schema output-schema] :as schemas}]
  (-> node
      remove-schema-return-annotation
      (maybe-process-arg-vector (first input-schema))
      (maybe-process-arity-lists schemas)
      (add-defn-meta output-schema)))

(defn defn-rewriter [node]
  (let [e (try
            (zip/sexpr node)
            (catch UnsupportedOperationException _))]
    (if-not (and (list? e)
                 ;; XXX analyze aliases
                 (-> e first #{'s/defn}))
      node
      (process-defn node (eval (apply list `tails-schemas (rest e)))))))
