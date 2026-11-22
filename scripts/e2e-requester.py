"""End-to-end test of the REQUESTER side of '울리기 → 그만 울리기' (hardening #13).

The emulator app is the requester; the other phone is relay/tools/fake-peer.mjs, which cannot
receive FCM, so the relay runs locally with a test outbox for it (`wrangler dev --var TEST_OUTBOX:1`).
The app still gets real FCM pushes, so the local relay needs the Firebase service account.

    # 1. emulator OFF (this PC cannot build with it running), build a debug app aimed at the local relay:
    cd android && ./gradlew assemblePlayDebug -Papp.relayUrl=http://10.0.2.2:8787
    # 2. boot fmwp-phone-api36 (tell other sessions that share it), then:
    python scripts/e2e-requester.py android/app/build/outputs/apk/play/debug/app-play-debug.apk

What it does: applies the relay migrations to a local D1, starts the relay, installs the APK and
CLEARS ITS DATA (so it registers with the local relay; emulator only), pairs through a fmwp://pair
link, taps 울리기, lets the fake phone answer countdown → ringing, taps 그만 울리기, and passes when
the fake phone got ring-cancel and the app says 울림을 멈췄어요.

FCM_SERVICE_ACCOUNT_FILE overrides the service-account path
(default %LOCALAPPDATA%/find-my-wife-phone/service-account.json); it is written to relay/.dev.vars
(git-ignored) for the run and removed afterwards if this script created it.
"""
import json
import os
import queue
import re
import shutil
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RELAY_DIR = ROOT / "relay"
PKG = "com.jeiel85.ponerdi"
PORT = 8787
LOCAL = f"http://127.0.0.1:{PORT}"


def adb(*args: str, check: bool = True) -> str:
    return subprocess.run(["adb", *args], check=check, capture_output=True, text=True, encoding="utf-8", errors="replace").stdout


def fail(msg: str) -> None:
    raise SystemExit(f"FAIL: {msg}")


def kill_tree(p: subprocess.Popen) -> None:
    if p.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(p.pid)], capture_output=True)
    else:
        p.terminate()


