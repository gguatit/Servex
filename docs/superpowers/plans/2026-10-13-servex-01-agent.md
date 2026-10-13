# Servex Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the reference `servex-agent` — a single-file Python 3 HTTP agent that reports host metrics to the Servex Android app.

**Architecture:** One process: a `ThreadingHTTPServer` serving a small JSON API, plus a background sampler thread that collects metrics every `sample_interval` seconds into a thread-safe ring buffer. All `/proc` parsing is isolated into pure functions so it is unit-testable without a real Linux host.

**Tech Stack:** Python 3.8+, standard library only (`http.server`, `threading`, `json`, `ssl`, `hmac`, `collections`, `unittest`). No third-party packages.

**Spec:** `docs/superpowers/specs/2026-10-13-servex-design.md`

## Global Constraints

- Python 3.8+; **zero** third-party packages (stdlib only).
- Default `bind` `0.0.0.0`, `port` 8765, `sample_interval` 2, `history_size` 150.
- Bearer token is **required**; comparison must be constant-time (`hmac.compare_digest`).
- Remote actions are **disabled by default** and only run if present in the config allowlist.
- **No emoji anywhere** (code, messages, docs).
- Response units: bytes / bytes-per-second are integers, `*_pct` are floats 0–100.
- Base path `/api/v1`; error bodies are `{"error": "<message>"}`.

## Review Focus

Inputs/conditions most likely to bite a real user, each pinned by a test below:

1. **No `/proc` (macOS/Windows/containers)** — the agent must fail with a clear message at startup, not crash mid-request. (Task 2/3: readers raise `RuntimeError("...requires Linux /proc...")`.)
2. **Network counter reset / interface appears or disappears between samples** — rates must clamp at 0, never negative or absurd. (Task 3: `net_rates` with wrap/zero-prev.)
3. **Permission-denied or unmounted disk path** — skip the mount, keep the rest. (Task 3: `disk_usage` returns `None` on `OSError`.)
4. **Malformed or oversized request body** — respond `400`/`413`, never crash the server. (Task 6.)
5. **Concurrent reads during sampling** — a `history`/`metrics` request must never see a half-written buffer. (Task 4: lock-guarded `Ring`.)
6. **Missing/empty token config** — agent refuses to start rather than serving unauthenticated. (Task 5.)
7. **Constant-time token comparison** (Task 5: `token_ok` uses `hmac.compare_digest`.)

---

### Task 1: Skeleton, config loader, package layout

**Files:**
- Create: `agent/servex-agent.py`
- Create: `agent/servex-agent.json.example`
- Create: `agent/test_agent.py`
- Create: `agent/README.md`

**Interfaces:**
- Produces: `DEFAULTS: dict`, `load_config(path: str | None = None, overrides: dict | None = None) -> dict`.

- [ ] **Step 1: Write the failing test**

```python
# agent/test_agent.py
import importlib.util, os, unittest

_spec = importlib.util.spec_from_file_location(
    "servex_agent", os.path.join(os.path.dirname(__file__), "servex-agent.py"))
sa = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sa)


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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python agent/test_agent.py -v`
Expected: FAIL (`FileNotFoundError` / no attribute `load_config`).

- [ ] **Step 3: Write minimal implementation**

```python
# agent/servex-agent.py
#!/usr/bin/env python3
"""Servex reference agent — stdlib only."""
import json
import os

DEFAULTS = {
    "bind": "0.0.0.0",
    "port": 8765,
    "token": "",
    "sample_interval": 2.0,
    "history_size": 150,
    "actions": [],
    "disk_mounts": ["/"],
    "tls": {"cert": None, "key": None},
    "rate_limit_per_min": 240,
    "max_body_bytes": 65536,
}


def load_config(path=None, overrides=None):
    cfg = json.loads(json.dumps(DEFAULTS))  # deep copy
    if path:
        with open(path, "r", encoding="utf-8") as fh:
            cfg.update(json.load(fh))
    if overrides:
        cfg.update({k: v for k, v in overrides.items() if v is not None})
    if not cfg.get("token"):
        cfg["token"] = os.environ.get("SERVEX_TOKEN", "")
    return cfg
```

- [ ] **Step 4: Run test to verify it passes**

