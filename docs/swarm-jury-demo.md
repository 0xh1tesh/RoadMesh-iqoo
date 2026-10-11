# RoadMesh automatic perception jury demonstration

Version `0.5.0-autonomous-preview`. RoadMesh turns a mounted phone into an automatic road-sensing node: local YOLO detections become persistent tracks; temporal rules evaluate approach, host braking and traffic slowdown; nearby phones exchange compact events over Bluetooth. Every participating phone can sense, relay and receive. The driver does not spend time reporting incidents.

The pitch is **cooperative perception beyond one camera view**, with explicit evidence and uncertainty. GPS still supplies position and host-motion measurements. Navigation, V2I and vehicle-to-vehicle research are related approaches; the prototype does not establish universal superiority over them.

## What to say

> "We mount a phone with its rear camera facing the road. It detects visible vehicles and obstacles locally, follows them over time and combines that history with available motion measurements. When the evidence supports an event, the phone generates and shares a compact warning automatically. Other participating phones can receive and relay it without waiting for a person to report it. Our prototype exposes why an event was raised, keeps its original age and does not treat missing knowledge as a clear road."

Keep the distinction between relative approach and measured braking: a growing box alone cannot tell us whether the lead vehicle braked or the camera vehicle accelerated. Host braking requires valid host-speed evidence; the slowdown rule also requires persistent vehicle tracks.

## Three minute presentation

1. **Show the intended setup, 20 seconds.** A phone is mounted with its rear camera facing forward. On Drive, show Start drive and explain that each participating phone can observe and relay. No manual reporting is required.
2. **Use a real recorded clip, 50 seconds.** Open replay lab, select the Bengaluru dashcam or pothole footage and press Start replay demo. Show actual local YOLO detections and track IDs over the video. Keep the REPLAY label visible: these are recorded frames processed now. All three bundled clips and the custom-video picker remain available.
3. **Inspect an event, 25 seconds.** When the replay evaluator raises an event, show its reason and temporal evidence. Candidate queued means accepted for mesh processing, where it can still be rejected; it is not transmission or receipt. If the chosen clip produces no current event, describe that honestly and use the algorithm fixtures to exercise a known event case; do not manually inject a report while calling it automatic detection.
4. **Explain the sensor fusion, 35 seconds.** In Lab, choose Corroborated traffic slowdown. The production evaluator processes supplied synthetic boxes and speed measurements. Show the supporting evidence, then choose Unknown speed quality or One-frame detector glitch to demonstrate a withheld event. These fixtures test event logic, not YOLO accuracy.
5. **Show cooperation, 30 seconds.** Use actual receiver logs if three phones are rehearsed. Alternatively, the labelled stopped-bus network model shows a possible A-to-B-to-C path and the loss of knowledge when the only relay is removed. Do not describe the ideal graph as measured Bluetooth recovery.
6. **Finish with the experiment, 20 seconds.** "The next evidence is a controlled phone trial measuring detector errors, original event age at reception and useful warning lead time. We have implemented the pipeline; field usefulness remains to be measured."

The [automatic drive guide](AUTOMATED_DRIVE.md) contains live setup, the three-phone Stage procedure, all replay choices and implementation pointers. The retained 3D brake scene is a separate synthetic demonstration.

## What is implemented

| Feature | Show the jury | Practical reason |
| --- | --- | --- |
| Automatic live rear-camera pipeline | Start drive, actual boxes, local track IDs and measured inference rate | Removes the per-incident reporting action |
| Shared replay pipeline | Bundled footage and custom-video input with REPLAY source | Repeatable visual pitch using the actual local models |
| Temporal event rules | Track persistence, unique speed samples, uncertainty and decision evidence | A single noisy frame or unknown speed does not prove a braking wave |
| Overlapping sensing and relaying | Each ROAD phone may originate and forward eligible events | Avoids dependence on a permanently appointed lead observer |
| Optional local explanation | Gemma explains recorded evidence after the compact warning is queued | Keeps slow text generation outside the urgent event path |
| Original source and age | Forwarded copies keep the original identity and generation time | Several copies are not several witnesses |
| Movement and uncertainty | Local direction groups and missing-position states | Nearby phones are not necessarily on the same approach |
| Bounded processing | Capped tracks, histories, neighbourhood and evidence storage | Keeps the problem local to a phone |

Track IDs are local visual associations, not global vehicle identities. There is no autonomous steering or braking. LLM explanations are checked before display, expire when stale and never gate deterministic warnings. They are not a vehicle-control authority. Signing, authenticated independent sources, distributed task leases and live fleet learning remain future work.

## Demonstrating actual phone exchange

Install the same APK on all phones. Use Start replay demo on the first phone, then Stage-mode Start mesh on the forwarding and receiving phones. The stage assigns synthetic positions and filters contacts into an ordered rehearsal chain. Recorded-frame detections and REPLAY event provenance must remain visible; Bluetooth receipt is real only when it appears on the receiver.

