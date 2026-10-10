"""Run three matched offline traffic experiments and export an honest visual replay.

Usage: python tools/junction_lab/run.py --java /path/to/jdk17/bin/java --seeds 7 19 42
See README.md. No Android device or network service is modified.
"""
from __future__ import annotations

import argparse
from dataclasses import asdict
import hashlib
import json
import os
from pathlib import Path
import shutil
import statistics
import subprocess

from model import Config, MODES, Observation, PolicyBridge, LogicalMesh, draw
from scenario import generate, sumo_binary

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
SUMO_VERSION = "1.28.0"

ASSUMPTIONS = [
    "Synthetic two-junction experiment, not a road-safety system or a measured benefit.",
    "SUMO 1.28.0; one eastbound lane, one car class, no pedestrian traffic or alternate route in this slice.",
    "Fixed environment: J1 serves the main road; J2 holds it red during the configured interval. Policies do not control signals.",
    "All modes share exact scheduled demand, geometry, seed and event schedule; trajectories may diverge after modeled response.",
    "Hypothetical local forward sensor sees stopped vehicles within local_range. Equipped stopped probes can broadcast their own location.",
    "The policy receives scoped observations, age and recipient position/speed, never the true queue count or signal state.",
    "Logical transport uses geometric contacts, one attempt per relay/neighbor, configurable loss/delay, bounded queues and three hops. It is not BLE airtime or RF validation.",
    "Two optional stationary pedestrian relay nodes forward only; they contribute no vehicle observations.",
    "Compliance is a fixed per-driver random draw. After response_delay, a responder adopts a temporary desired-speed cap; SUMO car-following and right-of-way remain active.",
    "The desired-speed cap is a disclosed human-response proxy, not a numeric speed instruction or a measured human behavior model.",
    "Delay includes scheduled-but-uninserted source waiting and active speed deficit integrated over the fixed horizon; unfinished trips remain counted.",
    "Queue extent is the ground-truth stopped extent on the connector, a geometry diagnostic rather than an inferred contiguous queue.",
    "Warning lead is time to cross the reported historical position. Useful means at least the assumed response delay; it does not certify hazard persistence or safety.",
    "Collision/teleport counters are simulator diagnostics. No TTC/PET, pedestrian safety or crash-reduction claim is made.",
]


def java_executable(explicit=None):
    if explicit:
        path = Path(explicit)
        if not path.is_file():
            raise RuntimeError(f"Java executable does not exist: {path}")
        return str(path)
    if os.environ.get("JAVA_HOME"):
        path = Path(os.environ["JAVA_HOME"]) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if path.is_file():
            return str(path)
    if shutil.which("java"):
        return shutil.which("java")
    raise RuntimeError("JDK 17+ required. Set JAVA_HOME or pass --java.")


def set_environment_signals(connection, now, config):
    for junction in ("j1", "j2"):
        links = connection.trafficlight.getControlledLinks(junction)
        state = []
        for alternatives in links:
            main = any(link[0].startswith(("approach_", "connector_")) for link in alternatives)
            red = junction == "j2" and config.red_start <= now < config.red_end
            state.append("G" if main and not red else "r")
        connection.trafficlight.setRedYellowGreenState(junction, "".join(state))


def run_one(mode, seed, config, java, jar, net, routes, departures, workdir):
    import traci
    workdir.mkdir(parents=True, exist_ok=True)
    label = f"junction-{mode}-{seed}"
    command = [sumo_binary("sumo"), "--net-file", str(net), "--route-files", str(routes),
               "--step-length", str(config.step), "--seed", str(seed), "--time-to-teleport", "-1", "--route-steps", "0",
               "--no-step-log", "true", "--duration-log.disable", "true", "--no-warnings", "true",
               "--collision.action", "warn", "--collision.check-junctions", "true",
               "--log", str(workdir / "sumo.log")]
    traci.start(command, label=label, doSwitch=False, stdout=subprocess.DEVNULL)
    connection = traci.getConnection(label)
    try:
        version = connection.getVersion()[1]
        if SUMO_VERSION not in version:
            raise RuntimeError(f"Pinned SUMO {SUMO_VERSION} required, got {version}")
        with PolicyBridge(java, jar) as policy:
            return _simulate(connection, policy, mode, seed, config, departures, version)
    finally:
        connection.close()


