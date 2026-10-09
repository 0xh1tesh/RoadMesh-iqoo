"""Score images with YOLO and report how often the simulated vehicle is found.

    python tools/yolo_check.py <dir-or-glob> [--model yolo11n.pt|path.onnx] [--imgsz 640,320]

Filenames from blender_views.py (<paint>_dNN_aNNN.jpg) are grouped by distance and paint.
Any frame: counts a hit when the best vehicle-class (car/bus/truck) box has conf >= --conf.
Also used on phone screenshots pulled with tools/deploy.sh shot.
"""
import argparse
import glob
import os
import re
from collections import defaultdict

from ultralytics import YOLO

VEH = {2, 5, 7}

ap = argparse.ArgumentParser()
ap.add_argument("src")
ap.add_argument("--model", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), "out", "yolo11n.pt"))
ap.add_argument("--imgsz", default="640")
ap.add_argument("--conf", type=float, default=0.25)
a = ap.parse_args()

files = sorted(glob.glob(os.path.join(a.src, "*.jpg")) + glob.glob(os.path.join(a.src, "*.png"))) \
    if os.path.isdir(a.src) else sorted(glob.glob(a.src))
imgsz = [int(v) for v in a.imgsz.split(",")]
model = YOLO(a.model, task="detect")
groups = defaultdict(lambda: [0, 0])
misses = []
for f in files:
    r = model(f, imgsz=imgsz if len(imgsz) > 1 else imgsz[0], conf=0.05, verbose=False)[0]
    best = max(((float(s), r.names[int(c)]) for c, s in zip(r.boxes.cls, r.boxes.conf) if int(c) in VEH),
               default=(0.0, "-"))
    top = max(((float(s), r.names[int(c)]) for c, s in zip(r.boxes.cls, r.boxes.conf)), default=(0.0, "-"))
    hit = best[0] >= a.conf
    m = re.match(r"(\w+)_d(\d+)_a(\d+)", os.path.basename(f))
    key = f"paint {m.group(1)} @ {int(m.group(2))} m" if m else "all"
    groups[key][0] += hit
    groups[key][1] += 1
    if not hit:
        misses.append(f"{os.path.basename(f)}  best vehicle {best[0]:.2f}  top {top[1]} {top[0]:.2f}")
tot = sum(v[0] for v in groups.values()), sum(v[1] for v in groups.values())
for k in sorted(groups):
    h, n = groups[k]
    print(f"{k:28s} {h:3d}/{n:<3d} {100 * h / n:5.1f}%")
print(f"{'TOTAL':28s} {tot[0]:3d}/{tot[1]:<3d} {100 * tot[0] / max(1, tot[1]):5.1f}%")
for m in misses[:40]:
    print("  miss:", m)
