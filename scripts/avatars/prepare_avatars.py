#!/usr/bin/env python3
"""Turn background-removed avatar renders into the files the TV and web panel ship.

Each cut-out (a transparent PNG) is trimmed to its subject, scaled so the
subject's longest side is FILL of the canvas, shrunk further if any of it would
fall outside the circle the apps clip avatars to, centred, and saved as a
512x512 transparent WebP named after the source file.

    python3 scripts/avatars/prepare_avatars.py <cut-out dir> app/src/main/assets/avatars
    cp app/src/main/assets/avatars/*.webp web/apps/web/public/avatars/

See docs/avatars.md for how the renders are made.
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image

SIZE = 512
FILL = 0.74
# Radius, as a share of SIZE, that the subject must stay inside.
CIRCLE = 0.47
# Alpha below this is the background remover's faint haze, not the subject.
HAZE = 10

_yy, _xx = np.mgrid[0:SIZE, 0:SIZE]
_OUTSIDE_CIRCLE = np.hypot(_xx - SIZE / 2, _yy - SIZE / 2) > CIRCLE * SIZE


def prepare(src: Path, dst: Path) -> float:
    """Write the avatar for cut-out `src` to `dst`; returns the subject's share of the canvas."""
    image = Image.open(src).convert("RGBA")
    alpha = image.getchannel("A").point(lambda v: 0 if v < HAZE else v)
    image.putalpha(alpha)
    image = image.crop(alpha.getbbox())

    scale = FILL * SIZE / max(image.size)
    while True:
        width = max(1, round(image.width * scale))
        height = max(1, round(image.height * scale))
        canvas = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
        canvas.alpha_composite(
            image.resize((width, height), Image.LANCZOS),
            ((SIZE - width) // 2, (SIZE - height) // 2),
        )
        solid = np.asarray(canvas.getchannel("A")) > 64
        if np.count_nonzero(solid & _OUTSIDE_CIRCLE) <= 16:
            break
        scale *= 0.97

    canvas.save(dst, "WEBP", quality=90, method=6)
    return scale * max(image.size) / SIZE


def main() -> None:
    """Prepare every PNG in the source folder into the output folder."""
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    src_dir, out_dir = Path(sys.argv[1]), Path(sys.argv[2])
    out_dir.mkdir(parents=True, exist_ok=True)
    for src in sorted(src_dir.glob("*.png")):
        dst = out_dir / f"{src.stem}.webp"
        fill = prepare(src, dst)
        print(f"{src.stem}: subject {fill:.0%} of the canvas, {dst.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
