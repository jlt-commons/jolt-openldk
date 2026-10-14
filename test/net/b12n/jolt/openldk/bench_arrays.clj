;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.bench-arrays
  "How long a million-element primitive array takes to cross each way, run by
  bb bench-arrays. Local only: timings vary too much between machines to gate
  CI on. Each read says whether it equals what went in."
  (:require [net.b12n.jolt.openldk :as ldk]))

(def n 1000000)

(defn- timed
  "Run f; [its result, the milliseconds it took]."
  [f]
  (let [t0 (System/nanoTime)
        r (f)]
    [r (/ (- (System/nanoTime) t0) 1e6)]))

(defn- report
  ([label ms] (println (format "  %-46s %8.1f ms" label ms)))
  ([label ms equal?] (println (format "  %-46s %8.1f ms   equal %s" label ms equal?))))

(defn- bench [component v typed]
  (let [[a ms] (timed #(ldk/new-array component v))]
    (report (str "new-array \"" component "\" from a vector") ms)
    (ldk/release! a))
  (let [arr (typed v)
        [a ms] (timed #(ldk/new-array component arr))]
    (report (str "new-array \"" component "\" from a typed array") ms)
    (ldk/with-ref [a a]
      (let [[r ms] (timed #(ldk/array->vec a))]
        (report "array->vec" ms (= v r)))
      (let [[r ms] (timed #(ldk/array->primitive a))]
        (report "array->primitive" ms (= v (vec r)))))))

(defn -main [& _]
  (if-not (ldk/built? (ldk/dist-dir))
    (println "no build in" (ldk/dist-dir) "; run bb build")
    (do
      (ldk/init! {:classpath []})
      (ldk/with-ref [a (ldk/new-array "I" [1 2 3])] (ldk/array->vec a))
      (doseq [run [1 2]]
        (println (str "run " run ", " n " elements each"))
        (bench "I" (vec (range n)) int-array)
        (bench "B" (mapv #(- (mod % 256) 128) (range n)) byte-array)
        (bench "D" (mapv #(* 0.5 %) (range n)) double-array))
      (shutdown-agents))))
