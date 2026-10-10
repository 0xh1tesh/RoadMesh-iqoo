# RoadMesh handover: Swarm prototype implementation

Updated: 10 October 2026. Workspace: `C:/Users/hites/Desktop/PROJ_MAIN/RoadMesh-iqoo`.

## Current swarm direction

The latest user priority is an orchestrated, decentralized vehicle swarm that learns and acts dynamically, with concrete differentiation from navigation and fixed V2I on Indian roads. The [implementation plan](swarm-implementation-plan.md) is grounded in the synchronized `c4faa69` main branch. The user subsequently authorized implementation. The first slice is now in the Android app as `0.4.1-swarm-preview`; start with the [jury demo guide](swarm-jury-demo.md). The Junction Lab notes below remain the record of earlier experiments.

Implemented in this slice:

- Swarm tab: stopped-bus scenario with computed paths, relay removal, alternative neighbour, fixed V2I coverage, navigation feed, observation age/expiry and four-method comparison. Lab inputs and ideal radio assumptions are visible. Real session data is separately available in This phone.
- Manual road-narrowing reports on Road and This phone, requiring a ready session and fresh position. ROAD uses LIVE provenance; Stage uses REPLAY and synthetic positions. Reports have unknown detector confidence. Queued does not mean delivered.
- Bounded movement-aware roster and advisory evaluation; opposite/crossing/uncertain neighbours stay distinct. ROAD forwarding is not gated by chain rank. Missing positions are not fabricated, and the mesh display preserves lateral offsets rather than placing everyone on a synthetic straight line.
- Original evidence identity, capture time, per-source position/accuracy, provenance and expiry survive query/reply forwarding. Duplicate holders are not extra witnesses. A later query may enrich missing accuracy for the same original ID without refreshing it. Version 2 presence/reply frames fit the existing payload budget; all phones need the same new APK. Raw hazard envelopes remain unsigned and unchanged.
- Bounded rolling-median clearance estimator with completed direct-observation episodes only. The synthetic learning replay shows both failure under a regime shift and recovery after new feedback, using separate training/evaluation episodes. No live fleet learning or driving-policy deployment is connected.
- Removed unvalidated speed prescriptions and guaranteed queue-clearance wording from the road forecast. The two detector models were restored from the checksum-pinned team release for packaging, with a reproducible fetch script; Gemma is still an optional import.

Deadline-aware message scheduling, map matching, task leases, live outcome capture, authenticated sources and real BLE alternate-path trials remain open. The local sensing nominee is a preference, not a dispatched task or consensus leader. The comparison harness computes a path centrally over fixed ideal links; it does not prove decentralized radio recovery. Keep the desktop optional and each driver's output locally arbitrated.

Preserve the measured negative Junction Lab results and all existing local edits. The user has authorized publishing this tested slice to `main` and releasing `v0.4.1-swarm-preview`; the earlier notes below describe the pre-publication work. This change establishes a useful prototype and an inspectable experiment, not demonstrated accident prevention or travel-time savings.

## Swarm verification and artifact

Final verification: **200 tests passed**, zero failures/errors/skips: 142 core, 36 simulator-core and 22 app tests, including 10 screenshot tests. `:app:assembleDebug` and `:app:lintDebug` passed. Lint reports 18 warnings and no errors. The final log is `build/swarm-release-checks.log`. Sixteen current screenshots were generated in `app/build/screens/`; the comparison, synthetic learning and unknown-position screens were visually inspected. Earlier differently named screenshots may also remain in that ignored directory.

