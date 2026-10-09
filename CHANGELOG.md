# Changelog

## Unreleased

First cut, on the `feat/openldk-bridge` branch. Nothing is tagged yet.

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
  `"é".toUpperCase()` into an error. Two upstream bugs are written up, not
  filed, in `docs/openldk-upstream-notes.md`.
- `init!` checks JAVA_HOME (a JDK 25 with jmods/ or lib/modules) and every
  classpath entry before starting SBCL, because OpenLDK exits the process on
  the first and fails late on the second. A failure after SBCL started is
  recorded, and later calls say the process needs a restart.
- Returned strings keep NUL and control characters, lone surrogates become
  U+FFFD, and a surrogate char comes back as its integer code. Chars past
  U+FFFF and integers boxed for Object parameters are range-checked.
- Verified on macOS arm64 only: jolt 0.8.19, JDK 25.0.2, 2026-10-09.
