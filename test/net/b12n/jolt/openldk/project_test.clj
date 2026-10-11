;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.project-test
  "Classpath resolution from deps.edn and jvm/. These tests need no build."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests]]
            [net.b12n.jolt.openldk.project :as project]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "jo" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- put! [dir path content]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f content)))

(deftest explicit-wins-over-everything
  (let [dir (temp-dir)]
    (put! dir "deps.edn" "{:jolt-openldk/classpath [\"lib/k.jar\"]}")
    (put! dir "jvm/a.jar" "")
    (is (= {:classpath "x" :entries ["x"] :source :explicit}
           (project/resolve-classpath dir ["x"])))))

(deftest explicit-string-splits-on-colon
  (let [dir (temp-dir)]
    (is (= {:classpath "a:b" :entries ["a" "b"] :source :explicit}
           (project/resolve-classpath dir "a:b")))))

(deftest explicit-ignores-broken-deps-edn
  (let [dir (temp-dir)]
    (put! dir "deps.edn" "{:a")
    (is (= :explicit (:source (project/resolve-classpath dir ["x"]))))))

(deftest deps-edn-key-wins-over-jvm-dir
  (let [dir (temp-dir)]
    (put! dir "deps.edn" "{:jolt-openldk/classpath [\"lib/k.jar\"]}")
    (put! dir "jvm/a.jar" "")
    (let [r (project/resolve-classpath dir nil)]
      (is (= :deps-edn (:source r)))
      (is (= [(str dir "/lib/k.jar")] (:entries r))))))

(deftest empty-key-vector-overrides-jvm-dir
  (let [dir (temp-dir)]
    (put! dir "deps.edn" "{:jolt-openldk/classpath []}")
    (put! dir "jvm/a.jar" "")
    (is (= {:classpath "" :entries [] :source :deps-edn}
           (project/resolve-classpath dir nil)))))

(deftest jvm-dir-used-when-deps-edn-has-no-key
  (let [dir (temp-dir)]
    (put! dir "deps.edn" "{:paths [\"src\"]}")
    (put! dir "jvm/b.jar" "")
    (put! dir "jvm/a.jar" "")
    (let [r (project/resolve-classpath dir nil)]
      (is (= :jvm-dir (:source r)))
      (is (= [(str dir "/jvm/a.jar") (str dir "/jvm/b.jar")] (:entries r)))
      (is (= (str dir "/jvm/a.jar:" dir "/jvm/b.jar") (:classpath r))))))

(deftest jvm-dir-with-classes-is-also-a-class-dir
  (let [dir (temp-dir)]
    (put! dir "jvm/a.jar" "")
    (put! dir "jvm/p/C.class" "")
    (is (= [(str dir "/jvm/a.jar") (str dir "/jvm")]
           (:entries (project/resolve-classpath dir nil))))))

(deftest no-deps-edn-is-fine
  (let [dir (temp-dir)]
    (put! dir "jvm/a.jar" "")
    (is (= :jvm-dir (:source (project/resolve-classpath dir nil))))))

(deftest nothing-declared-is-dot
  (is (= {:classpath "." :entries ["."] :source :default}
         (project/resolve-classpath (temp-dir) nil))))

(deftest unreadable-deps-edn-throws-with-path
  (let [dir (temp-dir)]
    (put! dir "deps.edn" "{:a")
    (is (thrown-with-msg? Exception #"deps.edn" (project/resolve-classpath dir nil)))))

(deftest non-vector-key-throws
  (let [dir (temp-dir)]
    (put! dir "deps.edn" "{:jolt-openldk/classpath \"lib/k.jar\"}")
    (is (thrown-with-msg? Exception #":jolt-openldk/classpath"
                          (project/resolve-classpath dir nil)))))

(deftest warm-classes-default-empty
  (let [dir (temp-dir) dir2 (temp-dir)]
    (is (= [] (project/warm-classes dir)))
    (put! dir2 "deps.edn" "{:jolt-openldk/warm [\"java.time.LocalDate\"]}")
    (is (= ["java.time.LocalDate"] (project/warm-classes dir2)))))

(deftest non-vector-warm-throws
  (let [dir (temp-dir) dir2 (temp-dir)]
    (put! dir "deps.edn" "{:jolt-openldk/warm \"java.time.LocalDate\"}")
    (is (thrown-with-msg? Exception #":jolt-openldk/warm.*deps.edn" (project/warm-classes dir)))
    (put! dir2 "deps.edn" "{:jolt-openldk/warm [\"java.time.LocalDate\" 3]}")
    (is (thrown-with-msg? Exception #":jolt-openldk/warm.*deps.edn" (project/warm-classes dir2)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk.project-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
