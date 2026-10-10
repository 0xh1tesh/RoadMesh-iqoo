# Driver drowsiness SOS + fog-road novelty: plan

Status: plan, 11 Oct 2026. Builds on commit `937b4a8` (yogeshwars-cys, `feat/dashcam-prototype`, not yet in `main`).

## 1. What already exists (937b4a8)

| Piece | File | State |
|---|---|---|
| Drowsiness rules: microsleep 1.2 s, PERCLOS 30 % over 30 s, unresponsive at 3 s / head drop / face lost after either | `core/.../perception/DriverWatch.kt` + `DriverWatchTest.kt` | Built, unit tested, thresholds untuned |
| Front camera + face detection (eyes open probability, head pose) | `app/.../perception/DriverMonitor.kt` (CameraX + **ML Kit**) | Built |
| Wake-up alarm (siren, vibration, speech, "I'm awake") | `DriverMonitor.kt`, `ui/DriverMonitorView.kt` | Built |
| `DRIVER_SOS` hazard (ETSI cause 93 "human problem"), relayed like other hazards | `core/.../mesh/Model.kt` | Built, sends **with** the alarm |

Gaps against our plan:
1. ML Kit is free and offline but **not open source**.
2. The driver camera **replaces** the dashcam view instead of running beside YOLO.
3. The SOS goes out at once; we want **alarm first, SOS only if the driver does not respond**.
4. No "driver recovered" cancel, no repeat while still impaired, and only cars *behind* hear it. An asleep driver drifting across the centre line is a danger to *oncoming* cars too.
5. The speech line says "Emergency". The storyboard rules allow only the Caution tier for unsigned evidence.

## 2. Feature: drowsiness → alarm → SOS over the mesh

### 2.1 Open-source face model
- **MediaPipe Face Landmarker** (`com.google.mediapipe:tasks-vision`, model `face_landmarker.task`, Apache 2.0). It gives 478 landmarks, 52 blendshapes (`eyeBlinkLeft/Right`, `jawOpen`) and a head transform matrix. It runs on the GPU delegate, so it does not compete with YOLO on the CPU.
- Map it onto the existing `FaceSample`:
  - eye open = `1 - eyeBlink`;
  - pitch and roll come from the transform matrix;
  - add an optional `yawn` value (`jawOpen` held at least 0.6 for at least 1.5 s), which DriverWatch counts as an early-drowsiness sign;
  - cross-check with eye aspect ratio from the eyelid landmarks.
- Keep `DriverWatch` and its tests unchanged. Put a small `FaceSource` interface in front so ML Kit stays available as a fallback.
- Add the model, licence and source to `ATTRIBUTION.md`.

### 2.2 Run beside YOLO (one phone on the windscreen)
- **Rear camera** feeds YOLO11n (road hazards); **front camera** faces the driver for the face model.
- Use the CameraX concurrent camera API (`ProcessCameraProvider.availableConcurrentCameraInfos`, CameraX 1.3+).
  - **Probe it on the iQOO 15 first.** Add one line to `DeviceProbe` that checks for front + back concurrent support.
- Budget:
  - YOLO: CPU (XNNPACK) at 10 Hz, 640×360.
  - Face: GPU at 10 to 15 Hz, 480×360.
  - Both: frame drop with `KEEP_ONLY_LATEST`.
  - Watch temperature with `PowerManager.getThermalHeadroom`, and halve the face rate when headroom is under 0.3.
- **Fallback** when concurrent capture is unsupported: the front camera stays live and YOLO runs on the recorded REPLAY clip, labelled as such. This is honest for a stage demo.

### 2.3 Escalation state machine (new, core, unit tested)
New file `core/.../perception/DriverEscalation.kt`. The phone stays quiet on the mesh until a response window passes.

```
AWAKE ──drowsy──▶ ALERTING(t0) ──driver responds──▶ AWAKE      (no SOS sent)
                        │
                        └─ no response in window ─▶ SOS_ACTIVE ──recovered/ack──▶ CLEARED ──▶ AWAKE
UNRESPONSIVE ──────────────▶ ALERTING (short window) ─▶ SOS_ACTIVE
```

- **Response window:** 6 s for drowsy, 2 s for unresponsive. Both are starting values to tune.
- **"Responded"** means any of the following. A response has to be deliberate; the eyes reopening for a moment does not count.
  - a tap anywhere on the full-screen alarm;
  - a volume-key press;
  - eyes open and head up for 1.5 s (DriverWatch `recoverS`).
- **While SOS_ACTIVE:**
  - re-broadcast every 2 s, using the same event id with a rising sequence number;
  - the local alarm keeps sounding.
- **On recovery:** send one `CLEAR` packet, so nearby phones stop warning straight away instead of waiting for expiry.
- **Rate limits:** at most one episode SOS per 60 s, plus the existing mesh rate limits.

