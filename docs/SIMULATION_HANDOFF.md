# RoadMesh / RESILANE: mesh and swarm simulation handoff

Implementation baseline: `db77a70651ae10f2dc313f4c1e3c22bea83bdc40` on `main`, release `v0.5.0-autonomous-preview`. This document describes that prototype and separately specifies simulation extensions. Code is authoritative if a later revision changes behavior. The older `architecture.md` describes an earlier stage harness and has outdated protocol/default values.

## 1. The product idea you must preserve

Every participating vehicle carries a forward-facing dashboard-mounted iQOO or other capable Android phone. The phone runs local object detection, associates objects across frames, combines temporal visual evidence with available phone motion/GNSS measurements, and automatically creates cautious traffic observations. Nearby participating phones exchange compact observations over Bluetooth. Receiving phones can relay them, assess their relevance locally, and warn their driver before their own camera can see the original situation.

No driver needs to report an incident. A simulation's scenario controls may introduce an obstacle into the simulated world, but the vehicle must then detect it through its simulated sensors; pressing a scenario button must not masquerade as an automatically detected report.

The intended chain is:

```text
World event
  -> front camera + local YOLO detections + available ego motion
  -> temporal tracking and deterministic evidence checks
  -> local caution + automatic event candidate
  -> mesh admission / bounded Bluetooth broadcast
  -> neighboring phones validate, deduplicate, assess, optionally relay
  -> other drivers receive a relevant caution
  -> optional local language model explains already established evidence
```

The core distinction is cooperative perception beyond one vehicle's view, without requiring a roadside unit or cloud round trip for each local alert. GPS remains useful for position and direction; the project does not replace satellite positioning. Compare against a navigation incident feed and a specified V2I deployment, not against “GPS” as if GPS itself detects incidents.

Keep replay clips and the visual pitch experience. Replay demonstrates the camera pipeline; the network simulation demonstrates propagation. Clearly label whether a displayed event comes from clip detections, synthetic sensor fixtures, or a live phone. Do not manufacture synchronized GNSS measurements for existing clips.

## 2. What exists, what is idealized, what is proposed

| Component | Status in this baseline |
|---|---|
| Foreground rear camera, local ONNX detection, temporal event analysis | Implemented; physical-phone performance still needs measurement |
| Automatic local cautions and event publication | Implemented, subject to sensor/provenance/rate checks |
| Bluetooth extended-advertisement transport and multi-hop relay engine | Implemented; physical multi-phone delivery not validated by desktop tests |
| Original-source identities, deduplication, expiry, bounded queues | Implemented |
| Nearby presence, local road evidence map, context query/reply | Implemented |
| Local swarm relevance, uncertainty and caution assessment | Implemented |
| Swarm demonstration graph with ideal links and fixed per-hop delay | Implemented as a separate lab, not a measured radio model |
| Rolling-median clearance learning | Implemented as a local shadow learning demonstration |
| Dynamic vehicle trajectories, collision/loss model, adoption sweeps | Extensions requested for the new simulator |
| Fleet-wide training, authenticated consensus, coordinated vehicle control | Not implemented; do not depict these as current capabilities |

“Swarm” currently means multiple autonomous sensing nodes exchanging evidence and independently evaluating it. It does not mean a central controller, distributed agreement on every hazard, platooning, or automatic braking control.

## 3. Separate the world from each phone's knowledge

Maintain three distinct structures:

1. **Ground-truth road world:** geometry, lanes if available, trajectories, obstacles, occlusion, real event times. Only the simulator and evaluator can access this directly.
2. **Radio connectivity:** directed, time-varying opportunities for packets to travel between phones. A vehicle can receive radio evidence about something hidden from its camera. A geometric edge is not proof of packet delivery.
3. **Each phone's local knowledge:** observed tracks, sensor history, heard neighbors, deduplication cache, pending relays, original evidence, hazard clusters, local assessment. Never grant it the global road graph or all vehicle positions unless a separate proposed feature explicitly supplies them.

The production mesh does not compute a global shortest path. Its behavior emerges from local broadcast, expiry, relay scheduling and suppression. BFS appears only in the existing idealized swarm lab to explain reachability.

