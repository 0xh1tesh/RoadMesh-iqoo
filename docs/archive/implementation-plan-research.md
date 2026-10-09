# Implementation plan: RoadMesh at the Finale
*ECC planner format, written by the integrator after the planner agent was stopped to save quota. Built on the council scope (10) and the architecture (11). No timings: steps are ordered by dependency, and the team plans its own hours. Design notes only: every line of code is written at the event.*

## Overview
A Kotlin app with one job on stage: a hazard seen or felt by one phone is spoken by the phones behind it, phone to phone, with no internet. The core chain ships first and is never cut. One signature feature (proxy V2X from the camera on the NPU) sits on top, with an on-device LLM co-pilot beside it that never touches the alert path. The venue booklet confirms that HackTracker logs inference calls, tokens and thermals, and that fully on-device builds are preferred. Stretch work is gated.

**RL** = Red-Light-friendly: can be done or tested on the phone alone, with the laptop reachable only through Office Kit. **RL ✗** needs Green Light (laptop).

## Requirements
- Alerts travel phone to phone over BLE extended advertising with up to 3 hops, airplane mode on.
- Received alerts are filtered for relevance (ahead, same direction, ≤400 m), checked for plausibility and given a trust tier, then spoken with distinct haptics.
- Hard brake is detected from the IMU and confirmed by a speed drop. Camera detections (pedestrian ahead, looming lead vehicle) are broadcast on behalf of road users without the app.
- Every number shown to the jury is measured on the phones: latency, PDR, hops, inference ms, battery.
- The app is tunable and debuggable on the phone (settings JSON, log, stats).

## Package layout
`com.jsons.roadmesh`: `core/{protocol,geo,mesh,arbiter,detect,sensor,config}` (pure Kotlin, JVM-testable), `radio/`, `sensing/`, `perception/`, `output/`, `audio/` (stretch), `service/`, `diag/`, `ui/`, `copilot/` (LLM, off the alert path). Plus `tools/viewer/` (stretch). Details in 11 §1.

## Phase 0: Setup and device truth
**0.1 Ask the organisers four questions.** What HackTracker counts as on-device AI and voice; whether a vendor sample-app pattern and pre-trained weights are allowed with attribution; the submission cut-off; whether Office Kit mirroring works without internet.
- Files: `docs/decisions.md`. Depends: none. Risk: low.
- Accept: answers written down. **RL ✓**

**0.2 Repo and skeleton.** A fresh repo; Kotlin, Compose, minSdk 31 (needed by the QNN delegate and the NPU APIs), target 36. Add `LICENSE` and `ATTRIBUTION.md` in the first commit. Record the detector licence decision: YOLOX via AI Hub (check the per-model licence), or YOLO26n, which makes the repo AGPL-3.0.
- Files: `settings.gradle.kts`, `app/build.gradle.kts`, `LICENSE`, `ATTRIBUTION.md`.
- Depends: 0.1. Risk: low.
- Accept: `./gradlew test assembleDebug` passes, and the APK is installed on all three loaners (moving it with Office Kit file transfer also counts for the 10%). **RL ✗**

**0.3 SetupCheck screen.** Probes:
- `isLeExtendedAdvertisingSupported`, 2M and Coded PHY, `getLeMaximumAdvertisingDataLength`, and the advertising-set count (create sets until `TOO_MANY_ADVERTISERS`);
- `FEATURE_WIFI_AWARE`;
- offline TTS voices for en, hi and kn;
- battery-optimisation status, and that location is on.

It also runs a permission wizard (BLUETOOTH_SCAN, ADVERTISE, CONNECT, fine location, camera, mic, notifications).
- Files: `radio/BleCaps.kt`, `ui/SetupCheck.kt`. Depends: 0.2. Risk: low.
- Accept: values shown on each loaner and exported as JSON. **RL ✓** (running it)

**0.4 NPU proof.** Build Qualcomm's AI Hub `object_detection_android` sample unchanged, run it on a loaner, and note the backend and ms. Also try the GPU rung: one city team reported OpenCL missing on the loaner.
- Depends: 0.2. Risk: medium.
- Accept: the result is recorded in `docs/decisions.md`. A failure here routes Phase 4 to GPU or CPU. **RL ✓** (running it)

**0.5 OriginOS settings, for our app only.** Autostart; battery "not optimized"; high background power; locked in recents. Never touch HackTracker.
- Depends: 0.2.
- Accept: every SetupCheck item green. **RL ✓**

*Demo-able slice:* the SetupCheck screen (real hardware probing) and the sample detector on the NPU, or a documented fallback.

