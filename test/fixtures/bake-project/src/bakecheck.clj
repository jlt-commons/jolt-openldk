;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns bakecheck
  (:require [net.b12n.jolt.openldk :as ldk]))

(defn -main [& _]
  (ldk/init!)
  (println "add" (ldk/call-static "Fixture" "add" "(II)I" 40 2))
  (println "core" (:core (ldk/status)))
  (shutdown-agents))
