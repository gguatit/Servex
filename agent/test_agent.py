import importlib.util
import io
import json
import os
import shutil
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request

_SPEC = importlib.util.spec_from_file_location(
    "servex_agent", os.path.join(os.path.dirname(__file__), "servex-agent.py")
)
assert _SPEC is not None and _SPEC.loader is not None
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


class TestExtendedParsers(unittest.TestCase):
    CORES = (
        "cpu  100 2 50 1000 10 0 5 0 0 0\n"
        "cpu0 50 1 25 500 5 0 2 0 0 0\n"
        "cpu1 50 1 25 500 5 0 3 0 0 0\n"
        "intr 123\n"
    )

    def test_parse_proc_stat_cores(self):
        cores = sa.parse_proc_stat_cores(self.CORES)
        self.assertEqual(len(cores), 2)
        self.assertEqual(cores[0]["total"], 50 + 1 + 25 + 500 + 5 + 2)
        self.assertEqual(cores[0]["idle"], 500 + 5)
        self.assertEqual(cores[1]["total"], 50 + 1 + 25 + 500 + 5 + 3)

    def test_parse_proc_stat_cores_empty(self):
        self.assertEqual(sa.parse_proc_stat_cores("intr 1\n"), [])

    MEM = (
        "MemTotal:       16000000 kB\n"
        "MemFree:         2000000 kB\n"
        "MemAvailable:    8000000 kB\n"
        "Buffers:          100000 kB\n"
        "Cached:          4000000 kB\n"
        "SwapTotal:       2000000 kB\n"
        "SwapFree:         500000 kB\n"
    )

    def test_parse_meminfo_extras(self):
        r = sa.parse_meminfo_extras(self.MEM)
        self.assertEqual(r["swap_total"], 2000000 * 1024)
        self.assertEqual(r["swap_used"], (2000000 - 500000) * 1024)
        self.assertEqual(r["swap_pct"], 75.0)
        self.assertEqual(r["cached"], 4000000 * 1024)
        self.assertEqual(r["buffers"], 100000 * 1024)

    DST = (
        "   8       0 sda 100 0 2000 0 50 0 1000 0 0 0 0\n"
        "   8       1 sda1 60 0 1200 0 30 0 600 0 0 0 0\n"
    )

    def test_parse_diskstats(self):
        r = sa.parse_diskstats(self.DST)
        self.assertEqual(r["read_total"], (2000 + 1200) * 512)
        self.assertEqual(r["write_total"], (1000 + 600) * 512)

    NETX = (
        "Inter-|   Receive                                                |  Transmit\n"
        " face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
        "    lo:    1000       10    0    0    0     0          0         0     1000       10    0    0    0     0       0          0\n"
        "  eth0:  5000000     4000    3    7    0     0          0         0  2000000     3000    9   11    0     0       0          0\n"
    )

    def test_parse_net_dev_extras(self):
        r = sa.parse_net_dev_extras(self.NETX)
        self.assertNotIn("lo", r)
        self.assertEqual(r["eth0"]["rx_errs"], 3)
        self.assertEqual(r["eth0"]["rx_drops"], 7)
        self.assertEqual(r["eth0"]["tx_errs"], 9)
        self.assertEqual(r["eth0"]["tx_drops"], 11)


class TestConnectionInfo(unittest.TestCase):
    def test_lan_addresses_has_loopback_first(self):
        urls = sa._lan_addresses(1234)
        self.assertEqual(urls[0], "http://127.0.0.1:1234")
        self.assertIn("http://127.0.0.1:1234", urls)

    def test_init_config_generates_token_and_no_overwrite(self):
        d = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, d, True)
        path = os.path.join(d, "servex-agent.json")
        cfg = sa.init_config(path, port=9999)
        self.assertTrue(cfg["token"])
        self.assertEqual(cfg["port"], 9999)
        with open(path, "r", encoding="utf-8") as fh:
            saved = json.load(fh)
        self.assertEqual(saved["token"], cfg["token"])
        again = sa.init_config(path, port=1111)
        self.assertEqual(again["port"], 9999)
        self.assertEqual(again["token"], cfg["token"])

    def test_print_connection_info_smoke(self):
        buf = io.StringIO()
        old = sa.sys.stderr
        sa.sys.stderr = buf
        try:
            sa.print_connection_info({"bind": "0.0.0.0", "port": 8765, "token": "xyz"},
                                     show_token=True)
        finally:
            sa.sys.stderr = old
        out = buf.getvalue()
        self.assertIn("servex-agent listening on 0.0.0.0:8765", out)
        self.assertIn("http://127.0.0.1:8765", out)
        self.assertIn("Token xyz", out)


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