All participating ROAD-mode phones can sense, originate, relay and receive simultaneously. Nodes are not permanently assigned “camera vehicle,” “relay vehicle,” and “receiver vehicle.” Those labels are useful views of a particular event path.

## 4. Automatic sensing and event generation

### Camera pipeline

- Foreground rear-camera pipeline; moving to the background pauses camera analysis. The Bluetooth service can continue.
- Analysis frames are approximately 640 × 360, with model preprocessing to 384 × 640 using letterboxing.
- Target sampling is 5 Hz, with one inference in flight. Drop work rather than accumulate an old-frame backlog.
- Camera timestamps and result age are checked; results older than 1.5 seconds are rejected. Capture-time ego measurements are used rather than a newer reading taken after inference.
- Mount movement/turning and camera geometry changes invalidate temporal assumptions and reset or pause tracking.
- GNSS used by the camera pipeline must be non-mock, recent and sufficiently accurate. Speed-based event reasoning imposes stricter speed age/accuracy checks below.

### Temporal analyzer defaults

| Parameter | Default |
|---|---:|
| Maximum live tracks | 48 |
| Maximum input detections per update | 256 |
| Maximum samples per track | 32 |
| Maximum association gap | 1 second |
| History | 2.5 seconds |
| Minimum observation span | 0.6 seconds |
| Minimum supporting samples | 4 |
| Per-kind event cooldown | 8 seconds |
| Maximum ego-speed measurement age | 1.5 seconds |
| Maximum speed uncertainty | 1 m/s |
| Hard-deceleration threshold | 3.5 m/s² |
| Slowdown-deceleration threshold | 1.5 m/s² |
| Minimum conservative speed drop | 2.5 m/s |
| Maximum heading change for stable reasoning | 12° |
| Maximum lateral track rate | 0.12 normalized image units/s |

Association uses compatible classes and box overlap, with IoU threshold 0.3. Competing overlapping vehicle-class boxes are suppressed at IoU above 0.65. Track IDs are temporary identities on one phone, not license plates, Bluetooth node IDs or cross-camera identities.

Optical approach is estimated from the slope of `log(box height)` over time, with width-growth and residual consistency checks. Positive expansion can support approximate TTC `1 / growth`. This assumes roughly constant object dimensions and viewing geometry; it does not establish the other driver's absolute speed or braking.

The analyzer's important distinctions:

- **BRAKING:** this phone's vehicle has a conservative measured deceleration, supported by persistent visible traffic. Requires at least three distinct acceptable speed measurements and consistent drops. Endpoint uncertainty is subtracted: `conservativeDrop = firstSpeed - lastSpeed - firstError - lastError`. The drop and deceleration must cross the thresholds. It does not claim that camera boxes prove another vehicle is braking.
- **SLOWDOWN:** host deceleration plus at least two persistent visible vehicles whose relative gaps are not opening, with stable lateral behavior. This is evidence consistent with collective slowdown, not proof every vehicle on the road slowed down.
- **VEHICLE_CLOSE:** persistent close/approaching visual evidence. Approximate geometry uses a 6 m close threshold, a 25 m approach range and a 2 s TTC threshold; these are prototype assumptions, not calibrated safety thresholds.
- **PEDESTRIAN:** persistent person in the forward corridor, excluding a person associated with a visible two-wheeler. Geometry defaults include a 2 m half-width and 18 m range.
- **POTHOLE:** repeated relevant road-model detections in the lower central view; center x between 0.25 and 0.75 and bottom y at least 0.55.

At most two candidates are emitted per update. Detector confidence is a supporting detector score, not a calibrated probability of a crash. Missing detections mean unknown, not road clear. Missing GNSS can leave a local visual caution available while preventing a speed-derived claim or network publication requiring position.

Do not automatically label a bus as a blocked road, infer an accident solely from a phone impact, or broadcast a helmet violation from an unreliable live detection. Those are not supported automatic behaviors in this baseline.

The optional local language model runs after deterministic event extraction. It explains evidence; it does not decide whether a packet can be sent, invent event facts, or issue vehicle-control commands. Its latency must not hold up the alert path.

## 5. Event identity, timestamps and provenance

A logical event identity is:

```text
MessageId = (originalNodeId: 64 bits,
             originalSessionNonce: 64 bits,
             originalSequence: 32 bits)
```

