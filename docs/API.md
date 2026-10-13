# Servex agent HTTP API

The agent exposes a small JSON API. All routes are under `/api/v1`.

## Authentication

Every request must send a bearer token:

```
Authorization: Bearer <token>
```

The token is compared in constant time. A missing or incorrect token returns
`401 Unauthorized`. One token per server.

## Transport

HTTPS is recommended. Plain HTTP is allowed for LAN-only setups and surfaces a
warning in the app. Self-signed certificates are supported by pinning the
certificate SPKI SHA-256 fingerprint in the app.

## Endpoints

| Method | Path | Description |
|---|---|---|
| GET | `/api/v1/summary` | Host identity, OS, uptime, overall status |
| GET | `/api/v1/metrics` | Current snapshot (cpu, memory, disks, network) |
| GET | `/api/v1/history?metric=<name>&points=<n>` | Time series from the ring buffer |
| POST | `/api/v1/action` | Execute an allowlisted action |
| GET | `/api/v1/health` | Liveness probe |

`metric` is one of `cpu`, `memory`, `disk`, `network` (matching the keys in the
metrics response). `points` is clamped to the buffer size.

## `GET /api/v1/metrics`

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

Units: `total`, `used`, `available`, `rx_total`, `tx_total` are bytes;
`rx_bps`, `tx_bps` are bytes per second; every `*_pct` is a float in `0–100`.

## `GET /api/v1/history`

```json
{
  "metric": "cpu",
  "interval_s": 2,
  "points": [ { "t": 1760000000, "v": 37.2 }, { "t": 1760000002, "v": 41.0 } ]
}
```

## `POST /api/v1/action`

Request:

```json
{ "action": "service_restart", "args": { "service": "nginx" } }
```

Response:

```json
{ "ok": true, "output": "restarted nginx" }
```

Only actions present in the agent's configured allowlist can be run. Remote
actions are disabled by default (the allowlist is empty). A non-allowlisted
action returns `403 Forbidden`.

## Status classification

The agent does not compute status; the app does:

| Status | Rule |
|---|---|
| normal | every metric is below the warning threshold |
| warning | any metric is at or above the warning threshold (default 75%) |
| error | any metric is at or above the critical threshold (default 90%) |
| offline | no response, timeout, or authentication failure |

Thresholds are configured in the app's Settings.

## Errors

| Code | Meaning |
|---|---|
| 401 | unauthorized (missing or bad token) |
| 403 | action not allowlisted |
| 404 | unknown route |
| 429 | rate limited |
| 5xx | server error |

Error bodies are `{ "error": "<message>" }`.