Run: `python agent/test_agent.py -v`
Expected: PASS (2 tests).

- [ ] **Step 5: Add the example config**

```json
// agent/servex-agent.json.example
{
  "bind": "0.0.0.0",
  "port": 8765,
  "token": "REPLACE_WITH_A_LONG_RANDOM_SECRET",
  "sample_interval": 2,
  "history_size": 150,
  "disk_mounts": ["/"],
  "actions": [],
  "tls": { "cert": null, "key": null }
}
```

- [ ] **Step 6: Add `agent/README.md`**

```markdown
# servex-agent

Reference agent for Servex. Python 3.8+, standard library only.

## Run

    cp servex-agent.json.example servex-agent.json
    # edit the token
    python3 servex-agent.py --config servex-agent.json

HTTP server on port 8765 (default). See `../docs/AGENT.md` for the API.
```

- [ ] **Step 7: Commit**

```bash
git add agent/servex-agent.py agent/test_agent.py agent/servex-agent.json.example agent/README.md
git commit -m "feat(agent): skeleton and config loader"
```

---

### Task 2: Pure `/proc` parsers

**Files:**
- Modify: `agent/servex-agent.py`
- Test: `agent/test_agent.py`

**Interfaces:**
- Produces:
  - `parse_proc_stat(text: str) -> dict` → `{"idle": int, "total": int}` (whole-CPU totals)
  - `parse_meminfo(text: str) -> dict` → `{"total": int, "used": int, "available": int, "pct": float}` (bytes)
  - `parse_net_dev(text: str) -> dict` → `{iface: {"rx_total": int, "tx_total": int}}` (excludes `lo`)
  - `parse_proc_uptime(text: str) -> int`

- [ ] **Step 1: Write the failing tests**

```python
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python agent/test_agent.py -v`
Expected: FAIL (`AttributeError: parse_proc_stat`).

- [ ] **Step 3: Write minimal implementation**

```python
def parse_proc_stat(text):
    for line in text.splitlines():
        if line.startswith("cpu "):
            nums = [int(x) for x in line.split()[1:]]
            idle = nums[3] + (nums[4] if len(nums) > 4 else 0)
            return {"idle": idle, "total": sum(nums)}
    raise ValueError("no aggregate cpu line in /proc/stat")


def parse_meminfo(text):
    vals = {}
    for line in text.splitlines():
        if ":" not in line:
            continue
        key, raw = line.split(":", 1)
        parts = raw.strip().split()
        if parts:
            try:
                vals[key] = int(parts[0]) * 1024  # kB -> bytes
            except ValueError:
                pass
    total = vals.get("MemTotal", 0)
    available = vals.get("MemAvailable", vals.get("MemFree", 0))
    used = max(0, total - available)
    pct = round(used / total * 100, 1) if total else 0.0
    return {"total": total, "used": used, "available": available, "pct": pct}


def parse_net_dev(text):
    out = {}
    for line in text.splitlines()[2:]:
        if ":" not in line:
            continue
        iface, rest = line.split(":", 1)
        iface = iface.strip()
        fields = rest.split()
        if iface == "lo" or len(fields) < 9:
            continue
        out[iface] = {"rx_total": int(fields[0]), "tx_total": int(fields[8])}
    return out


def parse_proc_uptime(text):
    return int(float(text.split()[0]))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python agent/test_agent.py -v`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/servex-agent.py agent/test_agent.py
git commit -m "feat(agent): pure /proc parsers"
```

---

### Task 3: Derived metrics (CPU usage, network rates, disk usage, host info)

**Files:**
- Modify: `agent/servex-agent.py`
- Test: `agent/test_agent.py`

**Interfaces:**
- Consumes: `parse_*` from Task 2.
- Produces:
  - `cpu_usage(prev: dict, cur: dict) -> float`
  - `net_rates(prev: dict, cur: dict, dt: float) -> dict` → `{iface: {"iface","rx_bps","tx_bps","rx_total","tx_total"}}`
  - `disk_usage(stat) -> dict` → `{"total","used","pct"}` from a `os.statvfs` result
  - `read_os_pretty(text: str) -> str`
  - `class LocalFS` with `proc_stat()`, `meminfo()`, `net_dev()`, `uptime()`, `statvfs(path)`
  - `metrics_from(fs, prev_cpu, prev_net, dt, mounts) -> dict`

- [ ] **Step 1: Write the failing tests**

```python
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python agent/test_agent.py -v`
Expected: FAIL (`AttributeError: cpu_usage`).

- [ ] **Step 3: Write minimal implementation**

```python
import time


