# RoadMesh Finale dossier
*Team JSONs · iQOO Hackathon 2026 Grand Finale · Mobility track · compiled 2026-10-09*

This is the entry point. It summarises eight research reports, a standards note, a value model, an ECC council decision, an architecture and an implementation plan. Every claim points to a file in this folder, and each file lists its own primary sources. **No timings are planned here:** the team plans its own hours. **No code was written:** the rules require all code to be written at the event, so everything here is evidence and design.

## 0. What the venue booklet changed (9 Oct)
The printed playbook handed out at check-in (the venue booklet) settles the open questions:
- **HackTracker is documented.** It "sits on the device through the build, reads model outputs, and logs inference calls, tokens and thermals in real time". Tokens count, so an on-device LLM now belongs in the build, off the alert path. "All crash and tamper logs are detected and will be penalised": never touch HackTracker, and treat an app crash as a scoring risk.
- **The tip to win.** Build apps that run locally and on-device, "including the backend, using on-device (mobile phone) LLMs". "The highest on-device builds will be preferred for the Top Teams Pitches." RoadMesh has no server: the phone is the backend (event store, analytics and LLM debrief on the device), and the laptop only views exports through Office Kit.
- **Format.** 48 h at roughly 55/45 Red/Green. In Red Light, laptop use is monitored and allowed only through Office Kit on the phone. Three scored evaluation rounds feed a Top Teams pitch, with the demo on the iQOO. Timings may vary and are announced at the venue.
- **Device and credits.** iQOO 15: Snapdragon 8 Elite Gen 5, Q3 chip, 16 GB LPDDR5X Ultra, 14,000 mm² vapour chamber, 7,000 mAh, sold as running "quantised local LLMs at usable speed". Cloud AI credits (frontier models) are for coding help; on-device inference is free.
- **Unchanged.** Scoring weights 30/20/15/15/10/10; code written during the event; OSS with attribution; repos locked before the Top Teams pitches.

## 1. The answer
Build one chain that cannot fail on stage, one signature feature on top, and an on-device LLM co-pilot beside it that never touches the alert path.

**The chain:** a hazard felt or seen by one phone is spoken by the phones behind it, phone to phone over Bluetooth LE, with up to 3 hops and airplane mode on. Every latency, delivery and inference number is measured live.

**The signature: proxy V2X.** The dash phone's camera runs a detector on the Snapdragon NPU. It broadcasts on behalf of road users who have no app and no V2X hardware:
- "pedestrian ahead, hidden";
- "car ahead braking", detected from the looming of the lead vehicle.

That answers the cold-start question on stage, because one equipped phone already protects the cars behind it.

**The co-pilot: an on-device LLM beside the chain, never in it.** A quantised model on the phone (Qualcomm GenieX on the NPU first, CPU runtimes as fallback) does three jobs:
- turns the local event log into a spoken drive debrief;
- parses a spoken hazard report into a checked alert;
- answers "why did you warn me?".

The alerts themselves stay templated and fast. This follows the venue's on-device LLM tip without putting a language model on the safety path ([12](12-implementation-plan.md) Phase 4B).

Everything else is gated stretch or roadmap ([10](10-council-scope.md)). Pitch it honestly: the parts have precedent, and what is new is the integration with on-device perception ([05](05-novelty-prior-art.md)).

## 2. Why it is hard to reject

