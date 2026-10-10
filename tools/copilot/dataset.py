"""Training data for the RESILANE co-pilot (Gemma 2B-it, LoRA).

Every example is a prompt exactly as the app builds it (see app/.../copilot/Copilot.kt) and the answer we want: action
first, short, only numbers that appear in the facts, never "Sure"/"Okay", never calling a manoeuvre safe, and an honest
"I can't tell from this phone" when the facts don't answer the question. The facts mirror NodeInference.facts,
RoadPicture.facts and the observer/scene lines in DashcamObserver, so the model learns the formats it will really see.

    python tools/copilot/dataset.py --out train.jsonl --n 6000
"""
import argparse, json, random

SYSTEM = ("You are RESILANE, an in-car road co-pilot on Indian roads (traffic keeps left). {task} Use only the facts. "
          "Never invent objects, distances or speeds. Never call a manoeuvre safe. Do not start with Sure or Okay.")
TASK_EVENT = "Tell the driver what is happening and what to do, in one sentence of at most 22 words."
TASK_SCENE = "Describe what the camera sees and what to watch, in one sentence of at most 22 words."
TASK_ROAD = "Summarise the road for the driver in at most two short sentences, nearest hazard first, keeping every number exactly."
TASK_REPORT = "Tell the driver in one short sentence what was logged for the traffic police."
def task_question(q): return f'The driver asks: "{q}". Answer in at most two short sentences. If the facts do not answer it, say you cannot tell from this phone.'

def prompt(task, facts):
    return f"<start_of_turn>user\n{SYSTEM.format(task=task)}\nFacts: {facts}<end_of_turn>\n<start_of_turn>model\n"

VEHICLES = ["car", "two-wheeler", "auto-rickshaw", "bus or truck"]
LIFETIME = {"BRAKING": 90, "PEDESTRIAN": 30, "VEHICLE_CLOSE": 20, "POTHOLE": 1800, "ACCIDENT": 1200, "SLOWDOWN": 120, "WRONG_WAY": 120}
def a_(word): return ("an " if word[0] in "aeiou" else "a ") + word
TITLES = {"BRAKING": "Braking ahead", "PEDESTRIAN": "Pedestrian ahead", "VEHICLE_CLOSE": "Vehicle close ahead", "POTHOLE": "Pothole",
          "ACCIDENT": "Accident", "SLOWDOWN": "Traffic slowdown", "WRONG_WAY": "Wrong-side vehicle"}
SUBJECT = {"BRAKING": "Traffic braking", "PEDESTRIAN": "Someone on the road", "VEHICLE_CLOSE": "A vehicle cut in",
           "POTHOLE": "Pothole", "ACCIDENT": "Accident", "SLOWDOWN": "Traffic slowing", "WRONG_WAY": "A vehicle on the wrong side"}
ACTION = {  # (soon, later) — identical to NodeInference.at
    "PEDESTRIAN": ("slow down now and be ready to stop", "ease off and watch for people on the road"),
    "BRAKING": ("brake gently and open the gap to the car ahead", "ease off early so you don't have to brake hard"),
    "VEHICLE_CLOSE": ("hold your lane and leave room, a vehicle cut in ahead", "expect a sudden lane change ahead"),
    "POTHOLE": ("slow to about 20 km/h and watch the road surface", "expect a pothole; slow down before you reach it"),
    "ACCIDENT": ("slow down now, a lane may be blocked and people may be on the road", "slow down early and be ready to change lanes"),
    "SLOWDOWN": ("ease off now so you reach the queue after it moves", "ease off early; a slowdown is moving back toward you"),
    "WRONG_WAY": ("keep left and be ready to stop, a vehicle is coming the wrong way", "keep left; a vehicle is on the wrong side ahead"),
}
LETTERS = "ABCDEFGH"