class TestRingSamplerHistory(unittest.TestCase):
    SNAPS = [
        {"ts": 1, "cpu": {"usage_pct": 10.0}, "memory": {"pct": 20.0},
         "disks": [{"pct": 30.0}], "network": [{"rx_bps": 100, "tx_bps": 50}]},
        {"ts": 2, "cpu": {"usage_pct": 15.5}, "memory": {"pct": 25.0},
         "disks": [{"pct": 31.0}], "network": [{"rx_bps": 200, "tx_bps": 60}]},
    ]

    def test_ring_is_bounded(self):
        r = sa.Ring(3)
        for i in range(5):
            r.append(i)
        self.assertEqual(r.snapshot(), [2, 3, 4])
        self.assertEqual(len(r), 3)

    def test_history_extracts_cpu(self):
        self.assertEqual(sa.history_points(self.SNAPS, "cpu", 60),
                         [{"t": 1, "v": 10.0}, {"t": 2, "v": 15.5}])

    def test_history_network_is_aggregate_rx(self):
        snaps = [{"ts": 5, "network": [{"rx_bps": 100, "tx_bps": 50},
                                       {"rx_bps": 50, "tx_bps": 10}]}]
        self.assertEqual(sa.history_points(snaps, "network", 60), [{"t": 5, "v": 150.0}])

    def test_history_disk_is_max_pct(self):
        snaps = [{"ts": 7, "disks": [{"pct": 30.0}, {"pct": 80.0}]}]
        self.assertEqual(sa.history_points(snaps, "disk", 60), [{"t": 7, "v": 80.0}])

    def test_history_points_clamped(self):
        snaps = [{"ts": i, "cpu": {"usage_pct": float(i)}} for i in range(10)]
        self.assertEqual(len(sa.history_points(snaps, "cpu", 3)), 3)

    def test_history_unknown_metric_is_empty(self):
        self.assertEqual(sa.history_points(self.SNAPS, "bogus", 60), [])


def _boot(token="secret", **cfg):
    config = sa.load_config(overrides={"token": token, "port": 0, **cfg})
    ring = sa.Ring(config["history_size"])
    sampler = sa.Sampler(ring, sa.LocalFS(), config["sample_interval"], config["disk_mounts"])
    snap = {"ts": 1, "cpu": {"usage_pct": 10.0}, "memory": {"pct": 20.0},
            "disks": [{"pct": 30.0}], "network": [{"rx_bps": 1, "tx_bps": 1}],
            "host": "h", "os": "Linux", "uptime_s": 5}
    ring.append(snap)
    sampler.latest = snap
    httpd = sa.make_server(config, ring, sampler)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    time.sleep(0.05)
    return httpd, httpd.server_address[1]


def _get(port, path, token: str | None = "secret"):
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (port, path))
    if token is not None:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=2) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())


