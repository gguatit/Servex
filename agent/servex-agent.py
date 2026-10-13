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
