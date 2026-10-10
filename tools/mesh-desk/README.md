# RESILANE Mesh Desk

Run `node tools/mesh-desk/server.mjs` from the repository, then open **http://localhost:8787**. Node 20+ is the only dependency. No npm install, remote assets, CDN or cloud service is used. The perspective 3D renderer is Canvas-based so it also runs on laptops without WebGL.

Windows: double-click [`start.cmd`](start.cmd). Install the packaged [Android debug APK](artifacts/roadmesh-mesh-desk-debug.apk) on the phones. Its SHA-256 is `9329b9b648d0edeb429a365eb427c86bb817489093c64380699dade7d45ee7bf`. The APK was produced by the shared project's build and checked for the telemetry activity, publisher and latest slowing fields. Actual phone/GNSS/BLE behavior has not yet been exercised.

The initial **Demo** explicitly shows synthetic A/B/C cars: braking, passing and relay events. Drag to orbit, Shift-drag to pan, scroll to zoom; arrow keys and +/- also work. Select a car or Fleet row to follow. Fit fleet resets the camera. Reduced-motion disables interpolation. Demo is not connected to real phones.

## Live phone connection

1. Rebuild/install the Android app with this change. Open **More options → Mesh Desk (desktop)**.
2. Connect laptop and phones to the same trusted private Wi-Fi/hotspot. The server prints the laptop IPv4 address and a per-run pairing token. Port defaults to 8787.
3. Enter the token under **Connect your phones** on the desktop and click **Connect viewer**.
4. On each phone, enter laptop IP, port and token, allow precise location, then **Connect desk**. Start the app's mesh. Leave “synthetic stage positions” unchecked for actual GPS movement.
5. Walk outdoors for a usable GPS fix. GNSS moves the car; GNSS course is used when moving, otherwise the phone magnetic heading is shown. Phone rotation and vehicle travel direction are different measurements. **GPS cannot faithfully show centimeter-scale indoor motion or lane-scale overtakes.** Cars show their relative measured trajectories; no overtaking claim is inferred from inaccurate fixes.

Mesh Desk shows `LIVE`, `DEMO` or `REPLAY`, source accuracy, age, Bluetooth mesh state and desk connection separately. Stale positions freeze and fade. Red lamps and the `SLOWING` label use consecutive GNSS speed measurements: both fixes must report speed uncertainty at most 0.7 m/s, a 0.2–3 second interval, previous speed at least 1.2 m/s, and a speed drop exceeding both 0.8 m/s and 1.5 times combined uncertainty with deceleration above 1.2 m/s². This is an experimental slowing indicator, not measured brake-pedal state. Devices without speed accuracy report no slowing indication. Camera/mesh `BRAKING` warnings remain in the event timeline because they describe a hazard ahead, not necessarily the reporting vehicle. To demonstrate staged events, enable the app's stage mode and the explicit synthetic-position switch. Demo packets retain DEMO provenance.

Choose **Save session** to download up to the last 3,000 telemetry packets. **Open replay** accepts these recordings and offers pause/seek. The app's older hazard-only JSON exports have no continuous pose history and cannot replay vehicle trajectories. No real phones were available during development; device/radio validation remains required.

## Transport and controls

The existing operational mesh remains BLE. This optional adapter posts its own pose + bounded event/chain snapshots at up to 4 Hz to the local desktop. It reads app state and never sends control commands into the mesh. The topology overlay is **reported chain ordering, not RF range or confirmed radio links**. The server bounds nodes (200), events (200), body size (64 KiB), recordings (3,000 packets), validates positions, rejects replayed sequences and requires the token for all telemetry reads/writes. Failed uploads back off; token is held in phone memory only; app restart disables sharing.

The server listens on the LAN by default because phones must reach it. Use `MESH_DESK_HOST=127.0.0.1` for demo/replay only. `MESH_DESK_PORT` changes the port; `MESH_DESK_TOKEN` optionally supplies a token (16–100 letters/digits/underscore/hyphen). Do not expose this prototype on the internet. Local HTTP is unencrypted; only use trusted Wi-Fi. Windows firewall may need a user-managed inbound rule for Node; this tool does not alter firewall settings.

Android's `usesCleartextTraffic` is enabled to support varying private LAN IPs. The publisher accepts only private IPv4 targets and disables redirects. No data is sent until the phone user connects. This is an experimental local telemetry facility, not a deployed internet service.

Run the contract tests: `node --test tools/mesh-desk/server.test.mjs`.

## Packet v1

`POST /api/telemetry`, `Authorization: Bearer <token>`, `Content-Type: application/json`.

```json
{"schema":1,"nodeId":"abc","sessionId":"session-uuid","seq":1,"sentAt":1790000000000,"source":"LIVE","role":"OBSERVER","rank":0,"meshRunning":true,"pose":{"lat":12.9716,"lon":77.5946,"headingDeg":90,"headingSource":"GNSS course","speedMps":1.5,"accuracyM":4,"ageMs":200},"chain":[],"events":[]}
```

`pose` may be null. Events: `{id, origin, via, kind, ageMs, provenance}`. Event IDs carry mesh origin/session/sequence; multiple relay observations do not become independent events. Wall clock is display metadata only; the server uses receipt age for stale state. The software does not claim synchronized radio latency.
