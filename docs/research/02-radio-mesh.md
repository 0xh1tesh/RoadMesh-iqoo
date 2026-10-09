# RoadMesh radio and multi-hop mesh: Android BLE facts, limits and parameters

*Researched 2026-10-08/09 · Sources: 27 · Confidence: High for Android API/AOSP behaviour (read from source), Medium for design parameters (inference), Low for phone-to-phone latency and Wi-Fi Aware (thin or unverified evidence)*

## Bottom line
- **Android cannot advertise faster than 100 ms.** `INTERVAL_MIN = INTERVAL_LOW = 160` (×0.625 ms = 100 ms); `setInterval` throws below 160 [1]. "Legacy 20 ms adverts" are not reachable through the public API. Budget ~3 advertising events per 300 ms.
- **Default TX power is −7 dBm, not max.** Builder default is `TX_POWER_MEDIUM`; set `TX_POWER_HIGH` (= 1 dBm, the API maximum) explicitly [1].
- **New finding: on Android 14+ a continuous scan is silently downgraded after 10 minutes.** Filtered scans drop from LOW_LATENCY (100/100 ms, 100% duty) to LOW_POWER (140/1400 ms, 10% duty); unfiltered scans become opportunistic [6][7][14]. Android 13 used 30 min and exempted filtered scans [13]. **Restart the scan every ~8 min** (stop + start resets the timer [8]); stay under 5 starts per 30 s, which fails silently [7][9]. Always use a ScanFilter (manufacturer ID): unfiltered scans are suspended with the screen off or location off [6][9].
- **Do not use legacy `startAdvertising()`**: it always makes the advert scannable [2]. Use `startAdvertisingSet()` (legacy or extended, non-connectable, non-scannable).
- **One-hop < 300 ms is plausible but unmeasured on phones.** The often-quoted "BT5 700–950 ms in a vehicle" (arXiv 2410.23366) is Raspberry Pi + nRF52840 running Bluetooth Mesh through an application stack, not phones [17]. Measure on day 1 (protocol below).
- **Extended advertising is more congestion-tolerant than legacy** (short primary PDUs, payload spread over 37 secondary channels): my ALOHA estimate gives ~0.99 vs ~0.88 delivery within 300 ms at 100 neighbours (calculation, not measured).
- Relay: keep TTL ≤ 3 and 500 m; use slotted 1-persistence (farthest relays first, "up to 90%" lower packet loss than flooding in VANET simulations [27]) with BLE-sized slots (3 × 100 ms = the current 200 ms Tmax), a Trickle-style counter cancel [26], and a fixed 3-event relay burst on a separate advertising set (`maxExtendedAdvertisingEvents`) [2][3].
- Wi-Fi Aware is a weak fallback: messages are unicast to discovered peers and instant mode lasts only 30 s [24][25]; no phone latency measurements found.

## Findings

### 1. Android BLE advertising API (verified in AOSP source, `packages/modules/Bluetooth`, main branch)

| Item | Exact value / behaviour | Src |
|---|---|---|
| `INTERVAL_HIGH` / `MEDIUM` / `LOW` / `MIN` / `MAX` | 1600 (~1000 ms) / 400 (~250 ms) / 160 (~100 ms) / 160 / 16777215, units 0.625 ms | [1] |
| `setInterval` valid range | 160 (100 ms) to 16777215; throws `IllegalArgumentException` otherwise | [1] |
| `TX_POWER_ULTRA_LOW/LOW/MEDIUM/HIGH` | −21 / −15 / −7 / 1 dBm; valid range [−127, 1] | [1] |
| Builder defaults | non-connectable, non-scannable, **extended (legacy=false)**, primary 1M, secondary 1M, interval 160, **TX −7 dBm** | [1] |
| PHY | primary: 1M or Coded only; secondary: 1M, 2M or Coded; unsupported PHY throws at `startAdvertisingSet` (checks `isLeCodedPhySupported`, `isLe2MPhySupported`) | [1][2] |
| Mode rules | extended: cannot be both connectable and scannable; legacy non-directed: cannot be connectable and non-scannable; legacy cannot include TX power | [1] |
| Max data | legacy 31 B; extended ≤ `getLeMaximumAdvertisingDataLength()` (controller value); flags (+3 B) added only if connectable and discoverable | [2][3] |
| Live update | `AdvertisingSet.setAdvertisingData()` is async (result in `onAdvertisingDataSet`); while enabled, data max **251 B**; data must be empty for extended scannable adverts | [3] |
| Parameter change | `setAdvertisingParameters()` "must be called when advertising is not active" (disable, update, enable) | [3] |
| Bursts | `duration` in 10 ms units (1–65535) and `maxExtendedAdvertisingEvents` (1–255; needs extended-adv controller) | [2][3] |
| Concurrent sets | = controller's "number of supported advertising sets" (or vendor `max_advt_instances`+1); beyond it `TOO_MANY_ADVERTISERS`. No public getter: probe on device | [12] |
| Fragmenting | stack fragments extended data at 251 B per HCI command; GAP AD element ≤ 255 B | [12] |
| Legacy `startAdvertising(AdvertiseSettings…)` | LOW_POWER→1600 (1 s), BALANCED→400 (250 ms), LOW_LATENCY→160 (100 ms); TX levels −21/−15/−7/1 dBm; **always scannable**; timeout ≤ 180 000 ms | [2][4] |

