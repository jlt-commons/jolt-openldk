;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.patches-test
  "The OpenLDK fixes bridge.lisp carries, each checked in a jolt subprocess
  under a watchdog, because two of them are about calls that never returned.
  Skips when bridge/build.sh has not been run, unless JOLT_OPENLDK_REQUIRE is
  set."
  (:require [babashka.process :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [net.b12n.jolt.openldk :as ldk])
  (:import [java.nio.file Files OpenOption]
           [java.nio.file.attribute FileAttribute]))

(def have-build?
  (let [dist (ldk/dist-dir)]
    (cond
      (ldk/built? dist) true
      (System/getenv "JOLT_OPENLDK_REQUIRE")
      (throw (ex-info (str "no build in " dist " and JOLT_OPENLDK_REQUIRE is set") {:dist dist}))
      :else (do (println "  (skipping net.b12n.jolt.openldk.patches-test: no build in" dist
                         "; run bb build)")
                false))))

(defmacro when-built [& body] `(when have-build? ~@body))

(def project (.getAbsolutePath (io/file "test/fixtures/patches-project")))

;; Each check finishes in a few seconds. Two minutes leaves room for a slow CI
;; machine and still catches a loop.
(def timeout-ms 120000)

(defn- temp-dir [prefix]
  (doto (java.io.File/createTempFile prefix "") .delete .mkdirs))

(defn- delete-tree
  "Deletes F and what's under it. A symlink is deleted, never followed, which
  matters here: the fake JAVA_HOME is mostly links into the real JDK."
  [f]
  (let [f (io/file f)
        path (.toPath f)]
    (when (and (not (Files/isSymbolicLink path)) (.isDirectory f))
      (run! delete-tree (.listFiles f)))
    (Files/deleteIfExists path)))

(defn- write-bytes! [f bs]
  (.mkdirs (.getParentFile (io/file f)))
  (Files/write (.toPath (io/file f)) bs (into-array OpenOption [])))

(defn- run-check-ns
  "Runs the fixture namespace CHECK-NS with ARGS in a jolt subprocess, ENV
  added to its environment. Returns the map it printed, or {:timed-out true}
  after killing a process that ran past timeout-ms. It's killed outright,
  since a SIGTERM didn't stop the hang it checks for within two seconds."
  [check-ns env & args]
  (let [proc (apply p/process {:dir project :extra-env env :out :string :err :string}
                    "jolt" "-M" "-m" check-ns args)
        r (deref proc timeout-ms ::timeout)]
    (if (= r ::timeout)
      (do (.destroyForcibly (:proc proc)) {:timed-out true})
      (or (some->> (str/split-lines (:out r)) (filter #(str/starts-with? % "{")) last edn/read-string)
          {:no-result (select-keys r [:exit :out :err])}))))

(defn- run-check [env & args] (apply run-check-ns "patchcheck" env args))

;;; --- a failed JIT compile of an instance method ------------------------------

(defn- u2 [x] [(bit-and (bit-shift-right x 8) 0xff) (bit-and x 0xff)])
(defn- u4 [x] (concat (u2 (bit-shift-right x 16)) (u2 (bit-and x 0xffff))))
(defn- utf8 [s] (let [b (.getBytes s "UTF-8")] (concat [1] (u2 (count b)) (map #(bit-and % 0xff) b))))

(defn- jsr-class
  "The bytes of p.Jsr, a version-49 class whose constructor calls the instance
  method m(), where m() uses jsr and ret. OpenLDK translates neither, so
  compiling m() fails on any JDK, which is the failure the stub fix is about."
  []
  (let [cp [(utf8 "p/Jsr") (concat [7] (u2 1)) (utf8 "java/lang/Object") (concat [7] (u2 3))
            (utf8 "<init>") (utf8 "()V") (concat [12] (u2 5) (u2 6)) (concat [10] (u2 4) (u2 7))
            (utf8 "m") (concat [12] (u2 9) (u2 6)) (concat [10] (u2 2) (u2 10)) (utf8 "Code")]
        code (fn [max-stack max-locals bc]
               (let [body (concat (u2 max-stack) (u2 max-locals) (u4 (count bc)) bc (u2 0) (u2 0))]
                 (concat (u2 12) (u4 (count body)) body)))
        method (fn [flags name-index code-attr] (concat (u2 flags) (u2 name-index) (u2 6) (u2 1) code-attr))]
    (byte-array
     (map unchecked-byte
          (concat (u4 0xCAFEBABE) (u2 0) (u2 49) (u2 (inc (count cp))) (apply concat cp)
                  (u2 0x21) (u2 2) (u2 4) (u2 0) (u2 0) (u2 2)
                  ;; public Jsr() { super(); m(); }
                  (method 0x0001 5 (code 1 1 [0x2a 0xb7 0 8 0x2a 0xb6 0 11 0xb1]))
                  ;; void m() { jsr L; return; L: astore_1; ret 1 }
                  (method 0x0000 9 (code 1 2 [0xa8 0 4 0xb1 0x4c 0xa9 1]))
                  (u2 0))))))

(deftest failed-compile-of-an-instance-method-throws
  ;; Before the fix this never returned: the error stub went onto the wrong
  ;; generic function and the lazy compile stub kept calling itself.
  (when-built
   (let [dir (temp-dir "jsr")]
     (try
       (write-bytes! (io/file dir "p" "Jsr.class") (jsr-class))
       (let [r (run-check {} (str dir) "p.Jsr" "()V")]
         (is (not (:timed-out r)) "the constructor call returns")
         (is (str/includes? (str (:error r)) "JIT compilation failed for p/Jsr.m()V") (pr-str r)))
       (finally (delete-tree dir))))))

;;; --- a jmod whose zip offsets count from the start of the file --------------

(defn- u16le [b i] (bit-or (bit-and (aget b i) 0xff) (bit-shift-left (bit-and (aget b (inc i)) 0xff) 8)))

(defn- u32le [b i]
  (reduce (fn [acc k] (bit-or acc (bit-shift-left (bit-and (aget b (+ i k)) 0xff) (* 8 k)))) 0 (range 4)))

(defn- put-u32le! [b i v]
  (dotimes [k 4] (aset b (+ i k) (unchecked-byte (bit-and (bit-shift-right v (* 8 k)) 0xff)))))

(defn- signature? [b i sig]
  (and (<= (+ i 4) (alength b))
       (every? (fn [k] (= (nth sig k) (bit-and (aget b (+ i k)) 0xff))) (range 4))))

(defn- file-absolute
  "B, a jmod, rewritten so its zip offsets count from the start of the file
  instead of from after the 4-byte JM header. That is how 30 of the 84 jmods in
  GraalVM CE 25.0.1 are laid out. B unchanged when it is already."
  [b]
  (let [eocd (first (filter #(signature? b % [0x50 0x4b 5 6]) (range (- (alength b) 22) -1 -1)))
        cd (u32le b (+ eocd 16))
        entries (u16le b (+ eocd 10))]
    (if (signature? b cd [0x50 0x4b 1 2])
      b
      (let [b (aclone b)]
        (loop [i (+ cd 4) k 0]
          (when (< k entries)
            (put-u32le! b (+ i 42) (+ 4 (u32le b (+ i 42))))
            (recur (+ i 46 (u16le b (+ i 28)) (u16le b (+ i 30)) (u16le b (+ i 32))) (inc k))))
        (put-u32le! b (+ eocd 16) (+ cd 4))
        b))))

(defn- fake-java-home
  "A JAVA_HOME of symlinks into REAL, except that jmods/java.prefs.jmod is a
  file-absolute copy."
  [real]
  (let [home (temp-dir "jdk")
        link! (fn [at target]
                (Files/createSymbolicLink (.toPath (io/file at)) (.toPath (io/file target))
                                          (into-array FileAttribute [])))]
    (doseq [f (.listFiles (io/file real)) :when (not= "jmods" (.getName f))]
      (link! (io/file home (.getName f)) f))
    (doseq [f (.listFiles (io/file real "jmods"))]
      (if (= "java.prefs.jmod" (.getName f))
        (write-bytes! (io/file home "jmods" (.getName f)) (file-absolute (Files/readAllBytes (.toPath f))))
        (do (.mkdirs (io/file home "jmods")) (link! (io/file home "jmods" (.getName f)) f))))
    home))

(deftest a-file-absolute-jmod-loads
  ;; OpenLDK reads the JDK from jmods/ when there is one. Temurin 25 has none,
  ;; so this skips in CI and runs against a JDK that ships them.
  (when-built
   (let [real (System/getenv "JAVA_HOME")]
     (if-not (.isFile (io/file real "jmods" "java.prefs.jmod"))
       (println "  (skipping the file-absolute jmod check:" real "has no jmods/java.prefs.jmod)")
       (let [home (fake-java-home real)]
         (try
           (is (= {:ok "java.util.prefs.BackingStoreException: m"}
                  (run-check {"JAVA_HOME" (str home)} (str home)
                             "java.util.prefs.BackingStoreException" "(Ljava/lang/String;)V" "m")))
           (finally (delete-tree home))))))))

(deftest four-threads-making-the-same-first-call-all-finish
  ;; OpenLDK woke one waiter when a compile finished, on a condition variable
  ;; every method shares, so the third thread waiting on a method slept
  ;; forever. 2997000 is (reduce + (for [t (range 4) k (range 1000)] (* t k))).
  (when-built
   (let [classes (.getAbsolutePath (io/file (or (System/getenv "JOLT_OPENLDK_TEST_CLASSES") "target/test-classes")))
         r (run-check-ns "racecheck" {} classes "4")]
     (is (not (:timed-out r)) "all four threads finish")
     (is (= {:ok 2997000} r) (pr-str r)))))

(deftest a-call-before-init-says-so
  (when-built
   (is (= {:error "jolt-openldk: call init! first"}
          (run-check-ns "initcheck" {} "before-init" "x")))))

(deftest a-call-during-init-waits-for-it
  ;; initcheck's future calls Java 50 ms in, while init! (about 260 ms in a
  ;; subprocess, measured 2026-10-17) is still starting.
  (when-built
   (let [classes (.getAbsolutePath (io/file (or (System/getenv "JOLT_OPENLDK_TEST_CLASSES") "target/test-classes")))]
     (is (= {:ok 4} (run-check-ns "initcheck" {} "during-init" classes))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk.patches-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
