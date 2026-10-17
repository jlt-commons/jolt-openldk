;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns racecheck
  "Starts OpenLDK on one classpath entry, then has n Java threads make the
  same first call at once. Prints {:ok sum} or {:error message}. The caller
  runs it under a watchdog, since what it checks is a lost wakeup that left
  threads waiting forever."
  (:require [net.b12n.jolt.openldk :as ldk]))

(defn -main [classes n]
  (ldk/init! {:classpath [classes]})
  (prn (try {:ok (ldk/call-static "Fixture$Claim" "race" "(I)J" (parse-long n))}
            (catch Throwable e {:error (ex-message e)})))
  (shutdown-agents))
