# Three-phone rehearsal — expandable stage base

Use the same RoadMesh APK on every phone: `app/build/outputs/apk/debug/app-debug.apk`. The standalone ResiLane APK is for scene experiments and does not run the hazard chain. If transferring through Office Kit, pass the RoadMesh APK path explicitly to `simulator/tools/send-officekit.ps1`, then install it manually from Download/Office Kit. Do not replace Office Kit's adb server with another adb version.

## Prepare

1. Select **A Detector**, **B Relay**, and **C Observer**, one role per phone. Keep all three in Stage mode with the default profile (`initialTtl: 1`). These are presentation roles in one app, not three different apps.
2. Enable Bluetooth on every phone. If using airplane mode, re-enable Bluetooth afterward. Wi-Fi is not needed for hazard delivery. Align the phones' system time before going offline; large clock skew causes freshness rejection, not a claimed latency measurement.
3. Run Device check on each phone **before** starting the mesh. Confirm extended advertising and enough payload capacity on all three. The prior measured probe covers one loaner only. Advertising-set probing must not run concurrently with a live mesh session.
4. Grant Bluetooth permissions. Road mode additionally requests precise location; the car scene asks for camera only. Allow notifications so the running session and Stop action are visible. Use the existing OriginOS background settings for this app, then verify their effect with the screen-off test.
5. Start C, then B, then A. All must show BLE ready. Open the car scene on A and grant camera access. Hold the phone landscape, point at a textured floor, and wait for tracking/model readiness. `3DOF rotation only` is an explicit fallback: turning changes the view, walking does not.
6. Check C's media/navigation speech volume and an installed offline English voice. The output log names the selected offline voice or reports that none is available. No network voice is selected.

## Present the chain

1. On A tap **+ Car**, adjust its size with the slider if needed, then **Brake → warn**. Brake also creates a rear-facing car if needed, decelerates it to rest, and lights its rear brake material. The virtual brake directly produces the event; no detector model is required.
2. B must show a received event and then a completed local relay burst. Its origin/session/sequence must match A. It changes the immediate forwarder to 2, TTL to 0 and relay count to 1.
3. C must show **CAUTION / Braking ahead**, `2 radio links · 1 relay`, a synthetic-track distance, one spoken caution and one vibration pattern. Repeated advertisements should increase duplicate counters without repeating the event output.
4. Wait at least two seconds between Brake taps. Dismiss only clears C's visual card; it does not clear dedup state. Reset on A clears the car scene, not remote reception history.
5. Export JSON from all three dashboards after the same run. Files are in `Downloads/RoadMesh/`. Transfer them back with Office Kit. Run:

   ```powershell
   python tools/verify_demo.py detector.json relay.json observer.json
   ```

   The verifier matches full event identities and C's acceptance/caution with A/B local completions. It does not prove speech playback, RF range, latency or PDR. Logs retain only the most recent 512 entries; export promptly after the run.

Say explicitly: **“The car and positions are simulated. The messages travel over BLE. We enforce A-B-C for this demonstration even though the phones can hear each other directly.”** A has no delivery receipt from C. Do not label the two radio links as two completed relays.

## Controls and failure checks

| Check | Expected evidence |
|---|---|
| Stop B, brake on A | C receives no new event; direct A-to-C packets are counted as link-filtered |
| Start B again, brake again | A new event reaches C once; a new mesh session nonce is logged on B |
| Stop A, B or C | Foreground notification disappears; scan/advertiser, GNSS, TTS and wake lock are released |
| Camera denied/unavailable | Car scene cannot trigger a tracked brake; dashboard and other phones remain available |
| Missing optional YOLO model | Pitch still works; standalone detector reports model unavailable rather than successful inference |
| B screen off for 15 minutes | C still receives later events; export results, especially around the 8-minute scan restart |
| C screen off | One new relevant brake still produces offline voice/haptics; verify on the physical phone |
| Reopen/rotate/background the scene repeatedly | Camera resumes, correct landscape orientation, car model reuploads if GL context was lost |
| Same brake radio copies | Exactly one `received` and one `caution` for that event on C |
| Invalid settings JSON | Rejected; last saved profile remains active; editing is disabled while running |
| Road mode without GNSS | No demo scene/input; no fabricated fix or synthetic fallback |

Run ten complete chains, then the Stop-B control, a Stop/restart cycle, and the screen-off test. Record the actual device model, build, voice, camera mode, software revision and result per run. Verify lamp appearance and camera alignment on the loaner; a successful build cannot establish either.

## What remains open

This is a forward-only, unsigned caution demonstration. There are no receiver acknowledgements, measured one-way latency/PDR, production trust guarantees or demonstrated 500-radio-cell capacity. The fleet unit simulation validates bounded logic over an area; device/radio measurements decide the next profile and implementation revision.