An alert also carries kind, original generation time, location, provenance, current forwarder, remaining TTL, hop count and compact optional details. Suggested simulation representation:

```text
HazardMessage {
  id: { origin, session, sequence },
  kind, provenance, capturedUtcMs,
  originLocation, detail,
  forwarder, ttl, hop, rank
}

LocalEvidence {
  originalMessageId, originalSourceId,
  capturedAtLocalMs, capturedUtcMs,
  provenance, confidence?, position?, positionAccuracy?
}
```

This is a readable simulation schema, not a replacement wire encoding. Current alert envelope version 2 is 61 bytes: a 27-byte core plus a 34-byte extension carrying full identities, provenance, TTL, hop and rank. The generation timestamp has 100 ms resolution and is reconstructed relative to the nearest UTC hour. It is not a precision latency probe.

Never reset the original event timestamp when relaying. Never create a new original identity merely because another phone forwarded it. Ten copies from one origin remain one claimed witness. Separate node identities are not cryptographically authenticated independent witnesses in this prototype.

Use separate capture, inference-completion, enqueue, transmission, reception and warning timestamps. The current origin packet uses the available publication position with the original capture time; do not silently treat it as a perfectly synchronized capture pose.

Provenance is mandatory:

- ROAD accepts LIVE with real, non-synthetic position.
- STAGE accepts DEMO/REPLAY with synthetic position.
- Do not mix replay evidence into a live road assessment.

## 6. Bluetooth transport and mesh rules

### Transport

This is application-level mesh behavior over nonconnectable, nonscannable BLE extended advertising. It is not Bluetooth SIG Mesh provisioning and not a collection of paired GATT links.

- Extended-advertising support and enough advertisement payload capacity are required.
- Experimental manufacturer ID: `0xFFFF`.
- Hazard/control prefixes are distinguished by the scanner.
- Low-latency scanning; requested advertisement interval 160 units, approximately 100 ms.
- Origin bursts request 10 advertisement events; relay bursts request 4.
- A second advertising set can carry presence, with requested 200 ms interval and payload refreshed approximately once per second. If unavailable, presence shares the serialized transmitter.
- Transmissions are serialized. Completion means a local transport operation completed, not that any receiver acknowledged delivery.
- No per-event end-to-end acknowledgement or guaranteed delivery exists.

Do not equate the configured 150 m mesh range parameter with a measured Bluetooth range. Reception probability, congestion, antenna placement, metal body occlusion and Android scheduling require separate simulation assumptions or measurements.

### Mesh defaults

| Parameter | ROAD | STAGE |
|---|---:|---:|
| Initial relay TTL | 4 | 8 |
| Maximum fresh alert age | 5,000 ms | 5,000 ms |
| Accepted future-clock tolerance | 2,000 ms | 2,000 ms |
| Deduplication capacity / retention | 4,096 / 30 s | Same |
| Engine neighbor capacity / retention | 1,024 / 30 s | Same |
| Pending relay capacity | 64 | 64 |
| Maximum distance-based delay | 200 ms | 200 ms |
| Additional random jitter | 0–20 ms | 0–20 ms |
| Suppression threshold | 2 distinct later forwarders | Same |
| Configured range parameter | 150 m | 150 m |
| Spoken-alert relevance radius | 400 m | 400 m |
| Local origin budget | 2 events/s | 2 events/s |
| Relay budget | 3 events/s | 3 events/s |
| Engine scheduling tick | 25 ms | 25 ms |

TTL counts remaining relays: origin hop 0 with ROAD TTL 4 allows four relay transmissions after the origin transmission, hence potentially five radio links. Relaying decrements TTL and increments hop. Receivers require `hop + ttl == profile.initialTtl`. Do not model it as unlimited flooding.

### Origination

The engine checks capture age, usable position, provenance, rate and available deduplication capacity. Accepted events enter the seen cache and produce an origin transmission. An API call successfully queueing a candidate is not proof it survived engine admission or radio delivery.

### Reception and forwarding

