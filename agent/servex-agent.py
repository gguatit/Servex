#!/usr/bin/env python3
"""Servex reference agent — stdlib only."""
import argparse
import glob
import hmac
import json
import os
import platform
import re
import secrets
import shutil
import socket
import ssl
import subprocess
import sys
import threading
import time
import urllib.parse
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

try:  # Windows-only; imported at top level so PyInstaller bundles the submodule.
    import ctypes
    import ctypes.wintypes  # noqa: F401
except Exception:
    pass

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


def parse_proc_stat_cores(text):
    """Per-core counters from cpu0..cpuN lines, in file order."""
    cores = []
    for line in text.splitlines():
        parts = line.split()
        if not parts:
            continue
        name = parts[0]
        if not name.startswith("cpu") or not name[3:].isdigit():
            continue
        nums = [int(x) for x in parts[1:]]
        if len(nums) < 4:
            continue
        idle = nums[3] + (nums[4] if len(nums) > 4 else 0)
        cores.append({"idle": idle, "total": sum(nums)})
    return cores


def parse_proc_btime(text):
    for line in text.splitlines():
        if line.startswith("btime "):
            try:
                return int(line.split()[1])
            except (IndexError, ValueError):
                return None
    return None


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


def parse_meminfo_extras(text):
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
    swap_total = vals.get("SwapTotal", 0)
    swap_free = vals.get("SwapFree", 0)
    swap_used = max(0, swap_total - swap_free)
    swap_pct = round(swap_used / swap_total * 100, 1) if swap_total else 0.0
    return {"swap_total": swap_total, "swap_used": swap_used, "swap_pct": swap_pct,
            "cached": vals.get("Cached", 0), "buffers": vals.get("Buffers", 0)}


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


def parse_net_dev_extras(text):
    out = {}
    for line in text.splitlines()[2:]:
        if ":" not in line:
            continue
        iface, rest = line.split(":", 1)
        iface = iface.strip()
        fields = rest.split()
        if iface == "lo" or len(fields) < 12:
            continue
        try:
            out[iface] = {"rx_errs": int(fields[2]), "rx_drops": int(fields[3]),
                          "tx_errs": int(fields[10]), "tx_drops": int(fields[11])}
        except ValueError:
            continue
    return out


SECTOR_SIZE = 512


def parse_diskstats(text):
    read_total = write_total = 0
    for line in text.splitlines():
        fields = line.split()
        if len(fields) < 10:
            continue
        try:
            read_total += int(fields[5]) * SECTOR_SIZE
            write_total += int(fields[9]) * SECTOR_SIZE
        except ValueError:
            continue
    return {"read_total": read_total, "write_total": write_total}


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

    def diskstats(self):
        return self._read("/proc/diskstats")

    def cpuinfo(self):
        return self._read("/proc/cpuinfo")

    def os_pretty(self):
        try:
            return read_os_pretty(self._read("/etc/os-release"))
        except RuntimeError:
            return "Linux"

    def statvfs(self, path):
        return os.statvfs(path)


def parse_cpuinfo_model(text):
    for line in text.splitlines():
        if ":" not in line:
            continue
        key, val = line.split(":", 1)
        if key.strip() in ("model name", "Hardware", "Processor"):
            return val.strip()
    return None


def read_thermal_temp():
    """First sane thermal zone/hwmon reading, millidegrees -> Celsius."""
    for pattern in ("/sys/class/thermal/thermal_zone*/temp",
                    "/sys/class/hwmon/hwmon*/temp1_input"):
        for path in sorted(glob.glob(pattern)):
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as fh:
                    val = float(fh.read().strip())
            except (OSError, ValueError):
                continue
            if val > 1000.0:
                val = val / 1000.0
            if -50.0 <= val <= 200.0:
                return round(val, 1)
    return None


# Disk I/O has no slot in the metrics_from 3-tuple return, so the previous
# totals are kept here keyed by fs identity (id()). One LocalFS lives for the
# whole sampler lifetime, so ids are stable; the entry is overwritten each call.
_DISK_IO_PREV = {}
_DISK_IO_LOCK = threading.Lock()


def disk_io_rates(fs, cur, dt):
    key = id(fs)
    with _DISK_IO_LOCK:
        prev = _DISK_IO_PREV.get(key)
        _DISK_IO_PREV[key] = cur
    io = {"read_total": cur["read_total"], "write_total": cur["write_total"]}
    if prev is not None and dt > 0:
        io["read_bps"] = max(0, int((cur["read_total"] - prev["read_total"]) / dt))
        io["write_bps"] = max(0, int((cur["write_total"] - prev["write_total"]) / dt))
    return io


