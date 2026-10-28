#!/bin/sh
# pp-bench-loop.sh <runs> <label> [seconds] [program]
# Repeats pp-bench.sh so one change can be judged on several runs, not one lucky sample.
# Each run starts cool (pp-bench.sh waits for it); results go to the same table.
set -u
RUNS="${1:?runs}"; shift
DIR=$(dirname "$0")
i=1
while [ "$i" -le "$RUNS" ]; do
    "$DIR/pp-bench.sh" "$@"
    i=$((i + 1))
done
