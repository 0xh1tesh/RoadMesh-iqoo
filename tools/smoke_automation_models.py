"""Run the bundled ONNX models on actual bundled clip frames, without Android or a GPU.

Requires numpy, Pillow, onnxruntime and either ffmpeg on PATH, --ffmpeg, or imageio-ffmpeg.
Example: python tools/smoke_automation_models.py --output build/release-review/model-smoke
This is an execution smoke check, not a detector accuracy or phone performance benchmark.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time

import numpy as np
import onnxruntime as ort
from PIL import Image, ImageDraw

COCO = {0: "person", 1: "bicycle", 2: "car", 3: "motorcycle", 5: "bus", 7: "truck", 9: "traffic_light"}
ROAD = {0: "pothole", 1: "helmet", 2: "no_helmet"}


def sha256(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def decoder(explicit: str | None) -> str:
    if explicit:
        return explicit
    found = shutil.which("ffmpeg")
    if found:
        return found
    try:
        import imageio_ffmpeg
        return imageio_ffmpeg.get_ffmpeg_exe()
    except ImportError as exc:
        raise RuntimeError("Supply --ffmpeg or install imageio-ffmpeg in an isolated environment") from exc


def frame_at(ffmpeg: str, clip: Path, seconds: float) -> Image.Image:
    result = subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-ss", str(seconds),
        "-i", str(clip), "-frames:v", "1", "-vf", "scale=640:360", "-pix_fmt", "rgb24", "-f", "rawvideo", "pipe:1"],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=30, check=True)
    if len(result.stdout) != 640 * 360 * 3:
        raise ValueError(f"No complete RGB frame at {seconds}s in {clip.name}")
    return Image.frombytes("RGB", (640, 360), result.stdout)


def infer(session: ort.InferenceSession, image: Image.Image, labels: dict[int, str], threshold: float):
    input_info = session.get_inputs()[0]
    _, _, height, width = input_info.shape
    scale = min(width / image.width, height / image.height)
    size = (round(image.width * scale), round(image.height * scale))
    pad_x, pad_y = (width - size[0]) // 2, (height - size[1]) // 2
    canvas = Image.new("RGB", (width, height), (114, 114, 114))
    canvas.paste(image.resize(size, Image.Resampling.BILINEAR), (pad_x, pad_y))
    tensor = np.asarray(canvas, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0
    started = time.perf_counter()
    raw = session.run(None, {input_info.name: tensor})[0]
    duration_ms = (time.perf_counter() - started) * 1000
    if raw.ndim != 3 or raw.shape[0] != 1 or raw.shape[1] < 5 or not np.isfinite(raw).all():
        raise AssertionError(f"Unexpected or nonfinite YOLO output: {raw.shape}")
    predictions = raw[0].T
    ids = predictions[:, 4:].argmax(axis=1)
    scores = predictions[np.arange(len(predictions)), ids + 4]
    indices = np.where((scores >= threshold) & np.isin(ids, list(labels)))[0]
    # Class-wise NMS for readable inspection images. Counts are not ground-truth annotations.
    selected = []
    for i in indices[np.argsort(-scores[indices])]:
        x, y, w, h = predictions[i, :4]
        box = np.array([(x - w / 2 - pad_x) / scale, (y - h / 2 - pad_y) / scale,
                        (x + w / 2 - pad_x) / scale, (y + h / 2 - pad_y) / scale])
        if w <= 0 or h <= 0:
            continue
        suppressed = False
        for previous in selected:
            if previous["class_id"] != int(ids[i]):
                continue
            other = np.array(previous["box"])
            wh = np.maximum(0, np.minimum(box[2:], other[2:]) - np.maximum(box[:2], other[:2]))
            intersection = wh.prod()
            union = (box[2:] - box[:2]).prod() + (other[2:] - other[:2]).prod() - intersection
            if union > 0 and intersection / union > 0.5:
                suppressed = True
                break
        if not suppressed:
            selected.append({"class_id": int(ids[i]), "label": labels[int(ids[i])],
                "score": round(float(scores[i]), 5), "box": [round(float(v), 2) for v in box]})
    return selected, list(raw.shape), round(duration_ms, 2)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output", type=Path, default=Path("build/release-review/model-smoke"))
    parser.add_argument("--ffmpeg")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    ffmpeg = decoder(args.ffmpeg)
    assets = args.root / "app/src/main/assets"
    options = ort.SessionOptions()
    options.intra_op_num_threads = 1
    options.inter_op_num_threads = 1
    models = []
    for name, labels, threshold in [("yolo11n_384x640.onnx", COCO, .25), ("road_yolo11n_384x640.onnx", ROAD, .35)]:
        path = assets / "models" / name
        session = ort.InferenceSession(str(path), options, providers=["CPUExecutionProvider"])
        models.append((name, labels, threshold, session))
    report = {"scope": "Desktop CPU execution on real bundled clip frames; no accuracy, Android camera, NPU, tracking or radio validation",
        "onnxruntime": ort.__version__, "models": [{"file": n, "sha256": sha256(assets / "models" / n),
            "input_shape": s.get_inputs()[0].shape, "provider": s.get_providers()} for n, _, _, s in models], "frames": []}
    counts = Counter()
    for clip_name in ["bangalore_cv_raman_road.mp4", "pothole_traffic_cc-by-sa.mp4", "pothole_road_cc-by-sa.mp4"]:
        clip = assets / "dashcam" / clip_name
        for seconds in [2, 5, 8]:
            frame = frame_at(ffmpeg, clip, seconds)
            row = {"clip": clip_name, "clip_sha256": sha256(clip), "seconds": seconds, "models": []}
            annotated = frame.copy()
            draw = ImageDraw.Draw(annotated)
            for name, labels, threshold, session in models:
                detections, shape, duration = infer(session, frame, labels, threshold)
                counts[name] += len(detections)
                row["models"].append({"file": name, "output_shape": shape, "finite": True,
                    "desktop_cpu_inference_ms": duration, "detections": detections})
                for d in detections:
                    draw.rectangle(d["box"], outline="yellow" if labels == COCO else "red", width=2)
                    draw.text(tuple(d["box"][:2]), f"{d['label']} {d['score']:.2f}", fill="white", stroke_width=1, stroke_fill="black")
            annotated.save(args.output / f"{clip.stem}-{seconds}s.png")
            report["frames"].append(row)
    report["detection_counts"] = dict(counts)
    report["checks"] = {"decoded_frames": len(report["frames"]), "finite_model_runs": 2 * len(report["frames"]),
        "general_model_has_detections": counts["yolo11n_384x640.onnx"] > 0}
    (args.output / "report.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"report": str(args.output / "report.json"), "checks": report["checks"], "detection_counts": dict(counts)}, indent=2))
    if not report["checks"]["general_model_has_detections"]:
        raise SystemExit("General model produced no relevant detections in any sampled frame")


if __name__ == "__main__":
    main()
