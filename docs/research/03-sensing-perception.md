# Sensing and perception algorithms for RoadMesh (card A3)
*Researched 2026-10-08 to 2026-10-09 · Sources: 31 · Confidence: Medium (High for hard-brake and crash evidence; Low for pothole, brake-light and pocket-mode numbers until verified)*

## Bottom line
- **Fix the "3.8×" pitch line.** In Google's paper, 3.8× is a ratio of PR-AUC scores: Transformer 0.833 vs a GPS-speed heuristic 0.221 (and 166.6× vs an accelerometer-only heuristic at 0.005), on candidate events already pre-filtered at linear acceleration > 5 m/s² [1]. It is not "3.8× fewer false alarms". The model ran server-side; on-device inference was listed as future work [1].
- **Never trigger on raw accelerometer magnitude.** Unaligned accelerometer thresholding scored PR-AUC 0.005 and ROC-AUC 0.482 (worse than chance) [1]. GNSS speed alone reached ROC-AUC 0.970 [1], so every IMU trigger must be confirmed by a GNSS speed drop.
- **Hard brake = longitudinal deceleration ≤ −4.4 to −5 m/s² (0.45–0.5 g), confirmed by GNSS.** Google's ground truth was wheel-speed derivative ≤ −5 m/s² [1]; −0.45 g is the teen-driving-study convention (not yet verified in full text) [21].
- **Single-phone crash detection is noisy in the wild.** 35 of 49 (71%) automatic iPhone/Apple Watch crash calls in Okayama City (Oct 2022–Mar 2023) were false positives from braking or dropped phones [2]; a peak-g baseline on 735k real fleet events had 12% precision at 75% recall [10]. N3 (multi-signal + peer confirmation) targets a documented problem, but no published evaluation of multi-device crash confirmation was found: pitch it as our hypothesis.
- **Vehicle frame:** gravity from `TYPE_GRAVITY`; forward axis by regressing horizontal acceleration on the GNSS speed derivative [6]. Classical GNSS-aided alignment takes minutes (u-blox EVK-M8U: first estimate after 4 min) [5]. Plan a 30–60 s calibration drive with one firm brake, plus a manual "top edge forward" mount mode for the stage.
- **FCW without intrinsics:** TTC = Δt / (1 − η), η = s(t−Δt) / s(t) for box size s [3]. Compute it as 1 / slope of ln s over a 0.5–1 s window, not from frame pairs (box jitter).
- **Proxy V2X without a brake-light model:** when the lead car's 1/TTC jumps while our own speed is steady, the lead car is braking; the dash phone can broadcast an Emergency Brake Alert on its behalf (inference; brake-light detectors not yet verified).
- **Sirens and horns need no training:** YAMNet's 521 classes include `Siren`, `Ambulance (siren)`, `Emergency vehicle` and `Vehicle horn` [26]. Expect a large lab-to-street accuracy drop [27], so smooth over frames and test with Indian sirens on day 1.

## Findings

### 1. Vehicle-frame alignment
| Method | Evidence | Time to calibrate |
|---|---|---|
| Roll/pitch from accelerometer at zero acceleration (or Android gravity sensor) | Survey [6] | Instant |
| Forward axis = GNSS course/velocity direction; PCA of accelerometer (longitudinal axis carries most variance, lateral next) or of gyro (yaw axis) | Survey [6] | Needs speed changes and turns |
| "Full auto-calibration" with IMU + GPS (Almazán et al., IEEE IV 2013) | Exists [7]; accuracy not yet verified | Not yet verified |
| Commercial GNSS/INS (u-blox EVK-M8U) | First output after 4 min; after a 90° re-mount it took >10 min and ended ~20° off [5] | Minutes |
| Learned yaw, IMU only (MountNet) | 8° within 5 s, 4° within 27 s; phone fixed on a mount; yaw only [5] | Seconds |
| Least-squares plane + random forest | RMSE 0.34 rad (≈19°) [8] | Per recording |
| DNN on 64,397 journeys, 10 s windows | Axis sign/assignment known in 90.07% of test trips [9] | Per trip |

- With the phone fixed, errors of about 2° per Euler angle are reported [6].
- Picking the phone up produces accelerations "orders of magnitude" larger than vehicle dynamics; systems discard IMU data while the phone moves relative to the car and re-initialise [6].
- Passenger-car horizontal accelerations are mostly below 2 Hz, so low-pass filter [6].