## Phase 1: Protocol and radio spine (never cut)
**1.1 Protocol v2 codec.** The 27-byte core from 11 §3, plus CauseTable (ETSI codes, validity, templates).
- Files: `core/protocol/{Packet,PacketCodec,CauseTable}.kt`. Depends: 0.2. Risk: low.
- Accept: JVM tests for round trip on every class, golden vectors, and 10,000 random byte arrays that each decode to an error and never crash. **RL ✗**

**1.2 Geometry and mesh logic.** Haversine distance, bearing and along-track distance; dedup LRU (256 entries / 30 s); contention delay `T = 200·(1 − min(d,R)/R) + U(0,20)` ms; cancel after ≥C overheard relays; senders per second.
- Files: `core/geo/Geo.kt`, `core/mesh/{DedupCache,RelayPolicy,NeighbourTable}.kt`. Depends: 1.1. Risk: low.
- Accept: JVM tests (distances within 0.5% of reference pairs; delay at d = R is jitter only; cancel fires at C). **RL ✗**

**1.3 Advertiser and TxActor.** Sets A (alert), B (relay) and C (presence): non-connectable, non-scannable, interval 160 (100 ms), `TX_POWER_HIGH`. Await `onAdvertisingDataSet` with a 300 ms timeout. Error ladder from 11 §2; multiplex when fewer than 3 sets exist.
- Files: `radio/{Advertiser,TxActor}.kt`. Depends: 1.1, 0.3. Risk: medium.
- Accept: a second phone (or nRF Connect) sees company 0xFFFF and magic 0x52 updating; a forced TOO_MANY_ADVERTISERS falls back cleanly. **RL ✓** (testing)

**1.4 Scanner and ScanBudget.** Filter on the magic byte; `setLegacy(false)`; LOW_LATENCY, ALL_MATCHES, AGGRESSIVE, MAX_ADVERTISEMENT; reportDelay 0. Restart every 8 min, with at most 4 starts per 30 s. A watchdog restarts the scan after 5 s of silence while neighbours exist.
- Files: `radio/{Scanner,ScanBudget}.kt`. Depends: 1.3. Risk: medium.
- Accept: a 15-min two-phone run with the screen off has no reception gap over 2 s, and adverts per minute stay flat across minute 10. **RL ✓**

**1.5 MeshService and MeshActor.** FGS `connectedDevice|location`, partial wake lock, START_STICKY. Pipeline: decode ▸ allowlist ▸ stale ▸ verify ▸ dedup ▸ relay, with drop counters per reason.
- Files: `service/MeshService.kt`, `core/mesh/MeshActor.kt`. Depends: 1.2, 1.4. Risk: medium.
- Accept: the app swiped away still receives for 15 min with the screen off, and `ApplicationExitInfo` is logged after any restart. **RL ✓**

**1.6 Ping-pong, Stats v1 and Exporter.** Four-timestamp latency over 50 probes, PDR, and JSON export to Downloads.
- Files: `diag/{PingPong,Stats,DiagBus,Exporter}.kt`, `ui/Stats.kt`. Depends: 1.5. Risk: low.
- Accept: p50, p95 and loss on screen; the export opens on the laptop after an Office Kit transfer. **RL ✓**

**1.7 Neighbour allowlist and hop counter.** A disclosed staging control.
- Depends: 1.5.
- Accept: with an A↔B↔C allowlist, A's packet reaches C with hop 1. With the allowlist off, C hears A directly, and the screen shows it. **RL ✓**

**1.8 Signing.** Ed25519 through `java.security` (API 33+), one key per pseudonym epoch. The signature covers the core with TTL, hop and lastHop zeroed. Sender-to-key binding. Extended rung only.
- Files: `core/protocol/Envelope.kt`. Depends: 1.1. Risk: low.
- Accept: JVM tests (one flipped byte → rejected; relay changes → still verifies). **RL ✗** · *cut-able*

*Demo-able slice:* airplane mode on every phone; "send test alert" on A is spoken on C through B, and Stats shows the measured latency.

## Phase 2: Positions, relevance and alerts (never cut)
**2.1 PositionSource.** LiveGnss, TraceReplay and DemoTrack, with GNSS time when available. DemoTrack sets SYNTH_POS and shows a banner; switching source is a visible tap.
- Files: `sensing/{LiveGnss,TraceReplay,DemoTrack,GnssClock}.kt`. Depends: 1.5. Risk: low.
- Accept: each source drives fixes, and demo packets carry SYNTH_POS. **RL ✓**