| Criterion (weight) | What RoadMesh builds and shows | Evidence |
|---|---|---|
| End product quality (30%) | The core chain on three iQOO 15s in airplane mode. A foreground service hardened for OriginOS. A rehearsal script that passes 10/10. "A well-built simple product beats a broken complex one" | [10](10-council-scope.md), [12](12-implementation-plan.md) |
| Novelty and impact (20%) | Proxy V2X for unequipped road users. A pocket beacon for riders and pedestrians, who are 69.1% of deaths. AIS-230 and ETSI semantics at byte level | [05](05-novelty-prior-art.md), [09](09-standards-alignment.md) |
| Creative phone use (15%, HackTracker) | Camera and NPU inference, spoken alerts with haptics, and an on-device LLM producing real tokens, all running for real during every test. HackTracker officially logs inference calls, tokens and thermals. Never touch HackTracker | venue booklet |
| Technical depth (15%) | NPU detector ladder with an on-screen backend and ms; BLE extended advertising with contention relay; signed 27-byte protocol; trust tiers; measured p50/p95 | [02](02-radio-mesh.md), [04](04-on-device-ai-stack.md), [11](11-architecture.md) |
| Office Kit usage (10%, HackTracker) | Mirroring for the demo; APKs, models and stats exports moved by file transfer; Red Light work driven through remote control. No developer API exists, so usage must be real | [07](07-validation-demo.md) |
| Demo and pitch (10%) | Airplane mode shown on the projector; a disclosed demo track and hop allowlist; see-through with a cardboard "bus"; a backup video | [07](07-validation-demo.md) |

## 3. Evidence that carries the pitch
1. **Who dies.** In 2024, 69.1% of India's 1,77,175 road deaths were outside cars: two-wheeler riders 46.2%, pedestrians 20.6%, cyclists 2.3% (MoRTH *Road Accidents in India 2024*) [05](05-novelty-prior-art.md).
2. **How they die.** "Hit from back" is the deadliest collision type, with 37,404 deaths (21.1%). Fog crashes killed 15,115: 44.5 deaths per 100 crashes against 36.3 overall [05](05-novelty-prior-art.md).
3. **Who the mandate leaves out.** The AIS-230 V2V draft makes C-V2X compulsory only on *new* L/M/N vehicles from 1 Oct 2028 (voluntary fitments must comply from 1 Oct 2027). It defines Emergency Brake, Forward Collision, Wrong-way and Emergency Vehicle alerts, and has no provision for phones, pedestrians or the existing fleet [05](05-novelty-prior-art.md), [09](09-standards-alignment.md).
4. **The fleet has no eyes.** ADAS was in 8.3% of new passenger vehicles in H1 2025, and 1.4% of entry-level petrol and diesel cars [05](05-novelty-prior-art.md).
5. **Why a broadcast beats brake lights.** Reaction to the brake lights ahead is about 1.25 s per driver, so the delay adds up down a queue. A broadcast is flat: about 30 m gained four cars back at 50 km/h. It gains nothing for the car directly behind [09b](09b-value-model.md).
6. **The standards anticipate our case.** ETSI already defines `postCrash / accidentWithECallTriggeredWithoutAccessToCellularNetwork`. The industry emergency-brake trigger is speed > 20 km/h and acceleration < −7 m/s² for at least 500 ms [09](09-standards-alignment.md).
7. **Why speed confirmation is mandatory.** In Google's study, a raw accelerometer threshold scored PR-AUC 0.005 and ROC-AUC 0.482, worse than chance. The "3.8×" figure is a PR-AUC ratio (0.833 vs 0.221), not "3.8× fewer false alarms" [03](03-sensing-perception.md).
8. **Why crash detection is roadmap.** 35 of 49 (71%) automatic crash calls in an Okayama City study were false positives [03](03-sensing-perception.md).
9. **The NPU is fast enough.** YOLO26n at 640 px runs in 10.7 ms on the NPU, 15.8 ms on the GPU and 52.2 ms on the CPU on a Snapdragon 8 Elite Gen 5 phone (Ultralytics benchmark) [04](04-on-device-ai-stack.md).
10. **Warnings work when trusted.** IIHS data: factory forward collision warning alone cut rear-end crash rates by 23% (from news coverage of the IIHS study). Tactile warnings gave the fastest reactions in a driving-simulator study [06](06-trust-safety-regulatory.md), [09b](09b-value-model.md).
11. **Phones have broadcast over BLE at national scale.** Exposure Notifications rotated identifiers in step with the Bluetooth address, a privacy pattern RoadMesh copies [06](06-trust-safety-regulatory.md).

