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


class TestParsers(unittest.TestCase):
    PROC_STAT = (
        "cpu  100 2 50 1000 10 0 5 0 0 0\n"
        "cpu0 50 1 25 500 5 0 2 0 0 0\n"
        "intr 12345\n"
    )

    def test_parse_proc_stat(self):
        r = sa.parse_proc_stat(self.PROC_STAT)
        self.assertEqual(r["total"], 100 + 2 + 50 + 1000 + 10 + 5)
        self.assertEqual(r["idle"], 1000 + 10)

    def test_parse_proc_stat_missing_cpu_raises(self):
        with self.assertRaises(ValueError):
            sa.parse_proc_stat("intr 1\n")

    MEM = (
        "MemTotal:       16000000 kB\n"
        "MemFree:         2000000 kB\n"
        "MemAvailable:    8000000 kB\n"
        "Buffers:          100000 kB\n"
    )

    def test_parse_meminfo(self):
        r = sa.parse_meminfo(self.MEM)
        self.assertEqual(r["total"], 16000000 * 1024)
        self.assertEqual(r["available"], 8000000 * 1024)
        self.assertEqual(r["used"], (16000000 - 8000000) * 1024)
        self.assertEqual(r["pct"], 50.0)

    NET = (
        "Inter-|   Receive                                                |  Transmit\n"
        " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
        "    lo:    1000       10    0    0    0     0          0         0     1000       10    0    0    0     0       0          0\n"
        "  eth0:  5000000     4000    0    0    0     0          0         0  2000000     3000    0    0    0     0       0          0\n"
    )

    def test_parse_net_dev_excludes_lo(self):
        r = sa.parse_net_dev(self.NET)
        self.assertNotIn("lo", r)
        self.assertEqual(r["eth0"]["rx_total"], 5000000)
        self.assertEqual(r["eth0"]["tx_total"], 2000000)

    UPTIME = "382100.55 1234567.00\n"

    def test_parse_proc_uptime(self):
        self.assertEqual(sa.parse_proc_uptime(self.UPTIME), 382100)


class TestDerived(unittest.TestCase):
    def test_cpu_usage(self):
        prev = {"idle": 1000, "total": 2000}
        cur = {"idle": 1500, "total": 3000}   # 500 idle of 1000 total -> 50%
        self.assertEqual(sa.cpu_usage(prev, cur), 50.0)

    def test_cpu_usage_zero_delta(self):
        s = {"idle": 10, "total": 20}
        self.assertEqual(sa.cpu_usage(s, s), 0.0)

    def test_net_rates_clamps_on_reset(self):
        prev = {"eth0": {"rx_total": 5000, "tx_total": 5000}}
        cur = {"eth0": {"rx_total": 100, "tx_total": 200}}  # counter wrapped
        r = sa.net_rates(prev, cur, 1.0)
        self.assertEqual(r["eth0"]["rx_bps"], 0)
        self.assertEqual(r["eth0"]["tx_bps"], 0)

    def test_net_rates_new_iface_is_zero(self):
        r = sa.net_rates({}, {"eth0": {"rx_total": 900, "tx_total": 900}}, 2.0)
        self.assertEqual(r["eth0"]["rx_bps"], 0)

    def test_net_rates_normal(self):
        prev = {"eth0": {"rx_total": 0, "tx_total": 0}}
        cur = {"eth0": {"rx_total": 2048, "tx_total": 1024}}
        r = sa.net_rates(prev, cur, 2.0)
        self.assertEqual(r["eth0"]["rx_bps"], 1024)
        self.assertEqual(r["eth0"]["tx_bps"], 512)

    def test_disk_usage(self):
        class St:
            f_frsize = 4096
            f_blocks = 1000
            f_bavail = 600
        r = sa.disk_usage(St())
        self.assertEqual(r["total"], 4096 * 1000)
        self.assertEqual(r["used"], 4096 * 400)
        self.assertEqual(r["pct"], 40.0)

    def test_read_os_pretty(self):
        text = 'NAME="Ubuntu"\nPRETTY_NAME="Ubuntu 24.04 LTS"\n'
        self.assertEqual(sa.read_os_pretty(text), "Ubuntu 24.04 LTS")

    def test_read_os_pretty_missing(self):
        self.assertEqual(sa.read_os_pretty("NAME=x\n"), "Linux")


if __name__ == "__main__":
    unittest.main()
