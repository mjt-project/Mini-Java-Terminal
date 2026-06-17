# Mini Java Terminal

> Java-based control plane for terminal, website preview, Cloudflare Tunnel, SSH/SFTP, Gateway routing, Minecraft target process, and utility services inside restricted container hosting environments.

![Version](https://img.shields.io/badge/version-3.0.0--SNAPSHOT%2B1-blue)
![Status](https://img.shields.io/badge/status-SNAPSHOT-orange)
![Runtime](https://img.shields.io/badge/runtime-Java-green)

## Project Status

`3.0.0-SNAPSHOT+1` is a development snapshot. It is intended to stabilize the new MJT service layout, server workspace layout, guest quick tunnel flow, cloudflared auto-installer, and help index before the next stable release.

This snapshot is not a final production release.

---

## Table of Contents

- [Overview](#overview)
- [Why This Version Exists](#why-this-version-exists)
- [Core Features](#core-features)
- [Architecture](#architecture)
- [Directory Layout](#directory-layout)
- [Requirements](#requirements)
- [Build](#build)
- [Quick Start](#quick-start)
- [Command Model](#command-model)
- [Website Service](#website-service)
- [Guest Quick Tunnel](#guest-quick-tunnel)
- [Cloudflared Auto Installer](#cloudflared-auto-installer)
- [Cloudflare Tunnel Modes](#cloudflare-tunnel-modes)
- [Gateway Router](#gateway-router)
- [SSH/SFTP](#sshsftp)
- [Minecraft Target](#minecraft-target)
- [KeepAlive Bot](#keepalive-bot)
- [Configuration Files](#configuration-files)
- [Security Notes](#security-notes)
- [Troubleshooting](#troubleshooting)
- [Roadmap](#roadmap)
- [Changelog](#changelog)

---

## Overview

Mini Java Terminal, or MJT, is a Java application designed to run in restricted hosting environments where normal shell access, custom startup commands, or direct public ports may be limited.

MJT acts as a **control plane**. It manages local services, command routing, configuration, logs, tunnel processes, Minecraft server processes, and system utilities.

MJT does **not** try to replace Cloudflare Tunnel, SSHD, HTTP servers, or Minecraft servers. Instead, it coordinates them safely from one command interface.

---

## Why This Version Exists

Previous MJT builds mixed configuration, website files, runtime data, and service state in one place. This snapshot reorganizes the project so that MJT can grow into a stable service manager.

The main goals are:

```text
1. Keep MJT runtime/config inside /home/container/MJT.
2. Keep user/server data inside /home/container/server.
3. Support local multi-site HTTP hosting.
4. Publish guest websites through Cloudflare Quick Tunnel.
5. Make guest mode work without Cloudflare tokens.
6. Auto-install cloudflared based on OS and CPU architecture.
7. Keep Gateway focused on TCP routing instead of public web hosting.
8. Prepare for future custom domain and user/workspace support.
```

---

## Core Features

- Strict command routing with `.mjt` and `.command` namespaces.
- Local website hosting on `127.0.0.1` ports.
- Guest website creation with temporary `trycloudflare.com` public URL.
- Cloudflare Quick Tunnel support without account or token.
- Cloudflared binary downloader and checker.
- Cloudflare named tunnel support for advanced use cases.
- Gateway TCP router for Minecraft, SSH/SFTP, and manual TCP forwarding.
- Embedded SSH/SFTP server.
- Managed Minecraft target process.
- Minecraft KeepAlive bot service.
- Service-based config layout.
- Runtime logs and system task folders.

---

## Architecture

MJT is split into two main zones.

```text
/home/container/MJT
```

This is the MJT control area. It stores configuration, logs, runtime state, downloads, and internal service metadata.

```text
/home/container/server
```

This is the user/server data area. It stores website content and Minecraft server workspaces.

High-level flow for guest website publishing:

```text
Browser
  ↓
Cloudflare Quick Tunnel / trycloudflare.com
  ↓
cloudflared process inside container
  ↓
Local HTTP site on 127.0.0.1:<port>
  ↓
/home/container/server/website/www/guest/<guest-id>/main
```

---

## Directory Layout

Recommended runtime layout:

```text
/home/container/
├── MJT/
│   ├── core/
│   │   └── app.properties
│   │
│   ├── services/
│   │   ├── ssh/
│   │   ├── cloudflare/
│   │   │   ├── account.properties
│   │   │   ├── ddns-public-ipv4/
│   │   │   └── tunnel/
│   │   ├── http/
│   │   │   └── sites/
│   │   ├── tcp/
│   │   ├── gateway/
│   │   ├── minecraft/
│   │   ├── https/
│   │   └── bot/
│   │
│   ├── system/
│   │   ├── downloads/
│   │   │   └── cloudflared/
│   │   └── tasks/
│   │
│   ├── runtime/
│   │   ├── pids/
│   │   └── cache/
│   │
│   └── logs/
│
└── server/
    ├── website/
    │   └── www/
    │       ├── main/
    │       ├── docs/
    │       ├── panel/
    │       └── guest/
    │
    └── Minecraft/
        ├── Velocity/
        ├── smp/
        └── lobby/
```

---

## Requirements

Minimum requirements:

```text
Java runtime compatible with the project build
Maven for building from source
Network access for cloudflared auto-download
Linux container recommended
```

Optional requirements:

```text
cloudflared binary for Cloudflare Tunnel
Allocated public port if using Gateway
Cloudflare account/token only for named tunnel or stable custom domain workflows
```

Guest Quick Tunnel does **not** require a Cloudflare token.

---

## Build

Build with Maven:

```bash
mvn -U clean package
```

Check version after running MJT:

```text
.mjt --version
```

Expected version:

```text
Mini Java Terminal v3.0.0-SNAPSHOT+1
```

---

## Quick Start

After starting MJT, check help:

```text
.mjt help
```

Install or check `cloudflared`:

```text
.mjt system install cloudflared
.mjt system cloudflared check
```

Create a guest website:

```text
.mjt website guest create
```

Show guest sites:

```text
.mjt website guest list
```

Show a specific guest site:

```text
.mjt website guest show <guest-id>
```

---

## Command Model

MJT uses strict routing.

```text
.mjt <command>       MJT internal command
.command <command>   Force shell command
no prefix            Minecraft console input only when target mode is active
```

Examples:

```text
.mjt help
.mjt website list
.mjt website guest create
.command ls
.mjt minecraft start
.command minecraft
```

No-prefix shell execution is intentionally disabled for safety.

---

## Website Service

Website sites run locally. They are not public by themselves unless exposed through Cloudflare Tunnel or another proxy.

Default local site:

```text
main -> http://127.0.0.1:8081
root -> /home/container/server/website/www/main
```

Website commands:

```text
.mjt website list
.mjt website show main
.mjt website add docs 127.0.0.1 8082 /home/container/server/website/www/docs
.mjt website start docs
.mjt website stop docs
.mjt website restart docs
.mjt website set docs spa true
```

Legacy HTTP commands remain supported:

```text
.mjt http site list
.mjt http site add ...
.mjt http site start ...
```

---

## Guest Quick Tunnel

Guest mode is for temporary preview websites.

Command:

```text
.mjt website guest create
```

MJT will:

```text
1. Generate a guest ID.
2. Create a guest website folder.
3. Generate a default index.html.
4. Pick a free local port from 8091 upward.
5. Start a local HTTP site on 127.0.0.1:<port>.
6. Start cloudflared quick tunnel.
7. Parse the trycloudflare.com URL.
8. Save the public URL to guest config.
```

Example output concept:

```text
Guest website created

ID      : guest-a8f31
Root    : /home/container/server/website/www/guest/guest-a8f31/main
Local   : http://127.0.0.1:8091
Public  : https://random-name.trycloudflare.com
Mode    : Cloudflare Quick Tunnel
```

Important:

```text
Guest URLs are temporary.
A trycloudflare.com URL may change after MJT, cloudflared, or container restart.
```

Guest commands:

```text
.mjt website guest list
.mjt website guest show <guest-id>
.mjt website guest stop <guest-id>
.mjt website guest restart <guest-id>
.mjt website guest remove <guest-id>
```

---

## Cloudflared Auto Installer

MJT includes a downloader for `cloudflared`.

Command:

```text
.mjt system install cloudflared
```

The installer should:

```text
1. Detect operating system.
2. Detect CPU architecture.
3. Select a matching cloudflared binary.
4. Download it into MJT/system/downloads/cloudflared.
5. Mark it executable on Linux.
6. Run cloudflared --version.
7. Save the working path to tunnel.cloudflared.path.
```

Useful commands:

```text
.mjt system download cloudflared
.mjt system cloudflared check
.mjt system cloudflared show
.mjt cloudflared install
.mjt tunnel binary install
```

Recommended download folder:

```text
/home/container/MJT/system/downloads/cloudflared
```

---

## Cloudflare Tunnel Modes

MJT supports three Cloudflare Tunnel modes.

### Quick Mode

Used for guest preview links.

```text
cloudflared tunnel --url http://127.0.0.1:<port>
```

No token required.

### Token Mode

Used for named tunnels from Cloudflare Dashboard/API.

```text
cloudflared tunnel run --token <token>
```

Requires a tunnel token.

### Config Mode

Used for named tunnel config files and stable hostname routing.

```text
cloudflared tunnel --config <config.yml> run <tunnel-name-or-id>
```

Requires named tunnel setup.

---

## Gateway Router

Gateway is a TCP router/forwarder. It should not be the default public web path.

Recommended role:

```text
Minecraft TCP fallback
SSH/SFTP proxy
manual TCP route forwarding
```

Recommended defaults:

```properties
gateway.route.http.enabled=false
gateway.route.https.enabled=false
gateway.tcp.default=close
```

Commands:

```text
.mjt gateway show
.mjt gateway start
.mjt gateway stop
.mjt gateway route add mc 127.0.0.1 25565
.mjt gateway default mc
```

---

## SSH/SFTP

MJT includes embedded SSH/SFTP support.

Recommended default:

```properties
ssh.host=127.0.0.1
ssh.port=2022
ssh.root=/home/container
ssh.terminal.mode=basic
```

Commands:

```text
.mjt ssh show
.mjt ssh set host 127.0.0.1
.mjt ssh set port 2022
.mjt ssh set user admin
.mjt ssh set pass <password>
.mjt ssh set root /home/container
.mjt ssh set mode basic
.mjt ssh set mode real-tty
.mjt ssh start
.mjt ssh stop
.mjt ssh status
```

---

## Minecraft Target

MJT can run Minecraft as a managed target process.

Commands:

```text
.mjt minecraft start
.mjt minecraft start <custom-command>
.mjt minecraft stop
.mjt minecraft kill
.mjt minecraft status
```

Recommended workspace:

```text
/home/container/server/Minecraft
```

When Minecraft route mode is active, no-prefix input goes to the Minecraft process console.

---

## KeepAlive Bot

MJT includes a Minecraft KeepAlive bot service.

Commands:

```text
.mjt bot show
.mjt bot start
.mjt bot stop
.mjt bot set enabled true
.mjt bot set host 127.0.0.1
.mjt bot set port 25565
.mjt bot set username MJT_Renew
.mjt bot set reconnect 30
```

The bot may auto-start and auto-stop with the Minecraft target depending on config.

---

## Configuration Files

Main config directory:

```text
/home/container/MJT
```

Important files:

```text
MJT/core/app.properties
MJT/services/http/http.properties
MJT/services/http/sites/sites.properties
MJT/services/cloudflare/tunnel/tunnel.properties
MJT/services/cloudflare/ddns-public-ipv4/ddns.properties
MJT/services/gateway/gateway.properties
MJT/services/tcp/tcp-routes.properties
MJT/services/ssh/ssh.properties
MJT/services/minecraft/minecraft.properties
MJT/services/bot/keepalive.properties
```

Important tunnel keys:

```properties
tunnel.enabled=false
tunnel.provider=cloudflare
tunnel.mode=quick
tunnel.autoStart=false
tunnel.cloudflared.path=cloudflared
tunnel.local.url=http://127.0.0.1:8081
tunnel.publicUrl=
tunnel.token=
```

Important guest keys:

```properties
website.guest.ids=guest-a8f31
website.guest.rootBase=/home/container/server/website/www/guest
website.guest.nextPort=8091
website.guest.guest-a8f31.root=/home/container/server/website/www/guest/guest-a8f31/main
website.guest.guest-a8f31.local=http://127.0.0.1:8091
website.guest.guest-a8f31.publicUrl=https://example.trycloudflare.com
website.guest.guest-a8f31.status=running
website.guest.guest-a8f31.tunnel=running
```

---

## Security Notes

Guest mode:

```text
No Cloudflare token
No custom domain
Temporary URL
Suitable for preview/demo only
```

Named tunnel/custom domain mode:

```text
Requires Cloudflare setup
May require token
Should be used for stable/public websites
Needs domain verification in future user/workspace design
```

Token handling rules:

```text
Do not log raw tokens
Mask tokens in config/status output
Store tokens only in service config
Remove token when no longer needed
```

---

## Troubleshooting

### Guest create asks for token

Guest Quick Tunnel should not ask for token.

Check tunnel mode:

```text
.mjt tunnel show
.mjt tunnel set mode quick
```

Then retry:

```text
.mjt website guest create
```

### cloudflared is missing

Install it:

```text
.mjt system install cloudflared
```

Or set path manually:

```text
.mjt tunnel set cloudflared /home/container/MJT/system/downloads/cloudflared/cloudflared
```

### Guest URL stays pending

Check cloudflared output and process status:

```text
.mjt system cloudflared check
.mjt website guest show <guest-id>
.mjt website guest restart <guest-id>
```

### Website still uses /home/container/MJT/www

This is an old config path. Move content to:

```text
/home/container/server/website/www
```

Then update site root:

```text
.mjt website set main root /home/container/server/website/www/main
```

### Gateway still forwards HTTP

Disable HTTP route if using Cloudflare Tunnel for web:

```text
.mjt gateway set gateway.route.http.enabled false
```

---

## Roadmap

Planned areas:

```text
custom domain verification
named tunnel stable mode
per-user workspace system
Cloudflare token isolation per workspace
Paper/Spigot plugin adapter
rich console dashboard
better process monitor for guest tunnels
```

---

## Changelog

See [CHANGE.md](./CHANGE.md).
Or **Full Changelog**: https://github.com/mjt-project/Mini-Java-Terminal/compare/2.5.0...3.0.0-SNAPSHOT+1
