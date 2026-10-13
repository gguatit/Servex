# Security Policy

## Reporting a vulnerability

Please report security issues through GitHub Security Advisories
("Report a vulnerability" on the repository's Security tab) rather than a public
issue. We will acknowledge reports within 7 days.

## Threat model

| Asset | Protection |
|---|---|
| Metrics in transit | HTTPS recommended; self-signed supported via SPKI pin |
| Bearer token (in transit) | Sent in the `Authorization` header; constant-time compared |
| Bearer token (at rest, app) | Stored in `EncryptedSharedPreferences` (AES-256) |
| Server identity | Optional SPKI SHA-256 certificate pin |
| Remote actions | Allowlist only; disabled by default |

## Hardening recommendations

- Always set a long, random token. The agent refuses to start without one.
- Prefer HTTPS. Plain HTTP exposes the token to anyone on the network; use it
  only on a trusted LAN.
- Bind the agent to a narrow address where possible, and put it behind a
  reverse proxy if it is reachable from the internet.
- Keep the action allowlist as small as possible.
- Rotate tokens if a device is lost.

## Agent defaults

The agent runs with a per-IP rate limit, a maximum request body size, and a
constant-time token comparison. Remote actions are disabled until you add
entries to the `actions` allowlist.

## Out of scope

- Compromised servers or a compromised Android device.
- Attacks that require physical access to the device.
