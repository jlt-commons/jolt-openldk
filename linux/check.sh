#!/bin/sh
# SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0
#
# Runs inside the linux/Dockerfile image. /src is the repository, mounted
# read-only; the work happens on a copy so nothing Linux builds lands in the
# checkout. /cache holds $JOLT_OPENLDK_HOME across runs, so SBCL is compiled
# once.
set -eu
uname -sm
rm -rf /work
cp -R /src /work
rm -rf /work/target /work/examples/tour/target /work/.cpcache
cd /work

bridge/build.sh

# The OpenLDK tests may not pass by skipping here.
JOLT_OPENLDK_REQUIRE=1 bb test
bb tour
