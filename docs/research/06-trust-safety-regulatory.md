# RoadMesh A6: trust, privacy, law, alert design and battery

*Researched 2026-10-08 to 2026-10-09 · Sources: 39 · Confidence: Medium (security and privacy High; Indian law and DPDP from secondary sources; alert timing and phone battery numbers thin)*

Research, not legal advice. "Not yet verified" = not read from a primary source by this card.

## Bottom line

- **A signature proves integrity, not honesty.** With self-made keys one attacker can mint unlimited identities (Sybil). Standard V2X binds every signing key to a CA-issued pseudonym certificate: IEEE 1609.2 uses a 64 B ECDSA P-256 signature plus a 170 B certificate, or an 8 B certificate digest on 4 of every 5 messages [3]. Finale: Ed25519-sign every alert in extended adverts; pitch certificates as the roadmap.
- **One unverified packet must never trigger a loud warning.** Use cheap plausibility checks (range, speed vs displacement, freshness, rate) plus corroboration (a second sender or the receiver's own sensors). VeReMi found single checks catch different attacks and recommends fusion [1].
- **Add a signed timestamp.** The 24-byte packet has no time field, so a captured "hard brake" can be replayed later once its msgId leaves the dedup cache. 1609.2 headers carry generation time and location for this reason [3].
- **RSSI is weak evidence in vehicles.** GAEN field studies found little RSSI-distance correlation in a tram and ±10 dB swings in a bus [12][13]. Use RSSI only as a loose "could be near" check.
- **Copy the Exposure Notification privacy pattern** (non-connectable adverts, identifier changed in lockstep with the BLE address) [10]. Android randomises its own address rotation at 5-15 min [11], and precise positions in every packet let an eavesdropper relink pseudonyms (62-73% even after mix-zones) [15].
- **Battery:** GAEN needs discovery only within 5 minutes and scans opportunistically [10]; RoadMesh scans continuously, so GAEN battery numbers are a floor. Measure on the iQOO on day 1; camera only in dash mode on a charger.
- **Law is a grey zone; design to the strictest reading.** MV Act s.184 makes *handheld* use dangerous driving (₹1,000-₹5,000 and/or 6-12 months, first offence) [27][28]; the 2020 Driving Regulations allow a dashboard-fixed phone "solely for route navigation" [29]. Mount it, auto-start, speak alerts, lock all touch while moving, keep glances under NHTSA's 2 s / 12 s [35]. Not legal advice.
- **Warnings work only if trusted.** IIHS: factory FCW alone cut rear-end crash rates 23% [33]. Few, corroborated, spoken alerts protect that trust; claim reach, not an effect size.

## Findings

### 1. Spoofed "phantom brake" alerts

**What the standards do, and what a phone can do offline**

| Mechanism | IEEE 1609.2 / SCMS | Phone, offline |
|---|---|---|
| Signature | ECDSA P-256 mandated; 64 B signature, 65 B public key [3] | Ed25519 (64 B signature) via `Signature.getInstance("Ed25519")`, Android API 33+ [8] |
| Credential | Pseudonym certificate: 170 B full or 8 B HashedId8 digest; full certificate in every 5th BSM (SAE J2945/1) [3] | Needs a CA that issues certificates while online; verification then works offline (inference) |
| Privacy | Pool of concurrently valid pseudonym certificates, rotated; linkage-based revocation lists [3]. Up to 20 certificates per week (vendor blog, single source) [4] | Rotate the sender ID (section 2) |
| Freshness | Header (~28 B) carries generation time and location [3] | Add a time field (design rule 2) |
| Misbehaviour detection | Plausibility and ML research below | All local, feasible |

ETSI TS 103 097 / 102 940 details: not yet verified.

**Ed25519 cost, native code** (interquartile-mean cycles, 59-byte message) [7]:

| Core | Library | Sign | Verify | At 3 GHz (my arithmetic) |
|---|---|---|---|---|
| Cortex-A76 | OpenSSL | 98,058 | 328,523 | ~33 µs / ~110 µs |
| Cortex-A76 | lib25519 | 54,872 | 242,570 | ~18 µs / ~81 µs |
| Cortex-A53 | OpenSSL | 198,326 | 550,366 | ~66 µs / ~183 µs |

The 8 Elite Gen 5 cores are newer, so expect equal or better (inference). A pure-JVM implementation will be slower: insufficient evidence found for a number. Even at 1 ms per verify, 100 alerts/s costs about 10% of one core (estimate).

**Size budget:** 24 B core + 64 B signature = 88 B, more than a 31 B legacy advert, so signed mode needs extended advertising [25]. Check `getLeMaximumAdvertisingDataLength()` on the loaner (card A2).

**Keys**

| Option | Gives | Limits |
|---|---|---|
| Per-install Ed25519 key | Integrity | Sybil: unlimited self-made keys |
| Keystore hardware key | Non-extractable key | StrongBox: RSA-2048, AES, ECDSA/ECDH P-256, HMAC, 3DES, no Ed25519 [9]; Curve25519 in KeyMint per AOSP commits, API level not yet verified [22]; signing latency not yet verified |
| Key attestation | Proof the key is in hardware Google trusts [21] | Verify on a server with the revocation list (network) [21] |
| Production path | Attestation-gated CA issues short-lived pseudonym certificates; phones verify offline (SCMS pattern [3][4]) | CA operations; not a 48 h build |

**Replay and Sybil**
- msgId dedup only catches replays still in the cache (inference). A signed timestamp plus claimed position closes the gap [3].
- VeReMi Extension models replay ("DoS disruptive") and Sybil variants (random pseudonyms; neighbours' pseudonyms) [5].
- RSSI signalprints without a trusted authority (Mason test, on Android phones) removed 99.6-100% of Sybil identities in offices, 91% in a cafeteria, 96% outdoors, but accepted only 61% of honest identities outdoors because of motion [6]. RSSI profiling fails when attackers move or vary transmit power [14].

**Misbehaviour detection evidence**

| Study | Setup | Result |
|---|---|---|
| VeReMi [1] | 225 simulations, 1 Hz beacons, 5 position attacks, 35-519 vehicles | Acceptance-range threshold (100-800 m) works against false/random positions, struggles with offsets and eventual-stop; simple speed check works against random offsets, fails against constant offset; "complementary", so fuse |
| So, Sharma, Petit 2018 [2] | VeReMi | KNN/SVM over plausibility-check features: precision up >20%, recall within 5% (search abstract; not yet verified) |
| VeReMi Extension, Kamel 2020 | Sensor-error model, 19 attack types | Recall 82.3%, precision 99.1% as cited by [5] (secondary; not yet verified) |
| PAMPOS 2026 [16] | VeReMi++, 19 attacks, trained on benign data only | AUC up to 0.98, F1 up to 0.95 |

VeReMi's authors warn against simulation data as the sole basis for ML detectors [1].

### 2. Privacy

**Exposure Notification precedent (GAEN Bluetooth spec v1.2) [10]**
- Non-connectable `ADV_NONCONN_IND`; random non-resolvable address rotated at a random 10-20 min.
- Address, 16 B Rolling Proximity Identifier and 4 B encrypted metadata "shall be changed synchronously so that they cannot be linked".
- Advertising 200-270 ms; discovery within 5 min; opportunistic scanning; controller duplicate filters "to prevent excessive power drain".
- Scale: the NHS COVID-19 app's contact tracing averted about 1 million cases in its first year [17].

**Android specifics**
- CDD [C-3-5]: RPA timeout ≤15 min, randomised between 5 and 15 min [11].
- A non-default own-address type needs a privileged permission (AOSP source via search; not yet verified) [18]. An app's fixed 10-min ID timer therefore straddles OS address changes, so an observer can bridge old and new IDs (inference). Test: recreate the AdvertisingSet when rotating the ID and check with a second phone whether the address changes.

**Pseudonyms vs payload.** Simple pseudonym change is defeated by linking attacks [19]. Trackers linked 73% (non-rush) and 62% (rush hour) of pseudonyms across mix-zones [15]; adaptive silent periods trade privacy against FCW quality [20]. The packet's 1e-7° grid is ~1.1 cm (111 km per degree), far finer than phone GNSS, so it adds tracking value but no safety value (inference).

**DPDP Act 2023 and Rules 2025** (secondary sources; gazette text not opened)

| Provision | Text (paraphrased by source) | Source |
|---|---|---|
| s.2(t) | "Personal data" is data about an individual "identifiable by or in relation to such data" | [37] |
| s.17(2)(b) | Act does not apply to processing for research, archiving or statistical purposes if the data is not used for a decision specific to a Data Principal and meets prescribed standards | [38] |
| Rule 16 + Second Schedule | Prescribes those standards | [38] |
| Commencement | Rules notified 13 Nov 2025; Rule 4 from 13 Nov 2026; Rules 3, 5-16, 22, 23 from 13 May 2027 | [39] |

Implications (inference, not legal advice): trajectories can make a rider identifiable "in relation to" the data, so treat broadcasts as personal data and minimise them. The heatmap fits s.17(2)(b) only if aggregated, never used for decisions about a person, and processed to Second Schedule standards. SOS and crash reports need notice and consent (legitimate-use grounds not verified here).

### 3. Law and distraction

| Provision | What it says | Source |
|---|---|---|
| MV Act 1988 s.184 (driving dangerously), as amended 2019, in force 1 Sep 2019 | The Explanation lists "use of handheld communications devices while driving" as dangerous driving | [27][28] |
| s.184 penalty | First offence: 6 months to 1 year imprisonment and/or ₹1,000-₹5,000 fine; repeat within 3 years: up to 2 years and/or up to ₹10,000 | [27][28] |
| Motor Vehicles (Driving) Regulations 2017, reg. 37 proviso, inserted by the Amendment Regulations 2020 (G.S.R. 586(E), 25 Sep 2020, in force 1 Oct 2020) | Hand-held devices may be used "solely for route navigation", fixed on the dashboard so as not to disturb the driver's concentration (a Driving Regulation, not CMVR) | [29][30] |
| NHTSA Visual-Manual Driver Distraction Guidelines (2013, Phase 1, voluntary, original-equipment devices) | Secondary tasks should need eyes off road no more than 2 s per glance and 12 s total per task; criteria set at the 85th percentile of test drivers | [35][36] |

Implication (inference, not legal advice): the offence turns on *handheld* use, and the 2020 proviso only contemplates a dashboard-fixed phone for route navigation. A hazard-warning app is a grey zone, so RoadMesh must be cradle-mounted, auto-start, audio-first, need zero touches while moving, and ideally run alongside or inside the navigation view. Say this openly to the jury.

### 4. Alert human factors

| Evidence | Finding | Source |
|---|---|---|
| IIHS (Cicchino), police-reported crashes 2010-2014, 22 US states | FCW alone cut rear-end crash rates 23%; FCW + autobrake 39%, and rear-end injury crashes 42% | [33][34] |
| IIHS, FCW + autobrake (as reported in the same coverage) | Front-to-rear crashes −50%, with injuries −56% | [33] |
| ISO 15623 timing, TTC thresholds, alarm fatigue, audio + haptic | Not yet verified | - |

Caveats (inference): these are factory radar/camera systems; a phone-based FCW has no published effectiveness figure (insufficient evidence found). Use them as "warnings work when trusted", not as RoadMesh's expected benefit. The figures come from news coverage of IIHS work; the original papers were not opened.

### 5. Battery and thermal

| Evidence | Finding | Source |
|---|---|---|
| GAEN design | Discovery within 5 min, opportunistic scanning, controller duplicate filters "to prevent excessive power drain" | [10] |
| GAEN in the field | Battery-drain complaints reached national news (Google shipped a fix for Ireland's app in 2020); systematic per-day measurement: insufficient evidence found | [31] |
| BLE radio, chip level | nRF51822: continuous scanning ~40 mW, broadcasting ≤600 µW. A dedicated SoC, not a phone; phone-level mW: insufficient evidence found | [32] |
| Camera + NPU power | Not yet verified (card A4) | - |

Implications (inference): scanning, not advertising, dominates radio cost; RoadMesh's continuous low-latency scan costs far more than GAEN's 5-minute discovery. Strategies: scan only while moving (activity or speed gate), drop to a low-duty scan when parked, use hardware scan filters and duplicate filtering [10], and run the camera only in dash mode on a charger. Measure mAh per hour on the loaner with the screen off before claiming any number.

## Design rules

1. **Sign every alert** in extended mode (Ed25519 over header, payload and time); unsigned legacy alerts get the lowest trust tier [3][8].
2. **Freshness:** signed 2-byte time (0.1 s within a GNSS-synced minute); drop packets older than ~2 s or future-dated (threshold to tune); dedup on (sender, msgId) [3].
3. **Trust tiers:** one uncorroborated sender gives a quiet caution; loud TTS needs corroboration (second sender, own IMU or camera) or a CA-backed credential [1].
4. **Plausibility per sender:** claimed position within radio range plus GNSS error; speed vs displacement; heading continuity; per-sender rate limit [1][5].
5. **RSSI as a soft score only** [6][12][13][14].
6. **Rotate the sender ID with a fresh advertising set**; never broadcast names or stable IDs; adverts non-connectable [10][11].
7. **Minimise payload precision** to what the safety function needs [15].
8. **Never let the LLM or cloud gate an alert.** The cloud gets aggregated, ID-free counts only, never used for decisions about a person (DPDP s.17(2)(b) logic) [38]; SOS and crash reports are opt-in with notice [37].
9. **While moving: no touch input, no text entry, no video; one glanceable icon plus speech.** Any visual task must fit ≤2 s per glance and ≤12 s total [35][36]; the phone stays cradle-mounted [27][29].
10. **Measure before claiming battery numbers;** gate scanning by motion and the camera by charging [10][32].

## Jury Q&A ammunition

| Hard question | Evidence-backed answer | Source |
|---|---|---|
| Can I fake a "hard brake" and cause a pile-up? | Every alert is signed, timestamped and range- and speed-checked; one uncorroborated sender only gets a quiet caution. Standards V2X layers signatures and misbehaviour detection the same way. | [1][3] |
| A signature from a key you made yourself proves nothing. | Agreed, that is the Sybil gap. Production follows SCMS: attestation-gated enrolment issues a few short-lived pseudonym certificates; phones verify offline. | [3][4][21] |
| How do you stop replays? | Signed time and position; stale or far-away packets are dropped; msgId dedup catches exact copies. 1609.2 carries generation time and location for the same reason. | [3] |
| Signing is too slow for safety. | Ed25519 verify is ~243k-329k cycles on a Cortex-A76, ~0.1 ms at 3 GHz, tiny next to 100+ ms of radio latency. | [7] |
| Why not use RSSI to verify position? | In-vehicle RSSI barely tracks distance (tram, bus studies); motion halved RSSI Sybil-test acceptance. We use it only as a soft check. | [6][12][13] |
| Doesn't broadcasting location let people track me? | No names or stable IDs; IDs rotate as in Exposure Notification. Honestly, a dedicated eavesdropper can still link trajectories (62-73% in research), so we minimise precision and keep no central log. | [10][15] |
| Is it legal under DPDP? | We treat broadcasts as personal data and minimise them (s.2(t) covers data identifiable "in relation to" a person). The city heatmap is aggregate-only and never drives decisions about an individual, the s.17(2)(b) research/statistics pattern; SOS sharing is opt-in. Most Rules apply from May 2027; we design to them now. Not legal advice. | [37][38][39] |
| Phone use while driving is illegal. | Section 184 targets *handheld* use (₹1,000-₹5,000 and/or 6-12 months, first offence). The 2020 Driving Regulations allow a dashboard-fixed phone for navigation without distraction; RoadMesh is mounted, auto-starts and needs no touch while moving. Not legal advice. | [27][28][29] |
| Won't the app distract? | It is built to NHTSA's visual-manual limits (≤2 s per glance, ≤12 s per task, for in-car devices): alerts are spoken, the screen is a glanceable icon, and every interactive feature locks while moving. | [35][36] |
| False alarms will make people uninstall it. | Agreed, so loud alerts need corroboration (trust tiers) and everything else is a quiet cue. Warnings only pay off when drivers trust them: IIHS found factory FCW alone cut rear-end crash rates 23%. | [1][33] |
| Do warnings even reduce crashes? | IIHS police-report data: FCW alone −23% rear-end crash rates, FCW + autobrake −39%. Phone-based FCW has no published figure yet, so we claim reach, not effect size. | [33][34] |
| Battery? | Advertising is cheap; scanning is the cost (chip-level ~40 mW scanning vs ≤600 µW broadcasting). We scan only while moving, use hardware filters as GAEN does, and run the camera only when charging. We will show the measured mAh/h from the loaner. | [10][32] |
| Nobody else has the app (cold start). | Single-phone value (camera FCW, crash detection) from day one; opt-in proximity apps showed effect at national scale. | [17] |
| Who is liable if it misses a hazard? | Advisory only; driver stays responsible. Legal basis not yet verified. | - |
| 500 phones at one junction? | GAEN was specified for "a large volume of broadcasters in public spaces" with controller filtering; VeReMi detection was tested at 491-519 vehicles. Radio collisions: card A2. | [1][10] |
| Why not just use the cloud? | Works with no coverage; safety V2X uses direct broadcast, not servers; no central location log. Cloud only for aggregates. | [3] |
| How does this relate to the 2028 AIS-230 V2V mandate? | Complementary: covers existing vehicles, two-wheelers and pedestrians that a new-vehicle OBU mandate misses, using the same message semantics. Mandate details per earlier research; not yet verified by A6. | [26] |
| Why Ed25519, not ECDSA like 1609.2? | Same 64 B signature, native on Android 13+; a gateway can re-sign as ECDSA for OBUs. | [3][8] |

## Options matrix

| Option | Evidence | Feasibility | Risk | Verdict |
|---|---|---|---|---|
| Ed25519 per-install key, every alert signed (extended adverts) | [7][8] | High | Sybil | **Recommended** (demo) |
| Unsigned legacy adverts + plausibility only | [1] | High | Spoofable | **Fallback** |
| Attestation-gated pseudonym certificates (SCMS-lite) | [3][4][21] | Low in 48 h | CA operations | **Roadmap** |
| Hardware Keystore signing per message | [9] | Unverified latency | Slow, no Ed25519 in StrongBox | **Avoid** per message |
| RSSI as hard reject | [6][12][13] | High | False rejects | **Avoid**; soft score = Fallback |
| Corroboration-gated loud alerts | [1] | High | Missed lone true alert | **Recommended** |
| ID rotation via fresh advertising set | [10][11] | Test day 1 | Address may not change | **Recommended** |
| Dash-mounted, auto-start, zero-touch-while-moving UI | [27][29][35] | High | Navigation-only wording of reg. 37 | **Recommended** |
| Continuous camera + NPU on battery | [32] (radio only); camera power not verified | Medium | Drain, heat | **Avoid**; camera only on charger = Recommended |
| Aggregate-only cloud heatmap | [37][38] | High | Re-identification if cells are small | **Recommended** |

## Sources

1. https://arxiv.org/abs/1804.06701 VeReMi dataset and plausibility detectors (read)
2. https://independent.academia.edu/PrinkleSharma So, Sharma, Petit 2018 ICMLA (search abstract only)
3. https://arxiv.org/abs/2608.05087 IEEE 1609.2 SPDU, certificate and J2945/1 sizes (read)
4. https://autocrypt.io/v2x-pki-certificates-butterfly-key-expansion-implicit-certificates/ SCMS 20 certificates/week, butterfly keys (vendor, search summary)
5. https://arxiv.org/abs/2501.15252 VeReMi Extension attack list, F2MD plausibility features (read)
6. https://arxiv.org/abs/1403.5871 Mason test, RSSI Sybil detection on phones (read)
7. https://lib25519.cr.yp.to/lib25519-20260614/doc/html/speed.html Ed25519 cycle counts (read)
8. https://developer.android.com/reference/kotlin/java/security/Signature Ed25519 API 33+ (read)
9. https://developer.android.com/privacy-and-security/keystore StrongBox algorithms (read)
10. https://covid19-static.cdn-apple.com/applications/covid19/current/static/contact-tracing/pdf/ExposureNotification-BluetoothSpecificationv1.2.pdf GAEN Bluetooth spec (read)
11. https://android.googlesource.com/platform/compatibility/cdd/+/08c2e5d%5E%21/ CDD RPA rotation (read)
12. https://pmc.ncbi.nlm.nih.gov/articles/PMC7526892/ GAEN in a tram (abstract)
13. https://arxiv.org/abs/2006.08543 GAEN in a bus (abstract)
14. https://arxiv.org/abs/2410.04063 power-controlled Sybil vs RSSI profiling (abstract)
15. https://arxiv.org/abs/2012.06666 pseudonym linking across mix-zones (abstract)
16. https://arxiv.org/abs/2605.06833 PAMPOS (abstract)
17. https://pmc.ncbi.nlm.nih.gov/articles/PMC9947127/ NHS COVID-19 app first year (abstract)
18. https://git.aicp-rom.com/packages_modules_Bluetooth.git/plain/framework/java/android/bluetooth/le/AdvertisingSetParameters.java AOSP mirror (search summary)
19. https://arxiv.org/abs/1704.00679 pseudonym-changing survey (abstract)
20. https://arxiv.org/abs/1607.07656 context-based pseudonym changing (abstract)
21. https://developer.android.com/privacy-and-security/security-key-attestation key attestation (read)
22. https://gerrit.omnirom.org/plugins/gitiles/android_system_security/+/e36d8b7e8f59cf47bf887679fd67b1e6e0a44966%5E%21 keystore2 Curve25519 (AOSP mirror, search summary)
23. https://developer.android.com/reference/kotlin/android/bluetooth/le/AdvertisingSetParameters.Builder advertising parameters (read)
24. https://doi.org/10.1038/s41586-021-03606-z Wymant 2021, NHS app (numbers not read)
25. C:\Users\hites\Desktop\PROJ_MAIN\gen\SOLUTION.md packet format and legacy-advert limit (internal)
26. https://www.autocarindia.com/industry/morth-proposes-mandatory-v2v-communication-systems-from-october-2028-440398 AIS-230 draft (via SOLUTION.md; not verified by A6)
27. https://www.livelaw.in/amp/know-the-law/use-of-mobile-phone-while-driving-is-now-an-offence-147890 s.184 after the 2019 amendment (search summary)
28. https://lawx.in/acts/section/46/470/4481 s.184 text mirror (search summary; indiacode PDF not opened)
29. https://www.acma.in/uploads/otherdocmanager/Final%20Notification-GSR%20586(E)-25Sept2020-DrivingRegulation2020.pdf G.S.R. 586(E), Driving (Amendment) Regulations 2020 (search summary)
30. https://www.deccanherald.com/amp/story/india%2Fwhile-driving-mobile-phones-must-be-used-only-for-navigation-govt-893809.html MoRTH navigation-only rule, news (search summary)
31. https://irishtimes-irishtimes-prod.cdn.arcpublishing.com/business/technology/google-to-roll-out-software-fix-to-remedy-issue-with-covid-app-1.4325981 GAEN battery-drain fix, news (search summary)
32. https://arxiv.org/abs/2103.06221 nRF51822 scan vs broadcast power (search summary)
33. https://www.kbb.com/car-news/iihs-study-finds-automatic-braking-cuts-collisions-39-percent/2100000239/ IIHS FCW and autobrake figures, news (search summary)
34. https://stnonline.com/news/study-collision-warning-automatic-braking-systems-prevent-crashes/ IIHS Cicchino study, news (search summary)
35. https://www.nhtsa.gov/document/visual-manual-nhtsa-driver-distraction-guidelines-vehicle-electronic-devices-docket-no NHTSA Visual-Manual Guidelines (search summary)
36. https://www.govinfo.gov/link/fr/78/24861 Federal Register 78 FR, 2013 guidelines (search result; not opened)
37. https://amlegals.com/what-is-personal-data/ DPDP s.2(t) definition, law-firm summary (search summary)
38. https://dpdp.myndsolution.com/wiki/rules/rule-16-exemption-from-act-for-research-archiving-or-statistical/ s.17(2)(b) and Rule 16 (search summary)
39. https://www.teamleaseregtech.com/updates/article/49364/meity-issued-a-notification-regarding-the-commencement-of-the-dpdp-act/ DPDP commencement dates (search summary)