### 1b. Scanning (verified in AOSP)

| Item | Value / behaviour | Src |
|---|---|---|
| Duty cycles (window/interval) | LOW_POWER 140/1400 ms (10%); BALANCED 183/730 ms (~25%); **LOW_LATENCY 100/100 ms (100%)**; screen-off low power 512/10240 ms (5%); screen-off balanced 183/730 ms. Overridable via `Settings.Global` | [6] |
| Builder defaults | LOW_POWER, `CALLBACK_TYPE_ALL_MATCHES`, report delay 0, `MATCH_MODE_AGGRESSIVE`, `MATCH_NUM_MAX` (FEW for targetSdk 36), **`legacy = true`**, PHY 1M | [5] |
| `setLegacy(false)` | required to receive extended adverts; `setPhy` only applies when legacy is false (`PHY_LE_ALL_SUPPORTED = 255` → 1M + Coded if supported) | [5] |
| `setReportDelay` | 0 = immediate; >0 batches for max(delay, 5000 ms) | [5] |
| `ALL_MATCHES` | "callback for every advertisement found that matches the filter" | [5] |
| Duplicate filtering | Android (non-Floss) enables scanning with controller `FilterDuplicates::DISABLED`, so repeated adverts reach the stack | [11] |
| Background | LOW_POWER "is enforced if the scanning application is not in foreground"; foreground = importance ≤ FOREGROUND_SERVICE; with screen off a foreground app's LOW_LATENCY stays LOW_LATENCY | [5][6] |
| Screen off / location off | unfiltered scans suspended ("Cannot start unfiltered scan in screen-off"); unfiltered scans also need location on unless `neverForLocation` | [6][9] |
| Throttle | `DEFAULT_SCAN_QUOTA_COUNT = 5` per `30 s`; excess → registration fails and "don't report anything to the app" | [7][9][10] |
| **Scan timeout** | `DEFAULT_SCAN_TIMEOUT_MILLIS` = 30 min (android13-release) → **10 min (android14/15/16-release, main)**; at expiry unfiltered → opportunistic, filtered → `SCAN_MODE_LOW_POWER`. Only opportunistic and FIRST_MATCH scans are exempt | [6][7][13][14] |
| Timer reset | `mScanStartTime` is set only when the app is not already scanning, so stop then start resets it | [8] |
| Connecting downgrade | while a BT connection is being set up, max-duty scans are downgraded for 6 s (default) | [6][7] |
| Android 16 | targetSdk 36: default trackable adverts for onFound/onLost = 2 (`CHANGE_DEFAULT_TRACKABLE_ADV_NUMBER`) | [5] |

**Permissions / FGS.** Runtime `BLUETOOTH_SCAN` (with `neverForLocation` if no location is derived from scans; then "some BLE beacons are filtered", in AOSP the denylist covers Eddystone and iBeacon patterns, not custom manufacturer data), `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` [7][15]. FGS `connectedDevice` needs `FOREGROUND_SERVICE_CONNECTED_DEVICE` plus one granted of `BLUETOOTH_CONNECT/ADVERTISE/SCAN`; `location` needs `FOREGROUND_SERVICE_LOCATION`, a granted location permission and location enabled, and cannot be started from the background without background location [16].