def _top_processes(procs):
    by_cpu = sorted(procs, key=lambda p: p["cpu_pct"], reverse=True)[:5]
    by_mem = sorted(procs, key=lambda p: p["mem_pct"], reverse=True)[:5]
    return {"by_cpu": by_cpu, "by_mem": by_mem}


def _clock_ticks():
    try:
        return os.sysconf("SC_CLK_TCK") or 100
    except (ValueError, OSError, AttributeError):
        return 100


def linux_top_processes(fs, mem_total):
    ticks = _clock_ticks()
    try:
        uptime_s = float(fs.uptime().split()[0])
    except Exception:
        uptime_s = 0.0
    total_ticks = max(1.0, uptime_s * ticks)
    try:
        pids = [p for p in os.listdir("/proc") if p.isdigit()]
    except OSError:
        return []
    procs = []
    for pid in pids:
        try:
            with open("/proc/%s/stat" % pid, "r", encoding="utf-8", errors="replace") as fh:
                stat = fh.read()
            rp = stat.rfind(")")
            lp = stat.find("(")
            if lp < 0 or rp < 0:
                continue
            fields = stat[rp + 2:].split()
            utime = int(fields[11])
            stime = int(fields[12])
            name = stat[lp + 1:rp]
            rss = 0
            with open("/proc/%s/status" % pid, "r", encoding="utf-8", errors="replace") as fh:
                for line in fh:
                    if line.startswith("VmRSS:"):
                        rss = int(line.split()[1]) * 1024
                        break
            cpu_pct = round((utime + stime) / total_ticks * 100.0, 1)
            mem_pct = round(rss / mem_total * 100.0, 1) if mem_total else 0.0
            procs.append({"pid": int(pid), "name": name,
                          "cpu_pct": cpu_pct, "mem_pct": mem_pct})
        except (OSError, ValueError, IndexError):
            continue
    return procs


