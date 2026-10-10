"""Explicit, replaceable experiment assumptions. This is not a BLE simulator."""
from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import heapq
import math
from queue import Queue, Empty
import subprocess
from threading import Thread

MODES = ("no_advice", "local_only", "mesh_informed")
SCOPE = "eastbound-j1-j2"


def draw(seed: int, namespace: str, *keys: object) -> float:
    """Counter-based randomness: extra messages never consume driver RNG draws."""
    payload = repr((seed, namespace, keys)).encode("utf-8")
    return (int.from_bytes(hashlib.sha256(payload).digest()[:8], "big") >> 11) / 2**53


@dataclass(frozen=True)
class Config:
    duration: float = 180.0
    step: float = 0.5
    vehicles: int = 72
    departure_interval: float = 1.4
    penetration: float = 0.6
    compliance: float = 0.6
    local_range: float = 35.0
    radio_range: float = 85.0
    link_delay: float = 0.5
    packet_loss: float = 0.1
    event_lifetime: float = 8.0
    response_delay: float = 1.5
    response_duration: float = 8.0
    response_speed: float = 8.0
    free_speed: float = 13.9
    red_start: float = 35.0
    red_end: float = 95.0
    relay_enabled: bool = True
    max_hops: int = 3
    max_pending: int = 4096
    cache_per_node: int = 128

    def __post_init__(self):
        for key in ("vehicles", "max_hops", "max_pending", "cache_per_node"):
            if type(getattr(self, key)) is not int:
                raise ValueError(f"{key} must be an integer")
        for key, value in asdict(self).items():
            if isinstance(value, (int, float)) and not math.isfinite(value):
                raise ValueError(f"{key} must be finite")
        for key in ("penetration", "compliance", "packet_loss"):
            if not 0 <= getattr(self, key) <= 1:
                raise ValueError(f"{key} must be in [0, 1]")
        if self.step != 0.5:
            raise ValueError("This version uses a fixed 0.5 s step")
        if not 10 <= self.duration <= 1800 or self.duration % self.step:
            raise ValueError("duration must be 10..1800 seconds on the step grid")
        if not 1 <= self.vehicles <= 200 or self.departure_interval <= 0:
            raise ValueError("vehicles must be 1..200 and departure interval positive")
        for key in ("local_range", "radio_range", "event_lifetime", "response_duration", "response_speed", "free_speed"):
            if getattr(self, key) <= 0:
                raise ValueError(f"{key} must be positive")
        if self.response_speed > self.free_speed or self.response_delay < 0 or self.link_delay < 0:
            raise ValueError("invalid response or link timing")
        if not 0 <= self.red_start < self.red_end:
            raise ValueError("red interval must be increasing and nonnegative")
        if self.event_lifetime > 10 or self.max_hops < 1 or self.max_pending < 1 or self.cache_per_node < 1:
            raise ValueError("invalid lifetime or resource bounds")


@dataclass(frozen=True)
class Observation:
    event_id: str
    source_id: str
    captured: float
    expires: float
    x: float
    y: float
    scope: str = SCOPE


