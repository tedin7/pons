#!/bin/sh
# Usage: build.sh <mesa-version> <sha256>  -> /out/mesa-<version>-pinguplay.tar.xz
set -eu
VERSION="$1"
SHA256="$2"
PREFIX=/opt/pinguplay/mesa
cd /tmp
curl -sSfLO "https://archive.mesa3d.org/mesa-$VERSION.tar.xz"
echo "$SHA256  mesa-$VERSION.tar.xz" | sha256sum -c -
tar xf "mesa-$VERSION.tar.xz"
cd "mesa-$VERSION"
meson setup build --cross-file /build/cross-aarch64.ini \
  --prefix="$PREFIX" --libdir=lib --buildtype=release -Db_ndebug=true \
  -Dplatforms=x11 -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl,msm \
  -Dgallium-drivers=zink -Dglx=dri -Degl=enabled -Dgles1=disabled -Dgles2=enabled \
  -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
  -Dvideo-codecs= -Dbuild-tests=false
ninja -C build -j"$(nproc)"
DESTDIR=/tmp/stage ninja -C build install
aarch64-linux-gnu-strip --strip-unneeded /tmp/stage$PREFIX/lib/*.so* /tmp/stage$PREFIX/lib/*/*.so* 2>/dev/null || true
tar -C /tmp/stage -cJf "/out/mesa-$VERSION-pinguplay.tar.xz" opt
ls -la /out