### 2. Measured latency and range
- **arXiv 2410.23366 (verified):** roadside unit to a node in a car at 30/50/70 km/h; hardware = Raspberry Pi 3B/3B+ with Nordic nRF52840 DK running Bluetooth Mesh (nRF Mesh) plus a libp2p edge layer. Mean BT5 latency 716–955 ms; loss 11% / 27% / 7% at 30 / 50 / 70 km/h [17]. It is **not** phone-to-phone raw advertising; treat it as a heavy-stack upper bound, not a BLE limit.
- **arXiv 2305.17575 (OSU/Ford):** Android app broadcasting SAE J2735 PSM over extended advertising (Coded PHY mentioned) worked pedestrian-to-vehicle in a non-line-of-sight test behind a building; the passages read give no latency or range numbers [18]. Proves feasibility, not performance.
- **RSSI in vehicles/bodies:** on a commuter bus, attenuation did not increase monotonically with distance and changing who held the phones shifted it by ±10 dB [21]; orientation and body absorption change BLE RSSI substantially [22]. Do not rely on RSSI for distance; use GNSS in the payload.
- **Car glass/body loss, LE Coded range on phones, discovery latency on phones:** insufficient evidence found in this session. **Measure on day 1.**
- **Theory (inference):** with 100 ms adverts and a 100% duty scan, each event offers one reception chance on the channel the scanner is tuned to; expected one-hop latency is roughly one advertising interval plus the `setAdvertisingData` command time and callback dispatch (not yet measured). Kindt et al. give exact discovery-latency theory [19] (not re-read).

### 3. Wi-Fi Aware (NAN)
Verified in AOSP (`packages/modules/Wifi`, main):
- Message size is device-specific: `Characteristics.getMaxServiceSpecificInfoLength()` = "maximum length of byte array for Aware messaging" [23]. The "≤ 255 B" figure from earlier research is **not yet verified**; read the value on the loaner.
- `DiscoverySession.sendMessage()` needs a `PeerHandle` from `onServiceDiscovered` or `onMessageReceived` [25]: messages are **unicast to already-discovered peers**, so a broadcast must ride on publish service-specific info (inference) or fan out per peer.
- Instant communication mode: `Characteristics.isInstantCommunicationModeSupported()` (API 31), `PublishConfig.Builder.setInstantCommunicationModeEnabled(enabled, band)` (API 33, 2.4 or 5 GHz); "it will only remain enabled for 30 seconds from the time the discovery session is started" [23][24].
- Measured discovery/message latency on phones: **insufficient evidence found** (a paper-index search on 2026-10-09 returned no Wi-Fi Aware smartphone latency measurements). Range, NAN ranging accuracy and iQOO/vivo support: **not yet verified**. Check `PackageManager.FEATURE_WIFI_AWARE` on the loaner.
- Implication (inference): Aware suits a 30 s "incident burst" or bulk data between nearby phones, not an always-on broadcast mesh; keep it as Fallback 2.

### 4. Multi-hop broadcast
- Bluetooth Mesh (managed flooding) simulation: ~250 ms average delay over 10+ hops in the worst simulated case, but "prone to broadcast storm" in dense deployments [20] (abstract).
- Trickle (RFC 6206, verified): three parameters, `Imin` (time, e.g. 100 ms), `Imax` (number of doublings) and redundancy constant `k`; a node suppresses its transmission when it has heard ≥ k consistent transmissions in the interval, so "communication rate scales logarithmically with density" [26]. RoadMesh borrows only the `k`-style suppression (counter C); interval doubling suits background state (presence), not urgent alerts (inference).
- Wisitpongphan, Tonguz et al. 2007 (verified from the paper text) [27]:
  - Weighted p-persistence: node j rebroadcasts with `p_ij = D_ij / R` (distance from sender over average range); if it decides not to, it waits `WAIT_TIME + d` (example WAIT_TIME 2 ms) and rebroadcasts with probability 1 if no neighbour retransmission is heard, to avoid "die out".
  - Slotted 1-persistence: rebroadcast with probability 1 in slot `T_s,ij = S_ij × τ`, where `τ` is the estimated one-hop delay (medium access + propagation) and the slot number `S_ij` (out of `N_s`) shrinks as distance grows, so the farthest node goes first; discard if a duplicate is heard before the slot.
  - Slotted p-persistence: same slots, rebroadcast with probability p.
  - Results: "up to 90% reduction in packet loss rate"; without suppression, packet loss ratio was 60% in the dense network; simulations used a 500 m broadcast range and 5 ms slots (DSRC timescale).
