# RoadMesh

RoadMesh is an Android prototype for **automatic cooperative perception**. A phone mounted with its rear camera facing the road runs local YOLO models, associates objects across frames, evaluates temporal evidence and automatically shares compact event warnings over Bluetooth. Each participating phone can sense, relay and receive. Drivers do not report each incident.

The **0.5.0 autonomous preview** adds a real camera input and a shared live/replay analysis pipeline. Sustained relative approach, measured host braking and corroborated slowdown have distinct evidence requirements. The optional local Gemma model explains observations after the deterministic event decision; language-model generation does not delay the compact warning. This is an experimental advisory system, without vehicle control or measured accident-prevention claims.

**Pitch with the retained replay clips:** Drive → Open replay lab → select a clip → Start replay demo. Recorded frames run through the local detector and temporal evaluator, with visible boxes and track IDs. The three bundled clips, custom-video picker and 3D brake scene remain available. Replay and synthetic demonstrations are explicitly separate from live road observations.

Start with the [automatic drive guide](docs/AUTOMATED_DRIVE.md) and [jury demonstration](docs/swarm-jury-demo.md). Download the Android APK from [GitHub releases](https://github.com/0xh1tesh/RoadMesh-iqoo/releases).

| Module | Responsibility |
|---|---|
| `app/` | Live rear camera and replay input, local YOLO, drive dashboard, BLE service, GNSS, speech and evidence export |
| `core/` | Temporal object tracking and automatic event evaluation, hazard protocol, bounded mesh state and evidence handling |
| `simulator/scene/` | Reusable camera/AR car scene and optional detector/dataset feed |
| `simulator/core/` | Scene math, calibration, pose protocol, interpolation and braking timeline |
| `simulator/app/` | Thin standalone scene launcher |
| `tools/junction_lab/` | Offline SUMO comparisons, logical transport/response models and recorded-results viewer |

The earlier [architecture](docs/architecture.md), [implementation status](docs/implementation-plan.md) and [three-phone rehearsal](docs/demo-runbook.md) document the original stage harness. The automatic drive guide describes the current primary flow. Earlier research remains in `docs/archive/`.

The new [Junction Lab](tools/junction_lab/README.md) compares no advice, local sensing and mesh-informed warnings in matched SUMO runs, using a shared Kotlin policy and an offline visual replay. Its [first measured results](docs/junction-lab-results.md) are a baseline for future algorithms, separate from the three-phone app.

The **Lab** tab adds computed automatic-event scenarios and retains the stopped-bus network comparison, relay loss, alternative paths, evidence expiry and synthetic learning replay from 0.4.1. This phone uses actual session evidence. Manual injection survives only as a diagnostic tool under Events; it is not the product workflow.

## Build

JDK 17, Android SDK 36, and the checked-in Gradle 8.14.6 wrapper. Set `JAVA_HOME` and `ANDROID_HOME`, or an escaped `sdk.dir` in ignored `local.properties`.

```powershell
.\gradlew.bat :core:test :sim-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :simulator-app:assembleDebug
```

Install **the same** `app/build/outputs/apk/debug/app-debug.apk` on all participating phones. For live sensing, choose Start drive on each; fixed observer/relay roles are not required in ROAD mode. For an indoor three-phone relay demonstration, follow the Stage instructions in the automatic drive guide. The standalone harness is `simulator/app/build/outputs/apk/debug/simulator-app-debug.apk` and has a different application ID. The APK defaults to arm64; override with `-ProadmeshAbis=arm64-v8a,x86_64` for additional targets.

Detector assets are ignored by Git. Restore both checksum-pinned models with `powershell -File tools/fetch_prototype_models.ps1` before building. They support live and recorded camera input; missing models are displayed as unavailable. The optional Gemma model remains a separate import. The separate simulator model can be fetched with `simulator/tools/fetch_model.py`; its opt-in check is `:sim-core:test -PwithModelTests=true --rerun-tasks`.

## What is validated

JVM tests cover temporal persistence, speed uncertainty, one-frame noise, camera turns, stale input, codec behavior, freshness, duplicate handling, bounded state, relevance and scene math. The automatic-event Lab executes the same evaluator on synthetic detector outputs; it does not measure YOLO accuracy. A separate mesh simulation covers 100, 300 and 500 devices with seeded packet loss and a partition; its report is `core/build/reports/mesh-simulation.json`.

Rehearse on the actual phones before presenting. Verify rear-camera orientation, local inference rate, GNSS speed quality, extended advertising and receiver logs. Camera analysis pauses when the app leaves the foreground; it remains attached across foreground tabs. A local TX completion is not a delivery receipt. The software tests do not establish road detection accuracy, RF range, end-to-end latency, packet delivery rate or battery endurance.

## Attribution

See [ATTRIBUTION.md](ATTRIBUTION.md), including the CC-BY car model and the optional detector's AGPL terms. Repository licensing remains a team decision; fetching an optional model is a separate licensing choice.
