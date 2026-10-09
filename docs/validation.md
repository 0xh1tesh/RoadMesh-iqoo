# Validation snapshot — 10 October 2026

The implementation started from the fetched `origin/main` merge `2a851c4`. Results below cover the current working-tree base, not the earlier research roadmap.

| Check | Result |
|---|---|
| `:core:test` | 25 passed, zero failures/skips; includes original 11 packet tests and logical fleet simulations |
| `:sim-core:test` | 36 passed, zero failures/skips; optional ONNX weight evaluation excluded explicitly |
| `:app:testDebugUnitTest` | 3 profile/schema tests passed, zero failures/skips |
| `:app:lintDebug` | Passed, zero errors; advisory warnings remain |
| `:app:assembleDebug` | Passed; arm64 RoadMesh APK |
| `:simulator-app:assembleDebug` | Passed; standalone harness through the root build |
| Standalone `simulator/` build | `:core:test :app:assembleDebug --offline` passed independently |
| Python export verifier tests | 3 passed |
| Python scene monitor tests | 2 passed |
| `git diff --check` | Passed with the repository's normal Windows line-ending configuration |

Build environment: JDK 17.0.14, Gradle wrapper 8.14.6, AGP 8.13.2, Kotlin 2.2.21, Android SDK 36. RoadMesh APK path: `app/build/outputs/apk/debug/app-debug.apk`. The root build's harness path is `simulator/app/build/outputs/apk/debug/simulator-app-debug.apk`.

The deterministic simulation report at `core/build/reports/mesh-simulation.json` uses 25% seeded loss, repeated advertisements, <=14 immediate neighbors, and a separate disconnected-partition test. The three area-wide runs produced:

| Devices | Accepted event receptions | Cautions | Local logical bursts | Max radio links | Max retained event keys per node |
|---|---|---|---|---|---|
| 100 | 137 | 76 | 43 | 3 | 2 |
| 300 | 465 | 230 | 159 | 3 | 2 |
| 500 | 791 | 388 | 265 | 3 | 2 |

These are logical model results, not hardware delivery counts, airtime estimates, PDR or latency measurements. Separate tests exercise table overflow and rejection without evicting live dedup keys, two-link/one-relay staging, freshness across an hour boundary, replay behavior, relevance, pending expiry and suppression.

No loaner radio, graphics, on-device detector, voice playback or screen-off acceptance is established by these checks. Those remain the explicit [phone rehearsal](demo-runbook.md), including ten repeated chains and the Stop-B control. The architecture, protocol extension and profiles remain revisable starting points.
