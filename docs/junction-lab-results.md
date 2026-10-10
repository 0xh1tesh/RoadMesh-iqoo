# Junction Lab: first measured baseline

Executed 10 October 2026 with SUMO 1.28.0 and the shared Kotlin queue-caution policy. Reproduce with the [Junction Lab instructions](../tools/junction_lab/README.md). This implements the requested three-way offline experiment; the larger [junction-flow specification](junction-flow-spec.md) remains an expandable research design.

## Result

Mesh-delivered cautions did **not** reduce the spillback proxy in this scenario. With the assumed temporary slowdown response, the mean fixed-horizon delay measure increased by **2.97%** relative to no advice, and fewer vehicles completed the corridor. More delivered information alone did not produce better traffic flow.

Three seeds (7, 19, 42), 72 scheduled vehicles, 180 seconds, 60% equipment and 60% assumed compliance. Geometry, demand and fixed signal schedule are identical across modes. Ranges below are descriptive across seeds, not confidence intervals.

| Mode | Spillback proxy, s | Delay proxy, vehicle-s: mean [min, max] | Cautions: mean [min, max] | Arrivals: mean [min, max] |
|---|---:|---:|---:|---:|
| No advice | 50.00 | 4371.35 [4371.35, 4371.35] | 0 [0, 0] | 52.00 [52, 52] |
| Local only | 50.00 | 4372.24 [4371.35, 4372.68] | 8.67 [7, 11] | 52.00 [52, 52] |
| Mesh informed | 50.00 | 4501.09 [4475.84, 4524.46] | 41.67 [38, 49] | 50.33 [50, 51] |

All nine runs had zero conservation-check failures, teleports and detected colliding-vehicle samples. These are simulation diagnostics, not a real-world safety finding. At the horizon, 20 vehicles remained active in each no-advice/local run and 21–22 in mesh runs; all were retained in accounting. Source waiting is included throughout the delay integral even though no vehicles remained pending at the final instant.

The spillback proxy measures whether the rearmost stopped vehicle on the connector reaches a 15 m upstream buffer. It does not prove contiguous queues or actual intersection blockage. Delay integrates active speed deficit and source waiting over the fixed horizon; it is not average completed-trip delay. See the README for all metric definitions.

Do not compare average warning lead as though the warnings concern identical actors/events: the policy modes emit different cohorts. Long crossing lead can include time spent waiting for the fixed red signal. It does not establish hazard persistence, useful human reaction or accidents avoided.

The added corridor diagnostic reaches **310.9 m** and peak source backlog reaches **24 vehicles** in every heavily queued run. This exposes congestion extending beyond the roughly 98 m usable connector. Local caps activate above the cap speed zero times in these runs; mesh caps do so 3, 6 and 8 times for seeds 7, 19 and 42. These activation counts are diagnostics, not causal speed-reduction estimates.

## Lighter-demand sensitivity

With 30 vehicles, 3-second scheduled headways and a shorter red interval (35–55 seconds), all modes have **zero spillback-proxy seconds**. Mean delay is **155.88** vehicle-s with no advice, **155.91** with local-only and **185.39** with mesh. This case also fails to demonstrate a traffic benefit. A lower maximum stopped extent in an individual seed must not be presented as success while delay increases.

Reproduce with the command in the README; open `tools/junction_lab/output/lighter-demand/report.html`. This is a second operating condition, not a proof of feasibility for arbitrary advisory policies.

## Why this is a useful base

The harness separates sparse observations, logical relaying, recipient decisions, assumed human response and traffic outcomes. It can expose a policy that makes the result worse. The next policy should be tested for its effect on finite downstream storage and throughput, with abstention when the available evidence does not justify intervention. This result does not establish whether a calibrated response, other demand pattern, reroute policy or hardware deployment would help.

No-advice, local-only and mesh traffic fingerprints coincide when compliance is zero. Mesh warnings can still be delivered in that case. At zero equipment, all modes coincide and emit no warnings. With complete logical packet loss, mesh reproduces local-only traffic. Those tests rule out several easy ways to fabricate an apparent gain.

## Artifacts and checks

- Generated replay: `tools/junction_lab/output/comparison/report.html` (self-contained, open offline).
- Raw trace: `tools/junction_lab/output/comparison/results.json`; SHA-256 `6b711e280a73cb776cc304a7fe14a51c1662accf9c109d7dab6dd093d2cf4ffc`.
- Lighter-demand trace SHA-256 `67bea4e1947d04a4453661a299c79ec516b77c79f723797a188b438c758df085`.
- Generated per-seed `summary.csv`, SUMO logs and scenario XML live beside it. Generated output is ignored by Git; rerun to recreate it.
- 12 Python tests passed with real SUMO/JVM integration enabled, including 15 simulation runs and protocol, identity, conservation, deterministic replay, relay-expiry, resource-bound and safe export checks.
- Gradle `:core:test :core:junctionPolicyJar :sim-core:test :app:testDebugUnitTest` passed. Test reports contain 43 core tests (18 new), 36 simulator-core tests and 3 Android app unit tests, all without failure or skips.
- Actual-browser QA passed 64 checks: recorded counts across all seeds/modes at five times, playback, reduced motion, rejection of malformed nested data, preservation of the previous replay, inert imported text and disabled relays. Wide and narrow screenshots were inspected; no horizontal page overflow at 1440 or 390 px.

Codex supplied the shared policy; Antigravity supplied the viewer, which the coordinator hardened and checked in the browser. Claude supplied the independent critique but encountered an API/DNS failure before final completion. The coordinator implemented the applicable findings and recorded [review dispositions](../tools/junction_lab/REVIEW.md). The original prior provider session was not resumed.

The existing three-phone scene and BLE envelope were not changed. This milestone provides no phone-to-SUMO connection, measured BLE performance, calibrated driver behavior, mixed-traffic model, probabilistic safety guarantee or deployed optimization controller.
