# RESILANE 0.4: road conditions, reports and a self-organising chain

Judges' feedback on 0.3: the overtaking advice read as the whole product. The purpose is the road itself: find
potholes, accidents and violations and report them, damp phantom traffic jams, and warn drivers of hazards before
they reach them. 0.4 removes the overtaking advisor and builds those features.

## What the app does now

| Feature | Where | How |
|---|---|---|
| Pothole detection | `core/perception/RoadWatch.kt`, road model | YOLO11n trained on Indian pothole and helmet data (`tools/kaggle/road_yolo/train.py`); distance from where the pothole touches the road (camera height and horizon), confirmed in 2 of 4 frames |
| Accident detection | `RoadWatch`, `sensing/CrashDetector.kt` | A person lying on the road or a two-wheeler on its side with a person by it (held 4 of 6 frames); a >4 g impact on this phone; or the driver's "Report an accident here" button |
| Violations | `RoadWatch` | Triple riding (3+ riders on one two-wheeler), no helmet (road model's bare-head box over a rider), wrong-side driving (a vehicle in our lane closing faster than our own speed allows; needs GNSS speed), red-light jumping (a vehicle ahead moving on while a red light is within 50 m; lamp colour read from the frame) |
| Reports | `reports/ReportLog.kt`, Reports tab | Every pothole, accident and violation is logged with a snapshot, time, place and reason, and exported as JSON + CSV + JPEGs to `Downloads/RESILANE` for the municipality or traffic police |
| Road-condition query | `core/road/RoadQueries.kt`, Road tab | One request floods the chain forward and back; each phone answers with its position, speed and the hazards it holds; the asker combines them (`RoadPicture`) |
| Predictive inference | `RoadPicture.forecast` | From the combined picture this phone predicts where traffic is slowing, when the slowdown reaches it (wave moving back at about 16 km/h), and a speed to ease off to so it arrives after the queue moves instead of braking into it: the phantom-jam damper |
| Receiver inference | `NodeInference` | Every receiving phone works out the hazard's distance and time from itself, and the action, before the co-pilot phrases it |
| 3D mesh map | `ui/MeshMap.kt`, Mesh tab | A two-lane road in 3D with every phone as a vehicle block (letter, role), relay links as arcs that light while a warning crosses, and known hazards on the road. Drag to orbit, pinch to zoom |

## Any number of phones: the self-organising chain

0.3 had a fixed A → B → C chain and a relay budget of one hop. 0.4:

- **Presence beacons.** Every phone advertises a 33-byte presence frame every second on its own always-on BLE
  advertising set (`ControlFrame.Presence`: node, join time, camera on/off, vehicle, position, how many hazards it holds).
- **One ordering everywhere** (`core/mesh/Roster.kt`). Each phone sorts what it hears the same way: phones with the
  camera on first (earliest joined leads), then everyone else by join time. Position 0 is the observer, the last phone
  only receives, everyone between relays. No coordinator is needed and all phones agree (`ChainTest`). A phone silent
  for 4 s drops out and the chain closes up; a new phone becomes the new last, and the old last starts relaying to it.
  On the road the order comes from position along the direction of travel instead of join time.
- **Relaying down the chain.** Envelope v2 (61 bytes) carries a full-byte relay budget (up to 15) and the sender's
  chain position. On the stage a phone accepts a warning only from the position directly in front of it (phones on one
  table hear each other, so this filter keeps the hop-by-hop path honest), and relays only if someone is behind it.
- **Keeping the loop up.** In 0.3 one failed BLE burst ended the session ("stop and restart"). Now the transport
  rebuilds its advertising set and carries on (`recover()`); the screen shows how many times it recovered. Long-lived
  reports (potholes, accidents) are re-sent every 20 s while the phone is near them, so phones that join later still learn
  about them; receivers merge repeats by place and do not speak a known pothole twice.

## Co-pilot: answering instead of saying "Sure"

Stock Gemma 2B-it opened most answers with "Sure" and padded them. Three changes:

1. **Prompt frame** matched to the task: action first, only numbers from the facts, never "safe", never "Sure".
2. **Fine-tune.** `tools/copilot/dataset.py` generates 6,000 examples in exactly the formats the app sends (received
   warnings, the observer's own detections, scene narration, road-condition pictures, driver questions, logged
   violations), each answered by rule from its facts: 0 answers with an unsupported number, none opening with filler.
   `tools/copilot/finetune_kaggle.py` trains a LoRA on Gemma 2B-it (Kaggle GPU), scores base and tuned models on 40
   held-out prompts (filler rate, unsupported numbers, "safe", length), merges, and converts to the MediaPipe CPU int4
   `.bin` the phone loads (`gemma-2b-it-resilane-cpu-int4.bin`, preferred over the stock file when present).
3. **Guard.** Filler openers are cut; an answer that calls something safe or uses a number absent from its facts is
   replaced by the deterministic text the app already computed.

## NPU instead of XNNPACK

YOLO ran on the CPU through XNNPACK. 0.4 links ONNX Runtime's QNN build with QNN 2.50 (the runtime the NPU probe
verified on the iQOO 15, SM8850) and asks for the Hexagon NPU (HTP, fp16). Because a QNN fault can be native, the first
attempt runs in a separate `:npu` process (`NpuProbeService`); the live analysis uses the NPU only after that probe
succeeded on this phone and app version, and otherwise stays on the CPU. The detector chip shows which one is running.
BQBOOST was considered: it is a retrieval kernel library (sign codes, Hamming top-k, int8 rescoring) and has no
convolution kernels, so it cannot run YOLO.

## Measured results

- **Road model** (Kaggle, 5,057 images, 12,264 pothole / 1,039 helmet / 597 bare-head boxes, 12% held out):
  mAP50 0.81 overall, pothole 0.74, helmet 0.91, no helmet 0.77 (mAP50-95 0.51).
- **Co-pilot fine-tune** (40 held-out prompts, greedy decoding):

  | | Stock Gemma 2B-it | RESILANE fine-tune |
  |---|---|---|
  | Opens with "Sure"/"Okay" | 2.5% | 0% |
  | Uses a number not in the facts | 10% | 0% |
  | Calls something "safe" | 0% | 0% |
  | Over 40 words | 0% | 2.5% |

  Converted with MediaPipe 0.10.30 (the closest available on Kaggle's Python 3.13); the app runs tasks-genai 0.10.32.

## Honest limits

- The detection thresholds are first guesses checked on clips and unit tests, not validated road thresholds.
  Wrong-side and red-light rules are heuristics and need this phone's GNSS speed (wrong-side) on the road.
- The road model's accuracy is what `summary.json` from the Kaggle run reports, on its own validation split.
- Stage positions are synthetic (100 m apart by chain position) and disclosed in exports.
- Presence and queries are unsigned, like warnings.
- The NPU path and the fine-tuned co-pilot were built and packaged here but could only be run on the phone itself.