def event(r):
    kind = r.choice(list(ACTION)); frm = r.choice(LETTERS[:3]); via = r.choice([None, LETTERS[LETTERS.index(frm) + 1]])
    rng = r.choice([None, r.randint(4, 40)]); conf = r.choice([None, r.randint(45, 95)]); veh = r.choice(VEHICLES + [None])
    to_origin = r.randint(40, 900); ahead = to_origin + (rng or 0)
    speed = r.choice([None, r.uniform(3, 22)]); eta = ahead / speed if speed and speed > 1 else None
    soon = ahead < 80 or (eta is not None and eta < 8); action = ACTION[kind][0 if soon else 1]
    f = f"{TITLES[kind]} reported by phone {frm}"
    if veh: f += f" ({a_(veh)})"
    if rng: f += f", whose camera saw it about {rng} m in front of it"
    if conf: f += f" with {conf}% detector confidence"
    if via and via != frm: f += f", relayed by phone {via}"
    f += f". From here the hazard is about {ahead} m ahead"
    if eta: f += f", about {round(eta)} s away at the current speed"
    f += f". Recommended: {action}. This phone is in {a_(r.choice(VEHICLES))}."
    when = f", about {round(eta)} s away" if eta else ""
    lead = SUBJECT[kind] if kind not in ("PEDESTRIAN",) else "Someone on the road"
    a = r.choice([f"{lead} about {ahead} m ahead{when}: {action}.", f"{lead} {ahead} m ahead{when} — {action}."])
    return prompt(TASK_EVENT, f), a

OBS = {
    "PEDESTRIAN": (lambda d: f"person on foot in the lane about {d} m ahead", "slow down and be ready to stop"),
    "VEHICLE_CLOSE": (lambda d: f"{random.choice(['car','motorcycle','bus','truck'])} in the lane only {d}.{random.randint(0,9)} m ahead", "hold back and open the gap"),
    "BRAKING": (lambda d: f"closing on the car ahead, contact in 1.{random.randint(0,9)} s", "brake gently now"),
    "POTHOLE": (lambda d: f"pothole on the road about {d} m ahead", "slow down and steer smoothly around it if the lane is clear"),
    "ACCIDENT": (lambda d: random.choice(["a person lying on the road", "a two-wheeler on its side with a person by it"]), "slow right down and stop if people are on the road"),
    "WRONG_WAY": (lambda d: f"car in our lane coming toward us about {d} m ahead", "keep left and be ready to stop"),
}
def counts(r):
    parts = []
    for name in ["car", "motorcycle", "person", "bus", "truck", "bicycle"]:
        if r.random() < 0.45:
            n = r.randint(1, 5); parts.append(f"{n} {name}{('es' if name.endswith('s') else 's') if n > 1 else ''}")
    return ", ".join(parts) or "an empty road"

def observed(r):
    kind = r.choice(list(OBS)); d = r.randint(3, 30); reason, todo = OBS[kind][0](d), OBS[kind][1]
    sent = r.random() < 0.8; veh = r.choice(VEHICLES)
    f = (f"This {veh}'s front camera saw: {reason}. Hazard: {TITLES[kind]}. Also in view: {counts(r)}. " +
         ("A warning was broadcast to the vehicles behind over the Bluetooth mesh." if sent else "The mesh is not running, so nobody behind was warned."))
    tail = "vehicles behind have been warned." if sent else "nobody behind was warned."
    return prompt(TASK_EVENT, f), f"{reason[0].upper() + reason[1:]}: {todo}; {tail}"

