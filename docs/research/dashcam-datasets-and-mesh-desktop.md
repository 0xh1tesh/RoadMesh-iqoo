# RoadMesh data and desktop mesh simulator research

**Implementation update:** This was the initial research snapshot. Trainable artifacts now exist under [`tools/training`](../../tools/training/README.md), and the desktop plus Android telemetry adapter are implemented under [`tools/mesh-desk`](../../tools/mesh-desk/README.md). The `tools/copilot` and Kaggle co-pilot work also now exist in this checkout. Repository observations below describe the earlier scan, not the final state. The broader source catalog remains useful for later acquisitions.

Research snapshot: 10 October 2026. This is a project handoff for the Kaggle training work and the proposed desktop mesh monitor. Dataset access and license terms can change; check the linked source and record the accepted terms before downloading or sharing data.

## Executive direction

1. **Train YOLO for perception.** Start with the existing YOLO-compatible pipeline and detection model in `simulator/`; build the training set around road users, road hazards and Indian traffic. Use IDD as the best regional anchor, then add BDD100K and selected permissively licensed or research-only sources only after checking their terms. Split by drive/video/route, never by randomly sampled adjacent frames.
2. **Use Gemma 2B as a text model, not the image detector.** The Gemma 2B model card describes a text-to-text model. Feed it structured, already-detected events and mesh context for explanations/debriefs; do not ask it to localize objects in dashcam pixels. Keep the safety alert path deterministic and independent of Gemma.
3. **Treat “open” as three different things.** Source code may be open, data may be free to download, and data/model weights may still prohibit commercial use, redistribution, or use in a deployed product. Keep per-source lineage and license metadata in the Kaggle artifacts.
4. **Desktop monitor: add an opt-in live telemetry bridge.** Current RoadMesh phones communicate operational alerts over BLE. Browsers do not receive that BLE mesh as a fleet feed. Add a local laptop service that receives phone telemetry over the same Wi-Fi/hotspot, then streams it to the desktop page. Show synthetic/replay/live states distinctly and show stale data as stale.

## What is in this repository today

- Android modules are Kotlin/Compose (`app/`, `core/`). `simulator/scene/` owns the reusable vehicle scene; `simulator/core/` contains pose and braking timeline math.
- `tools/viewer/README.md` describes a future offline JSON log viewer; the directory currently has no web app implementation.
- The README documents a checked-in optional YOLO11n ONNX model and an optional model evaluation path. `ATTRIBUTION.md` identifies Ultralytics YOLO11n as AGPL-3.0 unless separately licensed.
- The repo scan found no Kaggle notebook, `kaggle.json`, dataset config, or Gemma training script. This only describes the checkout at the time; the Kaggle co-pilot work now lives in `tools/copilot` and `tools/kaggle`.
- Current architecture docs describe an experimental starting layer, not a validated road-safety product. Some simulation positions are synthetic. The desktop must expose source and confidence instead of presenting inferred or replayed coordinates as live GPS.

## Dataset shortlist

