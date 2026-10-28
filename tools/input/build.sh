#!/bin/sh
# Builds the helpers on Proxmox: -> /root/pinguplay-work/out/pinguplay-tools-<version>.tar.xz
set -eu
VERSION="$1"
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=/root/pinguplay-work/out
docker build -q -t pinguplay-tools "$HERE" >/dev/null
docker run --rm -v "$HERE":/src:ro -v "$OUT":/out pinguplay-tools sh -euc "
  mkdir -p /stage/opt/pinguplay/bin
  aarch64-linux-gnu-gcc -O2 -Wall -Wextra -o /stage/opt/pinguplay/bin/pinguplay-input /src/pinguplay-input.c -lX11 -lXtst
  aarch64-linux-gnu-strip /stage/opt/pinguplay/bin/pinguplay-input
  install -m 755 /src/bin/* /stage/opt/pinguplay/bin/
  install -D -m 644 /src/home/.box64rc /stage/root/.box64rc
  tar -C /stage -cJf /out/pinguplay-tools-$VERSION.tar.xz opt root
"
ls -la "$OUT/pinguplay-tools-$VERSION.tar.xz"
