# First-hour checklist (live log)

Status key: [x] done · [ ] open · [~] needs the loaner phones

## A. Laptop toolchain (lead's laptop, checked 9 Oct)
- [x] Android SDK at `<Android SDK path>`: platforms 34, 35, 36; build-tools 34.0.0, 36.0.0; cmdline-tools; NDK; cmake; adb 37.0.1
- [x] JDK 17 at `<JDK 17 path>` (the default `java` on PATH is JDK 8: do not use it)
- [ ] `ANDROID_HOME`, `JAVA_HOME` (17) and PATH not set. Per shell:
  ```powershell
  $env:ANDROID_HOME="<Android SDK path>"
  $env:JAVA_HOME="<JDK 17 path>"
  $env:Path="$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:ANDROID_HOME\cmdline-tools\latest\bin;$env:Path"
  ```
- [ ] No Android Studio and no `gradle` command on this laptop. Either install Android Studio, or install Gradle and generate the wrapper. Other teammates: check theirs.
- [ ] Each teammate: clone this repo, JDK 17, SDK 36, `adb devices` shows their loaner.
- [x] **Decision: no debugging on the loaners** (cannot enable USB or wireless debugging). So:
  - Develop, run and read logcat on our OWN phones (adb works there). Loaners only get builds that already ran clean on an own phone, because crashes on a loaner show up in HackTracker.
  - Install on loaners: build the APK, move it with Office Kit file transfer, open it from the phone's file manager. Needs "install unknown apps" for the file manager (not a debugging setting).
  - Everything adb would show lives in the app: SetupCheck (Build.*, BLE caps, voices), log ring, stats, JSON export via Office Kit, `ApplicationExitInfo`, settings editor on the phone.
- [x] **GATE 1 settled (9 Oct): sideloading is allowed.** Build APK on laptop, move via Office Kit file transfer, install from the file manager. **Confirmed on a loaner: the skeleton APK installs and runs.** Keep a last-known-good APK on every phone. Office Kit transfer failed once with a network error (venue Wi-Fi), then worked.

## B. Ask the organisers (the answer goes below each line)
1. Which Red/Green windows apply? (The booklet shows a boundary at 14:00 on Saturday; the site data said 13:00.)
2. What is the submission cut-off, and where is the repo submitted?
3. Are vendor sample-app patterns (Qualcomm AI Hub apps, BSD-3) and pre-trained weights allowed with attribution under "code written during the event"?
4. Is a car park or outdoor area inside the venue grounds for field tests? (Phones must not leave the venue.)
5. Does Office Kit mirroring work without internet? Is the venue Wi-Fi usable?
6. Where do the cloud AI credits get claimed, and which models are listed?
7. Will Qualcomm mentors be on the floor, and when is the mentor round?
8. How should teams install their own APKs on the loaners (unknown sources, an official channel)? Is Developer options available at all?

## C. Device probes (run on each loaner; record the numbers)
- [~] (read in-app, no adb) About phone: model, Android version, OriginOS version, Bluetooth version
- [~] BLE: extended advertising supported, 2M PHY, Coded PHY, max advertising data length, number of advertising sets (create until TOO_MANY_ADVERTISERS)
- [~] Wi-Fi Aware feature present
- [~] NPU: build Qualcomm AI Hub `object_detection_android` (YOLOX) unchanged. Record backend (QNN/HTP, GPU, CPU) and ms. Is OpenCL present for the GPU rung?
- [~] LLM: GenieX chat sample. Record model, time to first token, tokens/s, memory. If it fails: LiteRT-LM, then llama.cpp on CPU.
- [~] Offline TTS voices: en, hi-IN, kn-IN
- [~] OriginOS, our app only: autostart on, battery "not optimized", high background power, locked in recents. Then 15-minute screen-off scan with a second phone reading delivery.
- [~] Office Kit: mirror, file transfer, clipboard, remote control, each once and timed.

## D. Decisions to make now
- [ ] Detector licence: YOLOX via AI Hub (check the model's licence) or YOLO26n (AGPL-3.0, repo becomes AGPL-3.0). Then add `LICENSE`.
- [ ] LLM runtime rung (from section C).
- [ ] Freeze protocol v2 (docs/architecture.md section 3) before anyone writes a codec.