def _simulate(connection, policy, mode, seed, config, departures, version):
    equipped = {v: draw(seed, "equipment", v) < config.penetration for v in departures}
    accepts = {v: draw(seed, "compliance", v) < config.compliance for v in departures}
    mesh = LogicalMesh(config, seed)
    departed, arrived = set(), set()
    responses, capped, stop_since = {}, set(), {}
    warnings, frames, lead_pending, lead_times, stop_pending = [], [], [], [], []
    knowledge = {}
    digest = hashlib.sha256()
    total_delay = spillback_duration = max_queue = 0.0
    max_corridor_extent = cap_active_seconds = 0.0
    max_source_backlog = departed_counter = arrived_counter = 0
    collisions = teleports = conservation_errors = 0
    connector_length = connection.lane.getLength("connector_0")
    connector_end_x = connection.lane.getShape("connector_0")[-1][0]
    positions = {}
    pending = future = 0
    for tick in range(int(config.duration / config.step)):
        before = tick * config.step
        set_environment_signals(connection, before, config)
        active_before = set(connection.vehicle.getIDList())
        for vehicle in list(responses):
            start, end, warning = responses[vehicle]
            if vehicle not in active_before:
                responses.pop(vehicle)
                capped.discard(vehicle)
                continue
            if before >= end:
                connection.vehicle.setMaxSpeed(vehicle, config.free_speed)
                capped.discard(vehicle)
                responses.pop(vehicle)
            elif before >= start and vehicle not in capped:
                warning["speedAtResponseMps"] = connection.vehicle.getSpeed(vehicle)
                connection.vehicle.setMaxSpeed(vehicle, config.response_speed)
                capped.add(vehicle)
                warning["responded"] = True
                warning["responseAppliedAt"] = before
        cap_active_seconds += config.step * len(capped & active_before)
        connection.simulationStep()
        now = connection.simulation.getTime()
        departed.update(connection.simulation.getDepartedIDList())
        arrived.update(connection.simulation.getArrivedIDList())
        departed_counter += connection.simulation.getDepartedNumber()
        arrived_counter += connection.simulation.getArrivedNumber()
        teleports += connection.simulation.getStartingTeleportNumber()
        collisions += connection.simulation.getCollidingVehiclesNumber()
        positions = {}
        for vehicle in sorted(connection.vehicle.getIDList()):
            x, y = connection.vehicle.getPosition(vehicle)
            positions[vehicle] = {"id": vehicle, "x": x, "y": y,
                                  "speed": connection.vehicle.getSpeed(vehicle),
                                  "edge": connection.vehicle.getRoadID(vehicle),
                                  "lanePosition": connection.vehicle.getLanePosition(vehicle),
                                  "equipped": equipped[vehicle]}
        due = sum(t < now + 1e-7 for t in departures.values())
        pending, future = due - len(departed), len(departures) - due
        max_source_backlog = max(max_source_backlog, pending)
        if pending < 0 or set(positions) & arrived or len(departed) != len(positions) + len(arrived):
            conservation_errors += 1
        if len(departures) != future + pending + len(positions) + len(arrived):
            conservation_errors += 1
        # Independent SUMO counters, with route-steps=0 loading all scheduled input.
        if (departed_counter != len(departed) or arrived_counter != len(arrived) or
                connection.simulation.getMinExpectedNumber() + arrived_counter != len(departures)):
            conservation_errors += 1
        total_delay += config.step * (max(0, pending) + sum(max(0, 1 - v["speed"] / config.free_speed) for v in positions.values()))
        stopped = [v for v in positions.values() if v["edge"] == "connector" and v["speed"] <= 0.5]
        extent = max((connector_length - v["lanePosition"] for v in stopped), default=0.0)
        max_queue = max(max_queue, extent)
        corridor_extent = max((connector_end_x - v["x"] for v in positions.values()
                               if v["speed"] <= 0.5 and v["x"] <= connector_end_x), default=0.0)
        max_corridor_extent = max(max_corridor_extent, corridor_extent)
        if extent >= connector_length - 15:
            spillback_duration += config.step
        for vehicle in list(stop_since):
            if vehicle not in positions or positions[vehicle]["edge"] != "connector" or positions[vehicle]["speed"] > 0.5:
                del stop_since[vehicle]
        for v in stopped:
            stop_since.setdefault(v["id"], now)

        nodes = {v["id"]: (v["x"], v["y"]) for v in positions.values() if v["equipped"]}
        if config.relay_enabled:
            nodes.update({"ped-relay-a": (160.0, -12.0), "ped-relay-b": (240.0, 12.0)})
        if mode == "mesh_informed":
            mesh.advance(now, nodes)
            if tick % 4 == 0:
                for v in stopped:
                    if v["equipped"] and now - stop_since[v["id"]] >= 2:
                        event = Observation(f"{v['id']}/probe/{tick}", v["id"], now,
                                            now + config.event_lifetime, v["x"], v["y"])
                        mesh.originate(v["id"], event, now, nodes)
                # Also permits zero-delay contacts in the declared logical model.
                mesh.advance(now, nodes)

        for vehicle, v in positions.items():
            if not v["equipped"] or v["edge"] not in ("approach", "connector"):
                continue
            candidates = []
            visible = [target for target in stopped if 0 < target["x"] - v["x"] <= config.local_range]
            if visible:
                target = min(visible, key=lambda target: target["x"])
                local = Observation(f"{vehicle}/local/{tick}", vehicle, now,
                                    now + config.event_lifetime, target["x"], target["y"])
                candidates.append((local, "local"))
            if mode == "mesh_informed":
                candidates.extend((event, "mesh") for event in mesh.observations(vehicle, now)
                                  if 0 < event.x - v["x"] <= 400)
            candidates.sort(key=lambda item: (item[0].x - v["x"], -item[0].captured, item[0].event_id, item[1]))
            if candidates:
                event, channel = candidates[0]
                # One selected observation, not the full cache or a queue oracle.
                knowledge[vehicle] = {"recipientId": vehicle, "capturedAt": event.captured,
                                      "expiresAt": event.expires, "sourceId": event.source_id, "channel": channel}
            for event, channel in candidates:
                if mode == "no_advice":
                    break
                warned = policy.evaluate(mode, vehicle, now, event, channel, event.x - v["x"], v["speed"])
                if warned:
                    warning = {"time": now, "recipientId": vehicle, "sourceId": event.source_id,
                               "eventId": event.event_id, "capturedAt": event.captured, "expiresAt": event.expires,
                               "channel": channel, "x": v["x"], "y": v["y"], "aheadDistanceM": event.x - v["x"],
                               "responded": False, "responseAppliedAt": None,
                               "speedAtResponseMps": None,
                               "responseAccepted": accepts[vehicle], "leadS": None,
                               "kinematicLeadS": round((event.x - v["x"]) / v["speed"], 3),
                               "timeToFirstStopS": None}
                    warnings.append(warning)
                    lead_pending.append((vehicle, event.x, warning))
                    stop_pending.append((vehicle, event.x, warning))
                    if accepts[vehicle]:
                        start = now + config.response_delay
                        responses[vehicle] = (start, start + config.response_duration, warning)
                    break
                # No later candidate can override recipient cooldown, but Kotlin owns that rule.

        for item in list(stop_pending):
            vehicle, target_x, warning = item
            if vehicle in positions:
                if positions[vehicle]["speed"] <= 0.5 and positions[vehicle]["x"] < target_x:
                    warning["timeToFirstStopS"] = round(now - warning["time"], 3)
                    stop_pending.remove(item)
                elif positions[vehicle]["x"] >= target_x:
                    stop_pending.remove(item)
            elif vehicle in arrived:
                stop_pending.remove(item)
        for item in list(lead_pending):
            vehicle, target_x, warning = item
            if vehicle in positions and positions[vehicle]["x"] >= target_x:
                lead = now - warning["time"]
                warning["leadS"] = round(lead, 3)
                lead_times.append(lead)
                lead_pending.remove(item)
            elif vehicle in arrived:
                lead_pending.remove(item)  # Censored: no invented sub-step crossing time.
        knowledge = {k: v for k, v in knowledge.items() if k in positions and v["expiresAt"] > now}
        visible_vehicles = [{k: (round(v[k], 4) if isinstance(v[k], float) else v[k])
                             for k in ("id", "x", "y", "speed", "equipped")} for v in positions.values()]
        # Includes every traffic step, not just the sampled animation.
        digest.update(json.dumps([now, pending, sorted(arrived), visible_vehicles], separators=(",", ":")).encode())
        if tick % 2 == 1 or tick == 0:
            previous_frame = frames[-1]["time"] if frames else -1
            warning_ids = sorted({w["recipientId"] for w in warnings if previous_frame < w["time"] <= now})
            frames.append({"time": now, "queueExtentM": round(extent, 3), "pending": pending,
                           "corridorStoppedExtentM": round(corridor_extent, 3),
                           "arrived": len(arrived), "vehicles": visible_vehicles,
                           "knowledge": list(knowledge.values()), "warnings": warning_ids})
    metrics = {"scheduledVehicles": len(departures), "arrivedVehicles": len(arrived),
               "activeVehicles": len(positions), "pendingVehicles": pending, "futureVehicles": future,
               "totalDelayS": round(total_delay, 6), "spillbackDurationS": spillback_duration,
               "maxQueueExtentM": round(max_queue, 3), "advisoryCount": len(warnings),
               "maxCorridorStoppedExtentM": round(max_corridor_extent, 3),
               "maxSourceBacklog": max_source_backlog,
               "capActiveVehicleSeconds": cap_active_seconds,
               "capActivationsAboveLimit": sum(w["speedAtResponseMps"] is not None and
                                               w["speedAtResponseMps"] > config.response_speed for w in warnings),
               "deliveredMessages": mesh.delivered, "expiredMessages": mesh.expired,
               "droppedMessages": mesh.dropped, "duplicateMessages": mesh.duplicates,
               "maxPendingMessages": mesh.max_queue,
               "usefulWarnings": sum(t >= config.response_delay for t in lead_times),
               "meanWarningLeadS": round(statistics.mean(lead_times), 3) if lead_times else None,
               "meanKinematicLeadS": round(statistics.mean(w["kinematicLeadS"] for w in warnings), 3) if warnings else None,
               "stopsBeforeReportCrossing": sum(w["timeToFirstStopS"] is not None for w in warnings),
               "censoredWarnings": sum(w["leadS"] is None for w in warnings),
               "responsesApplied": sum(w["responded"] for w in warnings),
               "conservationErrors": conservation_errors, "teleports": teleports, "collisions": collisions}
    return {"mode": mode, "seed": seed, "metrics": metrics, "frames": frames, "warnings": warnings,
            "fingerprint": digest.hexdigest(), "simulator": version,
            "equipment": equipped, "responseAcceptance": accepts}


