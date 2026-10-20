#!/usr/bin/env python3
"""Regenerates every Fetch brand bitmap (launcher icons, TV banner, wordmarks).

Run from the repository root:  python3 scripts/brand/generate_brand.py
Requires Pillow. Uses the DM Sans variable font already bundled with the app.
"""
from __future__ import annotations

import os
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
FONT = os.path.join(RES, "font", "dm_sans_variable.ttf")
GLYPH = os.path.join(os.path.dirname(os.path.abspath(__file__)), "mark_glyph.png")

# Primary brand gradient: warm amber to coral. Theme variants reuse the same shapes.
PALETTES = {
    "": ((255, 170, 64), (255, 77, 109)),
    "_copper": ((222, 140, 86), (176, 82, 52)),
    "_emerald": ((80, 220, 160), (16, 140, 110)),
    "_rose_gold": ((246, 190, 170), (214, 120, 130)),
    "_arctic_blue": ((140, 220, 255), (60, 130, 240)),
    "_graphite": ((170, 176, 186), (84, 90, 102)),
}
WORDMARK_COLORS = {
    "": (255, 255, 255),
    "_gold": (244, 196, 92),
    "_jade": (96, 210, 160),
    "_rose_gold": (240, 178, 162),
    "_arctic_blue": (150, 210, 255),
    "_graphite": (190, 196, 206),
}
BACKGROUND_TOP = (14, 14, 20)
BACKGROUND_BOTTOM = (34, 22, 26)


def gradient(size: int, start, end) -> Image.Image:
    """Diagonal linear gradient from top-left to bottom-right."""
    img = Image.new("RGB", (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(int(start[i] + (end[i] - start[i]) * t) for i in range(3))
    return img


def mark(size: int, palette, rounded: bool = True) -> Image.Image:
    """The Fetch mark: a dog in profile whose ear is a play triangle, on a gradient tile.

    The glyph (scripts/brand/mark_glyph.png, an alpha mask) was generated with Higgsfield and is
    tinted white here so every launcher palette reuses the same shape.
    """
    scale = 4  # supersample for smooth edges
    s = size * scale
    tile = gradient(s, *palette).convert("RGBA")
    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, s - 1, s - 1), radius=int(s * (0.23 if rounded else 0.0)), fill=255
    )
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(tile, (0, 0), mask)

    glyph = Image.open(GLYPH).convert("L")
    box = s * 0.64  # glyph fits inside this square, centred on the tile
    k = box / max(glyph.size)
    glyph = glyph.resize((round(glyph.width * k), round(glyph.height * k)), Image.LANCZOS)
    white = Image.new("RGBA", glyph.size, (255, 255, 255, 255))
    # Optical centring: the head's mass sits right of the ear, so nudge a touch left and up.
    x = (s - glyph.width) // 2 - int(s * 0.01)
    y = (s - glyph.height) // 2 - int(s * 0.01)
    out.paste(white, (x, y), glyph)
    return out.resize((size, size), Image.LANCZOS)


def font(px: int) -> ImageFont.FreeTypeFont:
    f = ImageFont.truetype(FONT, px)
    try:
        f.set_variation_by_axes([32, 760])  # (optical size, weight)
    except Exception:
        pass
    return f


def wordmark(width: int, height: int, color) -> Image.Image:
    img = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    f = font(int(height * 0.86))
    text = "fetch"
    box = draw.textbbox((0, 0), text, font=f)
    tw, th = box[2] - box[0], box[3] - box[1]
    draw.text(((width - tw) / 2 - box[0], (height - th) / 2 - box[1]), text, font=f, fill=color + (255,))
    return img


def banner(width: int, height: int, palette=PALETTES[""]) -> Image.Image:
    img = Image.new("RGB", (width, height))
    px = img.load()
    for y in range(height):
        t = y / (height - 1)
        row = tuple(int(BACKGROUND_TOP[i] + (BACKGROUND_BOTTOM[i] - BACKGROUND_TOP[i]) * t) for i in range(3))
        for x in range(width):
            px[x, y] = row
    img = img.convert("RGBA")
    m = mark(int(height * 0.34), palette)
    w = wordmark(int(width * 0.42), int(height * 0.24), WORDMARK_COLORS[""])
    w = w.crop(w.getbbox())
    gap = int(width * 0.03)
    total = m.width + gap + w.width
    x0 = (width - total) // 2
    img.alpha_composite(m, (x0, (height - m.height) // 2))
    img.alpha_composite(w, (x0 + m.width + gap, (height - w.height) // 2))
    return img.convert("RGB")


def save(img: Image.Image, *parts: str) -> None:
    path = os.path.join(*parts)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path, optimize=True)
    print("wrote", os.path.relpath(path, ROOT))


def main() -> None:
    densities = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for suffix, palette in PALETTES.items():
        for density, size in densities.items():
            save(mark(size, palette), RES, f"mipmap-{density}", f"ic_launcher{suffix}.png")
    save(mark(512, PALETTES[""]), RES, "drawable", "ic_launcher.png")
    save(mark(1080, PALETTES[""]), RES, "drawable", "app_logo_mark.png")
    save(mark(1080, PALETTES[""]), ROOT, "assets", "brand", "app_logo_mark.png")

    for suffix, color in WORDMARK_COLORS.items():
        save(wordmark(1085, 344, color), RES, "drawable", f"app_logo_wordmark{suffix}.png")
    save(wordmark(1085, 344, WORDMARK_COLORS[""]), ROOT, "assets", "brand", "app_logo_wordmark.png")
    save(wordmark(1000, 230, WORDMARK_COLORS[""]), RES, "drawable", "fetch_text.png")

    # Android TV launcher banners, one per launcher icon theme.
    for suffix, palette in PALETTES.items():
        save(banner(320, 180, palette), RES, "mipmap-xhdpi", f"banner{suffix}.png")

    b = banner(1280, 720)
    save(b, RES, "drawable", "tv_banner.png")
    save(b, RES, "drawable-nodpi", "tv_banner.png")
    save(b, ROOT, "assets", "fetch.png")


if __name__ == "__main__":
    main()
