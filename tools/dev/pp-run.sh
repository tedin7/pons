#!/bin/sh
# Dev helper: run a shell script inside the app's Debian (debug builds only, via run-as).
# Usage: PP_SERIAL=<ip:port> pp-run.sh 'commands...'
# With PP_DETACH=<name> it returns at once; output goes to cache/proot-tmp/<name>.log
# and survives adb disconnects. PP_PROOT_ENV="VAR=value ..." is set for proot itself
# (e.g. PROOT_SYSCALL_STATS=/data/user/0/io.github.pons/cache/proot-tmp/x.txt).
set -eu
ADB="${ADB:-/root/.local/mercati-android-tools/sdk/platform-tools/adb -P 5037}"
SERIAL="${PP_SERIAL:?set PP_SERIAL to the adb serial}"
PKG=io.github.pons
LIB=$($ADB -s "$SERIAL" shell dumpsys package $PKG | sed -n 's/.*legacyNativeLibraryDir=//p' | head -1)/arm64
SCRIPT_B64=$(printf '%s' "$1" | base64 -w0)
NAME="${PP_DETACH:-pp-script}"
if [ -n "${PP_DETACH:-}" ]; then
    LAUNCH="nohup"; TAIL="> \$D/cache/proot-tmp/$NAME.log 2>&1 < /dev/null & echo started $NAME"
else
    LAUNCH="exec"; TAIL=""
fi
$ADB -s "$SERIAL" shell "run-as $PKG sh -c '
D=/data/user/0/$PKG; mkdir -p \$D/cache/proot-tmp
mkdir -p \$D/cache/shm
export PROOT_X86_RUNNER=/usr/local/bin/box64 PROOT_LOADER=$LIB/libproot-loader.so PROOT_TMP_DIR=\$D/cache/proot-tmp LD_LIBRARY_PATH=$LIB PROOT_NO_HEAP=1 ${PP_PROOT_ENV:-}
echo $SCRIPT_B64 | base64 -d > \$D/cache/proot-tmp/$NAME.sh
$LAUNCH $LIB/libproot.so --kill-on-exit --link2symlink --sysvipc -0 -r \$D/files/rootfs -b /dev -b /proc -b /sys \
  -b \$D/cache/proot-tmp:/tmp -b \$D/cache/shm:/dev/shm -w /root /usr/bin/env -i HOME=/root TERM=xterm LANG=C.UTF-8 \
  DISPLAY=:0 XDG_RUNTIME_DIR=/tmp \
  PONS_FASTPATH_ROOT=\$D/files/rootfs PONS_FASTPATH_BINDS=/tmp=\$D/cache/proot-tmp:/dev/shm=\$D/cache/shm \
  VK_ICD_FILENAMES=/opt/pons/mesa/share/vulkan/icd.d/freedreno_icd.aarch64.json \
  LD_LIBRARY_PATH=/opt/pons/mesa/lib LIBGL_DRIVERS_PATH=/opt/pons/mesa/lib/dri \
  __GLX_VENDOR_LIBRARY_NAME=mesa MESA_LOADER_DRIVER_OVERRIDE=zink LIBGL_KOPPER_DRI2=1 TU_DEBUG=noconform \
  MESA_VK_WSI_DEBUG=sw PONS_PRESENT=/tmp/pons-present PONS_MAX_FPS=120 \
  PATH=/opt/pons/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/usr/games /bin/sh /tmp/$NAME.sh $TAIL
'"
