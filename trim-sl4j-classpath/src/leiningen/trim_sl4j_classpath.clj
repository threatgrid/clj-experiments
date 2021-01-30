(ns leiningen.trim-sl4j-classpath)

(defn trim [coordinate]
  {:pre [(vector? coordinate)
         (not (vector? (first coordinate)))]}
  (let [coordinate (cond-> coordinate
                     (not (some #{:exclusions} coordinate))
                     (conj :exclusions []))]
    (->> coordinate

         (reduce (fn [{:keys [found? result]} x]
                   (if found?
                     {:found? false
                      :result (conj result
                                    (conj x
                                          ['ch.qos.logback/logback-classic]
                                          ['org.slf4j/slf4j-log4j12]
                                          ['org.slf4j/slf4j-nop]))}
                     {:found? (= x :exclusions)
                      :result (conj result x)}))
                 {:found? false
                  :result []})

         :result)))

(defn middleware [project]
  (update project :dependencies (fn [deps]
                                  (->> deps
                                       (mapv trim)))))
