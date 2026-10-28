#!/bin/sh
# Usage: build.sh <git-tag>  (source mounted at /src) -> /out/box64-<tag>-pons.tar.xz
set -eu
TAG="$1"
# Patch a copy so the mounted source stays pristine.
rm -rf /tmp/src && cp -a /src /tmp/src
python3 /build/patches/apply.py /tmp/src
cmake -S /tmp/src -B /tmp/b -G Ninja \
  -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc -DCMAKE_ASM_COMPILER=aarch64-linux-gnu-gcc \
  -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo -DSDORYON1=ON -DBOX32=ON -DBOX32_BINFMT=OFF \
  -DCMAKE_INSTALL_PREFIX=/usr/local
ninja -C /tmp/b -j"$(nproc)"
DESTDIR=/tmp/stage ninja -C /tmp/b install
aarch64-linux-gnu-strip /tmp/stage/usr/local/bin/box64
tar -C /tmp/stage -cJf "/out/box64-$TAG-pons.tar.xz" .
ls -la /out
