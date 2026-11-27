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
# Signed with the release key, which is also Play's registered upload key (D-10). The spare
# play-upload key in .keystore/ is not registered with Play: do not sign with it.
[ -f android/release-signing.properties ] || [ -n "${FMWP_RELEASE_STORE_FILE:-}" ] \
  || { echo "✗ release signing properties missing (android/release-signing.properties)"; exit 1; }

GRADLEW=./gradlew; [ "${OS:-}" = Windows_NT ] && GRADLEW=./gradlew.bat # CI runs on Linux
(cd android && $GRADLEW --console=plain -q -Papp.version="$VER" testPlayDebugUnitTest bundlePlayRelease)
mkdir -p dist
OUT="dist/ponerdi-play-$VER.aab"
cp android/app/build/outputs/bundle/playRelease/app-play-release.aab "$OUT"
echo "✓ $OUT"
SIGNER=$(keytool -printcert -jarfile "$OUT" | grep -m1 SHA256 | sed 's/^\s*SHA256: *//' | tr -d '\r')
echo "  signer $SIGNER"
# Play's upload key is the release key (DECISIONS.md D-10); its certificate fingerprint is public.
RELEASE_SHA256="CF:D5:7C:0D:C3:8E:34:9F:35:A8:E9:9B:2F:5A:AA:17:BA:80:7A:24:BE:38:72:82:D0:8D:0F:A8:75:40:A3:7D"
[ "$SIGNER" = "$RELEASE_SHA256" ] || { echo "✗ not signed with the release key Play expects"; exit 1; }
echo "✓ signed with the registered upload key"