For live closed-site evaluation, choose Start drive on every mounted phone. Real GNSS replaces synthetic positions. Receiving/relaying and sensing can overlap; a missing position or poor speed estimate remains unknown. Compare the original event ID and generation age across device logs. Repeat with missing contacts and available alternative contacts before claiming recovery.

Useful measurements include event precision and misses, inference age, delivery fraction, original event age at receipt, duplicate suppression, wrong-approach warnings and battery/thermal behavior. The current software work does not replace that hardware experiment.

## Inspectable results

The core test suite writes [swarm-comparison.json](../core/build/reports/swarm-comparison.json). The standalone task `:core:swarmReport` prints the same computed scenarios as JSON. No hardcoded benefit percentage is used by the UI.

| Synthetic scenario | Result at recipient C |
| --- | --- |
| Connected | A → B → C, one original source, caution |
| B removed and D absent | No path, unknown |
| B removed and D enabled | A → D → C, caution |
| Roadside coverage with disconnected mesh | Fixed V2I caution; RoadMesh unknown |
| Navigation feed supplied | Navigation and mesh both receive the observation |
| Before delivery | Unknown |
| Observation age 16 seconds | Expired, unknown |

The harness configures 150 ms per ideal mesh hop and 250 ms for covered V2I. Its navigation-feed audit case uses 500 ms; the interactive screen uses a disclosed 8 seconds to make arrival visible on its one-second slider. These choices exercise ordering and expiry; they are not performance estimates for Google Maps, V2I or BLE. Links are symmetric and lossless, and vehicle positions are fixed.

The learning experiment uses a five-episode rolling median and a configured fixed 20-second baseline. Training and evaluation episodes are separate synthetic fixtures. The three new feedback episodes precede a further held-out evaluation.

| Evaluation | Learned estimate | Learned mean absolute error | Fixed baseline error |
| --- | ---: | ---: | ---: |
| Stable context | 10 s | 1.00 s | 9.67 s |
| Unseen congestion shift | 10 s | 34.33 s | 24.33 s |
| After three new observed outcomes | 40 s | 4.00 s | 24.00 s |

The estimator only accepts completed, directly observed episodes, with bounded storage and rejection of repeats. It currently operates in the replay experiment, not through automatic live outcome capture or federated fleet training. A historical clearance estimate never grants passage permission.

## Answers to likely jury questions

**Why target Indian roads?** Start with explicit situations: temporary loss of usable width around a stopped bus, two-way movement, lateral positioning, mixed vehicle capabilities and uncertain location. The app preserves lateral offsets and separates movement groups instead of treating every phone as a lane-ordered convoy. This is a useful first representation; precise lane association and unseen pedestrians remain unresolved.

**Why not use navigation alone?** The feature being tested is fresh local evidence from another viewpoint. A navigation service with the same event can provide similar knowledge, as the comparison shows. RoadMesh can be a complementary local input; it still uses GPS and does not replace routing.

**Why not install V2I?** A covered roadside sensor may have the better view and can work without a chain of participating vehicles. The hypothesis for RoadMesh is deployment flexibility at temporary locations. No lower total-cost or universal coverage claim has been measured.

**Where is the decentralized swarm?** Every ROAD phone can automatically sense with its own camera while maintaining bounded local knowledge and forwarding eligible observations without a laptop coordinator. Event decisions and advice are local. Distributed task leases, airtime-aware scheduling and cooperative learning remain roadmap items. The simulation's graph is not evidence of real distributed route discovery.

**How is the graph optimized?** The implementation bounds the local roster at 128 entries, scopes advisory neighbours to 250 metres by default, expires old information and caps assessment input at 128 participants and 512 observations. It separates movement groups and deduplicates original evidence. These are resource controls and relevance improvements; no CPU, battery or city-scale optimization benchmark has been run. The small lab still constructs edges with pairwise distance checks.

**Does it prevent accidents or save time?** Those are intended outcomes. This prototype measures neither. Existing [Junction Lab results](junction-lab-results.md) showed no spillback benefit and increased the default fixed-horizon delay proxy by 2.97%; that finding remains part of the evidence. The next evaluation must show useful warning lead time and human response before making traffic or safety claims.

## Build and verification

Use JDK 17 and the configured Android SDK. Restore the team's two detector assets with `powershell -File tools/fetch_prototype_models.ps1`; they are checksum-pinned and ignored by Git. The preview uses those models for both live rear-camera input and retained recorded-video input. The optional Gemma model remains a separate import.

```powershell
.\gradlew.bat :core:test :sim-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\gradlew.bat -q :core:swarmReport
```

Screenshot tests are opt-in with `-PwithScreenshots=true`. On this machine they used Robolectric API 35 with native graphics and a 400 × 880 dp viewport; rendered outputs are in `app/build/screens/`. Final verification counts and artifact details belong to the release record; historical 0.4.1 results are in the 0.4.1 release notes. Re-run the checks for this version before using earlier counts.

The preview is an unsigned-evidence advisory prototype: source IDs are claims, not authenticated independent vehicles. Position uncertainty cannot distinguish all parallel roads or flyovers. Rehearsal and controlled field evidence are the next steps, followed by calibrated mixed-traffic comparisons, message scheduling and live outcome capture.
