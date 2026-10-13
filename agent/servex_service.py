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
import os
import subprocess
import sys
import threading

try:
    import win32service
    import win32serviceutil
    import servicemanager
except ImportError:  # pywin32 missing (or non-Windows): console mode still works
    win32service = win32serviceutil = servicemanager = None

SERVICE_COMMANDS = ("install", "update", "remove", "start", "stop",
                    "restart", "debug")


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
            self._proc = subprocess.Popen(
                [sys.executable, "--run", "--config", config])
            self._proc.wait()
            servicemanager.LogInfoMsg("Servex Agent stopped")

else:

    class ServexAgentService(object):
        def __init__(self, *args, **kwargs):
            raise RuntimeError(
                "pywin32 is required for Windows service commands; "
                "run: python -m pip install pywin32")


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    if "--run" in argv:
        return agent.main([a for a in argv if a != "--run"])
    if argv and argv[0].lower() in SERVICE_COMMANDS:
        if win32serviceutil is None:
            raise RuntimeError(
                "pywin32 is required for Windows service commands; "
                "run: python -m pip install pywin32")
        # Default to automatic start so the agent comes back after a reboot
        # (pywin32 registers services as manual unless --startup is given).
        if argv[0].lower() == "install" and "--startup" not in argv:
            argv = argv + ["--startup", "auto"]
        win32serviceutil.HandleCommandLine(
            ServexAgentService, argv=[sys.argv[0]] + argv)
        return 0
    return agent.main(argv)


if __name__ == "__main__":
    sys.exit(main())
