;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.bake
  "Bakes a project's classpath into target/openldk/openldk.core, which init!
  then starts instead of the shared core:

    jolt -M -m net.b12n.jolt.openldk.bake

  It reads the classpath the way init! does (:jolt-openldk/classpath in
  deps.edn, else jvm/), runs the dist's bake.sh, and writes
  bake-manifest.edn last, so a core without one is a bake that did not finish."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [net.b12n.jolt.openldk :as ldk]
            [net.b12n.jolt.openldk.manifest :as manifest]
            [net.b12n.jolt.openldk.project :as project]))

(defn- fail [msg data] (throw (ex-info (str "jolt-openldk bake: " msg) data)))

(defn- bake-lines
  "The bridge's own `bake:` lines in the bake output, the last five at most."
  [text]
  (vec (take-last 5 (filter #(str/starts-with? % "bake:") (str/split-lines text)))))

(defn- delete-quietly [path] (.delete (io/file path)))

(defn ensure-unchanged!
  "Throws unless the classpath fingerprint `after` the bake equals `before`:
  a class or jar that changed while bake.sh ran is not what the core holds."
  [before after]
  (when (not= before after)
    (fail "classpath changed during the bake; run it again" {:before before :after after})))

(defn bake!
  "Bake the classpath of the project at (:root opts), default the current
  directory, into <root>/target/openldk. (:home opts) picks the build, as for
  init!. Returns the counts map, or throws ex-info and leaves no core."
  [{:keys [root home]}]
  (let [root (str (or root (System/getProperty "user.dir")))
        dist (ldk/dist-dir {:home home})
        {:keys [classpath entries source]} (project/resolve-classpath root nil)]
    (when (#{:default :explicit} source)
      (fail (str "nothing to bake in " root ": declare :jolt-openldk/classpath in deps.edn or add jars to jvm/")
            {:root root :source source}))
    (when-not (ldk/built? dist)
      (fail (str "no build in " dist "; run bb build, or set JOLT_OPENLDK_HOME") {:dist dist}))
    (let [dist-manifest (edn/read-string (slurp (io/file dist "manifest.edn")))
          script (:bake-script dist-manifest)]
      (when-not (and script (.exists (io/file script)))
        (fail (str "the build in " dist " has no bake script; re-run bb build") {:dist dist}))
      (let [warm (project/warm-classes root)
            out (io/file root "target" "openldk")
            core (str out "/openldk.core")
            core-tmp (str core ".tmp")
            counts-file (str out "/counts.edn")
            counts-tmp (str counts-file ".tmp")
            log (str out "/bake.log")
            manifest-file (str out "/bake-manifest.edn")]
        (.mkdirs out)
        ;; A manifest left from an earlier bake must not vouch for this one.
        (doseq [p [core manifest-file counts-file core-tmp counts-tmp]] (delete-quietly p))
        (try
          (let [before (manifest/fingerprint entries)
                {:keys [exit out err]} (apply sh script core-tmp counts-tmp classpath
                                              (map #(str/replace % "." "/") warm))]
            (spit log (str out err))
            (when-not (zero? exit)
              (let [lines (bake-lines (str out err))]
                (fail (str "bake.sh exited " exit
                           (apply str (map #(str "\n" %) lines))
                           "\nsee " log)
                      {:exit exit :log log :bake-lines lines})))
            (ensure-unchanged! before (manifest/fingerprint entries))
            (let [counts (edn/read-string (slurp counts-tmp))]
              (when-not (.renameTo (io/file core-tmp) (io/file core))
                (fail (str "could not move " core-tmp " to " core) {:core core}))
              (spit counts-file (pr-str counts))
              (spit manifest-file (pr-str (manifest/build {:fingerprint before :entries entries :classpath classpath
                                                           :warm warm :dist-manifest dist-manifest
                                                           :counts counts})))
              counts))
          (catch Throwable t
            ;; A failed bake leaves no core, and no manifest to vouch for one.
            (doseq [p [core manifest-file counts-file]] (delete-quietly p))
            (throw t))
          (finally
            (doseq [p [core-tmp counts-tmp]] (delete-quietly p))))))))

(defn -main [& _]
  (try
    (let [counts (bake! {})
          out (str (System/getProperty "user.dir") "/target/openldk")
          core (str out "/openldk.core")
          log (str out "/bake.log")]
      (println "baked" core)
      (println "counts" (pr-str counts))
      (println "skipped" (or (:classes-skipped counts) 0) "failed" (or (:methods-failed counts) 0))
      (println "core size" (format "%.1f MB" (/ (.length (io/file core)) 1048576.0)))
      (println "log" log)
      (shutdown-agents)
      (System/exit 0))
    (catch Exception e
      (binding [*out* *err*] (println (ex-message e)))
      (shutdown-agents)
      (System/exit 1))))