class LogicalMesh:
    """Directed contacts, bounded caches/queue, immutable evidence age and hop limit.

    One attempt per reached sender/event/neighbor, no MAC, advertising, byte-size,
    collision or hardware-energy model. Loss and latency are sensitivity inputs.
    """
    def __init__(self, config: Config, seed: int):
        self.config, self.seed = config, seed
        self.cache: dict[str, dict[str, tuple[Observation, int]]] = {}
        self.pending: list[tuple[float, int, str, str, Observation, int]] = []
        self.sequence = 0
        self.delivered = self.expired = self.dropped = self.duplicates = 0
        self.max_queue = 0

    def _forward(self, sender, event, hops, now, nodes):
        if hops >= self.config.max_hops or sender not in nodes:
            return
        for receiver in sorted(nodes):
            if receiver == sender:
                continue
            if math.dist(nodes[sender], nodes[receiver]) > self.config.radio_range:
                continue
            if draw(self.seed, "packet", event.event_id, sender, receiver) < self.config.packet_loss:
                self.dropped += 1
                continue
            if len(self.pending) >= self.config.max_pending:
                self.dropped += 1
                continue
            due = now + self.config.link_delay
            if due >= event.expires:
                self.expired += 1
                continue
            self.sequence += 1
            heapq.heappush(self.pending, (due, self.sequence, sender, receiver, event, hops + 1))
        self.max_queue = max(self.max_queue, len(self.pending))

    def _admit(self, node, event, hops):
        cache = self.cache.setdefault(node, {})
        if event.event_id in cache:
            self.duplicates += 1
            return False
        if len(cache) >= self.config.cache_per_node:
            self.dropped += 1
            return False
        cache[event.event_id] = (event, hops)
        return True

    def originate(self, node, event, now, nodes):
        if now < event.captured or now >= event.expires or node not in nodes:
            self.expired += 1
            return
        if self._admit(node, event, 0):
            self._forward(node, event, 0, now, nodes)

    def advance(self, now, nodes):
        for node in list(self.cache):
            if node not in nodes:
                del self.cache[node]
            else:
                self.cache[node] = {k: v for k, v in self.cache[node].items() if v[0].expires > now}
        while self.pending and self.pending[0][0] <= now:
            _, _, sender, receiver, event, hops = heapq.heappop(self.pending)
            if now >= event.expires:
                self.expired += 1
                continue
            if sender not in nodes or receiver not in nodes or math.dist(nodes[sender], nodes[receiver]) > self.config.radio_range:
                self.dropped += 1
                continue
            if self._admit(receiver, event, hops):
                self.delivered += 1
                self._forward(receiver, event, hops, now, nodes)

    def observations(self, recipient, now):
        return [v[0] for v in self.cache.get(recipient, {}).values()
                if v[0].source_id != recipient and v[0].captured <= now < v[0].expires]


class PolicyBridge:
    """Persistent JVM adapter: the experiment executes the real shared Kotlin policy."""
    def __init__(self, java, jar):
        self.process = subprocess.Popen([str(java), "-jar", str(jar)], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=None, text=True,
                                        encoding="utf-8", bufsize=1)
        self.lines = Queue()
        self.reader = Thread(target=self._read, daemon=True)
        self.reader.start()
        try:
            if self.command("RESET") != ["OK"]:
                raise RuntimeError("policy RESET failed")
        except BaseException:
            self.close()
            raise

    def _read(self):
        for line in self.process.stdout:
            self.lines.put(line.rstrip("\r\n"))
        self.lines.put(None)

    def command(self, line):
        if self.process.poll() is not None:
            raise RuntimeError("Kotlin policy process exited")
        self.process.stdin.write(line + "\n")
        self.process.stdin.flush()
        try:
            reply = self.lines.get(timeout=15)
        except Empty as exc:
            raise RuntimeError("Kotlin policy response timed out") from exc
        if reply is None:
            raise RuntimeError("Kotlin policy closed stdout")
        return reply.split("\t")

    def evaluate(self, mode, recipient, now, event, channel, distance, speed, equipped=True):
        fields = ["EVAL", mode, recipient, round(now * 1000), str(equipped).lower(), SCOPE,
                  distance, speed, event.event_id, round(event.captured * 1000),
                  round(event.expires * 1000), event.scope, event.source_id, channel]
        if any(any(ch in str(v) for ch in "\r\n\t") for v in fields):
            raise ValueError("invalid TSV field")
        result = self.command("\t".join(map(str, fields)))
        if not result or result[0] not in ("WARN", "IGNORE"):
            raise RuntimeError(f"policy rejected experiment input: {result}")
        return result[0] == "WARN"

    def close(self):
        if self.process.poll() is None:
            try:
                self.command("QUIT")
                self.process.wait(timeout=5)
            except (OSError, RuntimeError, subprocess.TimeoutExpired):
                self.process.kill()
                self.process.wait(timeout=5)
        if self.process.stdin:
            self.process.stdin.close()
        if self.process.stdout:
            self.process.stdout.close()

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
