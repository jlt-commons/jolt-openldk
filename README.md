# jolt-openldk

Java libraries from [jolt](https://github.com/jolt-lang/jolt), with no JVM in the process.

[OpenLDK](https://github.com/atgreen/openldk) is Anthony Green's Java runtime written in Common Lisp. It reads `.class` files, translates the bytecode into Lisp and lets SBCL compile that to machine code. This project loads SBCL as a shared library into a jolt process, starts it on a core with OpenLDK already warmed up, and gives Clojure a small API for calling into it.

```clojure
(require '[net.b12n.jolt.openldk :as ldk])

(ldk/init! {:classpath ["classes" "lib/some.jar"]})

(ldk/call-static "java.lang.Math" "sqrt" "(D)D" 2.0)          ;=> 1.4142135623730951

(ldk/with-ref [xs (ldk/new-object "java.util.ArrayList" "()V")]
  (ldk/call xs "add" "(Ljava/lang/Object;)Z" "a")
  (ldk/call xs "add" "(Ljava/lang/Object;)Z" 2)
  (ldk/to-string xs))                                          ;=> "[a, 2]"
```

**Status: an experiment, one day old.** It has run on one machine, macOS arm64 with jolt 0.8.19, SBCL 2.6.9 and JDK 25.0.2, on 2026-10-09. Linux is written for, but nothing has been compiled or run there. The API will change.

## Is this the right tool?

Probably not, if what you want is a Java library at full speed. A JVM over JNI is faster, far more complete, and what everyone else uses. OpenLDK says so about itself: it is for when you are in Lisp and need that one Java library, and it isn't trying to compete with HotSpot.

The reasons to want it anyway are narrower. Nothing here starts a JVM, so there is no second runtime with its own heap tuning and startup cost, and the whole thing lives in one process that you control. The catch is that it still reads the JDK's class library at run time, so a JDK 25 has to be installed even though it never runs.

## How it fits together

```
jolt process
  net.b12n.jolt.openldk     Clojure API: init!, call-static, new-object, call, release!
  net.b12n.jolt.openldk.wire     request text out, reply text back (pure, tested alone)
        │  ldk_call(request, &reply)        jolt.ffi, by name
        ▼
  libjoltopenldk.dylib      bridge/ldk.c: ldk_init, ldk_call, ldk_free
        │  function pointer SBCL filled in at startup
        ▼
  libsbcl.dylib + openldk.core
        bridge/bridge.lisp  reads the request, calls OpenLDK, prints the reply
        OpenLDK             JIT-translates each method on its first call
        JDK 25 class files  read from $JAVA_HOME/jmods
```

Three things make the middle layer work, and each one cost a failed run to find.

1. SBCL can start from a library. `save-lisp-and-die :callable-exports '(ldk_entry)` saves a core with no toplevel. When `initialize_lisp` starts it, SBCL writes the address of the `ldk_entry` callable into the C global of that name and returns to the caller rather than starting a REPL.
2. SBCL finds that global with a process-wide `dlsym`. `jolt.ffi/load-library` loads `RTLD_LOCAL`, which hides it, and SBCL then dies at startup with `UNDEFINED-ALIEN-VARIABLE-ERROR` and takes jolt down with it. So `ldk_init` first re-opens itself and `libsbcl` with `RTLD_NOLOAD | RTLD_GLOBAL`.
3. jolt binds C functions by name and can't call through a pointer, so `ldk_call` is a named C function that calls the pointer.

Every request is one line of text. Strings, numbers and booleans travel as tagged Lisp forms, `(:static "java.lang.Math" "sqrt" "(D)D" ((:d 2.0)))`. The reply is printed so that `clojure.edn` reads it unchanged, `(:ok (:d 1.4142135623730951))`. The bridge reads requests with `*read-eval*` off and interns nothing outside KEYWORD. `wire.clj` has the full grammar in its docstring.

## Building

```sh
export JAVA_HOME=/path/to/jdk-25      # mise: ~/.local/share/mise/installs/java/25.0.2
bb build                              # runs bridge/build.sh
```

`bridge/build.sh` needs `sbcl` (any recent one, used only to compile SBCL itself), `ocicl`, `git` and `cc` on PATH. It writes nothing outside `~/.cache/jolt-openldk`, or `$JOLT_OPENLDK_HOME` if that is set. It:

- clones SBCL 2.6.9 and builds it with its runtime as `libsbcl`. Homebrew's SBCL isn't built that way, so there is no shortcut.
- clones OpenLDK at commit `23da184e`, installs its dependencies with ocicl, and dumps `openldk.core` with `bridge/bridge.lisp` loaded.
- compiles the shim, and writes `manifest.edn` naming what went in.

From a clean cache it took about three and a half minutes here, two of them for SBCL, which is built once and reused after that. Set `OPENLDK_SRC=/path/to/checkout` to clone OpenLDK from a local copy instead of GitHub. The checkout itself is never modified.

Behind an HTTPS proxy that can't tunnel to ghcr.io, `ocicl install` fails with `Unable to establish HTTPS tunnel through proxy`. Run the build with the proxy variables unset.

The result in `dist/` is a 146 MB core, a 412 KB `libsbcl.dylib` and a 34 KB shim.

## Using it

`init!` starts SBCL and sets the classpath, which OpenLDK only accepts once per process. Calling it again with the same classpath does nothing. Calling it with a different one throws. Before starting anything it checks that JAVA_HOME is a JDK 25 with its class library and that every classpath entry exists, because OpenLDK reacts to the first by exiting the process and to the second by failing on the first class load, after the classpath can no longer change. If `init!` fails after SBCL was started, later calls say so and the process has to be restarted, since SBCL can't be started twice.

Methods are named the way JNI names them, a name plus a descriptor, because Java overloads. The descriptor's return type also tells the bridge how to hand back the result:

| Java | crosses as | comes back as |
|---|---|---|
| `int` `long` `short` `byte` | Clojure integer, range-checked for the parameter | long |
| `double` `float` | Clojure double; NaN and infinities included | double (a float is widened, so `1.0f/3` reads `0.3333333432674408`) |
| `boolean` | `true` / `false` | `true` / `false` |
| `char` | Clojure char up to U+FFFF; past that it doesn't fit one Java char and is refused | char, except half a surrogate pair, which a jolt char can't hold: that comes back as its integer code |
| `String` | Clojure string, any Unicode except NUL, which would end the C string the request travels in | string, NUL and control characters included; a lone surrogate, legal in Java, becomes U+FFFD |
| `Integer` `Long` `Double` `Boolean` `Character` returned as objects | | unboxed to the Clojure value |
| a scalar or string where a reference type is expected | boxed as Java would: an integer as `Long` (or `Integer` for an `Integer` parameter), a double as `Double`, then `Boolean`, `Character`, `String`. Only where that box fits the parameter (`Object`, `Number`, `Comparable`, `Serializable`, `CharSequence` or the box itself); anything else is refused | |
| an array (`[I`, `[Ljava/lang/String;`, `[[I` ...) | a Clojure vector, each element converted and checked as an argument of the component type, with the element's index in any error; or a JavaRef to an array | a JavaRef; read it with `array->vec` |
| anything else | a JavaRef you got back earlier | a JavaRef |
| `null` | `nil` | `nil` |

A JavaRef keeps its object alive until `release!`. `with-ref` releases on the throwing path too. Every call that returns an object hands back a new handle, including one that returns the same object (`StringBuilder.append`), so release those as well.

Arrays go in as vectors and come back as handles, because an array is mutable and Java may keep it:

```clojure
(ldk/call-static "java.util.Arrays" "toString" "([I)Ljava/lang/String;" [3 1 2])  ;=> "[3, 1, 2]"

(ldk/with-ref [xs (ldk/new-array "I" [3 1 2])]   ; an int[] you keep
  (ldk/call-static "java.util.Arrays" "sort" "([I)V" xs)
  (ldk/array->vec xs))                            ;=> [1 2 3]
```

`array->vec` converts by component type: `int[]` and `long[]` to integers, `byte[]` to signed integers as in Java, `boolean[]` to booleans, `char[]` to chars, `String[]` to strings. `Object[]` elements convert like returned values, so strings and boxed scalars become values and any other object, a nested array included, becomes a JavaRef to release. Only a vector is taken for an array, never a seq. A `float[]` reads back widened to double, so `0.1f` arrives as `0.10000000149011612`, the same as a returned `float`. A vector inside an `Object[]` is refused, since nothing says what array it should become: build it with `new-array` and put the handle in instead. Descriptors, for `new-array` and in method descriptors, are parsed strictly, so `int`, `V` or a dotted `Ljava.lang.String;` is an error rather than a strange array.

A Java exception arrives as an `ex-info`:

```clojure
(ex-data e)
;=> #:java{:class "java.lang.IllegalArgumentException",
;          :message "negative: -1",
;          :string "java.lang.IllegalArgumentException: negative: -1"}
```

Mistakes on the Clojure side, such as a missing class, a wrong argument count, or 99999999999 passed to an `int`, carry `:openldk/error` instead.

`bb tour` runs `examples/tour`, which works through `Math`, `StringBuilder`, `TreeMap`, `java.time` and a class of its own. Its date arithmetic and hex output were cross-checked against `date` and `printf`, since OpenLDK's correctness is the thing on trial.

## What was measured

On the machine above, 2026-10-09:

- Starting SBCL on the core (`ldk_init`) took 47 ms. The tour's whole `init!`, which adds OpenLDK's classpath setup, took 244 ms.
- A call that is already compiled: about 87 µs (2,000 `Math.max` calls in 175 ms). Nobody has profiled where that goes. The request and reply text is the obvious suspect, and a binary encoding would be the first thing to try if it ever matters.
- A method's first call pays for its JIT translation. That is OpenLDK's cost and runs from milliseconds to seconds depending on how much of the JDK the method pulls in.
- jolt keeps working around it. Its collector, its other threads, and Ctrl-C (exit 130, the same as without OpenLDK) all behaved normally. A call from a second jolt thread works too.

## What doesn't work yet

- **Bulk arrays.** Every element crosses as text. Measured with 100,000 elements, a `byte[]` or an `int[]` took about 60 ms to build from a vector and 260 to 330 ms to read back across two runs, and came back equal. A million `int`s took 0.7 s in and 3.4 s out. Fine for configuration, slow for image data.
- **Java calling back into Clojure.** No interface can be implemented from Clojure, so no callbacks, listeners or lambdas.
- **Concurrency.** Calls are serialised behind one lock, because OpenLDK's own thread safety hasn't been looked at.
- **Some failures take the host down.** Java's `System.exit` exits the jolt process, since it is the only process there is (measured; `Runtime.halt` presumably does too, untested). So does SBCL on heap exhaustion or a corrupt core. `ldk_init` checks the core can be opened, and `init!` checks JAVA_HOME, before either reaches SBCL. Nothing else is covered. The heap is a fixed 8 GB reservation.
- **JavaRef arguments aren't class-checked.** Scalars, strings and vectors are checked against the parameter type, but a JavaRef goes to any reference parameter, so passing the wrong class surfaces later as whatever error the Java code raises.
- **OpenLDK's own gaps.** It's a young runtime. Two string bugs found while building this are filed upstream as [#13](https://github.com/atgreen/openldk/issues/13) and [#12](https://github.com/atgreen/openldk/issues/12), with notes in `docs/openldk-upstream-notes.md`. One is worked around here. The other, `("é✓".toUpperCase() + "!")` throwing a NullPointerException that escapes `catch (Throwable)`, is not.
- **stdout ordering.** Java's `System.out` and jolt's `*out*` share file descriptor 1 but buffer separately. The bridge flushes after every call, but output written during a call can still land before output jolt had buffered before it.

## Tests

```sh
bb test     # wire tests always; the OpenLDK tests skip without a build
bb gates    # lint, then all tests with JOLT_OPENLDK_REQUIRE=1 so a missing build fails, then the tour
```

Last run: wire tests 10 tests and 55 assertions, OpenLDK tests 15 tests and 121 assertions, all passing.

## Licence

Two licences, by file, with `NOTICE` saying which is which.

- `bridge/bridge.lisp` is GPL-3.0-or-later with the Classpath exception (`COPYING`, `COPYING.CLASSPATH-EXCEPTION`), the same as OpenLDK. It has to be, since it's loaded into OpenLDK's image and calls its internals.
- Everything else is EPL-2.0 (`LICENSE`), like jolt, with GPL-2.0-or-later plus the Classpath exception named as a Secondary License.

Every source file names its licence in an `SPDX-License-Identifier` line. `bridge/build.sh` downloads and builds SBCL and OpenLDK but copies neither into this repository, so its output in `dist/` carries their licences as well. `NOTICE` has the details.
