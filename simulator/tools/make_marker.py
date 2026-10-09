"""Generate the ResiLane shared-origin marker.

Writes:
  app/src/main/assets/markers/resilane_origin.png   image ARCore tracks
  marker/resilane_origin_A4.pdf                       print at 100 % (no "fit to page")
  marker/resilane_origin.png                          same image, for reference

The marker is a deterministic, non-repeating, high-contrast texture: ARCore
Augmented Images needs dense, non-symmetric corner features. The arrow and
"TOP" text tell people how the world axes are laid out:
  +X  = toward the marker's right edge
  -Z  = toward the "TOP" edge   (heading 0 deg)
  +Y  = up, out of the paper when it lies flat on the floor
"""
import argparse
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent


def font(size):
    for name in ("arialbd.ttf", "Arial Bold.ttf", "DejaVuSans-Bold.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def marker(px=1200, seed=20261009):
    rnd = random.Random(seed)
    img = Image.new("L", (px, px), 255)
    d = ImageDraw.Draw(img)
    m = px // 20
    # Scattered shapes of varying grey give ARCore many unique corners.
    for _ in range(260):
        x, y = rnd.randint(m, px - m), rnd.randint(m, px - m)
        s = rnd.randint(px // 60, px // 9)
        g = rnd.choice((0, 0, 0, 40, 90, 160, 255))
        kind = rnd.random()
        if kind < 0.35:
            d.rectangle([x, y, x + s, y + rnd.randint(s // 3, s)], fill=g)
        elif kind < 0.6:
            pts = [(x + rnd.randint(-s, s), y + rnd.randint(-s, s)) for _ in range(rnd.randint(3, 5))]
            d.polygon(pts, fill=g)
        elif kind < 0.8:
            d.ellipse([x, y, x + s, y + s * rnd.uniform(0.4, 1.0)], fill=g)
        else:
            d.line([x, y, x + rnd.randint(-s * 2, s * 2), y + rnd.randint(-s * 2, s * 2)],
                   fill=g, width=rnd.randint(3, 14))
    # Orientation cues (asymmetric on purpose).
    d.rectangle([px * 0.30, px * 0.04, px * 0.70, px * 0.15], fill=255, outline=0, width=8)
    d.text((px * 0.5, px * 0.095), "TOP  (-Z)", fill=0, font=font(px // 16), anchor="mm")
    d.rectangle([px * 0.30, px * 0.84, px * 0.70, px * 0.95], fill=0)
    d.text((px * 0.5, px * 0.895), "RESILANE", fill=255, font=font(px // 14), anchor="mm")
    cx, cy = px * 0.84, px * 0.5
    d.polygon([(cx + px * 0.11, cy), (cx - px * 0.02, cy - px * 0.07), (cx - px * 0.02, cy + px * 0.07)],
              fill=0, outline=255)
    d.text((cx - px * 0.06, cy), "+X", fill=255, font=font(px // 22), anchor="mm")
    d.rectangle([0, 0, px - 1, px - 1], outline=0, width=px // 60)
    return img


def a4_pdf(img, width_mm, out):
    dpi = 300
    W, H = round(210 / 25.4 * dpi), round(297 / 25.4 * dpi)
    page = Image.new("L", (W, H), 255)
    side = round(width_mm / 25.4 * dpi)
    page.paste(img.resize((side, side), Image.LANCZOS), ((W - side) // 2, round(30 / 25.4 * dpi)))
    d = ImageDraw.Draw(page)
    y = round(30 / 25.4 * dpi) + side + 60
    lines = [
        f"ResiLane shared-origin marker  -  printed width MUST be {width_mm:.0f} mm",
        "Print at 100% / Actual size (disable 'fit to page'), then measure the black square.",
        "If it is not exactly that wide, enter the measured width in the app's Settings.",
        "Tape it FLAT on the floor. Do not move it while anyone is calibrating.",
    ]
    for t in lines:
        d.text((W // 2, y), t, fill=0, font=font(42), anchor="mm")
        y += 70
    page.save(out, "PDF", resolution=dpi)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--width-mm", type=float, default=180.0)
    a = ap.parse_args()
    img = marker()
    for p in (ROOT / "app/src/main/assets/markers", ROOT / "marker"):
        p.mkdir(parents=True, exist_ok=True)
    img.save(ROOT / "app/src/main/assets/markers/resilane_origin.png")
    img.save(ROOT / "marker/resilane_origin.png")
    a4_pdf(img, a.width_mm, ROOT / "marker/resilane_origin_A4.pdf")
    print("ok", img.size)