### 2.4 Mesh semantics for `DRIVER_SOS`
- **Tier:** stays **Caution**. Spoken line: "Caution. A driver ahead may be drowsy. Keep your distance." Drop "Emergency".
- **Packet:** reuse the `DRIVER_SOS` envelope. Use a flag bit for `ACTIVE` versus `CLEAR`, and put the episode id in the sequence field.
  - The relay is unchanged: contention delay `200·(1−d/150)` ms, at most 1 relay in Stage and 2 in Road, duplicates dropped.
- **Relevance:** new rule just for this kind.
  - Speak if the sender is **ahead in my direction** (within 400 m, existing rule).
  - Also speak if it is **oncoming in the opposite lane** (headings differ by 135° or more, closing, within 300 m).
  - Stay silent if it is behind and moving away.
- **Expiry:** 10 s without a beacon means expired (shown as unknown, not as "safe").
- **Privacy:** no images or face data leave the phone. On the air there is only a state, a position, a vehicle type and a confidence.

### 2.5 Alarm on the dashboard phone
- `AudioAttributes.USAGE_ALARM` with a siren loop.
- Vibration in the existing pattern, a full-screen amber screen and a huge "I'm awake" target.
- Speech says "Wake up. Pull over safely." on the alarm stream.

### 2.6 Known limits (say these out loud to the jury)
- At night, a phone front camera without infrared loses the eyes, so the face is reported lost (never on its own a reason to escalate). Raising screen brightness on the face helps a little.
- Sunglasses make eye state unknown; head pose and yawn still work.
- The thresholds are literature starting values, not validated on real drives.

## 3. Novelty: why this network matters on foggy and cloudy roads

**The problem.**
- In dense fog, visibility falls to tens of metres.
- At 60 km/h, reaction (1.5 s, about 25 m) plus braking (about 20 m) needs about 45 m. At 80 km/h it needs about 70 m. That is more than the driver can see.
- Fog pile-ups are chains: one slow or stopped vehicle is hit by the next, and so on, because each driver discovers the problem only when it appears.
- *To cite: MoRTH "Road Accidents in India", fog/mist weather table, for the yearly count. Look up the exact figure before quoting it.*

**Why the mesh still works when cameras and eyes do not.**
- Bluetooth at 2.4 GHz is essentially unaffected by fog or cloud. Fog attenuation (ITU-R P.840) matters at millimetre-wave and optical bands, not at 2.4 GHz.
- So a 150 m hop, relayed once or twice, warns 150 to 300 m out, which is several times the visible distance.
- The driver monitor looks **inside the cabin**, so fog does not blind it. Night fog driving is exactly when fatigue peaks.

**What changes in fog mode.**
1. **Fog mode switch**, manual by default.
   - Optional auto-suggest from the dashcam: low image contrast plus a dark-channel haze estimate over 10 s.
   - Speed alone is never enough evidence.
2. **Presence beacons.** Each phone sends a low-rate PRESENCE packet (class 2 already exists in our protocol) with speed and heading, once a second.
   - A phone that sees a same-direction vehicle ahead closing at a time to contact under 6 s says "Caution. Slow vehicle ahead, 120 m."
   - This is longitudinal only. GPS cannot tell lanes apart, and we say so.
3. **Stationary vehicle beacon.**
   - A phone whose speed is near 0 on a road (a breakdown, or the end of a queue) keeps beaconing `ETSI 27 end of queue` while stopped.
   - Most fog pile-ups start here.
4. **Earlier, longer warnings.** The fog preset widens relevance from 400 m to 600 m, raises the time-to-contact threshold for braking from 2 s to 4 s, and repeats speech for stationary hazards.
5. **Drowsy SOS** (section 2) matters most here: a sleepy driver is invisible in fog until it is too late.

**Honest framing.**
- We do not claim crashes prevented.
- We show the **warning distance against the visibility distance** on the same road.
- The measurable target is how often the warning arrives before visual contact.

## 4. Build order

| Step | Work | Files | Done when |
|---|---|---|---|
| 0 | Bring `937b4a8` into main. It is based on `42cca2b`, and main has since moved, so expect conflicts in `Model.kt`, `DashcamObserver.kt`, `RoadMeshScreen.kt`. | merge or rebase | Build green, 43+ core tests pass |
| 1 | `DriverEscalation` state machine plus tests (window, ack, recover, beacon, clear) | `core/.../perception/DriverEscalation.kt`, test | Unit tests green |
| 2 | `DRIVER_SOS` ACTIVE/CLEAR flag, oncoming relevance, Caution wording, 10 s expiry | `Model.kt`, `MeshEngine.kt`, `Insight.kt` + tests | Tests for both relevance directions |
| 3 | MediaPipe Face Landmarker behind `FaceSource`; yawn signal into DriverWatch | `perception/FaceLandmarkerSource.kt`, `DriverMonitor.kt`, `app/build.gradle.kts`, `ATTRIBUTION.md` | 10+ fps on the iQOO 15, eye state matches on a recorded clip |
| 4 | Concurrent front and back cameras; REPLAY fallback | `DriverMonitor.kt`, `DashcamObserver.kt`, `diag/DeviceProbe.kt` | Probe result written to `docs/decisions/` |
| 5 | Alarm UX polish | `ui/DriverMonitorView.kt` | Tap, volume key and eyes-open all count as acknowledging |
| 6 | Fog mode: switch, presence beacons, stationary beacon, preset | `core/mesh`, `core/insight`, settings | Lab or replay shows warning distance > visibility |
| 7 | Film scene "Fog: warned beyond sight" (road with a fog layer, rings passing through) | `motion/manim/road_film.py` | Rendered |

