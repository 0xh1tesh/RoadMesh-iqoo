# ResiLane: shared-AR vehicle simulator (Phase 1)

Each phone is a virtual car. It tracks its own 6-DoF pose with ARCore, puts that pose into a
**shared world frame** defined by one printed marker, broadcasts it peer-to-peer, and renders every
*other* phone as a 3D car at the right place, heading and size in its camera view. Your own car is
never drawn. Optionally you get a cockpit/windshield overlay instead.

The rendered AR frame is also a **simulator feed** for the learning layer that comes later. YOLO
runs on the composited camera+car frame on the phone. Every car's exact screen box is known from
its 3D pose, so detections are scored against ground truth live, and frames plus auto-labels can be
exported in Ultralytics format.

```
 ARCore session pose ──► SharedFrame (marker) ──► shared-world pose ──► Nearby / UDP ──►  peers
                                                                                            │
 peer pose (shared) ──► interpolate (no extrapolation) ──► place (ground-locked | free 3D)  │
        ──► sessionFromWorld ──► render car (PBR, HDR env light, contact shadow) ◄──────────┘
        ──► GT box from silhouette ──► PixelCopy frame ──► YOLO11n ──► match / dataset
```

## Look

Other vehicles are drawn as a **grid-line hologram** by default (`HologramRenderer`). Each is shown
in its node's colour: the body is sliced by a model-space lattice, a Fresnel rim outlines the
silhouette, a scan band sweeps up the body, and a glowing projector pad sits underneath. Detected
floor planes get a **holographic ground grid**: 25 cm minor and 1 m major lines, fading at the
plane's edge, with a pulse ring around you. Once calibrated, the grid is laid out in the shared
frame, so every phone draws the same lines on the same floor. *Settings → Look* switches to the
**realistic** PBR car, which YOLO detects far better (see below), and turns the grid off.

## Heading: the joystick

Where a car **is** comes only from the phone's own tracking: walk and it moves. Which way it
**faces** is set with the thumb stick at the bottom left, because the camera direction is a poor
proxy (people look around, point the phone at the floor, hold it sideways).

* Push the stick toward a direction **on screen** and your car turns to face it. Up = straight
  ahead along the ground as you see it, right = to your right. With the phone pointed at the floor,
  "up" is the top edge of the screen.
* Let go and the heading is **latched**: turning the phone no longer turns the car. The chevron on
  the ring shows where your car points relative to your view, and the radar triangle shows the same.
* The heading is relayed in `PoseMsg.yaw` (wire format unchanged), so every peer draws your car
  facing that way, and labels/radar show it. Before the stick is first touched the car faces where
  the camera looks, as before.
* It is latched in the tracking frame, not the shared frame, so **Recalibrate** doesn't swing it.
* *Settings → Driving* turns the joystick off (car faces the camera direction, the old behaviour).

The logic is `core/.../control/Heading.kt`, unit-tested in `HeadingControlTest` (view-relative
stick, latching, floor-pointing, and two phones with different session frames agreeing on the
relayed heading).

## Phones without ARCore (3-DoF mode)

