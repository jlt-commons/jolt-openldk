;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns reinit
  "Starts on the baked core, then removes the core and edits deps.edn under
  the running process. A repeat no-arg init! must not look at either."
  (:require [clojure.java.io :as io]
            [net.b12n.jolt.openldk :as ldk]))

(defn -main [& _]
  (ldk/init!)
  (.delete (io/file "target/openldk/openldk.core"))
  (spit "deps.edn" "{:jolt-openldk/classpath []}")
  (println "again" (ldk/init!))
  (println "add" (ldk/call-static "Fixture" "add" "(II)I" 40 2))
  (shutdown-agents))
