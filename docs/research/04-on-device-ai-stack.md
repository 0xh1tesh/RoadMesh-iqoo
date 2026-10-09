# On-device AI stack for RoadMesh on Snapdragon 8 Elite Gen 5 (iQOO 15)

*Researched 2026-10-08 to 10-09 · Sources: 34 · Confidence: High for the detection path and LLM speeds; Medium for Gemini Nano on the loaner; Low for Kannada speech and thermal (marked "not yet verified")*

Citations are [n] = entry n in Sources. "Not yet verified" = claim seen only in earlier notes or general knowledge, not confirmed from a source in this pass.

## Bottom line

1. **NPU detection is proven on this exact SoC.** On a Xiaomi 17 (8 Elite Gen 5), YOLO26n at 640 px takes **10.7 ms on the NPU, 15.8 ms on the LiteRT GPU and 52.2 ms on the CPU** [1]. The SOLUTION.md numbers hold, and the GPU alone is enough for more than 10 fps.
2. **Fastest Kotlin path: copy Qualcomm's AI Hub sample pattern.** Use the TFLite Interpreter with the QNN delegate from Maven Central (`com.qualcomm.qti:qnn-litert-delegate` and `qnn-runtime`, 2.51.0), falling back to GPU and then XNNPack, in a plain APK [14][15]. The 2.51.0 runtime AAR contains `libQnnHtpV81Skel.so`, the v81 library for the 8 Elite Gen 5 [12]. Build the sample unchanged on the loaner first.
3. **Treat LiteRT 2.3.0's own NPU path as a stretch goal.** It was released 2026-10-07 with new packaging, and the QNN libraries have to be added as dynamic features in an AAB installed with bundletool [7][8][9].
4. **Decide the licence on day 1.** Ultralytics is AGPL-3.0 and YOLOX is Apache-2.0 [21][22]. Choosing YOLO26 means releasing the whole app under AGPL-3.0. YOLOX, AI Hub's default detector [14], keeps the repo permissive.
5. **LLM:** three paths, in order of effort.
   - **Simplest:** the iQOO 15 is on Google's ML Kit GenAI (Gemini Nano) device list, and the Prompt API has structured output (Alpha) [32][33]. Use it to parse voice reports and explain alerts.
   - **Open model:** Gemma 4 E2B on the LiteRT-LM GPU, 52.1 tok/s, TTFT 0.3 s, 2.58 GB file (measured on Galaxy S26 Ultra) [27].
   - **NPU:** Qwen3-4B or Llama 3.2 3B on Genie, 28.6 and 28.2 tok/s on the 8 Elite Gen 5 [26][25], through the GenieX SDK (Developer Preview) [17].
6. **Correction to SOLUTION.md:** "Llama 3.2 3B ~10 tok/s via Genie" is outdated. AI Hub now reports 26.5 tok/s on the 8 Elite and 28.2 tok/s on the 8 Elite Gen 5 [25].
7. **Speech:**
   - **Hindi:** offline TTS exists, as sherpa-onnx Piper hi_IN voices [24].
   - **Kannada:** sherpa-onnx has no model [24], and the MMS Kannada voice is CC-BY-NC-4.0 [29]. Pre-render the templated Kannada alerts as audio clips, unless the phone's kn-IN system voice works offline (day-1 test).
8. **Keep the LLM off the safety path, and make the hardware visible to judges.** Load models only on events. Overlay the active delegate (QNN/HTP or GPU), ms per frame and fps.

## Findings

### 1. Object detection on the NPU

