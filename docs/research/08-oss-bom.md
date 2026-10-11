# RoadMesh open-source bill of materials, licences and dependency set

*Researched 2026-10-08/09 · Sources: 18 entries covering about 60 URLs · Confidence: High for the licences and versions listed (read from GitHub, the registries and Hugging Face); Medium overall. NYV = not yet verified.*

Card A8. Licences, stars and activity come from the GitHub REST API on 2026-10-08. Versions come from `maven-metadata.xml` (Google Maven and Maven Central), npm and PyPI.

## Bottom line

- **BLE: write it on the platform API** (`startAdvertisingSet` plus `ScanSettings.Builder().setLegacy(false)`). Nordic's Kotlin-BLE-Library v2 (BSD-3) calls `startAdvertisingSet`, but it is beta, its README leaves scan settings unfinished, and whether it exposes non-legacy parameters is NYV. Read **tinySSB (MIT)** for exactly our pattern: non-connectable extended advertising on Coded PHY plus an extended scan.
- **Mesh apps are read-only.** bitchat-android, knit, Briar and Meshtastic are GPL-3.0, and qaul is AGPL-3.0. The Swift bitchat is **Unlicense**. Avoid Bridgefy (proprietary agreement plus an account) and guacamaya-net (PolyForm Noncommercial). Pasting code would also break the "written at the event" rule.
- **Decide the detector licence on day 1.** Ultralytics YOLO (package, weights and apps) is **AGPL-3.0**, and only its Enterprise License removes that. Either licence the whole repo AGPL-3.0, or run an Apache-2.0 detector through LiteRT or MediaPipe. The Qualcomm AI Hub apps repo is BSD-3, with its Android sample at `object_detection_android/`. Per-model licences are NYV because our listing query failed, so read the model card of whichever detector the sample loads.
- **LLM: Gemma 4 E2B is Apache-2.0.** Hugging Face licence tags say so for `google/gemma-4-E2B-it` and `litert-community/gemma-4-E2B-it-litert-lm`. Gemma 3n and Gemma 3 still carry the Gemma licence. Use Gemma 4 E2B on LiteRT-LM 0.18.0.
- **Versions verified on 2026-10-08:** CameraX 1.6.2, LiteRT 2.3.0, MediaPipe Tasks 1.1.0 and LiteRT-LM 0.18.0 (all on Google Maven only); ORT QNN 1.29.0, Tink 1.23.0, Compose BOM 2026.09.00, coroutines 1.11.0, Play Services Location 21.4.0.
- **osmdroid is archived** (last push 2024-11). Use MapLibre Native 13.6.1 only if the phone needs a map; use Leaflet 1.9.4 + Leaflet.heat on the laptop.
- **First commit: `LICENSE` + `ATTRIBUTION.md`** listing every dependency, model and dataset. Download models at first run instead of committing them.

## Bill of materials

Stars and dates as of 2026-10-08. NYV means not yet verified.

### BLE extended advertising and scanning (Kotlin)

