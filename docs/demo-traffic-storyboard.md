# RoadMesh: make the invisible chain visible

Visual proposal 0.1 · 10 October 2026 · companion to [the architecture plan](cooperative-traffic-plan.md).

The audience should leave able to say: **“One vehicle notices trouble; the network passes evidence through nearby phones; the next driver learns about it before reaching it.”** Then show how multiple observations reveal a developing traffic problem.

## The three phones

| Phone | Hero view | What the audience understands |
|---|---|---|
| **A — Observe** | Preserve the friend's 3D car. Brake lamps, road position and a compact observation strip respond to a scene event. | A physical-looking event becomes a typed observation. The scene is explicitly synthetic. |
| **B — Carry** | Simple message path, event identity, age/expiry and one visible forwarding transition. Opt-in pedestrian role in the new slice. | You do not have to drive to help carry a warning. Relaying is not corroborating. |
| **C — Prepare** | The approaching car and a large, short caution, with offline speech/haptic feedback. A stopped-demo detail view explains why. | The warning is relevant to this approach and still useful now. |

All three run the same APK; these are presentation views. The current app supplies a working scene-triggered braking chain. Pedestrian mode, wave inference, richer hazard classes, receipts and the new overlays are proposed additions and must remain labeled as previews until built.

## One visual grammar throughout

| Layer | Shape and text | Appearance and behavior |
|---|---|---|
| **Observed** | Square + “Observed” | Solid marks at supported positions; accuracy regions where available |
| **Inferred** | Diamond + “Estimated” | Bounded region/front, with unknown areas visibly incomplete; no default confidence percentage |
| **Advised** | Triangle + short caution | Only appears after relevance/freshness gates; suppressed state has a reason |
| **Radio contact** | Labeled device nodes, thin dashed contact edges | Directed reception evidence; an animated transit only when justified by the corresponding log stage |
| **Road movement** | Solid road geometry and direction arrows | Persists independently of the changing radio path |

Pair color with shapes and text. Test contrast in actual lighting and project at crowd distance; do not label a palette WCAG-compliant without checking the rendered result. Avoid color-only status. Reduced-motion mode replaces traveling pulses with ordered highlights. Driver mode stays sparse; the graph and explanations belong to the presenter or a parked debrief.

Use the existing car as the emotional anchor. A camera pullback can reveal the road, then the traffic wave, then the radio overlay. The visual stays legible because only one layer changes at a time. Do not introduce a dashboard wall of counters.

## Hero sequence: 90 seconds

These are presentation allocations, not network-latency targets.

| Time | Action | Screen / spoken point |
|---|---|---|
| 0–15 s | Show the three phones and virtual cars moving along one corridor. | “The next driver cannot see what is developing ahead.” Label scene positions **SIMULATED**. |
| 15–30 s | Trigger A's car brake; reveal additional scripted slowdown observations for the wave scenario. | Observations appear first. A backward front appears only after inference accepts sufficient evidence. Scripted vehicles are visibly distinct from the three physical phones. |
| 30–50 s | Show the warning traveling through B to C using actual receive/forward/present logs. | “This phone is a pedestrian relay. It carries the message; its walking speed does not count as traffic.” No source-side delivered badge without a receipt. |
| 50–65 s | C gives one concise caution. Freeze the scene just before the scripted car reaches the estimated front. | Highlight the distance/time margin only when derived from a trace with stated clocks. The concept version simply shows **warning before encounter**, without a fabricated number. |
| 65–80 s | Expand the warning into Observed → Estimated → Advised. | “Here is the evidence. Here is what we infer. Here is why this driver hears it.” Show the one original event across multiple relays. |
| 80–90 s | Show a preview of the same contracts supporting a persistent surface hazard and junction queue. | “The same base grows from one warning into shared road awareness.” Keep unbuilt capabilities labeled **PROPOSED**. |

If wave inference is not complete, present only the current braking chain as live and the new wave view as an explicit concept preview. Three physical phones need not impersonate hundreds of independently sensed vehicles.

## Four-minute technical walkthrough

1. **First minute — the live chain.** Car event, actual BLE acceptance and one observer caution. Disclose the current stage acceptance filter: devices may physically hear each other even though A-to-C acceptance is disabled.
2. **Second minute — the algorithm.** Reveal the road graph separately from the radio graph. Show a supported slowdown versus a case with missing/ambiguous observations; the latter stays unknown. Replaying a packet does not increase support.
3. **Third minute — the two extensions.** RoadMemory keeps a located rough-road candidate after the sensing car leaves. JunctionShield shows downstream receiving space and a possible spillback dependency; an ordinary red-light queue is shown as the counterexample.
4. **Fourth minute — the evidence of usefulness.** Show a paired simulation only if available, with the same demand/seed and stated driver-response assumptions. Then switch off B in the enforced stage chain: C should not show a new warning. Restart is recovery, not proof of alternate-path self-healing.

