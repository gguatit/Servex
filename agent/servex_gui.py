#!/usr/bin/env python3
"""Tkinter front-end for the Servex agent (Windows).

The agent's HTTP server runs on a background thread, so closing the window only
hides it and monitoring keeps running. Use Quit to actually stop the agent.
Standard library only (tkinter); no extra dependencies.
"""
import importlib.util
import os
import sys
import threading

_AGENT_PATH = os.path.join(
    getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__))),
    "servex-agent.py")


def _load_agent():
    spec = importlib.util.spec_from_file_location("servex_agent", _AGENT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("could not load %s" % _AGENT_PATH)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


agent = _load_agent()


def _hide_console():
    """Hide the console window so only the GUI is visible (frozen exe)."""
    try:
        import ctypes
        hwnd = ctypes.windll.kernel32.GetConsoleWindow()
        if hwnd:
            ctypes.windll.user32.ShowWindow(hwnd, 0)
    except Exception:
        pass


def run(config_path):
    """Start the agent and show its window. Returns when the user quits."""
    import tkinter as tk
    from tkinter import ttk

    _hide_console()

    config = agent.load_config(config_path)
    token = config.get("token", "")
    port = config.get("port", 8765)
    urls = agent._lan_addresses(port) or ["http://127.0.0.1:%d" % port]

    state = {"error": ""}

    def serve():
        try:
            agent.main(["--config", config_path])
        except SystemExit:
            pass
        except Exception as exc:
            state["error"] = str(exc)

    thread = threading.Thread(target=serve, daemon=True)
    thread.start()

    root = tk.Tk()
    root.title("Servex Agent")
    root.geometry("560x360")
    root.minsize(480, 320)

    ttk.Label(root, text="Servex Agent is running",
              font=("Segoe UI", 14, "bold")).pack(padx=16, pady=(16, 2), anchor="w")
    ttk.Label(root, justify="left",
              text=("Closing this window only hides it - monitoring keeps running.\n"
                    "Use Quit to stop the agent.")).pack(padx=16, anchor="w")

    status_var = tk.StringVar(value="Listening on port %s" % port)
    ttk.Label(root, textvariable=status_var, foreground="#4C8DFF").pack(padx=16, pady=(8, 0), anchor="w")

    frame = ttk.Frame(root)
    frame.pack(fill="both", expand=True, padx=16, pady=12)

    ttk.Label(frame, text="Address (type this into the app)").pack(anchor="w")
    url_var = tk.StringVar(value=urls[0])
    ttk.Combobox(frame, textvariable=url_var, values=urls,
                 state="readonly").pack(fill="x")

    ttk.Label(frame, text="Token (type this into the app)").pack(anchor="w", pady=(10, 0))
    token_entry = ttk.Entry(frame)
    token_entry.insert(0, token)
    token_entry.configure(state="readonly")
    token_entry.pack(fill="x")

    def copy(value):
        root.clipboard_clear()
        root.clipboard_append(value)

    btns = ttk.Frame(frame)
    btns.pack(fill="x", pady=(12, 0))
    ttk.Button(btns, text="Copy address",
               command=lambda: copy(url_var.get())).pack(side="left")
    ttk.Button(btns, text="Copy token",
               command=lambda: copy(token)).pack(side="left", padx=8)

    def quit_app():
        try:
            root.destroy()
        except Exception:
            pass

    bottom = ttk.Frame(root)
    bottom.pack(fill="x", padx=16, pady=(0, 16))
    ttk.Button(bottom, text="Quit (stop the agent)",
               command=quit_app).pack(side="right")
    ttk.Button(bottom, text="Hide to taskbar",
               command=root.iconify).pack(side="right", padx=8)

    def poll():
        if state["error"]:
            status_var.set("Agent error: %s" % state["error"])
        elif not thread.is_alive():
            status_var.set("Agent stopped.")
        root.after(1000, poll)

    root.protocol("WM_DELETE_WINDOW", root.iconify)  # X hides, never quits
    root.after(1000, poll)
    root.mainloop()
    return 0


if __name__ == "__main__":
    sys.exit(run(sys.argv[1] if len(sys.argv) > 1 else None))
