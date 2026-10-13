import importlib.util
import os
import unittest

_SPEC = importlib.util.spec_from_file_location(
    "servex_agent", os.path.join(os.path.dirname(__file__), "servex-agent.py")
)
sa = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(sa)


class TestConfig(unittest.TestCase):
    def test_defaults(self):
        cfg = sa.load_config()
        self.assertEqual(cfg["port"], 8765)
        self.assertEqual(cfg["bind"], "0.0.0.0")
        self.assertEqual(cfg["sample_interval"], 2)
        self.assertEqual(cfg["history_size"], 150)
        self.assertEqual(cfg["actions"], [])
        self.assertEqual(cfg["disk_mounts"], ["/"])

    def test_overrides_win(self):
        cfg = sa.load_config(overrides={"port": 9000, "actions": ["system_info"]})
        self.assertEqual(cfg["port"], 9000)
        self.assertEqual(cfg["actions"], ["system_info"])


if __name__ == "__main__":
    unittest.main()