## Scenario cards to implement

### WaveGuard — “The wave moves toward you”

Road arrows point forward while the estimated slowdown front moves backward. Small observed slowdown marks precede the front overlay. A thin path carries the advisory upstream through B. C gets “Slow traffic ahead. Prepare to slow smoothly.”

For the no-bottleneck scenario, use a ring simulation; for the main chain, use a defect-induced disturbance and label the cause as scenario truth, not automatically inferred causality. Do not label every wave “phantom” when signal phase, a merge or a physical obstruction could explain it.

### RoadMemory — “The warning stays after the first car leaves”

A wheel crosses a scripted rough patch; an impact candidate appears over an uncertain region. Later independent qualified passes can support or contradict that record. Duplicated relays retain one evidence square. The marker becomes stale or disputed visibly; it never becomes “88% certain” because a third packet arrived.

Use “Rough road reported ahead.” Keep a possible pothole classification in the explainer until validated. Do not draw a precise safe wheel track or tell a driver to swerve.

### JunctionShield — “A green light can still lead into a full exit”

Show an approaching movement, a receiving segment and observed queue evidence. A separate directed dependency overlay makes a possible spillback cycle visible. Only evidenced dependencies light up; unobserved exits remain unknown.

C gets a queue/keep-clear caution. The phone never changes the signal or grants right of way. Show a normal red phase clearing afterward as the negative case, so judges can see that “stopped” does not mean “deadlocked.”

### Community Relay — “A phone on the sidewalk helps the road”

Move B off the road layer and keep it on the radio layer. Toggle participation: the conceptual path appears or breaks. In a live demonstration, distinguish an enforced acceptance gap from a measured physical RF gap. A pocket may attenuate the link; test it before choosing the stage pose.

Display participation and Stop clearly. Do not claim arbitrary passerby phones participate or that a pedestrian is another vehicle observation. No walking animation or cadence threshold is needed to prove the concept.

## The counterfactual twin

Use two matched traffic views only after there is a valid experiment: **no advisory** and **advisory with stated response model**. Keep geometry, demand, initial state and exogenous random streams matched. Let trajectories diverge after the warning. The intervention effect must emerge from the model, not from manually painting one scene green.

Show at most three meaningful measured quantities: travel time, harsh-deceleration surrogate and time spent blocking a junction, selected for the scenario. Include uncertainty across held-out runs. A favorable single seed, a prerecorded replay or a pretty animation is not a causal evaluation. “Accidents prevented” is not an available metric.

A projected twin is an audience view, never an operational hazard relay or a hidden source of perfect state for the phone. Confirm the event's display rules before relying on projection; the three-phone views must tell the complete story on their own.

## Evidence and failure presentation

| Claim or stage | What must drive it | Honest failure view |
|---|---|---|
| Observation produced | Source event/capture trace | No input / sensor ineligible |
| Relay accepted / scheduled / transmitted | Distinct local log stages | Expired, filtered, queue full or transport unavailable |
| C received / presented | C's logs or a returned receipt with that precise stage | Delivery unknown; no fabricated checkmark |
| Inference supported | Bounded original-support lineage plus policy version | Insufficient, conflicting or stale evidence |
| Useful warning time | Declared clock/reference and encounter definition | Timing unresolved or too late for early claim |
| Traffic improvement | Paired simulation or a designed field study | No effect or regression shown honestly |

No urgent red “brake now” screen from unsigned crowdsourced evidence. No animation should imply vehicle automation, guaranteed delivery, NPU perception, lane precision or measured traffic benefit unless that exact capability has been established.

## Visual acceptance checks

- An unfamiliar viewer can identify A, B and C and describe the message chain after one run.
- The observer view is readable at presentation distance; narration does not require reading diagnostic text.
- Observed, estimated and advised states remain distinct in grayscale and reduced-motion mode.
- Turning off relay participation or removing evidence changes the outcome, not just the decoration.
- A duplicate packet does not create another support mark; an expired warning disappears with a reason.
- Every live mark is trace-backed; every scripted mark says so. There are no invented confidence, battery, savings or latency figures.
- App Stop, process restart and an unavailable radio produce visible, bounded states. Existing car rendering and phone lifecycle acceptance still pass.

Antigravity supplied visual choreography and the layered presentation concept. The coordinator retained those ideas and removed unsupported numerical and technical claims during synthesis. Implementation is a later step after review of the proposal.
