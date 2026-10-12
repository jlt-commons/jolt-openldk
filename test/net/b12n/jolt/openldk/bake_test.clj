;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.bake-test
  "The bake driver and init! choosing the baked core, each in a jolt subprocess
  because SBCL starts once per process. Skips when bridge/build.sh has not been
  run, unless JOLT_OPENLDK_REQUIRE is set."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [net.b12n.jolt.openldk :as ldk]
            [net.b12n.jolt.openldk.bake :as bake]
            [net.b12n.jolt.openldk.manifest :as manifest]))

(def have-build?
  (let [dist (ldk/dist-dir)]
    (cond
      (ldk/built? dist) true
      (System/getenv "JOLT_OPENLDK_REQUIRE")
      (throw (ex-info (str "no build in " dist " and JOLT_OPENLDK_REQUIRE is set") {:dist dist}))
      :else (do (println "  (skipping net.b12n.jolt.openldk.bake-test: no build in" dist
                         "; run bb build)")
                false))))

(defmacro when-built [& body] `(when have-build? ~@body))

(def fixture (.getAbsolutePath (io/file "test/fixtures/bake-project")))

(defn- delete-tree [f]
  (let [f (io/file f)]
    (when (.isDirectory f) (run! delete-tree (.listFiles f)))
    (.delete f)))

(defn- clean! [] (delete-tree (io/file fixture "target")))

(defn- jolt [dir & args]
  (apply sh "jolt" (concat args [:dir dir])))

(defn- bake! [dir] (jolt dir "-M" "-m" "net.b12n.jolt.openldk.bake"))

(defmacro with-clean-fixture [& body]
  `(do (clean!) (try ~@body (finally (clean!)))))

(deftest baked-core-is-used-and-compiles-nothing-at-run-time
  (when-built
   (with-clean-fixture
     (let [b (bake! fixture)]
       (is (zero? (:exit b)) (:err b))
       (is (.exists (io/file fixture "target/openldk/bake-manifest.edn")))
       (let [core (str (.getCanonicalPath (io/file fixture)) "/target/openldk/openldk.core")
             r (sh "jolt" "-m" "bakecheck" :dir fixture :env (assoc (into {} (System/getenv)) "LDK_DEBUG" "L"))]
         (is (zero? (:exit r)) (:err r))
         (is (str/includes? (:out r) "add 42"))
         (is (str/includes? (:out r) (str "core " fixture "/target/openldk/openldk.core")) core)
         (is (not (re-find (re-pattern "; COMPILING Fixture\\.") (str (:out r) (:err r))))))))))

(deftest init-refuses-stale-core
  (when-built
   (with-clean-fixture
     (let [tmp (doto (java.io.File/createTempFile "stale" "") .delete .mkdirs)
           classes (io/file tmp "classes")]
       (try
         (.mkdirs classes)
         (doseq [f (.listFiles (io/file "target/test-classes"))]
           (io/copy f (io/file classes (.getName f))))
         (spit (io/file tmp "deps.edn")
               (pr-str {:deps {'net.b12n/jolt-openldk {:local/root (.getAbsolutePath (io/file "."))}}
                        :jolt-openldk/classpath [(str classes)]}))
         (.mkdirs (io/file tmp "src"))
         (io/copy (io/file fixture "src/bakecheck.clj") (io/file tmp "src/bakecheck.clj"))
         (is (zero? (:exit (bake! (str tmp)))))
         (with-open [o (java.io.FileOutputStream. (io/file classes "Fixture.class") true)]
           (.write o 0))
         (let [r (jolt (str tmp) "-m" "bakecheck")]
           (is (pos? (:exit r)))
           (is (str/includes? (:err r) "stale") (:err r))
           (is (str/includes? (:err r) "re-run")))
         (finally (delete-tree tmp)))))))

(deftest init-refuses-core-without-manifest
  (when-built
   (with-clean-fixture
     (is (zero? (:exit (bake! fixture))))
     (.delete (io/file fixture "target/openldk/bake-manifest.edn"))
     (let [r (jolt fixture "-m" "bakecheck")]
       (is (pos? (:exit r)))
       (is (str/includes? (:err r) "did not finish") (:err r))))))

(deftest baked-core-refuses-another-classpath-at-setup
  (when-built
   (with-clean-fixture
     (is (zero? (:exit (bake! fixture))))
     (let [r (jolt fixture "-m" "startcheck")
           msg (str (:err r) (:out r))]
       (is (pos? (:exit r)) msg)
       (is (not (str/includes? msg "started")))
       (is (str/includes? msg "/target/test-classes") msg)
       (is (str/includes? msg (str fixture "/src")) msg)))))

(defn- copy-fixture-with-warm [warm]
  (let [tmp (doto (java.io.File/createTempFile "warm" "") .delete .mkdirs)]
    (spit (io/file tmp "deps.edn")
          (pr-str {:deps {'net.b12n/jolt-openldk {:local/root (.getAbsolutePath (io/file "."))}}
                   :jolt-openldk/classpath [(.getAbsolutePath (io/file "target/test-classes"))]
                   :jolt-openldk/warm warm}))
    tmp))

(deftest a-failed-bake-leaves-nothing-behind
  (when-built
   (let [tmp (copy-fixture-with-warm ["java.time.LocalDate"])]
     (try
       (is (zero? (:exit (bake! (str tmp)))) "a good bake first")
       (is (.exists (io/file tmp "target/openldk/openldk.core")))
       (spit (io/file tmp "deps.edn")
             (pr-str {:deps {'net.b12n/jolt-openldk {:local/root (.getAbsolutePath (io/file "."))}}
                      :jolt-openldk/classpath [(.getAbsolutePath (io/file "target/test-classes"))]
                      :jolt-openldk/warm ["java.time.NoSuch"]}))
       (let [r (bake! (str tmp))
             left (set (.list (io/file tmp "target/openldk")))]
         (is (pos? (:exit r)))
         (is (not (contains? left "openldk.core")) (pr-str left))
         (is (not (contains? left "bake-manifest.edn")) (pr-str left))
         (is (not-any? #(str/ends-with? % ".tmp") left) (pr-str left)))
       (finally (delete-tree tmp))))))

(deftest a-classpath-that-changed-during-the-bake-is-refused
  ;; Driving a real mid-bake change is impractical, so this tests the check
  ;; bake! makes between its two fingerprints.
  (let [tmp (doto (java.io.File/createTempFile "fpr" "") .delete .mkdirs)
        cls (io/file tmp "C.class")
        entries [(str tmp)]]
    (try
      (spit cls "one")
      (let [before (manifest/fingerprint entries)]
        (is (nil? (bake/ensure-unchanged! before (manifest/fingerprint entries))))
        (spit cls "two")
        (is (thrown-with-msg? Exception (re-pattern "classpath changed during the bake")
                              (bake/ensure-unchanged! before (manifest/fingerprint entries)))))
      (finally (delete-tree tmp)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk.bake-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
