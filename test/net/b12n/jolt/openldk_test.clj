;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

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
    (testing "a wrapper Java throws stays the wrapper, even when it quotes the original"
      (ldk/with-ref [r (ldk/implement "java.lang.Runnable" #(throw (ex-info "inner" {})))]
        (let [e (thrown #(ldk/call-static "Fixture" "wrap" "(Ljava/lang/Runnable;)V" r))]
          (is (= "java.lang.IllegalStateException" (:java/class (ex-data e))))
          (is (re-find #"^wrapped: inner" (:java/message (ex-data e)))))))
    (testing "a map without the method Java called"
      (ldk/with-ref [it (ldk/implement "java.util.Iterator" {"hasNext" (constantly true)})]
        (is (re-find #"no fn for next\(\)Ljava/lang/Object;"
                     (ex-message (thrown #(ldk/call-static "Fixture" "drain" "(Ljava/util/Iterator;)Ljava/lang/String;" it)))))))
    (testing "a result of the wrong type is a RuntimeException Java can catch"
      (ldk/with-ref [s (ldk/implement "java.util.function.IntSupplier" (constantly "not an int"))]
        (is (re-find #"^caught: jolt-openldk callback getAsInt\(\)I returned a bad result: a string for parameter I"
                     (ldk/call-static "Fixture" "tryInt" "(Ljava/util/function/IntSupplier;)Ljava/lang/String;" s)))))
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

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk-test)]
    (shutdown-agents)
    (when (pos? (+ fail error)) (System/exit 1))))
