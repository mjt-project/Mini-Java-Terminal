# Mini Java Terminal

> A lightweight Java terminal runtime for controlled command execution, logging, Cloudflare DDNS, SSH/SFTP access, Gateway routing, Minecraft managed target mode, and KeepAliveBot support.

## Version

```text
v2.5.0
```

## Overview

Mini Java Terminal is a lightweight Java console application designed to run inside server-panel style environments.

It provides a controlled terminal runtime with command routing, logging, SSH/SFTP access, Cloudflare DDNS, Gateway routing, Minecraft managed target support, and an offline-mode KeepAliveBot for supported Minecraft hosting environments.

The project is built for learning, authorized testing, and managing simple runtime utilities in a controlled environment.

Core goals:

* Keep the terminal runtime small and readable
* Route commands through a shared command center
* Support logs, working directory control, timeout control, and command guards
* Provide Cloudflare DDNS support
* Provide SSH/SFTP access through the Java runtime
* Provide an experimental Gateway layer for HTTP, SSH/SFTP proxying, and manual TCP routing
* Provide managed Minecraft server process control
* Provide KeepAliveBot support for `online-mode=false` Minecraft servers

## What's New in v2.5.0

### Added KeepAliveBot

This release adds the new **KeepAliveBot** feature.

KeepAliveBot is an offline-mode Minecraft bot that can join the server as a normal player and help keep the server active when enabled.

It is useful for hosting environments that allow renew / keep-alive behavior.

Main KeepAliveBot features:

* Offline-mode bot login
* Configurable bot username
* Configurable host and port
* Automatic reconnect loop
* Manual start / stop / status commands
* Designed for `online-mode=false` Minecraft servers

### Added Minecraft Managed Target Support

Mini Java Terminal can now start Minecraft as a managed child process instead of running Minecraft directly through `.command`.

This prevents long-running Minecraft startup scripts from blocking the shell command runner.

Recommended flow:

```text
.mjt minecraft start
```

Then send Minecraft console commands directly:

```text
list
say hello
stop
```

## Command Rule

Mini Java Terminal uses a strict command routing rule:

```text
.mjt <command>       = MJT internal command
.command <command>   = Linux / shell command
no prefix            = Minecraft console input only when Minecraft target is running
```

Examples:

```text
.mjt help
.command ls
.command pwd
.mjt minecraft start
```

When Minecraft is running, no-prefix input is sent to the Minecraft console:

```text
list
say hello
op PlayerName
stop
```

Do not start Minecraft through shell commands such as:

```text
.command bash start-minecraft.sh
bash start-minecraft.sh
```

Use managed target mode instead:

```text
.mjt minecraft start
```

## Features

## Terminal Runtime

Mini Java Terminal provides controlled command execution and runtime utilities.

Supported commands:

```text
.mjt help
.mjt --version
.mjt version
.mjt -v
.mjt pwd
.mjt cd <folder>
.mjt clear
.mjt cls
.mjt public-ip
.mjt timeout
.mjt timeout <seconds>
.mjt exit
```

Shell command execution:

```text
.command <shell-command>
```

Examples:

```text
.command ls
.command pwd
.command curl https://example.com
.command bash backup.sh
```

Timeout behavior:

```text
0   = no timeout
60  = stop command after 60 seconds
300 = stop command after 5 minutes
```

The normal `exit` command is blocked to avoid accidentally stopping the runtime.

To intentionally stop Mini Java Terminal, use:

```text
.mjt exit
```

## Minecraft Managed Target

Mini Java Terminal can manage a Minecraft server process as a child process.

Supported commands:

```text
.mjt minecraft start
.mjt minecraft stop
.mjt minecraft kill
.mjt minecraft status
```

Short aliases:

```text
.mjt mc start
.mjt mc stop
.mjt mc kill
.mjt mc status
```

Default start command:

```text
bash start-minecraft.sh
```

Recommended start flow:

```text
.mjt minecraft start
```

After Minecraft starts, send console commands directly:

```text
list
say hello
stop
```

To run shell commands while Minecraft is running:

```text
.command ls
.command pwd
.command curl https://example.com
```

Do not use:

```text
.command bash start-minecraft.sh
```

Use:

```text
.mjt minecraft start
```

## KeepAliveBot

KeepAliveBot is an offline-mode Minecraft bot for supported hosting environments.

