# Junction layer: Reserve-then-Roll (experimental, `core/` only)

This is a proposal with an executable model behind it. Nothing here is wired into the app, the BLE envelope or the pitch yet.

## Model

The road is a graph. Junctions (any conflict point) are nodes, road segments are edges. Each edge carries a live subgraph of the road users on it; each junction carries its movements and conflict points. One 4-way junction is the proof case: one centre node, four arms, one lane each way, 12 movements (left-hand traffic: LEFT is the short turn, RIGHT crosses opposing traffic).

Every node on an inbound edge has one goal: transfer safely and smoothly through the junction to its exit edge. Formally, minimize transfer time + jerk + stops subject to:

| Requirement | Mechanism | Code |
|---|---|---|
| R1 no collision in the box | Box tiles reserved per vehicle as time intervals; any two uses of a tile separated by `gapS ≥ 2ε` (ε = timing error against the slot) | `JunctionScheduler.earliestFeasible` |
| R2 box never jams | A slot needs an **exit token**; tokens per exit ≤ its storage. Every vehicle in the box holds all it needs, so there is no hold-and-wait and no deadlock | `JunctionScheduler.schedule`, `released` |
| R3 no phantom jams | Slot changes are broadcast to the whole lane at once; FIFO re-slotting never shifts a follower more than the delayed vehicle; followers track the predecessor's *plan* | `JunctionScheduler.delay`, `ApproachLaw.followSpeed` |
| R4 arms bounded | Max-pressure order among lane heads. Demand outside the capacity region must queue on the arms; it can never enter the box | `JunctionScheduler.schedule` |
| Smooth handover | One owner per state: inbound edge → junction (from commit) → exit edge | `Transfer` |

Every failure path (no grant, missed window, lost message) ends with the vehicle **waiting outside the box**, which never causes a collision or a box jam; it only costs throughput.

## Components (`core/src/main/kotlin/com/jsons/roadmesh/core/junction/`)

- `JunctionGeometry`: arms, movements, swept paths of a vehicle footprint, tile occupancy offsets.
- `JunctionScheduler`: deterministic slot scheduler (same inputs → same table, so phones in a cluster can replicate it). `SchedulerConfig.laneHeadway` derives same-lane slot spacing from the following law at crossing speed; spacing slots closer than the following law allows makes committed cars miss their window.
- `ApproachLaw`, `Advisor`: target speed (arrive at the line at crossing speed in the middle of the entry window, constant time headway against the predecessor's plan, Gipps safe-stopping bound) and quantized, rate-limited speech advice.
- `LaneBelief`: shared estimate of one lane. A single lane is a chain, so Gaussian belief propagation is exact after one forward and one backward pass; messages are two numbers per hop. A camera gap that does not match the equipped vehicle ahead inserts a **proxy** for an unequipped vehicle.
- `EgoTracker`, `EdgeFrame`: along-edge Kalman filter (IMU acceleration in, GNSS position/speed and optional OBD speed as corrections) and projection of positions to edge coordinates (distance along, signed lateral offset).
- `Transfer`: handover state machine.

## Evidence

`JunctionSimulationTest` runs one junction with 150 m approaches and 60 m exits, drivers with a 0.8 s response lag, and a human start delay after the slot within ε. Every scenario checks collisions on world positions independently of the tile table. Report: `core/build/reports/junction-simulation.json`.

| Scenario (900 s) | Throughput veh/h | Mean delay s | Stops/veh | Collisions | Box blocked | Rear-end conflicts | Missed windows | Max spillback |
|---|---|---|---|---|---|---|---|---|
| moderate, 0.06 veh/s/arm, ε 0.4 s | 955 (all served) | 8.0 | 0.51 | 0 | 0 | 0 | 0 | 2 |
| overload, 0.3 veh/s/arm, ε 0.4 s | 1705 | 292 | 1.30 | 0 | 0 | 0 | 0 | 152 |
| north exit closed 200–500 s | 795 | 31.4 | 0.73 | 0 | 0 | 0 | 0 | 2 |
| overload, ε 0.25 s | 1900 | 276 | 0.78 | 0 | 0 | 0 | 0 | 142 |
| overload, ε 0.5 s | 1655 | 295 | 1.15 | 0 | 0 | 0 | 0 | 147 |
| overload, ε 1.0 s | 1360 | 329 | 0.96 | 0 | 0 | 0 | 0 | 162 |

The ε sweep is the main result: **timing error costs capacity (1900 → 1360 veh/h), not safety.** Overload queues on the arms (spillback past the leaves) while the box stays clear, as Theorem 4 predicts. During the exit closure, other exits keep flowing; a lane whose head wants the closed exit is blocked behind it (single-lane FIFO).

Unit tests also cover: exact chain fusion against a dense solve, proxy insertion, token caps, FIFO headway, upstream attenuation of a delay, committed slots never moving, legal handover transitions, ego tracking.

## What this model does not show

- All vehicles are equipped and comply. Unequipped vehicles, pedestrians and lane-filtering two-wheelers are not simulated; they need proxy reservations with wide uncertainty and will cost capacity.
- Box crossing is at a fixed speed along a fixed path.
- No BLE delay or loss, GNSS error, or replicated-scheduler disagreement. The scheduler is one in-memory object.
- Cross-traffic re-slotting can rarely shift a follower more than the delayed vehicle (1 of 144 re-slots in one overload run) when an already committed cross-lane vehicle is in the way.
- Re-slot traffic is high under moderate load (about 2.7 per vehicle), which matters for BLE airtime.
- Proxy association needs GNSS sigma near 2 m to separate a hidden vehicle from noise (see `LaneBeliefTest`).

## Next steps

1. Envelope revision for per-node edge state (edge, s, lane, v, a, sigmas, lead gap, intent, slot), within the BLE budget.
2. A fixed **junction-node** phone running the scheduler; vehicle phones run `EgoTracker`, `LaneBelief` and `ApproachLaw` and speak `Advisor` output.
3. A replicated scheduler across phones with an ordered request log, and the confirmation rule before commit.
4. Simulation with mixed penetration and proxy reservations, to find the equipped share at which this beats an unsignalized junction.
