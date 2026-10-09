# RoadMesh Android architecture
*ECC `architect` agent, 2026-10-09, from the council scope (10) plus 02, 09, packet.js and mesh.js. Integrator notes are marked ▸. Design notes only: all code is written at the event.*

## 1. Components and layout

```
ImuSource ──────────────► HardBrakeDetector ◄─ speed ─ PositionSource (Gnss|Trace|Demo)
FrameSource (CameraX|Clip) ► DetectorLadder (QNN▸GPU▸CPU) ► Tracker ► Looming, PedAhead
MicSource* ► SirenDetector*                      │ Hazard
                                                 ▼
                        AlertFactory, PresenceBeaconer ─┐
Scanner ► rxChannels ► MeshActor ► RelayScheduler ──────┴► TxActor ► Advertiser sets A alert | B relay | C presence
          (decode▸allowlist▸fresh▸verify▸dedup)
               └► NeighbourTable ► Arbiter (relevance▸plausibility▸tier) ► Announcer (TTS, haptics), UI
all ► DiagBus ► LogRing | Stats | Exporter ► Office Kit ► tools/viewer
SettingsStore (JSON) ► StateFlow<Settings> ► all                         * gated stretch
```

```
com.jsons.roadmesh            App + AppGraph (manual DI)
  core/       pure Kotlin + java.*, no android.* (a unit test greps imports)
    protocol/ Packet, PacketCodec, CauseTable, Envelope
    geo/      Geo (port of mesh.js)
    mesh/     DedupCache, RelayPolicy, NeighbourTable
    arbiter/  Arbiter, Plausibility, TrustPolicy
    detect/   HardBrakeDetector, LoomingDetector, PedAheadRule
    sensor/   SensorSource, CsvReplaySource, Clock
    config/   Settings, Presets
  radio/      BleCaps, RadioLink, TxActor, Advertiser, Scanner, ScanBudget
  sensing/    LiveImu, LiveGnss, GnssClock, TraceReplay, DemoTrack
  perception/ FrameSource, DetectorLadder, Detector (Qnn|Gpu|Cpu), Tracker
  output/     Announcer, Haptics    audio/ SirenDetector*
  service/    MeshService           diag/  DiagBus, Stats, PingPong, Exporter
  ui/         Drive, Stats, Log, SettingsEditor, SetupCheck
  copilot/    LlmEngine, Debrief, VoiceReport, WhyExplainer (▸ core since the venue booklet; reads logs only, never on the alert path)
tools/viewer/ index.html, viewer.js, packet2.js
```

The laptop viewer is offline. It loads the exported JSON and shows a timeline, PDR and latency, and a hex decode. It uses the same golden test vectors as the Kotlin codec tests.

## 2. Module contracts

Threads:
- **main**: Compose and TTS. Scan callbacks also land here; they only copy the data and `trySend`.
- **ble**: a HandlerThread for advertising callbacks.
- **mesh**: one thread that owns dedup, the neighbour table, relay timers and the Arbiter. No locks needed.
- **tx**: one thread that serialises advertiser calls.
- **imu**: a HandlerThread for sensor and location callbacks. Detectors run on Default.
- **perception**: one executor for the image analyzer and inference.
- **IO**: logs, export and settings.

Everything runs under MeshService's SupervisorJob, so a failing module logs and restarts alone. The alert path never touches IO.