It can join the Minecraft server as a normal player and reconnect automatically if disconnected.

This feature is designed for:

```text
online-mode=false
```

Recommended server settings:

```properties
server-ip=127.0.0.1
server-port=25565
online-mode=false
```

Supported bot commands:

```text
.mjt bot show
.mjt bot status
.mjt bot start
.mjt bot stop
.mjt bot set enabled true
.mjt bot set host 127.0.0.1
.mjt bot set port 25565
.mjt bot set username MJT_Renew
.mjt bot set reconnect 30
```

Basic setup:

```text
.mjt bot set enabled true
.mjt bot set host 127.0.0.1
.mjt bot set port 25565
.mjt bot set username MJT_Renew
.mjt bot set reconnect 60
.mjt bot start
```

Expected behavior:

```text
BOT Connecting to 127.0.0.1:25565 as MJT_Renew
BOT Joined Minecraft server as MJT_Renew
```

If the bot is disconnected, it waits and reconnects automatically.

If the bot username is already online, the server may return:

```text
multiplayer.disconnect.duplicate_login
```

In that case, stop the old bot process or change the bot username:

```text
.mjt bot set username MJT_Renew2
.mjt bot stop
.mjt bot start
```

## Improved Help Display

The help output is organized into clear sections.

Main help:

```text
.mjt help
```

Gateway help:

```text
.mjt gateway help
```

Version command:

```text
.mjt --version
```

## Cloudflare DDNS

Mini Java Terminal can update a Cloudflare DNS A record to the current public IPv4 address of the panel host.

Supported commands:

```text
.mjt cloudflare show
.mjt cloudflare set token <token>
.mjt cloudflare set zone <zone_id>
.mjt cloudflare set record <record_id>
.mjt cloudflare set name <domain>
.mjt cloudflare set proxied false
.mjt cloudflare set ttl 120
.mjt cloudflare set interval 300
.mjt cloudflare ddns once
.mjt cloudflare ddns start
.mjt cloudflare ddns stop
.mjt cloudflare ddns status
```

The DNS record ID can be detected automatically when possible.

## SSH / SFTP Runtime Service

Mini Java Terminal includes an embedded SSH/SFTP service using Apache MINA SSHD.

SSH and SFTP share the same configured port.

Supported commands:

```text
.mjt ssh show
.mjt ssh set host <host>
.mjt ssh set port <port>
.mjt ssh set user <username>
.mjt ssh set pass <password>
.mjt ssh set mode <real-tty|basic>
.mjt ssh set root <folder>
.mjt ssh start
.mjt ssh stop
.mjt ssh status
```

SFTP compatibility aliases are also supported:

```text
.mjt sftp show
.mjt sftp set <key> <value>
.mjt sftp start
.mjt sftp stop
.mjt sftp status
```

Connect using SSH:

```bash
ssh <username>@<domain-or-ip> -p <port>
```

Connect using SFTP:

```bash
sftp -P <port> <username>@<domain-or-ip>
```

Recommended local-only SSH setup:

```text
.mjt ssh stop
.mjt ssh set host 127.0.0.1
.mjt ssh set port 2022
.mjt ssh set user <username>
.mjt ssh set pass <password>
.mjt ssh set root /home/container
.mjt ssh start
```

## Gateway Service

Mini Java Terminal includes an experimental Gateway Service.

The Gateway Service is designed for controlled testing environments where one public TCP port is used as the main entry point.

Gateway behavior:

```text
HTTP request  -> handled by Gateway HTTP logic
SSH/SFTP      -> proxied to configured SSH/SFTP target
TCP fallback  -> proxied to manually configured TCP route
```

Example:

```text
PUBLIC_IP:PUBLIC_PORT
        │
        ▼
GatewayService
        ├── HTTP
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
.mjt gateway help
.mjt gateway show
.mjt gateway set <key> <value>
.mjt gateway default <route|close>
.mjt gateway route add <name> <host> <port>
.mjt gateway route remove <name>
.mjt gateway route enable <name>
.mjt gateway route disable <name>
```

## Gateway Core Examples

Show Gateway help:

```text
.mjt gateway help
```

Show Gateway config:

```text
.mjt gateway show
```

Set a Gateway config value manually:

```text
.mjt gateway set <key> <value>
```

Close TCP fallback:

```text
.mjt gateway default close
```

