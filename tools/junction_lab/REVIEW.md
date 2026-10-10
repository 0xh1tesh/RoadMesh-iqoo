# Junction Lab review and dispositions

10 October 2026. Claude supplied an independent critique of the initial contract, implementation and seed-7 smoke trace. Its final turn ended with an API/DNS `ENOTFOUND` error before revising the draft or reporting completion. The coordinator verified the findings against the final source and two three-seed datasets, made the fixes below and completed this record. This is not a completed Claude sign-off or a proof of traffic safety.

## Final assessment

The three-way harness is suitable as a reproducible, explicitly assumed baseline. It distinguishes mesh traffic from no advice, including a worse outcome. Some local-only runs exactly match no advice; this is a valid null result. Neither the overloaded nor lighter-demand condition demonstrates a traffic benefit. The data supports testing future policies, not connecting this policy to road users.

## Review findings and treatment

| Concern | Final treatment | Remaining limit |
|---|---|---|
| Historical-position crossing delay includes red-light waiting | Labels now say exactly that; added `kinematicLeadS`, `meanKinematicLeadS`, `timeToFirstStopS` and stop counts | Constant-speed distance/speed is not TTC; subsequent stopping is not necessarily caused by the caution. Censored crossings remain explicit. |
| Applying a cap need not change driving | Added `responseAppliedAt`, `speedAtResponseMps`, `capActiveVehicleSeconds`, `capActivationsAboveLimit` | Activation above cap is a diagnostic, not a causal bindingness measure. No effect is required for a valid null experiment. |
| Connector extent hides upstream congestion | Retained clearly labeled connector metric; added `maxCorridorStoppedExtentM` and peak source backlog | Stopped extent need not be contiguous. Actual junction blocking and conflict occupancy need separate geometry tests. |
| Default demand produces extensive queuing | Retained as the heavily queued baseline; added a 30-vehicle, 3-second-headway, 20-second-red comparison | No general infeasibility claim about all advisory policies. Both operating conditions show no benefit from this response rule. |
| Derived conservation identity is partly redundant | Added cumulative SUMO departure/arrival counters and min-expected count with all routes loaded | Scheduled waiting includes 0.5-second insertion quantization. The horizon and unfinished traffic stay explicit. |
| Seeds do not randomize demand | Documented: seeds vary equipment, compliance and loss; geometry and scheduled demand stay fixed | Results are descriptive for these conditions, not confidence intervals over real traffic demand. |
| Flooding generates many duplicates | Duplicate reception, dropped-record and peak pending counters retained; docs label a logical flooding baseline | No byte, airtime, battery, contention or background-OS model. Bounded memory is not sustainable BLE load. |
| Local samples use fresh IDs | Documented; recipient cooldown limits local repeats | Hazard association across time is future estimator work. Mesh duplicates do reach the Kotlin duplicate check before cooldown. |
| Knowledge display could show the last candidate instead of selected context | Select nearest eligible ahead observation once per recipient, retain until expiry | One selected record, not full cache or estimated global queue. Retention does not imply continuing eligibility. |
| Warnings between replay frames could disappear | Frames now include all recipients cautioned since the preceding sampled frame | Timeline is a recorded sample replay; no invented interpolation. Eventual response is labeled separately. |
| Viewer trusted partial schema checks | Coordinator validates every frame and vehicle, finite values, observation lifetime, modes, unique run keys and ordered times; recomputes summaries from runs | File input is a local research format, not a production telemetry protocol. |

## Acceptance evidence

- Real Kotlin jar executed in each simulation. Android-free policy independently tests freshness, expiry, scope, movement, deduplication, cooldown, bounded state, reset and clock reversal; CLI rejects malformed commands and remains usable.
- Zero compliance preserves the traffic fingerprint across all modes while cautions and logical deliveries still occur. Zero penetration preserves traffic and produces no cautions. Complete packet loss makes mesh traffic equal local-only traffic.
- Identical repeated experiments reproduce traffic fingerprints and metrics. Equipment, compliance and loss draws use independent keyed streams. Full traffic-step fingerprints use coordinates/speeds rounded to four decimals, a disclosed numerical tolerance rather than bit-level equality.
- Congestion retains scheduled-but-uninserted traffic; independent SUMO counters agree with recorded actors. Teleports are disabled and checked. Corridor diagnostics expose the upstream queue instead of truncating it to connector storage.
- Final Python suite: 12 passed with integration enabled. Gradle core/simulator-core/app unit checks passed. See the main results document for counts and artifact hashes.
- Actual-browser QA verified frame counts, seed selection, playback, reduced motion, malformed nested files, previous-replay preservation, inert HTML-like titles and relay-disable behavior. Final report works without a web server.

## Corrections to the initial critique

The initial draft overreached in several places; these claims are not accepted requirements:

1. An unchanged local-only trajectory does not invalidate the comparison. A null effect is an outcome, not a demand to manufacture a benefit. Mesh is distinguishable and measurably worse in these runs.
2. Aggregate inflow and a long red interval do not prove overflow unavoidable under every admissible holding/rerouting policy. The current response and route set are much narrower.
3. An omniscient warning baseline is not an optimal upper bound. It would need an explicit optimization objective and admissible control set to support such a claim; it is deferred.
4. Kotlin checks accepted-event duplication before recipient cooldown. Repeated fresh mesh evidence can therefore exercise the duplicate guard. A report older than 10 seconds is rejected as stale even if its explicit expiry is later; a proposed 14-second duplicate test would test staleness instead.
5. Timestamp/cooldown state deliberately uses one serial nondecreasing experiment clock. It is not a distributed clock or cross-phone authority guarantee.
6. Statistical or literal-word tests cannot prove causal validity or safety. A disclaimer can legitimately contain words such as safety or improvement; the relevant issue is whether its claim is supported.

## Expansion gates

Next candidates: a policy that considers finite downstream storage and can abstain, more demand conditions and sensitivity sweeps, an estimator with uncertainty at sparse participation, response calibration, and a declared per-node communication budget. Add candidate-policy/oracle comparisons with explicit assumptions, per-recipient outcomes and matched counterfactuals. Yellow transitions, mixed road users, lane/conflict geometry, stale or false evidence, radio congestion, partition recovery and physical-phone measurements remain separate work.

The larger stochastic optimization, ownership/handover protocols, signatures, learned capacities and lane/route advice remain proposals in the junction-flow specification. This milestone changes neither the original 58-byte BLE envelope nor the existing app output.