def ui_nodes() -> list[tuple[str, tuple[int, int]]]:
    """(text or content-desc, centre) of every node on screen."""
    adb("shell", "uiautomator", "dump", "/sdcard/e2e-ui.xml")
    xml = adb("exec-out", "cat", "/sdcard/e2e-ui.xml")
    out = []
    for m in re.finditer(r'<node [^>]*?text="([^"]*)"[^>]*?content-desc="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', xml):
        label = m.group(1) or m.group(2)
        if label:
            x1, y1, x2, y2 = map(int, m.groups()[2:])
            out.append((label, ((x1 + x2) // 2, (y1 + y2) // 2)))
    return out


def wait_ui(text: str, timeout: float = 30) -> tuple[int, int]:
    deadline = time.monotonic() + timeout
    seen: list[str] = []
    while time.monotonic() < deadline:
        nodes = ui_nodes()
        for label, centre in nodes:
            if label == text or (len(text) > 4 and text in label):
                return centre
        seen = [n[0] for n in nodes]
        time.sleep(1)
    fail(f"'{text}' never appeared on screen; last screen: {seen}")


def wait_event(events: "queue.Queue[dict]", pred, what: str, timeout: float = 45) -> dict:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            e = events.get(timeout=1)
        except queue.Empty:
            continue
        print("  fake phone:", e)
        if pred(e):
            return e
    fail(f"fake phone never saw {what}")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")  # Korean UI text on a cp949 console
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    apk = Path(sys.argv[1])
    if not apk.exists():
        fail(f"{apk} not found — build it first (see the usage above)")
    devices = [l for l in adb("devices").splitlines()[1:] if l.strip().endswith("device")]
    if len(devices) != 1:
        fail("need exactly one adb device (set ANDROID_SERIAL)")
    if adb("shell", "getprop", "ro.kernel.qemu").strip() != "1":
        fail("not an emulator; this clears 포너디's data")

    npx = shutil.which("npx") or fail("npx not found")
    node = shutil.which("node") or fail("node not found")
    dev_vars = RELAY_DIR / ".dev.vars"
    created_vars = False
    procs: list[subprocess.Popen] = []
    peer_env = {**os.environ, "RELAY_URL": LOCAL}
    try:
        if not dev_vars.exists():
            sa = Path(os.environ.get("FCM_SERVICE_ACCOUNT_FILE",
                                     Path(os.environ.get("LOCALAPPDATA", "")) / "find-my-wife-phone/service-account.json"))
            if not sa.exists():
                fail(f"service account not found at {sa} (set FCM_SERVICE_ACCOUNT_FILE)")
            one_line = json.dumps(json.loads(sa.read_text(encoding="utf-8")))
            dev_vars.write_text(f"FCM_SERVICE_ACCOUNT='{one_line}'\n", encoding="utf-8")
            created_vars = True

        print("• local relay")
        subprocess.run([npx, "wrangler", "d1", "migrations", "apply", "find-my-wife-phone-relay", "--local"],
                       cwd=RELAY_DIR, check=True, capture_output=True, input=b"y\n")
        relay = subprocess.Popen([npx, "wrangler", "dev", "--port", str(PORT), "--var", "TEST_OUTBOX:1"],
                                 cwd=RELAY_DIR, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        procs.append(relay)
        deadline = time.monotonic() + 90
        while True:
            try:
                with urllib.request.urlopen(f"{LOCAL}/v1/health", timeout=2) as r:
                    if r.status == 200:
                        break
            except OSError:
                pass
            if time.monotonic() > deadline or relay.poll() is not None:
                fail("local relay did not start (try: cd relay && npx wrangler dev --var TEST_OUTBOX:1)")
            time.sleep(1)

        print("• app (fresh data, local relay)")
        adb("install", "-r", "-d", str(apk))
        adb("shell", "pm", "clear", PKG)
        adb("shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", check=False)
        # Skips onboarding and sets a name; 'empty' leaves no demo peer behind.
        adb("shell", "am", "start", "-S", "-n", f"{PKG}/io.github.jeiel85.findmywifephone.DemoActivity", "--es", "state", "empty")
        time.sleep(8)

        print("• pair through a fmwp://pair link")
        subprocess.run([node, "tools/fake-peer.mjs", "cleanup"], cwd=RELAY_DIR, env=peer_env, capture_output=True)
        link = subprocess.run([node, "tools/fake-peer.mjs", "invite", "시험폰"], cwd=RELAY_DIR, env=peer_env,
                              check=True, capture_output=True, text=True).stdout.strip()
        serve = subprocess.Popen([node, "tools/fake-peer.mjs", "serve", "300", "--ring", "countdown,ringing"],
                                 cwd=RELAY_DIR, env=peer_env, stdout=subprocess.PIPE, text=True, encoding="utf-8")
        procs.append(serve)
        events: "queue.Queue[dict]" = queue.Queue()
        threading.Thread(target=lambda: [events.put(json.loads(l)) for l in serve.stdout if l.startswith("{")], daemon=True).start()
        adb("shell", f"am start -a android.intent.action.VIEW -d '{link}' {PKG}")
        wait_event(events, lambda e: e.get("event") == "paired", "the pairing push", timeout=60)

        print("• 울리기")
        # The link left MainActivity (singleTask) on its pairing screen; ask for home explicitly.
        adb("shell", "am", "start", "-n", f"{PKG}/io.github.jeiel85.findmywifephone.ui.MainActivity", "--es", "screen", "home")
        time.sleep(3)
        x, y = wait_ui("울리기")
        adb("shell", "input", "tap", str(x), str(y))
        wait_event(events, lambda e: e.get("event") == "received" and e.get("type") == "ring", "the ring request")
        wait_event(events, lambda e: e.get("event") == "sent" and e.get("state") == "ringing", "its own ringing reply")

        print("• 그만 울리기")
        x, y = wait_ui("그만 울리기")
        adb("shell", "input", "tap", str(x), str(y))
        wait_event(events, lambda e: e.get("event") == "received" and e.get("type") == "ring-cancel", "ring-cancel")
        wait_ui("울림을 멈췄어요")
        print("PASS: requester saw countdown → ringing, stopped it, and the fake phone got ring-cancel")
    finally:
        for p in reversed(procs):  # stop the fake phone's polling before its registration goes
            if p is not procs[0]:
                kill_tree(p)
        subprocess.run([node, "tools/fake-peer.mjs", "cleanup"], cwd=RELAY_DIR, env=peer_env, capture_output=True)
        if procs:
            kill_tree(procs[0])  # the relay, last: cleanup still talks to it
        if created_vars:
            dev_vars.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
