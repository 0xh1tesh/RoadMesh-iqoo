# Attribution

Every library, model, dataset and reference used, with its licence. Update in the same commit that adds the dependency.

| Item | Use | Licence | Source |
|---|---|---|---|
| Kotlin and Kotlin coroutines 2.2.21 / 1.10.2 | Core, Android adapters and coroutine actors | Apache-2.0 | https://github.com/JetBrains/kotlin / https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX Compose / Material 3 (BOM 2026.02.01), Activity Compose 1.10.1 | RoadMesh role dashboard and settings | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| JUnit 4.13.2 / Jupiter 5.10.2 | JVM regression tests | EPL-1.0 / EPL-2.0 | https://junit.org/ |
| JSON-java 20240303 | JVM-only validation tests for Android JSON profiles | Public domain | https://github.com/stleary/JSON-java |
| ARCore SDK for Android `com.google.ar:core` 1.48.0 | simulator/: 6-DoF tracking, marker shared frame | Apache-2.0 (runtime: Google Play Services for AR terms) | https://github.com/google-ar/arcore-android-sdk |
| Nearby Connections `play-services-nearby` 19.3.0 | simulator/: phone-to-phone pose relay | Android SDK License / Google APIs Terms | https://developers.google.com/nearby/connections |
| ONNX Runtime Android `onnxruntime-android` 1.20.0 | simulator/: on-device YOLO inference | MIT | https://github.com/microsoft/onnxruntime |
| ONNX Runtime JVM 1.20.0 (opt-in test dependency) | Optional model evaluation on laptop | MIT | https://github.com/microsoft/onnxruntime |
| AndroidX core, activity, lifecycle | simulator/: app framework | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Ultralytics YOLO11n weights (not committed; `simulator/tools/fetch_model.py`) | simulator/: car detector scored against simulator ground truth | AGPL-3.0 (enterprise licence otherwise) | https://github.com/ultralytics/ultralytics |
| *Car Concept* glTF model (Khronos glTF Sample Assets; Khronos plate logo blanked at load) | simulator/: rendered vehicle | CC-BY 4.0 | https://github.com/KhronosGroup/glTF-Sample-Assets |

Read-only references (ideas, never copied code): bitchat (Unlicense), tinySSB (MIT). GPL/AGPL mesh apps (bitchat-android, knit, Meshtastic, Briar, qaul) are not used as code sources.
