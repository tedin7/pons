#!/bin/sh
# pp-fastpath-check.sh: runs fastpath-check on the tablet under proot, without and
# with the fast-path library, and shows any line where the two disagree.
set -u
DIR=$(dirname "$0")
A=$(PP_SERIAL="${PP_SERIAL:?set PP_SERIAL}" "$DIR/pp-run.sh" /opt/pinguplay/bench/fastpath-check 2>&1 | tr -d '\r')
B=$(PP_SERIAL="$PP_SERIAL" "$DIR/pp-run.sh" 'LD_PRELOAD=/opt/pinguplay/lib/libpinguplay-fastpath.so /opt/pinguplay/bench/fastpath-check' 2>&1 | tr -d '\r')
TMP=$(mktemp -d)
# /proc/self is a different process each run: its inode always differs.
echo "$A" | grep -v "/proc/self" > "$TMP/proot"; echo "$B" | grep -v "/proc/self" > "$TMP/fast"
echo "lines: $(wc -l < "$TMP/proot") proot, $(wc -l < "$TMP/fast") fast"
if diff "$TMP/proot" "$TMP/fast"; then echo "SAME"; fi
rm -rf "$TMP"