def cpu_usage(prev, cur):
    d_total = cur["total"] - prev["total"]
    d_idle = cur["idle"] - prev["idle"]
    if d_total <= 0:
        return 0.0
    return round(max(0.0, min(100.0, (d_total - d_idle) / d_total * 100.0)), 1)


def net_rates(prev, cur, dt):
    out = {}
    for iface, c in cur.items():
        p = prev.get(iface)
        if not p or dt <= 0:
            rx_bps = tx_bps = 0
        else:
            rx_bps = max(0, int((c["rx_total"] - p["rx_total"]) / dt))
            tx_bps = max(0, int((c["tx_total"] - p["tx_total"]) / dt))
        out[iface] = {"iface": iface, "rx_bps": rx_bps, "tx_bps": tx_bps,
                      "rx_total": c["rx_total"], "tx_total": c["tx_total"]}
    return out


def disk_usage(stat):
    total = stat.f_frsize * stat.f_blocks
    free = stat.f_frsize * stat.f_bavail
    used = max(0, total - free)
    pct = round(used / total * 100, 1) if total else 0.0
    return {"total": total, "used": used, "pct": pct}


def read_os_pretty(text):
    for line in text.splitlines():
        if line.startswith("PRETTY_NAME="):
            return line.split("=", 1)[1].strip().strip('"')
    return "Linux"


class LocalFS:
    """Real filesystem reader. Isolated so tests can inject a fake."""

    def _read(self, path):
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as fh:
                return fh.read()
        except OSError as exc:
            raise RuntimeError("%s unavailable; servex-agent requires Linux /proc (%s)"
                               % (path, exc))

    def proc_stat(self):
        return self._read("/proc/stat")

    def meminfo(self):
        return self._read("/proc/meminfo")

    def net_dev(self):
        return self._read("/proc/net/dev")

    def uptime(self):
        return self._read("/proc/uptime")

    def os_pretty(self):
        try:
            return read_os_pretty(self._read("/etc/os-release"))
        except RuntimeError:
            return "Linux"

    def statvfs(self, path):
        return os.statvfs(path)


def metrics_from(fs, prev_cpu, prev_net, dt, mounts, ts):
    import socket
    cur_cpu = parse_proc_stat(fs.proc_stat())
    cur_net = parse_net_dev(fs.net_dev())
    mem = parse_meminfo(fs.meminfo())
    disks = []
    for mount in mounts:
        try:
            disks.append({"mount": mount, **disk_usage(fs.statvfs(mount))})
        except OSError:
            continue  # permission denied / unmounted -> skip
    try:
        uptime_s = parse_proc_uptime(fs.uptime())
    except RuntimeError:
        uptime_s = 0
    usage = cpu_usage(prev_cpu, cur_cpu) if prev_cpu else 0.0
    try:
        load1, load5, load15 = os.getloadavg()
    except OSError:
        load1 = load5 = load15 = 0.0
    snapshot = {
        "ts": int(ts),
        "host": socket.gethostname(),
        "os": fs.os_pretty(),
        "uptime_s": uptime_s,
        "cpu": {"usage_pct": usage, "cores": os.cpu_count() or 1,
                "load1": round(load1, 2), "load5": round(load5, 2), "load15": round(load15, 2)},
        "memory": mem,
        "disks": disks,
        "network": list(net_rates(prev_net or {}, cur_net, dt).values()),
    }
    return snapshot, cur_cpu, cur_net
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python agent/test_agent.py -v`
Expected: PASS (14 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/servex-agent.py agent/test_agent.py
git commit -m "feat(agent): derived cpu/net/disk metrics"
```

---

### Task 4: Ring buffer, sampler thread, history extraction

**Files:**
- Modify: `agent/servex-agent.py`
- Test: `agent/test_agent.py`

