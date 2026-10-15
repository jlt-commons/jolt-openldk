# Changelog

## Unreleased

- `implement` resolves interface methods the way `subclass` does. When a
  default method overrides an abstract one inherited along another path, as
  in `interface LK extends L, K` where `K` gives `n()` a default, the default
  now runs. Before, the abstract method won and Java got the proxy's stub.

- `getClass()` on a proxy answers with `Object`'s `Class` instead of throwing
  `ClassNotFoundException`.

- `subclass` makes a Java subclass at run time whose methods call Clojure fns,
  for the Java that wants a class rather than an interface: `TimerTask`,
  `AbstractList` and the other collection skeletons, `InputStream`, `Writer`,
  `Number`, `Thread`. Abstract or concrete superclass, any constructor by
  descriptor. Each fn takes `this` first. Every abstract method is defined,
  and a map key naming several overloads, or a method that is missing, final,
  static or private, is refused when the subclass is made. `call-super` runs
  the superclass's version of a method, and `getClass()` answers with the
  superclass's `Class`.

- jolt 0.8.22 or later is required, and CI tests it. It's the release that
  lets the next two things happen.

- A coordinate under `:jolt-openldk/deps` now brings in its own dependencies,
  read from its POM (compile and runtime scope, optional ones skipped),
  through jolt.deps' new `:jvm-deps?` option. `httpclient` 4.5.14 now brings
  `httpcore`, `commons-logging` and `commons-codec` with it. A coordinate may
  carry `:exclusions [group/artifact ...]` to leave some out.

- A `float-array` passed for a `float[]` crosses as 4-byte floats again,
  rather than widened to doubles for jolt 0.8.20's sake. The values are the
  same.

- Runaway recursion in a callback is now a `StackOverflowError` (jolt 0.8.22),
  not heap exhaustion. Java gets a `RuntimeException` it can catch, and a call
  from Clojure rethrows the original. `bb test` sets `JOLT_STACK_LIMIT=256m` so
  the test for it ends quickly.

- A callback that returns a jolt typed array (`int-array` ...) now gets an
  error saying to return a vector or a JavaRef from `new-array`, instead of
  one worded for an argument. A `:raw` reply the client can't read is now an
  error naming its component, and its buffer is still freed.

- A JavaRef argument is now checked against its parameter's class, the way
  Java's `instanceof` decides, so passing an `ArrayList` handle where a
  `String` goes fails before Java runs, as an `:openldk/error` naming both.
  Array handles follow Java's covariance: a `String[]` passes for `Object[]`,
  an `int[]` doesn't. A callback's JavaRef result is checked against its
  return type the same way. Before, the wrong class surfaced later as
  whatever error the Java code raised.

- When Java wraps a callback's exception in another one, the `ex-info` you
  get for the wrapper now has your original throwable as its `ex-cause`,
  found anywhere down the cause chain. Before, you got the wrapper alone.

- Fixed: `Instant.now()` failed with `The function
  OPENLDK::|jdk/internal/misc/VM.getNanoTimeAdjustment(J)| is undefined`, and
  so did `Date.toString` in a time zone CLDR has no display name for
  (`GMT+07:00`, say). `bridge/bridge.lisp` now defines the native as HotSpot
  does. Sent upstream as atgreen/openldk#18.

- Fixed, on macOS: the first `java.util.zip` call (CRC32, Deflater, Inflater,
  GZIP) failed with `Error opening shared object "libz.so.1"`, a Lisp error
  that ended the process. OpenLDK loaded zlib by its Linux name.
  `bridge/bridge.lisp` now loads `libz.1.dylib` on macOS. Sent upstream as
  atgreen/openldk#17.

- jolt 0.8.20 or later is required (`:jolt/min-version` in `deps.edn`, up
  from 0.8.19), the version CI tests on every push. The tests did pass on
  0.8.19 on 2026-10-14, but nothing keeps checking it.

- Fixed, on macOS: the first use of `java.nio.file` (`Path.of`, `Files`), and
  `MessageDigest.getInstance`, failed with `The function
  OPENLDK::|jdk/internal/loader/NativeLibraries.load(...)| is undefined`.
  macOS's JDK loads a library without first checking for its file, and
  OpenLDK, whose natives are Lisp, had no `load` to answer it. `bridge/bridge.lisp`
  now defines it to report the library as not loaded, which the JDK treats as
  absent, as on Linux. Sent upstream as atgreen/openldk#16.

