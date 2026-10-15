# servex-agent

Reference HTTP agent. A single static Rust binary, no runtime dependencies.

## Install on Linux (deb)

```sh
sudo dpkg -i servex-agent_1.2.1_amd64.deb
```

The `postinst` script creates `/etc/servex/servex-agent.json` (mode 0600) with
a random token (if it does not already exist), reloads systemd, and enables
the unit. The token is not printed to the install log; read it with
`sudo cat /etc/servex/servex-agent.json`. Edit the config if you need a fixed
port or token, then start the service:

```sh
sudo nano /etc/servex/servex-agent.json
sudo systemctl start servex-agent
systemctl status servex-agent
```

Enter the printed `http://<ip>:8765` URL and token into the app.

Files installed by the package:

| Path | Purpose |
|------|---------|
| `/usr/bin/servex-agent` | agent (static binary) |
| `/etc/servex/servex-agent.json` | config (created by `postinst`) |
| `/etc/servex/` | config directory |
| `/lib/systemd/system/servex-agent.service` | systemd unit |

## Install on Windows (exe)

Download `servex-agent.exe` (console + service) and `servex-agent-gui.exe`
(small window) from the releases page. From an **elevated** (Administrator)
prompt:

```powershell
servex-agent.exe install
servex-agent.exe start
```

The service reads its config from `C:\ProgramData\Servex\servex-agent.json`
(created with a random token on install). Read the token from that config
file, or print the connection info up front:

```powershell
servex-agent.exe --init --config "$env:ProgramData\Servex\servex-agent.json"
```

Enter the `http://<ip>:8765` URL and the token into the app. To remove the
service:

```powershell
servex-agent.exe stop
servex-agent.exe remove
```

The exe also runs the agent directly (no service) when given normal agent
flags, for example:

```powershell
servex-agent.exe --init --config "$env:ProgramData\Servex\servex-agent.json"
servex-agent.exe --config "$env:ProgramData\Servex\servex-agent.json"
```

`servex-agent-gui.exe` starts the agent and shows the addresses and token with
copy buttons. It has **Start with Windows** (per-user, no admin needed) and
**Allow in firewall** buttons, and its close button minimizes it instead of
stopping the agent.

## Run from source

```sh
cargo build --release -p servex-agent
./target/release/servex-agent --init --config servex-agent.json
./target/release/servex-agent --config servex-agent.json
```

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

## Metrics fields

`GET /api/v1/metrics` returns a snapshot. Every field below is optional and is
omitted when the platform or the host does not expose it.

| Group | Fields |
|-------|--------|
| system | `host`, `os`, `kernel`, `arch`, `uptime_s`, `boot_time`, `users` |
| cpu | `usage_pct`, `per_core`, `cores`, `threads`, `model`, `temp_c`, `load1`, `load5`, `load15` |
| memory | `total`, `used`, `available`, `pct`, `swap_total`, `swap_used`, `swap_pct`, `cached`, `buffers` |
| disks | `mount`, `total`, `used`, `pct` (per mount) |
| disk_io | `read_total`, `write_total`, `read_bps`, `write_bps` |
| network | `iface`, `rx_bps`, `tx_bps`, `rx_total`, `tx_total`, `rx_errs`, `rx_drops`, `tx_errs`, `tx_drops` |
| processes | `by_cpu`, `by_mem` (top 5: `pid`, `name`, `cpu_pct`, `mem_pct`) |
| services | `failed` (list), `total` |
| gpu | `name`, `util_pct`, `mem_pct`, `temp_c` (NVIDIA via `nvidia-smi`) |
| docker | `running`, `stopped`, `total` |

Notes:

- CPU per-core usage, CPU temperature and model come from `/proc/stat`,
  thermal/hwmon sensors and `/proc/cpuinfo`; they are absent on hosts without
  those interfaces.
- Memory `cached` and `buffers` come from `/proc/meminfo` (Linux only).
- Disk I/O totals come from `/proc/diskstats`; rates need two samples, so
  `read_bps` / `write_bps` appear only after the second scrape.
- Network error/drop counters come from `/proc/net/dev`.
- `gpu` requires `nvidia-smi`; `docker` requires the `docker` CLI.
- On Windows, CPU, memory, boot time and processes use the Win32 API; fields
  that only exist on Linux (`cached`, `buffers`, disk I/O, per-core from
  `/proc`) are skipped.

## Config keys

`bind` (0.0.0.0), `port` (8765), `token`, `sample_interval` (2), `history_size` (150),
`disk_mounts` (["/"]), `actions` ([]), `tls {cert,key}`, `rate_limit_per_min` (240, 0 = disabled),
`max_body_bytes` (65536).

## Security

Token required; constant-time comparison. Prefer HTTPS (set `tls` or put
the agent behind a TLS-terminating reverse proxy). Plain HTTP exposes the token
on the wire. See `SECURITY.md`.
