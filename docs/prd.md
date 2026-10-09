# RoadMesh: zero-hardware V2X for every road user

## Problem
In India, 69.1% of the 1,77,175 people killed on roads in 2024 were riders of two-wheelers, pedestrians or cyclists, and "hit from back" was the deadliest collision type. The national V2V answer (AIS-230 C-V2X) reaches only vehicles built from October 2028. Until then, the existing fleet and everyone outside a car get no early warning of hazards they cannot see. The cost of waiting is measured in the queue: each driver reacts to the brake lights ahead about 1.25 s later than the one in front.

## Evidence
- MoRTH *Road Accidents in India 2024*: two-wheelers 46.2%, pedestrians 20.6%, cyclists 2.3% of deaths; hit from back 21.1%; fog 15,115 deaths (`finale-research/05`).
- AIS-230 draft: new L/M/N vehicles from 1 Oct 2028; no phones, pedestrians or retrofit (`05`, `09`).
- ADAS in 8.3% of new passenger vehicles (H1 2025) (`05`).
- Reaction-time accumulation model built on published perception-brake times (`09b`).
- User-level evidence (interviews, field logs): **Assumption: needs validation via a field prototype and user interviews.**

## Users
- **Primary:** (a) drivers of existing cars, autos and buses with a phone in a dash mount, who cannot see a hazard two or more vehicles ahead or around a bend; (b) two-wheeler riders and pedestrians carrying a phone in a pocket, who are invisible to drivers until late.
- **Not for:** autonomous driving, lane-level collision avoidance or vehicle control; replacing certified OBUs; anyone who must touch the phone while moving.

## Hypothesis
We believe **phone-to-phone broadcast of hazards detected by phone sensors and on-device vision** will **give road users who cannot see a hazard an earlier spoken warning** for **drivers, riders and pedestrians outside the 2028 V2V mandate**.
We'll know we're right when **an alert reaches a phone two hops away with no internet, with measured one-hop p95 latency of 900 ms or less, and a 10-run rehearsal produces no false urgent alerts.**

## Success Metrics
| Metric | Target | How measured |
|---|---|---|
| One-hop latency | p50 ≤ 300 ms, p95 ≤ 900 ms | Four-timestamp ping-pong, 50+ probes |
| Brake to speech | ≤ 1.3 s | 240 fps video, 20 trials |
| Delivery ratio | ≥ 90% at 10 m | Sequence-numbered beacons |
| False alarms | < 1 per hour | Replays of normal-driving datasets |
| Robustness | No reception gap > 2 s in 60 min, screen off | Soak export |

## Scope
**MVP:** the never-cut core (protocol v2, BLE broadcast with ≤3-hop relay, relevance and trust tiers, spoken alerts with haptics, IMU hard brake confirmed by speed, pocket presence beacon, disclosed demo track and hop allowlist, measured stats with Office Kit export) plus proxy V2X (camera detector on the NPU emitting pedestrian-ahead and lead-vehicle-braking alerts for unequipped road users), plus an on-device LLM co-pilot off the alert path (drive debrief first; voice hazard reports and explanations next).

**Out of scope**
- Crash detection and SOS relay with an LLM incident report: single-phone crash detection has a documented 71% false-positive rate; roadmap.
- Spoken hazard reports in Hindi and Kannada: integration risk. English voice reports are in Phase 4B; Indic languages are roadmap.
- Live digital-twin dashboard: no Office Kit API; file drops are brittle live.
- Certificates and identity (SCMS-style): needs a CA; roadmap.
- Wrong-way detection, CRDT hazard gossip, Wi-Fi Aware ranging: roadmap.

## Delivery Milestones
| # | Milestone | Outcome | Status | Plan |
|---|---|---|---|---|
| 1 | Offline alert | An alert from one phone is spoken on phones behind it, with no internet | pending | `finale-research/12` Phase 1 |
| 2 | Trusted, relevant alerts | Only hazards ahead and in the same direction are spoken; corroboration upgrades caution to urgent | pending | Phase 2 |
| 3 | Real brake | A physical hard brake with a speed drop triggers the alert; a shake does not | pending | Phase 3 |
| 4 | See-through | A pedestrian seen only by the front phone's camera is announced on the rear phone | pending | Phase 4 |
| 4B | On-device co-pilot | An LLM on the phone turns the local event log into a spoken debrief, with no internet and no effect on alert latency | pending | Phase 4B |
| 5 | Measured and hardened | Live latency, delivery and inference numbers; 60-minute soak; 10/10 rehearsal | pending | Phase 5 |
| 6 | Gated stretch | Siren corridor, fog preset, LLM explanation, laptop viewer | pending | Phase 6 |

## Open Questions
- [x] What does HackTracker count? Answered by the venue booklet: inference calls, tokens and thermals. The LLM co-pilot is now in scope.
- [ ] Which LLM runs best on the loaner: GenieX on the NPU, LiteRT-LM or llama.cpp on CPU?
- [ ] Are vendor sample-app patterns and pre-trained weights allowed with attribution under "code written at the event"?
- [ ] Does the loaner support BLE extended advertising, and how many advertising sets?
- [ ] Does the QNN delegate run on HTP v81 on OriginOS, and does the GPU rung have OpenCL?
- [ ] Are offline Hindi and Kannada voices installed?
- [ ] Is the AI Hub YOLOX model's licence permissive?

## Risks
| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Extended advertising unsupported | Low-Medium | Medium | Legacy rung with unsigned 27-byte core |
| OriginOS kills the background service | High | High | FGS, exemptions, heartbeat log, SetupCheck |
| NPU integration stalls | Medium | Medium | GPU or CPU rung; overlay shows the truth |
| Crowded 2.4 GHz hall | Medium | High | Measure PDR in the hall; spacing; backup video |
| Stage false alarms | Medium | High | Trust tiers, ROI corridor, clip replay |
| Prior-art and patent challenge | Medium | Low | Cite ZTE, AT&T, Ford and Nexar; pitch the new perception layer |

---
*Status: DRAFT. Requirements only. Implementation planning is in `finale-research/12-implementation-plan.md`.*
