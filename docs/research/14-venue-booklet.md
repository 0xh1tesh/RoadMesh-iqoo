# Venue booklet: official Finale rules (transcribed)
*From five photos of the printed playbook handed out at the Grand Finale, Bengaluru, 9 Oct 2026 (shared by the team at 17:57). Wording is summarised except where quoted. Originals: the WhatsApp photos in the team chat.*

## What the event is
- "India's first phone-first hybrid hackathon series. Four cities. One Grand Finale. ₹40,00,000." Over 60,000 registrations and 10,000 ideas; only the top ideas plus the City Battle winners reach the Finale.
- **Format:** the Finale runs 48 hours on a Green Light / Red Light rhythm, "roughly 55/45". In Red Light, direct laptop use is restricted and the build keeps running on the phone, "pushing teams toward quantised models and on-device inference."
- **HackTracker:** "Every team runs HackTracker. It sits on the device through the build, reads model outputs, and logs inference calls, tokens and thermals in real time. The 48 hours become a record rather than a claim."
- **Device:** the iQOO 15. Snapdragon 8 Elite Gen 5 with a dedicated Q3 chip and 16 GB LPDDR5X Ultra RAM, which "runs quantised local LLMs at usable speed", a 14,000 mm² vapour chamber against throttling during sustained inference, and a 7,000 mAh battery.
- Students and working professionals compete in separate buckets.

## Playbook 1/2: rules and guidelines
**Phone-first format**
- The iQOO device is the build surface and the demo surface: every entry must run and pitch on the phone.
- A local or open-source model at the core earns brownie points, with the phone in the loop via Office Kit.
- Office Kit bridges phone and laptop: screen mirror, clipboard, file transfer and remote control. Pairing instructions ship with the loaner and are covered in the Friday teach-in.
- On-device inference targets the Snapdragon NPU. AI credits for the weekend are free.
- Stacks: native Android, Flutter, React Native or PWA. "If it runs on the phone with a local or open-source model at the core, it qualifies."

**Your iQOO device**
- One flagship loaner per person, with HackTracker pre-installed and Office Kit already paired.
- Devices remain iQOO property: keep them in the venue and return them before exit.
- HackTracker captures counts and durations only (no keystrokes, screenshots or browsing), for creative phone use and Office Kit scoring.

**48-hour battle:** check-in around Fri 18:00, awards around Sun 17:00. Green Light = both devices; Red Light = iQOO phone only, via Office Kit. **Three evaluation rounds feed a Top Teams pitch.**

**Tracks:** six at the Finale, including Mobility and Community App. Tracks are broad domains, not fixed briefs; Open Innovation is the wildcard.

## Playbook 2/2: rules and guidelines
**Build rules**
- Original work only: code written during the event window. No shipping a pre-built product.
- Open-source libraries and frameworks are fine with attribution. Carrying in a completed app is not.
- Submit the repo and demo assets on the Reskilll platform before the hard cut-off. Repos are locked before the Top Team pitches. Late submissions may be penalised or disqualified.
- Cheating, plagiarism or unfair practice means immediate disqualification. Organisers may verify that a project was built inside the event window.

**Judging:** three scored evaluation rounds plus a Top Teams final pitch, with the demo on the iQOO phone. HackTracker supplies the phone-use and Office Kit scores; the jury scores product quality, novelty, depth and pitch. **Weights:** end product 30% · novelty 20% · creative phone use 15% · technical depth 15% · Office Kit 10% · demo 10%. Hit a technical issue before your slot? Tell an organiser immediately.

**Also:** code of conduct, venue rules, the organiser contact desk, and media consent. Do not film or share another team's screen or build without permission.

## The format: Red Light / Green Light
- The printed timeline alternates Red and Green across Fri 19:00 to Sun 09:00. Its boundaries are 19:00, 21:00, 00:00, 03:00, 06:00, 08:00, 10:00 / 11:00, 14:00, 15:30, 16:30, 19:00, 22:00, 01:00, 06:30, 09:00. Check the booklet for which window is which colour; the last laptop window ends at 06:30 on Sunday.
- "Timings and schedule may vary and will be informed at the venue."
- **Red Light:** "Direct usage of laptops is restricted and will be monitored. Work on your phone, or access your laptop only via the office-kit on your phone."
- **Green Light:** free to use anything.
- **Device notes:** one device per person; do not leave the venue with it; return it after the hackathon; you are responsible for it.
- **HackTracker lockout:** contact the organisers. Genuine cases get help; tampering is penalised.
- "Do not tamper with HackTracker under any circumstances. All crash and tamper logs are detected and will be penalised."
- Chargers and wires are available on demand. AI credits can be used up to the stated amount.

**Tip to win (verbatim):** "Build apps that run locally and on-device, including the backend, using on-device (mobile phone) LLMs. These earn brownie points. Use the office-kit to connect to a laptop when necessary during a red light. The highest on-device builds will be preferred for the Top Teams Pitches."

## AI credits page
- Claim credits at the platform with your team; the recommended-models tab becomes visible after claiming. The curated model list is the place to start.
- "Device inference is free: running local LLMs directly on the iQOO uses zero credits. Explore this route."
- Credits can be used inside the app or in agentic coding tools. Credits are per team, and frontier models are included.

## The venue site the booklet links to
iqoo-blr.reskilll.com is the page for the **June 2026 Bengaluru city event** (30 h, Scaler School of Technology), not the Finale. Its five-part rubric differs from the Finale weights above, so ignore its percentages. Three signals still apply:
- "UX, on-device intelligence, and on-device LLM execution carry the judging weight."
- "How well AI is integrated not bolt-on. Quality of prompts, model choice, latency on mobile hardware."
- Office Kit: "More phone-bridge time = more points in judging", and HackTracker is described as a "server-side leaderboard".

## What changes for RoadMesh
1. **The on-device LLM is core**, as a co-pilot off the alert path (plan Phase 4B). Its minimum is a drive debrief from the local event log.
2. **The LLM must be integrated, not bolted on.** The voice hazard report turns speech into a real, checked mesh alert, and the debrief is part of the drive loop.
3. **No server anywhere.** The phone is the backend; the laptop only mirrors and receives exports through Office Kit.
4. **Zero crashes.** Crash logs are penalised, so catch every callback, budget memory (unload the LLM under pressure) and run the soak test.
5. **Keep Office Kit connected and in genuine use** throughout: mirroring as the dev display, file transfer for APKs and exports, and remote control in Red Light.
