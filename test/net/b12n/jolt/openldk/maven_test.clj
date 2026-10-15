;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.maven-test
  "Maven coordinates in deps.edn: shape checks and resolution to jars. These
  tests need no build. The last one reads the seeded repository that `bb test`
  puts under target/test-m2 and points JOLT_MAVEN_REPOSITORY at."
  (:require [clojure.test :refer [deftest is run-tests]]
            [net.b12n.jolt.openldk.maven :as maven]
            [net.b12n.jolt.openldk.test-m2 :as test-m2]))

(deftest validate-accepts-nil-and-good-maps
  (doseq [v [nil {} '{net.b12n.test/fixture {:mvn/version "1.0"}}
             '{a/b {:mvn/version "1" :exclusions [c/d e/f]}}
             '{a/b {:mvn/version "1" :exclusions []}}]]
    (is (= v (maven/validate-deps v)))))

(deftest validate-rejects-bad-shapes
  (doseq [v [[] "x"
             '{fixture {:mvn/version "1.0"}}
             '{a/b {:mvn/version 1}}
             '{a/b {:mvn/version " "}}
             '{a/b {}}
             '{a/b {:mvn/version "1" :exclusions c/d}}
             '{a/b {:mvn/version "1" :exclusions [d]}}
             '{a/b {:mvn/version "1" :exclusions ["c/d"]}}
             '{a/b {:mvn/version "1" :exclusions (c/d)}}
             '{a/b {:exclusions [c/d]}}
             '{a/b {:git/url "u" :git/sha "s"}}
             '{a/b "1.0"}]]
    (let [e (try (maven/validate-deps v) nil (catch Exception e e))]
      (is (some? e) (pr-str v))
      (when e
        (is (= :jolt-openldk/deps (:key (ex-data e))) (pr-str v))
        (is (re-find #":jolt-openldk/deps" (ex-message e)) (pr-str v))))))

(deftest resolve-empty-calls-nothing
  (is (= {:jars [] :libs {}}
         (maven/resolve-jars "/r" {} (fn [& _] (throw (ex-info "called" {})))))))

(deftest resolve-passes-deps-and-root
  (let [seen (atom nil)
        deps '{a/b {:mvn/version "1"}}
        stub (fn [d r opts] (reset! seen [d r opts])
               {:roots ["/m2/a.jar"] :libs '{a/b {:mvn/version "1"}}})]
    (is (= {:jars ["/m2/a.jar"] :libs '{a/b {:mvn/version "1"}}}
           (maven/resolve-jars "/r" deps stub)))
    (is (= [deps "/r" {:jvm-deps? true}] @seen)
        "a Java jar's own dependencies are asked for")))

(deftest resolve-rejects-a-non-jar-root
  (let [e (try (maven/resolve-jars "/r" '{a/b {:mvn/version "1"}}
                                   (fn [_ _ _] {:roots ["/m2/src"] :libs {}}))
               nil (catch Exception e e))]
    (is (some? e))
    (is (re-find #"not a jar" (ex-message e)))
    (is (re-find #"/m2/src" (ex-message e)))))

(deftest resolve-wraps-unresolved
  (let [unresolved [{:coord 'no.such/thing :version "1.0" :detail "not found"}]
        cause (ex-info "Error building classpath. The following artifacts could not be resolved: x"
                       {:unresolved unresolved})
        e (try (maven/resolve-jars "/r" '{no.such/thing {:mvn/version "1.0"}}
                                   (fn [_ _ _] (throw cause)))
               nil (catch Exception e e))]
    (is (some? e))
    (is (.startsWith ^String (ex-message e)
                     "jolt-openldk: cannot resolve :jolt-openldk/deps: Error building classpath"))
    (is (= unresolved (:unresolved (ex-data e))))
    (is (identical? cause (ex-cause e)))))

(deftest resolve-other-errors-propagate
  (let [boom (ex-info "boom" {})
        e (try (maven/resolve-jars "/r" '{a/b {:mvn/version "1"}}
                                   (fn [_ _ _] (throw boom)))
               nil (catch Exception e e))]
    (is (identical? boom e))))

(deftest resolve-without-jolt-deps-var
  (let [e (with-redefs [requiring-resolve (fn [_] nil)]
            (try (maven/resolve-jars "/r" '{a/b {:mvn/version "1"}})
                 nil (catch Exception e e)))]
    (is (some? e))
    (is (= "jolt-openldk: :jolt-openldk/deps needs jolt.deps/resolve-deps, which this jolt doesn't have. Use jolt 0.8.22 or later."
           (ex-message e)))
    (is (= {:key :jolt-openldk/deps} (ex-data e)))))

(deftest resolve-real-seeded-repo
  (let [r (maven/resolve-jars "." '{net.b12n.test/fixture {:mvn/version "1.0"}})]
    (is (= [(test-m2/seeded-jar "1.0")]
           (:jars r)))
    (is (= '{net.b12n.test/fixture {:mvn/version "1.0"}} (:libs r)))))

(deftest resolve-real-transitive-and-excluded
  (let [r (maven/resolve-jars "." '{net.b12n.test/app {:mvn/version "1.0"}})]
    (is (= #{(test-m2/seeded-jar "1.0")
             (str (System/getenv "JOLT_MAVEN_REPOSITORY") "/net/b12n/test/app/1.0/app-1.0.jar")}
           (set (:jars r)))
        "app's POM brings fixture in")
    (is (= #{'net.b12n.test/app 'net.b12n.test/fixture} (set (keys (:libs r))))))
  (let [r (maven/resolve-jars "." '{net.b12n.test/app {:mvn/version "1.0"
                                                       :exclusions [net.b12n.test/fixture]}})]
    (is (= [(str (System/getenv "JOLT_MAVEN_REPOSITORY") "/net/b12n/test/app/1.0/app-1.0.jar")]
           (:jars r))
        "an exclusion keeps it out")))

(defn -main [& _]
  (test-m2/require-seeded!)
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk.maven-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
