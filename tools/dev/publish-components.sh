#!/bin/sh
# Upload to the GitHub "components" release every file Component.kt pins and the
# release does not have yet, after checking its SHA-256. Run where the files are
# (the build host): publish-components.sh <dir with the .tar.xz files>
# Without this the published app cannot download a new Box64/Mesa/tools version.
set -eu
DIR="${1:?usage: publish-components.sh <dir with the component files>}"
REPO=tedin7/pons
TAG=components
KT="$(cd "$(dirname "$0")/../.." && pwd)/app/src/main/java/io/github/pons/linux/Component.kt"

have=$(gh release view "$TAG" -R "$REPO" --json assets -q '.assets[].name')
# Pairs "sha256 file" in the order they appear in Component.kt.
sed -n 's/.*sha256 = "\([0-9a-f]*\)".*/\1/p; s/.*url = "\${BASE}\([^"]*\)".*/\1/p' "$KT" | paste - - |
while read -r sum name; do
    if printf '%s\n' "$have" | grep -qx "$name"; then
        echo "ok        $name"
        continue
    fi
    [ -f "$DIR/$name" ] || { echo "MISSING   $DIR/$name" >&2; exit 1; }
    echo "$sum  $DIR/$name" | sha256sum -c --quiet - || { echo "BAD SHA   $name" >&2; exit 1; }
    gh release upload "$TAG" -R "$REPO" "$DIR/$name"
    echo "uploaded  $name"
done