- Bluetooth Mesh cache/TTL defaults and bitchat TTL: **not yet verified** this session.
- **Mapping to BLE (inference):** on BLE the one-hop delay `τ` is about one advertising interval (≥ 100 ms) rather than 5 ms, so slotted 1-persistence with `N_s = 3` and `τ = 100 ms` gives relay slots at 0 / 100 / 200 ms, the same span as the current `relayDelay(tMax = 0.2 s)` in `mesh.js`.

### 5. Scale and congestion (my calculation, unverified assumptions)
Assumptions: 100 ms interval + 0–10 ms advDelay (Core Spec, not re-verified); LE 1M at 8 µs/byte; 24 B payload; pure-ALOHA collisions (vulnerable window 2T); no capture effect, no Wi-Fi interference, scanner always on a primary channel. Legacy ADV_NONCONN_IND ≈ 352 µs; extended: ADV_EXT_IND ≈ 136 µs on each primary channel + AUX_ADV_IND ≈ 384 µs on one of 37 secondary channels.

| Neighbours N | Legacy P(event) | Legacy P(≤3 events, ~300 ms) | Extended P(event) | Extended P(≤3 events) |
|---|---|---|---|---|
| 50 | 0.72 | 0.98 | 0.87 | 0.998 |
| 100 | 0.51 | 0.88 | 0.76 | 0.99 |
| 200 | 0.26 | 0.59 | 0.57 | 0.92 |
| 400 | 0.07 | 0.19 | 0.33 | 0.70 |

How many RoadMesh phones in range in a Bengaluru jam: insufficient evidence; it depends on adoption. Design for >100 with adaptive rate control (section "What this means").

### 6. Precedents
Exposure Notifications, Apple Find My, bitchat, Bridgefy: **not yet verified** this session (prior research cites bitchat-android as a BLE mesh reference).

### 7. Crowded 2.4 GHz demo hall (inference)
Risks: Wi-Fi on channels 1/6/11 overlapping BLE data channels, many phones and wearables advertising, scan downgrade during BT connections [6]. Mitigations: manufacturer-ID + magic-byte ScanFilter; TX HIGH; extended adverts (secondary payload hops over 37 channels); 3–5 m phone spacing on stage; turn off Bluetooth audio connections on demo phones; pre-recorded outdoor video as backup.

## Options matrix

| Option | Evidence | Feasibility on iQOO (48 h) | Risk | Verdict |
|---|---|---|---|---|
| Extended adv, non-conn, 100 ms, TX HIGH, 1M/1M + LOW_LATENCY filtered scan, `setLegacy(false)` | API verified [1][2][5][6] | High, if `isLeExtendedAdvertisingSupported()` | Untested on OriginOS; 10-min downgrade | **Recommended** |
| Legacy ADV_NONCONN_IND via `startAdvertisingSet(legacy)`, 28 B AD | API verified [1][2] | High, works on any BLE phone | Worse under congestion (table) | **Fallback 1** |
| LE Coded PHY primary | API exists [1][5] | Medium: both phones must support | Range gain on phones unmeasured; longer airtime | Optional test |
| Legacy `startAdvertising(AdvertiseSettings)` | Always scannable [2] | High | Extra scan-request traffic | Avoid |
| Wi-Fi Aware publish/subscribe | Unverified | Unknown on iQOO | Discovery latency unknown | **Fallback 2** |
| Cloud relay (MQTT/WebSocket) | n/a | High | Needs internet; breaks "offline" claim | **Fallback 3 (demo continuity)** |
| Nearby Connections | Prior research: handshake takes seconds | High | Too slow for alerts | Avoid for alerts |

## What this means for RoadMesh

