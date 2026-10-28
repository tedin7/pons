#!/bin/sh
# pp-prof.sh <label> [command...]
# Automatic bottleneck profile of one workload on the device, without the app UI,
# Steam or a game: about a minute.
#   1. calibrates what one proot stop and one Box64 native call cost right now;
#   2. runs the workload under pons-prof with proot's syscall counters on, while
#      sampling device temperatures;
#   3. pulls everything to $PP_PROF_DIR/<time>-<label>/ and prints a ranked report
#      (tools/dev/pp-prof-report.py).
# Default workload: loadsim (x86_64 through Box64, with the file fast path), which
# scans, reads and parses /usr/share like a game loading its data.
set -u
LABEL="${1:-loadsim}"; [ $# -gt 0 ] && shift
DIR=$(cd "$(dirname "$0")" && pwd)
ADB="${ADB:-/root/.local/mercati-android-tools/sdk/platform-tools/adb -P 5037}"
S="$ADB -s ${PP_SERIAL:?set PP_SERIAL}"
PKG=io.github.pons
D=/data/user/0/$PKG
B=/opt/pons/bench
FAST=/opt/pons/lib/libpons-fastpath.so
if [ $# -gt 0 ]; then
    CMD="$*"
else
    CMD="env LD_PRELOAD=$FAST box64 $B/loadsim-x86_64 /usr/share 20000 4"
fi
LOCAL="${PP_PROF_DIR:-/root/pons-bench/prof}/$(date +%Y%m%d-%H%M%S)-$LABEL"
mkdir -p "$LOCAL"
log() { echo "[pp-prof] $*" >&2; }

log "calibrating"
PP_SERIAL="$PP_SERIAL" "$DIR/pp-run.sh" "
$B/sysbench-arm64 | sed 's/^/proot /'
LD_PRELOAD=$FAST $B/sysbench-arm64 | sed 's/^/fast /'
$B/bridgecost-arm64 2>/dev/null | sed 's/^/native /'
box64 $B/bridgecost-x86_64 2>/dev/null | sed 's/^/box64 /'
" 2>/dev/null | tr -d '\r' > "$LOCAL/calibration.txt"

log "running: $CMD"
$S shell "run-as $PKG rm -rf cache/proot-tmp/pons-prof/$LABEL" >/dev/null 2>&1
$S shell 'while :; do D=$(dumpsys thermalservice 2>/dev/null); echo "$(date +%s) $(echo "$D" | sed -n "s/^Thermal Status: //p" | head -1) $(echo "$D" | sed -n "/Current temperatures from HAL/,/Current cooling/p" | grep -o "mValue=[0-9.]*, mType=[013]," | tr "=," "  " | awk "\$4==0{if(\$2>c)c=\$2} \$4==1{if(\$2>g)g=\$2} \$4==3{s=\$2} END{printf \"%.1f %.1f %.1f\", c, g, s}")"; sleep 2; done' > "$LOCAL/thermals.txt" 2>/dev/null &
THERM=$!
trap 'kill $THERM 2>/dev/null' EXIT INT TERM
start=$(date +%s)
PP_PROOT_ENV="PROOT_SYSCALL_STATS=$D/cache/proot-tmp/pons-prof/$LABEL/proot.txt" PP_SERIAL="$PP_SERIAL" \
    "$DIR/pp-run.sh" "mkdir -p /tmp/pons-prof/$LABEL && pons-prof /tmp/pons-prof/$LABEL $CMD" > "$LOCAL/run.log" 2>&1
log "done in $(( $(date +%s) - start )) s, collecting"
kill $THERM 2>/dev/null
$S exec-out "run-as $PKG tar -C cache/proot-tmp/pons-prof -cf - $LABEL" | tar -C "$LOCAL" --strip-components=1 -xf - 2>/dev/null
echo "$CMD" > "$LOCAL/command.txt"
python3 "$DIR/pp-prof-report.py" "$LOCAL" | tee "$LOCAL/report.txt"