**Interfaces:**
- Consumes: `metrics_from` (Task 3).
- Produces:
  - `class Ring` with `append(item)`, `snapshot() -> list`, `__len__`
  - `class Sampler(threading.Thread)` with `latest -> dict | None`, `start()`, `stop()`
  - `history_points(snapshots: list, metric: str, points: int) -> list[dict]` → `[{"t": int, "v": float}]`

- [ ] **Step 1: Write the failing tests**

```python
class TestRingSamplerHistory(unittest.TestCase):
    def test_ring_is_bounded(self):
        r = sa.Ring(3)
        for i in range(5):
            r.append(i)
        self.assertEqual(r.snapshot(), [2, 3, 4])
        self.assertEqual(len(r), 3)

    def test_history_extracts_cpu(self):
        snaps = [
            {"ts": 1, "cpu": {"usage_pct": 10.0}, "memory": {"pct": 20.0},
             "disks": [{"pct": 30.0}], "network": [{"rx_bps": 100, "tx_bps": 50}]},
            {"ts": 2, "cpu": {"usage_pct": 15.5}, "memory": {"pct": 25.0},
             "disks": [{"pct": 31.0}], "network": [{"rx_bps": 200, "tx_bps": 60}]},
        ]
        pts = sa.history_points(snaps, "cpu", 60)
        self.assertEqual(pts, [{"t": 1, "v": 10.0}, {"t": 2, "v": 15.5}])

    def test_history_network_is_aggregate_rx(self):
        snaps = [{"ts": 1, "network": [{"rx_bps": 100, "tx_bps": 9},
                                       {"rx_bps": 50, "tx_bps": 1}]}]
        pts = sa.history_points(snaps, "network", 60)
        self.assertEqual(pts[0]["v"], 150)

    def test_history_disk_is_max_pct(self):
        snaps = [{"ts": 1, "disks": [{"pct": 10.0}, {"pct": 80.0}]}]
        pts = sa.history_points(snaps, "disk", 60)
        self.assertEqual(pts[0]["v"], 80.0)

    def test_history_points_clamped(self):
        snaps = [{"ts": i, "cpu": {"usage_pct": 1.0}} for i in range(10)]
        self.assertEqual(len(sa.history_points(snaps, "cpu", 3)), 3)

    def test_history_unknown_metric_is_empty(self):
        self.assertEqual(sa.history_points([{"ts": 1}], "bogus", 5), [])
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python agent/test_agent.py -v`
Expected: FAIL (`AttributeError: Ring`).

- [ ] **Step 3: Write minimal implementation**