| Option | Setup / Gradle (current) | Model source | Measured on 8 Elite Gen 5 | Licence | Risks |
|---|---|---|---|---|---|
| **(a) LiteRT CompiledModel + Qualcomm accelerator** | `com.google.ai.edge.litert:litert:2.3.0`, `litert-gpu:2.3.0`, `litert-npu-runtime-qualcomm:2.3.0` [8]. QNN libs (`libQnnHtp.so`, `libQnnHtpV81Skel.so`/`Stub.so`, `libQnnSystem.so`, plus `libQnnHtpPrepare.so` for JIT) come from QAIRT 2.50.0.260828 [9]. `CompiledModel.create(context.assets, "m.tflite", CompiledModel.Options(Accelerator.NPU, Accelerator.GPU))` falls back to GPU. NPU needs API 31+ [5] | Any `.tflite`, AOT- or on-device-compiled. SM8850 is supported [4] | Google: on 8 Elite Gen 5, 56+ of 72 models run under 5 ms on the NPU vs 13 on CPU; up to 100x CPU and 10x GPU [6] (no YOLO number) | Apache-2.0 [8] | 1-day-old release that dropped `litert_npu_runtime_libraries.zip` [7]. The sample needs an AAB, dynamic features, bundletool and `--device-groups=Qualcomm_SM8850` [9]. Plain-APK packaging is not yet verified |
| **(b) ONNX Runtime + QNN EP** | `com.microsoft.onnxruntime:onnxruntime-android-qnn:1.29.0` (latest AAR, MIT), which depends on `com.qualcomm.qti:qnn-runtime:2.42.0` [10]. ORT core is at 1.30.0 [11] | `yolo export model=yolo26n.pt format=qnn name=81 imgsz=640` gives `yolo26n_qnn.onnx`, a self-contained context binary, W8A16 [1][2] | **10.7 ms** YOLO26n detect [1] (Ultralytics runs it through ORT QNN EP in its Flutter plugin [1]) | ORT MIT; model AGPL | A context binary compiled with one QAIRT version may not load on another runtime version (inference, not yet verified). Ultralytics documents Flutter deployment only, not Kotlin |
| **(c) AI Hub model + TFLite + QNN delegate** (Qualcomm sample) | `org.tensorflow:tensorflow-lite:2.17.0`, `tensorflow-lite-gpu:2.17.0`, `com.qualcomm.qti:qnn-runtime:2.50.0`, `com.qualcomm.qti:qnn-litert-delegate:2.50.0`, `org.opencv:opencv:4.10.0`; minSdk 31, target 34 [14][15]. Latest Qualcomm AARs are 2.51.0 (2026-10-06); qnn-runtime is about 75 MB [12][13] | `pip install qai-hub-apps` then `qai-hub-apps fetch object_detection_android --model yolox`. Compatible w8a8 TFLite models: YOLOX, YOLOv3/5/6/7/8/10/11 [14] | Not yet verified for v81. The sample is "Tested" on Galaxy S25 (8 Elite) [14] | Sample BSD-3-Clause [14]; delegate under the "Qualcomm AI Hub Model License" [13]; YOLOX Apache-2.0 [22] | Android 14+ [14]. v81 libraries confirmed inside the qnn-runtime 2.51.0 AAR [12]. Whether vivo firmware lets an app load the HTP skel is untested |
| **(d) Ultralytics exports / apps** | `format=qnn` with HTP targets 68, 69, 73, 75, 79 and **81 = 8 Elite Gen 5** [1]. yolo-flutter-app v0.6.6+ ships precompiled v73 and v81 assets [1][2] | Ultralytics Hub / `pip install ultralytics` (docs reference 8.4.67 [2]) | 10.7 ms NPU, 15.8 ms LiteRT GPU, 52.2 ms CPU [1]. An older localized page shows 11.3 ms end-to-end (3.5 pre / 5.6 infer / 2.2 post) [3] | AGPL-3.0 (not yet verified here) | A native Kotlin Ultralytics Android app is not yet verified |
| **(e) MediaPipe Object Detector (EfficientDet-Lite, GPU)** | `com.google.mediapipe:tasks-vision:1.1.0` (latest listed on Google Maven) [34] | MediaPipe model zoo | Not yet verified | Not yet verified | No NPU path; lower accuracy than YOLO-class (not yet verified) |

**What AGPL-3.0 means for a public hackathon repo (not legal advice).** If you ship (distribute) an app containing AGPL code or model weights, or let users interact with a modified version over a network, the whole combined work must be available as source under AGPL-3.0. A public GitHub repo under AGPL-3.0 with the Ultralytics attribution complies. Mixing in GPL-3.0-compatible code is fine; proprietary closed modules are not. The risk is later commercialisation, not the hackathon itself. To avoid AGPL, use YOLOX through AI Hub (c) (licence not yet verified).

**Recommendation.** Build sample (c) unchanged on the iQOO 15 to prove the QNN delegate loads under OriginOS. Then write the Kotlin equivalent: Interpreter options with QNN delegate (HTP), then GPU delegate, then XNNPack. If (c) fails on v81, try (b) with Ultralytics' `name=81` export. In every case, LiteRT GPU at about 15.8 ms [1] is the guaranteed fallback.

