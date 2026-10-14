#!/usr/bin/env python3
"""Build dist/ServexAgent.exe with PyInstaller (Windows only).

Installs pyinstaller and pywin32 first. The agent is loaded dynamically by
path, so servex-agent.py is added with --add-data (PyInstaller cannot see a
dynamic import). win32timezone is a hidden import required by pywin32.
"""
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ENTRY = os.path.join(ROOT, "agent", "servex_service.py")
AGENT = os.path.join(ROOT, "agent", "servex-agent.py")

# servex-agent.py is exec'd at runtime, so PyInstaller cannot see its imports.
# List them explicitly (plus win32timezone, required by pywin32).
HIDDEN = ["win32timezone", "argparse", "glob", "hmac", "json", "os",
          "platform", "re", "secrets", "shutil", "socket", "ssl",
          "subprocess", "sys", "threading", "time", "urllib.parse",
          "collections", "http.server", "ctypes", "ctypes.wintypes", "winreg",
          "tkinter", "tkinter.ttk", "tkinter.constants", "tkinter.font",
          "_tkinter"]


def run(cmd):
    print("+ " + " ".join(cmd))
    return subprocess.run(cmd, cwd=ROOT).returncode


def main():
    if os.name != "nt":
        print("build_exe.py must run on Windows")
        return 1
    rc = run([sys.executable, "-m", "pip", "install",
              "--disable-pip-version-check", "--quiet", "pyinstaller", "pywin32"])
    if rc != 0:
        print("ERROR: could not install pyinstaller/pywin32 (no network?). "
              "Nothing was built or faked.")
        return rc
    icon = os.path.join(ROOT, "assets", "servex.ico")
    cmd = [sys.executable, "-m", "PyInstaller", "--onefile", "--noconfirm",
           "--name", "ServexAgent", "--distpath", "dist",
           "--workpath", "build/pyinstaller", "--specpath", "build/pyinstaller",
           "--windowed"]
    if os.path.exists(icon):
        cmd += ["--icon", icon]
    for mod in HIDDEN:
        cmd += ["--hidden-import", mod]
    cmd += ["--add-data", AGENT + os.pathsep + ".", ENTRY]
    rc = run(cmd)
    if rc != 0:
        print("ERROR: PyInstaller failed")
        return rc
    exe = os.path.join(ROOT, "dist", "ServexAgent.exe")
    print("wrote %s (%d bytes)" % (exe, os.path.getsize(exe)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
