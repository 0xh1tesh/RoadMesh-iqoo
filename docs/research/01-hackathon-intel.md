# RoadMesh at the iQOO Finale: hackathon intel and rubric maximisation (card A1)
*Researched 2026-10-08/09 · Sources: 24 · Confidence: Medium (schedule, rubric and device spec sheet High; HackTracker internals Medium-Low; BLE extended advertising, Coded PHY and Wi-Fi Aware must be probed on the phone)*

Citations like [S1] point to the numbered Sources list at the end. "Not yet verified" means I could not read a primary source in this pass. No agent-directed text was found on any fetched page.

## Bottom line
- **The schedule is confirmed.** The Finale runs 54% Red Light (~18.5 h) and 46% Green Light (~15.5 h) across 34 h of pure build time, Fri 19:00 → Sun 12:00. The scored checkpoints (no elimination) are Sat 10:00, Sat 19:00 and Sun 09:00 [S1][S6]. The last Green window ends **Sun 06:30**, so that is the real laptop code freeze. City handbooks warned that timings "may vary" and are announced at the venue [S12][S13], so check them again at the Fri 19:00 kickoff.
- **Some times are still open.** Submission cut-off and the start of jury presentations are "to be confirmed". Results are at 18:00 Sun [S1]. Repos lock before the Top 10 pitches [S2].
- **HackTracker is telemetry that sits on the phone.** Officially it captures "counts and durations only (no keystrokes, screenshots, or browsing)" [S2]. Participants quoting the printed city handbook say it also "reads model outputs, and logs inference calls, tokens and thermals in real time", and that tampering is "detected and will be penalised" [S12][S14][S15]. This is not on the public site and not yet verified for the Finale. A team's on-device log shows it runs as an **AccessibilityService** [S17]. → On all three loaners, run the camera, voice and on-device inference **inside our app**, genuinely and often.
- **The judges prefer on-device builds.** The printed handbook says "The highest on-device builds will be preferred for the Top 10", quoted independently by a Pune team and a Chennai team [S12][S13]. Keep cloud out of both the safety path and the AI path, and use the free credits only for coding help.
- **Office Kit (10%) also counts counts and durations** [S1]. Keep mirroring connected all 48 h on every loaner. In Red Light, drive the laptop from the phone. Move every APK, model, log and dashboard file through Office Kit. No developer API was found (not yet verified).
- **The jury and mentors are not announced.** The Finale panel shows "not announced" [S1][S6]. Kartikey Rawat (Qualcomm) mentored the **Bengaluru city battle**; he is not confirmed for the Finale [S1][S6].
- **The loaner's spec sheet differs from our design docs** [S24]. The iQOO 15 runs Android 16 / OriginOS 6, Bluetooth 6.0, Wi-Fi 7, dual-frequency GNSS and has an IR blaster. **NavIC and UWB are not listed**, and extended advertising, Coded PHY and Wi-Fi Aware need runtime probes. vivo has "no known" code-level fix for background killing [S23], so the settings must be changed by hand on each phone. On a city loaner, LiteRT Gemma could not use the GPU ("Can not find OpenCL library") and fell back to the CPU [S17], so **plan the local LLM on the NPU or CPU, not the GPU**.
- **The Finale prize is unconfirmed.** By arithmetic, ₹40 L minus 4 × ₹6 L leaves ₹16 L for "the Grand Finale and special awards" [S1]. An iQOO Instagram snippet says INR 15,00,000 [S20] (not yet verified). City battles gave a "Most iQOO Usage" award [S6]; whether the Finale has one is not yet verified.

## Findings

### 1. Finale specifics
**Run of show** [S1] (official home page, scraped 2026-10-08)