### 2. Audio classification
Artifact: `com.google.mediapipe:tasks-audio:1.1.0` (latest listed) [34]. Classes, latency and accelerator support for its YAMNet model (AudioSet labels such as siren and horn) are not yet verified. Treat it as an optional CPU feature, for example "ambulance siren heard" feeding the Emergency Vehicle Alert.

### 3. Speech (TTS / ASR / KWS)
- sherpa-onnx is at v1.13.8 (2026-09-10), Apache-2.0 [19][21]. Hindi TTS: three Piper VITS voices (`vits-piper-hi_IN-{rohan,priyamvada,pratham}-medium`, plus int8 and fp16) [24]. Kannada TTS: none in the release asset list (searched hi/kn/kan/indic). The only other Indic voice is `vits-mimic3-gu_IN` (Gujarati) [24]. Per-voice licences are not yet verified.
- **Kannada TTS options:**
  - `facebook/mms-tts-kan` and `mms-tts-hin` exist but are **CC-BY-NC-4.0** (non-commercial; acceptable for a demo with attribution) [29]. sherpa-onnx conversion of these is not yet verified.
  - Android system TTS kn-IN offline voice: **insufficient evidence found**. The only hits were an APK changelog and aggregators [30]. Test on the phone.
  - **Fallback (inference):** alerts are templated, so render a fixed set of Kannada and Hindi phrases to audio clips at the event, from any TTS, and play them offline.
- **ASR / keyword spotting in sherpa-onnx** [31]:
  - English streaming zipformer models are available (for example `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`).
  - English keyword spotting: `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01-mobile`.
  - **No Hindi or Kannada ASR model was found by name** in the asr-models assets. So Hindi and Kannada voice reports need Android `SpeechRecognizer` (offline language packs not yet verified) or a multilingual Whisper (not yet verified).
- `createOnDeviceSpeechRecognizer` availability on OriginOS 6 is not yet verified; it is a day-1 test.

### 4. Local LLM
- **GenieX (Qualcomm):** "Run frontier LLMs and VLMs locally on Qualcomm devices across NPU, GPU, and CPU". It is the "community version of Qualcomm GENIE" and has "Developer Preview" status [17].
  - Runtimes: llama.cpp for GGUF models (GGML over CPU, GPU or Hexagon HTP kernels), or Qualcomm AI Engine Direct for pre-compiled AI Hub bundles on the NPU [17].
  - README examples: `gemma-4-E4B-it` GGUF, `Qwen3-0.6B` GGUF, AI Hub `Qwen3-4B` / `Qwen3-4B-Instruct-2507` (NPU) and `Qwen2.5-VL-7B-Instruct` (VLM) [17].
  - Gradle: `com.qualcomm.qti:geniex-android:0.4.0` [17].
  - The sample app downloads models at runtime from an in-app catalog [16], so pre-download before going offline.
  - Tokens/s and structured output are not yet verified.
- **LiteRT-LM:** v0.18.0 (2026-10-06), Apache-2.0 [18][21]. Kotlin API "Stable". Tool use and function calling. GPU and NPU acceleration [23].
  - **Gemma 4 exists.** Models: `litert-community/gemma-4-E4B-it-litert-lm`, plus a 12B variant [23][7]. Gemma 3n E2B ships as `google/gemma-3n-E2B-it-litert-lm` [23].
  - Gemma 4 E2B `.litertlm` (Apache-2.0) on Galaxy S26 Ultra [27]:

    | Backend | Prefill | Decode | TTFT | Memory | Notes |
    |---|---|---|---|---|---|
    | GPU | 3,808 tok/s | 52.1 tok/s | 0.3 s | 676 MB | |
    | CPU | 557 tok/s | 46.9 tok/s | 1.8 s | 1,733 MB | |
    | GPU, speculative decoding | | 66.5–91.7 tok/s | | | varies by task |

    File size 2,583 MB; context up to 32k [27]. No NPU row in the card.
  - Gemma 4 E4B: 3.66 GB file, GPU decode 22.1 tok/s, TTFT 0.8 s [28].
  - The card says Gemma 4 is "available through Android AI Core as Gemini Nano" on supported devices [27]. Whether the iQOO 15 is supported: not yet verified.