| Module | Contract | Failure |
|---|---|---|
| SettingsStore | `settings: StateFlow<Settings>`; `apply(json): Result<Settings>` | rejects with the JSON path, keeps last good; 2 crashes within 60 s of a change → defaults |
| Clock | `nowMs()` from `SystemClock.currentGnssTimeClock()`, else wall clock | "time: network" badge |
| SensorSource, PositionSource | `samples(): Flow<ImuSample>` (100 Hz, unbatched); `fixes: StateFlow<Fix?>` (lat, lon, speed, heading?, accuracy, synthetic) | no fix for 10 s → null + banner; never switches to synthetic silently |
| HardBrakeDetector | `onImu(s)`, `onFix(f): Hazard?`; two tiers, on horizontal \|a\|. Emits only after a speed drop confirms. The drop also gives the sign, so cornering never fires | no speed → counted, never sent |
| Perception | `DetectorLadder.open(): Detector`; `detect(frame): List<Box>`; `onTracks(tracks, ownSpeed, t): List<Hazard>` | init error or warm-up p95 > 40 ms → next rung; thermal SEVERE → half fps; all rungs fail → camera off + banner |
| AlertFactory, PresenceBeaconer | `build(h, fix): Packet` (seq, TTL, validity from CauseTable); beacons every 250/500/1000 ms by neighbour count, 10 s hysteresis | no fix → not sent |
| MeshActor, RelayScheduler | `submit(raw)`; `accepted: SharedFlow<RxPacket>`; order: decode ▸ allowlist ▸ stale ▸ signature ▸ dedup ▸ relay wait. Dedup stores only validated packets, so a forged copy can't suppress the real one | drops counted by reason; alerts and presence use separate DROP_OLDEST channels, `select` prefers alerts |
| TxActor | `RadioLink { rx: Flow<RawAdv>; suspend send(bytes, kind) }`; priority alert > pong > relay > presence; awaits each `onAdvertisingDataSet` (300 ms timeout) | TOO_MANY_ADVERTISERS → one multiplexed set; DATA_TOO_LARGE → unsigned core; FEATURE_UNSUPPORTED → legacy; INTERNAL_ERROR ×3 → next rung down |
| Scanner | LOW_LATENCY, ALL_MATCHES, AGGRESSIVE, `setLegacy(false)`, magic-byte filter; restart every 8 min through ScanBudget (max 4 starts per 30 s) | no results for 5 s while neighbours were present → restart within budget |
| Arbiter | `judge(p, me: Fix?, own): Decision` (Ignore(reason) \| Caution \| Urgent) | no own fix → RSSI > −75 dBm gives Caution "hazard nearby" |
| Announcer | `announce(d)`; one template per kind and tier; Urgent = earcon + QUEUE_FLUSH; speaks once per hazard per validity window | TTS fails → earcon, haptics, full-screen card |
| Diag | `log(e)` into a 5,000-entry ring; `export(): Uri` to MediaStore Downloads | Sharesheet |
| MeshService | FGS `connectedDevice\|location` (+camera in dash mode), partial wake lock, START_STICKY | restarts and logs `ApplicationExitInfo` |

## 3. Protocol v2: the 27-byte core

The core goes in manufacturer data under company ID 0xFFFF (the Bluetooth SIG test ID; a shipped product needs its own). The advertising data is 4 + 27 = 31 B exactly, so it carries no name, TX-power field or flags. The scan filter matches only the magic byte; the decoder checks the version.

| Byte | Field | Encoding |
|---|---|---|
| 0 | magic | 0x52 |
| 1 | version, class | 4 + 4 bits: version 2; class 1 ALERT (DENM), 2 PRESENCE (CAM/VAM), 3 PING, 4 PONG |
| 2–4 | senderId | u24; rotates with a fresh advertising set and key pair (~10 min; never while an alert is live) |
| 5–6 | seq | u16; msgKey = senderId‖seq replaces v1's u32 msgId, saves 2 B and gives PDR |
| 7 | TTL, hop, flags | 2 + 2 + 4 bits; flags PROXY, SYNTH_POS, NO_HEADING, SIGNED |
| 8–9 | tGen | 0.1 s since the top of the UTC hour (0–35,999) |
| 10–17 | lat, lon | i32 at 1e-7°, rounded to 1e-5° (~1 m) |
| 18–19 | speed | 0.01 m/s (ETSI units); 0xFFFF = unknown |
| 20 | heading | 360/256° steps |
| 21–22 | cause, subCause (ALERT); stationType, vruState (PRESENCE) | ETSI codes |
| 23–24 | p1, p2 | per cause: distance ahead m, peak deceleration 0.1 m/s², TTC 0.1 s, confidence % |
| 25 | lastHop | stage node number, else low byte of senderId |
| 26 | validity | seconds (ETSI validityDuration) |

- **PING and PONG** reuse bytes 8–24 for the target senderId‖seq plus t1, t2, t3 (u32 ms). That is exactly 17 B.
- **One deviation from the brief:** tGen counts within the hour, not within the minute. It costs the same 2 B, and a packet replayed 60 s later no longer looks fresh.
- **Formats rejected:** CBOR/protobuf (variable size) and UPER DENM (too large).

