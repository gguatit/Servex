# Servex — Personal Server Monitoring App

- **Status:** Approved design (ready for implementation planning)
- **Date:** 2026-10-13
- **Author:** gguatit
- **License:** GPL-3.0

## 1. Overview

Servex is an Android app for monitoring your own servers, paired with a
lightweight reference HTTP agent written in Python 3 (standard library only,
zero third-party packages). The app connects directly to the agent over
HTTP(S) using a JSON API and a per-server Bearer token.

The app is dark-first, minimal, technical, and calibrated for quick reading on
a small screen. It is intended to be released as open source so that other
people can run both the app and the agent on their own machines.

## 2. Goals and Non-Goals

### Goals (v1)

- Multi-server list with an at-a-glance status summary.
- Per-server detail view with current metrics and time-series graphs for
  CPU / MEM / DISK / NET.
- Server create / edit / delete (CRUD).
- Automatic refresh while the app is in the foreground.
- Threshold alerts delivered as system notifications.
- Remote actions against a server through a strict allowlist.
- Low runtime memory footprint and minimal dependencies (no reflection).
- Open-source quality: docs (English), tests, license, clean project layout.

### Non-Goals (v1)

- SSH transport (rejected: heavy and fragile on Android).
- Any cloud service or account backend. The app talks to servers directly.
- Persistent history beyond the agent's in-memory ring buffer.
- Push notifications, multi-user, iOS.

## 3. Architecture

Two components communicate over a small HTTP+JSON contract.

```
+---------------------+          HTTP(S) + Bearer token         +----------------------+
|   Servex (Android)  |  ------------------------------------>  |  servex-agent (Py3)  |
|  Compose + MVVM     |  <------------------------------------  |  stdlib only         |
+---------------------+     /api/v1/{metrics,history,action}    +----------------------+
                                                                  reads /proc, statvfs
```

- The app polls each configured server; a failed server is only marked
  **offline** — it never crashes the app.
- The agent exposes one reference implementation of the contract. Anyone may
  re-implement the agent in another language as long as the contract holds.

## 4. API Contract

- **Base path:** `/api/v1`
- **Auth:** every request carries `Authorization: Bearer <token>`. Missing or
  invalid token → `401`. Comparison is constant-time.
- **Transport:** HTTPS recommended. Plain HTTP is allowed for LAN use and the
  app must show a warning for it. Self-signed certificates are supported via an
  optional certificate fingerprint (SPKI) that the app can pin.

### 4.1 Endpoints

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/summary` | Host identity, OS, uptime, overall status |
| GET | `/api/v1/metrics` | Current snapshot (cpu, memory, disks[], network[]) |
| GET | `/api/v1/history?metric=<name>&points=<n>` | Time series from the ring buffer |
| POST | `/api/v1/action` | Execute an allowlisted action |
| GET | `/api/v1/health` | Liveness probe |

`metric` ∈ {`cpu`, `memory`, `disk`, `network`} (matching the metrics response
keys). `points` is clamped to the agent's buffer size.

### 4.2 `GET /api/v1/metrics` response

```json
{
  "ts": 1760000000,
  "host": "web-01",
  "os": "Ubuntu 24.04",
  "uptime_s": 382100,
  "cpu": { "usage_pct": 37.2, "cores": 8, "load1": 1.42, "load5": 1.10, "load15": 0.98 },
  "memory": { "total": 16777216000, "used": 9021000000, "available": 7756216000, "pct": 53.8 },
  "disks": [ { "mount": "/", "total": 128000000000, "used": 51000000000, "pct": 39.8 } ],
  "network": [ { "iface": "eth0", "rx_bps": 1048576, "tx_bps": 524288, "rx_total": 123456789, "tx_total": 98765432 } ]
}
```

Units: bytes and bytes-per-second are integers; `*_pct` values are floats in
the range 0–100.

### 4.3 `GET /api/v1/history` response

```json
{
  "metric": "cpu",
  "interval_s": 2,
  "points": [ { "t": 1760000000, "v": 37.2 }, { "t": 1760000002, "v": 41.0 } ]
}
```

### 4.4 `POST /api/v1/action`

Request body:

```json
{ "action": "service_restart", "args": { "service": "nginx" } }
```

Response:

```json
{ "ok": true, "output": "restarted nginx" }
```

Only actions present in the agent's allowlist are accepted. Any other action →
`403`. Remote actions are disabled by default and must be enabled in the agent
config.

### 4.5 Status determination

A shared rule (documented, and unit-tested on both sides):

| Status | Condition |
|--------|-----------|
| normal (green) | every metric below the warning threshold |
| warning (yellow) | any metric ≥ warning threshold (default 75%) |
| error (red) | any metric ≥ critical threshold (default 90%) |
| offline (gray) | no response / timeout / auth failure |

Thresholds are configured in the app (Settings); the agent does not compute
status.

### 4.6 Error responses

`401` unauthorized, `403` forbidden action, `404` unknown route, `429` rate
limited, `5xx` server error. Bodies are `{ "error": "<message>" }`.

## 5. App Data Model

- `Server` — `id` (local UUID), `name`, `baseUrl`, `tokenRef`, optional
  `certFingerprint`, `pollIntervalS`, `enabled`.
- `MetricsSnapshot` — mirrors the metrics response plus `receivedAt` and a
  computed `status`.
- `HistoryPoint` — `t`, `v`.
- `AlertThreshold` — `warningPct` (default 75), `criticalPct` (default 90).
- `ServerStatus` — enum { `NORMAL`, `WARNING`, `ERROR`, `OFFLINE` }.

## 6. App Architecture

- **Module:** single Gradle module `:app`. No multi-module split.
- **Pattern:** MVVM + Kotlin `StateFlow`. Screens collect with
  `collectAsStateWithLifecycle`.
- **Layers:**

```
ui/       Compose screens, components/, theme/{Color,Type,Theme}.kt
domain/   models + pure status logic (unit-tested)
data/     ServerRepository, ServexApi (OkHttp + kotlinx.serialization),
          SettingsStore (DataStore)
