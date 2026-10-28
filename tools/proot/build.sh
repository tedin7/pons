#!/bin/sh
# Builds Termux's proot with the Pons x86 runner patch, for the APK's jniLibs.
# Usage (on Proxmox): build.sh  -> /root/pons-work/jni/libproot.so + libproot-loader.so
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=/root/pons-work
SRC=$WORK/proot-build
TAG=v5.1.107.101
NDK=/root/android-sdk/ndk/27.2.12479018
TOOLS=$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin
TERMUX=$WORK/x/data/data/com.termux/files/usr

rm -rf "$SRC"
git clone -q --depth 1 --branch "$TAG" https://github.com/termux/proot.git "$SRC"
patch -d "$SRC" -p1 < "$HERE/0001-x86-runner.patch"
patch -d "$SRC" -p1 < "$HERE/0002-syscall-stats.patch"
patch -d "$SRC" -p1 < "$HERE/0003-fastpath-marker.patch"

# talloc headers come from the Termux package; link against our renamed copy.
mkdir -p "$SRC/deps/lib"
cp "$WORK/jni/libtalloc.so" "$WORK/jni/libandroid-shmem.so" "$SRC/deps/lib/"

# Extra flags go through the environment: the makefile appends to them with +=.
CPPFLAGS="-I$TERMUX/include -DARG_MAX=131072 -DVERSION=\\\"5.1.107-pons\\\"" \
CFLAGS="-Wno-implicit-function-declaration" \
LDFLAGS="-L$SRC/deps/lib" \
make -C "$SRC/src" -j"$(nproc)" \
  CC="$TOOLS/aarch64-linux-android24-clang" \
  LD="$TOOLS/aarch64-linux-android24-clang" \
  STRIP="$TOOLS/llvm-strip" OBJCOPY="$TOOLS/llvm-objcopy" OBJDUMP="$TOOLS/llvm-objdump" \
  PROOT_UNBUNDLE_LOADER=/unused PROOT_WITH_LIBANDROID_SHMEM=true \
  proot loader/loader

"$TOOLS/llvm-strip" "$SRC/src/proot"
cp "$SRC/src/proot" "$WORK/jni/libproot.so"
cp "$SRC/src/loader/loader" "$WORK/jni/libproot-loader.so"
ls -la "$WORK/jni"