def _linux_users():
    try:
        res = subprocess.run(["who"], capture_output=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    if res.returncode != 0:
        return None
    lines = [l for l in res.stdout.decode("utf-8", "replace").splitlines() if l.strip()]
    return len(lines)


def collect_services():
    if os.name == "nt":
        return _windows_services()
    try:
        failed = subprocess.run(["systemctl", "--failed", "--no-legend", "--plain"],
                                capture_output=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    names = []
    for line in failed.stdout.decode("utf-8", "replace").splitlines():
        parts = line.split()
        if parts:
            name = parts[0].lstrip("\u25cf").strip()
            if name:
                names.append(name)
    out = {"failed": names}
    try:
        total = subprocess.run(["systemctl", "list-units", "--type=service", "--no-legend"],
                               capture_output=True, timeout=5)
        lines = [l for l in total.stdout.decode("utf-8", "replace").splitlines() if l.strip()]
        out["total"] = len(lines)
    except (OSError, subprocess.SubprocessError):
        pass
    return out


def _windows_services():
    try:
        import winreg
    except Exception:
        return None
    try:
        key = winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE,
                             r"SYSTEM\CurrentControlSet\Services")
    except OSError:
        return None
    total = 0
    try:
        i = 0
        while True:
            try:
                name = winreg.EnumKey(key, i)
            except OSError:
                break
            i += 1
            try:
                sk = winreg.OpenKey(key, name)
                start, _ = winreg.QueryValueEx(sk, "Start")
                winreg.CloseKey(sk)
                if start == 2:
                    total += 1
            except OSError:
                continue
    finally:
        winreg.CloseKey(key)
    return {"total": total}


def collect_gpu():
    exe = shutil.which("nvidia-smi")
    if not exe:
        return None
    try:
        res = subprocess.run(
            [exe, "--query-gpu=name,utilization.gpu,memory.used,memory.total,temperature.gpu",
             "--format=csv,noheader,nounits"],
            capture_output=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    if res.returncode != 0:
        return None
    gpus = []
    for line in res.stdout.decode("utf-8", "replace").splitlines():
        parts = [p.strip() for p in line.split(",")]
        if len(parts) < 5:
            continue
        try:
            util = float(parts[1])
            mem_used = float(parts[2])
            mem_total = float(parts[3])
            temp = float(parts[4])
        except ValueError:
            continue
        gpus.append({"name": parts[0], "util_pct": round(util, 1),
                     "mem_pct": round(mem_used / mem_total * 100.0, 1) if mem_total else 0.0,
                     "temp_c": round(temp, 1)})
    return gpus or None


def collect_docker():
    exe = shutil.which("docker")
    if not exe:
        return None
    try:
        res = subprocess.run([exe, "ps", "-a", "--format", "{{.State}}"],
                             capture_output=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    if res.returncode != 0:
        return None
    running = stopped = total = 0
    for line in res.stdout.decode("utf-8", "replace").splitlines():
        state = line.strip().lower()
        if not state:
            continue
        total += 1
        if state == "running":
            running += 1
        else:
            stopped += 1
    return {"running": running, "stopped": stopped, "total": total}


def _metrics_linux(snapshot, fs, prev_cpu, prev_net, dt, mounts):
    try:
        snapshot["os"] = fs.os_pretty()
    except Exception:
        snapshot["os"] = "Linux"

    stat_text = None
    try:
        stat_text = fs.proc_stat()
    except Exception:
        stat_text = None
    if stat_text is not None:
        try:
            cur_cpu = parse_proc_stat(stat_text)
            cores = parse_proc_stat_cores(stat_text)
            if cores:
                cur_cpu["cores"] = cores
            btime = parse_proc_btime(stat_text)
            if btime:
                snapshot["boot_time"] = btime
        except Exception:
            cur_cpu = prev_cpu or {"idle": 0, "total": 0}
    else:
        cur_cpu = prev_cpu or {"idle": 0, "total": 0}

    uptime_s = 0
    try:
        uptime_s = parse_proc_uptime(fs.uptime())
    except Exception:
        uptime_s = 0
    snapshot["uptime_s"] = uptime_s
    if "boot_time" not in snapshot and uptime_s:
        snapshot["boot_time"] = int(time.time() - uptime_s)

    cur_net = {}
    extras = {}
    try:
        net_text = fs.net_dev()
        cur_net = parse_net_dev(net_text)
        extras = parse_net_dev_extras(net_text)
    except Exception:
        cur_net = {}
    net = net_rates(prev_net or {}, cur_net, dt)
    for iface, entry in net.items():
        entry.update(extras.get(iface, {}))
    snapshot["network"] = list(net.values())

    mem = {}
    try:
        mem_text = fs.meminfo()
        mem = parse_meminfo(mem_text)
        mem.update(parse_meminfo_extras(mem_text))
    except Exception:
        mem = {}
    snapshot["memory"] = mem

    cpu = {"usage_pct": cpu_usage(prev_cpu, cur_cpu) if prev_cpu else 0.0,
           "cores": os.cpu_count() or 1, "threads": os.cpu_count() or 1}
    try:
        load1, load5, load15 = os.getloadavg()
        cpu["load1"] = round(load1, 2)
        cpu["load5"] = round(load5, 2)
        cpu["load15"] = round(load15, 2)
    except Exception:
        pass
    if isinstance(cur_cpu.get("cores"), list):
        pts = cur_cpu["cores"]
        prev_cores = (prev_cpu or {}).get("cores")
        if isinstance(prev_cores, list) and len(prev_cores) == len(pts):
            cpu["per_core"] = [cpu_usage(p, c) for p, c in zip(prev_cores, pts)]
        else:
            cpu["per_core"] = [0.0 for _ in pts]
    try:
        model = parse_cpuinfo_model(fs.cpuinfo())
        if model:
            cpu["model"] = model
    except Exception:
        pass
    temp = read_thermal_temp()
    if temp is not None:
        cpu["temp_c"] = temp
    snapshot["cpu"] = cpu

    disks = []
    for mount in mounts:
        try:
            disks.append({"mount": mount, **disk_usage(fs.statvfs(mount))})
        except OSError:
            continue  # permission denied / unmounted -> skip
    snapshot["disks"] = disks

    try:
        snapshot["disk_io"] = disk_io_rates(fs, parse_diskstats(fs.diskstats()), dt)
    except Exception:
        pass

    try:
        procs = linux_top_processes(fs, mem.get("total", 0))
        if procs:
            snapshot["processes"] = _top_processes(procs)
    except Exception:
        pass

    try:
        services = collect_services()
        if services:
            snapshot["services"] = services
    except Exception:
        pass
    try:
        gpu = collect_gpu()
        if gpu:
            snapshot["gpu"] = gpu
    except Exception:
        pass
    try:
        docker = collect_docker()
        if docker:
            snapshot["docker"] = docker
    except Exception:
        pass
    try:
        users = _linux_users()
        if users is not None:
            snapshot["users"] = users
    except Exception:
        pass

    return cur_cpu, cur_net


def windows_disks():
    if os.name != "nt":
        return []
    disks = []
    for letter in "ABCDEFGHIJKLMNOPQRSTUVWXYZ":
        root = "%s:\\" % letter
        try:
            usage = shutil.disk_usage(root)
        except OSError:
            continue
        total = usage.total
        used = usage.used
        disks.append({"mount": root, "total": total, "used": used,
                      "pct": round(used / total * 100, 1) if total else 0.0})
    return disks


def _metrics_windows(snapshot, prev_cpu):
    snapshot["os"] = "Windows %s" % (platform.release() or "")
    cur_cpu = windows_cpu_times()
    if not cur_cpu:
        cur_cpu = prev_cpu or {"idle": 0, "total": 0}
        snapshot["cpu"] = {"usage_pct": 0.0, "cores": os.cpu_count() or 1,
                           "threads": os.cpu_count() or 1,
                           "model": platform.processor() or ""}
    else:
        snapshot["cpu"] = {"usage_pct": cpu_usage(prev_cpu, cur_cpu) if prev_cpu else 0.0,
                           "cores": os.cpu_count() or 1,
                           "threads": os.cpu_count() or 1,
                           "model": platform.processor() or ""}
    mem = windows_memory() or {}
    snapshot["memory"] = mem
    boot = windows_boot_time()
    if boot is not None:
        snapshot["boot_time"] = boot
        snapshot["uptime_s"] = int(time.time() - boot)
    else:
        snapshot["uptime_s"] = 0
    snapshot["disks"] = windows_disks()
    snapshot["network"] = []
    try:
        procs = windows_top_processes(mem.get("total", 0))
        if procs:
            snapshot["processes"] = _top_processes(procs)
    except Exception:
        pass
    try:
        services = collect_services()
        if services:
            snapshot["services"] = services
    except Exception:
        pass
    try:
        gpu = collect_gpu()
        if gpu:
            snapshot["gpu"] = gpu
    except Exception:
        pass
    try:
        docker = collect_docker()
        if docker:
            snapshot["docker"] = docker
    except Exception:
        pass
    return cur_cpu, None


def windows_cpu_times():
    """Aggregate idle/total counters, same shape as parse_proc_stat."""
    if os.name != "nt":
        return None
    try:
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    except Exception:
        return None

    class FILETIME(ctypes.Structure):
        _fields_ = [("dwLowDateTime", wintypes.DWORD),
                    ("dwHighDateTime", wintypes.DWORD)]

    def ft_int(ft):
        return (ft.dwHighDateTime << 32) | ft.dwLowDateTime

    idle, kern, user = FILETIME(), FILETIME(), FILETIME()
    try:
        if not kernel32.GetSystemTimes(ctypes.byref(idle), ctypes.byref(kern),
                                       ctypes.byref(user)):
            return None
    except Exception:
        return None
    return {"idle": ft_int(idle), "total": ft_int(kern) + ft_int(user)}


def windows_memory():
    if os.name != "nt":
        return None
    try:
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
    except Exception:
        return None

    class MEMORYSTATUSEX(ctypes.Structure):
        _fields_ = [
            ("dwLength", wintypes.DWORD),
            ("dwMemoryLoad", wintypes.DWORD),
            ("ullTotalPhys", ctypes.c_ulonglong),
            ("ullAvailPhys", ctypes.c_ulonglong),
            ("ullTotalPageFile", ctypes.c_ulonglong),
            ("ullAvailPageFile", ctypes.c_ulonglong),
            ("ullTotalVirtual", ctypes.c_ulonglong),
            ("ullAvailVirtual", ctypes.c_ulonglong),
            ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
        ]

    st = MEMORYSTATUSEX()
    st.dwLength = ctypes.sizeof(st)
    try:
        if not kernel32.GlobalMemoryStatusEx(ctypes.byref(st)):
            return None
    except Exception:
        return None
    total = st.ullTotalPhys
    available = st.ullAvailPhys
    used = max(0, total - available)
    swap_total = max(0, st.ullTotalPageFile - st.ullTotalPhys)
    # Commit-limit heuristic: pagefile use ~= committed bytes minus physical use.
    swap_used = min(swap_total, max(0, (st.ullTotalPageFile - st.ullAvailPageFile) - used))
    return {"total": total, "used": used, "available": available,
            "pct": round(used / total * 100, 1) if total else 0.0,
            "swap_total": swap_total, "swap_used": swap_used,
            "swap_pct": round(swap_used / swap_total * 100, 1) if swap_total else 0.0}


def windows_boot_time():
    if os.name != "nt":
        return None
    try:
        import ctypes
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel32.GetTickCount64.restype = ctypes.c_ulonglong
        ms = kernel32.GetTickCount64()
    except Exception:
        return None
    return int(time.time() - ms / 1000.0)


def windows_top_processes(mem_total):
    if os.name != "nt":
        return []
    try:
        import ctypes
        from ctypes import wintypes
        kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
        psapi = ctypes.WinDLL("psapi", use_last_error=True)
    except Exception:
        return []

    class FILETIME(ctypes.Structure):
        _fields_ = [("dwLowDateTime", wintypes.DWORD),
                    ("dwHighDateTime", wintypes.DWORD)]

    class PROCESS_MEMORY_COUNTERS(ctypes.Structure):
        _fields_ = [("cb", wintypes.DWORD), ("PageFaultCount", wintypes.DWORD),
                    ("PeakWorkingSetSize", ctypes.c_size_t),
                    ("WorkingSetSize", ctypes.c_size_t),
                    ("QuotaPeakPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaPeakNonPagedPoolUsage", ctypes.c_size_t),
                    ("QuotaNonPagedPoolUsage", ctypes.c_size_t),
                    ("PagefileUsage", ctypes.c_size_t),
                    ("PeakPagefileUsage", ctypes.c_size_t)]

    def ft_int(ft):
        return (ft.dwHighDateTime << 32) | ft.dwLowDateTime

    try:
        kernel32.OpenProcess.restype = wintypes.HANDLE
        kernel32.OpenProcess.argtypes = [wintypes.DWORD, wintypes.BOOL, wintypes.DWORD]
        kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
        psapi.EnumProcesses.argtypes = [ctypes.POINTER(wintypes.DWORD),
                                        wintypes.DWORD, ctypes.POINTER(wintypes.DWORD)]
        psapi.GetModuleBaseNameW.argtypes = [wintypes.HANDLE, wintypes.HMODULE,
                                             ctypes.c_wchar_p, wintypes.DWORD]
        psapi.GetProcessMemoryInfo.argtypes = [wintypes.HANDLE, ctypes.c_void_p,
                                               wintypes.DWORD]
    except Exception:
        pass

    now = FILETIME()
    try:
        kernel32.GetSystemTimeAsFileTime(ctypes.byref(now))
    except Exception:
        return []
    now_i = ft_int(now)

    access = 0x1000 | 0x0400 | 0x0010  # limited info | query info | vm read
    arr = None
    n = 0
    for attempt in range(2):
        count = 4096 if attempt == 0 else 16384
        arr = (wintypes.DWORD * count)()
        needed = wintypes.DWORD()
        try:
            if not psapi.EnumProcesses(arr, ctypes.sizeof(arr), ctypes.byref(needed)):
                return []
        except Exception:
            return []
        n = needed.value // ctypes.sizeof(wintypes.DWORD)
        if n < count:
            break
    pids = list(arr)[:n] if arr is not None else []

    procs = []
    for pid in pids:
        if not pid:
            continue
        try:
            handle = kernel32.OpenProcess(access, False, pid)
            if not handle:
                continue
            try:
                creation, exit_, kern, user = FILETIME(), FILETIME(), FILETIME(), FILETIME()
                if not kernel32.GetProcessTimes(handle, ctypes.byref(creation),
                                                ctypes.byref(exit_), ctypes.byref(kern),
                                                ctypes.byref(user)):
                    continue
                cpu_s = (ft_int(kern) + ft_int(user)) / 1e7
                elapsed = (now_i - ft_int(creation)) / 1e7
                cpu_pct = round(cpu_s / elapsed * 100.0, 1) if elapsed > 0 else 0.0
                counter = PROCESS_MEMORY_COUNTERS()
                counter.cb = ctypes.sizeof(counter)
                rss = 0
                if psapi.GetProcessMemoryInfo(handle, ctypes.byref(counter), counter.cb):
                    rss = counter.WorkingSetSize
                buf = ctypes.create_unicode_buffer(260)
                name = ""
                if psapi.GetModuleBaseNameW(handle, None, buf, len(buf)):
                    name = buf.value
                mem_pct = round(rss / mem_total * 100.0, 1) if mem_total else 0.0
                procs.append({"pid": int(pid), "name": name or ("pid %d" % pid),
                              "cpu_pct": cpu_pct, "mem_pct": mem_pct})
            finally:
                kernel32.CloseHandle(handle)
        except Exception:
            continue
    return procs


def metrics_from(fs, prev_cpu, prev_net, dt, mounts, ts):
    snapshot = {"ts": int(ts), "host": socket.gethostname()}
    if os.name == "nt":
        cur_cpu, cur_net = _metrics_windows(snapshot, prev_cpu)
    else:
        cur_cpu, cur_net = _metrics_linux(snapshot, fs, prev_cpu, prev_net, dt, mounts)
    snapshot["kernel"] = platform.release() or platform.system()
    snapshot["arch"] = platform.machine() or ""
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


METRICS_KEYS = ("ts", "host", "os", "uptime_s", "cpu", "memory", "disks", "network")


def token_ok(provided, expected):
    if not provided or not expected:
        return False
    return hmac.compare_digest(provided, expected)


def summary_from(snapshot):
    if not snapshot:
        return {"host": None, "os": None, "uptime_s": 0, "ts": 0}
    return {k: snapshot.get(k) for k in ("host", "os", "uptime_s", "ts")}


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


class _Handler(BaseHTTPRequestHandler):
    server_version = "servex-agent/1.1"

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
        registry = builtin_actions()
        self.actions = {name: registry[name]
                        for name in config.get("actions", []) if name in registry}


def make_server(config, ring, sampler):
    limiter = RateLimiter(config["rate_limit_per_min"])
    return ServexServer((config["bind"], config["port"]), config, ring, sampler, limiter)


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


def build_parser():
    p = argparse.ArgumentParser(prog="servex-agent")
    p.add_argument("--config")
    p.add_argument("--token")
    p.add_argument("--port", type=int)
    p.add_argument("--bind")
    p.add_argument("--tls-cert")
    p.add_argument("--tls-key")
    p.add_argument("--init", action="store_true")
    return p


def _lan_addresses(port):
    urls = ["http://127.0.0.1:%d" % port]
    seen = set(["127.0.0.1"])
    try:
        infos = socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)
    except OSError:
        infos = []
    for info in infos:
        ip = info[4][0]
        if ip and ip not in seen and not ip.startswith("127."):
            seen.add(ip)
            urls.append("http://%s:%d" % (ip, port))
    try:
        _, _, ips = socket.gethostbyname_ex(socket.gethostname())
    except OSError:
        ips = []
    for ip in ips:
        if ip and ip not in seen and not ip.startswith("127."):
            seen.add(ip)
            urls.append("http://%s:%d" % (ip, port))
    return urls


def print_connection_info(config, show_token=False):
    sys.stderr.write("servex-agent listening on %s:%d\n"
                     % (config["bind"], config["port"]))
    for url in _lan_addresses(config["port"]):
        sys.stderr.write("  %s\n" % url)
    if show_token:
        sys.stderr.write("  Token %s\n" % config.get("token", ""))


def init_config(path, port=None, bind=None):
    if os.path.exists(path):
        try:
            with open(path, "r", encoding="utf-8") as fh:
                cfg = json.load(fh)
            sys.stderr.write("config already exists at %s; leaving unchanged\n" % path)
            return cfg
        except (OSError, ValueError):
            pass
    cfg = json.loads(json.dumps(DEFAULTS))  # deep copy
    cfg["token"] = secrets.token_urlsafe(24)
    if port is not None:
        cfg["port"] = port
    if bind is not None:
        cfg["bind"] = bind
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(json.dumps(cfg, indent=2) + "\n")
    return cfg


def main(argv=None):
    args = build_parser().parse_args(argv)
    if args.init:
        path = args.config or "servex-agent.json"
        cfg = init_config(path, args.port, args.bind)
        print_connection_info(cfg, show_token=True)
        return 0
    overrides = {"token": args.token, "port": args.port, "bind": args.bind}
    config = load_config(args.config, overrides)
    if not config["token"]:
        sys.stderr.write("error: a token is required (--token, config, or SERVEX_TOKEN)\n")
        raise SystemExit(2)
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
    print_connection_info(config)
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        sampler.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
