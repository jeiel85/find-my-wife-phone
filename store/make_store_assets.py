"""Builds the Google Play store graphics for 포너디.

    python store/make_store_assets.py --capture <raw-screenshot-dir>   # capture, then build
    python store/make_store_assets.py <raw-screenshot-dir>             # build from earlier captures

<raw-screenshot-dir> holds 1080x2400 PNG captures named after DemoActivity states
(idle, nearby, alert-countdown, found, located, history). --capture takes them itself from the
one adb device (set ANDROID_SERIAL when there are several), which must run a playDebug build —
use the fmwp-phone-api36 emulator, and tell other sessions that share it first. Per state it runs

    adb shell am start -S -n com.jeiel85.ponerdi/io.github.jeiel85.findmywifephone.DemoActivity --es state <state>

waits until 포너디's screen is in front (another app popping up fails the run instead of being
captured), lets it settle for SETTLE_S seconds, and saves `adb exec-out screencap -p`.

Writes store/ko-KR/feature-graphic.png (1024x500) and
store/ko-KR/phone-screenshots/NN-<state>.png (1080x1920, 9:16 — Play rejects
the raw 9:20 captures because the long side may be at most twice the short side).
"""
import io
import re
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "store" / "ko-KR"
ICON = ROOT / "design" / "icon" / "icon-full.png"

BG_TOP = (236, 239, 255)
BG_BOTTOM = (250, 247, 252)
INK = (31, 34, 53)
ACCENT = (51, 64, 168)
SUB = (88, 92, 120)

SHOTS = [
    ("idle", "버튼 한 번이면 울려요", "무음·진동이어도 알람 소리로 크게"),
    ("nearby", "가까워지면 알려 줘요", "울리는 동안 블루투스로 거리를 어림해요"),
    ("alert-countdown", "10초 안에 거절할 수 있어요", "받는 사람이 먼저 알고 고를 수 있어요"),
    ("found", "찾으면 원래대로", "볼륨과 방해 금지 설정을 되돌려 놓아요"),
    ("located", "위치는 한 번만, 지도로", "받는 사람이 정한 대로만 보내요"),
    ("history", "지울 수 없는 기록", "누가 언제 찾았는지 모두 남아요"),
]


def font(size: int, weight: int) -> ImageFont.FreeTypeFont:
    for path in (r"C:\Windows\Fonts\NotoSansKR-VF.ttf",
                 str(Path.home() / r"AppData\Local\Microsoft\Windows\Fonts\NotoSansKR-VF.ttf")):
        if Path(path).exists():
            f = ImageFont.truetype(path, size)
            f.set_variation_by_axes([weight])
            return f
    return ImageFont.truetype(r"C:\Windows\Fonts\malgunbd.ttf" if weight >= 600 else r"C:\Windows\Fonts\malgun.ttf", size)


def gradient(w: int, h: int) -> Image.Image:
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(round(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM)))
    return img


def rounded(im: Image.Image, r: int) -> Image.Image:
    mask = Image.new("L", im.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, im.width - 1, im.height - 1], r, fill=255)
    out = im.convert("RGBA")
    out.putalpha(mask)
    return out


def centered(d: ImageDraw.ImageDraw, y: int, text: str, f, fill, w: int) -> None:
    x0, _, x1, _ = d.textbbox((0, 0), text, font=f)
    d.text(((w - (x1 - x0)) / 2 - x0, y), text, font=f, fill=fill)


def phone_shot(raw: Path, title: str, sub: str, dest: Path) -> None:
    W, H = 1080, 1920
    canvas = gradient(W, H).convert("RGBA")
    d = ImageDraw.Draw(canvas)
    centered(d, 92, title, font(70, 800), INK, W)
    centered(d, 196, sub, font(38, 500), SUB, W)

    shot = Image.open(raw).convert("RGB")
    sh = H - 330 + 40          # bleeds off the bottom edge on purpose
    sw = round(shot.width * sh / shot.height)
    shot = rounded(shot.resize((sw, sh), Image.LANCZOS), 44)
    x, y = (W - sw) // 2, 330
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([x, y + 12, x + sw, y + sh + 12], 44, fill=(40, 46, 110, 70))
    canvas = Image.alpha_composite(canvas, shadow.filter(ImageFilter.GaussianBlur(26)))
    canvas.alpha_composite(shot, (x, y))
    canvas.convert("RGB").save(dest, optimize=True)


