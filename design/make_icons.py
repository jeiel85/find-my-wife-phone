"""Builds every icon size from the Codex-generated motif in design/icon/icon-foreground.png.

    python design/make_icons.py                  # rebuild from the motif in the repo
    python design/make_icons.py --from-codex     # first copy Codex's newest image in as the motif
    python design/make_icons.py --from-codex <generated.png>

Codex cannot save its image_generation output to a path we choose; it lands under
~/.codex/generated_images/<session>/<name>.png. --from-codex takes the newest PNG there (or the
file given) and checks it is a cut-out motif (transparent corners) before replacing the repo copy.

- Android adaptive icon foreground (108dp layer) per density, motif re-centred and scaled so the
  outermost wave stays inside the 66dp safe circle; monochrome layer for themed icons.
- Full icon = that motif on the launcher's coral gradient (res/drawable/ic_launcher_bg.xml), so the
  web icons and the Android adaptive layers can never drift apart.
- Web: favicon/touch icons and the 1200x630 og:image for the landing page.
"""
import shutil
import sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
MOTIF = ROOT / "design/icon/icon-foreground.png"
CODEX_IMAGES = Path.home() / ".codex/generated_images"
CORAL_START, CORAL_END = (0xFF, 0x6E, 0x5C), (0xE2, 0x4B, 0x3B)  # ic_launcher_bg.xml, angle 315
FG: Image.Image  # the motif, loaded in main() after an optional --from-codex copy
SCREEN_MAX: int  # luminance at or below which a pixel belongs to the dark screen (see screen_threshold)
RES = ROOT / "android/app/src/main/res"
WEB = ROOT / "docs/assets"

DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def centred_motif(size: int, fraction: float) -> Image.Image:
    """Crops the motif to its alpha bounds and centres it so its longest side is `fraction` of `size`."""
    motif = FG.crop(FG.getchannel("A").getbbox())
    scale = size * fraction / max(motif.size)
    motif = motif.resize((round(motif.width * scale), round(motif.height * scale)), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(motif, ((size - motif.width) // 2, (size - motif.height) // 2))
    return canvas


def full_icon(size: int = 1024) -> Image.Image:
    """Coral diagonal gradient (top-left → bottom-right) with the motif at the adaptive-icon scale."""
    bg = Image.new("RGBA", (size, size))
    px = bg.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(round(a + (b - a) * t) for a, b in zip(CORAL_START, CORAL_END)) + (255,)
    bg.alpha_composite(centred_motif(size, 0.74))
    return bg


def screen_threshold(img: Image.Image) -> int:
    """Luminance cut that separates the motif's darkest colour (the phone screen) from the rest.

    Takes the darkest histogram peak among opaque pixels and returns the middle of the empty gap
    right above it, so a new motif with a slightly different navy still cuts cleanly. Fails loudly
    when there is no dark cluster or no gap, instead of silently producing a blank themed icon.
    """
    hist = [0] * 256
    alpha = img.getchannel("A").tobytes()
    for lum, a in zip(img.convert("RGB").convert("L").tobytes(), alpha):
        if a >= 128:
            hist[lum] += 1
    opaque = sum(hist)
    peak = max(range(128), key=lambda i: hist[i])
    if hist[peak] < opaque * 0.01:
        sys.exit(f"monochrome: no dark screen in the motif (darkest peak {peak} has {hist[peak]} of {opaque} px)")
    floor = max(1, hist[peak] * 0.01)
    i = peak
    while i < 255 and hist[i] >= floor:
        i += 1
    gap_start = i
    while i < 255 and hist[i] < floor:
        i += 1
    if i - gap_start < 4:
        sys.exit(f"monochrome: the screen colour (~{peak}) is not separated from the rest; pass --mono-threshold N")
    return (gap_start + i) // 2


def monochrome(img: Image.Image) -> Image.Image:
    """White silhouette for themed icons; the dark navy screen is cut out so the eyes stay visible.

    SCREEN_MAX comes from the full-resolution motif (screen_threshold), so every density uses one
    cut instead of re-deriving it from a downscaled copy with blurrier edges.
    """
    lum = img.convert("RGB").convert("L").point(lambda v: 255 if v > SCREEN_MAX else 0)
    alpha = Image.composite(img.getchannel("A"), Image.new("L", img.size, 0), lum)
    white = Image.new("RGBA", img.size, (255, 255, 255, 0))
    white.putalpha(alpha)
    return white


def import_codex_motif(src: Path | None) -> None:
    """Copies a Codex-generated motif over design/icon/icon-foreground.png after checking it is cut out."""
    if src is None:
        pngs = sorted(CODEX_IMAGES.glob("*/*.png"), key=lambda p: p.stat().st_mtime)
        if not pngs:
            sys.exit(f"--from-codex: no PNG under {CODEX_IMAGES}")
        src = pngs[-1]
    img = Image.open(src).convert("RGBA")
    w, h = img.size
    corners = [img.getpixel(p)[3] for p in ((0, 0), (w - 1, 0), (0, h - 1), (w - 1, h - 1))]
    if max(corners) > 16:
        sys.exit(f"--from-codex: {src} has an opaque background (corner alpha {corners}); ask Codex for a transparent cut-out")
    if src.resolve() != MOTIF.resolve():
        shutil.copyfile(src, MOTIF)
    print(f"motif <- {src}")


def main() -> None:
    global FG, SCREEN_MAX
    args = sys.argv[1:]
    mono = None
    if "--mono-threshold" in args:
        i = args.index("--mono-threshold")
        mono = int(args[i + 1])
        del args[i:i + 2]
    if args and args[0] == "--from-codex":
        import_codex_motif(Path(args[1]) if len(args) > 1 else None)
    elif args:
        sys.exit(__doc__)
    FG = Image.open(MOTIF).convert("RGBA")
    SCREEN_MAX = mono if mono is not None else screen_threshold(FG)
    print(f"monochrome: screen cut at luminance {SCREEN_MAX}")
    for density, px in DENSITIES.items():
        d = RES / f"mipmap-{density}"
        d.mkdir(parents=True, exist_ok=True)
        fg = centred_motif(px, 0.56)  # 0.56 * 108dp ≈ 60dp, inside the 66dp safe zone
        fg.save(d / "ic_launcher_foreground.png", optimize=True)
        monochrome(fg).save(d / "ic_launcher_monochrome.png", optimize=True)

    FULL = full_icon()
    FULL.save(ROOT / "design/icon/icon-full.png", optimize=True)
    WEB.mkdir(parents=True, exist_ok=True)
    for px in (192, 512):
        FULL.resize((px, px), Image.LANCZOS).save(WEB / f"icon-{px}.png", optimize=True)
    FULL.resize((180, 180), Image.LANCZOS).save(WEB / "apple-touch-icon.png", optimize=True)

    # og:image — periwinkle wall like the landing page, icon on the left, one line of copy.
    og = Image.new("RGBA", (1200, 630), (232, 236, 255, 255))
    icon = FULL.resize((360, 360), Image.LANCZOS)
    mask = Image.new("L", icon.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 359, 359), radius=84, fill=255)
    og.paste(icon, (110, 135), mask)
    draw = ImageDraw.Draw(og)
    font_path = next((p for p in [Path("C:/Windows/Fonts/malgunbd.ttf"), Path("C:/Windows/Fonts/malgun.ttf")] if p.exists()), None)
    big = ImageFont.truetype(str(font_path), 64) if font_path else ImageFont.load_default()
    small = ImageFont.truetype(str(font_path), 34) if font_path else ImageFont.load_default()
    draw.text((540, 210), "포너디", font=big, fill=(31, 34, 53))
    draw.text((540, 305), "폰 어디? 소파 밑 폰도 버튼 한 번에.", font=small, fill=(51, 64, 168))
    draw.text((540, 360), "무음이어도 울리고, 찾으면 원래대로.", font=small, fill=(74, 79, 105))
    og.convert("RGB").save(WEB / "og.png", optimize=True)
    print("icons written")


if __name__ == "__main__":
    main()
