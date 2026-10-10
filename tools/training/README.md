# RoadMesh trainable data aggregate

The materialized data lives in `artifacts/`. `aggregate-report.json` gives exact counts after conversion. This directory is ignored by Git because it contains downloaded images and source archives.

Current materialized aggregate:

| Task | Train | Validation | Test | Total |
|---|---:|---:|---:|---:|
| Road damage images | 16,407 | 1,913 | 1,605 | 19,925 |
| Road user images | 6,948 | 760 | 1,045 | 8,753 |
| Gemma text examples | 16,675 | 1,769 | 2,047 | 20,491 |

The 28,678 images contain 98,178 labeled boxes. Counts refer to real materialized source images after filtering; Gemma rows are derived text examples of those images, not additional image data.

## Contents

| Artifact | Training use | Labels and limits |
|---|---|---|
| `road-damage/` | YOLO object detection | RDD2022: longitudinal, transverse and alligator cracks; potholes. India, Japan, Czech, United States and China motorcycle images. |
| `road-users/` | Separate YOLO object detection task | Udacity CrowdAI: car, truck, person. Labels are incomplete; educational use only. |
| `gemma/` | Gemma 2B text fine tuning supplement | Annotation-grounded `prompt` / `completion` JSONL, source IDs and original splits. Automatically templated; not human-written driving explanations. |
| `raw/` | Reproducible source inputs | Official country ZIPs, Udacity archive, corrected annotation CSV, download hashes. |

**Gemma 2B is text-only.** The JSONL teaches descriptions of supplied detector facts. It does not teach Gemma to read camera pixels, estimate speed from still images, or decide that overtaking is safe. Motion requires measured phone/mesh telemetry.

Keep the two YOLO label spaces separate. A road-damage image does not have exhaustively labeled cars, and a road-user image does not have exhaustively labeled potholes. Naively combining them makes missing labels become false negatives. A combined detector needs a reconciled, fully labeled set or training that explicitly handles partial labels.

## Reproduce

Python 3.10+, `requests`, and `Pillow` are needed for acquisition/conversion.

```powershell
python tools/training/figshare_subset.py
python tools/training/aggregate.py --download --udacity
python tools/training/aggregate.py --build
python tools/training/inspect_data.py
python tools/training/package_data.py
```

The old RDD S3 links currently return HTTP 403. `figshare_subset.py` reads the official Figshare ZIP by HTTP byte ranges and extracts its five nested country ZIPs without downloading the full 13.3 GB archive. Each extracted member is CRC checked by Python's ZIP reader and receives a SHA-256 record. The full Figshare archive MD5 is **not** verified when only portions are fetched. It saves the author's Figshare metadata beside the aggregate. Cached downloads are verified before reuse.

Norway is omitted because its roughly 9.9 GB nested archive exceeds this workspace's download/storage budget. China drone images are omitted because they are aerial views. No login-gated dataset has been bypassed. Broader sources and acquisition requirements are documented in [the research inventory](../../docs/research/dashcam-datasets-and-mesh-desktop.md).

### Data processing

- Human source boxes are clipped to image bounds and normalized to YOLO format. Invalid boxes are excluded and counted; images with unmapped RDD classes are excluded rather than silently treated as background.
- JPEGs retain aspect ratio with maximum dimension 640 pixels. Coordinates in labels remain normalized; `manifest.jsonl` records original dimensions and original-image SHA-256.
- Exact source-image duplicates are removed within each task. No claim of perceptual/near-duplicate removal is made.
- Source and 100-frame numeric blocks receive deterministic train/validation/test assignments, with the first five frames of each block excluded as a temporal guard band. Udacity uses chronological filename order. These are proxy groups, **not verified drive or route IDs**. Separately recorded local drives are needed for trustworthy deployment evaluation.
- The three `.txt` image manifests define each split. Use these, rather than recursively reading the image folders: older files may remain after a subset is rebuilt.
- Gemma examples retain their source image split; duplicate prompts are removed globally. Four authored uncertainty examples are explicitly identified. Empty images do not produce language targets.

## Kaggle: YOLO

`package_data.py` produces separate portable YOLO ZIPs and a small Gemma ZIP. Upload privately and attach the appropriate data to a GPU notebook, retaining the license/provenance files. This step does not launch or replace the existing Kaggle runs in `tools/kaggle`.

```python
!pip install ultralytics
!python /kaggle/input/YOUR_CODE/train_yolo.py --data /kaggle/input/YOUR_DATA/road-damage --output /kaggle/working/road-damage --epochs 30
```

For road users, choose its directory and a different output directory. `train_yolo.py` resolves dataset paths, trains, evaluates the held-out split, exports a fixed 384×640 ONNX file and writes deployment class metadata. Set batch size to fit the GPU. Pin the installed training library version in your run for reproducibility. A custom head has different class IDs from COCO: update the Android detector's mapping before loading it. The aggregate contains training data, not newly trained weights.

## Kaggle: Gemma / the existing co-pilot runner

`load_gemma.py` loads this aggregate without importing a training framework. In the `tools/copilot` runner, after its existing `train = make(...)` and `held = ...` lines:

```python
from load_gemma import load_splits, supplement
grounded = load_splits('/kaggle/input/YOUR_DATA/gemma')
train, grounded_val = supplement(train, grounded, max_extra=6000)
# Keep grounded_val separate from training. Evaluate the test split only after selection.
```

The prompt format is reused directly from `tools/copilot/dataset.py`. The original Gemma training code has not been changed. These templated annotation descriptions complement its authored/synthetic mesh reasoning examples; they are not substitutes for human-reviewed safety training or measured evaluation. Counts, licenses and label origin remain on each row.

## Source attribution and usage terms

- RDD2022: Arya et al., *RDD2022: A multi-national image dataset for automatic Road Damage Detection*; [author repository](https://github.com/sekilab/RoadDamageDetector), [official Figshare record](https://doi.org/10.6084/m9.figshare.21431547). Repository terms are CC BY-SA 4.0; Figshare metadata states CC BY 4.0. This package retains the stricter upstream attribution/share-alike designation; resolve the discrepancy with authors before broader distribution.
- Udacity: [official annotations README](https://github.com/udacity/self-driving-car/tree/master/annotations), CrowdAI archive and corrected CSV. The README restricts these annotations to educational purposes and warns that some labels are missing. This portion is not unrestricted open-source data. Derived examples retain that source designation.

The source inventory and per-image metadata travel with the packages. Public availability does not imply permission for every use.
