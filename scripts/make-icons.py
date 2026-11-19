"""Generate all Android icon assets from the logo in public/logo.svg.

Style: Streambert red with a subtle top→bottom gradient, white logo at ~60 %
of the visible icon, soft drop shadow.

    python scripts/make-icons.py

Writes: launcher icons (legacy + adaptive + themed/monochrome), splash images,
Android TV banner, notification icons, and public/logo-app.png (in-app logo).
Requires Pillow.
"""
import base64
import math
import os
import re

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")
TOP, BOTTOM = (255, 46, 56), (186, 6, 16)  # gradient
SHADOW = (60, 0, 4)

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def load_logo():
    svg = open(os.path.join(ROOT, "public", "logo.svg"), encoding="utf8").read()
    m = re.search(r"base64,([A-Za-z0-9+/=\s]+)\"", svg)
    if not m:
        raise SystemExit("logo.svg has no embedded PNG")
    path = os.path.join(ROOT, "scripts", ".logo-src.png")
    with open(path, "wb") as f:
        f.write(base64.b64decode(re.sub(r"\s", "", m.group(1))))
    img = Image.open(path).convert("RGBA")
    os.remove(path)
    return img


SRC = None


def logo_mask(target_w):
    """Line-art alpha upscaled and re-sharpened into clean edges."""
    a = SRC.split()[3]
    scale = max(1, math.ceil(target_w * 2 / a.width))
    big = a.resize((a.width * scale, a.height * scale), Image.LANCZOS)
    big = big.filter(ImageFilter.GaussianBlur(scale * 0.35))
    big = big.point(lambda v: 0 if v < 90 else (255 if v > 165 else int((v - 90) * 255 / 75)))
    h = max(1, int(target_w * a.height / a.width))
    return big.resize((target_w, h), Image.LANCZOS)


def gradient(w, h, y0=0, y1=None):
    """Vertical gradient; colors run from TOP at y0 to BOTTOM at y1 (clamped)."""
    y1 = h if y1 is None else y1
    g = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(g)
    for y in range(h):
        t = min(1, max(0, (y - y0) / max(1, (y1 - y0))))
        d.line([(0, y), (w, y)], fill=tuple(int(TOP[i] + (BOTTOM[i] - TOP[i]) * t) for i in range(3)))
    return g