| Option | URL | Licence | Stars | Last activity | Reuse as | Risk | Verdict |
|---|---|---|---|---|---|---|---|
| Android platform API (`AdvertisingSet`, `ScanSettings.setLegacy(false)`) | developer.android.com (see Reference docs) | Platform | n/a | n/a | Write it ourselves | Phone support for extended advertising and Coded PHY varies: check `isLeExtendedAdvertisingSupported()` on the loaner | **Use** |
| Nordic Kotlin-BLE-Library v2 | https://github.com/nordicsemi/Kotlin-BLE-Library | BSD-3-Clause | 528 | 2.0.0-beta07, 2026-10-07 | Optional dependency `no.nordicsemi.kotlin.ble:advertiser-android` / `client-android` 2.0.0-beta07 (Maven Central). Read `advertiser-android/.../BluetoothLeAdvertiserOreo.kt` | Beta. README: "Scan settings / background scan" unchecked. Its mock environment helps unit tests | Read; use the advertiser module only if it saves time |
| tinySSB | https://github.com/ssbc/tinySSB | MIT | 94 | commit 2026-07-09 | Read `BlePeersBroadcast.kt`: `setLegacyMode(false)`, non-connectable, `PHY_LE_CODED` primary, service data; scan with `setLegacy(false).setPhy(PHY_LE_CODED)`, LOW_LATENCY | Small project | **Best permissive reference** |
| Fieldwatch | https://github.com/OffGridPete/Fieldwatch | MIT | 2,801 | v1.1.21, 2026-10-06 | Read the BLE scanner (`radio/BleRadio.kt` uses `setLegacy(false)`) | Receive-only app | Read |
| android/platform-samples | https://github.com/android/platform-samples | Apache-2.0 | 1,750 | commit 2026-10-06 | Read the connectivity samples (BLE sample paths NYV) | Low | Read |
| android/connectivity-samples | https://github.com/android/connectivity-samples | Apache-2.0 | 1,788 | **Archived**, last commit 2025-07-10 | Read only | Stale | Skip |
| Kable / BLESSED / Nordic BLE 2.11 / Scanner-Compat | https://github.com/JuulLabs/kable · https://github.com/weliem/blessed-kotlin · https://github.com/nordicsemi/Android-BLE-Library · https://github.com/nordicsemi/Android-Scanner-Compat-Library | Apache-2.0 · MIT · BSD-3 · BSD-3 | 1,197 · 91 · 2,416 · 796 | 0.45.0 (2026-09-15) · 3.1.0 (2026-05-25) · 2.11.0 (2025-09-11) · 1.6.0 (**2021**) | GATT/central-focused (Kable advertising support NYV) | Mismatch with a connectionless broadcast | Skip |

### BLE mesh and relay apps to learn from (read only)

| Option | URL | Licence | Stars | Last activity | What to read | Verdict |
|---|---|---|---|---|---|---|
| bitchat-android | https://github.com/permissionlesstech/bitchat-android | **GPL-3.0** | 7,752 | v2.0.1 (2026-08-11), commit 2026-10-06 | Dedup, TTL, relay and fragmentation ideas | Read only |
| bitchat (Swift original) | https://github.com/permissionlesstech/bitchat | **Unlicense** (public domain) | 36,486 | v1.7.1 (2026-07-31) | Protocol design | Read freely |
| knit | https://github.com/getknit/knit | **GPL-3.0** | 132 | v2.8.0 (2026-10-03) | Wi-Fi Aware + BLE offline mesh (fallback ladder) | Read only |
| qaul.net | https://github.com/qaul/qaul.net | **AGPL-3.0** | 732 | v2.0.0-rc.8 (2026-08-30) | `blemodule` advertiser and scanner | Read only |
| Meshtastic firmware | https://github.com/meshtastic/firmware | **GPL-3.0** | 8,393 | commit 2026-10-08 | Flood routing with hop limit (details NYV) | Read only |
| Briar | https://github.com/briar/briar | **GPL-3.0-or-later** (LICENSE.txt) | 704 | mirror, commit 2026-09-27 | Offline sync design | Read only |
| nRF Mesh Library | https://github.com/nordicsemi/Android-nRF-Mesh-Library | BSD-3-Clause | 476 | 3.5.0 (2026-09-24) | Bluetooth SIG Mesh provisioning (inference: GATT proxy, not phone-to-phone broadcast) | Not a fit |
| Bridgefy SDK | https://github.com/bridgefy/sdk-android | **Proprietary** "SDK License Agreement" (accept terms + developer account) | 30 | commit 2026-04-15 | n/a | Avoid |
| guacamaya-net | https://github.com/vmlcode/guacamaya-net | **PolyForm Noncommercial 1.0.0** | 8 | commit 2026-07-17 | n/a | Avoid |

### Camera, detection, audio, LLM, speech

