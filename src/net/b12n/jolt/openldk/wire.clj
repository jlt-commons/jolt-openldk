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

;; A primitive array's elements in native memory, by address, so they cross
;; without text. `carrier` says how they were written (see bridge.lisp's
;; %ldk-raw-values), which is not always the component's width: a vector of
;; integers goes as int64 whatever the component, and Lisp checks each one.
(defrecord RawArray [carrier address length])

;; The reply to :elements for a primitive array: `length` elements of the
;; one-letter `component` at `address`, malloc'd by the bridge for the caller
;; to copy and then free.
(defrecord RawReply [component address length])

(def ^:private primitive-array-kinds
  [[(class (byte-array 0)) :byte] [(class (short-array 0)) :short]
   [(class (int-array 0)) :int] [(class (long-array 0)) :long]
   [(class (float-array 0)) :float] [(class (double-array 0)) :double]
   [(class (char-array 0)) :char] [(class (boolean-array 0)) :boolean]])

(defn primitive-array-kind
  "The kind of jolt typed array `x` is, :byte through :boolean, or nil for
  anything else, vectors and object arrays included."
  [x]
  (when (some? x)
    (let [c (class x)]
      (some (fn [[k kind]] (when (= c k) kind)) primitive-array-kinds))))

;; --- method descriptors ---------------------------------------------------------

(defn- field-end
  "The index just past the field descriptor starting at `i` in `s`, or nil.
  The same grammar as %ldk-field-end in bridge.lisp."
  [s i]
  (when (< i (count s))
    (case (nth s i)
      (\B \C \D \F \I \J \S \Z) (inc i)
      \L (let [end (str/index-of s ";" i)]
           (when (and end (> end (inc i)) (not (re-find #"[.\[()]" (subs s (inc i) end))))
             (inc end)))
      \[ (field-end s (inc i))
      nil)))

(defn split-descriptor
  "The parameter descriptors of method descriptor `desc` as a vector, or nil
  when it is malformed. bridge.lisp stays the authority: nil only means the
  client leaves the arguments as they are and lets Lisp say what is wrong."
  [desc]
  (let [close (str/index-of (str desc) ")")]
    (when (and close (str/starts-with? desc "("))
      (loop [i 1 params []]
        (cond
          (= i close) params
          :else (let [end (field-end desc i)]
                  (when (and end (<= end close))
                    (recur end (conj params (subs desc i end))))))))))

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
    (instance? RawArray v) (str "(:raw-array :" (name (:carrier v)) " " (:address v) " " (:length v) ")")
    (string? v) (str "(:s " (lisp-string v) ")")
    (char? v) (str "(:c " (int v) ")")
    (integer? v) (str "(:i " v ")")
    (or (double? v) (float? v)) (lisp-double v)
    (primitive-array-kind v)
    (throw (ex-info (str "jolt-openldk: a jolt " (name (primitive-array-kind v))
                         "-array can only be passed for a one-dimensional primitive array "
                         "parameter or as new-array's contents")
                    {:value v}))
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

(defn new-array
  "`values` is a seq of values, or a RawArray whose memory holds them."
  [component values]
  (str "(:new-array " (lisp-string component) " "
       (if (instance? RawArray values)
         (value->lisp values)
         (str "(" (str/join " " (map value->lisp values)) ")"))
       ")"))

(defn elements [r] (str "(:elements " (:handle r) ")"))

(defn array-length [r] (str "(:length " (:handle r) ")"))

(defn subclass-request
  "Make a subclass of `class-name` whose methods named by `method-keys` (the
  map's keys, as given) call fn `fn-id`, constructed through `descriptor`."
  [class-name descriptor args fn-id method-keys]
  (str "(:subclass " (lisp-string class-name) " " (lisp-string descriptor) " " (args->lisp args)
       " " fn-id " (" (str/join " " (map lisp-string method-keys)) "))"))

(defn super-request [r method descriptor args]
  (str "(:super " (:handle r) " " (lisp-string method) " " (lisp-string descriptor) " "
       (args->lisp args) ")"))

(defn get-field-request [r field] (str "(:get-field " (:handle r) " " (lisp-string field) ")"))

(defn set-field-request [r field v]
  (str "(:set-field " (:handle r) " " (lisp-string field) " " (value->lisp v) ")"))

(defn get-static-request [class-name field]
  (str "(:get-static " (lisp-string class-name) " " (lisp-string field) ")"))

(defn set-static-request [class-name field v]
  (str "(:set-static " (lisp-string class-name) " " (lisp-string field) " " (value->lisp v) ")"))

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
  (cond
    (str/ends-with? descriptor ")V") "(:ok (:void))"
    ;; value->lisp's own refusal is worded for an argument, not a result.
    (primitive-array-kind v)
    (throw (ex-info (str "jolt-openldk: a callback cannot return a jolt "
                         (name (primitive-array-kind v))
                         "-array; return a vector, or a JavaRef from new-array")
                    {:value v}))
    :else (str "(:ok " (value->lisp v) ")")))

(defn throw-reply
  "Tell Java the callback threw: it raises a RuntimeException carrying
  `message` and `error-id`, by which the original is found again if the
  exception comes back out to Clojure."
  [message error-id]
  (str "(:throw " (lisp-string (str/replace (str message) "\u0000" "")) " " error-id ")"))

(defn error-reply [message]
  (str "(:error " (lisp-string (str/replace (str message) "\u0000" "")) ")"))

(defn callback-error-id
  "The error id a callback's RuntimeException carries at the end of its
  message, or nil. Anchored at the end: a wrapper whose message quotes the
  original somewhere inside is a different exception."
  [message]
  (some-> (re-find #"\[jolt-openldk callback error (\d+)\]$" (or message "")) second parse-long))

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
    :raw (->RawReply a b (nth v 3))
    (throw (ex-info (str "jolt-openldk: unknown value in a reply: " (pr-str v)) {:value v}))))

(defn reply->value
  "The value an :ok reply carries, or the exception the reply describes.

  A Java exception becomes an ex-info carrying :java/class, :java/message (nil
  when Java's getMessage was null) and :java/string, its toString. Anything
  the bridge itself refused, a missing class or a wrong argument count, carries
  :openldk/error instead. When one of the exception's causes is a callback's
  throw, :openldk/callback-error carries its error id, for the client to swap
  for the original throwable."
  [text]
  (let [[status a b c d :as reply] (edn/read-string text)]
    (case status
      :ok (lisp->value a)
      :throw (throw (ex-info c (cond-> {:java/class a
                                        :java/message (when (string? b) b)
                                        :java/string c}
                                 (integer? d) (assoc :openldk/callback-error d))))
      :error (throw (ex-info (str "jolt-openldk: " a) {:openldk/error a}))
      (throw (ex-info "jolt-openldk: an unreadable reply" {:reply reply :text text})))))
