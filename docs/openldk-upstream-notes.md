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

Found 2026-10-14 against `701609f`, which is also `master` that day.

When `%compile-method` fails, `%install-jit-error-stub` installs a stub that
throws, so the method's lazy compile stub isn't left behind. For an instance
method it interns the name in `(%class-loader-package class)`. Instance methods
dispatch on a generic function in `:openldk`, though: that's where the lazy
stub's `defmethod` goes, and where `%build-method-definition` puts a method
that compiled. For a class from a user loader, whose package is `OPENLDK.L1`
and so on, the throwing stub lands on an unrelated generic function. The lazy
stub survives, and each call runs `%compile-method`, which returns at once for
a method already marked `:done`, then `invoke-special`s the same stub again.
The process spins at full CPU, and only SIGKILL stops it, since SBCL ignores
SIGTERM and owns SIGALRM. Static methods use the loader package on both sides,
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
exists, that breaks the classes in those modules and every lookup of a class
that isn't found at all. As a compile error, that's what led to the loop in 3.

`bridge/bridge.lisp` picks the offset per file: 0 when the central directory's
signature is at the offset the end record gives, 4 when it is 4 bytes later,
and 4 for anything else, zip64 included.