| Need | Option | URL | Licence | Stars | Last activity | Reuse as | Verdict |
|---|---|---|---|---|---|---|---|
| Runtime | LiteRT | https://github.com/google-ai-edge/LiteRT | Apache-2.0 | 3,473 | v2.3.0 (2026-10-07) | Dependency (GPU, NPU via vendor plug-in) | **Use (GPU first)** |
| Samples | litert-samples | https://github.com/google-ai-edge/litert-samples | Apache-2.0 | 458 | commit 2026-10-07 | Read the Android object-detection sample (path NYV) | Read |
| Samples | mediapipe-samples | https://github.com/google-ai-edge/mediapipe-samples | Apache-2.0 | 2,853 | commit 2026-10-07 | Read the object detector, audio classifier (YAMNet) and LLM Android samples | Read |
| Tasks API | MediaPipe | https://github.com/google-ai-edge/mediapipe | Apache-2.0 | 37,188 | v1.1.0 (2026-10-07) | Dependency `tasks-vision` / `tasks-audio` | Use for audio; optional for vision |
| NPU path | ONNX Runtime + QNN EP | https://github.com/microsoft/onnxruntime | MIT | 22,037 | v1.30.0 (2026-09-10) | Dependency `onnxruntime-android-qnn` (version below) | Upgrade path |
| Samples | ORT inference examples | https://github.com/microsoft/onnxruntime-inference-examples | MIT | 1,691 | commit 2026-09-06 | Read the Android object-detection example | Read |
| NPU models | Qualcomm AI Hub apps | https://github.com/qualcomm/ai-hub-apps | BSD-3-Clause (repo) | 463 | v0.38.0 (2026-09-30) | Read the Android object-detection app | Read; check each model's licence |
| NPU models | Qualcomm AI Hub models | https://github.com/qualcomm/ai-hub-models | BSD-3-Clause (repo) | 1,226 | v0.64.0 (2026-10-07) | Export or download QNN/TFLite models | Model licences vary |
| Detector | Ultralytics | https://github.com/ultralytics/ultralytics | **AGPL-3.0** | 62,300 | v8.4.174 (2026-10-06) | Export YOLO26/YOLO11 to TFLite/ONNX/QNN | Only if repo goes AGPL-3.0 |
| Detector app | yolo-flutter-app | https://github.com/ultralytics/yolo-flutter-app | **AGPL-3.0** | 502 | v0.6.15 (2026-09-20) | Read only | Read only |
| Camera | camera-samples | https://github.com/android/camera-samples | GitHub API detects **no licence** (check LICENSE file, NYV) | 5,463 | commit 2026-08-10 | Read CameraX samples | Read |
| Camera | ML Kit samples | https://github.com/googlesamples/mlkit | Apache-2.0 (samples; ML Kit SDK itself is closed source) | 4,301 | commit 2026-07-21 | CameraX + analyzer pattern | Read |
| Legacy | tensorflow/examples | https://github.com/tensorflow/examples | Apache-2.0 | 8,278 | commit 2026-10-05 | Old TFLite Android samples | Prefer litert-samples |
| LLM | LiteRT-LM | https://github.com/google-ai-edge/LiteRT-LM | Apache-2.0 | 6,610 | v0.18.0 (2026-10-06) | Dependency (Kotlin API) | **Use** |
| LLM app | AI Edge Gallery | https://github.com/google-ai-edge/gallery | Apache-2.0 | 24,875 | 1.0.20 (2026-10-06) | Read model download and chat flows | Read |
| LLM alt | MLC LLM | https://github.com/mlc-ai/mlc-llm | Apache-2.0 | 23,213 | push 2026-10-06 | Fallback runtime | Fallback only |
| LLM alt | llama.cpp | https://github.com/ggml-org/llama.cpp | MIT | 130,535 | push 2026-10-08 | Fallback runtime | Fallback only |
| Speech | sherpa-onnx | https://github.com/k2-fsa/sherpa-onnx | Apache-2.0 | 15,168 | push 2026-10-05 | ASR, VAD, keyword-spotting and TTS Android demos (AAR packaging NYV) | Use for ASR/KWS; Android system TTS for alerts |
| Speech alt | Vosk | https://github.com/alphacep/vosk-api | Apache-2.0 | 15,168 (same figure as sherpa-onnx; recheck) | push 2026-08-09; `com.alphacephei:vosk-android` 0.3.75 | Offline ASR | Fallback |

### Signing, maps, dashboard, simulation, geometry