## 4. Prior art, stated honestly
- **Precedents for each part:**
  - ZTE patent US11062604B2 (2017): brake or anomaly alerts to nearby terminals over Wi-Fi Direct or Bluetooth.
  - AT&T patent US8660480B2 (2009): out-of-range devices corroborate an emergency and relay it until one has coverage.
  - Ford (2022): pedestrians' phones send BLE alerts to nearby Ford cars.
  - Nexar (2016): cloud-based phone "V2V".
  - WiFiHonk (2014): phone-to-phone over Wi-Fi beacons.
  - Sources: [05](05-novelty-prior-art.md).
- **What is new in RoadMesh:**
  - On-device perception that speaks *for* unequipped road users (proxy V2X).
  - Any phone as receiver and relay, not a specific car.
  - Offline operation, with AIS-230 and ETSI semantics in a signed 27-byte packet.
  - Measured performance on stage.
  - Pitch it as an integration with new perception, not a first.
- **Commercial caution:** the ZTE patent is active to 2038 (US). Check for an Indian family before any commercial launch [05](05-novelty-prior-art.md).

## 5. Feasible options by component
| Component | Recommended | Fallback | Avoid | Evidence |
|---|---|---|---|---|
| Radio | `startAdvertisingSet`: extended, non-connectable, 100 ms, `TX_POWER_HIGH`. Filtered LOW_LATENCY scan with `setLegacy(false)`, restarted every 8 min | Legacy 27-byte unsigned, then Wi-Fi Aware, then cloud labelled "degraded" | Legacy `startAdvertising` (always scannable); Nearby Connections for alerts | [02](02-radio-mesh.md) |
| Relay | Contention delay `200·(1−d/R)+jitter` ms, cancel on overheard relays, dedup, TTL ≤3, 500 m | Dedup-only flooding | Unlimited TTL | [02](02-radio-mesh.md), [11](11-architecture.md) |
| Brake detection | Two tiers (−4.4 m/s² for 300 ms; −7 m/s² for 500 ms above 20 km/h), confirmed by a speed drop | Manual "top edge forward" mount | Raw accelerometer threshold | [03](03-sensing-perception.md), [09](09-standards-alignment.md) |
| Detector | Qualcomm AI Hub pattern: TFLite + QNN delegate (YOLOX w8a8) | ONNX Runtime QNN EP + YOLO26n (AGPL), then GPU (OpenCL reportedly missing on one loaner), then CPU | LiteRT 2.3.0's NPU path (released the day before research) | [04](04-on-device-ai-stack.md), [08](08-oss-bom.md) |
| Collision timing | 1/TTC from the slope of ln(box size) over 0.5 to 1 s | — | TTC from frame pairs (jitter) | [03](03-sensing-perception.md) |
| Speech | Android TTS with templates per kind and tier, plus haptics | Clips recorded by the team; sherpa-onnx | An LLM on the alert path | [04](04-on-device-ai-stack.md) |
| Positions | Disclosed DemoTrack on stage; live GNSS outside | TraceReplay; system mock location | Hidden simulation | [07](07-validation-demo.md) |
| Security | Ed25519 envelope (key + signature, 123 B), 2-byte time, trust tiers, plausibility checks | Unsigned legacy with tiers | RSSI as a hard reject; per-message Keystore signing | [06](06-trust-safety-regulatory.md), [11](11-architecture.md) |
| Privacy | Sender ID rotated with a fresh advertising set; positions rounded to about 1 m | — | Stable IDs; 1e-7° positions | [06](06-trust-safety-regulatory.md) |
| Local LLM (core, off the alert path) | Qualcomm GenieX on the NPU | LiteRT-LM (Gemma class) on CPU (the GPU may lack OpenCL), then llama.cpp on CPU, then templates | Cloud LLM in the product; any LLM on the alert path | [04](04-on-device-ai-stack.md) |
| Siren (stretch) | YAMNet siren classes, smoothed over frames | — | Single-frame triggers | [03](03-sensing-perception.md) |
| Laptop role | Office Kit mirroring and file transfer of exports; static viewer as stretch | — | A LAN or cloud dashboard as the product path | [10](10-council-scope.md) |
| Licences | `LICENSE` and `ATTRIBUTION.md` in the first commit; decide the detector licence at the start | — | Pasting code from GPL mesh apps | [08](08-oss-bom.md) |

