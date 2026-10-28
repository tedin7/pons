#!/bin/sh
# Usage: build.sh <git-tag>  (source mounted at /src) -> /out/box64-<tag>-pinguplay.tar.xz
set -eu
TAG="$1"
cmake -S /src -B /tmp/b -G Ninja \
  -DCMAKE_C_COMPILER=aarch64-linux-gnu-gcc -DCMAKE_ASM_COMPILER=aarch64-linux-gnu-gcc \
  -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=aarch64 \
  -DCMAKE_BUILD_TYPE=RelWithDebInfo -DSDORYON1=ON -DBOX32=ON -DBOX32_BINFMT=OFF \
  -DCMAKE_INSTALL_PREFIX=/usr/local
ninja -C /tmp/b -j"$(nproc)"
DESTDIR=/tmp/stage ninja -C /tmp/b install
aarch64-linux-gnu-strip /tmp/stage/usr/local/bin/box64
tar -C /tmp/stage -cJf "/out/box64-$TAG-pinguplay.tar.xz" .
ls -la /out