```
ĝ   = TYPE_GRAVITY (unit)                       # vertical
a_h = a_lin − (a_lin·ĝ) ĝ                        # horizontal accel, phone frame
if gyro_quiet and v_gnss > 3 m/s and |dv/dt_gnss| > 1 m/s²:   # start values, tune day 1
    S += a_h * (dv/dt_gnss)
f̂ = S/|S| (forward), l̂ = ĝ × f̂ (left)
accept when corr(a_h·f̂, dv/dt_gnss) > 0.8 over the last events; reset on handling
```
Numbers in the pseudocode are starting values, not literature values.

### 2. Hard-brake detection
**Google Research, arXiv 2202.01934 [1], verified in full text:**
| Item | Value |
|---|---|
| Data | Android Auto projection mode: phone sensors + vehicle wheel speed from CAN |
| Label | min(d(wheel speed)/dt) ≤ −5 m/s² |
| Candidate trigger (on phone) | linear-acceleration magnitude > 5 m/s²; 100 ms averaging → 10 Hz; 5 s window (101 samples) |
| Inputs | accelerometer (3), gyroscope (3), linear acceleration (3), GPS speed (1) |
| Model | Transformer, M=128, N=6, 268,501 params; CNN and LSTM reach the same PR-AUC (0.831) |
| Results | PR-AUC 0.833; ROC-AUC 0.996; 0.99 TPR at 0.04 FPR; precision 0.96 at recall 0.2, 0.87 at 0.6 |
| Single features | accel 0.745, linear accel 0.645, GPS speed 0.624, gyro 0.346; accel+speed 0.814 |

**Typical decelerations**
| Level | Value | Source |
|---|---|---|
| Normal stop | mean required 1.99 m/s² (≈0.2 g) at yellow onset | [16] (single source) |
| Comfort class boundaries (peak decel) | 4.07, 5.15, 7.54, 9.11 m/s² (≈0.41, 0.53, 0.77, 0.93 g) | [16] (single source, 449 runs) |
| "Hard braking" thresholds in studies | −0.25 to −0.6 g evaluated; −0.5 g common | [17] |
| Hard braking, NTDS | < −0.45 g | [21] (not yet verified) |
| Event-rate thresholds | optimal values depend on the decision rule | [18] |

- Elevated g-force event rates predicted crashes in teen drivers (OR 1.07, AUC 0.76) [19]; basic kinematic thresholds can have quite low sensitivity [20].
- Fleet data lists potholes and near-miss hard braking among high-g "distractors" [10]: separate them by axis in the vehicle frame (vertical vs longitudinal; inference).

### 3. Crash detection
| System | Signals | After detection |
|---|---|---|
| Apple (iPhone 14+, Watch S8+) | 256 g accelerometer, HDR gyro, barometer (cabin pressure), GPS speed change, microphone; trained on >1 million hours of driving and crash data [13]; fusion, "no silver bullet", gated by GPS speed, CarPlay/Bluetooth, Wi-Fi changes, road noise [14] | 10 s alert, then 30 s countdown, then call [12] |
| Google Pixel (4a+, India supported) | location, motion sensors, nearby sounds; needs location, activity, mic permissions; not in Airplane mode or Battery Saver; "high-impact activities could also trigger" [15] | 60 s to respond, then speakerphone call [15] |
| WreckWatch (2011) | 4 g acceleration filter; 140 dB sound threshold; armed only above a speed threshold, stays armed while stopped within a short distance [11] | report to server |

- WreckWatch tests: a drop from ear height onto a car seat gave ≈2–3 g and 103 dB; laughing, shouting and full-volume radio reached 145 dB, where phone audio clips [11]. Sound is only a secondary cue. A drop onto a hard floor may exceed 4 g (not tested there; inference).
- VZCrash (Verizon Connect, public on Hugging Face): 31,090 verified crashes, ~190k events, 100 Hz IMU + 1 Hz GPS speed. Non-crash events outnumber crashes 5,100:1; at 75% recall the peak-g baseline gave 780 false positives (12% precision), a CNN-RNN trained on all data 10 (91.5%) [10].
- Peer or multi-device crash confirmation: insufficient evidence found.

