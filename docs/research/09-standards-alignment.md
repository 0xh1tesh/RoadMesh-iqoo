# Standards alignment: RoadMesh alerts vs AIS-230, ETSI DENM and Car 2 Car triggers
*Researched 2026-10-08 · Sources: 4 · Confidence: High for code values (read from ASN.1), Medium for AIS-230 details (press coverage of a draft)*

## Bottom line
- AIS-230 (draft, Aug 2026) defines four V2V safety applications: **Emergency Brake Alert, Forward Collision Warning, Wrong-Way Driving Alert, Emergency Vehicle Alert**. It mandates **C-V2X** in 5.875 to 5.925 GHz for categories **L (2W/3W), M and N**: vehicles already fitted with V2V must comply from **1 Oct 2027**, and every new vehicle from **1 Oct 2028** [1].
- Each RoadMesh alert type can carry a real **ETSI DENM cause code and subcause code** in its event bytes. A future gateway to AIS-230 OBUs then translates rather than reinterprets. That makes "bridge, not competitor" a byte-level claim, not a slogan [2].
- ETSI already defines `postCrash (92)` / `accidentWithECallTriggeredWithoutAccessToCellularNetwork (4)`. The standards body anticipated a crash with **no cellular coverage**, which is exactly the case RoadMesh's multi-hop SOS covers [2].
- The Car 2 Car Communication Consortium triggers an **Emergency Electronic Brake Light** DENM only when **speed > 20 km/h and acceleration < -7 m/s² for at least 500 ms** (about 0.71 g). It uses `dangerousSituation (99)` / `emergencyElectronicBrakeEngaged (1)`, relevance **< 500 m** and validity **2 s** [3]. RoadMesh's 0.45 g / 300 ms rule is a *hard brake*, not an emergency brake. Use two tiers (below).

## Mapping table (proposed)

| RoadMesh alert | AIS-230 application | ETSI causeCode | ETSI subCauseCode | Notes |
|---|---|---|---|---|
| EBA (emergency brake) | Emergency Brake Alert | dangerousSituation (99) | emergencyElectronicBrakeEngaged (1) | Tier 2: < -7 m/s² for 500 ms at > 20 km/h, as the C2C-CC trigger [3] |
| HARD_BRAKE (caution) | Emergency Brake Alert (advisory) | dangerousEndOfQueue (27) | suddenEndOfQueue (1) | Tier 1: about -4.4 m/s² (0.45 g) for 300 ms; softer wording |
| FCW (camera TTC) | Forward Collision Warning | collisionRisk (97) | longitudinalCollisionRisk (1) | Local warning; broadcast only if confirmed |
| VRU_HERE (pocket beacon) | none (gap in AIS-230) | humanPresenceOnTheRoad (12) | cyclistOnRoadway (2) / motorcyclistOnRoadway (3) | AIS-230 lists no VRU application. This is RoadMesh's opening |
| PEDESTRIAN_AHEAD (camera) | none | collisionRisk (97) | vulnerableRoadUser (4) | "See-through" cooperative perception |
| ANIMAL_AHEAD (camera: cow, dog) | none | hazardousLocation-AnimalOnTheRoad (11) | largeAnimals (4) / smallAnimals (3) | India-specific hazard (stray cattle) |
| STOPPED_VEHICLE (camera) | none | stationaryVehicle (94) | unavailable (0) / vehicleBreakdown (2) | |
| CRASH / SOS | none | postCrash (92) | accidentWithECallTriggeredWithoutAccessToCellularNetwork (4) | Multi-hop to any phone with data |
| EMERGENCY_VEHICLE (siren heard or ambulance node) | Emergency Vehicle Alert | emergencyVehicleApproaching (95) | emergencyVehicleApproaching (1) | Green-corridor relay ahead |
| WRONG_WAY | Wrong-Way Driving Alert | wrongWayDriving (14) | wrongDirection (2) | Needs a road direction (map) |
| FOG / LOW_VISIBILITY | none | adverseWeatherCondition-Visibility (18) | fog (1) / heavyRain (4) | Raises warning distance in fog mode |
| POTHOLE / OBSTACLE | none | hazardousLocation-ObstacleOnTheRoad (10) | bigObjects (4) | ETSI surface-condition subcauses don't include potholes; obstacle is the nearest fit |

## What this means for RoadMesh
- **Technical depth:** encode `causeCode` (1 byte) and `subCauseCode` (1 byte) inside the 4 event bytes of the 24-byte alert. Then the message is a strict subset of DENM semantics and can be shown side by side with the ETSI ASN.1.
- **Novelty and impact:** RoadMesh covers all four AIS-230 applications and adds VRU presence, animals, fog and offline crash SOS. Those have ETSI codes but no AIS-230 phase-1 application, so this is a measurable gap in the national plan.
- **End product quality:** two-tier braking (caution vs emergency) cuts false "emergency" alerts. The emergency tier uses an industry-published trigger instead of a home-made number.
- **Pitch line backed by a source:** "ETSI already has a code for a crash with no cellular network. Phones are the only radios every road user already carries."

## Sources
1. https://www.techtimes.com/articles/322852/20260803/indias-v2v-mandate-first-world-cover-every-motorcycle-scooter.htm (AIS-230 draft: C-V2X, 5.875 to 5.925 GHz, L/M/N, four applications, 2027 and 2028 dates; the article links Business Standard, ANI and Business Today as its sources)
2. https://github.com/wireshark/wireshark/blob/c16ca0c3ee81e396b0e5cb5af538c484ace899da/epan/dissectors/asn1/its/ITS-ContainerV1.asn (ETSI ITS CDD v1 CauseCodeType and SubCauseCode definitions, as used by Wireshark's dissector)
3. https://www.car-2-car.org/fileadmin/documents/Basic_System_Profile/Release_1.6.7/C2CCC_RS_2003_DangerousSituation_R167.pdf (C2C-CC Basic System Profile 1.6.7, Dangerous Situation triggering conditions: EEBL thresholds, relevance distance, validity)
4. https://www.etsi.org/deliver/etsi_en/302600_302699/30263703/01.02.01_30/en_30263703v010201v.pdf (ETSI EN 302 637-3 DENM specification)
