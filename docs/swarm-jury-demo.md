# RoadMesh swarm prototype jury demonstration

Version `0.4.1-swarm-preview`, 10 October 2026. The prototype targets temporary road narrowing and hazards hidden from a following vehicle: a stopped bus, roadworks, or a queue beyond a junction. Nearby participating phones share short-lived observations and produce local cautions. The useful distinction to demonstrate is **awareness beyond one vehicle's view without requiring a sensor installed at that location**.

GPS supplies position. Navigation can supply routes and incident information. RoadMesh adds local observation exchange; its present positioning still depends on GPS. The comparison screen lets navigation and roadside sensing receive the same event, so the demonstration exposes the conditions under which each approach works. Research and competitor references are in the [implementation plan](swarm-implementation-plan.md).

## What is implemented

| Feature | Show the jury | Practical reason |
| --- | --- | --- |
| Manual road-narrowing report | In Swarm → This phone or Road, publish a condition at the reporting phone's position | Covers a stopped bus or temporary obstruction before automatic perception is reliable |
| Original source and age | One original event remains one claimed source through multiple copies; old reports expire | Repeated forwarding must not make a rumour look fresh or multiply witnesses |
| Movement and uncertainty | Same-direction, opposing, crossing and uncertain neighbours have separate classifications | A nearby phone need not be on the same approach; missing geometry stays uncertain |
| Bounded local processing | Local roster, evidence and assessment inputs have explicit limits | A phone processes its relevant surroundings rather than requiring a city-wide live graph |
| Swarm lab | Remove a relay, enable an alternative neighbour, change infrastructure coverage and age | Shows why cooperation helps and what a partition removes |
| Shadow learning replay | Compare predictions before and after new completed observations; show the intervening failure | Makes adaptive estimation inspectable before connecting learning to live advice |

The live phone view and lab use the same core advisory evaluator. The lab supplies synthetic positions and an ideal link graph. Its centrally computed shortest path is an explanation of possible delivery, not a trace from the phone radios. On phones, the existing distributed BLE mesh carries reports; ROAD forwarding no longer depends on a globally ordered chain rank. The local sensing nominee is a suggestion only; distributed task assignment is not implemented.

## Three minute presentation

1. **Introduce the problem, 20 seconds.** “Imagine a bus reducing usable road width ahead. The following driver cannot yet see it. Our question is whether another participating vehicle can provide a recent, relevant warning.”
2. **Open Swarm → Swarm lab, 40 seconds.** Show observer A, relay B and recipient C. C receives a caution supported by A's observation. E faces the opposite direction and is kept separate from same-direction peers. The drawing is explicitly synthetic.
3. **Challenge the network, 30 seconds.** Turn Relay B off: knowledge becomes UNKNOWN. Turn Alternative neighbour D on: the computed path becomes A → D → C. Explain that recovery needs an available path; it is not guaranteed by the word “mesh”.
4. **Make the comparison fair, 30 seconds.** With both relays absent, enable roadside sensor coverage: V2I has a caution while RoadMesh remains unknown. Enable the navigation incident feed and move the age to 8 seconds: navigation also receives the event. The selected delays are experiment inputs, not measured commercial performance.
5. **Age the evidence, 20 seconds.** Select Expired at 16 seconds. The original event had a 15-second lifetime. Expiry means unknown, not “road clear”; another relay cannot reset its age.
6. **Show learning, 30 seconds.** Scroll to “Learn from outcomes. Test the errors.” The estimate improves in stable synthetic conditions, fails when congestion changes, then adapts after new observed outcomes. These numbers measure prediction error, not time saved.
7. **Show This phone, 10 seconds.** This switches to actual local session records. No active session or usable position produces an honest unknown state. The report button uses the current phone position and identifies whether it is stage or road data.

Suggested closing statement: “We have implemented local observation sharing, evidence expiry and a working comparison prototype. We are testing whether this can provide useful warnings around temporary bottlenecks with partial participation. The next proof is measured delivery and warning relevance on real phones.”

## Demonstrating actual phone exchange

Install the same preview APK on each device. The control-frame revision carries original IDs, capture times, provenance and position uncertainty; an older client cannot provide equivalent attributed evidence. The APK requires Android 12 or newer and the device capabilities described in the [existing rehearsal runbook](demo-runbook.md).

First rehearse with three stationary phones in Stage mode using the existing A/B/C assignments. On A, open Swarm → This phone and report narrowing. On C, inspect actual received events and source age; use “Ask nearby phones for their road picture” to exercise the richer query response. Stage positions are synthetic, and the existing adjacency acceptance filter demonstrates a forced A → B → C exchange even when all devices are physically in range. A source-side “queued” or TX completion is not proof of C's receipt. Record the receiver's event ID.

For a subsequent closed-site ROAD experiment, use real fresh GNSS fixes and the same APK. A passenger or stationary observer reports the narrowing at its actual location. Verify which recipients receive the original ID and whether it is relevant to their approach. At rest, heading can be absent, so an uncertain advisory is expected. ROAD rejects demo/replay evidence; a missing fix is never replaced with stage coordinates. This session built and tested the software but did not perform this hardware experiment.

Measure delivery fraction, original observation age at receipt, duplicate suppression, wrong-approach warnings and unknown-state frequency. Repeat with the middle phone absent, with a genuinely available alternative contact, and with screen-off/background restrictions. Check actual receiver logs before describing recovery or end-to-end latency. Do not infer physical radio range from the lab's configured 50-metre edges.

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

**Where is the decentralized swarm?** Each phone maintains its own bounded knowledge and exchanges observations over the existing mesh without a laptop coordinator. Classification and advice are local. Distributed task leases, airtime-aware scheduling and cooperative learning remain roadmap items. The simulation's graph is not evidence of real distributed route discovery.

**How is the graph optimized?** The implementation bounds the local roster at 128 entries, scopes advisory neighbours to 250 metres by default, expires old information and caps assessment input at 128 participants and 512 observations. It separates movement groups and deduplicates original evidence. These are resource controls and relevance improvements; no CPU, battery or city-scale optimization benchmark has been run. The small lab still constructs edges with pairwise distance checks.

**Does it prevent accidents or save time?** Those are intended outcomes. This prototype measures neither. Existing [Junction Lab results](junction-lab-results.md) showed no spillback benefit and increased the default fixed-horizon delay proxy by 2.97%; that finding remains part of the evidence. The next evaluation must show useful warning lead time and human response before making traffic or safety claims.

## Build and verification

Use JDK 17 and the configured Android SDK. Restore the team's two detector assets with `powershell -File tools/fetch_prototype_models.ps1`; they are checksum-pinned and ignored by Git. The preview bundles those models for the existing replay detector. Live camera hazard generation and the optional separate Gemma import are not added by this change.

```powershell
.\gradlew.bat :core:test :sim-core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
.\gradlew.bat -q :core:swarmReport
```

Screenshot tests are opt-in with `-PwithScreenshots=true`. On this machine they used Robolectric API 35 with native graphics and a 400 × 880 dp viewport; rendered outputs are in `app/build/screens/`. The final test counts and artifact checksum are recorded in [HANDOVER.md](HANDOVER.md). Android lint has 18 warnings and no errors; the warnings are not treated as a completed cleanup.

The preview is an unsigned-evidence advisory prototype: source IDs are claims, not authenticated independent vehicles. Position uncertainty cannot distinguish all parallel roads or flyovers. Rehearsal and controlled field evidence are the next steps, followed by calibrated mixed-traffic comparisons, message scheduling and live outcome capture.
