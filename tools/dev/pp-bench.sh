#!/bin/sh
# pp-bench.sh <game>:<scene> [seconds] [label]     e.g. pp-bench.sh ck3:heavy 120
# pp-bench.sh <label> [seconds] [program]           any program, settings from env
#
# Runs one hands-off benchmark on the tablet over adb and prints one summary line:
#   1. stops any previous session and waits until the tablet has cooled down,
#      so runs start from the same temperature and can be compared;
#   2. prepares the same starting point and starts the app with the program
#      (debug builds accept it as an intent extra);
#   3. waits for the game's own "ready" log line, then steady frames, then settles;
#   4. samples every second ON THE TABLET (one adb stream, not one call per sample):
#      frames, late frames, CPU/GPU/skin temperatures, Android thermal status, free
#      memory, whether the app is still alive;
#   5. stops everything and appends the summary to $PP_BENCH_RESULTS.
# Game settings come from the profile tools/input/games/<game>.conf (the same file
# pons-game reads inside Linux); PP_BENCH_* variables override them.
# No touch, no mouse: repeatable in a loop (see pp-bench-loop.sh).
set -u
SECONDS_RUN="${2:-90}"
HOME_DIR="files/rootfs/root"
case "$1" in
    *:*)
        GAME_ID="${1%%:*}"; SCENE="${1#*:}"
        CONF="$(dirname "$0")/../input/games/$GAME_ID.conf"
        [ -f "$CONF" ] || { echo "no profile $CONF" >&2; exit 2; }
        LOG= READY= PREPARE=
        . "$CONF"
        eval "scene=\${SCENE_$SCENE:-}"
        [ -n "$scene" ] || { echo "profile $GAME_ID has no scene $SCENE" >&2; exit 2; }
        LABEL="${3:-$GAME_ID-$SCENE}"
        PROGRAM="pons-game $GAME_ID ${scene%%|*}"
        DEF_KEYS="${scene#*|}"
        # "~" in the profile is the Linux home; on the tablet that is $HOME_DIR.
        DEF_LOG=$(printf '%s' "$LOG" | sed "s|^~|$HOME_DIR|")
        DEF_READY="$READY"
        DEF_PREPARE=$(printf '%s' "$PREPARE" | sed "s|~|$HOME_DIR|g")
        ;;
    *)
        LABEL="$1"
        PROGRAM="${3:?program}"
        DEF_KEYS= DEF_LOG= DEF_READY= DEF_PREPARE=
        ;;
esac
ADB="${ADB:-/root/.local/mercati-android-tools/sdk/platform-tools/adb -P 5037}"
S="$ADB -s ${PP_SERIAL:?set PP_SERIAL}"
PKG=io.github.pons
COOL_C="${PP_BENCH_COOL_C:-45}"
COOL_SKIN_C="${PP_BENCH_COOL_SKIN_C:-40}"
READY_TIMEOUT="${PP_BENCH_READY_TIMEOUT:-600}"
SETTLE="${PP_BENCH_SETTLE:-20}"
GAME_LOG="${PP_BENCH_GAME_LOG:-$DEF_LOG}"
READY_LOG="${PP_BENCH_READY_LOG:-$DEF_READY}"
# Keys sent after settling, e.g. "space 5" to unpause a strategy game at top speed;
# "type:word" types text.
KEYS="${PP_BENCH_KEYS:-$DEF_KEYS}"
PREPARE="${PP_BENCH_PREPARE-$DEF_PREPARE}"
# Variables for the program, e.g. PP_BENCH_ENV="PONS_FASTPATH=0" for an A/B run.
PROGRAM="${PP_BENCH_ENV:+$PP_BENCH_ENV }$PROGRAM"
if [ "${PP_BENCH_DRY:-0}" = 1 ]; then
    printf 'label=%s\nprogram=%s\nkeys=%s\nlog=%s\nready=%s\nprepare=%s\n' \
        "$LABEL" "$PROGRAM" "$KEYS" "$GAME_LOG" "$READY_LOG" "$PREPARE"
    exit 0
