"""Validate an observed A-B-C chain from three RoadMesh exports; never infer latency or PDR.

    python tools/verify_demo.py detector.json relay.json observer.json
"""
import argparse
import json
from pathlib import Path


def key(frame):
    return frame["origin"], frame["session"], frame["sequence"]


def frames(doc, code):
    return [event["frame"] for event in doc.get("events", []) if event["code"] == code and "frame" in event]


def verify(documents):
    by_role = {doc["role"]: doc for doc in documents}
    if set(by_role) != {"DETECTOR", "RELAY", "OBSERVER"} or len(documents) != 3:
        raise ValueError("Supply exactly one export from each of Detector, Relay and Observer")
    if any(doc["profile"]["mode"] != "STAGE" or doc["profile"]["initialTtl"] != 1 for doc in documents):
        raise ValueError("This verifier expects the disclosed Stage profile with initialTtl=1")
    origins = {key(f) for f in frames(by_role["DETECTOR"], "tx_complete") if f["origin"] == "1" and f["provenance"] == "DEMO"}
    relay_receipts = {key(f) for f in frames(by_role["RELAY"], "received") if f["forwarder"] == "1" and f["relayCount"] == 0 and f["ttl"] == 1 and f["radioLinks"] == 1}
    relays = {key(f) for f in frames(by_role["RELAY"], "tx_complete") if f["forwarder"] == "2" and f["relayCount"] == 1 and f["ttl"] == 0}
    received = frames(by_role["OBSERVER"], "received")
    cautions = frames(by_role["OBSERVER"], "caution")
    matched = []
    for frame in received:
        event = key(frame)
        if event in origins & relay_receipts & relays and frame["forwarder"] == "2" and frame["ttl"] == 0 and frame["relayCount"] == 1 and frame["radioLinks"] == 2:
            if sum(key(f) == event for f in received) != 1 or sum(key(f) == event for f in cautions) != 1:
                raise ValueError(f"Expected one accepted reception and one caution on C for {event}")
            matched.append(":".join(event))
    if not matched:
        raise ValueError("No complete chain found in retained logs; export all phones after the same brake event")
    return {"observed_event_keys": matched, "radio_links": 2, "completed_relays": 1,
            "disclosure": "Matched local sender/relay completions with C's reception and caution. No automatic return receipt, speech playback proof, latency or PDR."}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("exports", nargs=3, type=Path)
    args = parser.parse_args()
    try:
        docs = [json.loads(path.read_text(encoding="utf-8-sig")) for path in args.exports]
        print(json.dumps(verify(docs), indent=2))
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, f"Verification failed: {error}\n")


if __name__ == "__main__":
    main()