def draw_logo(canvas, cx, cy, width, shadow=True, color=(255, 255, 255, 255)):
    mask = logo_mask(int(width))
    x, y = int(cx - mask.width / 2), int(cy - mask.height / 2)
    if shadow:
        size = canvas.width
        sh = Image.new("L", canvas.size, 0)
        sh.paste(mask, (x, y + int(width * 0.03)))
        sh = sh.filter(ImageFilter.GaussianBlur(width * 0.058)).point(lambda v: v * 110 // 255)
        canvas.alpha_composite(Image.composite(Image.new("RGBA", canvas.size, SHADOW + (255,)),
                                               Image.new("RGBA", canvas.size, (0, 0, 0, 0)), sh))
    canvas.paste(Image.new("RGBA", mask.size, color), (x, y), mask)


def full_icon(size):
    img = gradient(size, size).convert("RGBA")
    draw_logo(img, size / 2, size / 2 + size * 0.01, size * 0.60)
    return img


def shape(img, kind):
    s = img.width
    m = Image.new("L", (s * 4, s * 4), 0)
    d = ImageDraw.Draw(m)
    if kind == "circle":
        d.ellipse((0, 0, s * 4 - 1, s * 4 - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, s * 4 - 1, s * 4 - 1), radius=s * 4 * 0.2, fill=255)
    out = Image.new("RGBA", img.size, (0, 0, 0, 0))
    out.paste(img, (0, 0), m.resize((s, s), Image.LANCZOS))
    return out


def save(img, *parts):
    path = os.path.join(RES, *parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)


def main():
    global SRC
    SRC = load_logo()

    for name, k in DENSITIES.items():
        # legacy launcher icons (pre-Android 8): pre-shaped
        legacy = int(48 * k)
        big = full_icon(legacy * 4)
        save(shape(big, "square").resize((legacy, legacy), Image.LANCZOS), f"mipmap-{name}", "ic_launcher.png")
        save(shape(big, "circle").resize((legacy, legacy), Image.LANCZOS), f"mipmap-{name}", "ic_launcher_round.png")

        # adaptive layers: 108dp canvas, ~72dp visible, keep logo in the 66dp safe zone
        a = int(108 * k)
        hi = a * 4
        visible = hi * 72 / 108
        top = (hi - visible) / 2
        bg = gradient(hi, hi, int(top), int(top + visible))
        save(bg.resize((a, a), Image.LANCZOS), f"mipmap-{name}", "ic_launcher_background.png")
        fg = Image.new("RGBA", (hi, hi), (0, 0, 0, 0))
        draw_logo(fg, hi / 2, hi / 2 + visible * 0.01, visible * 0.60)
        save(fg.resize((a, a), Image.LANCZOS), f"mipmap-{name}", "ic_launcher_foreground.png")
        mono = Image.new("RGBA", (hi, hi), (0, 0, 0, 0))
        draw_logo(mono, hi / 2, hi / 2, visible * 0.60, shadow=False)
        save(mono.resize((a, a), Image.LANCZOS), f"mipmap-{name}", "ic_launcher_monochrome.png")

        # notification small icon: white silhouette, 24dp with ~2dp padding
        n = int(24 * k)
        nh = n * 4
        st = Image.new("RGBA", (nh, nh), (0, 0, 0, 0))
        draw_logo(st, nh / 2, nh / 2, nh * 0.92, shadow=False)
        save(st.resize((n, n), Image.LANCZOS), f"drawable-{name}", "ic_stat_streambert.png")

    xml = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
</adaptive-icon>
"""
    for f in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(RES, "mipmap-anydpi-v26", f), "w", encoding="utf8") as fh:
            fh.write(xml)

    # splash images (pre-Android 12 + Capacitor's fallback): red gradient, centered logo
    for d in os.listdir(RES):
        p = os.path.join(RES, d, "splash.png")
        if os.path.exists(p):
            w, h = Image.open(p).size
            img = gradient(w, h).convert("RGBA")
            draw_logo(img, w / 2, h / 2, min(w, h) * 0.38)
            img.convert("RGB").save(p, optimize=True)
    # Android 12+ splash icon: 288dp canvas, icon within the 192dp circle
    for name, k in DENSITIES.items():
        s = int(288 * k)
        hi = s * 2
        ic = Image.new("RGBA", (hi, hi), (0, 0, 0, 0))
        draw_logo(ic, hi / 2, hi / 2, hi * (192 / 288) * 0.62)
        save(ic.resize((s, s), Image.LANCZOS), f"drawable-{name}", "splash_icon.png")

    # Android TV banner (320x180dp → xhdpi 640x360)
    W, H = 640, 360
    tv = gradient(W, H).convert("RGBA")
    draw_logo(tv, 170, H / 2, 210)
    d = ImageDraw.Draw(tv)
    font = None
    for f in (os.path.join(ROOT, "src", "styles", "fonts", "bebas-neue-regular.woff2"),
              "C:/Windows/Fonts/impact.ttf", "C:/Windows/Fonts/arialbd.ttf"):
        try:
            font = ImageFont.truetype(f, 92)
            break
        except Exception:
            continue
    font = font or ImageFont.load_default()
    tw = d.textlength("STREAMBERT", font=font)
    tx = 300
    if tx + tw > W - 24:
        font = ImageFont.truetype(font.path, int(92 * (W - 24 - tx) / tw)) if hasattr(font, "path") else font
    d.text((tx + 3, H / 2 + 4), "STREAMBERT", font=font, fill=SHADOW + (140,), anchor="lm")
    d.text((tx, H / 2), "STREAMBERT", font=font, fill=(255, 255, 255, 255), anchor="lm")
    save(tv.convert("RGB"), "drawable-xhdpi", "tv_banner.png")
    old = os.path.join(RES, "drawable", "tv_banner.png")
    if os.path.exists(old):
        os.remove(old)

    # in-app logo (setup screen etc.)
    app = shape(full_icon(1024), "square").resize((256, 256), Image.LANCZOS)
    app.save(os.path.join(ROOT, "public", "logo-app.png"), optimize=True)
    print("icons generated")


if __name__ == "__main__":
    main()