## 6. Architecture and plan, in brief
- **Architecture** ([11](11-architecture.md)):
  - A pure-Kotlin `core/` that is JVM-testable.
  - Single-thread actors for mesh and TX.
  - A 27-byte protocol v2 with ETSI cause codes, an hour-based timestamp and relay-safe signatures.
  - A detector ladder: QNN, then GPU, then CPU.
  - One `PositionSource` (GNSS, trace, demo).
  - Settings as JSON for Red Light tuning.
- **Plan** ([12](12-implementation-plan.md)): seven phases in dependency order.
  - 0 setup and device truth;
  - 1 protocol and radio spine;
  - 2 positions, relevance and alerts;
  - 3 hard-brake sensing;
  - 4 the proxy V2X signature, and 4B the on-device co-pilot LLM;
  - 5 stats, hardening and demo modes;
  - 6 gated stretch.
  - Every step has an acceptance test and a Red-Light flag. The plan also lists a cut order and a never-cut set.

## 7. Demo and metrics
**Staging:**
- Airplane mode on the projector.
- The demo track and the A↔B↔C allowlist, both disclosed.
- See-through: the front phone on a stand facing a teammate behind a cardboard "bus", with clip replay as the backup.
- Brake: a trolley push plus the scripted speed drop.
- A Faraday-pouch control: no radio, no alert.
- The Office Kit mirror on the projector.
- A backup video [07](07-validation-demo.md).
- Never drop a loaner; any crash demo uses a team member's own phone onto a cushion.

| Metric | How it is measured | Proposed target |
|---|---|---|
| One-hop latency | Four-timestamp ping-pong, 50+ probes | p50 ≤ 300 ms, p95 ≤ 900 ms |
| Brake to speech | 240 fps video, 20 trials | ≤ 1.3 s |
| Delivery ratio | Sequence-numbered beacons, 60 s per distance | ≥ 90% at 10 m |
| Hop reach | Hop histogram | 3 hops |
| False alarms | Replays of normal driving | < 1 per hour |
| NPU vs CPU | 500 timed inferences per backend | Shown live |
| Soak | 60 min, screen off | No reception gap > 2 s |

## 8. Jury questions and answers
| Question | Answer | Evidence |
|---|---|---|
| Waze and Nexar already do this | They are cloud round-trips needing coverage and mostly manual reports. RoadMesh is offline, phone to phone, and perceives automatically for people who have no app | [05](05-novelty-prior-art.md) |
| AIS-230 will solve it from 2028 | Only for new vehicles; the existing fleet and pedestrians stay uncovered. RoadMesh speaks the same alert vocabulary, so it bridges to C-V2X | [05](05-novelty-prior-art.md), [09](09-standards-alignment.md) |
| Phone radios and GPS are too weak | We scope to awareness alerts, not lane-level control, and measure latency and delivery live on stage | [02](02-radio-mesh.md), [05](05-novelty-prior-art.md) |
| Can I fake a hard brake? | Signed packets with a timestamp; range and speed plausibility checks; one sender only earns a quiet caution, and urgent needs corroboration | [06](06-trust-safety-regulatory.md) |
| A key you made yourself proves nothing | Agreed. The signature gives integrity across hops; identity needs CA-issued pseudonym certificates, which is roadmap (the SCMS pattern) | [06](06-trust-safety-regulatory.md) |
| Can people track me? | No names and no stable IDs. IDs rotate with the Bluetooth address, as in Exposure Notifications. Positions are rounded to about 1 m. A determined eavesdropper can still link trajectories, which we say openly | [06](06-trust-safety-regulatory.md) |
| Phone use while driving is illegal | MV Act s.184 targets *handheld* use; a 2020 regulation allows a dashboard-fixed phone for navigation. RoadMesh is mounted, auto-starts, speaks, and locks touch while moving. Not legal advice | [06](06-trust-safety-regulatory.md) |
| False alarms will make people uninstall it | Trust tiers: loud alerts need corroboration, and everything else is a quiet cue | [06](06-trust-safety-regulatory.md) |
| Battery? | Advertising is cheap; scanning is the cost. We show the battery drain measured on the loaner during the soak | [06](06-trust-safety-regulatory.md), [07](07-validation-demo.md) |
| Nobody has the app (cold start) | Proxy V2X: one equipped dash phone protects the cars behind it from day one | [10](10-council-scope.md) |
| Is that number real? | Every number on the stats screen is measured on these phones, and the exports are on the laptop | [07](07-validation-demo.md) |
| Isn't this patented? | Parts are (ZTE, AT&T). We cite them and build the new perception layer | [05](05-novelty-prior-art.md) |

