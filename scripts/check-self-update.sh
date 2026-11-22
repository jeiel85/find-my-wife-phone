#!/usr/bin/env bash
# Checks that the GitHub build still updates itself: installs an older GitHub release on the
# emulator, wakes its update worker, and waits until the latest release replaced it (hardening #4).
#
#   scripts/check-self-update.sh            # previous release -> latest release
#   scripts/check-self-update.sh 0.4.0      # v0.4.0 -> latest release
#
# Run it right after scripts/release.sh published the new release. It UNINSTALLS 포너디 from the
# device (its pairings and history are lost), so it refuses anything but an emulator — use
# fmwp-phone-api36 and tell other sessions that share it first. Needs gh (to download the APK).
set -euo pipefail
cd "$(dirname "$0")/.."
PKG=com.jeiel85.ponerdi
REPO=jeiel85/find-my-wife-phone
WAIT_S=${WAIT_S:-300}

[ "$(adb devices | awk 'NR>1 && $2=="device"' | wc -l)" = 1 ] || { echo "✗ need exactly one adb device (set ANDROID_SERIAL)"; exit 1; }
[ "$(adb shell getprop ro.kernel.qemu | tr -d '\r')" = 1 ] || { echo "✗ not an emulator; this uninstalls 포너디"; exit 1; }

LATEST=$(gh release view --repo "$REPO" --json tagName --jq .tagName)
FROM=${1:+v$1}
FROM=${FROM:-$(gh release list --repo "$REPO" --exclude-drafts --exclude-pre-releases --limit 2 --json tagName --jq '.[1].tagName')}
[ "$FROM" != "$LATEST" ] || { echo "✗ $FROM is the latest release; nothing to update to"; exit 1; }
echo "• $FROM -> $LATEST"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
gh release download "$FROM" --repo "$REPO" --pattern find-my-wife-phone.apk --dir "$TMP"

adb uninstall "$PKG" >/dev/null 2>&1 || true
adb install "$TMP/find-my-wife-phone.apk" >/dev/null
version() { adb shell dumpsys package "$PKG" | sed -n 's/.*versionName=\([^ ]*\).*/\1/p' | head -1 | tr -d '\r'; }
echo "• installed $(version)"
# On a phone the app is its own installer of record after its first self-update and the owner has
# allowed "install unknown apps" for it; only then may Android update it without asking. Recreate
# that state instead of the adb-installed one, so a time-out below means a real regression.
adb shell pm set-installer "$PKG" "$PKG" >/dev/null || { echo "✗ could not make $PKG its own installer"; exit 1; }
adb shell appops set "$PKG" REQUEST_INSTALL_PACKAGES allow
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true

# The app schedules its update worker on start; the worker skips while the app is on screen.
adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 8
adb shell input keyevent KEYCODE_HOME

JOBS=$(adb shell dumpsys jobscheduler | tr -d '\r' | sed -n "s/.*JOB #u[0-9]*a*[0-9]*\/\([0-9]*\): [0-9a-f]* $PKG\/androidx.work.*/\1/p" | sort -u)
[ -n "$JOBS" ] || { echo "✗ no WorkManager job for $PKG — did the app start?"; exit 1; }
for j in $JOBS; do adb shell cmd jobscheduler run -f "$PKG" "$j" >/dev/null; done

deadline=$((SECONDS + WAIT_S))
while [ $SECONDS -lt $deadline ]; do
  v=$(version)
  if [ "v$v" = "$LATEST" ]; then echo "✓ updated itself to $v"; exit 0; fi
  # Updater.onNeedsConfirm: Android wanted the owner to confirm, i.e. the silent update did not happen.
  if adb shell dumpsys notification --noredact | tr -d '\r' | grep -q "준비됐어요"; then
    echo "✗ the update was downloaded but Android asked for confirmation instead of installing silently"
    exit 1
  fi
  sleep 5
done
echo "✗ still $(version) after ${WAIT_S}s; updater log:"
adb logcat -d -s Updater:V UpdateWorker:V WM-WorkerWrapper:V | tail -30
exit 1
