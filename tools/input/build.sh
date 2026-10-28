#!/bin/sh
# Builds the helpers on Proxmox: -> /root/pinguplay-work/out/pinguplay-tools-<version>.tar.xz
set -eu
VERSION="$1"
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=/root/pinguplay-work/out
docker build -q -t pinguplay-tools "$HERE" >/dev/null
docker run --rm -v "$HERE":/src:ro -v "$HERE/../compat":/compat:ro -v "$HERE/../transbench":/bench:ro -v "$OUT":/out pinguplay-tools sh -euc "
  mkdir -p /stage/opt/pinguplay/bin
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -o /stage/opt/pinguplay/bin/pinguplay-input /src/pinguplay-input.c -lX11 -lXtst
  aarch64-linux-gnu-strip /stage/opt/pinguplay/bin/pinguplay-input
  install -m 755 /src/bin/* /stage/opt/pinguplay/bin/
  install -D -m 644 /src/home/.box64rc /stage/root/.box64rc
  mkdir -p /stage/opt/pinguplay/lib
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -shared -fPIC -o /stage/opt/pinguplay/lib/libpinguplay-robust.so /compat/pinguplay-robust.c -ldl
  aarch64-linux-gnu-strip /stage/opt/pinguplay/lib/libpinguplay-robust.so
  mkdir -p /stage/opt/pinguplay/bench
  # Same flags on both sides; no FMA contraction so results match bit for bit.
  g++ -O2 -std=c++17 -march=x86-64-v2 -ffp-contract=off -pthread -o /stage/opt/pinguplay/bench/transbench-x86_64 /bench/transbench.cpp
  aarch64-linux-gnu-g++ -O2 -std=c++17 -march=armv8.7-a -ffp-contract=off -pthread -o /stage/opt/pinguplay/bench/transbench-arm64 /bench/transbench.cpp
  gcc -O2 -fno-builtin -march=x86-64-v2 -Wl,-z,now -o /stage/opt/pinguplay/bench/bridgecost-x86_64 /bench/bridgecost.c -lm
  aarch64-linux-gnu-gcc -O2 -fno-builtin -march=armv8.7-a -o /stage/opt/pinguplay/bench/bridgecost-arm64 /bench/bridgecost.c -lm
  strip /stage/opt/pinguplay/bench/transbench-x86_64 /stage/opt/pinguplay/bench/bridgecost-x86_64
  aarch64-linux-gnu-strip /stage/opt/pinguplay/bench/transbench-arm64 /stage/opt/pinguplay/bench/bridgecost-arm64
  tar -C /stage -cJf /out/pinguplay-tools-$VERSION.tar.xz opt root
"
ls -la "$OUT/pinguplay-tools-$VERSION.tar.xz"