### Recommended radio parameters
**Advertiser (one set per role):**
- Set A "alerts": `startAdvertisingSet` with `setLegacyMode(false)`, `setConnectable(false)`, `setScannable(false)`, `setInterval(160)`, `setTxPowerLevel(TX_POWER_HIGH)`, primary `PHY_LE_1M`, secondary `PHY_LE_1M` (try `PHY_LE_2M` on day 1 if both phones support it).
- Originator: on a new event, `setAdvertisingData()` then keep it for ~10 events (~1 s), inference.
- Set B "relay": same parameters with `maxExtendedAdvertisingEvents = 3` per relayed message (~300 ms airtime), inference.
- Set C "presence" (pocket/VRU, 2–5 Hz in SOLUTION.md): `INTERVAL_MEDIUM` (250 ms) by default.
- Probe how many sets the controller allows; if < 3, multiplex alerts and relays in one set.

**Payload:** manufacturer data, 24 B core + 2 B company ID; legacy total AD = 28 B ≤ 31 B (no flags because non-connectable) [2]. Add a 1-byte magic/version for the filter. Ed25519 signature (64 B) only in extended mode (fits under 251 B [3]).

**Scanner:** `ScanSettings`: `SCAN_MODE_LOW_LATENCY`, `CALLBACK_TYPE_ALL_MATCHES`, `MATCH_MODE_AGGRESSIVE`, `MATCH_NUM_MAX_ADVERTISEMENT`, `setReportDelay(0)`, `setLegacy(false)`, `setPhy(PHY_LE_1M)` (or `PHY_LE_ALL_SUPPORTED` if Coded is used). One `ScanFilter` with `setManufacturerData(id, data, mask)` on the magic byte. Run inside a `connectedDevice|location` foreground service. **Stop and restart the scan every 8 min**; never more than 1 restart per 30 s window. Never use FIRST_MATCH (exempt from the timeout but reports only the first advert per device).

**Relay rule constants (design inference, tune on day 1):**
- TTL ≤ 3, relay only within 500 m of origin (existing `mesh.js`).
- Dedup: msgId LRU, 256 entries or 30 s.
- Contention delay = slotted 1-persistence [27] on BLE timescale: `slot = min(N_s−1, floor(N_s·(1 − min(d,R)/R)))` (three equal distance bands: outer third → slot 0), delay = `slot × τ + U(0, 20 ms)` jitter, with `N_s = 3`, `τ = 100 ms` (one advertising interval), so Tmax = 200 ms as in the current code; `R` = day-1 measured reliable range (start 100 m). Equivalent continuous form: `T = Tmax·(1 − min(d,R)/R)`.
- Cancel if the same msgId is heard from ≥ C other relayers during the wait: C = 2 when fewer than 20 RoadMesh senders were heard in the last second, C = 1 otherwise.
- Adaptive rate (ETSI DCC-style analogue, inference): presence beacons 250 ms when < 30 senders heard, 500 ms for 30–100, 1000 ms above 100; alerts always 100 ms. Changing interval requires disable → `setAdvertisingParameters` → enable [3].

### Fallback ladder (trigger → next step)
1. **Extended advertising** (default). Move down if `isLeExtendedAdvertisingSupported()` is false on any demo phone, or `getLeMaximumAdvertisingDataLength()` < needed payload, or day-1 PDR at 10 m < 90% while legacy does better.
2. **Legacy advertising set** (ADV_NONCONN_IND, 24 B core, no signature). Move down if one-hop p95 latency > 1 s or PDR < 80% at stage distances after TX HIGH and filter fixes.
3. **Wi-Fi Aware** publish/subscribe (only if `FEATURE_WIFI_AWARE` on all phones; parameters not yet verified). Move down if attach/discovery fails or message latency > 1 s.
4. **Cloud relay** over mobile data/venue Wi-Fi, flagged in the UI as "degraded mode"; demo continuity only, not the product claim.

