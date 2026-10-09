"""Rebuild the optional detector, scene/src/main/assets/models/yolo11n_640x320.onnx.

The ONNX file is not committed (models are fetched, never checked in). This re-exports exactly
what the app and YoloOnnxTest expect: Ultralytics YOLO11n, input 1x3x640x320 (h x w, portrait),
opset 17, exported with ultralytics 8.4.174.

    pip install ultralytics==8.4.174 onnx onnxslim
    python tools/fetch_model.py            # downloads yolo11n.pt if needed (AGPL-3.0 weights)
"""
import pathlib
import shutil

from ultralytics import YOLO

root = pathlib.Path(__file__).resolve().parent.parent
dest = root / "scene/src/main/assets/models/yolo11n_640x320.onnx"

onnx = pathlib.Path(YOLO("yolo11n.pt").export(format="onnx", imgsz=(640, 320), opset=17))
dest.parent.mkdir(parents=True, exist_ok=True)
shutil.move(str(onnx), dest)
print(f"wrote {dest} ({dest.stat().st_size / 1e6:.1f} MB)")
