;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk-test
  "Against a real OpenLDK. Skips when bridge/build.sh has not been run, unless
  JOLT_OPENLDK_REQUIRE is set, which turns the skip into a failure for a gate
  that must not pass by skipping."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is run-tests testing]]
            [net.b12n.jolt.openldk :as ldk]
            [net.b12n.jolt.openldk.wire :as wire]))

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

(deftest java-concatenation-of-non-latin1-strings
  ;; Threw a NullPointerException that escaped catch (Throwable) until
  ;; OpenLDK 701609f (atgreen/openldk#12, #13).
  (when-built
    (ldk/init! {:classpath classes})
    (doseq [[in out] [["é✓" "É✓!"] ["héllo \"q\" ✓" "HÉLLO \"Q\" ✓!"] ["a✓" "A✓!"] ["😀" "😀!"]]]
      (is (= out (ldk/call-static "Fixture" "shout" "(Ljava/lang/String;)Ljava/lang/String;" in))
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

(deftest javaref-arguments-must-fit-their-parameter-class
  (when-built
    (ldk/init! {:classpath classes})
    (let [msg #(ex-message (thrown %))]
      (ldk/with-ref [xs (ldk/new-object "java.util.ArrayList" "()V")
                     strs (ldk/new-array "Ljava/lang/String;" ["a" "b"])
                     ints (ldk/new-array "I" [1 2])
                     r (ldk/implement "java.lang.Runnable" (fn [] nil))]
        (testing "an object of the wrong class is refused before Java runs"
          (is (re-find #"a java.util.ArrayList for parameter Ljava/lang/String;"
                       (msg #(ldk/call-static "Fixture" "upper" "(Ljava/lang/String;)Ljava/lang/String;" xs))))
          (is (:openldk/error (ex-data (thrown #(ldk/call-static "Fixture" "upper" "(Ljava/lang/String;)Ljava/lang/String;" xs)))))
          (is (re-find #"a jolt-openldk.Proxy\S* for parameter Ljava/util/Iterator;"
                       (msg #(ldk/call-static "Fixture" "drain" "(Ljava/util/Iterator;)Ljava/lang/String;" r))))
          (is (re-find #"for parameter Ljava/lang/Runnable;"
                       (msg #(ldk/call-static "Fixture" "run" "(Ljava/lang/Runnable;)V" xs)))))
        (testing "superclasses and interfaces fit"
          (is (= "[]" (ldk/call-static "java.lang.String" "valueOf" "(Ljava/lang/Object;)Ljava/lang/String;" xs)))
          (ldk/with-ref [c (ldk/call-static "java.util.Collections" "unmodifiableCollection"
                                            "(Ljava/util/Collection;)Ljava/util/Collection;" xs)]
            (is (= "[]" (ldk/to-string c)) "an ArrayList is a Collection"))
          (is (nil? (ldk/call-static "Fixture" "run" "(Ljava/lang/Runnable;)V" r)) "a proxy fits its interface"))
        (testing "a callback's result is checked against its return type the same way"
          (ldk/with-ref [it (ldk/implement "java.lang.Iterable" {"iterator" (constantly xs)})]
            (is (re-find #"iterator\(\)Ljava/util/Iterator; returned a bad result: a java.util.ArrayList for parameter Ljava/util/Iterator;"
                         (msg #(ldk/call-static "java.lang.String" "join"
                                                "(Ljava/lang/CharSequence;Ljava/lang/Iterable;)Ljava/lang/String;" "," it))))))
        (testing "an array handle must be the parameter's array type"
          (is (re-find #"for array parameter \[I"
                       (msg #(ldk/call-static "Fixture" "sum" "([I)I" strs))))
          (is (re-find #"for array parameter \[Ljava/lang/Object;"
                       (msg #(ldk/call-static "java.util.Arrays" "toString" "([Ljava/lang/Object;)Ljava/lang/String;" ints))))
          (is (= "[a, b]" (ldk/call-static "java.util.Arrays" "toString" "([Ljava/lang/Object;)Ljava/lang/String;" strs))
              "a String[] is an Object[]")
          (is (re-find #"^\[Ljava.lang.String;:" (ldk/call-static "Fixture" "describe" "(Ljava/lang/Object;)Ljava/lang/String;" strs))
              "any array is an Object"))
        (testing "a parameter class that cannot be loaded is left to the call to report"
          (doseq [desc ["(Lnope/Foo;)Ljava/lang/String;" "([Lnope/Foo;)Ljava/lang/String;"
                        "([[Lnope/Foo;)Ljava/lang/String;"]]
            (let [e (thrown #(ldk/call-static "Fixture" "nothing" desc strs))]
              (is (re-find #"no static method" (str (:openldk/error (ex-data e)))) desc)))))
      (testing "the bridge is still healthy after all of that"
        (is (= 3 (ldk/call-static "Fixture" "sum" "([I)I" [1 2])))))))

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

(deftest java-calls-clojure-through-interfaces
  (when-built
    (ldk/init! {:classpath classes})
    (testing "a Runnable"
      (let [ran (atom 0)]
        (ldk/with-ref [r (ldk/implement "java.lang.Runnable" #(swap! ran inc))]
          (ldk/call-static "Fixture" "run" "(Ljava/lang/Runnable;)V" r)
          (ldk/call-static "Fixture" "run" "(Ljava/lang/Runnable;)V" r))
        (is (= 2 @ran))))
    (testing "results convert by the method's return type"
      (ldk/with-ref [s (ldk/implement "java.util.function.Supplier" (constantly "ab"))]
        (is (= "abab" (ldk/call-static "Fixture" "twice" "(Ljava/util/function/Supplier;)Ljava/lang/String;" s))))
      (ldk/with-ref [op (ldk/implement "java.util.function.IntBinaryOperator" (fn [a b] (* a b)))]
        (is (= 42 (ldk/call-static "Fixture" "applyInt" "(Ljava/util/function/IntBinaryOperator;II)I" op 6 7)))))
    (testing "a Comparator drives the JDK's sort, and a default method runs our compare"
      (ldk/with-ref [desc (ldk/implement "java.util.Comparator" (fn [a b] (compare b a)))
                     xs (ldk/new-array "Ljava/lang/String;" ["b" "c" "a"])]
        (ldk/call-static "java.util.Arrays" "sort" "([Ljava/lang/Object;Ljava/util/Comparator;)V" xs desc)
        (is (= ["c" "b" "a"] (ldk/array->vec xs)))
        (ldk/with-ref [asc (ldk/call desc "reversed" "()Ljava/util/Comparator;")]
          (ldk/call-static "java.util.Arrays" "sort" "([Ljava/lang/Object;Ljava/util/Comparator;)V" xs asc)
          (is (= ["a" "b" "c"] (ldk/array->vec xs))))))
    (testing "a map implements several methods"
      (let [items (atom ["x" "y"])]
        (ldk/with-ref [it (ldk/implement "java.util.Iterator"
                                         {"hasNext" (fn [] (boolean (seq @items)))
                                          "next" (fn [] (let [v (first @items)] (swap! items rest) v))})]
          (is (= "x;y;" (ldk/call-static "Fixture" "drain" "(Ljava/util/Iterator;)Ljava/lang/String;" it))))))
    (testing "object arguments are borrowed handles, usable during the call only"
      (let [seen (atom nil)]
        (ldk/with-ref [f (ldk/implement "java.util.function.Function"
                                        (fn [x] (reset! seen x) (ldk/to-string x)))
                       fx (ldk/new-object "Fixture" "(I)V" 5)]
          (is (= "Fixture(5)" (ldk/call-static "Fixture" "apply"
                                               "(Ljava/util/function/Function;Ljava/lang/Object;)Ljava/lang/Object;" f fx)))
          (is (ldk/ref? @seen))
          (is (thrown? Exception (ldk/to-string @seen)) "released once the fn returned"))))
    (testing "a scalar argument arrives as a value, and the fn may call back into Java"
      (ldk/with-ref [f (ldk/implement "java.util.function.Function"
                                      (fn [x] (ldk/call-static "java.lang.Math" "abs" "(J)J" x)))]
        (is (= 9 (ldk/call-static "Fixture" "apply"
                                  "(Ljava/util/function/Function;Ljava/lang/Object;)Ljava/lang/Object;" f -9)))))
    (testing "objects the fn gets from its own calls into Java are its to keep"
      (let [kept (atom nil)]
        (ldk/with-ref [r (ldk/implement "java.lang.Runnable"
                                        #(reset! kept (ldk/new-object "java.lang.StringBuilder" "(Ljava/lang/String;)V" "kept")))]
          (ldk/call-static "Fixture" "run" "(Ljava/lang/Runnable;)V" r))
        (is (= "kept" (ldk/to-string @kept)) "still live after the callback returned")
        (ldk/release! @kept)))
    (testing "a default method in a subinterface stays the default"
      (ldk/with-ref [s (ldk/implement "Fixture$Sub" {"g" (constantly 3)})]
        (is (= 7 (ldk/call-static "Fixture" "callF" "(LFixture$Base;)I" s)) "Sub's default, not a call to Clojure")
        (is (= 3 (ldk/call-static "Fixture" "callG" "(LFixture$Base;)I" s)))))
    (testing "a thread Java started can call in"
      (let [where (promise)]
        (ldk/with-ref [r (ldk/implement "java.lang.Runnable" #(deliver where :ran))]
          (ldk/call-static "Fixture" "onThread" "(Ljava/lang/Runnable;)V" r))
        (is (= :ran (deref where 5000 :timed-out)))))))

(deftest callback-exceptions-cross-both-ways
  (when-built
    (ldk/init! {:classpath classes})
    (let [boom (ex-info "boom from clojure" {:k 1})]
      (testing "Java sees a RuntimeException with the message"
        (ldk/with-ref [r (ldk/implement "java.lang.Runnable" #(throw boom))]
          (is (re-find #"^caught: boom from clojure"
                       (ldk/call-static "Fixture" "tryRun" "(Ljava/lang/Runnable;)Ljava/lang/String;" r)))))
      (testing "and when it comes back out to Clojure, the original is rethrown"
        (ldk/with-ref [r (ldk/implement "java.lang.Runnable" #(throw boom))]
          (is (identical? boom (thrown #(ldk/call-static "Fixture" "run" "(Ljava/lang/Runnable;)V" r)))))))
    (testing "a wrapper Java throws stays the wrapper, with the original as its cause"
      (let [inner (ex-info "inner" {})]
        (ldk/with-ref [r (ldk/implement "java.lang.Runnable" #(throw inner))]
          (let [e (thrown #(ldk/call-static "Fixture" "wrap" "(Ljava/lang/Runnable;)V" r))]
            (is (= "java.lang.IllegalStateException" (:java/class (ex-data e))))
            (is (re-find #"^wrapped: inner" (:java/message (ex-data e))))
            (is (identical? inner (ex-cause e)))
            (is (not (contains? (ex-data e) :openldk/callback-error)) "the id is not left in ex-data")))
        (ldk/with-ref [r (ldk/implement "java.lang.Runnable" #(throw inner))]
          (let [e (thrown #(ldk/call-static "Fixture" "wrapTwice" "(Ljava/lang/Runnable;)V" r))]
            (is (= "java.lang.UnsupportedOperationException" (:java/class (ex-data e))))
            (is (identical? inner (ex-cause e)) "found two causes down")))))
    (testing "a cause whose getCause throws still lets the exception through"
      (let [e (thrown #(ldk/call-static "Fixture" "nasty" "()V"))]
        (is (= "java.lang.IllegalArgumentException" (:java/class (ex-data e))))
        (is (= "outer" (:java/message (ex-data e))))))
    (testing "a wrapper that only quotes the message has no cause"
      (ldk/with-ref [r (ldk/implement "java.lang.Runnable" #(throw (ex-info "inner" {})))]
        (let [e (thrown #(ldk/call-static "Fixture" "quote" "(Ljava/lang/Runnable;)V" r))]
          (is (= "java.lang.IllegalStateException" (:java/class (ex-data e))))
          (is (re-find #"^quoted: inner" (:java/message (ex-data e))))
          (is (nil? (ex-cause e))))))
    (testing "a map without the method Java called"
      (ldk/with-ref [it (ldk/implement "java.util.Iterator" {"hasNext" (constantly true)})]
        (is (re-find #"no fn for next\(\)Ljava/lang/Object;"
                     (ex-message (thrown #(ldk/call-static "Fixture" "drain" "(Ljava/util/Iterator;)Ljava/lang/String;" it)))))))
    (testing "a result of the wrong type is a RuntimeException Java can catch"
      (ldk/with-ref [s (ldk/implement "java.util.function.IntSupplier" (constantly "not an int"))]
        (is (re-find #"^caught: jolt-openldk callback getAsInt\(\)I returned a bad result: a string for parameter I"
                     (ldk/call-static "Fixture" "tryInt" "(Ljava/util/function/IntSupplier;)Ljava/lang/String;" s)))))
    (testing "a typed array returned from a callback says what to return instead"
      (ldk/with-ref [s (ldk/implement "java.util.function.Supplier" (constantly (int-array [1 2])))]
        (is (re-find #"a callback cannot return a jolt int-array; return a vector, or a JavaRef from new-array"
                     (ex-message (thrown #(ldk/call-static "Fixture" "twice" "(Ljava/util/function/Supplier;)Ljava/lang/String;" s)))))))
    (testing "a result that does not fit the return type"
      (ldk/with-ref [op (ldk/implement "java.util.function.IntBinaryOperator" (fn [_ _] 99999999999))]
        (is (re-find #"does not fit a Java int"
                     (ex-message (thrown #(ldk/call-static "Fixture" "applyInt" "(Ljava/util/function/IntBinaryOperator;II)I" op 1 2)))))))
    (testing "a released proxy refuses, rather than calling a fn that is gone"
      (let [r (ldk/implement "java.lang.Runnable" (fn [] nil))]
        (ldk/call-static "Fixture" "store" "(Ljava/lang/Runnable;)V" r)
        (ldk/release! r)
        (is (re-find #"was released" (ex-message (thrown #(ldk/call-static "Fixture" "runStored" "()V")))))))
    (testing "a proxy prints, for Java code that logs or concatenates it"
      (ldk/with-ref [r (ldk/implement "java.lang.Runnable" (fn [] nil))]
        (is (re-find #"^jolt-openldk proxy for java.lang.Runnable #\d+$" (ldk/to-string r)))
        (is (re-find #"^jolt-openldk proxy for java.lang.Runnable"
                     (ldk/call-static "java.lang.String" "valueOf" "(Ljava/lang/Object;)Ljava/lang/String;" r))
            "Java's own String.valueOf, which calls toString")
        (is (re-find #"ClassNotFoundException"
                     (ex-message (thrown #(ldk/call r "getClass" "()Ljava/lang/Class;"))))
            "getClass() is the documented gap")))
    (testing "only interfaces can be implemented"
      (is (re-find #"is a class, not an interface"
                   (ex-message (thrown #(ldk/implement "java.util.ArrayList" (fn [] nil)))))))))

(deftest another-thread-can-call
  (when-built
   (ldk/init! {:classpath classes})
   (is (= 3 @(future (ldk/call-static "Fixture" "add" "(II)I" 1 2))))))

(deftest main-runs
  (when-built
   (ldk/init! {:classpath classes})
   (is (nil? (ldk/run-main "Fixture" "a" "b")))))

;;; --- primitive arrays as raw memory -----------------------------------------------

;; Rows of [component vector typed-array-fn expected]. F and D compare by str,
;; so NaN and -0.0 compare.
(def ^:private primitive-rows
  [["I" [-2147483648 0 2147483647] int-array [-2147483648 0 2147483647]]
   ["J" [Long/MIN_VALUE 0 Long/MAX_VALUE] long-array [Long/MIN_VALUE 0 Long/MAX_VALUE]]
   ["S" [-32768 0 32767] short-array [-32768 0 32767]]
   ["B" [-128 -1 0 127] byte-array [-128 -1 0 127]]
   ["C" [\a \é (char 0xFFFF)] char-array [\a \é (char 0xFFFF)]]
   ["Z" [true false true] boolean-array [true false true]]
   ["F" [0.5 -1.25 ##NaN ##Inf] float-array [0.5 -1.25 ##NaN ##Inf]]
   ["D" [0.0 -0.0 ##NaN ##Inf ##-Inf 1.0E300] double-array [0.0 -0.0 ##NaN ##Inf ##-Inf 1.0E300]]])

(defn- round-trip [component contents]
  (ldk/with-ref [xs (ldk/new-array component contents)]
    (ldk/array->vec xs)))

(defn- comparable [component v]
  (if (#{"F" "D"} component) (mapv str v) v))

(deftest primitive-arrays-go-in-as-raw-memory
  (when-built
    (ldk/init! {:classpath classes})
    (doseq [[component v typed expected] primitive-rows]
      (is (= (comparable component expected) (comparable component (round-trip component v)))
          (str component " from a vector"))
      (is (= (comparable component expected) (comparable component (round-trip component (typed v))))
          (str component " from a typed array")))
    (is (= [55296] (round-trip "C" [0xD800])) "a lone surrogate half comes back as its integer")
    (doseq [[component _ typed _] primitive-rows]
      (ldk/with-ref [a (ldk/new-array component [])
                     b (ldk/new-array component (typed []))]
        (is (= [0 0] [(ldk/array-length a) (ldk/array-length b)]) (str "empty " component))))))

(deftest typed-arrays-pass-for-primitive-array-parameters
  (when-built
    (ldk/init! {:classpath classes})
    (is (= 6 (ldk/call-static "Fixture" "sum" "([I)I" (int-array [1 2 3]))))
    (is (= 3 (ldk/call-static "Fixture" "total" "([J)J" (int-array [1 2]))) "an int-array widens")
    (is (= 2.0 (ldk/call-static "Fixture" "avg" "([D)D" (int-array [1 3]))))
    (is (= 2.0 (ldk/call-static "Fixture" "avg" "([D)D" (double-array [1.0 3.0]))))
    (is (= -2 (ldk/call-static "Fixture" "byteSum" "([B)I" (byte-array [-1 127 -128]))))
    (is (= 2 (ldk/call-static "Fixture" "trues" "([Z)I" (boolean-array [true false true]))))
    (is (= \é (ldk/call-static "Fixture" "last" "([C)C" (char-array [\h \é]))))
    (is (= "[1.0, 2.5]" (ldk/call-static "java.util.Arrays" "toString" "([D)Ljava/lang/String;" [1 2.5]))
        "a vector of mixed numbers still takes the text path")))

(deftest raw-arrays-keep-todays-refusals
  (when-built
    (ldk/init! {:classpath classes})
    (let [msg #(ex-message (thrown %))
          typed-refusal "jolt-openldk: a jolt int-array can only be passed for a one-dimensional primitive array parameter or as new-array's contents"]
      (is (= "jolt-openldk: element 0 of the vector for [B: 200 does not fit a Java byte"
             (msg #(ldk/call-static "Fixture" "byteSum" "([B)I" [200]))))
      (is (= "jolt-openldk: element 1 of the vector for [I: 3000000000 does not fit a Java int"
             (msg #(ldk/call-static "Fixture" "sum" "([I)I" (long-array [1 3000000000])))))
      (is (= "jolt-openldk: element 1 of the vector for [I: a double for parameter I"
             (msg #(ldk/new-array "I" [1 2.5]))))
      (is (= "jolt-openldk: element 1 of the vector for [I: null for parameter I"
             (msg #(ldk/call-static "Fixture" "sum" "([I)I" [1 nil]))))
      (is (= "jolt-openldk: element 1 of the vector for [J: 9223372036854775808 does not fit a Java long"
             (msg #(ldk/call-static "Fixture" "total" "([J)J" [1 (inc' Long/MAX_VALUE)]))))
      (is (= typed-refusal (msg #(ldk/call-static "Fixture" "describe" "(Ljava/lang/Object;)Ljava/lang/String;" (int-array [1])))))
      (is (= typed-refusal (msg #(ldk/call-static "Fixture" "join" "([Ljava/lang/String;)Ljava/lang/String;" (int-array [1])))))
      (is (= typed-refusal (msg #(ldk/call-static "Fixture" "deep" "([[I)I" [(int-array [1])]))))
      (is (re-find #"malformed method descriptor" (msg #(ldk/call-static "Fixture" "sum" "([I" (int-array [1]))))))))

(deftest raw-array-wire-refusals
  (when-built
    (ldk/init! {:classpath classes})
    (let [send (deref (resolve 'net.b12n.jolt.openldk/send!))
          msg #(ex-message (thrown (fn [] (send %))))]
      (is (re-find #"a raw array needs a length of 0 or more, got -1"
                   (msg "(:new-array \"I\" (:raw-array :i64 0 -1))")))
      (is (re-find #"a raw array of 5 elements cannot be at address 0"
                   (msg "(:new-array \"I\" (:raw-array :i64 0 5))")))
      (is (re-find #"unknown raw array carrier :Q"
                   (msg "(:new-array \"I\" (:raw-array :q 0 0))")))
      (is (re-find #"a primitive array cannot be passed for parameter Ljava/lang/Object;"
                   (msg "(:static \"Fixture\" \"describe\" \"(Ljava/lang/Object;)Ljava/lang/String;\" ((:raw-array :i64 0 0)))"))))))

(deftest primitive-arrays-come-back-as-raw-memory
  (when-built
    (ldk/init! {:classpath classes})
    (testing "elements keep today's types"
      (doseq [[component v] [["I" [5]] ["B" [5]] ["J" [5]]]]
        (is (= (class 1) (class (first (round-trip component v)))) component))
      (is (= (class 1.0) (class (first (round-trip "D" [5.0]))))))
    (testing "arrays Java built itself"
      (ldk/with-ref [s (ldk/new-object "java.lang.String" "(Ljava/lang/String;)V" "hé")
                     bs (ldk/call s "getBytes" "(Ljava/lang/String;)[B" "ISO-8859-1")]
        ;; Stored unsigned by OpenLDK's JSTRING, read back signed as Java's.
        (is (= [104 -23] (ldk/array->vec bs)))
        (is (= [104 -23] (vec (ldk/array->primitive bs)))))
      (ldk/with-ref [s (ldk/new-object "java.lang.String" "(Ljava/lang/String;)V" "a😀")
                     cs (ldk/call s "toCharArray" "()[C")]
        (is (= [\a 55357 56832] (ldk/array->vec cs)) "surrogate halves come back as integers"))
      (ldk/with-ref [xs (ldk/new-array "I" [3 1 2])]
        (ldk/call-static "java.util.Arrays" "sort" "([I)V" xs)
        (is (= [1 2 3] (vec (ldk/array->primitive xs))))))))

(deftest array-primitive-returns-typed-arrays
  (when-built
    (ldk/init! {:classpath classes})
    (doseq [[component v typed] primitive-rows
            :when (#{"B" "S" "I" "J" "F" "D"} component)]
      (ldk/with-ref [xs (ldk/new-array component v)]
        (let [out (ldk/array->primitive xs)]
          (is (= (class (typed [])) (class out)) component)
          (is (= (comparable component v) (comparable component (vec out))) component)))
      (ldk/with-ref [xs (ldk/new-array component [])]
        (is (zero? (alength (ldk/array->primitive xs))) (str "empty " component))))
    (testing "a copy each way"
      (ldk/with-ref [xs (ldk/new-array "I" [1 2 3])]
        (let [out (ldk/array->primitive xs)]
          (aset out 0 99)
          (is (= [1 2 3] (ldk/array->vec xs)) "changing the jolt array leaves Java's alone")))
      (let [in (int-array [3 1 2])]
        (ldk/with-ref [xs (ldk/new-array "I" in)]
          (ldk/call-static "java.util.Arrays" "sort" "([I)V" xs)
          (is (= [1 2 3] (ldk/array->vec xs)))
          (is (= [3 1 2] (vec in)) "Java sorting its array leaves the int-array alone"))))))

(deftest a-raw-reply-it-cannot-read-is-refused
  (when-built
    (ldk/init! {:classpath classes})
    (let [copy-raw @#'ldk/copy-raw]
      (is (re-find #"a raw reply with unknown component Q"
                   (ex-message (thrown #(copy-raw (wire/->RawReply "Q" 0 0))))))
      (is (re-find #"a raw reply with unknown component Q"
                   (ex-message (thrown #(copy-raw (wire/->RawReply "Q" 0 3)))))))))

(deftest array-primitive-refusals
  (when-built
    (ldk/init! {:classpath classes})
    (let [msg #(ex-message (thrown %))
          read (fn [method desc] (ldk/with-ref [a (ldk/call-static "Fixture" method desc)]
                                   (msg (fn [] (ldk/array->primitive a)))))]
      (is (= "jolt-openldk: array->primitive takes a numeric array; use array->vec for a char[]"
             (read "letters" "()[C")))
      (is (= "jolt-openldk: array->primitive takes a numeric array; use array->vec for a boolean[]"
             (read "flags" "()[Z")))
      (is (= "jolt-openldk: array->primitive takes a numeric array, not a [Ljava.lang.String;"
             (read "names" "()[Ljava/lang/String;")))
      (ldk/with-ref [xs (ldk/new-object "java.util.ArrayList" "()V")]
        (is (re-find #"not an array" (msg #(ldk/array->primitive xs))))))))

(deftest reflectively-built-arrays-read-as-java-defaults
  ;; OpenLDK's Array.newInstance fills even a primitive array with nil, which
  ;; Java code reads as the type's zero; the bridge has to read it the same way.
  (when-built
    (ldk/init! {:classpath classes})
    (let [fresh (fn [component sample]
                  (ldk/with-ref [a (ldk/new-array component [sample])
                                 c (ldk/call a "getClass" "()Ljava/lang/Class;")
                                 t (ldk/call c "getComponentType" "()Ljava/lang/Class;")]
                    (ldk/call-static "java.lang.reflect.Array" "newInstance"
                                     "(Ljava/lang/Class;I)Ljava/lang/Object;" t 2)))]
      (doseq [[component sample expected] [["Z" true [false false]] ["B" 1 [0 0]] ["S" 1 [0 0]]
                                           ["I" 1 [0 0]] ["J" 1 [0 0]] ["C" \a [(char 0) (char 0)]]
                                           ["F" 1.0 [0.0 0.0]] ["D" 1.0 [0.0 0.0]]]]
        (ldk/with-ref [a (fresh component sample)]
          (is (= expected (ldk/array->vec a)) component)
          (when-not (#{"Z" "C"} component)
            (is (= expected (vec (ldk/array->primitive a))) component)))))))

(deftest strings-passed-in-are-java-strings
  ;; A string crosses into Java as OpenLDK's jstring, whose bytes used to be
  ;; unsigned where Java's byte[] is signed. JDK code depends on the sign, so
  ;; UTF-8 came out as Latin-1 and equals failed against Java-built text.
  (when-built
    (ldk/init! {:classpath classes})
    (ldk/with-ref [s (ldk/new-object "java.lang.String" "(Ljava/lang/String;)V" "hé")
                   utf8 (ldk/call s "getBytes" "(Ljava/lang/String;)[B" "UTF-8")
                   built (ldk/new-object "java.lang.String" "([C)V" [\h \é])]
      (is (= [104 -61 -87] (ldk/array->vec utf8)))
      (is (true? (ldk/call built "equals" "(Ljava/lang/Object;)Z" "hé"))))
    (ldk/with-ref [built (ldk/new-object "java.lang.String" "([C)V" [\a \日])]
      (is (true? (ldk/call built "equals" "(Ljava/lang/Object;)Z" "a日")) "UTF16 too"))
    (is (= "HÉ!" (ldk/call-static "Fixture" "shout" "(Ljava/lang/String;)Ljava/lang/String;" "hé")))))

(deftest java-nio-file-and-message-digest-work
  ;; MessageDigest initializes java.security.Security, which reads its master
  ;; file through java.nio.file, which loads the nio library. On macOS that
  ;; reached a native OpenLDK didn't define and failed with an undefined
  ;; function.
  (when-built
    (ldk/init! {:classpath classes})
    (ldk/with-ref [md (ldk/call-static "java.security.MessageDigest" "getInstance"
                                       "(Ljava/lang/String;)Ljava/security/MessageDigest;" "MD5")
                   d (ldk/call md "digest" "([B)[B" (byte-array [104 105]))]
      (is (= [73 -10 -118 92 -124 -109 -20 44 11 -12 -119 -126 28 33 -4 59] (ldk/array->vec d))
          "MD5 of \"hi\""))
    (let [f (java.io.File/createTempFile "nio" ".txt")]
      (try
        (spit f "hello nio")
        (ldk/with-ref [p (ldk/call-static "java.nio.file.Path" "of"
                                          "(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;"
                                          (.getAbsolutePath f) [])]
          (is (true? (ldk/call-static "java.nio.file.Files" "exists"
                                      "(Ljava/nio/file/Path;[Ljava/nio/file/LinkOption;)Z" p
                                      (ldk/new-array "Ljava/nio/file/LinkOption;" []))))
          (is (= "hello nio" (ldk/call-static "java.nio.file.Files" "readString"
                                              "(Ljava/nio/file/Path;)Ljava/lang/String;" p))))
        (finally (.delete f))))))

(deftest java-util-zip-works
  ;; OpenLDK's java.util.zip natives call the system zlib, which it loaded as
  ;; libz.so.1, a name macOS doesn't have.
  (when-built
    (ldk/init! {:classpath classes})
    (let [data (.getBytes "hello hello hello zlib" "UTF-8")]
      (ldk/with-ref [crc (ldk/new-object "java.util.zip.CRC32" "()V")]
        (ldk/call crc "update" "([B)V" data)
        (is (= 0x55381b8f (ldk/call crc "getValue" "()J"))))
      (ldk/with-ref [bo (ldk/new-object "java.io.ByteArrayOutputStream" "()V")]
        (ldk/with-ref [gz (ldk/new-object "java.util.zip.GZIPOutputStream" "(Ljava/io/OutputStream;)V" bo)]
          (ldk/call gz "write" "([B)V" data)
          (ldk/call gz "close" "()V"))
        (ldk/with-ref [packed (ldk/call bo "toByteArray" "()[B")
                       bi (ldk/new-object "java.io.ByteArrayInputStream" "([B)V" packed)
                       gin (ldk/new-object "java.util.zip.GZIPInputStream" "(Ljava/io/InputStream;)V" bi)
                       back (ldk/call gin "readAllBytes" "()[B")]
          (is (= (vec data) (ldk/array->vec back)) "a GZIP round trip"))))))

(deftest instant-now-works
  ;; Instant.now() reads the clock through VM.getNanoTimeAdjustment, a native
  ;; OpenLDK didn't define. Date.toString reaches it too, in a time zone with
  ;; no CLDR display name.
  (when-built
    (ldk/init! {:classpath classes})
    (ldk/with-ref [now (ldk/call-static "java.time.Instant" "now" "()Ljava/time/Instant;")]
      (let [ms (ldk/call now "toEpochMilli" "()J")]
        (is (< (abs (- ms (System/currentTimeMillis))) 5000) (str "Instant.now() gave " ms))))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
