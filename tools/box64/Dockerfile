# Cross-builds Box64 (x86_64 -> arm64 translator, with 32-bit Box32 support) for Debian arm64.
FROM debian:trixie
RUN apt-get update && apt-get install -y --no-install-recommends \
      crossbuild-essential-arm64 cmake ninja-build python3 git ca-certificates xz-utils \
    && rm -rf /var/lib/apt/lists/*
COPY build.sh /build/
ENTRYPOINT ["/build/build.sh"]