**2.2 Arbiter.**
- Relevance: ahead ≤400 m, bearing ≤30°, heading ≤45°.
- Plausibility: origin within (hop+1)·Rmax; speed against displacement; stale packets.
- Trust tiers: Caution for one sender. Urgent needs a second distinct originator within 50 m and 5 s, or own-sensor agreement. Relayed copies never count.
- Files: `core/arbiter/*`. Depends: 1.2, 2.1. Risk: medium.
- Accept: table-driven JVM tests. On the phone, a "flip heading" button on Car B silences the alert. **RL ✗/✓**

**2.3 Announcer and Haptics.** Pre-warmed TTS with `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`; templates from CauseTable. English first; Hindi and Kannada if offline voices exist, otherwise clips recorded by the team. Earcon; one vibration pattern per alert kind; speak once per hazard per validity window.
- Files: `output/{Announcer,Haptics}.kt`. Depends: 2.2. Risk: low.
- Accept: tGen-to-speech logged for every alert; the SetupCheck test voice works offline. **RL ✓**

**2.4 Presence beacon (pocket preset).** Set C at 250/500/1000 ms, adapting with hysteresis. Driver-side corridor rule: v·4 s + 10 m.
- Depends: 1.3, 2.2. Risk: medium (screen off).
- Accept: a pocket phone with the screen off for 15 min gives PDR ≥90% at 10 m on a second phone, and the driver phone says "Pedestrian ahead, right" on the demo track. **RL ✓**

**2.5 Settings JSON and editor.** Presets for stage, road, pocket and fog; last-good copy and safe mode.
- Files: `core/config/*`, `ui/SettingsEditor.kt`. Depends: 0.2. Risk: low.
- Accept: changing the relay Tmax on the phone takes effect without a rebuild, and invalid JSON is rejected with its path. **RL ✓** (this is the Red Light tuning tool)

*Demo-able slice:* A "brakes" (button) and B, two hops back, says "Caution, braking ahead, 120 metres"; oncoming C stays silent; the pocket walker warns the approaching car.

## Phase 3: Hard-brake sensing
**3.1 SensorSource and LiveImu.** Request 100 Hz and log the real rate; average to 10 Hz; low-pass under 2 Hz; CsvReplay.
- Files: `core/sensor/*`, `sensing/LiveImu.kt`. Depends: 0.2.
- Accept: replaying a recorded CSV reproduces the detector output exactly (golden test). **RL ✓** (recording)

**3.2 HardBrakeDetector.**
- Horizontal deceleration, with a manual "top edge forward" mount mode.
- Tier 27/1: ≤ −4.4 m/s² for 300 ms. Tier 99/1: ≤ −7 m/s² for 500 ms above 20 km/h.
- A speed drop from PositionSource within 2 s is required; gyro spikes from handling are rejected.
- Depends: 3.1, 2.1. Risk: medium.
- Accept: JVM replays report recall on the 12 aggressive brakes in jair-jr and false alarms per hour on Mendeley VIIT normal trips. No speed drop means no alert. **RL ✗**

**3.3 Stage brake.** A DemoTrack scripted speed drop combined with a real deceleration on the phone (trolley push or firm stop).
- Depends: 3.2.
- Accept: in rehearsal, physical deceleration plus the scripted drop broadcasts EBA, while a shake without a drop does nothing. Show both. **RL ✓**

**3.4 Auto-alignment (optional).** Gravity plus a regression on the GNSS speed derivative, accepted at corr > 0.8. *Cut first.*

*Demo-able slice:* a real IMU event triggers the relayed, spoken alert.

## Phase 4: Proxy V2X signature
**4.1 FrameSource.** CameraX ImageAnalysis at 640 px with KEEP_ONLY_LATEST, plus ClipReplay with a "REPLAY INPUT" badge.
- Files: `perception/FrameSource.kt`. Depends: 0.2.
- Accept: both inputs feed the same pipeline, with fps shown. **RL ✓**

**4.2 DetectorLadder.**
- Rungs: QNN delegate (HTP) → GPU → XNNPack CPU, with a warm-up.
- Move down a rung on an init error or a warm-up p95 over 40 ms; halve the fps on thermal SEVERE.
- Overlay shows the backend and ms; a 500-inference benchmark is exported.
- Cap the QNN effort: if it stalls, ship on GPU or CPU (about 52 ms at 640 px still allows 10 fps; drop to 320 px if needed).
- Files: `perception/{DetectorLadder,Detector*}.kt`. Depends: 0.4, 4.1. Risk: high.
- Accept: the overlay shows the rung and ms on each loaner, and the benchmark JSON is exported. **RL ✗** (build) / **✓** (benchmark)