fi
RESULTS="${PP_BENCH_RESULTS:-/root/pons-bench/results.tsv}"
RAW_DIR="$(dirname "$RESULTS")/raw"
mkdir -p "$RAW_DIR"
RAW="$RAW_DIR/$(date +%Y%m%d-%H%M%S)-$LABEL.log"

log() { echo "[pp-bench] $*" >&2; }

stop_all() {
    # Like the app: let Box64 save its translations first, so the next run's
    # loading measures the cached path a player gets from the second start on.
    $S shell "run-as $PKG sh -c 'pidof ck3 >/dev/null 2>&1 || ps -A -o ARGS | grep -q [c]k3 || exit 0; date > cache/proot-tmp/pons-dynacache-flush; sleep 4'" >/dev/null 2>&1
    $S shell "run-as $PKG sh -c 'for p in \$(ps -A -o PID,ARGS | grep -E \"[l]ibproot|[c]k3|[s]team|[X]vfb|[p]ulseaudio|[b]ox64\" | cut -c1-6); do kill -9 \$p; done' 2>/dev/null; am force-stop $PKG" >/dev/null 2>&1
}

# Safety brake: a run that eats the memory or overheats the device stops at once
# (a stuck load once froze the tablet until a reboot).
# Skin temperature, not the CPU sensor: CPU cores read 90+ C on every normal load,
# while Android throttles when the skin reaches 60 C.
MIN_FREE_MB="${PP_BENCH_MIN_FREE_MB:-800}"
MAX_SKIN_C="${PP_BENCH_MAX_SKIN_C:-58}"
unsafe() {
    last=$(grep '^S ' "$RAW" | tail -3)
    [ -z "$last" ] && return 1
    bad=$(echo "$last" | awk -v m="$MIN_FREE_MB" -v c="$MAX_SKIN_C" '$8 < m || $6 > c { n++ } END { print n + 0 }')
    if [ "$bad" -ge 3 ]; then
        log "SAFETY STOP: $(echo "$last" | tail -1 | awk '{ print "skin " $6 "C, free " $8 " MB" }')"
        stop_all
        return 0
    fi
    return 1
}

in_front() {
    $S shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>/dev/null | grep -q "$PKG"
}

skin_temp() {
    $S shell 'dumpsys thermalservice | sed -n "/Current temperatures from HAL/,/Current cooling/p" | grep -o "mValue=[0-9.]*, mType=3,"' \
        | tr '=,' '  ' | awk '{ printf "%d", $2 }'
}

cpu_temp() {
    $S shell 'dumpsys thermalservice | sed -n "/Current temperatures from HAL/,/Current cooling/p" | grep -o "mValue=[0-9.]*, mType=0,"' \
        | tr '=,' '  ' | awk '$2 > m { m = $2 } END { printf "%d", m }'
}

# Temperatures come from Android's thermal HAL (the values its throttling acts on),
# not raw sysfs zones, whose hottest core sensor spikes far above them.
# One device-side loop printing a sample line per second until killed.
SAMPLER='P=cache/proot-tmp/pons-present
while :; do
  # header u32 words 8..13: sequence (lo, hi), x, y, frames late >50 ms, >100 ms
  set -- $(run-as io.github.pons od -A n -t u4 -j 32 -N 24 $P 2>/dev/null) 0 0 0 0 0 0
  F=$1 L=$5 LL=$6
  T=$(date +%s.%N)
  D=$(dumpsys thermalservice 2>/dev/null)
  ST=$(echo "$D" | sed -n "s/^Thermal Status: //p" | head -1)
  Z=$(echo "$D" | sed -n "/Current temperatures from HAL/,/Current cooling/p" | grep -o "mValue=[0-9.]*, mType=[013]," | tr "=," "  " | awk "\$4==0{if(\$2>c)c=\$2} \$4==1{if(\$2>g)g=\$2} \$4==3{s=\$2} END{printf \"%d %d %d\", c, g, s}")
  M=$(awk "/MemAvailable/{printf \"%d\", \$2/1024}" /proc/meminfo)
  A=$(pidof io.github.pons >/dev/null && echo 1 || echo 0)
  echo "S ${F:-0} $T $Z ${ST:-?} $M $A $L $LL"
  sleep 1
