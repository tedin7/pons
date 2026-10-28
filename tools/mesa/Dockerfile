# Cross-builds Mesa (Turnip with Android KGSL support + Zink) for Debian arm64.
FROM debian:trixie
RUN dpkg --add-architecture arm64 && apt-get update && apt-get install -y --no-install-recommends \
      crossbuild-essential-arm64 meson ninja-build pkg-config python3-mako python3-yaml \
      python3-packaging bison flex glslang-tools curl xz-utils ca-certificates \
      libdrm-dev:arm64 libx11-dev:arm64 libxext-dev:arm64 libxfixes-dev:arm64 \
      libx11-xcb-dev:arm64 libxcb-glx0-dev:arm64 libxcb-shm0-dev:arm64 libxcb-dri3-dev:arm64 \
      libxcb-present-dev:arm64 libxcb-randr0-dev:arm64 libxcb-sync-dev:arm64 \
      libxcb-xfixes0-dev:arm64 libxshmfence-dev:arm64 libxxf86vm-dev:arm64 libxrandr-dev:arm64 \
      zlib1g-dev:arm64 libexpat1-dev:arm64 libzstd-dev:arm64 \
    && rm -rf /var/lib/apt/lists/*
COPY cross-aarch64.ini build.sh /build/
COPY patches /build/patches
ENTRYPOINT ["/build/build.sh"]