| Need | Option (version) | URL | Licence | Stars | Last push | Verdict |
|---|---|---|---|---|---|---|
| Ed25519 | Tink `tink-android` 1.23.0 | https://github.com/tink-crypto/tink-java | Apache-2.0 | 307 | 2026-10-08 | **Use** |
| Ed25519 | BouncyCastle `bcprov-jdk18on` 1.86 | https://github.com/bcgit/bc-java | MIT (GitHub detection) | 2,697 | 2026-10-07 | Fallback |
| Ed25519 | Lazysodium Android | https://github.com/terl/lazysodium-android | MPL-2.0 | 122 | 2025-05-31 | Skip (stale, native libs) |
| Phone map | osmdroid 6.1.20 (Aug 2024) | https://github.com/osmdroid/osmdroid | Apache-2.0 | 3,079 | **Archived** (2024-11-20) | **Avoid** |
| Phone map | MapLibre Native `org.maplibre.gl:android-sdk` 13.6.1 | https://github.com/maplibre/maplibre-native | BSD-2-Clause | 2,256 | 2026-10-08 | Only if the phone needs a map |
| Laptop map | Leaflet 1.9.4 + Leaflet.heat 0.2.0 | https://github.com/Leaflet/Leaflet · https://github.com/Leaflet/Leaflet.heat | BSD-2-Clause (both) | 45,714 · 1,657 | 2026-10-05 · 2024-06-28 | **Use** |
| Laptop alt | MapLibre GL JS 6.13.0; deck.gl 9.4.0 | https://github.com/maplibre/maplibre-gl-js · https://github.com/visgl/deck.gl | BSD-3-Clause (npm) · MIT | 11,825 · 14,637 | 2026-10-08 | If Leaflet lags with 500 moving cars |
| Backend | FastAPI 0.143.0 + uvicorn 0.54.0 + websockets 17.2 | https://github.com/fastapi/fastapi | MIT · BSD-3 · BSD-3 | 102,882 | 2026-10-08 | **Use** |
| Backend alt | Node `ws` 8.22.0 | https://github.com/websockets/ws | MIT | 22,811 | 2026-09-26 | Alternative |
| Office Kit drop folder | watchdog 6.0.0 · chokidar 5.0.0 | https://github.com/gorakhargosh/watchdog · https://github.com/paulmillr/chokidar | Apache-2.0 · MIT | 7,420 · 12,246 | 2026-09-22 · 2026-10-04 | **Use watchdog**: ingest JSON files that Office Kit drops into a configurable folder (Office Kit's default save path NYV) |
| Simulation | SUMO 1.28.0 (`eclipse-sumo` on PyPI) | https://github.com/eclipse-sumo/sumo | EPL-2.0 OR GPL-2.0-or-later | 4,202 | 2026-10-08 | Optional: import Bengaluru from OSM, export FCD, replay on the dashboard |
| Simulation | OSMnx 2.1.1 | https://github.com/gboeing/osmnx | MIT | 5,863 | 2026-07-31 | **Use** for a custom simulator on real roads; fetch and cache the graph while online |
| Simulation | Veins | https://github.com/sommer/veins | GPL-2.0 (+ OMNeT++, licence NYV) | 328 | 2026-06-16 | Skip (heavy setup) |
| Geometry | `Location.distanceBetween` / `bearingTo` + our own along-track code | Android platform; formulas at https://www.movable-type.co.uk/scripts/latlong.html | Platform (page licence NYV) | n/a | n/a | **Use** |
| Geometry | GeographicLib Java | https://github.com/geographiclib/geographiclib-java | No licence detected by GitHub (NYV) | 38 | 2026-07-14 | Skip (overkill under 1 km) |

## Licence review

| Licence / terms | Where | What it means for a public hackathon repo |
|---|---|---|
| GPL-3.0, GPL-3.0-or-later | bitchat-android, knit, Meshtastic, Briar | Copied code makes our repo a GPL derivative, and pasting breaks the "written at the event" rule anyway. Read for ideas only |
| AGPL-3.0 | Ultralytics package, YOLO weights and Flutter app; qaul.net | The Ultralytics README offers AGPL-3.0 for open use. Its **Enterprise License** is what lets products integrate "Ultralytics software and AI models" without AGPL-3.0 obligations. A public repo licensed AGPL-3.0 complies; later commercial use needs the Enterprise License. Decide on day 1 |
| PolyForm Noncommercial | guacamaya-net | Avoid |
| Proprietary SDK agreement | Bridgefy | Needs acceptance and an account; avoid |
| Unlicense | bitchat (Swift) | Public domain |
| MPL-2.0 | Lazysodium | File-level copyleft; fine unmodified, but we skip it |
| BSD / MIT / Apache-2.0 | Nordic, tinySSB, LiteRT, LiteRT-LM, MediaPipe, ORT, Tink, AI Hub repos (README: "licensed under BSD-3"), Leaflet, FastAPI | Keep copyright notices in `ATTRIBUTION.md`; carry Apache NOTICE text where present |
| EPL-2.0 OR GPL-2.0-or-later; GPL-2.0 | SUMO; Veins | Running a tool does not change our repo licence. Do not copy their code |
| Model terms | AI Hub per-model licences, Gemma, Llama 3.2 Community License, YAMNet, TTS voices (some MMS voices reported as CC-BY-NC), Qualcomm QNN/QAIRT runtime libraries | NYV unless the Bottom line says otherwise. Read each model card before bundling; prefer Apache-2.0 or MIT models; download models at first run instead of committing them; list them in `ATTRIBUTION.md` |
| Data terms (NYV) | OSM (ODbL, tile usage policy), RDD2022, DriveIndia, iWatchRoad, IDD | Credit "© OpenStreetMap contributors"; no bulk tile prefetch from tile.openstreetmap.org; cite datasets and respect non-commercial clauses |

