#!/usr/bin/env bash
# Builds the Google Play AAB (play flavor: no self-updater; same release key — DECISIONS.md D-10).
#
#   scripts/play-bundle.sh 0.3.0
#
# Output: dist/ponerdi-play-<version>.aab, to upload in Play Console (internal testing first).
set -euo pipefail
cd "$(dirname "$0")/.."
VER="${1:?usage: scripts/play-bundle.sh <version>}"

[ -f android/app/google-services.json ] || { echo "✗ android/app/google-services.json missing"; exit 1; }
[ -f android/play-upload-signing.properties ] || [ -n "${FMWP_PLAY_UPLOAD_STORE_FILE:-}" ] \
  || { echo "✗ Play upload signing properties missing"; exit 1; }

(cd android && ./gradlew.bat --console=plain -q -Papp.version="$VER" testPlayDebugUnitTest bundlePlayRelease)
mkdir -p dist
OUT="dist/ponerdi-play-$VER.aab"
cp android/app/build/outputs/bundle/playRelease/app-play-release.aab "$OUT"
echo "✓ $OUT"
keytool -printcert -jarfile "$OUT" | grep -m1 SHA256 | sed 's/^\s*/  signer /'
