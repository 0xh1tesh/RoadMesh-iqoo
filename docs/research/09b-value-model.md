# Value model: where a mesh alert beats brake lights (and where it doesn't)
*Researched 2026-10-08 · Sources: 3 · Confidence: Medium (simple model built on published reaction times; the calculations are ours)*

## Bottom line
- Driver brake reaction time is about **0.7 s when braking is expected**, about **1.25 s for an unexpected but common signal such as the brake lights of the car ahead**, and about **1.5 s for a surprise such as a crossing pedestrian**. The author stresses there is no single "standard" value [1].
- In a queue, each driver reacts to the car directly ahead, so the delay **adds up down the line**. A broadcast reaches every phone at once, so its delay is **flat**. This is the core value argument, and it can be shown with a table.
- **Honest scope:** the car directly behind the braking car usually sees the brake lights sooner than any radio alert, so RoadMesh adds little there in clear view. The gain starts at the **third car back**, and for **any car that can't see** (fog, curves, behind a bus or truck, at night with glare).
- Rear-end collision warnings in every modality cut reaction time versus no warning, and **tactile warnings were fastest** in a driving-simulator study [2]. Haptics should be first-class: phone vibration in pocket mode (riders in helmets) and audio plus vibration on the dash.

## Model (our calculation, labelled as an estimate)
Assumptions:
- The lead car brakes hard at t = 0.
- Without RoadMesh, car *k* back starts braking after about 1.25 s × k, since each driver reacts to the brake lights directly ahead [1].
- With RoadMesh, detection takes up to 0.5 s (the C2C-CC emergency-brake window), radio delivery 0.1 to 1.0 s, TTS start 0.1 s, then the driver reacts to the warning in a conservative 1.25 s. That puts brake onset for **every** warned car at **1.95 to 2.85 s**, whatever k is.

| Car behind lead (k) | Brake onset without RoadMesh | With RoadMesh (all k) | Time gained | Distance gained at 50 km/h | at 80 km/h |
|---|---|---|---|---|---|
| 1 | 1.25 s | 1.95 to 2.85 s | none (brake lights win) | none | none |
| 2 | 2.50 s | 1.95 to 2.85 s | -0.35 to +0.55 s | up to 8 m | up to 12 m |
| 3 | 3.75 s | 1.95 to 2.85 s | 0.9 to 1.8 s | 12 to 25 m | 20 to 40 m |
| 4 | 5.00 s | 1.95 to 2.85 s | 2.15 to 3.05 s | 30 to 42 m | 48 to 68 m |

Speeds: 50 km/h = 13.9 m/s, 80 km/h = 22.2 m/s. Distance gained = time gained × speed (rounded).

When visibility is blocked (fog, a bend, behind a bus), the "without" column is worse for every k, including k = 1, because there are no brake lights to see. That is where the "see-through" and fog-mode features belong.

## What this means for RoadMesh
- **Novelty and impact (pitch):** "Brake lights are a relay race of human reactions: about 1.25 seconds per car. A broadcast is not a relay race. Four cars back, that's roughly 30 metres at city speed."
- **End product quality:** the relevance filter should rank alerts by *occlusion*: same lane and two or more vehicles ahead, around a curve, or in fog mode. It should not alert a driver who can plainly see the hazard. That cuts alarm fatigue.
- **Technical depth (demo metric):** show the measured radio latency (p50/p95) beside this table. The measured number turns the estimate into evidence.
- **Creative phone use:** use haptic patterns as an alert channel, not just sound (tactile warnings were fastest [2]).

## Sources
1. https://www.visualexpert.com/Resources/reactiontime.html (Marc Green: summary of "How Long Does It Take to Stop? Methodological Analysis of Driver Perception-Brake Times", Transportation Human Factors 2(3):195-216, 2000. Expected 0.7 s; unexpected, e.g. brake lights ahead, about 1.25 s; surprise about 1.5 s; no universal value)
2. https://pubs.lib.uiowa.edu/driving/article/28327/galley/136619/view/ (Scott and Gray, Driving Assessment conference: visual, auditory and tactile rear-end collision warnings all reduced RT vs no warning; tactile fastest)
3. https://www.car-2-car.org/fileadmin/documents/Basic_System_Profile/Release_1.6.7/C2CCC_RS_2003_DangerousSituation_R167.pdf (emergency brake trigger window of 500 ms used in the model)