## Test on the loaner phone on day 1
1. **Capability readout** (log to screen): `isLeExtendedAdvertisingSupported`, `isLe2MPhySupported`, `isLeCodedPhySupported`, `isLePeriodicAdvertisingSupported`, `getLeMaximumAdvertisingDataLength`, `isMultipleAdvertisementSupported`, `FEATURE_WIFI_AWARE`, Android/OriginOS version. Create advertising sets until `TOO_MANY_ADVERTISERS`.
2. **One-hop latency without clock sync (ping-pong):** A sends seq n; B re-advertises n as soon as heard; A logs `elapsedRealtimeNanos` at send and at hearing B's echo. One-way ≈ RTT/2 (includes B's `setAdvertisingData` time). 200 pings per config: extended vs legacy, 1M vs 2M, TX HIGH vs MEDIUM. Report median and p95.
3. **PDR vs distance:** A advertises a counter at 100 ms for 60 s; B counts received/expected at 1, 5, 10, 25, 50, 100 m outdoors, then with B inside a closed car, then A in a pocket. Repeat with Coded PHY if supported.
4. **10-minute downgrade check:** run a filtered LOW_LATENCY scan for 15 min without restart; plot adverts/min. Expect a drop near minute 10 on Android 14+ [7]. Then repeat with the 8-min restart.
5. **Screen-off and background:** repeat test 3 at 10 m with screen off, app in background, FGS running.
6. **Multi-hop proof:** A → B → C with A and C out of range (or A at TX ULTRA_LOW −21 dBm [1] to shrink range indoors); check hop count, duplicates, end-to-end latency.
7. **Tools:** nRF Connect for Mobile as an independent scanner to see the raw adverts (extended-advert support in its advertiser not yet verified).

## Sources
1. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/le/AdvertisingSetParameters.java : interval/TX constants, builder defaults, mode rules
2. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/le/BluetoothLeAdvertiser.java : legacy mapping, size and PHY checks, duration/maxEvents
3. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/le/AdvertisingSet.java : setAdvertisingData 251 B limit, setAdvertisingParameters rule
4. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/le/AdvertiseSettings.java : legacy modes, 180 s timeout
5. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/le/ScanSettings.java : scan constants, builder defaults, Android 16 change
6. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/le_scan/ScanManager.java : duty cycles, screen-off, suspension, timeout downgrade
7. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/btservice/AdapterService.java : quota 5/30 s, timeout 10 min, location denylist
8. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/android/app/src/com/android/bluetooth/le_scan/AppScanStats.java : too-frequent / too-long logic
9. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/le/BluetoothLeScanner.java : silent throttle, screen-off note
10. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/framework/java/android/bluetooth/le/ScanCallback.java : SCAN_FAILED codes
11. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/gd/hci/le_scanning_manager.cc : duplicate filtering disabled
12. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/main/system/gd/hci/le_advertising_manager.cc : advertising set count, fragment sizes
13. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android13-release/android/app/src/com/android/bluetooth/gatt/ScanManager.java : Android 13 timeout rules
14. https://android.googlesource.com/platform/packages/modules/Bluetooth/+/refs/heads/android14-release/android/app/src/com/android/bluetooth/gatt/ScanManager.java : Android 14 filtered-scan downgrade
15. https://developer.android.com/develop/connectivity/bluetooth/bt-permissions : BLUETOOTH_* permissions, neverForLocation
16. https://developer.android.com/develop/background-work/services/fgs/service-types : FGS connectedDevice/location prerequisites
17. https://arxiv.org/abs/2410.23366 : RPi + nRF52840 Bluetooth Mesh vehicular test, 716–955 ms
18. https://arxiv.org/abs/2305.17575 : Android extended-advert P2V safety app (NLOS demo)
19. https://arxiv.org/abs/1509.04366 : BLE neighbour-discovery latency theory (not re-read)
20. https://arxiv.org/abs/1910.03345 : Bluetooth Mesh reliability/delay/scalability simulation (abstract)
21. https://arxiv.org/abs/2006.08543 : GAEN measurements on a commuter bus (abstract)
22. https://arxiv.org/abs/2006.06822 : BLE RSSI variability on handsets (abstract)
23. https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/framework/java/android/net/wifi/aware/Characteristics.java : Aware message size getter, instant-mode support flag
24. https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/framework/java/android/net/wifi/aware/PublishConfig.java : instant communication mode, 30 s limit
25. https://android.googlesource.com/platform/packages/modules/Wifi/+/refs/heads/main/framework/java/android/net/wifi/aware/DiscoverySession.java : sendMessage needs a discovered PeerHandle
26. https://www.rfc-editor.org/rfc/rfc6206.txt : Trickle parameters Imin, Imax, k and suppression
27. https://doi.org/10.1109/MWC.2007.4407231 (text read from the course copy https://www.csie.ntpu.edu.tw/~yschen/course/97-1/VANET/papers/3-4.pdf) : Wisitpongphan, Tonguz et al., "Broadcast storm mitigation techniques in vehicular ad hoc networks", IEEE Wireless Communications 2007
