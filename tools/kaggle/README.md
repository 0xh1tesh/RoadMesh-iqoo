# Kaggle training jobs

Both run as private Kaggle GPU scripts under the account in `~/.kaggle`.

- **Road model** (`road_yolo/train.py`): YOLO11n on Indian pothole (RDD2022 India D40 and others) and rider helmet datasets,
  classes pothole / helmet / no_helmet, exported to ONNX at 384x640. Copy `road_yolo11n_384x640.onnx` from the output
  to `app/src/main/assets/models/`.
  `kaggle kernels push -p tools/kaggle/road_yolo`.
- **Co-pilot** (`../copilot/finetune_kaggle.py` with `../copilot/dataset.py` above it): Gemma 2B-it LoRA, merged and
  converted to `gemma-2b-it-resilane-cpu-int4.bin`, plus `copilot_eval.json` with before/after scores. Bundle with
  `python -c` as in `../copilot/README.md`, then `kaggle kernels push -p <dir>`.
