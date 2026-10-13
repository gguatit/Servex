# Servex Release & Documentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Servex repository presentable and usable by others: a README that gets a stranger running, the required public-facing documents (LICENSE, API reference, security policy), and the first GitHub push.

**Architecture:** Documentation-only plan. No product code changes. Produces four files (`README.md`, `LICENSE`, `docs/API.md`, `SECURITY.md`), a `.gitignore` audit, and the `gh` commands that create the public `Servex` repository and push `main`.

**Tech Stack:** Markdown, GPL-3.0 license text, `gh` CLI, `git`.

**Spec:** `docs/superpowers/specs/2026-10-13-servex-design.md` (see §13 Repository Layout, §4 API Contract, §11 Security)

## Global Constraints

- Repository name: `Servex`. Visibility: **Public**. License: **GPL-3.0**.
- Git author identity (already configured locally): `gguatit`, email `162287131+gguatit@users.noreply.github.com`.
- **English for all docs and README.** (The app itself ships English + Korean string resources; that is done in plan 02.)
- **No emoji anywhere** in docs, commits, or repository text.
- No secrets, tokens, keystores, or `local.properties` content in any committed file.
- The API reference must match the spec's API contract exactly (paths, auth header, snake_case field names, status thresholds 75/90).
- Do not invent features in the README that the code does not have; describe only what plans 01 and 02 build.

## Review Focus

1. **A stranger follows the README on a clean Linux box and the agent does not start** — every command must be copy-pasteable and complete (python version floor, token creation, port, systemd unit). (Task 1.)
2. **LICENSE is truncated or the wrong license** — the file must be the complete, unmodified GPL-3.0 text. (Task 2.)
3. **A secret gets committed** — `.gitignore` must exclude `local.properties`, `*.jks`, `*.keystore`, and the plan must instruct inspecting `git status` before the first push. (Task 5, Task 6.)
4. **API.md drifts from the spec** — paths, auth header, and field names must be copied verbatim from the spec, not re-invented. (Task 3.)
5. **Broken relative links** between README, `docs/API.md`, and `SECURITY.md`. (Task 1, Task 4.)
6. **The push targets the wrong visibility or account** — confirm the account is `gguatit` and visibility is public before pushing. (Task 6.)

---

### Task 1: README

**Files:**
- Create: `README.md`

**Interfaces:**
- Produces: the repository's front page. Every command shown must be copy-paste runnable; every relative link must resolve.

- [ ] **Step 1: Write the README**

```markdown
# Servex

A minimal, dark-first Android client for monitoring your own servers, plus a
zero-dependency Python agent that exposes the metrics it reads.

Servex has two parts:

- `agent/servex-agent.py` — a single-file Python 3 agent (standard library only)
  that reads CPU, memory, disk, and network metrics and serves them over HTTP.
- `app/` — an Android app (Jetpack Compose) that lists your servers, shows live
  metrics with graphs, and raises alerts when thresholds are crossed.

```
+-----------+     HTTP (Bearer token)     +-------------------+
|  Android  |  ------------------------>  |  servex-agent.py  |
|    app    |  <------------------------  |  (on your server) |
+-----------+   JSON  /api/v1/...         +-------------------+
```

## Requirements

- Server: Python 3.9 or newer. No third-party packages.
- App: Android 15 (API 36) or newer.

## Run the agent

```sh
# On the server you want to monitor
python3 agent/servex-agent.py --token "$(head -c 24 /dev/urandom | base64)" --port 8765
```

Keep the token; the app needs it. For a persistent install, see
`docs/AGENT.md` (systemd unit and TLS options).

## Build the app

```sh
# From the repository root
gradlew.bat :app:assembleDebug        # Windows
./gradlew :app:assembleDebug          # Linux / macOS
```

The debug APK is written to `app/build/outputs/apk/debug/`. In the app, add a
server with its base URL (`http://host:8765`) and the agent token.

## Documentation

- [`docs/API.md`](docs/API.md) — agent HTTP API reference.
- [`SECURITY.md`](SECURITY.md) — security model and reporting.

## License

Servex is released under the GNU General Public License v3.0. See
[`LICENSE`](LICENSE).

## Disclaimer

Servex is provided as-is, without warranty. You are responsible for the
security of the servers and networks you connect it to.
```

- [ ] **Step 2: Verify every relative link resolves**

Run (from the repository root): `Test-Path LICENSE, SECURITY.md, docs\API.md, docs\AGENT.md`
Expected: four `True` lines (these targets exist after Tasks 2-4; re-run this
step once they do).

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "docs: add README"
```

---

### Task 2: LICENSE (GPL-3.0)

**Files:**
- Create: `LICENSE`