```python
import threading
from collections import deque


class Ring:
    def __init__(self, size):
        self._data = deque(maxlen=size)
        self._lock = threading.Lock()

    def append(self, item):
        with self._lock:
            self._data.append(item)

    def snapshot(self):
        with self._lock:
            return list(self._data)

    def __len__(self):
        with self._lock:
            return len(self._data)


class Sampler(threading.Thread):
    def __init__(self, ring, fs, sample_interval, mounts):
        super().__init__(daemon=True)
        self.ring = ring
        self.fs = fs
        self.sample_interval = sample_interval
        self.mounts = mounts
        self.latest = None
        self._stop = threading.Event()
        self._prev_cpu = None
        self._prev_net = None

    def run(self):
        while not self._stop.is_set():
            ts = time.time()
            try:
                snap, self._prev_cpu, self._prev_net = metrics_from(
                    self.fs, self._prev_cpu, self._prev_net,
                    self.sample_interval, self.mounts, ts)
                self.latest = snap
                self.ring.append(snap)
            except Exception:  # never let one bad sample kill the thread
                pass
            self._stop.wait(self.sample_interval)

    def stop(self):
        self._stop.set()


def history_points(snapshots, metric, points):
    gets = {
        "cpu": lambda s: s.get("cpu", {}).get("usage_pct", 0.0),
        "memory": lambda s: s.get("memory", {}).get("pct", 0.0),
        "disk": lambda s: max((d.get("pct", 0.0) for d in s.get("disks", [])), default=0.0),
        "network": lambda s: sum(n.get("rx_bps", 0) for n in s.get("network", [])),
    }
    get = gets.get(metric)
    if get is None:
        return []
    return [{"t": s["ts"], "v": round(float(get(s)), 1)} for s in snapshots[-points:]]
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python agent/test_agent.py -v`
Expected: PASS (20 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/servex-agent.py agent/test_agent.py
git commit -m "feat(agent): ring buffer, sampler, history"
```

---

### Task 5: HTTP server, auth, GET routes

**Files:**
- Modify: `agent/servex-agent.py`
- Test: `agent/test_agent.py`

**Interfaces:**
- Consumes: `Ring`, `Sampler`, `history_points`.
- Produces:
  - `token_ok(provided: str, expected: str) -> bool`
  - `class ServexServer(ThreadingHTTPServer)` with attributes `ring`, `sampler`, `config`, `limiter`
  - `make_server(config, ring, sampler) -> ServexServer`
  - `summary_from(snapshot: dict) -> dict`
- Route behavior: `/api/v1/health` and `/api/v1/summary` and `/api/v1/metrics` and `/api/v1/history` all require the bearer token and return JSON.

- [ ] **Step 1: Write the failing tests**

```python
import json
import threading
import time
import urllib.request
import urllib.error


def _boot(token="secret", **cfg):
    config = sa.load_config(overrides={"token": token, **cfg})
    ring = sa.Ring(config["history_size"])
    sampler = sa.Sampler(ring, sa.LocalFS(), config["sample_interval"], config["disk_mounts"])
    ring.append({"ts": 1, "cpu": {"usage_pct": 10.0}, "memory": {"pct": 20.0},
                 "disks": [{"pct": 30.0}], "network": [{"rx_bps": 1, "tx_bps": 1}],
                 "host": "h", "os": "Linux", "uptime_s": 5})
    httpd = sa.make_server(config, ring, sampler)
    t = threading.Thread(target=httpd.serve_forever, daemon=True)
    t.start()
    time.sleep(0.05)
    return httpd, httpd.server_address[1]


def _get(port, path, token="secret"):
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
        self.addCleanup(httpd.shutdown)
        code, body = _get(port, "/api/v1/health", token=None)
        self.assertEqual(code, 401)
        self.assertIn("error", body)

    def test_bad_token_401(self):
        httpd, port = _boot()
        self.addCleanup(httpd.shutdown)
        code, _ = _get(port, "/api/v1/metrics", token="wrong")
        self.assertEqual(code, 401)

    def test_metrics_ok(self):
        httpd, port = _boot()
        self.addCleanup(httpd.shutdown)
        code, body = _get(port, "/api/v1/metrics")
        self.assertEqual(code, 200)
        self.assertIn("cpu", body)
        self.assertEqual(body["cpu"]["usage_pct"], 10.0)

    def test_history_metric_param(self):
        httpd, port = _boot()
        self.addCleanup(httpd.shutdown)
        code, body = _get(port, "/api/v1/history?metric=cpu&points=10")
        self.assertEqual(code, 200)
        self.assertEqual(body["metric"], "cpu")
        self.assertEqual(body["points"][0]["v"], 10.0)

    def test_unknown_route_404(self):
        httpd, port = _boot()
        self.addCleanup(httpd.shutdown)
        code, _ = _get(port, "/api/v1/nope")
        self.assertEqual(code, 404)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python agent/test_agent.py -v`
Expected: FAIL (`AttributeError: make_server`).

- [ ] **Step 3: Write minimal implementation**

```python
import hmac
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

METRICS_KEYS = ("ts", "host", "os", "uptime_s", "cpu", "memory", "disks", "network")


def token_ok(provided, expected):
    if not provided or not expected:
        return False
    return hmac.compare_digest(provided, expected)


def summary_from(snapshot):
    if not snapshot:
        return {"host": None, "os": None, "uptime_s": 0, "ts": 0}
    return {k: snapshot.get(k) for k in ("host", "os", "uptime_s", "ts")}


class _Handler(BaseHTTPRequestHandler):
    server_version = "servex-agent/1.0"

    def _json(self, code, payload):
        body = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self):
        header = self.headers.get("Authorization", "")
        provided = header[7:] if header.startswith("Bearer ") else ""
        return token_ok(provided, self.server.config["token"])

    def do_GET(self):
        if not self._authorized():
            return self._json(401, {"error": "unauthorized"})
        if not self.server.limiter.allow(self.client_address[0]):
            return self._json(429, {"error": "rate limited"})
        parsed = urllib.parse.urlparse(self.path)
        path, query = parsed.path, urllib.parse.parse_qs(parsed.query)
        snap = self.server.sampler.latest
        if path == "/api/v1/health":
            return self._json(200, {"ok": True})
        if path == "/api/v1/summary":
            return self._json(200, summary_from(snap))
        if path == "/api/v1/metrics":
            if snap is None:
                return self._json(503, {"error": "no sample yet"})
            return self._json(200, snap)
        if path == "/api/v1/history":
            metric = query.get("metric", ["cpu"])[0]
            points = int(query.get("points", ["60"])[0] or 60)
            pts = history_points(self.server.ring.snapshot(), metric, points)
            return self._json(200, {"metric": metric,
                                    "interval_s": self.server.config["sample_interval"],
                                    "points": pts})
        return self._json(404, {"error": "not found"})

    def log_message(self, fmt, *args):
        pass


class ServexServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, addr, config, ring, sampler, limiter):
        super().__init__(addr, _Handler)
        self.config = config
        self.ring = ring
        self.sampler = sampler
        self.limiter = limiter


class RateLimiter:
    """ponytail: fixed-window per-IP counter; swap for a token bucket if needed."""

    def __init__(self, per_min):
        self.per_min = per_min
        self._hits = {}
        self._lock = threading.Lock()

    def allow(self, ip):
        now = time.time()
        with self._lock:
            window = self._hits.setdefault(ip, [])
            while window and now - window[0] > 60:
                window.pop(0)
            if len(window) >= self.per_min:
                return False
            window.append(now)
            return True


def make_server(config, ring, sampler):
    limiter = RateLimiter(config["rate_limit_per_min"])
    return ServexServer((config["bind"], config["port"]), config, ring, sampler, limiter)
```

Add `"rate_limit_per_min": 240` to `DEFAULTS` under Task 1's block (already present).

- [ ] **Step 4: Run tests to verify they pass**

Run: `python agent/test_agent.py -v`
Expected: PASS (26 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/servex-agent.py agent/test_agent.py
git commit -m "feat(agent): http server, auth, GET routes"
```

---

### Task 6: Remote actions (POST), body limits, allowlist

**Files:**
- Modify: `agent/servex-agent.py`
- Test: `agent/test_agent.py`

**Interfaces:**
- Consumes: `_Handler`, `ServexServer`.
- Produces:
  - `builtin_actions() -> dict[str, callable]` (keys: `system_info`, `service_restart`)
  - `ACTION_NAME_RE`, `SERVICE_NAME_RE`
  - `ServexServer.actions` (resolved allowlist dict)
  - Behavior: unknown/disabled action → `403`; malformed JSON → `400`; body over `max_body_bytes` → `413`.

- [ ] **Step 1: Write the failing tests**

```python
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
        self.addCleanup(httpd.shutdown)
        code, body = _post(port, "/api/v1/action", {"action": "system_info"})
        self.assertEqual(code, 403)

    def test_allowlisted_system_info_ok(self):
        httpd, port = _boot_actions(["system_info"])
        self.addCleanup(httpd.shutdown)
        code, body = _post(port, "/api/v1/action", {"action": "system_info"})
        self.assertEqual(code, 200)
        self.assertTrue(body["ok"])

    def test_service_restart_rejects_bad_name(self):
        self.assertFalse(sa.SERVICE_NAME_RE.match("nginx; rm -rf /"))
        self.assertTrue(sa.SERVICE_NAME_RE.match("nginx"))
        self.assertTrue(sa.SERVICE_NAME_RE.match("ssh@1.service"))

    def test_malformed_body_400(self):
        httpd, port = _boot_actions(["system_info"])
        self.addCleanup(httpd.shutdown)
        code, _ = _post(port, "/api/v1/action", None, raw=b"{not json")
        self.assertEqual(code, 400)

    def test_oversized_body_413(self):
        httpd, port = _boot_actions(["system_info"])
        self.addCleanup(httpd.shutdown)
        big = b'{"action":"system_info","pad":"' + b"x" * (70000) + b'"}'
        code, _ = _post(port, "/api/v1/action", None, raw=big)
        self.assertEqual(code, 413)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `python agent/test_agent.py -v`
Expected: FAIL (`AttributeError: SERVICE_NAME_RE` / 404 not 403).

- [ ] **Step 3: Write minimal implementation**

```python
import re
import subprocess

