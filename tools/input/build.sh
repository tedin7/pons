#!/bin/sh
# Builds the helpers on Proxmox: -> /root/pons-work/out/pons-tools-<version>.tar.xz
set -eu
VERSION="$1"
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=/root/pons-work/out
docker build -q -t pons-tools "$HERE" >/dev/null
docker run --rm -v "$HERE":/src:ro -v "$HERE/../compat":/compat:ro -v "$HERE/../transbench":/bench:ro -v "$OUT":/out pons-tools sh -euc "
  mkdir -p /stage/opt/pons/bin
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -o /stage/opt/pons/bin/pons-input /src/pons-input.c -lX11 -lXtst
  aarch64-linux-gnu-strip /stage/opt/pons/bin/pons-input
  install -m 755 /src/bin/* /stage/opt/pons/bin/
  install -D -m 644 /src/home/.box64rc /stage/root/.box64rc
  install -d /stage/opt/pons/games && install -m 644 /src/games/*.conf /stage/opt/pons/games/
  mkdir -p /stage/opt/pons/lib
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -shared -fPIC -o /stage/opt/pons/lib/libpons-robust.so /compat/pons-robust.c /compat/pons-sockfast.c -ldl
  aarch64-linux-gnu-strip /stage/opt/pons/lib/libpons-robust.so
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -shared -fPIC -DPONS_SOCKFAST_NO_CLOSE -o /stage/opt/pons/lib/libpons-fastpath.so /compat/pons-fastpath.c /compat/pons-sockfast.c -ldl
  aarch64-linux-gnu-strip /stage/opt/pons/lib/libpons-fastpath.so
  mkdir -p /stage/opt/pons/bench
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -o /stage/opt/pons/bench/fastpath-check /compat/fastpath-check.c
  # Same flags on both sides; no FMA contraction so results match bit for bit.
  g++ -O2 -std=c++17 -march=x86-64-v2 -ffp-contract=off -pthread -o /stage/opt/pons/bench/transbench-x86_64 /bench/transbench.cpp
  aarch64-linux-gnu-g++ -O2 -std=c++17 -march=armv8.7-a -ffp-contract=off -pthread -o /stage/opt/pons/bench/transbench-arm64 /bench/transbench.cpp
  gcc -O2 -fno-builtin -march=x86-64-v2 -Wl,-z,now -o /stage/opt/pons/bench/bridgecost-x86_64 /bench/bridgecost.c -lm
  aarch64-linux-gnu-gcc -O2 -fno-builtin -march=armv8.7-a -o /stage/opt/pons/bench/bridgecost-arm64 /bench/bridgecost.c -lm
  gcc -O2 -Wall -Wextra -march=x86-64-v2 -o /stage/opt/pons/bench/sysbench-x86_64 /bench/sysbench.c
  gcc -O2 -Wall -Wextra -o /stage/opt/pons/bench/idiombench-x86_64 /bench/idiombench.c
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -march=armv8.7-a -o /stage/opt/pons/bench/sysbench-arm64 /bench/sysbench.c
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -march=armv8.7-a -static -o /stage/opt/pons/bench/sysbench-arm64-static /bench/sysbench.c
  gcc -O2 -Wall -Wextra -march=x86-64-v2 -pthread -o /stage/opt/pons/bench/loadsim-x86_64 /bench/loadsim.c -lm
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -march=armv8.7-a -pthread -o /stage/opt/pons/bench/loadsim-arm64 /bench/loadsim.c -lm
  strip /stage/opt/pons/bench/loadsim-x86_64
  aarch64-linux-gnu-strip /stage/opt/pons/bench/loadsim-arm64
  strip /stage/opt/pons/bench/idiombench-x86_64 /stage/opt/pons/bench/transbench-x86_64 /stage/opt/pons/bench/bridgecost-x86_64 /stage/opt/pons/bench/sysbench-x86_64
  aarch64-linux-gnu-strip /stage/opt/pons/bench/sysbench-arm64 /stage/opt/pons/bench/sysbench-arm64-static
  aarch64-linux-gnu-strip /stage/opt/pons/bench/transbench-arm64 /stage/opt/pons/bench/bridgecost-arm64
  tar -C /stage -cJf /out/pons-tools-$VERSION.tar.xz opt root
"
ls -la "$OUT/pons-tools-$VERSION.tar.xz"
