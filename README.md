# Mini Java Terminal

> Java-based control plane for terminal workflows, managed runtime tools, secure access, and service operations inside restricted container-hosting environments.

[![Build](https://img.shields.io/badge/build-Maven%20%2B%20Java%2017-2ea44f?style=flat-square)](#build)
[![Status](https://img.shields.io/badge/status-development%20snapshot-f59e0b?style=flat-square)](#project-status)
[![License](https://img.shields.io/badge/license-MIT-0d74ce?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Linux%20containers-4c8bf5?style=flat-square)](#requirements)

Mini Java Terminal (MJT) is a Java application for running and managing terminal-oriented services where the host environment is restricted, minimal, or panel-managed. It keeps MJT-owned files inside its workspace, exposes a controlled command model, and provides optional integrations for tunnels, SSH/SFTP, website preview, Minecraft targets, and PRoot-based guest environments.

> **Development snapshot:** this branch is under active development. Commands, storage layout, and installer internals may change before a stable release.

---

## Table of Contents

- [Overview](#overview)
- [Why This Project Exists](#why-this-project-exists)
- [Core Features](#core-features)
- [Architecture](#architecture)
- [Directory Layout](#directory-layout)
- [Requirements](#requirements)
- [Build](#build)
- [Quick Start](#quick-start)
- [Command Model](#command-model)
- [Managed System Tools](#managed-system-tools)
- [Networking and Remote Access](#networking-and-remote-access)
- [Security Notes](#security-notes)
- [Third-Party Notices](#third-party-notices)
- [License](#license)
- [Contributing](#contributing)

---

## Overview

MJT is designed for hosts where you may have a Java runtime and a writable project directory, but do not necessarily have root access, a full Linux package manager, Docker, or unrestricted process control.

It provides a focused control plane for:

- controlled shell-command execution;
- Cloudflare Tunnel lifecycle and quick-tunnel workflows;
- SSH/SFTP access managed by MJT;
- gateway and website-preview services;
- Minecraft target process management;
- managed runtime prerequisites such as TAR, portable Python, PRoot, and `proot-distro`;
- isolated PRoot guest-environment workflows.

MJT is not a replacement for a full VM, Docker daemon, or host package manager. It is intended to make limited hosting environments more usable while keeping installation and runtime state under the project workspace.

---

## Why This Project Exists

Restricted game-panel, Java-container, and lightweight hosting environments often provide only part of a normal Linux server experience:

- no root shell;
- no `apt`, `apk`, or `dnf` access;
- no direct file manager or SFTP;
- no permanent public port;
- no Docker daemon;
- limited startup-command control.

MJT provides a structured way to operate services within those constraints without requiring the application to own the entire host.

---

## Core Features

### Terminal and command control

- Command namespaces that distinguish MJT commands from shell commands.
- Controlled shell execution through `.command <shell-command>`.
- Built-in help index through `.mjt help` or `.help`.
- MJT-owned runtime files and persistent settings.

### Tunnel, website, and gateway tools

- Cloudflared discovery and managed installation.
- Cloudflare Tunnel integration.
- Website-preview and gateway workflows.
- Optional public routing through supported tunnel modes.

### SSH and SFTP

- Embedded SSH server lifecycle management.
- SSH/SFTP access for MJT-managed workspace use cases.
- Configurable host, port, user, and authentication settings.

### Managed system runtime

- TAR discovery and managed bootstrap.
- Portable Python installation for supported Linux targets.
- Native PRoot installation.
- Upstream `proot-distro` installation and execution through an MJT-managed launcher.
- Linux host detection using kernel data, CPU architecture, bitness, libc, and `/etc/os-release`.

### Guest environments

- PRoot-based Linux environment workflows.
- Isolated XDG data and cache locations under MJT-controlled storage.
- Catalog, install, activation, and lifecycle operations for supported guest environments.

---

## Architecture

```text
User / Panel Console
        │
        ▼
Mini Java Terminal
        │
        ├── Command Dispatcher
        ├── Shell / Service Controllers
        ├── SSH / SFTP Server
        ├── Cloudflared / Tunnel Services
        ├── Gateway / Website Services
        ├── Minecraft Process Services
        └── Managed Runtime Services
                ├── TAR
                ├── Portable Python
                ├── PRoot
                └── proot-distro
```

MJT controls its own files, commands, state, and child processes. It does not attempt to replace the host operating system or bypass hosting-provider restrictions.

---

## Directory Layout

The exact directory structure may evolve during development. A typical MJT runtime layout is:

```text
MJT/
├── system/
│   ├── bin/                 # MJT-managed launchers and native helpers
│   ├── downloads/           # Verified temporary downloads
│   ├── python/
│   │   └── current/         # Portable Python runtime
│   └── proot-distro/        # Upstream runtime data, cache, and site packages
├── workspace/               # MJT-managed user/project workspace
├── logs/                    # Application and installer logs
└── state/                   # Persistent configuration/state
```

Do not manually delete managed runtime directories while their corresponding services are running.

---

## Requirements

### Build requirements

- Java 17 or newer
- Maven 3.9 or newer
- Git

### Runtime requirements

- Linux host environment
- Writable MJT project directory
- Outbound HTTPS access for managed downloads
- A supported CPU architecture for portable runtime components

Current managed Python/PRoot targets are intended for native:

- `x86_64` / `amd64`
- `aarch64` / `arm64`

MJT inspects:

- `uname -sr`
- `uname -m`
- `getconf LONG_BIT`
- libc indicators
- `/etc/os-release`

This allows installers to select the appropriate upstream runtime asset for the host.

---

## Build

```bash
git clone https://github.com/mjt-project/Mini-Java-Terminal.git
cd Mini-Java-Terminal
git checkout dev
mvn -U clean package
```

The built server artifact is generated under:

```text
target/server.jar
```

Run it with the startup command appropriate for the host panel or local environment:

```bash
java -jar target/server.jar
```

---

## Quick Start

After MJT starts:

```text
.mjt help
```

Install managed prerequisites as needed:

```text
.mjt system install tar
.mjt system install python
.mjt system install proot
.mjt system install proot-distro
```

Use shell commands through the command namespace:

```text
.command pwd
.command ls -la
.command java -version
```

The exact available commands depend on the current branch and enabled services.

---

## Command Model

MJT separates internal commands from host-shell commands.

| Purpose | Example |
|---|---|
| MJT help | `.mjt help` |
| Shell command | `.command ls -la` |
| Start Minecraft target | `.mjt minecraft start` |
| Install TAR | `.mjt system install tar` |
| Install Python | `.mjt system install python` |
| Install PRoot | `.mjt system install proot` |
| Install proot-distro | `.mjt system install proot-distro` |

This separation reduces accidental execution of host commands and keeps administrative actions explicit.

---

## Managed System Tools

### TAR

Portable runtime archives are extracted through a TAR command rather than a custom archive parser.

Resolution order:

1. existing MJT-managed TAR launcher;
2. host `tar`, `bsdtar`, or BusyBox TAR;
3. supported host package-manager installation;
4. static fallback bootstrap selected for the detected Linux CPU architecture.

### Portable Python

MJT selects an upstream portable CPython asset that matches the detected Linux target triple. Downloads are verified against published SHA-256 metadata before installation.

### PRoot

MJT can install a native PRoot binary for supported Linux architectures and validates it before use.

### proot-distro

MJT uses upstream `proot-distro` as a separate component for rootless guest-environment management. MJT does not claim ownership of that upstream project.

See [Third-Party Notices](#third-party-notices) for licensing and attribution.

---

## Networking and Remote Access

### Cloudflare Tunnel

MJT can use cloudflared for tunnel workflows. Tunnel connectivity and public hostname routing depend on the Cloudflare configuration and the host network policy.

### SSH and SFTP

MJT can run an embedded SSH server for controlled workspace access. Always use a strong password or key-based authentication where supported, avoid exposing the service without a firewall/tunnel policy, and restart the SSH service after changing credentials.

---

## Security Notes

- Do not store tunnel tokens, passwords, or API credentials in source control.
- Keep runtime downloads checksum-verified.
- Prefer trusted upstream assets and pinned URLs where appropriate.
- Treat remote shell access as production-sensitive.
- Restrict public ports and use tunnels or access policies when possible.
- Review third-party licenses before bundling, modifying, or redistributing dependencies.

---

## Third-Party Notices

MJT may install, invoke, or integrate with third-party tools. Their licenses remain independent from the MJT source license.

### PRoot-Distro

MJT acknowledges and thanks the **Termux `proot-distro`** project:

- Upstream repository: <https://github.com/termux/proot-distro>
- Purpose: rootless Linux container management on Termux and regular Linux hosts
- Upstream license: GNU General Public License, version 3.0 (GPL-3.0)

`proot-distro` is a separate upstream component. When MJT downloads, executes, bundles, redistributes, modifies, or incorporates its code, the applicable GPL-3.0 obligations for that component must be preserved.

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the project notice and distribution guidance.

> **Important licensing boundary:** MJT's own original source is released under MIT. This does **not** relicense GPL-3.0 code. If GPL-3.0 source from `proot-distro` is copied into or linked as part of an MJT distribution, the resulting distribution may need to be licensed and distributed under GPL-3.0 terms. Keep GPL components separate unless you have reviewed the applicable obligations.

---

## License

MJT original source code is licensed under the [MIT License](LICENSE).

Third-party components retain their own licenses. In particular, upstream `proot-distro` is GPL-3.0 licensed; see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

---

## Contributing

This project is actively evolving. Before opening a pull request:

1. Build with Java 17.
2. Run `mvn -U clean package`.
3. Keep managed-runtime changes isolated and test on a supported Linux host.
4. Do not commit credentials, tunnel tokens, or generated runtime directories.
5. Include clear notes for changes affecting installers, storage layout, or public networking.

---

## Disclaimer

MJT is provided as-is, without warranty. Hosting providers may restrict processes, filesystems, networking, symlinks, ports, package managers, or executable permissions. Always comply with the terms and technical limits of the host platform.