```text
on advertisement:
  decode envelope; reject malformed / unsupported data
  apply mode link policy
  reject own origin, unsupported kind/class, unsupported signed/proxy flags
  validate hop + TTL and provenance
  reconstruct original age; reject too old or too far in the future
  require usable local position
  check origin-distance plausibility:
      distanceToOrigin <= (hop + 1) * configuredRange * 1.5

  if this valid copy comes from a distinct later-hop forwarder:
      update suppression evidence for any pending relay
      cancel pending relay after two such distinct forwarders

  if original MessageId already seen: stop normal processing
  enforce per-origin and immediate-forwarder acceptance limits
  remember original MessageId; record received evidence
  independently evaluate whether to announce locally

  if TTL > 0, relay gate permits, and pending capacity exists:
      schedule relay with original event expiry

on scheduling tick:
  expire caches and pending work
  for due relays, subject to relay budget:
      transmit same original event with hop + 1, TTL - 1,
      and this phone as forwarder
```

The delay is:

```text
delayMs = 200 * (1 - clamp(distanceToOriginalEvent / 150, 0, 1))
          + uniformInteger(0, 20)
```

It uses distance to the event origin, not distance to the last transmitter. Farther recipients tend to relay sooner, but this is not a proven optimal routing algorithm. Duplicate suppression occurs before the duplicate-return path so overheard copies can cancel pending work. Budget/capacity failures can discard work; do not model an infinite retry queue.

ROAD-mode forwarding does not require the event to be relevant to the forwarding driver's heading. An opposing-direction phone can help propagate evidence while deciding the event does not warrant the same local warning.

Spoken remote caution uses separate checks: known headings, heading difference at most 45°, distance at most 400 m, and the event ahead within 30° of the receiver's heading, with a close-distance exception below 5 m. Missing heading prevents this directed spoken caution. The swarm panel has a separate, uncertainty-aware assessment described below.

### Android outbound scheduler

The runtime has a bounded 64-item priority outbox:

- URGENT: braking, close vehicle, pedestrian, accident.
- CONDITION: other caution events.
- CONTEXT: reports and control traffic.

Expired work is discarded; original deadlines survive queueing. Presence and matching event work can be coalesced. Higher-priority work can evict lower-priority queued work. Fairness allows lower-priority work after urgent bursts; an already active BLE burst cannot be preempted. Include this serialization when estimating latency rather than giving every event an independent radio.

## 7. Neighbor discovery and stage versus road behavior

Presence is local discovery, not a complete global fleet directory. The displayed chain roster holds up to 128 heard peers and expires peers after 4 seconds. This differs from the engine's 30-second neighbor retention and the advisor's 8-second participant age limit.

STAGE deliberately creates a reproducible chain. Phones sort by estimated session start, with close starts tied by unsigned node ID. Synthetic positions have 40 m spacing, heading 0° and speed 10 m/s. The stage link filter only accepts the preceding rank. Thus A cannot skip directly to C even if its Bluetooth advertisement physically reaches C. Label this as a demo constraint.

ROAD uses real fixes. Display ordering can group compatible headings and a roughly 40 m lateral corridor, but does not install a forwarding route or elect a leader. A capable ROAD phone remains an observer while also relaying. The order is a local view and need not agree between phones.

## 8. Road memory and context exchange

Fresh alert forwarding and remembered road conditions have different lifetimes.

The road map stores up to 256 hazard clusters, merges nearby same-kind observations within 25 m, and retains at most eight distinct original sources per cluster. A repeated event identity cannot refresh the observation age, confidence or position or become another witness. Newest evidence per source is retained. The seen-identity store is bounded and has one-hour retention; this is not permanent replay protection.

Uncertainty is preserved. Cluster position uncertainty includes source uncertainty and displacement from the cluster anchor. Unknown source uncertainty does not become zero simply because the packet arrived successfully. A later matching context record can fill certain missing uncertainty metadata, but must preserve the original identity and capture evidence.

Example condition-memory lifetimes:

| Kind | Road-memory lifetime |
|---|---:|
| Close vehicle | 20 s |
| Pedestrian | 30 s |
| Braking | 90 s |
| Slowdown / wrong way / narrowed road | 120 s |
| Accident | 1,200 s |
| Pothole | 1,800 s |

These are prototype storage policies, not guarantees that a hazard still exists. They do not allow a 30-minute-old pothole record to circulate as a fresh five-second alert.

