#!/bin/sh
# inflation.sh <box64 tarball> [workload...]
# Translation inflation, measured exactly: arm64 instructions executed by each
# transbench workload built for x86_64 and run through Box64, divided by the
# instructions of the same workload built natively for arm64. Lower is better.
# Both run under qemu with the instruction-count plugin (tools/box64/qemu), so the
# numbers are deterministic and need no ARM device. Box64's own startup and
# translation are included (they are part of what a game pays). Run on Proxmox.
set -eu
TAR="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"; shift
WORK="${*:-pathfinding economy matrix libm}"
HERE=$(cd "$(dirname "$0")" && pwd)
# Extra Box64 settings for the x86 run: BOX64_ENV="BOX64_DYNAREC_BIGBLOCK=3 ..."
docker run --rm -e BOX64_ENV="${BOX64_ENV:-}" -v "$TAR":/b64.tar.xz:ro -v "$HERE/../../transbench":/bench:ro pons-qemu sh -euc "
  mkdir -p /b64 && tar -C /b64 -xJf /b64.tar.xz
  B=\$(find /b64 -name box64 -type f | head -1)
  for kv in \$BOX64_ENV; do export \$kv; done
  g++ -O2 -std=c++17 -march=x86-64-v2 -ffp-contract=off -pthread -o /tmp/tb-x86 /bench/transbench.cpp
  aarch64-linux-gnu-g++ -O2 -std=c++17 -march=armv8.7-a -ffp-contract=off -pthread -o /tmp/tb-arm /bench/transbench.cpp
  Q='/opt/qemu/bin/qemu-aarch64 -L /usr/aarch64-linux-gnu -plugin /opt/qemu/libinsn.so -d plugin'
  count() { \$Q \"\$@\" 2>&1 >/dev/null | sed -n 's/.*insns: *\([0-9]*\).*/\1/p' | tail -1; }
  printf '%-12s %15s %15s %8s\n' workload native box64 ratio
  for w in $WORK; do
    n=\$(count /tmp/tb-arm \$w)
    x=\$(BOX64_LOG=0 BOX64_NOBANNER=1 BOX64_DYNACACHE=0 count \$B /tmp/tb-x86 \$w)
    awk -v w=\$w -v n=\$n -v x=\$x 'BEGIN { printf \"%-12s %15d %15d %8.2f\n\", w, n, x, x / n }'
  done"
