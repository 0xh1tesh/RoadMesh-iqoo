# RoadMesh decentralized swarm implementation plan

RoadMesh should become an orchestrated, decentralized swarm of participating vehicles that shares observations, maintains a local road picture, adapts communication and estimation, and produces timely driver advice. The first product focus is temporary bottlenecks and hidden queue tails in Indian mixed traffic. Accident prevention and shorter journeys are intended outcomes to evaluate, not established results.

Planning baseline: 10 October 2026, repository commit `c4faa69`, with the existing uncommitted Junction Lab work preserved. This is the next implementation plan; the features below are not implemented by this document. It carries forward the user's explicit priority that the swarm must learn and act dynamically, and demonstrate useful differentiation from navigation and fixed V2I.

Implementation update, 10 October 2026: the first prototype slice is now implemented in version `0.4.1-swarm-preview`. Original evidence identity/freshness, movement-aware local grouping, manual narrowing reports, a comparison screen and a synthetic shadow-learning experiment are available. The [jury demo guide](swarm-jury-demo.md) records the delivered scope. Deadline-aware scheduling, distributed task leases, map matching, live learning and traffic benefit validation remain future work; the full roadmap below is not complete.

## Product direction and differentiation

The initial promise to earn is: **nearby vehicles build and update a shared picture of temporary road problems, even without installed roadside equipment, and deliver relevant advice before another participant encounters the problem.** A single phone retains its local sensing; additional observers can extend awareness when contacts and viewpoints permit.

