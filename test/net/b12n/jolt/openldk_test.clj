(ns net.b12n.jolt.openldk-test
  "Against a real OpenLDK. Skips when bridge/build.sh has not been run, unless
  JOLT_OPENLDK_REQUIRE is set, which turns the skip into a failure for a gate
  that must not pass by skipping."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [net.b12n.jolt.openldk :as ldk]))

(def classes (or (System/getenv "JOLT_OPENLDK_TEST_CLASSES") "target/test-classes"))

(def have-build?
  (let [dist (ldk/dist-dir)]
    (cond
      (ldk/built? dist) true
      (System/getenv "JOLT_OPENLDK_REQUIRE")
      (throw (ex-info (str "no build in " dist " and JOLT_OPENLDK_REQUIRE is set") {:dist dist}))
      :else (do (println "  (skipping net.b12n.jolt.openldk-test: no build in" dist
                         "; run bb build)")
                false))))

(defmacro when-built [& body] `(when have-build? ~@body))

(defn- thrown [f] (try (f) nil (catch Exception e e)))

(deftest a-second-init-with-the-same-classpath-is-a-no-op
  (when-built
   (is (true? (ldk/init! {:classpath classes})))
   (is (true? (ldk/init! {:classpath classes})))
   (testing "OpenLDK sets its classpath once"
     (is (thrown? Exception (ldk/init! {:classpath "/elsewhere"}))))))

(deftest scalars-cross-both-ways
  (when-built
   (ldk/init! {:classpath classes})
   (is (= 42 (ldk/call-static "Fixture" "add" "(II)I" 40 2)))
   (is (= 9000000000 (ldk/call-static "Fixture" "mul" "(JJ)J" 3000000000 3)))
   (is (= 1.5 (ldk/call-static "Fixture" "half" "(D)D" 3.0)))
   (is (= 0.3333333432674408 (ldk/call-static "Fixture" "third" "(F)F" 1.0))
       "a float result is widened, not rounded to a prettier double")
   (is (true? (ldk/call-static "Fixture" "even" "(I)Z" 4)))
   (is (false? (ldk/call-static "Fixture" "even" "(I)Z" 3)))
   (is (= \b (ldk/call-static "Fixture" "next" "(C)C" \a)))
   (is (nil? (ldk/call-static "Fixture" "nothing" "()Ljava/lang/String;")))
   (is (Double/isNaN (ldk/call-static "Fixture" "nan" "()D")))
   (is (= 7 (ldk/call-static "Fixture" "boxed" "()Ljava/lang/Object;")) "a boxed Integer unboxes")))

(deftest strings-keep-every-character
  (when-built
   (ldk/init! {:classpath classes})
   (doseq [[in out] [["plain" "PLAIN"] ["q\"q\\" "Q\"Q\\"] ["two\nlines" "TWO\nLINES"]
                     ["héllo" "HÉLLO"] ["日本" "日本"] ["ÿ" "Ÿ"] ["😀x" "😀X"]]]
     (is (= out (ldk/call-static "Fixture" "upper" "(Ljava/lang/String;)Ljava/lang/String;" in))
         (pr-str in)))))

(deftest clojure-values-box-for-object-parameters
  (when-built
   (ldk/init! {:classpath classes})
   (let [describe #(ldk/call-static "Fixture" "describe" "(Ljava/lang/Object;)Ljava/lang/String;" %)]
     (is (= "java.lang.Long:5" (describe 5)))
     (is (= "java.lang.Double:2.5" (describe 2.5)))
     (is (= "java.lang.Boolean:true" (describe true)))
     (is (= "java.lang.String:s" (describe "s")))
     (is (= "null" (describe nil))))))

(deftest objects-live-behind-handles
  (when-built
   (ldk/init! {:classpath classes})
   (let [f (ldk/new-object "Fixture" "(I)V" 10)]
     (is (ldk/ref? f))
     (is (= "Fixture" (ldk/class-name f)))
     (is (= 11 (ldk/call f "bump" "()I")))
     (is (= 12 (ldk/call f "bump" "()I")))
     (is (= "Fixture(12)" (ldk/to-string f)) "toString dispatches to the override")
     (ldk/release! f)
     (testing "a released handle is gone, and releasing again is an error"
       (is (thrown? Exception (ldk/call f "bump" "()I")))
       (is (thrown? Exception (ldk/release! f)))))
   (testing "with-ref releases on the way out"
     (let [leaked (atom nil)]
       (ldk/with-ref [xs (ldk/new-object "java.util.ArrayList" "()V")]
         (reset! leaked xs)
         (is (true? (ldk/call xs "add" "(Ljava/lang/Object;)Z" "a")))
         (is (true? (ldk/call xs "add" "(Ljava/lang/Object;)Z" 2)))
         (is (= 2 (ldk/call xs "size" "()I")))
         (is (= "a" (ldk/call xs "get" "(I)Ljava/lang/Object;" 0)))
         (is (= 2 (ldk/call xs "get" "(I)Ljava/lang/Object;" 1)))
         (is (= "[a, 2]" (ldk/to-string xs))))
       (is (thrown? Exception (ldk/to-string @leaked)))))))

(deftest the-jdk-class-library-is-there
  (when-built
   (ldk/init! {:classpath classes})
   (is (= 1.4142135623730951 (ldk/call-static "java.lang.Math" "sqrt" "(D)D" 2.0)))
   (is (= 42 (ldk/call-static "java.lang.Integer" "parseInt" "(Ljava/lang/String;)I" "42")))
   (ldk/with-ref [m (ldk/new-object "java.util.HashMap" "()V")]
     (is (nil? (ldk/call m "put" "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;" "k" 1.5)))
     (is (= 1.5 (ldk/call m "get" "(Ljava/lang/Object;)Ljava/lang/Object;" "k"))))))

(deftest exceptions-become-ex-info
  (when-built
   (ldk/init! {:classpath classes})
   (let [e (thrown #(ldk/call-static "Fixture" "fail" "(Ljava/lang/String;)V" "x"))]
     (is (= "java.lang.IllegalStateException: boom: x" (ex-message e)))
     (is (= {:java/class "java.lang.IllegalStateException" :java/message "boom: x"
             :java/string "java.lang.IllegalStateException: boom: x"}
            (ex-data e))))
   (is (= "java.lang.NullPointerException"
          (:java/class (ex-data (thrown #(ldk/call-static "Fixture" "npe" "()V"))))))
   (is (= "java.lang.IndexOutOfBoundsException"
          (ldk/with-ref [xs (ldk/new-object "java.util.ArrayList" "()V")]
            (:java/class (ex-data (thrown #(ldk/call xs "get" "(I)Ljava/lang/Object;" 9)))))))
   (testing "what the bridge refuses names itself"
     (is (re-find #"class not found: Nope" (ex-message (thrown #(ldk/call-static "Nope" "x" "()V")))))
     (is (re-find #"takes 2 arguments, got 1" (ex-message (thrown #(ldk/call-static "Fixture" "add" "(II)I" 1)))))
     (is (:openldk/error (ex-data (thrown #(ldk/call-static "Fixture" "add" "(II)I" 99999999999 1))))
         "an int parameter refuses a value outside 32 bits rather than wrapping it"))))

(deftest awkward-characters-come-back-whole-or-say-why
  (when-built
    (ldk/init! {:classpath classes})
    (let [char-string #(ldk/call-static "java.lang.Character" "toString" "(I)Ljava/lang/String;" %)]
      (testing "a NUL in a returned string survives the C-string crossing"
        (is (= "\u0000" (char-string 0))))
      (testing "other control characters too"
        (is (= "A\tB\u0001" (ldk/call-static "Fixture" "upper" "(Ljava/lang/String;)Ljava/lang/String;" "a\tb\u0001"))))
      (testing "a lone surrogate, legal in Java and not in jolt, becomes U+FFFD"
        (is (= "\uFFFD" (char-string 0xD83D)))))
    (testing "half a surrogate pair as a char comes back as its integer code"
      (is (= 0xD83D (ldk/call-static "java.lang.Character" "highSurrogate" "(I)C" 0x1F600))))
    (testing "a code point past U+FFFF does not fit a Java char"
      (is (re-find #"does not fit a Java char"
                   (ex-message (thrown #(ldk/call-static "java.lang.Character" "toUpperCase" "(C)C" (char 0x1F600)))))))
    (testing "an integer boxed for an Object parameter has to fit a Long"
      (is (re-find #"does not fit a Java long"
                   (ex-message (thrown #(ldk/call-static "Fixture" "describe" "(Ljava/lang/Object;)Ljava/lang/String;"
                                                        9223372036854775808N)))))
      (is (= "java.lang.Long:9223372036854775807"
             (ldk/call-static "Fixture" "describe" "(Ljava/lang/Object;)Ljava/lang/String;" 9223372036854775807))))))

(deftest init-checks-what-openldk-would-exit-on
  ;; Pure checks, no build needed: OpenLDK reacts to a bad JAVA_HOME by
  ;; exiting the process, so init! has to catch these first.
  (let [dir (io/file (System/getProperty "java.io.tmpdir") (str "jolt-openldk-test-" (System/nanoTime)))
        release (io/file dir "release")]
    (io/make-parents release)
    (is (re-find #"not set" (ldk/jdk-problem nil)))
    (is (re-find #"no release file" (ldk/jdk-problem (str dir))))
    (spit release "JAVA_VERSION=\"21.0.9\"\n")
    (is (re-find #"not a JDK 25" (ldk/jdk-problem (str dir))))
    (spit release "IMPLEMENTOR=\"x\"\nJAVA_VERSION=\"25.0.2\"\n")
    (is (re-find #"neither jmods/ nor lib/modules" (ldk/jdk-problem (str dir))))
    (.mkdirs (io/file dir "jmods"))
    (is (nil? (ldk/jdk-problem (str dir))))
    (is (= [] (ldk/missing-classpath-entries (str dir ":" release))))
    (is (= ["/no/such/dir" "/no/such.jar"]
           (ldk/missing-classpath-entries (str dir ":/no/such/dir::/no/such.jar"))))))

(deftest vectors-become-java-arrays
  (when-built
    (ldk/init! {:classpath classes})
    (is (= 6 (ldk/call-static "Fixture" "sum" "([I)I" [1 2 3])))
    (is (= 0 (ldk/call-static "Fixture" "sum" "([I)I" [])) "an empty vector is an empty array")
    (is (= 9000000000 (ldk/call-static "Fixture" "total" "([J)J" [3000000000 6000000000])))
    (is (= 2.0 (ldk/call-static "Fixture" "avg" "([D)D" [1.0 3.0])))
    (is (= 2.0 (ldk/call-static "Fixture" "avg" "([D)D" [1 3])) "integers widen for a double[]")
    (is (= "a|é|null" (ldk/call-static "Fixture" "join" "([Ljava/lang/String;)Ljava/lang/String;" ["a" "é" nil])))
    (is (= 2 (ldk/call-static "Fixture" "trues" "([Z)I" [true false true])))
    (is (= \é (ldk/call-static "Fixture" "last" "([C)C" [\h \é])))
    (is (= -2 (ldk/call-static "Fixture" "byteSum" "([B)I" [-1 127 -128])))
    (is (= 10 (ldk/call-static "Fixture" "deep" "([[I)I" [[1 2] [3] [4]])) "nested vectors make int[][]")
    (testing "elements are checked against the component type"
      (is (re-find #"does not fit a Java byte"
                   (ex-message (thrown #(ldk/call-static "Fixture" "byteSum" "([B)I" [200])))))
      (is (re-find #"a vector for parameter I"
                   (ex-message (thrown #(ldk/call-static "Fixture" "add" "(II)I" [1] 2))))))))

(deftest arguments-must-fit-their-parameter-type
  (when-built
    (ldk/init! {:classpath classes})
    (let [msg #(ex-message (thrown %))]
      (testing "array elements are checked against the component, and the error names the element"
        (is (re-find #"element 0 of the vector for \[Ljava/lang/String;: an integer cannot be passed for parameter Ljava/lang/String;"
                     (msg #(ldk/call-static "Fixture" "join" "([Ljava/lang/String;)Ljava/lang/String;" [1 2]))))
        (is (re-find #"element 1 of the vector for \[\[I: an integer for array parameter \[I"
                     (msg #(ldk/call-static "Fixture" "deep" "([[I)I" [[1 2] 3])))))
      (testing "a scalar or a string is not an array"
        (is (re-find #"an integer for array parameter \[I" (msg #(ldk/call-static "Fixture" "sum" "([I)I" 5))))
        (is (re-find #"a string for array parameter \[I" (msg #(ldk/call-static "Fixture" "sum" "([I)I" "x"))))
        (ldk/with-ref [xs (ldk/new-object "java.util.ArrayList" "()V")]
          (is (re-find #"a java.util.ArrayList for array parameter \[I"
                       (msg #(ldk/call-static "Fixture" "sum" "([I)I" xs))))))
      (testing "a vector inside an Object[] has to be built first, and the error says how"
        (is (re-find #"element 1 .*a vector cannot be passed for parameter Ljava/lang/Object;; build the array with new-array"
                     (msg #(ldk/call-static "java.util.Arrays" "toString" "([Ljava/lang/Object;)Ljava/lang/String;" ["a" [1]])))))
      (testing "a scalar boxes only where its box fits"
        (is (= "java.lang.Integer:5" (ldk/call-static "Fixture" "boxedInt" "(Ljava/lang/Integer;)Ljava/lang/String;" 5)))
        (is (re-find #"does not fit a Java int"
                     (msg #(ldk/call-static "Fixture" "boxedInt" "(Ljava/lang/Integer;)Ljava/lang/String;" 3000000000))))
        (is (re-find #"a string cannot be passed for parameter Ljava/lang/Integer;"
                     (msg #(ldk/call-static "Fixture" "boxedInt" "(Ljava/lang/Integer;)Ljava/lang/String;" "5"))))
        (is (= "java.lang.Character:a"
               (ldk/call-static "Fixture" "describe" "(Ljava/lang/Object;)Ljava/lang/String;" \a))))
      (testing "descriptors are parsed strictly"
        (doseq [bad ["V" "int" "X" "" "L" "[" "[Q" "Ljava/lang/String" "Ljava.lang.String;" "II"]]
          (is (re-find #"bad type descriptor" (msg #(ldk/new-array bad []))) (pr-str bad)))
        (is (re-find #"malformed method descriptor" (msg #(ldk/call-static "Fixture" "add" "(II" 1 2))))
        (is (re-find #"malformed method descriptor"
                     (msg #(ldk/call-static "Fixture" "join" "([Ljava.lang.String;)Ljava/lang/String;" ["a"]))))
        (is (re-find #"bad type descriptor" (msg #(ldk/call-static "Fixture" "add" "(II)Q" 1 2))))))
    (testing "the bridge is still healthy after all of that"
      (is (= 6 (ldk/call-static "Fixture" "sum" "([I)I" [1 2 3]))))))

(deftest java-arrays-come-back-as-handles-and-read-as-vectors
  (when-built
    (ldk/init! {:classpath classes})
    (let [read (fn [method desc]
                 (ldk/with-ref [a (ldk/call-static "Fixture" method desc)]
                   [(ldk/class-name a) (ldk/array-length a) (ldk/array->vec a)]))]
      (is (= ["[B" 4 [-1 0 127 -128]] (read "bytes" "()[B")) "bytes are signed, as in Java")
      (is (= ["[Z" 3 [true false true]] (read "flags" "()[Z")))
      (is (= ["[C" 3 [\h \é \y]] (read "letters" "()[C")))
      (is (= ["[F" 2 [0.5 -1.25]] (read "floats" "()[F")))
      (is (= ["[Ljava.lang.String;" 3 ["ada" nil "é"]] (read "names" "()[Ljava/lang/String;")))
      (is (= ["[I" 0 []] (read "none" "()[I"))))
    (testing "Object[] elements convert as returned values do; other objects are handles"
      (ldk/with-ref [a (ldk/call-static "Fixture" "mixed" "()[Ljava/lang/Object;")]
        (let [[s i d z n f] (ldk/array->vec a)]
          (is (= ["s" 1 2.5 true nil] [s i d z n]))
          (is (ldk/ref? f))
          (is (= "Fixture(3)" (ldk/to-string f)))
          (ldk/release! f))))
    (testing "a nested array's rows are handles of their own"
      (ldk/with-ref [m (ldk/call-static "Fixture" "matrix" "()[[I")]
        (is (= "[[I" (ldk/class-name m)))
        (let [rows (ldk/array->vec m)]
          (is (= [[1 2] [3]] (mapv ldk/array->vec rows)))
          (run! ldk/release! rows))))
    (testing "array->vec refuses a handle that is not an array"
      (ldk/with-ref [xs (ldk/new-object "java.util.ArrayList" "()V")]
        (is (re-find #"not an array" (ex-message (thrown #(ldk/array->vec xs)))))))))

(deftest new-array-makes-an-array-java-can-change
  (when-built
    (ldk/init! {:classpath classes})
    (ldk/with-ref [xs (ldk/new-array "I" [0 0 0 0])]
      (is (= "[I" (ldk/class-name xs)))
      (is (nil? (ldk/call-static "Fixture" "squares" "([I)V" xs)))
      (is (= [0 1 4 9] (ldk/array->vec xs)) "Java wrote into the same array"))
    (ldk/with-ref [xs (ldk/new-array "I" [3 1 2])]
      (ldk/call-static "java.util.Arrays" "sort" "([I)V" xs)
      (is (= [1 2 3] (ldk/array->vec xs)))
      (is (= "[1, 2, 3]" (ldk/call-static "java.util.Arrays" "toString" "([I)Ljava/lang/String;" xs))))
    (ldk/with-ref [ss (ldk/new-array "Ljava/lang/String;" ["b" "a"])]
      (ldk/call-static "java.util.Arrays" "sort" "([Ljava/lang/Object;)V" ss)
      (is (= ["a" "b"] (ldk/array->vec ss))))
    (testing "the JDK's own arrays"
      (ldk/with-ref [parts (ldk/with-ref [s (ldk/new-object "java.lang.String" "(Ljava/lang/String;)V" "a,b,c")]
                             (ldk/call s "split" "(Ljava/lang/String;)[Ljava/lang/String;" ","))]
        (is (= ["a" "b" "c"] (ldk/array->vec parts)))))))

(deftest another-thread-can-call
  (when-built
   (ldk/init! {:classpath classes})
   (is (= 3 @(future (ldk/call-static "Fixture" "add" "(II)I" 1 2))))))

(deftest main-runs
  (when-built
   (ldk/init! {:classpath classes})
   (is (nil? (ldk/run-main "Fixture" "a" "b")))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
