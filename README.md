# Mini Java Terminal

> A lightweight Java terminal panel for command execution, logging, Cloudflare DDNS, SSH/SFTP access, single-port Gateway routing, and runtime utilities.

## Version

```text
v2.3.22
```

## Overview

Mini Java Terminal is a lightweight Java console application designed to run controlled command execution through a server-panel style terminal.

The project is built for learning, authorized testing, and managing simple runtime utilities in a controlled environment.

Core goals:

* Keep the terminal runtime small and readable
* Route commands through a shared command center
* Support logs, working directories, timeout control, and command guards
* Provide Cloudflare DDNS support
* Provide SSH/SFTP access through the Java runtime
* Provide a single-port Gateway for HTTP, SSH/SFTP, and manual TCP routing
* Prepare the architecture for a future Web Panel

## Features

### Terminal Runtime

* Interactive command input
* Real-time command output
* Working directory control with `cd` and `pwd`
* Clear console support
* Runtime command timeout
* Automatic log files
* Basic command guard for commands that may freeze or misuse panel consoles

### Cloudflare DDNS

Cloudflare DDNS support is completed.

Mini Java Terminal can update a Cloudflare DNS A record to the current public IPv4 address of the panel host.

Supported commands:

```text
cloudflare-set token <token>
cloudflare-set zone <zone_id>
cloudflare-set name <domain>
cloudflare-set proxied false
cloudflare-set ttl 120
cloudflare-set interval 300
cloudflare-show
cloudflare-ddns-once
cloudflare-ddns-start
cloudflare-ddns-stop
cloudflare-ddns-status
```

The DNS record ID can be detected automatically when possible.

### SSH / SFTP Runtime Service

Mini Java Terminal includes an embedded SSH/SFTP service using Apache MINA SSHD.

SSH and SFTP share the same configured port.

Supported commands:

```text
ssh-set host 0.0.0.0
ssh-set port <port>
ssh-set user <username>
ssh-set pass <password>
ssh-set root <folder>
ssh-show
ssh-start
ssh-stop
ssh-status
```

SFTP compatibility aliases are also supported:

```text
sftp-set
sftp-show
sftp-start
sftp-stop
sftp-status
```

Connect using SSH:

```bash
ssh <username>@<domain-or-ip> -p <port>
```

Connect using SFTP:

```bash
sftp -P <port> <username>@<domain-or-ip>
```

### Gateway Service

Mini Java Terminal includes an experimental Gateway Service.

The Gateway Service is designed for restricted hosting environments where only one public TCP port is available.

The Gateway can listen on the public `SERVER_PORT` and route different protocols internally.

Supported Gateway behavior:

```text
HTTP request  -> handled directly inside server.jar
SSH/SFTP      -> proxied to a local SSH/SFTP service
TCP fallback  -> proxied to a manually configured TCP route
```

This allows HTTP and SSH/SFTP to work through the same public port.

Example:

```text
PUBLIC_IP:PUBLIC_PORT
        │
        ▼
GatewayService
        ├── HTTP      -> internal HTTP handler
        ├── SSH/SFTP  -> 127.0.0.1:2022
        └── TCP route -> 127.0.0.1:<custom_port>
```

The public Gateway port is read from:

```text
SERVER_PORT
```

If `SERVER_PORT` is not available, the fallback port is:

```text
4848
```

## Gateway Commands

Supported Gateway commands:

```text
gateway-help
gateway-show
gateway-set <key> <value>
gateway-default <route|close>
gateway-route-add <name> <host> <port>
gateway-route-remove <name>
gateway-route-enable <name>
gateway-route-disable <name>
```

### Gateway Command Examples

Add a Minecraft Java backend route:

```text
gateway-route-add mc 127.0.0.1 25565
gateway-default mc
gateway-show
```

Disable the default TCP backend:

```text
gateway-default close
```

Disable a route:

```text
gateway-route-disable mc
```

Enable a route:

```text
gateway-route-enable mc
```

Remove a route:

```text
gateway-route-remove mc
```

Add a Velocity backend route:

```text
gateway-route-add velocity 127.0.0.1 25577
gateway-default velocity
```

### Gateway Configuration Example

Gateway configuration is stored in:

```text
terminal-state.properties
```

Example configuration:

```properties
gateway.http.enabled=true

gateway.ssh.enabled=true
gateway.ssh.host=127.0.0.1
gateway.ssh.port=2022

gateway.tcp.enabled=true
gateway.tcp.default=mc
gateway.tcp.routes=mc

gateway.tcp.mc.enabled=true
gateway.tcp.mc.host=127.0.0.1
gateway.tcp.mc.port=25565
```

To close all unknown TCP traffic:

