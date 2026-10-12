;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.manifest-test
  "Bake manifest: fingerprints and staleness reports. These tests need no build."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is run-tests]]
            [net.b12n.jolt.openldk.manifest :as manifest]))

(defn- temp-dir []
  (str (java.nio.file.Files/createTempDirectory
        "jo" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- put! [dir path content]
  (let [f (io/file dir path)]
    (io/make-parents f)
    (spit f content)
    (.getPath f)))

(def dist {:sbcl "2.4.0" :openldk "abc" :java-home "/jdk" :built "t1" :bake-script "/x"})

(defn- inputs [entries]
  {:entries entries :warm ["a.B"] :dist-manifest dist})

(defn- baked-of [entries]
  (manifest/build (assoc (inputs entries) :counts {:methods 1})))

(defn- fixture []
  (let [dir (temp-dir)
        jar (put! dir "lib/a.jar" "abcd")
        cls (str dir "/classes")]
    (put! dir "classes/p/C.class" "xx")
    {:dir dir :jar jar :cls cls}))

(deftest fingerprint-jar-and-dir
  (let [{:keys [dir jar cls]} (fixture)
        [j d m] (manifest/fingerprint [jar cls (str dir "/nope")])]
    (is (= 4 (:size j)))
    (is (integer? (:crc32 j)))
    (is (str/ends-with? (:path j) "a.jar"))
    (is (= 2 (get-in d [:classes "p/C.class" :size])))
    (is (integer? (get-in d [:classes "p/C.class" :crc32])))
    (is (true? (:missing m)))))

(deftest build-shape
  (let [{:keys [jar]} (fixture)
        b (baked-of [jar])]
    (is (= {:sbcl "2.4.0" :openldk "abc" :java-home "/jdk"} (:shared-build b)))
    (is (= ["a.B"] (:warm b)))
    (is (= {:methods 1} (:counts b)))))

(deftest build-records-a-precomputed-fingerprint
  (let [{:keys [jar]} (fixture)
        fp [{:path "/earlier" :size 1 :crc32 2}]]
    (is (= fp (:classpath-entries (manifest/build (assoc (inputs [jar]) :fingerprint fp)))))
    (is (= (manifest/fingerprint [jar]) (:classpath-entries (baked-of [jar]))))))

(deftest validate-ok-when-nothing-changed
  (let [{:keys [dir jar cls]} (fixture)
        es [jar cls (str dir "/nope")]]
    (is (= [] (manifest/problems (baked-of es) (inputs es))))
    (is (= [] (manifest/problems (baked-of es) (assoc-in (inputs es) [:dist-manifest :built] "t2"))))))

(deftest validate-refuses-changed-jar-content
  (let [{:keys [jar]} (fixture)
        b (baked-of [jar])]
    (spit jar "abce")
    (let [ps (manifest/problems b (inputs [jar]))]
      (is (= 1 (count ps)))
      (is (str/includes? (first ps) "a.jar"))
      (is (str/includes? (first ps) "content")))))

(deftest validate-refuses-added-class-in-dir
  (let [{:keys [dir cls]} (fixture)
        b (baked-of [cls])]
    (put! dir "classes/p/D.class" "y")
    (let [ps (manifest/problems b (inputs [cls]))]
      (is (some #(and (str/includes? % cls) (str/includes? % "p/D.class") (str/includes? % "added")) ps)))
    (.delete (io/file dir "classes/p/C.class"))
    (let [ps (manifest/problems b (inputs [cls]))]
      (is (some #(and (str/includes? % "p/C.class") (str/includes? % "removed")) ps)))))

(deftest validate-refuses-changed-classpath
  (let [{:keys [dir jar]} (fixture)
        jar2 (put! dir "lib2/a.jar" "abcd")
        ps (manifest/problems (baked-of [jar]) (inputs [jar2]))]
    (is (= 1 (count ps)))
    (is (str/includes? (first ps) "classpath changed"))
    (is (str/includes? (first ps) "lib2/a.jar"))))

(deftest validate-refuses-changed-warm-list
  (let [{:keys [jar]} (fixture)
        ps (manifest/problems (baked-of [jar]) (assoc (inputs [jar]) :warm ["a.B" "c.D"]))]
    (is (= 1 (count ps)))
    (is (str/includes? (first ps) "warm list"))
    (is (str/includes? (first ps) "c.D"))))

(deftest validate-refuses-changed-shared-build
  (let [{:keys [jar]} (fixture)
        ps (manifest/problems (baked-of [jar])
                              (assoc-in (inputs [jar]) [:dist-manifest :openldk] "def"))]
    (is (= 1 (count ps)))
    (is (str/includes? (first ps) ":openldk"))
    (is (str/includes? (first ps) "abc"))
    (is (str/includes? (first ps) "def"))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk.manifest-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
