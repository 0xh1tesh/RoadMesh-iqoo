# RoadMesh: prior art, India evidence and novelty candidates (card A5)
*Researched 2026-10-08/09 · Sources: 41 · Confidence: Medium (India statistics and policy: High, read from the primary MoRTH report; prior art: Medium; patents: a first pass only, see §A)*

## Bottom line
- **The VRU thesis is confirmed from the primary MoRTH report.** In 2024 two-wheeler riders were 46.2% (81,780) and pedestrians 20.6% (36,526) of 1,77,175 road deaths; with cyclists (2.3%) that is 69.1% of deaths outside cars [1].
- **Lead the pitch with rear-end and fog numbers.** "Hit from back" is the deadliest collision type (37,404 deaths, 21.1%) [1]. Fog/mist crashes killed 15,115: about 44.5 deaths per 100 crashes against 36.3 overall (derived from [1]). These map straight onto Emergency Brake Alert and a fog "pile-up shield".
- **Phone-based brake warnings are not new.** Nexar ran a smartphone "V2V network" over the cloud in 2016 (hard-brake alerts, "a few thousand users") [20], and WiFiHonk (2014) did phone-to-phone V2P over Wi-Fi beacons [30][31]. Claim novelty only for the combination: offline direct radio, plus on-device perception that speaks *for* vehicles with no equipment, plus VRU beacons, plus the AIS-230 message vocabulary.
- **Three of SOLUTION.md's novelties have direct prior art. Drop any "first" claims.**
  - **ZTE patent US11062604B2** (priority 2017, active to 2038): a vehicle anomaly such as rapid deceleration is sent to nearby terminals over Wi-Fi Direct or Bluetooth, with no 3G/4G or GPS [38]. This covers the core brake alert.
  - **AT&T patent US8660480B2** (priority 2009, expires about 2029): devices out of network range corroborate an emergency and hop the report over Bluetooth/Wi-Fi until one has coverage, including a car-crash example [39]. This is close to N3 and N4.
  - **Ford (Sept 2022) designed an app** in which pedestrians' and cyclists' phones broadcast BLE location alerts to nearby Ford cars without pairing [37]. This is close to N1.

  What remains new is the camera/NPU perception (proxy V2X, siren AI), phones as *receivers* and relays (Ford's needs a Ford infotainment unit), e-DAR/PM RAHAT-format reporting, and AIS-230 semantics.
- **The policy timing holds, with corrections.** The AIS-230 draft (3–4 Aug 2026) mandates C-V2X on new L/M/N vehicles from 1 Oct 2028; voluntary fitments must comply from 1 Oct 2027. DoT delicensed the band by G.S.R. 466(E) on 10 Jun 2026 [6][7]. The sub-band is reported inconsistently (5,875–5,905 vs 5,875–5,925 MHz) [9]. Coverage of the draft mentions no phones, pedestrians or retrofits [6].
- **Build these five signature novelties:** (1) Proxy V2X: the dash camera broadcasts for a car with no equipment when it brakes. (2) Siren-to-mesh emergency corridor. (3) Fog pile-up shield. (4) Peer-verified crash with a witness evidence pack drafted in e-DAR format by the local LLM. (5) A live digital twin on the laptop over Office Kit.
- **Fix RESEARCH.md:** pedestrians are 20.6%, not "about 24%"; the 2025 totals (5.13 lakh accidents / 1.83 lakh deaths) are unverified, and no RAI 2025 report was found.

## Findings

### A. Prior art and competitors (2012–2026)
| Product / research | What it does | Hardware and connectivity | Gap vs RoadMesh |
|---|---|---|---|
| **Nexar "V2V network" (Nov 2016)** | Dashcam app warns the drivers behind of hard braking and obstacles | Phone + cellular cloud. CEO claimed alerts in 50 ms (single source) [20] | Car-only and needs the cloud. Nexar now sells dashcam data and is merging with Nauto (Jul 2026, single source) [21]. **Closest prior art to the brake alert** |
| **WiFiHonk (IEEE VTC 2014)** | Phone-to-phone V2P: puts position and speed into Wi-Fi beacon SSIDs | Phones only, Wi-Fi, under 300 m [30][31] | Research prototype. No perception, no relay |
| Phone V2VRU research 2012–18 (GM Wi-Fi Direct, Honda DSRC phones, V2ProVu, WiSafe, pSafety, Telstra/Cohda 4G) | Collision warnings between phones and cars | Wi-Fi, DSRC-capable phones or cellular [30] | V2ProVu needed under 130 m for 80% delivery. Wi-Fi reached about 200 m in the open but about 60 m in a crowded car park. Phone GNSS error is 3–10 m. Mostly awareness only, with no C-ITS standards [30] |
| TravelSafely (Applied Information) | App alerts for red-light running, active school zones, emergency vehicles and VRUs | Phone + cellular + the Glance smart-city back end (signals, school beacons) [22] | Needs city infrastructure. A University of Florida study found drivers slowed in school zones [22]. Patented (2021) [23] |
| HAAS Alert Safety Cloud | Alerts drivers when a responder's lights switch on | Responder-vehicle integration + cloud. Delivered through Waze, Apple Maps, Stellantis (2018+) and VW (2024+) [24] | Cloud-based and US-focused. Shows demand for Emergency Vehicle Alerts: 97% of alerted Ontario drivers slowed or moved over (CAA 2024) [24]. The Detroit pilot sent 108,531 alerts in 10 months [25] |
| Spoke + Commsignia + Qualcomm | C-V2X "VRU2X" for cyclists and scooters | A dedicated C-V2X unit plus an app [26][27] | Every rider needs hardware |
| Qualcomm C-V2X (9150 chip; BMW V2P demo with a Snapdragon phone over PC5) | Direct sidelink V2X | Dedicated chip; the phone was a demo [28][29] | **Insufficient evidence** that any consumer phone ships PC5 sidelink |
| MobiCell (research) | VRU safety from a smartphone app + C-V2X + cloud | Phone + network [32] | Research project |
| Waze / Google Maps / Mappls | Hazard reports from the crowd or the map | Cloud | Manual and cloud-bound (RESEARCH.md; not re-verified) |
| Apple / Pixel crash detection, Life360 | Single-phone crash detection that calls for help | Phone + cellular | Not yet verified this session. One Japanese study found a 71% false-positive rate for single-phone detection [33] |
| bitchat, Bridgefy, Briar, goTenna, Apple Find My, Google Find My Device | Offline relay of chat or location | Phones (goTenna needs a dongle) | Not yet verified. They carry chat or location, not road-safety meaning |
| **Ford BLE phone-to-vehicle app (Sept 2022)** | Pedestrians' and cyclists' phones broadcast BLE location alerts to nearby Ford cars, with no pairing [37] | Phone + the car's own infotainment. In the design phase; no relay mentioned [37] | The receiver must be a Ford. RoadMesh receivers are any phones, and they relay |
| **ZTE patent US11062604B2** (priority 2017-09-30, granted 2021-07-13, active to 2038) | Detects a vehicle anomaly (fault, rapid speed drop, high deceleration) and sends it to nearby terminals over Wi-Fi Direct (inside probe frames) or Bluetooth; the receiver plays an audio/video alarm [38] | Short-range radio, "does not depend on 3G/4G or GPS" [38] | **Covers the basic brake alert.** A US patent: check for an Indian family before commercialising (not yet checked) |
| **AT&T patent US8660480B2** (priority 2009-05-01, granted 2014-02-25, expires about 2029) | Out-of-range devices corroborate an emergency, then relay the report device to device (Bluetooth, Wi-Fi, ZigBee) until one has coverage; includes a car-crash example [39] | Phones, direct links | **Close to N3 + N4.** RoadMesh adds IMU + camera fusion, e-DAR-format reports and PM RAHAT integration |
| Other patent hits | BLE-to-vehicle pairing and proximity-alert patents (e.g. US10068473, US10075581 family), not read | — | Not yet verified |

**Honest verdict:** no single product or patent found does offline phone-to-phone hazard relay with on-device camera perception. However, the brake alert over Bluetooth/Wi-Fi (ZTE), multi-hop corroborated emergency relay (AT&T) and BLE VRU beacons (Ford) all have direct precedent. Pitch RoadMesh as an **integration with new perception**, not as a first.

### B. India evidence (MoRTH *Road Accidents in India 2024* [1] unless noted)
| Statistic | Value (table) | RoadMesh hook |
|---|---|---|
| Totals | 4,87,707 crashes · 1,77,175 killed · 4,71,441 injured (T1.1) | Scale |
| Deaths by road user | 2W 81,780 (46.2%) · pedestrians 36,526 (20.6%) · cyclists 4,161 (2.3%) · autos 5,659 (3.2%) (T4.4). **The 46.2/20.6 split is verified** | Pocket/VRU mode |
| Collision type | Hit from back 37,404 (21.1%) · head-on 28,400 · hit-and-run 34,030 (19.2%) (T1.5) | Emergency Brake Alert; witnesses |
| Fog / mist | 33,955 crashes, 15,115 killed (T3.8) | Fog shield |
| Wrong-side driving | 22,530 crashes, 8,327 killed (4.7%) (T3.1) | Wrong-way alert |
| Road features | Potholes 2,385 · curved road 20,703 · road works 5,389 killed (T3.5). **No separate speed-breaker row in 2024** | Hazard map, curves, work zones |
| Time of day | 18:00–21:00 is the busiest slot: 21.1% of crashes (T7.3) | Night VRU mode |
| Behaviour | No helmet: 54,122 killed (T3.3). Mobile-phone use: 2,002 killed (T3.1) | Hands-free voice only |
| Karnataka / Bengaluru | 12,390 killed (T5.6) / 894 killed (T6.2) | Local framing |
| Stray animals | No separate statistic | **Insufficient evidence** |
| Pedestrians hit by | 2W 10,378 · cars/LMVs 9,302 · trucks 5,165 [4] | Truck blind spots |
| 2023, for trend | 4,80,583 crashes, 1,72,890 killed [5] | — |
| Golden hour | The government says "nearly 50%" of deaths are avertable with admission inside the first hour [11] | A government claim; the original study was not traced |
| ADAS | 8.3% of new passenger-vehicle wholesales in H1 2025 (6.2% in H1 2024); entry-level petrol/diesel cars under ₹10 lakh: 1.4% [18] | The legacy fleet has no ADAS |
| Phones | vivo (excluding iQOO) was India's #1 smartphone shipper in 2025 with 20% [19] | iQOO/vivo reach |
| Not yet verified | Smartphone users and share with Bluetooth 5; ambulance response times; Yamuna Expressway fog pile-ups; Bengaluru blackspots and waterlogging | — |

### C. Policy timing
| Item | Verified facts | Correction to earlier docs |
|---|---|---|
| AIS-230 V2V draft | Draft 3–4 Aug 2026, 30 days for comments. C-V2X at 5.875–5.925 GHz. Voluntary fitments comply from 1 Oct 2027; mandatory on new L, M, N vehicles from 1 Oct 2028. Apps: Emergency Brake Alert, Forward Collision Warning, Wrong-way Driving, Emergency Vehicle Alert [6][7][10] | Add the 2027 voluntary date. The ₹5–7k OBU cost is an earlier estimate and is not in the draft [6]. ETV Bharat adds Intersection Movement Assist and Left Turn Assist plus US NHTSA crash figures, which it attributes to BIF [8]: treat as unverified |
| DoT delicensing | G.S.R. 466(E), 10 Jun 2026 [7]. Roadside-unit authorisation is likely limited to governments, with final norms expected Dec 2026 [9] | The sub-band is inconsistent (5,875–5,905 vs 5,875–5,925 MHz) [9]. Check the gazette |
| PM RAHAT | Launched 13 Feb 2026 [12]. ₹1.5 lakh per victim for 7 days [11]. Stabilisation for 24 h (48 h if life-threatening). Integrated with ERSS 112, and with eDAR + NHA TMS 2.0 [13][14]. Builds on the Cashless Treatment Scheme 2025, notified 5 May 2025. The Rah-Veer reward rose to ₹25,000 [1] | SOLUTION.md is correct; add the 7-day limit |
| e-DAR / iRAD | Police mobile app records GPS and photos. All 36 States/UTs had joined by Nov 2022 [16] (single source). The e-DAR portal dates from 2022 [15] | — |
| Bharat NCAP 2.0 | Draft AIS-197 Rev 1 (Nov 2025), effective Oct 2027. 100 points: crash 55, VRU 20, safe driving 10, accident avoidance 10, post-crash 5. AEB optional [17] | — |

## Novelty candidates
Scores 1–5: **E** evidence of impact · **W** wow for a jury · **F** feasibility with 3 phones in 48 h · **R** rubric fit (camera, voice, on-device AI, laptop role).

| # | Candidate | Evidence note | E | W | F | R | Σ |
|---|---|---|---|---|---|---|---|
| C1 | **Proxy V2X for legacy vehicles**: the dash camera sees the lead car's brake lights or a fast-growing box and broadcasts Emergency Brake Alert *for* it | Rear-end crashes 21.1% of deaths [1]; ADAS 8.3% [18]; the mandate covers new vehicles only [6]. ETSI Collective Perception is a precedent (not yet verified) | 5 | 5 | 4 | 5 | **19** |
| C3 | **Siren-to-mesh emergency corridor**: on-device audio AI hears a siren, and the relay warns vehicles *ahead* along the route | Emergency Vehicle Alert is an AIS-230 app [6]; HAAS demand [24]; golden-hour claim [11]. YAMNet classifies 521 AudioSet sound classes on MobileNet-v1 and ships as TFLite [40]; its exact siren labels are not yet verified. The public sireNNet siren dataset exists for fine-tuning [41] | 4 | 5 | 4 | 5 | **18** |
| C2 | **Fog pile-up shield**: in fog mode thresholds drop, relay TTL extends, and stopped vehicles keep beaconing | 15,115 fog deaths; higher severity [1] | 5 | 4 | 4 | 4 | **17** |
| C5 | **Witness evidence pack**: neighbouring phones attach timestamps, positions, pre-crash speeds and a snapshot; the local LLM drafts an e-DAR-style report | Hit-and-run is 19.2% of deaths [1]; e-DAR needs GPS and photos [16]; PM RAHAT runs on eDAR [14] | 4 | 4 | 4 | 5 | **17** |
| C4 | **Crash-scene beacon** against secondary collisions | Hit-and-run and fog figures [1]; secondary-crash data not yet verified | 4 | 4 | 5 | 3 | 16 |
| C6 | **2W rider fall detection** in pocket mode, peer-verified | 2W 46.2% [1]; 71% single-phone false positives [33] | 5 | 3 | 4 | 4 | 16 |
| C17 | **Live digital twin on the laptop**: nodes, hops, hazards and replay over Office Kit | Office Kit counts for 10% of the score; demo clarity | 2 | 5 | 4 | 5 | 16 |
| C18 | **Spoken hazard reports in Hindi/Kannada** ("gaddha ahead"), turned into a structured broadcast | Voice rubric; phone use killed 2,002 [1], so hands-free matters | 3 | 4 | 4 | 5 | 16 |
| C19 | **Blind-curve / ghat relay**: "vehicle around the bend" | Curved roads: 20,703 deaths [1] | 4 | 4 | 4 | 3 | 15 |
| C7 | **Wrong-way detection by flow consensus**: you move against the flow of nearby phones, no map needed | 8,327 deaths [1]; an AIS-230 app [6] | 4 | 4 | 3 | 3 | 14 |
| C13 | **Truck/bus blind-spot VRU warning** from pocket beacons | Trucks killed 5,165 pedestrians [4] | 4 | 3 | 4 | 3 | 14 |
| C20 | **Work-zone beacon** from a worker's phone | Road works: 5,389 deaths [1] | 4 | 2 | 5 | 3 | 14 |
| C12 | **School-zone / bus-stop crowd alert** (camera counts people) | TravelSafely school-zone result [22]; pedestrians 20.6% [1] | 3 | 3 | 4 | 4 | 14 |
| C10 | **Stray-animal alert** (COCO classes include cow and dog; not yet verified) | No animal statistic in RAI [1] | 2 | 3 | 5 | 4 | 14 |
| C8 | **Crowd-mapped potholes and unmarked speed breakers** (IMU + vision) | Potholes: 2,385 deaths [1]; not new | 3 | 2 | 4 | 4 | 13 |
| C14 | **Wi-Fi Aware RTT ranging** between phones | GNSS 3–10 m vs the 0.5 m needed for collision avoidance [30]; iQOO support unverified | 3 | 4 | 2 | 4 | 13 |
| C9 | **Store-and-forward hazard gossip** (CRDT map, no server) | 36.6% of deaths on national highways [3]; coverage gaps not verified | 3 | 3 | 3 | 3 | 12 |
| C16 | **Insurer safe-driving score** from hard-brake density | Hard braking validated as a crash surrogate [34]; IRDAI telematics rules not verified | 3 | 2 | 4 | 3 | 12 |
| C11 | **Waterlogging alerts** (Bengaluru) | Insufficient evidence found | 2 | 3 | 3 | 3 | 11 |
| C15 | **Near-ultrasound acoustic fallback** | No evidence; cabins are noisy | 1 | 3 | 2 | 2 | 8 |

## Options matrix
| Option | Evidence | Feasibility | Risk | Verdict |
|---|---|---|---|---|
| BLE extended-advertising broadcast + TTL relay (core link) | Phone Wi-Fi/BLE V2P prototypes worked at 60–300 m [30] | High | Range and latency in a crowded hall | **Recommended** |
| Proxy V2X (C1) | [1][18] | Medium–high (YOLO + box-growth TTC) | False brake triggers | **Recommended** |
| Siren audio AI (C3) | [6][24] | High (offline audio classifier) | Hall noise; play a recorded siren | **Recommended** |
| Cloud relay like Nexar or HAAS | [20][24] | High | Not novel; needs coverage | **Fallback** (internet gateway only) |
| Wi-Fi Aware RTT (C14) | [30] | Low (device support unknown) | Burns hours | Roadmap |
| C-V2X sidelink on phones | None in consumer handsets [28][29] | None | — | **Avoid** |
| Ultrasound (C15) | None | Low | Unreliable | **Avoid** |
| LLM on the safety-critical path | SOLUTION.md N7 | — | Latency | **Avoid** (templated TTS) |

## What this means for RoadMesh

**Top 5 to build and demo**
1. **C1 Proxy V2X.** One equipped phone already protects the cars behind it, which answers the cold-start objection on stage.
2. **C3 Siren-to-mesh corridor.** Uses audio AI and maps to the AIS-230 Emergency Vehicle Alert.
3. **C2 + C4 Fog pile-up shield.** A fog toggle plus a crashed or stopped node that keeps beaconing.
4. **C5 + C6 Peer-verified crash with a witness evidence pack.** The local LLM drafts an e-DAR-style report, tying into PM RAHAT.
5. **C17 Laptop digital twin over Office Kit,** with C18 voice reports as the input layer.

**Roadmap only (pitch, don't build):** C7 wrong-way flow consensus · C9 CRDT gossip · C14 Wi-Fi Aware RTT · C16 insurer score · C13 truck blind-spot.

**Thesis (why phones, why now, why us).** In 2024, 69.1% of India's 1.77 lakh road deaths were riders, pedestrians and cyclists, and the deadliest crash type was being hit from behind [1]. India's answer, AIS-230 V2V, reaches only vehicles built from October 2028 [6]; ADAS is in 8.3% of new cars and 1.4% of entry-level petrol and diesel cars [18], and no draft provision covers pedestrians or the existing fleet [6]. Phone V2X has been tried before, over the cloud (Nexar, 2016) [20] or Wi-Fi beacons (WiFiHonk, 2014) [30], but those phones could only report *themselves*. Today's NPUs run detection in about 10 ms (Ultralytics benchmark [35]), so a phone can perceive and speak for the unequipped car ahead, and the mandate has just fixed the message vocabulary to bridge to. We demo it offline on three iQOO phones, on India's best-selling phone family [19].

**Likely jury objections and answers**
| Objection | Evidence-backed answer |
|---|---|
| "Waze, Google Maps and Nexar already warn about hazards." / "Isn't this patented?" | They are cloud round-trips that need coverage and mostly manual reports. Nexar's cloud V2V reached "a few thousand users" [20], and the company has since moved to dashcam data [21]. Say it before they do: the brake alert over Bluetooth/Wi-Fi (ZTE) [38] and corroborated multi-hop SOS (AT&T) [39] are known ideas. What is new is a phone that *perceives* with camera and audio AI and broadcasts for road users with no equipment, received and relayed by any phone, with e-DAR/AIS-230 semantics |
| "AIS-230 C-V2X will solve this from 2028." | Only for new vehicles from 1 Oct 2028 [6]; the existing fleet and pedestrians stay uncovered, and C-V2X has no consumer-phone presence [28][29]. RoadMesh speaks the same four alerts [6], so it bridges to C-V2X rather than competing with it |
| "Phone radios and GPS are too weak for safety." | Prototypes reached 60–300 m, and phone GNSS error of 3–10 m suffices for awareness alerts but not lane-level collision avoidance [30]. So RoadMesh scopes to awareness, with camera-based TTC on the local phone, and measures latency live (BT5 in a moving car: 700–950 ms [36]) |

## Sources
1. MoRTH, *Road Accidents in India 2024* (OpenCity mirror of the morth.gov.in PDF): https://data.opencity.in/dataset/33d29ab0-f9e8-4fc7-b404-c93c1ed8e1b8/resource/30af828c-3513-4c74-a919-8daa708f077d/download/road-accidents-in-india-2024.pdf
2. OpenCity dataset page (source morth.gov.in, 18 Jun 2026): https://data.opencity.in/dataset/road-accidents-in-india-2024
3. ETV Bharat, RAI 2024 summary: https://www.etvbharat.com/en/bharat/road-accidents-in-india-in-2024-enn26061107541
4. OpenCity, pedestrian deaths by striking vehicle: https://opencity.in/every-fifth-road-death-in-india-is-a-pedestrian-a-ratio-that-isnt-dropping/
5. DT Next/PTI, RAI 2023: https://www.dtnext.in/news/national/172-lakh-people-died-in-480-lakh-road-accidents-in-2023-road-ministry-report-844831
6. Autocar India, V2V draft: https://www.autocarindia.com/industry/morth-proposes-mandatory-v2v-communication-systems-from-october-2028-440398
7. Business Standard, draft + G.S.R. 466(E): https://www.business-standard.com/india-news/govt-issues-draft-notification-for-phased-v2v-communication-systems-126080300822_1.html
8. ETV Bharat, V2V explainer: https://www.etvbharat.com/en/technology/v2v-communication-explained-morth-mandate-october-2028-enn26080403574
9. Swarajya, DoT spectrum: https://swarajyamag.com/amp/story/tech/dot-clears-spectrum-hurdle-for-vehicle-to-vehicle-and-vehicle-to-infrastructure-communication-systems
10. Business Today, V2V draft: https://businesstoday.in/auto/story/indias-next-road-safety-upgrade-govt-proposes-mandatory-v2v-communication-systems-in-vehicles-from-2028-546969-2026-08-03
11. News On Air, PM RAHAT terms: https://www.newsonair.gov.in/government-announces-launch-of-pm-rahat-scheme-for-cashless-treatment-of-up-to-rs-1-lakh-50-thousand-for-road-accident-victims
12. News On Air, PM RAHAT launch: https://www.newsonair.gov.in/pm-modi-launches-pm-rahat-scheme-for-cashless-road-accident-treatment
13. West Khasi Hills district (govt), PM RAHAT and TMS–eDAR integration: https://westkhasihills.gov.in/scheme/prime-minister-road-accident-victims-hospitalisation-assured-treatment-pm-rahat/
14. Vikaspedia, PM RAHAT (ERSS 112, eDAR + TMS 2.0): https://en.vikaspedia.in/viewcontent/schemesall/central-government-schemes/pm-rahat
15. NextIAS, e-DAR portal (2022): https://www.nextias.com/ca/current-affairs/16-04-2022/e-dar-e-detailed-accident-report
16. CoERS IIT Madras, iRAD/eDAR (search snippet; page behind CAPTCHA): https://coers.iitm.ac.in/irad/
17. Autocar India, Bharat NCAP 2.0: https://www.autocarindia.com/industry/bharat-ncap-20-to-come-into-effect-from-october-2027-gadkari-440653
18. JATO, ADAS adoption in India H1 2025: https://www.jato.com/resources/news-and-insights/adas-adoption-in-india-reaches-8.3-in-h1-2025
19. GSMArena/Counterpoint, India 2025 shipments: https://www.gsmarena.com/counterpoint_1_in_5_smartphones_shipped_in_india_in_2025_were_premium_models-news-71366.php
20. GovTech, Nexar V2V app network (2016): https://www.govtech.com/fs/app-vehicle-to-vehicle-network-seeks-to-predict-and-prevent-accidents.html
21. Wikipedia, Nexar: https://en.wikipedia.org/wiki/Nexar
22. BusinessWire, UF study of TravelSafely: https://www.businesswire.com/news/home/20211027005102/en/University-of-Florida-Study-Shows-TravelSafely-Connected-Vehicle-Smartphone-App-Can-Improve-Safety-in-School-Zones-and-for-Cyclists
23. BusinessWire, TravelSafely patent (title only; page 403): https://www.businesswire.com/news/home/20210914005184/en/Applied-Information-Granted-Patent-for-TravelSafely-Connected-Vehicle-Smartphone-App-to-Help-Prevent-Crashes-and-Improve-Safety-in-School-Zones
24. HAAS Alert, Safety Cloud: https://haasalert.com/news/safety-cloud-system-save-lives-on-the-road
25. City of Detroit, HAAS Alert deployment: https://detroitmi.gov/node/97081
26. road.cc, Spoke: https://road.cc/content/tech-news/spoke-provides-road-users-real-time-safety-warnings-291825
27. ITS International, Commsignia + Spoke: https://www.itsinternational.com/commsignia-applies-v2x-cyclist-safety
28. Qualcomm C-V2X 9150: https://www.qualcomm.com/products/automotive/qualcomm-c-v2x-9150
29. GSMA, BMW–Qualcomm V2P demo (title only; page 403): https://www.gsma.com/iot/resources/protecting-vulnerable-road-users-bmw-qualcomm-demonstration-video/
30. VRU V2X survey, mobile devices (arXiv 2010.15502): https://arxiv.org/abs/2010.15502
31. IEEE Spectrum, WiFiHonk: https://spectrum.ieee.org/wifihonk-smartphone-app-for-drivers-and-pedestrians-gets-you-out-of-the-way
32. Fluxguide, MobiCell project: https://www.fluxguide.com/en/projects/mobicell/
33. Crash-detection false positives (cited in SOLUTION.md; not re-verified): https://pmc.ncbi.nlm.nih.gov/articles/PMC10363817/
34. Google, smartphone hard braking (cited in SOLUTION.md; not re-verified): https://arxiv.org/abs/2202.01934
35. Ultralytics QNN benchmark (cited in SOLUTION.md; not re-verified): https://github.com/ultralytics/ultralytics/blob/main/docs/en/integrations/qnn.md
36. BT5 latency in a moving vehicle (cited in SOLUTION.md; not re-verified): https://arxiv.org/abs/2410.23366
37. FenderBender, Ford BLE pedestrian/cyclist app (23 Sept 2022): https://www.fenderbender.com/articles/17952-ford-designs-app-to-alert-drivers-of-pedestrians-cyclists
38. Google Patents, ZTE US11062604B2 "Vehicle warning method and apparatus": https://patents.google.com/patent/US11062604B2/en
39. Google Patents, AT&T US8660480B2 "Relaying out of range emergency information": https://patents.google.com/patent/US8660480B2/en
40. YAMNet TFLite model and labels (Hugging Face mirror): https://huggingface.co/thelou1s/yamnet/tree/main
41. sireNNet emergency-vehicle siren dataset (Mendeley Data): https://data.mendeley.com/datasets/j4ydzzv4kb
