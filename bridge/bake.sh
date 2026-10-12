#!/bin/sh
# SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0
#
# Bake a classpath into a core: a separate SBCL process loads every class on
# CLASSPATH, force-compiles every method, and dumps OUT_CORE, which jolt can
# start instead of the shared openldk.core. It cannot run inside a jolt process,
# because the thread that calls into Lisp there is a foreign thread and SBCL
# refuses to save from one.
#
#   bake.sh OUT_CORE COUNTS_FILE CLASSPATH [WARM_CLASS ...]
#   bake.sh --self-test
#
# CLASSPATH is colon-joined jars and class directories, each an absolute path
# (the baked core only starts for exactly this string, so it is not rewritten). WARM_CLASS is a JDK
# class by binary name (java/time/LocalDate) to compile as well; one that does
# not load fails the bake. COUNTS_FILE receives an EDN map of counts. The bake
# log is stderr. Exit 0 means OUT_CORE and COUNTS_FILE both exist.
# --self-test checks the compile-failure detector and writes no core.
#
# Needs the work tree bridge/build.sh left in place (SBCL and OpenLDK sources);
# run `bb build` again if it is gone.
#
# This file is a template: build.sh copies it into dist/ with @WORK@ and
# @BRIDGE@ filled in.
set -eu

WORK=@WORK@
BRIDGE=@BRIDGE@
SBCL_SRC=$WORK/sbcl-@SBCL_VERSION@
OPENLDK=$WORK/openldk

[ -x "$SBCL_SRC/run-sbcl.sh" ] && [ -d "$OPENLDK" ] \
  || { echo "bake.sh: no work tree in $WORK; run bb build" >&2; exit 1; }

# A core only starts on the SBCL build that dumped it. If SBCL was rebuilt since
# bb build, the dist's runtime is not the one this script would dump with.
BUILD_ID_FILE=$SBCL_SRC/output/build-id.inc
[ "$(tr -d '"' < "$BUILD_ID_FILE" 2>/dev/null | tr -d '\n')" = "@SBCL_BUILD_ID@" ] \
  || { echo "bake.sh: SBCL in $WORK was rebuilt since the dist was built (build-id is not @SBCL_BUILD_ID@); re-run bb build" >&2; exit 1; }

# The Lisp reads its arguments from the environment, so nothing needs quoting.
SELF=""
if [ "${1:-}" = "--self-test" ]; then
  SELF=1
  FORM='(openldk::%ldk-bake-self-test)'
else
  [ $# -ge 3 ] || { echo "usage: bake.sh OUT_CORE COUNTS_FILE CLASSPATH [WARM_CLASS ...]" >&2; exit 2; }
  # The Lisp runs from OpenLDK's directory, so paths must not be relative.
  abs() { case $1 in /*) printf '%s' "$1" ;; *) printf '%s/%s' "$PWD" "$1" ;; esac; }
  BAKE_CORE=$(abs "$1"); BAKE_COUNTS=$(abs "$2")
  BAKE_CLASSPATH=$3
  # init! sends the same string to (:setup cp), and the baked core only accepts
  # an identical one, so entries are stored as given: absolute or refused.
  OLDIFS=$IFS; IFS=:
  for e in $BAKE_CLASSPATH; do
    case $e in
      /*) ;;
      *) echo "bake.sh: classpath entries must be absolute paths: '$e'" >&2; exit 2 ;;
    esac
  done
  IFS=$OLDIFS
  case $BAKE_CLASSPATH in
    ""|:*|*:|*::*) echo "bake.sh: classpath entries must be absolute paths: empty entry in '$BAKE_CLASSPATH'" >&2; exit 2 ;;
  esac
  shift 3
  BAKE_WARM="$*"
  export BAKE_CORE BAKE_COUNTS BAKE_CLASSPATH BAKE_WARM
  # A stale file from an earlier run must not pass for this one.
  rm -f "$BAKE_CORE" "$BAKE_COUNTS"
  FORM='(openldk::make-jolt-openldk-baked-core (uiop:getenv "BAKE_CORE") (uiop:getenv "BAKE_CLASSPATH") (remove "" (split-sequence:split-sequence #\Space (uiop:getenv "BAKE_WARM")) :test (function string=)) (uiop:getenv "BAKE_COUNTS"))'
fi

cd "$OPENLDK"
# stdout joins the log: OpenLDK prints progress there.
XDG_CACHE_HOME="$WORK/fasl-cache" CL_SOURCE_REGISTRY="$OPENLDK//:" \
  "$SBCL_SRC/run-sbcl.sh" --dynamic-space-size 32768 \
    --userinit "$WORK/ocicl-init.lisp" --disable-debugger \
    --eval "(asdf:load-system :openldk)" \
    --load "$BRIDGE" \
    --eval "$FORM" \
    --eval "(sb-ext:exit)" >&2

if [ -z "$SELF" ]; then
  [ -f "$BAKE_CORE" ] && [ -f "$BAKE_COUNTS" ] \
    || { echo "bake.sh: the bake wrote no core" >&2; exit 1; }
fi
