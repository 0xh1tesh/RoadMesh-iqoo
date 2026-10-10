# Implementation status — expandable stage base

The original larger roadmap is preserved in [archive/implementation-plan-research.md](archive/implementation-plan-research.md). It includes planned features that are not shipped. The current architecture and protocol are revisioned starting points, not final commitments.

## Implemented

- Synced the friend's visual car layer from `origin/main` at `2a851c4` before integration.
- Extracted Android-free `core/`, reusable `simulator/scene/`, and a thin standalone simulator launcher.
- Preserved the original 27-byte packet codec and its tests. Added the versioned 58-byte full-identity hazard envelope.
- Added a pure mesh engine with injected clock/position/link policy, TTL, freshness, relevance, duplicate/suppression behavior, rate limits and bounded state.
- Added real BLE extended scanning and serialized finite advertising bursts, foreground service, clean Stop, role selection and visible failures.
- Wired virtual car braking directly to DEMO events. Nearby/UDP scene synchronization is disabled in the pitch.
- Added Observer visual caution, offline English speech and haptics; relay copies do not create trust or repeated warnings.
- Added validated JSON profiles, retained logs/counters and Downloads export. Road mode requires live GNSS and rejects synthetic/demo/replay inputs.
- Improved scene lifecycle, landscape fallback rotation, brake lamps, optional detector status and exact-frame dataset capture.
- Fixed the scene protocol's old-sequence replay/reset bug; added session nonce compatibility, bounded replay/peer state and tests.
- Experimental junction layer in `core/junction/` (not wired into the app): Reserve-then-Roll slot scheduler with exit tokens, approach speed law, exact lane belief fusion, ego tracker and handover state machine, checked by a single-junction simulation. See [junction-node.md](junction-node.md).

## Automated validation

Run from the repository root with JDK 17 and Android SDK 36:

```powershell
.\gradlew.bat :core:test :sim-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :simulator-app:assembleDebug
```

The logical fleet simulation writes `core/build/reports/mesh-simulation.json`. It tests 100/300/500 nodes across an area with <=14 immediate neighbors, 25% seeded loss, repeats, TTL bounds, once-per-event acceptance, table limits and partitions. It is not measured RF performance or a guarantee for hundreds of radios in one cell.

The optional ONNX model test is excluded from ordinary unit runs. Fetch the model and explicitly request `:sim-core:test -PwithModelTests=true --rerun-tasks` to evaluate it; absent weights then fail clearly. This build does not claim on-device inference validation.

## Pending phone acceptance

Use [demo-runbook.md](demo-runbook.md). In particular: all three extended-advertising probes, landscape camera alignment, 3DOF fallback, actual brake lamp appearance, one audible/haptic caution on C, RF overlap filtering, Stop/restart resource release, repeated runs and the screen-off test. APK assembly cannot verify these.

## Deliberate extension work

Live camera/IMU hazard generation, signatures, independently corroborated urgent tiers, production company identity, presence beacons, congestion adaptation, receiver receipts, clock synchronization, measured latency/PDR, thermal adaptation and LLM debriefs remain future work. No hidden fallback substitutes simulated transport for BLE. The next implementation should revise these interfaces where measurements justify it.