Context queries can request nearby original evidence with position uncertainty. The automatic path uses a 500 m radius, a 2.5 s response window and an 8 s global cooldown when fresh remote evidence lacks needed accuracy. Queries/replies are separately bounded and deduplicated; query TTL is also limited. They supplement a received alert, not replace camera detection with manual reporting.

The live swarm adapter maps braking/slowdown to QUEUE_TAIL; accident/narrowed-road to OBSTRUCTION; pedestrian and wrong-way retain corresponding categories; other supported cautions become ROAD_HAZARD. It preserves individual original sources and capture times, and caps observation lifetime for this assessment at 15 seconds.

## 9. Local swarm assessment

Each vehicle evaluates its own surroundings. The current advisor returns **CAUTION** or **UNKNOWN**. It never concludes SAFE, CLEAR, GO or permission to overtake.

Defaults:

| Parameter | Value |
|---|---:|
| Participant freshness | 8 s |
| Maximum evidence lifetime accepted by advisor | 30 s |
| Default observation lifetime / live adapter cap | 15 s |
| Local neighborhood | 250 m |
| Corridor half-width | 35 m |
| Maximum trusted position uncertainty | 40 m |
| Minimum supplied confidence | 0.45 |
| Maximum participants / observations | 128 / 512 |

Deduplicate observations by `(sourceId, eventId)` and retain the oldest capture among duplicate copies before expiry checks. Missing confidence is retained as unverified, not silently assigned a high score. Over-capacity or invalid/stale ego context produces UNKNOWN.

For usable local coordinates relative to heading theta:

```text
forward = east * sin(theta) + north * cos(theta)
side    = east * cos(theta) - north * sin(theta)
uncertainty = egoPositionAccuracy + observationPositionAccuracy

potentially relevant if:
  distance <= 250 m
  forward + uncertainty >= -20 m
  abs(side) <= 35 m + uncertainty
```

Missing or poor position/heading accuracy falls back to an unverified vicinity caution rather than precise road assignment. This cannot reliably separate stacked flyovers, service roads and nearby parallel carriageways without additional map/elevation evidence.

Participant relations include SAME_DIRECTION, OPPOSING, CROSSING, UNCERTAIN, UNLOCATED, STALE and OUT_OF_RANGE. With valid accurate headings, let `delta` be wrapped absolute heading difference and `u` the sum of heading uncertainties:

- Same direction if `delta + u <= 60°`.
- Opposing if `delta - u >= 120°`.
- Crossing if `delta - u > 45°` and `delta + u < 135°`.
- Otherwise uncertain. Individual heading uncertainty above 45° also makes the relation uncertain.

Evidence display priority is pedestrian, wrong-way, obstruction, queue tail, then other road hazard. Count distinct original source IDs, never forwarder count, as claimed independent sources.

A nominated observer is a local preference among suitable original evidence sources with compatible direction and good position accuracy. It chooses better accuracy, then stable ID order. This is not distributed leader election, a delegated sensing protocol or a reason for other phones to stop observing.

## 10. Existing swarm lab: reproduce it as a baseline fixture

The existing lab uses a small stationary geometric graph:

```text
North increases upward; x is east, y is north, units are meters.

E = (3,120), heading 180°, opposing vehicle
Hazard = (0,100)
A = (0,80), heading 0°, original observer
B = (0,40), heading 0°, optional relay
D = (8,40), heading 0°, optional alternative relay
C = (0,0),  heading 0°, recipient
```

- Radio radius 50 m; sensor visibility radius 30 m.
- Event starts at 1,000 ms, lifetime 15 s, confidence 0.85, positional accuracy 3 m.
- Participants have 3 m position accuracy and 5° heading accuracy, with continuously fresh static positions.
- Ideal undirected graph edges exist within the radius.
- BFS finds a demonstration path; each graph hop costs a fixed 150 ms.
- A sees the event 20 m away; C cannot see it 100 m away.
- A→B→C delivers after 300 ms. Remove B and leave D disabled: no path, UNKNOWN. Enable D: A→D→C restores ideal connectivity.
- A covered V2I comparator delivers after 250 ms in this fixture. A navigation incident feed can be explicitly configured with a delay; otherwise it has no supplied report.
- Every comparator uses the same event capture time and expiry. Receiving old information later does not make it fresh.
- There is no measured warning lead time in this stationary fixture. The implementation leaves that metric unset.