## 9. Corrections to earlier documents
- Pedestrians are **20.6%** of deaths, not "about 24%". The 2025 totals in RESEARCH.md are unverified [05](05-novelty-prior-art.md).
- Rephrase the **"3.8×"** line (see evidence point 7) [03](03-sensing-perception.md).
- The **"BT5 700–950 ms in a vehicle"** figure came from Raspberry Pi boards running Bluetooth Mesh, not phones [02](02-radio-mesh.md).
- The **iQOO 15 spec sheet lists neither NavIC nor UWB**, so drop both claims (event rules).
- The **laptop code freeze** is the end of the last Green window, not the third checkpoint (event rules).
- The **₹5–7k OBU cost** is not in the AIS-230 draft. Add the Oct 2027 voluntary date and G.S.R. 466(E) [05](05-novelty-prior-art.md).
- **Hard braking has two tiers.** 0.45 g is "hard"; emergency is −7 m/s² [09](09-standards-alignment.md).

## 10. First checks on the loaner phones
1. Bluetooth capabilities: extended advertising, 2M, Coded, max data length, advertising-set count. Also Wi-Fi Aware.
2. The QNN delegate on HTP v81 via the AI Hub sample; whether the GPU rung has OpenCL.
3. Offline TTS voices for en, hi and kn.
4. OriginOS exemptions for our app only, then a 15-minute screen-off scan with PDR read on a second phone.
5. Ping-pong latency and PDR in the hall.
6. Office Kit: mirroring without internet, and file transfer of an export.
7. The LLM ladder: run the GenieX chat sample on a loaner and record the model, time to first token, tokens per second and memory. If it fails, try LiteRT-LM, then llama.cpp on CPU.
8. Ask the organisers whether sample-app patterns and pre-trained weights are allowed with attribution, and the submission cut-off. HackTracker's counting is now documented in the booklet.

## 11. Still unverified
- Phone-to-phone BLE latency, and range through car glass.
- Wi-Fi Aware support on the iQOO.
- Hindi and Kannada offline voices.
- GenieX model speeds.
- YAMNet accuracy on Indian sirens.
- Battery draw on the iQOO.
- FCW timing standards (ISO 15623).
- DPDP gazette text and liability.
- HackTracker internals.
- Whether the AI Hub model's licence is permissive.

Each is either a first check above or is labelled in its report.

## Files
| File | What it holds |
|---|---|
| [02](02-radio-mesh.md) | Android BLE facts from AOSP source, radio parameters, fallback ladder |
| [03](03-sensing-perception.md) | Hard brake, crash, alignment, TTC, sirens: thresholds with papers |
| [04](04-on-device-ai-stack.md) | NPU paths, AI bill of materials, LLM and speech options |
| [05](05-novelty-prior-art.md) | Prior art, patents, MoRTH 2024 statistics, scored novelty candidates |
| [06](06-trust-safety-regulatory.md) | Spoofing, privacy, law, alert human factors, jury Q&A |
| [07](07-validation-demo.md) | Datasets, metrics, demo track, staging options, test plan |
| [08](08-oss-bom.md) | Open-source bill of materials and licences |
| [09](09-standards-alignment.md) | AIS-230 and ETSI DENM mapping, emergency-brake trigger |
| [09b](09b-value-model.md) | Reaction-time value model |
| [10](10-council-scope.md) | ECC council: scope decision |
| [11](11-architecture.md) | ECC architect: architecture, protocol v2, ADRs |
| [12](12-implementation-plan.md) | Dependency-ordered implementation plan |
| [PRD](../.claude/prds/roadmesh.prd.md) | ECC product requirements |
