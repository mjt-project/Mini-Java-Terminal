# Mini Java Terminal

> A lightweight Java terminal panel for command execution, logging, Cloudflare DDNS, and runtime utilities.

## Overview

Mini Java Terminal is a simple Java console application for running system commands through a server-panel style terminal. It is designed for learning, controlled testing, and observing how Java handles command execution, process output, working directories, and logs.

This project is intended for authorized environments only.

## Features

* Interactive command input
* Real-time command output
* Working directory control with `cd` and `pwd`
* Automatic log files in `logs/`
* ANSI-colored terminal messages
* Configurable command timeout
* Basic blocking for commands that may freeze panel consoles
* Public IPv4 checking
* Cloudflare DDNS support

## Cloudflare DDNS

Cloudflare DNS update support is completed.

Mini Java Terminal can update a Cloudflare DNS A record to the current public IPv4 address of the panel host.

Available commands:

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

Configuration is stored in:

```text
terminal-state.properties
```

Do not commit this file to GitHub because it may contain private tokens.

## Build

```bash
mvn clean package
```

Output:

```text
target/server.jar
```

## Run

```bash
java -jar target/server.jar
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

## Safety Notice

This project can execute system commands with the permissions of the user running the Java process. Use it only in environments where you have permission.

It is not a hacking tool, sandbox escape, privilege escalation tool, malware loader, miner, or bypass tool.

## License

GPL-3.0-or-later
