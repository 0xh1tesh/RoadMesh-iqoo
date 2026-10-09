# RoadMesh

Zero-hardware V2X for every road user. **Private repository: team JSONs only.** Phones detect hazards (IMU, camera on the Snapdragon NPU), warn each other over a Bluetooth LE broadcast mesh with up to 3 hops and no internet, and speak the warning. An on-device LLM co-pilot (never on the alert path) summarises drives and turns spoken hazard reports into checked alerts.

**Team JSONs** · iQOO Hackathon 2026 Grand Finale, Mobility track · Bengaluru, 9 to 11 Oct 2026

> All application code in this repository is written during the event window. This initial commit contains only structure, documentation and research.

## Layout
| Path | What |
|---|---|
| `app/` | Android app (Kotlin), package `com.jsons.roadmesh`; module layout in `docs/architecture.md` |
| `app/.../core/` | Pure Kotlin protocol, geometry, mesh, arbiter, detectors (JVM-testable, no `android.*`) |
| `app/.../radio/`, `sensing/`, `perception/`, `output/`, `audio/` | BLE, sensors, camera detector ladder, speech and haptics, siren (stretch) |
| `app/.../service/`, `diag/`, `ui/`, `copilot/` | Foreground service, stats and export, screens, on-device LLM |
| `tools/viewer/` | Offline laptop log viewer (stretch) |
| `simulator/` | ResiLane: shared-AR vehicle simulator and YOLO feed (standalone Gradle project, see `simulator/README.md`) |
| `docs/` | PRD, architecture, implementation plan, decisions log, research dossier |
| `test-data/` | Replay datasets (not committed) |

## Read first
1. `docs/research/00-DOSSIER.md`: the answer, evidence, options, first checks
2. `docs/implementation-plan.md`: dependency-ordered steps with acceptance tests
3. `docs/architecture.md`: components, protocol v2, ADRs
4. `docs/research/14-venue-booklet.md`: official rules

## Licence
To be set once the detector licence is decided (see `docs/decisions/README.md`).