di/       AppContainer (manual DI; no Hilt)
worker/   AlertWorker (WorkManager)
```

- **DI:** manual `AppContainer` — avoids Hilt's runtime/setup cost.
- **Persistence:** server list and settings in `DataStore` (JSON via
  kotlinx.serialization). Tokens in `EncryptedSharedPreferences`
  (`androidx.security:security-crypto`). No Room.
- **Charts:** custom Compose `Canvas` composables (`Sparkline` for cards,
  `LineChart` for detail, `LinearProgressIndicator` for disks). No chart
  library — keeps size and style consistent.
- **Refresh:** while the screen is in the foreground, poll the active server
  every `pollIntervalS` (default 5s). Cancel the coroutine on background.

### 6.1 Dependencies added

`okhttp`, `kotlinx-serialization-json`, `androidx.datastore:preferences`,
`androidx.navigation:navigation-compose`, `androidx.work:work-runtime-ktx`,
`androidx.security:security-crypto`, `androidx.lifecycle:lifecycle-runtime-compose`.

### 6.2 Toolchain (existing project)

AGP 9.4.1, Kotlin 2.2.10, Gradle 9.6.0, Compose BOM 2026.02.01, compileSdk 37,
minSdk 36, targetSdk 37, Java 11. Release build uses the AGP 9
`buildTypes.release.optimization` DSL with `packageScope` for
`androidx.**`, `kotlin.**`, `kotlinx.**`.

## 7. Screens and UI

Four screens, routed with `navigation-compose`.

### 7.1 Overview

Purpose: overall status first, then server selection.

```
+------------------------------+
| Servex                 ⟳  ⚙ |
+------------------------------+
| 3 normal  1 warning  1 offline|   summary strip
+------------------------------+
| +--------------------------+ |
| | web-01            normal  | |
| | 10.0.0.5                  | |
| | CPU 37%  MEM 54%  ~/\/\~  | |   sparkline
| +--------------------------+ |
| + db-01 ...        warning  + |
+------------------------------+
```

### 7.2 Server Detail

Purpose: key numbers, then status, then detail.

```
+------------------------------+
| <- web-01           normal    |
|   10.0.0.5 . Ubuntu . up 4d   |
+------------------------------+
|   37%      54%      40%       |
|   CPU      MEM      DISK      |   large numbers
+------------------------------+
| CPU  /\/\_/\___               |
| MEM  ~~~~~~                   |   LineChart
| NET  rx - tx -                |
+------------------------------+
| Disks  /  39.8% [####..]      |
| Actions  [Restart] [Info]     |
+------------------------------+
```

### 7.3 Server Add / Edit

Form: name, base URL, token, optional certificate fingerprint, poll interval.
Includes a "Test connection" button.

### 7.4 Settings

Theme (dark default), refresh interval, alert thresholds (warning/critical),
alerts on/off, about + license (GPL-3.0).

### 7.5 Components

`ServerCard`, `StatusChip`, `MetricRow`, `BigMetric`, `Sparkline`, `LineChart`,
`SectionHeader`, `ActionButton`.

## 8. Design System

### 8.1 Color tokens

| Token | Value | Use |
|-------|-------|-----|
| background | `#0F1115` | screen background (dark) |
| surface | `#1A1D23` | cards (slightly lighter) |
| surfaceVariant | `#232732` | divided areas |
| onSurface | `#E6E9EF` | primary text (bright) |
| onSurfaceVariant | `#8A9099` | secondary text (gray) |
| primary | `#4C8DFF` | accent (blue) |
| success | `#3DDC84` | normal (green) |
| warning | `#FFC93C` | warning (yellow) |
| error | `#FF5C5C` | error (red) |
| offline | `#6B7280` | offline (gray) |

### 8.2 Typography and dimensions

- Typeface: default sans (Roboto).
- Hierarchy: `titleLarge` (bold) → numeric style `headlineMedium` with tabular
  figures for large metrics → `bodyMedium` → `labelSmall` (low-emphasis
  secondary).
- Spacing scale: 4 / 8 / 12 / 16 / 24 dp.
- Corner radius: cards 12dp, buttons 10dp. Icons 20/24dp.
- Icons: Material Symbols Outline. Status: `check_circle` / `warning` /
  `error` / `cloud_off`. Metrics: `memory` (CPU) / `ram` (MEM) /
  `hard_drive` (DISK) / `swap_horiz` (NET).

### 8.3 Status representation

A `StatusChip` component always renders **color + icon + text** together. This
enforces the "never distinguish by color alone" rule at the component level.

## 9. Alerts

- `WorkManager` `PeriodicWorkRequest`, **minimum 15-minute period** (Android
  constraint); Doze may delay it further. Documented for users.
- The worker polls all enabled servers and raises a notification (channel
  "Servex Alerts") when a threshold is breached.
- Notifications fire only when a server's status **changes** (no repeats).
- Thresholds and on/off are read from Settings.

## 10. Remote Actions

- `POST /api/v1/action` executes allowlisted actions only (e.g.
  `service_restart` with a service argument, `system_info`). Arbitrary shell
  execution is forbidden. Disabled by default; enabled via agent config.
- The app shows a confirmation dialog for destructive actions and displays the
  result or error.

## 11. Security

- Token required (Bearer), constant-time comparison, `401` otherwise. One token
  per server.
- HTTPS recommended; self-signed supported via optional fingerprint pinning.
  Plain HTTP is LAN-only and surfaces a warning in the app.
- Agent: simple rate limiting and a maximum request body size; bind address is
  configurable (default `0.0.0.0` with token; reverse proxy recommended).
- App stores tokens in `EncryptedSharedPreferences`.
- `docs/SECURITY.md` documents the threat model and hardening recommendations.

## 12. Agent Reference Implementation

- Single file `agent/servex-agent.py`, external dependencies **none**, Python
  3.8+.
- Config `agent/servex-agent.json`: `port` (default 8765), `bind`,
  `token`, `sample_interval` (default 2s), `history_size` (default 150, ≈5 min),
  `actions` (allowlist, empty by default), `tls { cert, key }`.
- Collectors parse `/proc/stat` (CPU), `/proc/meminfo` (memory),
  `os.statvfs` (disks), `/proc/net/dev` (network), `os.getloadavg`, uptime.
- A sampler thread fills a `deque(maxlen=history_size)` ring buffer; served by
  `ThreadingHTTPServer`.
- Ship `agent/servex-agent.service` (systemd unit example) and
  `agent/test_agent.py` (stdlib `unittest`: parsers, ring buffer, auth,
  endpoints).

## 13. Repository Layout

```
Servex/
├─ app/          Android module :app (existing)
├─ agent/        Python agent + systemd unit + tests
├─ docs/         README.md, API.md, AGENT.md, SECURITY.md
├─ LICENSE       GPL-3.0
└─ .gitignore
```

- Repo: **Servex**, **Public**, license **GPL-3.0**.
- Before committing, set local git identity:
  `user.name=gguatit`, `user.email=<GitHub noreply>`.

## 14. Testing

- **App:** JUnit4 unit tests for the pure status logic in `domain`. Minimal UI
  tests.
- **Agent:** stdlib `unittest` for parsers, ring buffer, auth, and endpoints.
- **Manual:** run the agent locally, connect the emulator app, verify all four
  screens and the offline path.

## 15. Implementation Milestones

1. Agent + tests.
2. App data layer (models / API / storage) + tests.
3. Design tokens + shared components + Overview.
4. Detail + charts.
5. Server CRUD + Settings.
6. Alerts + remote actions.
7. Docs / license / packaging.
8. GitHub push.

## 16. Risks and Notes

- Background alert cadence is bounded by Android (≥15 min, Doze delays).
- Plain-HTTP LAN usage is a documented, user-accepted risk.
- The agent is a reference implementation; the contract is the compatibility
  boundary, not this specific code.
