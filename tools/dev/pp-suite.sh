#!/bin/sh
# pp-suite.sh [label]: runs pons-suite on the tablet (inside Linux, no screen,
# works with the app in the background) plus the native syscall test OUTSIDE proot,
# so the table shows what proot itself costs. Appends to $PP_SUITE_RESULTS.
set -u
LABEL="${1:-suite}"
ADB="${ADB:-adb -P 5037}"
S="$ADB -s ${PP_SERIAL:?set PP_SERIAL}"
RESULTS="${PP_SUITE_RESULTS:-/root/pons-bench/suite.tsv}"
mkdir -p "$(dirname "$RESULTS")"
WHEN=$(date +%F_%T)

{
    PP_SERIAL="$PP_SERIAL" "$(dirname "$0")/pp-run.sh" pons-suite 2>/dev/null
    # Static arm64 build, run straight from the app's data folder: no proot at all.
    $S shell "run-as io.github.pons files/rootfs/opt/pons/bench/sysbench-arm64-static" 2>/dev/null \
        | awk '{printf "syscall %s-noproot %s ns\n", $1, $2}'
} | tr -d '\r' | grep -E '^(box64|bridge|syscall) ' | while read -r layer test value unit; do
    printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$WHEN" "$LABEL" "$layer" "$test" "$value" "$unit" | tee -a "$RESULTS"
done