**4.3 Tracker, LoomingDetector and PedAheadRule.**
- IoU tracker; 1/TTC from the slope of ln(box size) over 0.5–1 s.
- Proxy EBA when 1/TTC jumps while own |Δv| < 1 m/s; local FCW at TTC < 2.5 s (not yet verified as a threshold, so tune it).
- PedAhead: a person in the own-lane corridor for ≥3 frames.
- Files: `perception/Tracker.kt`, `core/detect/{LoomingDetector,PedAheadRule}.kt`. Depends: 4.2. Risk: medium.
- Accept: JVM tests on synthetic box sequences with known TTC (within ±15%), and a walk toward a parked car logs a falling TTC. **RL ✗/✓**

**4.4 Proxy alerts.** The PROXY flag, p1 = distance ahead, p2 = TTC or confidence; one alert per track per validity window. A receiver upgrades to Urgent on corroboration.
- Depends: 4.3, 2.2.
- Accept: in rehearsal, the front phone sees a person behind a cardboard "bus" (live or clip), and the rear phone says "Pedestrian ahead, hidden", with PROXY in the log. **RL ✓**

*Demo-able slice:* the full see-through demo.

## Phase 4B: On-device co-pilot LLM (core, off the alert path)
It depends only on the event log (1.6), so one person can build it in parallel with Phases 3 and 4.

**4B.1 LLM ladder probe.** Run Qualcomm's GenieX chat sample on a loaner and record the model, time to first token, tokens per second and memory. If it fails, try LiteRT-LM (Gemma class) on CPU, because the GPU path may lack OpenCL on the loaner; then llama.cpp on CPU.
- Files: `docs/decisions.md`. Depends: 0.2. Risk: medium.
- Accept: one rung produces a 100-token answer on the phone in airplane mode, with its numbers recorded. **RL ✓** (running)

**4B.2 LlmEngine with a fallback ladder.** One interface. Rungs: GenieX NPU → LiteRT-LM → llama.cpp → templates. It loads on demand on its own dispatcher, unloads on thermal SEVERE or memory pressure, and is never called from the alert path.
- Files: `copilot/{LlmEngine,Rungs}.kt`. Depends: 4B.1. Risk: medium.
- Accept: alert latency p95 (ping-pong and tGen to speech) is unchanged while the LLM generates. **RL ✗**

**4B.3 Drive debrief (the guaranteed LLM use).** Build a compact prompt from the local event log (alerts sent, received and relayed; hard brakes; proxy detections). Generate a three-sentence debrief, spoken on request and exported as JSON for the laptop through Office Kit. The numbers come from code, not the model: the prompt carries the counts, and the output is checked against them.
- Files: `copilot/Debrief.kt`. Depends: 4B.2, 1.6. Risk: low.
- Accept: in airplane mode, a debrief that matches the logged counts is spoken and exported. **RL ✓**

**4B.4 Voice hazard report.**
1. On-device speech recognition: Android's on-device recogniser if it works offline, otherwise sherpa-onnx English.
2. The LLM fills a strict schema {kind, side, distanceM}.
3. Code validates it and sends a normal ALERT with the matching ETSI cause, with p2 marking it as a human report.

Invalid output is dropped, never spoken.
- Files: `copilot/VoiceReport.kt`. Depends: 4B.2, 2.2. Risk: medium-high.
- Accept: "pothole ahead on the left" on one phone becomes "Hazard reported ahead, left" on the phone behind, and ten malformed utterances produce no alert. **RL ✓**

**4B.5 "Why did you warn me?"** The LLM explains the last decision from the arbiter's decision log: tier, distance and corroboration.
- Files: `copilot/WhyExplainer.kt`. Depends: 4B.2, 2.2. Risk: low.
- Accept: the explanation names the right hazard, distance and tier for the last three alerts. **RL ✓**

*Demo-able slice:* after the see-through run, ask "how was that drive?" and the phone summarises what happened, offline.

## Phase 5: Stats, hardening and demo modes
**5.1 Stats v2.** Latency histogram (p50/p95), PDR against distance, hop histogram, inference ms per rung, battery (charge counter and current) and thermal state; one-tap export.
- Depends: 1.6, 4.2.
- Accept: the export opens on the laptop after an Office Kit transfer. **RL ✓**

**5.2 Watchdogs, safe mode and a 60-min soak.** Screen off, advertising and scanning; the dash phone runs the camera at low fps on a charger.
- Accept: no reception gap over 2 s, the soak JSON is exported, and battery and temperature are reported as measured. **RL ✓**

