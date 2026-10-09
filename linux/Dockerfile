# SPDX-License-Identifier: EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0
#
# A Linux box for checking jolt-openldk: `bb linux` builds this image for the
# machine's own architecture and runs linux/check.sh in it.
#
#   arm64  jolt and ocicl publish no Linux arm64 binaries, so both are built
#          from their release tags here. jolt provisions its own pinned Chez
#          Scheme: Ubuntu's chezscheme is 10.0.0, which lacks vector-copy!.
#   amd64  jolt and ocicl come from their pinned release binaries.
FROM eclipse-temurin:25-jdk

ARG TARGETARCH

# sbcl here is only the host that compiles SBCL 2.6.9 from source (and ocicl
# on arm64); Ubuntu's own package is older and is not what bridge/build.sh
# ships. uuid, ncurses, lz4 and xxd are jolt's build needs on arm64.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      sbcl build-essential git curl ca-certificates libzstd-dev zlib1g-dev \
      libarchive-tools uuid-dev libncurses-dev liblz4-dev xxd \
 && rm -rf /var/lib/apt/lists/*

# jolt and babashka publish .sha256 files and these values match them; ocicl
# publishes none, so its hash was measured on 2026-10-09.
ARG JOLT_VERSION=v0.8.20
ARG JOLT_SHA256_AMD64=5400ed3c46d704ce30693b68edaa7998c248023f07b9df727447883343fcd050
ARG BB_VERSION=1.13.225
ARG BB_SHA256_AMD64=ab0e98307405ba19e7c5ba0c3c7cd17a6f90aa90577a72709055f120796d6bfe
ARG BB_SHA256_ARM64=7fd4ce2c6ac9975bfd6f20a42a49f5d6f95d8ac0b2c4d9336dc6afc67ad11ce2
ARG OCICL_VERSION=2.20.0
ARG OCICL_SHA256_AMD64=9fef518f8c20b12ce63ca6103d6224019e9b0a546ead5fc3f9a85e75b9d74050

# Downloads retry because GitHub's TLS endpoint drops the odd connection from
# inside a build. bsdtar, not GNU tar: under Docker Desktop's x86-64 emulation
# on Apple silicon, GNU tar 1.35 fails on the jolt archive with "Cannot open:
# Function not implemented" for every member (measured 2026-10-09).
RUN set -eu; cd /tmp; get() { curl -fsSL --retry 5 --retry-all-errors -o "$1" "$2"; }; \
    case "$TARGETARCH" in \
      amd64) \
        get jolt.tgz https://github.com/jolt-lang/jolt/releases/download/${JOLT_VERSION}/jolt-${JOLT_VERSION}-x86_64-linux.tar.gz; \
        echo "${JOLT_SHA256_AMD64}  jolt.tgz" | sha256sum -c -; \
        bsdtar xzf jolt.tgz; install -m 755 jolt-${JOLT_VERSION}-x86_64-linux/jolt /usr/local/bin/jolt; \
        get bb.tgz https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-amd64-static.tar.gz; \
        echo "${BB_SHA256_AMD64}  bb.tgz" | sha256sum -c -; \
        get ocicl.tgz https://github.com/ocicl/ocicl/releases/download/v${OCICL_VERSION}/ocicl-${OCICL_VERSION}-linux-amd64.tar.gz; \
        echo "${OCICL_SHA256_AMD64}  ocicl.tgz" | sha256sum -c -; \
        bsdtar xzf ocicl.tgz ./ocicl; install -m 755 ocicl /usr/local/bin/ocicl ;; \
      arm64) \
        git -c advice.detachedHead=false clone -q --depth 1 --branch ${JOLT_VERSION} \
            --recurse-submodules --shallow-submodules https://github.com/jolt-lang/jolt.git jolt-src; \
        make -C jolt-src build > /tmp/jolt-build.log 2>&1 || { tail -30 /tmp/jolt-build.log; exit 1; }; \
        install -m 755 jolt-src/target/release/jolt /usr/local/bin/jolt; \
        get bb.tgz https://github.com/babashka/babashka/releases/download/v${BB_VERSION}/babashka-${BB_VERSION}-linux-aarch64-static.tar.gz; \
        echo "${BB_SHA256_ARM64}  bb.tgz" | sha256sum -c -; \
        git -c advice.detachedHead=false clone -q --depth 1 --branch v${OCICL_VERSION} \
            https://github.com/ocicl/ocicl.git ocicl-src; \
        (cd ocicl-src && HOME=/tmp/ocicl-home sbcl --non-interactive --load setup.lisp > /tmp/ocicl-build.log 2>&1) \
            || { tail -30 /tmp/ocicl-build.log; exit 1; }; \
        install -m 755 /tmp/ocicl-home/.local/bin/ocicl /usr/local/bin/ocicl ;; \
      *) echo "unsupported architecture: $TARGETARCH" >&2; exit 1 ;; \
    esac; \
    bsdtar xzf bb.tgz; install -m 755 bb /usr/local/bin/bb; \
    cd /; rm -rf /tmp/* /root/.cache; \
    jolt --version; bb --version; ocicl version | head -1

# Optional: trust an extra CA, for a network behind a TLS-inspecting proxy such
# as Zscaler, which re-signs ghcr.io (where ocicl fetches from) with its own
# root. `bb linux` copies the host's bundle into linux/extra-ca/, which git
# ignores, so no certificate is committed; with nothing there this adds
# nothing. Last, so the tool builds above stay cached when the bundle changes.
COPY extra-ca/ /usr/local/share/ca-certificates/extra/
RUN find /usr/local/share/ca-certificates/extra -type f ! -name '*.crt' -delete \
 && update-ca-certificates > /dev/null
ENV OCICL_CA_FILE=/etc/ssl/certs/ca-certificates.crt

ENV JOLT_OPENLDK_HOME=/cache