```properties
gateway.tcp.default=close
```

To add multiple manual TCP routes:

```properties
gateway.tcp.routes=mc,velocity

gateway.tcp.mc.enabled=true
gateway.tcp.mc.host=127.0.0.1
gateway.tcp.mc.port=25565

gateway.tcp.velocity.enabled=true
gateway.tcp.velocity.host=127.0.0.1
gateway.tcp.velocity.port=25577
```

## Recommended Single-Port Setup

When using the Gateway, the public port should be owned by `GatewayService`.

Example:

```text
Gateway public:
0.0.0.0:${SERVER_PORT}

SSH/SFTP internal:
127.0.0.1:2022
```

Recommended SSH/SFTP setup:

```text
ssh-stop
ssh-set host 127.0.0.1
ssh-set port 2022
ssh-set user <username>
ssh-set pass <password>
ssh-set root /home/container
ssh-start
```

External users can still connect through the public Gateway port:

```bash
ssh <username>@<domain-or-ip> -p <public_port>
```

```bash
sftp -P <public_port> <username>@<domain-or-ip>
```

HTTP can also be accessed through the same public port:

```text
http://<domain-or-ip>:<public_port>
```

## Basic Commands

```text
help
pwd
cd <folder>
clear
public-ip
timeout
timeout <seconds>
shutdown-terminal
```

Timeout behavior:

```text
0   = no timeout
60  = stop command after 60 seconds
300 = stop command after 5 minutes
```

## Project Structure

```text
mini-java-terminal/
├── src/
│   └── terminal/
│       ├── Main.java
│       │
│       ├── command/
│       │   ├── CommandCenter.java
│       │   └── CommandContext.java
│       │
│       ├── services/
│       │   ├── CloudflareDnsService.java
│       │   ├── GatewayService.java
│       │   └── SshServerService.java
│       │
│       └── system/
│           ├── ShellRunner.java
│           ├── PublicIpService.java
│           ├── LogService.java
│           ├── StateStore.java
│           ├── CommandGuard.java
│           └── RuntimeConfig.java
│
├── scripts/
│   └── auto-build.ps1
│
├── dist/
├── logs/
├── target/
├── pom.xml
├── README.md
├── WhatNew.md
└── .gitignore
```

## Folder Roles

```text
Main.java
→ Application startup and console input loop.

command/
→ Routes user input to the correct internal command or service.

services/
→ Feature-level services such as Cloudflare DDNS, Gateway routing, and SSH/SFTP.

system/
→ Core runtime utilities such as shell execution, logging, state storage, public IP checking, command blocking, and runtime configuration.

scripts/
→ Development helper scripts.

dist/
→ Optional output folder for copied release JAR files.

target/
→ Maven build output. This folder should not be committed.

logs/
→ Runtime logs. This folder should not be committed.
```

## Requirements

* Java 17 or newer
* Maven
* A terminal or server panel that supports standard input/output
* Public ports if direct SSH/SFTP access is needed
* A public TCP port if Gateway mode is used

## Build

Build with Maven:

```bash
mvn clean package
```

The correct runnable JAR is:

```text
target/server.jar
```

If using the auto-build script, the JAR is copied to:

```text
dist/server.jar
```

Use `server.jar`, not the small thin JAR such as:

```text
mini-java-terminal-1.0.0.jar
```

The `server.jar` file is the shaded JAR and includes required dependencies such as Apache MINA SSHD.

## Run

```bash
java -jar target/server.jar
```

Or:

```bash
java -jar dist/server.jar
```

## Runtime Files

The app may generate:

```text
logs/
terminal-state.properties
ssh-hostkey.ser
```

## Notes

* Gateway Service is experimental.
* HTTP and SSH/SFTP through the same public TCP port have been tested.
* Manual TCP route support is experimental and depends on the target service.
* Minecraft Java backend routing may require additional testing.
* UDP routing is not included in this version.
* Gateway configuration is reloaded for new incoming connections.
* The project is moving toward a single-port multi-service runtime architecture.

## Security Notice

Mini Java Terminal can execute system commands with the permissions of the user running the Java process.

Use it only in environments where you have explicit permission.

This project is not:

* A hacking tool
* A privilege escalation tool
* A sandbox escape
* A malware loader
* A cryptocurrency miner
* A bypass tool for hosting restrictions

The built-in command guard is only a basic safety layer. Real security must still come from the operating system, container runtime, hosting panel, user permissions, and resource limits.

## Safety Notice

This project can execute system commands with the permissions of the user running the Java process. Use it only in environments where you have permission.

It is not a hacking tool, sandbox escape, privilege escalation tool, malware loader, miner, or bypass tool.

## License

GPL-3.0-or-later
