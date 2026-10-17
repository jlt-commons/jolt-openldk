;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns initcheck
  "What a call sees around init!. Mode before-init calls Java without init!
  and prints {:error message}. Mode during-init starts a future that sleeps
  50 ms and then calls Java, runs init! on the main thread, and prints the
  future's answer, so the call lands while init! is still starting."
  (:require [net.b12n.jolt.openldk :as ldk]))

(defn -main [mode classes]
  (case mode
    "before-init"
    (prn (try {:ok (ldk/call-static "java.lang.Math" "max" "(II)I" 3 4)}
              (catch Throwable e {:error (ex-message e)})))
    "during-init"
    (let [t0 (System/nanoTime)
          fut (future (Thread/sleep 50)
                      (ldk/call-static "java.lang.Math" "max" "(II)I" 3 4))]
      (ldk/init! {:classpath [classes]})
      (binding [*out* *err*]
        (println "init! took ms:" (/ (- (System/nanoTime) t0) 1e6)))
      (prn {:ok (deref fut 60000 :timed-out)}))
    "reinit"
    ;; A call that waited out init! must not keep holding init-lock through
    ;; its own Java call: a repeat init! during that call would stall for the
    ;; call's whole length (here a 2 s Java sleep).
    (let [fut (future (Thread/sleep 50)
                      (ldk/call-static "java.lang.Thread" "sleep" "(J)V" 2000)
                      :slept)]
      (ldk/init! {:classpath [classes]})
      (Thread/sleep 150)
      (let [t0 (System/nanoTime)]
        (ldk/init! {:classpath [classes]})
        (let [fast (< (/ (- (System/nanoTime) t0) 1e6) 1000)]
          (prn {:ok [(deref fut 60000 :timed-out) fast]})))))
  (shutdown-agents))
