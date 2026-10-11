# RoadMesh validation, metrics and demo proof (card A7)
*Researched 2026-10-08/09 · Sources: 31 · Confidence: Medium (API and dataset facts High; venue RF and Office Kit behaviour untested)*

## Bottom line
- **On stage, positions come from an in-app "demo track", not GNSS:** a `PositionSource` with live GNSS, replay of a recorded drive, or a scripted virtual road, under a permanent "SIMULATED POSITION" badge. Radio, IMU, camera and speech stay live. System mock location is only the fallback.
- **Measure latency without clock sync:** a four-timestamp ping-pong (the NTP formula) for radio latency, and a 240 fps slow-motion video of sender and receiver screens for the end-to-end number the jury sees.
- **Multi-hop on a small stage: impose the topology in software** (neighbour allowlist A↔B↔C), show the live hop counter, and say so out loud. Low TX power plus an RSSI gate is the "real radio" variant only if it survives rehearsal in the hall.
- **Assume a hostile 2.4 GHz hall.** Rehearse in the venue on Friday night, keep phones within a few metres, and keep the outdoor field-test video ready.
- **Validate detectors on public data first:** jair-jr (12 labelled aggressive brakes, Android), Mendeley VIIT (Indian cars, CC BY 4.0), UAH-DriveSet normal trips for false alarms per hour (non-commercial licence), RDD2022 India for pothole images (CC BY-SA 4.0).
- **One "robustness" screen earns the technical-depth marks:** tests passed, relay-simulation chart, replay precision/recall and false alarms per hour, latency p50/p95, PDR vs distance, a 60-minute soak chart.
- **Never drop the loaner.** Do the crash demo with a team member's own phone onto a cushion.

## Findings

### 1. Datasets and trace replay
| Dataset | Content | Useful labels | Size | Licence | Use |
|---|---|---|---|---|---|
| jair-jr driverBehaviorDataset [3] | Android (Motorola XT1058), accel/gyro/magnetometer at 50–200 Hz, 4 trips of ~13 min | 69 events incl. **12 aggressive brakes**, 14 non-aggressive; start/end times in `groundTruth.csv` | not stated | **none stated**: evaluate locally, do not redistribute | brake recall and detection latency |
| UAH-DriveSet [1] | >500 min, 6 drivers and vehicles, normal/drowsy/aggressive, motorway and secondary road, DriveSafe iPhone app, video | trip-level behaviour | not stated | non-commercial, no redistribution; trained models allowed [1]; reader tool CC BY-NC 4.0 [2] | false alarms per hour on normal trips |
| Mendeley, VIIT Pune [4] | Ford Figo, Swift, Nexon; Redmi 4 and Mi A3; accel + gyro at 50 Hz | trip-level normal/aggressive/risky | 23.8 MB | CC BY 4.0 | sanity-check thresholds on Indian cars |
| Mendeley, IIT Kharagpur buses [5] | Kolkata, Bhubaneswar, Durgapur; accel, gyro, magnetometer, GPS, sound | speed breakers, turns, bus stops, potholes | not stated | CC BY 4.0 | pothole/bump IMU check (authors warn it is noisy) |
| RDD2022 India [6][7] | part of 47,420 images from 6 countries; India shot with vehicle-mounted phones | potholes + 3 crack types, Pascal VOC XML | India zip 502.3 MB | CC BY-SA 4.0 | pothole detector sanity check |

**Skip for now:** IDD Detection (46,588 images; licence not found publicly) [8]; DriveIndia (66,986 images, 24 classes, YOLO mAP50 78.7%, Basler cameras rather than phones; signed EULA required) [9][10]; iWatchRoad (7,000+ dashcam pothole frames; dataset licence unverified) [11].

**Replay (recommendation):** a `SensorSource` (`Live`, `CsvReplay`) re-emits rows at recorded timestamps, so one detector runs in JVM tests and on the phone; resample 50–200 Hz data to the app's rate. Datasets are data, not code, so the build-at-the-event rule should not cover them (inference: confirm with organisers).