### 4. Potholes and speed breakers
- **Nericell (Microsoft Research India, Bangalore, SenSys 2008)** used commuters' phones (accelerometer, microphone, GPS) to detect potholes, bumps, braking and honking, and reported false-positive/negative rates [30]. Lecture slides describe two bump detectors: `z-peak` (vertical spike) at high speed and `z-sus` (sustained dip in vertical acceleration) at low speed [31]. The numeric thresholds, speed cut-off and error rates are **not yet verified**; read the paper before using them.
- Pothole Patrol (Eriksson 2008), Mednis 2011, RDD2022 and iWatchRoad: not yet verified here.
- Fleet IMU data confirms potholes produce crash-like high-g spikes [10]. Design implication: run bump detection on the vehicle-frame vertical axis with a speed-dependent rule (Nericell's split), and never let a vertical spike count as a crash impact (inference).
- Unmarked speed breakers: insufficient evidence found in this pass.

### 5. Forward collision warning from one camera
- **Formula [3]:** η = λ(t1)/λ(t0) = h(t0)/h(t1) for a rigid, planar, fronto-parallel object; τ = T/(1 − η). Error from changing object orientation shrinks at higher fps [3].
- **Log-scale form (derivation):** s ∝ 1/Z, so d(ln s)/dt = −Ż/Z = 1/τ. Track x = [ln s, d ln s/dt] with a constant-velocity Kalman filter or a 0.5–1 s line fit; TTC = 1/x₂.
- **Why not frame pairs (illustrative arithmetic):** car at 20 m closing at 5 m/s (τ = 4 s), box 100 px wide. In 0.1 s it grows only 2.6 px, so ±1 px jitter gives TTC ≈ 2.9–6.5 s. Over 0.5 s it grows 14.3 px, and ±2 px gives ≈ 3.5–4.6 s.
- **Metric distance (DMODE) [4]:** needs ego acceleration when the object moves at constant velocity, and box sizes were often inconsistent with distance. Skip for 48 h.
- **Failure modes:** turning or oblique vehicles [3], occlusion, truncation at image edges, own pitch during braking, lateral crossers (scale TTC ignores lateral motion; use the box's lateral position too).
- **Brake lights:** not yet verified.

### 6. Object classes on Indian roads
COCO covers person, bicycle, car, motorcycle, bus, truck, cow, dog (verify in the model's label file); no auto-rickshaw class. Indian datasets (IDD, DriveIndia, RDD2022 India, iWatchRoad) come from earlier research and are not re-verified here.

### 7. Audio (sirens, horns, crash sounds)
- **YAMNet (521 AudioSet classes) already has the classes we need** (verified in the official class map [26]): `Emergency vehicle` (316), `Police car (siren)` (317), `Ambulance (siren)` (318), `Fire engine, fire truck (siren)` (319), `Siren` (390), `Civil defense siren` (391), `Vehicle horn, car horn, honking` (302), `Air horn, truck horn` (312), `Skidding` (306), `Smash, crash` (463), `Traffic noise, roadway noise` (321). Note that index 102 `Honk` is not a vehicle horn.
- Use the max score over the siren group, smoothed over several consecutive frames, so one noisy frame cannot fire an Emergency Vehicle Alert (design choice).
- `Smash, crash` + `Skidding` can feed the crash detector as a secondary cue, in the same role WreckWatch gives loudness [11].
- **Expect a big lab-to-street drop.** A 2025 review [27] tabulates siren classifiers at 91–100% offline accuracy on small datasets, but one CNN fell from 91% test accuracy to 48.5–70.7% on continuous London street audio as SNR varied; SirenNet kept 96.89% with 250 ms inputs; few studies report latency or edge deployment [27]. The same authors fine-tune a general AudioSet tagger (PANNs) on a curated AudioSet-EV subset because raw AudioSet labels are unreliable for sirens [27][28].
- With two cheap microphones, siren presence recall was 99.16% at 10–50 m, with detection plus localisation within 50 ms [29] (abstract).
- Accuracy on Indian sirens and horns, and latency on the iQOO: not verified; measure on day 1 with recorded Indian ambulance sirens played over traffic noise.

### 8. Pocket mode for vulnerable road users
- A Random Forest on accelerometer, gyroscope and rotation-vector features (no GPS) reported 98.34% accuracy (98.79% with time-domain features) for VRU-type classification (abstract only) [22].
- A smartphone framework for e-bike/e-scooter crash and emergency-manoeuvre detection uses threshold criteria from track tests (abstract only) [23].
- Android Activity Recognition Transition API accuracy and latency: not yet verified.

## Options matrix
| Option | Evidence | Feasibility on iQOO in 48 h | Risk | Verdict |
|---|---|---|---|---|
| Gravity + GNSS-derivative regression for forward axis | [6] | High, about 60 lines | Needs a drive with speed changes | **Recommended** |
| PCA of horizontal accel | [6] | High | Sign ambiguity; turns pollute | Fallback |
| Learned mount angle (MountNet-style) | [5] | Low (no weights) | Training time | Avoid |
| Manual "top edge forward" mount mode | none | Trivial | User error | **Recommended for stage** |
| Threshold + GNSS gate hard brake | [1][17] | High | Misses short brakes | **Recommended** |
| On-device CNN hard-brake model | [1] | Low (no labelled data) | No data | Avoid (upgrade path) |
| Multi-signal crash AND + countdown | [11][12][14][15] | High | Low recall at low speed | **Recommended** |
| Peer-confirmed crash (N3) | none found | Medium | Unvalidated | **Recommended as novelty, labelled hypothesis** |
| 1D CNN trained on VZCrash | [10] | Medium (public data, dashcam IMU not phone) | Domain shift | Fallback |
| Log-scale TTC from tracked box | [3] | High | Jitter, occlusion | **Recommended** |
| Proxy EBA from lead-box looming | [3] + inference | High | False alerts on cut-ins | **Recommended** |
| Brake-light detector | not verified | Low | Custom training | Avoid |
| YAMNet siren/horn classes, smoothed | [26][27] | High (TFLite model) | Street-noise accuracy drop | **Recommended** |
| IMU bump detector (z-peak / z-sus by speed) | [30][31], thresholds unverified | High | Thresholds to tune | Fallback |

## What this means for RoadMesh
| Parameter | Value | Source / status |
|---|---|---|
| IMU rate | request 100 Hz; average to 10 Hz for braking | [1] |
| Low-pass for vehicle dynamics | < 2 Hz | [6] |
| Hard-brake trigger | a_long ≤ −4.4 to −5 m/s² | [1], [21] not verified |
| Hard-brake confirm | GNSS speed falling in the same 2 s window | [1] (design) |
| Hard-brake duration | 300 ms | design choice, no source |
| Crash impact | peak \|a\| ≥ 4 g | [11] |
| Crash context | armed only in-vehicle (speed gate, stays armed when briefly stopped) | [11][14] |
| Crash follow-up | speed → ~0 within 3 s, stillness 10 s | SOLUTION.md design, no source |
| Sound | secondary cue only | [11] |
| Countdown | 10 s demo; 30–60 s real use | [12][15] |
| TTC | 1/slope of ln(box size), 0.5–1 s window, ≥10 fps | [3] + derivation |
| FCW threshold | TTC < 2.5 s | SOLUTION.md, not yet verified |

**Scoring:** NPU vision + log-scale TTC + proxy EBA hits Tech depth and HackTracker camera/on-device AI. Gravity/GNSS frame alignment and the GNSS-gated brake detector show real sensor use. N3 crash confirmation, framed against the 71% false-positive figure [2], is the strongest novelty line.

## Test on the loaner phone on day 1
1. Log every sensor's real rate, `getMaximumRange()` (accelerometer clipping) and GNSS speed rate. 
2. Three phones in one car (dash, cup holder, pocket): drive 2 min with 3 firm brakes; check each phone's forward-axis trace against GNSS dv/dt.
3. Drop each phone from 1 m onto a seat and onto a hard floor; log peak g and audio level to set the impact threshold.
4. TTC: walk a phone toward a parked car at a steady pace; compare TTC with tape-measured distance / time.

## Sources
1. Liu et al., Smartphone-based Hard-braking Event Detection at Scale (Google Research): https://arxiv.org/abs/2202.01934
2. Hongo et al. 2023, Automatic emergency calls from smartphone/smartwatch applications in trauma: https://pmc.ncbi.nlm.nih.gov/articles/PMC10363817/ (read via Europe PMC)
3. Khan et al. 2024, oTTC: Object Time-to-Contact: https://arxiv.org/abs/2405.07698
4. Agand et al., DMODE monocular distance: https://arxiv.org/abs/2210.12596
5. Deep Learning for Inertial Sensor Alignment (MountNet): https://arxiv.org/abs/2212.11120
6. Wahlström, Skog, Händel, Smartphone-based Vehicle Telematics: A Ten-Year Anniversary: https://arxiv.org/abs/1611.03618
7. Almazán et al. 2013, Full auto-calibration of a smartphone on board a vehicle (IEEE IV, DOI 10.1109/IVS.2013.6629658): https://portalcientifico.uah.es/documentos/614e79be68c341284de7c7ec
8. Re-Orienting Smartphone-Collected Car Motion Data (Sensors 2022): https://pmc.ncbi.nlm.nih.gov/articles/PMC8875019/
9. Estimating Vehicle Movement Direction with DNNs (Sensors 2018): https://pmc.ncbi.nlm.nih.gov/articles/PMC6111255/
10. Bianconcini et al. 2026, VZCrash IMU crash dataset: https://arxiv.org/abs/2606.06074
11. White et al. 2011, WreckWatch: https://www.dre.vanderbilt.edu/~schmidt/PDF/wreckwatchj.pdf
12. Apple, Use Crash Detection: https://support.apple.com/en-us/104959
13. Apple Newsroom, iPhone 14 (Crash Detection sensors): https://www.apple.com/newsroom/2022/09/apple-introduces-iphone-14-and-iphone-14-plus/
14. TechCrunch 2022, Apple offers a deeper dive into Crash Detection: https://techcrunch.com/2022/10/10/apple-offers-a-deeper-dive-into-crash-detection/
15. Google Pixel Help, Car Crash Detection: https://support.google.com/pixelphone/answer/7055029
16. Driver behaviour at signalized intersections (comfort vs deceleration): https://arxiv.org/abs/2609.16058
17. Botzer et al. 2019, hard-braking thresholds −0.25 to −0.6 g (PMID 31446098): https://pubmed.ncbi.nlm.nih.gov/31446098/
18. Mao et al. 2021, decision-adjusted kinematic thresholds, SHRP2 (PMID 33866156): https://pubmed.ncbi.nlm.nih.gov/33866156/
19. Simons-Morton et al. 2012, elevated g-force events predict crashes: https://pmc.ncbi.nlm.nih.gov/articles/PMC3353134/
20. Perez et al. 2017, basic kinematic thresholds in naturalistic data (PMID 28371637): https://pubmed.ncbi.nlm.nih.gov/28371637/
21. Simons-Morton et al. 2015, Naturalistic Teenage Driving Study (−0.45 g; not yet verified): https://pmc.ncbi.nlm.nih.gov/articles/PMC4583651/
22. VRU detection with smartphone sensors and RQA: https://arxiv.org/abs/2006.06941
23. Naude et al. 2026, e-bike/e-scooter crash and jolt detection (PMID 42546572): https://pubmed.ncbi.nlm.nih.gov/42546572/
24. Android Sensor reference (gravity, linear acceleration, rotation vector): https://developer.android.com/reference/android/hardware/Sensor
25. Markkula et al. 2016, brake response in naturalistic rear-end emergencies (PMID 27450793): https://pubmed.ncbi.nlm.nih.gov/27450793/
26. YAMNet class map (TensorFlow models repo): https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/yamnet_class_map.csv
27. From Large-scale Audio Tagging to Real-Time Explainable Emergency Vehicle Siren Detection (E2PANNs, 2025): https://arxiv.org/abs/2506.23437
28. Real-Time Emergency Vehicle Siren Detection with Efficient CNNs on Embedded Hardware (2025): https://arxiv.org/abs/2507.01563
29. Emergency Vehicles Audio Detection and Localization in Autonomous Driving: https://arxiv.org/abs/2109.14797
30. Mohan, Padmanabhan, Ramjee 2008, Nericell (ACM SenSys): https://dl.acm.org/doi/10.1145/1460412.1460444
31. Nericell lecture slides (z-peak / z-sus): https://pdfs.semanticscholar.org/874a/2e788ba79b66a8c58cd1bb9dfaedcf5cc55f.pdf
