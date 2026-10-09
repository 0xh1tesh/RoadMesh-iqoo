# Device probe results: iQOO 15 loaner (9 Oct, measured on the phone)

Read through the in-app SetupCheck screen and the Qualcomm sample apps, installed by sideload. No adb.

## Device
| Item | Value |
|---|---|
| Model | vivo I2501 (iQOO 15), Android 16 (API 36), patch 2026-08-01 |
| Build | PD2505F_EX_A_16.0.24.1.W30 |
| SoC | QTI SM8850 (Snapdragon 8 Elite Gen 5) |
| OriginOS property | 7.0 |
| Wi-Fi on the phone | joined "iQOO" network |

## Radio (decides the transport)
| Probe | Result | Meaning |
|---|---|---|
| BLE extended advertising | **true** | Use the extended rung |
| BLE 2M PHY / Coded PHY | **true / true** | Both available for tests |
| BLE periodic advertising | true | not needed |
| Max advertising data length | **1650 bytes** | Plenty for the 123-byte signed envelope |
| Advertising sets at once | **10 (reached the test limit of 10, extended mode)** | No multiplexing needed |
| BLE multiple advertisement | true (reads false while Bluetooth is off) | |
| Offloaded filtering | true | scan filters run in the controller |
| **Wi-Fi Aware** | **false (no manager)** | **Fallback rung 3 does not exist on this phone** |
| Wi-Fi RTT | false | no Wi-Fi ranging |
| UWB | false | |
| Barometer | false | |
| GPS | true | |

Fallback ladder is now: extended advertising, then legacy advertising, then cloud (labelled degraded). Wi-Fi Aware is removed.

## Sensors
Accelerometer, gyroscope, linear acceleration and gravity all report max 200 Hz; rotation vector 100 Hz. Accelerometer range about 80 m/s².

## Permissions and settings
- All eight runtime permissions granted (Bluetooth scan, advertise, connect, fine location, camera, mic, notifications, nearby Wi-Fi).
- OriginOS "Allow background usage" is ON for RoadMesh. Android's `isIgnoringBatteryOptimizations` still reports false for our app, so use the OriginOS page as the truth and test a screen-off run.
- Bluetooth was OFF by default on this phone; it must be turned on before a probe or a demo.

## Voices (English only for now)
Default engine: Google TTS (`com.google.android.tts`). en-IN, en-US, hi-IN and kn-IN are all available offline. Hindi and Kannada are available later at no cost.

## Detector (Qualcomm AI Hub sample, YOLOX w8a8)
- The sample builds and runs; it detects objects from the phone camera live (bottle, laptop and others were boxed).
- The sample APK bundles the V81 NPU libraries (libQnnHtpV81Stub/Skel), the right ones for this SoC.
- The stock sample shows no timing or backend. A patched probe build (`qc-object-detection-sample-hud.apk`) prints the delegate set and ms per frame. Result: see section below once run.
- Package id `com.quicinc.objectdetection`; the model licence is the AI Hub per-model licence (check before bundling).

## LLM (GenieX demo app, v0.3.5 binding)
Qwen3-0.6B (GGUF), measured in the app:
| Processor | Time to first token | Prompt speed | Decode speed |
|---|---|---|---|
| CPU | 190 ms | 524 tok/s | **55 tok/s** |
| GPU (999 layers) | 93 ms | 1074 tok/s | **76 tok/s** |

GPU works on this loaner (an earlier city loaner reportedly lacked OpenCL). NPU models in the catalog: Qwen3-4B, Qwen3-4B-Instruct-2507, Qwen2.5-VL-7B-Instruct. NPU test: see below once run.

## Notes
- Office Kit transfer works over the same Wi-Fi; it dropped once with a network error and then succeeded. APKs of 100 to 170 MB took about 1.5 minutes.
- Office Kit syncs the clipboard between laptop and phone, so typed text sometimes pastes the previous clipboard.
- Play Protect shows "App scan recommended" when sideloading; choosing "Scan app" uploads the APK to Google. Fine for public samples; think twice for our own builds.
