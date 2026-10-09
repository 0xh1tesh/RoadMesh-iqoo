import struct
import unittest
from monitor import MAGIC, parse


class MonitorProtocolTest(unittest.TestCase):
    def test_both_scene_headers(self):
        for version, session in ((1, 0), (2, 0x1020304050607080)):
            header = struct.pack("<HBBBBqi", MAGIC, version, 2, 1, 0, 7, 5)
            if version == 2:
                header += struct.pack("<Q", session)
            pose = struct.pack("<iqBBH8f", 9, 123, 0, 1, 1, 1, 2, 3, 0, 0, 0, 1, 0)
            result = parse(header + pose)
            self.assertEqual(session, result["session"])
            self.assertEqual((1.0, 2.0, 3.0), result["pos"])

    def test_truncated_or_unknown_frames_do_not_crash_monitor(self):
        self.assertIsNone(parse(b"RL"))
        self.assertIsNone(parse(struct.pack("<HBBBBqi", MAGIC, 2, 2, 1, 0, 7, 5)))
        self.assertIsNone(parse(struct.pack("<HBBBBqi", MAGIC, 99, 2, 1, 0, 7, 5)))


if __name__ == "__main__":
    unittest.main()