- **AI Hub / Genie (NPU), Snapdragon 8 Elite Gen 5 "For Galaxy", context 4096:**

  | Model | Runtime | tok/s | TTFT | Licence |
  |---|---|---|---|---|
  | Qwen3-4B w4a16 | Genie | 28.6 | 0.054–1.71 s | Apache-2.0 [26] |
  | Llama 3.2 3B w4a16 | Genie | 28.2 | 0.072–2.31 s | llama3 [25] |
  | Llama 3.2 3B w4a16 | GenieX-QAIRT | 16.3 | 0.096–3.07 s | llama3 [25] |

  Bundles are built with QAIRT 2.45 [26]. Whether "For Galaxy" bundles run on a non-Samsung SM8850 (the iQOO 15) is not yet verified; this is a day-1 test.
- **Gemini Nano via ML Kit GenAI:**
  - Supported devices include "iQOO 13, iQOO 15" and vivo X200/X300-series phones [32].
  - The Prompt API lists "Generate structured output (Alpha)" [32].
  - Gradle on Google Maven (latest listed) [33]:
    - `com.google.mlkit:genai-prompt:1.0.0-beta4`
    - `genai-schema:1.0.0-alpha1` and `genai-schema-compiler:1.0.0-alpha1` (structured output)
    - `genai-image-description:1.0.0-beta1`
    - `genai-speech-recognition:1.0.0-alpha1`
    - `genai-summarization:1.0.0-beta1`
  - NPU use and the language coverage of speech recognition (Hindi, Kannada) are not yet verified.
  - MediaPipe LLM Inference status, MLC LLM, and the llama.cpp Hexagon backend (GenieX wraps llama.cpp with HTP kernels [17]) were not re-checked in this pass.
- **SOLUTION.md claim check:**

  | Claim | Verdict |
  |---|---|
  | YOLO26n 10.7 ms NPU / 15.8 ms GPU / 52 ms CPU | **Correct** [1] |
  | Gemma 4 E2B ~52 tok/s on Android GPU | **Correct** (52.1, S26 Ultra) [27] |
  | Gemma 3n E2B ~28 tok/s on NPU | Not yet verified |
  | Llama 3.2 3B ~10 tok/s on 8 Elite via Genie | **Outdated.** AI Hub now reports 26.5 tok/s on the 8 Elite and 28.2 tok/s on the 8 Elite Gen 5 [25] |
  | sherpa-onnx offline Hindi TTS | **Correct** (Piper hi_IN voices) [24] |
- **Qualcomm Genie with Llama 3.2 3B, ~10 tok/s on 8 Elite** (SOLUTION.md): not yet verified.
- MediaPipe LLM Inference status, MLC LLM, llama.cpp Hexagon backend, and Gemini Nano via ML Kit GenAI on vivo/iQOO: not yet verified.

### 5. Vision-language on device
Google reports that FastVLM-0.5B on the Qualcomm NPU through LiteRT reaches a time to first token of 0.12 s, prefill above 11,000 tok/s and decode above 100 tok/s [6] (single source; test device not pinned down). GenieX also lists VLM support [16]. ML Kit adds `genai-image-description:1.0.0-beta1` [33], and the iQOO 15 is a supported device [32]. That makes it the easiest crash-photo caption path, at the same level of verification as the Prompt API. A crash-scene caption within a few seconds looks plausible (inference). Gemma 3n image input and SmolVLM: not yet verified.

### 6. Thermal and power
Not yet verified. Estimate (inference): at 10 fps, 10.7 ms per frame [1] keeps the NPU busy about 11% of the time. Cap detection at 10 fps, run the LLM only on events, and log `PowerManager` thermal status during a 20-minute soak test.

### 7. Qualcomm AI Hub workflow
The `qai-hub-apps` CLI fetches apps with models already placed [14]. Compile and profile jobs, free-tier limits and the availability of an 8 Elite Gen 5 device on AI Hub are not yet verified.

## AI bill of materials

