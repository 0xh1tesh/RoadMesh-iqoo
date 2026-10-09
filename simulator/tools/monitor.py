"""Laptop-side monitor: listen to ResiLane UDP traffic on the shared Wi-Fi and show every node.

    python tools/monitor.py [--port 47800] [--csv out.csv]

Prints a live table of nodes (name, rate, shared-world position, heading, tracking, packet age
by the sender's clock) and checks the property the project rests on: every phone reports its pose
in the same shared frame, so positions printed here are directly comparable across phones.
Needs UDP enabled in the app (default) and the laptop on the same Wi-Fi/hotspot as the phones.
"""
import argparse
import math
import socket
import struct
import sys
import time

MAGIC = 0x4C52
TYPES = {1: "HELLO", 2: "POSE", 3: "SCALE_SYNC", 4: "PING", 5: "PONG", 6: "BYE"}
TRACK = {0: "TRACKING", 1: "PAUSED", 2: "STOPPED"}


def heading(yaw):
    return (math.degrees(math.atan2(math.sin(yaw), -math.cos(yaw))) + 360) % 360


def parse(b):
    try:
        return _parse(b)
    except (struct.error, IndexError, ValueError):
        return None


def _parse(b):
    if len(b) < 18:
        return None
    magic, ver, typ, ttl, _flags, origin, seq = struct.unpack_from("<HBBBBqi", b, 0)
    if magic != MAGIC or ver not in (1, 2) or typ not in TYPES:
        return None
    p = 18
    session = 0
    if ver == 2:
        session, = struct.unpack_from("<Q", b, p)
        p += 8
    m = {"type": TYPES[typ], "origin": origin & 0xFFFFFFFFFFFFFFFF, "seq": seq, "session": session}
    if typ == 1:
        n = b[p]; m["name"] = b[p + 1:p + 1 + n].decode("utf8", "replace"); p += 1 + n
        m["model"], m["color"], m["scale"], m["marker_mm"] = struct.unpack_from("<Biff", b, p)
    elif typ == 2:
        (m["pose_seq"], m["t_ms"], tr, cal, m["epoch"], x, y, z, qx, qy, qz, qw, yaw) = struct.unpack_from("<iqBBH8f", b, p)
        m.update(tracking=TRACK.get(tr, tr), calibrated=bool(cal), pos=(x, y, z), heading=heading(yaw))
    elif typ == 3:
        m["scale"], m["issued"] = struct.unpack_from("<fq", b, p)
    return m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=47800)
    ap.add_argument("--csv")
    a = ap.parse_args()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind(("", a.port))
    s.settimeout(0.5)
    out = open(a.csv, "w") if a.csv else None
    if out:
        out.write("recv_ms,node,name,seq,sender_ms,tracking,calibrated,x,y,z,heading\n")
    nodes = {}
    last_print = 0
    print(f"listening on udp/{a.port} ...")
    while True:
        try:
            b, addr = s.recvfrom(2048)
            m = parse(b)
            if m:
                n = nodes.setdefault(m["origin"], {"name": "?", "times": [], "ip": addr[0]})
                if m["type"] == "HELLO":
                    n["name"] = m["name"]; n["scale"] = m["scale"]
                elif m["type"] == "POSE":
                    now = time.time() * 1000
                    n.update(pose=m, recv=now)
                    n["times"] = [t for t in n["times"] if now - t < 2000] + [now]
                    if out:
                        x, y, z = m["pos"]
                        out.write(f"{now:.0f},{m['origin']:016x},{n['name']},{m['pose_seq']},{m['t_ms']},{m['tracking']},"
                                  f"{m['calibrated']},{x:.4f},{y:.4f},{z:.4f},{m['heading']:.1f}\n")
        except socket.timeout:
            pass
        if time.time() - last_print > 0.5:
            last_print = time.time()
            now = time.time() * 1000
            lines = [f"{'node':18} {'name':16} {'Hz':>5} {'x':>7} {'y':>6} {'z':>7} {'hdg':>5} {'state':9} {'cal':4} {'age':>6}"]
            for oid, n in sorted(nodes.items()):
                p = n.get("pose")
                if not p:
                    continue
                x, y, z = p["pos"]
                hz = len(n["times"]) / 2.0
                age = now - p["t_ms"]
                lines.append(f"{oid:016x}   {n['name'][:16]:16} {hz:5.1f} {x:+7.2f} {y:+6.2f} {z:+7.2f} {p['heading']:5.0f} "
                             f"{p['tracking']:9} {'yes' if p['calibrated'] else 'NO':4} {age:6.0f}")
            sys.stdout.write("\x1b[2J\x1b[H" + "\n".join(lines) + "\n(age = this PC's clock - sender clock, ms)\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
