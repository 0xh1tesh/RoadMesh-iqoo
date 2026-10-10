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
| ONNX Runtime Android, QNN build `onnxruntime-android-qnn` 1.29.0 | On-device YOLO inference, CPU or Hexagon NPU | MIT | https://github.com/microsoft/onnxruntime |
| Qualcomm AI Engine Direct runtime `com.qualcomm.qti:qnn-runtime` 2.50.0 | Hexagon NPU (HTP) backend libraries | Qualcomm licence shipped with the Maven artifact | https://central.sonatype.com/artifact/com.qualcomm.qti/qnn-runtime |
| MediaPipe LLM Inference `tasks-genai` 0.10.32 | On-device co-pilot runtime | Apache-2.0 | https://github.com/google-ai-edge/mediapipe |
| Gemma 2B-it (not committed; fine-tuned copy built by `tools/copilot`) | Co-pilot language model | Gemma Terms of Use | https://ai.google.dev/gemma/terms |
| PEFT, Transformers, Ultralytics (Kaggle training jobs only) | LoRA fine-tune, YOLO training | Apache-2.0 / Apache-2.0 / AGPL-3.0 | https://github.com/huggingface/peft, https://github.com/huggingface/transformers, https://github.com/ultralytics/ultralytics |
| Kaggle datasets for the road model: `vidishbijalwan/rdd2022-india-pothole-d40` (RDD2022, CC BY-SA 4.0), `anggadwisunarto/potholes-detection-yolov8`, `andrewmvd/pothole-detection`, `andrewmvd/helmet-detection`, `aneesarom/rider-with-helmet-without-helmet-number-plate` | Training data for pothole / helmet / no-helmet (`tools/kaggle/road_yolo`) | Per each dataset page on Kaggle | https://www.kaggle.com/datasets |
| *C V Raman Road* dashcam clip | Bundled dashcam replay | CC0 | Wikimedia Commons |
| *Cars Moving into pothole* (Amuzujoe), transcoded to 640x360 | Bundled roadside pothole clip | CC BY-SA 4.0 | https://commons.wikimedia.org/wiki/File:Cars_Moving_into_pothole.webm |
| CameraX 1.6.2 (`camera-camera2`, `camera-lifecycle`, `camera-view`, `camera-mlkit-vision`) | Driver monitor: front camera preview and frame analysis | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/camera |
| ML Kit Face Detection, bundled model `com.google.mlkit:face-detection` 16.1.7 | Driver monitor: on-device face, eye-open and head-pose estimates | ML Kit Terms of Service | https://developers.google.com/ml-kit/terms |
| ONNX Runtime JVM 1.20.0 (opt-in test dependency) | Optional model evaluation on laptop | MIT | https://github.com/microsoft/onnxruntime |
| AndroidX core, activity, lifecycle | simulator/: app framework | Apache-2.0 | https://developer.android.com/jetpack/androidx |
| Ultralytics YOLO11n weights (not committed; `simulator/tools/fetch_model.py`) | simulator/: car detector scored against simulator ground truth | AGPL-3.0 (enterprise licence otherwise) | https://github.com/ultralytics/ultralytics |
| *Car Concept* glTF model (Khronos glTF Sample Assets; Khronos plate logo blanked at load) | simulator/: rendered vehicle | CC-BY 4.0 | https://github.com/KhronosGroup/glTF-Sample-Assets |

Read-only references (ideas, never copied code): bitchat (Unlicense), tinySSB (MIT). GPL/AGPL mesh apps (bitchat-android, knit, Meshtastic, Briar, qaul) are not used as code sources.
