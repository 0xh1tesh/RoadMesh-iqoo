"""Unit checks; set JUNCTION_SUMO_TESTS=1 and JUNCTION_JAVA for actual SUMO integration."""
from dataclasses import replace
import json
import os
from pathlib import Path
import tempfile
import unittest

from model import Config, LogicalMesh, Observation, PolicyBridge, draw, MODES
from run import ROOT, experiment, java_executable, export_viewer


class ModelTests(unittest.TestCase):
    def test_independent_random_streams(self):
        before = draw(7, "compliance", "v001")
        for i in range(1000):
            draw(7, "packet", "event", i)
        self.assertEqual(before, draw(7, "compliance", "v001"))
        self.assertNotEqual(before, draw(7, "equipment", "v001"))

    def test_invalid_config_rejected(self):
        for kwargs in ({"penetration": 1.1}, {"packet_loss": -1}, {"duration": float("nan")},
                       {"response_delay": -1}, {"vehicles": 0}, {"vehicles": 1.5},
                       {"max_pending": True}, {"link_delay": float("inf")}):
            with self.assertRaises(ValueError):
                Config(**kwargs)

    def test_standalone_export_escapes_untrusted_script_terminator(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            data = {"title": "</script><script>alert(1)</script>"}
            (output / "results.json").write_text(json.dumps(data), encoding="utf-8")
            export_viewer(output)
            html = (output / "report.html").read_text(encoding="utf-8")
            embedded = html.split('<script id="junction-data" type="application/json">', 1)[1].split('</script>', 1)[0]
            self.assertNotIn("<", embedded)
            self.assertEqual(data, json.loads(embedded))

    def test_temporal_relay_and_no_evidence_age_reset(self):
        mesh = LogicalMesh(Config(packet_loss=0, link_delay=0.5, radio_range=60), 7)
        nodes = {"a": (0, 0), "relay": (50, 0), "c": (100, 0)}
        event = Observation("e", "a", 0, 8, 120, 0)
        mesh.originate("a", event, 0, nodes)
        mesh.advance(0.5, nodes)
        self.assertEqual([], mesh.observations("c", 0.5))
        mesh.advance(1, nodes)
        self.assertEqual(event, mesh.observations("c", 1)[0])
        self.assertEqual(0, mesh.observations("c", 1)[0].captured)
        self.assertEqual(1, len(mesh.cache["c"]))

    def test_exclusive_expiry_and_late_links(self):
        mesh = LogicalMesh(Config(packet_loss=0, link_delay=8), 7)
        nodes = {"a": (0, 0), "b": (20, 0)}
        mesh.originate("a", Observation("e", "a", 0, 8, 30, 0), 0, nodes)
        mesh.advance(8, nodes)
        self.assertFalse(mesh.observations("b", 8))
        self.assertGreater(mesh.expired, 0)

    def test_disappearing_node_is_not_a_successful_delivery(self):
        mesh = LogicalMesh(Config(packet_loss=0), 7)
        mesh.originate("a", Observation("e", "a", 0, 8, 30, 0), 0, {"a": (0, 0), "b": (20, 0)})
        mesh.advance(0.5, {"a": (0, 0)})
        self.assertEqual(mesh.delivered, 0)
        self.assertGreater(mesh.dropped, 0)

    def test_partition_and_resource_bounds(self):
        nodes = {str(i): (i, 0) for i in range(20)}
        mesh = LogicalMesh(Config(packet_loss=0, max_pending=3, cache_per_node=2), 7)
        for i in range(20):
            mesh.originate("0", Observation(str(i), "0", 0, 8, 40, 0), 0, nodes)
        self.assertLessEqual(len(mesh.pending), 3)
        self.assertLessEqual(len(mesh.cache["0"]), 2)
        self.assertGreater(mesh.dropped, 0)
        partition = LogicalMesh(Config(packet_loss=1), 7)
        partition.originate("0", Observation("e", "0", 0, 8, 40, 0), 0, nodes)
        partition.advance(1, nodes)
        self.assertEqual(partition.delivered, 0)


@unittest.skipUnless(os.environ.get("JUNCTION_SUMO_TESTS") == "1", "opt-in SUMO/JDK integration")
class IntegrationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.java = java_executable(os.environ.get("JUNCTION_JAVA"))
        cls.jar = ROOT / "core/build/libs/core-junction-policy.jar"
        if not cls.jar.is_file():
            raise RuntimeError("Build :core:junctionPolicyJar before requesting integration tests")

    def run_scenario(self, config):
        with tempfile.TemporaryDirectory(prefix="roadmesh-junction-") as directory:
            return experiment(config, [7], self.java, self.jar, Path(directory))

    def assert_conserved(self, result):
        for run in result["runs"]:
            m = run["metrics"]
            self.assertEqual(0, m["conservationErrors"])
            self.assertEqual(0, m["teleports"])
            self.assertEqual(m["scheduledVehicles"], m["arrivedVehicles"] + m["activeVehicles"] + m["pendingVehicles"] + m["futureVehicles"])
            last = {}
            for warning in run["warnings"]:
                self.assertLess(warning["time"], warning["expiresAt"])
                self.assertLessEqual(warning["capturedAt"], warning["time"])
                actor = warning["recipientId"]
                if actor in last:
                    self.assertGreaterEqual(warning["time"] - last[actor], 12)
                last[actor] = warning["time"]
                if warning["responded"]:
                    self.assertGreaterEqual(warning["responseAppliedAt"], warning["time"] + result["config"]["response_delay"])
                else:
                    self.assertIsNone(warning["responseAppliedAt"])
            # Every issued warning appears in its next sampled frame.
            previous = -1
            for frame in run["frames"]:
                expected = {w["recipientId"] for w in run["warnings"] if previous < w["time"] <= frame["time"]}
                self.assertEqual(expected, set(frame["warnings"]))
                previous = frame["time"]

    def test_real_jar_protocol_and_scope(self):
        with PolicyBridge(self.java, self.jar) as policy:
            event = Observation("e", "source", 1, 9, 100, 0)
            self.assertFalse(policy.evaluate("local_only", "r", 2, event, "mesh", 50, 10))
            self.assertTrue(policy.evaluate("mesh_informed", "r", 2, event, "mesh", 50, 10))
            self.assertFalse(policy.evaluate("mesh_informed", "r", 2, event, "mesh", 50, 10))
            self.assertEqual(["ERROR", "invalid_input"], policy.command("BAD"))
            self.assertEqual(["OK"], policy.command("RESET"))
            self.assertFalse(policy.evaluate("mesh_informed", "r", 9, event, "mesh", 50, 10))

    def test_zero_compliance_preserves_traffic_and_still_delivers_warnings(self):
        result = self.run_scenario(Config(duration=120, vehicles=60, penetration=1, compliance=0))
        self.assert_conserved(result)
        self.assertEqual(1, len({r["fingerprint"] for r in result["runs"]}))
        self.assertGreater(result["runs"][2]["metrics"]["deliveredMessages"], 0)
        self.assertGreater(result["runs"][2]["metrics"]["advisoryCount"], 0)
        self.assertTrue(all(r["metrics"]["responsesApplied"] == 0 for r in result["runs"]))

    def test_zero_penetration_preserves_traffic(self):
        result = self.run_scenario(Config(duration=100, vehicles=45, penetration=0))
        self.assert_conserved(result)
        self.assertEqual(1, len({r["fingerprint"] for r in result["runs"]}))
        self.assertTrue(all(r["metrics"]["advisoryCount"] == 0 for r in result["runs"]))

    def test_no_delivery_matches_local_and_retains_backlog(self):
        result = self.run_scenario(Config(duration=100, vehicles=72, penetration=1, packet_loss=1))
        self.assert_conserved(result)
        self.assertEqual(result["runs"][1]["fingerprint"], result["runs"][2]["fingerprint"])
        self.assertEqual(0, result["runs"][2]["metrics"]["deliveredMessages"])
        self.assertGreater(result["runs"][2]["metrics"]["pendingVehicles"], 0)
        self.assertTrue(all(w["channel"] == "local" for w in result["runs"][2]["warnings"]))
        for run in result["runs"]:
            m = run["metrics"]
            self.assertGreater(m["maxCorridorStoppedExtentM"], m["maxQueueExtentM"])
            self.assertGreater(m["maxSourceBacklog"], 0)
            for w in run["warnings"]:
                self.assertGreater(w["kinematicLeadS"], 0)
                if w["timeToFirstStopS"] is not None:
                    self.assertGreaterEqual(w["timeToFirstStopS"], 0)

    def test_replay_is_deterministic_and_responses_are_not_forced(self):
        config = Config(duration=100, vehicles=45, penetration=1, compliance=1)
        first = self.run_scenario(config)
        second = self.run_scenario(config)
        self.assert_conserved(first)
        self.assertEqual([r["fingerprint"] for r in first["runs"]], [r["fingerprint"] for r in second["runs"]])
        self.assertEqual([r["metrics"] for r in first["runs"]], [r["metrics"] for r in second["runs"]])
        self.assertGreater(first["runs"][2]["metrics"]["responsesApplied"], 0)
        self.assertNotEqual(first["runs"][0]["fingerprint"], first["runs"][2]["fingerprint"])


if __name__ == "__main__":
    unittest.main()