| Time | Item |
|---|---|
| Fri 17:00 | Check-in, ID cards, iQOO device distribution |
| Fri 19:00 | 48 h clock starts; keynote, jury/mentor intros, Green/Red format, Office Kit teach-in |
| Sat 10:00 | Checkpoint 1 · mentoring & evaluation, scored, no elimination |
| Sat 19:00 | Checkpoint 2 (19:00–22:00 on the spine [S6]) |
| Sun 09:00 | Checkpoint 3, "final build to 12:00" [S6] |
| Sun TBC | Final submission cut-off, "before jury presentations begin" |
| Sun TBC, post-lunch | Jury presentations: live pitch, demo on iQOO hardware |
| Sun 18:00 | Results & closing (presentations, results and phone collection run to 19:00) |

**Green/Red windows** (the site's own schedule data [S6], which matches the rendered spine [S1])

| Window | Light | h | Label (abridged) |
|---|---|---|---|
| Fri 19:00–21:00 | Green | 2 | Kickoff, both devices |
| Fri 21:00–00:00 | **Red** | 3 | Dinner from 21:00, phone only |
| Sat 00:00–03:00 | Green | 3 | Midnight activities |
| Sat 03:00–06:00 | **Red** | 3 | Overnight block |
| Sat 06:00–08:00 | Green | 2 | Breakfast from 07:30 |
| Sat 08:00–10:00 | **Red** | 2 | Morning block |
| Sat 10:00–11:00 | Eval | 1 | Checkpoint 1 |
| Sat 11:00–13:00 | Green | 2 | Build block |
| Sat 13:00–15:30 | **Red** | 2.5 | "Laptops closed" |
| Sat 15:30–16:30 | Green | 1 | Mentor round |
| Sat 16:30–19:00 | **Red** | 2.5 | Long afternoon block |
| Sat 19:00–22:00 | Eval | 3 | Checkpoint 2 |
| Sat 22:00–Sun 01:00 | **Red** | 3 | Night block |
| Sun 01:00–06:30 | Green | **5.5** | Largest laptop window |
| Sun 06:30–09:00 | **Red** | 2.5 | Dawn block |
| Sun 09:00–12:00 | Eval | 3 | Checkpoint 3 |

The windows sum to 15.5 h Green and 18.5 h Red, which matches the site's 54% / 46% [S1]. **RESEARCH.md's "54% Red" and its checkpoint times are confirmed.** SOLUTION.md's plan to code until Sun 09:00 is wrong: the laptop is Red from 06:30.

**Rules that matter**
- City-edition handbook (p.04, as quoted [S12]): Red Light means "Direct usage of laptops is restricted and will be monitored. Work on your phone, or access your laptop only via the Office Kit on your phone."
- Code must be written during the event and organisers "may verify a project was built inside the event window". OSS with attribution is fine. Repos lock before the Top 10 pitches; late work risks a penalty or disqualification [S2]. The Terms add that "pre-Event drafting of ideas is permitted" [S4].
- "Two scored evaluation rounds feed the Top 10 · final pitch decides the winners" [S1]. At city battles, the Top 10 was announced at 13:30 and pitches started 13:45, 3–5 min each [S6]. Finale Top 10 timing is not published.
- Finale tracks: Mobility, Community App, Smart Living, Productivity, Developer Tools, Open Innovation [S3].
- Finale registration closed 2026-10-05 23:59 IST; the shortlist was announced 2026-10-06 19:15 IST; venue "TBA, Bengaluru" [S6].
- Stack FAQ: "On-device inference targets the Snapdragon NPU (Sarvam, Gemma, Phi class models)" [S6].
- AI credits at city battles were $25 of OpenRouter credit per team (single source [S12]). For the Finale: not yet verified.
- /finale, /schedule, /faq and /mentors return 404. /register redirects to a login page [S7].

### 2. HackTracker

| Claim | Evidence | Status |
|---|---|---|
| Counts and durations only; no keystrokes, screenshots or browsing | Guide [S2] | Official |
| 15% = "Camera, voice, on-device AI in the build"; 10% = "Phone and laptop bridge use" | [S1][S2] | Official |
| Pre-installed on loaners; HackTracker teach-in at the opening; at Eval 1 "telemetry + jury scores begin to stack" | [S2][S6] | Official (city schedule) |
| "sits on the device through the build, reads model outputs, and logs inference calls, tokens and thermals in real time" | Handbook p.01 as quoted by a Pune team [S12]; also [S15] | 2 participants, same city; not yet verified for the Finale |
| "All crash and tamper logs are detected and will be penalised" | [S12] (Pune), [S14] (Bengaluru) | 2 cities |
| Runs as an Android AccessibilityService: "HackTracker AccessibilityService stayed up (pid 14104) for the whole session" | A Chennai team's test log on an iQOO 15 loaner, 2026-09-12 [S17] | Read in full; single source |
| Organiser tip: "30-hour surveillance of the phone"; the more genuine use, the better | [S12] | Single source |
| Organiser briefing: telemetry "can shift ranking by several positions" | [S16] | Single source |
| Scoring formula, how a team's phones are combined, idle vs active time | [S16] | Undisclosed |
| May 2026 rubric draft: "HackTracker buildScore. More phone-bridge time = more points" | Reskilll blog [S9] | Superseded rubric; suggests duration counts |

**Does it track our app or the whole phone?** The evidence points to a monitor that watches the whole phone (an accessibility service, "surveillance of the phone", counts and durations) and also counts inference calls and tokens [S12][S17]. How it attributes inference to a runtime or app is not disclosed. *Inference:* it can only register work that actually runs on the loaner. The safe strategy is to have our app itself use the camera, mic and NPU in the foreground, on every loaner, for long stretches.

### 3. vivo Office Kit
- **Official features** [S1]: screen mirror; shared clipboard; file transfer ("No size limit, no format restriction, no cable"); and remote control, which the site describes as the laptop keyboard and trackpad driving the phone. Requirements: Windows 10+ or macOS 10.14.6+; an iQOO phone on OriginOS 6, with Office Kit pre-installed and already paired on the loaner.
- **Red Light direction:** "you mirror the laptop, drive it from the keyboard, and move files without a cable" [S1]; "access your laptop only via the Office Kit on your phone" [S12]. So controlling the laptop from the phone is the expected Red Light workflow.
- **Developer API, SDK, intent or share target:** none found in organiser material or participant repos. Several teams simulated an integration instead. pc.vivoglobal.com [S22] renders client-side, so a text fetch returned only the title "vivo Office Kit". **Insufficient evidence. Design as if no API exists:** the app writes standard files (Downloads/share sheet) and a human moves them with Office Kit.
- **A genuine laptop role for RoadMesh** (*inference*): a laptop city dashboard fed by event-log JSON or CSV files exported from the phone and moved by Office Kit file transfer, plus the phone mirrored to the laptop or projector. This creates real transfer counts and mirroring time while the product genuinely benefits.

### 4. Past winners
- The Bengaluru city battle had 6 winning teams, all given a wild-card Finale entry (community recap, snippet only [S19]). A LinkedIn post claims 2nd runner-up in the student bucket [S21]. Which projects won, and why: **insufficient evidence found.**
- What the organisers signal: "A well-built simple product beats a broken complex one" [S1]. "Highest on-device builds will be preferred for the Top 10" [S12][S13]. "On-device LLM > Cloud API — Judges reward on-device intelligence over cloud calls" [S9]. Shortlisting weighs "novelty, tech impact, problem choice, idea/scope fit…, and phone-first fit" [S6].

### 5. Loaner device
- **iQOO 15.** The city handbook p.01, as quoted [S12]: "Snapdragon 8 Elite Gen 5, dedicated Q3 chip, 16 GB LPDDR5X, 14,000 mm² vapour chamber, 7,000 mAh". A venue standee photo is captioned "I code on iQOO 15" [S1]. Finale model: not yet verified (likely the same).
- **GSMArena iQOO 15 spec sheet** [S24]:

| Spec | Value | Matters for |
|---|---|---|
| OS | **Android 16**, OriginOS 6 | API level for foreground-service types and BLE permissions |
| SoC | Snapdragon 8 Elite Gen 5 (SM8850-AC, 3 nm), Adreno 840 GPU; 12 or 16 GB RAM | NPU and GPU inference |
| Bluetooth | **6.0**, A2DP, LE, aptX HD/Adaptive/Lossless, LHDC 5 | BLE mesh. LE Audio, extended advertising and Coded PHY are **not listed**; probe at runtime |
| Wi-Fi | 802.11 a/b/g/n/ac/6/7, dual-band, Wi-Fi Direct | Fallback transport. **Wi-Fi Aware (NAN) is not listed**; probe at runtime |
| GNSS | GPS L1+L5, GLONASS G1, BDS B1I+B1c+B2a, Galileo E1+E5a, QZSS L1+L5 | Dual-frequency confirmed. **NavIC is not listed**: drop the "NavIC" claim from SOLUTION.md unless the phone shows it |
| UWB | Not listed | Do not plan on UWB |
| Other | NFC, **IR blaster**, USB-C 3.2; accelerometer, gyro, proximity, compass | IMU fusion. No barometer listed |
| Cameras | 50 MP wide f/1.9 OIS + 50 MP 3x periscope + 50 MP ultrawide; 4K60, 1080p240 | Dash-cam perception |
| Battery | 7,000 mAh Si/C; 100 W wired; GSMArena "Active use score 21:47h" | 48 h soak |

- **The loaner's GPU path failed for LiteRT Gemma.** A Chennai team's log on an iQOO 15 loaner: "every run logs `Gemma-4-E2B-it unusable on GPU: Can not find OpenCL library` then loads CPU in ~1.3 s" [S17] (single source). SOLUTION.md's plan of Gemma 4 E2B on the GPU (~52 tok/s) is therefore at risk. Plan the LLM for the **NPU (QNN/Genie or the LiteRT NPU accelerator) or the CPU**, and test it in hour 1. The same log found that ML Kit silently adds the `INTERNET` permission through `transport-backend-cct` [S17]. If RoadMesh claims to be offline, remove that permission in the manifest merge.
- The Hexagon NPU generation is not on GSMArena. SOLUTION.md's "HTP v81" (from the Ultralytics docs) has not been re-verified in this pass.

### 6. Background killing on vivo/iQOO
- dontkillmyapp rates vivo **3/5** and lists **"No known solution on dev end yet"** [S23]. The settings steps it gives (menu paths vary by OS version; check them on the loaner):
  - **Autostart:** Settings > More settings > Applications > Autostart.
  - **Battery** (Android 13+): System settings → app → Battery; set battery optimisation to "Not optimized".
  - **Background power:** Settings → Battery → "Background power consumption management", and enable **"High background power consumption"** for our app.
  - **Lock the app** in recents (the lock icon in the app's recents menu).
- The code side therefore rests on standard Android: a foreground service with the right service types (SOLUTION.md) plus a runtime prompt to ignore battery optimisations (*inference*; verify it works on OriginOS on day 1).
- Participant practice: change these settings **for our app only**, and never touch HackTracker's settings [S12].

### 7. Qualcomm angle
- Kartikey Rawat, "Senior Developer Advocate & Senior Engineer, Qualcomm", was a Bengaluru city-battle mentor [S1][S6]. The Finale panel is not announced.
- AI Hub, QAIRT/QNN, the LiteRT NPU accelerator and Snapdragon Profiler: covered in prior research (SOLUTION.md), not re-verified in this pass. *Inference:* the most visible "real use of the hardware" is NPU inference with an on-screen overlay of runtime and latency.

## Rubric-maximisation table

| Criterion (weight) | Build / use / show | Evidence |
|---|---|---|
| End product quality (30%) | The core that is never cut (BLE alert + IMU hard brake + one camera detection) working in airplane mode on the iQOO; a foreground service that survives 48 h; a working demo at all 3 checkpoints | "Does it work, is it useful, would someone keep using it"; "simple … beats broken complex" [S1]; on-device builds preferred for the Top 10 [S12][S13] |
| Novelty & impact (20%) | Zero-hardware V2X for riders and pedestrians, phone-to-phone see-through warnings, peer-verified crash detection, offline SOS relay (SOLUTION.md N1–N4) | "Originality and real-world impact" [S1]; screening weighs novelty, problem choice and phone-first fit [S6] |
| HackTracker creative phone use (15%) | Camera YOLO on the NPU running whenever we test, plus an overnight soak; voice in (on-device ASR hazard reports) and out (TTS); the local LLM run on every event (incident report, "why" explanation); all 3 loaners actively used; never touch HackTracker | "Camera, voice, on-device AI" [S1]; logs inference calls and tokens [S12][S15]; tamper penalty [S12][S14] |
| Technical depth (15%) | NPU inference with a latency overlay; BLE extended advertising with relay; IMU-to-vehicle-frame fusion; a one-slide architecture; offline end to end | "Architecture, code quality, robustness, real use of the hardware" [S1]; "direct use of phone silicon is valuable" (organiser, single source [S16]) |
| HackTracker Office Kit (10%) | Mirroring connected all 48 h on each loaner; Red Light work driven through Office Kit; every APK, model and log moved by file transfer or clipboard; the laptop dashboard fed by Office Kit file drops; mirror to the projector in the pitch | Counts and durations [S1][S2]; Red Light rule [S12] |
| Demo & pitch (10%) | A 3–5 min pitch built for a jury that "sees your demo cold"; demo on the iQOO; a backup video recorded before 06:30 Sun | [S1]; submission is due before presentations [S1] |

## Options matrix

| Option | Evidence | Feasibility | Risk | Verdict |
|---|---|---|---|---|
| Local LLM runs on every event and trip summary (genuine "co-pilot") | Inference calls and tokens are logged [S12] | High | Heat and battery | **Recommended** |
| Dummy inference loops to farm telemetry | Tamper and crash logs penalised [S12][S14] | High | Ethics, disqualification | **Avoid** |
| Laptop dashboard fed by Office Kit file transfer | Counts and durations [S1] | High | Manual step | **Recommended** |
| Laptop dashboard over LAN or cloud backend | Probably not logged as bridge use (*inference*) | Medium | Wi-Fi in the venue | **Fallback** |
| Cloud LLM in the product (OpenRouter) | "Highest on-device builds preferred" [S12][S13] | High | Loses the Top 10 preference | **Avoid** (coding help only) |
| NPU via QNN/LiteRT; GPU delegate as fallback | FAQ: "targets the Snapdragon NPU" [S6] | Medium | Integration time | **Recommended** |
| Relying on an Office Kit developer API | None found | Low | Wasted hours | **Avoid** |
| Code only in Green windows, test only in Red | Last Green window ends 06:30 [S6] | High | Idle phones in Green | **Fallback**: keep phones busy in both phases [S12] |

## What this means for RoadMesh
- Re-plan the build around the real windows. The largest laptop block is **Sun 01:00–06:30**; everything must be installed on the phones by 06:30 Sunday.
- Make each Red window useful. Keep tunable settings (thresholds, TTS templates, relay parameters) in on-device JSON, editable from the phone. Field-test with three phones and drive the laptop through Office Kit.
- Make the camera, voice and LLM features *always in use* during testing, not demo-only extras.
- Give the laptop a real role: an analytics dashboard fed by Office Kit file drops, and the mirror used for the demo.
- Show something working at every checkpoint (Sat 10:00 BLE brake alert, Sat 19:00 see-through, Sun 09:00 full flow).

## Test on the loaner phone on day 1
1. Settings → About: confirm model, Android and OriginOS versions, Bluetooth version.
2. Run a BLE capability probe: `isLeExtendedAdvertisingSupported`, `isLeCodedPhySupported`, `getLeMaximumAdvertisingDataLength`; also check that the Wi-Fi Aware feature is present.
3. Check that HackTracker is present. **Do not change** its battery, notification or accessibility settings.
4. Use each Office Kit feature once in each direction, and time it.
5. Run a YOLO inference on the NPU (QNN HTP) and on the GPU; record the latencies. Then load the local LLM and check whether the GPU/OpenCL path exists; it failed on a city loaner [S17]. If it fails, switch to the NPU or CPU.
6. Check that offline TTS voices exist (en, hi, kn) and that ASR works in airplane mode.
7. Exempt only our app from background killing, then run a foreground service with BLE scanning for 10 min with the screen off.
8. Ask the organisers: the Finale Red/Green windows, submission cut-off, what counts as demo assets, how a team's HackTracker data is combined, whether there is a "Most iQOO Usage" award, and AI credits.

## Sources
1. **S1** https://iqoo.reskilll.com/ — official home: rubric, spine, run of show, Office Kit, panels (scraped 2026-10-08)
2. **S2** https://iqoo.reskilll.com/guide — official rules: HackTracker "counts and durations", build rules
3. **S3** https://iqoo.reskilll.com/cities — Finale track set
4. **S4** https://iqoo.reskilll.com/terms — Terms (updated 10 Sep 2026)
5. **S5** https://iqoo.reskilll.com/privacy — Privacy policy (no HackTracker detail)
6. **S6** https://iqoo.reskilll.com/assets/index-CuUjwVRQ.js — site bundle: Green/Red arrays, FAQ answers, panels, deadlines
7. **S7** https://iqoo.reskilll.com/register?city=finale — redirects to /login (behind login)
8. **S8** https://reskilll.com/blogs/how-to-win-iqoo-city-battles-strategy-guide-phone-first-ai-hackathon/ — organiser strategy blog (17 Aug 2026)
9. **S9** https://reskilll.com/blogs/how-to-prepare-for-iqoo-hackathon-2026-a-complete-guide-for-participants/ — earlier rubric draft (29 May 2026)
10. **S10** https://reskilll.com/blogs/on-device-ai-hackathons-build-local-llms-snapdragon-npu-2026/ — organiser on-device AI blog
11. **S11** https://reskilll.com/blogs/iqoo-hackathon-2026-india-phone-first-ai-hackathon-iqoo-reskilll/ — organiser overview blog
12. **S12** https://github.com/inslot2525-ctrl/EyesUp — Pune team; quotes the printed handbook (PROGRESS.md E-002, gameplan §3–4a, RED_LIGHT_PLAYBOOK)
13. **S13** https://github.com/ARCHIT3024/ShelfSense — Chennai team; quotes the playbook's on-device Top 10 preference
14. **S14** https://github.com/manasareddycherukupalli-cyber/safetyeye-live — Bengaluru team; tamper rule from the printed playbook
15. **S15** https://github.com/Physics0070/RetinaSight-Android — Pune team; "HackTracker logs inference calls/thermals"
16. **S16** https://github.com/muhammedsayeedurrahman/smartlease-edge-chennai — Chennai team; HackTracker unknowns and organiser briefing
17. **S17** https://github.com/arjun7n9s/Blackout — Chennai team's iQOO 15 test log (C-Outputs/SUMMARY.md): HackTracker AccessibilityService, OpenCL missing for LiteRT GPU
18. **S18** https://github.com/prajwal-gunnala/maynards — snippet: Finale amount "disputed (₹16L or ₹25L)"
19. **S19** https://community.iqoo.com/in/thread/169162 — Bengaluru recap (snippet)
20. **S20** https://www.instagram.com/p/DeJ6FZZFGGf/ — Finale "INR 15,00,000" (snippet)
21. **S21** https://www.linkedin.com/posts/reuben-anthony07_iqoohackathon-reskilll-hackathon-activity-7502976610359402496-_RLz — snippet
22. **S22** https://pc.vivoglobal.com/ — official Office Kit download (renders client-side; only the title was readable)
23. **S23** https://dontkillmyapp.com/vivo — vivo background-kill workarounds; "No known solution on dev end yet"
24. **S24** https://www.gsmarena.com/vivo_iqoo_15_5g-14198.php — iQOO 15 full specifications
