#!/bin/sh
# Cross-builds Mesa (Turnip with Android KGSL support + Zink) for Debian arm64.
#
# Release tarball:  build.sh <version> <sha256>
# Git checkout:     build.sh git <label>       (source mounted read-only at /src)
#
# Output: /out/mesa-<version|label>-pons.tar.xz
set -eu
PREFIX=/opt/pons/mesa

if [ "$1" = git ]; then
    LABEL="$2"
    cp -a /src /tmp/mesa
else
    LABEL="$1"
    cd /tmp
    curl -sSfLO "https://archive.mesa3d.org/mesa-$LABEL.tar.xz"
    echo "$2  mesa-$LABEL.tar.xz" | sha256sum -c -
    tar xf "mesa-$LABEL.tar.xz"
    mv "mesa-$LABEL" /tmp/mesa
fi
cd /tmp/mesa
python3 /build/patches/apply.py /tmp/mesa

# Snapdragon 8 Elite (Oryon) implements Armv8.7-A without SVE.
CPU_FLAGS="-march=armv8.7-a -mtune=neoverse-v2"

meson setup build --cross-file /build/cross-aarch64.ini \
  --prefix="$PREFIX" --libdir=lib --buildtype=release -Doptimization=3 -Db_ndebug=true \
  -Dc_args="$CPU_FLAGS" -Dcpp_args="$CPU_FLAGS" \
  -Dplatforms=x11 -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl,msm \
  -Dgallium-drivers=zink,softpipe -Dglx=dri -Degl=enabled -Dgles1=disabled -Dgles2=enabled \
  -Dllvm=disabled -Dvalgrind=disabled -Dlibunwind=disabled -Dlmsensors=disabled \
  -Dvideo-codecs= -Dbuild-tests=false
ninja -C build -j"$(nproc)"
DESTDIR=/tmp/stage ninja -C build install
aarch64-linux-gnu-strip --strip-unneeded /tmp/stage$PREFIX/lib/*.so* /tmp/stage$PREFIX/lib/*/*.so* 2>/dev/null || true
tar -C /tmp/stage -cJf "/out/mesa-$LABEL-pons.tar.xz" opt
ls -la /out
