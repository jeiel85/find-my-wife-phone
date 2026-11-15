"""Builds every icon size from the Codex-generated motif in design/icon/icon-foreground.png.

    python design/make_icons.py

- Android adaptive icon foreground (108dp layer) per density, motif re-centred and scaled so the
  outermost wave stays inside the 66dp safe circle; monochrome layer for themed icons.
- Full icon = that motif on the launcher's coral gradient (res/drawable/ic_launcher_bg.xml), so the
  web icons and the Android adaptive layers can never drift apart.
- Web: favicon/touch icons and the 1200x630 og:image for the landing page.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FG = Image.open(ROOT / "design/icon/icon-foreground.png").convert("RGBA")
CORAL_START, CORAL_END = (0xFF, 0x6E, 0x5C), (0xE2, 0x4B, 0x3B)  # ic_launcher_bg.xml, angle 315
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


def monochrome(img: Image.Image) -> Image.Image:
    """White silhouette for themed icons; the dark navy screen is cut out so the eyes stay visible."""
    lum = img.convert("RGB").convert("L").point(lambda v: 255 if v > 52 else 0)
    alpha = Image.composite(img.getchannel("A"), Image.new("L", img.size, 0), lum)
    white = Image.new("RGBA", img.size, (255, 255, 255, 0))
    white.putalpha(alpha)
    return white


def main() -> None:
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
