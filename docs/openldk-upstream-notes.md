# OpenLDK: notes for upstream

Two string bugs turned up while building this bridge. Both reproduce on
OpenLDK alone, without anything from this repository, so they belong upstream
at [atgreen/openldk](https://github.com/atgreen/openldk). Both were filed on
2026-10-09, after re-checking `master` and searching the tracker for
duplicates: bug 1 as
[#13](https://github.com/atgreen/openldk/issues/13) and bug 2 as
[#12](https://github.com/atgreen/openldk/issues/12). This page keeps the
longer notes.

**Both fixed upstream on 2026-10-10, in `701609f`, which jolt-openldk now
pins.** Bug 1 was fixed by d960ff6, with the same masking proposed below.
Bug 2 turned out to have its own cause. Every method body converts a Lisp
TYPE-ERROR into a Java NullPointerException inside a HANDLER-BIND, and SBCL
runs that handler outside the method's own catch clauses, so the
manufactured NPE could never be caught. 701609f fixes that. The notes below
are kept as filed.

Verified against `master` at `23da184e4bb89aeb2d3f230d9d608ac4cff7172b`
(2026-10-09), SBCL 2.6.9, JDK 25.0.2, macOS arm64.

**Sections 3 to 8 were sent upstream as pull requests [#14](https://github.com/atgreen/openldk/pull/14) through [#18](https://github.com/atgreen/openldk/pull/18), all merged on 2026-10-18.** `master` that day, `4498cd2`, is what jolt-openldk now pins, so the bridge no longer carries those six fixes. Sections 9 and 10 are the two still carried.

## 1. `lstring` reads Java's signed bytes as character codes

`lstring` (`src/strings.lisp`) turns a `java/lang/String` into a Lisp string.
In the LATIN1 branch it calls `code-char` on each element of the `byte[]`,
and in the UTF16 branch it adds pairs of elements together. But a Java
`byte[]` holds signed bytes. A string built by Java code, for example by
`toUpperCase`, stores `É` as -55, and `code-char` signals:

```
The value -55 is not of type (MOD 1114112) when binding SB-IMPL::CODE
```

Strings built by `jstring` work, because `%string-value-bytes` writes
unsigned bytes. That's why the bug only appears in one direction. Repro, in an
image with OpenLDK loaded and initialised:

```lisp
(openldk::lstring (openldk::|toUpperCase()| (openldk::jstring "é")))
;; error above; expected "É"
```

The fix is to mask each byte with `(logand b #xFF)` in both branches before
using it. jolt-openldk's `bridge.lisp` carries `%ldk-lstring`, which does
exactly that, and with it `"héllo"`, `"ÿ"` and `"😀x"` all upper-case
correctly through the bridge.

A related question is worth asking in the same issue. `jstring` writes
unsigned bytes into a `byte[]` that Java code expects to be signed, so the
same string can be represented two ways depending on who made it. Bug 2 might
be a consequence.

## 2. Concatenating an upper-cased mixed string throws an uncatchable NPE

```java
public class R {
    public static void main(String[] a) {
        try {
            String u = "é✓".toUpperCase();
            System.out.println(u.length() + " " + (u + "!").length());
        } catch (Throwable t) {
            System.out.println("caught " + t);
        }
    }
}
```

| | output |
|---|---|
| JVM (JDK 25.0.2) | `2 3` |
| `openldk R` | `Unhandled Java exception: java.lang.NullPointerException` |

Two things are wrong there. The concatenation fails at all, and its exception
escapes a `catch (Throwable)` that should have caught it. Two neighbours
work: `"a✓".toUpperCase() + "!"` prints `A✓!`, and `"é✓"` concatenated
without upper-casing it first prints `2 3`. That points at a string whose
UTF16 bytes Java code produced, as opposed to one `jstring` built.

The remaining cases from the same probe all matched the JVM: `"é".length()`,
printing `"é"`, `"é".toUpperCase().length()`, `(int) "é".toUpperCase().charAt(0)`
(201), and `"É".equals("É".toUpperCase())`.

## 3. A failed compile of an instance method loops forever

Found 2026-10-14 against `701609f`, which is also `master` that day. Bugs 3
and 4 went upstream together the same day, as a pull request with both fixes
and unit tests, [#14](https://github.com/atgreen/openldk/pull/14), after
re-checking `master` and searching the tracker for duplicates.

When `%compile-method` fails, `%install-jit-error-stub` installs a stub that
throws, so the method's lazy compile stub isn't left behind. For an instance
method it interns the name in `(%class-loader-package class)`. Instance methods
dispatch on a generic function in `:openldk`, though: that's where the lazy
stub's `defmethod` goes, and where `%build-method-definition` puts a method
that compiled. For a class from a user loader, whose package is `OPENLDK.APP`,
`OPENLDK.L1` and so on, the throwing stub lands on an unrelated generic function. The lazy
stub survives, and each call runs `%compile-method`, which returns at once for
a method already marked `:done`, then `invoke-special`s the same stub again.
The process spins at full CPU. SIGALRM, which SBCL owns, doesn't stop it, and
with OpenLDK hosted inside jolt a SIGTERM hadn't stopped it two seconds later.
SIGKILL does. Static methods use the loader package on both sides,
so they get the throwing stub.

Reproduced on OpenLDK alone with a version-49 class whose constructor calls an
instance method that uses `jsr`, which OpenLDK doesn't translate: it hangs
after `JIT-ERROR in p/Jsr.m()V: The function :JSR is undefined.` Interning the
instance-method stub in `:openldk` makes the call throw "JIT compilation failed
for p/Jsr.m()V" instead. `bridge/bridge.lisp` carries that version.

## 4. jmods whose zip offsets count from the start of the file

Found the same day, on GraalVM CE 25.0.1.

`open-jmod-zipfile` wraps every jmod in `jmod-offset-stream`, which adds 4 to
each position to skip the `JM\1\0` header. That fits a jmod whose zip offsets
count from after the header, as all 69 of Oracle OpenJDK 25.0.2's do. 30 of
GraalVM CE 25.0.1's 84 count from the start of the file (`java.desktop`,
`java.instrument`, `java.management`, `java.prefs`, `jdk.compiler` and more),
so they're read 4 bytes past their central directory and fail zip's assertion,
`(ZIP::CD/SIGNATURE ZIP::HEADER) = 1311508`, which is the two fields that
follow the signature. Since OpenLDK reads the whole JDK from `jmods/` when it
exists, that breaks any lookup that reaches one of those jmods: the classes in
them, and every lookup of a class that isn't found at all. As a compile error, that's what led to the loop in 3.

`bridge/bridge.lisp` picks the offset per file: 0 when the central directory's
signature is at the offset the end record gives, 4 when it is 4 bytes later,
and 4 for anything else, zip64 included.

## 5. Strings made by OpenLDK store their bytes unsigned

Found 2026-10-14 against `701609f`, which is also `master` that day, and sent
upstream with a fix and a unit test as
[#15](https://github.com/atgreen/openldk/pull/15), after re-checking `master`
and searching the tracker for duplicates.

`%string-value-bytes`, which `jstring` and `ijstring` use, builds a String's
`value` from unsigned octets, so a character from U+0080 to U+00FF is stored
as 128..255 where Java's `byte[]` holds -128..-1, and each UTF16 byte the same
way. JDK code depends on the sign. `String.encodeUTF8` returns a LATIN1 value
unchanged when `StringCoding.hasNegatives` finds no negative byte, so
`"h\u00e9".getBytes(UTF_8)` gave `[104, 233]` on plain OpenLDK where a JVM
gives `[104, -61, -87]`. `String.equals` compares the bytes, so the literal
wasn't equal to `new String(new char[] {'h', '\u00e9'})`, and the same held for
a UTF16 string such as `"a\u65e5"`. Strings Java builds itself were already
signed. Every string jolt-openldk passes in is a `jstring`, so before the fix
`"hé"` crossed in, went through `getBytes("UTF-8")` and came back as
`[104 -23]`.

`bridge/bridge.lisp` carries the fixed function, which writes both coders'
bytes signed (`lstring` already masks each byte, so reading is unchanged), and
a String `print-object` that prints through `lstring`, because upstream's
passed the raw bytes to `code-char`. It's loaded before the core initializes
OpenLDK, so no String is made the old way.

The same reproduction found one more thing, not looked into: an MD5
`MessageDigest` call made the run exit on both `master` and the fixed branch.

## 6. On macOS, NativeLibraries.load is undefined

Found 2026-10-14 against `701609f`, which is also `master` that day, chasing
the MD5 exit noted under 5, and sent upstream with a fix and a DejaGnu test as
[#16](https://github.com/atgreen/openldk/pull/16), after re-checking `master`
and searching the tracker for duplicates.

`MessageDigest.getInstance` initializes `java.security.Security`, which reads
its master file through `Path.of`. `FileSystems.getDefault()` initializes
`UnixNativeDispatcher`, whose static initializer calls
`BootLoader.loadLibrary("nio")`. `NativeLibraries.loadLibrary(Class, File)`
skips a library file that isn't present only when
`ClassLoaderHelper.loadLibraryOnlyIfPresent()` is true, and on macOS 11 and
later it is false (dyld's shared cache), so the JDK calls the native `load`,
which OpenLDK didn't define. Linux checks for the file first and never calls
it, going by the JDK's source; not run on Linux.

OpenLDK's natives are Lisp, so there is no library to load. `bridge/bridge.lisp`
defines `load` to return false, which the JDK treats as an absent library that
`BootLoader.loadLibrary` ignores. With it, MD5, SHA-256, `Path.of`,
`Files.exists`, `readString`, `readAllBytes` and `writeString` give the JVM's
results. On OpenLDK's own regression gate the fix turns `aaa/h005.java` and
`gcj/md5test.java` from FAIL to PASS (two of the three failures this machine
showed for #14 and #15), with nothing going the other way.

Seen in the same work, not looked into: `Files.list` throws
`ArrayIndexOutOfBoundsException: 0`, and OpenLDK opens zlib as `libz.so.1`, a
name macOS doesn't have (`aaa/ni.java`).

## 7. On macOS, zlib is loaded by its Linux name

Found 2026-10-14 against `701609f`, which is also `master` that day, and sent
upstream with a fix and a DejaGnu test as
[#17](https://github.com/atgreen/openldk/pull/17), after re-checking `master`
and searching the tracker for duplicates.

`%load-zlib` loads `"libz.so.1"` on every platform. macOS's zlib is
`libz.1.dylib`, in dyld's shared cache, so the first `java.util.zip` native
call signalled "Error opening shared object", a Lisp error Java code can't
catch. On macOS it's only reachable with the fix in 6, since loading
`java.util.zip` goes through `BootLoader.loadLibrary`. `bridge/bridge.lisp`
loads `libz.1.dylib` on macOS. With both, CRC32, Deflater, Inflater and a GZIP
round trip give the JVM's results.

Two more gaps the same work turned up, neither looked into:
- Reading a zip back fails: `ZipInputStream.getNextEntry()` is an undefined
  function, though writing with `ZipOutputStream` works.
- `aaa/ni.java` got past zlib but then failed on the undefined native
  `jdk/internal/misc/VM.getNanoTimeAdjustment(J)`. That is 8.

## 8. VM.getNanoTimeAdjustment is undefined

Found 2026-10-14 against `701609f`, which is also `master` that day, and sent
upstream with a fix, a unit test and a DejaGnu test as
[#18](https://github.com/atgreen/openldk/pull/18), after re-checking `master`
and searching the tracker for duplicates.

It's the native behind `Clock.currentInstant`, so `Instant.now()` signalled
an undefined function on every platform. `Date.toString` reaches it too, in a
time zone with no CLDR display name: `TimeZone.getDisplayName` falls back to a
GMT format through `Instant.now()`. That's why `aaa/ni.java` failed here in
`GMT+07:00` but passes in CI's UTC. `bridge/bridge.lisp` defines it as HotSpot
does: nanoseconds from the given epoch offset to now, or -1 when the offset is
2^32 seconds or more away.

With the fixes in 6, 7 and 8 together, OpenLDK's own `make check-regression`
passes on macOS arm64: 501 core passes against a baseline of 492.
## 9. A compile finishing wakes one waiter on a shared condition variable

Found 2026-10-17 against `4498cd2`'s `%compile-method` (unchanged since
`701609f`), while removing the one lock jolt-openldk held across every call.
Sent upstream as [#19](https://github.com/atgreen/openldk/pull/19)
on 2026-10-19, after re-checking `master` at `4498cd2` and searching the
tracker for duplicates (none mention threads, monitors or wakeups).

`%claim-method-compilation` blocks a thread whose method another thread is
compiling, on `*method-compilation-cv*`, one condition variable shared by every
method. `%compile-method` finishes by waking it with `bt:condition-notify`,
which wakes a single thread. So when three or more threads make the first call
of the same method, the compile finishing frees one of the waiters and the rest
sleep until some unrelated compile happens to notify again, which in a quiet
program is never. A notify meant for one method can also be taken by a thread
waiting on another.

The fix is `sb-thread:condition-broadcast` there, which `notifyAll()` in
`src/native/system.lisp` already uses. Woken threads re-check their method's
status under the lock, and the wakeups happen once per method, at its first
call. Measured with the new `testsuite/aaa/jitwake.java`, where four threads
make the first call of the same method together: on stock `4498cd2` the run
printed `stuck` after its 20 s join timed out, three runs out of three, and
with the broadcast all four threads finish in about 2 s. The bridge carries a copy of
`%claim-method-compilation` whose wait times out and re-checks every 50 ms,
which costs at most 50 ms per missed wakeup; it is dropped when the pin has
the broadcast.

## 10. A monitor exit can wake a wait() instead of a thread entering

Found 2026-10-17 the same way, timing a monitor handoff. Sent upstream as
[#20](https://github.com/atgreen/openldk/pull/20) on 2026-10-19,
with the same freshness checks as section 9.

A monitor's condition variable is shared by threads blocked entering it and
threads parked in `Object.wait()`. `monitor-exit` frees the monitor and wakes
one thread with `bordeaux-threads:condition-notify`. When the wakeup lands on
a `wait()`, that thread sees it is still in the wait set and goes back to sleep, and
a thread entering stays asleep at the door of a free monitor until something
else wakes it. Measured with the new `testsuite/aaa/monitorwake.java`: a
holder exits after 300 ms while a second thread sits in `wait(5000)`, and the
thread entering got in after about 5190 ms, three runs out of three, when the
expiring wait exited the monitor and notified again. With the fix it enters in
about 200 ms.

The fix is `sb-thread:condition-broadcast` at that exit, which `notify()` and
`notifyAll()` already use on the same condition variable. Threads entering
contend for the mutex and all but one go back to waiting; threads in `wait()`
re-check their wait-set membership, as they already must. The bridge carries a copy of
`monitor-exit` with the broadcast; it is dropped when the pin has it.
