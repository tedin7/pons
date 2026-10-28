# Cross-builds the small Pinguplay helper programs that run inside Debian arm64.
FROM pinguplay-mesa
RUN apt-get update && apt-get install -y --no-install-recommends libxtst-dev:arm64 g++ \
    && rm -rf /var/lib/apt/lists/*
ENTRYPOINT []
