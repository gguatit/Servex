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