| Task | Model | Size | Source | Licence | Runtime | Expected latency on this phone | NPU | Fallback |
|---|---|---|---|---|---|---|---|---|
| Road-user detection | YOLO26n W8A16 QNN context binary (`name=81`) | Not yet verified | Ultralytics export [1] | AGPL-3.0 (not yet verified) | ORT QNN EP 1.29.0 [10] | 10.7 ms @640 (Xiaomi 17, same SoC) [1] | Yes | LiteRT GPU 15.8 ms; CPU 52.2 ms [1] |
| Road-user detection (licence-safer) | YOLOX w8a8 TFLite | Not yet verified | AI Hub via `qai-hub-apps` [14] | Not yet verified | TFLite 2.17.0 + QNN delegate 2.50/2.51 [14][13] | Not yet verified | Yes | GPU delegate, then XNNPack [14] |
| Siren/horn | YAMNet | Not yet verified | MediaPipe | Not yet verified | MediaPipe Audio Classifier | Not yet verified | No | Skip |
| Alert speech (EN) | System TTS voice | n/a | Android | n/a | `TextToSpeech` | Not yet verified | No | Pre-recorded clips |
| Alert speech (HI) | Piper `vits-piper-hi_IN-*-medium` (int8 available) | Not yet verified | [24] | Not yet verified per voice | sherpa-onnx v1.13.8 (CPU) | Not yet verified | No | System hi-IN voice; pre-rendered clips |
| Alert speech (KN) | System kn-IN voice, or `facebook/mms-tts-kan` | Not yet verified | [29] | MMS: CC-BY-NC-4.0 [29] | Android TTS / VITS | Not yet verified | No | Pre-rendered clips (recommended) |
| ASR (EN) / wake-word keywords | `sherpa-onnx-streaming-zipformer-en-20M`; `kws-zipformer-gigaspeech-3.3M-mobile` | Not yet verified | [31] | Apache-2.0 (toolkit) [21] | sherpa-onnx | Not yet verified | No | Android `SpeechRecognizer` |
| SOS report, explanations, voice-report parsing (primary) | Gemma 4 E2B `.litertlm` | 2,583 MB file; 676 MB RAM on GPU | [27] | Apache-2.0 | LiteRT-LM v0.18.0 (GPU) | 52.1 tok/s decode, TTFT 0.3 s (S26 Ultra) [27] | No (GPU) | CPU 46.9 tok/s [27], else templates |
| Same tasks (NPU option) | Qwen3-4B w4a16 AI Hub bundle | Not yet verified | [26] | Apache-2.0 | Genie / GenieX-QAIRT [17][26] | 28.6 tok/s, TTFT 0.05–1.7 s (8 Elite Gen 5, Galaxy) [26] | Yes | Gemma 4 E2B on GPU |
| Hazard-report parsing and alert explanations (simplest) | Gemini Nano (system) | System-managed | [32][33] | Proprietary | ML Kit `genai-prompt` 1.0.0-beta4 plus `genai-schema` | Not yet verified | Not yet verified | Gemma 4 E2B on LiteRT-LM |
| Crash-scene caption | Gemini Nano image description, or FastVLM-0.5B | System / not yet verified | [33] / [6] | Proprietary / not yet verified | ML Kit `genai-image-description` / LiteRT NPU | Not yet verified / TTFT 0.12 s, >100 tok/s decode [6] | Not yet verified / Yes | Text-only report |

## Options matrix

| Option | Evidence | Feasibility in 48 h on iQOO/Snapdragon | Risk | Verdict |
|---|---|---|---|---|
| TFLite + QNN delegate + AI Hub w8a8 detector | Official Qualcomm camera sample, Maven deps [14][15] | High: a working app exists; Kotlin port is mostly delegate setup | v81 untested; vivo HTP access unknown | **Recommended** |
| ORT QNN EP + Ultralytics `_qnn.onnx` | 10.7 ms on 8 Elite Gen 5 [1]; Maven AAR [10] | Medium: ORT Java API is simple, but QAIRT version pairing is a risk | AGPL; context-binary version mismatch | **Fallback (NPU)** |
| LiteRT GPU delegate (YOLO TFLite) | 15.8 ms on 8 Elite Gen 5 [1] | High | Low | **Fallback (always on)** |
| LiteRT 2.3.0 CompiledModel NPU | SM8850 supported [4]; sample [9] | Low to medium: AAB, dynamic features, bundletool | 1-day-old release [7] | Stretch only |
| MediaPipe ObjectDetector (GPU) | Not yet verified | High | No NPU, so weaker for "real use of hardware" | Avoid unless all else fails |
| LiteRT-LM + Gemma 4 E2B (GPU) | 52.1 tok/s, TTFT 0.3 s [27]; Kotlin stable; function calling [23] | High | Uses the GPU, which detection also uses (contention untested) | **Recommended (LLM)** |
| Gemini Nano via ML Kit GenAI Prompt API | iQOO 15 is on the supported list; structured output is Alpha [32] | High if AICore and the model are present on the loaner ROM | Closed model; Alpha API; AICore download needs network once | **Recommended for parsing and explanations (verify day 1)** |
| GenieX / Genie + Qwen3-4B or Llama 3.2 3B (NPU) | 28.6 and 28.2 tok/s on 8 Elite Gen 5 [26][25]; SDK and sample [16][17] | Medium: Developer Preview SDK; Galaxy-targeted bundles | Bundle and SoC compatibility on the iQOO; preview API churn | **Fallback / NPU showcase** |

