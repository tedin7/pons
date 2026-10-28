#!/bin/sh
# Dev helper: run a shell script inside the app's Debian (debug builds only, via run-as).
# Usage: PP_SERIAL=<ip:port> pp-run.sh 'commands...'
set -eu
ADB="${ADB:-/root/.local/mercati-android-tools/sdk/platform-tools/adb -P 5037}"
SERIAL="${PP_SERIAL:?set PP_SERIAL to the adb serial}"
PKG=io.github.pinguplay
LIB=$($ADB -s "$SERIAL" shell dumpsys package $PKG | sed -n 's/.*legacyNativeLibraryDir=//p' | head -1)/arm64
SCRIPT_B64=$(printf '%s' "$1" | base64 -w0)
$ADB -s "$SERIAL" shell "run-as $PKG sh -c '
D=/data/user/0/$PKG; mkdir -p \$D/cache/proot-tmp
mkdir -p \$D/cache/shm
export PROOT_X86_RUNNER=/usr/local/bin/box64 PROOT_LOADER=$LIB/libproot-loader.so PROOT_TMP_DIR=\$D/cache/proot-tmp LD_LIBRARY_PATH=$LIB
echo $SCRIPT_B64 | base64 -d > \$D/cache/proot-tmp/pp-script.sh
exec $LIB/libproot.so --kill-on-exit --link2symlink -0 -r \$D/files/rootfs -b /dev -b /proc -b /sys \
  -b \$D/cache/proot-tmp:/tmp -b \$D/cache/shm:/dev/shm -w /root /usr/bin/env -i HOME=/root TERM=xterm LANG=C.UTF-8 \
  PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin /bin/sh /tmp/pp-script.sh
'"