done'

stop_all
# A stale log from the previous run would satisfy the ready check at once.
$S shell "run-as $PKG rm -f '$GAME_LOG'" >/dev/null 2>&1
# Same starting point every run: the game autosaves while it runs, and
# -continuelastsave would otherwise load wherever the last run stopped.
if [ -n "$PREPARE" ]; then
    $S shell "run-as $PKG sh -c \"$PREPARE\"" >/dev/null 2>&1 || log "prepare failed"
fi
# Start every run alike: cool CPU *and* skin (the skin cools much slower), and other
# apps' background processes closed, so memory is the same from run to run.
log "cooling down to CPU ${COOL_C}C / skin ${COOL_SKIN_C}C"
waited=0
while { [ "$(cpu_temp)" -gt "$COOL_C" ] || [ "$(skin_temp)" -gt "$COOL_SKIN_C" ]; } && [ "$waited" -lt 900 ]; do
    sleep 10; waited=$((waited + 10))
done
$S shell am kill-all >/dev/null 2>&1
start_temp=$(cpu_temp)

APP_UID=$($S shell pm list packages -U $PKG | sed -n 's/.*uid://p' | tr -d '\r')
log "starting: $PROGRAM"
$S shell "input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard; am start -n $PKG/.MainActivity --es program '$PROGRAM'" >/dev/null 2>&1
started=$(date +%s)

# Stream samples into the raw log for the whole run (readiness + measurement).
$S shell "$SAMPLER" > "$RAW" 2>/dev/null &
SAMPLER_PID=$!
trap 'kill $SAMPLER_PID 2>/dev/null; stop_all' EXIT INT TERM

# Ready = the game logged that it reached the map or menu (CK3 rewrites debug.log
# on every start), then frames flow. Loading screens also present steady frames,
# so frames alone would start measuring minutes too early.
log "waiting for: $READY_LOG (max ${READY_TIMEOUT}s)"
ready=0
away=0
load_top=0
while :; do
    sleep 5
    # Loading is the hottest phase: snapshot who works 60 s in.
    if [ "$load_top" = 0 ] && [ $(( $(date +%s) - started )) -ge 60 ]; then
        load_top=1
        $S shell "top -b -n 1 -d 2 -m 10 -o PID,%CPU,RES,NAME | cut -c1-120; P=\$(pidof ck3 | cut -d' ' -f1); [ -n \"\$P\" ] && top -H -b -n 1 -d 2 -m 12 -p \$P -o TID,%CPU,NAME" > "${RAW%.log}.loadtop" 2>/dev/null
    fi
    # Without a ready line (plain programs), steady frames alone mean ready.
    if [ -z "$READY_LOG" ] || $S shell "run-as $PKG grep -q -E \"$READY_LOG\" '$GAME_LOG'" 2>/dev/null; then
        ready=$(awk '/^S /{ if (p != "" && $2 - p >= 5) run++; else run = 0; p = $2 } END { print (run >= 3) }' "$RAW")
        [ "$ready" = 1 ] && break
    fi
    alive=$(tail -1 "$RAW" | awk '{print $9}')
    if unsafe; then break; fi
    # Android cuts the network of background apps, so Steam cannot sign in and the
    # game never starts: stop at once instead of waiting out the timeout.
    if in_front; then away=0; else away=$((away + 1)); fi
    if [ "$away" -ge 3 ]; then
        log "app not in front (is someone using the tablet?)"
        break
    fi
    if [ $(( $(date +%s) - started )) -gt "$READY_TIMEOUT" ] || [ "$alive" = 0 ]; then
        log "not ready (alive=$alive)"
        break
    fi
