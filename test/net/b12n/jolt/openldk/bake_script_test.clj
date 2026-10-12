;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.bake-script-test
  "bake.sh against a real build: it bakes a classpath into a core and counts
  what it did. Skips when bridge/build.sh has not been run, unless
  JOLT_OPENLDK_REQUIRE is set."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [net.b12n.jolt.openldk :as ldk]))

(def classes (.getAbsolutePath (io/file (or (System/getenv "JOLT_OPENLDK_TEST_CLASSES") "target/test-classes"))))

(def have-build?
  (let [dist (ldk/dist-dir)]
    (cond
      (ldk/built? dist) true
      (System/getenv "JOLT_OPENLDK_REQUIRE")
      (throw (ex-info (str "no build in " dist " and JOLT_OPENLDK_REQUIRE is set") {:dist dist}))
      :else (do (println "  (skipping net.b12n.jolt.openldk.bake-script-test: no build in" dist
                         "; run bb build)")
                false))))

(defmacro when-built [& body] `(when have-build? ~@body))

(defn- bake-script []
  (str (ldk/dist-dir) "/bake.sh"))

(defn- tmp-dir []
  (let [d (java.io.File/createTempFile "bake" "")]
    (.delete d)
    (.mkdirs d)
    (str d)))

(defn- bake
  "Run bake.sh on `classpath` into a fresh temp dir. Returns the sh result plus
  :core and :counts (the paths) and :counts-map (nil when no counts file)."
  [classpath & warm]
  (let [dir (tmp-dir)
        core (str dir "/c.core")
        counts (str dir "/counts.edn")
        r (apply sh (bake-script) core counts classpath warm)]
    (assoc r :core core :counts counts
           :counts-map (when (.exists (io/file counts)) (edn/read-string (slurp counts))))))

(deftest bake-script-bakes-the-fixture
  (when-built
   (let [{:keys [exit core counts counts-map err]} (bake classes)]
     (is (zero? exit) err)
     (is (.exists (io/file core)))
     (is (.exists (io/file counts)))
     (is (<= 1 (:classes-loaded counts-map)))
     (is (zero? (:methods-failed counts-map))))))

(deftest bake-script-skips-a-class-with-a-missing-superclass
  (when-built
   (let [dir (tmp-dir)]
     (apply sh (str (System/getenv "JAVA_HOME") "/bin/javac") "--release" "25" "-d" dir
            (map str (.listFiles (io/file "test/java/missing"))))
     (io/delete-file (str dir "/Gone.class"))
     (let [{:keys [exit core counts-map err]} (bake dir)]
       (is (zero? exit) err)
       (is (.exists (io/file core)))
       (is (= 1 (:classes-skipped counts-map)))
       (is (= 1 (:classes-loaded counts-map)) "UsesGone still loads")
       (is (str/includes? err "bake: skipped Sub: classload returned NIL") err)))))

(deftest bake-script-unknown-warm-class-fails
  (when-built
   (let [{:keys [exit core err]} (bake classes "java/time/NoSuch")]
     (is (not (zero? exit)))
     (is (not (.exists (io/file core))))
     (is (str/includes? err "java/time/NoSuch") err))))

(deftest bake-script-refuses-relative-classpath
  (when-built
   (doseq [cp ["target/test-classes" (str classes ":rel/dir") (str classes ":")]]
     (let [{:keys [exit core err]} (bake cp)]
       (is (= 2 exit) cp)
       (is (not (.exists (io/file core))) cp)
       (is (str/includes? err "classpath entries must be absolute") err)))
   (is (str/includes? (:err (bake "target/test-classes")) "target/test-classes"))))

(deftest bake-script-failure-detector
  ;; No method has failed to compile on any classpath tried, so the classifier
  ;; of %compile-method's output is checked directly.
  (when-built
   (let [{:keys [exit err]} (sh (bake-script) "--self-test")]
     (is (zero? exit) err)
     (is (str/includes? err "bake: self-test ok") err))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk.bake-script-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
