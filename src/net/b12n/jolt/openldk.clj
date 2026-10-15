;; SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0

(ns net.b12n.jolt.openldk
  "Java from jolt, with no JVM: OpenLDK translates bytecode to Common Lisp and
  SBCL compiles it, inside this process.

    (openldk/init!)   ; classpath from :jolt-openldk/classpath in deps.edn,
                      ; else the jars in jvm/
    (openldk/init! {:classpath [\"classes\" \"lib/some.jar\"]})   ; or say so
    (openldk/call-static \"java.lang.Math\" \"sqrt\" \"(D)D\" 2.0)   ;=> 1.4142135623730951
    (openldk/with-ref [xs (openldk/new-object \"java.util.ArrayList\" \"()V\")]
      (openldk/call xs \"add\" \"(Ljava/lang/Object;)Z\" \"a\")
      (openldk/call xs \"toString\" \"()Ljava/lang/String;\"))      ;=> \"[a]\"

  Methods are named the JNI way, name plus descriptor, because Java overloads
  and the descriptor is what tells \"add(I)\" from \"add(Ljava/lang/Object;)\".
  Its return type also says how to hand the result back: Z is a boolean, C a
  char.

  Scalars, strings and boxed scalars come back as Clojure values. A Clojure
  vector passed for an array parameter becomes a Java array, and so does a jolt
  typed array (int-array, byte-array ...) passed for a primitive one. A
  primitive array's elements cross as raw memory rather than text. Every other
  object, returned arrays included, comes back as a JavaRef, a handle that
  keeps the object alive until `release!`; read an array with `array->vec`, or
  a numeric one with `array->primitive`. A Java exception is an ex-info with
  :java/class, :java/message and :java/string.

  One OpenLDK per process, set up once with one classpath, and no shutdown.
  Calls are serialised: OpenLDK's own thread-safety is not established, so
  two jolt threads take turns rather than meeting inside it."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jolt.ffi :as ffi]
            [net.b12n.jolt.openldk.manifest :as manifest]
            [net.b12n.jolt.openldk.project :as project]
            [net.b12n.jolt.openldk.wire :as wire]))

(ffi/defcfn ^:private ldk-init "ldk_init" [:pointer] :int :blocking)
;; :blocking because a call can run for seconds: the first call into a class
;; JIT-compiles it, and a jolt thread inside an unmarked foreign call holds
;; every other thread's collection until it returns. That rules out :string
;; arguments, which the collector could move mid-call, so strings cross as
;; arena-owned pointers.
(ffi/defcfn ^:private ldk-call "ldk_call" [:pointer :pointer] :int :blocking)
(ffi/defcfn ^:private ldk-free "ldk_free" [:pointer] :void)
(ffi/defcfn ^:private ldk-set-upcall "ldk_set_upcall" [:pointer] :void)
(ffi/defcfn ^:private ldk-strdup "ldk_strdup" [:pointer] :pointer)

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

;; --- Java calling Clojure: the fn table, the error table, the trampoline ------------
;;
;; One ffi/callback, made at startup and handed to the shim, carries every call
;; Java makes into Clojure. A proxy names its fn by id. A fn that throws leaves
;; its throwable here under an error id that Java's RuntimeException carries in
;; its message, so when that exception comes back out through a call, the
;; original is rethrown, or becomes the cause of the wrapper Java put around
;; it. The table keeps the newest 256, because Java may
;; swallow the exception and never bring it back.

(def ^:private error-table-bound 256)
(defonce ^:private next-id (atom 0))
(defonce ^:private fns (atom {}))
(defonce ^:private errors (atom (sorted-map)))

(defn- keep-error! [t]
  (let [id (swap! next-id inc)]
    (swap! errors (fn [m]
                    (let [m (assoc m id t)]
                      (if (> (count m) error-table-bound) (dissoc m (first (keys m))) m))))
    id))

(defn- take-error! [id]
  (let [t (get @errors id)]
    (swap! errors dissoc id)
    t))

(defn- dispatch
  "The fn registered as `fn-id` for Java `method` `descriptor`: a map is looked
  up by \"name(descriptor)\", then by name; a fn takes every method."
  [fn-id method descriptor]
  (let [impl (get @fns fn-id)]
    (cond
      (nil? impl) (throw (ex-info (str "callback " fn-id " was released") {:fn-id fn-id}))
      (map? impl) (or (get impl (str method descriptor))
                      (get impl method)
                      (throw (ex-info (str "no fn for " method descriptor " in the implementation map")
                                      {:method method :descriptor descriptor})))
      :else impl)))

(defn- answer
  "The reply text for one callback request. Nothing escapes."
  [request]
  (try
    (let [{:keys [fn-id method descriptor args]} (wire/parse-call request)
          f (dispatch fn-id method descriptor)]
      (try
        (wire/ok-reply descriptor (apply f args))
        (catch Throwable t
          (wire/throw-reply (or (ex-message t) (str t)) (keep-error! t)))))
    (catch Throwable t
      (wire/error-reply (str "jolt-openldk callback: " (or (ex-message t) t))))))

(defn- upcall-trampoline
  "The body of the one ffi/callback: read a request, write a reply the shim
  allocated (ldk_strdup), so bridge.lisp frees it with the same malloc."
  [request-ptr out-ptr]
  (try
    (let [reply (answer (ffi/ptr->string request-ptr))]
      (ffi/with-arena [a]
        (ffi/write out-ptr :pointer (ldk-strdup (ffi/string->ptr a reply)) 0))
      0)
    (catch Throwable _ 1)))

(defonce ^:private trampoline-addr (atom nil))

(defn- install-trampoline!
  "Make the callback once and hand it to the shim. :collect-safe because Java
  threads OpenLDK started call it too, and jolt never started those."
  []
  (or @trampoline-addr
      (let [cb (ffi/callback (ffi/global-arena) upcall-trampoline [:pointer :pointer] :int :collect-safe)
            addr (if (integer? cb) cb (ffi/address cb))]
        (ldk-set-upcall addr)
        (reset! trampoline-addr addr))))

(defn- rethrow-original
  "If `e` is the very RuntimeException a callback's throw turned into, the
  original Clojure throwable, still in the error table. If Java wrapped that
  RuntimeException, `e` with the original as its cause. Otherwise `e`. A
  wrapper that only quotes the message gets no cause."
  [e]
  (let [{:java/keys [class message] cause-id :openldk/callback-error :as data} (ex-data e)]
    (or (when (= "java.lang.RuntimeException" class)
          (some-> (wire/callback-error-id message) take-error!))
        (when cause-id
          (ex-info (ex-message e) (dissoc data :openldk/callback-error) (take-error! cause-id)))
        e)))

(defn- copy-raw
  "The elements a wire/RawReply names, copied into a fresh jolt typed array,
as {:component letter :data array}. The bridge malloc'd them for this caller
(an empty array comes at address 0), so the memory is freed here whatever
happens to the copy, a reply this code can't read included."
  [{:keys [component address length]}]
  (let [unknown #(throw (ex-info (str "jolt-openldk: a raw reply with unknown component " component)
                                 {:component component}))]
    (try
      {:component component
       :data (if (zero? length)
               (case component
                 ("Z" "B") (byte-array 0) ("C" "S") (short-array 0) "I" (int-array 0)
                 "J" (long-array 0) "F" (float-array 0) "D" (double-array 0)
                 (unknown))
               (case component
                 ("Z" "B") (ffi/read-array address length)
                 ("C" "S") (ffi/read-array address :int16 length)
                 "I" (ffi/read-array address :int32 length)
                 "J" (ffi/read-array address :int64 length)
                 "F" (ffi/read-array address :float length)
                 "D" (ffi/read-array address :double length)
                 (unknown)))}
      (finally (when-not (zero? address) (ldk-free address))))))

(defn- send!
  "One request across, one reply back, decoded. `request` is the request text,
  or a fn of the call's arena that returns it, for a request that names memory
  in that arena (a staged primitive array). The arena closes after the reply,
  and the bridge has copied out of it by then."
  [request]
  (locking call-lock
    (ffi/with-arena [a]
      (let [request (if (string? request) request (request a))
            out (ffi/alloc a 8)
            rc (ldk-call (ffi/string->ptr a request) out)]
        (when-not (zero? rc)
          (throw (ex-info (str "jolt-openldk: the bridge could not produce a reply (status " rc ")")
                          {:rc rc :request request})))
        (let [p (ffi/read out :pointer 0)
              text (try (ffi/ptr->string p) (finally (ldk-free p)))
              v (try (wire/reply->value text)
                     (catch Exception e (throw (rethrow-original e))))]
          (if (instance? net.b12n.jolt.openldk.wire.RawReply v) (copy-raw v) v))))))

(defn project-classpath
  "What `init!` would use for `opts` from the current directory: a map of
  :classpath (a colon string), :entries and :source (:explicit, :deps-edn,
  :jvm-dir or :default), plus :libs when deps.edn has :jolt-openldk/deps. That
  key's coordinate jars come first on the classpath, so this call can download
  jars and can throw jolt's resolution error. See
  net.b12n.jolt.openldk.project/resolve-classpath."
  ([] (project-classpath {}))
  ([opts] (project/resolve-classpath (System/getProperty "user.dir") (:classpath opts))))

(defn missing-classpath-entries
  "The entries of classpath string `cp` that do not exist. OpenLDK accepts a
  missing entry at setup and fails on the first class load, by which time the
  classpath can no longer be changed, so init! checks first."
  [cp]
  (vec (remove #(.exists (io/file %)) (remove str/blank? (str/split cp #":")))))

(defn jdk-problem
  "Why `java-home` cannot serve OpenLDK, or nil. It needs a JDK 25 and its
  class library, as jmods/ or lib/modules. OpenLDK checks the class library
  itself, but by exiting the process, which here is the jolt process."
  [java-home]
  (let [release (some-> java-home (io/file "release"))]
    (cond
      (nil? java-home) "JAVA_HOME is not set"
      (not (.exists release)) (str java-home " has no release file, so it is not a JDK")
      (not (re-find #"(?m)^JAVA_VERSION=\"25" (slurp release)))
      (str java-home " is not a JDK 25; OpenLDK targets 25 only")
      (not (or (.isDirectory (io/file java-home "jmods"))
               (.exists (io/file java-home "lib" "modules"))))
      (str java-home " has neither jmods/ nor lib/modules, OpenLDK's two sources for the class library"))))

(def ^:private init-codes
  {-1 "SBCL failed to start on the core"
   -2 "the core file could not be opened"
   -3 "the shim could not make itself RTLD_GLOBAL"
   -4 "the core did not export ldk_entry: was it built by bridge/build.sh?"
   -5 "an earlier start failed part-way, and SBCL cannot be started twice; restart the process"})

(def ^:private init-lock (Object.))

(defn- start!
  "Everything init! does the first time. Any failure once SBCL has been asked
  to start is recorded, because nothing after that point can be retried in
  this process."
  [{:keys [classpath dist core]}]
  (when-not (built? dist)
    (throw (ex-info (str "jolt-openldk: no build in " dist "; run bridge/build.sh, or set JOLT_OPENLDK_HOME")
                    {:dist dist})))
  (when-let [why (jdk-problem (System/getenv "JAVA_HOME"))]
    (throw (ex-info (str "jolt-openldk: " why) {:java-home (System/getenv "JAVA_HOME")})))
  (when-let [missing (seq (missing-classpath-entries classpath))]
    (throw (ex-info (str "jolt-openldk: classpath entries do not exist: " (str/join ", " missing))
                    {:classpath classpath :missing (vec missing)})))
  (ffi/load-library (str dist "/" lib-name))
  (try
    (let [rc (ffi/with-arena [a] (ldk-init (ffi/string->ptr a core)))]
      (when-not (#{0 1} rc)
        (throw (ex-info (str "jolt-openldk: ldk_init failed: " (init-codes rc (str "status " rc)))
                        {:rc rc :dist dist}))))
    (install-trampoline!)
    (send! (wire/setup classpath))
    (reset! state {:phase :ready :classpath classpath :dist dist :core core})
    (catch Exception e
      (reset! state {:phase :failed :classpath classpath :dist dist :core core :error (ex-message e)})
      (throw e))))

;; --- the baked core ----------------------------------------------------------------

(def ^:private rebake "re-run: jolt -M -m net.b12n.jolt.openldk.bake")

(defn- baked-core
  "The path of this project's baked core when `opts` leaves the classpath to
  the project and the core exists, else nil."
  [root opts]
  (when (nil? (:classpath opts))
    (let [f (io/file root "target" "openldk" "openldk.core")]
      (when (.exists f) (.getPath f)))))

(defn- check-baked!
  "Refuse a baked core that is unfinished or no longer matches the project,
  before anything is loaded. A build that is missing is start!'s to report."
  [root {:keys [core dist]}]
  (when (and (not= core (str dist "/openldk.core")) (built? dist))
    (let [mf (io/file (.getParentFile (io/file core)) "bake-manifest.edn")]
      (when-not (.exists mf)
        (throw (ex-info (str "jolt-openldk: " core " has no bake-manifest.edn, so the bake did not finish; "
                             rebake)
                        {:core core})))
      (let [baked (edn/read-string (slurp mf))
            {:keys [entries classpath]} (project/resolve-classpath root nil)
            problems (manifest/problems baked {:entries entries :classpath classpath
                                               :warm (project/warm-classes root)
                                               :dist-manifest (edn/read-string (slurp (io/file dist "manifest.edn")))})]
        (when (seq problems)
          (throw (ex-info (str "jolt-openldk: the baked core is stale (" (str/join "; " problems) "); " rebake)
                          {:core core :problems problems})))))))

(defn status
  "Where init! stands: :phase (nil, :ready or :failed), :classpath, :dist and
  :core, the core file in use."
  []
  (select-keys @state [:phase :classpath :dist :core]))

(defn init!
  "Start SBCL on the OpenLDK core and set the classpath. Idempotent for the
  same classpath and build; OpenLDK sets its classpath once, so a different
  one throws, and so does any init! after a start that failed part-way.

  Without :classpath, a repeat call once set up only checks the build, never
  the project again. Also without :classpath, a core baked by `jolt -M -m net.b12n.jolt.openldk.bake`
  into target/openldk of the current directory is used instead of the shared
  one, and init! refuses it, naming what changed, when the project's classpath
  or the build no longer match it. An explicit :classpath always uses the
  shared core.

  opts: :classpath (a string, or a seq of directories and jars, all of which
  must exist), :home (the JOLT_OPENLDK_HOME to use). Without :classpath, the
  first source that applies is the whole classpath: a :jolt-openldk/classpath
  key in deps.edn that is present (even if empty), or a :jolt-openldk/deps map
  of Maven coordinates whose jars go first (this can download them), else jvm/ when it holds
  top-level jars or class files anywhere under it, else \".\". Nothing merges, and relative deps.edn
  entries resolve against the current directory. OpenLDK reads the JDK's
  class library at run time, so JAVA_HOME must name a JDK 25."
  ([] (init! {}))
  ([opts]
   (let [root (System/getProperty "user.dir")
         dist (dist-dir opts)]
     (locking init-lock
       (let [{:keys [phase error] :as current} @state
             mismatch (fn [want]
                        (throw (ex-info (str "jolt-openldk: already set up with classpath " (:classpath current)
                                             " from " (:dist current) " on core " (:core current)
                                             "; OpenLDK is set up once per process")
                                        {:current (select-keys current [:classpath :dist :core]) :asked want})))
             want (fn []
                    {:classpath (:classpath (project/resolve-classpath root (:classpath opts)))
                     :dist dist
                     :core (or (baked-core root opts) (str dist "/openldk.core"))})]
         (case phase
           nil (let [want (want)] (check-baked! root want) (start! want))
           ;; Without :classpath the project is not consulted again once set up:
           ;; a bake finishing or deps.edn changing since must not fail a repeat call.
           :ready (if (nil? (:classpath opts))
                    (when (not= dist (:dist current))
                      (mismatch {:dist dist}))
                    (let [want (want)]
                      (when (not= want (select-keys current [:classpath :dist :core]))
                        (mismatch want))))
           :failed (throw (ex-info (str "jolt-openldk: an earlier init! failed after SBCL was started ("
                                        error "); restart the process")
                                   {:error error})))
         true)))))

(defn initialized? [] (= :ready (:phase @state)))

(defn ref? [x] (wire/ref? x))

;; --- primitive arrays as raw memory --------------------------------------------------
;;
;; A vector or jolt typed array headed for a primitive array is written into the
;; call's arena and named by address, as a wire/RawArray. It is written wide,
;; never narrowed (integers as int64, floating values as float64, chars as int32
;; code points), because bridge.lisp checks each element against the component
;; and words the errors as it does for text. A vector that mixes kinds, or holds
;; a nil, a string or an integer past a long, isn't staged and crosses as text.

(def ^:private primitive-letters #{"Z" "B" "C" "S" "I" "J" "F" "D"})

(defn- fits-long? [x] (and (integer? x) (<= Long/MIN_VALUE x Long/MAX_VALUE)))

(defn- write-raw [arena carrier width n write!]
  (if (zero? n)
    (wire/->RawArray carrier 0 0)
    (let [p (ffi/alloc arena (* width n))]
      (write! p)
      (wire/->RawArray carrier p n))))

(defn- stage
  "`value` written into `arena` as a RawArray for a primitive array whose
  component is the one-letter descriptor `component`, or nil when it isn't a
  vector or typed array that fits one carrier. The component isn't consulted
  beyond that: the bridge checks every element against it."
  [arena _component value]
  (if-let [kind (wire/primitive-array-kind value)]
    (let [n (alength value)]
      (case kind
        :byte (write-raw arena :i8 1 n #(ffi/write-array % value))
        :short (write-raw arena :i16 2 n #(ffi/write-array % :int16 value))
        :int (write-raw arena :i32 4 n #(ffi/write-array % :int32 value))
        :long (write-raw arena :i64 8 n #(ffi/write-array % :int64 value))
        ;; Widened, exactly: jolt 0.8.20's write-array :float refuses the
        ;; Floats a float-array holds.
        :float (write-raw arena :f64 8 n #(ffi/write-array % :double (double-array (map double value))))
        :double (write-raw arena :f64 8 n #(ffi/write-array % :double value))
        :char (write-raw arena :c32 4 n #(ffi/write-array % :int32 (int-array (map int value))))
        :boolean (write-raw arena :z8 1 n #(ffi/write-array % (byte-array (map (fn [z] (if z 1 0)) value))))))
    (when (vector? value)
      (let [n (count value)]
        (cond
          (zero? n) (wire/->RawArray :i64 0 0)
          (every? fits-long? value)
          (write-raw arena :i64 8 n #(ffi/write-array % :int64 (long-array value)))
          (every? #(or (double? %) (float? %)) value)
          (write-raw arena :f64 8 n #(ffi/write-array % :double (double-array value)))
          (every? boolean? value)
          (write-raw arena :z8 1 n #(ffi/write-array % (byte-array (map (fn [z] (if z 1 0)) value))))
          (every? char? value)
          (write-raw arena :c32 4 n #(ffi/write-array % :int32 (int-array (map int value))))
          :else nil)))))

(defn- primitive-array-param [param]
  (when (and (= 2 (count param)) (= \[ (first param)))
    (primitive-letters (subs param 1))))

(defn- stage-args
  "`args` with each one bound for a one-dimensional primitive array parameter
  of `descriptor` staged in `arena` where it fits. When the descriptor doesn't
  split, or the count differs, nothing is staged and bridge.lisp reports it;
  a typed array then goes as its elements so that report is the one you see."
  [arena descriptor args]
  (let [params (wire/split-descriptor descriptor)]
    (if (and params (= (count params) (count args)))
      (mapv (fn [param arg]
              (if-let [letter (primitive-array-param param)]
                (or (stage arena letter arg) arg)
                arg))
            params args)
      (mapv #(if (wire/primitive-array-kind %) (vec %) %) args))))

(defn- dotted [class-name] (str/replace class-name "/" "."))

(defn call-static
  "Call static `method` with JNI `descriptor` on class `class-name` (dotted or
  slashed): (call-static \"java.lang.Integer\" \"parseInt\" \"(Ljava/lang/String;)I\" \"42\")."
  [class-name method descriptor & args]
  (send! (fn [a] (wire/static (dotted class-name) method descriptor (stage-args a descriptor args)))))

(defn new-object
  "Construct `class-name` through the constructor `descriptor` (its return type
  is V): (new-object \"java.util.ArrayList\" \"(I)V\" 10). Returns a JavaRef."
  [class-name descriptor & args]
  (send! (fn [a] (wire/new-object (dotted class-name) descriptor (stage-args a descriptor args)))))

(defn call
  "Call instance `method` on JavaRef `obj`, dispatched virtually as Java would."
  [obj method descriptor & args]
  (when-not (ref? obj)
    (throw (ex-info "jolt-openldk: call needs a JavaRef as its receiver" {:receiver obj})))
  (send! (fn [a] (wire/invoke obj method descriptor (stage-args a descriptor args)))))

(defn class-name
  "The runtime class of `obj`, dotted."
  [obj]
  (send! (wire/class-name obj)))

(defn to-string [obj] (call obj "toString" "()Ljava/lang/String;"))

;; --- arrays ---------------------------------------------------------------------
;;
;; A vector passed where a descriptor says [ becomes a Java array of that
;; component type on the way in, so most calls need none of these. They are for
;; the other direction, and for an array you want to keep and watch Java change.

(defn new-array
  "A Java array with component descriptor `component` (\"I\", \"D\",
  \"Ljava/lang/String;\", \"[I\" for int[][]) holding `values`, as a JavaRef.
  Each value converts as an argument of the component type would. `values` may
  also be a jolt typed array (int-array, byte-array ...), which converts like a
  vector of its elements."
  [component values]
  (send! (fn [a]
           (wire/new-array component (or (when (primitive-letters component) (stage a component values))
                                         values)))))

(defn array->vec
  "The elements of Java array `arr` as a vector, converted the way a returned
  value of the component type is: int[] to longs, boolean[] to booleans,
  char[] to chars, String[] to strings. Elements that are other objects,
  nested arrays included, come back as JavaRefs to release."
  [arr]
  (let [r (send! (wire/elements arr))]
    (if (map? r)
      (let [{:keys [component data]} r]
        (case component
          "Z" (mapv #(not (zero? %)) data)
          "C" (mapv (fn [x] (let [u (bit-and x 0xFFFF)] (if (<= 0xD800 u 0xDFFF) u (char u)))) data)
          ("F" "D") (mapv double data)
          (mapv long data)))
      r)))

(defn array->primitive
  "The elements of numeric Java array `arr` as a fresh jolt typed array:
  byte[] to a byte-array, short[] to a short-array, int[] to an int-array,
  long[] to a long-array, float[] to a float-array, double[] to a
  double-array. A copy: changing it doesn't change the Java array. char[] is
  refused, since a jolt char can't hold half a surrogate pair, and boolean[]
  too, since no jolt typed array holds booleans; array->vec reads both as
  fast."
  [arr]
  (let [cls (class-name arr)]
    (cond
      (#{"[C" "[Z"} cls)
      (throw (ex-info (str "jolt-openldk: array->primitive takes a numeric array; use array->vec for a "
                           (if (= "[C" cls) "char[]" "boolean[]"))
                      {:class cls}))
      (and (str/starts-with? cls "[") (not (#{"[B" "[S" "[I" "[J" "[F" "[D"} cls)))
      (throw (ex-info (str "jolt-openldk: array->primitive takes a numeric array, not a " cls)
                      {:class cls}))
      :else (:data (send! (wire/elements arr))))))

(defn array-length [arr] (send! (wire/array-length arr)))

(defn run-main
  "Run `class-name`'s public static void main(String[]) with string `args`."
  [class-name & args]
  (send! (wire/run-main (dotted class-name) (map str args))))

(defn release!
  "Let OpenLDK collect the object behind `obj`. Using it afterwards, or
  releasing it twice, throws. Releasing a proxy from `implement` also drops
  its fn, so Java calling it later gets a RuntimeException."
  [obj]
  (when (ref? obj)
    (try (send! (wire/release obj))
         (finally (when-let [id (:fn-id obj)] (swap! fns dissoc id)))))
  nil)

;; --- Java calling Clojure ---------------------------------------------------------------

(defn implement
  "A Java object implementing `interfaces` (one name, or a vector of them) whose
  methods call Clojure, as a JavaRef to pass wherever Java wants one.

  `impl` is a fn, called with the method's arguments for every method (the
  usual case, a functional interface such as Runnable or Comparator), or a map
  from method name, or name plus descriptor for an overload, to fn:

    (implement \"java.util.Comparator\" (fn [a b] (compare b a)))
    (implement \"java.util.Iterator\" {\"hasNext\" (fn [] ...) \"next\" (fn [] ...)})

  Arguments arrive converted like returned values. Objects among them are
  JavaRefs borrowed for the call only: they are released when the fn returns,
  so copy what you need out of them first. The fn's result converts by the
  method's return type, like an argument. If the fn throws, Java sees a
  RuntimeException carrying its message, and if that exception comes back out
  through the call Clojure made, the original throwable is rethrown.

  The fn may call back into Java on the same thread. A fn that Java runs on
  another thread while this one waits inside a call must not call into Java:
  calls take one lock, and the waiting call holds it."
  [interfaces impl]
  (let [ifaces (if (string? interfaces) [interfaces] (vec interfaces))
        id (swap! next-id inc)]
    (swap! fns assoc id impl)
    (try
      (assoc (send! (wire/proxy-request ifaces id)) :fn-id id)
      (catch Throwable t
        (swap! fns dissoc id)
        (throw t)))))

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