## Minimal dependency set

Versions read from `maven-metadata.xml`, npm and PyPI on 2026-10-08. Declare both `google()` and `mavenCentral()`: LiteRT, MediaPipe and LiteRT-LM are on Google Maven only.

### Android (Kotlin)

| Purpose | Coordinate | Version | Repository |
|---|---|---|---|
| BLE | Platform API | minSdk 26 for `AdvertisingSet`; runtime BLE permissions from API 31 | n/a |
| Camera | `androidx.camera:camera-core` / `-camera2` / `-lifecycle` / `-view` | 1.6.2 stable (1.7.0-alpha03 newest; checked on camera-core) | Google |
| Vision runtime | `com.google.ai.edge.litert:litert` | 2.3.0 | Google |
| NPU alternative | `com.microsoft.onnxruntime:onnxruntime-android-qnn` | 1.29.0 (the QNN build lags the CPU build, 1.30.0) | Central |
| Audio (YAMNet), vision tasks | `com.google.mediapipe:tasks-audio` / `tasks-vision` | 1.1.0 (checked on tasks-vision, which moved from 0.10.x to 1.0.0) | Google |
| LLM | `com.google.ai.edge.litertlm:litertlm-android` | 0.18.0 (the README marks the Kotlin API "Stable") | Google |
| Signing | `com.google.crypto.tink:tink-android` | 1.23.0 | Central |
| Coroutines | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.11.0 | Central |
| UI | `androidx.compose:compose-bom` | 2026.09.00 | Google |
| Location | `com.google.android.gms:play-services-location` | 21.4.0 | Google |
| Optional | `no.nordicsemi.kotlin.ble:advertiser-android` 2.0.0-beta07 · `org.maplibre.gl:android-sdk` 13.6.1 (skip `-pre` builds) | | Central |

SDK levels and conflicts:
- compileSdk and targetSdk should match the loaner's Android version (Android 16 / API 36 is likely; NYV here). Each AAR's minCompileSdk is NYV: if Gradle's AAR-metadata check fails, raise compileSdk.
- Use **one** LLM stack: LiteRT-LM, without MediaPipe `tasks-genai` as well. Duplicate TFLite-derived native libraries between MediaPipe and LiteRT are possible (NYV), so run `./gradlew :app:dependencies` and a release build on the first evening.
- osmdroid is archived. Do not start on it.

### Laptop

| Package | Version | Licence |
|---|---|---|
| fastapi · uvicorn[standard] · websockets | 0.143.0 · 0.54.0 · 17.2 | MIT · BSD-3 · BSD-3 |
| watchdog | 6.0.0 | Apache-2.0 |
| osmnx · eclipse-sumo (both optional) | 2.1.1 · 1.28.0 | MIT · EPL-2.0 OR GPL-2.0-or-later |
| leaflet · leaflet.heat (vendor the files for offline use) | 1.9.4 · 0.2.0 | BSD-2-Clause |
| deck.gl · maplibre-gl (optional) | 9.4.0 · 6.13.0 | MIT · BSD-3-Clause |
| Node alternative: ws · chokidar | 8.22.0 · 5.0.0 | MIT |

## Reference docs to consult at the event

Canonical pages. Not every link was re-fetched in this session.