def summarize(runs):
    summary = {}
    for mode in MODES:
        selected = [r for r in runs if r["mode"] == mode]
        if not selected:
            continue
        summary[mode] = {}
        for key in selected[0]["metrics"]:
            values = [r["metrics"][key] for r in selected if r["metrics"][key] is not None]
            summary[mode][key] = {"mean": statistics.mean(values), "min": min(values), "max": max(values)} if values else None
    return summary


def export_viewer(output):
    """Package a file-openable replay; uploaded text cannot end the JSON script."""
    output = Path(output)
    template = HERE / "viewer.html"
    if not template.is_file():
        return
    source = template.read_text(encoding="utf-8")
    data = (output / "results.json").read_text(encoding="utf-8")
    json.loads(data)  # Refuse to package truncated or corrupt output.
    embedded = '<script id="junction-data" type="application/json">' + data.replace("<", "\\u003c") + '</script>\n'
    placeholder = '<script id="junction-data" type="application/json"></script>'
    marker = "</head>"
    if marker not in source:
        raise RuntimeError("Viewer template lacks a head element")
    report = source.replace(placeholder, embedded, 1) if placeholder in source else source.replace(marker, embedded + marker, 1)
    (output / "report.html").write_text(report, encoding="utf-8")
    shutil.copyfile(template, output / "viewer.html")
    shutil.copyfile(output / "results.json", output / "sample-results.json")


