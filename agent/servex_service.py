#!/usr/bin/env python3
"""Windows service host and exe entry point for the Servex agent.

Loads servex-agent.py by path (its filename has a hyphen, so it cannot be
imported normally) and provides a pywin32 service that runs the agent as a
child process so it can be stopped cleanly.

Usage (frozen exe or python):
    ServexAgent.exe --init --config <path>   run the agent once to init
    ServexAgent.exe --config <path>          run the agent in the console
    ServexAgent.exe install                  install the Windows service
    ServexAgent.exe start | stop | remove    control the service
"""
import importlib.util
import json
import os
import secrets
import subprocess
import sys
import threading

try:
    import win32service
    import win32serviceutil
    import servicemanager
except ImportError:  # pywin32 missing (or non-Windows): console mode still works
    win32service = win32serviceutil = servicemanager = None

try:
    import servex_gui
except Exception:  # tkinter missing or not bundled
    servex_gui = None

SERVICE_COMMANDS = ("install", "update", "remove", "start", "stop",
                    "restart", "debug")
_NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)


def _base_dir():
    if getattr(sys, "frozen", False):
        return getattr(sys, "_MEIPASS", os.path.dirname(sys.executable))
    return os.path.dirname(os.path.abspath(__file__))


AGENT_PATH = os.path.join(_base_dir(), "servex-agent.py")


