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
         (is (str/includes? (:out r) "date 2024-02-29") (:out r))
         (is (not (re-find (re-pattern "; COMPILING Fixture\\.") (str (:out r) (:err r)))))
         (is (not (re-find (re-pattern "; COMPILING java/time/LocalDate\\.of") (str (:out r) (:err r))))
             "a warmed JDK class compiles nothing at run time"))))))

(deftest a-repeat-init-ignores-what-changed-on-disk
  ;; reinit deletes the core and rewrites deps.edn, so it runs on a copy.
  (when-built
   (let [tmp (doto (java.io.File/createTempFile "reinit" "") .delete .mkdirs)]
     (try
       (.mkdirs (io/file tmp "src"))
       (io/copy (io/file fixture "src/reinit.clj") (io/file tmp "src/reinit.clj"))
       (spit (io/file tmp "deps.edn")
             (pr-str {:deps {'net.b12n/jolt-openldk {:local/root (.getAbsolutePath (io/file "."))}}
                      :jolt-openldk/classpath [(.getAbsolutePath (io/file "target/test-classes"))]}))
       (is (zero? (:exit (bake! (str tmp)))))
       (let [r (jolt (str tmp) "-m" "reinit")]
         (is (zero? (:exit r)) (:err r))
         (is (str/includes? (:out r) "again true") (:out r))
         (is (str/includes? (:out r) "add 42") (:out r)))
       (finally (delete-tree tmp))))))

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
    (.mkdirs (io/file tmp "src"))
    (io/copy (io/file fixture "src/bakecheck.clj") (io/file tmp "src/bakecheck.clj"))
    (spit (io/file tmp "deps.edn")
          (pr-str {:deps {'net.b12n/jolt-openldk {:local/root (.getAbsolutePath (io/file "."))}}
                   :jolt-openldk/classpath [(.getAbsolutePath (io/file "target/test-classes"))]
                   :jolt-openldk/warm warm}))
    tmp))

