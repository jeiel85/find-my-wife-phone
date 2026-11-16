#!/usr/bin/env bash
# Publishes a release after design/VERIFY.md passed on real phones.
#
#   scripts/release.sh 0.1.0
#
# Builds with that version, creates tag + GitHub release with the APK and its SHA-256,
# installs the same APK on every connected phone (local deployment), and opens the
# "Hardening candidates" issue from design/hardening-<version>.md when it exists.
set -euo pipefail
cd "$(dirname "$0")/.."
VER="${1:?usage: scripts/release.sh <version>}"
TAG="v$VER"

[ -f android/app/google-services.json ] || { echo "✗ run scripts/finish-firebase.sh first"; exit 1; }
[ -f android/release-signing.properties ] || { echo "✗ release signing properties missing"; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "✗ working tree not clean"; exit 1; }
git fetch -q origin && [ "$(git rev-parse HEAD)" = "$(git rev-parse origin/main)" ] || { echo "✗ main is not in sync with origin/main"; exit 1; }

(cd relay && npm test --silent >/dev/null) && echo "✓ relay tests"
(cd android && ./gradlew.bat --console=plain -q -Papp.version="$VER" testGithubDebugUnitTest testPlayDebugUnitTest assembleGithubRelease)
mkdir -p dist
cp android/app/build/outputs/apk/github/release/app-github-release.apk dist/find-my-wife-phone.apk
SHA=$(sha256sum dist/find-my-wife-phone.apk | cut -d' ' -f1)

NOTES=$(mktemp)
awk -v v="## $TAG" 'index($0, v)==1 {on=1; next} /^## v/ {on=0} on' CHANGELOG.md > "$NOTES"
printf '\n---\n**APK SHA-256** `%s`\n\n두 폰 모두 설치한 뒤 앱에서 QR로 연결하세요. 자세한 안내는 https://jeiel85.github.io/find-my-wife-phone/ 에 있어요.\n' "$SHA" >> "$NOTES"
gh release create "$TAG" dist/find-my-wife-phone.apk --title "포너디 $TAG" --notes-file "$NOTES" --target main
rm -f "$NOTES"
echo "✓ released $TAG (sha256 $SHA)"

# Local deployment: the phones in this house run the released build.
for d in $(adb devices | awk 'NR>1 && $2=="device" {print $1}'); do
  adb -s "$d" install -r dist/find-my-wife-phone.apk >/dev/null && echo "✓ installed on $d"
done

if [ -f "design/hardening-$VER.md" ]; then
  gh issue create --title "Hardening candidates: $TAG" --body-file "design/hardening-$VER.md" >/dev/null && echo "✓ hardening issue opened"
fi
