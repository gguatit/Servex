# Servex

A dark, minimal Android app for monitoring your own servers, with a small
zero-dependency Rust agent that exposes the metrics over HTTP.

- **App** — Android · Kotlin · Jetpack Compose · MVVM · OkHttp · kotlinx.serialization
- **Agent** — a single static Rust binary (no runtime, no dependencies)

Servex talks directly to your server. There is no cloud service and no account:
the app connects to an agent you run yourself, using a shared bearer token.

## How it works

```
  Servex app (Android)                     servex-agent (your server)
        |                                            |
        |  HTTPS + Bearer token                       |
        |------------------------------------------>|
        |  JSON: metrics / history / actions          |
        |<------------------------------------------|
```

The agent samples CPU, memory, disk and network from `/proc`, keeps a short
in-memory history ring buffer, and serves it over a small HTTP API. The app
polls one or more agents, classifies each server as normal / warning / error /
offline, and can raise threshold alerts and run allowlisted remote actions.

## Requirements

| Part | Requirement |
|---|---|
| Agent | Linux x86-64 or Windows x64 — no runtime needed |
| App | Android 15 (API 36) or newer |

## Run the agent

The agent runs on the server you want to monitor. Install it with a package,
or run the plain script. Either way the client only needs the agent's URL and
token.

**Linux (deb):**

```bash
sudo dpkg -i servex-agent_1.2.1_amd64.deb
# creates /etc/servex/servex-agent.json (mode 0600) with a random token
# read it with: sudo cat /etc/servex/servex-agent.json
sudo systemctl start servex-agent
```

**Windows (exe)** — `servex-agent-gui.exe` for a small window, or
`servex-agent.exe` for the console and the Windows service. From an elevated
prompt:

```powershell
servex-agent.exe install
servex-agent.exe start
# config: C:\ProgramData\Servex\servex-agent.json
```

**From source (Cargo):**

```bash
cargo build --release -p servex-agent
./target/release/servex-agent --init --config servex-agent.json
./target/release/servex-agent --config servex-agent.json
```

The agent listens on port 8765 by default. See [`docs/AGENT.md`](docs/AGENT.md)
for configuration, the metrics fields, and install details. The Rust source
lives in [`agent-rs/`](agent-rs/); [`packaging/build_deb.py`](packaging/)
builds the deb.

## Build the app

```bash
./gradlew :app:assembleDebug          # Linux / macOS
gradlew.bat :app:assembleDebug        # Windows
```

The debug APK is written to `app/build/outputs/apk/debug/`.

## Documentation

- [`docs/API.md`](docs/API.md) — agent HTTP API reference
- [`docs/AGENT.md`](docs/AGENT.md) — agent install and configuration
- [`SECURITY.md`](SECURITY.md) — security model and reporting

## License

GPL-3.0. See [`LICENSE`](LICENSE).

## Disclaimer

Servex is a monitoring tool. Use it only on servers you own or administer.
