;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns startcheck
  "Calls the private start! with the baked core and another classpath, which
  init! never does, to reach the core's own refusal."
  (:require [net.b12n.jolt.openldk :as ldk]))

(defn -main [& _]
  (let [dist (ldk/dist-dir)
        cwd (System/getProperty "user.dir")]
    (#'ldk/start! {:dist dist
                   :core (str cwd "/target/openldk/openldk.core")
                   :classpath (str cwd "/src")})
    (println "started")
    (shutdown-agents)))
