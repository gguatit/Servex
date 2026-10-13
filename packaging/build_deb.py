#!/usr/bin/env python3
"""Build dist/servex-agent_<version>_all.deb from the repo (stdlib only).

A .deb is an ar archive holding debian-binary, control.tar.gz and
data.tar.gz. This script writes all three by hand so it works on any
platform (including Windows) without dpkg/fakeroot.
"""
import argparse
import gzip
import hashlib
import io
import os
import tarfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AGENT_SRC = os.path.join(ROOT, "agent", "servex-agent.py")
LICENSE_SRC = os.path.join(ROOT, "LICENSE")
ICON_SRC = os.path.join(ROOT, "assets", "icon-256.png")

MAINTAINER = "gguatit <162287131+gguatit@users.noreply.github.com>"
HOMEPAGE = "https://github.com/gguatit/Servex"

SYSTEMD_UNIT = """[Unit]
Description=Servex monitoring agent
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/servex-agent --config /etc/servex/servex-agent.json
Restart=on-failure
RestartSec=3
User=root
NoNewPrivileges=true
ProtectSystem=full
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
"""

WRAPPER = """#!/bin/sh
exec /usr/bin/python3 /opt/servex/servex-agent.py "$@"
"""

DESKTOP = """[Desktop Entry]
Type=Application
Name=Servex Agent
Comment=Servex monitoring agent
Exec=/usr/bin/servex-agent
Icon=servex-agent
Terminal=true
Categories=Utility;System;
"""

COPYRIGHT = """This package is part of Servex.

Servex is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free
Software Foundation, either version 3 of the License, or (at your
option) any later version.

Servex is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
more details.

The full license text is available at:
https://www.gnu.org/licenses/gpl-3.0.txt
"""

POSTINST = """#!/bin/sh
set -e
if [ ! -f /etc/servex/servex-agent.json ]; then
  mkdir -p /etc/servex
  /usr/bin/python3 /opt/servex/servex-agent.py --init \\
      --config /etc/servex/servex-agent.json || true
fi
systemctl daemon-reload || true
systemctl enable servex-agent.service || true
echo "Servex agent installed."
echo "Edit /etc/servex/servex-agent.json, then: systemctl start servex-agent"
if [ -f /etc/servex/servex-agent.json ]; then
  token=$(sed -n 's/.*"token": *"\\([^"]*\\)".*/\\1/p' \\
      /etc/servex/servex-agent.json | head -n 1)
  if [ -n "$token" ]; then
    echo "Token: $token"
  fi
fi
exit 0
"""

PRERM = """#!/bin/sh
systemctl stop servex-agent.service || true
systemctl disable servex-agent.service || true
"""


def _read(path):
    with open(path, "rb") as fh:
        return fh.read()


def _add_dir(tf, name, mode=0o755):
    info = tarfile.TarInfo(name)
    info.type = tarfile.DIRTYPE
    info.mode = mode
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    info.mtime = 0
    tf.addfile(info)


def _add_file(tf, name, data, mode):
    info = tarfile.TarInfo(name)
    info.type = tarfile.REGTYPE
    info.size = len(data)
    info.mode = mode
    info.uid = info.gid = 0
    info.uname = info.gname = "root"
    info.mtime = 0
    tf.addfile(info, io.BytesIO(data))


def _tar_gz(entries):
    """entries: list of ('dir'|'file', name, mode, data).

    Return gzip bytes padded to an even length. A stored filename in the
    gzip header adds an odd number of bytes, flipping the parity, so the
    ar member size is always even (the ar spec allows odd sizes, but
    even sizes keep every member a fixed stride).
    """
    raw = io.BytesIO()
    with tarfile.open(fileobj=raw, mode="w", format=tarfile.GNU_FORMAT) as tf:
        for kind, name, mode, data in entries:
            if kind == "dir":
                _add_dir(tf, name, mode)
            else:
                _add_file(tf, name, data, mode)
    payload = raw.getvalue()
    for filename in (None, "xx"):
        buf = io.BytesIO()
        with gzip.GzipFile(fileobj=buf, mode="wb", filename=filename,
                           mtime=0) as gz:
            gz.write(payload)
        data = buf.getvalue()
        if len(data) % 2 == 0:
            return data
    return data