| Dataset | Useful content / likely RoadMesh use | Access and license notes | Priority |
|---|---|---|---|
| [India Driving Dataset (IDD)](https://inai.iiit.ac.in/domains/idd.html), [official INSAAN catalog](https://insaan.iiit.ac.in/datasets/) | Indian road mix; IDD Detection (40k box-labeled images), segmentation, temporal and multimodal subsets; includes two-wheelers, three-wheelers, pedestrians, animals and unstructured roads. Strongest domain match. | IDD license is a research-use license, not a blanket open-source/commercial grant. Download from the official portal, accept current terms, attribute, and do not redistribute data/reformatted copies unless terms explicitly allow it. | **P0** |
| [BDD100K](https://github.com/ucbdrive/bdd100k) | 100k driving videos / heterogeneous tasks; detection, lane, drivable area, weather/time tags. Broad exposure and useful hard-negative context. | Read the current official download terms for each asset/task. Do not assume the paper or toolkit license also licenses image/video data or trained weights for unrestricted redistribution. | **P0** |
| [A2D2 (Audi)](https://www.a2d2.audi/), [dataset paper](https://arxiv.org/abs/2004.06320) | 41,277 frames with semantic labels; 12,497 include front-view 3D boxes; 392,556 sequential unlabeled frames, 6 cameras, lidar and vehicle bus. Useful for road users and temporal context. | Paper reports CC BY-ND 4.0, but verify the currently offered download terms and what that permits for derived labels/model training. | **P1** |
| [Mapillary Vistas](https://www.mapillary.com/dataset/vistas) | 25k densely annotated street-level images, 66 classes; broad geographies and camera devices, useful for segmentation/context and long-tail road objects. | Dataset paper describes CC BY-SA; Mapillary imagery generally carries attribution/share-alike obligations. Confirm the dataset-specific terms and implications for weights before use. | **P1** |
| [Waymo Open Dataset](https://waymo.com/open/about/) | Perception: 2,030 segments; Motion: 103,354 20-second scenes; end-to-end: 5,000 segments. Strong detection/tracking/motion labels and scenario diversity. | **Not an OSI open-source license**: registration and Waymo non-commercial dataset terms; no commercial use by default. Do not use for production-oriented weights without written rights. | **P1 research only** |
| [nuScenes](https://www.nuscenes.org/nuscenes) | 1,000 scenes, 6 cameras + lidar/radar, 3D object tracks and attributes; useful tracking/scenario validation. | Free registered access for non-commercial use under dataset terms/CC BY-NC-SA 4.0. Not a commercial training-data default. | **P1 research only** |
| [Argoverse 2](https://www.argoverse.org/av2.html) | Sensor: 1,000 annotated scenarios; motion: 250k scenarios. Useful multi-agent interaction and motion tracks; less dashcam-phone-like. | CC BY-NC-SA 4.0; non-commercial/share-alike terms. | **P2 research only** |
| [KITTI](https://www.cvlibs.net/datasets/kitti/) | Classic forward-camera/lidar object detection, tracking and odometry benchmarks; good sanity check, small/older domain. | Published under CC BY-NC-SA 3.0 according to the official site. Check each benchmark's terms; non-commercial/share-alike. | **P2** |
| [comma2k19](https://github.com/commaai/comma2k19) | 33 hours / 2,019 highway segments with video, IMU and raw GNSS; useful pose/trajectory and temporal experiments, not comprehensive object detection labels. | Review the repository's current data/license terms before training or distributing derivative artifacts. Highway and California bias. | **P2** |
| [D2-City](https://arxiv.org/abs/1904.01975) | 10k+ Chinese dashcam clips; 1k clips have per-frame detection boxes and tracking for 12 classes, with keyframe labels for remainder. Good temporal tracking source. | Paper/repo access is not enough to establish redistribution/commercial rights. Verify dataset terms before ingestion; assess overlap and geographic domain gap. | **P2 after terms check** |
| [DoTA](https://github.com/MoonBlvd/Detection-of-Traffic-Anomaly) | 4,677 traffic-anomaly clips with temporal/spatial anomaly labels; useful for hazard event classification and replay scenarios, not a substitute for box labels. | Videos are sourced from online channels; obtain and follow the dataset's current terms and source/creator rights. Avoid mirroring/rehosting clips. | **P2 event research** |
| [Driver Anomaly Detection (DAD)](https://www.ce.cit.tum.de/en/mmk/dad/) | In-cabin depth/IR driver behavior recordings with normal/anomalous actions. Relevant only if RoadMesh adds distracted-driver monitoring. | Research-only download with a separate license agreement; not road-scene dashcam data. Exclude from current road-object training. | **Out of scope** |
| [DriveIndia (paper/preprint)](https://arxiv.org/abs/2507.19912) | Reported 66,986 images, 24 traffic classes and 120+ hours across urban/rural/highway; appears exceptionally relevant to Indian traffic. | The paper says planned public availability through TiHAN-IITH. Confirm official release, labels, and license before depending on it; do not assume downloadable/cleared today. | **Watchlist** |

### Best-fit class taxonomy for first YOLO experiment

Use a small, stable detection taxonomy that can be consistently mapped across source datasets. Keep unsupported labels out rather than silently mapping them to a wrong class.

- **Road users:** car, bus, truck, motorcycle/scooter, bicycle, auto-rickshaw/three-wheeler, person, animal.
- **Potential hazards:** stopped/parked vehicle in travel lane, fallen object/debris, pothole/road damage (only where boxes/segmentation support reliable labels), road work/cone/barrier, crash/overturned vehicle, flood/water obstruction, smoke/fire.
- **Context / auxiliary tasks:** traffic light/sign, lane/road edge, drivable area, weather/low light. These may need segmentation or classification heads; don't force them into object boxes.

Class mapping should keep the native source class, normalized class, source dataset, source sequence, license ID and transformation version. Long-tail hazard classes will be sparse; report per-class counts and avoid headline mAP dominated by common cars.

## Data recipe for the Kaggle training work

### 1. Start with one reproducible YOLO baseline

1. Pin the exact base checkpoint, code version, image size, classes, transforms, seed, and Kaggle accelerator in a run manifest. Current repo supports YOLO11n ONNX evaluation at 640x320; validate input preprocessing and output class mapping before choosing a training/export size.
2. Begin with IDD Detection; add BDD100K detection after its current terms and conversion are recorded. Preserve official train/val/test boundaries. If creating a combined split, group by video/sequence/drive and keep all nearby frames in one split to prevent leakage.
3. Convert annotations once to the model format; store a deterministic conversion script and a source-to-normalized-class mapping. Deduplicate near-identical frames across sources with perceptual hashes, while preserving source/license lineage.
4. Add targeted hard cases: night, rain, glare, motion blur, occlusion, dense two/three-wheeler traffic, animals, roadside vs in-lane stops, potholes/debris and unusual viewpoints. Use augmentation only on training data and retain a clean untouched test set.
5. Measure per-class precision/recall/AP, confusion matrix, small-object recall and latency on target iQOO hardware after ONNX export. Evaluate false alert rate on long normal-driving clips; benchmark temporal stability/tracking separately from single-frame mAP.

### 2. Keep Gemma's job narrow

Gemma 2B base is text-to-text. If the app expects conversational explanations, use the instruction-tuned variant and a structured prompt contract. Train/evaluate on records such as `{objects, confidence, relative_position, speed_delta, event_type, time, provenance}` mapped to concise explanations and debrief summaries. Use curated labels or synthetic scenarios reviewed by humans; don't turn generated text into ground-truth perception labels. Keep language model output advisory and off the immediate brake/alert decision path.

The base Gemma 2B checkpoint is gated by acceptance of Google's usage terms on Hugging Face; “open weights” does not mean unrestricted OSI-licensed data/model. Record checkpoint variant, license acceptance, quantization, prompt/data provenance and evaluation. Avoid full fine-tuning initially: compare prompt-only, small adapter fine-tuning and retrieval/templates; evaluate factuality, latency/RAM, multilingual behavior and unsupported claims.

### 3. Training and evaluation deliverables to retain from Kaggle

- `run_manifest.json`: source commit, dataset versions/terms, class map, exact split manifest hash, seed, base weights hash, framework/version, accelerator and export options.
- Notebook or scripts for download instructions (never credentials), checksum validation, conversion, training, evaluation, ONNX export and smoke test.
- Per-source and combined counts; duplicate/leakage report; per-class metrics and PR/confusion plots; representative false positives/negatives with source IDs.
- Final `.pt` and `.onnx` files with hashes, input/output contract and target-device latency/memory measurements. Do not check huge datasets or private Kaggle credentials into this repo.
- License manifest (`dataset`, version/date, terms URL, accepted account/org, allowed purpose, attribution, redistribution/model restrictions, derivative checkpoint hash).

### 4. Licensing and implementation flags

- This repo's `ATTRIBUTION.md` identifies its optional Ultralytics YOLO11n weights/framework as AGPL-3.0. Ultralytics' current licensing page says its trained models default to AGPL-3.0 and offers a separate Enterprise license. Decide whether the project will fully comply with AGPL or use a separately licensed detector before shipping; exporting to ONNX does not automatically remove upstream obligations.
- Dataset terms and detector-framework terms are separate. A permissively licensed codebase does not make non-commercial datasets usable in a commercial product.
- Waymo, nuScenes, Argoverse 2, IDD and other research datasets in this list should not be put into a production candidate simply because Kaggle makes them easy to attach.
- Maintain a “trainable for deployment” pool with explicit affirmative rights; maintain a separate research-only benchmark pool. Do not publish derived weights until terms have been reviewed.

## Desktop “Mesh Desk” feature spec

### User-facing outcome

A desktop site shows phones/nodes in a free 3D scene as cars. When a phone moves, its car moves; the car arrow shows heading; braking and overtaking events appear in sync with telemetry. The page also shows connected-node count, each node's role, last update, speed/heading/position accuracy, link/relay status and a readable event timeline.

### What the current app can and cannot provide

Current app phone-to-phone alert delivery uses BLE. A desktop browser cannot directly inspect every other phone from that relationship, and BLE advertising does not provide a reliable fleet-wide position stream. Add a deliberately enabled laptop bridge over local Wi-Fi/hotspot. Each phone sends its own GNSS/heading plus selected mesh events directly to the bridge; it should not claim that a phone is “connected” only because it was heard on BLE. Bridge presence and BLE-neighbor state must be distinct.

### Proposed local architecture

```text
Phone MeshSession / PositionSource
  └─ opt-in local telemetry adapter (Wi-Fi, authenticated pairing code)
      └─ desktop bridge service (localhost UI + LAN WebSocket ingest)
          ├─ validates schema, source time, ranges, session and sequence
          ├─ expires stale nodes/events and retains a bounded in-memory history
          └─ WebSocket snapshot/delta stream → tools/viewer desktop page
```

- Host bridge binds the dashboard UI to loopback by default. For phone ingress, explicitly bind to the private LAN interface, show its address and pairing code, and offer stop/disconnect controls. Pairing token expires and can be revoked. No cloud relay in the first slice.
- Transport proposal: WebSocket over the local network (TLS when available; short-lived pairing token required); HTTPS serves the page. Add schema version and message type. Do not use an open unauthenticated socket on all interfaces.
- Suggested messages: `hello {schemaVersion,nodeId,sessionId,role,capabilities}`, `pose {seq,sourceTime,lat,lon,accuracyM,speedMps,headingDeg,headingAccuracyDeg,source}`, `mesh {seq,neighbors:[opaqueNodeIds],linkState}`, `event {eventId,type,sourceTime,position?,confidence,provenance,originId,forwarderId}`, `heartbeat`, and `goodbye`.
- Validate finite/ranged coordinates, monotonic sequence, timestamp skew and maximum payload; dedupe by origin/session/event ID. Keep only a short configurable local session buffer; redact IDs in screenshots/export; never stream camera images in the telemetry channel.
- Convert GNSS lat/lon to a local ENU scene frame around a user-selected/session origin. Show an uncertainty ring from horizontal accuracy. Smooth display-only motion with timestamped interpolation; do not modify authoritative alert logic.

### Scene and interaction

- A free-space 3D scene with simple top-down / perspective modes, orbit/pan/zoom, reset camera, follow-node, fit-all, and keyboard equivalents. Use stylized vehicle meshes with obvious heading arrows; avoid road geometry implying map precision unless an actual map is loaded.
- Car movement interpolates between time-stamped poses. Braking uses the event's own event time and provenance; lamp flash + short brake glow and timeline marker. Overtake should appear only when supported by temporally consistent relative tracks; otherwise label it “simulated” or “inferred”.
- Node list and event timeline remain available without WebGL. Selection links list ↔ scene ↔ timeline. Distinguish node roles, live/replay/demo source, GNSS accuracy and stale/disconnected states by label/icon as well as color.
- Header: bridge status, live/replay/demo selector, connected node count, last packet age. Side panel: node rows and event feed. Scene is primary and resizable; charts/details are secondary.
- Respect reduced motion dynamically; offer a pause-freeze mode, no forced camera motion, visible keyboard focus, labels/tooltips and accessible status announcements. Use instancing when many repeated vehicles are rendered.
- Dark real-time-monitoring visual system from UI Pro Max: technical/data hierarchy, alert red with safety blue, Fira Sans body + Fira Code telemetry, semantic tokens and WCAG AA contrast. Reserve red for brake/hazard states, not general decoration.

### Delivery phases and acceptance

**Phase A: replay prototype.** Extend `tools/viewer/` to load an exported JSON session and play node poses/events in the 3D scene. Include sample synthetic data with an explicit DEMO banner. Verify brake, overtaking, stale and disconnect behaviors using replay fixtures.

**Phase B: bridge.** Add a local desktop bridge and opt-in Android telemetry adapter. Show each phone only after its paired session sends a heartbeat; show BLE peer observations as a separate fact. Handle reconnect, duplicate/out-of-order messages and clock skew.

**Phase C: real-world rehearsal.** With 2–3 phones, walk/drive in a controlled safe setting, check GNSS accuracy and heading quality, compare event timestamps and scene lag, test Wi-Fi loss and permission revocation. Do not use the visualizer to direct driving or claim a safety benefit.

Acceptance checks: (1) each node's source/time/accuracy are inspectable; (2) phone movement produces the expected relative scene motion after interpolation; (3) brake and relay events appear once with origin/forwarder and timestamps; (4) stale nodes visibly age and expire; (5) demo/replay is never mistaken for live; (6) disconnect/re-pair works without restarting; (7) no camera frames or precise locations leave the local session without explicit export.

## Sources and current primary links

- IDD: [IIIT INAI IDD overview](https://inai.iiit.ac.in/domains/idd.html), [IIIT INSAAN dataset catalog and downloads](https://insaan.iiit.ac.in/datasets/), [IDD Research Use License](https://india-data.org/licenses/idd-crs-license).
- BDD100K: [official dataset repository](https://github.com/ucbdrive/bdd100k), [annotation format](https://github.com/ucbdrive/bdd100k/blob/master/doc/format.md).
- A2D2: [official Audi portal](https://www.a2d2.audi/), [dataset paper](https://arxiv.org/abs/2004.06320).
- Mapillary: [Vistas dataset](https://www.mapillary.com/dataset/vistas), [open data license explanation](https://help.mapillary.com/hc/en-us/articles/115001770409-CC-BY-SA-license-for-open-data).
- Waymo: [dataset scope](https://waymo.com/open/about/), [license terms](https://waymo.com/open/terms/), [FAQ on open-source status](https://waymo.com/open/faq/).
- nuScenes: [official portal and terms](https://www.nuscenes.org/nuscenes).
- Argoverse 2: [official dataset and license](https://www.argoverse.org/av2.html).
- KITTI: [official benchmark and license](https://www.cvlibs.net/datasets/kitti/).
- comma2k19: [official repository](https://github.com/commaai/comma2k19).
- D2-City: [dataset paper](https://arxiv.org/abs/1904.01975). DoTA: [official paper/code/data repo](https://github.com/MoonBlvd/Detection-of-Traffic-Anomaly).
- DriveIndia: [paper/preprint and stated future release path](https://arxiv.org/abs/2507.19912); verify actual release before use.
- Gemma 2B: [model card and access conditions](https://huggingface.co/google/gemma-2b).
- Ultralytics: [current license guidance](https://www.ultralytics.com/license), [official docs](https://docs.ultralytics.com/).
- Kaggle accelerator setup: [NVIDIA Kaggle notebook guide](https://docs.nvidia.com/datascience/deployment/latest/platforms/kaggle/). Availability varies by account, quota and current Kaggle policy; record the actual accelerator selected by each run.

