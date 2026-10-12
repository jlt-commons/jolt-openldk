;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.maven
  "Turns the :jolt-openldk/deps key of deps.edn into jar paths through jolt's
  own resolver. Direct coordinates only: jolt doesn't read the POMs of
  Java-only jars, so nothing transitive is fetched."
  (:require [clojure.string :as str]))

(defn- bad-deps [entry-or-value]
  (ex-info (str "jolt-openldk: :jolt-openldk/deps in deps.edn must map qualified symbols to "
                "{:mvn/version \"<version>\"}, got " (pr-str entry-or-value))
           {:key :jolt-openldk/deps :value entry-or-value}))

(defn- good-entry? [[lib coord]]
  (and (symbol? lib)
       (namespace lib)
       (map? coord)
       (= [:mvn/version] (keys coord))
       (string? (:mvn/version coord))
       (not (str/blank? (:mvn/version coord)))))

(defn validate-deps
  "Returns `v` when it is nil or a map of qualified symbols to
  {:mvn/version \"<version>\"}, else throws ex-info naming the offending part."
  [v]
  (when-not (or (nil? v) (map? v))
    (throw (bad-deps v)))
  (doseq [entry v]
    (when-not (good-entry? entry)
      (throw (bad-deps entry))))
  v)

(defn resolve-jars
  "Resolves `deps` (a validated :jolt-openldk/deps map) to
  {:jars [absolute-path ...] :libs {lib coord}} with `resolve-fn`, called as
  (resolve-fn deps root). It defaults to jolt.deps/resolve-deps. An empty map
  never calls it."
  ([root deps]
   (if (empty? deps)
     {:jars [] :libs {}}
     (resolve-jars root deps
                   (or (requiring-resolve 'jolt.deps/resolve-deps)
                       (throw (ex-info "jolt-openldk: :jolt-openldk/deps needs jolt.deps/resolve-deps, which this jolt doesn't have. Use jolt 0.8.19 or later."
                                       {:key :jolt-openldk/deps}))))))
  ([root deps resolve-fn]
   (if (empty? deps)
     {:jars [] :libs {}}
     (let [result (try (resolve-fn deps root)
                       (catch Exception e
                         (if-let [unresolved (:unresolved (ex-data e))]
                           (throw (ex-info (str "jolt-openldk: cannot resolve :jolt-openldk/deps: "
                                                (ex-message e))
                                           {:key :jolt-openldk/deps :unresolved unresolved}
                                           e))
                           (throw e))))]
       (doseq [r (:roots result)]
         (when-not (str/ends-with? r ".jar")
           (throw (ex-info (str "jolt-openldk: jolt resolved :jolt-openldk/deps to something that is not a jar: " r)
                           {:key :jolt-openldk/deps :root r}))))
       {:jars (vec (:roots result)) :libs (:libs result)}))))
