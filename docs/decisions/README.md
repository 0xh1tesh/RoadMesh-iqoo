# Decisions log

One short entry per decision made at the event: date, decision, evidence, who. Day-1 device probe results go here too.

Open items to settle first:
- [ ] Detector licence: YOLOX via Qualcomm AI Hub (check the per-model licence) or YOLO26n (AGPL-3.0, whole repo then AGPL-3.0). Then add `LICENSE`.
- [ ] Organisers: are sample-app patterns and pre-trained weights allowed with attribution? Submission cut-off?
- [ ] BLE probe: extended advertising, 2M, Coded, max data length, advertising-set count, Wi-Fi Aware.
- [ ] NPU probe: AI Hub `object_detection_android` sample on the loaner; is OpenCL present for the GPU rung?
- [ ] LLM ladder probe: GenieX chat sample, then LiteRT-LM, then llama.cpp on CPU. Record model, time to first token, tokens/s, memory.
- [ ] Offline TTS voices: en, hi-IN, kn-IN.
- [ ] OriginOS settings for our app only (never HackTracker).