(defn- write-deps-with-coord! [tmp coord version]
  (spit (io/file tmp "deps.edn")
        (pr-str {:deps {'net.b12n/jolt-openldk {:local/root (.getAbsolutePath (io/file "."))}}
                 :jolt-openldk/deps {coord {:mvn/version version}}
                 :jolt-openldk/warm ["java.time.LocalDate"]})))

(defn- copy-fixture-with-deps [coord-version]
  (let [tmp (doto (java.io.File/createTempFile "coord" "") .delete .mkdirs)]
    (.mkdirs (io/file tmp "src"))
    (io/copy (io/file fixture "src/bakecheck.clj") (io/file tmp "src/bakecheck.clj"))
    (write-deps-with-coord! tmp 'net.b12n.test/fixture coord-version)
    tmp))

(deftest a-coordinate-jar-is-on-the-classpath
  (when-built
   (let [tmp (copy-fixture-with-deps "1.0")]
     (try
       (let [r (jolt (str tmp) "-m" "bakecheck")]
         (is (zero? (:exit r)) (:err r))
         (is (str/includes? (:out r) "add 42") (:out r)))
       (finally (delete-tree tmp))))))

(deftest a-bake-of-coordinates-is-used-then-goes-stale-on-a-version-bump
  (when-built
   (let [tmp (copy-fixture-with-deps "1.0")]
     (try
       (let [b (bake! (str tmp))]
         (is (zero? (:exit b)) (:err b)))
       (let [r (jolt (str tmp) "-m" "bakecheck")]
         (is (zero? (:exit r)) (:err r))
         (is (str/includes? (:out r) (str "core " (.getCanonicalPath tmp) "/target/openldk/openldk.core")) (:out r)))
       (write-deps-with-coord! tmp 'net.b12n.test/fixture "2.0")
       (let [r (jolt (str tmp) "-m" "bakecheck")]
         (is (pos? (:exit r)))
         (is (str/includes? (:err r) "stale") (:err r)))
       (finally (delete-tree tmp))))))

(deftest a-missing-coordinate-stops-init-before-sbcl
  ;; No when-built: it fails during resolution, before any build is needed.
  (let [tmp (copy-fixture-with-deps "1.0")]
    (try
      (write-deps-with-coord! tmp 'no.such/thing "1.0")
      (let [r (jolt (str tmp) "-m" "bakecheck")]
        (is (pos? (:exit r)))
        (is (str/includes? (:err r) "cannot resolve :jolt-openldk/deps") (:err r))
        (is (str/includes? (:err r) "no.such/thing") (:err r)))
      (finally (delete-tree tmp)))))

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
         (is (str/includes? (:err r) "java/time/NoSuch") (:err r))
         (is (re-find (re-pattern "bake: unknown warm class java/time/NoSuch")
                      (slurp (io/file tmp "target/openldk/bake.log"))))
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

(deftest the-bake-summary-lists-every-skipped-and-failed-line
  (let [lines (concat ["bake: /x/a.jar: 3 classes" "; COMPILING Foo"]
                      (for [i (range 8)] (str "bake: skipped C" i ": classload returned NIL"))
                      ["bake: failed C.m: JIT-ERROR" "bake: counts {:classes-skipped 8}"])]
    (is (= (concat (for [i (range 8)] (str "bake: skipped C" i ": classload returned NIL"))
                   ["bake: failed C.m: JIT-ERROR"])
           (bake/report-lines (str/join "\n" lines))))
    (is (= [] (bake/report-lines "bake: counts {}\nnothing")))))

(deftest the-bake-driver-prints-a-skipped-class
  ;; Sub extends Gone, whose class file is absent from the classpath.
  (when-built
   (let [tmp (doto (java.io.File/createTempFile "skip" "") .delete .mkdirs)
         classes (io/file tmp "classes")]
     (try
       (.mkdirs classes)
       (apply sh (str (System/getenv "JAVA_HOME") "/bin/javac") "--release" "25" "-d" (str classes)
              (map str (.listFiles (io/file "test/java/missing"))))
       (io/delete-file (io/file classes "Gone.class"))
       (.mkdirs (io/file tmp "src"))
       (spit (io/file tmp "deps.edn")
             (pr-str {:deps {'net.b12n/jolt-openldk {:local/root (.getAbsolutePath (io/file "."))}}
                      :jolt-openldk/classpath [(str classes)]}))
       (let [r (bake! (str tmp))]
         (is (zero? (:exit r)) (:err r))
         (is (str/includes? (:out r) "bake: skipped Sub: ") (:out r))
         (is (< (str/index-of (:out r) "bake: skipped Sub") (str/index-of (:out r) "counts")) (:out r)))
       (finally (delete-tree tmp))))))

(deftest bake-names-missing-classpath-entries-and-takes-a-relative-root
  ;; A relative root: the entry in the message must be absolute, as bake.sh needs.
  (let [tmp (doto (java.io.File/createTempFile "miss" "") .delete .mkdirs)]
    (try
      (spit (io/file tmp "deps.edn") (pr-str {:jolt-openldk/classpath ["nope/classes" "gone.jar"]}))
      (let [rel (str (.relativize (.toPath (.getAbsoluteFile (io/file "."))) (.toPath tmp)))
            e (try (bake/bake! {:root rel}) nil (catch Exception e e))]
        (is (some? e))
        (is (str/includes? (ex-message e) "do not exist") (ex-message e))
        (is (re-find (re-pattern "do not exist: /.*/nope/classes, ") (ex-message e)) (ex-message e))
        (is (str/includes? (ex-message e) "gone.jar")))
      (finally (delete-tree tmp)))))

(deftest warm-list-change-makes-core-stale
  (when-built
   (let [tmp (copy-fixture-with-warm ["java.time.LocalDate"])]
     (try
       (is (zero? (:exit (bake! (str tmp)))))
       (is (zero? (:exit (jolt (str tmp) "-m" "bakecheck"))) "fresh core is accepted")
       (spit (io/file tmp "deps.edn")
             (pr-str {:deps {'net.b12n/jolt-openldk {:local/root (.getAbsolutePath (io/file "."))}}
                      :jolt-openldk/classpath [(.getAbsolutePath (io/file "target/test-classes"))]
                      :jolt-openldk/warm ["java.time.LocalDate" "java.time.LocalTime"]}))
       (let [r (jolt (str tmp) "-m" "bakecheck")]
         (is (pos? (:exit r)))
         (is (str/includes? (:err r) "stale") (:err r))
         (is (str/includes? (:err r) "warm") (:err r)))
       (finally (delete-tree tmp))))))

(deftest an-unwarmed-class-does-compile-at-run-time
  ;; The control for the warm check above: without the warm key the same call
  ;; compiles LocalDate.of when it runs.
  (when-built
   (let [tmp (copy-fixture-with-warm [])]
     (try
       (is (zero? (:exit (bake! (str tmp)))))
       (let [r (sh "jolt" "-m" "bakecheck" :dir (str tmp)
                   :env (assoc (into {} (System/getenv)) "LDK_DEBUG" "L"))]
         (is (zero? (:exit r)) (:err r))
         (is (re-find (re-pattern "; COMPILING java/time/LocalDate\\.of") (str (:out r) (:err r)))))
       (finally (delete-tree tmp))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk.bake-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
