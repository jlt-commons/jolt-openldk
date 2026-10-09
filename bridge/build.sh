#!/bin/sh
# Build what jolt-openldk loads at run time, into $JOLT_OPENLDK_HOME/dist:
#
#   libsbcl.dylib        SBCL's runtime as a shared library (.so on Linux)
#   libjoltopenldk.dylib the C shim from ldk.c, linked against it
#   openldk.core         SBCL core: OpenLDK warmed up, plus bridge.lisp
#   manifest.edn         what was built, from which sources
#
# Needs: a working `sbcl` on PATH (only to compile SBCL), `ocicl`, `git`, `cc`,
# and JAVA_HOME pointing at a JDK 25 (OpenLDK reads its class library).
#
# Nothing outside $JOLT_OPENLDK_HOME is written. SBCL and OpenLDK are cloned
# into $JOLT_OPENLDK_HOME/work at pinned versions; set OPENLDK_SRC to clone
# from a local checkout instead of GitHub (the checkout itself is not touched).
set -eu

SBCL_VERSION=2.6.9
OPENLDK_REF=23da184e4bb89aeb2d3f230d9d608ac4cff7172b
OPENLDK_SRC=${OPENLDK_SRC:-https://github.com/atgreen/openldk.git}
HOME_DIR=${JOLT_OPENLDK_HOME:-$HOME/.cache/jolt-openldk}
WORK=$HOME_DIR/work
DIST=$HOME_DIR/dist
HERE=$(cd "$(dirname "$0")" && pwd)

case $(uname -s) in
  Darwin) EXT=dylib ;;
  *) EXT=so ;;
esac

die() { echo "build.sh: $*" >&2; exit 1; }

for tool in sbcl ocicl git cc; do
  command -v "$tool" >/dev/null 2>&1 || die "needs $tool on PATH"
done
[ -n "${JAVA_HOME:-}" ] || die "JAVA_HOME is not set; OpenLDK needs a JDK 25"
grep -qs '^JAVA_VERSION="25' "$JAVA_HOME/release" \
  || die "JAVA_HOME=$JAVA_HOME is not a JDK 25 (OpenLDK targets 25 only)"

mkdir -p "$WORK" "$DIST"

# --- 1. SBCL from source, with its runtime as a shared library ----------------
# brew's SBCL is not built :sb-linkable-runtime, so there is no libsbcl to load.
# The core in step 2 must be dumped by THIS build: a core only starts on the
# runtime build that wrote it.
SBCL_SRC=$WORK/sbcl-$SBCL_VERSION
if [ ! -f "$SBCL_SRC/src/runtime/libsbcl.so" ]; then
  echo "== SBCL $SBCL_VERSION from source (a few minutes)"
  rm -rf "$SBCL_SRC"
  git -c advice.detachedHead=false clone -q --depth 1 --branch "sbcl-$SBCL_VERSION" https://github.com/sbcl/sbcl.git "$SBCL_SRC"
  # The default --xc-host is right. Don't pass one with --non-interactive: that
  # host quits before reading the build script from stdin, and the failure
  # surfaces much later as a missing genesis/sbcl.h.
  (cd "$SBCL_SRC" && ./make.sh --fancy > "$WORK/sbcl-build.log" 2>&1) \
    || die "SBCL build failed; see $WORK/sbcl-build.log"
  (cd "$SBCL_SRC" && ./make-shared-library.sh >> "$WORK/sbcl-build.log" 2>&1) \
    || die "libsbcl build failed; see $WORK/sbcl-build.log"
else
  echo "== SBCL $SBCL_VERSION already built"
fi

# --- 2. OpenLDK at the pinned commit, and the core ---------------------------
OPENLDK=$WORK/openldk
if [ "$(git -C "$OPENLDK" rev-parse HEAD 2>/dev/null || true)" != "$OPENLDK_REF" ]; then
  echo "== OpenLDK $OPENLDK_REF"
  rm -rf "$OPENLDK"
  git clone -q "$OPENLDK_SRC" "$OPENLDK"
  git -C "$OPENLDK" -c advice.detachedHead=false checkout -q "$OPENLDK_REF"
fi
# ocicl pulls from ghcr.io. Behind an HTTPS proxy that cannot tunnel to it, this
# fails with "Unable to establish HTTPS tunnel through proxy"; run the build
# with the proxy variables unset.
(cd "$OPENLDK" && ocicl install > "$WORK/ocicl.log" 2>&1) \
  || die "ocicl install failed; see $WORK/ocicl.log"

# OpenLDK's Makefile assumes ~/.sbclrc loads ocicl's runtime. Use ocicl's own
# init snippet instead of anyone's ~/.sbclrc.
ocicl setup > "$WORK/ocicl-init.lisp" 2>/dev/null || true
grep -q ocicl-runtime "$WORK/ocicl-init.lisp" || die "ocicl setup printed no init snippet"

echo "== openldk.core (about a minute)"
rm -f "$DIST/openldk.core"
(cd "$OPENLDK" && \
  XDG_CACHE_HOME="$WORK/fasl-cache" CL_SOURCE_REGISTRY="$OPENLDK//:" \
  "$SBCL_SRC/run-sbcl.sh" --dynamic-space-size 32768 \
    --userinit "$WORK/ocicl-init.lisp" --disable-debugger \
    --eval "(asdf:load-system :openldk)" \
    --load "$HERE/bridge.lisp" \
    --eval "(openldk::make-jolt-openldk-core \"$DIST/openldk.core\")" \
    > "$WORK/core-build.log" 2>&1) \
  || die "core build failed; see $WORK/core-build.log"
[ -f "$DIST/openldk.core" ] || die "no core written; see $WORK/core-build.log"

# --- 3. libsbcl and the shim ---------------------------------------------------
# libsbcl's install name is a bare "libsbcl.so", so a loader would look for it
# in the current directory. Rename it and give it an @rpath name; the shim
# finds it next to itself.
echo "== libsbcl.$EXT and libjoltopenldk.$EXT"
cp "$SBCL_SRC/src/runtime/libsbcl.so" "$DIST/libsbcl.$EXT"
if [ "$EXT" = dylib ]; then
  install_name_tool -id @rpath/libsbcl.dylib "$DIST/libsbcl.dylib"
  cc -dynamiclib -o "$DIST/libjoltopenldk.dylib" "$HERE/ldk.c" \
     -L"$DIST" -lsbcl -Wl,-rpath,@loader_path
else
  cc -shared -fPIC -o "$DIST/libjoltopenldk.so" "$HERE/ldk.c" \
     -L"$DIST" -lsbcl -ldl -Wl,-rpath,'$ORIGIN'
fi

cat > "$DIST/manifest.edn" <<EOF
{:sbcl "$SBCL_VERSION"
 :openldk "$OPENLDK_REF"
 :java-home "$JAVA_HOME"
 :built "$(date -u +%Y-%m-%dT%H:%M:%SZ)"}
EOF
echo "== done: $DIST"
ls -la "$DIST"