ACTION_NAME_RE = re.compile(r"^[a-z_]{1,32}$")
SERVICE_NAME_RE = re.compile(r"^[A-Za-z0-9_.@-]{1,64}$")


def _system_info(args, server):
    snap = server.sampler.latest or {}
    return "%s %s uptime=%ss cpu=%s%%" % (
        snap.get("host"), snap.get("os"), snap.get("uptime_s"),
        snap.get("cpu", {}).get("usage_pct"))


def _service_restart(args, server):
    name = (args or {}).get("service", "")
    if not SERVICE_NAME_RE.match(name):
        raise ValueError("invalid service name")
    subprocess.run(["systemctl", "restart", name], check=True, timeout=30)
    return "restarted %s" % name


def builtin_actions():
    return {"system_info": _system_info, "service_restart": _service_restart}
```

Add `do_POST` to `_Handler` and resolve actions in `ServexServer`:

```python
    def do_POST(self):
        if not self._authorized():
            return self._json(401, {"error": "unauthorized"})
        if not self.server.limiter.allow(self.client_address[0]):
            return self._json(429, {"error": "rate limited"})
        if urllib.parse.urlparse(self.path).path != "/api/v1/action":
            return self._json(404, {"error": "not found"})
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length > self.server.config["max_body_bytes"]:
            return self._json(413, {"error": "body too large"})
        raw = self.rfile.read(length) if length else b""
        try:
            payload = json.loads(raw.decode("utf-8"))
            action = payload["action"]
        except (ValueError, KeyError, TypeError):
            return self._json(400, {"error": "invalid body"})
        fn = self.server.actions.get(action)
        if fn is None:
            return self._json(403, {"error": "action not allowed"})
        try:
            output = fn(payload.get("args") or {}, self.server)
        except Exception as exc:
            return self._json(500, {"error": str(exc)})
        return self._json(200, {"ok": True, "output": output})
```

```python
class ServexServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, addr, config, ring, sampler, limiter):
        super().__init__(addr, _Handler)
        self.config = config
        self.ring = ring
        self.sampler = sampler
        self.limiter = limiter
        registry = builtin_actions()
        self.actions = {name: registry[name]
                        for name in config.get("actions", []) if name in registry}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python agent/test_agent.py -v`
Expected: PASS (31 tests).

- [ ] **Step 5: Commit**

```bash
git add agent/servex-agent.py agent/test_agent.py
git commit -m "feat(agent): allowlisted remote actions"
```

---

### Task 7: CLI entrypoint, TLS, systemd unit, AGENT.md

**Files:**
- Modify: `agent/servex-agent.py`
- Create: `agent/servex-agent.service`
- Create: `docs/AGENT.md`
- Test: `agent/test_agent.py`

**Interfaces:**
- Produces: `main(argv=None) -> int`; `build_parser() -> argparse.ArgumentParser`.
- Behavior: missing token → exit code `2` with a stderr message; `--tls-cert`/`--tls-key` wrap the socket with `ssl`.

- [ ] **Step 1: Write the failing test**

```python
class TestCli(unittest.TestCase):
    def test_missing_token_exits(self):
        with self.assertRaises(SystemExit) as ctx:
            sa.main(["--port", "0"])
        self.assertEqual(ctx.exception.code, 2)

    def test_parser_defaults(self):
        args = sa.build_parser().parse_args([])
        self.assertIsNone(args.config)
        self.assertIsNone(args.token)
```

- [ ] **Step 2: Run test to verify it fails**

Run: `python agent/test_agent.py -v`
Expected: FAIL (`AttributeError: main`).

- [ ] **Step 3: Write minimal implementation**

```python
import argparse
import ssl
import sys


