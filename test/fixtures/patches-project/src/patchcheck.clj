;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns patchcheck
  "Starts OpenLDK on one classpath entry, constructs one object and prints
  {:ok its-toString} or {:error message}. The caller runs it under a watchdog,
  since what it checks is a call that used to never return."
  (:require [net.b12n.jolt.openldk :as ldk]))

(defn -main [entry class-name descriptor & args]
  (ldk/init! {:classpath [entry]})
  (prn (try {:ok (ldk/to-string (apply ldk/new-object class-name descriptor args))}
            (catch Throwable e {:error (ex-message e)})))
  (shutdown-agents))
