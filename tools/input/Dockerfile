# Cross-builds the small Pons helper programs that run inside Debian arm64.
FROM pons-mesa
RUN apt-get update && apt-get install -y --no-install-recommends libxtst-dev:arm64 g++ \
    && rm -rf /var/lib/apt/lists/*
ENTRYPOINT []