def scene(r):
    c = counts(r); veh = r.choice(VEHICLES)
    lead = r.choice([None, (r.choice(["car", "motorcycle", "bus", "truck", "auto"]), r.randint(5, 45))])
    f = f"Front camera of {a_(veh)} on an Indian city road. In view: {c}. "
    f += f"The nearest vehicle in our lane is {a_(lead[0])} about {lead[1]} m ahead. " if lead else "No vehicle directly ahead in our lane. "
    f += "No hazard right now."
    watch = []
    if "motorcycle" in c: watch.append("two-wheelers cutting in")
    if "person" in c: watch.append("people stepping onto the road")
    if "bus" in c: watch.append("buses pulling over")
    w = " and ".join(watch[:2]) or "the road edges"
    if lead: a = f"{lead[0][0].upper() + lead[0][1:]} about {lead[1]} m ahead in our lane; keep your gap and watch {w}."
    elif c == "an empty road": a = "The road ahead looks clear; keep a steady speed and watch the road edges."
    else: a = f"No vehicle directly ahead in our lane; watch {w}."
    return prompt(TASK_SCENE, f), a

def picture(r, phones=None):
    """A RoadPicture.facts line and the facts behind it."""
    n = phones if phones is not None else r.randint(0, 5)
    peers = [(r.choice([1, -1]) * r.randint(80, 900), r.choice([None, round(r.uniform(0, 60))])) for _ in range(n)]
    f = f"Asked {n} phone{'' if n == 1 else 's'} on the road"
    if peers: f += " (" + ", ".join((f"{d} m ahead" if d >= 0 else f"{-d} m behind") + (f" at {s} km/h" if s is not None else "") for d, s in peers) + ")"
    f += ". "
    items = []
    for _ in range(r.randint(0, 3)):
        k = r.choice(["POTHOLE", "ACCIDENT", "BRAKING", "WRONG_WAY", "PEDESTRIAN"]); items.append((k, r.choice([1, 1, -1]) * r.randint(30, 1400), r.randint(1, 4), r.randint(1, LIFETIME[k])))
    ahead = sorted([i for i in items if i[1] >= 0], key=lambda i: i[1]); behind = [i for i in items if i[1] < 0]
    if not ahead: f += "No hazards reported ahead. "
    else: f += "Ahead: " + "; ".join(f"{TITLES[k].lower()} at {d} m ({n_} phone{'' if n_ == 1 else 's'}, {age} s old)" for k, d, n_, age in ahead) + ". "
    if behind: f += "Behind: " + "; ".join(f"{TITLES[k].lower()} {-d} m back" for k, d, _, _ in behind) + ". "
    advice = []
    my = r.choice([None, r.uniform(5, 18)])
    slow = [d for d, s in peers if d > 0 and s is not None and (my and s / 3.6 < my * 0.5 or s / 3.6 < 3)]
    if slow:
        at = min(slow)
        if my:
            meet = round(at / (my + 4.5)); rec = max(15, round(3.6 * max(4.2, min(my, my * at / (at + 90)))))
            advice.append(f"Traffic is slowing about {at} m ahead; at your speed you would meet it in about {meet} s.")
            if rec < round(my * 3.6) - 3: advice.append(f"Easing off to about {rec} km/h now lets it clear before you arrive, so you avoid braking hard and don't pass the jam back.")
        else: advice.append(f"Traffic is slowing about {at} m ahead.")
    nxt = next((i for i in ahead if i[1] <= 1500), None)
    if nxt:
        k, d = nxt[0], nxt[1]; eta = f" (about {round(d / my)} s away)" if my else ""
        advice.append({"POTHOLE": f"Pothole {d} m ahead{eta}: slow to about 20 km/h and watch the road surface.",
                       "ACCIDENT": f"Accident {d} m ahead{eta}: slow down early, expect a blocked lane and people on the road.",
                       "WRONG_WAY": f"A vehicle is on the wrong side {d} m ahead{eta}: keep left and be ready to stop.",
                       "PEDESTRIAN": f"Someone is on the road {d} m ahead{eta}: slow down.",
                       "BRAKING": f"Traffic is braking {d} m ahead{eta}: ease off early."}[k])
    if not slow and not nxt: advice.append("Nothing reported on the road ahead within range.")
    f += " ".join(advice)
    return f.strip(), ahead, behind, advice, slow

