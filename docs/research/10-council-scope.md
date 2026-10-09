# Council: what RoadMesh builds at the Finale
*ECC council, 2026-10-09. Four voices: Architect (in context), Skeptic, Pragmatist, Critic (independent subagents with only the question and a compact brief)*

**Question:** What should team JSONs commit to build and demo at the 48-hour Finale, and what is explicitly cut, to maximise the score and minimise the chance of a failed live demo?

**Architect (draft, written before reading the others):** a never-cut core (BLE mesh, IMU+GNSS hard brake, multilingual TTS and haptics, demo track, stats screen, Office Kit dashboard) plus three signature features: proxy V2X camera, peer-verified crash with an LLM report, and a siren corridor. Fog as a toggle.
*Why:* each signature uses a different phone capability that HackTracker counts, and each maps to an AIS-230 application or an ETSI code.

**Skeptic:** feature count is the wrong lever. About 40% of the score rides on the live demo working and 25% on usage telemetry. Commit to one live chain (dash-cam NPU detection → BLE relay → local-language voice and haptics) plus a measured-stats screen.
*Why:* a 3 to 5 minute pitch shows two things anyway. Crash detection starts from a detector with 71% false positives and can't be staged honestly.

**Pragmatist:** core plus one signature (proxy V2X), with two live hops on three iQOOs. Siren only if proxy V2X passes 10/10 rehearsals by Sat 19:00. Cut crash and SOS, spoken reports, the live dashboard and any LLM on the demo path.
*Why:* about 46 laptop person-hours against an estimated 60 needed for the draft. Red Light is for field testing and rehearsal, not features.

**Critic:** core plus proxy V2X only, with the pocket beacon in and the dashboard out. Siren only if the core passes a 15-minute soak at Sat 19:00.
*Why:* the draft is about twice what three people can integrate before the Sun 06:30 freeze. The pocket beacon needs screen-off scanning, which Android and OriginOS fight.

### Verdict
- **Consensus:** one signature feature, proxy V2X (camera on the NPU speaking for unequipped road users), on a never-cut core. Crash plus LLM report, spoken hazard reports and the live dashboard come off the build. All three independent voices aligned against the draft, so the recommendation changed.
- **Strongest dissent:** the Critic warns that cutting the LLM leaves zero token telemetry if HackTracker counts tokens. The Skeptic warns HackTracker may not register a QNN-delegate path at all. Both are answered by asking the organisers at kickoff what HackTracker counts, and smoke-testing it in the first hour.
- **Premise check:** the Skeptic reframed the question from "which features" to "which chain must never fail on stage". Accepted.
- **Recommendation:**
  - **Core (never cut):** protocol v2 frozen at kickoff; BLE extended-advertising broadcast with ≤3-hop relay; relevance and trust-tier arbiter; templated TTS with haptics; IMU hard brake gated by speed (GNSS or demo track); pocket/VRU presence beacon tested screen-off; position source (GNSS, trace replay, disclosed demo track); disclosed neighbour allowlist for staged hops; stats screen with JSON export moved by Office Kit.
  - **Signature:** proxy V2X / see-through. YOLO on the NPU (GPU fallback, QNN capped at about 4 h), a pedestrian-in-path event and a looming lead vehicle (log-scale TTC). A clip-replay camera input keeps the stage demo deterministic and is disclosed.
  - **Gated stretch, in order:** (1) siren-to-mesh corridor (MediaPipe YAMNet), if the core passes a 15-minute soak and proxy V2X passes 10/10 rehearsals; (2) fog mode as a config flag (thresholds, TTL); (3) an LLM "why did it warn me" or trip debrief, only if the GenieX sample runs on the loaner without fuss and never on the alert path; (4) a static laptop log viewer fed by Office Kit file drops.
  - **Roadmap only (pitch slide):** peer-verified crash + witness pack + e-DAR report; spoken hazard reports in Hindi and Kannada; live digital twin; wrong-way flow consensus; CRDT hazard gossip; Wi-Fi Aware ranging; CA-issued pseudonym certificates.
- **First-hour questions for organisers:** what HackTracker counts as on-device AI and voice (any API, or only some); whether a vendor sample app pattern and pre-trained weights count as allowed OSS with attribution; the submission cut-off; whether Office Kit mirroring works without internet.

### Amendment after the venue booklet (9 Oct, integrator)
The printed playbook makes two statements ([14](14-venue-booklet.md)):
- HackTracker "reads model outputs, and logs inference calls, tokens and thermals in real time".
- Its tip to win: on-device LLMs earn brownie points, and "the highest on-device builds will be preferred for the Top Teams Pitches".

That confirms the Critic's dissent, and the verdict changes in one place. **The on-device LLM moves from gated stretch to core**, as a co-pilot that never touches the alert path.
- **Minimum:** a drive debrief generated from the local event log. It is text in, text out, with no dependency on speech recognition.
- **Next:** voice hazard reports and "why" explanations.

Everything else in the verdict stands.
