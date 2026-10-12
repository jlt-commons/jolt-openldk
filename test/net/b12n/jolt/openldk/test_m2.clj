;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.test-m2
  "Shared helper for the tests that read the seeded Maven repository `bb test`
  puts under target/test-m2. They need JOLT_MAVEN_REPOSITORY to point at it,
  and jolt reads that variable at call time, so it can't be set in-process."
  (:require [clojure.java.io :as io]))

(defn seeded-jar
  "Absolute path of the seeded net.b12n.test/fixture jar at `version`."
  [version]
  (str (System/getenv "JOLT_MAVEN_REPOSITORY")
       "/net/b12n/test/fixture/" version "/fixture-" version ".jar"))

(defn require-seeded!
  "Throws before any test runs when JOLT_MAVEN_REPOSITORY is unset or blank, or
  the seeded 1.0 jar is missing there."
  []
  (let [repo (System/getenv "JOLT_MAVEN_REPOSITORY")]
    (when (or (nil? repo) (= "" (.trim ^String repo))
              (not (.exists (io/file (seeded-jar "1.0")))))
      (throw (ex-info "JOLT_MAVEN_REPOSITORY must point at the seeded test repository. Run the tests through `bb test`, or run it once and set JOLT_MAVEN_REPOSITORY=$PWD/target/test-m2 (an absolute path)."
                      {:JOLT_MAVEN_REPOSITORY repo})))))
