# Correctness bench for our Box64 builds without an ARM device: qemu runs the
# arm64 Box64, Box64 runs Box64's own x86_64 regression tests and our benchmarks.
FROM pons-tools
RUN apt-get update -qq && apt-get install -y -qq --no-install-recommends qemu-user >/dev/null \
    && rm -rf /var/lib/apt/lists/*
COPY run-tests.py /run-tests.py