**ETSI mapping.** `CauseTable` is the single source of truth. It maps each AlertKind to its cause, subCause, validity, radius and TTS template:
- Hard brake: 27/1.
- EBA: 99/1, valid 2 s, relevant within 500 m.
- PED_AHEAD: 97/4 with the PROXY flag.
- Lead-vehicle braking seen by camera: 99/1 with PROXY.
- EVA (siren): 95/1.
- Local FCW: 97/1, never broadcast.

PRESENCE carries the ETSI StationType (pedestrian 1, cyclist 2, motorcycle 4, car 5). A gateway can then emit a VAM or a DENM 12/x without reinterpreting anything.

**Extended envelope (123 B).** Core ‖ Ed25519 public key (32 B) ‖ signature (64 B).
- The signature covers "RMv2" ‖ core with TTL, hop and lastHop zeroed, so relays don't break it.
- One key pair per pseudonym epoch. The first packet seen binds senderId to its key; a later mismatch is dropped.
- This gives integrity across hops and stops anyone impersonating a live pseudonym. It does not prove identity; certificates are roadmap.
- On the legacy rung, packets are unsigned and the trust tiers are unchanged.

## 4. Data flows

**(a) Hard brake → relay → speak**
1. IMU at 100 Hz (live or CsvReplay) → candidate.
2. The next fix must show a speed drop → Hazard. Otherwise the candidate is dropped and counted. At 1 Hz GNSS this adds up to 1 s; measure it on day 1.
3. ALERT 99/1 or 27/1, TTL 3, p1 = peak deceleration. The braking driver gets only a haptic tick.
4. Set A: `setAdvertisingData` → callback → ~10 events → stop.
5. Receiver: the scan callback copies bytes, RSSI and `timestampNanos`, then `trySend`.
6. MeshActor runs its pipeline; NeighbourTable records senders per second and PDR.
7. If TTL > 0, within 500 m and not behind: wait T = 200·(1 − min(d,R)/R) + U(0,20) ms.
   - Cancel if ≥ C relayers are heard during the wait.
   - Otherwise set B sends with TTL−1, hop+1, lastHop = me, using `enableAdvertising(true, 0, 3)`.
   - Three hops use about 1 s of the 2 s age budget.
8. Arbiter: relevance (ahead ≤ 400 m, bearing ≤ 30°, heading difference ≤ 45°) ▸ plausibility ▸ tier.
9. Caution speaks "Caution, braking ahead, 150 metres". Urgent plays an earcon, says "Brake!" and gives a strong haptic. The time from tGen to speech is logged.

**(b) Camera → proxy alert**
1. Input is live CameraX by default (KEEP_ONLY_LATEST, ~15 fps). ClipReplay is the disclosed, repeatable stage input.
2. Detector → IoU tracker.
3. PedAhead: a person in the own-lane corridor for ≥ 3 frames; distance from the pinhole model.
4. Looming: the slope of ln(box size) over 0.5–1 s is 1/TTC. A proxy EBA fires when 1/TTC jumps while own speed is steady (|Δv| < 1 m/s). A local FCW fires at TTC < 2.5 s.
5. ALERT with PROXY at own position. p1 = distance ahead, so receivers can project the hazard point; p2 = TTC or confidence. One alert per track per validity window.
6. TX as in (a). A receiver upgrades it to Urgent when its own camera or a VRU beacon at that spot agrees.

**(c) Presence → warning**
1. Pocket phone, screen off: PRESENCE on set C, TTL 0, data and seq refreshed at 1 Hz.
2. Driver phone: NeighbourTable keeps a track of the VRU.
3. A VRU in the corridor within v·4 s + 10 m → Caution "Pedestrian ahead, right". Urgent if own camera confirms or TTC < 2 s.

**(d) Ping-pong**
1. A sends PING with t1 (wall clock, ms). TTL 0–2 probes 1–3 hops.
2. B maps `timestampNanos` to wall time as t2 and replies at once with PONG(t1, t2, t3).
3. A takes t4 the same way. δ = (t4−t1) − (t3−t2), θ = ((t2−t1) + (t3−t4))/2, one-way latency ≈ δ/2.
4. Run 50 probes at 1 Hz. Report p50, p95 and loss (2 s timeout), plus θ per neighbour, which reveals clock skew. Export as JSON.

