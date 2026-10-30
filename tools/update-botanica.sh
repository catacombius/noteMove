#!/bin/sh
# Rebuilds Botanica's engine (an AAR) from a Botanica checkout and puts it where the Garden edition uses it.
#   tools/update-botanica.sh /path/to/botanica
# (the checkout's android/ folder needs the Android SDK and NDK, as for building Botanica itself)
set -e
BOTANICA=${1:?"usage: $0 /path/to/botanica"}
HERE=$(cd "$(dirname "$0")/.." && pwd)
(cd "$BOTANICA/android" && ./gradlew :botanica-engine:assembleRelease)
cp "$BOTANICA/android/botanica-engine/build/outputs/aar/botanica-engine-release.aar" "$HERE/app/libs/"
echo "app/libs/botanica-engine-release.aar updated from $(git -C "$BOTANICA" rev-parse --short HEAD)"
