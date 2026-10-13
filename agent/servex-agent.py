#!/usr/bin/env python3
"""Servex reference agent — stdlib only."""
import json
import os
import threading
import time
from collections import deque

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
            except Exception:
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