def road(r):
    f, ahead, behind, advice, slow = picture(r)
    if not ahead and not slow:
        a = "Nothing reported on the road ahead" + (f"; there is a {TITLES[behind[0][0]].lower()} {-behind[0][1]} m behind you." if behind else ".")
        if not behind: a += " Keep a steady speed."
    else:
        a = " ".join(advice[:2])
    return prompt(TASK_ROAD, f), a

UNKNOWN_Q = ["Will it rain today?", "How far is the airport?", "What's the best route home?", "Is there parking near the mall?",
             "Who is driving the car in front?", "What is the speed limit here?", "Is the shop open?", "How much fuel do I have?"]
def question(r):
    f, ahead, behind, advice, slow = picture(r)
    facts = f"This phone is in {a_(r.choice(VEHICLES))}. " + f
    kind = r.random()
    if kind < 0.25:
        q = r.choice(UNKNOWN_Q); a = "I can't tell that from this phone; I only know what the mesh and the camera report about the road."
    elif kind < 0.5:
        q = r.choice(["Any potholes ahead?", "Is the road surface okay ahead?", "Should I worry about potholes?"])
        p = [i for i in ahead if i[0] == "POTHOLE"]
        slow_to = "slow to about 20 km/h before it" if "20 km/h" in f else "slow down before you reach it"
        a = (f"Yes, a pothole about {p[0][1]} m ahead, reported by {p[0][2]} phone{'' if p[0][2] == 1 else 's'}; {slow_to}."
             if p else "No potholes are reported ahead within range.")
    elif kind < 0.75:
        q = r.choice(["How's the traffic ahead?", "Will I have to stop?", "Is there a jam ahead?"])
        a = " ".join(advice[:2]) if slow else ("No slowdown is reported ahead; traffic ahead is moving.")
    else:
        q = r.choice(["What's the road like ahead?", "Anything I should know about?", "Is the road ahead clear?"])
        a = " ".join(advice[:2]) if (ahead or slow) else "Nothing is reported ahead within range. Keep a steady speed and watch the road yourself."
    return prompt(task_question(q), facts), a

VIOL = {"TRIPLE_RIDING": ("{n} people riding one two-wheeler", "Triple riding"),
        "NO_HELMET": ("two-wheeler rider without a helmet", "Rider without helmet"),
        "RED_LIGHT": ("{v} ahead moving through a red light about {d} m away", "Red light jumped")}
def report(r):
    k = r.choice(list(VIOL)); reason = VIOL[k][0].format(n=r.randint(3, 4), v=r.choice(["car", "motorcycle", "bus", "auto"]), d=r.randint(8, 45))
    f = (f"This {r.choice(VEHICLES)}'s front camera saw: {reason}. Violation: {VIOL[k][1]}. "
         "It was logged with a snapshot and location for the traffic police and shared over the mesh.")
    return prompt(TASK_REPORT, f), f"{VIOL[k][1]} logged with a snapshot and location for the traffic police; no action needed from you."

MAKERS = [(event, 3), (observed, 2), (scene, 2), (road, 3), (question, 3), (report, 1)]
def make(n, seed):
    r = random.Random(seed); random.seed(seed)
    pool = [m for m, w in MAKERS for _ in range(w)]
    seen, out = set(), []
    while len(out) < n:
        p, a = r.choice(pool)(r)
        if not a or p in seen: continue
        seen.add(p); out.append({"prompt": p, "completion": a})
    return out

if __name__ == "__main__":
    ap = argparse.ArgumentParser(); ap.add_argument("--out", default="train.jsonl"); ap.add_argument("--n", type=int, default=6000); ap.add_argument("--seed", type=int, default=7)
    args = ap.parse_args()
    rows = make(args.n, args.seed)
    with open(args.out, "w", encoding="utf8") as fh:
        for row in rows: fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"wrote {len(rows)} examples to {args.out}")
