;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk.wire
  "The text that crosses into OpenLDK and back. Pure, so it is tested without
  SBCL anywhere near.

  A request is a Common Lisp form, read on the far side by bridge.lisp with
  *read-eval* off and only keywords interned. A reply is printed by Lisp in a
  shape clojure.edn reads as it stands:

    (:ok (:i 42))
    (:throw \"java.lang.IllegalStateException\" \"boom\" \"java.lang.Illegal...: boom\")
    (:error \"class not found: Nope\")

  Values travel tagged, (:i n) (:d x) (:nan) (:inf 1) (:z :true) (:c 97)
  (:s \"text\") (:null) (:ref 3 \"java.util.ArrayList\") (:void), plus
  (:array (v ...)) out and (:vec v ...) back for arrays, so a Java
  boolean comes back as a boolean and a char as a char, not as the integers
  OpenLDK keeps them as."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]))

(defrecord JavaRef [handle class])

(defn ref? [x] (instance? JavaRef x))

;; --- Clojure -> Lisp text -----------------------------------------------------

(defn- lisp-string
  "A Lisp string literal. The Lisp reader escapes exactly two characters,
  backslash and double quote, and reads every other character as itself, so
  \\n written the Clojure way would arrive as the letter n. A request crosses
  as a C string, so a NUL would silently cut it short: refuse it instead."
  [s]
  (when (str/includes? s "\u0000")
    (throw (ex-info "jolt-openldk: a string with a NUL cannot cross into OpenLDK"
                    {:string s})))
  (str "\"" (-> s (str/replace "\\" "\\\\") (str/replace "\"" "\\\"")) "\""))

(defn- lisp-double [x]
  (let [x (double x)]
    (cond (Double/isNaN x) "(:nan)"
          (Double/isInfinite x) (if (pos? x) "(:inf 1)" "(:inf -1)")
          ;; "1.0E20" and "3.0" both read as doubles there, because the bridge
          ;; reads with *read-default-float-format* set to double-float.
          :else (str "(:d " x ")"))))

(defn value->lisp
  "One argument, tagged for bridge.lisp. The method descriptor decides what it
  becomes on the Java side: (:i 5) is an int for an I parameter, a long for J,
  and a boxed Long for an Object."
  [v]
  (cond
    (nil? v) "(:null)"
    (true? v) "(:z :true)"
    (false? v) "(:z :false)"
    (ref? v) (str "(:ref " (:handle v) ")")
    (string? v) (str "(:s " (lisp-string v) ")")
    (char? v) (str "(:c " (int v) ")")
    (integer? v) (str "(:i " v ")")
    (or (double? v) (float? v)) (lisp-double v)
    ;; Only a vector, not any seq: a lazy seq handed over by accident would
    ;; otherwise be realised and copied into an array.
    (vector? v) (str "(:array (" (str/join " " (map value->lisp v)) "))")
    :else (throw (ex-info (str "jolt-openldk: cannot pass a " (type v) " to Java; "
                               "pass nil, a boolean, an integer, a double, a string, a char, "
                               "a vector (for an array parameter) or a JavaRef")
                          {:value v}))))

(defn- args->lisp [args]
  (str "(" (str/join " " (map value->lisp args)) ")"))

(defn setup [classpath]
  (str "(:setup " (lisp-string classpath) ")"))

(defn static [class-name method descriptor args]
  (str "(:static " (lisp-string class-name) " " (lisp-string method) " "
       (lisp-string descriptor) " " (args->lisp args) ")"))

(defn new-object [class-name descriptor args]
  (str "(:new " (lisp-string class-name) " " (lisp-string descriptor) " " (args->lisp args) ")"))

(defn invoke [r method descriptor args]
  (str "(:invoke " (:handle r) " " (lisp-string method) " " (lisp-string descriptor) " "
       (args->lisp args) ")"))

(defn run-main [class-name args]
  (str "(:main " (lisp-string class-name) " (" (str/join " " (map lisp-string args)) "))"))

(defn class-name [r] (str "(:class-name " (:handle r) ")"))

(defn release [r] (str "(:release " (:handle r) ")"))

(defn new-array [component values]
  (str "(:new-array " (lisp-string component) " (" (str/join " " (map value->lisp values)) "))"))

(defn elements [r] (str "(:elements " (:handle r) ")"))

(defn array-length [r] (str "(:length " (:handle r) ")"))

(defn proxy-request [interfaces fn-id]
  (str "(:proxy (" (str/join " " (map lisp-string interfaces)) ") " fn-id ")"))

;; --- Java calling Clojure ----------------------------------------------------------
;;
;; bridge.lisp sends (:call fn-id "compare" "(Ljava/lang/Object;Ljava/lang/Object;)I"
;; (v ...)), printed by the same printer as its replies, and reads back one of
;; the three replies below with its request reader.

(declare lisp->value)

(defn parse-call
  "The callback request `text` as {:fn-id :method :descriptor :args}, the
  arguments decoded as reply values are."
  [text]
  (let [[tag fn-id method descriptor args :as form] (edn/read-string text)]
    (when-not (= :call tag)
      (throw (ex-info "jolt-openldk: not a callback request" {:form form})))
    {:fn-id fn-id :method method :descriptor descriptor :args (mapv lisp->value args)}))

(defn ok-reply
  "A callback's result. A void method's result is ignored rather than encoded,
  so a fn may return anything there."
  [descriptor v]
  (if (str/ends-with? descriptor ")V")
    "(:ok (:void))"
    (str "(:ok " (value->lisp v) ")")))

(defn throw-reply
  "Tell Java the callback threw: it raises a RuntimeException carrying
  `message` and `error-id`, by which the original is found again if the
  exception comes back out to Clojure."
  [message error-id]
  (str "(:throw " (lisp-string (str/replace (str message) "\u0000" "")) " " error-id ")"))

(defn error-reply [message]
  (str "(:error " (lisp-string (str/replace (str message) "\u0000" "")) ")"))

(defn callback-error-id
  "The error id a callback's RuntimeException carries in its message, or nil."
  [message]
  (some-> (re-find #"\[jolt-openldk callback error (\d+)\]" (or message "")) second parse-long))

;; --- Lisp text -> Clojure -------------------------------------------------------

(defn lisp->value
  "One tagged value from a reply."
  [[tag a b :as v]]
  (case tag
    (:void :null) nil
    :i a
    :d (double a)
    :nan ##NaN
    :inf (if (pos? a) ##Inf ##-Inf)
    :z (= a :true)
    ;; A jolt char cannot hold half a surrogate pair, which a Java char can,
    ;; so that one case comes back as its integer code.
    :c (if (<= 0xD800 a 0xDFFF) a (char a))
    :s a
    :ref (->JavaRef a b)
    :vec (mapv lisp->value (rest v))
    (throw (ex-info (str "jolt-openldk: unknown value in a reply: " (pr-str v)) {:value v}))))

(defn reply->value
  "The value an :ok reply carries, or the exception the reply describes.

  A Java exception becomes an ex-info carrying :java/class, :java/message (nil
  when Java's getMessage was null) and :java/string, its toString. Anything
  the bridge itself refused, a missing class or a wrong argument count, carries
  :openldk/error instead."
  [text]
  (let [[status a b c :as reply] (edn/read-string text)]
    (case status
      :ok (lisp->value a)
      :throw (throw (ex-info c {:java/class a
                                :java/message (when (string? b) b)
                                :java/string c}))
      :error (throw (ex-info (str "jolt-openldk: " a) {:openldk/error a}))
      (throw (ex-info "jolt-openldk: an unreadable reply" {:reply reply :text text})))))
