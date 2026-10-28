#!/bin/sh
# pp-monitor-host.sh <seconds>: every 2 s, one adb call -> fps, busiest CK3 threads, temps, free memory.
S="${ADB:-/root/.local/mercati-android-tools/sdk/platform-tools/adb -P 5037} -s ${PP_SERIAL:?set PP_SERIAL}"
LOG="${PP_MONITOR_LOG:-/tmp/pp-monitor.log}"
END=$(( $(date +%s) + $1 ))
prev=""; pt=""
while [ "$(date +%s)" -lt "$END" ]; do
  out=$($S shell 'P=$(pidof ck3 | cut -d" " -f1); F=$(run-as io.github.pinguplay od -A n -t u8 -j 32 -N 8 cache/proot-tmp/pinguplay-present 2>/dev/null | tr -d " "); echo "F $F $(date +%s.%N)"; [ -n "$P" ] && top -H -b -n 1 -d 1 -p $P -o %CPU,CMD 2>/dev/null | tail -n +6 | sort -rn | head -5 | awk "{printf \"T %s:%s\n\", \$2, \$1}"; for z in /sys/class/thermal/thermal_zone*; do echo "Z $(cat $z/type) $(cat $z/temp)"; done 2>/dev/null; grep -E "MemAvailable" /proc/meminfo; dumpsys window | grep -m1 mCurrentFocus | grep -c pinguplay' 2>/dev/null)
  f=$(echo "$out" | awk '/^F /{print $2, $3}')
  set -- $f; fr=$1; t=$2
  fps=0; [ -n "$prev" ] && fps=$(awk -v a="$prev" -v b="$fr" -v t1="$pt" -v t2="$t" 'BEGIN{printf "%.1f", (b-a)/(t2-t1)}')
  prev=$fr; pt=$t
  echo "$(date +%T) fps=$fps $(echo "$out" | awk '/^Z cpu/{if($3>c)c=$3} /^Z gpu/{if($3>g)g=$3} /MemAvailable/{m=$2} END{printf "cpu=%.0fC gpu=%.0fC memfree=%dMB", c/1000, g/1000, m/1024}') app_in_front=$(echo "$out" | tail -1) | $(echo "$out" | awk '/^T /{printf "%s ", $2}')" >> "$LOG"
  sleep 1
done