## What this means for RoadMesh

- **Perception thread:** CameraX analysis at 640 px and 10 fps, then the detector on HTP (QNN), then a tracker and TTC from box growth. A 10.7 ms inference [1] leaves plenty of slack, and the BLE and IMU threads are not affected.
- **Robustness (technical depth):** fall back at runtime from NPU to GPU to CPU. Show the active backend and latency on screen.
- **Licence:** pick the model on day 1. YOLO26n means an AGPL repo; YOLOX through AI Hub means a permissive repo (verify its licence).
- **LLM, three-way recommendation:**

  | Task | Model and runtime | Why |
  |---|---|---|
  | Parsing a spoken hazard report into a structured event; explaining an alert | Gemini Nano via ML Kit `genai-prompt` with `genai-schema` | iQOO 15 is on the device list; structured output [32][33] |
  | Drafting the crash incident report (open model) | Gemma 4 E2B on LiteRT-LM (GPU) | 52.1 tok/s, so a 200-token report takes about 4 s (estimate from [27]); function calling [23] |
  | NPU showcase (stretch) | Qwen3-4B on GenieX-QAIRT | 28.6 tok/s on Genie [26] |

  Always validate the JSON in code and fall back to templates. Load models on demand only.

## Test on the loaner phone on day 1

1. `adb shell getprop ro.soc.model`: expect SM8850 (inference: standard property on Android 12+).
2. Build AI Hub `object_detection_android` (`--model yolox`), install, and confirm the QNN/HTP delegate is active, plus the latency [14].
3. If step 2 fails on v81: ORT QNN EP with `yolo26n_qnn.onnx` (`name=81`) [1][10]. Then LiteRT GPU.
4. TTS: `isLanguageAvailable(Locale("hi","IN"))` and `Locale("kn","IN")`, and list `getVoices()` with `isNetworkConnectionRequired == false`.
5. ML Kit GenAI Prompt API (`genai-prompt:1.0.0-beta4`): check that the feature status is available and that the Gemini Nano download completes on the loaner ROM [32][33].
6. Run Gemma 4 E2B in LiteRT-LM (or the Google AI Edge Gallery app) and record time to first token and tok/s against the 52.1 tok/s reference [27].
7. Install the GenieX chat sample and try a Qwen3-4B "8 Elite Gen 5 For Galaxy" bundle. This tests whether Galaxy-targeted bundles run on the iQOO's SM8850 [16][26].
8. A 20-minute soak with camera, NPU and BLE running together, logging thermal status and fps.

## Sources