class TestHttp(unittest.TestCase):
    def test_token_ok_constant_time(self):
        self.assertTrue(sa.token_ok("abc", "abc"))
        self.assertFalse(sa.token_ok("abc", "abd"))
        self.assertFalse(sa.token_ok("", ""))

    def test_health_requires_token(self):
        httpd, port = _boot()
        self.addCleanup(httpd.server_close); self.addCleanup(httpd.shutdown)
        code, body = _get(port, "/api/v1/health", token=None)
        self.assertEqual(code, 401)
        self.assertIn("error", body)

    def test_bad_token_401(self):
        httpd, port = _boot()
        self.addCleanup(httpd.server_close); self.addCleanup(httpd.shutdown)
        self.assertEqual(_get(port, "/api/v1/metrics", token="wrong")[0], 401)

    def test_metrics_ok(self):
        httpd, port = _boot()
        self.addCleanup(httpd.server_close); self.addCleanup(httpd.shutdown)
        code, body = _get(port, "/api/v1/metrics")
        self.assertEqual(code, 200)
        self.assertIn("cpu", body)
        self.assertEqual(body["cpu"]["usage_pct"], 10.0)

    def test_history_metric_param(self):
        httpd, port = _boot()
        self.addCleanup(httpd.server_close); self.addCleanup(httpd.shutdown)
        code, body = _get(port, "/api/v1/history?metric=cpu&points=10")
        self.assertEqual(code, 200)
        self.assertEqual(body["metric"], "cpu")
        self.assertEqual(body["points"][0]["v"], 10.0)

    def test_unknown_route_404(self):
        httpd, port = _boot()
        self.addCleanup(httpd.server_close); self.addCleanup(httpd.shutdown)
        self.assertEqual(_get(port, "/api/v1/nope")[0], 404)


def _post(port, path, payload, token="secret", raw=None):
    data = raw if raw is not None else json.dumps(payload).encode()
    req = urllib.request.Request("http://127.0.0.1:%d%s" % (port, path), data=data, method="POST")
    req.add_header("Authorization", "Bearer " + token)
    req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=2) as resp:
            return resp.status, json.loads(resp.read())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read())
    except (urllib.error.URLError, ConnectionError, OSError):
        # The server may close the socket while the client is still uploading an
        # oversized body (common on Windows); treat it as a rejection with no code.
        return -1, {}


def _boot_actions(enabled):
    config = sa.load_config(overrides={"token": "secret", "actions": enabled, "port": 0})
    ring = sa.Ring(10)
    sampler = sa.Sampler(ring, sa.LocalFS(), 2, ["/"])
    httpd = sa.make_server(config, ring, sampler)
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    time.sleep(0.05)
    return httpd, httpd.server_address[1]


class TestActions(unittest.TestCase):
    def test_disabled_by_default_403(self):
        httpd, port = _boot_actions([])
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        code, body = _post(port, "/api/v1/action", {"action": "system_info"})
        self.assertEqual(code, 403)
        self.assertIn("error", body)

    def test_allowlisted_system_info_ok(self):
        httpd, port = _boot_actions(["system_info"])
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        code, body = _post(port, "/api/v1/action", {"action": "system_info"})
        self.assertEqual(code, 200)
        self.assertTrue(body["ok"])

    def test_service_restart_rejects_bad_name(self):
        self.assertFalse(bool(sa.SERVICE_NAME_RE.match("nginx; rm -rf /")))
        self.assertTrue(bool(sa.SERVICE_NAME_RE.match("nginx")))
        self.assertTrue(bool(sa.SERVICE_NAME_RE.match("ssh@1.service")))

    def test_malformed_body_400(self):
        httpd, port = _boot_actions(["system_info"])
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        code, _ = _post(port, "/api/v1/action", None, raw=b"{not json")
        self.assertEqual(code, 400)

    def test_oversized_body_413(self):
        httpd, port = _boot_actions(["system_info"])
        self.addCleanup(httpd.server_close)
        self.addCleanup(httpd.shutdown)
        code, _ = _post(port, "/api/v1/action", None, raw=b"x" * 70000)
        # The agent rejects the oversized body. On Windows the server may close the
        # connection before the client finishes uploading (surfacing here as -1),
        # which is still a rejection.
        self.assertIn(code, (413, -1))


class TestCli(unittest.TestCase):
    def test_missing_token_exits(self):
        with self.assertRaises(SystemExit) as ctx:
            sa.main(["--port", "0"])
        self.assertEqual(ctx.exception.code, 2)

    def test_parser_defaults(self):
        args = sa.build_parser().parse_args([])
        self.assertIsNone(args.config)
        self.assertIsNone(args.token)


if __name__ == "__main__":
    unittest.main()