GPS remains a positioning input. Navigation is a separate baseline: Google Maps already offers traffic-aware navigation and shared incident reports. A simulated navigation baseline must not be labeled as measured Google Maps performance. [Google Maps documentation](https://support.google.com/maps/answer/3273406?hl=en-NZ)

V2I is vehicle-to-infrastructure communication, not synonymous with centralized processing or dependence on the internet. V2X already includes vehicle-to-vehicle communication, shared perception, queue warnings and speed harmonization. RoadMesh belongs broadly in this cooperative-systems space; phone-based BLE does not establish interoperability with standardized V2X radios or messages. [USDOT application catalogue](https://its.dot.gov/research-areas/V2X-Deployment/overview/v2x-applications/), [ETSI Collective Perception Service](https://www.etsi.org/deliver/etsi_ts/103300_103399/103324/02.01.01_60/ts_103324v020101p.pdf)

The differentiation hypothesis is deployment flexibility and useful local cooperation under mixed traffic and partial participation. Multi-hop collective perception is also existing research, so neither a mesh nor message prioritization alone is a novelty claim. [Multi-hop collective perception research](https://arxiv.org/abs/2404.07761)

| Dimension | Advantage to test | Where another system may be stronger |
| --- | --- | --- |
| Temporary obstructions | Participating observers follow changing road conditions without installing a sensor at each location | An installed elevated sensor may see more of a covered intersection |
| Local freshness | Recent observations can reach relevant nearby participants directly | Delivery depends on actual phone contacts, platform limits and contention |
| Mixed traffic | Represent vehicle footprints, lateral movement and unequipped actors that participants can observe | RoadMesh cannot infer unseen actors or precise geometry from GPS alone |
| Deployment | Reuse mounted phones and optional fleet dashcams; measure installation effort and total cost | V2I can provide continuous coverage independent of participating traffic |
| Resilience | Continue local sensing when a peer, optional display or internet connection disappears | Loss of the only observer or relay genuinely removes remote coverage |

Indian mixed-traffic modeling needs lateral as well as longitudinal relationships. A Bangalore study supports accounting for vehicle type, opposing traffic and lateral placement, but does not validate this swarm design across Indian roads. Include motorcycles, auto-rickshaws, buses and pedestrians in progressively calibrated tests; treat market obstructions, bus stops and roadworks as explicit scenarios rather than national generalizations. [Kotagi, Raj and Asaithambi](https://jtte.chd.edu.cn/cn/article/pdf/preview/10.1016/j.jtte.2018.06.008.pdf)

## Meaning of orchestration and action

Orchestration means assigning useful sensing and relay work, combining evidence, resolving competing advice on each recipient, and observing outcomes. It does not require a permanent leader or identical views on every phone.

Each device runs the same bounded loop:

1. Observe locally and qualify the sensor, participant and position.
2. Associate observations with candidate road regions and temporary constraints.
3. Exchange relevant fresh evidence with available peers.
4. Estimate local conditions and expose gaps or contradictory evidence.
5. Choose communication tasks and produce advisory proposals.
6. Let one nominated phone in each vehicle arbitrate its driver's output.
7. Record subsequent observations and evaluate prediction and delivery errors.

The first physical actions are human responses to short advisories. The app can autonomously change relay selection, sensing effort within resource budgets, and local estimates. Vehicle speed, steering, signal phases and right of way are not app actuators. Cooperative crossing or speed control belongs in a separate simulation research track until an appropriate vehicle interface and validation exist. No peer or language model grants permission to enter, overtake or proceed.

An optional laptop visualizes telemetry; it must never be needed for membership, inference or advice. Demonstrate this by disconnecting it while phones continue to operate. Temporary task ownership suppresses redundant work; partitions can create duplicate workers, so correctness cannot depend on a globally unique cluster leader.

## Existing foundation and prerequisite repairs

| Existing code or artifact | Reuse | Required change before relying on it |
| --- | --- | --- |
| `core/.../mesh/Roster.kt` | Presence and neighbor expiry | The local mean-heading chain is unsuitable for opposing approaches; use directed region membership and represent partial views |
| `core/.../road/RoadMap.kt`, `RoadQueries.kt`, `RoadPicture.kt` | Local records and road queries | Preserve observation identity, original age, road association and support lineage end to end |
| `core/.../mesh/Control.kt` | Versioned control serialization | Current record counts cannot recover original observer identities; introduce a compatible evidence schema and byte budget |
| `app/.../service/MeshSession.kt` | Serial session ownership and BLE lifecycle | Retransmission must not manufacture a fresh observation; forwarding must not depend on being behind a locally computed rank |
| `RoadPicture.forecast` | A testable baseline | Fixed wave speed and clearance time are assumptions; remove guaranteed clearance language and uncalibrated numerical speed advice from the new advisory path; test low-speed bounds |
| `app/.../ui/MeshMap.kt` | Existing map presentation | Missing live positions must remain unknown; distinguish inferred rank links from observed contacts and confirmed delivery |
| `tools/mesh-desk/` | Opt-in desktop telemetry and replay | Add local beliefs, evidence lineage and comparison views; keep it an observer |
| `tools/junction_lab/`, `core/.../junction/JunctionPolicy.kt` | Matched experiments and shared Kotlin decisions | Preserve negative results; add new policies and baselines under new versioned runs |

In particular, `RoadPicture.combine` can count several holders of the same report as corroborating sources. `MeshSession.refresh` can reoriginate an old report and receivers can reset its apparent observation age. These are evidence-integrity problems, not merely UI issues.

The existing Junction Lab found a **2.97% increase** in its fixed-horizon delay proxy with mesh cautions and the modeled slowdown response, with no spillback-proxy reduction. Lighter demand also showed no benefit. This invalidates a benefit claim for those tested settings, not every possible cooperative policy. Preserve those runs as regressions and evaluate awareness and traffic outcomes separately. [Measured results](junction-lab-results.md)

## First implementation deliverables

### Shared evidence and dynamic local groups

Build one pure Kotlin package, initially `core/.../swarm/`, integrated through the existing session owner. Avoid a new distributed platform or citywide optimizer.

- Introduce immutable `ObservationId(origin, session, sequence)`, record revisions, capture-time uncertainty, road/frame version, location uncertainty, expiry, source type, LIVE/REPLAY/DEMO provenance and bounded support references. Observations, derived estimates and advisory proposals are separate types.
- Keep both received-at monotonic time and original evidence age. Clock uncertainty may make age unknown or inadmissible; relays never reset it. A new observation can refresh a record, while forwarding cannot. Keep bounded replay tombstones for the admitted lifetime.
- Preserve competing road hypotheses near parallel roads, flyovers and junctions. Start with a small explicit corridor map and coarse regions; precise lane association requires separate sensing evidence.
- Group peers by directed region and compatible movement, rather than a single global rank. One phone may participate in several nearby region contexts. Role capabilities include observing, relaying and consuming advice, and are not mutually exclusive ranks.
- Deduplicate by original identity, not arrival path. Distinct phone IDs are claimed sources, not proof of independent vehicles. Known test vehicles get an explicit nominated phone; unresolved correlated evidence remains non-additive.
- Schedule eligible messages by urgency, recipient relevance, age, novelty and airtime cost. Enforce hard expiry/resource limits first, then deterministic priority with tie-breakers, backoff and a bounded fairness budget. Compare against simple flooding before adopting more complex optimization.
- Select optional observation/relay tasks using short expiries and hysteresis. A task claim is an optimization hint; the task must tolerate duplication and loss. Suppression must not assume that overhearing a packet proves its delivery downstream.

Deliverable: phones discover relevant peers, handle joins and departures, and exchange evidence without inventing freshness, independent support or global agreement. Partition tests end in honest local-only or unknown state; an alternate path is used only if it exists.

### Temporary bottleneck and hidden queue advisories

Use the same evidence pipeline for two initial event families: a temporary obstruction and an observed queue tail. Start with explicit event injection/manual reporting and labeled replay for protocol testing, then add the actual camera observation adapter. An injected event does not validate perception.

Model the road as coarse usable regions plus temporary occupied or uncertain areas. Keep vehicle dimensions and lateral movement in the experimental geometry; do not model all traffic as one ordered car queue. Object association across cameras can be ambiguous and must retain alternatives rather than invent a fused trajectory.

Implement `BottleneckEstimator` with `unknown`, `observed`, `supported`, `conflicting` and `stale` states. Expiry returns knowledge to unknown, never to clear. A clearance observation needs its own identity and relevant spatial coverage. Two observations are not automatically a calibrated probability.

Implement `AdvisoryArbiter` on the nominated recipient phone. It checks scope, fresh evidence, movement relevance, confidence state, deadlines and attention budget, and suppresses incompatible or repetitive output. Initial templates include “Road narrowed ahead; opposing traffic reported” and “Queue beyond the junction; keep the junction clear.” They convey the condition without prescribing an unvalidated speed or granting a crossing slot.

A proposed conflict graph links overlapping movement regions and downstream storage dependencies. In the first app slice it informs cautions; it does not reserve physical road space. Unknown pedestrians, motorcycles and other unequipped actors remain possible even when no peer reports them.

Deliverable: one participant learns about a relevant condition beyond its own view, can inspect why it was warned, and sees that warning expire or change when the evidence changes.

The next simulation policy evaluates a small set of candidate advisories over a bounded horizon: no additional advice, early approach caution, and keep-exit-clear advice. Use the existing junction geometry and scheduler as a controlled benchmark, then estimate candidate outcomes from the recipient's permitted evidence and an explicit response model. Penalize delay, blockage and advice switching, subject to legal movement, occupancy, uncertainty and resource constraints. Include unequipped actors and nonresponse. If candidates are infeasible or indistinguishable within uncertainty, retain the baseline. This supplies an executable path from shared awareness to coordinated action without treating a human driver as an actuator. Cooperative arrival-window proposals are a later simulation extension, not live crossing permissions.

### Adaptive estimation and communication

Implement a small `AdaptiveStats` component and versioned trace format. Learning must have an input, an observable target and a held-out evaluation; increasing a support counter is not a learned model.

| Learn first | Evidence used | Initial deployment |
| --- | --- | --- |
| Which contacts deliver useful messages before expiry | Bounded sampled receipts, losses and censored outcomes; sequence and session identity | Shadow ranking beside the deterministic relay policy |
| Queue persistence and clearance-time uncertainty | Directly observed onset/clearance episodes, separately labeled truth in simulation | Shadow prediction; no live numerical speed instruction |
| Sensor or association reliability by supported context | Reviewed labels or independently observed outcomes | Offline calibration and recorded prediction intervals |

Use bounded rolling summaries and sample counts with forgetting for changing conditions. Unknown delivery is not success; relay agreement is not a truth label. Include sensor/mount/context version so incompatible data are not pooled. Where training data are insufficient, keep the deterministic baseline.

For decentralized learning, each node first updates its own estimates. A later candidate exchanges compact, versioned summaries keyed by original episode IDs. Merge only compatible data with resolvable overlap; do not sum copied summaries. Authenticate known pilot participants and cap contributions, while recognizing that authentication does not establish sensor truth. Raw video sharing is unnecessary for this slice.

A frozen candidate may enter a bounded live communication policy only after replay and device checks. Expiry, provenance, resource bounds and advisory eligibility remain fixed constraints. Use model versions, recorded rollback and drift-triggered return to baseline. Online trial-and-error driving instructions and unreviewed fleetwide model updates are outside this release.

Deliverable: a replay shows what the estimator learned, its prediction error before and after, sample coverage and failures on unseen scenarios. A simulated comparison cannot substitute for measured human response.

### Swarm comparison desk

Extend Mesh Desk and the existing Junction Lab viewer instead of creating another disconnected animation.

The presenter can switch between road regions, observed radio contacts, evidence lineage and blocked-movement dependencies. A selected vehicle shows its own knowledge; unknown areas remain visible. Display observed, estimated, advised, received, presented and response-assumed/measured stages distinctly. Every moving vehicle and graph edge comes from a trace or an explicitly labeled synthetic fixture.

Provide synchronized comparisons for navigation baseline, local sensing, simple flooding, fixed V2I and RoadMesh. Show age at presentation, useful warning coverage, false alerts, expired evidence, transmitted bytes, delays, arrivals and source backlog. Label absent coverage rather than drawing a reassuring empty road.

Allow controls for participation, packet loss, observer removal, position error and baseline infrastructure placement. A control reruns the model or selects a matching recorded run; it must not cosmetically change a metric. Live telemetry and simulation results stay separate.

Deliverable: a reviewer can see what each system knew, why a decision changed, and whether the measured outcome actually improved.

## Graph and protocol design

Retain separate graphs for directed road connectivity, observed radio contacts, evidence derivation, geometric movement conflicts, and evidenced blocked-movement dependencies. A geometric conflict is not a blocked exit: a cycle of conflicting movements cannot establish gridlock. Their nodes and edges have different meanings. A nearby phone is not necessarily on the same road; a communication relay is not the location of a hazard.

Maintain bounded spatial candidate sets and update affected regions incrementally. Limit the peer set, evidence window, association candidates and queued work with named configuration values. Profile processing time and memory on target phones before increasing the bounds. Fixed limits must surface rejected or degraded work rather than silently clearing unknowns.

Initial logical types are `Observation`, `RegionBelief`, `TaskClaim`, `AdvisoryProposal`, `DeliveryReceipt` and `DecisionTrace`. Receipts distinguish received, accepted and presented; none proves human compliance. Do not compare unsynchronized wall clocks as precise radio latency.

Do not squeeze these fields silently into the current control record. Specify a new protocol version, payload/fragment/reassembly limits, replay handling and compatibility rules before Android integration. Older records lacking lineage remain legacy observations with unresolved support. Malformed or incompatible frames cannot alter live beliefs. Known-device authentication and recorded origin identity belong in the pilot protocol; open enrollment and Sybil resistance remain separate work.

## Demonstrations and fair comparisons

The flagship demonstration is a temporary stopped bus or truck narrowing a two-way road. An observer reports the obstruction; another contributes an approach observation; a following participant receives a relevant caution beyond its own view. The display exposes changing region membership and evidence age. Remove a relay, then test both no-alternate-path and actual-alternate-path cases. Move the obstruction beyond the modeled roadside sensor coverage, and also run a case where that sensor has the better view.

Use two experiment suites. The awareness suite replays identical trajectories and visibility to isolate information quality and transport. The response suite permits trajectories to change under separately specified driver-response models and evaluates complete traffic outcomes. Do not compare different warning cohorts as if they were paired observations of the same event.

| Baseline | Inputs and purpose |
| --- | --- |
| No advice | Existing traffic reference; not a stand-in for Google Maps |
| Navigation model | Same map/GPS plus explicitly parameterized traffic and incident updates; sweep freshness rather than inventing a fixed Google delay |
| Local sensing | Same participating devices and local observation quality; isolates the benefit of sharing |
| Simple flooding | Same observations and radio budget; isolates the proposed message scheduler |
| Fixed V2I | A specified sensor footprint, observation quality, transport and compute; include covered and uncovered locations, and its independent power/backhaul assumptions |
| RoadMesh | Only its local/received evidence; no simulator truth leaks |
| Oracle | Perfect-information headroom, prominently labeled and excluded from deployable-system claims |

Run both matched observation-budget comparisons and realistic deployment comparisons with costs itemized. Fixed V2I can operate without cloud connectivity; do not disable it simply because mobile internet is disabled. If it has better sensing or signal information, expose that advantage. Infrastructure-free coverage requires participating observations and radio connectivity, not a promise to work everywhere.

Scenario order: temporary bottleneck; hidden queue tail; two junctions with finite downstream storage; mixed lateral movement and unequipped actors. Add low demand where no improvement is possible. Include partial and biased participation, compliance from zero upward, asymmetric links, partitions, congestion bursts, restart, clock error, stale maps, conflicting reports, identity spoofing and multiple phones in one vehicle.

## Acceptance gates

Freeze thresholds and scenario splits before evaluation. The numbers below are proposed engineering targets, not measured results or public-road safety thresholds.

| Gate | Required evidence |
| --- | --- |
| Correctness | Zero violated identity, expiry, resource and local-output invariants in deterministic/property/adversarial tests; copied reports never become extra observers; forwarding never extends validity |
| Useful awareness | On matched events, positive held-out improvement in warning coverage before an independently defined response deadline versus local-only, at a predeclared false-alert budget; report uncovered and missed cases in the denominator |
| Communication | Initial target: at least 20% fewer total transmitted bytes than flooding at no more than 2 percentage points lower overall deadline-delivery rate in the declared workload; urgent-event delivery has a separate non-inferiority test with its margin fixed before tuning. Count control, receipts, authentication, fragments and retries; report airtime and stressed cases |
| Learning | Frozen candidate improves its prespecified error/calibration metric on held-out scenarios over the deterministic baseline; no weakening of expiry, applicability or attention limits; demonstrate rollback |
| Traffic benefit | Proposed promotion target: at least 5% lower delay than local-only in the targeted bottleneck scenario family, with paired 95% interval excluding no benefit, and non-inferiority to no advice under a preregistered margin. Require no lower completions and no worsening of declared conflict/blockage constraints; publish failures and tradeoffs |
| Hardware | Measure capture-to-presentation age with clock uncertainty, unconditional deadline delivery, battery/thermal cost, contention, foreground/background behavior and restart recovery on named phones |

Start development with the existing seeds, then reserve at least 30 paired unseen seeds for the first synthetic comparison; use pilot variance to increase this count if necessary. Separate scenario-level training and test splits, not adjacent frames of the same clip. Select a primary outcome before tuning, report intervals and sensitivity, and retain unfavorable runs.

Preserve conservation of active, completed and waiting-to-enter vehicles. Zero equipment must recover the no-advice traffic baseline; zero compliance must leave traffic trajectories unchanged; complete communication loss must recover local-only behavior for the same sensing. Explicitly label the existing fixed-horizon delay proxy. Include source waiting and terminal backlog, and add demand-clearance runs or censored-trip accounting before claiming completed-trip time savings. Show actual intersection occupancy and completions, not only the existing spillback proxy. Simulated conflict diagnostics do not establish accidents prevented, especially where the simulator omits lateral or pedestrian interactions.

The awareness release can succeed without passing the traffic-benefit gate. In that case ship only the supported awareness claim and continue policy research. A separate controlled user study must establish comprehension, distraction burden and response before assumed compliance becomes a deployment claim.

## Implementation order

| Milestone | Concrete patch scope | Exit condition |
| --- | --- | --- |
| 0 Evidence baseline | Reproduce current tests and negative lab result; capture current UI/trace behavior; document model availability | Stable reference and unchanged user work |
| 1 Evidence integrity | Add core evidence contracts; fix lineage/age/retransmission, low-speed forecast bounds and deterministic advisory text; version protocol | Core tests prove replay/duplicate/expiry behavior; incompatible legacy input handled explicitly |
| 2 Local orchestration | Add directed-region membership and bounded relay/task policies; adapt `MeshSession` and transport; retain explicit stage mode | Join/departure/partition/overload tests and real contact measurements |
| 3 Cooperative advice | Add bottleneck/queue estimator and one local arbiter; integrate source adapters and minimal driver UI | Matched awareness suite passes; advice exposes unknown and conflicting conditions |
| 4 Proof and adaptation | Extend Mesh Desk/Junction Lab; add comparison adapters, sampled receipts and shadow learner | Held-out report and reproducible replay; frozen model and rollback demonstrated |
| 5 Pilot | Controlled multi-phone setup, first stationary/walking tests then suitable closed-course trials | Measured hardware and human-use evidence before broader claims |

Keep each milestone reviewable. Core contracts and comparison fixtures come before a more elaborate UI. Build the same Kotlin policy into the app and the lab so the demonstration does not evaluate a different implementation. Avoid concurrent Gradle builds in the shared checkout. Relevant checks include core/app/simulator tests, protocol compatibility tests, Mesh Desk server tests, real SUMO integration and Android lint/build; do not rerun unrelated suites without cause.

Future additions can include bounded route-diversion suggestions with anti-herding evaluation, authenticated emergency-vehicle awareness, recurring road-condition memory and cooperative maneuver proposals in simulation. Fleetwide federated model training and physical maneuver negotiation follow evidence from the smaller loop; they are not prerequisites for the first useful swarm.

## Immediate next patch

Begin with milestone 1: introduce immutable evidence identity and age, remove corroboration inflation, and add traces that separate sensing from forwarding. Follow with directed local membership and the temporary-bottleneck demo. This sequence turns the existing chain into a trustworthy basis for dynamic cooperation before asking it to learn or influence traffic flow.

Related project work: [cooperative traffic plan](cooperative-traffic-plan.md), [junction flow formulation](junction-flow-spec.md), [existing Junction Lab results](junction-lab-results.md), [current release description](road-mesh-v2.md), and [Mesh Desk](../tools/mesh-desk/README.md).
