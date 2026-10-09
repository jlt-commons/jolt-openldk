# Changelog

## Unreleased

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

Merged as PR #1. Nothing is tagged yet.

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