def _load_agent():
    spec = importlib.util.spec_from_file_location("servex_agent", AGENT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


agent = _load_agent()

DEFAULT_CONFIG_DIR = os.path.join(
    os.environ.get("ProgramData", r"C:\ProgramData"), "Servex")
DEFAULT_CONFIG = os.path.join(DEFAULT_CONFIG_DIR, "servex-agent.json")


def ensure_config(path=None):
    path = path or DEFAULT_CONFIG
    if not os.path.exists(path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        agent.init_config(path)
        return path
    # The file may exist with an empty token; fill one in so the agent can start.
    try:
        cfg = agent.load_config(path)
        if not cfg.get("token"):
            cfg["token"] = secrets.token_urlsafe(24)
            with open(path, "w", encoding="utf-8") as fh:
                fh.write(json.dumps(cfg, indent=2) + "\n")
    except Exception:
        pass
    return path


if win32serviceutil is not None:

    class ServexAgentService(win32serviceutil.ServiceFramework):
        _svc_name_ = "ServexAgent"
        _svc_display_name_ = "Servex Agent"
        _svc_description_ = (
            "Collects and serves system metrics over HTTP for the Servex app "
            "at http://<host>:8765. Requires a bearer token from the config.")

        def __init__(self, args):
            super().__init__(args)
            self._stop = threading.Event()
            self._proc = None

        def SvcStop(self):
            self.ReportServiceStatus(win32service.SERVICE_STOP_PENDING)
            self._stop.set()
            if self._proc is not None and self._proc.poll() is None:
                self._proc.terminate()
            self.ReportServiceStatus(win32service.SERVICE_STOPPED)

        def SvcDoRun(self):
            servicemanager.LogInfoMsg("Servex Agent starting")
            config = ensure_config()
            try:
                token = agent.load_config(config).get("token", "")
            except Exception:
                token = ""
            servicemanager.LogInfoMsg("Servex Agent config: %s" % config)
            if token:
                servicemanager.LogInfoMsg("Servex Agent token: %s" % token)
            # A service has no console, so give the child a real stdout/stderr
            # (a log file) instead of None handles, which crash its logging.
            log_path = os.path.join(DEFAULT_CONFIG_DIR, "servex-agent.log")
            try:
                log = open(log_path, "a", encoding="utf-8")
            except Exception:
                log = subprocess.DEVNULL
            self._proc = subprocess.Popen(
                [sys.executable, "--run", "--config", config],
                stdin=subprocess.DEVNULL, stdout=log, stderr=log)
            self._proc.wait()
            try:
                log.close()
            except Exception:
                pass
            servicemanager.LogInfoMsg("Servex Agent stopped")

else:

    class ServexAgentService(object):
        def __init__(self, *args, **kwargs):
            raise RuntimeError(
                "pywin32 is required for Windows service commands; "
                "run: python -m pip install pywin32")


def _configured_port(config):
    try:
        return agent.load_config(config).get("port", 8765)
    except Exception:
        return 8765


def _add_firewall_rule(port):
    """Best effort: let the phone reach the agent through Windows Firewall."""
    try:
        subprocess.run(
            ["netsh", "advfirewall", "firewall", "add", "rule",
             "name=Servex Agent", "dir=in", "action=allow",
             "protocol=TCP", "localport=%s" % port],
            check=False, timeout=20, creationflags=_NO_WINDOW,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def _remove_firewall_rule():
    try:
        subprocess.run(
            ["netsh", "advfirewall", "firewall", "delete", "rule",
             "name=Servex Agent"],
            check=False, timeout=20, creationflags=_NO_WINDOW,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception:
        pass


def _message_box(text, error=False):
    """Native dialog, because the frozen exe has no console to print to."""
    if os.name != "nt":
        return
    try:
        import ctypes
        flags = 0x10 if error else 0x40  # MB_ICONERROR / MB_ICONINFORMATION
        ctypes.windll.user32.MessageBoxW(0, text, "Servex Agent", flags)
    except Exception:
        pass


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--run" in argv:
        return agent.main([a for a in argv if a != "--run"])
    if argv and argv[0].lower() in SERVICE_COMMANDS:
        if win32serviceutil is None:
            raise RuntimeError(
                "pywin32 is required for Windows service commands; "
                "run: python -m pip install pywin32")
        command = argv[0].lower()
        # Default to automatic start so the agent comes back after a reboot
        # (pywin32 registers services as manual unless --startup is given).
        if command == "install" and "--startup" not in argv:
            argv = argv + ["--startup", "auto"]
        if command in ("install", "start"):
            _add_firewall_rule(_configured_port(ensure_config()))
        if command == "remove":
            _remove_firewall_rule()
        try:
            win32serviceutil.HandleCommandLine(
                ServexAgentService, argv=[sys.argv[0]] + argv)
        except Exception as exc:
            _message_box(
                "Could not %s the Servex Agent service:\n%s\n\n"
                "Run this from an ADMIN PowerShell (right-click > Run as "
                "administrator)." % (command, exc), error=True)
            return 1
        if command in ("install", "start"):
            _message_box("Servex Agent service '%s' done.\n"
                         "It now starts automatically at boot." % command)
        return 0
    if not argv:
        # Run with no arguments (e.g. double-clicked): make sure a config with a
        # token exists, then show the GUI (or fall back to the console) so the
        # token and URLs are visible.
        config = ensure_config()
        if servex_gui is not None:
            return servex_gui.run(config)
        port = _configured_port(config)
        urls = "\n".join(agent._lan_addresses(port) or ["http://127.0.0.1:%d" % port])
        token = ""
        try:
            token = agent.load_config(config).get("token", "")
        except Exception:
            pass
        _message_box(
            "Servex Agent is starting.\n\n"
            "Address:\n%s\n\nToken:\n%s\n\n"
            "To run it without any window and start it at boot, open an ADMIN "
            "PowerShell and run:\n    ServexAgent.exe install\n    "
            "ServexAgent.exe start" % (urls, token))
        try:
            return agent.main(["--config", config])
        except OSError as exc:
            _message_box("Could not start the agent:\n%s\n\n"
                         "Port %s may already be in use." % (exc, port), error=True)
        except Exception as exc:
            _message_box("Could not start the agent:\n%s" % exc, error=True)
        return 1
    return agent.main(argv)


if __name__ == "__main__":
    sys.exit(main())
