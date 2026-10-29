# qemu-aarch64 (user mode) with TCG plugins and the instruction-counting plugin:
# counts the arm64 instructions a workload executes, an exact and repeatable
# proxy to compare Box64 builds without an ARM device. Debian's qemu-user has
# no plugin support.
FROM pons-box64test
ARG QEMU=10.0.6
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends \
        ninja-build python3-venv pkg-config libglib2.0-dev flex bison wget xz-utils >/dev/null \
    && wget -q https://download.qemu.org/qemu-$QEMU.tar.xz \
    && tar xf qemu-$QEMU.tar.xz && cd qemu-$QEMU \
    && ./configure --target-list=aarch64-linux-user --enable-plugins --disable-docs \
        --disable-werror --prefix=/opt/qemu >/dev/null \
    && make -j"$(nproc)" >/dev/null && make install >/dev/null \
    && gcc -O2 -shared -fPIC -I include/qemu $(pkg-config --cflags glib-2.0) \
        -o /opt/qemu/libinsn.so tests/tcg/plugins/insn.c $(pkg-config --libs glib-2.0) \
    && cd / && rm -rf qemu-$QEMU qemu-$QEMU.tar.xz && rm -rf /var/lib/apt/lists/*