### 2. Latency and delivery without clock sync
- **Four-timestamp ping-pong.** A sends PING at T1 (A's clock). B receives at T2 and replies at T3 with T2 and T3. A receives at T4. Delay δ = (T4−T1) − (T3−T2), offset θ = ((T2−T1) + (T3−T4))/2 [16]. B's turnaround drops out. One-way ≈ δ/2 assumes symmetric paths, so report p50/p95 over ≥100 pings and label it an estimate.
- **θ puts every phone's log on A's timeline**, so later brake alerts and relays get one-way latency per hop. Recompute θ each run because clocks drift.
- Timestamp with `SystemClock.elapsedRealtimeNanos`. `ScanResult.getTimestampNanos()` gives the time the advert was observed in the same time base [17].
- **Slow-motion video for the jury:** film the sender's trigger flash and the receiver's alert in one 240 fps shot (1 frame ≈ 4.2 ms). It covers detection, radio and speech start. Run 20 trials.
- **PDR:** sequence-numbered beacons at a fixed rate, 60 s per distance point; PDR = unique received ÷ sent; log RSSI too.
- **Battery and NPU:** log `BATTERY_PROPERTY_CHARGE_COUNTER` (µAh) and `CURRENT_NOW` (µA) each minute [18]. Time 500 inferences per backend with `System.nanoTime`; Ultralytics reports YOLO26n at 10.7 ms NPU, 15.8 ms GPU, 52 ms CPU [20] (via SOLUTION.md, not re-verified).

### 3. Indoor GNSS and an honest demo track
- **Indoors, GNSS cannot be trusted.** Search snippets report indoor attenuation of 100–1,000× and a time to first fix over 300 s (or none) indoors versus under 60 s outdoors [24][25] (not deep-read; which source says which is unverified).
- **System mock location works but is invasive.** `FusedLocationProviderClient.setMockMode(true)` plus `setMockLocation()` makes the fused provider report only injected fixes, flagged by `isMock()`. Timestamps must increase monotonically, mock mode affects every app on the phone, and Android M+ requires `ACCESS_MOCK_LOCATION` plus selecting the app as mock location app in Developer options [22]. `LocationManager.addTestProvider` is the platform-level equivalent [23]. On a loaner that means Developer options, a manual setting that can be reset, and a phone-wide side effect.
- **Recommendation:** in-app `PositionSource` with `Gnss`, `TraceReplay` (this morning's field drive) and `DemoTrack` (role picker: Car A at 0 m, Car B at −60 m, Car C at −120 m, pedestrian at +40 m, road heading fixed, scripted speed). The relevance filter and relay code are unchanged. Add a **"flip heading" button** on Car B: it shows the filter rejecting oncoming traffic, live.
- **Pitch line:** "Indoors there is no GNSS, so each phone sits on a virtual road. The radio, sensors and camera are live. Here is the outdoor run with real GNSS."

### 4. Demo staging and live-demo failure modes
- **Airplane mode:** since Android 14, once Bluetooth is turned on in airplane mode the phone keeps it on next time [12][13]. OriginOS 6 is Android 16-based [14]; verify the behaviour on the loaner. Show quick settings on the projector: airplane on, Bluetooth on, Wi-Fi and data off.
- **Office Kit:** it mirrors and remote-controls the phone from the PC [15], but the connection method is not publicly documented; an iQOO community post recommends the same network [14] (single source). Test on Friday whether mirroring works over a laptop hotspot with no internet, or USB. If it needs Wi-Fi, mirror one phone and run the airplane-mode proof on the others.
- **Crowded halls break radio demos.** At WWDC 2010 Steve Jobs had to halt the iPhone 4 demo and ask the audience to switch off Wi-Fi; he said 570 Wi-Fi base stations were running in the hall [26][27]. Expect BLE loss in the venue and never depend on venue Wi-Fi.
- **RSSI is a poor distance proxy indoors.** Phone-to-phone Bluetooth signal strength showed little correlation with distance in a tram, and need not rise as phones get closer in a bus, because metal reflects [28][29]. RSSI-gated multi-hop is therefore unreliable on stage; the disclosed software topology is safer.
- **Organiser-platform advice:** the reskilll.com blog tells teams to record a backup video and lists the judges' first question as "Does it work?" [30]. Keep the live demo as the centre of the pitch.
- **vivo kills background work** (rated 3 of 5, no known developer-side fix). On every demo phone: battery "unrestricted", allow high background power use, autostart on, app locked in Recents [31].

### 5. Field-test protocol (outside the venue)
- **Where and who (recommendations):** empty parking lot or closed campus road, never public roads for brake tests; the driver never touches a phone; an operator in the passenger seat; a spotter outside; brakes from ≤20 km/h.
- **Log on every phone:** IMU, GNSS (accuracy, speed, bearing), every BLE packet (seq, RSSI, hop, timestamp), detections, alerts, battery, temperature. Screen-record each phone, film the scene, and **clap once at the start** to align all recordings.
- **Runs:** walk-away PDR (in hand, then in pocket); bicycle with pocket beacon passing a parked dash phone; slow-car hard brake with a follower phone; speed breakers.
- **Backup video (60–90 s):** airplane mode, brake → spoken alert, see-through, SOS hop, dashboard.

### 6. Test plan for technical depth
| Layer | Test | Evidence for the jury |
|---|---|---|
| Protocol | Port the 6 existing JS tests to Kotlin JUnit; fuzz `decode` with random bytes (must reject, never crash) | test count, all green |
| Relay | Simulate 10–500 nodes: reachability, saved rebroadcasts, latency (broadcast-storm metrics [19]); flooding vs contention delay | one chart |
| Detectors | Replay labelled CSVs on the JVM: precision/recall, detection latency, false alarms per hour; golden-file regression | confusion matrix, FA/h |
| Device | Two phones over adb: trigger A, read B's logcat; permission and foreground-service checks | log excerpt |
| Soak | 60 min, screen off, advertise + scan (+ camera at low fps): packets/min per peer, battery, temperature, scan-callback heartbeat. Catches silent scan stops (never restart scans in a loop: 5 starts per 30 s [21]) and vivo background kills [31] | soak chart |

## Metrics table
| Metric | How measured on site | Target (proposed) | How shown |
|---|---|---|---|
| One-hop radio latency | 4-timestamp ping-pong, ≥100 samples | p50 ≤ 300 ms, p95 ≤ 900 ms (budget [21]) | stats screen histogram |
| Brake → speech, end to end | 240 fps video, 20 trials | ≤ 1.3 s (budget [21]) | still frame + live |
| PDR vs distance | seq beacons, 60 s per point at 1/5/10/20/40 m | ≥ 90% at 10 m | dashboard chart |
| Hop reach | hop field histogram per receiver | 3 hops | dashboard |
| Detection latency | labelled replays + on-device video | ≤ 500 ms after onset | test report |
| False alarms per hour | replay normal driving (UAH normal + own drive) | < 1 per hour | test report |
| Battery drain | charge counter over 60-min soak | report honestly | soak chart |
| NPU vs CPU | 500 timed inferences per backend | NPU several× faster [20] | bar chart on stats screen |

## Demo staging options
| Option | Reliability | Honesty | Wow | Verdict |
|---|---|---|---|---|
| Airplane mode + Bluetooth on, shown on projector | High | High | Medium | **Open with it** |
| Multi-hop by software topology (allowlist) + live hop counter | High | Medium–High if disclosed | Medium | **Default** |
| Multi-hop by low TX power + RSSI gate + separation | Low–Medium | High | High | Rehearse; use only if stable |
| Faraday pouch on one phone | High (total isolation) | High | Low | Control experiment only ("no radio, no alert") |
| Metal tin for partial attenuation | Low (unpredictable) | High | Low | Skip |
| See-through: dash phone on a stand, teammate behind a cardboard "bus" | Medium–High (stage light) | High | High | **Do**; printed life-size photo as backup |
| Hard brake on a trolley or office chair in a corridor | Medium | High | High | Do if space allows |
| Shake-to-brake demo mode (lower threshold, labelled DEMO) | High | Medium (disclose) | Low | Fallback |
| Replay a recorded field brake through the live detector and radio | High | High if labelled | Medium | Fallback |
| Crash: own phone dropped on a cushion → countdown → SOS hop | Medium | High | High | **Do, never with the loaner** |
| Siren recording played from a speaker | High if loud | High | Medium | Do if the siren detector exists |
| Office Kit mirror to projector | Unknown until tested | High | — | Required (10%); test Friday |

## What this means for RoadMesh
1. Build `PositionSource` and `SensorSource` interfaces first; demo track, trace replay and tests all depend on them.
2. Ship a stats screen (latency histogram, PDR, hop counts, inference times) and a test-report screen; both mirror to the laptop.
3. Friday night in the hall: Bluetooth capabilities, airplane-mode behaviour, Office Kit connection mode, PDR at 1–10 m, vivo battery settings on all phones.
4. Sunday morning outside: field test plus backup video.

## Sources
1. UAH-DriveSet: http://www.robesafe.uah.es/personal/eduardo.romera/uah-driveset/
2. UAH-DriveSet reader (CC BY-NC 4.0): https://github.com/Eromera/uah_driveset_reader
3. jair-jr driverBehaviorDataset: https://github.com/jair-jr/driverBehaviorDataset
4. Mendeley, Driver Behavior Detection Using Smartphone (VIIT): https://data.mendeley.com/datasets/9vr83n7z5j/2
5. Mendeley, Smartphone Sensor Data of Indian Cities for Public Bus (IIT KGP): https://data.mendeley.com/datasets/92yrxtv5gn
6. RDD2022 paper: https://arxiv.org/abs/2209.08538
7. RDD2022 downloads and licence: https://github.com/sekilab/RoadDamageDetector
8. IDD details: https://idd.insaan.iiit.ac.in/dataset/details/
9. DriveIndia paper: https://arxiv.org/abs/2507.19912
10. TiHAN dataset access (EULA): https://tihan.iith.ac.in/TiAND.html
11. iWatchRoad paper: https://arxiv.org/abs/2508.10945
12. Google, airplane mode keeps wireless on: https://support.google.com/googlepixeltablet/answer/13592149?hl=en
13. SamMobile, Android 14 flight mode: https://www.sammobile.com/news/android-14-smarter-flight-mode-samsung-galaxy-phones/
14. iQOO community, Office Kit: https://community.iqoo.com/in/thread/119930
15. vivo newsroom, Office Kit features: https://vivonewsroom.in/how-vivo-office-kit-is-breaking-cross-device-barriers-for-modern-users/
16. RFC 5905 (NTPv4) on-wire offset and delay: https://datatracker.ietf.org/doc/html/rfc5905
17. Android ScanResult: https://developer.android.com/reference/android/bluetooth/le/ScanResult
18. Android BatteryManager: https://developer.android.com/reference/android/os/BatteryManager
19. Ni et al., The broadcast storm problem (MobiCom 1999): https://doi.org/10.1145/313451.313525
20. Ultralytics QNN integration (via SOLUTION.md): https://github.com/ultralytics/ultralytics/blob/main/docs/en/integrations/qnn.md
21. Earlier research, latency budget and scan limits: team planning notes (SOLUTION.md, not in this repo) §4
22. FusedLocationProviderClient (setMockMode, setMockLocation): https://developers.google.com/android/reference/com/google/android/gms/location/FusedLocationProviderClient
23. Android LocationManager (test providers): https://developer.android.com/reference/android/location/LocationManager
24. GNSS detection of attenuating environments: https://arxiv.org/abs/2007.14329
25. GIM International, GNSS indoors: https://www.gim-international.com/content/article/send/indoors
26. InfoWorld, Jobs' Wi-Fi meltdown at WWDC: https://www.infoworld.com/article/2627299/steve-jobs--wi-fi-meltdown-at-wwdc.html
27. AppleInsider, iPhone 4 keynote Wi-Fi meltdown: https://appleinsider.com/articles/10/06/07/iphone_4_keynote_plagued_by_high_tech_wi_fi_meltdown
28. Leith & Farrell, Bluetooth proximity in a commuter bus: https://arxiv.org/abs/2006.08543
29. Leith & Farrell, Bluetooth proximity in a light-rail tram: https://www.ncbi.nlm.nih.gov/pmc/articles/PMC7526892/
30. Reskilll blog, hackathon demo and pitch tips: https://blogs.reskilll.com/hackathon-demo-presentation-tips-pitch-3-minutes-win-2026/
31. Don't kill my app, vivo: https://dontkillmyapp.com/vivo