**5.3 Presets and rehearsal script.** Stage (allowlist, demo-track roles, clip replay), road (live GNSS) and pocket. Add a control experiment: a Faraday pouch gives no alert.
- Accept: 10/10 clean runs in the hall. **RL ✓**

**5.4 Field test and backup video.** Parking lot only. The driver never touches a phone; brakes from ≤20 km/h with a follower. Record a 60 to 90 s backup video.
- Accept: logs and video exported. **RL ✓**

*Demo-able slice:* the complete pitch demo, with measured numbers on screen.

## Phase 6: Gated stretch (only after 5.2 and 5.3 pass)
**6.1 Siren corridor.**
- MediaPipe Audio Classifier (YAMNet); take the max over the siren classes (316–319, 390), smoothed across frames.
- Broadcast EVA 95/1 ahead; the phone says "Ambulance behind, keep left".
- Accept: an Indian siren recording over traffic noise triggers it, and a traffic-only clip does not. Log both. **RL ✓**

**6.2 Fog preset.** Lower thresholds, longer validity and TTL, faster presence beacons. **RL ✓**

**6.3 Indic co-pilot output.** If a Sarvam- or Gemma-class model on the ladder handles Hindi and Kannada well, let the debrief and explanations answer in them. (WhyExplainer moved to 4B.5.) **RL ✓**

**6.4 Laptop viewer.** Offline HTML: timeline, PDR and latency charts, and a hex decode using the same golden vectors. **RL ✗**

## Testing strategy
- **Unit:** everything under `core/` in JVM tests (≥80% line coverage target): codec with golden vectors and fuzzing, geometry, relay policy, arbiter tables, detectors on CSV replays and synthetic box tracks.
- **Integration:** two-phone ping-pong and PDR runs; the allowlist hop test; a service-survival test.
- **End to end:** the rehearsal script (10/10) and the field test.
- **Soak:** 60 min with the screen off.

## Risks and mitigations
| Risk | Mitigation |
|---|---|
| No extended advertising on the loaner | Legacy rung: unsigned 27-byte core, trust tiers unchanged |
| OriginOS kills the service | SetupCheck settings, FGS, heartbeat log, `ApplicationExitInfo` |
| QNN fails on HTP v81 | GPU rung (OpenCL reportedly missing on one loaner), then CPU XNNPack at 640 or 320 px |
| Crowded 2.4 GHz hall | PDR measured in the hall; phone spacing; alert repeats; backup video |
| HackTracker does not register our inference | Ask in 0.1; keep camera, voice and NPU use real during testing |
| Stage false alarms from people walking | ROI corridor, N-of-M frames, ClipReplay |
| No GNSS indoors | DemoTrack with SYNTH_POS and a banner, said out loud |
| Clock skew makes packets look stale | GNSS time, θ per neighbour, `maxAgeMs` setting |
| The LLM runtime fails or is slow on the loaner | Ladder: GenieX NPU, LiteRT-LM, llama.cpp CPU, templates; load on demand |
| An app crash lands in HackTracker's crash logs | SupervisorJob everywhere, caught callbacks, a memory budget (unload the LLM under pressure), soak test |

## Cut lines
- **Never cut:** 1.1 to 1.7, 2.1 to 2.3, 2.5, 4B.1 to 4B.3, 5.1 and 5.3.
- **Cut in this order if a phase stalls:**
  1. all of Phase 6;
  2. 4B.5, then 4B.4 (keep the debrief);
  3. 3.4 auto-alignment;
  4. 1.8 signing (keep plausibility and tiers);
  5. Hindi and Kannada voices (English plus recorded clips);
  6. TraceReplay;
  7. the QNN rung (ship GPU or CPU);
  8. live camera (ClipReplay only, disclosed).

## Success criteria
- [ ] Airplane mode on all phones; an alert from A is spoken on C through B, and the hop count is shown.
- [ ] Measured one-hop p50/p95 latency and PDR are on screen and exported via Office Kit.
- [ ] A hard brake with a speed drop fires; a shake without a speed drop does not.
- [ ] See-through: a pedestrian seen only by the front phone is spoken on the rear phone, with the detector backend and ms on the overlay.
- [ ] The 60-min soak with the screen off shows no reception gap over 2 s.
- [ ] Rehearsal passes 10/10; the backup video is recorded.
- [ ] `ATTRIBUTION.md` lists every library, model and dataset with its licence.
- [ ] The on-device LLM produces a drive debrief in airplane mode, and alert latency p95 is unchanged while it runs.
- [ ] No app crash in the soak or the rehearsals, because crash and tamper logs are penalised.
