# Servex

A dark, minimal Android app for monitoring your own servers, with a small
zero-dependency Python agent that exposes the metrics over HTTP.

- **App** — Android · Kotlin · Jetpack Compose · MVVM · OkHttp · kotlinx.serialization
- **Agent** — a single Python 3 file (standard library only)

Servex talks directly to your server. There is no cloud service and no account:
the app connects to an agent you run yourself, using a shared bearer token.

## How it works

```
  Servex app (Android)                     servex-agent.py (your server)
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
| Agent | Python 3.8+ (standard library only) on the monitored server |
| App | Android 15 (API 36) or newer |

## Run the agent

```bash
cp agent/servex-agent.json.example agent/servex-agent.json
# edit the token in agent/servex-agent.json
python3 agent/servex-agent.py --config agent/servex-agent.json
```

The agent listens on port 8765 by default. See [`docs/AGENT.md`](docs/AGENT.md)
for configuration and [`agent/servex-agent.service`](agent/servex-agent.service)
for a systemd unit.

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