Do not turn the 150 ms assumption into a claim about real BLE latency. The dynamic simulator should offer this fixture for reproducibility and a separate mode running actual relay rules with queueing, loss and moving nodes.

## 11. What “learning” currently means

The local shadow clearance estimator learns completed, directly observed obstruction durations. It uses the median of the latest five valid episodes per region, keeps up to eight contexts, and requires at least three samples before estimating.

It rejects duplicates, overlapping episodes for a region, invalid times, future completions, old completed episodes and durations outside 1–300 seconds. Only directly observed completion qualifies. Silence, a lost neighbor, or failure to detect something is not evidence that a blockage cleared.

Its output is a median, observed minimum/maximum and sample count. The range is not a statistical confidence interval. No model weights are exchanged, and no fleet training or vehicle-control policy is installed.

The demonstration deliberately includes distribution shift: training durations around 10 seconds can perform worse than a fixed 20-second baseline when new congestion lasts about 40–48 seconds. Additional directly observed episodes allow adaptation. Show both the failure and recovery; do not report only favorable training-regime results.

## 12. New simulator: implementation brief

Build a deterministic, seeded, discrete-event simulation with a visual frontend. Keep the simulation engine independent of rendering so experiments can run faster than real time and export JSON/CSV results.

### Per-vehicle state

```text
VehicleNode {
  physicalTrajectory, participationEnabled,
  cameraModel, observedTracks, egoSensorHistory,
  nodeId, sessionNonce, nextSequence,
  clockOffset, localFixAndUncertainty,
  heardRoster, seenEvents, pendingRelays,
  transmitQueue, activeBurst,
  roadEvidenceMap, localSwarmAssessment,
  optionalLocalClearanceEstimator
}
```

Use a scheduler for sensor captures, inference completions, motion updates, advertisement attempts, deliveries, relay deadlines, queue expiry and assessment refresh. Reproduce the engine's 25 ms scheduling cadence where relevant. Movement interpolation can be finer without granting phones more frequent measurements.

Prefer reusing the pure Kotlin core in a JVM simulation harness if feasible. A web simulator may port the rules, but must share golden fixtures with the core and explicitly identify simplifications. The simulator should not invent different routing rules and call them current RoadMesh.

### Sensor abstraction

Offer two honest modes:

1. **Clip/perception mode:** play retained clips with actual detection/reasoning output when available. If output is precomputed, label it. Missing synchronized speed prevents claiming clip-based measured hard braking.
2. **Synthetic sensor mode:** world trajectories create noisy detections and timestamped ego measurements. Run the temporal reasoner on those observations. Ground truth is used only for sensor generation and evaluation, never fed directly into the event publisher.

Expose detection misses, false detections, jitter, occlusion, inference time, camera shake, GPS position/speed error and heading uncertainty. An event should earn persistence rather than become visible to the mesh at its ground-truth start time.

### Radio abstraction

Start with ideal radius links for debugging. Add a separately labeled configurable stochastic model with distance-dependent reception, vehicle/obstacle occlusion, packet loss, channel contention and one serialized broadcaster per phone. Unknown RF parameters are scenario assumptions, not measured defaults. Support asymmetric delivery and reproducible random seeds.

Model repeated advertisement opportunities within a burst and duplicate reception. Do not make one “broadcast” deliver reliably to every node in range. Do not add delivery acknowledgements, guaranteed retransmissions or store-and-forward beyond expiry unless marked as a proposed extension.

### Driver response and safety outcomes

Default to advisory mode with no automatic vehicle actuation. If modeling driver response, explicitly parameterize perception/reaction delay, compliance and deceleration; apply the same assumptions to all comparators. Only report collision or delay effects from actual simulated trajectories. Delivering a warning does not itself prove a collision was prevented.

### Visual interface

- Top-down road view with camera cones, occlusion, vehicles and evolving radio links.
- Per-phone selection showing what it knows, as distinct from the evaluator's ground truth.
- Different visuals for original detection, pending relay, actual transmission attempt, reception, suppression and expiry.
- Event timeline: world start → capture → reasoner event → queue → each relay → recipient caution.
- Show original source count separately from copy/relay count.
- Expose uncertainty, stale data and disconnected regions.
- Preserve a replay clip panel and pitch-friendly pause, step, speed and scenario reset controls.
- Clearly separate implemented behavior, ideal lab assumptions and proposed experiments.

