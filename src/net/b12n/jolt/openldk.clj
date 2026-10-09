(ns net.b12n.jolt.openldk
  "Java from jolt, with no JVM: OpenLDK translates bytecode to Common Lisp and
  SBCL compiles it, inside this process.

    (openldk/init! {:classpath [\"classes\" \"lib/some.jar\"]})
    (openldk/call-static \"java.lang.Math\" \"sqrt\" \"(D)D\" 2.0)   ;=> 1.4142135623730951
    (openldk/with-ref [xs (openldk/new-object \"java.util.ArrayList\" \"()V\")]
      (openldk/call xs \"add\" \"(Ljava/lang/Object;)Z\" \"a\")
      (openldk/call xs \"toString\" \"()Ljava/lang/String;\"))      ;=> \"[a]\"

  Methods are named the JNI way, name plus descriptor, because Java overloads
  and the descriptor is what tells \"add(I)\" from \"add(Ljava/lang/Object;)\".
  Its return type also says how to hand the result back: Z is a boolean, C a
  char.

  Scalars, strings and boxed scalars come back as Clojure values. Every other
  object comes back as a JavaRef, a handle that keeps the object alive until
  `release!`. A Java exception is an ex-info with :java/class, :java/message
  and :java/string.

  One OpenLDK per process, set up once with one classpath, and no shutdown.
  Calls are serialised: OpenLDK's own thread-safety is not established, so
  two jolt threads take turns rather than meeting inside it."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [net.b12n.jolt.openldk.wire :as wire]))

(ffi/defcfn ^:private ldk-init "ldk_init" [:pointer] :int :blocking)
;; :blocking because a call can run for seconds: the first call into a class
;; JIT-compiles it, and a jolt thread inside an unmarked foreign call holds
;; every other thread's collection until it returns. That rules out :string
;; arguments, which the collector could move mid-call, so strings cross as
;; arena-owned pointers.
(ffi/defcfn ^:private ldk-call "ldk_call" [:pointer :pointer] :int :blocking)
(ffi/defcfn ^:private ldk-free "ldk_free" [:pointer] :void)

(def ^:private ext
  (if (str/includes? (System/getProperty "os.name") "Mac") "dylib" "so"))

(def ^:private lib-name (str "libjoltopenldk." ext))

(defn dist-dir
  "Where bridge/build.sh put its output: (:home opts), else $JOLT_OPENLDK_HOME,
  else ~/.cache/jolt-openldk, each with /dist appended."
  ([] (dist-dir {}))
  ([{:keys [home]}]
   (str (or home
            (System/getenv "JOLT_OPENLDK_HOME")
            (str (System/getProperty "user.home") "/.cache/jolt-openldk"))
        "/dist")))

(defn built?
  "Is there a complete build in `dist`?"
  [dist]
  (every? #(.exists (io/file dist %)) [lib-name "openldk.core" (str "libsbcl." ext)]))

(defonce ^:private state (atom nil))
(def ^:private call-lock (Object.))

(defn- send!
  "One request across, one reply back, decoded."
  [request]
  (locking call-lock
    (ffi/with-arena [a]
      (let [out (ffi/alloc a 8)
            rc (ldk-call (ffi/string->ptr a request) out)]
        (when-not (zero? rc)
          (throw (ex-info (str "jolt-openldk: the bridge could not produce a reply (status " rc ")")
                          {:rc rc :request request})))
        (let [p (ffi/read out :pointer 0)
              text (try (ffi/ptr->string p) (finally (ldk-free p)))]
          (wire/reply->value text))))))

(defn- classpath-string [cp]
  (cond (nil? cp) "."
        (string? cp) cp
        :else (str/join ":" cp)))

(def ^:private init-codes
  {-1 "SBCL failed to start on the core"
   -2 "the core file could not be opened"
   -3 "the shim could not make itself RTLD_GLOBAL"
   -4 "the core did not export ldk_entry: was it built by bridge/build.sh?"})

(defn init!
  "Start SBCL on the OpenLDK core and set the classpath. Idempotent for the
  same classpath; OpenLDK sets its classpath once, so a different one throws.

  opts: :classpath (a string, or a seq of directories and jars), :home (the
  JOLT_OPENLDK_HOME to use). OpenLDK reads the JDK's class library at run time,
  so JAVA_HOME must name a JDK 25."
  ([] (init! {}))
  ([{:keys [classpath] :as opts}]
   (let [cp (classpath-string classpath)]
     (if-let [{:keys [classpath]} @state]
       (if (= cp classpath)
         true
         (throw (ex-info (str "jolt-openldk: already set up with classpath " classpath
                              "; OpenLDK sets its classpath once per process")
                         {:classpath classpath :asked cp})))
       (let [dist (dist-dir opts)
             java-home (System/getenv "JAVA_HOME")]
         (when-not (built? dist)
           (throw (ex-info (str "jolt-openldk: no build in " dist "; run bridge/build.sh, or set JOLT_OPENLDK_HOME")
                           {:dist dist})))
         ;; Checked here because OpenLDK's own check, at setup, ends in a Lisp
         ;; error message about a missing directory rather than this one.
         (when-not (and java-home (.exists (io/file java-home "release")))
           (throw (ex-info "jolt-openldk: JAVA_HOME must name a JDK 25; OpenLDK reads its class library"
                           {:java-home java-home})))
         (ffi/load-library (str dist "/" lib-name))
         (let [rc (ffi/with-arena [a] (ldk-init (ffi/string->ptr a (str dist "/openldk.core"))))]
           (when-not (#{0 1} rc)
             (throw (ex-info (str "jolt-openldk: ldk_init failed: " (init-codes rc (str "status " rc)))
                             {:rc rc :dist dist}))))
         (send! (wire/setup cp))
         (reset! state {:classpath cp :dist dist})
         true)))))

(defn initialized? [] (some? @state))

(defn ref? [x] (wire/ref? x))

(defn- dotted [class-name] (str/replace class-name "/" "."))

(defn call-static
  "Call static `method` with JNI `descriptor` on class `class-name` (dotted or
  slashed): (call-static \"java.lang.Integer\" \"parseInt\" \"(Ljava/lang/String;)I\" \"42\")."
  [class-name method descriptor & args]
  (send! (wire/static (dotted class-name) method descriptor args)))

(defn new-object
  "Construct `class-name` through the constructor `descriptor` (its return type
  is V): (new-object \"java.util.ArrayList\" \"(I)V\" 10). Returns a JavaRef."
  [class-name descriptor & args]
  (send! (wire/new-object (dotted class-name) descriptor args)))

(defn call
  "Call instance `method` on JavaRef `obj`, dispatched virtually as Java would."
  [obj method descriptor & args]
  (when-not (ref? obj)
    (throw (ex-info "jolt-openldk: call needs a JavaRef as its receiver" {:receiver obj})))
  (send! (wire/invoke obj method descriptor args)))

(defn class-name
  "The runtime class of `obj`, dotted."
  [obj]
  (send! (wire/class-name obj)))

(defn to-string [obj] (call obj "toString" "()Ljava/lang/String;"))

(defn run-main
  "Run `class-name`'s public static void main(String[]) with string `args`."
  [class-name & args]
  (send! (wire/run-main (dotted class-name) (map str args))))

(defn release!
  "Let OpenLDK collect the object behind `obj`. Using it afterwards, or
  releasing it twice, throws."
  [obj]
  (when (ref? obj) (send! (wire/release obj)))
  nil)

(defmacro with-ref
  "Bind each name to a JavaRef-producing expression, run body, and release
  every one, on the throwing path too."
  [bindings & body]
  (if (empty? bindings)
    `(do ~@body)
    (let [[sym expr & more] bindings]
      `(let [~sym ~expr]
         (try
           (with-ref [~@more] ~@body)
           (finally (release! ~sym)))))))
