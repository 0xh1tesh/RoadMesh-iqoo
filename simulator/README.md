# ResiLane reusable car scene and development harness

The friend's shared-AR vehicle layer now lives in **`scene/`**, reused by both RoadMesh and this standalone harness. This is an expandable starting layer; the simulation/learning architecture handoff in `docs/architecture/` remains a proposal. Operational hazard behavior is documented in [RoadMesh architecture](../docs/architecture.md).

| Path | Responsibility |
|---|---|
| `core/` | JVM math, shared-frame calibration, pose codec, replay tracking, clock sync, interpolation, vehicle placement, vision evaluation and braking timeline |
| `scene/` | Android ARCore/Camera2 activity, GLES renderer, car/marker assets, pose adapters, optional ONNX inference and dataset sinks |
| `app/` | Thin standalone `MainActivity : SimulatorActivity` launcher |
| `tools/` | Model/marker preparation, optional UDP monitor, Office Kit transfer and development helpers |

The harness retains marker-based shared-world pose exchange over Nearby/UDP, joystick heading, hologram/realistic rendering, contact shadows, and test cars. RoadMesh's pitch scene disables those pose transports and extra controls, uses a realistic virtual car, and maps `SceneEvent.BrakeStarted` to the separate BLE hazard pipeline. Simulated car poses never become BLE hazard packets implicitly.

The **Brake** action creates a rear-facing car that decelerates to rest, with timed brake lamps. The timeline is tested independently of Android. The scene API is deliberately small (`SceneHost`, `SceneEvent`) so another host can replace the event adapter later.

## Build

JDK 17, Android SDK 36 and Gradle wrapper 8.14.6. From the repository root:

```powershell
.\gradlew.bat :sim-core:test :simulator-app:assembleDebug
```

Or from this directory, using the standalone settings:

```powershell
.\gradlew.bat :core:test :app:assembleDebug
```

The standalone APK is `app/build/outputs/apk/debug/app-debug.apk`, package `org.resilane.sim`. It is a development harness, not the RoadMesh three-role APK. Use the root `app/` APK for the pitch.

## Tracking and lifecycle

ARCore is optional. Supported devices use tracked position and marker calibration; unsupported/unavailable ARCore falls back to Camera2 plus the rotation sensor. **3DOF is rotation only**: walking cannot move the viewpoint and there is no shared frame for real peers. Check current device support and the app's actual runtime mode rather than assuming a model name proves support.

The Camera2 fallback now accounts for display rotation in UVs, field of view and orientation. CPU model data survives GL context recreation for reupload; old GL names are cleared. Camera worker threads and bitmap data are released on teardown, and overlay repaint callbacks stop on pause. Landscape alignment and recovery must still be checked on each target phone.

## Optional detector and learning feed

YOLO is optional and loads lazily only when enabled. The model is not committed or required for a visual brake. A missing asset is reported as unavailable. To build it:

```powershell
python tools/fetch_model.py
.\gradlew.bat :core:test -PwithModelTests=true --rerun-tasks
```

Review the optional detector's AGPL terms in [ATTRIBUTION](../ATTRIBUTION.md) before fetching. The exporter writes `scene/src/main/assets/models/yolo11n_640x320.onnx`. An explicitly requested model test fails when the file is absent; ordinary core tests do not claim to evaluate weights.

`VisionPipeline` resolves the currently rendered GL buffer before swap, so ground truth, frame ID and capture time belong to the same pixels. MSAA is resolved explicitly, RGBA is flipped/downscaled on the worker, and only one sample is in flight. HUD views are outside the captured GL surface. `FeedSink` is synchronous and borrows the bitmap; copy it if retaining it. Ground-truth boxes are evaluator/dataset information, not independent perception evidence for the operational mesh.

Scene protocol v2 adds a session nonce while retaining v1 decoding. Old sequences no longer reset replay tracking; both replay windows and peer tables are bounded. Hello-only peers expire. `tools/monitor.py` reads both header revisions. Test it with:

```powershell
python -m unittest discover -s tools -p test_monitor.py
```

## Office Kit

For manual transfer, pass the intended APK explicitly:

```powershell
powershell -File tools/send-officekit.ps1 app/build/outputs/apk/debug/app-debug.apk
```

Install from Files → Download → Office Kit. `tools/deploy.sh` is an optional standalone-harness helper for Git Bash and Office Kit's own adb. Do not start a competing platform-tools adb server, and do not use the harness's UDP monitor as evidence of BLE hazard delivery.