## 13. Scenarios to build first

| Scenario | What it should demonstrate |
|---|---|
| Braking/slowdown behind a large bus | Persistent local evidence becomes an automatic message and reaches an occluded following participant |
| Relay leaves radio range | Pending communication can fail; recipients remain UNKNOWN rather than pretending delivery |
| Alternative vehicle supplies another link | Local relay behavior can preserve delivery without a global route controller |
| Dense mixed traffic | Duplicate suppression, queue limits and airtime pressure; more vehicles can help connectivity and worsen congestion |
| Sparse adoption: 10/25/50/75/100% | Nonparticipants remain physical traffic but cannot relay; partitions reveal the adoption limitation |
| Opposing carriageway / nearby service road | A relay can be useful while a direction-specific warning is irrelevant; uncertain geometry must be visible |
| Junction with crossing approaches | Show uncertainty-aware participant relations, without inventing right-of-way allocation |
| Poor GNSS / turning mount / one-frame false box | Suppress unsupported speed claims and transient detections; retain UNKNOWN states |
| Hazard expired during congestion | Drop stale queued alerts; do not rejuvenate them on the next hop |
| Two actual independent observations | Source count increases only for independently originated observations, not duplicate relays |
| Strong V2I coverage, weak mesh | V2I should win when its modeled evidence and delivery are better |
| Good navigation feed, no mesh path | Navigation can inform the recipient; do not handicap the comparator artificially |
| Clearance learning under changed congestion | Show initial estimation failure and adaptation from new direct observations |

Indian-road relevance comes from testing occlusion by buses/trucks, mixed two-wheelers/cars, irregular spacing, interrupted connectivity and ambiguous nearby roads. These are scenario conditions, not evidence that the prototype has already solved them.

## 14. Fair metrics and comparison rules

Compare camera-only, camera+RoadMesh, a specified navigation incident-feed model, and a specified V2I sensor/coverage model. Disclose each comparator's sensing coverage, detection delay, network delay and event availability. Do not assume the mesh gets perfect evidence while comparators get no evidence unless the scenario explicitly studies that coverage difference.

Record:

- Detection precision/recall against eligible ground-truth events, with a matching time/spatial window.
- Capture-to-warning and world-event-to-warning latency, separately; report median and tail quantiles.
- Fraction of intended, relevant recipients warned before expiry, with denominator defined even for disconnected recipients.
- Warning lead relative to the same recipient's local detection, only where both timings are meaningful; otherwise mark censored/unavailable.
- Alert age on receipt, stale rejection count, duplicate count and unique original-source count.
- Transmissions/bytes per original event, queue drops, suppression count, node load and estimated airtime.
- Incorrect-road or incorrect-direction warnings and proportion of time marked UNKNOWN.
- Participation/connectivity sensitivity and results across multiple fixed seeds.
- Clearance prediction error on held-out future episodes, including distribution shifts.

Do not label graph reachability as packet delivery, packet delivery as a useful warning, a useful warning as crash prevention, or a synthetic timing assumption as a hardware benchmark.

## 15. Minimum acceptance checks

1. A single-frame detection does not automatically become a persistent traffic event.
2. Repeated copies preserve original ID, timestamp and source count.
3. Relays decrement TTL, increment hop and obey the original expiry.
4. ROAD TTL 4 permits at most four relays after the original sender.
5. Two qualifying later forwarders suppress a pending relay; repeated copies from one forwarder do not satisfy that threshold.
6. No path, expired evidence, poor context or capacity overflow can remain UNKNOWN.
7. Missing GNSS cannot silently become a fabricated precise pose or measured speed.
8. Missing heading prevents unsupported direction-specific speech.
9. Live, replay and stage evidence cannot be silently mixed.
10. Every participating ROAD phone can originate and relay; no permanent relay-only assignment is required.
11. The original A–B–C ideal fixture reproduces 300 ms under its explicitly fixed assumptions.
12. Covered V2I can outperform disconnected mesh.
13. Detector latency, persistence, serialized bursts and queueing all contribute to dynamic end-to-end delay.
14. Learning does not use future episode completion or interpret radio silence as clearance.
15. The same seed and configuration reproduce the same event trace and metrics.

