#!/usr/bin/env bash
# Finishes setup once the two Firebase files exist. Run from Git Bash at the repo root:
#
#   scripts/finish-firebase.sh [google-services.json] [service-account.json]
#
# Without arguments it picks the newest matching files in ~/Downloads.
# 1. checks both files belong to the same Firebase project and to this app's package
# 2. copies google-services.json to android/app/ (git-ignored)
# 3. stores the service-account key outside the repo and puts it into the relay Worker secret
# 4. runs the tests and builds the signed release APK into dist/
# Secrets are never printed.
set -euo pipefail
cd "$(dirname "$0")/.."

PKG="io.github.jeiel85.findmywifephone"
DL="${USERPROFILE:-$HOME}/Downloads"
GS="${1:-$(ls -t "$DL"/google-services*.json 2>/dev/null | head -1 || true)}"
SA="${2:-$(ls -t "$DL"/*firebase-adminsdk*.json 2>/dev/null | head -1 || true)}"

[ -n "$GS" ] && [ -f "$GS" ] || { echo "✗ google-services.json not found (give the path as the 1st argument)"; exit 1; }
[ -n "$SA" ] && [ -f "$SA" ] || { echo "✗ service-account JSON not found (give the path as the 2nd argument)"; exit 1; }
echo "• google-services: $GS"
echo "• service account: $(basename "$SA")"

python - "$GS" "$SA" "$PKG" <<'EOF'
import json, sys
gs = json.load(open(sys.argv[1], encoding="utf-8"))
sa = json.load(open(sys.argv[2], encoding="utf-8"))
pkg = sys.argv[3]
pkgs = [c["client_info"]["android_client_info"]["package_name"] for c in gs.get("client", [])]
assert pkg in pkgs, f"google-services.json has no Android app '{pkg}' (found: {pkgs})"
assert sa.get("type") == "service_account" and sa.get("private_key") and sa.get("client_email"), "not a service-account key"
gp, sp = gs["project_info"]["project_id"], sa.get("project_id")
assert gp == sp, f"project mismatch: google-services is '{gp}', service account is '{sp}'"
print(f"✓ both files belong to Firebase project '{gp}'")
EOF

cp "$GS" android/app/google-services.json
echo "✓ android/app/google-services.json"

KEEP="${LOCALAPPDATA:-$HOME/.local/share}/find-my-wife-phone"
mkdir -p "$KEEP"
cp "$SA" "$KEEP/service-account.json"
if command -v icacls >/dev/null 2>&1; then
  MSYS_NO_PATHCONV=1 icacls "$(cygpath -w "$KEEP/service-account.json")" /inheritance:r /grant:r "${USERNAME}:F" >/dev/null
fi
echo "✓ key kept at $KEEP/service-account.json (current user only)"

(cd relay && npx wrangler secret put FCM_SERVICE_ACCOUNT < "$KEEP/service-account.json" >/dev/null)
echo "✓ relay secret FCM_SERVICE_ACCOUNT set"

(cd relay && npm test --silent >/dev/null) && echo "✓ relay tests"
(cd android && ./gradlew.bat --console=plain -q testDebugUnitTest assembleDebug assembleRelease)
mkdir -p dist
cp android/app/build/outputs/apk/release/app-release.apk dist/find-my-wife-phone.apk
cp android/app/build/outputs/apk/debug/app-debug.apk dist/find-my-wife-phone-debug.apk
echo "✓ dist/find-my-wife-phone.apk  sha256 $(sha256sum dist/find-my-wife-phone.apk | cut -d' ' -f1)"
echo
echo "Next: install on both phones (adb install -r dist/find-my-wife-phone.apk), pair them, and run the checklist in design/VERIFY.md."