def experiment(config, seeds, java, jar, output, modes=MODES):
    if not Path(jar).is_file():
        raise RuntimeError(f"Build the shared policy first: gradlew :core:junctionPolicyJar (missing {jar})")
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    net, routes, departures = generate(output / "scenario", config)
    runs = []
    for seed in seeds:
        for mode in modes:
            print(f"Running {mode}, seed {seed}", flush=True)
            runs.append(run_one(mode, seed, config, java, jar, net, routes, departures, output / f"{seed}-{mode}"))
    result = {"schemaVersion": 1, "title": "RoadMesh Junction Lab", "assumptions": ASSUMPTIONS,
              "config": asdict(config), "seeds": list(seeds), "runs": runs, "summary": summarize(runs),
              "scenario": {"junctions": [{"id": "j1", "x": 200, "y": 0}, {"id": "j2", "x": 320, "y": 0}]}}
    (output / "results.json").write_text(json.dumps(result, separators=(",", ":"), allow_nan=False), encoding="utf-8")
    lines = ["mode,seed,spillbackDurationS,totalDelayS,advisoryCount,meanWarningLeadS,pendingVehicles,conservationErrors"]
    for run in runs:
        m = run["metrics"]
        lines.append(",".join(map(str, [run["mode"], run["seed"], m["spillbackDurationS"], m["totalDelayS"],
                                         m["advisoryCount"], m["meanWarningLeadS"], m["pendingVehicles"], m["conservationErrors"]])))
    (output / "summary.csv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    export_viewer(output)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", help="JDK 17+ java executable (otherwise JAVA_HOME/PATH)")
    parser.add_argument("--jar", type=Path, default=ROOT / "core/build/libs/core-junction-policy.jar")
    parser.add_argument("--output", type=Path, default=HERE / "output")
    parser.add_argument("--seeds", type=int, nargs="+", default=[7, 19, 42])
    config_arguments = ("duration", "penetration", "compliance", "packet_loss", "link_delay", "response_delay",
                        "departure_interval", "red_start", "red_end")
    for name in config_arguments:
        parser.add_argument("--" + name.replace("_", "-"), type=float, default=getattr(Config(), name))
    parser.add_argument("--vehicles", type=int, default=Config().vehicles)
    parser.add_argument("--no-relays", action="store_true")
    args = parser.parse_args()
    try:
        if len(args.seeds) > 20 or len(set(args.seeds)) != len(args.seeds) or any(s < 0 or s > 2**31 - 1 for s in args.seeds):
            raise ValueError("Use 1..20 distinct nonnegative 32-bit seeds")
        config = Config(**{k: getattr(args, k) for k in (*config_arguments, "vehicles")}, relay_enabled=not args.no_relays)
        result = experiment(config, args.seeds, java_executable(args.java), args.jar, args.output)
        for mode, metrics in result["summary"].items():
            print(f"{mode}: mean spillback={metrics['spillbackDurationS']['mean']:.2f}s, "
                  f"delay={metrics['totalDelayS']['mean']:.2f}s, warnings={metrics['advisoryCount']['mean']:.1f}")
        print(f"Results: {args.output.resolve() / 'results.json'}")
        if any(run["metrics"]["conservationErrors"] or run["metrics"]["teleports"] for run in result["runs"]):
            raise RuntimeError("Invalid traffic accounting; inspect the exported diagnostics")
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError, ImportError) as exc:
        parser.exit(1, f"Junction Lab failed: {exc}\n")


if __name__ == "__main__":
    main()