## 16. Source map for the implementing AI

All paths are relative to the repository root:

- `core/src/main/kotlin/com/jsons/roadmesh/core/mesh/Model.kt`: mesh defaults, types and hazard kinds.
- `core/src/main/kotlin/com/jsons/roadmesh/core/mesh/MeshEngine.kt`: admission, relay scheduling, suppression and relevance.
- `core/src/main/kotlin/com/jsons/roadmesh/core/mesh/HazardEnvelope.kt`: original identities and wire encoding.
- `core/src/main/kotlin/com/jsons/roadmesh/core/mesh/Roster.kt`: presence roster and mode-specific chain behavior.
- `core/src/main/kotlin/com/jsons/roadmesh/core/road/RoadMap.kt`: original evidence, hazard clustering and memory.
- `core/src/main/kotlin/com/jsons/roadmesh/core/road/RoadQueries.kt`: context query/reply.
- `core/src/main/kotlin/com/jsons/roadmesh/core/perception/AutomatedTrafficAnalyzer.kt`: temporal sensor-fusion rules.
- `core/src/main/kotlin/com/jsons/roadmesh/core/perception/DashcamHazards.kt`: approximate camera geometry.
- `core/src/main/kotlin/com/jsons/roadmesh/core/perception/AutomationScenario.kt`: deterministic sensor fixtures.
- `core/src/main/kotlin/com/jsons/roadmesh/core/swarm/SwarmAdvisor.kt`: local relevance and uncertainty.
- `core/src/main/kotlin/com/jsons/roadmesh/core/swarm/SwarmScenario.kt`: ideal connectivity comparison fixture.
- `core/src/main/kotlin/com/jsons/roadmesh/core/swarm/ClearanceEstimator.kt`: local shadow learning.
- `app/src/main/java/com/jsons/roadmesh/radio/BleHazardTransport.kt`: Android radio implementation.
- `app/src/main/java/com/jsons/roadmesh/ui/SwarmScreen.kt`: live evidence adapter and lab UI.
- `docs/AUTOMATED_DRIVE.md`: automated-driving-observation pipeline and limitations.
- `docs/swarm-jury-demo.md`: demonstration narrative and learning experiments.

Retain the three replay assets: `bangalore_cv_raman_road.mp4`, `pothole_traffic_cc-by-sa.mp4`, and `pothole_road_cc-by-sa.mp4`, including applicable attribution. Their presence is an explicit product requirement.

## 17. Copyable task for the simulation-building AI

> Build a visual, seeded, discrete-event simulator for RoadMesh / RESILANE using this handoff and the cited source revision. The central product is fully automatic phone-based cooperative perception: forward camera → local detector → temporal reasoning with available ego sensors → short-lived Bluetooth event → bounded multi-hop relay → local relevance assessment and driver caution. No manual incident-report workflow should be required. Preserve replay clips for pitch visualization.
>
> First reproduce the existing ideal A–B–C fixture and deterministic analyzer fixtures. Then implement a separate dynamic mode with per-phone local state, actual relay scheduling/suppression/expiry, serialized transmission queues, moving vehicles, sensor uncertainty and explicitly configurable radio loss. Do not route production packets using global BFS or expose ground truth to phone decision logic. Every participating phone may sense, originate, relay and receive.
>
> Make the visual story easy to follow: show who originally observed the event, what evidence triggered it, how each copy moved, why a recipient was warned, and what remained unknown. Include Indian mixed-traffic and occlusion scenarios, low-adoption failures, alternate relays, misleading detections, direction ambiguity and fair V2I/navigation comparisons. Show where competing systems win too. Label measured facts, prototype rules and simulation assumptions separately.
>
> Deliver the simulator, scenario presets, reproducible event logs, exported metrics, source-to-simulator rule mapping, and automated acceptance checks. Keep the local clearance learner as a clearly labeled shadow experiment. Do not claim fleet learning, vehicle actuation, authenticated consensus, calibrated collision prediction or real-world accident prevention unless those are separately implemented and validated.
