#!/bin/sh
# run.sh <box64 tarball> [ENV=value ...]: Box64's regression tests on our arm64
# build, under qemu on the x86 build host (see run-tests.py). Run on Proxmox.
set -eu
TAR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; shift
HERE=$(cd "$(dirname "$0")" && pwd)
SRC=/root/pons-work/box64-main
docker build -q -t pons-box64test "$HERE" >/dev/null
docker run --rm -v "$TAR":/b64.tar.xz:ro -v "$SRC":/src:ro pons-box64test sh -c "
  mkdir -p /b64 && tar -C /b64 -xJf /b64.tar.xz
  python3 /run-tests.py \$(find /b64 -name box64 -type f | head -1) /src $*"
