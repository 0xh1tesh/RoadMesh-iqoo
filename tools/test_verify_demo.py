import unittest
from verify_demo import verify


class DemoEvidenceTest(unittest.TestCase):
    def documents(self):
        frame = dict(origin="1", session="ab", sequence="1", forwarder="1", relayCount=0, ttl=1, radioLinks=1, provenance="DEMO")
        relay = dict(frame, forwarder="2", relayCount=1, ttl=0, radioLinks=2)
        def doc(role, events):
            return dict(role=role, profile=dict(mode="STAGE", initialTtl=1), events=[dict(code=code, frame=f) for code, f in events])
        return [doc("DETECTOR", [("tx_complete", frame)]), doc("RELAY", [("received", frame), ("tx_complete", relay)]),
                doc("OBSERVER", [("received", relay), ("caution", relay)])]

    def test_matches_shared_identity(self):
        self.assertEqual(["1:ab:1"], verify(self.documents())["observed_event_keys"])

    def test_different_events_do_not_prove_a_chain(self):
        docs = self.documents()
        docs[2]["events"] = [dict(code="received", frame=dict(docs[2]["events"][0]["frame"], session="other"))]
        with self.assertRaises(ValueError):
            verify(docs)

    def test_direct_delivery_and_repeated_caution_fail(self):
        docs = self.documents()
        docs[2]["events"][0]["frame"] = dict(docs[2]["events"][0]["frame"], forwarder="1", radioLinks=1)
        with self.assertRaises(ValueError):
            verify(docs)
        docs = self.documents()
        docs[2]["events"].append(docs[2]["events"][1])
        with self.assertRaises(ValueError):
            verify(docs)


if __name__ == "__main__":
    unittest.main()
