# RoadMesh

One Android app, three roles: **Detector → Relay → Observer**. A virtual car brakes on A, B forwards the event over BLE extended advertising, and C shows a caution with offline speech and haptics. The stage uses a disclosed A-B-C acceptance filter and synthetic positions. The messages travel over the real radio.

This is an **expandable experimental base**, not a final architecture or a production road safety system. Live sensing, independent trust evidence, signing and deployment tuning are future adapters. The existing simulator remains available as a development harness.

| Module | Responsibility |
|---|---|
| `app/` | Role dashboard, settings, foreground service, BLE adapter, GNSS, speech, haptics and JSON export |
| `core/` | Android-free hazard protocol and mesh state machine; original 27-byte codec retained |
| `simulator/scene/` | Reusable camera/AR car scene and optional detector/dataset feed |
| `simulator/core/` | Scene math, calibration, pose protocol, interpolation and braking timeline |
| `simulator/app/` | Thin standalone scene launcher |
| `tools/junction_lab/` | Offline SUMO comparisons, logical transport/response models and recorded-results viewer |

Start with [architecture](docs/architecture.md), [implementation status](docs/implementation-plan.md), and the [three-phone rehearsal](docs/demo-runbook.md). Earlier research designs are preserved in `docs/archive/`; they are not descriptions of shipped behavior.

The new [Junction Lab](tools/junction_lab/README.md) compares no advice, local sensing and mesh-informed warnings in matched SUMO runs, using a shared Kotlin policy and an offline visual replay. Its [first measured results](docs/junction-lab-results.md) are a baseline for future algorithms, separate from the three-phone app.

The **0.4.1 swarm preview** adds a Swarm tab with a configurable stopped-bus comparison, relay loss and alternate paths, evidence expiry, and a synthetic learning experiment. The phone view uses actual session records, preserves original report identities, groups neighbours by movement and uncertainty, and supports manual road-narrowing reports. See the [jury demo guide](docs/swarm-jury-demo.md) for the demonstration, implemented scope and next field experiment.

Download the APK from the [0.4.1 swarm preview release](https://github.com/0xh1tesh/RoadMesh-iqoo/releases/tag/v0.4.1-swarm-preview). This debug build uses a different signing certificate from the published 0.4 APK, so it cannot update that installation in place. Export any reports before replacing an older installation; uninstalling removes its local app data.

## Build

JDK 17, Android SDK 36, and the checked-in Gradle 8.14.6 wrapper. Set `JAVA_HOME` and `ANDROID_HOME`, or an escaped `sdk.dir` in ignored `local.properties`.

```powershell
.\gradlew.bat :core:test :sim-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :simulator-app:assembleDebug
```

Install **the same** `app/build/outputs/apk/debug/app-debug.apk` on all three phones. Select a different role on each. The root build's standalone harness is `simulator/app/build/outputs/apk/debug/simulator-app-debug.apk` and has a different application ID. The RoadMesh APK defaults to the arm64 loaners; override with `-ProadmeshAbis=arm64-v8a,x86_64` for additional targets.

The detector assets are ignored by Git. To reproduce the team's prototype clip detection, restore the two checksum-pinned models with `powershell -File tools/fetch_prototype_models.ps1` before building. This swarm-preview APK includes those models. The pitch brake and manual reports work without detection. The separate simulator model can be fetched with `simulator/tools/fetch_model.py`; its opt-in check is `:sim-core:test -PwithModelTests=true --rerun-tasks`. An explicitly requested model test fails if its asset is missing. The optional Gemma model remains a separate import.

## What is validated

JVM tests cover the codec, the A-B-C chain, freshness, duplicate handling, bounds, relevance, scene math and braking. A deterministic simulation covers 100, 300 and 500 devices over an area with small neighborhoods, 25% seeded packet loss, repeats and a disconnected partition. Its report is `core/build/reports/mesh-simulation.json`. These results do not measure BLE delivery, RF range or graphics performance.

Use the runbook on the three loaners before presenting. Verify extended advertising on every phone, camera fallback, offline speech, screen-off reception and repeat runs. A local TX completion is not a delivery receipt. No end-to-end latency or PDR is claimed by this build.

## Attribution

See [ATTRIBUTION.md](ATTRIBUTION.md), including the CC-BY car model and the optional detector's AGPL terms. Repository licensing remains a team decision; fetching an optional model is a separate licensing choice.