The debug-signed APK is distributed through the [0.4.1 swarm preview release](https://github.com/0xh1tesh/RoadMesh-iqoo/releases/tag/v0.4.1-swarm-preview). Local copies are at `build/release-review/RESILANE-0.4.1-swarm-preview.apk` and `app/build/outputs/apk/debug/app-debug.apk`. Signature verification passed. Package `com.jsons.roadmesh`, versionCode 6, Android API 31 minimum, arm64-v8a. Both `.onnx` detector assets were verified present in the archive. SHA-256: `6f4611effe6d9ae1f9c230e0261e965fd919f8d4c4abf0ef9337141bedb20702`.

The published 0.4 APK and this build use different debug signing certificates. Android will reject an in-place update between them. Export reports before replacing the previous installation; uninstalling removes its local data. Install the same new APK on all participating phones for the revised control frames.

The build used JDK `C:/Users/hites/dev_tools/jdk-17.0.14+7`, one Gradle worker, in-process Kotlin and `-XX:TieredStopAtLevel=1` after an earlier host JVM native compiler crash. Do not run concurrent Gradle builds in this checkout. Screenshot tests used the local ignored init script `build/release-review/screenshots.init.gradle` to allow Robolectric dependency resolution and a 1536 MB test heap. The ordinary app unit-test task excludes screenshot tests unless `-PwithScreenshots=true` is set. Its default offline configuration expects the instrumented runtime in `app/build/robolectric-jars` and a writable `app/build/tmp-tests` directory; the old build-script reference to `docs/prototype-app.md` points to a missing upstream document.

The standalone `:core:swarmReport` task also passed. Its JSON parsed successfully with seven comparison scenarios and three learning cases; a copy is in `build/release-review/swarm-comparison.json`.

No physical phone or emulator was connected for this final validation. Real BLE delivery, background operation, live camera observation, road geometry, human response and traffic benefits remain unmeasured. Codebase-memory project `RoadMesh-iqoo` was refreshed; its graph may lag new Kotlin symbols, so use exact source files when graph discovery returns insufficient results.

## Earlier Junction Lab implementation

The user explicitly authorized implementation of the recommended three-way experiment and requested orchestration. **Junction Lab is now implemented**: matched SUMO no-advice/local-only/mesh-informed runs, a shared Android-free Kotlin caution policy, bounded logical transport, explicit simulated response, complete source accounting and an offline results viewer. Start with [the lab README](../tools/junction_lab/README.md) and [measured results](junction-lab-results.md). The wider optimization controller in [junction-flow-spec.md](junction-flow-spec.md) remains a research design; no MPC, traffic-benefit guarantee or production phone integration is implied.

The original prior Orca provider session remains read-only: do not resume or modify it. The user wants an expandable foundation, strong algorithms and compelling visuals. The earlier request to brainstorm first was followed by explicit implementation authorization. The Junction Lab patch did not change the Android scene, BLE envelope or road-mode behavior; the later swarm slice above adds Android changes.

## Latest implementation and verification

- New `core/.../junction/` policy and TSV CLI, with `:core:junctionPolicyJar`; 18 new Kotlin tests. Python runs the actual built Kotlin jar rather than duplicating the policy.
- New `tools/junction_lab/`: scenario, logical mesh, response/evaluation runner, pinned requirements, 12 tests, contract, independent-review dispositions and responsive HTML viewer. Generated files live under ignored `output/`.
- Open `tools/junction_lab/output/comparison/report.html` for the three-seed default run. `output/lighter-demand/report.html` contains the second operating condition. Each report embeds actual JSON and opens offline. The source viewer also supports loading result files.
- Default seeds 7/19/42: spillback proxy 50 s in every mode. Mean delay proxy no advice/local/mesh = 4371.35/4372.24/4501.09 vehicle-s. Mesh increases this measure by 2.97%; do not market a reduction. Lighter demand also shows no benefit: zero spillback proxy throughout, mean delays 155.88/155.91/185.39.
- Added diagnostics after review: full upstream stopped extent, peak source backlog, independent SUMO counters, cap activation speed/duration, constant-speed time-to-report and time-to-first-stop. Historical-position crossing delay can include red-light waiting and is not a safety margin.
- Final 12-test Python run passed with real SUMO/JVM integration enabled (15 test simulations). Zero compliance/penetration identities, complete-loss fallback, source conservation, deterministic replay and actual response effects are checked. No teleports mask congestion.
- Gradle `:core:test :core:junctionPolicyJar :sim-core:test :app:testDebugUnitTest` passed: 43 core, 36 simulator-core, 3 app unit tests. No physical-phone experiment was run.
- Browser QA: 64 assertions across 3 seeds, 3 modes and 5 times, malformed nested input, duplicate modes, invalid times/metrics, safe text rendering, disabled relays, playback and reduced motion. Screenshots inspected; 1440 px and 390 px layouts had no horizontal page overflow. Browser console was empty on final inspection.

Reproduction commands are in the lab README. This machine used JDK `C:/Users/hites/dev_tools/jdk-17.0.14+7` and an external venv at `C:/Users/hites/AppData/Local/RoadMeshReview/junction-venv`; the README gives portable local-venv setup. Default PATH Java is older, so set JAVA_HOME or pass `--java` explicitly. Files remain uncommitted; no push was performed in this implementation session. Preserve the earlier untracked research documents.

The code graph was refreshed in fast mode as project `C-Users-hites-Desktop-PROJ_MAIN-RoadMesh-iqoo`. Its configured exclusions include `tools/`; use exact source reads for the new lab when graph results are insufficient. Kotlin policy discovery is indexed. No graph artifact was written into the repository.

## Latest orchestration state

Fresh Orca run `run_cdda784b4dec` did not resume the earlier provider session. Codex delivered the policy/CLI/tests (task `task_914b7c802049`, succeeded); Antigravity delivered the viewer (task `task_ea7e69d0fe79`, succeeded). Their owned terminals were released and archived. The coordinator integrated the SUMO harness and corrected viewer validation, presentation and metric definitions after browser testing.

Claude provided the independent model critique (task `task_2f8a334686f4`) but its final turn ended with API `ENOTFOUND`, without `worker_done`. Its dispatch `ctx_69b4e39b27af` was explicitly abandoned after that positive final-turn evidence; task outcome is failed/interrupted, not completed review. The coordinator checked the findings, implemented the relevant diagnostics and finalized [review dispositions](../tools/junction_lab/REVIEW.md). No retry was started. Claude's externally registered terminal was left intact; no process stop is claimed. Earlier failed Claude launch attempts are also settled. No active dispatch or reclaimable terminal remains in this run.

The sections below preserve the earlier planning handover as historical context. Statements there about experiments not yet running describe that earlier stage and are superseded by the implementation status above.

## Durable deliverables

Read these rather than reconstructing research from chat history:

1. [Cooperative traffic plan](cooperative-traffic-plan.md): product scope, existing code seams, four graphs, contracts, algorithms/equations, BLE policies, validation and phased gates.
2. [Evidence matrix](research/traffic-evidence.md): 15 traffic/sensing/response entries, 8 networking/trust/platform entries and 3 evaluation-resource entries. Each identifies access depth, assumptions and transfer limits. This is a focused review, not a systematic meta-analysis.
3. [Visual storyboard](demo-traffic-storyboard.md): same APK on three phones, 90-second and four-minute choreography, layered explanation, counterfactual evaluation and honest failure states.
4. Concept visual: [editable source](../work/traffic-plan/roadmesh-cooperative-road.html). This is an inline explanatory HTML fragment, not an implemented app screen or traffic simulator. It illustrates three hazard scenarios, four story moments and pedestrian relay participation.
5. [Junction-flow specification](junction-flow-spec.md): corrections to conservation, spatial spillback, conflict clearance, H1–H5, risk accounting, decomposition and capacity claims; proposed local advisory arbitration; two-junction experiment and visual continuity scene. Includes primary-source and current regulatory qualifications.

Current implemented behavior is documented separately in [architecture.md](architecture.md), [implementation-plan.md](implementation-plan.md), [demo-runbook.md](demo-runbook.md) and [validation.md](validation.md). Older research/archive documents contain aspirational claims; do not confuse them with shipped behavior.

## User's intended product

Many road problems develop before an approaching driver sees them: phantom stop-and-go waves, road defects, congestion and intersection spillback. Participating phones contribute local observations; algorithms combine relevant evidence; a mesh carries timely warnings toward affected vehicles. Opted-in pedestrian phones can help relay information.

Three loaner phones make the idea tangible using the same application:

- **A / Observe:** the existing 3D car scene produces a demonstration event.
- **B / Carry:** relay the event; eventually demonstrate an explicit pedestrian participation mode.
- **C / Prepare:** receive a relevant caution with visual, offline speech and haptic output.

Hundreds of participants across an area are a future deployment objective, not a claim of hundreds of mutually connected radios in one interference cell.

The pitch concept is a constructed causal chain: **surface defect → braking → backward-moving wave → queue threatening an intersection → warning reaches the next driver through a pedestrian relay**. Also retain a separate no-bottleneck ring scenario to explain true phantom jams. Do not imply all waves are caused by potholes or that the system has inferred the scenario's true cause automatically.

## Recommended capabilities, all still proposals

- **WaveGuard:** start with a simple upstream slowdown warning; add a conditional backward-front estimate when observations support it.
- **RoadMemory:** qualified surface-impact candidates, road-aligned clustering, independent-pass lineage, uncertainty, contradiction, revision and expiry.
- **JunctionShield:** persistent queue observations and downstream receiving-space risk; later, possible spillback-cycle detection.
- **Community Relay:** opt-in participation independent of vehicle sensing eligibility, with bounded energy/airtime/storage and visible Stop.
- **RouteGuard, later:** feedback-aware route suggestions only after adequate observation and driver-response validation. Avoid sending everybody to the same supposedly best alternative.

Recommended first complete new slice: **WaveGuard + Community Relay + inspectable evidence and delivery deadlines**. This recommendation has not been approved for implementation merely by being written down.

## Architecture decisions worth preserving

Separate four graphs:

| Graph | Meaning |
|---|---|
| `G_road` | Directed road segments, junction movements and legal connectivity; determines relevance and traffic state |
| `G_radio(t)` | Time-varying directed contact opportunities; determines whether useful information can arrive |
| `G_evidence` | Observations and derivation lineage; prevents double counting and explains assessments |
| `G_wait(t)` | Evidenced blocked movement dependencies; identifies candidate spillback cycles |

A radio neighbor may be on another road or a flyover. A pedestrian may forward a warning while moving perpendicular to the affected road. The carrier's location is not the hazard's location. Contact paths must exist in time; a connected drawing is not proof of delivery.

Use bounded, pure policies and injected dependencies. Start with packages and small ports, not an elaborate plugin framework. Proposed logical contracts are `Observation`, `TrafficEstimate`, `HazardRecord`, `Advisory`, `Receipt` and `DecisionTrace`. Suggested ports include observation sources, map matching, evidence store, traffic estimation, hazard/advisory policies, dissemination, transport and trace sinks. Keep the scene independent of traffic algorithms and transport.

Original observations and derived assessments are different message types. A derived object has its own issuer/revision and bounded original-support references. Repackaging or forwarding evidence does not create independence or refresh its age. A digest alone cannot prove that support sets are disjoint.

## Algorithm findings and corrections

**Determinism:** identical versioned evidence and configuration can yield identical assessments. This does not imply certain knowledge of the future. Different nodes can legitimately disagree because they have different observations. Freeze time/window semantics, ordering, quantization and tie-breakers; provide explicit insufficient/conflicting/stale states.

**Wave estimation:** use qualified slowdown-onset points `(s,t)` on a common directed corridor. A bounded Theil–Sen fit estimates propagation speed. Exclude pairs with unresolved clock/position separation; require persistence and compatibility. A negative median alone is insufficient. Conditional encounter time is `(s_front - s_receiver)/(v_receiver - c)` at a common reference time, with positive usable closing speed and model/measurement uncertainty. Pair-slope dispersion is not a calibrated confidence interval. Materializing pair slopes costs quadratic scratch space; sorting adds a logarithmic factor to quadratic work.

**Queues:** unknown participation makes device counts a poor substitute for population density. Silence does not mean a road is clear. A newly observed stopped probe can make the apparent tail jump without any actual growth. A stopped span is not necessarily one contiguous queue. Require temporal association, direction, movement/head context and coverage; otherwise report observations or abstain.

**Intersection logic:** finite receiving storage matters. The plan includes a restricted CTM-style conservation model for offline exploration, with explicit units and positive backward-wave magnitude. A road-map cycle is ordinary; a wait-for SCC identifies a possible dependency cycle only after blocked edges are evidenced. Signal phases, discharge possibilities, missing movements and persistence matter. No proof of deadlock or signal-control authority follows from an SCC.

**Surface sensing:** begin with rough-road candidates. Correct orientation/gravity, gate vehicle/mounting quality, use speed-conditioned features and cluster uncertain locations. Bumps, joints, handling and suspension differences are confounders. An impact warns later vehicles, not the first vehicle that already hit the defect. Repeated packets do not establish pothole depth, type or a safe avoidance maneuver.

**Evidence/trust:** merge exact bounded support sets idempotently; overlapping `{A,B}` and `{B,C}` give `{A,B,C}`, not four observations. Two phones may occupy one vehicle. Unsigned IDs and self-issued keys do not establish independent vehicles. Authentication, sensor truth and independence remain separate. Keep open-network claims conservative and explicitly provision controlled test participants.

**Age:** choose one consistent age model. Do not add dwell twice to a source-to-current-clock age. Advance received bounds using local monotonic time and include transfer/scan uncertainty. A new assessment retains the age limits of its support. Long-lived records require replay retention/tombstones covering their admitted lifetime and clock tolerance.

**Dissemination:** road-scoped forwarding, comparable progress on the same branch, bounded contention/suppression, priority classes, earliest deadlines, fairness, coalescing and expiry checks immediately before transmit. A downstream copy must not suppress the only useful upstream relay. Carrying is more plausible for persistent hazards than expiring brake events. Sparse receipts must distinguish received, accepted and presented; no ACK storm or claim that presentation means driver response.

## What the repository already implements

Baseline commit: `c8ddaa665095c7eb7dde9d711f2ade9776efec4f` on `main`. The previous implementation was pushed before this planning work; this session did not commit or push its new documents.

- Pure Kotlin `:core`, Android `:app`, reusable `:scene`, `:sim-core` and `:simulator-app`.
- Single-owner `MeshEngine` with publish/receive/tick effects, clock/position/link ports, full event IDs, TTL, freshness, relevance, duplicate suppression and bounded state.
- Android actor ownership, foreground service, finite serialized BLE extended-advertising bursts, bounded command/TX queues and diagnostics.
- Original 27-byte packet wrapped by a **58-byte unsigned envelope** containing full origin/session/sequence/forwarder identity and provenance.
- Real scene-triggered DEMO braking, Observer caution/TTS/haptics, export and explicit failure handling. Live camera/IMU hazard producers are not connected.
- Road mode requires live GNSS and rejects DEMO/REPLAY/synthetic inputs. Existing road relevance is an ahead/heading cone, not map matching.
- Stage positions are synthetic, 100 m apart; an adjacency acceptance filter forces A → B → C even if all phones physically hear one another. This is **two radio links and one completed relay**. No reverse receipt exists.
- Logical 100/300/500-node fleet tests use bounded local neighborhoods and a simplified loss model, not RF airtime/collision validation.

Important extension limits: unsigned suppression/admission is forgeable; the generation-time field is reconstructed modulo an hour; short dedup retention is unsuitable for persistent hazard records; TX FIFO can lose useful warning time; received callbacks currently drop useful radio metadata; mixed-profile hop budgets need interoperability work; persistent cleartext IDs create linkage concerns.

New rich bodies/signatures/lineage require an explicit protocol revision and measured byte/airtime feasibility. Do not reinterpret the existing 58 bytes or assume that extended advertising implies coded PHY support or a guaranteed range.

## Research conclusions to carry forward

- Sugiyama establishes spontaneous backward-moving traffic waves in a ring.
- Stern includes both automated control and a specially instructed human experiment. An initial worker's “automated only” summary was corrected against the full paper.
- SafeTrip/Nowakowski provides closer human-auditory-advisory evidence, with a small before/after field study. Neither it nor ring experiments establish RoadMesh benefits.
- CIRCLES' 100-CAV work uses a different sensing/control/communication setup; no transfer of effect sizes to phone BLE.
- Pothole Patrol and Nericell motivate sensing and repeated observations, with significant hardware/mounting/context limits.
- Capacity-aware back-pressure and sparse-probe estimation highlight finite storage and uncertainty; their assumptions and control authority do not automatically apply here.
- Delayed route guidance can create congestion oscillations. Optimization must include the effect of recommendations on traffic.
- Broadcast-storm, contention-forwarding, information-age, delay-tolerant networking and Sybil research motivate mesh policies; none establishes this application's RF performance.

Full primary links, access depth, publication/preprint distinctions and evaluation resources are in the evidence matrix. Do not reuse worker-generated illustrative latency, confidence, path-loss, accident-savings or numeric speed prescriptions as measured facts. Unverified regulatory claims from visual brainstorming were excluded.

## Visual requirements are part of the product

Preserve the friend's 3D car. Keep **Observed → Estimated → Advised** distinct through shapes, labels and color; show radio contacts separately from road flow. Unknown areas stay unknown. Keep driver output minimal; detailed graphs belong to the presenter/parked view.

A comparison scene must use matched demand/initial conditions/exogenous randomness and a stated driver-response model. Let trajectories diverge after intervention. Do not manually paint an improvement or let the algorithm use simulator ground truth. Synthetic vehicles, live phone events, recorded replay and proposed features must be visibly distinct.

Turning off B in the filtered stage chain demonstrates dependency/failure; restarting B demonstrates recovery, not alternate-path self-healing. Source TX completion is not C's receipt. GNSS multipath is uncertainty, not synthetic provenance. No NPU, lane-accuracy, cryptographic or accident-reduction claim is implied by the animation.

## Evaluation and current verification

Separate inference accuracy, communication performance, human response and causal traffic outcomes. Use local-only, simple warning/flooding, no-advice and perfect-information baselines. Test sparse participation, missing maps, multiple phones per vehicle, clock faults, overlap, false sources, red-light queues, partitions, screen-off behavior, contention and stale alternatives.

SUMO is proposed as an offline version-pinned evaluation harness, not a phone dependency. Historical trajectory replay can evaluate inference but cannot alone establish the causal effect of advice that changes trajectories. TTC/PET are surrogate measures, not accidents prevented.

Completed in this session: document local-link checks; new-file whitespace checks; independent algorithm review; concept visual syntax and 48 scenario/moment/relay state checks at 320/736-pixel widths in an offline DOM, including state restoration. No connected browser was available for screenshots. No new Android build, hardware experiment or traffic-effect simulation was run.

## Workspace and orchestration state

At handover, these new files are uncommitted/untracked and must be preserved:

- `docs/cooperative-traffic-plan.md`
- `docs/research/traffic-evidence.md`
- `docs/demo-traffic-storyboard.md`
- `docs/junction-flow-spec.md`
- `work/traffic-plan/roadmesh-cooperative-road.html`
- This `docs/HANDOVER.md`

Orca run `run_e71e91933500` is fully settled. Claude researched traffic, Antigravity supplied visual concepts, and a Codex worker reviewed BLE/contracts; the coordinator verified/corrected their findings. Antigravity's managed terminal was released. Claude's terminal is external; the Codex terminal was marked user-owned by Orca. Do not close or repurpose those user/external resources. No active worker result is pending.

The temporary local preview server was stopped. An offline visual-check dependency/script lives outside the repository under `C:/Users/hites/AppData/Local/RoadMeshReview/visual-check`; it is not a product dependency.

For future code discovery, **prefer codebase-memory-mcp graph tools**. Project `RoadMesh-iqoo` was indexed; check status and reindex if needed. Use search_graph before exact get_code_snippet calls. Ordinary reads are appropriate for these documentation files.

If creating/updating the inline explanation, **reload the full visualize skill before creating or updating a visualization**. Keep it a fragment and return its visualization reference in that same turn's final response. Do not silently turn it into an app feature.

## Junction flow review and next step

Three Codex reviewers checked the mathematics, distributed handover and feasibility/deployment claims. This was a new collaboration review; none of the prior settled Orca workers or the read-only provider session was resumed.

Key corrections now preserved in `junction-flow-spec.md`:

- Queue PCU and queue length in metres are separate quantities. Mixed-traffic PCU does not replace actor footprints or pedestrian geometry.
- CTM normalization needs speed times step divided by cell length. Junction entry and exit flows differ when actors spend time inside the box. External demand needs conserved backlog; never clip actors away.
- Storage bounds can hold during complete gridlock. Evaluate buffer invasion, blockage duration, throughput and all upstream/source delays.
- Conflict safety uses occupancy intervals and exit-to-entry clearance; arrival-time separation alone is insufficient.
- H1 separates idempotent evidence handling from uncertain physical association. H2 requires common frames/epochs, correlation-aware residuals and uncertainty/coverage; agreement is not truth.
- H3 becomes one nominated phone's local output arbiter. Region replicas send proposals. Do not require an always-available globally unique cluster owner under partitions. Two phones in one vehicle remain a nomination issue.
- H4 is a conditional deadline/availability target. The supplied 21.67-m overlap is correct arithmetic under assumed delays, not measured BLE feasibility. H5 bounds advice changes, not actual human jerk.
- Integrity, expiry and contradictory-output prevention are admissibility conditions above all throughput objectives. Chance claims specify a model, conditioning information, joint horizon and risk budget; infeasibility remains visible.
- Flows follow human response and legal service; they are not app actuators. Pressure is a benchmark/heuristic until a specific relaxation is derived. Shared airtime, evidence, routes and risk budgets also couple local problems.
- Summing incompatible movement capacities overstates service. Rerouting can use spare paths, not add `p*pi` physical capacity. Unknown participation prevents universal queue inference or a universal minimum participation level.
- SPaT reception is observation, not signal-control authority. AIS-230 dates verified in the August 2026 official release are proposed draft dates, not a verified final mandate. DPDP commencement is staged. Voice-only use and ISO references do not establish deployment permission/compliance.

The additional primary source is Daganzo's full 1994 **network** CTM report (distinct from the original highway paper recorded as abstract-only in the evidence matrix). SUMO SSM documentation also has material limits: vehicle encounters, required right-of-way information for PET, simplified geometry and missing lateral cases. Do not claim default output validates pedestrian safety.

Recommended next experiment: two junctions, one finite connecting segment, explicit source queues, fixed environmental signal/right-of-way rules, one vehicle class and one queue caution. Compare no advice, local-only, mesh-informed and a labeled oracle; extend later to bounded candidate-policy optimization, mixed users, conflict geometry and rerouting. Algorithm inputs remain masked; response, inference and communications are evaluated separately. No experiment has run yet.

The proposed visual preserves the car and three-phone roles while showing region context feeding one recipient arbiter. A missing relay makes knowledge stale/unknown and late proposals expire; no fake handover or counterfactual benefit is animated. The existing HTML visual was not changed in this review.

Review checks: an independent reviewer read the junction specification and found no remaining material mathematical errors; its infeasibility example was clarified. Local links, whitespace, fenced blocks and 55 math groups were checked. These are documentation checks, not a solver proof or a rendered preview. No new application tests or hardware experiments were run.

Preserve all uncommitted documents. Next work depends on the user's response to this formulation; do not implement or push until requested.