ARCore runs only on phones on [Google's supported list](https://developers.google.com/ar/devices).
The **iQOO 15 (PD2505) is not on it**, although the 15R, 15T and 15 Ultra are. On such phones
the app no longer stops at a black screen. It switches to a **3-DoF camera mode**:

* Camera2 preview.
* Orientation from the rotation sensor.
* Phone assumed at *Settings → Phone height* above an estimated floor grid.
* An orange `3-DoF · NO ARCORE` pill plus a banner that names the reason.

You can turn around and test cars stay put, but walking does not move the view. There is no
shared frame, so real peers can't be placed. Use ARCore-supported phones for experiments A–F.

## Repository

| Path | What |
|---|---|
| `core/` | Pure Kotlin (JVM), unit-tested on the laptop: math, **SharedFrame** calibration, wire protocol, dedupe, clock sync, interpolation, node states, vehicle placement, YOLO decode/NMS, GT projection, matching |
| `app/` | Android app: ARCore session, GLES 3 renderer + GLB loader, calibrator, Nearby + UDP transports, ONNX Runtime YOLO, overlay/HUD |
| `marker/resilane_origin_A4.pdf` | Print this. Calibration marker, 180 mm, scored 100/100 by ARCore's `arcoreimg` |
| `tools/deploy.sh` | Send/install, launch, logs, screenshots and pulls over the **Office Kit** connection |
| `tools/send-officekit.ps1` | Pushes an APK to every phone linked in Office Kit (no adb needed) |
| `tools/monitor.py` | Laptop dashboard of all nodes from UDP traffic (same Wi-Fi) |
| `tools/yolo_check.py` | YOLO recall over renders or phone screenshots |
| `tools/blender_views.py` | Headless Blender renders of a vehicle model at phone-like views (for vetting models for YOLO) |
| `tools/make_marker.py` | Regenerates the marker PNG/PDF |

## Why it agrees across phones (the shared frame)

ARCore sessions share only gravity: every phone has its own origin and yaw. Raw session coordinates
are therefore never sent. On each phone, `SharedFrame` pools ~1 s of marker observations and passes
them through a stability gate (≥20 samples, <2 cm and <2° spread). It then defines:

* origin = marker centre (minus the configured marker height, so world y = 0 is the ground)
* +Y = gravity up (the marker's tilt is ignored, so a warped print can't tip the world)
* +X = the marker's right edge projected horizontally; heading 0° = marker **TOP** edge, clockwise

The frame is committed as an ARCore **Anchor**. ARCore keeps refining anchors, which absorbs much
of the drift. Whenever the marker is back in view, the HUD shows the remaining drift (cm / °).
**Recalibrate** re-scans while the old frame stays in use. `SharedFrameTest` checks the core
property: two phones with different random session frames agree on a third phone's position
within 1 cm.

## Build

Requirements, all already present on this machine: JDK 21, Android SDK 35, Gradle wrapper (8.8).

```bash
./gradlew :core:test            # 26 tests incl. two-phone agreement + shipped YOLO model vs Ultralytics
./gradlew :app:assembleDebug    # app/build/outputs/apk/debug/app-debug.apk (~45 MB, arm64)
```

## Deploy to the iQOO phones (Office Kit)

**Quickest path (no adb, no USB debugging):** link each phone in Office Kit (Wi-Fi is fine), then

```bash
tools/deploy.sh send      # copies the APK to dist/ and pushes it to every phone linked in Office Kit
```

On each phone: *Files → Download → Office Kit → ResiLane-0.3.0.apk → Install* (allow "install
unknown apps" for Files once). This drives Office Kit's own "send to vivo device" action
(`tools/send-officekit.ps1`). The transfer is logged in
`%APPDATA%\pcsuite\log\file-manager\main\` as `uploadDirectory end code:ok`.

**Full control (adb):** Office Kit (`C:\Program Files (x86)\pcsuite`) runs its own adb server
and keeps USB-attached phones connected. `tools/deploy.sh` talks to it with **Office Kit's own
adb.exe**. A newer platform-tools `adb` would kill Office Kit's server and drop the phones.

1. On each phone: Developer options → USB debugging ON. Connect it in Office Kit and accept the
   "Allow USB debugging" prompt.
2. Run these from Git Bash in the repo:
   ```bash
   tools/deploy.sh devices   # model, Android version, ARCore version, Wi-Fi IP per phone
   tools/deploy.sh install   # builds if needed, installs with permissions granted, launches
   tools/deploy.sh wifi      # optional: switch to wireless adb so people can walk freely
   tools/deploy.sh logs      # live RL/* logs from all phones, prefixed per phone
   tools/deploy.sh shot      # screenshot all phones + run the YOLO check on them
   tools/deploy.sh pull      # session CSVs + datasets -> tools/out/sessions/<phone>/
   ```
3. If a phone says "Google Play Services for AR" is missing, install it from Play Store
   (the app prompts). `tools/deploy.sh devices` shows `ARCore MISSING` for such phones.

## Field test (brief §8)

**Setup.** Print `marker/resilane_origin_A4.pdf` at 100 % / "Actual size". Measure the square
(should be 180 mm) and, if it differs, set *Settings → Printed width* on every phone. Tape it flat
on the floor in an open area with some texture (not a blank floor). All phones: same Wi-Fi or
hotspot (optional; Nearby works without), Bluetooth and Location on.

**Calibrate.** Each person points their phone at the marker from about 1 m until the status pill
reads `SHARED e1`. This takes about 1–2 s; hold still. Then walk away. To fix drift later, look
at the marker again and tap **Calibrate**.

| Exp | Do | Expect / where to read it |
|---|---|---|
| **0 (solo)** | One phone, tap **+ Test car**, then **Motion** | A simulated car 4 m ahead; motion cycles parked → circle → shuttle. YOLO boxes (green = matched GT) and recall in the debug panel. Works before calibration. |
| **A** | A looks at B | B's car is placed around B (offset sliders in Settings align it), with label `name · distance · alt · heading` |
| **B** | B walks forward/back/left/right | Car follows smoothly, about 100–150 ms behind (interpolation delay is shown as packet age) |
| **C** | B turns on the spot | Car yaws with B's camera heading; label heading changes, and the radar arrow turns |
| **D** | B walks away, then raises the phone | Distance grows and the car shrinks by perspective. `alt` rises, but the car stays on the road (ground-locked). Settings → *Free 3D* to see the raw 6-DoF pose instead. |
| **E** | A, B and C together | Each phone shows the other two, never itself; `3 peers`, per-peer Hz / packet age / seq gaps in the debug panel |
| **F** | Drag the bottom **Fleet** scale slider | Every phone's cars resize at once (sync), while distances, altitude and the poses in `tools/monitor.py` stay unchanged. Turn sync off for a local-only *View* scale. |

Tracking loss: the phone keeps sending its last confirmed pose flagged PAUSED. Peers show that car
grey and translucent with "⚠ tracking degraded", held still, not extrapolated. After 5 s without
packets the car disappears. A car only appears when its 3D position is inside the camera
frustum; otherwise an edge pointer shows where it is.

**Logs** (`tools/deploy.sh pull`): `events.csv` (calibration, tracking, peer state changes, YOLO)
and `poses.csv` (every pose sent/received with sender time and packet age). `adb logcat -s RL/Calib
RL/Track RL/Peer RL/Net RL/Yolo` streams the same events live.

## YOLO-friendliness and the simulator feed

* **Use the realistic style for YOLO work** (*Settings → Look*). The hologram is see-through
  light, which a COCO-trained detector does not reliably call a car. Ground-truth boxes, matching
  and dataset export work the same in both styles.
* The car is a realistic PBR model (Khronos *CarConcept*). It is lit by ARCore's Environmental HDR
  estimate of the real room (main light + SH ambient + reflection cubemap), with clearcoat paint,
  tinted glass and a contact shadow. That is what makes it read as a car, to people and to YOLO.
* YOLO sees only the GL surface (camera + cars). Labels, HUD and the windshield overlay are separate
  views and never contaminate frames or datasets.
* Offline check (`tools/blender_views.py` + `tools/yolo_check.py`, YOLO11n, 108 phone-like views):
  **87 % overall; 83–100 % at 8 m and 15 m; 67–83 % at 4 m**. The misses are straight front/rear
  views at 4 m, where a full-size car overflows a portrait frame and the concept styling reads as
  "airplane". Practical tips: scale ≥ 0.5×, a few metres away, saturated paint (default palette).
  The on-device panel reports live recall/precision/IoU vs ground truth.
* **Record dataset** (Settings → YOLO) writes `images/`, `labels/` (simulator GT, COCO class 2),
  `preds/` (YOLO output), `meta.jsonl` (poses, node ids, distances, match) and `data.yaml`, so
  `yolo val data=<dir>/data.yaml` works directly. `FeedSink` in `VisionPipeline.kt` is where an
  on-device or federated learner plugs in.
* Another vehicle GLB can be dropped in as `app/src/main/assets/models/vehicle_<id>.glb`
  (`modelId`). Vet it first with the Blender + YOLO scripts.

## MotorMesh integration point

Messages are versioned binary envelopes `{origin, msgSeq, ttl}` (`core/proto/Protocol.kt`).
`MeshRouter` de-duplicates per origin with a replay window and re-broadcasts `ttl > 1` messages.
That is the contract an event relay needs. A MotorMesh adapter implements `PeerTransport`
(`app/.../net/Transport.kt`) and carries the bytes as an opaque event payload. Nothing else changes.

## Known limits (Phase 1)

* No real-world occlusion (people or walls do not hide cars). The ARCore Depth API is the next step.
* Calibration accuracy depends on printing the marker at its configured width and on ARCore's
  image tracking (expect a few cm at 1 m). Drift grows with distance walked; recalibrate at the marker.
* The pose rate is a target (default 12 Hz, 2–30 configurable), not a guarantee. Nearby starts on
  Bluetooth and upgrades to Wi-Fi; UDP on a shared hotspot gives the lowest latency.
* Unsupported phones (e.g. iQOO 15) get rotation only (3-DoF mode above). Real 6-DoF and the
  shared frame need an ARCore-supported phone.
* Shaders are compile-checked offline (`glslc`) and the hologram was previewed offline with
  the same GLSL on the real model. On-device rendering, calibration and networking still need the
  field test above.

## Third-party assets

* Vehicle: *Car Concept*, Khronos glTF Sample Assets, **CC-BY 4.0** (derived from a CC0 model by
  Unity Fan). The license plate's Khronos logo is excluded from that license and is replaced with
  a blank plate at load time.
* Detector: Ultralytics **YOLO11n** weights, **AGPL-3.0**. Fine for research/internal use; any
  distribution of the APK carries AGPL obligations (or needs an Ultralytics enterprise licence).
  Swap in another ONNX detector with the same output layout if that matters.
* ARCore SDK, Nearby Connections (Google Play services), ONNX Runtime (MIT).
