# Mini Java Terminal

> A lightweight Java terminal panel for command execution, logging, Cloudflare DDNS, SSH/SFTP access, and runtime utilities.

## Version

```text
v2.2.24
```

This version includes small refactors and runtime improvements before the Web Panel feature is added.

## Overview

Mini Java Terminal is a lightweight Java console application designed to run controlled command execution through a server-panel style terminal.

The project is built for learning, authorized testing, and managing simple runtime utilities in a controlled environment.

Core goals:

* Keep the terminal runtime small and readable
* Route commands through a shared command center
* Support logs, working directories, timeout control, and command guards
* Provide Cloudflare DDNS support
* Provide SSH/SFTP access through the Java runtime
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

## Project Structure

```text
mini-java-terminal/
├── scr/
│   └── terminal/
│       ├── Main.java
│       │
│       ├── command/
│       │   ├── CommandCenter.java
│       │   └── CommandContext.java
│       │
│       ├── services/
│       │   ├── CloudflareDnsService.java
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
└── .gitignore
```

## Folder Roles

```text
Main.java
→ Application startup and console input loop.

command/
→ Routes user input to the correct internal command or service.

services/
→ Feature-level services such as Cloudflare DDNS and SSH/SFTP.

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
* Public ports if SSH/SFTP access is needed

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
0  = no timeout
60 = stop command after 60 seconds
300 = stop command after 5 minutes
```

## Runtime Files

The app may generate:

```text
logs/
terminal-state.properties
ssh-hostkey.ser
```

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