**Interfaces:**
- Produces: the verbatim GPL-3.0 text that `README.md` links to.

- [ ] **Step 1: Fetch the canonical text**

Run: `curl -L https://www.gnu.org/licenses/gpl-3.0.txt -o LICENSE`
(If `curl` is unavailable, download the same URL in a browser and save it as
`LICENSE`.)

- [ ] **Step 2: Verify it is the real, complete license**

Run: `(Get-Content LICENSE | Measure-Object -Line).Lines; Select-String -Path LICENSE -Pattern "GNU GENERAL PUBLIC LICENSE","Version 3, 29 June 2007","END OF TERMS AND CONDITIONS"`
Expected: more than 600 lines, and all three patterns found. If the file is
short or the patterns are missing, the download failed — redo Step 1.

- [ ] **Step 3: Commit**

```bash
git add LICENSE
git commit -m "docs: add GPL-3.0 license"
```

---

---

### Task 3: API reference (docs/API.md)

**Files:**
- Create: `docs/API.md`

**Interfaces:**
- Produces: the agent HTTP API reference. Every claim must match `docs/superpowers/specs/2026-10-13-servex-design.md` §4 exactly; do not document features that do not exist.

- [ ] **Step 1: Write `docs/API.md`**

````markdown
# Servex Agent API

Base path: `/api/v1`. All responses are JSON.

## Authentication

Every request must send a bearer token:

```
Authorization: Bearer <token>
```

The agent compares the token in constant time. A missing or wrong token returns `401`.

## Transport

HTTPS is recommended. Plain HTTP is accepted for LAN-only use; the app shows a
warning when a server uses plain HTTP. Self-signed certificates are supported
through an optional SHA-256 SPKI pin configured per server in the app.

## Endpoints

### GET /api/v1/summary

Host identity and uptime.

```json
{ "host": "web-01", "os": "Ubuntu 24.04", "uptime_s": 382100, "ts": 1760000000 }
```

### GET /api/v1/metrics

Current snapshot. Integer byte values, `*_pct` values are `0`–`100`.

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

| Field | Unit |
|---|---|
| `memory.total`, `memory.used`, `memory.available`, `disks[].total`, `disks[].used` | bytes |
| `network[].rx_bps`, `network[].tx_bps` | bytes per second |
| `network[].rx_total`, `network[].tx_total` | bytes |
| `cpu.usage_pct`, `memory.pct`, `disks[].pct` | percent (0-100) |

### GET /api/v1/history

Time series from the agent's ring buffer.

| Query | Values | Notes |
|---|---|---|
| `metric` | `cpu`, `memory`, `disk`, `network` | matches the `/metrics` keys |
| `points` | integer | clamped to the ring-buffer size |

```json
{ "metric": "cpu", "interval_s": 2, "points": [ { "t": 1760000000, "v": 37.2 }, { "t": 1760000002, "v": 41.0 } ] }
```

### POST /api/v1/action

Execute an allowlisted action. Disabled by default; the operator must enable an
allowlist in the agent config.

Request:

```json
{ "action": "service_restart", "args": { "service": "nginx" } }
```

Response:

```json
{ "ok": true, "output": "restarted nginx" }
```

An action outside the allowlist returns `403`.

### GET /api/v1/health

Liveness probe. Returns `200` when the agent is running.

## Status rules

The agent does not compute status; the app derives it from the metrics:

- `normal` - every metric is below the warning threshold
- `warning` - any metric is at or above the warning threshold (default 75%)
- `error` - any metric is at or above the critical threshold (default 90%)
- `offline` - no response, timeout, or authentication failure

## Errors

| Code | Meaning |
|---|---|
| 401 | Missing or invalid token |
| 403 | Action not in the allowlist |
| 404 | Unknown route |
| 429 | Rate limited |
| 5xx | Server error |

Error body:

```json
{ "error": "unauthorized" }
```
````

- [ ] **Step 2: Verify no drift from the spec**

Open `docs/superpowers/specs/2026-10-13-servex-design.md` §4 and confirm every
field name, unit, endpoint, and status rule above matches. Fix any mismatch.

- [ ] **Step 3: Commit**

```bash
git add docs/API.md
git commit -m "docs: add agent API reference"
```

---

### Task 4: Security policy

**Files:**
- Create: `SECURITY.md`

**Interfaces:**
- Produces: the threat-model and reporting document linked from `README.md` as `[SECURITY.md]`. Lives at the repository root so that link resolves.

- [ ] **Step 1: Write `SECURITY.md`**