done
load_s=$(( $(date +%s) - started ))
# CPU seconds each process used to get here (user/system split), i.e. the cost of
# loading: name, pid, user s, system s. The app's processes share its uid.
$S shell 'U='"$APP_UID"'; for d in /proc/[0-9]*; do [ "$(stat -c %u $d 2>/dev/null)" = "$U" ] || continue; awk -v hz=100 "{ o=index(\$0,\"(\"); c=index(\$0,\") \"); n=substr(\$0,o+1,c-o-1); split(substr(\$0,c+2),f,\" \"); printf \"%s %s %.1f %.1f\n\", n, \$1, f[12]/hz, f[13]/hz }" $d/stat 2>/dev/null; done' > "${RAW%.log}.loadcpu" 2>/dev/null
if [ "$ready" = 1 ]; then
    # Games keep streaming assets for a while after the first steady frames.
    log "settling ${SETTLE}s"
    sleep "$SETTLE"
    for key in $KEYS; do
        case "$key" in
            type:*) action="type --delay 80 ${key#type:}" ;;
            *) action="key $key" ;;
        esac
        PP_SERIAL="${PP_SERIAL}" "$(dirname "$0")/pp-run.sh" "DISPLAY=:0 xdotool $action" >/dev/null 2>&1
        sleep 1
    done
fi
mark=$(wc -l < "$RAW")

if [ "$ready" = 1 ]; then
    log "measuring ${SECONDS_RUN}s"
    half=$((SECONDS_RUN / 2))
    waited=0
    while [ "$waited" -lt "$half" ]; do sleep 5; waited=$((waited + 5)); unsafe && break; done
    # Who burns the CPU: processes, then the game's busiest threads.
    $S shell "top -b -n 1 -d 2 -m 12 -o PID,%CPU,RES,NAME,ARGS | cut -c1-160; P=\$(pidof ck3 | cut -d' ' -f1); [ -n \"\$P\" ] && top -H -b -n 1 -d 2 -m 15 -p \$P -o TID,%CPU,NAME" > "${RAW%.log}.top" 2>/dev/null
    while [ "$waited" -lt "$SECONDS_RUN" ]; do sleep 5; waited=$((waited + 5)); unsafe && break; done
    # Proof of what was measured: the screen as the player saw it.
    $S exec-out screencap -p > "${RAW%.log}.png" 2>/dev/null
fi
kill $SAMPLER_PID 2>/dev/null
trap - EXIT INT TERM
stop_all

# Summary over the measured window only.
summary=$(tail -n +"$((mark + 1))" "$RAW" | awk -v ready="$ready" '
    /^S / {
        if (pt != "" && $3 > pt) { f = ($2 - pf) / ($3 - pt); fps[n++] = f; sum += f }
        pf = $2; pt = $3
        if ($4 > cmax) cmax = $4; if ($5 > gmax) gmax = $5; if ($6 > smax) smax = $6
        if ($7 > tmax) tmax = $7
        if (mmin == "" || $8 < mmin) mmin = $8
        if ($9 == 0) dead = 1
        if (l0 == "") { l0 = $10; ll0 = $11; t0 = $3 }
        l1 = $10; ll1 = $11; t1 = $3
    }
    END {
        if (!ready || n == 0) { printf "FAILED\t-\t-\t-\t-\t-\t-\t-\t-\t-"; exit }
        mins = (t1 - t0) / 60
        # sort fps ascending for the 5th percentile (worst seconds)
        for (i = 0; i < n; i++) for (j = i + 1; j < n; j++) if (fps[j] < fps[i]) { t = fps[i]; fps[i] = fps[j]; fps[j] = t }
        printf "%s\t%.1f\t%.1f\t%.1f\t%.1f\t%d\t%d\t%d\t%d\t%d", dead ? "KILLED" : "ok", sum / n, fps[int(n * 0.05)], \
            (l1 - l0) / mins, (ll1 - ll0) / mins, cmax, gmax, smax, tmax, mmin
    }')

[ -f "$RESULTS" ] || printf 'when\tlabel\tprogram\tstatus\tfps_avg\tfps_p5\tlate50_per_min\tlate100_per_min\tcpu_max_C\tgpu_max_C\tskin_max_C\tthermal_max\tmem_free_min_MB\tload_s\tstart_C\n' > "$RESULTS"
line=$(printf '%s\t%s\t%s\t%s\t%s\t%s' "$(date +%F_%T)" "$LABEL" "$PROGRAM" "$summary" "$load_s" "$start_temp")
echo "$line" >> "$RESULTS"
echo "$line"