def build_parser():
    p = argparse.ArgumentParser(prog="servex-agent")
    p.add_argument("--config")
    p.add_argument("--token")
    p.add_argument("--port", type=int)
    p.add_argument("--bind")
    p.add_argument("--tls-cert")
    p.add_argument("--tls-key")
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    overrides = {"token": args.token, "port": args.port, "bind": args.bind}
    config = load_config(args.config, overrides)
    if not config["token"]:
        sys.stderr.write("error: a token is required (--token, config, or SERVEX_TOKEN)\n")
        return 2
    if args.tls_cert and args.tls_key:
        config["tls"] = {"cert": args.tls_cert, "key": args.tls_key}
    ring = Ring(config["history_size"])
    sampler = Sampler(ring, LocalFS(), config["sample_interval"], config["disk_mounts"])
    sampler.start()
    httpd = make_server(config, ring, sampler)
    if config.get("tls", {}).get("cert"):
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(config["tls"]["cert"], config["tls"]["key"])
        httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
    sys.stderr.write("servex-agent listening on %s:%d\n"
                     % (config["bind"], config["port"]))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        sampler.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
```

`main` must raise `SystemExit(2)`, so change the missing-token branch to call `sys.exit(2)` after the stderr write when `argv` was passed; keep `return 2` for programmatic use. Simplest correct form:

```python
    if not config["token"]:
        sys.stderr.write("error: a token is required (--token, config, or SERVEX_TOKEN)\n")
        raise SystemExit(2)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `python agent/test_agent.py -v`
Expected: PASS (33 tests).

- [ ] **Step 5: Create the systemd unit**

```ini
; agent/servex-agent.service
[Unit]
Description=Servex monitoring agent
After=network-online.target

[Service]
Type=simple
User=servex
ExecStart=/usr/bin/python3 /opt/servex/servex-agent.py --config /etc/servex/servex-agent.json
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

- [ ] **Step 6: Create `docs/AGENT.md`**

```markdown
# servex-agent

Reference HTTP agent. Python 3.8+, standard library only.

## Install

    sudo cp servex-agent.service /etc/systemd/system/
    sudo mkdir -p /etc/servex && sudo cp servex-agent.json.example /etc/servex/servex-agent.json
    # edit /etc/servex/servex-agent.json and set a long random token
    sudo systemctl enable --now servex-agent

## API

Base path `/api/v1`. Every request: `Authorization: Bearer <token>`.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/health` | liveness |
| GET | `/summary` | host/os/uptime |
| GET | `/metrics` | full snapshot |
| GET | `/history?metric=<cpu|memory|disk|network>&points=<n>` | ring buffer |
| POST | `/action` | allowlisted only; disabled by default |

History `v` per metric: `cpu` = usage %, `memory` = used %, `disk` = max mount %,
`network` = aggregate receive bytes/s across non-loopback interfaces.

## Config keys

`bind` (0.0.0.0), `port` (8765), `token`, `sample_interval` (2), `history_size` (150),
`disk_mounts` (["/"]), `actions` ([]), `tls {cert,key}`, `rate_limit_per_min` (240),
`max_body_bytes` (65536).

## Security

Token required; `hmac.compare_digest` comparison. Prefer HTTPS (set `tls` or put
the agent behind a TLS-terminating reverse proxy). Plain HTTP exposes the token
on the wire. See `SECURITY.md`.
```

- [ ] **Step 7: Commit**

```bash
git add agent/servex-agent.py agent/test_agent.py agent/servex-agent.service docs/AGENT.md
git commit -m "feat(agent): cli, tls, systemd unit, docs"
```

---

### Task 8: Run the full agent test suite

- [ ] **Step 1: Run everything**

Run: `python agent/test_agent.py -v`
Expected: PASS (33 tests), 0 failures.

- [ ] **Step 2: Smoke-test on a Linux host (manual, optional on Windows dev machine)**

Run: `python3 agent/servex-agent.py --token testtoken --port 8765` then
`curl -H "Authorization: Bearer testtoken" http://127.0.0.1:8765/api/v1/metrics`
Expected: JSON snapshot with `cpu`, `memory`, `disks`, `network`.

- [ ] **Step 3: Commit any test-only adjustments**

```bash
git add -A
git commit -m "test(agent): green full suite"
```
