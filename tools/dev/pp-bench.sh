#!/bin/sh
# pp-bench.sh <label> [seconds] [program]
#
# Runs one hands-off benchmark on the tablet over adb and prints one summary line:
#   1. stops any previous session and waits until the tablet has cooled down,
#      so runs start from the same temperature and can be compared;
#   2. starts the app with <program> (debug builds accept it as an intent extra);
#   3. waits until the game presents frames steadily (loading is not measured);
#   4. samples every second ON THE TABLET (one adb stream, not one call per sample):
#      frames, CPU/GPU/skin temperatures, Android thermal status, free memory,
#      whether the app is still alive;
#   5. stops everything and appends the summary to $PP_BENCH_RESULTS.
# No touch, no mouse: repeatable in a loop (see pp-bench-loop.sh).
set -u
LABEL="${1:?label}"
SECONDS_RUN="${2:-90}"
PROGRAM="${3:-pinguplay-ck3 -continuelastsave}"
ADB="${ADB:-/root/.local/mercati-android-tools/sdk/platform-tools/adb -P 5037}"
S="$ADB -s ${PP_SERIAL:?set PP_SERIAL}"
PKG=io.github.pinguplay
COOL_C="${PP_BENCH_COOL_C:-45}"
READY_TIMEOUT="${PP_BENCH_READY_TIMEOUT:-600}"
SETTLE="${PP_BENCH_SETTLE:-20}"
GAME_LOG="${PP_BENCH_GAME_LOG:-files/rootfs/root/.local/share/Paradox Interactive/Crusader Kings III/logs/debug.log}"
READY_LOG="${PP_BENCH_READY_LOG:-Setting idler 'In Game'|Held the main menu}"
# Keys sent after settling, e.g. "space 5" to unpause a strategy game at top speed;
# "type:word" types text (CK3 observer: "grave type:observe Return grave space 5").
KEYS="${PP_BENCH_KEYS:-}"
CK3_USER="files/rootfs/root/.local/share/Paradox Interactive/Crusader Kings III"
PREPARE="${PP_BENCH_PREPARE-cd '$CK3_USER' && cp 'save games/Duke_Vratislav_II_of_Bohemia_1066_09_15.ck3' last_save.ck3}"
RESULTS="${PP_BENCH_RESULTS:-/root/pinguplay-bench/results.tsv}"
RAW_DIR="$(dirname "$RESULTS")/raw"
mkdir -p "$RAW_DIR"
RAW="$RAW_DIR/$(date +%Y%m%d-%H%M%S)-$LABEL.log"

log() { echo "[pp-bench] $*" >&2; }

stop_all() {
    # Like the app: let Box64 save its translations first, so the next run's
    # loading measures the cached path a player gets from the second start on.
    $S shell "run-as $PKG sh -c 'pidof ck3 >/dev/null 2>&1 || ps -A -o ARGS | grep -q [c]k3 || exit 0; date > cache/proot-tmp/pinguplay-dynacache-flush; sleep 4'" >/dev/null 2>&1
    $S shell "run-as $PKG sh -c 'for p in \$(ps -A -o PID,ARGS | grep -E \"[l]ibproot|[c]k3|[s]team|[X]vfb|[p]ulseaudio|[b]ox64\" | cut -c1-6); do kill -9 \$p; done' 2>/dev/null; am force-stop $PKG" >/dev/null 2>&1
}

in_front() {
    $S shell "dumpsys activity activities | grep -m1 topResumedActivity" 2>/dev/null | grep -q "$PKG"
}

cpu_temp() {
    $S shell 'dumpsys thermalservice | sed -n "/Current temperatures from HAL/,/Current cooling/p" | grep -o "mValue=[0-9.]*, mType=0,"' \
        | tr '=,' '  ' | awk '$2 > m { m = $2 } END { printf "%d", m }'
}

# Temperatures come from Android's thermal HAL (the values its throttling acts on),
# not raw sysfs zones, whose hottest core sensor spikes far above them.
# One device-side loop printing a sample line per second until killed.
SAMPLER='P=cache/proot-tmp/pinguplay-present
while :; do
  # header u32 words 8..13: sequence (lo, hi), x, y, frames late >50 ms, >100 ms
  set -- $(run-as io.github.pinguplay od -A n -t u4 -j 32 -N 24 $P 2>/dev/null) 0 0 0 0 0 0
  F=$1 L=$5 LL=$6
  T=$(date +%s.%N)
  D=$(dumpsys thermalservice 2>/dev/null)
  ST=$(echo "$D" | sed -n "s/^Thermal Status: //p" | head -1)
  Z=$(echo "$D" | sed -n "/Current temperatures from HAL/,/Current cooling/p" | grep -o "mValue=[0-9.]*, mType=[013]," | tr "=," "  " | awk "\$4==0{if(\$2>c)c=\$2} \$4==1{if(\$2>g)g=\$2} \$4==3{s=\$2} END{printf \"%d %d %d\", c, g, s}")
  M=$(awk "/MemAvailable/{printf \"%d\", \$2/1024}" /proc/meminfo)
  A=$(pidof io.github.pinguplay >/dev/null && echo 1 || echo 0)
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
log "cooling down to ${COOL_C}C"
waited=0
while [ "$(cpu_temp)" -gt "$COOL_C" ] && [ "$waited" -lt 600 ]; do sleep 10; waited=$((waited + 10)); done
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
    if $S shell "run-as $PKG grep -q -E \"$READY_LOG\" '$GAME_LOG'" 2>/dev/null; then
        ready=$(awk '/^S /{ if (p != "" && $2 - p >= 5) run++; else run = 0; p = $2 } END { print (run >= 3) }' "$RAW")
        [ "$ready" = 1 ] && break
    fi
    alive=$(tail -1 "$RAW" | awk '{print $9}')
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
    sleep "$half"
    # Who burns the CPU: processes, then the game's busiest threads.
    $S shell "top -b -n 1 -d 2 -m 12 -o PID,%CPU,RES,NAME,ARGS | cut -c1-160; P=\$(pidof ck3 | cut -d' ' -f1); [ -n \"\$P\" ] && top -H -b -n 1 -d 2 -m 15 -p \$P -o TID,%CPU,NAME" > "${RAW%.log}.top" 2>/dev/null
    sleep $((SECONDS_RUN - half))
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
