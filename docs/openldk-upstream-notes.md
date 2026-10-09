# OpenLDK: notes for upstream

Two string bugs turned up while building this bridge. Both reproduce on
OpenLDK alone, without anything from this repository, so they belong upstream
at [atgreen/openldk](https://github.com/atgreen/openldk). Both were filed on
2026-10-09, after re-checking `master` and searching the tracker for
duplicates: bug 1 as
[#13](https://github.com/atgreen/openldk/issues/13) and bug 2 as
[#12](https://github.com/atgreen/openldk/issues/12). This page keeps the
longer notes.

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
