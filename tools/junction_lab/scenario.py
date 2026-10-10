"""Versioned synthetic network and exogenous demand, shared by all three modes."""
from pathlib import Path
import os
import shutil
import subprocess
import xml.etree.ElementTree as ET


def sumo_binary(name):
    # Use the installed pinned Python distribution before an unrelated system SUMO.
    try:
        import sumo
        binary = Path(sumo.__file__).parent / "bin" / (name + (".exe" if os.name == "nt" else ""))
        if binary.is_file():
            return str(binary)
    except ImportError:
        pass
    found = shutil.which(name)
    if found:
        return found
    raise RuntimeError(f"Missing {name}. Install tools/junction_lab/requirements.txt in a virtual environment.")


def write_xml(path, root):
    ET.indent(root)
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def generate(directory: Path, config):
    directory.mkdir(parents=True, exist_ok=True)
    nodes = ET.Element("nodes")
    for name, x, y, kind in [("west", 0, 0, "priority"), ("j1", 200, 0, "traffic_light"),
                             ("j2", 320, 0, "traffic_light"), ("east", 540, 0, "priority"),
                             ("south1", 200, -80, "priority"), ("north1", 200, 80, "priority"),
                             ("south2", 320, -80, "priority"), ("north2", 320, 80, "priority")]:
        ET.SubElement(nodes, "node", id=name, x=str(x), y=str(y), type=kind)
    edges = ET.Element("edges")
    for name, origin, destination in [("approach", "west", "j1"), ("connector", "j1", "j2"),
                                       ("exit", "j2", "east"), ("s1", "south1", "j1"),
                                       ("n1", "j1", "north1"), ("s2", "south2", "j2"),
                                       ("n2", "j2", "north2")]:
        ET.SubElement(edges, "edge", id=name, **{"from": origin, "to": destination},
                      numLanes="1", speed=str(config.free_speed), priority="2")
    connections = ET.Element("connections")
    for origin, destination in [("approach", "connector"), ("connector", "exit"), ("s1", "n1"), ("s2", "n2")]:
        ET.SubElement(connections, "connection", **{"from": origin, "to": destination}, fromLane="0", toLane="0")
    for name, element in [("nodes.nod.xml", nodes), ("edges.edg.xml", edges), ("connections.con.xml", connections)]:
        write_xml(directory / name, element)
    net = directory / "junction.net.xml"
    subprocess.run([sumo_binary("netconvert"), "--node-files", str(directory / "nodes.nod.xml"),
                    "--edge-files", str(directory / "edges.edg.xml"), "--connection-files", str(directory / "connections.con.xml"),
                    "--offset.disable-normalization", "true", "--output-file", str(net)],
                   check=True, capture_output=True, text=True)
    routes = ET.Element("routes")
    ET.SubElement(routes, "vType", id="car", length="5", minGap="2.5", accel="2.6", decel="4.5",
                  sigma="0", tau="1", maxSpeed=str(config.free_speed), speedFactor="1", speedDev="0")
    ET.SubElement(routes, "route", id="eastbound", edges="approach connector exit")
    departures = {}
    for index in range(config.vehicles):
        vehicle = f"v{index:03d}"
        departure = round(index * config.departure_interval, 6)
        departures[vehicle] = departure
        ET.SubElement(routes, "vehicle", id=vehicle, type="car", route="eastbound",
                      depart=str(departure), departSpeed="max", departLane="0")
    route_file = directory / "demand.rou.xml"
    write_xml(route_file, routes)
    return net, route_file, departures