## 5. Verification
- **Core tests:** DriverWatch (existing) and DriverEscalation (a scripted face stream gives exact SOS and CLEAR times); relevance for following, oncoming and receding senders.
- **Device:**
  - concurrent-camera probe;
  - face model fps and thermals over 10 minutes;
  - three phones in Stage: A gets drowsy (a team member closes their eyes and does not respond), so after the window B relays and C speaks. If A taps "I'm awake" within the window, nobody hears an SOS.
- **False alarm check:** 15 minutes of normal passenger driving with a log of alarms. Report the count; we have none measured yet.
- **Rules carried over from the storyboard:** no "brake now" screen; no NPU claim; no measured latency unless measured.

## 6. Status, 11 Oct 2026 (branch `feat/dashcam-prototype`)

| Step | State | Notes |
|---|---|---|
| 0 Merge main | Done (`775ea14`) | Four conflicts resolved. Core and app tests pass. |
| 1 DriverEscalation | Done | 12 tests. Also fixed `DriverWatch`: an awake driver now ends an episode while old closures still fill the PERCLOS window, and `acknowledge()` keeps closure timers so a driver who taps in their sleep is alarmed again on the next sample. |
| 2 Mesh semantics | Done | `DRIVER_SOS` is Caution, 10 s lifetime, spoken once per sender per 30 s, also relevant to an oncoming vehicle within 300 m (sender ahead and heading back at us). New `DRIVER_OK` (ETSI 93, private sub-cause 100, `HazardUse.CANCEL`) withdraws it from the road map. 10 mesh tests. |
| 3 MediaPipe Face Landmarker | Done in code, **not yet run on the phone** | `tasks-vision` 0.10.32 (same train as the co-pilot `tasks-genai`), `face_landmarker.task` bundled (3.76 MB), ML Kit kept as fallback behind `FaceSource`. Yawns are counted (jaw open 0.6 for 1.5 s) and shown, but do not raise an alarm. APK grew from 175 MB to 200 MB. |
| 4 Concurrent cameras | Probe added, **result not measured** | Setup check now reports "Front and back camera at once". The dual pipeline is not built: run the probe on the iQOO 15 first, then record the answer in `docs/decisions/`. |
| 5 Alarm UX | Done in code | Tap anywhere, a volume key, or looking up with eyes open all answer the alarm; countdown to the SOS is shown. Volume-key handling in a Compose dialog is untested on a device. |
| 6 Fog mode | Rules done, **app wiring not done** | `FogWatch` (core, 8 tests) turns peers' speed and distance into "slow or stopped vehicle ahead". At 70 km/h it warns at about 130 m against 50 m visibility. Still missing: the fog switch, a steady presence beacon, and speaking the warning. Visibility is assumed, not measured. |
| 7 Film scene | Not started | |

### Where the build differs from the plan above
- **No episode id on air.** Every beacon is a normal new message from the sender. Receivers group by sender, and `DRIVER_OK` removes that sender's SOS. This needed no wire change.
- **Head pitch comes from the landmarks** (forehead against chin depth), not the transformation matrix, so the sign is fixed in code rather than depending on a matrix convention. It is relative to a 2 s calibration, and its accuracy is unmeasured.
- **Eye open = 1 minus the eyeBlink blendshape.** The eye aspect ratio cross-check was not added.
- **Model card limit.** The Blendshape V2 model card says the model is not intended for life-critical decisions. We use it only to raise a Caution, never an urgent warning.
- **Cooldown.** At most one SOS episode per 60 s, except that an unresponsive driver always goes out.
- **Stopping the monitor mid-SOS does not send DRIVER_OK.** Whether the driver recovered is unknown, so the SOS lapses after 10 s.

### Still to do before claiming anything on stage
1. Run on the phone: fps, thermals over 10 minutes, eyes-closed and head-drop thresholds, night and sunglasses behaviour.
2. 15 minutes of normal driving with an alarm log to count false alarms.
3. Look up the MoRTH "Road Accidents in India" fog figure before quoting it.