- Fixed: a string passed into Java with any character from U+0080 up came out
  of Java's UTF-8 encoder as its Latin-1 bytes (`"hé"` as `[104 -23]`, not
  `[104 -61 -87]`), and wasn't equal to the same text built in Java. OpenLDK
  stored a string's bytes unsigned where Java's `byte[]` is signed, and the
  JDK's encoder skips work when it sees no negative byte. `bridge/bridge.lisp`
  now replaces OpenLDK's `%string-value-bytes` and the String `print-object`
  with corrected copies, until the pin moves to an OpenLDK with the fix
  (sent upstream as atgreen/openldk#15).

- Primitive arrays cross as raw memory instead of text, in both directions.
  `new-array`, `array->vec` and a vector passed for a primitive array
  parameter all take the new path without any change to the calling code, and
  give the same results and the same error messages. A million `int`s now take
  130 to 150 ms to go in from a vector and 64 to 100 ms to read back with
  `array->vec`, against 0.7 s and 3.4 s before (`bb bench-arrays`, which is
  new). A vector mixing kinds, such as `[1 2.5]`, or holding a nil still
  crosses as text.
- `array->primitive` reads a numeric Java array into a jolt typed array: a
  `byte[]` into a `byte-array`, an `int[]` into an `int-array`, and so on. A
  million `int`s take about 9 ms. `char[]` and `boolean[]` are refused, so
  read those with `array->vec`.
- A jolt typed array (`int-array`, `byte-array`, `double-array` ...) is
  accepted wherever a primitive array is, as `new-array`'s contents and for a
  primitive array parameter, and converts like a vector of its elements.
  Anywhere else it is refused with a message saying where it can go.
- Fixed: a primitive array that OpenLDK built through `Array.newInstance`
  holds nils, which Java code reads as zeros. `array->vec` and
  `array->primitive` now read them as zeros too.

- Fixed: calling an instance method that OpenLDK fails to compile, one that
  uses `jsr` for instance, hung the process at full CPU instead of throwing.
  The stub meant to throw went onto the wrong generic function, and the
  method's lazy compile stub then called itself forever. `bridge/bridge.lisp`
  now replaces OpenLDK's `%install-jit-error-stub` with a corrected copy, so
  the call throws "JIT compilation failed for ...". On GraalVM CE 25.0.1 the
  jmod problem below turned a missing class into such a compile failure, which
  is how httpclient's `URIBuilder` without httpcore hung. With both fixes it
  throws `The function OPENLDK::|%clinit-org/apache/http/Consts| is undefined`,
  as it already did on Oracle's OpenJDK.
- Fixed: a JDK whose jmods count their zip offsets from the start of the file,
  as 30 of the 84 in GraalVM CE 25.0.1 do, failed zip's signature assertion on
  any lookup that reached one of them: a class in those modules, or a class
  that wasn't found at all, which is what made the compile above fail on that
  JDK. `bridge/bridge.lisp`
  replaces OpenLDK's `open-jmod-zipfile` with one that checks where each
  jmod's central directory is.

- Maven coordinates (jlt-commons/jolt-openldk#3). A project can name Java
  libraries under `:jolt-openldk/deps` in `deps.edn`, as a qualified symbol
  mapped to `{:mvn/version "..."}`, and `init!` resolves them to jars through
  jolt's resolver (jolt 0.8.19 or later, Clojars then Maven Central, cached in
  `~/.m2/repository` or `$JOLT_MAVEN_REPOSITORY`). Either that key or
  `:jolt-openldk/classpath` makes `deps.edn` the classpath source. Coordinate
  jars come first, then the `:jolt-openldk/classpath` entries, and
  `project-classpath` gains `:libs`. A coordinate that can't be resolved stops
  `init!` with an error naming it, before SBCL starts. Only the coordinates
  you list are fetched, not their dependencies, so list those too. `bake`
  covers the coordinate jars, and a version bump makes the baked core stale.
  An extra key in a coordinate's map, such as
  `:exclusions`, is rejected, and `:mvn/repos` and classifiers aren't supported.

- CI uses the Node 24 releases of `actions/checkout` (v7), `actions/setup-java`
  (v6) and `actions/cache` (v6). GitHub had started warning that the v4
  releases target the deprecated Node 20.
  The lint and Linux x86-64 jobs are pinned to `ubuntu-24.04`, so GitHub's
  move of `ubuntu-latest` to Ubuntu 26 doesn't change them unannounced.

- Baked cores (jlt-commons/jolt-openldk#3). `bb bake`, or
  `jolt -M -m net.b12n.jolt.openldk.bake` in a project root, translates the
  project's classpath, plus any JDK classes named under `:jolt-openldk/warm` in
  `deps.edn`, into `target/openldk/openldk.core`, and `init!` with no
  `:classpath` starts on it. `init!` refuses the core, naming what changed,
  when the classpath, any class or jar, the warm list or the shared build no
  longer matches, so a `bb build` means baking again. A failed bake leaves no
  core and `init!` falls back to the shared one. On an M1 Pro the tour bakes in
  about 10 s and commons-codec in about 13 s. A warmed `LocalDate.of` took
  300 ms on its first call against 441 ms unwarmed, and the first call into the
  tour's `Counter` dropped from 12 ms to 2 ms. Lambdas and JDK methods not
  named in `warm` still translate at run time.

- The shared core warms OpenLDK's `invokedynamic` path while it is built, so the
  first method with a string `+` no longer costs about 640 ms. A method with a
  lambda still pays about 110 ms on its first call in OpenLDK's lambda
  metafactory.
  The pre-dump cleanup is now `%ldk-prepare-dump` and also destroys
  OpenLDK's watchdog thread and resets its debug flags.

- `init!` finds the project's classpath when none is passed
  (jlt-commons/jolt-openldk#3). It takes the first that applies of an explicit
  `:classpath`, a `:jolt-openldk/classpath` vector in `deps.edn`, and the jars
  and class files in `jvm/`, falling back to `.`, and that source is the whole
  classpath. Relative `deps.edn` entries resolve against the current
  directory. `project-classpath` reports what `init!` would use and where it
  came from. The tour now keeps its classpath in its own `deps.edn`.

- OpenLDK pinned at `701609f`, which fixes both string bugs reported from
  here (atgreen/openldk#13 and #12): `lstring` misreading signed bytes, and a
  NullPointerException no Java `catch` could see, which made
  `"é✓".toUpperCase() + "!"` fail. A test now covers that concatenation.

- Moved to `jlt-commons/jolt-openldk` and made public.
- CI on GitHub Actions: lint, Linux x86-64, Linux arm64 (through
  `linux/Dockerfile`) and macOS arm64, each running the build, every test with
  skipping disallowed, and the tour. Its first run verified Linux x86-64 on
  real hardware, which local emulation could not. `build.sh` retries
  `ocicl install` and prints the end of a failed step's log.

- Linux. Verified on Linux arm64 (Ubuntu 26.04 in Docker): `bridge/build.sh`,
  every test with skipping disallowed, and the tour, with no change to the
  bridge, shim or Clojure code. It also covered OpenLDK reading the class
  library from `lib/modules`, since Temurin ships no `jmods`. `bb linux`
  builds `linux/Dockerfile` for the machine's architecture: on arm64 it builds
  jolt and ocicl from their tags (neither publishes arm64 Linux binaries); on
  amd64 it uses their pinned release binaries. It trusts the host's CA bundle,
  for TLS-inspecting proxies such as Zscaler, without committing it. Linux
  x86-64 is not verified: SBCL's cross-compiler stops under Docker's x86-64
  emulation on Apple silicon.
- README: an alpha notice, the architecture as Mermaid diagrams (layers, and
  one call with a callback), and a platforms table.

- Java calling Clojure. `implement` makes a Java object implementing one or
  more interfaces whose methods run Clojure fns, through one jolt
  `ffi/callback` installed at startup (`ldk_set_upcall`). Default methods
  work, object arguments are borrowed for the call, a thrown Clojure
  exception reaches Java as a RuntimeException and comes back out as the
  original, and threads Java starts can call in. About 49 µs per callback.

- Licensed. `bridge/bridge.lisp` is GPL-3.0-or-later with the Classpath
  exception, like OpenLDK; everything else is EPL-2.0 with GPL-2.0-or-later
  plus the Classpath exception as a Secondary License. `NOTICE` maps files
  to licences, and every source file carries an SPDX line.

- Arrays. A Clojure vector passed for an array parameter becomes a Java
  array of the descriptor's component type, nested vectors included.
  `new-array` makes one to keep, and `array->vec` and `array-length` read
  any Java array by its component type (signed bytes, booleans, chars,
  strings, handles for other objects).
- Arguments are checked against their parameter type: an array element's
  error names its index, a scalar boxes only where its box fits (an Integer
  parameter gets an Integer), and descriptors are parsed strictly, so `int`,
  `V` or `Ljava.lang.String;` fails instead of building a strange array.

## First cut

Nothing is tagged yet.

- `bridge/build.sh` builds SBCL 2.6.9 with its runtime as a shared library,
  OpenLDK at `23da184e`, a core with the bridge loaded, and the C shim, all
  into `~/.cache/jolt-openldk/dist`.
- `net.b12n.jolt.openldk`: `init!`, `call-static`, `new-object`, `call`,
  `class-name`, `to-string`, `run-main`, `release!`, `with-ref`. Methods are
  named by JNI descriptor. Scalars, strings and boxed scalars come back as
  Clojure values, other objects as JavaRef handles, and Java exceptions as
  ex-info.
- `net.b12n.jolt.openldk.wire`, the text protocol, tested on its own.
- Works around OpenLDK's `lstring` misreading signed bytes, which turned
  `"é".toUpperCase()` into an error. Two upstream bugs are written up in
  `docs/openldk-upstream-notes.md` and filed as atgreen/openldk#12 and #13.
- `init!` checks JAVA_HOME (a JDK 25 with jmods/ or lib/modules) and every
  classpath entry before starting SBCL, because OpenLDK exits the process on
  the first and fails late on the second. A failure after SBCL started is
  recorded, and later calls say the process needs a restart.
- Returned strings keep NUL and control characters, lone surrogates become
  U+FFFD, and a surrogate char comes back as its integer code. Chars past
  U+FFFF and integers boxed for Object parameters are range-checked.
- Verified on macOS arm64 only: jolt 0.8.19, JDK 25.0.2, 2026-10-09.
