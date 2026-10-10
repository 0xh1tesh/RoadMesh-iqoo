# RESILANE road model: YOLO11n trained on potholes (India + general) and riders with / without helmets.
# Classes: 0 pothole, 1 helmet, 2 no_helmet. Exported to ONNX at 384x640 for the phone.
import os, glob, random, shutil, subprocess, sys, json, xml.etree.ElementTree as ET
subprocess.run([sys.executable, "-m", "pip", "install", "-q", "ultralytics==8.3.40", "onnx", "onnxslim"], check=True)
# Kaggle's image ships a Ray that breaks this Ultralytics version's Ray Tune callback after the first epoch.
subprocess.run([sys.executable, "-m", "pip", "uninstall", "-y", "-q", "ray"], check=False)
from pathlib import Path
random.seed(7)
NAMES = ["pothole", "helmet", "no_helmet"]
OUT = Path("/kaggle/working/ds")
for s in ("train", "val"):
    (OUT / "images" / s).mkdir(parents=True, exist_ok=True); (OUT / "labels" / s).mkdir(parents=True, exist_ok=True)

def map_name(n):
    n = n.lower().replace("_", " ").replace("-", " ")
    if "pothole" in n or n.strip() in ("d40",): return 0
    if "without" in n or "no helmet" in n or "nohelmet" in n.replace(" ", ""): return 2
    if "helmet" in n: return 1
    return None

IMG_EXT = (".jpg", ".jpeg", ".png", ".bmp", ".webp")
stats = {}
per_source = {}
count = 0
def emit(img, rows, tag):
    global count
    if not rows: return
    for row in rows: per_source.setdefault(tag, {}).setdefault(NAMES[int(row.split()[0])], 0)
    for row in rows: per_source[tag][NAMES[int(row.split()[0])]] += 1
    split = "val" if random.random() < 0.12 else "train"
    stem = f"{tag}_{count:06d}"; count += 1
    shutil.copy(img, OUT / "images" / split / (stem + Path(img).suffix.lower()))
    (OUT / "labels" / split / (stem + ".txt")).write_text("\n".join(rows) + "\n")
    for r in rows: stats[NAMES[int(r.split()[0])]] = stats.get(NAMES[int(r.split()[0])], 0) + 1

def yolo_dataset(root, tag):
    names = None
    for y in glob.glob(f"{root}/**/*.yaml", recursive=True):
        import yaml
        d = yaml.safe_load(open(y))
        if isinstance(d, dict) and "names" in d:
            names = d["names"]; names = [names[k] for k in sorted(names)] if isinstance(names, dict) else names; break
    for c in glob.glob(f"{root}/**/classes.txt", recursive=True):
        names = [l.strip() for l in open(c) if l.strip()]; break
    if not names: return 0
    mapping = {i: map_name(n) for i, n in enumerate(names)}
    print(tag, "names", names, "->", mapping)
    labels = {Path(p).stem: p for p in glob.glob(f"{root}/**/labels/**/*.txt", recursive=True)}
    n = 0
    for img in glob.glob(f"{root}/**/*", recursive=True):
        if not img.lower().endswith(IMG_EXT) or Path(img).stem not in labels: continue
        rows = []
        for line in open(labels[Path(img).stem]):
            p = line.split()
            if len(p) != 5: continue
            m = mapping.get(int(float(p[0])))
            if m is not None: rows.append(" ".join([str(m)] + p[1:]))
        emit(img, rows, tag); n += 1
    return n

def voc_dataset(root, tag):
    images = {Path(p).stem: p for p in glob.glob(f"{root}/**/*", recursive=True) if p.lower().endswith(IMG_EXT)}
    n = 0
    for x in glob.glob(f"{root}/**/*.xml", recursive=True):
        t = ET.parse(x).getroot()
        fn = Path(t.findtext("filename") or Path(x).stem).stem
        img = images.get(fn) or images.get(Path(x).stem)
        if not img: continue
        w = float(t.find("size/width").text or 0); h = float(t.find("size/height").text or 0)
        if w <= 0 or h <= 0:
            from PIL import Image; w, h = Image.open(img).size
        rows = []
        for o in t.findall("object"):
            m = map_name(o.findtext("name") or "")
            if m is None: continue
            b = o.find("bndbox"); x1, y1, x2, y2 = (float(b.findtext(k)) for k in ("xmin", "ymin", "xmax", "ymax"))
            rows.append(f"{m} {(x1+x2)/2/w:.6f} {(y1+y2)/2/h:.6f} {(x2-x1)/w:.6f} {(y2-y1)/h:.6f}")
        emit(img, rows, tag); n += 1
    return n

# Each attached dataset is its own root: /kaggle/input/datasets/<owner>/<slug> now, /kaggle/input/<slug> before.
roots = sorted(glob.glob("/kaggle/input/datasets/*/*")) or sorted(glob.glob("/kaggle/input/*"))
for d in roots:
    tag = Path(d).name[:12]
    n = yolo_dataset(d, tag) or voc_dataset(d, tag)
    print(f"{d}: {n} images, {per_source.get(tag, {})}", flush=True)
print("instances", stats, "images", count, flush=True)
assert stats.get("pothole", 0) > 500 and stats.get("no_helmet", 0) > 100, f"class mapping looks wrong: {stats}"
(OUT / "data.yaml").write_text(f"path: {OUT}\ntrain: images/train\nval: images/val\nnames: {NAMES}\n")

from ultralytics import YOLO
model = YOLO("yolo11n.pt")
model.train(data=str(OUT / "data.yaml"), epochs=80, imgsz=640, batch=32, patience=20, cos_lr=True, mosaic=1.0,
            fliplr=0.5, hsv_v=0.5, degrees=3, project="/kaggle/working/runs", name="road", exist_ok=True, plots=True, workers=4)
best = YOLO("/kaggle/working/runs/road/weights/best.pt")
metrics = best.val(data=str(OUT / "data.yaml"), imgsz=640)
summary = {"names": NAMES, "instances": stats, "per_source": per_source, "images": count, "map50": float(metrics.box.map50), "map50_95": float(metrics.box.map),
           "per_class_map50": {NAMES[i]: float(v) for i, v in enumerate(metrics.box.ap50)}}
print(json.dumps(summary, indent=1)); Path("/kaggle/working/summary.json").write_text(json.dumps(summary, indent=1))
best.export(format="onnx", imgsz=(384, 640), opset=12, simplify=True, dynamic=False)
shutil.copy("/kaggle/working/runs/road/weights/best.onnx", "/kaggle/working/road_yolo11n_384x640.onnx")
shutil.rmtree(OUT)