def _ar_header(name, size):
    header = "%-16s%-12d%-6d%-6d%-8s%-10d" % (name, 0, 0, 0, "100644", size)
    return header.encode("ascii") + b"\x60\n"


def _ar(members):
    out = bytearray(b"!<arch>\n")
    for name, data in members:
        out += _ar_header(name, len(data))
        out += data
        if len(data) % 2:
            out += b"\n"
    return bytes(out)


def _payload(version):
    agent = _read(AGENT_SRC)
    files = [
        ("opt/servex/servex-agent.py", 0o755, agent),
        ("lib/systemd/system/servex-agent.service", 0o644,
         SYSTEMD_UNIT.encode("utf-8")),
        ("usr/bin/servex-agent", 0o755, WRAPPER.encode("utf-8")),
        ("usr/share/doc/servex-agent/copyright", 0o644,
         COPYRIGHT.encode("utf-8")),
        ("usr/share/icons/hicolor/256x256/apps/servex-agent.png", 0o644,
         _read(ICON_SRC)),
        ("usr/share/applications/servex-agent.desktop", 0o644,
         DESKTOP.encode("utf-8")),
    ]
    return files


def _md5sums(files):
    lines = []
    for path, _mode, data in files:
        digest = hashlib.md5(data).hexdigest()
        lines.append("%s  %s" % (digest, path))
    return "\n".join(lines) + "\n"


def _control(version, installed_size):
    return (
        "Package: servex-agent\n"
        "Version: %s\n"
        "Section: admin\n"
        "Priority: optional\n"
        "Architecture: all\n"
        "Depends: python3 (>= 3.8)\n"
        "Maintainer: %s\n"
        "Homepage: %s\n"
        "Description: Servex monitoring agent\n"
        " Collects and serves system metrics over HTTP for the Servex app.\n"
        " Runs a small standard-library-only Python server on port 8765 and\n"
        " requires a bearer token.\n"
        "Installed-Size: %d\n"
        "License: GPL-3.0\n" % (version, MAINTAINER, HOMEPAGE, installed_size)
    )


def build(version, out_dir):
    files = _payload(version)
    installed_size = (sum(len(d) for _p, _m, d in files) + 1023) // 1024

    control_tar = _tar_gz([
        ("dir", "./", 0o755, b""),
        ("file", "./control", 0o644, _control(version, installed_size).encode("utf-8")),
        ("file", "./md5sums", 0o644, _md5sums(files).encode("utf-8")),
        ("file", "./postinst", 0o755, POSTINST.encode("utf-8")),
        ("file", "./prerm", 0o755, PRERM.encode("utf-8")),
    ])

    data_entries = [("dir", "./", 0o755, b"")]
    dirs = set()
    for path, mode, data in files:
        parts = path.split("/")
        for i in range(1, len(parts)):
            d = "./" + "/".join(parts[:i]) + "/"
            if d not in dirs:
                dirs.add(d)
                data_entries.append(("dir", d, 0o755, b""))
        data_entries.append(("file", "./" + path, mode, data))
    data_tar = _tar_gz(data_entries)

    deb = _ar([
        ("debian-binary/", b"2.0\n"),
        ("control.tar.gz/", control_tar),
        ("data.tar.gz/", data_tar),
    ])

    os.makedirs(out_dir, exist_ok=True)
    out_path = os.path.join(out_dir, "servex-agent_%s_all.deb" % version)
    with open(out_path, "wb") as fh:
        fh.write(deb)
    return out_path


def main(argv=None):
    parser = argparse.ArgumentParser(description="Build the Servex agent .deb")
    parser.add_argument("--version", default="1.1.0")
    parser.add_argument("--out", default="dist")
    args = parser.parse_args(argv)
    out_path = build(args.version, args.out)
    print("wrote %s (%d bytes)" % (out_path, os.path.getsize(out_path)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