## 5. ADRs

**ADR-1 Radio.** Non-connectable extended advertising sets at 100 ms, TX HIGH, 1M PHY. Use 2M or Coded PHY only if every phone's probe passes.
- Alternatives: GATT (slow setup, connection limits), Nearby Connections (needs connections).
- Fallback ladder:
  - extended → legacy if extended is unsupported, max data < 127 B, or PDR at 10 m < 90% while legacy does better;
  - → Wi-Fi Aware if p95 > 1 s or PDR < 80%;
  - → cloud relay, flagged "degraded".
- Fewer than 3 advertising sets → multiplex.

**ADR-2 Detector.** AI Hub pattern: TFLite with the QNN delegate (YOLOX w8a8), then GPU, then XNNPack.
- ▸ Licence: upstream YOLOX is reported as Apache-2.0, but AI Hub publishes per-model licences. Confirm the packaged model's licence on day 1.
- ▸ One Chennai team's iQOO 15 log reports OpenCL missing for the LiteRT GPU path (single source). The CPU rung must be real: about 52 ms at 640 px for YOLO26n, which still allows 10 fps, or drop to 320 px.
- Alternative: ONNX Runtime QNN EP with YOLO26n (AGPL), swappable behind `Detector`.
- Trigger: init exception, warm-up p95 > 40 ms, or thermal SEVERE.

**ADR-3 Position.** One `PositionSource` interface:
- LiveGnss.
- TraceReplay: a drive recorded on day 1.
- DemoTrack: SYNTH_POS flag plus a banner; its scripted brake supplies the speed drop.
- Alternative: Android mock location (hidden from the audience).
- Trigger: no fix or accuracy > 20 m for 10 s → banner. Switching source is a deliberate, visible tap.
- The demo layout must keep each hop shorter than R, or plausibility will reject it.

**ADR-4 Trust.** One plausible sender → Caution. A second distinct originator within 50 m and 5 s, or own sensor → Urgent. Relayed copies never count as a second sender.
- Implausible → logged only. That means origin > (hop+1)·Rmax, displacement/Δt > speed + 5 m/s, or stale.
- Alternatives: signature as trust (no PKI), always Urgent (alert fatigue).
- Trigger: false alarms in rehearsal → raise thresholds. The stage script includes one corroborated pair.

**ADR-5 Settings as JSON.** One kotlinx.serialization `Settings` with an on-phone editor, presets (stage, road, pocket, fog), last-good copy and safe mode.
- Diagnostics: Log, Stats, BleCaps and a self-test (codec vectors, detector benchmark, test voice).
- Alternatives: preference screens (one per knob, slow to build), rebuilding for each tweak (impossible in Red Light).
- Trigger: invalid JSON → rejected; crash loop → defaults.

## 6. Red flags

| Risk | Guard |
|---|---|
| OriginOS kills or freezes the service | FGS started while the app is visible; wake lock. SetupCheck verifies battery exemption, autostart, background power and recents lock. Log shows heartbeat gaps and `ApplicationExitInfo` |
| Silent scan throttling or the 10-min downgrade | ScanBudget, 8-min restart, watchdog; the UI never calls `startScan` directly |
| Scanning stops with the screen off | filter always on; 15-min screen-off pocket test on day 1, with PDR read on a second phone |
| `neverForLocation` filters beacon-like adverts | don't declare it; ask for fine location; SetupCheck confirms location is on |
| Clock skew makes every packet stale | GNSS time; θ per neighbour; `maxAgeMs` setting |
| Whole stage is in one radio cell | disclosed allowlist on lastHop; hop count shown on screen |
| 2.4 GHz congestion in the hall | measure PDR in the hall on day 1; repeats; fallback ladder |
| Slow first NPU inference, heat | warm-up at launch; thermal listener; clip replay |
| TTS latency, missing offline voice, Do Not Disturb | pre-warm; `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`; SetupCheck test voice |
| Android 16 permission prompts | first-run wizard; SetupCheck |
| Office Kit can't see the export | MediaStore Downloads; Sharesheet |