## HTTP Gateway Config Commands

```text
.mjt gateway set gateway.http.enabled true
.mjt gateway set gateway.http.enabled false
.mjt gateway set gateway.http.root /home/container/www
.mjt gateway set gateway.http.index index.html
.mjt gateway set gateway.http.spa true
.mjt gateway set gateway.http.spa false
```

## SSH / SFTP Gateway Proxy Commands

```text
.mjt gateway set gateway.ssh.enabled true
.mjt gateway set gateway.ssh.enabled false
.mjt gateway set gateway.ssh.host 127.0.0.1
.mjt gateway set gateway.ssh.port 2022
```

## Manual TCP Route Commands

Add a Minecraft Java backend route:

```text
.mjt gateway route add mc 127.0.0.1 25565
.mjt gateway default mc
.mjt gateway show
```

Disable the default TCP backend:

```text
.mjt gateway default close
```

Disable a route:

```text
.mjt gateway route disable mc
```

Enable a route:

```text
.mjt gateway route enable mc
```

Remove a route:

```text
.mjt gateway route remove mc
```

Add a Velocity backend route:

```text
.mjt gateway route add velocity 127.0.0.1 25577
.mjt gateway default velocity
```

## Gateway Configuration Example

Gateway configuration is stored in the MJT config folder.

Example configuration:

```properties
gateway.http.enabled=true
gateway.http.root=/home/container/www
gateway.http.index=index.html
gateway.http.spa=false

gateway.ssh.enabled=true
gateway.ssh.host=127.0.0.1
gateway.ssh.port=2022

gateway.tcp.enabled=true
gateway.tcp.default=close
gateway.tcp.routes=
```

Example manual TCP route:

```properties
gateway.tcp.routes=mc
gateway.tcp.default=mc

gateway.tcp.mc.enabled=true
gateway.tcp.mc.host=127.0.0.1
gateway.tcp.mc.port=25565
```

To close all unknown TCP traffic:

```properties
gateway.tcp.default=close
```

## Recommended Single-Port Setup

When using the Gateway, the public TCP port should be owned by `GatewayService`.

Example:

```text
Gateway public:
0.0.0.0:${SERVER_PORT}

SSH/SFTP internal:
127.0.0.1:2022

Minecraft internal:
127.0.0.1:25565
```

Recommended Minecraft Gateway route:

```text
.mjt gateway route add mc 127.0.0.1 25565
.mjt gateway default mc
```

External users can connect to Minecraft through the public Gateway port when the default TCP route is set to `mc`.

HTTP can also be accessed through the same public port:

```text
http://<domain-or-ip>:<public_port>
```

## Project Structure

```text
mini-java-terminal/
├── src/
│   └── main/
│       └── java/
│           └── ...
│               ├── Main.java
│               ├── command/
│               │   ├── CommandCenter.java
│               │   └── CommandContext.java
│               ├── services/
│               │   ├── CloudflareDnsService.java
│               │   ├── GatewayService.java
│               │   └── SshServerService.java
│               └── system/
│                   ├── BuildInfo.java
│                   ├── ShellRunner.java
│                   ├── PublicIpService.java
│                   ├── LogService.java
│                   ├── StateStore.java
│                   ├── CommandGuard.java
│                   ├── TargetProcessService.java
│                   ├── KeepAliveBotService.java
│                   └── RuntimeConfig.java
│
├── scripts/
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
→ Core runtime utilities such as shell execution, logging, state storage, public IP checking, command blocking, managed target processes, KeepAliveBot, and runtime configuration.

scripts/
→ Development helper scripts.

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
* Minecraft server with `online-mode=false` if using KeepAliveBot

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

Use `server.jar`, not the small thin JAR.

The `server.jar` file is the shaded JAR and includes required dependencies.

## Run

```bash
java -jar target/server.jar
```

## Runtime Files

The app may generate:

```text
logs/
mjt-config/
ssh-hostkey.ser
```

## Notes

* Gateway Service is experimental.
* HTTP, SSH/SFTP proxying, and manual TCP fallback are part of the Gateway direction.
* Manual TCP route support is experimental and depends on the target service.
* Minecraft Java routing is experimental.
* KeepAliveBot is designed for `online-mode=false` servers.
* UDP routing is not included in this version.
* Use `.mjt --version` to check the current runtime version.

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
