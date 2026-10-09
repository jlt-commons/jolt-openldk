;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.wire-test
  (:require [clojure.test :refer [deftest is run-tests testing]]
            [net.b12n.jolt.openldk.wire :as wire]))

(deftest values-go-out-tagged
  (is (= "(:null)" (wire/value->lisp nil)))
  (is (= "(:z :true)" (wire/value->lisp true)))
  (is (= "(:z :false)" (wire/value->lisp false)))
  (is (= "(:i 42)" (wire/value->lisp 42)))
  (is (= "(:i 123456789012345678901234567890)" (wire/value->lisp 123456789012345678901234567890N)))
  (is (= "(:d 1.5)" (wire/value->lisp 1.5)))
  (is (= "(:d 1.0E20)" (wire/value->lisp 1.0E20)) "the bridge reads E as a double exponent")
  (is (= "(:nan)" (wire/value->lisp ##NaN)))
  (is (= "(:inf 1)" (wire/value->lisp ##Inf)))
  (is (= "(:inf -1)" (wire/value->lisp ##-Inf)))
  (is (= "(:c 97)" (wire/value->lisp \a)))
  (is (= "(:ref 7)" (wire/value->lisp (wire/->JavaRef 7 "java.util.ArrayList")))))

(deftest strings-are-lisp-literals
  (testing "only backslash and double quote are escaped, the Lisp reader's two"
    (is (= "(:s \"a\\\"b\\\\c\")" (wire/value->lisp "a\"b\\c"))))
  (testing "a newline stays a raw newline: \\n would arrive as the letter n"
    (is (= "(:s \"x\ny\")" (wire/value->lisp "x\ny"))))
  (testing "non-ASCII passes through as itself"
    (is (= "(:s \"日本 é\")" (wire/value->lisp "日本 é"))))
  (testing "a NUL would cut the C string short, so it is refused"
    (is (thrown? Exception (wire/value->lisp "a\u0000b")))))

(deftest vectors-go-out-as-arrays
  (is (= "(:array ((:i 1) (:s \"a\") (:null)))" (wire/value->lisp [1 "a" nil])))
  (is (= "(:array ((:array ((:i 1))) (:array ())))" (wire/value->lisp [[1] []])))
  (testing "a seq is not taken for an array"
    (is (thrown? Exception (wire/value->lisp (list 1 2))))
    (is (thrown? Exception (wire/value->lisp (map inc [1 2])))))
  (is (= "(:new-array \"I\" ((:i 1) (:i 2)))" (wire/new-array "I" [1 2])))
  (is (= "(:elements 5)" (wire/elements (wire/->JavaRef 5 "[I"))))
  (is (= "(:length 5)" (wire/array-length (wire/->JavaRef 5 "[I")))))

(deftest arrays-come-back-as-vectors
  (is (= [1 true \a "s" nil] (wire/reply->value "(:ok (:vec (:i 1) (:z :true) (:c 97) (:s \"s\") (:null)))")))
  (is (= [] (wire/reply->value "(:ok (:vec))")))
  (is (= [(wire/->JavaRef 9 "[I")] (wire/reply->value "(:ok (:vec (:ref 9 \"[I\")))"))))

(deftest unsupported-values-are-refused
  (is (thrown? Exception (wire/value->lisp {:a 1})))
  (is (thrown? Exception (wire/value->lisp :kw))))

(deftest requests
  (is (= "(:static \"java.lang.Math\" \"sqrt\" \"(D)D\" ((:d 2.0)))"
         (wire/static "java.lang.Math" "sqrt" "(D)D" [2.0])))
  (is (= "(:new \"java.util.ArrayList\" \"()V\" ())"
         (wire/new-object "java.util.ArrayList" "()V" [])))
  (is (= "(:invoke 3 \"add\" \"(Ljava/lang/Object;)Z\" ((:s \"a\")))"
         (wire/invoke (wire/->JavaRef 3 "x") "add" "(Ljava/lang/Object;)Z" ["a"])))
  (is (= "(:main \"Hello\" (\"a\" \"b c\"))" (wire/run-main "Hello" ["a" "b c"])))
  (is (= "(:setup \"/a:/b.jar\")" (wire/setup "/a:/b.jar")))
  (is (= "(:release 4)" (wire/release (wire/->JavaRef 4 "x")))))

(deftest replies-as-the-bridge-prints-them
  ;; Each string below is copied from bridge.lisp's actual output.
  (is (= 42 (wire/reply->value "(:ok (:i 42))")))
  (is (= 9000000000 (wire/reply->value "(:ok (:i 9000000000))")))
  (is (= 0.3333333432674408 (wire/reply->value "(:ok (:d 0.3333333432674408))")))
  (is (true? (wire/reply->value "(:ok (:z :true))")))
  (is (false? (wire/reply->value "(:ok (:z :false))")))
  (is (= \b (wire/reply->value "(:ok (:c 98))")))
  (is (= "HÉLLO" (wire/reply->value "(:ok (:s \"HÉLLO\"))")))
  (is (nil? (wire/reply->value "(:ok (:null))")))
  (is (nil? (wire/reply->value "(:ok (:void))")))
  (is (Double/isNaN (wire/reply->value "(:ok (:nan))")))
  (is (= ##-Inf (wire/reply->value "(:ok (:inf -1))")))
  (is (= (wire/->JavaRef 2 "java.util.ArrayList")
         (wire/reply->value "(:ok (:ref 2 \"java.util.ArrayList\"))"))))

(deftest awkward-replies
  (testing "the bridge escapes NUL and control characters as \\uXXXX"
    (is (= "a\u0000b\u0001" (wire/reply->value "(:ok (:s \"a\\u0000b\\u0001\"))"))))
  (testing "a surrogate char comes back as its code, since a jolt char cannot hold it"
    (is (= 0xD83D (wire/reply->value "(:ok (:c 55357))")))
    (is (= \a (wire/reply->value "(:ok (:c 97))")))))

(deftest a-java-exception-becomes-ex-info
  (let [e (try (wire/reply->value
                "(:throw \"java.lang.IllegalStateException\" \"boom: x\" \"java.lang.IllegalStateException: boom: x\")")
               (catch Exception e e))]
    (is (= "java.lang.IllegalStateException: boom: x" (ex-message e)))
    (is (= {:java/class "java.lang.IllegalStateException"
            :java/message "boom: x"
            :java/string "java.lang.IllegalStateException: boom: x"}
           (ex-data e))))
  (testing "a null getMessage arrives as :null and leaves :java/message nil"
    (let [e (try (wire/reply->value
                  "(:throw \"java.lang.NullPointerException\" :null \"java.lang.NullPointerException\")")
                 (catch Exception e e))]
      (is (nil? (:java/message (ex-data e))))
      (is (= "java.lang.NullPointerException" (:java/class (ex-data e)))))))

(deftest a-bridge-error-becomes-ex-info
  (let [e (try (wire/reply->value "(:error \"class not found: Nope\")") (catch Exception e e))]
    (is (= "jolt-openldk: class not found: Nope" (ex-message e)))
    (is (= {:openldk/error "class not found: Nope"} (ex-data e)))))

(defn -main [& _]
  (let [{:keys [fail error]} (run-tests 'net.b12n.jolt.openldk.wire-test)]
    (when (pos? (+ fail error)) (System/exit 1))))