1. https://docs.ultralytics.com/integrations/qnn/: Ultralytics QNN export docs; Xiaomi 17 (8 Elite Gen 5) CPU/GPU/NPU table
2. https://github.com/ultralytics/ultralytics/blob/main/docs/en/integrations/qnn.md: source of the QNN doc (ultralytics 8.4.67, yolo-flutter-app 0.6.6 assets)
3. https://docs.ultralytics.com/de/integrations/qnn: localized revision with 11.3 ms end-to-end breakdown
4. https://developers.google.com/edge/litert/next/qualcomm: LiteRT Qualcomm AI Engine Direct; SM8850 supported; AOT and JIT
5. https://developers.google.com/edge/litert/next/npu: LiteRT NPU guide; API 31+; NPU-to-GPU fallback; Play for On-device AI
6. https://developers.googleblog.com/unlocking-peak-performance-on-qualcomm-npu-with-litert/: Nov 2025; 90 ops; 64/72 models; 8 Elite Gen 5 results; FastVLM
7. https://github.com/google-ai-edge/LiteRT/releases/tag/v2.3.0: LiteRT 2.3.0 notes (2026-10-07); new NPU Maven packages; Gemma4 12B mention
8. https://dl.google.com/android/maven2/com/google/ai/edge/litert/group-index.xml: Google Maven LiteRT artifacts and versions
9. https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/image_segmentation/kotlin_npu: LiteRT NPU Kotlin sample; QAIRT 2.50 libs; bundletool
10. https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android-qnn/: ORT QNN Android AAR (1.29.0, MIT, qnn-runtime 2.42.0)
11. https://github.com/microsoft/onnxruntime/releases: ORT v1.30.0 (2026-09-10)
12. https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/: Qualcomm QNN runtime AAR versions (2.51.0 latest)
13. https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-litert-delegate/: QNN LiteRT delegate AAR (2.51.0; AI Hub Model License)
14. https://github.com/qualcomm/ai-hub-apps/tree/main/apps/object_detection_android: AI Hub object detection sample (TFLite + QNN, Android 14+, YOLOX default)
15. https://github.com/qualcomm/ai-hub-apps/blob/main/apps/_shared/scripts/versions.env: pinned versions (TFLite 2.17.0, QNN 2.50.0, minSdk 31)
16. https://github.com/qualcomm/ai-hub-apps/tree/main/apps/geniex_chat_android: GenieX chat sample; NPU on 8 Elite / 8 Elite Gen 5
17. https://github.com/qualcomm/GenieX: GenieX SDK (BSD-3-Clause, v0.8.0, 2026-10-02)
18. https://github.com/google-ai-edge/LiteRT-LM/releases: LiteRT-LM v0.18.0 (2026-10-06)
19. https://github.com/k2-fsa/sherpa-onnx/releases: sherpa-onnx v1.13.8 (2026-09-10)
20. https://aihub.qualcomm.com/apps/object_detection_android: AI Hub app page (search summary only)
21. GitHub licence metadata (gh api): https://github.com/ultralytics/ultralytics (AGPL-3.0) · https://github.com/k2-fsa/sherpa-onnx (Apache-2.0) · https://github.com/google-ai-edge/LiteRT-LM (Apache-2.0)
22. https://github.com/Megvii-BaseDetection/YOLOX: YOLOX, Apache-2.0
23. https://github.com/google-ai-edge/LiteRT-LM: README; Kotlin stable; tool use; Gemma 4 E4B and Gemma 3n E2B `.litertlm` models
24. https://github.com/k2-fsa/sherpa-onnx/releases/tag/tts-models: sherpa-onnx TTS model assets (Hindi Piper voices; no Kannada)
25. https://huggingface.co/qualcomm/Llama-v3.2-3B-Instruct: AI Hub Genie and GenieX-QAIRT tok/s and TTFT on 8 Elite / 8 Elite Gen 5
26. https://huggingface.co/qualcomm/Qwen3-4B: AI Hub Genie tok/s and TTFT on 8 Elite Gen 5; QAIRT 2.45 bundles
27. https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm: Gemma 4 E2B LiteRT-LM benchmarks (S26 Ultra CPU/GPU, speculative decoding)
28. https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm: Gemma 4 E4B LiteRT-LM benchmarks
29. https://huggingface.co/facebook/mms-tts-kan (and /mms-tts-hin): MMS VITS TTS, CC-BY-NC-4.0 (HF API metadata)
30. https://www.apkmirror.com/apk/google-inc/google-text-to-speech-engine/: Google TTS changelogs (weak evidence on Indic offline voices)
31. https://github.com/k2-fsa/sherpa-onnx/releases/tag/asr-models and /tag/kws-models: sherpa-onnx ASR and KWS model assets
32. https://developers.google.com/ml-kit/genai: ML Kit GenAI device list (includes iQOO 13 and iQOO 15); Prompt API structured output (Alpha)
33. https://dl.google.com/android/maven2/com/google/mlkit/group-index.xml: ML Kit GenAI artifacts and versions
34. https://dl.google.com/android/maven2/com/google/mediapipe/group-index.xml: MediaPipe Tasks artifacts (tasks-vision 1.1.0, tasks-audio 1.1.0, tasks-genai 0.10.35)