def feature_graphic(dest: Path) -> None:
    W, H = 1024, 500
    canvas = gradient(W, H).convert("RGBA")
    icon = Image.open(ICON).convert("RGBA").resize((300, 300), Image.LANCZOS)
    icon = rounded(icon, 68)
    shadow = Image.new("RGBA", canvas.size, (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle([90, 112, 390, 412], 68, fill=(200, 70, 60, 80))
    canvas = Image.alpha_composite(canvas, shadow.filter(ImageFilter.GaussianBlur(22)))
    canvas.alpha_composite(icon, (90, 100))
    d = ImageDraw.Draw(canvas)
    d.text((450, 128), "포너디", font=font(96, 800), fill=INK)
    d.text((452, 266), "폰 어디? 소파 밑 폰도 버튼 한 번에.", font=font(34, 700), fill=ACCENT)
    d.text((452, 318), "무음이어도 울리고, 찾으면 원래대로.", font=font(34, 500), fill=SUB)
    canvas.convert("RGB").save(dest, optimize=True)


PACKAGE = "com.jeiel85.ponerdi"
DEMO = f"{PACKAGE}/io.github.jeiel85.findmywifephone.DemoActivity"
SETTLE_S = 4          # after the screen is in front: map tiles, entry animations
FRONT_TIMEOUT_S = 60  # cold start on a loaded emulator can take half a minute


def adb(*args: str) -> bytes:
    return subprocess.run(["adb", *args], check=True, capture_output=True).stdout


def front_activity() -> str:
    out = adb("shell", "dumpsys", "activity", "activities").decode(errors="replace")
    m = re.search(r"topResumedActivity=ActivityRecord\{\S+ \S+ (\S+)", out)
    return m.group(1) if m else ""


def capture(raw_dir: Path) -> None:
    raw_dir.mkdir(parents=True, exist_ok=True)
    for state, _, _ in SHOTS:
        adb("shell", "am", "start", "-S", "-n", DEMO, "--es", "state", state)
        deadline = time.monotonic() + FRONT_TIMEOUT_S
        while True:
            top = front_activity()
            # DemoActivity finishes itself and opens MainActivity or AlertActivity.
            if top.startswith(PACKAGE + "/") and "DemoActivity" not in top:
                break
            if time.monotonic() > deadline:
                sys.exit(f"capture {state}: 포너디 never came to the front (top: {top or 'none'})")
            time.sleep(1)
        time.sleep(SETTLE_S)
        if not front_activity().startswith(PACKAGE + "/"):
            sys.exit(f"capture {state}: another app took the screen ({front_activity()})")
        png = adb("exec-out", "screencap", "-p")
        size = Image.open(io.BytesIO(png)).size
        if size != (1080, 2400):
            sys.exit(f"capture {state}: screen is {size}, expected 1080x2400 (fmwp-phone-api36)")
        (raw_dir / f"{state}.png").write_bytes(png)
        print("captured", state)


def main() -> None:
    args = sys.argv[1:]
    if args[:1] == ["--capture"] and len(args) == 2:
        capture(Path(args[1]))
        args = args[1:]
    raw_dir = Path(args[0]) if len(args) == 1 else sys.exit(__doc__)
    shots_dir = OUT / "phone-screenshots"
    shots_dir.mkdir(parents=True, exist_ok=True)
    for i, (state, title, sub) in enumerate(SHOTS, 1):
        phone_shot(raw_dir / f"{state}.png", title, sub, shots_dir / f"{i:02d}-{state}.png")
    feature_graphic(OUT / "feature-graphic.png")
    print("done:", OUT)


if __name__ == "__main__":
    main()
