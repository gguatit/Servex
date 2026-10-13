# servex-agent

Reference HTTP agent. Python 3.8+, standard library only.

## Install

    sudo cp servex-agent.service /etc/systemd/system/
    sudo mkdir -p /etc/servex && sudo cp servex-agent.json.example /etc/servex/servex-agent.json
    # edit /etc/servex/servex-agent.json and set a long random token
    sudo systemctl enable --now servex-agent

## API

Base path `/api/v1`. Every request: `Authorization: Bearer <token>`.

| Method | Path | Notes |
|--------|------|-------|
| GET | `/health` | liveness |
| GET | `/summary` | host/os/uptime |
| GET | `/metrics` | full snapshot |
| GET | `/history?metric=<cpu|memory|disk|network>&points=<n>` | ring buffer |
| POST | `/action` | allowlisted only; disabled by default |

History `v` per metric: `cpu` = usage %, `memory` = used %, `disk` = max mount %,
`network` = aggregate receive bytes/s across non-loopback interfaces.

## Config keys

`bind` (0.0.0.0), `port` (8765), `token`, `sample_interval` (2), `history_size` (150),
`disk_mounts` (["/"]), `actions` ([]), `tls {cert,key}`, `rate_limit_per_min` (240),
`max_body_bytes` (65536).

## Security

Token required; `hmac.compare_digest` comparison. Prefer HTTPS (set `tls` or put
the agent behind a TLS-terminating reverse proxy). Plain HTTP exposes the token
on the wire. See `SECURITY.md`.