- BLE: https://developer.android.com/develop/connectivity/bluetooth/ble/ble-overview · https://developer.android.com/reference/android/bluetooth/le/AdvertisingSetParameters.Builder · https://developer.android.com/reference/android/bluetooth/le/BluetoothLeAdvertiser · https://developer.android.com/reference/android/bluetooth/le/ScanSettings.Builder · https://developer.android.com/develop/connectivity/bluetooth/bt-permissions · https://source.android.com/docs/core/connect/bluetooth/ble_advertising
- Foreground services: https://developer.android.com/develop/background-work/services/fgs/service-types
- CameraX analysis: https://developer.android.com/media/camera/camerax/analyze
- LiteRT and MediaPipe: https://ai.google.dev/edge/litert · https://ai.google.dev/edge/mediapipe/solutions/vision/object_detector/android · https://ai.google.dev/edge/mediapipe/solutions/audio/audio_classifier/android
- ONNX Runtime QNN EP: https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html
- LiteRT-LM Kotlin: https://github.com/google-ai-edge/LiteRT-LM (README)
- sherpa-onnx Android: https://k2-fsa.github.io/sherpa/onnx/android/index.html
- Tink signatures: https://developers.google.com/tink/digital-signature
- Geometry: https://developer.android.com/reference/android/location/Location · https://www.movable-type.co.uk/scripts/latlong.html
- Dashboard: https://fastapi.tiangolo.com/advanced/websockets/ · https://leafletjs.com/examples/quick-start/ · https://github.com/Leaflet/Leaflet.heat · https://deck.gl/docs/api-reference/aggregation-layers/heatmap-layer
- Simulation: https://sumo.dlr.de/docs/Tutorials/OSMWebWizard.html · https://sumo.dlr.de/docs/TraCI.html · https://sumo.dlr.de/docs/Simulation/Output/FCDOutput.html
- OSM terms: https://www.openstreetmap.org/copyright · https://operations.osmfoundation.org/policies/tiles/

## Sources

1. GitHub REST API `repos/OWNER/REPO` plus `/commits` and `/releases/latest`, 2026-10-08: licence, stars, push date, archived flag and release for every GitHub URL in the tables
2. GitHub code search for `startAdvertisingSet` and `setLegacy(false)` (language: Kotlin), 2026-10-08
3. https://github.com/nordicsemi/Kotlin-BLE-Library: README (beta, feature checklist); `advertiser-android/.../BluetoothLeAdvertiserOreo.kt` calls `startAdvertisingSet`
4. https://github.com/ssbc/tinySSB: MIT LICENSE; `android/.../tssb/ble/BlePeersBroadcast.kt`
5. https://github.com/bridgefy/sdk-android: LICENSE.md (SDK License Agreement)
6. https://github.com/briar/briar: LICENSE.txt (GPL-3.0-or-later)
7. https://github.com/vmlcode/guacamaya-net: LICENSE (PolyForm Noncommercial 1.0.0)
8. https://github.com/ultralytics/ultralytics: README licence section (AGPL-3.0, Enterprise License)
9. https://github.com/qualcomm/ai-hub-apps: README ("licensed under BSD-3"); `release` branch tree (`object_detection_android/`)
10. https://github.com/google-ai-edge/LiteRT-LM: README (Kotlin API Stable; guide https://ai.google.dev/edge/litert-lm/android)
11. https://dl.google.com/android/maven2/: `maven-metadata.xml` for camera-core, compose-bom, play-services-location, litert, tasks-vision, tasks-audio, litertlm-android
12. https://repo1.maven.org/maven2/: `maven-metadata.xml` for the Nordic artifacts, kable-core, onnxruntime-android(-qnn), tink-android, kotlinx-coroutines-android, osmdroid-android, maplibre android-sdk, bcprov-jdk18on, vosk-android
13. https://registry.npmjs.org/: leaflet, leaflet.heat, maplibre-gl, deck.gl, ws, chokidar
14. https://pypi.org/pypi/: fastapi, uvicorn, websockets, watchdog, eclipse-sumo, osmnx
15. https://huggingface.co/api/models: licence tags for google/gemma-4-*, google/gemma-3n-*, litert-community Gemma builds
16. https://huggingface.co/google/gemma-4-E2B-it: model page (tag `license:apache-2.0`)
17. https://github.com/tensorflow/models/tree/master/research/audioset/yamnet: YAMNet source (GitHub detects no repo licence, so NYV)
18. team planning notes (SOLUTION.md, not in this repo) §4 (earlier research)