```markdown
# Security Policy

## Reporting a Vulnerability

Do not open a public issue for security problems. Report privately via GitHub
Security Advisories ("Report a vulnerability" on the repository's Security tab),
or email the maintainer listed on the GitHub profile. Include:

- affected component (agent or app) and version/commit,
- a description of the issue and its impact,
- steps to reproduce, and any proof-of-concept,
- your suggested fix, if you have one.

You should receive an acknowledgement within 7 days.

## Threat Model

Servex is a small self-hosted monitoring pair: a Python `agent` runs on each
monitored server, and an Android `app` polls it over HTTP(S). The agent is
assumed to run behind a trusted network boundary; the token is the primary
control.

| Asset | Protected by |
|---|---|
| Metrics data in transit | HTTPS (recommended); plain HTTP only on a trusted LAN |
| Bearer token | Constant-time comparison; random 24+ byte token |
| Token at rest (app) | `EncryptedSharedPreferences` (AES-256) |
| Server identity | Optional SPKI certificate pin (SHA-256) per server |
| Remote actions | Allowlist; disabled by default |

## Hardening Recommendations

- **Always set a token.** The agent refuses to start without one.
- **Use HTTPS.** Terminate TLS at a reverse proxy (nginx/Caddy) or configure the
  agent's `tls` block. Self-signed certificates are fine if you pin the SPKI
  fingerprint in the app.
- **Do not expose plain HTTP to the internet.** Plain HTTP is for LAN use only;
  the app shows a warning when a server URL is `http://`.
- **Bind narrowly.** Set `bind` in the agent config to a LAN address or
  `127.0.0.1` behind a proxy instead of `0.0.0.0` when possible.
- **Keep the action allowlist minimal.** Remote actions are off unless you list
  explicit, argument-constrained actions in the agent config.
- **Rotate tokens** if a device is lost; each server has one token.

## Out of Scope

- Denial of service against a self-hosted agent you control.
- Attacks that require an already-rooted device or host access.
```

- [ ] **Step 2: Verify the document is linked and present**

Run: `Test-Path SECURITY.md; Select-String -Path README.md -Pattern "SECURITY.md"`
Expected: `True`, and one matching line in `README.md`.

- [ ] **Step 3: Commit**

```bash
git add SECURITY.md
git commit -m "docs: add security policy"
```

---

### Task 5: Final `.gitignore` audit

**Files:**
- Modify: `.gitignore` (only if something is missing)

**Interfaces:**
- Produces: a repository whose secret and build artifacts cannot be accidentally committed.

- [ ] **Step 1: Verify the required patterns are present**

Run: `Select-String -Path .gitignore -Pattern "local.properties","\*.jks","\*.keystore","build/","__pycache__/","\*.pyc",".venv/"`
Expected: a match for each pattern.

- [ ] **Step 2: Add any missing entries**

If a pattern is missing, append it under a `# Servex` heading:

```gitignore
# Servex
local.properties
*.jks
*.keystore
__pycache__/
*.pyc
.venv/
venv/
build/
```

- [ ] **Step 3: Confirm no secret is tracked**

Run: `git status --porcelain; git ls-files | Select-String -Pattern "local.properties|\.jks$|\.keystore$|\.pyc$"`
Expected: no tracked secret/artifact paths in the `git ls-files` output.

- [ ] **Step 4: Commit (only if the file changed)**

```bash
git add .gitignore
git commit -m "chore: audit gitignore for secrets and build output"
```

---

### Task 6: First push to GitHub

**Files:**
- No file changes; publishes the repository.

**Interfaces:**
- Consumes: all prior commits.
- Produces: a public GitHub repository `gguatit/Servex`.

- [ ] **Step 1: Confirm the authenticated account**

Run: `gh auth status`
Expected: the active account is `gguatit`. Stop and switch accounts if it is not.

- [ ] **Step 2: Confirm a clean, secret-free tree**

Run: `git status; git log --oneline -6`
Expected: working tree clean; the spec, scaffold, icon, README, LICENSE, API, and security commits are present.

- [ ] **Step 3: Create the repository and push**

Run: `gh repo create Servex --public --source=. --remote=origin --push`
Expected: repository created and `main` pushed. (If `origin` already exists, use `git remote set-url origin <url>` then `git push -u origin main`.)

- [ ] **Step 4: Verify**

Run: `gh repo view gguatit/Servex --json name,visibility,url`
Expected: `"name":"Servex"`, `"visibility":"PUBLIC"`, and a URL is returned.

- [ ] **Step 5: (No further commit)** — the push publishes the existing commits.

---

## Notes for the implementer

- These three plans (01 agent, 02 app, 03 release) are executed in order. The
  release plan is last because `API.md`, `README.md`, and the push describe the
  finished agent and app.
- If the app's `docs/API.md` content and this plan's Task 3 diverge from the
  agent actually implemented in plan 01, the **agent** is the source of truth;
  update `docs/API.md` to match it in the same commit.
